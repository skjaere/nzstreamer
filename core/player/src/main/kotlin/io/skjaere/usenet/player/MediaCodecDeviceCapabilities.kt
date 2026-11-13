package io.skjaere.usenet.player

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.DeviceCapabilities
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/** A decoder of one MIME type, as a decoder list gives it. */
internal class Decoder(
    /** Whether the player takes it for a hardware decoder (Media3's mark; only Media3's list says). */
    val hardware: Boolean,
    /** The profiles it lists, each with the highest level it lists for it (MediaCodecInfo.CodecProfileLevel's constants). */
    val profiles: Map<Int, Int>,
    /** Whether it plays a frame this size at film rate; null when it hides its limits. */
    val plays: ((width: Int, height: Int) -> Boolean)?,
)

/**
 * Decoder support and size limits, plus audio passthrough over HDMI (where a TV or receiver decodes AC3/DTS/TrueHD), on
 * [display]. The decoders come from two lists: MediaCodecList's, every decoder there is, for what plays at all; and
 * Media3's, the ones its renderers choose from (none that needs secure playback), for what plays in hardware. Both are
 * queried once, in the constructor; MediaCodecList is slow, so build it off the main thread ([DeviceCapabilitiesLoader]
 * does), and move it to the display as it is now with [on], and to the audio route as it is now with [with].
 */
@OptIn(UnstableApi::class)
class MediaCodecDeviceCapabilities private constructor(
    /** Each MIME type's decoders as MediaCodecList lists them (lower case): software, secure and aliases among them. */
    private val listed: Map<String, List<Decoder>>,
    /** Each video MIME type's decoders as Media3 lists them for playback; a type whose query failed is left out. */
    private val playback: Map<String, List<Decoder>>,
    /** What the TV or receiver takes over HDMI: as the probe read it, or as the route changed since ([with]). */
    val passthrough: Passthrough,
    val display: DisplayFacts,
    /**
     * The same decoders' summary, when [on] moves them to another display (it doesn't depend on the display), or [with]
     * to another route (the same but for what passes through).
     */
    shared: Lazy<DecoderSummary>?,
) : DeviceCapabilities {

    internal constructor(
        listed: Map<String, List<Decoder>>,
        playback: Map<String, List<Decoder>>,
        passthrough: Passthrough,
        display: DisplayFacts,
    ) : this(listed, playback, passthrough, display, shared = null)

    constructor(context: Context) : this(listedDecoders(), playbackDecoders(), passthroughOf(context), DisplayFacts.read(context))

    private val profilesByMime: Map<String, Set<Int>> = listed.mapValues { (_, decoders) -> decoders.flatMap { it.profiles.keys }.toSet() }

    override val displayHeight: Int get() = display.height

    /** Unknown counts as every format: no release is held to the screen's HDR, as before the display was asked. */
    override val hdrTypes: Set<Hdr> get() = display.hdr ?: Hdr.entries.toSet()

    override val dolbyVision: Boolean = MimeTypes.VIDEO_DOLBY_VISION in profilesByMime

    private val dolbyVisionProfileList: List<DolbyVisionProfile> = playback[MimeTypes.VIDEO_DOLBY_VISION].orEmpty()
        .flatMap { it.profiles.entries }
        .groupBy({ it.key }, { it.value })
        .map { (profile, levels) -> DolbyVisionProfile.of(profile, levels.max()) }
        .sortedBy { it.codecs }

    /** The Dolby Vision profiles the player's decoders list, each at its highest level, as Media3 names a track's ("dvhe.08.09"). */
    val dolbyVisionProfiles: List<String> = dolbyVisionProfileList.map { it.codecs }

    private val summary: Lazy<DecoderSummary> = shared ?: lazy { summarize() }

    /**
     * What the decoders play, for the log header ([decoderLines]) and This device. Worked out once for one probe's
     * decoders, whatever display they move to: the header reads it on every Copy, Share and crash.
     */
    val decoderSummary: DecoderSummary get() = summary.value

    /** These decoders on [display]. */
    fun on(display: DisplayFacts) = MediaCodecDeviceCapabilities(listed, playback, passthrough, display, summary)

    /**
     * These decoders with [passthrough], the route as it is now (a receiver switched on or off since the probe): the
     * summary's video is these decoders', worked out once, and what it says passes through is the new route's.
     */
    fun with(passthrough: Passthrough): MediaCodecDeviceCapabilities =
        if (passthrough == this.passthrough) this
        else MediaCodecDeviceCapabilities(listed, playback, passthrough, display, lazy { summary.value.copy(audioPassedThrough = passthrough.codecs) })

    override fun canDecode(video: VideoCodec, tenBit: Boolean): Boolean {
        val mime = VIDEO_MIME[video] ?: return false
        val profiles = profilesByMime[mime] ?: return false
        return !tenBit || TEN_BIT_PROFILES[video]?.let { it.any(profiles::contains) } == true
    }

    /**
     * Height of the largest of [FRAMES] that some decoder plays, 0 if none does. A software decoder
     * counts when it claims the size: ExoPlayer falls back to it. Unknown when a decoder hides its limits.
     */
    override fun maxHeight(video: VideoCodec, tenBit: Boolean): Int? {
        if (!canDecode(video, tenBit)) return null
        return heightOf(listed[VIDEO_MIME[video]].orEmpty().filter { it.takes(video, tenBit) })
    }

    /**
     * As [maxHeight], over the decoders Media3 chooses from and takes for hardware (by the platform's flag from API 29, by
     * name below), each asked the size as Media3 asks it (its performance points first, from API 29): the ranker then
     * judges as the player chooses.
     */
    override fun hardwareMaxHeight(video: VideoCodec, tenBit: Boolean): Int? {
        if (!canDecode(video, tenBit)) return null
        val hardware = (playback[VIDEO_MIME[video]] ?: return null).filter { it.hardware && it.takes(video, tenBit) }
        return if (hardware.isEmpty()) 0 else heightOf(hardware)
    }

    override fun canDecode(audio: AudioCodec): Boolean = decodes(audio) || passesThrough(audio)

    private fun decodes(audio: AudioCodec) = AUDIO_MIME[audio].orEmpty().any { it in profilesByMime }

    private fun passesThrough(audio: AudioCodec) = passthrough.takes(audio)

    /** What the log header says of the device: the display, then [decoderLines]. */
    val lines: List<String> get() = listOf(display.toString()) + decoderLines

    /**
     * Each codec's largest size and its hardware's, the Dolby Vision profiles and the codecs nothing decodes; the audio
     * decoded and passed through.
     */
    internal val decoderLines: List<String> get() = decoderSummary.lines

    private fun summarize() = DecoderSummary(
        video = VideoCodec.entries.flatMap { codec ->
            listOf(false, true).filter { canDecode(codec, it) }.map { tenBit -> VideoDecoding(codec, tenBit, maxHeight(codec, tenBit), hardwareMaxHeight(codec, tenBit)) }
        },
        dolbyVision = dolbyVision,
        dolbyVisionProfiles = dolbyVisionProfileList,
        noDecoder = VideoCodec.entries.filterNot { canDecode(it, false) },
        audioDecoded = AudioCodec.entries.filter(::decodes),
        audioPassedThrough = passthrough.codecs,
    )

    override fun toString(): String = lines.joinToString("\n")

    /**
     * The same decoders (one scan's) with an equal route on equal display facts: they answer alike, so the ranking
     * contexts holding them are equal, which is how auto-advance's prefetched search is handed to the play
     * (EpisodeFindHandoff). The route is compared by the codecs it passes through: read again, it says the same.
     */
    override fun equals(other: Any?): Boolean = other is MediaCodecDeviceCapabilities &&
        listed === other.listed && playback === other.playback && passthrough == other.passthrough && display == other.display

    override fun hashCode(): Int = (System.identityHashCode(listed) * 31 + passthrough.hashCode()) * 31 + display.hashCode()

    private fun Decoder.takes(video: VideoCodec, tenBit: Boolean) = !tenBit || profiles.keys.any { it in TEN_BIT_PROFILES[video].orEmpty() }

    /** The largest of [FRAMES] one of [decoders] plays, 0 if none does; null when one hides its limits, or there are none. */
    private fun heightOf(decoders: List<Decoder>): Int? = decoders.maxOfOrNull { decoder ->
        val plays = decoder.plays ?: return null
        FRAMES.firstOrNull { (width, height) -> plays(width, height) }?.second ?: 0
    }

    private companion object {
        /** The frame each resolution class names, largest first: 16:9, and DVD frames for SD. */
        val FRAMES = listOf(7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 720 to 576, 720 to 480)
        const val FILM_FRAME_RATE = 24.0
        val VIDEO_MIME = mapOf(
            VideoCodec.H264 to "video/avc",
            VideoCodec.Hevc to "video/hevc",
            VideoCodec.Av1 to "video/av01",
            VideoCodec.Vp9 to "video/x-vnd.on2.vp9",
            VideoCodec.Mpeg2 to "video/mpeg2",
            VideoCodec.Xvid to "video/mp4v-es",
            // As Media3 reads VC-1 (Matroska's WVC1), and its decoders list it (#64).
            VideoCodec.Vc1 to "video/wvc1",
        )
        val TEN_BIT_PROFILES = mapOf(
            VideoCodec.Hevc to listOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10),
            VideoCodec.Av1 to listOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10),
            VideoCodec.Vp9 to listOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR),
            VideoCodec.H264 to listOf(CodecProfileLevel.AVCProfileHigh10),
        )
        // DTS-HD and DTS:X tracks carry a DTS core that a plain DTS decoder plays.
        val AUDIO_MIME = mapOf(
            AudioCodec.Aac to listOf("audio/mp4a-latm"),
            AudioCodec.Ac3 to listOf("audio/ac3"),
            AudioCodec.Eac3 to listOf("audio/eac3", "audio/eac3-joc"),
            AudioCodec.Dts to listOf("audio/vnd.dts"),
            AudioCodec.DtsHd to listOf("audio/vnd.dts.hd", "audio/vnd.dts"),
            AudioCodec.DtsHdMa to listOf("audio/vnd.dts.hd", "audio/vnd.dts"),
            AudioCodec.DtsX to listOf("audio/vnd.dts.uhd", "audio/vnd.dts.hd", "audio/vnd.dts"),
            AudioCodec.TrueHd to listOf("audio/true-hd"),
            AudioCodec.Flac to listOf("audio/flac"),
            AudioCodec.Opus to listOf("audio/opus"),
            AudioCodec.Mp3 to listOf("audio/mpeg"),
            AudioCodec.Pcm to listOf("audio/raw"),
        )

        fun listedDecoders(): Map<String, List<Decoder>> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { info -> info.supportedTypes.map { type -> type.lowercase() to runCatching { info.getCapabilitiesForType(type) }.getOrNull() } }
            .groupBy({ it.first }, { (_, caps) -> Decoder(hardware = false, profilesOf(caps), caps?.videoCapabilities?.let { video -> { w, h -> video.plays(w, h) } }) })

        fun playbackDecoders(): Map<String, List<Decoder>> = (VIDEO_MIME.values + MimeTypes.VIDEO_DOLBY_VISION).mapNotNull { mime ->
            val infos = try {
                MediaCodecUtil.getDecoderInfos(mime, false, false)
            } catch (e: MediaCodecUtil.DecoderQueryException) {
                return@mapNotNull null
            }
            mime to infos.map { info ->
                val plays = info.capabilities?.videoCapabilities?.let { { w: Int, h: Int -> info.isVideoSizeAndRateSupportedV21(w, h, FILM_FRAME_RATE) } }
                Decoder(info.hardwareAccelerated, profilesOf(info.capabilities), plays)
            }
        }.toMap()

        fun passthroughOf(context: Context): Passthrough =
            Passthrough.of(runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull())

        fun profilesOf(caps: CodecCapabilities?): Map<Int, Int> =
            caps?.profileLevels.orEmpty().groupBy({ it.profile }, { it.level }).mapValues { (_, levels) -> levels.max() }

        /** At film frame rate, after rounding up to the decoder's alignment as ExoPlayer does before it asks. */
        fun VideoCapabilities.plays(width: Int, height: Int): Boolean =
            areSizeAndRateSupported(width.alignUp(widthAlignment), height.alignUp(heightAlignment), FILM_FRAME_RATE)

        fun Int.alignUp(alignment: Int) = (this + alignment - 1) / alignment * alignment
    }
}

/**
 * [MediaCodecDeviceCapabilities] with the decoders probed once and the display read on every [get]. Safe to call from the
 * main thread: the probe (a MediaCodecList scan, hundreds of ms on slow TV boxes) runs on [defaultDispatcher], and the
 * display's facts are a few cheap calls. The decoders are logged once, the display each time its facts change.
 *
 * The HDMI audio route is the probe's until it changes ([audioRouteChanged], from AudioRouteWatcher): a receiver or
 * soundbar switched on after the app started, or a TV that wakes, counts at once for the next ranking, the log header and
 * This device. A route lost counts only once it has held [LOSS_HOLD_MS] ([scope] waits it out), and not before a switch
 * of the TV's refresh rate under way has ended ([whileSwitching]): the TV re-syncs its HDMI as it switches, and a ranking
 * in that moment would take TrueHD releases for unplayable.
 */
class DeviceCapabilitiesLoader internal constructor(
    private val defaultDispatcher: CoroutineDispatcher,
    private val display: () -> DisplayFacts,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher),
    probe: () -> MediaCodecDeviceCapabilities,
) {
    constructor(context: Context) : this(Dispatchers.Default, { DisplayFacts.read(context) }, { LOG.info(it) }, probe = { MediaCodecDeviceCapabilities(context) })

    private val capabilities = lazy { probe().also { it.decoderLines.forEach(log) } }
    private val shown = AtomicReference<DisplayFacts?>()

    // The route and the loss waiting out its hold change together, under the lock; so does what get() answers.
    private val lock = Any()
    /** The route since it changed; null while it is the probe's own. */
    private var route: Passthrough? = null
    private var pendingLoss: Job? = null

    /** How many switches of the TV's refresh rate are under way ([whileSwitching]). */
    private val switching = MutableStateFlow(0)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each change of the route that counted, for This device to read again while it is open. */
    val audioChanges: SharedFlow<Unit> = changes.asSharedFlow()

    /** What the last [get] answered, for the log header, which never asks the device itself; null before the first. */
    @Volatile
    var latest: MediaCodecDeviceCapabilities? = null
        private set

    suspend fun get(): DeviceCapabilities {
        val decoders = if (capabilities.isInitialized()) capabilities.value else withContext(defaultDispatcher) { capabilities.value }
        val now = display()
        if (shown.getAndSet(now) != now) log(now.toString())
        return synchronized(lock) { decoders.on(now).with(route ?: decoders.passthrough).also { latest = it } }
    }

    /**
     * The HDMI audio route as it is [now]. One that passes the same codecs through as the one counting changes nothing,
     * so rankings before and after stay equal (TCR-1); a gain of codecs counts at once; any loss once it has held
     * [LOSS_HOLD_MS] with no other report in between.
     * Each change that counts is logged in the header's words and said on [audioChanges]. Before the decoders are probed
     * there is nothing to compare with, and the probe reads the route itself.
     */
    fun audioRouteChanged(now: Passthrough) {
        synchronized(lock) {
            pendingLoss?.cancel()
            pendingLoss = null
            if (!capabilities.isInitialized()) return
            val current = route ?: capabilities.value.passthrough
            if (now == current) return
            if (now.codecs.containsAll(current.codecs)) return count(now, current)
            pendingLoss = scope.launch {
                delay(LOSS_HOLD_MS)
                switching.first { it == 0 }
                synchronized(lock) {
                    // Another report may have come as this one's hold ended.
                    if (pendingLoss === coroutineContext[Job]) {
                        pendingLoss = null
                        count(now, current)
                    }
                }
            }
        }
    }

    /**
     * Runs [block], a switch of the TV's refresh rate and the wait for it (DisplayModeSwitcher): the TV's HDMI re-sync
     * may drop the route for as long as the TV takes, up to the switch's cap, which can be longer than [LOSS_HOLD_MS]. A
     * route lost meanwhile is judged as the switch ends: back by then, nothing is said; still lost, it counts then.
     */
    suspend fun <T> whileSwitching(block: suspend () -> T): T {
        switching.update { it + 1 }
        try {
            return block()
        } finally {
            switching.update { it - 1 }
        }
    }

    /** Under the lock. */
    private fun count(now: Passthrough, was: Passthrough) {
        route = now
        latest = latest?.with(now)
        log("Audio route changed: ${now.said} passed through over HDMI (was ${was.said})")
        changes.tryEmit(Unit)
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DeviceCapabilitiesLoader::class.java)

        /**
         * How long a route lost must hold before it counts, outside a switch ([whileSwitching]): a shorter drop is taken
         * for the TV's HDMI re-syncing (for HDR, say). How long the Shield's take is a box check.
         */
        const val LOSS_HOLD_MS = 2_000L
    }
}

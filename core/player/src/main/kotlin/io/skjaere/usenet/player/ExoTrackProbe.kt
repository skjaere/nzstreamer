package io.skjaere.usenet.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.slf4j.LoggerFactory

/**
 * Asks ExoPlayer itself whether the file has video and audio this device can play: a silent player
 * with tiny buffers prepares the file until its track list is known (for MKV, the header),
 * then is released. Track support comes from the real renderers, so it includes decoders
 * and HDMI passthrough exactly as playback will see them ([evaluate]). Each video track is
 * logged with how they rated it, its raw codecs string among it.
 *
 * It reads no Matroska index, the Cues, which most files have at their end: preparing went there
 * and back for them, and a 4K remux's 7 MB took about 6 s on a TV box before the player read them
 * again (#229). Without them the probe's file is unseekable, which it doesn't mind; the player keeps them.
 * Its one open would then read on until the probe is released, so its player stops loading once it
 * has the tracks ([TracksOnly]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoTrackProbe(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val timeoutMs: Long = 20_000,
) : TrackProbe {
    private val log = LoggerFactory.getLogger(ExoTrackProbe::class.java)

    override suspend fun probe(target: PlaybackTarget): ProbeResult {
        // The data source finds the file where the target keeps it, by its address.
        target.keep()
        val uri = target.uri
        val thread = HandlerThread("track-probe").apply { start() }
        val handler = Handler(thread.looper)
        var player: ExoPlayer? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<ProbeResult> { continuation ->
                    handler.post {
                        val probePlayer = build(thread.looper)
                        player = probePlayer
                        probePlayer.addListener(object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                if (tracks.groups.isNotEmpty() && continuation.isActive) continuation.resume(answer(tracks, probePlayer.duration))
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                if (!continuation.isActive) return
                                val cause = error.cause ?: error
                                log.info("track_probe: {}", probeFailureLine(error.errorCodeName, cause))
                                continuation.resume(failure(error.errorCode, cause))
                            }
                        })
                        probePlayer.setMediaItem(MediaItem.fromUri(uri))
                        probePlayer.prepare()
                    }
                }
            } ?: run {
                logStall(handler) { player }
                ProbeResult.Failed(UsenetError.Timeout("No track list within ${timeoutMs / 1000} s"))
            }
        } finally {
            handler.post {
                player?.release()
                thread.quitSafely()
            }
        }
    }

    /**
     * Diagnostics for a probe that timed out (Detailed logging only): the probe player's state, read
     * on its own thread, and the stacks of the threads in the app's or Media3's code, so a shared log
     * says where it waited; threads it doesn't know go by their id ([shownThreadName]).
     */
    private suspend fun logStall(handler: Handler, player: () -> ExoPlayer?) {
        if (!log.isDebugEnabled) return
        val state = CompletableDeferred<String>()
        handler.post {
            state.complete(
                player()?.let { p ->
                    "state=${p.playbackState} loading=${p.isLoading} buffered=${p.bufferedPosition} duration=${p.duration} " +
                        "tracks=${p.currentTracks.groups.size} error=${p.playerError?.errorCodeName}"
                } ?: "no player yet",
            )
        }
        log.debug("Track probe stalled: {}", withTimeoutOrNull(1_000) { state.await() } ?: "the probe's thread didn't answer")
        Thread.getAllStackTraces()
            .filter { (thread, stack) -> thread.state != Thread.State.TERMINATED && stack.any { it.className.startsWith(OUR_CODE) || it.className.startsWith(MEDIA3) } }
            .forEach { (thread, stack) ->
                log.debug("Probe stall, thread {} ({}):\n{}", shownThreadName(thread.name, thread.id), thread.state, stack.take(40).joinToString("\n") { "  at $it" })
            }
    }

    /**
     * The probe's player, on [looper]; the test of what it reads once it has the tracks builds one too. It parses every
     * subtitle format while extracting, Media3's default, where a play's leaves PGS and VobSub to the text renderer
     * (PlaySubtitleParserFactory): it reads only the header.
     */
    internal fun build(looper: Looper): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLooper(looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, headerOnly()).setLoadErrorHandlingPolicy(UsenetLoadErrorHandlingPolicy()))
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(TracksOnly(DefaultLoadControl.Builder().setBufferDurationsMs(500, 1_000, 250, 250).build()))
            // No telemetry, as a play's player (PlayerFactory.create).
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                playWhenReady = false
                volume = 0f
            }

    /**
     * [tracks] as the companion's [answer] finds them, each video track (or that there is none) logged first: what a
     * device's log is read for.
     */
    private fun answer(tracks: Tracks, durationMs: Long): ProbeResult {
        val decodes = { format: Format -> decodes(context, format) }
        val triesDolbyVision = { format: Format -> triesDolbyVision(context, format) }
        noVideoLine(tracks)?.let { log.info("track_probe: {}", it) }
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) to group.getTrackSupport(it) } }
            .forEachIndexed { i, (format, support) ->
                val exceeds = support == C.FORMAT_EXCEEDS_CAPABILITIES
                val dolbyVision = exceeds && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && triesDolbyVision(format)
                log.info("track_probe: {}", videoLine(i, format, support, if (exceeds) decodes(format) else null, dolbyVision))
            }
        return answer(tracks, durationMs, decodes, triesDolbyVision)
    }

    /** The player's extractors, but for Matroska's seek for its Cues: the tracks come from the header, in one open. */
    private fun headerOnly() = TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

    companion object {
        /**
         * The probe player's failure: a PlaybackException's [errorCode], and its [cause] (the exception itself when it has
         * none), kept with it for the play's source to read its own failures from (a debrid link no longer to be had). A
         * file the player can't read ([ProbeResult.Failed.unreadable], L317) is told by its cause chain: no extractor took
         * it (bytes that look random, an archive's encrypted file data for one, are of no format), else one took it and
         * failed (Media3's two parsing codes, which don't tell damage from a feature it lacks), else what read it crashed
         * with no read of the release failing under it. Its error is then none a reader could show Media3's words from or
         * take for a lost connection, as any IOException is ([io.skjaere.usenet.core.toUsenetError]).
         */
        internal fun failure(errorCode: Int, cause: Throwable): ProbeResult.Failed {
            val chain = generateSequence(cause) { it.cause.takeIf { next -> next !== it } }.take(16).toList()
            val unreadable = when {
                chain.any { it is UnrecognizedInputFormatException } -> UnreadableFile.UnknownFormat
                errorCode in UNREADABLE -> UnreadableFile.Broken
                chain.any { it is Loader.UnexpectedLoaderException } && chain.none { it is UsenetDataSourceException || it is LinkUnavailable } ->
                    UnreadableFile.ReaderCrashed
                else -> null
            }
            return if (unreadable == null) ProbeResult.Failed(cause.usenetError(), null, cause)
            else ProbeResult.Failed(UsenetError.Unknown(null), unreadable, cause)
        }

        private val UNREADABLE = setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)

        /**
         * The log's line for a file the probe couldn't read, by its error's [errorCodeName] and its [cause] (L320, L331): the
         * screen says it in plain words, so Media3's own are here. An extractor's ([ParserException]) in full, on one line:
         * they name no address (an unrecognised file keeps its URI apart from them). Of what crashed under Media3's loader,
         * which wraps any crash, the classes down its chain and where the innermost one was thrown, never their words, as a
         * debrid read's can name the CDN's host. Anything else by its class alone, as the player's load lines say one.
         */
        internal fun probeFailureLine(errorCodeName: String, cause: Throwable): String {
            val what = when (cause) {
                is ParserException -> "${className(cause)}: ${cause.message.orEmpty().lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ")}"
                is Loader.UnexpectedLoaderException -> {
                    val chain = generateSequence<Throwable>(cause) { it.cause.takeIf { next -> next !== it } }.take(16).toList()
                    val thrown = chain.last().stackTrace.firstOrNull()?.let { " at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }.orEmpty()
                    chain.joinToString(" < ", transform = ::className) + thrown
                }
                else -> className(cause)
            }
            return "can't read the file ($errorCodeName): $what"
        }

        private fun className(e: Throwable): String = e.javaClass.simpleName.ifEmpty { e.javaClass.name }

        /**
         * The probe's answer for [tracks], as [evaluate] finds them with [decodes] and [triesDolbyVision]: with its video's
         * rate and length ([videoOf]), the probe's player's [durationMs], and the file's tracks ([tracksOf]), whatever this
         * device's verdict, as a cast's verdict is its device's own, which may play what this one can't (L129).
         */
        internal fun answer(tracks: Tracks, durationMs: Long, decodes: (Format) -> Boolean, triesDolbyVision: (Format) -> Boolean): ProbeResult {
            val video = videoOf(tracks, durationMs)
            val summary = tracksOf(tracks)
            return when (val verdict = evaluate(tracks, decodes, triesDolbyVision)) {
                is ProbeResult.Playable -> ProbeResult.Playable(video, summary)
                is ProbeResult.NoPlayableAudio -> verdict.copy(tracks = summary, video = video)
                is ProbeResult.NoPlayableVideo -> verdict.copy(tracks = summary, video = video)
                else -> verdict
            }
        }

        /**
         * [tracks]' video, audio and text, each kind in the file's order (the groups'), as plain values ([ProbedTrack]): a
         * subtitle parsed while extracting by its own format, kept in its codecs; Dolby Vision as its own HDR, with the
         * profile its codecs give ("dvhe.08.06"); HDR10 and HLG by the colours' transfer. What the file doesn't say is
         * unknown. Other kinds (cover art) are none of these. The app's player reads a file it plays without a launch's
         * summary this way too, for a Cast device's verdict (L124).
         */
        fun tracksOf(tracks: Tracks): ProbedTracks {
            fun of(type: Int) = tracks.groups.filter { it.type == type }.flatMap { group -> (0 until group.length).map { probed(group.getTrackFormat(it), type) } }
            return ProbedTracks(video = of(C.TRACK_TYPE_VIDEO), audio = of(C.TRACK_TYPE_AUDIO), text = of(C.TRACK_TYPE_TEXT))
        }

        private fun probed(format: Format, type: Int): ProbedTrack {
            val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
            val dolbyVision = mime == MimeTypes.VIDEO_DOLBY_VISION
            val label = when (type) {
                C.TRACK_TYPE_VIDEO -> if (dolbyVision) "Dolby Vision" else VideoTracks.NAMES[mime]
                C.TRACK_TYPE_AUDIO -> audioLabel(mime)
                else -> TrackOptions.subtitleFormat(mime)
            } ?: mime ?: "unknown"
            val video = type == C.TRACK_TYPE_VIDEO
            return ProbedTrack(
                mime = mime,
                label = label,
                language = format.language?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED },
                default = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                width = format.width.takeIf { video && it > 0 },
                height = format.height.takeIf { video && it > 0 },
                bitDepth = format.colorInfo?.lumaBitdepth?.takeIf { video && it > 0 },
                hdr = if (!video) null else if (dolbyVision) Hdr.DolbyVision else HDR_TRANSFERS[format.colorInfo?.colorTransfer],
                dolbyVisionProfile = if (dolbyVision) format.codecs?.split('.')?.getOrNull(1)?.toIntOrNull() else null,
                channels = format.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 0 },
            )
        }

        /** The colours' transfer that makes a video HDR: PQ for HDR10 (HDR10+ says so only in its samples), HLG. */
        private val HDR_TRANSFERS = mapOf(C.COLOR_TRANSFER_ST2084 to Hdr.Hdr10, C.COLOR_TRANSFER_HLG to Hdr.Hlg)

        /**
         * Video first: a file with video none of which plays has no playable video, whatever its audio, as the player
         * would select none of it and play the sound over a black screen. A video track plays when the renderers rate it
         * handled, or beyond what its decoders claim (a level, a size: decoders often under-report them) while one of them
         * [decodes] its codec and profile. Dolby Vision so rated also plays where the player tries a Dolby Vision decoder
         * ([triesDolbyVision]), which on a TV box may not list a profile it plays; where the player plays profile 4, 8, 9
         * or 10 on a plain decoder instead, that decoder must take it. No video at all plays (audio alone). Then the
         * audio: none at all is playable (silent video); otherwise one supported audio track is enough.
         */
        fun evaluate(tracks: Tracks, decodes: (Format) -> Boolean, triesDolbyVision: (Format) -> Boolean): ProbeResult {
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { group -> (0 until group.length).any { plays(group, it, decodes, triesDolbyVision) } }) {
                return ProbeResult.NoPlayableVideo(VideoTracks.labels(video))
            }
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isEmpty() || audio.any { group -> (0 until group.length).any(group::isTrackSupported) }) {
                return ProbeResult.Playable()
            }
            return ProbeResult.NoPlayableAudio(audioLabels(audio))
        }

        /**
         * What the TV's frame-rate match reads of [tracks]' video before the player exists: the selected track's, else the
         * first's (as File details pick it), its rate as File details read it (Matroska's DefaultDuration, which Media3
         * leaves out of the format, else the container's own, an MP4's), the probe player's [durationMs] (C.TIME_UNSET
         * when not known), and whether it is Dolby Vision. Null without video. A rate that isn't a positive number is
         * none: the launch keeps it as JSON, which can't hold NaN or infinity.
         */
        internal fun videoOf(tracks: Tracks, durationMs: Long): ProbedVideo? {
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            val format = video.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }
                ?: video.firstOrNull()?.getTrackFormat(0) ?: return null
            val fps = (format.matroskaFacts?.frameRate ?: format.frameRate.toDouble()).takeIf { it.isFinite() && it > 0 }
            return ProbedVideo(fps, durationMs.takeIf { it != C.TIME_UNSET && it > 0 }, format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION)
        }

        /**
         * The log's line for [tracks] without video: Media3's extractor drops a video track whose codec it doesn't read (VVC
         * or MPEG-1 in Matroska) without a word, and the file then plays as audio alone, so this says why the screen is
         * black. Null when there is video.
         */
        internal fun noVideoLine(tracks: Tracks): String? {
            if (tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) return null
            val audio = audioLabels(tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO })
            return "no video track (audio: ${audio.joinToString().ifEmpty { "none" }})"
        }

        /** [groups]' audio tracks named, with their language: "E-AC-3 (en)". */
        private fun audioLabels(groups: List<Tracks.Group>): List<String> = groups.flatMap { group ->
            (0 until group.length).map { i ->
                val format = group.getTrackFormat(i)
                val codec = audioLabel(format.sampleMimeType) ?: format.sampleMimeType ?: "unknown"
                format.language?.let { "$codec ($it)" } ?: codec
            }
        }

        private fun plays(group: Tracks.Group, track: Int, decodes: (Format) -> Boolean, triesDolbyVision: (Format) -> Boolean): Boolean =
            when (group.getTrackSupport(track)) {
                C.FORMAT_HANDLED -> true
                C.FORMAT_EXCEEDS_CAPABILITIES -> group.getTrackFormat(track).let { (it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && triesDolbyVision(it)) || decodes(it) }
                else -> false
            }

        /**
         * Whether a decoder takes [format]'s codec and profile, its level and size aside: Media3's own check, of the
         * decoders it would choose from (the format's MIME type's and those it falls back to). The renderers listed them
         * as they rated the tracks, so this reads Media3's cache. A failed query takes none.
         */
        internal fun decodes(context: Context, format: Format): Boolean = try {
            MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false).any { it.isFormatFunctionallySupported(context, format) }
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            false
        }

        /**
         * Whether the player tries a Dolby Vision decoder for [format], a Dolby Vision track, as Media3 chooses its decoders:
         * one exists, and Media3 doesn't pass it over for the plain decoders profiles 4, 8, 9 and 10 fall back to, which it
         * does on a display without Dolby Vision when there are such decoders. A failed query tries none.
         */
        internal fun triesDolbyVision(context: Context, format: Format): Boolean = try {
            val passedOver = Hdr.DolbyVision !in DisplayFacts.read(context).hdr.orEmpty() &&
                MediaCodecUtil.getAlternativeDecoderInfos(MediaCodecSelector.DEFAULT, format, false, false).isNotEmpty()
            !passedOver && MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty()
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            false
        }

        /**
         * A video track as the log says it: its MIME type and raw codecs string (a Dolby Vision profile and level, an HEVC
         * profile), its size and ten bits, how the renderers rated it and, rated beyond what its decoders claim, whether one
         * takes its profile ([decodes]) and whether the player tries a Dolby Vision decoder ([triesDolbyVision]):
         * "video 0: video/dolby-vision dvhe.07.06 3840x2160 10-bit, unsupported subtype".
         */
        internal fun videoLine(index: Int, format: Format, support: Int, decodes: Boolean?, triesDolbyVision: Boolean = false): String =
            "video $index: ${format.sampleMimeType} ${format.codecs ?: "-"} ${format.width}x${format.height}" +
                (if ((format.colorInfo?.lumaBitdepth ?: 0) >= 10) " 10-bit" else "") +
                ", ${SUPPORT[support] ?: "support $support"}" +
                decodes?.let { if (it) ", a decoder takes its profile" else ", no decoder takes its profile" }.orEmpty() +
                (if (triesDolbyVision) ", a Dolby Vision decoder is tried" else "")

        private val SUPPORT = mapOf(
            C.FORMAT_HANDLED to "handled", C.FORMAT_EXCEEDS_CAPABILITIES to "exceeds capabilities", C.FORMAT_UNSUPPORTED_DRM to "unsupported DRM",
            C.FORMAT_UNSUPPORTED_SUBTYPE to "unsupported subtype", C.FORMAT_UNSUPPORTED_TYPE to "unsupported type",
        )

        /**
         * An audio track's codec as release names say it ("DTS-HD"); null for one with no name here. DTS's later kinds go by
         * their MIME types' start, as those carry a profile ("audio/vnd.dts.hd;profile=lbr" is DTS Express).
         */
        private fun audioLabel(mime: String?): String? = MIME_LABELS[mime] ?: when {
            mime == null -> null
            mime.startsWith("audio/vnd.dts.uhd") -> "DTS:X"
            mime.startsWith("audio/vnd.dts.hd") -> "DTS-HD"
            else -> null
        }

        private val MIME_LABELS = mapOf(
            "audio/ac3" to "AC-3", "audio/eac3" to "E-AC-3", "audio/eac3-joc" to "E-AC-3 Atmos",
            "audio/vnd.dts" to "DTS", "audio/vnd.dts.hd" to "DTS-HD", "audio/vnd.dts.hd;profile=lbr" to "DTS Express",
            "audio/true-hd" to "TrueHD", "audio/mp4a-latm" to "AAC", "audio/opus" to "Opus",
            "audio/flac" to "FLAC", "audio/mpeg" to "MP3", "audio/mpeg-L2" to "MP2", "audio/mpeg-L1" to "MP1", "audio/raw" to "PCM",
        )

        private const val OUR_CODE = "io.skjaere."
        private const val MEDIA3 = "androidx.media3."

        /**
         * A thread's name as the stall log shows it: one it knows, else only the thread's id. OkHttp
         * names its threads after the hosts they call, an indexer's among them.
         */
        internal fun shownThreadName(name: String, id: Long): String =
            if (name == "main" || name == "track-probe" || KNOWN_THREADS.any(name::startsWith)) name else "thread $id"

        private val KNOWN_THREADS = listOf("ExoPlayer:", "DefaultDispatcher-worker-", "kotlinx.coroutines.")
    }
}

/**
 * The probe's buffering, but that it loads no more once it has the tracks, all the probe wants. With
 * no seek for the Cues its one open read on in order until the probe was released, which comes only
 * after the answer has gone round the probe's caller: in a large release, far enough to start the
 * stream's whole read-ahead. Media3 asks [shouldContinueLoading] once the tracks are selected, and
 * the loader then waits before its next read.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TracksOnly(private val loadControl: LoadControl) : LoadControl by loadControl {
    // Both are called on the playback thread.
    private var tracksSelected = false

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) {
        tracksSelected = true
        loadControl.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        !tracksSelected && loadControl.shouldContinueLoading(parameters)

    // `by` forwards only LoadControl's abstract method, not its default ones: without these the
    // default load control would never hear of the player.
    override fun onPrepared(playerId: PlayerId) = loadControl.onPrepared(playerId)

    override fun onStopped(playerId: PlayerId) = loadControl.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = loadControl.onReleased(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = loadControl.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = loadControl.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = loadControl.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        loadControl.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

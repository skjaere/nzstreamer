package io.skjaere.usenet.app.play

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.skjaere.usenet.player.DecoderDetails
import io.skjaere.usenet.player.DisplayDetails
import io.skjaere.usenet.player.Fact
import io.skjaere.usenet.player.ListedDecoder
import io.skjaere.usenet.player.VideoTracks
import io.skjaere.usenet.player.aspectRatio
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * File details for the play running ([details]), gathered apart from PlayerActivity as AutoAdvance and SkipButton are.
 * Made from the launch at once ([start]), so the release, the file and where it came from show before the player has
 * read anything; then filled as each player the play makes reports the file's video track, its length and the decoder
 * it starts ([follow]), the latest decoder winning after a fallback. The display is read as the panel opens and again
 * as it changes while the panel shows ([showing]). What was read stays when a player stops or fails, so the "Playback
 * stopped" panel can show it, and from then ([stopped]) nothing is said to be still being read; only a new launch
 * starts again, and a player made again (onStart after onStop, recreation) fills it again.
 *
 * Once a player knows its decoder, one line in the log says what this device does with the video: its format, the
 * decoder, the output and the HDR the display shows. Device facts only, never the release.
 */
@OptIn(UnstableApi::class)
internal class FileDetailsCollector(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Media3's decoders of a MIME type for playback; the first ask is slow, so it is asked off the main thread. */
    private val decoders: (String) -> List<ListedDecoder> = DecoderDetails::playback,
    private val display: (Context) -> DisplayDetails = DisplayDetails::read,
) {
    private val log = LoggerFactory.getLogger(FileDetailsCollector::class.java)

    /** What the panel shows; null until a launch has started. */
    var details by mutableStateOf<FileDetails?>(null)
        private set

    private var origin: FileOrigin? = null
    private var playing = FileDetails.Playing()

    /** Bumped by each launch and each player, so what one player reports never lands on another's facts. */
    private var generation = 0

    /** Re-reads the display while the panel shows: a TV's output mode or HDR can change under it. */
    private val displayChanged = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) readDisplay()
        }
    }
    private var listening = false

    /** A new launch: [origin]'s facts, and nothing of the play before. */
    fun start(origin: FileOrigin) {
        generation++
        this.origin = origin
        playing = FileDetails.Playing()
        show()
    }

    /** Where the file came from read again (the settings' indexers, the providers in use now), for the same launch. */
    fun origin(origin: FileOrigin) {
        this.origin = origin
        show()
    }

    /**
     * Listens to [player] for the file's facts: added before its prepare(), so nothing it reports is missed. The facts
     * the player before it read stay until this one reports its own.
     */
    fun follow(player: ExoPlayer) {
        val mine = ++generation
        // This one reads the file anew.
        if (playing.stopped) update { it.copy(stopped = false) }
        // The decoder asked last: a lookup for one before it, started earlier, may end after.
        var asked = 0
        var logged = false
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                // A player stopping, or failing, reports none: what it read stays.
                if (mine != generation || tracks.groups.isEmpty()) return
                update { it.copy(tracksKnown = true, video = videoOf(tracks)) }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = length()

            override fun onPlaybackStateChanged(playbackState: Int) = length()

            private fun length() {
                val ms = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                if (mine == generation && ms != playing.durationMs) update { it.copy(durationMs = ms) }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                val format = player.videoFormat ?: playing.video ?: return
                val ask = ++asked
                // As Media3 read it choosing the decoder: on a display without Dolby Vision it may have passed those over.
                val displayDolbyVision = Hdr.DolbyVision in display(context).facts.hdr.orEmpty()
                scope.launch {
                    val fact = try {
                        withContext(Dispatchers.Default) { DecoderDetails.of(decoderName, format, decoders, displayDolbyVision) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The panel's words aren't worth the play: the decoder by its name alone.
                        log.debug("Couldn't look up decoder {}", decoderName, e)
                        Fact(decoderName)
                    }
                    if (mine != generation || ask != asked) return@launch
                    update { it.copy(decoder = fact) }
                    if (!logged) {
                        logged = true
                        logVideo(format, fact)
                    }
                }
            }
        })
    }

    /** The play stopped for good ("Playback stopped"): what wasn't read by then never will be, until a player is made again. */
    fun stopped() = update { it.copy(stopped = true) }

    /** While the panel shows ([shown]) the display is read now and as it changes; not otherwise, as nothing shows it. */
    fun showing(shown: Boolean) {
        if (shown) readDisplay()
        if (shown == listening) return
        listening = shown
        val displays = context.getSystemService(DisplayManager::class.java) ?: return
        if (shown) displays.registerDisplayListener(displayChanged, Handler(Looper.getMainLooper())) else displays.unregisterDisplayListener(displayChanged)
    }

    private fun readDisplay() = update { it.copy(display = display(context).fact) }

    private fun update(change: (FileDetails.Playing) -> FileDetails.Playing) {
        playing = change(playing)
        show()
    }

    private fun show() {
        details = origin?.let { FileDetails(it, playing) }
    }

    /**
     * "Video: 2160p HEVC 10-bit (hvc1.2.4.L153.B0), 2.39:1, c2.x.hevc.decoder (hardware), output 3840x2160 at 59.94 Hz,
     * display HDR: HDR10": the ratio as File details show it beside the resolution, in ASCII digits.
     */
    private fun logVideo(format: Format, decoder: Fact) {
        val screen = display(context)
        val output = screen.facts.output?.let { size -> "output $size" + screen.refreshRate?.let { " at ${hz(it)} Hz" }.orEmpty() } ?: "output unknown"
        val hdr = screen.facts.hdr?.let { types -> SHOWN.filter { it in types }.joinToString { it.label }.ifEmpty { "none" } } ?: "unknown"
        val aspect = aspectRatio(format.width, format.height, format.pixelWidthHeightRatio, Locale.ROOT)?.let { ", $it" }.orEmpty()
        log.info(
            "Video: {} ({}){}, {}{}, {}, display HDR: {}",
            VideoTracks.label(format), format.codecs ?: format.sampleMimeType, aspect, decoder.value, decoder.note?.let { " ($it)" }.orEmpty(), output, hdr,
        )
    }

    private companion object {
        /** The HDR formats in the order File details name them. */
        val SHOWN = listOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg)

        /** The video track the player selected, else the first: a device that plays none of it still has one, to show why. */
        fun videoOf(tracks: Tracks): Format? {
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            return video.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) } ?: video.firstOrNull()?.getTrackFormat(0)
        }

        /** A refresh rate as a log line has it, in ASCII digits whatever the locale: "59.94", "60". */
        fun hz(rate: Float) = "%.3f".format(Locale.ROOT, rate).trimEnd('0').trimEnd('.')
    }
}

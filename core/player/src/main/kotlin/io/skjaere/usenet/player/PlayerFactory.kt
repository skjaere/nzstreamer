package io.skjaere.usenet.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.skjaere.usenet.player.tv.PlaybackBudget

@androidx.annotation.OptIn(UnstableApi::class)
object PlayerFactory {
    /** How far the controls' rewind button and a remote's rewind key go back (media3's default is 5 s). */
    const val SEEK_BACK_MS = 10_000L

    /** How far the controls' fast-forward button and a remote's fast-forward key go on (media3's default is 15 s). */
    const val SEEK_FORWARD_MS = 10_000L

    /**
     * A player for one play, its sample buffer kept as [loadControl] says: for a play, [PlayerFactory.loadControl] of its
     * [PlaybackBudget]. With [followAudioRoute] (a TV's), Media3 is asked to select the tracks again when its renderers'
     * capabilities change, so that a receiver switched on mid-film moves the audio to passthrough, and a play that fell
     * back to PCM as the TV's HDMI re-synced (androidx/media#2258) recovers, at the cost of a brief break in the sound
     * (Media3's doing, checked on the Shield). A phone's selects once, as it always has. [gaps] hears of the missing
     * stretches its extractors go past, and of the file's index.
     *
     * Media3's own "becoming noisy" handling is off: Android sends that broadcast as HDMI goes away too, and a TV box's
     * HDMI goes away with every re-sync (a refresh-rate switch, Dolby Vision, the TV waking, a receiver switched on), so
     * it paused the next episode as the TV switched between two (the Shield). The play's [BecomingNoisy]
     * hears it instead, and on a TV pauses only for headphones ([NoisyPolicy]).
     *
     * Media3's platform diagnostics are off too: the app sends no telemetry, and Google may collect what a player reports
     * there (L17).
     */
    fun create(context: Context, dataSourceFactory: DataSource.Factory, loadControl: LoadControl, followAudioRoute: Boolean = false, gaps: PlayGaps? = null): ExoPlayer =
        ExoPlayer.Builder(context)
            .apply { if (followAudioRoute) setTrackSelector(DefaultTrackSelector(context, followingTheRoute(context))) }
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setMediaSourceFactory(mediaSourceFactory(dataSourceFactory, TrackFlagsExtractorsFactory(gaps = gaps)))
            .setRenderersFactory(renderersFactory(context))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUsePlatformDiagnostics(false)
            .build()

    private fun followingTheRoute(context: Context) =
        DefaultTrackSelector.Parameters.Builder(context).setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true).build()

    /**
     * A play's media sources, reading with [extractors]: an article no provider has is skipped where the file's index
     * allows ([GapSkippingExtractor]), and one that can't be, or a corrupt one, fails at once instead of after
     * ExoPlayer's three retries; Matroska's track flags reach the formats, so commentary and the like are known by
     * them too. Subtitles are parsed while extracting, Media3's default, but for PGS and VobSub, which the text renderer
     * decodes for the track it shows ([PlaySubtitleParserFactory], [renderersFactory]).
     */
    internal fun mediaSourceFactory(dataSourceFactory: DataSource.Factory, extractors: ExtractorsFactory = TrackFlagsExtractorsFactory()): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory, extractors)
            // Passed on to the extractors: each track's samples go through them.
            .setSubtitleParserFactory(PlaySubtitleParserFactory())
            .setLoadErrorHandlingPolicy(UsenetLoadErrorHandlingPolicy())

    /** A play's renderers: Media3's, falling back to another decoder when one fails, its text renderer decoding as it renders. */
    internal fun renderersFactory(context: Context): RenderersFactory = SubtitleDecodingRenderersFactory(context).setEnableDecoderFallback(true)

    /**
     * How much a play buffers: it starts once 2.5 s are in (5 s after a rebuffer) and loads on to
     * 50 s, or until it holds [PlaybackBudget.targetBufferBytes], whichever comes first, and loads
     * again as soon as it holds less: Media3's own streaming durations. At high bitrates the bytes
     * stop it short of 50 s: time doesn't take precedence over them, as a low-RAM box couldn't
     * spare the memory. At 20 s / 90 s it rested from 90 s, or the bytes, down to 20 s, up to 70 s
     * in which the stream's connections sat idle, long enough for a NAT, a VPN or the provider to
     * drop them. The same on every device, phones on mobile data too: playback holds a
     * high-performance Wi-Fi lock anyway ([C.WAKE_MODE_NETWORK]).
     */
    fun loadControl(budget: PlaybackBudget): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 50_000, 2_500, 5_000)
            .setTargetBufferBytes(budget.targetBufferBytes)
            .build()
}

/**
 * Media3's subtitle parsers for a play's extractors, but none for PGS and VobSub: their samples reach the text renderer
 * as the file has them, for it to decode those of the track it shows ([SubtitleDecodingRenderersFactory]), and the other
 * tracks' are dropped as they are. Parsed while extracting, every bitmap track was decoded on the loader thread, each
 * picture turned into a PNG and back on the way, which took 0.6 of the Shield's four cores for a 4K remux with 74 PGS
 * tracks. The rest stay parsed while extracting, as text is cheap to parse: their cues come with their parser's way of
 * showing them, so SRT, ASS and WebVTT cues that overlap show together (decoded as it renders, each sample's cues
 * replace the last one's), and DVB's parser has the page earlier samples set up when its track is turned on mid-play.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaySubtitleParserFactory(private val parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = format.sampleMimeType !in DECODED_AS_SHOWN && parsers.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = parsers.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        require(supportsFormat(format)) { "${format.sampleMimeType} is decoded as it is shown" }
        return parsers.create(format)
    }

    private companion object {
        val DECODED_AS_SHOWN = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB)
    }
}

/**
 * Media3's renderers, but that the text renderer decodes the subtitle samples it renders, which Media3 calls legacy
 * decoding: a play's media source leaves PGS and VobSub as the file has them ([PlaySubtitleParserFactory]), so only the
 * track shown is decoded. The other formats come parsed, and it shows them as it does by default.
 *
 * The cost, known and kept: Media3's text renderer renders what it decodes only while playing (its renderer has to be
 * started), so a PGS or VobSub cue that a seek or a track change while paused lands in shows once playing resumes, and
 * after a seek while playing once the buffering ends. Text cues show at once, paused or not (SubtitleCuesDeviceTest).
 */
// Media3 marks decoding while rendering experimental and deprecated, though its own CEA-608 and CEA-708 captions still
// decode so.
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal class SubtitleDecodingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        @Suppress("DEPRECATION")
        out.subList(first, out.size).filterIsInstance<TextRenderer>().forEach { it.experimentalSetLegacyDecodingEnabled(true) }
    }
}

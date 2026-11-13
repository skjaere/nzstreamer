package io.skjaere.usenet.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.utils.io.ByteReadChannel
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The track probe over the video fixtures (see the README beside them), through the real data source and renderers:
 * video no decoder of the device takes (Dolby Vision profiles 5 and 7 without a Dolby Vision decoder, MPEG-2 without
 * one, HEVC Main 10 and Dolby Vision profile 8 beside HEVC decoders that don't list Main 10) is no playable video, which
 * today plays its sound over a black screen; H.264, 10-bit AV1 and HEVC at a level above what its decoders list, which a
 * decoder takes, play. A file without playable video still has its tracks read, as a cast's verdict is its device's
 * own (L129). Each case assumes the device's decoders are as the case needs (phone34's are), so another device skips it
 * rather than failing. The last test logs each video track as the probe rates it, and what the probe's player does
 * after its tracks.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ProbeVideoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = error("not used")
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = error("not used")
    })
    private val store = PreparedNzbStore(File(context.cacheDir, "probe-video-test").apply { deleteRecursively() })

    private fun bytes(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    /** The fixture [name] as a one-file release, its stream the fixture's bytes. */
    private fun release(name: String): Pair<DataSource.Factory, UsenetTarget> {
        val data = bytes(name)
        val file = FileCandidate(0, StreamableFile(name, data.size.toLong(), 0, 0, 0, 0))
        val nzb = PreparedNzb(
            // An NZB's id is hex, as the data source's URIs have it.
            id = name.encodeToByteArray().joinToString("") { "%02x".format(it) },
            title = name,
            metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
            candidates = listOf(file),
        )
        val opener = StreamOpener { _, _, range -> OpenedStream(ByteReadChannel(data, range.first.toInt(), (range.last - range.first + 1).toInt()), Job()) }
        val factory = DataSource.Factory { UsenetDataSource(UsenetStreamReader(store, preparer, opener)) }
        return factory to UsenetTarget(PlayCandidate(name, name), byteArrayOf(1), nzb, file, store)
    }

    private fun probe(name: String): ProbeResult = runBlocking {
        val (factory, target) = release(name)
        ExoTrackProbe(context, factory).probe(target).also { Log.i(TAG, "$name: $it") }
    }

    @Test
    fun dolbyVisionProfiles5And7WithoutTheirDecoderAreNoPlayableVideo() {
        assumeFalse("this device has a Dolby Vision decoder", decodes(MimeTypes.VIDEO_DOLBY_VISION))
        assertNoPlayableVideo("90p Dolby Vision profile 5", probe("dv-p5.mkv"))
        assertNoPlayableVideo("90p Dolby Vision profile 7", probe("dv-p7.mkv"))
    }

    /**
     * Without a Dolby Vision decoder Media3 plays profile 8 on an HEVC decoder as HEVC Main 10, rated beyond the HEVC
     * decoders' capabilities where none lists Main 10, as hevc10.mkv is: it doesn't play either.
     */
    @Test
    fun dolbyVisionProfile8WithoutItsDecoderOrHevcMain10IsNoPlayableVideo() {
        assumeFalse("this device has a Dolby Vision decoder", decodes(MimeTypes.VIDEO_DOLBY_VISION))
        assumeFalse("an HEVC decoder here lists Main 10", lists(MimeTypes.VIDEO_H265, MAIN_10_HEVC))
        assertNoPlayableVideo("90p Dolby Vision profile 8", probe("dv-p8.mkv"))
    }

    @Test
    fun mpeg2WithoutADecoderIsNoPlayableVideo() {
        assumeFalse("this device has an MPEG-2 decoder", decodes(MimeTypes.VIDEO_MPEG2))
        assertNoPlayableVideo("90p MPEG-2", probe("mpeg2.mkv"))
    }

    /** Media3 rates it beyond the HEVC decoders' capabilities, and none takes its profile (the last test logs both). */
    @Test
    fun hevcMain10WithoutAMain10DecoderIsNoPlayableVideo() {
        assumeTrue("this device has no HEVC decoder", decodes(MimeTypes.VIDEO_H265))
        assumeFalse("an HEVC decoder here lists Main 10", lists(MimeTypes.VIDEO_H265, MAIN_10_HEVC))
        assertNoPlayableVideo("90p HEVC 10-bit", probe("hevc10.mkv"))
    }

    @Test
    fun h264AndTenBitAv1ThatADecoderTakesPlay() {
        // flags.mkv: H.264 and AAC, which every Android device decodes.
        assertPlayable(probe("flags.mkv"))
        assumeTrue("no AV1 decoder here lists Main 10", lists(MimeTypes.VIDEO_AV1, MAIN_10_AV1))
        assertPlayable(probe("av1-10bit.mkv"))
    }

    /**
     * A playable file's answer carries what the TV's frame-rate match reads before the player exists: the rate from
     * Matroska's DefaultDuration (rate-23976.mkv's 41,708,333 ns), and the length the probe's player knows by its first
     * track list, without the Cues (ffprobe reads 1.128 s).
     */
    @Test
    fun aPlayableFilesAnswerSaysItsVideosFrameRateAndLength() {
        val film = assertPlayable(probe("rate-23976.mkv")).video
        assertNotNull("rate-23976.mkv's video", film)
        assertEquals(24_000.0 / 1001, film!!.fps!!, 1e-6)
        assertTrue("$film", film.durationMs in 1_000L..1_200L)
        assertFalse(film.dolbyVision)
    }

    /**
     * What the probe's player reports for each fixture, in order, and when (#144: it starts the decoder of the video it
     * selects), each video track as the probe logs it: HEVC Main 10, say, which phone34 rates beyond its decoders and the
     * player still tries, or Dolby Vision profile 7, which it selects nothing of. The probe answers at the first track
     * list; this is only logged, as the order is Media3's.
     */
    @Test
    fun theProbesPlayerReportsItsTracksAndAnyErrorInOrder() = runBlocking<Unit> {
        listOf("hevc10.mkv", "dv-p5.mkv", "dv-p7.mkv", "dv-p8.mkv", "mpeg2.mkv", "hevc-level62.mkv", "av1-10bit.mkv", "flags.mkv").forEach { name ->
            val (factory, target) = release(name)
            store.put(target.prepared, target.nzbBytes)
            val thread = HandlerThread("probe-order-test").apply { start() }
            val handler = Handler(thread.looper)
            val events = CopyOnWriteArrayList<String>()
            val tracks = CompletableDeferred<Unit>()
            var player: ExoPlayer? = null
            val start = SystemClock.elapsedRealtime()
            fun event(what: String) {
                events += "${SystemClock.elapsedRealtime() - start} ms $what"
            }
            try {
                handler.post {
                    player = ExoTrackProbe(context, factory).build(thread.looper).apply {
                        addListener(object : Player.Listener {
                            override fun onTracksChanged(changed: Tracks) {
                                val video = changed.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                                event(
                                    "tracks: " + video.withIndex().joinToString { (i, g) ->
                                        val format = g.getTrackFormat(0)
                                        val support = g.getTrackSupport(0)
                                        val exceeds = support == C.FORMAT_EXCEEDS_CAPABILITIES
                                        val decodes = if (exceeds) ExoTrackProbe.decodes(context, format) else null
                                        val dolbyVision = exceeds && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && ExoTrackProbe.triesDolbyVision(context, format)
                                        "${ExoTrackProbe.videoLine(i, format, support, decodes, dolbyVision)}, selected: ${g.isSelected}"
                                    },
                                )
                                if (!changed.isEmpty) tracks.complete(Unit)
                            }

                            override fun onPlayerError(error: PlaybackException) = event("error ${error.errorCodeName}: ${error.cause?.message}")

                            override fun onPlaybackStateChanged(playbackState: Int) = event("state $playbackState")

                            override fun onRenderedFirstFrame() = event("first frame")
                        })
                        setMediaItem(MediaItem.fromUri(UsenetUris.of(target.prepared.id, target.file)))
                        prepare()
                    }
                }
                withTimeout(20_000) { tracks.await() }
                // Long enough for a decoder started after the tracks to fail, as the probe's player is released later.
                delay(2_000)
                Log.i(TAG, "$name, the probe's player: ${events.joinToString("; ")}")
            } finally {
                handler.post {
                    player?.release()
                    thread.quitSafely()
                }
            }
        }
    }

    /**
     * HEVC Main at level 6.2, above the level the emulators' HEVC decoders list for Main (5.2): Media3 rates it beyond
     * their capabilities, and as one takes Main (decoders often under-report their levels) the probe lets it play, as the
     * player tries it.
     */
    @Test
    fun videoAboveTheLevelItsDecodersListPlaysWhereOneTakesItsProfile() {
        // As the extractor reads hevc-level62.mkv (VideoFixturesTest checks it).
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.1.6.L186.90").setWidth(160).setHeight(90).build()
        val support = RendererCapabilities.getFormatSupport(MediaCodecVideoRenderer.supportsFormat(context, MediaCodecSelector.DEFAULT, format))
        assumeTrue("Media3 rates it $support, not beyond this device's decoders", support == C.FORMAT_EXCEEDS_CAPABILITIES)
        assertTrue("no decoder here takes HEVC Main", ExoTrackProbe.decodes(context, format))
        assertPlayable(probe("hevc-level62.mkv"))
    }

    private fun assertPlayable(result: ProbeResult): ProbeResult.Playable {
        assertTrue("$result", result is ProbeResult.Playable)
        return result as ProbeResult.Playable
    }

    /**
     * No playable video here, [found] as the ranker names it, with what the probe read of the file all the same (L129):
     * a fixture's 160x90 video track, its mono AAC and its second.
     */
    private fun assertNoPlayableVideo(found: String, result: ProbeResult) {
        assertTrue("$result", result is ProbeResult.NoPlayableVideo)
        result as ProbeResult.NoPlayableVideo
        assertEquals(listOf(found), result.found)
        assertEquals("$result", listOf(160 to 90), result.tracks?.video?.map { it.width to it.height })
        assertEquals("$result", listOf("AAC" to 1), result.tracks?.audio?.map { it.label to it.channels })
        assertTrue("$result", (result.video?.durationMs ?: 0L) in 1_000L..1_200L)
    }

    private fun codecs(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private fun decodes(mime: String) = codecs(mime).isNotEmpty()

    private fun lists(mime: String, profiles: Set<Int>) =
        codecs(mime).any { c -> c.getCapabilitiesForType(mime).profileLevels.any { it.profile in profiles } }

    private companion object {
        const val TAG = "ProbeVideoDeviceTest"
        val MAIN_10_HEVC = setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        val MAIN_10_AV1 = setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
    }
}

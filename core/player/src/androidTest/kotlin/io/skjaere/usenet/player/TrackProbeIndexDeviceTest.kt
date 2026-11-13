package io.skjaere.usenet.player

import android.media.MediaCodecList
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * What the track probe reads of a Matroska file whose index (its Cues) is at its end, where ffmpeg
 * and most muxers put it. The probe read it as the player does: a 4K remux's is 7 MB, read a few
 * bytes at a time, about 6 s on a TV box before the probe had its track list, then the player read
 * it again. The tracks are in the header. Through the real data source, its stream opener recording
 * the ranges opened.
 *
 * The fixture (`cues-at-end.mkv` in this test's assets, its Cues at byte [CUES] of 95,383) is ten
 * seconds of test pattern with an English E-AC-3 and a French AC-3 track, which an emulator decodes
 * neither of, so the probe's answer lists both, with the file's tracks as it read them (L129):
 * `ffmpeg -f lavfi -i testsrc=duration=10:size=160x90:rate=10
 * -f lavfi -i sine=frequency=440:duration=10:sample_rate=32000 -f lavfi -i sine=frequency=330:duration=10:sample_rate=32000
 * -map 0:v -map 1:a -map 2:a -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow -crf 38 -g 20
 * -c:a:0 eac3 -ac:a:0 2 -b:a:0 32k -c:a:1 ac3 -ac:a:1 2 -b:a:1 32k -metadata:s:a:0 language=eng -metadata:s:a:1 language=fre
 * -fflags +bitexact -flags:v +bitexact -flags:a +bitexact cues-at-end.mkv`.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TrackProbeIndexDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val data = instrumentation.context.assets.open("cues-at-end.mkv").use { it.readBytes() }
    private val file = FileCandidate(0, StreamableFile("cues-at-end.mkv", data.size.toLong(), 0, 0, 0, 0))
    private val nzb = PreparedNzb(
        id = "c0e5",
        title = "cues",
        metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        candidates = listOf(file),
    )
    private val preparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = error("not used")
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = error("not used")
    })
    private val store = PreparedNzbStore(File(context.cacheDir, "probe-index-test").apply { deleteRecursively() })

    /** The ranges the data source opened, in order, and the bytes it read. */
    private val opened = CopyOnWriteArrayList<LongRange>()
    private val bytesRead = AtomicLong()

    private val counting = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            bytesRead.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /** Where the streams that hold back part of the fixture write it from. */
    private val streams = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The real data source over the fixture, [stream] giving each range opened. */
    private fun dataSources(stream: (LongRange) -> OpenedStream = ::whole) = DataSource.Factory {
        val opener = StreamOpener { _, _, range ->
            opened += range
            stream(range)
        }
        UsenetDataSource(UsenetStreamReader(store, preparer, opener)).apply { addTransferListener(counting) }
    }

    private fun whole(range: LongRange) = OpenedStream(ByteReadChannel(data, range.first.toInt(), (range.last - range.first + 1).toInt()), Job())

    private val dataSourceFactory = dataSources()

    @After
    fun tearDown() {
        streams.cancel()
    }

    @Test
    fun theProbeGetsTheTracksFromTheHeaderInOneOpen() = runBlocking<Unit> {
        assumeFalse("this device decodes AC-3 or E-AC-3: the probe would only say it plays", decodes(MimeTypes.AUDIO_AC3) || decodes(MimeTypes.AUDIO_E_AC3))
        val result = ExoTrackProbe(context, dataSourceFactory).probe(UsenetTarget(PlayCandidate("cues", "cues"), byteArrayOf(1), nzb, file, store))
        Log.i(TAG, "probe: opened $opened, read ${bytesRead.get()} bytes")
        assertTrue("$result", result is ProbeResult.NoPlayableAudio)
        result as ProbeResult.NoPlayableAudio
        assertEquals(listOf("E-AC-3 (en)", "AC-3 (fr)"), result.found)
        assertEquals("$result", listOf("H.264"), result.tracks?.video?.map { it.label })
        assertEquals("$result", listOf("E-AC-3" to "en", "AC-3" to "fr"), result.tracks?.audio?.map { it.label to it.language })
        // One range from the start and none at the Cues: it never sought them. What it has read by now
        // is only logged: its player reads on in order until its playback thread has the tracks, here
        // a sample past the first Cluster's start, but how far depends on how its threads run.
        assertEquals("ranges opened", listOf(0L until data.size), opened)
    }

    /**
     * Once it has the tracks the probe's player loads no more, though the probe releases it only
     * after the answer has gone round its caller. With no seek for the Cues its one open read on
     * meanwhile, as fast as the stream came, and in a large release each segment it reached started
     * the stream's whole read-ahead. Here the stream holds back what follows the first Cluster's
     * start until the tracks are known, so where the player stops doesn't depend on how soon it is
     * released.
     */
    @Test
    fun theProbesPlayerLoadsNoMoreOnceItHasTheTracks() = runBlocking<Unit> {
        val known = CompletableDeferred<Unit>()
        // To the first Cluster's ID and size: there the extractor has sent the tracks and the seek map.
        val held = FIRST_CLUSTER + 12
        val factory = dataSources { range ->
            val split = held.coerceIn(range.first, range.last + 1)
            val writer = streams.writer {
                channel.writeFully(data, range.first.toInt(), split.toInt())
                channel.flush()
                known.await()
                channel.writeFully(data, split.toInt(), (range.last + 1).toInt())
            }
            OpenedStream(writer.channel, writer.job)
        }
        // The data source finds the NZB by id in the store, as the probe's does.
        store.put(nzb, byteArrayOf(1))
        val thread = HandlerThread("probe-player-test").apply { start() }
        val handler = Handler(thread.looper)
        var player: ExoPlayer? = null
        val tracks = CompletableDeferred<Unit>()
        try {
            handler.post {
                player = ExoTrackProbe(context, factory).build(thread.looper).apply {
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(changed: Tracks) {
                            if (!changed.isEmpty) tracks.complete(Unit)
                        }
                    })
                    setMediaItem(MediaItem.fromUri(UsenetUris.of(nzb.id, file)))
                    prepare()
                }
            }
            withTimeout(20_000) { tracks.await() }
            val atTracks = bytesRead.get()
            known.complete(Unit)
            delay(1_000)
            Log.i(TAG, "probe's player: opened $opened, read $atTracks bytes by its tracks and ${bytesRead.get()} a second after the rest came")
            assertTrue("read ${bytesRead.get()} bytes, the first Cluster ends at $FIRST_CLUSTER_END", bytesRead.get() < FIRST_CLUSTER_END)
        } finally {
            handler.post {
                player?.release()
                thread.quitSafely()
            }
        }
    }

    /** Only the probe goes without the index: the player seeks by it. */
    @Test
    fun thePlayerStillReadsTheIndex() = runBlocking<Unit> {
        val thread = HandlerThread("index-test-player").apply { start() }
        val handler = Handler(thread.looper)
        val seekable = CompletableDeferred<Boolean>()
        var player: ExoPlayer? = null
        // The data source finds the NZB by id in the store, as the probe's does.
        store.put(nzb, byteArrayOf(1))
        try {
            handler.post {
                val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(500, 1_000, 250, 250).build()
                player = PlayerFactory.create(context, dataSourceFactory, loadControl).apply {
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (!tracks.isEmpty) seekable.complete(isCurrentMediaItemSeekable)
                        }
                    })
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(UsenetUris.of(nzb.id, file)))
                    prepare()
                }
            }
            assertTrue("the player can seek", withTimeout(20_000) { seekable.await() })
            Log.i(TAG, "player: opened $opened, read ${bytesRead.get()} bytes")
            assertTrue("the player opened the Cues: $opened", opened.any { it.first == CUES })
        } finally {
            handler.post {
                player?.release()
                thread.quitSafely()
            }
        }
    }

    private fun decodes(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private companion object {
        const val TAG = "TrackProbeIndexDeviceTest"

        /** Where the fixture's Cues start, from its SeekHead. */
        const val CUES = 95_282L

        /** Where the fixture's first Cluster starts (its ID), and where it ends: 7 bytes of ID and size, then 17,663. */
        const val FIRST_CLUSTER = 821L
        const val FIRST_CLUSTER_END = FIRST_CLUSTER + 7 + 17_663
    }
}

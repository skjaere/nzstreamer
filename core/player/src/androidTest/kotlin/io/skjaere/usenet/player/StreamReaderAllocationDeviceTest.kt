package io.skjaere.usenet.player

import android.os.Debug
import androidx.media3.common.C
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
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.StreamTraffic
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A read of bytes the stream held allocated a lambda on ART (on the JVM, HotSpot's escape analysis
 * hides it): a Matroska index read a few bytes at a time, five million reads on a 4K remux, made
 * some 150 MB of garbage on a TV box, twice a play (the probe's and the player's). The reads are counted
 * for the stream-cost line, as the app's are.
 */
@RunWith(AndroidJUnit4::class)
class StreamReaderAllocationDeviceTest {
    private val data = ByteArray(1_200_000) { it.toByte() }
    private val nzb = PreparedNzb(
        id = "ab12",
        title = "test",
        metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        candidates = listOf(FileCandidate(0, StreamableFile("clip.mkv", data.size.toLong(), 0, 0, 0, 0))),
    )
    private val preparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = error("not used")
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = error("not used")
    })

    @Test
    fun smallReadsOfHeldBytesAllocateNothing() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "allocation-test").apply { deleteRecursively() }
        val store = PreparedNzbStore(dir).apply { runBlocking { put(nzb, byteArrayOf(1)) } }
        val reader = UsenetStreamReader(store, preparer, StreamOpener { _, _, _ -> OpenedStream(ByteReadChannel(data), Job()) }, StreamTraffic())
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val two = ByteArray(2)
        // Warmed up first: the JIT, the channel's first segment.
        repeat(50_000) { reader.read(two, 0, 2) }
        val count = 500_000
        val before = allocated()
        repeat(count) { reader.read(two, 0, 2) }
        val perRead = (allocated() - before).toDouble() / count
        reader.close()
        assertTrue("$perRead bytes allocated a read", perRead < 4.0)
    }

    /** What the runtime has allocated, in all threads: the reads' share dwarfs the rest's here. */
    private fun allocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
}

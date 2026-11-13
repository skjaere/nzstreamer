package io.skjaere.usenet.app.serve

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StreamGaps
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetException
import io.skjaere.usenet.player.GapReports
import io.skjaere.usenet.serve.Upstream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cast's server reading a prepared file over the Usenet stream (L56): each upstream the server opens is one stream of the
 * file from its byte to the end, read ahead into a queue of at most 2 MiB, closed by cancelling its download. Fake
 * openers stand in for the engine, as UsenetStreamReaderTest's do.
 */
class ServedStreamTest {
    private val data = Random(8).nextBytes(3 * MIB + 12_345)
    private val name = "Harbor.Lights.S02E04.1080p.mkv"
    private val nzb = nzbOf(data.size.toLong())
    private val scope = CoroutineScope(SupervisorJob())

    private val opened = CopyOnWriteArrayList<LongRange>()
    private val jobs = CopyOnWriteArrayList<Job>()

    /** What the server's gaps heard, and the app's gap reports. */
    private val told = CopyOnWriteArrayList<LongRange>()
    private val found = CopyOnWriteArrayList<GapReports.Found>()

    /** The file's bytes in [range], all there at once. */
    private val opener = StreamOpener { _, _, range ->
        opened += range
        OpenedStream(ByteReadChannel(data.copyOfRange(range.first.toInt(), range.last.toInt() + 1)), Job().also { jobs += it })
    }

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private fun nzbOf(size: Long) = PreparedNzb(
        "c0ffee", "Harbor Lights S02E04",
        ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        listOf(FileCandidate(0, StreamableFile(name, size, 0, 0, 0, 0))),
    )

    private fun source(
        opener: StreamOpener = this.opener,
        nzb: PreparedNzb = this.nzb,
        traffic: StreamTraffic? = null,
        reports: GapReports? = null,
    ) = ServedStream(nzb, nzb.candidates.single(), opener, scope, traffic, reports)

    /** Everything to the end, as the server reads: [wait] a read, 0 while nothing came. */
    private fun Upstream.readAll(wait: Long = 1_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            val n = read(buffer, 0, buffer.size, wait)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
        }
        throw AssertionError("no end within 10 s, after ${out.size()} bytes")
    }

    /** Waits up to [millis] for [done]; how long it took. */
    private fun within(millis: Long, what: String, done: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!done()) {
            if (System.nanoTime() - start > millis * 1_000_000) throw AssertionError("$what: not within $millis ms")
            Thread.sleep(2)
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `an upstream gives the file's exact bytes from its byte to the end`() {
        assertContentEquals(data, source().open(0) {}.readAll())
        val from = 1_234_567L
        assertContentEquals(data.copyOfRange(from.toInt(), data.size), source().open(from) {}.readAll())
        // Each upstream is one stream of the file, from its byte to the end.
        assertEquals(listOf(0L until data.size, from until data.size), opened.toList())
    }

    @Test
    fun `an upstream at the file's end reads its end and opens no stream`() {
        val upstream = source().open(data.size.toLong()) {}
        assertEquals(-1, upstream.read(ByteArray(10), 0, 10, 1_000))
        assertEquals(emptyList(), opened.toList())
    }

    @Test
    fun `a failing opener fails the read`() {
        val failure = UsenetException(UsenetError.NotConfigured)
        val upstream = source(StreamOpener { _, _, _ -> throw failure }).open(0) {}
        val e = assertFailsWith<IOException> { upstream.readAll() }
        assertSame(failure, e.cause)
        // And again: the server reads no further, but a read after one that failed fails the same.
        assertFailsWith<IOException> { upstream.read(ByteArray(10), 0, 10, 100) }
    }

    @Test
    fun `a stream that ends short fails the read after its bytes, a download's failure with its cause`() {
        val short = source(StreamOpener { _, _, _ -> OpenedStream(ByteReadChannel(data.copyOf(10)), Job()) }).open(0) {}
        val buffer = ByteArray(100)
        assertEquals(10, short.read(buffer, 0, 100, 1_000))
        assertContentEquals(data.copyOf(10), buffer.copyOf(10))
        assertFailsWith<IOException> { short.read(buffer, 0, 100, 1_000) }

        // A download that fails once the server has read what came before: its failure, with its cause.
        val gone = IOException("the provider went away")
        val delivered = CompletableDeferred<Unit>()
        val failing = source(
            StreamOpener { _, _, _ ->
                val channel = ByteChannel()
                scope.launch {
                    channel.writeFully(data, 0, 1_000)
                    channel.flush()
                    delivered.await()
                    channel.close(gone)
                }
                OpenedStream(channel, Job())
            },
        ).open(0) {}
        val read = ByteArrayOutputStream()
        while (read.size() < 1_000) read.write(buffer, 0, failing.read(buffer, 0, 100, 1_000))
        assertContentEquals(data.copyOf(1_000), read.toByteArray())
        delivered.complete(Unit)
        val e = assertFailsWith<IOException> { failing.read(buffer, 0, 100, 1_000) }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it === gone || it.message == gone.message }, "$e")
    }

    /**
     * Waits for [opener] to have been called: the download's coroutine, launched on the IO dispatcher, has started. A close
     * before then cancels a coroutine that never runs, and the case would say nothing of a download.
     */
    private fun reached(opener: CompletableDeferred<Unit>) = within(5_000, "the download never reached its opener") { opener.isCompleted }

    @Test
    fun `close cancels the download within 100 ms`() {
        // A download that waits on its provider: nothing comes.
        val waiting = Job()
        val idleOpened = CompletableDeferred<Unit>()
        val idle = source(StreamOpener { _, _, _ -> idleOpened.complete(Unit); OpenedStream(ByteChannel(), waiting) }).open(0) {}
        reached(idleOpened)
        assertEquals(0, idle.read(ByteArray(10), 0, 10, 50))
        idle.close()
        within(100, "the waiting download cancelled") { waiting.isCancelled }

        // A download held back by its queue: the server reads nothing, the queue is full, and the download holds a chunk more.
        val big = nzbOf(16L * MIB)
        val held = Job()
        val channel = ByteReadChannel(ByteArray(16 * MIB))
        val full = source(StreamOpener { _, _, _ -> OpenedStream(channel, held) }, big).open(0) {}
        within(5_000, "the queue never filled") { 16L * MIB - channel.availableForRead >= 2L * MIB + CHUNK }
        full.close()
        within(100, "the held download cancelled") { held.isCancelled }

        // An opener still opening: its call is cancelled.
        val slowOpened = CompletableDeferred<Unit>()
        val opening = CompletableDeferred<Unit>()
        val slow = source(
            StreamOpener { _, _, _ ->
                slowOpened.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    opening.complete(Unit)
                }
            },
        ).open(0) {}
        reached(slowOpened)
        slow.close()
        within(100, "the opening cancelled") { opening.isCompleted }

        // Nothing of any of them goes on reading.
        within(100, "every download coroutine ended") { scope.coroutineContext.job.children.none() }
        assertFailsWith<IOException> { idle.read(ByteArray(10), 0, 10, 10) }
    }

    @Test
    fun `a read waiting for bytes ends as soon as its upstream is closed`() {
        val upstream = source(StreamOpener { _, _, _ -> OpenedStream(ByteChannel(), Job()) }).open(0) {}
        val ended = AtomicReference<Any?>(null)
        val reader = thread {
            ended.set(runCatching { upstream.read(ByteArray(10), 0, 10, 5_000) }.exceptionOrNull() ?: "returned")
        }
        Thread.sleep(100)
        upstream.close()
        within(100, "the waiting read ended") { !reader.isAlive }
        assertIs<IOException>(ended.get())
        // Closing twice does nothing.
        upstream.close()
    }

    @Test
    fun `at most 2 MiB is read ahead of the server`() {
        val big = nzbOf(16L * MIB)
        val channel = ByteReadChannel(ByteArray(16 * MIB))
        val upstream = source(StreamOpener { _, _, _ -> OpenedStream(channel, Job()) }, big).open(0) {}
        fun taken() = 16L * MIB - channel.availableForRead
        within(1_000, "the queue filled") { taken() >= 2L * MIB }
        Thread.sleep(300)
        val ahead = taken()
        // The queue's 2 MiB, and the chunk the download holds while it waits for room.
        assertTrue(ahead <= 2L * MIB + CHUNK, "$ahead bytes taken from the stream with none read")
        Thread.sleep(300)
        assertEquals(ahead, taken(), "the download went on with the queue full")
        // The server reads a mebibyte: as much more is taken, and no more.
        var read = 0
        val buffer = ByteArray(64 * 1024)
        while (read < MIB) read += upstream.read(buffer, 0, buffer.size, 1_000)
        within(1_000, "the queue filled again") { taken() >= ahead + MIB - CHUNK }
        Thread.sleep(200)
        assertTrue(taken() <= read + 2L * MIB + CHUNK, "${taken()} bytes taken, $read read")
        upstream.close()
    }

    @Test
    fun `each gap is told once to the server and to the gap reports, by file`() = runBlocking<Unit> {
        // Found before the server listened, as the download records a stretch before its zeros reach the channel.
        val gaps = StreamGaps().apply { add(100L..199L) }
        val channel = ByteChannel()
        val upstream = source(StreamOpener { _, _, _ -> OpenedStream(channel, Job(), gaps) }, reports = GapReports().also { reports ->
            reports.listen(nzb.id) { found += it }
            reports.listen("another") { throw AssertionError("another NZB's listener heard of $it") }
        }).open(0) { told += it }
        val zeros = data.copyOf().apply {
            fill(0, 100, 200)
            fill(0, 5_000, 6_000)
        }
        val writer = scope.launch {
            channel.writeFully(zeros, 0, 5_000)
            channel.flush()
            gaps.add(5_000L..5_999L)
            channel.writeFully(zeros, 5_000, zeros.size)
            channel.flushAndClose()
        }
        assertContentEquals(zeros, upstream.readAll())
        writer.join()
        assertEquals(listOf(100L..199L, 5_000L..5_999L), told.toList())
        assertEquals(listOf(100L..199L, 5_000L..5_999L).map { GapReports.Found(nzb.id, 0, name, it) }, found.toList())
    }

    @Test
    fun `the bytes the server takes go through a stream traffic meter, one an upstream`() {
        val traffic = StreamTraffic()
        val upstream = source(traffic = traffic).open(1_000) {}
        val buffer = ByteArray(64 * 1024)
        var read = 0
        while (read < 64 * 1024) read += upstream.read(buffer, read, buffer.size - read, 1_000)
        assertEquals(listOf(64L * 1024), traffic.snapshot().streams.values.toList())
        upstream.readAll()
        upstream.close()
        val snapshot = traffic.snapshot()
        assertEquals(data.size - 1_000L, snapshot.totalBytes)
        assertEquals(1L, snapshot.endedStreams)
        assertEquals(emptyMap(), snapshot.streams)
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val CHUNK = 256 * 1024
    }
}

package io.skjaere.usenet.app.serve

import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.serve.Upstream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cast's server reading a debrid play's file (L217, W73): each upstream one ranged GET from its byte to the end, opened on
 * the caller's thread so a refusal fails the open, read ahead into a queue of at most 2 MiB; the open's length the rest of
 * the file; one open again after a cut that came after some bytes; a final refusal kept, so no later open asks the service;
 * and a close that returns at once, a connection blocked on the CDN aborted off the closing thread, and lets its source go
 * whenever it comes. A fake [ServiceReads] stands in for the app's, a ServiceDataSource of the container's, as
 * ServedStreamTest's openers stand in for the engine.
 */
class ServiceByteSourceTest {
    private val data = Random(9).nextBytes(3 * MIB + 23_456)
    private val size = data.size.toLong()
    private val scope = CoroutineScope(SupervisorJob())
    private val reads = FakeReads()

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private fun source(reads: ServiceReads = this.reads) = ServiceByteSource(size, reads, scope)

    /**
     * The file over HTTP as the fake gives it: each [open] at a byte, its bytes [READ] at a time. [refuse] fails an open as
     * the service's final refusal; [failOpen] fails one as a connection would, once; [lengthOf] is the open's length; each
     * open takes the next of [cuts], the bytes it gives before its read fails (null: none), and the next of [refusing], a
     * refusal of that open alone; [block] holds a read at that byte until the read is aborted (or [release]d).
     */
    private inner class FakeReads : ServiceReads {
        val opens = CopyOnWriteArrayList<Long>()
        val made = CopyOnWriteArrayList<Read>()

        /** Bytes the reads gave, all opens together. */
        val given = AtomicLong()

        @Volatile var refuse: LinkFailure? = null
        @Volatile var failOpen: IOException? = null
        @Volatile var lengthOf: (Long) -> Long? = { size - it }
        val cuts = CopyOnWriteArrayList<Long?>()
        val refusing = CopyOnWriteArrayList<LinkFailure?>()
        @Volatile var block: Long? = null
        val release = CountDownLatch(1)

        override fun make(): Read = Read().also { made += it }

        // A link a test might leak: the source's words must hold none of it.
        override fun toString() = "FakeReads(https://cdn.example.org/dl/0123abcd/Harbor.Lights.S02E04.mkv)"

        inner class Read : ServiceRead {
            private var at = 0L
            private var cut: Long? = null
            private var sent = 0L
            val aborted = AtomicBoolean()
            val closed = AtomicBoolean()
            private val abortedLatch = CountDownLatch(1)

            override fun open(position: Long): Long? {
                opens += position
                refuse?.let { throw LinkUnavailable(it) }
                refusing.removeFirstOrNull()?.let { throw LinkUnavailable(it) }
                failOpen?.let {
                    failOpen = null
                    throw it
                }
                at = position
                cut = cuts.removeFirstOrNull()
                return lengthOf(position)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (aborted.get()) throw IOException("aborted")
                if (block == at) {
                    // A CDN gone silent: the read waits until the connection is let go, or the test lets it on.
                    if (!abortedLatch.await(30, TimeUnit.SECONDS) && !release.await(0, TimeUnit.SECONDS)) throw IOException("a read time-out")
                    if (aborted.get()) throw IOException("the connection was let go")
                }
                cut?.let { if (sent >= it) throw IOException("the body was cut") }
                if (at >= size) return -1
                val n = minOf(length.toLong(), READ.toLong(), size - at, (cut ?: Long.MAX_VALUE) - sent).toInt()
                data.copyInto(buffer, offset, at.toInt(), at.toInt() + n)
                at += n
                sent += n
                given.addAndGet(n.toLong())
                return n
            }

            override fun abort() {
                aborted.set(true)
                abortedLatch.countDown()
            }

            override fun close() {
                closed.set(true)
            }
        }
    }

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

    /** What the server reads until a read fails: the bytes before it, and the failure. */
    private fun Upstream.readToFailure(): Pair<ByteArray, IOException> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = try {
                read(buffer, 0, buffer.size, 1_000)
            } catch (e: IOException) {
                return out.toByteArray() to e
            }
            if (n < 0) throw AssertionError("the end, after ${out.size()} bytes, where a failure was due")
            out.write(buffer, 0, n)
        }
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
    fun `an upstream gives the file's exact bytes from its byte to the end, one GET each`() {
        assertContentEquals(data, source().open(0) {}.readAll())
        val from = 1_234_567L
        assertContentEquals(data.copyOfRange(from.toInt(), data.size), source().open(from) {}.readAll())
        // Each upstream one open from its byte, its source closed once read to the end.
        assertEquals(listOf(0L, from), reads.opens.toList())
        within(1_000, "each read closed") { reads.made.all { it.closed.get() } }
        assertTrue(reads.made.none { it.aborted.get() })
    }

    @Test
    fun `an upstream at the file's end reads its end and asks for nothing`() {
        val upstream = source().open(size) {}
        assertEquals(-1, upstream.read(ByteArray(10), 0, 10, 1_000))
        assertEquals(emptyList(), reads.made.toList())
    }

    @Test
    fun `a refusal at open fails the open, and every later open fails the same with no call`() {
        val bytes = source()
        reads.refuse = LinkFailure.KeyRefused("TorBox")
        val refused = assertFailsWith<LinkUnavailable> { bytes.open(0) {} }
        assertEquals(LinkFailure.KeyRefused("TorBox"), refused.failure)
        assertTrue(reads.made.single().closed.get(), "the refused open's source left open")

        // The service would answer now, but nothing asks it: the refusal is final for this file.
        reads.refuse = null
        val again = assertFailsWith<LinkUnavailable> { bytes.open(1_000_000) {} }
        assertEquals(LinkFailure.KeyRefused("TorBox"), again.failure)
        assertEquals(listOf(0L), reads.opens.toList())
        assertEquals(1, reads.made.size)
    }

    @Test
    fun `a failed open that isn't the service's refusal is asked again by the next open`() {
        val bytes = source()
        reads.failOpen = IOException("a connection refused")
        assertFailsWith<IOException> { bytes.open(0) {} }
        assertTrue(reads.made.single().closed.get())
        assertContentEquals(data, bytes.open(0) {}.readAll())
        assertEquals(listOf(0L, 0L), reads.opens.toList())
    }

    @Test
    fun `an open whose length isn't the rest of the file fails as no working link, kept`() {
        val bytes = source()
        reads.lengthOf = { size - it - 1 }
        val short = assertFailsWith<LinkUnavailable> { bytes.open(4_096) {} }
        assertEquals(LinkFailure.NoWorkingLink, short.failure)
        assertTrue(reads.made.single().closed.get())
        // Another file, or one cut short: the next open doesn't ask again.
        reads.lengthOf = { size - it }
        assertFailsWith<LinkUnavailable> { bytes.open(0) {} }
        assertEquals(1, reads.made.size)

        // An answer that didn't say its length passes.
        val unsaid = FakeReads().apply { lengthOf = { null } }
        assertContentEquals(data, source(unsaid).open(0) {}.readAll())
    }

    @Test
    fun `a cut after some bytes opens the file again once at the byte reached, the bytes unbroken`() {
        val from = 5_000L
        reads.cuts += listOf(300_000L, null)
        assertContentEquals(data.copyOfRange(from.toInt(), data.size), source().open(from) {}.readAll())
        assertEquals(listOf(from, from + 300_000), reads.opens.toList())
        // The cut body let go before the file was opened again, on the download's own thread.
        assertTrue(reads.made[0].closed.get())
        assertFalse(reads.made[0].aborted.get())
    }

    @Test
    fun `a cut with no bytes since the last open fails at its byte, after the bytes before it`() {
        // Cut at once: no open again.
        reads.cuts += listOf(0L)
        val (none, failure) = source().open(7_000) {}.readToFailure()
        assertEquals(0, none.size)
        assertIs<IOException>(failure)
        assertEquals(listOf(7_000L), reads.opens.toList())

        // Cut after some bytes, opened again, and that one cut at once: the server has every byte up to it, then the failure.
        val again = FakeReads().apply { cuts += listOf(100_000L, 0L) }
        val (before, cut) = source(again).open(0) {}.readToFailure()
        assertContentEquals(data.copyOf(100_000), before)
        assertIs<IOException>(cut)
        assertEquals(listOf(0L, 100_000L), again.opens.toList())
        within(1_000, "both closed") { again.made.all { it.closed.get() } }
    }

    @Test
    fun `a refusal as the file is opened again after a cut fails there, and is kept`() {
        val bytes = source()
        reads.cuts += listOf(200_000L)
        reads.refusing += listOf(null, LinkFailure.Removed)
        val (before, failure) = bytes.open(0) {}.readToFailure()
        assertContentEquals(data.copyOf(200_000), before)
        assertEquals(LinkFailure.Removed, generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<LinkUnavailable>().first().failure)
        assertEquals(LinkFailure.Removed, assertFailsWith<LinkUnavailable> { bytes.open(0) {} }.failure)
        assertEquals(listOf(0L, 200_000L), reads.opens.toList())
    }

    @Test
    fun `a close during a waiting read ends it, and the download closes its source itself`() {
        // A body that flows slowly: each read comes back, but the server finds nothing queued for a moment.
        val slow = object : ServiceReads {
            val inner = reads.make()
            override fun make() = object : ServiceRead by inner {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    Thread.sleep(50)
                    return inner.read(buffer, offset, length)
                }
            }
        }
        val upstream = source(slow).open(0) {}
        val ended = AtomicReference<Any?>(null)
        val reader = thread {
            // Waiting for more than the queue has: drained, then waiting.
            ended.set(
                runCatching {
                    val buffer = ByteArray(64 * 1024)
                    while (true) upstream.read(buffer, 0, buffer.size, 5_000)
                }.exceptionOrNull(),
            )
        }
        Thread.sleep(200)
        upstream.close()
        within(100, "the waiting read ended") { !reader.isAlive }
        assertIs<IOException>(ended.get())
        within(1_000, "the source closed") { slow.inner.closed.get() }
        // A read that came back by itself: nothing to abort.
        assertFalse(slow.inner.aborted.get())
        within(1_000, "the download ended") { scope.coroutineContext.job.children.none() }
        // Closing twice does nothing.
        upstream.close()
    }

    /**
     * CRL-1: an upstream closed as soon as it opened, as core:serve closes a head that opened after the cast was stopped,
     * still lets its source go, though its download hadn't run yet. Opened and closed on an IO worker, as Casting's head
     * is: the download waits in that worker's own queue, where no other worker takes it for a while, as the close comes.
     */
    @Test
    fun `an upstream closed as soon as it opened still lets its source go`() {
        runBlocking(Dispatchers.IO) {
            repeat(20) { source().open(it * 1_000L) {}.close() }
        }
        within(2_000, "every source let go") { reads.made.size == 20 && reads.made.all { it.closed.get() } }
        // Its body never read: nothing to abort.
        assertTrue(reads.made.none { it.aborted.get() })
        within(1_000, "the downloads ended") { scope.coroutineContext.job.children.none() }
    }

    /**
     * CRL-2: the close returns at once, whatever thread closes (the main thread, as a cast ends): the moment a read under
     * way is given to come back is waited out off that thread, and the connection is then aborted there.
     */
    @Test
    fun `a close while the download is blocked on a silent CDN returns at once, the connection aborted`() {
        reads.block = 512L * 1024
        val upstream = source().open(0) {}
        within(5_000, "the download reached the silence") { reads.given.get() >= 512L * 1024 }
        Thread.sleep(100)
        val took = measure { upstream.close() }
        // Never the let-go's moment (200 ms), nor the CDN's read time-out.
        assertTrue(took < ServiceByteSource.LET_GO_MS / 2, "the close took $took ms")
        val read = reads.made.single()
        within(1_000, "the blocked read's connection aborted") { read.aborted.get() }
        within(1_000, "the source closed by the download as its read came back") { read.closed.get() }
        within(1_000, "the download ended") { scope.coroutineContext.job.children.none() }
        assertFailsWith<IOException> { upstream.read(ByteArray(10), 0, 10, 10) }
    }

    @Test
    fun `at most 2 MiB is read ahead of the server`() {
        val upstream = source().open(0) {}
        within(1_000, "the queue filled") { reads.given.get() >= 2L * MIB }
        Thread.sleep(300)
        val ahead = reads.given.get()
        // The queue's 2 MiB, and the chunk the download holds while it waits for room.
        assertTrue(ahead <= 2L * MIB + CHUNK, "$ahead bytes read from the CDN with none taken")
        Thread.sleep(300)
        assertEquals(ahead, reads.given.get(), "the download went on with the queue full")
        // The server reads a mebibyte: as much more is read, and no more.
        var read = 0
        val buffer = ByteArray(64 * 1024)
        while (read < MIB) read += upstream.read(buffer, 0, buffer.size, 1_000)
        within(1_000, "the queue filled again") { reads.given.get() >= ahead + MIB - CHUNK }
        Thread.sleep(200)
        assertTrue(reads.given.get() <= read + 2L * MIB + CHUNK, "${reads.given.get()} bytes read, $read taken")
        upstream.close()
    }

    @Test
    fun `a cached file is whole, so gaps are never told`() {
        reads.cuts += listOf(65_536L, null)
        val told = CopyOnWriteArrayList<LongRange>()
        assertContentEquals(data, source().open(0) { told += it }.readAll())
        assertEquals(emptyList(), told.toList())
    }

    @Test
    fun `its words hold no link`() {
        val bytes = source()
        val upstream = bytes.open(0) {}
        listOf(bytes.toString(), upstream.toString()).forEach { words ->
            assertFalse("http" in words || "cdn" in words || "Harbor" in words, words)
        }
        upstream.close()
    }

    private fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val CHUNK = 256 * 1024

        /** What one read of the fake gives at most, as an HTTP body's read gives a socket's worth. */
        const val READ = 16 * 1024
    }
}

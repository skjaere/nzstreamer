package io.skjaere.usenet.serve

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The streams of a served file's source (L56, L76, L101): at most two open, one parked when its answer ends and taken by
 * the next answer at its byte or up to 4 MiB on, the head opened ahead of the first request, a third answer waiting for a
 * stream and then taking the one with the oldest progress but never the head guard's, a parked one closed after 5 minutes,
 * and after a failure at a byte, one new stream there every 5 s. Over [DirectUpstreams] alone with a fake clock, then
 * through the server.
 */
class UpstreamsTest : ServeTestBase() {
    private val mib = 1024L * 1024
    private val size = 512 * mib
    private val now = AtomicLong(1_000_000)
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val source = PatternSource(size)

    private fun upstreams(limits: ServeLimits = ServeLimits(), from: ByteSource = source) =
        DirectUpstreams(from, size, limits, now::get, timer) {}

    @AfterEach
    fun stopTimer() {
        timer.shutdownNow()
    }

    /** [n] bytes from this upstream, which answers from [from] and has given [got] already: checked against the file. */
    private fun Upstream.readChecked(from: Long, n: Long, got: Long = 0): Upstream {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (done < n) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), n - done).toInt(), 100)
            assertTrue(count >= 0, "the upstream ended at ${from + got + done}")
            assertTrue(System.nanoTime() < until, "nothing came from ${from + got + done}")
            assertContentEquals(expected(from + got + done, from + got + done + count - 1), buffer.copyOf(count), "bytes from ${from + got + done}")
            done += count
        }
        return this
    }

    /** One read of this upstream, waiting [millis] at most: how many came. */
    private fun Upstream.poll(millis: Long = 50): Int = read(ByteArray(64 * 1024), 0, 64 * 1024, millis)

    @Test
    fun `a parked stream serves an answer from its byte and up to 4 MiB on, the bytes between dropped`() {
        val upstreams = upstreams()
        upstreams.open(0) {}.readChecked(0, mib).close()
        assertEquals(listOf(mib), upstreams.parked())
        // At its byte: no new stream.
        upstreams.open(mib) {}.readChecked(mib, mib).close()
        assertEquals(listOf(0L), source.opens)
        // 4 MiB on: the 4 MiB between are read and dropped.
        val far = 2 * mib + 4 * mib
        upstreams.open(far) {}.readChecked(far, 1000).close()
        assertEquals(listOf(0L), source.opens)
        assertEquals(listOf(far + 1000), upstreams.parked())
        // A byte further, or one before it, opens another: the parked one stays parked while there is room.
        val beyond = far + 1000 + 4 * mib + 1
        val other = upstreams.open(beyond) {}.readChecked(beyond, 1000)
        assertEquals(listOf(0L, beyond), source.opens)
        assertEquals(listOf(far + 1000), upstreams.parked())
        other.close()
        assertEquals(2, source.open.get())
    }

    @Test
    fun `the head opened ahead serves bytes=0- with no new open`() {
        source.openMillis = 50
        val upstreams = upstreams()
        upstreams.openHead()
        assertEquals(listOf(0L), source.opens)
        assertEquals(listOf(0L), upstreams.parked())
        // A second call while it is parked opens nothing more.
        upstreams.openHead()
        val head = upstreams.open(0) {}.readChecked(0, 3 * mib)
        assertEquals(listOf(0L), source.opens)
        assertEquals(emptyList(), upstreams.parked())
        head.close()
    }

    @Test
    fun `a far answer opens a second stream, and a third waits 10 s, then takes the one with the oldest progress`() {
        val upstreams = upstreams(ServeLimits(headGuardBytes = mib))
        val headTaken = AtomicBoolean()
        // From byte 0 but past the (shrunk) head guard, and its reads' progress the oldest.
        val head = upstreams.open(0) { headTaken.set(true) }.readChecked(0, 2 * mib)
        now.addAndGet(1000)
        val farTaken = AtomicBoolean()
        val far = upstreams.open(100 * mib) { farTaken.set(true) }.readChecked(100 * mib, mib)
        assertEquals(listOf(0L, 100 * mib), source.opens)

        val third = upstreams.open(200 * mib) {}
        assertEquals(0, third.poll(), "a third stream opened")
        now.addAndGet(9_999)
        assertEquals(0, third.poll(), "it waited less than 10 s")
        assertEquals(listOf(0L, 100 * mib), source.opens)
        now.addAndGet(1)
        third.readChecked(200 * mib, mib)
        assertTrue(headTaken.get(), "the oldest progress kept its stream")
        assertFalse(farTaken.get(), "another's stream was taken")
        assertEquals(listOf(0L, 100 * mib, 200 * mib), source.opens)
        source.closeOf(0)
        assertFailsWith<IOException> { head.poll() }
        assertTrue(source.maxOpen.get() <= 2, "${source.maxOpen.get()} open at once")
        far.readChecked(100 * mib, mib, got = mib)
    }

    /**
     * The answer whose stream is taken is told before any of its reads can fail for it, so the server ends it as taken and
     * never as a failure of the source's, which would count against the release (L88). Its server thread may read it just
     * as it is taken: here, while it is being told.
     */
    @Test
    fun `a taken stream's answer is told before its reads can fail`() {
        val upstreams = upstreams(ServeLimits(headGuardBytes = mib))
        val told = AtomicBoolean()
        val seen = LinkedBlockingQueue<String>()
        lateinit var head: Upstream
        head = upstreams.open(0) {
            val reader = thread {
                seen.put(
                    try {
                        "read ${head.poll()}"
                    } catch (_: IOException) {
                        if (told.get()) "failed once told" else "failed before it was told"
                    },
                )
            }
            reader.join(300)
            told.set(true)
        }.readChecked(0, 2 * mib)
        now.addAndGet(1000)
        upstreams.open(100 * mib) {}.readChecked(100 * mib, mib)
        val third = upstreams.open(200 * mib) {}
        now.addAndGet(10_000)
        third.readChecked(200 * mib, mib)
        assertTrue(told.get(), "the head's stream wasn't taken")
        assertEquals("failed once told", seen.poll(5, TimeUnit.SECONDS))
    }

    /** L101: a cut early in the first answer is fatal on the Shield, so its stream is never taken inside the guard. */
    @Test
    fun `never the stream of an answer from byte 0 inside the head guard`() {
        val upstreams = upstreams(ServeLimits(headGuardBytes = mib))
        val headTaken = AtomicBoolean()
        val head = upstreams.open(0) { headTaken.set(true) }.readChecked(0, 100_000)
        now.addAndGet(1000)
        val farTaken = AtomicBoolean()
        upstreams.open(100 * mib) { farTaken.set(true) }.readChecked(100 * mib, 100_000)
        val third = upstreams.open(200 * mib) {}
        now.addAndGet(10_000)
        third.readChecked(200 * mib, 1000)
        assertTrue(farTaken.get())
        assertFalse(headTaken.get(), "the head guard's stream was taken")

        // Two answers from byte 0, both inside the guard: a third waits as long as it takes.
        val guarded = upstreams(ServeLimits(headGuardBytes = mib))
        val first = guarded.open(0) {}.readChecked(0, 1000)
        val second = guarded.open(0) {}.readChecked(0, 1000)
        val waiting = guarded.open(300 * mib) {}
        now.addAndGet(60_000)
        assertEquals(0, waiting.poll(), "a head guard's stream was taken")
        // Once one is past the guard's bytes, it can go.
        first.readChecked(0, mib, got = 1000)
        waiting.readChecked(300 * mib, 1000)
        assertFailsWith<IOException> { first.poll() }
        second.readChecked(0, 1000, got = 1000)
        head.readChecked(0, 1000, got = 100_000)
    }

    @Test
    fun `an answer waiting for a stream takes one as soon as it is parked, a parked one before any being read`() {
        val upstreams = upstreams()
        val head = upstreams.open(0) {}.readChecked(0, mib)
        val far = upstreams.open(100 * mib) {}.readChecked(100 * mib, mib)
        val third = upstreams.open(200 * mib) {}
        assertEquals(0, third.poll())
        // The far answer ends: its stream is parked at 101 MiB, which the third, at 200 MiB, can't use; it goes for the
        // third's own, at once, the clock never moving.
        val waited = CopyOnWriteArrayList<Long>()
        val reader = thread {
            val started = System.nanoTime()
            val n = third.poll(5000)
            waited += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            waited += n.toLong()
        }
        Thread.sleep(100)
        far.close()
        reader.join(5000)
        assertTrue(waited[0] < 1000, "waited ${waited[0]} ms after a stream was parked")
        assertTrue(waited[1] > 0, "nothing came")
        assertEquals(listOf(0L, 100 * mib, 200 * mib), source.opens)
        source.closeOf(100 * mib)
        assertEquals(emptyList(), upstreams.parked())
        head.readChecked(0, mib, got = mib)
    }

    @Test
    fun `a parked stream is closed after the park time, unless taken before`() {
        val upstreams = upstreams(ServeLimits(parkMillis = 300))
        val upstream = upstreams.open(5000) {}.readChecked(5000, 1000)
        // Parked from here or a little later: it closes no sooner than the park time after this, however the threads run.
        val parking = System.nanoTime()
        upstream.close()
        val closedAfter = TimeUnit.NANOSECONDS.toMillis(source.closeOf(5000) - parking)
        assertTrue(closedAfter in 300..2000, "closed $closedAfter ms after it was parked")
        assertEquals(emptyList(), upstreams.parked())

        // Taken before the park time: its expiry does nothing.
        upstreams.open(10 * mib) {}.readChecked(10 * mib, 1000).close()
        val again = upstreams.open(10 * mib + 1000) {}.readChecked(10 * mib + 1000, 1000)
        Thread.sleep(600)
        assertEquals(1, source.open.get(), "a stream in use was closed")
        again.readChecked(10 * mib + 1000, 1000, got = 1000)
    }

    @Test
    fun `after a failure at a byte, answers there open one stream every 5 s for 30 s, the others are refused`() {
        val x = 3 * mib + 12_345
        source.failAt = x
        source.failures.set(1000)
        val upstreams = upstreams()
        val first = upstreams.open(0) {}
        assertFailsWith<IOException> { first.readChecked(0, size) }
        first.close()
        assertEquals(listOf(0L), source.opens)
        assertEquals(emptyList(), upstreams.parked(), "a failed stream was parked")
        // The first retry at the byte opens one, which fails there too.
        val retry = upstreams.open(x) {}
        assertFailsWith<IOException> { retry.poll() }
        retry.close()
        assertEquals(1, source.opens.count { it == x })
        // Within 5 s, at the byte or up to 4 MiB before it: refused. Past it: as any other.
        assertFailsWith<IOException> { upstreams.open(x) {} }
        assertFailsWith<IOException> { upstreams.open(x - 4 * mib) {} }
        upstreams.open(x + 1) {}.close()
        upstreams.open(x - 4 * mib - 1) {}.close()
        now.addAndGet(4_999)
        assertFailsWith<IOException> { upstreams.open(x) {} }
        now.addAndGet(1)
        val later = upstreams.open(x) {}
        assertFailsWith<IOException> { later.poll() }
        later.close()
        assertEquals(2, source.opens.count { it == x })

        // 30 s after the last failure there, it is forgotten: two opens within 5 s.
        now.addAndGet(30_000)
        upstreams.open(x) {}.close()
        upstreams.open(x) {}.close()

        // A stream that read the byte and reads on past it, further than a failure there could have been seen short of it
        // (L76), forgets it too; one that reads on less doesn't, nor does one from after the byte, however far it reads.
        source.failures.set(1)
        upstreams.open(x - 1000) {}.let { assertFailsWith<IOException> { it.readChecked(x - 1000, 2000) }; it.close() }
        now.addAndGet(5_000)
        upstreams.open(x) {}.readChecked(x, mib).close()
        assertFailsWith<IOException> { upstreams.open(x) {} }
        // Its own stream: further than the parked one, at x + 1 MiB, would skip to.
        val after = x + 6 * mib
        upstreams.open(after) {}.readChecked(after, DirectUpstreams.SHORTFALL_BYTES).close()
        assertEquals(listOf(x + mib, after + DirectUpstreams.SHORTFALL_BYTES), upstreams.parked())
        assertFailsWith<IOException> { upstreams.open(x) {} }
        upstreams.open(x + mib) {}.readChecked(x + mib, DirectUpstreams.SHORTFALL_BYTES - mib + 1).close()
        upstreams.open(x) {}.close()
        upstreams.open(x) {}.close()
    }

    /**
     * L76 through a source that reads ahead, as the app's over the engine's channel (item 6(b)): its failure can surface
     * short of the byte that failed, by what it held then, a different way short each time. The retry from where it
     * stopped reads past there and fails at the byte itself; answers there are still one stream every 5 s, and a failure
     * seen further short, more than 4 MiB before the byte, is the same one.
     */
    @Test
    fun `a failure seen short of its byte is the same failure, answers there one stream every 5 s`() {
        val x = 12 * mib + 12_345
        source.failAt = x
        source.failures.set(1000)
        // A stream from well before the byte held 300,000 bytes when it failed, one from further still 6 MiB, one near it none.
        source.shortBy = { from -> if (from < x - 8 * mib) 6 * mib else if (from < x - mib) 300_000L else 0L }
        val upstreams = upstreams()
        val near = x - 2 * mib
        val first = upstreams.open(near) {}
        assertFailsWith<IOException> { first.readChecked(near, size) }
        first.close()
        // Asked again where it stopped: one new stream, which reads past there and fails at the byte itself.
        val short = x - 300_000
        val retry = upstreams.open(short) {}
        assertFailsWith<IOException> { retry.readChecked(short, size) }
        retry.close()
        assertEquals(listOf(near, short), source.opens)
        // Within 5 s, at the byte itself or where it stopped short: refused, as after any failure there.
        assertFailsWith<IOException> { upstreams.open(x) {} }
        assertFailsWith<IOException> { upstreams.open(short) {} }
        now.addAndGet(5_000)
        val later = upstreams.open(x) {}
        assertFailsWith<IOException> { later.poll() }
        later.close()
        // A stream from further before fails further short, more than 4 MiB before the byte: the same failure, so where it
        // stopped is refused too, its last open there just now.
        upstreams.open(0) {}.let { assertFailsWith<IOException> { it.readChecked(0, size) }; it.close() }
        assertFailsWith<IOException> { upstreams.open(x - 6 * mib) {} }
        assertEquals(listOf(near, short, x, 0L), source.opens)
    }

    @Test
    fun `closing the file closes every stream, parked, read and waiting, and opens no more`() {
        val upstreams = upstreams()
        val read = upstreams.open(0) {}.readChecked(0, mib)
        val other = upstreams.open(100 * mib) {}.readChecked(100 * mib, mib)
        val waiting = upstreams.open(200 * mib) {}
        assertEquals(0, waiting.poll())
        other.close()
        // The parked one went to the waiting answer; park another.
        waiting.readChecked(200 * mib, 1000).close()
        assertEquals(listOf(200 * mib + 1000), upstreams.parked())
        val last = upstreams.open(300 * mib) {}
        upstreams.close()
        eventually("a stream is still open", 1000) { source.open.get() == 0 }
        assertFailsWith<IOException> { read.poll() }
        assertFailsWith<IOException> { last.poll() }
        assertFailsWith<IOException> { upstreams.open(0) {} }
        upstreams.openHead()
        assertEquals(0, source.open.get())
    }

    @Test
    fun `never more than two streams open, however many answers open at once over a slow source`() {
        source.openMillis = 30
        val upstreams = upstreams(ServeLimits(upstreamWaitMillis = 100, headGuardBytes = 0))
        val clock = System.nanoTime()
        val errors = CopyOnWriteArrayList<String>()
        // Daemons: a reader stuck for good fails the test, never outliving it.
        val readers = (1..6).map { i ->
            thread(isDaemon = true) {
                try {
                    repeat(5) { round ->
                        val from = (i * 40 + round) * mib
                        val upstream = upstreams.open(from) {}
                        try {
                            upstream.readChecked(from, 256 * 1024)
                        } catch (_: IOException) {
                            // Taken by another: as a receiver, ask again later.
                        }
                        upstream.close()
                    }
                } catch (e: Throwable) {
                    errors += "$e"
                }
            }
        }
        // The fake clock walks on so that waiting answers take streams.
        val walker = thread(isDaemon = true) { while (readers.any { it.isAlive }) { now.addAndGet(50); Thread.sleep(5) } }
        // One deadline for them all: a reader stuck in a lock or an open, which no read's own deadline can see, fails here.
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        readers.forEach { it.join(maxOf(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))) }
        walker.join(1000)
        assertTrue(readers.none { it.isAlive }, "${readers.count { it.isAlive }} of the readers hung")
        assertFalse(walker.isAlive, "the clock still walks")
        assertEquals(emptyList(), errors.toList())
        assertTrue(source.maxOpen.get() <= 2, "${source.maxOpen.get()} open at once")
        assertTrue(source.opens.size >= 6, "${source.opens.size} opens in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clock)} ms")
    }

    // Through the server.

    /** [source], but its streams from byte 0 give [bytes] and then nothing yet: an answer from there progresses no further. */
    private fun headEndingAt(bytes: Long) = ByteSource { position, gaps ->
        val upstream = source.open(position, gaps)
        if (position != 0L) {
            upstream
        } else {
            object : Upstream {
                private var at = 0L

                override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
                    if (at >= bytes) {
                        Thread.sleep(waitMillis)
                        return 0
                    }
                    return upstream.read(buffer, offset, minOf(length.toLong(), bytes - at).toInt(), waitMillis).also { if (it > 0) at += it }
                }

                override fun close() = upstream.close()
            }
        }
    }

    @Test
    fun `through the server, a third answer of a file waits, then takes the stream with the oldest progress`() {
        val server = server(ServeLimits(upstreamWaitMillis = 500, headGuardBytes = mib))
        // Once the head's client has its 3 MiB, the head's progress is over, however its thread runs: the far one's is later.
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", headEndingAt(3 * mib)), loopback)
        val (head, _) = opened(server, served.path, "bytes=0-")
        head.read(3 * mib)
        val (far, _) = opened(server, served.path, "bytes=${100 * mib}-")
        far.read(mib)
        eventually("two weren't open") { source.open.get() == 2 }
        val asked = System.nanoTime()
        val (third, thirdHead) = opened(server, served.path, "bytes=${200 * mib}-")
        assertEquals(206, thirdHead.status)
        assertContentEquals(expected(200 * mib, 200 * mib + 99_999), third.read(100_000))
        val waited = millisSince(asked)
        assertTrue(waited in 400..3000, "the body came after $waited ms")
        val (_, taken) = next<ServeEvent.Ended>()
        assertEquals(ServeEvent.Ended(served.id, 0, taken.sent, ServeEnd.Taken), taken)
        assertTrue(head.ended(2000), "the taken answer's connection is open")
        assertEquals(1, served.counters().evictions)
        assertEquals(0, served.counters().upstreamFailures)
        assertTrue(source.maxOpen.get() <= 2)
        assertContentEquals(expected(100 * mib + mib, 100 * mib + 2 * mib - 1), far.read(mib))
    }

    @Test
    fun `through the server, the head opened ahead answers bytes=0- with no new open`() {
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source), loopback)
        served.openHead()
        assertEquals(listOf(0L), source.opens)
        val (http, head) = opened(server, served.path, "bytes=0-")
        assertEquals(206, head.status)
        assertContentEquals(expected(0, 2 * mib - 1), http.read(2 * mib))
        assertEquals(listOf(0L), source.opens)
        // Revoked: the parked and the read streams close with it.
        http.socket.close()
        eventually("the head wasn't parked") { (served.upstreams as DirectUpstreams).parked().isNotEmpty() }
        served.revoke()
        eventually("a stream is still open", 1000) { source.open.get() == 0 }
    }

    @Test
    fun `through the server, a retry at a failed byte within 5 s gets its headers and a close, and opens nothing`() {
        val x = 2 * mib + 777
        source.failAt = x
        source.failures.set(1000)
        val server = server(clock = now::get)
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source), loopback)
        val (first, body) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, first.status)
        assertEquals(x, body.size.toLong())
        // Asked again at the byte, as after a FIN: one new stream, which fails there too.
        val (again, none) = exchange(server, request(server, served.path, range = "bytes=$x-"))
        assertEquals(206, again.status)
        assertEquals(0, none.size)
        // And again within 5 s: the headers and a close, nothing opened.
        repeat(3) {
            val (refused, nothing) = exchange(server, request(server, served.path, range = "bytes=$x-"))
            assertEquals(206, refused.status)
            assertEquals(size - x, refused.length)
            assertEquals(0, nothing.size)
        }
        assertEquals(1, source.opens.count { it == x })
        assertEquals(5, served.counters().upstreamFailures)
        now.addAndGet(5_000)
        exchange(server, request(server, served.path, range = "bytes=$x-"))
        assertEquals(2, source.opens.count { it == x })
        assertNotNull(served.counters().firstRequestAt)
    }
}

package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the server streams (L53, L54, L101): headers at once, the body as it comes, back-pressure, hang-ups, the stall timers
 * and the head guard, the slots and their evictions, a burst from elsewhere, the head's deadline and zeros at gaps; and
 * what a cast's slow-stream banner reads of it (L133, L134): the time spent waiting on the source and on the device, and
 * how far the device was sent past a byte.
 */
class ServeStreamingTest : ServeTestBase() {
    private val mib = 1024L * 1024
    /** Bigger than any test reads, made up as it is read. */
    private val hugeSize = 512 * mib

    @Test
    fun `the limits are the design's`() {
        assertEquals(
            ServeLimits(
                requestLine = 2048, headerBytes = 8192, headers = 64, headMillis = 10_000, serving = 8, reading = 8, readingPerAddress = 2,
                acceptsPerWindow = 30, acceptWindowMillis = 60_000, stallMillis = 90_000, headGuardBytes = mib, headStallMillis = 90_000,
                pollMillis = 250, upstreams = 2, skipBytes = 4 * mib, parkMillis = 300_000, upstreamWaitMillis = 10_000, failureMillis = 30_000,
                retryMillis = 5_000,
            ),
            ServeLimits(),
        )
    }

    @Test
    fun `the headers go before the first byte has come`() {
        val source = PatternSource(hugeSize).apply { hold() }
        val server = server()
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val started = System.nanoTime()
        val (http, head) = opened(server, served.path, "bytes=4096-")
        assertEquals(206, head.status)
        assertTrue(millisSince(started) < 1000, "the headers waited")
        assertEquals(0L, source.bytesRead.get())
        source.release()
        assertContentEquals(expected(4096, 4096 + 99_999), http.read(100_000))
    }

    @Test
    fun `a client that stops reading holds what the server reads bounded`() {
        val source = PatternSource(hugeSize)
        val server = server()
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val (http, _) = opened(server, served.path, "bytes=0-")
        assertContentEquals(expected(0, mib - 1), http.read(mib))
        // It stops reading: the server's write waits on the socket, and nothing more is read from the source.
        Thread.sleep(500)
        val read = source.bytesRead.get()
        Thread.sleep(700)
        assertEquals(read, source.bytesRead.get(), "the server read on while the client didn't")
        assertTrue(read < 24 * mib, "read $read bytes for a client that took 1 MiB")
        // And it goes on once the client does.
        assertContentEquals(expected(mib, 2 * mib - 1), http.read(mib))
    }

    @Test
    fun `a hang-up while the source has nothing yet is seen within the poll, and its stream parked`() {
        val source = PatternSource(hugeSize).apply { hold() }
        val server = server()
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val (http, head) = opened(server, served.path, "bytes=1000-")
        assertEquals(206, head.status)
        eventually("the source wasn't opened") { source.open.get() == 1 }
        Thread.sleep(100)
        val hungUp = System.nanoTime()
        http.socket.close()
        val (endedAt, ended) = next<ServeEvent.Ended>(2000)
        assertEquals(ServeEnd.Left, ended.why)
        val after = TimeUnit.NANOSECONDS.toMillis(endedAt - hungUp)
        assertTrue(after < 400, "seen $after ms after the hang-up")
        // Kept for the next answer from its byte (L56).
        eventually("its stream wasn't parked", 1000) { (served.upstreams as DirectUpstreams).parked() == listOf(1000L) }
        assertEquals(1, source.open.get())
    }

    /** L101: inside the head guard a response from byte 0 has the head's own stall limit, set apart here (by default the same since item 6(b)). */
    @Test
    fun `the stall timer closes a stuck writer, and one from byte 0 only after the head's limit until the guard's bytes are written`() {
        val limits = ServeLimits(stallMillis = 400, headStallMillis = 1600, headGuardBytes = 64 * mib)
        val source = PatternSource(hugeSize)
        val server = server(limits)
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val asked = System.nanoTime()
        opened(server, served.path, "bytes=0-")
        opened(server, served.path, "bytes=${mib}-")
        val (midAt, mid) = next<ServeEvent.Ended>(5000)
        assertEquals(ServeEvent.Ended(served.id, mib, mid.sent, ServeEnd.Stalled), mid)
        val midAfter = TimeUnit.NANOSECONDS.toMillis(midAt - asked)
        assertTrue(midAfter in 350..1500, "mid-file closed after $midAfter ms")
        val (headAt, head) = next<ServeEvent.Ended>(5000)
        assertEquals(ServeEvent.Ended(served.id, 0, head.sent, ServeEnd.Stalled), head)
        val headAfter = TimeUnit.NANOSECONDS.toMillis(headAt - asked)
        assertTrue(headAfter in 1550..4000, "from byte 0 closed after $headAfter ms")
        assertTrue(head.sent < limits.headGuardBytes, "${head.sent} bytes went before it stalled")
        assertEquals(2, served.counters().stalls)

        // Past the guard's bytes, a response from byte 0 stalls as any other.
        val past = server(ServeLimits(stallMillis = 400, headStallMillis = 5000, headGuardBytes = mib))
        val file = past.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val (http, _) = opened(past, file.path, "bytes=0-")
        http.read(2 * mib)
        val stopped = System.nanoTime()
        val (pastAt, pastEnd) = next<ServeEvent.Ended>(6000)
        assertEquals(ServeEnd.Stalled, pastEnd.why)
        val pastAfter = TimeUnit.NANOSECONDS.toMillis(pastAt - stopped)
        assertTrue(pastAfter < 2000, "past the guard, closed $pastAfter ms after the client stopped")
    }

    @Test
    fun `a 9th serving connection evicts the one with the oldest progress, never a response from byte 0, and nothing is a 503`() {
        val source = PatternSource(hugeSize)
        // The first two wait on a source with nothing yet: their progress ends with their headers, however the threads run.
        val waiting = PatternSource(hugeSize).apply { hold() }
        val server = server()
        // The serving slots are the server's, the streams of a source each file's (L56): a file for each connection.
        val files = List(9) { i -> server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", if (i < 2) waiting else source), loopback) }
        val starts = (1..7).map { it * 32 * mib }
        // The oldest, but from byte 0: inside the guard.
        val (_, firstHead) = opened(server, files[0].path, "bytes=0-")
        assertEquals(206, firstHead.status)
        eventually("the first answer didn't ask its source") { waiting.opens.size == 1 }
        // A tick of the server's clock later, the oldest that can go.
        Thread.sleep(5)
        assertEquals(206, opened(server, files[1].path, "bytes=${starts.first()}-").second.status)
        eventually("the second answer didn't ask its source") { waiting.opens.size == 2 }
        // Six more, writing until the kernel's buffers are full: all of it after the second's headers.
        for ((i, start) in starts.withIndex().drop(1)) assertEquals(206, opened(server, files[i + 1].path, "bytes=$start-").second.status)
        eventually("eight weren't serving") { source.open.get() == 6 }
        val (_, ninth) = opened(server, files[8].path, "bytes=${300 * mib}-")
        assertEquals(206, ninth.status)
        val (_, evicted) = next<ServeEvent.Ended>()
        assertEquals(ServeEvent.Ended(files[1].id, starts.first(), evicted.sent, ServeEnd.Evicted), evicted)
        assertEquals(1, files[1].counters().evictions)
        val later = drained()
        assertEquals(emptyList(), later.filterIsInstance<ServeEvent.Ended>(), "another ended")
        assertEquals(emptyList(), later.filterIsInstance<ServeEvent.Answered>().filter { it.status != 206 })

        // Eight from byte 0, all inside the guard: none can go, so the 9th is closed unanswered, and counted. The guard is
        // set above what the eight write into loopback's buffers while the 9th connects, so none leaves it by then.
        val guarded = server(ServeLimits(headGuardBytes = 16 * mib))
        val file = guarded.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        repeat(8) { assertEquals(206, opened(guarded, file.path, "bytes=0-").second.status) }
        val refused = connect(guarded)
        refused.send(request(guarded, file.path, range = "bytes=0-"))
        assertNull(refused.head())
        assertEquals(ServeDrop.Full, next<ServeEvent.Dropped>().second.why)
        assertEquals(1, guarded.counters().dropped[ServeDrop.Full])
        assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Ended>())
    }

    @Test
    fun `a newcomer past the head slots displaces the oldest still reading its head, never one serving`() {
        val source = PatternSource(hugeSize)
        val server = server()
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        assertEquals(206, opened(server, served.path, "bytes=0-").second.status)
        // Eight from the device's own address that send nothing hold every head slot.
        val idle = List(8) { connect(server).also { Thread.sleep(20) } }
        Thread.sleep(200)
        val newcomer = connect(server)
        newcomer.send(request(server, served.path, range = "bytes=1000-"))
        assertEquals(206, newcomer.head()?.status)
        assertEquals(ServeDrop.Displaced, next<ServeEvent.Dropped>().second.why)
        assertNull(idle.first().head(), "the oldest is still open")
        assertTrue(idle.drop(1).none { it.ended(50) }, "another was closed")
        assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Ended>())
    }

    @Test
    fun `a burst of connects from another address leaves the serving connections alone, and is counted`() {
        val source = PatternSource(hugeSize)
        val server = server()
        // A file for each, each file having two streams of its source at most (L56).
        val files = List(8) { server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback) }
        repeat(8) { i -> assertEquals(206, opened(server, files[i].path, "bytes=${i * 32 * mib}-").second.status) }
        eventually("eight weren't serving") { source.open.get() == 8 }
        val started = System.nanoTime()
        val burst = (1..50).map {
            Socket().apply {
                bind(InetSocketAddress("127.0.0.2", 0))
                connect(InetSocketAddress(loopback, server.port), 2000)
            }
        }
        assertTrue(millisSince(started) < 1000, "the burst took ${millisSince(started)} ms")
        try {
            eventually("the burst wasn't counted") {
                val dropped = server.counters().dropped
                dropped[ServeDrop.TooManyAccepts] == 20 && dropped[ServeDrop.TooManyFromAddress] == 28
            }
            // Two of them wait for their heads in the address's two slots; none of the eight went.
            assertEquals(8, source.open.get())
            assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Ended>())
            assertEquals(0, files.sumOf { it.counters().evictions })
        } finally {
            burst.forEach { it.close() }
        }
    }

    @Test
    fun `the request's head must come within its deadline of the accept, however slowly it drips`() {
        val server = server(ServeLimits(headMillis = 600))
        val served = server.serve(ServedFile(1000, "video/x-matroska", "mkv", PatternSource(1000)), loopback)
        val http = connect(server)
        val out = http.socket.getOutputStream()
        val started = System.nanoTime()
        out.write("GET ${served.path}".toByteArray())
        // A byte every 100 ms: each read would be well within a per-read timeout of 600 ms.
        thread(isDaemon = true) {
            try {
                repeat(100) {
                    out.write('a'.code)
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
                // Closed by the server.
            }
        }
        assertNull(http.head(), "an answer to a request that never ended")
        val elapsed = millisSince(started)
        assertTrue(elapsed in 500..2500, "closed after $elapsed ms")
        assertEquals(ServeDrop.TimedOut, next<ServeEvent.Dropped>().second.why)
        // A connection that sends nothing goes the same way.
        val idle = connect(server)
        assertNull(idle.head())
        assertEquals(ServeDrop.TimedOut, next<ServeEvent.Dropped>().second.why)
    }

    /** Missing articles come as zeros (L53): passed on byte for byte, never cut and never refused, the tail's too. */
    @Test
    fun `zeros at a gap come out byte-exact, the tail's too, counted once per merged range`() {
        val size = mib
        val gaps = listOf(100_000L..199_999L, 200_000L..249_999L, 1_040_000L..1_045_000L)
        val source = PatternSource(size, gaps)
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source), loopback)
        val (whole, body) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, whole.status)
        assertContentEquals(expected(0, size - 1, gaps), body)
        assertEquals(listOf(100_000L..249_999L, 1_040_000L..1_045_000L), served.gaps())
        assertEquals(2, served.counters().gaps)
        // The tail read, as on a first seek: from the Cues' offset rounded down to 32 KiB.
        val tailStart = 31 * 32_768L
        val (tail, tailBody) = exchange(server, request(server, served.path, range = "bytes=$tailStart-"))
        assertEquals(206, tail.status)
        assertContentEquals(expected(tailStart, size - 1, gaps), tailBody)
        // From inside a gap: a 206 like any other, never a 416.
        val (inside, insideBody) = exchange(server, request(server, served.path, range = "bytes=1042000-"))
        assertEquals(206, inside.status)
        assertContentEquals(expected(1_042_000, size - 1, gaps), insideBody)
        assertEquals(2, served.counters().gaps)
    }

    /**
     * L104: the phone's gap watch reads, of the gaps served, those the device was sent what follows: the answer that last
     * began at or before a gap's end has written past it. A read further on (the index at the end, L96) says nothing of the
     * stream that plays through the gap, nor does one before it once a later answer takes the device back there.
     */
    @Test
    fun `a gap is written past once the answer that last began before its end goes past it, never by a read further on`() {
        val size = mib
        val gap = 100_000L..199_999L
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", PatternSource(size, listOf(gap))), loopback)
        assertEquals(emptyList(), served.passed())
        // The playing stream stops right after the zeros: its provider is slow with what follows.
        assertEquals(206, exchange(server, request(server, served.path, range = "bytes=0-199999")).first.status)
        assertEquals(listOf(gap), served.gaps())
        assertEquals(emptyList(), served.passed(), "nothing after the gap yet")
        // The index at the end, read to the file's end, as on a Resume or a first seek.
        assertEquals(206, exchange(server, request(server, served.path, range = "bytes=983040-")).first.status)
        assertEquals(emptyList(), served.passed(), "a read further on")
        // The stream goes on past the gap (the device's retry from inside it).
        assertEquals(206, exchange(server, request(server, served.path, range = "bytes=131072-399999")).first.status)
        assertEquals(listOf(gap), served.passed())
        // Taken back before the gap: until this answer goes past it too, the device hasn't what follows it.
        assertEquals(206, exchange(server, request(server, served.path, range = "bytes=65536-99999")).first.status)
        assertEquals(emptyList(), served.passed(), "a rewind")
        assertEquals(206, exchange(server, request(server, served.path, range = "bytes=65536-200000")).first.status)
        assertEquals(listOf(gap), served.passed())
    }

    /**
     * L133: the time an answer's reads of its source brought nothing is the source's wait, on the server's clock (a fake
     * one here, which each empty read moves on as its poll's wait would); its writes to a device that reads at once add
     * nothing to the device's.
     */
    @Test
    fun `reads of the source that brought nothing count as its wait, and none as the device's`() {
        val now = AtomicLong(5_000_000)
        val size = mib
        val server = server(clock = now::get)
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", Waiting(PatternSource(size), now, empty = 3, millis = 100)), loopback)
        val (head, body) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, head.status)
        assertContentEquals(expected(0, size - 1), body)
        assertEquals(300L, served.counters().sourceWaitMillis)
        assertEquals(0L, served.counters().peerWaitMillis)
    }

    /**
     * L133 (CFS-2): a read that waits on the source and then brings bytes waited all the same: a source just under what the
     * film needs answers each read a moment late, never after a whole poll with nothing, and that is the source's wait too.
     */
    @Test
    fun `reads of the source that waited before their bytes count as its wait too`() {
        val now = AtomicLong(5_000_000)
        val size = mib
        val server = server(clock = now::get)
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", Late(PatternSource(size), now, late = 4, millis = 200)), loopback)
        val (head, body) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, head.status)
        assertContentEquals(expected(0, size - 1), body)
        assertEquals(800L, served.counters().sourceWaitMillis)
        assertEquals(0L, served.counters().peerWaitMillis)
    }

    /**
     * L133: the time an answer's writes were held by a device that stopped reading is the device's wait, on the server's
     * clock (a fake one here, moved on while a write is held); a source that always has its bytes adds nothing to its own.
     */
    @Test
    fun `writes held by a device that stopped reading count as its wait, and none as the source's`() {
        val now = AtomicLong(5_000_000)
        val source = PatternSource(hugeSize)
        val server = server(clock = now::get)
        val served = server.serve(ServedFile(hugeSize, "video/x-matroska", "mkv", source), loopback)
        val (http, _) = opened(server, served.path, "bytes=0-")
        assertContentEquals(expected(0, mib - 1), http.read(mib))
        // It stops reading: once nothing more is read from the source, the server's write is held on the socket.
        eventually("the server kept reading") {
            val read = source.bytesRead.get()
            Thread.sleep(300)
            source.bytesRead.get() == read
        }
        assertEquals(0L, served.counters().peerWaitMillis)
        now.addAndGet(700)
        // It reads on: the held write took the 700 ms, and the others none.
        assertContentEquals(expected(mib, 2 * mib - 1), http.read(mib))
        eventually("the held write wasn't counted") { served.counters().peerWaitMillis == 700L }
        assertEquals(0L, served.counters().sourceWaitMillis)
    }

    /**
     * L134: how far the device was sent past a byte, which a cast's buffer is read from: one past the last byte written by
     * the latest answer that began at or before it and wrote past it. A later answer from before the byte says so though
     * it wrote less far; where it didn't go, the one before it still does; one that began past the byte (the index at the
     * end, L96) says nothing of it.
     */
    @Test
    fun `how far the device was sent past a byte is the reach of the latest answer that began at or before it and wrote past it`() {
        val size = mib
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", PatternSource(size)), loopback)
        assertNull(served.reach(0), "nothing sent")
        fun get(range: String) = assertEquals(206, exchange(server, request(server, served.path, range = range)).first.status)
        // An answer covering the byte: its end.
        get("bytes=0-199999")
        assertEquals(listOf(200_000L, 200_000L, 200_000L), listOf(0L, 150_000L, 199_999L).map(served::reach))
        assertNull(served.reach(200_000), "none wrote past it")
        // The index at the end, read from further on: nothing of a byte before it.
        get("bytes=983040-")
        assertEquals(200_000L, served.reach(150_000))
        assertEquals(size, served.reach(990_000))
        assertNull(served.reach(500_000))
        // A later answer from before the byte: its end, though nearer.
        get("bytes=100000-169999")
        assertEquals(170_000L, served.reach(150_000))
        // Where it didn't go, the one before it still covers.
        assertEquals(200_000L, served.reach(180_000))
        // One that began past the byte doesn't count for it, and covers what it wrote past.
        get("bytes=160000-399999")
        assertEquals(170_000L, served.reach(150_000))
        assertEquals(listOf(400_000L, 400_000L), listOf(165_000L, 300_000L).map(served::reach))
        // The bytes that answer wrote, from where it began: what tells a seek back's answer from the one it left behind.
        assertEquals(160_000L until 400_000L, served.answered(300_000))
        assertEquals(100_000L until 170_000L, served.answered(150_000))
        assertNull(served.answered(500_000))
    }

    /** [source]'s bytes, the first [late] reads of them each [millis] of [now]'s time late: a provider a little slow. */
    private class Late(private val source: ByteSource, private val now: AtomicLong, late: Int, private val millis: Long) : ByteSource {
        private val left = AtomicInteger(late)

        override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
            val upstream = source.open(position, gaps)
            return object : Upstream by upstream {
                override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
                    if (left.getAndDecrement() > 0) now.addAndGet(millis)
                    return upstream.read(buffer, offset, length, waitMillis)
                }
            }
        }
    }

    /** [source]'s bytes after [empty] reads that bring nothing, each taking [millis] of [now]'s time: a provider slow to answer. */
    private class Waiting(private val source: ByteSource, private val now: AtomicLong, empty: Int, private val millis: Long) : ByteSource {
        private val left = AtomicInteger(empty)

        override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
            val upstream = source.open(position, gaps)
            return object : Upstream by upstream {
                override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
                    if (left.getAndDecrement() <= 0) return upstream.read(buffer, offset, length, waitMillis)
                    now.addAndGet(millis)
                    return 0
                }
            }
        }
    }
}

package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scripted client in the pattern the Shield's receiver showed (item 6(a), a spike's T1-T3 and T6): its
 * first request always `bytes=0-`, a burst, then deferring; each seek a close and a new connection asking a 32 KiB-aligned
 * `bytes=N-`; the first seek and a start-at reading the Cues at the end, then the target, which the Shield asked on the
 * tail's connection and our `Connection: close` turns into a new one; a cut retried 0.3 s later from the byte it reached;
 * never more than two connections. A cut connection's stream of the source is parked at once, and no more than two are
 * ever open (L56). The file is the spike's b.mkv in shape: 50,897,087 bytes, its Cues at 50,896,137.
 */
class ChromiumLikeClientTest : ServeTestBase() {
    private val size = 50_897_087L
    private val source = PatternSource(size)
    private val server = server()
    private val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source), loopback)

    /** The Cues' offset rounded down to 32 KiB, as the receiver asks it: 8,383 bytes to the end. */
    private val tail = 50_888_704L
    /** The cluster at 43.95 s, rounded down to 32 KiB: the first seek's target (45 s). */
    private val target = 24_576_000L

    /** What the receiver's media loader sent with every request, but the Range. */
    private val shield = listOf(
        "Accept-Encoding: identity;q=1, *;q=0",
        "Accept: */*",
        "Connection: keep-alive",
        "Accept-Language: en-US",
        "CAST-DEVICE-CAPABILITIES: {\"display_supported\":true,\"hi_res_audio_supported\":false,\"remote_control_input_supported\":true,\"touch_input_supported\":false}",
        "User-Agent: Mozilla/5.0 (Linux; Android 11.0; Build/RQ1A.210105.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.0 Safari/537.36 CrKey/1.56.500000 DeviceType/AndroidTV",
    )

    /** The receiver: its connections, never more than two at once, and every status it was answered with. */
    private inner class Receiver {
        val statuses = mutableListOf<Int>()
        private var open = 0
        var mostOpen = 0
            private set

        /** One answer on [http], from byte [from] on: [got] so far. */
        inner class Load(val http: Http, val from: Long, val head: Response) {
            var got = 0L
                private set

            /** [n] more bytes of the body, checked against the file. */
            fun read(n: Long): Load {
                val bytes = http.read(n)
                assertContentEquals(expected(from + got, from + got + bytes.size - 1), bytes, "bytes from ${from + got}")
                got += bytes.size
                return this
            }

            /** The rest of the body, to its end. */
            fun toEnd(): Load = read(head.length!! - got).also { assertEquals(head.length, got, "the body ended short") }
        }

        /** `GET Range: bytes=[from]-` on a new connection, or on [reused], a connection whose answer was read to its end. */
        fun ask(from: Long, reused: Http? = null): Load? {
            val http = reused ?: connect(server, receiveBuffer = 256 * 1024).also {
                open++
                mostOpen = maxOf(mostOpen, open)
            }
            try {
                http.send(request(server, served.path, range = "bytes=$from-", headers = shield))
            } catch (_: java.io.IOException) {
                // A connection the server closed: the receiver finds out from the read.
            }
            val head = http.head() ?: return null.also { if (reused != null) closed(http) }
            statuses += head.status
            return Load(http, from, head)
        }

        /** The receiver gives up on an answer it hasn't read to its end: a reset, as Chromium's close of such a socket is. */
        fun cut(load: Load): Long = System.nanoTime().also {
            load.http.abort()
            open--
        }

        fun closed(http: Http) {
            http.socket.close()
            open--
        }
    }

    @Test
    fun `the open, a burst, deferring, then the first seek's tail read and target, and a second seek`() {
        val receiver = Receiver()
        // T1: one connection, bytes=0-, a burst of 12 MB, then deferring with its buffer full.
        val head = assertNotNull(receiver.ask(0))
        assertEquals(206, head.head.status)
        assertEquals("bytes 0-${size - 1}/$size", head.head.header("Content-Range").single())
        head.read(12_000_000)
        val readBefore = source.bytesRead.get()
        Thread.sleep(1500)
        assertEquals(emptyList(), drained(0).filterIsInstance<ServeEvent.Ended>(), "a deferring receiver's connection ended")
        assertTrue(source.bytesRead.get() - readBefore < 16_000_000, "the source was read on while the receiver deferred")

        // T2: the first seek. The head's connection closes as a new one reads the Cues at the end, to the end.
        val cues = assertNotNull(receiver.ask(tail))
        val cutAt = receiver.cut(head)
        cues.toEnd()
        assertEquals(8_383, cues.got)
        assertEquals(listOf("close"), cues.head.header("Connection"))
        parkedWithin(100, cutAt, parked = 1)
        // The Shield asked the target on the tail's connection at once: ours closed it, so it goes on a new one.
        assertNull(receiver.ask(target, reused = cues.http), "a second answer on one connection")
        val seek = assertNotNull(receiver.ask(target)).read(4_000_000)
        // The second seek, to 85 s: no second Cues read. Two streams are parked, neither at its byte: the one parked
        // longest, the head's, goes for it.
        val secondCut = receiver.cut(seek)
        parkedWithin(100, secondCut, parked = 2)
        val second = assertNotNull(receiver.ask(47_415_296)).toEnd()
        assertEquals(3_481_791, second.got)
        source.closeOf(0)
        assertEquals(listOf(0L, tail, target, 47_415_296L), source.opens)

        assertEquals(List(receiver.statuses.size) { 206 }, receiver.statuses)
        assertTrue(receiver.mostOpen <= 2 && source.maxOpen.get() <= 2, "more than two at once")
    }

    @Test
    fun `a start-at reads the head, the tail, then the middle`() {
        val receiver = Receiver()
        // T3: bytes=0-, 3-10 MB in under 0.1 s, then the receiver closes it.
        val head = assertNotNull(receiver.ask(0)).read(6_000_000)
        val cutAt = receiver.cut(head)
        parkedWithin(100, cutAt, parked = 1)
        val cues = assertNotNull(receiver.ask(tail)).toEnd()
        assertNull(receiver.ask(target, reused = cues.http))
        assertNotNull(receiver.ask(target)).read(2_000_000)
        assertEquals(listOf(206, 206, 206), receiver.statuses)
        assertEquals(listOf(0L, tail, target), source.opens)
    }

    /** T6: a FIN mid-file is asked again at the exact byte, an RST at the last byte the receiver had; neither is seen. */
    @Test
    fun `a cut mid-body is asked again 0_3 s later from the byte it reached`() {
        val receiver = Receiver()
        // The target of a start-at-30 s load, cut by the server at the spike's byte as its upstream fails: a FIN.
        val cutByte = 24_589_009L
        source.failAt = cutByte
        val first = assertNotNull(receiver.ask(15_499_264))
        first.read(Long.MAX_VALUE)
        assertEquals(cutByte - 15_499_264, first.got)
        receiver.closed(first.http)
        Thread.sleep(300)
        val again = assertNotNull(receiver.ask(cutByte)).read(2_000_000)
        assertEquals(1, served.counters().upstreamFailures)

        // Cut by the receiver's side with a reset, after a few odd megabytes: asked again from the last byte it had, short
        // of where its stream was parked, so a new one opens beside it.
        val cutAt = receiver.cut(again)
        parkedWithin(100, cutAt, parked = 1)
        Thread.sleep(300)
        val resumed = assertNotNull(receiver.ask(cutByte + again.got)).read(1_000_000)
        assertEquals(cutByte + again.got, resumed.from)
        assertEquals(listOf(206, 206, 206), receiver.statuses)
        assertTrue(receiver.mostOpen <= 2)
    }

    @Test
    fun `keep-alive reuse after a body read to its end gets nothing on that connection, and a new one is answered`() {
        val receiver = Receiver()
        val cues = assertNotNull(receiver.ask(tail)).toEnd()
        assertNull(receiver.ask(tail, reused = cues.http))
        assertEquals(8_383, assertNotNull(receiver.ask(tail)).toEnd().got)
        assertEquals(listOf(206, 206), receiver.statuses)
    }

    /** The stream of the answer the receiver cut at [cutAt] (nanoTime) parked within [millis]: [parked] are parked then. */
    private fun parkedWithin(millis: Long, cutAt: Long, parked: Int) {
        val upstreams = served.upstreams as DirectUpstreams
        while (upstreams.parked().size < parked) {
            assertTrue(System.nanoTime() - cutAt < TimeUnit.SECONDS.toNanos(2), "the cut answer's stream wasn't parked")
            Thread.sleep(1)
        }
        val after = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cutAt)
        assertTrue(after <= millis, "the cut answer's stream was parked $after ms after the cut")
    }
}

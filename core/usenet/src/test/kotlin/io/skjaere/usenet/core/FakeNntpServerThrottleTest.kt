package io.skjaere.usenet.core

import io.skjaere.usenet.core.FakeNntpServer.Body
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FakeNntpServer]'s throttle holds each connection's answers to its bytes a second, as its KDoc says, seen from plain
 * sockets. The waits are kept rather than slept (virtual time): no case depends on a clock.
 */
class FakeNntpServerThrottleTest {
    /** The throttle's waits by connection, kept and not slept. */
    private class Waits {
        private val byConnection = ConcurrentHashMap<Int, MutableList<Long>>()

        fun wait(connection: Int, nanos: Long) {
            byConnection.computeIfAbsent(connection) { CopyOnWriteArrayList() } += nanos
        }

        fun of(connection: Int): List<Long> = byConnection[connection].orEmpty()

        val none: Boolean get() = byConnection.values.all { it.isEmpty() }
    }

    /** A plain client: sends commands and reads the answers' lines and bytes, giving up on a read after [READ_LIMIT_MS]. */
    private class Client(server: FakeNntpServer) : AutoCloseable {
        private val socket = server.provider().let { Socket(it.host, it.port) }.apply { soTimeout = READ_LIMIT_MS }
        private val input = BufferedInputStream(socket.getInputStream())
        val greeting: String? = line()

        fun send(command: String) = socket.getOutputStream().run {
            write("$command\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        /** The next line without its CRLF, or null at the stream's end. */
        fun line(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(b)
            }
        }

        /** The next [count] bytes. */
        fun bytes(count: Int): ByteArray = ByteArray(count).also { bytes ->
            var read = 0
            while (read < count) {
                val n = input.read(bytes, read, count - read)
                check(n >= 0) { "the stream ended after $read of $count bytes" }
                read += n
            }
        }

        /** Asks for [id]'s BODY and checks the answer is [article] whole, then the dot. */
        fun body(id: String, article: ByteArray) {
            send("BODY $id")
            assertEquals("222 0 $id", line())
            assertContentEquals(article, bytes(article.size))
            assertEquals(".", line())
        }

        override fun close() = socket.close()
    }

    @Test
    fun `a throttled connection's bytes each wait their share of a second before they go, in steps of a twentieth of one`() {
        val waits = Waits()
        FakeNntpServer(body = { Body.Article(ARTICLE) }, throttle = RATE, throttleWait = waits::wait).use { server ->
            Client(server).use { client ->
                assertEquals(GREETING, client.greeting)
                client.body("<a@x>", ARTICLE)
                // Every byte that went waited for it: the greeting's, the 222 line's, the article's and the dot's.
                val sent = "$GREETING\r\n".length + "222 0 <a@x>\r\n".length + ARTICLE.size + ".\r\n".length
                assertEquals(sent * NANOS_A_BYTE, waits.of(0).sum())
                assertTrue(waits.of(0).all { it <= STEP_NANOS }, "a step is a twentieth of a second's bytes at most: ${waits.of(0)}")
            }
        }
    }

    @Test
    fun `with no throttle a connection's answers never wait`() {
        val waits = Waits()
        FakeNntpServer(body = { Body.Article(ARTICLE) }, throttleWait = waits::wait).use { server ->
            Client(server).use { client ->
                client.body("<a@x>", ARTICLE)
                assertTrue(waits.none)
            }
        }
    }

    @Test
    fun `a throttle set on an open connection holds its next answers, and taking it away ends the waits`() {
        val waits = Waits()
        FakeNntpServer(throttleWait = waits::wait).use { server ->
            Client(server).use { client ->
                client.send("DATE")
                assertEquals(DATE, client.line())
                assertTrue(waits.none)

                server.throttle = RATE
                client.send("DATE")
                assertEquals(DATE, client.line())
                assertEquals("$DATE\r\n".length * NANOS_A_BYTE, waits.of(0).sum())

                server.throttle = null
                client.send("DATE")
                assertEquals(DATE, client.line())
                assertEquals("$DATE\r\n".length * NANOS_A_BYTE, waits.of(0).sum())
            }
        }
    }

    @Test
    fun `each connection waits for its own bytes alone`() {
        val waits = Waits()
        FakeNntpServer(body = { Body.Article(ARTICLE) }, throttle = RATE, throttleWait = waits::wait).use { server ->
            // The second connects after the first's greeting, so accept order is connect order.
            Client(server).use { first ->
                Client(server).use { second ->
                    first.body("<a@x>", ARTICLE)
                    second.send("DATE")
                    assertEquals(DATE, second.line())
                    val firstSent = "$GREETING\r\n".length + "222 0 <a@x>\r\n".length + ARTICLE.size + ".\r\n".length
                    assertEquals(firstSent * NANOS_A_BYTE, waits.of(0).sum())
                    assertEquals(("$GREETING\r\n".length + "$DATE\r\n".length) * NANOS_A_BYTE, waits.of(1).sum())
                }
            }
        }
    }

    /**
     * Four connections asked for an answer at once each wait for their own bytes, inside their waits at the same time: a
     * throttle shared under one lock would let one wait at a time, and its four would never meet; one shared byte budget
     * would make each wait longer than its own bytes take.
     */
    @Test
    fun `each connection has a throttle of its own, four waiting at once each for its own bytes`() {
        val waits = Waits()
        // Armed once the greetings are read: then each connection's first wait, for its answer, waits for the others'.
        val armed = AtomicBoolean(false)
        val together = CyclicBarrier(CONNECTIONS)
        val entered = ConcurrentHashMap.newKeySet<Int>()
        val met = ConcurrentHashMap.newKeySet<Int>()
        val wait = { connection: Int, nanos: Long ->
            waits.wait(connection, nanos)
            if (armed.get() && entered.add(connection)) {
                try {
                    together.await(MEET_LIMIT_MS, TimeUnit.MILLISECONDS)
                    met += connection
                } catch (_: TimeoutException) {
                    // Not all four came: they never waited at once.
                } catch (_: BrokenBarrierException) {
                    // Another's wait for them ran out first.
                }
            }
        }
        FakeNntpServer(body = { Body.Article(ARTICLE) }, throttle = RATE, throttleWait = wait).use { server ->
            // Each connects after the one before has its greeting, so accept order is the list's.
            val clients = List(CONNECTIONS) { Client(server) }
            try {
                armed.set(true)
                clients.forEachIndexed { i, client -> client.send("BODY <$i@x>") }
                clients.forEachIndexed { i, client ->
                    assertEquals("222 0 <$i@x>", client.line())
                    assertContentEquals(ARTICLE, client.bytes(ARTICLE.size))
                    assertEquals(".", client.line())
                }
                assertEquals((0 until CONNECTIONS).toSet(), met.toSet(), "connections that waited at once")
                repeat(CONNECTIONS) { i ->
                    val sent = "$GREETING\r\n".length + "222 0 <$i@x>\r\n".length + ARTICLE.size + ".\r\n".length
                    assertEquals(sent * NANOS_A_BYTE, waits.of(i).sum(), "connection $i waited for its own bytes alone")
                }
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    private companion object {
        /** How long a read waits for bytes that should come: only a failing test waits it out. */
        const val READ_LIMIT_MS = 10_000

        const val GREETING = "200 fake server ready"
        const val DATE = "111 20261015000000"

        /** 20,000 bytes a second: 50 µs a byte, and steps of 1,000 bytes. */
        const val RATE = 20_000L
        const val NANOS_A_BYTE = 1_000_000_000L / RATE
        const val STEP_NANOS = 1_000_000_000L / 20

        val ARTICLE = Random(7).nextBytes(4_500)

        const val CONNECTIONS = 4

        /** How long a connection's first wait waits for the others' to come at once: only a failing test waits it out. */
        const val MEET_LIMIT_MS = 5_000L
    }
}

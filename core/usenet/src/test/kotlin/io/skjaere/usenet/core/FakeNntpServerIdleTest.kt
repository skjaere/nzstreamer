package io.skjaere.usenet.core

import io.skjaere.usenet.core.FakeNntpServer.OnIdle
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [FakeNntpServer]'s connections die after sitting idle as its KDoc says, seen from a plain socket. */
class FakeNntpServerIdleTest {
    /** A plain client: sends a line and reads one back, or times out after [ANSWER_WAIT_MS]. */
    private class Client(server: FakeNntpServer) : AutoCloseable {
        val socket = server.provider().let { Socket(it.host, it.port) }.apply { soTimeout = ANSWER_WAIT_MS }
        private val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val greeting: String? = reader.readLine()

        fun send(line: String): String? {
            socket.getOutputStream().apply { write("$line\r\n".toByteArray(Charsets.ISO_8859_1)); flush() }
            return reader.readLine()
        }

        fun read(): String? = reader.readLine()

        override fun close() = socket.close()
    }

    @Test
    fun `a connection idle past the threshold goes silent for good and stays open`() {
        FakeNntpServer(idleAfterMs = IDLE_MS).use { server ->
            Client(server).use { client ->
                assertEquals("200 fake server ready", client.greeting)
                assertTrue(client.send("DATE")!!.startsWith("111 "))
                Thread.sleep(IDLE_MS + 100)

                // No answer, and no close either: the read waits out its timeout instead of seeing the end.
                assertFailsWith<SocketTimeoutException> { client.send("DATE") }
                assertFailsWith<SocketTimeoutException> { client.send("STAT <a@b>") }
                assertFalse(client.socket.isClosed)
                assertEquals(1, server.silenced.get())
                assertEquals(2, server.dropped.get())
                // A command dropped isn't one the server answered.
                assertEquals(listOf("DATE"), server.commands)
            }
        }
    }

    @Test
    fun `answers keep a connection alive, a DATE's as any other`() {
        FakeNntpServer(idleAfterMs = IDLE_MS).use { server ->
            Client(server).use { client ->
                repeat(8) {
                    Thread.sleep(IDLE_MS / 3)
                    assertTrue(client.send("DATE")!!.startsWith("111 "), "DATE $it")
                }
                assertEquals("430 no such article", client.send("STAT <a@b>"))
                assertEquals(0, server.silenced.get())
            }
        }
    }

    @Test
    fun `an idle connection closed with RST resets the next read`() {
        FakeNntpServer(idleAfterMs = IDLE_MS, onIdle = OnIdle.RST).use { server ->
            Client(server).use { client ->
                Thread.sleep(IDLE_MS + 100)
                val failure = assertFailsWith<SocketException> { client.send("DATE") }
                assertTrue("reset" in failure.message.orEmpty().lowercase(), "not a reset: $failure")
                assertEquals(1, server.silenced.get())
            }
        }
    }

    @Test
    fun `an idle connection closed with FIN ends the stream`() {
        FakeNntpServer(idleAfterMs = IDLE_MS, onIdle = OnIdle.FIN).use { server ->
            Client(server).use { client ->
                Thread.sleep(IDLE_MS + 100)
                assertNull(client.send("DATE"))
                assertEquals(1, server.silenced.get())
            }
        }
    }

    @Test
    fun `an idle connection kicked gets a 400 and then the stream's end`() {
        FakeNntpServer(idleAfterMs = IDLE_MS, onIdle = OnIdle.KICK).use { server ->
            Client(server).use { client ->
                Thread.sleep(IDLE_MS + 100)
                assertEquals("400 idle timeout", client.send("DATE"))
                assertNull(client.read())
                assertEquals(1, server.silenced.get())
            }
        }
    }

    @Test
    fun `only K of every M connections go dead`() {
        FakeNntpServer(idleAfterMs = IDLE_MS, affected = 1, outOf = 2).use { server ->
            // Each client connects after the last one's greeting, so accept order is connect order.
            val clients = List(4) { Client(server) }
            try {
                Thread.sleep(IDLE_MS + 100)
                val answered = clients.map { client -> runCatching { client.send("DATE") }.getOrNull() != null }
                assertEquals(listOf(false, true, false, true), answered)
                assertEquals(4, server.connections.get())
                assertEquals(2, server.silenced.get())
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    private companion object {
        const val IDLE_MS = 300L
        const val ANSWER_WAIT_MS = 500
    }
}

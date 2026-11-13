package io.skjaere.usenet.core

import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [FakeNntpServer]'s STAT answers wait for the STATs to come ([holdStatsFor]) as its KDoc says, seen from plain sockets. */
class FakeNntpServerHoldTest {
    /** A plain client: sends lines in one write, and reads lines back, giving up on one after [READ_LIMIT_MS]. */
    private class Client(server: FakeNntpServer) : AutoCloseable {
        private val socket = server.provider().let { Socket(it.host, it.port) }.apply { soTimeout = READ_LIMIT_MS }
        private val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val greeting: String? = reader.readLine()

        fun send(vararg lines: String) = socket.getOutputStream().run {
            write(lines.joinToString("") { "$it\r\n" }.toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        fun read(): String? = reader.readLine()

        /** Fails unless no line comes for [NO_ANSWER_MS]. */
        fun assertNothingComes() {
            socket.soTimeout = NO_ANSWER_MS
            assertFailsWith<SocketTimeoutException> { reader.readLine() }
            socket.soTimeout = READ_LIMIT_MS
        }

        override fun close() = socket.close()
    }

    @Test
    fun `STAT answers wait for every STAT held for, over all connections, and come in order`() {
        FakeNntpServer(has = { it == "<b@x>" }, holdStatsFor = 3).use { server ->
            Client(server).use { first ->
                Client(server).use { second ->
                    assertEquals("200 fake server ready", first.greeting)
                    assertEquals("200 fake server ready", second.greeting)
                    first.send("STAT <a@x>", "STAT <b@x>")
                    // Two of three came: no answer yet.
                    first.assertNothingComes()
                    second.send("STAT <c@x>")
                    assertEquals("430 no such article", first.read())
                    assertEquals("223 0 <b@x>", first.read())
                    assertEquals("430 no such article", second.read())
                    // Each was recorded before any was answered.
                    assertEquals(listOf("STAT <a@x>", "STAT <b@x>", "STAT <c@x>"), server.commands.sorted())
                    // Once let go, answers come at once.
                    second.send("STAT <d@x>")
                    assertEquals("430 no such article", second.read())
                    assertNull(server.statHoldTimedOut)
                }
            }
        }
    }

    @Test
    fun `held answers go after the time limit, which says how many STATs came, and a later command waits for them`() {
        FakeNntpServer(holdStatsFor = 2, holdStatsMs = HOLD_MS).use { server ->
            Client(server).use { client ->
                client.send("STAT <a@x>", "DATE")
                assertEquals("430 no such article", client.read())
                assertEquals("111 20261015000000", client.read())
                assertEquals("1 of the 2 STATs the answers waited for came in $HOLD_MS ms", server.statHoldTimedOut)
                assertEquals(listOf("STAT <a@x>", "DATE"), server.commands)
            }
        }
    }

    private companion object {
        /** How long a read waits for a line that should come: only a failing test waits it out. */
        const val READ_LIMIT_MS = 10_000

        /** Longer than a loopback answer takes, as a wait for one that must not come. */
        const val NO_ANSWER_MS = 300

        const val HOLD_MS = 100L
    }
}

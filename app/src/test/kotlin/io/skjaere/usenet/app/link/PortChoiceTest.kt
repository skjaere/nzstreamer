package io.skjaere.usenet.app.link

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** L12: 28462 unless another port was taken and saved before; any free one when that is busy, saved for next time. */
class PortChoiceTest {
    /** What [open] was asked for, in order; it binds the ports in [free] (0 as port 41_000), throws for the others. */
    private class Ports(private val free: Set<Int>) {
        val asked = mutableListOf<Int>()

        fun open(port: Int): Pair<String, Int> {
            asked += port
            if (port !in free) throw IOException("taken")
            return "server on $port" to if (port == 0) 41_000 else port
        }
    }

    @Test
    fun `the default port when none is remembered, saved`() {
        val ports = Ports(setOf(PortChoice.DEFAULT, 0))
        val bound = PortChoice.bind(remembered = null, open = ports::open)
        assertEquals(listOf(PortChoice.DEFAULT), ports.asked)
        assertEquals(28_462, bound.port)
        assertEquals("server on 28462", bound.value)
        assertTrue(bound.save, "saved, so it is the one remembered")
    }

    @Test
    fun `another free port when the default is taken, saved`() {
        val ports = Ports(setOf(0))
        val bound = PortChoice.bind(remembered = null, open = ports::open)
        assertEquals(listOf(PortChoice.DEFAULT, 0), ports.asked)
        assertEquals(41_000, bound.port)
        assertTrue(bound.save)
    }

    @Test
    fun `the remembered port first, nothing to save`() {
        val ports = Ports(setOf(PortChoice.DEFAULT, 30_123, 0))
        val bound = PortChoice.bind(remembered = 30_123, open = ports::open)
        assertEquals(listOf(30_123), ports.asked)
        assertEquals(30_123, bound.port)
        assertFalse(bound.save)
    }

    @Test
    fun `the remembered default port again, nothing to save`() {
        val ports = Ports(setOf(PortChoice.DEFAULT, 0))
        val bound = PortChoice.bind(remembered = PortChoice.DEFAULT, open = ports::open)
        assertEquals(28_462, bound.port)
        assertFalse(bound.save)
    }

    @Test
    fun `another free port when the remembered one is taken, saved`() {
        val ports = Ports(setOf(PortChoice.DEFAULT, 0))
        val bound = PortChoice.bind(remembered = 30_123, open = ports::open)
        assertEquals(listOf(30_123, 0), ports.asked)
        assertEquals(41_000, bound.port)
        assertTrue(bound.save)
    }

    @Test
    fun `a remembered port that isn't one is not tried`() {
        for (bad in listOf(0, -1, 65_536)) {
            val ports = Ports(setOf(PortChoice.DEFAULT, 0))
            assertEquals(28_462, PortChoice.bind(remembered = bad, open = ports::open).port, "remembered $bad")
        }
    }

    @Test
    fun `nowhere to bind throws`() {
        val ports = Ports(emptySet())
        assertThrows<IOException> { PortChoice.bind(remembered = 30_123, open = ports::open) }
        assertEquals(listOf(30_123, 0), ports.asked)
    }

    /** Over real sockets on loopback: a port another listener holds is taken, and another is bound and saved. */
    @Test
    fun `a port another listener holds`() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket().use { held ->
            held.reuseAddress = true
            held.bind(InetSocketAddress(loopback, 0))
            val bound = PortChoice.bind(remembered = null, default = held.localPort) { port ->
                val socket = ServerSocket()
                try {
                    // As the link's server binds: reuseAddress lets a port in TIME_WAIT be taken, not one that listens.
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(loopback, port))
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }
                socket to socket.localPort
            }
            bound.value.use { socket ->
                assertTrue(bound.port != held.localPort && bound.port == socket.localPort && bound.port > 0)
                assertTrue(bound.save)
            }
        }
    }
}

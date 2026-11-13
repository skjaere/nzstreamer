package io.skjaere.usenet.link

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The workstation's side of `LinkPlatformDeviceTest.listensForAPeer` (core:data's device test, L14), which listens for 60 s
 * on port 28462 of a device on the home network, the Shield over Ethernet say. Run it while that test listens:
 *
 *     ./gradlew :core:link:test --tests 'io.skjaere.usenet.link.LinkPeerClientTest' -PlinkPeer=<the device's address>[:port]
 *
 * It waits up to 60 s for the listener, then makes [ROUNDS] connections, each a full mutual handshake (pairing on both
 * ends: neither knows the other's key) and a line each way, and prints each handshake's time and the median, in ms.
 * Without linkPeer it is skipped; the case after it runs the same client against a listener here.
 */
class LinkPeerClientTest {
    @Test
    @EnabledIfSystemProperty(named = "linkPeer", matches = ".+")
    fun `a device listening for a peer handshakes with the workstation`() {
        val peer = System.getProperty("linkPeer")
        val host = peer.substringBefore(':')
        val port = peer.substringAfter(':', "").ifEmpty { "$PORT" }.toInt()
        val times = handshakes(InetSocketAddress(InetAddress.getByName(host), port))
        println("linkPeer: median of ${times.size}: ${times.sorted()[times.size / 2]} ms")
    }

    @Test
    fun `the client handshakes with a listener like the device's`() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val listener = thread(isDaemon = true) {
            server.use { listen(it, newKey()) }
        }
        val times = handshakes(InetSocketAddress(server.inetAddress, server.localPort))
        assertEquals(ROUNDS, times.size)
        listener.join(10_000)
        assertTrue(server.isClosed)
    }

    /** What the device test does with each connection: TLS as a server in pairing mode, then a line answered with a line. */
    private fun listen(server: ServerSocket, key: LinkKey) {
        repeat(ROUNDS) {
            val socket = server.accept()
            socket.soTimeout = 10_000
            val handshake = LinkTls.server(socket, key, PeerPolicy.Pairing)
            if (handshake is Handshake.Accepted) {
                handshake.socket.use {
                    if (it.inputStream.bufferedReader().readLine() == PING) it.outputStream.apply { write("$PONG\n".toByteArray()); flush() }
                }
            }
        }
    }

    /** [ROUNDS] handshakes with [address], after waiting up to 60 s for it to listen; each one's time, in ms. */
    private fun handshakes(address: InetSocketAddress): List<Long> {
        val key = newKey()
        val waitUntil = System.nanoTime() + 60_000_000_000
        return List(ROUNDS) { round ->
            val socket = connect(address, if (round == 0) waitUntil else System.nanoTime())
            socket.soTimeout = 10_000
            val started = System.nanoTime()
            val handshake = LinkTls.client(socket, key, PeerPolicy.Pairing)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (handshake !is Handshake.Accepted) fail("round ${round + 1}: no handshake ($handshake)")
            handshake.socket.use {
                it.outputStream.apply { write("$PING\n".toByteArray()); flush() }
                assertEquals(PONG, it.inputStream.bufferedReader().readLine(), "round ${round + 1}: no answer")
            }
            val session = handshake.session
            println("linkPeer: round ${round + 1}: ${session.protocol} ${session.cipherSuite} $millis ms, the device's key ${session.peer.short}")
            millis
        }
    }

    /** A connection to [address], tried every second until [until] (System.nanoTime) has passed, 2 s each, as the link's own. */
    private fun connect(address: InetSocketAddress, until: Long): Socket {
        while (true) {
            val socket = Socket()
            try {
                socket.connect(address, 2_000)
                return socket
            } catch (e: IOException) {
                socket.close()
                if (System.nanoTime() > until) fail("nothing listens at $address: ${e.javaClass.simpleName}")
                Thread.sleep(1_000)
            }
        }
    }

    private fun newKey(): LinkKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return LinkKey(pair.private, SelfSigned.certificate(pair))
    }

    private companion object {
        const val PORT = 28462
        const val ROUNDS = 10
        const val PING = "nzstreamer link spike: ping"
        const val PONG = "nzstreamer link spike: pong"
    }
}

package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Refused
import io.skjaere.usenet.link.LinkTestPeers.eventually
import io.skjaere.usenet.link.LinkTestPeers.linkKey
import io.skjaere.usenet.link.LinkTestPeers.loopback
import io.skjaere.usenet.link.LinkTestPeers.millisSince
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A connection closed under its own thread (the hello timer, a cancel, the client's close) as that thread steps from TLS
 * to the link: each step says the connection closed, and nothing is thrown out of core:link (L13). And a connection ended
 * with a last word in place of what waits ([LinkConnection.endWith], Remove's refusal), within a write's deadline.
 */
class LinkConnectionTest {
    private val opened = Opened()
    private val timer = linkTimer("test-timer")

    @AfterEach
    fun closeAll() {
        opened.close()
        timer.shutdownNow()
    }

    private fun connection(plain: Socket, limits: LinkLimits = LinkLimits()) = LinkConnection(plain, loopback, timer, limits, ::monotonicMillis)

    /** A connection whose TLS is up, the phone's end, its TLS socket, and the TV's end, which reads what it writes. */
    private fun tlsUp(limits: LinkLimits = LinkLimits()): Triple<LinkConnection, SSLSocket, SSLSocket> {
        val tv = linkKey()
        val phone = linkKey()
        val listener = opened.add(ServerSocket(0, 1, loopback))
        val tvSide = CompletableFuture.supplyAsync { LinkTls.server(opened.add(listener.accept()), tv, PeerPolicy.Pinned(setOf(phone.id))) }
        val plain = opened.connect(listener.localPort)
        val connection = connection(plain, limits)
        val ssl = assertIs<Handshake.Accepted>(LinkTls.client(plain, phone, PeerPolicy.Pinned(tv.id))).socket
        opened.add(ssl)
        val other = opened.add(assertIs<Handshake.Accepted>(tvSide.get(5, TimeUnit.SECONDS)).socket)
        return Triple(connection, ssl, other)
    }

    /** As Android's TLS has it once the plain socket under it is closed: the TLS socket reads as closed too. */
    @Test
    fun `TLS closed as it came up is a connection closed, nothing thrown`() {
        val (connection, ssl) = tlsUp()
        connection.close(LinkClose.TimedOut)
        ssl.close()
        assertFalse(connection.secured(ssl))
        assertEquals(LinkClose.TimedOut, connection.closeReason, "the first reason stands")
    }

    @Test
    fun `a writer started on a connection closed meanwhile says so, nothing thrown`() {
        val (connection, ssl) = tlsUp()
        assertEquals(true, connection.secured(ssl))
        connection.close(LinkClose.Closed)
        assertFalse(connection.startWriter())
        assertEquals(LinkClose.Closed, connection.closeReason)
    }

    @Test
    fun `a read timeout set on a socket closed meanwhile says so, nothing thrown`() {
        val plain = opened.connect(opened.add(ServerSocket(0, 1, loopback)).localPort)
        val connection = connection(plain)
        connection.close(LinkClose.TimedOut)
        assertFalse(connection.timeReads(10_000))
        assertEquals(LinkClose.TimedOut, connection.closeReason)
    }

    /** Remove's refusal (L30): what still waits for the writer is moot for a phone no longer paired, so it goes first. */
    @Test
    fun `endWith's frame goes before what waits, and nothing after it`() {
        val (connection, ssl, other) = tlsUp()
        assertTrue(connection.secured(ssl))
        // Queued while no writer runs: they wait, as frames do behind a peer that reads slowly.
        for (seq in 1..3) assertEquals(Sent.Queued, connection.send(Ack(seq)))
        connection.endWith(Refused(Refusal.NotPaired), LinkClose.Closed)
        assertEquals(Sent.Closed, connection.send(Ack(4)), "taken after the last word")
        connection.startWriter()
        val peer = Peer(other)
        assertEquals(Refused(Refusal.NotPaired), peer.next(), "what waited went first")
        assertNull(peer.next(), "a frame after the last word")
        eventually("still open once its last word is written") { connection.isClosed }
        assertEquals(LinkClose.Closed, connection.closeReason)
    }

    /** However the writer fares (here it never runs), a peer that reads slowly can't hold the end back past a write's deadline. */
    @Test
    fun `endWith closes the connection within a write's deadline, its frame written or not`() {
        val (connection, ssl, _) = tlsUp(LinkLimits(frameMillis = 300))
        assertTrue(connection.secured(ssl))
        val started = System.nanoTime()
        connection.endWith(Refused(Refusal.NotPaired), LinkClose.Closed)
        eventually("still open past the write's deadline") { connection.isClosed }
        val took = millisSince(started)
        assertTrue(took in 250..2_000, "closed after $took ms")
        assertEquals(LinkClose.Closed, connection.closeReason)
    }
}

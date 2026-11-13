package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Ping
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Pong
import io.skjaere.usenet.link.LinkMessage.Refused
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.LinkTestPeers.awaitPortFree
import io.skjaere.usenet.link.LinkTestPeers.eventually
import io.skjaere.usenet.link.LinkTestPeers.linkKey
import io.skjaere.usenet.link.LinkTestPeers.loopback
import io.skjaere.usenet.link.LinkTestPeers.millisSince
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's end of a link over real sockets on loopback (L2, L12, L24), against the real server and against fake TVs. */
class LinkClientTest {
    private val tv = linkKey()
    private val phone = linkKey()
    private val recorder = Recorder()
    private val tvSide = Recorder()
    private val events = LinkedBlockingQueue<LinkEvent>()
    private val opened = Opened()
    private val closers = mutableListOf<AutoCloseable>()
    private val ready = Hello.Ready(provider = true, indexer = true, tmdb = false)

    @AfterEach
    fun closeAll() {
        closers.forEach { it.close() }
        opened.close()
    }

    private fun client(limits: LinkLimits = LinkLimits(), versions: LinkVersions = LinkVersions.V1, allowPeer: (InetAddress) -> Boolean = { it.isLoopbackAddress }) =
        LinkClient(phone, "Galaxy phone", "0.9.0", versions, allowPeer, limits = limits).also { closers += it }

    /** A TV on [port], or any port for 0. A given port may be a closed server's: it is bound once that one has let it go. */
    private fun server(key: LinkKey = tv, port: Int = 0, paired: Set<KeyId> = setOf(phone.id), limits: LinkLimits = LinkLimits(), versions: LinkVersions = LinkVersions.V1): Int {
        if (port != 0) awaitPortFree(loopback, port)
        return LinkServer(
            bind = loopback as Inet4Address, port = port, key = key, name = "Living room TV", app = "0.9.1", versions = versions,
            paired = { paired }, pairedHello = { PairedHello(ready) }, allowPeer = { it.isLoopbackAddress }, limits = limits,
            handler = tvSide, onEvent = { events.put(it) },
        ).also { closers += it }.start()
    }

    private fun at(port: Int) = InetSocketAddress(loopback, port)

    /**
     * A TV that isn't NZStreamer's server: TLS in pairing mode with [key], then [script] on its end of the first
     * connection. Returns its port, and what the script returns.
     */
    private fun <T> fakeTv(key: LinkKey = tv, limits: LinkLimits = LinkLimits(), script: (Peer) -> T): Pair<Int, CompletableFuture<T>> {
        val server = opened.add(ServerSocket(0, 8, loopback))
        val result = CompletableFuture<T>()
        thread(isDaemon = true) {
            try {
                val socket = opened.add(server.accept())
                socket.soTimeout = 5_000
                val handshake = LinkTls.server(socket, key, PeerPolicy.Pairing) as Handshake.Accepted
                opened.add(handshake.socket)
                result.complete(script(Peer(handshake.socket, limits)))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return server.localPort to result
    }

    /** A fake TV that links as the real one does, then runs [then]. */
    private fun <T> linkingTv(limits: LinkLimits = LinkLimits(), then: (Peer) -> T) = fakeTv(limits = limits) { peer ->
        peer.send(Hello(1, 1, Role.Tv, "Living room TV", app = "0.9.1", ready = ready))
        assertIs<Hello>(peer.next())
        then(peer)
    }

    private fun linked(connect: Connect): PeerLink = assertIs<Connect.Linked>(connect, "$connect").link

    @Test
    fun `a pinned TV links, with its hello, and the link carries both ways`() {
        val port = server()
        val connect = assertIs<Connect.Linked>(client().connect(at(port), tv.id, recorder))
        assertEquals(Hello(1, 1, Role.Tv, "Living room TV", app = "0.9.1", ready = ready), connect.hello)
        assertEquals(tv.id, connect.link.peer)
        val up = tvSide.next<Heard.Linked>()
        assertEquals(Hello(1, 1, Role.Phone, "Galaxy phone", app = "0.9.0", purpose = Purpose.Use), up.hello)
        connect.link.send(Pause(1, "s-81"))
        assertEquals(Pause(1, "s-81"), tvSide.next<Heard.Received>().message)
        up.link.send(Ack(1, "s-81"))
        assertEquals(Ack(1, "s-81"), recorder.next<Heard.Received>().message)
        connect.link.close()
        assertEquals(LinkClose.Closed, recorder.next<Heard.Closed>().reason)
        assertEquals(LinkClose.PeerLeft, tvSide.next<Heard.Closed>().reason)
    }

    /** Forget on the phone (L30): its last word, an Unpair, reaches the TV before the link closes, and nothing goes after it. */
    @Test
    fun `a last word is written before the link closes`() {
        val port = server()
        val link = linked(client().connect(at(port), tv.id, recorder))
        tvSide.next<Heard.Linked>()
        link.finish(LinkMessage.Unpair)
        assertEquals(LinkMessage.Unpair, tvSide.next<Heard.Received>().message)
        assertEquals(LinkClose.Closed, recorder.next<Heard.Closed>().reason)
        assertFalse(link.isOpen)
        assertEquals(Sent.Closed, link.send(Pause(2, "s-81")))
    }

    @Test
    fun `a pin mismatch reports KeyChanged with the presented key's id, sends no frame, reads the TV's hello for its name and closes`() {
        val someTv = linkKey()
        val (port, sawFrame) = fakeTv(someTv) { peer ->
            peer.send(Hello(1, 1, Role.Tv, "Bedroom TV"))
            // Anything at all from the phone, or the end of the connection.
            peer.next() != null
        }
        val changed = assertIs<Connect.KeyChanged>(client().connect(at(port), tv.id, recorder))
        assertEquals(someTv.id, changed.presented)
        assertEquals("Bedroom TV", changed.name)
        assertFalse(changed.toString().contains("Bedroom"), changed.toString())
        assertFalse(sawFrame.get(5, TimeUnit.SECONDS), "the phone sent a frame")
        recorder.quiet()
    }

    @Test
    fun `a new connection to a cached address presenting another key is refused, with no session resumed`() {
        val first = ServerSocket(0, 8, loopback).use { it.localPort }
        server(tv, first)
        val client = client()
        val link = linked(client.connect(at(first), tv.id, recorder))
        link.close()
        recorder.next<Heard.Closed>()
        closers.forEach { it.takeIf { c -> c is LinkServer }?.close() }

        // Another NZStreamer TV on the address (DHCP gave it away): its key, not the session TLS had with the first.
        val other = linkKey()
        server(other, first)
        val changed = assertIs<Connect.KeyChanged>(client.connect(at(first), tv.id, recorder))
        assertEquals(other.id, changed.presented)
        assertEquals("Living room TV", changed.name)
        closers.forEach { it.takeIf { c -> c is LinkServer }?.close() }

        // The first one back: pinned again, a full handshake.
        server(tv, first)
        linked(client.connect(at(first), tv.id, recorder))
    }

    @Test
    fun `a TV that doesn't know the phone refuses it, and versions too far apart refuse on either side`() {
        assertEquals(Connect.Refused(Refusal.NotPaired), client().connect(at(server(paired = emptySet())), tv.id, recorder))

        // The TV speaks only versions this phone doesn't: the phone tells it so, and says it needs updating.
        val newer = server(versions = LinkVersions(pv = 2, minPv = 2))
        assertEquals(Connect.Refused(Refusal.UpdatePhone), client().connect(at(newer), tv.id, recorder))
        assertEquals(LinkEvent.Ended(LinkClose.Refused(Refusal.UpdatePhone)), generateSequence { events.poll(5, TimeUnit.SECONDS) }.first { it is LinkEvent.Ended })

        // The phone speaks only versions the TV doesn't.
        val older = server()
        assertEquals(Connect.Refused(Refusal.UpdateTv), client(versions = LinkVersions(pv = 2, minPv = 2)).connect(at(older), tv.id, recorder))
        assertEquals(LinkEvent.Ended(LinkClose.Refused(Refusal.UpdateTv)), generateSequence { events.poll(5, TimeUnit.SECONDS) }.first { it is LinkEvent.Ended })
        recorder.quiet()
    }

    @Test
    fun `pings keep an idle link up, and a TV that answers none is idle`() {
        val limits = LinkLimits(pingMillis = 100, idleMillis = 500)
        val port = server(limits = limits)
        val link = linked(client(limits).connect(at(port), tv.id, recorder))
        tvSide.next<Heard.Linked>()
        Thread.sleep(1_500)
        assertTrue(link.isOpen)
        // The pings were the link's own on both ends.
        recorder.quiet(100)
        tvSide.quiet(100)

        val (silent, _) = linkingTv { peer ->
            // It reads the pings and says nothing.
            while (peer.next() == Ping) Unit
        }
        val started = System.nanoTime()
        linked(client(limits).connect(at(silent), tv.id, recorder))
        assertEquals(LinkClose.Idle, recorder.next<Heard.Closed>().reason)
        assertTrue(millisSince(started) in 350..2_500)
    }

    @Test
    fun `status arrives in order`() {
        // The 200 go out at once, faster than any writer: the TV's queue holds them all, so none is a peer gone quiet.
        val limits = LinkLimits(framesPerSecond = 1_000, statusPerSecond = 1_000, writeQueue = 256)
        val port = server(limits = limits)
        linked(client(limits).connect(at(port), tv.id, recorder))
        val tvLink = tvSide.next<Heard.Linked>().link
        repeat(200) { tvLink.send(Status("s-81", Playback.Playing, mine = true, positionMs = it * 1_000L)) }
        val positions = List(200) { assertIs<Status>(recorder.next<Heard.Received>().message).positionMs }
        assertEquals(List(200) { it * 1_000L }, positions)
    }

    @Test
    fun `a TV's frames are held to the same limits`() {
        val limits = LinkLimits(frameMillis = 500, framesPerSecond = 6, statusPerSecond = 2)

        // Too big, refused from its length.
        val (big, _) = linkingTv { it.sendRaw(byteArrayOf(0, 1, 0, 1)); it.next() }
        linked(client(limits).connect(at(big), tv.id, recorder))
        assertEquals(LinkClose.Frame(FrameError.TooBig), recorder.next<Heard.Closed>().reason)

        // Too slow.
        val (slow, _) = linkingTv { peer ->
            val frame = LinkFrames.encode(LinkCodec.encode(Ack(1))!!)!!
            for (b in frame) {
                peer.sendRaw(byteArrayOf(b))
                Thread.sleep(150)
            }
        }
        linked(client(limits).connect(at(slow), tv.id, recorder))
        assertEquals(LinkClose.Frame(FrameError.TimedOut), recorder.next<Heard.Closed>().reason)

        // Not a message.
        val (bad, _) = linkingTv { it.sendRaw(LinkFrames.encode("Harbor Lights".toByteArray())!!); it.next() }
        linked(client(limits).connect(at(bad), tv.id, recorder))
        assertEquals(LinkClose.Bad(BadFrame.NotJson), recorder.next<Heard.Closed>().reason)

        // Too many: status past its own rate, then frames past theirs, are dropped, and the link stays.
        val (fast, _) = linkingTv { peer ->
            repeat(5) { peer.send(Status("s-81", Playback.Playing, positionMs = it.toLong())) }
            repeat(10) { peer.send(Ack(it)) }
            peer.next()
        }
        val link = linked(client(limits).connect(at(fast), tv.id, recorder))
        val heard = generateSequence { recorder.heard.poll(1, TimeUnit.SECONDS) }.toList()
        assertEquals(listOf(0L, 1L), heard.mapNotNull { ((it as? Heard.Received)?.message as? Status)?.positionMs })
        // Five statuses took five of the six frames a second, two of them heard; one Ack is left.
        assertEquals(listOf(0), heard.mapNotNull { ((it as? Heard.Received)?.message as? Ack)?.seq })
        assertTrue(link.isOpen)
        link.close()
        recorder.next<Heard.Closed>()

        // A TV's message out of place, and a refusal on a link.
        val (odd, _) = linkingTv { it.send(Play(1)); it.next() }
        linked(client(limits).connect(at(odd), tv.id, recorder))
        assertEquals(LinkClose.Unexpected, recorder.next<Heard.Closed>().reason)
        val (refusing, _) = linkingTv { it.send(Refused(Refusal.Busy)); it.next() }
        linked(client(limits).connect(at(refusing), tv.id, recorder))
        assertEquals(LinkClose.Refused(Refusal.Busy), recorder.next<Heard.Closed>().reason)
    }

    @Test
    fun `TLS and the hellos within the deadline, or the connect fails`() {
        val (port, _) = fakeTv { peer ->
            // TLS, then nothing.
            peer.endsWithin(5_000)
        }
        val started = System.nanoTime()
        assertEquals(Connect.Failed(ConnectFailure.TimedOut), client(LinkLimits(helloMillis = 600)).connect(at(port), tv.id, recorder))
        assertTrue(millisSince(started) in 450..2_500)

        // A plain listener that never starts TLS.
        val plain = opened.add(ServerSocket(0, 8, loopback))
        thread(isDaemon = true) { opened.add(plain.accept()) }
        assertEquals(Connect.Failed(ConnectFailure.TimedOut), client(LinkLimits(helloMillis = 600)).connect(at(plain.localPort), tv.id, recorder))
        recorder.quiet()
    }

    @Test
    fun `only the addresses the filter allows, and an address that isn't listening is unreachable`() {
        val port = server()
        assertEquals(Connect.Failed(ConnectFailure.NotAllowed), client(allowPeer = { false }).connect(at(port), tv.id, recorder))
        assertNull(tvSide.heard.poll(300, TimeUnit.MILLISECONDS))
        val closed = ServerSocket(0, 8, loopback).use { it.localPort }
        assertEquals(Connect.Failed(ConnectFailure.Unreachable), client().connect(at(closed), tv.id, recorder))
    }

    @Test
    fun `close ends every link at once, and nothing comes after it`() {
        val port = server()
        val client = client()
        val a = linked(client.connect(at(port), tv.id, recorder))
        val tvLink = tvSide.next<Heard.Linked>().link
        val stop = AtomicBoolean(false)
        thread(isDaemon = true) {
            while (!stop.get()) tvLink.send(Status("s-81", Playback.Playing))
        }
        Thread.sleep(200)
        val started = System.nanoTime()
        client.close()
        assertTrue(millisSince(started) < 500, "close took long")
        val after = recorder.heard.size
        Thread.sleep(500)
        stop.set(true)
        assertEquals(after, recorder.heard.size, "heard after close")
        assertFalse(a.isOpen)
        assertEquals(Connect.Failed(ConnectFailure.Closed), client.connect(at(port), tv.id, recorder))
        eventually("the TV still holds a link") { !tvLink.isOpen }
    }
}

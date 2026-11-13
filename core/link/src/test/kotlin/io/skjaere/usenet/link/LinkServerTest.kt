package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Ping
import io.skjaere.usenet.link.LinkMessage.Pong
import io.skjaere.usenet.link.LinkMessage.Refused
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.LinkTestPeers.clientHello
import io.skjaere.usenet.link.LinkTestPeers.eventually
import io.skjaere.usenet.link.LinkTestPeers.linkKey
import io.skjaere.usenet.link.LinkTestPeers.loopback
import io.skjaere.usenet.link.LinkTestPeers.millisSince
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The TV's listener over real sockets on loopback (L2, L23, L24), with limits shrunk where a test waits on one. */
class LinkServerTest {
    private val tv = linkKey()
    private val phone = linkKey()
    private val stranger = linkKey()
    private val events = LinkedBlockingQueue<LinkEvent>()
    private val recorder = Recorder()
    private val opened = Opened()
    private val servers = mutableListOf<LinkServer>()
    private val ready = Hello.Ready(provider = true, indexer = false, tmdb = true)

    @AfterEach
    fun closeAll() {
        servers.forEach { it.close() }
        opened.close()
    }

    private fun server(
        limits: LinkLimits = LinkLimits(),
        paired: Set<KeyId> = setOf(phone.id),
        versions: LinkVersions = LinkVersions.V1,
        allowPeer: (InetAddress) -> Boolean = { it.isLoopbackAddress },
        /** The paired set as the TV reads it each time; [paired] unless a test changes it as it goes. */
        pins: () -> Set<KeyId> = { paired },
        handler: LinkServer.Handler = recorder,
    ): LinkServer = LinkServer(
        bind = loopback as Inet4Address,
        key = tv,
        name = "Living room TV",
        app = "0.9.0",
        versions = versions,
        paired = pins,
        pairedHello = { PairedHello(ready, Hello.Session("s-81", mine = it == phone.id)) },
        allowPeer = allowPeer,
        limits = limits,
        handler = handler,
        onEvent = { events.put(it) },
    ).also { servers += it }

    private fun start(limits: LinkLimits = LinkLimits(), paired: Set<KeyId> = setOf(phone.id), versions: LinkVersions = LinkVersions.V1) =
        server(limits, paired, versions).start()

    private fun phoneHello(name: String = "Galaxy phone", purpose: Purpose? = Purpose.Use, pv: Int = 1, minPv: Int = 1) =
        Hello(pv, minPv, Role.Phone, name, app = "0.9.0", purpose = purpose)

    /** TLS from [from] with [key], the TV pinned (or not: any TV, for a stranger); the TV's hello not read yet. */
    private fun tls(port: Int, key: LinkKey = phone, from: String = "127.0.0.1", receiveBuffer: Int? = null): Peer {
        val socket = opened.connect(port, from, receiveBuffer)
        val socketOf = when (val handshake = LinkTls.client(socket, key, PeerPolicy.Pinned(tv.id))) {
            is Handshake.Accepted -> handshake.socket
            is Handshake.Refused -> handshake.socket
            is Handshake.Failed -> error("TLS failed: ${handshake.failure}")
        }
        opened.add(socketOf)
        return Peer(socketOf)
    }

    /** A phone whose link is up: the TV's hello read, its own sent, the TV's handler told. */
    private fun linked(port: Int, key: LinkKey = phone, from: String = "127.0.0.1", receiveBuffer: Int? = null): Pair<Peer, PeerLink> {
        val peer = tls(port, key, from, receiveBuffer)
        assertIs<Hello>(peer.next())
        peer.send(phoneHello())
        return peer to recorder.next<Heard.Linked>().link
    }

    private fun event(): LinkEvent = assertNotNull(events.poll(5, TimeUnit.SECONDS), "no event")

    /** The next event of type [T], those before it skipped. */
    private inline fun <reified T : LinkEvent> eventOf(): T {
        while (true) {
            val e = event()
            if (e is T) return e
        }
    }

    /** Whether [socket] gets nothing at all: no byte before the server closes it. */
    private fun unanswered(socket: Socket): Boolean = try {
        socket.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        // Reset: closed unanswered too.
        true
    }

    @Test
    fun `a paired phone gets the TV's full hello, and its link carries both ways`() {
        val port = start()
        val peer = tls(port)
        val hello = assertIs<Hello>(peer.next())
        assertEquals(Hello(1, 1, Role.Tv, "Living room TV", app = "0.9.0", ready = ready, session = Hello.Session("s-81", mine = true)), hello)
        peer.send(phoneHello())
        val up = recorder.next<Heard.Linked>()
        assertEquals("Galaxy phone", up.hello.name)
        assertEquals(phone.id, up.link.peer)
        assertEquals(1, up.link.pv)
        assertTrue(up.link.protocol == "TLSv1.3" && up.link.cipherSuite in LinkTls.CIPHER_SUITES, up.link.cipherSuite)
        assertTrue(up.link.handshakeMillis >= 0)

        peer.send(Pause(1, "s-81"))
        assertEquals(Pause(1, "s-81"), recorder.next<Heard.Received>().message)
        // A ping is the link's own: answered there, not heard.
        peer.send(Ping)
        assertEquals(Pong, peer.next())
        assertEquals(Sent.Queued, up.link.send(Ack(1, "s-81")))
        assertEquals(Ack(1, "s-81"), peer.next())
        recorder.quiet()
    }

    @Test
    fun `an unpaired key gets the short hello and Refused{not-paired}, whatever its own hello says`() {
        val port = start()
        for (claim in listOf(phoneHello(), phoneHello(name = "Living room TV"), phoneHello().copy(ready = ready, session = Hello.Session("s-81", true)))) {
            val peer = tls(port, stranger)
            // Its name and versions, nothing more (L23).
            assertEquals(Hello(1, 1, Role.Tv, "Living room TV"), peer.next())
            peer.send(claim)
            assertEquals(Refused(Refusal.NotPaired), peer.next())
            assertTrue(peer.endsWithin(2_000), "still open")
            assertEquals(LinkEvent.Refused(Refusal.NotPaired), eventOf<LinkEvent.Refused>())
        }
        // Come to pair while the TV isn't pairing.
        val pairing = tls(port, stranger)
        assertIs<Hello>(pairing.next())
        pairing.send(phoneHello(purpose = Purpose.Pair))
        assertEquals(Refused(Refusal.NotPairing), pairing.next())
        assertTrue(pairing.endsWithin(2_000))
        recorder.quiet()
    }

    @Test
    fun `minPv above either side gives update-tv or update-phone, and the highest both speak is used`() {
        // The phone needs a newer TV.
        val port = start()
        val needsTv = tls(port)
        assertIs<Hello>(needsTv.next())
        needsTv.send(phoneHello(pv = 3, minPv = 2))
        assertEquals(Refused(Refusal.UpdateTv), needsTv.next())
        assertEquals(LinkEvent.Refused(Refusal.UpdateTv), eventOf<LinkEvent.Refused>())

        // The TV needs a newer phone.
        val newer = start(versions = LinkVersions(pv = 3, minPv = 2))
        val needsPhone = tls(newer)
        assertEquals(3, assertIs<Hello>(needsPhone.next()).pv)
        needsPhone.send(phoneHello())
        assertEquals(Refused(Refusal.UpdatePhone), needsPhone.next())
        assertEquals(LinkEvent.Refused(Refusal.UpdatePhone), eventOf<LinkEvent.Refused>())

        // A newer phone that still speaks v1.
        val both = tls(port)
        assertIs<Hello>(both.next())
        both.send(phoneHello(pv = 4, minPv = 1))
        assertEquals(1, recorder.next<Heard.Linked>().link.pv)

        // The phone refuses the TV instead of answering its hello.
        val refusing = tls(port)
        assertIs<Hello>(refusing.next())
        refusing.send(Refused(Refusal.UpdatePhone))
        assertEquals(LinkEvent.Ended(LinkClose.Refused(Refusal.UpdatePhone)), eventOf<LinkEvent.Ended>())
        assertTrue(refusing.endsWithin(2_000))
    }

    @Test
    fun `a client with no certificate is closed`() {
        val port = start()
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            .socketFactory.createSocket(loopback, port) as SSLSocket
        opened.add(client)
        client.soTimeout = 5_000
        val read = try {
            client.startHandshake()
            client.inputStream.read()
        } catch (_: IOException) {
            -1
        }
        assertEquals(-1, read, "the client read data")
        assertIs<LinkEvent.TlsFailed>(event())
        recorder.quiet()
    }

    @Test
    fun `a peer the filter refuses is dropped unanswered`() {
        val port = server(allowPeer = { it.hostAddress == "127.0.0.1" }).start()
        val socket = opened.connect(port, from = "127.0.0.2")
        socket.getOutputStream().write(clientHello())
        assertTrue(unanswered(socket), "answered")
        assertEquals(LinkEvent.Dropped(Drop.NotAllowed), event())
        // Its own address still links.
        linked(port)
    }

    @Test
    fun `a ClientHello sent one byte at a time, each inside the read timeout, is closed at the hello deadline`() {
        val port = start(LinkLimits(helloMillis = 600))
        val socket = opened.connect(port)
        val hello = clientHello()
        val stop = AtomicBoolean(false)
        thread(isDaemon = true) {
            try {
                // A byte every 100 ms: the whole ClientHello would take tens of seconds.
                for (b in hello) {
                    if (stop.get()) break
                    socket.getOutputStream().apply { write(b.toInt()); flush() }
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
                // Closed by the server.
            }
        }
        val started = System.nanoTime()
        assertTrue(unanswered(socket), "answered")
        stop.set(true)
        val elapsed = millisSince(started)
        assertTrue(elapsed in 450..2_500, "closed after $elapsed ms")
        assertEquals(LinkEvent.Ended(LinkClose.TimedOut), event())
    }

    @Test
    fun `no hello in time closes the connection`() {
        val port = start(LinkLimits(helloMillis = 600))
        val started = System.nanoTime()
        val peer = tls(port)
        assertIs<Hello>(peer.next())
        assertTrue(peer.endsWithin(3_000), "still open")
        val elapsed = millisSince(started)
        assertTrue(elapsed in 450..2_500, "closed after $elapsed ms")
        assertEquals(LinkEvent.Ended(LinkClose.TimedOut), event())
        recorder.quiet()
    }

    @Test
    fun `silence closes a link`() {
        val port = start(LinkLimits(idleMillis = 600))
        val (peer, link) = linked(port)
        val started = System.nanoTime()
        val closed = recorder.next<Heard.Closed>()
        assertEquals(LinkClose.Idle, closed.reason)
        assertTrue(closed.link === link)
        assertTrue(millisSince(started) in 400..2_500)
        assertTrue(peer.endsWithin(2_000))
        assertFalse(link.isOpen)
        assertEquals(Sent.Closed, link.send(Ack(1)))
    }

    @Test
    fun `six silent sockets from one address, opened again as they are closed, don't keep a paired phone out`() {
        val port = start()
        val stop = AtomicBoolean(false)
        repeat(6) {
            thread(isDaemon = true) {
                while (!stop.get()) {
                    try {
                        val socket = opened.connect(port, from = "127.0.0.2")
                        socket.soTimeout = 100
                        // Silent: it waits for the server to close it, then opens again.
                        while (!stop.get()) {
                            try {
                                if (socket.getInputStream().read() < 0) break
                            } catch (_: SocketTimeoutException) {
                                // Still open.
                            }
                        }
                        socket.close()
                    } catch (_: IOException) {
                        // Refused or reset: again.
                    }
                    Thread.sleep(10)
                }
            }
        }
        Thread.sleep(1_000)
        try {
            val (peer, link) = linked(port, from = "127.0.0.3")
            assertEquals(Sent.Queued, link.send(Ack(1)))
            assertEquals(Ack(1), peer.next())
        } finally {
            stop.set(true)
        }
        val drops = generateSequence { events.poll() }.filterIsInstance<LinkEvent.Dropped>().map { it.why }.toSet()
        assertTrue(Drop.TooManyFromAddress in drops || Drop.TooManyAccepts in drops, "$drops")
    }

    @Test
    fun `with every slot taken, a new connection takes the slot of the oldest one still before its hello`() {
        val port = start(LinkLimits(connections = 3, useLinks = 3))
        val oldest = opened.connect(port, from = "127.0.0.2")
        Thread.sleep(100)
        val middle = opened.connect(port, from = "127.0.0.3")
        val newest = opened.connect(port, from = "127.0.0.4")
        Thread.sleep(100)
        val started = System.nanoTime()
        opened.connect(port, from = "127.0.0.5")
        assertTrue(unanswered(oldest), "the oldest kept its slot")
        assertTrue(millisSince(started) < 2_000)
        assertEquals(LinkEvent.Ended(LinkClose.Evicted), event())
        for (socket in listOf(middle, newest)) {
            socket.soTimeout = 300
            assertFalse(unanswered(socket), "a newer one lost its slot")
        }
    }

    @Test
    fun `with every slot a paired link, a new connection is closed and the links keep theirs`() {
        val other = linkKey()
        val port = start(LinkLimits(connections = 2, useLinks = 2), paired = setOf(phone.id, other.id))
        val (first, firstLink) = linked(port, phone, from = "127.0.0.2")
        val (second, secondLink) = linked(port, other, from = "127.0.0.3")
        val third = opened.connect(port, from = "127.0.0.4")
        third.getOutputStream().write(clientHello())
        assertTrue(unanswered(third), "answered")
        assertEquals(LinkEvent.Dropped(Drop.Full), eventOf<LinkEvent.Dropped>())
        firstLink.send(Ack(1))
        secondLink.send(Ack(2))
        assertEquals(Ack(1), first.next())
        assertEquals(Ack(2), second.next())
    }

    @Test
    fun `the 11th accept in a minute from an address without a paired link is closed before TLS`() {
        val port = start()
        repeat(10) {
            opened.connect(port, from = "127.0.0.2").close()
            // Each is taken and ends before the next, so the slots aren't what turns one away.
            Thread.sleep(50)
        }
        val eleventh = opened.connect(port, from = "127.0.0.2")
        eleventh.getOutputStream().write(clientHello())
        assertTrue(unanswered(eleventh), "answered")
        assertEquals(LinkEvent.Dropped(Drop.TooManyAccepts), eventOf<LinkEvent.Dropped>())

        // An address with a live paired link isn't held to it.
        val (_, link) = linked(port, from = "127.0.0.3")
        repeat(11) { opened.connect(port, from = "127.0.0.3").close() }
        Thread.sleep(200)
        val more = opened.connect(port, from = "127.0.0.3")
        more.getOutputStream().write(clientHello())
        assertFalse(unanswered(more), "closed before TLS")
        assertTrue(link.isOpen)
    }

    @Test
    fun `a peer that stops reading is closed alone`() {
        val other = linkKey()
        val port = start(LinkLimits(writeQueue = 4, frameMillis = 1_000), paired = setOf(phone.id, other.id))
        val (reading, readingLink) = linked(port, phone, from = "127.0.0.2")
        val (_, stuckLink) = linked(port, other, from = "127.0.0.3", receiveBuffer = 4_096)
        val big = Status("s-81", Playback.Playing, title = "x".repeat(60_000))
        val started = System.nanoTime()
        // It reads nothing: the frames fill the socket's buffers, then the queue, or a write waits past its deadline.
        while (stuckLink.isOpen && millisSince(started) < 20_000) {
            stuckLink.send(big)
            Thread.sleep(1)
        }
        assertFalse(stuckLink.isOpen, "a peer that reads nothing is still linked")
        val closed = recorder.next<Heard.Closed>()
        assertTrue(closed.link === stuckLink)
        assertEquals(LinkClose.Backlog, closed.reason)
        assertTrue(readingLink.isOpen)
        readingLink.send(Ack(7))
        assertEquals(Ack(7), reading.next())
    }

    @Test
    fun `nothing comes after close`() {
        val other = linkKey()
        val server = server(paired = setOf(phone.id, other.id))
        val port = server.start()
        val (busy, _) = linked(port, phone, from = "127.0.0.2")
        val (quiet, _) = linked(port, other, from = "127.0.0.3")
        val waiting = opened.connect(port, from = "127.0.0.4")
        val stop = AtomicBoolean(false)
        thread(isDaemon = true) {
            try {
                var seq = 0
                while (!stop.get()) busy.send(Pause(++seq, "s-81"))
            } catch (_: IOException) {
                // Closed.
            }
        }
        Thread.sleep(200)
        val started = System.nanoTime()
        server.close()
        assertTrue(millisSince(started) < 500, "close took long")
        val after = recorder.heard.size
        assertTrue(quiet.endsWithin(1_000))
        assertTrue(unanswered(waiting.also { it.soTimeout = 1_000 }))
        stop.set(true)
        Thread.sleep(500)
        assertEquals(after, recorder.heard.size, "heard after close")
        assertNull(generateSequence { events.poll() }.firstOrNull { it is LinkEvent.Ended }, "an event after close")
        try {
            Socket().use { it.connect(InetSocketAddress(loopback, port), 1_000) }
            error("the port still takes connections")
        } catch (_: ConnectException) {
            // Closed.
        }
        server.close()
    }

    @Test
    fun `commands over the rate get Rejected{too-fast}, and the rest are heard`() {
        val port = start(LinkLimits(framesPerSecond = 5))
        val (peer, _) = linked(port)
        for (seq in 1..8) peer.send(Pause(seq, "s-81"))
        repeat(5) { assertEquals(Pause(it + 1, "s-81"), recorder.next<Heard.Received>().message) }
        assertEquals(listOf(6, 7, 8), List(3) { assertIs<Rejected>(peer.next()).also { r -> assertEquals(Rejection.TooFast, r.reason) }.seq })
        recorder.quiet()
        // A second later there is room again.
        Thread.sleep(1_100)
        peer.send(Pause(9, "s-81"))
        assertEquals(Pause(9, "s-81"), recorder.next<Heard.Received>().message)
    }

    @Test
    fun `a command this TV doesn't know gets Rejected{unsupported}, and anything out of place closes the link`() {
        val port = start()
        val (peer, link) = linked(port)
        peer.sendRaw(LinkFrames.encode("""{"t":"skip-intro","seq":3,"session":"s-81"}""".toByteArray())!!)
        assertEquals(Rejected(3, Rejection.Unsupported), peer.next())
        // One with no seq is let go.
        peer.sendRaw(LinkFrames.encode("""{"t":"volume","level":3}""".toByteArray())!!)
        peer.send(Ping)
        assertEquals(Pong, peer.next())
        recorder.quiet()

        // A TV's message from a phone.
        peer.send(Status("s-81", Playback.Playing))
        val closed = recorder.next<Heard.Closed>()
        assertTrue(closed.link === link)
        assertEquals(LinkClose.Unexpected, closed.reason)
        assertTrue(peer.endsWithin(2_000))

        // A frame that isn't a message.
        val (bad, _) = linked(port)
        bad.sendRaw(LinkFrames.encode("""{"t":"pause","seq":"Harbor Lights"}""".toByteArray())!!)
        assertEquals(LinkClose.Bad(BadFrame.BadFields), recorder.next<Heard.Closed>().reason)
        val (big, _) = linked(port)
        big.sendRaw(byteArrayOf(0, 1, 0, 1))
        assertEquals(LinkClose.Frame(FrameError.TooBig), recorder.next<Heard.Closed>().reason)
    }

    @Test
    fun `the same phone linking again replaces its older link, and a phone past the use links is told busy`() {
        val other = linkKey()
        val third = linkKey()
        val port = start(LinkLimits(useLinks = 2), paired = setOf(phone.id, other.id, third.id))
        val (old, oldLink) = linked(port, phone)
        val fresh = tls(port, phone)
        assertIs<Hello>(fresh.next())
        fresh.send(phoneHello())
        // The new link's arrival and the old one's end, in either order.
        val two = List(2) { recorder.next<Heard>() }
        val freshLink = two.filterIsInstance<Heard.Linked>().single().link
        val replaced = two.filterIsInstance<Heard.Closed>().single()
        assertTrue(replaced.link === oldLink)
        assertEquals(LinkClose.Replaced, replaced.reason)
        assertTrue(old.endsWithin(2_000))
        linked(port, other)
        val busy = tls(port, third)
        assertIs<Hello>(busy.next())
        busy.send(phoneHello())
        assertEquals(Refused(Refusal.Busy), busy.next())
        assertEquals(LinkEvent.Refused(Refusal.Busy), eventOf<LinkEvent.Refused>())
        freshLink.send(Ack(1))
        assertEquals(Ack(1), fresh.next())
    }

    @Test
    fun `disconnect closes a phone's links at once`() {
        val other = linkKey()
        val server = server(paired = setOf(phone.id, other.id))
        val port = server.start()
        val (gone, goneLink) = linked(port, phone)
        val (stays, staysLink) = linked(port, other)
        server.disconnect(phone.id)
        assertTrue(gone.endsWithin(1_000))
        val closed = recorder.next<Heard.Closed>()
        assertTrue(closed.link === goneLink)
        assertEquals(LinkClose.Closed, closed.reason)
        staysLink.send(Ack(1))
        assertEquals(Ack(1), stays.next())
        eventually("the link still reads open") { !goneLink.isOpen }
    }

    /**
     * L4a: Remove tells each link of that phone it is not paired, as the link's last frame, before it closes: a phone
     * linked as it happens knows why at once, not only on its next try. Another phone's link goes on.
     */
    @Test
    fun `disconnect with a refusal tells each link of that phone before it closes`() {
        val other = linkKey()
        val pins = AtomicReference(setOf(phone.id, other.id))
        val server = server(pins = { pins.get() })
        val port = server.start()
        val (stays, staysLink) = linked(port, other)
        // The phone's own end, as the app has it: a LinkClient whose link is up.
        val client = LinkClient(phone, "Galaxy phone", "0.9.0", allowPeer = { it.isLoopbackAddress })
        val ended = LinkedBlockingQueue<LinkClose>()
        val heard = object : LinkHandler {
            override fun received(link: PeerLink, message: LinkMessage) = Unit
            override fun closed(link: PeerLink, reason: LinkClose) = ended.put(reason)
        }
        try {
            assertIs<Connect.Linked>(client.connect(InetSocketAddress(loopback, port), tv.id, heard))
            val phoneLink = recorder.next<Heard.Linked>().link

            // Remove: the key out of the paired set first, as the app does, then its links told and closed.
            pins.set(setOf(other.id))
            server.disconnect(phone.id, Refusal.NotPaired)
            assertEquals(LinkClose.Refused(Refusal.NotPaired), ended.poll(5, TimeUnit.SECONDS), "the phone's link says why it closed")
            val closed = recorder.next<Heard.Closed>()
            assertTrue(closed.link === phoneLink)
            assertEquals(LinkClose.Closed, closed.reason, "this end closed it")
            staysLink.send(Ack(1))
            assertEquals(Ack(1), stays.next())

            // The phone's next try is refused the same way.
            assertEquals(Connect.Refused(Refusal.NotPaired), client.connect(InetSocketAddress(loopback, port), tv.id, heard))
        } finally {
            client.close()
        }
    }

    /** L30: a phone's own Unpair closes its links plainly, as before: the phone knows why already. */
    @Test
    fun `disconnect with no refusal closes a phone's links with nothing said`() {
        val server = server()
        val port = server.start()
        val (gone, _) = linked(port, phone)
        server.disconnect(phone.id)
        assertEquals(null, gone.next(), "closed with no last word")
        assertEquals(LinkClose.Closed, recorder.next<Heard.Closed>().reason)
    }

    /**
     * L30: once Remove returns, nothing the phone sends reaches the TV's handler, though its link is still ending (here
     * the phone reads nothing, so what was queued for it waits), and the link is gone within a write's deadline.
     */
    @Test
    fun `after Remove nothing the phone sends is heard, and its link ends within a write's deadline`() {
        val limits = LinkLimits(frameMillis = 3_000, writeQueue = 200)
        val pins = AtomicReference(setOf(phone.id))
        val server = server(limits, pins = { pins.get() })
        val port = server.start()
        val (stuck, stuckLink) = linked(port, phone, receiveBuffer = 4_096)
        // More than the sockets' buffers take: the writer waits on the phone, and the rest wait behind it.
        val big = Status("s-81", Playback.Playing, title = "x".repeat(60_000))
        repeat(100) { assertEquals(Sent.Queued, stuckLink.send(big)) }
        Thread.sleep(200)

        pins.set(emptySet())
        val removed = System.nanoTime()
        server.disconnect(phone.id, Refusal.NotPaired)
        stuck.send(Pause(1, "s-81"))
        val after = mutableListOf<Heard>()
        while (after.lastOrNull() !is Heard.Closed) after += recorder.heard.poll(limits.frameMillis + 2_000, TimeUnit.MILLISECONDS) ?: break
        val took = millisSince(removed)
        assertEquals(listOf("Closed"), after.map { it::class.simpleName }, "heard of the removed phone's link after the Remove")
        assertTrue(took <= limits.frameMillis + 1_500, "its link ended $took ms after the Remove")
    }

    /**
     * L30, L24: a removed phone's link that is still ending holds no use slot, and no longer frees its address from the
     * accept limit: both go with the Remove, not with the link's last write.
     */
    @Test
    fun `a removed phone's use slot and its address's freedom from the accept limit go with the Remove`() {
        val other = linkKey()
        val pins = AtomicReference(setOf(phone.id, other.id))
        val server = server(LinkLimits(writeQueue = 200, useLinks = 1, acceptsPerWindow = 1), pins = { pins.get() })
        val port = server.start()
        val (_, stuckLink) = linked(port, phone, from = "127.0.0.2", receiveBuffer = 4_096)
        // Its end can't be written at once: the phone reads nothing.
        val big = Status("s-81", Playback.Playing, title = "x".repeat(60_000))
        repeat(100) { assertEquals(Sent.Queued, stuckLink.send(big)) }
        Thread.sleep(200)
        pins.set(setOf(other.id))
        server.disconnect(phone.id, Refusal.NotPaired)

        // Another paired phone takes the one use slot.
        val fresh = tls(port, other, from = "127.0.0.3")
        assertIs<Hello>(fresh.next())
        fresh.send(phoneHello())
        val up = recorder.heard.poll(5, TimeUnit.SECONDS)
        val slotTaken = (up as? Heard.Linked)?.link?.peer == other.id
        // The removed phone's address spent its one accept this minute.
        val again = opened.connect(port, from = "127.0.0.2")
        again.getOutputStream().write(clientHello())
        val held = unanswered(again)
        assertEquals(
            listOf(true, true), listOf(slotTaken, held),
            "[another phone took the use slot, the removed phone's address is held to the accept limit]; events ${generateSequence { events.poll() }.toList()}",
        )
        assertTrue(stuckLink.isOpen, "the removed link ended already, so this shows nothing")
    }

    @Test
    fun `a phone removed while its TLS is under way is refused not-paired, and no link of it comes up`() {
        val pins = AtomicReference(setOf(phone.id))
        val asked = CountDownLatch(1)
        val server = server(pins = { pins.get().also { asked.countDown() } })
        val port = server.start()
        val socket = opened.connect(port)
        // The TV has read the paired set for this connection: Remove comes now, before the phone's TLS goes on.
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        pins.set(emptySet())
        server.disconnect(phone.id)
        val secured = when (val handshake = LinkTls.client(socket, phone, PeerPolicy.Pinned(tv.id))) {
            is Handshake.Accepted -> handshake.socket
            else -> error("TLS: $handshake")
        }
        val peer = Peer(opened.add(secured))
        val hello = assertIs<Hello>(peer.next())
        assertNull(hello.ready, "a removed phone was told the TV's readiness")
        peer.send(phoneHello())
        assertEquals(Refused(Refusal.NotPaired), peer.next())
        assertTrue(peer.endsWithin(2_000))
        assertEquals(LinkEvent.Refused(Refusal.NotPaired), eventOf<LinkEvent.Refused>())
        recorder.quiet()
    }

    @Test
    fun `a phone removed between the TV's hello and its own is refused not-paired`() {
        val pins = AtomicReference(setOf(phone.id))
        val port = server(pins = { pins.get() }).start()
        val peer = tls(port)
        assertIs<Hello.Ready>(assertIs<Hello>(peer.next()).ready)
        pins.set(emptySet())
        peer.send(phoneHello())
        assertEquals(Refused(Refusal.NotPaired), peer.next())
        assertTrue(peer.endsWithin(2_000))
        recorder.quiet()
    }

    /**
     * #291: a Remove that lands as a phone's link comes up, once it has its use slot and before its writer starts, is
     * still told to that phone, and the TV's handler never hears of the link: a removed phone can't show as connected.
     */
    @Test
    fun `a Remove as a phone's link comes up is told to the phone, and its link is never heard of`() {
        val pins = AtomicReference(setOf(phone.id))
        val server = server(pins = { pins.get() })
        server.linking = { key ->
            // Remove, as the app does it: the key out of the paired set first, then its links told and closed.
            pins.set(emptySet())
            server.disconnect(key, Refusal.NotPaired)
        }
        val port = server.start()
        val peer = tls(port)
        assertIs<Hello>(peer.next())
        peer.send(phoneHello())
        assertEquals(Refused(Refusal.NotPaired), peer.next(), "the phone told why")
        assertTrue(peer.endsWithin(2_000))
        recorder.quiet()
    }

    /**
     * #291: what `disconnect` promises holds for a message the link read just before the Remove: once `disconnect` has
     * returned, the handler isn't given it, and hears only that the link closed.
     */
    @Test
    fun `a message read just before a Remove isn't delivered once disconnect has returned`() {
        val pins = AtomicReference(setOf(phone.id))
        val server = server(pins = { pins.get() })
        val removed = CountDownLatch(1)
        server.reading = { key, message ->
            if (message is Pause) {
                pins.set(emptySet())
                server.disconnect(key, Refusal.NotPaired)
                removed.countDown()
            }
        }
        val port = server.start()
        val (peer, _) = linked(port)
        peer.send(Pause(1, "s-81"))
        assertTrue(removed.await(5, TimeUnit.SECONDS), "removed as the Pause was read")
        val after = mutableListOf<Heard>()
        while (after.lastOrNull() !is Heard.Closed) after += recorder.heard.poll(5, TimeUnit.SECONDS) ?: break
        assertEquals(listOf("Closed"), after.map { it::class.simpleName }, "heard of the removed phone's link after disconnect returned")
    }

    /**
     * #291: `disconnect` from a thread of its own (the app's Remove) waits for a delivery under way on the link's thread,
     * and from the moment it returns nothing that link brings in reaches the handler, but that it closed.
     */
    @Test
    fun `disconnect from another thread waits for a delivery under way, and nothing that link brings in comes after it`() {
        val pins = AtomicReference(setOf(phone.id))
        val heard = java.util.concurrent.CopyOnWriteArrayList<String>()
        val delivering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handler = object : LinkServer.Handler {
            override fun linked(link: PeerLink, hello: Hello) = recorder.linked(link, hello)

            override fun received(link: PeerLink, message: LinkMessage) {
                heard += "received ${message::class.simpleName}"
                if (message is Pause) {
                    delivering.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }

            override fun closed(link: PeerLink, reason: LinkClose) {
                heard += "closed"
            }
        }
        val server = server(pins = { pins.get() }, handler = handler)
        val port = server.start()
        val (peer, _) = linked(port)
        peer.send(Pause(1, "s-81"))
        assertTrue(delivering.await(5, TimeUnit.SECONDS), "the Pause is being handled")
        // Remove on the TV, from another thread, as the Pause is handled.
        pins.set(emptySet())
        val returned = CountDownLatch(1)
        thread {
            server.disconnect(phone.id, Refusal.NotPaired)
            heard += "returned"
            returned.countDown()
        }
        assertFalse(returned.await(500, TimeUnit.MILLISECONDS), "disconnect waits for the delivery under way")
        peer.send(LinkMessage.Resume(2, "s-81"))
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        eventually("the link's close heard") { "closed" in heard }
        Thread.sleep(300)
        val after = heard.dropWhile { it != "returned" }.drop(1)
        assertTrue(after.none { it.startsWith("received") }, "heard after disconnect returned: $heard")
        assertEquals("received Pause", heard.first(), "the Pause, handled as it was read")
    }
}

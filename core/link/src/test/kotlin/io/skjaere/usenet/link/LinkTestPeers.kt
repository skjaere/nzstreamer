package io.skjaere.usenet.link

import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** What the link tests share: keys, a peer driven by hand, and a handler that records what it hears. */
internal object LinkTestPeers {
    val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    fun linkKey(): LinkKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return LinkKey(pair.private, SelfSigned.certificate(pair))
    }

    /** The bytes of a real TLS ClientHello, as a browser or a scanner would send first. */
    fun clientHello(): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine("127.0.0.1", 1)
        engine.useClientMode = true
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    fun millisSince(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    fun eventually(what: String, seconds: Long = 5, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(20)
        }
    }

    /**
     * Waits until nothing listens on [port] at [address] any more. A listener closed while its thread is blocked in
     * accept() keeps the port until that thread wakes (backlog 301): a LinkServer's close() returns before then, and a
     * bind of the same port at once fails now and then. A test that binds a closed server's port again waits here first.
     */
    fun awaitPortFree(address: InetAddress, port: Int, seconds: Long = 5) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(address, port))
                }
                return
            } catch (e: BindException) {
                assertTrue(System.nanoTime() < until, "port $port is still taken after $seconds s")
                Thread.sleep(5)
            }
        }
    }
}

/** Sockets and servers a test opened, closed after it. */
internal class Opened : Closeable {
    private val all = mutableListOf<Closeable>()

    fun <T : Closeable> add(c: T): T = c.also { synchronized(all) { all += it } }

    /** A plain connection to [port] on loopback from [from] (any 127.x address is loopback on Linux). */
    fun connect(port: Int, from: String = "127.0.0.1", receiveBuffer: Int? = null): Socket = add(Socket()).also { socket ->
        receiveBuffer?.let { socket.receiveBufferSize = it }
        socket.bind(InetSocketAddress(from, 0))
        socket.connect(InetSocketAddress(LinkTestPeers.loopback, port), 2_000)
        socket.soTimeout = 5_000
    }

    override fun close() {
        synchronized(all) { all.toList() }.forEach {
            try {
                it.close()
            } catch (_: IOException) {
                // Closed already.
            }
        }
    }
}

/** One end of a link driven by hand, over TLS already up: what it sends and reads are frames the test chooses. */
internal class Peer(val socket: SSLSocket, limits: LinkLimits = LinkLimits()) {
    private val reader = FrameReader(socket, limits, ::monotonicMillis)

    fun send(message: LinkMessage) = sendRaw(LinkFrames.encode(LinkCodec.encode(message)!!)!!)

    fun sendRaw(bytes: ByteArray) {
        socket.outputStream.apply {
            write(bytes)
            flush()
        }
    }

    /** The next message; null when the connection ended (or a frame broke) instead. */
    fun next(): LinkMessage? = when (val read = reader.read()) {
        is FrameRead.Frame -> when (val decoded = LinkCodec.decode(read.payload)) {
            is Decoded.Read -> decoded.value
            is Decoded.Bad -> fail("a frame that isn't a message: ${decoded.reason}")
        }
        else -> null
    }

    /** Whether the connection ends, with nothing more read, within [millis]. */
    fun endsWithin(millis: Long): Boolean {
        socket.soTimeout = millis.toInt()
        return try {
            socket.inputStream.read() < 0
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }
}

/** What a [LinkHandler] heard, in order. */
internal sealed interface Heard {
    class Linked(val link: PeerLink, val hello: LinkMessage.Hello) : Heard
    class Received(val link: PeerLink, val message: LinkMessage) : Heard
    class Closed(val link: PeerLink, val reason: LinkClose) : Heard
}

internal class Recorder : LinkServer.Handler {
    val heard = LinkedBlockingQueue<Heard>()

    override fun linked(link: PeerLink, hello: LinkMessage.Hello) = heard.put(Heard.Linked(link, hello))

    override fun received(link: PeerLink, message: LinkMessage) = heard.put(Heard.Received(link, message))

    override fun closed(link: PeerLink, reason: LinkClose) = heard.put(Heard.Closed(link, reason))

    inline fun <reified T : Heard> next(seconds: Long = 5): T {
        val got = assertNotNull(heard.poll(seconds, TimeUnit.SECONDS), "nothing heard")
        return got as? T ?: fail("heard ${got::class.simpleName}, not ${T::class.simpleName}")
    }

    /** Nothing more within [millis]. */
    fun quiet(millis: Long = 500) {
        val got = heard.poll(millis, TimeUnit.MILLISECONDS)
        assertTrue(got == null, "heard ${got?.let { it::class.simpleName }}")
    }
}

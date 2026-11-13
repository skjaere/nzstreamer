package io.skjaere.usenet.app.link

import androidx.datastore.core.DataStore
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkServer
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.PairedHello
import io.skjaere.usenet.link.PeerLink
import io.skjaere.usenet.link.Refusal
import io.skjaere.usenet.link.SelfSigned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/** A link key made in software, as a test's phone or TV holds one. */
fun softwareLinkKey(): LinkKey {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return LinkKey(pair.private, SelfSigned.certificate(pair))
}

fun loopback(address: String) = InetAddress.getByName(address) as Inet4Address

/** Waits for [condition], up to [millis]; fails saying [what] (and what [now] says, when given) if it never holds. */
fun eventually(what: String, millis: Long = 5_000, now: () -> Any? = { null }, condition: () -> Boolean) {
    val until = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        assertTrue(System.nanoTime() < until, "$what; now ${now()}")
        Thread.sleep(20)
    }
}

/**
 * Waits until nothing listens on [port] at [address] any more. A listener closed while its thread is blocked in accept()
 * keeps the port until that thread wakes (backlog 301): a LinkServer's close() returns before then, and a bind of the same
 * port at once fails now and then. A test that binds a closed server's port again waits here first.
 */
fun awaitPortFree(address: InetAddress, port: Int, millis: Long = 5_000) {
    val until = System.nanoTime() + millis * 1_000_000
    while (true) {
        try {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(address, port))
            }
            return
        } catch (e: BindException) {
            assertTrue(System.nanoTime() < until, "port $port is still taken after $millis ms")
            Thread.sleep(5)
        }
    }
}

/** A DataStore in memory. */
class MemoryStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock { transform(state.value).also { state.value = it } }
}

/**
 * A TV in the test's process: a real LinkServer on [address] (a loopback one in JVM tests), its own [key], knowing the
 * phones in [paired], saying [ready] to them, speaking [versions]. [port] 0 takes any; [reopen] binds the same one again.
 */
class FakeTv(
    val address: Inet4Address,
    val name: String,
    val key: LinkKey = softwareLinkKey(),
    @Volatile var paired: Set<KeyId> = emptySet(),
    @Volatile var ready: Hello.Ready = Hello.Ready(provider = true, indexer = true, tmdb = true),
    private val versions: LinkVersions = LinkVersions.V1,
    private val allow: (InetAddress) -> Boolean = { it.isLoopbackAddress },
    port: Int = 0,
) : AutoCloseable {
    val received = CopyOnWriteArrayList<LinkMessage>()
    val links = CopyOnWriteArrayList<PeerLink>()
    val closed = CopyOnWriteArrayList<LinkClose>()
    @Volatile private var server: LinkServer? = null
    @Volatile var port: Int = port
        private set

    /** Its links' messages and ends, as the TV's own handler would hear them. */
    private val handler = object : LinkServer.Handler {
        override fun linked(link: PeerLink, hello: Hello) {
            links += link
        }

        override fun received(link: PeerLink, message: LinkMessage) {
            received += message
        }

        override fun closed(link: PeerLink, reason: LinkClose) {
            links -= link
            closed += reason
        }
    }

    init {
        open()
    }

    private fun open() {
        // The same port again (reopen, or another TV on a closed one's): bound once the closed server has let it go.
        if (port != 0) awaitPortFree(address, port)
        val s = LinkServer(
            bind = address, port = port, key = key, name = name, app = "0.1.0", versions = versions,
            paired = { paired }, pairedHello = { PairedHello(ready) }, allowPeer = allow, handler = handler, onEvent = {},
        )
        port = s.start()
        server = s
    }

    /** NZStreamer on it closed: nothing listens on its port. */
    override fun close() {
        server?.close()
        server = null
        // A closed server tells its handler nothing more.
        links.clear()
    }

    /** Opened again, on the same port. */
    fun reopen() {
        close()
        open()
    }

    /** Remove on it, as the TV does it (L4a): it no longer knows [phone], and its links to it are told so and closed now. */
    fun remove(phone: KeyId) {
        paired = paired - phone
        server?.disconnect(phone, Refusal.NotPaired)
    }
}

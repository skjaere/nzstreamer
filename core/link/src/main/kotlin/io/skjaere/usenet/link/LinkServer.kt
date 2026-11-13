package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Hello
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a TV's hello tells a paired phone beyond its name (L25): whether it can play, and the play now. */
class PairedHello(val ready: Hello.Ready, val session: Hello.Session? = null)

/** Why the TV closed a connection before TLS. */
enum class Drop {
    /** The filter refused its address: not a private IPv4 one, in the app. */
    NotAllowed,

    /** Over [LinkLimits.acceptsPerWindow] from an address without a live paired link. */
    TooManyAccepts,

    /** [LinkLimits.perAddressBeforeHello] from its address already wait for their hellos. */
    TooManyFromAddress,

    /** Every slot holds a link, or a connection past its hello. */
    Full,
}

/**
 * What the TV's listener did with connections that never became a link, on its own threads: post it to the app's own
 * and don't block. None holds a frame or a name, so each may be logged as it is. Nothing comes after [LinkServer.close].
 */
sealed interface LinkEvent {
    /** Closed before TLS. */
    data class Dropped(val why: Drop) : LinkEvent

    /** TLS didn't come up. */
    data class TlsFailed(val failure: TlsFailure) : LinkEvent

    /** The TV refused a peer after TLS (L23, L26), and told it why. */
    data class Refused(val reason: Refusal) : LinkEvent

    /** A connection ended after TLS, before its link was up: no hello in time, a bad one, or the phone refused the TV. */
    data class Ended(val why: LinkClose) : LinkEvent
}

/**
 * The TV's end of Play on TV's link (L2, L23, L24): it listens on [bind], the TV's private IPv4, and [port], for paired
 * phones' links. [start] binds it; [close] ends everything at once.
 *
 * Each connection goes like this. Peers [allowPeer] refuses are dropped unanswered, the app's filter taking RFC 1918
 * IPv4 alone. An address without a live paired link has [LinkLimits.acceptsPerWindow] accepts a minute; one address
 * has [LinkLimits.perAddressBeforeHello] connections before their hellos; with every slot taken, a new connection takes
 * the slot of the oldest one still before its hello, and paired links never lose theirs. TLS and both hellos must come
 * within [LinkLimits.helloMillis] of the accept, by a timer. TLS takes any link key, and then the key it met is looked
 * up in [paired]: never anything the peer says. The TV says its hello first, to an unpaired key its name and versions
 * alone, and refuses an unpaired key once the phone has said its hello (`not-paired`), as it refuses versions too far
 * apart. A phone that came to pair goes to pairing while the Pair a phone page takes pairings ([openPairing]), and is
 * told `not-pairing` otherwise. A paired phone's link is then up, for its [handler]; the same phone linking again
 * replaces its older link, and past [LinkLimits.useLinks] a phone is told busy.
 *
 * On a link: frames within their deadline, some frame within [LinkLimits.idleMillis] (the phone pings), at most
 * [LinkLimits.framesPerSecond] (a command past it gets `Rejected{too-fast}`), and only a phone's messages; pings are
 * answered here, and a command of a type this version doesn't know gets `Rejected{unsupported}`. Anything else closes
 * it. [onEvent] hears what became of connections that never became links; it and [handler] hear nothing of a frame's
 * contents, and nothing is logged.
 */
class LinkServer(
    private val bind: Inet4Address,
    private val port: Int = 0,
    private val key: LinkKey,
    /** The TV's name (L28), which every peer that finishes TLS is told. */
    private val name: String,
    /** The app's version, for paired phones alone. */
    private val app: String,
    private val versions: LinkVersions = LinkVersions.V1,
    /** The paired phones' keys, looked up for each connection once TLS has met its key, and again as its link comes up. */
    private val paired: () -> Set<KeyId>,
    /** The rest of the TV's hello to the paired phone with that key; called on the connection's thread. */
    private val pairedHello: (KeyId) -> PairedHello,
    private val allowPeer: (InetAddress) -> Boolean,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = ::monotonicMillis,
    private val limits: LinkLimits = LinkLimits(),
    private val handler: Handler,
    private val onEvent: (LinkEvent) -> Unit,
) : AutoCloseable {
    /** A paired phone's links, as [LinkHandler]'s, and when each comes up. */
    interface Handler : LinkHandler {
        /** A paired phone's link is up: its [hello], its name the phone's own word (L28). */
        fun linked(link: PeerLink, hello: Hello)
    }

    /** A connection the server holds: from its accept until its thread ends. */
    private inner class Conn(val connection: LinkConnection) {
        /** The key TLS met; null before. */
        @Volatile var peer: KeyId? = null

        /** Its link is up. Set under [lock]. */
        @Volatile var linked = false

        /** It came to pair, past its hello: its slot is pairing's (L24). Set under [lock]. */
        @Volatile var pairing = false
        @Volatile var link: PeerLink? = null
        @Volatile var helloTimer: ScheduledFuture<*>? = null

        /** An event already said how it ended. */
        var told = false

        /** Neither a link nor pairing's yet: a newcomer may take its slot. */
        val beforeHello: Boolean get() = !linked && !pairing

        /**
         * A link up and not ending: what holds a use slot and frees its address from the accept limit. One that Remove
         * is ending holds neither while its last word goes out (L30).
         */
        val holdsLink: Boolean get() = linked && !connection.isEnding
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    private val timer = linkTimer("link-timer")
    private val desk = PairingDesk(limits, events = { deliver(it) }, clock = clock)

    /** Guards [conns] and [accepts]. */
    private val lock = Any()
    /** In the order they were accepted. */
    private val conns = mutableListOf<Conn>()
    /** Recent accepts by address, the addresses least lately seen dropped first past [MAX_ADDRESSES]. */
    private val accepts = object : LinkedHashMap<InetAddress, RateWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetAddress, RateWindow>?) = size > MAX_ADDRESSES
    }

    /** Held while an event is delivered, so none is delivered once [close] has returned. Never taken inside [lock]. */
    private val eventLock = Any()
    private var stopped = false

    /** A test's seam: called on the connection's thread as a paired phone's link comes up, just before its writer starts. */
    @Volatile internal var linking: (KeyId) -> Unit = {}

    /** A test's seam: called on a link's thread with each message it read, just before its handler is given it. */
    @Volatile internal var reading: (KeyId, LinkMessage) -> Unit = { _, _ -> }

    /**
     * Binds the server and starts taking connections; returns the port (OS-assigned for port 0). Throws an IOException
     * when it can't bind (the port is taken, say): the app chooses another (L12).
     */
    fun start(): Int {
        check(server == null && !closed) { "a link server starts once" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bind, port), BACKLOG)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw e
        }
        server = socket
        thread(isDaemon = true, name = "link-accept") { accept(socket) }
        return socket.localPort
    }

    /**
     * Takes pairings from now (L22), for the Pair a phone page while it is shown: [host] saves each phone paired and
     * hears how pairing goes. It stops at [closePairing], or by itself [LinkLimits.pairingMillis] after this or after its
     * last ceremony ended ([PairingEvent.Closed]). Opening again starts afresh, the failures counted forgotten.
     */
    fun openPairing(host: PairingHost) = desk.open(host)

    /** Takes no more pairings: a ceremony under way ends, nothing saved, and the page hears nothing more of it. */
    fun closePairing() = desk.close()

    /**
     * Closes [peer]'s links at once (Remove on the TV, or its Unpair, L30); its handler hears [LinkClose.Closed]. With a
     * [refusal] (Remove, L4a), each link that is up is told it first, as its last frame in place of any still waiting, so
     * the phone knows why now and not only on its next try; the link closes once that is written, and within
     * [LinkLimits.frameMillis] in any case. From the moment this returns, nothing that link brings in reaches [handler],
     * not even a message it read just before, and it holds no use slot and frees its address from the accept limit no
     * more; a link coming up meanwhile is never heard of. A connection of [peer] whose link isn't up yet is closed with
     * nothing said: its own thread may be writing. The caller takes [peer] out of [paired] first, so its next try is
     * refused.
     */
    fun disconnect(peer: KeyId, refusal: Refusal? = null) = synchronized(eventLock) {
        // Under the event lock: a delivery under way for that link ends first, and every one after it finds the link
        // ending or closed, and is dropped.
        synchronized(lock) { conns.filter { it.peer == peer }.map { it to it.linked } }.forEach { (conn, linked) ->
            // A link's frames go through its writer alone, from the moment it is up: the refusal joins them.
            if (refusal != null && linked) conn.connection.endWith(LinkMessage.Refused(refusal), LinkClose.Closed) else conn.connection.close(LinkClose.Closed)
        }
    }

    /** Ends everything now: no more connections, the open ones closed, nothing more heard. Returns without waiting. */
    override fun close() {
        synchronized(eventLock) { stopped = true }
        closed = true
        server?.closeQuietly()
        synchronized(lock) { conns.toList() }.forEach { it.connection.close(LinkClose.Closed) }
        timer.shutdownNow()
        desk.shutdown()
    }

    private fun accept(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // Closed by close(); anything else ends it too.
                if (!closed) close()
                return
            }
            try {
                admit(client)
            } catch (_: Throwable) {
                // A thread that couldn't be started, say: this connection goes, the server goes on.
                client.closeQuietly()
            }
        }
    }

    /** Takes [client] into a slot and starts its thread, or closes it before TLS. */
    private fun admit(client: Socket) {
        val address = client.inetAddress
        if (!allowPeer(address)) {
            client.closeQuietly()
            emit(LinkEvent.Dropped(Drop.NotAllowed))
            return
        }
        val conn = Conn(LinkConnection(client, address, timer, limits, clock))
        var evicted: Conn? = null
        val drop = synchronized(lock) {
            when {
                closed -> Drop.Full
                conns.none { it.holdsLink && it.connection.address == address } &&
                    !accepts.getOrPut(address) { RateWindow(limits.acceptsPerWindow, limits.acceptWindowMillis) }.take(clock()) -> Drop.TooManyAccepts
                conns.count { it.beforeHello && it.connection.address == address } >= limits.perAddressBeforeHello -> Drop.TooManyFromAddress
                conns.size >= limits.connections -> {
                    evicted = conns.firstOrNull { it.beforeHello }
                    evicted?.let { conns.remove(it) }
                    if (evicted == null) Drop.Full else null
                }
                else -> null
            }.also { if (it == null) conns += conn }
        }
        if (drop != null) {
            client.closeQuietly()
            if (!closed) emit(LinkEvent.Dropped(drop))
            return
        }
        evicted?.connection?.close(LinkClose.Evicted)
        conn.helloTimer = timer.schedule({ conn.connection.close(LinkClose.TimedOut) }, limits.helloMillis, TimeUnit.MILLISECONDS)
        // close() may have gone over the connections before this one joined them.
        if (closed) conn.connection.close(LinkClose.Closed)
        thread(isDaemon = true, name = "link-connection") { serve(conn) }
    }

    private fun serve(conn: Conn) {
        val connection = conn.connection
        try {
            run(conn)
        } catch (_: Throwable) {
            // The app's callbacks' too: the connection closes and the thread ends (L13). A thread of its own runs no
            // coroutine whose cancellation this could swallow.
            connection.close(LinkClose.Failed)
        } finally {
            conn.helloTimer?.cancel(false)
            synchronized(lock) { conns.remove(conn) }
            connection.close(LinkClose.Closed)
            val why = connection.closeReason ?: LinkClose.Closed
            val link = conn.link
            when {
                link != null -> deliver { handler.closed(link, why) }
                !conn.told -> emit(LinkEvent.Ended(why))
            }
        }
    }

    /** One connection, from TLS to its link's end. */
    private fun run(conn: Conn) {
        val connection = conn.connection
        if (!connection.timeReads(limits.helloMillis)) return
        val tlsStarted = clock()
        val handshake = LinkTls.server(connection.plain, key, PeerPolicy.Pinned(paired()))
        val handshakeMillis = clock() - tlsStarted
        val tls: TlsSession
        val socket = when (handshake) {
            is Handshake.Failed -> {
                // Closed by the timer, an eviction or close(): that is what's told, as the connection ends.
                if (!connection.isClosed) tell(conn, LinkEvent.TlsFailed(handshake.failure))
                return
            }
            is Handshake.Accepted -> {
                tls = handshake.session
                handshake.socket
            }
            is Handshake.Refused -> {
                tls = handshake.session
                handshake.socket
            }
        }
        val peer = tls.peer
        conn.peer = peer
        // Whether the key TLS met is a paired phone's, read again now: a phone removed during its TLS was still in the
        // set TLS took. Its key is set first, so a Remove from here on finds this connection and closes it (L30).
        val known = handshake is Handshake.Accepted && peer in paired()
        if (!connection.secured(socket)) return

        // The TV's hello first: its name and versions to anyone, the rest to a paired phone alone (L23).
        val own = if (known) {
            pairedHello(peer).let { Hello(versions.pv, versions.minPv, Role.Tv, name, app = app, ready = it.ready, session = it.session) }
        } else {
            Hello(versions.pv, versions.minPv, Role.Tv, name)
        }
        if (!connection.writeNow(own)) return connection.close(LinkClose.PeerLeft)
        val hello = when (val first = connection.next()) {
            is LinkConnection.Next.Ended -> return connection.close(first.why)
            is LinkConnection.Next.Got -> when (val message = first.value) {
                is Hello -> message
                is LinkMessage.Refused -> return connection.close(LinkClose.Refused(message.reason))
                else -> return connection.close(LinkClose.Unexpected)
            }
        }
        if (hello.role != Role.Phone || !LinkVersions.wellFormed(hello)) return connection.close(LinkClose.Unexpected)
        versions.refusal(hello, Role.Tv)?.let { return refuse(conn, it) }
        // Paired before or not: pairing again replaces a phone's entry (L22).
        if (hello.purpose == Purpose.Pair) return pair(conn, tls, hello)
        val refusal = when {
            !known -> Refusal.NotPaired
            // A purpose this version doesn't know, from a newer phone.
            hello.purpose != Purpose.Use -> Refusal.UpdateTv
            else -> null
        }
        if (refusal != null) return refuse(conn, refusal)

        // A phone removed since its TLS is refused as any other, its link never up.
        if (peer !in paired()) return refuse(conn, Refusal.NotPaired)
        // A paired phone's link: its slot, the newer link of the same phone winning.
        val replaced = synchronized(lock) {
            if (closed) return
            val older = conns.filter { it !== conn && it.linked && it.peer == peer }
            if (conns.count { it.holdsLink && it !in older } >= limits.useLinks) null else older.also { conn.linked = true }
        } ?: return refuse(conn, Refusal.Busy)
        replaced.forEach { it.connection.close(LinkClose.Replaced) }
        // A hello timer that fired first wins.
        if (conn.helloTimer?.cancel(false) == false) return connection.close(LinkClose.TimedOut)
        if (connection.isClosed) return
        val link = PeerLink(peer, connection.address, tls.protocol, tls.cipherSuite, handshakeMillis, versions.common(hello), connection)
        linking(peer)
        if (!connection.startWriter()) return
        // A Remove that came as the link came up (#291) has its refusal on the way out: the handler never hears of this
        // link, and it reads on, dropping what comes, until its cut-off.
        deliver {
            if (connection.counts) {
                conn.link = link
                handler.linked(link, hello)
            }
        }
        serveLink(connection, link)
    }

    /** A link's messages, until it ends. */
    private fun serveLink(connection: LinkConnection, link: PeerLink) {
        val rate = RateWindow(limits.framesPerSecond, SECOND)
        while (true) {
            val message = when (val next = connection.next()) {
                is LinkConnection.Next.Ended -> return connection.close(next.why)
                is LinkConnection.Next.Got -> next.value
            }
            // Ending (Remove, L30): its last word is on its way, and nothing the phone says counts any more. Read on rather
            // than close, so a ping that crosses that word doesn't cut it off.
            if (connection.isEnding) continue
            if (!rate.take(clock())) {
                // Too fast: a command is told so, anything else let go.
                val seq = (message as? LinkMessage.Command)?.seq ?: (message as? LinkMessage.Unknown)?.seq
                seq?.let { link.send(LinkMessage.Rejected(it, Rejection.TooFast)) }
                continue
            }
            when (message) {
                LinkMessage.Ping -> link.send(LinkMessage.Pong)
                is LinkMessage.Unknown -> message.seq?.let { link.send(LinkMessage.Rejected(it, Rejection.Unsupported)) }
                is LinkMessage.Play, is LinkMessage.Cancel, is LinkMessage.Pause, is LinkMessage.Resume, is LinkMessage.SeekTo,
                is LinkMessage.SeekBy, is LinkMessage.Stop, LinkMessage.Unpair, is LinkMessage.FinalAck,
                -> {
                    reading(link.peer, message)
                    // Asked again as it is delivered: a Remove may have come since it was read.
                    deliver { if (connection.counts) handler.received(link, message) }
                }
                else -> return connection.close(LinkClose.Unexpected)
            }
        }
    }

    /**
     * A phone that came to pair (L21, L22): its ceremony, or its wait for one, on this connection until it ends. The
     * pairing desk steps it; this thread reads, answers pings and hands the rest over.
     */
    private fun pair(conn: Conn, tls: TlsSession, hello: Hello) {
        val connection = conn.connection
        // Past its hello: its slot is pairing's, which no newcomer takes.
        synchronized(lock) {
            if (closed) return
            conn.pairing = true
        }
        // The ceremony's steps have their own deadlines from here.
        if (conn.helloTimer?.cancel(false) == false) return connection.close(LinkClose.TimedOut)
        if (connection.isClosed) return
        val slot = desk.Slot(connection, tls.peer, hello.name, tls.ownKey, tls.peerKey)
        desk.admit(slot)?.let { return refuse(conn, it) }
        // The page hears how it went; no event says it again.
        conn.told = true
        try {
            if (!connection.startWriter()) return
            val rate = RateWindow(limits.framesPerSecond, SECOND)
            while (true) {
                val message = when (val next = connection.next()) {
                    is LinkConnection.Next.Ended -> return connection.close(next.why)
                    is LinkConnection.Next.Got -> next.value
                }
                if (!rate.take(clock())) return connection.close(LinkClose.Unexpected)
                when (message) {
                    LinkMessage.Ping -> connection.send(LinkMessage.Pong)
                    // A newer phone's: let go.
                    is LinkMessage.Unknown -> Unit
                    else -> desk.received(slot, message)
                }
            }
        } finally {
            desk.gone(slot)
        }
    }

    /** Tells the peer why not, then closes: the refusal is the event. */
    private fun refuse(conn: Conn, reason: Refusal) {
        tell(conn, LinkEvent.Refused(reason))
        conn.connection.writeNow(LinkMessage.Refused(reason))
        conn.connection.close(LinkClose.Closed)
    }

    private fun tell(conn: Conn, event: LinkEvent) {
        conn.told = true
        emit(event)
    }

    private fun emit(event: LinkEvent) = deliver { onEvent(event) }

    private inline fun deliver(block: () -> Unit) = synchronized(eventLock) { if (!stopped) block() }

    private companion object {
        /** Connections the OS holds for the accept thread, which takes or drops each at once. */
        const val BACKLOG = 16

        /** Addresses whose recent accepts are kept. */
        const val MAX_ADDRESSES = 256

        const val SECOND = 1_000L
    }
}

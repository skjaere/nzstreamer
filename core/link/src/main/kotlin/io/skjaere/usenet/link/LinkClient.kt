package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Hello
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What came of [LinkClient.connect]. */
sealed interface Connect {
    /** The link is up: the pinned TV's key, and its [hello] (its readiness and session, L25). */
    class Linked(val link: PeerLink, val hello: Hello) : Connect {
        override fun toString() = "Linked"
    }

    /**
     * Another key where the pinned one should be, [presented]: another TV took the address, or this one was set up
     * again (L12, L30). [name] is what it calls itself, untrusted (L28), null when it said nothing. Nothing was sent.
     */
    class KeyChanged(val presented: KeyId, val name: String?) : Connect {
        override fun toString() = "KeyChanged(${presented.short})"
    }

    /** The TV refused this phone, or this phone the TV, for [reason]. */
    data class Refused(val reason: Refusal) : Connect

    data class Failed(val why: ConnectFailure) : Connect
}

/** Why a connect came to nothing. */
enum class ConnectFailure {
    /** The filter refused the address: not a private IPv4 one, in the app (L12). Nothing was tried. */
    NotAllowed,

    /** No connection within [LinkLimits.connectMillis]: nothing listening, or no way there. */
    Unreachable,

    /** TLS and the hellos took longer than [LinkLimits.helloMillis]. */
    TimedOut,

    /** TLS didn't come up. */
    Tls,

    /** The TV's certificate isn't a link key. */
    PeerKey,

    /** The TV closed the connection before its hello. */
    Left,

    /** The TV broke the protocol: a frame that isn't a message, or anything but a hello first. */
    Broken,

    /** This client is closed. */
    Closed,
}

/**
 * The phone's end of Play on TV's link (L2, L12, L24): [connect] makes one link to a paired TV, with this phone's [key].
 * [close] ends them all at once.
 *
 * Only addresses [allowPeer] allows are tried: the app's filter takes RFC 1918 IPv4 alone. The connect has
 * [LinkLimits.connectMillis], then TLS and the hellos [LinkLimits.helloMillis] by a timer. TLS must meet the pinned key,
 * by its session, never by what the TV says; another key is [Connect.KeyChanged], told nothing. A TV whose hello has
 * no readiness doesn't know this phone, and says why next. On a link the phone pings every [LinkLimits.pingMillis], and
 * holds the TV to the server's limits: frames within their deadline, some frame within [LinkLimits.idleMillis] (the TV
 * answers each ping), at most [LinkLimits.framesPerSecond] frames and [LinkLimits.statusPerSecond] statuses a second
 * (more are dropped), and only a TV's messages; anything else closes it. Nothing is logged.
 */
class LinkClient(
    internal val key: LinkKey,
    /** The phone's name (L28). */
    internal val name: String,
    /** The app's version. */
    internal val app: String,
    internal val versions: LinkVersions = LinkVersions.V1,
    internal val allowPeer: (InetAddress) -> Boolean,
    /** Monotonic milliseconds. */
    internal val clock: () -> Long = ::monotonicMillis,
    internal val limits: LinkLimits = LinkLimits(),
) : AutoCloseable {
    internal val timer = linkTimer("link-client-timer")
    /** Connections under way and links up (pairings' among them), for [close]. */
    internal val live = ConcurrentHashMap.newKeySet<LinkConnection>()
    @Volatile internal var closed = false
        private set

    /** Held while [LinkHandler] hears anything, so it hears nothing once [close] has returned. */
    private val eventLock = Any()
    private var stopped = false

    /**
     * A link to the TV at [address] whose key is [pin], its messages and its end for [handler] (which may hear of the
     * link before this returns). Blocks for the connect, TLS and the hellos: call it off the main thread.
     */
    fun connect(address: InetSocketAddress, pin: KeyId, handler: LinkHandler): Connect {
        val ip = address.address
        if (ip == null || !allowPeer(ip)) return Connect.Failed(ConnectFailure.NotAllowed)
        if (closed) return Connect.Failed(ConnectFailure.Closed)
        val connection = LinkConnection(Socket(), ip, timer, limits, clock)
        live += connection
        if (closed) connection.close(LinkClose.Closed)
        var linked = false
        try {
            try {
                connection.plain.connect(address, limits.connectMillis)
            } catch (_: IOException) {
                return failed(connection, ConnectFailure.Unreachable)
            }
            val timeout = try {
                timer.schedule({ connection.close(LinkClose.TimedOut) }, limits.helloMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                return Connect.Failed(ConnectFailure.Closed)
            }
            try {
                val outcome = when (val step = handshake(connection, pin)) {
                    is Step.Done -> return step.connect
                    is Step.Linking -> step
                }
                // A hello timer that fired first wins.
                if (!timeout.cancel(false)) return Connect.Failed(ConnectFailure.TimedOut)
                if (connection.isClosed) return failed(connection, ConnectFailure.Left)
                val link = PeerLink(pin, ip, outcome.protocol, outcome.cipherSuite, outcome.handshakeMillis, versions.common(outcome.hello), connection)
                if (!connection.startWriter()) return failed(connection, ConnectFailure.Left)
                val pings = try {
                    timer.scheduleWithFixedDelay({ link.send(LinkMessage.Ping) }, limits.pingMillis, limits.pingMillis, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    return Connect.Failed(ConnectFailure.Closed)
                }
                linked = true
                thread(isDaemon = true, name = "link-reader") { read(connection, link, handler, pings) }
                return Connect.Linked(link, outcome.hello)
            } finally {
                timeout.cancel(false)
            }
        } finally {
            if (!linked) {
                live -= connection
                connection.close(LinkClose.Closed)
            }
        }
    }

    /**
     * A pairing with the TV at [address] (L21, L22), not started: [PairAttempt.run] runs it on the caller's thread, and
     * [PairAttempt.cancel] stops it from any. [close] ends it too.
     */
    fun pairing(address: InetSocketAddress): PairAttempt = PairAttempt(this, address)

    /** Ends every link and connect now; nothing more is heard. Returns without waiting. */
    override fun close() {
        synchronized(eventLock) { stopped = true }
        closed = true
        live.forEach { it.close(LinkClose.Closed) }
        timer.shutdownNow()
    }

    /** How far a connect's handshake got: done, or linking with what the link is made of. */
    private sealed interface Step {
        class Done(val connect: Connect) : Step

        class Linking(val hello: Hello, val protocol: String, val cipherSuite: String, val handshakeMillis: Long) : Step
    }

    /** TLS with [pin], then the hellos: the TV's, checked, and this phone's. */
    private fun handshake(connection: LinkConnection, pin: KeyId): Step {
        if (!connection.timeReads(limits.helloMillis)) return Step.Done(failed(connection, ConnectFailure.Left))
        val tlsStarted = clock()
        val session = when (val handshake = LinkTls.client(connection.plain, key, PeerPolicy.Pinned(pin))) {
            is Handshake.Failed -> return Step.Done(Connect.Failed(tlsFailure(connection, handshake.failure)))
            is Handshake.Refused -> {
                // Another key where the pinned one should be: what the TV calls itself, then nothing more (L12).
                if (!connection.secured(handshake.socket)) return Step.Done(failed(connection, ConnectFailure.Left))
                val said = (connection.next() as? LinkConnection.Next.Got)?.value as? Hello
                return Step.Done(Connect.KeyChanged(handshake.presented, said?.takeIf { it.role == Role.Tv }?.name))
            }
            is Handshake.Accepted -> {
                if (!connection.secured(handshake.socket)) return Step.Done(failed(connection, ConnectFailure.Left))
                handshake.session
            }
        }
        val handshakeMillis = clock() - tlsStarted
        val hello = when (val first = connection.next()) {
            is LinkConnection.Next.Ended -> return Step.Done(ended(connection, first.why))
            is LinkConnection.Next.Got -> when (val message = first.value) {
                is Hello -> message
                is LinkMessage.Refused -> return Step.Done(Connect.Refused(message.reason))
                else -> return Step.Done(Connect.Failed(ConnectFailure.Broken))
            }
        }
        if (hello.role != Role.Tv || !LinkVersions.wellFormed(hello)) return Step.Done(Connect.Failed(ConnectFailure.Broken))
        versions.refusal(hello, Role.Phone)?.let { refusal ->
            // Too far apart: the TV is told so instead of a hello.
            connection.writeNow(LinkMessage.Refused(refusal))
            return Step.Done(Connect.Refused(refusal))
        }
        if (!connection.writeNow(Hello(versions.pv, versions.minPv, Role.Phone, name, app = app, purpose = Purpose.Use))) {
            return Step.Done(failed(connection, ConnectFailure.Left))
        }
        if (hello.ready == null) {
            // A TV that doesn't know this phone's key says only its name and versions, and then why not.
            return Step.Done(
                when (val next = connection.next()) {
                    is LinkConnection.Next.Ended -> ended(connection, next.why)
                    is LinkConnection.Next.Got -> (next.value as? LinkMessage.Refused)?.let { Connect.Refused(it.reason) } ?: Connect.Failed(ConnectFailure.Broken)
                },
            )
        }
        return Step.Linking(hello, session.protocol, session.cipherSuite, handshakeMillis)
    }

    /** A link's messages from the TV, until it ends. */
    private fun read(connection: LinkConnection, link: PeerLink, handler: LinkHandler, pings: ScheduledFuture<*>) {
        try {
            val frames = RateWindow(limits.framesPerSecond, SECOND)
            val statuses = RateWindow(limits.statusPerSecond, SECOND)
            while (true) {
                val message = when (val next = connection.next()) {
                    is LinkConnection.Next.Ended -> return connection.close(next.why)
                    is LinkConnection.Next.Got -> next.value
                }
                if (message is LinkMessage.Refused) return connection.close(LinkClose.Refused(message.reason))
                val now = clock()
                // Too fast: dropped.
                if (!frames.take(now)) continue
                when (message) {
                    LinkMessage.Pong, is LinkMessage.Unknown -> Unit
                    is LinkMessage.Status -> if (statuses.take(now)) deliver { handler.received(link, message) }
                    is LinkMessage.Ack, is LinkMessage.Rejected, is LinkMessage.Final -> deliver { handler.received(link, message) }
                    else -> return connection.close(LinkClose.Unexpected)
                }
            }
        } catch (_: Throwable) {
            // The app's handler's too: the link closes and the thread ends (L13). A thread of its own runs no coroutine
            // whose cancellation this could swallow.
            connection.close(LinkClose.Failed)
        } finally {
            pings.cancel(false)
            live -= connection
            connection.close(LinkClose.Closed)
            val why = connection.closeReason ?: LinkClose.Closed
            deliver { handler.closed(link, why) }
        }
    }

    /** A connect that ended as [connection] closed: why it closed, when this end closed it, else [otherwise]. */
    private fun failed(connection: LinkConnection, otherwise: ConnectFailure): Connect = Connect.Failed(failure(connection, otherwise))

    /** A connect whose read ended for [why] before the link was up. */
    private fun ended(connection: LinkConnection, why: LinkClose): Connect = Connect.Failed(endedFor(connection, why))

    /** What [failed] says. */
    internal fun failure(connection: LinkConnection, otherwise: ConnectFailure): ConnectFailure = when (connection.closeReason) {
        LinkClose.TimedOut -> ConnectFailure.TimedOut
        LinkClose.Closed -> ConnectFailure.Closed
        else -> otherwise
    }

    /** What [ended] says. */
    internal fun endedFor(connection: LinkConnection, why: LinkClose): ConnectFailure = when (why) {
        LinkClose.PeerLeft -> failure(connection, ConnectFailure.Left)
        LinkClose.Idle, LinkClose.Frame(FrameError.TimedOut) -> failure(connection, ConnectFailure.TimedOut)
        else -> failure(connection, ConnectFailure.Broken)
    }

    /** What [TlsFailure] a handshake failed with comes to, as the connection closed. */
    internal fun tlsFailure(connection: LinkConnection, failure: TlsFailure): ConnectFailure = failure(
        connection,
        when (failure) {
            TlsFailure.TimedOut -> ConnectFailure.TimedOut
            TlsFailure.PeerKey -> ConnectFailure.PeerKey
            TlsFailure.Other -> ConnectFailure.Tls
        },
    )

    internal inline fun deliver(block: () -> Unit) = synchronized(eventLock) { if (!stopped) block() }

    private companion object {
        const val SECOND = 1_000L
    }
}

package io.skjaere.usenet.link

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a phone's pairing shows its user (W3), on the attempt's thread: post it to the app's own and don't block. */
interface PairHandler {
    /** The TV is pairing with another phone: this one waits its turn (L22). */
    fun waiting()

    /** Both screens show [check]'s number now: its [PairCheck.yes] or [PairCheck.no] answers. */
    fun compare(check: PairCheck)
}

/**
 * The phone's question (W3): does [tvName] show [number]? [yes] or [no] answers it, once; both are main-safe. [tvName]
 * is the TV's own word, untrusted (L28). Prints as its type alone, never the number.
 */
class PairCheck internal constructor(val number: PairNumber, val tvName: String, private val answer: (Boolean) -> Unit) {
    fun yes() = answer(true)

    fun no() = answer(false)

    override fun toString() = "PairCheck"
}

/**
 * A phone's pairing with a TV (L21, L22), from [LinkClient.pairing]. [run] connects to the TV's address, which the
 * client's filter must allow, within [LinkLimits.connectMillis]; then TLS takes any link key, the TV's from the session,
 * never from what it says. TLS, both hellos and the TV's first word (its commit, or that this phone waits its turn) come
 * within [LinkLimits.helloMillis]. The phone sends its nonce only for the TV's commit, checks the reveal against it, and
 * shows the number; after its yes it waits for the TV's word no longer than the TV's question lasts, and counts itself
 * paired only on it. It pings while it waits, and holds the TV to the link's frame and rate limits. Nothing is logged.
 */
class PairAttempt internal constructor(private val client: LinkClient, private val address: InetSocketAddress) {
    /** What came of it. */
    sealed interface Result {
        /** The TV saved this phone and said so: save [tv], its key, pinned from now, and its [name] (untrusted, L28). */
        class Paired(val tv: KeyId, val name: String) : Result {
            override fun toString() = "Paired(${tv.short})"
        }

        /** The TV refused before any ceremony: not pairing, busy, too many tries, or an update (L22, L26). */
        data class Refused(val reason: Refusal) : Result

        /** The ceremony, or the wait for it, ended without a pairing, for [outcome]. Nothing was saved. */
        data class Stopped(val outcome: PairOutcome) : Result

        /** No ceremony: the connection, TLS or the hellos failed as a connect would, or it was cancelled ([ConnectFailure.Closed]). */
        data class Failed(val why: ConnectFailure) : Result
    }

    private val started = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile private var connection: LinkConnection? = null

    /** Runs the pairing on this thread until it ends: call it once, off the main thread. [handler] hears the wait and the number. */
    fun run(handler: PairHandler): Result {
        check(started.compareAndSet(false, true)) { "a pairing runs once" }
        val ip = address.address
        if (ip == null || !client.allowPeer(ip)) return Result.Failed(ConnectFailure.NotAllowed)
        if (client.closed || cancelled) return Result.Failed(ConnectFailure.Closed)
        val limits = client.limits
        val connection = LinkConnection(Socket(), ip, client.timer, limits, client.clock)
        this.connection = connection
        client.live += connection
        // cancel() or the client's close() may have gone by before this one was there.
        if (client.closed || cancelled) connection.close(LinkClose.Closed)
        val timers = mutableListOf<ScheduledFuture<*>>()
        try {
            try {
                connection.plain.connect(address, limits.connectMillis)
            } catch (_: IOException) {
                return Result.Failed(client.failure(connection, ConnectFailure.Unreachable))
            }
            val hello = schedule(timers, limits.helloMillis) { connection.close(LinkClose.TimedOut) } ?: return Result.Failed(ConnectFailure.Closed)
            if (!connection.timeReads(limits.helloMillis)) return Result.Failed(client.failure(connection, ConnectFailure.Left))
            val session = when (val handshake = LinkTls.client(connection.plain, client.key, PeerPolicy.Pairing)) {
                is Handshake.Failed -> return Result.Failed(client.tlsFailure(connection, handshake.failure))
                // Pairing takes any link key, so TLS never refuses one; were it to, nothing is said.
                is Handshake.Refused -> {
                    connection.secured(handshake.socket)
                    return Result.Failed(ConnectFailure.Tls)
                }
                is Handshake.Accepted -> {
                    if (!connection.secured(handshake.socket)) return Result.Failed(client.failure(connection, ConnectFailure.Left))
                    handshake.session
                }
            }
            val tvHello = when (val first = connection.next()) {
                is LinkConnection.Next.Ended -> return Result.Failed(client.endedFor(connection, first.why))
                is LinkConnection.Next.Got -> when (val said = first.value) {
                    is LinkMessage.Hello -> said
                    is LinkMessage.Refused -> return Result.Refused(said.reason)
                    else -> return Result.Failed(ConnectFailure.Broken)
                }
            }
            if (tvHello.role != Role.Tv || !LinkVersions.wellFormed(tvHello)) return Result.Failed(ConnectFailure.Broken)
            client.versions.refusal(tvHello, Role.Phone)?.let { refusal ->
                // Too far apart: the TV is told so instead of a hello.
                connection.writeNow(LinkMessage.Refused(refusal))
                return Result.Refused(refusal)
            }
            val own = LinkMessage.Hello(client.versions.pv, client.versions.minPv, Role.Phone, client.name, app = client.app, purpose = Purpose.Pair)
            if (!connection.writeNow(own) || !connection.startWriter()) return Result.Failed(client.failure(connection, ConnectFailure.Left))
            timers += try {
                // A phone waiting its turn may wait past the idle limit: its pings keep the connection up.
                client.timer.scheduleWithFixedDelay({ connection.send(LinkMessage.Ping) }, limits.pingMillis, limits.pingMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                return Result.Failed(ConnectFailure.Closed)
            }
            val pairing = Pairing.Phone(session.ownKey, session.peerKey)
            return ceremony(connection, pairing, session.peer, tvHello.name, hello, timers, handler)
        } finally {
            timers.forEach { it.cancel(false) }
            client.live -= connection
            connection.close(LinkClose.Closed)
        }
    }

    /** Stops it, from any thread: [run] returns [Result.Failed] with [ConnectFailure.Closed], and the TV sees the phone leave. */
    fun cancel() {
        cancelled = true
        connection?.close(LinkClose.Closed)
    }

    /** The TV's words after the hellos, until the ceremony ends. */
    private fun ceremony(
        connection: LinkConnection,
        pairing: Pairing.Phone,
        tv: KeyId,
        tvName: String,
        hello: ScheduledFuture<*>,
        timers: MutableList<ScheduledFuture<*>>,
        handler: PairHandler,
    ): Result {
        val frames = RateWindow(client.limits.framesPerSecond, SECOND)
        // The TV took this phone for pairing: its commit came, or its word to wait.
        var taken = false
        while (true) {
            val message = when (val next = connection.next()) {
                is LinkConnection.Next.Ended -> return ended(connection, pairing, taken, next.why)
                is LinkConnection.Next.Got -> next.value
            }
            if (!frames.take(client.clock())) {
                connection.close(LinkClose.Unexpected)
                return Result.Stopped(PairOutcome.Broken)
            }
            when (message) {
                LinkMessage.Pong, is LinkMessage.Unknown -> continue
                // Instead of its first word: not pairing, busy or too many tries. Later, out of place.
                is LinkMessage.Refused -> if (!taken) return Result.Refused(message.reason)
                else -> Unit
            }
            taken = true
            when (val step = pairing.received(message)) {
                Pairing.Step.Queued -> {
                    hello.cancel(false)
                    client.deliver { handler.waiting() }
                }
                is Pairing.Step.Send -> {
                    hello.cancel(false)
                    connection.send(step.said)
                }
                is Pairing.Step.Show -> {
                    // The TV's question closes after its time, and says so; this phone waits no longer than that.
                    schedule(timers, client.limits.questionMillis + client.limits.helloMillis) { connection.close(LinkClose.TimedOut) }
                    val check = PairCheck(step.number, tvName) { yes -> answer(connection, pairing, yes) }
                    client.deliver { handler.compare(check) }
                }
                Pairing.Step.Confirmed -> return Result.Paired(tv, tvName)
                is Pairing.Step.Ended -> {
                    connection.close(LinkClose.Closed)
                    return Result.Stopped(step.outcome)
                }
                Pairing.Step.Wait -> Unit
            }
        }
    }

    /** The user's yes or no on the number. */
    private fun answer(connection: LinkConnection, pairing: Pairing.Phone, yes: Boolean) {
        when (val step = if (yes) pairing.yes() else pairing.no()) {
            is Pairing.Step.Send -> connection.send(step.said)
            // The TV hears the no, then the connection closes.
            is Pairing.Step.Ended -> step.send?.let { connection.finish(it, LinkClose.Closed) } ?: connection.close(LinkClose.Closed)
            else -> Unit
        }
    }

    /** The connection ended for [why]: how the ceremony ended, if it did, else what ending it says. */
    private fun ended(connection: LinkConnection, pairing: Pairing.Phone, taken: Boolean, why: LinkClose): Result {
        pairing.outcome?.let { return Result.Stopped(it) }
        val failure = client.endedFor(connection, why)
        return when {
            // Cancelled, or the client closed.
            failure == ConnectFailure.Closed || !taken -> Result.Failed(failure)
            failure == ConnectFailure.TimedOut -> Result.Stopped(PairOutcome.TimedOut)
            else -> Result.Stopped(PairOutcome.Left)
        }
    }

    private fun schedule(timers: MutableList<ScheduledFuture<*>>, millis: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        client.timer.schedule(task, millis, TimeUnit.MILLISECONDS).also { timers += it }
    } catch (_: RejectedExecutionException) {
        null
    }

    private companion object {
        const val SECOND = 1_000L
    }
}

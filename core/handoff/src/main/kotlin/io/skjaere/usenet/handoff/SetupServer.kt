package io.skjaere.usenet.handoff

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** How big a request may be, how long it may take, how many at once, and how long a code lasts. Tests shrink them; the defaults are the design's. */
data class SetupLimits(
    val requestLine: Int = 2048,
    /** The header lines together, their line ends and the empty line included. */
    val headerBytes: Int = 8192,
    val headers: Int = 64,
    val body: Int = 32 * 1024,
    /** For the whole request from the connection's accept, so a slow sender can't hold a slot. */
    val deadlineMillis: Long = 10_000,
    val connections: Int = 4,
    val wrongTries: Int = 5,
    /** After an answer, how long what's left of the request is read and dropped, so the close doesn't reset the answer away. */
    val drainMillis: Long = 250,
    /**
     * Tests from the page a code allows, one a card tested, counted as they start: one a newer test replaced, or Next or
     * Back stopped, still counts, as the tester's work on it had begun.
     */
    val tests: Int = 30,
    /** How long the page waits on a test, from when it starts, before it is answered without it. */
    val testMillis: Long = 30_000,
    /** Providers a session's page holds, saved ones included: with more saved, all show and none can be added. */
    val providers: Int = 8,
    /** The same for indexers. */
    val indexers: Int = 10,
    /** The same for torrent indexers. Debrid service cards are one for each service the page offers. */
    val torrentIndexers: Int = 10,
    /** How long a code waits to be opened. */
    val unopenedMillis: Long = 10 * MINUTE,
    /** Once opened, how long it lasts after its device's last request or the end of its last test. */
    val idleMillis: Long = 10 * MINUTE,
    /** Once opened, the most it lasts, however much it is used. */
    val capMillis: Long = 45 * MINUTE,
    /** No test starts with less than this left before the cap: it could run into it. */
    val testRoomMillis: Long = 35_000,
) {
    /** What the request's reader holds the head to. */
    val head: HeadLimits get() = HeadLimits(requestLine, headerBytes, headers)
}

private const val MINUTE = 60_000L

/**
 * What a session tells the app, on the server's own threads: post it to the app's own before doing
 * anything with it, and don't block. They come in the order the session changed, and nothing comes
 * after the app's [SetupServer.close].
 */
sealed interface SetupEvent {
    /** The page was opened with the right code, from [peer], which now owns the session. */
    data class Opened(val peer: InetAddress) : SetupEvent

    /** The [count]th wrong code; the last one allowed closes the session instead ([Locked]). */
    data class WrongTry(val count: Int) : SetupEvent

    /** Too many wrong codes: the server has closed. */
    data object Locked : SetupEvent

    /** [peer] sent what its page holds ([changes]): the server has closed. */
    data class Received(val changes: SetupChanges, val peer: InetAddress) : SetupEvent

    /** [peer], not the device that opened the page, tried the right code; it was refused. */
    data class PeerRefused(val peer: InetAddress) : SetupEvent

    /** The phone sent a form that couldn't be read; the session goes on, and it can send again. */
    data class Rejected(val rejection: SetupRejection) : SetupEvent

    /** A form from the phone was read (an older page's too), so one refused before it is out of date. */
    data object FormRead : SetupEvent

    /** The phone's page went to another of the wizard's steps. */
    data class StepChanged(val step: SetupStep) : SetupEvent

    /**
     * The phone asked the TV to test the card at [slot]; the session goes on. The test ends in [TestFinished], or in
     * [TestStopped] when Next or Back stops it; a newer test's start replaces it.
     */
    data class TestStarted(val slot: SetupSlot) : SetupEvent

    /** That test ended: how it went, and no words, so no host or message. [slot] is where the card was when it started. */
    data class TestFinished(val slot: SetupSlot, val outcome: SetupOutcome) : SetupEvent

    /** Next or Back stopped that test before it ended: no outcome, and its card keeps what it had. [slot] as for [TestFinished]. */
    data class TestStopped(val slot: SetupSlot) : SetupEvent

    /** The code now lasts until [at], on the server's clock: its device used it. */
    data class DeadlineMoved(val at: Long) : SetupEvent

    /** A request came after the code's time ran out: the server has closed. */
    data object Expired : SetupEvent
}

/**
 * Where a session's page is: [url] for the QR code, [display] (no scheme, the code in fours) to read
 * off the TV and type. Both hold the code: show them, never log them or put them in a route or saved
 * state ([toString] has only the [authority]).
 */
class SetupAddress(val ip: Inet4Address, val port: Int, private val token: SetupToken) {
    /** What a browser sends as the Host, and with the scheme as the Origin. */
    val authority: String = "${ip.hostAddress}:$port"
    val url: String get() = "http://$authority/${token.value}"
    val display: String get() = "$authority/${token.grouped}"

    override fun toString() = "SetupAddress($authority)"
}

/**
 * The page a TV serves on the home network for "Set up with your phone", at `http://<bind>:<port>/<token>`, for one
 * session. [start] binds it (to [bind] alone, the TV's private IPv4) and returns the port; [close] ends it at once.
 *
 * The rules: GET and POST only; the Host must be the address and an Origin, when sent, its own; the first peer to use
 * the right code owns the session; [SetupLimits.wrongTries] wrong codes close it, where only a path shaped like a code
 * counts (browsers fetch icons on their own), and once the page is open only its owner's; each connection gets one
 * answer and is closed, within one deadline for the whole request. Peers [allowPeer] refuses (by default anything but
 * RFC 1918 IPv4) are dropped unanswered. Nothing is logged: [onEvent] is told what happened, in words with no request
 * data in them.
 *
 * The page is the [mode]'s (the wizard or the edit page), from [seed], what is saved, and [from], what an earlier
 * session sent, if this one is to start from it; a [SetupFlow] holds its cards on the TV. A GET shows it as it is. A form that is read is applied and answered with a 303 back to the page, at the card
 * it was about, so a reload or Back never sends it again; Send is answered directly, hands over the changes and ends
 * the session. Nothing a request carries comes back in an answer but what the owner typed, in its own page, never a
 * password or key. A card's test calls [tester], one at a time (a newer one replaces the running one, and Next or Back
 * stops it), at most [SetupLimits.tests] a code, each waited on for at most [SetupLimits.testMillis].
 *
 * A code lasts [SetupLimits.unopenedMillis] unopened; once opened, [SetupLimits.idleMillis] after its owner's last
 * request or test, and at most [SetupLimits.capMillis] from when it opened ([deadline], told as it moves). A request
 * after that is told the code closed, and the session ends. No test starts that could run into the cap.
 */
class SetupServer(
    private val bind: Inet4Address,
    private val port: Int = 0,
    private val token: SetupToken,
    mode: SetupMode,
    seed: SetupSeed = SetupSeed(),
    /**
     * What an earlier session of this page sent, for its page to start from (Send again); typed secrets in it stay on the
     * TV, each only for the server it was sent for.
     */
    from: SetupChanges? = null,
    /** Monotonic milliseconds, for the requests' deadlines and the code's lifetime: the app passes the clock it counts down by. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val limits: SetupLimits = SetupLimits(),
    private val allowPeer: (InetAddress) -> Boolean = ::isPrivateIpv4,
    private val tester: SetupTester,
    private val onEvent: (SetupEvent) -> Unit,
) : AutoCloseable {
    private enum class Phase { Open, Used, Locked, Expired, Closed }

    /**
     * The answer to one request: a page, or a 303 to [location]; and when it ends the session, [ending], the event that
     * says so, told once the answer is out. What else it changed was told as it changed.
     */
    private class Outcome(val status: Int, val page: String?, val ending: SetupEvent? = null, val location: String? = null)

    /**
     * A test from the page under way, of [ticket]: the [future] once the tester has given one; [replaced] once a newer
     * test took its place, or Next or Back stopped it.
     */
    private class Run(val ticket: SetupTicket) {
        var future: Future<SetupResult>? = null
        var replaced = false
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var ended = false
    private lateinit var listening: SetupAddress
    private val live = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(limits.connections)

    /** Guards the session: [phase], [owner], [wrongTries], [running], [flow] and the code's times. */
    private val lock = Any()
    private var phase = Phase.Open
    private var owner: InetAddress? = null
    private var wrongTries = 0
    private var running: Run? = null
    /** The page's cards and what was typed into them; let go when the session ends. */
    private var flow: SetupFlow? = SetupFlow(mode, seed, limits, from)
    private var openedAt: Long? = null
    private var lastUse = Long.MIN_VALUE
    /** What a request after the code's time is told: why it closed. */
    private var expiredPage = SetupPage.expired

    /** When the code stops working, on [clock]: it moves with its owner's requests and tests, as [SetupEvent.DeadlineMoved] tells. */
    @Volatile var deadline: Long = 0
        private set

    /** Held while events are told and delivered, so none is delivered once [close] has returned. Taken inside [lock], never around it. */
    private val eventLock = Any()
    private var stopped = false
    /**
     * What the session told and the app hasn't been given yet, in the order it was told under [lock]: a connection
     * delivers it all once its answer is out, so one whose thread is held after its answer can't have its events come
     * after a later request's; the next to deliver takes them along. Under [eventLock].
     */
    private val pending = ArrayDeque<SetupEvent>()

    /**
     * For tests: called on a connection's thread with its peer's address once its answer is written, before it delivers
     * what was told. A test holds a connection here, as a busy device can hold its thread, while the phone goes on.
     */
    @Volatile internal var afterAnswer: (InetSocketAddress) -> Unit = {}

    /** Where the page is, once [start] has bound it. */
    val address: SetupAddress get() = listening

    /**
     * Binds the server and starts taking connections; returns the port (OS-assigned for port 0).
     * Throws an IOException when it can't bind (the address has gone, say), with no code in its message.
     */
    fun start(): Int {
        check(server == null && !closed) { "a setup server starts once" }
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(bind, port), BACKLOG)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw e
        }
        listening = SetupAddress(bind, socket.localPort, token)
        synchronized(lock) { deadline = clock() + limits.unopenedMillis }
        server = socket
        thread(isDaemon = true, name = "phone-setup-accept") { accept(socket) }
        return socket.localPort
    }

    /** Ends the session now: no more connections, the open ones closed, no more events. Returns without waiting for anything. */
    override fun close() {
        synchronized(eventLock) {
            stopped = true
            pending.clear()
        }
        synchronized(lock) { end(Phase.Closed) }
        closed = true
        server?.closeQuietly()
        live.forEach { it.closeQuietly() }
    }

    private fun accept(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // Closed by close() or at the session's end; anything else ends it too.
                if (!closed && !ended) close()
                return
            }
            val acceptedAt = clock()
            when {
                !allowPeer(client.inetAddress) -> client.closeQuietly()
                !slots.tryAcquire() -> busy(client)
                else -> {
                    live += client
                    // close() may have gone over the open connections before this one joined them.
                    if (closed) client.closeQuietly()
                    thread(isDaemon = true, name = "phone-setup-connection") {
                        try {
                            serve(client, acceptedAt)
                        } finally {
                            live -= client
                            client.closeQuietly()
                            slots.release()
                        }
                    }
                }
            }
        }
    }

    /** All slots taken: a bare 503, not read, so the accept thread never waits on it. */
    private fun busy(client: Socket) {
        try {
            client.getOutputStream().write(BUSY)
            client.shutdownOutput()
        } catch (_: IOException) {
            // Gone already.
        } finally {
            client.closeQuietly()
        }
    }

    private fun serve(client: Socket, acceptedAt: Long) {
        val outcome = try {
            client.tcpNoDelay = true
            handle(client, RequestReader(client, acceptedAt + limits.deadlineMillis, clock))
        } catch (_: IOException) {
            // Out of time, cut off or closed: no answer.
            null
        }
        if (outcome == null) {
            // Unanswered, but what it changed before that is still told.
            deliver()
            return
        }
        try {
            write(client, outcome.status, outcome.page, outcome.location)
            client.shutdownOutput()
        } catch (_: IOException) {
            // The peer went; what it sent still counts.
        }
        if (outcome.ending != null) {
            ended = true
            server?.closeQuietly()
        }
        afterAnswer(client.remoteSocketAddress as InetSocketAddress)
        // Before the drain: the TV may stop the session within it, and what was sent must have reached it by then.
        deliver(outcome.ending)
        try {
            drain(client)
        } catch (_: IOException) {
            // Closed meanwhile: nothing left to read.
        }
        client.closeQuietly()
    }

    /** Queues [event] for the app, after what the session told before it. Under [lock], so the order is the session's. */
    private fun tell(event: SetupEvent) = synchronized(eventLock) { if (!stopped) pending += event }

    /**
     * Gives the app what was told, in order, then [ending], the event that ended the session, its answer out: nothing is
     * told after it. Outside [lock].
     */
    private fun deliver(ending: SetupEvent? = null) = synchronized(eventLock) {
        while (!stopped) onEvent(pending.removeFirstOrNull() ?: break)
        if (!stopped) ending?.let(onEvent)
    }

    /** The answer to the request on [client], or null to close it unanswered. */
    private fun handle(client: Socket, reader: RequestReader): Outcome? {
        // A TLS ClientHello: a browser trying https. Nothing to say to it in plain HTTP.
        if (reader.peek() == TLS_HANDSHAKE) return null
        val requestLine = when (val line = reader.line(limits.requestLine)) {
            null -> return null
            RequestReader.Line.TooLong -> return refuse(414)
            RequestReader.Line.Bad -> return refuse(400)
            is RequestReader.Line.Text -> String(line.bytes, Charsets.ISO_8859_1)
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3) return refuse(400)
        val (method, target, version) = parts
        if (version != "HTTP/1.1" && version != "HTTP/1.0" || method.isEmpty() || !method.all(RequestReader::isTokenChar)) return refuse(400)
        if (method != "GET" && method != "POST") return Outcome(404, null)
        // Only the origin form: an absolute target ("http://…") would name a host of its own.
        if (!target.startsWith('/') || target.any { it <= ' ' || it >= '\u007f' }) return refuse(400)

        val headers = when (val read = reader.headers(limits.head)) {
            null -> return null
            is RequestReader.Headers.Refused -> return refuse(read.status)
            is RequestReader.Headers.Read -> read.fields
        }
        // Against DNS rebinding: a page elsewhere can reach this address under its own name, but can't make the browser send this Host.
        val hosts = headers.filter { it.first == "host" }
        if (hosts.size != 1 || !hosts.single().second.equals(listening.authority, ignoreCase = true)) return refuse(400)
        // A form posted from a page elsewhere says so; curl and the tests send none.
        val origins = headers.filter { it.first == "origin" }
        if (origins.size > 1 || origins.any { !it.second.equals("http://${listening.authority}", ignoreCase = true) }) return refuse(403)

        val candidate = RequestReader.decodePath(target.substringBefore('?'))?.removePrefix("/")
        if (candidate == null || !SetupToken.isTokenShaped(candidate)) return Outcome(404, SetupPage.notFound)
        val peer = client.inetAddress
        synchronized(lock) {
            when (phase) {
                Phase.Open -> Unit
                Phase.Closed -> return null
                else -> return ended()
            }
            val now = clock()
            if (now >= deadline) {
                if (openedAt.let { it != null && now >= it + limits.capMillis }) expiredPage = SetupPage.closedAtCap
                end(Phase.Expired)
                return Outcome(410, expiredPage, ending = SetupEvent.Expired)
            }
            if (!token.matches(candidate)) {
                // Once the page is open, another device's wrong codes aren't counted: a prank on the network can't close it.
                if (owner != null && peer != owner) return Outcome(404, SetupPage.notFound)
                wrongTries++
                if (wrongTries < limits.wrongTries) {
                    tell(SetupEvent.WrongTry(wrongTries))
                    return Outcome(404, SetupPage.notFound)
                }
                end(Phase.Locked)
                return Outcome(410, SetupPage.locked, ending = SetupEvent.Locked)
            }
            when (owner) {
                null -> {
                    owner = peer
                    openedAt = now
                    tell(SetupEvent.Opened(peer))
                }
                peer -> Unit
                else -> {
                    tell(SetupEvent.PeerRefused(peer))
                    return Outcome(403, SetupPage.otherDevice)
                }
            }
            used(now)?.let(::tell)
            if (method == "GET") return Outcome(200, page(now))
        }

        val length = when (val checked = contentLength(headers)) {
            is Step.Refuse -> return Outcome(checked.status, SetupPage.refused)
            is Step.Go -> checked.value
        }
        val type = headers.filter { it.first == "content-type" }
        if (type.size != 1 || !type.single().second.substringBefore(';').trim().equals(FORM_TYPE, ignoreCase = true)) {
            return Outcome(415, SetupPage.refused)
        }
        val body = reader.bytes(length) ?: return null
        val run = synchronized(lock) {
            val flow = flow
            if (phase != Phase.Open || flow == null) return if (phase == Phase.Closed) null else ended()
            // Read against the page as it is now, and applied at once: what it may send is what it shows.
            val post = when (val parsed = SetupForm.parse(body, flow.layout())) {
                is SetupRejection -> {
                    tell(SetupEvent.Rejected(parsed))
                    return Outcome(400, SetupPage.rejected)
                }
                is SetupPost -> parsed
            }
            val step = flow.step
            when (val applied = flow.apply(post, closingSoon(clock()))) {
                is Applied.Sent -> {
                    end(Phase.Used)
                    return Outcome(200, SetupPage.sent, ending = SetupEvent.Received(applied.changes, peer))
                }
                is Applied.Shown -> {
                    read(flow, step)
                    // Next or Back left the step a running test is of: it stops, as a newer test would stop it, so a late
                    // result can't hold up a step the phone has left. Its card keeps what it had. It never finishes, so
                    // the TV is told here, inside the lock, where a newer test's start can't come before it.
                    val stopped = running?.takeIf { flow.step != step }
                    if (stopped != null) {
                        stopped.replaced = true
                        stopTest()
                        tell(SetupEvent.TestStopped(stopped.ticket.slot))
                    }
                    return Outcome(303, null, location = location(applied.anchor))
                }
                is Applied.Testing -> {
                    // A newer test replaces the one running, whose page the browser has given up on.
                    running?.let { it.replaced = true }
                    stopTest()
                    read(flow, step)
                    tell(SetupEvent.TestStarted(applied.ticket.slot))
                    Run(applied.ticket).also { running = it }
                }
            }
        }
        // Delivered before the test is waited on: the TV shows it running meanwhile.
        deliver()
        return test(run)
    }

    /** Tells the TV a form was read, and the step it went to, if it moved from [before]. Under [lock]. */
    private fun read(flow: SetupFlow, before: SetupStep) {
        tell(SetupEvent.FormRead)
        if (flow.step != before) tell(SetupEvent.StepChanged(flow.step))
    }

    /**
     * The test [run] is of, on this connection's thread: waited on for at most [SetupLimits.testMillis], its result
     * written onto its card, and answered with a 303 to the card. Its events are told inside [lock], so a replaced test's
     * end can't follow a newer one's start. Null to close the connection unanswered: the session closed meanwhile.
     */
    private fun test(run: Run): Outcome? {
        val ticket = run.ticket
        var words = SetupPage.COULDNT_RUN
        val future = try {
            tester.test(ticket.item)
        } catch (_: RuntimeException) {
            // The app's, so it shouldn't; but a session must outlive it.
            null
        }
        synchronized(lock) {
            if (future != null && running === run && phase == Phase.Open) run.future = future else future?.cancel(true)
        }
        val result = try {
            future?.get(limits.testMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future?.cancel(true)
            words = SetupPage.TOOK_TOO_LONG
            null
        } catch (_: CancellationException) {
            // Replaced, or the session ended: told below.
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        synchronized(lock) {
            val flow = flow
            if (running !== run || phase != Phase.Open || flow == null) {
                return when {
                    run.replaced -> Outcome(303, null, location = location(ticket.slot.key))
                    phase == Phase.Open || phase == Phase.Closed -> null
                    else -> ended()
                }
            }
            running = null
            val answered = result ?: SetupResult(SetupOutcome.Failed, words)
            val slot = flow.finish(ticket, answered)
            // The end of a test is use too: the phone was waiting on it.
            used(clock())?.let(::tell)
            tell(SetupEvent.TestFinished(ticket.slot, answered.outcome))
            return Outcome(303, null, location = location(slot?.key ?: SetupFlow.TOP))
        }
    }

    /** What a request to a session that has ended is told: used, locked, or closed by time. Under [lock]. */
    private fun ended() = Outcome(
        410,
        when (phase) {
            Phase.Used -> SetupPage.used
            Phase.Locked -> SetupPage.locked
            else -> expiredPage
        },
    )

    /** The page as the flow has it now, with the code's time in it. Under [lock]. */
    private fun page(now: Long): String? {
        val flow = flow ?: return null
        val left = openedAt?.let { it + limits.capMillis - now }
        val closesIn = left?.takeIf { it <= CLOSING_NOTE_MILLIS }?.let { ((it + MINUTE - 1) / MINUTE).toInt() }
        return SetupPage.render(flow.show(closingSoon(now), closesIn))
    }

    /** Too little time is left before the cap for a test to finish. Under [lock]. */
    private fun closingSoon(now: Long) = openedAt.let { it != null && it + limits.capMillis - now < limits.testRoomMillis }

    /** The owner used the code at [now]: its deadline moves, and an event says so when it did. Under [lock]. */
    private fun used(now: Long): SetupEvent? {
        lastUse = maxOf(lastUse, now)
        val opened = openedAt ?: return null
        val next = minOf(lastUse + limits.idleMillis, opened + limits.capMillis)
        if (next == deadline) return null
        deadline = next
        return SetupEvent.DeadlineMoved(next)
    }

    /** Ends the session as [to]: its test stopped, its cards and what was typed into them let go. Under [lock]. */
    private fun end(to: Phase) {
        phase = to
        stopTest()
        flow = null
    }

    /** Cancels the test that runs, if any. Under [lock]. */
    private fun stopTest() {
        running?.future?.cancel(true)
        running = null
    }

    /** Back to the page's own address, at [anchor]: the code as the server holds it, nothing from the request. */
    private fun location(anchor: String) = "/${token.value}#$anchor"

    /** A part of a request read, or the status to refuse it with. */
    private sealed interface Step<out T> {
        class Go<T>(val value: T) : Step<T>
        class Refuse(val status: Int) : Step<Nothing>
    }

    /** A POST's one Content-Length, within the limit; a body in chunks isn't taken at all. */
    private fun contentLength(headers: List<Pair<String, String>>): Step<Int> {
        if (headers.any { it.first == "transfer-encoding" }) return Step.Refuse(400)
        val lengths = headers.filter { it.first == "content-length" }
        if (lengths.isEmpty()) return Step.Refuse(411)
        val value = lengths.singleOrNull()?.second?.takeIf { it.length in 1..10 && it.all { c -> c in '0'..'9' } } ?: return Step.Refuse(400)
        val length = value.toLong()
        // Refused from the headers, before a byte of the body is read.
        return if (length > limits.body) Step.Refuse(413) else Step.Go(length.toInt())
    }

    private fun refuse(status: Int) = Outcome(status, SetupPage.refused)

    private fun write(client: Socket, status: Int, page: String?, location: String?) {
        val body = page?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS.getValue(status)).append("\r\n")
            for ((name, value) in SetupPage.headers) append(name).append(": ").append(value).append("\r\n")
            location?.let { append("Location: ").append(it).append("\r\n") }
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1) + body)
            flush()
        }
    }

    /** Reads and drops what the peer still sends, for a moment, so closing with it unread doesn't reset the answer away. */
    private fun drain(client: Socket) {
        val until = clock() + limits.drainMillis
        val input = client.getInputStream()
        val scrap = ByteArray(4096)
        var total = 0
        try {
            while (total < DRAIN_MAX) {
                val left = until - clock()
                if (left <= 0) return
                client.soTimeout = left.toInt()
                val n = input.read(scrap)
                if (n < 0) return
                total += n
            }
        } catch (_: IOException) {
            // Timed out or gone: either way, done.
        }
    }

    companion object {
        /** RFC 1918 alone, as Java's isSiteLocalAddress has it for IPv4: no loopback, link-local, CGNAT, public or IPv6. */
        fun isPrivateIpv4(address: InetAddress): Boolean = address is Inet4Address && address.isSiteLocalAddress

        private const val BACKLOG = 8
        /** In the cap's last minutes the page says how many are left. */
        private const val CLOSING_NOTE_MILLIS = 5 * MINUTE
        private const val DRAIN_MAX = 64 * 1024
        private const val TLS_HANDSHAKE = 0x16
        private const val FORM_TYPE = "application/x-www-form-urlencoded"
        private val BUSY = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private val REASONS = mapOf(
            200 to "OK", 303 to "See Other", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 410 to "Gone", 411 to "Length Required",
            413 to "Content Too Large", 414 to "URI Too Long", 415 to "Unsupported Media Type", 431 to "Request Header Fields Too Large",
        )
    }
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is all that's left to do.
    }
}

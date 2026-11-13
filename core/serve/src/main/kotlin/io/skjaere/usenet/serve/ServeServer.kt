package io.skjaere.usenet.serve

import io.skjaere.usenet.handoff.RequestReader
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.handoff.SetupToken
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.EnumMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Cast's media server (C1): the phone serves the file a Cast device plays, at `http://<bind>:<port>/<token>/v.<extension>`
 * ([serve]), for as long as the cast plays it ([Served.revoke]). [start] binds it to [bind] alone, the phone's private IPv4
 * on its Wi-Fi; [close] ends everything at once. A listening socket lost with the address, as when the Wi-Fi blips, ends
 * nothing else: [ServeEvent.ListenerLost] says so, and [relisten] binds the same port again once the address is back (L74).
 *
 * The answers (L53): GET, HEAD and OPTIONS, and a 405 for anything else. A GET's Range is served from the byte asked
 * ([rangeAnswer]): a 206 with its Content-Range, the whole file as a 200 without one, a 416 for a start past the end; every
 * answer gives the file's same length, and its headers go at once, as the size is known, the body after them as the source
 * gives it, never compressed. Every answer, errors too, allows any origin and is `no-store` and `Connection: close`. A
 * receiver's player fails the media on a 5xx, never retrying it (item 6(a)), so none is ever sent for a file served: a
 * source that fails gets its headers out and then a close, which the receiver asks again from where it got to.
 *
 * Who is answered (L55, L74): peers [allowPeer] refuses (by default anything but RFC 1918 IPv4) are dropped unanswered, as
 * is a TLS ClientHello; the Host must be this address; a path that names no file served, or one revoked, is a 404 that
 * locks nothing; a request with a body is a 400. The first GET of a file pins it to its peer, and the device it was served
 * for takes the pin whenever it asks, the connections of the peer that held it closing; any other peer is refused with a
 * 403. HEAD and OPTIONS pass the same checks and never set the pin.
 *
 * Connections (L54, L101): a request's head must be whole within [ServeLimits.headMillis] of the accept, through a
 * [RequestReader]. At most [ServeLimits.reading] read their heads at once, a newcomer displacing the oldest, and from an
 * address that is neither a file's expected device nor its pin's at most [ServeLimits.readingPerAddress] at once and
 * [ServeLimits.acceptsPerWindow] a minute. A GET for a file takes one of [ServeLimits.serving] serving slots; with all of
 * them held it evicts the one whose writes last made progress longest ago, never one inside the head guard, and with none
 * of them able to go it is closed unanswered. A write with no progress for [ServeLimits.stallMillis] closes its connection,
 * a response from byte 0 having [ServeLimits.headStallMillis] until it has written [ServeLimits.headGuardBytes]. While a
 * connection waits for its source, its socket is looked at every [ServeLimits.pollMillis] for a hang-up, which ends it.
 *
 * Each file's answers read its source through its [Upstreams] (L56, L76): two streams of it at most, an ended answer's
 * parked for the next, the head opened ahead of the LOAD ([Served.openHead]), and a byte that failed asked again at most
 * every few seconds. An answer whose stream another takes closes, and the receiver asks again.
 *
 * Nothing is logged: [onEvent] and the counters ([counters], [Served.counters]) say what happened, in numbers and statuses.
 */
class ServeServer(
    private val bind: Inet4Address,
    private val port: Int = 0,
    /** Monotonic milliseconds: the heads' deadlines, the accept windows, the progress and the counters' times. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val limits: ServeLimits = ServeLimits(),
    private val allowPeer: (InetAddress) -> Boolean = { isPrivateIpv4(it) },
    private val onEvent: (ServeEvent) -> Unit = {},
) : AutoCloseable {
    private enum class Phase {
        /** Reading its request's head: it holds a head slot. */
        Reading,

        /** Answered with no body (an error, a HEAD, an OPTIONS), and closing: it holds no slot. */
        Answering,

        /** Writing a body: it holds a serving slot. */
        Serving,
    }

    /** A connection the server holds: from its accept until its thread ends. */
    private inner class Conn(val socket: Socket, val address: InetAddress, val acceptedAt: Long) {
        /** Set under [lock]. */
        var phase = Phase.Reading

        /** The file it serves, from byte [from]. Set under [lock], before [phase] becomes Serving. */
        var file: Served? = null
        @Volatile var from = 0L
        @Volatile var sent = 0L

        /** When its last write ended, or its accept. */
        @Volatile var progressAt = acceptedAt
        @Volatile var upstream: Upstream? = null

        /** A newcomer took its head slot. */
        @Volatile var displaced = false

        private val closedFor = AtomicReference<ServeEnd?>(null)

        /** Why it was closed from outside its thread, by whoever closed it first; null while it wasn't. */
        val reason: ServeEnd? get() = closedFor.get()

        /** Inside the head guard (L101): from byte 0, with fewer than the guard's bytes written. */
        val guarded: Boolean get() = from == 0L && sent < limits.headGuardBytes

        /** Closes it now, its upstream with it, for [why]: its thread finds it closed. */
        fun close(why: ServeEnd) {
            closedFor.compareAndSet(null, why)
            socket.closeQuietly()
            upstream?.closeQuietly()
        }
    }

    /** What a request is answered with. */
    private sealed interface Outcome {
        /** Nothing: the connection is closed unanswered. */
        class Drop(val why: ServeDrop) : Outcome

        /** An answer with no body (an error, a HEAD, an OPTIONS), of [file] once one was named and its peer could ask. */
        class Short(val status: Int, val fields: List<Pair<String, String>>, val method: ServeMethod?, val file: Served? = null) : Outcome

        /** A GET's [status] (200 or 206) and bytes [first] to [last] of [file], for a connection that holds a serving slot. */
        class Body(val file: Served, val status: Int, val first: Long, val last: Long) : Outcome
    }

    /** Set by [start], under [listenLock]. */
    @Volatile private var started = false
    /** The socket it listens on: none once lost or closed, until [relisten] binds another. Set under [lock]. */
    @Volatile private var listener: ServerSocket? = null
    /** The port [start] bound, which [relisten] binds again. */
    private var listenPort = 0
    /** One [start] or [relisten] at a time: a port has one listener. */
    private val listenLock = Any()
    @Volatile private var closed = false
    private val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "serve-timer").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

    /** Guards [conns], [files], [accepts], the counts, each connection's phase and file, and each file's pin. */
    private val lock = Any()
    /** In the order they were accepted. */
    private val conns = mutableListOf<Conn>()
    private val files = mutableListOf<Served>()
    private var nextId = 1
    private var notFound = 0
    private val dropped = EnumMap<ServeDrop, Int>(ServeDrop::class.java)
    /** Recent accepts by address, the addresses least lately seen dropped first past [MAX_ADDRESSES]. */
    private val accepts = object : LinkedHashMap<InetAddress, RateWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetAddress, RateWindow>?) = size > MAX_ADDRESSES
    }

    /** Held while an event is delivered, so none is delivered once [close] has returned. Never taken inside [lock]. */
    private val eventLock = Any()
    private var stopped = false

    /** `<ip>:<port>`, once [start] has bound it: what a request's Host must be. */
    lateinit var authority: String
        private set

    /**
     * Binds the server and starts taking connections; returns the port (OS-assigned for port 0). Throws an IOException when
     * it can't bind (the address has gone, say).
     */
    fun start(): Int = synchronized(listenLock) {
        check(!started && !closed) { "a media server starts once" }
        val socket = listen(port)
        authority = "${bind.hostAddress}:${socket.localPort}"
        listenPort = socket.localPort
        started = true
        take(socket)
        socket.localPort
    }

    /**
     * Listens again on the same address and port, keeping every file served under its token, so the URL a Cast device holds
     * answers again (L74): once the address is back after the listening socket was lost with it ([ServeEvent.ListenerLost]).
     * A listener still there is closed first. Returns the port. Throws an IOException when the address or the port can't be
     * had: the cast fails then (L88), or tries again.
     */
    fun relisten(): Int = synchronized(listenLock) {
        val old = synchronized(lock) {
            check(started && !closed) { "listening again on a media server that isn't open" }
            listener.also { listener = null }
        }
        // Gone with the address, mostly; one still there gives its port up, its accept thread ending without a word.
        old?.closeQuietly()
        val socket = listen(listenPort)
        if (!take(socket)) throw IOException("the media server was closed")
        socket.localPort
    }

    /** A socket bound to [bind] at [port], listening. */
    private fun listen(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            // The port's earlier connections, closed by the server first, wait out TIME_WAIT on it: they mustn't stop a relisten.
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bind, port), BACKLOG)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw e
        }
        return socket
    }

    /** Listens on [socket] from now, unless the server was closed meanwhile; then it is closed, and false. */
    private fun take(socket: ServerSocket): Boolean {
        val open = synchronized(lock) {
            if (!closed) listener = socket
            !closed
        }
        if (open) thread(isDaemon = true, name = "serve-accept") { accept(socket) } else socket.closeQuietly()
        return open
    }

    /**
     * Serves [file] from now, under a new token, until it is revoked: for the Cast device at [expectedPeer] (its address as
     * it was found, null when unknown), which takes the pin whenever it asks.
     */
    fun serve(file: ServedFile, expectedPeer: InetAddress?): Served = synchronized(lock) {
        check(started && !closed) { "serving from a server that isn't open" }
        Served(nextId++, SetupToken.generate(), file, expectedPeer, authority, this).also { files += it }
    }

    /** The socket it listens on now: the tests close it as Android does when it takes the address away. */
    internal val listening: ServerSocket? get() = listener

    /** What became of requests no served file answered, so far. */
    fun counters(): ServerCounters = synchronized(lock) { ServerCounters(notFound, dropped.toMap()) }

    /** Ends everything now: no more connections, the open ones and their upstreams closed, nothing more heard. Returns without waiting. */
    override fun close() {
        synchronized(eventLock) { stopped = true }
        val (open, served, listening) = synchronized(lock) {
            closed = true
            files.forEach { it.revoked = true }
            val served = files.toList()
            files.clear()
            Triple(conns.toList(), served, listener.also { listener = null })
        }
        listening?.closeQuietly()
        open.forEach { it.close(ServeEnd.Closed) }
        served.forEach { it.upstreams.close() }
        timer.shutdownNow()
    }

    internal fun revoke(served: Served) {
        val ending = synchronized(lock) {
            if (served.revoked) return
            served.revoked = true
            files.remove(served)
            conns.filter { it.file === served }
        }
        // The connections first, so each knows why it ended before its stream closes under it.
        ending.forEach { it.close(ServeEnd.Revoked) }
        served.upstreams.close()
    }

    /** The streams of [file]'s source its answers read, its zeros told to [gaps]. */
    internal fun upstreamsOf(file: ServedFile, gaps: (LongRange) -> Unit): Upstreams =
        DirectUpstreams(file.source, file.size, limits, clock, timer, gaps)

    private fun accept(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // Closed by close() or for relisten(); or lost with the phone's address (L74), or failed: then what is
                // served stays served and its connections go on, for the app to listen again when it can.
                val lost = synchronized(lock) { (listener === socket).also { if (it) listener = null } }
                socket.closeQuietly()
                if (lost) emit(ServeEvent.ListenerLost)
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

    /** Takes [client] into a head slot and starts its thread, or closes it unanswered. */
    private fun admit(client: Socket) {
        val address = client.inetAddress
        if (!allowPeer(address)) return drop(client, ServeDrop.NotAllowed)
        val now = clock()
        val conn = Conn(client, address, now)
        var displaced: Conn? = null
        val refused = synchronized(lock) {
            if (closed) return client.closeQuietly()
            val known = files.any { it.expected == address || it.pin == address }
            when {
                !known && !accepts.getOrPut(address) { RateWindow(limits.acceptsPerWindow, limits.acceptWindowMillis) }.take(now) -> ServeDrop.TooManyAccepts
                !known && conns.count { it.phase == Phase.Reading && it.address == address } >= limits.readingPerAddress -> ServeDrop.TooManyFromAddress
                else -> {
                    // Every head slot taken: the oldest gives its own up. A serving connection never does.
                    if (conns.count { it.phase == Phase.Reading } >= limits.reading) {
                        displaced = conns.first { it.phase == Phase.Reading }.also { conns.remove(it) }
                    }
                    conns += conn
                    null
                }
            }
        }
        if (refused != null) return drop(client, refused)
        displaced?.let {
            it.displaced = true
            it.close(ServeEnd.Closed)
        }
        thread(isDaemon = true, name = "serve-connection") { run(conn) }
    }

    private fun run(conn: Conn) {
        try {
            when (val outcome = read(conn)) {
                is Outcome.Drop -> drop(conn.socket, outcome.why)
                is Outcome.Short -> short(conn, outcome)
                is Outcome.Body -> body(conn, outcome)
            }
        } catch (_: Throwable) {
            // A bug or a failure of the JVM's: the connection closes and its thread ends, the server goes on. A thread of its
            // own runs no coroutine whose cancellation this could swallow.
        } finally {
            synchronized(lock) { conns.remove(conn) }
            conn.socket.closeQuietly()
            conn.upstream?.closeQuietly()
        }
    }

    /** The request on [conn] read and decided; the connection's phase and slot set for its answer. */
    private fun read(conn: Conn): Outcome = try {
        conn.socket.tcpNoDelay = true
        decide(conn, RequestReader(conn.socket, conn.acceptedAt + limits.headMillis, clock))
    } catch (_: SocketTimeoutException) {
        Outcome.Drop(ServeDrop.TimedOut)
    } catch (_: IOException) {
        // Gone, or closed here: by a newcomer that took its slot, or by close().
        Outcome.Drop(if (conn.displaced) ServeDrop.Displaced else ServeDrop.Left)
    }

    private fun decide(conn: Conn, reader: RequestReader): Outcome {
        // A TLS ClientHello: something trying https. Nothing to say to it in plain HTTP.
        if (reader.peek() == TLS_HANDSHAKE) return Outcome.Drop(ServeDrop.Tls)
        val requestLine = when (val line = reader.line(limits.requestLine)) {
            null -> return Outcome.Drop(ServeDrop.Left)
            RequestReader.Line.TooLong -> return refuse(conn, 414, null)
            RequestReader.Line.Bad -> return refuse(conn, 400, null)
            is RequestReader.Line.Text -> String(line.bytes, Charsets.ISO_8859_1)
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3) return refuse(conn, 400, null)
        val (name, target, version) = parts
        if (version != "HTTP/1.1" && version != "HTTP/1.0" || name.isEmpty() || !name.all(RequestReader::isTokenChar)) return refuse(conn, 400, null)
        val method = METHODS[name] ?: return refuse(conn, 405, null, listOf("Allow" to ALLOW))
        // Only the origin form: an absolute target ("http://…") would name a host of its own.
        if (!target.startsWith('/') || target.any { it <= ' ' || it >= '\u007f' }) return refuse(conn, 400, method)
        val headers = when (val read = reader.headers(limits.head)) {
            null -> return Outcome.Drop(ServeDrop.Left)
            is RequestReader.Headers.Refused -> return refuse(conn, read.status, method)
            is RequestReader.Headers.Read -> read
        }
        // Against DNS rebinding: a page elsewhere can reach this address under a name of its own, but can't make the browser send this Host.
        val hosts = headers.values("host")
        if (hosts.size != 1 || !hosts.single().equals(authority, ignoreCase = true)) return refuse(conn, 400, method)
        // A media request has no body: one that has is someone else's.
        val lengths = headers.values("content-length")
        if (headers.values("transfer-encoding").isNotEmpty() || lengths.size > 1 || lengths.any { it.isEmpty() || it.any { c -> c != '0' } }) {
            return refuse(conn, 400, method)
        }
        val path = RequestReader.decodePath(target.substringBefore('?'))
        return answer(conn, method, path, headers)
    }

    /** The answer for a request that passed the checks of its form, by its [path], its peer and its Range. */
    private fun answer(conn: Conn, method: ServeMethod, path: String?, headers: RequestReader.Headers.Read): Outcome {
        val peer = conn.address
        var pinned: ServeEvent.Pinned? = null
        var unpinned = emptyList<Conn>()
        var evicted: Conn? = null
        val outcome = synchronized(lock) {
            val file = path?.let { p -> files.firstOrNull { it.answers(p) } }
            if (file == null) {
                notFound++
                return refuse(conn, 404, method)
            }
            // The pin (L55): its peer is answered, any while there is none, and the device it was served for always.
            val pin = file.pin
            if (pin != null && pin != peer && peer != file.expected) {
                file.count { it.copy(refused = it.refused + 1) }
                return refuse(conn, 403, method)
            }
            // Only a GET sets it. Another peer takes it only while it is free, so a receiver that fetches from another address
            // than it was found at still plays, counted; the expected device takes it from another, whose connections go.
            if (method == ServeMethod.Get && pin != peer) {
                file.pin = peer
                val expected = peer == file.expected
                if (!expected && file.expected != null) file.count { it.copy(mismatches = it.mismatches + 1) }
                pinned = ServeEvent.Pinned(file.id, expected)
                if (pin != null) {
                    unpinned = conns.filter { it.file === file && it.address == pin }
                    conns.removeAll(unpinned)
                }
            }
            val size = file.file.size
            val range = rangeAnswer(headers.values("range"), headers.values("if-range").isNotEmpty(), size)
            val (status, first, last) = if (range is RangeAnswer.Part) Triple(206, range.first, range.last) else Triple(200, 0L, size - 1)
            val decided = when {
                method == ServeMethod.Options -> Outcome.Short(204, PREFLIGHT, method, file)
                range == RangeAnswer.Unsatisfiable ->
                    Outcome.Short(416, listOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes", "Content-Length" to "0"), method, file)
                method == ServeMethod.Head -> Outcome.Short(status, fields(file.file, status, first, last), method, file)
                else -> {
                    // A serving slot (L54): with all of them held, the one whose writes last made progress longest ago goes,
                    // never one inside the head guard (L101); with none able to go, this one is closed unanswered.
                    val serving = conns.filter { it.phase == Phase.Serving }
                    val victim = if (serving.size < limits.serving) null else serving.filter { !it.guarded }.minByOrNull { it.progressAt }
                    if (serving.size >= limits.serving && victim == null) {
                        Outcome.Drop(ServeDrop.Full)
                    } else {
                        victim?.let {
                            conns.remove(it)
                            evicted = it
                        }
                        conn.file = file
                        conn.from = first
                        conn.phase = Phase.Serving
                        Outcome.Body(file, status, first, last)
                    }
                }
            }
            if (decided is Outcome.Short) conn.phase = Phase.Answering
            if (decided !is Outcome.Drop) counted(file)
            decided
        }
        pinned?.let(::emit)
        unpinned.forEach { it.close(ServeEnd.Unpinned) }
        evicted?.close(ServeEnd.Evicted)
        return outcome
    }

    /** Under [lock]: one more request answered for [file]. */
    private fun counted(file: Served) {
        val now = clock()
        file.count { it.copy(requests = it.requests + 1, firstRequestAt = it.firstRequestAt ?: now) }
    }

    private fun refuse(conn: Conn, status: Int, method: ServeMethod?, fields: List<Pair<String, String>> = emptyList()): Outcome {
        synchronized(lock) { conn.phase = Phase.Answering }
        return Outcome.Short(status, fields + ("Content-Length" to "0"), method)
    }

    /** A 200's or 206's headers, a GET's or a HEAD's. */
    private fun fields(file: ServedFile, status: Int, first: Long, last: Long): List<Pair<String, String>> =
        listOfNotNull(
            "Content-Type" to file.mimeType,
            "Content-Length" to "${last - first + 1}",
            if (status == 206) "Content-Range" to "bytes $first-$last/${file.size}" else null,
            "Accept-Ranges" to "bytes",
        )

    /** An answer with no body; what's left of the request is read and dropped for a moment, so the close doesn't reset it away. */
    private fun short(conn: Conn, answer: Outcome.Short) {
        emit(ServeEvent.Answered(answer.file?.id, answer.method, answer.status))
        val socket = conn.socket
        try {
            socket.getOutputStream().write(head(answer.status, answer.fields))
            socket.shutdownOutput()
            drain(socket)
        } catch (_: IOException) {
            // The peer went.
        }
    }

    /** A GET's answer: its headers at once, then the body as the source gives it; how it ended is told, and counted. */
    private fun body(conn: Conn, answer: Outcome.Body) {
        val served = answer.file
        emit(ServeEvent.Answered(served.id, ServeMethod.Get, answer.status))
        if (answer.status == 206) served.count { it.copy(partials = it.partials + 1) }
        var why = ServeEnd.Closed
        try {
            why = stream(conn, answer)
        } finally {
            // A close from outside the thread says why the writes or reads failed.
            val ended = conn.reason ?: why
            when (ended) {
                ServeEnd.Stalled -> served.count { it.copy(stalls = it.stalls + 1) }
                ServeEnd.Evicted, ServeEnd.Taken -> served.count { it.copy(evictions = it.evictions + 1) }
                ServeEnd.UpstreamFailed -> served.count {
                    it.copy(upstreamFailures = it.upstreamFailures + 1, headFailures = it.headFailures + if (answer.first == 0L) 1 else 0)
                }
                ServeEnd.Unpinned -> served.count { it.copy(unpinned = it.unpinned + 1) }
                ServeEnd.Done, ServeEnd.Left, ServeEnd.Revoked, ServeEnd.Closed -> Unit
            }
            emit(ServeEvent.Ended(served.id, answer.first, conn.sent, ended))
        }
    }

    private fun stream(conn: Conn, answer: Outcome.Body): ServeEnd {
        val socket = conn.socket
        val out = socket.getOutputStream()
        val head = head(answer.status, fields(answer.file.file, answer.status, answer.first, answer.last))
        if (!write(conn, answer.file, out, head, head.size)) return ServeEnd.Left
        if (answer.first <= answer.last) {
            val upstream = try {
                // Taken for another answer of the file (L56): this connection closes, and the receiver asks again.
                answer.file.upstreams.open(answer.first) { conn.close(ServeEnd.Taken) }
            } catch (_: Exception) {
                // The app's source, whatever it throws, or a retry too soon after a failure here (L76): the headers are out,
                // and the receiver asks again from here (L53).
                return ServeEnd.UpstreamFailed
            }
            conn.upstream = upstream
            // Closed while it opened: the close may have looked for the upstream before it was there.
            if (conn.reason != null) return ServeEnd.Closed.also { upstream.closeQuietly() }
            val read = answer.file.reading(answer.first)
            val buffer = ByteArray(CHUNK)
            var position = answer.first
            while (position <= answer.last) {
                val want = minOf(CHUNK.toLong(), answer.last - position + 1).toInt()
                val asked = clock()
                val n = try {
                    upstream.read(buffer, 0, want, limits.pollMillis)
                } catch (_: Exception) {
                    // As at its open: a close, at the byte it reached.
                    return ServeEnd.UpstreamFailed
                }
                // Shorter than its size: a failure as any other, at the byte it reached.
                if (n < 0) return ServeEnd.UpstreamFailed
                // However the read ended, its time was the source's (L133): a whole poll with nothing, or a wait for bytes
                // that then came, as a source a little slower than the film needs answers; one that had them takes none.
                val waited = clock() - asked
                if (waited > 0) answer.file.count { it.copy(sourceWaitMillis = it.sourceWaitMillis + waited) }
                when {
                    // Nothing yet: the receiver sees buffering, as it should, unless it has gone.
                    n == 0 -> if (hungUp(socket)) return ServeEnd.Left
                    else -> {
                        if (!write(conn, answer.file, out, buffer, n)) return ServeEnd.Left
                        position += n
                        conn.sent += n
                        answer.file.wrote(read, n, position)
                    }
                }
            }
        }
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
            // All of it was written: gone now, it was read.
        }
        return ServeEnd.Done
    }

    /**
     * [length] bytes of [bytes] to [out], for [file]: false when the peer went, or the connection was closed meanwhile. A
     * write that makes no progress for the stall limit (L54), the head guard's own inside it (L101), closes the connection.
     * However it ends, its time was the peer's to take what was sent (L133).
     */
    private fun write(conn: Conn, file: Served, out: OutputStream, bytes: ByteArray, length: Int): Boolean {
        val limit = if (conn.guarded) limits.headStallMillis else limits.stallMillis
        val stuck = try {
            timer.schedule({ conn.close(ServeEnd.Stalled) }, limit, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // The timer is shut down with the server, which closes its connections itself: this one now.
            return false
        }
        val began = clock()
        return try {
            out.write(bytes, 0, length)
            conn.progressAt = clock()
            true
        } catch (_: IOException) {
            false
        } finally {
            stuck.cancel(false)
            val waited = clock() - began
            file.count { it.copy(peerWaitMillis = it.peerWaitMillis + waited) }
        }
    }

    /** Whether the peer has hung up: a read of the end, within a moment. Anything it still sends is dropped. */
    private fun hungUp(socket: Socket): Boolean = try {
        socket.soTimeout = 1
        socket.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    /** Reads and drops what the peer still sends, for a moment, so closing with it unread doesn't reset the answer away. */
    private fun drain(socket: Socket) {
        val until = clock() + DRAIN_MILLIS
        val input = socket.getInputStream()
        val scrap = ByteArray(4096)
        var total = 0
        try {
            while (total < DRAIN_MAX) {
                val left = until - clock()
                if (left <= 0) return
                socket.soTimeout = left.coerceAtMost(DRAIN_MILLIS).toInt()
                val n = input.read(scrap)
                if (n < 0) return
                total += n
            }
        } catch (_: IOException) {
            // Timed out or gone: either way, done.
        }
    }

    private fun drop(socket: Socket, why: ServeDrop) {
        socket.closeQuietly()
        synchronized(lock) {
            if (closed) return
            dropped[why] = (dropped[why] ?: 0) + 1
        }
        emit(ServeEvent.Dropped(why))
    }

    private fun emit(event: ServeEvent) = synchronized(eventLock) { if (!stopped) onEvent(event) }

    private fun head(status: Int, fields: List<Pair<String, String>>): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(REASONS.getValue(status)).append("\r\n")
        for ((name, value) in fields + ALWAYS) append(name).append(": ").append(value).append("\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    companion object {
        /** RFC 1918 alone, as the setup page has it: no loopback, link-local, CGNAT, public or IPv6. */
        fun isPrivateIpv4(address: InetAddress): Boolean = SetupServer.isPrivateIpv4(address)

        /** Connections the OS holds for the accept thread, which takes or drops each at once. */
        private const val BACKLOG = 64

        /** Addresses whose recent accepts are kept. */
        private const val MAX_ADDRESSES = 256

        /** What a body is read and written in: the unit of a write's progress. */
        private const val CHUNK = 64 * 1024
        private const val DRAIN_MILLIS = 250L
        private const val DRAIN_MAX = 64 * 1024
        private const val TLS_HANDSHAKE = 0x16

        private val METHODS = mapOf("GET" to ServeMethod.Get, "HEAD" to ServeMethod.Head, "OPTIONS" to ServeMethod.Options)
        private const val ALLOW = "GET, HEAD, OPTIONS"

        /**
         * On every answer, errors too (L53): any origin may read it (side-loaded subtitles would ask, which wait on the back
         * burner, W55), the receiver keeps nothing (Terms 3.4.10).
         */
        private val ALWAYS = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
            "Cache-Control" to "no-store",
            "Connection" to "close",
        )

        private val PREFLIGHT = listOf(
            "Access-Control-Allow-Methods" to ALLOW,
            "Access-Control-Allow-Headers" to "Range, Content-Type, Accept-Encoding",
            "Access-Control-Max-Age" to "600",
        )

        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 414 to "URI Too Long", 416 to "Range Not Satisfiable", 431 to "Request Header Fields Too Large",
        )
    }
}

/**
 * Whether one more event fits in a sliding window of [windowMillis] holding at most [limit]; one that fits is counted.
 * Not thread-safe: each is kept under its owner's lock.
 */
internal class RateWindow(private val limit: Int, private val windowMillis: Long) {
    private val times = LongArray(limit)
    /** Where the next time goes: once full, the oldest. */
    private var next = 0
    private var filled = 0

    fun take(now: Long): Boolean {
        if (filled == limit && now - times[next] < windowMillis) return false
        times[next] = now
        next = (next + 1) % limit
        if (filled < limit) filled++
        return true
    }
}

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is all that's left to do.
    }
}

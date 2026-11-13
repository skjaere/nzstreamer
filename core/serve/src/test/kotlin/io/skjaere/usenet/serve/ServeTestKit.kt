package io.skjaere.usenet.serve

import org.junit.jupiter.api.AfterEach
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The byte at [position] of every test file: a mix of its bits, so a byte from the wrong offset shows. */
fun byteAt(position: Long): Byte {
    val x = (position xor (position ushr 13)) * -0x61c8864680b583ebL
    return (x ushr 56).toByte()
}

/** The bytes [first] to [last] of a test file of [gaps] (zeros there). */
fun expected(first: Long, last: Long, gaps: List<LongRange> = emptyList()): ByteArray =
    ByteArray((last - first + 1).toInt()) { i -> (first + i).let { p -> if (gaps.any { p in it }) 0 else byteAt(p) } }

/**
 * A served file's bytes made up as they are read, [size] of them: [byteAt] each, zeros in [gaps], each told to the server
 * as a read reaches it. Its controls make a source slow, stuck or broken; it counts what the server did with it.
 */
class PatternSource(
    private val size: Long,
    private val gaps: List<LongRange> = emptyList(),
) : ByteSource {
    /** Reads wait while it is closed: an upstream that has nothing yet. */
    @Volatile var gate = CountDownLatch(0)
    /** Opening throws. */
    @Volatile var failOpen = false
    /** A read at this position throws, [failures] times. */
    @Volatile var failAt: Long? = null
    val failures = AtomicInteger(1)
    /**
     * How far short of [failAt] a stream opened at a position throws: what a source that reads ahead held then, dropped,
     * as the app's over the engine's channel does (L76). None by default.
     */
    @Volatile var shortBy: (from: Long) -> Long = { 0L }
    /** Each read gives at most this many bytes. */
    @Volatile var readSize = 64 * 1024
    /** Opening takes this long: a provider's latency. */
    @Volatile var openMillis = 0L

    val opens = CopyOnWriteArrayList<Long>()
    val open = AtomicInteger()
    val maxOpen = AtomicInteger()
    val bytesRead = AtomicLong()
    /** When each upstream was closed (nanoTime), in the order they were. */
    val closedAt = LinkedBlockingQueue<Long>()
    /** Each upstream closed: where it was opened, and when it closed (nanoTime). */
    val closes = CopyOnWriteArrayList<Pair<Long, Long>>()

    /** When the upstream opened at [from] closed (nanoTime), waiting up to [millis] for it. */
    fun closeOf(from: Long, millis: Long = 2000): Long {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (true) {
            closes.firstOrNull { it.first == from }?.let { return it.second }
            assertTrue(System.nanoTime() < until, "the upstream from $from wasn't closed")
            Thread.sleep(2)
        }
    }

    fun hold() {
        gate = CountDownLatch(1)
    }

    fun release() = gate.countDown()

    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
        if (openMillis > 0) Thread.sleep(openMillis)
        if (failOpen) throw IOException("the provider is down")
        opens += position
        val now = open.incrementAndGet()
        maxOpen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        return Stream(position, gaps)
    }

    private inner class Stream(private val from: Long, private val sink: (LongRange) -> Unit) : Upstream {
        private var position = from
        @Volatile private var closed = false
        private val told = mutableSetOf<LongRange>()

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (closed) throw IOException("closed")
            if (!gate.await(waitMillis, TimeUnit.MILLISECONDS)) return 0
            if (closed) throw IOException("closed")
            val fail = failAt?.let { it - shortBy(from) }
            if (fail != null && position >= fail && failures.getAndDecrement() > 0) throw IOException("a part of the wrong size")
            if (position >= size) return -1
            var n = minOf(length.toLong(), readSize.toLong(), size - position)
            if (fail != null && position < fail && failures.get() > 0) n = minOf(n, fail - position)
            val end = position + n - 1
            for (gap in gaps) if (gap.first <= end && gap.last >= position && told.add(gap)) sink(gap)
            for (i in 0 until n.toInt()) {
                val p = position + i
                buffer[offset + i] = if (gaps.any { p in it }) 0 else byteAt(p)
            }
            position += n
            bytesRead.addAndGet(n)
            return n.toInt()
        }

        override fun close() {
            if (closed) return
            closed = true
            open.decrementAndGet()
            val at = System.nanoTime()
            closes += from to at
            closedAt.put(at)
        }
    }
}

/** An answer's head: its status and its header fields as sent, in order. */
class Response(val status: Int, val headers: List<Pair<String, String>>) {
    fun header(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val length: Long? get() = header("Content-Length").singleOrNull()?.toLong()

    override fun toString() = "Response($status, $headers)"
}

/** A client's connection: its request out, the answer's head and body in, read as slowly as a test likes. */
class Http(val socket: Socket) {
    private val input = BufferedInputStream(socket.getInputStream(), 8192)

    fun send(request: ByteArray) {
        socket.getOutputStream().write(request)
    }

    /** The answer's head, or null when the connection ends before any. */
    fun head(): Response? {
        val raw = ByteArrayOutputStream()
        try {
            while (true) {
                val b = input.read()
                if (b < 0) return null
                raw.write(b)
                val bytes = raw.toByteArray()
                if (bytes.size >= 4 && String(bytes, bytes.size - 4, 4, Charsets.ISO_8859_1) == "\r\n\r\n") break
            }
        } catch (_: IOException) {
            return null
        }
        val lines = String(raw.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n").split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        return Response(status, lines.drop(1).map { it.substringBefore(": ") to it.substringAfter(": ") })
    }

    /** Up to [n] bytes, fewer when the connection ends first. */
    fun read(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var left = n
        try {
            while (left > 0) {
                val got = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (got < 0) break
                out.write(buffer, 0, got)
                left -= got
            }
        } catch (_: IOException) {
            // A reset: what came before it is what was read.
        }
        return out.toByteArray()
    }

    /** Everything until the connection ends. */
    fun rest(): ByteArray = read(Long.MAX_VALUE)

    /** Whether the server has closed the connection: reads what is left, then the end, within [millis]. */
    fun ended(millis: Int = 5000): Boolean {
        socket.soTimeout = millis
        return try {
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) Unit
            true
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    /** Closes with a reset, as a client that gives up on an answer it hasn't read. */
    fun abort() {
        socket.setSoLinger(true, 0)
        socket.close()
    }
}

/** Real sockets on loopback, a server per test, every event kept with when it came. */
abstract class ServeTestBase {
    protected val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    /** Every event the servers told, with when (nanoTime). */
    val events = LinkedBlockingQueue<Pair<Long, ServeEvent>>()
    private val servers = CopyOnWriteArrayList<ServeServer>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    @AfterEach
    fun closeAll() {
        servers.forEach { it.close() }
        sockets.forEach {
            try {
                it.close()
            } catch (_: IOException) {
                // Gone already.
            }
        }
    }

    protected fun server(
        limits: ServeLimits = ServeLimits(),
        allowPeer: (InetAddress) -> Boolean = { it.isLoopbackAddress },
        clock: (() -> Long)? = null,
    ): ServeServer {
        val server = if (clock == null) {
            ServeServer(loopback, limits = limits, allowPeer = allowPeer, onEvent = { events.put(System.nanoTime() to it) })
        } else {
            ServeServer(loopback, clock = clock, limits = limits, allowPeer = allowPeer, onEvent = { events.put(System.nanoTime() to it) })
        }
        servers += server
        server.start()
        return server
    }

    protected val ServeServer.port: Int get() = authority.substringAfterLast(':').toInt()

    /** A connection from [from], nothing sent yet; [receiveBuffer] to keep what the kernel holds for a client that doesn't read small. */
    protected fun connect(server: ServeServer, from: String = "127.0.0.1", receiveBuffer: Int? = null): Http {
        val socket = Socket()
        sockets += socket
        receiveBuffer?.let { socket.receiveBufferSize = it }
        socket.bind(InetSocketAddress(from, 0))
        socket.connect(InetSocketAddress(loopback, server.port), 2000)
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        return Http(socket)
    }

    protected fun request(
        server: ServeServer,
        target: String,
        method: String = "GET",
        range: String? = null,
        host: String? = server.authority,
        headers: List<String> = emptyList(),
    ): ByteArray = buildString {
        append("$method $target HTTP/1.1\r\n")
        host?.let { append("Host: $it\r\n") }
        range?.let { append("Range: $it\r\n") }
        headers.forEach { append(it).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    /** A request on a connection of its own and its whole answer: head and body to the connection's end. */
    protected fun exchange(server: ServeServer, request: ByteArray, from: String = "127.0.0.1"): Pair<Response, ByteArray> {
        val http = connect(server, from)
        http.send(request)
        val head = assertNotNull(http.head(), "no answer")
        return head to http.rest()
    }

    /** The answer's head alone, its body left unread on a connection that stays open: a client that doesn't read. */
    protected fun opened(server: ServeServer, target: String, range: String? = null, from: String = "127.0.0.1", receiveBuffer: Int? = 64 * 1024): Pair<Http, Response> {
        val http = connect(server, from, receiveBuffer)
        http.send(request(server, target, range = range))
        return http to assertNotNull(http.head(), "no answer")
    }

    /** The next event [matching], with when it came (nanoTime); others before it are passed over. */
    inline fun <reified T : ServeEvent> next(millis: Long = 5000, matching: (T) -> Boolean = { true }): Pair<Long, T> {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (true) {
            val left = until - System.nanoTime()
            val (at, event) = assertNotNull(events.poll(maxOf(left, 0), TimeUnit.NANOSECONDS), "no ${T::class.simpleName} within $millis ms")
            if (event is T && matching(event)) return at to event
        }
    }

    /** Every event so far, after a moment for those under way. */
    protected fun drained(settleMillis: Long = 300): List<ServeEvent> {
        Thread.sleep(settleMillis)
        return generateSequence { events.poll() }.map { it.second }.toList()
    }

    protected fun eventually(what: String, millis: Long = 5000, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(10)
        }
    }

    protected fun millisSince(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos)
}

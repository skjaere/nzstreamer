package io.skjaere.usenet.serve

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The spike's relay (item 6(b)): the receiver's connections come to it at [bind]:[port], and it hands each request to the
 * server under test ([target]: where it listens and its authority, which the request's Host is rewritten to), its answer
 * back byte for byte. It sees what the server's events leave out, and logs it per request: the method, the Range, the
 * status, the bytes, the peer, the Origin and the User-Agent, the file by its name ([names], from the path), never the
 * path. It can do what the server never does: a 416, and a cut by FIN or RST at a byte ([SpikeFaults]).
 *
 * Its own buffers sit between the two: a server's close reaches the receiver only once it has read what they hold. The
 * spike's direct server, without the relay, is for timing a close.
 */
class FaultRelay(
    private val bind: InetAddress,
    private val port: Int,
    private val target: () -> Pair<InetSocketAddress, String>,
    private val names: (String) -> String?,
    private val faults: SpikeFaults,
    private val log: (Map<String, Any?>) -> Unit,
) : Closeable {
    /** An answer under way: where it started and where it got to, for the progress lines. */
    private class Active(val file: String?, val start: Long, val peer: String) {
        val position = AtomicLong(start)
        @Volatile var logged = -1L
        @Volatile var loggedAt = 0L
    }

    private val server = ServerSocket()
    private val connections = AtomicInteger()
    private val active = ConcurrentHashMap<Int, Active>()
    @Volatile private var closed = false

    fun start(): Int {
        server.bind(InetSocketAddress(bind, port), 64)
        thread(isDaemon = true, name = "relay-accept") {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                val id = connections.incrementAndGet()
                thread(isDaemon = true, name = "relay-$id") { relay(id, client) }
            }
        }
        thread(isDaemon = true, name = "relay-progress") {
            // Each answer under way once a second when it moved, else every 30 s, as the spike's server did.
            while (!closed) {
                Thread.sleep(1000)
                val now = System.nanoTime()
                for ((id, a) in active) {
                    val at = a.position.get()
                    if (at == a.logged && now - a.loggedAt < 30_000_000_000L) continue
                    a.logged = at
                    a.loggedAt = now
                    log(mapOf("ev" to "progress", "conn" to id, "file" to a.file, "peer" to a.peer, "start" to a.start, "pos" to at))
                }
            }
        }
        return server.localPort
    }

    override fun close() {
        closed = true
        server.close()
    }

    private fun relay(id: Int, client: Socket) {
        val peer = "${client.inetAddress.hostAddress}:${client.port}"
        val started = System.nanoTime()
        val record = linkedMapOf<String, Any?>("ev" to "request", "conn" to id, "peer" to peer)
        var upstream: Socket? = null
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val lines = String(head, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val (method, path) = lines.first().split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            val headers = lines.drop(1).map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
            val file = names(path.substringBefore('?'))
            val range = header("Range")
            val start = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            record += mapOf(
                "method" to method, "file" to (file ?: "<not served>"), "range" to range, "start" to start, "origin" to header("Origin"),
                "ua" to header("User-Agent"), "connection" to header("Connection"),
                "other" to headers.filter { it.first.lowercase() !in setOf("range", "origin", "user-agent", "connection", "host") }.associate { it },
            )
            val armed = file?.let { faults[it] }
            if (method == "GET" && armed != null && answer416(client, file, start, armed, record)) return
            val (address, authority) = target()
            val server = Socket().also { upstream = it }
            server.connect(address, 5000)
            server.tcpNoDelay = true
            val rewritten = buildString {
                append(lines.first()).append("\r\n")
                for ((name, value) in headers) append(name).append(": ").append(if (name.equals("Host", ignoreCase = true)) authority else value).append("\r\n")
                append("\r\n")
            }
            server.getOutputStream().write(rewritten.toByteArray(Charsets.ISO_8859_1))
            val answer = BufferedInputStream(server.getInputStream())
            val answerHead = readHead(answer) ?: return record.set("how", "server closed before answering")
            val status = String(answerHead, Charsets.ISO_8859_1).substringAfter(' ').take(3).toIntOrNull()
            val from = Regex("""Content-Range: bytes (\d+)-""", RegexOption.IGNORE_CASE).find(String(answerHead, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toLong() ?: 0L
            record["status"] = status
            client.getOutputStream().write(answerHead)
            // The receiver hanging up, seen while the server has nothing: the server is to see it too.
            thread(isDaemon = true, name = "relay-$id-watch") {
                try {
                    client.soTimeout = 0
                    while (client.getInputStream().read() >= 0) Unit
                } catch (_: IOException) {
                    // Gone, or closed here.
                }
                server.close()
            }
            val a = Active(file, from, peer)
            active[id] = a
            try {
                record["how"] = pump(answer, client, a, armed, record)
            } finally {
                active.remove(id)
            }
            record["bytes"] = a.position.get() - from
            record["end"] = if (a.position.get() > from) a.position.get() - 1 else null
        } catch (_: IOException) {
            record.putIfAbsent("how", "client closed")
        } finally {
            upstream?.close()
            if (!client.isClosed) client.close()
            record["dur"] = (System.nanoTime() - started) / 1_000_000 / 1000.0
            if ("method" in record) log(record)
        }
    }

    /** The answer's body from [answer] to [client], cut where a fault says: how it ended. */
    private fun pump(answer: InputStream, client: Socket, active: Active, armed: SpikeFaults.FileFaults?, record: MutableMap<String, Any?>): String {
        val buffer = ByteArray(64 * 1024)
        val out = client.getOutputStream()
        while (true) {
            val n = try {
                answer.read(buffer)
            } catch (_: IOException) {
                return "client closed"
            }
            if (n < 0) return "complete"
            val at = active.position.get()
            // A cut armed inside this answer, past its first byte, within what came: forward up to it, then cut there.
            val cut = armed?.cuts?.firstOrNull { it.armed && it.offset > active.start && it.offset > at && it.offset <= at + n }
            val count = if (cut != null) (cut.offset - at).toInt() else n
            try {
                out.write(buffer, 0, count)
            } catch (_: IOException) {
                return "client closed"
            }
            active.position.addAndGet(count.toLong())
            if (cut != null && cut.take()) {
                faults.fired(active.file ?: "", if (cut.rst) "cut-rst" else "cut", cut.offset)
                record["fault"] = if (cut.rst) "cut-rst" else "cut"
                if (cut.rst) {
                    // A reset, no FIN: the linger of nothing.
                    client.setSoLinger(true, 0)
                } else {
                    client.shutdownOutput()
                }
                client.close()
                return if (cut.rst) "cut-rst" else "cut"
            }
        }
    }

    /** Answers a GET with a 416 when an armed fault says to: whether it did. */
    private fun answer416(client: Socket, file: String, start: Long, armed: SpikeFaults.FileFaults, record: MutableMap<String, Any?>): Boolean {
        val fault = armed.e416.firstOrNull { it.armed && start in it.minStart..it.maxStart } ?: return false
        if (!fault.take()) return false
        faults.fired(file, "416", start)
        val head = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
            "Content-Range: bytes */${fault.pretendSize ?: start}\r\n" +
            "Content-Length: 0\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
        client.shutdownOutput()
        record += mapOf("status" to 416, "fault" to "416", "how" to "complete", "bytes" to 0)
        return true
    }

    /** A request's or an answer's head, its empty line included; null when the connection ends before it does. */
    private fun readHead(input: InputStream): ByteArray? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return head.toByteArray()
        }
        return null
    }
}

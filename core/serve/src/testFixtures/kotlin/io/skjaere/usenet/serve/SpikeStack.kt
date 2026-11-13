package io.skjaere.usenet.serve

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Item 6(b)'s spike on the workstation: the media folder's files served by core:serve's server through [FaultRelay] at
 * [address]:[relayPort], and by a second server bound at [address]:[directPort] itself, with no relay, for timing its own
 * closes; both read the files through [source], under the faults the control arms: [FaultySource] reads them straight,
 * and the app's run puts its own adapter over it (ServedStreamSpikeTest). In core:serve's test fixtures, so the app's
 * tests reach it. The control, on
 * 127.0.0.1:[controlPort] alone, is the receiver spike's `server.py`'s, so its driver keeps its calls:
 * - `GET /ctl/info`: `port`, `direct_port`, `files` (name to size), `urls` and `direct` (name to the URL a LOAD takes, the
 *   relay's and the direct server's; each file has its own token, so the driver takes a file's URL from here), `test`,
 *   `faults`, `limits` and each file's `counters`;
 * - `POST /ctl/test {"test": name}`, `/ctl/fault {...}` ([SpikeFaults]), `/ctl/clear`, and `/ctl/idle`, taken and ignored:
 *   the server closes every connection after its answer;
 * - new: `POST /ctl/serve {"file": name}` serves it again under a new token (the old URL answers 404), `/ctl/revoke
 *   {"file": name}`, `/ctl/limits {...}` restarts both servers with these [ServeLimits] fields (the head guard's stall
 *   shrunk, say) and new tokens, `/ctl/stop` ends the run;
 * - `GET /sender.html`: [page], when given, as the spike served its sender page.
 *
 * Everything is logged as JSON lines to [out], files by name: never a token, a path or a URL.
 */
class SpikeStack(
    private val address: Inet4Address,
    media: File,
    private val relayPort: Int = 8600,
    private val controlPort: Int = 8601,
    private val directPort: Int = 8602,
    /** Who the direct server answers: the home network's private addresses, as in the app. */
    private val directPeers: (InetAddress) -> Boolean = ServeServer::isPrivateIpv4,
    /** The Cast device's address, which takes the direct server's pin; null for whoever asks first. */
    private val expected: InetAddress? = null,
    private val page: File? = null,
    private val out: (String) -> Unit = ::println,
    /** Each served file's source, from the file, its name and the armed faults: a fresh one each time a file is served. */
    private val source: (File, String, SpikeFaults) -> ByteSource = ::FaultySource,
) : AutoCloseable {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    @Volatile private var test = "none"
    private val faults = SpikeFaults(::log)
    private val files: Map<String, File> = media.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in TYPES }
        .sortedBy { it.name }.associateBy { it.name }
    private val stopped = CountDownLatch(1)

    /** Both servers and what each serves, swapped whole by `/ctl/limits`. */
    private inner class Servers(val limits: ServeLimits) {
        val relayed = HashMap<String, Served>()
        val served = HashMap<String, Served>()
        val inner = server(loopback, 0, limits, { it.isLoopbackAddress }, "relay")
        val direct = server(address, directPort, limits, directPeers, "direct")

        init {
            files.keys.forEach(::serve)
        }

        fun serve(name: String) = synchronized(this) {
            val file = files.getValue(name)
            val type = TYPES.getValue(file.extension.lowercase())
            relayed.put(name, inner.serve(ServedFile(file.length(), type, file.extension.lowercase(), source(file, name, faults)), loopback))?.revoke()
            served.put(name, direct.serve(ServedFile(file.length(), type, file.extension.lowercase(), source(file, name, faults)), expected))?.revoke()
        }

        fun revoke(name: String) = synchronized(this) {
            relayed.remove(name)?.revoke()
            served.remove(name)?.revoke()
        }

        fun nameOf(path: String): String? = synchronized(this) { relayed.entries.firstOrNull { it.value.path == path }?.key }

        fun close() {
            inner.close()
            direct.close()
        }

        private fun server(bind: Inet4Address, port: Int, limits: ServeLimits, allow: (InetAddress) -> Boolean, via: String): ServeServer {
            lateinit var names: () -> Map<Int, String>
            val server = ServeServer(bind, port, limits = limits, allowPeer = allow, onEvent = { event -> log(serverEvent(via, event, names())) })
            names = { synchronized(this) { (if (via == "relay") relayed else served).entries.associate { it.value.id to it.key } } }
            server.start()
            return server
        }
    }

    @Volatile private var servers = Servers(ServeLimits())
    private val relay = FaultRelay(address, relayPort, { servers.inner.let { InetSocketAddress(loopback, it.authority.substringAfterLast(':').toInt()) to it.authority } }, { servers.nameOf(it) }, faults, ::log)
    private val control = HttpServer.create(InetSocketAddress(loopback, controlPort), 16)
    /** Where the relay listens: [relayPort], or the one the system gave for 0. */
    private val relayBound = relay.start()

    init {
        control.createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        control.start()
        log(mapOf("ev" to "server_start", "relay_port" to relayBound, "direct_port" to directPort, "ctl_port" to control.address.port, "files" to files.mapValues { it.value.length() }))
    }

    /** Where the control listens: its port. */
    val controlAddress: InetSocketAddress get() = control.address

    /** Waits for `/ctl/stop`, at most [minutes]: whether it came. */
    fun awaitStop(minutes: Long): Boolean = stopped.await(minutes, TimeUnit.MINUTES)

    override fun close() {
        control.stop(0)
        relay.close()
        servers.close()
    }

    private fun log(record: Map<String, Any?>) {
        val line = linkedMapOf<String, Any?>("t" to System.currentTimeMillis() / 1000.0, "test" to test)
        line.putAll(record)
        out(Json.write(line))
    }

    private fun serverEvent(via: String, event: ServeEvent, names: Map<Int, String>): Map<String, Any?> = when (event) {
        is ServeEvent.Answered -> mapOf("ev" to "answered", "via" to via, "file" to event.file?.let(names::get), "method" to event.method?.name, "status" to event.status)
        is ServeEvent.Ended -> mapOf("ev" to "ended", "via" to via, "file" to names[event.file], "from" to event.from, "sent" to event.sent, "why" to event.why.name)
        is ServeEvent.Dropped -> mapOf("ev" to "dropped", "via" to via, "why" to event.why.name)
        is ServeEvent.Pinned -> mapOf("ev" to "pinned", "via" to via, "file" to names[event.file], "expected" to event.expected)
        ServeEvent.ListenerLost -> mapOf("ev" to "listener-lost", "via" to via)
    }

    private fun handle(exchange: HttpExchange) {
        val body = if (exchange.requestMethod == "POST") Json.parse(exchange.requestBody.readBytes().toString(Charsets.UTF_8).ifBlank { "{}" }) as? Map<*, *> ?: emptyMap<String, Any?>() else emptyMap()
        @Suppress("UNCHECKED_CAST")
        val spec = body as Map<String, Any?>
        fun name() = (spec["file"] as? String)?.takeIf { it in files } ?: throw IllegalArgumentException("no such file")
        val reply: Any? = try {
            when (exchange.requestMethod to exchange.requestURI.path) {
                "GET" to "/ctl/info" -> info()
                "GET" to "/sender.html" -> return page(exchange)
                "POST" to "/ctl/test" -> mapOf("ok" to true).also {
                    test = spec["test"] as? String ?: "none"
                    log(mapOf("ev" to "test", "name" to test))
                }
                "POST" to "/ctl/fault" -> mapOf("ok" to true).also { faults.arm(spec) }
                "POST" to "/ctl/clear" -> mapOf("ok" to true).also { faults.clear() }
                "POST" to "/ctl/idle" -> mapOf("ok" to true, "note" to "the server closes every connection after its answer")
                "POST" to "/ctl/serve" -> name().let { servers.serve(it); urls(it) }
                "POST" to "/ctl/revoke" -> mapOf("ok" to true).also { servers.revoke(name()) }
                "POST" to "/ctl/limits" -> limits(spec).let { info() }
                "POST" to "/ctl/stop" -> mapOf("ok" to true).also { stopped.countDown() }
                else -> return reply(exchange, 404, mapOf("error" to "not found"))
            }
        } catch (e: IllegalArgumentException) {
            return reply(exchange, 400, mapOf("error" to "$e"))
        }
        reply(exchange, 200, reply)
    }

    private fun urls(name: String) = synchronized(servers) {
        val s = servers
        mapOf("url" to "http://${address.hostAddress}:$relayBound${s.relayed.getValue(name).path}", "direct" to s.served.getValue(name).url)
    }

    private fun info(): Map<String, Any?> {
        val s = servers
        return mapOf(
            "port" to relayBound,
            "direct_port" to s.direct.authority.substringAfterLast(':').toInt(),
            "files" to files.mapValues { it.value.length() },
            "urls" to files.keys.associateWith { urls(it)["url"] },
            "direct" to files.keys.associateWith { urls(it)["direct"] },
            "test" to test,
            "faults" to faults.specs(),
            "limits" to s.limits.toString(),
            "counters" to synchronized(s) { files.keys.associateWith { mapOf("relay" to s.relayed[it]?.counters()?.toString(), "direct" to s.served[it]?.counters()?.toString()) } },
        )
    }

    /** Restarts both servers with [spec]'s limits, the others the design's: every file under a new token. */
    private fun limits(spec: Map<String, Any?>) {
        fun long(key: String, default: Long) = (spec[key] as? Number)?.toLong() ?: default
        val d = ServeLimits()
        val limits = d.copy(
            stallMillis = long("stallMillis", d.stallMillis), headStallMillis = long("headStallMillis", d.headStallMillis),
            headGuardBytes = long("headGuardBytes", d.headGuardBytes), parkMillis = long("parkMillis", d.parkMillis),
            upstreamWaitMillis = long("upstreamWaitMillis", d.upstreamWaitMillis), headMillis = long("headMillis", d.headMillis),
        )
        servers.close()
        servers = Servers(limits)
        log(mapOf("ev" to "limits", "limits" to limits.toString()))
    }

    private fun page(exchange: HttpExchange) {
        val file = page?.takeIf { it.isFile } ?: return reply(exchange, 404, mapOf("error" to "no page"))
        val bytes = file.readBytes()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun reply(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.write(body).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private companion object {
        /** The types the spike's server gave, by extension: the castable ones and two it refused. */
        val TYPES = mapOf(
            "mkv" to "video/x-matroska", "mp4" to "video/mp4", "m4v" to "video/x-m4v", "webm" to "video/webm", "mov" to "video/quicktime",
            "ts" to "video/mp2t", "avi" to "video/x-msvideo",
        )
    }
}

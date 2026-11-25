package io.skjaere.usenet.app.plugins.sportarr

import android.net.Uri
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback relay in front of the sports indexer (10.0.2.2:8094) that counts the searches the app asks of it
 * (`t=search`): saved as the indexer's address ([url]), every search of the app's passes through it, whether a plugin
 * asked for it or the host did on its behalf, and whatever the host's cache would have answered had it been asked
 * again. Each request is answered with the indexer's own answer, on a connection of its own.
 */
class IndexerCounter : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))

    /** The indexer's address, as the app saves it. */
    val url = "http://127.0.0.1:${server.localPort}"

    /** The searches asked so far. */
    val searches = AtomicInteger()

    init {
        thread(isDaemon = true, name = "indexer-counter") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "indexer-counter-request") { relay(socket) }
            }
        }
    }

    private fun relay(socket: Socket): Unit = socket.use { client ->
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        // The request's headers, read past: the indexer is asked afresh with its own.
        while (!input.readLine().isNullOrEmpty()) Unit
        if (Uri.parse(path).getQueryParameter("t") == "search") searches.incrementAndGet()
        val indexer = URL("http://${SportsFakes.HOST}:${SportsFakes.INDEXER_PORT}$path").openConnection() as HttpURLConnection
        indexer.connectTimeout = TIMEOUT_MS
        indexer.readTimeout = TIMEOUT_MS
        val (status, type, body) = try {
            val status = indexer.responseCode
            Triple(status, indexer.contentType ?: "text/xml", ((if (status < 400) indexer.inputStream else indexer.errorStream)?.use { it.readBytes() } ?: ByteArray(0)))
        } finally {
            indexer.disconnect()
        }
        val out = client.getOutputStream()
        out.write("HTTP/1.1 $status \r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    override fun close() = server.close()

    private companion object {
        const val BACKLOG = 16
        const val TIMEOUT_MS = 10_000
    }
}

package io.skjaere.usenet.app.plugins

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * An HTTP/1.1 server on this machine's loopback address, in the test's own process, standing for GitHub: each request it
 * is sent is kept ([requests]), and [answer] says what it sends back, every connection closed after its answer. Just
 * enough of HTTP for one GET at a time; never a real service.
 */
internal class LoopbackHttp(private val answer: (Request) -> Response) : AutoCloseable {
    /** A request's [path] and its headers, by lower-case name. */
    class Request(val path: String, val headers: Map<String, String>)

    /**
     * What to send: [status], [headers], and [body], with a Content-Length unless [lengthless] (read to the close). With
     * [stallAfter], only that many bytes of the body go at once; the rest waits until the server closes, as a line that
     * stopped half-way.
     */
    class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val lengthless: Boolean = false,
        val stallAfter: Int? = null,
    )

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val released = CountDownLatch(1)
    val port: Int = server.localPort
    val requests = CopyOnWriteArrayList<Request>()

    private val thread = Thread {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            socket.use(::serve)
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val requestLine = input.line() ?: return
            val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val request = Request(requestLine.split(' ')[1], headers)
            requests += request
            val response = answer(request)
            val head = buildString {
                append("HTTP/1.1 ${response.status} Answer\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (!response.lengthless) append("Content-Length: ${response.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                val first = response.stallAfter ?: response.body.size
                write(response.body, 0, first)
                flush()
                if (first < response.body.size) {
                    released.await()
                    write(response.body, first, response.body.size - first)
                    flush()
                }
            }
        } catch (e: IOException) {
            // The client went before the whole answer was sent, as one that refuses a body too long does.
        }
    }

    override fun close() {
        released.countDown()
        server.close()
        thread.join(5_000)
    }

    private companion object {
        /** One line of the request's head, without its CR LF; null at the end of the stream. */
        fun InputStream.line(): String? {
            val bytes = StringBuilder()
            while (true) {
                val b = read()
                if (b < 0) return bytes.takeIf { it.isNotEmpty() }?.toString()
                if (b == '\n'.code) return bytes.toString().removeSuffix("\r")
                bytes.append(b.toChar())
            }
        }
    }
}

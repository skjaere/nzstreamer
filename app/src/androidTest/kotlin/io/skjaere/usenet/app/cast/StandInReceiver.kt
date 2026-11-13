package io.skjaere.usenet.app.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in Cast receiver's own connections to the phone's server ([at] its LOAD's address), as the Shield's media loader
 * made them (a spike's T1-T3, T6, T8; as ChromiumLikeClientTest): each request on a new connection with its
 * headers; each answer read against [film], the file's bytes, byte for byte, where the test knows them (a debrid file's
 * only once the head is read: [knows]); how many were open at once at most ([mostOpen]), every status it was answered
 * with, and every answer's headers ([headers]), what a receiver sees of the phone. A device test's, in its process,
 * reaching the server at the emulator's own address; moved here from CastServeEndToEndDeviceTest for the debrid cast's
 * class (D2 item 4).
 */
class StandInReceiver(@Volatile private var film: ByteArray? = null) : Closeable {
    private lateinit var uri: URI
    private val open = CopyOnWriteArrayList<Socket>()
    val statuses = CopyOnWriteArrayList<Int>()

    /** Each answer's headers, by their names in lower case, in the order they came. */
    val headers = CopyOnWriteArrayList<Map<String, String>>()

    @Volatile var mostOpen = 0
        private set

    fun at(url: String) {
        uri = URI(url)
    }

    /** The file's bytes from now on, which each answer is read against. */
    fun knows(film: ByteArray) {
        this.film = film
    }

    /** One answer on [socket], from byte [from] on: its status and headers, and [got] so far of its body. */
    inner class Answer(val socket: Socket, val input: InputStream, private val from: Long, val status: Int, private val headers: Map<String, String>) {
        var got = 0L
            private set

        /** What it read, while the test doesn't know the file's bytes. */
        private val kept = ByteArrayOutputStream()

        fun header(name: String): String? = headers[name]

        /** The file's size, as the answer's Content-Range gives it. */
        val total: Long get() = checkNotNull(header("content-range")?.substringAfterLast('/')?.toLongOrNull()) { "no size in ${header("content-range")}" }

        /** [n] more bytes of the body, checked against the file where its bytes are known, else kept ([bytes]). */
        fun read(n: Long): Answer {
            val body = ByteArray(n.toInt())
            var have = 0
            while (have < body.size) {
                val read = input.read(body, have, body.size - have)
                if (read < 0) throw AssertionError("the body from $from ended after ${got + have} bytes")
                have += read
            }
            val at = (from + got).toInt()
            film?.let { assertArrayEquals("the bytes from ${from + got}", it.copyOfRange(at, at + have), body) } ?: kept.write(body, 0, have)
            got += have
            return this
        }

        /** The rest of the body, to the file's end: the server closes after it (Connection: close). */
        fun toEnd(): Answer {
            read(total - from - got)
            assertEquals("more than the file", -1, input.read())
            return this
        }

        /** What it read where the file's bytes weren't known. */
        fun bytes(): ByteArray = kept.toByteArray()

        /** Whether its body ends where it is, nothing more coming: an answer the server closed after its headers. */
        fun endsHere(): Boolean = try {
            input.read() < 0
        } catch (_: IOException) {
            true
        }
    }

    /**
     * `GET Range: bytes=[from]-` on a new connection, or [on] one whose answer was read to its end, with the Shield's
     * headers; null where nothing answers.
     */
    fun ask(from: Long, on: Answer? = null): Answer? {
        val socket = on?.socket ?: Socket().apply {
            connect(InetSocketAddress(uri.host, uri.port), 5_000)
            soTimeout = 20_000
            open += this
            mostOpen = maxOf(mostOpen, open.size)
        }
        try {
            socket.getOutputStream().write(
                ("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nRange: bytes=$from-\r\nAccept-Encoding: identity;q=1, *;q=0\r\n" +
                    "Accept: */*\r\nConnection: keep-alive\r\nAccept-Language: en-US\r\nUser-Agent: $SHIELD_AGENT\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
        } catch (_: IOException) {
            // A connection the server closed: the read says so.
        }
        val input = on?.input ?: BufferedInputStream(socket.getInputStream())
        val status = try {
            line(input).split(' ').getOrNull(1)?.toIntOrNull()
        } catch (_: IOException) {
            null
        }
        if (status == null) {
            close(socket)
            return null
        }
        val headers = generateSequence { line(input).takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        statuses += status
        this.headers += headers
        return Answer(socket, input, from, status, headers)
    }

    /** The receiver gives up on an answer it hasn't read to its end: a reset, as Chromium's close of such a socket is. */
    fun cut(answer: Answer) {
        answer.socket.setSoLinger(true, 0)
        close(answer.socket)
    }

    private fun close(socket: Socket) {
        runCatching { socket.close() }
        open -= socket
    }

    override fun close() {
        open.toList().forEach(::close)
    }

    /** A header line, without its CRLF. */
    private fun line(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(c.toChar())
        }
    }

    companion object {
        /** The Shield's receiver's User-Agent, as a spike recorded it. */
        const val SHIELD_AGENT =
            "Mozilla/5.0 (Linux; Android 11.0; Build/RQ1A.210105.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.0 Safari/537.36 CrKey/1.56.500000 DeviceType/AndroidTV"
    }
}

package io.skjaere.usenet.handoff

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** How big a request's head may be. The defaults are the design's: a server's own limits name these for its reader. */
data class HeadLimits(
    val requestLine: Int = 2048,
    /** The header lines together, their line ends and the empty line included. */
    val headerBytes: Int = 8192,
    val headers: Int = 64,
)

/**
 * Reads one request on [socket] against one [deadline], on [clock]: before each read from the socket its timeout is set to
 * the time left, and once none is left a read throws a [SocketTimeoutException], so a peer that drips its request a byte at
 * a time can't hold the connection past it. It reads 4 KiB at a time and buffers what comes, so a body that came in the
 * head's packet is read with [bytes], from this reader, never from the socket. One reader per request; it keeps nothing
 * of what it read for anyone but its caller.
 *
 * The setup page (TV-8) and Cast's media server (C1) read their requests with it. Each checks what it reads in its own
 * order: the setup page its method and target before any header, then its body from here.
 */
class RequestReader(private val socket: Socket, private val deadline: Long, private val clock: () -> Long) {
    private val input = socket.getInputStream()
    private val buffer = ByteArray(4096)
    private var pos = 0
    private var end = 0

    /** A line read from a request. */
    sealed interface Line {
        /** Its [bytes] without the line end, and how many bytes it [consumed] with it. */
        class Text(val bytes: ByteArray, val consumed: Int) : Line

        /** Longer than allowed: a request line's is the server's 414, a header's its 431. */
        data object TooLong : Line

        /** A CR not followed by LF. */
        data object Bad : Line
    }

    /** A request's header fields, or the status to refuse it with. */
    sealed interface Headers {
        /** The [fields] in order, their names lowercased and their values without the spaces around them. */
        class Read(val fields: List<Pair<String, String>>) : Headers {
            /** The values of every field named [name] (lowercase), in order. */
            fun values(name: String): List<String> = fields.filter { it.first == name }.map { it.second }
        }

        /** 431 for too many or too big; 400 for a line that isn't a header (folded, no name, a bare CR, a NUL). */
        class Refused(val status: Int) : Headers
    }

    /** The next byte without taking it, or -1 at the end. */
    fun peek(): Int = if (pos < end || fill()) buffer[pos].toInt() and 0xff else -1

    private fun read(): Int = peek().also { if (it >= 0) pos++ }

    private fun fill(): Boolean {
        val left = deadline - clock()
        if (left <= 0) throw SocketTimeoutException()
        socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val n = input.read(buffer)
        if (n <= 0) return false
        pos = 0
        end = n
        return true
    }

    /** A line to its LF (after an optional CR), at most [limit] bytes before the line end; null at the end of the stream. */
    fun line(limit: Int): Line? {
        val out = ByteArrayOutputStream()
        var consumed = 0
        while (true) {
            val b = read()
            if (b < 0) return null
            consumed++
            when (b) {
                '\n'.code -> return Line.Text(out.toByteArray(), consumed)
                '\r'.code -> {
                    if (read() != '\n'.code) return Line.Bad
                    return Line.Text(out.toByteArray(), consumed + 1)
                }
                else -> {
                    if (out.size() >= limit) return Line.TooLong
                    out.write(b)
                }
            }
        }
    }

    /** The header fields to the empty line, within [limits]; null when the peer hung up first. */
    fun headers(limits: HeadLimits): Headers? {
        val fields = mutableListOf<Pair<String, String>>()
        var used = 0
        while (true) {
            val line = when (val read = line(limits.headerBytes - used)) {
                null -> return null
                Line.TooLong -> return Headers.Refused(431)
                Line.Bad -> return Headers.Refused(400)
                is Line.Text -> read
            }
            used += line.consumed
            if (used > limits.headerBytes) return Headers.Refused(431)
            val bytes = line.bytes
            if (bytes.isEmpty()) return Headers.Read(fields)
            if (fields.size == limits.headers) return Headers.Refused(431)
            // A line folded onto the one before (obs-fold) is refused, as RFC 9112 allows.
            if (bytes[0] == SP || bytes[0] == HTAB) return Headers.Refused(400)
            val colon = bytes.indexOf(':'.code.toByte())
            if (colon <= 0 || (0 until colon).any { !isTokenChar(bytes[it].toInt().toChar()) }) return Headers.Refused(400)
            val value = String(bytes, colon + 1, bytes.size - colon - 1, Charsets.ISO_8859_1).trim(' ', '\t')
            if ('\u0000' in value) return Headers.Refused(400)
            fields += String(bytes, 0, colon, Charsets.ISO_8859_1).lowercase() to value
        }
    }

    /** The next [n] bytes, or null when the stream ends first. */
    fun bytes(n: Int): ByteArray? {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            if (pos == end && !fill()) return null
            val chunk = minOf(n - filled, end - pos)
            System.arraycopy(buffer, pos, out, filled, chunk)
            pos += chunk
            filled += chunk
        }
        return out
    }

    companion object {
        private const val SP = ' '.code.toByte()
        private const val HTAB = '\t'.code.toByte()

        /** A character of a method or a header's name: RFC 9110's tchar. */
        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

        /** A path's %XX escapes undone, strictly; null when one is broken or it holds anything but ASCII. */
        fun decodePath(path: String): String? {
            val out = StringBuilder(path.length)
            var i = 0
            while (i < path.length) {
                val c = path[i]
                if (c == '%') {
                    if (i + 2 >= path.length) return null
                    val high = Character.digit(path[i + 1], 16)
                    val low = Character.digit(path[i + 2], 16)
                    if (high < 0 || low < 0 || high >= 8) return null
                    out.append((high * 16 + low).toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

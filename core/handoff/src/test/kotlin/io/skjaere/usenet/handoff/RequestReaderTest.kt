package io.skjaere.usenet.handoff

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The request reader the setup page and Cast's media server share, over real sockets on loopback: each of its limits. */
class RequestReaderTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val listener = ServerSocket(0, 8, loopback)
    private val sockets = mutableListOf<Socket>()
    private val clock = { System.nanoTime() / 1_000_000 }

    @AfterEach
    fun closeAll() {
        sockets.forEach { it.close() }
        listener.close()
    }

    /** A connection: the peer's end, to write the request into, and a reader over the server's end with [millis] to read it. */
    private fun connection(millis: Long = 5_000): Pair<Socket, RequestReader> {
        val peer = Socket(loopback, listener.localPort).also { it.tcpNoDelay = true }
        val accepted = listener.accept()
        sockets += peer
        sockets += accepted
        return peer to RequestReader(accepted, clock() + millis, clock)
    }

    /** A reader over [request], sent whole and then the peer's side shut. */
    private fun reading(request: String): RequestReader {
        val (peer, reader) = connection()
        peer.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        peer.shutdownOutput()
        return reader
    }

    private fun text(line: RequestReader.Line?) = String(assertIs<RequestReader.Line.Text>(line).bytes, Charsets.ISO_8859_1)

    private fun fields(read: RequestReader.Headers?) = assertIs<RequestReader.Headers.Read>(read).fields

    private fun refused(read: RequestReader.Headers?) = assertIs<RequestReader.Headers.Refused>(read).status

    private val limits = HeadLimits()

    @Test
    fun `the limits are the design's`() {
        assertEquals(HeadLimits(requestLine = 2048, headerBytes = 8192, headers = 64), limits)
    }

    @Test
    fun `a request line over its limit is too long, the server's 414, and one at it is read`() {
        // "GET " and " HTTP/1.1" around the target: 13 bytes.
        val atLimit = "GET /" + "a".repeat(2048 - 13 - 1) + " HTTP/1.1"
        assertEquals(2048, atLimit.length)
        assertEquals(atLimit, text(reading("$atLimit\r\n").line(limits.requestLine)))
        assertEquals(RequestReader.Line.TooLong, reading("${atLimit}a\r\n").line(limits.requestLine))
    }

    @Test
    fun `header bytes over their limit and the 65th header are refused with 431`() {
        val hostLine = "Host: 127.0.0.1:8080\r\n"
        // The Host line, the big one ("X-Big: " and CRLF) and the empty line make 8192 bytes.
        val fits = "X-Big: " + "a".repeat(8192 - hostLine.length - 9 - 2)
        val read = fields(reading("$hostLine$fits\r\n\r\n").headers(limits))
        assertEquals(listOf("host" to "127.0.0.1:8080", "x-big" to fits.removePrefix("X-Big: ")), read)
        assertEquals(431, refused(reading("${hostLine}${fits}a\r\n\r\n").headers(limits)))
        val many = (0 until 63).joinToString("") { "X-H$it: v\r\n" }
        assertEquals(64, fields(reading("$hostLine$many\r\n").headers(limits)).size)
        assertEquals(431, refused(reading("${hostLine}${many}X-H63: v\r\n\r\n").headers(limits)))
        // A head that never ends is refused once it is too big, not read on.
        assertEquals(431, refused(reading(hostLine + "X-Big: " + "a".repeat(20_000)).headers(limits)))
    }

    @Test
    fun `lines that aren't a header are refused with 400`() {
        val bad = listOf(
            // obs-fold: a line folded onto the one before.
            "X-Fold: a\r\n b\r\n\r\n",
            "X-Fold: a\r\n\tb\r\n\r\n",
            // A bare CR.
            "X-Name: a\rb\r\n\r\n",
            "X-Name: a\r\r\n\r\n",
            // A NUL in a value.
            "X-Nul: a\u0000b\r\n\r\n",
            "No colon here\r\n\r\n",
            "X-Name : value\r\n\r\n",
            "Bad Name: value\r\n\r\n",
            ": no name\r\n\r\n",
        )
        for (head in bad) assertEquals(400, refused(reading(head).headers(limits)), head)
    }

    @Test
    fun `names are lowercased and values trimmed, bare LFs end lines, and every field is kept in order`() {
        val read = reading("Host: a\nRange:  bytes=0- \t\r\nX-Two: 1\r\nx-two: 2\r\n\r\n").headers(limits)
        assertEquals(listOf("host" to "a", "range" to "bytes=0-", "x-two" to "1", "x-two" to "2"), fields(read))
        assertEquals(listOf("1", "2"), assertIs<RequestReader.Headers.Read>(read).values("x-two"))
    }

    @Test
    fun `a peer that hangs up is the end of the stream, not a refusal`() {
        assertNull(reading("").line(limits.requestLine))
        assertNull(reading("GET / HTTP/1.1").line(limits.requestLine))
        assertNull(reading("Host: a\r\n").headers(limits))
        assertEquals(-1, reading("").peek())
    }

    @Test
    fun `a TLS ClientHello peeks as TLS, and peeking takes nothing`() {
        val (peer, reader) = connection()
        peer.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2f, 0x01) + ByteArray(32))
        assertEquals(0x16, reader.peek())
        assertEquals(0x16, reader.peek())
        assertContentEquals(byteArrayOf(0x16, 0x03, 0x01), reader.bytes(3))
    }

    @Test
    fun `a head split across reads is read whole`() {
        val (peer, reader) = connection()
        val out = peer.getOutputStream()
        val pieces = listOf("GE", "T /x HT", "TP/1.1\r", "\nHo", "st: a\r\nRange: by", "tes=5-\r\n", "\r", "\n")
        thread(isDaemon = true) {
            for (piece in pieces) {
                out.write(piece.toByteArray())
                out.flush()
                Thread.sleep(30)
            }
        }
        assertEquals("GET /x HTTP/1.1", text(reader.line(limits.requestLine)))
        assertEquals(listOf("host" to "a", "range" to "bytes=5-"), fields(reader.headers(limits)))
    }

    @Test
    fun `the deadline runs out mid-head, however slowly it drips`() {
        val (peer, reader) = connection(millis = 600)
        val out = peer.getOutputStream()
        out.write("GET /".toByteArray())
        // A byte every 100 ms: each read would be well within a per-read timeout of 600 ms.
        thread(isDaemon = true) {
            try {
                repeat(100) {
                    out.write('a'.code)
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                // Closed at the test's end.
            }
        }
        val started = System.nanoTime()
        assertFailsWith<SocketTimeoutException> { reader.line(limits.requestLine) }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsed in 500..2500, "ran out after $elapsed ms")
        // A reader whose deadline has passed reads no more, even what is there.
        assertFailsWith<SocketTimeoutException> { reader.bytes(1_000) }
    }

    @Test
    fun `a body that came in the head's packet is read whole by bytes`() {
        val (peer, reader) = connection()
        val body = ByteArray(3000) { (it % 251).toByte() }
        // One write: the head and the body reach the reader together, and its first read buffers both.
        peer.getOutputStream().write("POST /x HTTP/1.1\r\nHost: a\r\nContent-Length: 3000\r\n\r\n".toByteArray() + body)
        assertEquals("POST /x HTTP/1.1", text(reader.line(limits.requestLine)))
        assertEquals(listOf("host" to "a", "content-length" to "3000"), fields(reader.headers(limits)))
        assertContentEquals(body, reader.bytes(3000))
        // And a body longer than one buffer, partly in the head's packet and partly after.
        val (later, longReader) = connection()
        val long = ByteArray(10_000) { (it % 241).toByte() }
        later.getOutputStream().write("POST /x HTTP/1.1\r\n\r\n".toByteArray() + long.copyOf(2000))
        thread(isDaemon = true) {
            Thread.sleep(100)
            later.getOutputStream().write(long, 2000, 8000)
            later.shutdownOutput()
        }
        longReader.line(limits.requestLine)
        assertEquals(emptyList(), fields(longReader.headers(limits)))
        assertContentEquals(long, longReader.bytes(10_000))
        assertNull(longReader.bytes(1), "a byte after the end of the stream")
    }

    @Test
    fun `paths are decoded strictly, ASCII alone`() {
        assertEquals("/a b/c", RequestReader.decodePath("/a%20b/c"))
        assertEquals("/AZ", RequestReader.decodePath("/%41%5a"))
        for (broken in listOf("/%zz", "/%2", "/a%", "/%e2%82%ac", "/%80")) assertNull(RequestReader.decodePath(broken), broken)
    }

    @Test
    fun `token characters are RFC 9110's`() {
        for (c in "azAZ09!#$%&'*+-.^_`|~") assertTrue(RequestReader.isTokenChar(c), "$c")
        for (c in " \t\"(),/:;<=>?@[\\]{}\u0000\u007fé") assertFalse(RequestReader.isTokenChar(c), "$c")
    }
}

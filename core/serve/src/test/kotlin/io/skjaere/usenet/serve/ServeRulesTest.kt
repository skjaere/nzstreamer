package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who the server answers and how it refuses (L53, L55, L74): the Host, the path and its token, the pin, the head's limits. */
class ServeRulesTest : ServeTestBase() {
    private val size = 200_000L
    private val now = AtomicLong(5_000_000)
    private val source = PatternSource(size)
    private val server = server(clock = now::get)
    private val served = server.serve(ServedFile(size, "video/mp4", "mp4", source), loopback)

    /** A file too big for the kernel's buffers, so a client that doesn't read holds its answer open. */
    private val bigSize = 64L * 1024 * 1024
    private val bigSource = PatternSource(bigSize)

    private fun big(expected: InetAddress = loopback) = server.serve(ServedFile(bigSize, "video/mp4", "mp4", bigSource), expected)

    private fun status(request: ByteArray, from: String = "127.0.0.1") = exchange(server, request, from).first.status

    private fun get(target: String = served.path, from: String = "127.0.0.1", method: String = "GET", range: String? = "bytes=0-") =
        status(request(server, target, method, range), from)

    @Test
    fun `the path is the token and the container's extension, never the file's name`() {
        val path = served.path
        assertTrue(Regex("/[0-9a-hjkmnp-tv-z]{26}/v\\.mp4").matches(path), path)
        assertEquals("http://${server.authority}$path", served.url)
        assertEquals("127.0.0.1", server.authority.substringBefore(':'))
        // A new token for each file served, so a URL a receiver still holds is never another file's.
        val again = server.serve(ServedFile(size, "video/mp4", "mp4", source), loopback)
        assertTrue(again.path != path)
        assertFalse(served.toString().contains(path.split('/')[1]), served.toString())
    }

    @Test
    fun `a Host other than the server's own address is a 400`() {
        val port = server.port
        val wrong = listOf(
            request(server, served.path, host = null),
            request(server, served.path, host = "evil.example:$port"),
            request(server, served.path, host = "localhost:$port"),
            request(server, served.path, host = "127.0.0.1"),
            request(server, served.path, host = "127.0.0.1:${port + 1}"),
            request(server, served.path, headers = listOf("Host: 127.0.0.1:$port")),
            request(server, "http://127.0.0.1:$port${served.path}"),
        )
        for (r in wrong) assertEquals(400, status(r), String(r))
        assertEquals(0, served.counters().requests)
        assertEquals(206, get())
    }

    @Test
    fun `a wrong, misnamed or revoked path is a 404, counted, and locks nothing`() {
        val token = served.path.split('/')[1]
        val wrong = listOf(
            "/", "/favicon.ico", "/$token", "/$token/", "/$token/v.mkv", "/$token/x.mp4", "/$token/v.mp4/x", "/$token/v.MP4",
            "/" + "0".repeat(26) + "/v.mp4", "/%zz", "/$token/v%2emp4x",
        )
        repeat(3) { for (target in wrong) assertEquals(404, get(target), target) }
        val revoked = server.serve(ServedFile(size, "video/mp4", "mp4", source), loopback)
        assertEquals(206, get(revoked.path))
        revoked.revoke()
        assertEquals(404, get(revoked.path))
        assertEquals(wrong.size * 3 + 1, server.counters().notFound)
        // Nothing was locked by them: the right path is served as before.
        assertEquals(206, get())
        assertEquals(0, served.counters().refused)
    }

    @Test
    fun `HEAD and OPTIONS pass the same checks as GET, and never set the pin`() {
        val wrong = "/" + "0".repeat(26) + "/v.mp4"
        assertEquals(404, get(wrong, method = "HEAD"))
        assertEquals(404, get(wrong, method = "OPTIONS"))
        assertEquals(400, status(request(server, served.path, "HEAD", host = "evil.example")))
        assertEquals(400, status(request(server, served.path, "OPTIONS", host = "evil.example")))
        // From other peers while nothing holds the pin: answered, and the pin is still free.
        assertEquals(206, get(from = "127.0.0.2", method = "HEAD"))
        assertEquals(204, get(from = "127.0.0.2", method = "OPTIONS"))
        assertEquals(206, get(from = "127.0.0.3"))
        // Now 127.0.0.3's: another peer's HEAD and OPTIONS are refused as its GET would be.
        assertEquals(403, get(from = "127.0.0.2", method = "HEAD"))
        assertEquals(403, get(from = "127.0.0.2", method = "OPTIONS"))
        assertEquals(403, get(from = "127.0.0.2"))
    }

    @Test
    fun `another peer that asks first is served, until the expected device asks and takes the pin, and the other's connection closes`() {
        val file = big()
        val (first, head) = opened(server, file.path, "bytes=0-", from = "127.0.0.2")
        assertEquals(206, head.status)
        assertEquals(1, file.counters().mismatches)
        val (_, pinned) = next<ServeEvent.Pinned>()
        assertEquals(ServeEvent.Pinned(file.id, expected = false), pinned)
        assertEquals(206, get(file.path, from = "127.0.0.2", range = "bytes=67000000-"))
        // The expected device's GET.
        val (_, own) = opened(server, file.path, "bytes=0-")
        assertEquals(206, own.status)
        assertEquals(ServeEvent.Pinned(file.id, expected = true), next<ServeEvent.Pinned>().second)
        assertTrue(first.ended(), "the other peer's connection is still open")
        assertEquals(ServeEnd.Unpinned, next<ServeEvent.Ended> { it.why != ServeEnd.Done }.second.why)
        assertEquals(403, get(file.path, from = "127.0.0.2"))
        val counters = file.counters()
        assertEquals(1, counters.mismatches)
        assertEquals(1, counters.unpinned)
        assertEquals(1, counters.refused)
    }

    @Test
    fun `another peer keeps the pin while the expected device never asks, counted as a mismatch`() {
        val elsewhere = server.serve(ServedFile(size, "video/mp4", "mp4", source), InetAddress.getByName("127.0.0.9"))
        assertEquals(206, get(elsewhere.path, from = "127.0.0.2"))
        assertEquals(206, get(elsewhere.path, from = "127.0.0.2", range = "bytes=5000-"))
        assertEquals(403, get(elsewhere.path, from = "127.0.0.3"))
        assertEquals(403, get(elsewhere.path))
        val counters = elsewhere.counters()
        assertEquals(1, counters.mismatches)
        assertEquals(2, counters.refused)
        assertEquals(2, counters.requests)
    }

    @Test
    fun `revoke answers 404 at once and closes the file's open connections`() {
        val file = big()
        val (open, head) = opened(server, file.path, "bytes=0-")
        assertEquals(206, head.status)
        val other = big()
        opened(server, other.path, "bytes=0-")
        eventually("the sources weren't opened") { bigSource.open.get() == 2 }
        val started = System.nanoTime()
        file.revoke()
        assertEquals(404, get(file.path))
        assertEquals(404, get(file.path, method = "HEAD"))
        assertTrue(open.ended(2000), "a revoked file's connection is still open")
        assertEquals(ServeEnd.Revoked, next<ServeEvent.Ended> { it.file == file.id }.second.why)
        // Its upstream closed with it, at once; the other file's goes on.
        eventually("the revoked file's upstream is still open", 1000) { bigSource.open.get() == 1 }
        assertTrue(millisSince(started) < 1000)
        assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Ended>().filter { it.file == other.id })
    }

    /**
     * L74: when the phone's address goes, Android destroys the sockets bound to it, the listening one among them. The server
     * keeps every file under its token and says so; once the address is back it listens again on the same port, and the
     * URL the device holds answers as before. A port another socket holds meanwhile fails that try, which can be made again.
     */
    @Test
    fun `a lost listening socket keeps every file served, and listening again on the same port answers the same URL`() {
        val port = server.port
        // Answered and closed by the server before the loss: its side of that connection waits out TIME_WAIT on the port.
        assertEquals(206, get())
        val file = big()
        val (open, head) = opened(server, file.path, "bytes=0-")
        assertEquals(206, head.status)
        assertNotNull(server.listening).close()
        next<ServeEvent.ListenerLost>()
        // Nothing revoked, nothing closed: the answer under way goes on.
        assertContentEquals(expected(0, 999_999), open.read(1_000_000))
        assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Ended>())

        ServerSocket().use { other ->
            other.bind(InetSocketAddress(loopback, port))
            assertFailsWith<IOException> { server.relisten() }
        }
        assertEquals(port, server.relisten())
        assertEquals("127.0.0.1:$port", server.authority)
        val (again, body) = exchange(server, request(server, served.path, range = "bytes=5000-"))
        assertEquals(206, again.status)
        assertContentEquals(expected(5000, size - 1), body)
        assertEquals(206, opened(server, file.path, "bytes=1000000-").second.status)

        // Closing ends the new listener too, and there is no listening again after it. The JDK closes a listening socket
        // for good once its thread, waiting in accept(), wakes: till then the port can still take a handshake, which
        // nothing answers.
        server.close()
        eventually("the new listener still takes connections after close", 2000) {
            val late = try {
                connect(server)
            } catch (_: IOException) {
                return@eventually true
            }
            val answer = try {
                late.send(request(server, served.path))
                late.head()
            } catch (_: IOException) {
                null
            }
            assertNull(answer, "an answer after close")
            false
        }
        assertFailsWith<IllegalStateException> { server.relisten() }
    }

    @Test
    fun `a public peer is closed unanswered, and private ones are the default's`() {
        val public = server(allowPeer = ServeServer.Companion::isPrivateIpv4)
        val file = public.serve(ServedFile(size, "video/mp4", "mp4", source), loopback)
        val http = connect(public)
        http.send(request(public, file.path))
        assertNull(http.head())
        assertEquals(ServeDrop.NotAllowed, next<ServeEvent.Dropped>().second.why)
        for (ip in listOf("10.0.2.16", "172.16.0.1", "192.168.1.42")) assertTrue(ServeServer.isPrivateIpv4(InetAddress.getByName(ip)), ip)
        for (ip in listOf("127.0.0.1", "169.254.1.1", "100.64.0.1", "8.8.8.8", "::1", "fd00::1")) assertFalse(ServeServer.isPrivateIpv4(InetAddress.getByName(ip)), ip)
    }

    @Test
    fun `a TLS ClientHello is closed unanswered, at once`() {
        val hello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2f, 0x01, 0x00, 0x00, 0x2b, 0x03, 0x03) + ByteArray(32)
        val started = System.nanoTime()
        val http = connect(server)
        http.send(hello)
        assertNull(http.head())
        assertTrue(millisSince(started) < 1000, "not closed at once")
        assertEquals(ServeDrop.Tls, next<ServeEvent.Dropped>().second.why)
    }

    @Test
    fun `a request line over 2 KB is a 414, and headers over 8 KB or 64 of them a 431`() {
        // "GET " and " HTTP/1.1" around the target: 13 bytes.
        assertEquals(404, status(request(server, "/" + "a".repeat(2048 - 13 - 1))))
        assertEquals(414, status(request(server, "/" + "a".repeat(2048 - 13))))
        assertEquals(431, status(request(server, served.path, headers = listOf("X-Big: " + "a".repeat(9000)))))
        assertEquals(206, status(request(server, served.path, range = "bytes=0-", headers = List(62) { "X-H$it: v" })))
        assertEquals(431, status(request(server, served.path, range = "bytes=0-", headers = List(63) { "X-H$it: v" })))
    }

    @Test
    fun `a request with a body is a 400`() {
        val head = "GET ${served.path} HTTP/1.1\r\nHost: ${server.authority}\r\n"
        fun raw(headers: String, body: String = "") = status("$head$headers\r\n$body".toByteArray())
        assertEquals(400, raw("Content-Length: 5\r\n", "hello"))
        assertEquals(400, raw("Transfer-Encoding: chunked\r\n", "0\r\n\r\n"))
        assertEquals(400, raw("Content-Length: x\r\n"))
        assertEquals(400, raw("Content-Length: 0\r\nContent-Length: 0\r\n"))
        // An empty body is no body.
        assertEquals(200, raw("Content-Length: 0\r\n"))
    }

    @Test
    fun `methods other than GET, HEAD and OPTIONS are a 405 that says which`() {
        for (method in listOf("PUT", "POST", "DELETE", "PATCH", "TRACE", "CONNECT", "get")) {
            val (answer, body) = exchange(server, request(server, served.path, method))
            assertEquals(405, answer.status, method)
            assertEquals(listOf("GET, HEAD, OPTIONS"), answer.header("Allow"), method)
            assertEquals(0, body.size, method)
        }
        assertEquals(emptyList(), source.opens)
    }

    @Test
    fun `malformed requests are a 400`() {
        val host = "Host: ${server.authority}"
        val bad = listOf(
            "GARBAGE\r\n\r\n",
            "GET ${served.path} HTTP/2.0\r\n$host\r\n\r\n",
            "GET  ${served.path} HTTP/1.1\r\n$host\r\n\r\n",
            "GET ${served.path}\r\n$host\r\n\r\n",
            "GET * HTTP/1.1\r\n$host\r\n\r\n",
            "GET /a\u0001b HTTP/1.1\r\n$host\r\n\r\n",
            "GET ${served.path} HTTP/1.1\r$host\r\n\r\n",
            "GET ${served.path} HTTP/1.1\r\n$host\r\nX-Fold: a\r\n b\r\n\r\n",
            "GET ${served.path} HTTP/1.1\r\n$host\r\nBad Name: v\r\n\r\n",
        )
        for (r in bad) assertEquals(400, status(r.toByteArray(Charsets.ISO_8859_1)), r)
        assertEquals(emptyList(), source.opens)
    }

    /** A 503 ends a Cast device's media at once, never retried (item 6(a)): a failure after the headers is a close instead. */
    @Test
    fun `no 5xx for a source that throws at open or mid-body, only the headers and a close`() {
        source.failOpen = true
        val (opened, body) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, opened.status)
        assertEquals(size, opened.length)
        assertEquals(0, body.size)
        assertEquals(ServeEnd.UpstreamFailed, next<ServeEvent.Ended>().second.why)
        source.failOpen = false
        source.failAt = 70_000
        val (midway, part) = exchange(server, request(server, served.path, range = "bytes=1000-"))
        assertEquals(206, midway.status)
        assertContentEquals(expected(1000, 69_999), part)
        val ended = next<ServeEvent.Ended>().second
        assertEquals(ServeEvent.Ended(served.id, from = 1000, sent = 69_000, why = ServeEnd.UpstreamFailed), ended)
        assertEquals(2, served.counters().upstreamFailures)
        assertEquals(1, served.counters().headFailures, "only the answer from byte 0's is the head's")
        assertEquals(emptyList(), drained().filterIsInstance<ServeEvent.Answered>().filter { it.status >= 500 })
        eventually("an upstream was left open") { source.open.get() == 0 }
    }

    @Test
    fun `the counters say what was asked and sent, and when the first request came`() {
        assertNull(served.counters().firstRequestAt)
        assertEquals(400, status(request(server, served.path, host = "evil.example")))
        assertNull(served.counters().firstRequestAt, "a refused request counted")
        val at = now.get()
        assertEquals(206, get(range = "bytes=100-"))
        now.addAndGet(1234)
        assertEquals(200, get(range = null))
        assertEquals(206, get(method = "HEAD"))
        val counters = served.counters()
        assertEquals(at, counters.firstRequestAt)
        assertEquals(3, counters.requests)
        assertEquals(1, counters.partials)
        assertEquals(size - 100 + size, counters.bytesSent)
        assertEquals(0, counters.upstreamFailures + counters.mismatches + counters.evictions + counters.stalls + counters.refused)
        val answered = drained().filterIsInstance<ServeEvent.Answered>()
        assertEquals(
            listOf(
                ServeEvent.Answered(null, ServeMethod.Get, 400), ServeEvent.Answered(served.id, ServeMethod.Get, 206),
                ServeEvent.Answered(served.id, ServeMethod.Get, 200), ServeEvent.Answered(served.id, ServeMethod.Head, 206),
            ),
            answered,
        )
        assertNotNull(served.counters().firstRequestAt)
    }
}

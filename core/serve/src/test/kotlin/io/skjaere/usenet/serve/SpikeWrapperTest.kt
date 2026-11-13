package io.skjaere.usenet.serve

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Item 6(b)'s serving side, [SpikeStack], on 127.0.0.1 alone, so that the Shield's session spends no time on a fault
 * that doesn't fire: the relay hands requests to the server and logs them by file name with their peer, Origin and
 * User-Agent, never a token; each fault the control arms acts once, at its byte (a FIN, an RST, a 416, zeros counted as
 * a gap, a stall, a failure the server closes on, latency); the control serves a file again, revokes it, restarts the
 * servers with new limits and stops the run. Nothing here reaches the home network.
 */
class SpikeWrapperTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val size = 2L * 1024 * 1024
    private val lines = CopyOnWriteArrayList<String>()
    private lateinit var stack: SpikeStack
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun start(folder: File) {
        File(folder, "b.mkv").writeBytes(expected(0, size - 1))
        File(folder, "a.mp4").writeBytes(expected(0, 1024L * 1024 - 1))
        stack = SpikeStack(loopback, folder, relayPort = 0, controlPort = 0, directPort = 0, directPeers = { it.isLoopbackAddress }, out = { lines += it })
    }

    @AfterEach
    fun stop() {
        if (::stack.isInitialized) stack.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ctl(path: String, body: Map<String, Any?>? = null): Map<String, Any?> {
        val uri = URI("http://127.0.0.1:${stack.controlAddress.port}$path")
        val request = if (body == null) HttpRequest.newBuilder(uri).GET().build() else HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return Json.parse(response.body()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun url(file: String, kind: String = "urls") = (ctl("/ctl/info")[kind] as Map<String, String>).getValue(file)

    /** An answer's head, its body as far as it came, and how it ended: "eof", "reset" or "timeout". */
    private class Got(val head: Response, val body: ByteArray, val end: String, val millis: Long)

    private fun get(url: String, range: String? = "bytes=0-", headers: List<String> = emptyList()): Got {
        val uri = URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 2000)
            socket.soTimeout = 10_000
            val started = System.nanoTime()
            val request = buildString {
                append("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n")
                range?.let { append("Range: $it\r\n") }
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            val input = BufferedInputStream(socket.getInputStream())
            val raw = ByteArrayOutputStream()
            while (!raw.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                val b = input.read()
                assertTrue(b >= 0, "no answer")
                raw.write(b)
            }
            val lines = raw.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n").split("\r\n")
            val head = Response(lines.first().split(' ')[1].toInt(), lines.drop(1).map { it.substringBefore(": ") to it.substringAfter(": ") })
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            val end = try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    body.write(buffer, 0, n)
                }
                "eof"
            } catch (_: SocketTimeoutException) {
                "timeout"
            } catch (_: IOException) {
                "reset"
            }
            return Got(head, body.toByteArray(), end, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        }
    }

    private fun records(kind: String) = lines.map { Json.parse(it) as Map<*, *> }.filter { it["ev"] == kind }

    @Test
    fun `the relay hands a request to the server and logs it by file name, with its peer, Origin and User-Agent`(@TempDir folder: File) {
        start(folder)
        val relayed = url("b.mkv")
        val got = get(relayed, "bytes=1000-", listOf("Origin: https://receiver.example", "User-Agent: CrKey-like/1.0"))
        assertEquals(206, got.head.status)
        assertEquals(listOf("bytes 1000-${size - 1}/$size"), got.head.header("Content-Range"))
        assertContentEquals(expected(1000, size - 1), got.body)
        Thread.sleep(300)
        val request = records("request").single()
        assertEquals("GET", request["method"])
        assertEquals("b.mkv", request["file"])
        assertEquals("bytes=1000-", request["range"])
        assertEquals(206.0, request["status"])
        assertEquals((size - 1000).toDouble(), request["bytes"])
        assertEquals("https://receiver.example", request["origin"])
        assertEquals("CrKey-like/1.0", request["ua"])
        assertTrue((request["peer"] as String).startsWith("127.0.0.1:"))
        assertEquals("complete", request["how"])
        assertTrue(records("ended").any { it["via"] == "relay" && it["file"] == "b.mkv" && it["why"] == "Done" })
        // The direct server answers the same, on its own port.
        val direct = get(url("b.mkv", "direct"), "bytes=1000-")
        assertContentEquals(expected(1000, size - 1), direct.body)
        // No token, path or URL in any line.
        val token = URI(relayed).rawPath.split('/')[1]
        assertEquals(emptyList(), lines.filter { token in it || "http://" in it })
    }

    @Test
    fun `a cut by FIN ends the answer at its byte, and one by RST resets it, each once`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "cut" to listOf(mapOf("offset" to 300_000))))
        val fin = get(url("b.mkv"))
        assertEquals(206, fin.head.status)
        assertContentEquals(expected(0, 299_999), fin.body)
        assertEquals("eof", fin.end)
        // Fired once: the next answer is whole.
        assertEquals(size, get(url("b.mkv")).body.size.toLong())
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "cut" to listOf(mapOf("offset" to 500_000, "rst" to true))))
        val rst = get(url("b.mkv"))
        assertTrue(rst.body.size <= 500_000, "${rst.body.size} bytes came past an RST at 500,000")
        assertEquals("reset", rst.end)
        Thread.sleep(300)
        assertEquals(listOf("cut", "complete", "cut-rst"), records("request").map { it["how"] })
    }

    @Test
    fun `an armed 416 answers a GET from inside its range, once, with the CORS headers`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "e416" to listOf(mapOf("min_start" to 1_000_000, "max_start" to 1_500_000, "pretend_size" to 1_200_000))))
        assertEquals(206, get(url("b.mkv"), "bytes=500000-").head.status)
        val refused = get(url("b.mkv"), "bytes=1048576-")
        assertEquals(416, refused.head.status)
        assertEquals(listOf("bytes */1200000"), refused.head.header("Content-Range"))
        assertEquals(listOf("*"), refused.head.header("Access-Control-Allow-Origin"))
        assertEquals(0, refused.body.size)
        assertEquals(206, get(url("b.mkv"), "bytes=1048576-").head.status)
    }

    @Test
    fun `zeros come as zeros and count once as the server's gap`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "zero" to listOf(listOf(100_000, 200_000))))
        val got = get(url("b.mkv"))
        assertContentEquals(expected(0, size - 1, listOf(100_000L..199_999L)), got.body)
        @Suppress("UNCHECKED_CAST")
        val counters = (ctl("/ctl/info")["counters"] as Map<String, Map<String, String>>).getValue("b.mkv").getValue("relay")
        assertTrue("gaps=1," in counters, counters)
    }

    @Test
    fun `a stall holds the bytes at its offset for its time, then they come`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "stall" to listOf(mapOf("offset" to 400_000, "seconds" to 0.8))))
        val got = get(url("b.mkv"))
        assertContentEquals(expected(0, size - 1), got.body)
        assertTrue(got.millis >= 700, "the answer took ${got.millis} ms through a 0.8 s stall")
        Thread.sleep(300)
        assertEquals(1, records("fault_fired").count { it["kind"] == "stall" && it["at"] == 400_000.0 })
    }

    /** L76 through the wrapper: the source fails at a byte, the server closes there, and a retry within 5 s opens nothing. */
    @Test
    fun `a failure at a byte is the server's close there, and a retry within 5 s is refused`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "fail" to listOf(mapOf("offset" to 700_000, "count" to 5))))
        val first = get(url("b.mkv"))
        assertContentEquals(expected(0, 699_999), first.body)
        assertEquals("eof", first.end)
        repeat(2) {
            val again = get(url("b.mkv"), "bytes=700000-")
            assertEquals(206, again.head.status)
            assertEquals(0, again.body.size)
        }
        Thread.sleep(300)
        // The first two reached the source; the second retry didn't.
        assertEquals(2, records("fault_fired").count { it["kind"] == "fail" })
        assertEquals(3, records("ended").count { it["via"] == "relay" && it["why"] == "UpstreamFailed" })
    }

    @Test
    fun `latency holds each part`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "a.mp4", "latency" to mapOf("part" to 262_144, "ms" to 300)))
        val got = get(url("a.mp4"))
        assertContentEquals(expected(0, 1024L * 1024 - 1), got.body)
        assertTrue(got.millis >= 850, "four parts came in ${got.millis} ms")
    }

    @Test
    fun `the control serves a file again, revokes it, restarts with new limits, and stops`(@TempDir folder: File) {
        start(folder)
        val first = url("a.mp4")
        ctl("/ctl/test", mapOf("test" to "R9"))
        val again = ctl("/ctl/serve", mapOf("file" to "a.mp4"))["url"] as String
        assertNotEquals(first, again)
        assertEquals(404, get(first).head.status)
        assertEquals(206, get(again).head.status)
        ctl("/ctl/revoke", mapOf("file" to "a.mp4"))
        assertEquals(404, get(again).head.status)
        ctl("/ctl/serve", mapOf("file" to "a.mp4"))
        val info = ctl("/ctl/limits", mapOf("stallMillis" to 1234, "headStallMillis" to 2345))
        assertTrue("stallMillis=1234" in info["limits"] as String && "headStallMillis=2345" in info["limits"] as String, "${info["limits"]}")
        assertEquals(206, get(url("a.mp4")).head.status)
        assertEquals(206, get(url("a.mp4", "direct")).head.status)
        assertEquals(true, ctl("/ctl/idle", mapOf("seconds" to 5))["ok"])
        ctl("/ctl/fault", mapOf("file" to "a.mp4", "cut" to listOf(mapOf("offset" to 10))))
        ctl("/ctl/clear", emptyMap())
        assertEquals(emptyMap<String, Any?>(), ctl("/ctl/info")["faults"])
        Thread.sleep(300)
        assertTrue(records("request").isNotEmpty() && records("request").all { it["test"] == "R9" })
        assertTrue(!stack.awaitStop(0))
        ctl("/ctl/stop", emptyMap())
        assertTrue(stack.awaitStop(0))
    }
}

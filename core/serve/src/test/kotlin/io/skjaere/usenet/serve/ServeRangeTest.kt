package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the server answers each request for a served file (L53): statuses, ranges, lengths and headers, over real sockets. */
class ServeRangeTest : ServeTestBase() {
    private val size = 10_000L
    private val source = PatternSource(size)
    private val server = server()
    private val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source), loopback)

    /** What every answer carries, errors included, last. */
    private val always = listOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
        "Cache-Control" to "no-store",
        "Connection" to "close",
    )

    private fun get(range: String? = null, method: String = "GET", headers: List<String> = emptyList(), target: String = served.path) =
        exchange(server, request(server, target, method, range, headers = headers))

    @Test
    fun `no Range is the whole file, a 200`() {
        val (head, body) = get()
        assertEquals(200, head.status)
        assertEquals(listOf("Content-Type" to "video/x-matroska", "Content-Length" to "10000", "Accept-Ranges" to "bytes") + always, head.headers)
        assertContentEquals(expected(0, size - 1), body)
    }

    @Test
    fun `each range form gets a 206 from the byte asked, its Content-Range and its exact Content-Length`() {
        val forms = listOf(
            "bytes=0-" to 0L..9_999L,
            "bytes=100-" to 100L..9_999L,
            "bytes=9999-" to 9_999L..9_999L,
            "bytes=100-199" to 100L..199L,
            "bytes=0-0" to 0L..0L,
            // The end clamped to the file's last byte.
            "bytes=100-99999" to 100L..9_999L,
            "bytes=5000-9999" to 5_000L..9_999L,
            "bytes=-500" to 9_500L..9_999L,
            "bytes=-1" to 9_999L..9_999L,
            // A suffix as long as the file or longer: the whole file, as a 206.
            "bytes=-10000" to 0L..9_999L,
            "bytes=-20000" to 0L..9_999L,
            // The unit in any case, as RFC 9110 has it.
            "Bytes=4096-" to 4_096L..9_999L,
        )
        for ((range, bytes) in forms) {
            val (head, body) = get(range)
            assertEquals(206, head.status, range)
            val length = bytes.last - bytes.first + 1
            assertEquals(
                listOf(
                    "Content-Type" to "video/x-matroska", "Content-Length" to "$length",
                    "Content-Range" to "bytes ${bytes.first}-${bytes.last}/10000", "Accept-Ranges" to "bytes",
                ) + always,
                head.headers,
                range,
            )
            assertContentEquals(expected(bytes.first, bytes.last), body, range)
        }
    }

    @Test
    fun `a range that starts at or past the end, or a suffix of nothing, is a 416 with the size`() {
        for (range in listOf("bytes=10000-", "bytes=10000-10005", "bytes=20000-30000", "bytes=-0")) {
            val (head, body) = get(range)
            assertEquals(416, head.status, range)
            assertEquals(listOf("Content-Range" to "bytes */10000", "Accept-Ranges" to "bytes", "Content-Length" to "0") + always, head.headers, range)
            assertEquals(0, body.size, range)
        }
        assertEquals(emptyList(), source.opens, "a 416 opened the source")
    }

    /** RFC 9110 lets a server ignore a Range it won't serve: the whole file then, as no media client sends these. */
    @Test
    fun `several ranges, a malformed one or an If-Range are ignored, and the whole file is a 200`() {
        val ignored = listOf(
            "bytes=0-1,5-6", "bytes=0-1, 5-", "bytes=abc", "bytes=5-2", "items=0-1", "bytes=", "bytes=-", "bytes=1-2-3", "bytes 0-1",
            "bytes=99999999999999999999-", "bytes=-99999999999999999999", "bytes=+5-", "bytes= 5-", "bytes=0x10-",
        )
        for (range in ignored) {
            val (head, body) = get(range)
            assertEquals(200, head.status, range)
            assertEquals(listOf("10000"), head.header("Content-Length"), range)
            assertEquals(size, body.size.toLong(), range)
        }
        val (ifRange, _) = get("bytes=5-", headers = listOf("If-Range: \"abc\""))
        assertEquals(200, ifRange.status)
        val twice = exchange(server, request(server, served.path, headers = listOf("Range: bytes=5-", "Range: bytes=6-"))).first
        assertEquals(200, twice.status)
    }

    @Test
    fun `every answer gives the file's same total length`() {
        val totals = listOf(null, "bytes=0-", "bytes=4096-", "bytes=1-2", "bytes=-100", "bytes=10000-", "bytes=a").map { range ->
            val head = get(range).first
            head.header("Content-Range").singleOrNull()?.substringAfter('/')?.toLong() ?: head.length
        }
        assertEquals(List(totals.size) { size }, totals)
    }

    @Test
    fun `HEAD has GET's headers and no body, and opens nothing`() {
        for (range in listOf(null, "bytes=100-", "bytes=-0", "bytes=1,2")) {
            val getHead = get(range).first
            val (head, body) = get(range, method = "HEAD")
            assertEquals(getHead.status, head.status, "$range")
            assertEquals(getHead.headers, head.headers, "$range")
            assertEquals(0, body.size, "$range")
        }
        // Only the GETs opened the file: HEAD never does.
        assertEquals(listOf(0L, 100L, 0L), source.opens)
    }

    @Test
    fun `OPTIONS is a 204 that says what may be asked`() {
        val (head, body) = get(method = "OPTIONS", headers = listOf("Origin: https://receiver.example", "Access-Control-Request-Method: GET"))
        assertEquals(204, head.status)
        assertEquals(
            listOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range, Content-Type, Accept-Encoding",
                "Access-Control-Max-Age" to "600",
            ) + always,
            head.headers,
        )
        assertEquals(0, body.size)
        assertEquals(emptyList(), source.opens)
    }

    @Test
    fun `every answer, the errors too, carries CORS, no-store and close`() {
        val wrong = "/" + "0".repeat(26) + "/v.mkv"
        val answers = mapOf(
            "200" to get(),
            "206" to get("bytes=5-"),
            "416" to get("bytes=-0"),
            "204" to get(method = "OPTIONS"),
            "HEAD" to get(method = "HEAD"),
            "404" to get(target = wrong),
            "400" to exchange(server, request(server, served.path, host = "evil.example")),
            "405" to get(method = "PUT"),
            // Another peer than the one that pinned the file with the first GET.
            "403" to exchange(server, request(server, served.path), from = "127.0.0.2"),
            "414" to get(target = "/" + "a".repeat(3000)),
            "431" to get(headers = listOf("X-Big: " + "a".repeat(9000))),
        )
        for ((name, answer) in answers) {
            val head = answer.first
            assertTrue(head.headers.containsAll(always), "$name: ${head.headers}")
            // Accept-Ranges where there is a file's length to range over.
            assertEquals(name in setOf("200", "206", "416", "HEAD"), head.header("Accept-Ranges") == listOf("bytes"), name)
            assertEquals(name in setOf("200", "206", "HEAD"), head.header("Content-Type") == listOf("video/x-matroska"), name)
            assertEquals(emptyList(), head.header("ETag") + head.header("Last-Modified") + head.header("Content-Encoding"), name)
        }
        assertEquals(listOf("GET, HEAD, OPTIONS"), answers.getValue("405").first.header("Allow"))
        for (error in listOf("404", "400", "405", "403", "414", "431")) assertEquals(listOf("0"), answers.getValue(error).first.header("Content-Length"), error)
    }

    @Test
    fun `nothing is compressed, whatever the client accepts`() {
        val (head, body) = get("bytes=0-", headers = listOf("Accept-Encoding: gzip, deflate, br"))
        assertEquals(206, head.status)
        assertEquals(emptyList(), head.header("Content-Encoding"))
        assertContentEquals(expected(0, size - 1), body)
    }
}

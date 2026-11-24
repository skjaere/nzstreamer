package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.skjaere.usenet.debrid.ServiceHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L620: one GET of the list with If-None-Match, through the app's service client, https alone (http only to a loopback
 * address where the source given allows it), no redirect followed, at most 256 KiB, as "NZStreamer". A 304 keeps the
 * copy, a 404 is no list yet, a 429 or a 5xx keeps the copy and waits as long as Retry-After says, a day at most.
 *
 * The server is this test's own on the loopback address, and the source is given to the client directly, so these pass in
 * the debug and the release unit-test runs alike (L622).
 */
class PluginListClientTest {
    @TempDir
    lateinit var dir: File

    private val http: HttpClient = ServiceHttp.defaultHttpClient()
    private val servers = mutableListOf<LoopbackHttp>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var time = Instant.parse("2026-12-02T08:00:00Z").toEpochMilli()

    @AfterEach
    fun close() {
        scope.cancel()
        http.close()
        servers.forEach { it.close() }
    }

    private fun server(answer: (LoopbackHttp.Request) -> LoopbackHttp.Response) = LoopbackHttp(answer).also { servers += it }

    private fun client(server: LoopbackHttp) = PluginListClient(PluginSource.loopback(server.port), http)

    private fun list(server: LoopbackHttp) = PluginList(
        source = { PluginSource.loopback(server.port) },
        reader = { PluginListClient(it, http) },
        cache = PluginListCache(File(dir, "list.json")),
        installed = { false },
        scope = scope,
        io = Dispatchers.IO,
        now = { time },
    )

    @Test
    fun `the copy's ETag is sent, and a 304 keeps the copy`() = runBlocking<Unit> {
        val server = server { request ->
            if (request.headers["if-none-match"] == "\"v1\"") LoopbackHttp.Response(304) else LoopbackHttp.Response(200, LIST, mapOf("ETag" to "\"v1\""))
        }
        val first = assertIs<ListAnswer.Fresh>(client(server).read(etag = null))
        assertEquals("\"v1\"", first.etag)
        assertEquals(LIST.decodeToString(), first.body.decodeToString())
        assertNull(server.requests.single().headers["if-none-match"])
        assertIs<ListAnswer.NotChanged>(client(server).read(etag = "\"v1\""))
        assertEquals("\"v1\"", server.requests.last().headers["if-none-match"])

        val list = list(server)
        list.pageOpened().join()
        time += Duration.ofMinutes(5).toMillis()
        list.pageOpened().join()
        assertEquals(listOf(null, "\"v1\"", null, "\"v1\""), server.requests.map { it.headers["if-none-match"] })
        val state = list.state.value
        assertEquals(listOf("harbor"), state.index?.plugins?.map { it.id }, "the copy kept")
        assertEquals(time, state.readAt, "read again just now")
        assertEquals(false, state.failed)
    }

    @Test
    fun `a 404 is a list with nothing in it yet`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(404, "Not Found".toByteArray()) }
        assertIs<ListAnswer.NoList>(client(server).read(etag = null))
        val list = list(server)
        list.pageOpened().join()
        assertEquals(emptyList(), list.state.value.index?.plugins)
        assertEquals(false, list.state.value.failed)
        assertEquals(time, list.state.value.readAt)
    }

    @Test
    fun `a 429 or a 5xx keeps the copy and waits as long as Retry-After says`() = runBlocking<Unit> {
        var busy = false
        val server = server {
            if (busy) LoopbackHttp.Response(429, headers = mapOf("Retry-After" to "120")) else LoopbackHttp.Response(200, LIST, mapOf("ETag" to "\"v1\""))
        }
        val list = list(server)
        list.pageOpened().join()
        busy = true
        time += Duration.ofMinutes(5).toMillis()
        list.pageOpened().join()
        assertEquals(2, server.requests.size)
        assertEquals(listOf("harbor"), list.state.value.index?.plugins?.map { it.id }, "the copy kept")
        assertTrue(list.state.value.failed)
        time += Duration.ofSeconds(119).toMillis()
        list.pageOpened().join()
        list.checkAgain().join()
        assertEquals(2, server.requests.size, "asked to wait 120 s")
        time += Duration.ofSeconds(1).toMillis()
        list.pageOpened().join()
        assertEquals(3, server.requests.size)

        val unavailable = server { LoopbackHttp.Response(503, headers = mapOf("Retry-After" to http(Instant.ofEpochMilli(time).plus(Duration.ofHours(2))))) }
        val answer = assertIs<ListAnswer.Busy>(PluginListClient(PluginSource.loopback(unavailable.port), http) { Instant.ofEpochMilli(time) }.read(null))
        assertEquals(503, answer.status)
        assertEquals(Duration.ofHours(2), answer.retryAfter)
    }

    @Test
    fun `a Retry-After is seconds or a date, a day at most`() {
        val now = Instant.parse("2026-12-02T08:00:00Z")
        assertEquals(Duration.ofSeconds(120), PluginListClient.retryAfter("120", now))
        assertEquals(Duration.ofHours(3), PluginListClient.retryAfter(http(now.plus(Duration.ofHours(3))), now))
        assertEquals(Duration.ofDays(1), PluginListClient.retryAfter("${Duration.ofDays(7).seconds}", now))
        assertEquals(Duration.ofDays(1), PluginListClient.retryAfter(http(now.plus(Duration.ofDays(7))), now))
        listOf(null, "", "soon", "-5", "0", http(now.minusSeconds(60)), "99999999999999999999").forEach { assertNull(PluginListClient.retryAfter(it, now), it) }
    }

    @Test
    fun `a redirect is not followed`() = runBlocking<Unit> {
        val server = server { request ->
            if (request.path == "/index.json") LoopbackHttp.Response(302, headers = mapOf("Location" to "/elsewhere.json")) else LoopbackHttp.Response(200, LIST)
        }
        val answer = assertIs<ListAnswer.Failed>(client(server).read(etag = null))
        assertEquals(ReadFailure.Redirect, answer.kind)
        assertEquals(listOf("/index.json"), server.requests.map { it.path })
    }

    @Test
    fun `http is refused unless the source given lets it through to the loopback address`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(200, LIST) }
        val strict = PluginSource("http://127.0.0.1:${server.port}/index.json", releases = "http://127.0.0.1:${server.port}", loopbackHttp = false)
        assertEquals(ReadFailure.Insecure, assertIs<ListAnswer.Failed>(PluginListClient(strict, http).read(null)).kind)
        val elsewhere = PluginSource("http://example.invalid/index.json", releases = "http://example.invalid", loopbackHttp = true)
        assertEquals(ReadFailure.Insecure, assertIs<ListAnswer.Failed>(PluginListClient(elsewhere, http).read(null)).kind)
        assertEquals(0, server.requests.size, "nothing asked")
        assertIs<ListAnswer.Fresh>(client(server).read(null))
        assertEquals(1, server.requests.size)
    }

    /**
     * Refused by its Content-Length before any of it is read; and cut where it gives none: a list far over the cap, sent
     * as fast as the client takes it, of which the client reads one byte past the cap and lets the rest go, so most of it
     * is never sent. One that read it all, to refuse it after, would have taken every byte. (With a Content-Length, OkHttp
     * itself takes what comes in a tenth of a second, to keep the connection, into no buffer of the app's.)
     */
    @Test
    fun `a body over 256 KiB is cut and refused, with its length given or not`() = runBlocking<Unit> {
        val withLength = server { LoopbackHttp.Response(200, ByteArray(PluginIndex.MAX_BYTES + 1) { ' '.code.toByte() }) }
        assertEquals(ReadFailure.TooLarge, assertIs<ListAnswer.Failed>(client(withLength).read(null)).kind, "with its Content-Length")
        val lengthless = server { LoopbackHttp.Response(200, lengthless = true, streamed = HUGE) }
        assertEquals(ReadFailure.TooLarge, assertIs<ListAnswer.Failed>(client(lengthless).read(null)).kind, "read to the close")
        lengthless.close()
        assertTrue(lengthless.sent.get() < HUGE / 2, "cut: ${lengthless.sent.get()} of $HUGE bytes sent")
        val atTheCap = server { LoopbackHttp.Response(200, ByteArray(PluginIndex.MAX_BYTES) { ' '.code.toByte() }, lengthless = true) }
        assertEquals(PluginIndex.MAX_BYTES, assertIs<ListAnswer.Fresh>(client(atTheCap).read(null)).body.size)
    }

    @Test
    fun `the list is asked for as NZStreamer, saying nothing else of the app`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(200, LIST) }
        client(server).read(null)
        assertEquals("NZStreamer", server.requests.single().headers["user-agent"])
    }

    @Test
    fun `another answer, or none, is a failed read`() = runBlocking<Unit> {
        val forbidden = server { LoopbackHttp.Response(403) }
        val answer = assertIs<ListAnswer.Failed>(client(forbidden).read(null))
        assertEquals(ReadFailure.Status, answer.kind)
        assertEquals(403, answer.status)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(ReadFailure.Network, assertIs<ListAnswer.Failed>(PluginListClient(PluginSource.loopback(port), http).read(null)).kind)
    }

    private companion object {
        /** A list far over the cap: more than the loopback connection's buffers hold, so a cut read leaves most of it unsent. */
        const val HUGE = 64L * 1024 * 1024

        val LIST = """
            {"format":1,"generated":"2026-12-01T10:00:00Z","plugins":[{"id":"harbor","package":"app.nzstreamer.plugin.harbor",
            "name":"Harbor Lights","summary":"Harbor Lights in a tab of its own.","withdrawn":[],"versions":[]}]}
        """.trimIndent().toByteArray()

        fun http(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))
    }
}

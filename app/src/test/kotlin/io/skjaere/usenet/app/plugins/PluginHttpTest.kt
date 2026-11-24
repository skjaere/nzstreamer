package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.PluginHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L631 against servers of the test's own on the loopback address: a plugin's GET through the app's service client, a
 * redirect followed only to the same scheme, host and port and three at most, the body read no further than maxBytes, a
 * request with no answer in time, and log lines that name the host and the status alone: never a header's value, the
 * address's path or query, or the body. And L593: a plugin's host judges an address as the app judges an indexer's.
 */
class PluginHttpTest {
    @TempDir
    lateinit var dir: File

    private val client: HttpClient = ServiceHttp.defaultHttpClient()
    private val servers = mutableListOf<AutoCloseable>()
    private val version = PluginVersion("kestrel", 1, 1)

    @AfterEach
    fun close() {
        client.close()
        servers.forEach { it.close() }
    }

    private fun server(answer: (LoopbackHttp.Request) -> LoopbackHttp.Response) = LoopbackHttp(answer).also { servers += it }

    private fun http(requestTimeoutMs: Long = 10_000) = PluginHttpImpl(version, { client }, requestTimeoutMs = requestTimeoutMs)

    private fun redirect(to: String) = LoopbackHttp.Response(302, headers = mapOf("Location" to to))

    private fun ok(body: String) = LoopbackHttp.Response(200, body.toByteArray())

    private suspend fun refused(kind: PluginHttpException.Kind, request: suspend () -> Unit) {
        val e = assertFailsWith<PluginHttpException> { request() }
        assertEquals(kind, e.kind)
        assertFalse("127.0.0.1" in e.message.orEmpty() || "/" in e.message.orEmpty(), "its message names no address: ${e.message}")
    }

    @Test
    fun `an answer is its status, its headers by lower-case name and its body, whatever the status`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(404, "nothing for this round".toByteArray(), headers = mapOf("X-Kestrel-Count" to "3")) }
        val answer = http().get("http://127.0.0.1:${server.port}/api/v3/rounds?league=7", mapOf("X-Api-Key" to "kestrel-key", "User-Agent" to "Kestrel/9"))

        assertEquals(404, answer.status)
        assertEquals("3", answer.headers["x-kestrel-count"])
        assertTrue(answer.headers.keys.all { it == it.lowercase() }, "${answer.headers.keys}")
        assertContentEquals("nothing for this round".toByteArray(), answer.body)
        val asked = server.requests.single()
        assertEquals("/api/v3/rounds?league=7", asked.path)
        assertEquals("kestrel-key", asked.headers["x-api-key"])
        assertEquals(ServiceHttp.USER_AGENT, asked.headers["user-agent"], "the app's own User-Agent")
    }

    /** Three redirects to the same address are followed, a fourth isn't; a redirect anywhere else isn't followed at all. */
    @Test
    fun `a redirect is followed only to the same scheme, host and port, three at most`() = runBlocking<Unit> {
        val elsewhere = server { ok("elsewhere") }
        lateinit var base: String
        val server = server { request ->
            when (request.path) {
                "/a0" -> redirect("/a1")
                "/a1" -> redirect("$base/a2")
                "/a2" -> LoopbackHttp.Response(307, headers = mapOf("Location" to "a3"))
                "/a3" -> ok("arrived")
                "/b0" -> redirect("/b1")
                "/b1" -> redirect("/b2")
                "/b2" -> redirect("/b3")
                "/b3" -> redirect("/b4")
                "/b4" -> ok("too far")
                "/port" -> redirect("http://127.0.0.1:${elsewhere.port}/x")
                "/scheme" -> redirect("https://127.0.0.1:${base.substringAfterLast(':')}/a3")
                "/host" -> redirect("http://localhost:${base.substringAfterLast(':')}/a3")
                "/nowhere" -> LoopbackHttp.Response(302)
                else -> LoopbackHttp.Response(404)
            }
        }
        base = "http://127.0.0.1:${server.port}"
        val http = http()

        val arrived = http.get("$base/a0", mapOf("X-Api-Key" to "kestrel-key"))
        assertEquals(200, arrived.status)
        assertContentEquals("arrived".toByteArray(), arrived.body)
        assertEquals(listOf("/a0", "/a1", "/a2", "/a3"), server.requests.map { it.path })
        assertEquals(listOf("kestrel-key"), server.requests.map { it.headers["x-api-key"] }.distinct(), "its headers on every hop to the same address")

        server.requests.clear()
        refused(PluginHttpException.Kind.RedirectRefused) { http.get("$base/b0") }
        assertEquals(listOf("/b0", "/b1", "/b2", "/b3"), server.requests.map { it.path }, "a fourth redirect isn't followed")
        listOf("/port", "/scheme", "/host").forEach { path -> refused(PluginHttpException.Kind.RedirectRefused) { http.get("$base$path", mapOf("X-Api-Key" to "kestrel-key")) } }
        assertEquals(emptyList(), elsewhere.requests, "nothing went to another port")
        assertEquals(302, http.get("$base/nowhere").status, "a redirect that says nowhere to go is the answer")
    }

    @Test
    fun `a body longer than maxBytes is refused, one of maxBytes read whole`() = runBlocking<Unit> {
        val body = ByteArray(100) { 'k'.code.toByte() }
        val server = server { request ->
            when (request.path) {
                "/told" -> LoopbackHttp.Response(200, body)
                else -> LoopbackHttp.Response(200, body, lengthless = true)
            }
        }
        val http = http()
        listOf("/told", "/untold").forEach { path ->
            refused(PluginHttpException.Kind.TooLarge) { http.get("http://127.0.0.1:${server.port}$path", maxBytes = 99) }
            assertContentEquals(body, http.get("http://127.0.0.1:${server.port}$path", maxBytes = 100).body, path)
        }
    }

    @Test
    fun `a request with no answer in time, or none at all, throws its kind`() = runBlocking<Unit> {
        // Takes the connection and never answers.
        val silent = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { servers += it }
        refused(PluginHttpException.Kind.Timeout) { http(requestTimeoutMs = 300).get("http://127.0.0.1:${silent.localPort}/") }
        // Answers its head, then stops half-way through the body.
        val stalled = server { LoopbackHttp.Response(200, ByteArray(100), stallAfter = 10) }
        refused(PluginHttpException.Kind.Timeout) { http(requestTimeoutMs = 300).get("http://127.0.0.1:${stalled.port}/") }

        val closed = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).run { localPort.also { close() } }
        refused(PluginHttpException.Kind.Unreachable) { http().get("http://127.0.0.1:$closed/") }
        listOf("ftp://127.0.0.1/x", "not an address", "http://", "file:///kestrel.txt", "").forEach { url ->
            refused(PluginHttpException.Kind.InvalidAddress) { http().get(url) }
        }
    }

    /**
     * L631, L606: a plugin's request is logged by the host and the status alone: a key in a header, a header of the answer,
     * the path and query, and the body never reach the log, whether the request was answered, redirected or refused.
     */
    @Test
    fun `nothing logged holds a header's value, the address's path or the body`() = runBlocking<Unit> {
        val canaries = listOf("canary-note-4e1a", "canary-key-9b3c", "canary-answer-77d2", "canary-body-0c5f", "canary-path-6a8e", "canary-query-2f9d")
        val server = server { request ->
            when (request.path.substringBefore('?')) {
                "/away" -> redirect("http://127.0.0.1:1/canary-path-6a8e")
                else -> LoopbackHttp.Response(200, "canary-body-0c5f".toByteArray(), headers = mapOf("X-Answer-Note" to "canary-answer-77d2"))
            }
        }
        val headers = mapOf("X-Kestrel-Note" to "canary-note-4e1a", "X-Api-Key" to "canary-key-9b3c")
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L

        val http = http()
        http.get("http://127.0.0.1:${server.port}/canary-path-6a8e?words=canary-query-2f9d", headers)
        refused(PluginHttpException.Kind.RedirectRefused) { http.get("http://127.0.0.1:${server.port}/away?k=canary-query-2f9d", headers) }
        refused(PluginHttpException.Kind.TooLarge) { http.get("http://127.0.0.1:${server.port}/canary-path-6a8e", headers, maxBytes = 3) }

        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(logged.any { "kestrel" in it && "127.0.0.1" in it && "200" in it }, "a line names the plugin, the host and the status: $logged")
        assertTrue(logged.any { "kestrel" in it && "RedirectRefused" in it }, "$logged")
        canaries.forEach { canary -> assertFalse(logged.any { canary in it }, "\"$canary\" reached the log: $logged") }
    }

    /** L593: the host judges an address a plugin is set up with as the app judges an indexer's, and its GETs are the app's. */
    @Test
    fun `a plugin's host judges an address as an indexer's, and gets through the app's client`() = runBlocking<Unit> {
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), FakeSecrets())
        val host = PluginHosts(store, FakeSecrets(), main = Dispatchers.Unconfined, http = { client }).create(version, "Kestrel") { _, _ -> }
        mapOf(
            "https://sports.example.org/api" to AddressSafety.Encrypted,
            "http://kestrel.local:1867" to AddressSafety.LocalHttp,
            "http://127.0.0.1:1867" to AddressSafety.LocalHttp,
            "http://sports.example.org" to AddressSafety.PublicHttp,
            "ftp://sports.example.org" to AddressSafety.Invalid,
            "http://sports.example.org:99999" to AddressSafety.Invalid,
            "not an address" to AddressSafety.Invalid,
        ).forEach { (url, safety) -> assertEquals(safety, host.addressSafety(url), url) }

        val server = server { ok("from the app's client") }
        assertContentEquals("from the app's client".toByteArray(), host.http.get("http://127.0.0.1:${server.port}/").body)
        host.cancel()
    }
}

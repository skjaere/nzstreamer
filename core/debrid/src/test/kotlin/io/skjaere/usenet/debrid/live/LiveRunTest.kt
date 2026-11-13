package io.skjaere.usenet.debrid.live

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.FakeApi
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.MutableClock
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.ServiceRateLimit
import io.skjaere.usenet.debrid.json
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live-account classes' own workings (item 39), on the loopback address and Ktor's MockEngine alone, so the lead's one
 * live run reads and reports as it should: the key from the run's environment (which Gradle's logs never print), a
 * CDN read that asks from a byte on and stops at 64 KB whatever comes, If-Range with a strong ETag, and every API answer's
 * shape. Markers stand in for each thing the report mustn't hold: a key, a link, a hash, an e-mail, a customer id, an
 * ETag, a release's name and a service's own words.
 */
class LiveRunTest {
    private val reports = Files.createTempDirectory("debridLive").toFile()
    private var cdn: FakeCdn? = null

    @AfterEach
    fun cleanUp() {
        cdn?.close()
        reports.deleteRecursively()
    }

    private fun run(env: Map<String, String> = emptyMap()) = LiveRun("TorBox", env = env::get, reports = reports)

    private fun LiveRun.report(): String = write().readText()

    private fun assertNothingPlanted(report: String) {
        listOf(KEY, LINK_TOKEN, HASH.hex, ACCOUNT, CUSTOMER, ETAG_MARK, RELEASE, DETAIL, "token=").forEach { planted ->
            assertFalse(planted in report, "\"$planted\" in the report:\n$report")
        }
    }

    @Test
    fun `the key and the film come from the run's environment, never from a system property gradle's log prints`() {
        val before = System.getProperty("devTorBoxKey")
        System.setProperty("devTorBoxKey", "from-a-property")
        try {
            val run = run(mapOf("devTorBoxKey" to "from-the-environment", "debridLiveHash" to HASH.hex.uppercase()))
            assertEquals("from-the-environment", run.key)
            assertEquals(HASH, run.film)
            val none = run()
            assertNull(none.key)
            assertNull(none.film)
        } finally {
            if (before == null) System.clearProperty("devTorBoxKey") else System.setProperty("devTorBoxKey", before)
        }
    }

    @Test
    fun `a read of a link asks from a byte on, as the player does, and stops at 64 KB even when the whole file comes`() {
        val cdn = FakeCdn().also { cdn = it }
        val run = run()
        val answer = run.cdn("a ranged read", cdn.link(LINK_TOKEN, whole = true))
        assertEquals(200, answer?.status)
        assertEquals<List<String?>>(listOf("bytes=1048576-"), cdn.ranges)
        assertFalse(cdn.wroteItAll(), "the CDN was let write the whole file")
        val report = run.report()
        assertContains(report, "a ranged read: HTTP 200 (a 200 to a ranged read: the whole file)")
        assertContains(report, "65536 bytes read")
        assertNothingPlanted(report)
    }

    @Test
    fun `a strong etag is asked again with if-range, a later link's compared with it, and neither is written`() {
        val cdn = FakeCdn().also { cdn = it }
        val run = run()
        val first = run.cdn("a ranged read", cdn.link(LINK_TOKEN))
        assertEquals(206, first?.status)
        assertContains(run.report(), "a ranged read: HTTP 206, HTTP/1.1, Content-Range from the byte asked, video/x-matroska, ranges bytes, ETag strong, 65536 bytes read")

        val again = run.cdn("again with If-Range", cdn.link(LINK_TOKEN), ifRange = first?.etag)
        assertEquals(206, again?.status)
        assertEquals("\"$ETAG_MARK-$LINK_TOKEN\"", cdn.ifRanges.last())

        // A new link from another host whose ETag differs: the old one in If-Range brings the whole file (Media3's reopen).
        val service = OneLinkService(ServiceLink(cdn.link("$LINK_TOKEN-2"), Instant.EPOCH))
        runBlocking { run.askAgain(service, PREPARED, PREPARED.picked!!, cdn.link(LINK_TOKEN), first, after = Duration.ZERO) }
        assertEquals("\"$ETAG_MARK-$LINK_TOKEN\"", cdn.ifRanges.last())
        val report = run.report()
        assertContains(report, "link again after 0 s: another link")
        assertContains(report, "the new link with the first link's ETag as If-Range: HTTP 200 (a 200 to a ranged read: the whole file)")
        assertContains(report, "the new link's ETag: another")
        assertNothingPlanted(report)
    }

    @Test
    fun `every answer the run's client gets is noted by its call and shape, never its query, a value or the service's words`() = runTest {
        val api = FakeApi(this) { request ->
            when (request.url.encodedPath.removePrefix("/v1/api/")) {
                "user/me" -> if (request.headers["Authorization"] == "Bearer $KEY") {
                    json("""{"success":true,"error":null,"detail":"$DETAIL","data":{"id":5,"email":"$ACCOUNT","plan":2,"premium_expires_at":"2027-01-12T00:00:00Z","settings":{"x":true}}}""")
                } else {
                    json("""{"success":false,"error":"BAD_TOKEN","detail":"$DETAIL for $ACCOUNT","data":null}""", HttpStatusCode.Forbidden)
                }
                "torrents/requestdl" -> json("""{"success":true,"error":null,"detail":"$DETAIL","data":"https://cdn.example.org/$LINK_TOKEN/$RELEASE.mkv"}""")
                else -> json("""{"success":true,"data":{"${HASH.hex}":{"name":"$RELEASE","size":1,"hash":"${HASH.hex}","files":[{"name":"$RELEASE.mkv","size":1},{"name":"$RELEASE.nfo","size":1,"short_name":"x"}]}}}""")
            }
        }
        val run = run()
        val http = run.recording(api.http)
        val clock = MutableClock()
        // The pace is the client's own business here: no wait between calls.
        fun torbox(key: String) = TorBoxService(key, http, limit = ServiceRateLimit(Duration.ZERO), gate = ServiceGate(clock), clock = clock, log = run.lines::add)

        torbox(KEY).test()
        // The client still reads the answer the run noted.
        assertEquals("https://cdn.example.org/$LINK_TOKEN/$RELEASE.mkv", torbox(KEY).link(PREPARED, PREPARED.picked!!).url)
        assertFailsWith<ServiceException> { torbox("NotTheKey").test() }
        torbox(KEY).check(listOf(HASH))

        val report = run.report()
        assertContains(report, "GET /v1/api/user/me: HTTP 200, {success: true, error: null, detail: string, data: {id: number, email: string, plan: number, premium_expires_at: string, settings: {x: boolean}}}")
        assertContains(report, "GET /v1/api/torrents/requestdl: HTTP 200, {success: true, error: null, detail: string, data: string}")
        assertContains(report, "GET /v1/api/user/me: HTTP 403, {success: false, error: \"BAD_TOKEN\", detail: string, data: null}")
        assertContains(report, "POST /v1/api/torrents/checkcached: HTTP 200, {success: true, data: {<key> ×1: {name: string, size: number, hash: string, files: [{name: string, size: number, short_name: string}] ×2}}}")
        assertContains(report, "API calls: 4")
        assertNothingPlanted(report)
    }

    @Test
    fun `an answer's shape names its fields and their kinds, and says only the envelope's status and code`() {
        assertEquals(
            "{status: \"error\", code: \"authentication_failed\", message: string}",
            AnswerShape.of("""{"status":"error","code":"authentication_failed","message":"$DETAIL for customer $CUSTOMER"}"""),
        )
        assertEquals(
            "{status: \"success\", content: [{path: string, size: number, link: string, stream_link: string | null}] ×2}",
            AnswerShape.of("""{"status":"success","content":[{"path":"$RELEASE/a.mkv","size":1,"link":"https://x/$LINK_TOKEN","stream_link":null},{"path":"b","size":2,"link":"l","stream_link":"s"}]}"""),
        )
        assertEquals("{status: \"success\", response: [boolean] ×3}", AnswerShape.of("""{"status":"success","response":[true,false,true]}"""))
        // A code that is a sentence is the service's words: its kind alone.
        assertEquals("{success: false, error: string, data: [] ×0}", AnswerShape.of("""{"success":false,"error":"$DETAIL for $ACCOUNT","data":[]}"""))
        // A key that is no field's name (an address, a hash) is never written either.
        assertEquals("{customer_id: string, <key> ×2: number}", AnswerShape.of("""{"customer_id":"$CUSTOMER","$ACCOUNT":1,"${HASH.hex}":2}"""))
        assertEquals("not JSON, 36 characters", AnswerShape.of("<html>$DETAIL</html>"))
    }

    /** A service whose link() gives [link]: the link asked again after a minute. */
    private class OneLinkService(private val link: ServiceLink) : DebridService {
        override val kind = ServiceKind.TorBox
        override suspend fun test() = Account("$ACCOUNT")
        override suspend fun check(hashes: List<InfoHash>) = hashes.associateWith { true }
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?) = PREPARED
        override suspend fun link(prepared: Prepared, file: ServiceFile) = link
    }

    /**
     * A CDN on the loopback address: each link's token has its own strong ETag; a link under `whole` answers every read
     * with the whole file, as a CDN that ignores Range does, and so does an If-Range that isn't the link's ETag.
     */
    private class FakeCdn : AutoCloseable {
        val ranges = CopyOnWriteArrayList<String?>()
        val ifRanges = CopyOnWriteArrayList<String?>()
        private val written = CopyOnWriteArrayList<Boolean>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/cdn/") { exchange ->
                try {
                    serve(exchange)
                } finally {
                    exchange.close()
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }

        fun link(token: String, whole: Boolean = false) =
            "http://127.0.0.1:${server.address.port}/cdn/${if (whole) "whole" else "ranged"}/$token/$RELEASE.mkv"

        /** Whether the last answer's body was written to its end, once its writer is done. */
        fun wroteItAll(): Boolean {
            val until = System.nanoTime() + 10_000_000_000
            while (written.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
            return written.last()
        }

        private fun serve(exchange: HttpExchange) {
            val (mode, token) = exchange.requestURI.path.split('/').let { it[2] to it[3] }
            val range = exchange.requestHeaders.getFirst("Range").also { ranges += it }
            val ifRange = exchange.requestHeaders.getFirst("If-Range").also { ifRanges += it }
            val etag = "\"$ETAG_MARK-$token\""
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
            val whole = mode == "whole" || from == null || (ifRange != null && ifRange != etag)
            val start = if (whole) 0 else from
            exchange.responseHeaders.add("ETag", etag)
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (!whole) exchange.responseHeaders.add("Content-Range", "bytes $start-${SIZE - 1}/$SIZE")
            exchange.sendResponseHeaders(if (whole) 200 else 206, SIZE - start)
            written += try {
                val chunk = ByteArray(64 * 1024)
                exchange.responseBody.use { out ->
                    var left = SIZE - start
                    while (left > 0) {
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        out.write(chunk, 0, n)
                        left -= n
                    }
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        override fun close() {
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }

        companion object {
            const val SIZE = 64L * 1024 * 1024
        }
    }

    private companion object {
        const val KEY = "LIVEKEYMARKER0000"
        const val LINK_TOKEN = "LINKTOKENMARKER"
        const val ACCOUNT = "quay.viewer@example.org"
        const val CUSTOMER = "900000042"
        const val ETAG_MARK = "ETAGMARKER"
        const val RELEASE = "Quiet.Orbit.2019.1080p.WEB-DL"
        const val DETAIL = "The service's own words"
        val HASH = InfoHash.of("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f")
        val PREPARED = ServiceFile(0, "$RELEASE.mkv", 1, id = "7").let { file -> Prepared(HASH, ServiceListing(listOf(file)), file, handle = "41") }
    }
}

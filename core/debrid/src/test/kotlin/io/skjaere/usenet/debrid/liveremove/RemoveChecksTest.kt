package io.skjaere.usenet.debrid.liveremove

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.skjaere.usenet.debrid.FakeApi
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.MutableClock
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceRateLimit
import io.skjaere.usenet.debrid.json
import io.skjaere.usenet.debrid.live.LiveRun
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The D2 live checks' own workings, on Ktor's MockEngine and the loopback address alone, so the lead's one live run asks
 * what it should and nothing more: the delete goes once, by the film entry's id alone, in the pinned body with no `all`;
 * a list that doesn't hold exactly that one entry stops the run before any delete; the calls stay within the budget; and
 * the reports and the summary say codes, booleans and deltas, never a key, a link, a host, an id, a hash or a name.
 * Markers stand in for each of those.
 */
class RemoveChecksTest {
    private val reports = Files.createTempDirectory("debridLiveRemove").toFile()
    private var cdn: FakeCdn? = null

    @AfterEach
    fun cleanUp() {
        cdn?.close()
        reports.deleteRecursively()
    }

    private fun run(service: String, report: String) = LiveRun(service, env = { null }, reports = reports, report = report)

    /** The check on [api] and the loopback CDN; the pace the client's, on the test's clock. */
    private fun torboxCheck(api: FakeApi, run: LiveRun, budget: CallBudget, readOn: Duration = Duration.ofMillis(1500)): TorBoxRemoveCheck {
        val answers = LastAnswers()
        val http = answers.install(budget.guarding(run.recording(api.http)))
        val clock = MutableClock()
        val torbox = TorBoxService(KEY, http, limit = ServiceRateLimit(Duration.ZERO), gate = ServiceGate(clock), clock = clock, log = run.lines::add)
        val links = LinkReader(counted = {
            budget.take()
            run.countCdnRead()
        }, readTimeout = Duration.ofSeconds(2))
        return TorBoxRemoveCheck(run, KEY, HASH, http, torbox, links, answers, readOn = readOn, tick = Duration.ofMillis(20))
    }

    private fun assertNothingPlanted(text: String) {
        listOf(KEY, LINK_TOKEN, HASH.hex, OTHER.hex, RELEASE, ACCOUNT, DETAIL, CUSTOMER, TRANSFER, "$HELD_ID", "$NEW_ID", "$FILE_ID", "127.0.0.1", "token=", "123456789", "223456789").forEach { planted ->
            assertFalse(planted in text, "\"$planted\" in:\n$text")
        }
    }

    private fun HttpRequestData.call() = "${method.value} ${url.encodedPath.removePrefix("/v1/api/").removePrefix("/api/")}"

    @Test
    fun `the film's one entry is deleted once, by its id alone, and what follows the delete is asked`() {
        runTest {
            val cdn = FakeCdn().also { cdn = it }
            val torbox = FakeTorBox(cdn)
            val api = FakeApi(this) { request -> torbox.answer(this, request) }
            val run = run("TorBox", TorBoxRemoveCheck.REPORT)
            val budget = CallBudget()
            val check = torboxCheck(api, run, budget)

            check.createOnHeld()
            check.linkBeforeDelete()
            check.deleteHeld()
            check.afterDelete()
            check.close()

            assertEquals(
                listOf(
                    "GET torrents/mylist", "POST torrents/createtorrent", "GET torrents/mylist", "GET torrents/requestdl",
                    "POST torrents/controltorrent", "GET torrents/requestdl", "GET torrents/mylist", "POST torrents/controltorrent",
                    "POST torrents/checkcached",
                ),
                api.requests.map { it.call() },
            )
            // Both deletes name the film's entry alone, in the pinned body: never `all`, never the other entry.
            assertEquals(List(2) { """{"torrent_id":$HELD_ID,"operation":"delete"}""" }, torbox.deletes)
            assertEquals(listOf(OTHER_ID), torbox.listed())
            assertEquals(11, budget.spent)
            assertEquals<List<String?>>(listOf("bytes=0-", "bytes=0-"), cdn.ranges)

            val report = run.write().readText()
            assertContains(report, "API calls: 9, CDN reads: 2")
            assertContains(report, "the list before createtorrent: read, entries with the film's hash: 1")
            assertContains(report, "createtorrent of the held hash: HTTP 200, code none, a torrent_id given: yes, the held entry's: yes, the answer's hash the film's: yes, a Date header: yes")
            assertContains(report, "the list after: entries with the film's hash: 1 (a second one: no), the held entry listed: yes, the returned id listed: yes")
            assertContains(report, "the link opened from byte 0: HTTP 206, a Content-Range from byte 0: yes, 65536 bytes read, its body left open")
            assertContains(report, "controltorrent delete of the held entry alone: HTTP 200, code none")
            assertContains(report, "kept delivering: no")
            assertContains(report, "a fresh ranged GET of the same link: HTTP 403")
            assertContains(report, "GET /v1/api/torrents/requestdl: HTTP 404, {success: false, error: \"ITEM_NOT_FOUND\", detail: string, data: null}")
            assertContains(report, "after the delete: the entry gone from the list: yes, entries with the film's hash: 0")
            assertContains(report, "the same delete again: HTTP 404, code ITEM_NOT_FOUND")
            assertContains(report, "checkcached of the film after the delete: still cached")

            val summary = check.summary
            assertEquals(4, summary.size)
            assertEquals(
                "1. createtorrent (add_only_if_cached) of the hash the list holds: HTTP 200, code none; the same torrent_id: yes; a second " +
                    "entry: no; the entry's created_at less the answer's Date: -74790.000 s, less the first list's created_at: +0.000 s",
                summary[0],
            )
            assertEquals("3. controltorrent delete of that entry's torrent_id alone (a body with no all): HTTP 200, code none", summary[2])
            assertContains(summary[3], "requestdl of the deleted entry: HTTP 404, code ITEM_NOT_FOUND; mylist: the entry gone: yes; the same delete again: HTTP 404, code ITEM_NOT_FOUND; checkcached: still cached: yes")
            val written = LiveSummary().apply { put("TorBox", "torbox-remove.txt", summary) }.text(budget)
            assertContains(written, "Real calls in this run: 11 of 25")
            assertNothingPlanted(report + written)
        }
    }

    @Test
    fun `a body that goes on after the delete is read on through the window, spread over it`() {
        runTest {
            val cdn = FakeCdn().also { cdn = it }
            val torbox = FakeTorBox(cdn, cuts = false)
            val api = FakeApi(this) { request -> torbox.answer(this, request) }
            val run = run("TorBox", TorBoxRemoveCheck.REPORT)
            val check = torboxCheck(api, run, CallBudget(), readOn = Duration.ofMillis(400))

            check.createOnHeld()
            check.linkBeforeDelete()
            check.deleteHeld()
            check.afterDelete()
            check.close()

            val report = run.write().readText()
            // 2 MiB a 20 ms tick at most: never the whole file, whatever the CDN would give.
            val kept = Regex("""kept delivering: yes, (\d+\.\d) MiB in (\d+) ms""").find(report)
            assertTrue(kept != null, report)
            assertTrue(kept.groupValues[1].toDouble() in 1.0..48.0, kept.value)
            assertTrue(kept.groupValues[2].toLong() >= 400, kept.value)
            assertContains(report, "a fresh ranged GET of the same link: HTTP 206")
            assertContains(check.summary[3], "the body already flowing kept delivering: yes")
        }
    }

    @Test
    fun `a second entry with the film's hash after createtorrent stops the run before any delete`() {
        runTest {
            val cdn = FakeCdn().also { cdn = it }
            val torbox = FakeTorBox(cdn, addsAnother = true)
            val api = FakeApi(this) { request -> torbox.answer(this, request) }
            val run = run("TorBox", TorBoxRemoveCheck.REPORT)
            val check = torboxCheck(api, run, CallBudget())

            check.createOnHeld()
            check.linkBeforeDelete()
            assertFailsWith<AssertionError> { check.deleteHeld() }
            assertFalse(check.deleteSent)
            assertFailsWith<AssertionError> { check.afterDelete() }
            check.close()

            assertTrue(torbox.deletes.isEmpty())
            assertFalse(api.requests.any { it.call() == "POST torrents/controltorrent" })
            assertEquals(listOf(OTHER_ID, HELD_ID, NEW_ID), torbox.listed())
            val report = run.write().readText()
            assertContains(report, "the list after: entries with the film's hash: 2 (a second one: yes), the held entry listed: yes, the returned id listed: yes")
            assertContains(report, "the returned id's entry: created_at less createtorrent's Date +1.000 s, less the first list's created_at +74791.000 s")
            assertEquals("3. failed: no delete: 2 entries in the latest list have the film's hash, not one", check.summary[2])
            assertEquals("4. failed: no delete was sent: nothing after it to see", check.summary[3])
            assertContains(check.summary[0], "the same torrent_id: no; a second entry: yes")
            assertNothingPlanted(report + check.summary)
        }
    }

    @Test
    fun `a list without exactly one entry of the film's stops the run before createtorrent`() {
        runTest {
            val cdn = FakeCdn().also { cdn = it }
            val torbox = FakeTorBox(cdn, holds = false)
            val api = FakeApi(this) { request -> torbox.answer(this, request) }
            val run = run("TorBox", TorBoxRemoveCheck.REPORT)
            val budget = CallBudget()
            val check = torboxCheck(api, run, budget)

            assertFailsWith<AssertionError> { check.createOnHeld() }
            assertFailsWith<AssertionError> { check.linkBeforeDelete() }
            assertFailsWith<AssertionError> { check.deleteHeld() }
            assertFailsWith<AssertionError> { check.afterDelete() }

            assertEquals(listOf("GET torrents/mylist"), api.requests.map { it.call() })
            assertEquals(1, budget.spent)
            assertTrue(cdn.ranges.isEmpty())
            assertEquals("1. failed: the list before has 0 entries with the film's hash, not one: no createtorrent, no delete", check.summary[0])
            assertNothingPlanted(run.write().readText() + check.summary)
        }
    }

    @Test
    fun `the delete may name only the entry held before, alone with the film's hash in a whole list, in the pinned body`() {
        assertEquals("""{"torrent_id":41,"operation":"delete"}""", deleteBody(41))
        assertFailsWith<IllegalArgumentException> { deleteBody(0) }

        val held = Entry(41, HASH, null, null, emptyList())
        val other = Entry(7, OTHER, null, null, emptyList())
        val again = Entry(42, HASH, null, null, emptyList())
        assertEquals(41L, assertIs<DeleteTarget.One>(deleteTarget(Listed(listOf(other, held), complete = true), held, HASH)).id)
        assertIs<DeleteTarget.None>(deleteTarget(Listed(listOf(other, held), complete = false), held, HASH))
        assertIs<DeleteTarget.None>(deleteTarget(Listed(listOf(held, again), complete = true), held, HASH))
        assertIs<DeleteTarget.None>(deleteTarget(Listed(listOf(other, again), complete = true), held, HASH))
        assertIs<DeleteTarget.None>(deleteTarget(Listed(listOf(other), complete = true), held, HASH))
        assertIs<DeleteTarget.None>(deleteTarget(null, held, HASH))
        assertIs<DeleteTarget.None>(deleteTarget(Listed(listOf(held), complete = true), null, HASH))

        // A list as long as a page may have more, and an entry no model reads may hide another: neither is whole.
        val page = (1..TorBoxRemoveCheck.PAGE).joinToString(",") { """{"id":$it,"hash":"${OTHER.hex}"}""" }
        assertFalse(Listed.of(200, """{"success":true,"data":[$page]}""")!!.complete)
        assertFalse(Listed.of(200, """{"success":true,"data":[{"id":41,"hash":"${HASH.hex}"},{"id":"not a number"}]}""")!!.complete)
        assertTrue(Listed.of(200, """{"success":true,"data":[{"id":41,"hash":"${HASH.hex.uppercase()}","created_at":"2026-11-05T13:13:30Z"}]}""")!!.complete)
        assertEquals(null, Listed.of(404, """{"success":true,"error":"ITEM_NOT_FOUND","data":{}}"""))
    }

    @Test
    fun `a call past the budget fails before it goes`() {
        runTest {
            val api = FakeApi(this) { json("""{"success":true}""") }
            val http = CallBudget(max = 2).guarding(api.http)
            http.get("https://api.example.org/a")
            http.get("https://api.example.org/b")
            assertFailsWith<CallBudget.OverBudget> { http.get("https://api.example.org/c") }
            assertEquals(2, api.requests.size)
        }
    }

    @Test
    fun `what a directdl leaves is said in booleans, never an id, a hash, a link or the space used`() {
        runTest {
            for (leaves in listOf(false, true)) {
                var added = false
                val api = FakeApi(this) { request ->
                    when (request.url.encodedPath.removePrefix("/api/")) {
                        "transfer/list" -> {
                            val film = if (added) """,{"id":"$TRANSFER-1","name":"$RELEASE","src":"magnet:?xt=urn:btih:${HASH.hex.uppercase()}"}""" else ""
                            json(
                                """{"status":"success","transfers":[{"id":"$TRANSFER-0","name":"$RELEASE","message":null,"status":"finished",""" +
                                    """"progress":1,"src":"magnet:?xt=urn:btih:${OTHER.hex}","folder_id":"f","file_id":null}$film]}""",
                            )
                        }
                        "account/info" -> json("""{"status":"success","customer_id":"$CUSTOMER","premium_until":1800000000,"limit_used":0.1,"space_used":${if (added) 223456789 else 123456789},"booster_points":0}""")
                        "transfer/directdl" -> {
                            added = leaves
                            json("""{"status":"success","content":[{"path":"$RELEASE/$RELEASE.mp4","size":734003200,"link":"https://cdn.example.org/$LINK_TOKEN/$RELEASE.mp4","stream_link":null,"transcode_status":"finished"}]}""")
                        }
                        else -> json("""{"status":"error","code":"not_found","message":"$DETAIL"}""")
                    }
                }
                val run = run("Premiumize", PremiumizeLeavesCheck.REPORT)
                val budget = CallBudget()
                val http = budget.guarding(run.recording(api.http))
                val clock = MutableClock()
                val premiumize = PremiumizeService(KEY, http, limit = ServiceRateLimit(Duration.ZERO), gate = ServiceGate(clock), clock = clock, log = run.lines::add)
                val check = PremiumizeLeavesCheck(run, KEY, HASH, http, premiumize)

                check.directdlLeaves()

                assertEquals(
                    listOf("GET transfer/list", "GET account/info", "POST transfer/directdl", "GET transfer/list", "GET account/info"),
                    api.requests.map { it.call() },
                )
                assertEquals(5, budget.spent)
                val said = if (leaves) "yes" else "no"
                assertEquals(
                    listOf("directdl of the film: files listed; 5 s later a transfer appeared: $said; one holds the film's hash: $said (before: no); space_used changed: $said"),
                    check.summary,
                )
                assertNothingPlanted(run.write().readText() + check.summary)
            }
        }
    }

    /**
     * TorBox's API for the check: the account's list (another torrent and, unless not [holds], the film's entry D1 left),
     * `createtorrent` naming the film's entry or, when it [addsAnother], a second one; `requestdl` of a listed entry's file
     * links to [cdn]; `controltorrent` deletes a listed entry and, when it [cuts], [cdn]'s streams with it, and answers
     * ITEM_NOT_FOUND for one that isn't listed.
     */
    private class FakeTorBox(private val cdn: FakeCdn, holds: Boolean = true, private val addsAnother: Boolean = false, private val cuts: Boolean = true) {
        private val entries = CopyOnWriteArrayList<Long>().apply {
            add(OTHER_ID)
            if (holds) add(HELD_ID)
        }
        val deletes = CopyOnWriteArrayList<String>()

        fun listed(): List<Long> = entries.toList()

        fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
            when (request.url.encodedPath.removePrefix("/v1/api/")) {
                "torrents/mylist" -> json("""{"success":true,"error":null,"detail":"$DETAIL","data":[${entries.joinToString(",") { entry(it) }}]}""")
                "torrents/createtorrent" -> {
                    val id = if (addsAnother) NEW_ID.also { entries += it } else HELD_ID
                    json("""{"success":true,"error":null,"detail":"$DETAIL","data":{"hash":"${HASH.hex}","torrent_id":$id,"auth_id":"$ACCOUNT"}}""", HttpStatusCode.OK, "Date" to DATE)
                }
                "torrents/requestdl" -> if (request.url.parameters["torrent_id"]?.toLongOrNull()?.let { it in entries } == true) {
                    json("""{"success":true,"error":null,"detail":"$DETAIL","data":"${cdn.link()}"}""")
                } else {
                    notFound()
                }
                "torrents/controltorrent" -> {
                    val body = (request.body as TextContent).text
                    deletes += body
                    if (entries.remove(Json.parseToJsonElement(body).jsonObject.getValue("torrent_id").jsonPrimitive.long)) {
                        if (cuts) cdn.cut = true
                        json("""{"success":true,"error":null,"detail":"$DETAIL","data":null}""")
                    } else {
                        notFound()
                    }
                }
                "torrents/checkcached" -> json("""{"success":true,"error":null,"detail":"$DETAIL","data":[{"name":"$RELEASE","size":734005248,"hash":"${HASH.hex}"}]}""")
                else -> json("""{"success":false,"error":"ENDPOINT_NOT_FOUND","detail":"$DETAIL","data":null}""", HttpStatusCode.NotFound)
            }
        }

        private fun MockRequestHandleScope.notFound() =
            json("""{"success":false,"error":"ITEM_NOT_FOUND","detail":"$DETAIL","data":null}""", HttpStatusCode.NotFound)

        private fun entry(id: Long): String {
            val hash = if (id == OTHER_ID) OTHER.hex else HASH.hex
            val created = if (id == NEW_ID) "2026-11-06T10:00:01Z" else "2026-11-05T13:13:30Z"
            return """{"id":$id,"auth_id":"$ACCOUNT","hash":"$hash","name":"$RELEASE","created_at":"$created","updated_at":"$created",""" +
                """"files":[{"id":$FILE_ID,"name":"$RELEASE/$RELEASE.mp4","size":734003200,"short_name":"$RELEASE.mp4"},""" +
                """{"id":${FILE_ID + 1},"name":"$RELEASE/$RELEASE.nfo","size":2048,"short_name":"$RELEASE.nfo"}]}"""
        }
    }

    /**
     * A CDN on the loopback address: every read gets the whole file as a 206 from byte 0, written until it is [cut]; then
     * the stream stops short and a new read gets 403, as a CDN dropping a deleted item would.
     */
    private class FakeCdn : AutoCloseable {
        @Volatile
        var cut = false
        val ranges = CopyOnWriteArrayList<String?>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/cdn/") { exchange ->
                try {
                    serve(exchange)
                } catch (e: IOException) {
                    // The reader went, or the stream was cut.
                } finally {
                    exchange.close()
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }

        fun link() = "http://127.0.0.1:${server.address.port}/cdn/$LINK_TOKEN/$RELEASE.mp4"

        private fun serve(exchange: HttpExchange) {
            ranges += exchange.requestHeaders.getFirst("Range")
            if (cut) {
                exchange.sendResponseHeaders(403, -1)
                return
            }
            exchange.responseHeaders.add("Content-Range", "bytes 0-${SIZE - 1}/$SIZE")
            exchange.sendResponseHeaders(206, SIZE)
            val chunk = ByteArray(64 * 1024)
            val out = exchange.responseBody
            var left = SIZE
            while (left > 0 && !cut) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }

        override fun close() {
            server.stop(0)
            (server.executor as ExecutorService).shutdownNow()
        }

        companion object {
            const val SIZE = 256L * 1024 * 1024
        }
    }

    private companion object {
        const val KEY = "LIVEKEYMARKER0000"
        const val LINK_TOKEN = "LINKTOKENMARKER"
        const val ACCOUNT = "quay.viewer@example.org"
        const val CUSTOMER = "900000042"
        const val TRANSFER = "TRANSFERIDMARKER"
        const val RELEASE = "Quiet.Orbit.2019.1080p.WEB-DL"
        const val DETAIL = "The service's own words"
        const val HELD_ID = 987654321L
        const val NEW_ID = 876543219L
        const val FILE_ID = 765432198L

        /** createtorrent's Date: 20 h 46 min 30 s after the held entry's created_at. */
        const val DATE = "Fri, 06 Nov 2026 10:00:00 GMT"
        const val OTHER_ID = 55L
        val HASH = InfoHash.of("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f")
        val OTHER = InfoHash.of("0123456789abcdef0123456789abcdef01234567")
    }
}

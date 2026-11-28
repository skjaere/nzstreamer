package io.skjaere.usenet.mockseed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake Wikipedia (L-B12): invented UFC articles and List of UFC events in the shapes the Sportarr plugin's reader takes
 * (L-B2), read here as raw HTTP and HTML, as the shapes are the point; its switches and counts through the fake Sportarr's
 * control path, where they live. A hold is waited for through the fake's seam, never slept out.
 */
class FakeWikipediaServerTest {
    private val anchor = LocalDate.of(2026, 11, 22)
    private val catalog = SportsCatalog.load()
    private val cards = WikipediaCards.load()
    private val holds = Semaphore(0)
    private val wiki = FakeWikipediaServer(catalog, anchor, port = 0, onHold = { holds.release() })
    private val sportarr = FakeSportarrServer(catalog, anchor, port = 0, wiki = wiki)
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun stop() {
        sportarr.close()
        wiki.close()
    }

    private fun request(path: String, gzip: Boolean, agent: String?, port: Int): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().apply {
            if (gzip) header("Accept-Encoding", "gzip")
            agent?.let { header("User-Agent", it) }
        }.build()

    /** [path] from the fake Wikipedia at [port], its body as sent (gzipped where [gzip] asked for it). */
    private fun get(path: String, gzip: Boolean = false, agent: String? = AGENT, port: Int = wiki.port): HttpResponse<ByteArray> =
        http.send(request(path, gzip, agent, port), HttpResponse.BodyHandlers.ofByteArray())

    /** [path]'s page, which must answer 200 as HTML. */
    private fun page(path: String, port: Int = wiki.port): String {
        val answer = get(path, port = port)
        assertEquals(200, answer.statusCode(), path)
        assertTrue(answer.headers().firstValue("Content-Type").get().startsWith("text/html"), path)
        return answer.body().decodeToString()
    }

    private fun status(path: String) = get(path).statusCode()

    private fun control(path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${sportarr.port}/control/$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun switch(path: String) = assertEquals(200, control(path).statusCode(), path)

    private fun counts(): JsonObject = Json.parseToJsonElement(control("counts").body()).jsonObject

    private fun article(event: Int) = cards.articles.single { it.event == event }

    /** [event]'s bouts as its table lists them: the main card first, each part's last bout at its top. */
    private fun tableOrder(event: Int): List<WikiBout> = article(event).parts.reversed().flatMap { it.bouts.reversed() }

    /** The date [event] starts on in UTC at [at], as the fake Sportarr dates it. */
    private fun dateOf(event: Int, at: LocalDate = anchor): LocalDate =
        catalog.leagues(at).flatMap { it.events }.single { it.id == event }.start.atZone(ZoneOffset.UTC).toLocalDate()

    @Test
    fun `UFC 999 is served at its title after the event, in its results form under a Parsoid heading`() {
        val html = page("/wiki/UFC_999")
        assertEquals("$CANONICAL/wiki/UFC_999", canonical(html))
        assertContains(html, """<h2 id="Results">Results</h2>""")
        val rows = cardTable(html)!!
        assertEquals(listOf("Main card (Pay-per-view)", "Preliminary card (Kestrel Sports)", "Early preliminary card (Kestrel+)"), headers(rows))
        assertEquals(listOf(5, 4, 3), boutsPerHeader(rows), "UFC 999's card: three early prelims, four prelims, five on the main card")
        assertTrue(rows.bouts().all { it.size == 8 }, "weight, two fighters and the separator, method, round, time and notes")
        // Its lead and Bonus awards name winners, so a reader that took either would show it.
        assertContains(html, "Halde def. Quessel")
        assertContains(html, """id="Bonus_awards"""")
        assertContains(html, "Fight of the Night:</b> Halde vs. Quessel")
        assertFalse("Announced_bouts" in html, "an event's announced bouts are gone once it has been fought")
    }

    @Test
    fun `the fight night is served at its title before the event, in its pre-event form under a legacy heading, with announced bouts`() {
        val html = page("/wiki/UFC_Fight_Night:_Brisk_vs._Loam")
        assertEquals("$CANONICAL/wiki/UFC_Fight_Night:_Brisk_vs._Loam", canonical(html))
        assertContains(html, """<h2><span class="mw-headline" id="Fight_card">Fight card</span>""")
        val rows = cardTable(html)!!
        assertEquals(listOf("Main card (Kestrel Sports)", "Preliminary card (Kestrel Sports)", "Early preliminary card (Kestrel+)"), headers(rows))
        assertEquals(listOf(4, 4, 1), boutsPerHeader(rows), "one early prelims bout, though Sportarr gives the fight night two parts")
        rows.bouts().forEach { bout ->
            assertEquals("vs.", bout[2])
            assertEquals(listOf("", "", ""), bout.subList(4, 7), "no method, round or time before the event")
        }
        assertEquals(tableOrder(1902).map { it.fighters }, rows.bouts().map { listOf(it[1], it[3]) }, "the fighters as the table lists them")
        assertContains(html, """<span class="mw-headline" id="Announced_bouts">""")
        assertContains(html, "<li>Lightweight bout: Ambrel vs. Tarnoy</li>")
        assertFalse("Bonus_awards" in html)
        assertFalse("def." in html, "nothing of a result before the event")
    }

    @Test
    fun `the fight night's number is served as Wikipedia serves a redirect, at the asked address with its target's canonical link`() {
        val target = page("/wiki/UFC_Fight_Night:_Brisk_vs._Loam")
        val redirect = page("/wiki/UFC_Fight_Night_888")
        assertEquals("$CANONICAL/wiki/UFC_Fight_Night:_Brisk_vs._Loam", canonical(redirect))
        assertContains(redirect, "Redirected from")
        assertEquals(cardTable(target), cardTable(redirect))
        assertFalse("Redirected from" in target)
    }

    @Test
    fun `any other title is a 404, as is anything outside wiki, a query is refused and only GET is served`() {
        listOf(
            "/wiki/UFC_Fight_Night_888_Brisk_vs_Loam", "/wiki/UFC_999:_Quessel_vs._Halde", "/wiki/UFC_998", "/wiki/UFC_1000",
            "/wiki/UFC_999_(Tarnholm)", "/wiki/UFC_on_Kestrel:_Brisk_vs._Loam", "/wiki/", "/w/index.php", "/api/rest_v1/page/html/UFC_999", "/",
        ).forEach { assertEquals(404, status(it), it) }
        assertEquals(200, status("/wiki/UFC%20999"), "a title's space percent-encoded is its underscore")
        assertEquals(400, status("/wiki/UFC_999?action=raw"))
        assertEquals(400, status("/w/index.php?title=UFC_999&oldid=1"))
        val post = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${wiki.port}/wiki/UFC_999")).POST(HttpRequest.BodyPublishers.ofString("")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, post.statusCode())
    }

    @Test
    fun `the results form moves every winner to the left with def, and lists the card otherwise as the pre-event form does`() {
        listOf(1901, 1902).forEach { event ->
            switch("wiki-form?event=$event&form=pre")
            val preHtml = page(path(event))
            switch("wiki-form?event=$event&form=results")
            val resultsHtml = page(path(event))
            val pre = cardTable(preHtml)!!
            val results = cardTable(resultsHtml)!!
            assertEquals(headers(pre), headers(results), "$event")
            assertEquals(boutsPerHeader(pre), boutsPerHeader(results), "$event")
            val bouts = tableOrder(event)
            assertEquals(bouts.size, results.bouts().size)
            bouts.zip(pre.bouts().zip(results.bouts())).forEach { (bout, rows) ->
                val (before, after) = rows
                assertEquals(before[0], after[0], "$event: the weight class")
                assertEquals(setOf(before[1], before[3]), setOf(after[1], after[3]), "$event: the same two fighters")
                assertEquals(bout.fighters, listOf(before[1], before[3]), "$event: as the pre-event table lists them")
                assertEquals("vs.", before[2])
                val winner = bout.winner
                if (winner == null) {
                    assertEquals("vs." to before[1], after[2] to after[1], "$event: a draw or a no contest keeps its order")
                } else {
                    assertEquals("def." to bout.fighters[winner], after[2] to after[1], "$event: the winner on the left")
                }
                assertTrue(after.subList(4, 7).all { it.isNotEmpty() }, "$event: a method, round and time after the event")
                assertEquals(bout.resultNote, after[7])
                assertEquals(bout.note, before[7])
            }
            assertContains(resultsHtml, """id="Results"""")
            assertContains(preHtml, """id="Fight_card"""")
            assertContains(resultsHtml, "Bonus_awards")
            assertFalse("Bonus_awards" in preHtml)
        }
    }

    /**
     * L-B12's winners, so a reader that kept the table's order fails: the main event's winner on the left against the order
     * shown (the title's), the later fighter by the alphabet the winner in most other bouts, and the draw and the no contest
     * listed against the alphabet.
     */
    @Test
    fun `the results form's left column gives the winners away, against the order the plugin shows`() {
        listOf(1901 to ("Quessel" to "Halde"), 1902 to ("Brisk" to "Loam")).forEach { (event, shown) ->
            switch("wiki-form?event=$event&form=results")
            val rows = cardTable(page(path(event)))!!
            val main = rows.dropWhile { it.header?.startsWith("Main card") != true }.drop(1).first()
            val (left, right) = bare(main.cells[1]) to bare(main.cells[3])
            assertEquals(shown.second to shown.first, left to right, "$event: the title's second side won the main event")
            val others = rows.bouts().filter { it !== main.cells }
            val won = others.filter { it[2] == "def." }
            assertTrue(won.count { bare(it[1]) > bare(it[3]) } * 2 >= won.size, "$event: the later fighter wins at least half")
            val even = others.filter { it[2] == "vs." }
            assertEquals(listOf("Draw", "No Contest"), even.map { it[4].substringBefore(" (") }.sorted(), "$event: one draw, one no contest")
            assertTrue(even.all { bare(it[1]) > bare(it[3]) }, "$event: listed against the alphabet")
        }
        // UFC 999's champions keep their marks, beside the loser of each title fight.
        val ufc999 = cardTable(page("/wiki/UFC_999"))!!.bouts()
        assertEquals(listOf("Loam def. Brisk (c)", "Halde def. Quessel (c)"), ufc999.filter { "(c)" in it[3] }.map { "${it[1]} ${it[2]} ${it[3]}" }.reversed())
    }

    @Test
    fun `unsplit lists the whole card under one Fight card header, and bare has no card section`() {
        listOf(1901, 1902).forEach { event ->
            switch("wiki-form?event=$event&form=unsplit")
            val unsplit = page(path(event))
            assertContains(unsplit, """id="Fight_card">Fight card<""")
            val rows = cardTable(unsplit)!!
            assertEquals(listOf("Fight card"), headers(rows))
            assertEquals(tableOrder(event).map { it.fighters }, rows.bouts().map { listOf(it[1], it[3]) })
            assertTrue(rows.bouts().all { it[2] == "vs." })

            switch("wiki-form?event=$event&form=bare")
            val bare = page(path(event))
            assertNull(cardTable(bare), "$event: no card heading")
            assertEquals(1, Regex("<table").findAll(bare).count(), "$event: the infobox alone")
            assertContains(bare, dateOf(event).toString(), message = "$event: still dated")
            assertContains(bare, "Announced_bouts")
            assertFalse("def." in bare)
        }
    }

    @Test
    fun `missing answers 404 at every title of the event and leaves it out of the index`() {
        switch("wiki-form?event=1901&form=missing")
        assertEquals(404, status("/wiki/UFC_999"))
        assertFalse("UFC 999:" in page(INDEX_PATH))
        assertEquals(200, status("/wiki/UFC_Fight_Night_888"), "the other event as it was")

        switch("wiki-form?event=1902&form=missing")
        listOf("/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "/wiki/UFC_Fight_Night_888").forEach { assertEquals(404, status(it), it) }
        assertFalse("Brisk vs. Loam" in page(INDEX_PATH))
        assertEquals(4, indexTables(page(INDEX_PATH)).sumOf { it.rows.size }, "the invented others alone")

        switch("wiki-form?event=1901&on=false")
        assertEquals(200, status("/wiki/UFC_999"))
        assertEquals(404, status("/wiki/UFC_Fight_Night_888"), "on=false with an event turns that event's alone back")
        switch("wiki-form?on=false")
        assertEquals(200, status("/wiki/UFC_Fight_Night_888"))
    }

    @Test
    fun `moved serves the event under another title alone, which only the index links`() {
        switch("wiki-form?event=1901&form=moved")
        assertEquals(404, status("/wiki/UFC_999"))
        val moved = page("/wiki/UFC_999_(Tarnholm)")
        assertEquals("$CANONICAL/wiki/UFC_999_(Tarnholm)", canonical(moved))
        assertContains(moved, """id="Results"""", message = "its usual form")
        assertEquals(12, cardTable(moved)!!.bouts().size)
        val past = indexTables(page(INDEX_PATH)).single { it.header.first() == "#" }
        val row = past.rows.single { it.link?.first == "/wiki/UFC_999_(Tarnholm)" }
        assertEquals("UFC 999 (Tarnholm)", row.link!!.third)
        assertTrue(past.rows.none { it.link?.first == "/wiki/UFC_999" })

        switch("wiki-form?event=1902&form=moved")
        listOf("/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "/wiki/UFC_Fight_Night_888").forEach { assertEquals(404, status(it), it) }
        val night = page("/wiki/UFC_on_Kestrel:_Brisk_vs._Loam")
        assertEquals("$CANONICAL/wiki/UFC_on_Kestrel:_Brisk_vs._Loam", canonical(night))
        assertContains(night, """id="Fight_card"""", message = "its usual form")
        val scheduled = indexTables(page(INDEX_PATH)).single { it.header.first() == "Event" }
        assertEquals("UFC on Kestrel: Brisk vs. Loam", scheduled.rows.single { it.link?.first == "/wiki/UFC_on_Kestrel:_Brisk_vs._Loam" }.link!!.third)

        switch("wiki-form?event=1901&form=moved&on=false")
        assertEquals(200, status("/wiki/UFC_999"))
        assertEquals(404, status("/wiki/UFC_999_(Tarnholm)"), "a moved title is served only while moved")
    }

    @Test
    fun `wiki-form refuses an event with no article, an unknown form and a form without its event`() {
        listOf(
            "wiki-form?event=2001&form=pre", "wiki-form?event=soon&form=pre", "wiki-form?event=1901&form=sideways", "wiki-form?event=1901",
            "wiki-form?form=pre", "wiki-form",
        ).forEach { assertEquals(400, control(it).statusCode(), it) }
        assertEquals(200, status("/wiki/UFC_999"))
    }

    @Test
    fun `List of UFC events has the scheduled and the past events' tables in L-B2's shape, newest first, a red link among them`() {
        val tables = indexTables(page(INDEX_PATH))
        assertEquals(listOf(listOf("Event", "Date", "Venue", "Location"), listOf("#", "Event", "Date", "Venue", "Location", "Attendance")), tables.map { it.header })
        val (scheduled, past) = tables
        assertEquals(
            listOf(
                Triple("/w/index.php?title=UFC_1000&amp;action=edit&amp;redlink=1", "new", "UFC 1000: Pellicot vs. Velmar"),
                Triple("/wiki/UFC_Fight_Night:_Tollerup_vs._Kjelsen", "", "UFC Fight Night: Tollerup vs. Kjelsen"),
                Triple("/wiki/UFC_Fight_Night:_Brisk_vs._Loam", "", "UFC Fight Night: Brisk vs. Loam"),
            ),
            scheduled.rows.map { it.link },
        )
        assertEquals(listOf("December 26, 2026", "December 5, 2026", "November 28, 2026"), scheduled.rows.map { it.cells[1] }, "full month names")
        assertEquals(
            listOf(
                Triple("/wiki/UFC_999", "", "UFC 999: Quessel vs. Halde"),
                Triple("/wiki/UFC_Fight_Night:_Ambrel_vs._Kestvik", "", "UFC Fight Night: Ambrel vs. Kestvik"),
                Triple("/wiki/UFC_998", "", "UFC 998: Tarnoy vs. Varnholt"),
            ),
            past.rows.map { it.link },
        )
        assertEquals(listOf("761", "760", "759"), past.rows.map { it.cells[0] })
        assertEquals(listOf("Nov 21, 2026", "Nov 14, 2026", "Nov 7, 2026"), past.rows.map { it.cells[2] }, "short month names")
        assertEquals(listOf("18,452", "9,876", "17,310"), past.rows.map { it.cells[5] })
        assertEquals("$CANONICAL/wiki/List_of_UFC_events", canonical(page(INDEX_PATH)))
    }

    @Test
    fun `every infobox and index row is dated from the anchor at the catalogue's day and time, never from now`() {
        val html = page("/wiki/UFC_999")
        assertContains(html, """November 21, 2026<span style="display:none">&#160;(<span class="bday dtstart published updated">2026-11-21</span>)</span>""")
        assertContains(page("/wiki/UFC_Fight_Night_888"), ">2026-11-28<")
        // Across a new year, far from today: UFC 999 on the last day of the old one.
        val later = LocalDate.of(2031, 1, 1)
        FakeWikipediaServer(catalog, later, port = 0).use { other ->
            val night = dateOf(1902, later)
            assertEquals(LocalDate.of(2030, 12, 31), dateOf(1901, later))
            assertContains(page("/wiki/UFC_999", other.port), """December 31, 2030<span style="display:none">&#160;(<span class="bday dtstart published updated">2030-12-31</span>)</span>""")
            assertContains(page("/wiki/UFC_Fight_Night_888", other.port), ">$night<")
            val (scheduled, past) = indexTables(page(INDEX_PATH, other.port))
            assertEquals(listOf("February 4, 2031", "January 14, 2031", "January 7, 2031"), scheduled.rows.map { it.cells[1] })
            assertEquals(listOf("Dec 31, 2030", "Dec 24, 2030", "Dec 17, 2030"), past.rows.map { it.cells[2] })
        }
    }

    @Test
    fun `an answer is gzipped only when the request asks for gzip, and the counts say which asked`() {
        listOf("/wiki/UFC_999", INDEX_PATH, "/wiki/UFC_998").forEach { path ->
            val plain = get(path)
            val zipped = get(path, gzip = true)
            assertEquals(plain.statusCode(), zipped.statusCode(), path)
            assertTrue(plain.headers().firstValue("Content-Encoding").isEmpty, path)
            assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").get(), path)
            assertEquals(plain.body().decodeToString(), GZIPInputStream(zipped.body().inputStream()).readBytes().decodeToString(), path)
        }
        fun encoded(asked: String) = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${wiki.port}/wiki/UFC_999")).header("Accept-Encoding", asked).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).headers().firstValue("Content-Encoding").orElse(null)
        assertEquals("gzip", encoded("br, GZIP"))
        assertNull(encoded("identity"))
        assertNull(encoded("gzip;q=0"))
        val gzips = counts().getValue("wiki").jsonObject.getValue("requests").jsonArray.map { it.jsonObject.getValue("gzip").jsonPrimitive.boolean }
        assertEquals(listOf(false, true, false, true, false, true, true, false, false), gzips)
    }

    @Test
    fun `counts list each request with its path, event, form, status, User-Agent, gzip and cookie, and reset clears them`() {
        get("/wiki/UFC_999", gzip = true, agent = "FakeWikiTest/1 (a test)")
        get("/wiki/UFC_Fight_Night_888", agent = "FakeWikiTest/2")
        get("/wiki/UFC_Fight_Night_888_Brisk_vs_Loam")
        get(INDEX_PATH)
        get("/wiki/UFC_999")
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${wiki.port}/wiki/UFC_999")).header("Cookie", "fakeWikiVisit=1").header("User-Agent", AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val wikiCounts = counts().getValue("wiki").jsonObject
        assertEquals(
            mapOf("/wiki/UFC_999" to 3, "/wiki/UFC_Fight_Night_888" to 1, "/wiki/UFC_Fight_Night_888_Brisk_vs_Loam" to 1, "/wiki/List_of_UFC_events" to 1),
            wikiCounts.getValue("paths").jsonObject.mapValues { it.value.jsonPrimitive.int },
        )
        val requests = wikiCounts.getValue("requests").jsonArray.map { it.jsonObject }
        fun JsonObject.text(name: String) = getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.content }
        assertEquals(
            listOf(
                listOf("/wiki/UFC_999", "1901", "results", "200", "FakeWikiTest/1 (a test)", "true", "false"),
                listOf("/wiki/UFC_Fight_Night_888", "1902", "pre", "200", "FakeWikiTest/2", "false", "false"),
                listOf("/wiki/UFC_Fight_Night_888_Brisk_vs_Loam", null, null, "404", AGENT, "false", "false"),
                listOf("/wiki/List_of_UFC_events", null, null, "200", AGENT, "false", "false"),
                listOf("/wiki/UFC_999", "1901", "results", "200", AGENT, "false", "false"),
                listOf("/wiki/UFC_999", "1901", "results", "200", AGENT, "false", "true"),
            ),
            requests.map { r -> listOf("path", "event", "form", "status", "userAgent", "gzip", "cookie").map { r.text(it) } },
        )
        assertTrue(requests.all { it.getValue("query") is JsonNull })
        assertEquals(1, get("/wiki/UFC_999").headers().allValues("Set-Cookie").size, "a cookie set, as Wikipedia sets them")
        // The fake Sportarr's own counts as they were.
        assertEquals(setOf("routes", "pages", "withoutKey", "keyInQuery", "wiki"), counts().keys)

        switch("reset")
        val cleared = counts().getValue("wiki").jsonObject
        assertEquals(emptyMap(), cleared.getValue("paths").jsonObject)
        assertEquals(0, cleared.getValue("requests").jsonArray.size)
    }

    @Test
    fun `wiki-fail answers every Wikipedia request with its status until it is switched off`() {
        listOf(503, 403, 429).forEach { failing ->
            switch("wiki-fail?status=$failing")
            listOf("/wiki/UFC_999", INDEX_PATH, "/wiki/UFC_Fight_Night_888", "/wiki/UFC_998").forEach { assertEquals(failing, status(it), "$failing $it") }
            switch("wiki-fail?on=false")
            assertEquals(200, status("/wiki/UFC_999"))
        }
        listOf("wiki-fail?status=500", "wiki-fail?status=soon", "wiki-fail").forEach { assertEquals(400, control(it).statusCode(), it) }
        val statuses = counts().getValue("wiki").jsonObject.getValue("requests").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.int }
        assertEquals(listOf(503, 503, 503, 503, 200, 403, 403, 403, 403, 200, 429, 429, 429, 429, 200), statuses)
    }

    @Test
    fun `wiki-hold holds every answer until it is switched off, or a reset`() {
        switch("wiki-hold")
        val held = http.sendAsync(request("/wiki/UFC_999", false, AGENT, wiki.port), HttpResponse.BodyHandlers.ofString())
        assertTrue(holds.tryAcquire(10, TimeUnit.SECONDS), "the request reached the hold")
        assertFalse(held.isDone)
        val asked = counts().getValue("wiki").jsonObject.getValue("requests").jsonArray.single().jsonObject
        assertEquals("/wiki/UFC_999" to JsonNull, asked.getValue("path").jsonPrimitive.content to asked.getValue("status"), "counted, not yet answered")
        switch("wiki-hold?on=false")
        assertEquals(200, held.get(10, TimeUnit.SECONDS).statusCode())

        switch("wiki-hold?on=true")
        val again = http.sendAsync(request(INDEX_PATH, false, AGENT, wiki.port), HttpResponse.BodyHandlers.ofString())
        assertTrue(holds.tryAcquire(10, TimeUnit.SECONDS))
        assertFalse(again.isDone)
        switch("reset")
        assertEquals(200, again.get(10, TimeUnit.SECONDS).statusCode())
        assertEquals(200, status("/wiki/UFC_999"), "a reset leaves nothing held")
        assertEquals(0, holds.availablePermits())
    }

    @Test
    fun `the lease and the reset are the fake Sportarr's, one set for both fakes`() {
        switch("take?by=phone34-test")
        switch("wiki-form?event=1901&form=bare")
        switch("wiki-form?event=1902&form=missing")
        switch("wiki-fail?status=403")
        get("/wiki/UFC_999")
        switch("reset")
        assertEquals(200, status("/wiki/UFC_999"))
        assertNotNull(cardTable(page("/wiki/UFC_999")), "every form back as it was")
        assertEquals(200, status("/wiki/UFC_Fight_Night_888"))
        assertEquals(409, control("take?by=tv36-test").statusCode(), "a reset leaves the holder as it is")
        // A fake Sportarr without the fake Wikipedia has none of its switches or counts.
        FakeSportarrServer(catalog, anchor, port = 0).use { alone ->
            fun alone(path: String) = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${alone.port}/control/$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            listOf("wiki-form?event=1901&form=pre", "wiki-fail?status=503", "wiki-hold").forEach { assertEquals(404, alone(it).statusCode(), it) }
            assertFalse("wiki" in Json.parseToJsonElement(alone("counts").body()).jsonObject)
        }
    }

    @Test
    fun `it listens on the loopback address alone, and its canonical links name a host that never resolves`() {
        assertTrue(wiki.address.isLoopbackAddress)
        assertEquals(anchor, wiki.anchor)
        assertTrue(CANONICAL.endsWith(".invalid"))
        assertEquals("$CANONICAL/wiki/UFC_999", canonical(page("/wiki/UFC_999")))
    }

    private fun path(event: Int) = "/wiki/" + article(event).title.replace(' ', '_')

    private companion object {
        const val AGENT = "FakeWikiTest/0 (a test)"
        const val CANONICAL = FakeWikipediaServer.CANONICAL_BASE
        const val INDEX_PATH = "/wiki/List_of_UFC_events"

        /** A card table's row as this test reads the fake's HTML: a part's [header], or a bout's eight [cells]' texts. */
        data class CardRow(val header: String?, val cells: List<String>)

        /** An index table: its [header] cells' texts, and its [rows]. */
        data class IndexTable(val header: List<String>, val rows: List<IndexRow>)

        /** An index row's cells' texts, and its Event cell's first [link]: its href and class as written, and its text. */
        data class IndexRow(val cells: List<String>, val link: Triple<String, String, String>?)

        val HEADING = Regex("""<(?:h2|span class="mw-headline") id="(?:Fight_card|Results|Card)[^"]*"""")
        val NEXT_SECTION = Regex("""<h2[ >]""")
        val TABLE = Regex("""<table class="wikitable[^"]*">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
        val ROW = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        val CELL = Regex("""<(td|th)([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        val LINK = Regex("""<a href="([^"]*)"( class="([^"]*)")?[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val SUP = Regex("""<sup[^>]*>.*?</sup>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("""<[^>]+>""")
        val CANONICAL_LINK = Regex("""<link rel="canonical" href="([^"]*)">""")

        fun canonical(html: String): String? = CANONICAL_LINK.find(html)?.groupValues?.get(1)

        /** A cell's text: its references and tags taken off, its entities decoded, its whitespace collapsed. */
        fun text(cell: String): String = TAG.replace(SUP.replace(cell, ""), "")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#160;", " ").replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ").trim()

        /** The rows of the first table after the card's heading and before the next section; null where there is none. */
        fun cardTable(html: String): List<CardRow>? {
            val heading = HEADING.find(html) ?: return null
            val end = NEXT_SECTION.find(html, heading.range.last)?.range?.first ?: html.length
            val open = html.indexOf("<table", heading.range.last).takeIf { it in 0 until end } ?: return null
            return ROW.findAll(html.substring(open, html.indexOf("</table>", open))).mapNotNull { row ->
                val cells = CELL.findAll(row.groupValues[1]).toList()
                val header = cells.singleOrNull()?.takeIf { it.groupValues[1] == "th" && """colspan="8"""" in it.groupValues[2] }
                when {
                    header != null -> CardRow(text(header.groupValues[3]), emptyList())
                    cells.isNotEmpty() && cells.all { it.groupValues[1] == "td" } -> CardRow(null, cells.map { text(it.groupValues[3]) })
                    else -> null
                }
            }.toList()
        }

        fun headers(rows: List<CardRow>) = rows.mapNotNull { it.header }

        fun List<CardRow>.bouts() = filter { it.header == null }.map { it.cells }

        fun boutsPerHeader(rows: List<CardRow>): List<Int> =
            rows.fold(emptyList()) { counts, row -> if (row.header != null) counts + 0 else counts.dropLast(1) + (counts.last() + 1) }

        /** A fighter's name without a champion's mark. */
        fun bare(name: String) = name.removeSuffix(" (c)").removeSuffix(" (ic)")

        /** Every table of the index page: its header row's texts, and each row's cells and Event cell's first link. */
        fun indexTables(html: String): List<IndexTable> = TABLE.findAll(html).map { table ->
            val rows = ROW.findAll(table.groupValues[1]).map { CELL.findAll(it.groupValues[1]).toList() }.toList()
            val header = rows.first().map { text(it.groupValues[3]) }
            val event = header.indexOf("Event")
            IndexTable(
                header,
                rows.drop(1).map { cells ->
                    val link = LINK.find(cells[event].groupValues[3])?.let { Triple(it.groupValues[1], it.groupValues[3], text(it.groupValues[4])) }
                    IndexRow(cells.map { text(it.groupValues[3]) }, link)
                },
            )
        }.toList()
    }
}

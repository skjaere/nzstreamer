package io.skjaere.usenet.mockseed

import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The fake Sportarr (L655): Sportarr 4.1.12's routes as this slice reads them, over the catalogue's invented library, read
 * here as raw HTTP and JSON, as the shapes are the point. Its lease's clock is moved on by a test ([skew]) and its
 * pauses are recorded, never waited out.
 */
class FakeSportarrServerTest {
    private val anchor = LocalDate.of(2026, 11, 22)
    private val catalog = SportsCatalog.load()
    private val lines = CopyOnWriteArrayList<String>()
    private val paused = CopyOnWriteArrayList<Long>()
    private val skew = AtomicLong()
    private val server = FakeSportarrServer(
        catalog, anchor, port = 0, log = { lines += it }, leaseMs = 60_000,
        now = { System.nanoTime() / 1_000_000 + skew.get() }, wallClock = { NOW }, pause = { paused += it },
    )
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun stop() = server.close()

    /** [path] with the key in X-Api-Key, or [query]'d, or none ([key] null). */
    private fun get(path: String, key: String? = KEY): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).GET().apply { key?.let { header("X-Api-Key", it) } }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun control(path: String): HttpResponse<String> = get("/control/$path", key = null)

    private fun ok(path: String): JsonElement {
        val answer = get(path)
        assertEquals(200, answer.statusCode(), "$path: ${answer.body().take(200)}")
        return Json.parseToJsonElement(answer.body())
    }

    private fun page(league: Int, query: String): JsonObject = ok("/api/leagues/$league/events?$query").jsonObject

    private fun JsonElement.ids(): List<Int> = jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }

    private fun JsonObject.records() = getValue("records").ids()

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.int

    private fun leagues(): List<JsonObject> = ok("/api/leagues").jsonArray.map { it.jsonObject }

    /** The catalogue's events of [league] as Sportarr orders them: newest first, then by id, from the newest. */
    private fun newestFirst(league: Int, all: Boolean = false): List<Int> = catalog.leagues(anchor).single { it.id == league }.events
        .filter { all || it.listed }
        .sortedWith(compareByDescending<SportsEvent> { it.start }.thenByDescending { it.id }).map { it.id }

    @Test
    fun `the key is taken from X-Api-Key or the apikey query, and any other or none is refused with 401, but for health`() {
        assertEquals(200, get("/api/leagues").statusCode())
        assertEquals(200, get("/api/leagues?apikey=$KEY", key = null).statusCode())
        assertEquals(200, get("/api/leagues/1/events?apikey=$KEY&page=1", key = null).statusCode())
        listOf("/api/system/status", "/api/leagues", "/api/leagues/1/events", "/api/leagues/1/events?page=1").forEach { path ->
            assertEquals(401, get(path, key = null).statusCode(), path)
            assertEquals(401, get(path, key = "not-the-key").statusCode(), path)
            assertEquals(401, get("$path${if ('?' in path) '&' else '?'}apikey=not-the-key", key = null).statusCode(), path)
        }
        assertEquals(200, get("/api/health", key = null).statusCode())
    }

    @Test
    fun `a league's page comes newest first, ties by id descending, in Sportarr's envelope`() {
        val first = page(1, "page=1&pageSize=5")
        assertEquals(listOf(1, 5, 17, 4), listOf("page", "pageSize", "totalRecords", "totalPages").map { first.number(it) })
        // Seven games kick off at 15:00 the day before: by id, from the highest.
        assertEquals(listOf(1010, 1009, 1008, 1007, 1006), page(1, "page=2&pageSize=5").records())
        assertEquals(listOf(1005, 1004, 1003, 1001, 1002), page(1, "page=3&pageSize=5").records())
        val walked = (1..4).flatMap { page(1, "page=$it&pageSize=5").records() }
        assertEquals(newestFirst(1), walked)
        assertEquals(listOf(1015, 1014, 1013, 1012, 1011), walked.take(5))
        val past = page(1, "page=5&pageSize=5")
        assertEquals(emptyList(), past.records(), "a page past the last is empty")
        assertEquals(17 to 4, past.number("totalRecords") to past.number("totalPages"))
    }

    @Test
    fun `a page below 1 reads as 1, the size is 100 by default and held between 1 and 1000, and none has no pages`() {
        assertEquals(1 to 5, page(1, "page=0&pageSize=5").let { it.number("page") to it.number("pageSize") })
        assertEquals(1, page(1, "page=-3&pageSize=5").number("page"))
        assertEquals(1 to 100, page(1, "page=1").let { it.number("page") to it.number("pageSize") })
        assertEquals(17, page(1, "page=1").records().size)
        assertEquals(1 to 1, page(1, "pageSize=0").let { it.number("page") to it.number("pageSize") }, "pageSize alone pages too")
        assertEquals(17, page(1, "pageSize=0").number("totalPages"))
        assertEquals(1000, page(1, "page=1&pageSize=5000").number("pageSize"))
        val none = page(14, "page=1&pageSize=1000")
        assertEquals(listOf(0, 0), listOf(none.number("totalRecords"), none.number("totalPages")))
        assertEquals(emptyList(), none.records())
    }

    @Test
    fun `without page or pageSize, a league's events come as a plain array, newest first`() {
        val whole = ok("/api/leagues/1/events")
        assertIs<JsonArray>(whole)
        assertEquals(newestFirst(1), whole.ids())
    }

    @Test
    fun `the page lists what the league's page shows, Keep every game and fighting leagues all they store, and showAll everything`() {
        assertEquals(listOf(1704, 1703, 1713, 1712), ok("/api/leagues/8/events").ids(), "MotoGP: the sessions chosen")
        assertEquals(newestFirst(8, all = true), ok("/api/leagues/8/events?showAll=true").ids())
        assertEquals(7, page(8, "page=1&pageSize=1000&showAll=true").number("totalRecords"))
        assertEquals(newestFirst(6), ok("/api/leagues/6/events").ids())
        assertEquals(4, ok("/api/leagues/6/events?showAll=true").jsonArray.size)
        // Keep every game: every stored game, those not on the league's page too.
        assertEquals(newestFirst(2, all = true), ok("/api/leagues/2/events").ids())
        assertEquals(5, ok("/api/leagues/2/events").jsonArray.size)
        // A fighting league, whatever its scope: every card, monitored or not.
        assertEquals(listOf(1303, 1302, 1301), ok("/api/leagues/4/events").ids())
        assertEquals(listOf(false, true, false), ok("/api/leagues/4/events").jsonArray.map { it.jsonObject.getValue("monitored").jsonPrimitive.boolean })
    }

    @Test
    fun `an unknown league is 404 with Sportarr's body, and so is one removed once the leagues were read`() {
        val unknown = get("/api/leagues/999/events?page=1&pageSize=1000")
        assertEquals(404, unknown.statusCode())
        assertEquals("""{"error":"League not found"}""", unknown.body())
        assertEquals(200, control("remove-league?id=1").statusCode())
        val removed = get("/api/leagues/1/events?page=1&pageSize=1000")
        assertEquals(404 to """{"error":"League not found"}""", removed.statusCode() to removed.body())
        assertEquals(404, get("/api/leagues/1/events").statusCode())
        assertTrue(leagues().any { it.number("id") == 1 }, "removed between the two reads: the leagues were read with it")
        assertEquals(1, page(2, "page=1").number("page"), "the others still answer")
        assertEquals(400, control("remove-league?id=999").statusCode())
        assertEquals(400, control("remove-league").statusCode())
        control("reset")
        assertEquals(200, get("/api/leagues/1/events").statusCode())
    }

    @Test
    fun `the leagues come by sport, then name, with what Sportarr sends of each`() {
        val all = leagues()
        assertEquals(
            listOf(
                "Kestrel Baseball League", "NBA", "Kestrel Boxing", "KCL", "UFC", "Kestrel Hockey League", "Formula 1",
                "Kestrel Endurance Series", "MotoGP", "FIS Alpine Ski World Cup", "Kestrel Cup", "Kestrel Premier Division",
                "Premier League", "Velmora Wrestling",
            ),
            all.map { it.getValue("name").jsonPrimitive.content },
        )
        val premier = all.single { it.number("id") == 1 }
        assertEquals("4001", premier.getValue("externalId").jsonPrimitive.content)
        assertEquals("Soccer", premier.getValue("sport").jsonPrimitive.content)
        assertEquals("Future", premier.getValue("monitorType").jsonPrimitive.content)
        assertEquals("EPL", premier.getValue("alternateName").jsonPrimitive.content)
        assertEquals(16, premier.number("monitoredEventCount"), "the game switched off by hand isn't counted")
        assertEquals(1, all.single { it.getValue("name").jsonPrimitive.content == "KCL" }.number("monitoredEventCount"))
        assertEquals(false, all.single { it.number("id") == 3 }.getValue("monitored").jsonPrimitive.boolean)
        assertEquals(true, all.single { it.number("id") == 2 }.getValue("keepAllEvents").jsonPrimitive.boolean)
        // Sportarr's guaranteed league fields, every one of them, on every league.
        val guaranteed = setOf("id", "externalId", "name", "sport", "country", "monitored", "rootFolderId", "path", "tags")
        assertTrue(all.all { it.keys.containsAll(guaranteed) })
        assertEquals(1, all.map { it.keys }.toSet().size, "every league sends the same fields")
        assertFalse(all.any { "teamless" in it }, "what the catalogue says of a league's route isn't Sportarr's to send")
    }

    @Test
    fun `every field Sportarr might leave empty comes as an explicit null`() {
        val race = ok("/api/leagues/7/events").jsonArray.map { it.jsonObject }.single { it.number("id") == 1605 }
        listOf("homeTeamName", "awayTeamName", "eventTypePartNames", "broadcastTimezone").forEach { assertEquals(JsonNull, race.getValue(it), it) }
        val ski = ok("/api/leagues/13/events").jsonArray.single().jsonObject
        assertEquals(JsonNull, ski.getValue("externalId"))
        assertEquals(JsonNull, ski.getValue("round"))
        val endurance = leagues().single { it.number("id") == 9 }
        assertEquals(JsonNull, endurance.getValue("monitorType"))
        assertEquals(JsonNull, endurance.getValue("keepAllEvents"))
        assertEquals(JsonNull, endurance.getValue("alternateName"))
        // Every event, in every league, sends the same fields.
        val events = leagues().map { it.number("id") }.flatMap { ok("/api/leagues/$it/events?showAll=true").jsonArray.map { e -> e.jsonObject } }
        assertEquals(catalog.leagues(anchor).sumOf { it.events.size }, events.size)
        assertEquals(1, events.map { it.keys }.toSet().size)
        assertTrue(
            events.first().keys.containsAll(
                listOf(
                    "id", "externalId", "title", "sport", "leagueId", "leagueName", "season", "seasonNumber", "episodeNumber", "eventDate",
                    "broadcastDate", "broadcastTimezone", "monitored", "hasFile", "filePath", "fileSize", "quality", "files", "homeTeamName",
                    "awayTeamName", "round", "eventTypePartNames",
                ),
            ),
        )
    }

    @Test
    fun `an event says its start in UTC, its broadcast date as Sportarr stores it, and its round and season as text`() {
        val game = ok("/api/leagues/1/events").jsonArray.map { it.jsonObject }.single { it.number("id") == 1001 }
        assertEquals("2026-11-21T15:00:00Z", game.getValue("eventDate").jsonPrimitive.content)
        assertEquals("2026-11-21T00:00:00Z", game.getValue("broadcastDate").jsonPrimitive.content)
        assertEquals("13", game.getValue("round").jsonPrimitive.content)
        assertTrue(game.getValue("round").jsonPrimitive.isString)
        assertEquals("2026-2027", game.getValue("season").jsonPrimitive.content)
        assertEquals("Lantern Bay FC", game.getValue("homeTeamName").jsonPrimitive.content)
        assertEquals("Premier League", game.getValue("leagueName").jsonPrimitive.content)
        assertEquals(1, game.number("leagueId"))
        assertEquals("ev-001001", game.getValue("externalId").jsonPrimitive.content)
        val evening = ok("/api/leagues/6/events").jsonArray.map { it.jsonObject }.single { it.number("id") == 1502 }
        assertEquals("2026-11-18T01:30:00Z", evening.getValue("eventDate").jsonPrimitive.content)
        assertEquals("2026-11-17T00:00:00Z", evening.getValue("broadcastDate").jsonPrimitive.content)
        assertEquals("Etc/GMT+5", evening.getValue("broadcastTimezone").jsonPrimitive.content)
    }

    @Test
    fun `every event carries its scores, status, images, venue and files, and every league its description and images, as canaries`() {
        assertEquals(
            setOf(
                "homeScore", "awayScore", "status", "images", "thumbUrl", "venue", "location", "broadcast", "files", "filePath", "fileSize",
                "quality", "partStatuses", "dvrInfo", "description", "leagueLogoUrl",
            ),
            FakeSportarrServer.CANARY_EVENT_FIELDS,
        )
        assertEquals(setOf("description", "logoUrl", "bannerUrl", "posterUrl"), FakeSportarrServer.CANARY_LEAGUE_FIELDS)
        val all = leagues()
        all.forEach { league ->
            FakeSportarrServer.CANARY_LEAGUE_FIELDS.forEach { field -> assertContains(league.getValue(field).toString(), FakeSportarrServer.CANARY, message = field) }
        }
        control("busy?events=50")
        val events = leagues().map { it.number("id") }.flatMap { ok("/api/leagues/$it/events?showAll=true").jsonArray.map { e -> e.jsonObject } }
        assertTrue(events.size > 100)
        events.forEach { event ->
            FakeSportarrServer.CANARY_EVENT_FIELDS.forEach { field ->
                assertContains(event.getValue(field).toString(), FakeSportarrServer.CANARY, message = "${event.number("id")} $field")
            }
        }
        // And nothing the plugin reads holds one.
        val read = setOf("id", "externalId", "title", "sport", "leagueId", "leagueName", "season", "eventDate", "broadcastDate", "monitored", "hasFile", "round", "homeTeamName", "awayTeamName", "eventTypePartNames")
        assertTrue(events.none { event -> read.any { FakeSportarrServer.CANARY in event.getValue(it).toString() } })
    }

    @Test
    fun `eventTypePartNames come as Sportarr shapes them, on fighting events alone`() {
        fun parts(league: Int, id: Int) = ok("/api/leagues/$league/events").jsonArray.map { it.jsonObject }.single { it.number("id") == id }
            .getValue("eventTypePartNames").let { if (it is JsonNull) null else it.jsonArray.map { name -> name.jsonPrimitive.content } }
        val numbered = listOf("Early Prelims", "Prelims", "Main Card", "Post Show")
        assertEquals(numbered, parts(10, 1901), "UFC 999")
        assertEquals(listOf("Prelims", "Main Card"), parts(10, 1902), "the fight night")
        // A promotion Sportarr doesn't know gets the numbered card's, whatever the event.
        assertEquals(numbered, parts(12, 2101), "a boxing matchup")
        assertEquals(numbered, parts(11, 2003), "a weekly show")
        assertEquals(numbered, parts(11, 2004), "a premium event")
        assertEquals(listOf("Countdown", "Main Show"), parts(11, 2005))
        assertEquals(null, parts(1, 1001))
        assertEquals(null, parts(7, 1605))
        assertEquals(null, parts(13, 2201))
    }

    @Test
    fun `the status says Sportarr and its version, which a switch changes, and the URL base moves every route under it`() {
        assertEquals("Sportarr", ok("/api/system/status").jsonObject.getValue("appName").jsonPrimitive.content)
        assertEquals(FakeSportarrServer.VERSION, ok("/api/system/status").jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals("4.1.12.1122", FakeSportarrServer.VERSION)
        assertEquals(200, control("version?v=4.0.998").statusCode())
        assertEquals("4.0.998", ok("/api/system/status").jsonObject.getValue("version").jsonPrimitive.content)
        control("version")
        assertEquals("4.1.12.1122", ok("/api/system/status").jsonObject.getValue("version").jsonPrimitive.content)

        assertEquals(200, control("url-base?base=/sportarr/").statusCode())
        assertEquals("/sportarr", ok("/sportarr/api/system/status").jsonObject.getValue("urlBase").jsonPrimitive.content)
        assertEquals(17, ok("/sportarr/api/leagues/1/events").jsonArray.size)
        assertEquals(200, get("/sportarr/api/health", key = null).statusCode())
        listOf("/api/system/status", "/api/leagues", "/api/health").forEach { assertEquals(404, get(it).statusCode(), it) }
        assertEquals(400, control("url-base?base=sportarr").statusCode(), "a base starts with a slash")
        control("url-base?base=")
        assertEquals(200, get("/api/leagues").statusCode())
        assertEquals(404, get("/sportarr/api/leagues").statusCode())
        assertEquals(404, get("/api/series").statusCode(), "a route Sportarr's API has, but not this fake")
    }

    @Test
    fun `refuse-key refuses every key, html answers a page for every route, and slow holds each answer`() {
        control("refuse-key")
        assertEquals(401, get("/api/system/status").statusCode())
        assertEquals(401, get("/api/leagues/1/events?page=1").statusCode())
        assertEquals(200, get("/api/health", key = null).statusCode())
        control("refuse-key?on=false")
        assertEquals(200, get("/api/system/status").statusCode())

        control("html")
        listOf("/api/system/status", "/api/leagues", "/api/leagues/1/events?page=1", "/api/health").forEach { path ->
            val page = get(path)
            assertEquals(200, page.statusCode(), path)
            assertTrue(page.headers().firstValue("Content-Type").get().startsWith("text/html"), path)
            assertTrue(page.body().startsWith("<!DOCTYPE html>"), path)
        }
        control("html?on=false")
        assertEquals(14, ok("/api/leagues").jsonArray.size)

        control("slow?ms=1500")
        get("/api/system/status")
        get("/api/leagues/1/events?page=1")
        assertEquals(listOf(1500L, 1500L), paused.toList())
        control("slow?ms=0")
        get("/api/system/status")
        assertEquals(2, paused.size)
        assertEquals(400, control("slow?ms=soon").statusCode())
    }

    @Test
    fun `fail answers a route, or one page of it, with a status, until it is switched off`() {
        assertEquals(200, control("fail?status=500&route=events&page=2").statusCode())
        assertEquals(200, get("/api/leagues/1/events?page=1&pageSize=5").statusCode())
        val failed = get("/api/leagues/1/events?page=2&pageSize=5")
        assertEquals(500, failed.statusCode())
        assertEquals(200, get("/api/leagues/1/events").statusCode(), "a plain array asks no page")
        assertEquals(200, get("/api/leagues").statusCode())
        control("fail?status=503&route=leagues")
        assertEquals(503, get("/api/leagues").statusCode())
        assertEquals(200, get("/api/leagues/1/events?page=2&pageSize=5").statusCode(), "one failure switched at a time")
        control("fail?status=500&route=status")
        assertEquals(500, get("/api/system/status").statusCode())
        control("fail?status=401&route=events&league=2")
        assertEquals(401, get("/api/leagues/2/events?page=1").statusCode())
        assertEquals(200, get("/api/leagues/1/events?page=1").statusCode())
        control("fail?on=false")
        assertEquals(200, get("/api/leagues/2/events?page=1").statusCode())
        assertEquals(400, control("fail?status=500&route=calendar").statusCode())
        assertEquals(400, control("fail?status=fine").statusCode())
    }

    @Test
    fun `order, paging and empty switch the order, the envelope and every league's monitoring`() {
        control("order?dir=asc")
        assertEquals(newestFirst(1).reversed(), (1..4).flatMap { page(1, "page=$it&pageSize=5").records() }, "oldest first, then by id")
        control("order?dir=desc")
        assertEquals(newestFirst(1), ok("/api/leagues/1/events").ids())
        assertEquals(400, control("order?dir=sideways").statusCode())

        control("paging?on=false")
        val whole = ok("/api/leagues/1/events?page=2&pageSize=5")
        assertIs<JsonArray>(whole, "a server before 4.1.10 ignores paging")
        assertEquals(17, whole.size)
        control("paging?on=true")
        assertIs<JsonObject>(ok("/api/leagues/1/events?page=2&pageSize=5"))

        control("empty")
        assertTrue(leagues().none { it.getValue("monitored").jsonPrimitive.boolean })
        assertEquals(17, ok("/api/leagues/1/events").jsonArray.size, "the events are still there")
        control("empty?on=false")
        assertEquals(13, leagues().count { it.getValue("monitored").jsonPrimitive.boolean })
    }

    @Test
    fun `busy adds a league of numbered clubs filling the window, a season ahead and one behind`() {
        assertEquals(200, control("busy?events=5200").statusCode())
        val busy = leagues().single { it.number("id") == SportsCatalog.BUSY_LEAGUE_ID }
        assertEquals("Baseball", busy.getValue("sport").jsonPrimitive.content)
        assertTrue(busy.getValue("monitored").jsonPrimitive.boolean)
        val first = page(SportsCatalog.BUSY_LEAGUE_ID, "page=1&pageSize=1000")
        assertEquals(5200 + SportsCatalog.BUSY_AHEAD + SportsCatalog.BUSY_BEHIND, first.number("totalRecords"))
        assertEquals(8, first.number("totalPages"))
        assertEquals(1000, first.records().size)
        assertEquals(newestFirst(1), ok("/api/leagues/1/events").ids(), "the rest as it was")
        control("busy?events=0")
        assertTrue(leagues().none { it.number("id") == SportsCatalog.BUSY_LEAGUE_ID })
        assertEquals(404, get("/api/leagues/${SportsCatalog.BUSY_LEAGUE_ID}/events").statusCode())
        assertEquals(400, control("busy?events=many").statusCode())
    }

    @Test
    fun `on=false turns any switch off alone, a removed league back among the rest`() {
        control("remove-league?id=1")
        control("remove-league?id=7")
        assertEquals(200, control("remove-league?id=7&on=false").statusCode())
        assertEquals(200, get("/api/leagues/7/events").statusCode(), "Formula 1 back")
        assertEquals(404, get("/api/leagues/1/events").statusCode(), "the other still removed")
        assertEquals(400, control("remove-league?on=false").statusCode(), "a league named")

        control("busy?events=10")
        control("busy?on=false")
        assertTrue(leagues().none { it.number("id") == SportsCatalog.BUSY_LEAGUE_ID })
        control("order?dir=asc")
        control("order?on=false")
        assertEquals(newestFirst(7), ok("/api/leagues/7/events").ids())
        control("version?v=4.0.998")
        control("version?v=4.0.998&on=false")
        assertEquals(FakeSportarrServer.VERSION, ok("/api/system/status").jsonObject.getValue("version").jsonPrimitive.content)
        control("url-base?base=/sportarr")
        control("url-base?on=false")
        assertEquals(200, get("/api/leagues").statusCode())
        control("slow?ms=10")
        control("slow?on=false")
        get("/api/health", key = null)
        assertEquals(emptyList(), paused.toList())
    }

    @Test
    fun `the clock says the anchor and the fake's now, and reset puts every switch back`() {
        val clock = Json.parseToJsonElement(control("clock").body()).jsonObject
        assertEquals("2026-11-22", clock.getValue("anchor").jsonPrimitive.content)
        assertEquals(NOW.toString(), clock.getValue("now").jsonPrimitive.content)

        listOf("refuse-key", "html", "version?v=4.0.998", "url-base?base=/sportarr", "slow?ms=10", "fail?status=500&route=leagues", "remove-league?id=1",
            "order?dir=asc", "paging?on=false", "empty", "busy?events=10").forEach { assertEquals(200, control(it).statusCode(), it) }
        control("reset")
        assertEquals(FakeSportarrServer.VERSION, ok("/api/system/status").jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals(14, leagues().size)
        assertEquals(13, leagues().count { it.getValue("monitored").jsonPrimitive.boolean })
        assertEquals(newestFirst(1), page(1, "page=1").records())
        assertEquals(emptyList(), paused.toList())
        assertEquals(404, control("calendar").statusCode())
    }

    @Test
    fun `counts say the requests by route and page, and any without the key or with it in the query`() {
        get("/api/system/status")
        get("/api/leagues?apikey=$KEY", key = null)
        get("/api/leagues")
        get("/api/leagues/1/events?page=1&pageSize=1000")
        get("/api/leagues/1/events?page=1&pageSize=1000")
        get("/api/leagues/2/events?page=2&pageSize=1000")
        get("/api/leagues/2/events")
        get("/api/leagues/2/events", key = null)
        get("/api/health", key = null)
        val counts = Json.parseToJsonElement(control("counts").body()).jsonObject
        assertEquals(
            mapOf("status" to 1, "health" to 1, "leagues" to 2, "events" to 5),
            counts.getValue("routes").jsonObject.mapValues { it.value.jsonPrimitive.int },
        )
        assertEquals(
            mapOf("1:1" to 2, "2:2" to 1, "2:whole" to 2),
            counts.getValue("pages").jsonObject.mapValues { it.value.jsonPrimitive.int },
        )
        assertEquals(1, counts.number("withoutKey"))
        assertEquals(1, counts.number("keyInQuery"))
        control("reset")
        val cleared = Json.parseToJsonElement(control("counts").body()).jsonObject
        assertEquals(0, cleared.getValue("routes").jsonObject.values.sumOf { it.jsonPrimitive.int })
        assertEquals(emptyMap(), cleared.getValue("pages").jsonObject)
        assertEquals(0 to 0, cleared.number("withoutKey") to cleared.number("keyInQuery"))
    }

    /**
     * Its switches and counts are one set for every emulator reaching it, so a device class holds it while it runs: another
     * is refused (409) until the holder gives it back, or its lease runs out (a test process that died). A reset leaves the
     * holder as it is.
     */
    @Test
    fun `one test at a time holds the fake, until it gives it back or its lease runs out`() {
        assertEquals(200, control("take?by=phone34-test").statusCode())
        assertEquals(200, control("take?by=phone34-test").statusCode(), "its holder takes it again")
        assertEquals(409, control("take?by=phone34b-test").statusCode())
        assertEquals(200, control("reset").statusCode())
        assertEquals(200, control("give-back?by=phone34b-test").statusCode())
        assertEquals(409, control("take?by=phone34b-test").statusCode(), "given back by one that didn't hold it")
        assertEquals(200, control("give-back?by=phone34-test").statusCode())
        assertEquals(200, control("take?by=phone34b-test").statusCode())
        skew.addAndGet(60_001)
        assertEquals(200, control("take?by=tv36-test").statusCode(), "the lease ran out")
        assertEquals(400, control("take").statusCode(), "a holder names itself")
        assertEquals(200, control("take?by=tv36-test&ms=${20 * 60_000}").statusCode())
        skew.addAndGet(10 * 60_000)
        assertEquals(409, control("take?by=phone34-test").statusCode(), "held for as long as it asked")
        assertEquals(400, control("take?by=tv36-test&ms=${30 * 60_000 + 1}").statusCode(), "past half an hour")
    }

    @Test
    fun `no line it logs holds the key, and it listens on the loopback address alone`() {
        assertTrue(server.address.isLoopbackAddress)
        get("/api/system/status")
        get("/api/leagues?apikey=$KEY", key = null)
        get("/api/leagues", key = "a-wrong-key")
        listOf("refuse-key", "html?on=false", "fail?status=500&route=leagues", "reset", "take?by=t", "give-back?by=t").forEach { control(it) }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { KEY in it || "a-wrong-key" in it }, lines.joinToString("\n"))
    }

    /**
     * `mock-seed sports` (L655): the fake Sportarr and the sports indexer in one process, on one anchor, each on the
     * loopback address alone (the emulators reach them at 10.0.2.2); with --seed false it lists the catalogue's releases
     * and never posts to the mock, so it runs while the mock is down. Given the address of a socket that only listens, it
     * never connects there.
     */
    @Test
    fun `the sports command serves the fake Sportarr and the sports indexer on one anchor, and seeds nothing with --seed false`() = runBlocking<Unit> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { mock ->
            mock.soTimeout = 300
            val log = CopyOnWriteArrayList<String>()
            val options = mapOf("port" to "0", "indexer-port" to "0", "anchor" to "2026-11-22", "seed" to "false", "mock" to "http://127.0.0.1:${mock.localPort}")
            sports(options, log = { log += it }).use { fakes ->
                assertEquals(anchor, fakes.anchor)
                assertTrue(fakes.sportarr.address.isLoopbackAddress)
                assertTrue(fakes.indexer.address.isLoopbackAddress)
                val status = http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${fakes.sportarr.port}/api/system/status")).header("X-Api-Key", KEY).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, status.statusCode())
                val clock = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${fakes.sportarr.port}/control/clock")).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertContains(clock.body(), "\"anchor\":\"2026-11-22\"")
                val indexer = IndexerConfig("sports", "Sports", "http://127.0.0.1:${fakes.indexer.port}", SportsFakes.INDEXER_KEY)
                assertEquals("Fake Indexer", NewznabClient(NewznabClient.defaultHttpClient()).caps(indexer).serverTitle)
            }
            assertFailsWith<SocketTimeoutException> { mock.accept() }
            assertTrue(log.any { "anchor 2026-11-22" in it }, log.joinToString("\n"))
            assertTrue(log.none { KEY in it }, log.joinToString("\n"))
        }
    }

    @Test
    fun `the sports command refuses the ports others use, an anchor that isn't a date, and a seed that is neither true nor false`() {
        fun refused(vararg options: Pair<String, String>): String = assertFailsWith<IllegalArgumentException>(options.toList().toString()) {
            val whole = mapOf("port" to "0", "indexer-port" to "0", "seed" to "false", "mock" to "http://127.0.0.1:1")
            sports(whole + options, log = {}).close()
        }.message!!
        listOf("1119", "8081", "8089", "8090", "8091", "8092", "8093", "8096", "8097", "29101", "-1", "70000", "soon").forEach { port ->
            assertContains(refused("port" to port), "--port", ignoreCase = false)
            assertContains(refused("indexer-port" to port), "--indexer-port")
        }
        assertContains(refused("port" to "18098", "indexer-port" to "18098"), "two ports")
        assertContains(refused("anchor" to "22.11.2026"), "--anchor")
        assertContains(refused("seed" to "yes"), "--seed")
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            assertContains(refused("port" to "${taken.localPort}"), "${taken.localPort} is taken")
            assertContains(refused("indexer-port" to "${taken.localPort}"), "${taken.localPort} is taken")
        }
    }

    private companion object {
        const val KEY = FakeSportarrServer.KEY
        val NOW: Instant = Instant.parse("2026-11-22T12:34:56Z")
    }
}

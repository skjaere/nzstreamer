package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The fake Sportarr (L655): the routes the Sportarr plugin reads, as Sportarr 4.1.12 answers them, over the catalogue's
 * invented library at [anchor] ([SportsCatalog]), so the plugin can be tried from an emulator (http://10.0.2.2:8098) with
 * an invented key and never a real server.
 *
 * - `GET /api/system/status`: `appName` "Sportarr" and the four-part [VERSION] (switchable).
 * - `GET /api/health`: needs no key.
 * - `GET /api/leagues`: a plain array of every league, by sport, then name, each with `monitoredEventCount` (its stored
 *   events switched on). Each league's sport is the catalogue's word, and each event says its league's, as Sportarr
 *   gives it: "Combat" for the fight leagues, as 4.1.12 renames every fighting league at each start, but one league's
 *   the "Fighting" of the servers before it, and one's a word no family of the plugin's has.
 * - `GET /api/leagues/{id}/events`: what the league's page in Sportarr lists: the events the catalogue marks listed, or
 *   every stored one for a league with Keep every game or a fighting (teamless) league, whatever its scope, or with
 *   `showAll=true`. Newest first by start, then by id descending. With `page` or `pageSize`, Sportarr's envelope
 *   `{page, pageSize, totalRecords, totalPages, records}`: `page` below 1 read as 1, `pageSize` 100 unless given and held
 *   between 1 and 1000, `totalPages` the records over the size rounded up (0 for none), a page past the last empty; without
 *   either, a plain array. An unknown league is 404 `{"error":"League not found"}`.
 *
 * Every route but the health one takes the key ([KEY]) in X-Api-Key or the `apikey` query, and answers any other, or none,
 * with 401. Sportarr omits no null: every field a fixture leaves empty comes as an explicit null. Every event carries the
 * fields the plugin must never show (scores, status, images, venue, files and more, [CANARY_EVENT_FIELDS]) and every league
 * its description and images ([CANARY_LEAGUE_FIELDS]), each value holding [CANARY], so a test can prove none of them ever
 * reaches a model, a file, a screen or a log (L653).
 *
 * Switches at `/control/…`, each answering `{"ok":true}`: `refuse-key` (every key refused), `version?v=4.0.998` (none
 * given: back to [VERSION]), `html` (every /api/ route answers a page), `url-base?base=/sportarr` (every route under
 * it; empty for none), `slow?ms=` (each /api/ answer held that long), `fail?status=500&route=events&page=2` (a route,
 * status, leagues or events, answered with that status; for events, one page, one league or both if named; one failure
 * at a time), `remove-league?id=` (its events answer 404 from then on, while the leagues still list it: a league
 * removed between the plugin's two reads), `order?dir=asc` (oldest first, then by id), `paging?on=false` (plain arrays
 * whatever is asked, a server before 4.1.10), `empty` (no league switched on), `busy?events=5200` (the catalogue's busy
 * league added, none with 0). A switch's `on=false` turns it off and no other: the version, the base, the pause, the
 * order and the busy league back as they were, and `remove-league?id=&on=false` that league back. `clock` says the
 * anchor and the fake's now; `counts` the requests by route and by league and page (`<id>:<page>`, `<id>:whole` without
 * paging), and how many asked without the key or carried it in the query, which the plugin never does; `reset` puts
 * every switch and count back.
 *
 * Given the fake Wikipedia ([wiki], L-B12), its switches and counts live here too, one lease and one reset for both:
 * `wiki-form?event=&form=` serves that event's article in a form (results, pre, unsplit, bare, missing or moved; `on=false`
 * puts the event's back as usual, or every event's without `event=`), `wiki-fail?status=` answers every Wikipedia request
 * with 503, 403 or 429, `wiki-hold` holds every Wikipedia answer until it is switched off (a device's Reading line), and
 * `counts` gains `wiki`, its requests ([FakeWikipediaServer.counts]). Without one, the three are unknown (404).
 *
 * The switches and counts are one set for every emulator reaching the fake, so a device class holds it while it runs
 * (`take?by=<its id>`, `give-back?by=`): another is refused (409) until it is given back or [leaseMs] after it was taken;
 * `take?by=&ms=` holds it that long instead, half an hour at most. A reset leaves the holder as it is. It listens on the
 * loopback address alone, and no line it logs holds a key. Development only.
 *
 * [now] is a clock that only goes forward, in milliseconds, for the lease; [wallClock] the fake's now for `clock`; [pause]
 * holds an answer for `slow`. Each is a test's seam.
 */
class FakeSportarrServer(
    catalog: SportsCatalog,
    val anchor: LocalDate,
    port: Int,
    private val log: (String) -> Unit = {},
    private val leaseMs: Long = LEASE_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Instant = Instant::now,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val wiki: FakeWikipediaServer? = null,
) : AutoCloseable {
    private val library: List<SportsLeague> = catalog.leagues(anchor)
    private val busyOf: (Int) -> SportsLeague = { events -> catalog.busy(anchor, events) }

    /** A failure switched on: [route]'s answers given [status], for one [page] or [league] of the events if named. */
    private class Fail(val status: Int, val route: String, val page: Int?, val league: Int?)

    @Volatile private var refuseKey = false
    @Volatile private var version = VERSION
    @Volatile private var html = false
    @Volatile private var urlBase = ""
    @Volatile private var slowMs = 0L
    @Volatile private var fail: Fail? = null
    @Volatile private var removed: Set<Int> = emptySet()
    @Volatile private var ascending = false
    @Volatile private var paging = true
    @Volatile private var empty = false
    @Volatile private var busy: SportsLeague? = null

    /** Guards the counts and the holder. */
    private val lock = Any()
    private val routes = LinkedHashMap<String, Int>()
    private val pages = LinkedHashMap<String, Int>()
    private var withoutKey = 0
    private var keyInQuery = 0
    private var holder: String? = null
    private var heldUntil = 0L

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        reset()
        server.createContext("/") { exchange -> exchange.use { api(it) } }
        server.createContext("/control/") { exchange -> exchange.use { control(it) } }
        // A slow answer mustn't hold the control path or another request up.
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }

    // The API.

    private fun api(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val base = urlBase
        if (!path.startsWith("$base/api/")) return exchange.json(NOT_FOUND, 404)
        val route = path.removePrefix(base)
        val events = EVENTS.matchEntire(route)?.groupValues?.get(1)?.toIntOrNull()
        val name = when {
            route == "/api/system/status" -> STATUS
            route == "/api/health" -> HEALTH
            route == "/api/leagues" -> LEAGUES
            events != null -> EVENTS_ROUTE
            else -> return exchange.json(NOT_FOUND, 404)
        }
        val query = queryOf(exchange.requestURI.rawQuery)
        val paged = paging && ("page" in query || "pageSize" in query)
        val page = if (paged) query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1 else null
        val keyed = name != HEALTH
        val given = exchange.requestHeaders.getFirst(KEY_HEADER) ?: query["apikey"]
        synchronized(lock) {
            routes.merge(name, 1, Int::plus)
            if (events != null) pages.merge("$events:${page ?: "whole"}", 1, Int::plus)
            if (keyed && given != KEY) withoutKey++
            if ("apikey" in query) keyInQuery++
        }
        slowMs.takeIf { it > 0 }?.let(pause)
        if (exchange.requestMethod != "GET") return exchange.json("""{"error":"Method not allowed"}""", 405)
        if (html) return exchange.reply(PAGE, "text/html")
        if (name == HEALTH) return exchange.json("""{"status":"healthy"}""")
        if (given != KEY || refuseKey) return exchange.json("""{"error":"Unauthorized"}""", 401)
        fail?.takeIf { it.route == name && (it.page == null || it.page == page) && (it.league == null || it.league == events) }?.let {
            return exchange.json("""{"error":"The fake's switched failure"}""", it.status)
        }
        when (name) {
            STATUS -> exchange.json(status().toString())
            LEAGUES -> exchange.json(JsonArray(leagues().map(::leagueJson)).toString())
            else -> leagueEvents(exchange, requireNotNull(events), query, page)
        }
    }

    private fun status() = buildJsonObject {
        put("appName", "Sportarr")
        put("instanceName", "Sportarr")
        put("version", version)
        put("urlBase", urlBase)
        put("isDocker", false)
    }

    /** Every league, by sport, then name: the catalogue's, with the busy one when switched on. */
    private fun leagues(): List<SportsLeague> = (library + listOfNotNull(busy)).sortedWith(compareBy({ it.sport ?: "" }, { it.name }))

    private fun leagueEvents(exchange: HttpExchange, id: Int, query: Map<String, String>, page: Int?) {
        val league = leagues().firstOrNull { it.id == id }?.takeIf { it.id !in removed } ?: return exchange.json(LEAGUE_NOT_FOUND, 404)
        val all = query["showAll"].equals("true", ignoreCase = true) || league.keepAllEvents == true || league.teamless
        val order = compareBy<SportsEvent>({ it.start }, { it.id })
        val events = league.events.filter { all || it.listed }.sortedWith(if (ascending) order else order.reversed())
        // Its place in the league by start, as Sportarr numbers a season's episodes: never the card's number.
        val episodes = league.events.sortedWith(order).withIndex().associate { (index, event) -> event.id to index + 1 }
        val json = { event: SportsEvent -> eventJson(event, league, episodes.getValue(event.id)) }
        if (page == null) return exchange.json(JsonArray(events.map(json)).toString())
        val size = query["pageSize"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val records = events.drop(((page - 1).toLong() * size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(size)
        val envelope = buildJsonObject {
            put("page", page)
            put("pageSize", size)
            put("totalRecords", events.size)
            put("totalPages", (events.size + size - 1) / size)
            put("records", JsonArray(records.map(json)))
        }
        exchange.json(envelope.toString())
    }

    private fun leagueJson(league: SportsLeague): JsonObject = buildJsonObject {
        put("id", league.id)
        put("externalId", league.externalId)
        put("name", league.name)
        put("sport", league.sport)
        put("country", JsonNull)
        put("monitored", league.monitored && !empty)
        put("rootFolderId", 1)
        put("path", "/sports/${league.name}")
        put("tags", JsonArray(emptyList()))
        put("sportFormat", JsonNull)
        put("monitorType", league.monitorType)
        put("keepAllEvents", league.keepAllEvents)
        put("alternateName", league.alternateName)
        put("monitoredEventCount", league.events.count { it.monitored })
        LEAGUE_CANARIES.forEach { (field, value) -> put(field, value) }
    }

    private fun eventJson(event: SportsEvent, league: SportsLeague, episode: Int): JsonObject = buildJsonObject {
        put("id", event.id)
        put("externalId", event.externalId)
        put("title", event.title)
        put("sport", league.sport)
        put("leagueId", league.id)
        put("leagueName", league.name)
        put("season", event.season)
        put("seasonNumber", event.start.atZone(ZoneOffset.UTC).year)
        put("episodeNumber", episode)
        put("eventDate", event.start.toString())
        put("broadcastDate", "${event.broadcastDate}T00:00:00Z")
        put("broadcastTimezone", event.broadcastTimezone)
        put("monitored", event.monitored)
        put("hasFile", event.hasFile)
        put("homeTeamName", event.homeTeamName)
        put("awayTeamName", event.awayTeamName)
        put("round", event.round)
        put("eventTypePartNames", event.partNames?.let { names -> JsonArray(names.map(::JsonPrimitive)) } ?: JsonNull)
        EVENT_CANARIES.forEach { (field, value) -> put(field, value) }
    }

    // The control path: switches, the clock, counts, a reset and the lease.

    private fun control(exchange: HttpExchange) {
        val query = queryOf(exchange.requestURI.rawQuery)
        val on = query["on"] != "false"
        val name = exchange.requestURI.path.removePrefix("/control/")
        when (name) {
            "refuse-key" -> refuseKey = on
            "version" -> version = query["v"]?.takeIf { it.isNotEmpty() && on } ?: VERSION
            "html" -> html = on
            "url-base" -> {
                val base = if (on) query["base"].orEmpty().trimEnd('/') else ""
                if (base.isNotEmpty() && !base.startsWith("/")) return exchange.json("""{"error":"base= starts with a slash"}""", 400)
                urlBase = base
            }
            "slow" -> slowMs = if (!on) 0 else query["ms"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return exchange.json("""{"error":"ms= is milliseconds"}""", 400)
            "fail" -> {
                if (!on) {
                    fail = null
                } else {
                    val status = query["status"]?.toIntOrNull()?.takeIf { it in 100..599 }
                    val route = query["route"]?.takeIf { it in setOf(STATUS, LEAGUES, EVENTS_ROUTE) }
                    if (status == null || route == null) return exchange.json("""{"error":"status= is a status, route= status, leagues or events"}""", 400)
                    fail = Fail(status, route, query["page"]?.toIntOrNull(), query["league"]?.toIntOrNull())
                }
            }
            "remove-league" -> {
                val id = query["id"]?.toIntOrNull()?.takeIf { id -> leagues().any { it.id == id } }
                    ?: return exchange.json("""{"error":"id= names a league"}""", 400)
                removed = if (on) removed + id else removed - id
            }
            "order" -> ascending = if (!on) false else when (query["dir"]) {
                "asc" -> true
                "desc" -> false
                else -> return exchange.json("""{"error":"dir= is asc or desc"}""", 400)
            }
            "paging" -> paging = on
            "empty" -> empty = on
            "busy" -> {
                val events = if (!on) 0 else query["events"]?.toIntOrNull()?.takeIf { it in 0..BUSY_MAX } ?: return exchange.json("""{"error":"events= is 0 to $BUSY_MAX"}""", 400)
                busy = events.takeIf { it > 0 }?.let(busyOf)
            }
            "clock" -> return exchange.json(buildJsonObject {
                put("anchor", anchor.toString())
                put("now", wallClock().toString())
            }.toString())
            "counts" -> return exchange.json(counts().toString())
            "reset" -> reset()
            "take" -> {
                val by = query["by"]?.takeIf { it.isNotEmpty() } ?: return exchange.json("""{"error":"by= names the test taking it"}""", 400)
                // A hold longer than a test's lease, for a pair; never so long that one that died keeps the fake.
                val ms = query["ms"]?.let { asked ->
                    asked.toLongOrNull()?.takeIf { it in 1..LONGEST_LEASE_MS } ?: return exchange.json("""{"error":"ms= is a hold of 1 ms to half an hour"}""", 400)
                }
                if (!take(by, ms ?: leaseMs)) return exchange.json("""{"held":true}""", 409)
            }
            "give-back" -> synchronized(lock) { if (holder == query["by"]) holder = null }
            "wiki-form" -> {
                val wiki = wiki ?: return exchange.json(NO_SUCH_CONTROL, 404)
                val given = query["event"]
                val event = given?.toIntOrNull()?.takeIf(wiki::hasArticle)
                if (given != null && event == null) return exchange.json("""{"error":"event= names an event the fake Wikipedia has an article for"}""", 400)
                val form = WikiForm.entries.firstOrNull { it.wire == query["form"] }
                when {
                    !on && event == null -> wiki.usualForms()
                    !on -> wiki.form(requireNotNull(event), null)
                    event == null || form == null ->
                        return exchange.json("""{"error":"event= names an event, form= results, pre, unsplit, bare, missing or moved"}""", 400)
                    else -> wiki.form(event, form)
                }
            }
            "wiki-fail" -> {
                val wiki = wiki ?: return exchange.json(NO_SUCH_CONTROL, 404)
                wiki.fail(
                    if (!on) null else query["status"]?.toIntOrNull()?.takeIf { it in FakeWikipediaServer.FAILS }
                        ?: return exchange.json("""{"error":"status= is 503, 403 or 429"}""", 400),
                )
            }
            "wiki-hold" -> (wiki ?: return exchange.json(NO_SUCH_CONTROL, 404)).hold(on)
            else -> return exchange.json(NO_SUCH_CONTROL, 404)
        }
        log("sportarr fake: control $name")
        exchange.json("""{"ok":true}""")
    }

    private fun counts(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put("routes", JsonObject(routes.mapValues { JsonPrimitive(it.value) }))
            put("pages", JsonObject(pages.mapValues { JsonPrimitive(it.value) }))
            put("withoutKey", withoutKey)
            put("keyInQuery", keyInQuery)
            wiki?.let { put("wiki", it.counts()) }
        }
    }

    /** Whether [by] holds the fake now, for [ms] from now: it was free, its lease over, or [by]'s already (taken again). */
    private fun take(by: String, ms: Long): Boolean = synchronized(lock) {
        val at = now()
        if (holder != null && holder != by && at < heldUntil) return false
        holder = by
        heldUntil = at + ms
        true
    }

    private fun reset() {
        refuseKey = false
        version = VERSION
        html = false
        urlBase = ""
        slowMs = 0
        fail = null
        removed = emptySet()
        ascending = false
        paging = true
        empty = false
        busy = null
        synchronized(lock) {
            listOf(STATUS, HEALTH, LEAGUES, EVENTS_ROUTE).forEach { routes[it] = 0 }
            pages.clear()
            withoutKey = 0
            keyInQuery = 0
        }
        wiki?.reset()
    }

    private fun HttpExchange.json(body: String, status: Int = 200) = reply(body, "application/json", status)

    companion object {
        /** The key the fake takes: the device tests' and the plugin's tests' own, never a real one. */
        const val KEY = "fake-sportarr-key"

        /** Sportarr's version as the fake reports it: four parts, as a release build's. */
        const val VERSION = "4.1.12.1122"

        /** In every canary value: a scan for it finds any that leaked. */
        const val CANARY = "CANARY"

        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 1000
        private const val BUSY_MAX = 50_000
        private const val LEASE_MS = 5 * 60_000L
        private const val LONGEST_LEASE_MS = 30 * 60_000L
        private const val KEY_HEADER = "X-Api-Key"

        private const val STATUS = "status"
        private const val HEALTH = "health"
        private const val LEAGUES = "leagues"
        private const val EVENTS_ROUTE = "events"
        private val EVENTS = Regex("/api/leagues/(-?\\d+)/events")

        private const val NOT_FOUND = """{"error":"Not found"}"""
        private const val NO_SUCH_CONTROL = """{"error":"no such control"}"""
        private const val LEAGUE_NOT_FOUND = """{"error":"League not found"}"""
        private const val PAGE = "<!DOCTYPE html><html><head><title>Sign in</title></head><body><form>Sign in</form></body></html>"

        private val canaryImage = buildJsonArray {
            add(buildJsonObject {
                put("coverType", "poster")
                put("url", "http://canary.invalid/$CANARY-image.jpg")
            })
        }

        /** What Sportarr sends on every event and the plugin never declares (L653), each holding [CANARY]. */
        private val EVENT_CANARIES: Map<String, JsonElement> = linkedMapOf(
            "homeScore" to JsonPrimitive("$CANARY-home-score"),
            "awayScore" to JsonPrimitive("$CANARY-away-score"),
            "status" to JsonPrimitive("$CANARY-status"),
            "images" to canaryImage,
            "thumbUrl" to JsonPrimitive("http://canary.invalid/$CANARY-thumb.jpg"),
            "venue" to JsonPrimitive("$CANARY-venue"),
            "location" to JsonPrimitive("$CANARY-location"),
            "broadcast" to JsonPrimitive("$CANARY-broadcast"),
            "files" to buildJsonArray {
                add(buildJsonObject {
                    put("path", "/$CANARY/$CANARY-file.mkv")
                    put("size", "$CANARY-file-size")
                    put("quality", "$CANARY-quality")
                })
            },
            "filePath" to JsonPrimitive("/$CANARY/$CANARY-file.mkv"),
            "fileSize" to JsonPrimitive("$CANARY-file-size"),
            "quality" to JsonPrimitive("$CANARY-quality"),
            "partStatuses" to buildJsonArray {
                add(buildJsonObject {
                    put("part", "$CANARY-part")
                    put("status", "$CANARY-part-status")
                })
            },
            "dvrInfo" to buildJsonObject { put("channel", "$CANARY-dvr-channel") },
            "description" to JsonPrimitive("$CANARY-description"),
            "leagueLogoUrl" to JsonPrimitive("http://canary.invalid/$CANARY-league-logo.png"),
        )

        /** What Sportarr sends on every league and the plugin never declares, each holding [CANARY]. */
        private val LEAGUE_CANARIES: Map<String, JsonElement> = linkedMapOf(
            "description" to JsonPrimitive("$CANARY-league-description"),
            "logoUrl" to JsonPrimitive("http://canary.invalid/$CANARY-logo.png"),
            "bannerUrl" to JsonPrimitive("http://canary.invalid/$CANARY-banner.png"),
            "posterUrl" to JsonPrimitive("http://canary.invalid/$CANARY-poster.png"),
        )

        val CANARY_EVENT_FIELDS: Set<String> = EVENT_CANARIES.keys
        val CANARY_LEAGUE_FIELDS: Set<String> = LEAGUE_CANARIES.keys
    }
}

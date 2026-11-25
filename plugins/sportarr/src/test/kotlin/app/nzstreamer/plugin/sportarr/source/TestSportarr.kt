package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.HttpAnswer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.ceil

/**
 * A small Sportarr for the source's tests, behind a [RecordingHttp]: its leagues, and each league's events paged as
 * Sportarr pages them (newest first by start, then by id; `page` below 1 read as 1; empty records past the last page;
 * `totalPages` the ceiling of the records over the page size), or as a plain array when [paging] is off, as a server
 * before 4.1.10 answers. Every event and league it sends carries the canary fields L653 lists.
 */
class TestSportarr(var leagues: List<WireLeague>, var events: Map<Int, List<WireEvent>>) {
    /** Off: every events route answers a plain array, whatever it was asked. */
    var paging = true

    /** On: each page oldest first, as a server that doesn't keep the order would send them. */
    var ascending = false

    /** Answers in place of the league's page: (league id, page) to the answer. */
    val pages = mutableMapOf<Pair<Int, Int>, () -> HttpAnswer>()

    /** An answer in place of the leagues'. */
    var leaguesAnswer: (() -> HttpAnswer)? = null

    /** Waits before each answer, in the test's time. */
    var latency: suspend () -> Unit = {}

    val http = RecordingHttp { request ->
        latency()
        answer(request.url)
    }

    /** The events routes asked, as (league id, page). */
    val pagesAsked: List<Pair<Int, Int>>
        get() = http.requests.mapNotNull { EVENTS.find(it.url) }.map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    private fun answer(url: String): HttpAnswer {
        if (url.endsWith("/api/leagues")) {
            leaguesAnswer?.let { return it() }
            return answer(200, buildJsonArray { leagues.forEach { add(withCanaries(Wire.json.encodeToJsonElement(WireLeague.serializer(), it).jsonObject, LEAGUE_CANARIES)) } }.toString())
        }
        val asked = EVENTS.find(url) ?: return answer(404)
        val leagueId = asked.groupValues[1].toInt()
        val page = asked.groupValues[2].toInt().coerceAtLeast(1)
        val size = asked.groupValues[3].toInt().coerceIn(1, 1000)
        pages[leagueId to page]?.let { return it() }
        val all = events[leagueId] ?: return answer(404, """{"error":"League not found"}""")
        val ordered = all.sortedWith(compareByDescending<WireEvent> { it.eventDate }.thenByDescending { it.id })
        if (!paging) return answer(200, eventsJson(ordered))
        val records = ordered.drop((page - 1) * size).take(size).let { if (ascending) it.reversed() else it }
        val totalPages = ceil(ordered.size / size.toDouble()).toInt()
        return answer(
            200,
            """{"page":$page,"pageSize":$size,"totalRecords":${ordered.size},"totalPages":$totalPages,"records":${eventsJson(records)}}""",
        )
    }

    companion object {
        private val EVENTS = Regex("""/api/leagues/(\d+)/events\?page=(-?\d+)&pageSize=(\d+)$""")

        /** L653's fields, with canary values. */
        val EVENT_CANARIES = mapOf(
            "homeScore" to "CANARY-home-score", "awayScore" to "CANARY-away-score", "status" to "CANARY-status",
            "thumbUrl" to "http://canary.invalid/thumb.jpg", "venue" to "CANARY-venue", "location" to "CANARY-location",
            "broadcast" to "CANARY-broadcast", "filePath" to "/canary/CANARY-file.mkv", "quality" to "CANARY-quality",
            "description" to "CANARY-description", "leagueLogoUrl" to "http://canary.invalid/logo.png",
        )
        val LEAGUE_CANARIES = mapOf(
            "description" to "CANARY-league-description", "logoUrl" to "http://canary.invalid/league-logo.png",
            "bannerUrl" to "http://canary.invalid/banner.png", "posterUrl" to "http://canary.invalid/poster.png",
        )
        val CANARY_VALUES = (EVENT_CANARIES.values + LEAGUE_CANARIES.values + "canary.invalid").toSet()

        fun eventsJson(events: List<WireEvent>): String =
            buildJsonArray { events.forEach { add(withCanaries(Wire.json.encodeToJsonElement(WireEvent.serializer(), it).jsonObject, EVENT_CANARIES)) } }
                .toString()

        private fun withCanaries(json: JsonObject, canaries: Map<String, String>) = buildJsonObject {
            json.forEach { (name, value) -> put(name, value) }
            canaries.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
            put("images", buildJsonArray { add(buildJsonObject { put("url", "http://canary.invalid/image.jpg") }) })
        }
    }
}

/** An event of league [leagueId] at [start], with the fields a test sets. */
fun event(
    id: Int,
    start: Instant,
    leagueId: Int = 1,
    title: String = "Kestrel Club ${(id % 30 + 1).toString().padStart(2, '0')} vs Kestrel Club ${((id + 7) % 30 + 1).toString().padStart(2, '0')}",
    monitored: Boolean = true,
    hasFile: Boolean = false,
    sport: String? = "Soccer",
    home: String? = null,
    away: String? = null,
    round: String? = null,
) = WireEvent(
    id = id, externalId = "ev-${id.toString().padStart(6, '0')}", title = title, sport = sport, leagueId = leagueId, leagueName = null,
    season = "2026", eventDate = start, broadcastDate = null, broadcastTimezone = null, monitored = monitored, hasFile = hasFile,
    round = round, homeTeamName = home, awayTeamName = away, eventTypePartNames = null,
)

/** A league switched on in Sportarr unless told otherwise. */
fun league(
    id: Int,
    name: String = "Kestrel Premier Division",
    sport: String? = "Soccer",
    monitored: Boolean = true,
    monitorType: String? = "Future",
    monitoredEventCount: Int? = null,
    keepAllEvents: Boolean? = false,
    alternateName: String? = null,
) = WireLeague(id, "47$id", name, sport, monitored, monitorType, monitoredEventCount, keepAllEvents, alternateName)

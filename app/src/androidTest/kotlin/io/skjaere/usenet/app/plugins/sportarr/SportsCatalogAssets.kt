package io.skjaere.usenet.app.plugins.sportarr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The sports fakes' catalogue (L655) as the test APK's assets carry it: copyTestPlugins' Sync copies mock-seed's
 * sports-catalogue.json beside the plugins' APKs, so the device classes read the fixtures' titles, keys, starts and the
 * releases' verdicts from the file the fake Sportarr and the sports indexer serve, rather than writing them again. Read at
 * the fakes' anchor (their `/control/clock`): an event starts its day from the anchor at its UTC time, and a release's
 * name takes its dates from its event, as mock-seed's SportsCatalog expands them.
 */
object SportsCatalogAssets {
    const val ASSET = "sports-catalogue.json"

    /** The catalogue at [anchor]. */
    fun load(anchor: LocalDate): SportsLibrary {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { it.readBytes().decodeToString() }
        return SportsLibrary.parse(text, anchor)
    }
}

/** A league of the fake library, its events in the file's order. */
data class CatalogueLeague(val id: Int, val name: String, val sport: String?, val monitored: Boolean, val events: List<CatalogueEvent>)

/**
 * An event of the fake library at the anchor: its plugin [key] (its externalId, else local:<id>, L644), its [start] in
 * UTC, its [round] as Sportarr sends it, the part names Sportarr sends with it ([parts], fighting events alone).
 */
data class CatalogueEvent(
    val id: Int,
    val key: String,
    val title: String,
    val leagueId: Int,
    val start: Instant,
    val round: String?,
    val parts: List<String>?,
    val listed: Boolean,
) {
    /** The key of its [part] (L644): "ev-001001:full". */
    fun part(part: String = FULL) = "$key:$part"

    /** The day it shows under in [zone]. */
    fun date(zone: ZoneId): LocalDate = start.atZone(zone).toLocalDate()

    companion object {
        const val FULL = "full"
    }
}

/** A release the sports indexer lists, its [name] at the anchor, with what the plugin's judge says of it for each event and part it is found for. */
data class CatalogueRelease(val id: String, val name: String, val whole: Boolean, val verdicts: List<CatalogueVerdict>)

/** A verdict of a release for [event]'s [part]: "accept", "offer" with its [reason], or "reject" with its [kind]; [reached] by the plugin's searches. */
data class CatalogueVerdict(val event: Int, val part: String, val verdict: String, val reason: String?, val kind: String?, val reached: Boolean)

/** The fake library at one anchor, and its releases. */
class SportsLibrary(val leagues: List<CatalogueLeague>, val releases: List<CatalogueRelease>) {
    val events: List<CatalogueEvent> get() = leagues.flatMap { it.events }

    fun event(id: Int): CatalogueEvent = events.single { it.id == id }

    fun league(id: Int): CatalogueLeague = leagues.single { it.id == id }

    /** The releases the plugin's searches for [event]'s [part] find with [verdict], each with its verdict for that part. */
    fun found(event: Int, part: String, verdict: String): List<Pair<CatalogueRelease, CatalogueVerdict>> = releases.mapNotNull { release ->
        release.verdicts.firstOrNull { it.event == event && it.part == part && it.reached && it.verdict == verdict }?.let { release to it }
    }

    companion object {
        fun parse(text: String, anchor: LocalDate): SportsLibrary {
            val root = Json.parseToJsonElement(text).jsonObject
            check(root.getValue("format").jsonPrimitive.int == 1) { "${SportsCatalogAssets.ASSET} isn't format 1" }
            val leagueRows = root.getValue("leagues").jsonArray.map { it.jsonObject }
            val leagues = leagueRows.map { league ->
                val id = league.int("id")
                CatalogueLeague(
                    id, league.string("name")!!, league.string("sport"), league.bool("monitored")!!,
                    league.getValue("events").jsonArray.map { it.jsonObject }.map { event ->
                        CatalogueEvent(
                            id = event.int("id"),
                            key = event.string("externalId")?.takeIf { KEYABLE.matches(it) } ?: "local:${event.int("id")}",
                            title = event.string("title")!!,
                            leagueId = id,
                            start = event.start(anchor),
                            round = event.string("round"),
                            parts = (event["parts"] as? JsonArray)?.map { it.jsonPrimitive.content },
                            listed = event.bool("listed") ?: true,
                        )
                    },
                )
            }
            // A release's {year} is its event's season's: for an event of a round, the year of the round's last event.
            val seasonYears = leagueRows.flatMap { league ->
                val events = league.getValue("events").jsonArray.map { it.jsonObject }
                val roundEnds = events.filter { it.string("round") != null }.groupBy { it.string("round") }
                    .mapValues { (_, round) -> round.maxOf { it.start(anchor).atZone(ZoneOffset.UTC).toLocalDate() } }
                events.map { it.int("id") to (roundEnds[it.string("round")] ?: it.start(anchor).atZone(ZoneOffset.UTC).toLocalDate()).year }
            }.toMap()
            val starts = leagues.flatMap { it.events }.associate { it.id to it.start }
            val releases = root.getValue("releases").jsonArray.map { it.jsonObject }.map { row ->
                val event = row.int("event")
                val start = starts.getValue(event).atZone(ZoneOffset.UTC).toLocalDate()
                val name = DATE.replace(row.string("name")!!) { match ->
                    val day = start.plusDays(match.groupValues[1].takeIf { it.isNotEmpty() }?.removePrefix("+")?.toLong() ?: 0)
                    "%04d.%02d.%02d".format(day.year, day.monthValue, day.dayOfMonth)
                }.replace("{year}", "${seasonYears.getValue(event)}")
                CatalogueRelease(
                    row.string("id")!!, name, row.bool("whole")!!,
                    (listOf(row) + (row["also"] as? JsonArray)?.map { it.jsonObject }.orEmpty()).map { judged ->
                        CatalogueVerdict(
                            judged.int("event"), judged.string("part")!!, judged.string("verdict")!!, judged.string("reason"), judged.string("kind"),
                            judged.bool("reached")!!,
                        )
                    },
                )
            }
            return SportsLibrary(leagues, releases)
        }

        private val KEYABLE = Regex("[A-Za-z0-9._-]{1,150}")
        // Android's patterns want the closing brace escaped too.
        private val DATE = Regex("""\{date([+-]\d+)?\}""")

        private fun JsonObject.start(anchor: LocalDate): Instant =
            anchor.plusDays(int("day").toLong()).atTime(LocalTime.parse(string("time"))).toInstant(ZoneOffset.UTC)

        private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

        private fun JsonObject.string(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.bool(name: String): Boolean? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
    }
}

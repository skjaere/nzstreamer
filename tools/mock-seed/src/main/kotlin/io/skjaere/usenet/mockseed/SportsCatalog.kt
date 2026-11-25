package io.skjaere.usenet.mockseed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The sports fakes' one catalogue (L655): sports-catalogue.json in mock-seed's resources, format 1, our own. It holds an
 * invented library, the leagues a user follows in Sportarr with their events, each start a day from an anchor date and a
 * UTC time, and the releases of those events, each name a template taking its dates from its event's start and posted
 * some hours from it, so the fake Sportarr, the sports indexer and the tests all keep one clock. The fake serves the
 * library ([FakeSportarrServer]), the sports indexer the releases (`mock-seed sports`), the plugin's tests read both from
 * their classpath and check each release's verdicts, and the device classes read a copy in their assets for its titles
 * and expectations.
 *
 * What the catalogue says that Sportarr doesn't send: whether an event is on its league's filtered page ([SportsEvent.listed];
 * stored only, it comes with showAll), and whether a league's page lists every event it stores ([SportsLeague.teamless], a
 * fighting league's, whatever its scope).
 */
class SportsCatalog private constructor(private val root: JsonObject) {
    /** The file's format: 1. */
    val format: Int = root.getValue("format").jsonPrimitive.int

    init {
        require(format == FORMAT) { "sports-catalogue.json is format $format; this mock-seed reads format $FORMAT" }
    }

    /** The library, its events' starts and broadcast dates [anchor]'s. */
    fun leagues(anchor: LocalDate): List<SportsLeague> = root.getValue("leagues").jsonArray.map { it.jsonObject.league(anchor) }

    /**
     * The busy league (`/control/busy`): baseball games between numbered invented clubs, Kestrel Club 01 to 30, [events] of
     * them spread over the plugin's window round [anchor] (59 days back to 30 ahead, whatever the device's zone), with a
     * season of [BUSY_AHEAD] games after it, which Sportarr lists first, and one of [BUSY_BEHIND] before it. Enough past
     * the 5,000 cap and the six-page budget to show both, and its pages hold thousands of rows for the list's speed.
     */
    fun busy(anchor: LocalDate, events: Int): SportsLeague {
        require(events >= 0) { "a busy league has no fewer than no games" }
        fun spread(count: Int, firstDay: Long, days: Long, firstId: Int) = (0 until count).map { i ->
            val day = firstDay + i * days / count.coerceAtLeast(1)
            // Ten in the morning to a quarter to eight in the evening, UTC, a game every quarter hour.
            val start = anchor.plusDays(day).atTime(LocalTime.of(10, 0)).plusMinutes((i % 40) * 15L).toInstant(ZoneOffset.UTC)
            val home = i % 30 + 1
            val away = (i + 1 + (i / 30) % 29) % 30 + 1
            val (homeName, awayName) = club(home) to club(away)
            SportsEvent(
                id = firstId + i, externalId = "ev-${firstId + i}", leagueId = BUSY_LEAGUE_ID, title = "$homeName vs $awayName", start = start,
                broadcastDate = start.atZone(ZoneOffset.UTC).toLocalDate(), broadcastTimezone = null,
                season = "${start.atZone(ZoneOffset.UTC).year}", round = null, homeTeamName = homeName, awayTeamName = awayName,
                monitored = true, hasFile = false, partNames = null, listed = true,
            )
        }
        val games = spread(BUSY_BEHIND, -150, 88, BUSY_FIRST_ID) + spread(events, -59, 90, BUSY_FIRST_ID + BUSY_BEHIND) +
            spread(BUSY_AHEAD, 33, 88, BUSY_FIRST_ID + BUSY_BEHIND + events)
        return SportsLeague(
            id = BUSY_LEAGUE_ID, externalId = "${4000 + BUSY_LEAGUE_ID}", name = BUSY_LEAGUE_NAME, sport = "Baseball", monitored = true,
            monitorType = "Future", keepAllEvents = false, alternateName = null, teamless = false, events = games,
        )
    }

    /**
     * The releases, each name and file taking its dates from its event's UTC start at [anchor]: `{date}` is that date
     * (2026.11.21), `{date-1}` and `{date+1}` a day either side (a US evening's date, the day before), `{year}` its year.
     */
    fun releases(anchor: LocalDate): List<SportsRelease> {
        val starts = leagues(anchor).flatMap { it.events }.associate { it.id to it.start }
        return root.getValue("releases").jsonArray.map { it.jsonObject }.map { row ->
            val event = row.int("event")
            val start = requireNotNull(starts[event]) { "release ${row.string("id")} names no event of the library: $event" }
            fun dated(template: String) = expand(template, start)
            val name = dated(row.string("name")!!)
            SportsRelease(
                id = row.string("id")!!,
                name = name,
                files = (row["files"] as? JsonArray)?.map { dated(it.jsonPrimitive.content) } ?: listOf("$name.mkv"),
                postedAt = start.plus(Duration.ofHours(row.int("posted").toLong())),
                sizeBytes = row.int("sizeMB").toLong() * 1_000_000,
                grabs = row.int("grabs"),
                whole = row.bool("whole")!!,
                verdicts = (listOf(row) + (row["also"] as? JsonArray)?.map { it.jsonObject }.orEmpty()).map { judged ->
                    SportsVerdict(
                        eventId = judged.int("event"), part = judged.string("part")!!,
                        verdict = SportsVerdict.Verdict.entries.single { it.name.equals(judged.string("verdict"), ignoreCase = true) },
                        reason = judged.string("reason"), kind = judged.string("kind"),
                        reached = requireNotNull(judged.bool("reached")) { "release ${row.string("id")} doesn't say whether the queries reach it" },
                    )
                },
            )
        }
    }

    private fun JsonObject.league(anchor: LocalDate): SportsLeague {
        val id = int("id")
        return SportsLeague(
            id = id, externalId = string("externalId")!!, name = string("name")!!, sport = string("sport"), monitored = bool("monitored")!!,
            monitorType = string("monitorType"), keepAllEvents = bool("keepAllEvents"), alternateName = string("alternateName"),
            teamless = bool("teamless") ?: false,
            events = getValue("events").jsonArray.map { it.jsonObject.event(id, anchor) },
        )
    }

    private fun JsonObject.event(leagueId: Int, anchor: LocalDate): SportsEvent {
        val start = anchor.plusDays(int("day").toLong()).atTime(LocalTime.parse(string("time"))).toInstant(ZoneOffset.UTC)
        val date = start.atZone(ZoneOffset.UTC).toLocalDate()
        return SportsEvent(
            id = int("id"), externalId = string("externalId"), leagueId = leagueId, title = string("title")!!, start = start,
            // Sportarr stores the UTC date where it has no broadcast date: the fallback looks the same on the wire.
            broadcastDate = date.plusDays((this["broadcast"]?.jsonPrimitive?.int ?: 0).toLong()), broadcastTimezone = string("timezone"),
            season = string("season")?.let { season(it, date) }, round = string("round"), homeTeamName = string("home"),
            awayTeamName = string("away"), monitored = bool("monitored")!!, hasFile = bool("hasFile")!!,
            partNames = (this["parts"] as? JsonArray)?.map { it.jsonPrimitive.content }, listed = bool("listed") ?: true,
        )
    }

    companion object {
        /** In mock-seed's resources, so its jar carries it; the plugin's tests read it from their classpath. */
        const val RESOURCE = "sports-catalogue.json"
        const val FORMAT = 1

        /** The busy league's: none of the catalogue's ids or names. */
        const val BUSY_LEAGUE_ID = 90
        const val BUSY_LEAGUE_NAME = "Kestrel Club League"
        const val BUSY_AHEAD = 1000
        const val BUSY_BEHIND = 1000
        private const val BUSY_FIRST_ID = 1_000_000

        /** The catalogue in mock-seed's resources. */
        fun load(): SportsCatalog = parse(
            requireNotNull(SportsCatalog::class.java.classLoader.getResourceAsStream(RESOURCE)) { "$RESOURCE isn't on the classpath" }
                .use { it.readBytes().decodeToString() },
        )

        fun parse(text: String): SportsCatalog = SportsCatalog(Json.parseToJsonElement(text).jsonObject)

        private fun club(number: Int) = "Kestrel Club ${number.toString().padStart(2, '0')}"

        private val DATE = Regex("""\{date([+-]\d+)?}""")

        /** [template]'s {date}, {date-N}, {date+N} and {year} from [start]'s UTC date. */
        private fun expand(template: String, start: Instant): String {
            val date = start.atZone(ZoneOffset.UTC).toLocalDate()
            return DATE.replace(template) { match ->
                val day = date.plusDays(match.groupValues[1].takeIf { it.isNotEmpty() }?.removePrefix("+")?.toLong() ?: 0)
                "%04d.%02d.%02d".format(day.year, day.monthValue, day.dayOfMonth)
            }.replace("{year}", "${date.year}")
        }

        /**
         * A season as written: "{year}" is the start's UTC year, "{split}" the split season it falls in from July on
         * ("2026-2027"), "{splitShort}" the same with the second year short ("2026-27"); anything else as it is.
         */
        private fun season(written: String, date: LocalDate): String {
            val first = if (date.monthValue >= 7) date.year else date.year - 1
            return written.replace("{year}", "${date.year}")
                .replace("{splitShort}", "$first-${((first + 1) % 100).toString().padStart(2, '0')}")
                .replace("{split}", "$first-${first + 1}")
        }

        private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

        private fun JsonObject.string(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.bool(name: String): Boolean? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
    }
}

/**
 * A league as the fake Sportarr serves it. [teamless]: its page lists every event it stores, as Sportarr's does for a
 * fighting league whatever its scope; the catalogue's word, never sent.
 */
data class SportsLeague(
    val id: Int,
    val externalId: String,
    val name: String,
    val sport: String?,
    val monitored: Boolean,
    val monitorType: String?,
    val keepAllEvents: Boolean?,
    val alternateName: String?,
    val teamless: Boolean,
    val events: List<SportsEvent>,
)

/**
 * An event at an anchor: its [start] in UTC, its [broadcastDate] (the UTC date where the catalogue gives none, as Sportarr
 * stores it), its [round] and [season] as text, Sportarr's [partNames] where it sends them (fighting events alone).
 * [listed]: on its league's filtered page; stored only, it comes with showAll, or where the league lists all it stores.
 */
data class SportsEvent(
    val id: Int,
    val externalId: String?,
    val leagueId: Int,
    val title: String,
    val start: Instant,
    val broadcastDate: LocalDate,
    val broadcastTimezone: String?,
    val season: String?,
    val round: String?,
    val homeTeamName: String?,
    val awayTeamName: String?,
    val monitored: Boolean,
    val hasFile: Boolean,
    val partNames: List<String>?,
    val listed: Boolean,
) {
    /** The plugin's key for it (L644): its externalId where it reads as [A-Za-z0-9._-]{1,150}, else local:<id>. */
    val key: String get() = externalId?.takeIf { KEYABLE.matches(it) } ?: "local:$id"

    private companion object {
        val KEYABLE = Regex("[A-Za-z0-9._-]{1,150}")
    }
}

/**
 * A release the sports indexer lists, its [name] and [files] at an anchor, posted at [postedAt]; [whole]: seeded into the
 * mock with the clip under its names, else with every article missing, so a play of it fails as a broken release would.
 * Its [verdicts]: for its event and part first, then for any other it is judged for (a full event for each part, a weekend
 * pack for each session, a double header's name for each game).
 */
data class SportsRelease(
    val id: String,
    val name: String,
    val files: List<String>,
    val postedAt: Instant,
    val sizeBytes: Long,
    val grabs: Int,
    val whole: Boolean,
    val verdicts: List<SportsVerdict>,
) {
    /** As the sports indexer lists it: in TV/Sport, its guid its id's, its size and posted time its own. */
    fun fake(): FakeRelease = FakeRelease(
        guid = "sp-$id", title = name, category = FakeCatalog.TV_SPORT, ids = FakeIds(), grabs = grabs, fileNames = files,
        sizeBytes = sizeBytes, postedAt = postedAt,
    )
}

/**
 * What the plugin's judge should say of a release for [eventId]'s [part] (L647): [verdict], with an offered one's
 * [reason] or a left-out one's [kind] in L647's words; [reached]: whether the plugin's queries for that event, stage 1 and
 * then stage 2 as its runner sends them, find it in the sports indexer (which matches whole words alone, a word asked
 * twice standing twice), or only the judge's tests see it.
 */
data class SportsVerdict(
    val eventId: Int,
    val part: String,
    val verdict: Verdict,
    val reason: String?,
    val kind: String?,
    val reached: Boolean,
) {
    enum class Verdict { Accept, Offer, Reject }
}

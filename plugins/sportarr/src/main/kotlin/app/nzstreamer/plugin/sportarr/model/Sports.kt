package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.source.SportsList
import app.nzstreamer.plugin.sportarr.source.WireEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * How an event's releases are found and played (L643): slice A's families. Every sport not ruled yet is Generic, which
 * never plays a release by itself: the user picks one.
 */
enum class Family { TeamGame, Motorsport, FightCard, Generic }

/** A fight card's type (L643), which names its parts (L644). */
enum class CardType {
    /** "UFC 999: Quessel vs. Halde": early prelims, prelims and the main card. */
    Numbered,

    /** "UFC Fight Night: Brisk vs. Loam": prelims and the main card. */
    FightNight,

    /** A show that airs every week under one title: one part. */
    WeeklyShow,

    /** A bare "A vs. B" (boxing): one part. */
    Matchup,

    /** Any other named event (wrestling's): a pre-show and the main show. */
    Premium,
}

/**
 * The model of one kept list (L644), built off the main thread from what a read gave or list.json holds: the leagues that
 * count, in Sportarr's order; their events, newest first, each with its key, family and parts; and the motorsport weekends,
 * which group sessions for display alone. [readAt] and [cut] are the list's.
 */
data class Sports(
    val readAt: Instant,
    val cut: Boolean,
    val leagues: List<League>,
    val events: List<SportsEvent>,
    val weekends: List<Weekend>,
) {
    private val eventsByKey = events.associateBy { it.key }

    /** Each fight card's type by its key: kept with the list, so a later build gives the card the same parts (L644). */
    val cardTypes: Map<String, CardType> get() = events.mapNotNull { event -> event.cardType?.let { event.key to it } }.toMap()
    private val weekendsByKey = weekends.associateBy { it.key }
    private val weekendOfEvent = weekends.flatMap { weekend -> weekend.sessions.map { it.key to weekend } }.toMap()

    /** What the list shows, a row each: every weekend once, every other event alone; the one that starts last first. */
    val listed: List<Listed> = (weekends + events.filter { it.key !in weekendOfEvent })
        .sortedWith(compareByDescending<Listed> { row -> row.events.last().start }.thenBy { it.key })

    /** The event under [key] (an `event` page's argument), or null once it left the list. */
    fun event(key: String): SportsEvent? = eventsByKey[key]

    /** The weekend under [key] (a `weekend` page's argument), or null once it left the list. */
    fun weekend(key: String): Weekend? = weekendsByKey[key]

    /** The weekend [event] is a session of; null for any other event. */
    fun weekendOf(event: SportsEvent): Weekend? = weekendOfEvent[event.key]

    companion object {
        /**
         * The model of [list]: each event under the league whose route listed it (one listed under no kept league is left
         * out), its family stepped down where its title can't carry it, and each key kept once, the first the list holds
         * (newest first). A fight card keeps the type the list kept for its key; one seen for the first time is typed
         * against its league's other events. A weekly show is one only while another night of it is listed a week away, so
         * typing a card again on each read would move its parts to other keys and leave their watch state behind.
         */
        fun of(list: SportsList): Sports {
            val leagues = list.leagues.map { League(it.id, it.name, it.sport, it.alternateName) }
            val byId = leagues.associateBy { it.id }
            val cardTypes = list.events.groupBy { it.leagueId }.filterKeys { it in byId }
                .mapValues { (leagueId, own) -> Families.cardTypes(byId[leagueId]?.name, own) }
            val seen = HashSet<String>()
            val events = list.events.mapNotNull { event ->
                val league = byId[event.leagueId] ?: return@mapNotNull null
                val cardType = list.cardTypes[Keys.event(event.externalId, event.id)] ?: cardTypes[league.id]?.get(event.id)
                build(league, event, cardType).takeIf { seen.add(it.key) }
            }
            val sessions = events.filter { it.family == Family.Motorsport }.groupBy { it.league.id }
            val weekends = leagues.flatMap { league -> Weekends.of(league, sessions[league.id].orEmpty()) }
            return Sports(list.readAt, list.cut, leagues, events, weekends)
        }

        private fun build(league: League, event: WireEvent, cardType: CardType?): SportsEvent {
            val key = Keys.event(event.externalId, event.id)
            val family = Families.of(league.sport, event)
            val session = if (family == Family.Motorsport) Sessions.read(event.title) else null
            val card = if (family == Family.FightCard) cardType ?: CardType.Premium else null
            // The runtimes read the sport as the family does: the league's, the event's where the league has none.
            val sport = league.sport?.takeIf { it.isNotBlank() } ?: event.sport
            return SportsEvent(
                key = key,
                id = event.id,
                league = league,
                title = event.title,
                start = event.eventDate,
                broadcastDate = event.broadcastDate,
                round = event.round,
                season = event.season,
                homeTeamName = event.homeTeamName,
                awayTeamName = event.awayTeamName,
                family = family,
                cardType = card,
                session = session,
                parts = Parts.of(key, event.title, family, card, session, sport, event.eventTypePartNames),
            )
        }
    }
}

/** A league that counts (L642): Sportarr's id, its name and sport, and its other names, which motorsport reads (L649). */
data class League(val id: Int, val name: String, val sport: String?, val alternateName: String?) {
    /** Its other names one by one: Sportarr's [alternateName] holds several, split at commas, pipes or slashes. */
    val alternateNames: List<String>
        get() = alternateName.orEmpty().split(',', '|', '/').map(String::trim).filter(String::isNotEmpty)
}

/** What the list shows as one row: an event alone, or a motorsport weekend's sessions. */
sealed interface Listed {
    /** The page key: an event's key, or a weekend's. */
    val key: String
    val league: League

    /** Its events in time order: the event itself, or the weekend's sessions. */
    val events: List<SportsEvent>

    /** What plays of it, in order: an event's parts in card order, or a weekend's sessions in time order. */
    val items: List<Item>
}

/** One part of one event: what one play plays, kept by the host under the part's key. */
data class Item(val event: SportsEvent, val part: Part)

/**
 * One playable part of an event (L644): its [key] (the event's key and the part's [id], "ev-000731:main-card"), its
 * [label] ("Main Card" as Sportarr names it, a session's words, else the event's title) and its runtime in minutes, or
 * null where unknown.
 */
data class Part(val key: String, val id: String, val label: String, val runtimeMinutes: Int?)

/**
 * An event as the tab lists it. [start] is Sportarr's eventDate, in UTC; [broadcastDate] the date as written (L647 reads
 * it); [round] and [season] are text as Sportarr sends them. [cardType] is a fight card's, [session] a motorsport event's.
 */
data class SportsEvent(
    override val key: String,
    val id: Int,
    override val league: League,
    val title: String,
    val start: Instant,
    val broadcastDate: LocalDate?,
    val round: String?,
    val season: String?,
    val homeTeamName: String?,
    val awayTeamName: String?,
    val family: Family,
    val cardType: CardType?,
    val session: Session?,
    val parts: List<Part>,
) : Listed {
    /** The round's number (L644): its one whole number, else none. */
    val roundNumber: Int? = Rounds.number(round)

    /** The season's years (L644): the years [season] names, a split season's two, else [start]'s UTC year. */
    val seasonYears: List<Int> = Rounds.seasonYears(season, start)

    /** The part that marks the event watched: the main card, the main show, else the whole event. */
    val headline: Part get() = Parts.headline(parts)

    override val events: List<SportsEvent> get() = listOf(this)
    override val items: List<Item> get() = parts.map { Item(this, it) }
}

/**
 * The keys the host keeps an event's parts under (L644), forever: an event's is its externalId where that reads as 1 to
 * 150 letters, digits, dots, underscores and dashes ("ev-000731"), else "local:" and Sportarr's id; a part's is the
 * event's, a colon and the part's id. The host stores each as item:sportarr:<key>.
 */
object Keys {
    private val EXTERNAL_ID = Regex("""[A-Za-z0-9._-]{1,150}""")

    fun event(externalId: String?, id: Int): String = externalId?.takeIf { EXTERNAL_ID.matches(it) } ?: "local:$id"

    fun part(eventKey: String, partId: String): String = "$eventKey:$partId"
}

/** Rounds and seasons, which Sportarr sends as text (L644): "20", "Week 10", "Final"; "2026", "2024-25", "2025-2026". */
object Rounds {
    /** The one whole number in [round] when it holds exactly one ("Round 20" is 20), else none ("Final", "Round 20 Race 2"). */
    fun number(round: String?): Int? = NUMBER.findAll(round.orEmpty()).toList().singleOrNull()?.value?.toIntOrNull()

    /**
     * [round] trimmed and case folded where it holds no digit at all ("Final"), which weekends group by; else none.
     */
    fun text(round: String?): String? = round?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.none(Char::isDigit) }

    /**
     * The years [season] names: each four-digit year in it, and a split season's second written short ("2024-25" is 2024
     * and 2025); with none, [start]'s year in UTC.
     */
    fun seasonYears(season: String?, start: Instant): List<Int> {
        val written = season.orEmpty()
        val years = YEAR.findAll(written).map { it.value.toInt() }.toMutableList()
        SPLIT.find(written)?.let { split ->
            val first = split.groupValues[1].toInt()
            // The second year's century: the first's, or the next one's ("1999-00").
            val second = (first / 100 * 100 + split.groupValues[2].toInt()).let { if (it <= first) it + 100 else it }
            if (second == first + 1) years += second
        }
        return years.distinct().ifEmpty { listOf(start.atOffset(ZoneOffset.UTC).year) }
    }

    private val NUMBER = Regex("""\d+""")
    private val YEAR = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
    private val SPLIT = Regex("""(?<!\d)((?:19|20)\d{2})\s*[-/–]\s*(\d{2})(?!\d)""")
}

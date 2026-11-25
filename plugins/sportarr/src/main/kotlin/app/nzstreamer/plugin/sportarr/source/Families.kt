package app.nzstreamer.plugin.sportarr.source

import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Sessions
import java.util.Locale
import kotlin.math.abs

/**
 * Which family an event belongs to (L643): from the league's sport (the event's when the league has none), then the
 * title. The sports and their names, each in its family, were written for this plugin from how sports are organised,
 * named and broadcast, by a writer who read no other project's lists; no league name is built in (C26). Every other
 * sport, an unknown one or none, is Generic.
 *
 * An event steps down to Generic, never up, where its title can't carry its family's matching: a team game with no two
 * sides, a motorsport event whose title is nothing but session words, a fight card whose title is nothing but dates and
 * numbers.
 */
object Families {
    /** The family [sport] names, lower-cased, its spaces and hyphens folded. */
    fun ofSport(sport: String?): Family = SPORTS[fold(sport)] ?: Family.Generic

    /** [event]'s family: its league's sport's, [leagueSport], or its own when the league has none, stepped down. */
    fun of(leagueSport: String?, event: WireEvent): Family {
        val family = ofSport(leagueSport?.takeIf { it.isNotBlank() } ?: event.sport)
        return when (family) {
            Family.TeamGame -> if (hasSides(event)) family else Family.Generic
            Family.Motorsport -> if (sessionWordsAlone(event.title)) Family.Generic else family
            Family.FightCard -> if (datesAndNumbersAlone(event.title)) Family.Generic else family
            Family.Generic -> family
        }
    }

    /**
     * [title]'s two sides, split at the first of " vs. ", " vs ", " v ", " @ " and " at " it holds (in that order, ignoring
     * case), each side trimmed; null when none splits it into two sides with words.
     */
    fun sides(title: String): Pair<String, String>? = SEPARATORS.firstNotNullOfOrNull { separator ->
        val at = title.indexOf(separator, ignoreCase = true)
        if (at < 0) return@firstNotNullOfOrNull null
        val home = title.substring(0, at).trim()
        val away = title.substring(at + separator.length).trim()
        (home to away).takeIf { home.isNotEmpty() && away.isNotEmpty() }
    }

    /**
     * A fight card's type (L643), from its title and its league ([leagueName]), in this order: numbered (the league's or the
     * title's first word, then a number: "UFC 999: Quessel vs. Halde"); fight night (that word, then "Fight Night"); a weekly
     * show (a title that, its digits and dates taken away, another of the league's listed events [leagueEvents] shares, six
     * to eight days apart); a matchup (a bare "A vs. B", boxing's); else a premium event (a named wrestling event).
     */
    fun cardType(leagueName: String?, event: WireEvent, leagueEvents: List<WireEvent>): CardType =
        cardTypes(leagueName, leagueEvents + event)[event.id] ?: CardType.Premium

    /** [cardType] for each of a league's [events] at once, by event id. */
    fun cardTypes(leagueName: String?, events: List<WireEvent>): Map<Int, CardType> {
        val weekly = weeklyShows(events.distinctBy { it.id })
        val leagueWord = words(leagueName.orEmpty()).firstOrNull()
        return events.associate { event ->
            val words = words(event.title)
            // Where the card's own word stands: the title's first word, and the league's first word wherever it is.
            val leads = words.indices.filter { it == 0 || words[it] == leagueWord }
            event.id to when {
                leads.any { words.getOrNull(it + 1)?.isNumber() == true } -> CardType.Numbered
                leads.any { words.getOrNull(it + 1) == "fight" && words.getOrNull(it + 2) == "night" } -> CardType.FightNight
                event.id in weekly -> CardType.WeeklyShow
                bare(event.title) -> CardType.Matchup
                else -> CardType.Premium
            }
        }
    }

    private fun hasSides(event: WireEvent): Boolean =
        (!event.homeTeamName.isNullOrBlank() && !event.awayTeamName.isNullOrBlank()) || sides(event.title) != null

    /** Whether [title] is nothing but session words, each perhaps with its number ("Practice 1", "Race 2"). */
    private fun sessionWordsAlone(title: String): Boolean {
        val words = words(title)
        var at = 0
        while (at < words.size) {
            val session = SESSIONS.firstOrNull { it == words.subList(at, minOf(words.size, at + it.size)) } ?: return false
            at += session.size
            if (words.getOrNull(at)?.isNumber() == true) at++
        }
        return true
    }

    /** Whether [title] holds no word but dates and numbers ("2026-11-21", "21 November 2026", "999"). */
    private fun datesAndNumbersAlone(title: String): Boolean = words(title).all { it.isNumber() || it in MONTHS }

    /** A bare "A vs. B": two sides and nothing before them, no name and colon or dash. */
    private fun bare(title: String): Boolean = sides(title) != null && NAMED.none { it in title }

    /**
     * The ids of [events] that are weekly shows: their title less its numbers and dates is another's too, and the two start
     * six to eight days apart. Each show is compared with the others of its title within eight days of it alone.
     */
    private fun weeklyShows(events: List<WireEvent>): Set<Int> = events
        .groupBy { event -> words(event.title).filterNot { it.isNumber() || it in MONTHS }.joinToString(" ") }
        .filterKeys { it.isNotEmpty() }
        .values
        .flatMap { shows ->
            val byStart = shows.sortedBy { it.eventDate }
            byStart.filterIndexed { index, show ->
                fun apart(other: Int) = abs(byStart[other].eventDate.toEpochMilli() - show.eventDate.toEpochMilli())
                val later = (index + 1 until byStart.size).asSequence()
                val earlier = (index - 1 downTo 0).asSequence()
                listOf(later, earlier).any { side -> side.takeWhile { apart(it) <= WEEK_APART.last }.any { apart(it) >= WEEK_APART.first } }
            }
        }
        .map { it.id }
        .toSet()

    /** [text]'s words, lower-cased: runs of letters and digits. */
    private fun words(text: String): List<String> = text.lowercase(Locale.ROOT).split(NOT_A_WORD).filter { it.isNotEmpty() }

    private fun String.isNumber(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    /** [sport] as the families and the runtimes read it: lower-cased, its spaces and hyphens folded into one space. */
    internal fun fold(sport: String?): String =
        sport.orEmpty().lowercase(Locale.ROOT).split(SPACES_AND_HYPHENS).filter { it.isNotEmpty() }.joinToString(" ")

    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES_AND_HYPHENS = Regex("""[\s-]+""")

    private val SEPARATORS = listOf(" vs. ", " vs ", " v ", " @ ", " at ")

    /** What makes a title more than its two sides: a name before them. */
    private val NAMED = listOf(":", " - ", " – ", " | ")

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val WEEK_APART = 6 * DAY_MS..8 * DAY_MS

    /**
     * Each sport's names, folded, in its family: games of two teams; motor racing of any kind; combat sports whose events
     * are cards of bouts, and wrestling. Sports in none (individual sports, races on foot, skiing, golf) are Generic.
     */
    private val SPORTS: Map<String, Family> = buildMap {
        // "football" is American football in Sportarr's words; association football is "soccer".
        listOf(
            "soccer", "association football", "futbol",
            "football", "american football", "gridiron football",
            "canadian football",
            "australian rules football", "australian football", "aussie rules",
            "gaelic football",
            "hurling", "camogie",
            "rugby union", "rugby", "rugby sevens", "rugby 7s",
            "rugby league",
            "basketball", "3x3 basketball",
            // Every hockey is a team game: "hockey" alone is ice hockey where North American sources write it.
            "ice hockey", "hockey",
            "field hockey",
            "roller hockey", "inline hockey", "rink hockey",
            "bandy", "floorball", "baseball", "softball", "cricket",
            "volleyball", "beach volleyball",
            "handball", "team handball",
            "water polo", "waterpolo",
            "futsal", "beach soccer", "lacrosse", "netball", "korfball", "kabaddi", "sepak takraw", "polo", "shinty", "curling",
        ).forEach { put(it, Family.TeamGame) }
        listOf(
            "motorsport", "motorsports", "motor sport", "motor racing", "auto racing", "car racing",
            "open wheel racing", "single seater racing", "formula racing",
            "touring car racing", "touring cars",
            "sports car racing", "sportscar racing", "endurance racing",
            "stock car racing", "oval racing",
            "drag racing",
            "rallying", "rally", "rally racing", "rallycross",
            "karting", "kart racing", "go kart racing",
            "truck racing",
            "drifting",
            "motorcycle racing", "motorbike racing", "motorcycling", "motorcycle road racing", "superbike racing",
            "motocross", "supercross", "motorcycle trials",
            "speedway", "motorcycle speedway",
            "powerboat racing", "powerboating",
        ).forEach { put(it, Family.Motorsport) }
        listOf(
            "boxing", "bare knuckle boxing", "bareknuckle boxing",
            "mixed martial arts", "mma",
            "kickboxing", "kick boxing",
            "muay thai", "thai boxing", "lethwei",
            "submission grappling", "grappling", "brazilian jiu jitsu", "jiu jitsu",
            "combat sports", "combat sport", "fighting",
            "wrestling", "professional wrestling", "pro wrestling",
        ).forEach { put(it, Family.FightCard) }
    }

    /**
     * Session words as the step-down reads a title, longest first so "sprint qualifying" is one session: the sessions' own
     * words, which also label a weekend's sessions, and "grand prix" and "gp", which name a race but not where it runs.
     */
    private val SESSIONS: List<List<String>> = (Sessions.words + listOf(listOf("grand", "prix"), listOf("gp"))).sortedByDescending { it.size }

    /** Month names, which a title's date may hold. */
    internal val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )
}

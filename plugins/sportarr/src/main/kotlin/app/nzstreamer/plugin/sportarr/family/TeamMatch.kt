package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.NameContext
import app.nzstreamer.plugin.sportarr.match.NumberWord
import app.nzstreamer.plugin.sportarr.match.Queries
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SportsName
import app.nzstreamer.plugin.sportarr.match.Token
import app.nzstreamer.plugin.sportarr.match.Tokens
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.ui.Words
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import app.nzstreamer.plugin.sportarr.match.Words as NameWords

/**
 * Team games (L648). A game is in a release name when both its sides are there, each by its full words together, all its
 * distinctive words or its nickname, in any order and with any separator, and the name's date is the game's (the shared
 * rules held a date to that). A name with no date is the game where a week, matchday or round equal to the game's round
 * and the season's year say so, while the sides meet no other time within three days. What names the game less surely is
 * a possible match, with its reason; both sides of another listed game, or one side with a team not in this game, leave a
 * name out as another game's.
 *
 * It reads the kept list ([sports]) for the sides' other meetings, which a date in a name could name too, and for every
 * listed team, which tells another game's names from this one's.
 */
class TeamMatch(private val sports: Sports) : FamilyMatcher {
    /** Every listed game with its sides, by its key. */
    private val games: Map<String, Game> by lazy {
        sports.events.filter { it.family == Family.TeamGame }.mapNotNull { event -> gameOf(event)?.let { event.key to it } }.toMap()
    }

    /** The listed games by their two sides, whichever is at home. */
    private val meetings: Map<Set<String>, List<Game>> by lazy { games.values.groupBy { it.pair } }

    /** Every listed team, once. */
    private val teams: List<Side> by lazy { games.values.flatMap { listOf(it.home, it.away) }.distinctBy { it.key } }

    override fun context(event: SportsEvent): NameContext {
        val game = game(event)
        return NameContext(
            title = event.title,
            league = event.league.name,
            sides = listOfNotNull(game?.home?.name, game?.away?.name),
            series = event.league.alternateNames,
        )
    }

    /**
     * Stage 1: both sides' distinctive words ("Lantern Bay Copper Crown"), and the league's name with the game's date as
     * three numbers ("Premier League 2026 11 21"); stage 2: the first distinctive word of each side, the away side's first
     * ("Copper Lantern"). Never a year alone or a compact date, which names glue to other words.
     */
    override fun queries(event: SportsEvent, part: Part): Queries {
        val game = game(event) ?: return Queries(emptyList())
        val date = event.broadcastDate ?: utcDate(event)
        return Queries(
            first = listOf(
                "${game.home.searched} ${game.away.searched}",
                "${event.league.name} ${date.year} ${twoDigits(date.monthValue)} ${twoDigits(date.dayOfMonth)}",
            ),
            then = "${game.away.firstWord} ${game.home.firstWord}",
        )
    }

    /** The sides' other listed meetings, either side at home: a date in a name that is one of theirs is theirs. */
    override fun related(event: SportsEvent): List<SportsEvent> = game(event)?.let { game -> others(game).map { it.event } }.orEmpty()

    override fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict {
        val game = game(event) ?: return OTHER_GAME
        val read = Read(name)
        val found = teams.filter { it.foundBy(read) != null }.map { it.key }.toSet() + listOfNotNull(
            game.home.key.takeIf { game.home.foundBy(read) != null },
            game.away.key.takeIf { game.away.foundBy(read) != null },
        )
        val home = game.home.key in found
        val away = game.away.key in found
        val dated = name.dates.isNotEmpty()
        return when {
            anotherGame(game, read, found) -> OTHER_GAME
            home && away -> bothSides(game, name, dated)
            home || away -> when {
                anotherTeam(game, read) -> OTHER_GAME
                dated && leagueNamed(event, read) -> Verdict.Offer(Words.ONE_TEAM)
                else -> OTHER_GAME
            }
            codes(name) && (dated || leagueNamed(event, read)) -> Verdict.Offer(Words.TEAM_CODES)
            else -> OTHER_GAME
        }
    }

    /** A name holding both of [game]'s sides: its sibling markers, a cricket day, its date or its round. */
    private fun bothSides(game: Game, name: SportsName, dated: Boolean): Verdict {
        if (!game.siblings.containsAll(name.siblings)) return OTHER_GAME
        if (game.cricket && name.numbered(NumberWord.Day).isNotEmpty()) return Verdict.Offer(Words.ONE_DAY)
        if (!dated) return undated(game, name)
        // A double header: the same sides twice on one day, told apart by the game's number alone.
        if (others(game).none { sameDay(it.event, game.event) }) return Verdict.Accept()
        val numbers = name.numbered(NumberWord.Game).toSet()
        return when {
            numbers.isEmpty() || game.number == null -> Verdict.Offer(Words.NO_GAME_NUMBER)
            numbers == setOf(game.number) -> Verdict.Accept()
            else -> OTHER_GAME
        }
    }

    /**
     * Both sides and no date: a year that isn't the season's is another season's game; a week, matchday or round equal to
     * the game's, with the season's year, is the game while the sides meet no other time within three days; a week with
     * the round unknown, or the sides alone where they meet once within 45 days, a possible match.
     */
    private fun undated(game: Game, name: SportsName): Verdict {
        val event = game.event
        val years = name.years + name.seasons.flatMap { listOf(it.first, it.second) }
        if (years.isNotEmpty() && years.none { it in event.seasonYears }) return OTHER_GAME
        val rounds = listOf(NumberWord.Week, NumberWord.Matchday, NumberWord.Round).flatMap { name.numbered(it) }
        if (rounds.isNotEmpty()) {
            val round = event.roundNumber ?: return Verdict.Offer(Words.WEEK_NO_DATE)
            if (rounds.any { it != round }) return OTHER_GAME
            if (years.isNotEmpty() && others(game).none { apart(it, game) <= THREE_DAYS }) return Verdict.Accept()
        }
        return if (others(game).none { apart(it, game) <= FORTY_FIVE_DAYS }) Verdict.Offer(Words.NO_DATE) else OTHER_GAME
    }

    /** Both sides of another listed game, one of them at least by words this game's sides don't have. */
    private fun anotherGame(game: Game, read: Read, found: Set<String>): Boolean = meetings.any { (pair, listed) ->
        val other = listed.first()
        pair != game.pair && found.containsAll(pair) && (other.home.foreign(read, game) || other.away.foreign(read, game))
    }

    /**
     * Another team beside one of [game]'s sides: a listed team named by words this game's sides don't have, or a side of
     * the name's (around its "vs") none of whose words are theirs, past the league's words and a team code.
     */
    private fun anotherTeam(game: Game, read: Read): Boolean {
        if (teams.any { it.key !in game.keys && it.foreign(read, game) }) return true
        val sides = read.name.sides ?: return false
        val league = leagueWords(game.event)
        return listOf(sides.before, sides.after).any { half ->
            val named = half.filter { it.word !in league && !isCode(it) }
            named.any { it.hasLetter } && named.none { it.word in game.words }
        }
    }

    /** Whether the league's name, or one of its other names, stands in the name. */
    private fun leagueNamed(event: SportsEvent, read: Read): Boolean =
        (listOf(event.league.name) + event.league.alternateNames).any { read.has(words(it)) }

    private fun leagueWords(event: SportsEvent): Set<String> =
        (listOf(event.league.name) + event.league.alternateNames).flatMap(::words).toSet()

    /** Two team codes at the name's separator: "LBF.vs.CCU", "LBF @ CCU". */
    private fun codes(name: SportsName): Boolean = name.sides?.let { isCode(it.before.last()) && isCode(it.after.first()) } == true

    /** A team code: two to four upper-case letters, not a club form ("FC"). */
    private fun isCode(token: Token): Boolean =
        token.written.length in 2..4 && token.written.all { it.isLetter() && it.isUpperCase() } && token.word !in COMMON

    private fun game(event: SportsEvent): Game? = games[event.key]?.takeIf { it.event == event } ?: gameOf(event)

    private fun others(game: Game): List<Game> = meetings[game.pair].orEmpty().filter { it.event.key != game.event.key }

    /** A game of [event]'s sides: its team fields when both are sent, else its title's two sides. */
    private fun gameOf(event: SportsEvent): Game? {
        val home = event.homeTeamName?.trim().orEmpty()
        val away = event.awayTeamName?.trim().orEmpty()
        if (home.isNotEmpty() && away.isNotEmpty()) return Game(event, Side(home), Side(away))
        val (titleHome, titleAway) = Families.sides(event.title) ?: return null
        // A title may name more than the sides: a competition before a colon, a game's number after a dash.
        val before = titleHome.substringAfterLast(": ").trim()
        val after = TITLE_NOTES.fold(titleAway) { side, note -> side.substringBefore(note) }.trim()
        return if (before.isNotEmpty() && after.isNotEmpty()) Game(event, Side(before), Side(after)) else null
    }

    /** One listed game: its event and its sides, with what the rules read of them. */
    private class Game(val event: SportsEvent, val home: Side, val away: Side) {
        val pair: Set<String> = setOf(home.key, away.key)
        val keys: Set<String> get() = pair
        val words: Set<String> = (home.full + away.full).toSet()

        /** The sibling markers the game itself carries: a women's league, a youth side. */
        val siblings: Set<String> by lazy {
            listOf(event.title, event.league.name, home.name, away.name).flatMap { SportsName.read(it).siblings }.toSet()
        }

        /** The number in its title's "Game N", a double header's. */
        val number: Int? by lazy { SportsName.read(event.title).numbered(NumberWord.Game).singleOrNull() }

        val cricket: Boolean get() = Families.fold(event.league.sport) == CRICKET
    }

    /** A release name's identity words, read once for every side's search in it. */
    private class Read(val name: SportsName) {
        val words: List<String> = name.identity.map { it.word }
        val present: Set<String> = words.toSet()

        /** Whether [phrase] stands together among the words. */
        fun has(phrase: List<String>): Boolean =
            phrase.isNotEmpty() && (0..words.size - phrase.size).any { at -> phrase.indices.all { words[at + it] == phrase[it] } }
    }

    /** The words by which this side stands in [read]'s name, or null where it doesn't. */
    private fun Side.foundBy(read: Read): List<String>? = when {
        read.has(full) -> full
        distinctive.isNotEmpty() && read.present.containsAll(distinctive) -> distinctive
        nickname != null && nickname in read.present -> listOf(nickname)
        else -> null
    }

    /** Whether this side stands in [read]'s name by words [game]'s own sides don't have. */
    private fun Side.foreign(read: Read, game: Game): Boolean = foundBy(read)?.let { !game.words.containsAll(it) } == true

    private companion object {
        val OTHER_GAME = Verdict.Reject(RejectKind.OtherEvents)

        val THREE_DAYS: Duration = Duration.ofDays(3)
        val FORTY_FIVE_DAYS: Duration = Duration.ofDays(45)

        const val CRICKET = "cricket"

        /** What follows a side in a title: " - Game 1", " (Round 9)". */
        val TITLE_NOTES = listOf(" - ", " – ", " (", " | ")

        fun words(text: String): List<String> = Tokens.of(text).map { it.word }

        fun utcDate(event: SportsEvent): LocalDate = event.start.atOffset(ZoneOffset.UTC).toLocalDate()

        /** Whether two events are on one day: their UTC dates or their broadcast dates. */
        fun sameDay(a: SportsEvent, b: SportsEvent): Boolean =
            setOfNotNull(utcDate(a), a.broadcastDate).intersect(setOfNotNull(utcDate(b), b.broadcastDate)).isNotEmpty()

        fun apart(a: Game, b: Game): Duration = Duration.between(a.event.start, b.event.start).abs()

        fun twoDigits(number: Int): String = number.toString().padStart(2, '0')
    }
}

/**
 * A team as release names name it (L648): its [full] words; its [distinctive] words, the full ones less club forms and
 * common words (all of them where nothing else is left); and its [nickname], its last word where that is four letters or
 * more and neither common nor a word of a sibling marker ("Herons" of "Lantern Bay Herons"). Words are folded as a
 * name's are.
 */
class Side(val name: String) {
    private val tokens: List<Token> = Tokens.of(name)
    private val distinctiveTokens: List<Token> = tokens.filter { it.word !in COMMON }.ifEmpty { tokens }

    val full: List<String> = tokens.map { it.word }
    val distinctive: List<String> = distinctiveTokens.map { it.word }
    val nickname: String? = full.lastOrNull()?.takeIf { word ->
        word.length >= 4 && word.all(Char::isLetter) && word !in COMMON && word !in NameWords.siblingWords
    }

    /** The side as one key: its full words. */
    internal val key: String = full.joinToString(" ")

    /** Its distinctive words as written, for a search ("Lantern Bay"). */
    internal val searched: String = distinctiveTokens.joinToString(" ") { it.written }

    /** Its first distinctive word as written, stage 2's ("Lantern"). */
    internal val firstWord: String = distinctiveTokens.firstOrNull()?.written.orEmpty()
}

/** Club forms and words many clubs share, which tell no side from another (L648). */
private val COMMON = setOf(
    "fc", "afc", "cf", "sc", "ac", "cd", "fk", "club", "the", "city", "united", "town", "athletic", "rovers", "county",
)

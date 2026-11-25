package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SharedRules
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.NBA
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.WireEvent
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L648's team games: a game is in a name when both its sides are (their full words together, all their distinctive words,
 * or their nickname, in any order) and the name's date is the game's; with no date, a week, matchday or round equal to the
 * game's, with the season's year, while the sides meet no other time within three days. What names it less surely is a
 * possible match with its reason, and another game's sides leave a name out. Every name goes through the shared rules
 * first, as the runner judges it.
 */
class TeamMatchTest {
    private val premier = KeptLeague(1, "Premier League", "Soccer", alternateName = "EPL")
    private val cup = KeptLeague(14, "Kestrel Cup", "Soccer")
    private val baseball = KeptLeague(5, "Kestrel Baseball League", "Baseball")

    /** A game of [league] between [home] and [away] as Sportarr lists it, its team fields given. */
    private fun game(
        league: KeptLeague,
        id: Int,
        start: String,
        home: String,
        away: String,
        round: String? = "13",
        season: String = "2026-2027",
        title: String = "$home vs $away",
    ): WireEvent = on(league, id, at(start), title, round = round).copy(homeTeamName = home, awayTeamName = away, season = season)

    private val list = sports(
        premier to listOf(
            game(premier, 1001, "2026-11-21T15:00", "Lantern Bay FC", "Copper Crown United"),
            game(premier, 1004, "2026-11-21T15:00", "Saltmere City", "Lowmarsh Rovers"),
            game(premier, 1014, "2026-11-28T15:00", "Wrenford Albion", "Lantern Bay FC", round = "14"),
        ),
        NBA to listOf(game(NBA, 1501, "2026-11-20T01:30", "Easton Comets", "Lantern Bay Herons", round = null, season = "2026-27")),
    )
    private val teams = TeamMatch(list)
    private val lbf = list.byId(1001)
    private val herons = list.byId(1501)

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    private fun judge(event: SportsEvent, name: String, matcher: TeamMatch = teams, posted: Instant? = null, size: Long? = null): Verdict =
        SharedRules.judge(matcher, event, event.headline, name, posted, size)

    private val accepted = Verdict.Accept()
    private val otherGame = Verdict.Reject(RejectKind.OtherEvents)

    private fun offered(reason: String) = Verdict.Offer(reason)

    /** Each row's name judged for [event], each verdict against the row's; the names that disagree, with what they got. */
    private fun wrong(event: SportsEvent, rows: List<Pair<String, Verdict>>, matcher: TeamMatch = teams): String =
        rows.mapNotNull { (name, expected) -> judge(event, name, matcher).takeIf { it != expected }?.let { "$name: $it, not $expected" } }
            .joinToString("\n")

    @Test
    fun `a game's sides are its team fields, else its title's two sides`() {
        assertEquals(listOf("Lantern Bay FC", "Copper Crown United"), teams.context(lbf).sides)

        val titled = sports(premier to listOf(on(premier, 1004, at("2026-11-21T15:00"), "Saltmere City v Lowmarsh Rovers")))
        val byTitle = TeamMatch(titled)
        assertEquals(listOf("Saltmere City", "Lowmarsh Rovers"), byTitle.context(titled.byId(1004)).sides)
        assertEquals(accepted, judge(titled.byId(1004), "Premier.League.2026.11.21.Saltmere.City.vs.Lowmarsh.Rovers.1080p.WEB.h264-KESTREL", byTitle))

        // A title's words after a dash are about the game, not a side.
        val numbered = sports(baseball to listOf(on(baseball, 1401, at("2026-11-20T15:00"), "Kestrel Club 03 vs Kestrel Club 07 - Game 1")))
        assertEquals(listOf("Kestrel Club 03", "Kestrel Club 07"), TeamMatch(numbered).context(numbered.byId(1401)).sides)

        // The fields win over the title.
        val swapped = sports(premier to listOf(game(premier, 1101, "2026-11-21T15:00", "Kestrel Vale", "Duskwater FC", title = "Duskwater FC vs Kestrel Vale")))
        assertEquals(listOf("Kestrel Vale", "Duskwater FC"), TeamMatch(swapped).context(swapped.byId(1101)).sides)
    }

    @Test
    fun `a side's distinctive words leave club forms and common words out, and its nickname is its last uncommon word`() {
        val rows = listOf(
            Triple("Lantern Bay FC", listOf("lantern", "bay"), null),
            Triple("Copper Crown United", listOf("copper", "crown"), null),
            Triple("Saltmere City", listOf("saltmere"), null),
            Triple("Lowmarsh Rovers", listOf("lowmarsh"), null),
            Triple("Wrenford Albion", listOf("wrenford", "albion"), "albion"),
            Triple("Lantern Bay Herons", listOf("lantern", "bay", "herons"), "herons"),
            Triple("Marrowby Lynx", listOf("marrowby", "lynx"), "lynx"),
            Triple("Kestrel Vale", listOf("kestrel", "vale"), "vale"),
            Triple("Kestrel Club 03", listOf("kestrel", "03"), null),
            // A sibling marker is a distinctive word, never a nickname.
            Triple("Lantern Bay FC Women", listOf("lantern", "bay", "women"), null),
            Triple("Lantern Bay FC Reserve Team", listOf("lantern", "bay", "reserve", "team"), null),
            // Nothing left but club forms and common words: the full words.
            Triple("County Athletic FC", listOf("county", "athletic", "fc"), null),
        )
        val wrong = rows.mapNotNull { (name, distinctive, nickname) ->
            val side = Side(name)
            "$name: ${side.distinctive}, ${side.nickname}".takeIf { side.distinctive != distinctive || side.nickname != nickname }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(listOf("lantern", "bay", "fc"), Side("Lantern Bay FC").full)
    }

    @Test
    fun `both sides by their full words, their distinctive words or their nicknames, in any order, with the date, are the game`() {
        val forGame = listOf(
            "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL" to accepted,
            "Lantern.Bay.vs.Copper.Crown.2026.11.21.720p.WEB.h264-WREN" to accepted,
            "Copper.Crown.United.v.Lantern.Bay.FC.2026.11.21.1080p.HDTV.x264-ORCHID" to accepted,
            "Lantern Bay FC - Copper Crown United 21.11.2026 1080p WEB h264-WREN" to accepted,
            "Copper.Crown.United.Lantern.Bay.FC.20261121.720p.WEB.h264-KESTREL" to accepted,
        )
        assertEquals("", wrong(lbf, forGame))
        val forHerons = listOf(
            "NBA.2026.11.19.Easton.Comets.vs.Lantern.Bay.Herons.1080p.WEB.h264-KESTREL" to accepted,
            "NBA.2026.11.19.Comets_@_Herons.720p.WEB.h264-WREN" to accepted,
            "NBA.2026.11.20.Herons.at.Easton.Comets.720p.WEB.h264-ORCHID" to accepted,
        )
        assertEquals("", wrong(herons, forHerons))
    }

    @Test
    fun `another listed game's name that holds this game's distinctive words is the other game's`() {
        val sameTowns = sports(
            premier to listOf(game(premier, 1010, "2026-11-21T15:00", "Saltmere City", "Marrowby United")),
            NBA to listOf(game(NBA, 1502, "2026-11-21T01:30", "Saltmere Gulls", "Marrowby Lynx", round = null)),
        )
        val matcher = TeamMatch(sameTowns)
        val nbaName = "NBA.2026.11.20.Saltmere.Gulls.vs.Marrowby.Lynx.1080p.WEB.h264-KESTREL"
        val soccerName = "Premier.League.2026.11.21.Saltmere.City.vs.Marrowby.United.1080p.WEB.h264-KESTREL"
        assertEquals(otherGame, judge(sameTowns.byId(1010), nbaName, matcher), "Saltmere and Marrowby, but the Gulls and the Lynx")
        assertEquals(accepted, judge(sameTowns.byId(1010), soccerName, matcher))
        assertEquals(accepted, judge(sameTowns.byId(1502), nbaName, matcher))
        assertEquals(otherGame, judge(sameTowns.byId(1502), soccerName, matcher))
    }

    @Test
    fun `a game is searched by both sides' distinctive words and by its league and date, then by one word of each, away first`() {
        val queries = teams.queries(lbf, lbf.headline)
        assertEquals(listOf("Lantern Bay Copper Crown", "Premier League 2026 11 21"), queries.first)
        assertEquals("Copper Lantern", queries.then)

        val nba = teams.queries(herons, herons.headline)
        assertEquals(listOf("Easton Comets Lantern Bay Herons", "NBA 2026 11 20"), nba.first)
        assertEquals("Lantern Easton", nba.then)

        // An evening game's date as Sportarr gives it where it isn't the UTC date.
        val gulls = game(NBA, 1502, "2026-11-22T01:30", "Saltmere Gulls", "Marrowby Lynx", round = null).copy(broadcastDate = LocalDate.of(2026, 11, 21))
        val evening = sports(NBA to listOf(gulls))
        assertEquals("NBA 2026 11 21", TeamMatch(evening).queries(evening.byId(1502), evening.byId(1502).headline).first[1])
    }

    @Test
    fun `the catalogue's game has three names accepted, two possible matches and the rest left out`() {
        val matcher = TeamMatch(Catalogue.sports)
        val rows = Catalogue.verdicts(Family.TeamGame).filter { (_, verdict) -> verdict.eventId == 1001 }
        val wrong = rows.mapNotNull { (release, verdict) -> Catalogue.disagreement(matcher, release, verdict) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        fun ids(verdict: String) = rows.filter { it.second.verdict.name == verdict }.map { it.first.id }.toSet()
        assertEquals(setOf("lbf-ccu-kestrel", "lbf-ccu-wren", "lbf-ccu-orchid"), ids("Accept"))
        assertEquals(setOf("lbf-ccu-no-date", "lbf-ccu-codes"), ids("Offer"))
        assertEquals(7, ids("Reject").size, "two highlights, a score, three other games and the film")
    }

    @Test
    fun `every other team game's release in the catalogue gets the verdict its row gives`() {
        val matcher = TeamMatch(Catalogue.sports)
        val rows = Catalogue.verdicts(Family.TeamGame).filter { (_, verdict) -> verdict.eventId != 1001 }
        assertTrue(rows.map { it.second.eventId }.toSet().containsAll(setOf(1012, 1016, 1017, 1401, 1402, 1501, 1502)))
        val wrong = rows.mapNotNull { (release, verdict) -> Catalogue.disagreement(matcher, release, verdict) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `the same clubs a day apart each have their own date, and a name with none is neither's`() {
        val series = sports(
            baseball to listOf(
                game(baseball, 1403, "2026-11-18T17:00", "Kestrel Club 07", "Kestrel Club 05", round = null, season = "2026"),
                game(baseball, 1404, "2026-11-19T17:00", "Kestrel Club 05", "Kestrel Club 07", round = null, season = "2026"),
            ),
        )
        val matcher = TeamMatch(series)
        val first = series.byId(1403)
        val second = series.byId(1404)
        assertEquals(listOf(second), matcher.related(first))
        assertEquals(listOf(first), matcher.related(second))
        val wednesday = "Kestrel.Club.07.vs.Kestrel.Club.05.2026.11.18.1080p.WEB.h264-KESTREL"
        val thursday = "Kestrel.Club.05.vs.Kestrel.Club.07.2026.11.19.1080p.WEB.h264-KESTREL"
        val undated = "Kestrel.Club.07.vs.Kestrel.Club.05.1080p.WEB.h264-WREN"
        assertEquals("", wrong(first, listOf(wednesday to accepted, thursday to otherGame, undated to otherGame), matcher))
        assertEquals("", wrong(second, listOf(wednesday to otherGame, thursday to accepted, undated to otherGame), matcher))
    }

    @Test
    fun `a cup second leg with home and away swapped is told apart from the league game by its date`() {
        val both = sports(
            premier to listOf(game(premier, 1001, "2026-11-21T15:00", "Lantern Bay FC", "Copper Crown United")),
            cup to listOf(game(cup, 1301, "2026-12-03T19:45", "Copper Crown United", "Lantern Bay FC", round = "Quarterfinals")),
        )
        val matcher = TeamMatch(both)
        val league = both.byId(1001)
        val secondLeg = both.byId(1301)
        assertEquals(listOf(secondLeg), matcher.related(league))
        val legName = "Kestrel.Cup.2026.12.03.Copper.Crown.United.vs.Lantern.Bay.FC.1080p.WEB.h264-KESTREL"
        val leagueName = "Copper.Crown.United.vs.Lantern.Bay.FC.2026.11.21.1080p.WEB.h264-WREN"
        val undated = "Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-DRIFTWOOD"
        val leg = "Kestrel.Cup.Leg.2.Copper.Crown.United.vs.Lantern.Bay.FC.720p.WEB.h264-ORCHID"
        assertEquals("", wrong(league, listOf(legName to otherGame, leagueName to accepted, undated to otherGame, leg to otherGame), matcher))
        assertEquals("", wrong(secondLeg, listOf(legName to accepted, leagueName to otherGame, undated to otherGame, leg to otherGame), matcher))
    }

    @Test
    fun `a double header's games are told apart by their game number, and a name without one is a possible match for each`() {
        fun numbered(id: Int, start: String, number: Int) = game(
            baseball, id, start, "Kestrel Club 03", "Kestrel Club 07", round = null, season = "2026", title = "Kestrel Club 03 vs Kestrel Club 07 - Game $number",
        )
        // On the 7th too, where the date's day stands right before "Game", as "07" does in the title.
        listOf("2026-11-20", "2026-12-07").forEach { day ->
            val doubleHeader = sports(baseball to listOf(numbered(1401, "${day}T15:00", 1), numbered(1402, "${day}T19:00", 2)))
            val matcher = TeamMatch(doubleHeader)
            val date = day.replace('-', '.')
            val gameOne = "Kestrel.Club.03.vs.Kestrel.Club.07.$date.Game.1.1080p.WEB.h264-KESTREL"
            val gameTwo = "Kestrel.Club.03.vs.Kestrel.Club.07.$date.Game2.1080p.WEB.h264-KESTREL"
            val noNumber = "Kestrel.Club.03.vs.Kestrel.Club.07.$date.720p.WEB.h264-WREN"
            val undated = "Kestrel.Club.03.vs.Kestrel.Club.07.Game.1.720p.WEB.h264-WREN"
            val rows = { mine: String, theirs: String ->
                listOf(mine to accepted, theirs to otherGame, noNumber to offered("No game number"), undated to otherGame)
            }
            assertEquals("", wrong(doubleHeader.byId(1401), rows(gameOne, gameTwo), matcher), day)
            assertEquals("", wrong(doubleHeader.byId(1402), rows(gameTwo, gameOne), matcher), day)
        }
    }

    @Test
    fun `a date's numbers name no side, though a listed club is numbered like its day or month`() {
        fun numbered(id: Int, start: String, number: Int) = game(
            baseball, id, start, "Kestrel Club 03", "Kestrel Club 07", round = null, season = "2026", title = "Kestrel Club 03 vs Kestrel Club 07 - Game $number",
        )
        // Kestrel Club 05 is listed too: "Kestrel" stands in every name of these clubs, so a date's 05 beside it would
        // name it, and the double header's own names would read as Kestrel Club 05's game with Kestrel Club 07.
        fun doubleHeader(day: String, before: String) = sports(
            baseball to listOf(
                numbered(1401, "${day}T15:00", 1),
                numbered(1402, "${day}T19:00", 2),
                game(baseball, 1403, "${before}T17:00", "Kestrel Club 07", "Kestrel Club 05", round = null, season = "2026"),
            ),
        )
        listOf("2026-12-05" to "2026-12-03", "2026-05-08" to "2026-05-06").forEach { (day, before) ->
            val list = doubleHeader(day, before)
            val matcher = TeamMatch(list)
            val date = day.replace('-', '.')
            val rows = listOf(
                "Kestrel.Club.03.vs.Kestrel.Club.07.$date.Game.1.1080p.WEB.h264-KESTREL" to accepted,
                "Kestrel.Club.03.vs.Kestrel.Club.07.$date.Game.2.1080p.WEB.h264-KESTREL" to otherGame,
                "Kestrel.Club.03.vs.Kestrel.Club.07.$date.720p.WEB.h264-WREN" to offered("No game number"),
            )
            assertEquals("", wrong(list.byId(1401), rows, matcher), day)
            // A club's own number still names it: Kestrel Club 05's game is that game's, and another game's here.
            val theirs = "Kestrel.Club.07.vs.Kestrel.Club.05.${before.replace('-', '.')}.1080p.WEB.h264-KESTREL"
            assertEquals(accepted, judge(list.byId(1403), theirs, matcher), day)
            assertEquals(otherGame, judge(list.byId(1401), "Kestrel.Club.07.vs.Kestrel.Club.05.1080p.WEB.h264-KESTREL", matcher), day)
        }
    }

    @Test
    fun `a women's, youth or reserve side's game is another game, unless the event is that side's`() {
        val womens = KeptLeague(2, "Kestrel Premier Division", "Soccer")
        val both = sports(
            premier to listOf(game(premier, 1001, "2026-11-21T15:00", "Lantern Bay FC", "Copper Crown United")),
            womens to listOf(game(womens, 1102, "2026-11-22T13:00", "Lantern Bay FC Women", "Copper Crown United Women")),
        )
        val matcher = TeamMatch(both)
        val siblings = listOf(
            "Lantern.Bay.FC.Women.vs.Copper.Crown.United.Women.2026.11.21.1080p.WEB.h264-KESTREL",
            "Lantern.Bay.FC.W.vs.Copper.Crown.United.W.2026.11.21.720p.WEB.h264-WREN",
            "Lantern.Bay.FC.Frauen.vs.Copper.Crown.United.Frauen.2026.11.21.720p.WEB.h264-WREN",
            "Lantern.Bay.FC.Ladies.vs.Copper.Crown.United.Ladies.2026.11.21.720p.WEB.h264-WREN",
            "Lantern.Bay.FC.U21.vs.Copper.Crown.United.U21.2026.11.21.720p.WEB.h264-ORCHID",
            "Lantern.Bay.FC.U-21.vs.Copper.Crown.United.U-21.2026.11.21.720p.WEB.h264-ORCHID",
            "Lantern.Bay.FC.Under.21.vs.Copper.Crown.United.Under.21.2026.11.21.720p.WEB.h264-ORCHID",
            "Lantern.Bay.FC.Youth.vs.Copper.Crown.United.Youth.2026.11.21.720p.WEB.h264-ORCHID",
            "Lantern.Bay.FC.Reserves.vs.Copper.Crown.United.Reserves.2026.11.21.720p.WEB.h264-ORCHID",
            "Lantern Bay FC Women\u02BCs vs Copper Crown United Women\u02BCs 2026 11 21 720p",
        )
        assertEquals("", wrong(both.byId(1001), siblings.map { it to otherGame }, matcher))
        val women = both.byId(1102)
        assertEquals(accepted, judge(women, "Lantern.Bay.FC.Women.vs.Copper.Crown.United.Women.2026.11.22.1080p.WEB.h264-KESTREL", matcher))
        assertEquals(otherGame, judge(women, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.22.1080p.WEB.h264-KESTREL", matcher), "the men's sides")
    }

    @Test
    fun `team codes at a separator with the league's words or the date are a possible match, and nothing more`() {
        val codes = offered("Team codes only")
        val rows = listOf(
            "Premier.League.2026.11.21.LBF.vs.CCU.720p.WEB.h264-WREN" to codes,
            "EPL.2026.11.21.LBF_@_CCU.720p.WEB.h264-WREN" to codes,
            "LBF.vs.CCU.2026.11.21.720p.WEB.h264-WREN" to codes,
            "Premier.League.LBF.vs.CCU.720p.WEB.h264-WREN" to codes,
            "LBF.vs.CCU.720p.WEB.h264-WREN" to otherGame,
            "Premier.League.2026.11.21.Lbf.vs.Ccu.720p.WEB.h264-WREN" to otherGame,
            "Premier.League.2026.11.21.LANTERN.vs.CCU.720p.WEB.h264-WREN" to otherGame,
            "Premier.League.2026.11.21.LBF.CCU.720p.WEB.h264-WREN" to otherGame,
        )
        assertEquals("", wrong(lbf, rows))
    }

    @Test
    fun `one side with the date and the league's words is a possible match, one with another team is another game`() {
        val oneTeam = offered("Only one team named")
        val rows = listOf(
            "Premier.League.2026.11.21.Lantern.Bay.FC.1080p.WEB.h264-KESTREL" to oneTeam,
            "EPL.2026.11.21.Copper.Crown.United.Full.Match.720p.WEB.h264-WREN" to oneTeam,
            "Premier.League.2026.11.21.Lantern.Bay.FC.vs.CCU.720p.WEB.h264-WREN" to oneTeam,
            "Lantern.Bay.FC.2026.11.21.1080p.WEB.h264-KESTREL" to otherGame,
            "Premier.League.Lantern.Bay.FC.1080p.WEB.h264-KESTREL" to otherGame,
            // Another listed team, another listed game's both sides, a team the list doesn't hold.
            "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Saltmere.City.1080p.WEB.h264-KESTREL" to otherGame,
            "Premier.League.2026.11.21.Saltmere.City.vs.Lowmarsh.Rovers.1080p.WEB.h264-KESTREL" to otherGame,
            "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Pellbrook.Town.1080p.WEB.h264-KESTREL" to otherGame,
            "NBA.2026.11.21.Easton.Comets.vs.Lantern.Bay.Herons.1080p.WEB.h264-KESTREL" to otherGame,
        )
        assertEquals("", wrong(lbf, rows))
    }

    @Test
    fun `with no date, a week, matchday or round equal to the game's and the season's year name it, else they offer it`() {
        val rows = listOf(
            "Premier.League.2026.Week.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to accepted,
            "Premier.League.2026-27.Matchday.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to accepted,
            "Premier.League.2027.Round.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to accepted,
            "Premier.League.2026.Week.14.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to otherGame,
            "Premier.League.2019.Week.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to otherGame,
            // No year: both sides with no date, the sides meeting once in the list.
            "Premier.League.Week.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN" to offered("No date in the name"),
            "Lantern.Bay.FC.vs.Copper.Crown.United.2026.1080p.WEB.h264-DRIFTWOOD" to offered("No date in the name"),
        )
        assertEquals("", wrong(lbf, rows))

        val unknownRound = sports(premier to listOf(game(premier, 1001, "2026-11-21T15:00", "Lantern Bay FC", "Copper Crown United", round = "Final")))
        val week = "Premier.League.2026.Week.13.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN"
        assertEquals(offered("A week number, no date"), judge(unknownRound.byId(1001), week, TeamMatch(unknownRound)))

        // Another meeting two days later: a week number can't tell them apart, and neither can a name with no date.
        val twice = sports(
            premier to listOf(game(premier, 1001, "2026-11-21T15:00", "Lantern Bay FC", "Copper Crown United")),
            cup to listOf(game(cup, 1301, "2026-11-23T19:45", "Copper Crown United", "Lantern Bay FC", round = null)),
        )
        assertEquals(otherGame, judge(twice.byId(1001), week, TeamMatch(twice)))
    }

    @Test
    fun `a cricket day is a possible match, as one day of the match`() {
        val cricket = KeptLeague(20, "Kestrel Cup", "Cricket")
        val match = sports(cricket to listOf(game(cricket, 1801, "2026-11-21T10:00", "Saltmere City", "Lowmarsh Rovers", round = null, season = "2026")))
        val matcher = TeamMatch(match)
        val event = match.byId(1801)
        val day = "Kestrel.Cup.2026.11.21.Saltmere.City.vs.Lowmarsh.Rovers.Day.2.1080p.WEB.h264-KESTREL"
        assertEquals(offered("One day of the match"), judge(event, day, matcher))
        assertEquals(accepted, judge(event, "Kestrel.Cup.2026.11.21.Saltmere.City.vs.Lowmarsh.Rovers.1080p.WEB.h264-KESTREL", matcher))
        val soccer = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Day.2.1080p.WEB.h264-KESTREL"
        assertEquals(accepted, judge(lbf, soccer), "a day of another sport's game is the game")
    }

    @Test
    fun `halves, a whip-around show, a film named like a side, an early post and a small full match`() {
        val rows = listOf(
            "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.1st.Half.1080p.WEB.h264-WREN" to Verdict.Reject(RejectKind.Partial),
            "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Second.Half.1080p.WEB.h264-WREN" to Verdict.Reject(RejectKind.Partial),
            "Premier.League.2026.11.21.Whip.Around.720p.WEB.h264-WREN" to otherGame,
            "Premier.League.2026.Week.13.Whip.Around.720p.WEB.h264-WREN" to Verdict.Reject(RejectKind.Packs),
            "Copper.Crown.2019.1080p.BluRay.x264-GOOD" to otherGame,
        )
        assertEquals("", wrong(lbf, rows))

        val name = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Full.Match.720p.WEB.h264-WREN"
        assertEquals(Verdict.Reject(RejectKind.Older), judge(lbf, name, posted = lbf.start.minusSeconds(2 * 86_400)))
        assertEquals(accepted, judge(lbf, name, posted = lbf.start.plusSeconds(3 * 3_600)))
        assertEquals(offered("Small for a whole game"), judge(lbf, name, size = 300_000_000))
        assertEquals(accepted, judge(lbf, name, size = 3_400_000_000))
    }

    @Test
    fun `a team game's matcher is TeamMatch`() {
        assertIs<TeamMatch>(Matchers(list).of(lbf))
        assertIs<TeamMatch>(Matchers(list).of(herons))
    }
}

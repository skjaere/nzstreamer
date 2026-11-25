package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.NBA
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.WireEvent
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * L647's shared rules, which run before every family's own and whatever it would say: a score or a result word, a
 * non-event word, a part of a game or a pack, a release posted over a day early, and a date that isn't the event's leave a
 * name out, each counted by its kind; the size floor makes an accepted release a possible match at most. The families
 * here accept or offer everything they are asked, so what these rules leave out is theirs alone.
 */
class SharedRulesTest {
    private val accepting = TestFamily()
    private val offering = TestFamily(verdict = { Verdict.Offer("Picked by league and date") })

    private val list = sports(
        PREMIER to listOf(on(PREMIER, 1001, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
        NBA to listOf(on(NBA, 1502, at("2026-11-22T01:30"), "Saltmere Gulls vs Marrowby Lynx")),
        FORMULA_1 to listOf(on(FORMULA_1, 1605, at("2026-11-22T14:00"), "Grand Prix of Kestrel", round = "20")),
        UFC to listOf(ufc999()),
        SKIING to listOf(on(SKIING, 2201, at("2026-11-21T10:30"), "Kestrel · Men's Downhill")),
    )
    private val game = list.byId(1001)
    private val nba = list.byId(1502)
    private val race = list.byId(1605)
    private val card = list.byId(31)
    private val downhill = list.byId(2201)

    private fun judge(
        event: SportsEvent,
        name: String,
        family: FamilyMatcher = accepting,
        part: Part = event.headline,
        posted: Instant? = null,
        size: Long? = null,
    ): Verdict = SharedRules.judge(family, event, part, name, posted, size)

    private fun left(kind: RejectKind) = Verdict.Reject(kind)

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    @Test
    fun `a score or a result word leaves a name out in every family, Generic included, before the family is asked`() {
        val rows = listOf(
            Triple(game, "Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.1080p.WEB.h264-KESTREL", RejectKind.Score),
            Triple(game, "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.2.0.1080p.WEB.h264-KESTREL", RejectKind.Score),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2.0.1080p.WEB.h264-WREN", RejectKind.Score),
            Triple(game, "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Result.720p.WEB.h264-ORCHID", RejectKind.Result),
            Triple(nba, "NBA.2026.11.21.Saltmere.Gulls.vs.Marrowby.Lynx.1080p.WEB.h264-WREN", null),
            Triple(nba, "NBA.2026.11.21.Saltmere.Gulls.Beat.Marrowby.Lynx.1080p.WEB.h264-WREN", RejectKind.Result),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Results.1080p.WEB.h264-ORCHID", RejectKind.Result),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Winner.1080p.WEB.h264-ORCHID", RejectKind.Result),
            Triple(card, "UFC.999.Quessel.vs.Halde.Results.720p.WEB.h264-WREN", RejectKind.Result),
            Triple(card, "UFC.999.Quessel.KO.Halde.1080p.WEB.h264-KESTREL", RejectKind.Result),
            Triple(card, "UFC.999.Quessel.vs.Halde.30-27.1080p.WEB.h264-KESTREL", RejectKind.Score),
            Triple(downhill, "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Results.720p.WEB.h264-ORCHID", RejectKind.Result),
            Triple(downhill, "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Men.Victory.720p.WEB.h264-ORCHID", RejectKind.Result),
        )
        val wrong = rows.flatMap { (event, name, kind) ->
            listOf(accepting, offering).mapNotNull { family ->
                val verdict = judge(event, name, family)
                val expected = kind?.let(::left) ?: if (family === accepting) Verdict.Accept() else Verdict.Offer("Picked by league and date")
                "$name for ${event.family}: $verdict".takeIf { verdict != expected }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        val passed = rows.filter { it.third == null }.map { it.second }
        assertEquals(passed + passed, accepting.judged + offering.judged, "the families are asked only of the names the shared rules passed")
    }

    @Test
    fun `a score joined by a hyphen, colon or dash is one wherever it stands, between a date and the tail too`() {
        val names = listOf(
            "Lantern.Bay.FC.vs.Copper.Crown.United.21.11.2026.3-1.720p.WEB.h264-WREN",
            "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.3-1.1080p.WEB.h264-KESTREL",
            "Lantern Bay FC vs Copper Crown United 21.11.2026 (3-1) 720p",
            "Lantern Bay FC vs Copper Crown United 2026 11 21 3:1 720p",
        )
        // The same game on the day "3:1" would make after a year: still a score, never that date.
        val march = sports(PREMIER to listOf(on(PREMIER, 1001, at("2027-03-01T15:00"), "Lantern Bay FC vs Copper Crown United"))).byId(1001)
        val rows = names.map { game to it } +
            listOf("Lantern Bay FC vs Copper Crown United 2027 3:1 720p", "Lantern Bay FC vs Copper Crown United 2027 3 - 1 720p").map { march to it }
        val wrong = rows.flatMap { (event, name) ->
            listOf(accepting, offering).mapNotNull { family -> "$name: ${judge(event, name, family)}".takeIf { judge(event, name, family) != left(RejectKind.Score) } }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a league's own crossed numbers are no score`() {
        val tour = KeptLeague(15, "Kestrel 3x3 Tour", "Basketball")
        val game = sports(tour to listOf(on(tour, 1601, at("2026-11-21T15:00"), "Lantern Bay Herons vs Saltmere Gulls"))).byId(1601)
        assertEquals(Verdict.Accept(), judge(game, "Kestrel.3x3.Tour.2026.11.21.Lantern.Bay.Herons.vs.Saltmere.Gulls.1080p.WEB.h264-WREN"))
        assertEquals(left(RejectKind.Score), judge(game, "Kestrel.3x3.Tour.2026.11.21.Lantern.Bay.Herons.21x18.Saltmere.Gulls.1080p.WEB.h264-WREN"))
    }

    @Test
    fun `a result word of the event's own title is no result`() {
        val named = sports(VELMORA to listOf(on(VELMORA, 2006, at("2026-11-14T23:00"), "Velmora Iron Crown: Victory Night"))).byId(2006)
        val name = "Velmora.Iron.Crown.Victory.Night.2026.11.14.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Accept(), judge(named, name))
        assertEquals(left(RejectKind.Result), judge(game, "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Victory.1080p.WEB.h264-KESTREL"))
    }

    @Test
    fun `highlights, previews and shows, and a race's own shows are left out by their kind`() {
        val rows = listOf(
            Triple(game, "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Highlights.720p.WEB.h264-WREN", "highlights"),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Extended.Highlights.1080p.WEB.h264-ORCHID", "highlights"),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Recap.720p.WEB.h264-WREN", "highlights"),
            Triple(nba, "NBA.2026.11.21.Saltmere.Gulls.vs.Marrowby.Lynx.Condensed.720p.WEB.h264-WREN", "highlights"),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Preview.720p.WEB.h264-WREN", "previews and shows"),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Post-Match.Reaction.720p.WEB.h264-WREN", "previews and shows"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Pre-Race.Show.1080p.WEB.h264-WREN", "a pre-race show"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Post-Race.1080p.WEB.h264-WREN", "a pre-race show"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Highlights.720p.WEB.h264-ORCHID", "highlights"),
            Triple(card, "UFC.999.Press.Conference.720p.WEB.h264-WREN", "previews and shows"),
            Triple(card, "UFC.999.Weigh-In.720p.WEB.h264-WREN", "previews and shows"),
            Triple(card, "UFC.999.Behind.The.Scenes.720p.WEB.h264-WREN", "previews and shows"),
        )
        val wrong = rows.mapNotNull { (event, name, words) ->
            val kind = (judge(event, name) as? Verdict.Reject)?.kind
            "$name: $kind".takeIf { kind?.words(Nouns.event(event)) != words }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertTrue(accepting.judged.isEmpty())
    }

    @Test
    fun `the rules are read in order, so a name early with highlights is highlights`() {
        val threeDaysBefore = game.start.minusSeconds(3 * 86_400)
        val highlights = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.Highlights.720p.WEB.h264-WREN"
        assertEquals(left(RejectKind.Highlights), judge(game, highlights, posted = threeDaysBefore))
        val scoreAndHighlights = "Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.Highlights.720p.WEB.h264-WREN"
        assertEquals(left(RejectKind.Score), judge(game, scoreAndHighlights))
        assertEquals(left(RejectKind.Shows), judge(card, "UFC.999.Press.Conference.720p.WEB.h264-WREN", posted = card.start.minusSeconds(72 * 3_600)))
    }

    @Test
    fun `a list's word that is the event's own leaves none of its names out, while another one still does`() {
        val countdown = KeptLeague(16, "Kestrel Countdown Cup", "Baseball")
        val game = sports(countdown to listOf(on(countdown, 1701, at("2026-03-10T18:00"), "Saltmere Gulls vs Marrowby Lynx"))).byId(1701)
        assertEquals(Verdict.Accept(), judge(game, "Kestrel.Countdown.Cup.2026.03.10.Saltmere.Gulls.vs.Marrowby.Lynx.1080p.WEB.h264-WREN"))
        assertEquals(left(RejectKind.Highlights), judge(game, "Kestrel.Countdown.Cup.2026.03.10.Saltmere.Gulls.vs.Marrowby.Lynx.Highlights.720p.WEB.h264-WREN"))
        assertEquals(left(RejectKind.Shows), judge(game, "Kestrel.Countdown.Cup.2026.03.10.Saltmere.Gulls.vs.Marrowby.Lynx.Preview.720p.WEB.h264-WREN"))
    }

    @Test
    fun `a part of a game or a pack is left out`() {
        val rows = listOf(
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.1st.Half.1080p.WEB.h264-WREN", "parts of a game"),
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Second.Half.1080p.WEB.h264-WREN", "parts of a game"),
            Triple(nba, "NBA.2026.11.21.Saltmere.Gulls.vs.Marrowby.Lynx.4th.Quarter.720p.WEB.h264-WREN", "parts of a game"),
            // A recording cut into numbered parts: one of them is no whole game, session or card part.
            Triple(game, "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Part.1.1080p.WEB.h264-KESTREL", "parts of a game"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Part.2.1080p.WEB.h264-WREN", "parts of a session"),
            Triple(card, "UFC.999.Main.Card.Part1.1080p.WEB.h264-KESTREL", "parts of a card"),
            Triple(game, "Premier.League.2026.Week.13.720p.WEB.h264-WREN", "packs"),
            Triple(game, "Premier.League.2026.Complete.Season.1080p.WEB.h264-WREN", "packs"),
            Triple(game, "Premier.League.2026.11.21.Bundle.720p.WEB.h264-ORCHID", "packs"),
        )
        val wrong = rows.mapNotNull { (event, name, words) ->
            val kind = (judge(event, name) as? Verdict.Reject)?.kind
            "$name: $kind".takeIf { kind?.words(Nouns.event(event)) != words }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a release posted more than a day before the start is left out, one without a time passes`() {
        val name = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL"
        assertEquals(left(RejectKind.Older), judge(game, name, posted = at("2026-11-20T14:59")))
        assertEquals("older releases", RejectKind.Older.words("game"))
        listOf(at("2026-11-20T15:00"), at("2026-11-20T23:00"), at("2026-11-21T18:00"), null).forEach { posted ->
            assertEquals(Verdict.Accept(), judge(game, name, posted = posted), "posted $posted")
        }
    }

    /** The US evening game at half past one UTC on the 22nd, its broadcast date as given. */
    private fun evening(broadcast: String?): SportsEvent =
        sports(NBA to listOf(on(NBA, 1502, at("2026-11-22T01:30"), "Saltmere Gulls vs Marrowby Lynx").withBroadcast(broadcast))).byId(1502)

    private fun WireEvent.withBroadcast(date: String?) = copy(broadcastDate = date?.let(LocalDate::parse))

    private fun eveningNamed(date: String) = "NBA.$date.Saltmere.Gulls.vs.Marrowby.Lynx.1080p.WEB.h264-KESTREL"

    @Test
    fun `a broadcast date unlike the UTC date is exact, the name's date one of the two`() {
        val exact = evening("2026-11-21")
        listOf("2026.11.21", "2026.11.22", "20261121", "21.11.2026", "2026-11-22").forEach { date ->
            assertEquals(Verdict.Accept(), judge(exact, eveningNamed(date)), date)
        }
        listOf("2026.11.20", "2026.11.23", "24.11.2026").forEach { date ->
            assertEquals(left(RejectKind.OtherEvents), judge(exact, eveningNamed(date)), date)
        }
        assertEquals("other games", RejectKind.OtherEvents.words(Nouns.event(exact)))
    }

    @Test
    fun `a broadcast date equal to the UTC date, or none, may be Sportarr's fallback, so a day either side passes`() {
        listOf(evening("2026-11-22"), evening(null)).forEach { event ->
            listOf("2026.11.21", "2026.11.22", "2026.11.23").forEach { date ->
                assertEquals(Verdict.Accept(), judge(event, eveningNamed(date)), "$date, broadcast ${event.broadcastDate}")
            }
            listOf("2026.11.20", "2026.11.24").forEach { date ->
                assertEquals(left(RejectKind.OtherEvents), judge(event, eveningNamed(date)), "$date, broadcast ${event.broadcastDate}")
            }
        }
        val name = "Premier.League.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Accept(), judge(game, name), "a name with no date is the family's to judge")
    }

    @Test
    fun `a date of another listed meeting of the same sides is that game's, never this one's`() {
        val title = "Lantern Bay FC vs Copper Crown United"
        val both = sports(
            PREMIER to listOf(on(PREMIER, 1001, at("2026-11-21T15:00"), title), on(PREMIER, 1018, at("2026-11-22T19:45"), title)),
        )
        val family = TestFamily(related = { event -> both.events.filter { it.key != event.key } })
        val league = both.byId(1001)
        val cup = both.byId(1018)
        fun named(date: String) = "Lantern.Bay.FC.vs.Copper.Crown.United.$date.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Accept(), judge(league, named("2026.11.21"), family))
        assertEquals(left(RejectKind.OtherEvents), judge(league, named("2026.11.22"), family))
        assertEquals(left(RejectKind.OtherEvents), judge(cup, named("2026.11.21"), family))
        assertEquals(Verdict.Accept(), judge(cup, named("2026.11.22"), family))
        assertEquals(Verdict.Accept(), judge(league, named("2026.11.22")), "with no other meeting listed, the 22nd is a day after")
        // Every date a name holds must be the game's: a release dated both meetings' days is neither's.
        val bothDays = named("2026.11.21.2026.11.22")
        assertEquals(left(RejectKind.OtherEvents), judge(league, bothDays, family))
        assertEquals(left(RejectKind.OtherEvents), judge(cup, bothDays, family))
        assertEquals(left(RejectKind.OtherEvents), judge(league, named("2026.11.21.2026.11.26"), family), "a second date nobody's")
    }

    @Test
    fun `two evening meetings of the same sides a day apart are each told by its own local date, fallback or exact`() {
        val baseball = KeptLeague(10, "Kestrel Baseball League", "Baseball")
        val title = "Kestrel Club 07 vs Kestrel Club 05"
        // Past two in the morning UTC: the evening before in the Americas, which names write by its local date.
        listOf(null to null, "2026-06-12" to "2026-06-13").forEach { (first, second) ->
            val series = sports(
                baseball to listOf(
                    on(baseball, 1405, at("2026-06-13T02:10"), title).withBroadcast(first),
                    on(baseball, 1406, at("2026-06-14T02:10"), title).withBroadcast(second),
                ),
            )
            val family = TestFamily(related = { event -> series.events.filter { it.key != event.key } })
            fun named(date: String) = "Kestrel.Club.07.vs.Kestrel.Club.05.$date.1080p.WEB.h264-KESTREL"
            val rows = listOf(
                Triple("2026.06.12", Verdict.Accept(), left(RejectKind.OtherEvents)),
                Triple("2026.06.13", left(RejectKind.OtherEvents), Verdict.Accept()),
                Triple("2026.06.14", left(RejectKind.OtherEvents), Verdict.Accept()),
            )
            val wrong = rows.flatMap { (date, forFirst, forSecond) ->
                listOfNotNull(
                    "$date for the first: ${judge(series.byId(1405), named(date), family)}".takeIf { judge(series.byId(1405), named(date), family) != forFirst },
                    "$date for the second: ${judge(series.byId(1406), named(date), family)}".takeIf { judge(series.byId(1406), named(date), family) != forSecond },
                )
            }
            assertTrue(wrong.isEmpty(), "broadcast $first and $second:\n" + wrong.joinToString("\n"))
        }
    }

    @Test
    fun `a start before four in the morning UTC is named by the evening before, one from four on by its UTC date`() {
        fun localDay(start: String, broadcast: String? = null) =
            SharedRules.localDay(sports(NBA to listOf(on(NBA, 1502, at(start), "Saltmere Gulls vs Marrowby Lynx").withBroadcast(broadcast))).byId(1502))
        assertEquals(LocalDate.parse("2026-06-12"), localDay("2026-06-13T03:59"), "a late evening on the west coast")
        assertEquals(LocalDate.parse("2026-06-13"), localDay("2026-06-13T04:00"))
        assertEquals(LocalDate.parse("2026-06-13"), localDay("2026-06-13T05:00", broadcast = "2026-06-13"), "a fallback broadcast date")
        assertEquals(LocalDate.parse("2026-06-12"), localDay("2026-06-13T05:00", broadcast = "2026-06-12"), "an exact broadcast date")
        assertEquals(LocalDate.parse("2026-06-12"), localDay("2026-06-13T03:00", broadcast = "2026-06-13"), "a fallback broadcast date")
    }

    @Test
    fun `a double header's other game on the same day doesn't make the day another game's`() {
        val first = "Kestrel Club 03 vs Kestrel Club 07 - Game 1"
        val baseball = KeptLeague(10, "Kestrel Baseball League", "Baseball")
        val doubleHeader = sports(
            baseball to listOf(
                on(baseball, 1401, at("2026-11-20T15:00"), first),
                on(baseball, 1402, at("2026-11-20T19:00"), "Kestrel Club 03 vs Kestrel Club 07 - Game 2"),
            ),
        )
        val family = TestFamily(related = { event -> doubleHeader.events.filter { it.key != event.key } })
        val name = "Kestrel.Club.03.vs.Kestrel.Club.07.2026.11.20.Game.1.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Accept(), judge(doubleHeader.byId(1401), name, family))
    }

    @Test
    fun `a weekly show's name dated another week is another show's`() {
        val brawls = sports(
            VELMORA to listOf("2026-11-10T01:00", "2026-11-17T01:00", "2026-11-24T01:00").mapIndexed { week, start ->
                on(VELMORA, 2001 + week, at(start), "Velmora Monday Brawl")
            },
        )
        val family = TestFamily(related = { event -> brawls.events.filter { it.key != event.key } })
        val week = brawls.byId(2002)
        assertEquals(CardType.WeeklyShow, week.cardType)
        // Named by the local date, the evening before one in the morning UTC.
        val name = "Velmora.Monday.Brawl.2026.11.16.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Accept(), judge(week, name, family))
        listOf(brawls.byId(2001), brawls.byId(2003)).forEach { other ->
            val verdict = judge(other, name, family)
            assertEquals(left(RejectKind.OtherEvents), verdict, other.start.toString())
            assertEquals("other shows", RejectKind.OtherEvents.words(Nouns.event(other)))
        }
    }

    @Test
    fun `an ambiguous day-first date matches only when one reading does and neither is another game's`() {
        val title = "Kestrel Vale vs Duskwater FC"
        val tenth = on(PREMIER, 1101, at("2026-11-10T15:00"), title)
        val october = on(PREMIER, 1102, at("2026-10-11T15:00"), title)
        val name = "Kestrel.Vale.vs.Duskwater.FC.10.11.2026.720p.WEB.h264-WREN"
        val alone = sports(PREMIER to listOf(tenth)).byId(1101)
        assertEquals(Verdict.Accept(), judge(alone, name), "10 November matches; 11 October is nobody's")

        val both = sports(PREMIER to listOf(tenth, october))
        val family = TestFamily(related = { event -> both.events.filter { it.key != event.key } })
        assertEquals(left(RejectKind.OtherEvents), judge(both.byId(1101), name, family), "11 October is the other meeting's")
        assertEquals(left(RejectKind.OtherEvents), judge(both.byId(1102), name, family), "10 November is the other meeting's")

        val later = sports(PREMIER to listOf(on(PREMIER, 1103, at("2026-11-15T15:00"), title))).byId(1103)
        assertEquals(left(RejectKind.OtherEvents), judge(later, name), "neither reading matches")
    }

    @Test
    fun `a score at a name's end is never read as only a date, so a game on a day its numbers make never takes it`() {
        val name = "Kestrel.Premier.Division.2027.Lantern.Bay.FC.vs.Copper.Crown.United.3.1.720p.WEB.h264-WREN"
        // 3 and 1 make the 3rd of January or the 1st of March; Sportarr's fallback date lets a day either side pass.
        listOf("2027-01-03", "2027-03-01", "2027-02-28", "2027-03-02").forEach { day ->
            val game = sports(PREMIER to listOf(on(PREMIER, 1001, at("${day}T15:00"), "Lantern Bay FC vs Copper Crown United"))).byId(1001)
            listOf(accepting, offering).forEach { family -> assertEquals(left(RejectKind.Score), judge(game, name, family), "$day, $family") }
        }
        // A day above 12 reads one way, as the day and month it is.
        val late = sports(PREMIER to listOf(on(PREMIER, 1001, at("2027-11-22T15:00"), "Lantern Bay FC vs Copper Crown United"))).byId(1001)
        assertEquals(Verdict.Accept(), judge(late, "Kestrel.Premier.Division.2027.Lantern.Bay.FC.vs.Copper.Crown.United.22.11.720p.WEB.h264-WREN"))
    }

    @Test
    fun `below 1 Mbit a second over the part's runtime an accepted release is a possible match, half that for standard definition`() {
        // 110 minutes of a soccer game: 825,000,000 bytes at 1 Mbit/s.
        val name = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL"
        assertEquals(110, game.headline.runtimeMinutes)
        assertEquals(Verdict.Offer("Small for a whole game"), judge(game, name, size = 824_999_999))
        assertEquals(Verdict.Accept(), judge(game, name, size = 825_000_000))
        listOf("480p", "576p").forEach { resolution ->
            val small = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.$resolution.WEB.h264-KESTREL"
            assertEquals(Verdict.Offer("Small for a whole game"), judge(game, small, size = 412_499_999), resolution)
            assertEquals(Verdict.Accept(), judge(game, small, size = 412_500_000), resolution)
        }
        val sd = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.SDTV.x264-KESTREL"
        assertEquals(Verdict.Accept(), judge(game, sd, size = 412_500_000))
        val bareSd = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.SD.x264-WREN"
        assertEquals(Verdict.Accept(), judge(game, bareSd, size = 600_000_000), "SD right before the tail")
        assertEquals(Verdict.Offer("Small for a whole game"), judge(game, bareSd, size = 412_499_999))
    }

    @Test
    fun `the floor is the part's, keeps a family's own possible match and left out, and is none with no runtime or size`() {
        val prelims = card.parts.single { it.id == "prelims" }
        val mainCard = card.parts.single { it.id == "main-card" }
        val name = "UFC.999.Prelims.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Offer("Small for a whole prelims"), judge(card, name, part = prelims, size = 899_999_999))
        assertEquals(Verdict.Accept(), judge(card, name, part = prelims, size = 900_000_000))
        assertEquals(Verdict.Offer("Small for a whole main card"), judge(card, "UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL", part = mainCard, size = 1))

        val gameName = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL"
        assertEquals(Verdict.Offer("Picked by league and date"), judge(game, gameName, offering, size = 1))
        val rejecting = TestFamily(verdict = { Verdict.Reject(RejectKind.OtherEvents) })
        assertEquals(left(RejectKind.OtherEvents), judge(game, gameName, rejecting, size = 1))
        assertEquals(Verdict.Accept(), judge(game, gameName, size = 0), "an indexer that gives no size")
        assertEquals(Verdict.Accept(), judge(game, gameName, size = null))

        assertEquals(null, downhill.headline.runtimeMinutes)
        val downhillName = "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Men.1080p.WEB.h264-WREN"
        assertEquals(Verdict.Accept(), judge(downhill, downhillName, size = 1))
        assertEquals(Verdict.Offer("Picked by league and date"), judge(downhill, downhillName, offering, size = 1))
    }

    @Test
    fun `a possible match's reason keeps to the host's rule as it is made`() {
        assertEquals("Small for a whole early prelims", Verdict.Offer("Small for a whole early prelims").reason)
        assertFailsWith<IllegalArgumentException> { Verdict.Offer("") }
        assertFailsWith<IllegalArgumentException> { Verdict.Offer("No date in the name, and no league either, nor any team at all here") }
    }
}

package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.PartPicker
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SharedRules
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.ENDURANCE
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.League
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.kestrelWeekend
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L649's motorsport sessions, each its own event in Sportarr: a name is a session's when it holds a form of the series and
 * no other followed series', the season's year (as a word, or in 2026x07 or S2026E34), the place or the round's number,
 * and the session's words, practice N to N; a name with no session word is the race once it has started. What names it
 * less surely is a possible match with its reason; another round, session, race number or place leaves a name out as
 * another session's. Every name goes through the shared rules first, as the runner judges it.
 */
class MotorsportTest {
    private val f1 = KeptLeague(7, "Formula 1", "Motorsport", alternateName = "F1")
    private val motoGp = KeptLeague(8, "MotoGP", "Motorsport")

    /** A sprint weekend, round 19: practice and sprint qualifying on Friday, the sprint and qualifying on Saturday, the race on Sunday. */
    private val vellmar = listOf(
        on(f1, 1611, at("2026-11-13T11:30"), "Grand Prix of Vellmar - Practice 1", round = "Round 19"),
        on(f1, 1612, at("2026-11-13T15:30"), "Grand Prix of Vellmar - Sprint Qualifying", round = "Round 19"),
        on(f1, 1613, at("2026-11-14T11:00"), "Grand Prix of Vellmar - Sprint", round = "Round 19"),
        on(f1, 1614, at("2026-11-14T15:00"), "Grand Prix of Vellmar - Qualifying", round = "Round 19"),
        on(f1, 1615, at("2026-11-15T14:00"), "Grand Prix of Vellmar - Race", round = "Round 19"),
    )
    private val list = sports(
        f1 to kestrelWeekend(f1, 1601) + vellmar,
        motoGp to listOf(on(motoGp, 1704, at("2026-11-15T13:00"), "Grand Prix of Oskelund - Race", round = "19")),
    )
    private val motorsport = Motorsport(list)

    /** The Grand Prix of Kestrel's sessions, round 20: Practice 1 to 3, Qualifying, the race on Sunday at three in the afternoon UTC. */
    private val kestrel = (1601..1605).map { list.byId(it) }
    private val race = list.byId(1605)
    private val qualifying = list.byId(1604)

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    private fun judge(event: SportsEvent, name: String, matcher: Motorsport = motorsport, posted: Instant? = null): Verdict =
        SharedRules.judge(matcher, event, event.headline, name, posted, null)

    private val accepted = Verdict.Accept()
    private val otherSession = Verdict.Reject(RejectKind.OtherEvents)

    private fun offered(reason: String) = Verdict.Offer(reason)

    /** Each row's name judged for [event]; the names whose verdict isn't the row's, with what they got. */
    private fun wrong(event: SportsEvent, rows: List<Pair<String, Verdict>>, matcher: Motorsport = motorsport): String =
        rows.mapNotNull { (name, expected) -> judge(event, name, matcher).takeIf { it != expected }?.let { "$name: $it, not $expected" } }
            .joinToString("\n")

    /** The titles of [events] that accept [name]. */
    private fun accepting(events: List<SportsEvent>, name: String, matcher: Motorsport = motorsport): List<String> =
        events.filter { judge(it, name, matcher) is Verdict.Accept }.map { it.title }

    @Test
    fun `a series' forms are its name, its twin, each of its other names with its twin, then their initials`() {
        assertEquals(listOf("Formula 1", "Formula1", "F1", "F 1"), Motorsport.forms(League(7, "Formula 1", "Motorsport", "F1")))
        assertEquals(
            listOf("Formula 1", "Formula1", "F1", "F 1", "Formula One", "Formel 1", "Formel1", "FO"),
            Motorsport.forms(League(7, "Formula 1", "Motorsport", "F1, Formula One|Formel 1")),
        )
        assertEquals(listOf("Formula1", "Formula 1", "F1"), Motorsport.forms(League(7, "Formula1", "Motorsport", null)))
        assertEquals(listOf("MotoGP"), Motorsport.forms(League(8, "MotoGP", "Motorsport", "")))
        assertEquals(listOf("Kestrel Endurance Series", "KES"), Motorsport.forms(League(9, "Kestrel Endurance Series", "Motorsport", null)))
    }

    @Test
    fun `a session is searched by the first two forms with the place, then by the first form and the round's number`() {
        val queries = motorsport.queries(race, race.headline)
        assertEquals(listOf("Formula 1 Kestrel", "Formula1 Kestrel"), queries.first)
        assertEquals("Formula 1 20", queries.then)
        assertEquals(listOf("Formula 1 Vellmar", "Formula1 Vellmar"), motorsport.queries(list.byId(1613), list.byId(1613).headline).first)
        assertEquals("Formula 1 19", motorsport.queries(list.byId(1613), list.byId(1613).headline).then)
        val moto = list.byId(1704)
        assertEquals(listOf("MotoGP Oskelund"), motorsport.queries(moto, moto.headline).first)
        assertEquals("MotoGP 19", motorsport.queries(moto, moto.headline).then)

        // The later forms are read in a name, never searched for.
        val many = KeptLeague(7, "Formula 1", "Motorsport", alternateName = "F1, Formula One|Formel 1")
        val manyForms = sports(many to kestrelWeekend(many, 1601))
        assertEquals(listOf("Formula 1 Kestrel", "Formula1 Kestrel"), Motorsport(manyForms).queries(manyForms.byId(1605), race.headline).first)
    }

    @Test
    fun `the place is the weekend's title less Grand Prix, GP, E-Prix, Rally, of and the`() {
        val titles = listOf(
            "Kestrel Grand Prix - Qualifying" to "MotoGP Kestrel",
            "Vellmar GP - Race" to "MotoGP Vellmar",
            "The Tarnholm E-Prix" to "MotoGP Tarnholm",
            "Rally of Tarnby - Shakedown" to "MotoGP Tarnby",
            "Grand Prix of the Oskelund - Sprint" to "MotoGP Oskelund",
        )
        val events = sports(motoGp to titles.mapIndexed { i, (title, _) -> on(motoGp, 1710 + i, at("2026-11-0${i + 1}T13:00"), title) })
        val wrong = titles.mapIndexedNotNull { i, (title, query) ->
            val event = events.byId(1710 + i)
            val first = Motorsport(events).queries(event, event.headline).first
            "$title: $first".takeIf { first != listOf(query) }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertNull(Motorsport(events).queries(events.byId(1710), events.byId(1710).headline).then, "a round with no number: no stage 2")

        // The series' own words name no place: "Kestrel" in "Kestrel Endurance Series".
        val series = sports(ENDURANCE to listOf(on(ENDURANCE, 1803, at("2026-11-21T12:00"), "Grand Prix of Kestrel - Race", round = "8")))
        val event = series.byId(1803)
        val matcher = Motorsport(series)
        assertEquals(offered("No place or round in the name"), judge(event, "Kestrel.Endurance.Series.2026.Race.1080p.WEB.h264-KESTREL", matcher))
        assertEquals(accepted, judge(event, "Kestrel.Endurance.Series.2026.Kestrel.Race.1080p.WEB.h264-KESTREL", matcher))
    }

    @Test
    fun `the catalogue's race has three names accepted, two possible matches and five left out`() {
        val matcher = Motorsport(Catalogue.sports)
        val rows = Catalogue.verdicts(Family.Motorsport)
        val wrong = rows.mapNotNull { (release, verdict) -> Catalogue.disagreement(matcher, release, verdict) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        val race = rows.filter { (_, verdict) -> verdict.eventId == 1605 }
        fun ids(verdict: String) = race.filter { it.second.verdict.name == verdict }.map { it.first.id }.toSet()
        assertEquals(setOf("kestrel-race-kestrel", "kestrel-race-f1", "kestrel-race-orchid"), ids("Accept"))
        assertEquals(setOf("kestrel-race-running-number", "kestrel-race-german"), ids("Offer"))
        assertEquals(5, ids("Reject").size, "two other sessions, another round, a pre-race show and highlights")
        // The name led by F1 and the running number's are judged here, though no query of the race reaches them.
        assertEquals(setOf("kestrel-race-f1", "kestrel-race-running-number"), race.filterNot { it.second.reached }.map { it.first.id }.toSet())
    }

    @Test
    fun `a round's number is read whole, so Round 20 is never 2 or 22`() {
        val rows = listOf(
            "Formula.1.2026.Round.2.Kestrel.Race.1080p.WEB.h264-WREN" to otherSession,
            "Formula1.2026.Round22.Kestrel.Grand.Prix.Race.1080p.WEB.h264-WREN" to otherSession,
            "Formula1.2026.Round200.Kestrel.Grand.Prix.Race.1080p.WEB.h264-WREN" to otherSession,
            "Formula1.2026.Round.20.Race.1080p.WEB.h264-KESTREL" to accepted,
            "Formula1.2026.R20.Race.1080p.WEB.h264-KESTREL" to accepted,
            "F1.2026.Rd.20.Race.720p.WEB.h264-ORCHID" to accepted,
        )
        assertEquals("", wrong(race, rows))
    }

    @Test
    fun `the season's year is needed, as a word, in a running number or an episode, and the running number is no round`() {
        val noPlace = offered("No place or round in the name")
        val rows = listOf(
            "Formula.1.2026x20.Kestrel.Race.1080p.WEB.h264-WREN" to accepted,
            "Formula1.2026x07.Kestrel.Race.1080p.WEB.h264-WREN" to accepted,
            "Formula.1.2026x20.Race.1080p.WEB.h264-DRIFTWOOD" to offered("Only a running number, no place"),
            "Formula1.S2026E20.Race.1080p.WEB.h264-DRIFTWOOD" to noPlace,
            "Formula1.2026.Race.1080p.WEB.h264-DRIFTWOOD" to noPlace,
            "Formula1.2026.11.22.Kestrel.Race.1080p.WEB.h264-KESTREL" to accepted,
            "Formula1.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-KESTREL" to otherSession,
            "Formula1.2025.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-KESTREL" to otherSession,
        )
        assertEquals("", wrong(race, rows))
    }

    @Test
    fun `a session word in another language is a possible match for its own session alone`() {
        val german = offered("Session not named in English")
        assertEquals("", wrong(race, listOf("Formula1.2026.GP.Kestrel.Rennen.German.1080p.WEB.h264-WREN" to german)))
        val qualifikation = "Formula1.2026.GP.Kestrel.Qualifikation.German.1080p.WEB.h264-WREN"
        assertEquals(otherSession, judge(race, qualifikation))
        assertEquals(german, judge(qualifying, qualifikation))
        val zeittraining = "Formula1.2026.GP.Kestrel.Zeittraining.German.1080p.WEB.h264-WREN"
        assertEquals(otherSession, judge(race, zeittraining))
        assertEquals(german, judge(qualifying, zeittraining))
        val essais = "Formula1.2026.GP.Kestrel.Essais.Libres.1.French.1080p.WEB.h264-WREN"
        assertEquals(german, judge(kestrel[0], essais))
        assertEquals(otherSession, judge(kestrel[1], essais))
    }

    @Test
    fun `sprint qualifying, the sprint, qualifying and the race are each their own session`() {
        val weekend = (1611..1615).map { list.byId(it) }
        val rows = listOf(
            "Formula1.2026.Round19.Vellmar.Sprint.Qualifying.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Sprint Qualifying",
            "Formula1.2026.Round19.Vellmar.Sprint.Shootout.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Sprint Qualifying",
            "Formula1.2026.Round19.Vellmar.Sprint.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Sprint",
            "Formula1.2026.Round19.Vellmar.Sprint.Race.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Sprint",
            "Formula1.2026.Round19.Vellmar.Qualifying.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Qualifying",
            "Formula1.2026.Round19.Vellmar.Quali.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Qualifying",
            "Formula1.2026.Round19.Vellmar.Race.1080p.WEB.h264-KESTREL" to "Grand Prix of Vellmar - Race",
        )
        val wrong = rows.mapNotNull { (name, title) -> accepting(weekend, name).takeIf { it != listOf(title) }?.let { "$name: $it" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(otherSession, judge(list.byId(1613), "Formula1.2026.Round19.Vellmar.Race.1080p.WEB.h264-KESTREL"))
    }

    @Test
    fun `FP2 is Practice 2, and practice with no number is none of three`() {
        val practices = kestrel.take(3)
        val rows = listOf(
            "Formula1.2026.Round20.Kestrel.FP2.1080p.WEB.h264-KESTREL" to listOf("Grand Prix of Kestrel - Practice 2"),
            "Formula.1.2026.Kestrel.Free.Practice.2.1080p.WEB.h264-WREN" to listOf("Grand Prix of Kestrel - Practice 2"),
            "Formula1.2026.Kestrel.Practice2.720p.WEB.h264-ORCHID" to listOf("Grand Prix of Kestrel - Practice 2"),
            "Formula1.2026.Kestrel.FP3.720p.WEB.h264-ORCHID" to listOf("Grand Prix of Kestrel - Practice 3"),
            "Formula1.2026.Kestrel.Practice.720p.WEB.h264-ORCHID" to emptyList(),
        )
        val wrong = rows.mapNotNull { (name, titles) -> accepting(practices, name).takeIf { it != titles }?.let { "$name: $it" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a pre-race build-up, a post-qualifying analysis and a press conference are left out as shows`() {
        val rows = listOf(
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Pre-Race.Build-Up.1080p.WEB.h264-WREN", "a pre-race show"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Grand.Prix.Post-Race.Analysis.1080p.WEB.h264-WREN", "a pre-race show"),
            Triple(qualifying, "Formula1.2026.Round20.Kestrel.Grand.Prix.Post-Qualifying.Analysis.1080p.WEB.h264-WREN", "a pre-race show"),
            Triple(race, "Formula1.2026.Round20.Kestrel.Press.Conference.720p.WEB.h264-ORCHID", "previews and shows"),
        )
        val wrong = rows.mapNotNull { (event, name, words) ->
            val kind = (judge(event, name) as? Verdict.Reject)?.kind
            "$name: $kind".takeIf { kind?.words(Nouns.event(event)) != words }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a name with no session word is the race, once the race has started`() {
        val name = "Formula1.2026.Round20.Kestrel.Grand.Prix.1080p.WEB.h264-KESTREL"
        assertEquals(otherSession, judge(race, name, posted = race.start.minusSeconds(2 * 3_600)))
        assertEquals(accepted, judge(race, name, posted = race.start.plusSeconds(3 * 3_600)))
        assertEquals(accepted, judge(race, name), "an indexer that gives no time")
        assertEquals(otherSession, judge(qualifying, name, posted = race.start.plusSeconds(3 * 3_600)))
        assertEquals(otherSession, judge(race, "Formula1.2026.1080p.WEB.h264-KESTREL"), "no place, round or session")
    }

    @Test
    fun `another series, this one's form with another number, or another weekend's place is another session's`() {
        val rows = listOf(
            "Formula.2.2026.Round.20.Kestrel.Race.1080p.WEB.h264-WREN",
            "F2.2026.Kestrel.Race.1080p.WEB.h264-WREN",
            "F1.2026.Kestrel.Formula.2.Race.1080p.WEB.h264-WREN",
            "Formula1.MotoGP.2026.Kestrel.Race.1080p.WEB.h264-WREN",
            "Formula1.2026.Vellmar.Grand.Prix.Race.1080p.WEB.h264-WREN",
        )
        assertEquals("", wrong(race, rows.map { it to otherSession }))
        assertEquals(accepted, judge(list.byId(1615), "Formula1.2026.Vellmar.Grand.Prix.Race.1080p.WEB.h264-WREN"))

        // A class run beside the series, under its banner: the series' word with another number glued on.
        val oskelund = list.byId(1704)
        val classes = listOf(
            "MotoGP.2026.Round19.Oskelund.Moto2.Race.1080p.WEB.h264-WREN" to otherSession,
            "MotoGP.2026.Oskelund.Moto3.Race.720p.WEB.h264-WREN" to otherSession,
            "MotoGP.2026.Round19.Oskelund.Race.1080p.WEB.h264-WREN" to accepted,
            "MotoGP.2026.Rd19.Oskelund.Race2.1080p.WEB.h264-WREN" to accepted,
        )
        assertEquals("", wrong(oskelund, classes))

        // A followed series whose name holds this one's form.
        val academy = KeptLeague(10, "F1 Academy", "Motorsport")
        val both = sports(
            f1 to kestrelWeekend(f1, 1601),
            academy to listOf(on(academy, 1901, at("2026-11-22T10:00"), "Grand Prix of Kestrel - Race", round = "7")),
        )
        val matcher = Motorsport(both)
        val academyName = "F1.Academy.2026.Kestrel.Race.1080p.WEB.h264-WREN"
        val formulaName = "F1.2026.Kestrel.GP.Race.1080p.WEB.h264-WREN"
        assertEquals(otherSession, judge(both.byId(1605), academyName, matcher))
        assertEquals(accepted, judge(both.byId(1901), academyName, matcher))
        assertEquals(accepted, judge(both.byId(1605), formulaName, matcher))
        assertEquals(otherSession, judge(both.byId(1901), formulaName, matcher))
    }

    @Test
    fun `two rounds at one place are told apart by their date or their round, never by the place alone`() {
        val formulaE = KeptLeague(11, "Formula E", "Motorsport")
        val double = sports(
            formulaE to listOf(
                on(formulaE, 1631, at("2026-11-21T14:00"), "Tarnholm E-Prix - Race", round = "5"),
                on(formulaE, 1632, at("2026-11-22T14:00"), "Tarnholm E-Prix - Race", round = "6"),
            ),
        )
        val matcher = Motorsport(double)
        val saturday = "Formula.E.2026.11.21.Tarnholm.E-Prix.Race.1080p.WEB.h264-KESTREL"
        val sunday = "Formula.E.2026.11.22.Tarnholm.E-Prix.Race.1080p.WEB.h264-KESTREL"
        val undated = "Formula.E.2026.Tarnholm.E-Prix.Race.1080p.WEB.h264-WREN"
        val roundSix = "Formula.E.2026.Round.6.Tarnholm.E-Prix.Race.1080p.WEB.h264-WREN"
        val noPlace = offered("No place or round in the name")
        assertEquals("", wrong(double.byId(1631), listOf(saturday to accepted, sunday to otherSession, undated to noPlace, roundSix to otherSession), matcher))
        assertEquals("", wrong(double.byId(1632), listOf(saturday to otherSession, sunday to accepted, undated to noPlace, roundSix to accepted), matcher))
        assertEquals(listOf(double.byId(1632)), matcher.related(double.byId(1631)))
        assertEquals(emptyList(), motorsport.related(race), "a place held once")
    }

    @Test
    fun `an endurance race and testing are possible matches, you pick`() {
        val endurance = sports(
            ENDURANCE to listOf(
                on(ENDURANCE, 1801, at("2026-10-23T14:00"), "6 Hours of Oskelund - Qualifying", round = "7"),
                on(ENDURANCE, 1802, at("2026-10-24T11:00"), "6 Hours of Oskelund", round = "7"),
            ),
        )
        val matcher = Motorsport(endurance)
        assertEquals(offered("Endurance race: you pick"), judge(endurance.byId(1802), "Kestrel.Endurance.Series.2026.6.Hours.of.Oskelund.1080p.WEB.h264-KESTREL", matcher))
        val qualifyingName = "Kestrel.Endurance.Series.2026.6.Hours.of.Oskelund.Qualifying.1080p.WEB.h264-KESTREL"
        assertEquals(accepted, judge(endurance.byId(1801), qualifyingName, matcher))

        val testing = sports(f1 to listOf(on(f1, 1600, at("2026-02-25T09:00"), "Kestrel Testing")))
        val tests = Motorsport(testing)
        val event = testing.byId(1600)
        assertEquals(offered("Testing: you pick"), judge(event, "Formula1.2026.Kestrel.Testing.1080p.WEB.h264-KESTREL", tests))
        assertEquals(offered("Testing: you pick"), judge(event, "Formula1.2026.Kestrel.Test.Day.2.1080p.WEB.h264-KESTREL", tests))
    }

    @Test
    fun `two races in a round are told apart by their number, and a name without one is a possible match for each`() {
        val tarnholm = sports(
            f1 to listOf(
                on(f1, 1621, at("2026-11-28T13:00"), "Grand Prix of Tarnholm - Race 1", round = "21"),
                on(f1, 1622, at("2026-11-29T13:00"), "Grand Prix of Tarnholm - Race 2", round = "21"),
            ),
        )
        val matcher = Motorsport(tarnholm)
        val noNumber = offered("No race number in the name")
        val first = "Formula1.2026.Round21.Tarnholm.Race.1.1080p.WEB.h264-KESTREL"
        val second = "Formula1.2026.Round21.Tarnholm.Race2.1080p.WEB.h264-KESTREL"
        val unnumbered = "Formula1.2026.Round21.Tarnholm.Race.1080p.WEB.h264-WREN"
        val unlabelled = "Formula1.2026.Round21.Tarnholm.Grand.Prix.1080p.WEB.h264-WREN"
        val rows = { mine: String, theirs: String -> listOf(mine to accepted, theirs to otherSession, unnumbered to noNumber, unlabelled to noNumber) }
        assertEquals("", wrong(tarnholm.byId(1621), rows(first, second), matcher))
        assertEquals("", wrong(tarnholm.byId(1622), rows(second, first), matcher))
    }

    @Test
    fun `a weekend's pack is accepted for each of its sessions, but not whole, so the picker chooses the session's file`() {
        val packs = listOf(
            "Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN",
            "Formula1.2026.Round20.Kestrel.Practice.Qualifying.Race.1080p.WEB.h264-WREN",
            "Formula1.2026.Kestrel.All.Sessions.1080p.WEB.h264-WREN",
        )
        val wrong = packs.flatMap { name -> kestrel.mapNotNull { event -> judge(event, name).takeIf { it != Verdict.Accept(whole = false) }?.let { "$name for ${event.title}: $it" } } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(otherSession, judge(race, "Formula1.2026.Round19.Vellmar.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN"))
        assertEquals(otherSession, judge(race, "Formula1.2026.Full.Weekend.1080p.WEB.h264-WREN"))

        val picker = PartPicker.of(motorsport, race, race.headline)
        assertFalse(picker.takesWhole(packs[0]))
        assertTrue(picker.takesWhole("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-KESTREL"))
    }

    @Test
    fun `a motorsport session's matcher is Motorsport`() {
        assertIs<Motorsport>(Matchers(list).of(race))
        assertIs<TeamMatch>(Matchers(Catalogue.sports).of(Catalogue.event(1001)))
    }
}

package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.PartPicker
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SharedRules
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L651's Generic, every sport the plugin has no rules for: nothing is ever accepted, so Play never plays a release by
 * itself. A name with the league's words and the event's date, or with seven in ten of the title's distinctive words and
 * the year or the date, posted once the event started, is a possible match the user picks; every other name is another
 * event's. The shared rules leave a score, a result word and highlights out first, as in every family.
 */
class GenericTest {
    private val cup = KeptLeague(14, "Kestrel Cup", "Soccer")
    private val list = sports(
        SKIING to listOf(
            on(SKIING, 2201, at("2026-11-21T10:30"), "Kestrel · Men's Downhill"),
            on(SKIING, 2202, at("2026-11-22T10:30"), "Kestrel · Women's Giant Slalom"),
        ),
        // A soccer event with no two sides steps down to Generic.
        cup to listOf(on(cup, 1501, at("2026-11-21T15:00"), "Kestrel Cup Final")),
    )

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    private val downhill = list.byId(2201)
    private val slalom = list.byId(2202)
    private val final = list.byId(1501)

    private val other = Verdict.Reject(RejectKind.OtherEvents)
    private val byLeague = Verdict.Offer("Picked by league and date")
    private val byTitle = Verdict.Offer("Picked by the title's words")

    private fun judge(event: SportsEvent, name: String, posted: Instant? = null): Verdict =
        SharedRules.judge(Generic, event, event.headline, name, posted, null)

    /** Each row's name judged for [event]; those whose verdict isn't the row's, with what they got. */
    private fun wrong(event: SportsEvent, rows: List<Pair<String, Verdict>>): String =
        rows.mapNotNull { (name, expected) -> judge(event, name).takeIf { it != expected }?.let { "$name: $it, not $expected" } }.joinToString("\n")

    @Test
    fun `every other sport, and an event stepped down to it, is Generic`() {
        assertEquals(listOf(Family.Generic), list.events.map { it.family }.distinct())
        assertEquals("other events", RejectKind.OtherEvents.words(Nouns.event(downhill)))
    }

    @Test
    fun `nothing is ever accepted, so Play never plays a release by itself`() {
        val names = listOf(
            "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Mens.Downhill.1080p.WEB.h264-KESTREL",
            "Kestrel.Mens.Downhill.2026.11.21.1080p.WEB.h264-WREN",
            "Kestrel.Mens.Downhill.2026.1080p.WEB.h264-ORCHID",
            "FIS.Alpine.Ski.World.Cup.Kestrel.Mens.Downhill.2026.11.21.2160p.WEB.h265-DRIFTWOOD",
        )
        val accepted = names.filter { name -> judge(downhill, name, posted = downhill.start.plusSeconds(3_600)) !is Verdict.Offer }
        assertTrue(accepted.isEmpty(), "not offered: $accepted")
        assertFalse(PartPicker.of(Generic, downhill, downhill.headline).takesWhole(names[0]))
    }

    @Test
    fun `the league's words and the event's date are a possible match`() {
        val rows = listOf(
            "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Men.1080p.WEB.h264-WREN" to byLeague,
            "FIS.Alpine.Ski.World.Cup.21.11.2026.1080p.WEB.h264-WREN" to byLeague,
            "FIS.Alpine.Ski.World.Cup.2026.11.23.Kestrel.Downhill.Men.1080p.WEB.h264-WREN" to other,
            "FIS.Alpine.Ski.World.Cup.Kestrel.Downhill.Men.1080p.WEB.h264-WREN" to other,
            "Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Men.1080p.WEB.h264-WREN" to other,
        )
        assertEquals("", wrong(downhill, rows))
    }

    @Test
    fun `seven in ten of the title's words with the year or the date, posted once it started, are a possible match`() {
        val downhillRows = listOf(
            "Kestrel.Mens.Downhill.2026.1080p.WEB.h264-WREN" to byTitle,
            "Kestrel.Mens.Downhill.2026.11.21.720p.WEB.h264-ORCHID" to byTitle,
            "Kestrel.Mens.Downhill.2027.1080p.WEB.h264-WREN" to other,
            "Kestrel.Mens.Downhill.1080p.WEB.h264-WREN" to other,
        )
        assertEquals("", wrong(downhill, downhillRows))

        val name = "Kestrel.Mens.Downhill.2026.1080p.WEB.h264-WREN"
        assertEquals(other, judge(downhill, name, posted = downhill.start.minus(Duration.ofHours(2))), "posted before the start")
        assertEquals(byTitle, judge(downhill, name, posted = downhill.start.plus(Duration.ofHours(3))))
    }

    @Test
    fun `fewer of the title's words are another event's`() {
        // Three of the four words are seven in ten; two of four, or two of three, are not.
        val slalomRows = listOf(
            "Kestrel.Womens.Giant.Slalom.2026.1080p.WEB.h264-WREN" to byTitle,
            "Kestrel.Giant.Slalom.2026.1080p.WEB.h264-WREN" to byTitle,
            "Giant.Slalom.2026.1080p.WEB.h264-WREN" to other,
        )
        assertEquals("", wrong(slalom, slalomRows))
        assertEquals("", wrong(downhill, listOf("Kestrel.Downhill.2026.1080p.WEB.h264-WREN" to other)))
    }

    @Test
    fun `the shared rules still leave a score, a result word and highlights out`() {
        val rows = listOf(
            Triple(final, "Kestrel.Cup.Final.2026.11.21.Lantern.Bay.3.1.Copper.Crown.1080p.WEB.h264-KESTREL", "a name with a score"),
            Triple(final, "Kestrel.Cup.Final.2026.11.21.Lantern.Bay.Wins.1080p.WEB.h264-KESTREL", "a name with a result"),
            Triple(downhill, "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Results.720p.WEB.h264-ORCHID", "a name with a result"),
            Triple(downhill, "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Highlights.720p.WEB.h264-WREN", "highlights"),
            Triple(downhill, "FIS.Alpine.Ski.World.Cup.2026.11.21.Kestrel.Downhill.Preview.720p.WEB.h264-WREN", "previews and shows"),
        )
        val wrong = rows.mapNotNull { (event, name, kind) ->
            val said = (judge(event, name) as? Verdict.Reject)?.kind?.words(Nouns.event(event))
            "$name: $said".takeIf { said != kind }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        // The same names without the score or the result word are offered.
        assertEquals(byLeague, judge(final, "Kestrel.Cup.Final.2026.11.21.Lantern.Bay.Copper.Crown.1080p.WEB.h264-KESTREL"))
    }

    @Test
    fun `it is searched by the title's words and by the league with the date, then by the title's words and the year`() {
        val queries = Generic.queries(downhill, downhill.headline)
        assertEquals(listOf("Kestrel Mens Downhill", "FIS Alpine Ski World Cup 2026 11 21"), queries.first)
        assertEquals("Kestrel Mens Downhill 2026", queries.then)

        val more = sports(
            SKIING to listOf(
                on(SKIING, 2203, at("2026-11-28T10:30"), "The Kestrel Vale Lantern Bay Copper Crown Relay"),
                // An evening start in the Americas: Sportarr's broadcast date is the day before the UTC date.
                on(SKIING, 2204, at("2027-01-01T01:30"), "Kestrel · Night Sprint").copy(broadcastDate = LocalDate.parse("2026-12-31")),
            ),
        )
        val relay = Generic.queries(more.byId(2203), more.byId(2203).headline)
        assertEquals(listOf("Kestrel Vale Lantern Bay Copper", "FIS Alpine Ski World Cup 2026 11 28"), relay.first, "five words at most, no article")
        assertEquals("Kestrel Vale Lantern Bay Copper Crown Relay 2026", relay.then)
        val sprint = Generic.queries(more.byId(2204), more.byId(2204).headline)
        assertEquals(listOf("Kestrel Night Sprint", "FIS Alpine Ski World Cup 2026 12 31"), sprint.first)
        assertEquals("Kestrel Night Sprint 2026", sprint.then)
    }

    @Test
    fun `the catalogue's Generic rows get the verdicts they say`() {
        val rows = Catalogue.verdicts(Family.Generic)
        val wrong = rows.mapNotNull { (release, verdict) -> Catalogue.disagreement(Generic, release, verdict) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(setOf("downhill", "downhill-results"), rows.map { it.first.id }.toSet())
    }

    @Test
    fun `every family has its matcher now, Generic for every other sport`() {
        assertSame(Generic, Matchers(list).of(downhill))
        val matchers = Matchers(Catalogue.sports)
        val kinds = Catalogue.sports.events.groupBy { it.family }
            .mapValues { (_, events) -> events.map { matchers.of(it).javaClass.simpleName }.distinct() }
        assertEquals(
            mapOf(
                Family.TeamGame to listOf("TeamMatch"),
                Family.Motorsport to listOf("Motorsport"),
                Family.FightCard to listOf("FightCard"),
                Family.Generic to listOf("Generic"),
            ),
            kinds,
        )
    }
}

package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.kestrelWeekend
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import io.skjaere.usenet.plugin.FileInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L647's part picker: of a release's videos, samples aside, the one whose file name carries the part's or the session's
 * words and no pre-show, post-show or non-event word; else the only video; else none, saying which part it missed. Never
 * the largest alone, as a weekend's release holds build-ups and analyses. A release is taken whole only where the judge
 * accepted it as the part itself.
 */
class PartPickerTest {
    private val family = TestFamily(
        verdict = { name ->
            when {
                PartWord.FullEvent in name.parts || name.has("full weekend") -> Verdict.Accept(whole = false)
                name.group == "DRIFTWOOD" -> Verdict.Offer("No date in the name")
                else -> Verdict.Accept()
            }
        },
    )
    private val list = sports(
        PREMIER to listOf(on(PREMIER, 1001, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
        FORMULA_1 to kestrelWeekend(),
        UFC to listOf(ufc999()),
        VELMORA to listOf(on(VELMORA, 43, at("2026-11-28T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show"))),
    )

    private fun Sports.titled(title: String): SportsEvent = events.single { it.title == title }

    private fun picker(event: SportsEvent, partId: String = event.headline.id) = PartPicker.of(family, event, event.parts.single { it.id == partId })

    private fun session(label: String) = list.titled("Grand Prix of Kestrel - $label")

    private fun video(path: String, size: Long = 4_000_000_000) = FileInfo(path, size, isVideo = true)

    private fun other(path: String) = FileInfo(path, 2_000, isVideo = false)

    private val weekend = listOf(
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Practice.1.1080p.WEB.h264-WREN.mkv"),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Practice.2.1080p.WEB.h264-WREN.mkv"),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Practice.3.1080p.WEB.h264-WREN.mkv"),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Qualifying.1080p.WEB.h264-WREN.mkv"),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-WREN.mkv", 6_000_000_000),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Build-Up.1080p.WEB.h264-WREN.mkv", 9_000_000_000),
        other("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN.nfo"),
        video("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN/Sample/formula1.round20.race.sample.mkv", 40_000_000),
    )

    @Test
    fun `each session's file in a weekend's release`() {
        val picked = listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race").associateWith { picker(session(it)).pick(weekend) }
        assertEquals(mapOf("Practice 1" to 0, "Practice 2" to 1, "Practice 3" to 2, "Qualifying" to 3, "Race" to 4), picked)

        val short = listOf("FP1", "FP2", "FP3", "Qualifying", "Race").map { video("Formula1.2026.Round20.Kestrel.$it.1080p.WEB.h264-ORCHID.mkv") }
        assertEquals(1, picker(session("Practice 2")).pick(short), "FP2 is Practice 2")
        assertEquals(3, picker(session("Qualifying")).pick(short))
    }

    @Test
    fun `a build-up and an analysis are passed over, however large`() {
        val files = listOf(
            video("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Build-Up.1080p.WEB.h264-WREN.mkv", 9_000_000_000),
            video("Formula1.2026.Round20.Kestrel.Grand.Prix.Post-Race.Analysis.1080p.WEB.h264-WREN.mkv", 8_000_000_000),
            video("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Analysis.1080p.WEB.h264-WREN.mkv", 7_000_000_000),
            video("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-WREN.mkv", 6_000_000_000),
        )
        assertEquals(3, picker(session("Race")).pick(files))
        assertNull(picker(session("Race")).pick(files.take(3)), "nothing but programmes about the race")
        assertNull(picker(session("Race")).pick(files.take(1)), "a build-up is never the only video")
    }

    @Test
    fun `a card's part by its words, the PPV as the main card, a show's pre-show and main show`() {
        val card = list.titled("UFC 999: Quessel vs. Halde")
        val files = listOf(
            video("UFC.999.Early.Prelims.1080p.WEB.h264-ORCHID.mkv"),
            video("UFC.999.Prelims.1080p.WEB.h264-ORCHID.mkv"),
            video("UFC.999.Main.Card.1080p.WEB.h264-ORCHID.mkv"),
            video("UFC.999.Post.Show.1080p.WEB.h264-ORCHID.mkv"),
        )
        assertEquals(listOf(0, 1, 2), listOf("early-prelims", "prelims", "main-card").map { picker(card, it).pick(files) })
        val ppv = listOf(video("UFC.999.Prelims.1080p.WEB.h264-ORCHID.mkv"), video("UFC.999.PPV.Quessel.vs.Halde.1080p.WEB.h264-ORCHID.mkv"))
        assertEquals(1, picker(card, "main-card").pick(ppv))
        // A part's own word beside PPV says which part it is: the prelims of the pay-per-view, never its main card.
        val ppvPrelims = listOf(video("UFC.999.PPV.Prelims.1080p.WEB.h264-ORCHID.mkv"))
        assertNull(picker(card, "main-card").pick(ppvPrelims))
        assertEquals(0, picker(card, "prelims").pick(ppvPrelims))

        val night = list.titled("Velmora Iron Crown: Night 2")
        val show = listOf(
            video("Velmora.Iron.Crown.Night.2.Pre-Show.1080p.WEB.h264-KESTREL.mkv"),
            video("Velmora.Iron.Crown.Night.2.Main.Show.1080p.WEB.h264-KESTREL.mkv"),
        )
        assertEquals(0, picker(night, "pre-show").pick(show))
        assertEquals(1, picker(night, "main-show").pick(show))
    }

    @Test
    fun `the only video, samples aside, whatever it is named`() {
        val card = list.titled("UFC 999: Quessel vs. Halde")
        val obfuscated = listOf(
            other("x7f3a9c1.nfo"),
            video("x7f3a9c1.mkv", 6_000_000_000),
            video("x7f3a9c1.sample.mkv", 30_000_000),
            video("Sample/x7f3a9c1.mkv", 30_000_000),
        )
        assertEquals(1, picker(card, "main-card").pick(obfuscated))
        val game = list.titled("Lantern Bay FC vs Copper Crown United")
        assertEquals(0, picker(game).pick(listOf(video("Lantern.Bay.FC.vs.Copper.Crown.United.mkv"), other("Lantern.Bay.FC.vs.Copper.Crown.United.nfo"))))
        assertNull(picker(game).pick(listOf(video("Lantern.Bay.FC.1st.Half.mkv"), video("Lantern.Bay.FC.2nd.Half.mkv"))), "two halves are no game")
        assertNull(picker(game).pick(listOf(video("Lantern.Bay.FC.1st.Half.mkv"))), "nor is one")
        assertNull(picker(card, "main-card").pick(listOf(video("UFC.999.Prelims.1080p.WEB.h264-ORCHID.mkv"))), "another part's only video")
        assertNull(picker(card, "main-card").pick(listOf(other("x7f3a9c1.nfo"))))
    }

    @Test
    fun `none when no file is the part's, saying which part it missed`() {
        val card = list.titled("UFC 999: Quessel vs. Halde")
        val prelimsOnly = listOf(video("UFC.999.Early.Prelims.1080p.WEB.h264-KESTREL.mkv"), video("UFC.999.Prelims.1080p.WEB.h264-KESTREL.mkv"))
        val main = picker(card, "main-card")
        assertNull(main.pick(prelimsOnly))
        assertEquals("No file for the Main Card in this release", main.noMatch)
        assertEquals("No file for the Practice 2 in this release", picker(session("Practice 2")).noMatch)
    }

    @Test
    fun `a release is taken whole only where the judge accepted it as the part itself`() {
        val card = list.titled("UFC 999: Quessel vs. Halde")
        val main = picker(card, "main-card")
        assertTrue(main.takesWhole("UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL"))
        assertFalse(main.takesWhole("UFC.999.Full.Event.1080p.WEB.h264-ORCHID"), "a full event holds every part")
        assertFalse(main.takesWhole("UFC.999.Quessel.vs.Halde.Results.720p.WEB.h264-WREN"), "left out")
        assertFalse(main.takesWhole("UFC.999.Quessel.vs.Halde.720p.WEB.h264-DRIFTWOOD"), "only a possible match")
        val race = picker(session("Race"))
        assertTrue(race.takesWhole("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p.WEB.h264-WREN"))
        assertFalse(race.takesWhole("Formula1.2026.Round20.Kestrel.Grand.Prix.Full.Weekend.1080p.WEB.h264-WREN"), "a weekend's release")
    }
}

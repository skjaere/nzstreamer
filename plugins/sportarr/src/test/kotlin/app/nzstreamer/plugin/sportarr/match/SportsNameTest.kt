package app.nzstreamer.plugin.sportarr.match

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * L646: the plugin's own sports name parser reads a release name for what it names, and leaves quality to the host. Each
 * table holds names as indexers write them (invented teams, fighters, races and groups) and what the parser reads of them:
 * the dates in every form and what is never one; the numbers a word leads, whole; the tail and the group; the two sides;
 * the six word lists, the siblings and the result words (a word of the event's own title, league or sides never one);
 * and scores in every form against the numbers that aren't. A long or hostile name reads quickly and never throws.
 */
class SportsNameTest {
    @Test
    fun `dates in every form, and season spans, running numbers and the event's own numbers never one`() {
        table(DATES) { name ->
            listOf(
                name.dates.map { date -> date.readings.joinToString("|") },
                name.years.map { "y$it" },
                name.seasons.map { "s${it.first}/${it.second}" },
                name.indexes.map { index -> (if (index.form == IndexForm.Episode) "e" else "x") + "${index.year}:${index.index}" },
            ).flatten().joinToString(" ")
        }
    }

    @Test
    fun `a date says how it was written and where it stands`() {
        // Where it stands: the places of the words it takes, from its first.
        val rows = listOf(
            "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL" to "YearFirst at 2, 2..4",
            "Lantern.Bay.vs.Copper.Crown.2026.11.21.720p50.WEB.h264-WREN" to "YearFirst at 5, 5..7",
            "EPL.20261121.Lantern.Bay.FC.v.Copper.Crown.United.1080p.HDTV.x264-ORCHID" to "Compact at 1, 1..1",
            "Premier.League.21.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p" to "DayFirst at 2, 2..4",
            "Kestrel.Hockey.League.2026.Easton.Comets.Saltmere.Gulls.22.11.720p.WEB.h264-WREN" to "DayMonth at 8, 8..9",
        )
        rows.forEach { (name, expected) ->
            val date = SportsName.read(name).dates.single()
            assertEquals(expected, "${date.form} at ${date.at}, ${date.places}", name)
        }
        assertTrue(SportsName.read("Premier.League.10.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p").dates.single().ambiguous)
        assertFalse(SportsName.read("Premier.League.21.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p").dates.single().ambiguous)
    }

    @Test
    fun `numbers after their words, glued or not, and a card's number after its organisation`() {
        table(NUMBERS) { name ->
            (name.numbered.map { "${it.word.name.lowercase(Locale.ROOT)} ${it.number}" } + name.cardNumbers.map { "card $it" }).joinToString(", ")
        }
    }

    @Test
    fun `numbers are whole tokens, never inside another`() {
        val rows = listOf(
            Triple("UFC.999.Prelims.1080p.WEB.h264-KESTREL", 999, true),
            Triple("UFC.9990.Prelims.1080p.WEB.h264-KESTREL", 999, false),
            Triple("UFC.9990.Prelims.1080p.WEB.h264-KESTREL", 9990, true),
            Triple("UFC.999.Prelims.1080p.WEB.h264-KESTREL", 99, false),
            Triple("UFC999.Prelims.1080p.WEB.h264-KESTREL", 999, false),
            Triple("Formula.1.2026.Round.20.Kestrel.Race.1080p", 2, false),
            Triple("Formula.1.2026.Round.20.Kestrel.Race.1080p", 20, true),
            Triple("Formula.1.2026.Round.22.Kestrel.Race.1080p", 20, false),
            Triple("Formula.1.2026.Round.2.Kestrel.Race.1080p", 20, false),
        )
        val wrong = rows.filter { (name, number, has) -> SportsName.read(name).hasNumber(number) != has }
        assertTrue(wrong.isEmpty(), "wrong: $wrong")
    }

    @Test
    fun `the identity ends at the first quality word, and the group after the tail's last hyphen is set aside`() {
        table(TAILS) { name ->
            listOf(
                name.identity.joinToString(" ") { it.word }.ifEmpty { "none" },
                name.tail.joinToString(" ") { it.word }.ifEmpty { "none" },
                name.group ?: "none",
            ).joinToString(" | ")
        }
    }

    @Test
    fun `the sides are the words before and after vs, v, @ or at`() {
        table(SIDES) { name ->
            name.sides?.let { sides -> listOf(words(sides.before), sides.separator, words(sides.after)).joinToString(" | ") } ?: "none"
        }
    }

    @Test
    fun `a token keeps its written form beside the folded one, for team codes`() {
        val codes = SportsName.read("Premier.League.2026.11.21.LBF.vs.CCU.720p.WEB.h264-WREN").sides!!
        assertEquals(listOf("LBF"), codes.before.map { it.written })
        assertEquals(listOf("CCU"), codes.after.map { it.written })
        val folded = SportsName.read("Lantern Bay FC vs Cöpper Crówn United 1080p").sides!!
        assertEquals(listOf("Cöpper", "Crówn", "United"), folded.after.map { it.written })
        assertEquals(listOf("copper", "crown", "united"), folded.after.map { it.word })
    }

    @Test
    fun `a phrase is in the identity when its words stand together, as a name folds them`() {
        val name = SportsName.read("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL")
        assertTrue(name.has("Lantern Bay"))
        assertTrue(name.has("copper crown united"))
        assertTrue(name.has("Premier League"))
        assertFalse(name.has("Lantern FC"), "not together")
        assertFalse(name.has("KESTREL"), "the group is not the identity")
        assertFalse(name.has("WEB"), "nor is the tail")
        assertFalse(name.has(""))
        val glued = SportsName.read("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL")
        assertTrue(glued.has("Formula1"))
        assertFalse(glued.has("Formula 1"), "a glued name is one word; the series' forms list its twin")
        assertTrue(glued.has("Grand Prix"))
    }

    @Test
    fun `the six lists, the siblings and the result words, content words read before sessions and parts`() {
        table(WORDS) { name ->
            listOf(
                name.nonEvent.map { "${KINDS.getValue(it.kind)}:${it.word}" },
                name.partial.map { "partial:$it" },
                name.packs.map { "pack:$it" },
                name.parts.map { "part:${it.word}" },
                name.sessions.map { session ->
                    listOfNotNull(session.kind.name.lowercase(Locale.ROOT), session.number, "foreign".takeIf { session.foreign })
                        .joinToString(" ", prefix = "session:")
                },
                listOfNotNull("gp".takeIf { name.grandPrix }),
                name.results.map { "result:$it" },
                name.siblings.sorted().map { "sibling:$it" },
            ).flatten().joinToString(", ")
        }
    }

    @Test
    fun `scores in every form, and the numbers that aren't one`() {
        table(SCORES) { name -> if (name.score) "score" else "none" }
    }

    @Test
    fun `standard definition is read from the tail, for the size floor`() {
        assertTrue(SportsName.read("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.480p.WEB.h264-WREN").standardDefinition)
        assertTrue(SportsName.read("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.576i.HDTV-WREN").standardDefinition)
        assertTrue(SportsName.read("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.WEB.SD.h264-WREN").standardDefinition)
        assertFalse(SportsName.read("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN").standardDefinition)
        assertFalse(SportsName.read("Premier.League.2026.11.21.SD.vs.LBF.720p.WEB.h264-WREN").standardDefinition, "a code before the tail")
    }

    @Test
    fun `a long or hostile name reads without throwing`() {
        val names = hostile() + generated(2_000)
        names.forEach { name ->
            val read = SportsName.read(name)
            assertTrue(read.readable, "read without its guard: ${name.take(80)}")
            assertEquals(name, read.name)
        }
        // The event's words read against hostile names too.
        val context = NameContext(
            title = "9".repeat(40) + " vs 2026", league = ".-.", sides = listOf("", "@"), series = listOf("1 2 3"), organisations = listOf("ufc", ""),
        )
        names.forEach { assertTrue(SportsName.read(it, context).readable, it.take(80)) }
    }

    @Test
    fun `a name of a few hundred characters reads in well under a millisecond`() {
        val names = hostile().filter { it.length in 200..1_000 } + generated(200, minLength = 200)
        repeat(3) { names.forEach { SportsName.read(it) } }
        val runs = 10
        val started = System.nanoTime()
        repeat(runs) { names.forEach { SportsName.read(it) } }
        val micros = (System.nanoTime() - started) / 1_000.0 / (runs * names.size)
        println("SportsName.read: %.1f µs a name over %d names of 200 to 1,000 characters".format(Locale.ROOT, micros, names.size))
        assertTrue(micros < 1_000, "$micros µs a name")
    }

    private class Row(val name: String, val expected: String, val context: NameContext = NameContext.NONE)

    private fun words(tokens: List<Token>): String = tokens.joinToString(" ") { it.word }

    /** Every row of [rows] read through [read], each wrong one listed. */
    private fun table(rows: List<Row>, read: (SportsName) -> String) {
        val wrong = rows.mapNotNull { row ->
            val got = read(SportsName.read(row.name, row.context))
            "  ${row.name}${if (row.context == NameContext.NONE) "" else " (${row.context})"}\n    expected <${row.expected}>, got <$got>"
                .takeIf { got != row.expected }
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} of ${rows.size} rows read wrong:\n" + wrong.joinToString("\n"))
    }

    private companion object {
        val KINDS = mapOf(NonEventKind.Cut to "cut", NonEventKind.RaceShow to "race show", NonEventKind.Show to "show")

        /** One game's names with a word added after the date. */
        const val GAME = "Quarrydown.FC.vs.Ellerholt.United.2026.11.21"
        const val RACE = "Formula1.2026.Round20.Kestrel"
        const val CARD = "UFC.999"
        const val SHOW = "Velmora.Iron.Crown.2026.11.21"

        val UFC = NameContext(organisations = listOf("UFC"))
        val KCL_21 = NameContext(title = "KCL 21: Brisk vs. Loam")
        val DOUBLE_HEADER = NameContext(title = "Kestrel Club 03 vs Kestrel Club 07 - Game 1", sides = listOf("Kestrel Club 03", "Kestrel Club 07"))
        val IN_A_SHOW = NameContext(show = true)

        val DATES = listOf(
            Row("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL", "2026-11-21"),
            Row("Premier League 2026-11-21 Lantern Bay FC vs Copper Crown United 1080p WEB h264-KESTREL", "2026-11-21"),
            Row("Premier League 2026 11 21 Lantern Bay FC vs Copper Crown United 720p", "2026-11-21"),
            Row("Premier_League_2026_11_21_Lantern_Bay_FC_vs_Copper_Crown_United_720p", "2026-11-21"),
            Row("Premier League 2026/11/21 Lantern Bay FC vs Copper Crown United 720p", "2026-11-21"),
            Row("NBA-2026-11-20_EAC@MLX_1080p_WEB_h264-WREN", "2026-11-20"),
            Row("Premier.League.2026.1.5.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2026-01-05"),
            Row("Lantern.Bay.vs.Copper.Crown.2026.11.21.720p50.WEB.h264-WREN", "2026-11-21"),
            Row("Velmora.Monday.Brawl.2026.11.23.1080p.WEB.h264-KESTREL", "2026-11-23"),
            // Compact.
            Row("EPL.20261121.Lantern.Bay.FC.v.Copper.Crown.United.1080p.HDTV.x264-ORCHID", "2026-11-21"),
            Row("EPL.20261321.Lantern.Bay.FC.v.Copper.Crown.United.1080p.HDTV.x264-ORCHID", ""),
            // Day first: a date when the day is above 12; else both readings, the day's and the month's.
            Row("Premier.League.21.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2026-11-21"),
            Row("Premier League 21-11-2026 Lantern Bay FC vs Copper Crown United 720p", "2026-11-21"),
            Row("Premier.League.10.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2026-11-10|2026-10-11"),
            Row("Premier.League.11.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2026-11-11"),
            Row("Premier.League.05.13.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2026-05-13"),
            Row("Premier.League.21.13.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "y2026"),
            // A trailing day and month, the year elsewhere; with no year they are no date.
            Row("Kestrel.Hockey.League.2026.Easton.Comets.Saltmere.Gulls.22.11.720p.WEB.h264-WREN", "2026-11-22 y2026"),
            Row("Kestrel.Hockey.League.2026.Easton.Comets.Saltmere.Gulls.05.11.720p.WEB.h264-WREN", "2026-11-05|2026-05-11 y2026"),
            Row("Lantern.Bay.vs.Copper.Crown.21.11.720p50.WEB.h264-WREN", ""),
            Row("Formula1.2026.Kestrel.Race.21.11.1080p.WEB.h264-WREN", "2026-11-21 y2026"),
            // Season spans, a year and a running number, a year and an episode: years, never dates.
            Row("Premier.League.2026.2027.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "s2026/2027"),
            Row("Kestrel.Premier.Division.2025-26.Week.10.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "s2025/2026"),
            Row("NBA.2026.27.Easton.Comets.vs.Saltmere.Gulls.1080p.WEB.h264-KESTREL", "s2026/2027"),
            Row("Kestrel.Premier.Division.2025-26.21.11.2025.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "2025-11-21 s2025/2026"),
            Row("MotoGP.2026x07.Race.1080p.WEB.h264-KESTREL", "x2026:7"),
            Row("Formula1.2026x20.Race.1080p.WEB.h264-DRIFTWOOD", "x2026:20"),
            Row("Formula1.S2026E34.Kestrel.Race.1080p.WEB.h264-WREN", "e2026:34"),
            // A year as a word.
            Row("Formula.1.2026.Round.20.Kestrel.Race.720p50.WEB.h264-ORCHID", "y2026"),
            Row("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", "y2026"),
            Row("Copper.Crown.2019.1080p.BluRay.x264-GOOD", "y2019"),
            Row("UFC.999.Early.Prelims.1080p.WEB.h264-KESTREL", ""),
            // A number of the event's own title is masked first: a race named for its distance gives no year.
            Row("Kestrel.Endurance.Series.2026.Kestrel.2000.Race.1080p.WEB.h264-KESTREL", "y2026", NameContext(title = "Kestrel 2000")),
            Row("Kestrel.Endurance.Series.2026.Kestrel.2000.Race.1080p.WEB.h264-KESTREL", "y2026 y2000"),
            // Masked beside the word after it alone, a number that closes a year-first date is the date's day: a title's
            // "07 - Game 1" or "21: Brisk" doesn't take it from a name dated the 7th or the 21st.
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.12.07.Game.1.1080p.WEB.h264-KESTREL", "2026-12-07", DOUBLE_HEADER),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.07.05.Game.1.1080p.WEB.h264-KESTREL", "2026-07-05", DOUBLE_HEADER),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.Game.1.2026.12.07.1080p.WEB.h264-KESTREL", "2026-12-07", DOUBLE_HEADER),
            Row("KCL.2026.11.21.Brisk.vs.Loam.1080p.WEB.h264-WREN", "2026-11-21", KCL_21),
            Row("KCL.21.Brisk.vs.Loam.2026.11.21.1080p.WEB.h264-WREN", "2026-11-21", KCL_21),
        )

        val NUMBERS = listOf(
            Row("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", "round 20"),
            Row("Formula.1.2026.Round.20.Kestrel.Race.720p50.WEB.h264-ORCHID", "round 20"),
            Row("Formula1.2026.R20.Kestrel.Race.1080p.WEB.h264-WREN", "round 20"),
            Row("Formula1.2026.R.20.Kestrel.Race.1080p.WEB.h264-WREN", "round 20"),
            Row("Formula1 2026 Rd 20 Kestrel Race 1080p WEB h264-WREN", "round 20"),
            Row("Formula1.2026.Rd20.Kestrel.Race.1080p.WEB.h264-WREN", "round 20"),
            Row("Formula.1.2026.Round.2.Kestrel.Race.1080p.WEB.h264-WREN", "round 2"),
            Row("Formula.1.2026.Round.22.Kestrel.Race.1080p.WEB.h264-WREN", "round 22"),
            Row("Premier.League.2026.Week.10.1080p.WEB.h264-WREN", "week 10"),
            Row("Premier.League.2026.Week10.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-WREN", "week 10"),
            Row("Premier.League.2026.Matchday.12.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN", "matchday 12"),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.11.21.Game.1.1080p.WEB.h264-KESTREL", "game 1"),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.11.21.Game2.720p.WEB.h264-WREN", "game 2"),
            Row("Kestrel.Cup.2026.Lantern.Bay.FC.vs.Copper.Crown.United.Leg.2.1080p.WEB.h264-WREN", "leg 2"),
            Row("Formula1.2026.Round20.Kestrel.Race.2.1080p.WEB.h264-WREN", "round 20, race 2"),
            Row("Kestrel.vs.Saltmere.2026.Day.3.1080p.WEB.h264-WREN", "day 3"),
            Row("Velmora.Monday.Brawl.2026.11.23.Part.1.720p.WEB.h264-WREN", "part 1"),
            Row("Kestrel.Endurance.Series.2026.6.Hours.of.Oskelund.Session.2.1080p.WEB.h264-WREN", "session 2"),
            Row("Velmora.Iron.Crown.Night.2.2026.1080p.WEB.h264-KESTREL", "night 2"),
            Row("UFC.999.Prelims.1080p.WEB.h264-KESTREL", "card 999", UFC),
            Row("UFC999.Main.Card.1080p.WEB.h264-KESTREL", "card 999", UFC),
            Row("UFC.9990.Prelims.1080p.WEB.h264-KESTREL", "card 9990", UFC),
            Row("UFC.999.Prelims.1080p.WEB.h264-KESTREL", ""),
            Row("UFC.Fight.Night.999.1080p.WEB.h264-DRIFTWOOD", "night 999", UFC),
            Row("Formula.1.2026.Round.Kestrel.Race.1080p.WEB.h264-WREN", ""),
            Row("Kestrel.Premier.Division.Round.2026.1080p.WEB.h264-WREN", ""),
            Row("Formula.1.2026.Kestrel.Practice.3.720p.WEB.h264-ORCHID", ""),
        )

        val TAILS = listOf(
            Row(
                "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL",
                "premier league 2026 11 21 lantern bay fc vs copper crown united | 1080p50 web h264 | KESTREL",
            ),
            Row("Lantern.Bay.vs.Copper.Crown.2026.11.21.720p50.WEB.h264-WREN", "lantern bay vs copper crown 2026 11 21 | 720p50 web h264 | WREN"),
            Row("UFC.999.Prelims.WEB.h264-KESTREL", "ufc 999 prelims | web h264 | KESTREL"),
            Row(
                "EPL.2026.11.21.Lantern.Bay.FC.v.Copper.Crown.United.HDTV.x264-ORCHID",
                "epl 2026 11 21 lantern bay fc v copper crown united | hdtv x264 | ORCHID",
            ),
            Row("Formula1.2026.Kestrel.GP.Race.h265-WREN", "formula1 2026 kestrel gp race | h265 | WREN"),
            Row("Velmora.Monday.Brawl.2026.11.23.AAC2.0.H.264-KESTREL", "velmora monday brawl 2026 11 23 | aac2 0 h 264 | KESTREL"),
            Row("Formula1.2026.Round20.Kestrel.Race.60fps.WEB-KESTREL", "formula1 2026 round20 kestrel race | 60fps web | KESTREL"),
            Row("Kestrel.Boxing.2026.11.21.Halde.vs.Brisk.H.264-WREN", "kestrel boxing 2026 11 21 halde vs brisk | h 264 | WREN"),
            Row("Kestrel.Boxing.2026.11.21.Halde.vs.Brisk.x265-WREN", "kestrel boxing 2026 11 21 halde vs brisk | x265 | WREN"),
            Row(
                "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.DDP5.1.Atmos-KESTREL",
                "lantern bay fc vs copper crown united 2026 11 21 | ddp5 1 atmos | KESTREL",
            ),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.1080i.HDTV.MPEG2-WREN", "lantern bay fc vs copper crown united | 1080i hdtv mpeg2 | WREN"),
            Row(
                "Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.2160p.HDR.HEVC-ORCHID",
                "lantern bay fc vs copper crown united 2026 11 21 | 2160p hdr hevc | ORCHID",
            ),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.SDTV.XviD-WREN", "lantern bay fc vs copper crown united 2026 11 21 | sdtv xvid | WREN"),
            Row("Velmora.Monday.Brawl.2026.11.23.4K.WEB.h265-ORCHID", "velmora monday brawl 2026 11 23 | 4k web h265 | ORCHID"),
            Row("Kestrel.Boxing.Halde.vs.Brisk.1080p-WREN", "kestrel boxing halde vs brisk | 1080p | WREN"),
            Row(
                "Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB-DL.AAC2.0.H.264-KESTREL",
                "lantern bay fc vs copper crown united | 720p web dl aac2 0 h 264 | KESTREL",
            ),
            // No tail: no group, and a name's last hyphenated word stays in it.
            Row("Lantern.Bay.FC.vs.Copper.Crown.United", "lantern bay fc vs copper crown united | none | none"),
            Row("Formula1.2026.Round20.Kestrel.Grand.Prix.Pre-Race", "formula1 2026 round20 kestrel grand prix pre race | none | none"),
            // A group in brackets is no hyphenated word: it stays in the tail.
            Row("Lantern Bay FC vs Copper Crown United [1080p] (KESTREL)", "lantern bay fc vs copper crown united | 1080p kestrel | none"),
        )

        val SIDES = listOf(
            Row("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL", "lantern bay fc | vs | copper crown united"),
            Row("EPL.2026.11.21.Lantern.Bay.FC.v.Copper.Crown.United.1080p.HDTV.x264-ORCHID", "lantern bay fc | v | copper crown united"),
            Row("Lantern.Bay.FC.VS.Copper.Crown.United.1080p.WEB.h264-WREN", "lantern bay fc | vs | copper crown united"),
            Row("NBA.2026.11.20.Easton.Comets_@_Marrowby.Lynx.1080p.WEB.h264-KESTREL", "easton comets | @ | marrowby lynx"),
            Row("NBA-2026-11-20_EAC@MLX_1080p_WEB_h264-WREN", "eac | @ | mlx"),
            Row("Easton.Comets.at.Marrowby.Lynx.2026.11.20.720p.WEB.h264-WREN", "easton comets | at | marrowby lynx"),
            // "at" splits a name only where nothing else does.
            Row("Premier.League.at.Saltmere.Lantern.Bay.vs.Copper.Crown.2026.11.21.720p.WEB.h264-WREN", "saltmere lantern bay | vs | copper crown"),
            Row("Lantern.Bay.vs.Copper.Crown.2026.11.21.720p50.WEB.h264-WREN", "lantern bay | vs | copper crown"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.Extended.Highlights.1080p.WEB.h264-ORCHID", "lantern bay fc | vs | copper crown united"),
            Row("Premier.League.2026.11.21.LBF.vs.CCU.720p.WEB.h264-WREN", "lbf | vs | ccu"),
            Row("UFC.999.Quessel.vs.Halde.Results.720p.WEB.h264-WREN", "quessel | vs | halde", UFC),
            Row("UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL", "ufc 999 quessel | vs | halde"),
            Row("Halde.vs.Brisk.1080p.WEB.h264-WREN", "halde | vs | brisk"),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.11.21.Game.1.1080p.WEB.h264-KESTREL", "kestrel club 03 | vs | kestrel club 07"),
            Row("Lantern.Bay.FC.Frauen.vs.Copper.Crown.United.Frauen.2026.11.21.1080p.WEB.h264-WREN", "lantern bay fc | vs | copper crown united"),
            Row("Lantern.Bay.FC.W.vs.Copper.Crown.United.W.2026.11.21.1080p.WEB.h264-WREN", "lantern bay fc | vs | copper crown united"),
            Row("Lantern.Bay.FC.Reserve.Team.vs.Copper.Crown.United.Reserve.Team.2026.11.21.720p.WEB.h264-WREN", "lantern bay fc | vs | copper crown united"),
            Row("Thistlecombe & Kestrel Vale vs Duskwater FC 2026 11 21 720p", "thistlecombe and kestrel vale | vs | duskwater fc"),
            Row("Lantern Bay FC vs Cöpper Crówn United 1080p", "lantern bay fc | vs | copper crown united"),
            Row("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", "none"),
            Row("Premier.League.2026.Week.10.1080p.WEB.h264-WREN", "none"),
            Row("vs.Copper.Crown.United.1080p.WEB.h264-WREN", "none"),
        )

        val WORDS = listOf(
            // Non-event words.
            Row("$GAME.Preview.720p.WEB.h264-WREN", "show:preview"),
            Row("$GAME.Previews.720p.WEB.h264-WREN", "show:preview"),
            Row("$GAME.Build-Up.720p.WEB.h264-WREN", "show:build-up"),
            Row("$GAME.Buildup.720p.WEB.h264-WREN", "show:build-up"),
            Row("$CARD.Press.Conference.720p.WEB.h264-WREN", "show:press conference"),
            Row("$GAME.Interview.720p.WEB.h264-WREN", "show:interview"),
            Row("$CARD.Weigh-In.720p.WEB.h264-WREN", "show:weigh-in"),
            Row("$CARD.Ceremonial.Weigh.Ins.720p.WEB.h264-WREN", "show:weigh-in"),
            Row("$CARD.Face-Offs.720p.WEB.h264-WREN", "show:face-offs"),
            Row("$CARD.Behind.The.Scenes.720p.WEB.h264-WREN", "show:behind the scenes"),
            Row("$CARD.Kickoff.Show.720p.WEB.h264-WREN", "show:kickoff show"),
            Row("$CARD.Red.Carpet.720p.WEB.h264-WREN", "show:red carpet"),
            Row("$GAME.Magazine.720p.WEB.h264-WREN", "show:magazine"),
            Row("$GAME.Review.720p.WEB.h264-WREN", "show:review"),
            Row("$GAME.Analysis.720p.WEB.h264-WREN", "show:analysis"),
            Row("$GAME.Reaction.720p.WEB.h264-WREN", "show:reaction"),
            Row("$GAME.Podcast.720p.WEB.h264-WREN", "show:podcast"),
            Row("$GAME.Documentary.720p.WEB.h264-WREN", "show:documentary"),
            Row("$GAME.Promo.720p.WEB.h264-WREN", "show:promo"),
            Row("$GAME.Trailer.720p.WEB.h264-WREN", "show:trailer"),
            Row("$GAME.Pre-Game.720p.WEB.h264-WREN", "show:pre-game"),
            Row("$GAME.Postgame.720p.WEB.h264-WREN", "show:post-game"),
            Row("$GAME.Pre-Match.720p.WEB.h264-WREN", "show:pre-match"),
            Row("$GAME.Post.Match.720p.WEB.h264-WREN", "show:post-match"),
            Row("$GAME.Top.Moments.720p.WEB.h264-WREN", "show:top moments"),
            Row("$CARD.Post-Fight.720p.WEB.h264-WREN", "show:post-fight"),
            Row("$GAME.After.Show.720p.WEB.h264-WREN", "show:after show"),
            Row("$GAME.Half.Time.Show.720p.WEB.h264-WREN", "show:halftime show"),
            Row("$GAME.Intermission.720p.WEB.h264-WREN", "show:intermission"),
            Row("$CARD.Press.Event.720p.WEB.h264-WREN", "show:press event"),
            Row("$CARD.Final.Face-Off.720p.WEB.h264-WREN", "show:final face-off"),
            Row("$CARD.Open.Workouts.720p.WEB.h264-WREN", "show:open workout"),
            Row("$GAME.Media.Day.720p.WEB.h264-WREN", "show:media day"),
            Row("$GAME.Media.Scrum.720p.WEB.h264-WREN", "show:media scrum"),
            Row("$CARD.Meet.&.Greet.720p.WEB.h264-WREN", "show:meet and greet"),
            Row("$CARD.Q&A.720p.WEB.h264-WREN", "show:q and a"),
            Row("$CARD.Predictions.720p.WEB.h264-WREN", "show:predictions"),
            Row("$CARD.Fight.Breakdown.720p.WEB.h264-WREN", "show:fight breakdown"),
            Row("$GAME.Film.Room.720p.WEB.h264-WREN", "show:film breakdown"),
            Row("$GAME.Wrap-Up.720p.WEB.h264-WREN", "show:wrap-up"),
            Row("$GAME.Debrief.720p.WEB.h264-WREN", "show:debrief"),
            Row("$GAME.Studio.Show.720p.WEB.h264-WREN", "show:studio show"),
            Row("$GAME.Talk.Show.720p.WEB.h264-WREN", "show:talk show"),
            Row("$GAME.Panel.Show.720p.WEB.h264-WREN", "show:panel show"),
            Row("$GAME.Mailbag.720p.WEB.h264-WREN", "show:mailbag"),
            Row("$GAME.Phone-In.720p.WEB.h264-WREN", "show:phone-in"),
            Row("$CARD.Vlog.720p.WEB.h264-WREN", "show:vlog"),
            Row("$GAME.Watchalong.720p.WEB.h264-WREN", "show:watch-along"),
            Row("$CARD.Watch.Party.720p.WEB.h264-WREN", "show:watch party"),
            Row("$GAME.Docuseries.720p.WEB.h264-WREN", "show:docuseries"),
            Row("$GAME.Featurette.720p.WEB.h264-WREN", "show:featurette"),
            Row("$GAME.Retrospective.720p.WEB.h264-WREN", "show:retrospective"),
            Row("$GAME.Locker.Room.720p.WEB.h264-WREN", "show:locker room"),
            Row("$GAME.Tunnel.Cam.720p.WEB.h264-WREN", "show:tunnel cam"),
            Row("$GAME.Fan.Cam.720p.WEB.h264-WREN", "show:fan cam"),
            Row("$GAME.Mic'd.Up.720p.WEB.h264-WREN", "show:micd up"),
            // A camp's training is no practice session.
            Row("$CARD.Training.Camp.720p.WEB.h264-WREN", "show:training camp"),
            Row("$CARD.Sparring.720p.WEB.h264-WREN", "show:sparring"),
            Row("$GAME.Bloopers.720p.WEB.h264-WREN", "show:bloopers"),
            Row("$GAME.Bonus.Features.720p.WEB.h264-WREN", "show:bonus features"),
            Row("$CARD.Teaser.720p.WEB.h264-WREN", "show:teaser"),
            Row("$CARD.Hype.Video.720p.WEB.h264-WREN", "show:hype video"),
            Row("$GAME.Compilation.720p.WEB.h264-WREN", "show:compilation"),
            Row("$GAME.Top.Plays.720p.WEB.h264-WREN", "show:top plays"),
            Row("$GAME.Draw.Ceremony.720p.WEB.h264-WREN", "show:draw ceremony"),
            // A result word stays one inside a programme's words.
            Row("$GAME.Victory.Parade.720p.WEB.h264-WREN", "show:victory parade, result:victory"),
            Row("$GAME.Season.Launch.720p.WEB.h264-WREN", "show:season launch"),
            Row("$RACE.Car.Launch.1080p.WEB.h264-WREN", "show:car launch"),
            Row("$RACE.Livery.Reveal.1080p.WEB.h264-WREN", "show:livery reveal"),
            Row("$GAME.Kit.Launch.720p.WEB.h264-WREN", "show:kit launch"),
            Row("$GAME.Highlights.720p.WEB.h264-WREN", "cut:highlights"),
            Row("$GAME.Extended.Highlights.1080p.WEB.h264-ORCHID", "cut:highlights"),
            Row("$GAME.Recap.720p.WEB.h264-WREN", "cut:recap"),
            Row("$GAME.Condensed.Game.720p.WEB.h264-WREN", "cut:condensed"),
            Row("$GAME.Summary.720p.WEB.h264-WREN", "cut:summary"),
            Row("$GAME.Key.Moments.720p.WEB.h264-WREN", "cut:key moments"),
            Row("$GAME.Best.Bits.720p.WEB.h264-WREN", "cut:best bits"),
            Row("$GAME.All.The.Goals.720p.WEB.h264-WREN", "cut:all goals"),
            Row("Kestrel.vs.Saltmere.2026.11.21.All.Tries.720p.WEB.h264-WREN", "cut:all tries"),
            // Content words first: "Pre-Race" is a pre-race show and no race, "Post-Qualifying" no qualifying.
            Row("$RACE.Grand.Prix.Pre-Race.Show.1080p.WEB.h264-WREN", "race show:pre-race, gp"),
            Row("$RACE.Post-Race.1080p.WEB.h264-WREN", "race show:post-race"),
            Row("$RACE.Post-Qualifying.Analysis.1080p.WEB.h264-WREN", "race show:post-qualifying, show:analysis"),
            Row("$RACE.Pre-Sprint.1080p.WEB.h264-WREN", "race show:pre-sprint"),
            Row("$RACE.Post-Sprint.1080p.WEB.h264-WREN", "race show:post-sprint"),
            Row("$RACE.Race.Build-Up.1080p.WEB.h264-WREN", "race show:race build-up"),
            Row("$RACE.Qualifying.Build-Up.1080p.WEB.h264-WREN", "race show:qualifying build-up"),
            Row("$RACE.Sprint.Build-Up.1080p.WEB.h264-WREN", "race show:sprint build-up"),
            Row("$RACE.Race.Preview.1080p.WEB.h264-WREN", "race show:race preview"),
            Row("$RACE.Qualifying.Preview.1080p.WEB.h264-WREN", "race show:qualifying preview"),
            Row("$RACE.Race.Reaction.1080p.WEB.h264-WREN", "race show:race reaction"),
            Row("$RACE.Qualifying.Reaction.1080p.WEB.h264-WREN", "race show:qualifying reaction"),
            Row("$RACE.Sprint.Reaction.1080p.WEB.h264-WREN", "race show:sprint reaction"),
            Row("$RACE.Race.Analysis.1080p.WEB.h264-WREN", "race show:race analysis"),
            Row("$RACE.Qualifying.Analysis.1080p.WEB.h264-WREN", "race show:qualifying analysis"),
            Row("$RACE.Race.Review.1080p.WEB.h264-WREN", "race show:race review"),
            Row("$RACE.Grid.Walk.1080p.WEB.h264-WREN", "race show:grid walk"),
            Row("$RACE.Drivers'.Parade.1080p.WEB.h264-WREN", "race show:drivers parade"),
            Row("$RACE.Grand.Prix.Race.Highlights.720p.WEB.h264-ORCHID", "cut:highlights, session:race, gp"),
            // Partial words.
            Row("$GAME.1st.Half.1080p.WEB.h264-WREN", "partial:1st half"),
            Row("$GAME.2nd.Half.1080p.WEB.h264-WREN", "partial:2nd half"),
            Row("$GAME.First.Half.1080p.WEB.h264-WREN", "partial:1st half"),
            Row("$GAME.Second.Half.1080p.WEB.h264-WREN", "partial:2nd half"),
            Row("$GAME.Half.2.1080p.WEB.h264-WREN", "partial:half 2"),
            Row("$GAME.Quarter.3.1080p.WEB.h264-WREN", "partial:quarter 3"),
            Row("$GAME.3rd.Quarter.1080p.WEB.h264-WREN", "partial:quarter 3"),
            Row("$GAME.Period.2.1080p.WEB.h264-WREN", "partial:period 2"),
            Row("Kestrel.vs.Saltmere.2026.11.21.Innings.1.1080p.WEB.h264-WREN", "partial:innings 1"),
            Row("$GAME.First.Quarter.1080p.WEB.h264-WREN", "partial:quarter 1"),
            Row("Easton.Comets.vs.Marrowby.Lynx.2026.11.21.Inning.7.720p.WEB.h264-WREN", "partial:inning 7"),
            Row("$GAME.Extra.Time.1080p.WEB.h264-WREN", "partial:extra time"),
            Row("$GAME.Penalty.Shoot-Out.1080p.WEB.h264-WREN", "partial:penalty shootout"),
            Row("Kestrel.Half.Marathon.2026.11.21.1080p.WEB.h264-WREN", ""),
            // A quarter needs its number: a quarter-final is a game.
            Row("Kestrel.Cup.2026.11.21.Quarter.Final.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-WREN", ""),
            // Pack words, and a week or matchday with no sides.
            Row("Kestrel.Premier.Division.2026.Bundle.1080p.WEB.h264-WREN", "pack:bundle"),
            Row("Kestrel.Premier.Division.2026.Complete.Season.1080p.WEB.h264-WREN", "pack:complete season"),
            Row("Kestrel.Premier.Division.2026.Full.Season.1080p.WEB.h264-WREN", "pack:full season"),
            Row("Kestrel.Premier.Division.2026.All.Games.1080p.WEB.h264-WREN", "pack:all games"),
            Row("Kestrel.Cup.2026.Full.Tournament.1080p.WEB.h264-WREN", "pack:full tournament"),
            Row("Kestrel.Cup.2026.Complete.Competition.1080p.WEB.h264-WREN", "pack:complete competition"),
            Row("NBA.2026.Full.Playoffs.1080p.WEB.h264-WREN", "pack:full playoffs"),
            Row("Kestrel.vs.Saltmere.2026.Full.Series.1080p.WEB.h264-WREN", "pack:full series"),
            Row("Formula1.2026.All.Races.1080p.WEB.h264-WREN", "pack:all races"),
            Row("Premier.League.2026.All.Matchdays.720p.WEB.h264-WREN", "pack:all matchdays"),
            Row("Velmora.Monday.Brawl.2026.All.Shows.1080p.WEB.h264-WREN", "pack:all shows"),
            Row("Velmora.Monday.Brawl.2026.All.Episodes.1080p.WEB.h264-WREN", "pack:all episodes"),
            Row("Kestrel.Premier.Division.Complete.Collection.1080p.WEB.h264-WREN", "pack:complete collection"),
            Row("Velmora.Iron.Crown.Box.Set.1080p.WEB.h264-WREN", "pack:box set"),
            Row("Premier.League.2026.Week.10.1080p.WEB.h264-WREN", "pack:week"),
            Row("Premier.League.2026.Matchday.12.720p.WEB.h264-WREN", "pack:matchday"),
            Row("Premier.League.2026.Week.10.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN", ""),
            // Part words.
            Row("$CARD.Early.Prelims.1080p.WEB.h264-KESTREL", "part:early prelims"),
            Row("$CARD.Prelims.1080p.WEB.h264-KESTREL", "part:prelims"),
            Row("$CARD.Preliminary.Card.1080p.WEB.h264-KESTREL", "part:prelims"),
            Row("$CARD.Preliminaries.1080p.WEB.h264-KESTREL", "part:prelims"),
            Row("$CARD.Main.Card.1080p.WEB.h264-KESTREL", "part:main card"),
            Row("$CARD.Maincard.1080p.WEB.h264-KESTREL", "part:main card"),
            Row("Kestrel.Boxing.2026.11.21.Halde.vs.Brisk.Main.Event.Only.720p.WEB.h264-WREN", "part:main event only"),
            Row("$CARD.Full.Event.1080p.WEB.h264-ORCHID", "part:full event"),
            Row("$CARD.Full.Card.1080p.WEB.h264-ORCHID", "part:full event"),
            Row("$CARD.Complete.Event.1080p.WEB.h264-ORCHID", "part:full event"),
            // The PPV broadcast in full is still the part sold on its own, never the whole event.
            Row("$CARD.Full.PPV.1080p.WEB.h264-ORCHID", "part:ppv"),
            Row("$CARD.PPV.Quessel.vs.Halde.720p.WEB.h264-WREN", "part:ppv"),
            Row("$SHOW.Pre-Show.1080p.WEB.h264-KESTREL", "part:pre-show"),
            Row("$SHOW.Preshow.1080p.WEB.h264-KESTREL", "part:pre-show"),
            Row("$CARD.Pre-Fight.Show.1080p.WEB.h264-KESTREL", "part:pre-show"),
            Row("$CARD.Prefight.Show.1080p.WEB.h264-KESTREL", "part:pre-show"),
            Row("$SHOW.Main.Show.1080p.WEB.h264-KESTREL", "part:main show"),
            Row("$SHOW.Post.Show.1080p.WEB.h264-KESTREL", "part:post show"),
            // A post-fight show is read as the programme first, which leaves it out all the same.
            Row("$CARD.Post.Fight.Show.720p.WEB.h264-WREN", "show:post-fight"),
            // "Kickoff Show" and "Red Carpet" are part words only beside a show: its pre-show. Elsewhere they are programmes
            // of their own. "Countdown" is a programme everywhere.
            Row("$SHOW.Kickoff.Show.1080p.WEB.h264-KESTREL", "part:pre-show", IN_A_SHOW),
            Row("$SHOW.Kick.Off.Show.1080p.WEB.h264-KESTREL", "part:pre-show", IN_A_SHOW),
            Row("$SHOW.Red.Carpet.1080p.WEB.h264-KESTREL", "part:pre-show", IN_A_SHOW),
            Row("$SHOW.Countdown.1080p.WEB.h264-KESTREL", "show:countdown", IN_A_SHOW),
            Row("$SHOW.Countdown.1080p.WEB.h264-KESTREL", "show:countdown"),
            Row("$CARD.Countdown.720p.WEB.h264-WREN", "show:countdown"),
            // Session words.
            Row("$RACE.FP1.1080p.WEB.h264-KESTREL", "session:practice 1"),
            Row("$RACE.FP2.1080p.WEB.h264-KESTREL", "session:practice 2"),
            Row("$RACE.FP3.1080p.WEB.h264-KESTREL", "session:practice 3"),
            Row("$RACE.Practice.1.1080p.WEB.h264-KESTREL", "session:practice 1"),
            Row("$RACE.Practice.3.1080p.WEB.h264-KESTREL", "session:practice 3"),
            Row("$RACE.Free.Practice.2.1080p.WEB.h264-KESTREL", "session:practice 2"),
            Row("$RACE.FP.2.1080p.WEB.h264-KESTREL", "session:practice 2"),
            Row("$RACE.Second.Practice.1080p.WEB.h264-KESTREL", "session:practice 2"),
            Row("$RACE.Final.Practice.1080p.WEB.h264-KESTREL", "session:practice"),
            Row("$RACE.Qualifying.1080p.WEB.h264-KESTREL", "session:qualifying"),
            Row("$RACE.Quali.1080p.WEB.h264-KESTREL", "session:qualifying"),
            Row("$RACE.Qualifying.2.1080p.WEB.h264-KESTREL", "session:qualifying 2"),
            Row("$RACE.Q2.1080p.WEB.h264-KESTREL", "session:qualifying 2"),
            Row("$RACE.Top.10.Shootout.1080p.WEB.h264-KESTREL", "session:qualifying"),
            Row("$RACE.Superpole.1080p.WEB.h264-KESTREL", "session:qualifying"),
            Row("$RACE.Superpole.Race.1080p.WEB.h264-KESTREL", "session:sprint"),
            Row("$RACE.Sprint.Qualifying.1080p.WEB.h264-KESTREL", "session:sprintqualifying"),
            Row("$RACE.Sprint.Shootout.1080p.WEB.h264-KESTREL", "session:sprintqualifying"),
            Row("$RACE.Sprint.1080p.WEB.h264-KESTREL", "session:sprint"),
            Row("$RACE.Sprint.Race.2.1080p.WEB.h264-KESTREL", "session:sprint 2"),
            Row("$RACE.Feature.Race.1080p.WEB.h264-KESTREL", "session:race"),
            Row("$RACE.Race.1080p.WEB.h264-KESTREL", "session:race"),
            Row("$RACE.Race.2.1080p.WEB.h264-KESTREL", "session:race 2"),
            Row("$RACE.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", "session:race, gp"),
            Row("F1.2026.Kestrel.GP.Race.2160p.WEB.h265-WREN", "session:race, gp"),
            Row("$RACE.Grand.Prix.Qualifying.1080p.WEB.h264-KESTREL", "session:qualifying, gp"),
            Row("$RACE.Warm-Up.1080p.WEB.h264-KESTREL", "session:warmup"),
            Row("$RACE.Shakedown.1080p.WEB.h264-KESTREL", "session:testing"),
            Row("Formula1.2026.Testing.1080p.WEB.h264-KESTREL", "session:testing"),
            Row("Formula1.2026.Test.Day.2.1080p.WEB.h264-KESTREL", "session:testing 2"),
            Row("Formula1.2026.Pre-Season.Testing.1080p.WEB.h264-KESTREL", "session:testing"),
            // In German, French, Dutch, Spanish and Italian, marked.
            Row("Formula1.2026.GP.Kestrel.Rennen.German.1080p.WEB.h264-WREN", "session:race foreign, gp"),
            Row("$RACE.Rennen.2.German.1080p.WEB.h264-WREN", "session:race 2 foreign"),
            Row("$RACE.Qualifikation.German.1080p.WEB.h264-WREN", "session:qualifying foreign"),
            Row("$RACE.Zeittraining.German.1080p.WEB.h264-WREN", "session:qualifying foreign"),
            Row("$RACE.Training.2.German.1080p.WEB.h264-WREN", "session:practice 2 foreign"),
            Row("$RACE.Freies.Training.1.German.1080p.WEB.h264-WREN", "session:practice 1 foreign"),
            Row("$RACE.Course.French.1080p.WEB.h264-WREN", "session:race foreign"),
            Row("$RACE.Qualifications.French.1080p.WEB.h264-WREN", "session:qualifying foreign"),
            Row("$RACE.Essais.Libres.1.French.1080p.WEB.h264-WREN", "session:practice 1 foreign"),
            Row("$RACE.Essais.Libres.French.1080p.WEB.h264-WREN", "session:practice foreign"),
            Row("$RACE.EL2.French.1080p.WEB.h264-WREN", "session:practice 2 foreign"),
            Row("$RACE.Kwalificatie.Dutch.1080p.WEB.h264-WREN", "session:qualifying foreign"),
            Row("$RACE.Vrije.Training.3.Dutch.1080p.WEB.h264-WREN", "session:practice 3 foreign"),
            Row("$RACE.Practica.Spanish.1080p.WEB.h264-WREN", "session:practice foreign"),
            Row("$RACE.Clasificacion.Spanish.1080p.WEB.h264-WREN", "session:qualifying foreign"),
            Row("$RACE.Calentamiento.Spanish.1080p.WEB.h264-WREN", "session:warmup foreign"),
            Row("$RACE.Carrera.Sprint.Spanish.1080p.WEB.h264-WREN", "session:sprint foreign"),
            Row("$RACE.Prove.Libere.1.Italian.1080p.WEB.h264-WREN", "session:practice 1 foreign"),
            Row("$RACE.Qualifiche.Sprint.Italian.1080p.WEB.h264-WREN", "session:sprintqualifying foreign"),
            Row("$RACE.Gara.2.Italian.1080p.WEB.h264-WREN", "session:race 2 foreign"),
            // Result words, as whole tokens.
            Row("$GAME.Result.720p.WEB.h264-ORCHID", "result:result"),
            Row("UFC.999.Quessel.vs.Halde.Results.720p.WEB.h264-WREN", "result:results"),
            Row("$GAME.Winner.720p.WEB.h264-ORCHID", "result:winner"),
            Row("Kestrel.Cup.2026.Winners.720p.WEB.h264-ORCHID", "result:winners"),
            Row("Quarrydown.FC.Wins.2026.11.21.720p.WEB.h264-ORCHID", "result:wins"),
            Row("Quarrydown.FC.Won.2026.11.21.720p.WEB.h264-ORCHID", "result:won"),
            Row("Quarrydown.FC.Beat.Ellerholt.United.2026.11.21.720p.WEB.h264-ORCHID", "result:beat"),
            Row("Quarrydown.FC.Beats.Ellerholt.United.2026.11.21.720p.WEB.h264-ORCHID", "result:beats"),
            Row("Quessel.Defeats.Halde.2026.11.21.720p.WEB.h264-ORCHID", "result:defeats"),
            Row("Halde.Defeated.2026.11.21.720p.WEB.h264-ORCHID", "result:defeated"),
            Row("Quarrydown.FC.Victory.2026.11.21.720p.WEB.h264-ORCHID", "result:victory"),
            Row("Halde.Retains.2026.11.21.720p.WEB.h264-ORCHID", "result:retains"),
            Row("Kestrel.Boxing.2026.11.21.Halde.KO.Brisk.720p.WEB.h264-ORCHID", "result:ko"),
            Row("Kestrel.Boxing.2026.11.21.Halde.TKO.Brisk.720p.WEB.h264-ORCHID", "result:tko"),
            Row("Velmora.Monday.Brawl.2026.11.23.Wonderland.1080p.WEB.h264-KESTREL", ""),
            // A word of the event's own title, league or sides is never a result word.
            Row("Velmora.Iron.Crown.Victory.Night.2026.11.21.1080p.WEB.h264-KESTREL", "", NameContext(title = "Velmora Iron Crown: Victory Night")),
            Row(
                "Velmora.Iron.Crown.Victory.Night.2026.11.21.Results.1080p.WEB.h264-KESTREL",
                "result:results",
                NameContext(title = "Velmora Iron Crown: Victory Night"),
            ),
            Row(
                "Kestrel.Winners.Cup.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-KESTREL",
                "",
                NameContext(league = "Kestrel Winners Cup"),
            ),
            Row("Pellbrook.Victory.vs.Ostrey.City.2026.11.21.720p.WEB.h264-WREN", "", NameContext(sides = listOf("Pellbrook Victory", "Ostrey City"))),
            Row("Pellbrook.Victory.vs.Ostrey.City.2026.11.21.720p.WEB.h264-WREN", "result:victory"),
            // Sibling markers.
            Row("Quarrydown.FC.Women.vs.Ellerholt.United.Women.2026.11.21.720p.WEB.h264-WREN", "sibling:women"),
            Row("Quarrydown.FC.Womens.vs.Ellerholt.United.Womens.2026.11.21.720p.WEB.h264-WREN", "sibling:women"),
            Row("Quarrydown FC Women's vs Ellerholt United Women's 2026 11 21 720p", "sibling:women"),
            Row("Quarrydown.FC.Frauen.vs.Ellerholt.United.Frauen.2026.11.21.720p.WEB.h264-WREN", "sibling:women"),
            Row("Quarrydown.Feminines.vs.Ellerholt.Feminines.2026.11.21.720p.WEB.h264-WREN", "sibling:women"),
            Row("Quarrydown.Ladies.vs.Ellerholt.Ladies.2026.11.21.720p.WEB.h264-WREN", "sibling:women"),
            Row("Quarrydown.FC.Youth.vs.Ellerholt.United.Youth.2026.11.21.720p.WEB.h264-WREN", "sibling:youth"),
            Row("Quarrydown.FC.Reserves.vs.Ellerholt.United.Reserves.2026.11.21.720p.WEB.h264-WREN", "sibling:reserves"),
            Row("Quarrydown.FC.U17.vs.Ellerholt.United.U17.2026.11.21.720p.WEB.h264-WREN", "sibling:u17"),
            Row("Quarrydown.FC.U21.vs.Ellerholt.United.U21.2026.11.21.720p.WEB.h264-WREN", "sibling:u21"),
            Row("Quarrydown.FC.U23.vs.Ellerholt.United.U23.2026.11.21.720p.WEB.h264-WREN", "sibling:u23"),
            Row("Quarrydown.FC.U-21.vs.Ellerholt.United.U-21.2026.11.21.720p.WEB.h264-WREN", "sibling:u21"),
            Row("Quarrydown.FC.Under.21.vs.Ellerholt.United.Under-21.2026.11.21.720p.WEB.h264-WREN", "sibling:u21"),
            Row("Quarrydown.FC.U14.vs.Ellerholt.United.U14.2026.11.21.720p.WEB.h264-WREN", ""),
            Row("$GAME.720p.WEB.h264-WREN", ""),
        )

        val SCORES = listOf(
            // Joined by a hyphen, colon or dash, glued or spaced; by a dot, space or underscore; by an x.
            Row("Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.1080p.WEB.h264-KESTREL", "score"),
            Row("Lantern Bay FC 3 - 1 Copper Crown United 1080p", "score"),
            Row("Premier.League.2026.11.21.LBF.3x1.CCU.720p.WEB.h264-WREN", "score"),
            Row("Premier.League.2026.11.21.Quarrydown.FC.1-2.Ellerholt.United.1080p.WEB.h264-KESTREL", "score"),
            Row("Premier.League.2026.11.21.Kelderby.FC.2-0.Amberlow.Rovers.1080p.WEB.h264-KESTREL", "score"),
            Row("Lantern Bay FC 3:1 Copper Crown United 720p", "score"),
            Row("Lantern Bay FC 3–1 Copper Crown United 720p", "score"),
            Row("Lantern Bay FC 3 : 1 Copper Crown United 720p", "score"),
            Row("Lantern Bay FC 3 – 1 Copper Crown United 720p", "score"),
            Row("Lantern_Bay_FC_3_1_Copper_Crown_United_720p", "score"),
            Row("Lantern Bay FC 3 1 Copper Crown United 720p", "score"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2.1.1080p.WEB.h264-WREN", "score"),
            Row("NBA.2026.11.20.Easton.Comets.112.98.Marrowby.Lynx.1080p.WEB.h264-KESTREL", "score"),
            Row("Lantern.Bay.FC.2.0.Copper.Crown.United.1080p.WEB.h264-WREN", "score"),
            // A day and month with no year elsewhere is no date: two numbers beside a side.
            Row("Lantern.Bay.vs.Copper.Crown.21.11.720p50.WEB.h264-WREN", "score"),
            // The series' own number is masked only when the series is given.
            Row("Formula.1.20.Kestrel.Race.1080p.WEB.h264-WREN", "score"),
            Row("Formula.1.20.Kestrel.Race.1080p.WEB.h264-WREN", "none", NameContext(series = listOf("Formula 1", "Formula1", "F1"))),
            // A title's own number beside the word after it stays its own where the numbers before it make no date.
            Row("KCL.2026.13.21.Brisk.vs.Loam.1080p.WEB.h264-WREN", "none", KCL_21),
            // A side's own number is a side's word.
            Row(
                "Kestrel.Club.03.vs.Kestrel.Club.07.2.1.1080p.WEB.h264-WREN",
                "score",
                NameContext(sides = listOf("Kestrel Club 03", "Kestrel Club 07")),
            ),
            // Audio channels, codecs, dates, rounds, card numbers and running numbers are no score.
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.1080p.WEB.DD.5.1.H.264-KESTREL", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.WEB.DDP5.1.H.264-KESTREL", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.WEB.AAC2.0.x264-WREN", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.7.1.1080p.WEB.h264-WREN", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.5.1.WEB.h264-WREN", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2.0.1080p.WEB.h264-WREN", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.H.264-KESTREL", "none"),
            Row("Lantern.Bay.FC.vs.Copper.Crown.United.2026.11.21.x264-KESTREL", "none"),
            Row("Formula.1.2026.Kestrel.GP.Race.1080p.WEB.h264-WREN", "none"),
            Row("Formula.1.2026.Round.20.Kestrel.Race.720p50.WEB.h264-ORCHID", "none"),
            Row("UFC.999.Prelims.1080p.WEB.h264-KESTREL", "none"),
            Row("UFC.999.2026.11.21.Prelims.1080p.WEB.h264-KESTREL", "none", UFC),
            Row("Formula1.2026x20.Race.1080p.WEB.h264-DRIFTWOOD", "none"),
            Row("Formula1.S2026E34.Kestrel.Race.1080p.WEB.h264-WREN", "none"),
            Row("Premier.League.21.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "none"),
            Row("Premier.League.10.11.2026.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "none"),
            Row("Premier.League.2026-11-21.Lantern.Bay.FC.vs.Copper.Crown.United.720p", "none"),
            Row("Kestrel.Hockey.League.2026.Easton.Comets.Saltmere.Gulls.22.11.720p.WEB.h264-WREN", "none"),
            Row("NBA.2026.27.Easton.Comets.vs.Saltmere.Gulls.1080p.WEB.h264-KESTREL", "none"),
            Row("Kestrel.Club.03.vs.Kestrel.Club.07.2026.11.21.Game.1.1080p.WEB.h264-KESTREL", "none"),
            Row("Premier.League.2026.Week.10.Lantern.Bay.FC.vs.Copper.Crown.United.720p.WEB.h264-WREN", "none"),
            Row("Formula.1.2026.Round.20.Kestrel.Practice.3.720p.WEB.h264-ORCHID", "none"),
            Row("Velmora.Iron.Crown.Night.2.2026.11.21.1080p.WEB.h264-KESTREL", "none"),
            Row("Kestrel.Endurance.Series.2026.6.Hours.of.Oskelund.1080p.WEB.h264-KESTREL", "none"),
        )

        /** Names no indexer should send, which the parser still reads without its guard. */
        fun hostile(): List<String> = listOf(
            "", " ", ".", "-", "--", "&", "@", "@@@", "& @ &", "''''", "’", "vs", "v", "at", "x",
            "9".repeat(400), "1.".repeat(300), "1-".repeat(300), "12.11.".repeat(150), "2026.11.".repeat(120), "2026x" + "9".repeat(60),
            "S2026E" + "9".repeat(60), "S" + "9".repeat(60) + "E1", "Round." + "9".repeat(60), "Round" + "9".repeat(60), "U." + "9".repeat(60),
            "U" + "9".repeat(60), "vs.".repeat(300), "Lantern.Bay.vs.".repeat(60), "Pre-Race-".repeat(100), "a-".repeat(300),
            "Lantern.Bay.FC." + ".".repeat(500) + "1080p", "3x1.".repeat(200), "Half.".repeat(200) + "2", "Race.2.".repeat(150),
            "FP1." + "Countdown.Show.".repeat(60), "́́́", "Á̂̃.B", "🏆.Kestrel.🏆",
            "\uD800abc", "abc\uDC00", "\u0000\u0001\u0002", "Ｌａｎｔｅｒｎ．Ｂａｙ", "ßæøœđłþı.vs.ÆØŒ", "2026.02.30.Kestrel",
            "0000.00.00", "00000000", "99999999", "2099.12.31.1900.01.01", "31.12.9999", "1.1.1", "Game.", "Night.1.Night.2.Night.3",
            "1080p-", "-1080p", "WEB-", "H.", "h.264", "1080p" + "-x".repeat(200), "UFC." + "999.".repeat(200),
            "Lantern Bay FC " + "3 - 1 ".repeat(100) + "Copper Crown United",
        )

        /** [count] names from a fixed seed, of separators, digits, letters and list words, up to 600 characters long. */
        fun generated(count: Int, minLength: Int = 0): List<String> {
            val random = Random(646)
            val pieces = listOf(
                ".", "-", " ", "_", "/", "@", "&", "(", ")", "[", "]", ":", "–", "'", "x", "1", "21", "11", "2026", "999", "20261121",
                "2026x07", "S2026E34", "Round", "R", "Week", "Game", "vs", "v", "at", "Pre", "Race", "Half", "U", "21", "Countdown",
                "Show", "Main", "Card", "FP1", "Practice", "Results", "KO", "W", "1080p", "WEB", "H", "264", "AAC2", "0", "5", "é", "́",
                "Lantern", "Bay", "Copper", "Crown", "KESTREL",
            )
            return List(count) {
                val target = minLength + random.nextInt(601 - minLength)
                buildString { while (length < target) append(pieces[random.nextInt(pieces.size)]) }
            }
        }
    }
}

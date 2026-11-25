package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.Days.Side
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a TV's focus goes back to when the row that opened a page has left the list meanwhile (a game watched on its page
 * waits for Show watched): the row now in its place, else the league's or the day's that stands where it stood.
 */
class ListKeysTest {
    @Test
    fun `the row now in the left row's place, in its league's day`() {
        val before = screen(day(SAT, league(PREMIER_SAT, "a", "b", "c")))
        val place = ListKeys.place(before, "b")!!
        assertEquals("row:c", ListKeys.standIn(screen(day(SAT, league(PREMIER_SAT, "a", "c"))), place))
        // The league's last row gone: the one now last.
        val last = ListKeys.place(before, "c")!!
        assertEquals("row:b", ListKeys.standIn(screen(day(SAT, league(PREMIER_SAT, "a", "b"))), last))
    }

    @Test
    fun `a league gone from its day gives the one now in its place, a day gone the next the side lists`() {
        val before = screen(day(SAT, league(PREMIER_SAT, "a"), league(F1_SAT, "r"), league(UFC_SAT, "u")), day(FRI, league(PREMIER_FRI, "f")))
        assertEquals("row:u", ListKeys.standIn(screen(day(SAT, league(PREMIER_SAT, "a"), league(UFC_SAT, "u")), day(FRI, league(PREMIER_FRI, "f"))), ListKeys.place(before, "r")!!))
        // Recent goes back in time: the day after Saturday's in the list is Friday's.
        val onlyFriday = screen(day(FRI, league(PREMIER_FRI, "f")))
        assertEquals("row:f", ListKeys.standIn(onlyFriday, ListKeys.place(screen(day(SAT, league(PREMIER_SAT, "s")), day(FRI, league(PREMIER_FRI, "f"))), "s")!!))
    }

    @Test
    fun `nothing stands in where the place can't be read or the list is empty`() {
        assertNull(ListKeys.standIn(screen(day(SAT, league(PREMIER_SAT, "a"))), "not a place"))
        assertNull(ListKeys.standIn(screen(), ListKeys.place(screen(day(SAT, league(PREMIER_SAT, "a"))), "a")!!))
        assertNull(ListKeys.place(screen(day(SAT, league(PREMIER_SAT, "a"))), "z"))
    }

    private fun screen(vararg days: DayView) = ListScreen(Side.Recent, false, "Show watched", emptyList(), emptyList(), null, emptyList(), days.toList(), null, null)

    private fun day(date: LocalDate, vararg leagues: LeagueView) = DayView(date, date.toString(), leagues.toList())

    private fun league(key: String, vararg rows: String) = LeagueView(key, key, rows.map(::row), null)

    private fun row(key: String) = RowView(key, Pages.EVENT, "15:00", key, null, null, null, emptyList(), watched = false, upcoming = false)

    private companion object {
        val SAT: LocalDate = LocalDate.of(2026, 11, 21)
        val FRI: LocalDate = LocalDate.of(2026, 11, 20)
        const val PREMIER_SAT = "2026-11-21|1"
        const val F1_SAT = "2026-11-21|7"
        const val UFC_SAT = "2026-11-21|10"
        const val PREMIER_FRI = "2026-11-20|1"
    }
}

package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.model.Days.Side
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.ui.Words
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The days the tab lists (L641, L644): Today heads both sides, with what started today and what comes later; Recent goes
 * back 60 local days, newest first, and Upcoming 31 ahead, soonest first, in the device's zone; a weekend sits under the
 * day of its last session started on Recent and of its next on Upcoming; each league shows five a day, then the rest.
 */
class DaysTest {
    private fun ids(days: List<Days.Day>): List<Pair<LocalDate, List<String>>> =
        days.map { day -> day.date to day.leagues.flatMap { league -> league.rows.map { it.listed.key } } }

    @Test
    fun `Today heads both sides, with what started today and what comes later today`() {
        val sports = sports(
            PREMIER to listOf(
                game(1, "2026-11-22T14:00"), game(2, "2026-11-22T22:00"), game(3, "2026-11-21T15:00"), game(4, "2026-11-23T15:00"),
            ),
        )
        val recent = Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC)
        val upcoming = Days.of(sports.listed, Side.Upcoming, SUNDAY, ZoneOffset.UTC)

        assertEquals(listOf(SUN to listOf("ev-000001", "ev-000002"), SAT to listOf("ev-000003")), ids(recent))
        assertEquals(listOf(SUN to listOf("ev-000001", "ev-000002"), MON to listOf("ev-000004")), ids(upcoming))
        assertEquals(listOf(true, false), recent.map { it.today })
    }

    @Test
    fun `Recent is newest first and Upcoming soonest first, a day's events in time order`() {
        val sports = sports(
            PREMIER to listOf(
                game(1, "2026-11-20T15:00"), game(2, "2026-11-21T17:30"), game(3, "2026-11-21T15:00"), game(4, "2026-11-19T20:00"),
                game(5, "2026-11-24T15:00"), game(6, "2026-11-23T20:00"), game(7, "2026-11-23T15:00"), game(8, "2026-12-05T12:00"),
            ),
        )
        assertEquals(
            listOf(SAT to listOf("ev-000003", "ev-000002"), SAT.minusDays(1) to listOf("ev-000001"), SAT.minusDays(2) to listOf("ev-000004")),
            ids(Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC)),
        )
        assertEquals(
            listOf(MON to listOf("ev-000007", "ev-000006"), MON.plusDays(1) to listOf("ev-000005"), LocalDate.of(2026, 12, 5) to listOf("ev-000008")),
            ids(Days.of(sports.listed, Side.Upcoming, SUNDAY, ZoneOffset.UTC)),
        )
    }

    @Test
    fun `Recent reaches 60 local days back and Upcoming 31 ahead, in the device's zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = at("2026-11-22T12:00") // 21:00 on the 22nd in Tokyo
        val sports = sports(
            PREMIER to listOf(
                // 23 Sep is 60 days before the 22nd: from its start in Tokyo, which is 15:00 UTC the day before.
                game(1, "2026-09-22T15:00"), game(2, "2026-09-22T14:59"),
                // 23 Dec is 31 days after: to its end in Tokyo, 15:00 UTC that day.
                game(3, "2026-12-23T14:59"), game(4, "2026-12-23T15:00"),
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 23) to listOf("ev-000001")), ids(Days.of(sports.listed, Side.Recent, now, tokyo)))
        assertEquals(listOf(LocalDate.of(2026, 12, 23) to listOf("ev-000003")), ids(Days.of(sports.listed, Side.Upcoming, now, tokyo)))
    }

    @Test
    fun `a UTC evening is the next day where the zone is ahead, and an early UTC morning the day before where it is behind`() {
        val now = at("2026-11-22T12:00")
        val sports = sports(NBA to listOf(game(1, "2026-11-21T20:00", NBA), game(2, "2026-11-23T01:30", NBA)))

        // Tokyo: Saturday 20:00 UTC is Sunday 05:00, today; Monday 01:30 UTC is Monday 10:30, tomorrow.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(listOf(SUN to listOf("ev-000001")), ids(Days.of(sports.listed, Side.Recent, now, tokyo)))
        assertEquals(listOf(SUN to listOf("ev-000001"), MON to listOf("ev-000002")), ids(Days.of(sports.listed, Side.Upcoming, now, tokyo)))

        // New York: Saturday 20:00 UTC is Saturday 15:00, yesterday; Monday 01:30 UTC is Sunday 20:30, later today.
        val newYork = ZoneId.of("America/New_York")
        assertEquals(listOf(SUN to listOf("ev-000002"), SAT to listOf("ev-000001")), ids(Days.of(sports.listed, Side.Recent, now, newYork)))
        assertEquals(listOf(SUN to listOf("ev-000002")), ids(Days.of(sports.listed, Side.Upcoming, now, newYork)))
    }

    @Test
    fun `a weekend sits under its last session started on Recent and its next on Upcoming`() {
        val sports = sports(FORMULA_1 to kestrelWeekend() + tarnholm())
        val saturdayEvening = at("2026-11-21T20:41")

        // Saturday evening: qualifying was the last to start, the race is next.
        val recent = Days.of(sports.listed, Side.Recent, saturdayEvening, ZoneOffset.UTC)
        assertEquals(listOf(SAT to listOf("2:r20")), ids(recent))
        assertEquals(4, recent.single().leagues.single().rows.single().at.id)
        val upcoming = Days.of(sports.listed, Side.Upcoming, saturdayEvening, ZoneOffset.UTC)
        assertEquals(listOf(SUN to listOf("2:r20"), LocalDate.of(2026, 11, 27) to listOf("2:r21")), ids(upcoming))
        assertEquals(listOf(5, 21), upcoming.map { it.leagues.single().rows.single().at.id })

        // Sunday evening, the race started at 15:00: Today on both sides, dated by the race.
        val sunday = Days.of(sports.listed, Side.Upcoming, SUNDAY, ZoneOffset.UTC)
        assertEquals(listOf(SUN to listOf("2:r20"), LocalDate.of(2026, 11, 27) to listOf("2:r21")), ids(sunday))
        assertEquals(5, sunday.first().leagues.single().rows.single().at.id)
        assertEquals(listOf(SUN to listOf("2:r20")), ids(Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC)))

        // Monday: the weekend is Sunday's on Recent and gone from Upcoming.
        val monday = at("2026-11-23T09:00")
        assertEquals(listOf(SUN to listOf("2:r20")), ids(Days.of(sports.listed, Side.Recent, monday, ZoneOffset.UTC)))
        assertEquals(listOf(LocalDate.of(2026, 11, 27) to listOf("2:r21")), ids(Days.of(sports.listed, Side.Upcoming, monday, ZoneOffset.UTC)))

        // Friday morning before the first session: on Recent only once one has started, but Today shows what comes later today.
        val friday = at("2026-11-20T08:00")
        assertEquals(listOf(FRI to listOf("2:r20")), ids(Days.of(sports.listed, Side.Recent, friday, ZoneOffset.UTC)))
        assertEquals(1, Days.of(sports.listed, Side.Recent, friday, ZoneOffset.UTC).single().leagues.single().rows.single().at.id)
        assertEquals(emptyList(), ids(Days.of(sports.listed, Side.Recent, at("2026-11-19T08:00"), ZoneOffset.UTC)))
    }

    @Test
    fun `five a league a day, then the rest, a weekend and a card counting one each`() {
        val saturday = (0 until 8).map { game(10 + it, "2026-11-21T15:00") }
        val sports = sports(
            PREMIER to saturday,
            UFC to listOf(ufc999()),
            FORMULA_1 to kestrelWeekend().take(4),
        )
        val day = Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC).single()
        assertEquals(SAT, day.date)
        val premier = day.leagues.single { it.league.id == PREMIER.id }
        assertEquals((10..14).map { "ev-0000$it" }, premier.first.map { it.listed.key })
        assertEquals((15..17).map { "ev-0000$it" }, premier.rest.map { it.listed.key })
        assertEquals("+3 more", Words.more(premier.rest.size))
        assertEquals(listOf(1, 1), day.leagues.filter { it.league.id != PREMIER.id }.map { it.rows.size })
        assertTrue(day.leagues.filter { it.league.id != PREMIER.id }.all { it.rest.isEmpty() })
    }

    @Test
    fun `leagues in a day follow the order asked, by name where none is`() {
        val sports = sports(PREMIER to listOf(game(1, "2026-11-21T15:00")), UFC to listOf(ufc999()), FORMULA_1 to kestrelWeekend().take(4))
        val byName = Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC).single().leagues.map { it.league.name }
        assertEquals(listOf("Formula 1", "Premier League", "UFC"), byName)
        val asked = Days.of(sports.listed, Side.Recent, SUNDAY, ZoneOffset.UTC, compareByDescending { it.name }).single().leagues.map { it.league.name }
        assertEquals(listOf("UFC", "Premier League", "Formula 1"), asked)
    }

    private fun game(id: Int, time: String, league: KeptLeague = PREMIER) = on(league, id, at(time), "Wrenford Albion vs Brackenmoor FC")

    /** The Grand Prix of Tarnholm the next weekend: practice on Friday the 27th. */
    private fun tarnholm() = listOf(
        on(FORMULA_1, 21, at("2026-11-27T12:30"), "Grand Prix of Tarnholm - Practice 1", round = "21"),
        on(FORMULA_1, 22, at("2026-11-29T15:00"), "Grand Prix of Tarnholm - Race", round = "21"),
    )

    private companion object {
        val SUNDAY: Instant = at("2026-11-22T20:41")
        val FRI: LocalDate = LocalDate.of(2026, 11, 20)
        val SAT: LocalDate = LocalDate.of(2026, 11, 21)
        val SUN: LocalDate = LocalDate.of(2026, 11, 22)
        val MON: LocalDate = LocalDate.of(2026, 11, 23)
    }
}

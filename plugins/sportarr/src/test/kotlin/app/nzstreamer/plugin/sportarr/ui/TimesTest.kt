package app.nzstreamer.plugin.sportarr.ui

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tab's dates, times and lengths (L652), in the device's zone with English names: each format as the designs write it,
 * at a day's edges in two zones (London, and Los Angeles eight hours behind it in November), and across a new year. The
 * designs' day is Sunday 22 November 2026.
 */
class TimesTest {
    @Test
    fun `a time is the zone's hour and minute on a 24-hour clock`() {
        assertEquals("15:00", Times.time(at("2026-11-21T15:00"), LONDON))
        assertEquals("07:00", Times.time(at("2026-11-21T15:00"), LOS_ANGELES))
        assertEquals("00:00", Times.time(at("2026-11-22T00:00"), LONDON))
        assertEquals("16:00", Times.time(at("2026-11-22T00:00"), LOS_ANGELES))
        assertEquals("23:59", Times.time(at("2026-11-22T07:59"), LOS_ANGELES))
        assertEquals("20:41", Times.time(at("2026-11-22T20:41"), LONDON))
        // Summer time begins at 01:00 UTC on the last Sunday of March in London.
        assertEquals("00:30", Times.time(at("2026-03-29T00:30"), LONDON))
        assertEquals("02:30", Times.time(at("2026-03-29T01:30"), LONDON))
    }

    @Test
    fun `day headers say Today, Yesterday and Tomorrow, else the day's name and date`() {
        assertEquals("Today", header("2026-11-22T14:00", NOON, LONDON))
        assertEquals("Yesterday", header("2026-11-21T15:00", NOON, LONDON))
        assertEquals("Tomorrow", header("2026-11-23T20:00", NOON, LONDON))
        assertEquals("Fri 20 Nov", header("2026-11-20T12:30", NOON, LONDON))
        assertEquals("Sun 15 Nov", header("2026-11-15T13:00", NOON, LONDON))
        assertEquals("Fri 27 Nov", header("2026-11-27T12:30", NOON, LONDON))
        assertEquals("Wed 23 Sep", header("2026-09-23T12:00", NOON, LONDON))
    }

    @Test
    fun `a day header changes at the zone's midnight, not at UTC's`() {
        // Midnight in London is four in the afternoon of the day before in Los Angeles, where it is still Saturday.
        assertEquals("Today", header("2026-11-22T00:00", NOON, LONDON))
        assertEquals("Yesterday", header("2026-11-21T23:59", NOON, LONDON))
        assertEquals("Yesterday", header("2026-11-22T00:00", NOON, LOS_ANGELES))
        assertEquals("Yesterday", header("2026-11-22T07:59", NOON, LOS_ANGELES))
        assertEquals("Today", header("2026-11-22T08:00", NOON, LOS_ANGELES))
        // A US evening game, after midnight in London: listed under its own evening there, the morning after here.
        assertEquals("Today", header("2026-11-22T01:30", NOON, LONDON))
        assertEquals("Yesterday", header("2026-11-22T01:30", NOON, LOS_ANGELES))
        // Today itself moves with the zone: early on Sunday in London it is still Saturday in Los Angeles.
        val early = at("2026-11-22T06:00")
        assertEquals("Today", header("2026-11-21T20:00", early, LOS_ANGELES))
        assertEquals("Tomorrow", header("2026-11-22T20:00", early, LOS_ANGELES))
        assertEquals("Yesterday", header("2026-11-21T20:00", early, LONDON))
    }

    @Test
    fun `a day in another year carries its year, across a new year as a year back`() {
        val newYearsEve = at("2026-12-31T12:00")
        assertEquals("Tomorrow", header("2027-01-01T15:00", newYearsEve, LONDON))
        assertEquals("Sat 2 Jan 2027", header("2027-01-02T15:00", newYearsEve, LONDON))
        assertEquals("Yesterday", header("2026-12-30T15:00", newYearsEve, LONDON))
        assertEquals("Tue 29 Dec", header("2026-12-29T15:00", newYearsEve, LONDON))
        val newYearsDay = at("2027-01-01T12:00")
        assertEquals("Yesterday", header("2026-12-31T15:00", newYearsDay, LONDON))
        assertEquals("Wed 30 Dec 2026", header("2026-12-30T15:00", newYearsDay, LONDON))
        assertEquals("Thu 20 Nov 2025", Times.day(LocalDate.of(2025, 11, 20), SUNDAY))
        // Noon on New Year's Day in London is four in the morning in Los Angeles: the same day there.
        assertEquals("Yesterday", header("2026-12-31T15:00", newYearsDay, LOS_ANGELES))
        // At one in the morning on New Year's Day in London it is still the old year in Los Angeles.
        val justAfter = at("2027-01-01T01:00")
        assertEquals("Today", header("2026-12-31T20:00", justAfter, LOS_ANGELES))
        assertEquals("Tomorrow", header("2027-01-01T20:00", justAfter, LOS_ANGELES))
        assertEquals("Sat 2 Jan 2027", header("2027-01-02T20:00", justAfter, LOS_ANGELES))
    }

    @Test
    fun `a short day is its date and month, with the year in another year`() {
        assertEquals("23 Sep", Times.shortDay(LocalDate.of(2026, 9, 23), SUNDAY))
        assertEquals("5 Dec", Times.shortDay(LocalDate.of(2026, 12, 5), SUNDAY))
        assertEquals("23 Sep 2025", Times.shortDay(LocalDate.of(2025, 9, 23), SUNDAY))
        val months = (1..12).map { Times.shortDay(LocalDate.of(2026, it, 1), SUNDAY) }
        assertEquals(listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").map { "1 $it" }, months)
        val days = (16..22).map { Times.day(LocalDate.of(2026, 11, it), SUNDAY).substringBefore(' ') }
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), days)
    }

    @Test
    fun `a page's meta is its league, day and time in the zone`() {
        assertEquals("Premier League · Sat 21 Nov · 15:00", Times.meta("Premier League", at("2026-11-21T15:00"), NOON, LONDON))
        assertEquals("Premier League · Sat 21 Nov · 07:00", Times.meta("Premier League", at("2026-11-21T15:00"), NOON, LOS_ANGELES))
        assertEquals("UFC · Sat 21 Nov · 23:00", Times.meta("UFC", at("2026-11-21T23:00"), NOON, LONDON))
        // An evening in Los Angeles is the next morning in London.
        assertEquals("NBA · Sun 22 Nov · 01:30", Times.meta("NBA", at("2026-11-22T01:30"), NOON, LONDON))
        assertEquals("NBA · Sat 21 Nov · 17:30", Times.meta("NBA", at("2026-11-22T01:30"), NOON, LOS_ANGELES))
        assertEquals("Premier League · Sat 2 Jan 2027 · 15:00", Times.meta("Premier League", at("2027-01-02T15:00"), at("2026-12-31T12:00"), LONDON))
        assertEquals("Fri 20 Nov · 12:30", Times.dayAndTime(at("2026-11-20T12:30"), NOON, LONDON))
    }

    @Test
    fun `a weekend's meta is its league, its round and its days`() {
        val first = at("2026-11-20T12:30")
        val race = at("2026-11-22T15:00")
        assertEquals("Formula 1 · Round 20 · 20–22 Nov", Times.weekendMeta("Formula 1", "20", 20, first, race, NOON, LONDON))
        assertEquals("Formula 1 · Round 20 · 20–22 Nov", Times.weekendMeta("Formula 1", "Round 20", 20, first, race, NOON, LONDON))
        // A round without one number is its text as Sportarr gives it; without a round, none.
        assertEquals("Formula 1 · Final · 20–22 Nov", Times.weekendMeta("Formula 1", " Final ", null, first, race, NOON, LONDON))
        assertEquals("Formula 1 · 20–22 Nov", Times.weekendMeta("Formula 1", null, null, first, race, NOON, LONDON))
        assertEquals("Formula 1 · 20–22 Nov", Times.weekendMeta("Formula 1", "  ", null, first, race, NOON, LONDON))
        // Its first session, early on Friday in London, is Thursday evening in Los Angeles.
        assertEquals(
            "MotoGP · Round 19 · 19–22 Nov",
            Times.weekendMeta("MotoGP", "19", 19, at("2026-11-20T06:00"), race, NOON, LOS_ANGELES),
        )
        assertEquals("MotoGP · Round 19 · 20–22 Nov", Times.weekendMeta("MotoGP", "19", 19, at("2026-11-20T06:00"), race, NOON, LONDON))
    }

    @Test
    fun `a weekend's days span a month, a year, or one day`() {
        assertEquals("22 Nov", Times.span(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22), SUNDAY))
        assertEquals("20–22 Nov", Times.span(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22), SUNDAY))
        assertEquals("30 Oct–1 Nov", Times.span(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1), SUNDAY))
        assertEquals("20–22 Nov 2025", Times.span(LocalDate.of(2025, 11, 20), LocalDate.of(2025, 11, 22), SUNDAY))
        assertEquals("31 Dec 2026–2 Jan 2027", Times.span(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 2), LocalDate.of(2026, 12, 31)))
        assertEquals("22 Nov 2025", Times.span(LocalDate.of(2025, 11, 22), LocalDate.of(2025, 11, 22), SUNDAY))
    }

    @Test
    fun `Starts says today, a day's name within the week, else the date`() {
        assertEquals("Starts today 15:00", Times.starts(at("2026-11-22T15:00"), NOON, LONDON))
        assertEquals("Starts Mon 15:00", Times.starts(at("2026-11-23T15:00"), NOON, LONDON))
        assertEquals("Starts Sat 15:00", Times.starts(at("2026-11-28T15:00"), NOON, LONDON))
        assertEquals("Starts 29 Nov 15:00", Times.starts(at("2026-11-29T15:00"), NOON, LONDON))
        assertEquals("Starts 5 Dec 15:00", Times.starts(at("2026-12-05T15:00"), NOON, LONDON))
        assertEquals("Starts Sat 19:00", Times.starts(at("2026-11-28T19:00"), NOON, LONDON))
    }

    @Test
    fun `Starts counts days in the zone, and across a new year`() {
        // At seven in the morning in London it is eleven at night on Saturday in Los Angeles.
        val early = at("2026-11-22T07:00")
        assertEquals("Starts today 09:00", Times.starts(at("2026-11-22T09:00"), early, LONDON))
        assertEquals("Starts Sun 01:00", Times.starts(at("2026-11-22T09:00"), early, LOS_ANGELES))
        assertEquals("Starts today 23:30", Times.starts(at("2026-11-22T07:30"), early, LOS_ANGELES))
        val newYearsEve = at("2026-12-31T12:00")
        assertEquals("Starts Sat 15:00", Times.starts(at("2027-01-02T15:00"), newYearsEve, LONDON))
        assertEquals("Starts 8 Jan 2027 15:00", Times.starts(at("2027-01-08T15:00"), newYearsEve, LONDON))
    }

    @Test
    fun `what is left reads as on episode rows, in Up next as on Home, on a page as on the Movie page`() {
        assertEquals("1:04:38 left", Times.rowLeft(positionMs = 1_000_000, durationMs = 1_000_000 + ms(1, 4, 38)))
        assertEquals("32:10 left", Times.rowLeft(positionMs = ms(0, 40, 0), durationMs = ms(1, 12, 10)))
        assertEquals("0:42 left", Times.rowLeft(positionMs = ms(0, 0, 18), durationMs = ms(0, 1, 0)))
        assertEquals("0:00 left", Times.rowLeft(positionMs = ms(0, 2, 0), durationMs = ms(0, 1, 0)))

        assertEquals("1 h 5 min left", Times.upNextLeft(positionMs = 0, durationMs = ms(1, 5, 0)))
        assertEquals("1 h 12 min left", Times.upNextLeft(positionMs = ms(0, 30, 0), durationMs = ms(1, 42, 0)))
        // A minute begun counts whole, as Home's rows count it.
        assertEquals("1 h 5 min left", Times.upNextLeft(positionMs = 0, durationMs = ms(1, 4, 1)))
        assertEquals("1 min left", Times.upNextLeft(positionMs = 0, durationMs = ms(0, 0, 30)))
        assertEquals("1 h 0 min left", Times.upNextLeft(positionMs = 0, durationMs = ms(1, 0, 0)))
        assertNull(Times.upNextLeft(positionMs = ms(0, 5, 0), durationMs = 0))

        assertEquals("42 min left", Times.pageLeft(positionMs = ms(1, 8, 0), durationMs = ms(1, 50, 0)))
        assertEquals("1 h left", Times.pageLeft(positionMs = 0, durationMs = ms(1, 0, 0)))
        assertEquals("1 h 5 min left", Times.pageLeft(positionMs = 0, durationMs = ms(1, 5, 0)))
        assertEquals("0 min left", Times.pageLeft(positionMs = ms(2, 0, 0), durationMs = ms(1, 0, 0)))
        assertNull(Times.pageLeft(positionMs = 0, durationMs = 0))
    }

    @Test
    fun `a top button's position is where the part stopped`() {
        assertEquals("Resume Prelims · 37:45", Times.resume("Prelims", ms(0, 37, 45)))
        assertEquals("Resume Qualifying · 18:22", Times.resume("Qualifying", ms(0, 18, 22)))
        assertEquals("Resume Race · 1:02:03", Times.resume("Race", ms(1, 2, 3)))
        assertEquals("0:00", Times.clock(-5_000))
        assertEquals("9:59", Times.clock(ms(0, 9, 59) + 999))
    }

    @Test
    fun `the list's age reads as Discover's`() {
        val now = at("2026-11-22T20:41")
        assertEquals("3 h ago", Times.ago(at("2026-11-22T17:41"), now))
        assertEquals("3 h ago", Times.ago(at("2026-11-22T17:00"), now))
        assertEquals("59 min ago", Times.ago(at("2026-11-22T19:42"), now))
        assertEquals("1 min ago", Times.ago(at("2026-11-22T20:41"), now))
        assertEquals("yesterday", Times.ago(at("2026-11-21T19:00"), now))
        assertEquals("2 days ago", Times.ago(at("2026-11-20T19:00"), now))
        // Read in the future, by a clock that ran ahead then: a minute old.
        assertEquals("1 min ago", Times.ago(at("2026-11-22T22:00"), now))
        assertEquals("Can't reach Sportarr · list from 3 h ago", Words.ListStates.cantReach(Times.ago(at("2026-11-22T17:41"), now)))
    }

    @Test
    fun `the empty Recent line names the first day Recent reaches`() {
        val since = Times.shortDay(SUNDAY.minusDays(60), SUNDAY)
        assertEquals(
            "Your Sportarr's leagues have nothing between 23 Sep and today. Upcoming has 6 events.",
            Words.ListStates.nothingRecent(since, 6),
        )
    }

    private fun header(event: String, now: Instant, zone: ZoneId): String = header(at(event), now, zone)

    private fun header(event: Instant, now: Instant, zone: ZoneId): String = Times.dayHeader(Times.date(event, zone), Times.date(now, zone))

    private fun ms(hours: Long, minutes: Long, seconds: Long): Long = ((hours * 60 + minutes) * 60 + seconds) * 1000

    private companion object {
        val LONDON: ZoneId = ZoneId.of("Europe/London")
        val LOS_ANGELES: ZoneId = ZoneId.of("America/Los_Angeles")
        val SUNDAY: LocalDate = LocalDate.of(2026, 11, 22)

        /** Noon on the designs' Sunday, in London. */
        val NOON: Instant = at("2026-11-22T12:00")

        fun at(utc: String): Instant = Instant.parse("$utc:00Z")
    }
}

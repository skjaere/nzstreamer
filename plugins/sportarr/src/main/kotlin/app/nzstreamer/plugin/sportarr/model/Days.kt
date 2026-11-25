package app.nzstreamer.plugin.sportarr.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tab's days (L641, L644), in the device's zone. Two sides: Recent, what has started, back 60 days, newest day first;
 * and Upcoming, what is still to start, 31 days ahead, soonest first. Today heads both, with what started today and what
 * comes later today.
 *
 * Each row is dated by one of its events ([DayRow.at]): an event by itself; a weekend by its last session started on
 * Recent and its next on Upcoming, and where it has none of those, by its other one when that is today's. In a day each
 * league's rows go in time order, five shown and the rest behind "+N more".
 */
object Days {
    enum class Side { Recent, Upcoming }

    /** A day of a side: its [date], whether it is [today], its leagues' rows. */
    data class Day(val date: LocalDate, val today: Boolean, val leagues: List<LeagueDay>)

    /** One league's rows on one day, in time order: the [first] five, then the [rest]. */
    data class LeagueDay(val league: League, val rows: List<DayRow>) {
        val first: List<DayRow> get() = rows.take(SHOWN)
        val rest: List<DayRow> get() = rows.drop(SHOWN)
    }

    /** A row: an event or weekend ([listed]), and the event it is dated by ([at]; for a weekend, one of its sessions). */
    data class DayRow(val listed: Listed, val at: SportsEvent)

    /** Rows shown a league a day before the rest. */
    const val SHOWN = 5

    /** The local days back Recent reaches, and ahead Upcoming does: the list's window (L641). */
    const val DAYS_BACK = 60L
    const val DAYS_AHEAD = 31L

    /**
     * [side]'s days of [rows] at [now] in [zone], each day's leagues in [leagueOrder] (the list's order, L652, is the list's
     * to give; by name where none is given).
     */
    fun of(
        rows: List<Listed>,
        side: Side,
        now: Instant,
        zone: ZoneId,
        leagueOrder: Comparator<League> = compareBy<League> { it.name }.thenBy { it.id },
    ): List<Day> {
        val today = now.atZone(zone).toLocalDate()
        val range = when (side) {
            Side.Recent -> today.minusDays(DAYS_BACK)..today
            Side.Upcoming -> today..today.plusDays(DAYS_AHEAD)
        }
        val dated = rows.mapNotNull { row ->
            val at = at(row, side, now, today, zone) ?: return@mapNotNull null
            DayRow(row, at).takeIf { date(at, zone) in range }
        }
        val days = dated.groupBy { date(it.at, zone) }.map { (date, inDay) ->
            val leagues = inDay.groupBy { it.listed.league }.map { (league, own) ->
                LeagueDay(league, own.sortedWith(compareBy<DayRow> { it.at.start }.thenBy { it.listed.key }))
            }
            Day(date, date == today, leagues.sortedWith(compareBy(leagueOrder) { it.league }))
        }
        return when (side) {
            Side.Recent -> days.sortedByDescending { it.date }
            Side.Upcoming -> days.sortedBy { it.date }
        }
    }

    /** The event [row] is dated by on [side], or null where it isn't on that side. */
    private fun at(row: Listed, side: Side, now: Instant, today: LocalDate, zone: ZoneId): SportsEvent? {
        val lastStarted = row.events.lastOrNull { !it.start.isAfter(now) }
        val next = row.events.firstOrNull { it.start.isAfter(now) }
        return when (side) {
            Side.Recent -> lastStarted ?: next?.takeIf { date(it, zone) == today }
            Side.Upcoming -> next ?: lastStarted?.takeIf { date(it, zone) == today }
        }
    }

    private fun date(event: SportsEvent, zone: ZoneId): LocalDate = event.start.atZone(zone).toLocalDate()
}

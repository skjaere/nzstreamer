package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.ui.Words.Dates
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Sports tab's dates, times and lengths (L652), in the device's zone, with English day and month names as the plugin's
 * designs write them whatever the device's language, as the rest of NZStreamer does: the formats are here, their words
 * [Words.Dates]'. Each is given the moment, the time now and the zone, and reads no clock of its own.
 *
 * Where a design draws one, its words are its: day headers (SpPhoneList), a page's meta, its Starts line and what is left
 * (SpPhoneGame), a weekend's meta and its top button's position (SpPhoneWeekend), Up next's time left (SpPhoneList 1), the
 * list's age (SpPhoneListStates 2). Undrawn: the year a date in another year carries, and a weekend's days across a month
 * or a year.
 */
internal object Times {
    /** The day [at] falls on in [zone]: the day an event is listed under, and the one its rows and pages say. */
    fun date(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    /** "15:00": [at]'s hour and minute in [zone], on a 24-hour clock. */
    fun time(at: Instant, zone: ZoneId): String = at.atZone(zone).let { "${two(it.hour)}:${two(it.minute)}" }

    /** A day's header in the list: "Today", "Yesterday", "Tomorrow", else [day]'s "Fri 20 Nov". */
    fun dayHeader(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> Dates.TODAY
        -1L -> Dates.YESTERDAY
        1L -> Dates.TOMORROW
        else -> day(date, today)
    }

    /** "Fri 20 Nov"; in another year than [today]'s, with the year after it: "Thu 20 Nov 2025". */
    fun day(date: LocalDate, today: LocalDate): String = "${Dates.DAY_NAMES[date.dayOfWeek.ordinal]} ${shortDay(date, today)}"

    /** "23 Sep"; in another year than [today]'s, "23 Sep 2025". */
    fun shortDay(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) dayMonth(date) else "${dayMonth(date)} ${date.year}"

    /** A day before [today] in a line: "yesterday", else its date, "on Sat 21 Nov". */
    fun pastDay(date: LocalDate, today: LocalDate): String =
        if (date == today.minusDays(1)) Dates.YESTERDAY_AGO else "${Dates.ON} ${day(date, today)}"

    /** When an event or session starts or started: "Fri 20 Nov · 12:30". */
    fun dayAndTime(at: Instant, now: Instant, zone: ZoneId): String = Words.line(day(date(at, zone), date(now, zone)), time(at, zone))

    /** A page's meta line, its league and when it starts: "Premier League · Sat 21 Nov · 15:00". */
    fun meta(league: String, start: Instant, now: Instant, zone: ZoneId): String = Words.line(league, dayAndTime(start, now, zone))

    /**
     * A weekend's meta line: its league, its round, and the days from its [first] session's start to its [last]'s: "Formula 1
     * · Round 20 · 20–22 Nov". The round is "Round <number>" where it has a number ([roundNumber], L644), else its text as
     * Sportarr gives it ("Formula 1 · Final · 20–22 Nov"), else left out.
     */
    fun weekendMeta(league: String, round: String?, roundNumber: Int?, first: Instant, last: Instant, now: Instant, zone: ZoneId): String {
        val roundWords = roundNumber?.let(Words.SportsList::round) ?: round?.trim()?.takeIf { it.isNotEmpty() }
        return Words.line(league, roundWords, span(date(first, zone), date(last, zone), date(now, zone)))
    }

    /**
     * The days from [first] to [last]: "22 Nov" for one, "20–22 Nov", "30 Oct–1 Nov". In another year than [today]'s the year
     * follows the last ("20–22 Nov 2025"); across a new year, each its own ("31 Dec 2026–2 Jan 2027").
     */
    fun span(first: LocalDate, last: LocalDate, today: LocalDate): String = when {
        first == last -> shortDay(last, today)
        first.year != last.year -> "${dayMonth(first)} ${first.year}–${dayMonth(last)} ${last.year}"
        first.month == last.month -> "${first.dayOfMonth}–${shortDay(last, today)}"
        else -> "${dayMonth(first)}–${shortDay(last, today)}"
    }

    /**
     * When an event not started yet starts: "Starts today 15:00"; on one of the six days after today, its day's name, "Starts
     * Sat 15:00"; else its date, "Starts 5 Dec 15:00".
     */
    fun starts(start: Instant, now: Instant, zone: ZoneId): String {
        val day = date(start, zone)
        val today = date(now, zone)
        val on = when (ChronoUnit.DAYS.between(today, day)) {
            0L -> Dates.STARTS_TODAY
            in 1L..WEEK_AHEAD -> Dates.DAY_NAMES[day.dayOfWeek.ordinal]
            else -> shortDay(day, today)
        }
        return "${Dates.STARTS} $on ${time(start, zone)}"
    }

    /** What is left of a play on a row, as a show's episode rows say it: "1:04:38 left", "32:10 left". */
    fun rowLeft(positionMs: Long, durationMs: Long): String = "${clock(durationMs - positionMs)} ${Dates.LEFT}"

    /**
     * What is left of a play in Up next, as Home's rows say it, a minute begun counted whole: "1 h 5 min left", "42 min
     * left"; null where the length isn't known.
     */
    fun upNextLeft(positionMs: Long, durationMs: Long): String? {
        val minutes = minutesLeft(positionMs, durationMs) ?: return null
        return if (minutes >= 60) "${minutes / 60} ${Dates.HOURS} ${minutes % 60} ${Dates.MINUTES} ${Dates.LEFT}" else "$minutes ${Dates.MINUTES} ${Dates.LEFT}"
    }

    /**
     * What is left of a play on a page's status line, as the Movie page's: "42 min left", "1 h 5 min left", "1 h left"; null
     * where the length isn't known.
     */
    fun pageLeft(positionMs: Long, durationMs: Long): String? {
        val minutes = minutesLeft(positionMs, durationMs) ?: return null
        val hours = (minutes / 60).takeIf { it > 0 }?.let { "$it ${Dates.HOURS}" }
        val rest = (minutes % 60).takeIf { it > 0 || hours == null }?.let { "$it ${Dates.MINUTES}" }
        return "${listOfNotNull(hours, rest).joinToString(" ")} ${Dates.LEFT}"
    }

    /** A top button that goes on with a part or session, where it stopped: "Resume Prelims · 37:45". */
    fun resume(label: String, positionMs: Long): String = Words.line("${Words.Game.RESUME} $label", clock(positionMs))

    /** A position or a length: "37:45", "1:04:38"; nothing below none. */
    fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) "${seconds / 3600}:${two(seconds / 60 % 60)}:${two(seconds % 60)}" else "${seconds / 60}:${two(seconds % 60)}"
    }

    /**
     * How old the kept list is, as Discover says its lists' age: "12 min ago", "3 h ago", "yesterday", "4 days ago". A list
     * read in the future, by a clock that ran ahead then, is a minute old.
     */
    fun ago(since: Instant, now: Instant): String {
        val age = Duration.between(since, now)
        return when {
            age.toMinutes() < 60 -> "${age.toMinutes().coerceAtLeast(1)} ${Dates.MINUTES} ${Dates.AGO}"
            age.toHours() < 24 -> "${age.toHours()} ${Dates.HOURS} ${Dates.AGO}"
            age.toDays() == 1L -> Dates.YESTERDAY_AGO
            else -> "${age.toDays()} ${Dates.DAYS_AGO}"
        }
    }

    private fun minutesLeft(positionMs: Long, durationMs: Long): Long? =
        if (durationMs <= 0) null else ((durationMs - positionMs).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS

    private fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} ${Dates.MONTH_NAMES[date.monthValue - 1]}"

    private fun two(number: Number): String = number.toString().padStart(2, '0')

    /** The days after today a start is said by its day's name. */
    private const val WEEK_AHEAD = 6L
    private const val MINUTE_MS = 60_000L
}

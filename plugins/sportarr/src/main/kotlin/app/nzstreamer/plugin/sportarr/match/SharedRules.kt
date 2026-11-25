package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.ui.Words
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The rules every family shares (L647), read before its own and whatever it would say, in this order, the first that
 * holds naming the kind a name is left out as:
 * 1. a score or a result word (W167 a: such a name is never offered, so it never reaches Choose release);
 * 2. a non-event word: highlights, recaps and condensed games; a race's own shows; every other programme;
 * 3. a half, quarter, period or innings alone, or one numbered part of a recording ("Part 1"); a pack;
 * 4. posted more than a day before the event started (a release with no time passes);
 * 5. a date that isn't the event's.
 *
 * Then the family's own rules, and last the size floor, which makes an accepted release that is small for the part's
 * runtime a possible match at most.
 */
object SharedRules {
    /** [name] for [part] of [event], read with [matcher]'s words and judged by the shared rules, then by [matcher]. */
    fun judge(matcher: FamilyMatcher, event: SportsEvent, part: Part, name: String, posted: Instant?, size: Long?): Verdict {
        val read = SportsName.read(name, matcher.context(event))
        rejected(read, event, posted, matcher.related(event))?.let { return it }
        return floor(matcher.judge(event, part, read, posted, size), read, part, size, Nouns.item(event, part))
    }

    /** Rules 1 to 5 for [name], [related] being the listed events its date could name as well; null when it passes them. */
    fun rejected(name: SportsName, event: SportsEvent, posted: Instant?, related: List<SportsEvent>): Verdict.Reject? {
        val kind = when {
            // The parser's guard caught a name it couldn't read: nothing in it can be told, so it is no one's.
            !name.readable -> RejectKind.OtherEvents
            name.score -> RejectKind.Score
            name.results.isNotEmpty() -> RejectKind.Result
            name.nonEvent.isNotEmpty() -> nonEvent(name.nonEvent, event)
            name.partial.isNotEmpty() || name.numbered(NumberWord.Part).isNotEmpty() -> RejectKind.Partial
            name.packs.isNotEmpty() -> RejectKind.Packs
            posted != null && posted < event.start.minus(EARLY) -> RejectKind.Older
            !datesMatch(name, event, related) -> RejectKind.OtherEvents
            else -> return null
        }
        return Verdict.Reject(kind)
    }

    /**
     * Rule 5: each date [name] holds is [event]'s, and no other listed event's of [related]. A broadcast date unlike the
     * UTC date is exact: the name's date is one of the two. One equal to it, or none, may be Sportarr's own fallback
     * (it stores the UTC date where its data has no broadcast date, and says nothing of it), so the date is unknown:
     * within a day of the UTC date. The broadcast date is read as written, never moved into a zone.
     *
     * A day is another related event's where it is that event's [localDay] and not this one's, or that event's UTC date
     * and this one's by neither: so two evening games of the same sides a day apart each keep their own local date, and a
     * double header's games share theirs. A day-first date that reads two ways matches when one reading does and neither
     * is another event's.
     */
    fun datesMatch(name: SportsName, event: SportsEvent, related: List<SportsEvent>): Boolean {
        if (name.dates.isEmpty()) return true
        val utc = utcDate(event)
        val day = localDay(event)
        val exact = event.broadcastDate != null && event.broadcastDate != utc
        val others = related.filter { it.key != event.key }
        fun matches(date: LocalDate) = if (exact) date == day || date == utc else abs(utc.toEpochDay() - date.toEpochDay()) <= 1
        fun theirs(date: LocalDate) = date != day && others.any { other -> date == localDay(other) || (date == utcDate(other) && date != utc) }
        return name.dates.all { date -> date.readings.any(::matches) && date.readings.none(::theirs) }
    }

    /**
     * The day [event]'s release names most likely carry: its broadcast date where that is exact; else, the date being
     * unknown, its UTC date, or the day before for a start before four in the morning UTC, an evening in the Americas,
     * which names write by its local date. Two listed events of the same sides are on one day, a double header, when
     * theirs is one.
     */
    fun localDay(event: SportsEvent): LocalDate {
        val utc = utcDate(event)
        if (event.broadcastDate != null && event.broadcastDate != utc) return event.broadcastDate
        return if (event.start.atOffset(ZoneOffset.UTC).hour < SMALL_HOURS_END) utc.minusDays(1) else utc
    }

    /**
     * The size floor (L647): an accepted release smaller than 1 Mbit/s over [part]'s runtime (0.5 for a name saying 480p,
     * 576p or SD) is offered at most, "Small for a whole <[noun]>". No runtime, or no size (an indexer that gives 0), no
     * floor. Any other verdict stays as it was.
     */
    fun floor(verdict: Verdict, name: SportsName, part: Part, size: Long?, noun: String): Verdict {
        if (verdict !is Verdict.Accept) return verdict
        val runtime = part.runtimeMinutes ?: return verdict
        if (size == null || size <= 0) return verdict
        val bitsPerSecond = if (name.standardDefinition) SD_BITS_PER_SECOND else BITS_PER_SECOND
        return if (size * 8 < bitsPerSecond * runtime * 60) Verdict.Offer(Words.small(noun)) else verdict
    }

    /** A non-event word's kind: highlights before any other, a race's own show only at a motorsport event. */
    private fun nonEvent(words: List<NonEvent>, event: SportsEvent): RejectKind = when {
        words.any { it.kind == NonEventKind.Cut } -> RejectKind.Highlights
        event.family == Family.Motorsport && words.any { it.kind == NonEventKind.RaceShow } -> RejectKind.RaceShow
        else -> RejectKind.Shows
    }

    private fun utcDate(event: SportsEvent): LocalDate = event.start.atOffset(ZoneOffset.UTC).toLocalDate()

    /**
     * Where UTC's small hours end: an evening in the Americas, a late start on the west coast included, begins before four
     * in the morning UTC, a day after its local date. A start from four on keeps its UTC date, as an afternoon in East Asia
     * does, so two such games of the same sides on consecutive days stay apart. An afternoon in Australia or New Zealand
     * that begins before four is read as the evening before.
     */
    private const val SMALL_HOURS_END = 4

    /** How early a release may be posted: a day before the start covers every time zone's evening. */
    private val EARLY: Duration = Duration.ofDays(1)

    private const val BITS_PER_SECOND = 1_000_000L
    private const val SD_BITS_PER_SECOND = 500_000L
}

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
 * 3. a half, quarter, period or innings alone; a pack;
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
            name.partial.isNotEmpty() -> RejectKind.Partial
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
     * within a day of the UTC date. The broadcast date is read as written, never moved into a zone. A day that is
     * another related event's own (and not this one's too, as a double header's) is that event's. A day-first date that
     * reads two ways matches when one reading does and neither is another event's.
     */
    fun datesMatch(name: SportsName, event: SportsEvent, related: List<SportsEvent>): Boolean {
        if (name.dates.isEmpty()) return true
        val utc = utcDate(event)
        val own = ownDates(event)
        val exact = event.broadcastDate != null && event.broadcastDate != utc
        val others = related.filter { it.key != event.key }.flatMap(::ownDates).toSet() - own
        fun matches(date: LocalDate) = if (exact) date in own else abs(utc.toEpochDay() - date.toEpochDay()) <= 1
        return name.dates.all { date -> date.readings.any(::matches) && date.readings.none { it in others } }
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

    /** The days an event is on: its UTC date, and its broadcast date as written. */
    private fun ownDates(event: SportsEvent): Set<LocalDate> = setOfNotNull(utcDate(event), event.broadcastDate)

    /** How early a release may be posted: a day before the start covers every time zone's evening. */
    private val EARLY: Duration = Duration.ofDays(1)

    private const val BITS_PER_SECOND = 1_000_000L
    private const val SD_BITS_PER_SECOND = 500_000L
}

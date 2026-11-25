package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import java.time.Instant

/**
 * One family's matching (L647-L651): what it searches for a part of an event, and what it makes of a release name the
 * shared rules ([SharedRules], rules 1 to 5) passed. A family is built over the kept list, which its rules read for the
 * other listed events. The runner ([Runner]) searches and judges with it, the part picker ([PartPicker]) reads file names
 * with its words.
 */
interface FamilyMatcher {
    /**
     * The event's own words a release name is read against (L646): its title, its league's name, its sides and its
     * series' forms, the organisations a card's number follows, whether it is a show with a pre-show, and its years.
     */
    fun context(event: SportsEvent): NameContext

    /** [part]'s searches: two at most searched together, then one more only when they accepted nothing. */
    fun queries(event: SportsEvent, part: Part): Queries

    /**
     * The other listed events a date in a name could name as well (L647 rule 5): the same sides' other meetings, the same
     * weekly show's other nights, the same place's other rounds. A name dated one of their days is theirs. None by default.
     */
    fun related(event: SportsEvent): List<SportsEvent> = emptyList()

    /**
     * The family's identity and part rules (L647 rule 6) for [name], read against [context], which the shared rules
     * passed: its date, if it has one, is the event's. A name that fails the identity while it holds the date and the
     * league's word is offered, not left out. [posted] is when it was posted, null where the indexer doesn't say; [size]
     * its size in bytes. The size floor caps an Accept afterwards.
     */
    fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict
}

/**
 * A part's searches (L647): [first], two at most, searched together; [then], one more, searched only when the first
 * accepted nothing. Words alone, as a user would type them: no part, session or year where names glue it.
 */
class Queries(val first: List<String>, val then: String? = null)

package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.NameContext
import app.nzstreamer.plugin.sportarr.match.Queries
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SportsName
import app.nzstreamer.plugin.sportarr.match.Token
import app.nzstreamer.plugin.sportarr.match.Tokens
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.ui.Words
import java.time.Instant

/**
 * Every sport the plugin has no rules for yet, and every event stepped down from a family (L651): you pick. It never
 * accepts a release, so Play never plays one by itself; the names that look like the event's are possible matches, and a
 * pick plays through Choose release. A name is one when it holds the league's words and the event's date ("Picked by
 * league and date"), or seven in ten of the title's distinctive words with the year or the event's date, posted once the
 * event started ("Picked by the title's words"). The shared rules leave a score, a result word and the rest out before
 * it is asked. Every other name is another event's.
 */
object Generic : FamilyMatcher {
    override fun context(event: SportsEvent): NameContext =
        NameContext(title = event.title, league = event.league.name, series = event.league.alternateNames, years = eventYears(event))

    /**
     * Stage 1: the title's distinctive words, five at most ("Kestrel Mens Downhill"), and the league's name with the event's
     * date as three numbers ("FIS Alpine Ski World Cup 2026 11 21"); stage 2: the title's distinctive words and the year.
     */
    override fun queries(event: SportsEvent, part: Part): Queries {
        val words = titleWords(event).map { it.written }
        val year = (event.broadcastDate ?: utcDate(event)).year
        return Queries(
            listOf(words.take(TITLE_WORDS).joinToString(" "), "${event.league.name} ${searchDate(event)}"),
            words.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" ")} $year" },
        )
    }

    override fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict {
        val dated = name.dates.isNotEmpty()
        val league = (listOf(event.league.name) + event.league.alternateNames).any(name::has)
        if (dated && league) return Verdict.Offer(Words.BY_LEAGUE_AND_DATE)
        val present = name.identity.mapTo(HashSet()) { it.word }
        val title = titleWords(event).map { it.word }.distinct()
        val enough = title.isNotEmpty() && title.count { it in present } * 10 >= title.size * SHARE_IN_TENS
        val year = dated || name.yearsNamed().any { it in eventYears(event) }
        val started = posted == null || !posted.isBefore(event.start)
        return if (enough && year && started) Verdict.Offer(Words.BY_TITLE) else OTHER_EVENT
    }

    /** The title's distinctive words: all of them but articles and the "vs" between two sides. */
    private fun titleWords(event: SportsEvent): List<Token> = Tokens.of(event.title).filter { it.word !in NOT_DISTINCTIVE }

    private val OTHER_EVENT = Verdict.Reject(RejectKind.OtherEvents)

    private const val TITLE_WORDS = 5

    /** How much of the title a name must hold: seven words in ten. */
    private const val SHARE_IN_TENS = 7

    private val NOT_DISTINCTIVE = setOf("a", "an", "the", "vs", "v", "@")
}

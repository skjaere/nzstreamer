package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.FamilyFrom
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.IndexerNote
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * A part's releases (L647), found through the user's indexers ([search], the host's) and judged by its family, for Play
 * and Choose release alone: never as a page opens, on a timer or for a list.
 *
 * Stage 1 searches the family's first two queries together, stage 2 its one more only when they accepted nothing that
 * plays here (a torrent not cached, a codec with no decoder is no find): three searches at most, each with the part's
 * runtime, so the host ranks for it (its cache keys on the words, so another part's same words cost no new request).
 * Each search is read to its last answer. Every release is judged once (the same name, size and indexer is one
 * release), on [work], off the caller's thread; the accepted keep the host's order, each at its best place across the
 * answers, the first search first at the same place, and the possible matches follow that order.
 *
 * A cancelled run cancels its searches with it. Its log line says counts, kinds and how long it took, never a name: the
 * family, the sport it came from by Sportarr's category word (a word in no family only when it is short and plain) and
 * whose word it was (L-SF3), and each search's indexers in the host's order, by their counts or what kept them from one.
 */
class Runner(private val search: PluginSearch, private val work: CoroutineContext = Dispatchers.Default) {
    /**
     * [part] of [event]'s releases, judged by [matcher]: the accepted in order, the possible matches with their reasons,
     * one line counting what was left out by kind; and, when nothing accepted or offered plays here, each indexer's line
     * and the note for the no-release sheet.
     */
    suspend fun releases(matcher: FamilyMatcher, event: SportsEvent, part: Part): ItemReleases = run(matcher, event, part).releases

    /**
     * What one run found ([releases]), and whether any indexer [answered] it: one that failed, was skipped or paused by the
     * host's cap, or none switched on, says nothing of whether the part's releases are there yet.
     */
    class Run(val releases: ItemReleases, val answered: Boolean)

    /** [part] of [event]'s releases as [releases] gives them, with whether any indexer answered. */
    suspend fun run(matcher: FamilyMatcher, event: SportsEvent, part: Part): Run {
        val started = System.nanoTime()
        val queries = matcher.queries(event, part)
        val first = queries.first.map(String::trim).filter(String::isNotEmpty).distinct().take(FIRST_AT_ONCE)
        val then = queries.then?.trim()?.takeIf { it.isNotEmpty() && it !in first }
        val judged = Judged(matcher, event, part)
        val answers = coroutineScope { first.map { words -> async { last(words, part.runtimeMinutes) } }.awaitAll() }.toMutableList()
        answers.forEachIndexed { query, found -> judged.add(query, found.releases) }
        if (then != null && judged.none { it.verdict is Verdict.Accept && it.playable }) {
            answers += last(then, part.runtimeMinutes)
            judged.add(answers.lastIndex, answers.last().releases)
        }

        val ordered = judged.sortedWith(compareBy<Judging>({ it.place }, { it.query }))
        val accepted = ordered.filter { it.verdict is Verdict.Accept }.map { it.handle }
        val offered = ordered.mapNotNull { judging -> (judging.verdict as? Verdict.Offer)?.let { Offered(judging.handle, it.reason) } }
        val kinds = ordered.mapNotNull { (it.verdict as? Verdict.Reject)?.kind }.groupingBy { it }.eachCount()
        // As the host decides: with nothing here to play or to pick, it shows the no-release sheet, which needs the lines.
        val nothing = ordered.none { it.playable && it.verdict !is Verdict.Reject }
        val noun = Nouns.event(event)
        // Each search's indexers after a space of its own, so a run that searched nothing says "0 searches,".
        val searched = answers.joinToString("") { " " + indexers(it) }
        log.info(
            "Releases for a {} part ({}): {} searches{}, {} judged, {} accepted, {} offered, {} left out {} in {} ms",
            event.family, origin(event), answers.size, searched, ordered.size, accepted.size,
            offered.size, kinds.values.sum(), kinds, (System.nanoTime() - started) / 1_000_000,
        )
        val releases = ItemReleases(
            accepted = accepted,
            offered = offered,
            leftOut = LeftOut.line(kinds, noun),
            lines = if (nothing) lines(answers, Nouns.item(event, part)) else emptyList(),
            note = if (nothing) Words.NoRelease.note(noun) else null,
        )
        return Run(releases, answered = answers.any { found -> found.indexers.any { it.state == IndexerLine.State.Found } })
    }

    /**
     * Where [event]'s family came from, for the log line: the league's sport or the event's, the part names Sportarr sent,
     * or none, with the sport word folded (Sportarr's category word, never a title or a league's name), and whether the
     * title stepped it down. A word no family lists is whatever the user's Sportarr holds, which can be renamed to any
     * text: it is named only when it is a short plain word, else by its length alone.
     */
    private fun origin(event: SportsEvent): String {
        val word = Families.fold(event.sport)
        val plain = word.length <= PLAIN_SPORT && word.all { it.isLetter() || it == ' ' }
        val sport = when {
            word.isEmpty() -> null
            plain || Families.ofSport(word) != Family.Generic -> "sport \"$word\""
            else -> "a sport of ${word.length} characters"
        }
        val stepped = if (event.family == Family.Generic && event.familyFrom != FamilyFrom.Nothing) ", stepped down" else ""
        return when (event.familyFrom) {
            FamilyFrom.LeagueSport -> "the league's $sport$stepped"
            FamilyFrom.EventSport -> "the event's $sport$stepped"
            FamilyFrom.PartNames -> "part names, ${sport ?: "no sport"}$stepped"
            FamilyFrom.Nothing -> sport?.let { "$it in no family" } ?: "no sport"
        }
    }

    /**
     * One search's indexers in the host's order, as its last answer had them: each one's count, "empty", its failure in the
     * host's words, paused or skipped; never an indexer's name, which the user typed. An answer naming no indexer is "no
     * answer": none switched on, the host's own search failing before any answered and a flow ending with none look the
     * same here.
     */
    private fun indexers(found: Found): String {
        if (found.indexers.isEmpty()) return "[no answer]"
        return found.indexers.joinToString(", ", "[", "]") {
            when (it.state) {
                IndexerLine.State.Found -> if (it.found > 0) "${it.found}" else "empty"
                IndexerLine.State.Failed -> "failed (${it.text})"
                IndexerLine.State.Limited -> "paused"
                IndexerLine.State.Skipped -> "skipped"
                // Still searching when its search ended (the host's own failure), or a state a later NZStreamer adds.
                else -> it.state.name.lowercase(Locale.ROOT)
            }
        }
    }

    /** The search for [words], read to its last answer: what every indexer said once they all had. */
    private suspend fun last(words: String, runtimeMinutes: Int?): Found =
        search.search(words, runtimeMinutes).lastOrNull() ?: Found(emptyList(), emptyList())

    /**
     * One line per indexer, in the host's order, for a run that found nothing for the item ([noun]): how many releases it
     * found, "No results" where it answered with none, else the host's words for why it gave none (failed, skipped or
     * paused). A release several indexers list is kept under one of them, so an indexer's count is at least what its own
     * line said.
     */
    private fun lines(answers: List<Found>, noun: String): List<IndexerNote> {
        val indexerLines = answers.flatMap { it.indexers }
        val releases = answers.flatMap { it.releases }
        return indexerLines.map { it.name }.distinct().map { indexer ->
            val own = indexerLines.filter { it.name == indexer }
            val answered = own.filter { it.state == IndexerLine.State.Found }
            val found = maxOf(
                releases.filter { it.indexer == indexer }.distinctBy { it.name to it.size }.size,
                answered.maxOfOrNull { it.found } ?: 0,
            )
            val words = when {
                found > 0 -> Words.NoRelease.noneFor(found, noun)
                answered.isNotEmpty() -> Words.NoRelease.NO_RESULTS
                else -> own.first().text
            }
            IndexerNote(indexer, words)
        }
    }

    /** What one run judged: each release once, with its verdict and its best place. */
    private inner class Judged(private val matcher: FamilyMatcher, private val event: SportsEvent, private val part: Part) : Iterable<Judging> {
        private val byRelease = LinkedHashMap<Release, Judging>()

        /** Query [query]'s [releases], best first: the new ones judged on [work], the known ones moved up to a better place. */
        suspend fun add(query: Int, releases: List<ReleaseInfo>) = withContext(work) {
            releases.forEachIndexed { place, release ->
                ensureActive()
                val key = Release(release.name, release.size, release.indexer)
                val known = byRelease[key]
                if (known == null) {
                    val verdict = SharedRules.judge(matcher, event, part, release.name, release.postedAt, release.size)
                    byRelease[key] = Judging(verdict, release.handle, place, query, release.playable)
                } else if (place < known.place) {
                    known.place = place
                    known.query = query
                    known.handle = release.handle
                    known.playable = release.playable
                }
            }
        }

        override fun iterator(): Iterator<Judging> = byRelease.values.iterator()
    }

    /** One release as the run tells it from another. */
    private data class Release(val name: String, val size: Long, val indexer: String)

    /** A release's verdict, and the handle at its best place: [place] in the answer to query [query], [playable] here or not. */
    private class Judging(val verdict: Verdict, var handle: ReleaseHandle, var place: Int, var query: Int, var playable: Boolean)

    private companion object {
        /** Stage 1's queries, searched together. */
        const val FIRST_AT_ONCE = 2

        /** The longest sport word in no family the log line names, folded. */
        const val PLAIN_SPORT = 32

        val log = LoggerFactory.getLogger(Runner::class.java)
    }
}

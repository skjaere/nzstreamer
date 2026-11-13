package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.PickedTitle
import io.skjaere.usenet.search.QueryParser
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import io.skjaere.usenet.search.SearchPlanner
import io.skjaere.usenet.search.TitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * What the indexers found for a movie: its releases ([group], playable best first; null when there
 * were none) and what each indexer answered ([statuses]), ranked by [context] (the app's, for whom Play goes to, L127;
 * null where none was given); the releases taken ([accepted]) and the movie they are of ([known]) rank again for another
 * device with no indexer asked ([rankedBy]).
 */
data class MovieFind(
    val group: ResultGroup?,
    val statuses: List<IndexerStatus>,
    val context: RankingContext? = null,
    private val accepted: List<NewznabItem> = emptyList(),
    private val known: ResultGrouper.KnownTitle? = null,
    /** The indexers' ids in Settings' order, by which a torrent listed by several is kept once (L200). */
    private val order: List<String> = emptyList(),
) {
    val playable: List<RankedItem> get() = group?.playable.orEmpty()
    val hidden: List<RankedItem> get() = group?.hidden.orEmpty()

    /** The Cast device the releases are ranked for, as the list's "Ranked for" line names it (L127); null for this device. */
    val rankedFor: String? get() = context?.rankedFor

    /** The same releases ranked by [context] (another device's, L127), as they would have been found for it: no indexer asked. */
    fun rankedBy(context: RankingContext, grouper: ResultGrouper = ResultGrouper()): MovieFind =
        copy(group = grouper.group(accepted, context, known, order).movieGroup, context = context)

    /** "Searched 2 indexers · 9 found", above the play sheet's steps. */
    val searchLine: String get() = "Searched ${statuses.size} indexer${if (statuses.size == 1) "" else "s"} · ${group?.size ?: 0} found"

    /**
     * The no-release sheet's lines: each indexer, and what it answered as Search's breakdown says it; a torrent indexer whose
     * releases none of the services has cached, that none is (L213).
     */
    val stages: List<Pair<String, String>>
        get() = statuses.map { s -> s.name to (noneCached(accepted.filter { it.indexerId == s.id }).takeIf { s.answered } ?: s.state.outcome()) }

    /**
     * [stages] as a paired phone is told them (L37): one line for the indexers together, what they found and how many didn't
     * answer, since an indexer's name is its address unless the user named it, and its error can be its server's reply.
     */
    val remoteStages: List<Pair<String, String>>
        get() {
            val count = statuses.size
            val answered = statuses.filter { it.answered }
            val results = answered.sumOf { it.results }
            val silent = statuses.count { it.state is IndexerStatus.State.Failed || it.state == IndexerStatus.State.Searching }
            val found = when {
                results > 0 -> "$results result${if (results == 1) "" else "s"}, none playable"
                answered.isNotEmpty() -> "No results"
                else -> null
            }
            val outcome = listOfNotNull(found, "⚠ $silent indexer${if (silent == 1) "" else "s"} didn't answer".takeIf { silent > 0 }).joinToString(" · ")
            return listOf("Searched $count indexer${if (count == 1) "" else "s"}" to outcome.ifEmpty { "No answer" })
        }

    companion object {
        /** The movie's search's [outcomes], grouped as Search groups a picked movie's releases. */
        fun of(target: MovieTarget, outcomes: List<IndexerOutcome>, context: RankingContext, grouper: ResultGrouper = ResultGrouper(), order: List<String> = emptyList()): MovieFind {
            val answers = Answers(target.known)
            outcomes.forEach { answers.take(it) }
            return answers.find(context, grouper, order)
        }

        /**
         * The movie's search as its page lists it (W71): once each of the [indexers] searched has answered, a torrent
         * indexer once it says its torrents are being checked; then again each time some of those settle, the cached
         * ones joining the list. [order]: the indexers' ids in Settings' order, by which a torrent several list is kept once.
         */
        fun found(
            target: MovieTarget,
            outcomes: Flow<IndexerOutcome>,
            indexers: Int,
            context: RankingContext,
            grouper: ResultGrouper = ResultGrouper(),
            order: List<String> = emptyList(),
        ): Flow<MovieFind> = flow {
            val answers = Answers(target.known)
            var shown = false
            outcomes.collect { outcome ->
                answers.take(outcome)
                if (answers.statuses.size >= indexers && (!shown || outcome is IndexerOutcome.Checked)) {
                    emit(answers.find(context, grouper, order))
                    shown = true
                }
            }
            if (!shown) emit(answers.find(context, grouper, order))
        }
    }
}

/** The movie as Search knows a title picked from TMDB's suggestions: searched by its ids, its releases grouped as its own. */
internal val MovieTarget.picked: PickedTitle get() = PickedTitle(title, year, isTv = false, TvIds(tmdb = tmdbId, imdb = imdb))

internal val MovieTarget.known: ResultGrouper.KnownTitle get() = ResultGrouper.KnownTitle(title, year, isTv = false, originalLanguage, runtimeMinutes)

/**
 * One search's answers as Search counts them ([title]: the title picked, null for typed text): each indexer's status, in
 * the order they first answered, and the releases taken ([accepted]). Results an indexer found by name, having no id to
 * search by, must actually be that title. A torrent indexer's torrents count as found once it answers, and are taken as
 * their checks settle (L199): its status says how many still wait, and once none do, how many couldn't be checked.
 */
internal class Answers(private val title: ResultGrouper.KnownTitle?) {
    private val byIndexer = LinkedHashMap<String, IndexerStatus>()
    private val waiting = HashMap<String, MutableSet<String>>()
    private val taken = HashMap<String, Int>()
    private val unchecked = HashMap<String, Int>()

    /** Every release taken so far. */
    val accepted = mutableListOf<NewznabItem>()

    val statuses: List<IndexerStatus> get() = byIndexer.values.toList()

    /** Takes [outcome] in: its indexer's status now, or null where it changes nothing (a torrent of a title not asked for). */
    fun take(outcome: IndexerOutcome): IndexerStatus? {
        val indexer = outcome.indexer
        val state = when (outcome) {
            is IndexerOutcome.Results -> {
                val found = outcome.page.items.filter { accepts(it, outcome.query) }
                accepted += found
                IndexerStatus.State.Answered(found.size)
            }
            is IndexerOutcome.Failed -> IndexerStatus.State.Failed(outcome.error.message)
            is IndexerOutcome.Skipped -> IndexerStatus.State.Skipped(outcome.reason)
            is IndexerOutcome.Checking -> {
                val found = outcome.found.items.filter { accepts(it, outcome.query) }
                waiting[indexer.id] = found.map { it.key }.toMutableSet()
                taken[indexer.id] = found.size
                unchecked[indexer.id] = 0
                if (found.isEmpty()) IndexerStatus.State.Answered(0) else IndexerStatus.State.Checking(found.size, waiting.getValue(indexer.id).size)
            }
            is IndexerOutcome.Checked -> {
                val left = waiting[indexer.id] ?: return null
                val settled = outcome.items.filter { left.remove(it.key) }
                if (settled.isEmpty() && left.isNotEmpty()) return null
                accepted += settled
                unchecked[indexer.id] = unchecked.getValue(indexer.id) + settled.count { it.availability == Availability.NotChecked }
                val results = taken.getValue(indexer.id)
                if (left.isEmpty()) IndexerStatus.State.Answered(results, unchecked.getValue(indexer.id)) else IndexerStatus.State.Checking(results, left.size)
            }
        }
        return IndexerStatus(indexer.id, indexer.name, state).also { byIndexer[indexer.id] = it }
    }

    /** What the search found for a movie picked: its releases as one group, ranked by [context]. */
    fun find(context: RankingContext, grouper: ResultGrouper = ResultGrouper(), order: List<String> = emptyList()): MovieFind {
        val releases = accepted.toList()
        return MovieFind(grouper.group(releases, context, title, order).movieGroup, statuses, context, releases, title, order)
    }

    private fun accepts(item: NewznabItem, query: SearchQuery): Boolean {
        val byName = (query is SearchQuery.Tv && query.ids == TvIds()) || query is SearchQuery.Text || (query is SearchQuery.Movie && query.imdb == null && query.tmdb == null)
        return title == null || !byName || TitleMatcher.matches(ReleaseParser.parse(item.title), title.name, title.year)
    }
}

/** What an indexer's answer comes to, as the breakdowns under a search with nothing to play say it. */
internal fun IndexerStatus.State.outcome(): String = when (this) {
    is IndexerStatus.State.Answered -> if (results == 0) "No results" else "$results result${if (results == 1) "" else "s"}, none playable"
    is IndexerStatus.State.Failed -> "⚠ $reason"
    is IndexerStatus.State.Skipped -> reason
    IndexerStatus.State.Searching -> "No answer"
    // Its torrents still checked count as found, as once they are.
    is IndexerStatus.State.Checking -> IndexerStatus.State.Answered(results).outcome()
}

/**
 * Searches every indexer for [target] as the Search tab searches a movie picked from TMDB's
 * suggestions (by its ids where an indexer can, else by name), so a movie's page and Search find the
 * same releases. What it found comes once every indexer has answered, and again as torrents join once checked
 * ([MovieFind.found]): Play's own pick takes the last (L199).
 */
fun findMovieReleases(container: AppContainer, target: MovieTarget, fresh: Boolean = false): Flow<MovieFind> = flow {
    val parsed = QueryParser.parse(target.query)
    val indexers = container.indexerConfigs()
    val context = container.rankingContext()
    val outcomes = container.checkedSearch.search(indexers, fresh) { caps -> SearchPlanner.plan(caps, parsed, target.picked) }
    emitAll(MovieFind.found(target, outcomes, indexers.count { it.enabled }, context, order = indexers.map { it.id }))
    // Checking names (a release-name parse per item) and grouping stay off the main thread.
}.flowOn(Dispatchers.Default)

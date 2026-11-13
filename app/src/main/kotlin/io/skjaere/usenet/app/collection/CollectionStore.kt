package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieRows
import io.skjaere.usenet.app.collection.model.RowCache
import io.skjaere.usenet.app.collection.model.RowInputs
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowRows
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.CollectionCounts
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.RefreshFailure
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.ShowWatchAggregate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** What the lists are built from: the library's tables, each read whole by one query. */
class CollectionInputs(
    val shows: Flow<List<CollectedShow>>,
    val movies: Flow<List<CollectedMovie>>,
    val showSummaries: Flow<List<ShowSummary>>,
    val movieSummaries: Flow<List<MovieSummary>>,
    val showWatches: Flow<List<ShowWatchAggregate>>,
    val movieWatches: Flow<List<MovieWatch>>,
    val failures: Flow<List<RefreshFailure>>,
    val counts: Flow<CollectionCounts>,
) {
    companion object {
        fun of(library: LibraryRepository) = CollectionInputs(
            library.shows, library.movies, library.showSummaries, library.movieSummaries,
            library.showWatchAggregates(), library.movieWatches, library.factlessFailures, library.counts,
        )
    }
}

/**
 * The collection's rows, one set per process: Home and the Collection tab read the same, so Home
 * warms the tab. Built from what the library stores, off the main thread, again only for the titles
 * whose inputs changed ([RowCache]); null until the first read. Kept a minute after the last reader
 * leaves (the player in front, a quick look elsewhere), then nothing re-queries until one returns.
 */
class CollectionStore(
    inputs: CollectionInputs,
    today: Flow<LocalDate>,
    hasKey: Flow<Boolean>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(library: LibraryRepository, today: Flow<LocalDate>, hasKey: Flow<Boolean>, scope: CoroutineScope) :
        this(CollectionInputs.of(library), today, hasKey, scope)

    private val showCache = RowCache<ShowRow>()
    private val movieCache = RowCache<MovieRow>()
    private val dayAndKey = combine(today, hasKey) { day, key -> day to key }.distinctUntilChanged()

    val shows: StateFlow<List<ShowRow>?> =
        combine(inputs.shows, inputs.showSummaries, inputs.showWatches, inputs.failures, dayAndKey) { shows, summaries, watches, failures, (day, key) ->
            ShowRows.build(shows, summaries.associateBy { it.tmdbId }, watches.associateBy { it.showTmdbId }, RowInputs.failures(failures), day, key, showCache)
        }.share(scope, dispatcher)

    val movies: StateFlow<List<MovieRow>?> =
        combine(inputs.movies, inputs.movieSummaries, inputs.movieWatches, inputs.failures, dayAndKey) { movies, summaries, watches, failures, (day, key) ->
            MovieRows.build(movies, summaries.associateBy { it.tmdbId }, watches.associateBy { it.movieTmdbId }, RowInputs.failures(failures), day, key, movieCache)
        }.share(scope, dispatcher)

    /** Home's "214 shows · 380 movies", without building the movie rows. */
    val counts: StateFlow<CollectionCounts?> = inputs.counts.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MS), null)

    private fun <T> Flow<List<T>>.share(scope: CoroutineScope, dispatcher: CoroutineDispatcher): StateFlow<List<T>?> =
        flowOn(dispatcher).conflate().distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MS), null)

    private companion object {
        const val KEEP_MS = 60_000L
    }
}

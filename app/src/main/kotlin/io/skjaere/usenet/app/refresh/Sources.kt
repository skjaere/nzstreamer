package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.RefreshCandidate
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Where the refresher's facts come from: TMDB in the app ([TmdbTitleSource]), a fake in tests. */
interface TitleSource {
    suspend fun show(tmdbId: Int): Aged<ShowDetails>
    suspend fun movie(tmdbId: Int): Aged<MovieDetails>
}

/** TMDB through [TmdbLookup]'s seams, read at each call, so the device tests' fake answers reach the refresher too. */
class TmdbTitleSource(private val container: AppContainer) : TitleSource {
    override suspend fun show(tmdbId: Int) = TmdbLookup.showAged(container, tmdbId)
    override suspend fun movie(tmdbId: Int) = TmdbLookup.movieAged(container, tmdbId)
}

/** The storage the refresher needs: [LibrarySummaryStore] in the app, in memory in tests. */
interface SummaryStore {
    /** The collected shows and movies with their bookkeeping, as they change. */
    val candidates: Flow<Pair<List<RefreshCandidate>, List<RefreshCandidate>>>
    suspend fun save(shows: List<ShowSummary>, movies: List<MovieSummary>, refresh: List<SummaryRefresh>)
    suspend fun invalidateAll()
    suspend fun prune(before: Long)
}

class LibrarySummaryStore(private val library: LibraryRepository) : SummaryStore {
    override val candidates = library.refreshCandidates
    override suspend fun save(shows: List<ShowSummary>, movies: List<MovieSummary>, refresh: List<SummaryRefresh>) = library.saveSummaries(shows, movies, refresh)
    override suspend fun invalidateAll() = library.invalidateSummaries()
    override suspend fun prune(before: Long) = library.pruneSummaries(before)
}

/**
 * At least [minIntervalMs] between passes, first come first through. A coroutine context element:
 * TmdbClient's beforeNetwork passes the gate of the coroutine that asks, so only the refresher's
 * requests (which run under it) are spaced, and only those that go to the network. [clock] is
 * monotonic milliseconds.
 */
class RateGate(private val minIntervalMs: Long = 50, private val clock: () -> Long) : AbstractCoroutineContextElement(Key) {
    // Fair: waiters get through in the order they came.
    private val turn = Mutex()
    /** When the next may pass; null before the first (a monotonic clock may read anything). */
    private var next: Long? = null

    suspend fun pass() = turn.withLock {
        next?.let { at -> (at - clock()).takeIf { it > 0 }?.let { delay(it) } }
        next = clock() + minIntervalMs
    }

    companion object Key : CoroutineContext.Key<RateGate> {
        /** What TmdbClient runs before each request it sends. */
        suspend fun beforeNetwork() {
            currentCoroutineContext()[Key]?.pass()
        }
    }
}

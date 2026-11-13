package io.skjaere.usenet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lists' cache of TMDB facts and the refresher's bookkeeping: what is stored reads back, and a
 * refresh that learns nothing new wakes no list (Room re-runs a query on every write to its tables).
 */
@RunWith(AndroidJUnit4::class)
class SummaryDaoTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private var time = 1_000L
    private val library = LibraryRepository(db) { time }
    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun show(id: Int, status: String = "Returning Series") =
        ShowSummary(id, "Avrel $id", status, "1:8", EpisodeMark(1, 4, "The Long Tide", 20_732), EpisodeMark(1, 5, null, 20_746))

    private fun movie(id: Int, runtime: Int = 118) = MovieSummary(id, "Ébrin $id", runtime, 18_208, "Released")

    private fun refresh(type: String, id: Int, fetchedAt: Long = 0, error: String? = null, retryAt: Long = 0) =
        SummaryRefresh(type, id, fetchedAt = fetchedAt, staleAt = fetchedAt + 100, retryAt = retryAt, failures = if (error == null) 0 else 1, error = error)

    /** Every emission of [flow], from a collector started now. */
    private fun <T> emissions(flow: Flow<T>): Channel<T> = Channel<T>(Channel.UNLIMITED).also { channel ->
        scope.launch { flow.collect { channel.send(it) } }
    }

    /**
     * Waits until Room has seen the writes so far: a write to summary_refresh makes [sentinel] (the
     * raw failures query, which reads it) emit, and the invalidation that does reaches every query
     * reading a written table in the same round. Then a moment for those to re-run.
     */
    private suspend fun settle(sentinel: Channel<*>) {
        withTimeout(5_000) { sentinel.receive() }
        delay(500)
    }

    @Test
    fun factsReadBackAsStored() = runBlocking<Unit> {
        val bare = ShowSummary(3)
        library.saveSummaries(listOf(show(1), bare), listOf(movie(11), MovieSummary(12)), listOf(refresh(SummaryRefresh.SHOW, 1, fetchedAt = 900)))
        assertEquals(setOf(show(1), bare), library.showSummaries.first().toSet())
        assertEquals(setOf(movie(11), MovieSummary(12)), library.movieSummaries.first().toSet())
        assertEquals(show(1), library.showSummary(1).first())
        assertEquals(bare, library.showSummary(3).first())
        assertNull(library.showSummary(2).first())
        assertEquals(movie(11), library.movieSummary(11).first())
    }

    @Test
    fun aRefreshThatLearnsNothingNewWakesNoList() = runBlocking<Unit> {
        library.saveSummaries(listOf(show(1), show(2)), listOf(movie(11)), listOf(refresh(SummaryRefresh.SHOW, 1, fetchedAt = 900)))
        val shows = emissions(library.showSummaries)
        val movies = emissions(library.movieSummaries)
        val sentinel = emissions(db.summaries().factlessFailures())
        assertEquals(2, withTimeout(5_000) { shows.receive() }.size)
        withTimeout(5_000) { movies.receive() }
        withTimeout(5_000) { sentinel.receive() }

        // The same facts with new bookkeeping, then bookkeeping alone.
        time = 2_000
        library.saveSummaries(listOf(show(1), show(2)), listOf(movie(11)), listOf(refresh(SummaryRefresh.SHOW, 1, fetchedAt = 1_900), refresh(SummaryRefresh.SHOW, 2, fetchedAt = 1_900)))
        settle(sentinel)
        library.saveSummaries(emptyList(), emptyList(), listOf(refresh(SummaryRefresh.MOVIE, 11, fetchedAt = 1_950)))
        settle(sentinel)
        assertTrue("an unchanged show woke the list", shows.tryReceive().isFailure)
        assertTrue("an unchanged movie woke the list", movies.tryReceive().isFailure)

        // A fact that changed is written.
        library.saveSummaries(listOf(show(1), show(2, "Ended")), listOf(movie(11)), emptyList())
        assertEquals("Ended", withTimeout(5_000) { shows.receive() }.single { it.tmdbId == 2 }.status)
        library.saveSummaries(emptyList(), listOf(movie(11, runtime = 119)), emptyList())
        assertEquals(119, withTimeout(5_000) { movies.receive() }.single().runtimeMinutes)
    }

    @Test
    fun aFailureShowsOnlyForTitlesWithoutFactsAndOnlyWhenItChanges() = runBlocking<Unit> {
        library.saveSummaries(listOf(show(1)), emptyList(), listOf(refresh(SummaryRefresh.SHOW, 1, fetchedAt = 900)))
        val failures = emissions(library.factlessFailures)
        val sentinel = emissions(db.summaries().factlessFailures())
        assertEquals(emptyList<RefreshFailure>(), withTimeout(5_000) { failures.receive() })
        withTimeout(5_000) { sentinel.receive() }

        library.saveSummaries(emptyList(), emptyList(), listOf(refresh(SummaryRefresh.SHOW, 2, error = "NotFound", retryAt = 5_000)))
        assertEquals(listOf(RefreshFailure("show", 2, "NotFound")), withTimeout(5_000) { failures.receive() })
        settle(sentinel)
        // Tried again and failed again: the same list, no emission.
        library.saveSummaries(emptyList(), emptyList(), listOf(refresh(SummaryRefresh.SHOW, 2, error = "NotFound", retryAt = 9_000)))
        // A title with facts whose refresh failed shows its facts, not the failure.
        library.saveSummaries(emptyList(), emptyList(), listOf(refresh(SummaryRefresh.SHOW, 1, fetchedAt = 900, error = "Server")))
        settle(sentinel)
        assertTrue("an unchanged failure list was emitted", failures.tryReceive().isFailure)

        library.saveSummaries(emptyList(), emptyList(), listOf(refresh(SummaryRefresh.MOVIE, 11, error = "Server")))
        assertEquals(setOf(RefreshFailure("show", 2, "NotFound"), RefreshFailure("movie", 11, "Server")), withTimeout(5_000) { failures.receive() }.toSet())
        // Facts at last.
        library.saveSummaries(listOf(show(2)), emptyList(), listOf(refresh(SummaryRefresh.SHOW, 2, fetchedAt = 1_000)))
        assertEquals(listOf(RefreshFailure("movie", 11, "Server")), withTimeout(5_000) { failures.receive() })
    }

    @Test
    fun theRefreshCandidatesAreTheCollectedTitlesWithTheirBookkeepingAndLastWatch() = runBlocking<Unit> {
        library.addShow(CollectedShow(CollectedShow.idFor(1), 1, name = "Avrel 1", addedAt = 0, updatedAt = 0))
        time = 1_100
        library.addShow(CollectedShow(CollectedShow.idFor(2), 2, name = "Avrel 2", addedAt = 0, updatedAt = 0))
        library.addShow(CollectedShow(CollectedShow.idFor(3), 3, name = "Avrel 3", addedAt = 0, updatedAt = 0))
        library.addMovie(CollectedMovie(CollectedMovie.idFor(11), 11, title = "Ébrin 11", addedAt = 0, updatedAt = 0))
        library.addMovie(CollectedMovie(CollectedMovie.idFor(12), 12, title = "Ébrin 12", addedAt = 0, updatedAt = 0))
        library.removeTitles(shows = listOf(3))
        fun episode(s: Int, e: Int, playedAt: Long, deleted: Boolean = false) = WatchState(
            WatchKey.episode(2, s, e), WatchKind.Episode, showTmdbId = 2, season = s, episode = e, title = "Avrel 2", lastPlayedAt = playedAt, updatedAt = 1, deleted = deleted,
        )
        db.watchStates().upsert(
            listOf(
                episode(1, 1, 5_000), episode(0, 1, 6_000), episode(1, 2, 9_000, deleted = true),
                WatchState(WatchKey.movie(11), WatchKind.Movie, movieTmdbId = 11, title = "Ébrin 11", lastPlayedAt = 7_000, updatedAt = 1),
            ),
        )
        library.saveSummaries(
            emptyList(), emptyList(),
            listOf(refresh(SummaryRefresh.SHOW, 2, fetchedAt = 900, retryAt = 3), refresh(SummaryRefresh.MOVIE, 11, fetchedAt = 800), refresh(SummaryRefresh.SHOW, 3, fetchedAt = 1)),
        )
        val (shows, movies) = library.refreshCandidates.first()
        assertEquals(
            setOf(RefreshCandidate(1, 1_000, null, null, null, null, null), RefreshCandidate(2, 1_100, 900, 1_000, 3, 0, 6_000)),
            shows.toSet(),
        )
        assertEquals(
            setOf(RefreshCandidate(11, 1_100, 800, 900, 0, 0, 7_000), RefreshCandidate(12, 1_100, null, null, null, null, null)),
            movies.toSet(),
        )

        // The TMDB language changed: everything is due now, nothing else changes.
        library.invalidateSummaries()
        val due = library.refreshCandidates.first().first.single { it.tmdbId == 2 }
        assertEquals(RefreshCandidate(2, 1_100, 900, 0, 0, 0, 6_000), due)
    }

    @Test
    fun pruningDropsWhatWasLastAskedLongAgoOfTitlesNoLongerCollected() = runBlocking<Unit> {
        listOf(1, 4).forEach { library.addShow(CollectedShow(CollectedShow.idFor(it), it, name = "Avrel $it", addedAt = 0, updatedAt = 0)) }
        library.addMovie(CollectedMovie(CollectedMovie.idFor(11), 11, title = "Ébrin 11", addedAt = 0, updatedAt = 0))
        library.removeTitles(shows = listOf(4))
        // 1 collected, 2 not (asked long ago), 3 not (asked lately), 4 removed (long ago), 5 not collected and never answered.
        library.saveSummaries(
            listOf(show(1), show(2), show(3), show(4)), listOf(movie(11), movie(12), movie(13)),
            listOf(
                refresh(SummaryRefresh.SHOW, 1, fetchedAt = 100), refresh(SummaryRefresh.SHOW, 2, fetchedAt = 100),
                refresh(SummaryRefresh.SHOW, 3, fetchedAt = 20_000), refresh(SummaryRefresh.SHOW, 4, fetchedAt = 100),
                refresh(SummaryRefresh.SHOW, 5, error = "NotFound"),
                refresh(SummaryRefresh.MOVIE, 11, fetchedAt = 100), refresh(SummaryRefresh.MOVIE, 12, fetchedAt = 100), refresh(SummaryRefresh.MOVIE, 13, fetchedAt = 20_000),
            ),
        )
        library.pruneSummaries(before = 10_000)
        assertEquals(setOf(1, 3), library.showSummaries.first().map { it.tmdbId }.toSet())
        assertEquals(setOf(11, 13), library.movieSummaries.first().map { it.tmdbId }.toSet())
        val bookkeeping = db.query("SELECT type, tmdbId FROM summary_refresh", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) + ":" + c.getInt(1) else null }.toSet()
        }
        assertEquals(setOf("show:1", "show:3", "movie:11", "movie:13"), bookkeeping)
    }
}

package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.LoadState
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.CollectionCounts
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.RefreshFailure
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.ShowWatchAggregate
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionStoreTest {
    private val today = LocalDate.of(2026, 10, 14)

    private fun show(id: Int) = CollectedShow(CollectedShow.idFor(id), id, name = "Avrel $id", addedAt = id.toLong(), updatedAt = 0)

    private fun facts(id: Int) = ShowDetails(id, "Avrel $id", null, 2021, null, null, null, "Ended", false, 45, listOf(SeasonSummary(1, null, 4, null, null)), null, null, ExternalIds()).toSummary()

    private class Library {
        val shows = MutableStateFlow<List<CollectedShow>?>(null)
        val movies = MutableStateFlow(emptyList<CollectedMovie>())
        val showSummaries = MutableStateFlow(emptyList<ShowSummary>())
        val movieSummaries = MutableStateFlow(emptyList<MovieSummary>())
        val showWatches = MutableStateFlow(emptyList<ShowWatchAggregate>())
        val movieWatches = MutableStateFlow(emptyList<MovieWatch>())
        val failures = MutableStateFlow(emptyList<RefreshFailure>())
        val counts = MutableStateFlow(CollectionCounts(0, 0))
        val inputs = CollectionInputs(
            kotlinx.coroutines.flow.flow { shows.collect { if (it != null) emit(it) } }, movies, showSummaries, movieSummaries, showWatches, movieWatches, failures, counts,
        )
    }

    private fun TestScope.store(library: Library, hasKey: MutableStateFlow<Boolean> = MutableStateFlow(true), day: MutableStateFlow<LocalDate> = MutableStateFlow(today)) =
        CollectionStore(library.inputs, day, hasKey, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `nothing until the library has answered, then a row per show`() = runTest {
        val library = Library()
        val store = store(library)
        val seen = mutableListOf<List<ShowRow>?>()
        backgroundScope.launch { store.shows.collect { seen += it } }
        runCurrent()
        assertNull(store.shows.value)
        library.shows.value = listOf(show(1), show(2))
        library.showSummaries.value = listOf(facts(1))
        runCurrent()
        assertEquals(listOf(LoadState.Ready, LoadState.Pending), store.shows.value?.map { it.load })
        assertEquals(listOf("Avrel 1", "Avrel 2"), store.shows.value?.map { it.text.name })
    }

    @Test
    fun `one show played builds that row again and keeps the others`() = runTest {
        val library = Library()
        val store = store(library)
        backgroundScope.launch { store.shows.collect {} }
        library.shows.value = (1..3).map(::show)
        library.showSummaries.value = (1..3).map(::facts)
        runCurrent()
        val before = store.shows.value!!
        library.showWatches.value = listOf(ShowWatchAggregate(2, "1:1", null, 9))
        runCurrent()
        val after = store.shows.value!!
        assertSame(before[0], after[0])
        assertNotSame(before[1], after[1])
        assertSame(before[2], after[2])
        assertEquals(9, after[1].lastWatchedAt)
    }

    @Test
    fun `an input that answers the same again doesn't emit new rows`() = runTest {
        val library = Library()
        val store = store(library)
        val seen = mutableListOf<List<ShowRow>?>()
        backgroundScope.launch { store.shows.collect { seen += it } }
        library.shows.value = listOf(show(1))
        runCurrent()
        val emissions = seen.size
        library.failures.value = listOf(RefreshFailure("movie", 7, "Server"))
        library.showSummaries.value = emptyList()
        runCurrent()
        assertEquals(emissions, seen.size)
    }

    @Test
    fun `without a key and on a new day the rows say so`() = runTest {
        val library = Library()
        val hasKey = MutableStateFlow(true)
        val day = MutableStateFlow(today)
        val store = store(library, hasKey, day)
        backgroundScope.launch { store.shows.collect {} }
        backgroundScope.launch { store.movies.collect {} }
        backgroundScope.launch { store.counts.collect {} }
        library.shows.value = listOf(show(1))
        library.movies.value = listOf(CollectedMovie(CollectedMovie.idFor(5), 5, title = "Ébrin 5", addedAt = 1, updatedAt = 1))
        library.movieSummaries.value = listOf(MovieSummary(5, releaseDay = today.plusDays(1).toEpochDay(), status = "Planned"))
        library.counts.value = CollectionCounts(1, 1)
        runCurrent()
        assertEquals("Out 15 Oct", store.movies.value?.single()?.status)
        day.value = today.plusDays(1)
        runCurrent()
        assertEquals("Not watched", store.movies.value?.single()?.status)
        hasKey.value = false
        runCurrent()
        assertEquals("No episode info yet", store.shows.value?.single()?.status)
        assertEquals(CollectionCounts(1, 1), store.counts.value)
        assertEquals(listOf<MovieRow>(), store.movies.value?.filter { it.load != LoadState.Ready })
    }
}

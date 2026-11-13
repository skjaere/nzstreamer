package io.skjaere.usenet.app.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Discover tab's view model over a fake source: rows per side, the genre chip, the key, the language, the marks. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private val store = ViewModelStore()

    @AfterEach
    fun main() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(f: Fixture, saved: SavedStateHandle = SavedStateHandle()): DiscoverViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ViewModelProvider(store, viewModelFactory { initializer { DiscoverViewModel(f.deps, saved) } })[DiscoverViewModel::class.java]
            .also { runCurrent() }
    }

    private fun DiscoverViewModel.headings() = ui.value.rows.map { it.heading }
    private fun DiscoverViewModel.row(list: DiscoverList) = ui.value.rows.first { it.list == list }.view

    @Test
    fun `while the key is unread nothing shows and nothing is asked`() = runTest {
        val f = Fixture(key = null)
        val vm = vm(f)
        assertNull(vm.ui.value.key)
        assertEquals(emptyList(), vm.ui.value.rows)
        assertEquals(emptyList(), f.source.asked)
    }

    @Test
    fun `the key the app read last shows from the first frame`() = runTest {
        val f = Fixture(key = null, knownKey = false)
        assertEquals(KeyState.Missing, vm(f).ui.value.key)
    }

    @Test
    fun `without a key the tab says so and asks TMDB nothing`() = runTest {
        val f = Fixture(key = false)
        val vm = vm(f)
        assertEquals(KeyState.Missing, vm.ui.value.key)
        assertEquals(emptyList(), vm.ui.value.rows)
        assertEquals(emptyList(), f.source.asked)
        assertEquals(emptyList(), f.source.genresAsked)
    }

    @Test
    fun `a key added loads the shows' five lists, their first pages, and their genres`() = runTest {
        val f = Fixture(key = false)
        val vm = vm(f)
        f.setKey(true)
        runCurrent()
        assertEquals(KeyState.Ready, vm.ui.value.key)
        assertEquals(listOf("Trending this week", "Popular", "Top rated", "Airing today", "On the air"), vm.headings())
        assertEquals(DiscoverList.on(MediaType.Tv).map { it to 1 }, f.source.asked.map { (q, from) -> q.list to from })
        assertTrue(f.source.asked.all { (q, _) -> q.type == MediaType.Tv && !q.filters.narrows })
        assertEquals(20, (vm.row(DiscoverList.Popular) as RowView.Cards).cards.size)
        assertEquals(listOf("Drama", "Comedy"), vm.ui.value.genres.map { it.name })
    }

    @Test
    fun `the movies' side is asked for once it shows, and each side keeps its rows`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        assertEquals(5, f.source.asked.size)
        vm.switchSide(MediaType.Movie)
        runCurrent()
        assertEquals(listOf("Trending this week", "Popular", "Top rated", "Now playing", "Upcoming"), vm.headings())
        assertEquals(10, f.source.asked.size)
        vm.switchSide(MediaType.Tv)
        vm.switchSide(MediaType.Movie)
        runCurrent()
        assertEquals(10, f.source.asked.size)
        assertEquals(listOf(MediaType.Tv, MediaType.Movie), f.source.genresAsked)
    }

    @Test
    fun `a genre chip filters every row of the side`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        f.source.asked.clear()
        vm.pickGenre(18)
        runCurrent()
        assertEquals(18, vm.ui.value.genre)
        assertEquals(5, f.source.asked.size)
        assertTrue(f.source.asked.all { (q, _) -> q.filters.genre == 18 })
        // Back to all genres asks again without it.
        vm.pickGenre(null)
        runCurrent()
        assertEquals(null, f.source.asked.last().first.filters.genre)
    }

    @Test
    fun `the side and its genre are kept with the screen's state`() = runTest {
        val saved = SavedStateHandle()
        val f = Fixture()
        vm(f, saved).apply {
            switchSide(MediaType.Movie)
            pickGenre(35)
        }
        store.clear()
        val again = vm(Fixture(), saved)
        assertEquals(MediaType.Movie, again.ui.value.side)
        assertEquals(35, again.ui.value.genre)
    }

    @Test
    fun `a row that fails leaves the others, and Try again asks for it alone`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            if (q.list == DiscoverList.TopRated) throw TmdbException(TmdbError.Network("down"))
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        assertEquals(RowView.Failed(LOAD_FAILED), vm.row(DiscoverList.TopRated))
        assertTrue(vm.row(DiscoverList.Popular) is RowView.Cards)
        assertEquals(false, vm.ui.value.allFailed)
        f.source.asked.clear()
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        vm.retry(DiscoverList.TopRated)
        runCurrent()
        assertEquals(listOf(DiscoverList.TopRated), f.source.asked.map { it.first.list })
        assertTrue(vm.row(DiscoverList.TopRated) is RowView.Cards)
    }

    @Test
    fun `every row failing is one failure, and Try again asks for them all`() = runTest {
        val f = Fixture()
        f.source.answer = { _, _ -> throw TmdbException(TmdbError.Network("down")) }
        val vm = vm(f)
        assertEquals(LOAD_FAILED, vm.ui.value.failure)
        f.source.asked.clear()
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        vm.retry()
        runCurrent()
        assertEquals(5, f.source.asked.size)
        assertEquals(null, vm.ui.value.failure)
    }

    @Test
    fun `a key TMDB refused is the key's state, and a new key is asked with at once`() = runTest {
        val f = Fixture()
        f.source.answer = { _, _ -> throw TmdbException(TmdbError.InvalidKey("Invalid API key")) }
        val vm = vm(f)
        assertEquals(KeyState.Refused, vm.ui.value.key)
        assertEquals(emptyList(), vm.ui.value.rows)
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        f.source.asked.clear()
        f.setKey(true)
        runCurrent()
        assertEquals(KeyState.Ready, vm.ui.value.key)
        assertEquals(5, f.source.asked.size)
    }

    @Test
    fun `offline copies say how old the oldest is`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            val age = if (q.list == DiscoverList.Popular) Duration.ofHours(5) else Duration.ofHours(2)
            Aged(FakeSource.page(q.type, from, 3), age, offline = q.list != DiscoverList.Trending)
        }
        assertEquals(Duration.ofHours(5), vm(f).ui.value.offline)
    }

    @Test
    fun `a new TMDB language asks for every list and the genres again`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        f.source.asked.clear()
        f.language.value = "es-ES"
        runCurrent()
        assertEquals(5, f.source.asked.size)
        assertEquals(listOf(MediaType.Tv, MediaType.Tv), f.source.genresAsked)
        assertTrue(vm.row(DiscoverList.Trending) is RowView.Cards)
    }

    @Test
    fun `the key gone drops the rows and asks nothing more`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        f.source.asked.clear()
        f.setKey(false)
        runCurrent()
        assertEquals(KeyState.Missing, vm.ui.value.key)
        vm.switchSide(MediaType.Movie)
        vm.retry()
        runCurrent()
        assertEquals(emptyList(), f.source.asked)
    }

    @Test
    fun `a title added to the collection shows its mark in every row it is in`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        val card = (vm.row(DiscoverList.Popular) as RowView.Cards).cards.first()
        assertEquals(false, card.collected)
        f.shows.value = setOf(card.title.tmdbId)
        runCurrent()
        val marked = vm.ui.value.rows.flatMap { (it.view as RowView.Cards).cards }.filter { it.key == card.key }
        assertEquals(5, marked.size)
        assertTrue(marked.all { it.collected && it.line == IN_COLLECTION })
        // A movie of the same id is another title.
        f.shows.value = emptySet()
        f.movies.value = setOf(card.title.tmdbId)
        runCurrent()
        assertEquals(false, (vm.row(DiscoverList.Popular) as RowView.Cards).cards.first().collected)
    }

    @Test
    fun `TMDB saying to slow down shows the last copy, and asks again after a wait`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ofHours(1), rateLimited = q.list == DiscoverList.Popular) }
        val vm = vm(f)
        assertTrue(vm.row(DiscoverList.Popular) is RowView.Cards)
        f.source.asked.clear()
        advanceTimeBy(9_000)
        assertEquals(emptyList(), f.source.asked)
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        advanceTimeBy(1_100)
        assertEquals(listOf(DiscoverList.Popular), f.source.asked.map { it.first.list })
        // Answered, it waits for nothing more.
        advanceTimeBy(600_000)
        assertEquals(1, f.source.asked.size)
    }

    @Test
    fun `TMDB busy with no copy says so, asks again after each wait, then leaves Try again to the user`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            if (q.list == DiscoverList.Popular) throw TmdbException(TmdbError.RateLimited())
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        assertEquals(RowView.Failed(BUSY), vm.row(DiscoverList.Popular))
        f.source.asked.clear()
        advanceTimeBy(Duration.ofMinutes(10).toMillis())
        assertEquals(DiscoverDeps.BACKOFF.size, f.source.asked.size)
        assertEquals(RowView.Failed(BUSY), vm.row(DiscoverList.Popular))
    }

    @Test
    fun `a side's first load asks at most four lists at once`() = runTest {
        val f = Fixture()
        val held = CompletableDeferred<Unit>()
        f.source.answer = { q, from ->
            held.await()
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        assertEquals(4, f.source.asked.size)
        held.complete(Unit)
        runCurrent()
        assertEquals(5, f.source.asked.size)
        assertTrue(vm.ui.value.rows.all { it.view is RowView.Cards })
    }
}

package io.skjaere.usenet.app.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.Filter
import io.skjaere.usenet.discover.Period
import io.skjaere.usenet.discover.YearFilter
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

/** A See all page's view model over a fake source: its filters and its paging. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverListViewModelTest {
    private val store = ViewModelStore()
    private val trending = DiscoverQuery(MediaType.Tv, DiscoverList.Trending)
    private val popularMovies = DiscoverQuery(MediaType.Movie, DiscoverList.Popular)

    @AfterEach
    fun main() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(f: Fixture, query: DiscoverQuery = trending, saved: SavedStateHandle = SavedStateHandle()): DiscoverListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ViewModelProvider(store, viewModelFactory { initializer { DiscoverListViewModel(f.deps, query, saved) } })[DiscoverListViewModel::class.java]
            .also { runCurrent() }
    }

    private fun Fixture.pages() = source.asked.map { it.second }

    @Test
    fun `the first page loads as the page opens, called by its list and side`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        assertEquals(listOf(trending to 1), f.source.asked)
        assertEquals("Trending · Shows", vm.ui.value.title)
        assertEquals(ListView.Shown, vm.ui.value.view)
        assertEquals(20, vm.ui.value.cards.size)
    }

    @Test
    fun `near the end the next page loads, one at a time`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        val held = CompletableDeferred<Unit>()
        f.source.answer = { q, from ->
            held.await()
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        vm.nearEnd()
        runCurrent()
        assertEquals(Footer.Loading, vm.ui.value.footer)
        vm.nearEnd()
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 2), f.pages())
        held.complete(Unit)
        runCurrent()
        assertEquals(40, vm.ui.value.cards.size)
        assertEquals(Footer.None, vm.ui.value.footer)
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 2, 3), f.pages())
    }

    @Test
    fun `it stops at TMDB's last page`() = runTest {
        val f = Fixture(FakeSource(pages = 2))
        val vm = vm(f)
        repeat(5) {
            vm.nearEnd()
            runCurrent()
        }
        assertEquals(listOf(1, 2), f.pages())
        assertEquals(40, vm.ui.value.cards.size)
        assertEquals(Footer.None, vm.ui.value.footer)
    }

    @Test
    fun `it stops at the cap, and says it shows the first 500`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, DiscoverPage.MAX_PAGES, capped = true), Duration.ZERO) }
        val vm = vm(f)
        repeat(40) {
            vm.nearEnd()
            runCurrent()
        }
        assertEquals((1..DiscoverPage.MAX_PAGES).toList(), f.pages())
        assertEquals(500, vm.ui.value.cards.size)
        assertEquals(Footer.Capped, vm.ui.value.footer)
    }

    @Test
    fun `filtered trending at the cap says it searched the first 500, not that it shows them`() = runTest {
        val f = Fixture()
        // Two matches in each step of five pages, up to the 25th.
        f.source.trendingMatching((1..21 step 5).associateWith { 2 })
        val vm = vm(f, trendingDrama)
        repeat(10) {
            vm.nearEnd()
            runCurrent()
        }
        assertEquals(listOf(1, 6, 11, 16, 21), f.pages())
        assertEquals(10, vm.ui.value.cards.size)
        assertEquals(Footer.Searched, vm.ui.value.footer)
    }

    @Test
    fun `a title on two pages shows once`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            // TMDB's lists move between two requests: page 2 starts with page 1's last title.
            val page = FakeSource.page(q.type, from, 3)
            Aged(if (from == 2) page.copy(items = listOf(FakeSource.title(q.type, 120)) + page.items.drop(1)) else page, Duration.ZERO)
        }
        val vm = vm(f)
        vm.nearEnd()
        runCurrent()
        val keys = vm.ui.value.cards.map { it.key }
        assertEquals(39, keys.size)
        assertEquals(keys.distinct(), keys)
    }

    /** Filtered Trending as TMDB's pages give it: a step of five pages from [from], its matches by the step's first page. */
    private fun FakeSource.trendingMatching(matches: Map<Int, Int>) {
        answer = { q, from ->
            val titles = (1..(matches[from] ?: 0)).map { FakeSource.title(q.type, from * 100 + it) }
            Aged(DiscoverPage(titles, from + 4, DiscoverPage.MAX_PAGES, capped = true), Duration.ZERO)
        }
    }

    private val trendingDrama = DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(genre = 18))

    @Test
    fun `a step that adds no title goes on to the next, as nothing on the screen would ask for it`() = runTest {
        val f = Fixture()
        // Two titles match on pages 1 to 5, none on 6 to 10, three on 11 to 15.
        f.source.trendingMatching(mapOf(1 to 2, 11 to 3))
        val vm = vm(f, trendingDrama)
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 6, 11), f.pages())
        assertEquals(5, vm.ui.value.cards.size)
        assertEquals(Footer.None, vm.ui.value.footer)
    }

    @Test
    fun `a step of titles already shown goes on to the next`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            // TMDB's list moved a whole page between two requests: page 2 is page 1's titles again.
            Aged(FakeSource.page(q.type, if (from == 2) 1 else from, 3).copy(page = from), Duration.ZERO)
        }
        val vm = vm(f)
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 2, 3), f.pages())
        assertEquals(40, vm.ui.value.cards.size)
    }

    @Test
    fun `a first step that matches nothing goes on while TMDB has pages, and nothing matches only after the last`() = runTest {
        val some = Fixture()
        some.source.trendingMatching(mapOf(6 to 2))
        val vm = vm(some, trendingDrama)
        assertEquals(listOf(1, 6), some.pages())
        assertEquals(ListView.Shown, vm.ui.value.view)
        assertEquals(2, vm.ui.value.cards.size)
        store.clear()
        val none = Fixture()
        none.source.trendingMatching(emptyMap())
        val empty = vm(none, trendingDrama)
        assertEquals(listOf(1, 6, 11, 16, 21), none.pages())
        assertEquals(ListView.Empty(canClear = true), empty.ui.value.view)
    }

    @Test
    fun `a page further down that fails keeps the titles, and Try again goes on from it`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        f.source.answer = { _, _ -> throw TmdbException(TmdbError.Network("down")) }
        vm.nearEnd()
        runCurrent()
        assertEquals(20, vm.ui.value.cards.size)
        assertEquals(Footer.Failed(LOAD_FAILED), vm.ui.value.footer)
        // Near the end again asks nothing until Try again.
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 2), f.pages())
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        vm.retry()
        runCurrent()
        assertEquals(listOf(1, 2, 2), f.pages())
        assertEquals(40, vm.ui.value.cards.size)
        assertEquals(Footer.None, vm.ui.value.footer)
    }

    @Test
    fun `the first page failing fails the page, and Try again asks for it again`() = runTest {
        val f = Fixture()
        f.source.answer = { _, _ -> throw TmdbException(TmdbError.Server("503")) }
        val vm = vm(f)
        assertEquals(ListView.Failed(LOAD_FAILED), vm.ui.value.view)
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        vm.retry()
        runCurrent()
        assertEquals(ListView.Shown, vm.ui.value.view)
        assertEquals(listOf(1, 1), f.pages())
    }

    @Test
    fun `a filter picked stops the page being asked for and starts from the first`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        val held = CompletableDeferred<Unit>()
        f.source.answer = { q, from ->
            if (!q.filters.narrows) held.await()
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        vm.nearEnd()
        runCurrent()
        vm.pick(Filter.Genre, "18")
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(genre = 18)) to 1, f.source.asked.last())
        // Page 2 of the list without the filter never shows.
        assertEquals(20, vm.ui.value.cards.size)
        assertEquals(18, vm.ui.value.query.filters.genre)
        assertEquals(Footer.None, vm.ui.value.footer)
    }

    /**
     * A restart that keeps the query (TMDB's language changed, a key stored again) can't tell an old answer by it: the
     * step being asked is stopped, so its titles, in the old language, never join the new first page's.
     */
    @Test
    fun `a TMDB language change starts the list again from its first page, the step being asked stopped`() = runTest {
        val f = Fixture()
        val held = CompletableDeferred<Unit>()
        f.source.answer = { q, from ->
            if (from == 2) held.await()
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        vm.nearEnd()
        runCurrent()
        f.language.value = "es-ES"
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 1), f.pages())
        assertEquals(20, vm.ui.value.cards.size)
        assertEquals(2, f.source.genresAsked.size)
        vm.nearEnd()
        runCurrent()
        assertEquals(listOf(1, 2, 1, 2), f.pages())
        assertEquals(40, vm.ui.value.cards.size)
    }

    @Test
    fun `a key stored again starts the list again from its first page, the step being asked stopped`() = runTest {
        val f = Fixture()
        val held = CompletableDeferred<Unit>()
        f.source.answer = { q, from ->
            if (from == 2) held.await()
            Aged(FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        vm.nearEnd()
        runCurrent()
        f.setKey(true)
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 1), f.pages())
        assertEquals(20, vm.ui.value.cards.size)
        assertEquals(Footer.None, vm.ui.value.footer)
    }

    @Test
    fun `shows take the period, genre and language only, movies add the year, and a service except on Trending`() = runTest {
        val shows = vm(Fixture()).ui.value
        assertEquals(listOf(Filter.Period, Filter.Genre, Filter.Language), shows.pills.map { it.filter })
        assertEquals(false, shows.justWatch)
        store.clear()
        val movies = vm(Fixture(), popularMovies).ui.value
        assertEquals(listOf(Filter.Genre, Filter.Language, Filter.Year, Filter.Service), movies.pills.map { it.filter })
        assertEquals(listOf(ANY_SERVICE, "Service A", "Service B"), movies.pills.last().options.map { it.label })
        assertEquals(true, movies.justWatch)
        store.clear()
        val trendingMovies = vm(Fixture(), DiscoverQuery(MediaType.Movie, DiscoverList.Trending)).ui.value
        assertEquals(listOf(Filter.Period, Filter.Genre, Filter.Language, Filter.Year), trendingMovies.pills.map { it.filter })
    }

    @Test
    fun `each pill says what is chosen and offers the rest`() = runTest {
        val vm = vm(Fixture(), popularMovies)
        vm.pick(Filter.Genre, "35")
        vm.pick(Filter.Language, "ko")
        vm.pick(Filter.Year, YearFilter.Tens.name)
        vm.pick(Filter.Service, "9")
        runCurrent()
        val pills = vm.ui.value.pills.associateBy { it.filter }
        assertEquals("Comedy", pills.getValue(Filter.Genre).label)
        assertEquals(listOf(ALL_GENRES, "Drama", "Comedy"), pills.getValue(Filter.Genre).options.map { it.label })
        assertEquals("Korean", pills.getValue(Filter.Language).label)
        assertEquals("2010s", pills.getValue(Filter.Year).label)
        assertEquals(listOf("Service B"), pills.getValue(Filter.Service).options.filter { it.selected }.map { it.label })
        assertEquals("Popular in Comedy · Korean · 2010s · on Service B", vm.ui.value.summary)
    }

    @Test
    fun `a filtered list with nothing says so, and Clear filters brings the list back`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from ->
            Aged(if (q.filters.narrows) DiscoverPage(emptyList(), from, 1) else FakeSource.page(q.type, from, 3), Duration.ZERO)
        }
        val vm = vm(f)
        vm.pick(Filter.Period, Period.Today.name)
        vm.pick(Filter.Language, "ko")
        runCurrent()
        assertEquals(ListView.Empty(canClear = true), vm.ui.value.view)
        vm.clearFilters()
        runCurrent()
        assertEquals(ListView.Shown, vm.ui.value.view)
        // Trending's period stays: it picks TMDB's list, it narrows nothing.
        assertEquals(DiscoverFilters(period = Period.Today), vm.ui.value.query.filters)
    }

    @Test
    fun `the summary counts the titles shown that are collected`() = runTest {
        val f = Fixture()
        f.shows.value = setOf(101, 102, 103)
        val vm = vm(f, DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(genre = 18)))
        assertEquals("Drama trending this week · 3 already in your collection", vm.ui.value.summary)
        assertEquals(3, vm.ui.value.cards.count { it.collected })
    }

    @Test
    fun `TMDB saying to slow down is asked again after a wait`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        f.source.answer = { _, _ -> throw TmdbException(TmdbError.RateLimited()) }
        vm.nearEnd()
        runCurrent()
        assertEquals(Footer.Failed(BUSY), vm.ui.value.footer)
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ZERO) }
        advanceTimeBy(10_100)
        assertEquals(listOf(1, 2, 2), f.pages())
        assertEquals(40, vm.ui.value.cards.size)
    }

    @Test
    fun `the filters picked are kept with the screen's state`() = runTest {
        val saved = SavedStateHandle()
        vm(Fixture(), popularMovies, saved).pick(Filter.Year, YearFilter.Nineties.name)
        store.clear()
        val f = Fixture()
        val again = vm(f, popularMovies, saved)
        assertEquals(YearFilter.Nineties, again.ui.value.query.filters.year)
        assertEquals(YearFilter.Nineties, f.source.asked.single().first.filters.year)
    }

    @Test
    fun `without a key nothing is asked, and a key TMDB refused says so`() = runTest {
        val none = Fixture(key = false)
        assertEquals(KeyState.Missing, vm(none).ui.value.key)
        assertEquals(emptyList(), none.source.asked)
        store.clear()
        val refused = Fixture()
        refused.source.answer = { _, _ -> throw TmdbException(TmdbError.InvalidKey("Invalid API key")) }
        assertEquals(KeyState.Refused, vm(refused).ui.value.key)
    }

    @Test
    fun `an offline copy says how old it is`() = runTest {
        val f = Fixture()
        f.source.answer = { q, from -> Aged(FakeSource.page(q.type, from, 3), Duration.ofHours(3), offline = true) }
        assertEquals(Duration.ofHours(3), vm(f).ui.value.offline)
    }
}

package io.skjaere.usenet.app.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.collected
import io.skjaere.usenet.app.collection.model.collectedMovie
import io.skjaere.usenet.app.collection.model.movieRow
import io.skjaere.usenet.app.collection.model.movieSummary
import io.skjaere.usenet.app.collection.model.movieWatch
import io.skjaere.usenet.app.collection.model.showDetails
import io.skjaere.usenet.app.collection.model.showRow
import io.skjaere.usenet.app.collection.model.today
import io.skjaere.usenet.app.collection.model.watched
import io.skjaere.usenet.app.refresh.RefreshStatus
import io.skjaere.usenet.app.refresh.SchedulerClock
import io.skjaere.usenet.data.CollectionViewPrefs
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.Removal
import io.skjaere.usenet.data.ShowMark
import io.skjaere.usenet.data.WatchSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModelTest {
    @AfterEach
    fun main() {
        Dispatchers.resetMain()
    }

    private class FakeWrites : CollectionWrites {
        val removed = mutableListOf<Pair<List<Int>, List<Int>>>()
        val undone = mutableListOf<Any>()
        val marked = mutableListOf<Pair<List<ShowMark>, Boolean>>()
        val markedMovies = mutableListOf<Pair<List<MovieMark>, Boolean>>()
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun remove(shows: List<Int>, movies: List<Int>): Removal {
            hold?.await()
            removed += shows to movies
            return Removal(shows, movies, at = 7)
        }

        override suspend fun undoRemove(removal: Removal): Int = removal.shows.size.also { undone += removal }

        override suspend fun markShows(marks: List<ShowMark>, watched: Boolean): WatchSnapshot {
            hold?.await()
            marked += marks to watched
            return WatchSnapshot(emptyList(), marks.map { "show:${it.tmdbId}" }, 9)
        }

        override suspend fun markMovies(marks: List<MovieMark>, watched: Boolean): WatchSnapshot {
            markedMovies += marks to watched
            return WatchSnapshot(emptyList(), emptyList(), 9)
        }

        override suspend fun undoMarks(snapshot: WatchSnapshot): Int = 1.also { undone += snapshot }
    }

    private inner class Fixture(scope: TestScope, prefs: Flow<CollectionViewPrefs>? = null) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val shows = MutableStateFlow<List<ShowRow>?>(null)
        val movies = MutableStateFlow<List<MovieRow>?>(emptyList())
        val stored = MutableStateFlow(CollectionViewPrefs())
        val saved = mutableListOf<CollectionViewPrefs>()
        var holdSave: CompletableDeferred<Unit>? = null
        val writes = FakeWrites()
        val prioritized = mutableListOf<List<TitleKey>>()
        val status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
        val writeScope = CoroutineScope(SupervisorJob() + dispatcher)
        val deps = CollectionDeps(
            shows = shows, movies = movies, writes = writes, prioritize = { prioritized += it }, refreshStatus = status,
            prefs = prefs ?: stored,
            savePrefs = { change -> holdSave?.await(); stored.value = change(stored.value).also { saved += it } },
            hasKey = MutableStateFlow(true), showArtwork = MutableStateFlow(true), today = MutableStateFlow(today),
            writeScope = writeScope, clock = SchedulerClock(scope.testScheduler), dispatcher = dispatcher,
        )
        val store = ViewModelStore()

        init {
            Dispatchers.setMain(dispatcher)
        }

        fun vm(saved: SavedStateHandle = SavedStateHandle()): CollectionViewModel =
            ViewModelProvider(store, viewModelFactory { initializer { CollectionViewModel(deps, saved) } })[CollectionViewModel::class.java]
    }

    private val harbor = showRow(collected(1, "Harbor Lights", addedAt = 1), showDetails(1), listOf(watched(1, 1, 1, at = 50)))
    private val orbit = showRow(collected(2, "The Quiet Orbit", addedAt = 2), showDetails(2, counts = listOf(3), last = 1 to 3))
    private val copper = showRow(collected(3, "Copper Crown", addedAt = 3), null)

    private fun TestScope.subscribed(vm: CollectionViewModel) = backgroundScope.launch { vm.ui.collect {} }.also { runCurrent() }

    @Test
    fun `nothing shows until the saved view is read, then it opens as it was left`() = runTest {
        val f = Fixture(this, prefs = flow { delay(1_000); emit(CollectionViewPrefs(side = "Movies", movieFilter = "Watched", movieSort = "AZ")) })
        f.shows.value = listOf(harbor)
        val vm = f.vm()
        subscribed(vm)
        assertNull(vm.ui.value)
        advanceTimeBy(1_100)
        val ui = vm.ui.value!!
        assertEquals(Side.Movies, ui.side)
        assertEquals("Watched", ui.filterName)
        assertEquals("AZ", ui.sortName)
    }

    @Test
    fun `nothing shows until the rows are known either`() = runTest {
        val f = Fixture(this)
        val vm = f.vm()
        subscribed(vm)
        assertNull(vm.ui.value)
        f.shows.value = emptyList()
        runCurrent()
        assertEquals(0, vm.ui.value?.view?.total)
    }

    @Test
    fun `names only a newer version knows read as the defaults`() = runTest {
        val f = Fixture(this)
        f.stored.value = CollectionViewPrefs(side = "Archive", showFilter = "Bogus", showSort = "Rating")
        f.shows.value = listOf(harbor)
        val vm = f.vm()
        subscribed(vm)
        val ui = vm.ui.value!!
        assertEquals(Side.Shows, ui.side)
        assertEquals("All", ui.filterName)
        assertEquals("RecentlyWatched", ui.sortName)
    }

    @Test
    fun `a chip changes the list at once and is saved after`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        f.holdSave = CompletableDeferred()
        val vm = f.vm()
        subscribed(vm)
        vm.setFilter("Watching")
        runCurrent()
        assertEquals("Watching", vm.ui.value?.filterName)
        assertEquals(listOf("Harbor Lights"), vm.ui.value?.view?.rows?.map { it.text.name })
        assertEquals(emptyList(), f.saved)
        f.holdSave!!.complete(Unit)
        runCurrent()
        assertEquals("Watching", f.saved.last().showFilter)
        vm.setSort("AZ")
        vm.switchSide(Side.Movies)
        runCurrent()
        assertEquals(CollectionViewPrefs(side = "Movies", showFilter = "Watching", showSort = "AZ"), f.saved.last().copy(updatedAt = 0))
    }

    @Test
    fun `Mark watched marks what has aired of the shows with facts and says which weren't loaded`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val vm = f.vm()
        subscribed(vm)
        vm.startSelect()
        vm.selectAllVisible()
        runCurrent()
        assertEquals(3, vm.ui.value?.selection?.count)
        vm.markSelected(watched = true)
        runCurrent()
        val (marks, watched) = f.writes.marked.single()
        assertTrue(watched)
        assertEquals(mapOf(1 to 20, 2 to 3), marks.associate { it.tmdbId to it.episodes.size })
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), marks.first { it.tmdbId == 2 }.episodes)
        assertEquals("Marked 2 shows watched · 1 not loaded yet", vm.ui.value?.undo?.message)
        assertNull(vm.ui.value?.selection)
        vm.undo()
        runCurrent()
        assertEquals(1, f.writes.undone.size)
        assertNull(vm.ui.value?.undo)
        // Unwatched clears every state the shows have: facts aren't needed.
        vm.startSelect(copper.key)
        vm.markSelected(watched = false)
        runCurrent()
        assertEquals(listOf(3) to false, f.writes.marked.last().let { (m, w) -> m.map { it.tmdbId } to w })
        assertEquals("Marked “Copper Crown” unwatched", vm.ui.value?.undo?.message)
    }

    @Test
    fun `a big mark says it is busy until it is done`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        f.writes.hold = CompletableDeferred()
        val vm = f.vm()
        subscribed(vm)
        vm.startSelect()
        vm.selectAllVisible()
        vm.markSelected(watched = false)
        runCurrent()
        assertEquals("Marking 2 shows…", vm.ui.value?.busy)
        f.writes.hold!!.complete(Unit)
        runCurrent()
        assertNull(vm.ui.value?.busy)
        assertEquals("Marked 2 shows unwatched", vm.ui.value?.undo?.message)
    }

    @Test
    fun `Remove acts on what is selected and still shown, and Undo brings it back`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val vm = f.vm()
        subscribed(vm)
        vm.startSelect(harbor.key)
        vm.toggle(orbit.key)
        vm.toggle(copper.key)
        // A search hides two of the three.
        vm.openSearch()
        vm.type("orbit")
        runCurrent()
        assertEquals(1, vm.ui.value?.selection?.count)
        vm.removeSelected()
        runCurrent()
        assertEquals(listOf(listOf(2) to emptyList<Int>()), f.writes.removed)
        assertEquals("Removed “The Quiet Orbit”", vm.ui.value?.undo?.message)
        assertNull(vm.ui.value?.selection)
        vm.undo()
        runCurrent()
        assertEquals(listOf<Any>(Removal(listOf(2), emptyList(), 7)), f.writes.undone)
    }

    @Test
    fun `focus moves to the neighbour of what was removed`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val vm = f.vm()
        subscribed(vm)
        // Recently watched: Harbor Lights, then by when added: Copper Crown, The Quiet Orbit.
        assertEquals(listOf(1, 3, 2), vm.ui.value?.view?.rows?.map { it.key.tmdbId })
        vm.startSelect()
        vm.toggle(copper.key)
        vm.toggle(harbor.key)
        vm.removeSelected()
        runCurrent()
        assertEquals("Removed 2 shows", vm.ui.value?.undo?.message)
        assertEquals(orbit.key, vm.focusAfter.value)
        vm.focusTaken()
        assertNull(vm.focusAfter.value)
        vm.removeOne(orbit.key)
        runCurrent()
        assertEquals("Removed “The Quiet Orbit”", vm.ui.value?.undo?.message)
    }

    /** The marked rows stay: a remote's focus goes back to the first of them (in the list's order) as the Undo bar goes. */
    @Test
    fun `a mark hands a remote's focus back to the first row marked`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val vm = f.vm()
        subscribed(vm)
        // Recently watched: Harbor Lights, then by when added: Copper Crown, The Quiet Orbit.
        vm.startSelect(orbit.key)
        vm.toggle(copper.key)
        vm.markSelected(watched = false)
        runCurrent()
        assertEquals("Marked 2 shows unwatched", vm.ui.value?.undo?.message)
        assertEquals(copper.key, vm.focusAfter.value)
    }

    /** A poster's menu marks its one title as select mode's Mark does: Undo offered, the focus back on it after; no select mode. */
    @Test
    fun `one title marked from its menu offers Undo and hands the focus back to it`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val film = movieRow(collectedMovie(11, "Copper Field"), movieSummary(11), movieWatch(11, watched = true))
        f.movies.value = listOf(film)
        val vm = f.vm()
        subscribed(vm)
        vm.markOne(orbit.key, watched = true)
        runCurrent()
        val (marks, watched) = f.writes.marked.single()
        assertTrue(watched)
        assertEquals(listOf(2 to listOf(1 to 1, 1 to 2, 1 to 3)), marks.map { it.tmdbId to it.episodes })
        assertEquals("Marked “The Quiet Orbit” watched", vm.ui.value?.undo?.message)
        assertEquals(orbit.key, vm.focusAfter.value)
        assertNull(vm.ui.value?.selection)
        vm.undo()
        runCurrent()
        assertEquals(1, f.writes.undone.size)
        // A title not shown (another side's) isn't marked.
        vm.markOne(film.key, watched = false)
        runCurrent()
        assertEquals(emptyList(), f.writes.markedMovies)
        vm.switchSide(Side.Movies)
        runCurrent()
        vm.markOne(film.key, watched = false)
        runCurrent()
        assertEquals(listOf(listOf(11) to false), f.writes.markedMovies.map { (m, w) -> m.map { it.tmdbId } to w })
        assertEquals("Marked “Copper Field” unwatched", vm.ui.value?.undo?.message)
        assertEquals(film.key, vm.focusAfter.value)
    }

    @Test
    fun `writes run in the app's scope, so leaving the screen doesn't stop them`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        f.writes.hold = CompletableDeferred()
        val vm = f.vm()
        subscribed(vm)
        vm.startSelect(orbit.key)
        vm.removeSelected()
        runCurrent()
        f.store.clear()
        f.writes.hold!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf(2) to emptyList<Int>()), f.writes.removed)
        f.writeScope.cancel()
    }

    @Test
    fun `search and selection survive a new view model on the same saved state`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        val handle = SavedStateHandle()
        val first = f.vm(handle)
        subscribed(first)
        first.openSearch()
        first.type("c")
        first.startSelect(copper.key)
        runCurrent()
        f.store.clear()
        val second = f.vm(handle)
        subscribed(second)
        val ui = second.ui.value!!
        assertEquals(true to "c", ui.searchOpen to ui.text)
        assertEquals(setOf(copper.key), ui.selection?.keys)
    }

    /** A remote's focus goes back to the row a page was opened from, on coming back from it: once, not on every later visit to the tab. */
    @Test
    fun `the row a page was opened from is handed back once, also to a new view model`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        val handle = SavedStateHandle()
        f.vm(handle).opened(orbit.key)
        f.store.clear()
        val second = f.vm(handle)
        assertEquals(orbit.key, second.takeOpened())
        assertNull(second.takeOpened())
    }

    /** The field shows what was typed at once: the list's state is rebuilt off the main thread, a keystroke later. */
    @Test
    fun `what is typed is there at once for the field, before the list is rebuilt`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        val vm = f.vm()
        subscribed(vm)
        vm.type("o")
        vm.type("or")
        assertEquals("or", vm.typed.value)
        assertEquals("", vm.ui.value?.text)
        runCurrent()
        assertEquals("or", vm.ui.value?.text)
        vm.closeSearch()
        assertEquals("", vm.typed.value)
    }

    @Test
    fun `switching sides leaves select mode, and closing the search clears it`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        f.movies.value = listOf(movieRow(collectedMovie(9, "Lantern Field"), movieSummary(9)))
        val vm = f.vm()
        subscribed(vm)
        vm.openSearch()
        vm.type("lantern")
        vm.startSelect(harbor.key)
        vm.switchSide(Side.Movies)
        runCurrent()
        assertNull(vm.ui.value?.selection)
        assertEquals(listOf("Lantern Field"), vm.ui.value?.view?.rows?.map { it.text.name })
        vm.closeSearch()
        runCurrent()
        assertEquals(false to "", vm.ui.value!!.let { it.searchOpen to it.text })
    }

    /** The TV's Shows | Movies switch says how many each side has: every title, whatever the search or the chip shows. */
    @Test
    fun `both sides' totals, not what the search or the chip shows`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit, copper)
        f.movies.value = null
        val vm = f.vm()
        subscribed(vm)
        assertEquals(mapOf(Side.Shows to 3), vm.ui.value?.totals)
        f.movies.value = listOf(movieRow(collectedMovie(9, "Lantern Field"), movieSummary(9)), movieRow(collectedMovie(10, "Salt Road"), movieSummary(10)))
        vm.setFilter("Watching")
        vm.type("harbor")
        runCurrent()
        assertEquals(1, vm.ui.value?.view?.rows?.size)
        assertEquals(mapOf(Side.Shows to 3, Side.Movies to 2), vm.ui.value?.totals)
        vm.switchSide(Side.Movies)
        runCurrent()
        assertEquals(mapOf(Side.Shows to 3, Side.Movies to 2), vm.ui.value?.totals)
    }

    @Test
    fun `the rows on screen are handed to the refresher, and its pause shows as a note`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor)
        val vm = f.vm()
        subscribed(vm)
        vm.visible(listOf(harbor.key))
        assertEquals(listOf(listOf(harbor.key)), f.prioritized)
        f.status.value = RefreshStatus.Paused(RefreshStatus.Reason.Offline, 5)
        runCurrent()
        assertEquals("TMDB unreachable · showing saved info", vm.ui.value?.note)
    }

    /** A list that counts the rows read from it: each rebuild of the side's view reads them all. */
    private class Counted<T>(private val rows: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size: Int get() = rows.size
        override fun get(index: Int): T = rows[index].also { reads++ }
    }

    /** A fill's count of what is left changes with every fetch and shows nowhere: only a pause's note rebuilds the view. */
    @Test
    fun `the refresher's progress rebuilds nothing, a pause does`() = runTest {
        val f = Fixture(this)
        val rows = Counted(listOf(harbor, orbit, copper))
        f.shows.value = rows
        val vm = f.vm()
        subscribed(vm)
        val read = rows.reads
        (300 downTo 1).forEach { left ->
            f.status.value = RefreshStatus.Running(left)
            runCurrent()
        }
        f.status.value = RefreshStatus.Idle
        runCurrent()
        assertEquals(read, rows.reads)
        f.status.value = RefreshStatus.Paused(RefreshStatus.Reason.RateLimited, 5)
        runCurrent()
        assertEquals("TMDB is busy · showing saved info", vm.ui.value?.note)
        assertTrue(rows.reads > read)
    }

    @Test
    fun `a title just added is brought into view, the filters that hide it cleared`() = runTest {
        val f = Fixture(this)
        f.stored.value = CollectionViewPrefs(showFilter = "Watching", movieFilter = "Watched")
        f.shows.value = listOf(harbor, orbit)
        val vm = f.vm()
        subscribed(vm)
        val index = async { vm.reveal(orbit.key) }
        runCurrent()
        assertEquals(1, index.await())
        assertEquals("All", vm.ui.value?.filterName)
        assertEquals(orbit.key, vm.ui.value?.highlight)
        assertEquals("Added The Quiet Orbit to your collection", vm.ui.value?.announce)
        advanceTimeBy(3_000)
        assertNull(vm.ui.value?.highlight)
        // A movie the library hasn't told of yet: the other side, then wait for it.
        val movie = async { vm.reveal(TitleKey.movie(9)) }
        advanceTimeBy(1_000)
        assertEquals(Side.Movies, vm.ui.value?.side)
        f.movies.value = listOf(movieRow(collectedMovie(9, "Lantern Field"), movieSummary(9)))
        runCurrent()
        assertEquals(0, movie.await())
        // Never there: given up.
        val never = async { vm.reveal(TitleKey.movie(99)) }
        advanceTimeBy(3_100)
        assertNull(never.await())
    }

    /** "In collection ›" goes to a title there already: highlighted and told, but not said to be just added. */
    @Test
    fun `a title revealed that was there already isn't said to be just added`() = runTest {
        val f = Fixture(this)
        f.shows.value = listOf(harbor, orbit)
        val vm = f.vm()
        subscribed(vm)
        assertEquals(1, async { vm.reveal(orbit.key, added = false) }.also { runCurrent() }.await())
        val there = vm.ui.value!!
        assertEquals(Triple(orbit.key, false, "The Quiet Orbit, in your collection"), Triple(there.highlight, there.highlightAdded, there.announce))
        advanceTimeBy(3_000)
        assertEquals(0, async { vm.reveal(harbor.key) }.also { runCurrent() }.await())
        val added = vm.ui.value!!
        assertEquals(Triple(harbor.key, true, "Added Harbor Lights to your collection"), Triple(added.highlight, added.highlightAdded, added.announce))
    }

    @Test
    fun `a search that hides the title is cleared, and a filter that shows it is kept`() = runTest {
        val f = Fixture(this)
        f.stored.value = CollectionViewPrefs(showFilter = "Watching")
        f.shows.value = listOf(harbor, orbit)
        val vm = f.vm()
        subscribed(vm)
        vm.openSearch()
        vm.type("orbit")
        runCurrent()
        assertEquals(emptyList(), vm.ui.value?.view?.rows)
        val index = async { vm.reveal(harbor.key) }
        runCurrent()
        assertEquals(0, index.await())
        assertEquals("Watching", vm.ui.value?.filterName)
        assertEquals(false to "", vm.ui.value!!.let { it.searchOpen to it.text })
        // A search the title matches stays; the filter that hides it goes.
        vm.type("quiet")
        runCurrent()
        assertEquals(0, async { vm.reveal(orbit.key) }.also { runCurrent() }.await())
        assertEquals("All", vm.ui.value?.filterName)
        assertEquals("quiet", vm.ui.value?.text)
    }
}

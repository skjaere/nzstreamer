package io.skjaere.usenet.app.download

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Select mode's Delete and the Undo bar of the Downloads page and a show's downloads (L379), over fakes of the runner and
 * the host: what is removed and when its files go, and what Undo brings back, the host asked for from the visible app
 * after it (L335), as a download removed as it ran ended its host's walk. Invented titles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadEditsTest {
    @AfterEach
    fun main() = Dispatchers.resetMain()

    @Test
    fun `Undo brings the downloads back, then asks for the host from here, as removing the running one ended its walk`() = runTest {
        val fakes = PageFakes()
        val edits = DownloadEdits(fakes.deps(backgroundScope), backgroundScope)
        edits.remove(listOf(episode("run", 4, DownloadState.Running)), keepFor = 10_000)
        runCurrent()
        assertEquals(listOf("remove [run] 11000"), fakes.calls)
        edits.undo()
        runCurrent()
        assertEquals(listOf("remove [run] 11000", "undo [run]", "schedule ${ScheduleCause.Resume}"), fakes.calls, "back in the queue before the host is asked")
    }

    @Test
    fun `a Delete asked as the page goes is still made, the runner's calls being the app's own work`() = runTest {
        val fakes = PageFakes()
        // The running download stops at its safe offset first, which takes a moment.
        val stopped = CompletableDeferred<Unit>()
        fakes.removing = { stopped.await() }
        val page = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val edits = DownloadEdits(fakes.deps(backgroundScope), page)
        edits.remove(listOf(episode("run", 4, DownloadState.Running)), keepFor = 10_000)
        runCurrent()
        // Back: the page's view model is cleared while the runner stops the download.
        page.cancel()
        stopped.complete(Unit)
        runCurrent()
        assertEquals(listOf("remove [run] 11000"), fakes.calls, "hidden and deleted after its Undo, not left running")
    }

    @Test
    fun `a Pause asked as the page goes still holds the queue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        val stopped = CompletableDeferred<Unit>()
        fakes.pausing = { stopped.await() }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { DownloadsViewModel(fakes.deps(backgroundScope), SavedStateHandle()) } })[DownloadsViewModel::class.java]
        vm.pause()
        runCurrent()
        store.clear()
        stopped.complete(Unit)
        runCurrent()
        assertEquals(listOf("pause"), fakes.calls)
    }

    @Test
    fun `a choice's pick replaces the download's releases, counted at what the sheet reckoned it needs`() = runTest {
        val fakes = PageFakes()
        val row = episode("run", 4, DownloadState.Running)
        fakes.rows[row.id] = row
        val candidates = listOf(DownloadCandidate("idx-a", "pack", "Harbor.Lights.S02.1080p.WEB-DL.H.264-WREN", size = 11_200_000_000))
        assertEquals(row, fakes.deps(backgroundScope).replacing(row).enqueue(DownloadTarget.episode(HL, "Harbor Lights", 2, 4), candidates, 1_400_000_000, null))
        assertEquals(listOf("replace run 1400000000"), fakes.calls)
    }

    @Test
    fun `a choice weighs the room free with the download's own counted free, as its replace gives that back`() = runTest {
        val fakes = PageFakes()
        val row = episode("run", 4, DownloadState.Running)
        val sheet = fakes.deps(backgroundScope).replacing(row)
        sheet.space()
        assertEquals(listOf<String?>("run"), fakes.spaceFor)
        // Nothing asked of Android: the download asked when it was queued.
        assertFalse(sheet.askDue())
        // The page's own room, outside a choice, counts every download.
        fakes.deps(backgroundScope).space(null)
        assertEquals(listOf("run", null), fakes.spaceFor)
    }

    @Test
    fun `Undo of a show's last download keeps its page, which closes once a removal's bar has gone`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        val done = episode("e1", 1, DownloadState.Done)
        fakes.items.value = listOf(DownloadRunner.Item(done, 0, null))
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { DownloadsShowViewModel(fakes.deps(backgroundScope), HL, SavedStateHandle()) } })[DownloadsShowViewModel::class.java]
        backgroundScope.launch { vm.gone.collect {} }
        runCurrent()
        assertFalse(vm.gone.value)

        // Removed: hidden at once, the Undo bar up.
        vm.edits.remove(listOf(done), keepFor = 10_000)
        fakes.items.value = emptyList()
        runCurrent()
        assertFalse(vm.gone.value, "the bar is up")
        // Undo: the bar goes at once, and the row comes back only once the runner has said so.
        vm.edits.undo()
        runCurrent()
        assertFalse(vm.gone.value, "brought back, not closed")
        fakes.items.value = listOf(DownloadRunner.Item(done, 0, null))
        runCurrent()
        assertFalse(vm.gone.value)

        // Removed again and left: once its bar has gone, nothing is left to show.
        vm.edits.remove(listOf(done), keepFor = 10_000)
        fakes.items.value = emptyList()
        vm.edits.shown(10_000)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(vm.gone.value)
        store.clear()
    }

    @Test
    fun `a notification's Play plays a finished download once for each ask, a page remade on the same state playing nothing more`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        fakes.rows["done"] = episode("done", 3, DownloadState.Done)
        fakes.rows["run"] = episode("run", 4, DownloadState.Running)
        val saved = SavedStateHandle()
        fun page(state: SavedStateHandle = saved) = ViewModelProvider(
            ViewModelStore().also { stores += it },
            viewModelFactory { initializer { DownloadsViewModel(fakes.deps(backgroundScope), state) } },
        )[DownloadsViewModel::class.java]
        val first = page()
        first.take("ask-1", choose = null, play = "done")
        runCurrent()
        assertEquals(listOf("play done"), fakes.calls)
        // A rotation remakes the page over its saved state, its route's ask with it: it has played already.
        page().take("ask-1", choose = null, play = "done")
        runCurrent()
        assertEquals(listOf("play done"), fakes.calls)
        // Another Play to the page already on top, which keeps its model: played again.
        first.take("ask-2", choose = null, play = "done")
        runCurrent()
        assertEquals(listOf("play done", "play done"), fakes.calls)
        // One not finished, or gone, plays nothing; nor does a page opened with no ask.
        page(SavedStateHandle()).take("ask-3", choose = null, play = "run")
        page(SavedStateHandle()).take("ask-4", choose = null, play = "gone")
        page(SavedStateHandle()).take(null, choose = null, play = "done")
        runCurrent()
        assertEquals(listOf("play done", "play done"), fakes.calls)
        stores.forEach { it.clear() }
    }

    /**
     * L437: a show's notification's Play opens the show's downloads, which play its next downloaded episode (as Resume at
     * the page's top would) once for each ask, a page remade on the same state playing nothing more, and a later Play to
     * the page already on top playing it again; opened by its tap, nothing plays.
     */
    @Test
    fun `a show's notification's Play plays its next downloaded episode once for each ask, its tap nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        fakes.items.value = (1..3).map { DownloadRunner.Item(episode("e$it", it, DownloadState.Done), 0, null) }
        // S02E01 watched: S02E02 is next.
        fakes.states = mapOf((2 to 1) to WatchState(WatchKey.episode(HL, 2, 1), WatchKind.Episode, showTmdbId = HL, season = 2, episode = 1, title = "Harbor Lights S02E01", watched = true, updatedAt = 1))
        val saved = SavedStateHandle()
        fun page(state: SavedStateHandle = saved) = ViewModelProvider(
            ViewModelStore().also { stores += it },
            viewModelFactory { initializer { DownloadsShowViewModel(fakes.deps(backgroundScope), HL, state) } },
        )[DownloadsShowViewModel::class.java]
        // Opened by its tap first, or from Home's row: nothing plays.
        val onTop = page()
        onTop.take(null, play = false)
        runCurrent()
        assertEquals(emptyList<String>(), fakes.calls)
        // The Play to the page on top, which keeps its model: the next one played.
        onTop.take("ask-1", play = true)
        runCurrent()
        assertEquals(listOf("play e2"), fakes.calls)
        // A rotation remakes the page over its saved state, its route's ask with it: it has played already.
        page().take("ask-1", play = true)
        runCurrent()
        assertEquals(listOf("play e2"), fakes.calls)
        // Another Play: played again.
        onTop.take("ask-2", play = true)
        runCurrent()
        assertEquals(listOf("play e2", "play e2"), fakes.calls)
        // Every downloaded episode watched: nothing to play.
        fakes.states = (1..3).associate { (2 to it) to WatchState(WatchKey.episode(HL, 2, it), WatchKind.Episode, showTmdbId = HL, season = 2, episode = it, title = "Harbor Lights S02E0$it", watched = true, updatedAt = 1) }
        page(SavedStateHandle()).take("ask-3", play = true)
        runCurrent()
        assertEquals(listOf("play e2", "play e2"), fakes.calls)
        stores.forEach { it.clear() }
    }

    @Test
    fun `a notification's Choose release opens a download's list once for each ask, and none for a finished one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        fakes.rows["failed"] = episode("failed", 4, DownloadState.Failed)
        fakes.rows["done"] = episode("done", 3, DownloadState.Done)
        val saved = SavedStateHandle()
        fun page(state: SavedStateHandle = saved) = ViewModelProvider(
            ViewModelStore().also { stores += it },
            viewModelFactory { initializer { DownloadsViewModel(fakes.deps(backgroundScope), state) } },
        )[DownloadsViewModel::class.java]
        val first = page()
        first.take("ask-1", choose = "failed", play = null)
        runCurrent()
        val choosing = assertIs<DownloadChoice.State.Choosing>(first.choice.state.value)
        assertEquals("failed", choosing.row.id)
        // Remade on the same state: not opened again.
        val again = page()
        again.take("ask-1", choose = "failed", play = null)
        runCurrent()
        assertEquals(DownloadChoice.State.None, again.choice.state.value)
        // Another Choose release to the page on top, its list closed meanwhile: opened again.
        first.choice.close()
        runCurrent()
        assertEquals(DownloadChoice.State.None, first.choice.state.value)
        first.take("ask-2", choose = "failed", play = null)
        runCurrent()
        assertEquals("failed", assertIs<DownloadChoice.State.Choosing>(first.choice.state.value).row.id)
        // A finished one has no releases to choose.
        val done = page(SavedStateHandle())
        done.take("ask-3", choose = "done", play = null)
        runCurrent()
        assertEquals(DownloadChoice.State.None, done.choice.state.value)
        stores.forEach { it.clear() }
    }

    /** The page's view models' stores, cleared at each case's end. */
    private val stores = mutableListOf<ViewModelStore>()

    internal companion object {
        const val HL = 990_617

        fun episode(id: String, episode: Int, state: DownloadState, position: Long = 0) = Download(
            id = id, watchKey = WatchKey.episode(HL, 2, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = HL, season = 2,
            episode = episode, state = state, position = position, indexerSize = 1_000_000_000, createdAt = 1, updatedAt = 1,
        )
    }
}

/** What the Downloads page's deps were asked, faked, in turn in [calls]; [rows] what [DownloadsDeps.get] reads. */
internal class PageFakes {
    val calls = mutableListOf<String>()
    val rows = mutableMapOf<String, Download>()
    val online = MutableStateFlow(true)

    /** The show's episodes' watch states, by season and episode, as [DownloadsDeps.episodeStates] reads them. */
    var states: Map<Pair<Int, Int>, WatchState> = emptyMap()

    /** Which download's own room each read of the space counted free ([DownloadsDeps.space]'s except). */
    val spaceFor = mutableListOf<String?>()

    /** The runner's queue as the page reads it. */
    val items = MutableStateFlow<List<DownloadRunner.Item>>(emptyList())

    /** What a Pause and a Remove wait for before the runner has done them: the running download stopping. */
    var pausing: suspend () -> Unit = {}
    var removing: suspend () -> Unit = {}

    fun deps(outlive: CoroutineScope) = DownloadsDeps(
        items = items,
        space = { except ->
            spaceFor += except
            DownloadSpace(available = 20_000_000_000, used = 0, queued = 0)
        },
        prefs = flowOf(DownloadPrefs()),
        network = MutableStateFlow(NetworkNow(usable = true, unmetered = true, wifi = true)),
        online = online,
        restricted = { false },
        episodeStates = { flowOf(states) },
        movieWatches = flowOf(emptyList()),
        showArtwork = flowOf(true),
        pause = {
            pausing()
            calls += "pause"
        },
        resume = { calls += "resume" },
        remove = { ids, keepFor ->
            removing()
            calls += "remove $ids $keepFor"
        },
        undoRemove = { ids -> calls += "undo $ids" },
        move = { id, up -> calls += "move $id ${if (up) "up" else "down"}" },
        replace = { id, _, need, _ -> calls += "replace $id $need" },
        retry = { id -> calls += "retry $id" },
        schedule = { cause -> calls += "schedule $cause" },
        lookUp = { show, season, episode ->
            EpisodePlay(
                showTmdbId = show, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = show), season = season,
                episode = episode, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
            )
        },
        search = DownloadSheetDeps(
            findEpisode = { request, _ -> flowOf(FinderEvent.Finished(FindResult(request, emptyList(), emptyList(), emptyList()))) },
            findMovie = { _, context -> flowOf(MovieFind(null, emptyList(), context)) },
            findSeason = { _, _ -> error("No season's search on the Downloads page") },
            context = { DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(), "en") },
            space = { DownloadSpace(available = 20_000_000_000, used = 0, queued = 0) },
            rows = { rows.values.toList() },
            prefs = { DownloadPrefs() },
            network = { NetworkNow(usable = true, unmetered = true, wifi = true) },
            askDue = { false },
            enqueue = { _, _, _, _ -> null },
            enqueueAll = { error("No season queued from the Downloads page") },
            enqueueImport = { _, _, _, _ -> error("No imported file queued from the Downloads page") },
            beforeDownload = {},
            schedule = {},
            outlive = outlive,
            grab = { error("No pack opened from the Downloads page's edits") },
            prepare = { _, _, _ -> error("No pack opened from the Downloads page's edits") },
        ),
        get = { rows[it] },
        play = { calls += "play ${it.id}" },
        mark = { row, watched -> calls += "mark ${row.id} $watched" },
        work = outlive,
        undoMillis = 10_000,
    )
}

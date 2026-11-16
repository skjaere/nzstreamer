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
        assertEquals(row, fakes.deps(backgroundScope).replacing(row).enqueue(DownloadTarget.episode(HL, "Harbor Lights", 2, 4), candidates, 1_400_000_000))
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
        val vm = ViewModelProvider(store, viewModelFactory { initializer { DownloadsShowViewModel(fakes.deps(backgroundScope), HL) } })[DownloadsShowViewModel::class.java]
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
    fun `a notification's Play plays a finished download once, a page remade on the same state playing nothing more`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        fakes.rows["done"] = episode("done", 3, DownloadState.Done)
        fakes.rows["run"] = episode("run", 4, DownloadState.Running)
        val saved = SavedStateHandle()
        fun page(choose: String? = null, play: String? = null, state: SavedStateHandle = saved) = ViewModelProvider(
            ViewModelStore().also { stores += it },
            viewModelFactory { initializer { DownloadsViewModel(fakes.deps(backgroundScope), state, choose, play) } },
        )[DownloadsViewModel::class.java]
        page(play = "done")
        runCurrent()
        assertEquals(listOf("play done"), fakes.calls)
        // A rotation remakes the page over its saved state: it has played already.
        page(play = "done")
        runCurrent()
        assertEquals(listOf("play done"), fakes.calls)
        // One not finished, or gone, plays nothing.
        page(play = "run", state = SavedStateHandle())
        page(play = "gone", state = SavedStateHandle())
        runCurrent()
        assertEquals(listOf("play done"), fakes.calls)
        stores.forEach { it.clear() }
    }

    @Test
    fun `a notification's Choose release opens a download's list once, and none for a finished one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakes = PageFakes()
        fakes.rows["failed"] = episode("failed", 4, DownloadState.Failed)
        fakes.rows["done"] = episode("done", 3, DownloadState.Done)
        val saved = SavedStateHandle()
        fun page(choose: String?, state: SavedStateHandle = saved) = ViewModelProvider(
            ViewModelStore().also { stores += it },
            viewModelFactory { initializer { DownloadsViewModel(fakes.deps(backgroundScope), state, choose = choose) } },
        )[DownloadsViewModel::class.java]
        val first = page("failed")
        runCurrent()
        val choosing = assertIs<DownloadChoice.State.Choosing>(first.choice.state.value)
        assertEquals("failed", choosing.row.id)
        // Remade on the same state: not opened again.
        val again = page("failed")
        runCurrent()
        assertEquals(DownloadChoice.State.None, again.choice.state.value)
        // A finished one has no releases to choose.
        val done = page("done", SavedStateHandle())
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
        episodeStates = { flowOf(emptyMap()) },
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
        replace = { id, _, need -> calls += "replace $id $need" },
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
            context = { DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(), "en") },
            space = { DownloadSpace(available = 20_000_000_000, used = 0, queued = 0) },
            rows = { rows.values.toList() },
            prefs = { DownloadPrefs() },
            network = { NetworkNow(usable = true, unmetered = true, wifi = true) },
            askDue = { false },
            enqueue = { _, _, _ -> null },
            beforeDownload = {},
            schedule = {},
            outlive = outlive,
        ),
        get = { rows[it] },
        play = { calls += "play ${it.id}" },
        mark = { row, watched -> calls += "mark ${row.id} $watched" },
        work = outlive,
        undoMillis = 10_000,
    )
}

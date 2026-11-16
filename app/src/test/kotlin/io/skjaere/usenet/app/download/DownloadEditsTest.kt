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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

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

    /** What a Pause and a Remove wait for before the runner has done them: the running download stopping. */
    var pausing: suspend () -> Unit = {}
    var removing: suspend () -> Unit = {}

    fun deps(outlive: CoroutineScope) = DownloadsDeps(
        items = flowOf(emptyList()),
        space = { DownloadSpace(available = 20_000_000_000, used = 0, queued = 0) },
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
        replace = { id, _ -> calls += "replace $id" },
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
            enqueue = { _, _ -> null },
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

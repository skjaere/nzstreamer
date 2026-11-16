package io.skjaere.usenet.app.download

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        pause = { calls += "pause" },
        resume = { calls += "resume" },
        remove = { ids, keepFor -> calls += "remove $ids $keepFor" },
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
        undoMillis = 10_000,
    )
}

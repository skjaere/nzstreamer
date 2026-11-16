package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A title's download as the Show page's rows, the Movie page's button and Search's card read it (L384): only what they say,
 * from the runner's row and its live bytes; and what their entries do, as the Downloads page does (L379, L380): Pause and
 * Resume the queue's, Download again, and Remove with the Undo bar, its files going a second after the bar has.
 */
class TitleDownloadsTest {
    private val saved = Locale.getDefault()

    // Sizes are said with the device's decimal mark: these read as written here.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private fun row(
        id: String,
        episode: Int,
        state: DownloadState,
        size: Long? = null,
        indexerSize: Long = 1_400_000_000,
        file: Boolean = false,
        offset: Long = 0,
        created: Long = 1,
    ) = Download(
        id = id, watchKey = WatchKey.episode(HL, 2, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = HL, season = 2,
        episode = episode, state = state, position = created, size = size, indexerSize = indexerSize, safeOffset = offset,
        relativePath = if (file) "downloads/$id/v.mkv" else null, createdAt = created, updatedAt = created,
    )

    @Test
    fun `a title's download says its state, how far once its file is made, its size, its missing parts and its tries`() {
        val written = row("a", 4, DownloadState.Running, size = 1_300_000_000, file = true, offset = 100_000_000)
        // The live write's bytes, not the record's: 546 of 1300 MB.
        assertEquals(TitleDownload("a", DownloadState.Running, null, 42, 1_300_000_000), TitleDownload.of(DownloadRunner.Item(written, 546_000_000, 8_000_000)))
        // No file made yet: no share of it, and the indexer's size until Resolve has the exact one.
        assertEquals(TitleDownload("b", DownloadState.Queued, null, null, 1_400_000_000), TitleDownload.of(DownloadRunner.Item(row("b", 5, DownloadState.Queued), 0, null)))
        val done = row("c", 3, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000)
            .copy(gaps = listOf(DownloadGap(10, 20), DownloadGap(40, 50)), tried = listOf("x", "y"))
        assertEquals(TitleDownload("c", DownloadState.Done, null, 100, 1_300_000_000, partsMissing = 2, tried = 2), TitleDownload.of(DownloadRunner.Item(done, done.safeOffset, null)))
    }

    @Test
    fun `a show's downloads by episode, the latest added for an episode downloaded twice`() {
        val items = listOf(
            row("old", 4, DownloadState.Failed, created = 1),
            row("new", 4, DownloadState.Queued, created = 2),
            row("e5", 5, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000),
        ).map { DownloadRunner.Item(it, it.safeOffset, null) }
        val byEpisode = TitleDownloads.byEpisode(items)
        assertEquals(setOf(2 to 4, 2 to 5), byEpisode.keys)
        assertEquals("new", byEpisode.getValue(2 to 4).id)
        assertEquals(DownloadState.Done, byEpisode.getValue(2 to 5).state)
    }

    /** The runner's calls and the host's schedule, as the actions make them, in order. */
    private val calls = mutableListOf<String>()

    private fun TestScope.actions(): TitleDownloadActions {
        val rows = mapOf("a" to row("a", 4, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000))
        val deps = TitleDownloadActions.Deps(
            get = { rows[it] },
            pause = { calls += "pause" },
            resume = { calls += "resume" },
            remove = { ids, keepFor -> calls += "remove $ids $keepFor" },
            undoRemove = { ids -> calls += "undo $ids" },
            retry = { id -> calls += "retry $id" },
            schedule = { cause -> calls += "schedule $cause" },
            undoMillis = 10_000,
        )
        return TitleDownloadActions(deps, backgroundScope, backgroundScope)
    }

    private fun d(state: DownloadState) = TitleDownload("a", state)

    @Test
    fun `Pause and Resume are the queue's, each asking for the host from here, and Download again tries it again`() = runTest {
        val actions = actions()
        actions.take(DownloadEntry.Pause, d(DownloadState.Running), keepFor = 10_000)
        actions.take(DownloadEntry.Resume, d(DownloadState.Paused), keepFor = 10_000)
        actions.take(DownloadEntry.DownloadAgain, d(DownloadState.Failed), keepFor = 10_000)
        // The sheet and the page are the page's own: nothing of the runner's.
        actions.take(DownloadEntry.Download, d(DownloadState.Done), keepFor = 10_000)
        actions.take(DownloadEntry.OpenDownloads, d(DownloadState.Done), keepFor = 10_000)
        runCurrent()
        assertEquals(listOf("pause", "resume", "schedule ${ScheduleCause.Resume}", "retry a", "schedule ${ScheduleCause.Download}"), calls)
    }

    @Test
    fun `Remove hides it at once with the Undo bar, its files going a second after the bar, and Undo brings it back`() = runTest {
        val actions = actions()
        actions.take(DownloadEntry.Remove, d(DownloadState.Done), keepFor = 12_000)
        runCurrent()
        assertEquals(listOf("remove [a] 13000"), calls)
        assertEquals("Deleted Harbor Lights S02E04 · 1.3 GB", actions.offer.value?.message)
        actions.undo()
        runCurrent()
        assertEquals(listOf("remove [a] 13000", "undo [a]"), calls)
        assertNull(actions.offer.value)
        // The bar goes by itself once it has shown its time.
        actions.take(DownloadEntry.Remove, d(DownloadState.Done), keepFor = 12_000)
        runCurrent()
        actions.shown(12_000)
        advanceTimeBy(12_001)
        assertNull(actions.offer.value)
    }

    private companion object {
        const val HL = 990_717
    }
}

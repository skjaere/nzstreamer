package io.skjaere.usenet.app.download

import androidx.media3.common.C
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.STREAM_INSTEAD
import io.skjaere.usenet.app.play.STREAM_INSTEAD_QUESTION
import io.skjaere.usenet.app.play.TRY_ANOTHER
import io.skjaere.usenet.app.play.MISSING_PARTS_QUESTION
import io.skjaere.usenet.app.play.missingPartsWords
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.player.PlayUris
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L354, L377: what the player does otherwise for a finished download's file, the plain decisions its branches take
 * ([PlayerBranches]), keyed on the launch's URI naming one. A file on this device is no stream: the CPU kept awake without
 * a Wi-Fi lock, no slow-stream watch, no stream-cost line, nothing remembered as missing on the providers; Stream instead
 * in Try another release's place only while the network is validated, TheIntroDB asked only then; no Cast button. And the
 * next-episode card's line from the downloads, with no search. Names and ids are invented.
 */
class PlayerBranchesTest {
    private val download = PlayUris.download("3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d")
    private val stream = UsenetUris.build("a1b2c3", 0, "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL.mkv", 1_400_000_000)
    private val service = PlayUris.service("listing-1", 0)

    private fun row(episode: Int, state: DownloadState = DownloadState.Done, gaps: Int = 0, size: Long? = 1_000_000_000, safeOffset: Long = 0) = Download(
        id = "row-$episode", watchKey = WatchKey.episode(900002, 1, episode), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S01E0$episode", showTmdbId = 900002, season = 1, episode = episode, seasonEpisodes = 8, state = state, position = episode.toLong(),
        release = RememberedRelease("fake-indexer", "hl-s01e0$episode", "Harbor.Lights.S01E0$episode.1080p.WEB.h264-KESTREL", null, 0, "nzb-$episode"),
        size = size, indexerSize = 1_000_000_000, safeOffset = safeOffset, gaps = (0 until gaps).map { DownloadGap(it * 10_000L, it * 10_000L + 999) },
        createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private val next = WatchKey.episode(900002, 1, 6)

    @Test
    fun `a download is a launch whose URI names one, and nothing else is`() {
        assertTrue(PlayerBranches.isDownload(download))
        assertFalse(PlayerBranches.isDownload(stream))
        assertFalse(PlayerBranches.isDownload(service))
        assertFalse(PlayerBranches.isDownload("file:///sdcard/clip.mkv"))
        assertFalse(PlayerBranches.isDownload(null))
    }

    @Test
    fun `a download's file keeps the CPU awake but holds no Wi-Fi lock, as a stream does`() {
        assertEquals(C.WAKE_MODE_LOCAL, PlayerBranches.wakeMode(download = true))
        assertEquals(C.WAKE_MODE_NETWORK, PlayerBranches.wakeMode(download = false))
    }

    @Test
    fun `a download's play watches no stream, logs no stream's cost and remembers nothing as missing on the providers`() {
        assertFalse(PlayerBranches.watchesDips(download = true))
        assertFalse(PlayerBranches.logsStreamCost(download = true))
        assertFalse(PlayerBranches.remembersMissing(download = true))
        // A stream's, as before.
        assertTrue(PlayerBranches.watchesDips(download = false))
        assertTrue(PlayerBranches.logsStreamCost(download = false))
        assertTrue(PlayerBranches.remembersMissing(download = false))
    }

    @Test
    fun `the Cast button hides while a download plays, and a TV has none either way`() {
        assertFalse(PlayerBranches.castShown(television = false, download = true))
        assertTrue(PlayerBranches.castShown(television = false, download = false))
        assertFalse(PlayerBranches.castShown(television = true, download = false))
        assertFalse(PlayerBranches.castShown(television = true, download = true))
    }

    @Test
    fun `TheIntroDB is asked for a download only while online, for a stream as before`() {
        assertTrue(PlayerBranches.asksIntroDb(download = true, online = true))
        assertFalse(PlayerBranches.asksIntroDb(download = true, online = false))
        assertTrue(PlayerBranches.asksIntroDb(download = false, online = false))
    }

    @Test
    fun `a download offers Stream instead in Try another release's place, only while online`() {
        assertEquals(PlayerBranches.Other.StreamInstead, PlayerBranches.other(download = true, online = true, canTryAnother = false))
        assertEquals(PlayerBranches.Other.None, PlayerBranches.other(download = true, online = false, canTryAnother = false))
        // Whatever the coordinator would say of another release: a download has no request behind it.
        assertEquals(PlayerBranches.Other.None, PlayerBranches.other(download = true, online = false, canTryAnother = true))
        assertEquals(PlayerBranches.Other.TryAnother, PlayerBranches.other(download = false, online = false, canTryAnother = true))
        assertEquals(PlayerBranches.Other.None, PlayerBranches.other(download = false, online = true, canTryAnother = false))
        assertEquals("Stream instead", STREAM_INSTEAD)
        assertEquals("Try another release", TRY_ANOTHER)
    }

    @Test
    fun `the missing-parts prompt asks to stream instead for a download`() {
        assertEquals(STREAM_INSTEAD_QUESTION to STREAM_INSTEAD, missingPartsWords(streamInstead = true))
        assertEquals("Stream instead? It starts where you are.", STREAM_INSTEAD_QUESTION)
        assertEquals(MISSING_PARTS_QUESTION to TRY_ANOTHER, missingPartsWords(streamInstead = false))
    }

    @Test
    fun `a downloaded next episode's line comes from its row, with no search, online or not`() {
        val done = mapOf(next to row(6))
        assertEquals(Prefetch.Downloaded(partsMissing = 0), PlayerBranches.nextStatus(next, online = true, done, emptyList()))
        assertEquals(Prefetch.Downloaded(partsMissing = 0), PlayerBranches.nextStatus(next, online = false, done, emptyList()))
        val gaps = mapOf(next to row(6, gaps = 2))
        assertEquals(Prefetch.Downloaded(partsMissing = 2), PlayerBranches.nextStatus(next, online = false, gaps, emptyList()))
    }

    @Test
    fun `offline, a next episode on its way says how much of it is written, and one not downloaded says so`() {
        val running = row(6, DownloadState.Running, size = 1_000_000_000)
        val items = listOf(DownloadRunner.Item(row(5, DownloadState.Queued), 0, null), DownloadRunner.Item(running, 420_000_000, 3_000_000))
        assertEquals(Prefetch.StillDownloading(42), PlayerBranches.nextStatus(next, online = false, emptyMap(), items))
        // Before Resolve knows the exact size, by the indexer's; a paused one is on its way too.
        val paused = row(6, DownloadState.Paused, size = null, safeOffset = 250_000_000)
        assertEquals(Prefetch.StillDownloading(25), PlayerBranches.nextStatus(next, online = false, emptyMap(), listOf(DownloadRunner.Item(paused, 250_000_000, null))))
        // A failed one isn't on its way.
        val failed = DownloadRunner.Item(row(6, DownloadState.Failed), 600_000_000, null)
        assertEquals(Prefetch.NotDownloadedOffline, PlayerBranches.nextStatus(next, online = false, emptyMap(), listOf(failed)))
        assertEquals(Prefetch.NotDownloadedOffline, PlayerBranches.nextStatus(next, online = false, emptyMap(), emptyList()))
    }

    @Test
    fun `online, a next episode not downloaded or not yet complete searches and streams as before`() {
        val running = DownloadRunner.Item(row(6, DownloadState.Running), 420_000_000, 3_000_000)
        assertNull(PlayerBranches.nextStatus(next, online = true, emptyMap(), listOf(running)))
        assertNull(PlayerBranches.nextStatus(next, online = true, emptyMap(), emptyList()))
    }

    /**
     * A stream that plays has a network, whatever Android says of it (a Wi-Fi that blocks its check, a handover): its next
     * episode not downloaded is searched for as before. The offline lines are a download's.
     */
    @Test
    fun `a stream's next episode is searched for whatever Android says of the network, and only a download's play goes offline`() {
        assertTrue(PlayerBranches.nextOnline(download = false, online = false))
        assertTrue(PlayerBranches.nextOnline(download = false, online = true))
        assertFalse(PlayerBranches.nextOnline(download = true, online = false))
        assertTrue(PlayerBranches.nextOnline(download = true, online = true))
        assertNull(PlayerBranches.nextStatus(next, PlayerBranches.nextOnline(download = false, online = false), emptyMap(), emptyList()))
        // A downloaded next plays from its file after a stream too (L353).
        assertEquals(Prefetch.Downloaded(partsMissing = 0), PlayerBranches.nextStatus(next, PlayerBranches.nextOnline(download = false, online = false), mapOf(next to row(6)), emptyList()))
    }

    /**
     * Previous plays the episode before as Next plays the one after: online as before, offline only one with a finished
     * download, from its file; else it starts this one again, as no stream can start offline.
     */
    @Test
    fun `offline, Previous plays only an episode with a finished download, and online any`() {
        val previous = WatchKey.episode(900002, 1, 4)
        assertTrue(PlayerBranches.previousPlays(previous, online = true, emptyMap()))
        assertTrue(PlayerBranches.previousPlays(previous, online = false, mapOf(previous to row(4))))
        assertFalse(PlayerBranches.previousPlays(previous, online = false, emptyMap()))
        assertFalse(PlayerBranches.previousPlays(previous, online = false, mapOf(next to row(6))))
    }

    /** The episode a download's row stood in for, TMDB not reached, has nothing to be searched by: never searched for. */
    @Test
    fun `an episode the row stood in for is never searched for, the network back or not`() {
        assertEquals(Prefetch.NotDownloadedOffline, PlayerBranches.nextStatus(next, online = true, emptyMap(), emptyList(), standIn = true))
        val running = DownloadRunner.Item(row(6, DownloadState.Running), 420_000_000, 3_000_000)
        assertEquals(Prefetch.StillDownloading(42), PlayerBranches.nextStatus(next, online = true, emptyMap(), listOf(running), standIn = true))
        // One downloaded plays from its file, as any.
        assertEquals(Prefetch.Downloaded(partsMissing = 0), PlayerBranches.nextStatus(next, online = true, mapOf(next to row(6)), emptyList(), standIn = true))
    }

    @Test
    fun `what can't play offline counts down to nothing and offers Close alone`() {
        listOf(Prefetch.NotDownloadedOffline, Prefetch.StillDownloading(42)).forEach {
            assertFalse(it.counts, "$it counts")
            assertFalse(it.canPlay, "$it plays")
        }
        assertTrue(Prefetch.Downloaded(0).counts)
        assertTrue(Prefetch.Downloaded(2).canPlay)
        // Nothing found: no countdown, but Play now searches again.
        assertFalse(Prefetch.NothingFound.counts)
        assertTrue(Prefetch.NothingFound.canPlay)
    }

    /** L354: Stream instead plays the title by its stream path, never through the hooks, which would play the file again. */
    @Test
    fun `the coordinator's Stream instead streams past the download hooks`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/play/PlaybackCoordinator.kt").readText()
        val at = source.indexOf("fun streamInstead(launch: PlayerLaunch")
        assertTrue(at >= 0, "no streamInstead")
        val body = source.substring(at).substringBefore("\n    }\n")
        assertFalse("downloadHook" in body, body)
        assertTrue("streamEpisode(" in body && "streamMovie(" in body, body)
    }
}

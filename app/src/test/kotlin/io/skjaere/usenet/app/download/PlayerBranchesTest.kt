package io.skjaere.usenet.app.download

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.STREAM_INSTEAD
import io.skjaere.usenet.app.play.STREAM_INSTEAD_QUESTION
import io.skjaere.usenet.app.play.TRY_ANOTHER
import io.skjaere.usenet.app.play.MISSING_PARTS_QUESTION
import io.skjaere.usenet.app.play.PlaybackFailure
import io.skjaere.usenet.app.play.missingPartsWords
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.player.GapReached
import io.skjaere.usenet.player.PlayUris
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
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

    /**
     * The player asks each decision where it acts (L354, L377). Nothing in a JVM test runs the activity, and a device can't
     * see a wake lock, so its source is read: a change that drops a guard (a download's play holding a Wi-Fi lock, watching
     * a stream's speed, logging a stream's cost, remembering its gap as missing on the providers, offering what can't play
     * offline) fails here, as the hooks' first lines are pinned. Each action is found once, inside its guard.
     */
    @Test
    fun `the player asks each decision where it acts`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/PlayerActivity.kt").readText()
        fun count(text: String) = Regex(Regex.escape(text)).findAll(source).count()
        val guarded = mapOf(
            "the wake mode by the play" to Regex("""PlayerFactory\.create\((?s:.){0,400}?wakeMode = PlayerBranches\.wakeMode\(download\),\s*\)"""),
            "no missing record for a download" to
                Regex("""if \(usenet is UsenetError\.MissingArticle && PlayerBranches\.remembersMissing\(download\)\) \{\s*launch\?\.let \{ container\.playback\.rememberMissing\("""),
            "no dip watch for a download" to Regex("""if \(PlayerBranches\.watchesDips\(download\)\) \{\s*dipJob = lifecycleScope\.launch \{ watchDips\("""),
            "no stream-cost line for a download" to Regex("""container\.streamCost\.playing\(awake && PlayerBranches\.logsStreamCost\(playingDownload\)\)"""),
            "the Cast button by the play" to Regex("""PlayerBranches\.castShown\(container\.television, playingDownload\)"""),
            "TheIntroDB only online for a download" to Regex("""PlayerBranches\.asksIntroDb\(download, online = download && online\(\)\)"""),
            "Play now only for what can play" to Regex("""private fun playNow\(card: NextCard\) \{\s*if \(offersPlayNow\(card\.card\) && """),
            "the next-track key only for what can play" to Regex("""cardHasNext = nextCard\?\.let \{ offersPlayNow\(it\.card\) \} == true"""),
            "Previous offline only to a download" to Regex("""if \(PlayerBranches\.previousPlays\(previous\.key, nextOnline\(\), finishedDownloads\(\)\)\) switchTo\(previous\)"""),
            "the offline lines a download's alone" to Regex("""PlayerBranches\.nextOnline\(playingDownload, online\(\)\)"""),
            "Stream instead's release by its failure" to Regex(
                """val missingParts = failure\?\.missingParts == true\s*return streamInstead\(PlayerBranches\.streamLeavesOut\(missingParts, failure\?\.releaseAtFault == true\), missingParts\)""",
            ),
            "Stream instead from the prompt for its missing parts" to Regex("""if \(playingDownload\) return streamInstead\(leaveOut = true, missingParts = true\)"""),
        ).filterValues { !it.containsMatchIn(source) }.keys
        val once = mapOf(
            "PlayerFactory.create(" to count("PlayerFactory.create("),
            "container.playback.rememberMissing(" to count("container.playback.rememberMissing("),
            "launch { watchDips(" to count("launch { watchDips("),
            "container.streamCost.playing(" to count("container.streamCost.playing("),
        ).filterValues { it != 1 }
        assertTrue(guarded.isEmpty() && once.isEmpty(), "guards gone: $guarded; actions not found once: $once")
    }

    /**
     * Stream instead leaves the download's own release out of the stream after its missing parts or a fault of its own
     * (a damaged file, what this device can't decode), as the stream would meet the same. Not after what says nothing of
     * the release: its file gone, this device's decoder or sound output stopping, a read that failed, anything else; there
     * it goes first, as the one that played the title. Each failure as the player reads it for a download's play.
     */
    @Test
    fun `Stream instead leaves the download's release out for its missing parts or a fault of its own`() {
        fun leavesOut(error: Throwable, code: Int) =
            PlaybackFailure.of(error, code, download = true).let { PlayerBranches.streamLeavesOut(it.missingParts, it.releaseAtFault) }
        val gap = IOException("load failed", GapReached(4_000_000L..4_999_999L))
        assertTrue(leavesOut(gap, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND), "a part missing")
        assertTrue(leavesOut(IOException("bad"), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED), "a damaged file")
        assertTrue(leavesOut(IOException("bad"), PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED), "what this device can't decode")
        assertTrue(PlaybackFailure.noVideo(listOf("HEVC")).let { PlayerBranches.streamLeavesOut(it.missingParts, it.releaseAtFault) }, "no video to play")
        val gone = IOException("load failed", DataSourceException("Download d1 has no finished file", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertFalse(leavesOut(gone, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND), "its file gone")
        assertFalse(leavesOut(IOException("sound"), PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED), "the sound output stopped")
        assertFalse(leavesOut(IOException("decoder"), PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), "the decoder stopped")
        assertFalse(leavesOut(IOException("read"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED), "a read that failed")
        assertFalse(leavesOut(IOException("other"), PlaybackException.ERROR_CODE_UNSPECIFIED), "anything else")
    }

    /**
     * L354: Stream instead plays the title by its stream path, never through the hooks, which would play the file again,
     * leaving the download's release out where the player says so; a film with its year and length from its download's row.
     */
    @Test
    fun `the coordinator's Stream instead streams past the download hooks`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/play/PlaybackCoordinator.kt").readText()
        val at = source.indexOf("fun streamInstead(launch: PlayerLaunch")
        assertTrue(at >= 0, "no streamInstead")
        val body = source.substring(at).substringBefore("\n    }\n")
        assertFalse("downloadHook" in body, body)
        assertTrue("streamEpisode(" in body && "streamMovie(" in body, body)
        assertTrue("Retry.streamInstead(launch.release, missingParts).takeIf { leaveOut }" in body, body)
        // A film's year and length from its download's row by its id, whatever its state, then TMDB's ids: never the
        // finished downloads' map, which a row failed as its file was found gone has left by then.
        assertFalse("downloads.done" in body, body)
        assertTrue("withTmdbIds(DownloadLaunch.filmFacts(known, launch) { id -> container.data.downloads.get(id) })" in body, body)
    }
}

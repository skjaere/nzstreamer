package io.skjaere.usenet.app.download

import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.nzbstreamer.stream.NntpPriority
import io.skjaere.nzbstreamer.stream.StreamOptions
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.LibraryDatabase
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * M2: a play started during a download, measured, not asserted. Run by name only (the sharded runner leaves it out): it
 * takes a few minutes. The download is 64 MB of seeded bytes, the play dip.mkv (20 s at about 840 kbps), both posted on a
 * server in the test's process ([io.skjaere.usenet.app.LocalNntp]) throttled to a provider's rate on each connection. Each
 * case prepares the film (as a play's Open does), launches it into the player and plays it [WINDOW_MS]; it measures the
 * prepare's time, the time from the launch to the first frame, the stalls (a wait for bytes after it first played) and
 * what the download wrote meanwhile. Cases: no download; the download at the priority Part 0 gives it
 * ([StreamOptions.BACKGROUND_PRIORITY]); the download at a play's ([NntpPriority.STREAMING], a seam); twice each but the
 * first. Two rounds: the engine at its own concurrency (the provider's 4 connections less a spare, 3 for each stream: one
 * connection always free for the other), then each stream fetching 8 at once on the 4 connections, so their fetches queue
 * for them and priority decides who goes next. The numbers go to the log under [TAG], one "M2 ..." line a case.
 *
 * So that no case measures what isn't its own: a first play, discarded, takes the process's cold start before any case;
 * the download is written again from its first byte each time it ends, so it runs through every case however long the
 * film's prepare waits behind it; and a case whose download wasn't under way at the film's first frame says so (void).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayDuringDownloadMeasure {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val downloads = DownloadFixtures()
    private val local get() = downloads.local
    private val plays = GapFixtures(downloads.local, INDEXER, WatchTarget(WatchKind.Movie, "Quiet Meridian", movieTmdbId = FILM))
    private val db = LibraryDatabase.inMemory(context)
    private val storage = DownloadStorage(
        downloads.file("files"), { downloads.file("volume") }, db.downloads(), context.getSystemService(StorageManager::class.java),
    )

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        local.throttle = null
        db.close()
        downloads.close()
        runBlocking {
            // Out of Up next.
            val film = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Quiet Meridian", updatedAt = 0)
            container.library.recordProgress(film, 0, DURATION_MS)
        }
    }

    /** One case's numbers. */
    private class Measured(
        val case: String,
        val prepareMs: Long,
        val firstFrameMs: Long,
        val stalls: Int,
        val stalledMs: Long,
        val playedMs: Long,
        val downloadedMb: Double?,
        /** Whether the download was under way at the film's first frame: else nothing ran beside it. */
        val beside: Boolean = true,
    ) {
        /** What the download wrote a second over the case: from before the film's prepare to the end of its play. */
        private val downloadMbps: String
            get() = downloadedMb?.let { "%.2f".format(Locale.ROOT, it * 1_000 / (prepareMs + firstFrameMs + WINDOW_MS)) } ?: "none"

        val line: String
            get() = "M2 case=$case prepareMs=$prepareMs firstFrameMs=$firstFrameMs stalls=$stalls stalledMs=$stalledMs " +
                "playedMs=$playedMs downloadMBps=$downloadMbps" + if (beside) "" else " void=no-download-at-first-frame"
    }

    @Test
    fun aPlayStartedDuringAThrottledDownload() = runBlocking<Unit> {
        val film = plays.media("dip.mkv")
        val big = downloads.post(Packing.Plain, TITLE, DownloadFixtures.seeded(64 * DownloadFixtures.MB, seed = System.nanoTime()))
        val outcome = engineSetup("$TITLE prepared") { container.preparer.prepare(big.nzb, big.title) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("$TITLE didn't prepare: $outcome")
        local.throttle = THROTTLE
        val cases = listOf(
            "none" to null,
            "background" to StreamOptions.BACKGROUND_PRIORITY,
            "play" to NntpPriority.STREAMING.value,
            "background" to StreamOptions.BACKGROUND_PRIORITY,
            "play" to NntpPriority.STREAMING.value,
        )
        val rounds = listOf("own" to null, "queued" to 2 * local.provider.maxConnections)
        // The process's first launch of the player, discarded: no case pays its cold start.
        Log.i(TAG, measure("warm-up", film) { true }.line.replace("M2 case=warm-up", "M2 warm-up (discarded)"))
        val measured = rounds.flatMap { (round, concurrency) ->
            engineSetup("the engine for the $round round") { container.engine.configure(EngineSettings(listOf(local.provider), concurrency = concurrency)) }
            cases.mapIndexed { i, (case, priority) -> measured("$round-$case", "$round-$i", priority, film, big, prepared) }
        }
        assertTrue(measured.all { it.playedMs > 0 })
    }

    /** One case: [priority]'s download (none without), and the film's play measured during it. */
    private suspend fun measured(case: String, tag: String, priority: Int?, film: ByteArray, big: DownloadFixtures.Release, prepared: PreparedNzb): Measured =
        coroutineScope {
            val writer = priority?.let {
                DownloadWriter(NzbStreamOpener(container.engine, container.engine.downloadScope, NzbStreamOpener.DOWNLOAD_OPTIONS.copy(priority = it)))
            }
            val id = Download.newId()
            val file = prepared.files.single()
            // The bytes of the writes before this one: the download is written again from its first byte each time it ends.
            val laps = java.util.concurrent.atomic.AtomicLong()
            val download = writer?.let { w ->
                launch(Dispatchers.IO) {
                    while (true) {
                        storage.open(id, big.fileName, file.size).use { out -> w.write(id, prepared, file, out, 0, emptyList()) { _, _ -> } }
                        storage.deleteVideo(id)
                        laps.addAndGet(file.size)
                    }
                }
            }
            /** What the download has written in all, its write under way's with those before. */
            fun written(): Long? = writer?.let { w -> laps.get() + (w.live.value?.written ?: 0) }
            try {
                // Past its start, where it asks for fewer segments at once: every connection busy, or queued for.
                writer?.let { w -> waitFor("the download under way", timeoutMs = 60_000) { w.live.value?.takeIf { it.written >= UNDER_WAY } } }
                val before = written()
                var beside = true
                measure(tag, film) { beside = writer == null || writer.live.value != null }.also { m ->
                    val after = written()
                    val downloaded = if (before != null && after != null) (after - before) / 1e6 else null
                    Log.i(TAG, Measured(case, m.prepareMs, m.firstFrameMs, m.stalls, m.stalledMs, m.playedMs, downloaded, beside).line)
                }
            } finally {
                download?.cancelAndJoin()
                if (download != null) storage.deleteVideo(id)
            }
        }

    /**
     * The film posted anew (nothing of it cached), prepared and put in the store as a play's Open does (with no time limit
     * but a long one: a prepare held behind the download is what is measured), launched and played [WINDOW_MS]:
     * [Measured]'s numbers, its download's left out. [atFirstFrame] is asked as the film first plays.
     */
    private suspend fun measure(tag: String, film: ByteArray, atFirstFrame: () -> Unit): Measured {
        val prepareStart = SystemClock.elapsedRealtime()
        val posted = local.post("dip.mkv", film, PART, "m2-$tag-${System.nanoTime()}")
        val nzb = local.nzb(posted)
        val outcome = withTimeout(120_000) { container.preparer.prepare(nzb, PLAY_TITLE) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("the film didn't prepare: $outcome")
        container.store.put(prepared, nzb)
        val prepareMs = SystemClock.elapsedRealtime() - prepareStart
        val launched = SystemClock.elapsedRealtime()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch(prepared, GUID, 0))).use { scenario ->
            waitFor("the first frame", timeoutMs = 60_000) {
                scenario.read { a ->
                    if ("Playback stopped" in overlayWords(a)) throw AssertionError("the play stopped: ${overlayWords(a)}")
                    true.takeIf { a.player?.isPlaying == true }
                }
            }
            val firstFrameMs = SystemClock.elapsedRealtime() - launched
            atFirstFrame()
            var stalls = 0
            var stalledMs = 0L
            var waitingSince: Long? = null
            val from = scenario.read { it.player!!.currentPosition }
            val end = SystemClock.elapsedRealtime() + WINDOW_MS
            while (SystemClock.elapsedRealtime() < end) {
                val waiting = scenario.read { a -> a.player?.let { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING } == true }
                val now = SystemClock.elapsedRealtime()
                when {
                    waiting && waitingSince == null -> {
                        stalls++
                        waitingSince = now
                    }
                    !waiting && waitingSince != null -> {
                        stalledMs += now - waitingSince
                        waitingSince = null
                    }
                }
                delay(POLL_MS)
            }
            waitingSince?.let { stalledMs += SystemClock.elapsedRealtime() - it }
            val playedMs = scenario.read { it.player!!.currentPosition } - from
            return Measured(tag, prepareMs, firstFrameMs, stalls, stalledMs, playedMs, null)
        }
    }

    private companion object {
        const val TAG = "PlayDuringDownloadMeasure"
        const val INDEXER = "play-during-download-measure"
        const val GUID = "quiet-meridian-web"
        const val TITLE = "Harbor.Lights.S03E08.1080p.WEB-DL.H.264-KESTREL"
        const val PLAY_TITLE = "Quiet.Meridian.2026.1080p.WEB.h264-GRP"

        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900062
        const val DURATION_MS = 20_000L

        /** About a third of a second of dip.mkv, as NetworkDipDeviceTest posts it. */
        const val PART = 32_000

        /** Each connection's bytes a second: a slow provider's. */
        const val THROTTLE = 400_000L

        /** How far a download is before its play starts: 8 MB, 16 of its articles. */
        const val UNDER_WAY = 8L * DownloadFixtures.MB

        /** How long each case plays once it has its first frame. */
        const val WINDOW_MS = 15_000L
        const val POLL_MS = 50L
    }
}

package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLoad
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.PlayInFlight
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerInFront
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L353: the coordinator's hooks ([DownloadHook]) over its play in flight ([PlayInFlight]), with fakes for the player in
 * front's launch, a Cast device's launcher and the runner's map of finished downloads. A play of a downloaded title asked
 * for on this device launches its file at once: what was in flight stops (a search cancelled, what it still writes or
 * sends dropped, a cast on its way stopped), the sheet never opens, and the launch goes out as every launch does, its
 * player on its way. A cast's, a paired phone's or a TV's play, and a title with no finished download, leave everything
 * as it was, for the stream. And the coordinator's four entry points ask the hook before anything else, while Choose
 * release and Search's play never does. Names and ids are invented.
 */
class DownloadHookTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val playerInFront = PlayerInFront()
    private val launcher = FakeLauncher()
    private val inFlight = PlayInFlight(playerInFront) { launcher }
    private var television = false
    private val done = mutableMapOf<String, Download>()
    private val holds = FakeHolds()
    private val hook = DownloadHook(inFlight, television = { television }, done = { done.toMap() }, holds = { holds }, scope = scope)

    private val row = Download(
        id = "3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d", watchKey = WatchKey.episode(900002, 1, 5), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S01E05 · Low Water", showTmdbId = 900002, season = 1, episode = 5, state = DownloadState.Done, position = 0,
        release = RememberedRelease("fake-indexer", "hl-s01e05", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL", null, 0, "a1b2c3", "Harbor.Lights.S01E05.mkv", 1_400_000_000),
        fileName = "Harbor.Lights.S01E05.mkv", size = 1_400_000_000, safeOffset = 1_400_000_000, relativePath = "downloads/3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d/v.mkv",
        createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private val searching = PlayState.Searching("Copper Crown", "2019", "Searching your indexers…")
    private val cast = CastTo("route-1", "Invented display")

    /** What lets each play before end, once the test is over, so none is left waiting. */
    private val ends = mutableListOf<CompletableDeferred<Unit>>()

    @AfterEach
    fun tearDown() {
        ends.forEach { it.complete(Unit) }
        scope.cancel()
    }

    /**
     * Another play on its way from [origin], as the coordinator starts one: the sheet its own, its job writing [searching] and
     * then waiting; once [go] completes (a cancel doesn't stop a write already on its way), it writes again and sends a launch.
     */
    private fun searchingPlay(origin: PlayOrigin, go: CompletableDeferred<Unit> = CompletableDeferred()): Job {
        ends += go
        inFlight.stop(origin)
        inFlight.job = scope.launch(inFlight.writes.writer()) {
            inFlight.writes.value = searching
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    go.await()
                    inFlight.writes.value = PlayState.Failed("Copper Crown", "2019", listOf("Copper Crown" to "No release found"), notTried = 0)
                    inFlight.writes.ifCurrent { inFlight.launches.trySend(stale) }
                }
            }
        }
        while (inFlight.writes.state.value != searching) Thread.yield()
        return inFlight.job!!
    }

    /** What the play before would have launched, had it not been stopped. */
    private val stale = PlayerLaunch(
        "usenet://nzb/abc/file/0", "Copper Crown", "2019", WatchKey.movie(900101), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900101),
        SavedRelease("fake-indexer", "cc", "Copper.Crown.2019.1080p.WEB.h264-WREN", null, 0, "abc"),
    )

    @Test
    fun `a tap on a downloaded title while another play still searches cancels that one and launches only the download`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val go = CompletableDeferred<Unit>()
        val before = searchingPlay(PlayOrigin.Here, go)

        assertTrue(hook.play(row.watchKey, PlayOrigin.Here))

        assertTrue(before.isCancelled, "the search goes on")
        // What the search still writes or sends as it ends is dropped: the sheet and the launches are the download's.
        go.complete(Unit)
        before.join()
        assertEquals(PlayState.Idle, inFlight.writes.state.value)
        val launch = withTimeout(5_000) { inFlight.launches.receive() }
        assertEquals(PlayUris.download(row.id), launch.uri)
        assertEquals(DownloadLaunch.of(row), launch)
        assertTrue(inFlight.launches.tryReceive().isFailure, "only the download's launch is sent")
        assertEquals(0, launcher.stops, "nothing was cast")
    }

    @Test
    fun `a cast on its way is stopped for a downloaded title played here`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val before = searchingPlay(PlayOrigin(cast = cast))

        assertTrue(hook.play(row.watchKey, PlayOrigin.Here))

        assertTrue(before.isCancelled)
        assertEquals(1, launcher.stops, "the cast on its way is stopped there")
        assertEquals(PlayUris.download(row.id), inFlight.launches.tryReceive().getOrNull()?.uri)
    }

    @Test
    fun `the sheet doesn't open and the launch goes through the player in front`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val states = mutableListOf<PlayState>()
        val watching = scope.launch(Dispatchers.Unconfined) { inFlight.writes.state.collect { states += it } }
        val started = scope.launch(Dispatchers.Unconfined) { inFlight.starts.first() }
        assertNull(playerInFront.launching.value)

        assertTrue(hook.play(row.watchKey, PlayOrigin.Here))

        assertEquals(listOf<PlayState>(PlayState.Idle), states, "the sheet showed something")
        assertEquals(Launching(null), playerInFront.launching.value)
        assertEquals(PlayUris.download(row.id), inFlight.launches.tryReceive().getOrNull()?.uri)
        // Those listening hear a play start (a player in the window pauses for it, L9).
        withTimeout(5_000) { started.join() }
        watching.cancel()
    }

    @Test
    fun `an auto-advance's hand-on looks too`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val previous = DownloadLaunch.of(row.copy(id = "e4", watchKey = WatchKey.episode(900002, 1, 4), episode = 4))
        assertTrue(hook.play(row.watchKey, PlayOrigin.after(previous)))
        assertEquals(PlayUris.download(row.id), inFlight.launches.tryReceive().getOrNull()?.uri)
    }

    @Test
    fun `a cast's, a paired phone's or a TV's play, or a title with no finished download, leaves the play in flight alone`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val phone = RemotePlay("s-a", "aa".repeat(32), "Invented phone")
        val before = searchingPlay(PlayOrigin.Here)
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        assertFalse(hook.play(row.watchKey, PlayOrigin(remote = phone)))
        assertFalse(hook.play(WatchKey.episode(900002, 1, 6), PlayOrigin.Here))
        assertFalse(hook.play(null, PlayOrigin.Here))
        television = true
        assertFalse(hook.play(row.watchKey, PlayOrigin.Here))

        assertTrue(before.isActive, "the play in flight was stopped")
        assertEquals(searching, inFlight.writes.state.value)
        assertTrue(inFlight.launches.tryReceive().isFailure, "something was launched")
        assertNull(playerInFront.launching.value)
        assertEquals(0, launcher.stops)
        assertEquals(emptySet(), holds.held(), "a file held")
    }

    /**
     * L413: the hook holds the download it launches for 30 seconds, so a Remove, Delete all or Delete once watched as its
     * player opens waits for it; the player holds it under its own lease from then on. Each launch holds it on its own, so
     * the first's end never lets go of a later launch's.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a download's launch holds its file for 30 seconds, each launch its own hold`() = runTest {
        done[row.watchKey] = row
        val launches = FakeHolds()
        val timed = DownloadHook(inFlight, television = { television }, done = { done.toMap() }, holds = { launches }, scope = backgroundScope)
        assertTrue(timed.play(row.watchKey, PlayOrigin.Here))
        assertEquals(setOf(row.id), launches.held())
        advanceTimeBy(10_000)
        assertTrue(timed.play(row.watchKey, PlayOrigin.Here))
        advanceTimeBy(DownloadHook.LAUNCH_HOLD_MILLIS - 10_000 - 1)
        runCurrent()
        assertEquals(setOf(row.id), launches.held())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(setOf(row.id), launches.held(), "the second launch's hold went with the first's")
        assertEquals(1, launches.owners())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptySet(), launches.held())
    }

    /**
     * L413: a download Delete once watched has begun to delete can't be held, so the hook launches nothing from its file:
     * the play goes as any other title's, the play in flight left alone.
     */
    @Test
    fun `a download being deleted by Delete once watched isn't launched from its file`() = runBlocking<Unit> {
        done[row.watchKey] = row
        holds.going = setOf(row.id)
        assertFalse(hook.play(row.watchKey, PlayOrigin.Here))
        assertTrue(inFlight.launches.tryReceive().isFailure, "something was launched")
        assertNull(playerInFront.launching.value)
        assertEquals(emptySet(), holds.held())
    }

    /**
     * The coordinator's entry points that play a title (Play on the Show and Movie pages, an episode by its numbers from Up
     * next, Resume) ask the hook before their sheet opens; a release chosen by hand never does (L353).
     */
    @Test
    fun `the coordinator's four entry points ask the hook first, and a chosen release's play never does`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/play/PlaybackCoordinator.kt").readText()
        fun body(signature: String): String {
            val at = source.indexOf(signature)
            assertTrue(at >= 0, "no $signature")
            return source.substring(source.indexOf('{', at) + 1).trimStart().lineSequence().first()
        }
        assertEquals("if (downloadHook.play(state.key, origin)) return", body("fun resume(state: WatchState"))
        assertEquals("if (downloadHook.play(WatchKey.movie(target.tmdbId), origin)) return", body("fun playMovie(target: MovieTarget"))
        assertEquals("if (downloadHook.play(target.key, origin)) return", body("fun playEpisode(target: EpisodeTarget"))
        assertEquals("if (downloadHook.play(ep.key, origin)) return", body("fun playEpisode(ep: EpisodePlay"))
        assertFalse("downloadHook" in body("fun play(request: PlayRequest"))
        assertEquals(4, Regex("""downloadHook\.play\(""").findAll(source).count())
    }

    /** The runner's holds as the hook takes them: each owner's ids; none of those [going] (Delete once watched's). */
    private class FakeHolds : DownloadHolds {
        private val owners = mutableMapOf<Any, MutableSet<String>>()

        @Volatile
        var going = emptySet<String>()

        @Synchronized
        override fun hold(owner: Any, id: String): Boolean {
            if (id in going) return false
            owners.getOrPut(owner) { mutableSetOf() } += id
            return true
        }

        @Synchronized
        override fun release(owner: Any, id: String) {
            owners[owner]?.let { ids -> ids -= id; if (ids.isEmpty()) owners -= owner }
        }

        @Synchronized
        fun held(): Set<String> = owners.values.flatten().toSet()

        @Synchronized
        fun owners(): Int = owners.size
    }

    /** A Cast device's launcher that counts its stops. */
    private class FakeLauncher : CastLauncher {
        var stops = 0

        override fun starting(cast: CastTo, metadata: SessionMetadata) = true
        override fun load(launch: PlayerLaunch): CastLoad = error("nothing is loaded")

        override fun stop() {
            stops++
        }
    }
}

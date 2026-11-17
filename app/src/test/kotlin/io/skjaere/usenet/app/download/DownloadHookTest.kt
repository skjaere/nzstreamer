package io.skjaere.usenet.app.download

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLoad
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.PlayInFlight
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerInFront
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.ProbedTracksSerializer
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.CastDeviceMemory
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L353: the coordinator's hooks ([DownloadHook]) over its play in flight ([PlayInFlight]), with fakes for the player in
 * front's launch, a Cast device's launcher and the runner's map of finished downloads. A play of a downloaded title asked
 * for on this device launches its file at once: what was in flight stops (a search cancelled, what it still writes or
 * sends dropped, a cast on its way stopped), the sheet never opens, and the launch goes out as every launch does, its
 * player on its way. One cast to a Google Cast device (W121 c, L446) goes to the coordinator's cast of the file, held as a
 * launch is, unless the title streams for the device instead. A paired phone's or a TV's play, and a title with no
 * finished download, leave everything as it was, for the stream. And the coordinator's four entry points ask the hook
 * before anything else, while Choose release and Search's play never does. Names and ids are invented.
 */
class DownloadHookTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val playerInFront = PlayerInFront()
    private val launcher = FakeLauncher()
    private val inFlight = PlayInFlight(playerInFront) { launcher }
    private var television = false
    private val done = mutableMapOf<String, Download>()
    private val holds = FakeHolds()

    /** The Cast devices' memory as the settings said it: null until read. */
    private var devices: List<CastDeviceMemory>? = emptyList()
    private var offline = false

    /** The coordinator's cast of a download's file, as the hook calls it: the row, its launch and the cast's origin. */
    private val casts = mutableListOf<Triple<Download, PlayerLaunch, PlayOrigin>>()
    private val hook = hook(holds, scope)

    private fun hook(holds: DownloadHolds, scope: CoroutineScope) = DownloadHook(
        inFlight, television = { television }, done = { done.toMap() }, holds = { holds }, scope = scope, devices = { devices }, offline = { offline },
        cast = { row, launch, origin -> casts += Triple(row, launch, origin) },
    )

    private val row = Download(
        id = "3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d", watchKey = WatchKey.episode(900002, 1, 5), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S01E05 · Low Water", showTmdbId = 900002, season = 1, episode = 5, state = DownloadState.Done, position = 0,
        release = RememberedRelease("fake-indexer", "hl-s01e05", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL", null, 0, "a1b2c3", "Harbor.Lights.S01E05.mkv", 1_400_000_000),
        fileName = "Harbor.Lights.S01E05.mkv", size = 1_400_000_000, safeOffset = 1_400_000_000, relativePath = "downloads/3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d/v.mkv",
        createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private val searching = PlayState.Searching("Copper Crown", "2019", "Searching your indexers…")
    private val cast = CastTo("route-1", "Invented display")
    private val json = Json { ignoreUnknownKeys = true }

    /** [row] with its file's tracks as Resolve's probe kept them: DTS its only sound, which no Cast device plays. */
    private val silent = row.copy(
        tracks = json.encodeToString(
            ProbedTracksSerializer,
            ProbedTracks(listOf(ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 8)), listOf(ProbedTrack(MimeTypes.AUDIO_DTS, "DTS", "en", channels = 6))),
        ),
    )

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
    fun `a paired phone's or a TV's play, or a title with no finished download, leaves the play in flight alone`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val phone = RemotePlay("s-a", "aa".repeat(32), "Invented phone")
        val before = searchingPlay(PlayOrigin.Here)
        assertFalse(hook.play(row.watchKey, PlayOrigin(remote = phone)))
        assertFalse(hook.play(WatchKey.episode(900002, 1, 6), PlayOrigin.Here))
        assertFalse(hook.play(WatchKey.episode(900002, 1, 6), PlayOrigin(cast = cast)))
        assertFalse(hook.play(null, PlayOrigin.Here))
        television = true
        assertFalse(hook.play(row.watchKey, PlayOrigin.Here))
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))

        assertTrue(before.isActive, "the play in flight was stopped")
        assertEquals(searching, inFlight.writes.state.value)
        assertTrue(inFlight.launches.tryReceive().isFailure, "something was launched")
        assertNull(playerInFront.launching.value)
        assertEquals(0, launcher.stops)
        assertEquals(emptySet(), holds.held(), "a file held")
        assertEquals(emptyList(), casts, "a file cast")
    }

    /**
     * W121 (c), L446: a title's Play to a Cast device with a finished download whose file the device plays (nothing read
     * of its tracks here) casts the file: the coordinator's cast of it is called with the row, its launch and the cast's
     * origin, the file held as a launch's is, and nothing goes to this phone's player.
     */
    @Test
    fun `a title's Play to a Cast device casts the downloaded file, held, with nothing launched here`() = runBlocking<Unit> {
        done[row.watchKey] = row
        val origin = PlayOrigin(cast = cast)
        assertTrue(hook.play(row.watchKey, origin))
        assertEquals(listOf(Triple(row, DownloadLaunch.of(row)!!, origin)), casts)
        assertEquals(setOf(row.id), holds.held())
        assertTrue(inFlight.launches.tryReceive().isFailure, "something was launched here")
        assertNull(playerInFront.launching.value)
    }

    /**
     * W121 (c), L446: a file the device can't fully play streams the title for it from a title's page while online: the hook
     * answers false and holds nothing, so the entry point streams as without a download. Offline, as nothing can stream,
     * the file is taken, to be asked about over it.
     */
    @Test
    fun `a file the device can't fully play streams the title online, holding nothing, and is taken offline`() = runBlocking<Unit> {
        done[row.watchKey] = silent
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        assertEquals(emptyList(), casts)
        assertEquals(emptySet(), holds.held())
        offline = true
        assertTrue(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        assertEquals(listOf(silent.id), casts.map { it.first.id })
        assertEquals(setOf(row.id), holds.held())
    }

    /**
     * L446: a download still running is no file to cast, its `.part` holding zeros where nothing is written yet: the title
     * streams for the device, nothing held. Nor before the Cast devices' memory is read (a moment at a cold start), as the
     * device's profile can't be told without a wait; nor one Delete once watched has taken.
     */
    @Test
    fun `a running download, profiles not read yet, or a row being deleted, isn't cast`() = runBlocking<Unit> {
        done[row.watchKey] = row.copy(state = DownloadState.Running, safeOffset = 400_000_000)
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        done[row.watchKey] = row
        devices = null
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        devices = emptyList()
        holds.going = setOf(row.id)
        assertFalse(hook.play(row.watchKey, PlayOrigin(cast = cast)))
        assertEquals(emptyList(), casts)
        assertEquals(emptySet(), holds.held())
    }

    /** A file of an NZB opened on Import, downloaded (L423): played by its `release:` key, its watch the release's. */
    private val imported = row.copy(
        id = "7c1d2e3f-0a1b-4c2d-8e3f-405162738495", watchKey = WatchKey.release("a1b2c3", 1), kind = DownloadKind.Release,
        title = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv", subtitle = "Quiet.Meridian.S01.1080p.WEB.h264-WREN", showTmdbId = null, season = null, episode = null,
        release = RememberedRelease(IMPORTED, "a1b2c3", "Quiet.Meridian.S01.1080p.WEB.h264-WREN", null, 1, "a1b2c3", "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv", 1_300_000_000),
        fileName = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv", size = 1_300_000_000, relativePath = "downloads/7c1d2e3f-0a1b-4c2d-8e3f-405162738495/v.mkv",
    )

    /**
     * L423, L458: Up next's Resume of a file opened on Import and downloaded (its row's `release:` key, which the coordinator's
     * resume asks the hook by) plays the file here, held 30 s as any launch, with nothing searched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an imported file's finished download plays from Resume by its release key, held 30 seconds`() = runTest {
        done[imported.watchKey] = imported
        val launches = FakeHolds()
        assertTrue(hook(launches, backgroundScope).play(imported.watchKey, PlayOrigin.Here))
        val launch = assertNotNull(inFlight.launches.tryReceive().getOrNull())
        assertEquals(DownloadLaunch.of(imported), launch)
        assertEquals(WatchKind.Release, launch.watch.kind)
        assertEquals(setOf(imported.id), launches.held())
        advanceTimeBy(DownloadHook.LAUNCH_HOLD_MILLIS)
        runCurrent()
        assertEquals(emptySet(), launches.held())
    }

    /**
     * L473: an imported file has no title to stream for a Cast device, so its cast is always taken: one the device can't
     * fully play asks over the file, online too, and before the Cast devices' memory is read its cast judges the file once
     * it has the device's profile, rather than streaming. Held as any cast's file; nothing played here.
     */
    @Test
    fun `an imported file's download is cast however its tracks or the profiles stand, never streamed`() = runBlocking<Unit> {
        val deaf = imported.copy(tracks = silent.tracks)
        done[imported.watchKey] = deaf
        val origin = PlayOrigin(cast = cast)
        assertTrue(hook.play(imported.watchKey, origin), "online, a miss from a title streams the title: there is none")
        devices = null
        assertTrue(hook.play(imported.watchKey, origin), "the profiles not read: taken all the same")
        assertEquals(listOf(Triple(deaf, DownloadLaunch.of(deaf)!!, origin), Triple(deaf, DownloadLaunch.of(deaf)!!, origin)), casts)
        assertEquals(setOf(imported.id), holds.held())
        assertTrue(inFlight.launches.tryReceive().isFailure, "something was launched here")
        // Still a finished file alone, and never one Delete once watched has taken.
        casts.clear()
        done[imported.watchKey] = deaf.copy(state = DownloadState.Running)
        assertFalse(hook.play(imported.watchKey, origin))
        done[imported.watchKey] = deaf
        holds.going = setOf(imported.id)
        assertFalse(hook.play(imported.watchKey, origin))
        assertEquals(emptyList(), casts)
    }

    /** L413, L447: the file a cast takes is held for 30 s as a launch's, until the cast's own hold has it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cast's file is held for 30 seconds, as a launch's`() = runTest {
        done[row.watchKey] = row
        val launches = FakeHolds()
        assertTrue(hook(launches, backgroundScope).play(row.watchKey, PlayOrigin(cast = cast)))
        advanceTimeBy(DownloadHook.LAUNCH_HOLD_MILLIS - 1)
        runCurrent()
        assertEquals(setOf(row.id), launches.held())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(emptySet(), launches.held())
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
        val timed = hook(launches, backgroundScope)
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
     * next, Resume) ask the hook before their sheet opens; a release chosen by hand never does (L353). A file opened on
     * Import, with no title, is played by the hook alone, by its watch key (playDownloaded, L458): the fifth asking.
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
        assertTrue(Regex("""fun playDownloaded\(watchKey: String, origin: PlayOrigin = PlayOrigin\.Here\): Boolean = downloadHook\.play\(watchKey, origin\)""").containsMatchIn(source))
        assertEquals(5, Regex("""downloadHook\.play\(""").findAll(source).count())
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

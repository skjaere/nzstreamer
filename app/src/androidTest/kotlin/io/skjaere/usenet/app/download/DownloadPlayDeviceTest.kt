package io.skjaere.usenet.app.download

import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * L353: on this phone, with Wi-Fi and mobile data off, a title with a finished download plays its file from each way in
 * that plays a title: the Show page's tap on an episode, the Movie page's Play, Home's Resume and Up next's play of a
 * show's next episode. Each opens the player on the download's `download://` URI at once: the play sheet never shows a
 * step, nothing is searched or grabbed, and the player plays the file (Resume from where the episode stopped).
 *
 * The downloads are seeded as a finished one is left, through the app's own storage: the device tests' chapters.mkv
 * written as the file, renamed done, then its row with its marker, Done. TMDB is the fake, whose seams answer offline. The
 * titles and ids are invented. Put back after each, whatever happened: the rows and their files deleted, the downloads'
 * jobs cancelled, the library's marks and the settings as they were, Wi-Fi on and mobile data as it was.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadPlayDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val grabs = AtomicInteger()
    private val made = mutableListOf<Download>()
    private val today = LocalDate.now()
    private lateinit var saved: AppSettings
    private var keyAdded = false
    private var showCollected = false

    // What the test changed of the device, so its end puts back exactly that.
    private var wifiOff = false
    private var dataWas: String? = null

    private val names = listOf("Low Water", "Salt Road", "Night Ferry", "Lantern Bay")

    private val show = ShowDetails(
        SHOW, NAME, null, 2024, null, null, null, "Returning Series", true, 6,
        listOf(SeasonSummary(1, null, 4, today.minusDays(40), null)),
        lastEpisode = EpisodeSummary(1, 4, names[3], today.minusDays(19)), nextEpisode = null, ids = ExternalIds(),
    )

    private val season = Season(SHOW, 1, null, null, names.mapIndexed { i, name -> Episode(1, i + 1, name, null, today.minusDays(40L - 7 * i), 6, null) })

    private val movie = MovieDetails(
        MOVIE, FILM, null, 2019, "A retired forger is pulled into one last job.", null, null, 6, ExternalIds(), releaseDate = LocalDate.of(2019, 3, 1),
        status = "Released",
    )

    @Before
    fun setUp() {
        assertFalse("a phone", container.television)
        home.show()
        runBlocking {
            saved = container.settings.settings.first()
            // Up next's next episodes need a key; the seam answers in TMDB's place.
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("download-play-device-test")
                keyAdded = true
            }
            showCollected = container.library.isCollected(SHOW).first()
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), movies = mapOf(MOVIE to movie), seasons = { id, number -> if (id == SHOW && number == 1) season else null })
        PlaybackCoordinator.fetchNzb = { _, _, _ ->
            grabs.incrementAndGet()
            throw FetchException("Not to be fetched", stopsAll = true)
        }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        // No network at all: the plays below need none.
        dataWas = shell("settings get global mobile_data").trim()
        shell("svc data disable")
        wifiOff = true
        shell("svc wifi disable")
        runBlocking { waitFor("no validated network", NETWORK_MILLIS) { if (!container.validatedNetwork.value) Unit else null } }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        suspend fun back(what: suspend () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        // A player left open by a failure would be in front of the next test.
        back { finishPlayers() }
        tmdb?.close()
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        container.playback.cancel()
        // No job of the downloads' left behind, then the rows, their files and any notification of theirs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) back { DownloadJobService.scheduler(context).cancelAll() }
        val manager = context.getSystemService(NotificationManager::class.java)
        made.forEach { row ->
            back { storage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
        }
        back {
            // Out of Up next and unwatched again, the releases the player kept for them forgotten, the show out of the collection.
            container.library.markEpisodes(names.indices.map { EpisodeRef(SHOW, 1, it + 1, WatchTitle.episode(NAME, 1, it + 1)) }, watched = false)
            container.library.markMovies(listOf(MovieMark(MOVIE, FILM)), watched = false)
            made.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
            if (!showCollected) container.library.removeShow(SHOW)
        }
        back {
            if (::saved.isInitialized) {
                container.settings.restore(saved)
                if (keyAdded) container.settings.setTmdbKey(null)
            }
        }
        back { home.restore() }
        if (wifiOff) back { shell("svc wifi enable") }
        dataWas?.let { was -> back { shell(if (was == "1") "svc data enable" else "svc data disable") } }
        if (wifiOff) back { waitFor("Wi-Fi back for the next test", BACK_MILLIS) { validatedWifi() } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    @Test
    fun theShowPagesTapOnADownloadedEpisodePlaysItsFile() {
        val row = seed(episode(2))
        compose.setContent { AppTheme { ShowScreen(SHOW, onBack = {}) } }
        val tap = hasText(names[1]) and hasClickAction()
        compose.waitUntil(15_000) { exists(tap) }
        val launch = played(row) { compose.onNode(tap).performClick() }
        assertEquals(WatchKey.episode(SHOW, 1, 2), launch.watchKey)
        playsTheFile(launch)
    }

    @Test
    fun theMoviePagesPlayPlaysTheDownloadedFilm() {
        val row = seed(film())
        compose.setContent { AppTheme { MovieScreen(MOVIE, onBack = {}) } }
        val play = hasText("Play", substring = false) and hasClickAction()
        compose.waitUntil(15_000) { exists(play) }
        val launch = played(row) { compose.onNode(play).performClick() }
        assertEquals(WatchKey.movie(MOVIE), launch.watchKey)
        playsTheFile(launch)
    }

    @Test
    fun homesResumePlaysTheDownloadedEpisodeFromWhereItStopped() {
        val row = seed(episode(3))
        runBlocking {
            val state = WatchState(row.watchKey, WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 3, title = WatchTitle.episode(NAME, 1, 3), updatedAt = 0)
            container.library.recordProgress(state, POSITION_MS, DURATION_MS, row.release)
        }
        compose.setContent { AppTheme { AppNav() } }
        val resume = hasContentDescription("Resume ${WatchTitle.episode(NAME, 1, 3)}")
        compose.waitUntil(15_000) { exists(resume) }
        val launch = played(row) { compose.onNode(resume).performClick() }
        // From the place the episode's progress keeps, as a stream's Resume starts.
        val at = playsTheFile(launch)
        assertTrue("resumed at $at ms, saved at $POSITION_MS", at in POSITION_MS - 1_000..POSITION_MS + 10_000)
    }

    @Test
    fun upNextsPlayOfAShowsNextEpisodePlaysItsDownload() {
        val row = seed(episode(2))
        runBlocking {
            val now = System.currentTimeMillis()
            container.library.addShow(CollectedShow(CollectedShow.idFor(SHOW), SHOW, name = NAME, addedAt = now, updatedAt = now))
            val first = WatchState(WatchKey.episode(SHOW, 1, 1), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 1, title = WatchTitle.episode(NAME, 1, 1), updatedAt = 0)
            container.library.recordProgress(first, DURATION_MS, DURATION_MS)
            container.refresher.refreshNow(listOf(TitleKey.show(SHOW)))
        }
        compose.setContent { AppTheme { AppNav() } }
        val next = hasContentDescription("Play ${WatchTitle.episode(NAME, 1, 2)}")
        compose.waitUntil(15_000) { exists(next) }
        compose.onNode(hasScrollAction()).performScrollToNode(next)
        val launch = played(row) { compose.onNode(next).performClick() }
        assertEquals(WatchKey.episode(SHOW, 1, 2), launch.watchKey)
        playsTheFile(launch)
    }

    /**
     * What [tap] launches, the coordinator's next launch, checked as a download's play: [row]'s file by its URI, the sheet
     * never past Idle (no step shown), nothing searched or grabbed.
     */
    private fun played(row: Download, tap: () -> Unit): PlayerLaunch {
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = CoroutineScope(Dispatchers.Default)
        watching.launch { container.playback.state.collect { states += it } }
        try {
            // Watched from before the tap: its first is the state now.
            await("the play sheet's state watched") { states.firstOrNull() }
            tap()
            // Taken here, as MainActivity takes a launch: none is collecting it in the test's activity.
            val launch = runBlocking { withTimeout(LAUNCH_MILLIS) { container.playback.launches.filter { it.watchKey == row.watchKey }.first() } }
            assertEquals(PlayUris.download(row.id), launch.uri)
            assertNull("no request behind it", launch.requestId)
            assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
            assertEquals(PlayState.Idle, container.playback.state.value)
            assertEquals("indexer grabs", 0, grabs.get())
            return launch
        } finally {
            watching.cancel()
        }
    }

    /** Opens the player on [launch], as MainActivity does, and waits for it to play the download's file; where it started. */
    private fun playsTheFile(launch: PlayerLaunch): Long {
        val startedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync { compose.activity.startActivity(PlayerActivity.intent(context, launch)) }
        val player = await("the player ready on the file", READY_MILLIS) { onMain { player()?.takeIf { it.player?.playbackState == Player.STATE_READY } } }
        val (uri, at) = onMain { player.player!!.currentMediaItem?.localConfiguration?.uri?.toString() to player.player!!.currentPosition }
        assertEquals(launch.uri, uri)
        onMain { player.finish() }
        // The player saves where it was as it closes: let it, before the test's end takes the marks back.
        await("the player's save on closing") { runBlocking { container.library.state(launch.watchKey).first() }?.takeIf { it.lastPlayedAt >= startedAt } }
        return at
    }

    /** [row] as a finished download is left: chapters.mkv written as its file and renamed done, then its row, Done, with its marker. */
    private fun seed(row: Download): Download = runBlocking {
        val bytes = DownloadFixtures.media(MEDIA)
        val name = checkNotNull(row.fileName)
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val release = checkNotNull(row.release).copy(fileSize = size)
        val done = row.copy(release = release, size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(row.id, name))
        made += done
        storage.add(done)
        // The hooks ask the runner's map, which follows the library.
        waitFor("download ${done.id} among the finished ones") { container.downloads.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        done
    }

    /** What [probe] gives once it gives something, asked every 100 ms for [timeoutMs] at most. */
    private fun <T : Any> await(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun episode(number: Int): Download {
        val id = Download.newId()
        val name = "Quiet.Meridian.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        return Download(
            id = id, watchKey = WatchKey.episode(SHOW, 1, number), kind = DownloadKind.Episode, title = NAME,
            subtitle = "S01E0$number · ${names[number - 1]}", showTmdbId = SHOW, season = 1, episode = number, seasonEpisodes = names.size,
            episodeName = names[number - 1], state = DownloadState.Done, position = -1_000L + number,
            release = RememberedRelease(INDEXER, "qm-s01e0$number", name.removeSuffix(".mkv"), null, 0, "nzb-qm-$number", name),
            fileName = name, createdAt = 1, completedAt = 2, updatedAt = 2,
        )
    }

    private fun film(): Download {
        val name = "Copper.Crown.2019.720p.WEB.h264-WREN.mkv"
        return Download(
            id = Download.newId(), watchKey = WatchKey.movie(MOVIE), kind = DownloadKind.Movie, title = FILM, subtitle = "2019", movieTmdbId = MOVIE,
            year = 2019, runtimeMinutes = 6, state = DownloadState.Done, position = -1_000L,
            release = RememberedRelease(INDEXER, "cc-2019", name.removeSuffix(".mkv"), null, 0, "nzb-cc", name),
            fileName = name, createdAt = 1, completedAt = 2, updatedAt = 2,
        )
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun player(): PlayerActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()

    private fun finishPlayers() = instrumentation.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).flatMap(monitor::getActivitiesInStage)
            .filterIsInstance<PlayerActivity>().forEach { it.finish() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun validatedWifi(): Unit? = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.let { }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        /** An invented show and film; nothing real has the ids. */
        const val SHOW = 900231
        const val NAME = "Quiet Meridian"
        const val MOVIE = 900232
        const val FILM = "Copper Crown"
        const val INDEXER = "download-play-device-test"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"
        const val DURATION_MS = 360_000L
        const val POSITION_MS = 120_000L

        const val NETWORK_MILLIS = 30_000L
        const val BACK_MILLIS = 90_000L
        const val LAUNCH_MILLIS = 15_000L
        const val READY_MILLIS = 30_000L
    }
}

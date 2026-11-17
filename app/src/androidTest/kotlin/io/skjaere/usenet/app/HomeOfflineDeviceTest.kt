package io.skjaere.usenet.app

import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.download.DownloadFixtures
import io.skjaere.usenet.app.download.DownloadJobService
import io.skjaere.usenet.app.download.DownloadNotifications
import io.skjaere.usenet.app.download.DownloadedTitles
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.home.HomeContent
import io.skjaere.usenet.app.home.HomeDownloads
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.OfflineHome
import io.skjaere.usenet.app.home.REMOVE_FROM_UP_NEXT
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
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
 * L410: Home offline, its content with offline given to it (as the container's signal gives it, L438; the
 * device's Wi-Fi is never touched), a finished download of Harbor Lights S02E01 written through the app's own storage
 * (the device tests' chapters.mkv), and Up next as a test's own (UpNextEditsFake): that episode in progress, Quiet
 * Meridian S01E03 in progress and the film Copper Crown in progress, neither downloaded. Offline, "Offline · showing
 * downloads" under the recent searches, the Downloads section before Up next, and the rows not downloaded greyed: read as
 * dimmed, their Play greyed, a tap saying why in a bar at Home's foot that goes by itself, their ⋮ as it was. The
 * downloaded row plays its file through the coordinator's hook, as Up next's Play does, with nothing searched or grabbed.
 * Online, Home is as it was. At twice the text size the line and the bar are whole.
 *
 * The titles and ids are invented; TMDB's seam answers for them. Nothing is searched for: the app's indexers are off
 * while a case runs, and only the downloaded row's Play goes to the coordinator (a greyed row's that slipped through is
 * recorded, never played). Put back after each, whatever happened: the player finished, the row and its file deleted,
 * the downloads' jobs cancelled, the library's marks and the settings as they were.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HomeOfflineDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val grabs = AtomicInteger()
    private val made = mutableListOf<Download>()
    private val played = CopyOnWriteArrayList<UpNextRow>()
    private val home = mutableStateOf<HomeState?>(null)
    private val edits = UpNextEditsFake(home)
    private var offline by mutableStateOf(true)
    private var downloads: HomeDownloads? = null
    private var downloadedKeys: Set<String> = emptySet()
    private val today = LocalDate.now()
    private var saved: AppSettings? = null

    @Before
    fun setUp() {
        assertFalse("a phone", container.television)
        runBlocking {
            // The debug build's start saves its dev settings: before the test reads or takes any.
            withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
            saved = container.settings.settings.first()
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
        }
        tmdb = FakeTmdb(
            shows = mapOf(
                HL to ShowDetails(
                    HL, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 6, listOf(SeasonSummary(2, null, 8, today.minusDays(60), null)),
                    lastEpisode = EpisodeSummary(2, 8, "Undertow", today.minusDays(10)), nextEpisode = null, ids = ExternalIds(),
                ),
                QM to ShowDetails(
                    QM, "Quiet Meridian", null, 2024, null, null, null, "Ended", false, 6, listOf(SeasonSummary(1, null, 6, today.minusDays(400), null)),
                    lastEpisode = EpisodeSummary(1, 6, "Slack Water", today.minusDays(300)), nextEpisode = null, ids = ExternalIds(),
                ),
            ),
            movies = mapOf(CC to MovieDetails(CC, "Copper Crown", null, 2022, null, null, null, 112, ExternalIds(), releaseDate = LocalDate.of(2022, 3, 1), status = "Released")),
            // Strict: anything else the app asked TMDB is refused, never sent.
            strict = true,
        )
        PlaybackCoordinator.fetchNzb = { _, _, _ ->
            grabs.incrementAndGet()
            throw FetchException("Not to be fetched", stopsAll = true)
        }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        val row = seed(harborEpisode())
        // Home's section and Up next's marks, as Home reads them from the runner: this test's own download alone.
        downloads = waitForValue("Home's Downloads section with the download") { HomeDownloads.of(container.downloads.state.value.filter { it.row.id == row.id }) }
        downloadedKeys = DownloadedTitles.keys(container.downloads.done.value.filterValues { it.id == row.id })
        assertEquals(setOf(row.watchKey), downloadedKeys)
        home.value = HomeState(listOf(RecentSearch("harbor lights", "harbor lights", 0, 0)), listOf(harbor(row), meridian, crown), collection = null, hasTmdbKey = true)
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
        back { finishPlayers() }
        edits.close()
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What the app asked of other titles was refused, never sent.
        }
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        container.playback.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) back { DownloadJobService.scheduler(context).cancelAll() }
        val manager = context.getSystemService(NotificationManager::class.java)
        made.forEach { row ->
            back { storage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
        }
        back {
            // A play's save puts its episode in Up next: out of it, unwatched, its release forgotten, as before the test.
            container.library.markEpisodes(listOf(EpisodeRef(HL, 2, 1, WatchTitle.episode("Harbor Lights", 2, 1))), watched = false)
            container.library.markEpisodes(listOf(EpisodeRef(QM, 1, 3, WatchTitle.episode("Quiet Meridian", 1, 3))), watched = false)
            container.library.markMovies(listOf(MovieMark(CC, "Copper Crown")), watched = false)
            made.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
        }
        saved?.let { back { container.settings.restore(it) } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    /** Home's content on [home] at [fontScale], offline as [offline] says; Up next's Play goes where Home's does. */
    private fun showHome(fontScale: Float = 1f) {
        compose.setContent {
            val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    HomeContent(
                        home.value, artwork = false, onSearch = {}, onSettings = {}, onRecent = {},
                        onPlay = { row ->
                            played += row
                            // As Home's Play, for the downloaded row alone: nothing else is to be searched for.
                            if (row.watchKey in downloadedKeys) container.playRouter.upNext(row.play)
                        },
                        onShow = {}, onCollection = {}, onMetadata = {}, onImport = {}, edits = edits.edits(),
                        downloads = downloads, downloadedKeys = downloadedKeys, offline = offline,
                    )
                }
            }
        }
        compose.waitUntil(10_000) { exists(hasText("Copper Crown")) }
    }

    @Test
    fun offlineTheLineAndTheDownloadsComeFirstAndWhatIsntDownloadedIsGreyed() {
        showHome()
        compose.waitUntil(5_000) { exists(hasText(OfflineHome.LINE)) }
        // Under the recent searches.
        val chip = compose.onNode(hasText("harbor lights") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val line = compose.onNode(hasText(OfflineHome.LINE)).fetchSemanticsNode().boundsInRoot
        assertTrue("the line at $line, the recent search at $chip", line.top >= chip.bottom)
        // Downloads, then Up next (section headers are said in capitals).
        val downloadsTop = top(hasText("DOWNLOADS", substring = true))
        val upNextTop = top(hasText("UP NEXT"))
        assertTrue("Downloads at $downloadsTop, Up next at $upNextTop", downloadsTop < upNextTop)
        // Greyed: read as dimmed, Play greyed; the downloaded one as it was.
        assertTrue(dimmed("Quiet Meridian"))
        assertTrue(dimmed("Copper Crown"))
        assertFalse(dimmed("Harbor Lights"))
        play("Resume Quiet Meridian S01E03").assertIsNotEnabled()
        play("Resume Copper Crown").assertIsNotEnabled()
        play("Resume Harbor Lights S02E01").assertIsEnabled()
        // Online: as it was.
        offline = false
        compose.waitUntil(5_000) { !exists(hasText(OfflineHome.LINE)) }
        assertTrue("Up next before Downloads online", top(hasText("UP NEXT")) < top(hasText("DOWNLOADS", substring = true)))
        listOf("Quiet Meridian", "Copper Crown", "Harbor Lights").forEach { assertFalse("$it dimmed online", dimmed(it)) }
        play("Resume Quiet Meridian S01E03").assertIsEnabled()
    }

    @Test
    fun aGreyedRowsTapSaysWhyInABarThatGoesByItselfAndItsMoreIsAsItWas() {
        showHome()
        val bar = hasText("Quiet Meridian S01E03 isn't downloaded. It plays once you're online.")
        compose.onNode(row("Quiet Meridian")).performClick()
        compose.waitUntil(5_000) { exists(bar) }
        assertEquals("nothing played", emptyList<UpNextRow>(), played.toList())
        // A polite live region, as the Undo bar is.
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes().isNotEmpty())
        val shownAt = SystemClock.elapsedRealtime()
        compose.waitUntil(15_000) { !exists(bar) }
        val gone = SystemClock.elapsedRealtime() - shownAt
        assertTrue("the bar went after $gone ms", gone >= 2_000)
        // The film's, by its title alone.
        compose.onNode(row("Copper Crown")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Copper Crown isn't downloaded. It plays once you're online.")) }
        // Its ⋮ as it was: Remove from Up next takes it out.
        compose.onNode(hasContentDescription("More for Quiet Meridian")).performClick()
        compose.onNode(hasText(REMOVE_FROM_UP_NEXT) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { edits.removed.isNotEmpty() }
        assertEquals(listOf(meridian), edits.removed.toList())
        assertEquals(emptyList<UpNextRow>(), played.toList())
    }

    @Test
    fun theDownloadedRowPlaysItsFileThroughTheHook() {
        showHome()
        val row = made.single()
        val launch = launched(row) { compose.onNode(row("Harbor Lights")).performClick() }
        assertEquals(WatchKey.episode(HL, 2, 1), launch.watchKey)
        playsTheFile(launch)
    }

    @Test
    fun atTwiceTheTextSizeTheLineAndTheBarAreWhole() {
        showHome(fontScale = 2f)
        val width = context.resources.displayMetrics.widthPixels
        compose.waitUntil(5_000) { exists(hasText(OfflineHome.LINE)) }
        compose.assertWhole(OfflineHome.LINE, width)
        val words = "Copper Crown isn't downloaded. It plays once you're online."
        compose.onNode(hasScrollAction()).performScrollToNode(row("Copper Crown"))
        compose.onNode(row("Copper Crown")).performClick()
        compose.waitUntil(5_000) { exists(hasText(words)) }
        compose.assertWhole(words, width)
    }

    /** Up next's row (its playing part, which TalkBack reads as one) of [title]. */
    private fun row(title: String) = hasText(title) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    /** Whether the row of [title] is read as dimmed. */
    private fun dimmed(title: String): Boolean {
        compose.onNode(hasScrollAction()).performScrollToNode(row(title))
        return compose.onNode(row(title)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
    }

    /** A row's Play, by what TalkBack hears of it, scrolled to. */
    private fun play(label: String): SemanticsNodeInteraction {
        val button = hasContentDescription(label) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(button)
        return compose.onNode(button)
    }

    /** Where [matcher]'s node starts, as Home shows it from its top: at 1x the whole of this Home fits a phone's screen. */
    private fun top(matcher: SemanticsMatcher): Float {
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        return compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top
    }

    /**
     * What [tap] launches, the coordinator's next launch, checked as a download's play: [row]'s file by its URI, the sheet
     * never past Idle, nothing searched or grabbed.
     */
    private fun launched(row: Download, tap: () -> Unit): PlayerLaunch {
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = CoroutineScope(Dispatchers.Default)
        watching.launch { container.playback.state.collect { states += it } }
        try {
            waitForValue("the play sheet's state watched") { states.firstOrNull() }
            tap()
            val launch = runBlocking { withTimeout(LAUNCH_MILLIS) { container.playback.launches.filter { it.watchKey == row.watchKey }.first() } }
            assertEquals(PlayUris.download(row.id), launch.uri)
            assertNull("no request behind it", launch.requestId)
            assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
            assertEquals("indexer grabs", 0, grabs.get())
            return launch
        } finally {
            watching.cancel()
        }
    }

    /** Opens the player on [launch], as MainActivity does, and waits for it to play the download's file. */
    private fun playsTheFile(launch: PlayerLaunch) {
        val startedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync { compose.activity.startActivity(PlayerActivity.intent(context, launch)) }
        val player = waitForValue("the player ready on the file", READY_MILLIS) { onMain { player()?.takeIf { it.player?.playbackState == Player.STATE_READY } } }
        assertEquals(launch.uri, onMain { player.player!!.currentMediaItem?.localConfiguration?.uri?.toString() })
        onMain { player.finish() }
        // The player saves where it was as it closes: let it, before the test's end takes the marks back.
        waitForValue("the player's save on closing") { runBlocking { container.library.state(launch.watchKey).first() }?.takeIf { it.lastPlayedAt >= startedAt } }
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
        val done = row.copy(release = checkNotNull(row.release).copy(fileSize = size), size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(row.id, name))
        made += done
        storage.add(done)
        waitFor("download ${done.id} among the finished ones") { container.downloads.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        done
    }

    private fun harborEpisode(): Download {
        val name = "Harbor.Lights.S02E01.720p.WEB.h264-KESTREL.mkv"
        return Download(
            id = Download.newId(), watchKey = WatchKey.episode(HL, 2, 1), kind = DownloadKind.Episode, title = "Harbor Lights", subtitle = "S02E01 · Brine",
            showTmdbId = HL, season = 2, episode = 1, seasonEpisodes = 8, episodeName = "Brine", state = DownloadState.Done, position = -1_000L,
            release = RememberedRelease(INDEXER, "hl-s02e01", name.removeSuffix(".mkv"), null, 0, "nzb-hl-201", name), fileName = name,
            createdAt = 1, completedAt = 2, updatedAt = 2,
        )
    }

    /** An episode of [show] in progress, two minutes into its six. */
    private fun inProgress(show: Int, name: String, season: Int, episode: Int, release: RememberedRelease? = null) = WatchState(
        WatchKey.episode(show, season, episode), WatchKind.Episode, showTmdbId = show, season = season, episode = episode,
        title = WatchTitle.episode(name, season, episode), positionMs = POSITION_MS, durationMs = DURATION_MS, release = release, updatedAt = 0,
    )

    private fun harbor(row: Download) = UpNextRow(
        "Harbor Lights", "S02E01 · Brine · 4 min left", 0.33f, false, null, UpNextPlay.Resume(inProgress(HL, "Harbor Lights", 2, 1, row.release)),
        "Resume Harbor Lights S02E01",
    )

    private val meridian = UpNextRow(
        "Quiet Meridian", "S01E03 · The Lantern Room · 4 min left", 0.33f, false, null, UpNextPlay.Resume(inProgress(QM, "Quiet Meridian", 1, 3)),
        "Resume Quiet Meridian S01E03",
    )

    private val crown = UpNextRow(
        "Copper Crown", "2022 · 1 h 2 min left", 0.45f, false, null,
        UpNextPlay.Resume(WatchState(WatchKey.movie(CC), WatchKind.Movie, movieTmdbId = CC, title = "Copper Crown", positionMs = 3_000_000, durationMs = 6_720_000, updatedAt = 0)),
        "Resume Copper Crown",
    )

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** What [probe] gives once it gives something, asked every 100 ms for [timeoutMs] at most. */
    private fun <T : Any> waitForValue(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

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

    private companion object {
        /** Invented titles; nothing real has these ids. */
        const val HL = 990_811
        const val QM = 990_812
        const val CC = 990_813
        const val INDEXER = "home-offline-device-test"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"
        const val DURATION_MS = 360_000L
        const val POSITION_MS = 120_000L

        const val LAUNCH_MILLIS = 15_000L
        const val SEEDING_MILLIS = 60_000L
        const val READY_MILLIS = 30_000L
    }
}

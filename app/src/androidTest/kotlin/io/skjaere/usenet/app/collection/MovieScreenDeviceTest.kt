package io.skjaere.usenet.app.collection

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.prepareFixture
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The movie page over the real library, opened from its row in the collection: the fake catalogue's
 * Copper Crown, collected, with the release that played it remembered and its NZB stored (the
 * chapters.mkv fixture on the mock NNTP server, see SkipSegmentsDeviceTest). Play plays that release
 * without a search or a grab; once the player closes, the page and the row say how much is left.
 * Mark watched and unwatched change both; the collection toggle writes and stays, TMDB answering or
 * not; Choose release lists what the fake indexer (10.0.2.2:8089) has. TMDB is the fake; everything is
 * put back.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MovieScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val grabs = AtomicInteger()
    private lateinit var saved: AppSettings
    private var keyAdded = false
    private var collectedBefore = true
    private var release: RememberedRelease? = null

    private val copperCrown = MovieDetails(
        MOVIE, "Copper Crown", null, 2019, "A retired forger is pulled into one last job when a crown goes missing.", null, null, 118,
        ExternalIds(imdb = "tt9000012"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )

    @Before
    fun setUp() {
        home.show()
        runBlocking {
            saved = container.settings.settings.first()
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("movie-device-test")
                keyAdded = true
            }
            container.settings.updateCollectionView { it.copy(side = "Movies", movieFilter = "All", movieSort = "RecentlyAdded") }
            collectedBefore = container.library.isMovieCollected(MOVIE).first()
            val now = System.currentTimeMillis()
            container.library.addMovie(CollectedMovie(CollectedMovie.idFor(MOVIE), MOVIE, "tt9000012", "Copper Crown", 2019, addedAt = now, updatedAt = now))
        }
        tmdb = FakeTmdb(movies = mapOf(MOVIE to copperCrown))
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
    }

    @After
    fun restore() {
        // A player left open by a failure would be in front of the next test.
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>().forEach { it.finish() }
        }
        tmdb?.close()
        PlaybackCoordinator.fetchNzb = realFetch
        container.playback.cancel()
        runBlocking {
            if (::saved.isInitialized) {
                // Its key goes with it (restore only puts back the settings document).
                if (saved.indexers.none { it.id == FAKE_INDEXER } && container.settings.indexer(FAKE_INDEXER) != null) container.settings.deleteIndexer(FAKE_INDEXER)
                container.settings.restore(saved)
                if (keyAdded) container.settings.setTmdbKey(null)
            }
            // Not in progress, nor played by the fixture's release; out of the collection again.
            container.library.markMovies(listOf(MovieMark(MOVIE, "Copper Crown")), watched = false)
            release?.let { container.library.forgetRelease(WatchKey.movie(MOVIE), it) }
            if (!collectedBefore) container.library.removeMovie(MOVIE)
        }
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    private val row = hasContentDescription("Copper Crown, 2019", substring = true) and hasClickAction()

    /** The collection's Movies side, then the movie's page from its row. */
    private fun openPage() {
        val tab = hasText("Collection") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitFor(tab, 15_000)
        compose.onNode(tab).performClick()
        waitFor(row)
        compose.onNode(row).performClick()
        waitFor(hasText("Choose release") and hasClickAction())
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun show() = compose.setContent { AppTheme { AppNav() } }

    @Test
    fun playPlaysTheRememberedReleaseWithoutASearchAndThePageAndRowSayWhatIsLeft() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        val fixture = runBlocking { storedFixture() }
        // Played before for half a minute: remembered, but not in progress.
        val remembered = RememberedRelease(FAKE_INDEXER, "cc-movie-page", RELEASE, null, fixture.defaultPick!!.index, fixture.id).also { release = it }
        runBlocking { container.library.recordProgress(template(), 30_000, DURATION_MS, remembered) }
        show()
        openPage()
        compose.onNode(hasText("Not watched")).assertIsDisplayed()

        val states = CopyOnWriteArrayList<PlayState>()
        val watching = CoroutineScope(Dispatchers.Default)
        watching.launch { container.playback.state.collect { states += it } }
        try {
            compose.onNode(hasText("Play") and hasClickAction()).performClick()
            val launch = runBlocking { launchOf(WatchKey.movie(MOVIE)) }
            assertEquals("indexer grabs", 0, grabs.get())
            assertTrue("it searched: $states", states.none { it is PlayState.Searching })
            assertEquals(fixture.id, launch.release.nzbId)
            assertEquals(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = MOVIE), launch.watch)
            val launchedAt = System.currentTimeMillis()
            // As MainActivity starts it: over the page, which is in front again once it closes.
            instrumentation.runOnMainSync { compose.activity.startActivity(PlayerActivity.intent(context, launch)) }
            val player = waitFor("the player to be ready") { onMain { player()?.takeIf { it.player?.playbackState == Player.STATE_READY } } }
            // From the start: thirty seconds isn't progress to resume.
            val start = onMain { player.player!!.currentPosition }
            assertTrue("started at $start ms", start < 10_000)
            onMain { player.player!!.seekTo(SEEK_MS) }
            waitFor("the seek") { onMain { player.player!!.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition >= SEEK_MS } } }
            onMain { player.finish() }
            // The player saves where it was as it closes.
            val state = waitFor("the player's save on closing") {
                runBlocking { container.library.state(WatchKey.movie(MOVIE)).first() }?.takeIf { it.lastPlayedAt > launchedAt && it.positionMs >= SEEK_MS }
            }
            val left = "${(state.durationMs - state.positionMs + 59_999) / 60_000} min left"
            waitFor(hasText(left))
            compose.onNode(hasText("Resume") and hasClickAction()).assertIsDisplayed()
            back()
            waitFor(row and hasContentDescription(left.replace("min", "minutes"), substring = true))
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun markWatchedAndUnwatchedChangeThePageAndTheRow() {
        show()
        openPage()
        compose.onNode(hasText("Mark watched") and hasClickAction()).performClick()
        waitFor(hasText("✓ Watched"))
        compose.onNode(hasText("Play again") and hasClickAction()).assertIsDisplayed()
        back()
        waitFor(row and hasContentDescription("Watched", substring = true))

        compose.onNode(row).performClick()
        waitFor(hasText("Mark unwatched") and hasClickAction())
        compose.onNode(hasText("Mark unwatched") and hasClickAction()).performClick()
        waitFor(hasText("Not watched"))
        back()
        waitFor(row and hasContentDescription("Not watched", substring = true))
    }

    @Test
    fun theCollectionToggleWritesAndStaysOnThePage() {
        val addedAt = { runBlocking { container.library.movies.first().singleOrNull { it.tmdbId == MOVIE }?.addedAt } }
        val added = addedAt()
        show()
        openPage()
        compose.onNode(hasText("In collection ✓") and hasClickAction()).performClick()
        waitFor(hasText("+ Collection"))
        assertTrue(runBlocking { !container.library.isMovieCollected(MOVIE).first() })
        // Still on the page.
        compose.onNode(hasText("Choose release") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("+ Collection") and hasClickAction()).performClick()
        waitFor(hasText("In collection ✓"))
        // Back as it was, when it was added included.
        assertEquals(added, addedAt())
        compose.onNode(hasText("Copper Crown")).assertIsDisplayed()
    }

    @Test
    fun withoutTmdbTheToggleStaysToPutTheMovieBack() {
        // TMDB can't answer for it (no key any more, a rejected one, its cached answer too old): the page
        // is the library's alone.
        tmdb?.close()
        tmdb = FakeTmdb(movieErrors = mapOf(MOVIE to TmdbError.NoKey()))
        val addedAt = { runBlocking { container.library.movies.first().singleOrNull { it.tmdbId == MOVIE }?.addedAt } }
        val added = addedAt()
        show()
        openPage()
        waitFor(hasText(DETAILS_LOAD_FAILED, substring = true))
        compose.onNode(hasText("In collection ✓") and hasClickAction()).performClick()
        waitFor(hasText("+ Collection"))
        assertTrue(runBlocking { !container.library.isMovieCollected(MOVIE).first() })
        // Still the movie's page, Play and all.
        compose.onNode(hasText("Copper Crown")).assertIsDisplayed()
        compose.onNode(hasText("Play") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("+ Collection") and hasClickAction()).performClick()
        waitFor(hasText("In collection ✓"))
        assertEquals(added, addedAt())
    }

    @Test
    fun chooseReleaseListsTheMoviesReleases() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        runBlocking {
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (movie test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        show()
        openPage()
        compose.onNode(hasText("Choose release") and hasClickAction()).performClick()
        waitFor(hasText("Copper Crown · 2 releases from 1 indexer"), 30_000)
        assertTrue(exists(hasText("Copper.Crown.2019.1080p.BluRay.x264-GOOD", substring = true)))
        assertEquals("indexer grabs", 0, grabs.get())
        back()
        waitFor(hasText("Choose release") and hasClickAction())
    }

    /** #220: a remote's way to Choose release's list and back, on a phone: Back from the list is on Choose release again, not Play. */
    @Test
    fun withARemoteBackFromChooseReleasesListIsOnChooseRelease() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        runBlocking {
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (movie test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        show()
        openPage()
        instrumentation.setInTouchMode(false)
        try {
            val remote = Remote(compose)
            compose.onNode(hasText("Choose release") and hasClickAction()).requestFocus()
            remote.waitForFocus("Choose release")
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Copper Crown · 2 releases from 1 indexer"), 30_000)
            back()
            waitFor(hasText("Play") and hasClickAction())
            remote.waitForFocus("Choose release")
        } finally {
            instrumentation.setInTouchMode(true)
        }
    }

    /** Not collected and TMDB can't answer for it: nothing to show it by but why, and Try again; once TMDB answers, the page. */
    @Test
    fun aMovieTmdbCantAnswerForSaysSoWithTryAgain() {
        val fake = TmdbLookup.movieAged
        var fail = true
        TmdbLookup.movieAged = { c, id ->
            if (fail && id == UNANSWERED) throw TmdbException(TmdbError.Network("Unable to resolve host"))
            if (id == UNANSWERED) Aged(copperCrown.copy(tmdbId = UNANSWERED, title = "Ember Quay"), Duration.ZERO) else fake(c, id)
        }
        try {
            compose.setContent { AppTheme { MovieScreen(UNANSWERED, onBack = {}) } }
            waitFor(hasText(MOVIE_LOAD_FAILED, substring = true), 15_000)
            fail = false
            compose.onNode(hasText(TRY_AGAIN) and hasClickAction()).performClick()
            waitFor(hasText("Ember Quay"), 15_000)
            compose.onNode(hasText("Play") and hasClickAction()).assertIsDisplayed()
        } finally {
            TmdbLookup.movieAged = fake
        }
    }

    /** The player in front; on the main thread, as the player's own state is read. */
    private fun player(): PlayerActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun template() = WatchState(WatchKey.movie(MOVIE), WatchKind.Movie, movieTmdbId = MOVIE, title = "Copper Crown", updatedAt = 0)

    /** The chapters fixture prepared from its NZB and put in the store, the engine on the mock server. */
    private suspend fun storedFixture(): PreparedNzb = engineSetup("the mock on the engine, the chapters fixture prepared") {
        mockOnEngine()
        prepareFixture("chapters")
    }

    /** The coordinator's next launch for [key], or a failure as soon as the play sheet shows one. */
    private suspend fun launchOf(key: String): PlayerLaunch = withTimeout(60_000) {
        merge(
            container.playback.launches.filter { it.watchKey == key },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("didn't start: $it") },
        ).first()
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        /** Copper Crown's id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val MOVIE = 900011
        /** An invented movie, not collected; nothing real has the id. */
        const val UNANSWERED = 900093
        const val FAKE_INDEXER = "movie-device-test"
        const val RELEASE = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"
        const val DURATION_MS = 360_000L
        const val SEEK_MS = 150_000L
    }
}

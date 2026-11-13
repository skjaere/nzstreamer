package io.skjaere.usenet.app.tv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.prepareFixture
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate

/**
 * A movie watched from Home on a real TV (tv36) with nothing but a remote (TV-3), the app launched as
 * a TV launches it: Home's first focus on the movie's Up next card; OK opens its page with Resume focused; OK plays it
 * from where it stopped, the release that played it before alone (the mock NNTP server's chapters fixture, its NZB in
 * the store: no indexer asked); OK pauses; Right seeks; Back leaves the player with the focus on Resume; Back goes back
 * to Home with the focus on the card. An invented movie the TMDB seam answers for; its progress and release are put back.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvPlayerSmokeTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private var tmdb: FakeTmdb? = null
    private lateinit var saved: AppSettings
    private var keyAdded = false
    private var release: RememberedRelease? = null

    private val lantern = MovieDetails(
        MOVIE, TITLE, null, 2018, "A lighthouse keeper's last winter on the rock.", null, null, 6, ExternalIds(imdb = "tt9000411"),
        releaseDate = LocalDate.of(2018, 11, 2), status = "Released",
    )

    @Before
    fun setUp() {
        assumeTv()
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        home.show()
        runBlocking {
            saved = container.settings.settings.first()
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("tv-player-smoke-test")
                keyAdded = true
            }
            val fixture = storedFixture()
            // Two minutes into its six, played last of all, by the fixture's release.
            val played = RememberedRelease(INDEXER, "tv-player-smoke", RELEASE, null, fixture.defaultPick!!.index, fixture.id).also { release = it }
            val template = WatchState(WatchKey.movie(MOVIE), WatchKind.Movie, movieTmdbId = MOVIE, title = TITLE, updatedAt = 0)
            container.library.recordProgress(template, POSITION_MS, DURATION_MS, played)
        }
        tmdb = FakeTmdb(movies = mapOf(MOVIE to lantern))
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        // A player left open by a failure would be in front of the next test.
        instrumentation.runOnMainSync { players(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).forEach { it.finish() } }
        tmdb?.close()
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        container.playback.cancel()
        runBlocking {
            if (::saved.isInitialized) {
                container.settings.restore(saved)
                if (keyAdded) container.settings.setTmdbKey(null)
            }
            // Out of Up next again: not in progress, nor played by the fixture's release.
            container.library.markMovies(listOf(MovieMark(MOVIE, TITLE)), watched = false)
            release?.let { container.library.forgetRelease(WatchKey.movie(MOVIE), it) }
        }
        home.restore()
    }

    @Test
    fun aMovieFromHomesCardPlaysPausesSeeksAndBackGoesToResumeThenToTheCard() {
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            remote.waitForHomeFocus(timeoutMs = 15_000)
            remote.waitForFocus(TITLE, timeoutMs = 10_000)
            assertTrue("Home's first focus is the movie's card; it is on ${remote.focused()}", remote.isFocused(inTvHome) && "4 min left" in remote.focused())

            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus(RESUME, timeoutMs = 15_000)
            // Past the page's guard against the OK that opened it.
            Thread.sleep(600)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

            val player = waitFor("the player to play", 60_000) { onMain { player()?.takeIf { it.player?.let { p -> p.isPlaying && p.currentPosition > 0 } == true } } }
            val from = onMain { player.player!!.currentPosition }
            assertTrue("resumed at $from ms, saved at $POSITION_MS", from in POSITION_MS - 5_000..POSITION_MS + 15_000)

            hideControls(player)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("OK to pause") { onMain { (!player.player!!.playWhenReady).takeIf { it } } }

            hideControls(player)
            val paused = onMain { player.player!!.currentPosition }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("Right to seek on") { onMain { player.player!!.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition >= paused + 9_000 } } }

            // Paused, the player brings its controls up again once the seek is in (media3's own, for a paused player): a
            // Back then hides them first, as it does wherever they are up, and the next leaves.
            Thread.sleep(1_500)
            if (onMain { playerView(player).isControllerFullyVisible }) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor("Back to hide the controls") { onMain { (!playerView(player).isControllerFullyVisible).takeIf { it } } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back to leave the player") { onMain { (player.isDestroyed || player.isFinishing).takeIf { it } } }
            waitFor("the app in front again") { scenario.state.takeIf { it == Lifecycle.State.RESUMED } }
            // Resume, from where the player was left once its save on closing is in: the focus stays on it as it says so.
            try {
                compose.waitUntil(10_000) { remote.focused().let { "Resume · " in it && RESUME !in it } }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("the focus isn't on Resume from further in; it is on \"${remote.focused()}\"", e)
            }

            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForHomeFocus(timeoutMs = 10_000)
            remote.waitForFocus(TITLE)
            assertTrue("back on the movie's card; focus on ${remote.focused()}", remote.isFocused(inTvHome) && TITLE in remote.focused())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** The chapters fixture prepared from its NZB and put in the store, the engine on the mock server (#89). */
    private suspend fun storedFixture(): PreparedNzb = engineSetup("the mock on the engine, the chapters fixture prepared") {
        mockOnEngine()
        prepareFixture("chapters")
    }

    /** The player's controls hidden, as they are while a remote watches: OK and the arrows then act on the playing. */
    private fun hideControls(player: PlayerActivity) {
        onMain { playerView(player).hideController() }
        waitFor("the controls to hide") { onMain { (!playerView(player).isControllerFullyVisible).takeIf { it } } }
        Thread.sleep(800)
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun players(vararg stages: Stage): List<PlayerActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>()
    }

    /** The player in front; on the main thread, as the player's own state is read. */
    private fun player(): PlayerActivity? = players(Stage.RESUMED).firstOrNull()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        /** An invented movie; nothing real has the id. */
        const val MOVIE = 900411
        const val TITLE = "Tidewater Lantern"
        const val RELEASE = "Tidewater.Lantern.2018.720p.WEB.h264-NZST"
        const val INDEXER = "tv-player-smoke-test"
        const val POSITION_MS = 120_000L
        /** The chapters fixture's six minutes. */
        const val DURATION_MS = 360_000L
        /** The Movie page's Play for a movie two minutes in. */
        const val RESUME = "Resume · 2:00"
    }
}

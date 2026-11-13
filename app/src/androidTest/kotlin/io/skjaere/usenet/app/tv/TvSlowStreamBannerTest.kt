package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.ThrottledOpener
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.dataSourcesThrough
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.focusedWords
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slow-stream banner on a real TV (tv36), driven by a remote (W6, QlTvDip): it never takes the focus. With the
 * controls hidden it doesn't have it; Up brings the controls on play/pause, the banner in its one-row form beside them,
 * and a banner with nothing to press isn't where Down goes. With Try a smaller release on it (QlTvDipControls), Down from
 * play/pause reaches the button and Up goes back. tv36 delivers a keyboard's keys but moves no focus for them, so the
 * moves are made by the views' own focus search. The release is dip.mkv held to 0.4 of
 * its bitrate ([ThrottledOpener]), posted on a server inside the test's process ([LocalNntp]); the film is invented
 * (TMDB id 900027).
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvSlowStreamBannerTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realDataSources = PlayerActivity.dataSources
    private val realLoadControl = PlayerActivity.loadControlFor
    private val realTimings = PlayerActivity.timings
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Tidewater Signal", movieTmdbId = FILM))
    private val throttle = ThrottledOpener(container.opener)

    @Before
    fun setUp() {
        assumeTv()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.dataSources = dataSourcesThrough(throttle)
        PlayerActivity.loadControlFor = { DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 8_000, 500, 500).build() }
        PlayerActivity.timings = PlayerActivity.Timings(dip = DipDetector.Timings(graceMs = 1_000, windowMs = 3_000, fallMs = 500, recoverMs = 2_000, shownForMs = 1_000))
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.loadControlFor = realLoadControl
        PlayerActivity.timings = realTimings
        local.close()
        runBlocking {
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Tidewater Signal", updatedAt = 0), 0, 20_000)
        }
    }

    /** dip.mkv held to 0.4 of its bitrate, launched as [guid] with the play's list [candidates]; [check] with the banner up and the controls on play/pause. */
    private fun slowPlay(guid: String, candidates: List<RankedItem>, check: suspend (ActivityScenario<PlayerActivity>) -> Unit) = runBlocking<Unit> {
        val clip = fixtures.media("dip.mkv")
        val release = fixtures.release("dip.mkv", clip, 32_000, guid, TITLE) { emptySet() }
        throttle.bytesPerSecond = clip.size * 4L / 10 / 20
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, guid, 0, candidates))).use { scenario ->
            fun playerView() = scenario.read { a -> views(a.window.decorView).filterIsInstance<PlayerView>().single() }
            waitFor("the slow-stream banner", timeoutMs = 40_000) { scenario.read { a -> true.takeIf { banner(a) } } }
            assertTrue("the banner took the focus", scenario.read { a -> focusedWords(a).none { it.startsWith(HEADLINE) || it == TRY_SMALLER } && !playerView().isControllerFullyVisible })

            // Up brings the controls, on play/pause; the banner stays, one row, without the focus.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            waitFor("the controls on play/pause") {
                scenario.read { a -> true.takeIf { playerView().isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause } }
            }
            assertTrue(scenario.read { a -> banner(a) && focusedWords(a).none { it.startsWith(HEADLINE) || it == TRY_SMALLER } })
            check(scenario)
        }
    }

    private fun banner(a: PlayerActivity) = overlayWords(a).any { it.startsWith(HEADLINE) }

    @Test
    fun theBannerNeverTakesTheRemotesFocus() = slowPlay("tidewater-signal-tv", emptyList()) { scenario ->
        // Down from play/pause: not the banner, which has nothing to press.
        scenario.onActivity { a -> a.currentFocus!!.focusSearch(View.FOCUS_DOWN)?.requestFocus() }
        assertTrue(scenario.read { a -> focusedWords(a).none { it.startsWith(HEADLINE) } })
    }

    /** With a release in the list estimated at 2 Mbps beside the playing one's 10: Down from play/pause reaches the button, Up goes back. */
    @Test
    fun downFromPlayPauseReachesTrySmallerReleaseAndUpGoesBack() = slowPlay(
        "tidewater-signal-tv-big",
        listOf(sized("tidewater-signal-tv-big", TITLE, 10), sized("tidewater-signal-tv-small", "Tidewater.Signal.2026.720p.WEB.h264-DIP", 2)),
    ) { scenario ->
        assertTrue(scenario.read { a -> TRY_SMALLER in overlayWords(a) })
        scenario.onActivity { a -> a.currentFocus!!.focusSearch(View.FOCUS_DOWN)!!.requestFocus() }
        waitFor("Try a smaller release to have the focus from play/pause") { scenario.read { a -> true.takeIf { TRY_SMALLER in focusedWords(a) } } }
        scenario.onActivity { a -> a.currentFocus!!.focusSearch(View.FOCUS_UP)!!.requestFocus() }
        waitFor("play/pause to have the focus again") { scenario.read { a -> true.takeIf { a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause } } }
        assertTrue(scenario.read { a -> TRY_SMALLER !in focusedWords(a) })
    }

    /** A search's release [guid] titled [title], the ranker estimating it at [mbps]. */
    private fun sized(guid: String, title: String, mbps: Long) = fixtures.candidate(guid, title).let { it.copy(ranked = it.ranked.copy(estimatedBitrate = mbps * 1_000_000)) }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900027
        const val INDEXER = "tv-slow-stream-banner-test"
        const val HEADLINE = "Streaming is slower than this video needs"
        const val TRY_SMALLER = "Try a smaller release"
        const val TITLE = "Tidewater.Signal.2026.1080p.BluRay.REMUX.AVC-DIP"
    }
}

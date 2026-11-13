package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.focus.FocusDirection
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.focusedWords
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The missing-parts prompt on a real TV (tv36), driven by a remote (W6): with the controls hidden it takes the focus on
 * Keep watching, so an OK meant to pause never switches the release, and a held OK's repeats press nothing; Up brings the
 * controls, which take the focus to play/pause, the prompt in its one-row form; Down from play/pause reaches Keep
 * watching, Right goes on to Try another release; as the controls hide it takes the focus back; Back keeps watching. tv36
 * delivers a keyboard's keys but moves no focus for them, so the focus moves are made as its arrows would: by the views'
 * own focus search, and inside the prompt by its FocusManager. The release is gaps-faststart.mp4 with three parts missing,
 * posted on a server inside the test's process ([LocalNntp]); the film is invented (TMDB id 900025).
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvMissingPartsPromptTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = FILM))

    @Before
    fun setUp() {
        assumeTv()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
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
        local.close()
        runBlocking {
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Lantern Field", updatedAt = 0), 0, 60_000)
        }
    }

    @Test
    fun theRemotesFocusIsOnKeepWatchingWithTheControlsHiddenAndReachesTheButtonsFromPlayPause() = runBlocking<Unit> {
        val mp4 = fixtures.media("gaps-faststart.mp4")
        val release = fixtures.release("gaps-faststart.mp4", mp4, 2_000, "tv-prompt", "Lantern.Field.2026.1080p.WEB.h264-GAPS") { posted ->
            listOf(20_000L, 26_000L, 32_000L).map(posted::idAt).toSet()
        }
        // Another release to offer, so the prompt asks rather than says the count alone.
        val candidates = listOf(fixtures.candidate("lantern-field-tv", release.prepared.title), fixtures.candidate("lantern-field-other", "Lantern.Field.2026.720p.WEB.h264-OTHER"))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, "lantern-field-tv", 0, candidates)))
            .use { scenario ->
                fun playerView() = scenario.read { a -> views(a.window.decorView).filterIsInstance<PlayerView>().single() }
                waitFor("the prompt with the focus on Keep watching", timeoutMs = 40_000) {
                    scenario.read { a -> true.takeIf { "Keep watching" in focusedWords(a) && "This release is missing parts (3 so far)" in overlayWords(a) } }
                }
                assertTrue("the controls came up", scenario.read { !playerView().isControllerFullyVisible })

                // A held OK's repeat (one meant to pause, pressed on): nothing.
                delay(500)
                val down = SystemClock.uptimeMillis() - 1_000
                instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
                instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                delay(300)
                assertTrue(scenario.read { a -> "Keep watching" in focusedWords(a) && a.player?.isPlaying == true })

                // Up brings the controls, and media3 the focus to play/pause; the prompt turns to one row.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                waitFor("the controls on play/pause") {
                    scenario.read { a -> true.takeIf { playerView().isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause } }
                }
                assertTrue(scenario.read { a -> "Try another release? It starts where you are." in overlayWords(a) && focusedWords(a).isEmpty() })

                // Down from play/pause: Keep watching; Right: Try another release.
                scenario.onActivity { a -> a.currentFocus!!.focusSearch(View.FOCUS_DOWN)!!.requestFocus() }
                waitFor("Keep watching to have the focus from play/pause") { scenario.read { a -> true.takeIf { "Keep watching" in focusedWords(a) } } }
                scenario.onActivity { a -> a.promptFocus!!.moveFocus(FocusDirection.Right) }
                waitFor("Try another release to have the focus") { scenario.read { a -> true.takeIf { "Try another release" in focusedWords(a) } } }

                // The controls hide: back on Keep watching.
                scenario.onActivity { playerView().hideController() }
                waitFor("Keep watching to have the focus again") { scenario.read { a -> true.takeIf { "Keep watching" in focusedWords(a) && !playerView().isControllerFullyVisible } } }

                // Back keeps watching: the prompt goes, the film plays on.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor("the prompt to go") { scenario.read { a -> true.takeIf { "Keep watching" !in overlayWords(a) } } }
                delay(500)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertTrue(scenario.read { a -> a.player?.isPlaying == true })
            }
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900025
        const val INDEXER = "tv-missing-parts-prompt-test"
    }
}

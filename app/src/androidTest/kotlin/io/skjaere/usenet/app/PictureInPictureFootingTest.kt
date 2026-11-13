package io.skjaere.usenet.app

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The device tests' footing for picture-in-picture. The run refuses the app the window, as a user who turned it off in
 * Android's settings has it ([AppTestRunner]): a test that leaves a playing player, by Home or as ActivityScenario stops
 * one (an activity of its own in a task of its own), stops it as before, rather than wait out ActivityScenario's 45 s for
 * a stop that the window never brings. [PictureInPictureAllowed] lets the app in for the window's tests and puts the
 * refusal back. The film is invented ("Quarry Light", TMDB id 900101), posted on a server inside the test's process
 * ([LocalNntp]); its progress is put back after each test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PictureInPictureFootingTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM))

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        local.close()
        runBlocking {
            // Out of Up next.
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 60_000)
        }
    }

    @Test
    fun theRunRefusesTheAppPictureInPicture() {
        assertEquals(PictureInPictureAppOp.IGNORE, PictureInPictureAppOp.mode())
    }

    /** The screen on and unlocked during the test too: Android lets nothing into the window over the lock screen. */
    @Test
    fun theRuleAllowsItDuringItsTestAndRefusesItAgainAfter() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        var during: List<Any?> = emptyList()
        val test = object : Statement() {
            override fun evaluate() {
                during = listOf(PictureInPictureAppOp.mode(), power.isInteractive, keyguard.isKeyguardLocked)
            }
        }
        PictureInPictureAllowed().apply(test, Description.EMPTY).evaluate()
        assertEquals("during the test: the app-op, the screen on, locked", listOf(PictureInPictureAppOp.ALLOW, true, false), during)
        assertEquals("after the test", PictureInPictureAppOp.IGNORE, PictureInPictureAppOp.mode())
    }

    /** Home from a playing film, the app refused the window: the player stops and lets the film go, as before the window. */
    @Test
    fun homeStopsAPlayingPlayer() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        val release = fixtures.release("gaps-faststart.mp4", fixtures.media("gaps-faststart.mp4"), PART, GUID, RELEASE_TITLE) { emptySet() }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, GUID, 0))).use { scenario ->
            waitFor("the film to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            pressHome()
            waitFor("the player to stop behind the home screen") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
            assertNull("a player is left", scenario.read { it.player })
            assertFalse("in picture-in-picture", scenario.read { it.isInPictureInPictureMode })
            // Closing a stopped activity waits out ActivityScenario's 45 s timeout: it is finished here.
            scenario.onActivity { it.finish() }
            waitFor<Boolean>("the player to close") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
        }
    }

    private companion object {
        const val FILM = 900101
        const val TITLE = "Quarry Light"
        const val INDEXER = "picture-in-picture-footing-test"
        const val GUID = "quarry-light-home"
        const val RELEASE_TITLE = "Quarry.Light.2026.1080p.WEB.h264-QUARRY"

        /** gaps-faststart.mp4 (136 KB) in five parts. */
        const val PART = 32_000
    }
}

package io.skjaere.usenet.app.tv

import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PictureInPicturePlays
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.app.wakeAndUnlock
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W106 on a real TV (tv36): the TV put to sleep with a film playing (as its remote's power key, or CEC standby, does) and
 * woken again, the player waits paused where the film was, rather than playing to an empty room; a paired phone is told
 * it is paused. The sleep and the wake are the shell's keys; the TV is woken after the test, whatever happened. The film
 * plays from a server inside the test's process ([PictureInPicturePlays]).
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvAsleepPausesTest {
    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val power = context.getSystemService(PowerManager::class.java)
    private val plays = PictureInPicturePlays(INDEXER)

    /** Past [assumeTv]: on a phone the class is skipped, and there is nothing to put back. */
    private var set = false

    @Before
    fun setUp() {
        assumeTv()
        set = true
        runBlocking { plays.setUp() }
    }

    @After
    fun tearDown() {
        if (!set) return
        try {
            wakeAndUnlock()
        } finally {
            plays.close()
            runBlocking { plays.forget() }
        }
    }

    @Test
    fun asleepAndWokenThePlayerWaitsPausedAtItsPlace() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("copper-tide-tv-asleep"))).use { scenario ->
            plays.playing(scenario)
            waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 3_000 } } }
            val (before, at) = scenario.read { a -> a.player!! to a.player!!.currentPosition }
            instrumentation.uiAutomation.shell("input keyevent KEYCODE_SLEEP")
            waitFor("the TV asleep") { true.takeIf { !power.isInteractive } }
            waitFor("the player stopped") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
            wakeAndUnlock()
            waitFor("the player in front again") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            val after = waitFor("the player made again, ready", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it !== before && it.playbackState == Player.STATE_READY } }
            }
            assertFalse("playing after the TV woke", scenario.read { after.playWhenReady })
            val position = scenario.read { after.currentPosition }
            assertTrue("at $position after the TV woke, where the film was at $at", position in at..at + 3_000)
            delay(1_500)
            scenario.read {
                assertFalse("playing a moment later", after.isPlaying || after.playWhenReady)
                assertEquals("still where it waited", position, after.currentPosition)
            }
            waitFor("paused, as a paired phone is told") { container.playerInFront.now.value?.takeIf { it.state == Playback.Paused } }
            plays.finish(scenario)
        }
    }

    private companion object {
        const val INDEXER = "tv-asleep-pauses-test"
    }
}

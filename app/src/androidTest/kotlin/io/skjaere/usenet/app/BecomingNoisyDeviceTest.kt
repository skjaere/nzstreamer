package io.skjaere.usenet.app

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.ControlsPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Android's "becoming noisy" on a phone: headphones or an adapter unplugged, and the play pauses, as Media3's own handling
 * paused it before the app's watcher took over; a burst of rewind and fast-forward taps waiting meanwhile seeks first. The
 * outputs listed are the phone's own; the broadcast is called on the player's watcher as Android would send it
 * ([NoisyPlays]). As the player closes, or another play starts in it, its watcher goes.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BecomingNoisyDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plays = NoisyPlays(FILM, INDEXER)

    @Before
    fun setUp() {
        assumeFalse("a TV: TvBecomingNoisyTest", context.container.television)
        runBlocking { plays.setUp() }
    }

    @After
    fun tearDown() {
        plays.close()
        runBlocking { plays.forget() }
    }

    private fun play(check: suspend (ActivityScenario<PlayerActivity>) -> Unit) = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("saltmarsh-evening-phone"))).use { scenario ->
            plays.playing(scenario)
            check(scenario)
        }
    }

    /** Whatever the phone lists: its kinds at DEBUG, never a device's name. */
    @Test
    fun aNoisyPausesThePlay() = play { scenario ->
        val mark = plays.logMark()
        plays.noisy(scenario)
        assertFalse("played on", scenario.read { it.player!!.playWhenReady })
        delay(500)
        assertFalse(scenario.read { it.player!!.isPlaying })
        val (heard, said) = plays.audioLines(mark).also { assertEquals(it.toString(), 2, it.size) }
        assertTrue(heard, Regex("DEBUG BecomingNoisy: Audio output going away; outputs listed: [\\w ,()-]+; gone in the last 2 s: none").matches(heard))
        assertEquals("INFO PlayerActivity: Audio output going away: paused", said)
    }

    /** Through the controls' player, which runs the burst's seek before it pauses. */
    @Test
    fun aNoisyDuringABurstOfFastForwardTapsSeeksFirst() = play { scenario ->
        val (target, at) = scenario.read { a ->
            val shown = views(a.window.decorView).filterIsInstance<PlayerView>().single().player as ControlsPlayer
            shown.seekForward()
            assertTrue("no burst under way", shown.seeking)
            val target = shown.currentPosition
            NoisyPlays.watcherOf(a).noisy()
            assertFalse("the burst still waits", shown.seeking)
            target to a.player!!.currentPosition
        }
        assertTrue("the player is at $at, the burst went to $target", abs(at - target) < 500)
        assertFalse("played on", scenario.read { it.player!!.playWhenReady })
        assertFalse(scenario.read { it.player!!.isPlaying })
    }

    /** Media3's own handling stays off (PlayerFactory): Android holds the watcher's receiver alone. */
    @Test
    fun theWatcherIsTheOnlyReceiverOfTheBroadcast() = runBlocking<Unit> {
        plays.onlyTheWatcherHearsTheBroadcast()
    }

    @Test
    fun afterThePlayerClosesItsWatcherIsGone() = runBlocking<Unit> {
        plays.closingLeavesNoWatcher(personal = null)
    }

    @Test
    fun aPlayStartedInThePlayerLeavesOnlyItsWatcher() = runBlocking<Unit> {
        plays.aPlayStartedHereLeavesOnlyItsWatcher(personal = null)
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900028
        const val INDEXER = "becoming-noisy-device-test"
    }
}

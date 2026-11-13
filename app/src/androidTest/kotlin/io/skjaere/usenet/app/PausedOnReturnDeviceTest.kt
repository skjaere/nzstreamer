package io.skjaere.usenet.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * W106 on a phone: a player made again after the player was stopped waits paused where the film was, its picture shown,
 * rather than playing the moment the screen is back. The screen off and on in the picture-in-picture window (the window
 * comes back with Play among its buttons, which plays it), and full screen (media3's controls up, as a pause brings them;
 * Android's session says it is paused, and a headset's Play plays it); Home and back to the player. A recreation keeps
 * whether it played: playing, it plays on; paused, it stays paused; the state saved for a process Android lets go says
 * it waits. A new play that reaches a stopped player plays, as any new play does, and one that reaches a player made again
 * after a stop has the window's Pause from the moment it comes. The screen goes off and on through the shell's keys, and
 * is woken and unlocked after each test, whatever happened. The film plays from a server inside the test's process
 * ([PictureInPicturePlays]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PausedOnReturnDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val plays = PictureInPicturePlays(INDEXER)

    @Before
    fun setUp() {
        plays.assumeWindow()
        runBlocking { plays.setUp() }
    }

    @After
    fun restore() {
        try {
            wakeAndUnlock()
        } finally {
            plays.close()
            runBlocking { plays.forget() }
        }
    }

    private suspend fun launch(guid: String): ActivityScenario<PlayerActivity> = launch(plays.launch(guid))

    private fun launch(launch: PlayerLaunch): ActivityScenario<PlayerActivity> = ActivityScenario.launch(PlayerActivity.intent(context, launch))

    @Test
    fun theScreenOffAndOnInTheWindowLeavesThePlayPaused() = runBlocking<Unit> {
        launch("copper-tide-window-screen-off").use { scenario ->
            plays.playing(scenario)
            pressHome()
            awaitPinned(scenario.read { it })
            val (before, at) = aFewSecondsIn(scenario)
            screenOff(scenario)
            wakeAndUnlock()
            val after = madeAgain(scenario, before)
            assertPausedAt(scenario, after, at, "after the screen came back in the window")
            assertTrue("in the window again", scenario.read { it.isInPictureInPictureMode })
            waitFor("Play among the window's buttons") { scenario.read { a -> a.windowButtons.map { it.title.toString() }.takeIf { it == listOf(REWIND, "Play", FORWARD) } } }
            scenario.read { a -> a.windowButtons.first { it.title.toString() == "Play" } }.actionIntent.send()
            waitFor("playing in the window after its Play") { scenario.read { after.isPlaying.takeIf { it } } }
            plays.finish(scenario)
        }
    }

    @Test
    fun theScreenOffAndOnFullScreenLeavesThePlayPaused() = runBlocking<Unit> {
        launch("copper-tide-full-screen-off").use { scenario ->
            plays.playing(scenario)
            val (before, at) = aFewSecondsIn(scenario)
            screenOff(scenario)
            wakeAndUnlock()
            waitFor("the player in front again") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            val after = madeAgain(scenario, before)
            assertPausedAt(scenario, after, at, "after the screen came back full screen")
            assertFalse("in the window", scenario.read { it.isInPictureInPictureMode })
            waitFor("media3's controls up, as a pause brings them") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            val platform = scenario.read { a -> MediaController(context, a.session!!.platformToken) }
            waitFor("Android's session paused") { platform.playbackState?.takeIf { it.state == PlaybackState.STATE_PAUSED } }
            waitFor("the app's session to take media keys") { mediaButtonSessionIsOurs().takeIf { it } }
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            waitFor("playing after a headset's Play") { scenario.read { after.isPlaying.takeIf { it } } }
            plays.finish(scenario)
        }
    }

    /** Picture-in-picture off for the app, so Home stops the player; back to it, as Recents brings its task. */
    @Test
    fun homeAndBackLeavesThePlayPaused() = runBlocking<Unit> {
        PictureInPictureAppOp.set(PictureInPictureAppOp.IGNORE)
        launch("copper-tide-home-and-back").use { scenario ->
            plays.playing(scenario)
            val (before, at) = aFewSecondsIn(scenario)
            val task = scenario.read { it.taskId }
            pressHome()
            waitFor("the player to stop behind the home screen") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
            context.getSystemService(ActivityManager::class.java).appTasks.first { it.taskInfo.taskId == task }.moveToFront()
            waitFor("the player in front again") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            val after = madeAgain(scenario, before)
            assertPausedAt(scenario, after, at, "back after Home")
            plays.finish(scenario)
        }
    }

    /** As a change of dark mode, the font or the language makes the player again. */
    @Test
    fun aRecreationWhilePlayingPlaysOn() = runBlocking<Unit> {
        launch("copper-tide-recreated-playing").use { scenario ->
            plays.playing(scenario)
            val (before, _) = aFewSecondsIn(scenario)
            scenario.recreate()
            val after = madeAgain(scenario, before)
            assertTrue("playing after a recreation of a playing player", scenario.read { after.playWhenReady })
            waitFor("the film to play on", timeoutMs = 10_000) { scenario.read { after.isPlaying.takeIf { it } } }
            plays.finish(scenario)
        }
    }

    @Test
    fun aRecreationWhilePausedStaysPaused() = runBlocking<Unit> {
        launch("copper-tide-recreated-paused").use { scenario ->
            plays.playing(scenario)
            aFewSecondsIn(scenario)
            scenario.onActivity { it.player!!.pause() }
            val (before, at) = scenario.read { a -> a.player!! to a.player!!.currentPosition }
            scenario.recreate()
            val after = madeAgain(scenario, before)
            assertPausedAt(scenario, after, at, "after a recreation of a paused player")
            plays.finish(scenario)
        }
    }

    /** A new play reaching the player while it is stopped behind the home screen (onNewIntent) plays as it comes. */
    @Test
    fun aNewPlayAfterAStopPlays() = runBlocking<Unit> {
        PictureInPictureAppOp.set(PictureInPictureAppOp.IGNORE)
        launch("copper-tide-before-the-new-play").use { scenario ->
            plays.playing(scenario)
            aFewSecondsIn(scenario)
            pressHome()
            waitFor("the player to stop behind the home screen") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
            val second = plays.launch("copper-tide-the-new-play")
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the new play to play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri && it.isPlaying } }
            }
            plays.finish(scenario)
        }
    }

    /**
     * A new play reaching a player made again after a stop, and played there, plays as it comes: the window's buttons say
     * Pause from the moment it comes (onNewIntent), not Play until its own player is made. They are read first thing after
     * the turn the new play came in.
     */
    @Test
    fun aNewPlayAfterAStopHasTheWindowsPauseAsItComes() = runBlocking<Unit> {
        launch("copper-tide-before-the-next-play").use { scenario ->
            plays.playing(scenario)
            val (before, _) = aFewSecondsIn(scenario)
            screenOff(scenario)
            wakeAndUnlock()
            waitFor("the player in front again") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            val after = madeAgain(scenario, before)
            scenario.onActivity { after.play() }
            waitFor("Pause among the window's buttons as it plays") { scenario.read { a -> windowTitles(a).takeIf { it == listOf(REWIND, "Pause", FORWARD) } } }
            val asItCame = CompletableDeferred<List<String>>()
            scenario.onActivity { a ->
                a.addOnNewIntentListener { Handler(Looper.getMainLooper()).postAtFrontOfQueue { asItCame.complete(windowTitles(a)) } }
            }
            val second = plays.launch("copper-tide-the-next-play")
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals("the window's buttons as the new play came", listOf(REWIND, "Pause", FORWARD), withTimeout(10_000) { asItCame.await() })
            waitFor("the new play to play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri && it.isPlaying } }
            }
            plays.finish(scenario)
        }
    }

    /**
     * The state a process Android lets go is made again from says the player waits, as the stop said. From Android 9 it is
     * saved after the stop, as here; on Android 8 before it, from the player still playing, as the test then has it saved
     * for a play's first player (last: it marks the activity's lifecycle stopped). A recreation's round trip of it is
     * [aRecreationWhilePausedStaysPaused]'s.
     */
    @Test
    fun theStateSavedForAProcessLetGoSaysItWaits() = runBlocking<Unit> {
        PictureInPictureAppOp.set(PictureInPictureAppOp.IGNORE)
        launch("copper-tide-saved-after-the-stop").use { scenario ->
            plays.playing(scenario)
            aFewSecondsIn(scenario)
            val saved = savedAs {
                pressHome()
                waitFor<Boolean>("the player to stop behind the home screen") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
            }
            assertFalse("saved after the stop as playing", saved.getBoolean(PlayerActivity.KEY_PLAYING, true))
            plays.finish(scenario)
        }
        launch("copper-tide-saved-before-the-stop").use { scenario ->
            plays.playing(scenario)
            val saved = Bundle()
            scenario.onActivity { a -> instrumentation.callActivityOnSaveInstanceState(a, saved) }
            assertFalse("saved before the stop as playing", saved.getBoolean(PlayerActivity.KEY_PLAYING, true))
            plays.finish(scenario)
        }
    }

    /** The state Android saves for the player as [stop] stops it. */
    private suspend fun savedAs(stop: suspend () -> Unit): Bundle {
        val saved = AtomicReference<Bundle>()
        val app = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
                if (activity is PlayerActivity) saved.set(outState)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            stop()
            return waitFor("the player's state saved") { saved.get() }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    /** The player once the film is a few seconds in, and where it is then. */
    private suspend fun aFewSecondsIn(scenario: ActivityScenario<PlayerActivity>): Pair<ExoPlayer, Long> {
        waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 3_000 } } }
        return scenario.read { a -> a.player!! to a.player!!.currentPosition }
    }

    /** The screen off, as its power key turns it off: the player stops and lets its player go. */
    private suspend fun screenOff(scenario: ActivityScenario<PlayerActivity>) {
        instrumentation.uiAutomation.shell("input keyevent KEYCODE_SLEEP")
        waitFor("the screen off") { true.takeIf { !power.isInteractive } }
        waitFor("the player stopped") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
    }

    /** The player made in [before]'s place, once it is ready to play. */
    private suspend fun madeAgain(scenario: ActivityScenario<PlayerActivity>, before: ExoPlayer): ExoPlayer =
        waitFor("the player made again, ready", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it !== before && it.playbackState == Player.STATE_READY } } }

    /**
     * [player] paused at about [at], where the film was as it stopped ([case]), its picture shown there, and still paused a
     * moment later.
     */
    private suspend fun assertPausedAt(scenario: ActivityScenario<PlayerActivity>, player: ExoPlayer, at: Long, case: String) {
        assertFalse("playing $case", scenario.read { player.playWhenReady })
        val position = scenario.read { player.currentPosition }
        assertTrue("$case at $position, where the film was at $at", position in at..at + 3_000)
        waitFor("the picture shown $case") { scenario.read { a -> (shutter(a).visibility != View.VISIBLE).takeIf { it } } }
        delay(1_500)
        scenario.read { a ->
            assertFalse("playing $case, a moment later", player.isPlaying || player.playWhenReady)
            assertEquals("still where it waited $case", position, player.currentPosition)
            assertTrue("the same player $case", a.player === player)
        }
    }

    private fun playerView(a: PlayerActivity) = views(a.window.decorView).filterIsInstance<PlayerView>().single()

    /** The window's buttons as the player has told Android of them, by their titles. */
    private fun windowTitles(a: PlayerActivity): List<String> = a.windowButtons.map { it.title.toString() }

    /** media3's shutter, black over the picture until the player's first frame. */
    private fun shutter(a: PlayerActivity): View = playerView(a).findViewById(androidx.media3.ui.R.id.exo_shutter)

    private companion object {
        const val INDEXER = "paused-on-return-device-test"

        /** The window's seeks' titles (PipWindowMenu). */
        const val REWIND = "Rewind 10 seconds"
        const val FORWARD = "Fast-forward 10 seconds"
    }
}

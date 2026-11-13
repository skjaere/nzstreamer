package io.skjaere.usenet.app

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaving a player that has been in the picture-in-picture window, as the app's tasks then are (L7). Played from the app,
 * the player goes into a task of its own as the window opens; expanded, Android 12 and later put it back on top of the
 * app's screens, so Back lands there as before. A player alone in its task after the window (Android 8 to 11 always, later
 * ones once the app's own task has gone; here a player started straight into a task of its own) brings the app's
 * screens forward as it leaves, rather than show whatever was behind the window. One never in the window leaves as
 * before. Another film played from the app's screens while the window floats over them (PipWindowNewPlay) pauses the
 * window's play as the play sheet starts it, and Android then expands the window with it, the same player; cancelled in
 * the sheet, it leaves the window paused, the window clear of the sheet's Cancel. The app is let into the window for each
 * test ([PictureInPictureAllowed]); the films play from a server inside the test's process ([PictureInPicturePlays]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PictureInPictureChainDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val plays = PictureInPicturePlays(INDEXER)

    @Before
    fun setUp() {
        plays.assumeWindow()
        runBlocking { plays.setUp() }
    }

    @After
    fun restore() {
        plays.close()
        runBlocking { plays.forget() }
    }

    @Test
    fun playedFromTheAppAndExpandedBackLandsOnTheApp() = runBlocking<Unit> {
        val launch = plays.launch("copper-tide-chain")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
            waitFor("the app") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
            main.onActivity { PlayerActivity.start(it, launch) }
            val player = waitFor("the film to play", timeoutMs = 30_000) { onMain { live<PlayerActivity>(Stage.RESUMED).firstOrNull { it.player?.isPlaying == true } } }
            val appsTask = main.read { it.taskId }
            assertEquals("the player on the app's screens", appsTask, onMain { player.taskId })
            pressHome()
            awaitPinned(player)
            assertTrue("the player in a task of its own in the window", onMain { player.taskId != appsTask })
            expandFromMenu(player)
            waitFor("the player full screen") { onMain { player.takeIf { it in live<PlayerActivity>(Stage.RESUMED) } } }
            assertEquals("the player back on the app's screens", appsTask, onMain { player.taskId })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close") { onMain { true.takeIf { player.isDestroyed } } }
            waitFor<Boolean>("the app's screens") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
        }
    }

    /**
     * Started straight into a task of its own, the player is still that task's root after the window: leaving, it brings
     * the app's screens forward (started anew here, as no task of the app's is left) and goes with its task.
     */
    @Test
    fun aPlayerAloneInItsTaskAfterTheWindowBringsTheAppForwardAsItLeaves() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("copper-tide-alone"))).use { scenario ->
            plays.playing(scenario)
            pressHome()
            val player = scenario.read { it }
            awaitPinned(player)
            expandFromMenu(player)
            waitFor("the player full screen") { true.takeIf { scenario.isIn(Lifecycle.State.RESUMED) } }
            assertTrue("the player its task's root after the window", scenario.read { it.isTaskRoot })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close") { true.takeIf { scenario.isIn(Lifecycle.State.DESTROYED) } }
            val app = waitFor("the app's screens") { onMain { live<MainActivity>(Stage.RESUMED).firstOrNull() } }
            instrumentation.runOnMainSync { app.finish() }
            waitFor<Boolean>("the app's screens to close") { onMain { true.takeIf { app.isDestroyed } } }
        }
    }

    /**
     * Made again after the window, as a change of dark mode, the font or the display size makes it, or as Android brings it
     * back after letting the app's process go: still alone in its task, it still brings the app's screens forward as it
     * leaves, as the player before it would have.
     */
    @Test
    fun aPlayerMadeAgainAfterTheWindowStillBringsTheAppForwardAsItLeaves() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("copper-tide-alone-again"))).use { scenario ->
            plays.playing(scenario)
            pressHome()
            val player = scenario.read { it }
            awaitPinned(player)
            expandFromMenu(player)
            waitFor("the player full screen") { true.takeIf { scenario.isIn(Lifecycle.State.RESUMED) } }
            scenario.recreate()
            assertNotSame("the player made again", player, scenario.read { it })
            plays.playing(scenario)
            assertTrue("the player made again its task's root", scenario.read { it.isTaskRoot })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close") { true.takeIf { scenario.isIn(Lifecycle.State.DESTROYED) } }
            val app = waitFor("the app's screens") { onMain { live<MainActivity>(Stage.RESUMED).firstOrNull() } }
            instrumentation.runOnMainSync { app.finish() }
            waitFor<Boolean>("the app's screens to close") { onMain { true.takeIf { app.isDestroyed } } }
        }
    }

    /** Tests launch the player straight into a task of its own, and leave it with Back: as before the window, it just closes. */
    @Test
    fun aPlayerNeverInTheWindowLeavesAsBefore() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("copper-tide-never"))).use { scenario ->
            plays.playing(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close") { true.takeIf { scenario.isIn(Lifecycle.State.DESTROYED) } }
            // Time for the app's screens to come up, were they brought forward.
            delay(1_500)
            assertFalse("the app's screens brought forward", onMain { live<MainActivity>(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED).isNotEmpty() })
        }
    }

    /**
     * The window floats over the app's screens and another film is played there (L9): the window's play pauses as the play
     * sheet starts the new one, and its place is saved, the window staying until the new play comes. That comes to the
     * same player, which Android takes out of the window with it: the new film full screen.
     */
    @Test
    fun aPlayFromTheAppPausesTheWindowsPlayThenTakesThePlayerOutOfTheWindow() = runBlocking<Unit> {
        val served = CompletableDeferred<Unit>()
        val request = plays.otherFilm("tin-lantern-new-play", served)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
            val player = pinnedOverTheApp(main, plays.launch("copper-tide-new-play"))
            val first = onMain { player.player!! }
            container.playback.play(request)
            val at = waitFor("the window's play to pause as the new play starts") { onMain { first.takeIf { !it.playWhenReady }?.currentPosition } }
            assertSame("the window's player", first, onMain { player.player })
            assertTrue("the window while the new play is found", onMain { player.isInPictureInPictureMode })
            waitFor("the window's place to be saved") { runBlocking { plays.savedMs() }.takeIf { it > 0 && it >= at - 500 } }
            served.complete(Unit)
            waitFor("the same player out of the window with the new film", timeoutMs = 60_000) {
                onMain { player.player?.takeIf { p -> !player.isInPictureInPictureMode && p !== first && p.isPlaying && p.mediaMetadata.title?.toString() == PictureInPicturePlays.OTHER_TITLE } }
            }
            assertEquals("the players", listOf(player), onMain { live<PlayerActivity>(*ALIVE) })
            instrumentation.runOnMainSync { player.finish() }
            waitFor<Boolean>("the player to close") { onMain { true.takeIf { player.isDestroyed } } }
        }
    }

    /**
     * The new play cancelled in the sheet: the window stays, its play paused, the same player. Where Android would float
     * the window over the sheet's Cancel, it keeps clear of it (Android 14 and later move it, PipWindowNewPlay).
     */
    @Test
    fun cancellingThePlayFromTheAppLeavesTheWindowPaused() = runBlocking<Unit> {
        val served = CompletableDeferred<Unit>()
        val request = plays.otherFilm("tin-lantern-cancelled", served)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
                val player = pinnedOverTheApp(main, plays.launch("copper-tide-cancelled"))
                val first = onMain { player.player!! }
                container.playback.play(request)
                waitFor("the window's play to pause as the new play starts") { onMain { first.takeIf { !it.playWhenReady } } }
                var last: Pair<Rect, Rect>? = null
                val cancel = try {
                    waitFor("the window to keep clear of the sheet's Cancel", timeoutMs = 15_000) {
                        val button = appButton("Cancel")
                        val now = button?.let { it.onScreen() to windowNow(player) }
                        val settled = now != null && now == last && !Rect.intersects(now.first, now.second)
                        last = now
                        button.takeIf { settled }
                    }
                } catch (e: AssertionError) {
                    throw AssertionError("${e.message}: Cancel at ${last?.first?.toShortString()}, the window at ${last?.second?.toShortString()}", e)
                }
                assertTrue("Cancel pressed", cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("the play to stop") { true.takeIf { container.playback.state.value == PlayState.Idle } }
                // Time for a launch to come, had the play gone on.
                delay(2_000)
                onMain {
                    assertTrue("the window after the play was cancelled", player.isInPictureInPictureMode)
                    assertSame("the window's player", first, player.player)
                    assertFalse("the window's play playing", first.playWhenReady)
                }
                instrumentation.runOnMainSync { player.finish() }
                waitFor<Boolean>("the player to close") { onMain { true.takeIf { player.isDestroyed } } }
            }
        } finally {
            served.complete(Unit)
        }
    }

    /**
     * The window expanded while the new play is still being found: the user went back to the window's play, and the new
     * one is dropped as Cancel drops it. Nothing of it is left to take the player over later, as the user leaves the
     * window's play for the app's screens.
     */
    @Test
    fun expandingTheWindowWhileTheNewPlayIsFoundDropsIt() = runBlocking<Unit> {
        val served = CompletableDeferred<Unit>()
        val request = plays.otherFilm("tin-lantern-expanded", served)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
                val player = pinnedOverTheApp(main, plays.launch("copper-tide-expanded-new-play"))
                val first = onMain { player.player!! }
                container.playback.play(request)
                waitFor("the window's play to pause as the new play starts") { onMain { first.takeIf { !it.playWhenReady } } }
                expandFromMenu(player)
                waitFor("the window's play full screen") { onMain { player.takeIf { it in live<PlayerActivity>(Stage.RESUMED) } } }
                served.complete(Unit)
                waitFor("the new play to be over", timeoutMs = 60_000) { true.takeIf { container.playback.state.value == PlayState.Idle } }
                // Time for a launch to go out, had the play gone on.
                delay(3_000)
                assertSame("the player after the window", first, onMain { player.player })
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor("the player to close") { onMain { true.takeIf { player.isDestroyed } } }
                // Time for a launch left waiting to start a player, as the app's screens take it.
                delay(3_000)
                assertEquals("players started for the dropped play", emptyList<PlayerActivity>(), onMain { live<PlayerActivity>(*ALIVE) })
                waitFor<Boolean>("the app's screens") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
            }
        } finally {
            served.complete(Unit)
        }
    }

    /** The window's play played on from its own Play while the new play is still being found: the new one is dropped likewise. */
    @Test
    fun playingOnInTheWindowWhileTheNewPlayIsFoundDropsIt() = runBlocking<Unit> {
        val served = CompletableDeferred<Unit>()
        val request = plays.otherFilm("tin-lantern-played-on", served)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
                val player = pinnedOverTheApp(main, plays.launch("copper-tide-played-on"))
                val first = onMain { player.player!! }
                container.playback.play(request)
                waitFor("the window's play to pause as the new play starts") { onMain { first.takeIf { !it.playWhenReady } } }
                val play = waitFor("the window's Play") { onMain { player.windowButtons.firstOrNull { it.title.toString() == "Play" } } }
                play.actionIntent.send()
                waitFor("the window's play playing") { onMain { first.takeIf { it.isPlaying } } }
                waitFor("the new play dropped", timeoutMs = 5_000) { true.takeIf { container.playback.state.value == PlayState.Idle } }
                served.complete(Unit)
                // Time for a launch to come, had the play gone on.
                delay(3_000)
                onMain {
                    assertTrue("the window", player.isInPictureInPictureMode)
                    assertSame("the window's player", first, player.player)
                    assertTrue("the window's play playing", first.isPlaying)
                }
                instrumentation.runOnMainSync { player.finish() }
                waitFor<Boolean>("the player to close") { onMain { true.takeIf { player.isDestroyed } } }
            }
        } finally {
            served.complete(Unit)
        }
    }

    /**
     * [launch] played from the app's screens ([main]), a few seconds in, then Home: in the window. Then the app's screens
     * come forward under it, as the app's icon brings their task (M13), and the window floats over them. The player.
     */
    private suspend fun pinnedOverTheApp(main: ActivityScenario<MainActivity>, launch: PlayerLaunch): PlayerActivity {
        waitFor("the app") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
        main.onActivity { PlayerActivity.start(it, launch) }
        val player = waitFor("the film to play", timeoutMs = 30_000) { onMain { live<PlayerActivity>(Stage.RESUMED).firstOrNull { it.player?.isPlaying == true } } }
        waitFor("the film a few seconds in", timeoutMs = 15_000) { onMain { player.player?.takeIf { it.currentPosition >= 3_000 } } }
        pressHome()
        awaitPinned(player)
        val app = ComponentName(context, MainActivity::class.java)
        context.getSystemService(ActivityManager::class.java).appTasks.first { it.taskInfo.baseActivity == app }.moveToFront()
        waitFor("the app's screens under the window") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
        assertTrue("the window over the app's screens", onMain { player.isInPictureInPictureMode })
        return player
    }

    /**
     * Whether the activity is in [wanted]: not while it moves between stages (restarted, say, as the app's task comes
     * forward), where ActivityScenario has no state to give and throws.
     */
    private fun ActivityScenario<*>.isIn(wanted: Lifecycle.State): Boolean =
        try {
            state == wanted
        } catch (e: NullPointerException) {
            false
        }

    /** This process's activities of [A] in [stages] (on the main thread, as the monitor wants). */
    private inline fun <reified A : Activity> live(vararg stages: Stage): List<A> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<A>().distinct()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T> ActivityScenario<MainActivity>.read(probe: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private companion object {
        const val INDEXER = "picture-in-picture-chain-device-test"

        /** Every stage of an activity not yet destroyed. */
        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}

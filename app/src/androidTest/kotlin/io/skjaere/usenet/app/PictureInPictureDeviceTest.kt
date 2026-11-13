package io.skjaere.usenet.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.data.AspectMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Picture-in-picture on a phone: Home from a playing film puts the play in the window, the same player playing on in the
 * picture's shape (2.39:1 at the widest Android allows); paused, during the setup, before the first frame or with
 * "Playback stopped" up, Home stops the player as it always has; so it does on a TV (a phone made one), and an activity
 * that leaves the player in sight makes no window. Before Android 12 the player asks for the window as the user leaves:
 * that path is taken here through a seam. In the window only the picture shows: no controls, overlays or words, none for
 * TalkBack, the picture in Fit, a failure one line on black; a player made again there likewise. Expanded, the player is as
 * it was left: the system bars hidden, the size chosen, the controls down, "Playback stopped" or the next-episode card
 * back where due. Closed, it saves where the film was, lets the play go and closes, its task with it; so it does as Recents
 * ends its task. The top bar's button (PipPlayerButton) puts the film in the window, playing or paused; it isn't there
 * without Android's picture-in-picture, nor on a TV. With picture-in-picture off for the app in Android's settings, Home
 * stops the player as it always has, and the button says why, with the way to that setting (PipPlayerOff). The window's
 * own buttons (PipWindowMenu) go 10 s back or on through the controls' burst, two taps one seek, and pause and play;
 * under "Playback stopped" its seeks are greyed and do nothing. The app is let into the window for each test
 * ([PictureInPictureAllowed]); the film plays from a server inside the test's process ([PictureInPicturePlays]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PictureInPictureDeviceTest {
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

    private suspend fun launch(guid: String): ActivityScenario<PlayerActivity> = launch(plays.launch(guid))

    private fun launch(launch: PlayerLaunch): ActivityScenario<PlayerActivity> = ActivityScenario.launch(PlayerActivity.intent(context, launch))

    @Test
    fun homeWhilePlayingPutsThePlayInTheWindow() = runBlocking<Unit> {
        launch("copper-tide-home").use { scenario ->
            val player = plays.playing(scenario)
            pressHome()
            val window = awaitPinned(scenario.read { it })
            assertEquals("the activity in the window: paused, not stopped", Lifecycle.State.STARTED, scenario.state)
            assertSame("the player in the window", player, scenario.read { it.player })
            assertShape(16.0 / 9, window, "a 16:9 film")
            val from = scenario.read { it.player!!.currentPosition }
            waitFor("the film to play on in the window", timeoutMs = 10_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= from + 1_500 } } }
            plays.finish(scenario)
        }
    }

    /** 1920x800 is 2.40:1, past the widest window Android allows: asked for as it is, Android would throw. */
    @Test
    fun aFilmWiderThanAndroidAllowsOpensTheWidestWindow() = runBlocking<Unit> {
        PlayerActivity.videoSizeOf = { VideoSize(1920, 800) }
        launch("copper-tide-wide").use { scenario ->
            plays.playing(scenario)
            pressHome()
            assertShape(2.39, awaitPinned(scenario.read { it }), "a 2.40:1 film")
            plays.finish(scenario)
        }
    }

    @Test
    fun pausedHomeStopsThePlayer() = runBlocking<Unit> {
        launch("copper-tide-paused").use { scenario ->
            plays.playing(scenario)
            scenario.onActivity { it.player!!.pause() }
            plays.homeStops(scenario)
        }
    }

    @Test
    fun homeDuringTheSetupStopsThePlayer() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        val read = PlayerActivity.readPlayback
        PlayerActivity.readPlayback = { c -> held.await(); read(c) }
        try {
            launch("copper-tide-setup").use { scenario ->
                waitFor("the player's window", timeoutMs = 10_000) { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
                plays.homeStops(scenario)
            }
        } finally {
            held.complete(Unit)
        }
    }

    /** The player made and asked to play, its stream not yet open: no picture to put in the window. */
    @Test
    fun homeBeforeTheFirstFrameStopsThePlayer() = runBlocking<Unit> {
        val opened = CountDownLatch(1)
        PlayerActivity.dataSources = { c -> DataSource.Factory { Held(c.dataSourceFactory.createDataSource(), opened) } }
        try {
            launch("copper-tide-first-frame").use { scenario ->
                waitFor("the player, asked to play", timeoutMs = 10_000) { scenario.read { a -> a.player?.takeIf { it.playWhenReady } } }
                // Long enough for a picture to have shown, had the stream opened.
                delay(1_000)
                plays.homeStops(scenario)
            }
        } finally {
            opened.countDown()
        }
    }

    @Test
    fun homeWithPlaybackStoppedUpStopsThePlayer() = runBlocking<Unit> {
        val refuse = AtomicBoolean(false)
        plays.refusing(refuse)
        launch("copper-tide-failed").use { scenario ->
            plays.playing(scenario)
            plays.failByASeek(scenario, refuse)
            waitFor("the panel", timeoutMs = 20_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            plays.homeStops(scenario)
        }
    }

    /** Android 8 to 11's way, taken here on a newer Android through the seam: the player asks for the window as the user leaves. */
    @Test
    fun beforeAndroid12ThePlayerAsksForTheWindowAsTheUserLeaves() = runBlocking<Unit> {
        PlayerActivity.pictureInPictureSdk = { 30 }
        launch("copper-tide-legacy").use { scenario ->
            val player = plays.playing(scenario)
            pressHome()
            awaitPinned(scenario.read { it })
            assertSame("the player in the window", player, scenario.read { it.player })
            plays.finish(scenario)
        }
    }

    /** No film in the window on a TV: Google's guidelines forbid it there. */
    @Test
    fun onATvHomeStopsThePlayer() = runBlocking<Unit> {
        PlayerActivity.isTelevision = { true }
        launch("copper-tide-tv").use { scenario ->
            plays.playing(scenario)
            plays.homeStops(scenario)
        }
    }

    /**
     * Android 12 and later's own rule, which the player leaves to Android: a floating activity in a task of its own over the
     * player (ActivityScenario's own, as a dialog-like app would be) pauses it, in sight, and Android opens no window as the
     * player leaves (it does so only for an activity that covers the player).
     */
    @Test
    fun anActivityThatLeavesThePlayerInSightMakesNoWindow() = runBlocking<Unit> {
        launch("copper-tide-floating").use { scenario ->
            plays.playing(scenario)
            scenario.moveToState(Lifecycle.State.STARTED)
            delay(2_000)
            assertFalse("in picture-in-picture under a floating activity", scenario.read { it.isInPictureInPictureMode })
            assertEquals(Lifecycle.State.STARTED, scenario.state)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse("in picture-in-picture", scenario.read { it.isInPictureInPictureMode })
        }
    }

    /**
     * Before Android 12 (the seam), where the player asks for the window as the user leaves, it can't tell what comes over
     * it: a floating activity in a task of its own still puts the film in the window, though it would have stayed in sight
     * behind it. A known limit of Android 8 to 11's way; the user expands the window again.
     */
    @Test
    fun beforeAndroid12AnActivityThatLeavesThePlayerInSightStillMakesTheWindow() = runBlocking<Unit> {
        PlayerActivity.pictureInPictureSdk = { 30 }
        launch("copper-tide-floating-legacy").use { scenario ->
            val player = plays.playing(scenario)
            scenario.moveToState(Lifecycle.State.STARTED)
            awaitPinned(scenario.read { it })
            assertSame("the player in the window", player, scenario.read { it.player })
            plays.finish(scenario)
        }
    }

    /**
     * The controls and the next-episode card up, then Home: in the window only the picture, nothing TalkBack reads; a pause
     * there brings no controls; expanded, the card is back.
     */
    @Test
    fun inTheWindowOnlyThePictureShows() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.episodeLaunch("lowland-signal-card"))).use { scenario ->
            plays.playing(scenario)
            waitFor("the next-episode card") { scenario.read { a -> overlayWords(a).takeIf { "Play now" in it } } }
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            scenario.read { a ->
                assertEquals("the overlays' words in the window", emptyList<String>(), overlayWords(a))
                assertEquals("the player's own words in the window", emptyList<String>(), shownTexts(a))
                assertFalse("media3's controls in the window", playerView(a).isControllerFullyVisible)
                assertFalse("media3's controls usable in the window", playerView(a).useController)
                assertEquals("the window's views for TalkBack", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, (playerView(a).parent as View).importantForAccessibility)
            }
            // media3 brings its controls up as a play pauses.
            scenario.onActivity { it.player!!.pause() }
            delay(1_500)
            assertFalse("media3's controls as the play paused in the window", scenario.read { playerView(it).isControllerFullyVisible })
            expandFromMenu(activity)
            waitFor("the player full screen") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            waitFor("the card back") { scenario.read { a -> overlayWords(a).takeIf { "Play now" in it } } }
            assertEquals("the player's views for TalkBack", View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, scenario.read { (playerView(it).parent as View).importantForAccessibility })
        }
    }

    /**
     * W1: an episode ending in the window waits there on its last frame, the next-episode card and its countdown held out
     * of sight, nothing handed over; expanded, the card shows, counting down, and plays the next episode as it would have
     * full screen.
     */
    @Test
    fun anEpisodeEndingInTheWindowWaitsThereUntilExpanded() = runBlocking<Unit> {
        plays.countDownToTheNextEpisode()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.episodeLaunch("lowland-signal-ends-in-window"))).use { scenario ->
            plays.playing(scenario)
            // Short of the card, which shows 8 s before the end.
            scenario.onActivity { it.player!!.seekTo(40_000) }
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            waitFor("the episode to end in the window", timeoutMs = 30_000) {
                if (scenario.state == Lifecycle.State.DESTROYED) throw AssertionError("the player closed in the window, for the next episode")
                scenario.read { a -> (a.player?.playbackState == Player.STATE_ENDED).takeIf { it } }
            }
            // Past the whole countdown, had it run.
            delay(PictureInPicturePlays.COUNTDOWN_MS + 2_000)
            scenario.read { a ->
                assertTrue("in the window at the end", a.isInPictureInPictureMode)
                assertFalse("the player closing", a.isFinishing)
                assertEquals("the overlays' words in the window", emptyList<String>(), overlayWords(a))
            }
            assertEquals("the next episode's play", PlayState.Idle, container.playback.state.value)
            expandFromMenu(activity)
            waitFor("the card counting down") { scenario.read { a -> overlayWords(a).takeIf { "Next episode plays automatically" in it } } }
            waitFor<Boolean>("the player to close for the next episode", timeoutMs = 15_000) { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            withTimeout(30_000) { container.playback.state.first { "High Water" in it.toString() || "S01E02" in it.toString() } }
        }
    }

    /**
     * The missing-parts prompt up, then Home: in the window it waits, and its time doesn't run while it can't be seen;
     * expanded, it shows again, as long as it had left.
     */
    @Test
    fun theMissingPartsPromptWaitsInTheWindow() = runBlocking<Unit> {
        val heading = "This release is missing parts (3 so far)"
        launch(plays.missingPartsLaunch("copper-tide-missing-parts", 10, 13, 16)).use { scenario ->
            waitFor("the prompt", timeoutMs = 40_000) { scenario.read { a -> overlayWords(a).takeIf { heading in it } } }
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            assertEquals("the overlays' words in the window", emptyList<String>(), scenario.read(::overlayWords))
            // Longer in the window than the prompt shows.
            delay(PictureInPicturePlays.COUNT_ONLY_FOR_MS + 2_000)
            assertTrue("the film playing in the window", scenario.read { it.player!!.isPlaying })
            expandFromMenu(activity)
            waitFor<List<String>>("the prompt again, full screen", timeoutMs = 3_000) { scenario.read { a -> overlayWords(a).takeIf { heading in it } } }
        }
    }

    /** PipWindowStates: "Playback stopped" on black in the window; the panel, with Try another release, full screen. */
    @Test
    fun aFailureInTheWindowIsOneLineAndThePanelShowsAsItIsExpanded() = runBlocking<Unit> {
        val refuse = AtomicBoolean(false)
        plays.refusing(refuse)
        launch("copper-tide-fails-in-window").use { scenario ->
            plays.playing(scenario)
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            plays.failByASeek(scenario, refuse)
            waitFor("the one line in the window", timeoutMs = 20_000) { scenario.read { a -> shownTexts(a).takeIf { it == listOf("Playback stopped") } } }
            assertEquals("the panel's words in the window", emptyList<String>(), scenario.read(::overlayWords))
            // PipWindowStates: −10 s and +10 s greyed, no Play; sent anyway, they do nothing.
            waitFor("the window's buttons greyed") { scenario.read { a -> a.windowButtons.takeIf { b -> b.isNotEmpty() && b.none { it.isEnabled } } } }
            assertEquals(listOf(REWIND, FORWARD), scenario.read { a -> a.windowButtons.map { it.title.toString() } })
            val seeks = seeksOf(scenario)
            press(scenario, FORWARD)
            delay(1_500)
            assertEquals("seeks under Playback stopped", 0, seeks.get())
            expandFromMenu(activity)
            waitFor("the player full screen") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            waitFor("the panel") { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            assertEquals("the one line full screen", emptyList<String>(), scenario.read(::shownTexts))
            assertFalse("media3's controls usable under the panel", scenario.read { playerView(it).useController })
        }
    }

    /**
     * The picture zoomed: in the window in Fit, which has the picture's shape (a zoom would cut it); expanded, the system
     * bars hidden, the zoom back, the controls down.
     */
    @Test
    fun expandedThePlayerIsAsItWasLeft() = runBlocking<Unit> {
        val read = PlayerActivity.readPlayback
        PlayerActivity.readPlayback = { c -> read(c).copy(aspectMode = AspectMode.Zoom.name, zoomScale = 1.6f) }
        launch("copper-tide-expanded").use { scenario ->
            val player = plays.playing(scenario)
            val zoomed = waitFor("the picture zoomed") { scenario.read { a -> surfaceScale(a).takeIf { it > 1.5f } } }
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            waitFor("the picture in Fit in the window") { scenario.read { a -> (abs(surfaceScale(a) - 1f) < 0.01f).takeIf { it } } }
            expandFromMenu(activity)
            waitFor("the player full screen") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            assertSame("the player after expanding", player, scenario.read { it.player })
            waitFor("the system bars hidden") { scenario.read { a -> barsShown(a).isEmpty().takeIf { it } } }
            waitFor("the picture zoomed again") { scenario.read { a -> (abs(surfaceScale(a) - zoomed) < 0.02f).takeIf { it } } }
            assertTrue("media3's controls usable", scenario.read { playerView(it).useController })
            assertFalse("media3's controls up", scenario.read { playerView(it).isControllerFullyVisible })
        }
    }

    /**
     * Android makes the player again in the window, as a change of dark mode, the font size or the display size does while
     * it floats; no mode change comes to the new one: still only the picture, the play going on.
     */
    @Test
    fun aPlayerMadeAgainInTheWindowShowsOnlyThePicture() = runBlocking<Unit> {
        launch("copper-tide-made-again").use { scenario ->
            plays.playing(scenario)
            pressHome()
            val before = scenario.read { it }
            awaitPinned(before)
            scenario.onActivity { it.recreate() }
            val after = waitFor("the player made again, playing", timeoutMs = 30_000) { onMain { players().firstOrNull { it !== before && it.player?.isPlaying == true } } }
            assertNotSame(before, after)
            // Over a player not yet ready media3 would show its controls at once.
            delay(1_000)
            onMain {
                assertTrue("the player made again in the window", after.isInPictureInPictureMode)
                assertFalse("media3's controls usable", playerView(after).useController)
                assertFalse("media3's controls up", playerView(after).isControllerFullyVisible)
                assertEquals("the overlays' words", emptyList<String>(), overlayWords(after))
                assertEquals("the player's views for TalkBack", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, (playerView(after).parent as View).importantForAccessibility)
            }
            instrumentation.runOnMainSync { after.finish() }
            waitFor<Boolean>("the player to close") { true.takeIf { onMain { players().isEmpty() } } }
        }
    }

    /**
     * The window's ✕ (PipWindowMenu): Android stops the player and leaves it behind the home screen. It has saved where the
     * film was and let the play go, and it closes, its task with it: nothing is left in Recents to play the launch again.
     */
    @Test
    fun closingTheWindowEndsThePlay() = runBlocking<Unit> {
        launch("copper-tide-closed").use { scenario ->
            plays.playing(scenario)
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            val task = activity.taskId
            val at = waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.currentPosition?.takeIf { it >= 3_000 } } }
            closeFromMenu(activity)
            waitFor<Boolean>("the player to close", timeoutMs = 10_000) { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            waitFor("where the film was to be saved") { runBlocking { plays.savedMs() }.takeIf { it >= at } }
            assertEquals("receivers of the \"becoming noisy\" broadcast", 0, NoisyPlays.noisyReceivers())
            waitFor<Boolean>("the player's task to go from Recents", timeoutMs = 5_000) { true.takeIf { task !in appTasks() } }
        }
    }

    /** Recents' swipe, as `am stack remove` does it: Android ends the window's task, and the player saves where the film was as it stops. */
    @Test
    fun endingTheWindowsTaskSavesThePlace() = runBlocking<Unit> {
        launch("copper-tide-removed").use { scenario ->
            plays.playing(scenario)
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            val at = waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.currentPosition?.takeIf { it >= 3_000 } } }
            val said = instrumentation.uiAutomation.shell("am stack remove ${activity.taskId}")
            waitFor<Boolean>("the player to close (am said \"$said\")", timeoutMs = 10_000) { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            waitFor<Long>("where the film was to be saved") { runBlocking { plays.savedMs() }.takeIf { it >= at } }
        }
    }

    /** PipPlayerButton: the first of the top bar's round buttons, before Aspect, puts the playing film in the window, the same player playing on. */
    @Test
    fun theButtonPutsAPlayingFilmInTheWindow() = runBlocking<Unit> {
        launch("copper-tide-button").use { scenario ->
            val player = plays.playing(scenario)
            pressTheButton(scenario)
            awaitPinned(scenario.read { it })
            assertSame("the player in the window", player, scenario.read { it.player })
            val from = scenario.read { it.player!!.currentPosition }
            waitFor("the film to play on in the window", timeoutMs = 10_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= from + 1_500 } } }
            plays.finish(scenario)
        }
    }

    /**
     * The button pressed with the controls up: they and the top bar go at once, as the player goes into the window, not
     * with media3's fade, which would bring the top bar's back arrow back over the shrinking picture for its quarter second.
     */
    @Test
    fun theButtonTakesTheControlsAndTheTopBarAwayAtOnce() = runBlocking<Unit> {
        launch("copper-tide-button-at-once").use { scenario ->
            plays.playing(scenario)
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            val button = waitFor("the picture-in-picture button") { scenario.read { a -> pipButton(a)?.takeIf { it.isShown } } }
            scenario.onActivity { a ->
                button.performClick()
                assertFalse("the top bar's back arrow as the button is pressed", backArrow(a).isShown)
                assertEquals("media3's controls as the button is pressed", View.GONE, controls(a).visibility)
            }
            awaitPinned(scenario.read { it })
            plays.finish(scenario)
        }
    }

    /**
     * The button pressed as a tap brings the controls up, media3 still animating them in: they go at once all the same, and
     * stay down in the window as that animation ends, which would put them up again with nothing to take them down; expanded,
     * they are down.
     */
    @Test
    fun controlsComingUpAsTheButtonIsPressedStayDownInTheWindow() = runBlocking<Unit> {
        launch("copper-tide-button-controls-coming").use { scenario ->
            plays.playing(scenario)
            scenario.onActivity { a ->
                playerView(a).setControllerAnimationEnabled(false)
                playerView(a).hideController()
                playerView(a).setControllerAnimationEnabled(true)
            }
            waitFor("the controls down") { scenario.read { a -> (controls(a).visibility == View.GONE).takeIf { it } } }
            val button = scenario.read { a -> pipButton(a)!! }
            scenario.onActivity { a ->
                playerView(a).showController()
                assertTrue("media3's controls coming up as the button is pressed", controls(a).visibility == View.VISIBLE && !playerView(a).isControllerFullyVisible)
                button.performClick()
            }
            val activity = scenario.read { it }
            awaitPinned(activity)
            // Well past media3's quarter-second show.
            delay(1_000)
            scenario.read { a ->
                assertEquals("media3's controls in the window", View.GONE, controls(a).visibility)
                assertFalse("the top bar's back arrow in the window", backArrow(a).isShown)
            }
            expandFromMenu(activity)
            waitFor("the player full screen") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            delay(1_000)
            scenario.read { a ->
                assertEquals("media3's controls after expanding", View.GONE, controls(a).visibility)
                assertFalse("the top bar's back arrow after expanding", backArrow(a).isShown)
            }
        }
    }

    /** Paused, Home stops the player; the button still puts it in the window, paused. */
    @Test
    fun theButtonPutsAPausedFilmInTheWindow() = runBlocking<Unit> {
        launch("copper-tide-button-paused").use { scenario ->
            val player = plays.playing(scenario)
            scenario.onActivity { it.player!!.pause() }
            pressTheButton(scenario)
            awaitPinned(scenario.read { it })
            assertSame("the player in the window", player, scenario.read { it.player })
            assertFalse("the film playing in the window", scenario.read { it.player!!.playWhenReady })
            plays.finish(scenario)
        }
    }

    @Test
    fun withoutPictureInPictureTheButtonIsHidden() = runBlocking<Unit> {
        PlayerActivity.hasPictureInPicture = { false }
        launch("copper-tide-no-feature").use { scenario ->
            plays.playing(scenario)
            assertNoButton(scenario)
            plays.finish(scenario)
        }
    }

    @Test
    fun onATvTheButtonIsHidden() = runBlocking<Unit> {
        PlayerActivity.isTelevision = { true }
        launch("copper-tide-tv-button").use { scenario ->
            plays.playing(scenario)
            assertNoButton(scenario)
            plays.finish(scenario)
        }
    }

    /**
     * PipPlayerOff, proposal A: picture-in-picture off for the app in Android's settings. Home stops the player as it always
     * has. The button leaves the film playing full screen and says why under the top bar, read once by TalkBack, with Open
     * settings, which opens Android's picture-in-picture page for the app (stopped here before it opens). The words go with
     * the controls, and a seek's take their place.
     */
    @Test
    fun withPictureInPictureOffHomeStopsAndTheButtonSaysWhy() = runBlocking<Unit> {
        PictureInPictureAppOp.set(PictureInPictureAppOp.IGNORE)
        launch("copper-tide-off-home").use { scenario ->
            plays.playing(scenario)
            plays.homeStops(scenario)
        }
        launch("copper-tide-off-button").use { scenario ->
            plays.playing(scenario)
            pressTheButton(scenario)
            waitFor("the words") { scenario.read { a -> overlayWords(a).takeIf { OFF in it } } }
            scenario.read { a ->
                assertFalse("in picture-in-picture", a.isInPictureInPictureMode)
                assertTrue("the film playing", a.player!!.isPlaying)
                assertTrue("media3's controls up with the words", playerView(a).isControllerFullyVisible)
                assertTrue("Open settings beside the words", "Open settings" in overlayWords(a))
                assertTrue("the words a polite live region", overlayNodes(a).any { n -> n.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite && OFF in n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } })
                assertEquals("the focus on the words or their button", emptyList<String>(), focusedWords(a))
            }
            val opened = settingsOpened(scenario, "Open settings")
            assertEquals("the settings opened", "android.settings.PICTURE_IN_PICTURE_SETTINGS", opened.action)
            assertEquals("the app they open on", "package:${context.packageName}", opened.dataString)
            scenario.onActivity { playerView(it).hideController() }
            waitFor("the words to go with the controls") { scenario.read { a -> (OFF !in overlayWords(a)).takeIf { it } } }
            pressTheButton(scenario)
            waitFor("the words again") { scenario.read { a -> overlayWords(a).takeIf { OFF in it } } }
            scenario.onActivity { it.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount).performClick() }
            waitFor("a seek's words in their place") { scenario.read { a -> (OFF !in overlayWords(a) && shownTexts(a).any { it.startsWith("+10 s") }).takeIf { it } } }
            plays.finish(scenario)
        }
    }

    /**
     * Android refuses the window for more than the app's switch: under App pinning (lock task mode) it refuses any, with
     * picture-in-picture on for the app. The button then says nothing of Android's settings, where the switch is on, and
     * the film plays on full screen.
     */
    @Test
    fun refusedForAnotherReasonTheButtonSaysNothingOfTheSettings() = runBlocking<Unit> {
        launch("copper-tide-app-pinned").use { scenario ->
            plays.playing(scenario)
            val task = scenario.read { it.taskId }
            val activities = context.getSystemService(ActivityManager::class.java)
            try {
                instrumentation.uiAutomation.shell("am task lock $task")
                waitFor("App pinning") { true.takeIf { activities.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE } }
                pressTheButton(scenario)
                delay(1_500)
                scenario.read { a ->
                    assertFalse("in picture-in-picture under App pinning", a.isInPictureInPictureMode)
                    assertFalse("the words that it is off in Android's settings", OFF in overlayWords(a))
                    assertTrue("the film playing", a.player!!.isPlaying)
                }
            } finally {
                instrumentation.uiAutomation.shell("am task lock stop")
                waitFor<Boolean>("App pinning to end") { true.takeIf { activities.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE } }
            }
            plays.finish(scenario)
        }
    }

    /**
     * PipWindowMenu: the window's own −10 s, Pause and +10 s, read by TalkBack in their titles' words. Each seek goes through
     * the controls' burst, 10 s a tap and two taps one seek; Pause pauses, and Play takes its place, which plays again.
     */
    @Test
    fun theWindowsButtonsSeekAndPauseAndPlay() = runBlocking<Unit> {
        launch("copper-tide-window-buttons").use { scenario ->
            plays.playing(scenario)
            pressHome()
            awaitPinned(scenario.read { it })
            assertEquals(listOf(REWIND, "Pause", FORWARD), scenario.read { a -> a.windowButtons.map { it.title.toString() } })
            assertEquals(listOf(REWIND, "Pause", FORWARD), scenario.read { a -> a.windowButtons.map { it.contentDescription.toString() } })
            waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 3_000 } } }
            val seeks = seeksOf(scenario)
            repeat(2) { n ->
                val from = scenario.read { it.player!!.currentPosition }
                press(scenario, FORWARD)
                waitFor("+10 s's seek") { seeks.get().takeIf { it == n + 1 } }
                val at = scenario.read { it.player!!.currentPosition }
                assertTrue("+10 s from $from at $at", at in from + 9_000..from + 13_000)
                // Said nowhere: the moment label full screen would say how far it went.
                assertEquals("the player's own words in the window", emptyList<String>(), scenario.read(::shownTexts))
            }
            // Two within the burst's window: one seek, 20 s back.
            val from = scenario.read { it.player!!.currentPosition }
            press(scenario, REWIND)
            press(scenario, REWIND)
            waitFor("the burst's seek") { seeks.get().takeIf { it == 3 } }
            delay(1_000)
            assertEquals("seeks", 3, seeks.get())
            val at = scenario.read { it.player!!.currentPosition }
            assertTrue("−20 s from $from at $at", at in from - 21_000..from - 17_000)
            press(scenario, "Pause")
            waitFor("paused in the window") { scenario.read { a -> (a.player?.playWhenReady == false).takeIf { it } } }
            waitFor("Play in Pause's place") { scenario.read { a -> a.windowButtons.map { it.title.toString() }.takeIf { it == listOf(REWIND, "Play", FORWARD) } } }
            press(scenario, "Play")
            waitFor("playing in the window") { scenario.read { a -> a.player?.isPlaying?.takeIf { it } } }
            assertTrue("in the window", scenario.read { it.isInPictureInPictureMode })
            plays.finish(scenario)
        }
    }

    /**
     * Where the window opens out to as it is expanded (its source rect hint) stays the picture full screen as the window's
     * buttons change in it: Android 14 takes a hint the app tells it as from the app's window, the small window's place
     * while in it.
     */
    @Test
    fun theWindowStillExpandsFromThePictureAfterItsButtonsChange() = runBlocking<Unit> {
        launch("copper-tide-hint").use { scenario ->
            plays.playing(scenario)
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            val task = activity.taskId
            val full = waitFor("the hint Android keeps") { keptHint(task) }
            assertTrue("the hint $full on the screen", full.left >= 0 && full.top >= 0)
            press(scenario, "Pause")
            waitFor("Play in Pause's place") { scenario.read { a -> a.windowButtons.map { it.title.toString() }.takeIf { it == listOf(REWIND, "Play", FORWARD) } } }
            delay(500)
            assertEquals("the hint after Pause", full, keptHint(task))
            press(scenario, "Play")
            waitFor("Pause in Play's place") { scenario.read { a -> a.windowButtons.map { it.title.toString() }.takeIf { it == listOf(REWIND, "Pause", FORWARD) } } }
            delay(500)
            assertEquals("the hint after Play", full, keptHint(task))
            plays.finish(scenario)
        }
    }

    /** The source rect hint Android keeps for task [taskId], as its task info says it ("sourceRectHint=Rect(l, t - r, b)"); null for none. */
    private fun keptHint(taskId: Int): Rect? {
        val info = context.getSystemService(ActivityManager::class.java).appTasks.firstNotNullOfOrNull { t -> runCatching { t.taskInfo }.getOrNull()?.takeIf { it.taskId == taskId } }
        val found = info?.let { HINT.find(it.toString()) } ?: return null
        val (left, top, right, bottom) = found.groupValues.drop(1).map(String::toInt)
        return Rect(left, top, right, bottom)
    }

    /** The window's button titled [title] pressed, as Android's menu does: its PendingIntent sent. */
    private fun press(scenario: ActivityScenario<PlayerActivity>, title: String) {
        scenario.read { a -> a.windowButtons.first { it.title.toString() == title } }.actionIntent.send()
    }

    /** The player's seeks from now on. */
    private fun seeksOf(scenario: ActivityScenario<PlayerActivity>): AtomicInteger {
        val seeks = AtomicInteger()
        scenario.onActivity { a ->
            a.player!!.addListener(
                object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                },
            )
        }
        return seeks
    }

    /** The controls up, then the top bar's picture-in-picture button pressed: the first of its round buttons, before Aspect. */
    private suspend fun pressTheButton(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).showController() }
        val button = waitFor("the picture-in-picture button") { scenario.read { a -> pipButton(a)?.takeIf { it.isShown } } }
        scenario.onActivity {
            val bar = button.parent as ViewGroup
            val at = bar.indexOfChild(button)
            assertTrue("Aspect after the button", bar.getChildAt(at + 1).contentDescription?.startsWith("Aspect") == true)
            assertFalse("a round button before it but Back", bar.getChildAt(at - 1) is ImageButton)
            button.performClick()
        }
    }

    /** The controls up, and no picture-in-picture button among the top bar's. */
    private suspend fun assertNoButton(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        assertFalse("the picture-in-picture button shown", scenario.read { a -> pipButton(a)?.isShown == true })
    }

    private fun pipButton(a: Activity): View? = views(a.window.decorView).singleOrNull { it.contentDescription == "Picture in picture" }

    /** media3's controls: gone while down. */
    private fun controls(a: Activity): View = a.findViewById(androidx.media3.ui.R.id.exo_controller)

    private fun backArrow(a: Activity): View = views(a.window.decorView).single { it.contentDescription == "Back" }

    /** The settings [button] opens, as asked for: stopped before Android opens them, so the player stays in front. */
    private fun settingsOpened(scenario: ActivityScenario<PlayerActivity>, button: String): Intent {
        var opened: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null).takeIf { intent.action?.startsWith("android.settings.") == true }?.also { opened = intent }
        }
        instrumentation.addMonitor(monitor)
        try {
            scenario.onActivity { clickOverlay(it, button) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        return opened ?: throw AssertionError("no settings opened by $button")
    }

    /** The app's tasks Android keeps, Recents' among them, by their ids. */
    private fun appTasks(): List<Int> = context.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { t -> runCatching { t.taskInfo.taskId }.getOrNull() }

    /** The window's shape, [bounds] on the screen, is [ratio]'s: Android rounds it to whole pixels. */
    private fun assertShape(ratio: Double, bounds: Rect, case: String) {
        val shown = bounds.width().toDouble() / bounds.height()
        assertTrue("$case: the window is ${bounds.width()}x${bounds.height()}, ${"%.3f".format(shown)}:1", kotlin.math.abs(shown - ratio) < 0.03)
    }

    private fun playerView(a: Activity) = views(a.window.decorView).filterIsInstance<PlayerView>().single()

    /** The player's own words that show: the top bar's, the moment label's, the window's "Playback stopped"; not the overlays', which are Compose. */
    private fun shownTexts(a: Activity) = views(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown && !it.text.isNullOrEmpty() }.map { it.text.toString() }

    /** How far the picture's surface is scaled across: 1 in Fit. */
    private fun surfaceScale(a: Activity) = playerView(a).videoSurfaceView!!.scaleX

    private fun barsShown(a: Activity): List<String> {
        val insets = ViewCompat.getRootWindowInsets(a.window.decorView) ?: return emptyList()
        return listOf(WindowInsetsCompat.Type.statusBars() to "status bar", WindowInsetsCompat.Type.navigationBars() to "navigation bar")
            .filter { (type, _) -> insets.isVisible(type) }.map { it.second }
    }

    /** Every player of this process not yet destroyed (on the main thread). */
    private fun players(): List<PlayerActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
            .flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>().distinct()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A data source that opens only once [opened] is let go; interrupted as the player is released, it gives up. */
    private class Held(private val source: DataSource, private val opened: CountDownLatch) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            try {
                opened.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("released while held")
            }
            return source.open(dataSpec)
        }
    }

    private companion object {
        const val INDEXER = "picture-in-picture-device-test"

        /** The window's seeks' titles (PipWindowMenu). */
        const val REWIND = "Rewind 10 seconds"
        const val FORWARD = "Fast-forward 10 seconds"

        /** A source rect hint as a task's info prints it. */
        val HINT = Regex("""sourceRectHint=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

        /** PipPlayerOff's words, for the debug build ("NZStreamer"). */
        const val OFF = "Picture-in-picture is off for NZStreamer in Android’s settings"
    }
}

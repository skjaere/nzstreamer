package io.skjaere.usenet.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * The player is immersive: the status and navigation bars hide while it is in front, the picture
 * draws under them and the display cutout, and they stay hidden as the controls and the audio and
 * subtitles sheet come and go. Wherever they may have come back they hide again: as the player is
 * stopped and started, gets the focus back from a dialog, turns, plays something new or is made
 * again. The controls, the top bar and the skip button keep clear of the bars as if they showed
 * (a swipe brings them back for a moment) and of the cutout; the controls also of the gesture area
 * at the bottom (#140). Plays the chapters.mkv fixture (an intro at 5–25 s, see
 * SkipSegmentsDeviceTest) on the emulator's landscape phone.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ImmersivePlayerDeviceTest {
    /** Android's hint as the bars first hide would take the player's window focus, and Back with it. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        instrumentation.setInTouchMode(true)
    }

    private fun uri() = UsenetUris.build(fixture.id, fixture.defaultPick!!.index)

    /** The fixture opened as an NZB file is: no launch, so no resume and no next episode. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, uri())

    @Test
    fun theSystemBarsStayHiddenWhilePlaying() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            awaitHidden(scenario, "once playing")

            // A tap brings the controls, not the bars.
            scenario.onActivity { playerView(it).hideController() }
            // Gone with their hide animation, the back arrow with them.
            waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible && !backArrow(a).isShown).takeIf { it } } }
            delay(800)
            tap(scenario)
            waitFor("a tap to show the controls", timeoutMs = 3_000) { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            assertStayHidden(scenario, "with the controls up")

            scenario.onActivity { a -> audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> ("Audio and subtitles" in paneTitles(a)).takeIf { it } } }
            assertStayHidden(scenario, "with the sheet open")
            // Back reaches the player's window, nothing over it.
            waitFor("the player's window to have the focus", timeoutMs = 3_000) { scenario.read { a -> a.hasWindowFocus().takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close") { scenario.read { a -> ("Audio and subtitles" !in paneTitles(a)).takeIf { it } } }
            assertStayHidden(scenario, "after the sheet closed")

            // File details, the same.
            scenario.onActivity { a ->
                playerView(a).showController()
                views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "File details" }.performClick()
            }
            waitFor("File details") { scenario.read { a -> ("File details" in paneTitles(a)).takeIf { it } } }
            assertStayHidden(scenario, "with File details open")
            waitFor("the player's window to have the focus", timeoutMs = 3_000) { scenario.read { a -> a.hasWindowFocus().takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("File details to close") { scenario.read { a -> ("File details" !in paneTitles(a)).takeIf { it } } }
            assertStayHidden(scenario, "after File details closed")
        }
    }

    /**
     * The bars hide again after each step, also when they had come back for good before it (the
     * test asks for them as the player's window would), as Android 10 and older can bring them back.
     * Without that the window's own request to hide them would outlast every step on newer ones,
     * and the test couldn't tell whether the step hides them.
     */
    @Test
    fun theSystemBarsHideAgainWhereverTheyMayHaveComeBack() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            awaitHidden(scenario, "once playing")
            val steps: List<Pair<String, suspend () -> Unit>> = listOf(
                "the player was stopped and started" to {
                    val first = scenario.read { it.player }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    waitFor("the player made again") { scenario.read { a -> a.player?.takeIf { it !== first } } }
                    // It waits paused after the stop (W106), the controls up as a pause puts them: played, and the controls
                    // down again, as before the stop, for the steps after.
                    scenario.onActivity { it.player!!.play() }
                    awaitPlaying(scenario)
                    scenario.onActivity { playerView(it).hideController() }
                    waitFor<Boolean>("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
                },
                "a dialog over the player closed" to {
                    val dialog = scenario.read { a -> Dialog(a).apply { setContentView(TextView(a).apply { text = "Over the player" }) }.also(Dialog::show) }
                    waitFor("the dialog to take the focus") { scenario.read { a -> (!a.hasWindowFocus()).takeIf { it } } }
                    scenario.onActivity { dialog.dismiss() }
                    waitFor<Boolean>("the player's window to have the focus back") { scenario.read { a -> a.hasWindowFocus().takeIf { it } } }
                },
                "the screen turned upright" to { turn(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) },
                "the screen turned back" to { turn(scenario, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE) },
                "a new play" to {
                    val first = scenario.read { it.player }
                    // As the coordinator's launches start it, from outside the open player.
                    context.startActivity(fileIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitFor("the new play") { scenario.read { a -> a.player?.takeIf { it !== first } } }
                    awaitPlaying(scenario)
                },
                "recreation" to {
                    scenario.recreate()
                    awaitPlaying(scenario)
                },
            )
            val stayed = steps.filter { (_, step) ->
                showBars(scenario)
                step()
                !hides(scenario)
            }.map { it.first }
            assertEquals("the bars stayed after", emptyList<String>(), stayed)
        }
    }

    /**
     * The picture fills the screen. With the bars hidden the controls, the top bar, the skip button
     * and the audio and subtitles sheet keep clear of where the bars and the cutout are, and the
     * controls of the gesture area under them; nothing moves as the bars show (as a swipe brings
     * them for a moment). As the controls fade, nothing of their bottom bar shows below their edge.
     */
    @Test
    fun theControlsKeepClearOfTheBarsAndTheCutoutWithoutMovingAsTheBarsShow() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            awaitHidden(scenario, "once playing")
            scenario.read { a ->
                assertEquals("the picture doesn't fill the screen", screen(a).toShortString(), onScreen(playerView(a)).toShortString())
            }

            // In the intro, with the controls held up: the skip button shows with them.
            waitFor("Skip intro", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Skip intro" in it } } }
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls in place") {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && bottomBar(a).translationY == 0f && "Skip intro" in overlayWords(a)).takeIf { it } }
            }
            delay(500)

            val (safe, bottom) = scenario.read { a ->
                val insets = ViewCompat.getRootWindowInsets(a.window.decorView)!!
                val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
                Insets.max(bars, cutout) to maxOf(bars.bottom, cutout.bottom, gestures.bottom)
            }
            assumeTrue("this device has no system bars or cutout to keep clear of", safe != Insets.NONE)
            val placed = scenario.read(::placed)
            val (width, height) = scenario.read { a -> a.window.decorView.width to a.window.decorView.height }
            val controls = RectF(safe.left.toFloat(), 0f, (width - safe.right).toFloat(), (height - bottom).toFloat())
            listOf("bottom bar", "time bar", "centre controls").forEach { name ->
                assertTrue("the $name ${placed.getValue(name)} is outside $controls (insets $safe, bottom $bottom)", controls.holds(placed.getValue(name)))
            }
            val clear = RectF(safe.left.toFloat(), safe.top.toFloat(), (width - safe.right).toFloat(), (height - bottom).toFloat())
            listOf("Back", "Aspect", "Audio and subtitles", "File details", "Skip intro").forEach { name ->
                assertTrue("$name ${placed.getValue(name)} is outside $clear (insets $safe, bottom $bottom)", clear.holds(placed.getValue(name)))
            }
            assertTrue("Skip intro ${placed.getValue("Skip intro")} covers the time bar ${placed.getValue("time bar")}", placed.getValue("Skip intro").bottom <= placed.getValue("time bar").top)

            // The sheet's heading and rows, whatever their colour behind them reaches.
            scenario.onActivity { a -> audioButton(a).performClick() }
            val sheet = waitFor("the sheet's rows") { scenario.read { a -> sheetContent(a).takeIf { it.size > 1 } } }
            sheet.forEach { (what, r) -> assertTrue("the sheet's $what $r is outside $clear (insets $safe)", r.top >= clear.top - 1 && r.right <= clear.right + 1) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close, the controls back") {
                scenario.read { a -> ("Audio and subtitles" !in paneTitles(a) && playerView(a).isControllerFullyVisible && bottomBar(a).translationY == 0f).takeIf { it } }
            }
            delay(500)

            // The bars back, as a swipe brings them: nothing moves.
            scenario.onActivity { a -> WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            waitFor("the navigation bar to show") { scenario.read { a -> ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.navigationBars())?.takeIf { it } } }
            delay(500)
            assertEquals("the controls moved as the bars showed", placed, scenario.read(::placed))

            // Fading to the time bar alone: the bottom bar slides down, cut off at the controls' edge.
            scenario.onActivity { a -> playerView(a).controllerShowTimeoutMs = 1_000 }
            scenario.onActivity { a -> playerView(a).showController() }
            waitFor("the controls to fade to the time bar", timeoutMs = 5_000) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown && bottomBar(a).translationY > 0f).takeIf { it } }
            }
            delay(500)
            scenario.read { a ->
                val bar = bottomBar(a)
                // Where it is, as it slid; the part past its clip doesn't show.
                val shownTo = windowRect(bar).top + (bar.clipBounds?.bottom ?: bar.height)
                assertTrue("the fading bottom bar shows down to $shownTo, past the controls' edge at ${height - bottom}", shownTo <= height - bottom + 1)
            }
        }
    }

    // The bars.

    /** The system bars that show, as the window's insets say. */
    private fun barsShown(a: Activity): List<String> {
        val insets = ViewCompat.getRootWindowInsets(a.window.decorView) ?: return listOf("no insets yet")
        return listOf(WindowInsetsCompat.Type.statusBars() to "status bar", WindowInsetsCompat.Type.navigationBars() to "navigation bar")
            .filter { (type, _) -> insets.isVisible(type) }.map { it.second }
    }

    /** Whether the bars hide within a few seconds. */
    private suspend fun hides(scenario: ActivityScenario<PlayerActivity>): Boolean =
        withTimeoutOrNull(5_000) { while (scenario.read(::barsShown).isNotEmpty()) delay(100) } != null

    private suspend fun awaitHidden(scenario: ActivityScenario<PlayerActivity>, after: String) {
        assertTrue("the ${scenario.read(::barsShown)} shows $after", hides(scenario))
    }

    /** The bars back for good, as the player's window asks for them. */
    private suspend fun showBars(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a -> WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        waitFor("the bars to show") { scenario.read(::barsShown).takeIf { it.size == 2 } }
    }

    /** The player asks for [requested]; returns as its screen has turned to [orientation]. */
    private suspend fun turn(scenario: ActivityScenario<PlayerActivity>, requested: Int, orientation: Int) {
        scenario.onActivity { a -> a.requestedOrientation = requested }
        waitFor("the screen to turn") { scenario.read { a -> (a.resources.configuration.orientation == orientation).takeIf { it } } }
    }

    /** The bars stay hidden for a second: they don't come back for a moment and go again. */
    private suspend fun assertStayHidden(scenario: ActivityScenario<PlayerActivity>, during: String) {
        repeat(10) {
            val shown = scenario.read(::barsShown)
            assertTrue("the $shown shows $during", shown.isEmpty())
            delay(100)
        }
    }

    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("playback", timeoutMs = 30_000) { scenario.read { a -> a.player?.isPlaying?.takeIf { it } } }
    }

    // Where things are.

    /** The screen, as the window manager has it; on Android 10 and older, the window's. */
    private fun screen(a: Activity): RectF =
        if (Build.VERSION.SDK_INT >= 30) RectF(a.windowManager.currentWindowMetrics.bounds) else onScreen(a.window.decorView)

    private fun onScreen(view: View): RectF {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    private fun windowRect(view: View): RectF {
        val at = IntArray(2).also(view::getLocationInWindow)
        return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    /** What has to keep clear of the bars, by name: media3's controls, the top bar's buttons and the skip button. */
    private fun placed(a: Activity): Map<String, RectF> {
        val centre = a.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
        val buttons = views(a.window.decorView).filterIsInstance<ImageButton>().filter { it.isShown }
        fun button(name: String) = windowRect(buttons.single { it.contentDescription?.startsWith(name) == true })
        return mapOf(
            "bottom bar" to windowRect(bottomBar(a)),
            "time bar" to windowRect(a.findViewById(androidx.media3.ui.R.id.exo_progress)),
            "centre controls" to (0 until centre.childCount).map(centre::getChildAt).filter { it.isShown }.map(::windowRect).reduce { u, r -> RectF(u).apply { union(r) } },
            "Back" to button("Back"),
            "Aspect" to button("Aspect"),
            "Audio and subtitles" to button("Audio and subtitles"),
            "File details" to button("File details"),
            "Skip intro" to overlayNodes(a).single { it.config.contains(SemanticsProperties.LiveRegion) }.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) },
        )
    }

    private fun RectF.holds(other: RectF) = other.left >= left - 1 && other.top >= top - 1 && other.right <= right + 1 && other.bottom <= bottom + 1

    private fun bottomBar(a: Activity) = a.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)

    private fun backArrow(a: Activity) = views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    private fun audioButton(a: Activity) = views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Audio and subtitles" }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    /** Every semantics node the shown Compose views hold: the skip button, and the sheet while it shows. */
    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun paneTitles(activity: Activity) = overlayNodes(activity).mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** The sheet's heading and its rows (the nodes that can be selected), by what they say, where they are. */
    private fun sheetContent(activity: Activity): List<Pair<String, RectF>> =
        overlayNodes(activity).filter { n ->
            n.config.contains(SemanticsProperties.Heading) && n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Audio and subtitles" } ||
                n.config.contains(SemanticsProperties.Selected)
        }.map { n -> subtree(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" · ") { it.text } to n.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) } }

    // Touch, as a finger gives it.

    /** One finger down and up, away from the controls' buttons and the top bar. */
    private suspend fun tap(scenario: ActivityScenario<PlayerActivity>) {
        val at = scenario.read { a ->
            val xy = IntArray(2).also(playerView(a)::getLocationOnScreen)
            PointF(xy[0] + playerView(a).width * 0.12f, xy[1] + playerView(a).height * 0.38f)
        }
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, at)
        delay(60)
        send(down, MotionEvent.ACTION_UP, at)
    }

    private fun send(downTime: Long, action: Int, at: PointF) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, at.x, at.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }
}

package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.FocusStyle
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The player's focus on a TV, seen from about 3 m (#148): the round buttons of its top bar and the skip button turn
 * over while a remote's focus is on them, the text's cream filling them and the background's near-black on their
 * icon and words, and the round buttons grow; a pointer remote's hover turns a round button over as the focus does. A
 * phone keeps the accent's circle and the skip button's ring. Shown on the phone emulator as a TV by the debug builds'
 * launch extra, with the chapters.mkv fixture (an intro at 5–25 s, see SkipSegmentsDeviceTest) opened as an NZB file
 * is; the colours read from the screen.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerFocusLookTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val c = AppColors()
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
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        instrumentation.setInTouchMode(true)
    }

    private fun intent(tv: Boolean) = Intent(context, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))
        .apply { if (tv) putExtra(FormFactor.EXTRA, "tv") }

    /** The round button [description] focused, with the controls held up: its circle's colour, its icon's tint and its scale. */
    private suspend fun focusedRoundButton(tv: Boolean, description: String): Triple<Color, Int, Pair<Float, Float>> {
        ActivityScenario.launch<PlayerActivity>(intent(tv)).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            scenario.onActivity { a -> roundButton(a, description).requestFocus() }
            waitFor("$description focused") { scenario.read { a -> roundButton(a, description).isFocused.takeIf { it } } }
            // The ripple's focus layer fades in.
            delay(600)
            val (activity, button) = scenario.read { a -> a to roundButton(a, description) }
            val density = context.resources.displayMetrics.density
            val pixels = capture(activity, scenario.read { windowRect(button) })
            // Inside the circle, above the icon (24 dp in the middle of 48).
            val circle = average(pixels, pixels.width / 2, (6 * density).toInt())
            val tint = scenario.read { button.imageTintList!!.getColorForState(button.drawableState, 0) }
            return Triple(circle, tint, scenario.read { button.scaleX to button.scaleY })
        }
    }

    @Test
    fun onATvTheRoundButtonsTurnOverWhenFocused() = runBlocking<Unit> {
        listOf("Audio and subtitles", "File details", "Back").forEach { description ->
            val (circle, tint, scale) = focusedRoundButton(tv = true, description)
            assertTrue("$description focused on a TV: the text's cream (${c.text.hex()}), not ${circle.hex()}", near(circle, c.text))
            assertEquals("$description focused on a TV: its icon in the background's near-black", c.background.toArgb(), tint)
            assertGrown("$description focused on a TV", scale)
        }
    }

    /**
     * The round button [description] under a pointer remote's pointer, the focus elsewhere, with the controls held up: its
     * circle's colour, its icon's tint and how much it has grown. The hover is given to the button itself, as the window
     * would pass a pointer's on, so no pointer is left on the screen for the tests after.
     */
    private suspend fun hoveredRoundButton(description: String): Triple<Color, Int, Pair<Float, Float>> {
        ActivityScenario.launch<PlayerActivity>(intent(tv = true)).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            scenario.onActivity { a ->
                val button = roundButton(a, description)
                val enter = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_ENTER, button.width / 2f, button.height / 2f, 0)
                enter.source = InputDevice.SOURCE_MOUSE
                button.dispatchGenericMotionEvent(enter)
                enter.recycle()
            }
            waitFor("$description hovered") { scenario.read { a -> roundButton(a, description).isHovered.takeIf { it } } }
            // The ripple's hover layer fades in, and the button grows.
            delay(600)
            val (activity, button) = scenario.read { a -> a to roundButton(a, description) }
            assertFalse("$description has the focus: only the hover is looked at", scenario.read { button.isFocused })
            val density = context.resources.displayMetrics.density
            val pixels = capture(activity, scenario.read { windowRect(button) })
            val circle = average(pixels, pixels.width / 2, (6 * density).toInt())
            val tint = scenario.read { button.imageTintList!!.getColorForState(button.drawableState, 0) }
            return Triple(circle, tint, scenario.read { button.scaleX to button.scaleY })
        }
    }

    /** Grown as the app's buttons grow on a TV when focused, both ways alike. */
    private fun assertGrown(what: String, scale: Pair<Float, Float>) {
        assertEquals("$what: grown across", FocusStyle.Tv.buttonScale, scale.first, 0.01f)
        assertEquals("$what: grown down", FocusStyle.Tv.buttonScale, scale.second, 0.01f)
    }

    /** A pointer remote's hover shows as the focus does, as on the skip button and the app's own. */
    @Test
    fun onATvARoundButtonUnderThePointerLooksAsWhenFocused() = runBlocking<Unit> {
        val (circle, tint, scale) = hoveredRoundButton("Audio and subtitles")
        assertTrue("hovered on a TV: the text's cream (${c.text.hex()}), not ${circle.hex()}", near(circle, c.text))
        assertEquals("hovered on a TV: its icon in the background's near-black", c.background.toArgb(), tint)
        assertGrown("hovered on a TV", scale)
    }

    @Test
    fun onAPhoneTheRoundButtonsKeepTheAccentsCircle() = runBlocking<Unit> {
        val (circle, tint, _) = focusedRoundButton(tv = false, "Audio and subtitles")
        // Under the ripple's focus layer, the text's cream at 0.24, as before.
        assertTrue("focused on a phone: the accent (${c.accent.hex()}), not ${circle.hex()}", near(circle, c.accent, 0.12f))
        assertEquals("focused on a phone: its icon in the accent's ink", c.onAccent.toArgb(), tint)
    }

    /** The skip button as it takes the remote's focus in the intro (the controls hidden): the colour inside its pill, at its start. */
    private suspend fun focusedSkipButton(tv: Boolean): Color {
        ActivityScenario.launch<PlayerActivity>(intent(tv)).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // Seeking into the intro counts as entering it.
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 3_000) }
            waitFor("Skip intro to have the focus") { scenario.read { a -> skipNode(a)?.takeIf { it.config.getOrNull(SemanticsProperties.Focused) == true } } }
            delay(600)
            val activity = scenario.read { it }
            val bounds = scenario.read { a -> skipNode(a)!!.boundsInWindow }
            val pixels = capture(activity, scenario.read { Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()) })
            val density = context.resources.displayMetrics.density
            return average(pixels, (10 * density).toInt(), pixels.height / 2)
        }
    }

    @Test
    fun onATvTheSkipButtonTurnsOverWhenFocused() = runBlocking<Unit> {
        val pill = focusedSkipButton(tv = true)
        assertTrue("Skip intro focused on a TV: the text's cream (${c.text.hex()}), not ${pill.hex()}", near(pill, c.text))
    }

    @Test
    fun onAPhoneTheSkipButtonKeepsItsSurface() = runBlocking<Unit> {
        val pill = focusedSkipButton(tv = false)
        assertTrue("Skip intro focused on a phone: its surface, not turned over: ${pill.hex()}", near(pill, c.surface, 0.1f))
    }

    // Helpers, as in SkipSegmentsDeviceTest.

    private fun roundButton(activity: Activity, description: String) =
        views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == description }

    /** The skip button's node, while it shows: the polite live region. */
    private fun skipNode(activity: Activity): SemanticsNode? =
        overlayNodes(activity).firstOrNull { n -> n.config.contains(SemanticsProperties.LiveRegion) && subtree(n).any { "Skip intro" in it.words() } }

    /** What shows in [rect] of [activity]'s window, read from the screen. */
    private fun capture(activity: Activity, rect: Rect): Bitmap {
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(activity.window, rect, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("the screen wasn't read", done.await(5, TimeUnit.SECONDS))
        assertEquals("reading the screen", PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun average(bitmap: Bitmap, x: Int, y: Int): Color {
        val pixels = (-1..1).flatMap { dx -> (-1..1).map { dy -> Color(bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))) } }
        return Color(pixels.map { it.red }.average().toFloat(), pixels.map { it.green }.average().toFloat(), pixels.map { it.blue }.average().toFloat())
    }

    private fun near(a: Color, b: Color, tolerance: Float = 0.06f) =
        abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance && abs(a.blue - b.blue) < tolerance

    private fun Color.hex() = "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

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

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun windowRect(view: View): Rect {
        val at = IntArray(2).also(view::getLocationInWindow)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private companion object {
        const val INTRO_START_MS = 5_000L
    }
}

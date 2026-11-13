package io.skjaere.usenet.app.cast

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.casting.CAST_INTRO_GOT_IT
import io.skjaere.usenet.app.casting.CAST_INTRO_TITLE
import io.skjaere.usenet.app.casting.CastIntroRule
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastIntroCircle
import io.skjaere.usenet.app.link.CastIntroFocus
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.link.playOnLabel
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The introduction to the Cast button (W24, W48, W61, L123; CsPhoneIntro), on a phone with Google Play services (the
 * gate's fake says so; nothing of Google's is started), on the emulator's Wi-Fi, after setup, over Home in MainActivity as
 * the launcher opens it. Its flag is cleared by the test itself and the settings are put back after (L143). What it
 * proves: it shows once, round Home's Cast button, kept shown as it appears; it waits while the list is open; Got it
 * closes it and hands the focus to the button; Back closes it; a tap anywhere closes it and reaches nothing under it; a
 * tap on the circled button closes it and opens the list; a recreation or a new activity never brings it back; TalkBack
 * reads it as a pane with where the button is, its focus starting on Got it, where a keyboard's stays while it shows; and
 * Google's software is never started.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastIntroDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val home = HomeContentShown()
    private val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java)
    private val realExploring = CastIntroFocus.exploring
    private val starts = AtomicInteger()

    /** The settings as the test found them, put back after: it clears the introduction's flag and the opt-in. */
    private var saved: AppSettings? = null

    /** Home's Cast button, This phone chosen: what TalkBack reads of it (C1's words). */
    private val button = hasContentDescription(playOnLabel(PlayTarget.Phone)) and hasClickAction()

    /** The introduction, as TalkBack finds it: a pane, read with where the button is (beside Settings, on Home). */
    private val pane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, CastIntroRule.spoken(home = true))

    private val gotIt = hasText(CAST_INTRO_GOT_IT) and hasClickAction()

    @Before
    fun setUp() {
        assumeFalse("a TV never introduces Cast (tv.TvNoCastTest)", container.television)
        home.show()
        runBlocking {
            saved = container.settings.settings.first()
            // As on a phone that never saw it (L143), and never chose to look for Cast devices: the list asks first, and
            // nothing looks for devices behind the test's back.
            container.settings.updateCast { it.copy(introShown = false, look = false) }
            withTimeout(10_000) { container.castIntroShown.first { it == false } }
            withTimeout(10_000) { container.castLooking.first { it == false } }
            assertNotNull("the emulator on its Wi-Fi", withTimeoutOrNull(15_000) { LanAddress.watch(context).filterNotNull().first() })
        }
        // As with TalkBack on: the introduction moves the focus.
        CastIntroFocus.exploring = { true }
        // Google Play services there, as the gate's check says; a start would be counted, and none comes.
        CastFakes.set(
            CastAvailability { true },
            object : CastStarter {
                override fun start(context: Context) {
                    starts.incrementAndGet()
                }

                override fun failed(context: Context) = false
            },
        )
    }

    @After
    fun restore() {
        // As a finger leaves the screen: a case's keys took it out of touch mode, for the whole display.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        CastIntroFocus.exploring = realExploring
        onMainThread { container.playOn.closeSheet() }
        CastFakes.missing()
        saved?.let { runBlocking { container.settings.restore(it) } }
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher): SemanticsNode = compose.onAllNodes(matcher).fetchSemanticsNodes().single()

    private fun focused(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** [millis] of MainActivity's frames, which the rule's clock drives: never a sleep (C1). */
    private fun frames(millis: Long) {
        val until = SystemClock.uptimeMillis() + millis
        compose.waitUntil(millis + 5_000) { SystemClock.uptimeMillis() > until }
    }

    /** Home up with its button, the list open over it as the activity comes up: the introduction waits under it. */
    private fun homeUnderTheList(scenario: ActivityScenario<MainActivity>): Rect {
        compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
        compose.waitUntil(15_000) { exists(button) }
        return node(button).boundsInRoot
    }

    /** Home, the introduction shown over it once the list it waited under closed. */
    private fun shownOverHome(scenario: ActivityScenario<MainActivity>): Rect {
        val bounds = homeUnderTheList(scenario)
        onMainThread { container.playOn.closeSheet() }
        compose.waitUntil(10_000) { exists(pane) }
        return bounds
    }

    /** L123: not over the list, then once it closes, round the button, kept shown as it appears; TalkBack's pane and focus. */
    @Test
    fun overHomeItShowsOnceRoundTheButtonAndGotItHandsTheFocusToIt() {
        onMainThread { container.playOn.openSheet() }
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            val bounds = homeUnderTheList(scenario)
            frames(2_000)
            assertFalse("shown over the list", exists(pane))
            assertEquals("kept shown before it showed", false, container.castIntroShown.value)
            onMainThread { container.playOn.closeSheet() }
            compose.waitUntil(10_000) { exists(pane) }
            // Kept shown as it appears, in the app's own settings.
            compose.waitUntil(5_000) { container.castIntroShown.value == true }
            assertTrue(runBlocking { container.settings.cast.first() }.introShown)
            // Round Home's button: the design's 30 dp circle on its middle.
            val circle = node(pane).config[CastIntroCircle]
            val radius = 30 * context.resources.displayMetrics.density
            assertEquals("the circle's middle, across", bounds.center.x, circle.center.x, 1.5f)
            assertEquals("the circle's middle, down", bounds.center.y, circle.center.y, 1.5f)
            assertEquals("the circle's size", radius, circle.width / 2, 1.5f)
            // Look A as drawn (W61): the screen dimmed nearly black, the amber ring, Home's own colours inside the circle.
            val drawn = compose.onNode(pane).captureToImage().toPixelMap()
            val at = node(pane).boundsInRoot
            val dp = context.resources.displayMetrics.density
            fun colour(x: Float, y: Float): Color = drawn[(x - at.left).toInt(), (y - at.top).toInt()]
            val dimmed = colour(at.center.x, at.top + at.height * 0.85f)
            assertTrue("not dimmed under the words: $dimmed", dimmed.red < 0.06f && dimmed.green < 0.06f && dimmed.blue < 0.06f)
            val ring = colour(circle.center.x + radius, circle.center.y)
            assertTrue("the ring not amber: $ring", abs(ring.red - 0xF2 / 255f) < 0.12f && abs(ring.green - 0xA3 / 255f) < 0.12f && abs(ring.blue - 0x3A / 255f) < 0.15f)
            // Inside the circle, outside the button: Home's search bar as it is, not dimmed.
            val bar = colour(circle.center.x, circle.center.y + 24 * dp)
            assertTrue("the search bar dimmed in the circle: $bar", abs(bar.red - 0x1A / 255f) < 0.03f && abs(bar.green - 0x1C / 255f) < 0.03f && abs(bar.blue - 0x21 / 255f) < 0.03f)
            // Its words, and TalkBack's focus on Got it first.
            assertTrue(exists(hasText(CAST_INTRO_TITLE)))
            compose.waitUntil(5_000) { focused(gotIt) }
            // Under it nothing is read: the introduction is all there is to TalkBack.
            assertFalse("the button read under the introduction", exists(button))
            assertTrue("Google's Cast software started", castUnstarted())
            // Got it: gone, and the focus on the button TalkBack reads as C1 did.
            compose.onNode(gotIt).performClick()
            compose.waitUntil(5_000) { !exists(pane) }
            compose.waitUntil(5_000) { focused(button) }
            assertNull("the list opened", container.playOn.sheet.value)
            assertEquals(0, starts.get())
            assertTrue(castUnstarted())
        }
    }

    @Test
    fun backClosesItAndLeavesHomeShowing() {
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { exists(pane) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !exists(pane) }
            frames(1_000)
            assertEquals("Back left Home", Lifecycle.State.RESUMED, scenario.state)
            assertTrue("Home's button gone", exists(button))
            compose.waitUntil(5_000) { focused(button) }
            assertEquals(true, container.castIntroShown.value)
        }
    }

    /** A tap anywhere but the circle closes it, and reaches nothing under it: here, Settings' gear beside the button. */
    @Test
    fun aTapAnywhereClosesItAndReachesNothingUnderIt() {
        onMainThread { container.playOn.openSheet() }
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            homeUnderTheList(scenario)
            val gear = node(hasContentDescription("Settings") and hasClickAction()).boundsInRoot
            onMainThread { container.playOn.closeSheet() }
            compose.waitUntil(10_000) { exists(pane) }
            val paneAt = node(pane).boundsInRoot
            compose.onNode(pane).performTouchInput { click(Offset(gear.center.x - paneAt.left, gear.center.y - paneAt.top)) }
            compose.waitUntil(5_000) { !exists(pane) }
            frames(1_000)
            assertTrue("Home still showing: the tap reached Settings", exists(button))
            assertNull(container.playOn.sheet.value)
        }
    }

    @Test
    fun aTapOnTheCircledButtonClosesItAndOpensTheList() {
        onMainThread { container.playOn.openSheet() }
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            val bounds = shownOverHome(scenario)
            val paneAt = node(pane).boundsInRoot
            compose.onNode(pane).performTouchInput { click(Offset(bounds.center.x - paneAt.left, bounds.center.y - paneAt.top)) }
            compose.waitUntil(5_000) { !exists(pane) }
            compose.waitUntil(5_000) { container.playOn.sheet.value == SheetPlace.Title }
            frames(1_000)
            assertTrue("Google's Cast software started with the list", castUnstarted())
            assertEquals(0, starts.get())
        }
    }

    /**
     * L123: a keyboard's focus stays in the introduction while it shows, as a pane's: Tab, Shift and Tab, and the arrows
     * leave Got it focused, never on Home's controls under the dimming, which Enter would press unseen.
     */
    @Test
    fun aKeyboardsFocusStaysOnGotItWhileItShows() {
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { exists(pane) }
            compose.waitUntil(5_000) { focused(gotIt) }
            val keys = listOf(
                "Tab" to (KeyEvent.KEYCODE_TAB to 0), "Shift and Tab" to (KeyEvent.KEYCODE_TAB to KeyEvent.META_SHIFT_ON),
                "Up" to (KeyEvent.KEYCODE_DPAD_UP to 0), "Left" to (KeyEvent.KEYCODE_DPAD_LEFT to 0),
            )
            keys.forEach { (name, key) ->
                val (code, meta) = key
                val at = SystemClock.uptimeMillis()
                InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, meta))
                InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
                frames(300)
                assertTrue("Got it lost the focus to $name", focused(gotIt))
                assertTrue("still showing after $name", exists(pane))
            }
        }
    }

    /** Kept shown as it appears: neither a recreation (a turn) nor the next start, which reads the flag again, brings it back. */
    @Test
    fun aRecreationOrTheNextStartNeverBringsItBack() {
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { exists(pane) }
            scenario.recreate()
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { exists(button) }
            frames(2_000)
            assertFalse("back after a recreation", exists(pane))
        }
        // What the next process reads: the flag as kept in settings.json.
        assertTrue(runBlocking { container.settings.cast.first() }.introShown)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) { exists(button) }
            frames(2_000)
            assertFalse("back at the next start", exists(pane))
        }
    }
}

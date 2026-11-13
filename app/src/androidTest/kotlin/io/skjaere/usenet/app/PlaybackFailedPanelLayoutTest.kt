package io.skjaere.usenet.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlaybackFailedPanel
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The player's "Playback stopped" panel where PlayerActivity puts it (bottom end, 16 dp from the
 * side and 100 dp from the bottom) on a landscape phone whose short side is 360 dp: at twice the
 * font size (nonlinear from Android 14, as the system applies it) a three-line reason and the
 * buttons are more than the 260 dp it has, and every button (Try another release, Close, File
 * details) must still show whole, with the reason whole by scrolling. A TV remote sees which
 * button it is on.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackFailedPanelLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val reason = "Part of this release is missing from every Usenet provider you use."

    private fun show(fontScale: Float) {
        val context = instrumentation.targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    Box(Modifier.size(640.dp, 360.dp).testTag("screen")) {
                        PlaybackFailedPanel(reason, canTryAnother = true, onTryAnother = {}, onClose = {}, onDetails = {}, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 100.dp))
                    }
                }
            }
        }
    }

    @After
    fun touchMode() {
        instrumentation.setInTouchMode(true)
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.cut() = didOverflowHeight || isLineEllipsized(lineCount - 1)

    private fun button(label: String) = compose.onNode(hasClickAction() and hasText(label))

    /** Where the panel may be: above the 100 dp the player keeps clear at the bottom. */
    private fun slot(): DpRect = compose.onNodeWithTag("screen").getBoundsInRoot().let { it.copy(bottom = it.bottom - 100.dp) }

    private fun DpRect.holds(other: DpRect) = other.top >= top - 1.dp && other.bottom <= bottom + 1.dp

    @Test
    fun at200PercentOnA360dpTallScreenEveryButtonShowsAndTheReasonIsWhole() {
        show(2f)
        val slot = slot()
        listOf("Try another release", "Close", "File details").forEach { label ->
            val button = button(label).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            val outer = button.getBoundsInRoot()
            assertTrue("$label ($outer) is outside the panel's room ($slot)", slot.holds(outer))
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertFalse("$label is cut or wraps", text.textLayout().cut())
            val inner = text.getBoundsInRoot()
            assertTrue("$label ($inner) sticks out of its button ($outer)", outer.holds(inner))
        }
        val words = compose.onNodeWithText(reason, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertFalse("the reason is cut", words.textLayout().cut())
        val bounds = words.getBoundsInRoot()
        assertTrue("the reason ($bounds) can't be scrolled into the panel's room ($slot)", slot.holds(bounds))
    }

    /** The pixel 1 dp in from the left of [button], halfway down: on its outline when it has one. */
    private fun edge(button: SemanticsNodeInteraction): Color {
        val image = button.captureToImage()
        val x = (instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
        return image.toPixelMap()[x, image.height / 2]
    }

    private fun assertColor(what: String, expected: Color, actual: Color) {
        val close = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue).all { (e, a) -> abs(e - a) < 0.1f }
        assertTrue("$what is $actual, not $expected", close)
    }

    @Test
    fun aRemoteStartsOnTryAnotherReleaseAndSeesWhichButtonItIsOn() {
        instrumentation.setInTouchMode(false)
        show(1f)
        val tryAnother = button("Try another release")
        compose.waitUntil(5_000) { runCatching { tryAnother.assertIsFocused() }.isSuccess }
        // One row at the default size, as the mockup's card.
        assertEquals(button("Close").getBoundsInRoot().top.value, tryAnother.getBoundsInRoot().top.value, 1f)
        val palette = AppColors()
        assertColor("Try another release's edge while focused", palette.text, edge(tryAnother))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        val close = button("Close").assertIsFocused()
        assertColor("Close's edge while focused", palette.accent, edge(close))
        assertColor("Try another release's edge once focus left it", palette.accent, edge(tryAnother))
    }

    /**
     * A window reaches the remote's input mode a moment after it was asked for (or at the remote's first key on a phone):
     * Try another release takes the focus then too, even though the panel showed before it.
     */
    @Test
    fun tryAnotherReleaseTakesTheFocusWhenTheRemotesInputModeComesAfterThePanel() {
        instrumentation.setInTouchMode(true)
        show(1f)
        val tryAnother = button("Try another release").assertIsDisplayed()
        instrumentation.setInTouchMode(false)
        compose.waitUntil(5_000) { runCatching { tryAnother.assertIsFocused() }.isSuccess }
    }
}

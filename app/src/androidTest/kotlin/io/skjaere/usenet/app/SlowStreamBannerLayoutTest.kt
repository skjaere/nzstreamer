package io.skjaere.usenet.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.app.play.SlowStreamBanner
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's slow-stream banner where PlayerActivity puts it (bottom end, 16 dp from the side and 100 dp from the
 * bottom) on a landscape phone whose short side is 360 dp. At twice the font size (QlPhoneLargeText) Try a smaller release
 * is wider than the 300 dp card leaves it: the card grows to the button rather than cut its label, and the button shows
 * whole, the words scrolling above it. TalkBack hears it once as it appears, one polite live region with the numbers in
 * words, and the same as it turns compact with the controls (QlPhoneTalkBack).
 */
@RunWith(AndroidJUnit4::class)
class SlowStreamBannerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val shown = SlowStream.Shown(
        "48 of 80 Mbps",
        "Setting Maximum bitrate to 30 Mbps (Settings › Quality and languages) picks smaller releases next time.",
        "Streaming is slower than this video needs. 48 of 80 megabits per second. Setting Maximum bitrate to 30 megabits per second, in Settings, " +
            "Quality and languages, picks smaller releases next time.",
    )
    private var compact by mutableStateOf(false)

    private fun show(fontScale: Float) {
        val context = instrumentation.targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    Box(Modifier.size(640.dp, 360.dp).testTag("screen")) {
                        SlowStreamBanner(shown, compact, onTrySmaller = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 100.dp).testTag("banner"))
                    }
                }
            }
        }
    }

    @Test
    fun at200PercentOnA360dpTallScreenTheCardGrowsToItsButtonWhichShowsWhole() {
        show(2f)
        val button = compose.onNode(hasClickAction() and hasText(SlowStream.TRY_SMALLER)).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        val pressed = button.getBoundsInRoot()
        val card = compose.onNodeWithTag("banner").getBoundsInRoot()
        val screen = compose.onNodeWithTag("screen").getBoundsInRoot()
        assertTrue("the button ($pressed) is outside the card ($card)", pressed.left >= card.left && pressed.right <= card.right && pressed.bottom <= card.bottom + 1.dp)
        assertTrue("the card ($card) is narrower than 300 dp or its button", card.width >= 300.dp - 1.dp && card.width >= pressed.width + 23.dp)
        assertTrue("the card ($card) is outside the room above the slot's 100 dp", card.top >= screen.top - 1.dp && card.bottom <= screen.bottom - 100.dp + 1.dp)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(SlowStream.TRY_SMALLER, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        // On one line, as its one line allowed: what didn't fit would wrap to a second, which its one line cuts (overflowing
        // its height). Its width can overflow by a fraction of a pixel as the button rounds it; that cuts nothing.
        val label = results.single()
        assertFalse("the label is cut or wraps (${label.lineCount} lines, ${label.size})", label.didOverflowHeight || label.lineCount > 1 || label.isLineEllipsized(0))
    }

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun talkBackHearsTheBannerOnceInWordsAndTheSameAsItTurnsCompact() {
        show(1f)
        compose.onNode(hasContentDescription(shown.spoken) and polite, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(polite, useUnmergedTree = true).fetchSemanticsNodes().size)
        compact = true
        compose.onNode(hasContentDescription(shown.spoken) and polite, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(polite, useUnmergedTree = true).fetchSemanticsNodes().size)
        // Its button by swiping on, after the words.
        compose.onNode(hasClickAction() and hasText(SlowStream.TRY_SMALLER)).assertIsDisplayed()
    }
}

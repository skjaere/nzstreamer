package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.SeasonChips
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.metadata.SeasonSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The Show page's season chips on a TV (TV-3), forced on a phone emulator: the selected season's chip
 * is tinted in the accent; a focused chip turns over, the selected one with a 2 dp amber line under its words; at 1.3x
 * the text size the chips grow, their words whole; the focus on each chip, and leaving them, is reported for the dwell.
 */
@RunWith(AndroidJUnit4::class)
class SeasonChipsTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val colors = AppColors()
    private var scale by mutableFloatStateOf(1f)
    private val focused = mutableListOf<Int>()
    private var left = 0
    private var selected by mutableStateOf<Int?>(2)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    private val seasons = listOf(SeasonSummary(1, null, 8, null, null), SeasonSummary(2, null, 8, null, null), SeasonSummary(3, null, 8, null, null), SeasonSummary(0, null, 2, null, null))

    private fun showChips() {
        compose.setTvContent({ scale }) {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 27.dp).width(540.dp)) {
                SeasonChips(
                    seasons, selected, { n -> if (n == 1) 8 else if (n == 2) 4 else 0 }, { selected = it },
                    onFocused = { focused += it }, onLeft = { left++ },
                )
                Text("Below the chips", Modifier.clickable {}.padding(16.dp))
            }
        }
        compose.waitForIdle()
    }

    private fun chip(label: String) = hasText(label) and hasClickAction()

    /** How many of the pixels inside [matcher]'s node are [color], and their share. */
    private fun pixels(matcher: SemanticsMatcher, color: Color): Pair<Int, Float> {
        compose.waitForIdle()
        val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().toPixelMap()
        val xs = bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width)
        val ys = bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height)
        fun same(a: Color) = abs(a.red - color.red) < 0.03f && abs(a.green - color.green) < 0.03f && abs(a.blue - color.blue) < 0.03f
        val count = xs.sumOf { x -> ys.count { y -> same(image[x, y]) } }
        return count to count.toFloat() / (xs.count() * ys.count())
    }

    @Test
    fun theSelectedChipIsTintedAndAFocusedOneTurnsOverTheSelectedWithAnAmberLine() {
        showChips()
        compose.onNode(chip("Below the chips")).requestFocus()
        remote.waitForFocus("Below the chips")
        val tint = pixels(chip("Season 2 · 4/8"), colors.accentTint).second
        assertTrue("the selected chip's tint: $tint", tint > 0.4f)
        assertTrue("an unselected chip's fill: ${pixels(chip("S3"), colors.chip).second}", pixels(chip("S3"), colors.chip).second > 0.4f)

        compose.onNode(chip("S3")).requestFocus()
        remote.waitForFocus("S3")
        assertTrue("the focused chip turned over: ${pixels(chip("S3"), colors.text).second}", pixels(chip("S3"), colors.text).second > 0.4f)
        assertEquals("an amber line under an unselected chip", 0, pixels(chip("S3"), colors.accent).first)

        compose.onNode(chip("Season 2 · 4/8")).requestFocus()
        remote.waitForFocus("Season 2")
        assertTrue("the focused selected chip turned over", pixels(chip("Season 2 · 4/8"), colors.text).second > 0.4f)
        val line = pixels(chip("Season 2 · 4/8"), colors.accent).first
        assertTrue("the amber line under the focused selected chip: $line pixels", line > 40)
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }

    @Test
    fun atALargerTextSizeTheChipsGrowWithTheirWordsWhole() {
        scale = 1.3f
        showChips()
        listOf("S1 ✓", "Season 2 · 4/8", "S3", "Specials").forEach { label ->
            val layout = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.textLayout() }
            assertTrue("\"$label\" laid out", layout != null)
            assertFalse("\"$label\" cut off at 1.3x", layout!!.didOverflowHeight)
        }
    }

    /** The chips in their order, by where they are: their words may change. */
    private fun chips(): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction() and !hasText("Below the chips")).fetchSemanticsNodes().sortedBy { it.positionInRoot.x }

    /**
     * Showing the focus never changes a chip's size, nor does the dwell selecting the chip the focus rests on: the chips
     * after it stay where they are (FocusLook's rule). S3, not begun, and the specials after it.
     */
    @Test
    fun aChipKeepsItsSizeWhenFocusedAndWhenTheDwellSelectsIt() {
        showChips()
        compose.onNode(chip("Below the chips")).requestFocus()
        remote.waitForFocus("Below the chips")
        fun place() = chips().let { it[2].size to it[3].positionInRoot.x }
        val before = place()
        compose.onNode(chip("S3")).requestFocus()
        remote.waitForFocus("S3")
        assertEquals("S3's size and the specials' place, focused", before, place())
        // As the dwell selects the season the focus rests on.
        compose.runOnIdle { selected = 3 }
        compose.waitForIdle()
        assertEquals("S3's size and the specials' place, focused and selected", before, place())
    }

    @Test
    fun theFocusOnEachChipAndLeavingThemIsReported() {
        showChips()
        compose.onNode(chip("S1 ✓")).requestFocus()
        remote.waitForFocus("S1")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the chips focused in turn", listOf(1, 2, 3), focused)
        assertEquals("left before leaving", 0, left)
        assertEquals("Below the chips", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("left once", 1, left)
    }
}

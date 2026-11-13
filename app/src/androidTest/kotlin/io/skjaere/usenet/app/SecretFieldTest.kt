package io.skjaere.usenet.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.FormField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A secret field's Show on a phone: at the normal font size the icon button it always was (40 dp,
 * touched within 48 dp, its press and focus layer the field's grey), so the forms look as before;
 * with a large font size it grows to fit the word on one line.
 */
@RunWith(AndroidJUnit4::class)
class SecretFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var fontScale by mutableFloatStateOf(1f)

    @Before
    fun showTheField() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                AppTheme(FormFactor.Phone) { FormField("Password", "", {}, kind = FieldKind.Password) }
            }
        }
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private val show = hasText("Show") and hasClickAction()

    @Test
    fun atTheNormalSizeItIsTheIconButtonItWas() {
        compose.onNode(show).assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
    }

    /** The layer over it while it has the focus (or is pressed) is the field's grey, as the icon button's was, not the accent. */
    @Test
    fun itsFocusLayerIsTheFieldsGrey() {
        instrumentation.setInTouchMode(false)
        compose.waitForIdle()
        val unfocused = compose.onRoot().captureToImage().toPixelMap()
        compose.onNode(show).requestFocus()
        compose.waitForIdle()
        compose.onNode(show).assertIsFocused()
        val focused = compose.onRoot().captureToImage().toPixelMap()
        // Above the word, inside the button's circle.
        val bounds = compose.onNode(show).fetchSemanticsNode().boundsInRoot
        val dp = bounds.width / 40f
        val x = bounds.center.x.toInt()
        val y = (bounds.top + 4 * dp).toInt()
        val before = average(unfocused, x, y)
        val after = average(focused, x, y)
        val red = (after.red - before.red) * 255
        val blue = (after.blue - before.blue) * 255
        assertTrue("no layer shows: red +%.1f, blue +%.1f".format(red, blue), red > 5f)
        assertTrue("the layer is tinted (red +%.1f, blue +%.1f of 255): the accent's, not grey".format(red, blue), abs(red - blue) < 5f)
    }

    /** The average colour of the 5 x 5 pixels around [x], [y]. */
    private fun average(map: PixelMap, x: Int, y: Int): Color {
        val pixels = (-2..2).flatMap { dx -> (-2..2).map { dy -> map[x + dx, y + dy] } }
        return Color(pixels.map { it.red }.average().toFloat(), pixels.map { it.green }.average().toFloat(), pixels.map { it.blue }.average().toFloat())
    }

    @Test
    fun atTwiceTheSizeTheWordFitsOnOneLine() {
        fontScale = 2f
        compose.waitForIdle()
        val text = compose.onNode(hasText("Show"), useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>().also { text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
        assertEquals("lines", 1, layout.lineCount)
        val button = compose.onNode(show).fetchSemanticsNode().boundsInRoot
        assertTrue("the word (${text.boundsInRoot.width} px) is wider than its button (${button.width} px)", text.boundsInRoot.width <= button.width)
    }
}

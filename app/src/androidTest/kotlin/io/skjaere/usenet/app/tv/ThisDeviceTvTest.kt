package io.skjaere.usenet.app.tv

import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.settings.ThisDeviceCard
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * This device on a real TV (tv36): Settings › This device opens with the Device card focused; Down goes through the
 * cards, each ringed in cream while it has the focus; Back returns to Settings with the focus on the "This device" row.
 * tv36's Android 16 doesn't move the focus for an instrumentation's arrow keys, so the focus is moved as they would, by
 * the screen's FocusManager; OK and Back are keys.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class ThisDeviceTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var nav: NavHostController
    private lateinit var focusManager: FocusManager

    @Before
    fun tv() {
        assumeTv()
        home.show()
        instrumentation.setInTouchMode(false)
        compose.setContent {
            AppTheme(FormFactor.Tv) {
                focusManager = LocalFocusManager.current
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        remote.waitForHomeFocus(timeoutMs = 10_000)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private val card = SemanticsMatcher.keyIsDefined(ThisDeviceCard)

    private fun focusedCard(): String? = compose.onAllNodes(card and isFocused()).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(ThisDeviceCard)

    private fun down() {
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()
    }

    /**
     * A card's ring: along its top edge, halfway across, a cream pixel while it is [focused], none while it isn't. The
     * card grows 1.02 as it is focused, so its ring is drawn a little above its bounds: looked for on the whole screen,
     * from just above where it grew to.
     */
    private fun assertCreamRing(title: String, focused: Boolean = true) {
        compose.waitForIdle()
        val node = SemanticsMatcher.expectValue(ThisDeviceCard, title)
        val bounds = compose.onNode(if (focused) node and isFocused() else node).fetchSemanticsNode().boundsInRoot
        val image = compose.onNode(isRoot()).captureToImage().toPixelMap()
        val dp = instrumentation.targetContext.resources.displayMetrics.density
        val top = bounds.top - if (focused) bounds.height * 0.01f else 0f
        val x = bounds.center.x.roundToInt()
        val cream = AppColors().text
        val edge = ((top - 2 * dp).roundToInt()..(top + 4 * dp).roundToInt()).filter { it in 0 until image.height }.map { y -> image[x, y] }
        assertTrue("$title's top edge isn't on the screen", edge.isNotEmpty())
        val ringed = edge.any { pixel -> listOf(cream.red to pixel.red, cream.green to pixel.green, cream.blue to pixel.blue).all { (e, a) -> abs(e - a) < 0.1f } }
        assertEquals("$title's top edge is $edge; cream ${Color(cream.value)} expected: $focused", focused, ringed)
    }

    @Test
    fun settingsThisDeviceOpensOnDeviceGoesDownThroughTheCardsRingedInCreamAndBackReturnsToItsRow() {
        compose.onNode(railItem("Settings")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Usenet providers")
        // Down the hub to This device, the third Playback row.
        repeat(8) { if ("This device" !in remote.focused()) down() }
        assertTrue("never reached This device; at ${remote.focused()}", remote.focused().startsWith("This device"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        try {
            compose.waitUntil(5_000) { focusedCard() == "Device" }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("This device never opened on Device; the focus is on ${remote.focused()}", e)
        }
        assertTrue(compose.onAllNodes(hasText("…"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // What this TV's page says, a row to a line, for the record.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .mapNotNull { node -> node.config[SemanticsProperties.Text].map { it.text }.takeIf { it.size >= 2 } }
            .forEach { Log.i("ThisDevicePage", it.joinToString(" | ")) }
        assertCreamRing("Device")
        // The next card, below it on the screen, has no ring.
        assertCreamRing("Display", focused = false)
        listOf("Display", "Video", "Audio", "yEnc decoding").forEach { title ->
            down()
            compose.waitUntil(5_000) { focusedCard() == title }
            assertCreamRing(title)
        }
        // The last keeps it.
        down()
        assertEquals("yEnc decoding", focusedCard())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("This device")
        assertTrue(remote.focused(), remote.focused().startsWith("This device"))
    }
}

package io.skjaere.usenet.app.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.search.SearchPaneKey
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.rememberFieldChain
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A field's gate with a real TV's keyboard (tv36): the focus on the gate brings no keyboard, so the remote's arrows
 * stay the app's (#236); OK puts the focus in the field and the keyboard comes; Back puts the keyboard away and the
 * focus back on the gate. The image's keyboard is logged, for the record. (The focus is put on the gate by semantics:
 * the TV emulator doesn't deliver a remote's arrows sent from here.)
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvImeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
        // As the app's activities are: the keyboard's insets reach the screens.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val ime = Settings.Secure.getString(compose.activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        Log.i(TAG, "the TV image's keyboard: $ime")
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private fun keyboardUp(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    @Test
    fun theGateWithTheFocusBringsNoKeyboardAndOkDoes() {
        compose.setContent {
            AppTheme {
                var host by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("563") }
                val chain = rememberFieldChain()
                Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Test", {})
                    FormField("Host", host, { host = it }, chain = chain)
                    FormField("Port", port, { port = it }, chain = chain)
                }
            }
        }
        compose.onNode(hasText("Test") and hasClickAction()).requestFocus()
        compose.onNode(fieldGate("Host")).requestFocus()
        compose.waitForIdle()
        assertTrue("on Host's gate; focus on ${remote.focused()}", remote.isFocused(fieldGate("Host")))
        // Given the time a keyboard takes to come.
        val deadline = System.currentTimeMillis() + KEYBOARD_MS
        while (System.currentTimeMillis() < deadline) {
            assertFalse("the gate with the focus brought the keyboard", keyboardUp())
            Thread.sleep(100)
        }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(KEYBOARD_MS) { keyboardUp() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("OK on the gate brought no keyboard; focus on \"${remote.focused()}\"", e)
        }
        assertTrue("OK on the gate: the field has the focus", compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty())

        // The keyboard takes Back, to go; the focus goes back to the gate.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(KEYBOARD_MS) { !keyboardUp() }
        try {
            compose.waitUntil(KEYBOARD_MS) { remote.isFocused(fieldGate("Host")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Back from the field: focus on \"${remote.focused()}\"", e)
        }
        assertFalse("the activity went", compose.activity.isFinishing)
    }

    /**
     * Search's field on the TV, in its two panes: the gate with the focus brings no keyboard, OK does, asked to float left
     * (Gboard on a TV takes the option). Where it shows, and how much of the right pane it covers, is logged for the
     * record. Back puts it away and the focus back on the gate.
     */
    @Test
    fun searchsKeyboardComesOnOkAskedToFloatLeft() {
        lateinit var nav: NavHostController
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        try {
            compose.waitUntil(10_000) { remote.isFocused(fieldGate("Search")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Search's gate never had the focus; it is on \"${remote.focused()}\"", e)
        }
        val deadline = System.currentTimeMillis() + KEYBOARD_MS
        while (System.currentTimeMillis() < deadline) {
            assertFalse("Search's gate with the focus brought the keyboard", keyboardUp())
            Thread.sleep(100)
        }
        val before = instrumentation.uiAutomation.takeScreenshot()

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(KEYBOARD_MS) { keyboardUp() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("OK on Search's gate brought no keyboard; focus on \"${remote.focused()}\"", e)
        }
        val options = compose.runOnIdle {
            val info = EditorInfo()
            compose.activity.window.decorView.findFocus()?.onCreateInputConnection(info)
            info.privateImeOptions
        }
        assertEquals("horizontalAlignment=left", options)
        // Where it shows, for the record (the screen's change below the field). Gboard on a TV takes the option and floats
        // left, over the recent searches; this image's keyboard sits centred at the bottom whatever it is asked
        // (about 250 to 710 dp across, from about 300 dp down, as a form field's), so here it covers the
        // lower left of the results.
        Thread.sleep(1_000)
        val after = instrumentation.uiAutomation.takeScreenshot()
        assertTrue("the keyboard went before it was looked at", keyboardUp())
        val at = IntArray(2)
        compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(at) }
        val right = compose.onNode(SemanticsMatcher.expectValue(SearchPaneKey, "results")).fetchSemanticsNode().boundsInWindow.translate(at[0].toFloat(), at[1].toFloat())
        Log.i(TAG, "Search's keyboard on screen: ${changedArea(before, after, BELOW_THE_FIELD_PX)} (its window as listed: ${keyboardWindowOnScreen()}); the right pane: $right, ${"%.1f".format(changedIn(before, after, right) * 100)} % of it covered")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(KEYBOARD_MS) { !keyboardUp() }
        try {
            compose.waitUntil(KEYBOARD_MS) { remote.isFocused(fieldGate("Search")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Back from Search's field: focus on \"${remote.focused()}\"", e)
        }
    }

    private fun same(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 8 && abs(Color.green(a) - Color.green(b)) <= 8 && abs(Color.blue(a) - Color.blue(b)) <= 8

    /** Where [after] differs from [before] from [fromY] down (every 4th pixel looked at), as a rect on the screen; null where nothing does. */
    private fun changedArea(before: Bitmap, after: Bitmap, fromY: Int): Rect? {
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
        for (y in fromY until minOf(before.height, after.height) step 4) for (x in 0 until minOf(before.width, after.width) step 4) {
            if (!same(before.getPixel(x, y), after.getPixel(x, y))) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** The share of [area]'s pixels (every 4th looked at) that differ between [before] and [after]. */
    private fun changedIn(before: Bitmap, after: Bitmap, area: Rect): Float {
        var seen = 0; var changed = 0
        for (y in area.top.toInt() until minOf(area.bottom.toInt(), before.height, after.height) step 4) {
            for (x in area.left.toInt() until minOf(area.right.toInt(), before.width, after.width) step 4) {
                seen++
                if (!same(before.getPixel(x, y), after.getPixel(x, y))) changed++
            }
        }
        return if (seen == 0) 0f else changed.toFloat() / seen
    }

    private companion object {
        const val TAG = "TvImeTest"
        /** Longer than the TV image's keyboard takes to come up or go. */
        const val KEYBOARD_MS = 3_000L
        /** Below Search's field on a 1080p screen, where only the keyboard changes the screen as it comes. */
        const val BELOW_THE_FIELD_PX = 400
    }
}

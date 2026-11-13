package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.discover.DISCOVER
import io.skjaere.usenet.app.discover.TvDiscoverKey
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
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
 * The app on a real TV (tv36) with nothing but a remote: started from the TV launcher on Home with
 * the focus in its content, every tab opened from the rail with its own first focus (Discover's too, in the builds
 * that have it), and three
 * presses of Back from a tab's content back to the TV's home screen. Search's field waits for OK
 * (#236): its gate takes the focus with no keyboard to take the arrows, and one Back goes to the rail.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun tv() {
        assumeTv()
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        home.restore()
    }

    private fun railFocused(label: String) = remote.isFocused(railItem(label))

    /** From the content, Left to the rail (entered at the current tab), along it to [label], and OK. */
    private fun open(label: String) {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        along(label)
    }

    /** From the rail, along it to [label], and OK. */
    private fun along(label: String) {
        val order = tabsFor(FormFactor.Tv).map { it.label }
        val from = order.firstOrNull { railFocused(it) } ?: throw AssertionError("not on the rail; focus on ${remote.focused()}")
        val key = if (order.indexOf(label) < order.indexOf(from)) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(abs(order.indexOf(label) - order.indexOf(from))) { remote.press(key) }
        assertTrue("on the rail's $label; focus on ${remote.focused()}", railFocused(label))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun waitForDiscoverFocus() {
        try {
            compose.waitUntil(5_000) { remote.isFocused(hasAnyAncestor(SemanticsMatcher.keyIsDefined(TvDiscoverKey))) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never went into Discover's content; it is on \"${remote.focused()}\"", e)
        }
    }

    /** No TV keyboard comes, given the time one takes to. */
    private fun assertNoKeyboard(where: String) {
        val deadline = System.currentTimeMillis() + KEYBOARD_MS
        while (System.currentTimeMillis() < deadline) {
            var up = false
            scenario.onActivity { up = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            assertFalse("$where: the keyboard came up", up)
            Thread.sleep(100)
        }
    }

    @Test
    fun everyTabOpensByRemoteAndThreeBacksLeave() {
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            this.scenario = scenario
            remote.waitForHomeFocus(timeoutMs = 10_000)
            assertTrue("Home starts with the focus in its content", !railFocused("Home"))

            open("Search")
            compose.waitUntil(5_000) { compose.onAllNodes(fieldGate("Search") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            assertNoKeyboard("Search's field's gate with the focus")
            remote.press(KeyEvent.KEYCODE_BACK)
            assertTrue("Back from Search's field: the rail; focus on ${remote.focused()}", railFocused("Search"))

            along("Home")
            remote.waitForHomeFocus()

            // Discover's own first focus, in its content: the switch, or without a key its card's button, over the device
            // tests' invented lists (the runner's FakeDiscover). Only the debug, bench and beta builds have it.
            if (tabsFor(FormFactor.Tv).any { it.label == DISCOVER }) {
                open(DISCOVER)
                waitForDiscoverFocus()
            }

            // The Collection's own first focus, in its content: the switch, an empty side's button or the first poster, as
            // the library is.
            open("Collection")
            remote.waitForCollectionFocus()

            open("Settings")
            remote.waitForFocus("Usenet providers")

            remote.press(KeyEvent.KEYCODE_BACK)
            assertTrue("Back from Settings' content: the rail; focus on ${remote.focused()}", railFocused("Settings"))
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Back from the rail: Home, the focus on its rail item; focus on ${remote.focused()}", railFocused("Home"))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("the third Back leaves the app", Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    private companion object {
        /** Longer than the TV image's keyboard takes to come up (under a second). */
        const val KEYBOARD_MS = 2_000L
    }
}

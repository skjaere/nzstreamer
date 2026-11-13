package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back on a real TV (tv36, Android 16 at targetSdk 36, where Back reaches the app as a back
 * callback, not as a key): from the Collection's content to the rail, from the rail to Home, from
 * Home's rail item out of the app.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvBackTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()

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

    @Test
    fun backGoesFromTheContentToTheRailThenHomeThenToTheTvsHomeScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            remote.waitForHomeFocus(timeoutMs = 10_000)
            remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            // Down the rail to Collection, past whatever the build's tabs put between (Discover in debug builds).
            val rail = tabsFor(FormFactor.Tv).map { it.label }
            repeat(rail.indexOf("Collection") - rail.indexOf("Home")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertTrue("focus on ${remote.focused()}", railFocused("Collection"))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForCollectionFocus()

            remote.press(KeyEvent.KEYCODE_BACK)
            assertTrue("Back from the content: the rail; focus on ${remote.focused()}", railFocused("Collection"))
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Back from the rail: Home, the focus on the rail; focus on ${remote.focused()}", railFocused("Home"))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("Back on Home's rail item leaves the app", Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}

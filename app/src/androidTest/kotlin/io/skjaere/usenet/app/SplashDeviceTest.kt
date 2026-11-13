package io.skjaere.usenet.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity's splash, on a phone and on a TV: the activity starts on the splash theme, and by the
 * time Home (or the first-run screen) shows, it is on the app's own theme, its window on the app's
 * background, and the splash was held by its gate (asked before the first frame) until the settings
 * Home shows by were read, not until the splash's timeout.
 */
@RunWith(AndroidJUnit4::class)
class SplashDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val background = AppColors().background.toArgb()

    private val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java)

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitHome() = compose.waitUntil(10_000) { shown("Search your indexers") || shown("Set up your sources") }

    @Test
    fun startedAsTheLauncherStartsItHomeComesUpOnTheAppsThemeOnceReady() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals("MainActivity doesn't start on the splash theme", R.style.Theme_NZStreamer_Splash, info.themeResource)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            awaitHome()
            scenario.onActivity { activity ->
                val value = TypedValue()
                assertTrue(activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true))
                assertTrue("the window background isn't a colour", value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
                assertEquals("the theme's window background", Integer.toHexString(background), Integer.toHexString(value.data))
                assertEquals("the window's background", Integer.toHexString(background), (activity.window.decorView.background as? ColorDrawable)?.color?.let(Integer::toHexString))
                assertTrue("the splash's gate was never asked: nothing holds the splash", activity.splashGate.asked > 0)
                assertFalse("the splash waited out its timeout: the settings weren't read", activity.splashGate.timedOut)
            }
        }
    }

    /**
     * Turned, say: the new activity keeps the splash's gate, so a splash still up stays until the
     * settings are read, and one gone stays gone.
     */
    @Test
    fun turnedTheActivityKeepsItsSplashGate() {
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            awaitHome()
            var first: SplashGate? = null
            var asked = 0
            scenario.onActivity {
                first = it.splashGate
                asked = it.splashGate.asked
            }
            scenario.recreate()
            awaitHome()
            scenario.onActivity {
                assertSame("the turned activity has a splash gate of its own", first, it.splashGate)
                assertTrue("the turned activity never asked the gate", it.splashGate.asked > asked)
            }
        }
    }

    /**
     * As after the process died: Android brings the activity back with its saved screens, but its view
     * models are gone, and with them the gate; the new one holds the splash until the settings are read
     * (which they already are, in this process, so it lets go at once).
     */
    @Test
    fun restoredWithoutItsViewModelsTheActivityHasANewSplashGate() {
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            awaitHome()
            var first: SplashGate? = null
            scenario.onActivity {
                first = it.splashGate
                it.viewModelStore.clear()
            }
            scenario.recreate()
            awaitHome()
            scenario.onActivity {
                assertNotSame("the restored activity kept the gate of the one before", first, it.splashGate)
                assertTrue("the splash's gate was never asked: nothing holds the splash", it.splashGate.asked > 0)
                assertFalse("the splash waited out its timeout: the settings weren't read", it.splashGate.timedOut)
            }
        }
    }
}

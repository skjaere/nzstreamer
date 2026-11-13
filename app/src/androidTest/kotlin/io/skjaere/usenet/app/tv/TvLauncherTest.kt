package io.skjaere.usenet.app.tv

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app in a real TV's launcher (tv36): listed under its name through MAIN + LEANBACK_LAUNCHER,
 * with its banner, and started from there on Home with the remote's focus in the content.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvLauncherTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pm = context.packageManager
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

    private fun tvLaunch(): Intent {
        val launch = pm.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("the TV launcher doesn't list the app: no activity has MAIN with LEANBACK_LAUNCHER", launch)
        return launch!!
    }

    @Test
    fun theTvLauncherListsNZStreamerWithItsBanner() {
        val launch = tvLaunch()
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertEquals("NZStreamer", pm.getApplicationLabel(context.applicationInfo).toString())
        val activity = pm.resolveActivity(launch, 0)?.activityInfo
        assertNotNull(activity)
        // The activity's banner, else the application's: what the launcher shows.
        val banner = activity!!.loadBanner(pm)
        assertNotNull("no banner", banner)
        // 320 x 180 px at xhdpi: 160 x 90 dp at any density.
        val density = context.resources.displayMetrics.density
        assertEquals(160f, banner.intrinsicWidth / density, 1f)
        assertEquals(90f, banner.intrinsicHeight / density, 1f)
    }

    @Test
    fun startedFromTheTvLauncherItShowsHomeWithTheFocusInTheContent() {
        assertEquals(FormFactor.Tv, FormFactor.of(context))
        ActivityScenario.launch<MainActivity>(tvLaunch()).use {
            remote.waitForHomeFocus(timeoutMs = 10_000)
            assertFalse("the focus is on the rail", remote.isFocused(railItem("Home")))
        }
    }
}

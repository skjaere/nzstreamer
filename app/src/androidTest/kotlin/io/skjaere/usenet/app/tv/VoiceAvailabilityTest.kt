package io.skjaere.usenet.app.tv

import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.search.VoiceSearch
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's mic on a real TV (tv36): it shows only where a recognizer resolves (the manifest's <queries> lets the app
 * see one; tv36 has Google's speech services), and hides where none does, as on many AOSP boxes (told so
 * through VoiceSearch's seam). What the image has is logged, for the record.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class VoiceAvailabilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val realResolves = VoiceSearch.resolves
    private lateinit var nav: NavHostController

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        VoiceSearch.resolves = realResolves
        instrumentation.setInTouchMode(true)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theMicShowsOnlyWhereARecognizerResolves() {
        val context = instrumentation.targetContext
        val recognizers = context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        Log.i(TAG, "speech recognizers on this TV image: ${recognizers.map { it.activityInfo.packageName }}")
        val resolves = recognizers.isNotEmpty()
        assertEquals("VoiceSearch.available", resolves, runBlocking { VoiceSearch.available(context) })

        assertEquals("the mic shows", resolves, micShown())
    }

    @Test
    fun noMicWhereNoRecognizerResolves() {
        VoiceSearch.resolves = { false }
        assertFalse("a mic, though no recognizer resolves", micShown())
    }

    /** Search, opened fresh in the device's layouts (a TV's): whether its mic shows. */
    private fun micShown(): Boolean {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        compose.waitUntil(10_000) { exists(fieldGate("Search")) }
        // Whether one resolves is asked off the main thread: the mic shows once it is known.
        Thread.sleep(1_000)
        compose.waitForIdle()
        return exists(hasContentDescription("Search by voice"))
    }

    private companion object {
        const val TAG = "VoiceAvailabilityTest"
    }
}

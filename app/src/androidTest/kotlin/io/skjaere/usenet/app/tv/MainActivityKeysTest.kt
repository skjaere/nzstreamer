package io.skjaere.usenet.app.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.VoiceSearch
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.LogBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity's keys on a TV (its layouts asked for with the debug launch extra on the phone
 * emulator): the remote's search key opens a new search, and with detailed logging a key nothing
 * takes is logged by its code, so a shared log says what a box's remote sends (not the arrows,
 * which move the focus once the app has passed them on).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityKeysTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private val levelBefore = AndroidLogConfig.minLevel
    private val realResolves = VoiceSearch.resolves

    @Before
    fun remote() {
        home.show()
        instrumentation.setInTouchMode(false)
        // The key asks Search for voice where a recognizer resolves (SearchTvTest has that): none here, so no real one starts.
        VoiceSearch.resolves = { false }
    }

    @After
    fun restore() {
        VoiceSearch.resolves = realResolves
        AndroidLogConfig.minLevel = levelBefore
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private fun tvIntent() = Intent(instrumentation.targetContext, MainActivity::class.java).putExtra(FormFactor.EXTRA, "tv")

    @Test
    fun theSearchKeyOpensANewSearchWithItsFieldFocused() {
        ActivityScenario.launch<MainActivity>(tvIntent()).use {
            remote.waitForHomeFocus(timeoutMs = 10_000)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SEARCH)
            compose.waitUntil(5_000) { compose.onAllNodes(fieldGate("Search") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** The play sheet is a window of its own, whose keys the activity doesn't see: the key hides it and opens Search all the same. */
    @Test
    fun theSearchKeyOpensANewSearchFromThePlaySheetTooAndHidesIt() {
        ActivityScenario.launch<MainActivity>(tvIntent()).use {
            remote.waitForHomeFocus(timeoutMs = 10_000)
            val playback = instrumentation.targetContext.container.playback
            try {
                // A start with no releases to try fails at once: its sheet stays up, Close focused.
                playback.play(PlayRequest("Search key check", null, emptyList(), watch = WatchTarget(WatchKind.Release, "Search key check")))
                remote.waitForFocus("Close")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SEARCH)
                try {
                    compose.waitUntil(5_000) { compose.onAllNodes(fieldGate("Search") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Search never opened; the sheet shows: ${playback.expanded.value}; focus on \"${remote.focused()}\"", e)
                }
                assertFalse("the sheet still shows", playback.expanded.value)
            } finally {
                playback.dismiss()
            }
        }
    }

    @Test
    fun aKeyNothingTakesIsLoggedButNotTheArrows() {
        AndroidLogConfig.detailed = true
        ActivityScenario.launch<MainActivity>(tvIntent()).use {
            remote.waitForHomeFocus(timeoutMs = 10_000)
            val before = LogBuffer.snapshot().maxOfOrNull { it.seq } ?: 0L
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            remote.press(KeyEvent.KEYCODE_DPAD_UP)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CHANNEL_UP)
            compose.waitUntil(5_000) { LogBuffer.snapshot().any { it.seq > before && "Key 166 CHANNEL_UP not handled" in it.message } }
            val arrows = LogBuffer.snapshot().filter { it.seq > before && "DPAD_" in it.message && "not handled" in it.message }.map { it.message }
            assertEquals("the D-pad's moves were logged", emptyList<String>(), arrows)
        }
    }
}

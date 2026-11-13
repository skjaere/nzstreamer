package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.IndexerEditScreen
import io.skjaere.usenet.app.settings.MetadataScreen
import io.skjaere.usenet.app.settings.ProviderScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TV_TYPING_TIP
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings forms on a TV (forced on the phone emulator): each starts with the tip about typing with a remote, and
 * a provider's connections are set with [ − ] and [ + ], the remote's OK on each a step. A phone's forms are as they
 * were: no tip, Connections a field.
 */
@RunWith(AndroidJUnit4::class)
class TvFormsTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val tip = hasText(TV_TYPING_TIP)

    /** The tip above the form's first field: [first]'s gate. */
    private fun assertTheTipIsAbove(first: String) {
        compose.waitUntil(5_000) { exists(fieldGate(first)) }
        assertTrue("no tip", exists(tip))
        val tipBounds = compose.onNode(tip).fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(fieldGate(first)).fetchSemanticsNode().boundsInRoot
        assertTrue("the tip ($tipBounds) isn't above the first field ($field)", tipBounds.bottom <= field.top)
    }

    @Test
    fun theProviderFormStartsWithTheTip() {
        compose.setTvContent { ProviderScreen(id = null, onBack = {}) }
        assertTheTipIsAbove("Name")
    }

    @Test
    fun theIndexerFormStartsWithTheTip() {
        compose.setTvContent { IndexerEditScreen(id = null, onBack = {}) }
        assertTheTipIsAbove("Name")
    }

    @Test
    fun theTmdbKeyFormStartsWithTheTip() {
        compose.setTvContent { MetadataScreen(onBack = {}) }
        assertTheTipIsAbove("TMDB API key or read access token")
    }

    private val more = hasContentDescription("More connections") and hasClickAction()
    private val fewer = hasContentDescription("Fewer connections") and hasClickAction()

    @Test
    fun aProvidersConnectionsAreStepsOnATv() {
        compose.setTvContent { ProviderScreen(id = null, onBack = {}) }
        compose.waitUntil(5_000) { exists(more) }
        assertFalse("Connections is a field to type in", exists(fieldGate("Connections")))
        assertTrue("a new provider's 8 connections", exists(hasText("8")))
        compose.onNode(more).requestFocus()
        compose.waitForIdle()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("9")) }
        // Left from + to −, and two steps down.
        assertTrue("Left from +", remote.press(KeyEvent.KEYCODE_DPAD_LEFT).startsWith("Fewer connections"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("7")) }
        assertTrue("the focus stays on −; it is on ${remote.focused()}", remote.isFocused(fewer))
    }

    @Test
    fun onAPhoneTheFormsHaveNoTipAndConnectionsIsAField() {
        compose.setContent { AppTheme(FormFactor.Phone) { ProviderScreen(id = null, onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasSetTextAction() and hasText("8")) }
        assertFalse("a phone's form has the tip", exists(tip))
        assertFalse("a phone's Connections are steps", exists(more))
    }
}

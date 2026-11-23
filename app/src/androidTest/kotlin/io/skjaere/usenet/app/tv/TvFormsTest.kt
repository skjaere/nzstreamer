package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.debrid.TorrentIndexerEditScreen
import io.skjaere.usenet.app.settings.IndexerEditScreen
import io.skjaere.usenet.app.settings.MetadataScreen
import io.skjaere.usenet.app.settings.ProviderScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FieldGateLabel
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TV_TYPING_TIP
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings forms on a TV (forced on the phone emulator): each starts with the tip about typing with a remote, and
 * a provider's connections are set with [ − ] and [ + ], the remote's OK on each a step; both indexer forms' User-Agent
 * is a gate between API key and Test connection. A phone's forms are as they were: no tip, Connections a field.
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

    /** The text field behind [label]'s gate. */
    private fun field(label: String): SemanticsMatcher = hasSetTextAction() and SemanticsMatcher("behind the gate $label") { node ->
        generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(FieldGateLabel) == label }
    }

    /** [words] in [label]'s field as a keyboard leaves them, the field not opened (no keyboard comes up). */
    private fun type(label: String, words: String) {
        compose.onNode(field(label)).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(words)) }
        compose.waitForIdle()
    }

    private fun imeAction(label: String): ImeAction? = compose.onNode(field(label)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction)

    /**
     * L546, L547, L559: on both indexer forms the User-Agent is a gate of its own, Down from API key and Up from Test
     * connection, which is Down from it; TalkBack on it hears the words typed, as a User-Agent is no secret (W144). The
     * keyboard's Next from API key opens it, and its Done, as the forms' last field, puts the focus back on its gate.
     */
    @Test
    fun bothIndexerFormsGoFromApiKeyToTheUserAgentToTestConnection() {
        var torrent by mutableStateOf(false)
        compose.setTvContent { if (torrent) TorrentIndexerEditScreen(id = null, onBack = {}) else IndexerEditScreen(id = null, onBack = {}) }
        theUserAgentIsAGateBetweenApiKeyAndTest("the indexer form")
        compose.runOnIdle { torrent = true }
        theUserAgentIsAGateBetweenApiKeyAndTest("the torrent indexer form")
    }

    private fun theUserAgentIsAGateBetweenApiKeyAndTest(form: String) {
        compose.waitUntil(5_000) { exists(fieldGate(AGENT_LABEL)) }
        // Test connection takes the focus once there is something to test: an address (never asked) and a key.
        type("API URL", "https://indexer.example.org/api")
        type("API key", "invented-indexer-key")
        type(AGENT_LABEL, AGENT)
        val test = hasText("Test connection") and hasClickAction()
        compose.waitUntil(5_000) { exists(test and isEnabled()) }

        compose.onNode(fieldGate("API key")).requestFocus()
        compose.waitForIdle()
        assertEquals("$form: Down from API key", AGENT_LABEL, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("$form: on the User-Agent's gate, not in its field", remote.isFocused(fieldGate(AGENT_LABEL)))
        val below = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("$form: Down from the User-Agent: $below", below.startsWith("Test connection"))
        assertEquals("$form: Up from Test connection", AGENT_LABEL, remote.press(KeyEvent.KEYCODE_DPAD_UP))
        val says = compose.onNode(fieldGate(AGENT_LABEL)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("$form: what TalkBack hears on the gate besides its name", AGENT, says)

        assertEquals("$form: API key's keyboard action", ImeAction.Next, imeAction("API key"))
        assertEquals("$form: the User-Agent's, the last field's", ImeAction.Done, imeAction(AGENT_LABEL))
        compose.onNode(fieldGate("API key")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(field("API key") and isFocused()) }
        compose.onNode(field("API key")).performImeAction()
        try {
            compose.waitUntil(5_000) { exists(field(AGENT_LABEL) and isFocused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$form: Next from API key never opened the User-Agent; the focus is on \"${remote.focused()}\"", e)
        }
        compose.onNode(field(AGENT_LABEL)).performImeAction()
        try {
            compose.waitUntil(5_000) { remote.isFocused(fieldGate(AGENT_LABEL)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$form: Done never put the focus back on the User-Agent's gate; it is on \"${remote.focused()}\"", e)
        }
        assertFalse("$form: Done left a field focused, and its keyboard", exists(hasSetTextAction() and isFocused()))
    }

    @Test
    fun onAPhoneTheFormsHaveNoTipAndConnectionsIsAField() {
        compose.setContent { AppTheme(FormFactor.Phone) { ProviderScreen(id = null, onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasSetTextAction() and hasText("8")) }
        assertFalse("a phone's form has the tip", exists(tip))
        assertFalse("a phone's Connections are steps", exists(more))
    }

    private companion object {
        const val AGENT_LABEL = "User-Agent"
        /** An invented product token, no real app's. */
        const val AGENT = "ExampleReader/2.1"
    }
}

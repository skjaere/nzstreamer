package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.sendRemoteKey
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FieldGateLabel
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.rememberFieldChain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A form's fields with a remote on a TV (forced on the phone emulator): each is a button until OK. The remote's focus
 * lands on a field's gate and passes over it, the field itself not focused (so no keyboard); OK puts the focus in the
 * field; the keyboard's Next goes on to the next field, Done on the last back to its gate, as Back does. A secret
 * field's Show is Right of its gate. A phone's fields have no gates.
 */
@RunWith(AndroidJUnit4::class)
class TvFieldGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
        // As the app's activities are: the keyboard's insets reach the screens.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @Composable
    private fun Form() {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("563") }
        var password by remember { mutableStateOf("") }
        val chain = rememberFieldChain()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Test", {})
            FormField("Host", host, { host = it }, chain = chain)
            FormField("Port", port, { port = it }, chain = chain)
            FormField("Password", password, { password = it }, kind = FieldKind.Password, chain = chain)
            SecondaryButton("Save", {})
        }
    }

    private fun gate(label: String) = SemanticsMatcher.expectValue(FieldGateLabel, label)

    /** The text field behind [label]'s gate. */
    private fun field(label: String): SemanticsMatcher = hasSetTextAction() and SemanticsMatcher("behind the gate $label") { node ->
        generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(FieldGateLabel) == label }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun typing() = exists(hasSetTextAction() and isFocused())

    private fun focusOn(matcher: SemanticsMatcher) {
        compose.onNode(matcher).requestFocus()
        compose.waitForIdle()
    }

    @Test
    fun theRemotesFocusLandsOnTheGatesAndPassesOverThem() {
        compose.setTvContent { Form() }
        focusOn(hasText("Test") and hasClickAction())
        assertEquals("Down from Test", "Host", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("on Host's gate", remote.isFocused(gate("Host")))
        assertFalse("the field took the focus, and its keyboard", typing())
        assertEquals("Down from Host's gate", "Port", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Down from Port's gate", "Password", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Down from Password's gate", "Save", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Up from Save", "Password", remote.press(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse("a field took the focus on the way, and its keyboard", typing())
    }

    @Test
    fun okPutsTheFocusInTheField() {
        compose.setTvContent { Form() }
        focusOn(gate("Host"))
        assertFalse(typing())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(field("Host") and isFocused()) }
    }

    /** The keyboard's Next goes on from field to field, the last one's action is Done, and Done puts the focus back on its gate. */
    @Test
    fun theKeyboardsNextGoesToTheNextFieldAndDoneBackToTheGate() {
        compose.setTvContent { Form() }
        assertEquals(ImeAction.Next, compose.onNode(field("Host")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction))
        assertEquals(ImeAction.Next, compose.onNode(field("Port")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction))
        assertEquals("the last field's", ImeAction.Done, compose.onNode(field("Password")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction))
        focusOn(gate("Host"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(field("Host") and isFocused()) }
        compose.onNode(field("Host")).performImeAction()
        compose.waitUntil(5_000) { exists(field("Port") and isFocused()) }
        compose.onNode(field("Port")).performImeAction()
        compose.waitUntil(5_000) { exists(field("Password") and isFocused()) }
        compose.onNode(field("Password")).performImeAction()
        compose.waitUntil(5_000) { remote.isFocused(gate("Password")) }
        assertFalse("Done left a field focused, and its keyboard", typing())
    }

    /** Back with the field open: its gate again (the keyboard, when it shows, takes Back to go, and its going does that). */
    @Test
    fun backFromTheFieldPutsTheFocusBackOnItsGate() {
        compose.setTvContent { Form() }
        focusOn(gate("Port"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(field("Port") and isFocused()) }
        // The keyboard, if it comes, up before Back.
        Thread.sleep(1_000)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        try {
            compose.waitUntil(5_000) { remote.isFocused(gate("Port")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Back from the field: focus on \"${remote.focused()}\", typing ${typing()}", e)
        }
        assertFalse(typing())
        assertFalse("the activity went", compose.activity.isFinishing)
    }

    /**
     * Show sits inside the field, whose keys a keyboard's arrows would move the cursor with: the remote's arrows
     * ([sendRemoteKey]) move the focus.
     */
    @Test
    fun aSecretFieldsShowIsRightOfItsGate() {
        compose.setTvContent { Form() }
        focusOn(gate("Password"))
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        assertEquals("Right from Password's gate", "Show", remote.focused())
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        assertTrue("Left from Show: Password's gate; focus on ${remote.focused()}", remote.isFocused(gate("Password")))
        assertFalse(typing())
    }

    /** What TalkBack hears on [label]'s gate besides its name and role. */
    private fun gateSays(label: String): String? = compose.onNode(gate(label)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /**
     * TalkBack on a gate, which takes the focus in its field's place, hears the words in the field, as it would on the
     * field; an empty one only its name, and a secret one that there are words, not what they are.
     */
    @Test
    fun aGateSaysTheWordsInItsField() {
        compose.setTvContent { Form() }
        assertEquals("Port's gate", "563", gateSays("Port"))
        assertEquals("Host's gate, its field empty", null, gateSays("Host"))
        compose.onNode(field("Host")).performTextReplacement("news.example.org")
        compose.onNode(field("Password")).performTextReplacement("tide-lantern")
        compose.waitForIdle()
        assertEquals("Host's gate", "news.example.org", gateSays("Host"))
        val secret = gateSays("Password")
        assertTrue("Password's gate says nothing of its words, or what they are: $secret", secret != null && "tide-lantern" !in secret)
    }

    @Test
    fun aPhonesFieldsHaveNoGates() {
        compose.setContent { AppTheme(FormFactor.Phone) { Form() } }
        assertFalse("a phone's field has a gate", exists(SemanticsMatcher.keyIsDefined(FieldGateLabel)))
        focusOn(hasText("Test") and hasClickAction())
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Test: Host's field itself; focus on ${remote.focused()}", typing())
    }
}

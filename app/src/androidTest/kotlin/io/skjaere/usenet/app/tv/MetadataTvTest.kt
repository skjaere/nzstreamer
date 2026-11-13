package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.MetadataScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Metadata on a TV (forced on the phone emulator): "How to get a key ›" shows the
 * address to visit elsewhere, as words a remote passes over. Selecting text is no use with a remote,
 * and a selectable line would be a stop for the focus that shows nothing.
 */
@RunWith(AndroidJUnit4::class)
class MetadataTvTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)

    @Before
    fun show() {
        instrumentation.setInTouchMode(false)
        compose.setTvContent { MetadataScreen(onBack = {}) }
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @Test
    fun theAddressShownForTheKeyIsNoStopForTheFocus() {
        compose.onNode(hasText("How to get a key ›") and hasClickAction()).requestFocus()
        compose.waitForIdle()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("This device can't open web links", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val next = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from the link: the next setting, not the address; focus on \"$next\"", next.startsWith("Use device language"))
    }
}

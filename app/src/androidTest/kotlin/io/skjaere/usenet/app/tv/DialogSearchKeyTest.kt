package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.ConfirmDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The remote's search key in a dialog on a TV (forced on the phone emulator): a window of its own,
 * whose keys the activity doesn't see. The key closes it and opens Search ([LocalSearchKey], the
 * shell's), rather than going to the TV's system.
 */
@RunWith(AndroidJUnit4::class)
class DialogSearchKeyTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val searches = AtomicInteger()
    private val dismissed = AtomicInteger()
    private var shown by mutableStateOf(true)

    @Before
    fun show() {
        instrumentation.setInTouchMode(false)
        compose.setTvContent {
            CompositionLocalProvider(LocalSearchKey provides { searches.incrementAndGet(); Unit }) {
                if (shown) {
                    ConfirmDialog("Clear the cache?", "The invented files go.", "Clear", onConfirm = {}, onDismiss = { dismissed.incrementAndGet(); shown = false })
                }
            }
        }
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @Test
    fun theSearchKeyInADialogClosesItAndOpensSearch() {
        compose.onNode(hasText("Cancel") and hasClickAction()).requestFocus()
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SEARCH)
        compose.waitForIdle()
        assertEquals("searches opened", 1, searches.get())
        assertEquals("dialog closed", 1, dismissed.get())
    }
}

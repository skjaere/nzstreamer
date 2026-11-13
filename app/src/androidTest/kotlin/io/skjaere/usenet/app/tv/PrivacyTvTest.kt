package io.skjaere.usenet.app.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.settings.GoogleCastPage
import io.skjaere.usenet.app.settings.HUB_FOOTNOTE
import io.skjaere.usenet.app.settings.PRIVACY_ONLINE
import io.skjaere.usenet.app.settings.PRIVACY_SEEDED
import io.skjaere.usenet.app.settings.PRIVACY_TITLE
import io.skjaere.usenet.app.settings.PrivacyPart
import io.skjaere.usenet.app.settings.PrivacyScreen
import io.skjaere.usenet.app.settings.privacyRowLine
import io.skjaere.usenet.app.settings.privacySections
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Privacy on a real TV (tv36; W60, L138): Settings' About has it, saying what leaves this TV, with no Google Cast row
 * anywhere and the footnote under it; OK opens the page, which names no site's page while that isn't live (PRIVACY_ONLINE)
 * and shows its address once it is, and opens nothing (no browser is started, whatever OK is pressed on); the policy takes
 * the remote's focus a paragraph at a time from the first, Down to the last brings each into view, the page scrolling,
 * and Up goes back to the first; Back returns to the Privacy row. The page as it is once the site's page is live is
 * rendered on its own too, whatever PRIVACY_ONLINE says. tv36's Android 16 doesn't move the focus for an
 * instrumentation's arrow keys, so the focus is moved as they would, by the screen's FocusManager; OK and Back are keys.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PrivacyTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var nav: NavHostController
    private lateinit var focusManager: FocusManager

    /** Stands in for a browser: every activity a web link would start, kept, none started. */
    private val browser = object : Instrumentation.ActivityMonitor() {
        val views = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
            if (intent.action == Intent.ACTION_VIEW) {
                views += intent
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                null
            }
    }

    @Before
    fun tv() {
        assumeTv()
        home.show()
        instrumentation.addMonitor(browser)
        instrumentation.setInTouchMode(false)
    }

    /** [content] on the screen in the TV's theme, the focus moved by its FocusManager. */
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme(FormFactor.Tv) {
                focusManager = LocalFocusManager.current
                content()
            }
        }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        instrumentation.removeMonitor(browser)
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun down() = move(FocusDirection.Down)

    private fun up() = move(FocusDirection.Up)

    private fun move(direction: FocusDirection) {
        compose.runOnIdle { focusManager.moveFocus(direction) }
        compose.waitForIdle()
    }

    private val part = SemanticsMatcher.keyIsDefined(PrivacyPart)

    /** The paragraph of the policy the focus is on, by its place on the page; null for none. */
    private fun focusedPart(): Int? = compose.onAllNodes(part and isFocused()).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(PrivacyPart)

    /** Where paragraph [index] is on the screen now: its top, in pixels. */
    private fun top(index: Int): Float = compose.onNode(SemanticsMatcher.expectValue(PrivacyPart, index)).fetchSemanticsNode().positionInRoot.y

    private val address = "Read it at nzstreamer.app/privacy"

    /**
     * The page's link while the site's page is [online] or not: the address alone, which opens nothing (TV-WB), with
     * nothing to read it online with; or nothing naming the site's page at all.
     */
    private fun assertTheLink(online: Boolean) {
        if (online) {
            compose.waitUntil(5_000) { exists(hasText(address)) }
            val shown = compose.onNode(hasText(address), useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("the address opens nothing", shown.config.getOrNull(SemanticsActions.OnClick) == null)
            assertFalse("nothing to read it online with", exists(hasText("Read it online")))
        } else {
            listOf("Read it at", "Read it online", "nzstreamer.app/privacy").forEach { words ->
                assertFalse("\"$words\" on the page", exists(hasText(words, substring = true)))
            }
        }
    }

    @Test
    fun settingsPrivacyOpensNothingAndScrollsAParagraphAtATime() {
        show {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus(timeoutMs = 10_000)
        compose.onNode(railItem("Settings")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Usenet providers")
        // Down the hub to Privacy, About's row, the last.
        repeat(12) { if (!remote.focused().startsWith(PRIVACY_TITLE)) down() }
        assertTrue("never reached Privacy; at ${remote.focused()}", remote.focused().startsWith(PRIVACY_TITLE))
        assertTrue(remote.focused(), privacyRowLine(television = true) in remote.focused())
        assertTrue(exists(hasText("About".uppercase())))
        assertTrue(exists(hasText(HUB_FOOTNOTE)))
        assertFalse("no Google Cast row on a TV", exists(hasText(GoogleCastPage.TITLE)))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.waitUntil(5_000) { exists(part) }
        assertTheLink(PRIVACY_ONLINE)
        try {
            compose.waitUntil(5_000) { focusedPart() == 0 }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the page never took the focus on its first part; it is on ${remote.focused()}", e)
        }
        // This debug build came set up with someone's accounts: its paragraph first, then the policy's, a part each.
        val parts = 1 + privacySections(instrumentation.targetContext.assets.open("privacy.txt").bufferedReader().use { it.readText() }).sumOf { it.paragraphs.size }
        assertTrue(exists(hasText(PRIVACY_SEEDED)))
        val lastAtFirst = top(parts - 1)
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().size.height
        assertTrue("the last part starts below the screen at first", lastAtFirst > screen)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        (1 until parts).forEach { index ->
            down()
            compose.waitUntil(5_000) { focusedPart() == index }
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        // The last keeps it, now on the screen: the page has scrolled.
        down()
        assertEquals(parts - 1, focusedPart())
        assertTrue("the last part on the screen: at ${top(parts - 1)} of $screen", top(parts - 1) < screen)
        assertTrue("the page scrolled", top(parts - 1) < lastAtFirst)
        assertTrue("OK on the page opened something: ${browser.views}", browser.views.isEmpty())
        // Up goes back a paragraph at a time, a tall one paged through, to the first, at the top of the page again.
        var presses = 0
        while (focusedPart() != 0 && presses++ < parts * 4) {
            val from = focusedPart()
            up()
            val to = focusedPart()
            assertTrue("Up from part $from went to $to", from != null && to != null && to <= from)
        }
        assertEquals("Up back to the first part", 0, focusedPart())
        assertTrue("the first part on the screen again: at ${top(0)}", top(0) >= 0f)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(PRIVACY_TITLE)
        assertTrue(remote.focused(), remote.focused().startsWith(PRIVACY_TITLE))
    }

    /**
     * L138, TV-WB: once the site's page is live, the address over the policy, for the user's phone or computer. It takes
     * no focus: the first is still the policy's first paragraph, Down and Up go between the paragraphs past it, and OK
     * opens nothing.
     */
    @Test
    fun onceTheSitesPageIsLiveTheAddressShowsAndOpensNothing() {
        show { PrivacyScreen(onBack = {}, online = true) }
        compose.waitUntil(10_000) { exists(part) }
        assertTheLink(online = true)
        try {
            compose.waitUntil(5_000) { focusedPart() == 0 }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the page never took the focus on its first part; it is on ${remote.focused()}", e)
        }
        val shown = compose.onNode(hasText(address), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the address over the policy", shown.bottom <= top(0))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        down()
        compose.waitUntil(5_000) { focusedPart() == 1 }
        up()
        compose.waitUntil(5_000) { focusedPart() == 0 }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK on the page opened something: ${browser.views}", browser.views.isEmpty())
    }
}

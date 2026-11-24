package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.plugins.InstallState
import io.skjaere.usenet.app.plugins.PluginIndex
import io.skjaere.usenet.app.plugins.PluginListState
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.Refusal
import io.skjaere.usenet.app.plugins.PluginsPageFakes
import io.skjaere.usenet.app.plugins.PluginsPageFakes.Companion.installed
import io.skjaere.usenet.app.plugins.PluginsPageFakes.Companion.listed
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.settings.PluginsScreen
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.plugin.PluginApi
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Plugins on a real TV (tv36, W157, L620, L633), with the remote. The hub row in App, reached and opened by
 * remote, and Back from the page on it again. Then the page's state table, over states the test sets in place of the
 * app's registry, list and installer ([PluginsPageFakes]), with a focused element asserted after every change: Install
 * to Cancel while it downloads and is checked, to Open <label> once installed; a failure's Try again; Install again for a
 * plugin refused at start; Remove alone for a withdrawn one; Check again where no card has a button; Turn on again above
 * the cards while plugins are off; two installed, a third's greyed Install passed over; Checking… keeping the focus while
 * the list is read; no list; offline; and Remove's question, Cancel focused, then the card's Install once removed.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PluginsTvTest {
    @get:Rule(order = 0)
    val plugins = PluginStateRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private val fakes = PluginsPageFakes(television = true)
    private lateinit var nav: NavHostController
    private val opened = mutableListOf<String>()

    @Serializable
    private data object PluginsPageRoute

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        fakes.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The page over the fakes, as Settings opens it on a TV: no rail, the TV's column; a card's Settings and Open there. */
    private fun showThePage() {
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {}
                    composable<PluginsPageRoute> {
                        PluginsScreen(
                            focus = null, onBack = { nav.popBackStack() }, onSettings = { slot -> opened += "settings $slot" },
                            onOpen = { slot -> opened += "open $slot" }, deps = fakes.deps,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The page opened afresh, as from Settings: its first focus where [first] says. */
    private fun open(first: String) {
        compose.runOnIdle {
            nav.popBackStack(HomeRoute, inclusive = false)
            nav.navigate(PluginsPageRoute)
        }
        compose.waitForIdle()
        remote.waitForFocus(first)
        assertEquals("first focus", first, remote.focused())
    }

    /** [change] made, as the installer or the registry would; then the focus on [focused]. */
    private fun then(focused: String, change: () -> Unit) {
        compose.runOnIdle(change)
        compose.waitForIdle()
        remote.waitForFocus(focused)
        assertEquals(focused, remote.focused())
    }

    private fun press(key: Int, focused: String) {
        val now = remote.press(key)
        if (now != focused) remote.waitForFocus(focused)
        assertEquals("after ${KeyEvent.keyCodeToString(key)}", focused, remote.focused())
    }

    /** The hub row in App, by remote: its line, OK to the page (GitHub answering nothing here), and Back on the row. */
    @Test
    fun theHubRowByRemoteOpensThePageAndBackComesBackToIt() {
        home.show()
        try {
            compose.setTvContent {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
            remote.waitForHomeFocus()
            val rail = tabsFor(FormFactor.Tv).map { it.label }
            remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            repeat(rail.indexOf("Settings") - rail.indexOf("Home")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus("Usenet providers")
            val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Plugins")
            assertEquals("Plugins | None yet", row)
            // Logs is right under it, in App.
            assertEquals("Logs", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
            press(KeyEvent.KEYCODE_DPAD_UP, "Plugins | None yet")

            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(PAGE_MS) { exists(hasText(PluginsPage.INTRO)) }
            // The device tests' list answers nothing: no copy, and Check again has the focus, its words back once it read.
            compose.waitUntil(PAGE_MS) { exists(hasText(PluginsPage.NO_COPY)) }
            remote.waitForFocus(PluginsPage.CHECK_AGAIN)
            assertEquals(PluginsPage.CHECK_AGAIN, remote.focused())

            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus("Plugins | None yet")
        } finally {
            home.restore()
        }
    }

    @Test
    fun installHandsTheFocusFromInstallToCancelToOpen() {
        fakes.listing(listed("kestrel", "Kestrel"))
        showThePage()
        open("Install")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "install kestrel" in fakes.asked }
        then("Cancel") { fakes.installs.value = mapOf("kestrel" to InstallState.Downloading(30)) }
        then("Cancel") { fakes.installs.value = mapOf("kestrel" to InstallState.Downloading(70)) }
        then("Cancel") { fakes.installs.value = mapOf("kestrel" to InstallState.Checking) }
        then("Open Sports") {
            fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports"))
            fakes.installs.value = mapOf("kestrel" to InstallState.Installed(1))
        }
        assertTrue(exists(hasText("Installed. Sports is now a tab after ${pluginTabsFollow(FormFactor.Tv)}.")))
        // Left and Right along the card's buttons, Down to Check again.
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Settings")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove")
        press(KeyEvent.KEYCODE_DPAD_LEFT, "Settings")
        press(KeyEvent.KEYCODE_DPAD_DOWN, PluginsPage.CHECK_AGAIN)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("back on the card: ${remote.focused()}", remote.focused() in setOf("Open Sports", "Settings", "Remove"))
        assertEquals("Open Sports", remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, "Open Sports", max = 3))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "open 1" in opened }
    }

    @Test
    fun aFailureFocusesTryAgainAndACancelGoesBackToInstall() {
        fakes.listing(listed("kestrel", "Kestrel"))
        showThePage()
        open("Install")
        then("Cancel") { fakes.installs.value = mapOf("kestrel" to InstallState.Downloading(10)) }
        then("Try again") { fakes.installs.value = mapOf("kestrel" to InstallState.Failed("It isn't signed by NZStreamer, so nothing was installed.")) }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "try again kestrel" in fakes.asked }
        then("Cancel") { fakes.installs.value = mapOf("kestrel" to InstallState.Downloading(0)) }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "cancel kestrel" in fakes.asked }
        then("Install") { fakes.installs.value = emptyMap() }
    }

    /** Each state's first focus as the page opens, and where the remote goes from it. */
    @Test
    fun eachStatesFirstFocus() {
        showThePage()
        val kestrel = listed("kestrel", "Kestrel")

        // Refused at start: Install again where the list has its version, else Remove alone.
        fakes.listing(kestrel)
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports", status = PluginStatus.Refused(Refusal.Hash)))
        open("Install again")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove")
        press(KeyEvent.KEYCODE_DPAD_LEFT, "Install again")
        // The list loses its version: Install again goes, the focus to the card's next button.
        then("Remove") {
            fakes.installAgain = false
            fakes.listing(kestrel, readAt = PluginsPageFakes.NOW - 30_000)
        }
        fakes.installAgain = true

        // Withdrawn: Remove alone, then Check again under it.
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports", status = PluginStatus.Withdrawn, withdrawn = true))
        open("Remove")
        press(KeyEvent.KEYCODE_DPAD_DOWN, PluginsPage.CHECK_AGAIN)

        // Turned off after a crash: Turn on again, which loads it at once; then its Settings.
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports", status = PluginStatus.OffAfterCrash))
        open("Turn on again")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "turn on again kestrel" in fakes.asked }
        then("Settings") { fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports")) }

        // A newer NZStreamer needed, nothing installed: no button on the card, so Check again.
        fakes.installed.value = emptyList()
        fakes.listing(listed("kestrel", "Kestrel", minor = PluginApi.MINOR + 1))
        open(PluginsPage.CHECK_AGAIN)
        assertTrue(exists(hasText("Needs a newer NZStreamer. Update NZStreamer on this TV to install it.")))

        // Plugins off after two early crashes: Turn on again above the cards, then the first card's first button.
        fakes.listing(kestrel)
        fakes.pluginsOff.value = true
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports", status = PluginStatus.PluginsOff))
        open("Turn on again")
        assertTrue(exists(hasText(PluginsPage.PLUGINS_OFF)))
        press(KeyEvent.KEYCODE_DPAD_DOWN, "Remove")
        press(KeyEvent.KEYCODE_DPAD_UP, "Turn on again")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "turn plugins on again" in fakes.asked }
        then("Settings") {
            fakes.pluginsOff.value = false
            fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports"))
        }

        // No list yet (a 404): Check again.
        fakes.installed.value = emptyList()
        fakes.list.value = PluginListState(PluginIndex.EMPTY, readAt = PluginsPageFakes.NOW)
        open(PluginsPage.CHECK_AGAIN)
        assertTrue(exists(hasText(PluginsPage.NONE_LISTED)))

        // Offline: the list kept, its card's Install; with no copy, Check again.
        fakes.listing(kestrel, failed = true)
        open("Install")
        assertTrue(exists(hasText("Couldn't reach GitHub. This list is from today.")))
        fakes.list.value = PluginListState(failed = true)
        open(PluginsPage.CHECK_AGAIN)
        assertTrue(exists(hasText(PluginsPage.NO_COPY)))
    }

    /** Two installed, a third listed: its Install greyed takes no focus, Down passing it to Check again. */
    @Test
    fun aThirdPluginsGreyedInstallIsPassedOver() {
        showThePage()
        fakes.listing(listed("kestrel", "Kestrel"), listed("harbor", "Harbor Lights"), listed("crown", "Copper Crown"))
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports"), installed("harbor", "Harbor Lights", "Talks", slot = 2))
        open("Settings")
        assertTrue(exists(hasText(PluginsPage.TWO_INSTALLED)))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove")
        press(KeyEvent.KEYCODE_DPAD_DOWN, "Remove")
        press(KeyEvent.KEYCODE_DPAD_LEFT, "Settings")
        press(KeyEvent.KEYCODE_DPAD_DOWN, PluginsPage.CHECK_AGAIN)
    }

    /** The first open with no copy: Checking… has the focus, and hands it to the list's Install once read. */
    @Test
    fun checkingKeepsTheFocusWhileTheListIsRead() {
        showThePage()
        fakes.list.value = PluginListState(checking = true)
        open(PluginsPage.CHECKING)
        assertTrue(exists(hasText(PluginsPage.READING)))
        then("Install") { fakes.listing(listed("kestrel", "Kestrel")) }
        // Pressed by the user, Check again keeps the focus while it reads, and after.
        press(KeyEvent.KEYCODE_DPAD_DOWN, PluginsPage.CHECK_AGAIN)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "check again" in fakes.asked }
        then(PluginsPage.CHECKING) { fakes.listing(listed("kestrel", "Kestrel"), checking = true) }
        then(PluginsPage.CHECK_AGAIN) { fakes.listing(listed("kestrel", "Kestrel"), readAt = PluginsPageFakes.NOW) }
    }

    /** Remove asks, Cancel focused; two OKs in a row never remove; Remove, then the card's Install has the focus. */
    @Test
    fun removeAsksWithCancelFocusedThenTheCardsInstallHasTheFocus() {
        showThePage()
        fakes.listing(listed("kestrel", "Kestrel"))
        fakes.installed.value = listOf(installed("kestrel", "Kestrel", "Sports"))
        open("Settings")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // OK again at once: the question's guard lets it by, or it lands on Cancel. Never Remove.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        if (exists(hasText("Remove Kestrel?"))) {
            remote.waitForFocus("Cancel")
            Thread.sleep(OkGuard.WINDOW_MS)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        compose.waitUntil(PAGE_MS) { !exists(hasText("Remove Kestrel?")) }
        assertFalse("kept", fakes.asked.any { it.startsWith("remove") })
        remote.waitForFocus("Remove")

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { exists(hasText("Remove Kestrel?")) }
        assertTrue(exists(hasText(PluginsPage.removeText("Sports"))))
        remote.waitForFocus("Cancel")
        assertEquals("Cancel first", "Cancel", remote.focused())
        Thread.sleep(OkGuard.WINDOW_MS)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PAGE_MS) { "remove kestrel" in fakes.asked }
        then("Install") { fakes.installed.value = emptyList() }
        press(KeyEvent.KEYCODE_DPAD_DOWN, PluginsPage.CHECK_AGAIN)
    }

    private companion object {
        /** Long enough for a page to read its states and for the app's list to fail against an address that answers nothing. */
        const val PAGE_MS = 10_000L
    }
}

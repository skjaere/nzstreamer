package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeContent
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.TvHomeContent
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A TV's cold start (#234). Android gives the window its first focus with no key pressed, as a move down into it
 * (ViewRootImpl's restoreDefaultFocus: View.requestFocus() goes down), and on a cold start that can come while Home is
 * still loading its settings and shows nothing, when the rail is all there is to take it. That focus is the window's,
 * not the user's: once Home shows, its search field takes the focus as it does when it loads first, and the rail
 * closes. The user's keys are the user's: an arrow from the content, or a move along the rail after that first focus,
 * leaves the focus on the rail. The TV shell as a TV shows it (forced on a phone emulator), with Home's content shown
 * once the test lets it, as Home shows it once its settings are read; and TV Home's (TvHomeContent) the same way, its first
 * focus on its first card, and none while it waits for its rows.
 */
@RunWith(AndroidJUnit4::class)
class TvFirstFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private var loaded by mutableStateOf(false)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    @Test
    fun theWindowsFirstFocusArrivingWhileHomeLoadsLeavesHomesFirstFocusToIt() {
        showTheShell()
        windowsFirstFocus()

        loaded = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search your indexers")).fetchSemanticsNodes().isNotEmpty() }
        remote.waitForFocus("Search your indexers")
        // The user's arrow from there is the user's: the rail keeps the focus, also from a screen's
        // first focus coming after it (Home showing again, as a screen still loading shows late).
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the field went to ${remote.focused()}", remote.isFocused(railItem("Home")))
        loaded = false
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search your indexers")).fetchSemanticsNodes().isEmpty() }
        loaded = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search your indexers")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("Home's first focus took the focus from the rail, to ${remote.focused()}", remote.isFocused(railItem("Home")))
    }

    /** A move along the rail after the window's first focus is the user's: Home's first focus, coming after it, waits. */
    @Test
    fun aMoveAlongTheRailAfterTheWindowsFirstFocusKeepsTheFocusThere() {
        showTheShell()
        windowsFirstFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Home went to ${remote.focused()}", remote.isFocused(railItem(belowHome)))

        loaded = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search your indexers")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("Home's first focus took the focus from the rail, to ${remote.focused()}", remote.isFocused(railItem(belowHome)))
    }

    /** TV Home (TvHomeContent) in place of the phone's: the same cold start, its first focus on its first card. */
    @Test
    fun onTvHomeTheWindowsFirstFocusArrivingWhileHomeLoadsLeavesHomesFirstFocusToIt() {
        showTheShell(tv = true)
        windowsFirstFocus()

        loaded = true
        remote.waitForHomeFocus()
        assertTrue("TV Home's first focus: its first card; it is on ${remote.focused()}", "Harbor Lights" in remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the card went to ${remote.focused()}", remote.isFocused(railItem("Home")))
        loaded = false
        compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isEmpty() }
        loaded = true
        compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("Home's first focus took the focus from the rail, to ${remote.focused()}", remote.isFocused(railItem("Home")))
    }

    @Test
    fun onTvHomeAMoveAlongTheRailAfterTheWindowsFirstFocusKeepsTheFocusThere() {
        showTheShell(tv = true)
        windowsFirstFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Home went to ${remote.focused()}", remote.isFocused(railItem(belowHome)))

        loaded = true
        compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("Home's first focus took the focus from the rail, to ${remote.focused()}", remote.isFocused(railItem(belowHome)))
    }

    /**
     * TV Home shown while the library answers (the outlines of its sections, nothing to focus) when the window's first
     * focus comes: it stays on the rail until the rows come, then goes to the first card.
     */
    @Test
    fun onTvHomeTheFirstFocusWaitsForTheRowsAndTakesTheWindowsFirstFocusFromTheRail() {
        rows = false
        loaded = true
        showTheShell(tv = true)
        compose.waitUntil(5_000) { compose.onAllNodes(tvHome).fetchSemanticsNodes().isNotEmpty() }
        windowsFirstFocus()
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("nothing in Home to focus yet: the rail keeps it; it is on ${remote.focused()}", remote.isFocused(railItem("Home")))
        rows = true
        remote.waitForHomeFocus()
        assertTrue("the first card; focus on ${remote.focused()}", "Harbor Lights" in remote.focused())
    }

    /** One show to go on with, for TV Home. */
    private val harbor = HomeState(
        recent = emptyList(),
        upNext = listOf(
            UpNextRow(
                "Harbor Lights", "S02E05 · 30 min left", 0.33f, false, null,
                UpNextPlay.Resume(WatchState(WatchKey.episode(900401, 2, 5), WatchKind.Episode, showTmdbId = 900401, season = 2, episode = 5, title = "Harbor Lights S02E05", positionMs = 900_000, durationMs = 2_700_000, updatedAt = 0)),
                "Resume Harbor Lights S02E05", cardLine = "S02E05 · 30 min left",
            ),
        ),
        collection = null, hasTmdbKey = true,
    )

    /** Whether TV Home's rows are in (else it shows the outlines it shows while the library answers). */
    private var rows by mutableStateOf(true)

    /** The TV shell on Home, whose content shows once [loaded]: the phone's, or a TV's ([tv]). */
    private fun showTheShell(tv: Boolean = false) {
        compose.setTvContent {
            val nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), compose.activity.container.playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        if (loaded && tv) {
                            TvHomeContent(
                                state = if (rows) harbor else null, artwork = false, onSearch = {}, onRecent = {}, onPlay = {}, onShow = {}, onMovie = {},
                                onCollection = {}, onMetadata = {}, onImport = {},
                            )
                        } else if (loaded) {
                            HomeContent(
                                state = null, artwork = false, onSearch = {}, onSettings = {}, onRecent = {}, onPlay = {}, onShow = {},
                                onCollection = {}, onMetadata = {}, onImport = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(railItem("Home")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The rail's item below Home's, as the app's tabs have it (Discover in debug builds, else Collection). */
    private val belowHome = tabsFor(FormFactor.Tv).map { it.label }.let { it[it.indexOf("Home") + 1] }

    /** The window's first focus, with no key pressed, while Home shows nothing: the rail takes it. */
    private fun windowsFirstFocus() {
        compose.runOnUiThread { compose.activity.window.decorView.restoreDefaultFocus() }
        compose.waitForIdle()
        assertTrue("the window's first focus went to ${remote.focused()}, not the rail", remote.isFocused(railItem("Home")))
    }
}

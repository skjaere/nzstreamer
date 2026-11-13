package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.BackToSearch
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.CollectionJumpState
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.collection.CollectionHarness
import io.skjaere.usenet.app.collection.CollectionScreen
import io.skjaere.usenet.app.collection.CollectionViewModel
import io.skjaere.usenet.app.collection.TvCollectionKey
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.jumpToCollection
import io.skjaere.usenet.app.rememberCollectionJumps
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back on a TV in the Collection tab, over a library of its own (20 shows): what the screen handles
 * itself (the letter grid, the search field, select mode, an empty search left open) and an armed
 * Back to Search each go first; only then does Back take the focus to the rail. From the grid's first
 * screen it goes there at once: the phone's step to the search button is the rail's job on a TV. Back
 * to Search after a jump isn't lost while the tab fades in, and the rail entered disarms it.
 */
@RunWith(AndroidJUnit4::class)
class TvBackPrecedenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel
    private lateinit var nav: NavHostController
    private lateinit var jumps: CollectionJumpState

    @Before
    fun showTheCollectionTab() {
        instrumentation.setInTouchMode(false)
        val h = CollectionHarness(instrumentation.targetContext, shows = 20, movies = 5).also { harness = it; it.seed() }
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            nav = rememberNavController()
            jumps = rememberCollectionJumps(nav)
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                // The app's graph's order in a tab's destination: TopLevelBack first, Back to Search, the screen.
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text("Home stand-in") }
                    }
                    composable<SearchRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text("Search stand-in") }
                    }
                    composable<SettingsRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text("Settings stand-in") }
                    }
                    composable<CollectionRoute> { screen ->
                        TopLevelBack(nav, screen)
                        BackToSearch(nav, jumps, screen)
                        vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                        CollectionScreen(
                            onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm,
                            jump = jumps.jump, onJumpDone = jumps::jumpDone, backToSearch = jumps.backToSearch,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        harness?.close()
    }

    private fun openCollection() {
        compose.runOnIdle { nav.switchTab(CollectionRoute) }
        compose.waitUntil(10_000) { runCatching { vm.ui.value?.view?.rows?.isNotEmpty() == true }.getOrDefault(false) }
        remote.waitForFocus(SHOWS)
    }

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    private fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val fieldFocused get() = exists(hasSetTextAction() and isFocused())

    private fun onTheRail() = remote.isFocused(railItem("Collection"))

    /** The fourth poster of the first line: Down from the switch to the line, then along it. */
    @Test
    fun fromTheGridsFirstScreenBackGoesStraightToTheRail() {
        openCollection()
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, rows()[0].a11y, max = 4)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, rows()[3].a11y, max = 3)
        assertEquals(rows()[3].a11y, remote.focused())
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun theLetterGridGoesFirst() {
        openCollection()
        compose.onNode(hasText("Jump to letter") and hasClickAction()).requestFocus()
        remote.waitForFocus("Jump to letter")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("A, 1 show")
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !exists(hasText("A, 1 show", substring = true)) }
        assertFalse("the grid's Back went on to the rail", onTheRail())
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun theSearchFieldGoesFirstThenTheEmptySearchClosesThenTheRail() {
        openCollection()
        // Find, above the switch, opens the search, with the field to type in: one press.
        compose.onNode(hasText("Find") and hasClickAction()).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fieldFocused }
        // Off the field, to its gate. (A keyboard on screen takes the first Back itself, to close.)
        remote.press(KeyEvent.KEYCODE_BACK)
        if (fieldFocused) remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(GATE)
        assertTrue(vm.ui.value!!.searchOpen)
        // The empty search left open closes, the focus on Find, where it was opened.
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.searchOpen == false }
        remote.waitForFocus("Find in 20 shows")
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun selectModeGoesFirst() {
        openCollection()
        compose.onNode(hasText("Select") and hasClickAction()).requestFocus()
        remote.waitForFocus("Select")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(rows()[0].a11y)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, vm.ui.value!!.selection?.count)
        // From the list in select mode, to its actions, as on a phone; then out of select mode, the
        // focus on Select, where select mode was entered (the actions go with it).
        assertEquals("Mark watched", remote.press(KeyEvent.KEYCODE_BACK))
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        remote.waitForFocus("Select")
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun anArmedBackToSearchGoesFirst() {
        compose.runOnIdle { nav.switchTab(SearchRoute()) }
        compose.waitUntil(5_000) { exists(hasText("Search stand-in")) }
        // "In collection ›" on Search for a show there already.
        val show = TitleKey.show(harness!!.fixture.showId(3))
        compose.runOnIdle { nav.jumpToCollection(jumps, CollectionJump(show.value, "A show of the fixture's", added = false)) }
        compose.waitUntil(10_000) { runCatching { vm.ui.value?.view?.rows?.isNotEmpty() == true }.getOrDefault(false) }
        compose.waitUntil(5_000) { jumps.jump == null }
        // Back is the tab's once its screen is up (no longer fading in).
        compose.waitUntil(5_000) { nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        assertTrue(jumps.backToSearch)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(hasText("Search stand-in")) }
        assertFalse(jumps.backToSearch)
        assertFalse("focus on ${remote.focused()}", onTheRail())
    }

    private fun jumpFromSearch() {
        compose.runOnIdle { nav.switchTab(SearchRoute()) }
        compose.waitUntil(5_000) { exists(hasText("Search stand-in")) }
        val show = TitleKey.show(harness!!.fixture.showId(3))
        compose.runOnIdle { nav.jumpToCollection(jumps, CollectionJump(show.value, "A show of the fixture's", added = false)) }
    }

    /**
     * A Back pressed as the tab fades in (its 700 ms) is Back to Search's, not taken and dropped (TVX-6's rule). The
     * screen's clock goes a frame at a time from the jump on, so the fade is still going when Back is pressed: from the
     * frame the tab's screen is first composed in. Every finder waits for the screen to be idle, which with the clock
     * running would run the fade to its end first.
     */
    @Test
    fun aBackAsTheTabFadesInAfterAJumpStillReturnsToSearch() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.runOnIdle { nav.addOnDestinationChangedListener { _, d, _ -> seen += d.route.orEmpty().substringAfterLast('.') } }
        compose.runOnIdle { nav.switchTab(SearchRoute()) }
        compose.waitUntil(5_000) { exists(hasText("Search stand-in")) }
        val show = TitleKey.show(harness!!.fixture.showId(3))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.jumpToCollection(jumps, CollectionJump(show.value, "A show of the fixture's", added = false)) }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!(onCollection() && exists(SemanticsMatcher.keyIsDefined(TvCollectionKey)))) {
            assertTrue("the Collection never showed; went $seen", SystemClock.elapsedRealtime() < deadline)
            compose.mainClock.advanceTimeByFrame()
        }
        val state = nav.currentBackStackEntry?.lifecycle?.currentState
        assertTrue("still fading in: $state", state != Lifecycle.State.RESUMED)
        val armed = jumps.backToSearch
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.mainClock.autoAdvance = true
        try {
            compose.waitUntil(5_000) { exists(hasText("Search stand-in")) && !exists(hasText(SHOWS)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Back as the tab faded in ($state, armed $armed): on ${nav.currentDestination?.route}, armed ${jumps.backToSearch}, focus on ${remote.focused()}; went $seen", e)
        }
        assertFalse(jumps.backToSearch)
    }

    private fun onCollection(): Boolean = runCatching { nav.currentBackStackEntry?.destination?.hasRoute(CollectionRoute::class) == true }.getOrDefault(false)

    /** After a jump, the user's move to the rail disarms Back to Search: Back from the rail goes Home, as it always does. */
    @Test
    fun theRailEnteredAfterAJumpDisarmsBackToSearch() {
        jumpFromSearch()
        compose.waitUntil(10_000) { runCatching { vm.ui.value?.view?.rows?.isNotEmpty() == true }.getOrDefault(false) }
        compose.waitUntil(5_000) { jumps.jump == null }
        compose.waitUntil(5_000) { nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        repeat(GRID_COLUMNS) { if (!onTheRail()) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("focus on ${remote.focused()}", onTheRail())
        assertFalse(jumps.backToSearch)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(hasText("Home stand-in")) }
    }

    private companion object {
        const val SHOWS = "Shows 20"
        const val GATE = "Search shows"
        const val GRID_COLUMNS = 7
    }
}

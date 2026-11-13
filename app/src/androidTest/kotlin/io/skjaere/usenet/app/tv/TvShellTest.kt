package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.VoiceSearch
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.WatchKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * The app in the TV shell, as a TV shows it (forced on the phone emulator, 960 x 540 dp), with a
 * remote: the rail, entered at the current tab, that only OK switches tabs from; the focus back in
 * the content where it was; the rail gone on pages; Settings a tab, and no back arrows; the mini bar
 * reachable; everything inside the TV's safe area. Back, press by press: from the content to the
 * rail, from the rail to Home, from Home's rail item out of the app (to the TV's home screen).
 */
@RunWith(AndroidJUnit4::class)
class TvShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var nav: NavHostController
    /** Back that nothing in the app took: the activity would finish, to the TV's home screen. */
    private val leaves = AtomicInteger()
    /** The remote's search key, as MainActivity hands it over. */
    private val searchKeys = Channel<Unit>(Channel.CONFLATED)
    private val realResolves = VoiceSearch.resolves

    @Before
    fun showTheApp() {
        home.show()
        instrumentation.setInTouchMode(false)
        // The search key asks Search for voice where a recognizer resolves (SearchTvTest has that): none here.
        VoiceSearch.resolves = { false }
        // Added before the app's own, so it is the last asked.
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    leaves.incrementAndGet()
                }
            })
        }
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav, searchKeys = searchKeys.receiveAsFlow())
        }
        // A TV's remote starts in Home's content, whatever its first focus is.
        remote.waitForHomeFocus()
    }

    @After
    fun restore() {
        VoiceSearch.resolves = realResolves
        instrumentation.setInTouchMode(true)
        compose.activity.container.playback.dismiss()
        home.restore()
    }

    private fun railFocused(label: String) = remote.isFocused(railItem(label))

    /** The rail's items as the app shows them, read from its tabs (Discover's too in debug builds). */
    private val rail = tabsFor(FormFactor.Tv).map { it.label }

    /** How many Downs from Home's item to [label]'s. */
    private fun downsTo(label: String) = rail.indexOf(label) - rail.indexOf("Home")

    @Test
    fun theRailIsEnteredAtTheCurrentTabStopsAtItsEndsAndTheFocusAloneSwitchesNothing() {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("rail entered at Home; focus on ${remote.focused()}", railFocused("Home"))
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(railFocused("Search"))
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up stops at the top; focus on ${remote.focused()}", railFocused("Search"))
        // Every item below Search in turn, then Settings again: Down stops at the bottom.
        (rail.drop(1) + rail.last()).forEach { label ->
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("Down to $label; focus on ${remote.focused()}", railFocused(label))
        }
        // Still Home's content: the focus alone never opens a tab.
        compose.onNode(tvHome).assertExists()
    }

    @Test
    fun okOnAnItemOpensItsTabWithTheFocusInIt() {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(downsTo("Collection")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(railFocused("Collection"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The Collection's own first focus, in its content (whatever it is, as earlier tests left the library), not the rail.
        remote.waitForCollectionFocus()
        assertTrue("focus on ${remote.focused()}", !railFocused("Collection"))
        assertTrue(compose.runOnIdle { nav.currentDestination?.hasRoute(CollectionRoute::class) == true })
    }

    @Test
    fun rightFromTheRailReturnsToTheElementLeft() {
        val bottom = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Open a .nzb file", max = 30)
        assertTrue("never reached the last element; at $bottom", "Open a .nzb file" in bottom)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("rail entered at Home, not the item level with the button; focus on ${remote.focused()}", railFocused("Home"))
        assertEquals("Open a .nzb file", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun settingsIsATabWithoutABackArrowAndPagesHideTheRail() {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(downsTo("Settings")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(railFocused("Settings"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Usenet providers")
        compose.onNode(hasContentDescription("Back")).assertDoesNotExist()
        compose.onNode(railItem("Settings")).assertExists()

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodes(railItem("Home")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasContentDescription("Back")).assertDoesNotExist()
        assertFalse(compose.onAllNodes(railItem("Settings")).fetchSemanticsNodes().isNotEmpty())
    }

    /** The mini bar, as OK opened the sheet from it, has the focus back once the sheet is hidden. */
    @Test
    fun theMiniBarHasTheFocusBackWhenItsSheetIsHidden() {
        val playback = compose.activity.container.playback
        playback.play(PlayRequest("Mini bar check", null, emptyList(), watch = WatchTarget(WatchKind.Release, "Mini bar check")))
        compose.waitUntil(5_000) { playback.state.value is PlayState.Failed }
        playback.minimize()
        compose.waitForIdle()
        remote.waitForHomeFocus()
        val reached = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Couldn't play Mini bar check", max = 30)
        assertTrue("mini bar never focused; at $reached", "Couldn't play Mini bar check" in reached)

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { playback.expanded.value }
        remote.waitForFocus("Close")
        // Back hides the sheet.
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !playback.expanded.value }
        remote.waitForFocus("Couldn't play Mini bar check")
    }

    /**
     * A row with the focus looks different from one without: on a TV it turns over, the text's cream
     * filling it (TvFocusLookTest has the colours).
     */
    @Test
    fun theFocusedRowCanBeSeen() {
        openTab("Settings")
        remote.waitForFocus("Usenet providers")
        val row = compose.onNode(hasText("Usenet providers") and hasClickAction())
        val focused = row.captureToImage().toPixelMap()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.waitForFocus("Indexers")
        val change = meanChange(focused, row.captureToImage().toPixelMap())
        assertTrue("the focused row differs from itself unfocused by only %.1f of 255 on average".format(change), change >= 10f)
    }

    /** The average change between two images of the same size, in 0-255, of each pixel's channel that changed most. */
    private fun meanChange(a: PixelMap, b: PixelMap): Float {
        assertEquals("the same size", a.width to a.height, b.width to b.height)
        var sum = 0.0
        for (x in 0 until a.width) for (y in 0 until a.height) {
            val p = a[x, y]
            val q = b[x, y]
            sum += max(abs(p.red - q.red), max(abs(p.green - q.green), abs(p.blue - q.blue))) * 255
        }
        return (sum / (a.width * a.height)).toFloat()
    }

    @Test
    fun theMiniBarIsReachedDownTheContent() {
        val playback = compose.activity.container.playback
        // A start with no releases to try fails at once; minimised, it shows in the mini bar.
        playback.play(PlayRequest("Mini bar check", null, emptyList(), watch = WatchTarget(WatchKind.Release, "Mini bar check")))
        compose.waitUntil(5_000) { playback.state.value is PlayState.Failed }
        playback.minimize()
        compose.waitForIdle()
        remote.waitForHomeFocus()
        val reached = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Couldn't play Mini bar check", max = 30)
        assertTrue("mini bar never focused; at $reached", "Couldn't play Mini bar check" in reached)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * Runs [test] with the fake indexer (10.0.2.2:8089) in place of the saved ones and TMDB's
     * suggestions answered with none; the settings, and the recent searches, are put back after.
     */
    private fun withFakeIndexer(test: () -> Unit) {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        val settings = compose.activity.container.settings
        val library = compose.activity.container.library
        val saved = runBlocking { settings.settings.first() }
        val searchedBefore = runBlocking { library.recentSearches.first().any { it.key == QUERY } }
        val tmdb = FakeTmdb(suggestions = { emptyList() })
        try {
            runBlocking {
                settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
                settings.saveIndexer(FAKE_INDEXER, "Fake (TV shell test)", "http://10.0.2.2:8089", "fake-indexer-key")
            }
            test()
        } finally {
            tmdb.close()
            runBlocking {
                // Its key goes with it (restore only puts back the settings document).
                if (saved.indexers.none { it.id == FAKE_INDEXER } && settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
                settings.restore(saved)
                if (!searchedBefore) library.removeSearch(QUERY)
            }
        }
    }

    /** A release group's row in Search's results. */
    private val resultRow = hasText("release ›", substring = true) or hasText("releases ›", substring = true)

    /**
     * Search keeps its results when its tab is opened again from the rail: the way in goes to them,
     * not to the field above them, whose keyboard would open and turn them into suggestions.
     */
    @Test
    fun searchOpenedAgainFromTheRailKeepsItsResults() = withFakeIndexer {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("on Search; focus on ${remote.focused()}", railFocused("Search"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        // Search indexers, by semantics (touch mode stays off): Enter keeps the preview.
        compose.onNode(hasContentDescription("Search indexers for", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { exists(resultRow) }

        // To Home by the rail, and back. The search sent leaves the focus in the results (#236), from which Back
        // goes to the field's gate, the words kept, and Back again to the rail.
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the results: the field's gate; focus on ${remote.focused()}", exists(fieldGate("Search") and isFocused()))
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("on the rail; focus on ${remote.focused()}", railFocused("Search"))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForHomeFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("on Search again; focus on ${remote.focused()}", railFocused("Search"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !railFocused("Search") && remote.focused() != "(nothing)" }

        assertFalse("the field took the focus; focus on ${remote.focused()}", exists(searchFieldFocused))
        assertTrue("the results were replaced by suggestions", exists(resultRow))

        // The field is still there, left of the results: its gate, with no keyboard (Up to the top of the results, then Left).
        repeat(10) { if (!exists(hasContentDescription("Search indexers for", substring = true) and isFocused())) remote.press(KeyEvent.KEYCODE_DPAD_UP) }
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Up and Left never reached the field's gate; focus on ${remote.focused()}", exists(fieldGate("Search") and isFocused()))
    }

    private fun openTab(label: String, downs: Int = downsTo(label)) {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(downs) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("on $label; focus on ${remote.focused()}", railFocused(label))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun backGoesFromTheContentToTheRailThenHomeThenOutOfTheApp() {
        openTab("Collection")
        // From the tab's first focus, whatever earlier tests left in the library (#143): the switch, an empty side's
        // button, or without a TMDB key the first poster; each on the first screen, where Back goes to the rail. A Down
        // first would go into the posters or not, as the library has them.
        remote.waitForCollectionFocus()
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the content: the rail at Collection; focus on ${remote.focused()}", railFocused("Collection"))
        assertTrue("still the Collection tab", compose.runOnIdle { nav.currentDestination?.hasRoute(CollectionRoute::class) == true })

        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(tvHome) }
        assertTrue("Back from the rail: Home, the focus still on the rail; focus on ${remote.focused()}", railFocused("Home"))
        assertEquals(0, leaves.get())

        remote.press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back on Home's rail item leaves the app", 1, leaves.get())
    }

    /**
     * Back from the rail goes to Home's own screen, as a phone's Back from a tab does, even when Home
     * was left from a page (here Metadata, by the search key): not back to that page, which has no rail.
     */
    @Test
    fun backFromTheRailGoesToHomeNotToThePageHomeWasLeftFrom() {
        compose.runOnUiThread { nav.navigate(MetadataRoute) }
        compose.waitUntil(5_000) { !exists(railItem("Home")) }
        searchKeys.trySend(Unit)
        // The field's gate, with no keyboard to put away first: one Back goes to the rail.
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from Search's field: the rail; focus on ${remote.focused()}", railFocused("Search"))

        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the rail: Home's own screen", compose.runOnIdle { nav.currentDestination?.hasRoute(HomeRoute::class) == true })
        assertTrue("the focus kept on the rail's Home; it is on ${remote.focused()}", railFocused("Home"))
        assertEquals(0, leaves.get())
        remote.press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back on Home's rail item leaves the app", 1, leaves.get())
    }

    @Test
    fun backFromHomesContentGoesToTheRailThenOut() {
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", railFocused("Home"))
        assertEquals(0, leaves.get())
        remote.press(KeyEvent.KEYCODE_BACK)
        assertEquals(1, leaves.get())
    }

    @Test
    fun backFromSettingsGoesToTheRailThenHome() {
        openTab("Settings")
        remote.waitForFocus("Usenet providers")
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", railFocused("Settings"))
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(tvHome) }
        assertTrue("focus on ${remote.focused()}", railFocused("Home"))
        assertEquals(0, leaves.get())
    }

    /** A page (here Settings › Usenet providers) goes back to its tab's screen, as on a phone. */
    @Test
    fun backFromAPageGoesToItsTab() {
        openTab("Settings")
        remote.waitForFocus("Usenet providers")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodes(railItem("Home")).fetchSemanticsNodes().isEmpty() }
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodes(railItem("Home")).fetchSemanticsNodes().isNotEmpty() }
        remote.waitForFocus("Usenet providers")
        assertEquals(0, leaves.get())
    }

    /** Back from a page puts the focus on the row that opened it, not on the tab's first row. */
    @Test
    fun backFromAPageFocusesTheRowThatOpenedIt() {
        openTab("Settings")
        remote.waitForFocus("Usenet providers")
        // Eight rows down on a TV with debrid's two rows shown; more where the shell runs on a phone, whose hub has TVs and
        // Google Cast too.
        val logs = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Logs", max = 12)
        assertTrue("never reached Logs; at $logs", logs.startsWith("Logs"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(railItem("Home")) }
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(railItem("Home")) }
        remote.waitForFocus("Logs")
        assertEquals(0, leaves.get())
    }

    /** A second Back while the tab's screen is still fading in after its page closed counts: to the rail. */
    @Test
    fun aBackWhileThePageFadesOutStillCounts() {
        openTab("Settings")
        remote.waitForFocus("Usenet providers")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(railItem("Home")) }
        compose.mainClock.autoAdvance = false
        try {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // Well inside the screens' 700 ms cross-fade.
            compose.mainClock.advanceTimeBy(300)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue("the second Back went to the rail; focus on ${remote.focused()}", railFocused("Settings"))
        assertEquals(0, leaves.get())
    }

    /**
     * A Back as a tab fades in (here Settings, as Home's gear opens it) is the tab's from the frame its screen is first
     * composed: to its rail item, not to the NavHost's own Back, which pops the tab to Home (#267).
     */
    @Test
    fun aBackAsATabFadesInGoesToItsRailItemNotHome() {
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { nav.switchTab(SettingsRoute) }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!exists(hasText("Usenet providers"))) {
                assertTrue("Settings never showed", SystemClock.elapsedRealtime() < deadline)
                compose.mainClock.advanceTimeByFrame()
            }
            val state = nav.currentBackStackEntry?.lifecycle?.currentState
            assertTrue("still fading in: $state", state != Lifecycle.State.RESUMED)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue(
            "Back as Settings faded in: still Settings, not ${nav.currentDestination?.route}",
            compose.runOnIdle { nav.currentDestination?.hasRoute(SettingsRoute::class) == true },
        )
        assertTrue("the focus on the rail's Settings; it is on ${remote.focused()}", railFocused("Settings"))
        assertEquals(0, leaves.get())
    }

    @Test
    fun backToTheRailThenRightReturnsToTheElementLeft() {
        val bottom = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Open a .nzb file", max = 30)
        assertTrue("never reached the last element; at $bottom", "Open a .nzb file" in bottom)
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", railFocused("Home"))
        assertEquals("Open a .nzb file", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun theRailAndHomeKeepInsideTheSafeArea() {
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("with the rail widened", emptyList<String>(), compose.outsideTheSafeArea())
    }

    private companion object {
        const val FAKE_INDEXER = "tv-shell-test"
        const val QUERY = "harbor lights"
    }
}

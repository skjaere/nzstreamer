package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.JumpFixture
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.search.SearchPaneKey
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.RecentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/**
 * Search on a TV in two panes (forced on the phone emulator, 960 x 540 dp): the field's gate at the
 * top of the left pane, about a third of the width, with the recent searches under it; the hint, TMDB's titles and the
 * results in the right pane, "⌕ Search indexers" at its top beside the Titles header; both inside the safe area. The
 * keyboard is asked to float left, over the recent searches, and the right pane neither moves nor shrinks for it. Where
 * the keyboard then shows is the keyboard's own, and nothing here can see it: a phone emulator's docks at the bottom of
 * its screen, below the TV's canvas, and tv36's ignores the option and covers the lower left of the results (TvImeTest
 * records it). Invented titles ([JumpFixture]).
 */
@RunWith(AndroidJUnit4::class)
class TvSearchLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val fixture = JumpFixture()
    private lateinit var nav: NavHostController

    @Before
    fun showTheApp() {
        fixture.open()
        instrumentation.setInTouchMode(false)
        // As MainActivity is: the keyboard's insets reach the screens, and the window keeps its size.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        val library = compose.activity.container.library
        runBlocking {
            library.recentSearches.first().forEach { library.removeSearch(it.key) }
            before.reversed().forEach { r ->
                // Each as what it was: a title opened, or a search.
                val title = r.pick?.takeIf { r.kind == RecentKind.Title }
                if (title != null) library.addTitle(title) else library.addSearch(r.query, r.pick)
                delay(5)
            }
        }
        fixture.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun pane(name: String) = SemanticsMatcher.expectValue(SearchPaneKey, name)

    private fun screenWidth(): Float = compose.tvScreen().width

    /** Search, opened fresh in the TV shell, with [recent] as its only recent search: its field's gate focused. */
    private fun openSearch(recent: String? = RECENT) {
        val library = compose.activity.container.library
        runBlocking {
            before = library.recentSearches.first()
            before.forEach { library.removeSearch(it.key) }
            recent?.let { library.addSearch(it) }
        }
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        recent?.let { compose.waitUntil(5_000) { exists(hasText(it)) } }
    }

    /** The recent searches there before, hidden meanwhile. */
    private var before: List<io.skjaere.usenet.data.RecentSearch> = emptyList()

    private fun Rect.within(outer: Rect) = left >= outer.left - 1 && top >= outer.top - 1 && right <= outer.right + 1 && bottom <= outer.bottom + 1

    @Test
    fun theFieldAndTheRecentSearchesAreLeftTheHintRightAllInsideTheSafeArea() {
        openSearch()
        val left = bounds(pane("input"))
        val right = bounds(pane("results"))
        val width = screenWidth()
        assertTrue("the panes overlap: $left, $right", left.right <= right.left)
        assertTrue("the left pane isn't about a third of the width: ${left.width} of $width", left.width / width in 0.25f..0.4f)
        val gate = bounds(fieldGate("Search"))
        val dp = width / TV_SIZE.width.value
        assertTrue("the field's gate isn't at the top of the left pane: $gate in $left", gate.within(left) && gate.top - left.top <= 16 * dp)
        assertTrue("the recent search isn't under the field, on the left", bounds(hasText(RECENT) and hasClickAction()).let { it.within(left) && it.top >= gate.bottom })
        assertTrue("the hint isn't on the right", bounds(hasText(HINT)).within(right))
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    @Test
    fun titlesShowOnTheRightUnderSearchIndexersBesideTheirHeader() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val title = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(title) }
        val right = bounds(pane("results"))
        val first = bounds(title)
        val indexers = bounds(hasContentDescription("Search indexers for “glasswater”") and hasClickAction())
        val header = bounds(hasText("TITLES"))
        assertTrue("the first title isn't on the right: $first in $right", first.within(right))
        assertTrue("Search indexers isn't at the top of the right pane, above the titles: $indexers", indexers.within(right) && indexers.bottom <= first.top)
        assertTrue("the Titles header isn't beside Search indexers: $header, $indexers", header.center.y in indexers.top..indexers.bottom && header.right <= indexers.left)
        assertTrue("the recent search left the left pane", bounds(hasText(RECENT) and hasClickAction()).within(bounds(pane("input"))))
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    /** The field opened (OK on its gate): its keyboard is asked to float left (Gboard on a TV does), from the editor's options. */
    @Test
    fun theKeyboardIsAskedToFloatLeft() {
        openSearch()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasSetTextAction() and isFocused()) }
        assertEquals("horizontalAlignment=left", editorOptions())
    }

    /**
     * The keyboard up: the right pane neither moves nor shrinks for it (the window keeps its size). Whether the keyboard
     * covers it isn't seen here (see the class's note).
     */
    @Test
    fun theRightPaneNeitherMovesNorShrinksForTheKeyboard() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        compose.onNode(fieldGate("Search")).requestFocus()
        compose.waitUntil(5_000) { !keyboardUp() }
        val right = bounds(pane("results"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { keyboardUp() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("OK on the gate brought no keyboard; focus on \"${remote.focused()}\"", e)
        }
        compose.waitForIdle()
        assertEquals("the right pane moved or shrank for the keyboard", right, bounds(pane("results")))
    }

    private fun keyboardUp(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    /** What the focused field asks of the keyboard beyond the usual ([EditorInfo.privateImeOptions]). */
    private fun editorOptions(): String? = compose.runOnIdle {
        val view = compose.activity.window.decorView.findFocus()
        val info = EditorInfo()
        view?.onCreateInputConnection(info)
        info.privateImeOptions
    }

    // The remote's moves between the panes, state by state: Right from the left pane enters the
    // right pane at its first row; Left from the right pane's first column goes back where the focus was in the left
    // pane; Up from the first title reaches Search indexers; Back from the right pane goes to the field's gate, the
    // query kept and no keyboard, and Back from the left pane to the rail.

    private fun railOpen() = RAIL.any { remote.isFocused(railItem(it)) }

    private fun gateFocused() = exists(fieldGate("Search") and isFocused())

    private fun typing() = exists(hasSetTextAction() and isFocused())

    /** The words in the field. */
    private fun query(): String = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun press(key: Int): String = remote.press(key)

    /** Back from the right pane, then from the left: the field's gate, [words] kept and no keyboard, then the rail. */
    private fun backToTheFieldThenTheRail(words: String) {
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the right pane: the field's gate; focus on ${remote.focused()}", gateFocused())
        assertEquals("Back cleared the query", words, query())
        assertFalse("Back opened the field, and its keyboard", typing())
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the left pane: the rail; focus on ${remote.focused()}", railOpen())
        assertEquals("Back cleared the query", words, query())
    }

    @Test
    fun theRemoteBetweenThePanesWithNothingTyped() {
        openSearch()
        // No indexer (the fixture's are off): the right pane's first row is its Add indexer.
        assertEquals("Right from the field's gate", "Add indexer", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from Add indexer: back on the gate; focus on ${remote.focused()}", gateFocused())
        // Down the left pane, past the list's Clear, to the recent search's ✕ at its end.
        val remove = "Remove $RECENT from recent searches"
        assertEquals("Down from the gate", remove, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, remove, max = 3))
        assertTrue("Left from its ✕: the recent search", press(KeyEvent.KEYCODE_DPAD_LEFT).startsWith(RECENT))
        assertEquals("Right from the recent search: its ✕", remove, press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Right from its ✕: the right pane's first row", "Add indexer", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left from Add indexer: back on the ✕", "Remove $RECENT from recent searches", press(KeyEvent.KEYCODE_DPAD_LEFT))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        backToTheFieldThenTheRail("")
    }

    @Test
    fun theRemoteBetweenThePanesWithTitlesTyped() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        // The field closed, as Back closes it: its gate, which TalkBack reads with the words in the field.
        compose.onNode(fieldGate("Search")).requestFocus()
        compose.waitUntil(5_000) { gateFocused() }
        assertEquals("the gate doesn't say the words in the field", "glasswater", compose.onNode(fieldGate("Search")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("Right from the gate: the first title, in one press", press(KeyEvent.KEYCODE_DPAD_RIGHT).startsWith("Glasswater Bay"))
        assertEquals("Up from the first title", "Search indexers for “glasswater”", press(KeyEvent.KEYCODE_DPAD_UP))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from Search indexers: back on the gate; focus on ${remote.focused()}", gateFocused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right from the first title", "+ Collection, Glasswater Bay", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse("the rail has the focus", railOpen())
        backToTheFieldThenTheRail("glasswater")
    }

    /** The keyboard's Enter: it goes, and the focus goes to the first title (Search clarity: the preview stays). */
    @Test
    fun enterClosesTheKeyboardAndFocusesTheFirstTitle() {
        openSearch()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { typing() }
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { remote.focused().startsWith("Glasswater Bay") }
        compose.waitUntil(5_000) { !keyboardUp() }
        assertFalse("the field kept the focus", typing())
        assertEquals("glasswater", query())
    }

    @Test
    fun theRemoteBetweenThePanesWithResultsAndTheirReleaseList() = withFakeIndexer {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        compose.onNode(hasContentDescription("Search indexers for “$QUERY”") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { exists(playsAGroup) }
        compose.waitUntil(5_000) { exists(playsAGroup and isFocused()) }
        val first = remote.focused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the first group: the left pane; focus on ${remote.focused()}", exists(pane("input")) && !railOpen() && remote.focused() != first)
        assertEquals("Right from the left pane: the first group", first, press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Up from the first group, to the top of the right pane", "Search indexers for “$QUERY”", press(KeyEvent.KEYCODE_DPAD_UP))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        // Its releases, opened in the right pane beside the field.
        val releases = press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from the group: its releases; focus on $releases", "release" in releases)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Size") and hasClickAction()) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertTrue("the field's gate went with the release list", exists(fieldGate("Search")) && bounds(fieldGate("Search")).within(bounds(pane("input"))))
        val release = remote.focused()
        assertTrue("the release list's first release has the focus; it is on $release", release.contains(".S0", ignoreCase = true) || release.contains("Best match"))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse("Left from the release: still in Search, not the rail", railOpen())
        assertEquals("Right from the left pane: the first release", release, press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Back from the left pane goes to the rail (3.5), the list left open: only Back from the list closes it.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from the left pane: the rail; focus on ${remote.focused()}", railOpen())
        assertTrue("Back from the left pane closed the release list", exists(hasText("Size") and hasClickAction()))
        assertEquals("Right from the rail: the first release", release, press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(playsAGroup) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("Back from the release list: the releases that opened it", releases, remote.focused())
        backToTheFieldThenTheRail(QUERY)
    }

    /** [QUERY]'s indexers searched, and its first group's releases opened from its "N releases ›": the list on the right. */
    private fun openAReleaseList() {
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        compose.onNode(hasContentDescription("Search indexers for “$QUERY”") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { exists(playsAGroup and isFocused()) }
        assertTrue("Right from the group: its releases; focus on ${remote.focused()}", "release" in press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(releaseList) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /** A release list's sorts: it shows. */
    private val releaseList = hasText("Size") and hasClickAction()

    /**
     * A release list open on the right, then the field opened from the left and other words typed: the list goes, the
     * right pane shows the titles for the words, and Enter puts the focus on the first of them, not in the list.
     */
    @Test
    fun wordsTypedWithAReleaseListOpenShowTheirTitlesOnTheRight() = withFakeIndexer {
        openSearch()
        openAReleaseList()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the release: the field's gate; focus on ${remote.focused()}", gateFocused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { typing() }
        compose.onNode(hasSetTextAction()).performTextReplacement("glasswater")
        try {
            compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the titles for the words typed never showed; the release list still shows: ${exists(releaseList)}", e)
        }
        assertFalse("the release list still shows", exists(releaseList))
        compose.onNode(hasSetTextAction()).performImeAction()
        try {
            compose.waitUntil(5_000) { remote.focused().startsWith("Glasswater Bay") }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Enter: the focus isn't on the first title; it is on \"${remote.focused()}\"", e)
        }
    }

    @Test
    fun theRemoteBetweenThePanesWhenNothingIsFound() = withFakeIndexer {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(NOTHING)
        compose.onNode(hasContentDescription("Search indexers for “$NOTHING”") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { exists(hasText("No releases found")) }
        compose.waitUntil(5_000) { remote.focused().startsWith("No releases found") }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the answer: back on the gate, where the focus was; focus on ${remote.focused()}", gateFocused())
        assertTrue("Right from the gate: the answer", press(KeyEvent.KEYCODE_DPAD_RIGHT).startsWith("No releases found"))
        backToTheFieldThenTheRail(NOTHING)
    }

    /** [test] with the fake indexer (10.0.2.2:8089) switched on, the fixture's being off; it goes again after, with its key. */
    private fun withFakeIndexer(test: () -> Unit) {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        val settings = compose.activity.container.settings
        try {
            runBlocking { settings.saveIndexer(FAKE_INDEXER, "Fake (TV search layout test)", "http://10.0.2.2:8089", "fake-indexer-key") }
            test()
        } finally {
            runBlocking { if (settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER) }
        }
    }

    private companion object {
        const val RECENT = "slate harbour"
        const val HINT = "Shows, movies or release names"
        /** The rail's items, the ones the app shows: read from its tabs, so none is missed (Discover's in debug builds). */
        val RAIL = tabsFor(FormFactor.Tv).map { it.label }
        const val SETTLE_MS = 700L
        const val FAKE_INDEXER = "tv-search-layout-test"
        const val QUERY = "harbor lights"
        /** Invented words: no release has them. */
        const val NOTHING = "quiet marsh"
    }
}

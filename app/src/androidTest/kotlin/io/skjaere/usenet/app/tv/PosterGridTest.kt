package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@Serializable
private data object GridPageRoute

@Serializable
private data class OpenedRoute(val n: Int)

@Serializable
private data object GrowingRoute

/**
 * TV-4's generic poster grid over plain items, as a TV shows it (forced on a phone emulator, 960 x 540 dp), with a remote:
 * seven to a line from the column's start, the walk along a line and down a column, Right stopping at a line's last
 * poster, Left from a line's first into the rail and Right back to it, the paging keys three lines at a time (held too)
 * and up to the header, the focus back on the poster that opened a page, requesters for the posters composed only; on a
 * page, without the rail, the page's whole column.
 */
@RunWith(AndroidJUnit4::class)
class PosterGridTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private lateinit var nav: NavHostController
    private var count by mutableIntStateOf(40)
    private var tabState: PosterGridState? = null
    /** The growing grid's posters, and how many times it asked for more. */
    private var growing by mutableIntStateOf(20)
    private val nearEnds = AtomicInteger()

    @Before
    fun showTheGrid() {
        instrumentation.setInTouchMode(false)
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), compose.activity.container.playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text("Home stand-in") }
                    }
                    composable<CollectionRoute> { screen ->
                        TopLevelBack(nav, screen)
                        val state = rememberPosterGridState().also { tabState = it }
                        Grid(state, onTab = true)
                    }
                    composable<GridPageRoute> { Grid(rememberPosterGridState(), onTab = false) }
                    // A page of a list that grows as it is scrolled, 20 at a time up to 100.
                    composable<GrowingRoute> {
                        PosterGrid(
                            items = (1..growing).toList(), key = { "g$it" }, poster = { n -> PosterItem("g$n", "Grown $n", posterPath = null, spoken = "Grown $n") },
                            state = rememberPosterGridState(), artwork = false, onOpen = {}, onTab = false,
                            onNearEnd = {
                                nearEnds.incrementAndGet()
                                if (growing < 100) growing += 20
                            },
                        )
                    }
                    composable<OpenedRoute> { entry ->
                        BackHandler { nav.popBackStack() }
                        Button(onClick = {}) { Text("Page of poster ${entry.toRoute<OpenedRoute>().n}") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Grid(state: PosterGridState, onTab: Boolean) {
        val scope = rememberCoroutineScope()
        val headerFocus = remember { FocusRequester() }
        PosterGrid(
            items = (1..count).toList(), key = { "p$it" },
            poster = { n -> PosterItem("p$n", "Poster $n", posterPath = null, line = "Line $n", spoken = spoken(n)) },
            state = state, artwork = false, onTab = onTab,
            onOpen = { n ->
                state.leaving("p$n")
                nav.navigate(OpenedRoute(n))
            },
            header = listOf(HEADER),
            headerItem = { Box(Modifier.padding(bottom = 12.dp)) { Button(onClick = {}, Modifier.focusRequester(headerFocus)) { Text(HEADER_BUTTON) } } },
            onHeader = {
                scope.launch {
                    state.grid.scrollToItem(0)
                    withFrameNanos {}
                    headerFocus.tryFocus()
                }
            },
        )
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    private fun spoken(n: Int) = "Poster $n, line $n"

    private fun openTab() {
        compose.runOnIdle { nav.switchTab(CollectionRoute) }
        remote.waitForFocus(HEADER_BUTTON)
        assertEquals(spoken(1), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    private fun dp(): Float = compose.tvScreen().width / TV_SIZE.width.value

    /** Where the poster [n] is across the screen, in dp from its left: its start and its end. */
    private fun span(n: Int): Pair<Float, Float> {
        val screen = compose.tvScreen()
        val b = compose.onNode(hasContentDescription(spoken(n))).fetchSemanticsNode().boundsInRoot
        return ((b.left - screen.left) / dp()) to ((b.right - screen.left) / dp())
    }

    @Test
    fun sevenToALineFromTheColumnsStartBesideTheRail() {
        openTab()
        val first = span(1)
        val seventh = span(7)
        assertTrue("the first poster at the column's start (128 dp): $first", abs(first.first - 128) <= 2)
        assertTrue("98 dp wide: $first", abs(first.second - first.first - 98) <= 2)
        // From the first poster: the test's dp (the screen's width over 960) is the forced density's within a few tenths of a percent.
        assertTrue("seven and their gutters, 782 dp, end at the seventh: $first to $seventh", abs(seventh.second - first.first - 782) <= 3)
        val eighth = compose.onNode(hasContentDescription(spoken(8))).fetchSemanticsNode().boundsInRoot
        val firstBox = compose.onNode(hasContentDescription(spoken(1))).fetchSemanticsNode().boundsInRoot
        assertTrue("the eighth starts the next line", eighth.top > firstBox.bottom && abs(eighth.left - firstBox.left) <= dp() * 2)
    }

    @Test
    fun theWalkGoesAlongALineAndDownAColumnAndRightStopsAtALinesLastPoster() {
        openTab()
        repeat(6) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(spoken(7), remote.focused())
        assertEquals("Right stops at the line's last poster", spoken(7), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(spoken(14), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(spoken(13), remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        // The last line, five of seven: Right from its last poster stays there, not up to the line before's.
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, spoken(6), max = 3)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, spoken(1), max = 6)
        assertEquals(spoken(36), remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, spoken(36), max = 6))
        assertEquals(spoken(40), remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, spoken(40), max = 5))
        assertEquals("Right stops at a short last line's last poster", spoken(40), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Back up to the first line, and Up from it to the header.
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, spoken(5), max = 6)
        assertEquals(HEADER_BUTTON, remote.press(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun leftFromALinesFirstPosterEntersTheRailAndRightComesBackToIt() {
        openTab()
        // Past the first screen: line 5's first.
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, spoken(29), max = 5)
        assertEquals(spoken(29), remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("on the rail; focus on ${remote.focused()}", remote.isFocused(railItem("Collection")))
        assertEquals("Right from the rail: the poster left", spoken(29), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun thePagingKeysMoveTheFocusThreeLinesAtATimeAndUpToTheHeader() {
        openTab()
        assertEquals(spoken(22), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("short of three lines: the last line's first", spoken(36), remote.press(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals("on the last line: stays", spoken(36), remote.press(KeyEvent.KEYCODE_PAGE_DOWN))
        compose.onNode(hasContentDescription(spoken(36))).assertExists()
        assertEquals(spoken(15), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(spoken(1), remote.press(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("from the first line: the header", HEADER_BUTTON, remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals("from the header: the first poster", spoken(1), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    /** A held key pages again with each repeat, on from where the last page went, not from where the focus still was. */
    @Test
    fun aHeldPagingKeyPagesOnWithEachRepeat() {
        openTab()
        val down = SystemClock.uptimeMillis()
        val press = KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, 0)
        instrumentation.sendKeySync(press)
        instrumentation.sendKeySync(KeyEvent.changeTimeRepeat(press, SystemClock.uptimeMillis(), 1))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, 0))
        remote.waitForFocus(spoken(36))
    }

    @Test
    fun onlyThePostersComposedKeepARequester() {
        compose.runOnIdle { count = 140 }
        openTab()
        repeat(8) { remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN) }
        assertEquals("paged to the last line", spoken(134), remote.focused())
        val kept = compose.runOnIdle { tabState!!.requestersKept }
        assertTrue("$kept requesters for 140 posters", kept in 1..60)
    }

    @Test
    fun theFocusComesBackToThePosterThatOpenedAPage() {
        openTab()
        remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(spoken(23), remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Page of poster 23")).fetchSemanticsNodes().isNotEmpty() }
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(spoken(23))
    }

    @Test
    fun onAPageWithoutTheRailTheGridTakesThePagesWholeColumnFromItsStart() {
        compose.runOnIdle { nav.navigate(GridPageRoute) }
        remote.waitForFocus(HEADER_BUTTON)
        assertEquals(spoken(1), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val first = span(1)
        val seventh = span(7)
        assertTrue("on a page the column starts at the safe line (48 dp): $first", abs(first.first - 48) <= 2)
        assertTrue("seven to a line still, from its start, not spread over the page's 864 dp: $first to $seventh", abs(seventh.second - first.first - 782) <= 3)
        assertEquals("no rail to go to", spoken(1), remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(compose.onAllNodes(railItem("Collection")).fetchSemanticsNodes().isEmpty())
        assertEquals(spoken(22), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    /**
     * A grid whose items grow as it is scrolled asks for more as its posters near the last line (within three), once for
     * each count it is near the end at: as they come near, and again as more lands while still there; never further up.
     */
    @Test
    fun aGrowingGridAsksForMoreAsItsPostersNearTheEndAndAgainAsMoreLands() {
        compose.runOnIdle { nav.navigate(GrowingRoute) }
        compose.waitUntil(5_000) { nearEnds.get() >= 1 }
        Thread.sleep(500)
        compose.waitForIdle()
        // At the top: enough to put the end more than three lines past the screen's last, and no more.
        val settled = growing
        assertTrue("grew ahead of the first screen, to $settled", settled in 40..60)
        assertEquals("one ask for each count it was near the end at", settled / 20 - 1, nearEnds.get())
        compose.onNode(hasContentDescription("Grown 1")).performSemanticsAction(SemanticsActions.RequestFocus)
        remote.waitForFocus("Grown 1")
        // Down every line, to the last of 100's fifteen: the counts 20 to 80 each grew it; 100 was asked once, and only once.
        repeat(16) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitForIdle()
        assertEquals("Grown 99", remote.focused())
        assertEquals(100, growing)
        assertEquals(5, nearEnds.get())
    }

    private companion object {
        const val HEADER = "head"
        const val HEADER_BUTTON = "Header button"
    }
}

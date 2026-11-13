package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.JumpFixture
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.collection.TvCollectionKey
import io.skjaere.usenet.app.switchTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.reflect.KClass

/**
 * "+ Collection" on a TV, end to end (TV-4's item 7): the real Search and Collection in the app's TV shell (forced on a
 * phone emulator), the remote on a title's "+ Collection", the Collection tab with the new poster focused and "Just
 * added", and one Back to Search with the focus back on the title's action; a Back pressed as the tab fades in is Back
 * to Search's all the same (#244, TVX-6's rule); after a jump the user's move to the rail disarms it, so Back from the
 * rail goes Home. Invented titles ([JumpFixture]), which it puts back.
 */
@RunWith(AndroidJUnit4::class)
class TvCollectionJumpTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val fixture = JumpFixture()
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        fixture.open()
        instrumentation.setInTouchMode(false)
        // As MainActivity is: the keyboard's insets reach the screens, and the window keeps its size.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        fixture.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The new title's poster in the Collection's grid, as a jump just added it. */
    private val justAdded = hasContentDescription(NAME, substring = true) and inTvCollection and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Just added")

    private fun on(route: KClass<*>): Boolean = runCatching { nav.currentBackStackEntry?.destination?.hasRoute(route) == true }.getOrDefault(false)

    private fun collectionUp(): Boolean = on(CollectionRoute::class) && nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

    /** The app on a TV, Search opened fresh and "glasswater" typed: its title on the right, the remote's focus on its "+ Collection". */
    private fun toTheTitlesCollectionAction() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        compose.waitUntil(10_000) { exists(hasText(NAME) and hasClickAction()) }
        compose.onNode(fieldGate("Search")).requestFocus()
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        assertTrue("Right from the gate: the title; on ${remote.focused()}", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).startsWith(NAME))
        assertEquals("+ Collection, $NAME", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /** The destinations gone to, in order, for what a failure says. */
    private val seen = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun watchTheDestinations() {
        compose.runOnIdle { nav.addOnDestinationChangedListener { _, d, _ -> seen += d.route.orEmpty().substringAfterLast('.') } }
    }

    /**
     * Search shown again, the focus back on the title's action, which now says it is in the collection; or, with [orRow],
     * on the title's row beside it.
     */
    private fun backOnSearchsAction(before: String = "", orRow: Boolean = false) {
        fun there(focused: String) = focused.startsWith("$NAME, in your collection") || (orRow && focused.startsWith("$NAME | "))
        try {
            compose.waitUntil(10_000) { on(SearchRoute::class) && there(remote.focused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("not back on Search's action: on ${nav.currentDestination?.route}, the focus on \"${remote.focused()}\"; $before; went $seen", e)
        }
    }

    @Test
    fun plusCollectionFocusesTheNewPosterJustAddedAndOneBackReturnsToSearchOnTheAction() {
        toTheTitlesCollectionAction()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(10_000) { exists(justAdded and isFocused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the new poster never had the focus; it is on \"${remote.focused()}\"", e)
        }
        compose.onNode(justAdded).assertIsDisplayed()
        // Back once the tab is up, as the user would: Search as it was, on the title's action.
        compose.waitUntil(5_000) { collectionUp() }
        remote.press(KeyEvent.KEYCODE_BACK)
        backOnSearchsAction()
    }

    /**
     * A Back pressed while the Collection tab fades in after the jump (its 700 ms) is Back to Search's, not taken and
     * dropped, nor passed on to the NavHost's own (which pops the tab to Home): from the tab's first composition. In the
     * frame before it (the tab switched, its screen not composed yet, so none of its handlers there) a Back is the
     * NavHost's, as for any tab switched to. Search, still fading out then, is the same screen again, its focus kept on
     * the title's row or its action.
     */
    @Test
    fun aBackAsTheCollectionFadesInAfterTheJumpStillReturnsToSearch() {
        toTheTitlesCollectionAction()
        watchTheDestinations()
        // A frame at a time from the OK on, so the tab's fade is still going when Back is pressed: from the frame its
        // screen is first composed in.
        compose.mainClock.autoAdvance = false
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!(on(CollectionRoute::class) && exists(SemanticsMatcher.keyIsDefined(TvCollectionKey)))) {
            assertTrue("the Collection never showed; went $seen", SystemClock.elapsedRealtime() < deadline)
            compose.mainClock.advanceTimeByFrame()
        }
        val state = nav.currentBackStackEntry?.lifecycle?.currentState
        assertTrue("still fading in: $state", state != Lifecycle.State.RESUMED)
        val before = "Back pressed in $state with the focus on \"${remote.focused()}\""
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.mainClock.autoAdvance = true
        backOnSearchsAction(before, orRow = true)
    }

    /** After the jump, the user's Left into the rail disarms Back to Search: Back from the rail goes Home, as it always does. */
    @Test
    fun backFromTheRailAfterAJumpGoesHome() {
        toTheTitlesCollectionAction()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(justAdded and isFocused()) }
        compose.waitUntil(5_000) { collectionUp() }
        repeat(GRID_COLUMNS) { if (!remote.isFocused(railItem("Collection"))) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("Left to the rail; on ${remote.focused()}", remote.isFocused(railItem("Collection")))
        remote.press(KeyEvent.KEYCODE_BACK)
        try {
            compose.waitUntil(5_000) { on(HomeRoute::class) && remote.isFocused(railItem("Home")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Back from the rail: on ${nav.currentDestination?.route}, the focus on \"${remote.focused()}\"", e)
        }
    }

    private companion object {
        const val NAME = "Glasswater Bay"
        const val GRID_COLUMNS = 7
    }
}

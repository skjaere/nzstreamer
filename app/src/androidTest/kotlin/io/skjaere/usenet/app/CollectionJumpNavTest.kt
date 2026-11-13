package io.skjaere.usenet.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * "+ Collection" on Search, in the app's navigation over stand-in screens: the Collection tab opens
 * on the jump, which it takes once; Back returns to Search as it was (its ViewModel kept); leaving the
 * tab disarms that Back, a Show page opened from the list doesn't; both survive a recreation. A jump
 * the list is left before revealing goes with it.
 */
@RunWith(AndroidJUnit4::class)
class CollectionJumpNavTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var jumps: CollectionJumpState
    private val searches = AtomicInteger()
    private val cleared = AtomicInteger()
    /** The jumps the Collection stand-in took, as CollectionScreen takes them (consumed once revealed). */
    private val revealed = CopyOnWriteArrayList<CollectionJump>()
    /** False: the Collection stand-in leaves the jump untaken (a reveal still under way). */
    private var reveals = true
    private val orbit = CollectionJump("show:900005", "The Quiet Orbit")

    private inner class SearchState : ViewModel() {
        init {
            searches.incrementAndGet()
        }

        override fun onCleared() {
            cleared.incrementAndGet()
        }
    }

    @Composable
    private fun Tabs() {
        nav = rememberNavController()
        jumps = rememberCollectionJumps(nav)
        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> { Text("Home") }
            composable<CollectionRoute> { screen ->
                BackToSearch(nav, jumps, screen)
                val jump = jumps.jump
                LaunchedEffect(jump) {
                    if (jump != null && reveals) {
                        revealed += jump
                        jumps.jumpDone()
                    }
                }
                Text("Collection")
            }
            composable<ShowRoute> { Text("Show ${it.toRoute<ShowRoute>().tmdbId}") }
            composable<SearchRoute> {
                viewModel { SearchState() }
                Text("Search")
            }
        }
    }

    private fun show() = compose.setContent { Tabs() }

    private fun onNav(action: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.action() }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** On Search, its results up, "+ Collection" tapped. */
    private fun jumpFromSearch() {
        onNav { switchTab(SearchRoute()) }
        compose.onNodeWithText("Search").assertIsDisplayed()
        onNav { jumpToCollection(jumps, orbit) }
    }

    @Test
    fun theJumpOpensTheCollectionOnceAndBackReturnsToTheSameSearch() {
        show()
        jumpFromSearch()
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertEquals(listOf(orbit), revealed)
        assertTrue(jumps.backToSearch)

        back()
        compose.onNodeWithText("Search").assertIsDisplayed()
        assertEquals("Search's ViewModel made again", 1, searches.get())
        assertEquals("Search's ViewModel cleared", 0, cleared.get())
        assertFalse(jumps.backToSearch)
    }

    @Test
    fun leavingTheCollectionTabDisarmsBackToSearch() {
        show()
        jumpFromSearch()
        onNav { switchTab(HomeRoute) }
        assertFalse(jumps.backToSearch)
        onNav { switchTab(CollectionRoute) }
        back()
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun aShowPageOpenedFromTheCollectionKeepsBackToSearch() {
        show()
        jumpFromSearch()
        onNav { navigate(ShowRoute(900005)) }
        compose.onNodeWithText("Show 900005").assertIsDisplayed()
        assertTrue(jumps.backToSearch)
        back()
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertTrue(jumps.backToSearch)
        back()
        compose.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun aLaterTapOnTheCollectionTabDoesntJumpAgain() {
        show()
        jumpFromSearch()
        onNav { switchTab(HomeRoute) }
        onNav { switchTab(CollectionRoute) }
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertEquals(listOf(orbit), revealed)
    }

    @Test
    fun aJumpLeftBeforeItsRevealIsntReplayedOnALaterVisit() {
        reveals = false
        show()
        jumpFromSearch()
        compose.onNodeWithText("Collection").assertIsDisplayed()
        onNav { switchTab(HomeRoute) }
        reveals = true
        onNav { switchTab(CollectionRoute) }
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertEquals(emptyList<CollectionJump>(), revealed)
        assertNull(jumps.jump)
    }

    @Test
    fun aShowPageOpenedMidRevealTakesTheJumpButKeepsBackToSearch() {
        reveals = false
        show()
        jumpFromSearch()
        onNav { navigate(ShowRoute(900005)) }
        compose.onNodeWithText("Show 900005").assertIsDisplayed()
        reveals = true
        back()
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertEquals(emptyList<CollectionJump>(), revealed)
        assertNull(jumps.jump)
        assertTrue(jumps.backToSearch)
    }

    @Test
    fun theJumpAndBackToSearchOutliveARecreation() {
        reveals = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Tabs() }
        jumpFromSearch()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Collection").assertIsDisplayed()
        assertEquals(orbit, jumps.jump)
        assertTrue(jumps.backToSearch)
        back()
        compose.onNodeWithText("Search").assertIsDisplayed()
        // Left unrevealed: gone with the list.
        assertNull(jumps.jump)
    }
}

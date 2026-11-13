package io.skjaere.usenet.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [switchTab] with restore = false, as "Search manually" on the play sheet uses it: the tab then
 * shows the new search, also after leaving it and coming back, and the search it replaced is gone.
 */
@RunWith(AndroidJUnit4::class)
class SwitchTabTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private val cleared = CopyOnWriteArrayList<String?>()

    private inner class SearchState(val query: String?) : ViewModel() {
        override fun onCleared() {
            cleared += query
        }
    }

    @Before
    fun showTabs() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("Home") }
                composable<CollectionRoute> { Text("Collection") }
                composable<SearchRoute> {
                    val query = it.toRoute<SearchRoute>().query
                    viewModel { SearchState(query) }
                    Text("Search $query")
                }
            }
        }
    }

    private fun switchTab(route: Any, restore: Boolean = true) {
        compose.runOnIdle { nav.switchTab(route, restore) }
        compose.waitForIdle()
    }

    @Test
    fun theSearchTabReturnsToANewSearchMadeFromAnotherTab() {
        switchTab(SearchRoute("foo"))
        switchTab(CollectionRoute)
        switchTab(SearchRoute("bar"), restore = false)
        compose.onNodeWithText("Search bar").assertIsDisplayed()

        switchTab(CollectionRoute)
        switchTab(SearchRoute())

        compose.onNodeWithText("Search foo").assertDoesNotExist()
        compose.onNodeWithText("Search bar").assertIsDisplayed()
        compose.waitUntil(5_000) { "foo" in cleared }
    }

    @Test
    fun theSearchTabReturnsToANewSearchMadeFromItself() {
        switchTab(SearchRoute("foo"))
        switchTab(SearchRoute("bar"), restore = false)
        compose.onNodeWithText("Search bar").assertIsDisplayed()

        switchTab(SearchRoute())

        compose.onNodeWithText("Search foo").assertDoesNotExist()
        compose.onNodeWithText("Search bar").assertIsDisplayed()
        compose.waitUntil(5_000) { "foo" in cleared }
    }
}

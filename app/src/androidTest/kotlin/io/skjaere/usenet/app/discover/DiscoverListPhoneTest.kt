package io.skjaere.usenet.app.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A list's See all page on a phone, over invented lists (FakeDiscover): the filters each side takes (no year or service
 * for shows), picking one, paging to the cap, a page further down that fails, a filter nothing matches, and Back.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverListPhoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val backs = AtomicInteger()

    /** [query]'s page, on a phone [width] wide (null: the emulator's own), which may change. */
    private fun showList(query: DiscoverQuery, source: FakeDiscover, width: () -> Dp? = { null }) {
        val deps = DiscoverDeps(
            hasTmdbKey = flowOf(true), showArtwork = flowOf(false), language = flowOf(null),
            collectedShows = flowOf(emptySet()), collectedMovies = flowOf(emptySet()), source = source,
        )
        compose.setContent {
            val page: @Composable () -> Unit = {
                AppTheme(FormFactor.Phone) {
                    DiscoverListScreen(query, onBack = { backs.incrementAndGet() }, onShow = {}, onMovie = {}, onMetadata = {}, deps = { deps })
                }
            }
            val w = width()
            if (w == null) page() else DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(w, 740.dp))) { page() }
        }
        compose.waitForIdle()
    }

    /** Three posters to a line on the phones most have (360 dp wide, or 390), as the mockup has them. */
    @Test
    fun aPhone360Or390DpWideHasThreePostersToALine() {
        var phone by mutableStateOf(360.dp)
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.Trending), FakeDiscover()) { phone }
        listOf(360.dp, 390.dp).forEach { width ->
            phone = width
            compose.waitForIdle()
            val tops = listOf("Harbor Lights 1", "Lantern Row 1", "The Quiet Orbit 1", "Tarnwick 1").map { name ->
                compose.onNode(hasContentDescription("$name, 2021, show")).fetchSemanticsNode().boundsInRoot.top
            }
            assertEquals("the first line at $width: three posters", 1, tops.take(3).distinct().size)
            assertTrue("the fourth on the next line at $width", tops[3] > tops[0])
        }
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private val grid get() = compose.onAllNodes(hasScrollToIndexAction()).onFirst()

    @Test
    fun aShowsListTakesThePeriodGenreAndLanguageButNoYearOrService() {
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.Trending), FakeDiscover())
        compose.onNodeWithText("Trending · Shows").assertExists()
        listOf("This week ▾", "All genres ▾", "Any language ▾").forEach { compose.onNodeWithText(it).assertExists() }
        assertTrue("no year for shows", !exists("Any year ▾"))
        assertTrue("no service for shows", !exists("Any service ▾"))
        assertTrue(!exists(JUSTWATCH))
        compose.onNodeWithText("Trending this week").assertExists()
        // Back goes back to the tab.
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs.get())
    }

    @Test
    fun aMoviesListAddsTheYearAndAServiceWithJustWatchsCredit() {
        showList(DiscoverQuery(MediaType.Movie, DiscoverList.Popular), FakeDiscover())
        compose.onNodeWithText("Popular · Movies").assertExists()
        listOf("All genres ▾", "Any language ▾", "Any year ▾", "Any service ▾").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(JUSTWATCH).assertExists()
    }

    @Test
    fun aFilterPickedAsksForTheListAgainWithIt() {
        val source = FakeDiscover()
        showList(DiscoverQuery(MediaType.Movie, DiscoverList.Popular), source)
        compose.onNodeWithText("All genres ▾").performClick()
        compose.onNodeWithText("Comedy").performClick()
        compose.waitUntil(5_000) { exists("Popular in Comedy") }
        compose.onNodeWithText("Comedy ▾").assertExists()
        // From the first page again (the next is asked as soon as the first screen shows).
        assertEquals(1, source.asked.first { it.first.filters.genre == 35 }.second)
        compose.onNodeWithText("Any year ▾").performClick()
        compose.onNodeWithText("1990s").performClick()
        compose.waitUntil(5_000) { exists("Popular in Comedy · 1990s") }
    }

    @Test
    fun pagingGoesOnToTheCapAndSaysItShowsTheFirst500() {
        val source = FakeDiscover(answer = { q, from -> Aged(FakeDiscover.page(q, from, DiscoverPage.MAX_PAGES, capped = true), Duration.ZERO) })
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.Popular), source)
        // To the end each time: the pills' and the summary's lines, the titles so far, then the footer.
        repeat(60) {
            if (exists(SHOWING_FIRST)) return@repeat
            grid.performScrollToIndex(2 + 20 * source.asked.size)
            compose.waitForIdle()
        }
        grid.performScrollToNode(hasText(SHOWING_FIRST))
        assertEquals((1..DiscoverPage.MAX_PAGES).toList(), source.asked.map { it.second })
        // The last title of the last page is there, each title once.
        grid.performScrollToNode(hasContentDescription("The Ninth Lamp 25, 2021, show"))
    }

    @Test
    fun aPageFurtherDownThatFailsKeepsTheTitlesAndTryAgainGoesOn() {
        val failing = AtomicBoolean(true)
        val source = FakeDiscover(answer = { q, from ->
            if (from == 2 && failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        })
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.Popular), source)
        grid.performScrollToNode(hasText(LOAD_FAILED))
        grid.performScrollToNode(hasContentDescription("The Ninth Lamp 1, 2021, show"))
        failing.set(false)
        grid.performScrollToNode(hasText(TRY_AGAIN))
        compose.onNodeWithText(TRY_AGAIN).performClick()
        compose.waitUntil(5_000) { !exists(LOAD_FAILED) }
        grid.performScrollToNode(hasContentDescription("Harbor Lights 2, 2021, show"))
        assertEquals(listOf(1, 2, 2), source.asked.map { it.second }.take(3))
    }

    @Test
    fun aFilterNothingMatchesSaysSoAndClearFiltersBringsTheListBack() {
        val source = FakeDiscover(answer = { q, from ->
            Aged(if (q.filters.language == "ko") DiscoverPage(emptyList(), from, 1) else FakeDiscover.page(q, from, 3), Duration.ZERO)
        })
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.TopRated), source)
        compose.onNodeWithText("Any language ▾").performClick()
        compose.onNodeWithText("Korean").performClick()
        compose.waitUntil(5_000) { exists(NOTHING_MATCHES) }
        compose.onNodeWithText(CLEAR_FILTERS).performClick()
        compose.waitUntil(5_000) { !exists(NOTHING_MATCHES) }
        compose.onNodeWithText("Any language ▾").assertExists()
        compose.onNode(hasContentDescription("Harbor Lights 1, 2021, show")).assertExists()
    }
}

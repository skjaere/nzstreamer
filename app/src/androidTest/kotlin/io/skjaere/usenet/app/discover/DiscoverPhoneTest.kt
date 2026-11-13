package io.skjaere.usenet.app.discover

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TMDB_ATTRIBUTION
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The Discover tab on a phone, over invented lists (FakeDiscover): the rows of a side, the switch and the genre chips,
 * See all and the cards, and the tab's every state: no key, a key TMDB refused, offline, a row that failed, every row
 * failing, a genre none has.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverPhoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = MutableStateFlow(true)
    private val opened = CopyOnWriteArrayList<Int>()
    private val lists = CopyOnWriteArrayList<DiscoverQuery>()
    private val metadata = AtomicInteger()

    private fun showTab(source: FakeDiscover) {
        val deps = DiscoverDeps(
            hasTmdbKey = key, showArtwork = flowOf(false), language = flowOf(null),
            collectedShows = flowOf(emptySet()), collectedMovies = flowOf(emptySet()), source = source,
        )
        compose.setContent {
            AppTheme(FormFactor.Phone) {
                DiscoverScreen(onShow = { opened += it }, onMovie = { opened += -it }, onList = { lists += it }, onMetadata = { metadata.incrementAndGet() }, deps = { deps })
            }
        }
        compose.waitForIdle()
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls the tab's list (the screen's first scrollable, around the rows' own) to [text]. */
    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    @Test
    fun theTabShowsTheShowsListsAsRowsOfTheirTitles() {
        showTab(FakeDiscover())
        compose.onNodeWithText(DISCOVER).assertExists()
        compose.onNodeWithText("TRENDING THIS WEEK").assertExists()
        // Each list's first page has its own Harbor Lights 1: the first is Trending's.
        compose.onAllNodes(hasContentDescription("Harbor Lights 1, 2021, show"))[0].assertExists()
        listOf("POPULAR", "TOP RATED", "AIRING TODAY", "ON THE AIR").forEach { heading ->
            scrollTo(heading)
            compose.onNodeWithText(heading).assertExists()
        }
        scrollTo("$FOOTNOTE $TMDB_ATTRIBUTION")
    }

    @Test
    fun theMoviesSideShowsTheMoviesLists() {
        val source = FakeDiscover()
        showTab(source)
        compose.onNodeWithText(MOVIES).performClick()
        compose.waitForIdle()
        scrollTo("NOW PLAYING")
        scrollTo("UPCOMING")
        assertEquals(DiscoverList.on(MediaType.Movie), source.asked.filter { it.first.type == MediaType.Movie }.map { it.first.list })
    }

    @Test
    fun aGenreChipFiltersEveryRowAndSeeAllOpensWithIt() {
        val source = FakeDiscover()
        showTab(source)
        source.asked.clear()
        compose.onNodeWithText("Drama").performClick()
        compose.waitForIdle()
        assertEquals(5, source.asked.size)
        assertTrue(source.asked.all { it.first.filters.genre == 18 })
        compose.onAllNodesWithText(SEE_ALL)[0].performClick()
        assertEquals(listOf(DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(genre = 18))), lists)
    }

    @Test
    fun eachRowsSeeAllSaysWhichListItOpens() {
        showTab(FakeDiscover())
        listOf("Trending this week", "Popular").forEach { heading ->
            assertTrue("See all of $heading", compose.onAllNodes(hasContentDescription(seeAllSaid(heading))).fetchSemanticsNodes().size == 1)
        }
    }

    /**
     * The switch's track is the mockup's 38 dp pill around its 32 dp sides (the track as drawn, read along the unselected
     * side's middle against the screen behind it), each side still a 48 dp target past it.
     */
    @Test
    fun theSwitchIsDrawnA38DpPillAroundItsSidesEachA48DpTarget() {
        showTab(FakeDiscover())
        val side = compose.onNode(hasText(MOVIES) and isSelectable())
        val movies = side.fetchSemanticsNode().boundsInRoot
        val dp = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        // Behind the switch: the screen left of the tab's title.
        val title = compose.onNodeWithText(DISCOVER).fetchSemanticsNode().boundsInRoot
        val behind = pixels[(title.left - 8 * dp).toInt().coerceAtLeast(0), title.center.y.toInt()]
        fun differs(c: Color) = abs(c.red - behind.red) + abs(c.green - behind.green) + abs(c.blue - behind.blue) > 0.05f
        val x = movies.center.x.toInt()
        val from = (movies.top - 16 * dp).toInt().coerceAtLeast(0)
        val to = (movies.bottom + 16 * dp).toInt().coerceAtMost(pixels.height - 1)
        val drawn = (from..to).count { y -> differs(pixels[x, y]) } / dp
        assertTrue("the track drawn $drawn dp tall", drawn in 36f..40f)
        // A touch 7 dp above the side's 32 dp, inside its 48 dp target, picks it.
        side.performTouchInput { click(Offset(width / 2f, -7 * dp)) }
        side.assertIsSelected()
    }

    @Test
    fun aCardOpensItsTitlesPage() {
        showTab(FakeDiscover())
        compose.onAllNodes(hasContentDescription("Lantern Row 1, 2021, show"))[0].performClick()
        assertEquals(listOf(FakeDiscover.id(DiscoverList.Trending, 1, 1)), opened)
    }

    @Test
    fun withoutAKeyTheTabSaysSoAsksNothingAndLeadsToTheKey() {
        key.value = false
        val source = FakeDiscover()
        showTab(source)
        compose.onNodeWithText(NO_KEY_TITLE).assertExists()
        compose.onNodeWithText(NO_KEY_LINE).assertExists()
        assertTrue("no switch without a key", !exists(MOVIES))
        compose.onNodeWithText(ADD_KEY).performClick()
        assertEquals(1, metadata.get())
        assertEquals(emptyList<Any>(), source.asked)
        // The key added: the rows, without leaving the tab.
        key.value = true
        compose.waitUntil(5_000) { exists("TRENDING THIS WEEK") }
        assertTrue(!exists(NO_KEY_TITLE))
    }

    @Test
    fun aKeyTmdbRefusedSaysSoAndLeadsToIt() {
        showTab(FakeDiscover(answer = { _, _ -> throw TmdbException(TmdbError.InvalidKey("Invalid API key")) }))
        compose.onNodeWithText(REFUSED_TITLE).assertExists()
        compose.onNodeWithText(CHECK_KEY).performClick()
        assertEquals(1, metadata.get())
        assertTrue("no rows", !exists("TRENDING THIS WEEK"))
    }

    @Test
    fun offlineTheTabSaysHowOldItsListsAre() {
        showTab(FakeDiscover(answer = { q, from -> Aged(FakeDiscover.page(q, from, 3), Duration.ofHours(3), offline = true) }))
        compose.onNodeWithText("Offline · lists from 3 h ago").assertExists()
        compose.onNodeWithText("TRENDING THIS WEEK").assertExists()
    }

    @Test
    fun aRowThatFailedHasTryAgainInItsPlace() {
        val failing = AtomicBoolean(true)
        showTab(
            FakeDiscover(answer = { q, from ->
                if (q.list == DiscoverList.Trending && failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
                Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
            }),
        )
        compose.onNodeWithText(LOAD_FAILED).assertExists()
        compose.onAllNodesWithText(TRY_AGAIN).assertCountEquals(1)
        // The other rows came.
        compose.onNodeWithText("POPULAR").assertExists()
        failing.set(false)
        compose.onNodeWithText(TRY_AGAIN).performClick()
        compose.waitUntil(5_000) { !exists(LOAD_FAILED) }
        compose.onAllNodes(hasContentDescription("Harbor Lights 1, 2021, show"))[0].assertExists()
    }

    @Test
    fun everyRowFailingIsOneCardWithTryAgain() {
        val failing = AtomicBoolean(true)
        showTab(
            FakeDiscover(answer = { q, from ->
                if (failing.get()) throw TmdbException(TmdbError.Server("503"))
                Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
            }),
        )
        compose.onAllNodesWithText(LOAD_FAILED).assertCountEquals(1)
        compose.onAllNodesWithText(TRY_AGAIN).assertCountEquals(1)
        assertTrue("no rows' headers", !exists("POPULAR"))
        failing.set(false)
        compose.onNodeWithText(TRY_AGAIN).performClick()
        compose.waitUntil(5_000) { exists("TRENDING THIS WEEK") }
    }

    @Test
    fun aGenreNoListHasSaysSoAndClearFiltersBringsTheRowsBack() {
        showTab(
            FakeDiscover(answer = { q, from ->
                Aged(if (q.filters.genre == 99) DiscoverPage(emptyList(), from, 1) else FakeDiscover.page(q, from, 3), Duration.ZERO)
            }),
        )
        compose.onNodeWithText("Documentary").performClick()
        compose.waitUntil(5_000) { exists(NOTHING_MATCHES) }
        compose.onNodeWithText(CLEAR_FILTERS).performClick()
        compose.waitUntil(5_000) { exists("TRENDING THIS WEEK") }
        assertTrue(!exists(NOTHING_MATCHES))
    }
}

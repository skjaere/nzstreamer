package io.skjaere.usenet.app.discover

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Discover in the phone's app, over invented lists (FakeDiscover) and an invented show's page (FakeTmdb): its place in
 * the bar after Home, a card's page and "+ Collection" there marking the card on the way back, See all and Back, and
 * without a key the card that leads to Settings › Metadata.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = HomeContentShown()
    private val container get() = compose.activity.container
    private var keyBefore: String? = null
    private var fakeDiscover: FakeDiscover? = null
    private var tmdb: FakeTmdb? = null

    /** The first show of Trending's first page: "Harbor Lights 1". */
    private val first = FakeDiscover.id(DiscoverList.Trending, 1, 0)
    private val firstCard = hasContentDescription("Harbor Lights 1, 2021, show")
    private val firstMarked = hasContentDescription("Harbor Lights 1, 2021, show, in your collection")
    private var collectedBefore = false

    private val show = ShowDetails(
        first, "Harbor Lights 1", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 2, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 2, "Episode 2", LocalDate.of(2021, 3, 8)), nextEpisode = null, ids = ExternalIds(),
    )

    @Before
    fun setUp() {
        home.show()
        runBlocking {
            keyBefore = container.settings.tmdbKey()
            // The lists and the page are the fakes': any key will do.
            if (keyBefore == null) container.settings.setTmdbKey("discover-device-test")
            collectedBefore = container.library.isCollected(first).first()
        }
        fakeDiscover = FakeDiscover().install()
        tmdb = FakeTmdb(
            shows = mapOf(first to show),
            seasons = { id, n -> if (id == first) Season(first, n, null, null, (1..2).map { Episode(n, it, "Episode $it", null, LocalDate.of(2021, 3, 1), 45, null) }) else null },
        )
        compose.setContent { AppTheme { AppNav() } }
    }

    @After
    fun restore() {
        tmdb?.close()
        fakeDiscover?.close()
        runBlocking {
            if (!collectedBefore) container.library.removeShow(first)
            container.settings.setTmdbKey(keyBefore)
        }
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    /** The bar's item for [label]. */
    private fun barItem(label: String) = hasText(label) and hasClickAction()

    private fun openDiscover() {
        waitFor(barItem(DISCOVER), 15_000)
        compose.onNode(barItem(DISCOVER)).performClick()
        waitFor(firstCard)
    }

    /** Each list's first page has its own Harbor Lights 1: the first is Trending's. */
    private fun clickFirstCard() = compose.onAllNodes(firstCard)[0].performClick()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun discoverIsInTheBarAfterHome() {
        waitFor(barItem(DISCOVER), 15_000)
        val x = listOf("Home", DISCOVER, "Collection", "Search").map { compose.onNode(barItem(it)).fetchSemanticsNode().boundsInRoot.left }
        assertTrue("the bar's order: $x", x == x.sorted())
    }

    @Test
    fun aCardOpensItsPageAndPlusCollectionThereMarksItOnTheWayBack() {
        openDiscover()
        clickFirstCard()
        waitFor(hasText("+ Collection"))
        compose.onNodeWithText("+ Collection").performClick()
        waitFor(hasText("In collection ✓"))
        back()
        waitFor(firstMarked)
        assertTrue("the mark is the card's line", exists(firstMarked))
        // Not in the recent searches: browsing isn't searching.
        assertTrue(runBlocking { container.library.recentSearches.first() }.none { it.query == "Harbor Lights 1" })
    }

    @Test
    fun seeAllOpensTheWholeListAndBackComesBackToTheTab() {
        openDiscover()
        compose.onAllNodesWithText(SEE_ALL)[0].performClick()
        waitFor(hasText("Trending · Shows"))
        compose.onNodeWithText("This week ▾").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasText("TRENDING THIS WEEK"))
        assertTrue(!exists(hasText("Trending · Shows")))
    }

    @Test
    fun aPosterOnSeeAllOpensItsPageAndPlusCollectionThereMarksItOnTheWayBack() {
        openDiscover()
        compose.onAllNodesWithText(SEE_ALL)[0].performClick()
        waitFor(hasText("Trending · Shows"))
        clickFirstCard()
        waitFor(hasText("+ Collection"))
        compose.onNodeWithText("+ Collection").performClick()
        waitFor(hasText("In collection ✓"))
        back()
        waitFor(hasText("Trending · Shows"))
        waitFor(firstMarked)
    }

    @Test
    fun withoutAKeyTheTabLeadsToSettingsMetadata() {
        runBlocking { container.settings.setTmdbKey(null) }
        waitFor(barItem(DISCOVER), 15_000)
        compose.onNode(barItem(DISCOVER)).performClick()
        waitFor(hasText(NO_KEY_TITLE))
        assertTrue("nothing asked of the lists without a key", fakeDiscover!!.asked.isEmpty())
        compose.onNodeWithText(ADD_KEY).performClick()
        waitFor(hasText("Metadata"))
        back()
        waitFor(hasText(NO_KEY_TITLE))
    }
}

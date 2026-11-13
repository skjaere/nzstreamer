package io.skjaere.usenet.app.discover

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvPage
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A list's See all page on a TV (forced on the phone emulator), with a remote, over invented lists (FakeDiscover): the
 * first pill's first focus, the pills' menus (no year or service for shows), a pick keeping the focus on its pill, the
 * posters paged as the D-pad and the CH keys near their end up to the cap, a page further down that fails, and a filter
 * nothing matches.
 */
@RunWith(AndroidJUnit4::class)
class TvDiscoverListTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    private fun showList(query: DiscoverQuery, source: FakeDiscover) {
        val deps = DiscoverDeps(
            hasTmdbKey = flowOf(true), showArtwork = flowOf(false), language = flowOf(null),
            collectedShows = flowOf(emptySet()), collectedMovies = flowOf(emptySet()), source = source,
        )
        compose.setTvPage { DiscoverListScreen(query, onBack = {}, onShow = {}, onMovie = {}, onMetadata = {}, deps = { deps }) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The open menu's items, by their words. */
    private fun menuItems(): List<String> =
        compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { t -> t.text } }

    private val trending = DiscoverQuery(MediaType.Tv, DiscoverList.Trending)

    @Test
    fun theFirstFocusIsTheFirstPillAndAShowsListHasNoYearOrServicePill() {
        showList(trending, FakeDiscover())
        remote.waitForFocus("Period: This week")
        assertTrue(exists(hasText("Trending · Shows")))
        assertTrue(exists(hasContentDescription("Genre: $ALL_GENRES")))
        assertTrue(exists(hasContentDescription("Language: $ANY_LANGUAGE")))
        assertTrue("no year for shows", !exists(hasContentDescription("Year: Any year")))
        assertTrue("no service for shows", !exists(hasContentDescription("Service: $ANY_SERVICE")))
        assertTrue(exists(hasText("Trending this week · $FROM_TMDB")))
    }

    @Test
    fun aMoviesListHasTheYearAndServicePillsWithJustWatchsCredit() {
        showList(DiscoverQuery(MediaType.Movie, DiscoverList.Popular), FakeDiscover())
        remote.waitForFocus("Genre: $ALL_GENRES")
        assertTrue(exists(hasContentDescription("Year: Any year")))
        assertTrue(exists(hasContentDescription("Service: $ANY_SERVICE")))
        assertTrue(exists(hasText(JUSTWATCH)))
    }

    @Test
    fun aPillOpensItsMenuItsFirstChoiceFocusedAndAPickKeepsTheFocusOnThePill() {
        val source = FakeDiscover()
        showList(trending, source)
        remote.waitForFocus("Period: This week")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Genre: $ALL_GENRES", remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(ALL_GENRES)
        assertEquals(listOf(ALL_GENRES) + FakeDiscover.GENRES.map { it.name }, menuItems())
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Genre: Drama")
        compose.waitUntil(5_000) { exists(hasText("Drama trending this week · $FROM_TMDB")) }
        assertEquals(18, source.asked.last { it.second == 1 }.first.filters.genre)
    }

    @Test
    fun theLanguagesMenuIsTheCappedList() {
        showList(trending, FakeDiscover())
        remote.waitForFocus("Period: This week")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Language: $ANY_LANGUAGE", remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(ANY_LANGUAGE)
        assertEquals(listOf(ANY_LANGUAGE) + FakeDiscover.LANGUAGES.map { it.name }, menuItems())
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Language: $ANY_LANGUAGE")
    }

    @Test
    fun downWalksIntoThePostersAndTheNextPagesComeAsItNearsTheEnd() {
        val source = FakeDiscover(pages = 5)
        showList(trending, source)
        remote.waitForFocus("Period: This week")
        // Down from the pills: the poster under the focus, on the first line.
        assertTrue("on the first line; focus on ${remote.focused()}", remote.press(KeyEvent.KEYCODE_DPAD_DOWN).endsWith(" 1, 2021, show"))
        // Line by line, down past the first page's three lines: the next pages come ahead of the focus.
        repeat(8) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitForIdle()
        val pages = source.asked.map { it.second }
        assertTrue("pages asked: $pages", pages.size >= 3 && pages == pages.distinct() && pages == (1..pages.size).toList())
        assertTrue("on a poster still; focus on ${remote.focused()}", remote.focused().endsWith(", 2021, show"))
    }

    @Test
    fun theChannelKeysPageThreeLinesAtATimeOnToTheCapAndBackUpToThePills() {
        val source = FakeDiscover(answer = { q, from -> Aged(FakeDiscover.page(q, from, DiscoverPage.MAX_PAGES, capped = true), Duration.ZERO) })
        showList(DiscoverQuery(MediaType.Tv, DiscoverList.Popular), source)
        remote.waitForFocus("Genre: $ALL_GENRES")
        assertEquals("from the pills: the first poster", "Harbor Lights 1, 2021, show", remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("three lines down", "Lantern Row 2, 2021, show", remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        repeat(120) {
            if (exists(hasText(SHOWING_FIRST))) return@repeat
            remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        }
        compose.waitUntil(10_000) { exists(hasText(SHOWING_FIRST)) }
        assertEquals((1..DiscoverPage.MAX_PAGES).toList(), source.asked.map { it.second })
        // Toward the top again: line by line three at a time, then from the first line up to the pills.
        repeat(200) {
            if (remote.focused() == "Genre: $ALL_GENRES") return@repeat
            remote.press(KeyEvent.KEYCODE_PAGE_UP)
        }
        assertEquals("Genre: $ALL_GENRES", remote.focused())
    }

    @Test
    fun aPageFurtherDownThatFailsHasTryAgainUnderThePostersAndGoesOnFromIt() {
        val failing = AtomicBoolean(true)
        val source = FakeDiscover(answer = { q, from ->
            if (from == 2 && failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        })
        showList(trending, source)
        remote.waitForFocus("Period: This week")
        // Page 2 is asked as the first screen shows, within three lines of the first's end: its failure is under the posters.
        compose.waitUntil(5_000) { source.asked.any { it.second == 2 } }
        val reached = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, TRY_AGAIN, max = 6)
        assertEquals(TRY_AGAIN, reached)
        assertTrue(exists(hasText(LOAD_FAILED)))
        failing.set(false)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(hasText(LOAD_FAILED)) }
        assertEquals(listOf(1, 2, 2), source.asked.map { it.second }.take(3))
    }

    @Test
    fun theFootersTryAgainLeavesTheFocusOnTheLastPosterAndHandsItToTheFirstNewOne() {
        val failing = AtomicBoolean(true)
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val source = FakeDiscover(answer = { q, from ->
            if (from == 2) {
                gate.get()?.await()
                if (failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            }
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        })
        showList(trending, source)
        remote.waitForFocus("Period: This week")
        compose.waitUntil(5_000) { source.asked.any { it.second == 2 } }
        assertEquals(TRY_AGAIN, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, TRY_AGAIN, max = 6))
        failing.set(false)
        val answer = CompletableDeferred<Unit>().also(gate::set)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(5) { compose.waitForIdle() }
        assertEquals("while the page is asked, the last poster", "The Ninth Lamp 1, 2021, show", remote.focused())
        answer.complete(Unit)
        remote.waitForFocus("Harbor Lights 2, 2021, show")
    }

    @Test
    fun theFirstPagesTryAgainPutsTheFocusOnTheFirstPill() {
        val failing = AtomicBoolean(true)
        showList(trending, FakeDiscover(answer = { q, from ->
            if (failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        remote.waitForFocus("Period: This week")
        compose.waitUntil(5_000) { exists(hasText(LOAD_FAILED)) }
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        failing.set(false)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription("Harbor Lights 1, 2021, show")) }
        repeat(5) { compose.waitForIdle() }
        assertEquals("Period: This week", remote.focused())
    }

    @Test
    fun aFilterNothingMatchesHasClearFiltersUnderThePillsAndItBringsTheListBack() {
        val source = FakeDiscover(answer = { q, from ->
            Aged(if (q.filters.language == "ko") DiscoverPage(emptyList(), from, 1) else FakeDiscover.page(q, from, 3), Duration.ZERO)
        })
        showList(trending, source)
        remote.waitForFocus("Period: This week")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(ANY_LANGUAGE)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Korean", max = 5)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Language: Korean")
        compose.waitUntil(5_000) { exists(hasText(NOTHING_MATCHES)) }
        assertEquals(CLEAR_FILTERS, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription("Harbor Lights 1, 2021, show")) }
        assertTrue(exists(hasContentDescription("Language: $ANY_LANGUAGE")))
        repeat(5) { compose.waitForIdle() }
        assertEquals("Clear filters gone, the first pill has the focus", "Period: This week", remote.focused())
    }
}

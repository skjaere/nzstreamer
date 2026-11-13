package io.skjaere.usenet.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
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
import java.time.LocalDate

/**
 * Search's preview on a phone, over invented titles ([JumpFixture], its indexers off): what is typed shows TMDB's
 * titles, and Enter closes the keyboard and keeps them, sending no search (a design decision: "Clicking enter or the
 * hour glass should not send a user to different results than they see in the preview"). Words TMDB has no titles
 * for show the fake indexer's releases after a pause, with no extra click. Search indexers, at the field's end, opens
 * the releases for the words, with TMDB's titles for them on top. A title opens its page. The recent searches say what
 * each was.
 */
@RunWith(AndroidJUnit4::class)
class SearchPreviewDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = JumpFixture()
    private val library get() = compose.activity.container.library

    @Before
    fun setUp() = fixture.open()

    @After
    fun restore() = fixture.close()

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private lateinit var nav: NavHostController

    /** The bottom bar's tab [name]. */
    private fun tab(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The app, its Search tab opened; with [restoration], recreated as [StateRestorationTester] does. */
    private fun openSearch(restoration: StateRestorationTester? = null) {
        val app = @Composable {
            nav = rememberNavController()
            AppTheme { AppNav(nav = nav) }
        }
        if (restoration != null) restoration.setContent(app) else compose.setContent(app)
        compose.waitUntil(15_000) { exists(tab("Search")) }
        compose.onNode(tab("Search")).performClick()
        compose.waitUntil(10_000) { exists(hasSetTextAction()) }
    }

    private fun fieldFocused() = exists(hasSetTextAction() and isFocused())

    /** Long enough for the preview's pause after typing (a second) and what would follow it. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /** [test] with the fake indexer (10.0.2.2:8089) switched on; it goes again after, with its key and the searches made. */
    private fun withFakeIndexer(test: () -> Unit) {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        val settings = compose.activity.container.settings
        val searchedBefore = runBlocking { library.recentSearches.first().map { it.key }.toSet() }
        try {
            runBlocking { settings.saveIndexer(FAKE_INDEXER, "Fake (search preview test)", "http://10.0.2.2:8089", "fake-indexer-key") }
            test()
        } finally {
            runBlocking {
                if (settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
                library.recentSearches.first().filter { it.key !in searchedBefore }.forEach { library.removeSearch(it.key) }
            }
        }
    }

    /** A release group's row in Search's results. */
    private val resultRow = hasText("release ›", substring = true) or hasText("releases ›", substring = true)

    @Test
    fun enterClosesTheKeyboardAndKeepsTheTitlesShown() {
        val searchesBefore = runBlocking { library.recentSearches.first() }
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val title = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(title) }

        compose.onNode(hasSetTextAction()).performImeAction()
        settle()
        assertFalse("the field kept the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
        assertTrue("the titles the preview showed went", exists(title))
        // A search's results end with what the indexers answered, even with none switched on.
        assertFalse("Enter sent a search", exists(searchFootnote))
        assertEquals("Enter remembered a search", searchesBefore, runBlocking { library.recentSearches.first() })
    }

    /**
     * Words TMDB has no titles for (none of the fixture's are named so): after a pause the preview shows the indexer's
     * releases for them, under a header that says why. Enter keeps them, and remembers the search; typing on drops them.
     */
    @Test
    fun afterAPauseWordsWithoutTitlesShowTheIndexersReleases() = withFakeIndexer {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        val header = hasText("No titles on TMDB for “$QUERY” · releases from your indexers")
        compose.waitUntil(20_000) { exists(header) && exists(resultRow) }

        compose.onNode(hasSetTextAction()).performImeAction()
        settle()
        assertTrue("Enter dropped the preview's releases", exists(header) && exists(resultRow))
        assertFalse("the field kept the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
        assertEquals("the search settled on, remembered", QUERY, runBlocking { library.recentSearches.first().first().query })

        compose.onNode(hasSetTextAction()).performTextReplacement("$QUERY tide")
        compose.waitUntil(5_000) { !exists(header) }
        assertFalse("typing on kept the releases for the words before", exists(resultRow))
    }

    /**
     * The recent searches say what each was: a title opened opens its page again, and Back from there finds the list
     * as it was, the field (and its keyboard) not taken; a search of words is one of the indexers.
     */
    @Test
    fun recentSearchesSayWhatTheyWereAndATitleOpensItsPageAgain() {
        val opened = SearchPick(TitleType.Tv, JumpFixture.GLASSWATER, "Glasswater Bay")
        runBlocking {
            library.addSearch(WORDS)
            delay(5)
            library.addTitle(opened)
        }
        try {
            openSearch()
            val title = hasText("Glasswater Bay") and hasText("TV series") and hasClickAction()
            val search = hasText(WORDS) and hasText("Indexer search") and hasClickAction()
            compose.waitUntil(10_000) { exists(title) && exists(search) }

            compose.onNode(title).performClick()
            compose.waitUntil(5_000) { compose.runOnIdle { nav.currentDestination?.hasRoute(ShowRoute::class) == true } }
            assertEquals("the title opened again goes to the top", RecentSearch.titleKey(opened), runBlocking { library.recentSearches.first().first().key })

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { exists(title) }
            settle()
            assertFalse("Back from the page: the field took the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
            assertTrue(exists(search))
        } finally {
            runBlocking {
                library.removeSearch(RecentSearch.titleKey(opened))
                library.removeSearch(WORDS)
            }
        }
    }

    /**
     * The activity recreated while typing (the phone turned): the field takes the focus again, and its keyboard, as the
     * words typed and their titles show again. Search hadn't left for anything that Back would come back from.
     */
    @Test
    fun recreatedWhileTypingTheFieldHasTheFocusAgain() {
        val restoration = StateRestorationTester(compose)
        openSearch(restoration)
        compose.onNode(hasSetTextAction()).performTextInput("glass")
        val title = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(title) }
        compose.waitUntil(5_000) { fieldFocused() }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { exists(title) }
        settle()
        assertTrue("recreated while typing: the field lost the focus, and its keyboard", fieldFocused())
    }

    /**
     * Back on the Search tab from another tab: the field takes the focus again (and its keyboard), as Search opens to
     * type in. Only Back from what Search opened (a title's page) leaves it alone.
     */
    @Test
    fun backOnTheSearchTabTheFieldHasTheFocusAgain() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glass")
        val title = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(title) }

        compose.onNode(tab("Home")).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { nav.currentDestination?.hasRoute(HomeRoute::class) == true } }
        compose.onNode(tab("Search")).performClick()
        compose.waitUntil(10_000) { exists(title) }
        settle()
        assertTrue("back on the Search tab: the field not focused, its keyboard closed", fieldFocused())
    }

    /** Clear after a search of the indexers: the field takes the focus to type again, as before Search kept its list on Enter. */
    @Test
    fun clearAfterASearchGivesTheFieldTheFocus() = withFakeIndexer {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        compose.onNode(indexersButton(QUERY)).performClick()
        val releases = hasText("Releases for “$QUERY” on your indexers")
        compose.waitUntil(20_000) { exists(releases) }
        settle()
        assertFalse("the search sent: the field kept the focus", fieldFocused())

        compose.onNode(hasContentDescription("Clear search")).performClick()
        compose.waitUntil(5_000) { !exists(releases) }
        settle()
        assertTrue("Clear: the field not focused, its keyboard closed", fieldFocused())
    }

    /** Waits for the page of [route]'s type to show, else fails saying where the app is. */
    private fun waitForPage(route: kotlin.reflect.KClass<*>) {
        try {
            compose.waitUntil(5_000) { compose.runOnIdle { nav.currentDestination?.hasRoute(route) == true } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("no ${route.simpleName} opened; the app is on ${compose.runOnIdle { nav.currentDestination?.route }}", e)
        }
    }

    /**
     * A title in the Titles opens its page, the Show page or the Movie page (whose Play searches the indexers by the
     * title's ids): no search of the indexers here, and it is remembered as a title opened. Back finds the titles as
     * they were, the field (and its keyboard) not taken.
     */
    @Test
    fun aTitleInTheTitlesOpensItsPage() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val show = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(show) }

        compose.onNode(show).performClick()
        waitForPage(ShowRoute::class)
        assertEquals(JumpFixture.GLASSWATER, compose.runOnIdle { nav.currentBackStackEntry?.toRoute<ShowRoute>()?.tmdbId })
        assertEquals(
            "remembered as the title opened", RecentSearch.titleKey(SearchPick(TitleType.Tv, JumpFixture.GLASSWATER, "Glasswater Bay")),
            runBlocking { library.recentSearches.first().first().key },
        )

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { exists(show) }
        settle()
        assertFalse("Back from the page: the field took the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
        assertFalse("a search was sent", exists(searchFootnote))

        // A movie, its Movie page.
        compose.onNode(hasSetTextAction()).performTextReplacement("lantern")
        val movie = hasText("Lantern Field") and hasClickAction()
        compose.waitUntil(10_000) { exists(movie) }
        compose.onNode(movie).performClick()
        waitForPage(MovieRoute::class)
        assertEquals(JumpFixture.LANTERN, compose.runOnIdle { nav.currentBackStackEntry?.toRoute<MovieRoute>()?.tmdbId })
    }

    /** What sends the words typed to the indexers, at the field's end. */
    private fun indexersButton(words: String) = hasContentDescription("Search indexers for “$words”") and hasClickAction()

    /**
     * Search indexers sits at the end of the field, not in a row of its own under it: it searches the indexers for the
     * words as typed and opens their releases, grouped by title, under a header that says so. The keyboard closes.
     */
    @Test
    fun searchIndexersAtTheFieldsEndOpensTheReleasesForTheWords() = withFakeIndexer {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        val button = indexersButton(QUERY)
        compose.waitUntil(5_000) { exists(button) }
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val at = compose.onNode(button).fetchSemanticsNode().boundsInRoot
        assertTrue("Search indexers isn't at the field's end: the field at $field, it at $at", at.left >= field.right - 1f && at.center.y in field.top..field.bottom)
        assertFalse("a row of its own under the field searches the indexers", exists(hasText("on all indexers", substring = true)))

        compose.onNode(button).performClick()
        compose.waitUntil(20_000) { exists(hasText("Releases for “$QUERY” on your indexers")) && exists(resultRow) }
        assertFalse("the field kept the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
        assertFalse("a Titles strip, though TMDB has no titles for the words", exists(hasText("TITLES")))
    }

    /** Words TMDB has a title for: their releases have a small Titles strip on top, whose title opens its page. */
    @Test
    fun theReleasesForWordsTmdbKnowsHaveATitlesStripOnTopWhoseTitleOpensItsPage() = withFakeIndexer {
        val harborLights = TitleSuggestion(MediaType.Tv, HARBOR, harbor.name, null, harbor.year, null, null)
        FakeTmdb(shows = mapOf(HARBOR to harbor), suggestions = { query -> listOf(harborLights).takeIf { query.lowercase() in QUERY } }).use {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(QUERY)
            val title = hasText("Harbor Lights") and hasClickAction()
            compose.waitUntil(10_000) { exists(title) }

            compose.onNode(indexersButton(QUERY)).performClick()
            compose.waitUntil(20_000) { exists(hasText("Releases for “$QUERY” on your indexers")) && exists(resultRow) }
            compose.waitUntil(5_000) { exists(hasText("TITLES")) && exists(title) }
            val strip = compose.onNode(title).fetchSemanticsNode().boundsInRoot
            val releases = compose.onAllNodes(resultRow).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
            assertTrue("the Titles strip isn't on top of the releases: it ends at ${strip.bottom}, they start at $releases", strip.bottom <= releases)

            compose.onNode(title).performClick()
            waitForPage(ShowRoute::class)
            assertEquals(HARBOR, compose.runOnIdle { nav.currentBackStackEntry?.toRoute<ShowRoute>()?.tmdbId })
        }
    }

    /** The fake catalogue's show, by its name and id there; what TMDB would say of it invented here. */
    private val harbor = ShowDetails(
        HARBOR, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 2, LocalDate.now().minusYears(1), null)),
        lastEpisode = EpisodeSummary(1, 2, "Low Water", LocalDate.now().minusYears(1).plusDays(7)), nextEpisode = null,
        ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    /** The line under a search's results: how many indexers answered. */
    private val searchFootnote = hasText("Sorted by best playable release", substring = true) or hasText("Showing results from", substring = true)

    private companion object {
        const val SETTLE_MS = 1_500L
        const val FAKE_INDEXER = "search-preview-test"
        /** The fake catalogue's show, which the fixture's TMDB has no title for. */
        const val QUERY = "harbor lights"
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val HARBOR = 900002
        /** Words searched before, as typed. */
        const val WORDS = "slate harbour"
    }
}

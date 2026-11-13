package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.JumpFixture
import io.skjaere.usenet.app.MovieRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.RecentKind
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * What the content shows changing under a TV remote's focus (a tester's report: "When searching and clicking a
 * result on the TV version, the side menu opens"): a search sent with Search indexers, Enter in the field, a TMDB
 * title opened from the Titles or a search's Titles strip, Search on a picked title (its card), the preview's own
 * releases, OK on a release group, a card's Open to its Show or Movie page, and Back from each; from the field, the
 * remote's arrows to Search indexers, Clear and the first title. Each time the focus goes into the content, never to
 * the rail, which stays narrow (a page has no rail beside it); Back from each page goes to the element that opened it;
 * the user's Left still opens the rail. The app in the TV shell as a TV shows it (forced on a phone emulator), over
 * invented titles ([JumpFixture]) and, for releases, the fake indexer.
 */
@RunWith(AndroidJUnit4::class)
class TvPageFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val fixture = JumpFixture()
    private lateinit var nav: NavHostController
    private lateinit var focusManager: FocusManager

    @Before
    fun showTheApp() {
        fixture.open()
        instrumentation.setInTouchMode(false)
        compose.setTvContent {
            nav = rememberNavController()
            focusManager = LocalFocusManager.current
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        compose.activity.container.playback.dismiss()
        fixture.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun railOpen(): Boolean = RAIL.any { remote.isFocused(railItem(it)) }

    /** Once the screen is up and has had its own first focus, and the shell its turn after it. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /** On a tab's screen: the focus in the content, on something; not on the rail (which would widen over the content), not nowhere. */
    private fun assertFocusInTheContent(where: String) {
        settle()
        assertFalse("$where: the rail has the focus (and is widened over the content)", railOpen())
        assertNotEquals("$where: nothing has the focus", "(nothing)", remote.focused())
    }

    /**
     * On a page (not a tab): something on it has the focus. The shell shows the rail beside the tabs' own screens only,
     * so there is none here to take the focus or widen.
     */
    private fun assertFocusOnThePage(where: String) {
        settle()
        assertFalse("$where: the rail is shown beside a page", exists(railItem("Search")))
        assertNotEquals("$where: nothing has the focus", "(nothing)", remote.focused())
    }

    private fun current(route: kotlin.reflect.KClass<*>) = compose.runOnIdle { nav.currentDestination?.hasRoute(route) == true }

    /**
     * [action], then [FRAMES] frames one at a time: the rail never has the focus meanwhile. The settled checks come
     * after the shell has put the focus into the content, which it would also do after the rail had widened for a
     * few frames; this one sees those frames.
     */
    private fun assertTheRailStaysNarrow(where: String, action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            action()
            repeat(FRAMES) { frame ->
                assertFalse("$where, frame $frame: the rail has the focus (and is widened over the content)", railOpen())
                compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /**
     * The user's Left from Search's right pane, until the rail: into the left pane (Search on a TV has two), by way of
     * what is left of the focus on its way (the mic, where a recognizer resolves), and from there to the rail. True once
     * on the rail.
     */
    private fun leftToTheRail(): Boolean {
        repeat(4) { if (!railOpen()) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        return railOpen()
    }

    /** Search opened from the rail, its field's gate focused (OK to type), with no keyboard. */
    private fun openSearch() {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("on the rail's Search; focus on ${remote.focused()}", remote.isFocused(railItem("Search")))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Search's gate never had the focus; it is on \"${remote.focused()}\"", e)
        }
    }

    /** Search's field focused: its gate, or the field itself while typing. */
    private fun fieldFocused() = exists(searchFieldFocused)

    /** The fixture's show and movie, as a pick remembers them. */
    private val glasswater = SearchPick(TitleType.Tv, JumpFixture.GLASSWATER, "Glasswater Bay")
    private val lantern = SearchPick(TitleType.Movie, JumpFixture.LANTERN, "Lantern Field")

    /**
     * Search opened on [title], as the play sheet's Search opens it (and a recent search picked from TMDB): the
     * indexers searched by the title's ids, its card on top once its details are in.
     */
    private fun openPicked(title: SearchPick) {
        compose.runOnIdle { nav.switchTab(searchRoute(title.name, title), restore = false) }
    }

    /** [title]'s card, Search opened on it: its Open shows. */
    private fun showCard(title: SearchPick) {
        openPicked(title)
        compose.waitUntil(10_000) { exists(open) }
    }

    /** A title card's Open. */
    private val open = hasText("Open") and hasClickAction()

    /** On the card's Open: not on "+ Collection", which Compose's way in would pick (wider), and which writes and leaves Search. */
    @Test
    fun aShowsCardPutsTheFocusOnItsOpen() {
        showCard(glasswater)
        assertFocusInTheContent("the card for the show picked")
        assertEquals("the focus on the card's Open", "Open", remote.focused())
    }

    @Test
    fun aMoviesCardPutsTheFocusOnItsOpen() {
        showCard(lantern)
        assertFocusInTheContent("the card for the movie picked")
        assertEquals("the focus on the card's Open", "Open", remote.focused())
    }

    /**
     * Left from a card's Open, by way of the left pane, to the rail and Right back: on Open again, not on what Compose's
     * own move right of the rail finds (the card's "+ Collection"). Then another title, typed in the field and opened
     * from the Titles: after that visit to the rail, its page takes the focus.
     */
    @Test
    fun afterTheRailACardsOpenHasTheFocusBackAndATitleOpenedHasItsPage() {
        showCard(glasswater)
        assertFocusInTheContent("the card for the show picked")
        assertEquals("the focus on the show's Open", "Open", remote.focused())
        assertTrue("the user's Left opens the rail; focus on ${remote.focused()}", leftToTheRail())
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right from the rail: back on the show's Open", "Open", remote.focused())

        // Up to the top of the right pane, Left into the left pane (where the focus was there: the mic, on the way to the
        // rail, where a recognizer resolves), the field's gate, and another title typed there and opened.
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        repeat(2) { if (!fieldFocused() && remote.focused() != "Search by voice") remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        if (remote.focused() == "Search by voice") remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Up and Left from Open: on the field; focus on ${remote.focused()}", fieldFocused())
        compose.onNode(hasSetTextAction()).performTextReplacement("lantern")
        val row = hasText("Lantern Field") and hasClickAction()
        compose.waitUntil(10_000) { exists(row) }
        compose.onNode(row).requestFocus()
        remote.waitForFocus("Lantern Field")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { current(MovieRoute::class) }
        compose.waitUntil(10_000) { exists(hasText("Lantern Field")) }
        assertFocusOnThePage("the Movie page of the title opened after the rail")
    }

    /**
     * The card after the title's details, held back a while: the results' footnote has the focus meanwhile, and lets it
     * go to the card's Open as the card shows, not to its "+ Collection".
     */
    @Test
    fun aCardThatComesLateTakesTheFocusOnItsOpen() {
        val answer = CompletableDeferred<Unit>()
        val fake = TmdbLookup.showAged
        TmdbLookup.showAged = { c, id ->
            if (id == JumpFixture.GLASSWATER) answer.await()
            fake(c, id)
        }
        try {
            openPicked(glasswater)
            assertFocusInTheContent("Search on the title, its details still to come")
            assertTrue("the focus on the results' footnote; it is on ${remote.focused()}", remote.focused().startsWith("Showing results from"))

            answer.complete(Unit)
            compose.waitUntil(10_000) { exists(open) }
            assertFocusInTheContent("the card once the details have come")
            assertEquals("the focus on the card's Open", "Open", remote.focused())
        } finally {
            answer.complete(Unit)
            TmdbLookup.showAged = fake
        }
    }

    /**
     * A title in the Titles, OK on its row: its page (whose Play searches the indexers by the title's ids), with the
     * focus there; Back goes to its row, not to the field (whose keyboard would open).
     */
    @Test
    fun aTitleInTheTitlesOpensItsPageAndBackGoesToItsRow() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val row = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(row) }
        compose.onNode(row).requestFocus()
        remote.waitForFocus("Glasswater Bay")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { current(ShowRoute::class) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("OK on the title opened no Show page; the app is on ${compose.runOnIdle { nav.currentDestination?.route }}", e)
        }
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay")) }
        assertFocusOnThePage("the Show page of the title opened")

        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { current(SearchRoute::class) }
        assertFocusInTheContent("Back from the Show page")
        assertTrue("Back from the Show page: the focus on the row that opened it; it is on ${remote.focused()}", remote.focused().startsWith("Glasswater Bay"))
        assertFalse("the field took the focus (its keyboard opens)", fieldFocused())
    }

    /** The window's default focus, handed out as the Titles go with Search, isn't taken by the rail. */
    @Test
    fun theRailStaysNarrowFrameByFrameAsATitleIsOpened() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val row = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(row) }
        compose.onNode(row).requestFocus()
        remote.waitForFocus("Glasswater Bay")
        assertTheRailStaysNarrow("opening a title") { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER) }
        compose.waitUntil(5_000) { current(ShowRoute::class) }
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay")) }
        assertFocusOnThePage("the Show page of the title opened")
    }

    /** Left while the title Search is on is still looked up (nothing of it shown yet) is the user's: the rail stays as its card shows. */
    @Test
    fun leftWhileACardsTitleIsLookedUpKeepsTheRailOnceTheCardShows() {
        // The show's details held back: its card waits for them.
        val answer = CompletableDeferred<Unit>()
        val fake = TmdbLookup.showAged
        TmdbLookup.showAged = { c, id ->
            if (id == JumpFixture.GLASSWATER) answer.await()
            fake(c, id)
        }
        try {
            openPicked(glasswater)
            settle()
            assertFalse("the card before its details", exists(open))
            leftToTheRail()
            assertTrue("the user's Left opens the rail at Search; focus on ${remote.focused()}", remote.isFocused(railItem("Search")))

            answer.complete(Unit)
            compose.waitUntil(10_000) { exists(open) }
            settle()
            assertTrue("the card shown, the rail keeps the focus the user moved there; it is on ${remote.focused()}", remote.isFocused(railItem("Search")))
        } finally {
            answer.complete(Unit)
            TmdbLookup.showAged = fake
        }
    }

    @Test
    fun openOnAShowsCardGoesToItsPageWithTheFocusThereAndBackToOpen() {
        showCard(glasswater)
        compose.onNode(open).requestFocus()
        remote.waitForFocus("Open")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { current(ShowRoute::class) }
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay")) }
        assertFocusOnThePage("the Show page")

        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { current(SearchRoute::class) }
        assertFocusInTheContent("Back from the Show page")
        assertEquals("Back from the Show page: the focus on the Open that opened it", "Open", remote.focused())
    }

    /** The Show page's details come after its fade (a cold cache): nothing to focus meanwhile, the page's once they show. */
    @Test
    fun theShowPageTakesTheFocusOnceItsDetailsArrive() {
        showCard(glasswater)
        compose.onNode(open).requestFocus()
        remote.waitForFocus("Open")
        val answer = CompletableDeferred<Unit>()
        val fake = TmdbLookup.showAged
        TmdbLookup.showAged = { c, id ->
            if (id == JumpFixture.GLASSWATER) answer.await()
            fake(c, id)
        }
        try {
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { current(ShowRoute::class) }
            settle()
            assertFalse("the page before its details", exists(hasText("Glasswater Bay")))

            answer.complete(Unit)
            compose.waitUntil(10_000) { exists(hasText("Glasswater Bay")) }
            assertFocusOnThePage("the Show page once its details arrive")
        } finally {
            answer.complete(Unit)
            TmdbLookup.showAged = fake
        }
    }

    /** The Show page's Choose release: "Finding releases…", then the list, with the focus in it once it shows. */
    @Test
    fun theShowPagesReleaseListTakesTheFocusOnceItShows() {
        withFakeIndexer {
            FakeTmdb(shows = mapOf(HARBOR to harbor), seasons = { id, number -> harborSeason.takeIf { id == HARBOR && number == 1 } }).use {
                compose.runOnIdle { nav.navigate(ShowRoute(HARBOR)) }
                val episode = hasText("Tide Clock", substring = true) and hasClickAction()
                compose.waitUntil(15_000) { exists(episode) }
                // Past the page's first 400 ms, whose OKs a TV takes for the one that opened it (play/OkGuard.kt).
                settle()
                compose.onNode(episode).requestFocus()
                remote.waitForFocus("Tide Clock")
                // The ⋮ beside the row, a button of its own (#160).
                assertEquals("Right from the episode", "More for episode 1", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                // The menu's item: the page has a Choose release of its own, for the next-up episode.
                val choose = hasText("Choose release") and hasClickAction() and hasAnyAncestor(isPopup())
                compose.waitUntil(5_000) { exists(choose) }
                // Its menu's item by semantics, which leaves touch mode off: what is tested is the list's focus.
                compose.onNode(choose).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(30_000) { exists(hasText("Size") and hasClickAction()) }
                assertFocusOnThePage("the release list chosen on the Show page")
            }
        }
    }

    /** The Movie page held on its spinner a while: its own first focus, Play, once its row has loaded. */
    @Test
    fun openOnAMoviesCardGoesToItsPageWithTheFocusOnPlayOnceItHasLoadedAndBackToOpen() {
        showCard(lantern)
        compose.onNode(open).requestFocus()
        remote.waitForFocus("Open")
        // The movie's details held back: its page shows its spinner meanwhile.
        val answer = CompletableDeferred<Unit>()
        val fake = TmdbLookup.movieAged
        TmdbLookup.movieAged = { c, id ->
            if (id == JumpFixture.LANTERN) answer.await()
            fake(c, id)
        }
        try {
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { current(MovieRoute::class) }
            settle()
            // Nothing there can take the focus yet, and no rail beside a page to take it instead.
            assertFalse("the Movie page while it loads: the rail is shown beside it", exists(railItem("Search")))

            answer.complete(Unit)
            compose.waitUntil(10_000) { exists(hasText("Lantern Field")) }
            assertFocusOnThePage("the Movie page once it has loaded")
            assertTrue("the Movie page's own first focus, Play; it is on ${remote.focused()}", remote.focused().startsWith("Play"))
        } finally {
            answer.complete(Unit)
            TmdbLookup.movieAged = fake
        }

        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { current(SearchRoute::class) }
        assertFocusInTheContent("Back from the Movie page")
        assertEquals("Back from the Movie page: the focus on the Open that opened it", "Open", remote.focused())
    }

    /**
     * Runs [test] with the indexer [id] at [url] switched on, the fixture's indexers being off; it goes again after,
     * with its key and the searches made.
     */
    private fun withIndexer(id: String, name: String, url: String, test: () -> Unit) {
        val settings = compose.activity.container.settings
        val library = compose.activity.container.library
        val searchedBefore = runBlocking { library.recentSearches.first().map { it.key }.toSet() }
        try {
            runBlocking { settings.saveIndexer(id, name, url, "fake-indexer-key") }
            test()
        } finally {
            runBlocking {
                if (settings.indexer(id) != null) settings.deleteIndexer(id)
                library.recentSearches.first().filter { it.key !in searchedBefore }.forEach { library.removeSearch(it.key) }
            }
        }
    }

    /** [test] with the fake indexer (10.0.2.2:8089). */
    private fun withFakeIndexer(test: () -> Unit) {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        withIndexer(FAKE_INDEXER, "Fake (TV page focus test)", "http://10.0.2.2:8089", test)
    }

    /** [test] with an indexer listing [LISTED]'s releases for any search: a card each, more than a screen of them. */
    private fun withListingIndexer(test: () -> Unit) {
        ListingIndexer(LISTED.mapIndexed { i, name -> name to i + 1 }).use { listing -> withIndexer(LISTING_INDEXER, "Listing (TV page focus test)", listing.url, test) }
    }

    /** [test] with an indexer that never answers: a search sent to it shows its results' first lines, and nothing to play. */
    private fun withSilentIndexer(test: () -> Unit) {
        SilentIndexer().use { silent -> withIndexer(SILENT_INDEXER, "Silent (TV page focus test)", silent.url, test) }
    }

    /** A release group's "N releases ›" in Search's results, which opens its list. */
    private val resultRow = hasText("release ›", substring = true) or hasText("releases ›", substring = true)

    /** A release group's row, which plays (#160): where the focus goes in the results. */
    private val groupRow = playsAGroup

    /** What sends the words typed to the indexers, as Enter no longer does: Search indexers, at the field's end. */
    private val indexersButton = hasContentDescription("Search indexers for", substring = true) and hasClickAction()

    /** Search indexers pressed by semantics, which leave touch mode off. */
    private fun searchIndexers() {
        compose.onNode(indexersButton).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** [QUERY] typed and searched on the indexers: its results show. */
    private fun search() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        searchIndexers()
        compose.waitUntil(20_000) { exists(resultRow) }
    }

    /** As Search indexers sends the search, the field's focus goes: the rail doesn't take it. */
    @Test
    fun theRailStaysNarrowFrameByFrameAsASearchIsSent() {
        withFakeIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(QUERY)
            // Shown once there are words to search: there before the frames are stepped through.
            compose.waitUntil(5_000) { exists(indexersButton) }
            assertTheRailStaysNarrow("sending a search") { searchIndexers() }
            compose.waitUntil(20_000) { exists(resultRow) }
            assertFocusInTheContent("the results of the search sent")
        }
    }

    /**
     * The remote's arrow [direction] where the focus is, as a text field takes it: Compose's text field moves the focus
     * for a remote's arrows just so. (A key sent from the test is a keyboard's, whose arrows move the field's cursor;
     * the TV emulator delivers no remote's key sent from here.)
     */
    private fun arrow(direction: FocusDirection): String {
        compose.runOnIdle { focusManager.moveFocus(direction) }
        compose.waitForIdle()
        return remote.focused()
    }

    /** The field's keyboard put away, as Back does on a TV (its keys would take the remote's arrows); the field keeps the focus. */
    private fun hideKeyboard() {
        val window = compose.activity.window.decorView
        compose.runOnUiThread { compose.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.windowToken, 0) }
        compose.waitUntil(5_000) { compose.runOnIdle { ViewCompat.getRootWindowInsets(window)?.isVisible(WindowInsetsCompat.Type.ime()) != true } }
    }

    /**
     * From the field, with the remote: Right to the first title in one press, Up from there to
     * Search indexers at the top of the right pane, Right on to Clear (which a remote reaches too), and Left back to the
     * field. The rail never has the focus.
     */
    @Test
    fun fromTheFieldTheRemoteReachesTheFirstTitleSearchIndexersAndClear() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        hideKeyboard()
        assertTrue("the field has the focus; it is on ${remote.focused()}", fieldFocused())

        assertTrue("Right from the field: the first title; focus on ${remote.focused()}", arrow(FocusDirection.Right).startsWith("Glasswater Bay"))
        assertEquals("Up from the first title", "Search indexers for “glasswater”", arrow(FocusDirection.Up))
        assertEquals("Right from Search indexers", "Clear search", arrow(FocusDirection.Right))
        assertEquals("Left from Clear", "Search indexers for “glasswater”", arrow(FocusDirection.Left))
        arrow(FocusDirection.Left)
        assertTrue("Left from Search indexers: the field; focus on ${remote.focused()}", fieldFocused())
        assertFalse("the rail has the focus", railOpen())
    }

    /**
     * OK on Clear, reached with the remote: the words go, and the focus goes to the field's gate to type again; not down into
     * the recent searches, where a second OK would run one again. The rail stays narrow.
     */
    @Test
    fun okOnClearPutsTheFocusOnTheField() {
        withRecentSearches(RECENT_TOP) {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput("glasswater")
            compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
            hideKeyboard()
            assertTrue("Right from the field: the first title; focus on ${remote.focused()}", arrow(FocusDirection.Right).startsWith("Glasswater Bay"))
            assertEquals("Up from the first title", "Search indexers for “glasswater”", arrow(FocusDirection.Up))
            assertEquals("Right from Search indexers", "Clear search", arrow(FocusDirection.Right))

            assertTheRailStaysNarrow("OK on Clear") { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER) }
            compose.waitUntil(5_000) { exists(hasText(RECENT_TOP)) }
            assertFocusInTheContent("OK on Clear")
            assertTrue("the field has the focus; it is on ${remote.focused()}", fieldFocused())
        }
    }

    /** OK on Search indexers, reached with the remote: the results take the focus as they come, never the rail. */
    @Test
    fun okOnSearchIndexersPutsTheFocusInTheResults() {
        withFakeIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(QUERY)
            compose.waitUntil(5_000) { exists(indexersButton) }
            hideKeyboard()
            // Right into the right pane, then Up to its top.
            arrow(FocusDirection.Right)
            repeat(3) { if (!exists(indexersButton and isFocused())) arrow(FocusDirection.Up) }
            assertEquals("Right and Up from the field", "Search indexers for “$QUERY”", remote.focused())
            assertTheRailStaysNarrow("OK on Search indexers") { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER) }
            compose.waitUntil(20_000) { exists(resultRow) }
            assertFocusInTheContent("the results of Search indexers")
            assertTrue("the focus on a result; it is on ${remote.focused()}", exists(groupRow and isFocused()))
        }
    }

    /**
     * Enter before the pause, for words TMDB has no titles for: nothing to keep yet, so nothing has the focus; the
     * preview's own releases, as they come after the pause, take it (else the next arrow would open the rail).
     */
    @Test
    fun thePreviewsOwnReleasesTakeTheFocusAsTheyComeAfterEnter() {
        withFakeIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(QUERY)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(20_000) { exists(hasText("No titles on TMDB for “$QUERY” · releases from your indexers")) && exists(resultRow) }
            assertFocusInTheContent("the preview's own releases, come after Enter")
            assertTrue("the focus on a release; it is on ${remote.focused()}", exists(groupRow and isFocused()))
            assertFalse("the field took the focus (its keyboard opens)", fieldFocused())
        }
    }

    /**
     * A search of words TMDB has a title for: the Titles strip on top takes the focus, Down reaches the releases; OK on
     * the strip's title opens its page, and Back goes to that title.
     */
    @Test
    fun aSearchsTitlesStripTakesTheFocusAndItsTitleOpensItsPage() {
        withFakeIndexer {
            val harborLights = TitleSuggestion(MediaType.Tv, HARBOR, harbor.name, null, harbor.year, null, null)
            FakeTmdb(shows = mapOf(HARBOR to harbor), suggestions = { query -> listOf(harborLights).takeIf { query.lowercase() in QUERY } }).use {
                openSearch()
                compose.onNode(hasSetTextAction()).performTextInput(QUERY)
                val title = hasText("Harbor Lights") and hasClickAction()
                compose.waitUntil(10_000) { exists(title) }
                searchIndexers()
                compose.waitUntil(20_000) { exists(hasText("TITLES")) && exists(resultRow) }
                assertFocusInTheContent("the releases for the words, the Titles strip on top")
                assertTrue("the focus on the strip's title; it is on ${remote.focused()}", remote.focused().startsWith("Harbor Lights"))
                remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
                assertTrue("Down from the strip: a release; focus on ${remote.focused()}", exists(groupRow and isFocused()))

                remote.press(KeyEvent.KEYCODE_DPAD_UP)
                assertTrue("Up to the strip; focus on ${remote.focused()}", remote.focused().startsWith("Harbor Lights"))
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { current(ShowRoute::class) }
                assertFocusOnThePage("the Show page of the strip's title")
                remote.press(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { current(SearchRoute::class) }
                assertFocusInTheContent("Back from the Show page")
                assertTrue("Back from the Show page: the focus on the strip's title; it is on ${remote.focused()}", remote.focused().startsWith("Harbor Lights"))
            }
        }
    }

    /**
     * Enter in the field closes its keyboard and keeps the titles the preview shows (no search sent): the field's focus
     * goes into them, not to the rail, frame by frame and once settled.
     */
    @Test
    fun enterKeepsTheTitlesShownWithTheFocusInThem() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("glasswater")
        val row = hasText("Glasswater Bay") and hasClickAction()
        compose.waitUntil(10_000) { exists(row) }
        assertTheRailStaysNarrow("Enter in the field") { compose.onNode(hasSetTextAction()).performImeAction() }
        assertFocusInTheContent("Enter in the field")
        assertFalse("the field kept the focus (and its keyboard)", fieldFocused())
        assertTrue("the titles the preview showed are gone", exists(row))
        // A search's results end with what the indexers answered, even with none switched on.
        assertFalse("Enter sent a search", exists(hasText("Sorted by best playable release", substring = true) or hasText("Showing results from", substring = true)))
    }

    /** The line under the preview's titles, about TMDB: the focus waits there while nothing else in the list can have it. */
    private fun onTheTmdbLine() = remote.focused().startsWith("Title suggestions from TMDB")

    /**
     * Enter on words that will never show anything (two characters, which TMDB has no titles for and the preview doesn't
     * search the indexers for): the keyboard closes, and the focus goes into the list, to the line about TMDB's titles.
     * Not nowhere, from where the next arrow would open the rail.
     */
    @Test
    fun enterWithNothingToShowKeepsTheFocusInTheList() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("zq")
        compose.waitUntil(5_000) { exists(hasText("Title suggestions from TMDB", substring = true)) }
        settle()
        assertTheRailStaysNarrow("Enter on two characters") { compose.onNode(hasSetTextAction()).performImeAction() }
        assertFocusInTheContent("Enter on two characters")
        assertTrue("the focus on the line about TMDB's titles; it is on ${remote.focused()}", onTheTmdbLine())
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusInTheContent("Down after Enter on two characters")
    }

    /**
     * Enter before TMDB has answered for the words: the focus waits in the list, on the line about TMDB's titles, and
     * goes to the first title as the titles come.
     */
    @Test
    fun titlesThatComeAfterEnterTakeTheFocus() {
        val answer = CompletableDeferred<Unit>()
        val fake = TmdbLookup.searchTitles
        TmdbLookup.searchTitles = { c, query ->
            answer.await()
            fake(c, query)
        }
        try {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput("glasswater")
            assertTheRailStaysNarrow("Enter before TMDB's titles") { compose.onNode(hasSetTextAction()).performImeAction() }
            assertFocusInTheContent("Enter before TMDB's titles")
            assertTrue("the focus on the line about TMDB's titles; it is on ${remote.focused()}", onTheTmdbLine())

            answer.complete(Unit)
            compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
            assertFocusInTheContent("the titles, come after Enter")
            assertTrue("the focus on the first title; it is on ${remote.focused()}", remote.focused().startsWith("Glasswater Bay"))
        } finally {
            answer.complete(Unit)
            TmdbLookup.searchTitles = fake
        }
    }

    /** #236: Search indexers sends the search; the results take the focus, not the rail. Left still opens it. */
    @Test
    fun searchingTheIndexersPutsTheFocusInTheResults() {
        withFakeIndexer {
            search()
            assertFocusInTheContent("the results of the search sent")
            assertTrue("the focus on a result; it is on ${remote.focused()}", exists(groupRow and isFocused()))

            leftToTheRail()
            assertTrue("the user's Left opens the rail at Search; focus on ${remote.focused()}", remote.isFocused(railItem("Search")))
        }
    }

    /** Sent while the indexer is still to answer: nothing in the results plays yet, and the focus is still in them. */
    @Test
    fun whileASentSearchWaitsForItsIndexersTheFocusStaysInTheResults() {
        withSilentIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(WAITING)
            searchIndexers()
            compose.waitUntil(5_000) { exists(hasText("searching", substring = true)) }
            assertFocusInTheContent("the results while the indexer is asked")
            assertTrue("the focus on the results' footnote; it is on ${remote.focused()}", remote.focused().startsWith("Showing results from 0 of 1"))
            // Down meant for the results, which aren't there yet: not to the rail.
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocusInTheContent("Down while the indexer is asked")
            assertTrue("still searching", exists(hasText("searching", substring = true)))
        }
    }

    /** "No releases found" for words no release has, and nothing to try instead: the focus on that answer. */
    @Test
    fun aSearchThatFindsNothingKeepsTheFocusOnItsAnswer() {
        withFakeIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(NOTHING)
            searchIndexers()
            compose.waitUntil(20_000) { exists(hasText("No releases found")) }
            assertFocusInTheContent("a search that found nothing")
            assertTrue("the focus on the answer; it is on ${remote.focused()}", remote.focused().startsWith("No releases found"))
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocusInTheContent("Down on a search that found nothing")
        }
    }

    /**
     * [test] with [queries] as the only recent searches, the first at the top; the ones there before are hidden
     * meanwhile and come back after.
     */
    private fun withRecentSearches(vararg queries: String, test: () -> Unit) {
        val library = compose.activity.container.library
        val before = runBlocking { library.recentSearches.first() }
        try {
            runBlocking {
                before.forEach { library.removeSearch(it.key) }
                // Oldest first, a moment apart: the newest is at the top.
                queries.reversed().forEach { library.addSearch(it); delay(5) }
            }
            test()
        } finally {
            runBlocking {
                library.recentSearches.first().forEach { library.removeSearch(it.key) }
                before.reversed().forEach { r ->
                    // Each as what it was: a title opened, or a search.
                    val title = r.pick?.takeIf { r.kind == RecentKind.Title }
                    if (title != null) library.addTitle(title) else library.addSearch(r.query, r.pick)
                    delay(5)
                }
            }
        }
    }

    /** A recent search's X, by its words. */
    private fun removeRecent(query: String) = hasContentDescription("Remove $query from recent searches")

    /** The X on the bottom recent search: its row goes, the focus on to the one above's X, not to the field. */
    @Test
    fun removingTheBottomRecentSearchLeavesTheFieldAlone() {
        withSilentIndexer {
            withRecentSearches(RECENT_TOP, RECENT_BOTTOM) {
                openSearch()
                compose.waitUntil(5_000) { exists(removeRecent(RECENT_BOTTOM)) }
                compose.onNode(removeRecent(RECENT_BOTTOM)).requestFocus()
                remote.waitForFocus("Remove $RECENT_BOTTOM")
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { !exists(removeRecent(RECENT_BOTTOM)) }
                assertFocusInTheContent("the bottom recent search removed")
                assertFalse("the field took the focus", fieldFocused())
                assertEquals("the focus on to the recent search above", "Remove $RECENT_TOP from recent searches", remote.focused())
            }
        }
    }

    /**
     * The X on the top of three recent searches: the focus on to the next one's X, the middle one's, not past it to the
     * bottom one's as the rows move up.
     */
    @Test
    fun removingTheTopRecentSearchPutsTheFocusOnTheNextOnesX() {
        withSilentIndexer {
            withRecentSearches(RECENT_TOP, RECENT_MIDDLE, RECENT_BOTTOM) {
                openSearch()
                compose.waitUntil(5_000) { exists(removeRecent(RECENT_TOP)) }
                compose.onNode(removeRecent(RECENT_TOP)).requestFocus()
                remote.waitForFocus("Remove $RECENT_TOP")
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { !exists(removeRecent(RECENT_TOP)) }
                assertFocusInTheContent("the top recent search removed")
                assertEquals("the focus on to the next recent search", "Remove $RECENT_MIDDLE from recent searches", remote.focused())
            }
        }
    }

    /** The only recent search removed: the focus on to the field's gate, with no keyboard (on a TV the tips are text, on the right). */
    @Test
    fun removingTheOnlyRecentSearchPutsTheFocusOnTheFieldsGate() {
        withSilentIndexer {
            withRecentSearches(RECENT_BOTTOM) {
                openSearch()
                compose.waitUntil(5_000) { exists(removeRecent(RECENT_BOTTOM)) }
                compose.onNode(removeRecent(RECENT_BOTTOM)).requestFocus()
                remote.waitForFocus("Remove $RECENT_BOTTOM")
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { !exists(removeRecent(RECENT_BOTTOM)) }
                assertFocusInTheContent("the only recent search removed")
                assertTrue("the focus on the field's gate; it is on ${remote.focused()}", exists(fieldGate("Search") and isFocused()))
                assertFalse("the field took the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
            }
        }
    }

    /** A title opened before, OK on its recent search: its page, with the focus there; Back goes to its row, not to the field. */
    @Test
    fun aRecentTitleOpensItsPageAndBackGoesToItsRow() {
        val library = compose.activity.container.library
        val opened = SearchPick(TitleType.Tv, JumpFixture.GLASSWATER, "Glasswater Bay")
        runBlocking { library.addTitle(opened) }
        try {
            openSearch()
            val row = hasText("Glasswater Bay") and hasText("TV series") and hasClickAction()
            compose.waitUntil(5_000) { exists(row) }
            compose.onNode(row).requestFocus()
            remote.waitForFocus("Glasswater Bay")
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { current(ShowRoute::class) }
            assertFocusOnThePage("the Show page of the recent title")

            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { current(SearchRoute::class) }
            assertFocusInTheContent("Back from the Show page")
            assertTrue("Back from the Show page: the focus on the row that opened it; it is on ${remote.focused()}", remote.focused().startsWith("Glasswater Bay"))
            assertFalse("the field took the focus (its keyboard opens)", fieldFocused())
        } finally {
            runBlocking { library.removeSearch(RecentSearch.titleKey(opened)) }
        }
    }

    /** OK on a release group's releases (not the first group's) opens its list: their label, from where the remote left there. */
    private fun openTheSecondGroup(): String {
        search()
        settle()
        val rows = compose.onAllNodes(resultRow)
        assertTrue("more than one group to pick from", rows.fetchSemanticsNodes().size > 1)
        rows[1].requestFocus()
        compose.waitUntil(5_000) { exists(resultRow and isFocused()) }
        val group = remote.focused()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Size") and hasClickAction()) }
        return group
    }

    @Test
    fun okOnAReleaseGroupOpensItsListWithTheFocusInIt() {
        withFakeIndexer {
            openTheSecondGroup()
            assertFocusInTheContent("the group's release list")
        }
    }

    @Test
    fun backFromAReleaseListGoesToTheGroupThatOpenedIt() {
        withFakeIndexer {
            val group = openTheSecondGroup()
            settle()
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(resultRow) }
            assertFocusInTheContent("Back from the release list")
            assertEquals("Back from the release list: the focus on the releases that opened it", group, remote.focused())
        }
    }

    /**
     * An indexer on this device that lists [releases] (a show's name and an episode of its first season each, invented)
     * for any search, its answer [answerAfterMs] late, until closed. Just enough of Newznab: its caps, and its results.
     */
    private class ListingIndexer(releases: List<Pair<String, Int>>, private val answerAfterMs: Long = 0) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        private val results = buildString {
            val date = ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
            append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>""")
            append("""<newznab:response offset="0" total="${releases.size}"/>""")
            releases.forEach { (name, episode) ->
                val title = "${name.replace(' ', '.')}.S01E%02d.1080p.WEB-DL.AAC2.0.H.264-NOVA".format(episode)
                val guid = "listed-$episode"
                append("<item><title>$title</title><guid isPermaLink=\"false\">$guid</guid><pubDate>$date</pubDate>")
                append("""<enclosure url="$url/api?t=get&amp;id=$guid" length="1000000000" type="application/x-nzb"/>""")
                listOf("category" to "5040", "size" to "1000000000", "guid" to guid, "grabs" to "10", "files" to "1", "password" to "0", "season" to "S01", "episode" to "E%02d".format(episode))
                    .forEach { (attr, value) -> append("""<newznab:attr name="$attr" value="$value"/>""") }
                append("</item>")
            }
            append("</channel></rss>")
        }

        init {
            thread(isDaemon = true, name = "listing-indexer") {
                while (!server.isClosed) runCatching { server.accept().use(::answer) }
            }
        }

        private fun answer(socket: Socket) {
            val request = socket.getInputStream().bufferedReader()
            val line = request.readLine().orEmpty()
            while (!request.readLine().isNullOrEmpty()) Unit
            if ("t=caps" !in line) Thread.sleep(answerAfterMs)
            val body = (if ("t=caps" in line) CAPS else results).toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }

        override fun close() = server.close()

        private companion object {
            const val CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server version="1.0" title="Listing"/><limits max="100" default="100"/>""" +
                """<searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,season,ep"/><movie-search available="no"/></searching>""" +
                """<categories><category id="5000" name="TV"><subcat id="5040" name="HD"/></category></categories></caps>"""
        }
    }

    /** An indexer that takes every connection and never answers, until closed: a search of it waits for it. */
    private class SilentIndexer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val connections = CopyOnWriteArrayList<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true, name = "silent-indexer") {
                while (!server.isClosed) runCatching { connections += server.accept() }
            }
        }

        override fun close() {
            server.close()
            connections.forEach { it.close() }
        }
    }

    /** A picked movie's card with its releases: Back from the list its "N releases" opened goes to that button. */
    @Test
    fun backFromTheReleaseListOfAMoviesCardGoesToItsReleasesButton() {
        withFakeIndexer {
            FakeTmdb(movies = mapOf(CROWN to crown)).use {
                showCard(SearchPick(TitleType.Movie, CROWN, crown.title))
                val releases = hasText("releases", substring = true) and hasClickAction()
                compose.waitUntil(20_000) { exists(releases) }
                compose.onNode(releases).requestFocus()
                remote.waitForFocus("releases")
                val button = remote.focused()
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { exists(hasText("Size") and hasClickAction()) }
                settle()
                remote.press(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { exists(releases) }
                assertFocusInTheContent("Back from the movie's release list")
                assertEquals("Back from the release list: the focus on the button that opened it", button, remote.focused())
            }
        }
    }

    /**
     * A group below the first screen, reached with the remote (the list scrolled to it): Back from its release list
     * finds the results where they were, so the row is there again for the focus.
     */
    @Test
    fun backFromTheReleaseListOfAGroupBelowTheFirstScreenGoesToThatGroup() {
        withListingIndexer {
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput(WAITING)
            searchIndexers()
            // Its pill once it has answered: all its releases there.
            compose.waitUntil(20_000) { exists(hasText("Listing (TV page focus test) · ${LISTED.size}")) }
            settle()
            val firstScreen = compose.onAllNodes(resultRow).fetchSemanticsNodes().map { it.label() }
            // Down to the last group, and Right to its releases.
            var row = remote.focused()
            for (i in 0 until 3 * LISTED.size) {
                val now = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
                if (now == row) break
                row = now
            }
            val last = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("the last group's releases (\"$last\") are below the first screen, $firstScreen", last.contains("release") && last !in firstScreen)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { exists(hasText("Size") and hasClickAction()) }
            settle()
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(resultRow) }
            assertFocusInTheContent("Back from the last group's release list")
            assertEquals("Back from the release list: the focus on the releases that opened it", last, remote.focused())
        }
    }

    /**
     * Results coming in under the focus (keyed rows, the focus staying while results stream in): the
     * focus on a season's second group, then a later indexer's newer episode of that season comes in above it. The
     * focus stays on its group, now the third row, not on whichever group takes its place (OK there plays at once).
     */
    @Test
    fun aGroupComingInAboveTheFocusedOneLeavesTheFocusOnItsGroup() {
        ListingIndexer(listOf(STREAMED to 1, STREAMED to 2)).use { first ->
            ListingIndexer(listOf(STREAMED to 3), answerAfterMs = LATER_MS).use { later ->
                withIndexer(LISTING_INDEXER, "Listing (TV page focus test)", first.url) {
                    withIndexer(LATER_INDEXER, "Later (TV page focus test)", later.url) {
                        openSearch()
                        compose.onNode(hasSetTextAction()).performTextInput(STREAMED)
                        searchIndexers()
                        compose.waitUntil(10_000) { compose.onAllNodes(groupRow).fetchSemanticsNodes().size == 2 }
                        settle()
                        compose.onAllNodes(groupRow)[1].requestFocus()
                        compose.waitUntil(5_000) { exists(groupRow and isFocused()) }
                        val group = remote.focused()
                        assertTrue("on the season's second group, its first episode: $group", "S01E01" in group)
                        // The later indexer's answer: its newer episode goes to the top of the season.
                        compose.waitUntil(LATER_MS + 10_000) { compose.onAllNodes(groupRow).fetchSemanticsNodes().size == 3 }
                        compose.waitForIdle()
                        assertEquals("the focus left its group as another came in above it", group, remote.focused())
                    }
                }
            }
        }
    }

    /**
     * Back pressed on the rail while a release list is open closes the list (Search's Back goes first), and the focus
     * stays where the user put it, on the rail; Right from there goes to the group that opened the list.
     */
    @Test
    fun backOnTheRailClosesTheReleaseListAndLeavesTheFocusThere() {
        withFakeIndexer {
            val group = openTheSecondGroup()
            settle()
            leftToTheRail()
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("on the rail's Home; focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(resultRow) }
            settle()
            assertTrue("the list closed, the focus still on the rail's Home; it is on ${remote.focused()}", remote.isFocused(railItem("Home")))

            remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            settle()
            assertEquals("Right from the rail: the releases that opened the list", group, remote.focused())
        }
    }

    private val today = LocalDate.now()

    /** The fake catalogue's show, by its name and ids there; its season and episodes as invented here. */
    private val harbor = ShowDetails(
        HARBOR, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 2, today.minusYears(1), null)),
        lastEpisode = EpisodeSummary(1, 2, "Low Water", today.minusYears(1).plusDays(7)), nextEpisode = null,
        ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )
    private val harborSeason = Season(
        HARBOR, 1, null, null,
        listOf(
            Episode(1, 1, "Tide Clock", null, today.minusYears(1), 45, null),
            Episode(1, 2, "Low Water", null, today.minusYears(1).plusDays(7), 45, null),
        ),
    )

    /** The fake catalogue's movie, by its name and ids there; what TMDB would say of it invented here. */
    private val crown = MovieDetails(
        CROWN, "Copper Crown", null, 2019, "A clockmaker's apprentice keeps the town's only crown wound.", null, null, 104,
        ExternalIds(imdb = "tt9000012"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )

    private companion object {
        /** The rail's items, the ones the app shows: read from its tabs, so none is missed (Discover's in debug builds). */
        val RAIL = tabsFor(FormFactor.Tv).map { it.label }
        const val SETTLE_MS = 700L
        /** Frames stepped through one at a time: past the shell's check, two frames after the screen is up. */
        const val FRAMES = 30
        const val FAKE_INDEXER = "tv-page-focus-test"
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val HARBOR = 900002
        /** Copper Crown's, likewise. */
        const val CROWN = 900011
        const val SILENT_INDEXER = "tv-page-focus-test-silent"
        const val LISTING_INDEXER = "tv-page-focus-test-listing"
        const val LATER_INDEXER = "tv-page-focus-test-later"
        /** An invented show, its episodes from two indexers, one answering [LATER_MS] after the other. */
        const val STREAMED = "Kestrel Wharf"
        const val LATER_MS = 4_000L
        /** Invented shows, an episode each on the listing indexer. */
        val LISTED = listOf("Brass Meridian", "Copperline Station", "Fernwick Hollow", "Gullwing Pier", "Lowtide Parish", "Marrow Lane", "Nettlebrook", "Orchard Signal")
        const val QUERY = "harbor lights"
        /** Invented words: no release has them. */
        const val NOTHING = "quiet marsh"
        const val WAITING = "lantern harbour"
        const val RECENT_TOP = "slate harbour"
        const val RECENT_MIDDLE = "cinder reach"
        const val RECENT_BOTTOM = "amber quay"
    }
}

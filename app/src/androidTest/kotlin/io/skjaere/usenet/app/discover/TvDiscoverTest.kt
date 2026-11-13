package io.skjaere.usenet.app.discover

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.outsideTheSafeArea
import io.skjaere.usenet.app.tv.railItem
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.tv.textsCut
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Discover tab on a TV (forced on the phone emulator, 960 x 540 dp), in the whole app, with a remote, over invented
 * lists (FakeDiscover): its place on the rail, its first focus in every state (while TMDB answers, without a key and
 * once one is added, a key refused), the rows and their See all, Left to the rail and Right back, Back from a page to
 * what opened it, the collection's mark after "+ Collection" on a card's page, and the safe area at 1x and 1.3x.
 */
@RunWith(AndroidJUnit4::class)
class TvDiscoverTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var nav: NavHostController
    private val container get() = compose.activity.container
    private var scale by mutableFloatStateOf(1f)
    private var keyBefore: String? = null
    private var fake: FakeDiscover? = null
    private var tmdb: FakeTmdb? = null
    /** Trending's first page's titles in the collection before the test: the others it adds go again after it. */
    private var collectedBefore = emptySet<Int>()
    private var languageBefore: String? = null

    /** Trending's first show, on its first page: "Harbor Lights 1". */
    private val first = FakeDiscover.id(DiscoverList.Trending, 1, 0)

    /** Trending's first page, its pages known to the fake TMDB. */
    private val trendingIds = (0 until 20).map { FakeDiscover.id(DiscoverList.Trending, 1, it) }

    private fun show(id: Int) = ShowDetails(
        id, "Invented $id", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 2, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 2, "Episode 2", LocalDate.of(2021, 3, 8)), nextEpisode = null, ids = ExternalIds(),
    )

    private val rail get() = tabsFor(FormFactor.Tv).map { it.label }

    @Before
    fun setUp() {
        home.show()
        instrumentation.setInTouchMode(false)
        runBlocking {
            keyBefore = container.settings.tmdbKey()
            // The lists and the page are the fakes': any key will do.
            if (keyBefore == null) container.settings.setTmdbKey("tv-discover-test")
            collectedBefore = trendingIds.filterTo(HashSet()) { container.library.isCollected(it).first() }
            languageBefore = container.settings.metadata.first().language
        }
        tmdb = FakeTmdb(
            shows = trendingIds.associateWith(::show),
            seasons = { id, n -> if (id in trendingIds) Season(id, n, null, null, (1..2).map { Episode(n, it, "Episode $it", null, LocalDate.of(2021, 3, 1), 45, null) }) else null },
        )
    }

    @After
    fun restore() {
        tmdb?.close()
        fake?.close()
        runBlocking {
            trendingIds.filterNot { it in collectedBefore }.forEach { container.library.removeShow(it) }
            container.settings.setTmdbKey(keyBefore)
            container.settings.updateMetadata { it.copy(language = languageBefore) }
        }
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private fun showTheApp(source: FakeDiscover = FakeDiscover()) {
        fake = source.install()
        compose.setTvContent({ scale }) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun railFocused(label: String) = remote.isFocused(railItem(label))

    private val inTvDiscover = hasAnyAncestor(SemanticsMatcher.keyIsDefined(TvDiscoverKey))

    /** From Home's content: Left to the rail, down it to Discover, and OK. */
    private fun openDiscover() {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(rail.indexOf(DISCOVER) - rail.indexOf("Home")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("on the rail's Discover; focus on ${remote.focused()}", railFocused(DISCOVER))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Down from the top line into the first row, on the card under the focus; then Left along it to its first card. */
    private fun intoTheFirstRow() {
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down into Trending's row; focus on ${remote.focused()}", remote.focused().endsWith(" 1, 2021, show"))
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, FIRST_CARD, max = 4)
        assertEquals(FIRST_CARD, remote.focused())
    }

    /** The page opened over the tab, up and taking Back: resumed, past the screens' cross-fade. */
    private fun waitForThePage(route: kotlin.reflect.KClass<*>) {
        compose.waitUntil(10_000) {
            compose.runOnIdle { nav.currentDestination?.hasRoute(route) == true && nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        }
    }

    /** The focus on the switch's selected side, inside Discover's content. */
    private fun waitForTheSwitch(side: String = SHOWS) {
        try {
            compose.waitUntil(5_000) { remote.isFocused(inTvDiscover and hasText(side)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never on the switch's $side; it is on \"${remote.focused()}\"", e)
        }
    }

    @Test
    fun discoverIsOnTheRailAfterHomeAndItsFirstFocusIsTheSwitch() {
        showTheApp()
        assertEquals(listOf("Search", "Home", DISCOVER, "Collection", "Settings"), rail)
        openDiscover()
        waitForTheSwitch()
        assertTrue("rows shown", exists(hasContentDescription(FIRST_CARD)))
    }

    @Test
    fun theTabOpenedAgainScrolledDownStartsOnTheSwitchWithTheColumnAtItsTop() {
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        // Down the rows to the fourth: the top line scrolls off.
        repeat(4) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("the top line scrolled off", !exists(inTvDiscover and hasText(SHOWS)))
        // To the Collection by the rail, and back.
        repeat(8) { if (!railFocused(DISCOVER)) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("on the rail's Collection; focus on ${remote.focused()}", railFocused("Collection"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForCollectionFocus()
        repeat(4) { if (!railFocused("Collection")) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("on the rail's Discover; focus on ${remote.focused()}", railFocused(DISCOVER))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForTheSwitch()
    }

    @Test
    fun whileTmdbIsAskedTheSwitchHasTheFocusAndKeepsItAsTheRowsCome() {
        val held = CompletableDeferred<Unit>()
        showTheApp(FakeDiscover(answer = { q, from ->
            held.await()
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        assertTrue("no rows yet", !exists(hasContentDescription(FIRST_CARD)))
        held.complete(Unit)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_CARD)) }
        compose.waitForIdle()
        assertTrue("the switch kept the focus; it is on ${remote.focused()}", remote.isFocused(inTvDiscover and hasText(SHOWS)))
    }

    @Test
    fun downEntersTheRowsLeftGoesToTheRailRightComesBackAndTheRowEndsInSeeAll() {
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        intoTheFirstRow()
        assertEquals("Lantern Row 1, 2021, show", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(FIRST_CARD, remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from a row's first card: the rail; focus on ${remote.focused()}", railFocused(DISCOVER))
        assertEquals("Right from the rail: the card left", FIRST_CARD, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // TalkBack hears which list it opens: the five rows each end in one.
        val end = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, TRENDING_SEE_ALL, max = 25)
        assertEquals(TRENDING_SEE_ALL, end)
    }

    @Test
    fun seeAllOpensItsPageWithoutTheRailItsFirstPillFocusedAndBackReturnsToSeeAll() {
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, TRENDING_SEE_ALL, max = 25)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Trending · Shows")) }
        assertTrue("a page: no rail", !exists(railItem("Home")))
        remote.waitForFocus("Period: This week")
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(railItem("Home")) }
        remote.waitForFocus(TRENDING_SEE_ALL)
    }

    @Test
    fun aCardOpensItsPageAndPlusCollectionThereMarksItWhereTheFocusComesBack() {
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        intoTheFirstRow()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText("+ Collection") and hasClickAction()) }
        waitForThePage(io.skjaere.usenet.app.ShowRoute::class)
        assertTrue("a page: no rail", !exists(railItem("Home")))
        // By semantics: a touch would leave the remote's mode.
        compose.onNode(hasText("+ Collection") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists(hasText("In collection ✓")) }
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("$FIRST_CARD, in your collection")
    }

    /**
     * Back from a card's page while the rows are asked again (TMDB's language changed meanwhile; after Android ended the
     * app in the background, the tab's new view model asks them the same way): the focus back on the card once its row
     * comes, not on the switch for good.
     */
    @Test
    fun backFromACardsPageWhileTheRowsAreAskedAgainReturnsToTheCardOnceItsRowComes() {
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        showTheApp(FakeDiscover(answer = { q, from ->
            gate.get()?.await()
            Aged(named(q, from), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, FIRST_TRENDING, max = 4)
        assertEquals(FIRST_TRENDING, remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForThePage(io.skjaere.usenet.app.ShowRoute::class)
        val answer = CompletableDeferred<Unit>().also(gate::set)
        runBlocking { container.settings.updateMetadata { it.copy(language = "fr-FR") } }
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(inTvDiscover and hasText(SHOWS)) }
        repeat(5) { compose.waitForIdle() }
        answer.complete(Unit)
        remote.waitForFocus(FIRST_TRENDING)
    }

    @Test
    fun aPosterOnSeeAllOpensItsPageAndBackReturnsToIt() {
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, TRENDING_SEE_ALL, max = 25)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Period: This week")
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        val poster = remote.focused()
        assertTrue("a poster on the second line; focus on $poster", poster.endsWith(" 1, 2021, show") && !poster.startsWith(FIRST_CARD.substringBefore(',')))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText("+ Collection") and hasClickAction()) }
        waitForThePage(io.skjaere.usenet.app.ShowRoute::class)
        // Added on its page: the poster comes back marked (its mark travels with the title, not a mapping kept).
        compose.onNode(hasText("+ Collection") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists(hasText("In collection ✓")) }
        remote.press(KeyEvent.KEYCODE_BACK)
        // See all again, where it was left: scrolled to the poster, its title line maybe above the screen.
        compose.waitUntil(5_000) { exists(SemanticsMatcher.keyIsDefined(TvDiscoverListKey)) }
        remote.waitForFocus("$poster, $IN_YOUR_COLLECTION")
    }

    @Test
    fun withoutAKeyItsCardHasTheFocusLeadsToTheKeyAndOnceAddedTheFocusGoesToTheSwitch() {
        runBlocking { container.settings.setTmdbKey(null) }
        val source = FakeDiscover()
        showTheApp(source)
        openDiscover()
        remote.waitForFocus(ADD_KEY)
        assertTrue(exists(hasText(NO_KEY_TITLE)))
        assertTrue("no switch without a key", !exists(inTvDiscover and hasText(SHOWS)))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the card's button: the rail; focus on ${remote.focused()}", railFocused(DISCOVER))
        assertEquals(ADD_KEY, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForThePage(MetadataRoute::class)
        runBlocking { container.settings.setTmdbKey("tv-discover-test") }
        remote.press(KeyEvent.KEYCODE_BACK)
        waitForTheSwitch()
        assertTrue("nothing asked without a key, then the lists", source.asked.isNotEmpty())
    }

    @Test
    fun aKeyTmdbRefusedHasItsCardWithTheFocus() {
        showTheApp(FakeDiscover(answer = { _, _ -> throw TmdbException(TmdbError.InvalidKey("Invalid API key")) }))
        openDiscover()
        remote.waitForFocus(CHECK_KEY)
        assertTrue(exists(hasText(REFUSED_TITLE)))
    }

    @Test
    fun aRowThatFailedHasTryAgainInItsPlaceAndItsFirstCardTakesTheFocusOnceItComes() {
        val failing = AtomicBoolean(true)
        showTheApp(FakeDiscover(answer = { q, from ->
            if (q.list == DiscoverList.Trending && failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(exists(hasText(LOAD_FAILED)))
        failing.set(false)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(FIRST_CARD)
    }

    /** A list's page, Trending's titles told apart from the other rows' (each list's first page has its own Harbor Lights 1). */
    private fun named(q: DiscoverQuery, from: Int) = FakeDiscover.page(q, from, 3).let { page ->
        if (q.list != DiscoverList.Trending) page else page.copy(items = page.items.map { it.copy(title = "${it.title} trending") })
    }

    /** Trending's row failing while [failing], each of its answers waiting for [gate] first when there is one. */
    private fun trendingFailing(failing: AtomicBoolean, gate: AtomicReference<CompletableDeferred<Unit>?> = AtomicReference(null)) =
        FakeDiscover(answer = { q, from ->
            if (q.list == DiscoverList.Trending) {
                gate.get()?.await()
                if (failing.get()) throw TmdbException(TmdbError.Network("unreachable"))
            }
            Aged(named(q, from), Duration.ZERO)
        })

    @Test
    fun aRowsTryAgainKeepsTheFocusWhileTmdbIsAskedAgainAndHandsItToTheFirstCard() {
        val failing = AtomicBoolean(true)
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        showTheApp(trendingFailing(failing, gate))
        openDiscover()
        waitForTheSwitch()
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        failing.set(false)
        val answer = CompletableDeferred<Unit>().also(gate::set)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // A while for the shell, which would put a lost focus elsewhere.
        repeat(5) { compose.waitForIdle() }
        assertEquals("Try again keeps the focus while TMDB is asked again", TRY_AGAIN, remote.focused())
        assertTrue(exists(hasText(ASKING_AGAIN)))
        answer.complete(Unit)
        remote.waitForFocus(FIRST_TRENDING)
    }

    @Test
    fun aRowAskedAgainLeavesTheFocusOnTheRailWhenTheUserWentThereMeanwhile() {
        val failing = AtomicBoolean(true)
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        showTheApp(trendingFailing(failing, gate))
        openDiscover()
        waitForTheSwitch()
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        failing.set(false)
        val answer = CompletableDeferred<Unit>().also(gate::set)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(5) { compose.waitForIdle() }
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("on the rail; focus on ${remote.focused()}", railFocused(DISCOVER))
        answer.complete(Unit)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_TRENDING)) }
        repeat(5) { compose.waitForIdle() }
        assertTrue("the rail kept the focus; it is on ${remote.focused()}", railFocused(DISCOVER))
    }

    @Test
    fun aTryAgainThatFailedAgainHandsNothingOnWhenTheRowComesLater() {
        // Trending fails without a genre, and answers with one.
        showTheApp(FakeDiscover(answer = { q, from ->
            if (q.list == DiscoverList.Trending && q.filters.genre == null) throw TmdbException(TmdbError.Network("unreachable"))
            Aged(named(q, from), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(LOAD_FAILED)) }
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Drama", remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Drama", max = 6))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_TRENDING)) }
        repeat(5) { compose.waitForIdle() }
        assertEquals("the chip picked keeps the focus", "Drama", remote.focused())
    }

    @Test
    fun aRowTmdbAsksAgainByItselfHandsTheFocusFromItsTryAgainToItsFirstCard() {
        val busy = AtomicBoolean(true)
        showTheApp(FakeDiscover(answer = { q, from ->
            if (q.list == DiscoverList.Trending && busy.get()) throw TmdbException(TmdbError.RateLimited())
            Aged(named(q, from), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(exists(hasText(BUSY)))
        busy.set(false)
        // Asked again after the backoff's first wait, 10 s.
        remote.waitForFocus(FIRST_TRENDING, timeoutMs = 20_000)
    }

    @Test
    fun everyRowFailingIsOneCardWhoseTryAgainIsReachedDownFromTheSwitch() {
        val failing = AtomicBoolean(true)
        showTheApp(FakeDiscover(answer = { q, from ->
            if (failing.get()) throw TmdbException(TmdbError.Server("503"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        assertEquals(1, compose.onAllNodes(hasText(LOAD_FAILED)).fetchSemanticsNodes().size)
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        failing.set(false)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_CARD)) }
    }

    /** From the switch's Shows, Right to Movies and OK: the Movies side, the focus kept on it. */
    private fun toMovies() {
        assertEquals(MOVIES, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForTheSwitch(MOVIES)
    }

    @Test
    fun everyRowFailingsTryAgainPutsTheFocusOnTheSideShown() {
        val failing = AtomicBoolean(true)
        showTheApp(FakeDiscover(answer = { q, from ->
            if (failing.get()) throw TmdbException(TmdbError.Server("503"))
            Aged(FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        toMovies()
        compose.waitUntil(5_000) { exists(hasText(LOAD_FAILED)) }
        assertEquals(TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        failing.set(false)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_MOVIE)) }
        repeat(5) { compose.waitForIdle() }
        assertTrue("the focus on Movies; it is on ${remote.focused()}", remote.isFocused(inTvDiscover and hasText(MOVIES)))
    }

    @Test
    fun aGenreNoRowHasClearFiltersPutsTheFocusOnTheSideShown() {
        showTheApp(FakeDiscover(answer = { q, from ->
            Aged(if (q.filters.genre == 99) DiscoverPage(emptyList(), from, 1) else FakeDiscover.page(q, from, 3), Duration.ZERO)
        }))
        openDiscover()
        waitForTheSwitch()
        toMovies()
        assertEquals("Documentary", remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Documentary", max = 6))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(NOTHING_MATCHES)) }
        assertEquals(CLEAR_FILTERS, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasContentDescription(FIRST_MOVIE)) }
        repeat(5) { compose.waitForIdle() }
        assertTrue("the focus on Movies; it is on ${remote.focused()}", remote.isFocused(inTvDiscover and hasText(MOVIES)))
    }

    @Test
    fun theTabAndItsSeeAllStayInsideTheSafeAreaWithNothingCutAtOneAndAThirdTimesTheText() {
        // Trending's first title collected: its card's mark, "✓ In collection", is shown whole too.
        runBlocking { container.library.addShow(CollectedShow(CollectedShow.idFor(first), first, name = "Harbor Lights 1", addedAt = 0, updatedAt = 0)) }
        showTheApp()
        openDiscover()
        waitForTheSwitch()
        assertTrue("the mark shown", exists(hasContentDescription("$FIRST_CARD, $IN_YOUR_COLLECTION")))
        // A poster's name ends in an ellipsis when long, as TV Home's do.
        val intended = { words: String -> words.endsWith(" 1") || words == "2021" }
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            compose.waitForIdle()
            assertEquals("the tab at ${s}x", emptyList<String>(), compose.outsideTheSafeArea())
            assertEquals("the tab's texts at ${s}x", emptyList<String>(), compose.textsCut(intended))
        }
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("down in the rows", emptyList<String>(), compose.outsideTheSafeArea())
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, TRENDING_SEE_ALL, max = 25)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Period: This week")
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            compose.waitForIdle()
            assertEquals("See all at ${s}x", emptyList<String>(), compose.outsideTheSafeArea())
            assertEquals("See all's texts at ${s}x", emptyList<String>(), compose.textsCut(intended))
        }
    }

    private companion object {
        /** What TalkBack hears of Trending's first card: TV Home's Down lands on the card under the focus, not on it. */
        const val FIRST_CARD = "Harbor Lights 1, 2021, show"

        /** What TalkBack hears of Trending's See all. */
        val TRENDING_SEE_ALL = seeAllSaid("Trending this week")

        /** Trending's first card on the Movies side. */
        const val FIRST_MOVIE = "Harbor Lights 1, 2021, movie"

        /** Trending's first card where its titles are told apart ([named]). */
        const val FIRST_TRENDING = "Harbor Lights 1 trending, 2021, show"
    }
}

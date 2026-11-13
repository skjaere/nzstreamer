package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.MovieRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.UpNextEditsFake
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeCollection
import io.skjaere.usenet.app.home.HomeCollectionRow
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.IMPORT_LABEL
import io.skjaere.usenet.app.home.MARK_WATCHED
import io.skjaere.usenet.app.home.REMOVE_FROM_UP_NEXT
import io.skjaere.usenet.app.home.SEARCH_INDEXERS
import io.skjaere.usenet.app.home.SEE_ALL
import io.skjaere.usenet.app.home.TMDB_HINT
import io.skjaere.usenet.app.home.TvHomeContent
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * TV Home (TV-3), in the TV shell as a TV shows it (forced on a phone emulator), over invented rows: its
 * first focus in each state; the remote reaching every card, poster, See all and recent search, and Left from the first
 * card the rail; OK on an Up next card opening its page (the Movie page here is the real one, over the TMDB seam), where
 * the OK that opened it plays nothing and the next OK plays; a release played without TMDB playing from its card; Back
 * from a page on the card or poster that opened it; the focus kept on a card, and on screen, as Up next reorders, and
 * handed on when the card goes; See all opening the Collection tab; everything inside the safe area and shown in full at
 * 1x and 1.3x. A held OK on a card opens its menu (L24): Remove from Up next, and Mark watched for an episode or film in
 * progress; after them Undo has the focus, which goes on to the next card, the one before, the first poster or the empty
 * card's button as the bar goes, and back to the card Undo restores. The Show pages and the other tabs are stand-ins.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val remote = Remote(compose)
    private val indexers = PageIndexers(INDEXER)
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private val shownState = mutableStateOf<HomeState?>(null)
    private var shown by shownState

    /** Up next's menu and Undo bar over [shown], as HomeViewModel does them over the library. */
    private val fake = UpNextEditsFake(shownState)
    /** Changed, Home is composed anew, as it is when the app comes back to it. */
    private var generation by mutableIntStateOf(0)
    private var scale by mutableFloatStateOf(1f)
    private val played = CopyOnWriteArrayList<UpNextRow>()
    private val picked = CopyOnWriteArrayList<RecentSearch>()
    private var searched = 0

    private val crown = MovieDetails(
        MOVIE, "Copper Crown", null, 2019, "A retired forger is pulled into one last job when a crown goes missing.", null, null, 118,
        ExternalIds(imdb = "tt9000312"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )

    @Before
    fun setUp() {
        instrumentation.setInTouchMode(false)
        tmdb = FakeTmdb(movies = mapOf(MOVIE to crown))
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        tmdb?.close()
        fake.close()
        container.playback.cancel()
        indexers.restore()
    }

    // The rows: invented titles and ids nothing real has.

    private fun show(id: Int, name: String) = CollectedShow(CollectedShow.idFor(id), id, name = name, addedAt = 0, updatedAt = 0)

    private fun episode(id: Int, name: String, s: Int, e: Int) = WatchState(
        WatchKey.episode(id, s, e), WatchKind.Episode, showTmdbId = id, season = s, episode = e, title = "$name S0${s}E0$e",
        positionMs = 900_000, durationMs = 2_700_000, updatedAt = 0,
    )

    private fun resumeEpisode(id: Int, name: String, s: Int, e: Int, card: String) =
        UpNextRow(name, card, 0.33f, false, null, UpNextPlay.Resume(episode(id, name, s, e)), "Resume $name S0${s}E0$e", cardLine = card)

    private fun next(id: Int, name: String, card: String, new: Boolean = false) =
        UpNextRow(name, card, null, new, null, UpNextPlay.NextEpisode(show(id, name), EpisodeRef(1, 2)), "Play $name S01E02", cardLine = card)

    private val harbor = resumeEpisode(HARBOR, "Harbor Lights", 2, 5, "S02E05 · 30 min left")
    private val copper = UpNextRow(
        "Copper Crown", "2019 · 42 min left", 0.64f, false, null,
        UpNextPlay.Resume(WatchState(WatchKey.movie(MOVIE), WatchKind.Movie, movieTmdbId = MOVIE, title = "Copper Crown", positionMs = 4_560_000, durationMs = 7_080_000, updatedAt = 0)),
        "Resume Copper Crown", cardLine = "2019 · 42 min left",
    )
    private val orbit = next(ORBIT, "The Quiet Orbit", "S01E02 · Second Light", new = true)
    private val northbound = UpNextRow(
        "Northbound", "2019 · 30 min left", 0.4f, false, null,
        UpNextPlay.Resume(WatchState(WatchKey.release("n-northbound", 0), WatchKind.Release, title = "Northbound.2019.1080p.BluRay.x264-NZST", positionMs = 1_200_000, durationMs = 3_000_000, updatedAt = 0)),
        "Resume Northbound 2019", cardLine = "2019 · 30 min left",
    )
    private val glasswater = next(GLASSWATER, "Glasswater Bay", "S01E02 · 19 unwatched")
    private val salt = resumeEpisode(SALT, "Salt & Signal", 1, 2, "S01E02 · 20 min left")
    private val upNext = listOf(harbor, copper, orbit, northbound, glasswater, salt)

    private val posterNames = listOf(
        "Harbor Lights", "The Quiet Orbit", "Glasswater Bay", "Salt & Signal", "Lantern Row", "Glasshouse",
        "Copper Valley", "Tide Clock", "Ember Quay", "Marsh Signal", "Low Water", "Breakwater",
    )
    private val posters = posterNames.mapIndexed { i, name -> HomeCollectionRow(TitleKey.show(POSTERS + i), name, "Up to date", new = i == 1, progress = 0.5f.takeIf { i == 0 }) }
    private val recent = listOf("harbor lights s02", "copper crown", "orbit 2024").map { RecentSearch(it, it, 0, 0) }

    private val full = HomeState(recent, upNext, HomeCollection(214, 380, posters), hasTmdbKey = true)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun card(title: String) = hasText(title) and hasClickAction() and SemanticsMatcher("a card") { n ->
        n.config.getOrNull(SemanticsActions.OnClick)?.label in setOf("Open", "Play")
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
    }

    /** Home in the shell on [state]: the Movie page the real one, the Show pages and other tabs stand-ins. */
    private fun showHome(state: HomeState?) {
        shown = state
        compose.setTvContent({ scale }) {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), container.playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { entry ->
                        TopLevelBack(nav, entry)
                        key(generation) {
                            TvHomeContent(
                                shown, artwork = false, onSearch = { searched++ }, onRecent = { picked += it }, onPlay = { played += it },
                                onShow = { nav.navigate(ShowRoute(it)) }, onMovie = { nav.navigate(MovieRoute(it)) },
                                onCollection = { nav.switchTab(CollectionRoute) }, onMetadata = {}, onImport = {}, edits = fake.edits(),
                            )
                        }
                    }
                    composable<ShowRoute> { entry -> StandIn("Show ${entry.toRoute<ShowRoute>().tmdbId}") }
                    composable<MovieRoute> { entry -> MovieScreen(entry.toRoute<MovieRoute>().tmdbId, onBack = { nav.popBackStack() }) }
                    composable<CollectionRoute> { entry ->
                        TopLevelBack(nav, entry)
                        StandIn(COLLECTION_TAB)
                    }
                    composable<SearchRoute> { StandIn("Search tab") }
                    composable<SettingsRoute> { StandIn("Settings tab") }
                }
            }
        }
    }

    /** Home composed anew on [state], as after a page or the app going away. */
    private fun fresh(state: HomeState?) {
        compose.runOnIdle {
            shown = state
            generation++
        }
    }

    private fun current(route: kotlin.reflect.KClass<*>) = compose.runOnIdle { nav.currentDestination?.hasRoute(route) == true }

    @Test
    fun theFirstFocusIsTheFirstCardElseTheFirstPosterElseTheEmptyCardsButtonAndNeverBeforeTheRows() {
        showHome(null)
        settle()
        assertTrue("loading, nothing of Home's has the focus; it is on ${remote.focused()}", remote.focused() == "(nothing)" || railFocused())
        compose.runOnIdle { shown = full }
        remote.waitForFocus("Harbor Lights")
        assertTrue("the first Up next card", remote.isFocused(card("Harbor Lights")))

        fresh(full.copy(upNext = emptyList()))
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("no Up next: the first poster", "Harbor Lights", focusedTitle())
        assertTrue(!exists(hasText("UP NEXT")))

        fresh(HomeState(recent, emptyList(), null, hasTmdbKey = true))
        remote.waitForFocus(SEARCH_INDEXERS)
        settle()
        // Alone on its line, in the middle: Left goes to the rail, as from the column's first elements, not to what is below.
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the empty card's button: the rail; focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
        assertEquals("and Right back", SEARCH_INDEXERS, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // The recent searches under it (Down lands on the one nearest below), and the .nzb entry.
        val chip = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from the button: a recent search, not $chip", recent.any { it.query == chip })
        assertEquals(IMPORT_LABEL, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the button searches", 1, searched)

        // No TMDB key: release-named text tiles, and the hint after them.
        val tile = northbound
        fresh(HomeState(emptyList(), listOf(tile), null, hasTmdbKey = false))
        remote.waitForFocus("Northbound")
        assertEquals("Down: the hint", TMDB_HINT, remote.press(KeyEvent.KEYCODE_DPAD_DOWN).substringBefore(" | "))
    }

    /** What the focused card's title says: its first words. */
    private fun focusedTitle(): String = remote.focused().substringBefore(" | ")

    private fun railFocused() = RAIL.any { remote.isFocused(railItem(it)) }

    /**
     * Along the focused row to its start (Left until the focus stays, or reaches the rail, from which Right comes back),
     * then to its end: what had the focus on the way.
     */
    private fun sweep(): List<String> {
        for (i in 0 until 20) {
            val before = remote.focused()
            val now = remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            if (railFocused()) {
                assertEquals("Right from the rail: back to where the focus was", before, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
                break
            }
            if (now == before) break
        }
        val seen = mutableListOf(remote.focused())
        for (i in 0 until 20) {
            val next = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            if (next == seen.last()) break
            seen += next
        }
        return seen
    }

    @Test
    fun theRemoteReachesEveryCardPosterSeeAllAndRecentSearchAndLeftFromTheFirstCardTheRail() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        val seen = mutableListOf<String>()
        seen += sweep()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        seen += sweep()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        seen += sweep()
        seen += remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        val titles = seen.map { it.substringBefore(" | ") }
        val expected = upNext.map { it.title } + posterNames + listOf("$SEE_ALL ›") + recent.map { it.query } + IMPORT_LABEL
        expected.forEach { words -> assertTrue("never focused: $words; focus went ${titles.joinToString(" → ")}", words in titles) }
        // Each card says its line under its title.
        assertTrue(seen.any { "S01E02 · Second Light" in it && "NEW" in it })

        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(card("Copper Crown")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Harbor Lights", focusedTitle())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the first card: the rail's Home; focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
        assertEquals("and Right back to it", "Harbor Lights", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
    }

    /** D1 and D2: OK on Copper Crown's card opens its page with Play focused; the OK that opened it plays nothing; the next OK plays. */
    @Test
    fun okOnAnUpNextCardOpensItsPageWhereTheOpeningOkPlaysNothingAndTheNextOkPlays() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV Home test)", silent.url)
            showHome(full)
            remote.waitForFocus("Harbor Lights")
            settle()
            assertEquals("Copper Crown", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
            val opened = SystemClock.uptimeMillis()
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(15_000) { exists(hasText("Choose release") and hasClickAction()) }
            assertTrue("the Movie page", current(MovieRoute::class))
            remote.waitForFocus("Play")
            assertEquals("the card played nothing itself", emptyList<UpNextRow>(), played.toList())
            // The second press of a double OK on the card: the page's first button doesn't take it.
            earlyOk(opened)
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals(PlayState.Idle, container.playback.state.value)
            settle()
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { container.playback.state.value != PlayState.Idle }
        }
    }

    @Test
    fun aCardOfAReleasePlayedWithoutTmdbPlaysFromHome() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        compose.onNode(card("Northbound")).requestFocus()
        remote.waitForFocus("Northbound")
        assertEquals("it says what OK does", "Play", compose.onNode(card("Northbound")).fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(northbound), played.toList())
        assertTrue("still Home", current(HomeRoute::class))
    }

    /**
     * No TMDB key: no page can show an episode in progress (the Show page needs TMDB's answer), so its card plays from
     * Home as a release's does, and says so.
     */
    @Test
    fun withoutATmdbKeyAnEpisodesCardPlaysFromHome() {
        showHome(HomeState(emptyList(), listOf(harbor, northbound), null, hasTmdbKey = false))
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("it says what OK does", "Play", compose.onNode(card("Harbor Lights")).fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(harbor), played.toList())
        assertTrue("still Home", current(HomeRoute::class))
    }

    /** The focused element's whole box, in dp of the TV's screen. */
    private fun focusedBox(): Rect {
        val n = compose.onNode(isFocused()).fetchSemanticsNode()
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        val b = Rect(n.positionInRoot, n.size.toSize())
        return Rect((b.left - screen.left) / dp, (b.top - screen.top) / dp, (b.right - screen.left) / dp, (b.bottom - screen.top) / dp)
    }

    private fun Rect.onScreen() = left >= 128 - 10 && right <= 912 + 10 && top >= 27 - 6 && bottom <= 513 + 6

    /**
     * Back from a page on what opened it: the sixth card (past the four the row shows at first, so the row scrolls to it;
     * not Home's first focus, the first card), then the ninth poster.
     */
    @Test
    fun backFromAPageGoesBackToTheCardOrPosterThatOpenedItPosterNineIncluded() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        val sixth = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Salt & Signal", max = 6)
        assertTrue("never reached the sixth card; at $sixth", "Salt & Signal" in sixth)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Show $SALT")) }
        settle()
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { current(HomeRoute::class) }
        remote.waitForFocus("Salt & Signal")
        settle()
        assertTrue("back on Salt & Signal's card; focus on ${remote.focused()}", remote.isFocused(card("Salt & Signal") and hasText("S01E02 · 20 min left")))
        assertTrue("the sixth card is on screen: ${focusedBox()}", focusedBox().onScreen())

        // Along the row back to its start, then Down to the posters.
        val start = remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, "Harbor Lights", max = 6)
        assertTrue("never back at the first card; at $start", "Harbor Lights" in start)
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(8) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // The row starts where Down landed: along it to the ninth poster.
        val ninth = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Ember Quay", max = 12)
        assertTrue("never reached the ninth poster; at $ninth", "Ember Quay" in ninth)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Show ${POSTERS + 8}")) }
        settle()
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { current(HomeRoute::class) }
        remote.waitForFocus("Ember Quay")
        settle()
        assertEquals("Ember Quay", focusedTitle())
        val box = focusedBox()
        assertTrue("the ninth poster is on screen: $box", box.onScreen())
    }

    /**
     * Played from its card (a release's) or its page, a show moves up (its next episode is now in progress): the focus is
     * still on its card, which shows; a row keeps its first card shown, so one moved to the front would sit off its end.
     */
    @Test
    fun aCardMovedToTheFrontKeepsTheFocusAndShows() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        val at = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Glasswater Bay", max = 6)
        assertTrue("never reached Glasswater Bay; at $at", "Glasswater Bay" in at)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()
        assertTrue("the row has scrolled: Harbor Lights' card is off its start", focusedBoxOf(harbor.cardLine)?.let { it.left < 128 } ?: true)
        val started = resumeEpisode(GLASSWATER, "Glasswater Bay", 1, 2, "S01E02 · 30 min left")
        compose.runOnIdle { shown = full.copy(upNext = listOf(started) + upNext.filter { it != glasswater }) }
        compose.waitForIdle()
        assertTrue("focus is on ${remote.focused()}", remote.isFocused(card("Glasswater Bay") and hasText("S01E02 · 30 min left")))
        settle()
        val box = focusedBox()
        assertTrue("the card moved to the front shows: $box", box.onScreen())
    }

    /** The box of the card saying [words] in dp, or null when it isn't composed. */
    private fun focusedBoxOf(words: String): Rect? {
        val n = compose.onAllNodes(card(words)).fetchSemanticsNodes().firstOrNull() ?: return null
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        val b = Rect(n.positionInRoot, n.size.toSize())
        return Rect((b.left - screen.left) / dp, (b.top - screen.top) / dp, (b.right - screen.left) / dp, (b.bottom - screen.top) / dp)
    }

    @Test
    fun aCardGoneUnderTheFocusHandsItToTheNextElseTheOneBeforeElseTheFirstPoster() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("Copper Crown", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
        compose.runOnIdle { shown = full.copy(upNext = upNext - copper) }
        remote.waitForFocus("The Quiet Orbit")
        settle()
        assertTrue("the next card; focus on ${remote.focused()}", remote.isFocused(card("The Quiet Orbit")))

        val last = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Salt & Signal", max = 6)
        assertTrue("never reached the last card; at $last", "Salt & Signal" in last)
        compose.runOnIdle { shown = full.copy(upNext = upNext - copper - salt) }
        remote.waitForFocus("Glasswater Bay")
        settle()
        assertTrue("the one before; focus on ${remote.focused()}", remote.isFocused(card("Glasswater Bay")))

        compose.runOnIdle { shown = full.copy(upNext = emptyList()) }
        remote.waitForFocus("Harbor Lights")
        settle()
        assertTrue("none left: the first poster; focus on ${remote.focused()}", remote.isFocused(card("Harbor Lights")) && !exists(hasText("UP NEXT")))

        // Nothing collected either: the empty card's button.
        fresh(HomeState(recent, listOf(harbor), null, hasTmdbKey = true))
        remote.waitForFocus("Harbor Lights")
        settle()
        compose.runOnIdle { shown = HomeState(recent, emptyList(), null, hasTmdbKey = true) }
        remote.waitForFocus(SEARCH_INDEXERS)
        settle()
        assertEquals("none left, nothing collected: the empty card's button", SEARCH_INDEXERS, remote.focused())
    }

    /** The open menu's items' words, in order. */
    private fun menuItems(): List<String> = compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes().map { it.label() }

    /**
     * OK held on the focused card, as a remote's long press: its menu shows, the key still down, then the key's repeats and
     * its up a while after (which pick nothing in it).
     */
    private fun holdOk() {
        longOk(compose.mainClock::advanceTimeBy) {
            compose.waitUntil(5_000) { menuItems().isNotEmpty() }
            Thread.sleep(500)
        }
        compose.waitForIdle()
    }

    private fun nothingDone() = assertEquals(
        "played, removed, marked", Triple(emptyList<UpNextRow>(), emptyList<UpNextRow>(), emptyList<UpNextRow>()), Triple(played.toList(), fake.removed.toList(), fake.marked.toList()),
    )

    /** [item] in the focused card's menu, by a held OK and OK on it. */
    private fun fromMenu(item: String) {
        holdOk()
        remote.waitForFocus(REMOVE_FROM_UP_NEXT)
        assertEquals(item, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, item, max = 2))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private val bar = hasText("Removed “Copper Crown” from Up next")

    /**
     * L24, W7: a held OK on an Up next card opens its menu, on every card (M10), its first item focused; the held OK (its
     * repeats, its up) picks nothing in it and plays nothing. A film in progress: Remove from Up next, then Mark watched; a
     * next episode, and a release played without TMDB (whose card plays on OK), Remove only. Back closes it, on the card.
     */
    @Test
    fun aHeldOkOnAnUpNextCardOpensItsMenuOnItsFirstItemAndPicksNothing() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        listOf(
            copper to listOf(REMOVE_FROM_UP_NEXT, MARK_WATCHED),
            orbit to listOf(REMOVE_FROM_UP_NEXT),
            northbound to listOf(REMOVE_FROM_UP_NEXT),
        ).forEach { (row, items) ->
            assertEquals(row.title, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
            holdOk()
            remote.waitForFocus(REMOVE_FROM_UP_NEXT)
            assertEquals("${row.title}'s menu", items, menuItems())
            nothingDone()
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { menuItems().isEmpty() }
            remote.waitForFocus(row.title)
            assertTrue("still Home", current(HomeRoute::class))
        }
        nothingDone()
    }

    /** L24: Remove takes the card out and Undo has the focus; Up from it hands the focus to the next card, and the bar goes. */
    @Test
    fun removeGivesUndoTheFocusThenUpHandsItToTheNextCard() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("Copper Crown", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
        fromMenu(REMOVE_FROM_UP_NEXT)
        compose.waitUntil(5_000) { exists(bar) }
        remote.waitForFocus(UNDO)
        assertEquals(listOf(copper), fake.removed.toList())
        assertTrue("Copper Crown's card is gone", !exists(card("Copper Crown")))
        assertTrue("the bar's time: ${fake.timeouts}", fake.timeouts.single() >= 10_000)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus("The Quiet Orbit")
        assertTrue("the next card; focus on ${remote.focused()}", remote.isFocused(card("The Quiet Orbit")))
        compose.waitUntil(5_000) { !exists(bar) }
    }

    /** Back from Undo, after the last card went: the card before it. */
    @Test
    fun backFromUndoAfterTheLastCardHandsTheFocusToTheOneBefore() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        val last = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Salt & Signal", max = 6)
        assertTrue("never reached the last card; at $last", "Salt & Signal" in last)
        fromMenu(REMOVE_FROM_UP_NEXT)
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Glasswater Bay")
        assertTrue("the one before; focus on ${remote.focused()}", remote.isFocused(card("Glasswater Bay")))
        assertTrue("still Home", current(HomeRoute::class))
        assertEquals(listOf(salt), fake.removed.toList())
    }

    /**
     * The bar's time running out with Undo focused, after Up next's only card went: the first poster; with nothing
     * collected either, Up from Undo: the empty card's button.
     */
    @Test
    fun afterTheOnlyCardTheFocusGoesToTheFirstPosterElseTheEmptyCardsButton() {
        showHome(full.copy(upNext = listOf(copper)))
        remote.waitForFocus("Copper Crown")
        settle()
        fromMenu(REMOVE_FROM_UP_NEXT)
        remote.waitForFocus(UNDO)
        // The fake's 2 s.
        remote.waitForFocus("Harbor Lights", timeoutMs = 10_000)
        assertTrue("the first poster; focus on ${remote.focused()}", remote.isFocused(card("Harbor Lights")) && !exists(hasText("UP NEXT")))
        assertTrue(!exists(bar))

        fresh(HomeState(recent, listOf(copper), null, hasTmdbKey = true))
        remote.waitForFocus("Copper Crown")
        settle()
        fromMenu(REMOVE_FROM_UP_NEXT)
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus(SEARCH_INDEXERS)
        assertEquals(SEARCH_INDEXERS, remote.focused())
    }

    /** L24: Undo puts the card back in its place, and the focus on it (the collection hands it to the neighbour instead). */
    @Test
    fun undoBringsTheCardBackInItsPlaceFocused() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("Copper Crown", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
        fromMenu(REMOVE_FROM_UP_NEXT)
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(card("Copper Crown")) }
        remote.waitForFocus("Copper Crown")
        settle()
        assertTrue("back on the card; focus on ${remote.focused()}", remote.isFocused(card("Copper Crown")))
        assertEquals(upNext, shown!!.upNext)
        assertEquals(1, fake.undone)
        assertTrue(!exists(bar))
    }

    /**
     * Home composed anew while an earlier Remove's Undo is still offered, as when it comes back from a page or a tab within
     * the bar's time: the focus goes by Home's own rules, here to its first card, not to Undo, which takes it only right
     * after an edit made there (L24); and leaving Home lost Undo, the removal staying (L23). Checked well inside the fake's
     * 2 s, while the bar would still be up.
     */
    @Test
    fun homeComposedAnewWhileUndoIsOfferedKeepsItsOwnFocusAndTheBarIsGone() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        assertEquals("Copper Crown", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" | "))
        fromMenu(REMOVE_FROM_UP_NEXT)
        remote.waitForFocus(UNDO)
        fresh(shown)
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        assertTrue("Home's first focus, its first card; focus on ${remote.focused()}", remote.isFocused(card("Harbor Lights")))
        assertTrue("Undo still offered after Home went", !exists(bar))
        assertEquals(listOf(copper), fake.removed.toList())
        assertTrue("Copper Crown's card came back", !exists(card("Copper Crown")))
    }

    /** Mark watched on an episode in progress: the show keeps its card (its next episode), and the focus goes back to it. */
    @Test
    fun markWatchedOnAnEpisodeKeepsItsCardAndTheFocusComesBackToIt() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        fromMenu(MARK_WATCHED)
        compose.waitUntil(5_000) { exists(hasText("Marked “Harbor Lights S02E05” watched")) }
        remote.waitForFocus(UNDO)
        assertEquals(listOf(harbor), fake.marked.toList())
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus("Harbor Lights")
        assertTrue("back on its card; focus on ${remote.focused()}", remote.isFocused(card("Harbor Lights")))
    }

    /** The first section's header on the safe area's top line, as the mockup has it; the sections apart by their gap only. */
    @Test
    fun theFirstSectionsHeaderIsOnTheSafeAreasTopLine() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        val top = (compose.onNode(hasText("UP NEXT")).fetchSemanticsNode().boundsInRoot.top - screen.top) / dp
        assertTrue("UP NEXT at %.1f dp from the screen's top, not the safe line's 27".format(top), abs(top - 27) <= 1)
    }

    @Test
    fun seeAllOpensTheCollectionTab() {
        showHome(full)
        remote.waitForFocus("Harbor Lights")
        settle()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        val more = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, SEE_ALL, max = 16)
        assertTrue("never reached See all; at $more", SEE_ALL in more)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { current(CollectionRoute::class) }
        compose.waitUntil(5_000) { exists(hasText(COLLECTION_TAB)) }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.singleOrNull()
    }

    /**
     * At 1x and 1.3x, everywhere the remote goes on Home: what has the focus is whole inside the safe area, its section's
     * header shown in full; nothing is cut (a card's title and line may end in an ellipsis, as they are made to; the
     * recent searches, See all, the hint and the buttons show all their words).
     */
    @Test
    fun everythingKeepsInsideTheSafeAreaAndShowsInFullAt1xAnd13x() {
        val problems = mutableListOf<String>()
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            if (s == 1f) showHome(full) else fresh(full)
            remote.waitForFocus("Harbor Lights")
            settle()
            fun check(where: String) {
                compose.outsideTheSafeArea().forEach { problems += "${s}x, $where: outside the safe area: $it" }
                val box = focusedBox()
                if (box.left < 48 - 1 || box.right > 912 + 1 || box.top < 27 - 1 || box.bottom > 513 + 1) problems += "${s}x, $where: the focused ${remote.focused()} at $box"
                val texts = compose.onAllNodes(hasAnyAncestor(isFocused()) or isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                val card = remote.isFocused(SemanticsMatcher("a card") { n -> n.config.getOrNull(SemanticsActions.OnClick)?.label in setOf("Open", "Play") && n.config.getOrNull(SemanticsProperties.Role) == null })
                texts.forEach { n ->
                    val layout = n.textLayout() ?: return@forEach
                    val words = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                    val clipped = layout.size.height < layout.multiParagraph.height - 1
                    val ellipsized = layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)
                    if (clipped || (ellipsized && !card)) problems += "${s}x, $where: \"$words\" is cut (${layout.lineCount} lines in ${layout.size})"
                }
            }
            val headers = listOf("UP NEXT", "COLLECTION · 214 SHOWS · 380 MOVIES", "RECENT SEARCHES")
            listOf(0, 1, 2).forEach { section ->
                if (section > 0) remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
                settle()
                if (compose.textsShownInFull()[headers[section]] != true) problems += "${s}x: the header \"${headers[section]}\" isn't shown in full"
                sweep().forEach { check(it.substringBefore(" | ")) }
            }
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            settle()
            check(remote.focused())
            if (IMPORT_LABEL !in remote.focused()) problems += "${s}x: Down from the recent searches went to ${remote.focused()}"
        }
        scale = 1f
        assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    private companion object {
        /** Invented ids; nothing real has them. */
        const val HARBOR = 900301
        const val ORBIT = 900302
        const val GLASSWATER = 900303
        const val SALT = 900304
        const val MOVIE = 900311
        const val POSTERS = 900320
        const val INDEXER = "tv-home-layout-test"
        const val COLLECTION_TAB = "Collection tab"
        const val UNDO = "Undo"
        /** The rail's items, the ones the app shows: read from its tabs, so none is missed (Discover's in debug builds). */
        val RAIL = tabsFor(FormFactor.Tv).map { it.label }
    }
}

/** A stand-in for a page or a tab: its name, on a button that takes the focus. */
@androidx.compose.runtime.Composable
private fun StandIn(name: String) {
    Column { SecondaryButton(name, {}) }
}

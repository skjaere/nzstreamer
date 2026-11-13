package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.BackToSearch
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.CollectionJumpState
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.collection.CollectionHarness
import io.skjaere.usenet.app.collection.CollectionScreen
import io.skjaere.usenet.app.collection.CollectionViewModel
import io.skjaere.usenet.app.collection.JUMP_TO_LETTER
import io.skjaere.usenet.app.collection.REMOVE_ACTION
import io.skjaere.usenet.app.collection.SELECT_ACTION
import io.skjaere.usenet.app.collection.SELECT_PILL
import io.skjaere.usenet.app.collection.UNWATCHED_ACTION
import io.skjaere.usenet.app.collection.WATCHED_ACTION
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowState
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.jumpToCollection
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.rememberCollectionJumps
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.TypeScale
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.CollectionViewPrefs
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchKind
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Serializable
private data class TitlePageRoute(val tmdbId: Int)

/**
 * The Collection tab on a TV (TV-4), as a TV shows it (forced on a phone emulator, 960 x 540 dp) in the TV shell, over a
 * library of its own, with a remote: each first focus the TV design gives, Left from a line's first poster into
 * the rail and Right back, Right stopping at a line's last poster, Up from the first line to the lines above, Back's
 * order, the paging keys in Recently watched and in A–Z, Jump to letter, the poster a page was opened from, a jump's new
 * poster, and every text inside the safe area and whole at 1x and 1.3x.
 */
@RunWith(AndroidJUnit4::class)
class TvCollectionGridTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel
    private lateinit var nav: NavHostController
    private lateinit var jumps: CollectionJumpState
    private var scale by mutableFloatStateOf(1f)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        harness?.close()
    }

    /** The app's graph's order in the tab's destination, over a library of [shows] and [movies]; [saved] the ViewModel's saved state. */
    private fun show(shows: Int = 20, movies: Int = 5, hasKey: Boolean = true, prefs: CollectionViewPrefs = CollectionViewPrefs(), saved: Map<String, Any?> = emptyMap(), open: Boolean = true) {
        val h = CollectionHarness(instrumentation.targetContext, shows = shows, movies = movies, prefs = prefs, hasKey = hasKey).also { harness = it; it.seed() }
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent({ scale }) {
            nav = rememberNavController()
            jumps = rememberCollectionJumps(nav)
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text(HOME) }
                    }
                    composable<SearchRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text(SEARCH) }
                    }
                    composable<SettingsRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Button(onClick = {}) { Text("Settings stand-in") }
                    }
                    composable<CollectionRoute> { screen ->
                        TopLevelBack(nav, screen)
                        BackToSearch(nav, jumps, screen)
                        vm = viewModel { CollectionViewModel(h.deps, SavedStateHandle(saved)) }
                        CollectionScreen(
                            onShow = { nav.navigate(TitlePageRoute(it)) }, onMovie = { nav.navigate(TitlePageRoute(it)) }, onAdd = {}, onMetadata = {}, vm = vm,
                            jump = jumps.jump, onJumpDone = jumps::jumpDone, backToSearch = jumps.backToSearch,
                        )
                    }
                    composable<TitlePageRoute> { entry ->
                        BackHandler { nav.popBackStack() }
                        Button(onClick = {}) { Text("Page of ${entry.toRoute<TitlePageRoute>().tmdbId}") }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (!open) return
        compose.runOnIdle { nav.switchTab(CollectionRoute) }
        compose.waitUntil(10_000) { runCatching { vm.ui.value != null }.getOrDefault(false) }
    }

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    /** The switch's Shows side, as it says how many there are: the tab's first focus. */
    private fun shows(): String = "Shows ${harness!!.fixture.shows}"

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun onTheRail() = remote.isFocused(railItem("Collection"))

    /** A poster in the grid, by its title's row. */
    private fun poster(row: Int) = rows()[row].a11y

    /** From the switch, Down to the first line's first poster, then on to [row]: Down a line at a time, then Right. */
    private fun walkTo(row: Int) {
        remote.waitForFocus(shows())
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, poster(0), max = 4)
        assertEquals(poster(0), remote.focused())
        repeat(row / 7) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(row % 7) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(poster(row), remote.focused())
    }

    @Test
    fun theFirstFocusIsTheSwitchsSelectedSide() {
        show()
        remote.waitForFocus(shows())
        assertEquals(shows(), remote.focused())
        assertTrue("the movies' count beside it", exists(hasText(MOVIES)))
    }

    /**
     * The switch's other side, focused, turns over in bold as wide as it always is: the chips beside it don't move
     * (FocusLook's rule). At twice the text size, and a count of three figures, a bold label's few more dp are pixels
     * even at a phone emulator's density.
     */
    @Test
    fun focusingTheSwitchsOtherSideMovesNothing() {
        scale = 2f
        show(movies = 380)
        remote.waitForFocus(shows())
        fun bounds(m: SemanticsMatcher) = compose.onNode(m).fetchSemanticsNode().boundsInRoot
        val side = bounds(hasText("Movies 380"))
        val chip = bounds(hasText(FIRST_CHIP))
        assertEquals("Movies 380", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        compose.waitForIdle()
        assertEquals("the side's own width", side, bounds(hasText("Movies 380")))
        assertEquals("the chips after it", chip, bounds(hasText(FIRST_CHIP)))
    }

    /**
     * A focused chip grows a little (drawn, 1.05 times): the chips' row has room for it at its ends, which it would clip,
     * for the first chip and for the last scrolled to the end.
     */
    @Test
    fun theChipsRowHasRoomAtItsEndsForAFocusedChipsGrowth() {
        show()
        remote.waitForFocus(shows())
        val row = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and inTvCollection).fetchSemanticsNode().boundsInRoot
        fun box(words: String) = compose.onNode(hasText(words, substring = true) and hasClickAction()).fetchSemanticsNode().let { androidx.compose.ui.geometry.Rect(it.positionInRoot, it.size.toSize()) }
        assertEquals("All, 20 shows", remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "All, 20", max = 3))
        val first = box(FIRST_CHIP)
        assertTrue("the first chip's growth clipped: $first in $row", first.left - first.width * GROWTH >= row.left)
        val last = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Finished", max = 8)
        assertTrue("on the last chip: $last", last.startsWith("Finished"))
        compose.waitForIdle()
        val end = box("Finished ")
        assertTrue("the last chip's growth clipped: $end in $row", end.right + end.width * GROWTH <= row.right)
    }

    @Test
    fun anEmptySideWithAKeyStartsOnItsFindButton() {
        show(shows = 0)
        remote.waitForFocus("Find a show")
        assertTrue("TV words, no tap", exists(hasText(io.skjaere.usenet.app.collection.noTitlesLine("show", tv = true))))
    }

    @Test
    fun anEmptySideWithoutAKeyStartsOnAddATmdbKey() {
        show(shows = 0, hasKey = false)
        remote.waitForFocus("Add a TMDB key")
    }

    @Test
    fun titlesWithoutAKeyStartOnTheFirstPosterUnderTheHint() {
        show(hasKey = false)
        remote.waitForFocus(poster(0))
        assertTrue(exists(hasText("Add a TMDB key for episode lists and air dates")))
    }

    /** The fixture's five movies: one still to come, three watched, one in progress; none unwatched. */
    @Test
    fun aFilterWithNothingStartsOnShowAll() {
        show(prefs = CollectionViewPrefs(side = "Movies", movieFilter = "Unwatched"))
        remote.waitForFocus("Show all")
    }

    @Test
    fun aSearchWithNothingInTheFilterStartsOnShowNInAll() {
        show(prefs = CollectionViewPrefs(showFilter = "Finished"), saved = mapOf("searchOpen" to true, "text" to "Avrel"))
        remote.waitForFocus("Show 1 in All")
    }

    /**
     * Without a key the first poster, under the hint, is the first focus: the first line, so Back goes to the rail, however
     * far its focus scrolled the grid. At twice the text size the lines above it are tallest, and the grid furthest down.
     */
    @Test
    fun withoutAKeyBackFromTheFirstPosterGoesToTheRail() {
        scale = 2f
        show(shows = 60, hasKey = false)
        remote.waitForFocus(poster(0))
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun theFocusComesBackToThePosterAPageWasOpenedFromPastTheFirstScreen() {
        show(shows = 60)
        walkTo(23)
        val opened = poster(23)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Page of", substring = true)) }
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(opened)
        compose.onNode(hasContentDescription(opened)).assertExists()
    }

    @Test
    fun aJumpFocusesTheNewPosterHighlightedAndOneBackReturnsToSearch() {
        show(open = false)
        compose.runOnIdle { nav.switchTab(SearchRoute()) }
        compose.waitUntil(5_000) { exists(hasText(SEARCH)) }
        val added = harness!!.addShow(1, "Glimmer Lake")
        compose.runOnIdle { nav.jumpToCollection(jumps, CollectionJump(added.value, "Glimmer Lake")) }
        compose.waitUntil(10_000) { runCatching { remote.focused().startsWith("Glimmer Lake") }.getOrDefault(false) }
        val node = compose.onNode(hasContentDescription("Glimmer Lake", substring = true) and isFocused()).fetchSemanticsNode()
        assertEquals("Just added", node.config.getOrNull(SemanticsProperties.StateDescription))
        compose.waitUntil(5_000) { nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(hasText(SEARCH)) }
    }

    /**
     * After a jump the user's move into the rail disarms Back to Search, from wherever in the tab it comes: here the mini
     * bar under the grid (a play that couldn't start, minimised). Back from the rail then goes Home, as it always does.
     */
    @Test
    fun theRailEnteredFromTheMiniBarAfterAJumpDisarmsBackToSearch() {
        show(open = false)
        val playback = instrumentation.targetContext.container.playback
        try {
            // A start with no releases to try fails at once; minimised, it shows in the mini bar.
            playback.play(PlayRequest(MINI_BAR, null, emptyList(), watch = WatchTarget(WatchKind.Release, MINI_BAR)))
            compose.waitUntil(5_000) { playback.state.value is PlayState.Failed }
            playback.minimize()
            compose.runOnIdle { nav.switchTab(SearchRoute()) }
            compose.waitUntil(5_000) { exists(hasText(SEARCH)) }
            val added = harness!!.addShow(1, "Glimmer Lake")
            compose.runOnIdle { nav.jumpToCollection(jumps, CollectionJump(added.value, "Glimmer Lake")) }
            compose.waitUntil(10_000) { runCatching { remote.focused().startsWith("Glimmer Lake") }.getOrDefault(false) }
            compose.waitUntil(5_000) { nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
            val bar = "Couldn't play $MINI_BAR"
            assertTrue("Down to the mini bar; on ${remote.focused()}", bar in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, bar, max = 6))
            remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue("focus on ${remote.focused()}", onTheRail())
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(hasText(HOME)) || exists(hasText(SEARCH)) }
            assertTrue("Back from the rail went to Search", exists(hasText(HOME)))
        } finally {
            playback.dismiss()
        }
    }

    @Test
    fun leftFromALinesFirstPosterEntersTheRailAndRightComesBack() {
        show(shows = 60)
        walkTo(21)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("focus on ${remote.focused()}", onTheRail())
        assertEquals(poster(21), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // And from the switch, one of the grid's own: back to it.
        remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        remote.waitForFocus(shows())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("focus on ${remote.focused()}", onTheRail())
        assertEquals(shows(), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /**
     * Right from the rail goes back to the poster left, also once it is no longer composed: here the grid scrolled to its
     * end meanwhile, as a re-sort under a sort that reads TMDB's facts can carry a poster away while the user is on the rail.
     */
    @Test
    fun rightFromTheRailBringsBackThePosterLeftWhenItIsNoLongerComposed() {
        show(shows = 60)
        walkTo(21)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("focus on ${remote.focused()}", onTheRail())
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3 + 59)
        compose.waitForIdle()
        assertTrue("still composed", !exists(hasContentDescription(poster(21))))
        assertEquals(poster(21), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).also { compose.waitForIdle() }.let { remote.focused() })
    }

    /** Without a key, the hint above the posters is an element the grid keeps: Left from it to the rail, and Right back to it. */
    @Test
    fun rightFromTheRailGoesBackToTheHintLeft() {
        show(hasKey = false)
        remote.waitForFocus(poster(0))
        assertTrue("Up to the hint; on ${remote.focused()}", remote.press(KeyEvent.KEYCODE_DPAD_UP).startsWith(HINT))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("focus on ${remote.focused()}", onTheRail())
        val back = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from the rail: on \"$back\"", back.startsWith(HINT))
    }

    /** Poster 40 (five lines down, far past the first screen), from the tab's first focus: the switch, or without a key the first poster. */
    private fun walkTo40(hasKey: Boolean) {
        if (hasKey) return walkTo(40)
        remote.waitForFocus(poster(0))
        repeat(5) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(5) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(poster(40), remote.focused())
    }

    /**
     * From poster 40 Back up to the switch, which takes the grid to its top, and Left to the rail; then another tab, and
     * OK on Collection again: the tab is composed anew, its grid's saved state restored. [throughHome]: OK on Home, and
     * from its content Left to the rail. Else OK on Search and from its content Back to the rail, then Back from
     * the rail to Home, which closes Search and keeps the focus on the rail: nothing the shell's content last had is
     * left on the screen as Collection opens.
     */
    private fun leaveAndOpenAgainByTheRail(throughHome: Boolean) {
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(shows())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the switch: focus on ${remote.focused()}", onTheRail())
        // The rail as the app shows it, Discover between Home and Collection in debug builds.
        val rail = tabsFor(FormFactor.Tv).map { it.label }
        if (throughHome) {
            repeat(rail.indexOf("Collection") - rail.indexOf("Home")) { remote.press(KeyEvent.KEYCODE_DPAD_UP) }
            assertTrue("Up to Home: focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus(HOME)
            remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue("Left from Home: focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
        } else {
            repeat(rail.indexOf("Collection") - rail.indexOf("Search")) { remote.press(KeyEvent.KEYCODE_DPAD_UP) }
            assertTrue("Up to Search: focus on ${remote.focused()}", remote.isFocused(railItem("Search")))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus(SEARCH)
            remote.press(KeyEvent.KEYCODE_BACK)
            assertTrue("Back from Search: focus on ${remote.focused()}", remote.isFocused(railItem("Search")))
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(hasText(HOME)) && !exists(hasText(SEARCH)) }
            assertTrue("Back from the rail: focus on ${remote.focused()}", remote.isFocused(railItem("Home")))
        }
        repeat(rail.indexOf("Collection") - rail.indexOf("Home")) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("Down to Collection: focus on ${remote.focused()}", onTheRail())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /**
     * The tab left and opened again by the rail's OK: its first focus is the tab's own, the switch's selected side (or
     * without a key the first poster), the grid at its top. Not the poster last focused, which only Right from the rail
     * comes back to, in the composition it was focused in.
     */
    private fun okOnTheRailOpensTheTabOnItsOwnFirstFocus(hasKey: Boolean, throughHome: Boolean) {
        show(shows = 60, hasKey = hasKey)
        walkTo40(hasKey)
        leaveAndOpenAgainByTheRail(throughHome)
        val first = if (hasKey) shows() else poster(0)
        remote.waitForFocus(first)
        theTopLinesWhole("the tab opened again")
        assertEquals(first, remote.focused())
    }

    @Test
    fun okOnTheRailFromHomeOpensTheTabOnTheSwitch() {
        okOnTheRailOpensTheTabOnItsOwnFirstFocus(hasKey = true, throughHome = true)
    }

    @Test
    fun withoutAKeyOkOnTheRailFromHomeOpensTheTabOnTheFirstPoster() {
        okOnTheRailOpensTheTabOnItsOwnFirstFocus(hasKey = false, throughHome = true)
    }

    @Test
    fun okOnTheRailAfterSearchClosedOpensTheTabOnTheSwitch() {
        okOnTheRailOpensTheTabOnItsOwnFirstFocus(hasKey = true, throughHome = false)
    }

    @Test
    fun withoutAKeyOkOnTheRailAfterSearchClosedOpensTheTabOnTheFirstPoster() {
        okOnTheRailOpensTheTabOnItsOwnFirstFocus(hasKey = false, throughHome = false)
    }

    /**
     * The rows re-sorted under the focus (here a watch just recorded, which Recently watched puts first; TMDB's facts
     * coming in do the same under the sorts that read them): the focused poster, carried off the screen, is brought back
     * into view with it, the focus kept on it. On a TV the focus is the only pointer.
     */
    @Test
    fun aPosterMovedUnderTheFocusIsBroughtBackIntoView() {
        show(shows = 60)
        walkTo(21)
        val key = rows()[21].key
        // Played a little of its first episode now.
        val episode = EpisodeRef(key.tmdbId, 1, 1, "Episode 1")
        val template = WatchState(episode.key, WatchKind.Episode, showTmdbId = key.tmdbId, season = 1, episode = 1, title = episode.title, updatedAt = 0)
        runBlocking { harness!!.library.recordProgress(template, positionMs = 120_000, durationMs = 2_400_000) }
        compose.waitUntil(5_000) { rows().first().key == key }
        compose.waitForIdle()
        val now = rowOf(key)!!.a11y
        assertEquals(now, remote.focused())
        compose.onNode(hasContentDescription(now) and isFocused()).assertIsDisplayed()
    }

    /** The sort's menu, the TV's: its first item focused as it opens, the current sort marked; a pick sorts, and the focus is back on the sort. */
    @Test
    fun theSortMenuOpensOnItsFirstItemAndAPickSorts() {
        show()
        remote.waitForFocus(shows())
        compose.onNode(hasContentDescription("Sort: Recently watched")).requestFocus()
        remote.waitForFocus("Sort: Recently watched")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Recently watched")
        assertEquals("A–Z", remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "A–Z", max = 5))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.sortName == "AZ" }
        remote.waitForFocus("Sort: A–Z")
    }

    @Test
    fun rightStopsAtALinesLastPosterAndUpFromTheFirstLineGoesToTheLinesAbove() {
        show()
        walkTo(6)
        assertEquals("Right stops at the line's last poster", poster(6), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // 20 titles: the last line has six; Right from its last stays.
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, poster(19), max = 3)
        assertEquals(poster(19), remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, poster(19), max = 2))
        assertEquals(poster(19), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, poster(5), max = 3)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        val above = remote.focused()
        assertTrue("Up from the first line: the lines above, not \"$above\"", rows().none { it.a11y == above } && !onTheRail())
    }

    @Test
    fun backFromBelowTheFirstScreenGoesUpToTheSwitchThenToTheRail() {
        show(shows = 60)
        walkTo(28)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(shows())
        assertTrue("still the Collection tab, not the rail", !onTheRail())
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    @Test
    fun backFromTheFirstScreenGoesToTheRail() {
        show(shows = 60)
        walkTo(2)
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    /** The top line and the switch's line, each text of them whole on the screen (not under the grid's top edge). */
    private fun theTopLinesWhole(after: String) {
        compose.waitForIdle()
        val shown = compose.textsShownInFull()
        val cut = listOf(FIND_PILL_TEXT, SELECT_PILL, shows()).filter { shown[it] != true }
        assertEquals("$after: cut at the grid's top", emptyList<String>(), cut)
    }

    /** The first line is on the first screen with the lines above it: going back to it shows them whole, not cut at the pivot. */
    @Test
    fun backUpToTheFirstLineShowsTheLinesAboveItWhole() {
        show(shows = 60)
        walkTo(0)
        theTopLinesWhole("Down from the switch")
        assertEquals(poster(7), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_DPAD_UP))
        theTopLinesWhole("Up from the second line")
        assertEquals(poster(14), remote.press(KeyEvent.KEYCODE_DPAD_DOWN).let { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) })
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        theTopLinesWhole("CH up from the third line")
    }

    /** Without a key the hint sits above the posters, and the first poster is the first focus: the screen still opens whole. */
    @Test
    fun withoutAKeyTheFirstScreenOpensWhole() {
        show(shows = 60, hasKey = false)
        remote.waitForFocus(poster(0))
        theTopLinesWhole("the tab opened")
    }

    @Test
    fun thePagingKeysMoveThreeLinesInRecentlyWatched() {
        show(shows = 60)
        remote.waitForFocus(shows())
        assertEquals("from the header: the first poster", poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(poster(21), remote.press(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(poster(42), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("short of three lines: the last line's first", poster(56), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(poster(35), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(poster(14), remote.press(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals("from the first line: the switch", shows(), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
    }

    @Test
    fun thePagingKeysMoveALetterInAToZ() {
        show(shows = 60, prefs = CollectionViewPrefs(showSort = "AZ"))
        remote.waitForFocus(shows())
        val letters = vm.ui.value!!.view.letters!!
        val starts = letters.letters.mapNotNull { letters.first[it] }
        assertEquals(poster(starts[0]), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(poster(starts[1]), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(poster(starts[2]), remote.press(KeyEvent.KEYCODE_PAGE_DOWN))
        // Past its letter's first: the first of its letter, then the letter before.
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(poster(starts[2] + 1), remote.focused())
        assertEquals(poster(starts[2]), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(poster(starts[1]), remote.press(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test
    fun jumpToLetterLandsOnTheLettersFirstPoster() {
        show(shows = 60, prefs = CollectionViewPrefs(showSort = "AZ"))
        remote.waitForFocus(shows())
        compose.onNode(hasText(JUMP_TO_LETTER) and hasClickAction()).requestFocus()
        remote.waitForFocus(JUMP_TO_LETTER)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The grid starts on the first title's letter, '#'; R is two lines down (N to T), the fifth.
        remote.waitForFocus("#, 2 shows")
        repeat(2) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(4) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("R, 2 shows", remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val r = vm.ui.value!!.view.letters!!.first.getValue('R')
        remote.waitForFocus(poster(r))
    }

    /** Every form of a poster's line fits its one line at 1x; titles end in an ellipsis when long, by design. */
    @Test
    fun everyCardLineFitsItsLineAt1x() {
        val lines = listOf(
            "S02E05", "Resume S02E05", "Up to date", "Back 14 Nov", "Back Nov 2027", "Airs 14 Nov", "Airs Nov 2027", "Finished",
            "Checking…", "Couldn't load", "Not on TMDB", "No episodes yet",
            "2019 · 1 h 58", "2019 · 45 min", "42 min left", "1 h 12 left", "✓ Watched", "Out 12 Mar", "Out Mar 2027", "Date not set",
        )
        compose.setTvContent {
            // Three lines of cards are taller than the screen: each has its whole height, scrolled.
            Column(Modifier.background(colors.background).verticalScroll(rememberScrollState())) {
                lines.chunked(7).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.posterGutter)) {
                        line.forEach { PosterCard("A title", posterPath = null, showArtwork = false, onClick = {}, subtitle = it, nameOnTile = false) }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.textsCut(intended = { it == "A title" }))
    }

    @Test
    fun everythingInsideTheSafeAreaAndWholeAt1xAnd13x() {
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            if (s == 1f) show(shows = 60) else compose.waitForIdle()
            remote.waitForFocus(shows())
            compose.waitForIdle()
            val titles = rows().map { it.text.name }.toSet()
            val lines = rows().map { it.cardLine }.toSet()
            assertEquals("at ${s}x", emptyList<String>(), compose.outsideTheSafeArea())
            // Titles end in an ellipsis by design; a line may too at a larger text size, never clipped.
            assertEquals("at ${s}x", emptyList<String>(), compose.textsCut(intended = { it in titles || (s > 1f && it in lines) }))
            // Down to the second line and on: still inside, with the grid scrolled.
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
            remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
            compose.waitForIdle()
            assertEquals("at ${s}x, scrolled", emptyList<String>(), compose.outsideTheSafeArea())
            assertEquals("at ${s}x, scrolled", emptyList<String>(), compose.textsCut(intended = { it in titles || (s > 1f && it in lines) }))
            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(shows())
        }
    }

    // The long OK's menu (TV-4's L13): no Play (a play starts on the title's page); Mark watched or unwatched by the title's
    // state, none for a show TMDB hasn't answered for; Remove, then Undo; Select, select mode with the poster selected.

    private fun rowOf(key: TitleKey): TitleRow? = rows().firstOrNull { it.key == key }

    /** The words of the menu's items, in order. */
    private fun menuItems(): List<String> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes().map { it.label() }

    /** The first show of a [state], with TMDB's facts (or without, [facts] false). */
    private fun showOf(state: ShowState, facts: Boolean = true): Int =
        rows().indexOfFirst { it is ShowRow && it.state == state && (it.details != null) == facts }.also { assertTrue("no such show", it >= 0) }

    /**
     * The poster at [row] walked to, then OK held on it as a remote's long press: its menu, with its first item focused, the
     * key's repeats and its up a while after (picking nothing in it).
     */
    private fun longOkOn(row: Int) {
        walkTo(row)
        longOk(compose.mainClock::advanceTimeBy) {
            compose.waitUntil(5_000) { menuItems().isNotEmpty() }
            Thread.sleep(500)
        }
        compose.waitForIdle()
    }

    @Test
    fun aLongOkOpensThePostersMenuOnItsFirstItemAndPicksNothing() {
        show()
        val i = showOf(ShowState.NotStarted)
        val before = rows()[i]
        longOkOn(i)
        remote.waitForFocus(WATCHED_ACTION)
        assertEquals("no Play: a play starts on the page", listOf(WATCHED_ACTION, REMOVE_ACTION, SELECT_ACTION), menuItems())
        val ui = vm.ui.value!!
        assertTrue("the held OK picked something: undo ${ui.undo}, busy ${ui.busy}, selection ${ui.selection}", ui.undo == null && ui.busy == null && ui.selection == null)
        assertEquals(ShowState.NotStarted, (rowOf(before.key) as ShowRow).state)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { menuItems().isEmpty() }
        remote.waitForFocus(before.a11y)
    }

    @Test
    fun aWatchedTitlesMenuMarksItUnwatchedAndAShowWithoutFactsHasNoMark() {
        show(shows = 60)
        val done = showOf(ShowState.Finished)
        val key = rows()[done].key
        val name = rows()[done].text.name
        longOkOn(done)
        remote.waitForFocus(UNWATCHED_ACTION)
        assertEquals(listOf(UNWATCHED_ACTION, REMOVE_ACTION, SELECT_ACTION), menuItems())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Marked “$name” unwatched")) }
        remote.waitForFocus(UNDO)
        assertTrue("still finished: ${rowOf(key)?.status}", (rowOf(key) as ShowRow).state != ShowState.Finished)
        // Undo marks it watched again, as it was.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { (rowOf(key) as? ShowRow)?.state == ShowState.Finished }
        // Its aired episodes aren't known: neither mark (TMDB hasn't answered for it).
        val factless = rows().indexOfFirst { it is ShowRow && it.details == null }
        assertTrue(factless >= 0)
        compose.onNode(hasText(shows())).requestFocus()
        longOkOn(factless)
        remote.waitForFocus(REMOVE_ACTION)
        assertEquals(listOf(REMOVE_ACTION, SELECT_ACTION), menuItems())
    }

    @Test
    fun theMenusMarkWatchedMarksThatTitleAndUndoPutsItBackWithTheFocusOnIt() {
        show()
        val i = showOf(ShowState.NotStarted)
        val key = rows()[i].key
        val name = rows()[i].text.name
        longOkOn(i)
        remote.waitForFocus(WATCHED_ACTION)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Marked “$name” watched")) }
        remote.waitForFocus(UNDO)
        assertTrue("marked: ${rowOf(key)?.status}", (rowOf(key) as ShowRow).state.let { it == ShowState.UpToDate || it == ShowState.Finished })
        assertTrue("select mode", vm.ui.value!!.selection == null)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { (rowOf(key) as? ShowRow)?.state == ShowState.NotStarted }
        // The focus back on the title marked, which stays.
        compose.waitUntil(5_000) { remote.focused() == rowOf(key)?.a11y }
    }

    @Test
    fun theMenusRemoveTakesTheTitleAwayThenUndoFocusedRestoresIt() {
        show()
        val before = rows()
        longOkOn(3)
        assertEquals(REMOVE_ACTION, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, REMOVE_ACTION, max = 3))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Removed “${before[3].text.name}”")) }
        remote.waitForFocus(UNDO)
        assertTrue(rows().none { it.key == before[3].key })
        assertEquals(19, runBlocking { harness!!.library.counts.first() }.shows)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { rows().map { it.key } == before.map { it.key } }
        assertEquals(20, runBlocking { harness!!.library.counts.first() }.shows)
        // As select mode's Remove: the focus to the poster after the one removed (slice 12's rule).
        remote.waitForFocus(before[4].a11y)
    }

    @Test
    fun theMenusSelectStartsSelectModeWithThatPosterSelected() {
        show()
        val row = rows()[2]
        longOkOn(2)
        assertEquals(SELECT_ACTION, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SELECT_ACTION, max = 3))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection?.keys == setOf(row.key) }
        remote.waitForFocus(row.a11y)
        val node = compose.onNode(hasContentDescription(row.a11y) and isFocused()).fetchSemanticsNode()
        assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Selected", node.config.getOrNull(SemanticsProperties.StateDescription))
    }

    /** TalkBack's actions on a poster (it has no long press): Select, and Remove from collection, which offers Undo. */
    @Test
    fun talkBacksActionsOnAPosterAreSelectAndRemove() {
        show()
        val row = rows()[1]
        val actions = compose.onNode(hasContentDescription(row.a11y)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(SELECT_ACTION, REMOVE_ACTION), actions.map { it.label })
        compose.runOnUiThread { actions.first { it.label == REMOVE_ACTION }.action() }
        compose.waitUntil(5_000) { exists(hasText("Removed “${row.text.name}”")) }
        assertTrue(rows().none { it.key == row.key })
    }

    /** The Undo bar's Undo is in the TV's type scale (its bodyStrong), as the words beside it and the pills are: no size of its own. */
    @Test
    fun theUndoBarsUndoIsInTheTvsTypeScale() {
        show()
        val row = rows()[1]
        val actions = compose.onNode(hasContentDescription(row.a11y)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.first { it.label == REMOVE_ACTION }.action() }
        remote.waitForFocus(UNDO)
        val undo = compose.onNode(hasText(UNDO) and inTvCollection, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        undo.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(TypeScale.Tv.bodyStrong.fontSize, layouts.single().layoutInput.style.fontSize)
    }

    // Select mode on the posters (TV-4's L14): a 32 dp check circle at a poster's top left, NEW kept at its top right, no
    // tint over the picture; Up from the first line to the action pills; Remove, then Undo with the focus; Up, Back or the
    // time running out hands the focus to the poster after those removed (or the first marked), else the switch.

    /** Select mode by Select, with the remote: the focus on the first poster on screen, nothing selected yet. */
    private fun selectMode() {
        remote.waitForFocus(shows())
        compose.onNode(hasText(SELECT_PILL) and hasClickAction()).requestFocus()
        remote.waitForFocus(SELECT_PILL)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(poster(0))
        assertTrue(vm.ui.value!!.selection != null)
    }

    /** What [row]'s poster shows, and its density (px per dp): its picture at the top, 98 dp wide. */
    private fun image(row: Int): Pair<PixelMap, Float> {
        val node = compose.onNode(hasContentDescription(poster(row)))
        return node.captureToImage().toPixelMap() to node.fetchSemanticsNode().layoutInfo.density.density
    }

    /** The share of [image]'s pixels in the box (dp) near [colour]. */
    private fun share(image: Pair<PixelMap, Float>, colour: Color, left: Float, top: Float, right: Float, bottom: Float): Float {
        val (map, dp) = image
        val xs = (left * dp).toInt() until (right * dp).toInt()
        val ys = (top * dp).toInt() until (bottom * dp).toInt()
        val near = xs.sumOf { x -> ys.count { y -> map[x, y].let { abs(it.red - colour.red) < 0.06f && abs(it.green - colour.green) < 0.06f && abs(it.blue - colour.blue) < 0.06f } } }
        return near.toFloat() / (xs.count() * ys.count())
    }

    @Test
    fun aSelectedPosterHasTheAccentsCheckCircleAtItsTopLeftAnUnselectedOneARingAndNeitherATint() {
        show()
        selectMode()
        // The first two selected (the second has NEW), then the focus away from all three looked at.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(4) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(poster(5), remote.focused())
        assertTrue("the second has NEW", (rows()[1] as ShowRow).newEpisodes > 0)
        compose.waitForIdle()
        val palette = AppColors()
        val selected = image(0)
        val withNew = image(1)
        val unselected = image(2)
        // The mark's box, 6 dp in from the picture's top left, 32 dp across.
        assertTrue("selected: the accent's circle", share(selected, palette.accent, 6f, 6f, 38f, 38f) > 0.5f)
        assertTrue("unselected: no accent in the mark", share(unselected, palette.accent, 6f, 6f, 38f, 38f) < 0.05f)
        assertTrue("unselected: a ring in the mark's outline colour", share(unselected, palette.markOutline, 6f, 6f, 38f, 38f) > 0.08f)
        assertTrue("unselected: the ring's middle is the picture", share(unselected, TILE, 18f, 18f, 26f, 26f) > 0.9f)
        assertTrue("NEW stays at the top right", share(withNew, palette.accentTint, 64f, 8f, 90f, 22f) > 0.3f)
        // No tint over the picture, selected or not.
        listOf(selected, withNew, unselected).forEach { assertTrue("the picture tinted", share(it, TILE, 30f, 60f, 68f, 100f) > 0.9f) }
        // TalkBack: a checkbox, selected or not; Remove its one action (Select is what OK does now).
        fun node(row: Int) = compose.onNode(hasContentDescription(poster(row))).fetchSemanticsNode().config
        assertEquals(Role.Checkbox, node(0).getOrNull(SemanticsProperties.Role))
        assertEquals("Selected", node(0).getOrNull(SemanticsProperties.StateDescription))
        assertEquals(true, node(0).getOrNull(SemanticsProperties.Selected))
        assertEquals("Not selected", node(2).getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf(REMOVE_ACTION), node(2)[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    fun upFromTheFirstLineInSelectModeReachesTheActionPills() {
        show()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val line = setOf("Leave select mode", WATCHED_ACTION, UNWATCHED_ACTION, "Remove 1 show from the collection", "Select all (20)")
        listOf(0, 3, 6).forEach { column ->
            compose.onNode(hasContentDescription(poster(column))).requestFocus()
            remote.waitForFocus(poster(column))
            val up = remote.press(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue("Up from column ${column + 1}: \"$up\"", up in line)
        }
    }

    /** Toward the top from the first line, the paging keys go to the select line's actions: the switch is hidden in select mode. */
    @Test
    fun thePagingKeysInSelectModeGoUpFromTheFirstLineToTheActions() {
        show()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(poster(7), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(WATCHED_ACTION, remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
    }

    @Test
    fun removeInSelectModeHandsTheFocusToUndoAndUpGoesToThePosterAfter() {
        show()
        val before = rows()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(WATCHED_ACTION, remote.press(KeyEvent.KEYCODE_BACK))
        assertEquals("Remove 2 shows from the collection", remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove 2 shows", max = 3))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Removed 2 shows")) }
        remote.waitForFocus(UNDO)
        assertEquals(18, runBlocking { harness!!.library.counts.first() }.shows)
        assertTrue("select mode left", vm.ui.value!!.selection == null)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus(before[2].a11y)
        // The bar stays for its time; OK on Undo from there brings them both back.
        assertTrue(exists(hasText("Removed 2 shows")))
        okOnUndoRestores(before)
    }

    /** Back on Undo from the grid, and OK: the titles removed are all back, as they were. */
    private fun okOnUndoRestores(before: List<TitleRow>) {
        compose.onNode(hasText(UNDO) and hasClickAction()).requestFocus()
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { rows().map { it.key } == before.map { it.key } }
        assertEquals(20, runBlocking { harness!!.library.counts.first() }.shows)
    }

    @Test
    fun backOnUndoAfterARemoveGoesToThePosterAfterAndOkOnUndoRestores() {
        show()
        val before = rows()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(WATCHED_ACTION, remote.press(KeyEvent.KEYCODE_BACK))
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Remove 1 show", max = 3)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(before[2].a11y)
        assertTrue(rows().none { it.key == before[1].key })
        okOnUndoRestores(before)
    }

    @Test
    fun theUndoBarsTimeRunningOutHandsTheFocusToThePosterAfter() {
        show()
        val before = rows()
        longOkOn(4)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, REMOVE_ACTION, max = 3)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(UNDO)
        // Its 10 s (no accessibility service asks for longer).
        compose.waitUntil(15_000) { vm.ui.value?.undo == null }
        remote.waitForFocus(before[5].a11y)
    }

    @Test
    fun aMarkInSelectModeHandsTheFocusToUndoThenUpToTheFirstMarked() {
        show()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val first = rows()[2]
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, vm.ui.value!!.selection?.count)
        assertEquals(UNWATCHED_ACTION, remote.press(KeyEvent.KEYCODE_BACK).let { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) })
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Marked 2 shows unwatched")) }
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(5_000) { remote.focused() == rowOf(first.key)?.a11y }
    }

    /** Everything removed: nothing left to hand the focus to but the switch's selected side, the tab's first focus. */
    @Test
    fun withNothingLeftTheFocusGoesToTheSwitch() {
        show(shows = 3)
        selectMode()
        compose.onNode(hasText("Select all (3)") and hasClickAction()).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection?.count == 3 }
        compose.onNode(hasContentDescription("Remove 3 shows from the collection")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Removed 3 shows")) }
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Shows 0")
    }

    /** In select mode a long OK is an OK: it selects the poster (or not), once however long it is held, and opens no menu. */
    @Test
    fun aLongOkInSelectModeTogglesThePosterAndOpensNoMenu() {
        show()
        selectMode()
        longOk(compose.mainClock::advanceTimeBy)
        compose.waitForIdle()
        assertEquals(setOf(rows()[0].key), vm.ui.value!!.selection?.keys)
        assertTrue("a menu: ${menuItems()}", menuItems().isEmpty())
        longOk(compose.mainClock::advanceTimeBy)
        compose.waitForIdle()
        assertEquals(emptySet<TitleKey>(), vm.ui.value!!.selection?.keys)
        assertEquals(poster(0), remote.focused())
    }

    /** Nothing selected, the actions are off: Back leaves select mode, the poster keeping the focus (slice 12's S12-3). */
    @Test
    fun backInSelectModeWithNothingSelectedLeavesIt() {
        show()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val row = rows()[1]
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        remote.waitForFocus(row.a11y)
        assertTrue("the top lines back", exists(hasText(shows())))
    }

    /** The tab left and returned to in select mode, which stays on: the first focus is select mode's first action, its switch hidden. */
    @Test
    fun theTabReturnedToInSelectModeStartsOnItsActions() {
        show()
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, vm.ui.value!!.selection?.count)
        compose.runOnIdle { nav.switchTab(HomeRoute) }
        compose.waitUntil(5_000) { exists(hasText(HOME)) }
        compose.runOnIdle { nav.switchTab(CollectionRoute) }
        compose.waitUntil(5_000) { runCatching { vm.ui.value?.selection != null && !exists(hasText(HOME)) }.getOrDefault(false) }
        remote.waitForFocus(WATCHED_ACTION)
    }

    /**
     * In select mode TalkBack's Remove from collection on the only title (Undo then takes the focus): with nothing left to
     * hand the focus back to, select mode's ✕, nothing being selected, not the switch it hides.
     */
    @Test
    fun withNothingLeftInSelectModeTheFocusGoesToItsClose() {
        show(shows = 1)
        selectMode()
        val row = rows()[0]
        val actions = compose.onNode(hasContentDescription(row.a11y)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.first { it.label == REMOVE_ACTION }.action() }
        compose.waitUntil(5_000) { exists(hasText("Removed “${row.text.name}”")) }
        remote.waitForFocus(UNDO)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Leave select mode")
    }

    /** Select mode left by Back from the rail (select mode's Back goes first): the focus stays on the rail, the grid where it was. */
    @Test
    fun backOnTheRailInSelectModeLeavesItWithTheFocusOnTheRail() {
        show(shows = 60)
        selectMode()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(2) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(poster(14), remote.focused())
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("focus on ${remote.focused()}", onTheRail())
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        compose.waitForIdle()
        assertTrue("focus on ${remote.focused()}", onTheRail())
        // The grid where it was, not taken to its top: the poster left still in view.
        compose.onNode(hasContentDescription(poster(14))).assertIsDisplayed()
        assertEquals(poster(14), remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /**
     * Finished's Select all on the header line starts select mode with every title selected; select mode's line takes the
     * header lines' place, and the focus goes on to its own Select all, Select none now, rather than with the pill pressed.
     */
    @Test
    fun selectAllInFinishedPutsTheFocusOnSelectModesSelectNone() {
        show(prefs = CollectionViewPrefs(showFilter = "Finished"))
        remote.waitForFocus(shows())
        compose.onNode(hasText("Select all") and hasClickAction()).requestFocus()
        remote.waitForFocus("Select all")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection?.allSelected == true }
        remote.waitForFocus("Select none")
    }

    /** OK on select mode's ✕ leaves it as Back from its actions does: the focus on Select, where it was entered. */
    @Test
    fun okOnSelectModesCloseLeavesItWithTheFocusOnSelect() {
        show()
        selectMode()
        compose.onNode(hasContentDescription("Leave select mode")).requestFocus()
        remote.waitForFocus("Leave select mode")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        remote.waitForFocus(SELECT_PILL)
        assertEquals(SELECT_PILL, remote.focused())
    }

    // Find (TV-4's L16): the field behind its gate, which Find opens with its one press; Clear beside it.

    private val fieldFocused get() = exists(hasSetTextAction() and isFocused())

    private fun keyboardUp(): Boolean {
        var up = false
        compose.runOnUiThread { up = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return up
    }

    /** From the switch, Up to the top line's Find, and OK: the field, to type in, at once. */
    private fun openFind() {
        remote.waitForFocus(shows())
        assertEquals(FIND, remote.press(KeyEvent.KEYCODE_DPAD_UP))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fieldFocused }
    }

    /** Back until the focus is off the field, on its gate (a keyboard up takes the first Back itself, to go). */
    private fun backToTheGate() {
        remote.press(KeyEvent.KEYCODE_BACK)
        if (fieldFocused) remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(GATE)
    }

    @Test
    fun findOpensTheFieldWithItsOnePress() {
        show()
        openFind()
        assertTrue(vm.ui.value!!.searchOpen)
    }

    @Test
    fun typingFiltersAsItGoes() {
        show()
        openFind()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Avrel")
        compose.waitUntil(5_000) { vm.ui.value?.view?.rows?.map { it.text.name } == listOf("Avrel 0000") }
        assertTrue(exists(hasText("1 of 20 shows matches “Avrel”")))
        compose.onNode(hasContentDescription(poster(0))).assertExists()
    }

    /** Clear is beside the gate, not in the field (inside the gate a remote couldn't reach it): Right from the gate. */
    @Test
    fun clearBesideTheGateEmptiesTheFieldAndTheFocusGoesBackToTheGate() {
        show()
        openFind()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Avrel")
        backToTheGate()
        assertEquals(CLEAR, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.text == "" }
        remote.waitForFocus(GATE)
        assertTrue("still open", vm.ui.value!!.searchOpen)
        assertTrue("no keyboard", !fieldFocused && !keyboardUp())
    }

    @Test
    fun backFromTheFieldGoesToTheGateThenAnEmptyFindClosesToFindThenTheRail() {
        show()
        openFind()
        backToTheGate()
        assertTrue(vm.ui.value!!.searchOpen)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.searchOpen == false }
        remote.waitForFocus(FIND)
        remote.press(KeyEvent.KEYCODE_BACK)
        assertTrue("focus on ${remote.focused()}", onTheRail())
    }

    /** What is typed stays as Back leaves the field; the gate is passed over with the remote and raises no keyboard. */
    @Test
    fun theGateTakesTheRemotesFocusWithoutAKeyboard() {
        show()
        openFind()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Avrel")
        backToTheGate()
        compose.waitUntil(5_000) { !keyboardUp() }
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("off the gate: on ${remote.focused()}", remote.focused() != GATE)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus(GATE)
        Thread.sleep(KEYBOARD_MS)
        assertTrue("the gate, no keyboard", !fieldFocused && !keyboardUp())
        assertEquals("Avrel", vm.ui.value!!.text)
    }

    @Test
    fun theKeyboardsSearchGoesToTheFirstMatch() {
        show()
        openFind()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Avrel")
        compose.waitUntil(5_000) { vm.ui.value?.view?.rows?.size == 1 }
        compose.onNode(hasSetTextAction() and isFocused()).performImeAction()
        remote.waitForFocus(poster(0))
        assertTrue("the keyboard goes with the field", !fieldFocused)
    }

    private companion object {
        const val FIND = "Find in 20 shows"
        const val FIND_PILL_TEXT = "Find"
        const val UNDO = "Undo"
        /** The tile under a poster's picture, which shows without artwork (TvCards' TILE). */
        val TILE = Color(0xFF2E4A5C)
        const val GATE = "Search shows"
        const val CLEAR = "Clear the search"
        /** Longer than a keyboard takes to come up. */
        const val KEYBOARD_MS = 1_500L
        const val MOVIES = "Movies 5"
        const val FIRST_CHIP = "All 20"
        /** How far a focused chip spills past each of its ends, of its width (it grows 1.05 times about its middle). */
        const val GROWTH = 0.025f
        const val HOME = "Home stand-in"
        const val SEARCH = "Search stand-in"
        const val MINI_BAR = "Mini bar check"
        const val HINT = "Add a TMDB key for episode lists and air dates"
    }
}

package io.skjaere.usenet.app.collection

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.CollectionViewPrefs
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The collection with a remote only (not in touch mode), over a library of its own (20 shows, so U
 * to Z and '#' have none): the walk from the search button through Select, + Add, the switch, the
 * chips and the sort to the rows; Jump to letter; Back from deep in the list; select mode, Remove,
 * Undo, Back with nothing selected; and a row opened and come back to. Each step asserts what has
 * the focus.
 */
@RunWith(AndroidJUnit4::class)
class CollectionDpadDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel
    /** The Show page the stub navigation has open, if any. */
    private var page by mutableStateOf<Int?>(null)
    /** Another tab, the stub navigation's: the collection left without a row opened. */
    private var elsewhere by mutableStateOf(false)
    private var adds = 0
    /** A "+ Collection" jump the screen is given, until it says it is done. */
    private var jump by mutableStateOf<CollectionJump?>(null)
    private var jumpsDone = 0

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        harness?.close()
    }

    /** The screen, and a stand-in for the Show page that Back leaves, as the app's navigation keeps the list's state; [jumpTo] as AppNav hands it on. */
    private fun show(jumpTo: CollectionJump? = null, prefs: CollectionViewPrefs = CollectionViewPrefs()): CollectionHarness {
        jump = jumpTo
        val h = CollectionHarness(instrumentation.targetContext, shows = 20, movies = 5, prefs = prefs).also { harness = it; it.seed() }
        compose.setContent {
            AppTheme {
                vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                val holder = rememberSaveableStateHolder()
                if (elsewhere) Text("Another tab")
                else when (val opened = page) {
                    null -> holder.SaveableStateProvider("collection") {
                        CollectionScreen(
                            onShow = { page = it }, onMovie = {}, onAdd = { adds++ }, onMetadata = {}, vm = vm,
                            jump = jump, onJumpDone = { jump = null; jumpsDone++ }, backToSearch = jumpTo != null,
                        )
                    }
                    else -> {
                        BackHandler { page = null }
                        Text("Show page $opened")
                    }
                }
            }
        }
        compose.waitUntil(10_000) { vm.ui.value?.view?.rows?.isNotEmpty() == true }
        if (jumpTo == null) compose.waitUntil(5_000) { focused() == "Search 20 shows" }
        return h
    }

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The focused node of the window on top: a menu or a dialog has its own, besides the screen's. */
    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    /** What the focused node says: its description, else its words. */
    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
    } ?: "(nothing)"

    private fun focusedRole(): Role? = focusedNode()?.config?.getOrNull(SemanticsProperties.Role)

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    /** Presses [key] until [what] has the focus, at most [times]; fails with where the focus went. */
    private fun pressUntil(key: Int, what: String, times: Int = 8, done: () -> Boolean) {
        val seen = mutableListOf(focused())
        repeat(times) { if (!done()) seen += press(key) }
        assertTrue("never reached $what; focus went ${seen.joinToString(" → ")}", done())
    }

    private fun assertFocused(expected: String) {
        try {
            compose.waitUntil(5_000) { focused() == expected }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the focus is on ${focused()}, not $expected", e)
        }
    }

    @Test
    fun theWalkGoesFromSearchThroughTheTopBarSwitchChipsAndSortToTheRows() {
        show()
        assertEquals("Select", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Add to the collection", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the switch", times = 2) { focusedRole() == Role.Tab }
        if (focused() != "Shows") assertEquals("Shows", press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Movies", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Shows", press(KeyEvent.KEYCODE_DPAD_LEFT))
        // Moving onto a side doesn't pick it.
        assertEquals("Shows", vm.ui.value!!.side.name)
        // Down lands on the chip nearest below; Left runs back to All.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(focused(), Role.RadioButton, focusedRole())
        pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, "All", times = 6) { focused().startsWith("All, 20 shows") }
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT), focused().startsWith("Watching, "))
        // The header line: Jump to letter, then the sort, at its end.
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the header line", times = 2) { focused() == "Jump to letter" || focused() == "Sort: Recently watched" }
        pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "the sort", times = 2) { focused() == "Sort: Recently watched" }
        assertEquals("Jump to letter", press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Sort: Recently watched", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("A–Z")) }
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "A–Z in the menu", times = 6) { focused() == "A–Z" }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.sortName == "AZ" }
        assertFocused("Sort: A–Z")
        val first = rows().first()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the first row", times = 2) { focused() == first.a11y }
        assertEquals(rows()[1].a11y, press(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun jumpToLetterSkipsEmptyLettersAndLandsOnTheLettersFirstRowInAToZ() {
        show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the sort") { focused() == "Sort: Recently watched" }
        assertEquals("Jump to letter", press(KeyEvent.KEYCODE_DPAD_LEFT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The letter of the first row on screen: Recently watched leads with Avrel 0000.
        assertFocused("A, 1 show")
        // Seven to a line: # A … F, G … M, N … T, U … Z.
        assertEquals("H, 1 show", press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("O, 1 show", press(KeyEvent.KEYCODE_DPAD_DOWN))
        // U to Z have no titles: Down stays on O.
        assertEquals("O, 1 show", press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("N, 1 show", press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("G, 1 show", press(KeyEvent.KEYCODE_DPAD_UP))
        // Nor has '#', above G: Up passes it by.
        val up = press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(up, up.matches(Regex("[A-F], 1 show")))
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "O", times = 3) { focused() == "O, 1 show" }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.sortName == "AZ" }
        compose.waitUntil(5_000) { focused().startsWith("Ostral 0014.") }
        assertEquals("A–Z", vm.ui.value!!.sortLabel)
    }

    @Test
    fun backFromDeepInTheListGoesToSearchWhichOkOpensForTyping() {
        show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the sixth row", times = 12) { focused() == rows()[5].a11y }
        assertEquals("Search 20 shows", press(KeyEvent.KEYCODE_BACK))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasSetTextAction() and isFocused()) }
        compose.onNode(hasSetTextAction()).performTextInput("ostral")
        compose.waitUntil(5_000) { rows().map { it.text.name } == listOf("Ostral 0014") }
        // Back leaves the field for the top bar; the search stays as typed. (A keyboard on screen takes the first Back itself, to close.)
        press(KeyEvent.KEYCODE_BACK)
        if (exists(hasSetTextAction() and isFocused())) press(KeyEvent.KEYCODE_BACK)
        assertFocused("Select")
        assertEquals("ostral", vm.ui.value!!.text)
        assertTrue(vm.ui.value!!.searchOpen)
    }

    @Test
    fun comingBackToTheTabWithTheSearchOpenPutsTheFocusOnSelectNotTheField() {
        show()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasSetTextAction() and isFocused()) }
        compose.onNode(hasSetTextAction()).performTextInput("ostral")
        compose.waitUntil(5_000) { rows().map { it.text.name } == listOf("Ostral 0014") }
        elsewhere = true
        compose.waitUntil(5_000) { exists(hasText("Another tab")) }
        elsewhere = false
        compose.waitUntil(5_000) { exists(hasSetTextAction()) }
        // The search button isn't there while the search is open: the bar's next, and no keyboard.
        assertFocused("Select")
        assertFalse(exists(hasSetTextAction() and isFocused()))
        assertEquals("ostral", vm.ui.value!!.text)
    }

    @Test
    fun selectModeOkTogglesAndRemoveHandsTheFocusToUndoWhichRestores() {
        val h = show()
        val before = rows()
        assertEquals("Select", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // On the first row on screen, nothing selected yet.
        assertFocused(before[0].a11y)
        assertEquals(Role.Checkbox, focusedRole())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, vm.ui.value!!.selection?.count)
        assertEquals(before[1].a11y, press(KeyEvent.KEYCODE_DPAD_DOWN))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, vm.ui.value!!.selection?.count)
        // Back from the list goes to the actions.
        assertEquals("Mark watched", press(KeyEvent.KEYCODE_BACK))
        assertEquals("Mark unwatched", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Remove 2 shows from the collection", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Removed 2 shows")) }
        assertFocused("Undo")
        assertEquals(18, runBlocking { h.library.counts.first() }.shows)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { rows().map { it.key } == before.map { it.key } }
        assertEquals(20, runBlocking { h.library.counts.first() }.shows)
        // The focus goes to the row after those removed.
        assertFocused(before[2].a11y)
    }

    @Test
    fun backAfterARemoveLeavesTheFocusOnTheNeighbourRow() {
        show()
        val before = rows()
        assertEquals("Select", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocused(before[0].a11y)
        assertEquals(before[1].a11y, press(KeyEvent.KEYCODE_DPAD_DOWN))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Mark watched", press(KeyEvent.KEYCODE_BACK))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Remove 1 show from the collection", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocused("Undo")
        press(KeyEvent.KEYCODE_BACK)
        assertFocused(before[2].a11y)
        assertTrue(rows().none { it.key == before[1].key })
    }

    @Test
    fun backInSelectModeWithNothingSelectedLeavesIt() {
        show()
        val before = rows()
        assertEquals("Select", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocused(before[0].a11y)
        // Nothing to act on (the actions are off): Back leaves select mode, the row keeps the focus; the next goes up as ever.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        assertFocused(before[0].a11y)
        assertEquals("Search 20 shows", press(KeyEvent.KEYCODE_BACK))
        // The same with the one row selected deselected again.
        assertEquals("Select", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocused(before[0].a11y)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, vm.ui.value!!.selection?.count)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, vm.ui.value!!.selection?.count)
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.ui.value?.selection == null }
        assertFocused(before[0].a11y)
    }

    @Test
    fun aRowOpenedAndComeBackToHasTheFocusAgain() {
        show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the fourth row", times = 10) { focused() == rows()[3].a11y }
        val row = rows()[3]
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Show page ${row.key.tmdbId}")) }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { focused() == row.a11y }
        assertEquals(0, adds)
    }

    /** ⌕ opens the search, [text] is typed, then Back off the field: the focus on Select (⌕ isn't there while the search is open). */
    private fun searchFor(text: String) {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fieldFocused }
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitUntil(5_000) { vm.ui.value?.text == text }
        press(KeyEvent.KEYCODE_BACK)
        if (fieldFocused) press(KeyEvent.KEYCODE_BACK)
        assertFocused("Select")
    }

    /** With the search open, Undo after a mark hands the focus to the first row marked (they stay), not to the search field and its keyboard. */
    @Test
    fun withTheSearchOpenUndoAfterAMarkHandsTheFocusToTheFirstRowMarked() {
        show()
        searchFor("001")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val shown = rows()
        assertTrue(shown.joinToString { it.text.name }, shown.size >= 2)
        assertFocused(shown[0].a11y)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(shown[1].a11y, press(KeyEvent.KEYCODE_DPAD_DOWN))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, vm.ui.value!!.selection?.count)
        assertEquals("Mark watched", press(KeyEvent.KEYCODE_BACK))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.undo?.message?.startsWith("Marked 2 shows watched") == true }
        assertFocused("Undo")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { focused().startsWith(shown[0].text.name) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the focus is on ${focused()}, not ${shown[0].text.name}", e)
        }
        assertFalse("the search field took the focus (and the keyboard)", fieldFocused)
        assertEquals("001", vm.ui.value!!.text)
    }

    /** With the search open and every match removed, Back from Undo hands the focus to Select (⌕ isn't there), not to the field. */
    @Test
    fun withTheSearchOpenBackFromUndoWithNoRowLeftGoesToSelect() {
        show()
        searchFor("ostral")
        compose.waitUntil(5_000) { rows().map { it.text.name } == listOf("Ostral 0014") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocused(rows()[0].a11y)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Mark watched", press(KeyEvent.KEYCODE_BACK))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Remove 1 show from the collection", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Removed “Ostral 0014”")) }
        assertFocused("Undo")
        press(KeyEvent.KEYCODE_BACK)
        assertFocused("Select")
        assertFalse("the search field took the focus (and the keyboard)", fieldFocused)
    }

    /** Come back to once, the row isn't focused again on a later visit to the tab: the list stays where it was left, the focus on ⌕. */
    @Test
    fun aRowComeBackToIsNotFocusedAgainOnTheNextVisitToTheTab() {
        show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the fourth row", times = 10) { focused() == rows()[3].a11y }
        val row = rows()[3]
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Show page ${row.key.tmdbId}")) }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { focused() == row.a11y }
        // On down the list, then another tab and back.
        val further = rows()[15]
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the sixteenth row", times = 14) { focused() == further.a11y }
        elsewhere = true
        compose.waitUntil(5_000) { exists(hasText("Another tab")) }
        elsewhere = false
        assertFocused("Search 20 shows")
        compose.onNode(hasContentDescription(further.a11y)).assertIsDisplayed()
    }

    private val fieldFocused get() = exists(hasSetTextAction() and isFocused())

    /** OK on the other side picks it, and the focus stays on the switch: not thrown to the top bar, nor to the search field and its keyboard. */
    @Test
    fun okOnTheSwitchPicksTheSideAndTheFocusStaysThere() {
        show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the switch", times = 2) { focusedRole() == Role.Tab }
        if (focused() != "Shows") assertEquals("Shows", press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Movies", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.side == Side.Movies }
        assertFocused("Movies")
        // With the search open (its field the first control at the top).
        assertEquals("Search 5 movies", press(KeyEvent.KEYCODE_BACK))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fieldFocused }
        compose.onNode(hasSetTextAction()).performTextInput("0")
        compose.waitUntil(5_000) { vm.ui.value?.text == "0" }
        press(KeyEvent.KEYCODE_BACK)
        if (fieldFocused) press(KeyEvent.KEYCODE_BACK)
        assertFocused("Select")
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the switch", times = 2) { focusedRole() == Role.Tab }
        if (focused() != "Movies") assertEquals("Movies", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Shows", press(KeyEvent.KEYCODE_DPAD_LEFT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.side == Side.Shows }
        assertFocused("Shows")
        assertFalse("the search field took the focus (and the keyboard)", fieldFocused)
    }

    /** The other side left scrolled down (a jump took the list there): it opens at its top, so the switch, and the focus on it, stay. */
    @Test
    fun okOnTheSwitchKeepsTheFocusWhenTheOtherSideWasLeftScrolledDown() {
        val h = show()
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "the thirteenth row", times = 20) { focused() == rows()[12].a11y }
        jump = CollectionJump(TitleKey.movie(h.fixture.movieId(4)).value, h.fixture.movieName(4))
        compose.waitUntil(10_000) { jumpsDone == 1 }
        compose.waitUntil(5_000) { focused().startsWith(h.fixture.movieName(4)) }
        pressUntil(KeyEvent.KEYCODE_DPAD_UP, "the switch", times = 12) { focusedRole() == Role.Tab }
        if (focused() != "Shows") assertEquals("Shows", press(KeyEvent.KEYCODE_DPAD_LEFT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.side == Side.Shows }
        assertFocused("Shows")
    }

    /** Finished's Select all stays in the header line, as Select none once all are: the focus on it isn't thrown to select mode's ✕. */
    @Test
    fun selectAllInFinishedKeepsTheFocusInTheHeaderLine() {
        show(prefs = CollectionViewPrefs(showFilter = "Finished"))
        val finished = rows()
        assertEquals(listOf("Mirel 0012"), finished.map { it.text.name })
        // Where the header line wraps (a phone) it is under the sort, at a TV's width after it: focused here either way.
        compose.onNode(hasText("Select all") and hasClickAction()).requestFocus()
        assertFocused("Select all")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection?.allSelected == true }
        assertFocused("Select none")
        // The header line's, right above the rows (select mode's own is in the bar above the list).
        assertEquals(finished.first().a11y, press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Select none", press(KeyEvent.KEYCODE_DPAD_UP))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.selection?.count == 0 }
        assertFocused("Select all")
    }

    /** "Show 1 in All" shows the match it counted, the search kept, and hands the focus on to it (not to the field and its keyboard). */
    @Test
    fun showTheMatchesInAllKeepsTheSearchAndHandsTheFocusToTheFirst() {
        show(prefs = CollectionViewPrefs(showFilter = "Finished"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fieldFocused }
        compose.onNode(hasSetTextAction()).performTextInput("avrel")
        compose.waitUntil(5_000) { vm.ui.value?.let { it.text == "avrel" && it.view.empty != null } == true }
        press(KeyEvent.KEYCODE_BACK)
        if (fieldFocused) press(KeyEvent.KEYCODE_BACK)
        assertFocused("Select")
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Show 1 in All", times = 6) { focused() == "Show 1 in All" }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.filterName == "All" }
        assertEquals("avrel", vm.ui.value!!.text)
        assertEquals(listOf("Avrel 0000"), rows().map { it.text.name })
        assertFocused(rows().first().a11y)
        assertFalse("the search field took the focus (and the keyboard)", fieldFocused)
    }

    /** A title a jump revealed keeps the focus, and stays on screen, as its facts move it up the list (Next to air). */
    @Test
    fun aRevealedTitleKeepsTheFocusAndStaysInViewAsItsFactsMoveIt() {
        val h = show(prefs = CollectionViewPrefs(showSort = "NextToAir"))
        val added = h.addShow(500, "Aldren 0500")
        jump = CollectionJump(added.value, "Aldren 0500")
        compose.waitUntil(10_000) { jumpsDone == 1 }
        compose.waitUntil(5_000) { focused().startsWith("Aldren 0500") }
        val before = rows().indexOfFirst { it.key == added }
        h.answer(added, LocalDate.now(h.clock).plusDays(1))
        compose.waitUntil(5_000) { rows().indexOfFirst { it.key == added } < before - 5 }
        val row = hasContentDescription("Aldren 0500", substring = true) and isFocused()
        compose.waitUntil(2_000) { exists(row) }
        compose.onNode(row).assertIsDisplayed()
        assertTrue(focused(), focused().startsWith("Aldren 0500"))
    }

    /** A jump to a title that doesn't show in time (three seconds) leaves the focus where the tab's starts, not nowhere. */
    @Test
    fun aJumpThatNeverShowsLeavesTheFocusOnTheSearchButton() {
        show(jumpTo = CollectionJump("show:999999999", "Not in this library"))
        compose.waitUntil(10_000) { jumpsDone == 1 }
        assertFocused("Search 20 shows")
    }
}

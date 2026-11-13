package io.skjaere.usenet.app.collection

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.CollectionViewPrefs
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The collection on a touch screen, over a library of its own (20 shows, more than a screen): a title
 * revealed at the far end of the list is told to TalkBack; the search field takes the focus (and the
 * keyboard) when the search is opened, not again when select mode ends or a page is come back from.
 * Empty and without a TMDB key, it points to the key rather than to Search.
 */
@RunWith(AndroidJUnit4::class)
class CollectionTouchDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel
    /** The Show page the stub navigation has open, if any. */
    private var page by mutableStateOf<Int?>(null)
    /** A "+ Collection" jump the screen is given, until it says it is done. */
    private var jump by mutableStateOf<CollectionJump?>(null)

    @Before
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @After
    fun restore() {
        harness?.close()
    }

    /** The screen, and a stand-in for the Show page that Back leaves, as the app's navigation keeps the list's state. */
    private fun show(prefs: CollectionViewPrefs = CollectionViewPrefs()): CollectionHarness {
        val h = CollectionHarness(instrumentation.targetContext, shows = 20, movies = 5, prefs = prefs).also { harness = it; it.seed() }
        compose.setContent {
            AppTheme {
                vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                val holder = rememberSaveableStateHolder()
                when (val opened = page) {
                    null -> holder.SaveableStateProvider("collection") {
                        CollectionScreen(onShow = { page = it }, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm, jump = jump, onJumpDone = { jump = null })
                    }
                    else -> {
                        BackHandler { page = null }
                        Text("Show page $opened")
                    }
                }
            }
        }
        compose.waitUntil(10_000) { vm.ui.value?.view?.rows?.isNotEmpty() == true }
        return h
    }

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val fieldFocused get() = exists(hasSetTextAction() and isFocused())

    /** ⌕ opens the search with the field focused; [text] typed, then Back off the field (the keyboard closes, the search stays). */
    private fun searchFor(text: String) {
        compose.onNodeWithContentDescription("Search 20 shows").performClick()
        compose.waitUntil(5_000) { fieldFocused }
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitUntil(5_000) { vm.ui.value?.text == text }
        back()
        compose.waitUntil(5_000) { !fieldFocused }
    }

    @Test
    fun aTitleRevealedAtTheFarEndOfTheListIsToldToTalkBack() {
        show()
        val last = rows().last()
        val header = vm.ui.value!!.view.header
        jump = CollectionJump(last.key.value, last.text.name)
        val row = hasContentDescription(last.a11y) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Just added")
        compose.waitUntil(10_000) { exists(row) }
        compose.onNode(row).assertIsDisplayed()
        // The header line is scrolled away; TalkBack is told all the same.
        assertFalse("the header line is still on screen", exists(hasText(header)))
        val said = hasContentDescription("Added ${last.text.name} to your collection") and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        assertTrue("TalkBack wasn't told", exists(said))
    }

    /**
     * A show just added sorts by what TMDB says of it (Next to air), and that comes in after it is
     * revealed: it moves up the list, and the list goes with it, still highlighted.
     */
    @Test
    fun aRevealedTitleStaysInViewWhenItsFactsMoveIt() {
        val h = show(CollectionViewPrefs(showSort = "NextToAir"))
        val added = h.addShow(500, "Aldren 0500")
        jump = CollectionJump(added.value, "Aldren 0500")
        val row = hasContentDescription("Aldren 0500", substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Just added")
        compose.waitUntil(10_000) { exists(row) }
        compose.onNode(row).assertIsDisplayed()
        // Without facts it sorts with the shows that have no date (the end); TMDB then says an episode airs tomorrow.
        val before = rows().indexOfFirst { it.key == added }
        h.answer(added, LocalDate.now(h.clock).plusDays(1))
        compose.waitUntil(5_000) { rows().indexOfFirst { it.key == added } < before - 5 }
        compose.waitUntil(2_000) { exists(row) }
        compose.onNode(row).assertIsDisplayed()
    }

    @Test
    fun theSearchFieldDoesNotTakeTheFocusAgainWhenSelectModeEnds() {
        show()
        searchFor("001")
        val row = rows().first()
        compose.onNode(hasContentDescription(row.a11y)).performTouchInput { longClick() }
        compose.waitUntil(5_000) { vm.ui.value?.selection != null }
        compose.onNodeWithContentDescription("Remove 1 show from the collection").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.let { it.selection == null && it.undo != null } == true }
        compose.waitForIdle()
        assertTrue(vm.ui.value!!.searchOpen)
        assertFalse("the field took the focus (and the keyboard) again", fieldFocused)
    }

    @Test
    fun theSearchFieldDoesNotTakeTheFocusAgainOnComingBackFromAPage() {
        show()
        searchFor("001")
        val row = rows().first()
        compose.onNode(hasContentDescription(row.a11y)).performClick()
        compose.waitUntil(5_000) { exists(hasText("Show page ${row.key.tmdbId}")) }
        back()
        compose.waitUntil(5_000) { exists(hasContentDescription(row.a11y)) }
        compose.waitForIdle()
        assertEquals("001", vm.ui.value!!.text)
        assertFalse("the field took the focus (and the keyboard) again", fieldFocused)
    }

    /** Without a TMDB key Search has no "+ Collection": the empty collection says the key is needed and goes to Settings › Metadata. */
    @Test
    fun withoutAKeyTheEmptyCollectionGoesToTheKeyNotToSearch() {
        val h = CollectionHarness(instrumentation.targetContext, shows = 0, movies = 0, hasKey = false).also { harness = it }
        var metadata = 0
        var adds = 0
        compose.setContent {
            AppTheme {
                vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                CollectionScreen(onShow = {}, onMovie = {}, onAdd = { adds++ }, onMetadata = { metadata++ }, vm = vm)
            }
        }
        compose.waitUntil(10_000) { exists(hasText("No shows yet")) }
        compose.onNodeWithText("Adding a show needs your own TMDB key: Settings › Metadata.", substring = true).assertIsDisplayed()
        assertFalse("it points to Search", exists(hasText("Find a show")))
        compose.onNodeWithText("Add a TMDB key").performClick()
        compose.waitForIdle()
        assertEquals(1 to 0, metadata to adds)
    }
}

package io.skjaere.usenet.app.collection

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The collection at twice the default font size, over a library of its own (60 shows, 30 movies):
 * the top bar (Select and + Add as icons), the switch, the chips, the header line and a show's and a
 * movie's row whole, rows grown rather than cut; every control a 48 dp target; the select-mode
 * actions wrapped onto a second line, and on a narrow phone the count whole; every letter of the
 * letter grid reachable.
 */
@RunWith(AndroidJUnit4::class)
class CollectionFontScaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel

    @After
    fun close() {
        harness?.close()
    }

    /** The screen at [fontScale], [width] wide when given (a phone narrower than the emulator's). */
    private fun show(fontScale: Float = 2f, shows: Int = 60, width: Dp? = null) {
        val h = CollectionHarness(context, shows = shows, movies = 30).also { harness = it; it.seed() }
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                    Box(width?.let { Modifier.width(it) } ?: Modifier) {
                        CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm)
                    }
                }
            }
        }
        compose.waitUntil(10_000) { vm.ui.value?.view?.rows?.isNotEmpty() == true }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.singleOrNull()
    }

    /** [text]'s words all shown: not cut at the bottom, not ellipsized, inside [within] when given, and on the screen sideways. */
    private fun assertWhole(text: String, within: SemanticsNode? = null) {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null }
            ?: throw AssertionError("no text '$text'")
        val layout = node.textLayout()!!
        assertFalse("'$text' is cut: ${layout.lineCount} lines in ${layout.layoutInput.constraints}", layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1))
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = node.boundsInRoot
        assertTrue("'$text' ($bounds) leaves the screen ($screen)", bounds.left >= screen.left && bounds.right <= screen.right)
        within?.boundsInRoot?.let { row -> assertTrue("'$text' ($bounds) is outside its row ($row)", bounds.top >= row.top && bounds.bottom <= row.bottom && bounds.right <= row.right) }
    }

    /** Every control on screen is at least 48 dp each way. */
    private fun assertTargets(where: String) {
        val min = with(compose.density) { 48.dp.toPx() } - 1
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val touch = node.touchBoundsInRoot
            val said = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
            assertTrue("$where: $said is ${touch.width} x ${touch.height} px", touch.width >= min && touch.height >= min)
        }
    }

    private fun rowNode(a11y: String): SemanticsNode = compose.onNode(hasContentDescription(a11y)).fetchSemanticsNode()

    @Test
    fun at200PercentEverythingIsWholeAndEveryControlA48dpTarget() {
        show()
        // The top bar: the title on one line, Select and + Add as icons that say what they are.
        compose.onNodeWithText("Collection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to the collection").assertIsDisplayed()
        assertFalse(compose.onAllNodes(hasText("+ Add"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        listOf("Shows", "Movies", "All 60").forEach { assertWhole(it) }
        assertWhole(vm.ui.value!!.view.header)
        val show = vm.ui.value!!.view.rows.first() as ShowRow
        val showRow = rowNode(show.a11y)
        assertWhole(show.text.name, showRow)
        assertWhole(show.status, showRow)
        assertTargets("Shows")

        compose.onNodeWithText("Movies").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.side?.name == "Movies" }
        val movie = vm.ui.value!!.view.rows.first() as MovieRow
        val movieRow = rowNode(movie.a11y)
        listOf(movie.text.name, movie.meta, movie.status).forEach { assertWhole(it, movieRow) }
        assertTargets("Movies")

        // Select mode's actions wrap: Remove isn't on the first line.
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.selection != null }
        val watched = compose.onNodeWithText("Mark watched").fetchSemanticsNode().boundsInRoot
        val remove = compose.onNode(hasText("Remove")).fetchSemanticsNode().boundsInRoot
        assertTrue("Remove ($remove) on the line of Mark watched ($watched)", remove.top >= watched.bottom)
        listOf("Mark watched", "Mark unwatched", "Remove").forEach { assertWhole(it) }
        assertTargets("Select mode")
    }

    /**
     * Select mode's count, what its bar is for, whole on a 360 dp phone at twice the size: Select all
     * with a three-digit count (or Select none) doesn't squeeze it to "0" over "sele…".
     */
    @Test
    fun at200PercentOnANarrowPhoneTheSelectedCountIsWhole() {
        show(shows = 120, width = 360.dp)
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.selection != null }
        listOf("0 selected", "Select all (120)").forEach { assertWhole(it) }
        compose.onNodeWithText("Select all (120)").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.selection?.count == 120 }
        listOf("120 selected", "Select none").forEach { assertWhole(it) }
        assertTargets("Select mode")
    }

    /** The default size too: the switch's 40 dp segments and the text actions still take a 48 dp touch. */
    @Test
    fun atTheDefaultSizeEveryControlIsA48dpTarget() {
        show(fontScale = 1f)
        assertTargets("Shows")
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.selection != null }
        assertTargets("Select mode")
    }

    @Test
    fun at200PercentEveryLetterOfTheGridCanBeReached() {
        show()
        compose.onNodeWithContentDescription("Sort: Recently watched").performClick()
        compose.onNodeWithText("A–Z").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.sortName == "AZ" }
        // TalkBack's way to the grid: the rail is one button.
        compose.onNodeWithContentDescription("Jump to letter").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Jump to letter"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val grid = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and SemanticsMatcher("in the dialog") { node -> node.children.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("#, ") == true } })
        (listOf('#') + ('A'..'Z')).forEach { letter ->
            val button = hasContentDescription("$letter, ", substring = true)
            grid.performScrollToNode(button)
            compose.onNode(button).assertIsDisplayed()
        }
    }
}

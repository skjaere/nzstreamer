package io.skjaere.usenet.app.collection

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.LoadState
import io.skjaere.usenet.app.collection.model.ShowFilter
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.CollectionViewPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * The collection at the size slice 12 aims for, 1,000 shows and 2,000 movies (ScaleFixture), over a
 * library and a TMDB of its own (CollectionHarness): it opens with rows within budget, cold (nothing
 * read yet) and warm (Home read the rows first), on both sides; search, a chip, the letter rail and
 * the end of the list work; select three, Remove, Undo; titles without facts fill those on screen
 * first. Measurements go to logcat under "CollectionScale" (kept per test by the test run).
 */
@RunWith(AndroidJUnit4::class)
class CollectionScaleDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel

    @After
    fun close() {
        harness?.close()
    }

    private fun harness(prefs: CollectionViewPrefs = CollectionViewPrefs()) =
        CollectionHarness(context, prefs = prefs).also { harness = it; it.seed() }

    private fun show(h: CollectionHarness) {
        compose.setContent {
            AppTheme {
                vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm)
            }
        }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** From setContent to the frame after the screen said it had its rows (ReportDrawnWhen), in ms. */
    private fun timeToFirstRows(h: CollectionHarness): Double {
        val drawn = AtomicLong(0)
        compose.activity.fullyDrawnReporter.addOnReportDrawnListener { drawn.compareAndSet(0, SystemClock.elapsedRealtimeNanos()) }
        val start = SystemClock.elapsedRealtimeNanos()
        show(h)
        compose.waitUntil(10_000) { drawn.get() != 0L }
        return (drawn.get() - start) / 1e6
    }

    private fun opens(side: String, warm: Boolean, budgetMs: Double) {
        val h = harness(CollectionViewPrefs(side = side))
        if (warm) {
            val ms = measure { h.warm() }
            report("$side: the store's rows read in %.0f ms before the tab opened".format(ms))
        }
        val ms = timeToFirstRows(h)
        val first = vm.ui.value!!.view.rows.first()
        compose.onNode(hasContentDescription(first.a11y)).assertIsDisplayed()
        report("$side ${if (warm) "warm" else "cold"}: first rows drawn in %.0f ms (budget %.0f ms), ${vm.ui.value!!.view.total} titles".format(ms, budgetMs))
        assertTrue("$side ${if (warm) "warm" else "cold"} took $ms ms", ms <= budgetMs)
    }

    private fun measure(block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }

    @Test
    fun showsOpenWithRowsWithinBudgetCold() = opens("Shows", warm = false, budgetMs = COLD_MS)

    @Test
    fun showsOpenWithRowsWithinBudgetWarm() = opens("Shows", warm = true, budgetMs = WARM_MS)

    @Test
    fun moviesOpenWithRowsWithinBudgetCold() = opens("Movies", warm = false, budgetMs = COLD_MS)

    @Test
    fun moviesOpenWithRowsWithinBudgetWarm() = opens("Movies", warm = true, budgetMs = WARM_MS)

    private fun opened(h: CollectionHarness) {
        show(h)
        compose.waitUntil(10_000) { vm.ui.value?.view?.rows?.isNotEmpty() == true }
    }

    /** Each letter typed narrows the list at once: "avrel 00" leaves the four Avrel shows numbered 00. */
    @Test
    fun searchNarrowsAsYouType() {
        val h = harness()
        opened(h)
        compose.onNodeWithContentDescription("Search 1000 shows").performClick()
        compose.waitUntil(5_000) { exists(hasSetTextAction()) }
        listOf("a" to 34, "av" to 34, "avr" to 34, "avrel 00" to 4, "avrel 009" to 1).forEach { (typed, count) ->
            val header = "$count of 1000 shows ${if (count == 1) "matches" else "match"} “$typed”"
            val took = measure {
                compose.onNode(hasSetTextAction()).performTextReplacement(typed)
                try {
                    compose.waitUntil(5_000) { vm.ui.value?.text == typed && exists(hasText(header, substring = true)) }
                } catch (e: ComposeTimeoutException) {
                    throw AssertionError("\"$typed\": the list has \"${vm.ui.value?.text}\", ${vm.ui.value?.view?.rows?.size} rows, \"${vm.ui.value?.view?.header}\"", e)
                }
            }
            val ui = vm.ui.value!!
            val rows = ui.view.rows
            assertEquals("\"$typed\" (${ui.text}): ${rows.take(6).map { it.text.name to it.text.searchKey }}, ${ui.view.header}", count, rows.size)
            assertTrue("$typed: ${rows.map { it.text.name }}", rows.all { it.text.name.startsWith("Avrel") })
            compose.onNode(hasContentDescription(rows.first().a11y)).assertIsDisplayed()
            report("search \"$typed\": $count rows shown in %.0f ms".format(took))
        }
        assertEquals("Avrel 0090", vm.ui.value!!.view.rows.single().text.name)
    }

    /** Each chip says how many it holds, and picking it leaves exactly those: the header still counts every show. */
    @Test
    fun aFilterNarrowsAndCountsMatchChips() {
        val h = harness()
        opened(h)
        val header = vm.ui.value!!.view.header
        val chips = compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        ShowFilter.entries.forEach { filter ->
            val chip = hasContentDescription("${filter.label}, ", substring = true)
            chips.performScrollToNode(chip)
            val said = compose.onNode(chip).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            val count = said.substringAfter(", ").substringBefore(" ").toInt()
            compose.onNode(chip).performClick()
            compose.waitUntil(5_000) { vm.ui.value?.filterName == filter.name }
            val rows = vm.ui.value!!.view.rows.map { it as ShowRow }
            assertEquals(said, count, rows.size)
            assertTrue("${filter.label}: $said", count > 0)
            assertTrue("${filter.label} holds others", rows.all { io.skjaere.usenet.app.collection.model.CollectionView.inFilter(it, filter) })
            assertEquals(header, vm.ui.value!!.view.header)
            compose.onNode(hasContentDescription(rows.first().a11y)).assertIsDisplayed()
            report("${filter.label}: $count shows")
        }
        assertEquals(1_000, vm.ui.value!!.view.total)
    }

    /** In A–Z (picked from the sort menu) the rail jumps to a letter's first title, by a tap and by a drag. */
    @Test
    fun azRailJumpsToALetter() {
        val h = harness()
        opened(h)
        compose.onNodeWithContentDescription("Sort: Recently watched").performClick()
        compose.onNodeWithText("A–Z").performClick()
        compose.waitUntil(5_000) { vm.ui.value?.sortName == "AZ" && exists(hasContentDescription("Jump to letter")) }
        val rail = compose.onNodeWithContentDescription("Jump to letter")
        fun at(letter: Char, height: Float) = (LETTERS.indexOf(letter) + 0.5f) / LETTERS.size * height
        rail.performTouchInput { click(Offset(centerX, at('Q', height.toFloat()))) }
        compose.waitUntil(5_000) { exists(hasContentDescription("Quessa 0016.", substring = true)) }
        assertTopRow("Quessa 0016.")
        // Dragged from Q down to T: Tavel's first is at the top.
        rail.performTouchInput {
            down(Offset(centerX, at('Q', height.toFloat())))
            moveTo(Offset(centerX, at('S', height.toFloat())))
            moveTo(Offset(centerX, at('T', height.toFloat())))
            up()
        }
        compose.waitUntil(5_000) { exists(hasContentDescription("Tavel 0019.", substring = true)) }
        assertTopRow("Tavel 0019.")
    }

    /** [name]'s row is the first title row on screen (the switch, chips and header scrolled away). */
    private fun assertTopRow(name: String) {
        compose.waitForIdle()
        val rows = compose.onAllNodes(SemanticsMatcher("a title row") { node -> vm.ui.value!!.view.rows.any { it.a11y == node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() } })
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        val top = rows.first().config[SemanticsProperties.ContentDescription].single()
        assertTrue("the top row says $top", top.startsWith(name))
    }

    /**
     * Thirty flings through the shows, then to the end: the last title and the footnote show. (The
     * frames are measured by CollectionFrameStatsDeviceTest: here the test's clock drives them.)
     */
    @Test
    fun thirtyFlingsAndAScrollReachTheEnd() {
        val h = harness()
        opened(h)
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        repeat(FLINGS) {
            list.performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 80) }
            compose.waitForIdle()
        }
        // Flung away from the top.
        compose.onNode(hasContentDescription(vm.ui.value!!.view.rows.first().a11y)).assertDoesNotExist()
        list.performScrollToKey("footnote")
        compose.onNodeWithText("Episode lists and air dates from TMDB.").assertIsDisplayed()
        compose.onNode(hasContentDescription(vm.ui.value!!.view.rows.last().a11y)).assertIsDisplayed()
    }

    /** A long press starts select mode; two more taps, Remove: gone at once, Undo brings all three back. */
    @Test
    fun selectThreeRemoveUndoRestoresThem() {
        val h = harness()
        opened(h)
        val three = vm.ui.value!!.view.rows.take(3)
        compose.onNode(hasContentDescription(three[0].a11y)).performTouchInput { longClick() }
        compose.waitUntil(5_000) { vm.ui.value?.selection != null }
        three.drop(1).forEach { compose.onNode(hasContentDescription(it.a11y)).performClick() }
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove 3 shows from the collection").performClick()
        compose.waitUntil(5_000) { exists(hasText("Removed 3 shows")) }
        assertTrue(vm.ui.value!!.view.rows.none { row -> three.any { it.key == row.key } })
        assertEquals(997, runBlocking { h.library.counts.first() }.shows)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { vm.ui.value!!.view.rows.take(3).map { it.key } == three.map { it.key } }
        assertEquals(1_000, runBlocking { h.library.counts.first() }.shows)
        three.forEach { compose.onNode(hasContentDescription(it.a11y)).assertIsDisplayed() }
    }

    /**
     * Titles without facts are asked for those on screen first: in A–Z the '#' titles lead, two of
     * them without facts; the refresher, started once the screen has said what it shows, asks for
     * those before the rest (newest first), and all 50 fill in.
     */
    @Test
    fun missingSummariesFillVisibleFirst() {
        val h = harness(CollectionViewPrefs(showSort = "AZ"))
        opened(h)
        // The screen hands over what it shows once scrolling settles (300 ms).
        compose.waitUntil(5_000) { h.prioritized.lastOrNull().orEmpty().size >= 5 }
        val visible = h.prioritized.last()
        val missing = visible.filter { h.fixture.factless(it) }
        assertTrue("on screen: $visible", missing.isNotEmpty())
        val all = (0 until h.fixture.shows).map { TitleKey.show(h.fixture.showId(it)) }.filter(h.fixture::factless) +
            (0 until h.fixture.movies).map { TitleKey.movie(h.fixture.movieId(it)) }.filter(h.fixture::factless)
        assertTrue("checking ${all.size}", vm.ui.value!!.view.header.endsWith(" · checking ${all.count { it.isShow }}"))
        val start = SystemClock.elapsedRealtimeNanos()
        h.startRefresher()
        compose.waitUntil(30_000) {
            h.source.calls.size >= all.size && vm.ui.value!!.view.pending == 0 && h.store.movies.value.orEmpty().none { it.load == LoadState.Pending }
        }
        val filled = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        // Picked first; four fetches start together, so the order they record among the first few is the scheduler's.
        val first = h.source.calls.take(missing.size + WORKERS - 1)
        assertTrue("asked first: $first, on screen: $missing", first.containsAll(missing))
        assertEquals(all.toSet(), h.source.calls.toSet())
        assertEquals(all.size, h.source.calls.size)
        assertTrue(vm.ui.value!!.view.rows.filter { it.key in missing }.all { it.load == LoadState.Ready })
        report("${all.size} titles without facts filled in %.0f ms; on screen, asked first: $missing; first calls $first".format(filled))
    }

    private companion object {
        const val TAG = "CollectionScale"
        const val COLD_MS = 800.0
        const val WARM_MS = 300.0
        const val FLINGS = 30
        /** The refresher's. */
        const val WORKERS = 4
        val LETTERS = listOf('#') + ('A'..'Z')
    }
}

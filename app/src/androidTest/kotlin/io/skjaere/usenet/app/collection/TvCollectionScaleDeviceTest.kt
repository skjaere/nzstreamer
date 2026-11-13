package io.skjaere.usenet.app.collection

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.GridPaging
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.tv.inTvCollection
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.tv.tvScreen
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.CollectionViewPrefs
import io.skjaere.usenet.metadata.TmdbImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.atomic.AtomicLong

/**
 * The TV's Collection grid at the size slice 12 aims for, 1,000 shows and 2,000 movies (ScaleFixture), each with a
 * poster from a test loader of its own ([ScaleImages]: one made-up JPEG after a set latency, nothing reaching the
 * network), over a library and a TMDB of its own, in the TV shell on the TV emulator: time to first rows (the same
 * budgets as the phone's list), cold and warm (Home having read the rows), and to first posters fetched, on both sides;
 * the posters again from the images' memory cache as the tab opens a second time; the paging keys from the top to the
 * end and back, a page at a time in Recently watched and a letter at a time in A–Z, with the focus on each page's poster;
 * a held Down, after which the focus is on a poster in view; and a walk down every line of both sides, after which the
 * images' memory cache is measured, and the lines walked back up to are served from it. TV-4's L21 (#219: posters
 * never measured). Every number goes to logcat under "CollectionScale" (kept per test by the test run). Phones skip it:
 * a phone's density isn't a TV's. In name order: the first case run pays for the test process's first composition of
 * the grid (some 370 ms more on tv36), and that is the held Down, which has no time budget, not an open measured
 * against one.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TvCollectionScaleDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val remote = Remote(compose)
    private var harness: CollectionHarness? = null
    private var images: ScaleImages? = null
    private lateinit var vm: CollectionViewModel

    /** Whether the tab's screen is composed: false, it is left (its ViewModel kept, as a tab's is), true again, opened again. */
    private var open by mutableStateOf(true)

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun close() {
        instrumentation.setInTouchMode(true)
        harness?.close()
        images?.restore()
    }

    private fun report(line: String) {
        Log.i(TAG, "TV: $line")
    }

    private fun harness(prefs: CollectionViewPrefs = CollectionViewPrefs()) =
        CollectionHarness(context, prefs = prefs, posters = true).also { harness = it; it.seed() }

    private fun images() = ScaleImages(context).also { images = it; it.install() }

    /** The Collection tab in the TV shell, over [h]. */
    private fun show(h: CollectionHarness) {
        val playback = context.container.playback
        compose.setTvContent {
            val nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = CollectionRoute) {
                    composable<CollectionRoute> {
                        vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
                        if (open) CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm)
                    }
                }
            }
        }
    }

    private fun opened(h: CollectionHarness) {
        show(h)
        compose.waitUntil(10_000) { runCatching { vm.ui.value?.view?.rows?.isNotEmpty() == true }.getOrDefault(false) }
    }

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    private fun poster(row: Int) = rows()[row].a11y

    private fun url(row: TitleRow) = TmdbImages.poster(row.posterPath, POSTER_PX)!!

    /** The titles whose posters show on the screen now. */
    private fun onScreen(): List<TitleRow> {
        val screen = compose.tvScreen()
        val byWords = vm.ui.value?.view?.rows.orEmpty().associateBy { it.a11y }
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and inTvCollection).fetchSemanticsNodes().mapNotNull { node ->
            val b = node.boundsInRoot
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let(byWords::get)
                ?.takeIf { b.height > 0 && b.bottom > screen.top && b.top < screen.bottom }
        }
    }

    /** From [since] (elapsedRealtimeNanos) until every poster on the screen has its picture: each one's time, in ms, sorted. */
    private fun postersShown(since: Long): List<Double> {
        val i = images!!
        compose.waitUntil(10_000) { onScreen().isNotEmpty() }
        var shown = onScreen()
        compose.waitUntil(10_000) {
            shown = onScreen()
            shown.all { i.shown.containsKey(url(it)) }
        }
        return shown.map { (i.shown.getValue(url(it)) - since).coerceAtLeast(0) / 1e6 }.sorted()
    }

    private fun List<Double>.p(q: Double) = this[((size - 1) * q).toInt()]

    private fun opens(side: String, warm: Boolean, budgetMs: Double) {
        val h = harness(CollectionViewPrefs(side = side))
        val i = images()
        if (warm) h.warm()
        val drawn = AtomicLong(0)
        compose.activity.fullyDrawnReporter.addOnReportDrawnListener { drawn.compareAndSet(0, SystemClock.elapsedRealtimeNanos()) }
        val start = SystemClock.elapsedRealtimeNanos()
        show(h)
        compose.waitUntil(10_000) { drawn.get() != 0L }
        val rowsMs = (drawn.get() - start) / 1e6
        compose.onNode(hasContentDescription(rows().first().a11y)).assertIsDisplayed()
        val posters = postersShown(start)
        val (size, max) = i.memory()
        val entries = i.loader.memoryCache?.keys?.size ?: 0
        report(
            "$side, rows ${if (warm) "warm" else "cold"}: first rows drawn in %.0f ms (budget %.0f ms), ${vm.ui.value!!.view.total} titles; first posters fetched (%d on the screen, %d ms a fetch) all shown in %.0f ms, each p50 %.0f / p90 %.0f ms; %d fetched, %d failed; image cache %.1f of %.1f MB (%d images)".format(
                rowsMs, budgetMs, posters.size, i.latencyMs, posters.last(), posters.p(0.5), posters.p(0.9), i.fetched.get(), i.failed.get(), size / MB, max / MB, entries,
            ),
        )
        assertEquals("asked the network", 0, i.refused.get())
        assertTrue("$side ${if (warm) "warm" else "cold"} took $rowsMs ms", rowsMs <= budgetMs)
    }

    @Test
    fun showsOpenColdWithRowsWithinBudgetAndTheirPostersFetched() {
        opens("Shows", warm = false, budgetMs = COLD_MS)
    }

    @Test
    fun showsOpenWarmWithRowsWithinBudgetAndTheirPostersFetched() {
        opens("Shows", warm = true, budgetMs = WARM_MS)
    }

    @Test
    fun moviesOpenColdWithRowsWithinBudgetAndTheirPostersFetched() {
        opens("Movies", warm = false, budgetMs = COLD_MS)
    }

    @Test
    fun moviesOpenWarmWithRowsWithinBudgetAndTheirPostersFetched() {
        opens("Movies", warm = true, budgetMs = WARM_MS)
    }

    /**
     * The tab opened, its posters shown, left and opened again with the same images: the posters on the screen come from
     * the memory cache, none fetched again, and how soon they all show.
     */
    private fun opensAgain(side: String) {
        val h = harness(CollectionViewPrefs(side = side))
        val i = images()
        opened(h)
        postersShown(SystemClock.elapsedRealtimeNanos())
        val fetched = i.fetched.get()
        val fromMemory = i.fromMemory.get()
        compose.runOnIdle { open = false }
        compose.waitUntil(5_000) { onScreen().isEmpty() }
        i.shown.clear()
        val start = SystemClock.elapsedRealtimeNanos()
        compose.runOnIdle { open = true }
        val posters = postersShown(start)
        val hits = i.fromMemory.get() - fromMemory
        report("$side opened again: first posters (%d on the screen) all shown in %.0f ms, each p50 %.0f / p90 %.0f ms; %d from memory, %d fetched again".format(posters.size, posters.last(), posters.p(0.5), posters.p(0.9), hits, i.fetched.get() - fetched))
        assertEquals("fetched again", fetched, i.fetched.get())
        assertTrue("from memory: $hits of ${posters.size}", hits >= posters.size)
    }

    @Test
    fun showsPostersComeFromMemoryAsTheTabOpensAgain() {
        opensAgain("Shows")
    }

    @Test
    fun moviesPostersComeFromMemoryAsTheTabOpensAgain() {
        opensAgain("Movies")
    }

    /** CH down from the switch to the end, three lines a page, the focus on each page's first poster; then CH up back to the switch. */
    @Test
    fun pagingThroughAThousandShowsLeavesTheFocusOnEachPagesPoster() {
        val h = harness()
        images()
        opened(h)
        remote.waitForFocus(SWITCH)
        val count = rows().size
        var from: Int? = null
        var pages = 0
        val down = SystemClock.elapsedRealtime()
        while (true) {
            val to = GridPaging.lines(from, count, forward = true) as? GridPaging.Target.Poster ?: break
            assertEquals("page ${pages + 1} down", poster(to.row), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
            from = to.row
            pages++
        }
        val downMs = SystemClock.elapsedRealtime() - down
        val last = from!! / GridPaging.COLUMNS + 1
        assertEquals("past the last line: it stays", poster(from!!), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        val blank = postersShown(SystemClock.elapsedRealtimeNanos())
        var up = 0
        while (true) {
            when (val to = GridPaging.lines(from, count, forward = false)) {
                is GridPaging.Target.Poster -> {
                    assertEquals("page ${up + 1} up", poster(to.row), remote.press(KeyEvent.KEYCODE_PAGE_UP))
                    from = to.row
                    up++
                }
                else -> {
                    assertEquals("from the first line: the switch", SWITCH, remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
                    break
                }
            }
        }
        report("Recently watched: $pages pages down to line $last of ${(count + GridPaging.COLUMNS - 1) / GridPaging.COLUMNS} in $downMs ms (%.0f ms a page), the last page's posters shown %.0f ms after; $up pages back up; %d fetched".format(downMs.toDouble() / pages, blank.last(), images!!.fetched.get()))
        assertEquals(0, images!!.refused.get())
    }

    /** In A–Z, CH down a letter at a time to the last, the focus on each letter's first poster; then back up, letter by letter, to the switch. */
    @Test
    fun pagingByLetterThroughAThousandShowsLeavesTheFocusOnEachLettersFirst() {
        val h = harness(CollectionViewPrefs(showSort = "AZ"))
        images()
        opened(h)
        remote.waitForFocus(SWITCH)
        val letters = vm.ui.value!!.view.letters!!
        val starts = letters.letters.mapNotNull { letter -> letters.first[letter]?.let { letter to it } }
        val down = SystemClock.elapsedRealtime()
        starts.forEach { (letter, row) -> assertEquals("down to $letter", poster(row), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)) }
        val downMs = SystemClock.elapsedRealtime() - down
        starts.dropLast(1).reversed().forEach { (letter, row) -> assertEquals("up to $letter", poster(row), remote.press(KeyEvent.KEYCODE_CHANNEL_UP)) }
        assertEquals(SWITCH, remote.press(KeyEvent.KEYCODE_CHANNEL_UP))
        report("A–Z: ${starts.size} letters down in $downMs ms (%.0f ms a letter) and back up; %d fetched".format(downMs.toDouble() / starts.size, images!!.fetched.get()))
    }

    /** Down held for a while, as a remote repeats it: the focus ends on a poster, in view, its line's posters shown soon after. */
    @Test
    fun aHeldDownLeavesTheFocusOnAPosterInView() {
        val h = harness()
        val i = images()
        opened(h)
        remote.waitForFocus(SWITCH)
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        val repeats = hold(KeyEvent.KEYCODE_DPAD_DOWN, HOLD_MS)
        val released = SystemClock.elapsedRealtimeNanos()
        compose.waitForIdle()
        val focused = remote.focused()
        val row = rows().indexOfFirst { it.a11y == focused }
        assertTrue("on a poster after the held Down: \"$focused\"", row >= 0)
        compose.onNode(hasContentDescription(focused)).assertIsDisplayed()
        val blank = postersShown(released)
        report("held Down ${HOLD_MS} ms ($repeats repeats): to line ${row / GridPaging.COLUMNS + 1}; the posters in view all shown %.0f ms after the release; %d fetched, %d cancelled, %d from memory".format(blank.last(), i.fetched.get(), i.cancelled.get(), i.fromMemory.get()))
    }

    /** Down every line of the shows, then of the movies: every poster loaded once; how much of the images stays in memory then. */
    @Test
    fun aWalkDownEveryLineOfBothSidesKeepsTheImagesInTheirCache() {
        val h = harness()
        val i = images()
        opened(h)
        remote.waitForFocus(SWITCH)
        val heap0 = heapMb()
        walkDown()
        val shows = i.memory()
        val showsHeld = i.loader.memoryCache?.keys?.size ?: 0
        val heapShows = heapMb()
        // Up to the switch (Back from below the first screen), the movies' side, and down its lines.
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(SWITCH)
        assertEquals("Movies 2000", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { vm.ui.value?.side == io.skjaere.usenet.app.collection.model.Side.Movies }
        walkDown()
        val movies = i.memory()
        val moviesHeld = i.loader.memoryCache?.keys?.size ?: 0
        val heap = heapMb()
        // Back up lines gone from the screen on the way down: their posters, just shown, come from the memory cache.
        val fetched = i.fetched.get()
        val fromMemory = i.fromMemory.get()
        repeat(BACK_UP_LINES) { remote.press(KeyEvent.KEYCODE_DPAD_UP) }
        postersShown(SystemClock.elapsedRealtimeNanos())
        val hits = i.fromMemory.get() - fromMemory
        report(
            "a walk down every line: image cache %.1f of %.1f MB (%d images) after the shows' 143 lines, %.1f MB (%d images) after the movies' 286 too; %d fetched, %d from memory, %d cancelled, %d failed; $BACK_UP_LINES lines back up: %d from memory, %d fetched; Java heap %.0f MB before, %.0f after the shows, %.0f after both; native heap %.0f MB".format(
                shows.first / MB, shows.second / MB, showsHeld, movies.first / MB, moviesHeld, fetched, fromMemory, i.cancelled.get(), i.failed.get(),
                hits, i.fetched.get() - fetched, heap0, heapShows, heap, Debug.getNativeHeapAllocatedSize() / MB,
            ),
        )
        assertEquals("the lines walked back up to fetched again", fetched, i.fetched.get())
        assertTrue("the lines walked back up to: none from memory", hits > 0)
        assertEquals(0, i.refused.get())
        assertEquals(0, i.failed.get())
    }

    /** From where the focus is, CH down to the first poster, then Down a line at a time to the last line. */
    private fun walkDown() {
        assertEquals(poster(0), remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        val lines = (rows().size + GridPaging.COLUMNS - 1) / GridPaging.COLUMNS
        repeat(lines - 1) { remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("the last line's first poster", poster((lines - 1) * GridPaging.COLUMNS), remote.focused())
        postersShown(SystemClock.elapsedRealtimeNanos())
    }

    /** [ms] go by, on the real clock and on the screen's. */
    private fun pass(ms: Long) {
        Thread.sleep(ms)
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun heapMb(): Double = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / MB }

    /**
     * [code] held for [ms]: down, then a remote's repeats (the first after the system's key repeat timeout, then every
     * 50 ms), then up; how many repeats. The screen's clock (the test's) is moved on with the real one, so it scrolls and
     * lays out between the repeats as a TV's would.
     */
    private fun hold(code: Int, ms: Long): Int {
        val down = SystemClock.uptimeMillis()
        val press = KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0)
        instrumentation.sendKeySync(press)
        pass(REPEAT_TIMEOUT_MS)
        var n = 0
        while (SystemClock.uptimeMillis() - down < ms) {
            instrumentation.sendKeySync(KeyEvent.changeTimeRepeat(press, SystemClock.uptimeMillis(), ++n))
            pass(REPEAT_MS)
        }
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        return n
    }

    private companion object {
        const val TAG = "CollectionScale"
        const val COLD_MS = 800.0
        const val WARM_MS = 300.0
        const val SWITCH = "Shows 1000"
        const val POSTER_PX = 185
        const val MB = 1024.0 * 1024
        const val HOLD_MS = 4_000L

        /** Further up than the screen's lines, so these were gone from it on the way down. */
        const val BACK_UP_LINES = 4
        const val REPEAT_TIMEOUT_MS = 500L
        const val REPEAT_MS = 50L
    }
}

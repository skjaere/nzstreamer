package io.skjaere.usenet.app.collection

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.GridPaging
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.CollectionViewPrefs
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Frame times on the TV emulator as a remote moves through the TV's Collection grid at slice 12's size (1,000 shows,
 * 2,000 movies, ScaleFixture), each title with a poster from a test loader of its own ([ScaleImages]): the paging keys
 * three lines at a time in Recently watched, a letter at a time in A–Z, Down held as a remote repeats it, a walk along a
 * line, and paging the movies. Each first over a plain grid of 1,000 tiles with two lines of words and no images, moved
 * the same way, as the emulator's own baseline. On the real display clock: no compose test rule, whose clock would drive
 * the frames instead. The focus is checked after each page. At most a quarter of the frames late (past their deadline)
 * while paging and walking along a line; a held Down at most 15 points over its baseline's. Every number goes to logcat
 * under "CollectionScale". TV-4's L21; phones skip it, and the sharded phone runs leave it out.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvCollectionFrameStatsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var harness: CollectionHarness? = null
    private var images: ScaleImages? = null

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

    /** One frame: its total time, whether it missed its deadline, and its stages (ms). */
    private class Frame(val total: Double, val late: Boolean, val stages: Map<String, Double>)

    private class Stats(val name: String, all: List<Frame>, val drivenMs: Long) {
        val frames = all.sortedBy { it.total }
        val late = frames.count { it.late }
        val latePercent get() = if (frames.isEmpty()) 0.0 else late * 100.0 / frames.size
        fun p(q: Double) = frames[((frames.size - 1) * q).toInt()].total
        private fun median(stage: String) = frames.map { it.stages.getValue(stage) }.sorted()[frames.size / 2]
        override fun toString() = if (frames.isEmpty()) "$name: driven for $drivenMs ms, no frames" else
            "$name: driven for $drivenMs ms, ${frames.size} frames, p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, max %.1f ms, late: $late (%.0f %%); median stages: %s".format(
                p(0.5), p(0.9), p(0.99), frames.last().total, latePercent, STAGES.keys.joinToString { "$it %.1f".format(median(it)) },
            )
    }

    /** The keys a measurement presses, and what it looks at between them. */
    private inner class Driver(private val scenario: ActivityScenario<ComponentActivity>) {
        fun key(code: Int) {
            instrumentation.sendKeyDownUpSync(code)
        }

        /** What has the focus now, by its description or its words; null for nothing. */
        fun focused(): String? {
            var words: String? = null
            scenario.onActivity { a ->
                val root = (a.window.decorView as ViewGroup).composeRoot() ?: return@onActivity
                words = root.semanticsOwner.rootSemanticsNode.focusedNode()?.let { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                        ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
                }
            }
            return words
        }

        /** [code] held for [ms]: down, a remote's repeats (the first after the key repeat timeout, then every 50 ms), up. */
        fun hold(code: Int, ms: Long): Int {
            val down = SystemClock.uptimeMillis()
            val press = KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0)
            instrumentation.sendKeySync(press)
            Thread.sleep(REPEAT_TIMEOUT_MS)
            var n = 0
            while (SystemClock.uptimeMillis() - down < ms) {
                instrumentation.sendKeySync(KeyEvent.changeTimeRepeat(press, SystemClock.uptimeMillis(), ++n))
                Thread.sleep(REPEAT_MS)
            }
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            return n
        }
    }

    private fun ViewGroup.composeRoot(): ViewRootForTest? {
        for (i in 0 until childCount) {
            when (val child: View = getChildAt(i)) {
                is ViewRootForTest -> return child
                is ViewGroup -> child.composeRoot()?.let { return it }
            }
        }
        return null
    }

    private fun SemanticsNode.focusedNode(): SemanticsNode? =
        if (config.getOrNull(SemanticsProperties.Focused) == true) this else children.firstNotNullOfOrNull { it.focusedNode() }

    /**
     * [content] as the TV's Collection tab shows it (in the TV shell, beside the rail), in an activity of its own, its images
     * from a test loader of its own (so each measurement starts with none in memory); once [ready] and a moment more for its
     * first focus, [drive] presses its keys with every frame's time recorded.
     */
    private fun measure(name: String, ready: () -> Boolean, content: @Composable () -> Unit, drive: Driver.() -> Unit): Stats {
        val frames = Collections.synchronizedList(mutableListOf<Frame>())
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val late = if (Build.VERSION.SDK_INT >= 31) total > metrics.getMetric(FrameMetrics.DEADLINE) else total > 16_700_000
                frames += Frame(total / 1e6, late, STAGES.mapValues { (_, id) -> if (id < 0) 0.0 else metrics.getMetric(id) / 1e6 })
            }
        }
        images?.restore()
        images = ScaleImages(context).also { it.install() }
        val playback = context.container.playback
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    AppTheme(FormFactor.Tv) {
                        val nav = rememberNavController()
                        TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                            NavHost(nav, startDestination = CollectionRoute) { composable<CollectionRoute> { content() } }
                        }
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!ready()) {
                assertTrue("never ready", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(50)
            }
            SystemClock.sleep(SETTLE_MS)
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            val start = SystemClock.elapsedRealtime()
            Driver(scenario).drive()
            val took = SystemClock.elapsedRealtime() - start
            SystemClock.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            return Stats(name, synchronized(frames) { frames.toList() }, took).also { Log.i(TAG, "TV: $it") }
        }
    }

    private fun harness(prefs: CollectionViewPrefs = CollectionViewPrefs()) =
        CollectionHarness(context, prefs = prefs, posters = true).also { harness = it; it.seed(); it.warm() }

    private lateinit var vm: CollectionViewModel

    /** The collection's screen over [h], for [measure]. */
    @Composable
    private fun Collection(h: CollectionHarness) {
        vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
        CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm)
    }

    private fun collectionReady(): Boolean = runCatching { vm.ui.value?.view?.rows?.isNotEmpty() == true }.getOrDefault(false)

    private fun rows(): List<TitleRow> = vm.ui.value!!.view.rows

    /**
     * The paging keys toward the end [presses] times, [GAP_MS] apart, from the switch; the focus checked after each page
     * against where [target] says it goes (from the poster at a row, or from the switch: null). What was wrong, if anything.
     */
    private fun Driver.page(presses: Int, target: (from: Int?) -> GridPaging.Target, key: Int = KeyEvent.KEYCODE_CHANNEL_DOWN): List<String> {
        val wrong = mutableListOf<String>()
        var from: Int? = null
        repeat(presses) { n ->
            key(key)
            SystemClock.sleep(GAP_MS)
            (target(from) as? GridPaging.Target.Poster)?.let { from = it.row }
            val expected = from?.let { rows()[it].a11y }
            val now = focused()
            if (expected != null && now != expected) wrong += "page ${n + 1}: on \"$now\", not \"$expected\""
        }
        return wrong
    }

    /** As [page], back toward the top until the switch: each letter's or page's poster, then the switch. */
    private fun Driver.pageBack(start: Int, target: (from: Int) -> GridPaging.Target, switch: String): List<String> {
        val wrong = mutableListOf<String>()
        var from = start
        var n = 0
        while (true) {
            key(KeyEvent.KEYCODE_CHANNEL_UP)
            SystemClock.sleep(GAP_MS)
            n++
            val expected = when (val to = target(from)) {
                is GridPaging.Target.Poster -> rows()[to.row].a11y.also { from = to.row }
                else -> switch
            }
            val now = focused()
            if (now != expected) wrong += "back ${n}: on \"$now\", not \"$expected\""
            if (expected == switch) return wrong
        }
    }

    private fun baseline(name: String, drive: Driver.() -> Unit): Stats = measure("plain grid, $name", ready = { true }, content = { PlainGrid(PLAIN) }, drive = drive)

    @Test
    fun pagingThreeLinesAtATimeThroughAThousandShows() {
        val base = baseline("CH paging") { repeat(PAGES) { key(KeyEvent.KEYCODE_CHANNEL_DOWN); SystemClock.sleep(GAP_MS) } }
        val h = harness()
        var wrong = emptyList<String>()
        val stats = measure("collection, 1,000 shows with posters, CH paging in Recently watched", ::collectionReady, { Collection(h) }) {
            wrong = page(PAGES, { from -> GridPaging.lines(from, rows().size, forward = true) })
        }
        gate(base, stats, wrong, images!!)
    }

    @Test
    fun pagingALetterAtATimeThroughAThousandShows() {
        val h = harness(CollectionViewPrefs(showSort = "AZ"))
        var wrong = emptyList<String>()
        var letters = 0
        val stats = measure("collection, 1,000 shows with posters, CH paging in A–Z, down and back up", ::collectionReady, { Collection(h) }) {
            val index = vm.ui.value!!.view.letters!!
            val starts = index.letters.mapNotNull { index.first[it] }
            letters = starts.size
            wrong = page(starts.size, { from -> GridPaging.letters(from, starts, forward = true) }) +
                pageBack(starts.last(), { from -> GridPaging.letters(from, starts, forward = false) }, SWITCH)
        }
        val i = images!!
        // The plain grid after it here, as many presses as there are letters, down and back up, three lines a time.
        val base = baseline("CH paging, as many presses as A–Z's") {
            repeat(letters) { key(KeyEvent.KEYCODE_CHANNEL_DOWN); SystemClock.sleep(GAP_MS) }
            repeat(letters) { key(KeyEvent.KEYCODE_CHANNEL_UP); SystemClock.sleep(GAP_MS) }
        }
        gate(base, stats, wrong, i)
    }

    @Test
    fun pagingThreeLinesAtATimeThroughTwoThousandMovies() {
        val base = baseline("CH paging") { repeat(PAGES) { key(KeyEvent.KEYCODE_CHANNEL_DOWN); SystemClock.sleep(GAP_MS) } }
        val h = harness(CollectionViewPrefs(side = "Movies"))
        var wrong = emptyList<String>()
        val stats = measure("collection, 2,000 movies with posters, CH paging in Recently added", ::collectionReady, { Collection(h) }) {
            wrong = page(PAGES, { from -> GridPaging.lines(from, rows().size, forward = true) })
        }
        gate(base, stats, wrong, images!!)
    }

    @Test
    fun walkingAlongALine() {
        val walk: Driver.() -> Unit = {
            repeat(WALKS) {
                repeat(GridPaging.COLUMNS - 1) { key(KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(STEP_MS) }
                repeat(GridPaging.COLUMNS - 1) { key(KeyEvent.KEYCODE_DPAD_LEFT); SystemClock.sleep(STEP_MS) }
            }
        }
        val base = baseline("a walk along a line", walk)
        val h = harness()
        var wrong = emptyList<String>()
        val stats = measure("collection, 1,000 shows with posters, a walk along the first line", ::collectionReady, { Collection(h) }) {
            // From the switch to the first poster, then along its line and back.
            key(KeyEvent.KEYCODE_CHANNEL_DOWN)
            SystemClock.sleep(GAP_MS)
            walk()
            val now = focused()
            if (now != rows()[0].a11y) wrong = listOf("after the walk: on \"$now\", not the first poster")
        }
        gate(base, stats, wrong, images!!)
    }

    /** Down held, as a remote repeats it: recorded, its gate looser (its baseline's late share plus 15 points). */
    @Test
    fun holdingDown() {
        var baseRepeats = 0
        val base = baseline("Down held") { baseRepeats = hold(KeyEvent.KEYCODE_DPAD_DOWN, HOLD_MS) }
        val h = harness()
        var repeats = 0
        var wrong = emptyList<String>()
        var line = 0
        val stats = measure("collection, 1,000 shows with posters, Down held", ::collectionReady, { Collection(h) }) {
            key(KeyEvent.KEYCODE_CHANNEL_DOWN)
            SystemClock.sleep(GAP_MS)
            repeats = hold(KeyEvent.KEYCODE_DPAD_DOWN, HOLD_MS)
            SystemClock.sleep(GAP_MS)
            val now = focused()
            val row = rows().indexOfFirst { it.a11y == now }
            line = row / GridPaging.COLUMNS + 1
            if (row < 0) wrong = listOf("after the held Down: on \"$now\", not a poster")
        }
        val i = images!!
        Log.i(TAG, "TV: held Down ${HOLD_MS} ms: baseline $baseRepeats repeats, collection $repeats repeats to line $line; ${i.fetched.get()} posters fetched, ${i.cancelled.get()} cancelled, ${i.fromMemory.get()} from memory")
        assertEquals("the focus", emptyList<String>(), wrong)
        assertEquals("asked the network", 0, i.refused.get())
        assertTrue("$stats", stats.frames.size > 10)
        assertTrue("a held Down: ${stats.late} of ${stats.frames.size} frames late, over its baseline's %.0f %% + 15".format(base.latePercent), stats.latePercent <= base.latePercent + HELD_SLACK)
    }

    /** The focus right after each move, nothing asked of the network, and at most a quarter of the frames late. */
    private fun gate(base: Stats, stats: Stats, wrong: List<String>, i: ScaleImages) {
        Log.i(TAG, "TV: ${stats.name}: ${i.fetched.get()} posters fetched, ${i.cancelled.get()} cancelled, ${i.fromMemory.get()} from memory; baseline late %.0f %%".format(base.latePercent))
        assertEquals("the focus", emptyList<String>(), wrong)
        assertEquals("asked the network", 0, i.refused.get())
        assertTrue("$stats", stats.frames.size > 10)
        assertTrue("${stats.late} of ${stats.frames.size} frames late", stats.late <= stats.frames.size / 4)
    }

    private companion object {
        const val TAG = "CollectionScale"
        const val SETTLE_MS = 1_000L
        const val GAP_MS = 400L
        const val STEP_MS = 150L
        const val PAGES = 40
        const val WALKS = 5
        const val PLAIN = 1_000
        const val HOLD_MS = 5_000L
        const val REPEAT_TIMEOUT_MS = 500L
        const val REPEAT_MS = 50L
        const val HELD_SLACK = 15.0
        const val SWITCH = "Shows 1000"

        /** FrameMetrics' stages by name; GPU only from API 31. */
        val STAGES = mapOf(
            "input" to FrameMetrics.INPUT_HANDLING_DURATION, "animation" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION, "draw" to FrameMetrics.DRAW_DURATION, "sync" to FrameMetrics.SYNC_DURATION,
            "issue" to FrameMetrics.COMMAND_ISSUE_DURATION, "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
            "gpu" to if (Build.VERSION.SDK_INT >= 31) FrameMetrics.GPU_DURATION else -1,
        )
    }
}

/**
 * The emulator's baseline: [count] tiles, seven to a line as the collection's, each a 98 x 147 tile with two lines of
 * words under it and no image, focusable; the paging keys move three lines and focus the first tile there, as the
 * collection's do, without its lines above, its cards or its memory.
 */
@Composable
private fun PlainGrid(count: Int) {
    val state = rememberLazyGridState()
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val focused = remember { IntArray(1) }
    LazyVerticalGrid(
        GridCells.Fixed(GridPaging.COLUMNS),
        state = state,
        modifier = Modifier.tvFullWidth().onPreviewKeyEvent { event ->
            val forward = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                KeyEvent.KEYCODE_CHANNEL_UP -> false
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) {
                val to = (GridPaging.lines(focused[0], count, forward) as? GridPaging.Target.Poster)?.row ?: 0
                scope.launch {
                    state.scrollToItem(to)
                    withFrameNanos {}
                    requesters[to]?.requestFocus()
                }
            }
            true
        },
        horizontalArrangement = Arrangement.spacedBy(TvDimens.posterGutter),
        verticalArrangement = Arrangement.spacedBy(TvDimens.posterLineGap),
    ) {
        items(count, key = { it }) { i ->
            val requester = remember { FocusRequester() }
            DisposableEffect(i) {
                requesters[i] = requester
                onDispose { requesters.remove(i) }
            }
            Column(Modifier.width(TvDimens.poster.width).focusRequester(requester).onFocusChanged { if (it.isFocused) focused[0] = i }.focusable()) {
                Box(Modifier.size(TvDimens.poster).background(TILE))
                Text("Avrel %04d".format(i), color = colors.text, maxLines = 1)
                Text("S01E01", color = colors.muted, maxLines = 1)
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        requesters[0]?.requestFocus()
    }
}

/** The tile under a poster's picture (TvCards' TILE). */
private val TILE = Color(0xFF2E4A5C)

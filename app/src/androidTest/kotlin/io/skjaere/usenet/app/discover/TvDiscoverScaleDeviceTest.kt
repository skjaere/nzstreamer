package io.skjaere.usenet.app.discover

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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.collection.ScaleImages
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.GridPaging
import io.skjaere.usenet.app.tv.PageTestRoute
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * See all at its cap on the TV emulator: 500 invented titles (TMDB's 25 pages of 20, each page 100 ms away, as a fetch
 * would be), each with a poster from TV-4's test loader ([ScaleImages]: one made-up JPEG after 40 ms, nothing from the
 * network). How long the first two lines' posters take from a cold start, then frame times as CH pages three lines at
 * a time down all 500, the next pages asked as the grid nears its end; the focus checked after each page. At most a
 * quarter of the frames late, as TV-4's gate. Every number goes to logcat under "DiscoverScale". On the real display
 * clock (no compose test rule). TV only: phones skip it, and the sharded phone runs leave it out.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvDiscoverScaleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var images: ScaleImages? = null
    private val asked = CopyOnWriteArrayList<Int>()

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun close() {
        instrumentation.setInTouchMode(true)
        images?.restore()
    }

    private class Frame(val total: Double, val late: Boolean)

    /** The 500: 25 pages of 20, each with its own poster address. */
    private val source = FakeDiscover(answer = { q, from ->
        asked += from
        delay(PAGE_MS)
        Aged(DiscoverPage((0 until 20).map { i -> title(q, from, i) }, from, DiscoverPage.MAX_PAGES, capped = true), java.time.Duration.ZERO)
    })

    private fun title(q: DiscoverQuery, page: Int, i: Int): DiscoverTitle {
        val id = FakeDiscover.id(q.list, page, i)
        return DiscoverTitle(q.type, id, "Invented $id", 2021, "/discover-scale-$id.jpg")
    }

    /** The poster TalkBack hears as [spoken], by its place in the 500; null for anything else. */
    private fun rowOf(spoken: String): Int? {
        val id = Regex("""Invented (\d+), 2021, show""").matchEntire(spoken)?.groupValues?.get(1)?.toInt() ?: return null
        // Ids run on from page to page: the first page's first is the 500's first.
        return (id - FakeDiscover.id(DiscoverList.Popular, 1, 0)).takeIf { it in 0 until DiscoverPage.MAX_PAGES * 20 }
    }

    @Test
    fun seeAllAtItsCapShowsItsFirstPostersSoonAndPagesWithFewLateFrames() {
        images = ScaleImages(context).also { it.install() }
        val i = images!!
        val deps = DiscoverDeps(
            hasTmdbKey = flowOf(true), showArtwork = flowOf(true), language = flowOf(null),
            collectedShows = flowOf(emptySet()), collectedMovies = flowOf(emptySet()), source = source, knownKey = true,
        )
        val query = DiscoverQuery(MediaType.Tv, DiscoverList.Popular)
        val frames = Collections.synchronizedList(mutableListOf<Frame>())
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val late = if (Build.VERSION.SDK_INT >= 31) total > metrics.getMetric(FrameMetrics.DEADLINE) else total > 16_700_000
                frames += Frame(total / 1e6, late)
            }
        }
        val playback = context.container.playback
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val start = SystemClock.elapsedRealtimeNanos()
            scenario.onActivity {
                it.setContent {
                    AppTheme(FormFactor.Tv) {
                        val nav = rememberNavController()
                        TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                            NavHost(nav, startDestination = PageTestRoute) {
                                composable<PageTestRoute> { DiscoverListScreen(query, onBack = {}, onShow = {}, onMovie = {}, onMetadata = {}, deps = { deps }) }
                            }
                        }
                    }
                }
            }
            // The first two lines' posters, from nothing in memory.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (i.shown.size < FIRST_SCREEN) {
                assertTrue("only ${i.shown.size} posters came", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(10)
            }
            val firstPosters = (i.shown.values.sorted()[FIRST_SCREEN - 1] - start) / 1_000_000
            SystemClock.sleep(SETTLE_MS)
            val focus = { focused(scenario) }
            assertEquals("the first focus: the first pill", "Genre: $ALL_GENRES", focus())

            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            val paged = SystemClock.elapsedRealtime()
            val wrong = mutableListOf<String>()
            var stays = 0
            var from = -1
            var presses = 0
            // CH toward the end until the last line's first poster: 500 posters are 72 lines, 24 pages of three. Each press
            // lands on a line's first poster at most three lines on, or stays while the next page is still coming.
            while (from != LAST_LINE_FIRST && presses < MAX_PRESSES) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CHANNEL_DOWN)
                presses++
                SystemClock.sleep(GAP_MS)
                val now = focus()
                val row = now?.let(::rowOf)
                when {
                    row == null -> wrong += "page $presses: on \"$now\", not a poster"
                    row == from -> stays++
                    row % GridPaging.COLUMNS != 0 || row < from || (from >= 0 && row - from > GridPaging.LINES * GridPaging.COLUMNS) ->
                        wrong += "page $presses: on poster $row, from $from"
                }
                row?.let { from = it }
            }
            val took = SystemClock.elapsedRealtime() - paged
            SystemClock.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()

            val all = synchronized(frames) { frames.toList() }.sortedBy { it.total }
            val late = all.count { it.late }
            val (used, most) = i.memory()
            fun p(q: Double) = all[((all.size - 1) * q).toInt()].total
            Log.i(
                TAG,
                "TV: See all, 500 titles: first $FIRST_SCREEN posters in $firstPosters ms; CH paging $presses presses in $took ms, " +
                    "${all.size} frames, p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, max %.1f ms, late $late (%.0f %%); ".format(p(0.5), p(0.9), p(0.99), all.last().total, late * 100.0 / all.size) +
                    "pages asked ${asked.size}, presses that stayed while a page came $stays; posters fetched ${i.fetched.get()}, cancelled ${i.cancelled.get()}, " +
                    "from memory ${i.fromMemory.get()}; Coil's memory %.1f of %.1f MB".format(used / 1e6, most / 1e6),
            )
            assertEquals("the focus after each page", emptyList<String>(), wrong)
            assertEquals("every page, each once", (1..DiscoverPage.MAX_PAGES).toList(), asked.toList())
            assertEquals("asked the network", 0, i.refused.get())
            assertTrue("first posters in $firstPosters ms", firstPosters <= FIRST_POSTERS_MS)
            assertTrue("${all.size} frames", all.size > 10)
            assertTrue("$late of ${all.size} frames late", late <= all.size / 4)
        }
    }

    private fun focused(scenario: ActivityScenario<ComponentActivity>): String? {
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

    private fun ViewGroup.composeRoot(): ViewRootForTest? {
        for (n in 0 until childCount) {
            when (val child: View = getChildAt(n)) {
                is ViewRootForTest -> return child
                is ViewGroup -> child.composeRoot()?.let { return it }
            }
        }
        return null
    }

    private fun SemanticsNode.focusedNode(): SemanticsNode? =
        if (config.getOrNull(SemanticsProperties.Focused) == true) this else children.firstNotNullOfOrNull { it.focusedNode() }

    private companion object {
        const val TAG = "DiscoverScale"
        /** A TMDB page's time, about; set, so runs compare. */
        const val PAGE_MS = 100L
        /** The first two lines. */
        const val FIRST_SCREEN = 14
        /** TV-4's budget for a tab's first rows, cold. */
        const val FIRST_POSTERS_MS = 800L
        const val SETTLE_MS = 1_000L
        const val GAP_MS = 400L
        /** 500 posters, seven to a line: the last line, 71, starts at 497. */
        const val LAST_LINE_FIRST = 497
        const val MAX_PRESSES = 40
    }
}

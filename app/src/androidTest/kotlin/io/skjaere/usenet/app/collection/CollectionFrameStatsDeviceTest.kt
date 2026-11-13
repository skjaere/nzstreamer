package io.skjaere.usenet.app.collection

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.colors
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Frame times while flinging through 1,000 shows (ScaleFixture, over a library of its own), on the
 * real display clock: no compose test rule, whose clock would drive the frames instead, and flings
 * injected as a finger would make them. A plain list of 1,000 two-line rows is flung the same way
 * first, as the emulator's own baseline. Both go to logcat under "CollectionScale"; the collection
 * may have at most a quarter of its frames over 16.7 ms.
 */
@RunWith(AndroidJUnit4::class)
class CollectionFrameStatsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var harness: CollectionHarness? = null

    @After
    fun close() {
        harness?.close()
    }

    /** One frame: its total time, whether it missed its deadline, and its stages (ms). */
    private class Frame(val total: Double, val late: Boolean, val stages: Map<String, Double>)

    private class Stats(all: List<Frame>, val flingMs: Double) {
        val frames = all.sortedBy { it.total }
        /** Past the frame's deadline (API 31+), else past one 60 Hz interval. */
        val janky = frames.count { it.late }
        fun p(q: Double) = frames[((frames.size - 1) * q).toInt()].total
        private fun median(stage: String) = frames.map { it.stages.getValue(stage) }.sorted()[frames.size / 2]
        override fun toString() =
            "$FLINGS flings in %.0f ms: ${frames.size} frames, p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, max %.1f ms, late: $janky (%.0f %%), over 16.7 ms: %d (%.0f %%); median stages: %s".format(
                flingMs, p(0.5), p(0.9), p(0.99), frames.last().total, janky * 100.0 / frames.size, over, over * 100.0 / frames.size,
                STAGES.keys.joinToString { "$it %.1f".format(median(it)) },
            )

        /** By the old rule of thumb; a frame right on a 60 Hz vsync (16.70x ms) counts here, which is why [janky] goes by the deadline. */
        private val over get() = frames.count { it.total > 16.7 }
    }

    /** [content] in an activity of its own; once [ready], [FLINGS] flings up the screen with every frame's time recorded. */
    private fun fling(ready: () -> Boolean, content: @Composable () -> Unit): Stats {
        val frames = Collections.synchronizedList(mutableListOf<Frame>())
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val late = if (Build.VERSION.SDK_INT >= 31) total > metrics.getMetric(FrameMetrics.DEADLINE) else total > 16_700_000
                frames += Frame(total / 1e6, late, STAGES.mapValues { (_, id) -> if (id < 0) 0.0 else metrics.getMetric(id) / 1e6 })
            }
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { AppTheme { content() } } }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!ready()) {
                assertTrue("never ready", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(50)
            }
            SystemClock.sleep(SETTLE_MS)
            var width = 0
            var height = 0
            scenario.onActivity { a ->
                width = a.window.decorView.width
                height = a.window.decorView.height
                a.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            val start = SystemClock.elapsedRealtime()
            repeat(FLINGS) {
                swipe(width / 2f, height * 0.8f, height * 0.3f)
                SystemClock.sleep(GAP_MS)
            }
            val took = (SystemClock.elapsedRealtime() - start).toDouble()
            SystemClock.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            return Stats(synchronized(frames) { frames.toList() }, took)
        }
    }

    /** A finger's fling: down, eight moves 10 ms apart, up. */
    private fun swipe(x: Float, fromY: Float, toY: Float) {
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, at: Long) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromY, down)
        (1..STEPS).forEach { step ->
            SystemClock.sleep(10)
            send(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / STEPS, SystemClock.uptimeMillis())
        }
        send(MotionEvent.ACTION_UP, toY, SystemClock.uptimeMillis())
    }

    @Test
    fun thirtyFlingsThroughAThousandShowsStaySmooth() {
        val baseline = fling(ready = { true }) {
            LazyColumn(Modifier.fillMaxSize()) {
                items((0 until 1_000).toList(), key = { it }) { i ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 86.dp).padding(16.dp)) {
                        Text("Avrel %04d".format(i), color = colors.text)
                        Text("Next: S01E01 · 10 unwatched", color = colors.muted)
                    }
                }
            }
        }
        Log.i(TAG, "plain list (the emulator's baseline): $baseline")

        val h = CollectionHarness(instrumentation.targetContext).also { harness = it; it.seed() }
        h.warm()
        // The collection's rows alone, in a plain list: what the rows cost apart from the rest of the screen.
        val shows = h.store.shows.value!!
        val rowsOnly = fling(ready = { true }) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shows, key = { _, row -> row.key.value }) { i, row ->
                    TitleRowItem(
                        row, first = i == 0, last = i == shows.lastIndex, selecting = false, selected = false, highlighted = false, justAdded = false, artwork = false,
                        dateChip = false, focus = null, onClick = {}, onLongClick = {}, onSelect = {}, onRemove = {},
                    )
                }
            }
        }
        Log.i(TAG, "the collection's rows in a plain list: $rowsOnly")

        lateinit var vm: CollectionViewModel
        val collection = fling(ready = { runCatching { vm.ui.value?.view?.rows?.isNotEmpty() }.getOrNull() == true }) {
            vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
            CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm)
        }
        Log.i(TAG, "collection, 1000 shows: $collection")
        assertTrue("$collection", collection.frames.size > FLINGS)
        assertTrue("${collection.janky} of ${collection.frames.size} frames late", collection.janky <= collection.frames.size / 4)
    }

    private companion object {
        const val TAG = "CollectionScale"
        const val FLINGS = 30
        const val STEPS = 8
        const val GAP_MS = 700L
        const val SETTLE_MS = 1_000L

        /** FrameMetrics' stages by name; GPU only from API 31. */
        val STAGES = mapOf(
            "input" to FrameMetrics.INPUT_HANDLING_DURATION, "animation" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION, "draw" to FrameMetrics.DRAW_DURATION, "sync" to FrameMetrics.SYNC_DURATION,
            "issue" to FrameMetrics.COMMAND_ISSUE_DURATION, "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
            "gpu" to if (Build.VERSION.SDK_INT >= 31) FrameMetrics.GPU_DURATION else -1,
        )
    }
}

package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.UsenetDataSourceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The player keeps the screen on only while playback runs or is about to (KeepAwake, TV-BU and
 * TV-BY): paused, a phone may sleep and a TV show its screensaver, and so with the "Playback
 * stopped" panel up. The chapters.mkv fixture (see SkipSegmentsDeviceTest) opened as an NZB file
 * is; the panel comes from a provider refusing the stream as the player, made again after onStop,
 * opens it. (A seek can't do it: the fixture is so small that the player holds all of it at once.)
 * That player waits paused (W106), which alone would let the screen go: it is played under the
 * panel, the provider answering again, so that only the panel lets the screen go off.
 * The stream-cost line runs just as long (StreamCostMonitor): its first line reaches the Logs screen's
 * log 10 s into the play, with the player's threads in their groups and no provider named.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class KeepScreenOnDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realDataSources = PlayerActivity.dataSources
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.dataSources = realDataSources
    }

    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))

    @Test
    fun theScreenStaysOnWhilePlayingAndMayGoOffPausedOrStopped() = runBlocking<Unit> {
        val refusing = AtomicBoolean(false)
        PlayerActivity.dataSources = { c -> DataSource.Factory { Refusing(c.dataSourceFactory.createDataSource(), refusing) } }
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("playback", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            awaitScreenOn(scenario, true, "while playing")
            val line = waitFor("a stream-cost line", timeoutMs = 15_000) {
                LogBuffer.snapshot().lastOrNull { it.seq > since && it.tag == "StreamCost" }?.message
            }
            assertTrue(line, line.startsWith("Stream cost over ") && Regex("""loader \d+ \([1-9]\d*\), playback \d+ \([1-9]\d*\)""") in line)
            // The play's first line: it says what the first sample took too, which opened the threads' files.
            assertTrue(line, Regex("""the sample took \d+\.\d\d ms of CPU \(the first, opening its files, \d+\.\d\d ms\)$""") in line)
            assertFalse(line, "10.0.2.2" in line)
            scenario.onActivity { it.player!!.pause() }
            awaitScreenOn(scenario, false, "paused", timeoutMs = 1_000)
            scenario.onActivity { it.player!!.play() }
            awaitScreenOn(scenario, true, "playing again", timeoutMs = 1_000)
            // Left and come back to, the player made again finds the provider refusing the stream.
            scenario.moveToState(Lifecycle.State.CREATED)
            refusing.set(true)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            refusing.set(false)
            scenario.onActivity { a -> a.player!!.run { prepare(); play() } }
            waitFor("the player made again playing under the panel", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            assertTrue("the panel still up", "Playback stopped" in scenario.read { a -> overlayWords(a) })
            awaitScreenOn(scenario, false, "with the panel up", timeoutMs = 1_000)
        }
    }

    /**
     * Waits until something in the player's window keeps the screen on, or nothing does, as [on] says; and the
     * stream-cost line runs, or doesn't, alike.
     */
    private suspend fun awaitScreenOn(scenario: ActivityScenario<PlayerActivity>, on: Boolean, case: String, timeoutMs: Long = 5_000) {
        waitFor("the screen kept ${if (on) "on" else "free to go off"} $case", timeoutMs) {
            scenario.read { a -> (views(a.window.decorView).any { it.keepScreenOn } == on).takeIf { it } }
        }
        waitFor("the stream-cost line ${if (on) "running" else "stopped"} $case", timeoutMs) { (container.streamCost.running == on).takeIf { it } }
    }

    /** A data source the provider refuses to open while [refusing]. */
    private class Refusing(private val source: DataSource, private val refusing: AtomicBoolean) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            if (refusing.get()) {
                val error = UsenetError.AuthFailed("481 Authentication rejected")
                throw UsenetDataSourceException(error, IOException(error.detail), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
            }
            return source.open(dataSpec)
        }
    }

    private fun overlayWords(activity: Activity): List<String> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }
}

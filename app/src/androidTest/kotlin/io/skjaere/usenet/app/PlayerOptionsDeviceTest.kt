package io.skjaere.usenet.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.player.PlayerFactory
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudget
import io.skjaere.usenet.player.tv.PlaybackBudgets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's options from the device's memory (PlaybackBudget, TV-ME): a low-RAM box of 1 GB,
 * made up through PlayerActivity's memory seam, plays with a 48 MB sample buffer, which the
 * player's load control stops loading at: the one the player was made with, watched through
 * PlayerActivity's load control seam. The chapters.mkv fixture (see SkipSegmentsDeviceTest)
 * opened as an NZB file is.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerOptionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realMemory = PlayerActivity.readMemory
    private val realLoadControl = PlayerActivity.loadControlFor
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
        PlayerActivity.readMemory = realMemory
        PlayerActivity.loadControlFor = realLoadControl
    }

    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))

    @Test
    fun aLowRamBoxOfOneGbPlaysWithA48MbBufferThatTheLoadControlStopsAt() = runBlocking<Unit> {
        val box = DeviceMemory(lowRam = true, memoryClassMb = 128, totalRamBytes = GB, process64Bit = false, abis = listOf("armeabi-v7a"))
        PlayerActivity.readMemory = { box }
        val made = CopyOnWriteArrayList<Pair<PlaybackBudget, Watched>>()
        PlayerActivity.loadControlFor = { b -> Watched(realLoadControl(b)).also { made += b to it } }
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("playback", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            val budget = scenario.read { it.budget }
            assertEquals(PlaybackBudget(48 * MB.toInt(), streamBytes = 8 * MB, imageMemoryBytes = 8 * MB), budget)
            // The player's own, made for that budget: what it loads goes into this one's buffers.
            assertEquals(listOf(budget), made.map { it.first })
            val control = made.single().second
            waitFor("the player to load into the load control made for its budget") { control.players.firstOrNull()?.let(control::loadedBy)?.takeIf { it > 0 } }
        }
        // Released with the player, it stops a play's loading at 48 MB.
        assertEquals(48 * MB, loadedBeforeStopping(made.single().second))
        // The same load control on a 1.5 GB box's budget goes on to 64 MB.
        val bigger = PlaybackBudgets.of(box.copy(totalRamBytes = GB * 3 / 2), uhdDisplay = false)
        assertEquals(64 * MB, loadedBeforeStopping(PlayerFactory.loadControl(bigger)))
    }

    /** The device's own memory, as the player reads it without the seam. */
    @Test
    fun theDevicesOwnMemoryIsRead() {
        val memory = DeviceMemory.of(context)
        assertTrue(memory.toString(), memory.totalRamBytes > 512 * MB && memory.memoryClassMb > 0 && memory.abis.isNotEmpty())
        assertEquals(android.os.Process.is64Bit(), memory.process64Bit)
    }

    /**
     * How much a play's sample buffer takes before [control] stops it loading, 10 s into a stream
     * (short of its 50 s minimum, so the byte target alone stops it): its allocator's buffers.
     */
    private fun loadedBeforeStopping(control: LoadControl): Long {
        val id = PlayerId("budget-test")
        val timeline = SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, MediaItem.fromUri(Uri.parse("usenet://fixture/0")))
        val parameters = LoadControl.Parameters(id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, 10_000_000, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        control.onPrepared(id)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray<ExoTrackSelection>())
        val allocator = control.getAllocator(id)
        var buffers = 0
        while (control.shouldContinueLoading(parameters) && buffers < 10_000) {
            allocator.allocate()
            buffers++
        }
        control.onReleased(id)
        return buffers.toLong() * allocator.individualAllocationLength
    }

    /**
     * A player's load control, deciding as [inner] does, that says which players it was prepared
     * for. Each method media3 calls is passed on by hand: Kotlin's delegation leaves the
     * interface's Java default methods to the interface, whose own lead to one that throws.
     */
    private class Watched(private val inner: LoadControl) : LoadControl by inner {
        val players = CopyOnWriteArrayList<PlayerId>()

        override fun onPrepared(playerId: PlayerId) {
            players += playerId
            inner.onPrepared(playerId)
        }

        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
            inner.onTracksSelected(parameters, trackGroups, trackSelections)

        override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

        override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

        override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)

        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = inner.retainBackBufferFromKeyframe(playerId)

        override fun shouldContinueLoading(parameters: LoadControl.Parameters) = inner.shouldContinueLoading(parameters)

        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = inner.shouldStartPlayback(parameters)

        override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
            inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

        /** What [player] holds in its buffers. */
        fun loadedBy(player: PlayerId) = inner.getAllocator(player).totalBytesAllocated
    }

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

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024 * MB
    }
}

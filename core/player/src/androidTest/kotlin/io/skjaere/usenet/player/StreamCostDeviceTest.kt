package io.skjaere.usenet.player

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import io.skjaere.usenet.core.ConnectionMeter
import io.skjaere.usenet.core.StreamCost
import io.skjaere.usenet.core.StreamCostSampler
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.core.ThreadCpuReader
import io.skjaere.usenet.core.ThreadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The stream-cost line's samples on ART: what one costs with as many threads as a play has, the groups the names ART
 * gives threads fall in, ART's GC counters, and what attaching the connection meter adds to the libraries' meters,
 * which they record per command. The figures go to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class StreamCostDeviceTest {
    /** Threads parked until [release], named as a play's are (the Shield's baseline play had 57 to 65 of them). */
    private val release = CountDownLatch(1)

    private fun park(name: String, started: CountDownLatch? = null, work: () -> Unit = {}) = thread(name = name, isDaemon = true) {
        work()
        started?.countDown()
        release.await()
    }

    private val reader = ThreadCpuReader(ticksPerSecond = clockTicksPerSecond(), files = androidKeptFiles)

    /** As the app's: Android's kept files, ART's counters. */
    private fun sampler(connections: ConnectionMeter? = null) =
        StreamCostSampler(StreamTraffic(), reader, connections, gc = ::artGcStats, threadCpuNanos = Debug::threadCpuTimeNanos)

    @Test
    fun aSampleIsCheapWithAsManyThreadsAsAPlayHas() {
        val names = List(28) { "DefaultDispatcher-worker-$it" } + List(16) { "Binder:4242_$it" } +
            listOf("ExoPlayer:Loader:ProgressiveMediaPeriod", "ExoPlayer:Playback", "MediaCodec_looper", "CodecLooper", "JNA Cleaner") +
            List(11) { "OkHttp TaskRunner $it" }
        val started = CountDownLatch(names.size)
        names.forEach { park(it, started) }
        val meter = ConnectionMeter()
        val sampler = sampler(meter)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            // The first opens each thread's file; the next few run as the app's do, one every 10 s: too seldom for the JIT.
            val first = sampler.sample()
            val threads = checkNotNull(first.cpu).threads.sum()
            val cold = LongArray(9) { sampler.sample().tookNanos }.sorted()
            repeat(50) { sampler.sample() }
            var before = sampler.sample()
            val samples = LongArray(200)
            val windows = LongArray(200)
            val lines = LongArray(200)
            for (i in samples.indices) {
                val after = sampler.sample()
                // The rest of what the line does each 10 s, the log call aside.
                val sampled = SystemClock.elapsedRealtimeNanos()
                val cost = StreamCost.between(before, after)
                val compared = SystemClock.elapsedRealtimeNanos()
                cost.line()
                lines[i] = SystemClock.elapsedRealtimeNanos() - compared
                windows[i] = compared - sampled
                samples[i] = after.tookNanos
                before = after
            }
            samples.sort()
            windows.sort()
            lines.sort()
            // Closed and sampled again, as a run of lines starts on a resume: its files opened and the meter attached anew.
            sampler.close()
            val reopened = sampler.sample().tookNanos
            val figures = "$threads threads: the first sample took ${ms(first.tookNanos)} ms of CPU, opening each thread's files and " +
                "attaching the meter, the next ${ms(cold[4])} ms (median of 9); warmed up, ${ms(samples[100])} ms (median), " +
                "${ms(samples[180])} ms (p90), ${ms(samples[199])} ms (max); the first after a close ${ms(reopened)} ms; the window " +
                "compared in ${ms(windows[100])} ms and the line written in ${ms(lines[100])} ms (medians, wall time)"
            Log.i(TAG, figures)
            assertTrue(threads >= names.size)
            assertTrue(figures, cold[4] < 1_000_000 && samples[100] < 1_000_000)
        } finally {
            release.countDown()
            sampler.close()
            meter.close()
        }
    }

    @Test
    fun theThreadsAreGroupedByTheNamesArtGivesThem() {
        val sampler = sampler()
        val before = sampler.sample()
        val started = CountDownLatch(3)
        // 300 ms of CPU on the loader, as reading a stream takes it.
        park("ExoPlayer:Loader:ProgressiveMediaPeriod", started) { spin(300) }
        park("ExoPlayer:Playback", started)
        park("DefaultDispatcher-worker-99", started)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val after = sampler.sample()
            val names = reader.names()
            Log.i(TAG, "thread names: " + names.groupBy(ThreadKind::of).entries.joinToString { (kind, n) -> "$kind=${n.sorted()}" })
            assertTrue("$names", "ExoPlayer:Loade" in names && "ExoPlayer:Playb" in names && "DefaultDispatch" in names)
            val cost = StreamCost.between(before, after)
            // Each parked thread adds one to its group. A group may hold one of the process's already: Media3's background
            // executor ("ExoPlayer:BackgroundExecutor", a playback thread) lives on once a test before this one ran a player.
            val was = checkNotNull(before.cpu).threads
            assertEquals(was[ThreadKind.Loader.ordinal] + 1, cost.threads(ThreadKind.Loader))
            assertEquals(was[ThreadKind.Playback.ordinal] + 1, cost.threads(ThreadKind.Playback))
            assertTrue(cost.threads(ThreadKind.Gc) >= 1)
            assertTrue(cost.threads(ThreadKind.Jit) >= 1)
            assertTrue(cost.line(), checkNotNull(cost.ms(ThreadKind.Loader)) in 250L..400L)
        } finally {
            release.countDown()
            sampler.close()
        }
    }

    @Test
    fun artGivesItsGcCounters() {
        val before = checkNotNull(artGcStats())
        val garbage = List(1_000) { ByteArray(10_000) }
        Runtime.getRuntime().gc()
        val after = checkNotNull(artGcStats())
        assertTrue("${before.count} -> ${after.count}", after.count > before.count)
        assertTrue(after.timeMs >= before.timeMs)
        assertTrue(after.allocatedBytes - before.allocatedBytes >= garbage.size * 10_000L)
    }

    /**
     * NntpConnection builds and records a timer for each command, and the pool another for each connection it hands out:
     * with the meter's registry attached (only while a stream plays), a command's is a no-op still, at a little more.
     */
    @Test
    fun theLibrariesMetersStayCheapWithTheMeterAttached() {
        fun perCommand(): Double {
            val n = 100_000
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(n) { command() }
            return (SystemClock.elapsedRealtimeNanos() - start).toDouble() / n
        }
        // Warmed up first; the faster of two runs, each way.
        repeat(3) { perCommand() }
        val alone = minOf(perCommand(), perCommand())
        val meter = ConnectionMeter()
        try {
            meter.attach()
            val attached = minOf(perCommand(), perCommand())
            Log.i(TAG, "a command's timer: ${"%.0f".format(alone)} ns alone, ${"%.0f".format(attached)} ns with the meter attached")
            // About 50 commands a second at 10 MB/s: a few µs each would still be nothing; a histogram's work, more.
            assertTrue("$alone ns -> $attached ns", attached < alone + 5_000)
        } finally {
            meter.close()
        }
    }

    private fun command() {
        val sample = Timer.start(Metrics.globalRegistry)
        sample.stop(
            Timer.builder("nntp.command.ack_latency")
                .description("Time from sending an NNTP command to receiving the first byte of the response status line")
                .publishPercentileHistogram()
                .tag("command", "BODY")
                .register(Metrics.globalRegistry),
        )
    }

    private fun spin(ms: Long) {
        val end = SystemClock.currentThreadTimeMillis() + ms
        var x = 0L
        while (SystemClock.currentThreadTimeMillis() < end) x += x xor 7
        if (x == 42L) Log.i(TAG, "unlikely")
    }

    private fun ms(nanos: Long) = "%.3f".format(nanos / 1e6)

    private companion object {
        const val TAG = "StreamCostDeviceTest"
    }
}

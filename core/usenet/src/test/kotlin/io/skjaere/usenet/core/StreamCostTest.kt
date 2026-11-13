package io.skjaere.usenet.core

import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stream-cost line: the streams' rates, the connections', the CPU per MB streamed by thread group and ART's GC
 * over a window between two samples, and the monitor that logs one every 10 s while a stream plays and stops with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamCostTest {
    /**
     * The process's CPU so far, and each kind's and its threads, in [ThreadKind]'s order (workers, loader, playback, codec,
     * GC, JIT, rest); the CPU in ticks of 10 ms, as the stat counts it.
     */
    private fun cpu(process: Long, vararg kinds: Pair<Long, Int>) =
        CpuSnapshot(process * 10_000_000, LongArray(kinds.size) { kinds[it].first * 10_000_000 }, IntArray(kinds.size) { kinds[it].second })

    private fun traffic(total: Long, vararg streams: Pair<Long, Long>, ended: Long = 0) = TrafficSnapshot(total, ended, linkedMapOf(*streams))

    /** As the Shield played the 4K remux of the baseline, over 10 s. */
    private val before = StreamCostSample(
        nanos = 5_000_000_000,
        traffic = traffic(1_000, 1L to 1_000L),
        // Two workers; among the rest, the main thread and one that ends within the window. The rest aren't read.
        cpu = cpu(1_000, 150L to 2, 10L to 1, 5L to 1, 0L to 1, 20L to 1, 30L to 1, 0L to 2),
        connections = ConnectionTotals(busyNanos = 10_000_000_000, bodies = 100, fetchedBytes = 500_000_000, open = 8),
        gc = GcStats(count = 100, timeMs = 5_000, blockingCount = 3, blockingTimeMs = 50, allocatedBytes = 1_000_000_000),
        tookNanos = 280_000,
    )
    private val after = StreamCostSample(
        nanos = 15_000_000_000,
        traffic = traffic(99_401_000, 1L to 99_401_000L),
        // A third worker started. The rest's 5 ticks (the main thread's 3, the ended thread's 2) are the process's beyond
        // the others'.
        cpu = cpu(2_950, 1_225L to 3, 600L to 1, 95L to 1, 30L to 1, 160L to 1, 50L to 1, 0L to 1),
        connections = ConnectionTotals(busyNanos = 36_000_000_000, bodies = 236, fetchedBytes = 599_000_000, open = 8),
        gc = GcStats(count = 116, timeMs = 6_203, blockingCount = 3, blockingTimeMs = 50, allocatedBytes = 1_348_000_000),
        tookNanos = 310_000,
    )

    @Test
    fun `the rates over a window`() {
        val cost = StreamCost.between(before, after)
        assertEquals(10.0, cost.seconds, 1e-9)
        assertEquals(99_400_000L, cost.streamedBytes)
        assertEquals(listOf(9_940_000.0), cost.streamRates)
        assertEquals(2.6, assertNotNull(cost.busyConnections), 1e-9)
        // 99 MB fetched in 26 s of connections busy.
        assertEquals(99_000_000 / 26.0, assertNotNull(cost.perConnection), 1e-6)
        assertEquals(1.95, assertNotNull(cost.cores), 1e-9)
    }

    @Test
    fun `the CPU per MB streamed by thread group, threads that ended counted with the rest`() {
        val cost = StreamCost.between(before, after)
        assertEquals(19_500 / 99.4, assertNotNull(cost.msPerMb), 1e-9)
        val byKind = ThreadKind.entries.associateWith { assertNotNull(cost.msPerMb(it)) * 99.4 / 10 }
        assertEquals(
            mapOf(
                ThreadKind.Workers to 1_075.0, ThreadKind.Loader to 590.0, ThreadKind.Playback to 90.0, ThreadKind.Codec to 30.0,
                ThreadKind.Gc to 140.0, ThreadKind.Jit to 20.0, ThreadKind.Rest to 5.0,
            ),
            byKind.mapValues { Math.round(it.value).toDouble() },
        )
        assertEquals(listOf(3, 1, 1, 1, 1, 1, 1), ThreadKind.entries.map(cost::threads))
        assertEquals(348 / 99.4, assertNotNull(cost.allocatedPerMb), 1e-9)
    }

    @Test
    fun `the GCs and the rates as figures, for the stream bench`() {
        val cost = StreamCost.between(before, after)
        assertEquals(16L, cost.gcCount)
        assertEquals(1_203L, cost.gcTimeMs)
        assertEquals(0L, cost.blockingGcCount)
        assertEquals(0L, cost.blockingGcTimeMs)
        assertEquals(9_940_000.0, cost.streamedPerSecond, 1e-6)
        assertEquals(9_900_000.0, assertNotNull(cost.fetchedPerSecond), 1e-6)
        // Unread: no figure, not naught.
        val bare = StreamCost.between(StreamCostSample(0, traffic(0)), StreamCostSample(2_000_000_000, traffic(3_000_000)))
        assertNull(bare.gcCount)
        assertNull(bare.gcTimeMs)
        assertNull(bare.blockingGcCount)
        assertNull(bare.blockingGcTimeMs)
        assertNull(bare.fetchedPerSecond)
        assertEquals(1_500_000.0, bare.streamedPerSecond, 1e-6)
    }

    @Test
    fun `the line says it all in one`() {
        assertEquals(
            "Stream cost over 10.0 s: streamed 9.94 MB/s (1 stream: 9.94); fetched 9.90 MB/s, 2.6 of 8 connections busy, " +
                "3.81 MB/s each, 0.19 s an article; CPU 1.95 cores, 196 ms/MB by thread group (threads): workers 108 (3), " +
                "loader 59 (1), playback 9 (1), codec 3 (1), GC 14 (1), JIT 2 (1), rest 1 (1); GC 16 times, 1203 ms, " +
                "0 blocking (0 ms); allocated 3.50 MB per MB; the sample took 0.31 ms of CPU",
            StreamCost.between(before, after).line(),
        )
    }

    @Test
    fun `streams that start and end within a window`() {
        val traffic = StreamTraffic()
        val first = traffic.open()
        first.add(10_000_000)
        val start = traffic.snapshot()
        first.add(20_000_000)
        first.close()
        val second = traffic.open()
        second.add(15_000_000)
        val third = traffic.open()
        third.add(5_000_000)
        val end = traffic.snapshot()
        val cost = StreamCost.between(StreamCostSample(0, start), StreamCostSample(10_000_000_000, end))
        assertEquals(40_000_000L, cost.streamedBytes)
        assertEquals(listOf(1_500_000.0, 500_000.0), cost.streamRates)
        assertEquals(1L, cost.endedStreams)
        assertEquals("Stream cost over 10.0 s: streamed 4.00 MB/s (2 streams: 1.50, 0.50; 1 ended); the sample took 0.00 ms of CPU", cost.line())
        // Those two ended too: none open.
        second.close()
        third.close()
        val closed = StreamCost.between(StreamCostSample(0, end), StreamCostSample(10_000_000_000, traffic.snapshot()))
        assertEquals("Stream cost over 10.0 s: nothing streamed (2 ended); the sample took 0.00 ms of CPU", closed.line())
    }

    @Test
    fun `a stream closed twice is counted once`() {
        val traffic = StreamTraffic()
        val stream = traffic.open()
        stream.add(1_000)
        stream.close()
        stream.close()
        val snapshot = traffic.snapshot()
        assertEquals(1_000L, snapshot.totalBytes)
        assertEquals(1L, snapshot.endedStreams)
        assertTrue(snapshot.streams.isEmpty())
    }

    @Test
    fun `with nothing streamed the CPU is in ms and nothing is per MB`() {
        val start = StreamCostSample(
            0, traffic(5_000, 1L to 5_000L),
            cpu = cpu(1_000, 100L to 1, 0L to 0, 0L to 0, 0L to 0, 0L to 0, 0L to 0, 0L to 1),
            gc = GcStats(count = 10, timeMs = 100, blockingCount = 0, blockingTimeMs = 0, allocatedBytes = 0),
        )
        val end = StreamCostSample(
            10_000_000_000, traffic(5_000, 1L to 5_000L),
            cpu = cpu(1_200, 220L to 1, 0L to 0, 0L to 0, 0L to 0, 0L to 0, 0L to 0, 0L to 1),
            gc = GcStats(count = 12, timeMs = 130, blockingCount = 1, blockingTimeMs = 12, allocatedBytes = 12_000_000),
            tookNanos = 400_000,
        )
        val cost = StreamCost.between(start, end)
        assertNull(cost.msPerMb)
        assertNull(cost.msPerMb(ThreadKind.Workers))
        assertNull(cost.allocatedPerMb)
        assertEquals(
            // The stream open but moving nothing, as one stalled.
            "Stream cost over 10.0 s: nothing streamed (1 stream: 0.00); CPU 0.20 cores by thread group in ms (threads): workers 1200 (1), " +
                "loader 0 (0), playback 0 (0), codec 0 (0), GC 0 (0), JIT 0 (0), rest 800 (1); GC 2 times, 30 ms, " +
                "1 blocking (12 ms); allocated 12.0 MB; the sample took 0.40 ms of CPU",
            cost.line(),
        )
    }

    @Test
    fun `without connections, GC counters or proc the line says what it has`() {
        val cost = StreamCost.between(StreamCostSample(0, traffic(0, 1L to 0L)), StreamCostSample(4_000_000_000, traffic(4_000_000, 1L to 4_000_000L), tookNanos = 50_000))
        assertNull(cost.cores)
        assertNull(cost.busyConnections)
        assertEquals("Stream cost over 4.0 s: streamed 1.00 MB/s (1 stream: 1.00); the sample took 0.05 ms of CPU", cost.line())
    }

    @Test
    fun `connections that fetched nothing are said to be idle`() {
        val idle = ConnectionTotals(busyNanos = 1_000, bodies = 1, fetchedBytes = 700_000, open = 6)
        val cost = StreamCost.between(
            StreamCostSample(0, traffic(0), connections = idle),
            StreamCostSample(10_000_000_000, traffic(0), connections = idle),
        )
        assertEquals(0.0, cost.busyConnections)
        assertNull(cost.perConnection)
        assertEquals("Stream cost over 10.0 s: nothing streamed; fetched 0.00 MB/s, 0.0 of 6 connections busy; the sample took 0.00 ms of CPU", cost.line())
    }

    /**
     * A debrid play's stream (L208) reads over HTTP with no provider connection open: the line leaves the connections out,
     * rather than "0.0 of 0 connections busy", and says what ones closed within the window fetched.
     */
    @Test
    fun `with no connection open the line leaves the connections out`() {
        val none = ConnectionTotals(busyNanos = 0, bodies = 0, fetchedBytes = 0, open = 0)
        val http = StreamCost.between(
            StreamCostSample(0, traffic(0, 1L to 0L), connections = none),
            StreamCostSample(10_000_000_000, traffic(25_000_000, 1L to 25_000_000L), connections = none),
        )
        assertEquals("Stream cost over 10.0 s: streamed 2.50 MB/s (1 stream: 2.50); the sample took 0.00 ms of CPU", http.line())
        // A Usenet stream's connections that closed within the window: what they fetched, no count of them busy.
        val closed = StreamCost.between(
            StreamCostSample(0, traffic(0, 1L to 0L), connections = ConnectionTotals(busyNanos = 0, bodies = 0, fetchedBytes = 0, open = 4)),
            StreamCostSample(10_000_000_000, traffic(10_000_000, 1L to 10_000_000L), connections = ConnectionTotals(busyNanos = 5_000_000_000, bodies = 20, fetchedBytes = 10_000_000, open = 0)),
        )
        assertEquals("Stream cost over 10.0 s: streamed 1.00 MB/s (1 stream: 1.00); fetched 1.00 MB/s; the sample took 0.00 ms of CPU", closed.line())
    }

    @Test
    fun `a sample is timed by the CPU it cost the sampling thread`() {
        var cpu = 1_000L
        val sampler = StreamCostSampler(
            StreamTraffic(), ThreadCpuReader(File("/no/such/proc")),
            nanoTime = { 7_000_000_000 },
            threadCpuNanos = { cpu.also { cpu += 350_000 } },
        )
        val sample = sampler.sample()
        assertEquals(350_000L, sample.tookNanos)
        assertEquals(7_000_000_000L, sample.nanos)
    }

    @Test
    fun `attaching the connection meter, at a run's first sample, is timed with it`() {
        val global = CompositeMeterRegistry()
        val meter = ConnectionMeter(global)
        // The sampling thread's CPU, as if the attach took a millisecond of it.
        val sampler = StreamCostSampler(
            StreamTraffic(), ThreadCpuReader(File("/no/such/proc")), meter,
            threadCpuNanos = { if (global.registries.isEmpty()) 0 else 1_000_000 },
        )
        try {
            assertEquals(1_000_000L, sampler.sample().tookNanos)
            // Attached already: the next has nothing of it.
            assertEquals(0L, sampler.sample().tookNanos)
        } finally {
            meter.close()
        }
    }

    // The monitor, on virtual time: a sample says how many bytes the one stream had moved by then, 1 MB a second.

    private val streamed = StreamTraffic()
    private val stream = streamed.open()

    private fun TestScope.sampler(fail: (Int) -> Boolean = { false }): () -> StreamCostSample {
        var samples = 0
        val sampler = StreamCostSampler(streamed, ThreadCpuReader(File("/no/such/proc")), nanoTime = { testScheduler.currentTime * 1_000_000 }, threadCpuNanos = { 0 })
        return {
            if (fail(++samples)) throw IOException("proc went away")
            sampler.sample()
        }
    }

    /** The stream moves 1 MB a second, at each half second: never as a sample is taken. */
    private fun TestScope.feed() = backgroundScope.launch {
        delay(500)
        while (true) {
            stream.add(1_000_000)
            delay(1_000)
        }
    }

    private fun TestScope.monitor(lines: MutableList<String>): StreamCostMonitor {
        feed()
        return StreamCostMonitor(backgroundScope, sampler(), log = { lines += it })
    }

    @Test
    fun `a line every 10 s while a stream plays, and none once it stops`() {
        runTest {
            val lines = mutableListOf<String>()
            val monitor = monitor(lines)
            monitor.playing(true)
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(
                listOf("Stream cost over 10.0 s: streamed 1.00 MB/s (1 stream: 1.00); the sample took 0.00 ms of CPU (the first, opening its files, 0.00 ms)"),
                lines,
            )
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(2, lines.size)
            assertEquals("Stream cost over 10.0 s: streamed 1.00 MB/s (1 stream: 1.00); the sample took 0.00 ms of CPU", lines[1])
            assertTrue(monitor.running)
            monitor.playing(false)
            assertFalse(monitor.running)
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertEquals(2, lines.size)
        }
    }

    @Test
    fun `told again that a stream plays, it keeps to one line every 10 s`() {
        runTest {
            val lines = mutableListOf<String>()
            val monitor = monitor(lines)
            monitor.playing(true)
            testScheduler.advanceTimeBy(3_000)
            monitor.playing(true)
            monitor.playing(true)
            testScheduler.advanceTimeBy(7_000)
            testScheduler.runCurrent()
            assertEquals(1, lines.size)
            monitor.playing(false)
        }
    }

    @Test
    fun `started again, it measures from then`() {
        runTest {
            val lines = mutableListOf<String>()
            val monitor = monitor(lines)
            monitor.playing(true)
            testScheduler.advanceTimeBy(15_000)
            monitor.playing(false)
            testScheduler.advanceTimeBy(25_000)
            monitor.playing(true)
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(2, lines.size)
            assertEquals("Stream cost over 10.0 s: streamed 1.00 MB/s (1 stream: 1.00); the sample took 0.00 ms of CPU (the first, opening its files, 0.00 ms)", lines[1])
            monitor.playing(false)
        }
    }

    @Test
    fun `a run's first line says what its first sample took too, which opened the files the others read through`() {
        runTest {
            val lines = mutableListOf<String>()
            val sample = sampler()
            // What each sample cost, in turn: each run's first opens the files.
            val took = ArrayDeque(listOf(2_800_000L, 310_000L, 300_000L, 3_100_000L, 320_000L))
            feed()
            val monitor = StreamCostMonitor(backgroundScope, { sample().let { StreamCostSample(it.nanos, it.traffic, tookNanos = took.removeFirst()) } }, log = { lines += it })
            monitor.playing(true)
            testScheduler.advanceTimeBy(20_000)
            testScheduler.runCurrent()
            monitor.playing(false)
            monitor.playing(true)
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(
                listOf(
                    "the sample took 0.31 ms of CPU (the first, opening its files, 2.80 ms)",
                    "the sample took 0.30 ms of CPU",
                    "the sample took 0.32 ms of CPU (the first, opening its files, 3.10 ms)",
                ),
                lines.map { it.substringAfter("; ") },
            )
            monitor.playing(false)
        }
    }

    @Test
    fun `a run of lines is done with the sampler before the next run takes it`() {
        runTest {
            val events = mutableListOf<String>()
            val sampler = sampler()
            val monitor = StreamCostMonitor(backgroundScope, { sampler().also { events += "sample" } }, log = { events += "line" }, done = { events += "done" })
            feed()
            monitor.playing(true)
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            // Stopped and started again before the stopped run has had its last say.
            monitor.playing(false)
            monitor.playing(true)
            testScheduler.runCurrent()
            assertEquals(listOf("sample", "sample", "line", "done", "sample"), events)
            monitor.playing(false)
            testScheduler.runCurrent()
            assertEquals("done", events.last())
        }
    }

    @Test
    fun `a sample that fails ends the line, not the app`() {
        runTest {
            val lines = mutableListOf<String>()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val other = scope.launch { awaitCancellation() }
            feed()
            val monitor = StreamCostMonitor(scope, sampler(fail = { it == 3 }), log = { lines += it })
            monitor.playing(true)
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()
            assertEquals(1, lines.size)
            assertFalse(monitor.running)
            assertTrue(scope.isActive && other.isActive)
            // Told a stream plays again, it starts over.
            monitor.playing(true)
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertEquals(2, lines.size)
            monitor.playing(false)
            other.cancel()
        }
    }
}

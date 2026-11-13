package io.skjaere.usenet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** ART's GC counters so far: collections and their time, those a thread waited for, and the bytes ever allocated. */
class GcStats(val count: Long, val timeMs: Long, val blockingCount: Long, val blockingTimeMs: Long, val allocatedBytes: Long)

/**
 * What the stream-cost line compares, as it was at [nanos] (System.nanoTime): the streams' bytes, the threads' CPU, the
 * connections' work and ART's GC, each null where it couldn't be read; [tookNanos] is the CPU reading them cost.
 */
class StreamCostSample(
    val nanos: Long,
    val traffic: TrafficSnapshot,
    val cpu: CpuSnapshot? = null,
    val connections: ConnectionTotals? = null,
    val gc: GcStats? = null,
    val tookNanos: Long = 0,
)

/**
 * What streaming cost over a window between two samples ([between]), as the stream-cost line says it ([line]): the rate
 * of each stream; the providers' bytes and how many connections were busy fetching them, and at what rate each; the
 * process's CPU per MB streamed, by thread group ([ThreadKind]); ART's GCs and what was allocated per MB. A MB is
 * 1,000,000 bytes, as the app's sizes are.
 */
class StreamCost private constructor(
    val seconds: Double,
    val streamedBytes: Long,
    /** Bytes a second of each stream open at the window's end, oldest first. */
    val streamRates: List<Double>,
    /** Streams that ended within the window: their bytes are in [streamedBytes]. */
    val endedStreams: Long,
    private val fetchedBytes: Long?,
    private val busySeconds: Double?,
    private val bodies: Long?,
    private val openConnections: Int?,
    private val cpuMs: LongArray?,
    private val threadCounts: IntArray?,
    private val gc: GcDelta?,
    private val tookNanos: Long,
    /** What the run's first sample took, where the window starts at it ([between]'s opening). */
    private val openingNanos: Long?,
) {
    private class GcDelta(val count: Long, val timeMs: Long, val blockingCount: Long, val blockingTimeMs: Long, val allocatedBytes: Long)

    private val mb = streamedBytes / 1e6

    /** Connections busy fetching, on average over the window. */
    val busyConnections: Double? get() = busySeconds?.let { it / seconds }

    /** The bytes a second a connection fetched while busy; null when none was. */
    val perConnection: Double? get() = busySeconds?.takeIf { it > 0 }?.let { fetchedBytes!! / it }

    /** The cores the process kept busy, on average. */
    val cores: Double? get() = cpuMs?.let { it.sum() / 1e3 / seconds }

    /** The process's CPU in ms for each MB streamed; null when nothing was streamed. */
    val msPerMb: Double? get() = cpuMs?.takeIf { mb > 0 }?.let { it.sum() / mb }

    /** What the process allocated for each MB streamed, in MB. */
    val allocatedPerMb: Double? get() = gc?.takeIf { mb > 0 }?.let { it.allocatedBytes / 1e6 / mb }

    /** The CPU in ms of [kind]'s threads for each MB streamed; null when nothing was streamed. */
    fun msPerMb(kind: ThreadKind): Double? = cpuMs?.takeIf { mb > 0 }?.let { it[kind.ordinal] / mb }

    /** The CPU in ms of [kind]'s threads over the window. */
    fun ms(kind: ThreadKind): Long? = cpuMs?.get(kind.ordinal)

    /** [kind]'s threads alive at the window's end. */
    fun threads(kind: ThreadKind): Int = threadCounts?.get(kind.ordinal) ?: 0

    /** The bytes a second the streams handed their readers. */
    val streamedPerSecond: Double get() = streamedBytes / seconds

    /** The decoded bytes a second fetched from the providers; null where the connections weren't read. */
    val fetchedPerSecond: Double? get() = fetchedBytes?.let { it / seconds }

    /** ART's collections within the window and their time in ms; null where its counters weren't read. */
    val gcCount: Long? get() = gc?.count
    val gcTimeMs: Long? get() = gc?.timeMs

    /** The collections a thread waited for (an allocation that couldn't go on), and their time in ms. */
    val blockingGcCount: Long? get() = gc?.blockingCount
    val blockingGcTimeMs: Long? get() = gc?.blockingTimeMs

    /**
     * "Stream cost over 10.0 s: streamed 9.94 MB/s (1 stream: 9.94); fetched 9.90 MB/s, 2.6 of 8 connections busy,
     * 3.81 MB/s each, 0.19 s an article; CPU 1.95 cores, 196 ms/MB by thread group (threads): workers 108 (28), …;
     * GC 16 times, 1203 ms, 0 blocking (0 ms); allocated 3.50 MB per MB; the sample took 0.31 ms of CPU", and a run's
     * first adds "(the first, opening its files, 2.80 ms)": numbers and fixed words only, written the same in every locale.
     * With no connection open at the window's end (a debrid play) no connection is said busy (L208).
     */
    fun line(): String = buildString {
        append("Stream cost over ").fixed(seconds, 1).append(" s: ")
        if (streamedBytes == 0L) append("nothing streamed") else append("streamed ").fixed(streamedBytes / seconds / 1e6, 2).append(" MB/s")
        // The streams open at the end, each at its rate (a stalled one at naught), and those that ended.
        if (streamRates.isNotEmpty() || endedStreams > 0) {
            append(" (")
            if (streamRates.isNotEmpty()) {
                append(streamRates.size).append(if (streamRates.size == 1) " stream" else " streams")
                streamRates.forEachIndexed { i, rate -> append(if (i == 0) ": " else ", ").fixed(rate / 1e6, 2) }
                if (endedStreams > 0) append("; ")
            }
            if (endedStreams > 0) append(endedStreams).append(" ended")
            append(")")
        }
        // With no connection open (a debrid play's stream is HTTP's), none is said busy: only what any closed meanwhile fetched.
        if (busySeconds != null && openConnections == 0) {
            if (fetchedBytes!! > 0) append("; fetched ").fixed(fetchedBytes / seconds / 1e6, 2).append(" MB/s")
        } else if (busySeconds != null) {
            append("; fetched ").fixed(fetchedBytes!! / seconds / 1e6, 2).append(" MB/s, ")
            fixed(busySeconds / seconds, 1).append(" of ").append(openConnections).append(" connections busy")
            perConnection?.let { append(", ").fixed(it / 1e6, 2).append(" MB/s each") }
            if (busySeconds > 0 && bodies!! > 0) append(", ").fixed(busySeconds / bodies, 2).append(" s an article")
        }
        if (cpuMs != null) {
            append("; CPU ").fixed(cores!!, 2).append(" cores")
            if (mb > 0) append(", ").fixed(msPerMb!!, 0).append(" ms/MB by thread group (threads): ")
            else append(" by thread group in ms (threads): ")
            ThreadKind.entries.forEachIndexed { i, kind ->
                if (i > 0) append(", ")
                append(kind.label).append(' ').fixed(msPerMb(kind) ?: cpuMs[kind.ordinal].toDouble(), 0)
                append(" (").append(threads(kind)).append(')')
            }
        }
        if (gc != null) {
            append("; GC ").append(gc.count).append(" times, ").append(gc.timeMs).append(" ms, ")
            append(gc.blockingCount).append(" blocking (").append(gc.blockingTimeMs).append(" ms); allocated ")
            if (mb > 0) fixed(allocatedPerMb!!, 2).append(" MB per MB") else fixed(gc.allocatedBytes / 1e6, 1).append(" MB")
        }
        append("; the sample took ").fixed(tookNanos / 1e6, 2).append(" ms of CPU")
        if (openingNanos != null) append(" (the first, opening its files, ").fixed(openingNanos / 1e6, 2).append(" ms)")
    }

    companion object {
        private val scales = longArrayOf(1, 10, 100)

        /**
         * [x] with [places] decimals (none to two), rounded half up, as Locale.ROOT writes it: String.format took over a
         * millisecond for the line's dozen numbers on the emulator.
         */
        private fun StringBuilder.fixed(x: Double, places: Int): StringBuilder {
            if (!x.isFinite()) return append(x)
            val scale = scales[places]
            val scaled = Math.round(Math.abs(x) * scale)
            if (x < 0 && scaled > 0) append('-')
            append(scaled / scale)
            if (places > 0) {
                append('.')
                val fraction = scaled % scale
                var digit = scale / 10
                while (digit > 1 && fraction < digit) {
                    append('0')
                    digit /= 10
                }
                append(fraction)
            }
            return this
        }

        /**
         * The window from [before] to [after]. [opening]: [before] is its run's first sample, which opened the files the
         * later ones read through and attached the connection meter, and costs the most: the line says what it took too.
         */
        fun between(before: StreamCostSample, after: StreamCostSample, opening: Boolean = false): StreamCost {
            val seconds = (after.nanos - before.nanos) / 1e9
            val traffic = after.traffic
            val streamed = (traffic.totalBytes - before.traffic.totalBytes).coerceAtLeast(0)
            // A stream that started within the window moved all it has since.
            val rates = traffic.streams.map { (id, bytes) -> (bytes - (before.traffic.streams[id] ?: 0)) / seconds }
            val connections = after.connections?.let { now -> before.connections?.let { was -> now to was } }
            val cpu = after.cpu?.let { now -> before.cpu?.let { was -> cpuByKind(was, now) } }
            val gc = after.gc?.let { now ->
                before.gc?.let { was ->
                    GcDelta(
                        now.count - was.count, now.timeMs - was.timeMs, now.blockingCount - was.blockingCount,
                        now.blockingTimeMs - was.blockingTimeMs, now.allocatedBytes - was.allocatedBytes,
                    )
                }
            }
            return StreamCost(
                seconds, streamed, rates, traffic.endedStreams - before.traffic.endedStreams,
                fetchedBytes = connections?.let { (now, was) -> now.fetchedBytes - was.fetchedBytes },
                busySeconds = connections?.let { (now, was) -> (now.busyNanos - was.busyNanos) / 1e9 },
                bodies = connections?.let { (now, was) -> now.bodies - was.bodies },
                openConnections = connections?.first?.open,
                cpuMs = cpu?.first, threadCounts = cpu?.second, gc = gc, tookNanos = after.tookNanos,
                openingNanos = before.tookNanos.takeIf { opening },
            )
        }

        /**
         * Each kind's CPU in ms over the window, and its threads alive at the end. The rest's is what the process used
         * beyond the other kinds' threads: its own threads', and the threads' that ended within the window.
         */
        private fun cpuByKind(before: CpuSnapshot, after: CpuSnapshot): Pair<LongArray, IntArray> {
            val rest = ThreadKind.Rest.ordinal
            val nanos = LongArray(ThreadKind.entries.size) { if (it == rest) 0 else (after.kindNanos[it] - before.kindNanos[it]).coerceAtLeast(0) }
            nanos[rest] = (after.processNanos - before.processNanos - nanos.sum()).coerceAtLeast(0)
            return LongArray(nanos.size) { (nanos[it] + 500_000) / 1_000_000 } to after.threads
        }
    }
}

/**
 * Takes the stream-cost line's samples: the streams' bytes ([traffic]), the threads' CPU ([cpu]), the connections' work
 * ([connections], attached at the first sample) and ART's GC counters ([gc]), as at [nanoTime]. What a sample cost is
 * the CPU time [threadCpuNanos] says the sampling thread spent on it: a sample's wall time, on a box busy decoding the
 * picture, says more of the box than of the sample. [close] lets go of the files the CPU's reads keep open and detaches
 * the connection meter, till the next sample.
 */
class StreamCostSampler(
    private val traffic: StreamTraffic,
    private val cpu: ThreadCpuReader = ThreadCpuReader(),
    private val connections: ConnectionMeter? = null,
    private val gc: () -> GcStats? = { null },
    private val nanoTime: () -> Long = System::nanoTime,
    private val threadCpuNanos: () -> Long,
) : AutoCloseable {
    override fun close() {
        cpu.close()
        connections?.detach()
    }

    fun sample(): StreamCostSample {
        val start = threadCpuNanos()
        // Timed with the rest: a run's first sample attaches it, which makes a meter for each the libraries keep.
        connections?.attach()
        val threads = cpu.read()
        val streams = traffic.snapshot()
        val work = connections?.read()
        val collector = gc()
        val took = threadCpuNanos() - start
        return StreamCostSample(nanoTime(), streams, threads, work, collector, tookNanos = took)
    }
}

/**
 * Logs what streaming costs ([StreamCost]) every [interval] while a stream plays ([playing]), for the Logs screen (which
 * keeps the last 10 minutes of them apart from its other lines) and logcat: a Shield run then needs nothing but the log.
 * At INFO, so without Detailed logging too, which a run measuring the CPU must leave off: its line for each article cost
 * the Shield's logd 40 % of a core. This one costs nothing to speak of: a sample took about half a millisecond of CPU in
 * a play on the emulator (the line it makes another tenth), 0.005 % of a core at one every 10 s, and one line every 10 s
 * is nothing to logd. A run's first sample opens each thread's files and attaches the connection meter: 4.5 to 8 ms on
 * the emulator, once a play or resume, and the run's first line says what it took. A failing sample ends the lines with
 * a warning; the next play starts them again. [done] follows the last sample of each run of lines (the sampler's files
 * go).
 */
class StreamCostMonitor(
    private val scope: CoroutineScope,
    private val sample: () -> StreamCostSample,
    private val interval: Duration = INTERVAL,
    private val log: (String) -> Unit = { logger.info(it) },
    private val done: () -> Unit = {},
) {
    private var job: Job? = null

    /** Whether lines are being logged. */
    val running: Boolean
        @Synchronized get() = job?.isActive == true

    /**
     * A stream plays, or is about to, while [on]: the first line comes [interval] after it starts and measures from then;
     * lines stop when it does. Told again while on, it goes on as it was.
     */
    @Synchronized
    fun playing(on: Boolean) {
        if (!on) {
            job?.cancel()
            return
        }
        if (job?.isActive == true) return
        val stopped = job
        job = scope.launch {
            // The sampler is one run's at a time: one just stopped is done with it first.
            stopped?.join()
            try {
                var before = sample()
                var opening = true
                while (true) {
                    delay(interval)
                    val after = sample()
                    // Stopped while it sampled: no line after the stream.
                    ensureActive()
                    log(StreamCost.between(before, after, opening).line())
                    opening = false
                    before = after
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Stopped the stream-cost line", e)
            } finally {
                done()
            }
        }
    }

    companion object {
        val INTERVAL = 10.seconds
        private val logger = LoggerFactory.getLogger(StreamCost::class.java)
    }
}

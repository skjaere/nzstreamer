package io.skjaere.usenet.core

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the NNTP connections had done by a moment, all providers together: [busyNanos] the time they spent on articles,
 * from each BODY command to its last byte (one that timed out counts its timeout), over [bodies] articles; [fetchedBytes]
 * the decoded bytes fetched from the providers (not the segment cache's); [open] the connections open or opening then.
 */
class ConnectionTotals(val busyNanos: Long, val bodies: Long, val fetchedBytes: Long, val open: Int)

/**
 * Reads [ConnectionTotals] from the meters ktor-nntp-client and nzb-streamer keep in Micrometer's [global] registry,
 * which keeps nothing until a registry is added to it. [attach] adds one of its own that takes only the three meters it
 * reads, and keeps no histogram for them: every other meter the libraries record, many per command, stays a no-op, and
 * a BODY's time is a count and a sum. Attached, a command's timer cost about 1.8 µs on the emulator, 1.2 µs more
 * than alone (0.6 µs), so it is attached only while a stream plays: [detach] takes it away again, and what it counted
 * with it.
 */
class ConnectionMeter(private val global: CompositeMeterRegistry = Metrics.globalRegistry) : AutoCloseable {
    internal val registry: MeterRegistry = SimpleMeterRegistry().apply {
        config().meterFilter(
            object : MeterFilter {
                override fun accept(id: Meter.Id) = if (id.name in kept) MeterFilterReply.ACCEPT else MeterFilterReply.DENY

                override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                    noHistogram.merge(config)
            },
        )
    }
    private val attached = AtomicBoolean()

    internal fun meters(): List<Meter> = registry.meters

    /** Starts counting, if it isn't: the pools and connections already open are counted from now on too. */
    fun attach() {
        if (attached.compareAndSet(false, true)) global.add(registry)
    }

    /**
     * Stops counting, if it is. What it counted goes too: a gauge it kept would go on reading the pool it was made for,
     * closed meanwhile, rather than the one opened since under the same name.
     */
    fun detach() {
        if (!attached.compareAndSet(true, false)) return
        global.remove(registry)
        registry.clear()
    }

    /** What the connections had done by now, since the attach; nothing while detached. One pass over its few meters. */
    fun read(): ConnectionTotals {
        var busyNanos = 0.0
        var bodies = 0L
        var fetched = 0.0
        var open = 0.0
        for (meter in registry.meters) {
            when {
                meter is Timer && meter.id.name == BODY -> {
                    busyNanos += meter.totalTime(TimeUnit.NANOSECONDS)
                    bodies += meter.count()
                }
                meter is Counter && meter.id.name == FETCHED -> fetched += meter.count()
                // A pool gone (its gauge's pool collected) reads NaN.
                meter is Gauge && meter.id.name == POOL_SIZE -> meter.value().takeUnless(Double::isNaN)?.let { open += it }
            }
        }
        return ConnectionTotals(busyNanos.toLong(), bodies, fetched.toLong(), open.toInt())
    }

    override fun close() {
        detach()
        registry.close()
    }

    private companion object {
        /** NntpConnection's per-article timer by pool, which nzb-streamer's timed-out fetches record into too. */
        const val BODY = "nntp.body.duration"
        const val FETCHED = "nzb.segments.bytes"
        const val POOL_SIZE = "nntp.pool.size"
        val kept = setOf(BODY, FETCHED, POOL_SIZE)

        val noHistogram: DistributionStatisticConfig = DistributionStatisticConfig.builder()
            .percentilesHistogram(false)
            .percentiles()
            .serviceLevelObjectives()
            .build()
    }
}

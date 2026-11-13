package io.skjaere.usenet.core

import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stream-cost line's connections: what the libraries' own meters say the NNTP connections did, through the real
 * engine and opener against a [FakeNntpServer]. Counting starts as the meter is attached, with the engine's pools
 * already open, as a play's first line finds them.
 */
class ConnectionMeterTest {
    private val clip = TestClip()
    private val server = clip.server()
    private val engine = UsenetEngine()
    private val meter = ConnectionMeter()

    @AfterTest
    fun tearDown() {
        meter.close()
        engine.close()
        server.close()
    }

    private suspend fun streamClip() {
        val (received, cause) = withTimeout(30_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }
        assertNull(cause, "the stream ended with $cause")
        assertContentEquals(clip.data, received)
    }

    @Test
    fun `the connections' work is counted from the attach on`() {
        runBlocking<Unit> {
            engine.configure(EngineSettings(listOf(server.provider())))
            // Before the attach: not counted.
            streamClip()
            meter.attach()
            val before = meter.read()
            streamClip()
            val after = meter.read()
            assertEquals(clip.segments.size.toLong(), after.bodies - before.bodies)
            assertEquals(clip.data.size.toLong(), after.fetchedBytes - before.fetchedBytes)
            assertTrue(after.busyNanos > before.busyNanos, "busy ${before.busyNanos} -> ${after.busyNanos}")
            assertTrue(after.open >= 1, "open ${after.open}")
        }
    }

    @Test
    fun `it keeps the few meters it reads and leaves every other one a no-op`() {
        runBlocking<Unit> {
            engine.configure(EngineSettings(listOf(server.provider())))
            meter.attach()
            meter.attach()
            streamClip()
            val kept = meter.meters().map { it.id.name }.toSet()
            assertEquals(setOf("nntp.body.duration", "nzb.segments.bytes", "nntp.pool.size"), kept)
            // Its timers keep no histogram: a BODY's record is a count and a sum.
            meter.meters().filterIsInstance<io.micrometer.core.instrument.Timer>().forEach { timer ->
                assertTrue(timer.takeSnapshot().histogramCounts().isEmpty(), "${timer.id} keeps a histogram")
            }
            assertEquals(1, Metrics.globalRegistry.registries.count { it === meter.registry })
        }
    }

    @Test
    fun `detached, it counts nothing, and attached again it counts afresh`() {
        runBlocking<Unit> {
            engine.configure(EngineSettings(listOf(server.provider())))
            meter.attach()
            streamClip()
            meter.detach()
            assertTrue(Metrics.globalRegistry.registries.none { it === meter.registry })
            streamClip()
            assertEquals(0L, meter.read().bodies)
            meter.attach()
            val before = meter.read()
            streamClip()
            val after = meter.read()
            assertEquals(clip.segments.size.toLong(), after.bodies - before.bodies)
            assertTrue(after.open >= 1, "open ${after.open}")
        }
    }

    @Test
    fun `closed, it stops counting`() {
        runBlocking<Unit> {
            engine.configure(EngineSettings(listOf(server.provider())))
            meter.attach()
            meter.close()
            assertTrue(Metrics.globalRegistry.registries.none { it === meter.registry })
        }
    }
}

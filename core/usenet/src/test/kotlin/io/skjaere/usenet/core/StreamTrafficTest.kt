package io.skjaere.usenet.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L471: whether something streams here, for a debrid download to wait while it does ([StreamTraffic.streaming]): true
 * while any stream's meter is open, from the first open until the last one ends, set with the counts under one lock, so
 * the streams' threads opening and ending at once leave it as the meters are.
 */
class StreamTrafficTest {
    @Test
    fun `nothing streams with no meter open, a stream does from the first open, and none once the last ends`() {
        val traffic = StreamTraffic()
        assertFalse(traffic.streaming.value)
        val first = traffic.open()
        assertTrue(traffic.streaming.value)
        val second = traffic.open()
        first.add(1_000)
        first.close()
        assertTrue(traffic.streaming.value, "one still open")
        second.close()
        assertFalse(traffic.streaming.value)
        // And again for the next stream: the counts as before.
        traffic.open().close()
        assertFalse(traffic.streaming.value)
        assertEquals(3L, traffic.snapshot().endedStreams)
        assertEquals(1_000L, traffic.snapshot().totalBytes)
    }

    @Test
    fun `a meter closed twice changes nothing`() {
        val traffic = StreamTraffic()
        val ended = traffic.open()
        val playing = traffic.open()
        ended.close()
        ended.close()
        assertTrue(traffic.streaming.value, "the second close of one ended took the other's stream away")
        playing.close()
        assertFalse(traffic.streaming.value)
        playing.close()
        ended.close()
        assertFalse(traffic.streaming.value)
        assertEquals(2L, traffic.snapshot().endedStreams)
    }

    /**
     * Streams open and end on their own threads (the player's loaders, the cast server's): however their opens and ends
     * fall, the signal follows the meters, true while one is open and false once none is.
     */
    @Test
    fun `meters opened and closed on several threads leave it as the meters are`() {
        val traffic = StreamTraffic()
        val threads = 8
        val holding = CyclicBarrier(threads + 1)
        val letGo = CyclicBarrier(threads + 1)
        val workers = List(threads) {
            thread(name = "stream-$it") {
                repeat(5_000) { traffic.open().close() }
                // Each ends holding one open, until the test has looked.
                val held = traffic.open()
                holding.await(10, TimeUnit.SECONDS)
                letGo.await(10, TimeUnit.SECONDS)
                held.close()
            }
        }
        holding.await(10, TimeUnit.SECONDS)
        assertTrue(traffic.streaming.value)
        assertEquals(threads, traffic.snapshot().streams.size)
        letGo.await(10, TimeUnit.SECONDS)
        workers.forEach { it.join(10_000) }
        assertFalse(traffic.streaming.value)
        assertEquals(threads * 5_001L, traffic.snapshot().endedStreams)
    }

    /** A watcher of the flow is told each change, and no more: a second stream beside the first changes nothing. */
    @Test
    fun `a watcher of the flow is told each change`() = runTest {
        val traffic = StreamTraffic()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { traffic.streaming.collect { seen += it } }
        val first = traffic.open()
        val second = traffic.open()
        first.close()
        second.close()
        traffic.open().close()
        assertEquals(listOf(false, true, false, true, false), seen)
    }
}

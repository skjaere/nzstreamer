package io.skjaere.usenet.app.refresh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RateGateTest {
    /** What TmdbClient's beforeNetwork does: wait at the gate when the coroutine runs under one. */
    private suspend fun beforeNetwork() = currentCoroutineContext()[RateGate]?.pass()

    @Test
    fun `passes are spaced, first come first through`() = runTest {
        val gate = RateGate(50) { testScheduler.currentTime }
        val passed = mutableListOf<Pair<Int, Long>>()
        (1..5).forEach { n -> launch(gate) { beforeNetwork(); passed += n to testScheduler.currentTime } }
        advanceUntilIdle()
        assertEquals(listOf(1 to 0L, 2 to 50L, 3 to 100L, 4 to 150L, 5 to 200L), passed)
    }

    /** The first pass on a clock that has run a while (System.nanoTime is anywhere): at once. */
    @Test
    fun `the first pass goes at once whatever the clock says`() = runTest {
        listOf(10_000L, Long.MAX_VALUE / 2, -5_000L).forEach { offset ->
            val gate = RateGate(50) { testScheduler.currentTime + offset }
            val before = testScheduler.currentTime
            withContext(gate) { beforeNetwork() }
            assertEquals(before, testScheduler.currentTime, "clock at $offset")
        }
    }

    @Test
    fun `a pass after a quiet spell goes at once`() = runTest {
        val gate = RateGate(50) { testScheduler.currentTime }
        withContext(gate) { beforeNetwork() }
        testScheduler.advanceTimeBy(500)
        val before = testScheduler.currentTime
        withContext(gate) { beforeNetwork() }
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `only coroutines running under the gate wait at it`() = runTest {
        val gate = RateGate(50) { testScheduler.currentTime }
        val times = mutableListOf<Long>()
        repeat(3) { launch(gate) { beforeNetwork() } }
        repeat(3) { launch { beforeNetwork(); times += testScheduler.currentTime } }
        advanceUntilIdle()
        assertEquals(listOf(0L, 0L, 0L), times)
    }
}

package io.skjaere.usenet.debrid

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class ServiceRateLimitTest {

    @Test
    fun `calls go one every interval`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }
        val times = (1..4).map { limit.acquire(); testScheduler.currentTime }
        assertEquals(listOf(0L, 250L, 500L, 750L), times)
    }

    @Test
    fun `callers at once wait their turn`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }
        val times = mutableListOf<Long>()
        repeat(3) { launch { limit.acquire(); times += testScheduler.currentTime } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L, 250L, 500L), times)
    }

    @Test
    fun `a call long after the last goes at once`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(500)) { testScheduler.currentTime }
        limit.acquire()
        advanceTimeBy(5_000)
        limit.acquire()
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun `a wait the service asked for holds every call until it ends`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }
        limit.acquire()
        limit.holdFor(Duration.ofSeconds(30))
        limit.acquire()
        assertEquals(30_000, testScheduler.currentTime, "the Retry-After's 30 s")
        limit.acquire()
        assertEquals(30_250, testScheduler.currentTime, "then the interval again")
    }

    @Test
    fun `a shorter wait doesn't cut a longer one short`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }
        limit.holdFor(Duration.ofSeconds(10))
        limit.holdFor(Duration.ofSeconds(2))
        limit.acquire()
        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun `a wait asked for while a caller waits holds that caller too`() = runTest {
        val limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }
        limit.acquire()
        var at = -1L
        launch { limit.acquire(); at = testScheduler.currentTime }
        advanceTimeBy(100)
        limit.holdFor(Duration.ofSeconds(5))
        testScheduler.advanceUntilIdle()
        assertEquals(5_100, at)
    }
}

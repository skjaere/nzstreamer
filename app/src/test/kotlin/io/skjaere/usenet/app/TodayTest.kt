package io.skjaere.usenet.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOn
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TodayTest {
    /** Wall time that moves with virtual time, plus [skipped] ms that passed without it (deep sleep). */
    private class TestClock(private val scheduler: TestCoroutineScheduler, private val start: Instant, private val zone: ZoneId) : Clock() {
        var skipped = 0L
        override fun instant(): Instant = start.plusMillis(scheduler.currentTime + skipped)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `a new date at local midnight`() = runTest {
        // 23:59 in Paris, 21:59 UTC.
        val clock = TestClock(testScheduler, Instant.parse("2026-10-14T21:59:00Z"), paris)
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { Today(clock, MutableStateFlow(true)).dates.flowOn(StandardTestDispatcher(testScheduler)).collect { dates += it } }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), dates)
        advanceTimeBy(59_000)
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), dates)
        advanceTimeBy(1_100)
        assertEquals(listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)), dates)
        // Every midnight after, once.
        advanceTimeBy(24 * 3_600_000L)
        assertEquals(LocalDate.of(2026, 10, 16), dates.last())
        assertEquals(3, dates.size)
    }

    /** 29 March 2026 has 23 hours in Paris: the next date comes 23 hours after its midnight, not 24. */
    @Test
    fun `a day that is an hour short`() = runTest {
        val clock = TestClock(testScheduler, Instant.parse("2026-03-28T23:30:00Z"), paris)
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { Today(clock, MutableStateFlow(true)).dates.flowOn(StandardTestDispatcher(testScheduler)).collect { dates += it } }
        runCurrent()
        assertEquals(LocalDate.of(2026, 3, 29), dates.single())
        // Paris's midnight is 22:00 UTC on the 29th.
        advanceTimeBy(Instant.parse("2026-03-29T21:59:00Z").toEpochMilli() - Instant.parse("2026-03-28T23:30:00Z").toEpochMilli())
        assertEquals(1, dates.size)
        advanceTimeBy(61_000)
        assertEquals(LocalDate.of(2026, 3, 30), dates.last())
    }

    @Test
    fun `coming back to the foreground looks at the date again`() = runTest {
        val clock = TestClock(testScheduler, Instant.parse("2026-10-14T10:00:00Z"), paris)
        val foreground = MutableStateFlow(true)
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { Today(clock, foreground).dates.flowOn(StandardTestDispatcher(testScheduler)).collect { dates += it } }
        runCurrent()
        foreground.value = false
        runCurrent()
        // Two days pass in deep sleep, where a delay doesn't run.
        clock.skipped = 2 * 24 * 3_600_000L
        foreground.value = true
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16)), dates)
    }

    /**
     * The lists, the refresher and TMDB's client count days by it: a zone change while the app runs
     * reaches each of them at its next read, so none can call today's episode tomorrow's.
     */
    @Test
    fun `the device clock reads the zone as it is at each call`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val clock: Clock = DeviceClock
            assertEquals(ZoneId.of("America/New_York"), clock.zone)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), clock.zone)
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}

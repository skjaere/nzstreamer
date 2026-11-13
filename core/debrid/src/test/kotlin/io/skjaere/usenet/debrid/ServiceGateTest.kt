package io.skjaere.usenet.debrid

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServiceGateTest {
    private val clock = MutableClock()
    private val gate = ServiceGate(clock)

    private fun after(wait: Duration) {
        clock.instant = clock.instant.plus(wait)
    }

    @Test
    fun `a limit with a wait closes it for that wait`() {
        gate.record(ServiceError.Limit(Duration.ofMinutes(20)))
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), gate.closed())
        after(Duration.ofMinutes(19))
        assertEquals(ServiceError.Limit(Duration.ofMinutes(1)), gate.closed(), "with the time left")
        after(Duration.ofMinutes(1))
        assertNull(gate.closed())
    }

    @Test
    fun `a limit without a wait closes it for an hour`() {
        gate.record(ServiceError.Limit())
        after(Duration.ofMinutes(59))
        assertEquals(ServiceError.Limit(Duration.ofMinutes(1)), gate.closed())
        after(Duration.ofMinutes(1))
        assertNull(gate.closed())
    }

    @Test
    fun `a refused key closes it until it is reset`() {
        gate.record(ServiceError.KeyRefused)
        after(Duration.ofDays(30))
        assertEquals(ServiceError.KeyRefused, gate.closed())
        gate.reset()
        assertNull(gate.closed())
    }

    @Test
    fun `a call that succeeds opens it`() {
        gate.record(ServiceError.Limit(Duration.ofMinutes(20)))
        gate.opened()
        assertNull(gate.closed())
        gate.record(ServiceError.KeyRefused)
        gate.opened()
        assertNull(gate.closed())
    }

    @Test
    fun `a refused release, a fault and a miss leave it open`() {
        listOf(ServiceError.Refused(tooLarge = true), ServiceError.Refused(), ServiceError.Down, ServiceError.NotCached, ServiceError.Removed)
            .forEach { error ->
                gate.record(error)
                assertNull(gate.closed(), error.toString())
            }
    }

    @Test
    fun `a closed gate answers its error without calling`() = runBlocking<Unit> {
        gate.record(ServiceError.Limit(Duration.ofMinutes(20)))
        var calls = 0
        val refused = assertFailsWith<ServiceException> { gate.guarded { calls++ } }
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), refused.error)
        assertEquals(0, calls)
    }

    @Test
    fun `a guarded call's limit closes it and its success opens it, a refused release doesn't close it`() = runBlocking<Unit> {
        assertFailsWith<ServiceException> { gate.guarded { throw ServiceException(ServiceError.Refused(tooLarge = true)) } }
        assertNull(gate.closed())
        assertEquals("ok", gate.guarded { "ok" })
        assertFailsWith<ServiceException> { gate.guarded { throw ServiceException(ServiceError.Limit(Duration.ofMinutes(5))) } }
        assertEquals(ServiceError.Limit(Duration.ofMinutes(5)), gate.closed())
        after(Duration.ofMinutes(5))
        assertEquals(2, gate.guarded { 2 })
        assertNull(gate.closed())
    }
}

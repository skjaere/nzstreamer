package io.skjaere.usenet.debrid

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * One account's pace (L175): one call every [interval] (TorBox 250 ms, 240 a minute against its 300; Premiumize, which
 * publishes none, 500 ms), and a wait the service asked for ([holdFor]: a 429's Retry-After) holds every call until it
 * ends. Callers wait their turn in order. [now] is a monotonic clock in milliseconds.
 */
class ServiceRateLimit(
    private val interval: Duration,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Turns are taken one at a time, in the order callers came. */
    private val turns = Mutex()

    /** When the next call may go, on [now]'s clock. */
    private val next = AtomicLong(Long.MIN_VALUE)

    /** Waits until this account may make its next call, and takes that turn. */
    suspend fun acquire() {
        turns.withLock {
            // A wait asked for while this caller waits ([holdFor]) moves the turn on: look again after each.
            while (true) {
                val at = next.get()
                val time = now()
                if (at <= time) break
                delay(at - time)
            }
            next.accumulateAndGet(now() + interval.toMillis()) { a, b -> maxOf(a, b) }
        }
    }

    /** Holds every call until [wait] from now has passed (or later, if one is held longer already). */
    fun holdFor(wait: Duration) {
        next.accumulateAndGet(now() + wait.toMillis()) { a, b -> maxOf(a, b) }
    }
}

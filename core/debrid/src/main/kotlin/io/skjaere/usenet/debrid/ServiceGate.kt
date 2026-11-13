package io.skjaere.usenet.debrid

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * One account's gate (L175; debridav's `DebridClientGate`, ported as an idea): a [ServiceError.Limit] closes it for the
 * answer's wait, else for [limitWithoutWait]; a [ServiceError.KeyRefused] closes it until [reset] (a new key saved). While
 * it is closed, the account's torrents aren't checked or played: [guarded] answers the closing error without a call. Any
 * call that succeeds opens it. Nothing else closes it: a [ServiceError.Refused] is one release's, and a
 * [ServiceError.Down] passes.
 */
class ServiceGate(
    private val clock: Clock = Clock.systemUTC(),
    private val limitWithoutWait: Duration = Duration.ofHours(1),
) {
    /** Why the gate is closed: a limit until [until], or a refused key ([until] null: until reset). */
    private class Closed(val until: Instant?)

    private val state = AtomicReference<Closed?>(null)

    /** The error a closed gate answers, without a call: the limit with its time left, or the refused key; null while open. */
    fun closed(): ServiceError? {
        val closed = state.get() ?: return null
        val until = closed.until ?: return ServiceError.KeyRefused
        val left = Duration.between(clock.instant(), until)
        if (left.isNegative || left.isZero) {
            // Over: open it, unless something closed it again meanwhile.
            state.compareAndSet(closed, null)
            return null
        }
        return ServiceError.Limit(left)
    }

    /** Runs [call] while the gate is open, else answers its error without calling; [call]'s outcome opens or closes it. */
    suspend fun <T> guarded(call: suspend () -> T): T {
        closed()?.let { throw ServiceException(it) }
        val value = try {
            call()
        } catch (e: ServiceException) {
            record(e.error)
            throw e
        }
        opened()
        return value
    }

    /** Closes the gate for [error] if it is a limit or a refused key; anything else leaves it as it is. */
    fun record(error: ServiceError) {
        when (error) {
            is ServiceError.Limit -> state.set(Closed(clock.instant().plus(error.retryAfter ?: limitWithoutWait)))
            ServiceError.KeyRefused -> state.set(Closed(null))
            else -> Unit
        }
    }

    /** A call succeeded: the gate opens. */
    fun opened() = state.set(null)

    /** A new key was saved: the gate opens, whatever closed it. */
    fun reset() = state.set(null)
}

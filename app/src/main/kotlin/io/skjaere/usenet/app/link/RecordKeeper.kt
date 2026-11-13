package io.skjaere.usenet.app.link

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One mDNS record kept up through NsdManager one call at a time (L47): the record it is told it wants ([want]), or none.
 *
 * On Android 14, unregistering a record while its probe was finishing wedged the emulators' mDNS advertiser, and after
 * that no registration finished, in any app, until a restart. The TV's record changes as `pair` or its address does, so
 * a quick open and close of Pair a phone could do that to a TV. So the keeper never unregisters a record before NSD has
 * said how its registration went, and never registers the next before NSD has said the last is down. What it is told
 * meanwhile comes to the latest record alone, or to none. An answer that never comes is given up after [giveUpMillis],
 * with one line to [info]: a registration as if it were up (so it is taken down, or kept), an unregistration as if it
 * were down. A registration that failed is down already and blocks nothing; the same record isn't tried again until it
 * is asked for again.
 *
 * Everything happens on [executor], one thing at a time: what the caller says and NsdManager's answers, which come on
 * its own thread, are handed to it, and the NSD calls are made from it. The multicast lock ([NsdCalls.multicast], L15)
 * is held from the moment a record is wanted until none is wanted or held. Nothing of the record is logged.
 */
internal class RecordKeeper(
    private val calls: NsdCalls,
    private val executor: ScheduledExecutorService,
    private val giveUpMillis: Long,
    private val info: (String) -> Unit,
) {
    /** One registration of [advert], which NSD's answers about it come to. */
    private inner class Registration(val advert: Advert) : NsdHeard {
        /** What [NsdCalls.register] gave for it, for its unregister. */
        var token: Any? = null

        override fun registered() = post { if (state === State.Registering && current === this) up() }

        // Down already: NSD holds nothing of a failed registration, whether it was waited on, given up on as up, or
        // being taken down.
        override fun registrationFailed(code: Int) = post {
            if (current === this) {
                refused = advert
                idle()
            }
        }

        override fun unregistered() = post { if (state === State.Unregistering && current === this) idle() }

        override fun unregistrationFailed(code: Int) = post { if (state === State.Unregistering && current === this) idle() }
    }

    private enum class State { Idle, Registering, Up, Unregistering }

    // All of these on the executor's thread alone.
    private var state = State.Idle
    /** The registration [state] is about; null while idle. */
    private var current: Registration? = null
    private var wanted: Advert? = null
    /** A record whose registration failed: not tried again until it is asked for again. */
    private var refused: Advert? = null
    private var waiting: ScheduledFuture<*>? = null
    private var locked = false

    /** Whether the keeper holds no record, wants none and waits on no answer: for a test to wait on. */
    @Volatile var settled = true
        private set

    /** [advert] to keep up from now, in place of any other. Returns at once. */
    fun want(advert: Advert) = post {
        wanted = advert
        refused = null
        step()
    }

    /** [advert] no longer wanted, if it still is: another may have taken its place. Returns at once. */
    fun drop(advert: Advert) = post {
        if (wanted === advert) {
            wanted = null
            step()
        }
    }

    private fun post(block: () -> Unit) = executor.execute(block)

    /** The next call, if one is due: [state] says what is waited on, [wanted] what should be up. */
    private fun step() {
        val registration = current
        when (state) {
            State.Idle -> {
                val next = wanted?.takeIf { it != refused }
                if (next != null) register(next) else rest()
            }
            State.Up -> if (registration != null && wanted != registration.advert) unregister(registration)
            State.Registering, State.Unregistering -> Unit
        }
    }

    private fun register(advert: Advert) {
        if (!locked) {
            locked = true
            settled = false
            calls.multicast(true)
        }
        val registration = Registration(advert)
        current = registration
        state = State.Registering
        giveUp(registration)
        val token = try {
            calls.register(advert, registration)
        } catch (_: RuntimeException) {
            // Refused at once in a way NsdManager doesn't say it will: as a refusal.
            null
        }
        if (token != null) {
            registration.token = token
        } else if (current === registration && state == State.Registering) {
            refused = advert
            idle()
        }
    }

    private fun unregister(registration: Registration) {
        state = State.Unregistering
        giveUp(registration)
        val taken = try {
            registration.token?.let(calls::unregister) == true
        } catch (_: RuntimeException) {
            false
        }
        // NSD doesn't have it (its registration failed meanwhile): down already.
        if (!taken && current === registration && state == State.Unregistering) idle()
    }

    private fun up() {
        waiting?.cancel(false)
        state = State.Up
        step()
    }

    private fun idle() {
        waiting?.cancel(false)
        current = null
        state = State.Idle
        step()
    }

    /** No record held or wanted: the lock given back until one is asked for. */
    private fun rest() {
        if (locked) {
            locked = false
            calls.multicast(false)
        }
        settled = true
    }

    /** Gives up on [registration]'s answer after [giveUpMillis], with a line, unless it came first. */
    private fun giveUp(registration: Registration) {
        waiting?.cancel(false)
        val awaited = state
        waiting = executor.schedule({
            if (current === registration && state == awaited) {
                if (awaited == State.Registering) {
                    info("Play on TV: no word from NSD of the TV's record's registration in $giveUpMillis ms; going on as if it were up")
                    up()
                } else {
                    info("Play on TV: no word from NSD of the TV's record being taken down in $giveUpMillis ms; going on as if it were")
                    idle()
                }
            }
        }, giveUpMillis, TimeUnit.MILLISECONDS)
    }
}

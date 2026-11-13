package io.skjaere.usenet.link

import java.net.InetAddress
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A phone a ceremony paired, for the TV to save (L22): its [key] as TLS met it, its [name] as its hello gave it
 * (untrusted, L28) and the [address] it came from. Prints as its key's short id alone.
 */
class NewPhone internal constructor(val key: KeyId, val name: String, val address: InetAddress) {
    override fun toString() = "NewPhone(${key.short})"
}

/** The TV's Pair a phone page, as its listener sees it while it takes pairings (L22). */
interface PairingHost {
    /**
     * Both said yes: save [phone] now, replacing any entry with its key, and say whether it was saved. Only then is the
     * phone told it is paired. On the listener's pairing thread, which may block for the write.
     */
    fun save(phone: NewPhone): Boolean

    /** What pairing did, in order, on the listener's pairing thread: post it to the app's own and don't block. */
    fun heard(event: PairingEvent)
}

/** What a page that takes pairings hears. None holds a number or a name in its printed form (L13). */
sealed interface PairingEvent {
    /** A phone asks to pair: [question] shows its number until it is answered, or [LinkLimits.questionMillis] pass. */
    class Asking(val question: PairQuestion) : PairingEvent {
        override fun toString() = "Asking"
    }

    /** Whether another phone waits behind the one asking: the question says so (L22). */
    data class Waiting(val another: Boolean) : PairingEvent

    /** A phone's ceremony ended for [outcome]; [question] is the one it asked, null when it ended before its number. */
    class Ended(val question: PairQuestion?, val outcome: PairOutcome) : PairingEvent {
        override fun toString() = "Ended($outcome)"
    }

    /** Pairing closed by itself, [LinkLimits.pairingMillis] after it opened or its last ceremony ended (L22). */
    data object Closed : PairingEvent
}

/**
 * The TV's question (W3, L22): "Pair with [name]?", and [number], which the phone shows too. [ok] or [cancel] answers it,
 * once; it closes by itself after [LinkLimits.questionMillis]. Both are main-safe. The 0.4 s guard on OK is the page's.
 * [name] is the phone's own word, untrusted (L28). Prints as the phone's key's short id alone, never the number.
 */
class PairQuestion internal constructor(
    val phone: KeyId,
    val name: String,
    val address: InetAddress,
    val number: PairNumber,
    private val answer: (Boolean) -> Unit,
) {
    fun ok() = answer(true)

    fun cancel() = answer(false)

    override fun toString() = "PairQuestion(${phone.short})"
}

/**
 * The listener's pairing (L22): open while the Pair a phone page takes pairings ([open], [close]), for one ceremony at
 * a time, one phone waiting its turn and a third told busy. Each ceremony is [Pairing.Tv]'s, on its own connection: a
 * commit, then the phone's nonce within [LinkLimits.helloMillis], then the question for [LinkLimits.questionMillis].
 * Failed ceremonies count against their address; [LinkLimits.pairingFailures] of them in one opening, and that address
 * is refused `too-many` until the page opens again, while other addresses still pair. Each failed one, from any address,
 * also makes the next commit wait ([LinkLimits.pairingPauseMillis], longer for each in a row), its phone told to wait
 * meanwhile, so a relay that comes from many addresses gets few tries. Pairing closes by itself
 * [LinkLimits.pairingMillis] after it opened or after its last ceremony ended.
 *
 * Its state is under one lock, stepped by the connections' threads (their messages, their end), the page (its answers)
 * and the timer. What it sends goes through each connection's queue, so none of them waits on a peer. What the page
 * hears, and the save, go in order through one thread of its own, as [events] delivers them: never after the
 * listener's close, nor after [close].
 */
internal class PairingDesk(
    private val limits: LinkLimits,
    /** The listener's delivery: runs the block under its event lock unless it is closed. */
    private val events: (() -> Unit) -> Unit,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = ::monotonicMillis,
    private val random: Random = SecureRandom(),
) {
    /** The page's thread: timers, saves and what the page hears, in order. */
    private val desk = linkTimer("link-pairing")
    private val lock = Any()

    /** The opening whose page hears, set and cleared through [events], so none hears once [close] has returned. */
    @Volatile private var hearing: Mode? = null

    /** The opening now; null while no page takes pairings. Under [lock], as everything below. */
    private var mode: Mode? = null

    /** One opening of the page. */
    inner class Mode(val host: PairingHost) {
        /** Failed ceremonies by address, the least lately counted dropped first past [MAX_ADDRESSES]. */
        val failures = object : LinkedHashMap<InetAddress, Int>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InetAddress, Int>?) = size > MAX_ADDRESSES
        }
        var active: Slot? = null
        var waiting: Slot? = null
        var window: ScheduledFuture<*>? = null

        /** Failed ceremonies in a row, from any address; a pairing starts the count again. */
        var failedInRow = 0

        /** No commit goes out before this, after a failed ceremony; null when nothing waits. */
        var notBefore: Long? = null
    }

    /** A connection that came to pair: its ceremony when its turn comes. [tvKey] and [phoneKey] as TLS met them. */
    inner class Slot(val connection: LinkConnection, val peer: KeyId, val name: String, val tvKey: ByteArray, val phoneKey: ByteArray) {
        var mode: Mode? = null
        var ceremony: Pairing.Tv? = null
        var question: PairQuestion? = null
        var timer: ScheduledFuture<*>? = null

        /** Both said yes: the save is under way, and ends the ceremony itself. */
        var saving = false
        var over = false

        /** Its phone was told to wait its turn (`pair-wait`), once. */
        var toldToWait = false
    }

    /** Takes pairings from now, for [host]; an opening before it ends, and the failures it counted go with it. */
    fun open(host: PairingHost) {
        val opening = Mode(host)
        events { hearing = opening }
        synchronized(lock) {
            mode?.let(::shut)
            mode = opening
            window(opening)
        }
    }

    /** Takes no more: a ceremony under way ends (the phone hears `closed`), and the page hears nothing more. */
    fun close() {
        events { hearing = null }
        synchronized(lock) {
            mode?.let(::shut)
            mode = null
        }
    }

    /** The listener closed: nothing more is done or heard. Its connections are closed with it. */
    fun shutdown() {
        desk.shutdownNow()
    }

    /**
     * Whether [slot], past its hello, may pair: null when its ceremony starts now or it waits its turn, its first word
     * queued; else why not, and nothing is sent.
     */
    fun admit(slot: Slot): Refusal? = synchronized(lock) {
        val opening = mode ?: return Refusal.NotPairing
        if (tooMany(opening, slot)) return Refusal.TooMany
        when {
            opening.active == null -> {
                opening.active = slot
                slot.mode = opening
                start(slot)
            }
            opening.waiting == null -> {
                opening.waiting = slot
                slot.mode = opening
                slot.toldToWait = true
                slot.connection.send(LinkMessage.PairWait)
                post(opening, PairingEvent.Waiting(another = true))
            }
            else -> return Refusal.Busy
        }
        null
    }

    /** A pairing message, or anything else, from [slot]'s phone. Pings are the listener's. */
    fun received(slot: Slot, message: LinkMessage) {
        synchronized(lock) {
            if (slot.over || slot.saving) return
            val ceremony = slot.ceremony
            // Its turn hasn't come: nothing of a ceremony is in place.
            if (ceremony == null) end(slot, PairOutcome.Broken, null) else step(slot, ceremony.received(message))
        }
    }

    /** [slot]'s connection ended; a ceremony under way ends with it, the phone gone. */
    fun gone(slot: Slot) {
        synchronized(lock) {
            if (!slot.over && !slot.saving) end(slot, PairOutcome.Left, null)
        }
    }

    /**
     * [slot]'s turn: its ceremony starts with the TV's commit, once a failed one's pause is over (its phone told to wait
     * meanwhile), and the phone's nonce must come promptly.
     */
    private fun start(slot: Slot) {
        slot.mode?.window?.cancel(false)
        val wait = slot.mode?.notBefore?.let { it - clock() } ?: 0
        if (wait > 0) {
            if (!slot.toldToWait) {
                slot.toldToWait = true
                slot.connection.send(LinkMessage.PairWait)
            }
            slot.timer = schedule(wait) { synchronized(lock) { if (!slot.over && slot.ceremony == null) commit(slot) } }
            return
        }
        commit(slot)
    }

    private fun commit(slot: Slot) {
        val ceremony = Pairing.Tv(slot.tvKey, slot.phoneKey, random)
        slot.ceremony = ceremony
        step(slot, ceremony.commit())
        slot.timer = schedule(limits.helloMillis) { timedOut(slot) }
    }

    private fun step(slot: Slot, step: Pairing.Step) {
        when (step) {
            is Pairing.Step.Send -> slot.connection.send(step.said)
            is Pairing.Step.Show -> {
                step.send?.let(slot.connection::send)
                slot.timer?.cancel(false)
                slot.timer = schedule(limits.questionMillis) { timedOut(slot) }
                val question = PairQuestion(slot.peer, slot.name, slot.connection.address, step.number) { ok -> answer(slot, ok) }
                slot.question = question
                slot.mode?.let { post(it, PairingEvent.Asking(question)) }
            }
            Pairing.Step.Confirmed -> {
                slot.saving = true
                slot.timer?.cancel(false)
                if (!later { save(slot) }) {
                    slot.saving = false
                    end(slot, PairOutcome.Closed, LinkMessage.PairResult(false, PairEnd.Closed))
                }
            }
            is Pairing.Step.Ended -> end(slot, step.outcome, step.send)
            Pairing.Step.Queued, Pairing.Step.Wait -> Unit
        }
    }

    /** The page's OK or Cancel on [slot]'s question. */
    private fun answer(slot: Slot, ok: Boolean) {
        synchronized(lock) {
            val ceremony = slot.ceremony
            if (slot.over || slot.saving || ceremony == null) return
            step(slot, if (ok) ceremony.ok() else ceremony.cancel())
        }
    }

    private fun timedOut(slot: Slot) {
        synchronized(lock) {
            val ceremony = slot.ceremony
            if (slot.over || slot.saving || ceremony == null) return
            step(slot, ceremony.timedOut())
        }
    }

    /**
     * On the page's thread: the page saves the phone, then the phone hears it, as L22 has it. A page that can't save it
     * saves nothing, and one that is gone isn't asked; the phone hears which.
     */
    private fun save(slot: Slot) {
        val opening = slot.mode
        // Null while the page wasn't asked.
        var saved: Boolean? = null
        try {
            events { if (opening != null && hearing === opening) saved = opening.host.save(NewPhone(slot.peer, slot.name, slot.connection.address)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // The page's own failure: not saved.
            saved = false
        } finally {
            synchronized(lock) {
                slot.saving = false
                when (saved) {
                    true -> end(slot, PairOutcome.Paired, LinkMessage.PairResult(ok = true))
                    false -> end(slot, PairOutcome.NotSaved, LinkMessage.PairResult(false, PairEnd.NotSaved))
                    null -> end(slot, PairOutcome.Closed, LinkMessage.PairResult(false, PairEnd.Closed))
                }
            }
        }
    }

    /** [slot] is over for [outcome]: [send] its last word then close, its place given up, the page told. */
    private fun end(slot: Slot, outcome: PairOutcome, send: LinkMessage?) {
        if (slot.over) return
        slot.over = true
        slot.timer?.cancel(false)
        if (send != null) slot.connection.finish(send, LinkClose.Closed) else slot.connection.close(LinkClose.Closed)
        val opening = slot.mode ?: return
        when {
            opening.active === slot -> {
                opening.active = null
                // One that left while its commit waited counts no more than a waiting one.
                if (outcome.failed && (slot.ceremony != null || outcome == PairOutcome.Broken)) count(opening, slot)
                if (outcome == PairOutcome.Paired) {
                    opening.failedInRow = 0
                    opening.notBefore = null
                }
                post(opening, PairingEvent.Ended(slot.question, outcome))
                next(opening)
            }
            opening.waiting === slot -> {
                opening.waiting = null
                // A phone that broke the order while it waited counts; one that left before its turn doesn't.
                if (outcome == PairOutcome.Broken) count(opening, slot)
                post(opening, PairingEvent.Waiting(another = false))
            }
        }
    }

    /** The ceremony ended: the waiting phone's turn, if one waits, else pairing's time starts again. */
    private fun next(opening: Mode) {
        val waiting = opening.waiting
        if (waiting == null) {
            window(opening)
            return
        }
        opening.waiting = null
        post(opening, PairingEvent.Waiting(another = false))
        if (tooMany(opening, waiting)) {
            waiting.over = true
            waiting.connection.finish(LinkMessage.Refused(Refusal.TooMany), LinkClose.Closed)
            window(opening)
            return
        }
        opening.active = waiting
        start(waiting)
    }

    /** Ends [opening]'s phones, the page told nothing: it is gone, or opened again. A save under way ends its own. */
    private fun shut(opening: Mode) {
        opening.window?.cancel(false)
        for (slot in listOfNotNull(opening.active, opening.waiting)) {
            slot.mode = null
            if (!slot.saving) end(slot, PairOutcome.Closed, LinkMessage.PairResult(false, PairEnd.Closed))
        }
        opening.active = null
        opening.waiting = null
    }

    /** Pairing closes [LinkLimits.pairingMillis] from now, unless a ceremony starts first. */
    private fun window(opening: Mode) {
        opening.window?.cancel(false)
        opening.window = schedule(limits.pairingMillis) {
            synchronized(lock) {
                if (mode !== opening || opening.active != null) return@synchronized
                shut(opening)
                mode = null
                post(opening, PairingEvent.Closed)
                later { events { if (hearing === opening) hearing = null } }
            }
        }
    }

    private fun tooMany(opening: Mode, slot: Slot) = (opening.failures[slot.connection.address] ?: 0) >= limits.pairingFailures

    /** A failed ceremony: against its address, and the next commit from any waits longer. */
    private fun count(opening: Mode, slot: Slot) {
        val address = slot.connection.address
        opening.failures[address] = (opening.failures[address] ?: 0) + 1
        opening.failedInRow++
        opening.notBefore = clock() + (limits.pairingPauseMillis shl (opening.failedInRow - 1).coerceAtMost(MAX_DOUBLINGS))
    }

    /** [event] for [opening]'s page, after what was posted before it. */
    private fun post(opening: Mode, event: PairingEvent) {
        later { events { if (hearing === opening) opening.host.heard(event) } }
    }

    /** [task] on the page's thread, after what was posted before it; false when the listener is closed. */
    private fun later(task: () -> Unit): Boolean = try {
        desk.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun schedule(millis: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        desk.schedule(task, millis, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    private companion object {
        /** Addresses whose failures are kept. */
        const val MAX_ADDRESSES = 256

        /** A failed ceremony's pause doubles this often at most: 16 times [LinkLimits.pairingPauseMillis]. */
        const val MAX_DOUBLINGS = 4
    }
}

/** Whether the TV counts it against the phone's address (L22): No, Cancel, a timeout, a broken order, a phone that left. */
internal val PairOutcome.failed: Boolean
    get() = when (this) {
        PairOutcome.No, PairOutcome.Declined, PairOutcome.TimedOut, PairOutcome.WrongCommit, PairOutcome.Broken, PairOutcome.Left -> true
        PairOutcome.Paired, PairOutcome.Closed, PairOutcome.NotSaved, PairOutcome.Stopped -> false
    }

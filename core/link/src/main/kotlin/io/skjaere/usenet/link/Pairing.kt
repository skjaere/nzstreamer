package io.skjaere.usenet.link

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * The six digits both screens show to pair (L21): the same on both when nothing sits between them. It prints as its
 * type alone, since a number shown for pairing is never logged (L13).
 */
class PairNumber internal constructor(val value: Int) {
    /** As both screens show it: six digits, leading zeros kept, ASCII whatever the locale. */
    val digits: String get() = String.format(Locale.ROOT, "%06d", value)

    override fun equals(other: Any?) = other is PairNumber && other.value == value

    override fun hashCode() = value

    override fun toString() = "PairNumber"
}

/** How a pairing ended, on either end (L21, L22): what the app shows and logs. */
enum class PairOutcome {
    /** Both said yes, and the TV saved it. */
    Paired,

    /** No on the phone: the numbers didn't match. */
    No,

    /** Cancel on the TV. */
    Declined,

    /** The TV's question went unanswered for [LinkLimits.questionMillis], or a step didn't come in time. */
    TimedOut,

    /** Pair a phone closed meanwhile. */
    Closed,

    /** The TV couldn't save the pairing. */
    NotSaved,

    /** The TV ended it for a reason this version doesn't know. */
    Stopped,

    /** The phone's end: the TV's nonce didn't match its commit, so another device may sit between them. */
    WrongCommit,

    /** A pairing message repeated, late, out of order or malformed, or another message out of place. */
    Broken,

    /** The peer left, or the connection went. */
    Left,
}

/**
 * Pairing by numbers, commit then reveal (L21), as Bluetooth LE Secure Connections and ZRTP do. It runs inside a TLS
 * connection whose two keys aren't pinned yet, and takes them from the session: TLS proves each peer holds its key.
 * The TV commits to its nonce, the phone sends its own, the TV reveals; both then show [number], worked out from both
 * keys and both nonces, and the pairing stands once the TV's user and the phone's have said they match.
 *
 * [Tv] and [Phone] are each end's steps, and do no I/O: the server and the client send what they say and show what they
 * show. One ceremony a connection: each end takes exactly one of each pairing message, in order, and works the number
 * out once; anything else ends it with nothing saved.
 */
internal object Pairing {
    const val NONCE_BYTES = 32

    /** c = SHA-256("NZStreamer pair commit v1" ‖ len‖[tvKey] ‖ len‖[phoneKey] ‖ [tvNonce]), the keys as SPKI. */
    fun commit(tvKey: ByteArray, phoneKey: ByteArray, tvNonce: ByteArray): ByteArray =
        sha256(COMMIT_LABEL).sized(tvKey).sized(phoneKey).plain(tvNonce).digest()

    /**
     * The first 8 bytes of SHA-256("NZStreamer pair number v1" ‖ len‖[phoneKey] ‖ len‖[tvKey] ‖ [phoneNonce] ‖ [tvNonce]),
     * big-endian and unsigned, mod 1,000,000. Fixed within v1: honest numbers must agree across app versions (G7).
     */
    fun number(phoneKey: ByteArray, tvKey: ByteArray, phoneNonce: ByteArray, tvNonce: ByteArray): PairNumber {
        val digest = sha256(NUMBER_LABEL).sized(phoneKey).sized(tvKey).plain(phoneNonce).plain(tvNonce).digest()
        val first = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long.toULong()
        return PairNumber((first % 1_000_000u).toInt())
    }

    /** As the pairing messages carry 32 bytes: 64 lowercase hex digits. */
    fun hex(bytes: ByteArray): String {
        val text = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            text[i * 2] = HEX[(b.toInt() shr 4) and 0xf]
            text[i * 2 + 1] = HEX[b.toInt() and 0xf]
        }
        return String(text)
    }

    /** The 32 bytes of exactly 64 lowercase hex digits; null for anything else, which ends a ceremony. */
    fun unhex(text: String): ByteArray? {
        if (text.length != NONCE_BYTES * 2) return null
        val bytes = ByteArray(NONCE_BYTES)
        for (i in bytes.indices) {
            val high = HEX.indexOf(text[i * 2])
            val low = HEX.indexOf(text[i * 2 + 1])
            if (high < 0 || low < 0) return null
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private const val HEX = "0123456789abcdef"
    private val COMMIT_LABEL = "NZStreamer pair commit v1".toByteArray(Charsets.US_ASCII)
    private val NUMBER_LABEL = "NZStreamer pair number v1".toByteArray(Charsets.US_ASCII)

    private fun sha256(label: ByteArray): MessageDigest = MessageDigest.getInstance("SHA-256").apply { update(label) }

    /** [bytes] after their length, 4 bytes big-endian. */
    private fun MessageDigest.sized(bytes: ByteArray): MessageDigest = apply {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private fun MessageDigest.plain(bytes: ByteArray): MessageDigest = apply { update(bytes) }

    /** What a ceremony's step comes to, for the end that took it. */
    sealed interface Step {
        /** Send [said]; the ceremony goes on. */
        class Send(val said: LinkMessage) : Step

        /** The number shows now; the TV sends [send], its reveal, first. */
        class Show(val number: PairNumber, val send: LinkMessage?) : Step

        /** The phone waits its turn: another phone pairs with the TV. */
        data object Queued : Step

        /** Nothing to do now: the other confirmation is still to come, or the ceremony is over. */
        data object Wait : Step

        /** Both said yes. The TV saves, then tells the phone; the phone saves on that. */
        data object Confirmed : Step

        /** Over, nothing saved: [send] is the peer's last word, if any, then the connection closes. */
        class Ended(val outcome: PairOutcome, val send: LinkMessage? = null) : Step
    }

    /**
     * The TV's end: it commits to its nonce first, reveals it once the phone's is in, and asks its user; the pairing
     * stands only once its OK and the phone's yes are both in, and the TV saves before it says so. Any other message,
     * or one again, ends it. Locked, as the listener's thread, its timer and the page's answer all step it.
     */
    class Tv(private val tvKey: ByteArray, private val phoneKey: ByteArray, random: Random = SecureRandom()) {
        private val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        private var state = State.Fresh
        private var ok = false
        private var yes = false

        private enum class State { Fresh, Committed, Asking, Over }

        /** The ceremony's first message: the commit, once. */
        @Synchronized
        fun commit(): Step {
            if (state != State.Fresh) return broken()
            state = State.Committed
            return Step.Send(LinkMessage.PairCommit(hex(commit(tvKey, phoneKey, nonce))))
        }

        @Synchronized
        fun received(message: LinkMessage): Step = when {
            state == State.Over -> Step.Wait
            state == State.Committed && message is LinkMessage.PairNonce -> {
                val phoneNonce = unhex(message.n)
                if (phoneNonce == null) {
                    broken()
                } else {
                    state = State.Asking
                    Step.Show(number(phoneKey, tvKey, phoneNonce, nonce), LinkMessage.PairReveal(hex(nonce)))
                }
            }
            state == State.Asking && message is LinkMessage.PairConfirm && !yes -> if (message.yes) {
                yes = true
                confirmed()
            } else {
                over(PairOutcome.No, null)
            }
            else -> broken()
        }

        /** The TV's user's OK on its question. */
        @Synchronized
        fun ok(): Step {
            if (state != State.Asking || ok) return Step.Wait
            ok = true
            return confirmed()
        }

        /** The TV's user's Cancel. */
        @Synchronized
        fun cancel(): Step = if (state == State.Over) Step.Wait else over(PairOutcome.Declined, LinkMessage.PairResult(false, PairEnd.Declined))

        /** A step didn't come in time: the phone's nonce, or the user's answer. */
        @Synchronized
        fun timedOut(): Step = if (state == State.Over) Step.Wait else over(PairOutcome.TimedOut, LinkMessage.PairResult(false, PairEnd.TimedOut))

        private fun confirmed(): Step {
            if (!(ok && yes)) return Step.Wait
            state = State.Over
            return Step.Confirmed
        }

        private fun broken() = over(PairOutcome.Broken, null)

        private fun over(outcome: PairOutcome, send: LinkMessage?): Step {
            state = State.Over
            return Step.Ended(outcome, send)
        }
    }

    /**
     * The phone's end: it sends its nonce only for the TV's commit, checks the reveal against that commit, shows the
     * number, and counts itself paired only on the TV's word after its own yes. Any other message, or one again, ends
     * it. Locked, as its reader and its user both step it.
     */
    class Phone(private val phoneKey: ByteArray, private val tvKey: ByteArray, random: Random = SecureRandom()) {
        private val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        private var commit: ByteArray? = null
        private var state = State.Fresh
        private var queued = false

        private enum class State { Fresh, Nonced, Shown, Confirmed, Over }

        /** How it ended; null while it goes on. */
        @get:Synchronized
        var outcome: PairOutcome? = null
            private set

        @Synchronized
        fun received(message: LinkMessage): Step = when {
            state == State.Over -> Step.Wait
            // The TV's last word, whenever it comes: it stopped.
            message is LinkMessage.PairResult && !message.ok -> over(stopped(message.reason), null)
            state == State.Fresh && message is LinkMessage.PairWait && !queued -> {
                queued = true
                Step.Queued
            }
            state == State.Fresh && message is LinkMessage.PairCommit -> {
                commit = unhex(message.c)
                if (commit == null) {
                    broken()
                } else {
                    state = State.Nonced
                    Step.Send(LinkMessage.PairNonce(hex(nonce)))
                }
            }
            state == State.Nonced && message is LinkMessage.PairReveal -> {
                val tvNonce = unhex(message.n)
                when {
                    tvNonce == null -> broken()
                    !MessageDigest.isEqual(commit, commit(tvKey, phoneKey, tvNonce)) -> over(PairOutcome.WrongCommit, null)
                    else -> {
                        state = State.Shown
                        Step.Show(number(phoneKey, tvKey, nonce, tvNonce), null)
                    }
                }
            }
            state == State.Confirmed && message is LinkMessage.PairResult -> {
                state = State.Over
                outcome = PairOutcome.Paired
                Step.Confirmed
            }
            else -> broken()
        }

        /** The phone's user's yes: the numbers match. */
        @Synchronized
        fun yes(): Step {
            if (state != State.Shown) return Step.Wait
            state = State.Confirmed
            return Step.Send(LinkMessage.PairConfirm(yes = true))
        }

        /** The phone's user's No. */
        @Synchronized
        fun no(): Step = if (state == State.Shown) over(PairOutcome.No, LinkMessage.PairConfirm(yes = false)) else Step.Wait

        private fun stopped(reason: PairEnd?): PairOutcome = when (reason) {
            PairEnd.Declined -> PairOutcome.Declined
            PairEnd.TimedOut -> PairOutcome.TimedOut
            PairEnd.Closed -> PairOutcome.Closed
            PairEnd.NotSaved -> PairOutcome.NotSaved
            PairEnd.Unknown, null -> PairOutcome.Stopped
        }

        private fun broken() = over(PairOutcome.Broken, null)

        private fun over(outcome: PairOutcome, send: LinkMessage?): Step {
            state = State.Over
            this.outcome = outcome
            return Step.Ended(outcome, send)
        }
    }
}

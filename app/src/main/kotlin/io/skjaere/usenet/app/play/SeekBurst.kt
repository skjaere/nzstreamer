package io.skjaere.usenet.app.play

import kotlin.math.abs

/**
 * One seek for a burst of rewind and fast-forward taps, kept apart from the player so it can be
 * tested. Each seek has the player close the file's stream and open another, starting article
 * fetches that the next seek cancels: taps (or a remote's keys) less than [WINDOW_MS] apart add up,
 * and one seek runs [WINDOW_MS] after the last. A tap can ask for a wait of its own (touch, slower
 * than a remote). While a remote's key is down the burst is held open ([holdOpen]): nothing seeks
 * until the key's up settles it ([settle]), the up's wait later, or [HOLD_MS] after the key's last
 * event if the up never comes. [ControlsPlayer] feeds it the taps, the keys and the time.
 *
 * Each tap moves the target by its step: from the target, or for a burst's first tap from where
 * playback is. A burst goes no earlier than the start and, once the file's length is known, no
 * later than [END_MS] before its end: it lands in the file rather than ending the play. A step
 * never moves the target the other way, so fast-forward from inside that last bit stays put.
 *
 * Anything else done meanwhile has the seek run at once, first ([flush]); a seek from elsewhere
 * replaces it ([drop]). A burst that ends where it started seeks nowhere. One can also seek at
 * once and go on ([seekNow]): it seeks again as it ends only if taps moved it since.
 */
class SeekBurst(
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
    private val seek: (Long) -> Unit,
) {
    /** Where the burst goes; null while none is under way. */
    var targetMs: Long? = null
        private set
    /** Where playback was as the burst started. */
    private var fromMs = 0L
    /** Where the player was last sent: where playback was as the burst started, until [seekNow]. */
    private var sentMs = 0L
    /** As near the end as the burst goes, as of its last tap. */
    private var lastMs = Long.MAX_VALUE
    /** When the last tap, key event or settle was, and how long the seek waits after it. */
    private var lastTap = 0L
    private var waitMs = WINDOW_MS
    /** A remote's key is down: the seek waits for its up, [HOLD_MS] at most. */
    private var open = false

    /** How far the burst goes from where it started; null while none is under way. */
    val shiftMs: Long? get() = targetMs?.let { it - fromMs }

    /**
     * Whether the burst's seek is still to move the player: one under way, going elsewhere than
     * where the player was last sent. Not fast-forward from inside the file's last second, which
     * stays put, nor a burst [seekNow] has sent where it goes, until taps move it again.
     */
    val seekToCome: Boolean get() = targetMs.let { it != null && it != sentMs }

    /**
     * What a label says of the burst, to the nearest second: how far it goes, "+30 s" or "−20 s",
     * from a minute on as the controls write a time, "+1:30". Going nowhere at the start or as near
     * the end as it goes, where taps that way can't move it, it says so instead. Null while none
     * is under way.
     */
    val said: Said?
        get() {
            val to = targetMs ?: return null
            val shift = to - fromMs
            val seconds = (abs(shift) + 500) / 1_000
            val length = if (seconds < 60) "$seconds s" else clock(seconds)
            return when {
                seconds == 0L && to == 0L -> Said("At the start")
                seconds == 0L && to >= lastMs -> Said("At the end")
                seconds == 0L -> Said("0 s", spoken(seconds))
                shift > 0 -> Said("+$length", "Forward ${spoken(seconds)}")
                else -> Said("\u2212$length", "Back ${spoken(seconds)}")
            }
        }

    /**
     * A tap: [stepMs] on, or back when negative. [positionMs] is where playback is, [durationMs]
     * the file's length (C.TIME_UNSET while unknown); the seek runs [waitMs] after it, unless more
     * come. Returns the target.
     */
    fun tap(stepMs: Long, positionMs: Long, durationMs: Long, waitMs: Long = WINDOW_MS): Long {
        val from = targetMs ?: positionMs.also {
            fromMs = it
            sentMs = it
        }
        val last = if (durationMs > 0) (durationMs - END_MS).coerceAtLeast(0) else Long.MAX_VALUE
        val moved = (from + stepMs).coerceIn(0, last)
        val to = if (stepMs >= 0) maxOf(moved, from) else minOf(moved, from)
        targetMs = to
        lastMs = last
        lastTap = now()
        this.waitMs = waitMs
        return to
    }

    /**
     * A remote's key is down (its tap made): the seek waits for its up ([settle]), however far apart its repeats
     * come, and at most [HOLD_MS] after this, its last event. Nothing to hold while no burst is under way.
     */
    fun holdOpen() {
        if (targetMs == null) return
        open = true
        lastTap = now()
    }

    /** The key is up: the seek runs [waitMs] from now, unless more taps come first. */
    fun settle(waitMs: Long) {
        if (targetMs == null) return
        open = false
        lastTap = now()
        this.waitMs = waitMs
    }

    /** How long until the burst's seek runs, zero once it is due; null while none is under way. */
    fun dueInMs(): Long? = targetMs?.let { (lastTap + (if (open) HOLD_MS else waitMs) - now()).coerceAtLeast(0) }

    /** Runs the seek once it is due ([dueInMs]): the last tap's wait passed, or [HOLD_MS] held open with no event. */
    fun update() {
        if (dueInMs() == 0L) flush()
    }

    /** Something else is done: the burst's seek runs now, before it. */
    fun flush() {
        val to = targetMs ?: return
        targetMs = null
        open = false
        if (to != sentMs) seek(to)
    }

    /** The burst's seek runs now, and the burst goes on: later taps add to it, as before. */
    fun seekNow() {
        val to = targetMs ?: return
        if (to == sentMs) return
        sentMs = to
        seek(to)
    }

    /** A seek from elsewhere replaces the burst's: it ends, seeking nowhere. */
    fun drop() {
        targetMs = null
        open = false
    }

    /** What a label shows, and what TalkBack reads of it. */
    data class Said(val text: String, val spoken: String = text) {
        /** Which way the burst goes, as its words say how far: on (true) or back; null where it goes nowhere ("At the start", "At the end", "0 s"). */
        val forward: Boolean?
            get() = when {
                text.startsWith("+") -> true
                text.startsWith("\u2212") -> false
                else -> null
            }

        /** Said with where the burst goes, written as [time]: for a label shown without the time bar, "−20 s · 18:02". */
        fun withTarget(time: String) = Said("$text \u00b7 $time", "$spoken, $time")
    }

    /** "1:30", or "1:02:05" from an hour on. */
    private fun clock(seconds: Long): String =
        if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

    /** "1 minute 30 seconds", for TalkBack: hours, minutes and seconds, those that aren't nought. */
    private fun spoken(seconds: Long): String {
        fun part(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"}"
        val parts = listOf(seconds / 3_600 to "hour", seconds / 60 % 60 to "minute", seconds % 60 to "second").filter { (n, _) -> n > 0 }
        return parts.joinToString(" ") { (n, unit) -> part(n, unit) }.ifEmpty { part(0, "second") }
    }

    companion object {
        /** How long after a tap the burst's seek waits for another: taps in quick succession come well within it. */
        const val WINDOW_MS = 500L
        /** How long a burst held open by a key that is down waits for its next event or its up: a lost up (Bluetooth) still seeks. */
        const val HOLD_MS = 2_000L
        /** How far before the end a burst stops at most: in the file's last second, not past its end. */
        const val END_MS = 1_000L
    }
}

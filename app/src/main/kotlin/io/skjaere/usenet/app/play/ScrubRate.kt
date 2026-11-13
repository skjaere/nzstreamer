package io.skjaere.usenet.app.play

import io.skjaere.usenet.player.PlayerFactory
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How far a remote's Left or Right (or ⏪/⏩) held down seeks ([RemoteKeys]), kept apart from the player so it can be
 * tested: a press goes as far as the controls' buttons, 10 s; held, it goes on at 40 s a second for the first 2 s, as
 * before, so a jump of a minute or so stays fine (L3). From then the speed doubles every 0.35 s, up to an eighth of the
 * file a second, never under 240 s a second (240 while the length is unknown): from 0:00, a quarter of a 45 min to 3 h
 * file takes 4.4 to 5.2 s held, half 6.4 to 7.2 s, the whole of it 10.4 to 11.2 s. Each of the key's repeats moves the
 * target of [ControlsPlayer]'s burst on by what the hold added since the one before, and the burst seeks once, a moment
 * after the key is let go ([settleMs]). The numbers are to tune on a real remote (W4).
 */
object ScrubRate {
    /** How far a press goes from where it started, [heldMs] after the key went down, in a file [durationMs] long (C.TIME_UNSET while unknown). */
    fun offsetMs(heldMs: Long, durationMs: Long): Long {
        val held = heldMs.coerceAtLeast(0).toDouble()
        val fine = FINE_RATE * held.coerceAtMost(FINE_MS.toDouble())
        if (held <= FINE_MS) return PlayerFactory.SEEK_FORWARD_MS + fine.toLong()
        val top = topRate(durationMs)
        val topAt = topAtMs(top)
        // The doubling stage in closed form: the integral of 40 · 2^(t / 350 ms) from 2 s, then the top speed on.
        val rising = held.coerceAtMost(topAt) - FINE_MS
        val doubled = FINE_RATE * DOUBLING_MS / ln(2.0) * (2.0.pow(rising / DOUBLING_MS) - 1)
        val atTop = top * (held - topAt).coerceAtLeast(0.0)
        return PlayerFactory.SEEK_FORWARD_MS + (fine + doubled + atTop).roundToLong()
    }

    /** How fast a hold goes [heldMs] after the key went down, for the label's speed cue (W8). */
    fun speed(heldMs: Long, durationMs: Long): Speed = when {
        heldMs < FINE_MS -> Speed.Fine
        heldMs < topAtMs(topRate(durationMs)) -> Speed.Rising
        else -> Speed.Top
    }

    /**
     * How long the burst's seek waits after the up of a key held [heldMs], for a press or a hold the other way to join it
     * (L5): half a second, as after a tap, but a second once the hold went past its first 2 s, where a reaction overshoots.
     */
    fun settleMs(heldMs: Long): Long = if (heldMs >= FINE_MS) SETTLE_AFTER_FAST_MS else SeekBurst.WINDOW_MS

    /** A hold's speed: its first 2 s, the doubling after them, and the top speed. */
    enum class Speed { Fine, Rising, Top }

    /** Seconds of the file per second held at the top speed: an eighth of the file, never under [FLOOR_RATE]. */
    private fun topRate(durationMs: Long): Double = maxOf(FLOOR_RATE, if (durationMs > 0) durationMs / 1_000.0 / TOP_SHARE else 0.0)

    /** Milliseconds held when the doubling reaches [top]. */
    private fun topAtMs(top: Double): Double = FINE_MS + DOUBLING_MS * log2(top / FINE_RATE)

    /** Seconds of the file per second held for the first [FINE_MS], as before (L3). */
    private const val FINE_RATE = 40.0
    private const val FINE_MS = 2_000L
    /** From [FINE_MS] the speed doubles every this long. */
    private const val DOUBLING_MS = 350.0
    /** The top speed, in seconds of the file per second held: the file's length over [TOP_SHARE], never under this. */
    private const val FLOOR_RATE = 240.0
    private const val TOP_SHARE = 8.0
    private const val SETTLE_AFTER_FAST_MS = 1_000L
    /**
     * How long a TV's own remote over HDMI-CEC may seem to let a held key go between its presses ([SeekHold]): its press
     * again within this of its up goes on with their run, and a longer gap is the key let go. One TV's remote (the Shield's
     * CEC history) sends a held key's first repeat about 500 ms after the press, 430 ms after its release, then
     * one every 166 to 380 ms (mostly 250), each press mostly 71 ms long: that first gap, 426 to 430 ms, is its longest
     * within a run. This is over it, and under the burst's 500 ms wait ([SeekBurst.WINDOW_MS]), so a hold's seek never goes
     * out between its presses. A first repeat that came later still would only start the run, and the hold, at it.
     */
    const val CEC_LIFT_MS = 450L
    /**
     * A run of one key's presses over HDMI-CEC, each within [CEC_LIFT_MS] of the up before, is a hold ([SeekHold]) from the
     * press that makes this many of them within [CEC_HOLD_WITHIN_MS] (the rule: count presses in a window, not their
     * timing); until then each goes 10 s, as a tap does. On a TV's recorded runs over HDMI-CEC every hold is one by its
     * third press (a hold of Up in a menu, three presses in 541 ms) or its fourth (880 to 1,024 ms in: its first repeat
     * comes 500 ms after the press, so the first three take 640 to 858 ms); its taps 400 ms apart, three in 739 ms at the
     * closest, never are. The window sits between the two, nearer the holds, so slower taps stay taps. Taps at about 3.3 a
     * second or faster are a hold: 40 s a second for its first 2 s, as four taps a second go, faster only kept up past
     * them. Slower ones go 10 s each however long, and so would a TV repeating slower than every 300 ms.
     */
    const val CEC_HOLD_PRESSES = 3
    const val CEC_HOLD_WITHIN_MS = 600L
    /**
     * A hold's press over HDMI-CEC this long or longer after its up ([SeekHold]), within [CEC_LIFT_MS], may be the key let
     * go and pressed again at once to nudge the target on: it goes on with the hold, but at most 10 s, as a tap does, and
     * the hold's next press, on its beat, catches up with the curve (CSK-2). Of the 147 presses in a TV's recorded holds
     * over HDMI-CEC after the one that made each a hold, 130 came at most 200 ms after the up before (mostly 180), 12
     * at 201 to 291 ms, the last of each hold among them (at most 289 ms), and 5 later (309 to 398 ms), none a hold's last:
     * those go a beat behind the curve until the next. A press again after the hold's last up, the key let go somewhere in
     * the beat after its last press and pressed again by hand, mostly comes later than this; one sooner still goes on at
     * the hold's speed.
     */
    const val CEC_NUDGE_MS = 300L
}

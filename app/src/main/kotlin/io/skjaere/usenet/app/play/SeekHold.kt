package io.skjaere.usenet.app.play

import android.view.InputDevice

/**
 * How long a remote's key has been held to seek ([ScrubRate]), from its press as it reached the player, and how far it has
 * sought, kept apart from it so it can be tested. The key's events can't always say it themselves. A TV's own remote over
 * HDMI-CEC reaches the player through Android's CEC service (AOSP's HdmiCecLocalDevice), which gives every event its own
 * time as its downTime, a held press's repeats too (NXT-1). And many TVs send a held key as a
 * press and a release over and over, about four times a second: the service passes each on as a down and an up, and
 * resets the repeat count at each release, so each down came as a press of its own, 10 s however long the key was held,
 * and never a hold.
 *
 * So presses over HDMI-CEC ([InputDevice.SOURCE_HDMI]) of one key, each within [ScrubRate.CEC_LIFT_MS] of the up before,
 * are one run; a longer gap is the key let go. Taps come the same way, and a TV's repeats don't keep one beat (CEC-2), so
 * a run is told from taps by how many presses come how soon, not by their timing: it is a hold from the press that makes
 * [ScrubRate.CEC_HOLD_PRESSES] of them within [ScrubRate.CEC_HOLD_WITHIN_MS]. Until then each press goes
 * 10 s, as a tap does. From it the hold is timed from its own first press, the one before those (its first repeat comes
 * about half a second after it), or their first where the run has none before them, and every later press of the run
 * is its next repeat, whatever the beat or the press's length; it goes as far as the taps before it and the curve say,
 * never less far than the run had gone ([stepMs]). So taps, then the key held at their pace, don't count the tapping as
 * time held (CSK-1). One that comes [ScrubRate.CEC_NUDGE_MS] or more after the up before may be the key let go and
 * pressed again at once to nudge the target: it goes 10 s at most, and the hold's next press catches up with the curve
 * (CSK-2). A hold that came with repeat counts (NXT-1) goes on the same way. An up from there is only sure to end the
 * run once the lift has passed with no press ([up]). Other remotes' keys (infrared, Bluetooth) and a keyboard's are timed
 * as before, and an up is the key let go. A key whose press didn't seek, only its repeats, is timed from its press all
 * the same, the last the player saw ([pressed], [sincePressMs]), not by the events' downTime, over HDMI-CEC each one's
 * own time.
 */
internal class SeekHold {
    /** What [heldMs] times the hold from: its press, as it reached the player; over HDMI-CEC, once a run is a hold, the hold's own first. */
    private var from = 0L
    /** The key whose up over HDMI-CEC may have ended a run of its presses, which its press again may go on with; null after any other down. */
    private var lifted: Int? = null
    /** When that up came. */
    private var liftedAt = 0L
    /** The last down's repeat count, as the player takes it. */
    private var repeats = 0
    /** When the last press went down, as it reached the player, whatever it did ([sincePressMs], [pressed]). */
    private var pressedAt = 0L
    /** The run's last presses, as many as a hold takes ([ScrubRate.CEC_HOLD_PRESSES]): when each went down, and how far the run had gone before it. */
    private val lastPresses = ArrayDeque<Press>()
    /** How far the run had gone before its hold's first press: the taps the hold goes on from. */
    private var tapped = 0L
    /** Whether the last down went on with a hold over HDMI-CEC [ScrubRate.CEC_NUDGE_MS] or more after its up: maybe a nudge. */
    private var late = false
    /** Whether the last down went on with a run over HDMI-CEC, as a tap or as the hold: the words its key's up put off are dropped. */
    var goesOn = false
        private set
    /** How far the hold has sought, from where it began ([stepMs]): from its key's press, over HDMI-CEC its run's first, taps and all. */
    private var scrubbed = 0L

    /**
     * The repeat count of [keyCode]'s down from [source] at [eventTime], [repeatCount] as it came, as the player takes
     * it: a press over HDMI-CEC that goes on with a run that is a hold is its next repeat; until the run is one, a press.
     */
    fun repeatCount(keyCode: Int, repeatCount: Int, source: Int, eventTime: Long): Int {
        goesOn = repeatCount == 0 && keyCode == lifted && fromHdmi(source) && eventTime - liftedAt <= ScrubRate.CEC_LIFT_MS
        lifted = null
        late = false
        // When the first of as many of the run's presses as a hold takes went down, this one the last of them.
        val soonest = lastPresses.getOrNull(lastPresses.size - (ScrubRate.CEC_HOLD_PRESSES - 1))?.at
        when {
            !goesOn -> {
                repeats = repeatCount
                // A press starts a seek from nothing, though it doesn't seek itself and only its repeats do (#321).
                if (repeatCount == 0) {
                    scrubbed = 0
                    tapped = 0
                    lastPresses.clear()
                }
            }
            // A hold goes on: one the run's presses made, or one that came as repeats, let go for want of the next (AOSP's
            // FOLLOWER_SAFETY_TIMEOUT).
            repeats > 0 -> {
                repeats++
                late = eventTime - liftedAt >= ScrubRate.CEC_NUDGE_MS
            }
            // Enough of the run's presses soon enough: a hold from this one, timed from its own first press, the one before
            // them, or their first.
            soonest != null && eventTime - soonest <= ScrubRate.CEC_HOLD_WITHIN_MS -> {
                val first = lastPresses.first()
                repeats = lastPresses.size
                from = first.at
                tapped = first.scrubbed
            }
            // Not yet: a tap of the run.
            else -> repeats = 0
        }
        if (repeatCount == 0) {
            pressedAt = eventTime
            lastPresses.addLast(Press(eventTime, scrubbed))
            if (lastPresses.size > ScrubRate.CEC_HOLD_PRESSES) lastPresses.removeFirst()
        }
        return repeats
    }

    /**
     * How much further the down just taken ([repeatCount]) goes as it seeks, [heldMs] into the hold as the player times it
     * ([this.heldMs], or [sincePressMs]), in a file [durationMs] long: a press as far as the controls' buttons, a run's
     * tap over HDMI-CEC as far again; a repeat what the hold added since the key's event
     * before ([ScrubRate]), on from the run's taps before the hold, never back, so the press that makes a run a hold goes
     * no less far than its taps had; a hold's press late after its up no further than a press, maybe a nudge.
     */
    fun stepMs(heldMs: Long, durationMs: Long): Long {
        val press = scrubbed + ScrubRate.offsetMs(0, durationMs)
        val held = maxOf(scrubbed, tapped + ScrubRate.offsetMs(heldMs, durationMs))
        val offset = if (repeats == 0) press else if (late) minOf(held, press) else held
        return (offset - scrubbed).also { scrubbed = offset }
    }

    /**
     * The down just taken made its key the one held to seek: the hold is timed from its press, the last ([sincePressMs]),
     * that down itself or, where only its repeats seek, the one before them (#321).
     */
    fun pressed() {
        from = pressedAt
    }

    /** How long the key held to seek has been held at [eventTime], as far as its speed goes. */
    fun heldMs(eventTime: Long): Long = eventTime - from

    /**
     * How long since the last press, at [eventTime]: how long a key whose repeats seek has been held where it isn't the
     * one held to seek, another's up lost (#321), its press the last (a key's repeats stop at another's press); over
     * HDMI-CEC too, where each event's downTime is its own time (NXT-1, CST-1).
     */
    fun sincePressMs(eventTime: Long): Long = eventTime - pressedAt

    /**
     * The up of [keyCode], the key held to seek, from [source] at [eventTime], ending its hold: how long until that is
     * sure. At once, but over HDMI-CEC, where the key pressed again within [ScrubRate.CEC_LIFT_MS] goes on with its run.
     */
    fun up(keyCode: Int, source: Int, eventTime: Long): Long {
        if (!fromHdmi(source)) return 0
        lifted = keyCode
        liftedAt = eventTime
        return ScrubRate.CEC_LIFT_MS
    }

    private fun fromHdmi(source: Int) = source and InputDevice.SOURCE_HDMI == InputDevice.SOURCE_HDMI

    /** A press of a run over HDMI-CEC: when it went down ([at]), and how far the run had gone before it ([scrubbed]). */
    private data class Press(val at: Long, val scrubbed: Long)
}

package io.skjaere.usenet.app.play

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Double-tap to seek on a phone's picture (L9 to L12), kept apart from PlayerActivity so it can be tested. The picture's
 * outer thirds, by their side on the screen whatever the layout's direction ([Zone]): a double-tap in the left one goes
 * 10 s back, in the right one 10 s on, through the controls' burst. The middle third keeps a tap's show or hide of the
 * controls, at once, and a double-tap there does nothing new.
 *
 * A single tap at the sides waits for a second (W1): its up reaches the views as a cancel ([Act.Wait]), as a pinch's
 * touch does, and the controls show or hide once the double-tap timeout has passed with none ([toggleDueInMs],
 * [timeUp]). So a double-tap leaves them as they were, with no flash and no button pressed under the second tap. A
 * second tap within that time, near the first and in its third, is a double-tap: as it lifts (its up a cancel too) the
 * picture seeks ([Act.Back], [Act.Forward]). After it, single taps in either outer third within the burst's window each
 * add 10 s their way, one burst and one seek (YouTube's way); while such a tap is down, the burst waits for it
 * ([Act.Hold]). A tap in the middle ends that, and is a tap as ever.
 *
 * Only taps that reached the picture count, not the controls' buttons, the time bar, the top bar's buttons, an overlay or
 * a panel: the player tells which ([down]'s reached). A second finger or a drag makes a touch no tap: a waiting tap
 * acts then ([Act.Toggle]), and a tap held for lets the burst go on ([Act.Settle]); so a tap and then a pinch never
 * seek. Any other touch that comes down while a tap waits has it act at once. Off, every event passing, on a TV,
 * under a side panel or "Playback stopped", before the first frame, with TalkBack's touch exploration on (its
 * double-tap is its own) and for a mouse ([Scene]); a finger, a stylus and a tool Android doesn't name count.
 */
internal class EdgeTaps(
    /** Milliseconds from a monotonic clock: the one touch events are timed by. */
    private val now: () -> Long,
    /** How far a finger may move and its touch still be a tap (Android's touch slop), in px. */
    private val touchSlop: Float,
    /** How far from the first tap a second may land and be its double (Android's double-tap slop), in px. */
    private val doubleTapSlop: Float,
    /** How soon after the first tap's up a second must come down (Android's double-tap timeout). */
    private val doubleTapMs: Long = DOUBLE_TAP_MS,
    /** How soon after a seek's tap another at a side adds to it: the burst's window ([WINDOW_MS]). */
    private val windowMs: Long = WINDOW_MS,
) {
    /** The picture's thirds, by their side on the screen: the left one goes back, the right one on. */
    enum class Zone { Back, Centre, Forward }

    /**
     * The player as a touch comes down: on a TV ([tv], the debug builds' launch extra included), under a side panel
     * ([panel]) or "Playback stopped" ([failure]), before its first frame ([pictureShown]), or with an accessibility
     * service exploring by touch ([touchExploration], TalkBack's). In any of those this policy is off.
     */
    data class Scene(
        val tv: Boolean = false,
        val panel: Boolean = false,
        val failure: Boolean = false,
        val pictureShown: Boolean = true,
        val touchExploration: Boolean = false,
    ) {
        internal val off: Boolean get() = tv || panel || failure || !pictureShown || touchExploration
    }

    /** A touch's first finger at ([x], [y]) on a picture [width] px wide, in its px; [toolType] MotionEvent's. */
    data class Touch(val x: Float, val y: Float, val width: Int, val toolType: Int = MotionEvent.TOOL_TYPE_FINGER)

    /** What the player does with a touch event; the views get a cancel in place of an up that [takesUp]. */
    enum class Act(val takesUp: Boolean = false) {
        /** Nothing of this policy's: the event goes to the views as it is. */
        Pass,

        /** A single tap at a side lifted: the controls show or hide when [toggleDueInMs] says, unless a second tap comes first. */
        Wait(takesUp = true),

        /** The tap that waited acts now, as PlayerView's own tap: the controls show or hide. The event goes to the views. */
        Toggle,

        /** A tap at a side is down while taps add up: the burst's seek waits for it. */
        Hold,

        /** That tap became a drag or a pinch: the burst's seek follows as it would have. */
        Settle,

        /** A seek's tap lifted: 10 s back. */
        Back(takesUp = true),

        /** A seek's tap lifted: 10 s on. */
        Forward(takesUp = true),
    }

    private sealed interface State {
        /** No touch of this policy's, nor any tap waiting. */
        data object Idle : State

        /** A first touch down on the picture in a side's [zone], at ([x], [y]). */
        data class First(val zone: Zone, val x: Float, val y: Float) : State

        /** That touch lifted as a tap at [upAt]: the controls show or hide unless a second comes. */
        data class Waiting(val zone: Zone, val x: Float, val y: Float, val upAt: Long) : State

        /** A second touch down near the [first] tap, within its wait, at ([x], [y]): a double-tap if it lifts as a tap. */
        data class Second(val first: Waiting, val x: Float, val y: Float) : State

        /** A seek's tap lifted at [upAt]: taps at the sides within the window add to it. */
        data class Seeking(val upAt: Long) : State

        /** A tap at a side's [zone] down at ([x], [y]) while taps add up; the burst waits for it. */
        data class Adding(val zone: Zone, val x: Float, val y: Float) : State
    }

    private var state: State = State.Idle

    /**
     * A touch's first finger down, given to the views already: whether it [reached] the picture itself (no button, bar,
     * overlay or panel took it). A tap that waits acts now unless this is its second.
     */
    fun down(touch: Touch, reached: Boolean, scene: Scene): Act {
        val was = state
        val zone = zone(touch)
        val t = now()
        val counts = zone != Zone.Centre && reached && !scene.off && touch.toolType != MotionEvent.TOOL_TYPE_MOUSE
        state = when {
            !counts -> State.Idle
            was is State.Waiting && zone == was.zone && t - was.upAt <= doubleTapMs && hypot(touch.x - was.x, touch.y - was.y) <= doubleTapSlop ->
                State.Second(was, touch.x, touch.y)
            was is State.Seeking && t - was.upAt <= windowMs -> State.Adding(zone, touch.x, touch.y)
            else -> State.First(zone, touch.x, touch.y)
        }
        return when {
            state is State.Adding -> Act.Hold
            was is State.Waiting && state !is State.Second -> Act.Toggle
            else -> Act.Pass
        }
    }

    /** The finger moved: past the touch slop, the touch is no tap. */
    fun move(touch: Touch): Act = if (moved(touch)) letGo() else Act.Pass

    /** Another finger came down: a pinch, or a grip; no tap. */
    fun pointerDown(): Act = letGo()

    /** The finger lifted: a tap, if it never went past the touch slop. */
    fun up(touch: Touch): Act {
        if (moved(touch)) return letGo()
        return when (val s = state) {
            is State.First -> {
                state = State.Waiting(s.zone, s.x, s.y, now())
                Act.Wait
            }
            is State.Second -> seek(s.first.zone)
            is State.Adding -> seek(s.zone)
            else -> Act.Pass
        }
    }

    /** Android took the touch (a system gesture): no tap. */
    fun cancel(): Act = letGo()

    /** The player's handler, as [toggleDueInMs] said: the tap that waited acts once its wait is over. */
    fun timeUp(): Act {
        val s = state as? State.Waiting ?: return Act.Pass
        if (now() - s.upAt < doubleTapMs) return Act.Pass
        state = State.Idle
        return Act.Toggle
    }

    /** How long until the tap that waits acts ([timeUp]); null while none waits. */
    fun toggleDueInMs(): Long? = (state as? State.Waiting)?.let { (it.upAt + doubleTapMs - now()).coerceAtLeast(0) }

    /** The player goes: a tap that waits, or taps adding up, are forgotten. */
    fun reset() {
        state = State.Idle
    }

    private fun zone(touch: Touch): Zone = when {
        touch.x < touch.width / 3f -> Zone.Back
        touch.x > touch.width * 2 / 3f -> Zone.Forward
        else -> Zone.Centre
    }

    /** Whether the touch down now has gone past the touch slop from where it came down. */
    private fun moved(touch: Touch): Boolean {
        val (x, y) = when (val s = state) {
            is State.First -> s.x to s.y
            is State.Second -> s.x to s.y
            is State.Adding -> s.x to s.y
            else -> return false
        }
        return hypot(touch.x - x, touch.y - y) > touchSlop
    }

    /** The touch down now is no tap: a second tap's first acts after all, and a tap held for lets the burst go on. */
    private fun letGo(): Act {
        val was = state
        if (was is State.First || was is State.Second || was is State.Adding) state = State.Idle
        return when (was) {
            is State.Second -> Act.Toggle
            is State.Adding -> Act.Settle
            else -> Act.Pass
        }
    }

    private fun seek(zone: Zone): Act {
        state = State.Seeking(now())
        return if (zone == Zone.Back) Act.Back else Act.Forward
    }

    companion object {
        /** Android's double-tap timeout as ViewConfiguration has it; the player passes in the device's own. */
        const val DOUBLE_TAP_MS = 300L

        /** How long after a seek's tap another adds to it, and the burst's seek waits: its window, widened here for touch alone if it feels tight. */
        const val WINDOW_MS = SeekBurst.WINDOW_MS
    }
}

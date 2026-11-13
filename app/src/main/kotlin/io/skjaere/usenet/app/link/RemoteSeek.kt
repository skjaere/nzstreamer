package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.SeekBurst

/**
 * The remote's seeks (L38, PtPhoneRemote): ±10 s taps in quick succession make one seek after the last, through the
 * app's player's own [SeekBurst], over where the TV last said it is. The burst hands its seek only the target, so the
 * position its first tap was given is kept, and the TV is sent how far from there ([seekBy]): it applies that to its own
 * exact position, which has moved on meanwhile. The time bar's drag moves only what it shows ([drag]); one seek to where
 * it is goes as the finger lifts ([release], [seekTo]). A drag takes the place of a burst under way, as a seek from
 * elsewhere does in the player; play or pause has the burst's seek go first ([flush]). [now] is a monotonic clock; the
 * screen calls [update] as [dueInMs] says.
 */
class RemoteSeek(now: () -> Long, private val seekBy: (Long) -> Unit, private val seekTo: (Long) -> Unit) {
    /** Where the burst under way started: the position its first tap was given. */
    private var from = 0L
    private val burst = SeekBurst(now) { target -> seekBy(target - from) }
    private var dragTo: Long? = null

    /** Whether the time bar's thumb is held. */
    val dragging: Boolean get() = dragTo != null

    /** Where the time bar shows the play: a drag's target, a burst's, else where the TV is ([positionMs]). */
    fun shown(positionMs: Long): Long = dragTo ?: burst.targetMs ?: positionMs

    /** A ±10 s tap ([stepMs], back when negative), the TV at [positionMs] of [durationMs]. */
    fun tap(stepMs: Long, positionMs: Long, durationMs: Long) {
        if (burst.targetMs == null) from = positionMs
        burst.tap(stepMs, positionMs, durationMs)
    }

    /** How long until the burst's seek goes, zero once it is due; null while none is under way. */
    fun dueInMs(): Long? = burst.dueInMs()

    /** The burst's seek goes once it is due. */
    fun update() = burst.update()

    /** Something else is sent (pause or play): the burst's seek goes first. */
    fun flush() = burst.flush()

    /** The thumb is at [toMs]: shown there, nothing sent; a burst under way gives way to it. */
    fun drag(toMs: Long) {
        burst.drop()
        dragTo = toMs
    }

    /** The drag's gesture went elsewhere: nothing is sent. */
    fun cancelDrag() {
        dragTo = null
    }

    /** The finger lifts: one seek to where the thumb is, returned; null when no drag was under way. */
    fun release(): Long? {
        val to = dragTo ?: return null
        dragTo = null
        seekTo(to)
        return to
    }
}

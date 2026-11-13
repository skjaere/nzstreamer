package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.LinkMessage

/**
 * L39: where a phone's play starts on the TV, the newer of the two places. The phone sends its own for the watch key
 * ([LinkMessage.Play.From]), with how long ago it was saved; the TV turns that age into its own clock (L25), so a TV clock
 * wrong by a constant gives the same answer, and compares it with when its own row was saved.
 */
object PlayFrom {
    /**
     * The start for the phone's [from] against the TV's own row [tv] (null without one), at [now] on the TV's clock: the
     * phone's place when it was saved after the TV's, 0 when it was watched there or is under a minute in; null for the
     * TV's own place, when that is as new or newer, or the phone sent none.
     */
    fun start(from: LinkMessage.Play.From?, tv: WatchState?, now: Long): Long? {
        from ?: return null
        // An age past the TV's clock is as old as can be; one below zero is now.
        val saved = (now - from.ageMs.coerceAtLeast(0)).coerceIn(0, now.coerceAtLeast(0))
        if (tv != null && tv.updatedAt >= saved) return null
        return if (from.watched || from.positionMs < LibraryRepository.MIN_RESUME_MS) 0 else from.positionMs
    }
}

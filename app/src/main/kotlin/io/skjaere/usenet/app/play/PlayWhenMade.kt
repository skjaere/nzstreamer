package io.skjaere.usenet.app.play

/**
 * Whether the player made next plays as it is made, or waits paused at its place with its picture shown (W106), kept apart
 * from the player so it can be tested. A phone's Play or Pause held meanwhile still wins ([HeldCommands.playWhenReady]).
 *
 * A play's first player plays: as the play starts, as another play arrives in the player (onNewIntent: Next, Previous,
 * auto-advance, Try another and Try a smaller release close the player, and their play starts in a new one), and after a
 * stop that came before any player of the play was made. Made again after the player was stopped (the screen off, in the
 * picture-in-picture window or full screen; Home or another app, then back; a TV asleep and woken) it waits, whether the
 * one before played or not, as most players do: nobody may be watching as the screen comes back. A play that had ended
 * plays again, so its end is followed as before ([EndOfPlay]) and no player waits at its very end with nothing to follow.
 * Made again for a change of dark mode, the font or the language, it plays or waits as the one before did; one a move to
 * a Cast device had paused plays here, as the change calls the move off (L124). Made again after Android let the process
 * go, it does as the saved state says: that comes after a stop, so it waits.
 */
object PlayWhenMade {
    /** A play's first player plays. */
    const val FIRST = true

    /**
     * What the next player does after the player stops, [stopped] as it was then (null: none of the play was made yet,
     * and what the next one does, [now], stands); [changingConfigurations] as Android makes the player again for a change.
     */
    fun afterStop(now: Boolean, stopped: Stopped?, changingConfigurations: Boolean): Boolean = when {
        stopped == null -> now
        stopped.ended -> true
        changingConfigurations -> stopped.playWhenReady || stopped.moving
        else -> false
    }

    /** The player as it stopped: whether it would play ([playWhenReady]), at its file's end ([ended]), paused by a Cast move under way ([moving]). */
    data class Stopped(val playWhenReady: Boolean, val ended: Boolean, val moving: Boolean = false)
}

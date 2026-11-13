package io.skjaere.usenet.app.play

/**
 * The picture-in-picture window's own buttons, kept apart from the player so they can be tested (W2, L11, L18; PipWindowMenu,
 * PipWindowStates): −10 s, Play or Pause, +10 s, the controls' own, where the window would otherwise show the media
 * session's Previous, Play/Pause and Next (an app's buttons take their place). Pause while the play plays or waits for the
 * stream to play on, Play while it is paused. At the end no Play, which would start the file again from 0:00; under
 * "Playback stopped" the seeks greyed and no Play. Never none: then Android would show the session's Previous and Next.
 */
object PipActions {
    /** A button, by the words of its title, which TalkBack reads as its description too. */
    enum class Kind(val words: String) {
        Rewind("Rewind 10 seconds"),
        Play("Play"),
        Pause("Pause"),
        FastForward("Fast-forward 10 seconds"),
    }

    /** A button the window shows: greyed while not [enabled]. */
    data class Action(val kind: Kind, val enabled: Boolean = true)

    /** The window's buttons for a play that would play ([playWhenReady]), has [ended], or [failed] ("Playback stopped"). */
    fun of(playWhenReady: Boolean, ended: Boolean, failed: Boolean): List<Action> = when {
        failed -> listOf(Action(Kind.Rewind, enabled = false), Action(Kind.FastForward, enabled = false))
        ended -> listOf(Action(Kind.Rewind), Action(Kind.FastForward))
        else -> listOf(Action(Kind.Rewind), Action(if (playWhenReady) Kind.Pause else Kind.Play), Action(Kind.FastForward))
    }
}

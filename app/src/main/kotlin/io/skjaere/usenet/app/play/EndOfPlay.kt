package io.skjaere.usenet.app.play

/**
 * What the player does as a play reaches its end, kept apart from the player so it can be tested (E1-E6). The last frame
 * is the end: when nothing will move on from it, the player leaves as its back arrow does, back to where the play started
 * (the show's page, the movie's, Home, Search; the app that opened an NZB file); in the picture-in-picture window the
 * window closes as its ✕ does, as the user is elsewhere. No wait, and nothing shown.
 *
 * The end is where the play ran out, or where a seek while playing went past it: ended while [Facts.playWhenReady].
 * Nothing moves on from it when the play isn't a show's episode, when the next episode's lookup found none, or when the
 * next-episode card was stopped (Stop, Close or Back on it) before the end or at it. While the lookup still runs, the
 * player waits for its answer; with an episode next, the card shows and auto-advance does as it always has: it counts
 * down, or waits at the end for Play now or Close (in the window held, W1). Never under "Playback stopped" (a failure
 * isn't an end), as the player leaves or hands over to another episode, once it has stopped, while a burst of rewind and
 * fast-forward taps is still to seek somewhere (it takes the player out of its end at once, ControlsPlayer, and the end
 * that follows is asked again), nor paused at the end: the time bar dragged there, the user is still there, and Play
 * replays from the start as it always has. A burst that goes nowhere (fast-forward in the file's last second) holds
 * nothing: no seek, so no end after it, would ask again.
 */
object EndOfPlay {
    /** The next episode's lookup: still running, answered with none (or nothing to look up: a film, a release, a file), or one offered. */
    enum class Next { Pending, None, Offered }

    /** What the player does now: nothing ([Stay]), nothing until the lookup answers ([Wait]), or [Leave] or [CloseWindow]. */
    enum class Step { Stay, Wait, Leave, CloseWindow }

    /**
     * The play as the player has it: [ended] and [playWhenReady] as its player reports them, the lookup's [next], whether
     * the card was [cardStopped] for this episode, [failed] while "Playback stopped" shows, [leaving] once it closes or
     * hands over to another episode, [started] until it stops, [seekToCome] while a burst's seek is still to move it,
     * and [pinned] while it is in the window.
     */
    data class Facts(
        val ended: Boolean,
        val playWhenReady: Boolean,
        val next: Next,
        val cardStopped: Boolean = false,
        val failed: Boolean = false,
        val leaving: Boolean = false,
        val started: Boolean = true,
        val seekToCome: Boolean = false,
        val pinned: Boolean = false,
    )

    fun of(facts: Facts): Step {
        val atTheEnd = facts.ended && facts.playWhenReady && !facts.failed && !facts.leaving && facts.started && !facts.seekToCome
        return when {
            !atTheEnd -> Step.Stay
            facts.cardStopped || facts.next == Next.None -> if (facts.pinned) Step.CloseWindow else Step.Leave
            facts.next == Next.Pending -> Step.Wait
            else -> Step.Stay
        }
    }
}

package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.play.EndOfPlay.Facts
import io.skjaere.usenet.app.play.EndOfPlay.Next
import io.skjaere.usenet.app.play.EndOfPlay.Step
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EndOfPlayTest {
    /** Played to its end, or sought past it while playing. */
    private val atTheEnd = Facts(ended = true, playWhenReady = true, next = Next.None)

    /** A film, a release played without TMDB numbers, an opened NZB file, or an episode TMDB has nothing after. */
    @Test
    fun `with nothing next the player leaves at the end`() {
        assertEquals(Step.Leave, EndOfPlay.of(atTheEnd))
    }

    @Test
    fun `while the next episode is looked up it waits for the answer`() {
        assertEquals(Step.Wait, EndOfPlay.of(atTheEnd.copy(next = Next.Pending)))
        assertEquals(Step.Leave, EndOfPlay.of(atTheEnd.copy(next = Next.None)), "answered with none")
    }

    /** The card shows and auto-advance does what it always has: counts down, or waits on Play now with auto-play off. */
    @Test
    fun `with a next episode offered it stays for the card`() {
        assertEquals(Step.Stay, EndOfPlay.of(atTheEnd.copy(next = Next.Offered)))
    }

    /** Stop, Close or Back on the card, before the end or at it: nothing moves on. */
    @Test
    fun `with the card stopped it leaves, whatever the lookup says`() {
        Next.entries.forEach { next ->
            assertEquals(Step.Leave, EndOfPlay.of(atTheEnd.copy(next = next, cardStopped = true)), "the card stopped, the lookup $next")
        }
    }

    /**
     * Not under "Playback stopped" (a failure isn't an end), as the player leaves or hands over, once it has stopped, while
     * a burst of rewind and fast-forward taps is still to seek somewhere, nor paused at the end (the time bar dragged there).
     */
    @Test
    fun `failed, leaving, stopped, seeking or paused at the end, it stays`() {
        mapOf(
            "under Playback stopped" to atTheEnd.copy(failed = true),
            "leaving, switched or finishing" to atTheEnd.copy(leaving = true),
            "below STARTED" to atTheEnd.copy(started = false),
            "a burst's seek to come" to atTheEnd.copy(seekToCome = true),
            "paused at the end" to atTheEnd.copy(playWhenReady = false),
        ).forEach { (case, facts) ->
            Next.entries.forEach { next ->
                assertEquals(Step.Stay, EndOfPlay.of(facts.copy(next = next)), "$case, the lookup $next")
                assertEquals(Step.Stay, EndOfPlay.of(facts.copy(next = next, cardStopped = true)), "$case, the lookup $next, the card stopped")
                assertEquals(Step.Stay, EndOfPlay.of(facts.copy(next = next, pinned = true)), "$case, the lookup $next, in the window")
            }
        }
    }

    @Test
    fun `before the end it stays`() {
        listOf(true, false).forEach { playWhenReady ->
            Next.entries.forEach { next ->
                listOf(false, true).forEach { stopped ->
                    val facts = Facts(ended = false, playWhenReady = playWhenReady, next = next, cardStopped = stopped)
                    assertEquals(Step.Stay, EndOfPlay.of(facts), "$facts")
                    assertEquals(Step.Stay, EndOfPlay.of(facts.copy(pinned = true)), "$facts, in the window")
                }
            }
        }
    }

    /** E4: the window closes as its own ✕ does; an episode with a next one waits there, its card held (W1). */
    @Test
    fun `in the window it closes instead, and still waits for the lookup or the card`() {
        val pinned = atTheEnd.copy(pinned = true)
        assertEquals(Step.CloseWindow, EndOfPlay.of(pinned))
        assertEquals(Step.CloseWindow, EndOfPlay.of(pinned.copy(next = Next.Offered, cardStopped = true)), "the card stopped")
        assertEquals(Step.Wait, EndOfPlay.of(pinned.copy(next = Next.Pending)), "the lookup running")
        assertEquals(Step.Stay, EndOfPlay.of(pinned.copy(next = Next.Offered)), "the card held")
    }
}

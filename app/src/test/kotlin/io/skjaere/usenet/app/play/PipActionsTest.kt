package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.play.PipActions.Action
import io.skjaere.usenet.app.play.PipActions.Kind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipActionsTest {
    /** PipWindowMenu, playing: −10 s, Pause, +10 s, in the words TalkBack reads, its title's. */
    @Test
    fun `playing, the window offers the app's own rewind, pause and fast-forward`() {
        val shown = PipActions.of(playWhenReady = true, ended = false, failed = false)
        assertEquals(listOf(Action(Kind.Rewind), Action(Kind.Pause), Action(Kind.FastForward)), shown)
        assertEquals(listOf("Rewind 10 seconds", "Pause", "Fast-forward 10 seconds"), shown.map { it.kind.words })
    }

    /** Paused, Play in the middle. (Waiting for the stream to play on, the play plays as far as the user is concerned: Pause.) */
    @Test
    fun `paused, play takes pause's place`() {
        val shown = PipActions.of(playWhenReady = false, ended = false, failed = false)
        assertEquals(listOf(Action(Kind.Rewind), Action(Kind.Play), Action(Kind.FastForward)), shown)
        assertEquals("Play", shown[1].kind.words)
    }

    /** PipWindowStates, an episode's end: no Play, which would start it again from 0:00 (L11); the seeks stay. */
    @Test
    fun `at the end there is no play`() {
        listOf(true, false).forEach { playWhenReady ->
            assertEquals(listOf(Action(Kind.Rewind), Action(Kind.FastForward)), PipActions.of(playWhenReady, ended = true, failed = false))
        }
    }

    /**
     * PipWindowStates, a failure: −10 s and +10 s greyed, no Play. With no buttons of its own the window would show the
     * media session's Previous and Next instead.
     */
    @Test
    fun `under Playback stopped the seeks are greyed and there is no play`() {
        listOf(true, false).forEach { playWhenReady ->
            listOf(true, false).forEach { ended ->
                assertEquals(listOf(Action(Kind.Rewind, enabled = false), Action(Kind.FastForward, enabled = false)), PipActions.of(playWhenReady, ended, failed = true))
            }
        }
    }

    /** Android shows three of an app's buttons; with none it shows the media session's own. */
    @Test
    fun `never more than three buttons, never none`() {
        listOf(true, false).forEach { a -> listOf(true, false).forEach { b -> listOf(true, false).forEach { c -> assertTrue(PipActions.of(a, b, c).size in 2..3, "$a $b $c") } } }
    }
}

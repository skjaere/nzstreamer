package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * W106: whether the player made next plays as it is made, or waits paused at its place. A play's first player plays,
 * and so does one after a stop that came before any player of the play was made; one made again after a stop waits,
 * played or not, unless its play had ended; one made again for a change of configuration plays or waits as the one
 * before did, or plays where a Cast move had paused it. A phone's Play or Pause held meanwhile still wins.
 */
class PlayWhenMadeTest {
    private val playing = PlayWhenMade.Stopped(playWhenReady = true, ended = false)
    private val paused = PlayWhenMade.Stopped(playWhenReady = false, ended = false)

    /**
     * A new play arriving in the player (onNewIntent) starts afresh with this, which the player sets as the play comes:
     * PausedOnReturnDeviceTest.aNewPlayAfterAStopPlays says it plays after a stop.
     */
    @Test
    fun `a play's first player plays`() {
        assertEquals(true, PlayWhenMade.FIRST)
    }

    @Test
    fun `made again after a stop, playing or paused, it waits`() {
        assertEquals(false, PlayWhenMade.afterStop(PlayWhenMade.FIRST, playing, changingConfigurations = false))
        assertEquals(false, PlayWhenMade.afterStop(PlayWhenMade.FIRST, paused, changingConfigurations = false))
        assertEquals(false, PlayWhenMade.afterStop(PlayWhenMade.FIRST, playing.copy(moving = true), changingConfigurations = false), "a move's pause too")
    }

    @Test
    fun `a stop during the setup, before any player of the play, leaves it as it was`() {
        assertEquals(true, PlayWhenMade.afterStop(PlayWhenMade.FIRST, null, changingConfigurations = false), "as the first one would")
        assertEquals(false, PlayWhenMade.afterStop(false, null, changingConfigurations = false), "after a player of the play was made and stopped")
        assertEquals(true, PlayWhenMade.afterStop(PlayWhenMade.FIRST, null, changingConfigurations = true))
        assertEquals(false, PlayWhenMade.afterStop(false, null, changingConfigurations = true))
    }

    /**
     * Android saves the state a process let go is made again from as the player stops: after the stop from Android 9 (from
     * what the stop said), before it on Android 8 (from the player still there). Either way it says wait, unless the play
     * had ended, whether the player played or not: the screen comes back to a play nobody may be watching, as after any
     * other stop.
     */
    @Test
    fun `made again after Android let the process go, it waits as the stop said, unless the play had ended`() {
        listOf(playing, paused).forEach { was ->
            val stopped = PlayWhenMade.afterStop(PlayWhenMade.FIRST, was, changingConfigurations = false)
            assertEquals(false, PlayWhenMade.afterStop(stopped, null, changingConfigurations = false), "saved after the stop, playWhenReady ${was.playWhenReady}")
            assertEquals(false, PlayWhenMade.afterStop(PlayWhenMade.FIRST, was, changingConfigurations = false), "saved before it, playWhenReady ${was.playWhenReady}")
        }
        val ended = PlayWhenMade.Stopped(playWhenReady = false, ended = true)
        assertEquals(true, PlayWhenMade.afterStop(PlayWhenMade.afterStop(false, ended, changingConfigurations = false), null, changingConfigurations = false), "ended, saved after the stop")
        assertEquals(true, PlayWhenMade.afterStop(false, ended, changingConfigurations = false), "ended, saved before it")
    }

    @Test
    fun `a play that had ended plays, so its end is followed as before`() {
        listOf(true, false).forEach { playWhenReady ->
            listOf(true, false).forEach { change ->
                val ended = PlayWhenMade.Stopped(playWhenReady, ended = true)
                assertEquals(true, PlayWhenMade.afterStop(false, ended, changingConfigurations = change), "playWhenReady $playWhenReady, a change $change")
            }
        }
    }

    @Test
    fun `made again for a change of configuration, it plays or waits as it did`() {
        assertEquals(true, PlayWhenMade.afterStop(false, playing, changingConfigurations = true))
        assertEquals(false, PlayWhenMade.afterStop(PlayWhenMade.FIRST, paused, changingConfigurations = true))
    }

    /** L124: the change calls the move off, and the film plays on here from where the move paused it. */
    @Test
    fun `paused by a Cast move the change calls off, it plays here`() {
        assertEquals(true, PlayWhenMade.afterStop(false, paused.copy(moving = true), changingConfigurations = true))
    }

    /** Play on TV's L8: what a phone asked for during the setup is what the player does. */
    @Test
    fun `a phone's Play or Pause held during the setup wins`() {
        val held = HeldCommands()
        assertEquals(false, held.playWhenReady(otherwise = PlayWhenMade.afterStop(PlayWhenMade.FIRST, playing, changingConfigurations = false)))
        held.resume()
        assertEquals(true, held.playWhenReady(otherwise = PlayWhenMade.afterStop(PlayWhenMade.FIRST, playing, changingConfigurations = false)))
        held.pause()
        assertEquals(false, held.playWhenReady(otherwise = PlayWhenMade.FIRST))
    }
}

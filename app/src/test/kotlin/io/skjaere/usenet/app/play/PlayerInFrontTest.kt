package io.skjaere.usenet.app.play

import io.skjaere.usenet.link.Playback
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L8, L34: the player in front, and a launch on its way to one. The launch stays on its way until the player that takes it
 * says what it plays, so the play is never over in between; another play starting, or the launch dropped, ends it. And
 * the player's state as a phone is told it: paused at the end of its file, as its session goes on until it leaves.
 */
class PlayerInFrontTest {
    private class Handle(override val remote: RemotePlay?) : PlayerHandle {
        override val durationMs: Long? = null
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun stop() = Unit
        override fun handOver(play: AskedPlay, origin: PlayOrigin) = Unit
    }

    private val session = RemotePlay("s-1", "ab".repeat(32), "Galaxy phone")

    private fun said(remote: RemotePlay?) = NowPlaying(Playback.Buffering, 0, null, "Harbor Bell", null, "movie:900031", null, remote)

    @Test
    fun `a launch stays on its way until its player says what it plays`() {
        val front = PlayerInFront()
        front.launched(session)
        val player = Handle(session)
        front.started(player)
        assertEquals(Launching(session), front.launching.value, "started, not yet saying")
        front.said(player, said(session))
        assertNull(front.launching.value)
        assertEquals(said(session), front.now.value)
    }

    @Test
    fun `a player saying another play leaves the launch on its way, and another play or a drop ends it`() {
        val front = PlayerInFront()
        val window = Handle(null)
        front.started(window)
        front.launched(session)
        front.said(window, said(null))
        assertEquals(Launching(session), front.launching.value)
        front.notLaunching()
        assertNull(front.launching.value)
    }

    @Test
    fun `the player's state as a phone is told it`() {
        fun state(failed: Boolean = false, made: Boolean = true, ended: Boolean = false, isPlaying: Boolean = false, playWhenReady: Boolean = true) =
            NowPlaying.state(failed, made, ended, isPlaying, playWhenReady)
        assertEquals(Playback.Failed, state(failed = true, ended = true), "Playback stopped shows")
        assertEquals(Playback.Buffering, state(made = false), "the player not made yet")
        assertEquals(Playback.Playing, state(isPlaying = true))
        assertEquals(Playback.Paused, state(playWhenReady = false))
        assertEquals(Playback.Buffering, state(), "waiting to play")
    }

    @Test
    fun `at the end of its file the player is paused there, its session going on until it leaves`() {
        // The next-episode card waits at the end for Play now (auto-play off, or nothing found ahead), or the next episode
        // is about to start in the session: stopped is the session's end alone (L33, L34), told once the player has left.
        assertEquals(Playback.Paused, NowPlaying.state(failed = false, made = true, ended = true, isPlaying = false, playWhenReady = true))
        assertEquals(Playback.Paused, NowPlaying.state(failed = false, made = true, ended = true, isPlaying = false, playWhenReady = false))
    }
}

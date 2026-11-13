package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L8: Play on TV's commands that reach a player before it is made (its setup reads its settings, and on a TV may wait for
 * the screen's frame rate) are held, and the player takes them as it is made: paused or playing as last asked, from a
 * SeekTo's place moved by any SeekBy since, or from its own start moved by them.
 */
class HeldCommandsTest {
    @Test
    fun `with nothing held the player starts where it would, playing`() {
        val held = HeldCommands()
        assertEquals(true, held.playWhenReady())
        assertEquals(1_930_000L, held.startAt(1_930_000))
    }

    @Test
    fun `the last Pause or Resume asked says whether it plays`() {
        val held = HeldCommands()
        held.pause()
        assertEquals(false, held.playWhenReady())
        held.resume()
        assertEquals(true, held.playWhenReady())
        held.pause()
        assertEquals(false, held.playWhenReady())
    }

    @Test
    fun `a SeekTo's place, moved by each SeekBy since, never before the start of the file`() {
        val held = HeldCommands()
        held.seekBy(10_000)
        held.seekBy(10_000)
        assertEquals(80_000L, held.startAt(60_000), "from its own start")
        held.seekTo(120_000)
        assertEquals(120_000L, held.startAt(60_000), "a SeekTo takes the place of the SeekBys before it")
        held.seekBy(30_000)
        assertEquals(150_000L, held.startAt(60_000))
        held.seekBy(-400_000)
        assertEquals(0L, held.startAt(60_000))
    }

    @Test
    fun `taken once, so the next player made starts afresh`() {
        val held = HeldCommands()
        held.pause()
        held.seekTo(120_000)
        held.clear()
        assertEquals(true, held.playWhenReady())
        assertEquals(5_000L, held.startAt(5_000))
    }
}

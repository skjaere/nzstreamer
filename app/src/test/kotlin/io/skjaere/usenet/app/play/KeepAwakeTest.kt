package io.skjaere.usenet.app.play

import androidx.media3.common.Player
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAwakeTest {
    @Test
    fun `the screen stays on while playback runs or is about to`() {
        assertTrue(KeepAwake.of(playWhenReady = true, state = Player.STATE_READY, failed = false))
        assertTrue(KeepAwake.of(playWhenReady = true, state = Player.STATE_BUFFERING, failed = false))
    }

    /** Paused, a phone may sleep and a TV show its screensaver (TV-BU, TV-BY). */
    @Test
    fun `paused, ended, idle or failed, it may go off`() {
        listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED, Player.STATE_IDLE).forEach { state ->
            assertFalse(KeepAwake.of(playWhenReady = false, state = state, failed = false), "paused in $state")
        }
        assertFalse(KeepAwake.of(playWhenReady = true, state = Player.STATE_ENDED, failed = false))
        assertFalse(KeepAwake.of(playWhenReady = true, state = Player.STATE_IDLE, failed = false))
        listOf(Player.STATE_READY, Player.STATE_BUFFERING).forEach { state ->
            assertFalse(KeepAwake.of(playWhenReady = true, state = state, failed = true), "failed in $state")
        }
    }
}

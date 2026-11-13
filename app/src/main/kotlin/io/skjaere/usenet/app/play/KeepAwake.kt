package io.skjaere.usenet.app.play

import androidx.media3.common.Player

/**
 * Whether the player keeps the screen on: only while playback runs or is about to. Paused,
 * ended or stopped by the "Playback stopped" panel, a phone may sleep and a TV show its
 * screensaver (TV-BU, TV-BY).
 */
object KeepAwake {
    fun of(playWhenReady: Boolean, state: Int, failed: Boolean): Boolean =
        !failed && playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
}

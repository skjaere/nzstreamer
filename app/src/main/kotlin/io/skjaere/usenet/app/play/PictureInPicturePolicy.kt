package io.skjaere.usenet.app.play

import androidx.media3.common.Player

/**
 * When the player goes into picture-in-picture as the user leaves a playing film (Home, Recents, another app), kept
 * apart from the player so it can be tested (W3, L1, L2).
 *
 * Only on phones and tablets that have Android's picture-in-picture: never on a TV, where Google's guidelines forbid a
 * film in the window and it would fight the TV's frame-rate matching (W4). And only while the play plays, or buffers to
 * play, a picture already shown: paused or ended, Home stops the player as it always has (the top bar's button still
 * opens the window then); nor during the setup or before the first frame, with "Playback stopped" up, as the player
 * closes or hands over to another episode, or while a TV switches its frame rate. From Android 12 Android opens the
 * window itself as the user leaves, the player keeping it told whether it may ([Arming.autoEnter]); before that the
 * player asks for it as the user leaves ([Arming.onLeaveHint]), by the same rules.
 */
object PictureInPicturePolicy {
    /** The first Android, 12, that opens the window itself as the user leaves (setAutoEnterEnabled). */
    const val AUTO_ENTER_SDK = 31

    /** Whether the player offers the window at all: the device has Android's picture-in-picture, and isn't a TV. */
    fun available(hasFeature: Boolean, television: Boolean): Boolean = hasFeature && !television

    /**
     * The play as the player has it: [playWhenReady] and [state] as its player reports them, [pictureShown] once its first
     * frame has, [failed] while "Playback stopped" shows, [leaving] once it closes or hands over to another episode,
     * [matching] while a TV switches its frame rate before the player is made.
     */
    data class Scene(
        val playWhenReady: Boolean,
        val state: Int,
        val pictureShown: Boolean,
        val failed: Boolean = false,
        val leaving: Boolean = false,
        val matching: Boolean = false,
    )

    /** How the window opens as the user leaves now: by Android itself ([autoEnter]), or asked for by the player ([onLeaveHint]); with neither, it doesn't. */
    data class Arming(val autoEnter: Boolean = false, val onLeaveHint: Boolean = false)

    /** The window as the user leaves the play [scene] (null: no player yet) on Android [sdk], where it is [available]. */
    fun arming(available: Boolean, sdk: Int, scene: Scene?): Arming {
        val armed = available && scene != null && scene.playWhenReady && scene.pictureShown && !scene.failed && !scene.leaving && !scene.matching &&
            (scene.state == Player.STATE_READY || scene.state == Player.STATE_BUFFERING)
        return Arming(autoEnter = armed && sdk >= AUTO_ENTER_SDK, onLeaveHint = armed && sdk < AUTO_ENTER_SDK)
    }
}

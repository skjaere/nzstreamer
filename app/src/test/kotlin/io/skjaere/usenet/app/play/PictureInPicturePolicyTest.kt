package io.skjaere.usenet.app.play

import androidx.media3.common.Player
import io.skjaere.usenet.app.play.PictureInPicturePolicy.Arming
import io.skjaere.usenet.app.play.PictureInPicturePolicy.Scene
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureInPicturePolicyTest {
    private val playing = Scene(playWhenReady = true, state = Player.STATE_READY, pictureShown = true)
    private val byAndroid = Arming(autoEnter = true)
    private val byThePlayer = Arming(onLeaveHint = true)
    private val none = Arming()

    @Test
    fun `a film playing, or buffering to play, opens the window as the user leaves it`() {
        listOf(31, 34, 36).forEach { sdk ->
            assertEquals(byAndroid, PictureInPicturePolicy.arming(available = true, sdk, playing), "playing on $sdk")
            assertEquals(byAndroid, PictureInPicturePolicy.arming(available = true, sdk, playing.copy(state = Player.STATE_BUFFERING)), "buffering on $sdk")
        }
    }

    /** Home then stops the player, as before the window; the top bar's button still opens it while paused. */
    @Test
    fun `paused or ended, it doesn't`() {
        listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED, Player.STATE_IDLE).forEach { state ->
            assertEquals(none, PictureInPicturePolicy.arming(available = true, 34, playing.copy(playWhenReady = false, state = state)), "paused in $state")
        }
        assertEquals(none, PictureInPicturePolicy.arming(available = true, 34, playing.copy(state = Player.STATE_ENDED)), "ended")
        assertEquals(none, PictureInPicturePolicy.arming(available = true, 34, playing.copy(state = Player.STATE_IDLE)), "idle")
    }

    @Test
    fun `not during the setup, before the first frame, with Playback stopped up, while leaving or during a TV's switch`() {
        assertEquals(none, PictureInPicturePolicy.arming(available = true, 34, scene = null), "no player yet")
        mapOf(
            "before the first frame" to playing.copy(pictureShown = false),
            "with Playback stopped up" to playing.copy(failed = true),
            "leaving or handing over" to playing.copy(leaving = true),
            "while the TV switches its rate" to playing.copy(matching = true),
        ).forEach { (case, scene) ->
            assertEquals(none, PictureInPicturePolicy.arming(available = true, 34, scene), case)
            assertEquals(none, PictureInPicturePolicy.arming(available = true, 30, scene), "$case, Android 11")
        }
    }

    /** Android 8 to 11 never enter by themselves: the player asks as the user leaves (onUserLeaveHint), by the same rules. */
    @Test
    fun `before Android 12 the player asks for it as the user leaves`() {
        listOf(26, 28, 30).forEach { sdk ->
            assertEquals(byThePlayer, PictureInPicturePolicy.arming(available = true, sdk, playing), "playing on $sdk")
            assertEquals(none, PictureInPicturePolicy.arming(available = true, sdk, playing.copy(playWhenReady = false)), "paused on $sdk")
        }
    }

    /** A TV never: Google's guidelines forbid a film in the window there (W4); and Android 8.0 throws for a device without it. */
    @Test
    fun `without Android's picture-in-picture or on a TV, never`() {
        assertTrue(PictureInPicturePolicy.available(hasFeature = true, television = false))
        assertFalse(PictureInPicturePolicy.available(hasFeature = false, television = false), "no picture-in-picture")
        assertFalse(PictureInPicturePolicy.available(hasFeature = true, television = true), "a TV")
        listOf(26, 30, 31, 36).forEach { sdk ->
            assertEquals(none, PictureInPicturePolicy.arming(available = false, sdk, playing), "unavailable on $sdk")
        }
    }
}

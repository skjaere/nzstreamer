package io.skjaere.usenet.app.link

import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

// The paths below are Google's Material icons (github.com/google/material-design-icons, Apache License 2.0):
// tv and smartphone (outlined), cast, cast_connected, pause, stop, speaker, volume_up and subtitles.
// THIRD_PARTY_NOTICES.md lists them.

/** Material's outlined "tv", which the icons the app ships (the core set) don't have (L9): Play on TV's mark, never Cast's. */
internal val TvIcon: ImageVector by lazy { outlined("Outlined.Tv", TV) }

/** Material's outlined "smartphone", which the core set doesn't have either (L9). */
internal val PhoneIcon: ImageVector by lazy { outlined("Outlined.Smartphone", PHONE) }

/**
 * Google's Cast icon at rest and connected (L113), as Material's cast and cast_connected draw it: the Cast button's and the
 * Cast devices' rows' alone, never NZStreamer's own TVs', which keep the TV (L9). The paths are Material's, under the
 * Apache License 2.0. The icon itself is a Google brand feature, shown as Google's Cast terms ask; no licence here covers it.
 */
internal val CastIcon: ImageVector by lazy { outlined("Filled.Cast", CAST) }
internal val CastConnectedIcon: ImageVector by lazy { outlined("Filled.CastConnected", CAST_CONNECTED) }

/** Material's "pause" and "stop", for the Playing on bar and the remote: the core set has neither (L9). */
internal val PauseIcon: ImageVector by lazy { outlined("Filled.Pause", PAUSE) }
internal val StopIcon: ImageVector by lazy { outlined("Filled.Stop", STOP) }

/** Material's "speaker", for a Cast speaker's and a speaker group's row (W50, L122): the core set doesn't have it. */
internal val SpeakerIcon: ImageVector by lazy { outlined("Filled.Speaker", SPEAKER) }

/** Material's "volume_up" and "subtitles", for the lines a cast says before it plays (CsPhonePrecheck): the core set has neither. */
internal val SoundIcon: ImageVector by lazy { outlined("Filled.VolumeUp", VOLUME_UP) }
internal val SubtitlesIcon: ImageVector by lazy { outlined("Filled.Subtitles", SUBTITLES) }

/** [path], Material's own on its 24 dp grid, as an icon its tint colours, as the core set's are drawn. */
private fun outlined(name: String, path: String): ImageVector = materialIcon(name) {
    addPath(addPathNodes(path), fill = SolidColor(Color.Black))
}

private const val TV = "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"
private const val PHONE = "M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 21H7v-1h10v1zm0-3H7V6h10v12zM7 4V3h10v1H7z"

/**
 * Google's Cast icon in its parts, Material's cast taken apart for the connecting frames: the three waves from the corner
 * out, which light in turn while the button connects (L113), and the screen's frame.
 */
internal val CAST_WAVES = listOf(
    "M1,18 L1,21 L4,21 C4,19.34 2.66,18 1,18 L1,18 Z",
    "M1,14 L1,16 C3.76,16 6,18.24 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z",
    "M1,10 L1,12 C5.97,12 10,16.03 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z",
)
internal const val CAST_FRAME = "M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 " +
    "L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"

/** Material's cast, the icon at rest: its waves and frame in one, the frame last. */
internal const val CAST = "M1,18 L1,21 L4,21 C4,19.34 2.66,18 1,18 L1,18 Z M1,14 L1,16 C3.76,16 6,18.24 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z " +
    "M1,10 L1,12 C5.97,12 10,16.03 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 " +
    "L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"

/** Material's cast_connected: the waves, the screen filled, the frame. */
private const val CAST_CONNECTED = "M1,18 L1,21 L4,21 C4,19.34 2.66,18 1,18 L1,18 Z M1,14 L1,16 C3.76,16 6,18.24 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z " +
    "M19,7 L5,7 L5,8.63 C8.96,9.91 12.09,13.04 13.37,17 L19,17 L19,7 L19,7 Z M1,10 L1,12 C5.97,12 10,16.03 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z " +
    "M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"

private const val SPEAKER = "M17 2H7c-1.1 0-2 .9-2 2v16c0 1.1.9 1.99 2 1.99L17 22c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-5 2c1.1 0 2 .9 2 2s-.9 2-2 2" +
    "c-1.11 0-2-.9-2-2s.89-2 2-2zm0 16c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"
private const val PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
private const val STOP = "M6 6h12v12H6z"
private const val VOLUME_UP = "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 " +
    "6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
private const val SUBTITLES = "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2z" +
    "m0-4H10v-2h10v2z"

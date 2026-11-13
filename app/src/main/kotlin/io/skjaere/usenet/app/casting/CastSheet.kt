package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.RowEnd
import io.skjaere.usenet.app.link.RowLine

// The Play on sheet's Cast group (L78, L95, L112; CsPhoneSheet, CsPhoneConnecting, CsPhoneLookFor): its words, and each
// device's row by the session now; and the words of the connected device's block above the list (L117, L119;
// CsPhoneSheetConnected). Drawn by the cast package, which has Google's routes, and by Play on's list; decided here, where
// a JVM test reads it.

/** The group's heading (W47): "Play on" heads the whole list. */
const val CAST_DEVICES = "Cast to"

/** Under the group's heading, once for every device, whatever it plays. */
const val CAST_SIMPLE = "Simple files only: some releases play without sound or subtitles"
const val CAST_LOOKING = "Looking for Cast devices…"

/** The opt-in (W46, L112; CsPhoneLookFor): the Cast group's one row until it is tapped, its line, and its Privacy link. */
const val CAST_LOOK_FOR = "Look for Cast devices"
const val CAST_LOOK_LINE =
    "Uses Google’s Cast software, which sends Google data about finding and using Cast devices, this phone and this app, also while NZStreamer is closed."
const val CAST_PRIVACY = "Privacy"

/** What TalkBack says once, as the opt-in's tap starts the looking (CsPhoneTalkBackC2 3). */
const val CAST_LOOKING_SAID = "Looking for Cast devices"

/** After [CastTimings.noneFoundMillis] with none found (W34). */
const val CAST_NONE_FOUND = "No Cast devices found. They show here when on the same Wi-Fi as this phone."

/** The group's one row when Cast can't start here (L50): no Play services, or an old one. */
const val CAST_UNAVAILABLE = "Cast isn't available on this phone"
const val CAST_UNAVAILABLE_WHY = "Google Play services is missing or out of date."
const val CAST_CONNECTING = "Connecting…"

/** The connected device's line before anything plays there (L97); then what plays ([playingThere]). */
const val CAST_CONNECTED = "Connected"
const val CAST_COULD_NOT_CONNECT = "Couldn't connect. Tap to try again."
const val STOP_CASTING = "Stop casting"

/** The block's button while another phone's or app's film plays there (W49, L119): this phone alone leaves. */
const val DISCONNECT = "Disconnect"

/** Under the block's volume while another phone's film plays there (CsPhoneSheetConnected 3). */
const val CAST_FROM_ANOTHER = "Cast from another phone. Disconnect leaves it playing there."

/** What TalkBack says after a Cast row's name: it tells a TV listed in both groups apart (L95). */
const val CAST_DEVICE = "Cast device"

/** A Cast row's line at the address of one of your TVs with NZStreamer (L121): "Also runs NZStreamer", and ": Living room TV". */
const val CAST_ALSO_RUNS = "Also runs NZStreamer"

/** A speaker's and a speaker group's line (W50, L122). */
const val CAST_SPEAKER_LINE = "Speaker · sound only"
const val CAST_GROUP_LINE = "Speaker group · sound only"

/** Under Play while the chosen Cast device connects, or reconnects (L97). */
fun connectingTo(name: String) = "Connecting to $name…"

/** The connected device's line while a cast play lives there (L78; CsPhoneSheet): "Playing Harbor Lights · S02E04". */
fun playingThere(label: String) = "Playing $label"

/** The block's line for another sender's film that gave no title (L149): "Playing on Kitchen display". */
fun playingOn(name: String) = "Playing on $name"

/** What TalkBack says once, as a tap on [name]'s row connects (L95). */
fun connectingAnnouncement(name: String) = "Connecting to $name"

/** What TalkBack says once, as [name] connects and the sheet closes (L95). */
fun connectedAnnouncement(name: String) = "Connected to $name"

/** Stop casting, to TalkBack (CsPhoneSheetConnected, CsPhoneRemote): "Stop casting to Kitchen display". */
fun stopCastingTo(name: String) = "Stop casting to $name"

/** Disconnect, to TalkBack (CsPhoneSheetConnected): "Disconnect from Kitchen display". */
fun disconnectFrom(name: String) = "Disconnect from $name"

/**
 * A Cast device's row: its [line] under the name, what sits at its end ([end]), whether it is the choice ([chosen]: This
 * phone is unticked), and whether its session is [live] (Google's filled Cast icon, in the accent).
 */
data class CastRowLook(val line: RowLine?, val end: RowEnd, val chosen: Boolean, val live: Boolean)

/**
 * The row of the device at [route] (L78), with its own [description] ("Casting Default Media Receiver" while another
 * sender uses it), by the session [now] and whether its last connect [failed]: connecting, with a spinner where the ✓
 * goes; connected, ✓ and "Connected" in green, or what plays there ([playing], its bar's title) while a cast play lives;
 * reconnecting, ✓ and connecting again; refused or not connected in time, the warning, a tap away from another try;
 * otherwise its own text, muted. Stop casting is the block's, above the list (L117).
 */
fun castRowLook(route: String, description: String?, now: CastLinkNow?, failed: Boolean, playing: String? = null): CastRowLook {
    val own = description?.takeIf { it.isNotBlank() }?.let { RowLine(it, RowLine.Tone.Muted) }
    if (now?.route != route) {
        return if (failed) CastRowLook(RowLine(CAST_COULD_NOT_CONNECT, RowLine.Tone.Warning), RowEnd.None, chosen = false, live = false)
        else CastRowLook(own, RowEnd.None, chosen = false, live = false)
    }
    return when (now.link) {
        CastLink.Connecting -> CastRowLook(RowLine(CAST_CONNECTING, RowLine.Tone.Muted), RowEnd.Spinner, chosen = true, live = false)
        CastLink.Connected -> CastRowLook(RowLine(playing?.let(::playingThere) ?: CAST_CONNECTED, RowLine.Tone.Good), RowEnd.None, chosen = true, live = true)
        CastLink.Reconnecting -> CastRowLook(RowLine(CAST_CONNECTING, RowLine.Tone.Muted), RowEnd.None, chosen = true, live = true)
    }
}

/** A Cast row to TalkBack (L95): "Den TV, Cast device, Casting Default Media Receiver", then its role and whether it is chosen. */
fun castRowDescription(name: String, line: RowLine?): String = listOfNotNull(name, CAST_DEVICE, line?.words).joinToString(", ")

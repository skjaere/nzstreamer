package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PeerNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How this phone's Cast session with a device is (L77): connecting, from a tap on its row until Google's software says
 * the session started; connected; or reconnecting, while the session is suspended (the phone's Wi-Fi gone a moment, L91).
 */
enum class CastLink { Connecting, Connected, Reconnecting }

/**
 * The Cast session now, as castExtras' connection says it (L77): the device's MediaRouter [route], its [name] and how the
 * session is ([link]), and what its route said of the device ([device], L153), null where none was read. None while
 * there is no session. It prints without its name or the device's facts.
 */
data class CastLinkNow(val route: String, val name: String, val link: CastLink, val device: CastDeviceFacts? = null) {
    override fun toString() = "CastLinkNow($link)"
}

/** What a Cast device is (L153): one that shows video, a speaker that plays only the sound, or a speaker group. */
enum class CastDeviceKind { Video, Speaker, Group }

/**
 * What a Cast device's route says of it (L153), so a play goes with its device's profile: its Cast device [id] (what its
 * session names; null where its route names none), its [model] as the device gives it, its IPv4 [address], its [kind],
 * and whether its route says it keeps its volume fixed ([fixedVolume]: MediaRouter's PLAYBACK_VOLUME_FIXED), which only
 * the log says: the Shield's route says so while its volume moves (M1-A), so the slider goes by what the device does. The
 * model and the address come from the network; it prints its kind alone.
 */
data class CastDeviceFacts(val id: String?, val model: String?, val address: String?, val kind: CastDeviceKind, val fixedVolume: Boolean) {
    override fun toString() = "CastDeviceFacts($kind)"
}

/**
 * The log's line as a session starts (L153; O4 and M1 read it): the device's model, its kind and what its route says of
 * its volume ("route volume fixed" or "variable", M1-A), never its name or address. Any device can set the model, so it
 * is cleaned as a peer's name is, to one line of at most 40 characters, and a blank one reads "(unknown)".
 */
fun castConnectedLine(model: String?, kind: CastDeviceKind, fixed: Boolean): String =
    "cast: connected to a ${PeerNames.clean(model, "(unknown)")} (${kind.name.lowercase()}, route volume ${if (fixed) "fixed" else "variable"})"

/** No Cast session, ever: a TV's (L67), and a build's without Cast's extras (L105). */
val NoCast: StateFlow<CastLinkNow?> = MutableStateFlow<CastLinkNow?>(null).asStateFlow()

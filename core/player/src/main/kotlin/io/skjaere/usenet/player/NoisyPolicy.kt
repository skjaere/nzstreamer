package io.skjaere.usenet.player

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo

/**
 * What a play does as Android says its audio is "becoming noisy" (ACTION_AUDIO_BECOMING_NOISY). Android sends it as the
 * output the music plays on goes away, HDMI included, while that is the only one of its "noisy" kinds connected (AOSP's
 * AudioDeviceInventory: wired, USB and Bluetooth headphones, hearing aids, line and dock outputs, and HDMI). On a phone
 * that is headphones or an adapter unplugged, and the play pauses, as Media3's own handling paused it. On a TV box the
 * output is HDMI, and it goes away whenever the box re-syncs it: a refresh-rate switch, Dolby Vision on or off, the TV
 * waking, a receiver or soundbar switched on. One of those after the player was made paused the next episode as it
 * started (the Shield). So on a TV the play pauses only when a personal output is involved ([PERSONAL]):
 * one is listed as the broadcast arrives, or one went away in the last [RECENT_MS].
 *
 * That is a TV set's headphones, the only "noisy" kind it has connected: Android delays the switch after the broadcast,
 * so they are usually still listed, but the device callback and the broadcast come by different paths, in no fixed
 * order. A box's HDMI is one of those kinds too, so with headphones connected to it Android sends nothing, neither for
 * an HDMI re-sync nor as they go: the play goes on through the TV, as it did with Media3's handling. On a box, what
 * pauses is an HDMI re-sync in the [RECENT_MS] after headphones went.
 *
 * Outputs are AudioDeviceInfo's types. Pure, for the JVM tests; the watcher is [BecomingNoisy].
 */
// Compared as ints: the types of a later Android than the app's oldest (BLE, eARC) are inlined, and never listed there.
@SuppressLint("InlinedApi")
object NoisyPolicy {
    /** How long a personal output that went away still counts. */
    const val RECENT_MS = 2_000L

    /** An output that went away: its AudioDeviceInfo [type], at [atMs] on the watcher's clock. */
    data class Gone(val type: Int, val atMs: Long)

    /** Whether the play [pauses]; [reason] names the output's kind for the log line, null on a phone. */
    data class Decision(val pauses: Boolean, val reason: String?) {
        /** The log line, by the output's kind only, never a device's name (Bluetooth names are often personal). */
        val line: String get() = "Audio output going away${reason?.let { " ($it)" }.orEmpty()}: ${if (pauses) "paused" else "playing on"}"
    }

    /**
     * The decision for a "becoming noisy" arriving at [nowMs] on a [tv] or a phone, with the outputs [listed] as it
     * arrives and those [gone] before it. A phone pauses. A TV pauses for a personal output listed, else for one gone
     * within [RECENT_MS]; else it plays on, naming HDMI where it was listed or went, else the output listed.
     */
    fun decide(tv: Boolean, listed: Collection<Int>, gone: Collection<Gone>, nowMs: Long): Decision {
        if (!tv) return Decision(pauses = true, reason = null)
        val recent = gone.filter { nowMs - it.atMs <= RECENT_MS }.map { it.type }
        val personal = listed.firstOrNull { it in PERSONAL } ?: recent.firstOrNull { it in PERSONAL }
        if (personal != null) return Decision(pauses = true, reason = kind(personal))
        val reason = when {
            (listed + recent).any { it in HDMI } -> "HDMI re-sync, no headphones"
            else -> listed.firstOrNull { it in NAMED }?.let { "${kind(it)}, no headphones" } ?: "no headphones"
        }
        return Decision(pauses = false, reason = reason)
    }

    /** The kinds of the outputs [types], for the DEBUG line: "HDMI, speaker", or "none". */
    fun kinds(types: Collection<Int>): String = types.takeIf { it.isNotEmpty() }?.joinToString(transform = ::kind) ?: "none"

    private fun kind(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        in BLUETOOTH -> "Bluetooth"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "analog line"
        AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "dock"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "optical"
        else -> "type $type"
    }

    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    /**
     * The outputs a person wears or carries, or plugs in beside the box, which pause a TV's play: Android's own "noisy"
     * outputs (AOSP's AudioDeviceInventory) but for HDMI. Not Bluetooth SCO: media never goes to it, and Android doesn't
     * count it, so beside a Bluetooth device connected for calls only an HDMI re-sync still sends the broadcast, which
     * must play on. A headset going away with its music takes its A2DP output too.
     */
    private val PERSONAL = BLUETOOTH - AudioDeviceInfo.TYPE_BLUETOOTH_SCO + setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG,
    )

    private val HDMI = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)

    /** The TV's own outputs besides HDMI, named in the line when one is all there is. */
    private val NAMED = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_LINE_DIGITAL)
}

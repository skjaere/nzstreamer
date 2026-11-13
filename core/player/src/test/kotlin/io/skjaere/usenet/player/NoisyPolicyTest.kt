package io.skjaere.usenet.player

import android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST
import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_DOCK
import android.media.AudioDeviceInfo.TYPE_DOCK_ANALOG
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_HDMI_ARC
import android.media.AudioDeviceInfo.TYPE_HDMI_EARC
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_LINE_ANALOG
import android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL
import android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX
import android.media.AudioDeviceInfo.TYPE_UNKNOWN
import android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NoisyPolicy]: what a play does as Android says its audio output is going away ("becoming noisy"). A phone pauses, as
 * Media3 paused it; a TV pauses only for headphones, listed as the broadcast arrives or gone in the last 2 s, and plays
 * on through its HDMI's re-syncs (the Shield: the next episode sat paused after the TV's refresh-rate
 * switches). The outputs are AudioDeviceInfo's types; the clock reads 60 s.
 */
class NoisyPolicyTest {
    private fun tv(vararg listed: Int, gone: List<NoisyPolicy.Gone> = emptyList()) = NoisyPolicy.decide(tv = true, listed.toList(), gone, NOW_MS)

    private fun phone(vararg listed: Int, gone: List<NoisyPolicy.Gone> = emptyList()) = NoisyPolicy.decide(tv = false, listed.toList(), gone, NOW_MS)

    /** An output of [type] that went away [ms] before now. */
    private fun gone(type: Int, ms: Long) = NoisyPolicy.Gone(type, NOW_MS - ms)

    @Test
    fun `a phone always pauses`() {
        listOf(intArrayOf(), intArrayOf(TYPE_BUILTIN_SPEAKER), intArrayOf(TYPE_HDMI), intArrayOf(TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP)).forEach { listed ->
            assertTrue(phone(*listed).pauses, listed.toList().toString())
        }
        assertTrue(phone(TYPE_BUILTIN_SPEAKER, gone = listOf(gone(TYPE_WIRED_HEADPHONES, 100))).pauses)
        assertTrue(phone(TYPE_BUILTIN_SPEAKER, gone = listOf(gone(TYPE_HDMI, 5_000))).pauses)
    }

    @Test
    fun `on a TV, a Bluetooth output listed pauses`() {
        listOf(TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST).forEach { bluetooth ->
            assertTrue(tv(TYPE_HDMI, bluetooth).pauses, "type $bluetooth")
            assertTrue(tv(bluetooth).pauses, "type $bluetooth alone")
        }
    }

    @Test
    fun `on a TV, a Bluetooth device connected for calls only doesn't pause an HDMI re-sync`() {
        // Media never plays on SCO, and Android doesn't count it as "noisy": beside HDMI, the broadcast is HDMI's.
        assertFalse(tv(TYPE_HDMI, TYPE_BLUETOOTH_SCO).pauses)
        assertFalse(tv(TYPE_HDMI, gone = listOf(gone(TYPE_BLUETOOTH_SCO, 1_000))).pauses)
        assertEquals("Audio output going away (HDMI re-sync, no headphones): playing on", tv(TYPE_HDMI, TYPE_BLUETOOTH_SCO).line)
    }

    @Test
    fun `on a TV, wired headphones, a USB headset or a hearing aid listed pause, and the other personal outputs`() {
        listOf(TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_HEARING_AID).forEach { personal ->
            assertTrue(tv(TYPE_HDMI, personal).pauses, "type $personal")
        }
        // A USB device or accessory output, an analog line, a dock: as Android counts them among the "noisy" ones.
        listOf(TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_LINE_ANALOG, TYPE_DOCK, TYPE_DOCK_ANALOG).forEach { personal ->
            assertTrue(tv(TYPE_HDMI, personal).pauses, "type $personal")
        }
    }

    @Test
    fun `on a TV, Bluetooth gone 1 s ago with nothing personal listed pauses, gone 3 s ago doesn't`() {
        assertTrue(tv(TYPE_HDMI, gone = listOf(gone(TYPE_BLUETOOTH_A2DP, 1_000))).pauses)
        assertTrue(tv(gone = listOf(gone(TYPE_BLUETOOTH_A2DP, 1_000))).pauses)
        assertTrue(tv(TYPE_HDMI, gone = listOf(gone(TYPE_HDMI, 200), gone(TYPE_WIRED_HEADPHONES, 1_000))).pauses)
        assertFalse(tv(TYPE_HDMI, gone = listOf(gone(TYPE_BLUETOOTH_A2DP, 3_000))).pauses)
        assertFalse(tv(gone = listOf(gone(TYPE_BLUETOOTH_A2DP, 3_000))).pauses)
    }

    @Test
    fun `on a TV, only HDMI, ARC, eARC or the speaker listed, or HDMI gone, plays on`() {
        listOf(TYPE_HDMI, TYPE_HDMI_ARC, TYPE_HDMI_EARC, TYPE_BUILTIN_SPEAKER, TYPE_LINE_DIGITAL).forEach { output ->
            assertFalse(tv(output).pauses, "type $output")
        }
        assertFalse(tv(TYPE_HDMI, TYPE_HDMI_ARC, TYPE_BUILTIN_SPEAKER).pauses)
        assertFalse(tv(gone = listOf(gone(TYPE_HDMI, 500))).pauses)
        assertFalse(tv(TYPE_BUILTIN_SPEAKER, gone = listOf(gone(TYPE_HDMI, 500), gone(TYPE_HDMI_EARC, 300))).pauses)
    }

    @Test
    fun `on a TV, nothing known plays on`() {
        assertFalse(tv().pauses)
        assertFalse(tv(TYPE_UNKNOWN).pauses)
        assertFalse(tv(TYPE_REMOTE_SUBMIX).pauses)
    }

    @Test
    fun `the reason names the kind`() {
        assertEquals("Audio output going away (Bluetooth): paused", tv(TYPE_HDMI, TYPE_BLUETOOTH_A2DP).line)
        assertEquals("Audio output going away (Bluetooth): paused", tv(TYPE_HDMI, gone = listOf(gone(TYPE_BLE_HEADSET, 1_000))).line)
        assertEquals("Audio output going away (wired headphones): paused", tv(TYPE_HDMI, TYPE_WIRED_HEADSET).line)
        assertEquals("Audio output going away (USB): paused", tv(TYPE_USB_HEADSET).line)
        assertEquals("Audio output going away (hearing aid): paused", tv(TYPE_HEARING_AID).line)
        assertEquals("Audio output going away (HDMI re-sync, no headphones): playing on", tv(TYPE_HDMI).line)
        assertEquals("Audio output going away (HDMI re-sync, no headphones): playing on", tv(TYPE_HDMI_EARC).line)
        assertEquals("Audio output going away (HDMI re-sync, no headphones): playing on", tv(gone = listOf(gone(TYPE_HDMI, 500))).line)
        assertEquals("Audio output going away (speaker, no headphones): playing on", tv(TYPE_BUILTIN_SPEAKER).line)
        assertEquals("Audio output going away (no headphones): playing on", tv().line)
        assertEquals("Audio output going away: paused", phone(TYPE_BLUETOOTH_A2DP).line)
        // The kinds listed, for the DEBUG line: never a device's name.
        assertEquals("HDMI, HDMI eARC, Bluetooth, speaker, type 25", NoisyPolicy.kinds(listOf(TYPE_HDMI, TYPE_HDMI_EARC, TYPE_BLUETOOTH_A2DP, TYPE_BUILTIN_SPEAKER, TYPE_REMOTE_SUBMIX)))
        assertEquals("none", NoisyPolicy.kinds(emptyList()))
    }

    private companion object {
        const val NOW_MS = 60_000L
    }
}

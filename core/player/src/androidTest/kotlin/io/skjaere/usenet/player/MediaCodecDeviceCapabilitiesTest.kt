package io.skjaere.usenet.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCodecDeviceCapabilitiesTest {

    @Test
    fun reportsTheBaselineEveryDeviceHas() {
        val capabilities = MediaCodecDeviceCapabilities(InstrumentationRegistry.getInstrumentation().targetContext)
        Log.i("DeviceCapabilities", capabilities.toString())

        // Android's compatibility definition requires H.264 and AAC decoders.
        assertTrue(capabilities.canDecode(VideoCodec.H264, tenBit = false))
        assertTrue(capabilities.canDecode(AudioCodec.Aac))
        assertTrue(capabilities.displayHeight > 0)
    }

    @Test
    fun reportsDecoderSizeLimits() {
        val capabilities = MediaCodecDeviceCapabilities(InstrumentationRegistry.getInstrumentation().targetContext)
        Log.i("DeviceCapabilities", capabilities.toString())

        // Android's compatibility definition requires H.264 decoding at 480p, and at 720p on screens that tall.
        val h264 = capabilities.maxHeight(VideoCodec.H264, tenBit = false)
        assertNotNull(h264)
        assertTrue("H.264 up to ${h264}p", h264!! >= if (capabilities.displayHeight >= 720) 720 else 480)
        VideoCodec.entries.forEach { codec ->
            val heights = listOf(false, true).map { tenBit ->
                capabilities.maxHeight(codec, tenBit).also { height ->
                    if (!capabilities.canDecode(codec, tenBit)) assertNull("$codec tenBit=$tenBit", height)
                    if (height != null) assertTrue("$codec tenBit=$tenBit up to ${height}p", height in 0..4320)
                }
            }
            // Ten-bit decoders are a subset of all decoders for the codec.
            val (eightBit, tenBit) = heights
            if (eightBit != null && tenBit != null) assertTrue("$codec: ${tenBit}p 10-bit > ${eightBit}p", tenBit <= eightBit)
        }
    }

    /**
     * What the log header says of the device (L12), one line each, for the record of what a device really answers:
     * hardware heights, Dolby Vision profiles, the output and largest modes, the HDR types. Only what holds on every
     * device is checked; whether an emulator's decoders count as hardware is the device's own answer.
     */
    @Test
    fun reportsHardwareHeightsAndTheDisplay() {
        val capabilities = MediaCodecDeviceCapabilities(InstrumentationRegistry.getInstrumentation().targetContext)
        capabilities.lines.forEach { Log.i("DeviceCapabilities", it) }
        Log.i("DeviceCapabilities", "Dolby Vision profiles: ${capabilities.dolbyVisionProfiles}")

        VideoCodec.entries.forEach { codec ->
            listOf(false, true).forEach { tenBit ->
                val hardware = capabilities.hardwareMaxHeight(codec, tenBit)
                if (!capabilities.canDecode(codec, tenBit)) assertNull("$codec tenBit=$tenBit", hardware)
                if (hardware != null) assertTrue("$codec tenBit=$tenBit hardware up to ${hardware}p", hardware in 0..4320)
            }
        }
        val display = capabilities.display
        assertNotNull("the default display's output mode", display.output)
        assertNotNull("the default display's modes", display.largest)
        assertNotNull("the default display's HDR types", display.hdr)
        if (!display.fromTv) assertTrue("$display", capabilities.displayHeight <= display.largest!!.shorter)
        // A phone or tablet ranks for its largest mode, whatever mode its resolution setting runs.
        if (!display.tv) assertEquals("$display", display.largest!!.shorter, capabilities.displayHeight)
        assertTrue("$display", capabilities.displayHeight > 0)
    }
}

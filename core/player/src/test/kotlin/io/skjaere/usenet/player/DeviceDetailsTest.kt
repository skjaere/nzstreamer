package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * File details' "On this device": the decoder the player started, as Media3's own decoder lists mark it ([DecoderDetails]),
 * and the display's output, refresh rate and the HDR it can show ([DisplayDetails]). The names are invented, the
 * Shield's shape kept (an NVIDIA Dolby Vision decoder; 3840x2160 at 59.94 Hz with Dolby Vision and HDR10).
 */
class DeviceDetailsTest {
    private val saved = Locale.getDefault()

    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).setLumaBitdepth(10).build()
    private val hlg = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).setLumaBitdepth(10).build()

    private fun format(mime: String, codecs: String? = null, color: ColorInfo? = null) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setColorInfo(color).build()

    /** Media3's lists on a box with a Dolby Vision decoder, hardware HEVC and H.264 decoders and a software AV1 one. */
    private val lists = mapOf(
        MimeTypes.VIDEO_DOLBY_VISION to listOf(ListedDecoder("OMX.Vendor.DOVI.decode", hardware = true)),
        MimeTypes.VIDEO_H265 to listOf(ListedDecoder("OMX.Vendor.hevc.decode", hardware = true), ListedDecoder("c2.android.hevc.decoder", hardware = false)),
        MimeTypes.VIDEO_H264 to listOf(ListedDecoder("c2.vendor.avc.decoder", hardware = true)),
        MimeTypes.VIDEO_AV1 to listOf(ListedDecoder("c2.android.av1.decoder", hardware = false)),
    )
    private val asked = mutableListOf<String>()
    private val decoders: (String) -> List<ListedDecoder> = { mime -> asked += mime; lists[mime].orEmpty() }

    /** [DecoderDetails.of], on a display that shows Dolby Vision unless said otherwise. */
    private fun decoder(name: String, format: Format, decoders: (String) -> List<ListedDecoder>, displayDolbyVision: Boolean = true) =
        DecoderDetails.of(name, format, decoders, displayDolbyVision)

    @Test
    fun `a decoder is hardware or software as Media3's lists mark it`() {
        assertEquals(Fact("c2.vendor.avc.decoder", "hardware"), decoder("c2.vendor.avc.decoder", format(MimeTypes.VIDEO_H264, "avc1.640029"), decoders))
        assertEquals(Fact("c2.android.av1.decoder", "software"), decoder("c2.android.av1.decoder", format(MimeTypes.VIDEO_AV1, "av01.0.08M.10"), decoders))
        assertEquals(Fact("c2.android.hevc.decoder", "software"), decoder("c2.android.hevc.decoder", format(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", pq), decoders))
    }

    @Test
    fun `a Dolby Vision track says whether the decoder takes Dolby Vision or only the base layer under it`() {
        val dolbyVision = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", pq)
        assertEquals(Fact("OMX.Vendor.DOVI.decode", "hardware · Dolby Vision"), decoder("OMX.Vendor.DOVI.decode", dolbyVision, decoders))
        // Media3's fallback where no Dolby Vision decoder is chosen: the HEVC decoder plays the profile 8.1's HDR10.
        assertEquals(Fact("OMX.Vendor.hevc.decode", "hardware · decodes the HDR10 base layer"), decoder("OMX.Vendor.hevc.decode", dolbyVision, decoders))
        assertEquals(
            Fact("c2.android.hevc.decoder", "software · decodes the HLG base layer"),
            decoder("c2.android.hevc.decoder", format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", hlg), decoders),
        )
        // A profile 9's base layer is H.264's, SDR.
        assertEquals(Fact("c2.vendor.avc.decoder", "hardware · decodes the SDR base layer"), decoder("c2.vendor.avc.decoder", format(MimeTypes.VIDEO_DOLBY_VISION, "dvav.09.05"), decoders))
    }

    /**
     * Some chips list one decoder under both types. Media3 chooses it from the Dolby Vision list, but on a display without
     * Dolby Vision from the plain list a profile 4, 8, 9 or 10 falls back to, where it decodes the base layer only.
     */
    @Test
    fun `a decoder listed under both types takes Dolby Vision only where Media3 chooses it for that`() {
        val both: (String) -> List<ListedDecoder> = { listOf(ListedDecoder("OMX.Vendor.dual.decode", hardware = true)) }
        val dolbyVision = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", pq)
        assertEquals(Fact("OMX.Vendor.dual.decode", "hardware · Dolby Vision"), decoder("OMX.Vendor.dual.decode", dolbyVision, both))
        assertEquals(Fact("OMX.Vendor.dual.decode", "hardware · decodes the HDR10 base layer"), decoder("OMX.Vendor.dual.decode", dolbyVision, both, displayDolbyVision = false))
        // A profile 7 has no plain decoder to fall back to: the Dolby Vision list, whatever the display.
        assertEquals(Fact("OMX.Vendor.dual.decode", "hardware · Dolby Vision"), decoder("OMX.Vendor.dual.decode", format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06", pq), both, displayDolbyVision = false))
        // Without a plain decoder listed, Media3 keeps to the Dolby Vision ones there too.
        val dolbyVisionOnly: (String) -> List<ListedDecoder> = { mime -> if (mime == MimeTypes.VIDEO_DOLBY_VISION) lists.getValue(mime) else emptyList() }
        assertEquals(Fact("OMX.Vendor.DOVI.decode", "hardware · Dolby Vision"), decoder("OMX.Vendor.DOVI.decode", dolbyVision, dolbyVisionOnly, displayDolbyVision = false))
    }

    @Test
    fun `a decoder no list has is named alone`() {
        assertEquals(Fact("c2.other.decoder"), decoder("c2.other.decoder", format(MimeTypes.VIDEO_H264), decoders))
        assertEquals(Fact("c2.other.decoder"), decoder("c2.other.decoder", format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", pq), decoders))
        assertEquals(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_H265), asked)
    }

    private val uhd = ModeSize(3840, 2160)

    /** A display's answers, by default the Shield's: 3840x2160 output at 59.94 Hz, Dolby Vision and HDR10. */
    private fun readings(output: ModeSize = uhd, refreshRate: Float? = 59.94006f, hdrTypes: IntArray? = intArrayOf(1, 2)) =
        DisplayReadings(output = { output }, modes = { listOf(ModeSize(1920, 1080), uhd) }, hdrTypes = { hdrTypes }, refreshRate = { refreshRate })

    private fun details(display: DisplayReadings? = readings(), tv: Boolean = true) =
        DisplayDetails.of(tv, 30, { "3840x2160" }, ModeSize(1920, 1080), display)

    @Test
    fun `the display is its output and refresh rate, noting the HDR it can show`() {
        assertEquals(Fact("3840 × 2160 · 59.94 Hz", "can show Dolby Vision, HDR10"), details().fact)
        assertEquals(Fact("3840 × 2160 · 59.94 Hz", "can show Dolby Vision, HDR10, HLG"), details(readings(hdrTypes = intArrayOf(3, 2, 1))).fact)
        assertEquals(Fact("3840 × 2160 · 23.976 Hz", "can show Dolby Vision, HDR10"), details(readings(refreshRate = 23.976025f)).fact)
        assertEquals(Fact("1920 × 1080 · 50 Hz", "can't show HDR"), details(readings(output = ModeSize(1920, 1080), refreshRate = 50f, hdrTypes = null)).fact)
        // A phone's: its mode as the display gives it.
        assertEquals(
            Fact("1080 × 2340 · 120 Hz", "can show HDR10, HDR10+, HLG"),
            details(readings(output = ModeSize(1080, 2340), refreshRate = 120.00001f, hdrTypes = intArrayOf(4, 3, 2)), tv = false).fact,
        )
    }

    @Test
    fun `what the display doesn't say is left out`() {
        val noRate = details(readings(refreshRate = null))
        assertNull(noRate.refreshRate)
        assertEquals(Fact("3840 × 2160", "can show Dolby Vision, HDR10"), noRate.fact)
        // HDR that can't be read says nothing, unlike a display without HDR.
        val unknownHdr = DisplayReadings(output = { uhd }, modes = { listOf(uhd) }, hdrTypes = { throw IllegalStateException("display removed") }, refreshRate = { 60f })
        assertEquals(Fact("3840 × 2160 · 60 Hz"), details(unknownHdr).fact)
        assertEquals(Fact("60 Hz", "can show Dolby Vision, HDR10"), details(readings(output = ModeSize(0, 0), refreshRate = 60f)).fact)
        assertNull(details(display = null).fact)
    }

    @Test
    fun `a refresh rate's digits follow the locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(Fact("3840 × 2160 · 59,94 Hz", "can show Dolby Vision, HDR10"), details().fact)
    }
}

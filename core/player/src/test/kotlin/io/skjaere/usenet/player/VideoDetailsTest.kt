package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A video track in File details' words ([VideoDetails]): its format by a friendly name with its level and tier, its
 * size and class, its frame rate snapped to the usual ones, its bit depth, HDR and Dolby Vision, and its colour; a fact
 * the file doesn't state is null, its row left out. The words are the approved mockups' (FdPhoneFull, FdTvFull).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoDetailsTest {
    private val saved = Locale.getDefault()

    // Frame rates follow the default locale's digits, as sizes do; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private fun video(
        mime: String = MimeTypes.VIDEO_H265,
        codecs: String? = null,
        width: Int = 3840,
        height: Int = 2160,
        color: ColorInfo? = null,
        facts: MatroskaVideoFacts? = null,
        frameRate: Float = Format.NO_VALUE.toFloat(),
        pixelRatio: Float = 1f,
    ): Format = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setWidth(width).setHeight(height).setColorInfo(color)
        .setFrameRate(frameRate).setPixelWidthHeightRatio(pixelRatio).setMetadata(facts?.let { Metadata(it) }).build()

    private fun color(space: Int = Format.NO_VALUE, transfer: Int = Format.NO_VALUE, range: Int = Format.NO_VALUE, bits: Int = Format.NO_VALUE, staticInfo: ByteArray? = null) =
        ColorInfo.Builder().setColorSpace(space).setColorTransfer(transfer).setColorRange(range).setLumaBitdepth(bits).setChromaBitdepth(bits)
            .setHdrStaticInfo(staticInfo).build()

    private val pq = color(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084, C.COLOR_RANGE_LIMITED, 10)

    /** CTA-861.3's static metadata as Media3 lays it out: the primaries, the mastering display's peak and floor, MaxCLL, MaxFALL. */
    private fun staticInfo(peak: Int, maxCll: Int, maxFall: Int = 0): ByteArray = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0)
        listOf(34_000, 16_000, 13_250, 34_500, 7_500, 3_000, 15_635, 16_450, peak, 0, maxCll, maxFall).forEach { putShort(it.toShort()) }
    }.array()

    private fun dolbyVision(codecs: String = "dvhe.08.06", color: ColorInfo? = pq, record: DolbyVisionRecord? = null, base: String? = "hvc1.2.4.L153.B0") =
        video(MimeTypes.VIDEO_DOLBY_VISION, codecs, color = color, facts = MatroskaVideoFacts(dolbyVision = record, baseCodecs = base))

    @Test
    fun `a format reads by its friendly name, its level and tier noted`() {
        fun named(mime: String, codecs: String?, bits: Int = Format.NO_VALUE) = VideoDetails.of(video(mime, codecs, color = color(bits = bits))).format
        // The mockups' two: a 4K HEVC at 5.1 high tier, a 1080p one at 4.1.
        assertEquals(Fact("HEVC Main 10", "level 5.1, high tier"), named(MimeTypes.VIDEO_H265, "hvc1.2.4.H153.90"))
        assertEquals(Fact("HEVC Main 10", "level 4.1"), named(MimeTypes.VIDEO_H265, "hvc1.2.4.L123.B0"))
        assertEquals(Fact("HEVC Main", "level 6.2"), named(MimeTypes.VIDEO_H265, "hvc1.1.6.L186.90"))
        assertEquals(Fact("HEVC Main", "level 4"), named(MimeTypes.VIDEO_H265, "hev1.1.6.L120.90"))
        assertEquals(Fact("H.264 High", "level 4.1"), named(MimeTypes.VIDEO_H264, "avc1.640029"))
        assertEquals(Fact("H.264 High 10", "level 5.1"), named(MimeTypes.VIDEO_H264, "avc1.6E0033"))
        assertEquals(Fact("H.264 Main", "level 3.1"), named(MimeTypes.VIDEO_H264, "avc1.4D401F"))
        assertEquals(Fact("H.264 Constrained Baseline", "level 3"), named(MimeTypes.VIDEO_H264, "avc1.42E01E"))
        assertEquals(Fact("AV1 Main 10-bit", "level 5.1, high tier"), named(MimeTypes.VIDEO_AV1, "av01.0.13H.10"))
        assertEquals(Fact("AV1 Main", "level 4.0"), named(MimeTypes.VIDEO_AV1, "av01.0.08M.08"))
        assertEquals(Fact("VP9 Profile 2", "level 4.1"), named(MimeTypes.VIDEO_VP9, "vp09.02.41.10"))
        // Without codecs, the codec alone; one the app doesn't name, as Media3 does.
        assertEquals(Fact("MPEG-2"), named(MimeTypes.VIDEO_MPEG2, null))
        assertEquals(Fact("VC-1"), named(MimeTypes.VIDEO_VC1, null))
        assertEquals(Fact("HEVC"), named(MimeTypes.VIDEO_H265, null))
        assertEquals(Fact("AV1 10-bit"), named(MimeTypes.VIDEO_AV1, null, bits = 10))
        assertEquals(Fact("video/x-invented"), named("video/x-invented", null))
    }

    /** As FdTvFull shows a profile 8.1 file: the HEVC under the record, which Media3's codecs for the track replace. */
    @Test
    fun `a Dolby Vision track's format is the codec under it, or Dolby Vision where that isn't known`() {
        val details = VideoDetails.of(dolbyVision(base = "hvc1.2.4.H153.90"))
        assertEquals(Fact("HEVC Main 10", "level 5.1, high tier"), details.format)
        assertEquals(VideoCodec.Hevc, details.codec)
        // An MP4's: no Matroska facts, so only the record's codecs; the codec is still HEVC's, the "dvhe" says so.
        val mp4 = VideoDetails.of(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", color = pq))
        assertEquals(Fact("Dolby Vision"), mp4.format)
        assertEquals(VideoCodec.Hevc, mp4.codec)
        assertEquals(VideoCodec.H264, VideoDetails.of(video(MimeTypes.VIDEO_DOLBY_VISION, "dvav.09.05")).codec)
    }

    @Test
    fun `the resolution is the size the file stores, its class as release names have it, and the ratio it shows at`() {
        val scope = VideoDetails.of(video(width = 3840, height = 1606))
        assertEquals(Fact("3840 × 1606 · 2160p · 2.39:1"), scope.resolution)
        assertEquals(2160, scope.resolutionClass)
        assertEquals(Fact("1920 × 800 · 1080p · 2.40:1"), VideoDetails.of(video(width = 1920, height = 800)).resolution)
        assertEquals(Fact("1920 × 1080 · 1080p · 16:9"), VideoDetails.of(video(width = 1920, height = 1080)).resolution)
        assertEquals(Fact("160 × 90 · 90p · 16:9"), VideoDetails.of(video(width = 160, height = 90)).resolution)
        // A PAL DVD's 16:9, its pixels wider than tall.
        assertEquals(Fact("720 × 576 · 576p · 16:9 (anamorphic)"), VideoDetails.of(video(width = 720, height = 576, pixelRatio = 64f / 45)).resolution)
        val unknown = VideoDetails.of(video(width = Format.NO_VALUE, height = Format.NO_VALUE))
        assertNull(unknown.resolution)
        assertNull(unknown.resolutionClass)
    }

    /**
     * The ratio the picture shows at (W11): its width times Media3's pixel aspect ratio over its height. Within 1 % of 16:9
     * or 4:3 it is named, else two decimals to 1; pixels that aren't square say so.
     */
    @Test
    fun `the aspect ratio is named near 16 to 9 and 4 to 3, else two decimals to 1, noting pixels that aren't square`() {
        fun ratio(width: Int, height: Int, pixelRatio: Float = 1f) = aspectRatio(width, height, pixelRatio)
        // Square pixels.
        assertEquals("2.39:1", ratio(3840, 1606))
        assertEquals("1.85:1", ratio(1998, 1080))
        assertEquals("16:9", ratio(3840, 2160))
        assertEquals("4:3", ratio(1440, 1080))
        assertEquals("2.00:1", ratio(3840, 1920))
        assertEquals("2.40:1", ratio(1920, 800))
        // Anamorphic PAL DVDs: 16:9 and 4:3 from 720 × 576.
        assertEquals("16:9 (anamorphic)", ratio(720, 576, 64f / 45))
        assertEquals("4:3 (anamorphic)", ratio(720, 576, 16f / 15))
        // A ratio of 0 (not known) or 1 is square.
        assertEquals("16:9", ratio(1920, 1080, 0f))
        assertEquals("16:9", ratio(1920, 1080, Format.NO_VALUE.toFloat()))
        // At the 1 % edge: 16:9 is 1.7778, so from 1.7600 to 1.7956; 4:3 from 1.3200 to 1.3467.
        assertEquals("16:9", ratio(1795, 1000))
        assertEquals("1.80:1", ratio(1796, 1000))
        assertEquals("16:9", ratio(1761, 1000))
        assertEquals("1.76:1", ratio(1759, 1000))
        assertEquals("4:3", ratio(1346, 1000))
        assertEquals("1.35:1", ratio(1347, 1000))
        assertEquals("4:3", ratio(1321, 1000))
        assertEquals("1.32:1", ratio(1319, 1000))
        // No size, no ratio.
        assertNull(ratio(0, 1080))
        assertNull(ratio(1920, Format.NO_VALUE))
        // The digits as frame rates have them: the locale's, a log's the root locale's.
        assertEquals("2,39:1", aspectRatio(3840, 1606, 1f, Locale.GERMANY))
    }

    @Test
    fun `a Matroska frame duration reads as its rate, snapped to the usual ones, NTSC's as x over 1,001`() {
        fun rate(ns: Int) = VideoDetails.of(video(facts = MatroskaVideoFacts(frameDurationNs = ns))).frameRate?.value
        mapOf(
            41_708_333 to "23.976 fps", 41_708_334 to "23.976 fps", 41_666_667 to "24 fps", 40_000_000 to "25 fps",
            33_366_667 to "29.97 fps", 33_333_333 to "30 fps", 20_854_167 to "47.952 fps", 20_833_333 to "48 fps",
            20_000_000 to "50 fps", 16_683_333 to "59.94 fps", 16_666_667 to "60 fps", 8_341_667 to "119.88 fps", 8_333_333 to "120 fps",
            // Others with up to three decimals.
            100_000_000 to "10 fps", 80_000_000 to "12.5 fps", 41_000_000 to "24.39 fps",
        ).forEach { (ns, words) -> assertEquals(words, rate(ns), "$ns ns") }
    }

    @Test
    fun `an MP4's frame rate is Media3's, and a file without one has no frame rate row`() {
        assertEquals(Fact("23.976 fps"), VideoDetails.of(video(frameRate = 23.976f)).frameRate)
        assertEquals(Fact("29.97 fps"), VideoDetails.of(video(frameRate = 29.97003f)).frameRate)
        assertEquals(Fact("24 fps"), VideoDetails.of(video(frameRate = 24f)).frameRate)
        assertNull(VideoDetails.of(video()).frameRate)
    }

    @Test
    fun `a frame rate's digits follow the locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(Fact("23,976 fps"), VideoDetails.of(video(facts = MatroskaVideoFacts(frameDurationNs = 41_708_333))).frameRate)
        assertEquals("59,94", formatRate(60 / 1.001))
    }

    /** The log's rates are the same in every locale (This device's header line), as its other numbers are. */
    @Test
    fun `a rate can be written in the root locale's digits whatever the default`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("59.94", formatRate(60 / 1.001, Locale.ROOT))
        assertEquals("23.976", formatRate(23.976025, Locale.ROOT))
        assertEquals("12.5", formatRate(12.5, Locale.ROOT))
        assertEquals("60", formatRate(60.000004, Locale.ROOT))
    }

    @Test
    fun `the bit depth is the configuration's, left out when it doesn't say`() {
        val ten = VideoDetails.of(video(color = color(bits = 10)))
        assertEquals(Fact("10-bit"), ten.bitDepth)
        assertEquals(10, ten.bits)
        assertEquals(Fact("8-bit"), VideoDetails.of(video(color = color(bits = 8))).bitDepth)
        assertNull(VideoDetails.of(video()).bitDepth)
        assertNull(VideoDetails.of(video()).bits)
    }

    @Test
    fun `HDR10 notes the mastering display and MaxCLL the file has, HLG nothing, SDR has no HDR`() {
        assertEquals(
            HdrFact(listOf(Hdr.Hdr10), "mastering display 1000 nits, MaxCLL 812 (from the file)"),
            VideoDetails.of(video(color = color(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084, bits = 10, staticInfo = staticInfo(1_000, 812, 400)))).hdr,
        )
        // MaxCLL 0 is CTA-861.3's unknown.
        assertEquals(
            HdrFact(listOf(Hdr.Hdr10), "mastering display 4000 nits (from the file)"),
            VideoDetails.of(video(color = color(transfer = C.COLOR_TRANSFER_ST2084, staticInfo = staticInfo(4_000, 0)))).hdr,
        )
        assertEquals(HdrFact(listOf(Hdr.Hdr10), null), VideoDetails.of(video(color = pq)).hdr)
        assertEquals(HdrFact(listOf(Hdr.Hlg), null), VideoDetails.of(video(color = color(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_HLG))).hdr)
        assertEquals(HdrFact(emptyList(), null), VideoDetails.of(video(color = color(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR))).hdr)
        assertEquals(HdrFact(emptyList(), null), VideoDetails.of(video()).hdr)
    }

    @Test
    fun `Dolby Vision names its profile, level and base layer from the record`() {
        fun hdr(codecs: String, compatibility: Int, el: Boolean = false, color: ColorInfo? = pq) =
            VideoDetails.of(dolbyVision(codecs, color, DolbyVisionRecord(compatibility, el, baseLayer = true))).hdr
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 8.1, level 6 · HDR10 base layer"), hdr("dvhe.08.06", 1))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 8.2, level 6 · SDR base layer"), hdr("dvhe.08.06", 2))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hlg), "Dolby Vision profile 8.4, level 6 · HLG base layer"), hdr("dvhe.08.06", 4))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 5, level 6 · no HDR10 base layer"), hdr("dvhe.05.06", 0, color = null))
        // The layer is there; whether a decoder uses it nothing says.
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 7, level 6 · HDR10 base layer, enhancement layer"), hdr("dvhe.07.06", 6, el = true))
        // Without a level in the codecs.
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 8.1 · HDR10 base layer"), hdr("dvhe.08", 1))
    }

    /** An MP4's track: no record to read, so profile 8's base layer is the one its colour says, and profiles 5 and 7 have one each. */
    @Test
    fun `without a record the base layer is the track's transfer for profile 8, the profile's own for 5 and 7`() {
        fun hdr(codecs: String, color: ColorInfo?) = VideoDetails.of(video(MimeTypes.VIDEO_DOLBY_VISION, codecs, color = color)).hdr
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 8.1, level 6 · HDR10 base layer"), hdr("dvhe.08.06", pq))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hlg), "Dolby Vision profile 8.4, level 6 · HLG base layer"), hdr("dvhe.08.06", color(transfer = C.COLOR_TRANSFER_HLG)))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 8.2, level 6 · SDR base layer"), hdr("dvhe.08.06", color(transfer = C.COLOR_TRANSFER_SDR)))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 8, level 6"), hdr("dvhe.08.06", null))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 5, level 6 · no HDR10 base layer"), hdr("dvhe.05.06", null))
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 7, level 6 · HDR10 base layer"), hdr("dvhe.07.06", null))
    }

    @Test
    fun `the colour is the primaries and the transfer, its range noted`() {
        assertEquals(Fact("BT.2020 · PQ", "limited range"), VideoDetails.of(video(color = pq)).colour)
        assertEquals(Fact("BT.2020 · HLG", "limited range"), VideoDetails.of(video(color = color(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_HLG, C.COLOR_RANGE_LIMITED))).colour)
        assertEquals(Fact("BT.709 · SDR", "full range"), VideoDetails.of(video(color = color(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR, C.COLOR_RANGE_FULL))).colour)
        assertEquals(Fact("BT.601"), VideoDetails.of(video(color = color(C.COLOR_SPACE_BT601))).colour)
        // A range alone says nothing worth a row.
        assertNull(VideoDetails.of(video(color = color(range = C.COLOR_RANGE_LIMITED, bits = 8))).colour)
        assertNull(VideoDetails.of(video()).colour)
    }

    /** The fixtures as the player's extractor reads them, end to end (see the README beside them). */
    @Test
    fun `the fixtures read as File details describe them`() {
        fun fixture(name: String) = VideoDetails.of(
            ExtractedFormats.read(ExtractedFormats.fixture(name), TrackFlagsExtractorsFactory().matroska()).values.single { MimeTypes.isVideo(it.sampleMimeType) },
        )
        with(fixture("dv-p8-hdr10.mkv")) {
            assertEquals(Fact("HEVC Main 10", "level 1"), format)
            assertEquals(Fact("160 × 90 · 90p · 16:9"), resolution)
            assertEquals(Fact("10 fps"), frameRate)
            assertEquals(Fact("10-bit"), bitDepth)
            assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 8.1, level 6 · HDR10 base layer"), hdr)
            assertEquals(Fact("BT.2020 · PQ", "limited range"), colour)
            assertEquals(Triple(90, VideoCodec.Hevc, 10), Triple(resolutionClass, codec, bits))
        }
        assertEquals(HdrFact(listOf(Hdr.Hdr10), "mastering display 1000 nits, MaxCLL 812 (from the file)"), fixture("hdr10-hevc.mkv").hdr)
        with(fixture("hlg-hevc.mkv")) {
            assertEquals(HdrFact(listOf(Hdr.Hlg), null), hdr)
            assertEquals(Fact("BT.2020 · HLG", "limited range"), colour)
        }
        with(fixture("rate-23976.mkv")) {
            assertEquals(listOf(Fact("H.264 High", "level 1.2"), Fact("23.976 fps"), Fact("8-bit")), listOf(format, frameRate, bitDepth))
            assertEquals(HdrFact(emptyList(), null), hdr)
            assertNull(colour)
        }
        assertEquals(Fact("AV1 Main 10-bit", "level 2.0"), fixture("av1-10bit.mkv").format)
        assertEquals(Fact("HEVC Main", "level 6.2"), fixture("hevc-level62.mkv").format)
        assertEquals(listOf(Fact("MPEG-2"), Fact("25 fps")), fixture("mpeg2.mkv").let { listOf(it.format, it.frameRate) })
        assertEquals(HdrFact(listOf(Hdr.DolbyVision), "Dolby Vision profile 5, level 6 · no HDR10 base layer"), fixture("dv-p5.mkv").hdr)
        assertEquals(HdrFact(listOf(Hdr.DolbyVision, Hdr.Hdr10), "Dolby Vision profile 7, level 6 · HDR10 base layer, enhancement layer"), fixture("dv-p7.mkv").hdr)
    }

    /**
     * An HDR10 Matroska file with a mastering display but no MaxCLL: Media3 writes its own default MaxCLL (1000) into the
     * static metadata, which the file never stated, so only the mastering display is noted.
     */
    @Test
    fun `an HDR10 Matroska file without MaxCLL notes only its mastering display`() {
        val read = ExtractedFormats.read(ExtractedFormats.withoutMaxCll(ExtractedFormats.fixture("hdr10-hevc.mkv")), TrackFlagsExtractorsFactory().matroska())
        assertEquals(HdrFact(listOf(Hdr.Hdr10), "mastering display 1000 nits (from the file)"), VideoDetails.of(read.values.single { MimeTypes.isVideo(it.sampleMimeType) }).hdr)
    }

    /** The transfer is the fallback where no record is read (MP4); where both are, they agree. */
    @Test
    fun `a profile 8 record and the track's own transfer give the same base layer`() {
        val read = ExtractedFormats.read(ExtractedFormats.fixture("dv-p8-hdr10.mkv"), TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES).matroska()).getValue("1")
        assertEquals(VideoDetails.of(read).hdr, VideoDetails.of(read.buildUpon().setMetadata(null).build()).hdr)
    }

    companion object {
        /** Strict reads, as MatroskaTrackFlagsTest has them: Media3's own test check throws on android.jar's stubs. */
        @BeforeAll
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

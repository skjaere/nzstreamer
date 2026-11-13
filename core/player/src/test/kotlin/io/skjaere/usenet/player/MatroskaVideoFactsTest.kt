package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the player's Matroska extractor reads of a video track beyond Media3's format ([MatroskaVideoFacts]), and the
 * colour Media3 reads itself, from the video fixtures (see the README beside them) as the player's and the probe's
 * extractors read them: the frame rate from DefaultDuration, the Dolby Vision record's compatibility id and layers, and
 * HDR10's mastering display and MaxCLL. File details show them; a Media3 upgrade that reads them otherwise fails here.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MatroskaVideoFactsTest {
    /** Each extractor's formats for [name], both Matroska flag variants (the player's, the probe's). */
    private fun read(name: String): List<Map<String, Format>> =
        listOf(TrackFlagsExtractorsFactory(), TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES))
            .map { ExtractedFormats.read(ExtractedFormats.fixture(name), it.matroska()) }

    /** [name]'s video track as each extractor reads it. */
    private fun video(name: String): List<Format> = read(name).map { formats -> formats.values.single { MimeTypes.isVideo(it.sampleMimeType) } }

    /** [info]'s little-endian 16-bit value at [at], as Media3 writes the CTA-861.3 static metadata. */
    private fun uint16(info: ByteArray, at: Int) = (info[at].toInt() and 0xFF) or ((info[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `a track's DefaultDuration gives its frame rate, which Media3 leaves unset for Matroska and the extractor leaves so`() {
        video("rate-23976.mkv").forEach {
            assertEquals(41_708_333, it.matroskaFacts?.frameDurationNs)
            assertEquals(24_000.0 / 1_001, assertNotNull(it.matroskaFacts?.frameRate), 1e-6)
            assertEquals(Format.NO_VALUE.toFloat(), it.frameRate)
        }
        video("hevc10.mkv").forEach { assertEquals(100_000_000, it.matroskaFacts?.frameDurationNs) }
        // MPEG-2 has no 10 fps.
        video("mpeg2.mkv").forEach { assertEquals(40_000_000, it.matroskaFacts?.frameDurationNs) }
    }

    @Test
    fun `a Dolby Vision record's compatibility id and layers, which Media3 drops`() {
        video("dv-p5.mkv").forEach { assertEquals(DolbyVisionRecord(0, enhancementLayer = false, baseLayer = true), it.matroskaFacts?.dolbyVision) }
        video("dv-p7.mkv").forEach { assertEquals(DolbyVisionRecord(6, enhancementLayer = true, baseLayer = true), it.matroskaFacts?.dolbyVision) }
        video("dv-p8.mkv").forEach { assertEquals(DolbyVisionRecord(1, enhancementLayer = false, baseLayer = true), it.matroskaFacts?.dolbyVision) }
    }

    /** Media3 replaces the track's codecs with the record's; the facts keep the HEVC's, which File details name. */
    @Test
    fun `a Dolby Vision track keeps the codecs of the HEVC under it`() {
        val hevc = video("hevc10.mkv").first().codecs
        assertTrue(hevc.orEmpty().startsWith("hvc1.2."), hevc)
        listOf("dv-p5.mkv", "dv-p7.mkv", "dv-p8.mkv").flatMap(::video).forEach {
            assertEquals(MimeTypes.VIDEO_DOLBY_VISION, it.sampleMimeType)
            assertEquals(hevc, it.matroskaFacts?.baseCodecs)
        }
    }

    @Test
    fun `a track without a record has no record facts, and a track that isn't video no facts at all`() {
        video("hevc10.mkv").forEach {
            assertNull(it.matroskaFacts?.dolbyVision)
            assertNull(it.matroskaFacts?.baseCodecs)
        }
        // Track 2 is the fixtures' AAC.
        read("hevc10.mkv").forEach { assertNull(it.getValue("2").matroskaFacts) }
    }

    @Test
    fun `HDR10's colour, mastering display and MaxCLL come from Matroska's Colour element`() {
        video("hdr10-hevc.mkv").forEach {
            val color = assertNotNull(it.colorInfo)
            assertEquals(listOf(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084, C.COLOR_RANGE_LIMITED, 10), listOf(color.colorSpace, color.colorTransfer, color.colorRange, color.lumaBitdepth))
            val info = assertNotNull(color.hdrStaticInfo)
            assertEquals(25, info.size)
            // The red primary (0.68 in 0.00002 units), then the mastering display's peak in nits, MaxCLL and MaxFALL.
            assertEquals(listOf(34_000, 1_000, 812, 400), listOf(uint16(info, 1), uint16(info, 17), uint16(info, 21), uint16(info, 23)))
            assertEquals(100_000_000, it.matroskaFacts?.frameDurationNs)
            assertNull(it.matroskaFacts?.dolbyVision)
            assertEquals(812, it.matroskaFacts?.maxCll)
        }
    }

    /** Media3 starts each track at MaxCLL 1000 and MaxFALL 200, and writes them for a file without them: only the facts can tell. */
    @Test
    fun `an HDR10 file without MaxCLL has none in its facts, whatever Media3 writes`() {
        listOf(TrackFlagsExtractorsFactory(), TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)).forEach { factory ->
            val video = ExtractedFormats.read(ExtractedFormats.withoutMaxCll(ExtractedFormats.fixture("hdr10-hevc.mkv")), factory.matroska()).getValue("1")
            assertEquals(1_000, uint16(assertNotNull(video.colorInfo?.hdrStaticInfo), 21))
            assertNull(video.matroskaFacts?.maxCll)
            assertEquals(100_000_000, video.matroskaFacts?.frameDurationNs)
        }
        video("hlg-hevc.mkv").forEach { assertNull(it.matroskaFacts?.maxCll) }
    }

    @Test
    fun `HLG's colour, with no static metadata`() {
        video("hlg-hevc.mkv").forEach {
            val color = assertNotNull(it.colorInfo)
            assertEquals(listOf(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_HLG, C.COLOR_RANGE_LIMITED, 10), listOf(color.colorSpace, color.colorTransfer, color.colorRange, color.lumaBitdepth))
            assertNull(color.hdrStaticInfo)
        }
    }

    /** A profile 8.1 as releases have it: the track keeps the HDR10 stream's colour, which agrees with the record's id. */
    @Test
    fun `a profile 8 record over an HDR10 stream keeps the stream's colour beside the record's facts`() {
        video("dv-p8-hdr10.mkv").forEach {
            assertEquals(MimeTypes.VIDEO_DOLBY_VISION to "dvhe.08.06", it.sampleMimeType to it.codecs)
            assertEquals(C.COLOR_TRANSFER_ST2084, it.colorInfo?.colorTransfer)
            assertEquals(812, uint16(assertNotNull(it.colorInfo?.hdrStaticInfo), 21))
            assertEquals(DolbyVisionRecord(1, enhancementLayer = false, baseLayer = true), it.matroskaFacts?.dolbyVision)
            assertTrue(it.matroskaFacts?.baseCodecs.orEmpty().startsWith("hvc1.2."), it.matroskaFacts?.baseCodecs)
        }
    }

    /**
     * Media3 appends a file's chapters to its tracks' formats after the TrackEntry ends, where the facts are set. The
     * player's extractor sends the formats once the Cues are read, the chapters with them; the probe's, which doesn't seek
     * for the Cues, sends them as the Tracks end, before the chapters.
     */
    @Test
    fun `the facts survive the chapters Media3 adds to the format`() {
        val (player, probe) = video("chapters.mkv")
        val entries = (0 until assertNotNull(player.metadata).length()).map(player.metadata!!::get)
        assertTrue(entries.any { it is Chapter }, "$entries")
        listOf(player, probe).forEach { assertEquals(100_000_000, it.matroskaFacts?.frameDurationNs) }
    }

    @Test
    fun `two reads of a file give equal formats, the facts among them`() {
        listOf("chapters.mkv", "dv-p8-hdr10.mkv", "rate-23976.mkv").forEach { name ->
            assertEquals(read(name), read(name), name)
            video(name).forEach { assertNotNull(it.matroskaFacts, name) }
        }
    }

    @Test
    fun `a record too short to hold the compatibility id is none`() {
        assertNull(DolbyVisionRecord.parse(byteArrayOf(1, 0, 16, 0x35)))
    }

    companion object {
        /** Strict reads, as MatroskaTrackFlagsTest has them: Media3's own test check throws on android.jar's stubs. */
        @BeforeClass
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

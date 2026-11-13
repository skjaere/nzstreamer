package io.skjaere.usenet.player

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [MediaCodecDeviceCapabilities] over decoder lists as the device gives them: MediaCodecList's (what plays at all, every
 * decoder counted) and Media3's (what the player chooses from, each marked hardware or not). Below API 29 Media3 marks
 * hardware by the decoder's name (OMX.google.*, c2.android.* are software), from 29 by the platform's flag; either way
 * its mark is what counts here, as it is what the player goes by.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DecoderFactsTest {
    private val display = DisplayFacts(true, ModeSize(3840, 2160), false, ModeSize(3840, 2160), ModeSize(1920, 1080), setOf(Hdr.Hdr10))

    /** A decoder that plays 16:9 frames up to [height] at film rate; null for one that hides its limits. */
    private fun decoder(height: Int?, hardware: Boolean = true, profiles: Map<Int, Int> = emptyMap()) =
        Decoder(hardware, profiles, height?.let { max -> { _: Int, h: Int -> h <= max } })

    private fun capabilities(
        listed: Map<String, List<Decoder>>,
        playback: Map<String, List<Decoder>> = listed,
        passthrough: Set<Int> = emptySet(),
    ) = MediaCodecDeviceCapabilities(listed, playback, Passthrough(passthrough), display)

    private val main10 = mapOf(CodecProfileLevel.HEVCProfileMain to 0, CodecProfileLevel.HEVCProfileMain10 to 0)

    @Test
    fun `a software decoder counts for the size, only a hardware one for the hardware height`() {
        // A box whose hardware HEVC decoder tops out at 1080p, beside the software one (c2.android.hevc) claiming 4K.
        val hevc = listOf(decoder(2160, hardware = false), decoder(1080, hardware = true))
        val device = capabilities(mapOf("video/hevc" to hevc))
        assertEquals(2160, device.maxHeight(VideoCodec.Hevc, tenBit = false))
        assertEquals(1080, device.hardwareMaxHeight(VideoCodec.Hevc, tenBit = false))
    }

    @Test
    fun `the ten-bit hardware height counts only the hardware decoders that list a ten-bit profile`() {
        val hevc = listOf(decoder(2160, profiles = mapOf(CodecProfileLevel.HEVCProfileMain to 0)), decoder(1080, profiles = main10))
        val device = capabilities(mapOf("video/hevc" to hevc))
        assertEquals(2160, device.hardwareMaxHeight(VideoCodec.Hevc, tenBit = false))
        assertEquals(1080, device.hardwareMaxHeight(VideoCodec.Hevc, tenBit = true))
    }

    /**
     * The hardware height comes from Media3's list alone: a decoder only MediaCodecList lists (one that needs secure
     * playback, ".secure") doesn't count for it, as the player never chooses it for a plain file.
     */
    @Test
    fun `none in hardware is 0, a hidden limit or a failed query unknown, and no decoder null`() {
        val device = capabilities(
            listed = mapOf(
                "video/hevc" to listOf(decoder(2160, hardware = true), decoder(1080, hardware = false)),
                "video/av01" to listOf(decoder(1080, hardware = false)),
                "video/x-vnd.on2.vp9" to listOf(decoder(null)),
                "video/mpeg2" to listOf(decoder(1080)),
            ),
            playback = mapOf(
                "video/hevc" to listOf(decoder(1080, hardware = false)),
                "video/av01" to listOf(decoder(1080, hardware = false)),
                "video/x-vnd.on2.vp9" to listOf(decoder(null)),
                // Media3's query for MPEG-2 failed: nothing to go by.
            ),
        )
        assertEquals(0, device.hardwareMaxHeight(VideoCodec.Hevc, tenBit = false))
        assertEquals(2160, device.maxHeight(VideoCodec.Hevc, tenBit = false))
        assertEquals(0, device.hardwareMaxHeight(VideoCodec.Av1, tenBit = false))
        assertNull(device.hardwareMaxHeight(VideoCodec.Vp9, tenBit = false))
        assertNull(device.hardwareMaxHeight(VideoCodec.Mpeg2, tenBit = false))
        assertNull(device.hardwareMaxHeight(VideoCodec.H264, tenBit = false))
        assertNull(device.hardwareMaxHeight(VideoCodec.Hevc, tenBit = true))
    }

    /** #64: Media3 reads VC-1 (Matroska's WVC1) as video/wvc1, the MIME type its decoders list. */
    @Test
    fun `VC-1 plays where a decoder takes video wvc1`() {
        val device = capabilities(mapOf("video/wvc1" to listOf(decoder(1080))))
        assertTrue(device.canDecode(VideoCodec.Vc1, tenBit = false))
        assertEquals(1080, device.maxHeight(VideoCodec.Vc1, tenBit = false))
        assertEquals(1080, device.hardwareMaxHeight(VideoCodec.Vc1, tenBit = false))
        assertFalse(device.canDecode(VideoCodec.Vc1, tenBit = true))
        assertFalse(capabilities(mapOf("video/avc" to listOf(decoder(2160)))).canDecode(VideoCodec.Vc1, tenBit = false))
    }

    @Test
    fun `the Dolby Vision profiles are named as Media3 names a track's, each at its highest level`() {
        val dv = decoder(
            2160,
            profiles = mapOf(
                CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd60,
                CodecProfileLevel.DolbyVisionProfileDvheStn to CodecProfileLevel.DolbyVisionLevelUhd60,
                CodecProfileLevel.DolbyVisionProfileDvavSe to CodecProfileLevel.DolbyVisionLevelFhd60,
            ),
        )
        val other = decoder(2160, profiles = mapOf(CodecProfileLevel.DolbyVisionProfileDvheStn to CodecProfileLevel.DolbyVisionLevelUhd24))
        val device = capabilities(mapOf("video/dolby-vision" to listOf(dv, other)))
        assertTrue(device.dolbyVision)
        assertEquals(listOf("dvav.09.05", "dvhe.05.09", "dvhe.08.09"), device.dolbyVisionProfiles)
        assertEquals("dav1.10.06", DolbyVisionProfile.of(CodecProfileLevel.DolbyVisionProfileDvav110, CodecProfileLevel.DolbyVisionLevelUhd24).codecs)
        assertEquals("dvhe.07.06", DolbyVisionProfile.of(CodecProfileLevel.DolbyVisionProfileDvheDtb, CodecProfileLevel.DolbyVisionLevelUhd24).codecs)
    }

    @Test
    fun `the log lines say the display, each codec's sizes and hardware, the Dolby Vision profiles and the audio`() {
        val device = capabilities(
            listed = mapOf(
                "video/avc" to listOf(decoder(2160)),
                "video/hevc" to listOf(decoder(2160, profiles = main10), decoder(2160, hardware = false)),
                "video/x-vnd.on2.vp9" to listOf(decoder(2160, profiles = mapOf(CodecProfileLevel.VP9Profile2 to 0))),
                "video/mp4v-es" to listOf(decoder(576, hardware = false)),
                "video/dolby-vision" to listOf(decoder(2160, profiles = mapOf(CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd60))),
                "audio/mp4a-latm" to listOf(decoder(null)),
                "audio/ac3" to listOf(decoder(null)),
            ),
            passthrough = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3),
        )
        assertEquals(
            listOf(
                "Display: 3840x2160 output, largest mode 3840x2160: 2160p; HDR: HDR10",
                "Video: H.264 2160p (hardware 2160p), HEVC 2160p (hardware 2160p), HEVC 10-bit 2160p (hardware 2160p), " +
                    "VP9 2160p (hardware 2160p), VP9 10-bit 2160p (hardware 2160p), XviD 576p (no hardware); " +
                    "Dolby Vision dvhe.08.09; no decoder for AV1, VC-1, MPEG-2",
                "Audio: AAC, DD decoded; DD, DD+ passed through over HDMI",
            ),
            device.lines,
        )
        assertEquals(device.lines.joinToString("\n"), device.toString())
        assertTrue(device.canDecode(AudioCodec.Eac3))
        val bare = capabilities(mapOf("video/avc" to listOf(decoder(null, hardware = false))))
        assertEquals(
            "Video: H.264 size unknown (no hardware); no Dolby Vision; no decoder for HEVC, AV1, VP9, XviD, VC-1, MPEG-2",
            bare.lines[1],
        )
        assertEquals("Audio: none decoded; none passed through over HDMI", bare.lines[2])
    }

    /**
     * Decoder lists shaped as the Shield's (MediaCodecDeviceCapabilitiesTest's lines there): MediaCodecList's
     * sizes beside Media3's hardware ones, which can be the larger (XviD, MPEG-2), Dolby Vision profiles 4, 5, 8 and 9,
     * no AV1, and DD and DD+ passed through.
     */
    private fun shieldLike(): MediaCodecDeviceCapabilities {
        val dolbyVision = listOf(
            CodecProfileLevel.DolbyVisionProfileDvheDtr, CodecProfileLevel.DolbyVisionProfileDvheStn,
            CodecProfileLevel.DolbyVisionProfileDvheSt, CodecProfileLevel.DolbyVisionProfileDvavSe,
        ).associateWith { CodecProfileLevel.DolbyVisionLevelUhd60 }
        val vp9 = mapOf(CodecProfileLevel.VP9Profile0 to 0)
        val vp9TenBit = mapOf(CodecProfileLevel.VP9Profile0 to 0, CodecProfileLevel.VP9Profile2 to 0)
        return capabilities(
            listed = mapOf(
                "video/avc" to listOf(decoder(2160)),
                "video/hevc" to listOf(decoder(2160, profiles = main10)),
                "video/x-vnd.on2.vp9" to listOf(decoder(2160, profiles = vp9), decoder(1080, hardware = false, profiles = vp9TenBit)),
                "video/mp4v-es" to listOf(decoder(576, hardware = false)),
                "video/wvc1" to listOf(decoder(2160)),
                "video/mpeg2" to listOf(decoder(1080)),
                "video/dolby-vision" to listOf(decoder(2160, profiles = dolbyVision)),
                "audio/mp4a-latm" to listOf(decoder(null)),
                "audio/flac" to listOf(decoder(null)),
                "audio/opus" to listOf(decoder(null)),
                "audio/mpeg" to listOf(decoder(null)),
                "audio/raw" to listOf(decoder(null)),
            ),
            playback = mapOf(
                "video/avc" to listOf(decoder(2160)),
                "video/hevc" to listOf(decoder(2160, profiles = main10)),
                "video/x-vnd.on2.vp9" to listOf(decoder(2160, profiles = vp9), decoder(1080, hardware = false, profiles = vp9TenBit)),
                "video/mp4v-es" to listOf(decoder(1080)),
                "video/wvc1" to listOf(decoder(1080)),
                "video/mpeg2" to listOf(decoder(2160)),
                "video/dolby-vision" to listOf(decoder(2160, profiles = dolbyVision)),
            ),
            passthrough = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3),
        )
    }

    /** The log's decoder lines word for word as the Shield's: LogsTest, RankingContextDeviceTest and the box's checks read them. */
    @Test
    fun `the Shield's decoders give the lines its log gave`() {
        assertEquals(
            listOf(
                "Video: H.264 2160p (hardware 2160p), HEVC 2160p (hardware 2160p), HEVC 10-bit 2160p (hardware 2160p), " +
                    "VP9 2160p (hardware 2160p), VP9 10-bit 1080p (no hardware), XviD 576p (hardware 1080p), " +
                    "VC-1 2160p (hardware 1080p), MPEG-2 1080p (hardware 2160p); " +
                    "Dolby Vision dvav.09.09, dvhe.04.09, dvhe.05.09, dvhe.08.09; no decoder for AV1",
                "Audio: AAC, FLAC, Opus, MP3, PCM decoded; DD, DD+ passed through over HDMI",
            ),
            shieldLike().decoderLines,
        )
    }

    /**
     * The same facts typed, for This device's rows: each codec by bit depth with its sizes as [MediaCodecDeviceCapabilities]
     * answers them (null unknown, 0 none), Dolby Vision's profiles by number and level, the codecs nothing decodes, the audio.
     */
    @Test
    fun `the decoder summary types what the lines say`() {
        val summary = shieldLike().decoderSummary
        assertEquals(
            listOf(
                VideoDecoding(VideoCodec.H264, tenBit = false, maxHeight = 2160, hardwareMaxHeight = 2160),
                VideoDecoding(VideoCodec.Hevc, tenBit = false, maxHeight = 2160, hardwareMaxHeight = 2160),
                VideoDecoding(VideoCodec.Hevc, tenBit = true, maxHeight = 2160, hardwareMaxHeight = 2160),
                VideoDecoding(VideoCodec.Vp9, tenBit = false, maxHeight = 2160, hardwareMaxHeight = 2160),
                VideoDecoding(VideoCodec.Vp9, tenBit = true, maxHeight = 1080, hardwareMaxHeight = 0),
                VideoDecoding(VideoCodec.Xvid, tenBit = false, maxHeight = 576, hardwareMaxHeight = 1080),
                VideoDecoding(VideoCodec.Vc1, tenBit = false, maxHeight = 2160, hardwareMaxHeight = 1080),
                VideoDecoding(VideoCodec.Mpeg2, tenBit = false, maxHeight = 1080, hardwareMaxHeight = 2160),
            ),
            summary.video,
        )
        assertTrue(summary.dolbyVision)
        assertEquals(listOf(9 to 9, 4 to 9, 5 to 9, 8 to 9), summary.dolbyVisionProfiles.map { it.profile to it.level })
        assertEquals(listOf("dvav.09.09", "dvhe.04.09", "dvhe.05.09", "dvhe.08.09"), summary.dolbyVisionProfiles.map { it.codecs })
        assertEquals(listOf("dvav.09", "dvhe.04", "dvhe.05", "dvhe.08"), summary.dolbyVisionProfiles.map { it.name })
        assertEquals(listOf(VideoCodec.Av1), summary.noDecoder)
        assertEquals(listOf(AudioCodec.Aac, AudioCodec.Flac, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Pcm), summary.audioDecoded)
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3), summary.audioPassedThrough)
        // The lines are the summary's.
        assertEquals(shieldLike().decoderLines, summary.lines)
    }

    @Test
    fun `a summary with nothing decoded, no Dolby Vision decoder, or one that lists no profiles says so`() {
        val bare = capabilities(mapOf("video/avc" to listOf(decoder(null, hardware = false)))).decoderSummary
        assertEquals(listOf(VideoDecoding(VideoCodec.H264, tenBit = false, maxHeight = null, hardwareMaxHeight = 0)), bare.video)
        assertFalse(bare.dolbyVision)
        assertEquals(emptyList(), bare.dolbyVisionProfiles)
        assertEquals(VideoCodec.entries - VideoCodec.H264, bare.noDecoder)
        assertEquals(emptyList(), bare.audioDecoded)
        assertEquals(emptyList(), bare.audioPassedThrough)

        val unlisted = DecoderSummary(emptyList(), dolbyVision = true, emptyList(), VideoCodec.entries, emptyList(), emptyList())
        assertEquals(
            listOf(
                "Video: none decoded; Dolby Vision with no profiles listed; no decoder for H.264, HEVC, AV1, VP9, XviD, VC-1, MPEG-2",
                "Audio: none decoded; none passed through over HDMI",
            ),
            unlisted.lines,
        )
        val small = DecoderSummary(listOf(VideoDecoding(VideoCodec.Mpeg2, false, 0, null)), false, emptyList(), emptyList(), emptyList(), listOf(AudioCodec.TrueHd))
        assertEquals(listOf("Video: MPEG-2 below 480p (hardware unknown); no Dolby Vision", "Audio: none decoded; TrueHD passed through over HDMI"), small.lines)
    }

    /** One probe's summary, whatever display the capabilities move to: the header asks it on every Copy, Share and crash. */
    @Test
    fun `the summary is worked out once for the decoders, not again on each display`() {
        val device = shieldLike()
        val moved = device.on(DisplayFacts(true, ModeSize(1920, 1080), false, ModeSize(3840, 2160), ModeSize(1920, 1080), hdr = null))
        assertSame(device.decoderSummary, moved.decoderSummary)
        assertSame(device.decoderSummary, device.decoderSummary)
    }

    @Test
    fun `on another display the decoders stay, the display's facts change`() {
        val device = capabilities(mapOf("video/hevc" to listOf(decoder(2160))))
        val moved = device.on(DisplayFacts(true, ModeSize(1920, 1080), false, ModeSize(3840, 2160), ModeSize(1920, 1080), hdr = null))
        assertEquals(2160, device.displayHeight)
        assertEquals(setOf(Hdr.Hdr10), device.hdrTypes)
        assertEquals(1080, moved.displayHeight)
        // Unknown HDR holds no release to the screen's HDR, as before the display was asked.
        assertEquals(Hdr.entries.toSet(), moved.hdrTypes)
        assertEquals(2160, moved.hardwareMaxHeight(VideoCodec.Hevc, tenBit = false))
    }

    private val dd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3))
    private val withTrueHd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD))

    /**
     * TCR-1: the route read again says the same, so the capabilities holding it are equal and auto-advance's prefetched
     * search is still handed to the play; another route isn't, and the first again is.
     */
    @Test
    fun `the route passed through is compared by value`() {
        val device = shieldLike()
        assertSame(device, device.with(Passthrough(setOf(C.ENCODING_E_AC3, C.ENCODING_AC3))))
        val receiverOn = device.with(withTrueHd)
        assertNotEquals(device, receiverOn)
        val back = receiverOn.with(dd)
        assertEquals(device, back)
        assertEquals(device.hashCode(), back.hashCode())
        assertEquals(device.on(display), back.on(display))
    }

    /**
     * Two routes that answer every codec alike are one for the ranking: E-AC-3 JOC (Atmos) is DD+ as E-AC-3 is, and DTS
     * already plays every DTS codec's core, so DTS-HD beside it adds none. What was read is kept as it was.
     */
    @Test
    fun `routes that pass the same codecs through are equal`() {
        val atmos = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC))
        assertEquals(dd, atmos)
        assertEquals(dd.hashCode(), atmos.hashCode())
        assertEquals(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC), atmos.encodings)
        val dts = Passthrough(setOf(C.ENCODING_DTS))
        assertEquals(dts, Passthrough(setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)))
        assertEquals(dts.hashCode(), Passthrough(setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)).hashCode())
        // DTS-HD alone plays no plain DTS track: another route.
        assertNotEquals(dts, Passthrough(setOf(C.ENCODING_DTS_HD)))
        assertNotEquals(dd, withTrueHd)
        val device = shieldLike()
        assertSame(device, device.with(atmos))
    }

    /** A receiver switched on: the decoders are the probe's, what passes through is the route's, in the summary and the lines too. */
    @Test
    fun `with another route the decoders stay and what passes through follows it`() {
        val device = shieldLike()
        assertFalse(device.canDecode(AudioCodec.TrueHd))
        val receiverOn = device.with(withTrueHd)
        assertTrue(receiverOn.canDecode(AudioCodec.TrueHd))
        assertEquals(withTrueHd, receiverOn.passthrough)
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.TrueHd), receiverOn.decoderSummary.audioPassedThrough)
        assertEquals(device.decoderSummary.video, receiverOn.decoderSummary.video)
        assertEquals("Audio: AAC, FLAC, Opus, MP3, PCM decoded; DD, DD+, TrueHD passed through over HDMI", receiverOn.decoderLines[1])
        // The probe's summary is still its own.
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3), device.decoderSummary.audioPassedThrough)
        // Moved to another display, it keeps the route.
        assertTrue(receiverOn.on(DisplayFacts(true, ModeSize(1920, 1080), false, ModeSize(3840, 2160), ModeSize(1920, 1080), hdr = null)).canDecode(AudioCodec.TrueHd))
    }

    /** A route changes for the ranking only in what it asks about: an encoding it doesn't, or a channel count, isn't a change. */
    @Test
    fun `a route read from Media3 keeps only the encodings the ranking asks about`() {
        val read = Passthrough.of(AudioCapabilities(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD, C.ENCODING_AAC_LC, C.ENCODING_OPUS), 8))
        assertEquals(withTrueHd, read)
        assertEquals(read, Passthrough.of(AudioCapabilities(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD), 6)))
        assertEquals(Passthrough.NONE, Passthrough.of(null))
        // Said as the log header says it; a DTS core counts for every DTS codec, as the ranking reads it.
        assertEquals("DD, DD+, TrueHD", withTrueHd.said)
        assertEquals("none", Passthrough.NONE.said)
        assertEquals(listOf(AudioCodec.Dts, AudioCodec.DtsHd, AudioCodec.DtsHdMa, AudioCodec.DtsX), Passthrough(setOf(C.ENCODING_DTS)).codecs)
    }
}

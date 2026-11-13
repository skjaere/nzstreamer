package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.releases.Hdr
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the track probe makes of a file's tracks as the real renderers rate them ([ExoTrackProbe.evaluate]). Video no decoder
 * takes would play its sound over a black screen, as the player selects none of it: it is no playable video, so the next
 * release is tried. Video a decoder takes beyond what it claims (a level, a size) plays when a decoder takes its codec and
 * profile, as the player tries it; Dolby Vision so rated also plays where the player tries a Dolby Vision decoder. Video is
 * checked before audio. A playable file's answer carries what the TV's frame-rate match reads of its video ([ExoTrackProbe.videoOf]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoTrackProbeTest {
    private val aac = TrackFixtures.audio("2", "en")
    private val eac3 = TrackFixtures.audio("3", "en", MimeTypes.AUDIO_E_AC3, channels = 6)
    private fun dolbyVision(profile: Int, id: String = "1") =
        TrackFixtures.video(id, MimeTypes.VIDEO_DOLBY_VISION, "dvhe.0$profile.06", 3840, 2160, bitDepth = 10)
    private val hevc10 = TrackFixtures.video("1", MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", 3840, 2160, bitDepth = 10)

    /** A track Media3 rates handled, or no decoder takes at all, needs no decoder's word: the test fails if one is asked. */
    private val notAsked: (Format) -> Boolean = { error("a decoder was asked about ${it.sampleMimeType}") }

    private fun evaluate(tracks: Tracks, decodes: (Format) -> Boolean = notAsked, triesDolbyVision: (Format) -> Boolean = notAsked) =
        ExoTrackProbe.evaluate(tracks, decodes, triesDolbyVision)

    @Test
    fun `video no decoder takes is no playable video, named as the ranker names releases`() {
        // Dolby Vision profile 7 on a device without a Dolby Vision decoder: Media3 has no other decoder for it.
        val tracks = TrackFixtures.tracks(listOf(dolbyVision(7), aac), support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE))
        assertEquals(ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")), evaluate(tracks))
        // Two tracks alike are named once.
        val twice = TrackFixtures.tracks(
            listOf(dolbyVision(7), dolbyVision(7, id = "5"), aac),
            support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE, "5" to C.FORMAT_UNSUPPORTED_SUBTYPE),
        )
        assertEquals(ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")), evaluate(twice))
    }

    @Test
    fun `video beyond what its decoder claims plays only when a decoder takes its codec and profile`() {
        // Ten-bit HEVC beside HEVC decoders that list no Main 10 (phone34's) won't play; beside one that under-reports
        // its level or size, it does, and the player tries it.
        val tracks = TrackFixtures.tracks(listOf(hevc10, aac), support = mapOf("1" to C.FORMAT_EXCEEDS_CAPABILITIES))
        assertEquals(ProbeResult.NoPlayableVideo(listOf("2160p HEVC 10-bit")), evaluate(tracks, decodes = { false }))
        assertEquals(ProbeResult.Playable(), evaluate(tracks, decodes = { it == hevc10 }))
    }

    @Test
    fun `Dolby Vision beyond what its decoder claims plays where the player tries a Dolby Vision decoder`() {
        // A TV box's Dolby Vision decoder that doesn't list the track's profile or level: the player tries it, and so the
        // box isn't refused a file its decoder may play.
        val tracks = TrackFixtures.tracks(listOf(dolbyVision(7), aac), support = mapOf("1" to C.FORMAT_EXCEEDS_CAPABILITIES))
        assertEquals(ProbeResult.Playable(), evaluate(tracks, triesDolbyVision = { true }))
    }

    /**
     * Without a Dolby Vision decoder (or on a screen without Dolby Vision), Media3 plays profile 8 on an HEVC decoder as
     * HEVC Main 10: rated beyond what the HEVC decoders claim, it plays only where one takes Main 10, as plain HEVC 10-bit
     * does.
     */
    @Test
    fun `Dolby Vision the player plays on a plain decoder plays only where that decoder takes its profile`() {
        val tracks = TrackFixtures.tracks(listOf(dolbyVision(8), aac), support = mapOf("1" to C.FORMAT_EXCEEDS_CAPABILITIES))
        assertEquals(ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 8")), evaluate(tracks, decodes = { false }, triesDolbyVision = { false }))
        assertEquals(ProbeResult.Playable(), evaluate(tracks, decodes = { it == dolbyVision(8) }, triesDolbyVision = { false }))
    }

    @Test
    fun `one playable video track is enough, and a file without video plays as before`() {
        val vc1 = TrackFixtures.video("0", "video/wvc1", width = 1920, height = 1080)
        val oneOfTwo = TrackFixtures.tracks(listOf(vc1, TrackFixtures.video("1"), aac), support = mapOf("0" to C.FORMAT_UNSUPPORTED_SUBTYPE))
        assertEquals(ProbeResult.Playable(), evaluate(oneOfTwo))
        // Audio alone, and nothing at all.
        assertEquals(ProbeResult.Playable(), evaluate(TrackFixtures.tracks(listOf(aac))))
        assertEquals(ProbeResult.Playable(), evaluate(TrackFixtures.tracks(emptyList())))
        // Cover art is an image track, not video.
        val cover = Format.Builder().setId("4").setSampleMimeType(MimeTypes.IMAGE_JPEG).build()
        assertEquals(ProbeResult.Playable(), evaluate(TrackFixtures.tracks(listOf(cover, aac), support = mapOf("4" to C.FORMAT_UNSUPPORTED_TYPE))))
    }

    /**
     * Media3's extractor drops a video track whose codec it doesn't read (VVC or MPEG-1 in Matroska), saying nothing: the
     * file looks like audio alone, which plays. The log says the probe found no video, so it explains a black screen.
     */
    @Test
    fun `a file without video is logged as such, with its audio`() {
        assertEquals("no video track (audio: AAC (en), E-AC-3 (en))", ExoTrackProbe.noVideoLine(TrackFixtures.tracks(listOf(aac, eac3))))
        assertEquals("no video track (audio: none)", ExoTrackProbe.noVideoLine(TrackFixtures.tracks(listOf(TrackFixtures.text("5", "en")))))
        assertNull(ExoTrackProbe.noVideoLine(TrackFixtures.tracks(listOf(TrackFixtures.video("1"), aac))))
    }

    @Test
    fun `audio decides once the video plays, and video is told when neither does`() {
        val withEac3 = TrackFixtures.tracks(listOf(TrackFixtures.video("1"), eac3), unsupported = setOf("3"))
        assertEquals(ProbeResult.NoPlayableAudio(listOf("E-AC-3 (en)")), evaluate(withEac3))
        // With no picture the sound doesn't matter: the video says why it can't play.
        val neither = TrackFixtures.tracks(listOf(dolbyVision(5), eac3), support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE), unsupported = setOf("3"))
        assertEquals(ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 5")), evaluate(neither))
    }

    @Test
    fun `video is named by its resolution, codec and ten bits, as release names are`() {
        fun label(format: Format) = VideoTracks.label(format)
        assertEquals("1080p VC-1", label(TrackFixtures.video("1", "video/wvc1", width = 1920, height = 1080)))
        assertEquals("1080p MPEG-2", label(TrackFixtures.video("1", MimeTypes.VIDEO_MPEG2, width = 1920, height = 1080)))
        // A film cropped to 2.40:1 is still 1080p, as an indexer's MediaInfo sizes are read.
        assertEquals("1080p H.264", label(TrackFixtures.video("1", width = 1920, height = 800)))
        assertEquals("720p AV1 10-bit", label(TrackFixtures.video("1", MimeTypes.VIDEO_AV1, width = 1280, height = 720, bitDepth = 10)))
        assertEquals("576p MPEG-4", label(TrackFixtures.video("1", MimeTypes.VIDEO_MP4V, width = 720, height = 576)))
        // Dolby Vision's profile says its depth; one whose codecs don't parse is Dolby Vision alone.
        assertEquals("2160p Dolby Vision profile 8", label(dolbyVision(8)))
        assertEquals("Dolby Vision", label(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        // A codec Media3 can't place, and one the probe has no name for.
        assertEquals("unknown codec", label(Format.Builder().setSampleMimeType("video/x-unknown").build()))
        assertEquals("480p video/x-flv", label(TrackFixtures.video("1", "video/x-flv", width = 640, height = 480)))
    }

    @Test
    fun `each video track is logged with its raw codecs and how it was rated`() {
        assertEquals(
            "video 0: video/dolby-vision dvhe.07.06 3840x2160 10-bit, unsupported subtype",
            ExoTrackProbe.videoLine(0, dolbyVision(7), C.FORMAT_UNSUPPORTED_SUBTYPE, decodes = null),
        )
        assertEquals(
            "video 1: video/hevc hvc1.2.4.L153.B0 3840x2160 10-bit, exceeds capabilities, no decoder takes its profile",
            ExoTrackProbe.videoLine(1, hevc10, C.FORMAT_EXCEEDS_CAPABILITIES, decodes = false),
        )
        assertEquals(
            "video 0: video/avc - 160x90, handled",
            ExoTrackProbe.videoLine(0, TrackFixtures.video("1"), C.FORMAT_HANDLED, decodes = null),
        )
        assertEquals(
            "video 0: video/hevc hvc1.2.4.L153.B0 3840x2160 10-bit, exceeds capabilities, a decoder takes its profile",
            ExoTrackProbe.videoLine(0, hevc10, C.FORMAT_EXCEEDS_CAPABILITIES, decodes = true),
        )
        assertEquals(
            "video 0: video/dolby-vision dvhe.07.06 3840x2160 10-bit, exceeds capabilities, no decoder takes its profile, a Dolby Vision decoder is tried",
            ExoTrackProbe.videoLine(0, dolbyVision(7), C.FORMAT_EXCEEDS_CAPABILITIES, decodes = false, triesDolbyVision = true),
        )
    }

    private fun answer(tracks: Tracks, decodes: (Format) -> Boolean = notAsked, triesDolbyVision: (Format) -> Boolean = notAsked) =
        ExoTrackProbe.answer(tracks, 2_640_000, decodes, triesDolbyVision)

    /** [format] with its colours as an HDR file's header gives them: BT.2020, [transfer] (PQ for HDR10, HLG), ten bits. */
    private fun hdr(format: Format, transfer: Int): Format = format.buildUpon()
        .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(transfer).setLumaBitdepth(10).setChromaBitdepth(10).build())
        .build()

    /**
     * L129: the answer carries the file's tracks, each kind in the file's order, as plain values: what a Cast device's
     * verdict reads, as the device plays the first video track and the first audio track it can play. Subtitles are read as
     * the probe's player reads them, parsed while extracting, so their own format is in their codecs.
     */
    @Test
    fun `the answer carries each track in the file's order, as plain values`() {
        val dtsHd = TrackFixtures.audio("2", "de", MimeTypes.AUDIO_DTS_HD, channels = 8)
        val ac3 = TrackFixtures.audio("3", "en", MimeTypes.AUDIO_AC3, channels = 6, selection = C.SELECTION_FLAG_DEFAULT)
        val forced = TrackFixtures.parsedWhileExtracting(TrackFixtures.text("4", "en", selection = C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_DEFAULT))
        val pgs = TrackFixtures.parsedWhileExtracting(TrackFixtures.text("5", "de", MimeTypes.APPLICATION_PGS))
        val answer = answer(TrackFixtures.tracks(listOf(hdr(hevc10, C.COLOR_TRANSFER_ST2084), dtsHd, ac3, forced, pgs)))
        assertEquals(
            ProbedTracks(
                video = listOf(ProbedTrack(MimeTypes.VIDEO_H265, "HEVC", width = 3840, height = 2160, bitDepth = 10, hdr = Hdr.Hdr10)),
                audio = listOf(
                    ProbedTrack(MimeTypes.AUDIO_DTS_HD, "DTS-HD", language = "de", channels = 8),
                    ProbedTrack(MimeTypes.AUDIO_AC3, "AC-3", language = "en", default = true, channels = 6),
                ),
                text = listOf(
                    ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", language = "en", default = true, forced = true),
                    ProbedTrack(MimeTypes.APPLICATION_PGS, "PGS", language = "de"),
                ),
            ),
            assertIs<ProbeResult.Playable>(answer).tracks,
        )
    }

    /**
     * Dolby Vision says its profile and is its own HDR, HLG is read from the colours, a codec with no name goes by its MIME
     * type, and what the file doesn't say (a size, a bit depth, a language, its channels) is left unknown. Cover art is an
     * image, none of the three kinds.
     */
    @Test
    fun `the summary says Dolby Vision's profile and HDR, and leaves what the file doesn't say unknown`() {
        val hlg = hdr(TrackFixtures.video("6", width = 1920, height = 1080), C.COLOR_TRANSFER_HLG)
        val unsaid = Format.Builder().setId("7").setSampleMimeType(MimeTypes.AUDIO_OPUS).setLanguage(C.LANGUAGE_UNDETERMINED).build()
        val cover = Format.Builder().setId("8").setSampleMimeType(MimeTypes.IMAGE_JPEG).build()
        val odd = Format.Builder().setId("9").setSampleMimeType("video/x-flv").build()
        val tracks = TrackFixtures.tracks(listOf(dolbyVision(8), hlg, odd, unsaid, cover), support = mapOf("8" to C.FORMAT_UNSUPPORTED_TYPE))
        assertEquals(
            ProbedTracks(
                video = listOf(
                    ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 3840, height = 2160, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 8),
                    ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.Hlg),
                    ProbedTrack("video/x-flv", "video/x-flv"),
                ),
                audio = listOf(ProbedTrack(MimeTypes.AUDIO_OPUS, "Opus")),
            ),
            assertIs<ProbeResult.Playable>(answer(tracks)).tracks,
        )
    }

    /**
     * CFR-4: DTS Express and DTS:X, whose MIME types carry a profile ("audio/vnd.dts.hd;profile=lbr"), are named as the rest
     * of the family is, in the summary and in what this device can't play: a cast's pre-check and its failures show those
     * names, never a MIME type.
     */
    @Test
    fun `DTS Express and DTS-X are named by their codec, never by their MIME type`() {
        val express = TrackFixtures.audio("2", "en", MimeTypes.AUDIO_DTS_EXPRESS, channels = 2)
        val dtsX = TrackFixtures.audio("3", "en", MimeTypes.AUDIO_DTS_X, channels = 8)
        val silent = answer(TrackFixtures.tracks(listOf(TrackFixtures.video("1"), express, dtsX), unsupported = setOf("2", "3")))
        assertEquals(listOf("DTS Express (en)", "DTS:X (en)"), assertIs<ProbeResult.NoPlayableAudio>(silent).found)
        assertEquals(
            listOf(ProbedTrack(MimeTypes.AUDIO_DTS_EXPRESS, "DTS Express", "en", channels = 2), ProbedTrack(MimeTypes.AUDIO_DTS_X, "DTS:X", "en", channels = 8)),
            silent.tracks?.audio,
        )
    }

    /** CFR-3: MPEG audio's layers 1 and 2 (MP2, which DVB captures carry) are named as release names say them. */
    @Test
    fun `MP1 and MP2 are named, not shown as their MIME types`() {
        val mp2 = TrackFixtures.audio("2", "cs", MimeTypes.AUDIO_MPEG_L2)
        val mp1 = TrackFixtures.audio("3", "cs", MimeTypes.AUDIO_MPEG_L1)
        val silent = answer(TrackFixtures.tracks(listOf(TrackFixtures.video("1"), mp2, mp1), unsupported = setOf("2", "3")))
        assertEquals(listOf("MP2 (cs)", "MP1 (cs)"), assertIs<ProbeResult.NoPlayableAudio>(silent).found)
        assertEquals(listOf("MP2", "MP1"), silent.tracks?.audio?.map { it.label })
    }

    /** Whatever this device's verdict, the tracks come with it: a cast's verdict is the device's own, not this phone's (L129). */
    @Test
    fun `a file this device can't play carries its tracks too`() {
        val silent = answer(TrackFixtures.tracks(listOf(TrackFixtures.video("1"), eac3), unsupported = setOf("3")))
        assertEquals(listOf("E-AC-3 (en)"), assertIs<ProbeResult.NoPlayableAudio>(silent).found)
        assertEquals(
            ProbedTracks(video = listOf(ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 160, height = 90)), audio = listOf(ProbedTrack(MimeTypes.AUDIO_E_AC3, "E-AC-3", "en", channels = 6))),
            silent.tracks,
        )
        val dark = answer(TrackFixtures.tracks(listOf(dolbyVision(7), aac), support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE)))
        assertEquals(listOf("2160p Dolby Vision profile 7"), assertIs<ProbeResult.NoPlayableVideo>(dark).found)
        assertEquals(
            ProbedTracks(
                video = listOf(ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 3840, height = 2160, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 7)),
                audio = listOf(ProbedTrack(MimeTypes.AUDIO_AAC, "AAC", "en", channels = 2)),
            ),
            dark.tracks,
        )
    }

    /**
     * A Cast device may play what this device can't (L129), and its play needs what the probe read of the video (its length,
     * whether there is a picture): a file this device can't play says it too.
     */
    @Test
    fun `a file this device can't play still says what its video is`() {
        val matroska = probed("rate-23976.mkv")
        val silent = answer(TrackFixtures.tracks(listOf(matroska, eac3), unsupported = setOf("3")))
        val read = assertNotNull(assertIs<ProbeResult.NoPlayableAudio>(silent).video)
        assertEquals(24_000.0 / 1_001, assertNotNull(read.fps), 1e-6)
        assertEquals(2_640_000L, read.durationMs)
        val dark = answer(TrackFixtures.tracks(listOf(dolbyVision(7), aac), support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE)))
        assertEquals(ProbedVideo(fps = null, durationMs = 2_640_000, dolbyVision = true), assertIs<ProbeResult.NoPlayableVideo>(dark).video)
        // Audio alone: no video to say.
        assertNull(assertIs<ProbeResult.NoPlayableAudio>(answer(TrackFixtures.tracks(listOf(eac3), unsupported = setOf("3")))).video)
    }

    /** The summary keeps the module of PlayPipeline free of the player's types (L129): its fields are plain values. */
    @Test
    fun `the summary holds no player type`() {
        val fields = listOf(ProbedTracks::class.java, ProbedTrack::class.java)
            .flatMap { type -> type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) } }
            .map { it.genericType.typeName }
        val plain = setOf("java.lang.String", "java.lang.Integer", "boolean", Hdr::class.java.name, "java.util.List<${ProbedTrack::class.java.name}>")
        assertEquals(14, fields.size)
        fields.forEach { assertTrue(it in plain, "$it isn't a plain value") }
    }

    /** [name]'s video format as the probe's extractor reads it (it doesn't seek for the Cues). */
    private fun probed(name: String): Format =
        ExtractedFormats.read(ExtractedFormats.fixture(name), TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES).matroska())
            .values.single { MimeTypes.isVideo(it.sampleMimeType) }

    /**
     * What the probe reads of the video for the TV's frame-rate match, before the player exists: the rate as File details
     * read it (Matroska's DefaultDuration, which Media3 1.11.1 leaves out of the format, else the container's own, an
     * MP4's), the length, and whether it is Dolby Vision.
     */
    @Test
    fun `the video's frame rate comes from Matroska's DefaultDuration, else the container's, with its length and Dolby Vision`() {
        val matroska = assertNotNull(ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(probed("rate-23976.mkv"), aac)), durationMs = 1_128))
        assertEquals(24_000.0 / 1_001, assertNotNull(matroska.fps), 1e-6)
        assertEquals(1_128L, matroska.durationMs)
        assertFalse(matroska.dolbyVision)
        // An MP4's, which Media3 reads from its samples.
        val mp4 = TrackFixtures.video("1").buildUpon().setFrameRate(25f).build()
        assertEquals(ProbedVideo(25.0, durationMs = null, dolbyVision = false), ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(mp4, aac)), C.TIME_UNSET))
        // Matroska's first, as File details read it.
        val both = probed("rate-23976.mkv").buildUpon().setFrameRate(25f).build()
        assertEquals(24_000.0 / 1_001, assertNotNull(ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(both)), C.TIME_UNSET)?.fps), 1e-6)
        // Dolby Vision by its MIME type, whatever decoder plays it.
        assertTrue(assertNotNull(ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(probed("dv-p8.mkv"), aac)), 60_000)).dolbyVision)
    }

    @Test
    fun `the video read is the selected track's, else the first's, and there is none without video`() {
        val first = TrackFixtures.video("1").buildUpon().setFrameRate(25f).build()
        val second = TrackFixtures.video("2").buildUpon().setFrameRate(50f).build()
        assertEquals(50.0, ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(first, second), selected = setOf("2")), C.TIME_UNSET)?.fps)
        assertEquals(25.0, ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(first, second)), C.TIME_UNSET)?.fps)
        assertNull(ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(aac, eac3)), 60_000))
    }

    /** No DefaultDuration (a variable frame rate), one of 0 (a broken header), Media3's NO_VALUE, a length not known. */
    @Test
    fun `a rate or length the file doesn't state is unknown, never a number a launch can't hold`() {
        val variable = TrackFixtures.video("1").buildUpon().setMetadata(Metadata(MatroskaVideoFacts(frameDurationNs = null))).build()
        val broken = TrackFixtures.video("1").buildUpon().setMetadata(Metadata(MatroskaVideoFacts(frameDurationNs = 0))).build()
        listOf(variable, broken, TrackFixtures.video("1")).forEach {
            assertEquals(ProbedVideo(fps = null, durationMs = 2_640_000, dolbyVision = false), ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(it)), 2_640_000), "$it")
        }
        listOf(C.TIME_UNSET, 0L, -5L).forEach { assertNull(ExoTrackProbe.videoOf(TrackFixtures.tracks(listOf(variable)), it)?.durationMs, "$it") }
    }

    companion object {
        /** Strict reads, as MatroskaVideoFactsTest has them: Media3's own test check throws on android.jar's stubs. */
        @BeforeAll
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

package io.skjaere.usenet.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The video fixtures (see the README beside them) as the player's and the probe's extractors read them: a Dolby Vision
 * configuration record makes the track `video/dolby-vision`, its profile in the codecs, whatever the codec under it, and
 * the 10-bit ones carry their bit depth. The probe's device test rests on these; a Media3 upgrade that reads them
 * otherwise fails here, not on a TV.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoFixturesTest {
    private fun video(name: String): List<Format> =
        listOf(TrackFlagsExtractorsFactory(), TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)).map { extractors ->
            val formats = ExtractedFormats.read(ExtractedFormats.fixture(name), extractors.matroska())
            // Track numbers: the video is 1, its AAC audio 2.
            assertEquals(MimeTypes.AUDIO_AAC, formats.getValue("2").sampleMimeType, name)
            formats.getValue("1")
        }

    @Test
    fun `a Dolby Vision record makes the track Dolby Vision, profile and level in its codecs`() {
        video("dv-p5.mkv").forEach { assertEquals(MimeTypes.VIDEO_DOLBY_VISION to "dvhe.05.06", it.sampleMimeType to it.codecs) }
        video("dv-p7.mkv").forEach { assertEquals(MimeTypes.VIDEO_DOLBY_VISION to "dvhe.07.06", it.sampleMimeType to it.codecs) }
        video("dv-p8.mkv").forEach { assertEquals(MimeTypes.VIDEO_DOLBY_VISION to "dvhe.08.06", it.sampleMimeType to it.codecs) }
    }

    @Test
    fun `the others read as their codec, ten-bit ones with their bit depth`() {
        video("hevc10.mkv").forEach {
            assertEquals(MimeTypes.VIDEO_H265, it.sampleMimeType)
            // HEVC's profile 2 is Main 10.
            assertTrue(it.codecs.orEmpty().startsWith("hvc1.2."), it.codecs)
            assertEquals(10, it.colorInfo?.lumaBitdepth)
        }
        video("av1-10bit.mkv").forEach {
            assertEquals(MimeTypes.VIDEO_AV1, it.sampleMimeType)
            assertEquals(10, it.colorInfo?.lumaBitdepth)
        }
        video("mpeg2.mkv").forEach { assertEquals(MimeTypes.VIDEO_MPEG2, it.sampleMimeType) }
        // Main (profile 1) at level 6.2 (L186), 8-bit: the device test's format.
        video("hevc-level62.mkv").forEach {
            assertEquals(Triple(MimeTypes.VIDEO_H265, "hvc1.1.6.L186.90", 160 to 90), Triple(it.sampleMimeType, it.codecs, it.width to it.height))
            assertEquals(8, it.colorInfo?.lumaBitdepth)
        }
        video("dv-p7.mkv").forEach { assertEquals(160 to 90, it.width to it.height) }
    }

    companion object {
        /** Strict reads, as MatroskaTrackFlagsTest has them: Media3's own test check throws on android.jar's stubs. */
        @BeforeAll
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matroska's track flags as the player's and the probe's extractors read them from `flags.mkv`
 * (see the README beside it): each flag in its track's role flags, a flag set to 0 and a track
 * only named so adding none.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MatroskaTrackFlagsTest {
    private val fixture = ExtractedFormats.fixture("flags.mkv")

    @Test
    fun `the player's extractor puts each flag in its track's role flags`() {
        // It seeks for the Cues at the file's end, and sends the formats after reading them.
        assertFlags(ExtractedFormats.read(fixture, TrackFlagsExtractorsFactory().matroska()))
    }

    @Test
    fun `the probe's extractor, which reads only the header, does too`() {
        assertFlags(ExtractedFormats.read(fixture, TrackFlagsExtractorsFactory(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES).matroska()))
    }

    @Test
    fun `a play's media source keeps them too, its text subtitles parsed while extracting`() {
        // PlayerFactory's media source gives its extractors subtitle parsers of its own: text ones are parsed as Media3's
        // default has them, so overlapping cues show together (PlaySubtitleFormatsTest has each format).
        val extractors = TrackFlagsExtractorsFactory()
        PlayerFactory.mediaSourceFactory(DataSource.Factory { error("the media source reads nothing here") }, extractors)
        assertFlags(ExtractedFormats.read(fixture, extractors.matroska()))
    }

    /** The fixture's flags in [formats]; its subtitle track's format is [subtitles]: parsed while extracting, Media3's default, unless told otherwise. */
    private fun assertFlags(formats: Map<String, Format>, subtitles: String = MimeTypes.APPLICATION_MEDIA3_CUES) {
        // Track numbers: the video is 1, the audio 2 to 8, the subtitles 9.
        assertEquals((1..9).map(Int::toString).toSet(), formats.keys)
        val roles = formats.mapValues { it.value.roleFlags }
        assertEquals(C.ROLE_FLAG_COMMENTARY, roles["2"], "FlagCommentary")
        assertEquals(0, roles["3"], "no flags")
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, roles["4"], "FlagVisualImpaired")
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, roles["5"], "FlagHearingImpaired on audio")
        assertEquals(C.ROLE_FLAG_MAIN, roles["6"], "FlagOriginal")
        assertEquals(0, roles["7"], "FlagOriginal 0")
        assertEquals(0, roles["8"], "a name only")
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, roles["9"], "FlagHearingImpaired on subtitles")
        assertEquals(0, roles["1"], "video")
        // What Media3 read before stays: the default flag, the name, and the subtitles' format.
        assertTrue(formats.getValue("2").selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
        assertEquals("Commentary with the crew", formats.getValue("8").label)
        assertEquals("ja", formats.getValue("6").language)
        assertEquals(subtitles, formats.getValue("9").sampleMimeType)
    }

    companion object {
        /**
         * Media3 checks its reads strictly in tests, which it tells by Build.FINGERPRINT first: null in
         * android.jar's stubs, so that check throws. Strict, as Media3 would have chosen.
         */
        @BeforeAll
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

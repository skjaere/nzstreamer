package io.skjaere.usenet.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * What a play's media source makes of each subtitle format in `subtitles.mkv` (see the README beside it). Parsed while
 * extracting, a track's cues come with the parser's way of showing them: merged with the cues they overlap, or replacing
 * them. Left as the file has them, the text renderer decodes each sample on its own, and a sample's cues replace the
 * last one's.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaySubtitleFormatsTest {
    private val fixture = ExtractedFormats.fixture("subtitles.mkv")

    @Test
    fun `text subtitles and DVB are parsed while extracting, PGS and VobSub left as the file has them`() {
        val extractors = DecidingOnly(TrackFlagsExtractorsFactory())
        PlayerFactory.mediaSourceFactory(DataSource.Factory { error("the media source reads nothing here") }, extractors)
        // Track numbers: the video is 1, the audio 2, the subtitles 3 to 10.
        val subtitles = ExtractedFormats.read(fixture, extractors.play.matroska()).filterKeys { it.toInt() >= 3 }
        assertEquals(
            mapOf(
                "3" to "raw ${MimeTypes.APPLICATION_PGS}",
                "4" to "raw ${MimeTypes.APPLICATION_PGS}",
                "5" to "parsed ${MimeTypes.APPLICATION_SUBRIP}, merged",
                "6" to "parsed ${MimeTypes.TEXT_SSA}, merged",
                "7" to "raw ${MimeTypes.APPLICATION_VOBSUB}",
                "8" to "parsed ${MimeTypes.TEXT_SSA}, merged",
                "9" to "parsed ${MimeTypes.APPLICATION_SUBRIP}, merged",
                // DVB's parser keeps the page, regions and palettes earlier samples set up: parsed all along, a track
                // switched on mid-play shows its current cue, where a fresh decoder may wait for the next acquisition point.
                "10" to "parsed ${MimeTypes.APPLICATION_DVBSUBS}, replaced",
            ),
            subtitles.mapValues { describe(it.value) },
        )
    }

    @Test
    fun `the formats with no track in the fixture are parsed while extracting too, as Media3 parses them`() {
        val parsers = PlaySubtitleParserFactory()
        val media3 = DefaultSubtitleParserFactory()
        listOf(MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT, MimeTypes.APPLICATION_TTML, MimeTypes.APPLICATION_TX3G).forEach { mime ->
            val format = Format.Builder().setSampleMimeType(mime).build()
            assertTrue(parsers.supportsFormat(format), mime)
            assertEquals(media3.getCueReplacementBehavior(format), parsers.getCueReplacementBehavior(format), mime)
        }
        listOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB).forEach { mime ->
            val format = Format.Builder().setSampleMimeType(mime).build()
            assertFalse(parsers.supportsFormat(format), mime)
            assertFailsWith<IllegalArgumentException>(mime) { parsers.create(format) }
        }
    }

    /**
     * [play], a play's extractors, given the subtitle parsers the media source gives them, but that a parser parses
     * nothing: Media3's SSA parser reads its header with android.text.TextUtils, which is a stub on the JVM.
     */
    private class DecidingOnly(val play: TrackFlagsExtractorsFactory) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = play.createExtractors()

        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
            play.setSubtitleParserFactory(object : SubtitleParser.Factory by subtitleParserFactory {
                override fun create(format: Format): SubtitleParser {
                    check(subtitleParserFactory.supportsFormat(format)) { "a parser for ${format.sampleMimeType}, which it doesn't parse" }
                    return NO_CUES
                }
            })
        }

        @Deprecated("Media3's legacy subtitle path")
        override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
            @Suppress("DEPRECATION")
            play.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        }

        private companion object {
            val NO_CUES = object : SubtitleParser {
                override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) = Unit

                override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
            }
        }
    }

    private fun describe(format: Format): String = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
        val shown = if (format.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE) "merged" else "replaced"
        "parsed ${format.codecs}, $shown"
    } else {
        "raw ${format.sampleMimeType}"
    }

    companion object {
        /** Media3 checks its reads strictly in tests (see MatroskaTrackFlagsTest). */
        @BeforeClass
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}

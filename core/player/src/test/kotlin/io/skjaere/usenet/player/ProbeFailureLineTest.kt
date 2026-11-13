package io.skjaere.usenet.player

import androidx.media3.common.ParserException
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.SniffFailure
import io.skjaere.usenet.core.UsenetError
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The probe's log line for a file it couldn't read (L320, L331), where Media3's words go now that the screen shows plain
 * ones: an extractor's words in full, on one line; of a reader that crashed, only the classes down its chain and where the
 * crash was, as Media3 wraps any crash and a debrid read's words may name the CDN's host; of anything else, its class.
 */
class ProbeFailureLineTest {
    @Test
    fun `a file no extractor took is logged with Media3's words, on one line`() {
        val sniffed = object : SniffFailure {
            override fun toString() = "TsExtractor: no sync byte in the first 940 bytes"
        }
        val unknown = unrecognizedInput("None of the available extractors (TsExtractor, MatroskaExtractor) could read the stream.", listOf(sniffed))
        assertEquals(
            "can't read the file (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED): UnrecognizedInputFormatException: None of the available extractors " +
                "(TsExtractor, MatroskaExtractor) could read the stream. {contentIsMalformed=false, dataType=1} " +
                "sniff failures: [TsExtractor: no sync byte in the first 940 bytes]",
            ExoTrackProbe.probeFailureLine("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", unknown),
        )
    }

    @Test
    fun `a file an extractor failed on is logged with its words, on one line`() {
        val malformed = ParserException.createForMalformedContainer("Multiple Segment elements not supported\r\nat 1046", null)
        assertEquals(
            "can't read the file (ERROR_CODE_PARSING_CONTAINER_MALFORMED): ParserException: Multiple Segment elements not supported at 1046 " +
                "{contentIsMalformed=true, dataType=1}",
            ExoTrackProbe.probeFailureLine("ERROR_CODE_PARSING_CONTAINER_MALFORMED", malformed),
        )
    }

    @Test
    fun `a reader that crashed is logged by its classes and where it crashed, never by its words`() {
        val crash = Loader.UnexpectedLoaderException(IllegalStateException("from https://cdn.example.invalid/f"))
        val line = ExoTrackProbe.probeFailureLine("ERROR_CODE_IO_UNSPECIFIED", crash)
        val frame = crash.cause!!.stackTrace.first()
        assertEquals(
            "can't read the file (ERROR_CODE_IO_UNSPECIFIED): UnexpectedLoaderException < IllegalStateException " +
                "at io.skjaere.usenet.player.ProbeFailureLineTest.${frame.methodName}(ProbeFailureLineTest.kt:${frame.lineNumber})",
            line,
        )
        assertFalse("example.invalid" in line, line)

        // Down a longer chain, the frame is the innermost cause's: where the crash was, not where it was wrapped.
        val inner = IllegalArgumentException("bad offset in https://cdn.example.invalid/g")
        val nested = Loader.UnexpectedLoaderException(RuntimeException("while reading https://cdn.example.invalid/g", inner))
        val nestedLine = ExoTrackProbe.probeFailureLine("ERROR_CODE_IO_UNSPECIFIED", nested)
        assertEquals(
            "can't read the file (ERROR_CODE_IO_UNSPECIFIED): UnexpectedLoaderException < RuntimeException < IllegalArgumentException " +
                "at io.skjaere.usenet.player.ProbeFailureLineTest.${frame.methodName}(ProbeFailureLineTest.kt:${inner.stackTrace.first().lineNumber})",
            nestedLine,
        )
        assertFalse("example.invalid" in nestedLine, nestedLine)
    }

    @Test
    fun `any other failure is logged by its class alone`() {
        assertEquals(
            "can't read the file (ERROR_CODE_IO_UNSPECIFIED): IOException",
            ExoTrackProbe.probeFailureLine("ERROR_CODE_IO_UNSPECIFIED", IOException("host.example.invalid")),
        )
        val lost = UsenetDataSourceException(UsenetError.Network("news.example.invalid:563 reset"), IOException("news.example.invalid:563 reset"), 2000)
        assertEquals("can't read the file (ERROR_CODE_IO_UNSPECIFIED): UsenetDataSourceException", ExoTrackProbe.probeFailureLine("ERROR_CODE_IO_UNSPECIFIED", lost))
        assertEquals(
            "can't read the file (ERROR_CODE_IO_UNSPECIFIED): LinkUnavailable",
            ExoTrackProbe.probeFailureLine("ERROR_CODE_IO_UNSPECIFIED", LinkUnavailable(LinkFailure.NoWorkingLink)),
        )
    }
}

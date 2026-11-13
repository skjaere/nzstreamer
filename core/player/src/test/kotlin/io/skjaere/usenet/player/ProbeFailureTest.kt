package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.SniffFailure
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * How the track probe reports a file the player can't read (L317), told by what its read failed with: bytes no extractor
 * took, bytes one took and then failed on, or a reader that crashed. Each fails as no error a reader could show Media3's
 * words from or take for a lost connection; any other failure as what its cause says.
 */
class ProbeFailureTest {
    @Test
    fun aFileNoExtractorTookIsOfAnUnknownFormat() {
        val unknown = unrecognizedInput("None of the available extractors (TsExtractor, MatroskaExtractor) could read the stream.")
        val failed = ExoTrackProbe.failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, unknown)
        assertEquals(ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat), failed)
        // Kept for a source to read its own failures from.
        assertSame(unknown, failed.cause)
    }

    @Test
    fun aFileAnExtractorTookAndFailedOnIsBroken() {
        val malformed = ParserException.createForMalformedContainer("Multiple Segment elements not supported", null)
        assertEquals(
            ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.Broken),
            ExoTrackProbe.failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, malformed),
        )
        // Media3's code for a feature it lacks says no more than that an extractor took the file.
        val unsupported = ParserException.createForUnsupportedContainerFeature("Atom size less than header length (unsupported).")
        assertEquals(
            ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.Broken),
            ExoTrackProbe.failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, unsupported),
        )
    }

    /** Media3 wraps what crashed under its loader; a read that failed under it stays that read's failure, as before. */
    @Test
    fun aReaderThatCrashedIsToldFromAReadThatFailed() {
        val crash = Loader.UnexpectedLoaderException(IllegalStateException("Top bit not zero: -2147483648"))
        assertEquals(
            ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.ReaderCrashed),
            ExoTrackProbe.failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, crash),
        )

        val lost = UsenetDataSourceException(UsenetError.MissingArticle("<a@b> not found"), IOException("<a@b> not found"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(
            ProbeResult.Failed(UsenetError.MissingArticle("<a@b> not found")),
            ExoTrackProbe.failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, Loader.UnexpectedLoaderException(lost)),
        )

        val link = LinkUnavailable(LinkFailure.NoWorkingLink)
        assertEquals(ProbeResult.Failed(UsenetError.Network(link.message)), ExoTrackProbe.failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, link))
        val wrapped = Loader.UnexpectedLoaderException(link)
        assertEquals(ProbeResult.Failed(UsenetError.Network(wrapped.message)), ExoTrackProbe.failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, wrapped))
    }

    @Test
    fun anyOtherFailureIsWhatItsCauseSays() {
        val lost = UsenetDataSourceException(UsenetError.MissingArticle("<a@b> not found"), IOException("<a@b> not found"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(ProbeResult.Failed(UsenetError.MissingArticle("<a@b> not found")), ExoTrackProbe.failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, lost))
        assertEquals(null, ExoTrackProbe.failure(PlaybackException.ERROR_CODE_DECODING_FAILED, IOException("decoder")).unreadable)
    }
}

/**
 * Media3's exception for a file no extractor took, with its [sniffFailures]: made by reflection, as Kotlin won't pass its
 * Uri null and android.jar's stubs can't make one; the message names none anyway.
 */
internal fun unrecognizedInput(message: String, sniffFailures: List<SniffFailure> = emptyList()): UnrecognizedInputFormatException =
    UnrecognizedInputFormatException::class.java.getConstructor(String::class.java, Uri::class.java, List::class.java).newInstance(message, null, sniffFailures)

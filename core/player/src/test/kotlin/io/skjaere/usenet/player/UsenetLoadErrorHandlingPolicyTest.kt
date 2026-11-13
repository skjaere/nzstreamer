package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.usenet.core.UsenetError
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ExoPlayer retries a failed load three times, after 0, 1 and 2 s, before the player fails. A
 * Usenet failure that can't go away by itself fails at once instead (#62); one that can (a
 * timeout, a dropped connection) keeps those retries.
 */
class UsenetLoadErrorHandlingPolicyTest {
    private val policy = UsenetLoadErrorHandlingPolicy()

    private fun usenet(error: UsenetError) = UsenetDataSourceException(error, IOException(error.detail), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    /**
     * The load's details are left out (made by reflection, as Kotlin won't pass null): they need a
     * Uri, which android.jar's stubs can't make, and the policy reads only the error and its count.
     */
    private fun delayFor(e: IOException, errorCount: Int = 1): Long = policy.getRetryDelayMsFor(
        LoadErrorInfo::class.java.getConstructor(LoadEventInfo::class.java, MediaLoadData::class.java, IOException::class.java, Int::class.javaPrimitiveType)
            .newInstance(null, MediaLoadData(C.DATA_TYPE_MEDIA), e, errorCount),
    )

    @Test
    fun aMissingArticleIsNotRetried() {
        assertEquals(C.TIME_UNSET, delayFor(usenet(UsenetError.MissingArticle("<a@b> not found on any pool"))))
    }

    @Test
    fun aCorruptArticleIsNotRetried() {
        assertEquals(C.TIME_UNSET, delayFor(usenet(UsenetError.Corrupt("CRC mismatch"))))
    }

    @Test
    fun aRefusedLoginIsNotRetried() {
        assertEquals(C.TIME_UNSET, delayFor(usenet(UsenetError.AuthFailed("481 Authentication failed"))))
    }

    @Test
    fun aMissingArticleAsTheReaderThrowsItIsNotRetried() {
        // What a read hits mid-stream: the writer's cause, classified by the reader.
        val thrown = UsenetStreamReader.dataSourceException(IOException("stream closed", ArticleNotFoundException("<a@b> not found on any pool")))
        assertEquals(C.TIME_UNSET, delayFor(thrown))
    }

    /**
     * A gap the reader stops at (the stream fills an article no provider has with zeros): loaded again, the extractor
     * would resume at the same byte and reach it again, so what can't skip it fails at once, as a missing article did.
     */
    @Test
    fun aGapTheReaderStopsAtIsNotRetried() {
        val reached = GapReached(300_000L..399_999L)
        assertEquals(C.TIME_UNSET, delayFor(reached))
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, reached.reason)
        assertTrue(reached.usenetError() is UsenetError.MissingArticle, "${reached.usenetError()}")
    }

    @Test
    fun aFileTheNzbDoesNotHaveIsNotRetried() {
        // The reader's "No file 3 in …", and an NZB that can't be prepared any more (#62).
        assertEquals(C.TIME_UNSET, delayFor(DataSourceException("No file 3 in test", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
    }

    @Test
    fun transientFailuresKeepExoPlayersRetries() {
        val transient = listOf(
            UsenetError.Timeout("segment fetch timed out"),
            UsenetError.Network("Connection reset"),
            UsenetError.ConnectionFailed("Connection refused"),
            UsenetError.Protocol("unexpected 500"),
            // The engine is configured from the saved providers a moment after the app starts.
            UsenetError.NotConfigured,
            UsenetError.Unknown("boom"),
        )
        for (error in transient) {
            assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(usenet(error)), "$error")
            assertEquals(0L, delayFor(usenet(error), errorCount = 1), "$error, first retry")
            assertEquals(2_000L, delayFor(usenet(error), errorCount = 3), "$error, third retry")
        }
        assertEquals(1_000L, delayFor(IOException("plain"), errorCount = 2))
    }

    /**
     * A debrid play's file no longer to be had (L173): its source already asked the service for a fresh link once, so
     * loading again would only ask again. A connection the CDN dropped keeps Media3's retries (its 503 and 429 too: the
     * source's device test, as the JVM's android.jar can't make the Uri their exception holds).
     */
    @Test
    fun aDebridLinkNoLongerToBeHadIsNotRetried() {
        val final = listOf(
            LinkFailure.NoWorkingLink, LinkFailure.NotCached, LinkFailure.Removed, LinkFailure.Limit(1_200_000), LinkFailure.KeyRefused("TorBox"),
            LinkFailure.Down, LinkFailure.Refused(tooLarge = true),
        )
        for (failure in final) {
            assertEquals(C.TIME_UNSET, delayFor(LinkUnavailable(failure)), "$failure")
            assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(IOException("load failed", LinkUnavailable(failure))), "$failure, as a cause")
        }
        val dropped = java.net.SocketException("Connection reset")
        assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(dropped))
        assertEquals(1_000L, delayFor(dropped, errorCount = 2))
    }

    @Test
    fun theClassificationIsFoundAnywhereInTheCauseChain() {
        val wrapped = IOException("load failed", usenet(UsenetError.MissingArticle("gone")))
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(wrapped))
    }
}

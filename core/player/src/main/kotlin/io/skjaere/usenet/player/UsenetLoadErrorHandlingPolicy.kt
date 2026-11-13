package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.skjaere.usenet.core.UsenetError

/**
 * Media3's load retries (the first at once, each next one a second later than the last, 5 s apart
 * at most; a load that reads something starts the count again, and the play fails only once more
 * than three in a row have read nothing and the player has run dry), except for failures retrying
 * can't fix: an article missing from every provider, a corrupt one, a provider refusing the login,
 * a file the NZB doesn't have; and a debrid play's file no longer to be had ([LinkUnavailable], L173: its source already
 * asked the service for a fresh link once). Those fail the load at once, so the player says so within seconds
 * instead of fetching the same article four times (#62). Timeouts and network errors are retried
 * as usual, a CDN's 503 and 429 too; so is a missing provider, which the app configures a moment after it starts.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsenetLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isPermanent(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    companion object {
        /** Whether [e]'s cause chain holds a failure that loading again would only repeat. */
        fun isPermanent(e: Throwable): Boolean {
            val chain = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(16).toList()
            if (chain.any { it is LinkUnavailable }) return true
            val usenet = chain.filterIsInstance<UsenetDataSourceException>().firstOrNull()?.error
            return when (usenet) {
                is UsenetError.MissingArticle, is UsenetError.Corrupt, is UsenetError.AuthFailed -> true
                null -> chain.filterIsInstance<DataSourceException>().any { it.reason == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND }
                else -> false
            }
        }
    }
}

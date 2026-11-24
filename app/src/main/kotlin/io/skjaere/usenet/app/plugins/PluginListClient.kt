package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One read of the plugin list: [etag] the copy's, which a list not changed since answers with no body. */
fun interface ListReader {
    suspend fun read(etag: String?): ListAnswer
}

/**
 * Reads the plugin list at [source]'s address (L620): one GET, with the copy's ETag as If-None-Match, through [http], the
 * app's service client (ServiceHttp.defaultHttpClient(): its timeouts, and no redirect followed). https alone, or http to
 * the loopback address where [source] allows it; a redirect is a failed read, never followed; at most
 * [PluginIndex.MAX_BYTES] of body, read no further; as [PluginSource.USER_AGENT], naming no version or device.
 */
class PluginListClient(
    private val source: PluginSource,
    private val http: HttpClient,
    private val now: () -> Instant = Instant::now,
) : ListReader {
    override suspend fun read(etag: String?): ListAnswer {
        if (!source.allows(source.list)) return ListAnswer.Failed(ReadFailure.Insecure)
        return try {
            http.prepareGet(source.list) {
                header(HttpHeaders.UserAgent, PluginSource.USER_AGENT)
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }.execute(::answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A timeout, a refused connection, a broken TLS: only its kind matters, and the caller logs no more.
            ListAnswer.Failed(ReadFailure.Network)
        }
    }

    private suspend fun answer(response: HttpResponse): ListAnswer = when (val status = response.status.value) {
        OK -> response.bodyAtMost(PluginIndex.MAX_BYTES.toLong())
            ?.let { ListAnswer.Fresh(it, response.headers[HttpHeaders.ETag]?.takeIf { tag -> ETAG.matches(tag) }) }
            ?: ListAnswer.Failed(ReadFailure.TooLarge)
        NOT_MODIFIED -> ListAnswer.NotChanged
        NOT_FOUND -> ListAnswer.NoList
        TOO_MANY_REQUESTS, in 500..599 -> ListAnswer.Busy(status, retryAfter(response.headers[HttpHeaders.RetryAfter], now()))
        in 300..399 -> ListAnswer.Failed(ReadFailure.Redirect, status)
        else -> ListAnswer.Failed(ReadFailure.Status, status)
    }

    /** The body, read no further than one byte past [limit]; null when it is longer. */
    private suspend fun HttpResponse.bodyAtMost(limit: Long): ByteArray? {
        if ((contentLength() ?: 0) > limit) return null
        val body = bodyAsChannel().readRemaining(limit + 1).readByteArray()
        return body.takeIf { it.size <= limit }
    }

    companion object {
        /** The longest wait a Retry-After is taken for (L620): a day, so a withdrawal still arrives within one. */
        val MAX_RETRY_AFTER: Duration = Duration.ofDays(1)

        /** A Retry-After's wait from [now]: seconds, or an HTTP date, [MAX_RETRY_AFTER] at most. Null for none, or one over. */
        fun retryAfter(header: String?, now: Instant): Duration? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val wait = value.toLongOrNull()?.let { Duration.ofSeconds(it) } ?: try {
                Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
            } catch (e: DateTimeParseException) {
                null
            }
            return wait?.takeIf { !it.isNegative && !it.isZero }?.let { minOf(it, MAX_RETRY_AFTER) }
        }

        private const val OK = 200
        private const val NOT_MODIFIED = 304
        private const val NOT_FOUND = 404
        private const val TOO_MANY_REQUESTS = 429

        // An ETag as it is sent back: printable ASCII, and short; one that isn't is kept as none.
        private val ETAG = Regex("""[\x21-\x7e]{1,200}""")
    }
}

/** What a read of the list came to. */
sealed interface ListAnswer {
    /** 200: the list's [body], at most [PluginIndex.MAX_BYTES], and its [etag]. */
    class Fresh(val body: ByteArray, val etag: String?) : ListAnswer

    /** 304: the copy is the list still. */
    data object NotChanged : ListAnswer

    /** 404: no list yet, until the first plugin release makes its branch (L620); a list with nothing in it. */
    data object NoList : ListAnswer

    /** 429 or 5xx ([status]): the copy is kept, and no read is made for [retryAfter] where GitHub asked so, a day at most. */
    class Busy(val status: Int, val retryAfter: Duration?) : ListAnswer

    /** Not read, for [kind] ([status] where an answer came). */
    class Failed(val kind: ReadFailure, val status: Int? = null) : ListAnswer
}

/** Why a read failed: http where only https goes, a redirect, a body over the cap, another answer, or no answer. */
enum class ReadFailure { Insecure, Redirect, TooLarge, Status, Network }

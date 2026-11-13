package io.skjaere.usenet.debrid

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The services' HTTP, shared by every client (L156): one client, the same timeouts, user agent, body cap and JSON reading. */
object ServiceHttp {
    /** The app's user agent, as its indexers see it. */
    const val USER_AGENT = "NZStreamer/0.1"
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val REQUEST_TIMEOUT_MS = 15_000L

    /** No answer of an API is near this; a bigger one is a fault, read no further. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    /**
     * One OkHttp-backed client for every account. It follows no redirect: an API that answers with one is at fault, and
     * the key in its Authorization header (or a link request's query) must never go on to another host.
     */
    fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        followRedirects = false
        expectSuccess = false
    }

    /** Lenient models (L185): unknown fields ignored, missing ones defaulted, a null where a default is wanted taken as it. */
    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }
}

/** One answer as a client reads it: its [status], [headers] and [body], which is never over [ServiceHttp.MAX_BODY_BYTES]. */
internal class Answer(val status: Int, val headers: Headers, val body: ByteArray) {
    fun text(): String = body.decodeToString()
}

/**
 * A client's reading of an answer that failed: [error]'s bucket, [reason] what the log line says it was (a service's code,
 * never its message), and a wait the service asked for that holds the account's calls ([holdFor]).
 */
internal class Failed(val error: ServiceError, val reason: String? = null, val holdFor: Duration? = null) :
    Exception(null, null, false, false)

/**
 * How each call of one account's client is made (L175, L197, L208): through its [gate], which answers a closed gate's
 * error without a request; at its [limit]'s pace; with the app's user agent and the timeouts. A 429 waits out its
 * Retry-After in the limiter and is asked again, and only once [MAX_429_WAIT] of them have passed is it a
 * [ServiceError.Limit]. Timeouts, network errors and answers over the cap are [ServiceError.Down]. Each call is one log
 * line: the service, the call, what it answered and how long it took; never a key, a link, a hash or a name.
 */
internal class ServiceCalls(
    val kind: ServiceKind,
    private val http: HttpClient,
    private val limit: ServiceRateLimit,
    val gate: ServiceGate,
    private val log: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /**
     * Makes the request [request] builds and gives its answer to [read], which returns the call's value or throws
     * [Failed]. [what] names the call in the log line ("checkcached 34 hashes"), [said] its value ("12 cached"). A call
     * that isn't [gated] (a key's Test) is made while the gate is closed too; its outcome still opens or closes it. A
     * [quiet] call has no line when it succeeds: its caller says in one line what a run of them came to ([note]), as a
     * whole list's pages are; a failure has its own line as any call's.
     */
    suspend fun <T> call(
        what: String,
        gated: Boolean = true,
        quiet: Boolean = false,
        said: (T) -> String = { "answered" },
        request: HttpRequestBuilder.() -> Unit,
        read: (Answer) -> T,
    ): T {
        val started = elapsedMs()
        if (gated) {
            gate.closed()?.let { closed ->
                log(line(what, "not asked, the account's gate is closed ($closed)", started))
                throw ServiceException(closed)
            }
        }
        val value = try {
            read(answer(what, request, started))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Failed) {
            e.holdFor?.let(limit::holdFor)
            throw failed(what, e.error, e.reason, started)
        } catch (e: Exception) {
            // Timeouts, network errors and answers no model reads. Only the exception's kind is said: a client's own
            // words can hold a request's address, and a link request's holds the key (L186).
            throw failed(what, ServiceError.Down, e::class.simpleName, started)
        }
        gate.opened()
        if (!quiet) log(line(what, said(value), started))
        return value
    }

    /** When a run of calls starts, for its [note]. */
    fun now(): Long = elapsedMs()

    /** The one line of a run of [quiet] calls begun at [started], in a call's own form: what it came to, how long it took. */
    fun note(what: String, said: String, started: Long) = log(line(what, said, started))

    /** The answer to [request], once its 429s are waited out (or have held it too long, a [Failed] limit). */
    private suspend fun answer(what: String, request: HttpRequestBuilder.() -> Unit, started: Long): Answer {
        var waited = Duration.ZERO
        while (true) {
            limit.acquire()
            val answer = http.prepareRequest {
                header(HttpHeaders.UserAgent, ServiceHttp.USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                timeout {
                    connectTimeoutMillis = ServiceHttp.CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = ServiceHttp.REQUEST_TIMEOUT_MS
                }
                request()
            }.execute { response ->
                val body = response.bodyAtMost(ServiceHttp.MAX_BODY_BYTES) ?: throw Failed(ServiceError.Down, "an answer over 2 MB")
                Answer(response.status.value, response.headers, body)
            }
            if (answer.status != TOO_MANY_REQUESTS) return answer
            val wait = retryAfter(answer.headers[HttpHeaders.RetryAfter], clock.instant()) ?: DEFAULT_429_WAIT
            if (waited.plus(wait) > MAX_429_WAIT) throw Failed(ServiceError.Limit(wait), "HTTP 429")
            log(line(what, "HTTP 429, waiting ${wait.seconds} s", started))
            limit.holdFor(wait)
            waited = waited.plus(wait)
        }
    }

    /** [error] as the call's exception, logged with [reason], after it has opened or closed the gate. */
    private fun failed(what: String, error: ServiceError, reason: String?, started: Long): ServiceException {
        gate.record(error)
        log(line(what, listOfNotNull(reason, error.toString()).joinToString(", "), started))
        return ServiceException(error)
    }

    private fun line(what: String, said: String, started: Long) =
        "debrid: ${kind.displayName} $what: $said, ${elapsedMs() - started} ms"

    /** The body, read no further than one byte past [limit]; null when it is longer. */
    private suspend fun HttpResponse.bodyAtMost(limit: Long): ByteArray? {
        if ((contentLength() ?: 0) > limit) return null
        val body = bodyAsChannel().readRemaining(limit + 1).readByteArray()
        return body.takeIf { it.size <= limit }
    }

    companion object {
        /** How long the 429s of one call may hold it before it is the account's limit. */
        val MAX_429_WAIT: Duration = Duration.ofSeconds(30)

        /** A 429's wait when it gives none. */
        val DEFAULT_429_WAIT: Duration = Duration.ofSeconds(5)

        /**
         * A Retry-After's wait from [now]: seconds, or an HTTP date (RFC 9110), a day at most ([MAX_RETRY_AFTER]). Null for
         * none, one that isn't either, or one already over.
         */
        fun retryAfter(header: String?, now: Instant): Duration? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val wait = value.toLongOrNull()?.let { runCatching { Duration.ofSeconds(it) }.getOrNull() }
                ?: runCatching { Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()) }.getOrNull()
            return wait?.takeIf { !it.isNegative && !it.isZero }?.coerceAtMost(MAX_RETRY_AFTER)
        }

        /**
         * The longest wait a Retry-After is taken for. The gate stays closed until the wait's end and the limiter holds the
         * calls as long, both kept as times that must exist: a wait past any would throw where only a [ServiceException]
         * may leave a call.
         */
        val MAX_RETRY_AFTER: Duration = Duration.ofDays(1)

        /** A service's error code as a log line may show it: letters, digits and underscores, at most 40 of them. */
        fun codeOf(code: String?): String? =
            code?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }?.take(40)?.takeIf { it.isNotEmpty() }

        private const val TOO_MANY_REQUESTS = 429
    }
}

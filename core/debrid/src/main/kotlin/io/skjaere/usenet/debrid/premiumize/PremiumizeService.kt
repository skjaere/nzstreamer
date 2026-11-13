package io.skjaere.usenet.debrid.premiumize

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.Answer
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.Failed
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceCalls
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.ServiceRateLimit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Premiumize (L198; its API page read again 2026-11-04): `Authorization: Bearer <key>` on every call, never `apikey=`.
 *
 * - [test]: `GET /account/info`, the customer number and when the plan ends.
 * - [check]: `POST /cache/check` with the xt-only magnets as `items[]`, at most [BATCH] a call; `response[]` is parallel
 *   to them.
 * - [prepare]: `POST /transfer/directdl` with the xt-only magnet as `src`: its `content[]` (path, size, link) is the
 *   listing the pick chooses from. Nothing is added to the account. Each file's link stays in memory in the
 *   [Prepared], never in its stored form.
 * - [link]: the link `directdl` gave with the listing, once; after that, `directdl` again, the file found by path and
 *   size.
 *
 * Errors: `{status: "error", message, code}` arrives with HTTP 200 and is read on every answer, its `code` mapped to a
 * bucket (every code the API page lists); the message is never shown or logged (W80). `rate_limit_reached` holds the
 * account's calls 60 s and is its limit; `permission_denied` (restricted, unconfirmed, someone else's resource) is not
 * the key's fault, so it refuses rather than refusing the key. An unknown code is down on [test] and [check] and
 * refuses the release on [prepare] and [link]. Deprecated fields (`transcoded`, `stream_link`, `location`, `filename`,
 * `filesize`) are never read.
 */
class PremiumizeService(
    private val key: String,
    http: HttpClient,
    private val baseUrl: String = BASE_URL,
    limit: ServiceRateLimit = ServiceRateLimit(CALL_INTERVAL),
    gate: ServiceGate = ServiceGate(),
    private val clock: Clock = Clock.systemUTC(),
    log: (String) -> Unit = { DEFAULT_LOG.info(it) },
) : DebridService {
    override val kind = ServiceKind.Premiumize

    private val calls = ServiceCalls(kind, http, limit, gate, log, clock)

    /** Which call an answer is to: an unknown code is down on a test or a check, and refuses the release otherwise. */
    private enum class Call(val unknown: ServiceError) {
        Test(ServiceError.Down),
        Check(ServiceError.Down),
        Play(ServiceError.Refused()),
    }

    override suspend fun test(): Account =
        calls.call("account/info", gated = false, said = { "signed in" }, request = { api("account/info") }) { answer ->
            val info = ok(answer, Call.Test)
            val customer = (info["customer_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw Failed(ServiceError.Down, "no account in the answer")
            // A number of seconds; a free account's is false or absent.
            val until = (info["premium_until"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            Account("customer $customer", planUntil = until)
        }

    override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> {
        val cached = HashSet<InfoHash>()
        hashes.distinct().chunked(BATCH).forEach { batch ->
            val what = "cache/check ${batch.size} ${if (batch.size == 1) "hash" else "hashes"}"
            cached += calls.call(what, said = { "${it.size} cached" }, request = {
                method = HttpMethod.Post
                api("cache/check")
                setBody(FormDataContent(Parameters.build { appendAll("items[]", batch.map { it.xtMagnet() }) }))
            }) { answer ->
                val response = decode(CacheWire.serializer(), ok(answer, Call.Check)).response
                if (response.size != batch.size) throw Failed(ServiceError.Down, "an answer not parallel to the items")
                batch.filterIndexed { i, _ -> response[i] }.toSet()
            }
        }
        return hashes.associateWith { it in cached }
    }

    override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
        val content = directdl(hash)
        val listing = ServiceListing(content.mapIndexed { index, file -> ServiceFile(index, file.path, file.size) })
        val picked = pick(listing) ?: return Prepared(hash, listing, null)
        require(listing.files.getOrNull(picked.index) == picked) { "The pick must be a file of the listing" }
        val madeAt = clock.instant()
        return Prepared(hash, listing, picked).apply {
            holdLinks(content.filter { it.link.isNotBlank() }.associate { it.path to ServiceLink(it.link, madeAt) })
        }
    }

    override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
        prepared.takeLink(file.path)?.let { return it }
        val link = directdl(prepared.hash).firstOrNull { it.path == file.path && it.size == file.size }?.link?.takeIf { it.isNotBlank() }
            ?: throw ServiceException(ServiceError.NotCached)
        return ServiceLink(link, clock.instant())
    }

    /** The cached release's files with their links; [ServiceError.NotCached] when it has none. */
    private suspend fun directdl(hash: InfoHash): List<FileWire> =
        calls.call<List<FileWire>>("directdl", said = { "${it.size} files" }, request = {
            method = HttpMethod.Post
            api("transfer/directdl")
            // The exact topic alone (L159): no tracker, no name.
            setBody(FormDataContent(Parameters.build { append("src", hash.xtMagnet()) }))
        }) { answer ->
            decode(DirectWire.serializer(), ok(answer, Call.Play)).content.takeIf { it.isNotEmpty() }
                ?: throw Failed(ServiceError.NotCached, "no content")
        }

    /** A call to [path] under the API, with the key as a bearer token and nowhere else. */
    private fun HttpRequestBuilder.api(path: String) {
        url.takeFrom(URLBuilder(baseUrl).apply { appendPathSegments(path.split('/')) })
        header(HttpHeaders.Authorization, "Bearer $key")
    }

    /** The answer when it succeeded; else the [Failed] its code (HTTP 200) or status means for [call]. */
    private fun ok(answer: Answer, call: Call): JsonObject {
        val body = runCatching { ServiceHttp.json.parseToJsonElement(answer.text()) as? JsonObject }.getOrNull()
        val status = (body?.get("status") as? JsonPrimitive)?.contentOrNull
        if (answer.status in 200..299 && body != null && status != "error") return body
        if (answer.status in 200..299 && body == null) throw Failed(ServiceError.Down, "an answer no model reads")
        val code = (body?.get("code") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val reason = ServiceCalls.codeOf(code) ?: "HTTP ${answer.status}"
        if (code == RATE_LIMIT) throw Failed(ServiceError.Limit(RATE_LIMIT_WAIT), reason, holdFor = RATE_LIMIT_WAIT)
        val error = code?.let { bucketOf(it, answer, call) } ?: when (answer.status) {
            401, 403 -> ServiceError.KeyRefused
            in 500..599 -> ServiceError.Down
            else -> call.unknown
        }
        throw Failed(error, reason)
    }

    /** What Premiumize's [code] means; null for one its API page doesn't list. */
    private fun bucketOf(code: String, answer: Answer, call: Call): ServiceError? = when (code) {
        "account_limit_reached", "service_limit_reached" ->
            ServiceError.Limit(ServiceCalls.retryAfter(answer.headers[HttpHeaders.RetryAfter], clock.instant()))
        "authentication_failed" -> ServiceError.KeyRefused
        // "Authenticated but not allowed": the account's state, not the key; a key's Test says so in its own words.
        "permission_denied" -> ServiceError.Refused()
        "not_found" -> if (call == Call.Play) ServiceError.NotCached else null
        "service_unsupported", "invalid_request", "permanent_error" -> ServiceError.Refused()
        "service_down", "transient_error", "semi_permanent_error", "link_generation_failed", "unknown_error" -> ServiceError.Down
        else -> null
    }

    private fun <T> decode(serializer: KSerializer<T>, body: JsonObject): T = ServiceHttp.json.decodeFromJsonElement(serializer, body)

    companion object {
        const val BASE_URL = "https://www.premiumize.me/api"

        /** Premiumize publishes no rate (L175): one call every 500 ms. */
        val CALL_INTERVAL: Duration = Duration.ofMillis(500)

        /** Magnets a cache check asks at once: TorBox's documented limit, as Premiumize's API page names none. */
        const val BATCH = 100

        private const val RATE_LIMIT = "rate_limit_reached"
        private val RATE_LIMIT_WAIT: Duration = Duration.ofSeconds(60)
        private val DEFAULT_LOG = LoggerFactory.getLogger(DebridService::class.java)
    }
}

@Serializable
private class CacheWire(val response: List<Boolean> = emptyList())

@Serializable
private class DirectWire(val content: List<FileWire> = emptyList())

@Serializable
private class FileWire(val path: String = "", val size: Long = 0, val link: String = "")

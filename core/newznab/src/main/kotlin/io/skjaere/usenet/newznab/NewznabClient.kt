package io.skjaere.usenet.newznab

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.charset
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Talks to one Newznab-compatible API (Newznab, NNTmux, nZEDb, and the Prowlarr/NZBHydra
 * proxies). All failures surface as [NewznabException]; the API key never appears in
 * logs or exception messages.
 *
 * Safe to call from the main thread: answers (an extended search page can be hundreds of KB)
 * are decoded and parsed on [defaultDispatcher]. Bodies are read only up to [maxNzbBytes] or
 * [maxAnswerBytes], so a link that answers with a video or an archive can't exhaust memory.
 */
class NewznabClient(
    private val http: HttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
    /** A Usenet indexer's caps and searches; a torrent indexer's take [torznabTimeoutMs] (L170). */
    private val requestTimeoutMs: Long = IndexerKind.Newznab.timeoutMs,
    private val downloadTimeoutMs: Long = 30_000,
    private val maxNzbBytes: Long = 64L * 1024 * 1024,
    private val maxAnswerBytes: Long = 32L * 1024 * 1024,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** A torrent indexer's caps and searches: as long as the search gives it, else they would cut it short. */
    private val torznabTimeoutMs: Long = IndexerKind.Torznab.timeoutMs,
) {
    suspend fun caps(indexer: IndexerConfig): IndexerCaps =
        getParsed(indexer, mapOf("t" to "caps"), NewznabXml::parseCaps)

    suspend fun search(indexer: IndexerConfig, query: SearchQuery, offset: Int = 0, limit: Int? = null): SearchPage {
        val params = searchParams(query) + listOfNotNull(
            "extended" to "1",
            "offset" to offset.toString(),
            limit?.let { "limit" to it.toString() },
        )
        val page = getParsed(indexer, params.toMap()) { NewznabXml.parseSearch(it, indexer) }
        leftOutLine(indexer, page)?.let { log.info(it) }
        return page
    }

    /** Downloads the NZB for [item]: its stored link when there is one, else `t=get`. Counts as a grab. */
    suspend fun nzb(indexer: IndexerConfig, item: NewznabItem): ByteArray =
        nzb(indexer, item.guid, item.downloadTemplate)

    suspend fun nzb(indexer: IndexerConfig, guid: String, downloadTemplate: String?): ByteArray =
        download(indexer, guid, downloadTemplate).bytes

    /** Downloads the NZB for [item] as [nzb] does, with the archive password its indexer sent beside it ([NzbDownload]). */
    suspend fun download(indexer: IndexerConfig, item: NewznabItem): NzbDownload =
        download(indexer, item.guid, item.downloadTemplate)

    suspend fun download(indexer: IndexerConfig, guid: String, downloadTemplate: String?): NzbDownload {
        val url = downloadTemplate?.let { keyedLink(indexer, it) } ?: apiUrl(indexer, mapOf("t" to "get", "id" to guid))
        val download = request(indexer, url, downloadTimeoutMs) { response ->
            NzbDownload(response.bodyAtMost(maxNzbBytes, "The download is too large to be an NZB"), NzbDownload.passwordOf(response.headers))
        }
        val bytes = download.bytes
        val head = bytes.copyOf(minOf(bytes.size, 512)).decodeToString()
        if ("<nzb" !in head) {
            withContext(defaultDispatcher) { NewznabXml.checkError(bytes.decodeToString()) }
            throw NewznabException(NewznabError.BadResponse("Download is not an NZB"))
        }
        return download
    }

    internal fun searchParams(query: SearchQuery): List<Pair<String, String>> {
        val categories = query.categories.takeIf { it.isNotEmpty() }?.let { "cat" to it.joinToString(",") }
        return listOfNotNull(categories) + when (query) {
            is SearchQuery.Text -> listOf("t" to "search", "q" to query.query)
            is SearchQuery.Tv -> listOfNotNull(
                "t" to "tvsearch",
                query.query?.let { "q" to it },
                query.ids.tvdb?.let { "tvdbid" to it.toString() },
                query.ids.tmdb?.let { "tmdbid" to it.toString() },
                query.ids.imdb?.let { "imdbid" to it.removePrefix("tt") },
                query.ids.tvmaze?.let { "tvmazeid" to it.toString() },
                (query.airDate?.year ?: query.season)?.let { "season" to it.toString() },
                (query.airDate?.let { "%02d/%02d".format(Locale.ROOT, it.monthValue, it.dayOfMonth) } ?: query.episode?.toString())?.let { "ep" to it },
            )
            is SearchQuery.Movie -> listOfNotNull(
                "t" to "movie",
                query.query?.let { "q" to it },
                query.imdb?.let { "imdbid" to it.removePrefix("tt") },
                query.tmdb?.let { "tmdbid" to it.toString() },
            )
        }
    }

    private suspend fun <T> getParsed(indexer: IndexerConfig, params: Map<String, String>, parse: (String) -> T): T {
        val timeoutMs = if (indexer.kind == IndexerKind.Torznab) torznabTimeoutMs else requestTimeoutMs
        val text = request(indexer, apiUrl(indexer, params), timeoutMs, silentMs = timeoutMs) { it.text() }
        return withContext(defaultDispatcher) { parse(text) }
    }

    /**
     * GETs [url] and hands a successful answer to [read] on [defaultDispatcher] while its body is still
     * streaming, so [read] decides how much of it is ever held in memory. [silentMs]: how long it may go without a byte
     * (null keeps the engine's own read timeout, OkHttp's 10 s).
     */
    private suspend fun <T> request(indexer: IndexerConfig, url: String, timeoutMs: Long, silentMs: Long? = null, read: suspend (HttpResponse) -> T): T {
        if (log.isDebugEnabled) log.debug(requestLine(indexer, url))
        return try {
            http.prepareGet(url) {
                header(HttpHeaders.UserAgent, userAgent)
                timeout {
                    requestTimeoutMillis = timeoutMs
                    // Jackett and Prowlarr send nothing until their trackers have answered: the engine's own read
                    // timeout would end a caps call or search long before its kind's time (L170).
                    silentMs?.let { socketTimeoutMillis = it }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) throw statusError(response)
                withContext(defaultDispatcher) { read(response) }
            }
        } catch (e: NewznabException) {
            throw e
        } catch (e: CancellationException) {
            // A caller's timeout too (MultiIndexerSearch's): its cancellation, not the indexer's failure, for its
            // withTimeoutOrNull to end. Ktor passes it on as the caller's TimeoutCancellationException since 3.5.
            throw e
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            throw NewznabException(NewznabError.Timeout("No answer within ${timeoutMs / 1000} s"))
        } catch (e: java.net.SocketTimeoutException) {
            // Silent as long as it was given: its time ran out, whichever of the two ended it first. A grab, which keeps the
            // engine's read timeout, fails as before.
            throw NewznabException(if (silentMs != null) NewznabError.Timeout(noAnswerWithin(timeoutMs)) else NewznabError.Network(safeMessage(e, indexer)))
        } catch (e: Exception) {
            // IO errors, and whatever else Ktor or OkHttp throw (a redirect loop, a link they can't parse).
            throw NewznabException(NewznabError.Network(safeMessage(e, indexer)))
        }
    }

    /**
     * The error document an indexer sent with a failure status, else the status itself; but a 429 is the API limit
     * whatever document comes with it (L169): Jackett's is its code 900, which alone reads as a server error. Its
     * Retry-After goes with it, for the searches to keep the indexer out until then. A used-up grab allowance stays one,
     * as NNTmux answers its code 501 with a 429 too: a play then skips that indexer's other releases.
     */
    private suspend fun statusError(response: HttpResponse): Throwable {
        val document = withContext(defaultDispatcher) {
            val body = runCatching { response.text() }.getOrDefault("")
            runCatching { NewznabXml.checkError(body) }.exceptionOrNull()
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            if ((document as? NewznabException)?.error is NewznabError.DownloadLimitReached) return document
            return NewznabException(NewznabError.ApiLimitReached(API_LIMIT_REACHED, retryAfter(response.headers[HttpHeaders.RetryAfter], Instant.now())))
        }
        return document ?: NewznabException(
            when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> NewznabError.Auth("HTTP ${response.status.value}")
                HttpStatusCode.NotFound -> NewznabError.NotFound("HTTP 404")
                else -> NewznabError.Server("HTTP ${response.status.value}")
            },
        )
    }

    private suspend fun HttpResponse.text(): String =
        String(bodyAtMost(maxAnswerBytes, "The answer is too large"), charset() ?: Charsets.UTF_8)

    companion object {
        const val DEFAULT_USER_AGENT = "NZStreamer/0.1"
        private val log = LoggerFactory.getLogger(NewznabClient::class.java)

        /** An OkHttp-backed client; OkHttp verifies TLS hostnames and follows redirects. */
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout)
            expectSuccess = false
        }

        /** The body, read no further than one byte past [limit]; longer is a bad response saying [tooLarge]. */
        private suspend fun HttpResponse.bodyAtMost(limit: Long, tooLarge: String): ByteArray {
            if ((contentLength() ?: 0) > limit) throw NewznabException(NewznabError.BadResponse(tooLarge))
            val body = bodyAsChannel().readRemaining(limit + 1).readByteArray()
            if (body.size > limit) throw NewznabException(NewznabError.BadResponse(tooLarge))
            return body
        }

        private val origin = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*")

        /** How a request is logged: by the indexer's name, its address left out, its key redacted. */
        internal fun requestLine(indexer: IndexerConfig, url: String): String =
            "GET ${NewznabXml.redactApiKey(url, indexer.apiKey).replace(origin, "").ifEmpty { "/" }} from ${indexer.name}"

        /**
         * "Left out 3 of Home Prowlarr's 40 results: 3 without an info hash": what [page] left out, by why, of its results
         * (those kept and those left out, L238); null for nothing.
         */
        internal fun leftOutLine(indexer: IndexerConfig, page: SearchPage): String? {
            val counts = LeftOut.entries.mapNotNull { why -> page.leftOut[why]?.takeIf { it > 0 }?.let { why to it } }
            if (counts.isEmpty()) return null
            val reasons = counts.joinToString { (why, n) ->
                when (why) {
                    LeftOut.NotNzb -> if (n == 1) "1 not an NZB" else "$n not NZBs"
                    LeftOut.Nzb -> if (n == 1) "1 NZB" else "$n NZBs"
                    LeftOut.NoHash -> "$n without an info hash"
                }
            }
            val left = counts.sumOf { it.second }
            val total = page.items.size + left
            return "Left out $left of ${indexer.name}'s $total result${if (total == 1) "" else "s"}: $reasons"
        }

        /**
         * A Retry-After's wait from [now]: seconds, or an HTTP date (RFC 9110), a day at most ([MAX_RETRY_AFTER]). Null for
         * none, one that isn't either, or one already over.
         */
        internal fun retryAfter(header: String?, now: Instant): Duration? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val wait = value.toLongOrNull()?.let { runCatching { Duration.ofSeconds(it) }.getOrNull() }
                ?: runCatching { Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()) }.getOrNull()
            return wait?.takeIf { !it.isNegative && !it.isZero }?.coerceAtMost(MAX_RETRY_AFTER)
        }

        /**
         * The longest wait a Retry-After is taken for: a daily limit's. The indexer is kept out until the wait's end, a time
         * that must exist: a broken or hostile indexer's wait past any (Long.MAX_VALUE seconds) would fail the whole search.
         */
        internal val MAX_RETRY_AFTER: Duration = Duration.ofDays(1)

        internal fun safeMessage(e: Throwable, indexer: IndexerConfig): String =
            NewznabXml.redactApiKey(e.message ?: e::class.simpleName.orEmpty(), indexer.apiKey)

        /** [address] as requests use it, or null when Ktor can't parse it (java.net.URI is laxer, e.g. about the port). */
        internal fun parseBaseUrl(address: String): Url? = runCatching { Url(address.trim().trimEnd('/')) }.getOrNull()

        /**
         * [template] with the API key filled in, never less secure than [indexer]'s address: an http
         * link from an https indexer moves to https on the indexer's host, and one on another host
         * gives null, so the caller asks `t=get` instead (the key would leave in cleartext before any
         * redirect to https could help).
         */
        internal fun keyedLink(indexer: IndexerConfig, template: String): String? {
            val link = template.replace(NewznabItem.API_KEY_PLACEHOLDER, indexer.apiKey)
            val base = parseBaseUrl(indexer.baseUrl) ?: return null
            // A link Ktor can't parse is never sent: the request fails with a redacted error.
            val url = runCatching { Url(link) }.getOrNull()
            return when {
                url == null || base.protocol != URLProtocol.HTTPS || url.protocol == URLProtocol.HTTPS -> link
                url.host.equals(base.host, ignoreCase = true) ->
                    URLBuilder(url).apply { protocol = URLProtocol.HTTPS; port = base.specifiedPort }.buildString()
                else -> null
            }
        }

        internal fun apiUrl(indexer: IndexerConfig, params: Map<String, String>): String {
            val base = parseBaseUrl(indexer.baseUrl) ?: throw NewznabException(NewznabError.BadResponse("Invalid indexer address"))
            return URLBuilder(base).apply {
                if (base.segments.lastOrNull() != "api") appendPathSegments("api")
                params.forEach { (k, v) -> parameters.append(k, v) }
                parameters.append("apikey", indexer.apiKey)
            }.buildString()
        }
    }
}

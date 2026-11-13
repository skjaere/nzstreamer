package io.skjaere.usenet.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

sealed class TmdbError(val message: String) {
    class NoKey : TmdbError("No TMDB key set")
    class InvalidKey(message: String) : TmdbError(message)
    class NotFound(message: String) : TmdbError(message)
    class RateLimited : TmdbError("TMDB rate limit reached")
    /** TMDB refused what was asked (HTTP 400 or 422: a page past its last, a filter it can't read): asking again won't help. */
    class BadRequest(message: String) : TmdbError(message)
    class Network(message: String) : TmdbError(message)
    class Server(message: String) : TmdbError(message)
    class BadResponse(message: String) : TmdbError(message)

    override fun toString() = "${this::class.simpleName}($message)"
}

class TmdbException(val error: TmdbError, cause: Throwable? = null) : Exception(error.toString(), cause)

sealed interface KeyCheck {
    data object Valid : KeyCheck
    data object Invalid : KeyCheck
    data class Failed(val error: TmdbError) : KeyCheck
}

/**
 * TMDB v3 with the user's own key: a v3 API key (sent as `api_key`) or a v4 read access
 * token (sent as a bearer token). Responses are cached by request, without the key; when
 * TMDB can't be reached, an expired cached answer is better than none and is used (an [Aged]
 * answer says so). How long an answer stays good depends on what it says ([Freshness]); days
 * are the device's ([clock]'s zone), as air dates are.
 *
 * Safe to call from the main thread: the cache and the key are read on [ioDispatcher], and
 * answers (a season's can be hundreds of KB) are decoded on [defaultDispatcher].
 *
 * [beforeNetwork] runs before each request sent to TMDB, never for an answer from the cache:
 * the lists' refresher spaces its own requests with it.
 */
class TmdbClient(
    private val http: HttpClient,
    private val credentials: suspend () -> String?,
    private val language: suspend () -> String = { "en-US" },
    private val cache: MetadataCache = InMemoryMetadataCache(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val baseUrl: String = "https://api.themoviedb.org/3",
    private val timeoutMs: Long = 10_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val beforeNetwork: suspend () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(TmdbClient::class.java)

    /** Checks a key before it is saved. Never cached. */
    suspend fun validate(key: String): KeyCheck = try {
        request("authentication", emptyMap(), key.trim())
        KeyCheck.Valid
    } catch (e: TmdbException) {
        if (e.error is TmdbError.InvalidKey) KeyCheck.Invalid else KeyCheck.Failed(e.error)
    }

    /** Movies and TV shows matching [query], in TMDB's order; people are dropped. */
    suspend fun searchTitles(query: String): List<TitleSuggestion> {
        if (query.isBlank()) return emptyList()
        val page = fetch(
            "search/multi",
            mapOf("query" to query.trim(), "include_adult" to "false"),
            PagedWire.serializer(MultiResultWire.serializer()),
            maxAge = Duration.ofHours(1),
        ).value
        return page.results.mapNotNull { it.toSuggestion(it.mediaType) }
    }

    /** A show whose next episode is due (it airs today or should have aired) is refreshed hourly. */
    suspend fun show(tmdbId: Int, refresh: Boolean = false): ShowDetails = showAged(tmdbId, refresh).value

    /** [show], with how old the answer is. */
    suspend fun showAged(tmdbId: Int, refresh: Boolean = false): Aged<ShowDetails> {
        val today = LocalDate.now(clock)
        return fetch(
            "tv/$tmdbId",
            mapOf("append_to_response" to "external_ids"),
            TvWire.serializer(),
            maxAge = if (refresh) Duration.ZERO else Freshness.SHOW_ANSWER,
            freshFor = { Freshness.showAnswer(it.status, date(it.nextEpisodeToAir?.airDate), today) },
        ).map { it.toDetails() }
    }

    /**
     * One season with its episodes. A season that is airing (episodes from the last week, or
     * still to come) is refreshed hourly so new air dates and titles show up.
     */
    suspend fun season(showTmdbId: Int, number: Int, refresh: Boolean = false): Season = seasonAged(showTmdbId, number, refresh).value

    /** [season], with how old the answer is. */
    suspend fun seasonAged(showTmdbId: Int, number: Int, refresh: Boolean = false): Aged<Season> {
        val today = LocalDate.now(clock)
        return fetch(
            "tv/$showTmdbId/season/$number",
            emptyMap(),
            SeasonWire.serializer(),
            maxAge = if (refresh) Duration.ZERO else Freshness.SEASON,
            freshFor = { wire -> Freshness.season(wire.episodes.map { date(it.airDate) }, today) },
        ).map { wire ->
            Season(
                showTmdbId = showTmdbId,
                number = wire.seasonNumber,
                name = wire.name,
                overview = wire.overview?.ifBlank { null },
                episodes = wire.episodes.map {
                    Episode(it.seasonNumber, it.episodeNumber, it.name?.ifBlank { null }, it.overview?.ifBlank { null }, date(it.airDate), it.runtime?.takeIf { runtime -> runtime > 0 }, it.stillPath)
                },
            )
        }
    }

    /** A movie not out yet is refreshed daily, one out for a while weekly. */
    suspend fun movie(tmdbId: Int): MovieDetails = movieAged(tmdbId).value

    /** [movie], with how old the answer is. */
    suspend fun movieAged(tmdbId: Int): Aged<MovieDetails> {
        val today = LocalDate.now(clock)
        return fetch(
            "movie/$tmdbId",
            mapOf("append_to_response" to "external_ids"),
            MovieWire.serializer(),
            Freshness.MOVIE_ANSWER,
            freshFor = { Freshness.movieAnswer(date(it.releaseDate), it.status, today) },
        ).map { wire ->
            MovieDetails(
                tmdbId = wire.id,
                title = wire.title,
                originalTitle = wire.originalTitle,
                year = year(wire.releaseDate),
                overview = wire.overview?.ifBlank { null },
                posterPath = wire.posterPath,
                backdropPath = wire.backdropPath,
                runtimeMinutes = wire.runtime?.takeIf { it > 0 },
                ids = ExternalIds(imdb = (wire.externalIds?.imdbId ?: wire.imdbId)?.ifBlank { null }),
                releaseDate = date(wire.releaseDate),
                status = wire.status?.ifBlank { null },
                originalLanguage = originalLanguage(wire.originalLanguage),
            )
        }
    }

    /** Maps an indexer's IMDb or TVDB id back to a TMDB title. */
    suspend fun findByImdb(imdbId: String): TitleSuggestion? = find(imdbId, "imdb_id")
    suspend fun findByTvdb(tvdbId: Int): TitleSuggestion? = find(tvdbId.toString(), "tvdb_id")

    private suspend fun find(id: String, source: String): TitleSuggestion? {
        val wire = fetch("find/$id", mapOf("external_source" to source), FindWire.serializer(), Duration.ofDays(7)).value
        return wire.tvResults.firstNotNullOfOrNull { it.toSuggestion("tv") }
            ?: wire.movieResults.firstNotNullOfOrNull { it.toSuggestion("movie") }
    }

    // Plumbing

    /**
     * A GET of TMDB's v3 [path] with [params], as every call of this client makes it: the user's key (read only when
     * the answer isn't cached; [TmdbError.NoKey] without one, before anything is sent), the language, [beforeNetwork],
     * the cache and the errors. For calls this client has no method for, from other modules.
     *
     * A cached answer up to [maxAge] old is used while it is fresh for what it says ([freshFor]); [maxAge] zero asks
     * TMDB again whatever is cached. The answer comes with its age: zero from the network, the entry's from the cache.
     * When TMDB can't be reached or fails (a 5xx), the copy it gave last, up to [MetadataCache.MAX_AGE] old, is better
     * than none ([Aged.offline]); with [staleOnRateLimit], also while TMDB says to slow down ([Aged.rateLimited]).
     */
    suspend fun <T : Any> fetch(
        path: String,
        params: Map<String, String>,
        serializer: KSerializer<T>,
        maxAge: Duration,
        freshFor: (T) -> Duration = { maxAge },
        staleOnRateLimit: Boolean = false,
    ): Aged<T> = withContext(ioDispatcher) {
        val allParams = params + ("language" to language())
        val cacheKey = path + "?" + allParams.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        // maxAge zero means "refresh": don't read the cache at all, however fresh the entry.
        val entry = if (maxAge.isZero) null else cache.get(cacheKey, maxAge)
        // An entry that no longer decodes is fetched again.
        val cached = entry?.let { hit -> try { Aged(decode(serializer, hit.body), hit.age) } catch (e: TmdbException) { null } }
        if (cached != null && cached.age <= freshFor(cached.value)) return@withContext cached
        val key = credentials()?.takeIf { it.isNotBlank() } ?: throw TmdbException(TmdbError.NoKey())
        beforeNetwork()
        val body = try {
            request(path, allParams, key)
        } catch (e: TmdbException) {
            val limited = staleOnRateLimit && e.error is TmdbError.RateLimited
            if (e.error !is TmdbError.Network && e.error !is TmdbError.Server && !limited) throw e
            // The answer already read, else any older one TMDB's terms let us keep; either way the copy TMDB gave last.
            val stale = cached ?: cache.get(cacheKey, MetadataCache.MAX_AGE)?.let { Aged(decode(serializer, it.body), it.age) } ?: throw e
            log.debug("TMDB {} ({}), using cached {}", if (limited) "says to slow down" else "unreachable", e.error, path)
            return@withContext if (limited) stale.copy(rateLimited = true) else stale.copy(offline = true)
        }
        val value = decode(serializer, body)
        // Caching is best effort: an answer that came back is returned even if it can't be stored.
        try {
            cache.put(cacheKey, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("TMDB response for {} not cached: {}", path, e.toString())
        }
        Aged(value, Duration.ZERO)
    }

    private suspend fun <T> decode(serializer: KSerializer<T>, body: String): T = withContext(defaultDispatcher) {
        try {
            json.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw TmdbException(TmdbError.BadResponse("Unexpected TMDB response: ${e.message?.take(200)}"), e)
        } catch (e: IllegalArgumentException) {
            throw TmdbException(TmdbError.BadResponse("Unexpected TMDB response: ${e.message?.take(200)}"), e)
        }
    }

    private suspend fun request(path: String, params: Map<String, String>, key: String): String {
        val bearer = isReadAccessToken(key)
        val url = URLBuilder(baseUrl).apply {
            appendPathSegments(path.split('/'))
            params.forEach { (k, v) -> parameters.append(k, v) }
            if (!bearer) parameters.append("api_key", key)
        }.buildString()
        val response = try {
            http.get(url) {
                if (bearer) header(HttpHeaders.Authorization, "Bearer $key")
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = timeoutMs }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw TmdbException(TmdbError.Network("TMDB didn't answer within ${timeoutMs / 1000} s"))
        } catch (e: Exception) {
            // IO errors, and whatever else Ktor or OkHttp throw (a redirect loop, say).
            throw TmdbException(TmdbError.Network((e.message ?: "Network error").replace(key, "***")))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val message = runCatching { json.decodeFromString(StatusWire.serializer(), body).statusMessage }.getOrNull()
                ?: "HTTP ${response.status.value}"
            throw TmdbException(
                when (response.status) {
                    HttpStatusCode.Unauthorized -> TmdbError.InvalidKey(message)
                    HttpStatusCode.NotFound -> TmdbError.NotFound(message)
                    HttpStatusCode.TooManyRequests -> TmdbError.RateLimited()
                    HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity -> TmdbError.BadRequest(message)
                    else -> TmdbError.Server(message)
                },
            )
        }
        return body
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** v4 read access tokens are JWTs; v3 keys are 32 hex characters. */
        fun isReadAccessToken(key: String) = key.startsWith("eyJ") && key.count { it == '.' } == 2

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout)
            expectSuccess = false
        }

        internal fun date(text: String?): LocalDate? = text?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        internal fun year(text: String?): Int? = date(text)?.year ?: text?.take(4)?.toIntOrNull()

        /**
         * TMDB's original_language as the ISO 639-1 code release names are read in: TMDB's "cn" (Cantonese)
         * is Chinese ("zh"), as "CANTONESE" in a name is; "xx" (no language) and blank are none.
         */
        fun originalLanguage(code: String?): String? = when (val c = code?.trim()?.lowercase()) {
            null, "", "xx" -> null
            "cn" -> "zh"
            else -> c
        }
    }
}

private fun MultiResultWire.toSuggestion(type: String?): TitleSuggestion? = when (type) {
    "tv" -> TitleSuggestion(MediaType.Tv, id, name ?: return null, originalName, TmdbClient.year(firstAirDate), posterPath, overview?.ifBlank { null })
    "movie" -> TitleSuggestion(MediaType.Movie, id, title ?: return null, originalTitle, TmdbClient.year(releaseDate), posterPath, overview?.ifBlank { null })
    else -> null
}

private fun EpisodeRefWire.toSummary() = EpisodeSummary(seasonNumber, episodeNumber, name?.ifBlank { null }, TmdbClient.date(airDate))

private fun TvWire.toDetails() = ShowDetails(
    tmdbId = id,
    name = name,
    originalName = originalName,
    year = TmdbClient.year(firstAirDate),
    overview = overview?.ifBlank { null },
    posterPath = posterPath,
    backdropPath = backdropPath,
    status = status,
    inProduction = inProduction,
    episodeRuntimeMinutes = episodeRunTime.firstOrNull { it > 0 },
    seasons = seasons.map { SeasonSummary(it.seasonNumber, it.name, it.episodeCount, TmdbClient.date(it.airDate), it.posterPath) },
    lastEpisode = lastEpisodeToAir?.toSummary(),
    nextEpisode = nextEpisodeToAir?.toSummary(),
    ids = ExternalIds(tvdb = externalIds?.tvdbId?.takeIf { it > 0 }, imdb = externalIds?.imdbId?.ifBlank { null }),
    originalLanguage = TmdbClient.originalLanguage(originalLanguage),
)

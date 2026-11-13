package io.skjaere.usenet.metadata

import io.ktor.client.HttpClient
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Intro, recap, credits and preview times for TV episodes from TheIntroDB
 * (https://theintrodb.org), by TMDB id, season and episode: `GET /media` of its v3 API, which
 * needs no key. It is also sent the file's length (`duration_ms`), so it answers with the times of
 * the version of the episode closest to the file playing (a cut with or without a recap, say);
 * without a match it falls back to the version most submitted, as it does without a length.
 * Asked only while [enabled] (the user's opt-in) says so, and never for more than [timeoutMs];
 * any failure means no segments, so playback never waits on it or sees an error. Answers are
 * cached per episode and length, times for [FOUND_FOR] and "nothing known" (404) for
 * [NOT_FOUND_FOR]; failures aren't cached. The cache is in memory only: TheIntroDB's terms
 * (section 4.3) allow temporary client-side caching, such as for the duration of a session, so
 * answers never go to disk and end with the app's process.
 *
 * Safe to call from the main thread: the setting and the cache are read on [ioDispatcher], and
 * answers decoded on [defaultDispatcher].
 */
class IntroDbClient(
    private val http: HttpClient,
    private val enabled: suspend () -> Boolean,
    private val cache: InMemoryMetadataCache = InMemoryMetadataCache(),
    private val baseUrl: String = "https://api.theintrodb.org/v3",
    private val timeoutMs: Long = 3_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = LoggerFactory.getLogger(IntroDbClient::class.java)

    /**
     * The segments TheIntroDB has for an episode in a file of [durationMs], in its order; empty when
     * it has none, the setting is off, the length is unknown (zero or less) or anything fails.
     */
    suspend fun episode(showTmdbId: Int, season: Int, episode: Int, durationMs: Long): List<Segment> = withContext(ioDispatcher) {
        // TheIntroDB numbers seasons and episodes from 1, so specials can't be asked about.
        if (!enabled() || showTmdbId !in 1..MAX_TMDB_ID || season < 1 || episode < 1 || durationMs <= 0) return@withContext emptyList()
        // To the second: closer than any two versions of an episode, and no more than it needs.
        val length = (durationMs + 500) / 1000 * 1000
        val cacheKey = "theintrodb/media?tmdb_id=$showTmdbId&season=$season&episode=$episode&duration_ms=$length"
        val cached = cache.get(cacheKey, FOUND_FOR)?.takeIf { it.body != NOT_FOUND || it.age <= NOT_FOUND_FOR }
        if (cached?.body == NOT_FOUND) return@withContext emptyList()
        // An entry that no longer decodes is fetched again.
        cached?.let { decode(it.body) }?.let { return@withContext it.segments() }
        val body = fetch(showTmdbId, season, episode, length) ?: return@withContext emptyList()
        if (body == NOT_FOUND) return@withContext emptyList<Segment>().also { store(cacheKey, NOT_FOUND) }
        val media = decode(body) ?: return@withContext emptyList<Segment>().also { log.debug("TheIntroDB answered something unexpected: {}", body.take(200)) }
        store(cacheKey, body)
        media.segments()
    }

    /** The answer's body, [NOT_FOUND] for a 404, or null when there's no answer to use. */
    private suspend fun fetch(showTmdbId: Int, season: Int, episode: Int, durationMs: Long): String? {
        val url = URLBuilder(baseUrl).apply {
            appendPathSegments("media")
            parameters.append("tmdb_id", showTmdbId.toString())
            parameters.append("season", season.toString())
            parameters.append("episode", episode.toString())
            parameters.append("duration_ms", durationMs.toString())
        }.buildString()
        val answer = try {
            withTimeoutOrNull(timeoutMs) {
                val response = http.get(url) { header(HttpHeaders.Accept, "application/json") }
                response.status to response.bodyAsText()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IO errors, and whatever else Ktor or OkHttp throw.
            log.debug("TheIntroDB unreachable: {}", e.toString())
            return null
        }
        val (status, body) = answer ?: return null.also { log.debug("TheIntroDB didn't answer within {} ms", timeoutMs) }
        return when {
            status == HttpStatusCode.NotFound -> NOT_FOUND
            status.isSuccess() -> body
            // Rate and daily limits (429), bad requests, server errors: try again next time.
            else -> null.also { log.debug("TheIntroDB answered HTTP {}: {}", status.value, body.take(200)) }
        }
    }

    /** Caching is best effort: an answer is used even if it can't be stored. */
    private fun store(key: String, body: String) {
        try {
            cache.put(key, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("TheIntroDB answer not cached: {}", e.toString())
        }
    }

    private suspend fun decode(body: String): MediaWire? = withContext(defaultDispatcher) {
        try {
            json.decodeFromString(MediaWire.serializer(), body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * As TheIntroDB documents its times: an intro or recap without a start starts at the
     * beginning, and one ending at 0 means "none"; credits or a preview without an end run to the
     * end, and one starting (or ending) at 0 means "none".
     */
    private fun MediaWire.segments(): List<Segment> {
        fun opening(kind: SegmentKind, times: List<TimesWire>) = times.mapNotNull { t ->
            val end = t.endMs?.takeIf { it > 0 } ?: return@mapNotNull null
            Segment(kind, t.startMs ?: 0, end, SegmentSource.TheIntroDb)
        }
        fun closing(kind: SegmentKind, times: List<TimesWire>) = times.mapNotNull { t ->
            val start = t.startMs?.takeIf { it > 0 } ?: return@mapNotNull null
            if (t.endMs == 0L) return@mapNotNull null
            Segment(kind, start, t.endMs ?: Segment.TO_END, SegmentSource.TheIntroDb)
        }
        return opening(SegmentKind.Intro, intro) + opening(SegmentKind.Recap, recap) + closing(SegmentKind.Credits, credits) + closing(SegmentKind.Preview, preview)
    }

    companion object {
        val FOUND_FOR: Duration = Duration.ofDays(3)
        val NOT_FOUND_FOR: Duration = Duration.ofDays(1)
        private const val MAX_TMDB_ID = 10_000_000
        /** Cached in place of an answer for an episode TheIntroDB has nothing for. */
        private const val NOT_FOUND = "not found"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** `GET /media`'s answer; a kind with no submissions is left out. */
@Serializable
private class MediaWire(
    @SerialName("tmdb_id") val tmdbId: Int,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null,
    val intro: List<TimesWire> = emptyList(),
    val recap: List<TimesWire> = emptyList(),
    val credits: List<TimesWire> = emptyList(),
    val preview: List<TimesWire> = emptyList(),
)

/** Milliseconds; both fields are always there, either may be null. */
@Serializable
private class TimesWire(
    @SerialName("start_ms") val startMs: Long?,
    @SerialName("end_ms") val endMs: Long?,
)

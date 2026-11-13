package io.skjaere.usenet.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbClientTest {
    private val v3Key = "0123456789abcdef0123456789abcdef"
    private val v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl"

    private var now = Instant.parse("2026-10-13T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/tmdb/$name")).readText()

    private val requests = mutableListOf<HttpRequestData>()
    private var failNetwork = false
    /** A status TMDB answers every request with, as it does a page past 500 (400) or a filter it can't read (422). */
    private var refuse: HttpStatusCode? = null
    /** TMDB saying to slow down (429). */
    private var limit = false

    private val http = HttpClient(MockEngine { request ->
        requests += request
        if (failNetwork) throw IOException("unreachable api_key=$v3Key")
        val path = request.url.encodedPath.removePrefix("/3/")
        val key = request.url.parameters["api_key"] ?: request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        val refused = refuse
        val (body, status) = when {
            key != v3Key && key != v4Token -> fixture("error-401.json") to HttpStatusCode.Unauthorized
            refused != null -> """{"status_code":22,"status_message":"Invalid page: Pages start at 1 and max at 500. They are expected to be an integer."}""" to refused
            limit -> """{"status_code":25,"status_message":"Your request count (41) is over the allowed limit of (40)."}""" to HttpStatusCode.TooManyRequests
            path == "authentication" -> fixture("authentication.json") to HttpStatusCode.OK
            path == "search/multi" && request.url.parameters["page"] == "2" -> fixture("search-multi-page-2.json") to HttpStatusCode.OK
            path == "search/multi" -> fixture("search-multi.json") to HttpStatusCode.OK
            path == "tv/900002" -> fixture("tv.json") to HttpStatusCode.OK
            path == "tv/900002/season/1" -> fixture("season-1.json") to HttpStatusCode.OK
            path == "tv/900002/season/2" -> fixture("season-2.json") to HttpStatusCode.OK
            path == "movie/900011" -> fixture("movie.json") to HttpStatusCode.OK
            path == "movie/900013" -> fixture("movie-upcoming.json") to HttpStatusCode.OK
            path == "find/900001" -> fixture("find-tvdb.json") to HttpStatusCode.OK
            else -> """{"status_code":34,"status_message":"The resource you requested could not be found."}""" to HttpStatusCode.NotFound
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        install(HttpTimeout)
        expectSuccess = false
    }

    private var key: String? = v3Key
    private var language = "en-US"
    private val cache = InMemoryMetadataCache(clock)
    private val client = TmdbClient(http, { key }, { language }, cache, clock)

    @Test
    fun `search keeps titles in order and drops people`() = runBlocking<Unit> {
        val results = client.searchTitles("harbor")
        assertEquals(listOf(MediaType.Tv to "Harbor Lights", MediaType.Movie to "Copper Crown", MediaType.Tv to "Harbor Lights (1998)"), results.map { it.type to it.name })
        assertEquals(2021, results[0].year)
        assertEquals("Kupferkrone", results[1].originalName)
        assertNull(results[1].overview)
        assertNull(results[2].year)
        assertEquals("harbor", requests.single().url.parameters["query"])
        assertEquals("false", requests.single().url.parameters["include_adult"])
    }

    @Test
    fun `v3 keys go in the query, v4 tokens in the header`() = runBlocking<Unit> {
        client.searchTitles("a")
        assertEquals(v3Key, requests.last().url.parameters["api_key"])

        key = v4Token
        client.searchTitles("b")
        assertNull(requests.last().url.parameters["api_key"])
        assertEquals("Bearer $v4Token", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `show details with ids, seasons and next episode`() = runBlocking<Unit> {
        val show = client.show(900002)
        assertEquals(ExternalIds(tvdb = 900001, imdb = "tt9000003"), show.ids)
        assertEquals(45, show.episodeRuntimeMinutes)
        assertEquals(listOf(0, 1, 2), show.seasons.map { it.number })
        assertTrue(show.seasons.first().isSpecials)
        assertEquals(EpisodeSummary(2, 6, null, LocalDate.of(2026, 10, 20)), show.nextEpisode)
        assertEquals("external_ids", requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun `season episodes`() = runBlocking<Unit> {
        val season = client.season(900002, 1)
        assertEquals(listOf(1, 2, 3), season.episodes.map { it.episode })
        assertEquals("Pilot", season.episodes[0].name)
        assertEquals(52, season.episodes[0].runtimeMinutes)
        assertNull(season.episodes[1].overview)
        assertNull(season.episodes[1].runtimeMinutes)
        assertNull(season.overview)
        val airing = client.season(900002, 2).episodes
        assertTrue(airing[0].hasAired(LocalDate.of(2026, 10, 13)))
        assertFalse(airing[1].hasAired(LocalDate.of(2026, 10, 13)))
        assertFalse(airing[2].hasAired(LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun `an episode runtime of zero is unknown`() = runBlocking<Unit> {
        val episode = client.season(900002, 2).episodes.single { it.episode == 7 }
        assertNull(episode.runtimeMinutes)
    }

    @Test
    fun `movie and find`() = runBlocking<Unit> {
        val movie = client.movie(900011)
        assertEquals(118, movie.runtimeMinutes)
        assertEquals("tt9000012", movie.ids.imdb)
        assertEquals(2019, movie.year)
        val found = client.findByTvdb(900001)!!
        assertEquals(MediaType.Tv to 900002, found.type to found.tmdbId)
        assertEquals("tvdb_id", requests.last().url.parameters["external_source"])
    }

    @Test
    fun `responses are cached per language and refresh bypasses the cache`() = runBlocking<Unit> {
        client.show(900002)
        client.show(900002)
        assertEquals(1, requests.size)
        client.show(900002, refresh = true)
        assertEquals(2, requests.size)
        language = "es-ES"
        client.show(900002)
        assertEquals(3, requests.size)
        assertEquals("es-ES", requests.last().url.parameters["language"])
        now = now.plus(Duration.ofHours(25))
        language = "en-US"
        client.show(900002)
        assertEquals(4, requests.size)
    }

    @Test
    fun `an airing season refreshes hourly, a finished one daily`() = runBlocking<Unit> {
        client.season(900002, 1)
        client.season(900002, 2)
        now = now.plus(Duration.ofHours(2))
        client.season(900002, 1)
        client.season(900002, 2)
        val fetched = requests.map { it.url.encodedPath.substringAfterLast('/') }
        assertEquals(listOf("1", "2", "2"), fetched)
    }

    @Test
    fun `a show whose next episode has aired refreshes hourly`() = runBlocking<Unit> {
        val cache = RecordingCache(InMemoryMetadataCache(clock))
        val client = TmdbClient(http, { key }, { language }, cache, clock)
        // The fixture's next episode, S02E06, airs 20 Oct.
        client.show(900002)
        now = now.plus(Duration.ofHours(2))
        client.show(900002)
        assertEquals(1, requests.size)
        now = Instant.parse("2026-10-19T23:30:00Z")
        client.show(900002)
        assertEquals(2, requests.size)
        // 20 Oct: the cached next episode has aired, so the copy from 23:30 is fresh for an hour.
        now = Instant.parse("2026-10-20T00:15:00Z")
        cache.gets.set(0)
        client.show(900002)
        assertEquals(2, requests.size)
        assertEquals(1, cache.gets.get())
        now = Instant.parse("2026-10-20T01:30:00Z")
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals(3, requests.size)
    }

    @Test
    fun `stale cache is used when TMDB is unreachable`() = runBlocking<Unit> {
        client.show(900002)
        now = now.plus(Duration.ofDays(3))
        failNetwork = true
        assertEquals("Harbor Lights", client.show(900002).name)
        val e = assertFailsWith<TmdbException> { client.season(900002, 1) }
        assertIs<TmdbError.Network>(e.error)
        assertFalse(v3Key in e.message.orEmpty())
    }

    @Test
    fun `a request TMDB refuses (400, 422) never falls back to an old copy`() = runBlocking<Unit> {
        client.show(900002)
        client.movie(900011)
        // Past the day a show is kept for, and the week a movie is: both are asked for again.
        now = now.plus(Duration.ofDays(8))
        refuse = HttpStatusCode.BadRequest
        assertFailsWith<TmdbException> { client.show(900002) }
        refuse = HttpStatusCode.UnprocessableEntity
        assertFailsWith<TmdbException> { client.movie(900011) }
        assertEquals(4, requests.size)
    }

    @Test
    fun `400 and 422 are bad requests, with TMDB's message`() = runBlocking<Unit> {
        listOf(HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity).forEach { status ->
            refuse = status
            val error = assertFailsWith<TmdbException> { client.show(900002) }.error
            assertIs<TmdbError.BadRequest>(error, "HTTP ${status.value}")
            assertEquals("Invalid page: Pages start at 1 and max at 500. They are expected to be an integer.", error.message)
        }
    }

    /** Another module's call (a path this client has no method for), with its own answer type. */
    @Serializable
    private data class NameOnly(val name: String)

    @Test
    fun `a fetch of any path shares the key, the language and the cache`() = runBlocking<Unit> {
        val answer = client.fetch("tv/900002", mapOf("append_to_response" to "external_ids"), NameOnly.serializer(), Duration.ofHours(6))
        assertEquals(Aged(NameOnly("Harbor Lights"), Duration.ZERO), answer)
        assertEquals(v3Key to "en-US", requests.single().url.parameters.let { it["api_key"] to it["language"] })
        now = now.plus(Duration.ofHours(5))
        assertEquals(Duration.ofHours(5), client.fetch("tv/900002", mapOf("append_to_response" to "external_ids"), NameOnly.serializer(), Duration.ofHours(6)).age)
        // The show's own call asks the same of TMDB: the same cached answer.
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals(1, requests.size)
        key = null
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { client.fetch("tv/900002", emptyMap(), NameOnly.serializer(), Duration.ofHours(6)) }.error)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a paged answer says how many pages there are`() = runBlocking<Unit> {
        val page = client.fetch(
            "search/multi", mapOf("query" to "harbor", "page" to "2"),
            PagedWire.serializer(NameOnly.serializer()), Duration.ofHours(1),
        ).value
        assertEquals(PagedWire(page = 2, results = listOf(NameOnly("Harbor Watch")), totalResults = 47, totalPages = 3), page)
    }

    @Test
    fun `a fetch may take the last copy while TMDB says to slow down`() = runBlocking<Unit> {
        suspend fun fetch(stale: Boolean) = client.fetch("tv/900002", emptyMap(), NameOnly.serializer(), Duration.ofHours(1), staleOnRateLimit = stale)
        fetch(stale = true)
        now = now.plus(Duration.ofHours(2))
        limit = true
        // Without asking for it, TMDB's answer as it is (the lists' refresher pauses on it).
        assertIs<TmdbError.RateLimited>(assertFailsWith<TmdbException> { fetch(stale = false) }.error)
        assertEquals(Aged(NameOnly("Harbor Lights"), Duration.ofHours(2), rateLimited = true), fetch(stale = true))
        // With no copy to fall back to, the error.
        assertIs<TmdbError.RateLimited>(assertFailsWith<TmdbException> { client.fetch("tv/1", emptyMap(), NameOnly.serializer(), Duration.ofHours(1), staleOnRateLimit = true) }.error)
        assertEquals(4, requests.size)
    }

    @Test
    fun `the offline fallback reaches back six months at most`() = runBlocking<Unit> {
        client.show(900002)
        failNetwork = true
        now = now.plus(Duration.ofDays(180))
        assertEquals(Duration.ofDays(180) to true, client.showAged(900002).let { it.age to it.offline })
        // TMDB's terms let an answer be kept six months at most: older, it is as good as none.
        now = now.plus(Duration.ofDays(1))
        assertIs<TmdbError.Network>(assertFailsWith<TmdbException> { client.show(900002) }.error)
    }

    @Test
    fun `failures other than IO are network errors without the key`() = runBlocking<Unit> {
        fun client(handler: MockRequestHandler) = TmdbClient(
            HttpClient(MockEngine(handler)) {
                install(HttpTimeout)
                expectSuccess = false
            },
            { v3Key },
            clock = clock,
        )
        // A redirect loop ends in Ktor's SendCountExceedException.
        val looping = client { request -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, request.url.toString())) }
        val rejecting = client { request -> throw IllegalArgumentException("unexpected url ${request.url}") }
        listOf(looping, rejecting).forEach { client ->
            val e = assertFailsWith<TmdbException> { client.show(900002) }
            assertIs<TmdbError.Network>(e.error)
            assertFalse(v3Key in e.message.orEmpty())
        }
    }

    @Test
    fun `key problems`() = runBlocking<Unit> {
        assertEquals(KeyCheck.Valid, client.validate(v3Key))
        assertEquals(KeyCheck.Valid, client.validate(" $v4Token "))
        assertEquals(KeyCheck.Invalid, client.validate("wrong"))
        key = "wrong"
        val invalid = assertFailsWith<TmdbException> { client.show(900002) }.error
        assertEquals("Invalid API key: You must be granted a valid key.", invalid.message)
        key = null
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { client.show(900002) }.error)
        key = v3Key
        assertIs<TmdbError.NotFound>(assertFailsWith<TmdbException> { client.show(1) }.error)
    }

    @Test
    fun `token detection`() {
        assertTrue(TmdbClient.isReadAccessToken(v4Token))
        assertFalse(TmdbClient.isReadAccessToken(v3Key))
    }

    @Test
    fun `file cache round trip and expiry`() {
        val dir = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val fileCache = FileMetadataCache(dir, clock)
        fileCache.put("tv/1?language=en-US", "{}")
        assertEquals("{}", fileCache.get("tv/1?language=en-US", Duration.ofHours(1))?.body)
        now = now.plus(Duration.ofHours(2))
        assertNull(fileCache.get("tv/1?language=en-US", Duration.ofHours(1)))
        assertTrue(fileCache.sizeBytes() > 0)
        fileCache.clear()
        assertEquals(0, fileCache.sizeBytes())
        dir.deleteRecursively()
    }

    /**
     * TMDB's terms let an answer be cached six months at most: the file cache removes entries not written for 180 days, on
     * its first use after it is opened (as the app starts) and then at most daily, and never serves one older meanwhile.
     */
    @Test
    fun `the file cache removes entries not written for six months, on its first use and then at most daily`() {
        val dir = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val start = now
        FileMetadataCache(dir, clock).apply {
            put("tv/1?language=en-US", "{\"old\":true}")
            now = start.plus(Duration.ofHours(12))
            put("tv/2?language=en-US", "{\"two\":true}")
        }
        val written = dir.listFiles()!!.associate { it.name to it.lastModified() }
        // The app opened again: tv/1 is 180 days and an hour old, tv/2 179 days and 13 hours.
        now = start.plus(Duration.ofDays(180)).plus(Duration.ofHours(1))
        val cache = FileMetadataCache(dir, clock)
        assertEquals(written, dir.listFiles()!!.associate { it.name to it.lastModified() }, "opening touches no files")
        assertNull(cache.get("tv/1?language=en-US", Duration.ofDays(3650)))
        assertEquals(1, dir.list()!!.size, "the first use removes what is too old")
        assertEquals("{\"two\":true}", cache.get("tv/2?language=en-US", Duration.ofDays(3650))?.body)
        // 23 hours on, tv/2 is past six months: not served, but not removed before the day is out.
        now = now.plus(Duration.ofHours(23))
        assertNull(cache.get("tv/2?language=en-US", Duration.ofDays(3650)))
        assertEquals(1, dir.list()!!.size)
        now = now.plus(Duration.ofHours(2))
        cache.put("tv/3?language=en-US", "{\"three\":true}")
        assertEquals(listOf("{\"three\":true}"), dir.listFiles()!!.map { it.readText() }, "a day after the last sweep, the next")
        dir.deleteRecursively()
    }

    @Test
    fun `file cache recreates a directory deleted while running`() {
        val dir = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val fileCache = FileMetadataCache(dir, clock)
        // Clear cache in the system settings, or the system trimming caches, with the app running.
        dir.deleteRecursively()
        assertNull(fileCache.get("tv/1?language=en-US", Duration.ofDays(3650)))
        assertEquals(0, fileCache.sizeBytes())
        fileCache.put("tv/1?language=en-US", "{}")
        assertEquals("{}", fileCache.get("tv/1?language=en-US", Duration.ofHours(1))?.body)
        dir.deleteRecursively()
    }

    @Test
    fun `a failed file cache write leaves no temporary file`() {
        val dir = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val fileCache = FileMetadataCache(dir, clock)
        fileCache.put("tv/1?language=en-US", "{}")
        // A directory in the entry's place makes the final rename fail.
        val entry = dir.listFiles()!!.single().apply { delete(); mkdir() }
        fileCache.put("tv/1?language=en-US", "{}")
        assertEquals(listOf(entry.name), dir.list()!!.toList())
        assertNull(fileCache.get("tv/1?language=en-US", Duration.ofHours(1)))
        dir.deleteRecursively()
    }

    @Test
    fun `a fetch succeeds when the cache directory was deleted while running`() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val client = TmdbClient(http, { key }, { language }, FileMetadataCache(dir, clock), clock)
        dir.deleteRecursively()
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals(1, requests.size)
        dir.deleteRecursively()
    }

    @Test
    fun `a fetch succeeds when the cache directory can't be written`() = runBlocking<Unit> {
        val parent = kotlin.io.path.createTempDirectory("tmdb").toFile()
        val notADirectory = File(parent, "file").apply { writeText("") }
        val readOnly = File(parent, "read-only").apply { mkdir(); setWritable(false) }
        try {
            listOf(notADirectory, readOnly).forEach { dir ->
                val client = TmdbClient(http, { key }, { language }, FileMetadataCache(dir, clock), clock)
                assertEquals("Harbor Lights", client.show(900002).name)
                assertEquals("Harbor Lights", client.show(900002).name)
                assertEquals("Copper Crown", client.movie(900011).title)
            }
        } finally {
            readOnly.setWritable(true)
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a fetch succeeds when the cache fails to store it`() = runBlocking<Unit> {
        val full = object : MetadataCache by InMemoryMetadataCache(clock) {
            override fun put(key: String, body: String) {
                throw IOException("No space left on device")
            }
        }
        val client = TmdbClient(http, { key }, { language }, full, clock)
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals("Harbor Lights", client.show(900002).name)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a fetch from the main thread keeps the cache, key and decoding off it`() = runBlocking<Unit> {
        val cache = RecordingCache(InMemoryMetadataCache(clock))
        val keyThreads = CopyOnWriteArrayList<String>()
        val decoding = CountingDispatcher(Dispatchers.Default)
        val client = TmdbClient(http, { keyThreads += Thread.currentThread().name; key }, { language }, cache, clock, defaultDispatcher = decoding)
        fakeMain().use { main -> withContext(main) { assertEquals("Harbor Lights", client.show(900002).name) } }
        assertEquals(1, requests.size)
        assertEquals(2, cache.threads.size)
        assertTrue(cache.threads.none { it.isFakeMain() }, "cache used on ${cache.threads}")
        assertTrue(keyThreads.none { it.isFakeMain() }, "key read on $keyThreads")
        assertTrue(decoding.dispatches.get() > 0, "not decoded on the default dispatcher")
    }

    @Test
    fun `a cache hit from the main thread keeps the cache and decoding off it`() = runBlocking<Unit> {
        val cache = RecordingCache(InMemoryMetadataCache(clock))
        val decoding = CountingDispatcher(Dispatchers.Default)
        val client = TmdbClient(http, { key }, { language }, cache, clock, defaultDispatcher = decoding)
        client.show(900002)
        client.season(900002, 1)
        cache.threads.clear()
        decoding.dispatches.set(0)
        fakeMain().use { main ->
            withContext(main) {
                assertEquals("Harbor Lights", client.show(900002).name)
                assertEquals("Pilot", client.season(900002, 1).episodes.first().name)
            }
        }
        assertEquals(2, requests.size)
        assertTrue(cache.threads.isNotEmpty())
        assertTrue(cache.threads.none { it.isFakeMain() }, "cache used on ${cache.threads}")
        assertTrue(decoding.dispatches.get() >= 2, "not decoded on the default dispatcher")
    }

    @Test
    fun `a fresh cached season is read once`() = runBlocking<Unit> {
        val cache = RecordingCache(InMemoryMetadataCache(clock))
        val client = TmdbClient(http, { key }, { language }, cache, clock)
        client.season(900002, 1)
        client.season(900002, 2)
        now = now.plus(Duration.ofMinutes(30))
        cache.gets.set(0)
        // A finished season, and an airing one still within its hour.
        assertEquals("Pilot", client.season(900002, 1).episodes.first().name)
        assertEquals("The Long Tide", client.season(900002, 2).episodes.first().name)
        assertEquals(2, cache.gets.get())
        assertEquals(2, requests.size)
    }

    @Test
    fun `an airing season past its hour falls back to the copy already read when TMDB is unreachable`() = runBlocking<Unit> {
        val cache = RecordingCache(InMemoryMetadataCache(clock))
        val client = TmdbClient(http, { key }, { language }, cache, clock)
        client.season(900002, 2)
        now = now.plus(Duration.ofHours(2))
        failNetwork = true
        cache.gets.set(0)
        assertEquals("The Long Tide", client.season(900002, 2).episodes.first().name)
        assertEquals(1, cache.gets.get())
        assertEquals(2, requests.size)
    }

    @Test
    fun `an answer says how old it is`() = runBlocking<Unit> {
        assertEquals(Aged(client.show(900002), Duration.ZERO), client.showAged(900002, refresh = true))
        now = now.plus(Duration.ofMinutes(30))
        assertEquals(Duration.ofMinutes(30), client.showAged(900002).age)
        assertFalse(client.showAged(900002).offline)
        assertEquals(Duration.ZERO, client.movieAged(900011).age)
        now = now.plus(Duration.ofHours(5))
        val movie = client.movieAged(900011)
        assertEquals(Duration.ofHours(5) to false, movie.age to movie.offline)
        assertEquals("Copper Crown", movie.value.title)
        assertEquals(3, requests.size)
    }

    @Test
    fun `a season says how old it is`() = runBlocking<Unit> {
        assertEquals(Aged(client.season(900002, 1), Duration.ZERO), client.seasonAged(900002, 1))
        now = now.plus(Duration.ofMinutes(30))
        assertEquals(Duration.ofMinutes(30), client.seasonAged(900002, 1).age)
        assertEquals(1, requests.size)
    }

    @Test
    fun `an answer stays good as long as the client keeps it`() = runBlocking<Unit> {
        val today = LocalDate.of(2026, 10, 13)
        // An airing season an hour, a finished one a day (as `an airing season refreshes hourly, a finished one daily`).
        assertEquals(Duration.ofHours(1), Freshness.answer(client.season(900002, 2), today))
        assertEquals(Duration.ofHours(24), Freshness.answer(client.season(900002, 1), today))
        // A show a day at most, an ended one too (the lists' refresher asks about it weekly); one whose next episode is due, an hour.
        val show = client.show(900002)
        assertEquals(Duration.ofHours(24), Freshness.answer(show, today))
        assertEquals(Duration.ofHours(24), Freshness.answer(show.copy(status = "Ended", nextEpisode = null), today))
        assertEquals(Duration.ofHours(1), Freshness.answer(show, LocalDate.of(2026, 10, 20)))
        // A movie a week at most; one not out yet a day.
        assertEquals(Duration.ofDays(7), Freshness.answer(client.movie(900011), today))
        assertEquals(Duration.ofHours(24), Freshness.answer(client.movie(900013), today))
    }

    @Test
    fun `an answer from the cache while TMDB is unreachable says so, with its age`() = runBlocking<Unit> {
        client.show(900002)
        client.movie(900013)
        now = now.plus(Duration.ofDays(2))
        failNetwork = true
        // Past the day a show is kept for: the copy TMDB gave last.
        assertEquals(Aged(client.show(900002), Duration.ofDays(2), offline = true), client.showAged(900002))
        // A refresh doesn't read the cache, but falls back to it all the same.
        assertEquals(Duration.ofDays(2) to true, client.showAged(900002, refresh = true).let { it.age to it.offline })
        // Within the week a movie is kept for, but due again (it isn't out yet): the copy already read.
        val movie = client.movieAged(900013)
        assertEquals(Duration.ofDays(2) to true, movie.age to movie.offline)
        assertEquals("Lantern Field", movie.value.title)
    }

    @Test
    fun `a movie's release date and status`() = runBlocking<Unit> {
        val out = client.movie(900011)
        assertEquals(LocalDate.of(2019, 11, 8) to "Released", out.releaseDate to out.status)
        val upcoming = client.movie(900013)
        assertEquals(LocalDate.of(2027, 3, 12) to "Post Production", upcoming.releaseDate to upcoming.status)
        assertEquals(2027, upcoming.year)
        assertNull(upcoming.runtimeMinutes)
    }

    @Test
    fun `a show's and a movie's original language`() = runBlocking<Unit> {
        assertEquals("en", client.show(900002).originalLanguage)
        assertEquals("de", client.movie(900011).originalLanguage)
        // TMDB's own code for Cantonese, which release names call Chinese.
        assertEquals("zh", client.movie(900013).originalLanguage)
    }

    @Test
    fun `an original language is read as the release names' codes`() {
        assertEquals("ko", TmdbClient.originalLanguage("ko"))
        assertEquals("ko", TmdbClient.originalLanguage("KO"))
        assertEquals("zh", TmdbClient.originalLanguage("cn"))
        // "No language": a silent film, or one TMDB knows nothing of.
        assertNull(TmdbClient.originalLanguage("xx"))
        assertNull(TmdbClient.originalLanguage(" "))
        assertNull(TmdbClient.originalLanguage(null))
    }

    @Test
    fun `a movie not out yet is asked about again daily, one that is out weekly`() = runBlocking<Unit> {
        client.movie(900011)
        client.movie(900013)
        now = now.plus(Duration.ofHours(25))
        client.movie(900011)
        client.movie(900013)
        assertEquals(listOf("900011", "900013", "900013"), requests.map { it.url.encodedPath.substringAfterLast('/') })
        now = now.plus(Duration.ofDays(6))
        client.movie(900011)
        assertEquals(4, requests.size)
    }

    @Test
    fun `a client built without a clock counts days in the device's zone, not in UTC`() = runBlocking<Unit> {
        // A zone whose date differs from UTC's right now: a day behind before 11:00 UTC, a day ahead after.
        val zone = if (ZonedDateTime.now(ZoneOffset.UTC).hour < 11) ZoneId.of("Etc/GMT+12") else ZoneId.of("Etc/GMT-13")
        val behind = zone == ZoneId.of("Etc/GMT+12")
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val localToday = LocalDate.now(zone)
            // The next episode airs tomorrow there (today in UTC) or today there (tomorrow in UTC).
            val airs = if (behind) localToday.plusDays(1) else localToday
            val tv = fixture("tv.json").replace("2026-10-20", airs.toString())
            val http = HttpClient(MockEngine { request ->
                requests += request
                respond(tv, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(HttpTimeout); expectSuccess = false }
            // The cache ages entries by the test's clock; the client tells the day by the system's.
            val client = TmdbClient(http, { key }, { language }, InMemoryMetadataCache(clock))
            client.show(900002)
            now = now.plus(Duration.ofHours(2))
            client.show(900002)
            // Airing today, it is asked about again after an hour; airing tomorrow, after a day.
            assertEquals(if (behind) 1 else 2, requests.size, "next episode airs $airs, $zone's today is $localToday")
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `only requests sent to TMDB pass beforeNetwork`() = runBlocking<Unit> {
        var passes = 0
        val client = TmdbClient(http, { key }, { language }, cache, clock, beforeNetwork = { passes++ })
        client.validate(v3Key)
        assertEquals(0, passes)
        client.show(900002)
        assertEquals(1, passes)
        // Cache hits don't.
        client.show(900002)
        assertEquals(1, passes)
        client.season(900002, 1)
        client.movie(900011)
        client.searchTitles("harbor")
        client.findByTvdb(900001)
        assertEquals(5, passes)
        // Without a key nothing is sent.
        key = null
        assertFailsWith<TmdbException> { client.movie(900013) }
        assertEquals(5, passes)
        // A request that fails was still sent.
        key = v3Key
        failNetwork = true
        now = now.plus(Duration.ofDays(2))
        assertTrue(client.showAged(900002).offline)
        assertEquals(6, passes)
    }

    private fun fakeMain() = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

    // Coroutines debug mode (on with -ea) appends " @coroutine#n" to thread names.
    private fun String.isFakeMain() = startsWith("fake-main")
}

/** Counts reads and records the threads the cache is used on. */
private class RecordingCache(private val inner: MetadataCache) : MetadataCache by inner {
    val gets = AtomicInteger()
    val threads = CopyOnWriteArrayList<String>()

    override fun get(key: String, maxAge: Duration) = inner.get(key, maxAge).also {
        gets.incrementAndGet()
        threads += Thread.currentThread().name
    }

    override fun put(key: String, body: String) {
        threads += Thread.currentThread().name
        inner.put(key, body)
    }
}

/** Dispatches to [inner], counting the dispatches. */
private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    val dispatches = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches.incrementAndGet()
        inner.dispatch(context, block)
    }
}

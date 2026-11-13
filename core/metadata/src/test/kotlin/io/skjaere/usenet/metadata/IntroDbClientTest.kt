package io.skjaere.usenet.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.metadata.SegmentKind.Credits
import io.skjaere.usenet.metadata.SegmentKind.Intro
import io.skjaere.usenet.metadata.SegmentKind.Preview
import io.skjaere.usenet.metadata.SegmentKind.Recap
import io.skjaere.usenet.metadata.SegmentSource.TheIntroDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroDbClientTest {
    private var now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/theintrodb/$name")).readText()

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    /** What TheIntroDB answers: a status and a fixture, or a failure. */
    private var answer: suspend () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to fixture("episode.json") }

    private val http = HttpClient(MockEngine { request ->
        requests += request
        val (status, body) = answer()
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        install(HttpTimeout)
        expectSuccess = false
    }

    private var enabled = true
    /** The length of the file playing, which TheIntroDB picks the version of the episode by. */
    private val length = 45 * 60_000L
    private val cache = InMemoryMetadataCache(clock)
    private fun client(timeoutMs: Long = 3_000, cache: InMemoryMetadataCache = this.cache) =
        IntroDbClient(http, { enabled }, cache, timeoutMs = timeoutMs)

    @Test
    fun `answers are kept in memory, for the session only as TheIntroDB's terms allow, so no cache that stores them on disk can be given`() {
        val caches = IntroDbClient::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .filter { MetadataCache::class.java.isAssignableFrom(it) }
        assertEquals(setOf(InMemoryMetadataCache::class.java), caches.toSet())
    }

    @Test
    fun `an episode's segments, a null intro start being the beginning and a null credits end the end of the file`() = runBlocking<Unit> {
        val segments = client().episode(900002, 1, 2, length)
        assertEquals(
            listOf(
                Segment(Intro, 0, 23_000, TheIntroDb),
                Segment(Recap, 25_000, 134_000, TheIntroDb),
                Segment(Credits, 2_580_000, 2_640_000, TheIntroDb),
                Segment(Credits, 2_652_000, Segment.TO_END, TheIntroDb),
                Segment(Preview, 2_680_000, 2_700_000, TheIntroDb),
            ),
            segments,
        )
        // Keyless GET /media by TMDB id, season, episode and the file's length, as documented; nothing else is sent.
        val request = requests.single()
        assertEquals("https://api.theintrodb.org/v3/media?tmdb_id=900002&season=1&episode=2&duration_ms=2700000", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the file's length is sent to the second, so TheIntroDB picks the version it matches, and each length is cached on its own`() = runBlocking<Unit> {
        val client = client()
        client.episode(900002, 1, 2, 2_610_400)
        assertEquals("https://api.theintrodb.org/v3/media?tmdb_id=900002&season=1&episode=2&duration_ms=2610000", requests.last().url.toString())
        // Another cut of the episode is asked about on its own; the same one again comes from the cache.
        client.episode(900002, 1, 2, 2_652_000)
        assertEquals("https://api.theintrodb.org/v3/media?tmdb_id=900002&season=1&episode=2&duration_ms=2652000", requests.last().url.toString())
        assertEquals(5, client.episode(900002, 1, 2, 2_610_400).size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `several segments of a kind are all returned`() = runBlocking<Unit> {
        answer = { HttpStatusCode.OK to fixture("episode-two-intros.json") }
        assertEquals(listOf(Segment(Intro, 30_000, 90_000, TheIntroDb), Segment(Intro, 120_000, 150_000, TheIntroDb)), client().episode(900002, 1, 1, length))
    }

    @Test
    fun `zero-length markers mean no segment`() = runBlocking<Unit> {
        answer = { HttpStatusCode.OK to fixture("episode-no-segments.json") }
        assertEquals(emptyList(), client().episode(900002, 1, 3, length))
    }

    @Test
    fun `with the setting off no request is made, not even for a cached episode`() = runBlocking<Unit> {
        enabled = false
        assertEquals(emptyList(), client().episode(900002, 1, 2, length))
        assertEquals(0, requests.size)
        enabled = true
        assertEquals(5, client().episode(900002, 1, 2, length).size)
        enabled = false
        assertEquals(emptyList(), client().episode(900002, 1, 2, length))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a found episode is cached for days`() = runBlocking<Unit> {
        val client = client()
        client.episode(900002, 1, 2, length)
        now = now.plus(Duration.ofDays(2))
        assertEquals(5, client.episode(900002, 1, 2, length).size)
        assertEquals(1, requests.size)
        // Another episode is asked for on its own.
        answer = { HttpStatusCode.OK to fixture("episode-two-intros.json") }
        assertEquals(2, client.episode(900002, 1, 1, length).size)
        assertEquals(2, requests.size)
        now = now.plus(Duration.ofDays(2))
        answer = { HttpStatusCode.OK to fixture("episode.json") }
        client.episode(900002, 1, 2, length)
        assertEquals(3, requests.size)
    }

    @Test
    fun `an episode TheIntroDB doesn't know is not asked about again for a day`() = runBlocking<Unit> {
        answer = { HttpStatusCode.NotFound to fixture("not-found.json") }
        val client = client()
        assertEquals(emptyList(), client.episode(900002, 1, 2, length))
        now = now.plus(Duration.ofHours(20))
        assertEquals(emptyList(), client.episode(900002, 1, 2, length))
        assertEquals(1, requests.size)
        now = now.plus(Duration.ofHours(5))
        answer = { HttpStatusCode.OK to fixture("episode.json") }
        assertEquals(5, client.episode(900002, 1, 2, length).size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a malformed answer means no segments and is not cached`() = runBlocking<Unit> {
        listOf(fixture("malformed.json"), "<html>Bad gateway</html>", "", """{"type":"tv"}""").forEach { body ->
            answer = { HttpStatusCode.OK to body }
            assertEquals(emptyList(), client().episode(900002, 1, 2, length), body)
        }
        answer = { HttpStatusCode.OK to fixture("episode.json") }
        assertEquals(5, client().episode(900002, 1, 2, length).size)
        assertEquals(5, requests.size)
    }

    @Test
    fun `rate limits, server errors and network failures mean no segments and are not cached`() = runBlocking<Unit> {
        val failures = listOf<suspend () -> Pair<HttpStatusCode, String>>(
            { HttpStatusCode.TooManyRequests to fixture("too-many-requests.json") },
            { HttpStatusCode.InternalServerError to """{"error":"internal"}""" },
            { HttpStatusCode.BadRequest to """{"error":"invalid season"}""" },
            { throw IOException("unreachable") },
            { throw IllegalStateException("engine broke") },
        )
        failures.forEach { failure ->
            answer = failure
            assertEquals(emptyList(), client().episode(900002, 1, 2, length))
        }
        answer = { HttpStatusCode.OK to fixture("episode.json") }
        assertEquals(5, client().episode(900002, 1, 2, length).size)
        assertEquals(failures.size + 1, requests.size)
    }

    @Test
    fun `a slow answer is given up on after the timeout and means no segments`() = runBlocking<Unit> {
        answer = {
            delay(5_000)
            HttpStatusCode.OK to fixture("episode.json")
        }
        val started = System.nanoTime()
        assertEquals(emptyList(), client(timeoutMs = 200).episode(900002, 1, 2, length))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 2_000, "took $tookMs ms")
        answer = { HttpStatusCode.OK to fixture("episode.json") }
        assertEquals(5, client(timeoutMs = 200).episode(900002, 1, 2, length).size)
    }

    @Test
    fun `the default timeout is about three seconds`() = runBlocking<Unit> {
        answer = {
            delay(10_000)
            HttpStatusCode.OK to fixture("episode.json")
        }
        val started = System.nanoTime()
        assertEquals(emptyList(), client().episode(900002, 1, 2, length))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 2_900..4_500, "took $tookMs ms")
    }

    @Test
    fun `a cancelled lookup is cancelled, not turned into no segments`() = runBlocking<Unit> {
        answer = {
            delay(5_000)
            HttpStatusCode.OK to fixture("episode.json")
        }
        assertFailsWith<CancellationException> { withTimeout(200) { client().episode(900002, 1, 2, length) } }
    }

    @Test
    fun `specials, impossible numbers and unknown lengths are not asked about`() = runBlocking<Unit> {
        // TheIntroDB numbers seasons and episodes from 1.
        assertEquals(emptyList(), client().episode(900002, 0, 1, length))
        assertEquals(emptyList(), client().episode(900002, 1, 0, length))
        assertEquals(emptyList(), client().episode(0, 1, 1, length))
        // Nor is a file of unknown length, whose segments couldn't be checked anyway.
        assertEquals(emptyList(), client().episode(900002, 1, 1, 0))
        assertEquals(emptyList(), client().episode(900002, 1, 1, -1))
        assertEquals(0, requests.size)
    }

    @Test
    fun `a lookup from the main thread keeps the cache and decoding off it`() = runBlocking<Unit> {
        // The cache reads its clock as it stores an answer and as it hands one back.
        val threads = CopyOnWriteArrayList<String>()
        val recording = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
            override fun instant() = now.also { threads += Thread.currentThread().name }
        }
        var setting = ""
        val client = IntroDbClient(http, { setting = Thread.currentThread().name; true }, InMemoryMetadataCache(recording))
        Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher().use { main ->
            withContext(main) {
                assertEquals(5, client.episode(900002, 1, 2, length).size)
                assertEquals(5, client.episode(900002, 1, 2, length).size)
            }
        }
        assertEquals(1, requests.size)
        assertEquals(2, threads.size)
        assertTrue(threads.none { it.startsWith("fake-main") }, "cache used on $threads")
        assertTrue(!setting.startsWith("fake-main"), "setting read on $setting")
    }
}

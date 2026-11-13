package io.skjaere.usenet.newznab

import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.skjaere.usenet.releases.AudioCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewznabClientTest {

    private fun answering(body: String, status: HttpStatusCode = HttpStatusCode.OK) = FakeIndexers { xml(body, status) }

    @Test
    fun `caps lists available searches with their parameters and categories`() = runBlocking<Unit> {
        val caps = answering(fixture("newznab-caps.xml")).client.caps(indexer())
        assertEquals("Example Newznab", caps.serverTitle)
        assertEquals(100, caps.maxLimit)
        assertTrue(caps.supports(SearchType.Tv, "tvdbid"))
        assertFalse(caps.supports(SearchType.Tv, "tmdbid"))
        assertTrue(caps.supports(SearchType.Movie, "imdbid"))
        assertEquals(setOf(SearchType.Search, SearchType.Tv, SearchType.Movie), caps.searching.keys)
        assertEquals(listOf(2000, 5000), caps.categories.map { it.id })
        assertEquals(listOf(2040, 2045), caps.categories.first().subcategories.map { it.id })
    }

    /** Self-hosted indexers (NZBHydra, Prowlarr) list each client by its user agent, so the user sees the app's name there. */
    @Test
    fun `requests carry the app's name as their user agent`() = runBlocking<Unit> {
        val fake = answering(fixture("newznab-caps.xml"))
        fake.client.caps(indexer())
        assertEquals("NZStreamer/0.1", fake.requests.single().headers[HttpHeaders.UserAgent])
    }

    @Test
    fun `prowlarr caps without movie search`() = runBlocking<Unit> {
        val caps = answering(fixture("prowlarr-caps.xml")).client.caps(indexer())
        assertFalse(caps.supports(SearchType.Movie))
        assertTrue(caps.supports(SearchType.Tv, "tmdbid"))
    }

    @Test
    fun `newznab search items`() = runBlocking<Unit> {
        val page = answering(fixture("newznab-search.xml")).client.search(indexer(), SearchQuery.Text("harbor lights"))
        assertEquals(2, page.total)
        val first = page.items.first()
        assertEquals("a1b2c3d4", first.guid)
        assertEquals("Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTGRP", first.title)
        assertEquals(2_147_483_648, first.sizeBytes)
        assertEquals(117, first.grabs)
        assertEquals(43, first.files)
        assertEquals(398712, first.tvdbId)
        assertEquals(1 to 2, first.season to first.episode)
        assertEquals(listOf(5000, 5040), first.categories)
        assertEquals(Instant.parse("2026-10-11T17:55:12Z"), first.postedAt)
        assertFalse(first.passworded)
        assertNull(first.mediaInfo)
        assertEquals("https://indexer.example.org/getnzb/a1b2c3d4.nzb&i=42&r={apikey}", first.downloadTemplate)
        assertTrue(page.items[1].passworded)
    }

    @Test
    fun `nntmux search reports api limits and media info`() = runBlocking<Unit> {
        val page = answering(fixture("nntmux-search.xml")).client.search(indexer(), SearchQuery.Movie(imdb = "tt1234567"))
        assertEquals(ApiLimits(142, 1000, 7, 100), page.limits)
        val item = page.items.single()
        assertEquals("tt1234567", item.imdbId)
        assertEquals(555001, item.tmdbId)
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.DtsHdMa), item.mediaInfo?.audio)
        assertEquals(setOf("de", "en"), item.mediaInfo?.languages)
        assertEquals(1080, item.mediaInfo?.resolution)
        assertEquals(Instant.parse("2026-10-05T20:01:00Z"), item.postedAt)
    }

    @Test
    fun `imdb ids keep ascii digits whatever the device locale`() = runBlocking<Unit> {
        // The id is compared with TMDB's, so a localized "tt١٢٣٤٥٦٧" would reject the show's own releases.
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        try {
            val item = answering(fixture("nntmux-search.xml")).client.search(indexer(), SearchQuery.Movie(imdb = "tt1234567")).items.single()
            assertEquals("tt1234567", item.imdbId)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `prowlarr search uses the guid url and redacts the key in its download link`() = runBlocking<Unit> {
        val item = answering(fixture("prowlarr-search.xml")).client.search(indexer(), SearchQuery.Text("orbit")).items.single()
        assertEquals("9988776655", item.guid)
        assertEquals(1_610_612_736, item.sizeBytes)
        assertEquals("tt7654321", item.imdbId)
        assertEquals(65432, item.tvmazeId)
        assertEquals(listOf(5000, 100040), item.categories)
        assertEquals(
            "http://prowlarr.local:9696/3/download?apikey={apikey}&link=Q2hhbmdlTWU&file=The+Quiet+Orbit+S02E10",
            item.downloadTemplate,
        )
    }

    /** L161: a torrent feed added as a Newznab indexer is searched, but nothing of it reaches the NZB path. */
    @Test
    fun `a newznab search of a torrent feed returns no item, and counts what it left out`() = runBlocking<Unit> {
        val page = answering(fixture("torznab-jackett.xml")).client.search(indexer(), SearchQuery.Text("orbit"))
        assertEquals(emptyList(), page.items)
        assertEquals(mapOf(LeftOut.NotNzb to 8), page.leftOut)
    }

    @Test
    fun `a torznab search keeps the torrents with a hash and counts the rest`() = runBlocking<Unit> {
        val torznab = indexer().copy(kind = IndexerKind.Torznab)
        val page = answering(fixture("torznab-jackett.xml")).client.search(torznab, SearchQuery.Text("orbit"))
        assertEquals(6, page.items.size)
        assertTrue(page.items.all { it.infoHash != null })
        assertEquals(mapOf(LeftOut.NoHash to 2), page.leftOut)
        // In the feed's order, for the torrent indexer's Test to count its first ones (L238): the two last were left out.
        assertEquals(List(6) { null } + listOf(LeftOut.NoHash, LeftOut.NoHash), page.feed)
        val nzbs = answering(fixture("newznab-search.xml")).client.search(torznab, SearchQuery.Text("harbor"))
        assertEquals(emptyList(), nzbs.items)
        assertEquals(mapOf(LeftOut.Nzb to 1, LeftOut.NoHash to 1), nzbs.leftOut, "the second NZB has no enclosure to tell")
    }

    /** L168, L238: the indexer's log line counts what it left out, by why, of the page's results; one that left nothing out has none. */
    @Test
    fun `what a search left out is logged by the indexer's name, with its count of the page's results and why`() {
        val page = SearchPage(emptyList(), 0, null, null, leftOut = mapOf(LeftOut.NoHash to 2, LeftOut.Nzb to 1))
        assertEquals("Left out 3 of nz's 3 results: 1 NZB, 2 without an info hash", NewznabClient.leftOutLine(indexer(), page))
        assertEquals(
            "Left out 8 of nz's 8 results: 8 not NZBs",
            NewznabClient.leftOutLine(indexer(), page.copy(leftOut = mapOf(LeftOut.NotNzb to 8))),
        )
        assertEquals("Left out 1 of nz's 1 result: 1 not an NZB", NewznabClient.leftOutLine(indexer(), page.copy(leftOut = mapOf(LeftOut.NotNzb to 1))))
        assertNull(NewznabClient.leftOutLine(indexer(), page.copy(leftOut = emptyMap())))
    }

    /** L238: the total is the page's, the items kept and those left out, so the line says how much of an answer went. */
    @Test
    fun `the left-out line's total is the page's results, kept and left out`() = runBlocking<Unit> {
        val torznab = indexer().copy(name = "Home Prowlarr", kind = IndexerKind.Torznab)
        val page = answering(fixture("torznab-jackett.xml")).client.search(torznab, SearchQuery.Text("orbit"))
        assertEquals("Left out 2 of Home Prowlarr's 8 results: 2 without an info hash", NewznabClient.leftOutLine(torznab, page))
        val kept = page.items.first()
        val forty = page.copy(items = List(37) { kept }, leftOut = mapOf(LeftOut.NoHash to 3))
        assertEquals("Left out 3 of Home Prowlarr's 40 results: 3 without an info hash", NewznabClient.leftOutLine(torznab, forty))
    }

    @Test
    fun `guid urls with the id in their query keep one guid per item`() = runBlocking<Unit> {
        // Prowlarr never sends a guid attr; it passes the upstream <guid> through.
        val items = answering(fixture("prowlarr-query-guids.xml")).client.search(indexer(), SearchQuery.Text("orbit")).items
        assertEquals(
            listOf(
                "3f2b9c0d1e4a5b6c7d8e9f0a1b2c3d4e", // details.php?guid=
                "a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4",
                "42", // details.php?cat=5040&id=42
                "9988776655", // a details path keeps its last segment
                "https://other.example/view.php?release=77&r={apikey}", // no id parameter: the whole url, key redacted
                "b7e1f0c2", // not a url
            ),
            items.map { it.guid },
        )
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        items.forEach { fake.client.nzb(indexer(), it.guid, downloadTemplate = null) }
        assertEquals(items.map { it.guid }, fake.requests.map { it.url.parameters["id"] })
    }

    @Test
    fun `request urls`() = runBlocking<Unit> {
        val fake = answering(fixture("newznab-search.xml"))
        fake.client.search(
            indexer(),
            SearchQuery.Tv(ids = TvIds(tvdb = 398712, imdb = "tt0012345"), season = 1, episode = 2, categories = listOf(5000, 5040)),
        )
        val url = fake.requests.single().url
        assertEquals("/api", url.encodedPath)
        assertEquals(
            mapOf(
                "cat" to "5000,5040", "t" to "tvsearch", "tvdbid" to "398712", "imdbid" to "0012345", "season" to "1",
                "ep" to "2", "extended" to "1", "offset" to "0", "apikey" to KEY,
            ),
            url.parameters.entries().associate { it.key to it.value.single() },
        )
    }

    @Test
    fun `a daily episode is asked by air date as the year and month slash day, in ascii digits`() = runBlocking<Unit> {
        val fake = answering(fixture("newznab-search.xml"))
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        try {
            fake.client.search(indexer(), SearchQuery.Tv(ids = TvIds(tvdb = 398712), airDate = LocalDate.of(2026, 3, 7)))
        } finally {
            Locale.setDefault(saved)
        }
        val params = fake.requests.single().url.parameters
        assertEquals(listOf("398712", "2026", "03/07"), listOf(params["tvdbid"], params["season"], params["ep"]))
    }

    @Test
    fun `base url with api path or trailing slash`() {
        listOf("https://x.example/api", "https://x.example/api/", "https://x.example", "https://x.example/").forEach { base ->
            val url = NewznabClient.apiUrl(IndexerConfig("i", "i", base, KEY), mapOf("t" to "caps"))
            assertTrue(url.startsWith("https://x.example/api?t=caps"), "$base -> $url")
        }
        assertEquals(
            "https://x.example/prowlarr/3/api?t=caps&apikey=$KEY",
            NewznabClient.apiUrl(IndexerConfig("i", "i", "https://x.example/prowlarr/3", KEY), mapOf("t" to "caps")),
        )
    }

    @Test
    fun `newznab error documents map to errors`() = runBlocking<Unit> {
        assertIs<NewznabError.ApiLimitReached>(
            assertFailsWith<NewznabException> { answering(fixture("error-limit.xml")).client.caps(indexer()) }.error,
        )
        assertIs<NewznabError.Auth>(
            assertFailsWith<NewznabException> { answering(fixture("error-auth.xml")).client.search(indexer(), SearchQuery.Text("x")) }.error,
        )
    }

    @Test
    fun `http statuses map to errors`() = runBlocking<Unit> {
        mapOf(
            HttpStatusCode.Unauthorized to NewznabError.Auth::class,
            HttpStatusCode.TooManyRequests to NewznabError.ApiLimitReached::class,
            HttpStatusCode.BadGateway to NewznabError.Server::class,
        ).forEach { (status, expected) ->
            val error = assertFailsWith<NewznabException> { answering("nope", status).client.caps(indexer()) }.error
            assertEquals(expected, error::class, "HTTP $status")
        }
        // Some indexers send the error document with a non-200 status.
        val error = assertFailsWith<NewznabException> { answering(fixture("error-auth.xml"), HttpStatusCode.Forbidden).client.caps(indexer()) }.error
        assertEquals("Incorrect user credentials", error.message)
    }

    /**
     * L169: a 429 reads as the API limit whatever error document comes with it: Jackett sends its code 900, which as a
     * document alone reads as a server error. Its Retry-After, in seconds or as a date, goes with it.
     */
    @Test
    fun `a 429 is the API limit whatever document comes with it, with its Retry-After`() = runBlocking<Unit> {
        val jackett = FakeIndexers {
            respond(
                """<?xml version="1.0" encoding="UTF-8"?><error code="900" description="Request limit reached for this indexer"/>""",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.RetryAfter to listOf("30")),
            )
        }
        val error = assertFailsWith<NewznabException> { jackett.client.search(indexer().copy(kind = IndexerKind.Torznab), SearchQuery.Text("orbit")) }.error
        val limit = assertIs<NewznabError.ApiLimitReached>(error)
        assertEquals("API limit reached", limit.message)
        assertEquals(Duration.ofSeconds(30), limit.retryAfter)
        // An auth document with a 429 is the limit too, and a 429 with none says the same words.
        assertIs<NewznabError.ApiLimitReached>(assertFailsWith<NewznabException> { answering(fixture("error-auth.xml"), HttpStatusCode.TooManyRequests).client.caps(indexer()) }.error)
        val bare = assertIs<NewznabError.ApiLimitReached>(assertFailsWith<NewznabException> { answering("nope", HttpStatusCode.TooManyRequests).client.caps(indexer()) }.error)
        assertEquals("API limit reached", bare.message)
        assertNull(bare.retryAfter)
        // The document still speaks where the status isn't 429.
        assertEquals("Incorrect user credentials", assertFailsWith<NewznabException> { answering(fixture("error-auth.xml"), HttpStatusCode.Forbidden).client.caps(indexer()) }.error.message)
    }

    @Test
    fun `Retry-After reads as seconds or as a date, and nothing else`() {
        val now = Instant.parse("2026-11-05T12:00:00Z")
        assertEquals(Duration.ofSeconds(30), NewznabClient.retryAfter("30", now))
        assertEquals(Duration.ofSeconds(90), NewznabClient.retryAfter(" 90 ", now))
        assertEquals(Duration.ofMinutes(20), NewznabClient.retryAfter("Thu, 05 Nov 2026 12:20:00 GMT", now))
        listOf(null, "", "soon", "-5", "0", "Thu, 05 Nov 2026 11:00:00 GMT", "99999999999999999999").forEach {
            assertNull(NewznabClient.retryAfter(it, now), "Retry-After: $it")
        }
    }

    /** A wait no indexer means (a broken or hostile one's) is a day at most: a limit's wait is kept as a time, which has an end. */
    @Test
    fun `a Retry-After past a day is a day`() {
        val now = Instant.parse("2026-11-05T12:00:00Z")
        assertEquals(Duration.ofHours(12), NewznabClient.retryAfter("43200", now))
        listOf("86401", "99999999999999999", Long.MAX_VALUE.toString(), "Fri, 31 Dec 9999 23:59:59 GMT").forEach {
            assertEquals(Duration.ofDays(1), NewznabClient.retryAfter(it, now), "Retry-After: $it")
        }
    }

    /**
     * L170: a request's own timeout is its indexer's kind's, caps and searches alike: 25 s for a torrent indexer, which
     * would otherwise be cut at 15 s whatever the search allowed it, and 15 s for a Usenet one, as before.
     */
    @Test
    fun `each request is given its indexer's kind's time`() = runBlocking<Unit> {
        val fake = FakeIndexers { request ->
            xml(fixture(if (request.url.parameters["t"] == "caps") "newznab-caps.xml" else "newznab-search.xml"))
        }
        val torznab = indexer("tz", "torrents.example").copy(kind = IndexerKind.Torznab)
        val newznab = indexer("nz", "usenet.example")
        listOf(torznab, newznab).forEach { indexer ->
            fake.client.caps(indexer)
            fake.client.search(indexer, SearchQuery.Text("orbit"))
        }
        fun timeouts(host: String) = fake.requests.filter { it.url.host == host }
            .map { it.url.parameters["t"] to it.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis }
        assertEquals(listOf("caps" to 25_000L, "search" to 25_000L), timeouts("torrents.example"))
        assertEquals(listOf("caps" to 15_000L, "search" to 15_000L), timeouts("usenet.example"))
    }

    /**
     * L170: a caps call or search may be silent its kind's whole time: the engine's own read timeout (OkHttp's 10 s)
     * would otherwise end it first, as Jackett and Prowlarr send nothing until their trackers have answered. A grab keeps
     * the engine's.
     */
    @Test
    fun `each caps call and search may be silent its kind's whole time`() = runBlocking<Unit> {
        val fake = FakeIndexers { request ->
            if (request.url.parameters["t"] == "get") respond(fixture("sample.nzb"), HttpStatusCode.OK)
            else xml(fixture(if (request.url.parameters["t"] == "caps") "newznab-caps.xml" else "newznab-search.xml"))
        }
        val torznab = indexer("tz", "torrents.example").copy(kind = IndexerKind.Torznab)
        val newznab = indexer("nz", "usenet.example")
        listOf(torznab, newznab).forEach { indexer ->
            fake.client.caps(indexer)
            fake.client.search(indexer, SearchQuery.Text("orbit"))
        }
        fake.client.nzb(newznab, "g", null)
        fun silences(host: String) = fake.requests.filter { it.url.host == host }
            .map { it.url.parameters["t"] to it.getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis }
        assertEquals(listOf("caps" to 25_000L, "search" to 25_000L), silences("torrents.example"))
        assertEquals(listOf("caps" to 15_000L, "search" to 15_000L, "get" to null), silences("usenet.example"))
    }

    /**
     * The same over the app's own engine: a torrent indexer silent past the engine's read timeout (here 1 s, OkHttp's
     * own 10 s) still answers within its 25 s; one silent past its own time is out in today's words, never as a network
     * error, whichever of the two times ends it.
     */
    @Test
    fun `over okhttp a torrent indexer silent past the engine's read timeout still answers`() = runBlocking<Unit> {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine { config { readTimeout(1, java.util.concurrent.TimeUnit.SECONDS) } }
            install(io.ktor.client.plugins.HttpTimeout)
            expectSuccess = false
        }
        SilentIndexer(holdMs = 1_500, body = fixture("newznab-caps.xml")).use { silent ->
            val torznab = IndexerConfig("tz", "tz", "http://127.0.0.1:${silent.port}", KEY, kind = IndexerKind.Torznab)
            assertEquals("Example Newznab", NewznabClient(http).caps(torznab).serverTitle)
            val error = assertFailsWith<NewznabException> { NewznabClient(http, torznabTimeoutMs = 1_000).caps(torznab) }.error
            assertEquals("No answer within 1 s", assertIs<NewznabError.Timeout>(error).message)
        }
        http.close()
    }

    /** A loopback indexer that reads each request and answers [body] only after [holdMs] of silence. */
    private class SilentIndexer(holdMs: Long, body: String) : AutoCloseable {
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread {
                        socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            Thread.sleep(holdMs)
                            val bytes = body.encodeToByteArray()
                            runCatching {
                                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + bytes)
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = server.close()
    }

    @Test
    fun `html instead of xml is a bad response`() = runBlocking<Unit> {
        val error = assertFailsWith<NewznabException> { answering("<html><body>Cloudflare</body>").client.caps(indexer()) }.error
        assertIs<NewznabError.BadResponse>(error)
    }

    @Test
    fun `slow indexer times out`() = runBlocking<Unit> {
        val fake = FakeIndexers { delay(5_000); xml(fixture("newznab-caps.xml")) }
        val client = NewznabClient(fake.http, requestTimeoutMs = 100)
        assertIs<NewznabError.Timeout>(assertFailsWith<NewznabException> { client.caps(indexer()) }.error)
    }

    /**
     * A caller's timeout that runs out while the request is out (MultiIndexerSearch's, per indexer) is the caller's
     * cancellation, not the indexer's failure: it goes on up, for the caller's withTimeoutOrNull to end. Ktor passes it
     * on as the caller's TimeoutCancellationException since 3.5 (KTOR-9183), as a plain CancellationException before.
     */
    @Test
    fun `a caller's timeout while the request is out goes on up as the caller's`() = runBlocking<Unit> {
        val fake = FakeIndexers { awaitCancellation() }
        assertFailsWith<TimeoutCancellationException> { withTimeout(200) { fake.client.caps(indexer()) } }
    }

    @Test
    fun `network errors never carry the api key`() = runBlocking<Unit> {
        val fake = FakeIndexers { throw IOException("connect failed for https://indexer.example.org/api?apikey=$KEY") }
        val e = assertFailsWith<NewznabException> { fake.client.caps(indexer()) }
        assertIs<NewznabError.Network>(e.error)
        assertFalse(KEY in e.message.orEmpty())
        assertFalse(KEY in e.error.message)
    }

    @Test
    fun `an address the url parser rejects is a newznab error`() = runBlocking<Unit> {
        val fake = answering(fixture("newznab-caps.xml"))
        // java.net.URI accepts the five-digit port; Ktor's Url() does not.
        val badPort = IndexerConfig("i", "i", "http://indexer.example.org:96960/api", KEY)
        listOf<suspend () -> Unit>(
            { fake.client.caps(badPort) },
            { fake.client.search(badPort, SearchQuery.Text("x")) },
            { fake.client.nzb(badPort, "g", downloadTemplate = null) },
        ).forEach { call ->
            val e = assertFailsWith<NewznabException> { call() }
            assertIs<NewznabError.BadResponse>(e.error)
            assertEquals("Invalid indexer address", e.error.message)
            assertFalse(KEY in e.message.orEmpty())
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a download link the url parser rejects is a newznab error without the key`() = runBlocking<Unit> {
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        val link = "https://indexer.example.org:96960/getnzb/g.nzb?r=${NewznabItem.API_KEY_PLACEHOLDER}"
        val e = assertFailsWith<NewznabException> { fake.client.nzb(indexer(), "g", link) }
        assertFalse(KEY in e.message.orEmpty())
        assertFalse(KEY in e.error.message)
    }

    @Test
    fun `non-io failures from the http stack are network errors without the key`() = runBlocking<Unit> {
        val rejecting = FakeIndexers { request -> throw IllegalArgumentException("unexpected url ${request.url}") }
        val e = assertFailsWith<NewznabException> { rejecting.client.caps(indexer()) }
        assertIs<NewznabError.Network>(e.error)
        assertFalse(KEY in e.message.orEmpty())
        assertFalse(KEY in e.error.message)
    }

    @Test
    fun `a redirect loop is a network error`() = runBlocking<Unit> {
        // Ktor gives up with SendCountExceedException, an IllegalStateException.
        val looping = FakeIndexers { request -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, request.url.toString())) }
        val loop = assertFailsWith<NewznabException> { looping.client.search(indexer(), SearchQuery.Text("x")) }
        assertIs<NewznabError.Network>(loop.error)
        assertFalse(KEY in loop.error.message)
    }

    @Test
    fun `nzb download puts the key back into the stored link`() = runBlocking<Unit> {
        val nzb = fixture("sample.nzb")
        val fake = FakeIndexers { xml(nzb) }
        val item = answering(fixture("newznab-search.xml")).client.search(indexer(), SearchQuery.Text("x")).items.first()
        val bytes = fake.client.nzb(indexer(), item)
        assertContentEquals(nzb.encodeToByteArray(), bytes)
        assertEquals("https://indexer.example.org/getnzb/a1b2c3d4.nzb&i=42&r=$KEY", fake.requests.single().url.toString())
    }

    /** A download answered with the NZB and [headers], as indexers answer one (names and passwords invented). */
    private fun downloading(vararg headers: Pair<String, String>) = FakeIndexers {
        respond(fixture("sample.nzb"), HttpStatusCode.OK, headersOf(*(listOf(HttpHeaders.ContentType to "application/x-nzb") + headers).map { (k, v) -> k to listOf(v) }.toTypedArray()))
    }

    @Test
    fun `a download carries the password its indexer sends in the X-DNZB-Password header`() = runBlocking<Unit> {
        val fake = downloading(
            "X-DNZB-Name" to "Harbor.Lights.S01E01.1080p.WEB-DL.H.264-GRP",
            "X-DNZB-Password" to "lantern-42",
            "Content-Disposition" to "attachment; filename=\"Harbor.Lights.S01E01.1080p.WEB-DL.H.264-GRP{{other}}.nzb\"",
        )
        val download = fake.client.download(indexer(), "a1b2c3d4", downloadTemplate = null)
        assertContentEquals(fixture("sample.nzb").encodeToByteArray(), download.bytes)
        // The header wins over a password in the file name.
        assertEquals("lantern-42", download.password)
    }

    /** As SABnzbd reads them: "name{{password}}.nzb" or "name password=password.nzb". */
    @Test
    fun `without the header, the password is read from the NZB's file name as SABnzbd reads it`() = runBlocking<Unit> {
        val named = mapOf(
            "attachment; filename=\"Harbor.Lights.S01E01.1080p.WEB-DL.H.264-GRP{{salt and signal}}.nzb\"" to "salt and signal",
            "attachment; filename=Copper.Crown.2019.1080p.BluRay.x264-GRP{{c0pper}}.nzb" to "c0pper",
            "attachment; filename=\"Copper.Crown.2019.1080p.BluRay.x264-GRP password=c0pper.nzb\"" to "c0pper",
            "attachment; filename*=UTF-8''Harbor.Lights.S01E01.1080p.WEB-DL.H.264-GRP%7B%7Bm%C3%B8we%7D%7D.nzb" to "møwe",
            "attachment; filename=\"Harbor.Lights.S01E01.1080p.WEB-DL.H.264-GRP.nzb\"" to null,
            "inline" to null,
        )
        named.forEach { (disposition, password) ->
            assertEquals(password, downloading("Content-Disposition" to disposition).client.download(indexer(), "g", null).password, disposition)
        }
        assertNull(downloading().client.download(indexer(), "g", null).password)
        // An empty header says nothing.
        assertNull(downloading("X-DNZB-Password" to " ").client.download(indexer(), "g", null).password)
    }

    @Test
    fun `nzb download without a link uses t=get`() = runBlocking<Unit> {
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        fake.client.nzb(indexer(), guid = "a1b2c3d4", downloadTemplate = null)
        val url = fake.requests.single().url
        assertEquals("get", url.parameters["t"])
        assertEquals("a1b2c3d4", url.parameters["id"])
    }

    @Test
    fun `an http link on the https indexer's host is fetched over https`() = runBlocking<Unit> {
        // Sites behind a TLS proxy often still build their links with http.
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        val link = "http://indexer.example.org/getnzb/abc.nzb&r=${NewznabItem.API_KEY_PLACEHOLDER}"
        fake.client.nzb(indexer(), "abc", link)
        val ported = IndexerConfig("i", "i", "https://Indexer.example.org:8443/api", KEY)
        fake.client.nzb(ported, "abc", "http://indexer.example.org:8080/getnzb/abc.nzb?r=${NewznabItem.API_KEY_PLACEHOLDER}")
        assertEquals(
            listOf("https://indexer.example.org/getnzb/abc.nzb&r=$KEY", "https://indexer.example.org:8443/getnzb/abc.nzb?r=$KEY"),
            fake.requests.map { it.url.toString() },
        )
    }

    @Test
    fun `an http link on another host never gets the https indexer's key`() = runBlocking<Unit> {
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        fake.client.nzb(indexer(), "abc", "http://downloads.example.net/getnzb/abc.nzb?r=${NewznabItem.API_KEY_PLACEHOLDER}")
        val url = fake.requests.single().url
        assertEquals("https://indexer.example.org/api", "${url.protocol.name}://${url.host}${url.encodedPath}")
        assertEquals(listOf("get", "abc", KEY), listOf(url.parameters["t"], url.parameters["id"], url.parameters["apikey"]))
    }

    @Test
    fun `links at least as secure as the indexer address are used as they are`() = runBlocking<Unit> {
        val fake = FakeIndexers { xml(fixture("sample.nzb")) }
        // An https proxy (Prowlarr, NZBHydra) links to its own host, which may differ from the address typed.
        fake.client.nzb(indexer(), "g", "https://hydra.example.net/getnzb/api/g?apikey=${NewznabItem.API_KEY_PLACEHOLDER}")
        // An indexer configured with http gains nothing from rewriting its links.
        val local = IndexerConfig("p", "p", "http://prowlarr.local:9696/3", KEY)
        fake.client.nzb(local, "g", "http://prowlarr.local:9696/3/download?apikey=${NewznabItem.API_KEY_PLACEHOLDER}&link=Q2hhbmdlTWU")
        assertEquals(
            listOf("https://hydra.example.net/getnzb/api/g?apikey=$KEY", "http://prowlarr.local:9696/3/download?apikey=$KEY&link=Q2hhbmdlTWU"),
            fake.requests.map { it.url.toString() },
        )
    }

    @Test
    fun `download limit answer instead of an nzb`() = runBlocking<Unit> {
        val fake = FakeIndexers { xml("""<?xml version="1.0"?><error code="501" description="Download limit reached"/>""") }
        val error = assertFailsWith<NewznabException> { fake.client.nzb(indexer(), "g", null) }.error
        assertIs<NewznabError.DownloadLimitReached>(error)
    }

    /**
     * NNTmux answers a used-up grab allowance with HTTP 429 and its code 501: still the download limit, which keeps the
     * indexer's other releases from being grabbed for nothing, not the API limit L169 makes of other 429s.
     */
    @Test
    fun `a used-up grab allowance answered with a 429 is still the download limit`() = runBlocking<Unit> {
        val fake = FakeIndexers {
            respond(
                """<?xml version="1.0" encoding="UTF-8"?><error code="501" description="Download limit reached"/>""",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.RetryAfter to listOf("3600")),
            )
        }
        val error = assertFailsWith<NewznabException> { fake.client.nzb(indexer(), "g", null) }.error
        assertEquals("Download limit reached", assertIs<NewznabError.DownloadLimitReached>(error).message)
    }

    @Test
    fun `an nzb download announced as larger than the cap fails without reading the body`() = runBlocking<Unit> {
        // The body never arrives: reading it would wait until the guard gives up.
        val fake = FakeIndexers { respond(ByteChannel(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1025")) }
        val client = NewznabClient(fake.http, maxNzbBytes = 1024)
        val error = withTimeout(5_000) { assertFailsWith<NewznabException> { client.nzb(indexer(), "g", null) } }.error
        assertIs<NewznabError.BadResponse>(error)
        assertEquals("The download is too large to be an NZB", error.message)
    }

    @Test
    fun `an nzb download without a length stops reading once past the cap`() = runBlocking<Unit> {
        // Starts like an NZB, then goes on for 64 MB.
        val body = GeneratedBody(64L * 1024 * 1024, head = "<nzb ")
        val fake = FakeIndexers { respond(body.toByteReadChannel(), HttpStatusCode.OK) }
        val client = NewznabClient(fake.http, maxNzbBytes = 1024 * 1024)
        val error = assertFailsWith<NewznabException> { client.nzb(indexer(), "g", null) }.error
        assertEquals("The download is too large to be an NZB", error.message)
        // The cap, plus what the channel buffers ahead of the reader.
        assertTrue(body.served.get() < 3 * 1024 * 1024, "served ${body.served.get()} bytes")
    }

    @Test
    fun `an nzb download of exactly the cap is accepted and one byte more is not`() = runBlocking<Unit> {
        val nzb = fixture("sample.nzb").encodeToByteArray()
        val fake = FakeIndexers { request ->
            when (request.url.parameters["id"]) {
                "announced" -> respond(nzb, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, nzb.size.toString()))
                "streamed" -> respond(ByteReadChannel(nzb), HttpStatusCode.OK)
                else -> respond(ByteReadChannel(nzb + '\n'.code.toByte()), HttpStatusCode.OK)
            }
        }
        val client = NewznabClient(fake.http, maxNzbBytes = nzb.size.toLong())
        assertContentEquals(nzb, client.nzb(indexer(), "announced", null))
        assertContentEquals(nzb, client.nzb(indexer(), "streamed", null))
        val error = assertFailsWith<NewznabException> { client.nzb(indexer(), "longer", null) }.error
        assertEquals("The download is too large to be an NZB", error.message)
    }

    @Test
    fun `search and caps answers larger than their cap are bad responses`() = runBlocking<Unit> {
        val search = fixture("newznab-search.xml").encodeToByteArray()
        val fake = FakeIndexers { respond(ByteReadChannel(search), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml")) }
        val client = NewznabClient(fake.http, maxAnswerBytes = search.size - 1L)
        listOf<suspend () -> Unit>({ client.search(indexer(), SearchQuery.Text("x")) }, { client.caps(indexer()) }).forEach { call ->
            val error = assertFailsWith<NewznabException> { call() }.error
            assertIs<NewznabError.BadResponse>(error)
            assertEquals("The answer is too large", error.message)
        }
        assertEquals(2, NewznabClient(fake.http, maxAnswerBytes = search.size.toLong()).search(indexer(), SearchQuery.Text("x")).total)
    }

    @Test
    fun `answers are decoded and parsed off the calling thread`() {
        val fake = FakeIndexers { request ->
            when {
                request.url.host == "denied.example" -> xml(fixture("error-auth.xml"), HttpStatusCode.Forbidden)
                request.url.parameters["t"] == "caps" -> xml(fixture("newznab-caps.xml"))
                else -> xml(fixture("newznab-search.xml"))
            }
        }
        // The thread each answer's body is read and decoded on.
        val decodedOn = ConcurrentLinkedQueue<String>()
        fake.http.responsePipeline.intercept(HttpResponsePipeline.Receive) { decodedOn += Thread.currentThread().name.substringBefore(" @") }
        RecordingDispatcher("parsing").use { parsing ->
            val client = NewznabClient(fake.http, defaultDispatcher = parsing)
            onFakeMain {
                assertEquals("Example Newznab", client.caps(indexer()).serverTitle)
                assertEquals(2, client.search(indexer(), SearchQuery.Text("harbor lights")).items.size)
                assertIs<NewznabError.Auth>(assertFailsWith<NewznabException> { client.caps(indexer(host = "denied.example")) }.error)
            }
            assertEquals(listOf("parsing", "parsing", "parsing"), decodedOn.toList())
        }
    }

    @Test
    fun `an error document instead of an nzb is parsed off the calling thread`() {
        val fake = FakeIndexers { xml("""<?xml version="1.0"?><error code="501" description="Download limit reached"/>""") }
        RecordingDispatcher("parsing").use { parsing ->
            val client = NewznabClient(fake.http, defaultDispatcher = parsing)
            val error = onFakeMain { assertFailsWith<NewznabException> { client.nzb(indexer(), "g", null) }.error }
            assertIs<NewznabError.DownloadLimitReached>(error)
            assertTrue(parsing.dispatches.get() > 0, "the error document was parsed on the caller's thread")
        }
    }

    @Test
    fun `api keys are redacted both raw and url-encoded`() {
        assertEquals(
            "https://x.example/getnzb?r={apikey}&raw={apikey}",
            NewznabXml.redactApiKey("https://x.example/getnzb?r=ab%2Bc%2Fd%3De+f&raw=ab+c/d=e f", "ab+c/d=e f"),
        )
    }

    @Test
    fun `indexer config never prints its key`() {
        assertFalse(KEY in indexer().toString())
    }

    @Test
    fun `a request is logged by the indexer's name, without its address or key`() {
        assertEquals(
            "GET /api?t=search&q=harbor&apikey={apikey} from nz",
            NewznabClient.requestLine(indexer(), "https://indexer.example.org/api?t=search&q=harbor&apikey=$KEY"),
        )
        assertEquals(
            "GET /getnzb/a1b2c3d4.nzb&i=42&r={apikey} from nz",
            NewznabClient.requestLine(indexer(), "https://indexer.example.org:8443/getnzb/a1b2c3d4.nzb&i=42&r=$KEY"),
        )
        assertEquals("GET / from nz", NewznabClient.requestLine(indexer(), "http://192.168.1.5:9696"))
    }

    /** [size] bytes starting with [head], made as they are read; [served] counts how many were. */
    private class GeneratedBody(private val size: Long, head: String) : InputStream() {
        private val head = head.encodeToByteArray()
        val served = AtomicLong()

        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val at = served.get()
            if (at >= size) return -1
            val n = minOf(len.toLong(), size - at).toInt()
            b.fill(' '.code.toByte(), off, off + n)
            if (at < head.size) head.copyInto(b, off, at.toInt(), minOf(head.size, at.toInt() + n))
            served.addAndGet(n.toLong())
            return n
        }
    }

    /** Runs [block] on a single thread named fake-main, standing in for Android's main thread. */
    private fun <T> onFakeMain(block: suspend CoroutineScope.() -> T): T =
        Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher().use { runBlocking(it, block) }

    /** Runs blocks on one thread named [name], counting how often it was asked to. */
    private class RecordingDispatcher(name: String) : CoroutineDispatcher(), AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, name) }
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            executor.execute(block)
        }

        override fun close() = executor.shutdown()
    }
}

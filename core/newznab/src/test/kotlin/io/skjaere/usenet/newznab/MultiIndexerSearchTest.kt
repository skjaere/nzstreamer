package io.skjaere.usenet.newznab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiIndexerSearchTest {

    private val good = indexer("good", "good.example")
    private val badKey = indexer("bad", "bad.example")
    private val slow = indexer("slow", "slow.example")
    private val noTvdb = indexer("prowlarr-like", "text.example")
    private val broken = indexer("broken", "broken.example")

    private val fake = FakeIndexers { request ->
        val host = request.url.host
        val function = request.url.parameters["t"]
        when {
            host == "slow.example" -> { delay(5_000); xml(fixture("newznab-search.xml")) }
            host == "bad.example" -> xml(fixture("error-auth.xml"), HttpStatusCode.OK)
            host == "broken.example" -> throw IllegalStateException("engine failed for ${request.url}")
            function == "caps" && host == "text.example" -> xml(
                """<caps><searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,season,ep"/></searching></caps>""",
            )
            function == "caps" -> xml(fixture("newznab-caps.xml"))
            else -> xml(fixture("newznab-search.xml"))
        }
    }

    // Tests that need the slow indexer to time out use 2 s: well below its 5 s delay, and enough
    // for the healthy fakes on a cold, busy JVM (300 ms made them time out under parallel builds).
    private fun search(timeoutMs: Long = 15_000) = MultiIndexerSearch(fake.client, CapsCache(fake.client), timeoutMs)

    /**
     * A cache that holds [good]'s caps already, for the tests with a 1 s timeout. A search's timeout covers its caps
     * too, and a cold, busy JVM can take longer than that over them: the search would then time out before its request
     * reached the indexer, and the test would wait for that request forever.
     */
    private suspend fun capsOfGood(indexers: FakeIndexers) = CapsCache(indexers.client).also { it.get(good) }

    /** By tvdb id where supported, else by name. */
    private val plan: (IndexerCaps) -> SearchQuery? = { caps ->
        when {
            caps.supports(SearchType.Tv, "tvdbid") -> SearchQuery.Tv(ids = TvIds(tvdb = 398712), season = 1, episode = 2)
            caps.supports(SearchType.Tv, "q") -> SearchQuery.Tv(query = "Harbor Lights", season = 1, episode = 2)
            else -> null
        }
    }

    @Test
    fun `each indexer reports its own outcome and failures stay isolated`() = runBlocking<Unit> {
        val outcomes = search(timeoutMs = 2_000).search(listOf(good, badKey, slow, noTvdb), plan = plan).toList().associateBy { it.indexer.id }

        assertEquals(2, assertIs<IndexerOutcome.Results>(outcomes.getValue("good")).page.items.size)
        assertIs<NewznabError.Auth>(assertIs<IndexerOutcome.Failed>(outcomes.getValue("bad")).error)
        assertIs<NewznabError.Timeout>(assertIs<IndexerOutcome.Failed>(outcomes.getValue("slow")).error)
        assertIs<IndexerOutcome.Results>(outcomes.getValue("prowlarr-like"))

        val textSearch = fake.requests.single { it.url.host == "text.example" && it.url.parameters["t"] == "tvsearch" }
        assertEquals("Harbor Lights", textSearch.url.parameters["q"])
        val idSearch = fake.requests.single { it.url.host == "good.example" && it.url.parameters["t"] == "tvsearch" }
        assertEquals("398712", idSearch.url.parameters["tvdbid"])
    }

    @Test
    fun `an indexer failing with a non-newznab exception fails alone`() = runBlocking<Unit> {
        val badPort = IndexerConfig("port", "port", "http://indexer.example.org:96960/api", KEY)
        val outcomes = search().search(listOf(good, broken, badPort), plan = plan).toList().associateBy { it.indexer.id }

        assertEquals(2, assertIs<IndexerOutcome.Results>(outcomes.getValue("good")).page.items.size)
        val error = assertIs<IndexerOutcome.Failed>(outcomes.getValue("broken")).error
        assertIs<NewznabError.Network>(error)
        assertFalse(KEY in error.message)
        assertIs<NewznabError.BadResponse>(assertIs<IndexerOutcome.Failed>(outcomes.getValue("port")).error)
    }

    @Test
    fun `a plan that throws for one indexer fails only that indexer`() = runBlocking<Unit> {
        val outcomes = search().search(listOf(good, noTvdb)) { caps ->
            check(caps.supports(SearchType.Tv, "tvdbid")) { "no plan for $KEY" }
            plan(caps)
        }.toList().associateBy { it.indexer.id }

        assertIs<IndexerOutcome.Results>(outcomes.getValue("good"))
        val error = assertIs<IndexerOutcome.Failed>(outcomes.getValue("prowlarr-like")).error
        assertFalse(KEY in error.message)
    }

    /** The collector stops at once; the request it leaves goes on (for the cache) no longer than the indexer's timeout. */
    @Test
    fun `cancelling the collector stops it at once, and the request it leaves ends at the timeout`() = runBlocking<Unit> {
        val searching = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val hanging = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") return@FakeIndexers xml(fixture("newznab-caps.xml"))
            searching.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                requestCancelled.complete(Unit)
            }
        }
        val received = mutableListOf<IndexerOutcome>()
        val multi = MultiIndexerSearch(hanging.client, capsOfGood(hanging), perIndexerTimeoutMs = 1_000)
        val collector = launch { multi.search(listOf(good), plan = plan).collect { received += it } }
        withTimeout(5_000) { searching.await() }
        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        assertEquals(emptyList(), received)
        withTimeout(5_000) { requestCancelled.await() }
    }

    /**
     * A search dropped while an indexer is still answering (typing on, Search indexers pressed over the preview's own
     * search): the indexer has counted the call already, so its answer goes on into the cache. The same search meanwhile
     * waits for that answer, and later ones take it from the cache: the indexer is asked once.
     */
    @Test
    fun `a search dropped while an indexer answers leaves its answer to the same search after it`() = runBlocking<Unit> {
        val searching = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val held = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") return@FakeIndexers xml(fixture("newznab-caps.xml"))
            searching.complete(Unit)
            answer.await()
            xml(fixture("newznab-search.xml"))
        }
        fun searches() = held.requests.count { it.url.parameters["t"] == "tvsearch" }
        val multi = MultiIndexerSearch(held.client, CapsCache(held.client))
        val dropped = launch { multi.search(listOf(good), plan = plan).collect() }
        searching.await()
        dropped.cancelAndJoin()

        val next = async { multi.search(listOf(good), plan = plan).toList() }
        // The same search under way while the first answer is still to come.
        delay(500)
        assertEquals(1, searches(), "the same search asked the indexer again while its first answer was on its way")
        answer.complete(Unit)
        assertEquals(2, assertIs<IndexerOutcome.Results>(withTimeout(5_000) { next.await() }.single()).page.items.size)
        multi.search(listOf(good), plan = plan).toList()
        assertEquals(1, searches(), "the same search asked the indexer again once its answer was in")
    }

    /**
     * A search that waits for a request already on its way (a dropped search's, of the same words) fails when that
     * request's own timeout runs out, with the indexer's timeout in its message: that is the timeout that ran out, not the
     * client's longer request timeout.
     */
    @Test
    fun `a search waiting for a request that times out fails with the indexer's timeout`() = runBlocking<Unit> {
        val searching = CompletableDeferred<Unit>()
        val hanging = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") return@FakeIndexers xml(fixture("newznab-caps.xml"))
            searching.complete(Unit)
            awaitCancellation()
        }
        val multi = MultiIndexerSearch(hanging.client, capsOfGood(hanging), perIndexerTimeoutMs = 1_000)
        val dropped = launch { multi.search(listOf(good), plan = plan).collect() }
        withTimeout(5_000) { searching.await() }
        dropped.cancelAndJoin()
        // Started well after the request, so the request's own timeout runs out first.
        delay(500)

        val outcome = multi.search(listOf(good), plan = plan).toList().single()
        val error = assertIs<NewznabError.Timeout>(assertIs<IndexerOutcome.Failed>(outcome).error)
        assertEquals("No answer within 1 s", error.message)
    }

    @Test
    fun `fast results arrive before slow ones`() = runBlocking<Unit> {
        val order = search(timeoutMs = 2_000).search(listOf(slow, good), plan = plan).toList().map { it.indexer.id }
        assertEquals(listOf("good", "slow"), order)
    }

    @Test
    fun `disabled indexers are not asked and unsupported searches are skipped`() = runBlocking<Unit> {
        val outcomes = search().search(listOf(good.copy(enabled = false), good.copy(id = "g2"))) { null }.toList()
        assertIs<IndexerOutcome.Skipped>(outcomes.single())
        assertEquals("g2", outcomes.single().indexer.id)
    }

    @Test
    fun `caps are cached until they expire`() = runBlocking<Unit> {
        var now = Instant.parse("2026-10-13T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val cache = CapsCache(fake.client, clock, Duration.ofHours(24))
        repeat(3) { cache.get(good) }
        now = now.plus(Duration.ofHours(25))
        cache.get(good)
        assertEquals(2, fake.requests.count { it.url.parameters["t"] == "caps" })
    }

    @Test
    fun `answers are reused for 15 minutes unless fresh is asked for`() = runBlocking<Unit> {
        var now = Instant.parse("2026-10-13T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client), clock = clock)
        fun searches() = fake.requests.count { it.url.parameters["t"] == "tvsearch" }
        repeat(2) { multi.search(listOf(good), plan = plan).toList() }
        assertEquals(1, searches())
        multi.search(listOf(good), fresh = true, plan = plan).toList()
        assertEquals(2, searches())
        now = now.plus(Duration.ofMinutes(16))
        multi.search(listOf(good), plan = plan).toList()
        assertEquals(3, searches())
        // Failures aren't cached: the bad indexer is asked again every time.
        repeat(2) { multi.search(listOf(badKey), plan = plan).toList() }
        assertEquals(2, fake.requests.count { it.url.host == "bad.example" })
    }

    /**
     * L553: an answer made with one User-Agent is never given for another, caps (kept 24 hours) and a search's answer (kept
     * 15 minutes) alike. The same value, padded or not, reuses both.
     */
    @Test
    fun `a changed User-Agent asks again for caps and the same search, and the same one reuses both`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-23T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client, clock), clock = clock)
        fun asked(function: String) = fake.requests.filter { it.url.parameters["t"] == function }
        suspend fun searchAs(userAgent: String) =
            assertIs<IndexerOutcome.Results>(multi.search(listOf(good.copy(userAgent = userAgent)), plan = plan).toList().single())

        searchAs("")
        searchAs("ExampleReader/2.1")
        assertEquals(2, asked("caps").size)
        assertEquals(2, asked("tvsearch").size)
        now = now.plus(Duration.ofMinutes(10))
        searchAs(" ExampleReader/2.1\t")
        searchAs("")
        assertEquals(2, asked("caps").size, "each value's caps were kept")
        assertEquals(2, asked("tvsearch").size, "each value's answer was kept")
        searchAs("QuarryFetch/1.0")
        assertEquals(listOf("NZStreamer/0.1", "ExampleReader/2.1", "QuarryFetch/1.0"), asked("caps").map { it.headers[HttpHeaders.UserAgent] })
        assertEquals(listOf("NZStreamer/0.1", "ExampleReader/2.1", "QuarryFetch/1.0"), asked("tvsearch").map { it.headers[HttpHeaders.UserAgent] })
    }

    /** L553: an API limit is the account's, its address's and key's, whatever asks: a new User-Agent doesn't end it. */
    @Test
    fun `an indexer kept out by its 429's Retry-After stays out whatever its User-Agent`() = runBlocking<Unit> {
        val fake = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") {
                xml(fixture("newznab-caps.xml"))
            } else {
                respond("nope", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
            }
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client))
        fun searches() = fake.requests.count { it.url.parameters["t"] == "tvsearch" }
        listOf("", "ExampleReader/2.1", "QuarryFetch/1.0", "").forEach { userAgent ->
            val held = assertIs<IndexerOutcome.Failed>(multi.search(listOf(good.copy(userAgent = userAgent)), plan = plan).toList().single())
            assertEquals(API_LIMIT_REACHED, assertIs<NewznabError.ApiLimitReached>(held.error).message)
        }
        assertEquals(1, searches(), "an indexer kept out was asked with another User-Agent")
        assertEquals(1, fake.requests.count { it.url.parameters["t"] == "caps" })
    }

    /** A Test asks for caps anew with the form's User-Agent: the caps kept for the indexer's other values stay. */
    @Test
    fun `invalidate drops the caps kept for its own User-Agent only`() = runBlocking<Unit> {
        val cache = CapsCache(fake.client)
        val reader = good.copy(userAgent = "ExampleReader/2.1")
        fun asked() = fake.requests.count { it.url.parameters["t"] == "caps" }
        cache.get(good)
        cache.get(reader)
        assertEquals(2, asked())
        cache.invalidate(reader)
        cache.get(good)
        assertEquals(2, asked(), "the caps kept for the client's own were dropped")
        cache.get(reader)
        assertEquals(3, asked())
        assertEquals("ExampleReader/2.1", fake.requests.last().headers[HttpHeaders.UserAgent])
    }

    /**
     * L170: a torrent indexer asks the trackers' sites, which are slow, so it is given 25 s where a Usenet one keeps
     * 15 s, in both of the search's bounds and the client's own request timeout. On the test's clock: each indexer is
     * silent for 20 s.
     */
    @Test
    fun `a torrent indexer silent for 20 s still answers, where a usenet one is out at 15 s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        if (request.url.parameters["t"] == "caps") {
                            xml(fixture("newznab-caps.xml"))
                        } else {
                            delay(20_000)
                            xml(fixture(if (request.url.host == "torrents.example") "torznab-prowlarr.xml" else "newznab-search.xml"))
                        }
                    }
                },
            ),
        ) {
            install(HttpTimeout)
            expectSuccess = false
        }
        val client = NewznabClient(http, defaultDispatcher = dispatcher)
        val usenet = indexer("usenet", "usenet.example")
        val torrents = indexer("torrents", "torrents.example").copy(kind = IndexerKind.Torznab)
        // Requests as the app's are: a failed one is its own search's failure, never its parent's. On the test's clock.
        val outcomes = MultiIndexerSearch(client, CapsCache(client), requests = CoroutineScope(SupervisorJob()))
            .search(listOf(usenet, torrents), plan = plan).toList().associateBy { it.indexer.id }

        val timedOut = assertIs<NewznabError.Timeout>(assertIs<IndexerOutcome.Failed>(outcomes.getValue("usenet")).error)
        assertEquals("No answer within 15 s", timedOut.message)
        assertTrue(assertIs<IndexerOutcome.Results>(outcomes.getValue("torrents")).page.items.isNotEmpty())
        assertEquals(20_000, testScheduler.currentTime, "the search ended as the torrent indexer answered")
        http.close()
    }

    @Test
    fun `a torrent indexer that never answers is out at 25 s, in today's words`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        if (request.url.parameters["t"] == "caps") xml(fixture("newznab-caps.xml")) else awaitCancellation()
                    }
                },
            ),
        ) {
            install(HttpTimeout)
            expectSuccess = false
        }
        val client = NewznabClient(http, defaultDispatcher = dispatcher)
        val torrents = indexer("torrents", "torrents.example").copy(kind = IndexerKind.Torznab)
        val outcome = MultiIndexerSearch(client, CapsCache(client), requests = CoroutineScope(SupervisorJob())).search(listOf(torrents), plan = plan).toList().single()
        assertEquals("No answer within 25 s", assertIs<NewznabError.Timeout>(assertIs<IndexerOutcome.Failed>(outcome).error).message)
        assertEquals(25_000, testScheduler.currentTime)
        http.close()
    }

    /** Caps with the Movies and TV parents (and children), and a tracker's own category, as Jackett and Prowlarr list them. */
    private val torrentCaps = """<caps><searching><search available="yes" supportedParams="q"/>""" +
        """<tv-search available="yes" supportedParams="q,season,ep"/><movie-search available="yes" supportedParams="q,imdbid"/></searching>""" +
        """<categories><category id="2000" name="Movies"><subcat id="2040" name="Movies/HD"/></category>""" +
        """<category id="5000" name="TV"><subcat id="5040" name="TV/HD"/></category><category id="100001" name="Site TV"/></categories></caps>"""

    /** Each search's `cat`, by host and function, from a fake whose caps are [caps] for every host. */
    private fun categoriesAsked(caps: String, indexers: List<IndexerConfig>, plan: (IndexerCaps) -> SearchQuery?): List<String?> = runBlocking {
        val fake = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") xml(caps) else xml(fixture("newznab-search.xml"))
        }
        MultiIndexerSearch(fake.client, CapsCache(fake.client)).search(indexers, plan = plan).toList()
        indexers.map { indexer -> fake.requests.single { it.url.host == indexer.baseUrl.removePrefix("https://") && it.url.parameters["t"] != "caps" }.url.parameters["cat"] }
    }

    /**
     * L167: a torrent indexer's search names the Movies (2000) and TV (5000) parents its own caps list: TV's for a TV
     * search, Movies' for a film's, both for words alone. Jackett runs an uncategorised search over every category a
     * site has. A tracker's own categories (100000 on) and the children are never added: the parents take them in.
     */
    @Test
    fun `a torrent indexer's search asks for the movies and tv its caps list`() {
        val torrents = indexer("torrents", "torrents.example").copy(kind = IndexerKind.Torznab)
        assertEquals(listOf("5000"), categoriesAsked(torrentCaps, listOf(torrents)) { SearchQuery.Tv(query = "Harbor Lights", season = 1, episode = 2) })
        assertEquals(listOf("2000"), categoriesAsked(torrentCaps, listOf(torrents)) { SearchQuery.Movie(query = "Copper Crown") })
        assertEquals(listOf("2000,5000"), categoriesAsked(torrentCaps, listOf(torrents)) { SearchQuery.Text("quiet orbit") })
    }

    /** No built-in catalogue (the caps rule): caps listing neither parent get no `cat`, as one without the search's own. */
    @Test
    fun `a torrent indexer whose caps list neither parent is asked without cat`() {
        val torrents = indexer("torrents", "torrents.example").copy(kind = IndexerKind.Torznab)
        val siteOnly = """<caps><searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q"/>""" +
            """</searching><categories><category id="100001" name="Site TV"/><category id="3000" name="Audio"/></categories></caps>"""
        assertEquals(listOf<String?>(null), categoriesAsked(siteOnly, listOf(torrents)) { SearchQuery.Tv(query = "Harbor Lights") })
        val moviesOnly = """<caps><searching><tv-search available="yes" supportedParams="q"/></searching>""" +
            """<categories><category id="2000" name="Movies"/></categories></caps>"""
        assertEquals(listOf<String?>(null), categoriesAsked(moviesOnly, listOf(torrents)) { SearchQuery.Tv(query = "Harbor Lights") })
    }

    @Test
    fun `a usenet indexer's search is asked as it was, without cat`() {
        val usenet = indexer("usenet", "usenet.example")
        assertEquals(listOf<String?>(null), categoriesAsked(torrentCaps, listOf(usenet)) { SearchQuery.Tv(query = "Harbor Lights", season = 1) })
        assertEquals(listOf<String?>(null), categoriesAsked(torrentCaps, listOf(usenet)) { SearchQuery.Text("quiet orbit") })
    }

    /**
     * L169: an indexer that answered 429 with a Retry-After (Jackett's code 900, Prowlarr's query limit) is kept out of
     * searches until it ends: each search meanwhile is answered "API limit reached" at once, without a request, Search
     * indexers' fresh one too. Once it ends the indexer is asked again.
     */
    @Test
    fun `an indexer that answered 429 with Retry-After is answered limited without a request until it ends`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-05T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        var limited = true
        val fake = FakeIndexers { request ->
            when {
                request.url.parameters["t"] == "caps" -> xml(fixture("newznab-caps.xml"))
                limited -> respond(
                    """<?xml version="1.0" encoding="UTF-8"?><error code="900" description="Request limit reached for this indexer"/>""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.RetryAfter to listOf("30")),
                )
                else -> xml(fixture("newznab-search.xml"))
            }
        }
        fun searches() = fake.requests.count { it.url.parameters["t"] == "tvsearch" }
        val torrents = indexer("torrents", "torrents.example").copy(kind = IndexerKind.Torznab)
        val other = indexer("other", "other.example")
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client), clock = clock)

        val first = assertIs<IndexerOutcome.Failed>(multi.search(listOf(torrents), plan = plan).toList().single())
        assertEquals("API limit reached", assertIs<NewznabError.ApiLimitReached>(first.error).message)
        assertEquals(1, searches())

        limited = false
        now = now.plusSeconds(29)
        listOf(false, true).forEach { fresh ->
            val held = assertIs<IndexerOutcome.Failed>(multi.search(listOf(torrents), fresh = fresh, plan = plan).toList().single())
            assertEquals("API limit reached", assertIs<NewznabError.ApiLimitReached>(held.error).message)
        }
        assertEquals(1, searches(), "an indexer kept out was asked")
        // Another indexer, of either kind, is asked as ever.
        assertIs<IndexerOutcome.Results>(multi.search(listOf(other), plan = plan).toList().single())

        now = now.plusSeconds(1)
        assertIs<IndexerOutcome.Results>(multi.search(listOf(torrents), plan = plan).toList().single())
        assertEquals(3, searches())
    }

    /**
     * Kept out means not asked: a search whose answer the indexer gave before its limit, still kept, is answered from it
     * without a request, as ever. Only what would need a request (a search not kept, Search indexers' fresh one, caps no
     * longer kept) is answered "API limit reached".
     */
    @Test
    fun `an indexer kept out still gives a search the answer it already had`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-05T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val fake = FakeIndexers { request ->
            when {
                request.url.parameters["t"] == "caps" -> xml(fixture("newznab-caps.xml"))
                request.url.parameters["ep"] == "2" -> xml(fixture("newznab-search.xml"))
                else -> respond("nope", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
            }
        }
        fun requests() = fake.requests.count { it.url.parameters["t"] != "caps" }
        val episode = { ep: Int -> { _: IndexerCaps -> SearchQuery.Tv(ids = TvIds(tvdb = 398712), season = 1, episode = ep) } }
        val caps = CapsCache(fake.client, clock, Duration.ofMinutes(30))
        val multi = MultiIndexerSearch(fake.client, caps, clock = clock)

        val first = assertIs<IndexerOutcome.Results>(multi.search(listOf(good), plan = episode(2)).toList().single())
        assertIs<IndexerOutcome.Failed>(multi.search(listOf(good), plan = episode(3)).toList().single())
        assertEquals(2, requests())

        now = now.plus(Duration.ofMinutes(5))
        val again = assertIs<IndexerOutcome.Results>(multi.search(listOf(good), plan = episode(2)).toList().single())
        assertEquals(first.page, again.page)
        assertEquals(2, requests(), "the kept answer needed no request")
        listOf(multi.search(listOf(good), fresh = true, plan = episode(2)), multi.search(listOf(good), plan = episode(4))).forEach { search ->
            assertEquals(API_LIMIT_REACHED, assertIs<NewznabError.ApiLimitReached>(assertIs<IndexerOutcome.Failed>(search.toList().single()).error).message)
        }
        // Past the answer's 15 minutes and the caps' 30, nothing is kept: it waits for the limit's end like any search.
        now = now.plus(Duration.ofMinutes(30))
        assertIs<IndexerOutcome.Failed>(multi.search(listOf(good), plan = episode(2)).toList().single())
        assertEquals(2, requests(), "an indexer kept out was asked")
        assertEquals(1, fake.requests.count { it.url.parameters["t"] == "caps" })
    }

    /** Whatever wait an indexer's 429 names, that indexer alone fails: the others' answers and the search go on. */
    @Test
    fun `an indexer naming a wait past any end fails alone`() = runBlocking<Unit> {
        val fake = FakeIndexers { request ->
            when {
                request.url.host == "broken.example" -> respond("nope", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, Long.MAX_VALUE.toString()))
                request.url.parameters["t"] == "caps" -> xml(fixture("newznab-caps.xml"))
                else -> xml(fixture("newznab-search.xml"))
            }
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client))
        val waits = (1..2).map {
            val outcomes = withTimeout(10_000) { multi.search(listOf(good, broken), plan = plan).toList() }.associateBy { it.indexer.id }
            assertIs<IndexerOutcome.Results>(outcomes.getValue("good"))
            assertIs<NewznabError.ApiLimitReached>(assertIs<IndexerOutcome.Failed>(outcomes.getValue("broken")).error).retryAfter
        }
        assertEquals(Duration.ofDays(1), waits[0])
        assertTrue(waits[1]!! <= Duration.ofDays(1) && waits[1]!! > Duration.ofHours(23), "the rest of its day: ${waits[1]}")
        assertEquals(1, fake.requests.count { it.url.host == "broken.example" }, "kept out for its day")
    }

    /** A 429 without a Retry-After says the limit, and keeps nothing out: the next search asks again. */
    @Test
    fun `a 429 without Retry-After keeps nothing out`() = runBlocking<Unit> {
        val fake = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") xml(fixture("newznab-caps.xml")) else xml("nope", HttpStatusCode.TooManyRequests)
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client))
        repeat(2) {
            val failed = assertIs<IndexerOutcome.Failed>(multi.search(listOf(good), plan = plan).toList().single())
            val limit = assertIs<NewznabError.ApiLimitReached>(failed.error)
            assertNull(limit.retryAfter)
        }
        assertEquals(2, fake.requests.count { it.url.parameters["t"] == "tvsearch" })
    }

    /**
     * L630: whether a search asked the indexer anew, for a plugin's soft cap. A first search did, and so does a fresh one
     * and one whose answer is no longer kept; the same search again within its 15 minutes is answered from the cache and
     * didn't.
     */
    @Test
    fun `a first search and a fresh one asked the indexer, the same one within 15 minutes didn't`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-24T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val multi = MultiIndexerSearch(fake.client, CapsCache(fake.client, clock), clock = clock)
        suspend fun once(fresh: Boolean = false) = assertIs<IndexerOutcome.Results>(multi.search(listOf(good), fresh, plan).toList().single())

        assertTrue(once().requested, "a first search")
        assertFalse(once().requested, "the same search, answered from the cache")
        now = now.plus(Duration.ofMinutes(10))
        assertFalse(once().requested, "the same search within its 15 minutes")
        assertTrue(once(fresh = true).requested, "a fresh search")
        now = now.plus(Duration.ofMinutes(16))
        assertTrue(once().requested, "the same search once its answer is no longer kept")
        assertEquals(3, fake.requests.count { it.url.parameters["t"] == "tvsearch" })
    }

    /**
     * A search of the same words while the first one's request is still on its way waits for that request: it asked the
     * indexer nothing, and only the first counts. On the test's clock, so the second search is waiting before the answer
     * comes.
     */
    @Test
    fun `a search that joins the same request on its way didn't ask the indexer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val answer = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        if (request.url.parameters["t"] == "caps") return@addHandler xml(fixture("newznab-caps.xml"))
                        answer.await()
                        xml(fixture("newznab-search.xml"))
                    }
                },
            ),
        ) {
            install(HttpTimeout)
            expectSuccess = false
        }
        val client = NewznabClient(http, defaultDispatcher = dispatcher)
        val multi = MultiIndexerSearch(client, CapsCache(client), requests = CoroutineScope(SupervisorJob()))
        val first = async(dispatcher) { multi.search(listOf(good), plan = plan).toList().single() }
        testScheduler.runCurrent()
        val joined = async(dispatcher) { multi.search(listOf(good), plan = plan).toList().single() }
        testScheduler.runCurrent()
        answer.complete(Unit)

        assertTrue(assertIs<IndexerOutcome.Results>(first.await()).requested, "the search that sent the request")
        val second = assertIs<IndexerOutcome.Results>(joined.await())
        assertFalse(second.requested, "the search that waited for it")
        assertEquals(2, second.page.items.size)
        http.close()
    }

    /**
     * A request that fails, or gets no answer in time, still reached the indexer, and a failure is never kept: each such
     * search asked anew, its caps' request included. An indexer kept out by its limit is answered without a request.
     */
    @Test
    fun `a search whose request failed or timed out asked the indexer, one kept out by its limit didn't`() = runBlocking<Unit> {
        val failing = FakeIndexers { request ->
            when {
                request.url.host == "broken.example" -> throw IllegalStateException("engine failed for ${request.url}")
                request.url.parameters["t"] == "caps" -> xml(fixture("newznab-caps.xml"))
                request.url.host == "slow.example" -> { delay(5_000); xml(fixture("newznab-search.xml")) }
                // Its caps answered; its search is refused.
                else -> xml(fixture("error-auth.xml"))
            }
        }
        val multi = MultiIndexerSearch(failing.client, CapsCache(failing.client), perIndexerTimeoutMs = 2_000)
        val outcomes = multi.search(listOf(badKey, slow, broken), plan = plan).toList().associateBy { it.indexer.id }
        val late = assertIs<IndexerOutcome.Failed>(outcomes.getValue("slow"))
        assertIs<NewznabError.Timeout>(late.error)
        assertTrue(late.requested, "a request with no answer in time")
        listOf(outcomes, multi.search(listOf(badKey, broken), plan = plan).toList().associateBy { it.indexer.id }).forEachIndexed { round, answered ->
            val refused = assertIs<IndexerOutcome.Failed>(answered.getValue("bad"))
            assertIs<NewznabError.Auth>(refused.error)
            assertTrue(refused.requested, "a refused request, search ${round + 1}")
            val unreachable = assertIs<IndexerOutcome.Failed>(answered.getValue("broken"))
            assertIs<NewznabError.Network>(unreachable.error)
            assertTrue(unreachable.requested, "a caps request that failed, search ${round + 1}")
        }
        assertEquals(2, failing.requests.count { it.url.host == "bad.example" && it.url.parameters["t"] == "tvsearch" }, "a failure was kept")

        val limiting = FakeIndexers { request ->
            if (request.url.parameters["t"] == "caps") xml(fixture("newznab-caps.xml")) else respond("nope", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
        }
        val kept = MultiIndexerSearch(limiting.client, CapsCache(limiting.client))
        assertTrue(assertIs<IndexerOutcome.Failed>(kept.search(listOf(good), plan = plan).toList().single()).requested, "the request its limit answered")
        listOf(false, true).forEach { fresh ->
            val held = assertIs<IndexerOutcome.Failed>(kept.search(listOf(good), fresh = fresh, plan = plan).toList().single())
            assertIs<NewznabError.ApiLimitReached>(held.error)
            assertFalse(held.requested, "kept out by its limit, fresh = $fresh")
        }
        assertEquals(1, limiting.requests.count { it.url.parameters["t"] == "tvsearch" })
    }
}

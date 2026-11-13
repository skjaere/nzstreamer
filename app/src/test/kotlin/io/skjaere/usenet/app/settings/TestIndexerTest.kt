package io.skjaere.usenet.app.settings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.newznab.ApiLimits
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.NewznabClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An indexer's Test, as the indexer screen and "Set up with your phone" both run it: its caps, then one tiny search
 * for the API and grab counts, and the caps the searches keep for it forgotten.
 */
class TestIndexerTest {
    private val config = IndexerConfig("test-indexer", "Example", "https://indexer.example.org/api", "KEY")

    private var capsCalls = 0
    private var searchAnswer = HttpStatusCode.OK
    private var capsAnswer = HttpStatusCode.OK
    private var searchBody = SEARCH
    /** Each search's answer by its words, where set; else [searchBody] whatever was asked. */
    private var searchBodyFor: ((q: String?) -> String)? = null
    private val asked = mutableListOf<String?>()
    /** Each search's `limit`, as sent. */
    private val limits = mutableListOf<String?>()

    private val client = NewznabClient(
        HttpClient(
            MockEngine { request ->
                if (request.url.parameters["t"] == "caps") {
                    capsCalls++
                    respond(CAPS, capsAnswer, headersOf(HttpHeaders.ContentType, "application/xml"))
                } else {
                    val q = request.url.parameters["q"]
                    asked += q
                    limits += request.url.parameters["limit"]
                    respond(searchBodyFor?.invoke(q) ?: searchBody, searchAnswer, headersOf(HttpHeaders.ContentType, "application/xml"))
                }
            },
        ) {
            install(HttpTimeout)
            expectSuccess = false
        },
    )
    private val caps = CapsCache(client)

    @Test
    fun `a working indexer shows its caps and the search's API and grab counts`() = runBlocking<Unit> {
        val result = assertIs<IndexerTest.Ok>(testIndexer(client, caps, config))
        assertEquals("Example", result.caps.serverTitle)
        assertEquals(ApiLimits(apiCurrent = 3, apiMax = 100, grabCurrent = 1, grabMax = 10), result.limits)
    }

    @Test
    fun `without counts in the search the caps' are shown`() = runBlocking<Unit> {
        searchAnswer = HttpStatusCode.InternalServerError
        val result = assertIs<IndexerTest.Ok>(testIndexer(client, caps, config))
        assertEquals(ApiLimits(apiCurrent = 7, apiMax = 50), result.limits)
    }

    @Test
    fun `an indexer that refuses the caps fails with its words`() = runBlocking<Unit> {
        capsAnswer = HttpStatusCode.Unauthorized
        val result = assertIs<IndexerTest.Failed>(testIndexer(client, caps, config))
        assertEquals(true, result.message.isNotBlank())
    }

    /** The searches read the caps again after a Test, so a changed key or address is used at once. */
    @Test
    fun `a test forgets the caps the searches kept`() = runBlocking<Unit> {
        caps.get(config)
        assertEquals(1, capsCalls)
        testIndexer(client, caps, config)
        assertEquals(2, capsCalls)
        caps.get(config)
        assertEquals(3, capsCalls, "the searches' caps were kept")
    }

    /** L161: a torrent feed added under Indexers is found out by its one-item search, before any play grabs from it. */
    @Test
    fun `a torrent feed on the indexers form says its results aren't NZBs and where to add it`() = runBlocking<Unit> {
        searchBody = TORRENT_SEARCH
        val result = assertIs<IndexerTest.Failed>(testIndexer(client, caps, config, shown = true))
        assertEquals("This indexer’s results aren’t NZBs. Add it under Torrent indexers instead.", result.message)
    }

    /**
     * L161 where the tracker has nothing called "test" (most haven't): a search for "test" that found nothing tells
     * nothing, so the Indexers form's Test asks for the latest results too, and those say they aren't NZBs.
     */
    @Test
    fun `a torrent feed with nothing called test on the indexers form still says its results aren't NZBs`() = runBlocking<Unit> {
        searchBodyFor = { q -> if (q.isNullOrEmpty()) TORRENT_SEARCH else SEARCH }
        val result = assertIs<IndexerTest.Failed>(testIndexer(client, caps, config, shown = true))
        assertEquals("This indexer’s results aren’t NZBs. Add it under Torrent indexers instead.", result.message)
        assertEquals(listOf<String?>("test", ""), asked)
    }

    /** A Usenet indexer whose "test" search finds something is asked once, as before. */
    @Test
    fun `an NZB feed that has something called test is asked once`() = runBlocking<Unit> {
        searchBodyFor = { q -> if (q == "test") NZB_SEARCH else error("asked again, for ${q?.let { "\"$it\"" }}") }
        assertIs<IndexerTest.Ok>(testIndexer(client, caps, config, shown = true))
        assertEquals(listOf<String?>("test"), asked)
    }

    /** While debrid is hidden the screen the second sentence names isn't there (L192), so the Test says the first alone. */
    @Test
    fun `while debrid is hidden it says the first sentence alone`() = runBlocking<Unit> {
        searchBody = TORRENT_SEARCH
        val result = DebridGate.showing(false) { assertIs<IndexerTest.Failed>(testIndexer(client, caps, config)) }
        assertEquals("This indexer’s results aren’t NZBs.", result.message)
    }

    @Test
    fun `an NZB feed on the torrent indexers form says its results are NZBs`() = runBlocking<Unit> {
        searchBody = NZB_SEARCH
        val result = assertIs<IndexerTest.Failed>(testIndexer(client, caps, config.copy(kind = IndexerKind.Torznab), shown = true))
        assertEquals("This indexer’s results are NZBs. Add it under Indexers instead.", result.message)
    }

    @Test
    fun `each feed on its own form passes, and a search that found nothing tells nothing`() = runBlocking<Unit> {
        searchBody = NZB_SEARCH
        assertIs<IndexerTest.Ok>(testIndexer(client, caps, config, shown = true))
        searchBody = TORRENT_SEARCH
        assertIs<IndexerTest.Ok>(testIndexer(client, caps, config.copy(kind = IndexerKind.Torznab), shown = true))
        searchBody = SEARCH
        assertIs<IndexerTest.Ok>(testIndexer(client, caps, config, shown = true))
        assertIs<IndexerTest.Ok>(testIndexer(client, caps, config.copy(kind = IndexerKind.Torznab), shown = true))
    }

    /** L238: a torrent indexer's Test reads its latest 10 results, to say how many have no hash; a Usenet indexer's one. */
    @Test
    fun `a torrent indexer's test asks 10 results, a usenet indexer's one`() = runBlocking<Unit> {
        searchBody = torrents(hashless = setOf(4))
        testIndexer(client, caps, config.copy(kind = IndexerKind.Torznab), shown = true)
        assertEquals(listOf<String?>("10"), limits)
        limits.clear()
        searchBody = NZB_SEARCH
        testIndexer(client, caps, config, shown = true)
        assertEquals(listOf<String?>("1"), limits)
    }

    /** L238: how many of the results it read have no info hash, of how many; the counts stay 0 for a Usenet indexer. */
    @Test
    fun `a torrent indexer's test counts the results without a hash, of those it read`() = runBlocking<Unit> {
        val torznab = config.copy(kind = IndexerKind.Torznab)
        searchBody = torrents(hashless = setOf(2, 5, 7, 9))
        val four = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torznab, shown = true))
        assertEquals(4 to 10, four.noHash to four.latest)
        assertEquals(IndexerKind.Torznab, four.results)
        searchBody = torrents(count = 3, hashless = setOf(1, 3))
        val fewer = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torznab, shown = true))
        assertEquals(2 to 3, fewer.noHash to fewer.latest)
        searchBody = torrents(hashless = emptySet())
        val all = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torznab, shown = true))
        assertEquals(0 to 10, all.noHash to all.latest)
        searchBody = NZB_SEARCH
        val usenet = assertIs<IndexerTest.Ok>(testIndexer(client, caps, config, shown = true))
        assertEquals(0 to 0, usenet.noHash to usenet.latest)
    }

    /** An indexer that answers more than it was asked: its first 10 are counted, in the feed's order. */
    @Test
    fun `an answer with more results than asked counts its first 10`() = runBlocking<Unit> {
        val torznab = config.copy(kind = IndexerKind.Torznab)
        searchBody = torrents(count = 14, hashless = setOf(11, 12, 14))
        val late = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torznab, shown = true))
        assertEquals(0 to 10, late.noHash to late.latest)
        searchBody = torrents(count = 14, hashless = setOf(1, 10, 11))
        val early = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torznab, shown = true))
        assertEquals(2 to 10, early.noHash to early.latest)
    }

    /** DbPhoneTorrentIndexer, D2PhoneIndexerTest 2: results left out for want of a hash are still torrents. */
    @Test
    fun `results all without a hash still read as torrents`() = runBlocking<Unit> {
        searchBody = torrents(hashless = (1..10).toSet())
        val ok = assertIs<IndexerTest.Ok>(testIndexer(client, caps, config.copy(kind = IndexerKind.Torznab), shown = true))
        assertEquals(IndexerKind.Torznab, ok.results)
        assertEquals(10 to 10, ok.noHash to ok.latest)
    }

    private companion object {
        /** A Torznab answer of [count] torrents, newest first; those numbered (from 1) in [hashless] have no hash anywhere. */
        fun torrents(count: Int = 10, hashless: Set<Int>): String =
            """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>""" +
                (1..count).joinToString("") { n ->
                    """<item><title>Harbor.Lights.S01E${"%02d".format(n)}.1080p.WEB.H264-GRP</title><guid>https://tracker.example.org/torrent/$n</guid>""" +
                        """<enclosure url="http://192.168.1.20:9696/3/download?link=$n" length="1000" type="application/x-bittorrent"/>""" +
                        (if (n in hashless) "" else """<torznab:attr name="infohash" value="${"%040x".format(n)}"/>""") + "</item>"
                } + "</channel></rss>"

        const val CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server version="1.0" title="Example"/><limits max="100" default="100"/>""" +
            """<apilimits apicurrent="7" apimax="50"/>""" +
            """<searching><search available="yes" supportedParams="q"/><tv-search available="no"/><movie-search available="no"/></searching></caps>"""
        const val SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">""" +
            """<channel><newznab:apilimits apicurrent="3" apimax="100" grabcurrent="1" grabmax="10"/><newznab:response offset="0" total="0"/></channel></rss>"""
        const val TORRENT_SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">""" +
            """<channel><item><title>Quiet.Orbit.S02E05.1080p.WEB.H264-GRP</title><guid>https://tracker.example.org/torrent/79</guid>""" +
            """<enclosure url="http://jackett.home.lan:9117/dl/t/?jackett_apikey=KEY" length="1000" type="application/x-bittorrent"/>""" +
            """<torznab:attr name="infohash" value="4d59de26e786129d949fc9ebb40f115b1d69b1b7"/></item></channel></rss>"""
        const val NZB_SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">""" +
            """<channel><item><title>Quiet.Orbit.S02E05.1080p.WEB.H264-GRP</title><guid>a1b2</guid>""" +
            """<enclosure url="https://indexer.example.org/getnzb/a1b2.nzb" length="1000" type="application/x-nzb"/></item></channel></rss>"""
    }
}

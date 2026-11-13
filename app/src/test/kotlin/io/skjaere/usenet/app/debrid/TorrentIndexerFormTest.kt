package io.skjaere.usenet.app.debrid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.indexerConnected
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.app.settings.testIndexer
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.newznab.ApiLimits
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.Category
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.NewznabClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Torrent indexers (L194; DbPhoneTorrentIndexer, DbPhoneSources, DbTvSources 5-6): the form's Test lines for a
 * torrent feed, its switches and what Save keeps, each row's line, the hub row's, and the list's words.
 */
class TorrentIndexerFormTest {
    private var searchBody = TORRENT_SEARCH

    /** Each search's words (`q`), as the indexer was asked. */
    private val asked = mutableListOf<String?>()

    private val client = NewznabClient(
        HttpClient(
            MockEngine { request ->
                val caps = request.url.parameters["t"] == "caps"
                if (!caps) asked += request.url.parameters["q"]
                val body = if (caps) PROWLARR_CAPS else searchBody
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            },
        ) {
            install(HttpTimeout)
            expectSuccess = false
        },
    )
    private val caps = CapsCache(client)

    private val form = newTorrentIndexerForm().let { f ->
        f.copy(fields = f.fields.copy(name = "Home Prowlarr", url = "http://192.168.1.20:9696/3", apiKey = "a3f9c2e81b7d4c60"))
    }

    /** DbPhoneTorrentIndexer 2: Prowlarr's caps and one torrent found. */
    @Test
    fun `a prowlarr feed's test says torrents, how it searches and in what`() = runBlocking<Unit> {
        val config = torrentIndexerConfig(form.fields, savedKey = null)
        assertEquals(IndexerKind.Torznab, config.kind)
        val ok = assertIs<IndexerTest.Ok>(testIndexer(client, caps, config, shown = true))
        assertEquals("Connected · Prowlarr", indexerConnected(ok))
        assertEquals(
            listOf("Results" to "Torrents", "TV search by" to "TVDB, IMDb", "Movie search by" to "TMDB, IMDb", "Searches in" to "Movies, TV"),
            torrentTestLines(ok),
        )
    }

    /**
     * A tracker may have nothing called "test", which a Usenet indexer's Test searches for: a torrent indexer's asks its
     * latest torrent (no words, Torznab's RSS search), so its results can be told.
     */
    @Test
    fun `a torrent indexer's test asks for its latest torrent, not for words it may not have`() = runBlocking<Unit> {
        testIndexer(client, caps, torrentIndexerConfig(form.fields, null), shown = true)
        assertEquals(listOf<String?>(""), asked)
        asked.clear()
        searchBody = NZB_SEARCH
        testIndexer(client, caps, torrentIndexerConfig(form.fields, null).copy(kind = IndexerKind.Newznab), shown = true)
        assertEquals(listOf<String?>("test"), asked, "a usenet indexer's test as before")
    }

    @Test
    fun `a test search that found nothing can't tell what the results are`() = runBlocking<Unit> {
        searchBody = EMPTY_SEARCH
        val ok = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torrentIndexerConfig(form.fields, null), shown = true))
        assertEquals("Results" to "—", torrentTestLines(ok).first())
    }

    /**
     * D2PhoneIndexerTest, choice 2 (L238): a warning under the Test's lines says how many of the latest results have no
     * info hash, so the app leaves them out; nothing when every one has one, or there were none.
     */
    @Test
    fun `the test's hash line says how many of the last results have no info hash, and nothing when none lacks one`() {
        fun line(noHash: Int, latest: Int) = torrentHashLine(IndexerTest.Ok(IndexerCaps(), null, IndexerKind.Torznab, noHash = noHash, latest = latest))
        assertEquals("4 of the last 10 results have no info hash, so the app leaves them out.", line(4, 10))
        assertEquals("None of the last 10 results has an info hash, so the app leaves them all out.", line(10, 10))
        assertEquals("2 of the last 3 results have no info hash, so the app leaves them out.", line(2, 3))
        assertEquals("1 of the last 10 results has no info hash, so the app leaves it out.", line(1, 10))
        assertEquals("The last result has no info hash, so the app leaves it out.", line(1, 1))
        assertNull(line(0, 10), "every result has a hash: no line")
        assertNull(line(0, 0), "no results: no line, Results says —")
    }

    /** The form's Test as a whole: Prowlarr answering its 10 newest, 4 of them without a hash. */
    @Test
    fun `a feed with results lacking a hash shows the line under the test's lines`() = runBlocking<Unit> {
        searchBody = TORRENTS_FOUR_HASHLESS
        val ok = assertIs<IndexerTest.Ok>(testIndexer(client, caps, torrentIndexerConfig(form.fields, null), shown = true))
        assertEquals("Results" to "Torrents", torrentTestLines(ok).first())
        assertEquals("4 of the last 10 results have no info hash, so the app leaves them out.", torrentHashLine(ok))
        searchBody = TORRENT_SEARCH
        assertNull(torrentHashLine(assertIs<IndexerTest.Ok>(testIndexer(client, caps, torrentIndexerConfig(form.fields, null), shown = true))))
    }

    /** An indexer reporting its API calls says so, as the indexer form does; a torrent indexer is never grabbed from. */
    @Test
    fun `the api calls an indexer reports follow`() {
        val ok = IndexerTest.Ok(IndexerCaps(), ApiLimits(apiCurrent = 3, apiMax = 100, grabCurrent = 1, grabMax = 10), IndexerKind.Torznab)
        assertEquals("API calls today" to "3 of 100", torrentTestLines(ok).last())
        assertTrue(torrentTestLines(ok).none { it.first.startsWith("Grabs") })
    }

    /** "Searches in", in Categories' place: the parents a search will ask for (L167), from the indexer's own caps. */
    @Test
    fun `searches in names the movies and tv its caps list`() {
        fun caps(vararg ids: Int) = IndexerCaps(categories = ids.map { Category(it, "c$it", listOf(Category(it + 40, "HD"))) })
        assertEquals("Movies, TV", searchesIn(caps(2000, 5000, 100001)))
        assertEquals("TV", searchesIn(caps(5000)))
        assertEquals("Movies", searchesIn(caps(2000, 3000)))
        // Neither listed: no category is sent, so the indexer searches all of its own.
        assertEquals("All categories", searchesIn(caps(100001)))
        assertEquals("All categories", searchesIn(IndexerCaps()))
    }

    @Test
    fun `an NZB feed on the torrent form says its results are NZBs`() = runBlocking<Unit> {
        searchBody = NZB_SEARCH
        val failed = assertIs<IndexerTest.Failed>(testIndexer(client, caps, torrentIndexerConfig(form.fields, null), shown = true))
        assertEquals("This indexer’s results are NZBs. Add it under Indexers instead.", failed.message)
    }

    /** As the indexer form: an empty key field tests with the saved key, and a typed one wins. */
    @Test
    fun `the test uses the typed key, else the saved one`() {
        assertEquals("SAVED", torrentIndexerConfig(form.fields.copy(apiKey = " "), savedKey = "SAVED").apiKey)
        assertEquals("a3f9c2e81b7d4c60", torrentIndexerConfig(form.fields, savedKey = "SAVED").apiKey)
    }

    /** W75: both switches on for a new one; the second says what it is for. */
    @Test
    fun `a new torrent indexer is used for searches and with the service, and the switch says why it's there`() {
        val fresh = newTorrentIndexerForm()
        assertTrue(fresh.fields.enabled)
        assertTrue(fresh.useWithService)
        assertEquals("Use with your debrid service", USE_WITH_SERVICE)
        assertEquals("Some private trackers don’t allow debrid services. Turn this off for those.", USE_WITH_SERVICE_HELP)
    }

    @Test
    fun `save keeps both switches, the key trimmed, and names it after its host when unnamed`() = runBlocking<Unit> {
        val settings = memorySettings()
        val unnamed = form.copy(fields = form.fields.copy(name = " ", apiKey = " a3f9c2e81b7d4c60 "), useWithService = false)
        val id = settings.save(unnamed)
        val saved = settings.torrentIndexer(id)!!
        assertEquals(TorrentIndexerRecord(id, "192.168.1.20", "http://192.168.1.20:9696/3", enabled = true, useWithService = false), saved.record.copy(updatedAt = 0))
        assertEquals("a3f9c2e81b7d4c60", saved.apiKey)
        // Saved again with the key field empty and the switch on: the key stays.
        settings.save(unnamed.copy(fields = unnamed.fields.copy(name = "Quarry", apiKey = "", enabled = false), useWithService = true))
        val again = settings.torrentIndexer(id)!!
        assertEquals(listOf("Quarry"), settings.torrentIndexers.first().map { it.name })
        assertEquals(false, again.record.enabled)
        assertEquals(true, again.record.useWithService)
        assertEquals("a3f9c2e81b7d4c60", again.apiKey)
    }

    /** DbPhoneTorrentIndexer 1: each row's line says where it is, and when it's off or not used with the service. */
    @Test
    fun `each row's line says its host, and when it's off or not used with the service`() {
        val prowlarr = TorrentIndexerRecord("1", "Home Prowlarr", "http://192.168.1.20:9696/1")
        assertEquals("192.168.1.20", torrentIndexerRowLine(prowlarr))
        assertEquals("jackett.home.lan · off", torrentIndexerRowLine(TorrentIndexerRecord("2", "Jackett", "http://jackett.home.lan:9117/api/v2.0/indexers/x/results/torznab", enabled = false)))
        assertEquals("192.168.1.20 · not used with your debrid service", torrentIndexerRowLine(prowlarr.copy(useWithService = false)))
        // Off says it all: not searched at all.
        assertEquals("192.168.1.20 · off", torrentIndexerRowLine(prowlarr.copy(enabled = false, useWithService = false)))
    }

    /** DbPhoneSources: the hub row as Indexers' reads, and, with no service on, that none is searched (L166). */
    @Test
    fun `the hub row says how many, and that none is searched without a service`() {
        val three = listOf(
            TorrentIndexerRecord("1", "Home Prowlarr", "http://192.168.1.20:9696/1"),
            TorrentIndexerRecord("2", "Jackett", "http://jackett.home.lan:9117", enabled = false),
            TorrentIndexerRecord("3", "Quarry", "http://192.168.1.20:9696/3", useWithService = false),
        )
        assertEquals(HubLine("None yet"), torrentIndexersHubLine(emptyList(), serviceOn = false))
        assertEquals(HubLine("None yet"), torrentIndexersHubLine(emptyList(), serviceOn = true))
        assertEquals(HubLine("3 torrent indexers · 1 off"), torrentIndexersHubLine(three, serviceOn = true))
        assertEquals(HubLine("1 torrent indexer"), torrentIndexersHubLine(three.take(1), serviceOn = true))
        assertEquals(HubLine("1 torrent indexer · not searched without a service", warn = true), torrentIndexersHubLine(three.take(1), serviceOn = false))
        assertEquals(HubLine("3 torrent indexers · not searched without a service", warn = true), torrentIndexersHubLine(three, serviceOn = false))
    }

    /** The list's words (DbPhoneTorrentIndexer 1; DbTvSources 5 says "this TV"), the footnote as L194 corrects the boards'. */
    @Test
    fun `the list's line and footnote`() {
        assertEquals("Searched beside your Indexers while a debrid service is on.", TORRENT_INDEXERS_LINE)
        assertEquals(
            "Only a torrent’s ID (its info hash) goes to your debrid service, and only cached torrents play: no passkey leaves this phone, " +
                "and the tracker sees nothing of what you play.",
            torrentIndexersFootnote(television = false),
        )
        assertTrue("no passkey leaves this TV, and the tracker sees nothing of what you play." in torrentIndexersFootnote(television = true))
    }

    private companion object {
        const val PROWLARR_CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server title="Prowlarr"/><limits default="100" max="100"/>""" +
            """<searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,season,ep,imdbid,tvdbid"/>""" +
            """<movie-search available="yes" supportedParams="q,imdbid,tmdbid"/></searching>""" +
            """<categories><category id="2000" name="Movies"><subcat id="2040" name="Movies/HD"/></category>""" +
            """<category id="5000" name="TV"><subcat id="5040" name="TV/HD"/></category><category id="100001" name="Site TV"/></categories></caps>"""
        const val TORRENT_SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">""" +
            """<channel><item><title>Quiet.Orbit.S02E05.1080p.WEB.H264-GRP</title><guid>https://tracker.example.org/torrent/79</guid>""" +
            """<enclosure url="http://192.168.1.20:9696/3/download?link=x" length="1000" type="application/x-bittorrent"/>""" +
            """<torznab:attr name="infohash" value="4d59de26e786129d949fc9ebb40f115b1d69b1b7"/></item></channel></rss>"""
        /** Ten torrents, newest first; the 2nd, 5th, 7th and 9th give no hash (a .torrent link alone). */
        val TORRENTS_FOUR_HASHLESS = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>""" +
            (1..10).joinToString("") { n ->
                """<item><title>Quiet.Orbit.S02E${"%02d".format(n)}.1080p.WEB.H264-GRP</title><guid>https://tracker.example.org/torrent/$n</guid>""" +
                    """<enclosure url="http://192.168.1.20:9696/3/download?link=$n" length="1000" type="application/x-bittorrent"/>""" +
                    (if (n in setOf(2, 5, 7, 9)) "" else """<torznab:attr name="infohash" value="${"%040x".format(n)}"/>""") + "</item>"
            } + "</channel></rss>"
        const val EMPTY_SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel></channel></rss>"""
        const val NZB_SEARCH = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">""" +
            """<channel><item><title>Quiet.Orbit.S02E05.1080p.WEB.H264-GRP</title><guid>a1b2</guid>""" +
            """<enclosure url="https://indexer.example.org/getnzb/a1b2.nzb" length="1000" type="application/x-nzb"/></item></channel></rss>"""
    }
}

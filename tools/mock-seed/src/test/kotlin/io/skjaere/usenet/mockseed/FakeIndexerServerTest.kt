package io.skjaere.usenet.mockseed

import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.LeftOut
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeIndexerServerTest {

    private val releases = FakeCatalog.releases().map { release ->
        release to """<?xml version="1.0"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file subject="${release.guid}"><segments><segment bytes="1000000" number="1">${release.guid}@x</segment></segments></file></nzb>"""
    }
    private val server = FakeIndexerServer(releases, apiKey = "k", port = 0, grabLimit = 2)
    private val indexer = IndexerConfig("fake", "Fake", "http://localhost:${server.port}", "k")
    private val client = NewznabClient(NewznabClient.defaultHttpClient())

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `caps support every id search`() = runBlocking<Unit> {
        val caps = client.caps(indexer)
        listOf("tvdbid", "tmdbid", "imdbid", "tvmazeid", "season", "ep").forEach { assertTrue(caps.supports(SearchType.Tv, it), it) }
    }

    @Test
    fun `episode search by tvdb id returns the four S01E01 releases, ranked with the broken one first and the passworded one next`() = runBlocking<Unit> {
        val page = client.search(indexer, SearchQuery.Tv(ids = TvIds(tvdb = FakeCatalog.HARBOR_LIGHTS.tvdb), season = 1, episode = 1))
        assertEquals(4, page.items.size)
        assertTrue(page.items.all { it.tvdbId == 900001 && it.season == 1 && it.episode == 1 })

        // On a device without HEVC 10-bit: BROKEN (proper, most grabs) > PASSWD (flagged, tried all the same; more grabs) > GOOD,
        // then the one that can't play.
        val device = StaticDeviceCapabilities(videoTenBit = emptySet())
        val ranked = ReleaseRanker().rank(
            page.items.map { ReleaseFacts(it.guid, it.title, it.sizeBytes, it.passworded, it.grabs, mediaInfo = it.mediaInfo) },
            RankingContext(device),
        )
        assertEquals(listOf("hl-s01e01-broken", "hl-s01e01-passwd", "hl-s01e01-good", "hl-s01e01-tenbit"), ranked.map { it.facts.id })
        assertEquals(listOf(true, true, true, false), ranked.map { it.playable })
    }

    @Test
    fun `season search includes the pack, episode search does not`() = runBlocking<Unit> {
        val ids = TvIds(imdb = FakeCatalog.HARBOR_LIGHTS.imdb)
        assertEquals(emptyList(), client.search(indexer, SearchQuery.Tv(ids = ids, season = 1, episode = 3)).items)
        assertTrue("hl-s01-pack" in client.search(indexer, SearchQuery.Tv(ids = ids, season = 1)).items.map { it.guid })
    }

    @Test
    fun `text and movie searches`() = runBlocking<Unit> {
        assertEquals(2, client.search(indexer, SearchQuery.Text("harbor lights s01e02")).items.size)
        assertEquals(listOf("cc-2019-eac3", "cc-2019-good"), client.search(indexer, SearchQuery.Movie(imdb = "tt9000012")).items.map { it.guid })
    }

    @Test
    fun `nzb download counts grabs and enforces the limit`() = runBlocking<Unit> {
        val item = client.search(indexer, SearchQuery.Text("copper crown good")).items.single()
        assertTrue("{apikey}" in item.downloadTemplate.orEmpty())
        repeat(2) { assertTrue(client.nzb(indexer, item).decodeToString().contains("cc-2019-good")) }
        assertIs<NewznabError.DownloadLimitReached>(assertFailsWith<NewznabException> { client.nzb(indexer, item) }.error)
    }

    @Test
    fun `wrong key is an auth error`() = runBlocking<Unit> {
        assertIs<NewznabError.Auth>(assertFailsWith<NewznabException> { client.caps(indexer.copy(apiKey = "nope")) }.error)
    }

    // The Torznab mode (L189): `torznab --port 8096`, Prowlarr- and Jackett-shaped feeds over torrents named for the
    // device cases, read here by the app's client as a torrent indexer's, and as served where the shape is the point.

    private fun prowlarr(server: FakeTorznabServer) =
        IndexerConfig("prowlarr", "Home Prowlarr", "http://127.0.0.1:${server.port}/prowlarr/1", "tk", kind = IndexerKind.Torznab)
    private fun jackett(server: FakeTorznabServer) = IndexerConfig(
        "jackett", "Jackett", "http://127.0.0.1:${server.port}/jackett/api/v2.0/indexers/fake/results/torznab", "tk", kind = IndexerKind.Torznab,
    )

    /** The feed as served, for what the client doesn't keep (links, magnets): only invented names and hashes in it. */
    private fun served(server: FakeTorznabServer, path: String, query: String): String =
        URI("http://127.0.0.1:${server.port}$path/api?apikey=tk&$query").toURL().readText()

    @Test
    fun `the prowlarr-shaped feed lists torrents with their hash, seeders, peers, files and grabs as attributes`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            val caps = client.caps(prowlarr(server))
            assertTrue(caps.supports(SearchType.Tv, "tvdbid") && caps.supports(SearchType.Tv, "season"))
            assertEquals(listOf(2000, 5000, 100001), caps.categories.map { it.id })

            val page = client.search(prowlarr(server), SearchQuery.Tv(ids = TvIds(tvdb = FakeCatalog.HARBOR_LIGHTS.tvdb), season = 1, episode = 2))
            val good = page.items.single { it.title == FakeTorrents.GOOD_S01E02.title }
            assertEquals(FakeTorrents.GOOD_S01E02.hash, good.infoHash)
            assertEquals("btih:${FakeTorrents.GOOD_S01E02.hash}", good.guid)
            assertEquals(FakeTorrents.GOOD_S01E02.sizeBytes, good.sizeBytes)
            assertEquals(FakeTorrents.GOOD_S01E02.grabs, good.grabs)
            assertEquals(1, good.files)
            assertEquals(FakeCatalog.HARBOR_LIGHTS.tvdb, good.tvdbId)
            assertTrue(good.attributes.getValue("seeders").single().toInt() > 0)
            assertTrue(good.attributes.getValue("peers").single().toInt() > 0)
            assertEquals(listOf(5040, 100001), good.categories)
            assertEquals("application/x-bittorrent", good.enclosureType)
            // Without a v1 hash (a .torrent link alone, or a v2-only torrent), no infohash attribute: left out.
            assertEquals(listOf(FakeTorrents.HDTV_S01E02.title), page.items.map { it.title } - good.title)
            assertEquals(mapOf(LeftOut.NoHash to 2), page.leftOut)
            // The enclosure is the fake's own download link, as Prowlarr wraps every link.
            val xml = served(server, "/prowlarr/1", "t=tvsearch&tvdbid=900001&season=1&ep=2")
            assertContains(xml, """<enclosure url="http://127.0.0.1:${server.port}/prowlarr/1/download?apikey=tk&amp;""")
            assertFalse("magnet:" in xml)
        }
    }

    @Test
    fun `the jackett-shaped feed gives raw magnets and size, files and grabs as elements`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            val caps = client.caps(jackett(server))
            assertFalse(caps.supports(SearchType.Tv, "tvdbid"), "Jackett's sites search TV by name")
            assertTrue(caps.supports(SearchType.Tv, "season") && caps.supports(SearchType.Tv, "ep"))
            assertEquals(listOf(2000, 5000, 100001), caps.categories.map { it.id })

            val page = client.search(jackett(server), SearchQuery.Tv(query = "Harbor Lights", season = 1))
            val pack = page.items.single { it.title == FakeTorrents.PACK_S01.title }
            assertEquals(FakeTorrents.PACK_S01.hash, pack.infoHash, "from its hybrid magnet")
            assertEquals(FakeTorrents.PACK_S01.sizeBytes, pack.sizeBytes)
            assertEquals(3, pack.files)
            assertEquals(FakeTorrents.PACK_S01.grabs, pack.grabs)
            assertEquals(FakeTorrents.HDTV_S01E02.hash, page.items.single { it.title == FakeTorrents.HDTV_S01E02.title }.infoHash, "from base32")
            assertEquals(mapOf(LeftOut.NoHash to 2), page.leftOut, "the v2-only torrent and the .torrent link alone")

            val xml = served(server, "/jackett/api/v2.0/indexers/fake/results/torznab", "t=tvsearch&q=harbor+lights&season=1")
            // A hybrid magnet: its v1 hash beside a v2 one, the name and a tracker, as Jackett builds them; raw as the
            // guid, the link, the enclosure and magneturl.
            val hybrid = "magnet:?xt=urn:btih:${FakeTorrents.PACK_S01.hash}&amp;xt=urn:btmh:1220"
            assertEquals(4, xml.split(hybrid).size - 1)
            assertContains(xml, "&amp;dn=Harbor.Lights.S01.1080p")
            assertContains(xml, "&amp;tr=udp%3A%2F%2Ftracker.example.org")
            assertContains(xml, "xt=urn:btih:${FakeTorrents.base32(FakeTorrents.HDTV_S01E02.hash!!)}&amp;")
            assertContains(xml, "<files>3</files>")
            assertContains(xml, "<size>${FakeTorrents.PACK_S01.sizeBytes}</size>")
            assertContains(xml, "<grabs>${FakeTorrents.PACK_S01.grabs}</grabs>")
            assertContains(xml, "/jackett/dl/fake/?jackett_apikey=tk&amp;")
            // One item's guid is a tracker's download link with a passkey, as Jackett falls back to when a site gives
            // no guid; the client knows it by its hash alone.
            assertContains(served(server, "/jackett/api/v2.0/indexers/fake/results/torznab", "t=movie&q=copper+crown"), "?torrent_pass=")
            val remux = client.search(jackett(server), SearchQuery.Movie(query = "Copper Crown")).items.single { it.title == FakeTorrents.CROWN_REMUX.title }
            assertEquals("btih:${FakeTorrents.CROWN_REMUX.hash}", remux.guid)
        }
    }

    @Test
    fun `the categories each search asked for are recorded`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            client.search(prowlarr(server), SearchQuery.Tv(query = "Harbor Lights", season = 1, categories = listOf(5000)))
            client.search(jackett(server), SearchQuery.Text("Copper Crown"))
            client.search(jackett(server), SearchQuery.Movie(query = "Copper Crown", categories = listOf(2000, 100001)))
            assertEquals(
                listOf(
                    FakeTorznabServer.Search(FakeTorznabServer.Feed.Prowlarr, "tvsearch", "5000"),
                    FakeTorznabServer.Search(FakeTorznabServer.Feed.Jackett, "search", null),
                    FakeTorznabServer.Search(FakeTorznabServer.Feed.Jackett, "movie", "2000,100001"),
                ),
                server.searches,
            )
        }
    }

    @Test
    fun `a category the items aren't in finds none of them, their parent or the site category finds them`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            val tv = SearchQuery.Tv(query = "Harbor Lights", season = 1, episode = 2)
            assertEquals(emptyList(), client.search(prowlarr(server), tv.copy(categories = listOf(2000))).items)
            assertTrue(client.search(prowlarr(server), tv.copy(categories = listOf(5000))).items.isNotEmpty())
            assertTrue(client.search(prowlarr(server), tv.copy(categories = listOf(100001))).items.isNotEmpty())
        }
    }

    /**
     * L238: the feeds answer newest first, by age, and honour `offset` and `limit` as Prowlarr and Jackett do, so the
     * torrent indexer's Test reads the newest 10 (all but the oldest film here, two of them without a v1 hash).
     */
    @Test
    fun `the feeds answer newest first and honour offset and limit`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            val newest = FakeTorrents.all.sortedBy { it.ageDays }
            assertEquals(FakeTorrents.CROWN_RAR, newest.last(), "the oldest")
            listOf(prowlarr(server), jackett(server)).forEach { feed ->
                val path = URI(feed.baseUrl).path
                fun titles(query: String) = Regex("<item>\\s*<title>([^<]+)</title>").findAll(served(server, path, query)).map { it.groupValues[1] }.toList()
                assertEquals(newest.map { it.title }, titles("t=search&q="), "${feed.name}: newest first")
                assertEquals(newest.take(10).map { it.title }, titles("t=search&q=&limit=10"), feed.name)
                assertEquals(newest.drop(3).take(4).map { it.title }, titles("t=search&q=&offset=3&limit=4"), feed.name)
                assertEquals(newest.drop(9).map { it.title }, titles("t=search&q=&offset=9"), feed.name)

                val page = client.search(feed, SearchQuery.Text(""), limit = 10)
                assertEquals(10, page.items.size + page.leftOut.values.sum(), feed.name)
                assertEquals(mapOf(LeftOut.NoHash to 2), page.leftOut, "${feed.name}: the v2-only torrent and the .torrent link alone")
            }
        }
    }

    /** `--fail 429-900`: Jackett's answer to a site's rate limit, its code 900 in an HTTP 429 with a Retry-After. */
    @Test
    fun `the 429 mode answers every search with code 900 and Retry-After 30, and still the caps`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0, fail = FakeTorznabServer.Fail.Limit429Code900).use { server ->
            assertEquals("Jackett", client.caps(jackett(server)).serverTitle)
            val search = URI("http://127.0.0.1:${server.port}/jackett/api/v2.0/indexers/fake/results/torznab/api?t=search&q=harbor&apikey=tk")
                .toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(429, search.responseCode)
                assertEquals("30", search.getHeaderField("Retry-After"))
                assertContains(search.errorStream.readBytes().decodeToString(), "<error code=\"900\"")
            } finally {
                search.disconnect()
            }
        }
    }

    /**
     * Read against the delay, not against the machine's speed: the client's first request (its engine starting, under a
     * busy parallel build) is left out of the timing, and the caps need only answer within half the delay.
     */
    @Test
    fun `a delay holds each search's answer, not the caps`() = runBlocking<Unit> {
        val delayMs = 2_000L
        FakeTorznabServer(apiKey = "tk", port = 0, delayMs = delayMs).use { server ->
            client.caps(prowlarr(server))
            val capsStarted = System.nanoTime()
            client.caps(prowlarr(server))
            val capsMs = (System.nanoTime() - capsStarted) / 1_000_000
            assertTrue(capsMs < delayMs / 2, "the caps answer at once, not held (${capsMs} ms)")
            val started = System.nanoTime()
            client.search(prowlarr(server), SearchQuery.Text("Harbor Lights"))
            assertTrue((System.nanoTime() - started) / 1_000_000 >= delayMs, "the search waits")
        }
    }

    /** `--extra-hash <hex> --extra-title <title>`: one more item, in both feeds, for the live device class (L212). */
    @Test
    fun `the extra item is listed in both feeds by its hash`() = runBlocking<Unit> {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        FakeTorznabServer(apiKey = "tk", port = 0, extra = FakeTorrents.extra(hash, "Quiet Orbit 2008 1080p")).use { server ->
            val fromProwlarr = client.search(prowlarr(server), SearchQuery.Text("quiet orbit")).items.single()
            assertEquals("Quiet Orbit 2008 1080p", fromProwlarr.title)
            assertEquals(hash, fromProwlarr.infoHash)
            val fromJackett = client.search(jackett(server), SearchQuery.Movie(query = "quiet orbit")).items.single()
            assertEquals(hash, fromJackett.infoHash, "from its magnet")
        }
        // Without one, the search finds nothing by those words.
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            assertEquals(emptyList(), client.search(prowlarr(server), SearchQuery.Text("quiet orbit")).items)
        }
    }

    @Test
    fun `a wrong key is refused as Prowlarr and Jackett refuse it`() = runBlocking<Unit> {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            assertIs<NewznabError.Auth>(assertFailsWith<NewznabException> { client.caps(prowlarr(server).copy(apiKey = "nope")) }.error)
            assertIs<NewznabError.Auth>(assertFailsWith<NewznabException> { client.caps(jackett(server).copy(apiKey = "nope")) }.error)
        }
    }

    /**
     * The command seeds nothing (L189): unlike `indexer`, it never posts to the mock NNTP server's 8081, so it can run
     * while the mock is down. Given the address of a socket that only listens, it never connects there.
     */
    @Test
    fun `the torznab command starts the fake without calling the mock`() = runBlocking<Unit> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { mock ->
            mock.soTimeout = 300
            torznab(mapOf("port" to "0", "mock" to "http://127.0.0.1:${mock.localPort}"), log = {}).use { server ->
                assertEquals("Prowlarr", client.caps(prowlarr(server).copy(apiKey = "fake-torznab-key")).serverTitle)
                assertTrue(client.search(prowlarr(server).copy(apiKey = "fake-torznab-key"), SearchQuery.Text("Harbor Lights")).items.isNotEmpty())
            }
            assertFailsWith<SocketTimeoutException> { mock.accept() }
        }
    }

    @Test
    fun `the command refuses an extra item without both its hash and its title, or a hash that isn't 40 hex digits`() {
        listOf(
            mapOf("extra-hash" to "0123456789abcdef0123456789abcdef01234567"),
            mapOf("extra-title" to "Quiet Orbit"),
            mapOf("extra-hash" to "0123456789abcdef", "extra-title" to "Quiet Orbit"),
            mapOf("fail" to "500"),
        ).forEach { options ->
            assertFailsWith<IllegalArgumentException>(options.toString()) { torznab(options + ("port" to "0"), log = {}).close() }
        }
    }

    /** The fake listens on this machine alone: the emulators reach it at 10.0.2.2, which is the host's loopback. */
    @Test
    fun `the fake listens on the loopback address alone`() {
        FakeTorznabServer(apiKey = "tk", port = 0).use { server ->
            assertTrue(server.address.isLoopbackAddress)
        }
    }
}

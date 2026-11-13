package io.skjaere.usenet.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EpisodeReleaseFinderTest {

    private val show = ShowRef("Harbor Lights", 2021, ids = TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"))
    private val request = EpisodeRequest(show, season = 1, episode = 2, runtimeMinutes = 45)
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))

    /**
     * A release a fake indexer lists; ids are what the indexer attached (often none), [season] and
     * [episode] how it numbered it. [guid] defaults to a hash of the title.
     */
    data class Listing(val title: String, val tvdb: Int? = null, val imdb: String? = null, val season: Int? = null, val episode: Int? = null, val guid: String? = null)

    /**
     * A Newznab indexer at [host] whose tv-search supports [tvParams] (null: no tv-search).
     * Like real ones it filters by id, season and episode, and by words for q, and ignores the
     * tv-search params its caps don't list. A daily episode asked by air date (season=YYYY&ep=MM/DD)
     * is matched by the date in the release name. With [failWith]
     * it answers that error code to the requests [failsOn] picks (by default all of them).
     */
    data class FakeIndexer(
        val host: String,
        val tvParams: String?,
        val listings: List<Listing>,
        val failWith: Int? = null,
        val failsOn: (io.ktor.http.Parameters) -> Boolean = { true },
    ) {
        val config = IndexerConfig(host, host, "https://$host/api", "k")
    }

    /** Every request the indexers got: the episode's and the season's searches ask from two threads at once. */
    private val requests: MutableList<HttpRequestData> = java.util.concurrent.CopyOnWriteArrayList()
    /** The threads the indexers were asked from. */
    private val askedOn = ConcurrentLinkedQueue<String>()

    private fun finder(
        vararg indexers: FakeIndexer,
        ranker: ReleaseRanker = ReleaseRanker(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Pair<EpisodeReleaseFinder, List<IndexerConfig>> {
        val byHost = indexers.associateBy { it.host }
        val http = HttpClient(MockEngine { request ->
            requests += request
            val fake = byHost.getValue(request.url.host)
            val p = request.url.parameters
            val body = when {
                fake.failWith != null && fake.failsOn(p) -> """<error code="${fake.failWith}" description="Request limit reached"/>"""
                p["t"] == "caps" -> caps(fake)
                else -> rss(fake.listings.filter { matches(fake, it, p) })
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/xml"))
        }) {
            install(HttpTimeout)
            expectSuccess = false
        }
        http.requestPipeline.intercept(HttpRequestPipeline.Before) { askedOn += Thread.currentThread().name.substringBefore(" @") }
        val client = NewznabClient(http)
        return EpisodeReleaseFinder(MultiIndexerSearch(client, CapsCache(client)), ranker, dispatcher) to indexers.map { it.config }
    }

    private fun matches(fake: FakeIndexer, l: Listing, p: io.ktor.http.Parameters): Boolean {
        fun param(name: String) = p[name]?.takeIf { p["t"] != "tvsearch" || name in fake.tvParams.orEmpty().split(",") }
        val words = param("q")?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        val titleWords = l.title.lowercase().split(Regex("[^a-z0-9]+")).toSet()
        val airDate = param("ep")?.takeIf { '/' in it }?.let { ep ->
            val (month, day) = ep.split('/').map { it.toInt() }
            LocalDate.of(param("season")!!.toInt(), month, day)
        }
        val rightEpisode = if (airDate != null) {
            ReleaseParser.parse(l.title).airDate == airDate
        } else {
            (param("season")?.let { it.toInt() == l.season } ?: true) && (param("ep")?.let { it.toInt() == l.episode } ?: true)
        }
        return words.all { it in titleWords } &&
            (param("tvdbid")?.let { it.toInt() == l.tvdb } ?: true) &&
            (param("imdbid")?.let { "tt$it" == l.imdb } ?: true) &&
            rightEpisode
    }

    private fun caps(fake: FakeIndexer) = """
        <caps><searching>
          <search available="yes" supportedParams="q"/>
          <tv-search available="${if (fake.tvParams != null) "yes" else "no"}" supportedParams="${fake.tvParams.orEmpty()}"/>
        </searching></caps>
    """.trimIndent()

    private fun rss(listings: List<Listing>) = buildString {
        append("""<rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>""")
        listings.forEach { l ->
            val guid = l.guid ?: l.title.hashCode().toUInt().toString(16)
            append("<item><title>${l.title}</title><guid>$guid</guid>")
            append("""<newznab:attr name="size" value="${if (l.season != null && l.episode == null) 30_000_000_000 else 2_000_000_000}"/>""")
            l.tvdb?.let { append("""<newznab:attr name="tvdbid" value="$it"/>""") }
            append("</item>")
        }
        append("</channel></rss>")
    }

    private fun find(vararg indexers: FakeIndexer, req: EpisodeRequest = request, remembered: RememberedPick? = null): FindResult = runBlocking {
        val (finder, configs) = finder(*indexers)
        assertIs<FinderEvent.Finished>(finder.find(req, configs, context, remembered).last()).result
    }

    private fun FindResult.titles() = playable.map { it.item.title }

    private val e02 = Listing("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP", tvdb = 900001, imdb = "tt9000003", season = 1, episode = 2)
    private val pack = Listing("Harbor.Lights.S01.1080p.WEB-DL.H.264-PACK", tvdb = 900001, imdb = "tt9000003", season = 1)

    @Test
    fun `searches by tvdb id where supported and reports it`() {
        val result = find(FakeIndexer("a.example", "q,tvdbid,imdbid,season,ep", listOf(e02)))
        assertEquals(listOf(e02.title), result.titles())
        val query = requests.single { it.url.parameters["ep"] != null }.url.parameters
        assertEquals("900001", query["tvdbid"])
        assertEquals("1", query["season"])
        assertEquals("2", query["ep"])
        assertEquals(null, query["imdbid"])
        assertEquals("TVDB id", result.stages.first().indexers.single().by)
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack), result.stages.map { it.stage })
    }

    @Test
    fun `falls back to imdb id, then to the name`() {
        val result = find(
            FakeIndexer("imdb.example", "q,imdbid,season,ep", listOf(e02)),
            FakeIndexer("name.example", "q,season,ep", listOf(e02.copy(title = "Harbor.Lights.S01E02.720p.HDTV.x264-OTHER", tvdb = null))),
        )
        assertEquals(2, result.playable.size)
        val byHost = result.stages.first().indexers.associate { it.indexer.id to it.by }
        assertEquals(mapOf("imdb.example" to "IMDb id", "name.example" to "name"), byHost)
        assertEquals("0012345".length, requests.first { it.url.host == "imdb.example" && it.url.parameters["t"] == "tvsearch" }.url.parameters["imdbid"]!!.length)
    }

    @Test
    fun `an untagged release speaks the show's original language`() {
        val listings = listOf(e02, Listing("Harbor.Lights.S01E02.ENGLISH.720p.WEB-DL.H.264-DUB", tvdb = 900001, season = 1, episode = 2))
        val indexer = FakeIndexer("a.example", "q,tvdbid,season,ep", listings)
        // English is preferred: the dub beats the untagged release of a Korean show, which an unknown original language takes for English.
        assertEquals(listOf("Harbor.Lights.S01E02.ENGLISH.720p.WEB-DL.H.264-DUB", e02.title), find(indexer, req = request.copy(show = show.copy(originalLanguage = "ko"))).titles())
        assertEquals(listOf(e02.title, "Harbor.Lights.S01E02.ENGLISH.720p.WEB-DL.H.264-DUB"), find(indexer).titles())
    }

    @Test
    fun `name searches only accept the right show and year`() {
        val listings = listOf(
            Listing("Harbor.Lights.S01E02.1080p.WEB.H264-OK", season = 1, episode = 2),
            Listing("Harbor.Lights.2021.S01E02.720p.WEB.H264-OKYEAR", season = 1, episode = 2),
            Listing("Harbor.Lights.1998.S01E02.720p.HDTV.x264-REMAKE", season = 1, episode = 2),
            Listing("Harbor.Lights.Revisited.S01E02.720p.WEB.H264-SPINOFF", season = 1, episode = 2),
        )
        val result = find(FakeIndexer("name.example", "q,season,ep", listings))
        assertEquals(setOf("Harbor.Lights.S01E02.1080p.WEB.H264-OK", "Harbor.Lights.2021.S01E02.720p.WEB.H264-OKYEAR"), result.titles().toSet())
        assertEquals(4 to 2, result.stages.first().found to result.stages.first().accepted)
    }

    @Test
    fun `without tv-search the episode is searched as text`() {
        find(FakeIndexer("text.example", null, listOf(e02)))
        assertEquals(setOf("Harbor Lights S01E02", "Harbor Lights S01"), requests.filter { it.url.parameters["t"] == "search" }.map { it.url.parameters["q"] }.toSet())
    }

    @Test
    fun `text searches write the episode in ascii digits whatever the device locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        val e03 = e02.copy(title = "Harbor.Lights.S05E03.1080p.WEB-DL.H.264-GRP", season = 5, episode = 3)
        val result = try {
            find(FakeIndexer("text.example", null, listOf(e03)), req = request.copy(season = 5, episode = 3))
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals(setOf("Harbor Lights S05E03", "Harbor Lights S05"), requests.filter { it.url.parameters["t"] == "search" }.map { it.url.parameters["q"] }.toSet())
        assertEquals(listOf(e03.title), result.titles())
    }

    @Test
    fun `wrong episodes and other shows' ids are rejected even from id searches`() {
        // An indexer that ignores ep= and one that mislabels another show with ids attached.
        val sloppy = FakeIndexer(
            "sloppy.example", "q,tvdbid,season",
            listOf(e02, e02.copy(title = "Harbor.Lights.S01E03.1080p.WEB-DL.H.264-GRP", episode = 3)),
        )
        val result = find(sloppy, FakeIndexer("other.example", "q,season,ep", listOf(e02.copy(title = "Harbor.Lights.S01E02.REPACK.720p.WEB.H264-X", tvdb = 123))))
        assertEquals(listOf(e02.title), result.titles())
    }

    @Test
    fun `season packs are searched alongside the episode, also when a single episode plays`() {
        val withSingle = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02, pack)))
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack), withSingle.stages.map { it.stage })
        assertEquals(listOf(e02.title, pack.title), withSingle.titles())
        assertEquals(SearchStage.SeasonPack, withSingle.playable.last().stage)
        assertEquals(listOf("2", null), requests.filter { it.url.parameters["t"] == "tvsearch" }.map { it.url.parameters["ep"] }.sortedBy { it == null })

        requests.clear()
        val packOnly = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(pack)))
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack), packOnly.stages.map { it.stage })
        assertEquals(listOf(pack.title), packOnly.titles())
        assertEquals(SearchStage.SeasonPack, packOnly.playable.single().stage)
    }

    @Test
    fun `the season search is asked while the episode search is still out`() = runBlocking<Unit> {
        // The indexer answers the episode search only once the season search has reached it.
        val seasonAsked = CompletableDeferred<Unit>()
        val indexer = FakeIndexer("a.example", "q,tvdbid,season,ep", emptyList())
        val http = HttpClient(MockEngine { request ->
            val p = request.url.parameters
            val body = when {
                p["t"] == "caps" -> caps(indexer)
                p["ep"] != null -> {
                    withTimeoutOrNull(5_000) { seasonAsked.await() } ?: error("the season search wasn't asked while the episode search was out")
                    rss(listOf(e02))
                }
                else -> {
                    seasonAsked.complete(Unit)
                    rss(listOf(pack))
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/xml"))
        }) {
            install(HttpTimeout)
            expectSuccess = false
        }
        val client = NewznabClient(http)
        val finder = EpisodeReleaseFinder(MultiIndexerSearch(client, CapsCache(client)))
        val result = assertIs<FinderEvent.Finished>(finder.find(request, listOf(indexer.config), context).last()).result
        assertEquals(listOf(e02.title, pack.title), result.titles())
        assertEquals(listOf(1, 1), result.stages.map { it.accepted })
    }

    @Test
    fun `a pack's bitrate is estimated per episode of the season the request counts`() {
        // The fake indexer's packs are 30 GB: 3 GB an episode of a 10-episode season.
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(pack)), req = request.copy(seasonEpisodes = 10))
        assertEquals(30_000_000_000 * 8 / (10 * 45 * 60L), result.playable.single().ranked.estimatedBitrate)
        assertEquals(null, find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(pack))).playable.single().ranked.estimatedBitrate)
    }

    @Test
    fun `season packs are searched when no single episode can play on this device`() {
        // The device has no 10-bit decoder; the episode exists only in 10-bit HEVC, the season also as an x264 pack.
        val uhd = e02.copy(title = "Harbor.Lights.S01E02.2160p.WEB.H265.10bit-UHD")
        val x265 = e02.copy(title = "Harbor.Lights.S01E02.1080p.BluRay.x265.10bit-X")
        val x264Pack = pack.copy(title = "Harbor.Lights.S01.1080p.BluRay.x264-C")
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(uhd, x265, x264Pack)))
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack), result.stages.map { it.stage })
        assertEquals(listOf(x264Pack.title), result.titles())
        assertEquals(setOf(uhd.title, x265.title), result.hidden.map { it.item.title }.toSet())
    }

    @Test
    fun `packs and single episodes are ranked together, a single first at equal standing`() {
        // On this 1080p device the 1080p pack beats the 720p single, and the 1080p single the pack.
        val worse = e02.copy(title = "Harbor.Lights.S01E02.720p.HDTV.x264-B")
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(pack, worse, e02)))
        assertEquals(listOf(e02.title, pack.title, worse.title), result.titles())
    }

    @Test
    fun `the season search keeps the episode itself, e g when the episode search failed`() {
        // The indexer fails the episode search, then answers the season search with the season's episodes.
        val e03 = e02.copy(title = "Harbor.Lights.S01E03.1080p.WEB-DL.H.264-GRP", episode = 3)
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02, e03), failWith = 900, failsOn = { it["ep"] != null }))
        assertEquals(listOf(e02.title), result.titles())
        assertEquals(SearchStage.SeasonPack, result.playable.single().stage)
    }

    @Test
    fun `the season search keeps a daily show's episode by air date`() {
        // TMDB numbers the episode 45; the indexer files the date-named release under the year with another number,
        // and fails the search by air date.
        val daily = Listing("Morning.Ledger.2026.10.12.720p.WEB.h264-GRP", season = 2026, episode = 1012)
        val req = EpisodeRequest(ShowRef("Morning Ledger"), season = 2026, episode = 45, airDate = LocalDate.of(2026, 10, 12))
        val result = find(FakeIndexer("a.example", "q,season,ep", listOf(daily), failWith = 900, failsOn = { "/" in it["ep"].orEmpty() }), req = req)
        assertEquals(listOf(daily.title), result.titles())
        assertEquals(listOf(SearchStage.Episode, SearchStage.AirDate, SearchStage.SeasonPack), result.stages.map { it.stage })
    }

    @Test
    fun `name search runs when id searches find nothing, e g numbering differences`() {
        // The indexer has the episode, but not linked to the show's ids.
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02.copy(tvdb = null))))
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack, SearchStage.ByName), result.stages.map { it.stage })
        assertEquals(SearchStage.ByName, result.playable.single().stage)
    }

    @Test
    fun `nothing found explains every stage and failing indexers`() {
        val result = find(
            FakeIndexer("a.example", "q,tvdbid,season,ep", emptyList()),
            FakeIndexer("limit.example", "q,tvdbid,season,ep", emptyList(), failWith = 500),
        )
        assertEquals(emptyList(), result.playable)
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack, SearchStage.ByName), result.stages.map { it.stage })
        val failed = result.stages.first().indexers.single { it.indexer.id == "limit.example" }
        assertIs<NewznabError.ApiLimitReached>(failed.error)
    }

    @Test
    fun `releases this device can't play are hidden, not dropped`() {
        val tenBit = e02.copy(title = "Harbor.Lights.S01E02.2160p.WEB.H265.10bit-UHD")
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02, tenBit)))
        assertEquals(listOf(e02.title), result.titles())
        assertEquals(listOf(tenBit.title), result.hidden.map { it.item.title })
    }

    @Test
    fun `remembered release goes first`() {
        val better = e02.copy(title = "Harbor.Lights.S01E02.PROPER.1080p.WEB-DL.H.264-BETTER")
        val remembered = RememberedPick("a.example", e02.title.hashCode().toUInt().toString(16), e02.title, null)
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(better, e02)), remembered = remembered)
        assertEquals(listOf(e02.title, better.title), result.titles())
        assertTrue(result.playable.first().remembered)
    }

    /**
     * The remembered release is matched by its key ([RememberedPick.key], a search result's [FoundRelease.key]), not by
     * the indexer and guid it names: a torrent's key is its hash, whichever indexer lists it now.
     */
    @Test
    fun `the remembered release is promoted by its key`() {
        val better = e02.copy(title = "Harbor.Lights.S01E02.PROPER.1080p.WEB-DL.H.264-BETTER")
        val indexer = FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(better, e02))
        val guid = e02.title.hashCode().toUInt().toString(16)
        val byKey = RememberedPick("b.example", "elsewhere", e02.title, null, key = "a.example/$guid")
        val promoted = find(indexer, remembered = byKey)
        assertEquals(listOf(e02.title, better.title), promoted.titles())
        assertTrue(promoted.playable.first().remembered)
        // Its indexer and guid, under another key, aren't it.
        val otherKey = RememberedPick("a.example", guid, e02.title, null, key = "btih:0123456789abcdef0123456789abcdef01234567")
        val notPromoted = find(indexer, remembered = otherKey)
        assertEquals(listOf(better.title, e02.title), notPromoted.titles())
        assertTrue(notPromoted.playable.none { it.remembered })
        // A pick made without one has its indexer's and guid's.
        assertEquals("a.example/$guid", RememberedPick("a.example", guid, e02.title, null).key)
    }

    @Test
    fun `releases whose guid urls carry the id in the query stay apart`() {
        // An indexer through Prowlarr: every guid is a details.php?guid=<hash> url, with no guid attr.
        val (web, hdtv) = listOf(e02, e02.copy(title = "Harbor.Lights.S01E02.720p.HDTV.x264-SANDBAR")).map { l ->
            l.copy(guid = "https://indexer.example/details.php?guid=${l.title.hashCode().toUInt().toString(16)}")
        }
        val remembered = RememberedPick("indexer.example", hdtv.title.hashCode().toUInt().toString(16), hdtv.title, null)
        val result = find(FakeIndexer("indexer.example", "q,tvdbid,season,ep", listOf(web, hdtv)), remembered = remembered)
        assertEquals(listOf(hdtv.title, web.title), result.titles())
        assertTrue(result.playable.first().remembered)
    }

    // A talk show TMDB numbers S11E50; its releases are named by the date it aired.
    private val ledger = ShowRef("Morning Ledger", 2015, ids = TvIds(tvdb = 900101, tmdb = 900102))
    private val dailyRequest = EpisodeRequest(ledger, season = 11, episode = 50, airDate = LocalDate.of(2026, 10, 12), runtimeMinutes = 45)
    private val daily = Listing("Morning.Ledger.2026.10.12.Guest.1080p.WEB.h264-GRP", tvdb = 900101)
    private val otherDay = Listing("Morning.Ledger.2026.10.13.Guest.1080p.WEB.h264-GRP", tvdb = 900101)

    @Test
    fun `daily shows are found by air date, by id where the indexer takes it`() {
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(daily, otherDay)), req = dailyRequest)
        assertEquals(listOf(daily.title), result.titles())
        assertEquals(listOf(SearchStage.Episode, SearchStage.AirDate, SearchStage.SeasonPack), result.stages.map { it.stage })
        assertEquals(SearchStage.AirDate, result.playable.single().stage)
        val query = requests.single { "/" in it.url.parameters["ep"].orEmpty() }.url.parameters
        assertEquals(listOf("900101", "2026", "10/12"), listOf(query["tvdbid"], query["season"], query["ep"]))
        assertEquals("TVDB id", result.stages.last().indexers.single().by)
    }

    @Test
    fun `daily shows are found by air date and name on indexers without ids`() {
        val result = find(FakeIndexer("a.example", "q,season,ep", listOf(daily.copy(tvdb = null), otherDay.copy(tvdb = null))), req = dailyRequest)
        assertEquals(listOf(daily.title), result.titles())
        val query = requests.single { "/" in it.url.parameters["ep"].orEmpty() }.url.parameters
        assertEquals(listOf("Morning Ledger", "2026", "10/12"), listOf(query["q"], query["season"], query["ep"]))
    }

    @Test
    fun `without tv-search a daily episode is searched as text with the date in ascii digits whatever the device locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        val result = try {
            find(FakeIndexer("text.example", null, listOf(daily.copy(tvdb = null), otherDay.copy(tvdb = null))), req = dailyRequest)
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals(listOf(daily.title), result.titles())
        assertTrue(requests.any { it.url.parameters["t"] == "search" && it.url.parameters["q"] == "Morning Ledger 2026 10 12" })
    }

    @Test
    fun `a show its indexer finds by season and episode sends no date query`() {
        val result = find(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02)), req = request.copy(airDate = LocalDate.of(2021, 3, 4)))
        assertEquals(listOf(e02.title), result.titles())
        assertEquals(listOf(SearchStage.Episode, SearchStage.SeasonPack), result.stages.map { it.stage })
        assertTrue(requests.none { "/" in it.url.parameters["ep"].orEmpty() || "2021 03 04" in it.url.parameters["q"].orEmpty() })
    }

    @Test
    fun `progress events per indexer`() = runBlocking<Unit> {
        val (finder, configs) = finder(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02)), FakeIndexer("b.example", "q,tvdbid,season,ep", emptyList()))
        val events = finder.find(request, configs, context).toList()
        assertEquals(FinderEvent.StageStarted(SearchStage.Episode, 2), events.first())
        assertEquals(2, events.filterIsInstance<FinderEvent.IndexerAnswered>().count { it.stage == SearchStage.Episode })
    }

    /** Each answer says which releases it accepted: the season's search accepts the episode the episode's search did, and the pack. */
    @Test
    fun `each answer names the releases it accepted`() = runBlocking<Unit> {
        val (finder, configs) = finder(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02, pack)))
        val events = finder.find(request, configs, context).toList()
        val keys = assertIs<FinderEvent.Finished>(events.last()).result.playable.associate { it.item.title to it.key }
        assertEquals(
            mapOf(SearchStage.Episode to setOf(keys.getValue(e02.title)), SearchStage.SeasonPack to setOf(keys.getValue(e02.title), keys.getValue(pack.title))),
            events.filterIsInstance<FinderEvent.IndexerAnswered>().associate { it.stage to it.acceptedKeys.toSet() },
        )
    }

    // Torrent indexers whose answers are checked against a debrid service's cache (L199, L200), on a virtual clock.

    private val usenetA = IndexerConfig("indexer-a", "Indexer A", "https://a.invalid/api", "k")
    private val prowlarr = IndexerConfig("prowlarr", "Home Prowlarr", "https://p.invalid/api", "k", kind = IndexerKind.Torznab)
    private val quarry = IndexerConfig("quarry", "Quarry", "https://q.invalid/api", "k", kind = IndexerKind.Torznab, useWithService = false)
    private val tvCaps = IndexerCaps(searching = mapOf(SearchType.Tv to setOf("q", "tvdbid", "season", "ep")))

    private fun listed(indexer: IndexerConfig, title: String, hash: String? = null) = NewznabItem(
        indexerId = indexer.id, guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 2_000_000_000, publishedAt = null,
        postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        infoHash = hash,
    )

    /** Indexers that each list [episodes] to the episode's search, [after] ms after it starts, and nothing to the season's. */
    private class ListingSearch(private val caps: IndexerCaps, private val episodes: Map<String, List<NewznabItem>>, private val after: Map<String, Long> = emptyMap()) : ReleaseSearch {
        override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = channelFlow {
            val query = plan(caps)!!
            val listing = (query as SearchQuery.Tv).episode != null
            indexers.forEach { indexer ->
                launch {
                    delay(after[indexer.id] ?: 0)
                    val items = if (listing) episodes[indexer.id].orEmpty() else emptyList()
                    send(IndexerOutcome.Results(indexer, SearchPage(items, 0, null, null), query))
                }
            }
        }
    }

    private fun TestScope.events(search: ReleaseSearch, indexers: List<IndexerConfig>): List<Pair<Long, FinderEvent>> {
        val finder = EpisodeReleaseFinder(search, defaultDispatcher = StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Pair<Long, FinderEvent>>()
        launch { finder.find(request, indexers, context).collect { seen += testScheduler.currentTime to it } }
        testScheduler.advanceUntilIdle()
        return seen
    }

    private val good = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
    private val goodHash = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"

    /** L200: listed by two torrent indexers, a torrent is one release, from the one whose switch is on, though the other answered first. */
    @Test
    fun `one torrent from two indexers is one release, from the indexer that uses the service`() = runTest {
        val search = CheckedSearch(
            ListingSearch(tvCaps, mapOf("quarry" to listOf(listed(quarry, good, goodHash)), "prowlarr" to listOf(listed(prowlarr, good, goodHash))), after = mapOf("prowlarr" to 50L)),
        ) { indexer, items -> flow { emit(items.map { it.copy(availability = if (indexer.useWithService) Availability.Cached(listOf("torbox-1")) else Availability.NotForService) }) } }
        val last = events(search, listOf(quarry, prowlarr)).last().second
        val result = (last as? FinderEvent.Joined)?.result ?: assertIs<FinderEvent.Finished>(last).result
        val found = (result.playable + result.hidden).single()
        assertEquals("prowlarr" to Availability.Cached(listOf("torbox-1")), found.item.indexerId to found.item.availability)
    }

    /**
     * W71: the list never waits for the check. The finder is done once every indexer has answered, a torrent indexer at its
     * Checking, and its torrents join as they settle.
     */
    @Test
    fun `Finished doesn't wait for a held check, and Joined brings its torrents`() = runTest {
        val search = CheckedSearch(
            ListingSearch(tvCaps, mapOf("indexer-a" to listOf(listed(usenetA, good)), "prowlarr" to listOf(listed(prowlarr, good, goodHash)))),
        ) { _, items -> flow { delay(3_000); emit(items.map { it.copy(availability = Availability.Cached(listOf("torbox-1"))) }) } }
        val events = events(search, listOf(usenetA, prowlarr))
        val (finishedAt, finished) = events.single { it.second is FinderEvent.Finished }
        assertEquals(0L, finishedAt)
        assertEquals(listOf("indexer-a"), assertIs<FinderEvent.Finished>(finished).result.playable.map { it.item.indexerId })
        assertEquals(2, events.count { (it.second as? FinderEvent.IndexerAnswered)?.stage == SearchStage.Episode }, "both answered the episode's search")
        val (joinedAt, joined) = events.last()
        assertEquals(3_000L, joinedAt)
        val all = assertIs<FinderEvent.Joined>(joined).result
        assertEquals(setOf("indexer-a", "prowlarr"), (all.playable + all.hidden).map { it.item.indexerId }.toSet())
    }

    /** Play's automatic pick, which shows no list, takes the finder's last word: it waits for a check still running, at most 10 s. */
    @Test
    fun `the finder's last word waits for a held check up to 10 s, its torrents then not checked`() = runTest {
        val search = CheckedSearch(
            ListingSearch(tvCaps, mapOf("indexer-a" to listOf(listed(usenetA, good)), "prowlarr" to listOf(listed(prowlarr, good, goodHash)))),
        ) { _, _ -> flow { awaitCancellation() } }
        val (lastAt, last) = events(search, listOf(usenetA, prowlarr)).last()
        assertEquals(10_000L, lastAt)
        val result = assertIs<FinderEvent.Joined>(last).result
        val torrent = (result.playable + result.hidden).single { it.item.indexerId == "prowlarr" }
        assertEquals(Availability.NotChecked, torrent.item.availability)
        // Ranked as a torrent no service said it has: hidden, never tried (W71).
        assertEquals(listOf("indexer-a"), result.playable.map { it.item.indexerId })
    }

    @Test
    fun `collecting on the main thread asks the indexers and ranks off it`() {
        val rankedOn = ConcurrentLinkedQueue<String>()
        val ranker = ReleaseRanker { title -> rankedOn += Thread.currentThread().name.substringBefore(" @"); ReleaseParser.parse(title) }
        Executors.newSingleThreadExecutor { Thread(it, "finder") }.asCoroutineDispatcher().use { work ->
            val (finder, configs) = finder(FakeIndexer("a.example", "q,tvdbid,season,ep", listOf(e02)), ranker = ranker, dispatcher = work)
            val result = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher().use { main ->
                runBlocking(main) { assertIs<FinderEvent.Finished>(finder.find(request, configs, context).last()).result }
            }
            assertEquals(listOf(e02.title), result.titles())
            assertEquals(setOf("finder"), askedOn.toSet())
            assertEquals(setOf("finder"), rankedOn.toSet())
        }
    }
}

package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.casting.CastModel
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The movie page's search is the Search tab's for a picked movie: by id where it can, the name checked where it can't. */
class MovieFindTest {
    private val copperCrown = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118)
    /** No 10-bit decoding: a 10-bit HEVC release is hidden. */
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))
    private val a = IndexerConfig("a", "Indexer A", "http://a.invalid", "key-a")
    private val b = IndexerConfig("b", "Indexer B", "http://b.invalid", "key-b")
    private val c = IndexerConfig("c", "Indexer C", "http://c.invalid", "key-c")
    private val d = IndexerConfig("d", "Indexer D", "http://d.invalid", "key-d")

    private fun item(indexer: IndexerConfig, title: String, size: Long = 8_000_000_000) = NewznabItem(
        indexerId = indexer.id, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    private fun results(indexer: IndexerConfig, query: SearchQuery, vararg titles: String) =
        IndexerOutcome.Results(indexer, SearchPage(titles.map { item(indexer, it) }, offset = 0, total = null, limits = null), query)

    @Test
    fun `the movie's releases from every indexer are one group, those found by name only when they are the movie`() {
        val find = MovieFind.of(
            copperCrown,
            listOf(
                // By its IMDb id: whatever the indexer calls them, they are the movie.
                results(a, SearchQuery.Movie(imdb = "tt9000012"), "Copper.Crown.2019.1080p.BluRay.x264-GOOD", "CC.2019.720p.WEB.h264-SHORT"),
                // By name: only the ones named for it.
                results(b, SearchQuery.Text("Copper Crown 2019"), "Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED", "Brass.Crown.2019.1080p.WEB-DL.H.264-OTHER", "CC.2019.720p.WEB.h264-SHORT"),
            ),
            context,
        )
        val group = assertNotNull(find.group)
        assertEquals(GroupKind.Movie, group.kind)
        assertEquals(
            setOf("a/Copper.Crown.2019.1080p.BluRay.x264-GOOD", "a/CC.2019.720p.WEB.h264-SHORT", "b/Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED"),
            find.playable.map { it.key }.toSet(),
        )
        assertEquals(listOf(IndexerStatus.State.Answered(2), IndexerStatus.State.Answered(1)), find.statuses.map { it.state })
    }

    @Test
    fun `the movie's untagged releases speak its original language`() {
        val outcomes = listOf(results(a, SearchQuery.Movie(imdb = "tt9000042"), "Moonwell.Village.2021.1080p.BluRay.x264-KO", "Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-DUB"))
        val moonwell = MovieTarget(900042, "Moonwell Village", 2021, "tt9000042", 118, originalLanguage = "ko")
        // English is preferred: the dub beats the untagged release of a Korean film, which an unknown original language takes for English.
        assertEquals("a/Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-DUB", MovieFind.of(moonwell, outcomes, context).playable.first().key)
        assertEquals("a/Moonwell.Village.2021.1080p.BluRay.x264-KO", MovieFind.of(moonwell.copy(originalLanguage = null), outcomes, context).playable.first().key)
    }

    /**
     * With a maximum bitrate, a movie's releases are estimated over its runtime from TMDB: a 38 GB remux of a 165-minute
     * film is about 31 Mbps and fits 40 Mbps, so it goes first; one of 30 GB of an 85-minute film is about 47 Mbps and doesn't.
     */
    @Test
    fun `a movie's releases are estimated over its runtime from TMDB`() {
        val upTo40 = context.copy(prefs = RankingPrefs(maxBitrate = 40_000_000))
        fun found(runtime: Int, remux: Long) = MovieFind.of(
            copperCrown.copy(runtimeMinutes = runtime),
            listOf(
                IndexerOutcome.Results(
                    a,
                    SearchPage(
                        listOf(item(a, "Copper.Crown.2019.1080p.BluRay.REMUX.AVC-GRP", size = remux), item(a, "Copper.Crown.2019.1080p.WEB-DL.H.264-GRP", size = 10_000_000_000)),
                        offset = 0, total = null, limits = null,
                    ),
                    SearchQuery.Movie(imdb = "tt9000012"),
                ),
            ),
            upTo40,
        ).playable
        val long = found(runtime = 165, remux = 38_000_000_000)
        assertEquals("a/Copper.Crown.2019.1080p.BluRay.REMUX.AVC-GRP", long.first().key)
        assertEquals(emptyList(), long.flatMap { it.ranked.reasons }.filterIsInstance<ReleaseReason.AboveMaxBitrate>())
        val short = found(runtime = 85, remux = 30_000_000_000)
        assertEquals("a/Copper.Crown.2019.1080p.WEB-DL.H.264-GRP", short.first().key)
        assertEquals(listOf(ReleaseReason.AboveMaxBitrate(40)), short.last().ranked.reasons.filterIsInstance<ReleaseReason.AboveMaxBitrate>())
    }

    /**
     * L127: a find says whom it was ranked for, the Cast device by the name its "Ranked for" line gives; and the same
     * releases rank again for another as they would have been found for it, no indexer asked (it keeps no outcomes to ask
     * with). A DTS-only remux is hidden here (no DTS decoder), and tried last on Kitchen display, warned.
     */
    @Test
    fun `a movie's find says whom it is ranked for, and ranks its releases again for another`() {
        val kitchen = context.copy(device = CastModel.Chromecast.profile(null), target = RankingTarget.Cast("Kitchen display"))
        val outcomes = listOf(
            results(a, SearchQuery.Movie(imdb = "tt9000012"), "Copper.Crown.2019.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", "Copper.Crown.2019.1080p.BluRay.DD5.1.x264-GOOD"),
            IndexerOutcome.Failed(c, NewznabError.Timeout("Timed out")),
        )
        val here = MovieFind.of(copperCrown, outcomes, context)
        assertNull(here.rankedFor)
        assertEquals(listOf("a/Copper.Crown.2019.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"), here.hidden.map { it.key })

        val cast = here.rankedBy(kitchen)
        assertEquals("Kitchen display", cast.rankedFor)
        assertEquals(MovieFind.of(copperCrown, outcomes, kitchen), cast, "as if found for it")
        assertEquals(
            listOf("a/Copper.Crown.2019.1080p.BluRay.DD5.1.x264-GOOD", "a/Copper.Crown.2019.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"),
            cast.playable.map { it.key },
        )
        assertEquals(listOf(ReleaseReason.NoSoundOn("DTS-HD MA", "Kitchen display")), cast.playable.last().ranked.reasons.filterIsInstance<ReleaseReason.NoSoundOn>())
        assertEquals(here.statuses, cast.statuses, "what each indexer answered stays")
        // And back for this phone: as found here.
        assertEquals(here, cast.rankedBy(context))
    }

    /** What Search and the movie page take of [outcome], a search for [title]. */
    private fun taken(outcome: IndexerOutcome, title: ResultGrouper.KnownTitle?) = Answers(title).apply { take(outcome) }.accepted

    @Test
    fun `an indexer answers alike on Search and on the movie page`() {
        val known = ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false)
        val byName = listOf(SearchQuery.Text("Copper Crown 2019"), SearchQuery.Movie(query = "Copper Crown"), SearchQuery.Tv(query = "Copper Crown"))
        val byId = listOf(SearchQuery.Movie(imdb = "tt9000012"), SearchQuery.Movie(tmdb = 900011), SearchQuery.Tv(ids = TvIds(tvdb = 900001)))
        val titles = arrayOf("Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED", "Brass.Crown.2019.1080p.WEB-DL.H.264-OTHER")
        byName.forEach { q -> assertEquals(listOf(titles[0]), taken(results(a, q, *titles), known).map { it.title }, "$q") }
        byId.forEach { q -> assertEquals(titles.toList(), taken(results(a, q, *titles), known).map { it.title }, "$q") }
        // Typed text has no title to hold the results to.
        assertEquals(titles.toList(), taken(results(a, SearchQuery.Text("crown"), *titles), null).map { it.title })
    }

    @Test
    fun `nothing playable leaves no candidates, the hidden ones kept, and each indexer's line says what it did`() {
        val find = MovieFind.of(
            copperCrown,
            listOf(
                results(a, SearchQuery.Movie(imdb = "tt9000012")),
                results(b, SearchQuery.Movie(imdb = "tt9000012"), "Copper.Crown.2019.2160p.WEB.H265.10bit.HDR.DDP5.1-TENBIT"),
                IndexerOutcome.Failed(c, NewznabError.Timeout("Timed out")),
                IndexerOutcome.Skipped(d, "Doesn't support this search"),
            ),
            context,
        )
        assertEquals(emptyList(), find.playable)
        assertEquals(listOf("b/Copper.Crown.2019.2160p.WEB.H265.10bit.HDR.DDP5.1-TENBIT"), find.hidden.map { it.key })
        assertEquals(
            listOf("Indexer A" to "No results", "Indexer B" to "1 result, none playable", "Indexer C" to "⚠ Timed out", "Indexer D" to "Doesn't support this search"),
            find.stages,
        )
        assertEquals("Searched 4 indexers · 1 found", find.searchLine)
    }

    @Test
    fun `no indexer answering anything gives no group`() {
        val find = MovieFind.of(copperCrown, listOf(results(a, SearchQuery.Movie(imdb = "tt9000012"))), context)
        assertNull(find.group)
        assertEquals("Searched 1 indexer · 0 found", find.searchLine)
        assertEquals(listOf("2 results, none playable", "No answer"), listOf(IndexerStatus.State.Answered(2), IndexerStatus.State.Searching).map { it.outcome() })
    }

    /** L213: a film's no-release sheet says of a torrent indexer whose releases none of the services has cached that none is. */
    @Test
    fun `a torrent indexer whose releases none of the services has cached says so on the no-release sheet`() {
        val prowlarr = IndexerConfig("p", "Home Prowlarr", "http://p.invalid", "key-p", kind = IndexerKind.Torznab)
        val byId = SearchQuery.Movie(imdb = "tt9000012")
        val torrents = listOf("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DRIFT", "Copper.Crown.2019.720p.HDTV.x264-DRIFT").mapIndexed { i, title ->
            item(prowlarr, title).copy(guid = "btih:${"%040x".format(i + 1)}", infoHash = "%040x".format(i + 1))
        }
        val find = MovieFind.of(
            copperCrown,
            listOf(
                results(a, byId),
                IndexerOutcome.Checking(prowlarr, byId, SearchPage(torrents, 0, null, null), waiting = 2),
                IndexerOutcome.Checked(prowlarr, torrents.map { it.copy(availability = Availability.NotCached) }, waiting = 0),
            ),
            context,
        )
        assertEquals(listOf("Indexer A" to "No results", "Home Prowlarr" to "2 found, none cached"), find.stages)
    }
}

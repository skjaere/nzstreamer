package io.skjaere.usenet.app.search

import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.GroupedResults
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchRequestTest {
    private val harborLights = TitleSuggestion(MediaType.Tv, 900002, "Harbor Lights", null, 2021, null, null)
    private val harborLightsInfo = TitleInfo(
        MediaType.Tv, 900002, "Harbor Lights", 2021, "TV series · 2021 · 2 seasons", null, null, null,
        TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"), 45,
    )
    private val timedOut = listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Failed("Timed out")))
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))

    private fun item(title: String, size: Long = 2_000_000_000) = NewznabItem(
        indexerId = "a", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    @Test
    fun `a picked title's search ranks its untagged releases in its original language`() {
        val movie = MovieDetails(900042, "Moonwell Village", "Dalsaem Maeul", 2021, null, null, null, 118, ExternalIds(imdb = "tt9000042"), originalLanguage = "ko")
        assertEquals(ResultGrouper.KnownTitle("Moonwell Village", 2021, isTv = false, originalLanguage = "ko", runtimeMinutes = 118), TitleInfo.of(movie).known)
        val show = ShowDetails(
            900040, "Moonwell Village", null, 2021, null, null, null, "Ended", false, 60, emptyList(), null, null, ExternalIds(), originalLanguage = "ko",
        )
        assertEquals(ResultGrouper.KnownTitle("Moonwell Village", 2021, isTv = true, originalLanguage = "ko", runtimeMinutes = 60), TitleInfo.of(show).known)
    }

    /**
     * With a maximum bitrate, a picked title's releases are estimated over its runtime from TMDB, as the search ranks them
     * (its ranking context and the title it groups them for): a movie's, a show's episodes'. A 1.8 GB episode of a
     * 22-minute sitcom is about 11 Mbps, above 10 Mbps, which over the 45 minutes taken by default it isn't.
     */
    @Test
    fun `a picked title's search estimates its releases over its runtime from TMDB`() {
        val base = context.copy(prefs = RankingPrefs(maxBitrate = 10_000_000))
        fun reasons(info: TitleInfo, vararg releases: Pair<String, Long>) =
            ResultGrouper().group(releases.map { (title, size) -> item(title, size) }, SearchRequest(info.name, title = info).rankingContext(base), info.known)
                .sections.single().groups.flatMap { it.playable }.associate { it.item.title to it.ranked.reasons.filterIsInstance<ReleaseReason.AboveMaxBitrate>() }
        val sitcom = ShowDetails(900041, "Brinemoor", null, 2020, null, null, null, "Ended", false, 22, emptyList(), null, null, ExternalIds())
        assertEquals(mapOf("Brinemoor.S01E05.1080p.WEB-DL.H.264-GRP" to listOf(ReleaseReason.AboveMaxBitrate(10))), reasons(TitleInfo.of(sitcom), "Brinemoor.S01E05.1080p.WEB-DL.H.264-GRP" to 1_800_000_000))
        assertEquals(mapOf("Brinemoor.S01E05.1080p.WEB-DL.H.264-GRP" to emptyList()), reasons(TitleInfo.of(sitcom.copy(episodeRuntimeMinutes = null)), "Brinemoor.S01E05.1080p.WEB-DL.H.264-GRP" to 1_800_000_000))
        // A 12 GB film of 165 minutes is about 9.7 Mbps; over the 110 taken by default it would be about 14.5.
        val film = MovieDetails(900043, "Long Haul", null, 2022, null, null, null, 165, ExternalIds(imdb = "tt9000043"))
        assertEquals(mapOf("Long.Haul.2022.1080p.BluRay.x264-GRP" to emptyList()), reasons(TitleInfo.of(film), "Long.Haul.2022.1080p.BluRay.x264-GRP" to 12_000_000_000))
    }

    @Test
    fun `trying a picked title's search again searches by that title`() {
        val failed = SearchRequest("Harbor Lights", harborLights).results(harborLightsInfo, timedOut, GroupedResults(emptyList()), done = true)
        assertTrue(failed.allFailed)
        assertEquals(SearchRequest("Harbor Lights", harborLights, harborLightsInfo), failed.retry)
    }

    @Test
    fun `a picked title that couldn't be looked up is looked up again on retry`() {
        val failed = SearchRequest("Harbor Lights", harborLights).results(null, timedOut, GroupedResults(emptyList()), done = true)
        assertEquals(SearchRequest("Harbor Lights", harborLights), failed.retry)
    }

    @Test
    fun `a free-text search is tried again as free text`() {
        val failed = SearchRequest("harbor lights s02e05").results(null, timedOut, GroupedResults(emptyList()), done = true)
        assertEquals(SearchRequest("harbor lights s02e05"), failed.retry)
    }

    @Test
    fun `searching a picked show without the episode searches the whole show by that title`() {
        val nothing = SearchRequest("Harbor Lights S02E05", harborLights)
            .results(harborLightsInfo, listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(0))), GroupedResults(emptyList()), done = true)
        assertTrue(nothing.nothingPlayable)
        assertEquals(SearchRequest("Harbor Lights", harborLights, harborLightsInfo), nothing.withoutEpisode)
        assertEquals(SearchRequest("harbor lights"), SearchRequest("harbor lights s02e05").results(null, timedOut, GroupedResults(emptyList()), done = true).withoutEpisode)
    }

    @Test
    fun `a name search for a show's episode keeps the show's language when tried again or without the episode`() {
        val nothing = SearchRequest("Moonwell Village s01e02", language = "ko")
            .results(null, listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(0))), GroupedResults(emptyList()), done = true)
        assertEquals(SearchRequest("Moonwell Village s01e02", language = "ko"), nothing.retry)
        assertEquals(SearchRequest("Moonwell Village", language = "ko"), nothing.withoutEpisode)
        assertEquals("ko", nothing.retry.rankingContext(context).originalLanguage)
        // Words typed in have none.
        assertNull(SearchRequest("Moonwell Village s01e02").rankingContext(context).originalLanguage)
    }

    @Test
    fun `a pasted release name is searched without the episode by the release's title`() {
        val nothing = SearchRequest("Harbor.Lights.S02E05.1080p.WEB.h264-GRP")
            .results(null, listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(0))), GroupedResults(emptyList()), done = true)
        assertEquals(SearchRequest("Harbor Lights"), nothing.withoutEpisode)
    }

    @Test
    fun `a new search shows none of the previous search's results, only its indexers searching again`() {
        val copperCrown = TitleSuggestion(MediaType.Movie, 900010, "Copper Crown", null, 2019, null, null)
        val copperCrownInfo = TitleInfo(MediaType.Movie, 900010, "Copper Crown", 2019, "Movie · 2019", null, null, null, TvIds(tmdb = 900010), 120)
        val previous = SearchRequest("Copper Crown", copperCrown).results(
            copperCrownInfo,
            listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(1)), IndexerStatus("b", "Indexer B", IndexerStatus.State.Failed("Timed out"))),
            ResultGrouper().group(listOf(item("Copper.Crown.2019.1080p.WEB-DL.H.264-GRP")), context, ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false)),
            done = true,
        )
        assertEquals(1, previous.grouped.playableCount)

        val next = assertNotNull(SearchRequest("Harbor Lights", harborLights).starting(previous))
        assertEquals("Harbor Lights", next.query)
        assertNull(next.title)
        assertTrue(next.grouped.isEmpty)
        assertFalse(next.done)
        assertEquals(
            listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Searching), IndexerStatus("b", "Indexer B", IndexerStatus.State.Searching)),
            next.statuses,
        )
    }

    @Test
    fun `the first search starts without indexers, and a retried title shows its card at once`() {
        val first = assertNotNull(SearchRequest("Harbor Lights", harborLights, harborLightsInfo).starting(null))
        assertEquals(harborLightsInfo, first.title)
        assertEquals(emptyList(), first.statuses)
        assertFalse(first.done)
    }

    @Test
    fun `a title kept from the first run isn't looked up again`() = runBlocking<Unit> {
        var lookups = 0
        val info = SearchRequest("Harbor Lights", harborLights, harborLightsInfo).titleInfo { lookups++; null }
        assertEquals(harborLightsInfo, info)
        assertEquals(0, lookups)
        assertEquals(harborLightsInfo, SearchRequest("Harbor Lights", harborLights).titleInfo { lookups++; harborLightsInfo })
        assertEquals(1, lookups)
    }
}

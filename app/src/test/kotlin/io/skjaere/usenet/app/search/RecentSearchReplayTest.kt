package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.MovieRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.page
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.request
import io.skjaere.usenet.app.route
import io.skjaere.usenet.data.RecentKind
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #122: a recent search picked from TMDB's suggestions replays by the title's id, and progress goes to the TMDB episode. */
class RecentSearchReplayTest {
    private val harborLights = TitleSuggestion(MediaType.Tv, 900002, "Harbor Lights", null, 2021, "/p.jpg", "A harbour town.")
    private val copperCrown = TitleSuggestion(MediaType.Movie, 900010, "Copper Crown", null, 2019, null, null)
    private val picked = RecentSearch(
        "harbor lights s02e05", "Harbor Lights S02E05", searchedAt = 10, updatedAt = 10,
        pick = SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5),
    )
    private val typed = RecentSearch("harbor lights s02e05", "harbor lights s02e05", searchedAt = 10, updatedAt = 10)

    @Test
    fun `a search picked from TMDB is remembered with the title, and the season and episode typed`() {
        assertEquals(SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5), SearchRequest("Harbor Lights S02E05", harborLights).recentPick())
        assertEquals(SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2), SearchRequest("Harbor Lights S02", harborLights).recentPick())
        assertEquals(SearchPick(TitleType.Tv, 900002, "Harbor Lights"), SearchRequest("Harbor Lights", harborLights).recentPick())
        assertEquals(SearchPick(TitleType.Movie, 900010, "Copper Crown"), SearchRequest("Copper Crown", copperCrown).recentPick())
        assertNull(SearchRequest("harbor lights s02e05").recentPick())
    }

    @Test
    fun `a recent search picked from TMDB replays by the picked title`() {
        assertEquals(SearchRequest("Harbor Lights S02E05", TitleSuggestion(MediaType.Tv, 900002, "Harbor Lights", null, null, null, null)), picked.request())
        val movie = RecentSearch("copper crown", "Copper Crown", 10, 10, pick = SearchPick(TitleType.Movie, 900010, "Copper Crown"))
        assertEquals(SearchRequest("Copper Crown", TitleSuggestion(MediaType.Movie, 900010, "Copper Crown", null, null, null, null)), movie.request())
    }

    @Test
    fun `a recent search typed as text, or saved before picks were remembered, replays as text`() {
        assertEquals(SearchRequest("harbor lights s02e05"), typed.request())
    }

    @Test
    fun `a chip on Home opens the search tab with the same search`() {
        assertEquals(picked.request(), picked.route().request())
        assertEquals(typed.request(), typed.route().request())
        // The Search tab itself, or a search typed elsewhere.
        assertNull(SearchRoute().request())
        assertEquals(SearchRequest("northbound"), SearchRoute("northbound").request())
    }

    /** A title opened from Search opens its page again, from Search's list or a chip on Home; a search runs again. */
    @Test
    fun `a recent title opens its page again, a recent search runs again`() {
        val show = RecentSearch("title:tv:900002", "Harbor Lights", 10, 10, pick = SearchPick(TitleType.Tv, 900002, "Harbor Lights"), kind = RecentKind.Title)
        val movie = RecentSearch("title:movie:900010", "Copper Crown", 10, 10, pick = SearchPick(TitleType.Movie, 900010, "Copper Crown"), kind = RecentKind.Title)
        assertEquals(SearchPick(TitleType.Tv, 900002, "Harbor Lights"), show.openedTitle)
        assertEquals(ShowRoute(900002), show.page())
        assertEquals(MovieRoute(900010), movie.page())
        // Searches, by a picked title's ids or of words: not a page, the Search tab.
        assertNull(picked.openedTitle)
        assertNull(picked.page())
        assertNull(typed.page())
    }

    @Test
    fun `a recent search says which kind it was`() {
        val show = RecentSearch("title:tv:900002", "Harbor Lights", 10, 10, pick = SearchPick(TitleType.Tv, 900002, "Harbor Lights"), kind = RecentKind.Title)
        val movie = RecentSearch("title:movie:900010", "Copper Crown", 10, 10, pick = SearchPick(TitleType.Movie, 900010, "Copper Crown"), kind = RecentKind.Title)
        assertEquals("TV series", show.kindLabel)
        assertEquals("Movie", movie.kindLabel)
        assertEquals("Indexer search", picked.kindLabel)
        assertEquals("Indexer search", typed.kindLabel)
    }

    @Test
    fun `playing an episode found by a replayed pick records progress against the TMDB episode, not the release`() {
        val info = TitleInfo(
            MediaType.Tv, 900002, "Harbor Lights", 2021, "TV series · 2021 · 2 seasons", null, null, null,
            TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"), 45,
        )
        val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))
        val item = NewznabItem(
            indexerId = "a", guid = "g", title = "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val statuses = listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(1)))

        val replayed = picked.request()
        val byPick = replayed.results(info, statuses, ResultGrouper().group(listOf(item), context, ResultGrouper.KnownTitle("Harbor Lights", 2021, isTv = true)), done = true)
        val group = byPick.grouped.sections.single().groups.single()
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5), playRequest(byPick, group, group.playable).watch)

        val asText = typed.request().results(null, statuses, ResultGrouper().group(listOf(item), context), done = true)
        val textGroup = asText.grouped.sections.single().groups.single()
        assertEquals(WatchKind.Release, playRequest(asText, textGroup, textGroup.playable).watch.kind)
    }
}

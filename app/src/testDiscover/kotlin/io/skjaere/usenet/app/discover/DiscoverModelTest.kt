package io.skjaere.usenet.app.discover

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.tv.PosterTone
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Period
import io.skjaere.usenet.discover.YearFilter
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/** Discover's cards and words. */
class DiscoverModelTest {
    private val harbor = DiscoverTitle(MediaType.Tv, 1, "Harbor Lights", 2021, null)
    private val copper = DiscoverTitle(MediaType.Movie, 1, "Copper Crown", null, null)

    @Test
    fun `a card shows its year, or the collection's mark in the Good tone, and TalkBack hears which`() {
        val (plain, collected) = cards(listOf(harbor, copper), setOf(TitleKey.movie(1)))
        assertEquals("show:1", plain.key)
        assertEquals("2021", plain.line)
        assertEquals(PosterTone.Muted, plain.tone)
        assertEquals("Harbor Lights, 2021, show", plain.spoken)
        // The same id on the other side is another title.
        assertEquals("movie:1", collected.key)
        assertEquals(IN_COLLECTION, collected.line)
        assertEquals(PosterTone.Good, collected.tone)
        assertEquals("Copper Crown, movie, in your collection", collected.spoken)
    }

    @Test
    fun `a See all page is called by its list and side`() {
        assertEquals("Trending · Shows", listTitle(DiscoverQuery(MediaType.Tv, DiscoverList.Trending)))
        assertEquals("Now playing · Movies", listTitle(DiscoverQuery(MediaType.Movie, DiscoverList.NowPlaying)))
    }

    @Test
    fun `the summary says what the list is narrowed to, and how many of it are collected`() {
        val trending = DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(genre = 18))
        assertEquals("Drama trending this week · 3 already in your collection", summary(trending, "Drama", null, null, 3))
        assertEquals("Trending today", summary(DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters(period = Period.Today)), null, null, null, 0))
        val movies = DiscoverQuery(MediaType.Movie, DiscoverList.TopRated, DiscoverFilters(genre = 35, language = "ko", year = YearFilter.Tens, service = 8))
        assertEquals("Top rated in Comedy · Korean · 2010s · on Service A", summary(movies, "Comedy", "Korean", "Service A", 0))
        assertEquals("Popular", summary(DiscoverQuery(MediaType.Movie, DiscoverList.Popular), null, null, null, 0))
    }

    @Test
    fun `failures say whether TMDB is down or busy`() {
        assertEquals(LOAD_FAILED, failureText(TmdbError.Network("down")))
        assertEquals(LOAD_FAILED, failureText(TmdbError.Server("500")))
        assertEquals(BUSY, failureText(TmdbError.RateLimited()))
    }

    @Test
    fun `an offline copy says how old it is`() {
        assertEquals("Offline · lists from 1 min ago", offlineLine(Duration.ofSeconds(20)))
        assertEquals("Offline · lists from 45 min ago", offlineLine(Duration.ofMinutes(45)))
        assertEquals("Offline · lists from 3 h ago", offlineLine(Duration.ofHours(3)))
        assertEquals("Offline · lists from yesterday", offlineLine(Duration.ofHours(30)))
        assertEquals("Offline · lists from 12 days ago", offlineLine(Duration.ofDays(12)))
    }

    @Test
    fun `the tab's rows without a title are left out, and a genre none has says so`() {
        val empty = DiscoverRow(DiscoverList.Popular, "Popular", RowView.Cards(emptyList()))
        val some = DiscoverRow(DiscoverList.TopRated, "Top rated", RowView.Cards(cards(listOf(harbor), emptySet())))
        val ui = DiscoverUi(KeyState.Ready, MediaType.Tv, emptyList(), 18, listOf(empty, some), artwork = true)
        assertEquals(listOf(some), ui.shownRows)
        assertEquals(false, ui.nothingMatches)
        assertEquals(true, ui.copy(rows = listOf(empty)).nothingMatches)
        // Without a genre an empty list is TMDB's, not the filter's.
        assertEquals(false, ui.copy(rows = listOf(empty), genre = null).nothingMatches)
    }

    @Test
    fun `every row failing is the page's failure`() {
        val failed = DiscoverRow(DiscoverList.Popular, "Popular", RowView.Failed(LOAD_FAILED))
        val loading = DiscoverRow(DiscoverList.TopRated, "Top rated", RowView.Loading)
        val ui = DiscoverUi(KeyState.Ready, MediaType.Tv, emptyList(), null, listOf(failed, loading), artwork = true)
        assertEquals(false, ui.allFailed)
        assertEquals(null, ui.failure)
        assertEquals(LOAD_FAILED, ui.copy(rows = listOf(failed, failed.copy(list = DiscoverList.TopRated))).failure)
    }

    @Test
    fun `a list's next step is due within a page of its end`() {
        assertEquals(false, nearEnd(lastShown = null, count = 0))
        assertEquals(false, nearEnd(lastShown = null, count = 20))
        assertEquals(false, nearEnd(lastShown = 19, count = 60))
        assertEquals(true, nearEnd(lastShown = 40, count = 60))
        assertEquals(true, nearEnd(lastShown = 59, count = 60))
        // The first screen of a page of 20 is within it.
        assertEquals(true, nearEnd(lastShown = 8, count = 20))
    }
}

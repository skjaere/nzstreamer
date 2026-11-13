package io.skjaere.usenet.app.discover

import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.MediaType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A See all page's route: names and ids, kept in saved state. */
class DiscoverRoutesTest {
    @Test
    fun `a See all page's route opens its list with the tab's genre`() {
        val query = DiscoverQuery(MediaType.Movie, DiscoverList.NowPlaying, DiscoverFilters(genre = 35))
        assertEquals(DiscoverListRoute("Movie", "NowPlaying", 35), DiscoverListRoute.of(query))
        assertEquals(query, DiscoverListRoute.of(query).query())
    }

    @Test
    fun `a route an older build saved, for a list it doesn't know, opens the side's first`() {
        assertEquals(DiscoverQuery(MediaType.Tv, DiscoverList.Trending), DiscoverListRoute("Tv", "Gone").query())
        // A movies' list on the shows' side is not one of theirs either.
        assertEquals(DiscoverQuery(MediaType.Tv, DiscoverList.Trending), DiscoverListRoute("Tv", "NowPlaying").query())
    }
}

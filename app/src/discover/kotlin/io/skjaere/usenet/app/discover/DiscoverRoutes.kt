package io.skjaere.usenet.app.discover

import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.MediaType
import kotlinx.serialization.Serializable

/** The Discover tab. */
@Serializable data object DiscoverRoute

/**
 * A list's See all page: [type] a side's ("Tv" or "Movie"), [list] a [DiscoverList]'s name, opened with the tab's
 * [genre]. Names and ids only: a route is kept in saved state.
 */
@Serializable data class DiscoverListRoute(val type: String, val list: String, val genre: Int? = null) {
    /** The list it opens; one it doesn't know (a name from an older build, restored) is the side's first. */
    fun query(): DiscoverQuery {
        val side = MediaType.entries.firstOrNull { it.name == type } ?: MediaType.Tv
        val of = DiscoverList.on(side).firstOrNull { it.name == list } ?: DiscoverList.on(side).first()
        return DiscoverQuery(side, of, DiscoverFilters(genre = genre).on(side, of))
    }

    companion object {
        fun of(query: DiscoverQuery) = DiscoverListRoute(query.type.name, query.list.name, query.filters.genre)
    }
}

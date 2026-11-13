package io.skjaere.usenet.discover

import io.skjaere.usenet.metadata.MediaType

/** A title on one of TMDB's lists: what its card shows, and the type and id its page opens by. */
data class DiscoverTitle(
    val type: MediaType,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val genreIds: List<Int> = emptyList(),
    /** TMDB's own code, as the language filter takes it ("cn" for Cantonese); null when TMDB has none. */
    val originalLanguage: String? = null,
)

/**
 * A step of a list: its titles (people and adult titles left out, each title once), the last of TMDB's pages read for
 * them ([page]: the next step starts after it), and how many pages there are to read, at most [MAX_PAGES] ([capped]:
 * TMDB has more, which a list doesn't show).
 */
data class DiscoverPage(val items: List<DiscoverTitle>, val page: Int, val totalPages: Int, val capped: Boolean = false) {
    val hasMore: Boolean get() = page < totalPages

    companion object {
        /** 500 titles at TMDB's 20 a page: enough to browse, and far fewer than TMDB's own limit of 500 pages. */
        const val MAX_PAGES = 25
    }
}

/** These titles with [more] after them, each title once: TMDB's lists can move between two pages, so a title can come twice. */
fun List<DiscoverTitle>.plusNew(more: List<DiscoverTitle>): List<DiscoverTitle> {
    val seen = mapTo(HashSet()) { it.type to it.tmdbId }
    return this + more.filter { seen.add(it.type to it.tmdbId) }
}

/** A TMDB genre, in the TMDB language. */
data class Genre(val id: Int, val name: String)

/** A streaming service, by name only (no logos). */
data class Service(val id: Int, val name: String)

/** An original language: TMDB's [code] and its [name] in the device's language. */
data class Language(val code: String, val name: String)

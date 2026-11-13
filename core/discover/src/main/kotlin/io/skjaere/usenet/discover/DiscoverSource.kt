package io.skjaere.usenet.discover

import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import java.util.Locale

/**
 * Where Discover's lists come from: TMDB with the user's own key ([TmdbDiscoverSource]), or a test's fake. The screens
 * talk only to this. Failures are TMDB's ([io.skjaere.usenet.metadata.TmdbException]): no key (nothing sent), a refused
 * key, TMDB unreachable with no copy kept, a request it refused.
 */
interface DiscoverSource {
    /**
     * A step of [query]'s titles, from TMDB's page [from] (1 to [DiscoverPage.MAX_PAGES]): one page; for Trending with
     * filters, which TMDB can't apply to it, pages filtered here until a screenful match or five were read, or one is a
     * copy TMDB gave earlier, or one after the first fails (the step is then the pages before it). With its age; a copy
     * TMDB gave earlier says so ([Aged.offline], [Aged.rateLimited]). [refresh] asks TMDB again.
     */
    suspend fun page(query: DiscoverQuery, from: Int = 1, refresh: Boolean = false): Aged<DiscoverPage>

    /** [type]'s genres, in the TMDB language. */
    suspend fun genres(type: MediaType): List<Genre>

    /** The device's region's top streaming services for movies, by TMDB's display priority; none without a region. */
    suspend fun services(): List<Service>

    /** The original languages to pick from, named in [locale]. */
    fun languages(locale: Locale = Locale.getDefault()): List<Language>
}

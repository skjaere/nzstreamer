package io.skjaere.usenet.app

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion

/**
 * TMDB's details of titles the app knows by id, as Home, the Show screen, the lists' refresher, a
 * search's picked title and playing an episode by its numbers read them, its suggestions as
 * Search shows them, and its check of a key as Settings › Metadata and "Set up with your phone" ask
 * for it. A test seam: device tests answer for the fake catalogue's invented titles, which TMDB
 * doesn't know, and for invented keys (FakeTmdb swaps and restores them).
 */
object TmdbLookup {
    @VisibleForTesting
    @Volatile
    internal var showAged: suspend (AppContainer, Int) -> Aged<ShowDetails> = { c, id -> c.tmdb.showAged(id) }

    @VisibleForTesting
    @Volatile
    internal var seasonAged: suspend (AppContainer, Int, Int) -> Aged<Season> = { c, id, number -> c.tmdb.seasonAged(id, number) }

    @VisibleForTesting
    @Volatile
    internal var movieAged: suspend (AppContainer, Int) -> Aged<MovieDetails> = { c, id -> c.tmdb.movieAged(id) }

    @VisibleForTesting
    @Volatile
    internal var searchTitles: suspend (AppContainer, String) -> List<TitleSuggestion> = { c, query -> c.tmdb.searchTitles(query) }

    @VisibleForTesting
    @Volatile
    internal var validateKey: suspend (AppContainer, String) -> KeyCheck = { c, key -> c.tmdb.validate(key) }

    suspend fun show(container: AppContainer, tmdbId: Int): ShowDetails = showAged(container, tmdbId).value

    suspend fun movie(container: AppContainer, tmdbId: Int): MovieDetails = movieAged(container, tmdbId).value

    suspend fun season(container: AppContainer, showTmdbId: Int, number: Int): Season = seasonAged(container, showTmdbId, number).value

    /** Whether TMDB takes [key], asked now (never from the cache). */
    suspend fun validate(container: AppContainer, key: String): KeyCheck = validateKey(container, key)
}

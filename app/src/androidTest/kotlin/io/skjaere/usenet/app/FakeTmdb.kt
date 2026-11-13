package io.skjaere.usenet.app

import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMDB for the app's device tests: TmdbLookup's seams answer for the given invented titles (the fake
 * catalogue's, which TMDB doesn't know), or fail for [movieErrors]'s, and pass everything else to the
 * real lookups. Screens, Home, the lists' refresher and Search's suggestions all read the seams, so one
 * swap covers them; [close] puts the real ones back. [lookups] counts the answers it gave. Its answers
 * are [age] old, as TmdbClient's cache would give them (from TMDB just now by default). [keys] answers
 * a key's check (Settings › Metadata's, the phone setup's) for invented keys; null asks TMDB.
 */
class FakeTmdb(
    private val shows: Map<Int, ShowDetails> = emptyMap(),
    private val movies: Map<Int, MovieDetails> = emptyMap(),
    private val seasons: (show: Int, number: Int) -> Season? = { _, _ -> null },
    private val suggestions: (query: String) -> List<TitleSuggestion>? = { null },
    private val movieErrors: Map<Int, TmdbError> = emptyMap(),
    private val age: Duration = Duration.ZERO,
    private val keys: (key: String) -> KeyCheck? = { null },
) : AutoCloseable {
    private val realShow = TmdbLookup.showAged
    private val realSeason = TmdbLookup.seasonAged
    private val realMovie = TmdbLookup.movieAged
    private val realSearch = TmdbLookup.searchTitles
    private val realValidate = TmdbLookup.validateKey
    val lookups = AtomicInteger()

    init {
        TmdbLookup.showAged = { c, id -> shows[id]?.let { lookups.incrementAndGet(); Aged(it, age) } ?: realShow(c, id) }
        TmdbLookup.seasonAged = { c, id, number -> seasons(id, number)?.let { lookups.incrementAndGet(); Aged(it, age) } ?: realSeason(c, id, number) }
        TmdbLookup.movieAged = { c, id ->
            movieErrors[id]?.let { throw TmdbException(it) }
            movies[id]?.let { lookups.incrementAndGet(); Aged(it, age) } ?: realMovie(c, id)
        }
        TmdbLookup.searchTitles = { c, query -> suggestions(query)?.also { lookups.incrementAndGet() } ?: realSearch(c, query) }
        TmdbLookup.validateKey = { c, key -> keys(key)?.also { lookups.incrementAndGet() } ?: realValidate(c, key) }
    }

    override fun close() {
        TmdbLookup.showAged = realShow
        TmdbLookup.seasonAged = realSeason
        TmdbLookup.movieAged = realMovie
        TmdbLookup.searchTitles = realSearch
        TmdbLookup.validateKey = realValidate
    }
}

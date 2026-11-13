package io.skjaere.usenet.app.collection.model

import io.skjaere.usenet.data.ShowWatchAggregate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 1,000 shows and 2,000 movies through the rows and every view, within a loose budget: catches quadratic work, not a benchmark. */
class CollectionViewScaleTest {
    @Test
    fun `a thousand shows and two thousand movies build and view quickly`() {
        val syllables = listOf("av", "rel", "eb", "rin", "or", "bel", "ka", "lo", "mi", "sur", "tan", "vel", "zo", "qui", "nor", "har")
        fun name(i: Int) = syllables[i % 16].replaceFirstChar { it.uppercase() } + syllables[(i / 16) % 16] + " %04d".format(i)
        val shows = (1..1_000).map { collected(it, name(it), addedAt = it.toLong()) }
        val summaries = shows.associate { it.tmdbId to showDetails(it.tmdbId, counts = listOf(10, 10, 6), last = 3 to 6, lastAired = today.minusDays((it.tmdbId % 20).toLong())).toSummary() }
        val watches = shows.filter { it.tmdbId % 3 == 0 }.associate { s ->
            s.tmdbId to ShowWatchAggregate(s.tmdbId, (1..10).joinToString(",") { "1:$it" }, "2:1:600000:${s.tmdbId}", s.tmdbId.toLong())
        }
        val movies = (1..2_000).map { collectedMovie(10_000 + it, name(it + 7), addedAt = it.toLong()) }
        val movieSummaries = movies.associate { it.tmdbId to movieSummary(it.tmdbId, release = today.minusDays((it.tmdbId % 900).toLong() - 100)) }
        val start = System.nanoTime()
        val cache = RowCache<ShowRow>()
        val showRows = ShowRows.build(shows, summaries, watches, emptyMap(), today, true, cache)
        val movieRows = MovieRows.build(movies, movieSummaries, emptyMap(), emptyMap(), today, true)
        val views = ShowSort.entries.flatMap { sort -> ShowFilter.entries.map { CollectionView.shows(showRows, it, sort, "") } } +
            MovieSort.entries.flatMap { sort -> MovieFilter.entries.map { CollectionView.movies(movieRows, it, sort, "") } } +
            listOf("a", "av", "avr", "avrel 00", "zo 1").map { CollectionView.shows(showRows, ShowFilter.All, ShowSort.AZ, it) }
        // Built again with one show changed: the cache keeps the rest.
        ShowRows.build(shows, summaries, watches + (3 to watches.getValue(3).copy(lastPlayedAt = 99_999)), emptyMap(), today, true, cache)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(1_001, cache.built)
        assertEquals(1_000, views.first().rows.size)
        assertTrue(ms < 5_000, "took $ms ms")
    }
}

package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.collected
import io.skjaere.usenet.app.collection.model.collectedMovie
import io.skjaere.usenet.app.collection.model.movieRow
import io.skjaere.usenet.app.collection.model.movieSummary
import io.skjaere.usenet.app.collection.model.movieWatch
import io.skjaere.usenet.app.collection.model.showDetails
import io.skjaere.usenet.app.collection.model.showRow
import io.skjaere.usenet.app.collection.model.today
import io.skjaere.usenet.app.collection.model.watched
import io.skjaere.usenet.data.CollectionCounts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCollectionTest {
    @Test
    fun `the header counts the shows and the movies, leaving out what there is none of`() {
        assertEquals("Collection · 214 shows · 380 movies", HomeCollection(214, 380, emptyList()).header)
        assertEquals("Collection · 1 show", HomeCollection(1, 0, emptyList()).header)
        assertEquals("Collection · 1 show · 1 movie", HomeCollection(1, 1, emptyList()).header)
        assertEquals("Collection · 3 movies", HomeCollection(0, 3, emptyList()).header)
    }

    @Test
    fun `its rows are the three shows watched last`() {
        val shows = (1..5).map { id -> showRow(collected(id, "Show $id", addedAt = id.toLong()), showDetails(id), listOf(watched(id, 1, 1, at = (10 - id).toLong()))) } +
            showRow(collected(6, "Never watched", addedAt = 99), showDetails(6))
        val home = HomeCollection.of(CollectionCounts(6, 2), shows, null)!!
        assertEquals(listOf("Show 1", "Show 2", "Show 3"), home.rows.map { it.title })
        assertEquals(listOf("Next: S01E02 · 19 unwatched"), home.rows.map { it.status }.distinct())
        assertEquals(TitleKey.show(1), home.rows.first().key)
        assertEquals("Collection · 6 shows · 2 movies", home.header)
    }

    @Test
    fun `with no shows, the three movies added last`() {
        val movies = (1..4).map { id -> movieRow(collectedMovie(id, "Film $id", addedAt = id.toLong()), movieSummary(id), movieWatch(id, watched = id == 3, at = 50)) }
        val home = HomeCollection.of(CollectionCounts(0, 4), emptyList(), movies)!!
        assertEquals(listOf("Film 4", "Film 3", "Film 2"), home.rows.map { it.title })
        assertEquals(listOf("Not watched", "✓ Watched", "Not watched"), home.rows.map { it.status })
        assertEquals(TitleKey.movie(4), home.rows.first().key)
    }

    @Test
    fun `nothing collected, no section`() {
        assertNull(HomeCollection.of(CollectionCounts(0, 0), emptyList(), emptyList()))
        // The movies' rows aren't read while there are shows.
        assertEquals(emptyList(), HomeCollection.of(CollectionCounts(0, 2), emptyList(), null)?.rows)
    }

    /** A TV's row of posters: twelve, each with its poster, NEW for a show with new episodes, and how far it was watched. */
    @Test
    fun `on a TV twelve shows, each with its poster, NEW and how much of it was watched`() {
        val shows = (1..14).map { id ->
            val show = collected(id, "Show $id", addedAt = id.toLong()).copy(posterPath = "/p$id.jpg")
            // Show 1 watched last; its last episode aired yesterday, so it is new.
            val details = if (id == 1) showDetails(id, lastAired = today.minusDays(1)) else showDetails(id)
            showRow(show, details, listOf(watched(id, 1, 1, at = (100 - id).toLong())))
        }
        val home = HomeCollection.of(CollectionCounts(14, 0), shows, null, rows = HomeCollection.TV_ROWS)!!
        assertEquals((1..12).map { "Show $it" }, home.rows.map { it.title })
        assertEquals((1..12).map { "/p$it.jpg" }, home.rows.map { it.posterPath })
        assertEquals(listOf(true) + List(11) { false }, home.rows.map { it.new })
        assertEquals(List(12) { 1f / 20 }, home.rows.map { it.progress })
        // A phone's three, as before.
        assertEquals(3, HomeCollection.of(CollectionCounts(14, 0), shows, null)!!.rows.size)
    }

    /** As the collection grid's posters: no bar on a show watched to the end (TV-4's L8). */
    @Test
    fun `a show watched to its end has no bar`() {
        fun all(show: Int) = (1..10).flatMap { listOf(watched(show, 1, it, at = 9), watched(show, 2, it, at = 9)) }
        val shows = listOf(
            showRow(collected(1, "Up to date", addedAt = 1), showDetails(1), all(1)),
            showRow(collected(2, "Finished", addedAt = 2), showDetails(2, status = "Ended"), all(2)),
            showRow(collected(3, "Begun", addedAt = 3), showDetails(3), listOf(watched(3, 1, 1, at = 5))),
        )
        val home = HomeCollection.of(CollectionCounts(3, 0), shows, null, rows = HomeCollection.TV_ROWS)!!
        assertEquals(mapOf("Up to date" to null, "Finished" to null, "Begun" to 1f / 20), home.rows.associate { it.title to it.progress })
    }

    @Test
    fun `a movie's poster says how far in it is while in progress, and never NEW`() {
        val movies = listOf(
            movieRow(collectedMovie(1, "Film 1", addedAt = 2).copy(posterPath = "/m1.jpg"), movieSummary(1), movieWatch(1, positionMs = 1_770_000, durationMs = 7_080_000, at = 50)),
            movieRow(collectedMovie(2, "Film 2", addedAt = 1), movieSummary(2)),
        )
        val home = HomeCollection.of(CollectionCounts(0, 2), emptyList(), movies, rows = HomeCollection.TV_ROWS)!!
        assertEquals(listOf("/m1.jpg", null), home.rows.map { it.posterPath })
        assertEquals(listOf(0.25f, null), home.rows.map { it.progress })
        assertEquals(listOf(false, false), home.rows.map { it.new })
    }
}

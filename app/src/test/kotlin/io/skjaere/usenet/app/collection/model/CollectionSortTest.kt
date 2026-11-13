package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CollectionSortTest {
    private fun names(rows: List<TitleRow>) = rows.map { it.text.name }

    @Test
    fun `recently watched first, then never watched by when they were added`() {
        val rows = listOf(
            showRow(collected(1, "Old add", addedAt = 10), showDetails(1)),
            showRow(collected(2, "Watched long ago", addedAt = 1), showDetails(2), listOf(watched(2, 1, 1, at = 5))),
            showRow(collected(3, "New add", addedAt = 30), showDetails(3)),
            showRow(collected(4, "Watched lately", addedAt = 2), showDetails(4), listOf(watched(4, 1, 1, at = 50))),
        )
        assertEquals(listOf("Watched lately", "Watched long ago", "New add", "Old add"), names(CollectionView.sorted(rows, ShowSort.RecentlyWatched)))
        assertEquals(listOf("New add", "Old add", "Watched lately", "Watched long ago"), names(CollectionView.sorted(rows, ShowSort.RecentlyAdded)))
    }

    @Test
    fun `new episodes first, the latest aired first, then the most left to watch`() {
        val fresh = showDetails(1, counts = listOf(10), last = 1 to 10, lastAired = today.minusDays(1))
        val week = showDetails(2, counts = listOf(10), last = 1 to 10, lastAired = today.minusDays(6))
        val rows = listOf(
            showRow(collected(1, "Aired yesterday"), fresh, (1..9).map { watched(1, 1, it) }),
            showRow(collected(2, "Aired this week"), week, (1..9).map { watched(2, 1, it) }),
            showRow(collected(3, "Old, much left"), showDetails(3)),
            showRow(collected(4, "Old, little left"), showDetails(4), (1..10).map { watched(4, 1, it) }),
            showRow(collected(5, "No facts"), null),
        )
        assertEquals(listOf("Aired yesterday", "Aired this week", "Old, much left", "Old, little left", "No facts"), names(CollectionView.sorted(rows, ShowSort.NewEpisodes)))
    }

    @Test
    fun `next to air, soonest first, shows with nothing to come last`() {
        val rows = listOf(
            showRow(collected(1, "Nothing to come"), showDetails(1)),
            showRow(collected(2, "In a week"), showDetails(2, next = 3 to 1, nextAirs = today.plusDays(7))),
            showRow(collected(3, "Today"), showDetails(3, next = 3 to 1, nextAirs = today)),
            showRow(collected(4, "No facts"), null),
            showRow(collected(5, "Tomorrow"), showDetails(5, next = 3 to 1, nextAirs = today.plusDays(1))),
        )
        assertEquals(listOf("Today", "Tomorrow", "In a week", "No facts", "Nothing to come"), names(CollectionView.sorted(rows, ShowSort.NextToAir)))
    }

    @Test
    fun `A to Z folds case and accents, files digits under hash and leaves out a leading article`() {
        val titles = listOf("the Quiet Orbit", "Ébrin Hollow", "apple Field", "7 Orbel", "An Ember", "The", "Zed", "A Lantern Field", "Copper Crown")
        val rows = titles.mapIndexed { i, t -> showRow(collected(i + 1, t), null) }
        val sorted = CollectionView.sorted(rows, ShowSort.AZ)
        assertEquals(listOf("7 Orbel", "apple Field", "Copper Crown", "Ébrin Hollow", "An Ember", "A Lantern Field", "the Quiet Orbit", "The", "Zed"), names(sorted))
        assertEquals(listOf('#', 'A', 'C', 'E', 'E', 'L', 'Q', 'T', 'Z'), sorted.map { it.text.letter })
    }

    @Test
    fun `ties keep one order, by title and then by key`() {
        val rows = (1..6).map { showRow(collected(it, if (it % 2 == 0) "Same" else "Avrel $it", addedAt = 5), showDetails(it)) }
        val once = CollectionView.sorted(rows, ShowSort.RecentlyAdded)
        assertEquals(once, CollectionView.sorted(rows.reversed(), ShowSort.RecentlyAdded))
        assertEquals(listOf("Avrel 1", "Avrel 3", "Avrel 5", "Same", "Same", "Same"), names(once))
        assertEquals(listOf(2, 4, 6), once.drop(3).map { it.key.tmdbId })
    }

    @Test
    fun `movies sort by when added, watched, title or release, unknown dates last`() {
        val rows = listOf(
            movieRow(collectedMovie(1, "Lantern Field", addedAt = 3), movieSummary(1, release = today.minusDays(400)), movieWatch(1, watched = true, at = 9)),
            movieRow(collectedMovie(2, "Copper Crown", addedAt = 1), movieSummary(2, release = today.plusDays(90), status = "Planned")),
            movieRow(collectedMovie(3, "The Quiet Orbit", addedAt = 2), movieSummary(3, release = null)),
            movieRow(collectedMovie(4, "Glasswater Bay", addedAt = 4), movieSummary(4, release = today.minusDays(3)), movieWatch(4, positionMs = 600_000, at = 20)),
        )
        assertEquals(listOf("Glasswater Bay", "Lantern Field", "The Quiet Orbit", "Copper Crown"), names(CollectionView.sorted(rows, MovieSort.RecentlyAdded)))
        assertEquals(listOf("Glasswater Bay", "Lantern Field", "The Quiet Orbit", "Copper Crown"), names(CollectionView.sorted(rows, MovieSort.RecentlyWatched)))
        assertEquals(listOf("Copper Crown", "Glasswater Bay", "Lantern Field", "The Quiet Orbit"), names(CollectionView.sorted(rows, MovieSort.AZ)))
        assertEquals(listOf("Copper Crown", "Glasswater Bay", "Lantern Field", "The Quiet Orbit"), names(CollectionView.sorted(rows, MovieSort.ReleaseDate)))
    }
}

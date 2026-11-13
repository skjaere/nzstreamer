package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionViewTest {
    private val fresh = showDetails(0, counts = listOf(10), last = 1 to 10, lastAired = today.minusDays(1))

    /** Harbor Lights watching with a new episode, The Quiet Orbit not started, Glasswater Bay finished, Copper Crown without facts. */
    private val shows = listOf(
        showRow(collected(1, "Harbor Lights"), fresh.copy(tmdbId = 1), (1..9).map { watched(1, 1, it) }),
        showRow(collected(2, "The Quiet Orbit"), showDetails(2)),
        showRow(collected(3, "Glasswater Bay"), showDetails(3, status = "Ended"), (1..10).flatMap { listOf(watched(3, 1, it), watched(3, 2, it)) }),
        showRow(collected(4, "Copper Crown"), null),
    )

    private fun chips(view: SideView) = view.chips.associate { it.name to it.count }

    @Test
    fun `the chips count each filter, and the header the shows and those with new episodes`() {
        val view = CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "")
        assertEquals(mapOf("All" to 4, "Watching" to 1, "NewEpisodes" to 1, "NotStarted" to 2, "UpToDate" to 0, "Finished" to 1), chips(view))
        assertEquals(listOf("All", "Watching", "New episodes", "Not started", "Up to date", "Finished"), view.chips.map { it.label })
        assertEquals(listOf(true, false, false, false, false, false), view.chips.map { it.selected })
        assertEquals("4 shows · 1 with new episodes · checking 1", view.header)
        assertEquals(1, view.pending)
        assertEquals(4, view.total)
        val ready = shows.dropLast(1)
        assertEquals("3 shows · 1 with new episodes", CollectionView.shows(ready, ShowFilter.All, ShowSort.AZ, "").header)
        assertEquals("1 show", CollectionView.shows(ready.drop(1).take(1), ShowFilter.All, ShowSort.AZ, "").header)
    }

    @Test
    fun `a filter keeps its rows, and the search narrows the rows and the counts`() {
        val watching = CollectionView.shows(shows, ShowFilter.NotStarted, ShowSort.AZ, "")
        assertEquals(listOf("Copper Crown", "The Quiet Orbit"), watching.rows.map { it.text.name })
        assertEquals(listOf(false, false, false, true, false, false), watching.chips.map { it.selected })
        val search = CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "orb")
        assertEquals(listOf("The Quiet Orbit"), search.rows.map { it.text.name })
        assertEquals(mapOf("All" to 1, "Watching" to 0, "NewEpisodes" to 0, "NotStarted" to 1, "UpToDate" to 0, "Finished" to 0), chips(search))
        assertEquals("1 of 4 shows matches “orb” · checking 1", search.header)
        val more = shows + showRow(collected(5, "Cinder Coast"), showDetails(5))
        assertEquals("2 of 5 shows match “c” · checking 1", CollectionView.shows(more, ShowFilter.All, ShowSort.AZ, " c ").header)
    }

    @Test
    fun `the movie header counts the movies out and not watched`() {
        val movies = listOf(
            movieRow(collectedMovie(1, "Copper Crown"), movieSummary(1)),
            movieRow(collectedMovie(2, "Lantern Field"), movieSummary(2), movieWatch(2, watched = true)),
            movieRow(collectedMovie(3, "Glasswater Bay"), movieSummary(3, release = today.plusDays(9), status = "Planned")),
        )
        val view = CollectionView.movies(movies, MovieFilter.All, MovieSort.RecentlyAdded, "")
        assertEquals("3 movies · 1 unwatched", view.header)
        assertEquals(mapOf("All" to 3, "Unwatched" to 1, "InProgress" to 0, "Watched" to 1, "NotYetReleased" to 1), chips(view))
        assertEquals("1 movie", CollectionView.movies(movies.drop(1).take(1), MovieFilter.All, MovieSort.RecentlyAdded, "").header)
        // Every word typed must start a word of the title.
        assertEquals("0 of 3 movies match “field bay”", CollectionView.movies(movies, MovieFilter.All, MovieSort.AZ, "field bay").header)
    }

    @Test
    fun `the letters are indexed in A to Z only`() {
        val rows = listOf("Avrel", "Ardent", "Copper Crown", "Ébrin", "7 Orbel").mapIndexed { i, t -> showRow(collected(i + 1, t), null) }
        assertNull(CollectionView.shows(rows, ShowFilter.All, ShowSort.RecentlyAdded, "").letters)
        val letters = CollectionView.shows(rows, ShowFilter.All, ShowSort.AZ, "").letters!!
        assertEquals(listOf('#') + ('A'..'Z'), letters.letters)
        assertEquals(mapOf('#' to 0, 'A' to 1, 'C' to 3, 'E' to 4), letters.first)
        assertEquals(mapOf('#' to 1, 'A' to 2, 'C' to 1, 'E' to 1), letters.counts)
        // A letter without titles goes to the next one that has some; past the last, to the last.
        assertEquals(3, letters.indexFor('B'))
        assertEquals(4, letters.indexFor('Z'))
        assertEquals(1, letters.indexFor('A'))
    }

    @Test
    fun `what the list says when it has nothing to show`() {
        assertEquals(Empty.NoTitles, CollectionView.shows(emptyList(), ShowFilter.Finished, ShowSort.AZ, "orb").empty)
        assertEquals(Empty.FilterEmpty("UpToDate"), CollectionView.shows(shows, ShowFilter.UpToDate, ShowSort.AZ, "").empty)
        assertEquals(Empty.SearchEmpty("orb", 1), CollectionView.shows(shows, ShowFilter.Finished, ShowSort.AZ, " orb ").empty)
        assertEquals(Empty.SearchEmpty("zzz", 0), CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "zzz").empty)
        assertNull(CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "").empty)
    }

    @Test
    fun `in select mode the rows keep their order, and new ones go to the end`() {
        val order = listOf(TitleKey.show(3), TitleKey.show(1), TitleKey.show(9), TitleKey.show(2))
        val view = CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "", frozenOrder = order)
        assertEquals(listOf(3, 1, 2, 4), view.rows.map { it.key.tmdbId })
        assertEquals(listOf(4, 3, 1, 2), CollectionView.shows(shows, ShowFilter.All, ShowSort.AZ, "").rows.map { it.key.tmdbId })
    }
}

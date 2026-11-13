package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals

class MovieFiltersTest {
    private fun filters(row: MovieRow) = MovieFilter.entries.filter { CollectionView.inFilter(row, it) }
    private val m = collectedMovie(11)

    @Test
    fun `each chip's movies`() {
        assertEquals(listOf(MovieFilter.All, MovieFilter.Unwatched), filters(movieRow(m, movieSummary(11))))
        assertEquals(listOf(MovieFilter.All, MovieFilter.InProgress), filters(movieRow(m, movieSummary(11), movieWatch(11, positionMs = 600_000))))
        assertEquals(listOf(MovieFilter.All, MovieFilter.Watched), filters(movieRow(m, movieSummary(11), movieWatch(11, watched = true))))
        val coming = movieSummary(11, release = today.plusDays(1), status = "Post Production")
        assertEquals(listOf(MovieFilter.All, MovieFilter.NotYetReleased), filters(movieRow(m, coming)))
        assertEquals(listOf(MovieFilter.All, MovieFilter.NotYetReleased), filters(movieRow(m, movieSummary(11, release = null, status = "Planned"))))
    }

    @Test
    fun `a movie out today is released`() {
        assertEquals(listOf(MovieFilter.All, MovieFilter.Unwatched), filters(movieRow(m, movieSummary(11, release = today, status = "Post Production"))))
    }

    @Test
    fun `the chips other than All partition the movies with facts`() {
        val random = Random(3)
        repeat(300) {
            val summary = movieSummary(
                11, release = today.plusDays(random.nextLong(-400, 400)).takeIf { random.nextInt(5) > 0 },
                status = listOf("Released", "Post Production", "Planned", null).random(random),
            )
            val watch = when (random.nextInt(4)) {
                0 -> null
                1 -> movieWatch(11, watched = true)
                2 -> movieWatch(11, positionMs = random.nextLong(0, 200_000))
                else -> movieWatch(11, positionMs = random.nextLong(0, 200_000), durationMs = 0)
            }
            val chips = filters(movieRow(m, summary, watch)) - MovieFilter.All
            assertEquals(1, chips.size, "$summary $watch: $chips")
        }
    }

    @Test
    fun `without facts, a movie is in progress or watched by its state alone, else only in All`() {
        assertEquals(listOf(MovieFilter.All), filters(movieRow(m, null)))
        assertEquals(listOf(MovieFilter.All, MovieFilter.InProgress), filters(movieRow(m, null, movieWatch(11, positionMs = 600_000))))
        assertEquals(listOf(MovieFilter.All, MovieFilter.Watched), filters(movieRow(m, null, movieWatch(11, watched = true))))
    }

    @Test
    fun `filter and sort names read back`() {
        assertEquals(MovieFilter.entries, MovieFilter.entries.map { MovieFilter.byName(it.name) })
        assertEquals(MovieSort.entries, MovieSort.entries.map { MovieSort.byName(it.name) })
        assertEquals(null, MovieFilter.byName("Archived"))
        assertEquals(LocalDate.of(2019, 5, 1), movieRow(m, movieSummary(11)).releaseDate)
    }
}

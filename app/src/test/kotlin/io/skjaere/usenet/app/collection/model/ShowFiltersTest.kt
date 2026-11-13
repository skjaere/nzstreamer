package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ShowFiltersTest {
    private val d = showDetails(1)
    private val all = (1..10).flatMap { listOf(watched(1, 1, it), watched(1, 2, it)) }

    private fun filters(row: ShowRow) = ShowFilter.entries.filter { CollectionView.inFilter(row, it) }

    @Test
    fun `each show is in All and in one of its states`() {
        assertEquals(listOf(ShowFilter.All, ShowFilter.NotStarted), filters(showRow(collected(1), d)))
        assertEquals(listOf(ShowFilter.All, ShowFilter.Watching), filters(showRow(collected(1), d, listOf(watched(1, 1, 1)))))
        assertEquals(listOf(ShowFilter.All, ShowFilter.UpToDate), filters(showRow(collected(1), d, all)))
        assertEquals(listOf(ShowFilter.All, ShowFilter.Finished), filters(showRow(collected(1), d.copy(status = "Ended"), all)))
    }

    @Test
    fun `an episode in progress is watching, even with nothing else left`() {
        assertEquals(listOf(ShowFilter.All, ShowFilter.Watching), filters(showRow(collected(1), d.copy(status = "Ended"), all.dropLast(1) + started(1, 2, 10))))
        assertEquals(listOf(ShowFilter.All, ShowFilter.Watching), filters(showRow(collected(1), d, listOf(started(1, 1, 1)))))
    }

    @Test
    fun `new episodes aired this past week, unwatched, whatever else the show is`() {
        val aired = { days: Long -> showDetails(1, counts = listOf(10), last = 1 to 10, lastAired = today.minusDays(days)) }
        val nine = (1..9).map { watched(1, 1, it) }
        assertEquals(listOf(ShowFilter.All, ShowFilter.Watching, ShowFilter.NewEpisodes), filters(showRow(collected(1), aired(7), nine)))
        assertEquals(listOf(ShowFilter.All, ShowFilter.Watching), filters(showRow(collected(1), aired(8), nine)))
        assertEquals(listOf(ShowFilter.All, ShowFilter.NewEpisodes, ShowFilter.NotStarted), filters(showRow(collected(1), aired(0))))
        assertEquals(listOf(ShowFilter.All, ShowFilter.UpToDate), filters(showRow(collected(1), aired(0), nine + watched(1, 1, 10))))
    }

    @Test
    fun `without facts a show is not started only when nothing was played, and otherwise only in All`() {
        assertEquals(listOf(ShowFilter.All, ShowFilter.NotStarted), filters(showRow(collected(1), null)))
        assertEquals(listOf(ShowFilter.All), filters(showRow(collected(1), null, listOf(watched(1, 1, 1)))))
        assertEquals(listOf(ShowFilter.All), filters(showRow(collected(1), null, listOf(started(1, 1, 1)))))
        assertEquals(listOf(ShowFilter.All, ShowFilter.NotStarted), filters(showRow(collected(1), null, failure = "NotFound")))
    }

    @Test
    fun `filter names read back, and an unknown one is none`() {
        assertEquals(ShowFilter.entries, ShowFilter.entries.map { ShowFilter.byName(it.name) })
        assertEquals(null, ShowFilter.byName("Archived"))
        assertEquals(ShowSort.entries, ShowSort.entries.map { ShowSort.byName(it.name) })
        assertEquals(null, ShowSort.byName("Rating"))
        assertEquals(Side.entries, Side.entries.map { Side.byName(it.name) })
    }
}

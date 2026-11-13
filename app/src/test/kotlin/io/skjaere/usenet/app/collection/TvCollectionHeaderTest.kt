package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.CollectionView
import io.skjaere.usenet.app.collection.model.ShowFilter
import io.skjaere.usenet.app.collection.model.ShowSort
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.collected
import io.skjaere.usenet.app.collection.model.showDetails
import io.skjaere.usenet.app.collection.model.showRow
import io.skjaere.usenet.app.collection.model.watched
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The TV grid's lines above its posters (TV-4's L2 and L3), as one description the grid emits and its scrolling, paging
 * and letter jump count by: what shows is what is counted, so a poster's place can't drift (#221's hint).
 */
class TvCollectionHeaderTest {
    private val rows = (1..3).map { showRow(collected(it, "Show $it", addedAt = it.toLong()), showDetails(it), listOf(watched(it, 1, 1))) }

    private fun ui(
        rows: List<io.skjaere.usenet.app.collection.model.ShowRow> = this.rows,
        filter: ShowFilter = ShowFilter.All,
        text: String = "",
        hasKey: Boolean = true,
        note: String? = null,
        selecting: Boolean = false,
    ) = CollectionUi(
        side = Side.Shows, filterName = filter.name, sortName = ShowSort.RecentlyWatched.name, sorts = emptyList(), searchOpen = text.isNotEmpty(), text = text,
        view = CollectionView.shows(rows, filter, ShowSort.RecentlyWatched, text),
        selection = if (selecting) SelectionUi(0, false, emptySet()) else null, undo = null, busy = null,
        highlight = null, highlightAdded = false, announce = null, note = note, hasKey = hasKey, showArtwork = false,
    )

    @Test
    fun `with titles shown the top line, the switch and chips, and the header line`() {
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER), tvHeaderItems(ui()))
    }

    @Test
    fun `without a key the hint goes above the posters, but an empty side says it itself`() {
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER, TV_HINT), tvHeaderItems(ui(hasKey = false)))
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER, TV_EMPTY), tvHeaderItems(ui(rows = emptyList(), hasKey = false)))
    }

    @Test
    fun `a note and an empty state are lines of their own`() {
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER, TV_NOTE), tvHeaderItems(ui(note = "TMDB unreachable · showing saved info")))
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER, TV_EMPTY), tvHeaderItems(ui(filter = ShowFilter.Finished)))
        assertEquals(listOf(TV_TOP, TV_SIDES, TV_HEADER, TV_EMPTY), tvHeaderItems(ui(text = "orb")))
    }

    @Test
    fun `select mode has its bar in place of the top lines`() {
        assertEquals(listOf(TV_SELECT), tvHeaderItems(ui(selecting = true)))
        assertEquals(listOf(TV_SELECT, TV_HINT), tvHeaderItems(ui(selecting = true, hasKey = false)))
    }

    /** The grid's scrolling, reveal and letter jump count a title's item from here: its place among the posters, after these. */
    @Test
    fun `the posters come after every line the header emits`() {
        assertEquals(3, tvItemsBefore(ui()))
        assertEquals(4, tvItemsBefore(ui(hasKey = false)))
        assertEquals(5, tvItemsBefore(ui(hasKey = false, note = "TMDB unreachable · showing saved info")))
        assertEquals(1, tvItemsBefore(ui(selecting = true)))
    }
}

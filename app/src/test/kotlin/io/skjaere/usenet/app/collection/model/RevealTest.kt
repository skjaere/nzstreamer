package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RevealTest {
    private val orbit = showRow(collected(2, "The Quiet Orbit"), showDetails(2))
    private val harbor = showRow(collected(1, "Harbor Lights"), showDetails(1), listOf(watched(1, 1, 1)))
    private val rows = listOf(harbor, orbit)
    private fun view(filter: ShowFilter = ShowFilter.All, text: String = "") = CollectionView.shows(rows, filter, ShowSort.AZ, text)
    private val keys = rows.map { it.key }.toSet()

    @Test
    fun `a title on the other side switches sides first`() {
        assertEquals(RevealStep.SwitchSide, Reveal.step(TitleKey.movie(2), Side.Shows, view(), keys))
        assertEquals(RevealStep.SwitchSide, Reveal.step(orbit.key, Side.Movies, null, null))
    }

    @Test
    fun `a title not in the rows yet is waited for`() {
        assertEquals(RevealStep.Wait, Reveal.step(TitleKey.show(3), Side.Shows, view(), keys))
        assertEquals(RevealStep.Wait, Reveal.step(orbit.key, Side.Shows, null, null))
    }

    @Test
    fun `a title the filter or the search hides clears them`() {
        assertEquals(RevealStep.ClearFilters, Reveal.step(orbit.key, Side.Shows, view(ShowFilter.Watching), keys))
        assertEquals(RevealStep.ClearFilters, Reveal.step(orbit.key, Side.Shows, view(text = "harbor"), keys))
    }

    @Test
    fun `a filter that shows the title is kept, and the list scrolls to it`() {
        assertEquals(RevealStep.ScrollTo(0), Reveal.step(orbit.key, Side.Shows, view(ShowFilter.NotStarted), keys))
        assertEquals(RevealStep.ScrollTo(1), Reveal.step(orbit.key, Side.Shows, view(), keys))
    }
}

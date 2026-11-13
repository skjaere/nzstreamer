package io.skjaere.usenet.app.collection

import io.skjaere.usenet.metadata.SeasonSummary
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The season chips' words, as the Show page has always said them. */
class SeasonChipLabelTest {
    private fun season(number: Int, episodes: Int = 8) = SeasonSummary(number, null, episodes, null, null)

    @Test
    fun `specials, watched through, selected or begun, and the rest`() {
        assertEquals("Specials", seasonChipLabel(season(0, 3), watched = 3, selected = true))
        assertEquals("S1 ✓", seasonChipLabel(season(1), watched = 8, selected = false))
        assertEquals("S1 ✓", seasonChipLabel(season(1), watched = 8, selected = true))
        assertEquals("Season 2 · 4/8", seasonChipLabel(season(2), watched = 4, selected = false))
        assertEquals("Season 3 · 0/8", seasonChipLabel(season(3), watched = 0, selected = true))
        assertEquals("S4", seasonChipLabel(season(4), watched = 0, selected = false))
    }

    /** TMDB lists a season before its episodes: nothing to watch through yet. */
    @Test
    fun `a season with no episodes is never watched through`() {
        assertEquals("S5", seasonChipLabel(season(5, 0), watched = 0, selected = false))
    }
}

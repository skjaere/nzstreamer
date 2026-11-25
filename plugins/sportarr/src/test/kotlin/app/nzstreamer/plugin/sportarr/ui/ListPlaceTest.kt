package app.nzstreamer.plugin.sportarr.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the list comes back to (L645): the side and the days opened only on the way back from a page or the settings the
 * list opened, or its activity made again; each time the tab opens, Recent with nothing opened.
 */
class ListPlaceTest {
    @Test
    fun `the list's place comes back once, and only after it left for its own page or its settings`() {
        val place = ListPlace()
        val saved = ListModel.Restored("Upcoming", "2026-11-21|1")
        assertNull(place.take(saved), "the tab opened again: Recent")
        place.keep()
        assertEquals(saved, place.take(saved), "back from its page")
        assertNull(place.take(saved), "the next time the tab opens: Recent again")
        place.keep()
        assertNull(place.take(null), "nothing kept, nothing to come back to")
        assertNull(place.take(saved), "taken once")
    }
}

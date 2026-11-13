package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the side mark's pill goes up and down its third (L13, NxPhoneSeekOverlays): centred on the room above the bottom
 * inset, up 8 dp clear of an overlay in the bottom-end slot, and never off the top of the screen. In dp, as a 640 x 360
 * phone in landscape with a 24 dp status bar and a 32 dp gesture area: the overlays stand 100 dp above that area, and the
 * pill with its time is 57 dp tall.
 */
class SideMarkRoomTest {
    private fun room(clearAbove: Int?, top: Int = 24) = SideMark.Room(left = 427, right = 640, top = top, centreY = (360 - 32) / 2, clearAbove = clearAbove, edge = 0)

    @Test
    fun `with nothing under it, it centres on the room above the bottom inset`() {
        assertEquals(164f - 28.5f, room(clearAbove = null).pillTop(PILL, MARGIN))
    }

    @Test
    fun `an overlay reaching up into its room lifts it 8 dp clear`() {
        // The compact next-episode card, 96 dp tall: its top at 132.
        assertEquals(132f - 8 - PILL, room(clearAbove = 132).pillTop(PILL, MARGIN))
        // One lower down leaves it centred.
        assertEquals(164f - 28.5f, room(clearAbove = 220).pillTop(PILL, MARGIN))
    }

    /**
     * The full missing-parts prompt, its buttons on two rows, reaches to 39 dp from the top: no room above it, so the pill
     * keeps on screen under the status bar, or the top bar while the controls show, over the prompt's top edge.
     */
    @Test
    fun `an overlay with no room above it leaves it on screen, over its top`() {
        val top = room(clearAbove = 39).pillTop(PILL, MARGIN)
        assertTrue(top >= 24 + MARGIN, "the pill's top at $top dp, above the status bar's 24")
        assertEquals(24f + MARGIN, top)
        assertEquals(64f + MARGIN, room(clearAbove = 39, top = 64).pillTop(PILL, MARGIN))
    }

    private companion object {
        const val PILL = 57
        const val MARGIN = 8
    }
}

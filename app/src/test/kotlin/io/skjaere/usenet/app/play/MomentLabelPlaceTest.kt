package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the moment label goes while a full-size overlay shows in the bottom-end slot (L6, QlPhoneCollisions): on a short
 * landscape phone the missing-parts prompt's top (67 dp) comes up level with the label (72 to 108 dp under the top), and
 * the label then centres on the room beside it, kept inside it, its words cut short where they are wider; elsewhere it
 * stays centred on the screen. In dp, as the mockup's 780 x 360 phone with a 300 dp prompt 16 dp from the side.
 */
class MomentLabelPlaceTest {
    private val label = MomentLabelPlace.Box(left = 290, top = 72, right = 490, bottom = 108)

    private fun place(label: MomentLabelPlace.Box, overlay: MomentLabelPlace.Box?, safeLeft: Int = 0) =
        MomentLabelPlace.place(label, overlay, safeLeft, margin = 8)

    @Test
    fun `nothing in the slot, or an overlay below it, leaves it centred`() {
        assertEquals(MomentLabelPlace.Place(0f, null), place(label, null))
        assertEquals(MomentLabelPlace.Place(0f, null), place(label, MomentLabelPlace.Box(464, 120, 764, 248)))
    }

    @Test
    fun `an overlay level with it but clear of it to the side leaves it centred`() {
        assertEquals(MomentLabelPlace.Place(0f, null), place(label, MomentLabelPlace.Box(500, 67, 800, 248)))
    }

    @Test
    fun `an overlay it would touch moves it to the middle of the room beside it`() {
        val prompt = MomentLabelPlace.Box(464, 67, 764, 248)
        val placed = place(label, prompt)
        assertEquals(232f - 390f, placed.shift)
        assertNull(placed.maxWidth, "it fits")
        // Clear of the prompt now.
        assertTrue(label.right + placed.shift < prompt.left)
    }

    /**
     * At 2x text on a 640 x 360 dp phone, "A missing part was skipped" is about 400 dp wide beside a full-size prompt 316 dp
     * wide: centred on the room left of it, it would run off the screen's edge and under the prompt. It is cut to that
     * room instead, 8 dp in from each side, and the system bars' inset on the left (a navigation bar) is no room.
     */
    @Test
    fun `a label wider than the room beside the overlay keeps inside it, its words cut short`() {
        val wide = MomentLabelPlace.Box(left = 120, top = 72, right = 520, bottom = 120)
        val prompt = MomentLabelPlace.Box(308, 0, 624, 260)
        for (safeLeft in listOf(0, 48)) {
            val placed = place(wide, prompt, safeLeft)
            val width = assertNotNullWidth(placed)
            assertEquals(prompt.left - safeLeft - 16, width)
            val centre = (wide.left + wide.right) / 2f + placed.shift
            assertEquals(safeLeft + 8f, centre - width / 2f, "its left, with $safeLeft dp of inset")
            assertEquals(prompt.left - 8f, centre + width / 2f, "its right, with $safeLeft dp of inset")
        }
    }

    private fun assertNotNullWidth(placed: MomentLabelPlace.Place): Int = placed.maxWidth ?: error("not cut to the room: $placed")
}

package io.skjaere.usenet.app.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Distances are what the list scrolls by: positive moves its content up. A 540 px list, its pivot at 30 % (162 px). */
class PivotBringIntoViewSpecTest {
    private val pivot = PivotBringIntoViewSpec()
    private val list = 540f

    @Test
    fun `a row focused below the pivot comes up to it`() {
        assertEquals(238f, pivot.calculateScrollDistance(offset = 400f, size = 56f, containerSize = list))
    }

    /** The list stops at its start, so the first rows stay where they are. */
    @Test
    fun `a row focused above the pivot goes down to it`() {
        assertEquals(-112f, pivot.calculateScrollDistance(offset = 50f, size = 56f, containerSize = list))
    }

    @Test
    fun `a row at the pivot stays`() {
        assertEquals(0f, pivot.calculateScrollDistance(offset = 162f, size = 56f, containerSize = list))
    }

    /**
     * A focused text field asks for itself and then for its cursor, 16 dp further down: on screen near
     * the line, either stays put. Taking each to the line would move the list to and fro for ever.
     */
    @Test
    fun `an element on screen within a tenth of the list of the pivot stays`() {
        assertEquals(0f, pivot.calculateScrollDistance(offset = 178f, size = 20f, containerSize = list))
        assertEquals(0f, pivot.calculateScrollDistance(offset = 146f, size = 56f, containerSize = list))
        assertEquals(0f, pivot.calculateScrollDistance(offset = 162f + 54f, size = 56f, containerSize = list))
    }

    /** Also one that is on screen already: the focus line stays put as the remote moves down. */
    @Test
    fun `a row on screen but off the pivot still moves to it`() {
        assertEquals(100f, pivot.calculateScrollDistance(offset = 262f, size = 56f, containerSize = list))
    }

    @Test
    fun `a block too tall to show whole from the pivot comes up only as far as it must`() {
        // 450 px from 162 would end at 612: its bottom goes to the list's instead, its top at 90.
        assertEquals(210f, pivot.calculateScrollDistance(offset = 300f, size = 450f, containerSize = list))
    }

    @Test
    fun `a block taller than the list starts at the pivot`() {
        assertEquals(138f, pivot.calculateScrollDistance(offset = 300f, size = 600f, containerSize = list))
    }

    @Test
    fun `a child fraction centres the element on the line`() {
        val centred = PivotBringIntoViewSpec(parentFraction = 0.5f, childFraction = 0.5f)
        assertEquals(400f - (270f - 28f), centred.calculateScrollDistance(offset = 400f, size = 56f, containerSize = list))
    }

    /** Rows and chips under the pivot: only as far as needed, nothing while the element shows whole. */
    @Test
    fun `the edge spec scrolls only an element that doesn't show whole`() {
        assertEquals(0f, EdgeBringIntoViewSpec.calculateScrollDistance(offset = 100f, size = 80f, containerSize = 400f))
        assertEquals(60f, EdgeBringIntoViewSpec.calculateScrollDistance(offset = 380f, size = 80f, containerSize = 400f))
        assertEquals(-30f, EdgeBringIntoViewSpec.calculateScrollDistance(offset = -30f, size = 80f, containerSize = 400f))
    }

    /** A focused chip grows past its ends: with room for it, the row brings that in too, and nothing while it shows. */
    @Test
    fun `the edge spec with room scrolls the element's room into view too`() {
        val roomy = edgeBringIntoViewSpec(room = 10f)
        assertEquals(0f, roomy.calculateScrollDistance(offset = 100f, size = 80f, containerSize = 400f))
        assertEquals(70f, roomy.calculateScrollDistance(offset = 380f, size = 80f, containerSize = 400f))
        assertEquals(10f, roomy.calculateScrollDistance(offset = 320f, size = 80f, containerSize = 400f))
        assertEquals(-5f, roomy.calculateScrollDistance(offset = 5f, size = 80f, containerSize = 400f))
    }
}

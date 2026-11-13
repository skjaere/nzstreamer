package io.skjaere.usenet.app.collection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class UndoTimerTest {
    @Test
    fun `an offer goes the given time after it was first shown`() = runTest {
        val undo = UndoTimer<String>(backgroundScope)
        undo.offer("Removed 3 shows", "removal")
        advanceTimeBy(60_000)
        // Not on screen yet: it waits.
        assertEquals("Removed 3 shows", undo.offer.value?.message)
        undo.shown(10_000)
        advanceTimeBy(9_900)
        assertEquals("removal", undo.offer.value?.value)
        advanceTimeBy(200)
        assertNull(undo.offer.value)
    }

    @Test
    fun `a new offer replaces the last, whose time no longer counts`() = runTest {
        val undo = UndoTimer<String>(backgroundScope)
        undo.offer("Removed “Harbor Lights”", "first")
        undo.shown(10_000)
        advanceTimeBy(8_000)
        undo.offer("Marked 2 shows watched", "second")
        advanceTimeBy(5_000)
        assertEquals("second", undo.offer.value?.value)
        undo.shown(10_000)
        advanceTimeBy(9_900)
        assertEquals("second", undo.offer.value?.value)
        advanceTimeBy(200)
        assertNull(undo.offer.value)
    }

    /** The bar composed again (a rotation, the list coming back) shows the same offer: its time keeps running. */
    @Test
    fun `showing the same offer again doesn't restart its time`() = runTest {
        val undo = UndoTimer<String>(backgroundScope)
        undo.offer("Removed 3 shows", "removal")
        undo.shown(10_000)
        advanceTimeBy(6_000)
        undo.shown(10_000)
        advanceTimeBy(4_100)
        assertNull(undo.offer.value)
    }

    @Test
    fun `Undo takes the offer once`() = runTest {
        val undo = UndoTimer<String>(backgroundScope)
        undo.offer("Removed 3 shows", "removal")
        undo.shown(10_000)
        assertEquals("removal", undo.take())
        assertNull(undo.take())
        assertNull(undo.offer.value)
        runCurrent()
        undo.offer("Removed 1 show", "other")
        undo.dismiss()
        assertNull(undo.offer.value)
    }
}

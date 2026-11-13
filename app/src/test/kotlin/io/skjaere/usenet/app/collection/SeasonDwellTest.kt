package io.skjaere.usenet.app.collection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A TV's season chips select the season the focus settles on, 300 ms after it came (TV-3 D6): one season
 * load per chip the user stops at, not per chip passed; OK selects at once; leaving the chips drops what was pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonDwellTest {
    private val selects = mutableListOf<Int>()
    private var selected: Int? = 2

    private fun TestScope.dwell() = SeasonDwell(backgroundScope, { selected }) { selects += it; selected = it }

    @Test
    fun `a chip the focus settles on is selected 300 ms after it came`() = runTest {
        val dwell = dwell()
        dwell.focused(3)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList(), selects)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(3), selects)
    }

    @Test
    fun `chips passed on the way select nothing, only the one stopped at`() = runTest {
        val dwell = dwell()
        listOf(3, 4, 5, 6).forEach {
            dwell.focused(it)
            advanceTimeBy(120)
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(6), selects)
    }

    @Test
    fun `OK selects at once, and drops the pending one`() = runTest {
        val dwell = dwell()
        dwell.focused(4)
        advanceTimeBy(100)
        dwell.pressed(5)
        assertEquals(listOf(5), selects)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(5), selects)
    }

    /** Down from a chip into the episodes: the list mustn't change under the focus a moment later. */
    @Test
    fun `leaving the chips drops the pending selection`() = runTest {
        val dwell = dwell()
        dwell.focused(4)
        advanceTimeBy(200)
        dwell.left()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList(), selects)
    }

    /** Back on the selected chip (from the list, or across it): no season to load again. */
    @Test
    fun `the selected season's chip asks for nothing, and drops one pending`() = runTest {
        val dwell = dwell()
        dwell.focused(3)
        advanceTimeBy(100)
        dwell.focused(2)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList(), selects)
    }

    /** OK on the selected chip asks again: a season that failed to load is tried again that way. */
    @Test
    fun `OK on the selected chip selects it again`() = runTest {
        val dwell = dwell()
        dwell.pressed(2)
        assertEquals(listOf(2), selects)
    }
}

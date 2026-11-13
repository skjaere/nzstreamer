package io.skjaere.usenet.app.play

import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.tv.ScreenMode
import io.skjaere.usenet.player.tv.ScreenModes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The TV's mode kept across a hand-over between two plays (TV-7's L8): held by its size and rate, found again among the
 * modes as listed later (a box numbers them again), released with a line in the log, and by itself after 60 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayModeHoldTest {
    private val uhd = ModeSize(3840, 2160)
    private val film = ScreenMode(23, uhd, 23.976025f)
    private val lines = mutableListOf<String>()

    private fun TestScope.hold() = DisplayModeHold(backgroundScope, { lines += it })

    private fun screen(vararg modes: ScreenMode) = ScreenModes(30, modes.first(), modes.toList(), null, uhd, outputFromTv = true)

    @Test
    fun `a mode handed over is held by its size and rate, and found again whatever its id now`() = runTest {
        val hold = hold()
        hold.handOver(film)
        assertEquals(DisplayModeHold.Held(uhd, 23.976025f), hold.held.value)
        assertEquals(listOf("Display mode held for the next play: 3840x2160 at 23.976 Hz"), lines)
        // The same modes numbered again (another Shield had 23.976 as mode 2).
        val renumbered = screen(ScreenMode(15, uhd, 59.94006f), ScreenMode(2, uhd, 23.976025f), ScreenMode(3, uhd, 24f))
        assertEquals(ScreenMode(2, uhd, 23.976025f), hold.modeIn(renumbered))
        // Not 24 Hz, nor 23.976 at another size.
        assertNull(hold.modeIn(screen(ScreenMode(41, uhd, 59.94006f), ScreenMode(31, uhd, 24f), ScreenMode(25, ModeSize(1920, 1080), 23.976f))))
    }

    /**
     * #142: a mode handed over as the app went to the background, the TV back on its own mode since. The hold is kept only
     * while the display runs its mode, found then by its size and rate as listed now; else it goes, with a line.
     */
    @Test
    fun `a hold is kept only while the display runs its mode`() = runTest {
        val hold = hold()
        val modes = listOf(ScreenMode(41, uhd, 59.94006f), ScreenMode(2, uhd, 23.976025f), ScreenMode(31, uhd, 24f), ScreenMode(25, ModeSize(1920, 1080), 23.976f))
        fun running(mode: ScreenMode) = ScreenModes(30, mode, modes, null, uhd, outputFromTv = true)
        hold.handOver(film)
        assertEquals(ScreenMode(2, uhd, 23.976025f), hold.keptIn(running(modes[1])))
        assertEquals(DisplayModeHold.Held(uhd, 23.976025f), hold.held.value)
        // 59.94 Hz, 24 Hz, or 23.976 at another size: the display left it.
        listOf(modes[0], modes[2], modes[3]).forEach { other ->
            hold.handOver(film)
            assertNull(hold.keptIn(running(other)), "running $other")
            assertNull(hold.held.value)
            assertEquals("Display mode hold released: the display runs another mode", lines.last())
        }
        val said = lines.size
        assertNull(hold.keptIn(running(modes[1])))
        assertEquals(said, lines.size)
    }

    @Test
    fun `cleared, it says why, once`() = runTest {
        val hold = hold()
        hold.clear("nothing held")
        assertEquals(emptyList(), lines)
        hold.handOver(film)
        hold.clear("the next play didn't start")
        hold.clear("again")
        assertNull(hold.held.value)
        assertNull(hold.modeIn(screen(film)))
        assertEquals(listOf("Display mode held for the next play: 3840x2160 at 23.976 Hz", "Display mode hold released: the next play didn't start"), lines)
    }

    /** A hold nothing takes up lets the TV go back to its own mode a minute on; another hand-over starts the minute again. */
    @Test
    fun `a hold goes by itself after 60 s, counted from the last hand-over`() = runTest {
        val hold = hold()
        hold.handOver(film)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(DisplayModeHold.Held(uhd, 23.976025f), hold.held.value)
        hold.handOver(film)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(DisplayModeHold.Held(uhd, 23.976025f), hold.held.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(hold.held.value)
        assertEquals("Display mode hold released: no play started within 60 s", lines.last())
    }

    /** A hold cleared, then handed over again: the first one's minute doesn't end the second. */
    @Test
    fun `a cleared hold's minute doesn't end the next one`() = runTest {
        val hold = hold()
        hold.handOver(film)
        advanceTimeBy(30_000)
        hold.clear("the play was cancelled")
        hold.handOver(film)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(DisplayModeHold.Held(uhd, 23.976025f), hold.held.value)
    }
}

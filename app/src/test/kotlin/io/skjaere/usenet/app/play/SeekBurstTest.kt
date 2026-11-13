package io.skjaere.usenet.app.play

import androidx.media3.common.C
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekBurstTest {
    private var clock = 1_000_000L
    private val seeks = mutableListOf<Long>()

    private fun burst() = SeekBurst(now = { clock }, seek = { seeks += it })

    /** A tap [afterMs] after the one before, as the player is at [positionMs] in a ten-minute file; its seek waits [waitMs]. */
    private fun SeekBurst.tap(stepMs: Long, afterMs: Long = 0, positionMs: Long = 60_000, durationMs: Long = 600_000, waitMs: Long = SeekBurst.WINDOW_MS): Long {
        clock += afterMs
        return tap(stepMs, positionMs, durationMs, waitMs)
    }

    /** A remote's key event [afterMs] after the one before, the key down: a tap, the burst held open. */
    private fun SeekBurst.held(stepMs: Long, afterMs: Long = 0): Long = tap(stepMs, afterMs).also { holdOpen() }

    /** The clock moved on by [ms], and the burst told. */
    private fun SeekBurst.wait(ms: Long) {
        clock += ms
        update()
    }

    @Test
    fun `taps within the window add up to one seek, as the window ends`() {
        val b = burst()
        assertEquals(70_000, b.tap(10_000))
        assertEquals(80_000, b.tap(10_000, afterMs = 200))
        assertEquals(90_000, b.tap(10_000, afterMs = 200))
        assertEquals(100_000, b.tap(10_000, afterMs = 200))
        assertEquals(100_000, b.targetMs)
        assertEquals(40_000, b.shiftMs)
        b.wait(SeekBurst.WINDOW_MS - 1)
        assertEquals(emptyList(), seeks)
        b.wait(1)
        assertEquals(listOf(100_000L), seeks)
        assertNull(b.targetMs)
        assertNull(b.shiftMs)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(100_000L), seeks)
    }

    @Test
    fun `the window starts again with each tap, and nothing seeks before it ends`() {
        val b = burst()
        b.tap(10_000)
        b.wait(SeekBurst.WINDOW_MS - 50)
        b.tap(10_000)
        assertEquals(SeekBurst.WINDOW_MS, b.dueInMs())
        b.wait(SeekBurst.WINDOW_MS - 50)
        assertEquals(50, b.dueInMs())
        assertEquals(emptyList(), seeks)
        b.wait(50)
        assertEquals(listOf(80_000L), seeks)
        assertNull(b.dueInMs())
    }

    /** Touch can wait longer for the next tap than a remote: each tap says how long its seek waits. */
    @Test
    fun `a tap's seek waits as long as it says`() {
        val b = burst()
        b.tap(10_000, waitMs = 800)
        b.wait(799)
        assertEquals(emptyList(), seeks)
        b.wait(1)
        assertEquals(listOf(70_000L), seeks)
        // The last tap's wait counts.
        b.tap(10_000, waitMs = 800)
        b.tap(10_000, afterMs = 100)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(70_000L, 80_000L), seeks)
    }

    /**
     * A remote's key held down: its repeats can come further apart than the window (over HDMI-CEC), and nothing seeks
     * while it is down; its up settles the burst, which seeks once, the up's wait later.
     */
    @Test
    fun `held open, nothing seeks until the key's up, and then once, its wait later`() {
        val b = burst()
        b.held(10_000)
        b.wait(600)
        b.held(30_000)
        b.wait(600)
        b.held(60_000)
        b.wait(600)
        assertEquals(emptyList(), seeks)
        assertEquals(160_000, b.targetMs)
        b.settle(1_000)
        assertEquals(1_000, b.dueInMs())
        b.wait(999)
        assertEquals(emptyList(), seeks)
        b.wait(1)
        assertEquals(listOf(160_000L), seeks)
        assertNull(b.targetMs)
        // Settled with nothing under way: nothing to seek.
        b.settle(500)
        b.wait(500)
        assertEquals(listOf(160_000L), seeks)
    }

    /** A press or a hold the other way while the up's wait runs corrects the target: still one seek, after it. */
    @Test
    fun `a tap the other way while the settled seek waits joins it`() {
        val b = burst()
        b.held(10_000)
        b.held(90_000, afterMs = 1_000)
        b.settle(1_000)
        b.wait(600)
        b.held(-10_000)
        b.wait(300)
        b.settle(SeekBurst.WINDOW_MS)
        b.wait(SeekBurst.WINDOW_MS - 1)
        assertEquals(emptyList(), seeks)
        b.wait(1)
        assertEquals(listOf(150_000L), seeks)
    }

    /** A key's up lost (Bluetooth): the burst seeks all the same, [SeekBurst.HOLD_MS] after the key's last event. */
    @Test
    fun `held open with no event for a while, it seeks all the same`() {
        val b = burst()
        b.held(10_000)
        b.wait(SeekBurst.HOLD_MS - 100)
        b.held(10_000)
        b.wait(SeekBurst.HOLD_MS - 1)
        assertEquals(emptyList(), seeks)
        b.wait(1)
        assertEquals(listOf(80_000L), seeks)
        assertNull(b.targetMs)
    }

    /** Held open or not, anything else done meanwhile has the seek run first, and a seek from elsewhere replaces it. */
    @Test
    fun `held open, a flush still seeks at once and a drop still ends it`() {
        val b = burst()
        b.held(10_000)
        b.flush()
        assertEquals(listOf(70_000L), seeks)
        b.held(10_000)
        b.drop()
        b.wait(SeekBurst.HOLD_MS)
        b.settle(SeekBurst.WINDOW_MS)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(70_000L), seeks)
        // The next burst isn't held open: it seeks after the window.
        b.tap(10_000)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(70_000L, 70_000L), seeks)
    }

    @Test
    fun `rewinding stops at the start`() {
        val b = burst()
        assertEquals(5_000, b.tap(-10_000, positionMs = 15_000))
        assertEquals(0, b.tap(-10_000, afterMs = 100, positionMs = 15_100))
        assertEquals(0, b.tap(-10_000, afterMs = 100, positionMs = 15_200))
        assertEquals(-15_000, b.shiftMs)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(0L), seeks)
    }

    @Test
    fun `fast-forward stops a little before the end, and doesn't go back from inside that bit`() {
        val end = 600_000L - SeekBurst.END_MS
        val b = burst()
        assertEquals(end - 5_000, b.tap(10_000, positionMs = end - 15_000))
        assertEquals(end, b.tap(10_000, afterMs = 100))
        assertEquals(end, b.tap(10_000, afterMs = 100))
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(end), seeks)
        // Already past that point, fast-forward stays where playback is: no seek at all.
        assertEquals(end + 300, b.tap(10_000, positionMs = end + 300))
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(end), seeks)
        // Rewind from there goes back as usual.
        assertEquals(end - 9_700, b.tap(-10_000, positionMs = end + 300))
    }

    @Test
    fun `with the length not yet known only the start bounds a burst`() {
        val b = burst()
        assertEquals(70_000, b.tap(10_000, durationMs = C.TIME_UNSET))
        assertEquals(80_000, b.tap(10_000, afterMs = 100, durationMs = C.TIME_UNSET))
        assertEquals(0, burst().tap(-10_000, positionMs = 3_000, durationMs = C.TIME_UNSET))
    }

    @Test
    fun `back and forward mix, each from the target, not from where playback has got to`() {
        val b = burst()
        assertEquals(90_000, b.tap(-10_000, positionMs = 100_000))
        assertEquals(80_000, b.tap(-10_000, afterMs = 300, positionMs = 100_300))
        assertEquals(90_000, b.tap(10_000, afterMs = 300, positionMs = 100_600))
        assertEquals(-10_000, b.shiftMs)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(90_000L), seeks)
    }

    @Test
    fun `a burst back where it started seeks nowhere`() {
        val b = burst()
        b.tap(10_000)
        b.tap(-10_000, afterMs = 100)
        assertEquals(0, b.shiftMs)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(emptyList(), seeks)
        assertNull(b.targetMs)
    }

    @Test
    fun `anything else done meanwhile has the seek run at once, first`() {
        val b = burst()
        b.tap(10_000)
        b.tap(10_000, afterMs = 100)
        clock += 100
        b.flush()
        assertEquals(listOf(80_000L), seeks)
        assertNull(b.targetMs)
        b.wait(SeekBurst.WINDOW_MS)
        b.flush()
        assertEquals(listOf(80_000L), seeks)
    }

    @Test
    fun `a seek run at once leaves the burst going, to seek again as it ends only if moved since`() {
        val b = burst()
        // From the end, as a player that has ended is.
        assertEquals(590_000, b.tap(-10_000, positionMs = 600_000))
        b.seekNow()
        assertEquals(listOf(590_000L), seeks)
        assertEquals(590_000, b.targetMs)
        b.seekNow()
        assertEquals(listOf(590_000L), seeks)
        assertEquals(580_000, b.tap(-10_000, afterMs = 100, positionMs = 590_000))
        assertEquals(-20_000, b.shiftMs)
        assertEquals(590_000, b.tap(10_000, afterMs = 100, positionMs = 590_100))
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(590_000L), seeks)
        assertNull(b.targetMs)

        b.tap(-10_000, positionMs = 600_000)
        b.seekNow()
        b.tap(-10_000, afterMs = 100)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(listOf(590_000L, 590_000L, 580_000L), seeks)
        // Nothing to seek while no burst is under way.
        b.seekNow()
        assertEquals(3, seeks.size)
    }

    /**
     * Ended, the player waits for a burst's seek still to come, which takes it out of its end (ControlsPlayer, EndOfPlay).
     * Fast-forward from inside the file's last second goes nowhere, and a burst already sent where it goes has nothing
     * more to come until a tap moves it: neither is one to wait for.
     */
    @Test
    fun `it says whether its seek is still to move the player`() {
        val b = burst()
        assertFalse(b.seekToCome, "no burst")
        b.tap(10_000)
        assertTrue(b.seekToCome)
        b.wait(SeekBurst.WINDOW_MS)
        assertFalse(b.seekToCome, "sought")
        val end = 600_000L - SeekBurst.END_MS
        assertEquals(end + 300, b.tap(10_000, positionMs = end + 300))
        assertFalse(b.seekToCome, "fast-forward from the file's last second")
        b.tap(-10_000, afterMs = 100)
        assertTrue(b.seekToCome, "rewound from there")
        b.seekNow()
        assertFalse(b.seekToCome, "sent where it goes")
        b.tap(-10_000, afterMs = 100)
        assertTrue(b.seekToCome, "moved since")
        b.drop()
        assertFalse(b.seekToCome, "dropped")
    }

    @Test
    fun `a seek from elsewhere replaces the burst's`() {
        val b = burst()
        b.tap(10_000)
        b.tap(10_000, afterMs = 100)
        b.drop()
        assertNull(b.targetMs)
        b.wait(SeekBurst.WINDOW_MS)
        b.flush()
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun `it says how far a burst goes, to the nearest second`() {
        val b = burst()
        assertNull(b.said)
        b.tap(10_000)
        assertEquals(SeekBurst.Said("+10 s", "Forward 10 seconds"), b.said)
        b.tap(-10_000, afterMs = 100)
        b.tap(-10_000, afterMs = 100)
        assertEquals(SeekBurst.Said("\u221210 s", "Back 10 seconds"), b.said)
        // Taps that cancel out, away from the start and the end.
        b.tap(10_000, afterMs = 100)
        assertEquals(SeekBurst.Said("0 s", "0 seconds"), b.said)
        assertEquals(SeekBurst.Said("\u22121 s", "Back 1 second"), burst().apply { tap(-10_000, positionMs = 600) }.said)
        assertEquals(SeekBurst.Said("+1 s", "Forward 1 second"), burst().apply { tap(10_000, positionMs = 598_400) }.said)
    }

    /** A remote's held key goes minutes at a time: said in minutes and seconds, as the controls write a time. */
    @Test
    fun `from a minute on it says minutes and seconds`() {
        fun said(stepMs: Long, positionMs: Long = 60_000) = burst().apply { tap(stepMs, positionMs = positionMs, durationMs = 7_200_000) }.said
        assertEquals(SeekBurst.Said("+59 s", "Forward 59 seconds"), said(59_000))
        assertEquals(SeekBurst.Said("+1:00", "Forward 1 minute"), said(60_000))
        assertEquals(SeekBurst.Said("+1:30", "Forward 1 minute 30 seconds"), said(90_000))
        assertEquals(SeekBurst.Said("+9:31", "Forward 9 minutes 31 seconds"), said(570_600))
        assertEquals(SeekBurst.Said("\u22122:00", "Back 2 minutes"), said(-120_000, positionMs = 600_000))
        assertEquals(SeekBurst.Said("+1:00:00", "Forward 1 hour"), said(3_600_000))
        assertEquals(SeekBurst.Said("+1:02:05", "Forward 1 hour 2 minutes 5 seconds"), said(3_725_000))
        assertEquals(SeekBurst.Said("+2:00:01", "Forward 2 hours 1 second"), burst().apply { tap(7_201_000, positionMs = 0, durationMs = C.TIME_UNSET) }.said)
    }

    /** Where the time bar doesn't show where it goes, the label says that too. */
    @Test
    fun `said with where it goes`() {
        assertEquals(SeekBurst.Said("+1:30 · 19:52", "Forward 1 minute 30 seconds, 19:52"), SeekBurst.Said("+1:30", "Forward 1 minute 30 seconds").withTarget("19:52"))
        assertEquals(SeekBurst.Said("At the start · 0:00", "At the start, 0:00"), SeekBurst.Said("At the start").withTarget("0:00"))
    }

    /** The side mark shows on the side a burst goes, with chevrons pointing its way, and none where it goes nowhere. */
    @Test
    fun `it says which way the burst goes, where it goes anywhere`() {
        fun way(stepMs: Long, positionMs: Long = 60_000) = burst().apply { tap(stepMs, positionMs = positionMs) }.said?.forward
        assertEquals(true, way(10_000))
        assertEquals(false, way(-10_000))
        assertEquals(true, way(10_000, positionMs = 598_400))
        assertEquals(null, way(-10_000, positionMs = 0))
        assertEquals(null, way(10_000, positionMs = 599_500))
        assertEquals(null, burst().apply { tap(10_000); tap(-10_000) }.said?.forward)
        assertEquals(true, SeekBurst.Said("+1:30", "Forward 1 minute 30 seconds").withTarget("19:52").forward)
        assertEquals(false, SeekBurst.Said("\u221220 s", "Back 20 seconds").withTarget("18:02").forward)
        assertEquals(null, SeekBurst.Said("At the start").withTarget("0:00").forward)
    }

    @Test
    fun `a burst that a bound keeps from moving says it is at the start or the end`() {
        val start = SeekBurst.Said("At the start", "At the start")
        val end = SeekBurst.Said("At the end", "At the end")
        assertEquals(start, burst().apply { tap(-10_000, positionMs = 0) }.said)
        assertEquals(start, burst().apply { tap(-10_000, positionMs = 400) }.said)
        assertEquals(start, burst().apply { tap(-10_000, positionMs = 0, durationMs = C.TIME_UNSET) }.said)
        // In the last second, and after the end, where a player that has ended is.
        assertEquals(end, burst().apply { tap(10_000, positionMs = 599_500) }.said)
        assertEquals(end, burst().apply { tap(10_000, positionMs = 600_000) }.said)
        assertEquals(end, burst().apply { tap(10_000, positionMs = 598_600) }.said)
    }

    @Test
    fun `after a burst's seek the next burst starts from where playback is`() {
        val b = burst()
        b.tap(10_000)
        b.wait(SeekBurst.WINDOW_MS)
        assertEquals(130_000, b.tap(10_000, afterMs = 5_000, positionMs = 120_000))
        assertEquals(10_000, b.shiftMs)
    }
}

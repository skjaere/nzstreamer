package io.skjaere.usenet.app.play

import io.skjaere.usenet.metadata.Segment
import io.skjaere.usenet.metadata.SegmentKind
import io.skjaere.usenet.metadata.SegmentSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipButtonTest {
    private var clock = 1_000_000L
    private val seeks = mutableListOf<Long>()
    private val intro = Segment(SegmentKind.Intro, 5_000, 25_000, SegmentSource.Chapters)
    private val credits = Segment(SegmentKind.Credits, 40 * 60_000L, 45 * 60_000L, SegmentSource.Chapters)
    private val segments = listOf(intro, credits)

    private fun button() = SkipButton(now = { clock }, seek = { seeks += it })

    /**
     * Plays from [fromMs] for [forMs], updating every 100 ms as the player does; returns what showed
     * at each tick. [stalled] holds the position meanwhile: loading, rebuffering or paused.
     */
    private fun SkipButton.play(
        fromMs: Long,
        forMs: Long,
        segments: List<Segment> = this@SkipButtonTest.segments,
        controlsShown: Boolean = false,
        cardShown: Boolean = false,
        stalled: Boolean = false,
    ): List<SkipButton.Shown?> {
        val start = clock
        return (0..forMs step 100).map { dt ->
            clock = start + dt
            update(if (stalled) fromMs else fromMs + dt, segments, controlsShown, cardShown)
        }
    }

    @Test
    fun `entering an intro shows "Skip intro", which goes to its end`() {
        val b = button()
        assertNull(b.update(4_900, segments, controlsShown = false, cardShown = false))
        val shown = assertNotNull(b.update(5_000, segments, controlsShown = false, cardShown = false))
        assertEquals("Skip intro", shown.label)
        assertEquals(25_000, shown.targetMs)
    }

    @Test
    fun `a recap says "Skip recap"`() {
        val recap = Segment(SegmentKind.Recap, 0, 40_000, SegmentSource.TheIntroDb)
        assertEquals("Skip recap", button().update(0, listOf(recap), controlsShown = false, cardShown = false)?.label)
    }

    @Test
    fun `it shows for ten seconds after entering, then hides`() {
        val steps = button().play(5_000, 15_000)
        // 100 ticks at 100 ms: the first ten seconds.
        assertEquals((0 until 100).toList(), steps.indices.filter { steps[it] != null })
    }

    /** The device tests' shorter time ([io.skjaere.usenet.app.PlayerActivity.timings]): it shows for that long instead. */
    @Test
    fun `a shorter time, as a device test gives, hides it sooner`() {
        val steps = SkipButton(now = { clock }, seek = { seeks += it }, showForMs = 1_000).play(5_000, 3_000)
        assertEquals((0 until 10).toList(), steps.indices.filter { steps[it] != null })
    }

    @Test
    fun `its ten seconds are of playback, so loading, stalls and pauses don't use them up`() {
        // Entered as the file opens, with six seconds of loading before the picture moves.
        val opening = button()
        assertTrue(opening.play(5_000, 6_000, stalled = true).all { it != null })
        val played = opening.play(5_000, 12_000)
        assertEquals((0 until 100).toList(), played.indices.filter { played[it] != null })
        // Four seconds in, a long stall or pause: six seconds of playback are left after it.
        val stalling = button()
        stalling.play(5_000, 4_000)
        assertTrue(stalling.play(9_000, 20_000, stalled = true).all { it != null })
        val rest = stalling.play(9_000, 7_000)
        assertEquals((0 until 60).toList(), rest.indices.filter { rest[it] != null })
    }

    @Test
    fun `a seek within the segment neither restarts nor uses up its ten seconds`() {
        val long = listOf(Segment(SegmentKind.Intro, 5_000, 85_000, SegmentSource.Chapters))
        val b = button()
        b.play(5_000, 4_000, long)
        // Back to its start, then far ahead: neither counts, and six seconds have played.
        b.play(5_000, 2_000, long)
        val ahead = b.play(60_000, 6_000, long)
        assertEquals((0 until 40).toList(), ahead.indices.filter { ahead[it] != null })
    }

    @Test
    fun `it shows again whenever the controls are up while still inside the segment`() {
        val b = button()
        b.play(5_000, 12_000)
        assertTrue(b.play(17_100, 3_000, controlsShown = true).all { it?.targetMs == 25_000L })
        assertTrue(b.play(20_200, 2_000).all { it == null })
        // Past the segment, the controls bring nothing back.
        assertTrue(b.play(25_000, 2_000, controlsShown = true).all { it == null })
    }

    @Test
    fun `it takes a TV remote's focus whenever it shows with the controls hidden, not when it comes back with them`() {
        val b = button()
        assertTrue(b.play(5_000, 12_000).filterNotNull().all { it.takeFocus })
        // Back with the controls: media3 has just put the focus on play/pause, where it stays.
        assertTrue(b.play(17_100, 2_000, controlsShown = true).all { it != null && !it.takeFocus })
        // Entering with the controls up leaves the focus with them; as they go while it still
        // shows, it takes it.
        val withControls = button()
        assertTrue(withControls.play(5_000, 1_000, controlsShown = true).all { it != null && !it.takeFocus })
        assertTrue(withControls.play(6_100, 2_000).all { it != null && it.takeFocus })
        // A key that brings them back moves the focus to play/pause, and it doesn't ask for it
        // while they're up; it does again as they go.
        assertTrue(withControls.play(8_200, 1_000, controlsShown = true).all { it != null && !it.takeFocus })
        assertTrue(withControls.play(9_300, 500).all { it != null && it.takeFocus })
    }

    @Test
    fun `seeking into a segment counts as entering it`() {
        val b = button()
        assertNull(b.update(1_000, segments, controlsShown = false, cardShown = false))
        clock += 500
        val steps = b.play(12_000, 9_900)
        assertTrue(steps.all { it != null })
        // Out and back in: another ten seconds.
        b.play(1_000, 0)
        clock += 30_000
        assertNotNull(b.update(6_000, segments, controlsShown = false, cardShown = false))
    }

    @Test
    fun `it goes a second before the segment ends`() {
        val b = button()
        b.play(20_000, 0)
        clock += 100
        assertNotNull(b.update(23_999, segments, controlsShown = false, cardShown = false))
        assertNull(b.update(24_000, segments, controlsShown = true, cardShown = false))
    }

    @Test
    fun `pressing it seeks to the segment's end, once`() {
        val b = button()
        b.play(5_000, 2_000)
        assertTrue(b.press())
        assertEquals(listOf(25_000L), seeks)
        assertFalse(b.press())
        // The seek takes a moment to show in the position: no second press shows meanwhile.
        assertNull(b.update(7_100, segments, controlsShown = true, cardShown = false))
        assertEquals(listOf(25_000L), seeks)
    }

    @Test
    fun `pressing when nothing shows does nothing`() {
        val b = button()
        assertFalse(b.press())
        b.play(5_000, 12_000)
        assertFalse(b.press())
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun `it never skips by itself`() {
        val b = button()
        val steps = b.play(0, 30_000) + b.play(5_000, 25_000, controlsShown = true)
        assertTrue(steps.any { it != null })
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun `Back hides it until playback next enters a segment`() {
        val b = button()
        b.play(5_000, 1_000)
        b.dismiss()
        assertTrue(b.play(6_100, 5_000).all { it == null })
        assertTrue(b.play(11_200, 2_000, controlsShown = true).all { it == null })
        assertFalse(b.press())
        b.play(30_000, 0)
        assertNotNull(b.update(5_000, segments, controlsShown = false, cardShown = false))
    }

    @Test
    fun `only intros and recaps get a button`() {
        val preview = Segment(SegmentKind.Preview, 30_000, 60_000, SegmentSource.Chapters)
        assertTrue(button().play(40 * 60_000L, 5_000, listOf(credits, preview), controlsShown = true).all { it == null })
        assertTrue(button().play(30_000, 5_000, listOf(credits, preview), controlsShown = true).all { it == null })
    }

    @Test
    fun `from a recap straight into an intro the button changes to "Skip intro"`() {
        val recap = Segment(SegmentKind.Recap, 0, 30_000, SegmentSource.TheIntroDb)
        val intro = Segment(SegmentKind.Intro, 30_000, 60_000, SegmentSource.TheIntroDb)
        val steps = button().play(0, 35_000, listOf(recap, intro))
        assertEquals("Skip recap", steps.first()?.label)
        assertEquals(30_000L, steps.first()?.targetMs)
        assertEquals("Skip intro", steps[300]?.label)
        assertEquals(60_000L, steps[300]?.targetMs)
    }

    @Test
    fun `it stays hidden while the next-episode card shows, so the two never overlap`() {
        val b = button()
        assertTrue(b.play(5_000, 3_000, cardShown = true, controlsShown = true).all { it == null })
        assertNotNull(b.update(8_100, segments, controlsShown = false, cardShown = false))
    }
}

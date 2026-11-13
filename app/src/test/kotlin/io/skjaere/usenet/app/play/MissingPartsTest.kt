package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissingPartsTest {
    private var clock = 1_000_000L
    private val parts = MissingParts(now = { clock })

    /** The [i]th article of a release, 750 KB parts: missing ones are reported a part at a time. */
    private fun article(i: Int) = i * 750_000L until (i + 1) * 750_000L

    /** Finds a missing article at each of [minutes] into the film, none beside another. */
    private fun findAtMinutes(vararg minutes: Int) = minutes.forEach { m -> parts.found(article(2 * m), m * 60_000L) }

    /** One tick of the player's loop, at [positionMs], [dtMs] after the last. */
    private fun tick(positionMs: Long, dtMs: Long = 250, waiting: Boolean = false, tryAnother: Boolean = true): MissingParts.Shown {
        clock += dtMs
        return parts.update(positionMs, waiting, tryAnother)
    }

    /** Plays on from [fromMs] for [forMs], ticking every 250 ms; the last tick's. [stalled] holds the position meanwhile. */
    private fun play(fromMs: Long, forMs: Long, waiting: Boolean = false, tryAnother: Boolean = true, stalled: Boolean = false): MissingParts.Shown {
        var shown = tick(fromMs, 0, waiting, tryAnother)
        for (dt in 250..forMs step 250) shown = tick(if (stalled) fromMs else fromMs + dt, 250, waiting, tryAnother)
        return shown
    }

    @Test
    fun `three missing spots within ten minutes of the film ask, and say how many so far`() {
        findAtMinutes(1, 5)
        assertNull(tick(0).prompt)
        findAtMinutes(10)
        assertEquals(MissingParts.Prompt(3, tryAnother = true), tick(0).prompt)
        assertEquals(3, parts.count)
    }

    /** By their places in the film, found ahead or not: three in 25 minutes don't ask; a fourth and fifth close by do. */
    @Test
    fun `spots spread over more than ten minutes of the film don't ask`() {
        findAtMinutes(0, 11, 22)
        assertNull(play(0, 2_000).prompt)
        findAtMinutes(25)
        assertNull(tick(2_000).prompt)
        findAtMinutes(30)
        assertEquals(MissingParts.Prompt(5, tryAnother = true), tick(2_000).prompt)
    }

    /** Articles side by side are one spot, the part found by a stream that started inside one is the same, and found again (a seek back) it isn't counted twice. */
    @Test
    fun `a spot counts once, its articles merged and found again or not`() {
        assertTrue(parts.found(0L until 750_000, 60_000))
        assertFalse(parts.found(750_000L until 1_500_000, 60_500))
        assertFalse(parts.found(100_000L until 750_000, 60_000))
        assertFalse(parts.found(0L until 750_000, 60_000))
        assertEquals(1, parts.count)
        assertTrue(parts.found(article(10), 600_000))
        assertEquals(2, parts.count)
        // One that fills the space between two makes them one.
        parts.found(1_500_000L until 7_500_000, 70_000)
        assertEquals(1, parts.count)
    }

    @Test
    fun `keep watching asks again at six, then at twelve, then never`() {
        findAtMinutes(1, 2, 3)
        assertEquals(3, tick(0).prompt?.count)
        parts.keepWatching()
        assertNull(tick(0).prompt)
        for (m in 4..5) {
            findAtMinutes(m)
            assertNull(tick(0).prompt, "asked at ${parts.count}")
        }
        findAtMinutes(6)
        assertEquals(6, tick(0).prompt?.count)
        parts.keepWatching()
        for (m in 7..11) {
            findAtMinutes(m)
            assertNull(tick(0).prompt, "asked at ${parts.count}")
        }
        findAtMinutes(12)
        assertEquals(12, tick(0).prompt?.count)
        parts.keepWatching()
        for (m in 13..40) {
            findAtMinutes(m)
            assertNull(tick(0).prompt, "asked at ${parts.count}")
        }
    }

    /** It says the count as it came (TalkBack reads it once); answered with seven found, it asks next at twelve. */
    @Test
    fun `the prompt keeps its count, and an answer past a threshold waits for the next`() {
        findAtMinutes(1, 2, 3)
        assertEquals(3, tick(0).prompt?.count)
        findAtMinutes(4, 5, 6, 7)
        assertEquals(3, tick(0).prompt?.count)
        parts.keepWatching()
        findAtMinutes(8, 9, 10, 11)
        assertNull(tick(0).prompt)
        findAtMinutes(12)
        assertEquals(12, tick(0).prompt?.count)
    }

    @Test
    fun `unanswered, it hides after twenty seconds of playback, as keep watching`() {
        findAtMinutes(1, 2, 3)
        assertEquals(3, play(60_000, 19_750).prompt?.count)
        val last = tick(80_000)
        assertNull(last.prompt)
        assertTrue(last.timedOut)
        assertFalse(tick(80_250).timedOut)
        // As keep watching: not again before six.
        findAtMinutes(4, 5)
        assertNull(tick(80_500).prompt)
        findAtMinutes(6)
        assertEquals(6, tick(80_750).prompt?.count)
    }

    /** Playback's twenty seconds, not the clock's: paused, loading or stalled, the position doesn't move. */
    @Test
    fun `pauses and stalls don't use up its twenty seconds`() {
        findAtMinutes(1, 2, 3)
        assertEquals(3, play(60_000, 60_000, stalled = true).prompt?.count)
        // A seek ahead counts no more than the clock: 250 ms here.
        assertEquals(3, tick(600_000).prompt?.count)
        assertEquals(3, play(600_000, 19_000).prompt?.count)
        assertNull(play(619_000, 1_000).prompt)
    }

    /** Behind the next-episode card, the skip button or "Playback stopped", or under a side panel, it waits. */
    @Test
    fun `while it waits for the slot or under a side panel its time doesn't run`() {
        findAtMinutes(1, 2, 3)
        assertEquals(3, play(60_000, 30_000, waiting = true).prompt?.count)
        assertEquals(3, play(90_000, 19_000).prompt?.count)
        assertNull(play(109_000, 1_000).prompt)
    }

    @Test
    fun `without another release to try it says the count alone, for five seconds of playback`() {
        findAtMinutes(1, 2, 3)
        assertEquals(MissingParts.Prompt(3, tryAnother = false), play(60_000, 4_750, tryAnother = false).prompt)
        assertNull(tick(65_000, tryAnother = false).prompt)
        findAtMinutes(4, 5, 6)
        assertEquals(MissingParts.Prompt(6, tryAnother = false), tick(65_250, tryAnother = false).prompt)
    }

    /** Where the player jumped past a missing part ahead, the notice comes as playback gets there: once. */
    @Test
    fun `the notice comes as playback reaches where a jump went on from`() {
        parts.jumped(article(1), toMs = 65_000)
        assertFalse(play(60_000, 4_000).notice)
        // The jump: the position goes on from there.
        assertTrue(tick(65_050).notice)
        assertFalse(tick(65_300).notice)
        assertFalse(play(65_300, 10_000).notice)
    }

    /** Three taps on +10 s ask for 41:10, inside a missing part: playback starts at the keyframe after it, 41:18, and says so. */
    @Test
    fun `a seek that lands past a missing part says so, one that goes far past it says nothing`() {
        parts.jumped(article(1), toMs = 2_478_000)
        tick(2_440_000)
        assertTrue(tick(2_478_100).notice)
        parts.jumped(article(5), toMs = 2_600_000)
        tick(2_500_000)
        assertFalse(tick(3_000_000).notice)
        // Nor later, back there.
        assertFalse(tick(2_600_000).notice)
    }

    @Test
    fun `a part jumped past again (a seek back) says so again`() {
        parts.jumped(article(1), toMs = 65_000)
        tick(64_000)
        assertTrue(tick(65_000).notice)
        tick(30_000)
        parts.jumped(article(1), toMs = 65_000)
        tick(64_000)
        assertTrue(tick(65_000).notice)
    }

    /** The prompt says more: a jump reached while it shows says nothing over it. */
    @Test
    fun `no notice while the prompt shows`() {
        findAtMinutes(1, 2, 3)
        parts.jumped(article(1), toMs = 65_000)
        tick(64_000)
        val shown = tick(65_000)
        assertEquals(3, shown.prompt?.count)
        assertFalse(shown.notice)
    }

    /**
     * L104: a Cast device stuck at a missing part can't play on past it, so the prompt asks at once, whatever the count;
     * answered, it asks again at the next count past it, as after any ask.
     */
    @Test
    fun `asked at once, whatever the count, then again at the next count past it`() {
        findAtMinutes(30)
        assertNull(tick(0).prompt)
        parts.askNow(tryAnother = true)
        assertEquals(MissingParts.Prompt(1, tryAnother = true), tick(0).prompt)
        parts.keepWatching()
        findAtMinutes(32, 34)
        assertNull(tick(0).prompt, "three so far, the first ask's spent")
        findAtMinutes(36, 38, 40)
        assertEquals(MissingParts.Prompt(6, tryAnother = true), tick(0).prompt)
    }
}

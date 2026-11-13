package io.skjaere.usenet.app.play

import androidx.media3.common.C
import io.skjaere.usenet.app.play.ScrubRate.Speed
import io.skjaere.usenet.player.PlayerFactory
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrubRateTest {
    private val unknown = C.TIME_UNSET
    private val min22 = 22 * MINUTE
    private val min45 = 45 * MINUTE
    private val hours2 = 120 * MINUTE
    private val hours3 = 180 * MINUTE
    private val hours10 = 600 * MINUTE
    private val lengths = listOf(unknown, min22, min45, hours2, hours3, hours10)

    /** Seconds of the file per second held at [atMs], over the 100 ms around it. */
    private fun rate(atMs: Long, durationMs: Long) = (ScrubRate.offsetMs(atMs + 50, durationMs) - ScrubRate.offsetMs(atMs - 50, durationMs)) / 100.0

    /** Seconds held until a press from 0:00 reaches [share] of a file [durationMs] long. */
    private fun secondsTo(share: Double, durationMs: Long): Double =
        (0L..60_000L step 10).first { ScrubRate.offsetMs(it, durationMs) >= share * durationMs } / 1_000.0

    @Test
    fun `a press goes as far as the controls' buttons, whatever the file's length`() {
        lengths.forEach { d ->
            assertEquals(PlayerFactory.SEEK_FORWARD_MS, ScrubRate.offsetMs(0, d), "length $d")
            assertEquals(PlayerFactory.SEEK_BACK_MS, ScrubRate.offsetMs(0, d), "length $d")
            assertEquals(10_000, ScrubRate.offsetMs(-50, d), "length $d")
        }
    }

    /** L3: today's first 2 s kept, so a jump of a minute or so is as fine as before. */
    @Test
    fun `held, it goes on at 40 s a second for the first 2 s, whatever the file's length`() {
        lengths.forEach { d ->
            assertEquals(30_000, ScrubRate.offsetMs(500, d), "length $d")
            assertEquals(50_000, ScrubRate.offsetMs(1_000, d), "length $d")
            assertEquals(70_000, ScrubRate.offsetMs(1_500, d), "length $d")
            assertEquals(90_000, ScrubRate.offsetMs(2_000, d), "length $d")
            listOf(100L, 1_000L, 1_900L).forEach { at -> assertEquals(40.0, rate(at, d), 0.5, "at $at ms, length $d") }
        }
    }

    @Test
    fun `from 2 s the speed doubles every 350 ms`() {
        listOf(hours2, hours3, hours10).forEach { d ->
            val rates = (0..3).map { k -> rate(2_000 + 350L * k + 175, d) }
            assertEquals(40 * sqrt(2.0), rates.first(), 0.5, "length $d")
            rates.zipWithNext().forEach { (a, b) -> assertEquals(2.0, b / a, 0.02, "length $d: $rates") }
        }
    }

    @Test
    fun `the top speed is an eighth of the file a second, never under 240 s a second, and 240 while the length is unknown`() {
        mapOf(unknown to 240.0, min22 to 240.0, min45 to 337.5, hours2 to 900.0, hours3 to 1_350.0, hours10 to 4_500.0).forEach { (d, top) ->
            listOf(8_000L, 15_000L, 30_000L).forEach { at -> assertEquals(top, rate(at, d), 0.5, "at $at ms, length $d") }
        }
    }

    /** A key's repeats only ever move the target on: each repeat adds what the hold has added since the one before. */
    @Test
    fun `the longer it is held the further it goes`() {
        lengths.forEach { d ->
            val offsets = (0L..30_000L step 50).map { ScrubRate.offsetMs(it, d) }
            assertTrue(offsets.zipWithNext().all { (a, b) -> b > a }, "length $d: $offsets")
        }
    }

    /** L3's table, from 0:00: a quarter, half and all of the file in about the same time from 45 min up. */
    @Test
    fun `a quarter, half and the whole of a 45 min, 2 h or 3 h file take about the same seconds held`() {
        mapOf(min45 to listOf(4.4, 6.4, 10.4), hours2 to listOf(5.0, 7.0, 11.0), hours3 to listOf(5.2, 7.2, 11.2)).forEach { (d, seconds) ->
            listOf(0.25, 0.5, 1.0).zip(seconds).forEach { (share, expected) ->
                assertEquals(expected, secondsTo(share, d), 0.3, "${share * 100}% of $d ms")
            }
        }
    }

    @Test
    fun `a 22 min episode stays at the 240 s a second floor`() {
        assertEquals(240.0, rate(5_000, min22), 0.5)
        assertEquals(ScrubRate.offsetMs(10_000, unknown), ScrubRate.offsetMs(10_000, min22))
    }

    /** W8's speed cue: none in the first 2 s, two chevrons while the speed doubles, three at the top. */
    @Test
    fun `the speed is fine for 2 s, rising while it doubles, then at the top`() {
        lengths.forEach { d ->
            assertEquals(Speed.Fine, ScrubRate.speed(0, d), "length $d")
            assertEquals(Speed.Fine, ScrubRate.speed(1_999, d), "length $d")
            assertEquals(Speed.Rising, ScrubRate.speed(2_000, d), "length $d")
            assertEquals(Speed.Top, ScrubRate.speed(12_000, d), "length $d")
        }
        // The top comes as the doubling reaches it: 2.9 s at the floor, 3.8 s for 3 h.
        listOf(unknown, min22).forEach { d ->
            assertEquals(Speed.Rising, ScrubRate.speed(2_850, d), "length $d")
            assertEquals(Speed.Top, ScrubRate.speed(2_950, d), "length $d")
        }
        assertEquals(Speed.Rising, ScrubRate.speed(3_700, hours3))
        assertEquals(Speed.Top, ScrubRate.speed(3_850, hours3))
    }

    /** L5: after a hold's up the seek waits for a correction, a second once the hold went past its first 2 s. */
    @Test
    fun `after the up the seek waits half a second, a second after a hold past its first 2 s`() {
        listOf(0L, 400L, 1_999L).forEach { held -> assertEquals(SeekBurst.WINDOW_MS, ScrubRate.settleMs(held), "held $held ms") }
        listOf(2_000L, 3_500L, 12_000L).forEach { held -> assertEquals(1_000, ScrubRate.settleMs(held), "held $held ms") }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}

package app.nzstreamer.plugin.sportarr.ui

import android.view.KeyEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The right pane's OK guard from Try again's move of the focus to a part's row (L-B10): the rest of the OK that moved it,
 * pressed twice or held, never reaches the row. A TV's own remote over HDMI-CEC sends a held OK as separate presses, each
 * with its repeat count 0, for as long as it is held. Times are in ms from the press on Try again, whose up moved the
 * focus 71 ms in.
 */
class HeldOkGuardTest {
    private val first = 10_000L
    private val guard = HeldOkGuard(first + CEC_UP_MS)

    /** Whether a press of OK [at] ms, its up [upMs] later, was ignored going down and coming up. */
    private fun press(at: Long, upMs: Long = CEC_UP_MS): Pair<Boolean, Boolean> = down(at) to up(at + upMs)

    /** A remote's key going down, its [repeat]th repeat where above 0, [at] ms: whether it was ignored. */
    private fun down(at: Long, repeat: Int = 0, key: Int = KeyEvent.KEYCODE_DPAD_CENTER) =
        guard.blocks(key, down = true, repeatCount = repeat, eventTime = first + at)

    private fun up(at: Long, key: Int = KeyEvent.KEYCODE_DPAD_CENTER) = guard.blocks(key, down = false, repeatCount = 0, eventTime = first + at)

    @Test
    fun `OK held over HDMI-CEC is ignored, every press and its up, for as long as its presses keep coming`() {
        CEC_HELD.forEach { (pressed, released) -> assertEquals(true to true, press(pressed, upMs = released - pressed), "the press at $pressed ms") }
    }

    @Test
    fun `the next press once a hold's presses have stopped coming for 600 ms is the row's, down and up`() {
        assertEquals(true to true, press(at = 501))
        assertEquals(false to false, press(at = 501 + 600), "the next press")
    }

    /** The second press 150 ms after the first, whose up moved the focus 40 ms in. */
    @Test
    fun `OK pressed twice quickly is ignored, with each of a remote's OK keys`() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A).forEach { key ->
            val quick = HeldOkGuard(first + 40)
            assertTrue(quick.blocks(key, down = true, repeatCount = 0, eventTime = first + 150), "down of $key")
            assertTrue(quick.blocks(key, down = false, repeatCount = 0, eventTime = first + 190), "up of $key")
        }
    }

    /** One sent before the last press ignored keeps the window that press began. */
    @Test
    fun `a press that reaches the app late, sent before the one before it, keeps the hold as long`() {
        assertEquals(true to true, press(at = 501))
        assertEquals(true to true, press(at = 300), "the press sent earlier, reaching the app late")
        assertEquals(true to true, press(at = 1_050), "within 600 ms of the press at 501 ms")
    }

    /** A remote that repeats a held key (its repeat count above 0) sends its up as it is let go: the hold ends there. */
    @Test
    fun `a held OK whose presses repeat is ignored up and all, and the press after its up is the row's`() {
        assertEquals(true, down(at = 150))
        (1..20).forEach { n -> assertEquals(true, down(at = 550 + 50L * n, repeat = n), "repeat $n") }
        assertEquals(true, up(at = 1_700))
        assertEquals(false to false, press(at = 1_900), "the next press")
    }

    /** As a sheet's guard: a press let through is the row's, held or not, and the row clicks on its up. */
    @Test
    fun `a press let through, held with repeats, comes up through it`() {
        assertFalse(down(at = 800))
        (1..6).forEach { n -> assertEquals(true, down(at = 1_300 + 50L * n, repeat = n), "repeat $n") }
        assertFalse(up(at = 1_700), "the up")
    }

    @Test
    fun `other keys go through at once, so the remote still moves the focus`() {
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK).forEach { key ->
            assertFalse(down(at = 100, key = key), "down of $key")
            assertFalse(down(at = 150, repeat = 1, key = key), "repeat of $key")
            assertFalse(up(at = 200, key = key), "up of $key")
        }
    }

    private companion object {
        /** A press's up after its down over HDMI-CEC. */
        const val CEC_UP_MS = 71L

        /** A key held about 3 s on a TV's own remote over HDMI-CEC: each press's down and up after the first's, as it sent them. */
        val CEC_HELD = listOf(
            501L to 572L, 858L to 929L, 1_024L to 1_095L, 1_257L to 1_328L, 1_508L to 1_579L, 1_761L to 1_831L, 2_011L to 2_082L,
            2_263L to 2_334L, 2_517L to 2_588L, 2_766L to 2_838L, 3_018L to 3_089L,
        )
    }
}

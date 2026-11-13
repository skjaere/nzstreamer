package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.SeekBurst
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L38, item 26: the remote's seeks. ±10 s taps in quick succession make one seek after the last, as the app's player's
 * do (its SeekBurst), sent as `SeekBy` from the position the burst's first tap was given, which the TV applies to its own
 * exact position; the time bar sends one `SeekTo`, as the finger lifts. A clock of the test's own.
 */
class RemoteSeekTest {
    private var clock = 1_000_000L
    private val sent = mutableListOf<String>()
    private val seek = RemoteSeek(now = { clock }, seekBy = { sent += "by $it" }, seekTo = { sent += "to $it" })

    /** The clock moved on by [ms], and the seek told, as the screen's loop tells it. */
    private fun later(ms: Long) {
        clock += ms
        seek.update()
    }

    @Test
    fun `three taps make one SeekBy of 30 s, from the position the first tap was given`() {
        seek.tap(10_000, positionMs = 1_930_000, durationMs = 3_120_000)
        // The TV's position moves on between the taps; the burst goes on from its own target.
        clock += 200
        seek.tap(10_000, positionMs = 1_930_200, durationMs = 3_120_000)
        clock += 200
        seek.tap(10_000, positionMs = 1_930_400, durationMs = 3_120_000)
        assertEquals(1_960_000L, seek.shown(1_930_400), "the time bar shows where it goes")
        later(SeekBurst.WINDOW_MS - 1)
        assertEquals(emptyList(), sent)
        later(1)
        assertEquals(listOf("by 30000"), sent)
        assertNull(seek.dueInMs())
        assertEquals(1_930_900L, seek.shown(1_930_900), "then where the TV says")
    }

    @Test
    fun `back taps make one SeekBy back, and a burst that ends where it started seeks nowhere`() {
        seek.tap(-10_000, positionMs = 600_000, durationMs = 3_120_000)
        seek.tap(-10_000, positionMs = 600_000, durationMs = 3_120_000)
        later(SeekBurst.WINDOW_MS)
        seek.tap(10_000, positionMs = 580_000, durationMs = 3_120_000)
        seek.tap(-10_000, positionMs = 580_000, durationMs = 3_120_000)
        later(SeekBurst.WINDOW_MS)
        // At the start: no further back than 0.
        seek.tap(-10_000, positionMs = 4_000, durationMs = 3_120_000)
        later(SeekBurst.WINDOW_MS)
        assertEquals(listOf("by -20000", "by -4000"), sent)
    }

    @Test
    fun `a drag makes one SeekTo on release, nothing while it moves`() {
        seek.drag(2_400_000)
        seek.drag(2_490_000)
        assertTrue(seek.dragging)
        assertEquals(2_490_000L, seek.shown(1_930_000), "the thumb and the time show the target")
        assertEquals(emptyList(), sent)
        assertEquals(2_490_000L, seek.release())
        assertEquals(listOf("to 2490000"), sent)
        assertFalse(seek.dragging)
        assertNull(seek.release(), "one seek a drag")
        assertEquals(listOf("to 2490000"), sent)
    }

    @Test
    fun `a drag takes the place of a burst under way, and play or pause sends the burst's seek first`() {
        seek.tap(10_000, positionMs = 60_000, durationMs = 3_120_000)
        seek.drag(900_000)
        later(SeekBurst.WINDOW_MS)
        seek.release()
        assertEquals(listOf("to 900000"), sent)
        seek.tap(10_000, positionMs = 900_000, durationMs = 3_120_000)
        seek.flush()
        assertEquals(listOf("to 900000", "by 10000"), sent)
        later(SeekBurst.WINDOW_MS)
        assertEquals(listOf("to 900000", "by 10000"), sent)
        // A drag cancelled (the gesture taken elsewhere) seeks nowhere.
        seek.drag(900_000)
        seek.cancelDrag()
        assertNull(seek.release())
    }
}

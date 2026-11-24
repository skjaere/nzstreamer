package io.skjaere.usenet.app.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which element opened what covers the screen, for the remote's focus on the way back (TV-3 D8). */
class ReturnFocusTest {
    /** A frame clock that answers at once: [ReturnFocus.restore] waits a frame for the element to be placed. */
    private val frames = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(0L)
    }

    private fun returnFocus(dpad: Boolean = true, turn: suspend () -> Unit = {}) = ReturnFocus(mutableStateOf(null), turn) { dpad }

    @Test
    fun `with a remote, the element leaving is the one the focus comes back to, the last one to leave`() {
        val focus = returnFocus()
        focus.leaving("row:1x2")
        assertEquals("row:1x2", focus.pending)
        focus.leaving("choose")
        assertEquals("choose", focus.pending)
    }

    /** On a touch screen nothing takes the focus on the way back, as before. */
    @Test
    fun `without a remote nothing is kept and the way back asks for nothing`() = runBlocking<Unit>(frames) {
        val focus = returnFocus(dpad = false)
        focus.leaving("row:1x2")
        assertNull(focus.pending)
        var brought: String? = null
        assertFalse(focus.restore { brought = it })
        assertNull(brought)
    }

    /** A lazy list's row is brought into view first (it takes the focus only once composed); once only. */
    @Test
    fun `the way back brings the element into view and is taken once`() = runBlocking<Unit>(frames) {
        val focus = returnFocus()
        focus.leaving("row:1x7")
        val brought = mutableListOf<String>()
        // Not attached to anything here, so it can't take the focus: false, but the key is spent all the same.
        assertFalse(focus.restore { brought += it })
        assertEquals(listOf("row:1x7"), brought)
        assertNull(focus.pending)
        assertFalse(focus.restore { brought += it })
        assertEquals(listOf("row:1x7"), brought)
    }

    /** Not while the user holds the TV's rail ([InitialFocus.awaitTurn]): the key waits with it. */
    @Test
    fun `the way back waits for the screen's turn`() = runBlocking<Unit>(frames) {
        val turn = CompletableDeferred<Unit>()
        val focus = returnFocus(turn = { turn.await() })
        focus.leaving("choose")
        val brought = mutableListOf<String>()
        val restoring = async { focus.restore { brought += it } }
        repeat(3) { yield() }
        assertTrue(brought.isEmpty(), "brought into view before its turn: $brought")
        assertEquals("choose", focus.pending)
        turn.complete(Unit)
        restoring.await()
        assertEquals(listOf("choose"), brought)
        assertNull(focus.pending)
    }
}

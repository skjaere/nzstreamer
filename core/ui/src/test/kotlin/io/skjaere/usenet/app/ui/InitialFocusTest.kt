package io.skjaere.usenet.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/**
 * A screen's first focus on a TV ([InitialFocus.awaitTurn]) waits while the user holds the rail, read through the kit's
 * [RailState] alone: the host's rail is one, and so is this test's.
 */
class InitialFocusTest {
    /** A rail as the host's keeps its state: in Compose's snapshots, which the wait observes. */
    private class Rail(hasFocus: Boolean, held: Boolean) : RailState {
        override var hasFocus by mutableStateOf(hasFocus)
        override var held by mutableStateOf(held)
        override var entering by mutableStateOf(false)
        override fun contentChanged() = Unit
    }

    /** A turn that comes, or a test that fails rather than hangs: no screen waits this long for nothing. */
    private suspend fun turn(focus: InitialFocus) = withTimeout(TURN_MS) { focus.awaitTurn() }

    /** On a phone (no rail), with the rail not focused, or focused by the window rather than the user: at once. */
    @Test
    fun `the screen takes its turn at once unless the user holds the rail`() = runBlocking<Unit> {
        turn(InitialFocus(null))
        turn(InitialFocus(Rail(hasFocus = false, held = false)))
        turn(InitialFocus(Rail(hasFocus = true, held = false)))
        turn(InitialFocus(Rail(hasFocus = false, held = true)))
    }

    /** Back to the rail leaves the focus there: the screen beside it waits until OK on the rail goes into it. */
    @Test
    fun `while the user holds the rail the screen waits, until OK enters`() = runBlocking<Unit> {
        val rail = Rail(hasFocus = true, held = true)
        val waiting = async { turn(InitialFocus(rail)) }
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted, "took its turn while the user holds the rail")
        Snapshot.withMutableSnapshot { rail.entering = true }
        waiting.await()
    }

    /** An arrow from the rail into the content is the user's move too: the screen's turn comes with it. */
    @Test
    fun `while the user holds the rail the screen waits, until the rail loses the focus`() = runBlocking<Unit> {
        val rail = Rail(hasFocus = true, held = true)
        val waiting = async { turn(InitialFocus(rail)) }
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted, "took its turn while the user holds the rail")
        Snapshot.withMutableSnapshot { rail.hasFocus = false }
        waiting.await()
    }

    private companion object {
        const val TURN_MS = 5_000L
    }
}

package io.skjaere.usenet.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.RailFocus
import kotlinx.coroutines.flow.first

/**
 * A screen's first focus for a remote, as it opens. On a TV not while the user holds the rail: Back
 * to the rail, or to Home from it, leaves the focus there, and the screen's first focus waits for the
 * user to go into the content (or for OK on the rail, which goes there). On a touch screen nothing
 * takes the focus, as before.
 */
@Stable
class InitialFocus internal constructor(private val rail: RailFocus?) {
    /** Suspends until the screen may take the focus. */
    suspend fun awaitTurn() {
        val rail = rail ?: return
        snapshotFlow { !(rail.hasFocus && rail.held) || rail.entering }.first { it }
    }

    /** Gives [target] the focus once the screen may take it. */
    suspend fun request(target: FocusRequester) {
        awaitTurn()
        target.tryFocus()
    }
}

@Composable
fun rememberInitialFocus(): InitialFocus {
    val rail = LocalRailFocus.current
    return remember(rail) { InitialFocus(rail) }
}

/**
 * On a TV, a list keeps which of its rows had the focus, so the focus comes back to that row from the
 * rail (Right, or Back to the rail and Right): the shell's content remembers only its direct child,
 * and a lazy list in between would hand the focus to its first row. Phones' lists stay as they were.
 */
fun Modifier.tvFocusMemory(): Modifier = composed {
    if (LocalFormFactor.current == FormFactor.Tv) Modifier.focusRestorer() else Modifier
}

/** Focused, or false: not attached yet (or any more), or nothing there takes the focus. */
fun FocusRequester.tryFocus(): Boolean = try {
    requestFocus(FocusDirection.Enter)
} catch (e: IllegalStateException) {
    // Not attached (yet, or any more): nothing to focus.
    false
}

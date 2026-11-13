package io.skjaere.usenet.app.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.skjaere.usenet.app.collection.rememberDpad

/**
 * A remote's focus back on the element that opened what covered the screen (a page, the release chooser), as the screen
 * shows again. Nothing does that by itself: the element was gone meanwhile (a page over it, or the chooser in its place),
 * restoring across a page finds nothing on a TV (#244), and the TV shell would settle the focus on the screen's first
 * element. Each element that opens something takes its [requester] by a key and says [leaving] with that key before it
 * opens it; the screen calls [restore] as it shows again. The key is saved with the screen's state, so it lasts while a
 * page is over the screen. Only with a remote: after a touch nothing takes the focus, as before. Search keeps its own
 * (its returnTo), which this follows.
 */
@Stable
class ReturnFocus internal constructor(private val saved: MutableState<String?>, private val turn: suspend () -> Unit, private val dpad: () -> Boolean) {
    private val requesters = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The element [key] is opening something: the focus comes back to it once that closes. */
    fun leaving(key: String) {
        if (dpad()) saved.value = key
    }

    /** The key of the element the focus is to come back to; null when there is none. */
    val pending: String? get() = saved.value

    /**
     * The focus on the element that opened what closed, once the screen may take it ([InitialFocus.awaitTurn]): first
     * [bringIntoView] (a lazy list's row takes it only once composed), then a frame for it to be placed. Taken once,
     * whether the element took the focus or not. False when there was nothing to go back to, or it couldn't take it:
     * the screen's own first focus goes instead.
     */
    suspend fun restore(bringIntoView: suspend (key: String) -> Unit = {}): Boolean {
        val key = saved.value ?: return false
        turn()
        saved.value = null
        bringIntoView(key)
        withFrameNanos {}
        return requesters[key]?.tryFocus() == true
    }
}

/**
 * Where the remote's focus was last in each pane of a TV page, by the [ReturnFocus] keys of the panes' elements: Left or
 * Right into a pane goes back to that element, else wherever the move would go. Each element says it is one
 * ([paneElement]), each pane is one ([focusPane]). Not Compose's focus restorer: it keeps only the pane's direct child (a list's group, entered again at its
 * first row), and it turns a request for one of the pane's elements into one for the element it kept, so the focus back
 * on what opened the chooser, or a page's first focus, would land elsewhere.
 */
@Stable
class PaneMemory(internal val focus: ReturnFocus) {
    /** The key of the element last focused in each pane, by the pane's name. */
    internal val last = HashMap<String, String>()
}

/** The pane [name] of [memory]: Left and Right into it go back to its element last focused. */
fun Modifier.focusPane(memory: PaneMemory, name: String): Modifier = focusProperties {
    onEnter = {
        if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) {
            memory.last[name]?.let { memory.focus.requester(it).tryFocus() }
        }
    }
}.focusGroup()

/** The element [key] in the pane [pane] of [memory]: its requester ([ReturnFocus.requester]), and the pane's memory of it. */
fun Modifier.paneElement(memory: PaneMemory, pane: String, key: String): Modifier =
    focusRequester(memory.focus.requester(key)).onFocusChanged { if (it.isFocused) memory.last[pane] = key }

@Composable
fun rememberReturnFocus(): ReturnFocus {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    val initial = rememberInitialFocus()
    val dpad by rememberUpdatedState(rememberDpad())
    return remember(saved, initial) { ReturnFocus(saved, initial::awaitTurn) { dpad } }
}

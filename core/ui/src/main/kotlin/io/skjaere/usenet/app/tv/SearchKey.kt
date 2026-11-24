package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** What the remote's search key does on a TV: open a new search, through the shell. Null on a phone, whose system keeps the key. */
val LocalSearchKey = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The remote's search key in a dialog or a sheet on a TV: [onClose] closes it (the play sheet is
 * only hidden) and Search opens, as from the screens. A dialog is a window of its own, whose keys
 * the activity never sees, and the TV's system would take this one and open its assistant over the
 * app. Nothing on a phone.
 */
fun Modifier.searchKeyOpensSearch(onClose: () -> Unit): Modifier = composed {
    val search = LocalSearchKey.current ?: return@composed Modifier
    val close by rememberUpdatedState(onClose)
    onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode != KeyEvent.KEYCODE_SEARCH) return@onPreviewKeyEvent false
        // Down and up both taken, so the window never sees half a press; the up opens Search.
        if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) {
            close()
            search()
        }
        true
    }
}

package io.skjaere.usenet.app.play

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor

/**
 * Keeps an OK meant for what opened a sheet away from the sheet: the second press of a double OK
 * on an Up next row, or the repeats of an OK held there, would land on the sheet's focused button
 * (and could cancel the play just started). OK keys are ignored for [windowMs] after the sheet
 * opened ([openedAt], uptime) and when they repeat, and so is the up of a press ignored so, or of
 * one whose down it never saw; other keys always go through. A press it let through comes up
 * through it, held or not: the button clicks on that up.
 */
class OkGuard(private val openedAt: Long, private val windowMs: Long = WINDOW_MS) {
    // Whether the press under way was ignored going down (or repeating, its down having gone elsewhere): its up is then
    // ignored too, however late, so the button focused never sees an up without its down.
    private var ignoringPress = false

    // Whether the press under way went through going down: held, its repeats are ignored, but not its up, which the
    // button focused clicks on.
    private var letThrough = false

    /** Whether the key event ([eventTime] in uptime) is ignored. */
    fun blocks(keyCode: Int, down: Boolean, repeatCount: Int, eventTime: Long): Boolean {
        if (keyCode !in RemoteKeys.OK) return false
        val early = eventTime - openedAt < windowMs
        return when {
            down && repeatCount > 0 -> true.also { if (!letThrough) ignoringPress = true }
            down -> early.also { ignoringPress = it; letThrough = !it }
            else -> (early || ignoringPress).also { ignoringPress = false; letThrough = false }
        }
    }

    companion object {
        const val WINDOW_MS = 400L
    }
}

/** An [OkGuard] on a sheet's content, from the moment it is first shown, for [windowMs]. */
fun Modifier.okGuard(windowMs: Long = OkGuard.WINDOW_MS): Modifier = composed {
    val guard = remember { OkGuard(SystemClock.uptimeMillis(), windowMs) }
    okGuard(guard)
}

/** [guard] on what this modifies: the OK keys it blocks never reach the focused element inside. */
fun Modifier.okGuard(guard: OkGuard): Modifier = onPreviewKeyEvent { event ->
    val key = event.nativeKeyEvent
    guard.blocks(key.keyCode, key.action == KeyEvent.ACTION_DOWN, key.repeatCount, key.eventTime)
}

/**
 * A title page's [OkGuard] from when the page opened (TV-3 D2), for [pageOkGuard]. Remembered by the page
 * itself, above what it shows in turn (its release chooser), so the window is the page's.
 */
@Composable
fun rememberPageOkGuard(): OkGuard = remember { OkGuard(SystemClock.uptimeMillis()) }

/**
 * On a TV, a title page's [guard] on its content: the OK that opened the page from a card or a title's Open, pressed
 * twice or held, never presses the page's first button, its Play or Resume. A phone's pages take every press, as before.
 */
fun Modifier.pageOkGuard(guard: OkGuard): Modifier = composed {
    if (LocalFormFactor.current == FormFactor.Tv) okGuard(guard) else this
}

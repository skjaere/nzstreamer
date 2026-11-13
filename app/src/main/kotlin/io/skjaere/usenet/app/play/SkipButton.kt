package io.skjaere.usenet.app.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.metadata.Segment
import io.skjaere.usenet.metadata.SegmentKind

/**
 * The player's "Skip intro" / "Skip recap" button, kept apart from the player so it can be tested:
 * whether it shows and where it goes. PlayerActivity feeds it the position on every tick and
 * shows what it says; one instance per player setup, once the file's segments are known.
 *
 * The button is there while playback is in an intro or recap, from its start until [UNTIL_END_MS]
 * before its end: for [showForMs] of playback after playback enters it (playing or seeking into
 * it), so loading, stalls and pauses don't use that up, and again whenever the controls are up
 * while still inside. Back hides it until playback next enters
 * a segment. It gives way to the next-episode card, so the two never show together. It never
 * skips by itself; only [press] seeks.
 *
 * It takes a TV remote's focus whenever it shows with the controls hidden ([Shown.takeFocus]): as
 * it appears, and as they hide while it still shows. With them up it leaves the focus where media3
 * put it as they came, on play/pause, so an OK meant to pause doesn't skip; the D-pad reaches it
 * below play/pause.
 */
class SkipButton(
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
    /** How long it shows after playback enters a segment: the app's own, shorter only in tests. */
    private val showForMs: Long = SHOW_FOR_MS,
    private val seek: (Long) -> Unit,
) {
    /**
     * The button as it shows: what it skips, and where pressing it goes. [takeFocus] is whether it
     * should have a TV remote's focus: whether it shows with the controls hidden.
     */
    data class Shown(val kind: SegmentKind, val targetMs: Long, val takeFocus: Boolean) {
        val label: String get() = if (kind == SegmentKind.Recap) "Skip recap" else "Skip intro"
    }

    /** The segment playback is in, as of the last update. */
    private var current: Segment? = null
    /** Playback in [current] since it was entered: the position's progress, no faster than the clock's. */
    private var playedMs = 0L
    private var lastPositionMs = 0L
    private var lastTick = 0L
    /** Back or a press: nothing shows again until playback enters a segment anew. */
    private var dismissed = false
    private var shown: Shown? = null

    fun update(positionMs: Long, segments: List<Segment>, controlsShown: Boolean, cardShown: Boolean): Shown? {
        val t = now()
        val inside = segments.firstOrNull { s ->
            (s.kind == SegmentKind.Intro || s.kind == SegmentKind.Recap) && positionMs >= s.startMs && positionMs < s.endMs - UNTIL_END_MS
        }
        if (inside != current) {
            current = inside
            playedMs = 0
            dismissed = false
        } else {
            // A stall, a pause or a seek back adds nothing; a seek ahead no more than the time since the last update.
            playedMs += minOf(positionMs - lastPositionMs, t - lastTick).coerceAtLeast(0)
        }
        lastPositionMs = positionMs
        lastTick = t
        shown = inside?.takeIf { !dismissed && !cardShown && (controlsShown || playedMs < showForMs) }
            ?.let { Shown(it.kind, it.endMs, takeFocus = !controlsShown) }
        return shown
    }

    /** Seeks to the end of the segment shown; false (and no seek) when nothing shows. */
    fun press(): Boolean {
        val target = shown?.targetMs ?: return false
        dismiss()
        seek(target)
        return true
    }

    /** Back: hides the button until playback next enters a segment. */
    fun dismiss() {
        dismissed = true
        shown = null
    }

    companion object {
        const val SHOW_FOR_MS = 10_000L
        const val UNTIL_END_MS = 1_000L
    }
}

/**
 * "Skip intro" or "Skip recap": an outlined pill like the next-episode card's Stop, on the card's
 * surface so it reads over the picture. It takes a TV remote's focus each time [takeFocus] turns
 * true, and is a polite live region, read out once as it appears (its label doesn't change while
 * it shows).
 */
@Composable
fun SkipSegmentButton(label: String, takeFocus: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(takeFocus) { if (takeFocus) focus.requestFocus() }
    CardButton(
        label, onClick, modifier.semantics { liveRegion = LiveRegionMode.Polite }.focusRequester(focus),
        filled = false, container = colors.surface.copy(alpha = 0.94f), horizontalPadding = 20.dp,
    )
}

package io.skjaere.usenet.app.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.skjaere.usenet.app.collection.model.LetterIndex
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.Sora
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import kotlinx.coroutines.CancellationException

/**
 * A–Z's letter scrubber on touch screens: a 48 dp strip at the right edge; a tap or a drag goes to a
 * letter ([onLetter], with a tick per letter), [onDrag] says which while the finger is down. Letters
 * without titles are faint. TalkBack finds one button instead, which opens the letter grid.
 */
@Composable
internal fun LetterRail(letters: LetterIndex, onLetter: (Char) -> Unit, onDrag: (Char?) -> Unit, onOpenGrid: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The gesture loop outlives a recomposition (it starts again only with new letters): it calls the
    // latest, whose scroll counts the items before the rows as they are now (a note come or gone).
    val onLetterNow by rememberUpdatedState(onLetter)
    val onDragNow by rememberUpdatedState(onDrag)
    BoxWithConstraints(
        modifier.width(48.dp).fillMaxHeight().clearAndSetSemantics {
            role = Role.Button
            contentDescription = "Jump to letter"
            onClick { onOpenGrid(); true }
        },
    ) {
        // The letters grow with the font size up to 1.3 times; past what fits, every other one shows.
        val scale = minOf(density.fontScale, 1.3f) / density.fontScale
        val size = (11 * scale).sp
        val each = with(density) { (size * 1.5f).toDp() }
        val every = if (each * letters.letters.size > maxHeight) 2 else 1
        Column(
            Modifier.fillMaxSize().pointerInput(letters) {
                fun at(y: Float) = letters.letters[((y / this.size.height) * letters.letters.size).toInt().coerceIn(0, letters.letters.lastIndex)]
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown()
                        var last = at(down.position.y)
                        onDragNow(last)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onLetterNow(last)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val letter = at(change.position.y)
                            if (letter != last) {
                                last = letter
                                onDragNow(letter)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLetterNow(letter)
                            }
                        }
                    } finally {
                        // Also when new letters stop the loop under the finger: the bubble goes.
                        onDragNow(null)
                    }
                }
            },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.letters.filterIndexed { i, _ -> i % every == 0 }.forEach { c ->
                Text(c.toString(), style = AppType.small.copy(fontSize = size, lineHeight = size * 1.2f), color = colors.muted, modifier = Modifier.alpha(if (c in letters.first) 1f else 0.35f))
            }
        }
    }
}

/** The letter under the finger while it scrubs. */
@Composable
internal fun LetterBubble(letter: Char, modifier: Modifier = Modifier) {
    Box(modifier.size(56.dp).background(colors.accentTint, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        Text(letter.toString(), style = AppType.screenTitle.copy(fontFamily = Sora, fontSize = 24.sp, fontWeight = FontWeight.Bold), color = colors.accent)
    }
}

/**
 * Jump to letter, for a remote (and TalkBack): '#' and A to Z in seven columns of 48 dp; letters
 * without titles can't be picked and focus skips them; focus starts on [current]'s letter. Each reads
 * "Q, 12 shows". It scrolls when the font is large.
 */
@Composable
internal fun LetterGrid(letters: LetterIndex, current: Char?, noun: String, onPick: (Char) -> Unit, onDismiss: () -> Unit) {
    val start = remember(letters, current) { current?.takeIf { it in letters.first } ?: letters.letters.firstOrNull { it in letters.first } }
    val focus = remember { FocusRequester() }
    var startFocused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.searchKeyOpensSearch(onDismiss).background(colors.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Jump to letter", style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(GridCells.Fixed(7), Modifier.heightIn(max = 420.dp)) {
                items(letters.letters, key = { it }) { c ->
                    val count = letters.counts[c] ?: 0
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.size(48.dp)
                            .then(if (c == start) Modifier.focusRequester(focus).onFocusChanged { startFocused = it.isFocused } else Modifier)
                            .focusOutline(shape)
                            .semantics { contentDescription = "$c, $count ${noun(noun, count)}" }
                            .clickable(enabled = count > 0, role = Role.Button) { onPick(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), style = AppType.bodyStrong, color = if (count > 0) colors.text else colors.muted, modifier = Modifier.alpha(if (count > 0) 1f else 0.35f))
                    }
                }
            }
            // Once the grid has placed its buttons. On a TV asked again a frame at a time until it has it: a dialog's
            // window may not take the focus in the frame it opens (#243), and a remote has nothing else to put it there.
            val tv = LocalFormFactor.current == FormFactor.Tv
            LaunchedEffect(start) {
                if (start == null) return@LaunchedEffect
                repeat(if (tv) TV_FOCUS_FRAMES else 1) {
                    withFrameNanos {}
                    if (startFocused) return@LaunchedEffect
                    runCatchingFocus { focus.requestFocus() }
                }
            }
        }
    }
}

/** A TV's letter grid's first focus, asked a frame at a time at most this many times. */
private const val TV_FOCUS_FRAMES = 10

/** A focus request that may come before its node is placed: tried, not fatal. */
internal inline fun runCatchingFocus(request: () -> Unit) {
    try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        // Not attached yet (the node is still being placed): nothing to focus.
    }
}

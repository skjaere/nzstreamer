package io.skjaere.usenet.app.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus

/**
 * A title page TMDB couldn't load ([message]), with a way on: Try again ([onRetry]), which a remote starts on (TV-3
 * D11). On phones too.
 */
@Composable
internal fun PageLoadFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val retry = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    val dpad = rememberDpad()
    LaunchedEffect(Unit) { if (dpad) initial.request(retry) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = AppType.secondary, color = colors.textSecondary)
        SecondaryButton(TRY_AGAIN, onRetry, Modifier.focusRequester(retry))
    }
}

/**
 * On a TV, "Finding releases…" can be left with a button as well as Back: [onCancel] closes the chooser (the focus goes
 * back to what opened it). It takes the focus as it shows, the element that opened the chooser being gone.
 */
@Composable
internal fun ChooserCancel(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The menu that opened the chooser may still be closing: asked again, a frame at a time, until it has the focus.
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (cancel.tryFocus()) return@LaunchedEffect
        }
    }
    SecondaryButton(CANCEL, onCancel, modifier.padding(horizontal = 16.dp).focusRequester(cancel))
}

/**
 * A TV page's left pane (TV-3 D4): [top] and [bottom] whole, the bottom at the pane's foot, and [between] them
 * the room the page leaves, which [between] fills as it can (none at all when the rest takes the page). Taller than the
 * page (a long title at a larger text size, the play bar under the page), the pane scrolls, only as far as the focused
 * element needs: its buttons are never squeezed to make room.
 */
@Composable
internal fun TvPagePane(
    modifier: Modifier,
    gap: Dp,
    top: @Composable () -> Unit,
    between: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val page = constraints.maxHeight
        EdgeScrolling {
            Layout(listOf(top, between, bottom), Modifier.verticalScroll(rememberScrollState())) { (tops, betweens, bottoms), c ->
                val gapPx = gap.roundToPx()
                val loose = Constraints(maxWidth = c.maxWidth)
                val t = tops.map { it.measure(loose) }
                val b = bottoms.map { it.measure(loose) }
                val topHeight = t.sumOf { it.height }
                val bottomHeight = b.sumOf { it.height }
                val whole = topHeight + if (b.isEmpty()) 0 else gapPx + bottomHeight
                val room = (page - whole - gapPx).coerceAtLeast(0)
                val m = betweens.map { it.measure(Constraints(maxWidth = c.maxWidth, maxHeight = room)) }
                val height = if (room > 0) page else whole
                layout(c.maxWidth, height) {
                    var y = 0
                    t.forEach { it.place(0, y); y += it.height }
                    m.forEach { it.place(0, y + gapPx) }
                    y = height - bottomHeight
                    b.forEach { it.place(0, y); y += it.height }
                }
            }
        }
    }
}

/**
 * [text] in what room it is given, in whole lines (up to [maxLines], the last ending in an ellipsis when there is more);
 * nothing at all when there isn't room for one: a line cut through its words would show.
 */
@Composable
internal fun WholeLines(text: String, style: TextStyle, color: Color, maxLines: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        if (maxHeight >= line) Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * What a title page says TMDB couldn't load, before why (TV-3 D11): the show, the movie, a season, a movie's
 * details. The same words on phones and TVs; the tests match them.
 */
const val SHOW_LOAD_FAILED = "Couldn't load this show from TMDB: "
const val MOVIE_LOAD_FAILED = "Couldn't load this movie from TMDB: "
const val SEASON_LOAD_FAILED = "Couldn't load this season from TMDB: "
const val DETAILS_LOAD_FAILED = "Couldn't load details from TMDB: "

const val TRY_AGAIN = "Try again"
const val CANCEL = "Cancel"

private const val FOCUS_FRAMES = 10

package io.skjaere.usenet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The rows of a list the user puts in order, Usenet providers' and Debrid services' (S9-2, S9-12, S9-13, L229): one look
// and one D-pad behaviour on phones and TVs.

/** A row's place in a list the user orders: [position] (1 is first, tried first) of [count]. */
data class ListPlace(val position: Int, val count: Int) {
    val canMoveUp: Boolean get() = position > 1
    val canMoveDown: Boolean get() = position < count
}

/** Row [id] was moved [by] places (negative: up) from [from]. */
data class ListMove(val id: String, val by: Int, val from: Int)

/** An arrow's words for TalkBack and the tests: "Move TorBox up". */
fun moveLabel(name: String, up: Boolean): String = "Move $name ${if (up) "up" else "down"}"

/**
 * One row of a list the user orders: its place (at least 26 dp, the first in the accent), [name] (muted when [off]) and
 * the [lines] under it open the row ([onOpen]); then [end] (a provider's switch), and Move up and Move down, each
 * outlined in the focus as the row is (S9-12). [moved]: the row's last move, once it is in its new place, keeps the focus
 * on the arrow pressed, so OK moves it on (S9-2); on the other arrow when there is no moving further that way. TalkBack
 * reads the row's words and "Position N of M", or [spoken] where given (which then says the place itself), and the row
 * is a polite live region once moved, so it is read out in its new place. [body] is the row's own focus, and
 * [bodyModifier] goes on it too (a list's first focus).
 */
@Composable
fun OrderedRow(
    place: ListPlace,
    name: String,
    moved: ListMove?,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    body: FocusRequester = remember { FocusRequester() },
    bodyModifier: Modifier = Modifier,
    off: Boolean = false,
    spoken: String? = null,
    end: @Composable RowScope.() -> Unit = {},
    lines: @Composable ColumnScope.() -> Unit,
) {
    val up = remember { FocusRequester() }
    val down = remember { FocusRequester() }
    // Once the move is saved the row is in its new place, and the focus goes there with it.
    LaunchedEffect(moved, place.position) {
        if (moved == null || place.position == moved.from) return@LaunchedEffect
        val pressed = if (moved.by < 0) up.takeIf { place.canMoveUp } else down.takeIf { place.canMoveDown }
        val other = if (moved.by < 0) down.takeIf { place.canMoveDown } else up.takeIf { place.canMoveUp }
        runCatching { (pressed ?: other ?: body).requestFocus() }
    }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).focusRequester(body).then(bodyModifier).focusOutline()
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    if (spoken != null) contentDescription = spoken else stateDescription = "Position ${place.position} of ${place.count}"
                    if (moved != null) liveRegion = LiveRegionMode.Polite
                }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlaceBadge(place.position)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = AppType.bodyStrong, color = if (off) colors.muted else colors.text)
                lines()
            }
        }
        end()
        Column {
            MoveButton(Icons.Filled.KeyboardArrowUp, moveLabel(name, up = true), place.canMoveUp, up) { onMove(-1) }
            MoveButton(Icons.Filled.KeyboardArrowDown, moveLabel(name, up = false), place.canMoveDown, down) { onMove(1) }
        }
    }
}

/** A row's place: a circle at least 26 dp, growing with a large font size so the number isn't cut off; the first in the accent. */
@Composable
private fun PlaceBadge(position: Int) {
    val first = position == 1
    Box(
        Modifier.sizeIn(minWidth = 26.dp, minHeight = 26.dp).clip(CircleShape).background(if (first) colors.accentTint else colors.chip).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text("$position", style = AppType.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = if (first) colors.accent else colors.textSecondary)
    }
}

/**
 * A move arrow, a 48 dp target of its own, none of it shared with the arrow above or below (S9-13);
 * where there is no moving that way, an empty place keeps the arrows lined up.
 */
@Composable
private fun MoveButton(icon: ImageVector, label: String, shown: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    if (!shown) {
        Spacer(Modifier.size(48.dp))
        return
    }
    IconButton(onClick = onClick, modifier = Modifier.focusRequester(focus).focusOutline(CircleShape)) {
        Icon(icon, contentDescription = label, tint = colors.muted, modifier = Modifier.size(20.dp))
    }
}

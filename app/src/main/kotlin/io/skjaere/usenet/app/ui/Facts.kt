package io.skjaere.usenet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.launch

/** From this font scale a fact's label goes above its value: beside it, at 2x, a label's words would break. */
private const val STACKED_FROM = 1.5f

/** How much of what shows of a block a remote's Down or Up scrolls on through it: the rest stays on screen, to read on from. */
private const val PAGE = 0.75f

/**
 * A fact as File details and This device show it: its [label] in a column (118 dp on a TV, 108 dp on phones) beside its
 * [value], a [note] under that, and [warnings] in the warning colour; from 1.5x the font size the label above. One node
 * for TalkBack. With [hdr] the value is those formats' chips ([HdrChip]); [failed], a value in the strong warning colour.
 */
@Composable
fun FactRow(label: String, value: String, note: String? = null, warnings: List<String> = emptyList(), hdr: List<Hdr> = emptyList(), failed: Boolean = false) {
    val labelWidth = if (LocalFormFactor.current == FormFactor.Tv) 118.dp else 108.dp
    val stacked = LocalDensity.current.fontScale >= STACKED_FROM
    val title = @Composable { modifier: Modifier -> Text(label, style = AppType.secondary, color = colors.muted, modifier = modifier) }
    val facts = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (hdr.isEmpty()) Text(value, style = AppType.body, color = if (failed) colors.warnStrong else colors.text) else ChipRow { hdr.forEach { HdrChip(it) } }
            note?.let { Text(it, style = AppType.small, color = colors.muted) }
            warnings.forEach { Text(it, style = AppType.small, color = colors.warn) }
        }
    }
    val one = Modifier.fillMaxWidth().padding(vertical = 5.dp).semantics(mergeDescendants = true) {}
    if (stacked) {
        Column(one) {
            title(Modifier)
            facts(Modifier)
        }
    } else {
        Row(one, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            title(Modifier.width(labelWidth).alignByBaseline())
            facts(Modifier.weight(1f).alignByBaseline())
        }
    }
}

/** An HDR format as a pill: Dolby Vision in the accent, the others in the blue language chips have. */
@Composable
fun HdrChip(hdr: Hdr) {
    val (background, words) = if (hdr == Hdr.DolbyVision) colors.accentTint to colors.accent else colors.langBg to colors.lang
    Box(Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(hdr.label, style = AppType.tag, color = words)
    }
}

/**
 * A block a remote's focus takes whole (a section of facts) that reads a page at a time while it is taller than [room],
 * the height in px its scrolling container shows it in: Down and Up scroll through it first, then go on to the next
 * stop as usual, so no row in its middle is passed over. Come to from below (an Up), it starts at its last page, next
 * to where the focus came from, and Up pages up from there; from above, at its top. Goes before the block's focusable,
 * whose keys it sees first, and makes a focus group around it, to be told which way the focus came in.
 */
fun Modifier.pagesThrough(room: Int): Modifier = composed {
    val pages = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val entry = remember { Entry() }
    // The part of the block the scrolling leaves showing, in its own coordinates; a page is most of it. One that fits
    // goes on at once, also while it is still being brought into view.
    fun page(down: Boolean): Boolean {
        val c = at?.takeIf { it.isAttached && it.size.height > room } ?: return false
        val shown = c.boundsInRoot()
        val top = shown.top - c.positionInRoot().y
        val bottom = shown.bottom - c.positionInRoot().y
        val step = (bottom - top) * PAGE
        val next = when {
            down && bottom < c.size.height - 1 -> Rect(0f, bottom, c.size.width.toFloat(), minOf(c.size.height.toFloat(), bottom + step))
            !down && top > 1 -> Rect(0f, maxOf(0f, top - step), c.size.width.toFloat(), top)
            else -> return false
        }
        scope.launch { pages.bringIntoView(next) }
        return true
    }
    bringIntoViewRequester(pages)
        .onPlaced { at = it }
        .onKeyEvent { e ->
            e.type == KeyEventType.KeyDown && when (e.key) {
                Key.DirectionDown -> page(down = true)
                Key.DirectionUp -> page(down = false)
                else -> false
            }
        }
        .onFocusChanged { if (!it.hasFocus) entry.fromBelow = false }
        .focusProperties { onEnter = { entry.fromBelow = requestedFocusDirection == FocusDirection.Up } }
        .focusGroup()
        // After the pages' requester, whose requests pass it by.
        .then(StartAtLastPageElement(room, entry))
}

/** Which way the focus came into a block ([pagesThrough]): set as it comes in, taken as the block is brought into view. */
private class Entry {
    var fromBelow = false
}

private class StartAtLastPageElement(private val room: Int, private val entry: Entry) : ModifierNodeElement<StartAtLastPageNode>() {
    override fun create() = StartAtLastPageNode(room, entry)

    override fun update(node: StartAtLastPageNode) {
        node.room = room
        node.entry = entry
    }

    override fun equals(other: Any?) = other is StartAtLastPageElement && other.room == room && other.entry === entry

    override fun hashCode() = 31 * room + entry.hashCode()
}

/**
 * Between a block's focusable and the container it scrolls in: as the focus lands on it from below, the focusable's
 * bringing it into view shows its last [room] px rather than its top. On a TV the pivot puts the top of a block taller
 * than the screen at the pivot line, so going Up its lower rows, the ones next to where the focus came from, would never
 * show. Every other request passes on as it is.
 */
private class StartAtLastPageNode(var room: Int, var entry: Entry) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // Decided as the request comes: the provider is asked again as the scrolling goes.
        val last = entry.fromBelow
        entry.fromBelow = false
        bringIntoView {
            if (!isAttached || !childCoordinates.isAttached) return@bringIntoView null
            val own = requireLayoutCoordinates()
            val child = boundsProvider() ?: childCoordinates.size.toSize().toRect()
            val rect = child.translate(own.localBoundingBoxOf(childCoordinates, clipBounds = false).topLeft)
            if (last && rect.height > room) Rect(rect.left, rect.bottom - room, rect.right, rect.bottom) else rect
        }
    }
}

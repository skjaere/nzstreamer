package io.skjaere.usenet.app.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.TvSafeArea
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.reflect.KClass

/**
 * The TV rail's focus, for the shell and the screens: whether the rail has it ([hasFocus]) and
 * whether the user put it there or keeps it there ([held]: an arrow from the content, Back, or any
 * key pressed on the rail; not the window's first focus, given to the rail while the screen beside
 * it is still loading, which Android gives as a move down into the window), the current tab's place
 * in it ([selected]), and whether OK on an item is taking the focus into the content ([entering]).
 * A screen's first focus waits while the user holds the rail, unless OK is entering
 * ([io.skjaere.usenet.app.ui.InitialFocus]). Only the user's moves open the rail: when what the
 * content shows changes under the focus, the shell puts the focus back into the content
 * ([contentChanged], [settling]).
 */
@Stable
class RailFocus(private val tabs: List<Tab>) {
    var hasFocus by mutableStateOf(false)
        internal set
    var held by mutableStateOf(false)
        internal set
    var selected by mutableIntStateOf(0)
        internal set
    var entering by mutableStateOf(false)
        internal set
    internal val items = List(tabs.size) { FocusRequester() }

    /** Whether the content beside the rail has the focus, from which the user's arrows come into the rail. */
    internal var contentHasFocus = false

    /** Whether it has had it: before that (a cold start) the window's first focus stays on the rail (#234). */
    internal var contentHadFocus = false

    /**
     * Whether the shell, not the user, is moving the focus into the content (the screen changed under it): a screen's
     * way in may then skip what only the user should land on (Search's field, whose keyboard would replace its results).
     */
    var settling = false
        private set

    private val changes = Channel<Unit>(Channel.CONFLATED)

    /**
     * Whether the shell is to look at where the focus is ([contentChanged]): until then the window's default focus,
     * which Android gives the rail as the focused element goes, isn't taken.
     */
    internal var settlePending = false

    /** What the content shows has changed, as the shell can't see (results arriving): the shell checks where the focus is. */
    fun contentChanged() {
        settlePending = true
        changes.trySend(Unit)
    }

    /** The content's changes: a page opened or closed, the focus lost from it, or [contentChanged]. */
    internal val changed: Flow<Unit> = changes.receiveAsFlow()

    /** The content beside the rail: the shell's focus group around the screens. */
    internal val content = FocusRequester()

    /** Moves the focus to the current tab's item, keeping where it was in the content for [focusContent]. */
    fun focusSelected(): Boolean = focusItem(selected)

    /**
     * The rail as a content element's Left (its focusProperties' left), for one alone on its line away from the column's
     * start (TV Home's centred empty card): a move left from it would go to what is below it, not to the rail. Entered
     * so, the rail takes the focus on the current tab's item, as from the column's first elements.
     */
    val wayIn: FocusRequester get() = items[selected]

    /** Moves the focus to the item of the tab whose route is [type]. */
    fun focus(type: KClass<*>): Boolean = focusItem(tabs.indexOfFirst { it.type == type })

    /**
     * An arrow brings the focus into the rail: to the current tab's item. The user's when it comes from
     * the content; from nowhere it is the window's first focus (#234), which a screen's first focus takes over.
     */
    internal fun arrowIn(): Boolean = focusItem(selected, held = contentHasFocus)

    private fun focusItem(i: Int, held: Boolean = true): Boolean {
        if (i < 0) return false
        content.saveFocusedChild()
        this.held = held
        return items[i].tryFocus()
    }

    /** Moves the focus back into the content: to the element it was left from, else to its first. */
    fun focusContent(): Boolean = content.restoreFocusedChild() || content.tryFocus()

    /** [focusContent] on the shell's account, not the user's ([settling]). */
    internal fun settleContent(): Boolean {
        settling = true
        try {
            return focusContent()
        } finally {
            settling = false
        }
    }
}

/** The TV shell's rail; null on a phone. */
val LocalRailFocus = staticCompositionLocalOf<RailFocus?> { null }

/**
 * The TV's tabs down the left: icons while the content has the focus, widening over the content
 * with their names while the rail has it. The focus comes in on the current tab, whatever item it
 * left from; Up and Down stop at the ends, Right goes back to where the focus was in the content.
 * OK on an item opens its tab ([onOpen]); the focus alone never does.
 */
@Composable
internal fun TvRail(tabs: List<Tab>, rail: RailFocus, safe: WindowInsets, onOpen: (Tab) -> Unit) {
    val expanded = rail.hasFocus
    // Wider with a large font size, so the names aren't cut off; the icons alone stay as they are.
    val wide = TvSafeArea.railExpanded * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val width by animateDpAsState(if (expanded) wide else TvSafeArea.railEnd, tween(EXPAND_MS), label = "rail")
    Column(
        Modifier.width(width).fillMaxHeight()
            .background(if (expanded) colors.surface else Color.Transparent)
            .windowInsetsPadding(safe.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(end = if (expanded) 16.dp else 0.dp)
            .onFocusChanged { focus ->
                rail.hasFocus = focus.hasFocus
                if (!focus.hasFocus) {
                    rail.held = false
                    rail.entering = false
                }
            }
            // A key pressed on the rail is the user's, also after the window's first focus put it
            // there: Up and Down along it never come in through onEnter.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) rail.held = true
                false
            }
            .focusProperties {
                onEnter = {
                    when {
                        !requestedFocusDirection.isArrow() -> Unit
                        // From nowhere as the shell is about to look at the content: the window's default focus,
                        // given as the focused element went (a page opened or closed, the screen changed under it),
                        // not the user's move. Refused; the shell puts the focus into the content once it has settled.
                        !rail.contentHasFocus && rail.contentHadFocus && rail.settlePending -> cancelFocusChange()
                        else -> rail.arrowIn()
                    }
                }
                onExit = {
                    when (requestedFocusDirection) {
                        // Back to where the focus was in the content, in place of the move. Cancelled only when nothing
                        // there takes it: a cancelled move goes on to the next element Compose finds right of the rail,
                        // which would take the focus from the one just restored.
                        FocusDirection.Right -> if (!rail.focusContent()) cancelFocusChange()
                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Left -> cancelFocusChange()
                        else -> Unit
                    }
                }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { i, tab ->
            if (tab.pinned) return@forEachIndexed
            RailItem(tab, i == rail.selected, expanded, rail.items[i]) { onOpen(tab) }
        }
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { i, tab ->
            if (tab.pinned) RailItem(tab, i == rail.selected, expanded, rail.items[i]) { onOpen(tab) }
        }
    }
}

/**
 * A tab's item: the current tab's tinted in the accent; focused, any item turns over, the current tab's with a 2 dp
 * accent line under it, as its tint is gone then.
 */
@Composable
private fun RailItem(tab: Tab, selected: Boolean, expanded: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val tint = when {
        turned -> colors.background
        selected -> colors.accent
        else -> colors.text
    }
    val fill = when {
        turned -> colors.text
        selected -> colors.accentTint
        else -> Color.Transparent
    }
    val accent = colors.accent
    OwnedRipple {
        Row(
            Modifier.fillMaxWidth().height(ITEM_HEIGHT)
                .focusRequester(requester)
                // Not grown when focused, as rows are: the items start at the safe area's edge.
                .clip(RoundedCornerShape(12.dp))
                .background(fill)
                .semantics { contentDescription = tab.label; this.selected = selected }
                .clickable(interaction, LocalIndication.current, role = Role.Tab, onClick = onClick)
                .drawWithContent {
                    drawContent()
                    if (selected && turned) {
                        val inset = 16.dp.toPx()
                        drawRect(accent, Offset(inset, size.height - 4.dp.toPx()), Size(size.width - 2 * inset, 2.dp.toPx()))
                    }
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            if (expanded) Text(tab.label, style = AppType.bodyStrong, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun FocusDirection.isArrow(): Boolean =
    this == FocusDirection.Left || this == FocusDirection.Right || this == FocusDirection.Up || this == FocusDirection.Down

private val ITEM_HEIGHT = 56.dp
private const val EXPAND_MS = 150

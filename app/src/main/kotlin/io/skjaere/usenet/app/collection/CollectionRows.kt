package io.skjaere.usenet.app.collection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.collection.model.Chip
import io.skjaere.usenet.app.collection.model.Empty
import io.skjaere.usenet.app.collection.model.MovieFilter
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieState
import io.skjaere.usenet.app.collection.model.ShowFilter
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.tvFocusMemory

/**
 * One title's row, a segment of the Dense group card ([first] and [last] round its corners): poster,
 * title, the status line, progress. The whole row is one target, with no button inside it (a remote
 * couldn't reach one, #160); TalkBack hears [TitleRow.a11y] and has Select and Remove as actions.
 * [highlighted]: a jump revealed it; TalkBack hears "Just added" only if it was ([justAdded]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TitleRowItem(
    row: TitleRow,
    first: Boolean,
    last: Boolean,
    selecting: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    justAdded: Boolean,
    artwork: Boolean,
    dateChip: Boolean,
    focus: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(HIGHLIGHT_FADE_MS))
        }
    }
    val surface = colors.surface
    val base = if (selecting && selected) colors.rowSelected else surface
    val tint = colors.accentTint
    val divider = colors.divider
    Row(
        Modifier
            .cardSegment(first, last, lerp(base, tint, glow.value))
            .drawBehind {
                if (!first) drawLine(divider, Offset(12.dp.toPx(), 0f), Offset(size.width - 12.dp.toPx(), 0f), 1.dp.toPx())
            }
            .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusOutline(RoundedCornerShape(if (first) 16.dp else 0.dp, if (first) 16.dp else 0.dp, if (last) 16.dp else 0.dp, if (last) 16.dp else 0.dp))
            .combinedClickable(onClickLabel = if (selecting) "Select" else "Open", onClick = onClick, onLongClick = if (selecting) null else onLongClick)
            .clearAndSetSemantics {
                contentDescription = row.a11y
                when {
                    selecting -> {
                        role = Role.Checkbox
                        this.selected = selected
                        stateDescription = if (selected) "Selected" else "Not selected"
                    }
                    justAdded -> stateDescription = "Just added"
                }
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Select") { onSelect(); true }.takeIf { !selecting },
                    CustomAccessibilityAction("Remove from collection") { onRemove(); true },
                )
            }
            .heightIn(min = 86.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) SelectMark(selected)
        Poster(row.posterPath, artwork, 44, 66, imageWidth = 154)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.text.name, style = AppType.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when {
                    row is ShowRow && dateChip && row.upcomingLabel != null -> Pill(row.upcomingLabel, colors.chip, colors.chipText)
                    row is ShowRow && row.newEpisodes > 0 -> Pill("NEW", colors.accentTint, colors.accent)
                }
            }
            when (row) {
                is ShowRow -> {
                    Text(row.status, style = AppType.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.watchedFraction?.let { ProgressLine(it) }
                }
                is MovieRow -> {
                    if (row.meta.isNotEmpty()) Text(row.meta, style = AppType.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        row.status, style = AppType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = when (row.state) {
                            MovieState.InProgress -> colors.accent
                            MovieState.Watched -> colors.good
                            else -> colors.muted
                        },
                    )
                    row.progressFraction?.let { ProgressLine(it) }
                }
            }
        }
    }
}

/** The Dense group card, one row at a time: the list can't sit in one card (that kept it from being lazy). */
internal fun Modifier.cardSegment(first: Boolean, last: Boolean, color: Color): Modifier {
    val top = if (first) 16.dp else 0.dp
    val bottom = if (last) 16.dp else 0.dp
    return padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(color)
}

/** 4 dp, drawn rather than laid out. */
@Composable
private fun ProgressLine(fraction: Float) {
    val track = colors.chip
    val fill = colors.accent
    Box(
        Modifier.padding(top = 3.dp).fillMaxWidth().heightIn(min = 4.dp).drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
        },
    )
}

@Composable
private fun Pill(text: String, background: Color, color: Color) {
    Text(
        text, style = AppType.tag, color = color, maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Select mode's 32 dp mark before the poster: accent with a check when selected, a ring when not. */
@Composable
private fun SelectMark(selected: Boolean) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).then(
            if (selected) Modifier.background(colors.accent) else Modifier.border(2.dp, colors.markOutline, CircleShape),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
    }
}

/** Shows | Movies, DenseShow's season tabs: the selected one filled with the text colour. */
@Composable
internal fun SideSwitch(side: Side, onSwitch: (Side) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, colors.chip, RoundedCornerShape(20.dp))) {
            Side.entries.forEach { s ->
                val on = s == side
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .focusOutline(RoundedCornerShape(20.dp), color = colors.accent, fill = false)
                        .background(if (on) colors.text else Color.Transparent, RoundedCornerShape(20.dp))
                        .selectable(on, role = Role.Tab) { onSwitch(s) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.name, style = if (on) AppType.bodyStrong.copy(fontSize = 14.sp) else AppType.body.copy(fontSize = 14.sp), color = if (on) colors.background else colors.textSecondary)
                }
            }
        }
    }
}

/** The filter chips with their counts, sideways; one with nothing stays, dimmed, and can still be picked. */
@Composable
internal fun FilterChips(chips: List<Chip>, noun: String, onPick: (String) -> Unit) {
    // Sideways, only as far as a chip needs to show: a TV's list pivot is for the list.
    EdgeScrolling {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp).tvFocusMemory()) {
            items(chips, key = { it.name }) { chip ->
                val shape = RoundedCornerShape(8.dp)
                // The 48 dp target takes the focus; the 32 dp chip inside shows it.
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                Box(
                    Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "${chip.label}, ${chip.count} ${noun(noun, chip.count)}" }
                        .selectable(chip.selected, interactionSource = source, indication = null, role = Role.RadioButton) { onPick(chip.name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${chip.label} ${chip.count}",
                        style = if (chip.selected) AppType.bodyStrong.copy(fontSize = AppType.secondary.fontSize) else AppType.secondary,
                        color = if (chip.selected) colors.accent else colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.alpha(if (chip.count == 0 && !chip.selected) 0.5f else 1f).heightIn(min = 32.dp).clip(shape)
                            .then(if (chip.selected) Modifier.background(colors.accentTint, shape) else Modifier.border(1.dp, colors.chip, shape))
                            .then(if (focused) Modifier.background(colors.chip, shape).border(2.dp, colors.accent, shape) else Modifier)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

internal fun noun(noun: String, n: Int) = if (n == 1) noun else "${noun}s"

/**
 * The header line: "214 shows · 12 with new episodes", the sort ([sortFocus]), "Jump to letter" for a
 * remote and Select all in Finished. It wraps at large font sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeaderLine(
    ui: CollectionUi,
    dpad: Boolean,
    jumpFocus: FocusRequester,
    sortFocus: FocusRequester,
    onSort: (String) -> Unit,
    onJump: () -> Unit,
    onSelectAll: () -> Unit,
) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp).padding(top = 4.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ui.view.header, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 12.dp).weight(1f, fill = true))
        if (dpad && ui.view.total > 0) TextAction("Jump to letter", Modifier.focusRequester(jumpFocus), onClick = onJump)
        var open by remember { mutableStateOf(false) }
        Box {
            TextAction("${ui.sortLabel} ▾", Modifier.focusRequester(sortFocus).semantics { contentDescription = "Sort: ${ui.sortLabel}" }, color = colors.accent) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
                ui.sorts.forEach { (name, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = if (name == ui.sortName) AppType.bodyStrong else AppType.body, color = if (name == ui.sortName) colors.accent else colors.text) },
                        onClick = { open = false; onSort(name) },
                    )
                }
            }
        }
        // It stays in select mode, as select mode's own does (Select none once all are): the focus on it stays too.
        val finished = ui.filterName == ShowFilter.Finished.name && ui.side == Side.Shows
        if (finished && ui.view.rows.isNotEmpty()) TextAction(if (ui.selection?.allSelected == true) "Select none" else "Select all", onClick = onSelectAll)
    }
}

/** A text button: a size under the body's on a phone; on a TV (the Undo bar's Undo) the TV's own, read from across the room. */
@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = colors.accent, onClick: () -> Unit) {
    val style = if (LocalFormFactor.current == FormFactor.Tv) AppType.bodyStrong else AppType.bodyStrong.copy(fontSize = 14.sp)
    Text(
        text, style = style, color = color,
        modifier = modifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(12.dp), fill = false)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 13.dp),
    )
}

/** Without a TMDB key: the rows show what is stored, and this says where a key goes. [modifier] goes on what takes the focus. */
@Composable
internal fun NoKeyHint(side: Side, onMetadata: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
            .background(colors.accentTint, RoundedCornerShape(12.dp)).focusOutline()
            .clickable(role = Role.Button, onClick = onMetadata).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (side == Side.Shows) "Add a TMDB key for episode lists and air dates" else "Add a TMDB key for release dates and runtimes",
            style = AppType.secondary, color = colors.accent, modifier = Modifier.weight(1f),
        )
        Text("›", style = AppType.body, color = colors.accent)
    }
}

/**
 * What a side says when it shows nothing; every way out points to Search or to All, nothing is
 * recommended. Without a TMDB key ([hasKey]) Search can't add anything: an empty side points to the
 * key instead ([onMetadata]). [onShowAll] hears whether its button had the focus (it goes as the rows come).
 * [button] goes on its button (a TV's focus key, and its way to the rail); a TV says no "tap".
 */
@Composable
internal fun EmptyState(
    side: Side,
    empty: Empty,
    filterLabel: String,
    hasKey: Boolean,
    onFind: () -> Unit,
    onMetadata: () -> Unit,
    onShowAll: (focused: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    button: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val kind = if (side == Side.Shows) "show" else "movie"
        var focused by remember { mutableStateOf(false) }
        val showAll = button.onFocusChanged { focused = it.isFocused }.focusOutline(RoundedCornerShape(24.dp), fill = false)
        when (empty) {
            Empty.NoTitles -> {
                Text(if (side == Side.Shows) "No shows yet" else "No movies yet", style = AppType.cardTitle, color = colors.text)
                val outline = button.focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false)
                if (hasKey) {
                    Text(noTitlesLine(kind, tv = LocalFormFactor.current == FormFactor.Tv), style = AppType.secondary, color = colors.textSecondary)
                    PrimaryButton(if (side == Side.Shows) "Find a show" else "Find a movie", onFind, outline)
                } else {
                    Text("Adding a $kind needs your own TMDB key: Settings › Metadata. Your collection stays on this device.", style = AppType.secondary, color = colors.textSecondary)
                    PrimaryButton("Add a TMDB key", onMetadata, outline)
                }
            }
            is Empty.SearchEmpty -> {
                Text(
                    if (empty.elsewhere > 0) "Nothing in $filterLabel matches “${empty.query}”" else "Nothing matches “${empty.query}”",
                    style = AppType.cardTitle, color = colors.text,
                )
                if (empty.elsewhere > 0) SecondaryButton("Show ${empty.elsewhere} in All", { onShowAll(focused) }, showAll)
            }
            is Empty.FilterEmpty -> {
                Text(filterEmptyTitle(side, empty.filter), style = AppType.cardTitle, color = colors.text)
                Text(teaching(empty.filter), style = AppType.secondary, color = colors.textSecondary)
                SecondaryButton("Show all", { onShowAll(focused) }, showAll)
            }
        }
    }
}

/** An empty side's way to fill it, a [kind] at a time; a TV has no "tap". */
internal fun noTitlesLine(kind: String, tv: Boolean): String =
    if (tv) "Search for a $kind and add it with + Collection. Your collection stays on this device."
    else "Search for a $kind and tap “+ Collection”. Your collection stays on this device."

private fun filterEmptyTitle(side: Side, filter: String): String = when (filter) {
    ShowFilter.Watching.name -> "No shows you're watching"
    ShowFilter.NewEpisodes.name -> "No new episodes"
    ShowFilter.NotStarted.name -> if (side == Side.Shows) "No shows not started" else "Nothing here"
    ShowFilter.UpToDate.name -> "No shows up to date"
    ShowFilter.Finished.name -> "No finished shows"
    MovieFilter.Unwatched.name -> "No unwatched movies"
    MovieFilter.InProgress.name -> "No movies in progress"
    MovieFilter.Watched.name -> "No watched movies"
    MovieFilter.NotYetReleased.name -> "No movies still to come"
    else -> "Nothing here"
}

/** What a filter holds, said when it holds nothing. */
private fun teaching(filter: String): String = when (filter) {
    ShowFilter.Watching.name -> "Shows you've started that have episodes left."
    ShowFilter.NewEpisodes.name -> "Shows with an unwatched episode out this past week."
    ShowFilter.NotStarted.name -> "Shows you haven't started yet."
    ShowFilter.UpToDate.name -> "Shows you've caught up on that have more to come."
    ShowFilter.Finished.name -> "Shows that have ended and that you've watched to the end show up here."
    MovieFilter.Unwatched.name -> "Movies that are out and you haven't started."
    MovieFilter.InProgress.name -> "Movies you've started."
    MovieFilter.Watched.name -> "Movies you've watched."
    MovieFilter.NotYetReleased.name -> "Movies TMDB lists with a release date still to come."
    else -> ""
}

/** The Undo bar (or a mark being written), above the tab bar; a polite live region. [undoModifier] goes on Undo. */
@Composable
internal fun UndoBar(message: String, focus: FocusRequester?, onUndo: (() -> Unit)?, modifier: Modifier = Modifier, undoModifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 52.dp)
            .background(colors.chip, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = AppType.secondary, color = colors.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onUndo != null) TextAction("Undo", undoModifier.then(focus?.let { Modifier.focusRequester(it) } ?: Modifier), onClick = onUndo)
    }
}

internal const val HIGHLIGHT_FADE_MS = 2_000

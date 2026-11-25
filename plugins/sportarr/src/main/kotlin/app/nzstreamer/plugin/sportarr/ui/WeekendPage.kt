package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.turnsOver

/**
 * A motorsport weekend's page (L644, L652): one round's sessions, each its own event in Sportarr, the race its headline.
 * Drawn as a page of parts ([PartsPage]), a session a row.
 */
@Composable
internal fun WeekendPage(screen: EventScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    PartsPage(screen, tv, actions, okGuard, bar)
}

/**
 * A page of parts, a weekend's sessions or a card's parts, as a show's page lists its episodes, without a picture (L653).
 * The title; the league and when (a weekend's round and days); ✓ Watched, or when the first starts; how many are watched,
 * with its bar; the top button, Resume for what was played last, else Play for the next part begun; a row per part, in
 * time or card order, its day and time or when it starts, what is left with its bar, its ✓, and its ⋮ (Choose release,
 * Search indexers, Mark watched or unwatched); the footnote. A part not begun yet can't be tapped and has no ⋮.
 *
 * On a TV, two panes: the title, the count, the top button and Choose release for the part it plays on the left, with
 * the TV's footnote at its foot; the parts on the right, the one the top button plays marked. OK on a part plays it; OK
 * on its ⋮ opens the menu, the focus on its first item; a part not begun takes no focus. The focus starts on the top
 * button, else on the first part begun and not watched; Back from Choose release puts it back on Choose release or on the
 * ⋮ it came from.
 */
@Composable
internal fun PartsPage(screen: EventScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    PageFocus(EVENT_SHOWN, screen.firstFocus, tv, actions.focus)
    if (tv) TvParts(screen, actions, okGuard) else PhoneParts(screen, actions, okGuard, bar)
}

@Composable
private fun PhoneParts(screen: EventScreen, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    val list = rememberLazyListState()
    // The line Play or Choose release leaves sits under the top button: brought into view as it comes, from a row's ⋮
    // far down the page, so its outcome shows (not as the page opens with one).
    val kept = screen.kept
    var seen by remember { mutableStateOf(kept) }
    LaunchedEffect(kept) {
        if (kept != null && kept != seen) list.animateScrollToItem(0)
        seen = kept
    }
    Column(Modifier.fillMaxSize().pageOkGuard(okGuard)) {
        bar()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageTitle(screen.title, tv = false)
                    Text(screen.meta, style = AppType.secondary, color = colors.muted)
                    screen.status?.let { Status(it, AppType.secondary, 3.dp, colors.divider) }
                    screen.count?.let { Status(it, AppType.secondary, 3.dp, colors.divider) }
                    screen.buttons.forEach { button -> Box(Modifier.padding(top = 6.dp)) { PhoneButton(button, actions) } }
                    screen.note?.let { SmallLine(it) }
                    screen.kept?.let { SmallLine(it, live = true) }
                }
            }
            item {
                GroupCard {
                    screen.parts.forEachIndexed { index, row ->
                        key(row.part) {
                            if (index > 0) InsetDivider()
                            PhonePartRow(row, actions)
                        }
                    }
                }
            }
            item { Footnote(screen.footnote) }
        }
    }
}

/**
 * A part's row on a phone, as a show's episode rows: its name, its day and time or when it starts, what is left in the
 * accent over its 2 dp bar, the ✓ in the green; its ⋮ beside it. A tap plays it. One not begun is quieter, can't be tapped
 * and has room where its ⋮ would be.
 */
@Composable
private fun PhonePartRow(row: PartRow, actions: PageActions) {
    val rowKey = PageKeys.row(row.part)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.focusRequester(actions.focus.requester(rowKey)).weight(1f).heightIn(min = 68.dp)
                .then(
                    if (row.started) {
                        Modifier.focusOutline().clickable(onClickLabel = Words.Game.PLAY, role = Role.Button) { actions.play(row.part, rowKey) }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.label, style = AppType.body, color = if (row.started) colors.text else colors.textSecondary)
                row.meta?.let { Text(it, style = AppType.small, color = colors.muted) }
                if (row.searching) {
                    Text(Words.Game.SEARCHING, style = AppType.small, color = colors.accent, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                row.left?.let { left ->
                    Text(left, style = AppType.small, color = colors.accent)
                    row.progress?.let { ProgressBar(it, 2.dp, colors.divider) }
                }
            }
            if (row.watched) Text(Words.SportsList.TICK, style = AppType.bodyStrong, color = colors.good)
        }
        if (row.menu.isEmpty()) {
            Spacer(Modifier.width(52.dp))
        } else {
            Box(Modifier.padding(end = 4.dp)) {
                PartMenu(row, actions) { open ->
                    IconButton(
                        onClick = open,
                        modifier = Modifier.focusRequester(actions.focus.requester(PageKeys.more(row.part))).focusOutline(CircleShape, fill = false),
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = Words.Weekend.moreFor(row.label), tint = colors.muted) }
                }
            }
        }
    }
}

@Composable
private fun TvParts(screen: EventScreen, actions: PageActions, okGuard: OkGuard) {
    val panes = remember(actions.focus) { PaneMemory(actions.focus) }
    TvPanes(
        panes, okGuard,
        left = {
            TvHeading(screen)
            TvButtons(screen.buttons, panes, actions)
            screen.note?.let { HeldLine(it, panes) }
            screen.kept?.let { SmallLine(it, live = true) }
        },
        leftFoot = { TvFoot(screen.footnote, TvDimens.pagePane) },
        right = {
            // Taller than the page (many sessions, a larger text size), the parts scroll as far as the focused one needs.
            EdgeScrolling {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
                        screen.parts.forEachIndexed { index, row ->
                            key(row.part) {
                                if (index > 0) InsetDivider()
                                TvPartRow(row, panes, actions)
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * A part's row on a TV, as a show's TV episode rows: its name (strong for the one the top button plays, whose row is
 * marked) over its day and time with what is left in the accent, the 2 dp bar under them, the ✓ in the accent; turned
 * over while focused. Its ⋮ beside it, a stop of its own. One not begun takes no focus.
 */
@Composable
private fun TvPartRow(row: PartRow, panes: PaneMemory, actions: PageActions) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val accent = if (turned) colors.turnedAccent else colors.accent
    val rowKey = PageKeys.row(row.part)
    Row(
        Modifier.fillMaxWidth().then(if (row.current) Modifier.background(colors.rowSelected) else Modifier)
            .padding(start = 5.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OwnedRipple {
            Row(
                Modifier.paneElement(panes, RIGHT, rowKey).weight(1f).heightIn(min = 60.dp)
                    .then(
                        if (row.started) {
                            Modifier.focusScale(interaction, { it.rowScale }).clip(RoundedCornerShape(10.dp))
                                .then(if (turned) Modifier.background(colors.text) else Modifier)
                                .clickable(interaction, LocalIndication.current, onClickLabel = Words.Game.PLAY, role = Role.Button) { actions.play(row.part, rowKey) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.label, style = if (row.current) AppType.bodyStrong else AppType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            turned -> colors.background
                            row.started -> colors.text
                            else -> colors.textSecondary
                        },
                    )
                    // One line, as a TV's episode rows have it: what is left, or that Choose release is searching, in the accent.
                    val lit = listOfNotNull(row.left, Words.Game.SEARCHING.takeIf { row.searching })
                    if (row.meta != null || lit.isNotEmpty()) {
                        Text(
                            buildAnnotatedString {
                                row.meta?.let(::append)
                                lit.forEach { words ->
                                    if (length > 0) append(" · ")
                                    withStyle(SpanStyle(color = accent)) { append(words) }
                                }
                            },
                            style = AppType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = when {
                                turned -> colors.turnedSecondary
                                row.current -> colors.textSecondary
                                else -> colors.muted
                            },
                        )
                    }
                    row.progress?.let { ProgressBar(it, 2.dp, colors.divider, accent) }
                }
                if (row.watched) Text(Words.SportsList.TICK, style = AppType.bodyStrong, color = accent)
            }
        }
        if (row.menu.isEmpty()) {
            Spacer(Modifier.width(TV_MORE))
        } else {
            PartMenu(row, actions) { open -> TvMore(Modifier.paneElement(panes, RIGHT, PageKeys.more(row.part)), Words.Weekend.moreFor(row.label), open) }
        }
    }
}

/**
 * A part's ⋮ ([button], which opens it) and its menu: Choose release, Search indexers, Mark watched or unwatched. On a TV
 * the menu's first item takes the focus as it opens (the kit's menu); as it closes the focus is back on the ⋮, and Choose
 * release or Search come back to it.
 */
@Composable
private fun PartMenu(row: PartRow, actions: PageActions, button: @Composable (open: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        button { expanded = true }
        TvMenu(expanded, onDismiss = { expanded = false }) { first ->
            row.menu.forEachIndexed { index, entry ->
                TvMenuItem(entry.label, if (index == 0) first else Modifier) {
                    expanded = false
                    actions.menu(row.part, entry.action)
                }
            }
        }
    }
}

/** A part's ⋮ on a TV: turned over while focused, as the TV's buttons are. */
@Composable
private fun TvMore(modifier: Modifier, spoken: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Box(
            modifier.size(TV_MORE).focusScale(interaction, { it.buttonScale }).clip(CircleShape)
                .then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MoreVert, contentDescription = spoken, tint = if (turned) colors.background else colors.muted) }
    }
}

/** A TV part's ⋮, and the room a part not begun keeps in its place. */
private val TV_MORE = 48.dp

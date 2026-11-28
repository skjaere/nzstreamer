package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.OkKeys
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
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.tryFocus
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
 * A UFC card's parts have their bouts under them, which play their part as its row does, then the credit, which opens
 * its sheet ([CreditSheet]); or the line of the card's state in the credit's place, with Try again or Sportarr settings
 * where it has them (L-B7).
 *
 * On a TV, two panes: the title, the count, the top button and Choose release for the part it plays on the left, with
 * the TV's footnote at its foot; the parts on the right, the one the top button plays marked. OK on a part plays it; OK
 * on its ⋮ opens the menu, the focus on its first item; a part not begun takes no focus. The focus starts on the top
 * button, else on the first part begun and not watched; Back from Choose release puts it back on Choose release or on the
 * ⋮ it came from. A card's bouts and its credit come row by row after their part, the credit the list's last stop; a
 * line's button comes after the last part (L-B10).
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
    // The credit's sheet is the page's, open or not, not its row's: the row is a list item, composed only while in view,
    // and a page made again (turned, or at a larger text size) can leave it out of view with the sheet open, which would
    // close it, then open it unasked as the row came back.
    var about by rememberSaveable { mutableStateOf(false) }
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
                            row.bouts.forEach { bout ->
                                key(bout.key) {
                                    BoutDivider()
                                    PhoneBoutRow(bout, row, actions)
                                }
                            }
                        }
                    }
                }
            }
            screen.cardLine?.let { line -> item { PhoneCardLine(line, actions) } }
            screen.credit?.let { item { PhoneCreditRow(actions) { about = true } } }
            item { Footnote(screen.footnote) }
        }
    }
    screen.credit?.takeIf { about }?.let { credit -> CreditSheet(credit, tv = false) { about = false } }
}

/**
 * A bout's row on a phone, under its part's (BtPhoneCard): its names at the secondary size, a step under the part's name,
 * so the parts, which are what plays, stand out; its weight class and labels under them; 28 dp in, with the ⋮'s room kept
 * at its end, and no ⋮, ✓ or bar of its own. A tap plays its part, as the part's row does (W5), TalkBack saying which
 * ("Play Prelims"); before the part has started, in its not-aired colour, it can't be tapped, and is read as one line.
 */
@Composable
private fun PhoneBoutRow(bout: BoutRow, row: PartRow, actions: PageActions) {
    val play = bout.play
    Column(
        Modifier.fillMaxWidth().focusRequester(actions.focus.requester(bout.key)).heightIn(min = 54.dp)
            .then(
                if (play != null) {
                    Modifier.focusOutline().clickable(onClickLabel = play, role = Role.Button) { actions.play(row.part, bout.key) }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(start = 28.dp, end = 64.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(bout.names, style = AppType.secondary, color = if (row.started) colors.text else colors.textSecondary)
        BoutLine(bout, tv = false, turned = false)
    }
}

/**
 * A bout's weight class, small and muted (at the secondary size on a TV), then its labels: Title fight in the chip's grey,
 * Main event in the accent's tint; on a TV row turned over, shades of the dark words on the cream.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoutLine(bout: BoutRow, tv: Boolean, turned: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            bout.weight, style = if (tv) AppType.secondary else AppType.small, color = if (turned) colors.turnedSecondary else colors.muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (bout.titleFight) BoutLabel(Words.Bouts.TITLE_FIGHT, accent = false, turned, Modifier.align(Alignment.CenterVertically))
        if (bout.mainEvent) BoutLabel(Words.Bouts.MAIN_EVENT, accent = true, turned, Modifier.align(Alignment.CenterVertically))
    }
}

/** A bout's label, as the kit's chips: [accent] in the accent's tint, else the chip's grey. */
@Composable
private fun BoutLabel(text: String, accent: Boolean, turned: Boolean, modifier: Modifier) {
    val (background, words) = when {
        turned && accent -> TURNED_ACCENT_LABEL to colors.turnedAccent
        turned -> TURNED_LABEL to colors.background
        accent -> colors.accentTint to colors.accent
        else -> colors.chip to colors.chipText
    }
    Text(text, style = AppType.small, color = words, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 1.dp))
}

/** The divider over a bout's row, as far in as its words. */
@Composable
private fun BoutDivider(start: Dp = 28.dp) = HorizontalDivider(Modifier.padding(start = start, end = 12.dp), thickness = 1.dp, color = colors.divider)

/**
 * The line under a card's parts on a phone while it shows no bouts (BtPhoneCardStates), in the footnote's small muted
 * style, as far in as the parts' names: a spinner before it while the card is read; Try again or Sportarr settings after
 * it, as the list's line has its button. A read's outcome is announced as it comes.
 */
@Composable
private fun PhoneCardLine(line: CardLine, actions: PageActions) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 28.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (line == CardLine.Reading) CircularProgressIndicator(Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
        Text(
            line.words, style = AppType.small, color = colors.muted,
            modifier = Modifier.weight(1f).then(if (line in OUTCOMES) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
        line.button?.let { words ->
            TextButton(
                onClick = { actions.cardButton(line) },
                modifier = Modifier.focusRequester(actions.focus.requester(PageKeys.CARD_BUTTON)).heightIn(min = 48.dp).focusOutline(RoundedCornerShape(20.dp), fill = false),
            ) { Text(words, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.accent) }
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
    // Try again's button goes as its read starts. Before the start, with no part to take the focus, it waits on the line in
    // the buttons' place for the bouts, and goes to the last part's first bout as they come (L-B10); a read that ends with
    // no card ends the wait there. (One that fails again brings the button back, and the wait goes on to its next press.)
    var awaitingBouts by remember { mutableStateOf(false) }
    val lastBout = screen.parts.lastOrNull()?.bouts?.firstOrNull()?.key
    LaunchedEffect(awaitingBouts, lastBout, screen.cardLine) {
        if (!awaitingBouts) return@LaunchedEffect
        when {
            lastBout != null -> {
                withFrameNanos {}
                actions.focus.requester(lastBout).tryFocus()
                awaitingBouts = false
            }
            screen.cardLine == CardLine.NotYet || screen.cardLine == CardLine.NoCard -> awaitingBouts = false
        }
    }
    val cardButton = { line: CardLine ->
        if (line == CardLine.CantReach) {
            // The focus off the button before it goes: on the last part's row, which the bouts come under.
            val last = screen.parts.lastOrNull { it.started }
            if (last != null) {
                actions.focus.requester(PageKeys.row(last.part)).tryFocus()
            } else {
                actions.focus.requester(PageKeys.HELD).tryFocus()
                awaitingBouts = true
            }
        }
        actions.cardButton(line)
    }
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
                                row.bouts.forEach { bout ->
                                    key(bout.key) {
                                        BoutDivider(TV_BOUT_DIVIDER)
                                        TvBoutRow(bout, row, panes, actions)
                                    }
                                }
                            }
                        }
                    }
                    screen.cardLine?.let { line -> TvCardLine(line, panes, cardButton) }
                    screen.credit?.let { credit -> TvCreditRow(credit, panes, actions) }
                }
            }
        },
    )
}

/**
 * A bout's row on a TV (BtTvCard): as a part's row, 22 dp further in, its names on one line at the secondary size over its
 * weight class and labels; turned over while focused; no ⋮, its room kept so the rows' ends line up, and Right does
 * nothing, as the 2D search would find a part's ⋮ nearby. OK plays its part (W5), the focus staying on it. Before the
 * part has started it still takes the focus, so Down reads the card, shown by the ring on the chip's fill as Search's
 * words are, and OK does nothing.
 */
@Composable
private fun TvBoutRow(bout: BoutRow, row: PartRow, panes: PaneMemory, actions: PageActions) {
    val interaction = remember { MutableInteractionSource() }
    val press = remember(interaction) { BoutPress(interaction) }
    val play = bout.play
    val turned = interaction.turnsOver() && play != null
    Row(
        Modifier.fillMaxWidth().padding(start = 5.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OwnedRipple {
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .then(
                        if (play != null) {
                            Modifier.focusScale(interaction, { it.rowScale }).clip(RoundedCornerShape(10.dp))
                                .then(if (turned) Modifier.background(colors.text) else Modifier)
                        } else {
                            Modifier.focusOutline(RoundedCornerShape(10.dp))
                        },
                    )
                    .boutFocus(Modifier.paneElement(panes, RIGHT, bout.key), interaction, LocalIndication.current, press, play) { actions.play(row.part, bout.key) }
                    .padding(start = 28.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            ) {
                Text(
                    bout.names, style = AppType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when {
                        turned -> colors.background
                        row.started -> colors.text
                        else -> colors.textSecondary
                    },
                )
                BoutLine(bout, tv = true, turned = turned)
            }
        }
        Spacer(Modifier.width(TV_MORE))
    }
}

/**
 * A TV bout's focus and OK (L-B10). Its focus is the same element before its part starts and after, so the start, which a
 * minute's tick brings while the user reads the card, changes what OK does and never the node holding the focus: Compose
 * keeps a node only for an element of the same class in its place, and one dropped while focused takes the focus off the
 * bout. (A clickable once started would be such an element, and one over the focusable would say the bout isn't focused.)
 * Once [play] is set (its part begun), OK and a pointer's tap play ([onPlay]) as a clickable's do, through [press], with
 * [interaction] showing the press through [indication] and a pointer over it, and TalkBack says what it plays; before, the
 * row takes the focus, OK does nothing, and it is read as one line. [element] (its pane's memory of it) and Right going
 * nowhere are on the focus itself.
 */
internal fun Modifier.boutFocus(
    element: Modifier,
    interaction: MutableInteractionSource,
    indication: Indication?,
    press: BoutPress,
    play: String?,
    onPlay: () -> Unit,
): Modifier = indication(interaction, indication)
    .hoverable(interaction)
    .pointerInput(play) {
        if (play == null) return@pointerInput
        detectTapGestures(onPress = { at -> press.pointer(at) { tryAwaitRelease() } }, onTap = { onPlay() })
    }
    .onSizeChanged { press.middle = Offset(it.width / 2f, it.height / 2f) }
    .onKeyEvent { event ->
        val down = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return@onKeyEvent false
        }
        press.key(event.nativeKeyEvent.keyCode, down, playable = play != null, onPlay)
    }
    .semantics(mergeDescendants = true) {
        if (play != null) {
            role = Role.Button
            onClick(label = play) {
                onPlay()
                true
            }
        }
    }
    .then(element)
    .focusProperties { right = FocusRequester.Cancel }
    .onFocusChanged { if (!it.isFocused) press.letGo() }
    .focusable(interactionSource = interaction)

/**
 * A TV bout's presses, for [boutFocus], shown on [interaction] as a clickable shows its own. OK is pressed on its down
 * (from the row's [middle]), its repeats taken, and plays on the up of a press it saw; a press under way is let go as the
 * bout loses the focus, and one on a bout that can't play isn't taken. A pointer's press shows from where it went down.
 */
internal class BoutPress(private val interaction: MutableInteractionSource) {
    private var press: PressInteraction.Press? = null

    /** Where OK's press shows from: the row's middle, as a clickable's key press does. */
    var middle: Offset = Offset.Zero

    /** Whether the key [keyCode] going [down] or up was OK on a bout that plays ([playable]), and so taken: [onPlay] on its up. */
    fun key(keyCode: Int, down: Boolean, playable: Boolean, onPlay: () -> Unit): Boolean {
        if (keyCode !in OkKeys) return false
        if (!playable) {
            letGo()
            return false
        }
        if (down) {
            if (press == null) press = PressInteraction.Press(middle).also { interaction.tryEmit(it) }
            return true
        }
        val pressed = press ?: return false
        press = null
        interaction.tryEmit(PressInteraction.Release(pressed))
        onPlay()
        return true
    }

    /** Lets go of OK's press under way, as the bout loses the focus. */
    fun letGo() {
        press?.let { interaction.tryEmit(PressInteraction.Cancel(it)) }
        press = null
    }

    /** A pointer's press from [at] until it is [released], or cancelled where it isn't (dragged off, or taken by a scroll). */
    suspend fun pointer(at: Offset, released: suspend () -> Boolean) {
        val down = PressInteraction.Press(at)
        interaction.tryEmit(down)
        interaction.tryEmit(if (released()) PressInteraction.Release(down) else PressInteraction.Cancel(down))
    }
}

/**
 * The line under a card's parts on a TV while it shows no bouts (BtTvCardStates), as the list's offline line there: its
 * small button first where it has one, the only stop on it, reached by Down from the last part, or by Right from the line
 * in the buttons' place before the start ([onButton]); then a spinner while the card is read, and the words.
 */
@Composable
private fun TvCardLine(line: CardLine, panes: PaneMemory, onButton: (CardLine) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        line.button?.let { words -> SecondaryButton(words, { onButton(line) }, Modifier.paneElement(panes, RIGHT, PageKeys.CARD_BUTTON)) }
        if (line == CardLine.Reading) CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
        Text(
            line.words, style = AppType.secondary, color = colors.textSecondary,
            modifier = Modifier.weight(1f).then(if (line in OUTCOMES) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
    }
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
internal val TV_MORE = 48.dp

/** A bout's divider on a TV, as far in as its words. */
private val TV_BOUT_DIVIDER = 33.dp

/** A turned-over bout's Main event label: a shade of the turned accent on the cream. */
private val TURNED_ACCENT_LABEL = Color(0x248A5000)

/** The lines that are a read's outcome, which TalkBack says as they come. */
private val OUTCOMES = setOf(CardLine.NotYet, CardLine.NoCard, CardLine.CantReach)

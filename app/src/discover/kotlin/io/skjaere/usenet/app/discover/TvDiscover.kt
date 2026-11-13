package io.skjaere.usenet.app.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.tv.CardRow
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.MoreCard
import io.skjaere.usenet.app.tv.PosterCard
import io.skjaere.usenet.app.tv.SwitchSide
import io.skjaere.usenet.app.tv.TvChip
import io.skjaere.usenet.app.tv.TvSection
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TMDB_ATTRIBUTION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Discover tab on a TV (the DcTv mockup), as TV Home is laid out: one list across the column beside the rail. Its
 * top line ("Discover", Shows | Movies, the genre chips) is there from the first frame, before TMDB answers, so the
 * focus has somewhere to land as the tab opens (TV-4's trap: nothing focusable while loading loses the first focus to
 * the rail). Then each list's row of posters, ending in See all; a row TMDB failed for has Try again in its place, which
 * keeps the focus while TMDB is asked again and hands it to the row's first card once it comes.
 *
 * The first focus: the switch's selected side; the element that opened a page, coming back from it; without a key,
 * or with one TMDB refused, the card's button, and the switch again once a key works. Left from a line's first element
 * goes to the rail, Right from the rail back to the element left.
 */
@Composable
internal fun TvDiscover(
    ui: DiscoverUi,
    onSide: (MediaType) -> Unit,
    onGenre: (Int?) -> Unit,
    onRetry: (DiscoverList?) -> Unit,
    onOpen: (DiscoverCard) -> Unit,
    onList: (DiscoverList) -> Unit,
    onMetadata: () -> Unit,
) {
    val focus = rememberReturnFocus()
    val initial = rememberInitialFocus()
    val rail = LocalRailFocus.current
    val memory = remember { TabMemory() }
    val list = rememberLazyListState()
    val latest by rememberUpdatedState(ui)

    /** The element [key]: the focus can be put on it, and the tab keeps that it had it. */
    fun Modifier.element(key: String): Modifier = focusRequester(focus.requester(key)).onFocusChanged {
        if (it.isFocused) {
            memory.last = key
            memory.focused = key
        } else if (memory.focused == key) {
            memory.focused = null
        }
    }

    /**
     * A page card's button goes with its card as the rows are asked again (Try again, Clear filters): the focus first on
     * the switch's selected side above it, rather than lost and put wherever the shell finds.
     */
    fun handedUp(action: () -> Unit) {
        focus.requester(switchKey(latest.side)).tryFocus()
        action()
    }

    /** The element [key] opens something (a page, Settings): the focus comes back to it after. */
    fun open(key: String, action: () -> Unit) {
        focus.leaving(key)
        action()
    }

    // In view before the focus goes back to it: a row's card, or the list's item, is composed only once it is near.
    suspend fun bringIntoView(key: String) {
        val items = columnItems(latest)
        val row = rowOfKey(key) ?: return
        val at = items.indexOf(ROW + row.name)
        if (at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == ROW + row.name }) list.scrollToItem(at)
        val cards = (latest.rows.firstOrNull { it.list == row }?.view as? RowView.Cards)?.cards ?: return
        val index = if (key.startsWith(MORE)) cards.size else cards.indexOfFirst { cardKey(row, it) == key }
        val rowState = memory.row(latest.side, row)
        if (index >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
    }

    // The first focus, and again when what the tab can show changes under it (a key added or refused): back on what
    // opened what was in front, else the switch's selected side or the card's button. Asked a frame at a time until it
    // takes, as a tab fading in may hand it to the one leaving (#244).
    val first = when (ui.key) {
        KeyState.Missing -> ADD_KEY_EL
        KeyState.Refused -> CHECK_KEY_EL
        else -> SWITCH
    }
    LaunchedEffect(first) {
        // Back from a page before the rows are there (asked again, or the tab's view model new after Android ended the
        // app in the background): the opener's row comes first, for a while, as a grid waits for its poster.
        focus.pending?.let(::rowOfKey)?.let { row ->
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                snapshotFlow {
                    when (latest.key) {
                        null -> false
                        KeyState.Ready -> latest.rows.firstOrNull { it.list == row }?.view.let { it != null && it != RowView.Loading }
                        else -> true
                    }
                }.first { it }
            }
        }
        if (focus.restore(::bringIntoView)) return@LaunchedEffect
        initial.awaitTurn()
        // The tab can come back scrolled down (its place is kept with it): its top line is composed only once in view.
        if (list.firstVisibleItemIndex > 0) list.scrollToItem(0)
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            val key = if (first == SWITCH) switchKey(latest.side) else first
            if (memory.focused == key) return@LaunchedEffect
            focus.requester(key).tryFocus()
        }
    }

    // The rows as they answer: the shell settles the focus should it have been lost meanwhile.
    val answered = ui.rows.count { it.view !is RowView.Loading }
    LaunchedEffect(answered, ui.key) { rail?.contentChanged() }

    // Elements alone on their line, away from the column's start: Left goes to the rail.
    val left = Modifier.focusProperties { rail?.let { left = it.wayIn } }
    LazyColumn(
        Modifier.fillMaxHeight().tvFullWidth().semantics { tvDiscover = true }
            // From the rail, back to the element last focused: the shell's own memory keeps only this list.
            .focusProperties { onEnter = { if (rail?.hasFocus == true) memory.last?.let { focus.requester(it).tryFocus() } } }
            .focusGroup(),
        state = list,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        columnItems(ui).forEach { part ->
            when (part) {
                TOP -> item(key = part) {
                    TopLine(ui, rail?.wayIn, { k -> element(k) }, onSide, onGenre = { genre -> onGenre(genre) })
                }
                LOADING -> item(key = part) { Blanks() }
                NO_KEY -> item(key = part) { TvStateCard(NO_KEY_TITLE, NO_KEY_LINE, ADD_KEY, primary = true, { open(ADD_KEY_EL, onMetadata) }, buttonModifier = left.element(ADD_KEY_EL)) }
                REFUSED -> item(key = part) { TvStateCard(REFUSED_TITLE, REFUSED_LINE, CHECK_KEY, primary = true, { open(CHECK_KEY_EL, onMetadata) }, buttonModifier = left.element(CHECK_KEY_EL)) }
                OFFLINE -> item(key = part) { ui.offline?.let { OfflineLine(it) } }
                FAILED -> item(key = part) { TvStateCard(ui.failure.orEmpty(), null, TRY_AGAIN, primary = false, { handedUp { onRetry(null) } }, buttonModifier = left.element(RETRY_ALL)) }
                NOTHING -> item(key = part) { TvStateCard(NOTHING_MATCHES, null, CLEAR_FILTERS, primary = false, { handedUp { onGenre(null) } }, buttonModifier = left.element(CLEAR)) }
                FOOTNOTE_ITEM -> item(key = part) { Footnote("$FOOTNOTE $TMDB_ATTRIBUTION") }
                else -> {
                    val row = ui.shownRows.firstOrNull { ROW + it.list.name == part } ?: return@forEach
                    item(key = part) {
                        val view = row.view
                        // Whether the row's Try again had the focus as the row came to [view]: asked again (pressed, or by
                        // itself after TMDB said to slow down), Try again keeps it while TMDB answers, rather than the
                        // focus being lost and put elsewhere; then the row's first card has it, Try again being gone.
                        val fromTryAgain = remember(view) { memory.focused == retryKey(row.list) }
                        TvSection(row.heading) {
                            if (view is RowView.Failed || (view == RowView.Loading && fromTryAgain)) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    SecondaryButton(TRY_AGAIN, { if (view is RowView.Failed) onRetry(row.list) }, Modifier.element(retryKey(row.list)))
                                    Text((view as? RowView.Failed)?.message ?: ASKING_AGAIN, style = AppType.secondary, color = colors.textSecondary)
                                }
                            } else if (view is RowView.Cards) {
                                CardRow(
                                    view.cards, key = { cardKey(row.list, it) }, state = memory.row(ui.side, row.list),
                                    end = { MoreCard(SEE_ALL, { open(moreKey(row.list)) { onList(row.list) } }, Modifier.element(moreKey(row.list)), spoken = seeAllSaid(row.heading)) },
                                ) { card ->
                                    val key = cardKey(row.list, card)
                                    PosterCard(
                                        card.title.title, card.title.posterPath, ui.artwork, onClick = { open(key) { onOpen(card) } },
                                        modifier = Modifier.element(key), subtitle = card.line, tone = card.tone, spoken = card.spoken,
                                        subtitleLines = card.lineLines,
                                    )
                                }
                                if (fromTryAgain) {
                                    LaunchedEffect(view) {
                                        withFrameNanos {}
                                        // Not when the user went on meanwhile, in the tab or to the rail.
                                        val now = memory.focused
                                        if ((now != null && now != retryKey(row.list)) || rail?.let { it.hasFocus && it.held } == true) return@LaunchedEffect
                                        val key = view.cards.firstOrNull()?.let { cardKey(row.list, it) } ?: return@LaunchedEffect
                                        bringIntoView(key)
                                        withFrameNanos {}
                                        focus.requester(key).tryFocus()
                                    }
                                }
                            } else {
                                Blanks()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the TV tab keeps of its focus while it shows: the element last focused ([last], by its ReturnFocus key) for the
 * way back from the rail, the one focused now ([focused]), and each row's place sideways, by side and list.
 */
@Stable
private class TabMemory {
    var last: String? = null
    var focused: String? = null
    private val rows = HashMap<String, LazyListState>()

    fun row(side: MediaType, list: DiscoverList): LazyListState = rows.getOrPut(side.name + list.name) { LazyListState() }
}

/** "Discover", Shows | Movies and the genre chips, on one line: the top line, focusable while TMDB is still asked. */
@Composable
private fun TopLine(ui: DiscoverUi, wayIn: FocusRequester?, element: Modifier.(String) -> Modifier, onSide: (MediaType) -> Unit, onGenre: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(DISCOVER, style = AppType.screenTitle, color = colors.text, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
        // Without a key there is nothing to switch, nor genres to pick: the card below says what to do.
        if (ui.key == KeyState.Missing || ui.key == KeyState.Refused) return@Row
        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(colors.surface).padding(3.dp)) {
            listOf(MediaType.Tv, MediaType.Movie).forEach { side ->
                // The first on its line, away from the column's start: Left goes to the rail.
                val left = if (side == MediaType.Tv) Modifier.focusProperties { wayIn?.let { left = it } } else Modifier
                SwitchSide(sideName(side), side == ui.side, left.element(switchKey(side))) { onSide(side) }
            }
        }
        // The chips' room at their row's start: a focused chip grows past its ends.
        EdgeScrolling(room = CHIP_ROOM) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = CHIP_ROOM), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") { TvChip(ALL_GENRES, ui.genre == null, Modifier.element(chipKey(null)), style = AppType.secondary, role = Role.RadioButton) { onGenre(null) } }
                items(ui.genres, key = { it.id }) { genre ->
                    TvChip(genre.name, genre.id == ui.genre, Modifier.element(chipKey(genre.id)), style = AppType.secondary, role = Role.RadioButton) { onGenre(genre.id) }
                }
            }
        }
    }
}

/**
 * A card in the lists' place, as TV Home's empty one: [title], [line] under it, its one way on; [buttonModifier] goes on
 * its button (its key and its Left to the rail, centred as it is).
 */
@Composable
internal fun TvStateCard(
    title: String,
    line: String?,
    button: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(CARD_WIDTH).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center)
            line?.let { Text(it, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center) }
            if (primary) PrimaryButton(button, onClick, buttonModifier.padding(top = 14.dp)) else SecondaryButton(button, onClick, buttonModifier.padding(top = 14.dp))
        }
    }
}

/** While TMDB is asked: a row's outlines, posters without anything on them, none focusable. */
@Composable
private fun Blanks() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(TvDimens.posterGutter)) {
        repeat(BLANKS) { Box(Modifier.size(TvDimens.poster).clip(RoundedCornerShape(8.dp)).background(colors.surface)) }
    }
}

/** The list's items, in order, for [ui]. */
private fun columnItems(ui: DiscoverUi): List<String> = buildList {
    add(TOP)
    when (ui.key) {
        null -> add(LOADING)
        KeyState.Missing -> add(NO_KEY)
        KeyState.Refused -> add(REFUSED)
        KeyState.Ready -> {
            if (ui.offline != null) add(OFFLINE)
            when {
                ui.allFailed -> add(FAILED)
                ui.nothingMatches -> add(NOTHING)
                else -> ui.shownRows.forEach { add(ROW + it.list.name) }
            }
            add(FOOTNOTE_ITEM)
        }
    }
}

private fun switchKey(side: MediaType) = SWITCH + side.name
private fun chipKey(genre: Int?) = CHIP + (genre?.toString() ?: "all")
private fun cardKey(list: DiscoverList, card: DiscoverCard) = "$CARD${list.name}:${card.key}"
private fun moreKey(list: DiscoverList) = MORE + list.name
private fun retryKey(list: DiscoverList) = RETRY + list.name

/** The row an element's key is in, for a card's, See all's or Try again's. */
private fun rowOfKey(key: String): DiscoverList? {
    val name = when {
        key.startsWith(CARD) -> key.removePrefix(CARD).substringBefore(':')
        key.startsWith(MORE) -> key.removePrefix(MORE)
        key.startsWith(RETRY) -> key.removePrefix(RETRY)
        else -> return null
    }
    return DiscoverList.entries.firstOrNull { it.name == name }
}

/**
 * The TV's Discover content, which the tests know it by: they wait for the focus inside it, whatever its first focus is
 * (the switch, a card's button), and not on the rail.
 */
val TvDiscoverKey = SemanticsPropertyKey<Boolean>("TvDiscover")
private var SemanticsPropertyReceiver.tvDiscover by TvDiscoverKey

/** The elements' keys (ReturnFocus's), by what they are; a card's and See all's carry their row's list. */
private const val SWITCH = "switch:"
private const val CHIP = "chip:"
private const val CARD = "card:"
private const val MORE = "more:"
private const val RETRY = "retry:"
private const val ADD_KEY_EL = "add-key"
private const val CHECK_KEY_EL = "check-key"
private const val RETRY_ALL = "retry-all"
private const val CLEAR = "clear"

/** The list's items. */
private const val TOP = "top"
private const val LOADING = "loading"
private const val NO_KEY = "no-key"
private const val REFUSED = "refused"
private const val OFFLINE = "offline"
private const val FAILED = "failed"
private const val NOTHING = "nothing"
private const val ROW = "row:"
private const val FOOTNOTE_ITEM = "footnote"

private val SECTION_GAP = 20.dp
private val CARD_WIDTH = 480.dp
private val CHIP_ROOM = 4.dp
private const val BLANKS = 7
private const val FOCUS_FRAMES = 10

/** How long the tab shown again waits for the row of the element that opened a page, before its own first focus. */
private const val RESTORE_WAIT_MS = 2_000L

package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nzstreamer.plugin.sportarr.model.Days
import app.nzstreamer.plugin.sportarr.model.Days.Side
import io.skjaere.usenet.app.tv.SwitchSide
import io.skjaere.usenet.app.tv.TvChip
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.LocalRail
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.PivotBringIntoViewSpec
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.app.ui.tvFocusMemory

/**
 * The Sports list on a TV (L652) and its states, one list across the column beside the rail: the title line
 * with Recent | Upcoming, the sport chips (as Discover's genres) and the ⋮; a sport's leagues on a line of their own once
 * one is picked; the notice, its button first; Up next; the days; the card in the list's place; the cut line. The title
 * line is there from the first frame, so the focus has somewhere to land while the first read runs.
 *
 * The focus: first on the sport chip picked ("All" as the tab opens); on Recent while the cards are blank; on the notice's
 * or the card's button in those states. It moves on from where it was put as the list comes, but never from where the
 * user took it. Down goes into Up next, then the days; Left from a line's first element to the rail. OK on an Up next row
 * plays it, on any other row opens its page; "+3 more" opens in place with the focus on its sixth row. The list keeps the
 * row the focus left for the way back from the rail ([tvFocusMemory]), Back from a page lands on the row that opened it
 * ([rememberReturnFocus], "+3 more" opened or not), and the focus sits a third of the way down as the list moves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvSportsList(screen: ListScreen, model: ListModel, actions: ListActions) {
    val focus = rememberReturnFocus()
    val initial = rememberInitialFocus()
    val rail = LocalRail.current
    val list = rememberLazyListState()
    val memory = remember(focus) { TvListFocus({ key -> focus.requester(key).tryFocus() }, { withFrameNanos {} }) }
    val latest by rememberUpdatedState(screen)

    /** The element [key]: the focus can be put on it, and the list keeps whether it has it. */
    fun element(key: String): Modifier = Modifier.focusRequester(focus.requester(key)).onFocusChanged {
        if (it.isFocused) memory.focused = key else if (memory.focused == key) memory.focused = null
    }

    suspend fun bringIntoView(key: String) = ListKeys.bringIntoView(list, latest, key, sportsLine = false)

    // Back on what opened a page; else the state's first focus, once the rail lets the list take one, and again when the
    // state changes under a focus the list put (the blank cards' Recent as the list comes), never under the user's.
    val first = firstFocus(screen)
    LaunchedEffect(first) { memory.settle(first, { focus.restore(::bringIntoView) }, initial::awaitTurn, ::bringIntoView) }
    // What the list shows changed (the list came, a card took its place): the shell checks the focus is somewhere.
    LaunchedEffect(kind(screen)) { rail?.contentChanged() }
    // "+3 more" opened by OK: the focus on the league's sixth row, the first of those it brought.
    LaunchedEffect(screen) {
        val opening = memory.opening ?: return@LaunchedEffect
        val league = screen.days.flatMap { it.leagues }.firstOrNull { it.key == opening }
        if (league != null && league.more != Words.SportsList.SHOW_FEWER) return@LaunchedEffect
        memory.opening = null
        league?.rows?.getOrNull(Days.SHOWN)?.let { memory.place(ListKeys.row(it.key), ::bringIntoView, own = false) }
    }

    /**
     * A card's or the notice's button. Its card or line may go as it acts (Try again with no list, Upcoming, Show watched):
     * the focus was on it by the list's doing, so the state's first focus follows rather than the focus being lost.
     */
    val perform = { action: ListAction, from: String ->
        if (action == ListAction.Settings) focus.leaving(from) else memory.placed = from
        actions.perform(action, model)
    }

    val pivot = remember { PivotBringIntoViewSpec() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(
            Modifier.fillMaxHeight().tvFullWidth().tvFocusMemory(), state = list, contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = ListKeys.TOP) { TvTop(screen, model, actions, focus, ::element) }
            if (screen.leagues.isNotEmpty()) {
                item(key = ListKeys.LEAGUES) {
                    TvChips(screen.leagues, { ListKeys.league(it.league) }, { it.label }, { it.selected }, ::element, Modifier.padding(top = 8.dp), 16.dp) {
                        model.league(it.league)
                    }
                }
            }
            screen.notice?.let { notice ->
                item(key = ListKeys.NOTICE_ITEM) { TvNotice(notice, { perform(it, ListKeys.NOTICE) }, buttonModifier = element(ListKeys.NOTICE)) }
            }
            if (screen.loading) item(key = ListKeys.LOADING) { Blanks(tv = true) }
            screen.card?.let { card ->
                item(key = ListKeys.CARD_ITEM) { TvStateCard(card, { perform(it, ListKeys.CARD) }, buttonModifier = element(ListKeys.CARD)) }
            }
            if (screen.upNext.isNotEmpty()) {
                item(key = ListKeys.UP_NEXT) {
                    Column {
                        SectionHeader(Words.SportsList.UP_NEXT, top = 20.dp)
                        TvUpNext(screen.upNext, ::element) { row ->
                            focus.leaving(ListKeys.next(row.item.part.key))
                            model.play(row)
                        }
                    }
                }
            }
            screen.days.forEach { day ->
                item(key = ListKeys.day(day)) { SectionHeader(day.header, top = 20.dp) }
                items(day.leagues, key = ListKeys::group) { league ->
                    TvLeagueCard(
                        league, ::element,
                        onOpen = { row ->
                            focus.leaving(ListKeys.row(row.key))
                            actions.open(row)
                        },
                        onMore = {
                            if (league.more != Words.SportsList.SHOW_FEWER) memory.opening = league.key
                            model.toggle(league)
                        },
                    )
                }
            }
            screen.cut?.let { cut -> item(key = ListKeys.CUT) { Footnote(cut) } }
        }
    }
}

/**
 * Where the TV list's focus goes as its states come (L590, L660), by its elements' keys ([ListKeys]): the element focused
 * now ([focused], as its focus events say), the one the list itself put the focus on ([placed]), and a league's day being
 * opened by "+3 more" ([opening]). [request] asks an element for the focus; [frame] waits for the next frame.
 */
@Stable
internal class TvListFocus(private val request: (String) -> Unit, private val frame: suspend () -> Unit) {
    var focused: String? = null
    var placed: String? = null
    var opening: String? = null

    /**
     * The focus on [key], in view first ([bringIntoView]), asked a frame at a time until it takes, as an element may come
     * a frame late. As the list's [own] first focus, counted as the list's from the moment it is asked: a state that comes
     * before the frame that confirms it (the list, while the blank cards' Recent took the focus) cancels this and moves
     * on from it, rather than taking it for the user's.
     */
    suspend fun place(key: String, bringIntoView: suspend (String) -> Unit, own: Boolean = true): Boolean {
        if (own) placed = key
        bringIntoView(key)
        repeat(FOCUS_FRAMES) {
            frame()
            if (focused == key) return true
            request(key)
        }
        return focused == key
    }

    /** Whether the list has put the focus anywhere yet, or brought it back: before, a focus elsewhere is no one's choice. */
    private var settled = false

    /**
     * A state's first focus: back on what opened a page ([restore]); else, once the screen may take one ([awaitTurn]),
     * [first], unless the user took the focus off where the list put it. Before the list has put it anywhere, a focus
     * elsewhere is where the shell's settling left it as the content showed (the list's rows kept from before), not the
     * user's, and [first] takes it.
     */
    suspend fun settle(first: String, restore: suspend () -> Boolean, awaitTurn: suspend () -> Unit, bringIntoView: suspend (String) -> Unit) {
        if (restore()) {
            placed = null
            settled = true
            return
        }
        awaitTurn()
        val now = focused
        if (settled && now != null && now != placed) return
        settled = true
        place(first, bringIntoView)
    }
}

/** The state's first focus: Recent while loading; the notice's or the card's button; else the sport chip picked. */
private fun firstFocus(screen: ListScreen): String = when {
    screen.loading -> ListKeys.side(screen.side)
    screen.notice != null -> ListKeys.NOTICE
    screen.card != null -> ListKeys.CARD
    else -> ListKeys.sport(screen.sports.firstOrNull { it.selected }?.sport)
}

/** What the list shows, as the shell needs to know it changed: loading, a card, or the list. */
private fun kind(screen: ListScreen): String = when {
    screen.loading -> "loading"
    screen.card != null -> "card:${screen.card.title}"
    else -> "list"
}

/** "Sports", Recent | Upcoming, the sport chips and the ⋮, on one line, focusable from the first frame. */
@Composable
private fun TvTop(screen: ListScreen, model: ListModel, actions: ListActions, focus: ReturnFocus, element: (String) -> Modifier) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            Words.Setup.TAB, style = AppType.screenTitle, color = colors.text, maxLines = 1,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() },
        )
        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(colors.surface).padding(3.dp)) {
            listOf(Side.Recent to Words.SportsList.RECENT, Side.Upcoming to Words.SportsList.UPCOMING).forEach { (side, label) ->
                SwitchSide(label, side == screen.side, element(ListKeys.side(side))) { model.side(side) }
            }
        }
        if (screen.sports.isNotEmpty()) {
            Box(Modifier.weight(1f)) {
                TvChips(screen.sports, { ListKeys.sport(it.sport) }, { it.label }, { it.selected }, element, Modifier, CHIP_ROOM) { model.sport(it.sport) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        ListMenu(screen, model, actions, focus) { open -> TvMoreButton(element(ListKeys.MENU), open) }
    }
}

/**
 * A line of the kit's TV chips, sideways, [inset] at its ends, scrolling only as far as the focused one needs, room left
 * for it to grow.
 */
@Composable
private fun <T> TvChips(
    chips: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    selected: (T) -> Boolean,
    element: (String) -> Modifier,
    modifier: Modifier,
    inset: Dp,
    onPick: (T) -> Unit,
) {
    EdgeScrolling(room = CHIP_ROOM) {
        LazyRow(
            modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = inset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chips, key = key) { chip ->
                TvChip(label(chip), selected(chip), element(key(chip)), style = AppType.secondary, role = Role.RadioButton) { onPick(chip) }
            }
        }
    }
}

/** The ⋮ at the title line's end: turned over while focused, as the TV's buttons are. */
@Composable
private fun TvMoreButton(modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Box(
            modifier.size(40.dp).focusScale(interaction, { it.buttonScale }).clip(CircleShape)
                .then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = Words.SportsList.MORE, tint = if (turned) colors.background else colors.text)
        }
    }
}

/** The room past a chip line's ends for a focused chip's growth. */
private val CHIP_ROOM = 4.dp

/** Frames the first focus is asked again for, as an element may come a frame late. */
private const val FOCUS_FRAMES = 10

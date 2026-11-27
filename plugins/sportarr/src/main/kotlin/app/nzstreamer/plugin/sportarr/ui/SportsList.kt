package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nzstreamer.plugin.sportarr.model.Days.Side
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberReturnFocus

/**
 * What the list's elements do beyond its own model: [open] a row's page (L652), the [settings] page, and [refresh] the
 * list now (the ⋮'s Refresh, Try again).
 */
internal class ListActions(val open: (RowView) -> Unit, val settings: () -> Unit, val refresh: () -> Unit) {
    /** A card's or the notice's button. */
    fun perform(action: ListAction, model: ListModel) = when (action) {
        ListAction.Refresh -> refresh()
        ListAction.Settings -> settings()
        ListAction.Recent -> model.side(Side.Recent)
        ListAction.Upcoming -> model.side(Side.Upcoming)
        ListAction.ShowWatched -> model.toggleWatched()
        ListAction.ClearFilters -> model.clearFilters()
    }
}

/**
 * The list's focusable elements by key, for the focus coming back to them ([ReturnFocus]), and the list's items, so a
 * key's item can be brought into view first: an element is composed only while its item is near.
 */
internal object ListKeys {
    const val MENU = "menu"
    const val NOTICE = "notice"
    const val CARD = "card"

    fun side(side: Side) = "side:${side.name}"
    fun sport(sport: String?) = "$SPORT${sport ?: "*"}"
    fun league(league: LeagueChoice?) = "$LEAGUE${league?.id ?: "*"}"
    const val SPORT = "sport:"
    const val LEAGUE = "league:"
    fun next(partKey: String) = "next:$partKey"
    fun row(key: String) = "$ROW$key"
    const val ROW = "row:"
    fun more(leagueKey: String) = "more:$leagueKey"

    /** The list's items. */
    const val TOP = "top"
    const val SPORTS = "sports"
    const val LEAGUES = "leagues"
    const val NOTICE_ITEM = "notice-item"
    const val LOADING = "loading"
    const val CARD_ITEM = "card-item"
    const val UP_NEXT = "up-next"
    const val CUT = "cut"
    fun day(day: DayView) = "day:${day.date}"
    fun group(league: LeagueView) = "group:${league.key}"

    /**
     * [screen]'s items in order: the top line, the sport chips' line ([sportsLine], a phone's; a TV's are on the top line),
     * the leagues', the notice, the blank cards, the card, Up next, each day's header and league cards, the cut line.
     */
    fun items(screen: ListScreen, sportsLine: Boolean): List<String> = buildList {
        add(TOP)
        if (sportsLine && screen.sports.isNotEmpty()) add(SPORTS)
        if (screen.leagues.isNotEmpty()) add(LEAGUES)
        if (screen.notice != null) add(NOTICE_ITEM)
        if (screen.loading) add(LOADING)
        if (screen.card != null) add(CARD_ITEM)
        if (screen.upNext.isNotEmpty()) add(UP_NEXT)
        screen.days.forEach { day ->
            add(day(day))
            day.leagues.forEach { add(group(it)) }
        }
        if (screen.cut != null) add(CUT)
    }

    /** The item [key]'s element is in, or null where it is none of [screen]'s. */
    fun itemOf(screen: ListScreen, key: String, sportsLine: Boolean): String? = when {
        key == MENU || key.startsWith("side:") -> TOP
        key.startsWith("sport:") -> if (sportsLine) SPORTS else TOP
        key.startsWith("league:") -> LEAGUES
        key == NOTICE -> NOTICE_ITEM
        key == CARD -> CARD_ITEM
        key.startsWith("next:") -> UP_NEXT
        key.startsWith("more:") -> "group:" + key.removePrefix("more:")
        key.startsWith("row:") -> screen.days.flatMap { it.leagues }.firstOrNull { league -> league.rows.any { row(it.key) == key } }?.let(::group)
        else -> null
    }

    /**
     * Where [row] (a row's key) stands in [screen]: its day, its league's card and their places, for the way back to its
     * neighbourhood once it has gone ([standIn]); null where it isn't one of [screen]'s. As text, kept with the page.
     */
    fun place(screen: ListScreen, row: String): String? {
        screen.days.forEach { day ->
            day.leagues.forEachIndexed { at, league ->
                val index = league.rows.indexOfFirst { it.key == row }
                if (index >= 0) return listOf(day.date, league.key, at, index).joinToString(PLACE)
            }
        }
        return null
    }

    /**
     * The row now standing where a row that left [screen] stood ([place]): the one in its place in its league's card, or
     * the card's last; else the first of the league's card now in that card's place in the day, or the day's last; else,
     * the day gone, the first of the day the side lists next. Null where none is, or [place] can't be read.
     */
    fun standIn(screen: ListScreen, place: String): String? {
        val parts = place.split(PLACE)
        if (parts.size != 4) return null
        val date = parts[0]
        val leagueAt = parts[2].toIntOrNull() ?: return null
        val rowAt = parts[3].toIntOrNull() ?: return null
        val day = screen.days.firstOrNull { it.date.toString() == date }
        val stands = if (day != null) {
            day.leagues.firstOrNull { it.key == parts[1] }?.rows?.let { it.getOrNull(rowAt) ?: it.lastOrNull() }
                ?: (day.leagues.getOrNull(leagueAt) ?: day.leagues.lastOrNull())?.rows?.firstOrNull()
        } else {
            // Dates as text sort as the days do (yyyy-mm-dd): Recent lists the earlier day next, Upcoming the later.
            val next = screen.days.firstOrNull { if (screen.side == Side.Recent) it.date.toString() < date else it.date.toString() > date }
            (next ?: screen.days.lastOrNull())?.leagues?.firstOrNull()?.rows?.firstOrNull()
        }
        return stands?.let { row(it.key) }
    }

    /** Between a place's parts: no date, league key or number holds it. */
    private const val PLACE = "\t"

    /** Brings [key]'s item into [list]'s view, when it isn't. */
    suspend fun bringIntoView(list: LazyListState, screen: ListScreen, key: String, sportsLine: Boolean) {
        val item = itemOf(screen, key, sportsLine) ?: return
        val index = items(screen, sportsLine).indexOf(item)
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == item }) list.scrollToItem(index)
    }
}

/**
 * The leagues' line's scroll, made afresh for each sport picked, so a sport's leagues open at their start, All leagues
 * picked and in view. A lazy line keeps its place as its chips change, and a lazy list's item gets its state back as it
 * comes again: one state for every sport, or the leagues' item's own through All, would open the next sport's leagues
 * where the last one's were left. So the list keeps it, not the line's item.
 */
@Composable
internal fun rememberLeaguesLine(screen: ListScreen): LazyListState =
    rememberSaveable(screen.sports.firstOrNull { it.selected }?.sport, saver = LazyListState.Saver) { LazyListState() }

/**
 * The Sports list on a phone (L652) and its states: the title line with Recent | Upcoming and the ⋮; the
 * sport chips, then a sport's leagues; the line over a list kept through a failed read; Up next; the days with a card per
 * league; the card in the list's place; the cut line. One list, which scrolls whole. A tap on a row opens its page, on an
 * Up next row plays it, and with a remote the focus comes back to the row that opened a page.
 */
@Composable
internal fun SportsList(screen: ListScreen, model: ListModel, actions: ListActions) {
    val focus = rememberReturnFocus()
    val list = rememberLazyListState()
    val leaguesLine = rememberLeaguesLine(screen)
    val latest by rememberUpdatedState(screen)
    LaunchedEffect(Unit) { focus.restore { key -> ListKeys.bringIntoView(list, latest, key, sportsLine = true) } }
    val element = { key: String -> Modifier.focusRequester(focus.requester(key)) }
    val perform = { action: ListAction, from: String ->
        if (action == ListAction.Settings) focus.leaving(from)
        actions.perform(action, model)
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = ListKeys.TOP) { PhoneTop(screen, model, actions, focus) }
        if (screen.sports.isNotEmpty()) {
            item(key = ListKeys.SPORTS) {
                PhoneChips(screen.sports, { ListKeys.sport(it.sport) }, { it.label }, { it.selected }, element) { model.sport(it.sport) }
            }
        }
        if (screen.leagues.isNotEmpty()) {
            item(key = ListKeys.LEAGUES) {
                PhoneChips(screen.leagues, { ListKeys.league(it.league) }, { it.label }, { it.selected }, element, leaguesLine) { model.league(it.league) }
            }
        }
        screen.notice?.let { notice ->
            item(key = ListKeys.NOTICE_ITEM) { PhoneNotice(notice, { perform(it, ListKeys.NOTICE) }, buttonModifier = element(ListKeys.NOTICE)) }
        }
        if (screen.loading) item(key = ListKeys.LOADING) { Blanks(tv = false) }
        screen.card?.let { card ->
            item(key = ListKeys.CARD_ITEM) { PhoneStateCard(card, { perform(it, ListKeys.CARD) }, buttonModifier = element(ListKeys.CARD)) }
        }
        if (screen.upNext.isNotEmpty()) {
            item(key = ListKeys.UP_NEXT) {
                Column {
                    SectionHeader(Words.SportsList.UP_NEXT, top = 8.dp)
                    PhoneUpNext(screen.upNext, element) { row ->
                        focus.leaving(ListKeys.next(row.item.part.key))
                        model.play(row)
                    }
                }
            }
        }
        screen.days.forEach { day ->
            item(key = ListKeys.day(day)) { SectionHeader(day.header, top = 20.dp) }
            items(day.leagues, key = ListKeys::group) { league ->
                PhoneLeagueCard(
                    league, element,
                    onOpen = { row ->
                        focus.leaving(ListKeys.row(row.key))
                        actions.open(row)
                    },
                    onMore = { model.toggle(league) },
                )
            }
        }
        screen.cut?.let { cut -> item(key = ListKeys.CUT) { Footnote(cut) } }
    }
}

/** The title line, as Discover's: "Sports", Recent | Upcoming, and the ⋮ with Show or Hide watched, Refresh, Sportarr settings. */
@Composable
private fun PhoneTop(screen: ListScreen, model: ListModel, actions: ListActions, focus: ReturnFocus) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(Words.Setup.TAB, style = AppType.screenTitle, color = colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        PhoneSideSwitch(screen.side, focus, model::side)
        ListMenu(screen, model, actions, focus) { open ->
            IconButton(onClick = open, modifier = Modifier.focusRequester(focus.requester(ListKeys.MENU)).focusOutline(CircleShape, fill = false)) {
                Icon(Icons.Filled.MoreVert, contentDescription = Words.SportsList.MORE, tint = colors.text)
            }
        }
    }
}

/**
 * The ⋮'s menu over [button], which opens it: Show watched (Hide watched once they show), Refresh, Sportarr settings. On a
 * TV its first item takes the focus as it opens (the kit's menu).
 */
@Composable
internal fun ListMenu(
    screen: ListScreen,
    model: ListModel,
    actions: ListActions,
    focus: ReturnFocus,
    button: @Composable (open: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        button { expanded = true }
        TvMenu(expanded, onDismiss = { expanded = false }) { first ->
            TvMenuItem(screen.watchedItem, first) {
                expanded = false
                model.toggleWatched()
            }
            TvMenuItem(Words.SportsList.REFRESH) {
                expanded = false
                actions.refresh()
            }
            TvMenuItem(Words.Setup.SETTINGS_TITLE) {
                expanded = false
                focus.leaving(ListKeys.MENU)
                actions.settings()
            }
        }
    }
}

/**
 * Recent | Upcoming, as Discover's phone switch: the side picked tinted in the accent, each side a 48 dp target drawn
 * 32 dp tall, on a track drawn behind them.
 */
@Composable
private fun PhoneSideSwitch(side: Side, focus: ReturnFocus, onSide: (Side) -> Unit) {
    val track = colors.surface
    Row(
        Modifier.selectableGroup().drawBehind {
            val height = TRACK.toPx()
            drawRoundRect(track, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(height / 2))
        }.padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(Side.Recent to Words.SportsList.RECENT, Side.Upcoming to Words.SportsList.UPCOMING).forEach { (s, label) ->
            val on = s == side
            Box(
                Modifier.minimumInteractiveComponentSize().focusRequester(focus.requester(ListKeys.side(s)))
                    .focusOutline(RoundedCornerShape(16.dp), fill = false).selectable(on, role = Role.Tab) { onSide(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = if (on) AppType.bodyStrong else AppType.body, color = if (on) colors.accent else colors.textSecondary, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentTint else Color.Transparent)
                        .heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * A line of chips, sideways, as Discover's genres: the one picked tinted in the accent, each a 48 dp target drawn 33 dp
 * tall; its scroll [state] the line's own unless the list keeps it.
 */
@Composable
private fun <T> PhoneChips(
    chips: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    selected: (T) -> Boolean,
    element: (String) -> Modifier,
    state: LazyListState = rememberLazyListState(),
    onPick: (T) -> Unit,
) {
    LazyRow(
        state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(chips, key = key) { chip ->
            val on = selected(chip)
            val shape = RoundedCornerShape(16.dp)
            Box(
                element(key(chip)).heightIn(min = 48.dp).focusOutline(shape, fill = false).selectable(on, role = Role.RadioButton) { onPick(chip) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(chip), style = if (on) AppType.bodyStrong.copy(fontSize = AppType.secondary.fontSize) else AppType.secondary,
                    color = if (on) colors.accent else colors.textSecondary, maxLines = 1,
                    modifier = Modifier.heightIn(min = 33.dp).clip(shape)
                        .then(if (on) Modifier.background(colors.accentTint) else Modifier.border(1.dp, colors.chip, shape))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** The switch's track: 3 dp round its 32 dp sides. */
private val TRACK = 38.dp

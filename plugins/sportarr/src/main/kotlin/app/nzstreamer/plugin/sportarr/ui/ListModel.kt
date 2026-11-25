package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.Days
import app.nzstreamer.plugin.sportarr.model.Days.Side
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Item
import app.nzstreamer.plugin.sportarr.model.League
import app.nzstreamer.plugin.sportarr.model.Listed
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UpNext
import app.nzstreamer.plugin.sportarr.model.Watched
import app.nzstreamer.plugin.sportarr.model.Weekend
import app.nzstreamer.plugin.sportarr.source.ListState
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** The plugin's pages a row opens (L652): an event's, with the event's key, and a weekend's, with the weekend's key. */
internal object Pages {
    const val EVENT = "event"
    const val WEEKEND = "weekend"
}

/** What a state card's or the notice's one button does. */
internal enum class ListAction { Refresh, Settings, Recent, Upcoming, ShowWatched, ClearFilters }

/**
 * What the Sports list shows (L652), all of it, phone and TV alike: the [side]; the blank cards while the first read runs
 * ([loading]); the ⋮'s first item ([watchedItem]); the sport chips, and a sport's league chips once one is picked; the
 * line over a list kept through a failed read ([notice]); Up next; the days; the card in the list's place, if any; and
 * the cut line at the end of Recent.
 */
internal data class ListScreen(
    val side: Side,
    val loading: Boolean,
    val watchedItem: String,
    val sports: List<SportChip>,
    val leagues: List<LeagueChip>,
    val notice: Notice?,
    val upNext: List<StripRow>,
    val days: List<DayView>,
    val card: StateCard?,
    val cut: String?,
)

/** A sport's chip: its [label] as Sportarr names it, the [sport] it picks (null: All). */
internal data class SportChip(val label: String, val sport: String?, val selected: Boolean)

/** A league's chip, once a sport is picked: the [league] it picks (null: All leagues). */
internal data class LeagueChip(val label: String, val league: LeagueChoice?, val selected: Boolean)

/** The line over a list kept through a failed read, with its age, and its one button. */
internal data class Notice(val line: String, val button: String, val action: ListAction)

/** A card in the list's place: its [title], its [line], its one [button], and a [foot] under it. */
internal data class StateCard(val title: String, val line: String?, val button: String, val action: ListAction, val foot: String?)

/**
 * A row of the tab's Up next: what it plays ([item]), its [title] as the play stores it ("Grand Prix of Kestrel · Race"),
 * its [line] (the league, a card's or weekend's count, what is left), how far it was played ([progress]); the first row's
 * Play is the [primary] one.
 */
internal data class StripRow(val item: Item, val title: String, val line: String, val progress: Float?, val primary: Boolean)

/** One day of a side: its [header] ("Today", "Fri 20 Nov") and a card per league. */
internal data class DayView(val date: LocalDate, val header: String, val leagues: List<LeagueView>)

/**
 * One league's card on one day ([key], which "+3 more" opens by): its [name] as the card's small heading, the [rows]
 * shown, and the line after them: "+3 more", "Show fewer" once opened, or none.
 */
internal data class LeagueView(val key: String, val name: String, val rows: List<RowView>, val more: String?)

/**
 * An event's or weekend's row: the [page] it opens with its [key]; its start [time]; its [title] as Sportarr gives it; a
 * small [line] under it (a weekend's round and count, "You pick the release"); what is [left] of a play begun, and how
 * far it was played ([progress]); a card's [parts]; the tick of a [watched] one; and whether it is still to start
 * ([upcoming]), drawn a little quieter.
 */
internal data class RowView(
    val key: String,
    val page: String,
    val time: String,
    val title: String,
    val line: String?,
    val left: String?,
    val progress: Float?,
    val parts: List<PartLabel>,
    val watched: Boolean,
    val upcoming: Boolean,
)

/** A card's part as a small label in card order, ticked once watched. */
internal data class PartLabel(val label: String, val watched: Boolean)

/** The list a state holds: the one read, or the one kept through a failed or refused read; none while there is none. */
internal val ListState.sports: Sports?
    get() = when (this) {
        is ListState.Ready -> sports
        is ListState.Unreachable -> sports
        is ListState.Refused -> sports
        ListState.Loading, ListState.NotSetUp, ListState.NothingFollowed -> null
    }

/**
 * The list as data (L641, L644, L652), built from the refresh's [ListState], the side, the view's choices, the days opened
 * past their first five, the host's watch states, the time now and the device's zone; [tv] where its words say "this TV".
 * It reads no clock and searches nothing.
 *
 * - The chips: All, then each sport as Sportarr names it, by how many rows it has here, then A to Z; once one is picked,
 *   All leagues and its leagues the same way. A choice kept from before whose sport or league isn't listed now keeps its
 *   chip, picked, so the card below says why nothing shows.
 * - Up next on Recent alone, with every sport (L644's strip, three rows at most).
 * - The days, Today first on both sides; inside a day the leagues by the sport chips' order, then by name, a league with
 *   no sport last; five rows a league a day, a weekend or a card one each, then "+N more", which opens the rest in place
 *   with "Show fewer" at its end. Watched rows hidden until Show watched, then ticked in their place.
 * - The states, each with one way on: loading; a list kept through a failed or refused read, with its age; no list kept
 *   and none read; nothing followed; nothing on the side; everything watched; nothing matching the chips.
 */
internal object ListScreens {
    fun of(
        state: ListState,
        side: Side,
        choices: ViewChoices,
        expanded: Set<String>,
        watch: Map<String, WatchInfo>,
        now: Instant,
        zone: ZoneId,
        tv: Boolean,
    ): ListScreen {
        val words = Words.SportsList
        val empty = ListScreen(
            side, loading = false, watchedItem = if (choices.showWatched) words.HIDE_WATCHED else words.SHOW_WATCHED,
            sports = emptyList(), leagues = emptyList(), notice = null, upNext = emptyList(), days = emptyList(), card = null, cut = null,
        )
        val states = Words.ListStates
        val sports = state.sports
        val notice = when (state) {
            ListState.NotSetUp, ListState.Loading -> return empty.copy(loading = true)
            ListState.NothingFollowed -> return empty.copy(card = nothingFollowed())
            is ListState.Ready -> null
            is ListState.Unreachable -> {
                if (sports == null) {
                    return empty.copy(card = StateCard(states.CANT_REACH, states.cantReachWhy(state.why, tv), states.TRY_AGAIN, ListAction.Refresh, null))
                }
                Notice(states.cantReach(Times.ago(sports.readAt, now)), states.TRY_AGAIN, ListAction.Refresh)
            }
            is ListState.Refused -> {
                if (sports == null) return empty.copy(card = StateCard(states.REFUSED, null, Words.Setup.SETTINGS_TITLE, ListAction.Settings, null))
                Notice(states.refused(Times.ago(sports.readAt, now)), Words.Setup.SETTINGS_TITLE, ListAction.Settings)
            }
        }
        if (sports == null || sports.leagues.isEmpty()) return empty.copy(notice = notice, card = nothingFollowed())
        return listed(empty.copy(notice = notice), sports, choices, expanded, watch, now, zone)
    }

    /**
     * Up next's title for [item] of [listed], as its play stores it: a weekend's session after the weekend's name ("Grand
     * Prix of Kestrel · Race"), a card's part after the card's ("UFC 999: Quessel vs. Halde · Main Card"), else the
     * event's title.
     */
    fun title(listed: Listed, item: Item): String = when {
        listed is Weekend -> Words.line(listed.title, item.part.label)
        item.event.parts.size > 1 -> Words.line(item.event.title, item.part.label)
        else -> item.event.title
    }

    private fun nothingFollowed(): StateCard {
        val states = Words.ListStates
        return StateCard(states.NOTHING_FOLLOWED, states.NOTHING_FOLLOWED_LINE, Words.SportsList.REFRESH, ListAction.Refresh, states.NO_LEAGUE)
    }

    /** [screen] filled from a list: its chips, Up next, days, card and cut line. */
    private fun listed(
        screen: ListScreen,
        sports: Sports,
        choices: ViewChoices,
        expanded: Set<String>,
        watch: Map<String, WatchInfo>,
        now: Instant,
        zone: ZoneId,
    ): ListScreen {
        val side = screen.side
        val ranked = ranked(sports)
        val sport = choices.sport?.let(::fold)?.takeIf { it.isNotEmpty() }
        // A league belongs to the sport picked with it: with every sport, no league is.
        val league = choices.league?.takeIf { sport != null }
        val sportChips = listOf(SportChip(Words.SportsList.ALL, null, sport == null)) +
            ranked.map { SportChip(it.label, it.label, it.key == sport) } +
            listOfNotNull(choices.sport?.takeIf { sport != null && ranked.none { it.key == sport } }?.let { SportChip(it, it, true) })
        val leagueChips = if (sport == null) emptyList() else {
            val rows = sports.listed.groupingBy { it.league.id }.eachCount()
            val own = sports.leagues.filter { fold(it.sport) == sport }
                .sortedWith(compareByDescending<League> { rows[it.id] ?: 0 }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            listOf(LeagueChip(Words.SportsList.ALL_LEAGUES, null, league == null)) +
                own.map { LeagueChip(it.name, LeagueChoice(it.id, it.name), it.id == league?.id) } +
                listOfNotNull(league?.takeIf { picked -> own.none { it.id == picked.id } }?.let { LeagueChip(it.name, it, true) })
        }

        val rank = ranked.withIndex().associate { (index, sport) -> sport.key to index }
        val order = compareBy<League> { rank[fold(it.sport)] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        val chosen = sports.listed.filter { (sport == null || fold(it.league.sport) == sport) && (league == null || it.league.id == league.id) }
        val shown = if (choices.showWatched) chosen else chosen.filterNot { Watched.listed(it, watch) }
        val days = Days.of(shown, side, now, zone, order)
        val today = Times.date(now, zone)

        val card = if (days.isNotEmpty()) null else when {
            // Whether the side has anything for these chips, watched or not, tells what the card says.
            Days.of(chosen, side, now, zone, order).isNotEmpty() ->
                StateCard(Words.ListStates.ALL_WATCHED, null, Words.SportsList.SHOW_WATCHED, ListAction.ShowWatched, null)
            sport != null -> StateCard(Words.ListStates.NOTHING_MATCHES, null, Words.ListStates.CLEAR_FILTERS, ListAction.ClearFilters, null)
            side == Side.Recent -> {
                val upcoming = Days.of(shown, Side.Upcoming, now, zone, order).sumOf { day -> day.leagues.sumOf { it.rows.size } }
                val since = Times.shortDay(today.minusDays(Days.DAYS_BACK), today)
                val line = Words.ListStates.nothingRecent(since, upcoming)
                StateCard(Words.ListStates.NOTHING_RECENT, line, Words.SportsList.UPCOMING, ListAction.Upcoming, null)
            }
            else -> StateCard(Words.ListStates.NOTHING_UPCOMING, null, Words.SportsList.RECENT, ListAction.Recent, null)
        }
        // Up next goes with Recent and every sport: with a sport picked, its days show alone.
        val upNext = if (side == Side.Recent && sport == null) {
            Watched.upNext(sports.listed, watch, now).mapIndexed { index, next -> strip(next, watch, now, index == 0) }
        } else {
            emptyList()
        }
        return screen.copy(
            sports = sportChips,
            leagues = leagueChips,
            upNext = upNext,
            days = days.map { day -> day(day, side, expanded, watch, now, zone, today) },
            card = card,
            cut = Words.ListStates.CUT.takeIf { side == Side.Recent && sports.cut },
        )
    }

    /** A sport as the chips have it: its [key], folded, its [label] as Sportarr first names it, and its rows here. */
    private class RankedSport(val key: String, val label: String, val rows: Int)

    /**
     * The sports of the list's leagues, by how many rows each has here (a weekend or a card one), then A to Z; a league
     * with no sport has no chip.
     */
    private fun ranked(sports: Sports): List<RankedSport> {
        val rows = sports.listed.groupingBy { fold(it.league.sport) }.eachCount()
        return sports.leagues.mapNotNull { league -> league.sport?.trim()?.takeIf { it.isNotEmpty() } }.distinctBy(::fold)
            .map { RankedSport(fold(it), it, rows[fold(it)] ?: 0) }
            .sortedWith(compareByDescending<RankedSport> { it.rows }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    private fun strip(next: UpNext, watch: Map<String, WatchInfo>, now: Instant, primary: Boolean): StripRow {
        val listed = next.listed
        val count = if (listed.items.size > 1) Words.SportsList.watched(Watched.count(listed, watch, now)) else null
        val left = next.info?.let { Times.upNextLeft(it.positionMs, it.durationMs) }
        return StripRow(next.item, title(listed, next.item), Words.line(listed.league.name, count, left), next.info?.let(::progress), primary)
    }

    private fun day(
        day: Days.Day,
        side: Side,
        expanded: Set<String>,
        watch: Map<String, WatchInfo>,
        now: Instant,
        zone: ZoneId,
        today: LocalDate,
    ): DayView = DayView(
        day.date,
        Times.dayHeader(day.date, today),
        day.leagues.map { league ->
            val key = leagueKey(day.date, league.league)
            val open = key in expanded && league.rest.isNotEmpty()
            val rows = if (open) league.rows else league.first
            val more = when {
                league.rest.isEmpty() -> null
                open -> Words.SportsList.SHOW_FEWER
                else -> Words.SportsList.more(league.rest.size)
            }
            LeagueView(key, league.league.name, rows.map { row(it, side, watch, now, zone) }, more)
        },
    )

    private fun row(row: Days.DayRow, side: Side, watch: Map<String, WatchInfo>, now: Instant, zone: ZoneId): RowView {
        val at = row.at
        val time = Times.time(at.start, zone)
        val upcoming = at.start.isAfter(now)
        val watched = Watched.listed(row.listed, watch)
        return when (val listed = row.listed) {
            is Weekend -> {
                // A session in progress leads what is left; the last played of them, should there be several.
                val playing = listed.items.mapNotNull { item -> watch[item.part.key]?.takeIf(Watched::inProgress)?.let { item to it } }
                    .maxByOrNull { it.second.lastPlayedAt ?: Instant.MIN }
                val count = Watched.count(listed, watch, now)
                val counted = if (count.watched > 0) Words.SportsList.watched(count) else Words.SportsList.sessions(listed.sessions.size)
                val session = at.session?.label ?: at.title
                val dated = when {
                    playing != null -> null
                    side == Side.Upcoming && upcoming -> Words.SportsList.first(session)
                    else -> session
                }
                val left = playing?.let { (item, info) -> left(info)?.let { Words.line(item.part.label, it) } }
                RowView(
                    listed.key, Pages.WEEKEND, time, listed.title, Words.line(listed.roundNumber?.let(Words.SportsList::round), dated, counted),
                    left, playing?.second?.takeIf { left != null }?.let(::progress), emptyList(), watched, upcoming,
                )
            }
            is SportsEvent -> {
                val parts = if (listed.parts.size > 1) listed.parts.map { PartLabel(it.label, Watched.part(it, watch)) } else emptyList()
                val playing = listed.parts.singleOrNull()?.let { watch[it.key] }?.takeIf(Watched::inProgress)
                val left = playing?.let(::left)
                RowView(
                    listed.key, Pages.EVENT, time, listed.title, Words.SportsList.YOU_PICK.takeIf { listed.family == Family.Generic },
                    left, playing?.takeIf { left != null }?.let(::progress), parts, watched, upcoming,
                )
            }
        }
    }

    /** What is left of a play on a row, where its length is known. */
    private fun left(info: WatchInfo): String? = if (info.durationMs > 0) Times.rowLeft(info.positionMs, info.durationMs) else null

    private fun progress(info: WatchInfo): Float? =
        if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else null

    /** A league's card on [date]: what "+3 more" opens by, kept with the page as text. */
    private fun leagueKey(date: LocalDate, league: League): String = "$date|${league.id}"

    /** A sport's words as they compare: trimmed and in lower case, so "Soccer" and "soccer" are one sport. */
    private fun fold(sport: String?): String = sport.orEmpty().trim().lowercase(Locale.ROOT)
}

/**
 * The list's model on a phone or a [tv]: the refresh's [state] as a [ListScreen], with the host's [watch] states of every
 * part listed, the view's choices kept in view.json ([kept], L645), the side and the days opened kept with the page
 * ([Restored]), and the time from [clock] in [zone]'s days. It searches nothing: a tap on Up next hands its part to
 * [onPlay], with the list, and the host searches only as it plays.
 *
 * [run] does its work while the list shows: it reads view.json once, follows the state, the watch states and the view,
 * builds each screen on [work], off the main thread (L641), and writes the choices as they change. The time moves on by
 * [tick].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ListModel(
    private val state: StateFlow<ListState>,
    private val watch: PluginWatch,
    private val kept: KeptView,
    private val tv: Boolean,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val onPlay: (Item, Sports) -> Unit,
    restored: Restored?,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    /**
     * What the page keeps across Back and its activity being made again, as strings alone (NzPlugin's rule): the [side],
     * and the days opened past their first five, comma-separated.
     */
    data class Restored(val side: String, val expanded: String)

    private data class View(val side: Side, val choices: ViewChoices, val expanded: Set<String>, val now: Instant)

    private val view = MutableStateFlow(
        View(
            side = Side.entries.firstOrNull { it.name == restored?.side } ?: Side.Recent,
            choices = ViewChoices(),
            expanded = restored?.expanded.orEmpty().split(',').filter(String::isNotEmpty).toSet(),
            now = clock(),
        ),
    )

    /** Whether view.json was read: once a model, however often [run] starts again. */
    private var read = false

    private val shown = MutableStateFlow<ListScreen?>(null)

    /** The list's screen, null until the first is built. */
    val screen: StateFlow<ListScreen?> = shown.asStateFlow()

    /** The list the screen shows, for a play from Up next. */
    @Volatile
    private var sports: Sports? = null

    suspend fun run(): Unit = coroutineScope {
        if (!read) {
            kept.read()?.let { stored -> view.update { it.copy(choices = stored) } }
            read = true
        }
        view.update { it.copy(now = clock()) }
        // The choices as they change, the first being what the file holds; each written whole, even as the page leaves.
        launch { view.map { it.choices }.distinctUntilChanged().drop(1).collectLatest { withContext(NonCancellable) { kept.write(it) } } }
        state.flatMapLatest { listState ->
            val listed = listState.sports ?: return@flatMapLatest flowOf(listState to emptyMap())
            val keys = withContext(work) { listed.events.flatMap { event -> event.parts.map { it.key } } }
            watch.observe(keys).map { listState to it }
        }.combine(view) { (listState, states), view -> Triple(listState, states, view) }
            .collectLatest { (listState, states, view) ->
                val built = withContext(work) { ListScreens.of(listState, view.side, view.choices, view.expanded, states, view.now, zone(), tv) }
                sports = listState.sports
                shown.value = built
            }
    }

    fun side(side: Side) = view.update { it.copy(side = side) }

    /** Picks [sport] (null: All), every league of it. */
    fun sport(sport: String?) = view.update { it.copy(choices = it.choices.copy(sport = sport, league = null)) }

    /** Picks [league] of the sport picked (null: All leagues). */
    fun league(league: LeagueChoice?) = view.update { it.copy(choices = it.choices.copy(league = league)) }

    fun clearFilters() = view.update { it.copy(choices = it.choices.copy(sport = null, league = null)) }

    fun toggleWatched() = view.update { it.copy(choices = it.choices.copy(showWatched = !it.choices.showWatched)) }

    /** "+3 more" opens [league]'s day in place; "Show fewer" folds it again. */
    fun toggle(league: LeagueView) = view.update {
        it.copy(expanded = if (league.key in it.expanded) it.expanded - league.key else it.expanded + league.key)
    }

    /** The time moves on: rows start, and Today turns into Yesterday. */
    fun tick() = view.update { it.copy(now = clock()) }

    /** Plays Up next's [row]: its part, with the list it was shown from. */
    fun play(row: StripRow) {
        sports?.let { onPlay(row.item, it) }
    }

    /** What the page keeps for this model's successor. */
    fun kept(): Restored = view.value.let { Restored(it.side.name, it.expanded.joinToString(",")) }
}

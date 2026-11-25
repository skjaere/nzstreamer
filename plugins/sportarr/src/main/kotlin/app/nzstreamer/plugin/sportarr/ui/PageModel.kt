package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Item
import app.nzstreamer.plugin.sportarr.model.Listed
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.Watched
import app.nzstreamer.plugin.sportarr.model.Weekend
import app.nzstreamer.plugin.sportarr.source.ListState
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/**
 * How a page draws (L652): a game's, the Movie page's look ([Game]: a team game, and a card of one part, a boxing matchup
 * or a weekly show); a sport's the plugin has no rules for ([Generic], the game's page with Choose release in Play's
 * place); a motorsport weekend's, one row per session ([Weekend]); a fight card's, one row per part ([Card]).
 */
internal enum class PageKind { Game, Generic, Weekend, Card }

/** A line's colour: muted, the secondary text's, the accent's (a play begun) or the green of a watched one. */
internal enum class Tone { Muted, Secondary, Accent, Good }

/** A line under a page's title, with how far a play went or how much is watched where it has a bar ([progress]). */
internal data class StatusLine(val text: String, val tone: Tone, val progress: Float? = null)

/** What a page's button does: plays the part, Choose release for it, or Search indexers. */
internal enum class PageAction { Play, Choose, Search }

/**
 * One of a page's buttons: its [label], the [part] it acts for (its key), what it does ([action]), its focus [key]
 * ([PageKeys]), and whether it is the [primary] one, the amber button at the top.
 */
internal data class PageButton(val label: String, val part: String, val action: PageAction, val key: String, val primary: Boolean)

/** A one-part page's Mark watched or Mark unwatched ([label]): marks [part] [watched] or not. */
internal data class MarkButton(val label: String, val part: String, val watched: Boolean)

/** What an item of a part's ⋮ does. */
internal enum class MenuAction { Choose, Search, MarkWatched, MarkUnwatched }

internal data class MenuEntry(val label: String, val action: MenuAction)

/**
 * A weekend's session or a card's part, a row of its page: its [part]'s key and [label]; a session's day and time, or when
 * it starts ([meta]; none for a card's part); what is [left] of a play begun and how far it went ([progress]); the ✓ of a
 * [watched] one. Only one [started] plays and has its ⋮ ([menu]). The one the top button plays is [current], which a TV
 * marks; [searching] while Choose release runs for it from its ⋮.
 */
internal data class PartRow(
    val part: String,
    val label: String,
    val meta: String?,
    val left: String?,
    val progress: Float?,
    val watched: Boolean,
    val started: Boolean,
    val current: Boolean,
    val searching: Boolean,
    val menu: List<MenuEntry>,
)

/** What a page shows. */
internal sealed interface PageScreen

/**
 * An event's or a weekend's page, phone and TV alike but where a TV's words or buttons differ: its [title] as Sportarr
 * gives it; its [meta] (the league and when, or a weekend's round and days); a [status] line (not watched, what is left, ✓
 * Watched, when it starts); a card's or weekend's [count] with its bar; Generic's line that the user picks ([pick]); the
 * [buttons], the primary first; the line in the buttons' place before the start ([note], which holds a TV's focus); the
 * line kept after Play or Choose release found nothing, or why Choose release found nothing where no indexer answered
 * ([kept]); a one-part page's [mark]; a card's or weekend's [parts];
 * the [footnote]; and a TV's [firstFocus], one of [PageKeys].
 */
internal data class EventScreen(
    val kind: PageKind,
    val title: String,
    val meta: String,
    val status: StatusLine?,
    val count: StatusLine?,
    val pick: String?,
    val buttons: List<PageButton>,
    val mark: MarkButton?,
    val note: String?,
    val kept: String?,
    val parts: List<PartRow>,
    val footnote: String,
    val firstFocus: String,
) : PageScreen

/** A page whose event or weekend left the list after a read (L652): the [line] that says so, and [back]. */
internal data class GoneScreen(val line: String, val back: String, val firstFocus: String) : PageScreen

/**
 * A page's focusable elements by key, for its first focus on a TV and for the focus coming back to what opened Choose
 * release, Search, a play or the settings ([io.skjaere.usenet.app.ui.ReturnFocus]).
 */
internal object PageKeys {
    /** The button that plays: Play, Resume, Play again, a weekend's or card's top button. */
    const val PLAY = "play"
    const val CHOOSE = "choose"
    const val SEARCH = "search"
    const val MARK = "mark"

    /** The line that holds a TV's focus while nothing plays yet. */
    const val HELD = "held"
    const val BACK = "back"

    fun row(part: String) = "row:$part"
    fun more(part: String) = "more:$part"
}

/**
 * The event pages as data (L644, L647, L652, L653), built from the refresh's [ListState], the page and its key, the host's
 * watch states of its parts, when Play or Choose release last found nothing for them, the part Choose release is
 * searching for, the time now and the device's zone; [tv] where its words or buttons differ; and what Choose release
 * last said of a part ([unanswered]) where no indexer answered it. It reads no clock and searches nothing.
 *
 * - A one-part page: before the start, the league, when it starts and the line in the buttons' place; then Play (Resume
 *   while played, Play again once watched), Choose release, Search indexers, and Mark watched or unwatched. Generic's has
 *   Choose release in Play's place, Resume while played, and says the user picks.
 * - A weekend's or card's page: the count of parts, or sessions begun, watched; the top button, Resume for the part played
 *   last, else Play for the next one begun after the last one watched (a part skipped never comes back), none once the
 *   race or main card is watched; on a TV Choose release for that part beside it; a row per part, those begun with their
 *   ⋮. Before the first part starts, when it starts and the line in the buttons' place.
 * - A page whose event or weekend left the list says so; none while the list loads.
 */
internal object PageScreens {
    fun of(
        state: ListState,
        page: String,
        key: String,
        watch: Map<String, WatchInfo>,
        nothing: Map<String, Instant>,
        searching: String?,
        now: Instant,
        zone: ZoneId,
        tv: Boolean,
        unanswered: Pair<String, String>? = null,
    ): PageScreen? {
        if (state == ListState.Loading) return null
        val sports = state.sports ?: return GONE
        val listed = listed(sports, page, key) ?: return GONE
        val screen = if (listed is SportsEvent && listed.parts.size == 1) {
            onePart(listed, watch, nothing, searching, now, zone)
        } else {
            parts(listed, watch, nothing, searching, now, zone, tv)
        }
        // Why Choose release found nothing, where no indexer answered, in the kept line's place while the page shows.
        val why = unanswered?.takeIf { (part, _) -> listed.items.any { it.part.key == part } }?.second ?: return screen
        return screen.copy(kept = why)
    }

    /** What [page] [key] shows of [sports]: an event, or a weekend, a session's own page being its weekend's; null once gone. */
    fun listed(sports: Sports, page: String, key: String): Listed? = when (page) {
        Pages.WEEKEND -> sports.weekend(key)
        else -> sports.event(key)?.let { sports.weekendOf(it) ?: it }
    }

    private val GONE = GoneScreen(Words.Game.GONE, Words.Game.BACK, PageKeys.BACK)

    private fun onePart(
        event: SportsEvent,
        watch: Map<String, WatchInfo>,
        nothing: Map<String, Instant>,
        searching: String?,
        now: Instant,
        zone: ZoneId,
    ): EventScreen {
        val part = event.parts.single()
        val generic = event.family == Family.Generic
        val kind = if (generic) PageKind.Generic else PageKind.Game
        val footnote = when (event.family) {
            Family.Generic -> Words.Generic.FOOTNOTE
            Family.TeamGame -> Words.Game.FOOTNOTE
            else -> Words.Game.EVENT_FOOTNOTE
        }
        val pick = Words.SportsList.YOU_PICK.takeIf { generic }
        if (event.start.isAfter(now)) {
            val note = if (generic) Words.Generic.NOT_STARTED else Words.Game.NOT_STARTED
            return EventScreen(
                kind, event.title, event.league.name, StatusLine(Times.starts(event.start, now, zone), Tone.Muted), null, pick, emptyList(), null,
                note, null, emptyList(), footnote, PageKeys.HELD,
            )
        }
        val watched = Watched.part(part, watch)
        val playing = watch[part.key]?.takeIf(Watched::inProgress)
        val left = playing?.let { Times.pageLeft(it.positionMs, it.durationMs) }
        val status = when {
            watched -> StatusLine(Words.Game.WATCHED, Tone.Good)
            left != null -> StatusLine(left, Tone.Accent, playing?.let(::progress))
            else -> StatusLine(Words.Game.NOT_WATCHED, Tone.Muted)
        }
        val choose = PageButton(chooseLabel(part, searching), part.key, PageAction.Choose, PageKeys.CHOOSE, primary = false)
        val search = PageButton(Words.Game.SEARCH_INDEXERS, part.key, PageAction.Search, PageKeys.SEARCH, primary = false)
        val play = when {
            watched && !generic -> Words.Game.PLAY_AGAIN
            playing != null -> Words.Game.RESUME
            !generic -> Words.Game.PLAY
            else -> null
        }
        val buttons = if (play != null) {
            listOf(PageButton(play, part.key, PageAction.Play, PageKeys.PLAY, primary = true), choose, search)
        } else {
            listOf(choose.copy(primary = true), search)
        }
        val mark = MarkButton(if (watched) Words.Game.MARK_UNWATCHED else Words.Game.MARK_WATCHED, part.key, watched = !watched)
        return EventScreen(
            kind, event.title, Times.meta(event.league.name, event.start, now, zone), status, null, pick, buttons, mark, null,
            kept(listOf(Item(event, part)), watch, nothing, zone), emptyList(), footnote, buttons.first().key,
        )
    }

    private fun parts(
        listed: Listed,
        watch: Map<String, WatchInfo>,
        nothing: Map<String, Instant>,
        searching: String?,
        now: Instant,
        zone: ZoneId,
        tv: Boolean,
    ): EventScreen {
        val weekend = listed as? Weekend
        val items = listed.items
        val begun = items.filter { !it.event.start.isAfter(now) }
        val first = listed.events.first()
        val league = listed.league.name
        val title = weekend?.title ?: first.title
        val meta = when {
            weekend != null -> Times.weekendMeta(league, weekend.round, weekend.roundNumber, first.start, listed.events.last().start, now, zone)
            begun.isEmpty() -> league
            else -> Times.meta(league, first.start, now, zone)
        }
        val done = Watched.listed(listed, watch)
        val status = when {
            begun.isEmpty() -> StatusLine(Times.starts(first.start, now, zone), Tone.Muted)
            done -> StatusLine(Words.Game.WATCHED, Tone.Good)
            else -> null
        }
        val count = if (begun.isEmpty()) {
            null
        } else {
            Watched.count(listed, watch, now).let { StatusLine(Words.SportsList.watched(it), Tone.Secondary, if (it.of > 0) it.watched.toFloat() / it.of else null) }
        }
        val top = if (done || begun.isEmpty()) null else top(items, watch, now)
        val buttons = top?.let { (item, label) ->
            listOfNotNull(
                PageButton(label, item.part.key, PageAction.Play, PageKeys.PLAY, primary = true),
                // A TV's left pane holds Choose release for the part the top button plays; a phone's is in each row's ⋮.
                PageButton(chooseLabel(item.part, searching), item.part.key, PageAction.Choose, PageKeys.CHOOSE, primary = false).takeIf { tv },
            )
        }.orEmpty()
        val rows = items.map { item -> row(item, session = weekend != null, watch, searching, now, zone, current = item == top?.first) }
        val note = when {
            begun.isNotEmpty() -> null
            weekend != null -> Words.Weekend.NOT_STARTED
            else -> Words.Card.NOT_STARTED
        }
        val footnote = when {
            weekend != null -> if (tv) Words.Weekend.TV_FOOTNOTE else Words.Weekend.FOOTNOTE
            else -> if (tv) Words.Card.TV_FOOTNOTE else Words.Card.FOOTNOTE
        }
        // A TV's first focus: the top button, else the first part begun and not watched, else the first begun, else the line.
        val firstFocus = buttons.firstOrNull()?.key
            ?: (begun.firstOrNull { !Watched.part(it.part, watch) } ?: begun.firstOrNull())?.let { PageKeys.row(it.part.key) }
            ?: PageKeys.HELD
        return EventScreen(
            if (weekend != null) PageKind.Weekend else PageKind.Card, title, meta, status, count, null, buttons, null, note,
            kept(items, watch, nothing, zone), rows, footnote, firstFocus,
        )
    }

    /**
     * The top button's part and words: Resume for the part begun and played last, where it stopped ("Resume Qualifying ·
     * 18:22"); else Play for the first part after the last one watched that has begun and isn't watched ("Play Race"), as a
     * show's page goes on to the next episode; null where none has.
     */
    private fun top(items: List<Item>, watch: Map<String, WatchInfo>, now: Instant): Pair<Item, String>? {
        val playing = items.mapNotNull { item -> watch[item.part.key]?.takeIf { Watched.inProgress(it) && !item.event.start.isAfter(now) }?.let { item to it } }
            .maxByOrNull { it.second.lastPlayedAt ?: Instant.MIN }
        if (playing != null) return playing.first to Times.resume(playing.first.part.label, playing.second.positionMs)
        val last = items.indexOfLast { Watched.part(it.part, watch) }
        val next = items.drop(last + 1).firstOrNull { !it.event.start.isAfter(now) && !Watched.part(it.part, watch) } ?: return null
        return next to Words.SportsList.play(next.part.label)
    }

    private fun row(
        item: Item,
        session: Boolean,
        watch: Map<String, WatchInfo>,
        searching: String?,
        now: Instant,
        zone: ZoneId,
        current: Boolean,
    ): PartRow {
        val start = item.event.start
        val started = !start.isAfter(now)
        val watched = Watched.part(item.part, watch)
        val playing = watch[item.part.key]?.takeIf { Watched.inProgress(it) && it.durationMs > 0 }
        val meta = when {
            !session -> null
            started -> Times.dayAndTime(start, now, zone)
            else -> Times.starts(start, now, zone)
        }
        val menu = if (!started) {
            emptyList()
        } else {
            listOf(
                MenuEntry(Words.Game.CHOOSE_RELEASE, MenuAction.Choose),
                MenuEntry(Words.Game.SEARCH_INDEXERS, MenuAction.Search),
                if (watched) MenuEntry(Words.Game.MARK_UNWATCHED, MenuAction.MarkUnwatched) else MenuEntry(Words.Game.MARK_WATCHED, MenuAction.MarkWatched),
            )
        }
        return PartRow(
            item.part.key, item.part.label, meta, playing?.let { Times.rowLeft(it.positionMs, it.durationMs) }, playing?.let(::progress), watched,
            started, current, searching == item.part.key, menu,
        )
    }

    /** Choose release's words: "Searching your indexers…" while it runs for [part]. */
    private fun chooseLabel(part: Part, searching: String?): String = if (searching == part.key) Words.Game.SEARCHING else Words.Game.CHOOSE_RELEASE

    /**
     * The line the page keeps after Play or Choose release last found nothing for one of [items] ([nothing]), until a
     * release of the page plays after it (the host's last play of any of its parts: a weekend that played on is past it):
     * the latest such, with when, and the event's noun.
     */
    private fun kept(items: List<Item>, watch: Map<String, WatchInfo>, nothing: Map<String, Instant>, zone: ZoneId): String? {
        val played = items.mapNotNull { watch[it.part.key]?.lastPlayedAt }.maxOrNull()
        return items.mapNotNull { item -> nothing[item.part.key]?.let { item to it } }
            .filter { (_, at) -> played == null || played.isBefore(at) }
            .maxByOrNull { it.second }?.let { (item, at) -> Words.NoRelease.kept(Times.time(at, zone), Nouns.event(item.event)) }
    }

    private fun progress(info: WatchInfo): Float? = if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else null
}

/**
 * An event's or weekend's page's model on a phone or a [tv]: the refresh's [state] as a [PageScreen], with the host's
 * [watch] states of its parts, the lines [plays] keeps when Play or Choose release found nothing, the part Choose release
 * searches for, and the time from [clock] in [zone]'s days. Opening it searches nothing: Play hands the part to the host,
 * which asks for releases only as it plays; Choose release and Search indexers search when pressed.
 *
 * [run] does its work while the page shows: it follows the list, its parts' watch states and the lines, and builds each
 * screen on [work], off the main thread (L641). The time moves on by [tick].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PageModel(
    private val state: StateFlow<ListState>,
    private val watch: PluginWatch,
    private val plays: Plays,
    private val page: String,
    private val key: String,
    private val tv: Boolean,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val now = MutableStateFlow(clock())
    private val searching = MutableStateFlow<String?>(null)

    /** The part Choose release last found nothing for with no indexer answering, and why: until the next Play or Choose release. */
    private val unanswered = MutableStateFlow<Pair<String, String>?>(null)
    private val shown = MutableStateFlow<PageScreen?>(null)

    /** The page's screen, null until the first is built and while the list loads. */
    val screen: StateFlow<PageScreen?> = shown.asStateFlow()

    /** The list and what the page shows of it, as last built, for its buttons. */
    @Volatile
    private var current: Pair<Sports, Listed>? = null

    suspend fun run() {
        now.value = clock()
        val followed = state.flatMapLatest { listState ->
            val sports = listState.sports
            val listed = sports?.let { withContext(work) { PageScreens.listed(it, page, key) } }
            if (listed == null) flowOf(listState to emptyMap()) else watch.observe(listed.items.map { it.part.key }).map { listState to it }
        }
        combine(followed, plays.nothing, searching, now, unanswered) { (listState, states), nothing, searching, now, unanswered ->
            Input(listState, states, nothing, searching, now, unanswered)
        }
            .collectLatest { input ->
                val (built, listed) = withContext(work) {
                    val sports = input.state.sports
                    PageScreens.of(input.state, page, key, input.watch, input.nothing, input.searching, input.now, zone(), tv, input.unanswered) to
                        sports?.let { PageScreens.listed(it, page, key)?.let { listed -> sports to listed } }
                }
                current = listed
                shown.value = built
            }
    }

    /** Plays [part] (its key), handing it to the host. */
    fun play(part: String) {
        val (sports, event, found) = find(part) ?: return
        unanswered.value = null
        plays.play(sports, event, found)
    }

    /**
     * Choose release for [part]: the runner's releases in the host's list, or the page's line with nothing at all. Its
     * button says it is searching meanwhile, and a press while it runs, for this part or another, does nothing.
     */
    suspend fun choose(part: String, nav: PluginNav) {
        val (sports, event, found) = find(part) ?: return
        if (!searching.compareAndSet(null, part)) return
        unanswered.value = null
        try {
            (plays.choose(sports, event, found, nav) as? Chosen.Unanswered)?.let { unanswered.value = part to it.line }
        } finally {
            searching.value = null
        }
    }

    /** Search indexers for [part]: the host's Search with the event's words and the scores note. */
    suspend fun search(part: String, nav: PluginNav) {
        find(part)?.let { (sports, event, found) -> plays.search(sports, event, found, nav) }
    }

    /** Marks [part] [watched] or not. */
    fun mark(part: String, watched: Boolean) {
        find(part)?.let { (sports, event, found) -> plays.mark(sports, event, found, watched) }
    }

    /** The time moves on: an event starts, and its page shows its buttons. */
    fun tick() {
        now.value = clock()
    }

    private fun find(part: String): Triple<Sports, SportsEvent, Part>? {
        val (sports, listed) = current ?: return null
        val item = listed.items.firstOrNull { it.part.key == part } ?: return null
        return Triple(sports, item.event, item.part)
    }

    private class Input(
        val state: ListState,
        val watch: Map<String, WatchInfo>,
        val nothing: Map<String, Instant>,
        val searching: String?,
        val now: Instant,
        val unanswered: Pair<String, String>?,
    )
}

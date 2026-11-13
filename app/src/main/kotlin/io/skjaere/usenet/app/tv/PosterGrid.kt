package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.PivotBringIntoViewSpec
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A TV's grid of posters (TV-4's L2, L3 and L9), for the Collection tab and for Discover's lists: seven to a line, the
 * lines above them (a header, each the whole width of a line) and a footer scrolling with them, the focus kept by key.
 * It knows nothing of what its items are: the caller maps each to a [PosterItem] as it is composed.
 */

/**
 * One poster as the grid draws it: [line] under its title in [tone], on up to [lineLines] lines at a large text size;
 * [spoken] what TalkBack hears of it (null: its words).
 */
@Immutable
data class PosterItem(
    val key: String,
    val title: String,
    val posterPath: String?,
    val line: String? = null,
    val tone: PosterTone = PosterTone.Muted,
    val new: Boolean = false,
    val progress: Float? = null,
    val spoken: String? = null,
    val lineLines: Int = 1,
)

/** What the caller marks on its posters, by key: select mode and what is selected; a jump's [highlight], [justAdded] or not. */
@Immutable
data class PosterMarks(
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val highlight: String? = null,
    val justAdded: Boolean = false,
)

/**
 * A grid's place and the focus it keeps: where it is scrolled ([grid]), the poster last focused ([lastPoster], for the
 * way back from the rail) and the one with the focus now ([focusedPoster]), and the poster that opened a page
 * ([leaving]), focused again as the grid shows again. One per list shown (each of the collection's sides). Requesters
 * are kept for the posters composed only, so a long list doesn't grow a map of every poster ever shown.
 *
 * The poster last focused isn't saved: a grid composed anew (its tab opened again from the rail) starts on its screen's
 * own first focus, which a poster focused before mustn't take over as the focus comes in from the rail.
 */
@Stable
class PosterGridState internal constructor(val grid: LazyGridState, private var opened: String?) {
    var lastPoster by mutableStateOf<String?>(null)
        internal set
    var focusedPoster by mutableStateOf<String?>(null)
        internal set
    internal val requesters = HashMap<String, FocusRequester>()

    /** How many posters have a requester: the composed ones. */
    val requestersKept: Int get() = requesters.size

    /** The poster [key] opens something (a page): the focus comes back to it as the grid shows again. */
    fun leaving(key: String) {
        opened = key
    }

    /**
     * The focus on the poster [key] now; false when it isn't composed (or takes nothing), or when the request was handed
     * on elsewhere on its way in: the shell's content gives it to what it last had there, still on the screen as a tab
     * fades in over the one left.
     */
    fun focusPoster(key: String): Boolean = requesters[key]?.tryFocus() == true && focusedPoster == key

    internal fun takeOpened(): String? = opened.also { opened = null }

    companion object {
        val Saver = listSaver<PosterGridState, Any?>(
            save = { listOf(it.grid.firstVisibleItemIndex, it.grid.firstVisibleItemScrollOffset, it.opened) },
            restore = { PosterGridState(LazyGridState(it[0] as Int, it[1] as Int), it[2] as String?) },
        )
    }
}

@Composable
fun rememberPosterGridState(): PosterGridState = rememberSaveable(saver = PosterGridState.Saver) { PosterGridState(LazyGridState(), null) }

/**
 * [items] as posters, seven to a line ([TvDimens.poster] with its gutter, from the line's start), the whole width of the
 * shell's column ([tvFullWidth]: 784 dp beside the rail, 864 on a page). [header]'s keys are the lines above them, in
 * order, each the line's whole width and drawn by [headerItem]: the caller's own description, which its own scrolling
 * counts by too ([GridPaging.itemOf]). [footer] follows the posters. [poster] maps an item as it is composed.
 *
 * The first line is on the first screen with the lines above it, when they fit: an element there brought into view (the
 * focus on it) takes the grid to its top, those lines shown whole, rather than to the shell's pivot, which would cut the
 * top line off. Further down, the pivot.
 *
 * The remote: Left from a line's first poster goes to the rail, Right stops at a line's last; [onTab] (the rail beside
 * it), Right from the rail comes back to [onEnterFromRail]'s element (the caller's own, false for none), else to the
 * poster last focused since the grid was composed, brought into view first should it have gone from the screen
 * meanwhile; none, the focus comes in as it was asked for (the screen's own first focus, OK on the rail). On a page
 * there is no rail, and nothing of it. The paging keys (CH and PAGE, up and down, held or not) move the focus to
 * [paging]'s target ([GridPaging.lines] by default), scrolled into view first; above the posters is [onHeader]'s;
 * [paging] null leaves the keys alone. A poster that opened something ([PosterGridState.leaving]) has the focus again
 * as the grid shows again.
 *
 * A long OK on a poster opens its [menu] beside it ([TvMenu]: its first item focused, the held OK picking nothing in it),
 * whose items the caller draws ([TvMenuItem], the first with the modifier it is given) and closes with the close it is
 * given; as it closes the focus is back on the poster. In select mode ([PosterMarks.selecting]) a long OK is an OK.
 *
 * A grid whose items grow as it is scrolled asks for more by [onNearEnd]: called as the posters shown come near the last
 * line ([GridPaging.nearEnd]), however they got there (the remote's walk, the paging keys, a touch), and again as more
 * land while they are still near it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> PosterGrid(
    items: List<T>,
    key: (T) -> String,
    poster: (T) -> PosterItem,
    state: PosterGridState,
    artwork: Boolean,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
    onTab: Boolean = true,
    header: List<String> = emptyList(),
    headerItem: @Composable LazyGridItemScope.(key: String) -> Unit = {},
    footer: (@Composable LazyGridItemScope.() -> Unit)? = null,
    marks: PosterMarks = PosterMarks(),
    menu: (@Composable ColumnScope.(item: T, first: Modifier, close: () -> Unit) -> Unit)? = null,
    actions: (T) -> List<CustomAccessibilityAction> = { emptyList() },
    paging: ((from: Int?, forward: Boolean) -> GridPaging.Target)? = { from, forward -> GridPaging.lines(from, items.size, forward) },
    onHeader: () -> Unit = {},
    onEnterFromRail: (() -> Boolean)? = null,
    onFocused: (T) -> Unit = {},
    bottomPadding: Dp = 16.dp,
    onNearEnd: (() -> Unit)? = null,
) {
    val rail = LocalRailFocus.current.takeIf { onTab }
    val scope = rememberCoroutineScope()
    val initial = rememberInitialFocus()
    val latestItems by rememberUpdatedState(items)
    val latestHeader by rememberUpdatedState(header)
    val latestPaging by rememberUpdatedState(paging)
    val latestOnHeader by rememberUpdatedState(onHeader)
    // The poster a page is taking the focus to: a key held, or pressed again, pages on from there, not from where the
    // focus still is.
    val page = remember { PageRun() }
    // The poster whose menu is open, by key.
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun rowOf(k: String): Int? = latestItems.indexOfFirst { key(it) == k }.takeIf { it >= 0 }

    // In view first (scrolled to the pivot's line, where the focus takes it anyway; the first line to the grid's top, the
    // first screen), then focused once composed.
    suspend fun bringIn(row: Int): Boolean {
        val item = latestItems.getOrNull(row) ?: return false
        val k = key(item)
        if (state.grid.layoutInfo.visibleItemsInfo.none { it.key == k }) {
            val pivot = (state.grid.layoutInfo.viewportSize.height * PivotBringIntoViewSpec.LINE).toInt()
            if (row < GridPaging.COLUMNS) state.grid.scrollToItem(0) else state.grid.scrollToItem(GridPaging.itemOf(row, latestHeader.size), -pivot)
        }
        withFrameNanos {}
        return state.focusPoster(k)
    }

    // Right from the rail: the poster last focused. Not composed any more (the items moved it while the rail had the
    // focus), it is brought in first, the focus kept on the rail meanwhile rather than put on whichever poster is nearest.
    fun FocusEnterExitScope.enterLast() {
        val k = state.lastPoster ?: return
        if (state.focusPoster(k)) return
        val row = rowOf(k) ?: return
        cancelFocusChange()
        page.job?.cancel()
        page.row = null
        page.job = scope.launch { bringIn(row) }
    }

    // The items moved under the focused poster (re-sorted as what they show changes): the grid keeps its place by the
    // first poster on the screen, and the focused one, kept composed, may have been carried off it. Brought back into
    // view, once the grid is laid out with them: on a TV the focus is the only pointer.
    LaunchedEffect(state) {
        snapshotFlow { latestItems }.collectLatest { items ->
            val k = state.focusedPoster ?: return@collectLatest
            val info = snapshotFlow { state.grid.layoutInfo }.first { info ->
                info.visibleItemsInfo.all { it.contentType != POSTER || items.getOrNull(it.index - latestHeader.size)?.let(key) == it.key }
            }
            if (info.visibleItemsInfo.none { it.key == k }) rowOf(k)?.let { bringIn(it) }
        }
    }

    // Back from what a poster opened: the focus on it again, once the items are there and the screen may take it.
    LaunchedEffect(state) {
        val k = state.takeOpened() ?: return@LaunchedEffect
        initial.awaitTurn()
        val row = withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { rowOf(k) }.filterNotNull().first() } ?: return@LaunchedEffect
        bringIn(row)
    }

    // Near the end, by the last poster laid out: once as it comes near, then as each more lands while it is still there.
    val latestNearEnd by rememberUpdatedState(onNearEnd)
    if (onNearEnd != null) {
        LaunchedEffect(state) {
            snapshotFlow {
                val count = latestItems.size
                val last = state.grid.layoutInfo.visibleItemsInfo.lastOrNull { it.contentType == POSTER }?.index?.minus(latestHeader.size)
                count.takeIf { GridPaging.nearEnd(last, count) }
            }.distinctUntilChanged().collect { if (it != null) latestNearEnd?.invoke() }
        }
    }

    // The grid's own scrolling only: what is inside its items (the chips' row, a menu) scrolls as the shell's.
    val outer = LocalBringIntoViewSpec.current
    val firstScreen = remember(state, outer) { FirstScreen(state.grid, outer) { latestHeader } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides firstScreen) {
        LazyVerticalGrid(
            columns = SevenPosters,
            modifier = modifier.tvFullWidth()
                // Before the grid's own scrolling, which takes PAGE_UP and PAGE_DOWN to scroll a screen and leaves the
                // focus where it was. Down and up both taken, so nothing else sees half a press; each down pages, a held
                // key's repeats too.
                .onPreviewKeyEvent { event ->
                    val forward = pagesForward(event.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
                    val target = latestPaging ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        val from = page.row ?: state.focusedPoster?.let(::rowOf)
                        when (val to = target(from, forward)) {
                            is GridPaging.Target.Poster -> {
                                page.row = to.row
                                page.job?.cancel()
                                page.job = scope.launch {
                                    try {
                                        bringIn(to.row)
                                    } finally {
                                        if (page.row == to.row) page.row = null
                                    }
                                }
                            }
                            GridPaging.Target.Header -> {
                                page.job?.cancel()
                                page.row = null
                                latestOnHeader()
                            }
                            GridPaging.Target.Stay -> Unit
                        }
                    }
                    true
                }
                // From the rail, back to the element last focused: the shell's own memory keeps only the grid.
                .focusProperties {
                    onEnter = {
                        if (rail?.hasFocus == true && onEnterFromRail?.invoke() != true) enterLast()
                    }
                }
                .focusGroup(),
            state = state.grid,
            // Room above the first line for a focused poster's growth, which the grid would clip.
            contentPadding = PaddingValues(top = TOP_ROOM, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.posterGutter, Alignment.Start),
        ) {
            header.forEach { k ->
                item(key = k, span = { GridItemSpan(maxLineSpan) }, contentType = k) { CompositionLocalProvider(LocalBringIntoViewSpec provides outer) { headerItem(k) } }
            }
            itemsIndexed(items, key = { _, item -> key(item) }, contentType = { _, _ -> POSTER }) { row, item ->
                CompositionLocalProvider(LocalBringIntoViewSpec provides outer) {
                    val k = key(item)
                    val shown = remember(item, poster) { poster(item) }
                    val requester = remember { FocusRequester() }
                    DisposableEffect(state, k) {
                        state.requesters[k] = requester
                        onDispose { if (state.requesters[k] === requester) state.requesters.remove(k) }
                    }
                    val lastOfLine = row % GridPaging.COLUMNS == GridPaging.COLUMNS - 1 || row == items.lastIndex
                    // The gap under the line is outside the card, which takes the focus, and the menu beside it.
                    Box(Modifier.padding(bottom = TvDimens.posterLineGap)) {
                        PosterCard(
                            shown.title, shown.posterPath, artwork, onClick = { onOpen(item) },
                            modifier = Modifier.focusRequester(requester)
                                // A line's last poster: Right stops, rather than go up to the line before's (a short
                                // last line).
                                .then(if (lastOfLine) Modifier.focusProperties { right = FocusRequester.Cancel } else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        state.lastPoster = k
                                        state.focusedPoster = k
                                        onFocused(item)
                                    } else if (state.focusedPoster == k) {
                                        state.focusedPoster = null
                                    }
                                },
                            subtitle = shown.line, new = shown.new, progress = shown.progress,
                            clickLabel = if (marks.selecting) SELECT else OPEN,
                            // Always a long click when there is a menu, select mode or not: a card that changed
                            // between a click and a long click would be a new focus target, and drop the focus.
                            onLongClick = menu?.let { { if (marks.selecting) onOpen(item) else menuFor = k } },
                            spoken = shown.spoken, actions = actions(item),
                            selected = if (marks.selecting) k in marks.selected else null,
                            highlighted = k == marks.highlight, justAdded = k == marks.highlight && marks.justAdded,
                            tone = shown.tone, nameOnTile = false, subtitleLines = shown.lineLines,
                        )
                        // Composed only while open: a grid's every poster needn't carry a closed menu.
                        if (menu != null && menuFor == k) {
                            TvMenu(expanded = true, onDismiss = { menuFor = null }) { first -> menu(item, first) { menuFor = null } }
                        }
                    }
                }
            }
            footer?.let { content ->
                item(key = FOOTER, span = { GridItemSpan(maxLineSpan) }, contentType = FOOTER) { CompositionLocalProvider(LocalBringIntoViewSpec provides outer) { content() } }
            }
        }
    }
}

/**
 * The grid's scrolling to what is brought into view: when it would be whole on the first screen, the grid's top (the
 * lines above it shown too); else as [outer] (the shell's pivot). How far the grid is from its top is counted from the
 * items before the first one laid out: the header's lines by the sizes they last had (each was laid out to get there),
 * the posters' lines by theirs; not known (a line above never laid out since the grid was restored further down), the
 * pivot until it is.
 */
@OptIn(ExperimentalFoundationApi::class)
private class FirstScreen(private val grid: LazyGridState, private val outer: BringIntoViewSpec, private val header: () -> List<String>) : BringIntoViewSpec {
    private val sizes = HashMap<String, Int>()
    private var line: Int? = null

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val scrolled = scrolled(grid.layoutInfo)
        if (scrolled != null && offset + scrolled + size <= containerSize) return -scrolled.toFloat()
        return outer.calculateScrollDistance(offset, size, containerSize)
    }

    private fun scrolled(info: LazyGridLayoutInfo): Int? {
        val header = header()
        info.visibleItemsInfo.forEach { item ->
            if (item.contentType == POSTER) line = item.size.height
            else (item.key as? String)?.takeIf { it in header }?.let { sizes[it] = item.size.height }
        }
        val first = info.visibleItemsInfo.firstOrNull() ?: return null
        var above = 0
        for (k in header.take(first.index)) above += sizes[k] ?: return null
        val lines = (first.index - header.size).coerceAtLeast(0) / GridPaging.COLUMNS
        if (lines > 0) above += lines * (line ?: return null)
        return above - first.offset.y
    }
}

/** A page under way: the poster it takes the focus to, and its job, which a later page replaces. */
private class PageRun {
    var row: Int? = null
    var job: Job? = null
}

/** Toward the end (CH down, PAGE down) or the top (CH up, PAGE up); null for any other key. */
private fun pagesForward(keyCode: Int): Boolean? = when (keyCode) {
    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> true
    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> false
    else -> null
}

/** Seven posters to a line, each its own width, from the line's start: the 784 dp beside the rail, or a page's 864. */
private object SevenPosters : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> =
        List(GridPaging.COLUMNS) { TvDimens.poster.width.roundToPx() }
}

/** At least a focused first line's growth (147 dp grown 1.1 times spills 7.35 dp up). */
private val TOP_ROOM = 8.dp

private const val POSTER = "poster"
private const val FOOTER = "poster-grid-footer"

/** How long a grid shown again waits for its items before it gives up on the focus back on the poster that opened a page. */
private const val RESTORE_WAIT_MS = 2_000L

/** What OK on a poster does, said to TalkBack. */
private const val OPEN = "Open"
private const val SELECT = "Select"

package io.skjaere.usenet.app.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.search.kindLabel
import io.skjaere.usenet.app.search.openedTitle
import io.skjaere.usenet.app.tv.CardRow
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.MoreCard
import io.skjaere.usenet.app.tv.PosterCard
import io.skjaere.usenet.app.tv.StillCard
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvSection
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.data.RecentSearch
import kotlinx.coroutines.launch

/**
 * Home on a TV (TV-3 D1 and D12): one list across the column beside the rail, with no
 * search bar (Search is the rail's first item, and the remote's search key opens it). Up next as 16:9 cards: OK opens
 * the show's or movie's page, whose first focus is Resume or Play, so a second OK plays; a release
 * played without TMDB has no page, nor has anything without a TMDB key, and its card plays. Then the collection's
 * posters and See all, the recent searches, and Open a .nzb file. With nothing to resume or collected, a card that leads
 * to Search. [state] null while the library answers: the sections' outlines, nothing to focus.
 *
 * The first focus is the element that opened what was in front (a page, a tab), else the first card, the first poster,
 * the empty card's button, the TMDB hint or Open a .nzb file; never while the user holds the rail. Right from the rail
 * goes back to the element last focused. When Up next changes under the focus (a play reorders it), the focused card
 * stays on screen, and one gone hands the focus to the next card, else the one before, else Home's first focus.
 *
 * A held OK on a card opens its menu ([edits], L24): Remove from Up next, and Mark watched for an episode or film in
 * progress. Then the Undo bar at the foot has the focus; as it goes, the focus goes to the next card, else the one
 * before, else Home's first focus; after Undo, to the card brought back.
 */
@Composable
fun TvHomeContent(
    state: HomeState?,
    artwork: Boolean,
    onSearch: () -> Unit,
    onRecent: (RecentSearch) -> Unit,
    onPlay: (UpNextRow) -> Unit,
    onShow: (Int) -> Unit,
    onMovie: (Int) -> Unit,
    onCollection: () -> Unit,
    onMetadata: () -> Unit,
    onImport: () -> Unit,
    edits: UpNextEdits = UpNextEdits(),
) {
    val focus = rememberReturnFocus()
    val initial = rememberInitialFocus()
    val rail = LocalRailFocus.current
    val memory = remember { HomeMemory() }
    val list = rememberLazyListState()
    val upNext = rememberLazyListState()
    val posters = rememberLazyListState()
    val recents = rememberLazyListState()
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val undo = remember { UpNextUndoFocus() }
    // The card whose menu is open, by its row's key.
    var menuFor by remember { mutableStateOf<String?>(null) }

    /** The element [key]: the focus can be put on it, and Home keeps that it had it. */
    fun Modifier.element(key: String): Modifier = focusRequester(focus.requester(key)).onFocusChanged { if (it.isFocused) memory.last = key }

    /** The element [key] opens something (a page, a tab): the focus comes back to it after. */
    fun open(key: String, action: () -> Unit) {
        focus.leaving(key)
        action()
    }

    // In view before the focus goes back to it: a row's card, or the list's item, is composed only once it is near.
    suspend fun bringIntoView(key: String) {
        val s = latest ?: return
        val items = columnItems(s)
        val (item, row, index) = place(key, s)
        val at = items.indexOf(item)
        if (at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == item }) list.scrollToItem(at)
        if (row != null && index >= 0) {
            val rowState = when (row) {
                UP_NEXT -> upNext
                COLLECTION -> posters
                else -> recents
            }
            if (rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
        }
    }

    /** The focus on the element [key]; brought into view first when it isn't composed. */
    fun focusOn(key: String) {
        if (focus.requester(key).tryFocus()) return
        scope.launch {
            bringIntoView(key)
            withFrameNanos {}
            focus.requester(key).tryFocus()
        }
    }

    // The first focus, once the library has answered: back on what opened what was in front, else the first of Home's.
    LaunchedEffect(state != null) {
        if (state == null) return@LaunchedEffect
        if (focus.restore(::bringIntoView)) return@LaunchedEffect
        initial.awaitTurn()
        withFrameNanos {}
        latest?.let(::firstFocus)?.let { focus.requester(it).tryFocus() }
    }

    // Up next changed under the focus (a play moved a card, one ended). Seen as the new rows are composed: the focused
    // card's new place ([moved], with the rows before), or, for a card gone, the one to hand the focus to once the new
    // row is up.
    val upKeys = state?.upNext?.map { it.key }.orEmpty()
    val moved: Pair<List<String>, Int>? = if (memory.upKeys == upKeys) null else {
        val before = memory.upKeys
        memory.upKeys = upKeys
        val focused = memory.upFocused?.takeIf { memory.upHasFocus }
        val now = focused?.let(upKeys::indexOf) ?: -1
        when {
            focused == null -> null
            now >= 0 -> before to now
            // An edit from the card's menu hands the focus on itself, once its Undo bar goes.
            undo.busy -> null
            else -> {
                val at = before.indexOf(focused).coerceAtMost(upKeys.size - 1)
                memory.handOn = upKeys.getOrNull(at)?.let { UP_PREFIX + it } ?: state?.let(::firstFocus)
                null
            }
        }
    }
    // Before the row is measured with the new rows: it keeps showing its first card by key, so a focused card moved in
    // front of that one would sit off its left end. It is asked into view instead.
    SideEffect {
        moved?.let { (before, now) ->
            val firstShown = before.getOrNull(upNext.firstVisibleItemIndex)
            if (now < upKeys.indexOf(firstShown)) upNext.requestScrollToItem(now)
        }
    }
    LaunchedEffect(upKeys) {
        // A card gone with its menu open: closed, so it doesn't open again should the card come back.
        if (menuFor != null && menuFor !in upKeys) menuFor = null
        val next = memory.handOn ?: return@LaunchedEffect
        memory.handOn = null
        withFrameNanos {}
        focusOn(next)
    }

    val tracked = undo.tracking(edits, upKeys)
    Box(Modifier.fillMaxHeight().tvFullWidth()) {
        LazyColumn(
            Modifier.fillMaxHeight().tvFullWidth().semantics { tvHome = true }
                // From the rail, back to the element last focused: the shell's own memory keeps only this list.
                .focusProperties { onEnter = { if (rail?.hasFocus == true) memory.last?.let { focus.requester(it).tryFocus() } } }
                .focusGroup(),
            state = list,
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            if (state == null) {
                item(key = LOADING) { Outlines() }
                return@LazyColumn
            }
            columnItems(state).forEach { part ->
                when (part) {
                    UP_NEXT -> item(key = part) {
                        TvSection(UP_NEXT_TITLE) {
                            CardRow(
                                state.upNext, key = { it.key }, state = upNext, gutter = TvDimens.landscapeGutter,
                                modifier = Modifier.onFocusChanged { memory.upHasFocus = it.hasFocus },
                            ) { row ->
                                // Without a TMDB key a page has nothing to show it by (the Show page needs TMDB's answer):
                                // the card plays, as a release's does.
                                val title = row.titleKey?.takeIf { state.hasTmdbKey }
                                val items = tracked.items(row)
                                // The menu below the card, in the box with it.
                                Box {
                                    StillCard(
                                        row.title, row.landscapePath, artwork,
                                        onClick = { if (title == null) onPlay(row) else open(upKey(row)) { if (title.isShow) onShow(title.tmdbId) else onMovie(title.tmdbId) } },
                                        modifier = Modifier.element(upKey(row)).onFocusChanged { if (it.isFocused) memory.upFocused = row.key },
                                        subtitle = row.cardLine, progress = row.progress, posterPath = row.posterPath, new = row.new,
                                        // A release played without TMDB, or anything without a key, has no page to open: its card
                                        // plays, and says so.
                                        clickLabel = if (title == null) PLAY else OPEN,
                                        // Every card's, so none changes its click's kind (which would drop the focus, M10). A held
                                        // OK on a card that plays opens the menu and plays nothing.
                                        onLongClick = { menuFor = row.key },
                                        actions = upNextActions(items),
                                    )
                                    // Composed only while open, as the collection grid's.
                                    if (menuFor == row.key) {
                                        TvMenu(expanded = true, onDismiss = { menuFor = null }) { first ->
                                            UpNextMenuItems(items, close = { menuFor = null }, modifier = first)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EMPTY_ITEM -> item(key = part) {
                        // Alone on its line, in the middle: Left goes to the rail, as from the column's first elements, not
                        // to the recent searches or Open a .nzb file below it.
                        val left = Modifier.focusProperties { rail?.let { left = it.wayIn } }
                        NothingYet(onSearch = { open(EMPTY, onSearch) }, left.element(EMPTY))
                    }
                    HINT_ITEM -> item(key = part) {
                        NavRow(TMDB_HINT, modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = HINT_WIDTH).element(HINT)) { open(HINT, onMetadata) }
                    }
                    COLLECTION -> item(key = part) {
                        val collection = state.collection ?: return@item
                        TvSection(collection.header) {
                            CardRow(
                                collection.rows, key = { it.key.value }, state = posters,
                                end = { MoreCard(SEE_ALL, { open(MORE, onCollection) }, Modifier.element(MORE)) },
                            ) { row ->
                                PosterCard(
                                    row.title, row.posterPath, artwork,
                                    onClick = { open(posterKey(row)) { if (row.key.isShow) onShow(row.key.tmdbId) else onMovie(row.key.tmdbId) } },
                                    modifier = Modifier.element(posterKey(row)), new = row.new, progress = row.progress,
                                )
                            }
                        }
                    }
                    RECENT -> item(key = part) {
                        TvSection(RECENT_TITLE) {
                            EdgeScrolling {
                                LazyRow(
                                    Modifier.fillMaxWidth(), state = recents,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(state.recent, key = { it.key }) { r ->
                                        RecentChip(r, Modifier.element(recentKey(r))) { open(recentKey(r)) { onRecent(r) } }
                                    }
                                }
                            }
                        }
                    }
                    IMPORT_ITEM -> item(key = part) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SecondaryButton(IMPORT_LABEL, { open(IMPORT, onImport) }, Modifier.element(IMPORT))
                        }
                    }
                }
            }
        }
        // At the column's foot, over what is there. A TV has a remote: Undo takes the focus, and hands it on as it goes.
        UpNextUndo(
            edits, undo, upKeys, dpad = true,
            handTo = { key -> (key?.let { UP_PREFIX + it } ?: latest?.let(::firstFocus))?.let(::focusOn) },
            modifier = Modifier.align(Alignment.BottomCenter).tvFullWidth(),
        )
    }
}

/**
 * What TV Home keeps of its focus while it shows: the element last focused ([last], by its ReturnFocus key), for the way
 * back from the rail; the Up next card focused last ([upFocused], by its row's key) and whether a card has the focus now
 * ([upHasFocus]); the keys of the rows shown ([upKeys]); and the card to hand the focus to once one has gone ([handOn]).
 */
@Stable
private class HomeMemory {
    var last: String? = null
    var upFocused: String? = null
    var upHasFocus = false
    var upKeys: List<String> = emptyList()
    var handOn: String? = null
}

/** The list's items, in order, for [state]. */
private fun columnItems(state: HomeState): List<String> = buildList {
    if (state.upNext.isNotEmpty()) add(UP_NEXT) else if (state.collection == null) add(EMPTY_ITEM)
    if (!state.hasTmdbKey) add(HINT_ITEM)
    if (state.collection != null) add(COLLECTION)
    if (state.recent.isNotEmpty()) add(RECENT)
    add(IMPORT_ITEM)
}

/** Where the element [key] is: its list item, the row in it (when it is in one) and its place in that row. */
private fun place(key: String, state: HomeState): Triple<String, String?, Int> = when {
    key.startsWith(UP_PREFIX) -> Triple(UP_NEXT, UP_NEXT, state.upNext.indexOfFirst { upKey(it) == key })
    key.startsWith(POSTER_PREFIX) -> Triple(COLLECTION, COLLECTION, state.collection?.rows?.indexOfFirst { posterKey(it) == key } ?: -1)
    key == MORE -> Triple(COLLECTION, COLLECTION, state.collection?.rows?.size ?: -1)
    key.startsWith(RECENT_PREFIX) -> Triple(RECENT, RECENT, state.recent.indexOfFirst { recentKey(it) == key })
    key == EMPTY -> Triple(EMPTY_ITEM, null, -1)
    key == HINT -> Triple(HINT_ITEM, null, -1)
    else -> Triple(IMPORT_ITEM, null, -1)
}

/** Home's own first focus: the first Up next card, else the first poster, the empty card's button, the TMDB hint, Open a .nzb file. */
private fun firstFocus(state: HomeState): String {
    val items = columnItems(state)
    return state.upNext.firstOrNull()?.let(::upKey)
        ?: state.collection?.let { c -> c.rows.firstOrNull()?.let(::posterKey) ?: MORE }
        ?: EMPTY.takeIf { EMPTY_ITEM in items }
        ?: HINT.takeIf { HINT_ITEM in items }
        ?: IMPORT
}

/** Nothing to resume and nothing collected: a card that says so, and leads to Search; [modifier] goes on its button. */
@Composable
private fun NothingYet(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(EMPTY_WIDTH).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(NOTHING_TITLE, style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center)
            Text(NOTHING_LINE, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center)
            PrimaryButton(
                SEARCH_INDEXERS, onSearch, modifier.padding(top = 14.dp),
                leading = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp)) },
            )
        }
    }
}

/**
 * A recent search on a TV: a pill, turned over while focused, its words and, for a title opened from Search, what it is.
 * OK does what the phone's chip does: a title's page opens again, a search of the indexers runs again.
 */
@Composable
private fun RecentChip(recent: RecentSearch, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val title = recent.openedTitle
    OwnedRipple {
        Row(
            modifier.heightIn(min = 40.dp).focusScale(interaction, { it.buttonScale }).clip(RoundedCornerShape(20.dp))
                .background(if (turned) colors.text else colors.chip)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClickLabel = if (title == null) SEARCH else OPEN, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(recent.query, style = AppType.body, color = if (turned) colors.background else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (title != null) Text(recent.kindLabel, style = AppType.secondary, color = if (turned) colors.turnedSecondary else colors.muted, maxLines = 1)
        }
    }
}

/** While the library answers: the sections' outlines, cards without anything on them, none of them focusable. */
@Composable
private fun Outlines() {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        TvSection(UP_NEXT_TITLE) { Blanks(4, TvDimens.landscape.width, TvDimens.landscape.height, TvDimens.landscapeGutter) }
        TvSection(COLLECTION_TITLE) { Blanks(7, TvDimens.poster.width, TvDimens.poster.height, TvDimens.posterGutter) }
    }
}

@Composable
private fun Blanks(n: Int, width: Dp, height: Dp, gutter: Dp) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(gutter)) {
        repeat(n) { Box(Modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(colors.surface)) }
    }
}

private fun upKey(row: UpNextRow) = UP_PREFIX + row.key
private fun posterKey(row: HomeCollectionRow) = POSTER_PREFIX + row.key.value
private fun recentKey(recent: RecentSearch) = RECENT_PREFIX + recent.key

/** Home's elements' keys (ReturnFocus's), by what they are. */
private const val UP_PREFIX = "up:"
private const val POSTER_PREFIX = "poster:"
private const val RECENT_PREFIX = "recent:"
private const val MORE = "more"
private const val EMPTY = "empty"
private const val HINT = "hint"
private const val IMPORT = "import"

/** The list's items. */
private const val LOADING = "loading"
private const val UP_NEXT = "up-next"
private const val EMPTY_ITEM = "empty-item"
private const val HINT_ITEM = "tmdb-hint"
private const val COLLECTION = "collection"
private const val RECENT = "recent"
private const val IMPORT_ITEM = "import-item"

/**
 * TV Home's content, which the tests know it by: they wait for the focus inside it, whatever Home's first focus is (an
 * Up next card, a poster, the empty card's button), and not on the rail.
 */
val TvHomeKey = SemanticsPropertyKey<Boolean>("TvHome")
private var SemanticsPropertyReceiver.tvHome by TvHomeKey

/** What TV Home says; the tests match these. */
const val UP_NEXT_TITLE = "Up next"
const val COLLECTION_TITLE = "Collection"
const val RECENT_TITLE = "Recent searches"
const val SEE_ALL = "See all"
const val NOTHING_TITLE = "Nothing to resume yet"
const val NOTHING_LINE = "Search your indexers to find something to watch."
const val SEARCH_INDEXERS = "Search your indexers"
const val TMDB_HINT = "Add a TMDB key to keep a collection"
const val IMPORT_LABEL = "Open a .nzb file"

/** What OK on a card or chip does, said to TalkBack. */
private const val OPEN = "Open"
private const val PLAY = "Play"
private const val SEARCH = "Search"

private val SECTION_GAP = 20.dp
private val EMPTY_WIDTH = 480.dp
private val HINT_WIDTH = 480.dp

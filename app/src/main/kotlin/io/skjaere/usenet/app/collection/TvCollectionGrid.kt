package io.skjaere.usenet.app.collection

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.Chip
import io.skjaere.usenet.app.collection.model.CollectionView
import io.skjaere.usenet.app.collection.model.Empty
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieSort
import io.skjaere.usenet.app.collection.model.MovieState
import io.skjaere.usenet.app.collection.model.ShowFilter
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowSort
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.collection.model.menuMark
import io.skjaere.usenet.app.collection.model.posterLine
import io.skjaere.usenet.app.collection.model.posterProgress
import io.skjaere.usenet.app.tv.GridPaging
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.PosterGrid
import io.skjaere.usenet.app.tv.PosterGridState
import io.skjaere.usenet.app.tv.PosterItem
import io.skjaere.usenet.app.tv.PosterMarks
import io.skjaere.usenet.app.tv.PosterTone
import io.skjaere.usenet.app.tv.SwitchSide
import io.skjaere.usenet.app.tv.TvChip
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.tv.TvPill
import io.skjaere.usenet.app.tv.rememberPosterGridState
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.FieldGate
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.PivotBringIntoViewSpec
import io.skjaere.usenet.app.ui.TvFieldGate
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberFieldGate
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Collection tab on a TV (TV-4): the titles as posters, seven to a line, over slice 12's
 * ViewModel and its focus and scrolling ([CollectionFocusEffects]). Everything scrolls in the grid: the top line
 * ("Collection", Find, Select), the switch with each side's count and the chips, the header line (the count, Jump to
 * letter, the sort), then the posters. OK on a poster opens its page; a play starts there. A long OK
 * opens its menu: Mark watched or unwatched, Remove from collection, Select.
 *
 * The first focus: the switch's selected side; the poster a page was opened from, coming back from it; the title a
 * jump revealed; an empty side's, a filter's or a search's own button; the first poster without a TMDB key. Left from
 * a line's first element goes to the rail, and Right from the rail back to the element left. The paging keys move
 * three lines, or in A–Z a letter. Back: select mode and the field first, then, from posters below the first screen,
 * up to the switch; then the rail.
 */
@Composable
internal fun TvCollectionGrid(
    onShow: (Int) -> Unit,
    onMovie: (Int) -> Unit,
    onAdd: () -> Unit,
    onMetadata: () -> Unit,
    vm: CollectionViewModel,
    jump: CollectionJump?,
    onJumpDone: () -> Unit,
    backToSearch: Boolean,
) {
    val uiState = vm.ui.collectAsStateWithLifecycle()
    val ui by uiState
    val typed by vm.typed.collectAsStateWithLifecycle()
    ReportDrawnWhen { ui != null }
    val dpad = rememberDpad()
    val rail = LocalRailFocus.current
    val scope = rememberCoroutineScope()
    val shows = rememberPosterGridState()
    val movies = rememberPosterGridState()
    val focus = rememberCollectionFocus()
    val elements = remember { TvElements() }
    val gate = rememberFieldGate()
    val layout = remember(focus, elements, shows, movies, scope) { TvLayout(focus, elements, shows, movies, scope) { uiState.value } }
    CollectionFocusEffects(focus, vm, uiState, dpad, tv = true, jump, onJumpDone, backToSearch, layout)

    // Null while the library answers: the top line shows meanwhile, the same line as after, so a remote's way in from
    // the rail (OK on its Collection) has something to land on and keeps it as the rest comes.
    val current = ui
    val side = current?.side ?: Side.Shows
    val grid = layout.grid(side)
    // Which letter the letter grid starts on: the first title shown as Jump to letter is pressed.
    var gridLetter by remember { mutableStateOf<Char?>(null) }
    LaunchedEffect(grid) { snapshotFlow { grid.focusedPoster != null }.collect { focus.listFocused = it } }
    // From the posters below the first screen, past their first line, Back goes up to the switch first (one press to the
    // top of a long grid); from the first line it goes on to the rail, however far its focus scrolled the grid (the lines
    // above it tall at a large text size). Not right after a jump: one Back returns to Search.
    val belowTop by remember(grid) {
        derivedStateOf { grid.focusedPoster?.let { k -> uiState.value?.view?.rows?.indexOfFirst { it.key.value == k } }?.let { it >= GridPaging.COLUMNS } == true }
    }
    BackHandler(enabled = dpad && focus.listFocused && belowTop && current != null && current.selection == null && !backToSearch) { layout.focusTop(vm.ui.value) }
    // Back on Find's gate with nothing typed closes Find, the focus back on Find, where it was opened (the gate goes):
    // once the ui, built off the main thread, has it closed, so Find is there to take it.
    BackHandler(enabled = gate.gateFocused && current?.searchOpen == true && current.text.isEmpty() && current.selection == null) {
        vm.closeSearch()
        scope.launch {
            vm.ui.first { it?.searchOpen == false }
            layout.focusElement(FIND_PILL_KEY)
        }
    }

    val header = current?.let(::tvHeaderItems) ?: listOf(TV_TOP)
    val nextToAir = current?.side == Side.Shows && current.sortName == ShowSort.NextToAir.name
    val poster = remember(nextToAir) { { row: TitleRow -> row.toPoster(nextToAir) } }
    val noun = if (side == Side.Shows) "show" else "movie"
    // An element alone on its line, away from the column's start: Left goes to the rail.
    val wayIn = rail?.wayIn
    val element: Modifier.(String) -> Modifier = { key -> with(elements) { element(key) } }

    Box(
        Modifier.fillMaxSize().semantics { tvCollection = true }
            // A key or a touch: the user goes on from a revealed title, which isn't followed any more.
            .onPreviewKeyEvent { focus.follow = null; false },
    ) {
        PosterGrid(
            items = current?.view?.rows.orEmpty(), key = { it.key.value }, poster = poster, state = grid, artwork = current?.showArtwork == true,
            onOpen = { row ->
                if (current?.selection != null) vm.toggle(row.key)
                else {
                    vm.opened(row.key)
                    if (row.key.isShow) onShow(row.key.tmdbId) else onMovie(row.key.tmdbId)
                }
            },
            modifier = Modifier.fillMaxSize(),
            header = header,
            headerItem = { key ->
                if (key == TV_TOP) {
                    TopLine(
                        current, typed, gate, wayIn, element, openField = focus.focusField,
                        // Find is an explicit OK: the field opens with it, its keyboard up.
                        onFind = {
                            focus.focusField = true
                            vm.openSearch()
                        },
                        onFieldOpened = { focus.focusField = false },
                        onType = vm::type,
                        onClear = {
                            vm.type("")
                            layout.focusElement(FIND)
                        },
                        onFieldFocus = { focus.fieldFocused = it },
                        onSelect = {
                            vm.startSelect()
                            if (dpad) current?.view?.rows?.firstOrNull { row -> grid.grid.layoutInfo.visibleItemsInfo.any { it.key == row.key.value } }?.let { focus.focusKey = it.key }
                        },
                        // The keyboard's Search: on to the first match, the gate closed first (its keyboard going with the
                        // field), so the gate doesn't take the focus back as the keyboard goes; none, the gate.
                        onSubmit = {
                            gate.close()
                            current?.view?.rows?.firstOrNull()?.let { focus.focusKey = it.key }
                        },
                    )
                    return@PosterGrid
                }
                // Each other line is of the ui's own lines.
                current ?: return@PosterGrid
                when (key) {
                    TV_SIDES -> SidesLine(
                        current, wayIn, element,
                        onSide = { side ->
                            // Where it was left, unless the switch isn't there (a jump took that side down the grid): its top then.
                            val other = layout.grid(side).grid
                            if (other.firstVisibleItemIndex > 0) other.requestScrollToItem(0)
                            vm.switchSide(side)
                        },
                        onFilter = vm::setFilter,
                        noun = noun,
                    )
                    TV_HEADER -> HeaderLine(
                        current, wayIn, element,
                        onJump = {
                            // The letter of the first title shown, read now rather than as each scroll recomposes.
                            gridLetter = current.view.rows.getOrNull((grid.grid.firstVisibleItemIndex - header.size).coerceAtLeast(0))?.text?.letter
                            focus.letterGrid = true
                        },
                        onSort = vm::setSort,
                        onSelectAll = {
                            // Select mode's line takes this one's place, its own Select all there (Select none now): the
                            // focus on to it, once the ui has select mode, rather than gone with the pill pressed.
                            vm.selectAllVisible()
                            scope.launch {
                                vm.ui.first { it?.selection != null }
                                layout.focusElement(SELECT_ALL)
                            }
                        },
                    )
                    TV_SELECT -> SelectLine(
                        current, element,
                        onExit = {
                            // As Back from the actions: the focus to Select, where select mode was entered, rather than
                            // gone with the line.
                            vm.exitSelect()
                            scope.launch {
                                vm.ui.first { it?.selection == null }
                                layout.focusSelect()
                            }
                        },
                        onSelectAll = vm::selectAllVisible, onMark = vm::markSelected, onRemove = vm::removeSelected,
                    )
                    // Alone on its line: Left goes to the rail, and Right from the rail back to it.
                    TV_HINT -> NoKeyHint(current.side, onMetadata, Modifier.leftTo(wayIn).element(HINT))
                    TV_NOTE -> current.note?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    TV_EMPTY -> current.view.empty?.let { empty ->
                        EmptyState(
                            current.side, empty, current.view.chips.firstOrNull { it.selected }?.label.orEmpty(), current.hasKey, onFind = onAdd, onMetadata = onMetadata,
                            onShowAll = { focused ->
                                // Its button goes as the rows come: the focus to the header line first, then on to the first poster.
                                if (focused) {
                                    layout.focusElement(SORT)
                                    focus.focusFirstRow = true
                                }
                                vm.showAll()
                            },
                            // Alone on its line: Left goes to the rail.
                            button = Modifier.leftTo(wayIn).element(emptyButton(empty, current.hasKey)),
                        )
                    }
                }
            },
            footer = { Footnote(if (side == Side.Shows) SHOWS_FOOTNOTE else MOVIES_FOOTNOTE, Modifier.padding(top = 4.dp)) },
            marks = PosterMarks(
                selecting = current?.selection != null, selected = current?.selection?.keys.orEmpty().mapTo(HashSet()) { it.value },
                highlight = current?.highlight?.value, justAdded = current?.highlightAdded == true,
            ),
            // A long OK (no Play: a play starts on the title's page): Mark watched or unwatched by its state (neither for a
            // show TMDB hasn't answered for), Remove from collection (Undo then, as select mode's), Select (select mode
            // with it selected).
            menu = { row, first, close ->
                val items = listOfNotNull(
                    row.menuMark?.let { watched -> (if (watched) WATCHED_ACTION else UNWATCHED_ACTION) to { vm.markOne(row.key, watched) } },
                    REMOVE_ACTION to { vm.removeOne(row.key) },
                    SELECT_ACTION to { vm.startSelect(row.key) },
                )
                items.forEachIndexed { i, (label, act) ->
                    TvMenuItem(label, if (i == 0) first else Modifier) {
                        close()
                        act()
                    }
                }
            },
            actions = { row ->
                listOfNotNull(
                    CustomAccessibilityAction(SELECT_ACTION) { vm.startSelect(row.key); true }.takeIf { current?.selection == null },
                    CustomAccessibilityAction(REMOVE_ACTION) { vm.removeOne(row.key); true },
                )
            },
            // Not while typing: the field's keys are its own.
            paging = if (focus.fieldFocused || current == null) null else { from, forward ->
                val letters = current.view.letters
                if (letters != null) GridPaging.letters(from, letters.letters.mapNotNull { letters.first[it] }, forward)
                else GridPaging.lines(from, current.view.rows.size, forward)
            },
            onHeader = { layout.focusTop(vm.ui.value) },
            onEnterFromRail = layout::focusLast,
            onFocused = { elements.last = it.key.value },
            bottomPadding = if (current?.undo != null || current?.busy != null) UNDO_ROOM else 16.dp,
        )
        current ?: return@Box
        // A polite live region outside the grid, whose header a reveal far down scrolls away: TalkBack hears a title
        // just added without its focus moving.
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; current.announce?.let { contentDescription = it } })
        val bar = current.busy ?: current.undo?.message
        if (bar != null) {
            UndoBar(
                bar, focus.undoFocus, onUndo = if (current.busy == null) vm::undo else null,
                modifier = Modifier.align(Alignment.BottomCenter).tvFullWidth()
                    .onFocusChanged { focus.undoFocusChanged(it.hasFocus, offerLeft = vm.ui.value?.undo != null) }
                    .onPreviewKeyEvent { event ->
                        // Up from Undo goes back to the grid, where the removed titles were.
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && focus.undoFocused) {
                            focus.handFocusBack()
                            true
                        } else false
                    },
                undoModifier = Modifier.leftTo(wayIn),
            )
        }
    }
    if (focus.letterGrid && current != null) {
        LetterGrid(
            CollectionView.letterIndex(current.view.rows), gridLetter, noun,
            onPick = { letter ->
                focus.letterGrid = false
                if (current.view.letters == null) vm.setSort(if (current.side == Side.Shows) ShowSort.AZ.name else MovieSort.AZ.name)
                focus.pendingLetter = letter
            },
            onDismiss = { focus.letterGrid = false },
        )
    }
}

/** A title as the grid's poster shows it: its card line (under Next to air a show's air date), its bar, TalkBack's whole row. */
private fun TitleRow.toPoster(nextToAir: Boolean) = PosterItem(
    key = key.value, title = text.name, posterPath = posterPath, line = posterLine(nextToAir),
    tone = when ((this as? MovieRow)?.state) {
        MovieState.InProgress -> PosterTone.Accent
        MovieState.Watched -> PosterTone.Good
        else -> PosterTone.Muted
    },
    new = this is ShowRow && newEpisodes > 0, progress = posterProgress, spoken = a11y,
)

/**
 * What the TV's grid keeps of its own elements (not the posters, which [PosterGridState] keeps): a requester for each
 * (a fixed few: the pills, the switch's sides, the chips, an empty state's button), the element with the focus now
 * ([focused]) and the element last focused, a poster's by its title's key ([last]), for the way back from the rail.
 */
@Stable
private class TvElements {
    private val requesters = HashMap<String, FocusRequester>()
    var last: String? = null
    var focused: String? = null

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The element [key]: the focus can be put on it, and the grid keeps that it had it. */
    fun Modifier.element(key: String): Modifier = focusRequester(requester(key)).onFocusChanged {
        if (it.isFocused) {
            last = key
            focused = key
        } else if (focused == key) {
            focused = null
        }
    }
}

/** The TV's grid for the shared focus: its posters by key ([PosterGridState]), its own elements by [TvElements]' keys. */
private class TvLayout(
    private val focus: CollectionFocus,
    private val elements: TvElements,
    private val shows: PosterGridState,
    private val movies: PosterGridState,
    private val scope: CoroutineScope,
    private val ui: () -> CollectionUi?,
) : CollectionLayout {
    private val showsScroller = GridScroller(shows.grid)
    private val moviesScroller = GridScroller(movies.grid)
    private var focusing: Job? = null

    fun grid(side: Side): PosterGridState = if (side == Side.Movies) movies else shows

    override fun scroller(side: Side): TitleScroller = if (side == Side.Movies) moviesScroller else showsScroller
    override fun itemsBefore(ui: CollectionUi): Int = tvItemsBefore(ui)
    override fun focusTitle(key: TitleKey): Boolean = grid(if (key.isShow) Side.Shows else Side.Movies).focusPoster(key.value)

    override fun focusStart(ui: CollectionUi) {
        val empty = ui.view.empty
        val first = ui.view.rows.firstOrNull()
        when {
            empty != null -> emptyButton(empty, ui.hasKey).takeIf { it != FIND || ui.searchOpen }?.let(::focusElement) ?: focusTop(ui)
            // Without a key the hint row is above them: the first poster, so the hint is passed over, not stopped at.
            !ui.hasKey && first != null -> focus.focusKey = first.key
            else -> focusTop(ui)
        }
    }

    /**
     * Above the posters (the paging keys from the first line), and where the focus goes when nothing is left to hand it
     * to: the switch's selected side, the tab's first focus; in select mode, which hides it (the tab returned to with
     * select mode on, too), the select line's first action, or its ✕ while nothing is selected (the actions are off then).
     */
    override fun focusTop(ui: CollectionUi?) {
        val selection = ui?.selection
        focusElement(
            when {
                selection == null -> switchKey(ui?.side ?: Side.Shows)
                selection.count > 0 -> MARK_WATCHED
                else -> EXIT_SELECT
            },
        )
    }
    override fun focusMissedJump() = focusTop(ui())
    override fun focusActions() = focusElement(MARK_WATCHED)
    override fun focusSelect() = focusElement(SELECT)

    // At the pivot's line, where the focus takes a poster anyway.
    override fun reveal(viewport: Int, size: Int, offset: Int): Float = offset - viewport * PivotBringIntoViewSpec.LINE

    /**
     * The element [key] focused: the grid scrolled to its top first (every element of the grid's own is in its header),
     * then asked a frame at a time until it has it, its line being composed only once in view.
     */
    fun focusElement(key: String) {
        focusing?.cancel()
        // An element gone with the focus (its line replaced under it) may not have said so: asked again.
        if (elements.focused == key) elements.focused = null
        focusing = scope.launch {
            val side = ui()?.side ?: Side.Shows
            val grid = grid(side).grid
            if (grid.firstVisibleItemIndex > 0) grid.requestScrollToItem(0)
            repeat(ELEMENT_FOCUS_FRAMES) {
                withFrameNanos {}
                if (elements.focused == key) return@launch
                elements.requester(key).tryFocus()
            }
        }
    }

    /** Right from the rail: the element of the grid's own last focused; false for a poster's, which the grid brings back itself. */
    fun focusLast(): Boolean {
        val key = elements.last ?: return false
        return TitleKey.parse(key) == null && elements.requester(key).tryFocus()
    }
}

/** Left goes to the rail ([wayIn], the current tab's item), for an element alone on its line; nothing on a page. */
private fun Modifier.leftTo(wayIn: FocusRequester?): Modifier = focusProperties { wayIn?.let { left = it } }

/** The empty state's button, by its element's key. */
private fun emptyButton(empty: Empty, hasKey: Boolean): String = when (empty) {
    Empty.NoTitles -> if (hasKey) FIND_A_TITLE else ADD_KEY
    is Empty.FilterEmpty -> SHOW_ALL
    is Empty.SearchEmpty -> if (empty.elsewhere > 0) SHOW_ALL else FIND
}

private fun switchKey(side: Side) = SWITCH + side.name

/**
 * "Collection", Find, Select; with the search open, in their place the field behind its gate (a remote's focus raises no
 * keyboard on it), opened as Find opens it ([openField], then [onFieldOpened]), and Clear beside it while there are words:
 * inside the gate, the field's own clear button would be out of the remote's reach.
 */
@Composable
private fun TopLine(
    ui: CollectionUi?,
    typed: String,
    gate: FieldGate,
    wayIn: FocusRequester?,
    element: Modifier.(String) -> Modifier,
    openField: Boolean,
    onFind: () -> Unit,
    onFieldOpened: () -> Unit,
    onType: (String) -> Unit,
    onClear: () -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onSubmit: () -> Unit,
) {
    val noun = if (ui?.side == Side.Movies) "movie" else "show"
    Row(
        Modifier.fillMaxWidth().padding(bottom = LINE_GAP).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui?.searchOpen == true) {
            TvFieldGate(gate, findLabel(noun), typed, Modifier.leftTo(wayIn).element(FIND).weight(1f), shape = RoundedCornerShape(20.dp)) { field ->
                TextField(
                    value = typed, onValueChange = onType, singleLine = true,
                    placeholder = { Text(findLabel(noun), style = AppType.body, color = colors.muted) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.muted) },
                    textStyle = AppType.body.copy(color = colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = colors.accent,
                    ),
                    modifier = field.onFocusChanged { onFieldFocus(it.isFocused) },
                )
            }
            // Once the field is composed, a frame at a time until it has the focus.
            LaunchedEffect(openField) {
                if (!openField) return@LaunchedEffect
                for (frame in 1..FIELD_OPEN_FRAMES) {
                    withFrameNanos {}
                    if (gate.isOpen || gate.open()) break
                }
                onFieldOpened()
            }
            if (typed.isNotEmpty()) TvPill(CLEAR, Modifier.element(CLEAR_KEY), said = CLEAR_SAID, onClick = onClear)
        } else {
            Text(SCREEN_TITLE, style = AppType.screenTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The first on its line, away from the column's start: Left goes to the rail.
            TvPill(FIND_PILL, Modifier.leftTo(wayIn).element(FIND_PILL_KEY), icon = Icons.Filled.Search, said = ui?.let { "Find in ${it.view.total} ${noun(noun, it.view.total)}" }, onClick = onFind)
        }
        TvPill(SELECT_PILL, Modifier.element(SELECT), onClick = onSelect)
    }
}

/** Shows | Movies with each side's count, then the side's filter chips, sideways. */
@Composable
private fun SidesLine(ui: CollectionUi, wayIn: FocusRequester?, element: Modifier.(String) -> Modifier, onSide: (Side) -> Unit, onFilter: (String) -> Unit, noun: String) {
    // The chips' room at their row's start comes off the gap after the switch: the first chip starts where it would without.
    Row(Modifier.fillMaxWidth().padding(bottom = LINE_GAP), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp - CHIP_ROOM)) {
        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(colors.surface).padding(3.dp)) {
            Side.entries.forEach { side ->
                val label = ui.totals[side]?.let { "${side.name} $it" } ?: side.name
                SwitchSide(label, side == ui.side, Modifier.element(switchKey(side)).then(if (side == Side.Shows) Modifier.leftTo(wayIn) else Modifier)) { onSide(side) }
            }
        }
        // Each side's chips from their start.
        androidx.compose.runtime.key(ui.side) { FilterChipsLine(ui.view.chips, noun, element, onFilter, Modifier.weight(1f)) }
    }
}

/** The side's filter chips with their counts, as TV chips, sideways; one with nothing stays, dimmed, and can still be picked. */
@Composable
private fun FilterChipsLine(chips: List<Chip>, noun: String, element: Modifier.(String) -> Modifier, onPick: (String) -> Unit, modifier: Modifier) {
    // Sideways, only as far as a chip needs to show: the grid's pivot is for the grid. Room at the row's ends for a focused
    // chip's growth, which the row would clip, and the chip brought in with it.
    EdgeScrolling(room = CHIP_ROOM) {
        LazyRow(modifier, contentPadding = PaddingValues(horizontal = CHIP_ROOM), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it.name }) { chip ->
                TvChip(
                    "${chip.label} ${chip.count}", chip.selected,
                    Modifier.element(CHIP + chip.name).semantics { contentDescription = "${chip.label}, ${chip.count} ${noun(noun, chip.count)}" },
                    style = AppType.secondary, role = Role.RadioButton, dimmed = chip.count == 0,
                ) { onPick(chip.name) }
            }
        }
    }
}

/** The header line: "214 shows · 12 with new episodes", Jump to letter, the sort and its menu, and Select all in Finished. */
@Composable
private fun HeaderLine(ui: CollectionUi, wayIn: FocusRequester?, element: Modifier.(String) -> Modifier, onJump: () -> Unit, onSort: (String) -> Unit, onSelectAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = HEADER_GAP).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ui.view.header, style = AppType.secondary, color = colors.muted, modifier = Modifier.weight(1f))
        val jump = ui.view.total > 0
        // The first on its line, away from the column's start: Left goes to the rail.
        if (jump) TvPill(JUMP_TO_LETTER, Modifier.leftTo(wayIn).element(JUMP), style = AppType.secondary, onClick = onJump)
        var open by remember { mutableStateOf(false) }
        Box {
            TvPill(
                "${ui.sortLabel} ▾", (if (jump) Modifier else Modifier.leftTo(wayIn)).element(SORT).semantics { contentDescription = "Sort: ${ui.sortLabel}" },
                style = AppType.secondary,
            ) { open = true }
            TvMenu(open, onDismiss = { open = false }) { first ->
                ui.sorts.forEachIndexed { i, (name, label) ->
                    TvMenuItem(label, if (i == 0) first else Modifier, selected = name == ui.sortName) {
                        open = false
                        onSort(name)
                    }
                }
            }
        }
        // Select mode with every title selected; its line, in this one's place, has Select none.
        val finished = ui.filterName == ShowFilter.Finished.name && ui.side == Side.Shows
        if (finished && ui.view.rows.isNotEmpty()) TvPill("Select all", Modifier.element(SELECT_ALL), style = AppType.secondary, onClick = onSelectAll)
    }
}

/** Select mode's line, in place of the top lines: ✕, how many are selected, the actions, Select all. */
@Composable
private fun SelectLine(
    ui: CollectionUi,
    element: Modifier.(String) -> Modifier,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onMark: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val selection = ui.selection ?: return
    val noun = if (ui.side == Side.Shows) "show" else "movie"
    val count = selection.count
    Row(
        Modifier.fillMaxWidth().padding(bottom = HEADER_GAP).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvPill("", Modifier.element(EXIT_SELECT), icon = Icons.Filled.Close, said = "Leave select mode", onClick = onExit)
        Text(
            "$count selected", style = AppType.cardTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$count ${noun(noun, count)} selected" },
        )
        TvPill(WATCHED_ACTION, Modifier.element(MARK_WATCHED), enabled = count > 0) { onMark(true) }
        TvPill(UNWATCHED_ACTION, Modifier.element(MARK_UNWATCHED), enabled = count > 0) { onMark(false) }
        TvPill("Remove", Modifier.element(REMOVE), enabled = count > 0, said = "Remove $count ${noun(noun, count)} from the collection", onClick = onRemove)
        TvPill(if (selection.allSelected) "Select none" else "Select all (${ui.view.rows.size})", Modifier.element(SELECT_ALL), onClick = onSelectAll)
    }
}

/**
 * The TV's Collection content, which the tests know it by: they wait for the focus inside it, whatever its first focus is
 * (the switch, an empty state's button, the first poster), and not on the rail.
 */
val TvCollectionKey = SemanticsPropertyKey<Boolean>("TvCollection")
private var SemanticsPropertyReceiver.tvCollection by TvCollectionKey

/** What a TV's Find says it searches, and its field's gate is called. */
internal fun findLabel(noun: String) = "Search ${noun}s"

/** What the TV's grid says; the tests match these. */
internal const val SCREEN_TITLE = "Collection"
internal const val FIND_PILL = "Find"
internal const val SELECT_PILL = "Select"
internal const val JUMP_TO_LETTER = "Jump to letter"
internal const val CLEAR = "Clear"
internal const val CLEAR_SAID = "Clear the search"
internal const val SELECT_ACTION = "Select"
internal const val REMOVE_ACTION = "Remove from collection"
internal const val WATCHED_ACTION = "Mark watched"
internal const val UNWATCHED_ACTION = "Mark unwatched"
internal const val SHOWS_FOOTNOTE = "Episode lists and air dates from TMDB."
internal const val MOVIES_FOOTNOTE = "Release dates and runtimes from TMDB."

/** The grid's own elements' keys, which no title's is. */
private const val SWITCH = "switch:"
private const val CHIP = "chip:"
private const val FIND = "find"
private const val CLEAR_KEY = "clear"
private const val FIND_PILL_KEY = "find-pill"
private const val SELECT = "select"
private const val JUMP = "jump"
private const val SORT = "sort"
private const val SELECT_ALL = "select-all"
private const val FIND_A_TITLE = "find-a-title"
private const val ADD_KEY = "add-key"
private const val SHOW_ALL = "show-all"
private const val EXIT_SELECT = "exit-select"
private const val MARK_WATCHED = "mark-watched"
private const val MARK_UNWATCHED = "mark-unwatched"
private const val REMOVE = "remove"
private const val HINT = "hint"

/** Between the header's lines, and under the last of them before the posters (the mockup's 12 and 16). */
private val LINE_GAP = 12.dp
private val HEADER_GAP = 16.dp

/** At the chips' row's ends: more than a focused chip's growth past its end (1.05 times, of the widest chip). */
private val CHIP_ROOM = 4.dp

/** Under the posters while the Undo bar shows, so the last line can come up above it. */
private val UNDO_ROOM = 76.dp

/** A frame at a time, this many, for an element of the header to be composed and take the focus; Find's field the same. */
private const val ELEMENT_FOCUS_FRAMES = 10
private const val FIELD_OPEN_FRAMES = 10

package io.skjaere.usenet.app.collection

import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.CollectionView
import io.skjaere.usenet.app.collection.model.Empty
import io.skjaere.usenet.app.collection.model.MovieSort
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowSort
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.tvFocusMemory
import kotlinx.coroutines.launch

/** Rows before the first title: the side switch, the chips and the header line (the hint, a note and an empty state add to it). */
private const val HEADER_ITEMS = 3

/**
 * The Collection tab (Dense D): Shows | Movies, filter chips with counts, the header line and sort, one
 * lazy row per title; search in the top bar; select mode with Mark watched, Mark unwatched and Remove
 * (Undo for each). A remote reaches all of it ([dpad]: no touch screen, or the D-pad in use); TalkBack
 * hears each row whole. [vm] is the app's; device tests hand in one over their own library.
 *
 * [jump]: a title "+ Collection" just added on Search, brought into view and highlighted, then
 * [onJumpDone]. While [backToSearch], Back returns there (AppNav's handler): a remote's Back from the
 * list doesn't stop at the top bar first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(
    onShow: (Int) -> Unit,
    onMovie: (Int) -> Unit,
    onAdd: () -> Unit,
    onMetadata: () -> Unit,
    vm: CollectionViewModel = collectionViewModel(),
    jump: CollectionJump? = null,
    onJumpDone: () -> Unit = {},
    backToSearch: Boolean = false,
) {
    // A TV's own layout, a grid of posters; phones and tablets keep the list.
    if (LocalFormFactor.current == FormFactor.Tv) {
        TvCollectionGrid(onShow, onMovie, onAdd, onMetadata, vm, jump, onJumpDone, backToSearch)
        return
    }
    val uiState = vm.ui.collectAsStateWithLifecycle()
    val ui by uiState
    val typed by vm.typed.collectAsStateWithLifecycle()
    ReportDrawnWhen { ui != null }
    val dpad = rememberDpad()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The Cast button in the bar on phones and tablets (L115): Select and + Add make room as icons there.
    val castButton = !LocalContext.current.container.television

    val showsList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val moviesList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val focus = rememberCollectionFocus()
    val layout = remember(focus, showsList, moviesList) { PhoneLayout(focus, ListScroller(showsList), ListScroller(moviesList)) }
    var scrubbing by remember { mutableStateOf<Char?>(null) }

    val current = ui
    val list = if (current?.side == Side.Movies) moviesList else showsList
    val headerItems = current?.let(::phoneItemsBefore) ?: HEADER_ITEMS
    CollectionFocusEffects(focus, vm, uiState, dpad, tv = false, jump, onJumpDone, backToSearch, layout)

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A key or a touch: the user goes on from a revealed title, which isn't followed any more.
            .onPreviewKeyEvent { focus.follow = null; false }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); focus.follow = null } },
    ) {
        // Wide screens (TV, tablets): one centred column until the grids of a later phase.
        val wide = maxWidth > 840.dp
        val compact = LocalDensity.current.fontScale >= 1.5f || maxWidth < 360.dp
        Column(Modifier.align(Alignment.TopCenter).then(if (wide) Modifier.width(720.dp) else Modifier.fillMaxWidth()).fillMaxSize()) {
            val selection = current?.selection
            if (current != null && selection != null) {
                SelectBar(current, selection, compact, focus.firstPill, onExit = vm::exitSelect, onSelectAll = vm::selectAllVisible, onMark = vm::markSelected, onRemove = vm::removeSelected)
            } else {
                CollectionTopBar(
                    current, typed, compact = compact || castButton, focus.searchFocus, focus.topFocus, focus.focusField,
                    onOpenSearch = { focus.focusField = true; vm.openSearch() }, onType = vm::type, onCloseSearch = vm::closeSearch,
                    onFieldFocus = { focus.fieldFocused = it }, onFieldFocused = { focus.focusField = false },
                    onSelect = {
                        vm.startSelect()
                        if (dpad) current?.view?.rows?.firstOrNull { row -> list.layoutInfo.visibleItemsInfo.any { it.key == row.key.value } }?.let { focus.focusKey = it.key }
                    },
                    onAdd = onAdd,
                    // The keyboard's Search: on a remote on to the first match, else the top bar.
                    onSubmit = {
                        keyboard?.hide()
                        val first = current?.view?.rows?.firstOrNull()
                        when {
                            !dpad -> focusManager.clearFocus()
                            first != null -> focus.focusKey = first.key
                            else -> runCatchingFocus { focus.topFocus.requestFocus() }
                        }
                    },
                )
            }
            val ready = current ?: return@Column
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val rail = !dpad && ready.view.letters != null && ready.view.rows.size > RAIL_MIN_ROWS
                // One list for both sides, each with its own scroll position: the switch, the chips and the
                // header line stay as the side changes, and so does the focus on the switch (a remote's, TalkBack's).
                SideList(
                    ready, list, headerItems, rail, dpad, focus.focusKey, focus.rowFocus, focus.jumpFocus, focus.sortFocus,
                    Modifier.fillMaxSize().onFocusChanged { focus.listFocused = it.hasFocus }.tvFocusMemory(),
                    onSide = { side ->
                        // Where it was left, unless the switch isn't there (a jump took that side down the list): its top then.
                        val other = if (side == Side.Movies) moviesList else showsList
                        if (other.firstVisibleItemIndex > 0) other.requestScrollToItem(0)
                        vm.switchSide(side)
                    },
                    onFilter = vm::setFilter, onSort = vm::setSort,
                    onJump = { focus.letterGrid = true }, onSelectAll = vm::selectAllVisible, onMetadata = onMetadata, onFind = onAdd,
                    onShowAll = { focused ->
                        // Its button goes as the rows come: the focus to the header line first (not to the top
                        // bar, nor the search field and its keyboard), then on to the first row.
                        if (focused) {
                            runCatchingFocus { focus.sortFocus.requestFocus() }
                            focus.focusFirstRow = true
                        }
                        vm.showAll()
                    },
                    onRow = { row ->
                        when {
                            ready.selection != null -> vm.toggle(row.key)
                            else -> {
                                vm.opened(row.key)
                                if (row.key.isShow) onShow(row.key.tmdbId) else onMovie(row.key.tmdbId)
                            }
                        }
                    },
                    onLongRow = { vm.startSelect(it.key) },
                    onSelectRow = { vm.startSelect(it.key) },
                    onRemoveRow = { vm.removeOne(it.key) },
                )
                if (rail) {
                    LetterRail(
                        ready.view.letters,
                        onLetter = { letter -> scope.launch { list.scrollToItem(headerItems + ready.view.letters.indexFor(letter)) } },
                        onDrag = { scrubbing = it },
                        onOpenGrid = { focus.letterGrid = true },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                scrubbing?.let { LetterBubble(it, Modifier.align(Alignment.Center)) }
                // A polite live region outside the list, whose header a reveal far down scrolls away:
                // TalkBack hears a title just added without its focus moving.
                Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; ready.announce?.let { contentDescription = it } })
                val bar = ready.busy ?: ready.undo?.message
                if (bar != null) {
                    UndoBar(
                        bar, focus.undoFocus, onUndo = if (ready.busy == null) vm::undo else null,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .onFocusChanged { focus.undoFocusChanged(it.hasFocus, offerLeft = vm.ui.value?.undo != null) }
                            .onPreviewKeyEvent { event ->
                                // Up from Undo goes back to the list, where the removed rows were.
                                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && focus.undoFocused) {
                                    focus.handFocusBack()
                                    true
                                } else false
                            },
                    )
                }
            }
        }
        if (focus.letterGrid && current != null) {
            val first = current.view.rows.getOrNull((list.firstVisibleItemIndex - headerItems).coerceAtLeast(0))
            LetterGrid(
                CollectionView.letterIndex(current.view.rows), first?.text?.letter, if (current.side == Side.Shows) "show" else "movie",
                onPick = { letter ->
                    focus.letterGrid = false
                    if (current.view.letters == null) vm.setSort(if (current.side == Side.Shows) ShowSort.AZ.name else MovieSort.AZ.name)
                    focus.pendingLetter = letter
                },
                onDismiss = { focus.letterGrid = false },
            )
        }
    }
}

/** The list's items before its first row: the switch, the chips, the header line, then the hint, a note and an empty state when shown. */
private fun phoneItemsBefore(ui: CollectionUi): Int = HEADER_ITEMS + listOf(!ui.hasKey, ui.note != null, ui.view.empty != null).count { it }

/** A phone's and a tablet's list for the shared focus: its rows take the one moving [CollectionFocus.rowFocus]; the top bar is ⌕, or Select while the search is open. */
private class PhoneLayout(private val focus: CollectionFocus, private val shows: TitleScroller, private val movies: TitleScroller) : CollectionLayout {
    override fun scroller(side: Side): TitleScroller = if (side == Side.Movies) movies else shows
    override fun itemsBefore(ui: CollectionUi): Int = phoneItemsBefore(ui)
    override fun focusTitle(key: TitleKey): Boolean = focus.rowFocus.tryFocus()

    // Select while the search is open (⌕ isn't there, and focus left nowhere would land on the field and its keyboard).
    override fun focusStart(ui: CollectionUi) = focusTop(ui)
    override fun focusTop(ui: CollectionUi?) = runCatchingFocus { if (ui?.searchOpen == true) focus.topFocus.requestFocus() else focus.searchFocus.requestFocus() }
    override fun focusMissedJump() = runCatchingFocus { focus.searchFocus.requestFocus() }
    override fun focusActions() = runCatchingFocus { focus.firstPill.requestFocus() }
    override fun focusSelect() = runCatchingFocus { focus.topFocus.requestFocus() }
    override fun reveal(viewport: Int, size: Int, offset: Int): Float = centred(viewport, size, offset)
}

@Composable
private fun collectionViewModel(): CollectionViewModel {
    val container = LocalContext.current.container
    return viewModel { CollectionViewModel(CollectionDeps.of(container), createSavedStateHandle()) }
}

/** No touch screen (a TV), or a remote or keyboard in use. */
@Composable
internal fun rememberDpad(): Boolean {
    val context = LocalContext.current
    val touch = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    return !touch || LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

@Composable
private fun SideList(
    ui: CollectionUi,
    list: LazyListState,
    headerItems: Int,
    rail: Boolean,
    dpad: Boolean,
    focusKey: TitleKey?,
    rowFocus: FocusRequester,
    jumpFocus: FocusRequester,
    sortFocus: FocusRequester,
    modifier: Modifier,
    onSide: (Side) -> Unit,
    onFilter: (String) -> Unit,
    onSort: (String) -> Unit,
    onJump: () -> Unit,
    onSelectAll: () -> Unit,
    onMetadata: () -> Unit,
    onFind: () -> Unit,
    onShowAll: (focused: Boolean) -> Unit,
    onRow: (TitleRow) -> Unit,
    onLongRow: (TitleRow) -> Unit,
    onSelectRow: (TitleRow) -> Unit,
    onRemoveRow: (TitleRow) -> Unit,
) {
    val rows = ui.view.rows
    val keys = ui.selection?.keys.orEmpty()
    val noun = if (ui.side == Side.Shows) "show" else "movie"
    LazyColumn(
        modifier, state = list,
        contentPadding = PaddingValues(end = if (rail) 40.dp else 0.dp, bottom = if (ui.undo != null || ui.busy != null) 76.dp else 8.dp),
    ) {
        item(key = "switch", contentType = "switch") { SideSwitch(ui.side, onSide) }
        // Each side's chips from their start.
        item(key = "chips", contentType = "chips") { key(ui.side) { FilterChips(ui.view.chips, noun, onFilter) } }
        item(key = "header", contentType = "header") { HeaderLine(ui, dpad, jumpFocus, sortFocus, onSort, onJump, onSelectAll) }
        // An empty side says it itself, with its own button.
        if (!ui.hasKey && ui.view.empty != Empty.NoTitles) item(key = "hint", contentType = "hint") { NoKeyHint(ui.side, onMetadata) }
        ui.note?.let { note -> item(key = "note", contentType = "note") { Text(note, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) } }
        ui.view.empty?.let { empty ->
            item(key = "empty", contentType = "empty") {
                EmptyState(ui.side, empty, ui.view.chips.firstOrNull { it.selected }?.label.orEmpty(), ui.hasKey, onFind, onMetadata, onShowAll)
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key.value }, contentType = { _, row -> if (row is ShowRow) "show" else "movie" }) { i, row ->
            TitleRowItem(
                row, first = i == 0, last = i == rows.lastIndex, selecting = ui.selection != null, selected = row.key in keys,
                highlighted = row.key == ui.highlight, justAdded = row.key == ui.highlight && ui.highlightAdded,
                artwork = ui.showArtwork, dateChip = ui.sortName == ShowSort.NextToAir.name,
                focus = if (row.key == focusKey) rowFocus else null,
                onClick = { onRow(row) }, onLongClick = { onLongRow(row) }, onSelect = { onSelectRow(row) }, onRemove = { onRemoveRow(row) },
            )
        }
        item(key = "footnote", contentType = "footnote") {
            Footnote(if (ui.side == Side.Shows) "Episode lists and air dates from TMDB." else "Release dates and runtimes from TMDB.", Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * "Collection", the search button, Select and + Add, then the Cast button (CsPhonePlaces); with the search open, the
 * field ([typed]) in place of the title, focused once [focusField] (then [onFieldFocused]). Short of room ([compact]: a
 * phone, whose bar has the Cast button, or a narrow screen) Select and + Add show as icons, read as their words.
 */
@Composable
private fun CollectionTopBar(
    ui: CollectionUi?,
    typed: String,
    compact: Boolean,
    searchFocus: FocusRequester,
    topFocus: FocusRequester,
    focusField: Boolean,
    onOpenSearch: () -> Unit,
    onType: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onFieldFocused: () -> Unit,
    onSelect: () -> Unit,
    onAdd: () -> Unit,
    onSubmit: () -> Unit,
) {
    val noun = if (ui?.side == Side.Movies) "movie" else "show"
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ui?.searchOpen == true) {
            SearchField(typed, noun, focusField, onType, onCloseSearch, onFieldFocus, onFieldFocused, onSubmit, Modifier.weight(1f))
        } else {
            Text(
                "Collection", style = AppType.screenTitle.copy(fontSize = 24.sp, letterSpacing = (-0.4).sp), color = colors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onOpenSearch,
                modifier = Modifier.size(48.dp).focusRequester(searchFocus).focusOutline(CircleShape, fill = false),
            ) { Icon(Icons.Filled.Search, contentDescription = "Search ${ui?.view?.total ?: 0} ${noun(noun, ui?.view?.total ?: 0)}", tint = colors.text) }
        }
        TopPill("Select", Icons.Filled.CheckCircle, compact, Modifier.focusRequester(topFocus), onSelect)
        TopPill("Add", Icons.Filled.Add, compact, onClick = onAdd, label = "+ Add")
        val container = LocalContext.current.container
        val playOn by container.playOn.target.collectAsStateWithLifecycle()
        CastButton(playOn, { container.playOn.openSheet() })
    }
}

/** A Dense pill (40 dp, chip fill, 48 dp target); an icon alone with its description when the bar is short of room. */
@Composable
private fun TopPill(name: String, icon: ImageVector, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, label: String = name) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(24.dp), fill = false)
            .semantics { contentDescription = if (name == "Add") "Add to the collection" else name }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).background(colors.chip, shape).padding(horizontal = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            else Text(label, style = AppType.body.copy(fontSize = 14.sp), color = colors.text, maxLines = 1)
        }
    }
}

@Composable
private fun SearchField(
    text: String,
    noun: String,
    autoFocus: Boolean,
    onType: (String) -> Unit,
    onClose: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onAutoFocused: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    TextField(
        value = text,
        onValueChange = onType,
        singleLine = true,
        placeholder = { Text("Search ${noun}s", style = AppType.body, color = colors.muted) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.muted) },
        trailingIcon = {
            IconButton(onClick = { if (text.isEmpty()) onClose() else onType("") }) {
                Icon(Icons.Filled.Close, if (text.isEmpty()) "Close search" else "Clear search", tint = colors.muted)
            }
        },
        textStyle = AppType.body.copy(color = colors.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = colors.accent,
        ),
        modifier = modifier.focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
    )
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatchingFocus { focus.requestFocus() }
            onAutoFocused()
        }
    }
}

/**
 * Select mode's bar: ✕, the count, Select all; under it the actions, which wrap at large font sizes.
 * Short of room ([compact]) Select all goes down with the actions: the count, what the bar is for,
 * keeps its line ("Select all (120)" beside it left "0" over "sele…" on a narrow phone at 200 %).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectBar(ui: CollectionUi, selection: SelectionUi, compact: Boolean, firstPill: FocusRequester, onExit: () -> Unit, onSelectAll: () -> Unit, onMark: (Boolean) -> Unit, onRemove: () -> Unit) {
    val noun = if (ui.side == Side.Shows) "show" else "movie"
    val count = selection.count
    val selectAll = if (selection.allSelected) "Select none" else "Select all (${ui.view.rows.size})"
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, modifier = Modifier.size(48.dp).focusOutline(CircleShape, fill = false)) {
                Icon(Icons.Filled.Close, contentDescription = "Leave select mode", tint = colors.text)
            }
            Text(
                "$count selected", style = AppType.cardTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { contentDescription = "$count ${noun(noun, count)} selected" },
            )
            if (!compact) TextAction(selectAll, onClick = onSelectAll)
        }
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill("Mark watched", Icons.Filled.Check, count > 0, Modifier.focusRequester(firstPill)) { onMark(true) }
            ActionPill("Mark unwatched", null, count > 0) { onMark(false) }
            ActionPill("Remove", Icons.Filled.Delete, count > 0, said = "Remove $count ${noun(noun, count)} from the collection", onClick = onRemove)
            if (compact) TextAction(selectAll, onClick = onSelectAll)
        }
    }
}

@Composable
private fun ActionPill(text: String, icon: ImageVector?, enabled: Boolean, modifier: Modifier = Modifier, said: String? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(24.dp), fill = false).then(said?.let { s -> Modifier.semantics { contentDescription = s } } ?: Modifier),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.markOutline),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = if (enabled) colors.accent else colors.muted, modifier = Modifier.size(18.dp).padding(end = 2.dp)) }
        Text(text, style = AppType.body.copy(fontSize = 14.sp), color = if (enabled) colors.text else colors.muted)
    }
}

private const val RAIL_MIN_ROWS = 40

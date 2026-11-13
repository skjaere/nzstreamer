package io.skjaere.usenet.app.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.ui.rememberInitialFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The collection's focus and scrolling, a phone's list's and a TV's grid's alike (TV-4's L1): the first focus, a row to
 * focus scrolled into view first, Undo taking the focus and handing it back, a jump's reveal and its follow, the rows on
 * screen for the refresher, a letter picked, and Back, innermost first. Each layout says where its lines are and how
 * its elements take the focus ([CollectionLayout]); the phone suites guard this for both.
 */

/** An item the lazy layout has laid out: its [key], its place among the items, and where it is along the scroll. */
internal class ShownItem(val key: Any, val index: Int, val offset: Int, val size: Int)

/** What the collection's scrolling needs of the lazy layout its titles are in: a phone's list, a TV's grid. */
internal interface TitleScroller {
    /** The items laid out now; read in a snapshot, its changes are seen. */
    val shown: List<ShownItem>
    val viewport: Int
    val firstVisibleItemIndex: Int
    fun requestScrollToItem(index: Int)
    suspend fun scrollToItem(index: Int)
    suspend fun scrollBy(px: Float)
}

internal class ListScroller(private val list: LazyListState) : TitleScroller {
    override val shown: List<ShownItem> get() = list.layoutInfo.visibleItemsInfo.map { ShownItem(it.key, it.index, it.offset, it.size) }
    override val viewport: Int get() = list.layoutInfo.viewportSize.height
    override val firstVisibleItemIndex: Int get() = list.firstVisibleItemIndex
    override fun requestScrollToItem(index: Int) = list.requestScrollToItem(index)
    override suspend fun scrollToItem(index: Int) = list.scrollToItem(index)
    override suspend fun scrollBy(px: Float) {
        list.scrollBy(px)
    }
}

internal class GridScroller(private val grid: LazyGridState) : TitleScroller {
    override val shown: List<ShownItem> get() = grid.layoutInfo.visibleItemsInfo.map { ShownItem(it.key, it.index, it.offset.y, it.size.height) }
    override val viewport: Int get() = grid.layoutInfo.viewportSize.height
    override val firstVisibleItemIndex: Int get() = grid.firstVisibleItemIndex
    override fun requestScrollToItem(index: Int) = grid.requestScrollToItem(index)
    override suspend fun scrollToItem(index: Int) = grid.scrollToItem(index)
    override suspend fun scrollBy(px: Float) {
        grid.scrollBy(px)
    }
}

/**
 * Where a layout's lines are and how its elements take the focus, for [rememberCollectionFocus]. Each focus hook does what
 * it can now; a TV's, whose element may sit in a line not composed yet, brings it in first on its own.
 */
internal interface CollectionLayout {
    /** The side's lazy layout. The same instance each time: the rows on screen are watched by it. */
    fun scroller(side: Side): TitleScroller

    /** The items before the first title's, as [ui] shows them. */
    fun itemsBefore(ui: CollectionUi): Int

    /** The title [key]'s element takes the focus now; false when it isn't composed (yet). */
    fun focusTitle(key: TitleKey): Boolean

    /** The tab's first focus, with a remote, when no row is to be focused. */
    fun focusStart(ui: CollectionUi)

    /** Where the focus goes when the row it was to go back to isn't shown any more ([ui] as it is now). */
    fun focusTop(ui: CollectionUi?)

    /** A jump's title didn't show in time: where the tab's focus starts. */
    fun focusMissedJump()

    /** Select mode's first action. */
    fun focusActions()

    /** Select, where select mode is entered, as it ends from its actions; also off the search field. */
    fun focusSelect()

    /** Where a revealed title is brought to: the scroll that takes the title's top from [offset] (its [size] tall) there, in [viewport]. */
    fun reveal(viewport: Int, size: Int, offset: Int): Float
}

/**
 * What the collection's focus is doing, for its layout: the row to focus ([focusKey], by [rowFocus] on a phone), the
 * jump's title kept in view ([follow]), whether the list, the search field or Undo has the focus, the letter grid
 * ([letterGrid]) and a letter picked ([pendingLetter]); and the requesters the layouts share.
 */
@Stable
internal class CollectionFocus(letterGrid: MutableState<Boolean>) {
    val searchFocus = FocusRequester()
    val topFocus = FocusRequester()
    val firstPill = FocusRequester()
    val undoFocus = FocusRequester()
    val jumpFocus = FocusRequester()
    val sortFocus = FocusRequester()
    val rowFocus = FocusRequester()
    var focusKey by mutableStateOf<TitleKey?>(null)

    /** A title a jump revealed, kept in view while it is highlighted: until the user scrolls or presses a key. */
    var follow by mutableStateOf<TitleKey?>(null)

    /** The empty state's Show all went with the focus: on to the first row once the rows are there. */
    var focusFirstRow by mutableStateOf(false)
    var listFocused by mutableStateOf(false)
    var fieldFocused by mutableStateOf(false)

    /**
     * The search just opened (⌕): its field takes the focus and the keyboard, once. Not each time it is composed again
     * (select mode ending, a page come back from, the tab returned to).
     */
    var focusField by mutableStateOf(false)
    var undoFocused by mutableStateOf(false)

    /** The Undo bar had the focus as it went (undone, or its time up): its focus is handed on then too. */
    var undoHeld by mutableStateOf(false)
    var letterGrid by letterGrid
    var pendingLetter by mutableStateOf<Char?>(null)

    /** The Undo bar's focus, as it changes: leaving with the bar (no offer left) isn't the remote moving off it. */
    fun undoFocusChanged(focused: Boolean, offerLeft: Boolean) {
        undoFocused = focused
        if (focused) undoHeld = true else if (offerLeft) undoHeld = false
    }

    internal var handBack: () -> Unit = {}

    /** The removed rows' neighbour or the first row marked, while it is shown; else the layout's top ([CollectionLayout.focusTop]). */
    fun handFocusBack() = handBack()
}

/** The collection's focus state; the letter grid's being open is saved with the screen. */
@Composable
internal fun rememberCollectionFocus(): CollectionFocus {
    val letterGrid = rememberSaveable { mutableStateOf(false) }
    return remember(letterGrid) { CollectionFocus(letterGrid) }
}

/**
 * The collection's focus and scrolling ([focus]) over [layout], with [vm]'s ui ([uiState], as composed): everything
 * slice 12's screen did between its state and its layout, and its Back handlers, composed here in that order before the
 * layout (a layout's own handler, a field's gate, goes after them, so first).
 */
@OptIn(FlowPreview::class)
@Composable
internal fun CollectionFocusEffects(
    focus: CollectionFocus,
    vm: CollectionViewModel,
    uiState: State<CollectionUi?>,
    dpad: Boolean,
    tv: Boolean,
    jump: CollectionJump?,
    onJumpDone: () -> Unit,
    backToSearch: Boolean,
    layout: CollectionLayout,
) {
    // For effects that outlive a composition: the input mode can change meanwhile (a remote's first key).
    val dpadNow by rememberUpdatedState(dpad)
    val focusAfter by vm.focusAfter.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val a11y = LocalAccessibilityManager.current
    val rail = LocalRailFocus.current
    val current = uiState.value
    val ui = { uiState.value }
    val list = layout.scroller(current?.side ?: Side.Shows)
    fun itemOf(u: CollectionUi, key: TitleKey): Int? = u.view.rows.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(layout.itemsBefore(u))

    // A remote's focus: where the layout starts it as the tab opens, or back on the row a page was opened from, coming
    // back from it (only then: the tab returned to later keeps the list where it was). A jump puts it on the title it
    // reveals.
    val initial = rememberInitialFocus()
    LaunchedEffect(current != null) {
        if (current == null || jump != null) return@LaunchedEffect
        initial.awaitTurn()
        val opened = vm.takeOpened()
        if (dpad && opened != null && current.view.rows.any { it.key == opened }) focus.focusKey = opened
        else layout.focusStart(current)
    }
    // The row to focus: scrolled into view, then focused once it is composed. Followed should the rows move it meanwhile
    // (its facts coming in under a sort that reads them); gone, nothing is focused.
    LaunchedEffect(focus.focusKey) {
        val key = focus.focusKey ?: return@LaunchedEffect
        var tries = 0
        while (list.reach(key, ui, ::itemOf)) {
            withFrameNanos {}
            if (layout.focusTitle(key) || ++tries == FOCUS_TRIES) break
        }
        focus.focusKey = null
    }
    // After a removal or a mark: Undo takes the focus on a remote; when it goes, the row focusAfter says does.
    LaunchedEffect(current?.undo?.id) {
        current?.undo ?: return@LaunchedEffect
        vm.undoShown(a11y?.calculateRecommendedTimeoutMillis(UNDO_MS, containsIcons = false, containsText = true, containsControls = true) ?: UNDO_MS)
        if (dpad) {
            withFrameNanos {}
            runCatchingFocus { focus.undoFocus.requestFocus() }
        }
    }
    focus.handBack = {
        focus.undoHeld = false
        val after = focusAfter
        vm.focusTaken()
        val now = vm.ui.value
        if (after != null && now?.view?.rows?.any { it.key == after } == true) focus.focusKey = after
        else layout.focusTop(now)
    }
    LaunchedEffect(current?.undo == null) {
        if (current?.undo == null && focus.undoHeld) focus.handFocusBack()
    }
    LaunchedEffect(focusAfter, dpad) {
        if (focusAfter != null && !dpad) vm.focusTaken()
    }
    // A title just added on Search: its side, the filter or search that hid it cleared, brought into view and highlighted.
    LaunchedEffect(jump) {
        val target = jump?.let { TitleKey.parse(it.key) } ?: return@LaunchedEffect
        val index = vm.reveal(target, added = jump.added)
        if (index != null) {
            val shown = layout.scroller(if (target.isShow) Side.Shows else Side.Movies)
            shown.centre(target, ui, ::itemOf, layout::reveal)
            focus.follow = target
            if (dpadNow) focus.focusKey = target
        } else if (dpadNow) {
            // It didn't show in time: where the tab's focus starts.
            layout.focusMissedJump()
        }
        onJumpDone()
    }
    // The title a jump revealed stays in view (brought in again) while it is highlighted: its facts coming in under a
    // sort that reads them (Next to air, New episodes, Release date) move it after the reveal.
    LaunchedEffect(focus.follow) {
        val target = focus.follow ?: return@LaunchedEffect
        val shown = layout.scroller(if (target.isShow) Side.Shows else Side.Movies)
        withTimeoutOrNull(FOLLOW_MAX_MS) {
            snapshotFlow { ui()?.let { it.highlight to itemOf(it, target) } }.filterNotNull()
                // From the highlight showing (the reveal's ui may still be on its way) to its end, or the row going.
                .dropWhile { (highlight, _) -> highlight != target }
                .takeWhile { (highlight, at) -> highlight == target && at != null }
                .map { (_, at) -> at!! }
                .distinctUntilChanged()
                .collect { at -> if (shown.shown.none { it.key == target.value && it.index == at }) shown.centre(target, ui, ::itemOf, layout::reveal) }
        }
        focus.follow = null
    }
    // The rows on screen go to the refresher first.
    LaunchedEffect(list) {
        snapshotFlow { list.shown.mapNotNull { (it.key as? String)?.let(TitleKey::parse) } }
            .debounce(VISIBLE_DEBOUNCE_MS).distinctUntilChanged().collect { vm.visible(it) }
    }
    LaunchedEffect(focus.focusFirstRow, current?.view) {
        val view = current?.view
        if (!focus.focusFirstRow || view == null || view.empty != null) return@LaunchedEffect
        focus.focusKey = view.rows.firstOrNull()?.key
        focus.focusFirstRow = false
    }
    // A letter picked: A–Z first (its index comes with it), then there.
    LaunchedEffect(focus.pendingLetter, current?.view?.letters) {
        val letter = focus.pendingLetter ?: return@LaunchedEffect
        val view = current?.view ?: return@LaunchedEffect
        val letters = view.letters ?: return@LaunchedEffect
        val index = letters.indexFor(letter)
        list.scrollToItem(layout.itemsBefore(current) + index)
        if (dpad) view.rows.getOrNull(index)?.let { focus.focusKey = it.key }
        focus.pendingLetter = null
    }

    // Back, innermost first (the last enabled handler composed wins): the field, then a remote deep in the list (to the
    // top bar), then select mode, then an empty search. The letter grid is a dialog. On a TV the list's Back goes to the
    // rail instead (TopLevelBack), one press fewer: only select mode keeps its step to the actions.
    BackHandler(enabled = current != null && current.searchOpen && current.text.isEmpty() && current.selection == null && !focus.fieldFocused) { vm.closeSearch() }
    BackHandler(enabled = current?.selection != null) {
        // On a TV, out of select mode from its actions: the focus to Select, where select mode was entered, rather
        // than lost with the actions (it would land on the rail). From the rail it stays there, the grid as it is.
        val fromActions = tv && dpad && !focus.listFocused && rail?.hasFocus != true
        vm.exitSelect()
        if (fromActions) {
            scope.launch {
                vm.ui.first { it?.selection == null }
                withFrameNanos {}
                layout.focusSelect()
            }
        }
    }
    // Not right after a jump: one Back from the revealed row returns to Search. In select mode with nothing selected
    // the actions are off and can't take the focus: Back leaves select mode then.
    BackHandler(enabled = dpad && focus.listFocused && !backToSearch && (!tv || current?.selection != null)) {
        val selection = current?.selection
        if (selection != null && selection.count == 0) vm.exitSelect()
        else if (selection != null) layout.focusActions()
        else layout.focusTop(current)
    }
    BackHandler(enabled = focus.undoFocused) { focus.handFocusBack() }
    // Off the field: on a remote to the top bar (focus cleared would land straight back on the field, the first control).
    BackHandler(enabled = focus.fieldFocused) {
        keyboard?.hide()
        if (dpad) layout.focusSelect() else focusManager.clearFocus()
    }
}

/**
 * Whether the layout was last laid out with [ui]'s rows in [ui]'s order: the ui comes first, the layout composes and
 * lays out its rows a frame or so later. Only then does an index in [ui] say where to scroll.
 */
private fun TitleScroller.laidOut(ui: CollectionUi, itemOf: (CollectionUi, TitleKey) -> Int?): Boolean = shown.all { item ->
    (item.key as? String)?.let(TitleKey::parse)?.let { itemOf(ui, it) == item.index } ?: true
}

// Both below scroll with requestScrollToItem (taken at the next layout), once the layout is laid out as the ui says: an
// index in a ui the layout hasn't caught up with would bring up another row, which the layout then keeps in view.
// scrollToItem would also wait for a side's first layout, which a side's state put into the one lazy layout never hears of.

/** Scrolls until [key]'s row is on screen, to wherever [ui] has it (again, should it move). False once it is gone. */
internal suspend fun TitleScroller.reach(key: TitleKey, ui: () -> CollectionUi?, itemOf: (CollectionUi, TitleKey) -> Int?): Boolean {
    var scrolledTo: Int? = null
    return snapshotFlow { ui()?.let { u -> Triple(itemOf(u, key), laidOut(u, itemOf), shown.any { it.key == key.value }) } }
        .filterNotNull()
        .first { (index, current, shown) ->
            if (!shown && index != null && current && index != scrolledTo) {
                scrolledTo = index
                requestScrollToItem(index)
            }
            shown || index == null
        }.third
}

/**
 * [key]'s row where the layout reveals it ([place]: the scroll from its [ShownItem.offset]), brought into view first if
 * it isn't, once the layout is laid out as [ui] says. Another scroll taking over (the user's) leaves the layout where
 * that one takes it.
 */
internal suspend fun TitleScroller.centre(
    key: TitleKey,
    ui: () -> CollectionUi?,
    itemOf: (CollectionUi, TitleKey) -> Int?,
    place: (viewport: Int, size: Int, offset: Int) -> Float,
) {
    try {
        var requested: Int? = null
        val row = withTimeoutOrNull(LAYOUT_WAIT_MS) {
            snapshotFlow { ui()?.takeIf { laidOut(it, itemOf) }?.let { itemOf(it, key) } to shown.firstOrNull { it.key == key.value } }
                .first { (index, row) ->
                    if (index != null && row?.index != index && index != requested) {
                        requested = index
                        requestScrollToItem(index)
                    }
                    index != null && row?.index == index
                }.second
        } ?: return
        scrollBy(place(viewport, row.size, row.offset))
    } catch (e: CancellationException) {
        // Interrupted by another scroll, not cancelled: that one goes on.
        currentCoroutineContext().ensureActive()
    }
}

/** A phone's reveal: the row in the middle of the list (as far as it scrolls). */
internal fun centred(viewport: Int, size: Int, offset: Int): Float = -(viewport / 2f - size / 2f - offset)

internal const val UNDO_MS = 10_000L
private const val VISIBLE_DEBOUNCE_MS = 300L

/** A row moved off screen just as it was to take the focus: brought back this many times at most. */
private const val FOCUS_TRIES = 3
private const val LAYOUT_WAIT_MS = 2_000L

/** Longer than a reveal's highlight: the follow never outlives it. */
private const val FOLLOW_MAX_MS = 5_000L

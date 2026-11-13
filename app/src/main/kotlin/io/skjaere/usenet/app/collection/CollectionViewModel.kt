package io.skjaere.usenet.app.collection

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.DeviceClock
import io.skjaere.usenet.app.collection.model.CollectionView
import io.skjaere.usenet.app.collection.model.MovieFilter
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieSort
import io.skjaere.usenet.app.collection.model.Reveal
import io.skjaere.usenet.app.collection.model.RevealStep
import io.skjaere.usenet.app.collection.model.Selection
import io.skjaere.usenet.app.collection.model.ShowFilter
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowSort
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.SideView
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.collection.model.TitleSearch
import io.skjaere.usenet.app.collection.model.focusAfterRemoval
import io.skjaere.usenet.app.refresh.RefreshStatus
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.CollectionViewPrefs
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.Removal
import io.skjaere.usenet.data.ShowMark
import io.skjaere.usenet.data.WatchSnapshot
import io.skjaere.usenet.metadata.airedEpisodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.LocalDate

/** The library writes the collection makes; [LibraryWrites] in the app, a fake on the JVM. */
interface CollectionWrites {
    suspend fun remove(shows: List<Int>, movies: List<Int>): Removal
    suspend fun undoRemove(removal: Removal): Int
    suspend fun markShows(marks: List<ShowMark>, watched: Boolean): WatchSnapshot
    suspend fun markMovies(marks: List<MovieMark>, watched: Boolean): WatchSnapshot
    suspend fun undoMarks(snapshot: WatchSnapshot): Int
}

class LibraryWrites(private val library: LibraryRepository) : CollectionWrites {
    override suspend fun remove(shows: List<Int>, movies: List<Int>) = library.removeTitles(shows, movies)
    override suspend fun undoRemove(removal: Removal) = library.undoRemove(removal)
    override suspend fun markShows(marks: List<ShowMark>, watched: Boolean) = library.markShows(marks, watched)
    override suspend fun markMovies(marks: List<MovieMark>, watched: Boolean) = library.markMovies(marks, watched)
    override suspend fun undoMarks(snapshot: WatchSnapshot) = library.undoMarks(snapshot)
}

/**
 * What the collection screen reads and writes. [writeScope] outlives the screen (the app's scope), so
 * leaving it can't stop a removal or a mark halfway; [dispatcher] builds the list off the main thread.
 */
class CollectionDeps(
    val shows: Flow<List<ShowRow>?>,
    val movies: Flow<List<MovieRow>?>,
    val writes: CollectionWrites,
    val prioritize: (List<TitleKey>) -> Unit,
    val refreshStatus: Flow<RefreshStatus>,
    val prefs: Flow<CollectionViewPrefs>,
    val savePrefs: suspend ((CollectionViewPrefs) -> CollectionViewPrefs) -> Unit,
    val hasKey: Flow<Boolean>,
    val showArtwork: Flow<Boolean>,
    val today: Flow<LocalDate>,
    val writeScope: CoroutineScope,
    val clock: Clock,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        fun of(c: AppContainer) = CollectionDeps(
            shows = c.collection.shows, movies = c.collection.movies, writes = LibraryWrites(c.library), prioritize = c.refresher::prioritize,
            refreshStatus = c.refresher.status, prefs = c.settings.collectionView, savePrefs = c.settings::updateCollectionView,
            hasKey = c.settings.hasTmdbKey, showArtwork = c.settings.metadata.map { it.showArtwork }, today = c.today,
            writeScope = c.appScope, clock = DeviceClock,
        )
    }
}

/** Select mode as the screen shows it: [count] selected of what is shown. */
@Immutable
data class SelectionUi(val count: Int, val allSelected: Boolean, val keys: Set<TitleKey>)

@Immutable
data class UndoUi(val id: Long, val message: String)

/** One side of the collection as the screen draws it. */
@Immutable
data class CollectionUi(
    val side: Side,
    val filterName: String,
    val sortName: String,
    /** The side's sorts: setting name to label. */
    val sorts: List<Pair<String, String>>,
    val searchOpen: Boolean,
    val text: String,
    val view: SideView,
    /** Null outside select mode. */
    val selection: SelectionUi?,
    val undo: UndoUi?,
    /** "Marking 120 shows…" while a mark is written. */
    val busy: String?,
    /** A title a jump revealed, highlighted for a moment. */
    val highlight: TitleKey?,
    /** [highlight] just added ("+ Collection"), not there already ("In collection ›"): only then is it "Just added" to TalkBack. */
    val highlightAdded: Boolean,
    /** What TalkBack hears about [highlight]. */
    val announce: String?,
    /** "TMDB unreachable · showing saved info" while the refresher can't ask. */
    val note: String?,
    val hasKey: Boolean,
    val showArtwork: Boolean,
    /** How many titles each side has (a TV's Shows | Movies switch says it), whatever the search and the chip show; a side not read yet is left out. */
    val totals: Map<Side, Int> = emptyMap(),
) {
    val sortLabel: String get() = sorts.firstOrNull { it.first == sortName }?.second.orEmpty()
}

/**
 * The Undo bar's offer: one at a time (a new one makes the last final), gone [shown]'s time after it
 * first showed. The bar composed again (a rotation) shows the same offer, whose time keeps running.
 */
class UndoTimer<T : Any>(private val scope: CoroutineScope) {
    data class Offer<T>(val id: Long, val message: String, val value: T)

    private val current = MutableStateFlow<Offer<T>?>(null)
    val offer: StateFlow<Offer<T>?> = current.asStateFlow()
    private var ids = 0L
    private var shownId: Long? = null
    private var timer: Job? = null

    @Synchronized
    fun offer(message: String, value: T) {
        timer?.cancel()
        shownId = null
        current.value = Offer(++ids, message, value)
    }

    /** The bar is on screen: the offer goes [timeoutMs] later (the screen passes the accessibility timeout). */
    @Synchronized
    fun shown(timeoutMs: Long) {
        val offer = current.value ?: return
        if (shownId == offer.id) return
        shownId = offer.id
        timer = scope.launch {
            delay(timeoutMs)
            current.compareAndSet(offer, null)
        }
    }

    /** Undo: the offer's value, once. */
    @Synchronized
    fun take(): T? {
        timer?.cancel()
        return current.getAndUpdate { null }?.value
    }

    @Synchronized
    fun dismiss() {
        timer?.cancel()
        current.value = null
    }
}

/**
 * The collection screen's state: the side, its filter and sort (the settings keep them; this is the
 * source of truth while it lives, a chip tap shows at once and is saved after), the search, select
 * mode, the Undo offer. Search and selection survive in [saved]; select mode keeps the rows' order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(private val deps: CollectionDeps, private val saved: SavedStateHandle) : ViewModel() {
    private sealed interface Undoable {
        data class Removed(val removal: Removal) : Undoable
        data class Marked(val snapshot: WatchSnapshot) : Undoable
    }

    private data class Local(
        val prefs: CollectionViewPrefs,
        val searchOpen: Boolean,
        val text: String,
        val selection: Selection,
        /** The rows' order when select mode started: they don't move under the finger. */
        val frozen: List<TitleKey>?,
        val busy: String? = null,
        val highlight: TitleKey? = null,
        val highlightAdded: Boolean = false,
        val announce: String? = null,
    ) {
        val side: Side get() = Side.byName(prefs.side) ?: Side.Shows
    }

    private data class Extras(val note: String?, val hasKey: Boolean, val artwork: Boolean)

    private val local = MutableStateFlow<Local?>(null)
    private val _typed = MutableStateFlow(saved[TEXT] ?: "")

    /** The search as typed, for the field: set in the same call as the keystroke ([ui] follows a moment later, built off the main thread). */
    val typed: StateFlow<String> = _typed.asStateFlow()
    private val undo = UndoTimer<Undoable>(viewModelScope)
    private val _focusAfter = MutableStateFlow<TitleKey?>(null)

    /** Set by a removal or a mark: the row a remote's focus goes to as the Undo bar goes (the removed rows' neighbour, the first row marked; null: the top bar). */
    val focusAfter: StateFlow<TitleKey?> = _focusAfter.asStateFlow()

    /** Every title of each side, as last built: what a reveal waits for, and whether a filter or the search hides it. */
    @Volatile
    private var sideRows: Map<Side, Map<TitleKey, TitleRow>> = emptyMap()
    private val saving = Mutex()

    /**
     * The row a Show or Movie page was opened from, once: a remote's focus goes back to it on coming
     * back from the page, not on every later visit to the tab (that starts on the search button).
     */
    fun takeOpened(): TitleKey? = saved.remove<String>(OPENED)?.let(TitleKey::parse)

    init {
        viewModelScope.launch {
            val prefs = deps.prefs.first()
            local.value = Local(prefs, saved[SEARCH_OPEN] ?: false, saved[TEXT] ?: "", Selection.restore(saved.get<ArrayList<String>>(SELECTED)), frozen = null)
        }
    }

    /** Null until the saved view and the side's rows are known: no default sort or empty list flashes up first. */
    val ui: StateFlow<CollectionUi?> = combine(
        local.filterNotNull(), deps.shows, deps.movies,
        // The refresher's note, not its status: a fill's count of what is left changes with every fetch and shows nowhere.
        combine(deps.refreshStatus.map { note(it) }, deps.hasKey, deps.showArtwork) { note, key, artwork -> Extras(note, key, artwork) }.distinctUntilChanged(),
        undo.offer,
    ) { l, shows, movies, extras, offer -> Inputs(l, shows, movies, extras, offer) }
        .mapLatest { build(it) }
        .flowOn(deps.dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private class Inputs(val local: Local, val shows: List<ShowRow>?, val movies: List<MovieRow>?, val extras: Extras, val offer: UndoTimer.Offer<Undoable>?)

    private fun build(i: Inputs): CollectionUi? {
        val l = i.local
        i.shows?.let { rows -> sideRows = sideRows + (Side.Shows to rows.associateBy { it.key }) }
        i.movies?.let { rows -> sideRows = sideRows + (Side.Movies to rows.associateBy { it.key }) }
        val (view, filter, sort, sorts) = when (l.side) {
            Side.Shows -> {
                val filter = ShowFilter.byName(l.prefs.showFilter) ?: ShowFilter.All
                val sort = ShowSort.byName(l.prefs.showSort) ?: ShowSort.RecentlyWatched
                Quad(CollectionView.shows(i.shows ?: return null, filter, sort, l.text, l.frozen), filter.name, sort.name, ShowSort.entries.map { it.name to it.label })
            }
            Side.Movies -> {
                val filter = MovieFilter.byName(l.prefs.movieFilter) ?: MovieFilter.All
                val sort = MovieSort.byName(l.prefs.movieSort) ?: MovieSort.RecentlyAdded
                Quad(CollectionView.movies(i.movies ?: return null, filter, sort, l.text, l.frozen), filter.name, sort.name, MovieSort.entries.map { it.name to it.label })
            }
        }
        val visible = view.rows.map { it.key }
        return CollectionUi(
            side = l.side,
            filterName = filter,
            sortName = sort,
            sorts = sorts,
            searchOpen = l.searchOpen,
            text = l.text,
            view = view,
            selection = l.selection.takeIf { it.active }?.let { SelectionUi(it.count(visible), it.allSelected(visible), it.keys) },
            undo = i.offer?.let { UndoUi(it.id, it.message) },
            busy = l.busy,
            highlight = l.highlight,
            highlightAdded = l.highlightAdded,
            announce = l.announce,
            note = i.extras.note,
            hasKey = i.extras.hasKey,
            showArtwork = i.extras.artwork,
            totals = buildMap {
                i.shows?.let { put(Side.Shows, it.size) }
                i.movies?.let { put(Side.Movies, it.size) }
            },
        )
    }

    /** What the list says while the refresher can't ask TMDB. */
    private fun note(status: RefreshStatus): String? = when ((status as? RefreshStatus.Paused)?.reason) {
        RefreshStatus.Reason.Offline -> "TMDB unreachable · showing saved info"
        RefreshStatus.Reason.RateLimited -> "TMDB is busy · showing saved info"
        RefreshStatus.Reason.InvalidKey -> "TMDB didn't accept the key · showing saved info"
        RefreshStatus.Reason.NoKey, null -> null
    }

    private data class Quad(val view: SideView, val filter: String, val sort: String, val sorts: List<Pair<String, String>>)

    private fun update(change: (Local) -> Local) {
        val next = local.updateAndGet { it?.let(change) } ?: return
        _typed.value = next.text
        saved[SEARCH_OPEN] = next.searchOpen
        saved[TEXT] = next.text
        saved[SELECTED] = next.selection.saved()?.let { ArrayList(it) }
    }

    /** In memory at once, then saved: the latest wins however the saves are scheduled. */
    private fun persist(change: (CollectionViewPrefs) -> CollectionViewPrefs) {
        update { it.copy(prefs = change(it.prefs)) }
        deps.writeScope.launch {
            saveOrLog("save the collection's view") {
                saving.withLock {
                    val p = local.value?.prefs ?: return@withLock
                    deps.savePrefs { it.copy(side = p.side, showFilter = p.showFilter, showSort = p.showSort, movieFilter = p.movieFilter, movieSort = p.movieSort) }
                }
            }
        }
    }

    /** Leaves select mode. */
    fun switchSide(side: Side) {
        update { it.copy(selection = Selection(), frozen = null) }
        persist { it.copy(side = side.name) }
    }

    fun setFilter(name: String) = persist { p -> if (current() == Side.Shows) p.copy(showFilter = name) else p.copy(movieFilter = name) }

    fun setSort(name: String) = persist { p -> if (current() == Side.Shows) p.copy(showSort = name) else p.copy(movieSort = name) }

    /** The empty state's way out: the side's every title, the search kept (its matches are what "Show 2 in All" counted). */
    fun showAll() = setFilter(if (current() == Side.Shows) ShowFilter.All.name else MovieFilter.All.name)

    private fun current(): Side = local.value?.side ?: Side.Shows

    fun openSearch() = update { it.copy(searchOpen = true) }

    /** Filters as typed, title, original title and year (both sides share it). */
    fun type(text: String) = update { it.copy(searchOpen = true, text = text) }

    fun closeSearch() = update { it.copy(searchOpen = false, text = "") }

    /** A long press starts with its row; Select with none. */
    fun startSelect(with: TitleKey? = null) = update { it.copy(selection = Selection().start(with), frozen = visibleKeys()) }

    fun toggle(key: TitleKey) = update { l -> if (l.selection.active) l.copy(selection = l.selection.toggle(key)) else l.copy(selection = Selection().start(key), frozen = visibleKeys()) }

    /** Every row the chip and the search show; when they all are, none. */
    fun selectAllVisible() = update { l ->
        l.copy(selection = l.selection.selectAll(visibleKeys()), frozen = l.frozen ?: visibleKeys())
    }

    fun exitSelect() = update { it.copy(selection = Selection(), frozen = null) }

    private fun visibleKeys(): List<TitleKey> = ui.value?.view?.rows?.map { it.key }.orEmpty()

    private fun visibleRows(): List<TitleRow> = ui.value?.view?.rows.orEmpty()

    /** Removes what is selected and shown; no dialog, Undo instead. */
    fun removeSelected() {
        val rows = visibleRows()
        val keys = local.value?.selection?.effective(rows.map { it.key }).orEmpty()
        if (keys.isEmpty()) return
        exitSelect()
        remove(keys, rows)
    }

    /** A row's own "Remove from collection" (TalkBack's action), with Undo too. */
    fun removeOne(key: TitleKey) = remove(listOf(key), visibleRows())

    private fun remove(keys: List<TitleKey>, rows: List<TitleRow>) {
        val message = "Removed " + what(rows.filter { it.key in keys })
        _focusAfter.value = focusAfterRemoval(rows.map { it.key }, keys.toSet())
        deps.writeScope.launch {
            saveOrLog("remove titles from the collection") {
                val removal = deps.writes.remove(shows = keys.filter { it.isShow }.map { it.tmdbId }, movies = keys.filterNot { it.isShow }.map { it.tmdbId })
                undo.offer(message, Undoable.Removed(removal))
            }
        }
    }

    /**
     * Marks what is selected and shown. Watched marks each show's aired episodes by its stored facts
     * (shows without facts are left out, and the bar says so); unwatched clears every episode state.
     */
    fun markSelected(watched: Boolean) {
        val rows = visibleRows()
        val keys = local.value?.selection?.effective(rows.map { it.key }).orEmpty().toSet()
        if (keys.isEmpty()) return
        exitSelect()
        mark(rows.filter { it.key in keys }, watched)
    }

    /** A poster's menu's Mark watched or unwatched: its one title, as select mode marks (Undo, the focus back on it after). */
    fun markOne(key: TitleKey, watched: Boolean) {
        val chosen = visibleRows().filter { it.key == key }
        if (chosen.isNotEmpty()) mark(chosen, watched)
    }

    private fun mark(chosen: List<TitleRow>, watched: Boolean) {
        // The marked rows stay (unless a filter they leave hides them): a remote's focus goes back to the first.
        _focusAfter.value = chosen.first().key
        update { it.copy(busy = "Marking ${count(chosen)}…") }
        val done = if (watched) "watched" else "unwatched"
        deps.writeScope.launch {
            try {
                saveOrLog("mark titles $done") {
                    val shows = chosen.filterIsInstance<ShowRow>()
                    val movies = chosen.filterIsInstance<MovieRow>()
                    val today = deps.today.first()
                    val markable = if (watched) shows.filter { it.details != null } else shows
                    val snapshot = if (shows.isNotEmpty()) {
                        deps.writes.markShows(
                            markable.map { row -> ShowMark(row.show.tmdbId, row.show.name, if (watched) row.details!!.airedEpisodes(today).map { it.season to it.episode } else emptyList()) },
                            watched,
                        )
                    } else {
                        deps.writes.markMovies(movies.map { MovieMark(it.movie.tmdbId, it.movie.title) }, watched)
                    }
                    val skipped = shows.size - markable.size
                    val marked = if (shows.isNotEmpty()) markable else movies
                    undo.offer("Marked ${what(marked)} $done" + if (skipped > 0) " · $skipped not loaded yet" else "", Undoable.Marked(snapshot))
                }
            } finally {
                update { it.copy(busy = null) }
            }
        }
    }

    /** “Harbor Lights” for one, "3 shows" for more. */
    private fun what(rows: List<TitleRow>): String = rows.singleOrNull()?.let { "“${it.text.name}”" } ?: count(rows)

    private fun count(rows: List<TitleRow>): String {
        val noun = when {
            rows.all { it is ShowRow } -> "show"
            rows.all { it is MovieRow } -> "movie"
            else -> "title"
        }
        return "${rows.size} $noun${if (rows.size == 1) "" else "s"}"
    }

    fun undo() {
        val offer = undo.take() ?: return
        deps.writeScope.launch {
            saveOrLog("undo") {
                when (offer) {
                    is Undoable.Removed -> deps.writes.undoRemove(offer.removal)
                    is Undoable.Marked -> deps.writes.undoMarks(offer.snapshot)
                }
            }
        }
    }

    /** The Undo bar is on screen; [timeoutMs] from calculateRecommendedTimeoutMillis (longer with TalkBack). */
    fun undoShown(timeoutMs: Long) = undo.shown(timeoutMs)

    fun dismissUndo() = undo.dismiss()

    /**
     * Brings [target], just added, into view: its side, then (once the library has it) the filter and
     * search cleared if they hide it. Highlights it and tells TalkBack ([added] false: it was there
     * already). The index in the side's rows, or null if it didn't show within [timeoutMs].
     */
    suspend fun reveal(target: TitleKey, timeoutMs: Long = 3_000, added: Boolean = true): Int? {
        val found = withTimeoutOrNull(timeoutMs) {
            ui.filterNotNull().mapNotNull { u ->
                val rows = sideRows[u.side]
                when (val step = Reveal.step(target, u.side, u.view, rows?.keys)) {
                    RevealStep.SwitchSide -> null.also { if (u.side == current()) switchSide(if (target.isShow) Side.Shows else Side.Movies) }
                    RevealStep.Wait -> null
                    // Only what hides it: a search the title matches stays, and so does a filter that shows it.
                    RevealStep.ClearFilters -> null.also {
                        val row = rows?.get(target) ?: return@also
                        if (!row.text.matches(TitleSearch.tokens(u.text))) update { it.copy(searchOpen = false, text = "") }
                        val shown = when (row) {
                            is ShowRow -> CollectionView.inFilter(row, ShowFilter.byName(u.filterName) ?: ShowFilter.All)
                            is MovieRow -> CollectionView.inFilter(row, MovieFilter.byName(u.filterName) ?: MovieFilter.All)
                        }
                        if (!shown) setFilter(if (target.isShow) ShowFilter.All.name else MovieFilter.All.name)
                    }
                    is RevealStep.ScrollTo -> step.index to u.view.rows[step.index].text.name
                }
            }.first()
        } ?: return null
        val (index, name) = found
        update { it.copy(highlight = target, highlightAdded = added, announce = if (added) "Added $name to your collection" else "$name, in your collection") }
        viewModelScope.launch {
            delay(HIGHLIGHT_MS)
            update { if (it.highlight == target) it.copy(highlight = null, highlightAdded = false, announce = null) else it }
        }
        return index
    }

    /** The rows on screen, for the refresher to fill first. */
    fun visible(keys: List<TitleKey>) = deps.prioritize(keys)

    /** A Show or Movie page opened from [key]'s row. */
    fun opened(key: TitleKey) {
        saved[OPENED] = key.value
    }

    fun focusTaken() {
        _focusAfter.value = null
    }

    private companion object {
        const val SEARCH_OPEN = "searchOpen"
        const val TEXT = "text"
        const val SELECTED = "selected"
        const val OPENED = "opened"
        const val HIGHLIGHT_MS = 2_500L
    }
}

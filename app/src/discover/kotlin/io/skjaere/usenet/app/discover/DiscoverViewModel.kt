package io.skjaere.usenet.app.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.discover.Period
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration

/** The collected shows and movies, by the collection's keys. */
internal fun collectedKeys(deps: DiscoverDeps): Flow<Set<TitleKey>> =
    combine(deps.collectedShows, deps.collectedMovies) { shows, movies -> shows.mapTo(HashSet()) { TitleKey.show(it) } + movies.map { TitleKey.movie(it) } }
        .onStart { emit(emptySet()) }

/**
 * The Discover tab: a side's lists as rows (its first page each), the side's genres as chips, one picked filtering
 * every row. A side is asked for once it shows, its rows kept while the tab lives (its back stack saved with it, so a
 * page or another tab and back asks nothing again); a genre picked asks the side's rows again. Nothing is asked
 * without a key, and everything again when one is added or changed, or TMDB's language changes. A row TMDB says to
 * slow down for shows its last copy, if there is one, and is asked again after a wait. The side and each side's genre
 * are kept with the screen's state.
 */
class DiscoverViewModel(private val deps: DiscoverDeps, private val saved: SavedStateHandle) : ViewModel() {
    private sealed interface Row {
        data object Loading : Row
        data class Shown(val titles: List<DiscoverTitle>, val offline: Duration?) : Row
        data class Failed(val error: TmdbError) : Row
    }

    /** What the tab holds: whether there is a key (null until read), the side, each side's genre, genres and rows. */
    private data class Tab(
        val hasKey: Boolean?,
        val side: MediaType,
        val genre: Map<MediaType, Int?>,
        val genres: Map<MediaType, List<Genre>> = emptyMap(),
        val rows: Map<MediaType, Map<DiscoverList, Row>> = emptyMap(),
    )

    private val tab = MutableStateFlow(
        Tab(
            deps.knownKey,
            saved.get<String>(SIDE)?.let { name -> MediaType.entries.firstOrNull { it.name == name } } ?: MediaType.Tv,
            MediaType.entries.associateWith { saved.get<Int>(GENRE + it.name) },
        ),
    )
    private val jobs = HashMap<Pair<MediaType, DiscoverList>, Job>()
    private val genreJobs = HashMap<MediaType, Job>()

    /** A side's first load asks at most this many lists at once. */
    private val gate = Semaphore(PARALLEL)

    val ui: StateFlow<DiscoverUi> = combine(tab, collectedKeys(deps), deps.showArtwork.onStart { emit(true) }) { t, collected, artwork -> t.ui(collected, artwork) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, tab.value.ui(emptySet(), true))

    init {
        viewModelScope.launch {
            // Each key stored emits: a new one after TMDB refused the last is asked with at once.
            deps.hasTmdbKey.collect { key ->
                stopAll()
                tab.update { it.copy(hasKey = key, rows = emptyMap(), genres = emptyMap()) }
                if (key) load(tab.value.side)
            }
        }
        viewModelScope.launch {
            deps.language.distinctUntilChanged().drop(1).collect {
                if (tab.value.hasKey != true) return@collect
                stopAll()
                tab.update { it.copy(rows = emptyMap(), genres = emptyMap()) }
                load(tab.value.side)
            }
        }
    }

    /** Shows or Movies: the side's rows, asked for the first time it shows. */
    fun switchSide(side: MediaType) {
        saved[SIDE] = side.name
        val before = tab.value
        tab.update { it.copy(side = side) }
        if (before.hasKey == true && before.rows[side] == null) load(side)
    }

    /** A genre chip ([genre] null: all genres): every row of the side shows only titles in it. */
    fun pickGenre(genre: Int?) {
        val side = tab.value.side
        if (tab.value.genre[side] == genre) return
        saved[GENRE + side.name] = genre
        stop(side)
        tab.update { it.copy(genre = it.genre + (side to genre), rows = it.rows - side) }
        if (tab.value.hasKey == true) load(side)
    }

    /** Try again: every row of the side that failed (all of them, for a key TMDB refused), and its genres if they did. */
    fun retry() {
        val t = tab.value
        if (t.hasKey != true) return
        val rows = t.rows[t.side].orEmpty()
        DiscoverList.on(t.side).filter { rows[it] is Row.Failed }.forEach { loadRow(t.side, it) }
        if (t.genres[t.side] == null) loadGenres(t.side)
    }

    /** Try again in one row's place. */
    fun retry(list: DiscoverList) {
        if (tab.value.hasKey == true) loadRow(tab.value.side, list)
    }

    private fun load(side: MediaType) {
        loadGenres(side)
        DiscoverList.on(side).forEach { loadRow(side, it) }
    }

    private fun loadGenres(side: MediaType) {
        if (genreJobs[side]?.isActive == true) return
        genreJobs[side] = viewModelScope.launch {
            try {
                val genres = deps.source.genres(side)
                tab.update { it.copy(genres = it.genres + (side to genres)) }
            } catch (e: TmdbException) {
                // The chips show All genres alone; Try again asks again.
            }
        }
    }

    private fun loadRow(side: MediaType, list: DiscoverList) {
        val at = side to list
        jobs[at]?.cancel()
        setRow(side, list, Row.Loading)
        val query = DiscoverQuery(side, list, DiscoverFilters(genre = tab.value.genre[side]).on(side, list))
        jobs[at] = viewModelScope.launch {
            var waits = 0
            while (true) {
                // TMDB said to slow down: its last copy shows meanwhile (or why there is none), and it is asked again after a wait.
                val slowDown = try {
                    val answer = gate.withPermit { deps.source.page(query) }
                    setRow(side, list, Row.Shown(answer.value.items, answer.age.takeIf { answer.offline }))
                    answer.rateLimited
                } catch (e: TmdbException) {
                    setRow(side, list, Row.Failed(e.error))
                    e.error is TmdbError.RateLimited
                }
                if (!slowDown || waits >= deps.backoff.size) break
                delay(deps.backoff[waits++].toMillis())
            }
        }
    }

    private fun setRow(side: MediaType, list: DiscoverList, row: Row) {
        tab.update { it.copy(rows = it.rows + (side to (it.rows[side].orEmpty() + (list to row)))) }
    }

    private fun stop(side: MediaType) {
        jobs.keys.filter { it.first == side }.forEach { jobs.remove(it)?.cancel() }
    }

    private fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        genreJobs.values.forEach { it.cancel() }
        genreJobs.clear()
    }

    private fun Tab.ui(collected: Set<TitleKey>, artwork: Boolean): DiscoverUi {
        val sideRows = rows[side].orEmpty()
        val errors = sideRows.values.mapNotNull { (it as? Row.Failed)?.error }
        val key = when {
            hasKey == null -> null
            !hasKey || errors.any { it is TmdbError.NoKey } -> KeyState.Missing
            errors.any { it is TmdbError.InvalidKey } -> KeyState.Refused
            else -> KeyState.Ready
        }
        val shown = if (key != KeyState.Ready) emptyList() else DiscoverList.on(side).map { list ->
            val view = when (val row = sideRows[list]) {
                null, Row.Loading -> RowView.Loading
                is Row.Shown -> RowView.Cards(cards(row.titles, collected))
                is Row.Failed -> RowView.Failed(failureText(row.error))
            }
            DiscoverRow(list, list.heading(Period.Week), view)
        }
        val offline = sideRows.values.mapNotNull { (it as? Row.Shown)?.offline }.maxOrNull()
        return DiscoverUi(key, side, genres[side].orEmpty(), genre[side], shown, artwork, offline)
    }

    private companion object {
        const val SIDE = "side"
        const val GENRE = "genre:"
        const val PARALLEL = 4
    }
}

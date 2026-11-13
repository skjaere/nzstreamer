package io.skjaere.usenet.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.DetailsLoader
import io.skjaere.usenet.app.collection.UndoTimer
import io.skjaere.usenet.app.collection.UndoUi
import io.skjaere.usenet.app.collection.model.CollectionView
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieSort
import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.ShowSort
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.collection.model.posterProgress
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.CollectionCounts
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.UpNextRemoval
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchSnapshot
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * One of Home's Collection rows: a show watched lately, or (with no shows) a movie added lately. A TV shows it as a poster
 * ([posterPath]), NEW on a show with new episodes, and how much of it was watched ([progress]: a show's watched of aired,
 * a movie's how far in while in progress; none before it is begun or once it is all watched, as the collection grid's).
 */
data class HomeCollectionRow(
    val key: TitleKey,
    val title: String,
    val status: String,
    val posterPath: String? = null,
    val new: Boolean = false,
    val progress: Float? = null,
)

/** Home's Collection section: how many shows and movies there are, and a few of them. */
data class HomeCollection(val shows: Int, val movies: Int, val rows: List<HomeCollectionRow>) {
    /** "Collection · 214 shows · 380 movies", without a kind there is none of. */
    val header: String
        get() = listOfNotNull("Collection", count(shows, "show"), count(movies, "movie")).joinToString(" · ")

    companion object {
        /** A phone's few rows, under Up next; a TV's row of posters, which scrolls sideways. */
        const val PHONE_ROWS = 3
        const val TV_ROWS = 12

        private fun count(n: Int, noun: String) = n.takeIf { it > 0 }?.let { "$it $noun${if (it == 1) "" else "s"}" }

        /** Null with nothing collected; [movies] are read only when there are no shows. At most [rows] of them. */
        fun of(counts: CollectionCounts, shows: List<ShowRow>, movies: List<MovieRow>?, rows: Int = PHONE_ROWS): HomeCollection? {
            if (counts.shows + counts.movies == 0) return null
            val taken: List<TitleRow> =
                if (shows.isNotEmpty()) CollectionView.sorted(shows, ShowSort.RecentlyWatched).take(rows)
                else movies?.let { CollectionView.sorted(it, MovieSort.RecentlyAdded).take(rows) }.orEmpty()
            return HomeCollection(counts.shows, counts.movies, taken.map(::row))
        }

        private fun row(row: TitleRow) = when (row) {
            is ShowRow -> HomeCollectionRow(row.key, row.text.name, row.status, row.posterPath, new = row.newEpisodes > 0, progress = row.posterProgress)
            is MovieRow -> HomeCollectionRow(row.key, row.text.name, row.status, row.posterPath, progress = row.posterProgress)
        }
    }
}

/** What Home shows under its search field once setup is done. */
data class HomeState(
    val recent: List<RecentSearch>,
    val upNext: List<UpNextRow>,
    /** Null without a TMDB key, as adding to the collection needs one, or with nothing collected. */
    val collection: HomeCollection?,
    val hasTmdbKey: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
/** [collectionRows]: how many of the collection Home lists ([HomeCollection.PHONE_ROWS], or a TV's [HomeCollection.TV_ROWS]). */
class HomeViewModel(private val container: AppContainer, private val collectionRows: Int = HomeCollection.PHONE_ROWS) : ViewModel() {
    private val settings = container.settings
    private val library = container.library

    /** Null until the settings are read, so the first-run screen doesn't flash up on every start. */
    val setup: StateFlow<SetupState?> = container.setup

    val showArtwork: StateFlow<Boolean> = settings.metadata.map { it.showArtwork }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // The collected shows' details are the store's (its refresher keeps them); these load only what
    // Up next names besides: its seasons' episodes, and titles in progress that aren't collected.
    private val shows = DetailsLoader<Int, ShowDetails>({ TmdbLookup.show(container, it) })
    private val seasons = DetailsLoader<SeasonKey, Season>({ TmdbLookup.season(container, it.showTmdbId, it.season) })
    private val movies = DetailsLoader<Int, MovieDetails>({ TmdbLookup.movie(container, it) })

    /** [hidden]: the keys of the rows taken out of Up next. */
    private class History(val inProgress: List<WatchState>, val collected: List<ShowRow>, val hidden: Set<String>) {
        /** What is in progress and not taken out: what TMDB is asked about. */
        val shown: List<WatchState> get() = inProgress.filter { UpNext.key(it) !in hidden }
    }

    private class Known(val history: History, val tmdb: TmdbAnswers, val hasKey: Boolean) {
        fun rows(today: LocalDate, tmdb: TmdbAnswers = this.tmdb) = UpNext.rows(history.inProgress, history.collected, tmdb, hasKey, today, history.hidden)
    }

    // Shared: several flows below read each of these, and one read of the library (and one load loop
    // per TMDB lookup) serves them all. In progress: the recent list and every collected show's.
    private val history: Flow<History> = combine(
        library.continueWatching, library.inProgressOfCollected, container.collection.shows.filterNotNull(), library.upNextHidden,
    ) { recent, collected, rows, hidden ->
        History((recent + collected).distinctBy { it.key }, rows, hidden)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** What [loader] knows of [keys] and then more as TMDB answers; nothing is asked for without a key. */
    private fun <K : Any, V : Any> answers(loader: DetailsLoader<K, V>, keys: Flow<List<K>>): Flow<Map<K, V?>> =
        combine(keys, settings.hasTmdbKey) { k, key -> if (key) k else emptyList() }
            .distinctUntilChanged()
            .flatMapLatest { k -> if (k.isEmpty()) flowOf(emptyMap()) else loader.load(k) }

    private val known: Flow<Known> = combine(
        history,
        answers(shows, history.map { h -> h.collected.map { it.show.tmdbId }.toSet().let { ids -> h.shown.filter { it.kind == WatchKind.Episode }.mapNotNull { it.showTmdbId }.filter { it !in ids }.distinct() } }),
        answers(movies, history.map { h -> h.shown.filter { it.kind == WatchKind.Movie }.mapNotNull { it.movieTmdbId }.distinct() }),
        settings.hasTmdbKey,
    ) { h, showAnswers, movieAnswers, key -> Known(h, TmdbAnswers(shows = showAnswers, movies = movieAnswers), key) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The movies' rows only while there are no shows to list instead. */
    private val collection: Flow<Pair<CollectionCounts, List<MovieRow>?>> = container.collection.counts.filterNotNull().flatMapLatest { counts ->
        if (counts.shows == 0 && counts.movies > 0) container.collection.movies.filterNotNull().map { counts to it } else flowOf(counts to null)
    }

    /** Null until the library has answered. TMDB's answers only add to it: the rows in progress show without them. */
    val state: StateFlow<HomeState?> = combine(
        combine(known, container.today) { k, today -> k to today },
        answers(seasons, combine(known, container.today) { k, today -> UpNext.seasons(k.rows(today)).toList() }),
        library.recentSearches,
        collection,
    ) { (k, today), seasonAnswers, recent, (counts, movieRows) ->
        val tmdb = k.tmdb.copy(seasons = seasonAnswers)
        HomeState(recent.take(RECENT_SEARCHES), k.rows(today, tmdb), if (k.hasKey) HomeCollection.of(counts, k.history.collected, movieRows, collectionRows) else null, k.hasKey)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A row's Play: resumes what is in progress, or finds and plays a show's next episode, where Play on says (L7). */
    fun play(row: UpNextRow) {
        container.playRouter.upNext(row.play)
    }

    private sealed interface Undoable {
        data class Removed(val removal: UpNextRemoval) : Undoable
        data class Marked(val snapshot: WatchSnapshot) : Undoable
    }

    private val undoTimer = UndoTimer<Undoable>(viewModelScope)

    /**
     * The Undo bar's offer after Remove from Up next or Mark watched, one at a time as the collection's. It goes with
     * Home's screen, which sends it away as it leaves (UpNextUndo), though this model stays while a page or a tab is in
     * front: leaving within its time loses Undo, and what was done stays.
     */
    val undoOffer: StateFlow<UndoUi?> = undoTimer.offer.map { offer -> offer?.let { UndoUi(it.id, it.message) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _focusAfter = MutableStateFlow<String?>(null)

    /** Set by Remove from Up next or Mark watched: the row a remote's focus goes to as the Undo bar goes ([UpNextEdit.focusAfter]). */
    val focusAfter: StateFlow<String?> = _focusAfter.asStateFlow()

    /** A row's Remove from Up next: out of it until it plays again, with Undo. Written on the app's scope, so it is kept leaving Home. */
    fun remove(row: UpNextRow) {
        val edit = UpNextEdit.remove(row)
        _focusAfter.value = edit.focusAfter(shownKeys())
        container.appScope.launch {
            saveOrLog("take a title out of Up next") {
                undoTimer.offer(edit.message, Undoable.Removed(library.removeFromUpNext(edit.key, edit.showTmdbId, edit.watchKey)))
            }
        }
    }

    /** A row's Mark watched, for an episode or film in progress ([UpNextEdit.markWatched]), with Undo; nothing for another row. */
    fun markWatched(row: UpNextRow) {
        val edit = UpNextEdit.markWatched(row) ?: return
        _focusAfter.value = edit.focusAfter(shownKeys())
        container.appScope.launch {
            saveOrLog("mark watched from Up next") {
                val snapshot = when (edit) {
                    is UpNextEdit.MarkEpisode -> library.markShows(listOf(edit.mark), watched = true)
                    is UpNextEdit.MarkMovie -> library.markMovies(listOf(edit.mark), watched = true)
                }
                undoTimer.offer(edit.message, Undoable.Marked(snapshot))
            }
        }
    }

    private fun shownKeys(): List<String> = state.value?.upNext?.map { it.key }.orEmpty()

    /** The bar's Undo: the removal undone while it is as it was made, the marks while as they left the states. */
    fun undo() {
        val offer = undoTimer.take() ?: return
        container.appScope.launch {
            saveOrLog("undo in Up next") {
                when (offer) {
                    is Undoable.Removed -> library.undoUpNextRemoval(offer.removal)
                    is Undoable.Marked -> library.undoMarks(offer.snapshot)
                }
            }
        }
    }

    /** The Undo bar is on screen; [timeoutMs] from calculateRecommendedTimeoutMillis (longer with TalkBack). */
    fun undoShown(timeoutMs: Long) = undoTimer.shown(timeoutMs)

    fun dismissUndo() = undoTimer.dismiss()

    fun focusTaken() {
        _focusAfter.value = null
    }

    /** "Not now": Home without an indexer. */
    fun skipIndexer() {
        viewModelScope.launch { settings.setIndexerSetupSkipped(true) }
    }

    private companion object {
        const val RECENT_SEARCHES = 6
    }
}

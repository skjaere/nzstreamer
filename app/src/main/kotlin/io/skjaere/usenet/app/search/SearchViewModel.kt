package io.skjaere.usenet.app.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.RecentKind
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.metadata.TmdbException
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupedResults
import io.skjaere.usenet.search.ParsedQuery
import io.skjaere.usenet.search.PickedTitle
import io.skjaere.usenet.search.QueryParser
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import io.skjaere.usenet.search.SearchPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One indexer's state in the current search, for the status pills and the failure screens. */
data class IndexerStatus(val id: String, val name: String, val state: State) {
    sealed interface State {
        data object Searching : State

        /** It found [results] it was asked for; [unchecked] of them torrents whose cache check failed or ran out of time (L199). */
        data class Answered(val results: Int, val unchecked: Int = 0) : State

        /** A torrent indexer that found [results], [waiting] of them torrents still being checked (L199): it has answered. */
        data class Checking(val results: Int, val waiting: Int) : State
        data class Failed(val reason: String) : State
        data class Skipped(val reason: String) : State
    }

    /** Whether it has answered, a torrent indexer whose torrents are still being checked included. */
    val answered: Boolean get() = state is State.Answered || state is State.Checking

    /** What it found, its torrents still being checked included. */
    val results: Int get() = (state as? State.Answered)?.results ?: (state as? State.Checking)?.results ?: 0
}

/** The title the user picked from the suggestions, with what the title card shows. */
data class TitleInfo(
    val type: MediaType,
    val tmdbId: Int,
    val name: String,
    val year: Int?,
    val meta: String,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val ids: TvIds,
    val runtimeMinutes: Int?,
    /** ISO 639-1, from TMDB: the title's releases without a language tag speak it. */
    val originalLanguage: String? = null,
) {
    /** The title as the search's results are grouped and ranked for it: in its language, their bitrates over its runtime. */
    val known: ResultGrouper.KnownTitle get() = ResultGrouper.KnownTitle(name, year, type == MediaType.Tv, originalLanguage, runtimeMinutes)

    companion object {
        /** What the title card shows of a picked show, and its ids for searching. */
        fun of(show: ShowDetails): TitleInfo {
            val seasons = show.seasons.count { !it.isSpecials }
            return TitleInfo(
                MediaType.Tv, show.tmdbId, show.name, show.year,
                listOfNotNull("TV series", show.year?.toString(), "$seasons season${if (seasons == 1) "" else "s"}").joinToString(" · "),
                show.overview, show.posterPath, show.backdropPath,
                TvIds(tvdb = show.ids.tvdb, tmdb = show.tmdbId, imdb = show.ids.imdb), show.episodeRuntimeMinutes, show.originalLanguage,
            )
        }

        /** What the title card shows of a picked movie, and its ids for searching. */
        fun of(movie: MovieDetails): TitleInfo {
            val runtime = movie.runtimeMinutes?.let { "${it / 60} h ${it % 60} min" }
            return TitleInfo(
                MediaType.Movie, movie.tmdbId, movie.title, movie.year,
                listOfNotNull("Movie", movie.year?.toString(), runtime).joinToString(" · "),
                movie.overview, movie.posterPath, movie.backdropPath,
                TvIds(tmdb = movie.tmdbId, imdb = movie.ids.imdb), movie.runtimeMinutes, movie.originalLanguage,
            )
        }
    }
}

/**
 * One search: the text, and the title picked from the suggestions, with its details once they were looked up.
 * A search of the words for a title known without a pick (the no-release sheet's, for an episode) has its
 * original [language] (ISO 639-1), which its untagged releases speak. A [fallback] is the preview's own, run
 * after a pause in typing when TMDB had no titles for the words: it shows in the preview, under the field.
 */
data class SearchRequest(val text: String, val pick: TitleSuggestion? = null, val title: TitleInfo? = null, val language: String? = null, val fallback: Boolean = false) {
    val parsed: ParsedQuery = QueryParser.parse(text)

    /** [base] for ranking this search's releases: in its [language] when it has one (a picked title's details say theirs). */
    fun rankingContext(base: RankingContext): RankingContext = language?.let { base.copy(originalLanguage = it) } ?: base

    /** The picked title's details: kept from an earlier run of this search, else looked up with [lookUp]. */
    suspend fun titleInfo(lookUp: suspend (TitleSuggestion) -> TitleInfo?): TitleInfo? = title ?: pick?.let { lookUp(it) }

    /**
     * This search as the screen shows it: the picked title's details ([info]) and what the indexers answered so far,
     * ranked for the Cast device [rankedFor] names (null: this device, L127).
     */
    fun results(info: TitleInfo?, statuses: List<IndexerStatus>, grouped: GroupedResults, done: Boolean, rankedFor: String? = null) =
        SearchResults(text, parsed, info, statuses, grouped, done, pick, language, fallback, rankedFor)

    /**
     * What shows from the moment this search starts until its first snapshot: the [previous] search's
     * indexers, searching again, and none of its results, which mustn't be played under the new query.
     */
    fun starting(previous: SearchResults?): SearchResults =
        results(title, previous?.statuses.orEmpty().map { it.copy(state = IndexerStatus.State.Searching) }, GroupedResults(emptyList()), done = false)
}

/** The TMDB title this search was picked from, as its recent search remembers it; null for a search of typed text. */
fun SearchRequest.recentPick(): SearchPick? = pick?.let { p ->
    val tv = p.type == MediaType.Tv
    SearchPick(
        if (tv) TitleType.Tv else TitleType.Movie, p.tmdbId, p.name,
        season = parsed.season.takeIf { tv }, episode = parsed.episode.takeIf { tv },
    )
}

/** [this] title as the recent searches remember it opened. */
fun TitleSuggestion.opened(): SearchPick = SearchPick(if (type == MediaType.Tv) TitleType.Tv else TitleType.Movie, tmdbId, name)

/** The suggestion this was picked from, as far as searching by it needs: its type, TMDB id and name. */
fun SearchPick.suggestion(): TitleSuggestion =
    TitleSuggestion(if (type == TitleType.Tv) MediaType.Tv else MediaType.Movie, tmdbId, name, null, null, null, null)

/** The TMDB title [this] opened, whose page it opens again; null for a search of the indexers, which it runs again. */
val RecentSearch.openedTitle: SearchPick? get() = pick.takeIf { kind == RecentKind.Title }

/** Which kind [this] was, as the lists say it beside its words. */
val RecentSearch.kindLabel: String
    get() = when (openedTitle?.type) {
        TitleType.Tv -> "TV series"
        TitleType.Movie -> "Movie"
        null -> "Indexer search"
    }

/** The search a recent search replays: by its picked title's id when it had one (#122), else its text. */
fun RecentSearch.request(): SearchRequest = SearchRequest(query, pick?.suggestion())

data class SearchResults(
    val query: String,
    val parsed: ParsedQuery,
    val title: TitleInfo?,
    val statuses: List<IndexerStatus>,
    val grouped: GroupedResults,
    val done: Boolean,
    /** The suggestion [title] was looked up from; null for a free-text search. */
    val pick: TitleSuggestion?,
    /** The search's [SearchRequest.language]. */
    val language: String?,
    /** The preview's own search ([SearchRequest.fallback]). */
    val fallback: Boolean = false,
    /** The Cast device the releases are ranked for, as a release list's "Ranked for" line names it (L127); null for this device. */
    val rankedFor: String? = null,
) {
    /** What "Try again" searches: the same text and picked title, not looked up again once it was; the preview's again in the preview. */
    val retry: SearchRequest get() = SearchRequest(query, pick, title, language, fallback)

    /** "Search without the episode": the whole show, still by the picked title; a pasted release name by its title. */
    val withoutEpisode: SearchRequest
        get() = SearchRequest(if (parsed.isReleaseName) ReleaseParser.parse(query).title else parsed.title, pick, title, language)

    /** A search of the words alone, sent on purpose: not by a picked title, not the preview's own. TMDB's titles for them show on top. */
    val byWords: Boolean get() = pick == null && !fallback

    val answered: Int get() = statuses.count { it.answered }
    val found: Int get() = statuses.sumOf { it.results }

    /** Whether a torrent indexer's torrents are still being checked: they may yet join the list (W71). */
    val checking: Boolean get() = statuses.any { it.state is IndexerStatus.State.Checking }
    val allFailed: Boolean get() = done && statuses.isNotEmpty() && answered == 0
    val nothingPlayable: Boolean get() = done && !checking && answered > 0 && grouped.playableCount == 0
}

/** The recent searches, as Search lists and remembers them. */
interface SearchHistory {
    val recent: Flow<List<RecentSearch>>

    /** Remembers a search of [query], by the TMDB title it was picked from when it was ([pick]). */
    suspend fun add(query: String, pick: SearchPick?)

    /** Remembers a TMDB title opened from Search, by its id. */
    suspend fun addTitle(title: SearchPick)

    suspend fun remove(key: String)

    suspend fun clear()
}

/** The library's recent searches. */
private class LibrarySearchHistory(private val library: LibraryRepository) : SearchHistory {
    override val recent: Flow<List<RecentSearch>> = library.recentSearches

    override suspend fun add(query: String, pick: SearchPick?) = library.addSearch(query, pick)

    override suspend fun addTitle(title: SearchPick) = library.addTitle(title)

    override suspend fun remove(key: String) = library.removeSearch(key)

    override suspend fun clear() = library.clearSearches()
}

/** What Search reads and writes: the app's ([of]), or fakes, so its view model runs in a JVM test. */
class SearchDeps(
    val hasTmdbKey: Flow<Boolean>,
    val showArtwork: Flow<Boolean>,
    /** True while an indexer is switched on, or a torrent indexer while a service account is on ([IndexerConfigs.anySearched]). */
    val hasIndexers: Flow<Boolean>,
    val collectedShows: Flow<Set<Int>>,
    val collectedMovies: Flow<Set<Int>>,
    val history: SearchHistory,
    val adder: CollectionAdder,
    /** TMDB's titles for the words typed; throws when TMDB can't be asked. */
    val searchTitles: suspend (String) -> List<TitleSuggestion>,
    val show: suspend (Int) -> ShowDetails,
    val movie: suspend (Int) -> MovieDetails,
    val indexers: suspend () -> List<IndexerConfig>,
    /** What a search ranks by as it starts: the choice in force (L127). */
    val rankingContext: suspend () -> RankingContext,
    /**
     * Every indexer asked, as MultiIndexerSearch asks them: an answer is reused for 15 minutes unless [fresh] is. A torrent
     * indexer's torrents come as their cache checks settle (CheckedSearch).
     */
    val search: (indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?) -> Flow<IndexerOutcome>,
    /** Where names are checked and results regrouped: off the main thread. */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * What the releases a search holds rank again by as a choice is made in Play on (L127): the chosen Cast device's, or
     * this device's.
     */
    val rankings: Flow<RankingContext> = emptyFlow(),
) {
    companion object {
        fun of(c: AppContainer) = SearchDeps(
            hasTmdbKey = c.settings.hasTmdbKey, showArtwork = c.settings.metadata.map { it.showArtwork },
            hasIndexers = combine(c.settings.indexers, c.settings.torrentIndexers, c.serviceOn, IndexerConfigs::anySearched),
            collectedShows = c.library.collectedShowIds, collectedMovies = c.library.collectedMovieIds,
            history = LibrarySearchHistory(c.library),
            adder = CollectionAdder(c.appScope, c.library::addShow, c.library::addMovie, c.refresher::refreshNow),
            // Through TmdbLookup's seams, which the device tests answer for invented titles.
            searchTitles = { TmdbLookup.searchTitles(c, it) }, show = { TmdbLookup.show(c, it) }, movie = { TmdbLookup.movie(c, it) },
            indexers = c::indexerConfigs, rankingContext = c::rankingContext, search = c.checkedSearch::search, rankings = c.listRankings,
        )
    }
}

/** What came of words said into the mic ([SearchViewModel.spoken]). */
enum class Spoken { Titles, Searched }

/** Why TMDB has no titles for the words typed. */
enum class NoTitles { NotFound, Unreachable, NoKey }

/**
 * TMDB's titles for [query], the words typed (trimmed). While TMDB is still asked ([settled] false) they are the
 * words before's, so the list doesn't empty at each keystroke; once it has answered, [noTitles] says why there are none.
 */
data class TitleLookup(val query: String, val titles: List<TitleSuggestion>, val settled: Boolean, val noTitles: NoTitles? = null) {
    companion object {
        /** Nothing typed, or nothing to ask about. */
        val None = TitleLookup("", emptyList(), settled = true)
    }
}

/** [initial]: the search to run at once (a recent search on Home, Search on the play sheet); null opens the field to type in. */
@OptIn(FlowPreview::class)
class SearchViewModel(private val deps: SearchDeps, initial: SearchRequest?) : ViewModel() {
    val query = MutableStateFlow(initial?.text.orEmpty())
    /** True while the user is typing: the preview (TMDB's titles, or its own search) shows instead of a search's results. */
    val editing = MutableStateFlow(initial == null)
    val results = MutableStateFlow<SearchResults?>(null)
    val noKeyBannerDismissed = MutableStateFlow(false)

    val hasTmdbKey = deps.hasTmdbKey.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val showArtwork = deps.showArtwork.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val hasIndexers = deps.hasIndexers.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val recent: StateFlow<List<RecentSearch>> = deps.history.recent.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** The collected shows and movies, by the ids alone (not their rows). */
    val collected: StateFlow<Set<TitleKey>> = combine(deps.collectedShows, deps.collectedMovies) { shows, movies ->
        shows.map { TitleKey.show(it) }.toSet() + movies.map { TitleKey.movie(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val adder = deps.adder

    /** Titles "+ Collection" is writing: their buttons say so meanwhile. */
    val adding: StateFlow<Set<TitleKey>> = adder.adding

    private val lookup = MutableStateFlow(TitleLookup.None)

    /**
     * TMDB's titles for what's being typed (300 ms after the last keystroke), and why there are none; while the releases
     * of a search of words show, for its words.
     */
    val titles: StateFlow<TitleLookup> = lookup.asStateFlow()

    /** The titles alone. */
    val suggestions: StateFlow<List<TitleSuggestion>> = lookup.map { it.titles }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The preview's own search ([SearchRequest.fallback]) for the words typed, while it shows; null otherwise. */
    val fallback: StateFlow<SearchResults?> = combine(results, editing, query) { r, e, q -> r.shownFallback(e, q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * TMDB's titles for the words a search of the indexers was for ([SearchResults.byWords]), while its releases show: a
     * strip on top. Only once TMDB has answered for them: meanwhile the lookup still holds the words before's titles.
     */
    val strip: StateFlow<List<TitleSuggestion>> = combine(lookup, results, editing) { t, r, e ->
        t.titles.takeIf { t.settled && !e && r != null && r.byWords && t.query == r.query }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null
    /** A release list opened: what the search holds ranks for the choice in force, if not already ([listOpened]). */
    private val rankNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The preview's wait for a pause in typing, then for TMDB's answer, before it searches the indexers itself. */
    private var pause: Job? = null
    /** The words Enter kept before the preview's own search of them came: it is remembered as it starts. */
    private var kept: String? = null
    private val grouper = ResultGrouper()
    private val chosen = ChosenPlays(viewModelScope)

    /** What the release list open marks of the releases chosen on it that failed to start. */
    val chosenFailed: StateFlow<Map<String, String>> = chosen.failed

    init {
        // The key's own flow, not hasTmdbKey: that starts as false, which would read as no key until the settings are read.
        viewModelScope.launch {
            combine(query, editing, results, deps.hasTmdbKey) { q, e, r, key -> (if (e) q.trim() else r?.takeIf { it.byWords }?.query) to key }
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { (text, key) -> lookUp(text, key) }
        }
        initial?.let { run(it) }
    }

    fun onQueryChange(text: String) {
        val changed = text != query.value || !editing.value
        query.value = text
        editing.value = true
        if (changed) previewChanged()
    }

    fun startEditing() {
        if (editing.value) return
        editing.value = true
        previewChanged()
    }

    fun clear() {
        job?.cancel()
        pause?.cancel()
        kept = null
        query.value = ""
        results.value = null
        editing.value = true
    }

    /**
     * Enter in the field, or a release played or opened from the preview's list: the list stays as the preview shows it
     * (the screen closes the keyboard). It never shows another search's results than the preview's: the
     * indexers are searched on purpose. The preview's own search, settled on, is remembered; Enter
     * pressed before it came (during the pause, or while TMDB still answers), once it does.
     */
    fun keep() {
        if (!editing.value) return
        val shown = results.value.shownFallback(editing.value, query.value)
        if (shown == null) {
            kept = query.value.trim()
            return
        }
        viewModelScope.launch { deps.history.add(shown.query, null) }
    }

    /**
     * Words said into the mic: in the field, as if typed. TMDB's titles for them, when they come within
     * [VOICE_TITLES_MS], show to pick from ([Spoken.Titles]); else (none, no key, TMDB slow) the indexers are searched
     * for the words at once ([Spoken.Searched]).
     */
    suspend fun spoken(text: String): Spoken {
        val words = text.trim()
        onQueryChange(words)
        val answer = withTimeoutOrNull(VOICE_TITLES_MS) { titles.first { it.query == words && it.settled } }
        if (answer != null && answer.titles.isNotEmpty()) return Spoken.Titles
        searchIndexers()
        return Spoken.Searched
    }

    /** "Search indexers": the words as typed, searched on every indexer. */
    fun searchIndexers() {
        val text = query.value.trim()
        if (text.isEmpty()) return
        run(SearchRequest(text))
    }

    /**
     * A title from the Titles: the screen opens its own page, Show or Movie, whose Play searches the indexers by the
     * title's ids; no search runs here. It is remembered as a title opened, which opens the page again. The preview
     * stays as it is, for Back from the page.
     */
    fun openTitle(s: TitleSuggestion) {
        viewModelScope.launch { deps.history.addTitle(s.opened()) }
    }

    /**
     * [recent] again: a title opened is returned, for the screen to open its page again (it goes to the top of the
     * list meanwhile); a search is searched again, by the title it was picked from when it was (#122).
     */
    fun openRecent(recent: RecentSearch): SearchPick? {
        recent.openedTitle?.let { title ->
            viewModelScope.launch { deps.history.addTitle(title) }
            return title
        }
        val request = recent.request()
        query.value = request.text
        run(request)
        return null
    }

    fun searchWithoutEpisode() {
        val request = results.value?.withoutEpisode ?: return
        query.value = request.text
        run(request)
    }

    /**
     * Plays [item], chosen on a release list, with [start]: the list stays under the play sheet as before, and marks it
     * if it fails to start.
     */
    fun playChosen(item: RankedItem, start: () -> Deferred<PlayOutcome>) {
        chosen.play(item, start)
    }

    /**
     * A release list opened (L127): the releases held rank for the choice in force now, with no new search, where Play
     * went back to This phone by itself since they were ranked.
     */
    fun listOpened() {
        rankNow.tryEmit(Unit)
    }

    /** A release list opened or closed: nothing on it is marked. */
    fun clearMarks() {
        chosen.clear()
    }

    fun removeRecent(recent: RecentSearch) = viewModelScope.launch { deps.history.remove(recent.key) }

    fun clearRecent() = viewModelScope.launch { deps.history.clear() }

    /** Asks every indexer again, bypassing the 15-minute result cache. */
    fun retry() {
        val r = results.value ?: return
        run(r.retry, fresh = true)
    }

    /** The title card's show or movie; the Deferred completes once it is stored, with where the collection jumps. */
    fun addToCollection(title: TitleInfo): Deferred<CollectionJump> = adder.add(title)

    /** A suggestion, from its own data: the jump doesn't wait on TMDB. */
    fun addSuggestionToCollection(s: TitleSuggestion): Deferred<CollectionJump> = adder.add(s)

    /** TMDB's titles for [text] (null: nothing typed, nor a search of words shown), with [key] or without one. */
    private suspend fun lookUp(text: String?, key: Boolean) {
        val title = text?.let { QueryParser.parse(it).title }.orEmpty()
        lookup.value = when {
            text.isNullOrEmpty() -> TitleLookup.None
            !key -> TitleLookup(text, emptyList(), settled = true, NoTitles.NoKey)
            title.length < 2 -> TitleLookup(text, emptyList(), settled = true, NoTitles.NotFound)
            else -> {
                lookup.value = TitleLookup(text, lookup.value.titles, settled = false)
                try {
                    val found = deps.searchTitles(title).take(8)
                    TitleLookup(text, found, settled = true, NoTitles.NotFound.takeIf { found.isEmpty() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TitleLookup(text, emptyList(), settled = true, NoTitles.Unreachable)
                }
            }
        }
    }

    /**
     * The words typed changed, or the preview shows again: after a pause (about a second, words of 3 characters or
     * more) with no titles from TMDB (none found, TMDB unreachable, no key), the preview searches the indexers for the
     * words itself, with no extra click. Typing on cancels that, and drops what it found. Its API calls are the
     * indexers' (no grabs), and MultiIndexerSearch's 15-minute cache answers the same words again for free.
     */
    private fun previewChanged() {
        val text = query.value.trim()
        // Other words than Enter kept: not settled on.
        if (kept != text) kept = null
        // The same words (a space typed after them): the preview's own search of them stays, running or shown.
        if (results.value?.takeIf { it.fallback }?.query == text) return
        pause?.cancel()
        if (results.value?.fallback == true) {
            job?.cancel()
            results.value = null
        }
        if (text.length < FALLBACK_MIN_LENGTH) return
        pause = viewModelScope.launch {
            delay(FALLBACK_PAUSE_MS)
            // TMDB's answer for these words, which may still be on its way.
            val answer = titles.first { it.query == text && it.settled }
            if (answer.titles.isEmpty()) run(SearchRequest(text, fallback = true))
        }
    }

    private fun run(request: SearchRequest, fresh: Boolean = false) {
        job?.cancel()
        if (!request.fallback) {
            // A search sent on purpose: the preview's own isn't wanted after it.
            pause?.cancel()
            editing.value = false
        }
        // The preview's own search is remembered once the user settles on it (keep), not at each pause in typing.
        val remembered = !request.fallback || request.text == kept
        if (request.fallback) kept = null
        results.value = request.starting(results.value)
        job = viewModelScope.launch {
            if (remembered) deps.history.add(request.text, request.recentPick())
            val info = request.titleInfo { resolve(it) }
            val indexers = deps.indexers()
            // Whom they rank for (L127): the choice in force now, then each made in Play on while they show.
            var base = deps.rankingContext()
            val known = info?.known
            val picked = info?.let { PickedTitle(it.name, it.year, it.type == MediaType.Tv, it.ids) }
            // What each indexer answered, and the releases taken: a torrent indexer's as their checks settle.
            val answers = Answers(known)
            val statuses = indexers.associate { it.id to IndexerStatus(it.id, it.name, IndexerStatus.State.Searching) }.toMutableMap()
            val order = indexers.map { it.id }
            // Done once every indexer has answered: a torrent indexer whose torrents are still checked has (W71).
            var done = indexers.isEmpty()
            // The indexers' answers and the choices made change what shows, one at a time, each snapshot numbered so
            // that one made earlier never replaces a later one.
            val lock = Mutex()
            var made = 0L
            var shown = 0L

            fun snapshot() = request.results(info, statuses.values.toList(), grouper.group(answers.accepted.toList(), request.rankingContext(base), known, order), done, base.rankedFor)

            // Snapshots are built off the main thread but assigned here: withContext doesn't return into
            // a cancelled job, so a search that was replaced or cleared never writes its results back.
            suspend fun show(change: () -> Unit) {
                // Checking names (a release-name parse per item) and regrouping stay off the main thread.
                val (number, snapshot) = withContext(deps.dispatcher) {
                    lock.withLock {
                        change()
                        ++made to snapshot()
                    }
                }
                if (number < shown) return
                shown = number
                results.value = snapshot
            }
            results.value = snapshot()

            // The releases held rank again, no indexer asked: for a choice made in Play on, and as a list opens for the
            // choice in force, where This phone came back by itself since (L127).
            launch {
                merge(deps.rankings, rankNow.map { deps.rankingContext() }).collectLatest { by ->
                    if (!lock.withLock { base.ranksAs(by) }) show { base = by }
                }
            }
            deps.search(indexers, fresh) { caps -> SearchPlanner.plan(caps, request.parsed, picked) }.collect { outcome ->
                show {
                    // As the movie page's search counts it (findMovieReleases): a picked title's results found by name must be that title.
                    answers.take(outcome)?.let { statuses[it.id] = it }
                    if (statuses.values.none { it.state == IndexerStatus.State.Searching }) done = true
                }
            }
            show { done = true }
        }
    }

    /** Details for the picked title: ids for searching, and what the title card shows. */
    private suspend fun resolve(s: TitleSuggestion): TitleInfo? = try {
        when (s.type) {
            MediaType.Tv -> TitleInfo.of(deps.show(s.tmdbId))
            MediaType.Movie -> TitleInfo.of(deps.movie(s.tmdbId))
        }
    } catch (e: TmdbException) {
        null
    }

    fun dismissNoKeyBanner() = noKeyBannerDismissed.update { true }

    private companion object {
        /** About a second after the last keystroke. */
        const val FALLBACK_PAUSE_MS = 1_000L
        const val FALLBACK_MIN_LENGTH = 3
        /** How long words said wait for TMDB's titles before the indexers are searched for them. */
        const val VOICE_TITLES_MS = 1_500L
    }
}

/** [this], the preview's own search, while the preview ([editing]) shows it for the words typed ([query]). */
private fun SearchResults?.shownFallback(editing: Boolean, query: String): SearchResults? =
    this?.takeIf { editing && it.fallback && it.query == query.trim() }

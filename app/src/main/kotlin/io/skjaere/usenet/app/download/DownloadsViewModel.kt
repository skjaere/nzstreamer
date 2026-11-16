package io.skjaere.usenet.app.download

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.UNDO_MS
import io.skjaere.usenet.app.collection.UndoTimer
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.EpisodeTarget
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * What the Downloads page and a show's downloads read and ask (L372, L379-L381): the app's ([of]), or a test's seams.
 * [items] is the runner's queue with the live write's bytes; [space] what downloads hold and may take ([except]: the
 * download whose releases a choice replaces, its own room counted free); [network] and [online] the default network (a
 * metered one is mobile data); [restricted] whether Android limits the app in the background (L361). The runner's calls,
 * the host's schedule from the visible app (L335), TMDB's lookup of an episode ([lookUp]), the search a choice runs
 * ([search]: the Download sheet's), and a finished download's play and watched mark. [work]: the app's scope, where the
 * runner's calls and the marks run, so one asked as the page goes (Back as a download stops at its safe offset) is still
 * made, as the title pages' are. [undoMillis]: the Undo bar's time before TalkBack's own (L379).
 */
data class DownloadsDeps(
    val items: Flow<List<DownloadRunner.Item>>,
    val space: suspend (except: String?) -> DownloadSpace?,
    val prefs: Flow<DownloadPrefs>,
    val network: StateFlow<NetworkNow>,
    val online: StateFlow<Boolean>,
    val restricted: () -> Boolean,
    val episodeStates: (showTmdbId: Int) -> Flow<Map<Pair<Int, Int>, WatchState>>,
    val movieWatches: Flow<List<MovieWatch>>,
    val showArtwork: Flow<Boolean>,
    val pause: suspend () -> Unit,
    val resume: suspend () -> Unit,
    val remove: suspend (ids: Collection<String>, keepFor: Long) -> Unit,
    val undoRemove: suspend (ids: Collection<String>) -> Unit,
    val move: suspend (id: String, up: Boolean) -> Unit,
    val replace: suspend (id: String, candidates: List<DownloadCandidate>, need: Long) -> Unit,
    val retry: suspend (id: String) -> Unit,
    val schedule: suspend (ScheduleCause) -> Unit,
    val lookUp: suspend (showTmdbId: Int, season: Int, episode: Int) -> EpisodePlay,
    val search: DownloadSheetDeps,
    val get: suspend (id: String) -> Download?,
    val play: (Download) -> Unit,
    val mark: suspend (Download, watched: Boolean) -> Unit,
    val work: CoroutineScope,
    val undoMillis: Long = UNDO_MS,
) {
    /**
     * The Download sheet's deps for a choice that replaces [row]'s releases: its search, the room free with [row]'s own
     * counted free, no notification ask (the download asked when it was queued), the runner's replace in the queue's
     * place, then the host's schedule from the visible app.
     */
    fun replacing(row: Download): DownloadSheetDeps = search.copy(
        space = { space(row.id) },
        askDue = { false },
        enqueue = { _, candidates, need ->
            replace(row.id, candidates, need)
            get(row.id)
        },
        beforeDownload = {},
        schedule = { schedule(ScheduleCause.Download) },
    )

    companion object {
        /**
         * The app's: the runner's queue and calls, the downloads' storage, the settings, the default network, the library's
         * watch states and marks, TMDB's lookup as a play's (TmdbLookup), the Download sheet's search, the host scheduled
         * from the visible app, and a finished download played here from its file (the coordinator's hook, L353).
         */
        fun of(container: AppContainer, context: Context) = DownloadsDeps(
            items = container.downloads.state,
            space = { except -> container.downloadStorage.space(except) },
            prefs = container.settings.downloads,
            network = container.defaultNetwork.now,
            online = container.validatedNetwork,
            restricted = { backgroundRestricted(context) },
            episodeStates = container.library::episodeStates,
            movieWatches = container.library.movieWatches,
            showArtwork = container.settings.metadata.map { it.showArtwork },
            pause = container.downloads::pause,
            resume = container.downloads::resume,
            remove = { ids, keepFor -> container.downloads.remove(ids, keepFor) },
            undoRemove = container.downloads::undoRemove,
            move = container.downloads::move,
            replace = container.downloads::replace,
            retry = container.downloads::retry,
            schedule = { cause -> container.downloadHosts.schedule(cause) },
            lookUp = { show, season, episode ->
                val details = TmdbLookup.show(container, show)
                val found = TmdbLookup.season(container, show, season).episodes.firstOrNull { it.episode == episode }
                    ?: throw NoSuchElementException("TMDB doesn't list the episode")
                EpisodePlay.of(details, found)
            },
            search = DownloadSheetDeps.of(container, ask = null, context),
            get = container.data.downloads::get,
            play = { row -> playHere(container, row) },
            mark = { row, watched -> mark(container, row, watched) },
            work = container.appScope,
        )

        /**
         * Plays [row]'s file here (L353): by its title's own play, which the coordinator's hook turns to the file, so its
         * progress and Up next are the title's either way. Home's Downloads plays a film so too.
         */
        internal fun playHere(container: AppContainer, row: Download) {
            when (row.kind) {
                DownloadKind.Episode -> {
                    val target = EpisodeTarget(row.showTmdbId ?: return, row.title, row.season ?: return, row.episode ?: return)
                    container.playback.playEpisode(target, PlayOrigin.Here)
                }
                DownloadKind.Movie -> {
                    val target = MovieTarget(row.movieTmdbId ?: return, row.title, row.year, imdb = null, runtimeMinutes = row.runtimeMinutes)
                    container.playback.playMovie(target, PlayOrigin.Here)
                }
            }
        }

        /** Marks [row]'s episode or film [watched] or not, as the Show and Movie pages do. */
        private suspend fun mark(container: AppContainer, row: Download, watched: Boolean) {
            when (row.kind) {
                DownloadKind.Episode -> {
                    val show = row.showTmdbId ?: return
                    val season = row.season ?: return
                    val episode = row.episode ?: return
                    container.library.markEpisodes(listOf(EpisodeRef(show, season, episode, WatchTitle.episode(row.title, season, episode))), watched)
                }
                DownloadKind.Movie -> container.library.markMovies(listOf(MovieMark(row.movieTmdbId ?: return, row.title)), watched)
            }
        }

        /** Whether Android limits the app in the background (L361): Android 9's, asked of none before it. */
        private fun backgroundRestricted(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    }
}

/**
 * Select mode and the Undo bar of the Downloads page and a show's downloads (L379): what is selected, by row key; Delete
 * removes every download the selected rows stand for, hidden at once, their files going once the bar has gone; Undo
 * brings them back. One offer at a time: a new one makes the last final. The bar is the page's ([scope]); the runner's
 * calls run in the app's ([DownloadsDeps.work]), so a Delete asked as the page goes still stops and hides its downloads.
 */
class DownloadEdits(private val deps: DownloadsDeps, scope: CoroutineScope) {
    private val _selection = MutableStateFlow<Set<String>?>(null)

    /** The keys selected, or null outside select mode. */
    val selection: StateFlow<Set<String>?> = _selection.asStateFlow()

    private val undo = UndoTimer<List<String>>(scope)
    val offer: StateFlow<UndoTimer.Offer<List<String>>?> = undo.offer

    fun startSelect(key: String? = null) {
        _selection.value = setOfNotNull(key)
    }

    fun toggle(key: String) {
        _selection.update { keys -> keys?.let { if (key in it) it - key else it + key } }
    }

    /** Every row's key, or none once all of them are. */
    fun selectAll(keys: List<String>) {
        _selection.update { selected -> if (selected != null && selected.containsAll(keys)) emptySet() else keys.toSet() }
    }

    fun exitSelect() {
        _selection.value = null
    }

    /**
     * Removes [downloads] (L379), hidden at once: the bar says what went and its size, and their files go [keepFor] after
     * (the bar's own time, longer with TalkBack), a second more so they never go before it has.
     */
    fun remove(downloads: List<Download>, keepFor: Long) {
        if (downloads.isEmpty()) return
        _selection.value = null
        undo.offer(DownloadsPage.deleted(downloads), downloads.map { it.id })
        deps.work.launch { logged("remove ${downloads.size} downloads") { deps.remove(downloads.map { it.id }, keepFor + UNDO_SLACK_MILLIS) } }
    }

    /**
     * The bar's Undo: what it named comes back as it was, then the host is asked for from here (L335), as one removed as
     * it ran ended its host's walk and is queued again with nothing to take it up.
     */
    fun undo() {
        val ids = undo.take() ?: return
        deps.work.launch {
            logged("bring ${ids.size} downloads back") {
                deps.undoRemove(ids)
                deps.schedule(ScheduleCause.Resume)
            }
        }
    }

    /** The bar is on screen: it goes [timeoutMs] later. */
    fun shown(timeoutMs: Long) = undo.shown(timeoutMs)
}

/**
 * The Downloads page's state (L372, L379-L381): the page's rows ([DownloadsPage]), the space strip, the lines about
 * mobile data and Android's background limit, select mode and Undo ([edits]), and Choose release ([choice]). A download
 * asked for by a notification ([choose]: its list of releases; [play]: played) is taken up once, kept so in [saved].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val deps: DownloadsDeps,
    private val saved: SavedStateHandle,
    choose: String? = null,
    play: String? = null,
) : ViewModel() {
    val edits = DownloadEdits(deps, viewModelScope)
    val choice = DownloadChoice(viewModelScope, deps.online, deps.lookUp, deps::replacing)

    /**
     * The runner's queue at most four times a second: the live write says each chunk it writes, and the page needs no more
     * than its bytes and rate as they go.
     */
    private val items = deps.items.conflate().transform { items ->
        emit(items)
        delay(REDRAW_MILLIS)
    }

    /** The finished shows' episodes' watch states, by show, followed while the page shows them. */
    private val episodeStates = deps.items.map { items -> items.mapNotNull { it.row.takeIf { row -> row.state == DownloadState.Done }?.showTmdbId }.distinct().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { shows -> if (shows.isEmpty()) flowOf(emptyMap()) else combine(shows.map { id -> deps.episodeStates(id).map { id to it } }) { it.toMap() } }

    /** The rows, once the runner has said: null before. */
    val page: StateFlow<DownloadsPage?> = combine(items, episodeStates, deps.movieWatches) { items, episodes, films ->
        DownloadsPage.of(items, episodes, films.associateBy { it.movieTmdbId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What downloads hold and what is free, read again as the rows change; null until first read. */
    val space: StateFlow<Measured?> = deps.items.map { items -> items.map { it.row } }.distinctUntilChanged()
        .mapLatest { Measured(deps.space(null)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The space as read: [space] null while the primary volume isn't there. */
    data class Measured(val space: DownloadSpace?)

    /** On mobile data while downloads wait for Wi-Fi or Ethernet (W110). */
    val mobileData: StateFlow<Boolean> = combine(deps.prefs, deps.network) { prefs, now -> prefs.unmeteredOnly && now.usable && !now.unmetered }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val online: StateFlow<Boolean> = deps.online
    val showArtwork: StateFlow<Boolean> = deps.showArtwork.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _restricted = MutableStateFlow(false)

    /** Whether Android limits the app in the background (L361), read each time the page is in front. */
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    val undoMillis: Long get() = deps.undoMillis

    init {
        if (choose != null && saved.get<Boolean>(CHOSE) != true) {
            saved[CHOSE] = true
            viewModelScope.launch { deps.get(choose)?.takeIf { it.state != DownloadState.Done && it.state != DownloadState.Deleting }?.let(choice::open) }
        }
        if (play != null && saved.get<Boolean>(PLAYED) != true) {
            saved[PLAYED] = true
            viewModelScope.launch { deps.get(play)?.takeIf { it.state == DownloadState.Done }?.let(deps.play) }
        }
    }

    /** The page is in front again: Android's background limit read again. */
    fun shown() {
        _restricted.value = deps.restricted()
    }

    /** Pause on the row the queue is at (L380): the whole queue held. */
    fun pause() = act("pause the downloads") { deps.pause() }

    /** Resume (L380): the queue let go, then its host scheduled from here, the visible app. */
    fun resume() = act("resume the downloads") {
        deps.resume()
        deps.schedule(ScheduleCause.Resume)
    }

    fun move(row: Download, up: Boolean) = act("move a download") { deps.move(row.id, up) }

    /** Try again: the failed download queued again from its releases, and its host scheduled from here. */
    fun retry(row: Download) = act("try a download again") {
        deps.retry(row.id)
        deps.schedule(ScheduleCause.Download)
    }

    fun play(row: Download) = deps.play(row)

    fun mark(row: Download, watched: Boolean) = act("mark a download's title") { deps.mark(row, watched) }

    /** Runs [action], one of the runner's calls, in the app's scope: one asked as the page goes is still made. */
    private fun act(what: String, action: suspend () -> Unit) {
        deps.work.launch { logged(what, action) }
    }

    private companion object {
        const val CHOSE = "chose"
        const val PLAYED = "played"

        /** How often the page draws the running download's bytes again, at most. */
        const val REDRAW_MILLIS = 250L
    }
}

/** A show's downloads (L372): its finished episodes with their watch states, Resume's pick, select mode and Undo. */
class DownloadsShowViewModel(private val deps: DownloadsDeps, val showTmdbId: Int) : ViewModel() {
    val edits = DownloadEdits(deps, viewModelScope)

    /** The show's finished episodes, by season and episode; null before the runner has said. */
    val episodes: StateFlow<List<Download>?> = deps.items.map { items -> DownloadsShow.ordered(items.map { it.row }.filter { it.showTmdbId == showTmdbId }) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val states: StateFlow<Map<Pair<Int, Int>, WatchState>> = deps.episodeStates(showTmdbId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val undoMillis: Long get() = deps.undoMillis

    fun play(row: Download) = deps.play(row)

    fun mark(row: Download, watched: Boolean) {
        deps.work.launch { logged("mark a downloaded episode") { deps.mark(row, watched) } }
    }
}

/**
 * Runs [action], one of the page's: a failure is logged by its kind alone, as the downloads' lines are (never a cause's
 * words, which could name a file or a host), and the page goes on.
 */
internal suspend fun logged(what: String, action: suspend () -> Unit) {
    try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Couldn't {}: {}", what, e::class.simpleName)
    }
}

private val log = LoggerFactory.getLogger(DownloadsViewModel::class.java)

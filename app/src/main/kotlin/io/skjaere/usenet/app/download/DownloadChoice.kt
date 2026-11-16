package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Choose another release and Choose release on the Downloads page (item 19): the release list in download mode for a
 * download already queued, running or failed, whose pick replaces its releases ([DownloadSheetModel] over the deps
 * [sheet] gives for it, whose enqueue is the runner's replace). An episode's search needs the show's ids, aliases,
 * original language, air date and the season's count of episodes, which its row doesn't keep: it is looked up on TMDB
 * first ([lookUp]), as a play of an episode known by its numbers is. A film's is built from its row.
 *
 * Offline ([online] false) it opens nothing, and the page greys its items "Needs a connection"; so it does for a download
 * whose lookup failed ([needsConnection]), until the network comes back.
 */
class DownloadChoice(
    private val scope: CoroutineScope,
    private val online: StateFlow<Boolean>,
    private val lookUp: suspend (showTmdbId: Int, season: Int, episode: Int) -> EpisodePlay,
    private val sheet: (Download) -> DownloadSheetDeps,
) {
    /** What the page shows of a choice: [None]; the episode [LookingUp] on TMDB; or the list's model, [Choosing]. */
    sealed interface State {
        data object None : State

        data class LookingUp(val row: Download) : State

        data class Choosing(val row: Download, val model: DownloadSheetModel) : State
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _needsConnection = MutableStateFlow<Set<String>>(emptySet())

    /** The downloads whose lookup on TMDB failed: greyed "Needs a connection" until the network comes back. */
    val needsConnection: StateFlow<Set<String>> = _needsConnection.asStateFlow()

    /** The choice under way: its lookup, then its list's search, both ended by [close]. */
    private var job: Job? = null

    init {
        // The network back: a lookup that failed may work now.
        scope.launch { online.collect { if (it) _needsConnection.value = emptySet() } }
    }

    /** Whether [row]'s choice can open now: [online], and its lookup not [failed] since. */
    fun can(row: Download, online: Boolean, failed: Set<String>): Boolean = online && row.id !in failed

    /** Opens the list for [row]: an episode looked up on TMDB first, then searched; a film searched as its row names it. */
    fun open(row: Download) {
        if (!can(row, online.value, _needsConnection.value)) return
        close()
        _state.value = State.LookingUp(row)
        job = scope.launch {
            val request = try {
                request(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("download {}: couldn't look it up on TMDB to choose its release: {}", row.id, e::class.simpleName)
                _needsConnection.update { it + row.id }
                _state.value = State.None
                return@launch
            }
            // The list's search runs in this job, so a close ends it; what its pick starts outlives both (the deps' own scope).
            val model = DownloadSheetModel(request, sheet(row), this)
            model.start()
            _state.value = State.Choosing(row, model)
            // The pick replaced its releases: the list closes, and the page shows the row queued again.
            model.closed.first { it }
            _state.value = State.None
            job = null
            cancel()
        }
    }

    /** Closes the list, or the lookup before it: Back, its arrow. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = State.None
    }

    /** [row]'s request: an episode as TMDB lists it now, a film as its row names it. */
    private suspend fun request(row: Download): DownloadRequest = when (row.kind) {
        DownloadKind.Episode -> {
            val show = requireNotNull(row.showTmdbId) { "An episode's show" }
            val season = requireNotNull(row.season) { "An episode's season" }
            val episode = requireNotNull(row.episode) { "An episode's number" }
            DownloadRequest.Episode(lookUp(show, season, episode), row.posterPath)
        }
        DownloadKind.Movie -> DownloadRequest.Movie(
            MovieTarget(requireNotNull(row.movieTmdbId) { "A film's id" }, row.title, row.year, imdb = null, runtimeMinutes = row.runtimeMinutes),
            row.posterPath,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(DownloadChoice::class.java)
    }
}

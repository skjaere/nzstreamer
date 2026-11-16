package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.collection.ChooseState
import io.skjaere.usenet.app.collection.ListSearch
import io.skjaere.usenet.app.collection.ReleaseChooser
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.movieNoReleaseNote
import io.skjaere.usenet.app.play.noRelease
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.app.search.findMovieReleases
import io.skjaere.usenet.app.search.forEpisode
import io.skjaere.usenet.app.search.qualityParts
import io.skjaere.usenet.app.search.rankedAgain
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * What a Download sheet is for (L338): an episode, searched as Choose release searches it, or a film, searched as its page
 * searches it; with the names its download's row keeps, so the Downloads page works offline (L368).
 */
sealed interface DownloadRequest {
    /** The download as its row keeps it: its kind and names, and for an episode its season's count of episodes. */
    val target: DownloadTarget

    /** The show's or the film's name, as the release list names it. */
    val title: String

    /** Under the sheet's title: "Harbor Lights · S02E04 · Low Water", "Copper Crown · 2022". */
    val subtitle: String

    /** How many episodes TMDB lists for the episode's season, by which a season pack's share of it is reckoned; null for a film. */
    val seasonEpisodes: Int?

    /** Episode [play] of its show, whose poster is [posterPath]. */
    data class Episode(val play: EpisodePlay, val posterPath: String? = null) : DownloadRequest {
        override val target: DownloadTarget
            get() = DownloadTarget.episode(
                showTmdbId = play.showTmdbId, title = play.showName, season = play.season, episode = play.episode,
                seasonEpisodes = play.seasonEpisodes, episodeName = play.episodeName, subtitle = play.subtitle, posterPath = posterPath,
                stillPath = play.stillPath, runtimeMinutes = play.runtimeMinutes,
            )
        override val title: String get() = play.showName
        override val subtitle: String get() = "${play.showName} · ${play.subtitle}"
        override val seasonEpisodes: Int? get() = play.seasonEpisodes
    }

    /** The film [movie], whose poster is [posterPath]. */
    data class Movie(val movie: MovieTarget, val posterPath: String? = null) : DownloadRequest {
        override val target: DownloadTarget
            get() = DownloadTarget.movie(movie.tmdbId, movie.title, movie.year, movie.runtimeMinutes, movie.subtitle, posterPath)
        override val title: String get() = movie.title
        override val subtitle: String get() = listOfNotNull(movie.title, movie.subtitle).joinToString(" · ")
        override val seasonEpisodes: Int? get() = null
    }
}

/** When a download queued now starts (L335, L337, W110): [Now], [OnWifi] (on mobile data), or after the [ahead] in the queue. */
sealed interface DownloadStart {
    data object Now : DownloadStart

    /** This phone is on mobile data (the default network metered) while downloads wait for Wi-Fi or Ethernet. */
    data object OnWifi : DownloadStart

    /** [ahead] downloads go first, one at a time. */
    data class AfterQueue(val ahead: Int) : DownloadStart
}

/** What the Download sheet shows: [Finding] while it searches, then [Pick], [WontFit] or [NoRelease]. */
sealed interface DownloadSheetState {
    /** Searching and ranking, as Choose release does: nothing is grabbed until Download (L338). */
    data object Finding : DownloadSheetState

    /**
     * The download ranking's first release, [best], and what it [need]s on this device (its size as the indexer says it, or
     * a season pack's share of the episode), what is [free] for a new download, when it would [start], and whether the
     * sheet says that Android asks about notifications next ([asks], L358). [group]: every Usenet release found, for
     * Choose release, which lists them in download [mode].
     */
    data class Pick(
        val group: ResultGroup,
        val best: RankedItem,
        val mode: ReleaseListMode.Download,
        val need: Long,
        val free: Long,
        val start: DownloadStart,
        val asks: Boolean,
    ) : DownloadSheetState

    /**
     * The first release doesn't fit: it [need]s more than is [free], which is after the [queued] bytes the [queuedDownloads]
     * queued downloads still need and what is kept free for the phone (L348). No Download: Choose release shows what fits,
     * and a tap there downloads, so the sheet says that Android asks about notifications next where it does ([asks], L358).
     */
    data class WontFit(
        val group: ResultGroup,
        val best: RankedItem,
        val mode: ReleaseListMode.Download,
        val need: Long,
        val free: Long,
        val queued: Long,
        val queuedDownloads: Int,
        val asks: Boolean = false,
    ) : DownloadSheetState

    /** No Usenet release to download: what each search of the indexers did ([stages]), as the play sheet says it, and its [note]. */
    data class NoRelease(val stages: List<Pair<String, String>>, val note: String? = null) : DownloadSheetState
}

/**
 * What the Download sheet reads and asks: the app's ([of]), or a test's fakes. [findEpisode] and [findMovie] are the
 * searches, by the ranking [context] given; [space], [rows], [prefs] and [network] what the sheet says of the device;
 * [askDue] and [beforeDownload] Android's notification ask (L358); [enqueue] and [schedule] the download, counted at what
 * the sheet reckoned its pick needs until it is resolved, and its host. What Download starts runs in [outlive], on the
 * main thread, so the sheet's close never cuts it short.
 */
data class DownloadSheetDeps(
    val findEpisode: (request: EpisodeRequest, context: RankingContext) -> Flow<FinderEvent>,
    val findMovie: (movie: MovieTarget, context: RankingContext) -> Flow<MovieFind>,
    /** What a download's releases are ranked by ([AppContainer.downloadRankingContext]). */
    val context: suspend () -> RankingContext,
    /** What downloads take of the primary volume and what a new one may take; null while it isn't there. */
    val space: suspend () -> DownloadSpace?,
    /** Every download's row, for the ones a new download would wait behind. */
    val rows: suspend () -> List<Download>,
    val prefs: suspend () -> DownloadPrefs,
    /** What the device's default network offers now ([AppContainer.defaultNetwork]): metered is mobile data. */
    val network: () -> NetworkNow,
    val askDue: suspend () -> Boolean,
    val enqueue: suspend (target: DownloadTarget, candidates: List<DownloadCandidate>, need: Long) -> Download?,
    val beforeDownload: suspend () -> Unit,
    val schedule: suspend () -> Unit,
    val outlive: CoroutineScope,
    val today: () -> LocalDate = { LocalDate.now() },
) {
    companion object {
        /**
         * The app's: the episode finder and the film's search over the Usenet indexers alone (L370), ranked for downloading;
         * the downloads' storage, rows and settings; the default network; the activity's notification [ask] (none outside
         * MainActivity); the runner's queue, and its host scheduled from the visible app (L335).
         */
        fun of(container: AppContainer, ask: NotificationAsk?) = DownloadSheetDeps(
            findEpisode = { request, context -> flow { emitAll(container.finder.find(request, usenetIndexers(container), context)) } },
            findMovie = { movie, context -> findMovieReleases(container, movie, indexers = { usenetIndexers(container) }, context = { context }) },
            context = { container.downloadRankingContext() },
            space = { container.downloadStorage.space() },
            rows = { container.data.downloads.all().first() },
            prefs = { container.settings.downloads.first() },
            network = { container.defaultNetwork.now.value },
            askDue = { ask?.due() == true },
            enqueue = { target, candidates, need -> container.downloads.enqueue(target, candidates, need) },
            beforeDownload = { ask?.beforeDownload() },
            schedule = { container.downloadHosts.schedule(ScheduleCause.Download) },
            outlive = container.appScope + Dispatchers.Main.immediate,
        )

        /** The indexers a download searches: the Usenet ones, as only their releases download (L370). */
        private suspend fun usenetIndexers(container: AppContainer) = container.indexerConfigs().filter { it.kind == IndexerKind.Newznab }
    }
}

/**
 * The Download sheet's model (L338, L358, L370): it searches for [request] as Choose release does, with a [ReleaseChooser]
 * of its own ranked for downloading (L355), its list never ranked again for a Cast device, and says what it found. Finding
 * only searches: nothing is grabbed until the download's turn comes in its host.
 * - [DownloadSheetState.Pick]: the download ranking's first Usenet release, with the space free and when it starts.
 * - [DownloadSheetState.WontFit]: the first doesn't fit; Choose release shows the ones that do.
 * - [DownloadSheetState.NoRelease]: nothing to download, with the indexers' lines.
 *
 * Torrents are left out (L370). [download] (the sheet's Download, or a tap in the list) queues the download with up to 10
 * releases that fit, in download order, the one chosen first, and the sheet is [closed]; then Android's notification ask
 * where it is due, then the host's schedule. The search runs in [scope], the sheet's, and goes with it.
 */
class DownloadSheetModel(private val request: DownloadRequest, private val deps: DownloadSheetDeps, private val scope: CoroutineScope) {
    private val chooser = ReleaseChooser<DownloadRequest>(scope)

    /** What the search said of its indexers, for [DownloadSheetState.NoRelease]: the last of it, set before its releases. */
    private var said = DownloadSheetState.NoRelease(emptyList())

    private val _state = MutableStateFlow<DownloadSheetState>(DownloadSheetState.Finding)
    val state: StateFlow<DownloadSheetState> = _state.asStateFlow()

    private val _downloading = MutableStateFlow(false)

    /** Whether a Download is under way: the sheet's buttons wait for it. */
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _closed = MutableStateFlow(false)

    /**
     * Whether the sheet is done: the download queued. Its notification ask and its host's schedule go on after, so a page
     * remade meanwhile (a rotation with Android's dialog up) never shows the sheet again, to queue the download twice.
     */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private var started = false

    /** What the search found last, which [shown] weighs again. */
    private var found: ResultGroup? = null

    /** Searches, once. */
    fun start() {
        if (started) return
        started = true
        chooser.chooseAsFound(request) { search() }
        scope.launch {
            chooser.state.collectLatest { chosen ->
                if (chosen is ChooseState.Ready) {
                    found = chosen.group
                    _state.value = decide(chosen.group)
                }
            }
        }
    }

    /**
     * The sheet is in front again, maybe from Android's storage page (Won't fit's Storage settings): what was found is
     * weighed again against the space as it is now, with no new search, so room made there offers Download.
     */
    fun shown() {
        val group = found ?: return
        if (_downloading.value || _closed.value) return
        scope.launch {
            val now = decide(group)
            // A search that found more meanwhile has said its own.
            if (found === group && !_downloading.value) _state.value = now
        }
    }

    /** The sheet's Download: the pick, with the other releases that fit after it. */
    fun download() {
        val pick = _state.value as? DownloadSheetState.Pick ?: return
        download(pick.best, pick.group, pick.mode)
    }

    /**
     * Queues [chosen] with [group]'s other releases that fit after it, in their order (L338), closes the sheet, then asks
     * Android about notifications where that is due and schedules the host: either answer starts the download (L358).
     * Once. Where the runner queues nothing (a TV's), nothing is asked or scheduled.
     */
    private fun download(chosen: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download) {
        if (_downloading.value || _closed.value) return
        _downloading.value = true
        deps.outlive.launch {
            try {
                val candidates = candidates(chosen, group, mode)
                // Counted at what it needs here, a season pack's share of its episode, not the pack's whole size.
                deps.enqueue(request.target, candidates, mode.need(chosen)) ?: return@launch
                _closed.value = true
                deps.beforeDownload()
                deps.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't queue the download: {}", e::class.simpleName)
            } finally {
                _closed.value = true
            }
        }
    }

    /** The search, as Choose release's: [request]'s releases ranked for downloading, Usenet's alone. */
    private fun ListSearch.search(): Flow<ResultGroup> = flow {
        val context = deps.context()
        var shown = false
        try {
            when (val r = request) {
                is DownloadRequest.Episode -> deps.findEpisode(r.play.request, context).collect { event ->
                    val result = (event as? FinderEvent.Finished)?.result ?: (event as? FinderEvent.Joined)?.result ?: return@collect
                    val found = ResultGroup(
                        key = r.play.label, kind = GroupKind.Episode, season = r.play.season, episodes = listOf(r.play.episode), airDate = null,
                        year = null, playable = result.playable.map { RankedItem(it.item, it.ranked) }, hidden = result.hidden.map { RankedItem(it.item, it.ranked) },
                    ).usenetOnly()
                    val lines = noRelease(r.play, result, deps.today())
                    said = DownloadSheetState.NoRelease(lines.stages, lines.note)
                    rankedBy(context) { found.rankedAgain(it.forEpisode(r.play.request)) }
                    emit(found)
                    shown = true
                }
                is DownloadRequest.Movie -> deps.findMovie(r.movie, context).collect { find ->
                    val found = (find.group ?: ResultGroup(r.movie.title, GroupKind.Movie, null, emptyList(), null, r.movie.year, emptyList(), emptyList())).usenetOnly()
                    said = DownloadSheetState.NoRelease(find.stages, movieNoReleaseNote(r.movie.releaseDate, r.movie.released, deps.today()))
                    rankedBy(context) { found.rankedAgain(it) }
                    emit(found)
                    shown = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't search for a download: {}", e::class.simpleName)
        }
        // A search that found nothing to say still ends the finding.
        if (!shown) emit(ResultGroup(request.title, GroupKind.Other, null, emptyList(), null, null, emptyList(), emptyList()))
    }

    /** What the sheet shows for [group], found: the pick and whether it fits, when it starts, and the ask's line. */
    private suspend fun decide(group: ResultGroup): DownloadSheetState {
        val best = group.playable.firstOrNull() ?: return said
        val space = deps.space()
        val free = space?.free ?: 0
        val startsOnWifi = deps.prefs().unmeteredOnly && !deps.network().unmetered
        lateinit var mode: ReleaseListMode.Download
        mode = ReleaseListMode.Download(free, startsOnWifi, request.seasonEpisodes) { item -> download(item, group, mode) }
        val need = mode.need(best)
        if (!mode.fits(best)) return DownloadSheetState.WontFit(group, best, mode, need, free, space?.queued ?: 0, space?.queuedDownloads ?: 0, deps.askDue())
        val ahead = deps.rows().count { it.state in AHEAD }
        val start = when {
            startsOnWifi -> DownloadStart.OnWifi
            ahead > 0 -> DownloadStart.AfterQueue(ahead)
            else -> DownloadStart.Now
        }
        return DownloadSheetState.Pick(group, best, mode, need, free, start, deps.askDue())
    }

    companion object {
        const val TITLE = "Download"
        const val FINDING = "Finding releases…"
        const val FINDING_NOTE = "Searches your indexers and ranks what they find for downloading. Nothing is downloaded until you choose Download."
        const val BEST = "Best match"
        const val INDEXERS_SIZE = "The size is the indexer's. It is checked again when the download starts."
        const val STAYS = "Downloads stay in NZStreamer's own storage on this device, and are deleted if you uninstall the app."
        const val ASKS = "Android asks next whether NZStreamer may show notifications. They show how the download is going, with Pause and Cancel."
        const val WONT_FIT = "Won't fit on this device"
        const val WONT_FIT_NOTE = "Choose release shows which releases fit. Storage settings opens Android's page for making room."
        const val NO_RELEASE = "No playable release found"
        const val CHOOSE_RELEASE = "Choose release"
        const val STORAGE_SETTINGS = "Storage settings"

        /** The releases a download keeps (L338). */
        private const val KEPT = DownloadRunner.MAX_CANDIDATES

        /** The downloads a new one waits behind: those that go on without the user's word, one at a time. */
        private val AHEAD = setOf(
            DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Waiting, DownloadState.Paused, DownloadState.Stopped,
        )

        /**
         * What a download of [chosen] keeps to try (L338, L340): [chosen], then [group]'s other Usenet releases that fit
         * ([ReleaseListMode.Download.fits]), in the list's order, 10 at most. One that doesn't fit would only be found too big
         * after its grab.
         */
        fun candidates(chosen: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download): List<DownloadCandidate> =
            (listOf(chosen) + group.playable.filter { it.key != chosen.key && mode.fits(it) })
                .filter { it.item.infoHash == null }
                .take(KEPT)
                .map { DownloadCandidate(it.item.indexerId, it.item.guid, it.item.title, it.item.downloadTemplate, it.item.sizeBytes, it.item.passworded) }

        /** "1080p · WEB-DL · HEVC · about 1.2 GB": the release's quality as its name says it, and what it needs here. */
        fun quality(item: RankedItem, need: Long): String = (qualityParts(item) + "about ${formatSize(need)}").joinToString(" · ")

        /** "14.0 GB free on this device". */
        fun free(free: Long): String = "${formatSize(free)} free on this device"

        /** When it starts, and the line under that: "Starts now"; "Starts on Wi-Fi", and why; after the queue, and why. */
        fun start(start: DownloadStart): Pair<String, String?> = when (start) {
            DownloadStart.Now -> "Starts now" to null
            DownloadStart.OnWifi -> "Starts on Wi-Fi" to "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads."
            is DownloadStart.AfterQueue ->
                (if (start.ahead == 1) "Starts after the download in the queue" else "Starts after the downloads in the queue") to "One download runs at a time."
        }

        /** "Needs about 4.3 GB · 3.0 GB free on this device". */
        fun needs(need: Long, free: Long): String = "Needs about ${formatSize(need)} · ${free(free)}"

        /**
         * Why less is free than Android's own page says: "That is after the 1.2 GB the queued download still needs, and the
         * 1.0 GB kept free for the phone."; with none queued, only what is kept free.
         */
        fun after(queued: Long, queuedDownloads: Int): String {
            val kept = "the ${formatSize(DownloadSpace.KEPT_FREE)} kept free for the phone"
            return when {
                queued <= 0 -> "That is after $kept."
                queuedDownloads == 1 -> "That is after the ${formatSize(queued)} the queued download still needs, and $kept."
                else -> "That is after the ${formatSize(queued)} the queued downloads still need, and $kept."
            }
        }

        private val log = LoggerFactory.getLogger(DownloadSheetModel::class.java)
    }
}

/** [this] with its torrents left out: a download is Usenet's alone (L370). */
private fun ResultGroup.usenetOnly(): ResultGroup =
    copy(playable = playable.filter { it.item.infoHash == null }, hidden = hidden.filter { it.item.infoHash == null })

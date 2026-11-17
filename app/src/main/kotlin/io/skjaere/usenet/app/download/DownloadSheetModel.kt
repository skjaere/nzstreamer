package io.skjaere.usenet.app.download

import android.annotation.SuppressLint
import android.content.Context
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.collection.ChooseState
import io.skjaere.usenet.app.collection.ListSearch
import io.skjaere.usenet.app.collection.ReleaseChooser
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.ReleaseName
import io.skjaere.usenet.app.play.indexerGrab
import io.skjaere.usenet.app.play.movieNoReleaseNote
import io.skjaere.usenet.app.play.noRelease
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.app.search.findMovieReleases
import io.skjaere.usenet.app.search.forEpisode
import io.skjaere.usenet.app.search.qualityParts
import io.skjaere.usenet.app.search.rankedAgain
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.discCopy
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.releases.ParsedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.SeasonFind
import io.skjaere.usenet.search.SeasonRequest
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * What a Download sheet is for (L338): an episode, searched as Choose release searches it, or a film, searched as its page
 * searches it; or a file of an NZB opened on Import, which is its own release, searched for nowhere (L455); with the names
 * its download's row keeps, so the Downloads page works offline (L368).
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

    /**
     * [file] of the NZB [nzbId] the user opened on Import, titled [nzbTitle] (L455): nothing searched or ranked, the file is
     * the release. [videos]: how many videos the NZB holds, by which the sheet says only this file is downloaded. [source]:
     * the NZB's bytes and the archive password it was opened with, which its download keeps (L423). Named as Up next names a
     * release played without TMDB: "Quiet Meridian · S01E02".
     */
    data class File(val nzbId: String, val file: FileCandidate, val nzbTitle: String, val videos: Int, val source: DownloadStorage.Source) : DownloadRequest {
        override val target: DownloadTarget get() = DownloadTarget.release(nzbId, file.index, file.name, nzbTitle)
        override val title: String get() = ReleaseName(file.name).title
        override val subtitle: String get() = ReleaseName(file.name).let { listOfNotNull(it.title, it.tag) }.joinToString(" · ")
        override val seasonEpisodes: Int? get() = null

        /** The file's exact size: what it needs on this device. */
        val size: Long get() = file.size

        /** Import's own release of the NZB, its download's one candidate, at the file's exact size. */
        val candidate: DownloadCandidate get() = DownloadCandidate(IMPORTED, nzbId, nzbTitle, size = file.size)

        /** The file chosen in the NZB, which Resolve picks by its name and size (L457). */
        val pick: DownloadPick get() = DownloadPick(file.index, file.name, file.size, nzbId)

        // The file's and the NZB's names and the password are never in a log line or a trace.
        override fun toString() = "DownloadRequest.File(${file.index}, ${file.size} bytes)"

        companion object {
            /**
             * [file] of [nzb], prepared on Import from [bytes], as its Download asks for it, with the password it was opened
             * with (what prepare unlocked it with, kept in the prepared NZB); null where Import offers no Download: a file that
             * isn't a video, or any file of a copy of a disc, which plays as no single video (L455).
             */
            fun of(nzb: PreparedNzb, file: FileCandidate, bytes: ByteArray): File? {
                if (!file.isVideo || nzb.discCopy() != null) return null
                return File(nzb.id, file, nzb.title, nzb.files.count { it.isVideo }, DownloadStorage.Source(bytes, nzb.metadata.orderedArchiveNzb.password))
            }
        }
    }
}

/**
 * When a download queued now starts (L335, L337, L365, L380, W110): [Now]; [OnWifi] (on mobile data); [Online] (no
 * connection now); [Charging]; [Held] (until Resume); or after the [AfterQueue.ahead] in the queue.
 */
sealed interface DownloadStart {
    data object Now : DownloadStart

    /** This phone is on mobile data (the default network metered) while downloads wait for Wi-Fi or Ethernet. */
    data object OnWifi : DownloadStart

    /** This phone has no connection now: it starts once there is one, Wi-Fi or Ethernet where [wifiOnly]. */
    data class Online(val wifiOnly: Boolean) : DownloadStart

    /** Only while charging is on (L365) and this phone isn't on the charger: it starts once it is. */
    data object Charging : DownloadStart

    /** A download paused or stopped by Android holds the whole queue (L380): nothing starts until Resume. */
    data object Held : DownloadStart

    /** [ahead] downloads go first, one at a time. */
    data class AfterQueue(val ahead: Int) : DownloadStart
}

/**
 * What the Download sheet shows: [Finding] while it searches, then [Pick], [WontFit] or [NoRelease]; for a file opened on
 * Import, [File] or [FileWontFit], with no search.
 */
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

    /**
     * A file chosen in a pack, back on the sheet (L403, L435): the pack [item] of [group], listed in [mode], and its [file],
     * which needs its own exact size; the [chosen] file and the pack's NZB that Download keeps with the download; what is
     * [free] for a new download, when it would [start], and whether the sheet says that Android asks next ([asks], L358).
     */
    data class Chosen(
        val group: ResultGroup,
        val item: RankedItem,
        val mode: ReleaseListMode.Download,
        val file: FileChoice,
        val chosen: ChosenFile,
        val free: Long,
        val start: DownloadStart,
        val asks: Boolean,
    ) : DownloadSheetState

    /**
     * A file of an NZB opened on Import (L455), [file], whose exact size fits what is [free] for a new download: when it
     * would [start], and whether the sheet says that Android asks about notifications next ([asks], L358).
     */
    data class File(val file: DownloadRequest.File, val free: Long, val start: DownloadStart, val asks: Boolean) : DownloadSheetState

    /**
     * The file doesn't fit: its exact size is more than is [free], which is after the [queued] bytes the [queuedDownloads]
     * queued downloads still need and what is kept free for the phone (L348). No Download, and no Choose release: the file
     * is the one release there is (L455).
     */
    data class FileWontFit(val file: DownloadRequest.File, val free: Long, val queued: Long, val queuedDownloads: Int) : DownloadSheetState
}

/**
 * What a Download sheet reads and asks, one title's or a season's: the app's ([of]), or a test's fakes. [findEpisode],
 * [findMovie] and [findSeason] are the searches, by the ranking [context] given; [space], [rows], [prefs], [network] and
 * [charging] what the sheet says of the device; [askDue] and [beforeDownload] Android's notification ask (L358);
 * [enqueue] (one title, with a file chosen in a pack where one was), [enqueueAll] (a season's episodes) or [enqueueImport]
 * (a file opened on Import) and [schedule] the download, counted at what the sheet reckoned its pick needs until it is
 * resolved, and its host. What Download starts runs in [outlive], on the main thread, so the sheet's close never cuts it
 * short. [grab] and [prepare] are Choose file's.
 */
data class DownloadSheetDeps(
    val findEpisode: (request: EpisodeRequest, context: RankingContext) -> Flow<FinderEvent>,
    val findMovie: (movie: MovieTarget, context: RankingContext) -> Flow<MovieFind>,
    /** A season's one search for its episodes ([io.skjaere.usenet.search.EpisodeReleaseFinder.findSeason], L393). */
    val findSeason: suspend (request: SeasonRequest, context: RankingContext) -> SeasonFind,
    /** What a download's releases are ranked by ([AppContainer.downloadRankingContext]). */
    val context: suspend () -> RankingContext,
    /** What downloads take of the primary volume and what a new one may take; null while it isn't there. */
    val space: suspend () -> DownloadSpace?,
    /** Every download's row, for the ones a new download would wait behind. */
    val rows: suspend () -> List<Download>,
    val prefs: suspend () -> DownloadPrefs,
    /** What the device's default network offers now ([AppContainer.defaultNetwork]): metered is mobile data. */
    val network: () -> NetworkNow,
    /** Whether the device is on the charger now, which Only while charging waits for (L365). */
    val charging: () -> Boolean = { true },
    val askDue: suspend () -> Boolean,
    val enqueue: suspend (target: DownloadTarget, candidates: List<DownloadCandidate>, need: Long, chosen: ChosenFile?) -> Download?,
    /** A season's episodes queued at once, in episode order ([DownloadRunner.enqueueAll]): the rows queued, none on a TV. */
    val enqueueAll: suspend (items: List<DownloadRunner.QueueItem>) -> List<Download>,
    /**
     * A file of an NZB opened on Import queued with the NZB and its password kept for it ([DownloadRunner.enqueueImport],
     * L423): the row, none on a TV.
     */
    val enqueueImport: suspend (target: DownloadTarget, candidate: DownloadCandidate, pick: DownloadPick, source: DownloadStorage.Source) -> Download?,
    val beforeDownload: suspend () -> Unit,
    val schedule: suspend () -> Unit,
    val outlive: CoroutineScope,
    /** Choose file's one grab of a pack's NZB from its indexer, with the password sent beside it (L403). */
    val grab: suspend (item: RankedItem) -> FetchedNzb,
    /** Choose file's one prepare of a pack's NZB, outside the NZB store (DN1's L383). */
    val prepare: suspend (nzb: ByteArray, title: String, password: String?) -> PrepareOutcome,
    /** Whether a provider is left out of the engine: a pack missing articles may then be whole on it, as a play's words say. */
    val providersLeftOut: () -> Boolean = { false },
    val today: () -> LocalDate = { LocalDate.now() },
    /**
     * Where Choose file reads a pack's NZB for its own password: off the sheet's main thread, as a pack's NZB of several MB
     * takes a while to parse, as the rest of the app parses one (a test's dispatcher keeps its virtual time).
     */
    val nzbReading: CoroutineContext = Dispatchers.Default,
) {
    companion object {
        /**
         * The app's: the episode finder, the film's search and the season's over the Usenet indexers alone (L370), ranked
         * for downloading; the downloads' storage, rows and settings; the default network and the charger, as [context]
         * reads it; the activity's notification [ask] (none outside MainActivity); the runner's queue, each download queued
         * with its pictures fetched (L411), and its host scheduled from the visible app (L335). Choose file's grab is the
         * coordinator's, as a download's Resolve grabs (its test seam), and its prepare the container's preparer, which
         * keeps nothing in the NZB store.
         */
        @SuppressLint("VisibleForTests") // The coordinator's grab, read as each grab is made, so a test serves both.
        fun of(container: AppContainer, ask: NotificationAsk?, context: Context) = DownloadSheetDeps(
            findEpisode = { request, context -> flow { emitAll(container.finder.find(request, usenetIndexers(container), context)) } },
            findMovie = { movie, context -> findMovieReleases(container, movie, indexers = { usenetIndexers(container) }, context = { context }) },
            findSeason = { request, context -> container.finder.findSeason(request, usenetIndexers(container), context) },
            context = { container.downloadRankingContext() },
            space = { container.downloadStorage.space() },
            rows = { container.data.downloads.all().first() },
            prefs = { container.settings.downloads.first() },
            network = { container.defaultNetwork.now.value },
            charging = { pluggedIn(context) },
            askDue = { ask?.due() == true },
            // One per title: a sheet restored after its Download, or a second tap, has the download already queued. Each one
            // queued has its poster and still fetched now, from the visible app (L411).
            enqueue = { target, candidates, need, chosen ->
                container.downloads.enqueue(target, candidates, need, onePerTitle = true, pick = chosen?.pick, grab = chosen?.grab)
                    .also { row -> container.downloadImages.enqueued(listOfNotNull(row)) }
            },
            enqueueAll = { items -> container.downloads.enqueueAll(items).also(container.downloadImages::enqueued) },
            enqueueImport = { target, candidate, pick, source -> container.downloads.enqueueImport(target, candidate, pick, source) },
            beforeDownload = { ask?.beforeDownload() },
            schedule = { container.downloadHosts.schedule(ScheduleCause.Download) },
            outlive = container.appScope + Dispatchers.Main.immediate,
            grab = { item ->
                val indexers = container.indexerConfigs().associateBy { it.id }
                indexerGrab(mapOf(item.key to item.item), indexers) { indexer, nzb -> PlaybackCoordinator.fetchNzb(container, indexer, nzb) }
                    .fetchWithPassword(item.playCandidate)
            },
            prepare = { nzb, title, password -> container.preparer.prepare(nzb, title, password) },
            providersLeftOut = { container.engine.leftOut.value.isNotEmpty() },
        )

        /** The indexers a download searches: the app's Usenet ones ([usenetOnly]). */
        private suspend fun usenetIndexers(container: AppContainer) = usenetOnly(container.indexerConfigs())

        /**
         * Of [configs], the Usenet indexers alone, as only their releases download (L370): a torrent indexer's calls, and
         * the debrid cache checks after them, would be spent on releases left out.
         */
        internal fun usenetOnly(configs: List<IndexerConfig>): List<IndexerConfig> = configs.filter { it.kind == IndexerKind.Newznab }
    }
}

/**
 * The Download sheet's model (L338, L358, L370): it searches for [request] as Choose release does, with a [ReleaseChooser]
 * of its own ranked for downloading (L355), its list never ranked again for a Cast device, and says what it found. Finding
 * only searches: nothing is grabbed until the download's turn comes in its host.
 * - [DownloadSheetState.Pick]: the download ranking's first Usenet release, with the space free and when it starts.
 * - [DownloadSheetState.WontFit]: the first doesn't fit; Choose release shows the ones that do.
 * - [DownloadSheetState.NoRelease]: nothing to download, with the indexers' lines.
 * - [DownloadSheetState.Chosen]: a file chosen in a pack from the list (Choose file, L403, L435), with its exact size.
 *
 * Torrents are left out (L370). [download] (the sheet's Download, or a tap in the list) queues the download with up to 10
 * releases that fit, in download order, the one chosen first, and the sheet is [closed]; then Android's notification ask
 * where it is due, then the host's schedule. A file chosen in a pack goes with it: the pack first, the file's size its
 * need, the pack's NZB kept with the download. The search runs in [scope], the sheet's, and goes with it.
 *
 * A file of an NZB opened on Import (L455) is searched for nowhere: the sheet weighs its exact size against the room now
 * ([DownloadSheetState.File], [DownloadSheetState.FileWontFit]), and [download] queues it with the NZB and its password.
 *
 * The list's Choose file ([chooseFile], an episode's alone) opens a pack's files ([choice]) in [scope] too. A file chosen
 * there comes back to the sheet as its Chosen file; where the list replaces download [replacing]'s releases (the Downloads
 * page's Choose release), it replaces them at once, as a tap in that list does. A pack whose files couldn't be listed is
 * marked in the list with why ([failed]).
 */
class DownloadSheetModel(
    private val request: DownloadRequest,
    private val deps: DownloadSheetDeps,
    private val scope: CoroutineScope,
    private val replacing: String? = null,
) {
    private val chooser = ReleaseChooser<DownloadRequest>(scope)

    private val _choice = MutableStateFlow<DownloadFileChoice?>(null)

    /** Choose file under way on a pack of the list: its sheet over the list while it is there. */
    val choice: StateFlow<DownloadFileChoice?> = _choice.asStateFlow()

    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Why each pack whose Choose file couldn't list its files failed, by key, as the list marks it (L435). */
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()

    /** Choose file on [item], one of the list shown now: where its card offers it ([ReleaseListMode.Download.fileChoice]). */
    fun chooseFile(item: RankedItem) {
        when (val s = _state.value) {
            is DownloadSheetState.Pick -> chooseFile(item, s.group, s.mode)
            is DownloadSheetState.WontFit -> chooseFile(item, s.group, s.mode)
            is DownloadSheetState.Chosen -> chooseFile(item, s.group, s.mode)
            else -> Unit
        }
    }

    /** Choose file's Cancel, Try another release and Close: its sheet goes, and the grab or prepare under way with it. */
    fun closeChoice() {
        _choice.value?.cancel()
        _choice.value = null
    }

    /**
     * Opens [item]'s files, a pack of [group] listed in [mode]: an episode's, where its card offers Choose file (never
     * greyed for Wi-Fi), and no Download under way. One open before is closed first. Whether it would start on Wi-Fi is
     * read again as it is tapped (L403): a list drawn before the phone went on mobile data opens nothing.
     */
    private fun chooseFile(item: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download) {
        val play = (request as? DownloadRequest.Episode)?.play ?: return
        if (mode.fileChoice(item) != ReleaseListMode.Download.FileChoiceOffer.Offered || _downloading.value || _closed.value) return
        scope.launch {
            if (startsOnWifi(deps.prefs(), deps.network())) return@launch
            closeChoice()
            val choice = DownloadFileChoice(
                item, request.subtitle, FilePicker.Episode(play.season, play.episode), play.episodeName, deps, scope, replacing,
                onFailed = { reason -> _failed.update { it + (item.key to reason) } },
            ) { file, picked -> chosen(item, group, mode, file, picked) }
            _choice.value = choice
            choice.start()
        }
    }

    /**
     * [file] of the pack [item] chosen (L435): from the Downloads page its download's release replaced at once; else back
     * on the sheet as its Chosen file, with the space free, when it would start and the ask's line, as a pick says them.
     * The files' sheet goes once the Chosen file is there to show, so the sheet never shows its pick before it meanwhile.
     */
    private fun chosen(item: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download, file: FileChoice, chosen: ChosenFile) {
        if (replacing != null) {
            _choice.value = null
            return download(item, group, mode, file.sizeBytes, chosen)
        }
        scope.launch {
            val prefs = deps.prefs()
            val network = deps.network()
            val free = deps.space()?.free ?: 0
            _state.value = DownloadSheetState.Chosen(group, item, mode, file, chosen, free, startOf(deps.rows(), prefs, network, deps.charging), deps.askDue())
            _choice.value = null
        }
    }

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

    /** Searches, once; a file opened on Import is weighed alone, with no search (L455). */
    fun start() {
        if (started) return
        started = true
        if (request is DownloadRequest.File) {
            scope.launch { _state.value = weigh(request) }
            return
        }
        chooser.chooseAsFound(request) { search() }
        scope.launch {
            chooser.state.collectLatest { chosen ->
                if (chosen is ChooseState.Ready) {
                    found = chosen.group
                    val now = decide(chosen.group)
                    // A file chosen in a pack stays chosen as more is found: Choose release lists the new releases.
                    if (_state.value !is DownloadSheetState.Chosen) _state.value = now
                }
            }
        }
    }

    /**
     * The sheet is in front again, maybe from Android's storage page (Won't fit's Storage settings): what was found is
     * weighed again against the space as it is now, with no new search, so room made there offers Download.
     */
    fun shown() {
        if (request is DownloadRequest.File) {
            if (_downloading.value || _closed.value || _state.value == DownloadSheetState.Finding) return
            scope.launch {
                val now = weigh(request)
                if (!_downloading.value && !_closed.value) _state.value = now
            }
            return
        }
        val group = found ?: return
        if (_downloading.value || _closed.value) return
        // A file chosen in a pack stays chosen; the space, the network and when it starts are read again, and its list's.
        (_state.value as? DownloadSheetState.Chosen)?.let { chosen ->
            scope.launch {
                val now = again(chosen)
                if (_state.value === chosen && !_downloading.value) _state.value = now
            }
            return
        }
        scope.launch {
            val now = decide(group)
            // A search that found more meanwhile has said its own.
            if (found === group && !_downloading.value && _state.value !is DownloadSheetState.Chosen) _state.value = now
        }
    }

    /**
     * The sheet's Download: the pick, with the other releases that fit after it; a file chosen in a pack, the pack first; a
     * file opened on Import, with its NZB and the password typed for it (L455).
     */
    fun download() {
        when (val s = _state.value) {
            is DownloadSheetState.Pick -> download(s.best, s.group, s.mode)
            is DownloadSheetState.Chosen -> download(s.item, s.group, s.mode, s.file.sizeBytes, s.chosen)
            is DownloadSheetState.File -> queue { deps.enqueueImport(s.file.target, s.file.candidate, s.file.pick, s.file.source) }
            else -> Unit
        }
    }

    /**
     * Queues [chosen] with [group]'s other releases that fit after it, in their order (L338), closes the sheet, then asks
     * Android about notifications where that is due and schedules the host: either answer starts the download (L358).
     * Once. Where the runner queues nothing (a TV's), nothing is asked or scheduled. With a [file] chosen in the pack
     * [chosen] (L403), the download keeps it and the pack's NZB, and counts its exact size as its [need].
     */
    private fun download(chosen: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download, need: Long = mode.need(chosen), file: ChosenFile? = null) =
        queue {
            // Counted at what it needs here, a season pack's share of its episode, not the pack's whole size; a file chosen
            // in it, at that file's own size.
            deps.enqueue(request.target, candidates(chosen, group, mode), need, file)
        }

    /**
     * Queues the download [enqueue] makes, closes the sheet, then asks Android about notifications where that is due and
     * schedules the host (L358). Once; where the runner queues nothing (a TV's), nothing is asked or scheduled.
     */
    private fun queue(enqueue: suspend () -> Download?) {
        if (_downloading.value || _closed.value) return
        _downloading.value = true
        deps.outlive.launch {
            try {
                enqueue() ?: return@launch
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
                // Never searched for: [start] weighs it alone.
                is DownloadRequest.File -> Unit
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
        val prefs = deps.prefs()
        val network = deps.network()
        val mode = modeOf(group, free, prefs, network)
        val need = mode.need(best)
        if (!mode.fits(best)) return DownloadSheetState.WontFit(group, best, mode, need, free, space?.queued ?: 0, space?.queuedDownloads ?: 0, deps.askDue())
        return DownloadSheetState.Pick(group, best, mode, need, free, startOf(deps.rows(), prefs, network, deps.charging), deps.askDue())
    }

    /**
     * [group]'s list in download mode with [free] room, greyed for Wi-Fi as [prefs] and [network] say: a tap downloads the
     * release, and an episode's Choose file opens a pack's files (L403).
     */
    private fun modeOf(group: ResultGroup, free: Long, prefs: DownloadPrefs, network: NetworkNow): ReleaseListMode.Download {
        lateinit var mode: ReleaseListMode.Download
        // Choose file is an episode's: which of a pack's videos is it (L403).
        val chooseFile = { item: RankedItem -> chooseFile(item, group, mode) }.takeIf { request is DownloadRequest.Episode }
        mode = ReleaseListMode.Download(free, startsOnWifi(prefs, network), request.seasonEpisodes, chooseFile) { item -> download(item, group, mode) }
        return mode
    }

    /**
     * What the sheet shows for [file], opened on Import (L455): whether its exact size fits the room now, when it would
     * start, and the ask's line.
     */
    private suspend fun weigh(file: DownloadRequest.File): DownloadSheetState {
        val space = deps.space()
        val free = space?.free ?: 0
        if (file.size > free) return DownloadSheetState.FileWontFit(file, free, space?.queued ?: 0, space?.queuedDownloads ?: 0)
        return DownloadSheetState.File(file, free, startOf(deps.rows(), deps.prefs(), deps.network(), deps.charging), deps.askDue())
    }

    /** [chosen] with its file kept, read again as the sheet is in front again: the space free, when it starts, its list. */
    private suspend fun again(chosen: DownloadSheetState.Chosen): DownloadSheetState.Chosen {
        val free = deps.space()?.free ?: 0
        val prefs = deps.prefs()
        val network = deps.network()
        return chosen.copy(mode = modeOf(chosen.group, free, prefs, network), free = free, start = startOf(deps.rows(), prefs, network, deps.charging), asks = deps.askDue())
    }

    companion object {
        const val TITLE = "Download"
        const val FINDING = "Finding releases…"
        const val FINDING_NOTE = "Searches your indexers and ranks what they find for downloading. Nothing is downloaded until you choose Download."
        const val BEST = "Best match"
        const val CHOSEN = "Chosen file"
        const val FILES_SIZE = "The size is the file's, read from the release."
        const val INDEXERS_SIZE = "The size is the indexer's. It is checked again when the download starts."
        const val STAYS = "Downloads stay in NZStreamer's own storage on this device, and are deleted if you uninstall the app."
        const val ASKS = "Android asks next whether NZStreamer may show notifications. They show how the download is going, with Pause and Cancel."
        const val WONT_FIT = "Won't fit on this device"

        /** Won't fit's note for a file opened on Import, which has no release to choose from (L455): the storage page's line alone. */
        const val STORAGE_NOTE = "Storage settings opens Android's page for making room."
        const val WONT_FIT_NOTE = "Choose release shows which releases fit. $STORAGE_NOTE"

        /** A file opened on Import's card, in Best match's place (L455). */
        const val THIS_FILE = "This file"
        const val NO_RELEASE = "No playable release found"
        const val CHOOSE_RELEASE = "Choose release"
        const val STORAGE_SETTINGS = "Storage settings"

        /** The releases a download keeps (L338). */
        private const val KEPT = DownloadRunner.MAX_CANDIDATES

        /** The downloads a new one waits behind: those that go on without the user's word, one at a time. */
        private val AHEAD = setOf(DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Waiting)

        /** The downloads that hold the whole queue until Resume (L380). */
        private val HOLDS = setOf(DownloadState.Paused, DownloadState.Stopped)

        private const val SETTINGS = "Settings › Downloads"

        /**
         * What a download of [chosen] keeps to try (L338, L340): [chosen], then [group]'s other Usenet releases that fit
         * ([ReleaseListMode.Download.fits]), in the list's order, 10 at most. One that doesn't fit would only be found too big
         * after its grab.
         */
        fun candidates(chosen: RankedItem, group: ResultGroup, mode: ReleaseListMode.Download): List<DownloadCandidate> =
            (listOf(chosen) + group.playable.filter { it.key != chosen.key && mode.fits(it) })
                .filter { it.item.infoHash == null }
                .take(KEPT)
                .map(::candidate)

        /** [item] as a download keeps it to try: its indexer and guid, its name, its link as the app keeps it, its size, passworded. */
        internal fun candidate(item: RankedItem): DownloadCandidate =
            DownloadCandidate(item.item.indexerId, item.item.guid, item.item.title, item.item.downloadTemplate, item.item.sizeBytes, item.item.passworded)

        /**
         * When a download queued now starts, as the host would start it: what holds it first, a hold only Resume ends (L380),
         * then the network and the charger the host waits for (L335, L365), then the queue's [rows] still to go on ahead of
         * it, one at a time (L337).
         */
        internal fun startOf(rows: List<Download>, prefs: DownloadPrefs, network: NetworkNow, charging: () -> Boolean): DownloadStart {
            val ahead = rows.count { it.state in AHEAD }
            return when {
                rows.any { it.state in HOLDS } -> DownloadStart.Held
                !network.usable -> DownloadStart.Online(prefs.unmeteredOnly)
                startsOnWifi(prefs, network) -> DownloadStart.OnWifi
                prefs.whileCharging && !charging() -> DownloadStart.Charging
                ahead > 0 -> DownloadStart.AfterQueue(ahead)
                else -> DownloadStart.Now
            }
        }

        /** Mobile data: a connection, metered, while downloads wait for Wi-Fi or Ethernet (W110); none at all isn't. */
        private fun startsOnWifi(prefs: DownloadPrefs, network: NetworkNow): Boolean = prefs.unmeteredOnly && network.usable && !network.unmetered

        /** "1080p · WEB-DL · HEVC · about 1.2 GB": the release's quality as its name says it, and what it needs here. */
        fun quality(item: RankedItem, need: Long): String = (qualityParts(item) + "about ${formatSize(need)}").joinToString(" · ")

        /** "1080p · WEB-DL · 1.3 GB": the pack's quality as its name says it, and the chosen file's exact size. */
        fun chosenQuality(chosen: DownloadSheetState.Chosen): String = (qualityParts(chosen.item) + formatSize(chosen.file.sizeBytes)).joinToString(" · ")

        /** Over the chosen file's name: "Season pack · only this file is downloaded:"; a release of several episodes, without its kind. */
        fun onlyThisFile(item: RankedItem): String = onlyThisFile(item.parsed)

        private fun onlyThisFile(parsed: ParsedRelease): String = if (parsed.isSeasonPack) "Season pack · only this file is downloaded:" else "Only this file is downloaded:"

        /**
         * "1080p · WEB-DL · 1.3 GB": a file opened on Import's quality as its name says it, else as its NZB's does, and its
         * exact size (L455).
         */
        fun fileQuality(file: DownloadRequest.File): String =
            (qualityParts(ReleaseParser.parse(file.file.name)).ifEmpty { qualityParts(ReleaseParser.parse(file.nzbTitle)) } + formatSize(file.size)).joinToString(" · ")

        /** Over a file opened on Import's name, where its NZB holds more than one video; null where it is the one. */
        fun onlyThisFile(file: DownloadRequest.File): String? = if (file.videos > 1) onlyThisFile(ReleaseParser.parse(file.nzbTitle)) else null

        /** "14.0 GB free on this device". */
        fun free(free: Long): String = "${formatSize(free)} free on this device"

        /**
         * When it starts, and the line under that: "Starts now"; "Starts on Wi-Fi", and why; once online, on the charger, or
         * resumed, and why; after the queue, and why.
         */
        fun start(start: DownloadStart): Pair<String, String?> = when (start) {
            DownloadStart.Now -> "Starts now" to null
            DownloadStart.OnWifi -> "Starts on Wi-Fi" to "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: $SETTINGS."
            is DownloadStart.Online -> if (start.wifiOnly) {
                "Starts on Wi-Fi" to "This phone has no connection now. Downloads wait for Wi-Fi or Ethernet: $SETTINGS."
            } else {
                "Starts when this phone is online" to "This phone has no connection now."
            }
            DownloadStart.Charging -> "Starts when this phone is charging" to "Downloads wait until this phone is plugged in: $SETTINGS."
            DownloadStart.Held -> "Starts when downloads resume" to "Downloads are on hold until you resume them on the Downloads page."
            is DownloadStart.AfterQueue ->
                (if (start.ahead == 1) "Starts after the download in the queue" else "Starts after the downloads in the queue") to "One download runs at a time."
        }

        /** "Needs about 4.3 GB · 3.0 GB free on this device". */
        fun needs(need: Long, free: Long): String = "Needs about ${formatSize(need)} · ${free(free)}"

        /** "Needs 1.4 GB · 900 MB free on this device": a file opened on Import's exact size, with no "about" (L455). */
        fun needsExactly(need: Long, free: Long): String = "Needs ${formatSize(need)} · ${free(free)}"

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

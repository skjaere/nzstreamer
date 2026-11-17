package io.skjaere.usenet.app.download

import android.app.ActivityManager
import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Build
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.download.DownloadResolver.Resolution
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.Replay
import io.skjaere.usenet.core.BackoffWakes
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What a download is of, as the Download sheet names it: an episode of a TMDB show ([episode]) or a TMDB film ([movie]),
 * with the names its row keeps, so the Downloads page works offline (L368).
 */
data class DownloadTarget(
    val kind: DownloadKind,
    val title: String,
    val subtitle: String? = null,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val seasonEpisodes: Int? = null,
    val episodeName: String? = null,
    val movieTmdbId: Int? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val posterPath: String? = null,
    val stillPath: String? = null,
) {
    /** What it plays as: [WatchKey.episode] or [WatchKey.movie]. */
    val watchKey: String = when (kind) {
        DownloadKind.Episode -> WatchKey.episode(
            requireNotNull(showTmdbId) { "An episode's show" }, requireNotNull(season) { "An episode's season" },
            requireNotNull(episode) { "An episode's number" },
        )
        DownloadKind.Movie -> WatchKey.movie(requireNotNull(movieTmdbId) { "A film's id" })
    }

    companion object {
        /**
         * Episode [episode] of [season] of the show [title], whose season has [seasonEpisodes] episodes (offline Next, item
         * 15), and which runs [runtimeMinutes] (a show's downloads say it).
         */
        fun episode(
            showTmdbId: Int, title: String, season: Int, episode: Int, seasonEpisodes: Int? = null, episodeName: String? = null,
            subtitle: String? = null, posterPath: String? = null, stillPath: String? = null, runtimeMinutes: Int? = null,
        ) = DownloadTarget(
            DownloadKind.Episode, title, subtitle, showTmdbId = showTmdbId, season = season, episode = episode, seasonEpisodes = seasonEpisodes,
            episodeName = episodeName, runtimeMinutes = runtimeMinutes, posterPath = posterPath, stillPath = stillPath,
        )

        /** The film [title]. */
        fun movie(tmdbId: Int, title: String, year: Int? = null, runtimeMinutes: Int? = null, subtitle: String? = null, posterPath: String? = null) =
            DownloadTarget(
                DownloadKind.Movie, title, subtitle, movieTmdbId = tmdbId, year = year, runtimeMinutes = runtimeMinutes, posterPath = posterPath,
            )
    }
}

/**
 * How the app's process last ended, as Android keeps it (ApplicationExitInfo, API 30 on): its [reason], at the wall-clock
 * time [at], with Android's [description] of it, and when the app's package was last [updated] (installed over).
 */
data class DownloadExit(val reason: Int, val at: Long, val description: String? = null, val updated: Long? = null) {
    /**
     * Whether the user stopped the app (Task Manager's Stop, a force stop), which holds a download it left running (L359).
     * Before Android 14 an update's kill is recorded as the user's too (REASON_PACKAGE_UPDATED is 14's): an exit the
     * package's update came with, from just before it to [UPDATE_WINDOW_MILLIS] after (the install commits after its kill),
     * is the update's. A swipe from Recents is recorded as the user's, as "remove task", and stops no download either.
     */
    val byUser: Boolean
        get() = reason == USER_REQUESTED &&
            description?.contains(REMOVE_TASK, ignoreCase = true) != true &&
            updated?.let { it in at - UPDATE_SLACK_MILLIS..at + UPDATE_WINDOW_MILLIS } != true

    companion object {
        /** Task Manager's Stop or a force stop: ApplicationExitInfo.REASON_USER_REQUESTED, Android 11's. */
        const val USER_REQUESTED = 10

        /** How Android describes a process ended by a swipe from Recents (ApplicationExitInfo's subreason, hidden). */
        private const val REMOVE_TASK = "remove task"

        /** How long an update's install may take to commit after it killed the app. */
        const val UPDATE_WINDOW_MILLIS = 10 * 60_000L

        /** The clocks' slack the other way. */
        private const val UPDATE_SLACK_MILLIS = 5_000L
    }
}

/**
 * What [DownloadRunner] reads and writes, and its clocks: the app's ([of]), or a test's fakes. The writer is made here, over
 * the [opener], from the same [network] the runner follows, so its wait before a reopen ends on the network's return as
 * Resolve's does (L381), whoever builds the deps.
 */
class DownloadRunnerDeps(
    val downloads: DownloadDao,
    val storage: DownloadRunner.Storage,
    /** Resolve (item 5): [DownloadResolver.resolve]. */
    val resolve: suspend (id: String) -> Resolution,
    /** What the writer streams through: the engine's download opener ([NzbStreamOpener.download]). */
    opener: StreamOpener,
    /**
     * Remembers [release] as missing on the providers [asked], as a play's gap does (L342), its line naming it [named], never
     * by its name: the app's memory of releases tried ([remembered]).
     */
    val missing: suspend (release: RememberedRelease, asked: List<ProviderConfig>, named: String) -> Unit,
    /** The providers in use now: those a write fetches from. */
    val inUse: () -> List<ProviderConfig>,
    /** How the app's process last ended; null below API 30, or with none kept. */
    val lastExit: () -> DownloadExit?,
    /** Whether this is a TV, where nothing of downloads runs (W108, L369). */
    val television: Boolean,
    /** The host on this device (L334, L336). */
    val host: () -> DownloadHost,
    /**
     * Whether Android calls the device's network validated, the app's one flow of it ([AppContainer.validatedNetwork]):
     * the runner and its writer read it at each failure, and its return ends their waits (L381, L382).
     */
    val network: StateFlow<Boolean>,
    /** Turns the boot and update receiver on or off (L375): the app's switches the component itself ([DownloadBootReceiver.switch]). */
    val receiverSwitch: (on: Boolean) -> Unit = {},
    /** The debug build's seams: the writer's throttle, asked as each stream opens, and the trace (item 8). */
    val debug: DownloadDebug = DownloadDebug.Off,
    /** A stretch missing in a row that gives a release up (L342): a test's given small. */
    longGapBytes: Long = DownloadWriter.LONG_GAP_BYTES,
    /** Whether a provider is left out of the engine now, which may have what reads as missing (a long gap is asked for again). */
    providersLeftOut: () -> Boolean = { false },
    val clock: Clock = Clock.System,
    /** The clock of the hour a provider may be out of reach; the waits themselves are the coroutine's own. */
    val timeSource: TimeSource = TimeSource.Monotonic,
    /** Where a download runs: it blocks on its file. */
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long a removed download can be brought back (L379): the Undo bar's time. */
    val undoMillis: Long = DownloadRunner.UNDO_MILLIS,
    /** The walk's memory of the indexers' limits (L443), which Resolve's turns read and the runner's Resume forgets. */
    val limits: DownloadLimits = DownloadLimits(),
) {
    /** The writer (item 6), over [opener], following [network] as the runner does. */
    val writer = DownloadWriter(
        opener, network, throttle = { debug.throttle }, timeSource = timeSource, longGapBytes = longGapBytes, providersLeftOut = providersLeftOut,
    )

    companion object {
        /**
         * The app's: the library's downloads, their places on the device, Resolve and the writer over the engine's download
         * opener (the debug build's throttle asked as each stream opens, item 8; a long gap asked for again while a provider
         * is left out of the engine), both following the device's validated network, the container's one flow of it (L381);
         * the memory of releases found missing, the process's last exit, and the host by the API level and the debug
         * override, and the boot and update receiver switched as it says (L375); the walk's memory of the indexers' limits,
         * which Resolve's turns keep ([limits], L443). A test gives its own [resolve] and [debug], and the writer's
         * [longGapBytes] given small.
         */
        fun of(
            container: AppContainer,
            context: Context,
            storage: DownloadStorage = container.downloadStorage,
            debug: DownloadDebug = container.downloadDebug,
            limits: DownloadLimits = DownloadLimits(),
            resolve: suspend (String) -> Resolution = DownloadResolver.of(container, storage, limits = limits)::resolve,
            longGapBytes: Long = DownloadWriter.LONG_GAP_BYTES,
        ) = DownloadRunnerDeps(
            downloads = container.data.downloads,
            storage = DownloadRunner.Storage.of(storage),
            resolve = resolve,
            opener = NzbStreamOpener.download(container.engine),
            missing = remembered(container.missingReleases),
            inUse = { container.engine.inUse },
            lastExit = { lastExit(context) },
            television = container.television,
            host = { DownloadHostRules.hostFor(Build.VERSION.SDK_INT, debug.serviceHost) },
            network = container.validatedNetwork,
            receiverSwitch = { on -> DownloadBootReceiver.switch(context, on) },
            debug = debug,
            longGapBytes = longGapBytes,
            providersLeftOut = { container.engine.leftOut.value.isNotEmpty() },
            limits = limits,
        )

        /**
         * The app's memory of releases tried ([missingReleases]) remembering a release found missing on the providers fetched
         * from as it was written (L342), its line naming it as a download's lines do, with neither its title nor its indexer.
         */
        fun remembered(missingReleases: MissingReleases): suspend (RememberedRelease, List<ProviderConfig>, String) -> Unit = { release, asked, named ->
            missingReleases.record(release.indexerId, release.guid, release.title, indexerName = null, MissingOn.ProvidersFetchedFrom, asked, named)
        }

        /** The app's process's last end, as Android keeps it from Android 11 on, with the package's last update; null before. */
        private fun lastExit(context: Context): DownloadExit? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1)
            // The flags' overload is Android 13's; this one serves every level the app runs on.
            @Suppress("DEPRECATION")
            val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            return exits.firstOrNull()?.let { DownloadExit(it.reason, it.timestamp, it.description, updated) }
        }
    }
}

/**
 * This device's downloads, one at a time (L335, L337): one per process, beside the play's coordinator. The queue is
 * Room's, in position order; a host (the job, or the service on older Android) lends it a lifetime by calling [run], which
 * walks the queue while the host lives: each item resolved (its release found, item 5), then written (item 6), and on to
 * the next. [state] is every download with the live write's bytes and rate, for the Downloads page, Home's section and the
 * notification; [done] the finished ones by watch key, for the play hooks and the player (items 14, 15).
 *
 * - A release given up as it is written (a part missing, a long gap, damaged) is discarded, its file and NZB with it,
 *   and the next tried while tries are left (L340); one of a single video found missing is remembered as missing on its
 *   providers, as a play remembers it (L342).
 * - Pause holds the whole queue: the running item stops at its safe offset, keeping its file and room; nothing else
 *   starts until Resume (L380). "Stopped by Android" holds it the same way (L359).
 * - The waits for the providers and for storage end the walk, so the host ends (L388); the other rows wait their turn.
 *   An indexer's limit with nothing else to try holds that download alone (L399, L399b): it waits, and the walk goes on
 *   to the next, the limited indexer kept out of the later turns (L443), ending once nothing more can run. Resume queues
 *   it again at once, and the app's start once the limit has ended ([limitsEnded], L434).
 * - The NZB and its password go once the file is complete, the item fails or is cancelled (L346); a season pack's copy
 *   goes at the end of the last download of its season still to finish, whichever end it is, one removed counting as
 *   still to finish while its Undo can bring it back (L394, [Storage.sweepPacks]).
 * - Remove hides a download at once and deletes it once its Undo has gone (L379); Cancel deletes it at once.
 * - A season is queued at once, all or none, a failed episode's row put back rather than queued twice ([enqueueAll],
 *   L392, L396). Its rows' turns search for their releases and take a copy of their season's pack first (Resolve's);
 *   [packs] says which copies are kept, for the Downloads page's words (L406).
 *
 * Nothing runs on a TV (L369): every call but [reconcile] returns at once there. Its log lines name a download by its id,
 * with sizes and a cause's kind: never its title, release or a host.
 */
class DownloadRunner(private val deps: DownloadRunnerDeps, private val scope: CoroutineScope) {
    /** What the runner keeps in and asks of the downloads' places on the device: [DownloadStorage]'s ([of]). */
    interface Storage {
        /**
         * Makes [row]'s bookkeeping folder and its marker, with [grab] beside them where it has one (the NZB of a file chosen
         * by hand, L403), then the row (L346).
         */
        suspend fun add(row: Download, grab: DownloadStorage.Grab? = null)

        /**
         * Makes each of [rows]' folders and markers, then inserts them and writes [putBack] whole in one transaction: all of
         * them, or none (a season queued at once, L392).
         */
        suspend fun addAll(rows: List<Download>, putBack: List<Download>)

        /** Keeps [grab] for [id]'s attempt of its release, as Resolve keeps one it grabbed ([DownloadStorage.keepGrab]). */
        suspend fun keepGrab(id: String, grab: DownloadStorage.Grab)

        /** Opens [id]'s file to write, allocated whole; throws [DownloadStorage.Unavailable] while its volume isn't there. */
        suspend fun open(id: String, fileName: String, size: Long): FileChannel

        /** Renames [id]'s complete file to its finished name. */
        suspend fun finish(id: String, fileName: String)

        /** Where [id]'s finished file goes, as its row keeps it. */
        fun relativePath(id: String, fileName: String): String

        /** Deletes [id]'s file, giving its room back; throws [DownloadStorage.Unavailable] while its volume isn't there. */
        suspend fun deleteVideo(id: String)

        /** Deletes [id]'s NZB and password; its folder and marker stay. */
        suspend fun dropSource(id: String)

        /** Deletes [id], its row, file and bookkeeping, in the crash-safe order. */
        suspend fun delete(id: String)

        /** Brings the rows and folders into step (item 4). */
        suspend fun reconcile()

        /** Whether the primary volume's folder is there. */
        fun volumeThere(): Boolean

        /**
         * Drops the season packs' copies no download still to finish holds now (L394), nor one of [removed], the ids of
         * those removed whose Undo can still bring them back.
         */
        suspend fun sweepPacks(removed: Set<String>)

        /** The season packs' copies kept now ([DownloadNzbs.copies]). */
        val copies: StateFlow<List<DownloadNzbs.Meta>>

        companion object {
            fun of(storage: DownloadStorage): Storage = object : Storage {
                override suspend fun add(row: Download, grab: DownloadStorage.Grab?) = storage.add(row, grab)
                override suspend fun addAll(rows: List<Download>, putBack: List<Download>) = storage.addAll(rows, putBack)

                override suspend fun keepGrab(id: String, grab: DownloadStorage.Grab) =
                    storage.keepGrab(id, grab.key, grab.source.nzb, grab.source.password)

                override suspend fun open(id: String, fileName: String, size: Long) = storage.open(id, fileName, size)

                override suspend fun finish(id: String, fileName: String) {
                    storage.finish(id, fileName)
                }

                override fun relativePath(id: String, fileName: String) = storage.relativePath(id, fileName)
                override suspend fun deleteVideo(id: String) = storage.deleteVideo(id)
                override suspend fun dropSource(id: String) = storage.dropSource(id)
                override suspend fun delete(id: String) = storage.delete(id)
                override suspend fun reconcile() = storage.reconcile()
                override fun volumeThere() = storage.volumeThere()
                override suspend fun sweepPacks(removed: Set<String>) = storage.sweepPacks(removed)
                override val copies = storage.packs.copies
            }
        }
    }

    /**
     * A download as the page, Home's section and the notification show it: its [row]; [written] bytes of it, the live
     * write's as they come, else its safe offset; and the live write's bytes a second over 5 s ([bytesPerSecond], null for
     * any other).
     */
    data class Item(val row: Download, val written: Long, val bytesPerSecond: Long?)

    /**
     * One episode of a season queued at once ([enqueueAll]): what it is of ([target]); the releases the season's sheet
     * ranked for it, in download order ([candidates]: none where nothing was found yet, which only a row with [search]
     * can be); what the first needs until Resolve has its file's own size ([need], as [enqueue] counts it); and its search
     * facts ([search], L392), with which its turn searches.
     */
    data class QueueItem(
        val target: DownloadTarget,
        val candidates: List<DownloadCandidate>,
        val need: Long = candidates.firstOrNull()?.size ?: 0,
        val search: SeasonSearch? = null,
    )

    /**
     * The season packs' copies kept now, as the Downloads page says them (L406): the keys of those that lead ([leading], by
     * show and season), which a queued season row of theirs takes first (L431), and every copy's release key ([keys]), by
     * which a row written from one is told.
     */
    data class Packs(val leading: Map<Pair<Int, Int>, Set<String>> = emptyMap(), val keys: Set<String> = emptySet()) {
        companion object {
            fun of(copies: List<DownloadNzbs.Meta>) = Packs(
                copies.filter { it.leads }.groupBy({ it.show to it.season }, { it.key }).mapValues { (_, keys) -> keys.toSet() },
                copies.mapTo(HashSet()) { it.key },
            )
        }
    }

    private val dao = deps.downloads
    private val writer = deps.writer
    private val runs = DownloadHostRules.runs(deps.television)

    /** The queue's changes, one at a time: each call's, and the walk's pick of the next item. */
    private val changes = Mutex()

    /** One walk at a time: a host that starts while another's walk ends waits for it. */
    private val walks = Mutex()

    private val _walking = MutableStateFlow(false)

    /**
     * Whether a host's walk is under way and will take up what is queued now: what the app's schedule asks instead of the
     * job's own state (L335, [DownloadHostRules.schedules]).
     */
    val walking: StateFlow<Boolean> = _walking.asStateFlow()

    /** Reconcile once a process, before anything reads the queue to run it ([reconcile]). */
    private val reconciling = Mutex()

    @Volatile
    private var reconciled = false

    /** The item the walk runs now: its row's id and its job, which Pause, Cancel, Remove and Replace stop. */
    private class Current(val id: String, val job: Job)

    @Volatile
    private var current: Current? = null

    /** What a removed download was, to put it back on Undo; [token] is its removal's, whose timer deletes it. */
    private class Removed(val state: DownloadState, val reason: String?, val token: Any)

    // Changed under [changes] and [removals] both, beside the row's own state, and read under either.
    private val removed = HashMap<String, Removed>()

    /**
     * Taken with each change of [removed], and by each sweep of the season packs' copies, which counts a row removed with
     * its Undo still to come as one still to finish (L394): so a sweep from the walk, which can't take the queue's lock,
     * reads the rows and [removed] as one, never a row Deleting that its Undo has brought back, nor one removed but not yet
     * in [removed].
     */
    private val removals = Mutex()

    /** What ends a wait before Resolve is asked again sooner: [retryNow], [resume], and the network's return (L381). */
    private val wakes = BackoffWakes(deps.network)

    /** Every download but those removed, by position, with the live write's bytes and rate (L380's page and notification). */
    val state: StateFlow<List<Item>> = if (!runs) {
        MutableStateFlow(emptyList())
    } else {
        combine(dao.all(), writer.live) { rows, live -> rows.filter { it.state != DownloadState.Deleting }.map { item(it, live) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /**
     * Show [showTmdbId]'s downloads but those removed, by season and episode, with the live write's bytes: its page's rows'
     * marks (L384). None on a TV.
     */
    fun forShow(showTmdbId: Int): Flow<List<Item>> =
        if (!runs) flowOf(emptyList()) else combine(dao.forShow(showTmdbId), writer.live) { rows, live -> rows.map { item(it, live) } }

    /**
     * Film [movieTmdbId]'s download but one removed, the latest added, with the live write's bytes: its page's and Search's
     * card's Download button (L356). None on a TV.
     */
    fun forMovie(movieTmdbId: Int): Flow<Item?> =
        if (!runs) flowOf(null) else combine(dao.forMovie(movieTmdbId), writer.live) { row, live -> row?.let { item(it, live) } }

    /** [row] with what the writer says of it while it is the one written ([live]), else its safe offset. */
    private fun item(row: Download, live: DownloadWriter.Live?): Item =
        if (live != null && live.id == row.id) Item(row, live.written, writer.bytesPerSecond()) else Item(row, row.safeOffset, null)

    /** The season packs' copies kept now (L406), as the copies' last write or sweep left them: none on a TV. */
    val packs: StateFlow<Packs> = if (!runs) {
        MutableStateFlow(Packs())
    } else {
        deps.storage.copies.map(Packs::of).stateIn(scope, SharingStarted.Eagerly, Packs())
    }

    /** The finished downloads by watch key, the latest finished for each: what a play hook and the player ask (items 14, 15). */
    val done: StateFlow<Map<String, Download>> = if (!runs) {
        MutableStateFlow(emptyMap())
    } else {
        dao.done().map { rows -> rows.associateBy { it.watchKey } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    init {
        // The boot and update receiver follows the rows (L375): on while the host is the service and something can run.
        if (runs) {
            scope.launch {
                dao.queue().map { DownloadHostRules.receiverOn(deps.television, deps.host(), it) }.distinctUntilChanged().collect { on ->
                    try {
                        deps.receiverSwitch(on)
                    } catch (e: Exception) {
                        log.warn("Couldn't turn the downloads' boot receiver {}: {}", if (on) "on" else "off", e::class.simpleName)
                    }
                }
            }
        }
    }

    /**
     * Queues a download of [target] from [candidates], the releases the sheet ranked, in download order (up to 10 kept,
     * L338): the row Queued at the queue's end, made with its bookkeeping folder and marker (L346). Until Resolve has its
     * file's own size the row counts [need], what the sheet reckoned the first needs (a season pack's share of its
     * episode), so the space a queued download holds back is that, while its releases keep the indexer's sizes, which
     * Resolve ranks by. With [onePerTitle], as a user's Download has it, a title whose download is still to finish has
     * that one: it is returned, and nothing more is queued (a sheet restored with its page after a Download, a second tap
     * meanwhile). One removed with its Undo still pending isn't still to finish: a Download meanwhile queues another, and
     * an Undo then brings the removed one back beside it. Without, it is queued beside that one, as the pairs' seeding
     * queues two rows of its one fixture episode (L367). Scheduling its host is the visible app's (L335). Null on a TV.
     *
     * With [search], its search facts (L392), it may come with no release to try yet: its turn searches. A file chosen by
     * hand in a pack (Choose file, L403) comes as [pick], with the pack's NZB and password as [grab], kept beside the marker
     * before the row is there, so its attempt grabs nothing and no walk takes it up without them.
     */
    suspend fun enqueue(
        target: DownloadTarget,
        candidates: List<DownloadCandidate>,
        need: Long = candidates.firstOrNull()?.size ?: 0,
        onePerTitle: Boolean = true,
        search: SeasonSearch? = null,
        pick: DownloadPick? = null,
        grab: DownloadStorage.Grab? = null,
    ): Download? {
        if (!runs) return null
        requireReleases(candidates, search)
        require(grab == null || candidates.any { key(it) == grab.key }) { "An NZB kept for a release the download doesn't list" }
        return changes.withLock {
            val rows = dao.all().first()
            if (onePerTitle) {
                rows.firstOrNull { it.watchKey == target.watchKey && it.state in GOING_ON }?.let { queued ->
                    log.info("download {}: already queued", queued.id)
                    return@withLock queued
                }
            }
            val position = (rows.maxOfOrNull { it.position } ?: -1) + 1
            val row = newRow(target, candidates, need, search, position, now()).copy(pick = pick?.let(DownloadColumns::text))
            deps.storage.add(row, grab)
            log.info("download {}: queued at {}, {} release{} to try", row.id, position, row.candidates.size, if (row.candidates.size == 1) "" else "s")
            row
        }
    }

    /**
     * Queues a season's episodes at once (W114, L392): one row each, at consecutive places at the queue's end in episode
     * order, under one lock and one read of the rows, each with its releases, what the first needs and its search facts,
     * not yet searched. An episode with a download still to finish, or downloaded, keeps that one and is passed over. One
     * whose download failed has that row put back (L396), never a second: queued anew, now, in the season's order from the
     * new releases, its tries, reason, pick and limit's wait gone, its search facts the new ones, and what it is of as the
     * sheet has it now (TMDB's names, pictures, runtime and season's count, which may have come since it was first queued).
     * Its file and release stay, as Try again keeps them: Resolve goes on from them for the same release, and starts again
     * for another. Its NZB goes first, as Try again drops it.
     *
     * All or none: every new row's folder and marker first, then every row written in one transaction ([Storage.addAll]);
     * a failure leaves none of it, and a process that dies between the two leaves folders for reconcile to delete. Returns
     * the rows queued and put back, in their order; none on a TV. Scheduling the host is the visible app's (L335).
     */
    suspend fun enqueueAll(items: List<QueueItem>): List<Download> {
        if (!runs) return emptyList()
        items.forEach { requireReleases(it.candidates, it.search) }
        return changes.withLock {
            val rows = dao.all().first()
            val byTitle = rows.groupBy { it.watchKey }
            var position = (rows.maxOfOrNull { it.position } ?: -1) + 1
            val at = now()
            val added = mutableListOf<Download>()
            val putBack = mutableListOf<Download>()
            val queued = items.sortedWith(compareBy({ it.target.season }, { it.target.episode })).distinctBy { it.target.watchKey }.mapNotNull { item ->
                val own = byTitle[item.target.watchKey].orEmpty()
                own.firstOrNull { it.state in GOING_ON || it.state == DownloadState.Done }?.let { kept ->
                    log.info("download {}: already {}", kept.id, if (kept.state == DownloadState.Done) "downloaded" else "queued")
                    return@mapNotNull null
                }
                val failed = own.filter { it.state == DownloadState.Failed }.maxByOrNull { it.createdAt }
                val target = item.target
                val row = if (failed == null) {
                    newRow(target, item.candidates, item.need, item.search, position, at).also { added += it }
                } else {
                    failed.copy(
                        title = target.title, subtitle = target.subtitle, seasonEpisodes = target.seasonEpisodes, episodeName = target.episodeName,
                        year = target.year, runtimeMinutes = target.runtimeMinutes, posterPath = target.posterPath, stillPath = target.stillPath,
                        state = DownloadState.Queued, reason = null, position = position, candidates = item.candidates.take(MAX_CANDIDATES),
                        tried = emptyList(), indexerSize = item.need, search = item.search?.let(::searchText), pick = null, limitWait = null,
                        createdAt = at, updatedAt = at,
                    ).also { putBack += it }
                }
                position++
                row
            }
            if (queued.isEmpty()) return@withLock queued
            // A failed row's NZB went as it failed, but where its process died in between: dropped first, as Try again drops
            // it, so its turn never opens again from it the release it failed with. A failed row needs none, whatever comes.
            putBack.forEach { deps.storage.dropSource(it.id) }
            deps.storage.addAll(added, putBack)
            val back = putBack.mapTo(HashSet()) { it.id }
            queued.forEach { row ->
                val count = row.candidates.size
                val how = if (row.id in back) "put back" else "queued"
                log.info("download {}: {} at {}, {} release{} to try", row.id, how, row.position, count, if (count == 1) "" else "s")
            }
            queued
        }
    }

    /** A new row of [target] at [position], as [enqueue] and [enqueueAll] make one. */
    private fun newRow(target: DownloadTarget, candidates: List<DownloadCandidate>, need: Long, search: SeasonSearch?, position: Long, at: Long) = Download(
        id = Download.newId(), watchKey = target.watchKey, kind = target.kind, title = target.title, subtitle = target.subtitle,
        showTmdbId = target.showTmdbId, season = target.season, episode = target.episode, seasonEpisodes = target.seasonEpisodes,
        episodeName = target.episodeName, movieTmdbId = target.movieTmdbId, year = target.year, runtimeMinutes = target.runtimeMinutes,
        posterPath = target.posterPath, stillPath = target.stillPath, position = position, candidates = candidates.take(MAX_CANDIDATES),
        indexerSize = need, search = search?.let(::searchText), createdAt = at, updatedAt = at,
    )

    /**
     * Holds the queue (L380): the running item stops at its safe offset, keeping its file and the room it holds, and is
     * Paused; with none running, the next to run is. Nothing else starts until [resume], so the walk ends, and its host.
     */
    suspend fun pause() {
        if (!runs) return
        changes.withLock {
            current?.job?.cancelAndJoin()
            val rows = dao.queue().first()
            val held = rows.firstOrNull { it.state == DownloadState.Running || it.state == DownloadState.Resolving }
                ?: DownloadHostRules.runnable(rows, deps.storage.volumeThere()).firstOrNull()
                ?: return@withLock
            dao.setState(held.id, DownloadState.Paused, null, now())
            log.info("download {}: paused at {} bytes", held.id, held.safeOffset)
        }
    }

    /**
     * Lets the queue go on (L380): a Paused or Stopped download back at its head, and those waiting for the providers, for
     * storage or for an indexer's limit queued again; then the visible app schedules the host. A wait before a reopen ends
     * now (L381), and the walk's memory of the indexers' limits is forgotten, so a turn asks them again at once (L434).
     */
    suspend fun resume() {
        if (!runs) return
        deps.limits.forget()
        changes.withLock {
            val rows = dao.queue().first()
            val held = rows.filter { it.state in HOLDS }
            val head = rows.minOfOrNull { it.position } ?: 0
            held.forEachIndexed { i, row ->
                dao.move(row.id, head - held.size + i)
                dao.setState(row.id, DownloadState.Queued, null, now())
            }
            val waits = rows.filter { it.state == DownloadState.Waiting && it.reason in ENDING_WAITS }
            waits.forEach { dao.setState(it.id, DownloadState.Queued, null, now()) }
            if (held.size + waits.size > 0) log.info("Downloads: resumed, {} held and {} waiting queued again", held.size, waits.size)
        }
        wake()
    }

    /**
     * At the app's start, before its schedule (L434): each download waiting on an indexer's limit is queued again once that
     * limit has ended, where the indexer said, else 30 minutes after it began ([LimitWait.end]); one whose limit's facts
     * can't be read, at once. Before then it waits on, so a switch away from the app and back doesn't ask the indexer again.
     */
    suspend fun limitsEnded() {
        if (!runs) return
        changes.withLock {
            val at = now()
            val ended = dao.queue().first().filter { row ->
                row.state == DownloadState.Waiting && row.reason == DownloadHostRules.LIMIT_REACHED && (DownloadColumns.limitWait(row)?.end() ?: at) <= at
            }
            ended.forEach { dao.setState(it.id, DownloadState.Queued, null, at) }
            if (ended.isNotEmpty()) log.info("Downloads: {} waiting on an indexer's limit queued again, the limit ended", ended.size)
        }
    }

    /**
     * Ends the waits between reopens and Resolve's tries, their backoff starting again from 2 s (L381), and queues again
     * the downloads waiting for the providers: Resume, a provider saved (L388).
     */
    suspend fun retryNow() {
        if (!runs) return
        wake()
        changes.withLock {
            dao.queue().first().filter { it.state == DownloadState.Waiting && it.reason in PROVIDER_WAITS }.forEach {
                dao.setState(it.id, DownloadState.Queued, null, now())
            }
        }
    }

    /**
     * Cancels download [id]: stopped where it runs, its NZB and password gone, then the whole of it, file, bookkeeping and
     * row, in the crash-safe order. The page has no cancelled state, and the notification's Cancel no Undo.
     */
    suspend fun cancel(id: String) {
        if (!runs) return
        changes.withLock {
            stop(id)
            removals.withLock { removed.remove(id) }
            val row = dao.get(id) ?: return@withLock
            deps.storage.dropSource(id)
            delete(id)
            sweepPacks()
            passHold(row)
            log.info("download {}: cancelled", id)
        }
    }

    /**
     * Removes the downloads [ids] (L379): each hidden at once (Deleting), one running stopped first, and deleted, files and
     * all, once the Undo bar's time has gone ([keepFor]: the bar's own, longer with TalkBack), unless [undoRemove] brought it
     * back. A process that dies meanwhile leaves them Deleting, for the next start's reconcile to finish.
     */
    suspend fun remove(ids: Collection<String>, keepFor: Long = deps.undoMillis) {
        if (!runs) return
        val token = Any()
        val marked = changes.withLock {
            ids.mapNotNull { id ->
                stop(id)
                val row = dao.get(id)?.takeIf { it.state != DownloadState.Deleting } ?: return@mapNotNull null
                removals.withLock {
                    removed[id] = Removed(row.state, row.reason, token)
                    dao.setState(id, DownloadState.Deleting, null, now())
                }
                passHold(row)
                id
            }
        }
        if (marked.isEmpty()) return
        log.info("Downloads: {} removed, deleted in {} s unless brought back", marked.size, keepFor / 1000)
        scope.launch {
            delay(keepFor)
            changes.withLock {
                marked.filter { removed[it]?.token === token }.forEach { id ->
                    removals.withLock { removed.remove(id) }
                    if (dao.get(id)?.state == DownloadState.Deleting) delete(id)
                }
                sweepPacks()
            }
        }
    }

    /**
     * Deletes every download (Settings › Downloads' Delete all, which asks first; L379): the running one stopped, then each,
     * its NZB first, then its file, bookkeeping and row in the crash-safe order, those removed and waiting for their Undo's
     * end with them, so their timers find nothing and an Undo brings nothing back. Then the files are brought into step, a
     * folder no row has deleted too (item 4), and the season packs' copies with them (L394). Nothing is left to run: the
     * walk ends, and its host.
     */
    suspend fun deleteAll() {
        if (!runs) return
        val count = changes.withLock {
            current?.job?.cancelAndJoin()
            removals.withLock { removed.clear() }
            val rows = dao.all().first()
            rows.forEach { row ->
                // One that can't be dropped stops none of the others: reconcile finishes what is left.
                try {
                    deps.storage.dropSource(row.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("download {}: couldn't drop its NZB: {}", row.id, e::class.simpleName)
                }
                delete(row.id)
            }
            rows.size
        }
        try {
            deps.storage.reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't bring the downloads' files into step after Delete all: {}", e::class.simpleName)
        }
        log.info("Downloads: all {} deleted", count)
    }

    /** Brings back the downloads [ids] [remove] hid, while their Undo lasts: as they were, one stopped as it ran queued again. */
    suspend fun undoRemove(ids: Collection<String>) {
        if (!runs) return
        changes.withLock {
            ids.forEach { id ->
                removals.withLock {
                    val was = removed.remove(id) ?: return@withLock
                    if (dao.get(id)?.state != DownloadState.Deleting) return@withLock
                    // One stopped as it ran goes back to the queue, to go on from its safe offset when its turn comes.
                    val running = was.state == DownloadState.Running || was.state == DownloadState.Resolving
                    dao.setState(id, if (running) DownloadState.Queued else was.state, if (running) null else was.reason, now())
                }
            }
        }
    }

    /** Moves the queued download [id] one place [up] the queue, or down: it swaps places with its queued neighbour. */
    suspend fun move(id: String, up: Boolean) {
        if (!runs) return
        changes.withLock {
            val queued = dao.queue().first().filter { it.state == DownloadState.Queued }
            val at = queued.indexOfFirst { it.id == id }
            if (at < 0) return@withLock
            val other = queued.getOrNull(if (up) at - 1 else at + 1) ?: return@withLock
            val mine = queued[at]
            if (mine.position == other.position) {
                dao.move(id, other.position + if (up) -1 else 1)
            } else {
                dao.move(id, other.position)
                dao.move(other.id, mine.position)
            }
        }
    }

    /**
     * Replaces download [id]'s releases with [candidates] (Choose another release, Choose release): stopped where it runs,
     * what it wrote deleted and its room given back, its NZB gone, its tries, gaps and safe offset reset, and queued in its
     * place, to resolve from the new ones; one that holds the queue (Paused, Stopped) holds it still, until Resume. Until
     * resolved it counts [need], as [enqueue] has it.
     *
     * A release chosen by hand is tried as chosen: its search facts go, so its turn neither searches nor takes its season's
     * pack first (L392), and so does a limit's wait. A file chosen in a pack (Choose file, L435) comes as [pick], with the
     * pack's NZB as [grab], kept before the row names it; any other release clears the pick before (L403).
     */
    suspend fun replace(
        id: String,
        candidates: List<DownloadCandidate>,
        need: Long = candidates.first().size,
        pick: DownloadPick? = null,
        grab: DownloadStorage.Grab? = null,
    ) {
        if (!runs) return
        require(candidates.isNotEmpty()) { "A download needs a release to try" }
        require(grab == null || candidates.any { key(it) == grab.key }) { "An NZB kept for a release the download doesn't list" }
        changes.withLock {
            stop(id)
            val row = dao.get(id)?.takeIf { it.state != DownloadState.Deleting && it.state != DownloadState.Done } ?: return@withLock
            // Its NZB and the row's file, offset and release gone before the file is, and all of it whatever stops the call
            // meanwhile: a row left naming a deleted file at its offset would open its release again into a file of zeros.
            withContext(NonCancellable) {
                deps.storage.dropSource(id)
                // The chosen file's NZB before the row names its pick: no walk takes it up without it.
                grab?.let { deps.storage.keepGrab(id, it) }
                // One that holds the queue still holds it (L380); any other is queued in its place.
                val held = row.state in HOLDS
                dao.update(
                    row.copy(
                        state = if (held) row.state else DownloadState.Queued, reason = if (held) row.reason else null,
                        candidates = candidates.take(MAX_CANDIDATES), tried = emptyList(), oneVideo = false,
                        release = null, fileName = null, size = null, indexerSize = need, safeOffset = 0, gaps = emptyList(),
                        relativePath = null, video = null, tracks = null, completedAt = null, search = null, pick = pick?.let(DownloadColumns::text),
                        limitWait = null, updatedAt = now(),
                    ),
                )
                try {
                    deps.storage.deleteVideo(id)
                } catch (e: DownloadStorage.Unavailable) {
                    // Its file can't go while the volume is away: the new release's is written over it from its first byte.
                    log.info("download {}: its file stays until its volume is back", id)
                }
            }
            log.info("download {}: {} other release{} to try", id, candidates.size, if (candidates.size == 1) "" else "s")
        }
    }

    /**
     * Tries the failed download [id] again (Try again, Download again): its tries reset and queued, to resolve again from
     * its releases. The one its file came from, picked again at its size, goes on from the safe offset (as after "Storage
     * is full"); another starts from its first byte (Resolve's rule). A season row (one with search facts, L392) has its
     * releases cleared instead, so its turn searches its episode afresh (L396, L398); one with a file chosen by hand keeps
     * its releases, as its turn never searches.
     */
    suspend fun retry(id: String) {
        if (!runs) return
        changes.withLock {
            val row = dao.get(id)?.takeIf { it.state == DownloadState.Failed } ?: return@withLock
            val search = DownloadColumns.search(row)?.takeIf { DownloadColumns.pick(row) == null }
            deps.storage.dropSource(id)
            dao.update(
                row.copy(
                    state = DownloadState.Queued, reason = null, tried = emptyList(), candidates = if (search == null) row.candidates else emptyList(),
                    search = search?.let(::searchText) ?: row.search, limitWait = null, updatedAt = now(),
                ),
            )
            log.info("download {}: tried again{}", id, if (search == null) "" else ", searched afresh")
        }
    }

    /**
     * The downloads that can run now, in their order (L388): what a host waits for, and what the app's start schedules for.
     * As [reconcile] leaves them, whichever asks first.
     */
    suspend fun runnable(): List<Download> {
        if (!runs) return emptyList()
        reconcile()
        return DownloadHostRules.runnable(dao.queue().first(), deps.storage.volumeThere())
    }

    /**
     * Every download but those removed, read from the table now, at their safe offsets: what a host's end says ([state]
     * has it once Room says so, which can come after). Empty on a TV.
     */
    suspend fun items(): List<Item> {
        if (!runs) return emptyList()
        return dao.all().first().filter { it.state != DownloadState.Deleting }.map { Item(it, it.safeOffset, null) }
    }

    /**
     * Android stopped the host (the job's onStopJob, item 9), whose walk was cancelled and recorded where it stopped: the
     * download it left under way (Running or Resolving) is said to be in [state] for [reason], Waiting for the need that
     * went (the host runs again once it is back) or Stopped by Android, held until Resume (L359). Under the queue's lock: one
     * a walk has taken up again since is left as it runs.
     */
    suspend fun hostStopped(state: DownloadState, reason: String) {
        if (!runs) return
        require(state == DownloadState.Waiting || state == DownloadState.Stopped) { "A stopped host leaves a download waiting or stopped" }
        changes.withLock {
            // A cancelled walk's item stays current until a walk picks the next: only one still running is live.
            val live = current?.takeIf { it.job.isActive }?.id
            dao.queue().first().filter { (it.state == DownloadState.Running || it.state == DownloadState.Resolving) && it.id != live }.forEach { row ->
                dao.setState(row.id, state, reason, now())
                log.info("download {}: its host was stopped at {} bytes: {}", row.id, row.safeOffset, state)
            }
        }
    }

    /**
     * The host waits for its need: the service in place (item 10), its walk stopped where it was, or the job under its
     * constraints, scheduled and not started (L335). The download it would run next says why ([reason]: "Waiting for
     * Wi-Fi", "Waiting to charge"), the one a stopped walk left under way first, else the first that can run, so the page,
     * the title's pages and the notification say what holds it. A need's wait still runs (L388), so the next walk takes it
     * up first. Under the queue's lock: nothing while a walk runs one, nor in a held queue.
     */
    suspend fun hostWaits(reason: String) {
        if (!runs) return
        changes.withLock {
            if (current?.job?.isActive == true) return@withLock
            val row = next()?.takeUnless { it.state == DownloadState.Waiting && it.reason == reason } ?: return@withLock
            dao.setState(row.id, DownloadState.Waiting, reason, now())
            log.info("download {}: its host waits at {} bytes: {}", row.id, row.safeOffset, reason)
        }
    }

    /**
     * Walks the queue under [host] while it lives: the next runnable download, one left running by a kill first, resolved
     * then written, then the next. Returns once nothing can run (L388): all done, failed or waiting on an indexer's limit
     * (L399b), the queue held, or a wait for the providers or for storage. The host ends then; cancelled by its host, the
     * item under way stops at its safe offset, its row as a kill leaves it, for the next walk. [walking] says it is under
     * way until it has found nothing more.
     * [taken] is called once, under the queue's lock, as the walk first looks at the queue: [walking] says from then on
     * whether it takes up what is queued, so the host stops counting itself ahead of its walk (the job, L335).
     */
    suspend fun run(host: DownloadHost, taken: () -> Unit = {}) {
        if (!runs) return
        // A host in a new process can start before the app's start has reconciled: its walk takes the rows as reconcile
        // leaves them, never one the user stopped from Android.
        reconcile()
        walks.withLock {
            log.info("Downloads: the {} runs the queue", host.name.lowercase())
            var first = true
            try {
                coroutineScope {
                    while (true) {
                        var walk = Walk.Next
                        val job = changes.withLock {
                            val row = next()
                            // Said under the queue's lock: a change made after this finds the walk taking it up, or ended.
                            _walking.value = row != null
                            if (first) {
                                first = false
                                taken()
                            }
                            if (row == null) return@withLock null
                            launch(deps.io) { walk = item(row.id) }.also { current = Current(row.id, it) }
                        } ?: break
                        job.join()
                        changes.withLock {
                            if (current?.job === job) current = null
                            if (walk == Walk.End) _walking.value = false
                        }
                        if (walk == Walk.End) break
                    }
                }
            } finally {
                _walking.value = false
            }
            log.info("Downloads: nothing more runs under the {}", host.name.lowercase())
        }
    }

    /**
     * At the app's start, before any host (L359): storage's reconcile first (item 4); then each download a process left
     * Running or Resolving with no host on it now is Stopped, "Stopped by Android", held until Resume, when the process's
     * last end was the user's (Task Manager's Stop or a force stop, [DownloadExit.byUser]) after the row's last update; any
     * other end (a kill, a reboot, an update, a swipe from Recents), or one before that update, leaves it for its host to go
     * on with. Runs on a TV too.
     *
     * Once a process, whoever asks first: the app's start launches it, and a host's walk ([run]) and the start's schedule
     * ([runnable]) wait for it, so a host in a new process never takes up a row before reconcile has judged it.
     */
    suspend fun reconcile() {
        if (reconciled) return
        reconciling.withLock {
            if (reconciled) return@withLock
            reconcileNow()
            reconciled = true
        }
    }

    private suspend fun reconcileNow() {
        try {
            deps.storage.reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't bring the downloads' files into step: {}", e::class.simpleName)
        }
        changes.withLock {
            val live = current?.id
            val left = dao.queue().first().filter { (it.state == DownloadState.Running || it.state == DownloadState.Resolving) && it.id != live }
            if (left.isEmpty()) return@withLock
            val exit = try {
                deps.lastExit()
            } catch (e: Exception) {
                log.warn("Couldn't read how the app last ended: {}", e::class.simpleName)
                null
            }
            left.forEach { row ->
                val stopped = exit != null && exit.byUser && exit.at > row.updatedAt
                if (stopped) dao.setState(row.id, DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID, now())
                val decision = if (stopped) DownloadTrace.Decision.Stopped else DownloadTrace.Decision.LeftForHost
                deps.debug.trace(DownloadTrace.Reconciled(row.id, decision, exit?.reason, exit?.at))
                log.info("download {}: left {} as the app last ended (exit {}): {}", row.id, row.state, exit?.reason ?: "unknown", decision.word)
            }
        }
    }

    /**
     * [gone], cancelled or removed, held the queue (Paused or Stopped, L380): unless another row holds it still, the hold
     * goes to the next that would run, as a Pause with nothing running does, so nothing starts before Resume. Under [changes].
     */
    private suspend fun passHold(gone: Download) {
        if (gone.state !in HOLDS) return
        val rows = dao.queue().first()
        if (rows.any { it.state in HOLDS }) return
        val next = DownloadHostRules.runnable(rows, deps.storage.volumeThere()).firstOrNull() ?: return
        dao.setState(next.id, gone.state, gone.reason, now())
        log.info("download {}: holds the queue in {}'s place", next.id, gone.id)
    }

    /** The next download to run, under [changes]: one a kill left Running or Resolving first, else the first that can run. */
    private suspend fun next(): Download? {
        val runnable = DownloadHostRules.runnable(dao.queue().first(), deps.storage.volumeThere())
        return runnable.firstOrNull { it.state == DownloadState.Running || it.state == DownloadState.Resolving } ?: runnable.firstOrNull()
    }

    /** Stops download [id] where the walk runs it, waiting until it has stopped (its writer records as it does), under [changes]. */
    private suspend fun stop(id: String) {
        current?.takeIf { it.id == id }?.job?.cancelAndJoin()
    }

    /** What the walk does after an item. */
    private enum class Walk { Next, End }

    /**
     * Resolves and writes download [id], and the next release after one given up; what the walk does then. A failure it
     * didn't expect fails it, its file and record kept, rather than run into the same failure again: a device so full that
     * Room can't write (its file was allocated whole before, so its record is what fails first) as "Storage is full" (L382).
     * Where even that can't be kept, the row is as it was: the walk ends, rather than take it up again at once, and the
     * next host goes on with it.
     */
    private suspend fun item(id: String): Walk = try {
        releases(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("download {}: failed on {}", id, e::class.simpleName)
        val reason = when {
            generateSequence<Throwable>(e) { it.cause }.take(16).any { it is SQLiteFullException } -> DownloadWriter.STORAGE_FULL
            e is IOException -> DownloadWriter.WRITE_FAILED
            else -> FAILED_IN_APP
        }
        try {
            failed(id, reason)
            Walk.Next
        } catch (again: CancellationException) {
            throw again
        } catch (again: Exception) {
            log.warn("download {}: couldn't say it failed ({}): the walk ends", id, again::class.simpleName)
            Walk.End
        }
    }

    /** Download [id]'s releases, resolved and written in turn, the network followed meanwhile: its return ends a wait between Resolve's tries. */
    private suspend fun releases(id: String): Walk = wakes.watching { tryReleases(id) }

    private suspend fun tryReleases(id: String): Walk {
        // Why the last release was given up as it was written: the words of a failure once no other is left to try.
        var gaveUp: DownloadWriter.GiveUp? = null
        var wait = DownloadWriter.FIRST_BACKOFF
        // Since when Resolve has found the providers out of reach, try after try.
        var outOfReach: TimeMark? = null
        while (true) {
            // A retry asked for, or the network back, while Resolve asks ends the wait after it.
            val mark = wakes.mark()
            when (val resolution = deps.resolve(id)) {
                Resolution.Gone -> return Walk.Next
                is Resolution.Failed -> {
                    val why = gaveUp
                    // Failed by Resolve: its NZB goes too, whatever stops the walk meanwhile (L346).
                    withContext(NonCancellable) {
                        if (why != null && resolution.reason == DownloadResolver.NO_OTHER_RELEASE) {
                            dao.setState(id, DownloadState.Failed, gaveUpWords(why), now())
                        }
                        deps.storage.dropSource(id)
                        sweepPacks()
                    }
                    log.info("download {}: failed", id)
                    return Walk.Next
                }
                // An indexer's limit holds this download alone (L399b): the walk goes on to the next, which may need
                // nothing of that indexer (its season's copy, an NZB kept for it) or take another's releases, the limited
                // indexer kept out of its turn (L443). A wait for the providers or for storage holds every download alike.
                is Resolution.Waiting -> return if (resolution.reason == DownloadHostRules.LIMIT_REACHED) Walk.Next else Walk.End
                is Resolution.Unreachable -> {
                    val since = outOfReach ?: deps.timeSource.markNow().also { outOfReach = it }
                    if (since.elapsedNow() >= DownloadWriter.UNREACHABLE_FOR) {
                        log.info("download {}: out of reach for {} min: waiting", id, since.elapsedNow().inWholeMinutes)
                        waiting(id, resolution.words)
                        return Walk.End
                    }
                    val kind = resolution.error::class.simpleName
                    log.info("download {}: out of reach ({}); resolving it again in {} s", id, kind, wait.inWholeSeconds)
                    if (wakes.pause(wait, mark)) {
                        wait = DownloadWriter.FIRST_BACKOFF
                        outOfReach = null
                    } else {
                        wait = minOf(wait * 2, DownloadWriter.MAX_BACKOFF)
                    }
                }
                is Resolution.Ready -> {
                    wait = DownloadWriter.FIRST_BACKOFF
                    outOfReach = null
                    when (val written = write(id, resolution)) {
                        is Written.Ended -> return written.walk
                        is Written.GaveUp -> {
                            gaveUp = written.why
                            if (!discard(id)) return Walk.End
                            // A stop asked for as it was discarded stops the walk here, before the next release is resolved.
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            }
        }
    }

    /** How a write went for the walk. */
    private sealed interface Written {
        data class Ended(val walk: Walk) : Written

        data class GaveUp(val why: DownloadWriter.GiveUp) : Written
    }

    /** [ready]'s file written into download [id]'s, from its safe offset; the row says how it ended. */
    private suspend fun write(id: String, ready: Resolution.Ready): Written {
        val row = ready.row
        val size = ready.file.size
        val fileName = row.fileName ?: ready.file.name
        val channel = try {
            deps.storage.open(id, fileName, size)
        } catch (e: DownloadStorage.Unavailable) {
            waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE)
            return Written.Ended(Walk.End)
        } catch (e: IOException) {
            log.warn("download {}: its file of {} bytes couldn't be made: {}", id, size, e::class.simpleName)
            failed(id, DownloadWriter.WRITE_FAILED)
            return Written.Ended(Walk.Next)
        }
        val asked = deps.inUse()
        val outcome = channel.use { out ->
            val made = dao.get(id) ?: return Written.Ended(Walk.Next)
            // Its file made at its whole size: the room it holds is its own from here (DownloadSpace).
            val path = deps.storage.relativePath(id, fileName)
            dao.update(made.copy(state = DownloadState.Running, reason = null, relativePath = path, updatedAt = now()))
            val from = row.safeOffset.coerceIn(0, size)
            coroutineScope {
                val words = launch { sayOutOfReach(id) }
                try {
                    writer.write(
                        id, ready.nzb, ready.file, out, from, row.gaps.map { it.first..it.last },
                        onOpen = { offset -> deps.debug.trace(DownloadTrace.Reopen(id, offset)) },
                    ) { offset, gaps ->
                        dao.progress(id, offset, gaps.map { DownloadGap(it.first, it.last) }, now())
                        deps.debug.trace(DownloadTrace.Progress(id, offset, size))
                    }
                } finally {
                    words.cancel()
                }
            }
        }
        return when (outcome) {
            is DownloadWriter.Outcome.Done -> try {
                finished(id, fileName, size, outcome.gaps)
                Written.Ended(Walk.Next)
            } catch (e: DownloadStorage.Unavailable) {
                // Written whole, and its volume gone before it was renamed: the next walk renames it, its record at its end.
                waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE)
                Written.Ended(Walk.End)
            }
            is DownloadWriter.Outcome.NextRelease -> {
                remember(id, outcome.why, asked)
                Written.GaveUp(outcome.why)
            }
            is DownloadWriter.Outcome.Waiting -> {
                val provider = outcome.wait == DownloadWriter.Wait.Provider
                waiting(id, if (provider) DownloadHostRules.CHECK_PROVIDER else DownloadHostRules.CANT_REACH_PROVIDER)
                Written.Ended(Walk.End)
            }
            is DownloadWriter.Outcome.Failed -> {
                failed(id, outcome.reason)
                Written.Ended(Walk.Next)
            }
        }
    }

    /** While download [id] is written, its row says "Can't reach your Usenet provider" as long as its writer backs off for that (L382). */
    private suspend fun sayOutOfReach(id: String) {
        var said = false
        writer.live.collect { live ->
            val out = live != null && live.id == id && live.unreachable
            if (out != said) {
                said = out
                dao.setState(id, DownloadState.Running, if (out) DownloadHostRules.CANT_REACH_PROVIDER else null, now())
            }
        }
    }

    /**
     * Download [id]'s file complete: renamed, its row Done with the [gaps] still missing, its NZB and password gone (L346),
     * all of it once renamed whatever stops the walk meanwhile: nothing would drop them from a Done row later. Its search
     * facts go with them, taken from TMDB and kept no longer than the row needs them, and any limit's wait (L442).
     */
    private suspend fun finished(id: String, fileName: String, size: Long, gaps: List<LongRange>) {
        deps.storage.finish(id, fileName)
        withContext(NonCancellable) {
            val row = dao.get(id) ?: return@withContext
            val at = now()
            dao.update(
                row.copy(
                    state = DownloadState.Done, reason = null, safeOffset = size, gaps = gaps.map { DownloadGap(it.first, it.last) }, completedAt = at,
                    search = null, limitWait = null, updatedAt = at,
                ),
            )
            deps.storage.dropSource(id)
            sweepPacks()
            deps.debug.trace(DownloadTrace.Completed(id, size))
            log.info("download {}: done, {} bytes, {} part{} missing", id, size, gaps.size, if (gaps.size == 1) "" else "s")
        }
    }

    /**
     * Download [id]'s release given up [why] as it was written: remembered as missing on the providers [asked] when it is
     * its release's one video (L342; a pack's other videos may be whole), as a play remembers it. A damaged one isn't
     * missing anything.
     */
    private suspend fun remember(id: String, why: DownloadWriter.GiveUp, asked: List<ProviderConfig>) {
        log.info("download {}: its release given up ({})", id, why)
        if (why == DownloadWriter.GiveUp.Corrupt) return
        val row = dao.get(id) ?: return
        val release = row.release?.takeIf { row.oneVideo } ?: return
        try {
            deps.missing(release, asked, DownloadResolver.naming(row)(Replay.key(release)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't remember its release as missing: {}", id, e::class.simpleName)
        }
    }

    /**
     * Download [id]'s release given up: its NZB and password gone, so Resolve finds the next rather than opening this one
     * again from the copy, its row back to nothing written, then its file deleted and its room given back: in that order,
     * and all of it whatever stops the walk meanwhile, so the row never names a file that is gone. False when its volume
     * isn't there to delete it from: it waits for it, the next open writing over what is left.
     */
    private suspend fun discard(id: String): Boolean = withContext(NonCancellable) {
        deps.storage.dropSource(id)
        dao.get(id)?.let { row ->
            dao.update(
                row.copy(
                    release = null, fileName = null, size = null, oneVideo = false, video = null, tracks = null, safeOffset = 0, gaps = emptyList(),
                    relativePath = null, updatedAt = now(),
                ),
            )
        }
        try {
            deps.storage.deleteVideo(id)
            true
        } catch (e: DownloadStorage.Unavailable) {
            waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE)
            false
        }
    }

    /**
     * Download [id] failed, for [reason]: its NZB and password go (L346), its file and record stay, for Try again; both
     * whatever stops the walk meanwhile.
     */
    private suspend fun failed(id: String, reason: String) = withContext(NonCancellable) {
        dao.setState(id, DownloadState.Failed, reason, now())
        deps.storage.dropSource(id)
        sweepPacks()
        log.info("download {}: failed", id)
    }

    /** Download [id] waits, for [reason]: the walk ends, and its host (L388). */
    private suspend fun waiting(id: String, reason: String) {
        dao.setState(id, DownloadState.Waiting, reason, now())
        log.info("download {}: waiting: {}", id, reason)
    }

    /** Deletes download [id] in the crash-safe order; a failure is logged, and the next start's reconcile finishes it. */
    private suspend fun delete(id: String) {
        try {
            deps.storage.delete(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't delete it: {}", id, e::class.simpleName)
        }
    }

    /**
     * A download's end, whichever it is: the season packs' copies no download still to finish holds now go (L394), one
     * removed whose Undo can still bring it back counted as still to finish ([removals]). A failure is logged, and the next
     * start's reconcile sweeps them.
     */
    private suspend fun sweepPacks() {
        try {
            removals.withLock { deps.storage.sweepPacks(removed.keys.toSet()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't drop the season packs' copies: {}", e::class.simpleName)
        }
    }

    /** Ends the writer's wait between reopens and Resolve's between tries, or the one after a try under way (L381). */
    private fun wake() {
        writer.retryNow()
        wakes.retryNow()
    }

    private fun now() = deps.clock.now()

    companion object {
        /** How long a removed download can be brought back: the Undo bar's 10 s (L379). */
        const val UNDO_MILLIS = 10_000L

        /** The releases a download keeps to try, in download order (L338). */
        const val MAX_CANDIDATES = 10

        /** Something the app didn't expect ended it: no cause's words, which could name a host. */
        const val FAILED_IN_APP = "Something went wrong in the app"

        /** The states that hold the whole queue until Resume (L380). */
        private val HOLDS = setOf(DownloadState.Paused, DownloadState.Stopped)

        /**
         * A download still to finish: queued, under way, held or waiting; neither done, failed nor removed. A season pack's
         * copy lasts while one of its season's is ([DownloadNzbs]).
         */
        internal val GOING_ON = setOf(
            DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting, DownloadState.Stopped,
        )

        /** The waits for the providers and the indexers, which a provider saved or Resume queues again (L388). */
        private val PROVIDER_WAITS = setOf(DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER)

        /** The waits Resume queues again: those that end the host (L388), and an indexer's limit, its download's alone (L399b). */
        private val ENDING_WAITS = PROVIDER_WAITS + DownloadHostRules.STORAGE_UNAVAILABLE + DownloadHostRules.LIMIT_REACHED

        /** Every download has a release to try, but one whose turn searches for its releases ([search], L392). */
        private fun requireReleases(candidates: List<DownloadCandidate>, search: SeasonSearch?) =
            require(candidates.isNotEmpty() || search != null) { "A download needs a release to try, or the facts to search for one" }

        /** [candidate]'s release key, as Resolve and the NZB kept for an attempt know it. */
        private fun key(candidate: DownloadCandidate): String = ReleaseKey.of(candidate.indexerId, candidate.guid)

        /** [search] as a row keeps it: not yet searched, as a queued or put-back row's turn is still to come. */
        private fun searchText(search: SeasonSearch): String = DownloadColumns.text(search.copy(searched = false))

        /** A failed download's words, its last release given up [why] as it was written and none left to try. */
        fun gaveUpWords(why: DownloadWriter.GiveUp): String = when (why) {
            DownloadWriter.GiveUp.Incomplete, DownloadWriter.GiveUp.LongGap -> DownloadResolver.INCOMPLETE
            DownloadWriter.GiveUp.Corrupt -> DownloadResolver.DAMAGED
        }
    }
}

private val log = LoggerFactory.getLogger(DownloadRunner::class.java)

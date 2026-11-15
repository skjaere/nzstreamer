package io.skjaere.usenet.app.download

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.download.DownloadResolver.Resolution
import io.skjaere.usenet.core.BackoffWakes
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        /** Episode [episode] of [season] of the show [title], whose season has [seasonEpisodes] episodes (offline Next, item 15). */
        fun episode(
            showTmdbId: Int, title: String, season: Int, episode: Int, seasonEpisodes: Int? = null, episodeName: String? = null,
            subtitle: String? = null, posterPath: String? = null, stillPath: String? = null,
        ) = DownloadTarget(
            DownloadKind.Episode, title, subtitle, showTmdbId = showTmdbId, season = season, episode = episode, seasonEpisodes = seasonEpisodes,
            episodeName = episodeName, posterPath = posterPath, stillPath = stillPath,
        )

        /** The film [title]. */
        fun movie(tmdbId: Int, title: String, year: Int? = null, runtimeMinutes: Int? = null, subtitle: String? = null, posterPath: String? = null) =
            DownloadTarget(
                DownloadKind.Movie, title, subtitle, movieTmdbId = tmdbId, year = year, runtimeMinutes = runtimeMinutes, posterPath = posterPath,
            )
    }
}

/** How the app's process last ended, as Android keeps it (ApplicationExitInfo, API 30 on): its [reason], at the wall-clock time [at]. */
data class DownloadExit(val reason: Int, val at: Long) {
    companion object {
        /** Task Manager's Stop or a force stop: ApplicationExitInfo.REASON_USER_REQUESTED, Android 11's. */
        const val USER_REQUESTED = 10
    }
}

/** What [DownloadRunner] reads and writes, and its clocks: the app's ([of]), or a test's fakes. */
class DownloadRunnerDeps(
    val downloads: DownloadDao,
    val storage: DownloadRunner.Storage,
    /** Resolve (item 5): [DownloadResolver.resolve]. */
    val resolve: suspend (id: String) -> Resolution,
    /** The writer (item 6), over the engine's download opener. */
    val writer: DownloadWriter,
    /** Remembers [release] as missing on the providers [asked], as a play's gap does (L342). */
    val missing: suspend (release: RememberedRelease, asked: List<ProviderConfig>) -> Unit,
    /** The providers in use now: those a write fetches from. */
    val inUse: () -> List<ProviderConfig>,
    /** How the app's process last ended; null below API 30, or with none kept. */
    val lastExit: () -> DownloadExit?,
    /** Whether this is a TV, where nothing of downloads runs (W108, L369). */
    val television: Boolean,
    /** The host on this device (L334, L336). */
    val host: () -> DownloadHost,
    /** Turns the boot and update receiver on or off (L375): item 10 wires it to setComponentEnabledSetting. */
    val receiverSwitch: (on: Boolean) -> Unit = {},
    val debug: DownloadDebug = DownloadDebug.Off,
    /** Whether Android calls the network validated, as the writer reads it: its return ends a wait (L381). */
    val network: StateFlow<Boolean> = MutableStateFlow(true),
    val clock: Clock = Clock.System,
    /** The clock of the hour a provider may be out of reach; the waits themselves are the coroutine's own. */
    val timeSource: TimeSource = TimeSource.Monotonic,
    /** Where a download runs: it blocks on its file. */
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long a removed download can be brought back (L379): the Undo bar's time. */
    val undoMillis: Long = DownloadRunner.UNDO_MILLIS,
) {
    companion object {
        /**
         * The app's: the library's downloads, their places on the device, Resolve and the writer over the engine's download
         * opener (the debug build's throttle asked as each stream opens, item 8), the memory of releases found missing, the
         * process's last exit, and the host by the API level and the debug override. A test gives its own [resolve],
         * [writer] and [debug].
         */
        fun of(
            container: AppContainer,
            context: Context,
            storage: DownloadStorage = container.downloadStorage,
            debug: DownloadDebug = container.downloadDebug,
            resolve: suspend (String) -> Resolution = DownloadResolver.of(container, storage)::resolve,
            writer: DownloadWriter = DownloadWriter(NzbStreamOpener.download(container.engine), throttle = { debug.throttle }),
        ) = DownloadRunnerDeps(
            downloads = container.data.downloads,
            storage = DownloadRunner.Storage.of(storage),
            resolve = resolve,
            writer = writer,
            missing = { release, asked ->
                val indexer = container.settings.indexers.first().firstOrNull { it.id == release.indexerId }?.name
                container.missingReleases.record(release.indexerId, release.guid, release.title, indexer, MissingOn.ProvidersFetchedFrom, asked)
            },
            inUse = { container.engine.inUse },
            lastExit = { lastExit(context) },
            television = container.television,
            host = { DownloadHostRules.hostFor(Build.VERSION.SDK_INT, debug.serviceHost) },
            debug = debug,
        )

        /** The app's process's last end, as Android keeps it from Android 11 on; null before. */
        private fun lastExit(context: Context): DownloadExit? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1)
            return exits.firstOrNull()?.let { DownloadExit(it.reason, it.timestamp) }
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
 * - The NZB and its password go once the file is complete, the item fails or is cancelled (L346).
 * - Remove hides a download at once and deletes it once its Undo has gone (L379); Cancel deletes it at once.
 *
 * Nothing runs on a TV (L369): every call but [reconcile] returns at once there. Its log lines name a download by its id,
 * with sizes and a cause's kind: never its title, release or a host.
 */
class DownloadRunner(private val deps: DownloadRunnerDeps, private val scope: CoroutineScope) {
    /** What the runner keeps in and asks of the downloads' places on the device: [DownloadStorage]'s ([of]). */
    interface Storage {
        /** Makes [row]'s bookkeeping folder and its marker, then the row (L346). */
        suspend fun add(row: Download)

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

        companion object {
            fun of(storage: DownloadStorage): Storage = object : Storage {
                override suspend fun add(row: Download) = storage.add(row)
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
            }
        }
    }

    /**
     * A download as the page, Home's section and the notification show it: its [row]; [written] bytes of it, the live
     * write's as they come, else its safe offset; and the live write's bytes a second over 5 s ([bytesPerSecond], null for
     * any other).
     */
    data class Item(val row: Download, val written: Long, val bytesPerSecond: Long?)

    private val dao = deps.downloads
    private val writer = deps.writer
    private val runs = DownloadHostRules.runs(deps.television)

    /** The queue's changes, one at a time: each call's, and the walk's pick of the next item. */
    private val changes = Mutex()

    /** One walk at a time: a host that starts while another's walk ends waits for it. */
    private val walking = Mutex()

    /** The item the walk runs now: its row's id and its job, which Pause, Cancel, Remove and Replace stop. */
    private class Current(val id: String, val job: Job)

    @Volatile
    private var current: Current? = null

    /** What a removed download was, to put it back on Undo; [token] is its removal's, whose timer deletes it. */
    private class Removed(val state: DownloadState, val reason: String?, val token: Any)

    // Under [changes].
    private val removed = HashMap<String, Removed>()

    /** What ends a wait before Resolve is asked again sooner: [retryNow], [resume], and the network's return (L381). */
    private val wakes = BackoffWakes(deps.network)

    /** Every download but those removed, by position, with the live write's bytes and rate (L380's page and notification). */
    val state: StateFlow<List<Item>> = if (!runs) {
        MutableStateFlow(emptyList())
    } else {
        combine(dao.all(), writer.live) { rows, live ->
            rows.filter { it.state != DownloadState.Deleting }.map { row ->
                if (live != null && live.id == row.id) Item(row, live.written, writer.bytesPerSecond()) else Item(row, row.safeOffset, null)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
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
     * L338): the row Queued at the queue's end, made with its bookkeeping folder and marker (L346). Scheduling its host is
     * the visible app's (L335). Null on a TV.
     */
    suspend fun enqueue(target: DownloadTarget, candidates: List<DownloadCandidate>): Download? {
        if (!runs) return null
        require(candidates.isNotEmpty()) { "A download needs a release to try" }
        return changes.withLock {
            val at = now()
            val position = (dao.all().first().maxOfOrNull { it.position } ?: -1) + 1
            val row = Download(
                id = Download.newId(), watchKey = target.watchKey, kind = target.kind, title = target.title, subtitle = target.subtitle,
                showTmdbId = target.showTmdbId, season = target.season, episode = target.episode, seasonEpisodes = target.seasonEpisodes,
                episodeName = target.episodeName, movieTmdbId = target.movieTmdbId, year = target.year, runtimeMinutes = target.runtimeMinutes,
                posterPath = target.posterPath, stillPath = target.stillPath, position = position, candidates = candidates.take(MAX_CANDIDATES),
                indexerSize = candidates.first().size, createdAt = at, updatedAt = at,
            )
            deps.storage.add(row)
            log.info("download {}: queued at {}, {} release{} to try", row.id, position, row.candidates.size, if (row.candidates.size == 1) "" else "s")
            row
        }
    }

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
     * Lets the queue go on (L380): a Paused or Stopped download back at its head, and those waiting for the providers or
     * for storage queued again; then the visible app schedules the host. A wait before a reopen ends now (L381).
     */
    suspend fun resume() {
        if (!runs) return
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
            removed.remove(id)
            if (dao.get(id) == null) return@withLock
            deps.storage.dropSource(id)
            delete(id)
            log.info("download {}: cancelled", id)
        }
    }

    /**
     * Removes the downloads [ids] (L379): each hidden at once (Deleting), one running stopped first, and deleted, files and
     * all, once the Undo bar's time has gone, unless [undoRemove] brought it back. A process that dies meanwhile leaves
     * them Deleting, for the next start's reconcile to finish.
     */
    suspend fun remove(ids: Collection<String>) {
        if (!runs) return
        val token = Any()
        val marked = changes.withLock {
            ids.mapNotNull { id ->
                stop(id)
                val row = dao.get(id)?.takeIf { it.state != DownloadState.Deleting } ?: return@mapNotNull null
                removed[id] = Removed(row.state, row.reason, token)
                dao.setState(id, DownloadState.Deleting, null, now())
                id
            }
        }
        if (marked.isEmpty()) return
        log.info("Downloads: {} removed, deleted in {} s unless brought back", marked.size, deps.undoMillis / 1000)
        scope.launch {
            delay(deps.undoMillis)
            changes.withLock {
                marked.filter { removed[it]?.token === token }.forEach { id ->
                    removed.remove(id)
                    if (dao.get(id)?.state == DownloadState.Deleting) delete(id)
                }
            }
        }
    }

    /** Brings back the downloads [ids] [remove] hid, while their Undo lasts: as they were, one stopped as it ran queued again. */
    suspend fun undoRemove(ids: Collection<String>) {
        if (!runs) return
        changes.withLock {
            ids.forEach { id ->
                val was = removed.remove(id) ?: return@forEach
                if (dao.get(id)?.state != DownloadState.Deleting) return@forEach
                // One stopped as it ran goes back to the queue, to go on from its safe offset when its turn comes.
                val running = was.state == DownloadState.Running || was.state == DownloadState.Resolving
                dao.setState(id, if (running) DownloadState.Queued else was.state, if (running) null else was.reason, now())
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
     * place, to resolve from the new ones.
     */
    suspend fun replace(id: String, candidates: List<DownloadCandidate>) {
        if (!runs) return
        require(candidates.isNotEmpty()) { "A download needs a release to try" }
        changes.withLock {
            stop(id)
            val row = dao.get(id)?.takeIf { it.state != DownloadState.Deleting && it.state != DownloadState.Done } ?: return@withLock
            try {
                deps.storage.deleteVideo(id)
            } catch (e: DownloadStorage.Unavailable) {
                // Its file can't go while the volume is away: the new release's is written over it from its first byte.
                log.info("download {}: its file stays until its volume is back", id)
            }
            deps.storage.dropSource(id)
            dao.update(
                row.copy(
                    state = DownloadState.Queued, reason = null, candidates = candidates.take(MAX_CANDIDATES), tried = emptyList(), oneVideo = false,
                    release = null, fileName = null, size = null, indexerSize = candidates.first().size, safeOffset = 0, gaps = emptyList(),
                    relativePath = null, video = null, tracks = null, completedAt = null, updatedAt = now(),
                ),
            )
            log.info("download {}: {} other release{} to try", id, candidates.size, if (candidates.size == 1) "" else "s")
        }
    }

    /**
     * Tries the failed download [id] again (Try again, Download again): its tries reset and queued, to resolve again from
     * its releases. The one its file came from, picked again at its size, goes on from the safe offset (as after "Storage
     * is full"); another starts from its first byte (Resolve's rule).
     */
    suspend fun retry(id: String) {
        if (!runs) return
        changes.withLock {
            val row = dao.get(id)?.takeIf { it.state == DownloadState.Failed } ?: return@withLock
            deps.storage.dropSource(id)
            dao.update(row.copy(state = DownloadState.Queued, reason = null, tried = emptyList(), updatedAt = now()))
            log.info("download {}: tried again", id)
        }
    }

    /** The downloads that can run now, in their order (L388): what a host waits for, and what the app's start schedules for. */
    suspend fun runnable(): List<Download> = if (!runs) emptyList() else DownloadHostRules.runnable(dao.queue().first(), deps.storage.volumeThere())

    /**
     * Walks the queue under [host] while it lives: the next runnable download, one left running by a kill first, resolved
     * then written, then the next. Returns once nothing can run (L388): all done or failed, the queue held, or a wait for
     * the providers or for storage. The host ends then; cancelled by its host, the item under way stops at its safe offset,
     * its row as a kill leaves it, for the next walk.
     */
    suspend fun run(host: DownloadHost) {
        if (!runs) return
        walking.withLock {
            log.info("Downloads: the {} runs the queue", host.name.lowercase())
            coroutineScope {
                while (true) {
                    var walk = Walk.Next
                    val job = changes.withLock {
                        val row = next() ?: return@withLock null
                        launch(deps.io) { walk = item(row.id) }.also { current = Current(row.id, it) }
                    } ?: break
                    job.join()
                    changes.withLock { if (current?.job === job) current = null }
                    if (walk == Walk.End) break
                }
            }
            log.info("Downloads: nothing more runs under the {}", host.name.lowercase())
        }
    }

    /**
     * At the app's start, before any host (L359): storage's reconcile first (item 4); then each download a process left
     * Running or Resolving with no host on it now is Stopped, "Stopped by Android", held until Resume, when the process's
     * last end was the user's (Task Manager's Stop or a force stop) after the row's last update; any other end (a kill, a
     * reboot, an update), or one before that update, leaves it for its host to go on with. Runs on a TV too.
     */
    suspend fun reconcile() {
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
                val stopped = exit != null && exit.reason == DownloadExit.USER_REQUESTED && exit.at > row.updatedAt
                if (stopped) dao.setState(row.id, DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID, now())
                val decision = if (stopped) DownloadTrace.Decision.Stopped else DownloadTrace.Decision.LeftForHost
                deps.debug.trace(DownloadTrace.Reconciled(row.id, decision, exit?.reason, exit?.at))
                log.info("download {}: left {} as the app last ended (exit {}): {}", row.id, row.state, exit?.reason ?: "unknown", decision.word)
            }
        }
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

    /** Resolves and writes download [id], and the next release after one given up; what the walk does then. */
    private suspend fun item(id: String): Walk = try {
        releases(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nothing the item expects: it fails, its file and record kept, rather than run into the same failure again.
        log.warn("download {}: failed on {}", id, e::class.simpleName)
        try {
            failed(id, if (e is IOException) DownloadWriter.WRITE_FAILED else FAILED_IN_APP)
        } catch (again: CancellationException) {
            throw again
        } catch (again: Exception) {
            log.warn("download {}: couldn't say it failed: {}", id, again::class.simpleName)
        }
        Walk.Next
    }

    // The network followed meanwhile: its return ends a wait between Resolve's tries.
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
                    if (why != null && resolution.reason == DownloadResolver.NO_OTHER_RELEASE) {
                        dao.setState(id, DownloadState.Failed, gaveUpWords(why), now())
                    }
                    deps.storage.dropSource(id)
                    log.info("download {}: failed", id)
                    return Walk.Next
                }
                is Resolution.Waiting -> return Walk.End
                is Resolution.Unreachable -> {
                    val since = outOfReach ?: deps.timeSource.markNow().also { outOfReach = it }
                    if (since.elapsedNow() >= DownloadWriter.UNREACHABLE_FOR) {
                        log.info("download {}: its providers out of reach for {} min: waiting", id, since.elapsedNow().inWholeMinutes)
                        waiting(id, DownloadHostRules.CANT_REACH_PROVIDER)
                        return Walk.End
                    }
                    val kind = resolution.error::class.simpleName
                    log.info("download {}: its providers out of reach ({}); resolving it again in {} s", id, kind, wait.inWholeSeconds)
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

    /** Download [id]'s file complete: renamed, its row Done with the [gaps] still missing, its NZB and password gone (L346). */
    private suspend fun finished(id: String, fileName: String, size: Long, gaps: List<LongRange>) {
        deps.storage.finish(id, fileName)
        val row = dao.get(id) ?: return
        val at = now()
        dao.update(
            row.copy(
                state = DownloadState.Done, reason = null, safeOffset = size, gaps = gaps.map { DownloadGap(it.first, it.last) }, completedAt = at,
                updatedAt = at,
            ),
        )
        deps.storage.dropSource(id)
        deps.debug.trace(DownloadTrace.Completed(id, size))
        log.info("download {}: done, {} bytes, {} part{} missing", id, size, gaps.size, if (gaps.size == 1) "" else "s")
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
            deps.missing(release, asked)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't remember its release as missing: {}", id, e::class.simpleName)
        }
    }

    /**
     * Download [id]'s release given up: its file deleted and its room given back, its NZB and password gone, so Resolve
     * finds the next rather than opening this one again from the copy, and its row back to nothing written. False when its
     * volume isn't there to delete it from: it waits for it.
     */
    private suspend fun discard(id: String): Boolean {
        try {
            deps.storage.deleteVideo(id)
        } catch (e: DownloadStorage.Unavailable) {
            waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE)
            return false
        }
        deps.storage.dropSource(id)
        val row = dao.get(id) ?: return true
        dao.update(
            row.copy(
                release = null, fileName = null, size = null, oneVideo = false, video = null, tracks = null, safeOffset = 0, gaps = emptyList(),
                relativePath = null, updatedAt = now(),
            ),
        )
        return true
    }

    /** Download [id] failed, for [reason]: its NZB and password go (L346), its file and record stay, for Try again. */
    private suspend fun failed(id: String, reason: String) {
        dao.setState(id, DownloadState.Failed, reason, now())
        deps.storage.dropSource(id)
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

        /** The waits for the providers, which a provider saved or Resume queues again (L388). */
        private val PROVIDER_WAITS = setOf(DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_PROVIDER)

        /** The waits that end the host, which Resume queues again (L388). */
        private val ENDING_WAITS = PROVIDER_WAITS + DownloadHostRules.STORAGE_UNAVAILABLE

        /** A failed download's words, its last release given up [why] as it was written and none left to try. */
        fun gaveUpWords(why: DownloadWriter.GiveUp): String = when (why) {
            DownloadWriter.GiveUp.Incomplete, DownloadWriter.GiveUp.LongGap -> DownloadResolver.INCOMPLETE
            DownloadWriter.GiveUp.Corrupt -> DownloadResolver.DAMAGED
        }
    }
}

private val log = LoggerFactory.getLogger(DownloadRunner::class.java)

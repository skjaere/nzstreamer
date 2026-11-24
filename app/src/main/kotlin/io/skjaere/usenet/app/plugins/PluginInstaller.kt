package io.skjaere.usenet.app.plugins

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Installs, updates and removes plugins from the list (L633, L621, L581, L618), with no dialog anywhere (W155):
 * - Install: the space, then the download ([PluginDownloads]: the address, https on every hop, the list's size, the
 *   SHA-256 against the list), then the registry's install (the archive's checks, the store, the registry, the slot, the
 *   load). Its states ([states]) are the card's: downloading, checking, installed, or failed with its one line; Cancel
 *   while it downloads and checks keeps nothing, and Try again starts it over. At most two plugins installed (L618): a
 *   third isn't downloaded.
 * - Install again, for a plugin refused at a start: the installed version again, or the one offered, through every check.
 *   The Plugins page is never on a slot's stack (L581), so a newer version it fetched takes over at once.
 * - After each read of the list ([PluginList.reads]): each installed plugin's update downloads and is checked in the app's
 *   scope, stored beside the running version, and takes over at the swap point (the registry's); a version the list
 *   withdraws is let go there and stays off until a fixed one comes, which installs as an update.
 * - Remove, Turn on again: the registry's, its card cleared.
 * Logs name a plugin by its id and version, and what went wrong by its kind: never an address or a path.
 */
class PluginInstaller(
    private val registry: PluginRegistry,
    private val list: StateFlow<PluginListState>,
    reads: Flow<PluginIndex>,
    private val downloads: () -> PluginDownloads,
    private val space: () -> Long,
    private val scope: CoroutineScope,
    private val television: () -> Boolean,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val compat: PluginCompat = PluginCompat(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val shown = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    private val applied = MutableStateFlow(0)

    /** What each plugin's card shows of an install the page started: by id, none where nothing runs or was left to say. */
    val states: StateFlow<Map<String, InstallState>> = shown.asStateFlow()

    /** How many reads of the list have been applied, their updates downloaded and stored: a test's view of when they end. */
    @VisibleForTesting
    internal val readsApplied: StateFlow<Int> = applied.asStateFlow()

    init {
        scope.launch {
            reads.collect { index ->
                try {
                    afterRead(index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Plugins: the list's updates weren't applied: {}", e.javaClass.simpleName)
                }
                applied.update { it + 1 }
            }
        }
    }

    /**
     * Install, for plugin [id] the list offers and that isn't installed: its offered version, downloaded, checked, stored and
     * loaded, the card following it. Null where there's nothing to start: not offered, one already on its way, or two
     * plugins installed (or about to be) already.
     */
    fun install(id: String): Job? {
        val listed = list.value.index?.plugins?.firstOrNull { it.id == id } ?: return null
        if (installed(id) != null) return null
        val version = list.value.index?.offer(id, installed = null, sdk, compat)?.offered ?: return null
        // The count and the start at once: two Installs pressed together never make three.
        return synchronized(jobs) {
            val coming = jobs.filterValues { it.isActive }.keys.filter { installed(it) == null }
            if ((registry.plugins.value.orEmpty().map { it.id } + coming).distinct().size >= PluginRegistry.MAX_INSTALLED) return null
            start(id, shown = true) { fetch(id, listed.name, version) }
        }
    }

    /**
     * Install again, for an installed plugin refused at a start: [installAgainVersion] through every check. A newer version
     * takes over at once, as at a swap point: the page it is pressed on is never on the plugin's slot's stack (L581).
     */
    fun installAgain(id: String): Job? {
        val name = list.value.index?.plugins?.firstOrNull { it.id == id }?.name ?: return null
        val version = installAgainVersion(id) ?: return null
        return start(id, shown = true) { fetch(id, name, version, update = true, takesOver = true) }
    }

    /** Try again, after a failed install or Install again: the same again. */
    fun tryAgain(id: String): Job? = if (installed(id) == null) install(id) else installAgain(id)

    /**
     * What Install again would fetch for installed plugin [id] (L633): its installed version as the list still gives it,
     * else the version the list offers above it; null where the list has neither (the card then has Remove alone). A
     * version the list withdrew, one this NZStreamer can't run, and an update that already couldn't start are never it.
     */
    fun installAgainVersion(id: String): ListedVersion? {
        val plugin = installed(id) ?: return null
        val index = list.value.index ?: return null
        val listed = index.plugins.firstOrNull { it.id == id } ?: return null
        val same = listed.versions.firstOrNull { version ->
            version.versionCode == plugin.versionCode && version.versionCode !in listed.withdrawn && version.minSdk <= sdk &&
                compat.apiAccepted(version.apiMajor, version.apiMinor) && compat.libsAccepted(version.libs)
        }
        return same ?: index.offer(id, plugin.versionCode, sdk, compat)?.offered?.takeIf { it.versionCode != plugin.failedUpdate?.versionCode }
    }

    /** Cancel, while [id]'s install downloads or is checked: it stops, its file is deleted and nothing of it is kept. */
    fun cancel(id: String) {
        val state = shown.value[id]
        if (state !is InstallState.Downloading && state !is InstallState.Checking) return
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    /** The Plugins page was left: the lines of installs that ended (Installed, a failure's) go with it. */
    fun pageLeft() {
        shown.update { states -> states.filterValues { it is InstallState.Downloading || it is InstallState.Checking } }
    }

    /**
     * Remove (L633), once asked: whatever install or update of [id] runs is cancelled, then the registry lets it go, frees
     * its slot and deletes its code, data, cache and secrets, and its entry. What it played stays. Its card goes back to
     * Install.
     */
    suspend fun remove(id: String) {
        synchronized(jobs) { jobs[id] }?.cancelAndJoin()
        registry.remove(id)
        shown.update { it - id }
    }

    /** Turn on again, for a version turned off after a crash or an error (L619): it loads at once. */
    suspend fun turnOnAgain(id: String) = registry.turnOnAgain(id)

    /** Turn on again, after two early crashes turned the plugins off (L619). */
    suspend fun turnPluginsOnAgain() = registry.turnPluginsOnAgain()

    /** [plugin]'s last update while it is news: for seven days after it took over (L621), else null. */
    fun recentUpdate(plugin: InstalledPlugin): LastUpdate? = plugin.lastUpdate?.takeIf { now() - it.at < UPDATE_NEWS_MS }

    /**
     * After a read of the list (L621): each installed plugin's withdrawn version let go at the swap point (a waiting one
     * dropped), and its update, the highest version offered above what it has or waits with, downloaded and stored
     * beside it, one plugin's at a time. An update that already couldn't start isn't fetched again; a newer one is.
     */
    private suspend fun afterRead(index: PluginIndex) {
        val updates = registry.plugins.filterNotNull().first().mapNotNull { plugin ->
            val listed = index.plugins.firstOrNull { it.id == plugin.id } ?: return@mapNotNull null
            val update = follow(plugin, listed, index) ?: return@mapNotNull null
            start(plugin.id, shown = false) { fetch(plugin.id, listed.name, update, update = true) }
        }
        updates.joinAll()
    }

    /**
     * [plugin] as [index] lists it ([listed]): its withdrawn version let go at the swap point (a waiting one dropped); and
     * the update to fetch, the highest version offered above what it has or waits with, unless that one couldn't start.
     */
    private suspend fun follow(plugin: InstalledPlugin, listed: ListedPlugin, index: PluginIndex): ListedVersion? {
        if (plugin.versionCode in listed.withdrawn) registry.withdraw(plugin.id, plugin.versionCode)
        var waiting = plugin.waitingVersion
        if (waiting != null && waiting in listed.withdrawn) {
            registry.withdraw(plugin.id, waiting)
            waiting = null
        }
        val update = index.offer(plugin.id, maxOf(plugin.versionCode, waiting ?: 0), sdk, compat)?.offered ?: return null
        return update.takeIf { it.versionCode != plugin.failedUpdate?.versionCode }
    }

    /**
     * An install the page started took its version from the list as the page showed it, maybe its copy while a read ran:
     * a read that came back meanwhile, before [id] was installed, passed it over. Once installed, it follows the list as it
     * stands now, as after a read (L621).
     */
    private suspend fun caughtUp(id: String) {
        try {
            val index = list.value.index ?: return
            val plugin = installed(id) ?: return
            val listed = index.plugins.firstOrNull { it.id == id } ?: return
            val update = follow(plugin, listed, index) ?: return
            with(Fetch(id, shown = false)) { fetch(id, listed.name, update, update = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Plugin {}: the list wasn't followed after its install: {}", id, e.javaClass.simpleName)
        }
    }

    /**
     * [work] for [id] in the app's scope, unless one runs for it already; where [shown], the card follows it from
     * Downloading, a Cancel takes its state away, and once installed the plugin follows the list as it stands then.
     */
    private fun start(id: String, shown: Boolean, work: suspend Fetch.() -> InstallState?): Job? = synchronized(jobs) {
        if (jobs[id]?.isActive == true) return@synchronized null
        if (shown) show(id, InstallState.Downloading(0))
        val fetch = Fetch(id, shown)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val end = try {
                fetch.work()
            } catch (e: CancellationException) {
                if (shown) show(id, null)
                throw e
            }
            if (shown) show(id, end)
            if (shown && end is InstallState.Installed) caughtUp(id)
        }
        jobs[id] = job
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) } }
        job.start()
        job
    }

    /** The steps of one plugin's fetch, telling its card where it is. */
    private inner class Fetch(private val id: String, private val shown: Boolean) {
        fun downloaded(bytes: Long, size: Long) {
            if (shown) show(id, InstallState.Downloading((bytes * 100 / size.coerceAtLeast(1)).toInt().coerceIn(0, 100)))
        }

        fun checking() {
            if (shown) show(id, InstallState.Checking)
        }
    }

    /**
     * Plugin [id]'s [version] as the list gives it, by the list's [name]: downloaded, then installed by the registry, whose
     * checks read it only once its SHA-256 was the list's; the download deleted after, whatever came of it. The card's
     * state at the end: installed, or why not; none for an update stored to wait for its swap. An [update] is for the
     * plugin installed now, and installs nothing where it was removed meanwhile; one that [takesOver] doesn't wait for a
     * swap point: the registry's swap runs at once, and to its end.
     */
    private suspend fun Fetch.fetch(
        id: String,
        name: String,
        version: ListedVersion,
        update: Boolean = false,
        takesOver: Boolean = false,
    ): InstallState? {
        val file = when (val download = downloads().download(id, version) { downloaded(it, version.size) }) {
            is Download.Done -> download.file
            is Download.NoSpace -> {
                log.warn("Plugin {} {} not downloaded: no space", id, version.versionCode)
                return InstallState.Failed(spaceLine(download.toFree, television()))
            }
            is Download.Failed -> {
                log.warn("Plugin {} {} not downloaded: {}", id, version.versionCode, download.kind)
                return InstallState.Failed(if (download.kind in DOESNT_MATCH) Refusal.Hash.installLine else DOWNLOAD_FAILED)
            }
        }
        try {
            checking()
            return when (val result = registry.install(file, InstallRequest(id, name, version.versionCode, version.sha256, update))) {
                is InstallResult.Installed -> InstallState.Installed(result.slot)
                InstallResult.UpdateWaiting -> if (takesOver) tookOver(id) else null
                is InstallResult.Refused -> InstallState.Failed(result.refusal.installLine)
                // Two installed already, or that version already couldn't start: the card has its own line for either. An
                // update of a plugin removed meanwhile: its card is back to Install.
                InstallResult.NoRoom, InstallResult.NotAgain, InstallResult.NotInstalled -> null
                // The store couldn't write it: a disk that filled up says so, as the space check would have.
                InstallResult.Failed -> InstallState.Failed(space().let { free -> if (free < version.size) spaceLine(version.size - free, television()) else DOWNLOAD_FAILED })
            }.also { if (it is InstallState.Failed) log.warn("Plugin {} {} not installed", id, version.versionCode) }
        } finally {
            file.delete()
        }
    }

    /** [id]'s stored update, taking over now: installed where it runs after, else its card shows why not. */
    private suspend fun tookOver(id: String): InstallState? {
        withContext(NonCancellable) { registry.atSwapPoint(id) }
        return installed(id)?.takeIf { it.status is PluginStatus.Running }?.let { InstallState.Installed(it.slot) }
    }

    private fun installed(id: String): InstalledPlugin? = registry.plugins.value?.firstOrNull { it.id == id }

    private fun show(id: String, state: InstallState?) {
        shown.update { states -> if (state == null) states - id else states + (id to state) }
    }

    companion object {
        /** "Updated to …" shows this long after an update took over (L621). */
        const val UPDATE_NEWS_MS = 7 * 24 * 60 * 60 * 1000L

        /** A download that didn't come, whatever stopped it but a mismatch (L633). */
        const val DOWNLOAD_FAILED = "Couldn't download it. Check your connection."

        private val DOESNT_MATCH = setOf(DownloadFailure.Mismatch, DownloadFailure.TooLarge)
        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        private val log = LoggerFactory.getLogger(PluginInstaller::class.java)

        /**
         * Too little space (L633): "Not enough space on this phone. Free 0.7 MB, then try again." ("this TV" on a TV),
         * [toFree] in MB with one decimal, rounded up, so freeing that much is enough; a decimal point in any language, as
         * the card's size line has.
         */
        fun spaceLine(toFree: Long, television: Boolean): String {
            val tenths = ((toFree + 99_999) / 100_000).coerceAtLeast(1)
            val mb = String.format(Locale.ROOT, "%.1f", tenths / 10.0)
            return "Not enough space on this ${if (television) "TV" else "phone"}. Free $mb MB, then try again."
        }

        /** A recent update's line (L621): "Updated to 1.2 today", or "Updated to 1.2 · 20 Nov" from an earlier day. */
        fun updatedLine(update: LastUpdate, now: Long, zone: ZoneId): String {
            val day = Instant.ofEpochMilli(update.at).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return "Updated to ${update.versionName}" + if (day == today) " today" else " · ${DAY.format(day)}"
        }

        /** An installed plugin whose newest listed version this NZStreamer can't run (L621): "Version 1.1 · 1.2 needs a newer NZStreamer". */
        fun needsNewerLine(installed: String, newest: String): String = "Version $installed · $newest needs a newer NZStreamer"

        /**
         * The app's installer: the registry's plugins, the list's reads, and downloads from the list's source at each one
         * (GitHub's, or a device test's in a debug build, PluginSource.current), through [http], into
         * [cacheDir]/plugins/.download, with the space free in [filesDir]. Free as the volume has it now (L633): what
         * Android could clear from caches isn't counted, and a plugin is a few hundred KB.
         */
        @SuppressLint("UsableSpace")
        fun of(
            filesDir: File,
            cacheDir: File,
            registry: PluginRegistry,
            list: PluginList,
            http: () -> HttpClient,
            scope: CoroutineScope,
            sdk: Int = Build.VERSION.SDK_INT,
            television: () -> Boolean,
        ): PluginInstaller {
            val folder = File(File(cacheDir, PluginStore.DIR), PluginStore.DOWNLOADS)
            val space = { filesDir.usableSpace }
            return PluginInstaller(registry, list.state, list.reads, { PluginDownloader(PluginSource.current(), http(), folder, space) }, space, scope, television, sdk)
        }
    }
}

/** An install the Plugins page started, as its card shows it. */
sealed interface InstallState {
    /** Its file coming, [percent] of the list's size. */
    data class Downloading(val percent: Int) : InstallState

    /** Its file and signature being checked, then stored and loaded. */
    data object Checking : InstallState

    /** Installed just now, in [slot]: "Installed. <label> is now a tab …" with Open <label>, until the page is left. */
    class Installed(val slot: Int) : InstallState

    /** It failed, and [line] says why (L633), with Try again: nothing of it was kept. */
    class Failed(val line: String) : InstallState
}

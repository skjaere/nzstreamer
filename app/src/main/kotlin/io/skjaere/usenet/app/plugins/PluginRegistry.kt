package io.skjaere.usenet.app.plugins

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The installed plugins (L616, L582, L619, L581, L635), at most two at once (L618), each bound to the lowest free of four
 * slots and kept there across starts, so a back stack saved across a process death opens the same plugin.
 * - Their files are read as the process starts ([start]); their code loads only once the first screen asks
 *   ([firstActivity]), never in a process started for background work alone, off the main thread, each plugin on its
 *   own: one that can't start says why, and the others load.
 * - A version counts as the highest that ever loaded only once its entry was made and its tab() answered (L583), so an
 *   update that passed the checks but couldn't start can go back to the version before.
 * - An update waits for its slot to leave the screen ([atSwapPoint]), then takes over in the same process; one that can't
 *   start goes back to the version before, with a new loader over its kept file, and isn't tried again.
 * - Each instance's host has a scope whose failures, and the host's calls into its code ([PluginCalls]), turn that version
 *   off "after an error" at once rather than close NZStreamer; Turn on again loads it at once.
 * Log lines name a plugin by its id and version, and a reason by its kind: never a path, a key or the plugin's own words.
 */
class PluginRegistry(
    private val store: PluginStore,
    private val loading: PluginLoading,
    private val hosts: PluginHosts,
    val crashGuard: PluginGuard,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val filesRead = CompletableDeferred<Unit>()
    private val asked = AtomicBoolean()

    /** The first screen's start check came back (or failed): what waits for it may take the lock. */
    private val checked = CompletableDeferred<Unit>()
    private val keys = AtomicLong()
    private val calls = PluginCalls(::failed)

    // Under [lock].
    private val records = mutableListOf<PluginRecord>()
    private val statuses = mutableMapOf<String, PluginStatus>()

    /** Whether the crash guard's start check ran in this process: no plugin code loads before it (L619). Under [lock]. */
    private var startChecked = false

    /** The plugins installForTest put in slots: in memory alone, never written to the registry's file. Under [lock]. */
    private val forTest = mutableMapOf<String, NzPlugin>()

    private val shown = MutableStateFlow<List<InstalledPlugin>?>(null)
    private val off = MutableStateFlow(false)

    /** Whether this start loads no plugin: NZStreamer closed twice just after starting (L619), until Turn on again. */
    val pluginsOff: StateFlow<Boolean> = off.asStateFlow()

    /** The installed plugins in slot order; null until the files are read (a slot then draws nothing, L590). */
    val plugins: StateFlow<List<InstalledPlugin>?> = shown.asStateFlow()

    /** Reads the registry and sweeps away what no installed plugin owns, off the main thread: as the process starts. */
    fun start() {
        scope.launch(io) {
            try {
                lock.withLock { readFiles() }
            } finally {
                filesRead.complete(Unit)
            }
        }
    }

    /**
     * The first screen asks for the plugins: their code loads, once per process, as soon as their files are read. First
     * the crash guard says what the last start left (L619): a version a crash blamed, or whose load died twice in a row,
     * is off; after two early crashes in a row, none loads. A start that lasts [PluginCrashGuard.EARLY_MS] tells it so.
     */
    fun firstActivity() {
        if (!asked.compareAndSet(false, true)) return
        scope.launch(io) {
            filesRead.await()
            try {
                lock.withLock {
                    keepStartCheck()
                    if (off.value) {
                        records.filter { it.id !in forTest }.forEach { record ->
                            runningInstance(record.id)?.let(::letGo)
                            statuses[record.id] = PluginStatus.PluginsOff
                        }
                        publish()
                        return@withLock
                    }
                    loadWaiting()
                    scope.launch(io) {
                        delay(PluginCrashGuard.EARLY_MS)
                        crashGuard.lasted()
                    }
                }
            } finally {
                checked.complete(Unit)
            }
        }
    }

    /** Turn on again, after two early crashes turned the plugins off (L619): every plugin loads at once, as its state allows. */
    suspend fun turnPluginsOnAgain() {
        filesRead.await()
        lock.withLock {
            if (!off.value) return@withLock
            crashGuard.turnOnPlugins()
            off.value = false
            records.filter { statuses[it.id] == PluginStatus.PluginsOff }.forEach { statuses[it.id] = PluginStatus.Waiting }
            if (startChecked) loadWaiting() else publish()
        }
    }

    /**
     * The guard's start check, kept: the versions it found off are off in the registry's file before its records go. An
     * update waiting for its swap point is checked too: one a crash blamed, or whose load there died twice in a row,
     * couldn't start, and isn't tried again (L581); the version before runs on. Under [lock].
     */
    private suspend fun keepStartCheck() {
        val ours = records.filter { it.id !in forTest }
        val toLoad = ours.filter { it.state == StoredState.On || it.state == StoredState.Refused }
        val waiting = ours.mapNotNull { record -> record.pending?.let { record.id to it.versionCode } }
        val check = crashGuard.startCheck(toLoad.map { it.id to it.versionCode } + waiting)
        val failedUpdates = waiting.filter { it in check.crashed || it in check.errored }
        failedUpdates.forEach { (id, versionCode) ->
            log.warn("Plugin {} {} couldn't take over from {}: it crashed as it took over", id, versionCode, record(id)?.versionCode)
            update(id) { it.copy(pending = null, failedVersion = FailedVersion(versionCode, it.pending!!.versionName, LoadFailure.Other.reason)) }
        }
        val turnedOff = toLoad.mapNotNull { record ->
            val version = record.id to record.versionCode
            when (version) {
                in check.crashed -> record.id to StoredState.Crashed
                in check.errored -> record.id to StoredState.Errored
                else -> null
            }
        }
        turnedOff.forEach { (id, state) ->
            log.warn("Plugin {} {} is off: {}", id, record(id)?.versionCode, state)
            update(id) { it.copy(state = state) }
        }
        val saved = if (turnedOff.isNotEmpty() || failedUpdates.isNotEmpty()) persist() else true
        // Only once the registry's file holds what the guard found do its records go, and a failed update's file: a write
        // that failed (a full disk) leaves both for the next start to apply.
        if (saved) {
            failedUpdates.forEach { (id, versionCode) -> withContext(io) { store.deleteVersion(id, versionCode) } }
            crashGuard.recordsApplied()
        }
        off.value = check.pluginsOff
        startChecked = true
    }

    /**
     * Each plugin not loaded yet, in slot order, each on its own: one that can't start says why, whatever stopped it, and
     * the others load. Only the process's own errors go on up. Under [lock].
     */
    private suspend fun loadWaiting() {
        records.sortedBy { it.slot }.filter { statuses[it.id] == null || statuses[it.id] == PluginStatus.Waiting }.forEach { record ->
            statuses[record.id] = try {
                begin(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!e.isPluginsOwn()) throw e
                log.warn("Plugin {} {} couldn't start: {}", record.id, record.versionCode, e.javaClass.name)
                PluginStatus.CouldntStart(LoadFailure.Other)
            }
            publish()
        }
    }

    /**
     * Installs [download], a file whose SHA-256 the list's matched, as [request] names it: checked again (L583, L584),
     * stored read-only, bound to the lowest free slot and, once the first screen's start check ran, loaded (before it, the
     * start's own load takes it). A higher version of one
     * installed is stored beside it, and waits for the swap ([atSwapPoint]); the same version again replaces its file and,
     * if it isn't running, loads it. Nothing of a refused or failed install is kept.
     */
    suspend fun install(download: File, request: InstallRequest): InstallResult {
        filesRead.await()
        return lock.withLock {
            val id = request.id
            val existing = record(id)
            when {
                existing == null && records.size >= MAX_INSTALLED -> return@withLock InstallResult.NoRoom
                existing?.failedVersion?.versionCode == request.versionCode -> return@withLock InstallResult.NotAgain
                existing != null && request.versionCode < existing.versionCode -> return@withLock InstallResult.Refused(Refusal.Older)
            }
            val expected = Expectation(id, request.sha256, request.versionCode, existing?.highestLoaded ?: 0, stored = false)
            val facts = when (val verdict = withContext(io) { loading.verify(download, expected) }) {
                is Verdict.Refused -> {
                    log.warn("Plugin {} {} refused: {}", id, request.versionCode, verdict.refusal)
                    return@withLock InstallResult.Refused(verdict.refusal)
                }
                is Verdict.Accepted -> verdict.facts
            }
            val result = try {
                withContext(io) { store.writeVersion(id, request.versionCode, download, request.sha256) }
                when {
                    existing == null -> {
                        withContext(io) { store.emptyFolders(id) }
                        val slot = PluginStore.SLOTS.first { slot -> records.none { it.slot == slot } }
                        records += PluginRecord(id, request.name, request.versionCode, facts.versionName, request.sha256, slot, installedAt = now())
                        persist()
                        statuses[id] = if (startChecked) begin(record(id)!!) else PluginStatus.Waiting
                        InstallResult.Installed(slot)
                    }
                    request.versionCode == existing.versionCode -> {
                        update(id) { it.copy(name = request.name, sha256 = request.sha256, state = if (it.state == StoredState.Refused) StoredState.On else it.state) }
                        persist()
                        if (startChecked && runningInstance(id) == null) statuses[id] = begin(record(id)!!)
                        InstallResult.Installed(existing.slot)
                    }
                    else -> {
                        existing.pending?.takeIf { it.versionCode != request.versionCode }?.let { withContext(io) { store.deleteVersion(id, it.versionCode) } }
                        update(id) { it.copy(name = request.name, pending = PendingVersion(request.versionCode, facts.versionName, request.sha256)) }
                        persist()
                        InstallResult.UpdateWaiting
                    }
                }
            } catch (e: IOException) {
                log.warn("Plugin {} {} couldn't be stored: {}", id, request.versionCode, e.javaClass.simpleName)
                if (existing == null) {
                    records.removeAll { it.id == id }
                    statuses.remove(id)
                    withContext(io) { store.delete(id) }
                } else if (request.versionCode != existing.versionCode && request.versionCode != existing.pending?.versionCode) {
                    // Only a folder this install made: the running version's and the waiting one's still hold the file stored
                    // before, which the failed write left as it was.
                    withContext(io) { store.deleteVersion(id, request.versionCode) }
                }
                InstallResult.Failed
            }
            publish()
            result
        }
    }

    /**
     * The swap point (L581): no entry of [id]'s slot is on the current back stack. An update waiting takes over: the old
     * instance's scope is cancelled and its close() called, the new version loads under a new instance key, and the old
     * version's folder goes once it did. One that can't start goes back to the version before and isn't tried again.
     * True where something changed, so the slot's saved stack is to be cleared. No plugin code loads before the first
     * screen asked, nor before its start check ran, nor while plugins are off (L619): then the update waits for a later
     * swap point, and nothing changed.
     */
    suspend fun atSwapPoint(id: String): Boolean {
        filesRead.await()
        if (asked.get()) checked.await()
        return lock.withLock {
            if (!startChecked || off.value) return@withLock false
            val record = record(id) ?: return@withLock false
            val pending = record.pending ?: return@withLock false
            runningInstance(id)?.let(::letGo)
            when (val outcome = load(id, pending.versionCode, pending.sha256, record.highestLoaded)) {
                is Outcome.Running -> {
                    update(id) {
                        it.copy(
                            versionCode = pending.versionCode, versionName = pending.versionName, sha256 = pending.sha256, pending = null,
                            failedVersion = null, state = StoredState.On, label = outcome.instance.tab.label,
                            highestLoaded = maxOf(it.highestLoaded, pending.versionCode), lastUpdate = LastUpdate(pending.versionName, now()),
                        )
                    }
                    persist()
                    withContext(io) { store.deleteVersion(id, record.versionCode) }
                    statuses[id] = PluginStatus.Running(outcome.instance)
                }
                else -> {
                    log.warn("Plugin {} {} couldn't take over from {}", id, pending.versionCode, record.versionCode)
                    update(id) { it.copy(pending = null, failedVersion = FailedVersion(pending.versionCode, pending.versionName, outcome.reason)) }
                    persist()
                    withContext(io) { store.deleteVersion(id, pending.versionCode) }
                    statuses[id] = begin(record(id)!!)
                }
            }
            publish()
            true
        }
    }

    /** Turn on again (L619): a version turned off after a crash or an error loads at once, with no restart. */
    suspend fun turnOnAgain(id: String) {
        filesRead.await()
        lock.withLock {
            val record = record(id)?.takeIf { it.state == StoredState.Crashed || it.state == StoredState.Errored } ?: return@withLock
            crashGuard.turnedOnAgain(id, record.versionCode)
            update(id) { it.copy(state = StoredState.On) }
            persist()
            statuses[id] = if (startChecked) begin(record(id)!!) else PluginStatus.Waiting
            publish()
        }
    }

    /**
     * Removes [id]: its instance let go, its slot freed, its code, data, cache and secrets deleted, and what the crash guard
     * kept of it (an error its close() threw as it went among it), so the same version installed again starts clean. What
     * it played stays.
     */
    suspend fun remove(id: String) {
        filesRead.await()
        lock.withLock {
            record(id) ?: return@withLock
            runningInstance(id)?.let(::letGo)
            // The entry goes first: should the process die before the files do, the next start's sweep takes them.
            records.removeAll { it.id == id }
            statuses.remove(id)
            forTest.remove(id)
            persist()
            crashGuard.removed(id)
            withContext(io) { store.delete(id) }
            publish()
        }
    }

    /**
     * Test seam (L622): puts [plugin], a device test's own, in the lowest free slot as an installed version that loaded,
     * with a host of its own, in memory alone. A debug build's alone: anywhere else it does nothing. Only device tests call
     * it.
     */
    @VisibleForTesting
    suspend fun installForTest(plugin: NzPlugin, id: String) {
        if (!BuildConfig.DEBUG) return
        require(PluginIds.valid(id)) { "Not a plugin's id" }
        filesRead.await()
        lock.withLock {
            check(record(id) == null && records.size < MAX_INSTALLED) { "No slot for a test's plugin" }
            forTest[id] = plugin
            withContext(io) { store.emptyFolders(id) }
            val slot = PluginStore.SLOTS.first { slot -> records.none { it.slot == slot } }
            records += PluginRecord(id, id, versionCode = 1, versionName = "1", sha256 = "", slot = slot, installedAt = now())
            statuses[id] = begin(record(id)!!)
            publish()
        }
    }

    /** A version's code as its kept state allows: off until Turn on again, or loaded. Under [lock]. */
    private suspend fun begin(record: PluginRecord): PluginStatus = when {
        off.value && record.id !in forTest -> PluginStatus.PluginsOff
        else -> beginOwn(record)
    }

    private suspend fun beginOwn(record: PluginRecord): PluginStatus = when (record.state) {
        StoredState.Crashed -> PluginStatus.OffAfterCrash
        StoredState.Errored -> PluginStatus.OffAfterError
        StoredState.Withdrawn -> PluginStatus.Withdrawn
        StoredState.On, StoredState.Refused -> run(record)
    }

    /** Loads [record]'s version and keeps what it found: the label, the highest loaded, or why it didn't. Under [lock]. */
    private suspend fun run(record: PluginRecord): PluginStatus = when (val outcome = load(record.id, record.versionCode, record.sha256, record.highestLoaded)) {
        is Outcome.Running -> {
            keep(record.id) {
                it.copy(state = StoredState.On, label = outcome.instance.tab.label, highestLoaded = maxOf(it.highestLoaded, it.versionCode))
            }
            PluginStatus.Running(outcome.instance)
        }
        is Outcome.Refused -> {
            keep(record.id) { it.copy(state = StoredState.Refused) }
            PluginStatus.Refused(outcome.refusal)
        }
        is Outcome.Failed -> {
            keep(record.id) { it.copy(state = StoredState.On) }
            PluginStatus.CouldntStart(outcome.failure)
        }
        Outcome.Errored -> {
            keep(record.id) { it.copy(state = StoredState.Errored) }
            PluginStatus.OffAfterError
        }
    }

    /**
     * [id]'s record as [change] leaves it, written only where that changed it: a start that finds nothing new writes no
     * registry, so a power cut just after a start finds the file the last change wrote. Under [lock].
     */
    private suspend fun keep(id: String, change: (PluginRecord) -> PluginRecord) {
        val before = record(id)
        update(id, change)
        if (record(id) != before) persist()
    }

    /**
     * [id]'s version [versionCode], checked, loaded and its instance made, off the main thread, inside the crash guard's
     * load marker: a death between its class load and its tab()'s answer counts against it at the next start. The marker
     * ends only once the load came back: what still escapes it (the process's own error) leaves it, as a death would.
     * Under [lock].
     */
    private suspend fun load(id: String, versionCode: Long, sha256: String, highestLoaded: Long): Outcome = withContext(io) {
        val version = PluginVersion(id, versionCode, keys.incrementAndGet())
        forTest[id]?.let { return@withContext instantiate(version, it) }
        crashGuard.loading(id, versionCode)
        val outcome = when (val result = loadCode(id, versionCode, sha256, highestLoaded)) {
            is LoadResult.Refused -> Outcome.Refused(result.refusal).also { log.warn("Plugin {} refused: {}", version, result.refusal) }
            is LoadResult.Failed -> Outcome.Failed(result.failure).also { log.warn("Plugin {} couldn't start: {}", version, result.failure) }
            is LoadResult.Loaded -> instantiate(version, result.plugin)
        }
        crashGuard.loadDone(id, versionCode, running = outcome is Outcome.Running)
        outcome
    }

    /** [plugin]'s instance as [version]: its host, and the tab it gives, guarded. */
    private fun instantiate(version: PluginVersion, plugin: NzPlugin): Outcome {
        val host = hosts.create(version) { e -> failed(version, "its scope", e) }
        val tab = calls.call(version, "tab", nothing = null) { plugin.tab() }
        if (tab == null) {
            letGo(version, plugin, host)
            return Outcome.Errored
        }
        return Outcome.Running(PluginInstance(version, plugin, host, tab))
    }

    // Whatever the plugin's code throws as it loads is a reason, an Error that got past the loader among it.
    private fun loadCode(id: String, versionCode: Long, sha256: String, highestLoaded: Long): LoadResult = try {
        loading.load(store.apk(id, versionCode), Expectation(id, sha256, versionCode, highestLoaded, stored = true))
    } catch (e: Throwable) {
        if (!e.isPluginsOwn()) throw e
        LoadResult.Failed(LoadFailure.Other)
    }

    /** Lets [instance] go: its scope cancelled first, then its close(), guarded (L635). */
    private fun letGo(instance: PluginInstance) = letGo(instance.version, instance.plugin, instance.host)

    private fun letGo(version: PluginVersion, plugin: NzPlugin, host: HostedPlugin) {
        host.cancel()
        calls.call(version, "close", nothing = Unit) { plugin.close() }
        // Only now: a crash while its close() runs is still its version's.
        crashGuard.letGo(version.id, version.versionCode)
    }

    /**
     * Code of [version]'s threw outside composition, or escaped its scope (L619): logged by its id, version and the
     * exception's kind, recorded with the crash guard at once (the process may not live to save the registry), and that
     * version turned off "after an error".
     */
    private fun failed(version: PluginVersion, what: String, e: Throwable) {
        log.warn("Plugin {} turned off after an error in {}: {}", version, what, e.javaClass.name)
        try {
            crashGuard.recordError(version.id, version.versionCode)
        } catch (e: Exception) {
            log.warn("Plugin {}'s error not recorded: {}", version, e.javaClass.simpleName)
        }
        scope.launch(io) { lock.withLock { offAfterError(version) } }
    }

    /** [version] off after an error, its instance let go, if it is still the one installed. Under [lock]. */
    private suspend fun offAfterError(version: PluginVersion) {
        val record = record(version.id)?.takeIf { it.versionCode == version.versionCode } ?: return
        if (record.state != StoredState.Errored) {
            update(version.id) { it.copy(state = StoredState.Errored) }
            persist()
        }
        runningInstance(version.id)?.takeIf { it.versionCode == version.versionCode }?.let(::letGo)
        statuses[version.id] = PluginStatus.OffAfterError
        publish()
    }

    private suspend fun readFiles() {
        records += withContext(io) { store.readRegistry() }.plugins
        try {
            withContext(io) { store.sweep(records.associate { record -> record.id to setOfNotNull(record.versionCode, record.pending?.versionCode) }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Plugins: what no plugin owns wasn't swept away: {}", e.javaClass.simpleName)
        }
        publish()
    }

    private fun record(id: String): PluginRecord? = records.firstOrNull { it.id == id }

    private fun runningInstance(id: String): PluginInstance? = (statuses[id] as? PluginStatus.Running)?.instance

    private fun update(id: String, change: (PluginRecord) -> PluginRecord) {
        val i = records.indexOfFirst { it.id == id }
        if (i >= 0) records[i] = change(records[i])
    }

    /**
     * The registry's file, whole; a test's plugins never in it. A write that fails is logged: the registry goes on in
     * memory. True where it was written.
     */
    private suspend fun persist(): Boolean = try {
        withContext(io) { store.writeRegistry(RegistryFile(records.filter { it.id !in forTest })) }
        true
    } catch (e: IOException) {
        log.warn("Plugins: the registry wasn't written: {}", e.javaClass.simpleName)
        false
    }

    private fun publish() {
        shown.value = records.sortedBy { it.slot }.map { record ->
            InstalledPlugin(
                record.id, record.name, record.slot, record.versionCode, record.versionName, record.label,
                statuses[record.id] ?: PluginStatus.Waiting, record.pending != null, record.failedVersion, record.lastUpdate,
            )
        }
    }

    /** What loading a version came to. */
    private sealed interface Outcome {
        val reason: String

        class Running(val instance: PluginInstance) : Outcome {
            override val reason get() = ""
        }

        class Refused(val refusal: Refusal) : Outcome {
            override val reason get() = refusal.reason
        }

        class Failed(val failure: LoadFailure) : Outcome {
            override val reason get() = failure.reason
        }

        /** Its tab() threw: the version is off after an error. */
        data object Errored : Outcome {
            override val reason get() = LoadFailure.Other.reason
        }
    }

    companion object {
        /** At most two plugins installed at once (L618); the graph's four slots let a later raise change nothing there. */
        const val MAX_INSTALLED = 2

        private val log = LoggerFactory.getLogger(PluginRegistry::class.java)

        /**
         * The app's registry: the plugins' files under its own storage, Android's reading of their archives, its secrets,
         * and the crash guard's files beside the plugins'.
         */
        fun of(context: Context, secrets: NamedSecrets, scope: CoroutineScope): PluginRegistry {
            val store = PluginStore(context.filesDir, context.cacheDir, secrets)
            val loader = PluginLoader(PluginVerifier(AndroidArchiveReader(context.packageManager)))
            return PluginRegistry(store, loader, PluginHosts(store, secrets), PluginCrashGuard(store.root), scope)
        }
    }
}

/** A plugin to install: its [id], the list's [name] for it, the [versionCode] the list gives and its file's [sha256]. */
class InstallRequest(val id: String, val name: String, val versionCode: Long, val sha256: String)

sealed interface InstallResult {
    /** Installed in [slot], and loaded once the first screen asked. */
    class Installed(val slot: Int) : InstallResult

    /** An update stored beside the running version: it takes over at the swap point. */
    data object UpdateWaiting : InstallResult

    class Refused(val refusal: Refusal) : InstallResult

    /** Two plugins are installed already (L618). */
    data object NoRoom : InstallResult

    /** That version already couldn't start, and isn't tried again (L581). */
    data object NotAgain : InstallResult

    /** It couldn't be stored (a full disk): nothing of it is kept. */
    data object Failed : InstallResult
}

/**
 * An installed plugin, as the app shows it: the tab's [label] as the plugin last gave it (null until it first loaded),
 * its [status] in this process, an update waiting for the swap ([updateWaiting]), the last update that couldn't start
 * ([failedUpdate]) and the last that did ([lastUpdate]).
 */
class InstalledPlugin(
    val id: String,
    val name: String,
    val slot: Int,
    val versionCode: Long,
    val versionName: String,
    val label: String?,
    val status: PluginStatus,
    val updateWaiting: Boolean,
    val failedUpdate: FailedVersion?,
    val lastUpdate: LastUpdate?,
)

sealed interface PluginStatus {
    /** Its files read, its code not loaded in this process yet: before the first screen asks, or while it loads. */
    data object Waiting : PluginStatus

    class Running(val instance: PluginInstance) : PluginStatus

    /** Off after a crash it caused, until Turn on again or another version (L619). */
    data object OffAfterCrash : PluginStatus

    /** Off after an error its code threw outside composition, or that escaped its scope (L619). */
    data object OffAfterError : PluginStatus

    /** Its version was withdrawn from the list: off until a fixed version is out (L621). */
    data object Withdrawn : PluginStatus

    /** Its file didn't pass the checks at this start. */
    class Refused(val refusal: Refusal) : PluginStatus

    /** Its file passed, but its code didn't load. */
    class CouldntStart(val failure: LoadFailure) : PluginStatus

    /** No plugin loads: NZStreamer closed twice just after starting (L619), until Turn on again. */
    data object PluginsOff : PluginStatus
}

/** A loaded instance: the plugin's entry, its own host and the [tab] it gave as it loaded. */
class PluginInstance internal constructor(val version: PluginVersion, val plugin: NzPlugin, val host: HostedPlugin, val tab: PluginTab) {
    val id: String get() = version.id
    val versionCode: Long get() = version.versionCode
    val key: Long get() = version.key
}

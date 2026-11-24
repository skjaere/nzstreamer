package io.skjaere.usenet.app.plugins

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** The crash guard as the registry tells it what happens to plugins' code, and asks it what the last start left. */
interface PluginGuard {
    /** An uncaught throwable is about to end the process: on the crashing thread, before the crash log's record. */
    fun blame(t: Throwable)

    /** [id]'s version [versionCode] failed with an error: recorded before this returns, so it is off at the next start. */
    fun recordError(id: String, versionCode: Long)

    /** A version's classes are about to load: the load marker. From here on a crash with its frames blames it. */
    fun loading(id: String, versionCode: Long)

    /** Its load came back: [running] (its instance made and its tab() answered), or not. Not a death either way. */
    fun loadDone(id: String, versionCode: Long, running: Boolean)

    /** A version's instance was let go, its close() returned: a crash no longer blames it. */
    fun letGo(id: String, versionCode: Long)

    /** At a start, before any plugin loads: what the last start left, for the versions this one is to load ([toLoad]). */
    fun startCheck(toLoad: List<Pair<String, Long>>): StartCheck

    /** The registry kept what [startCheck] found: its records go. */
    fun recordsApplied()

    /** This start lasted [PluginCrashGuard.EARLY_MS]: it didn't crash early. */
    fun lasted()

    /** Turn on again, after plugins were turned off by two early crashes. */
    fun turnOnPlugins()

    /** Turn on again for one version: what this process recorded against it goes. */
    fun turnedOnAgain(id: String, versionCode: Long)
}

/**
 * What the last start left: plugins off after two early crashes in a row ([pluginsOff]), the versions to load that a
 * crash blamed or a second death in their load turned off ([crashed]), and those an error turned off as the process went
 * ([errored]).
 */
class StartCheck(val pluginsOff: Boolean, val crashed: Set<Pair<String, Long>>, val errored: Set<Pair<String, Long>>)

/**
 * The crash guard (L585, L619). Compose can't catch what a composable throws, so a plugin's crash ends NZStreamer; this
 * makes sure it is the plugin that stays off, not NZStreamer, at the next start. Its files are in [folder]
 * (filesDir/plugins), each written whole then renamed, and read tolerantly: one that doesn't read is no record, and none
 * of them ever throws at a start.
 * - crash.json: on the crashing thread, before the crash log, every version loaded or loading whose package
 *   (app.nzstreamer.plugin.<id>.) is in the throwable's frames, its causes' and its suppressed ones' (each once, at most
 *   64 deep), with when; and, for a process that loaded plugins, the guard's own crash time. An error a plugin's code threw
 *   outside composition is recorded there too.
 * - loading: a version about to load, deleted once its load came back. Found at a start, it counts one death during
 *   that version's load (a native crash names no frame, and so does a kill); a second in a row turns it off as a crash
 *   would. One alone is tried again.
 * - starts.json: when the last start that loaded plugins began, how many in a row crashed within [EARLY_MS] of it (two
 *   turn the plugins off until Turn on again), and the loads' death counts.
 * Not covered: a plugin that blocks the main thread (an ANR names no frame), and a crash in the app's code that a plugin's
 * bad call caused, unless twice just after a start.
 */
class PluginCrashGuard(private val folder: File, private val now: () -> Long = System::currentTimeMillis) : PluginGuard {
    private val crashFile = File(folder, CRASH)
    private val markerFile = File(folder, LOADING)
    private val startsFile = File(folder, STARTS)

    /** The versions whose code runs or loads in this process, by id: what a crash's frames are blamed by. */
    private val running = ConcurrentHashMap<String, Long>()

    /** Whether this process is a start that loads plugins: only its crashes are timed for the safe start. */
    @Volatile
    private var started = false

    /** The files' lock: a crash, an error and the registry may write at once. */
    private val files = Any()

    override fun blame(t: Throwable) {
        val prefixes = running.entries.associate { (id, versionCode) -> PluginIds.packageOf(id) + "." to (id to versionCode) }
        val blamed = LinkedHashSet<Pair<String, Long>>()
        if (prefixes.isNotEmpty()) {
            walk(t) { frame -> prefixes.entries.firstOrNull { frame.className.startsWith(it.key) }?.let { blamed += it.value } }
        }
        if (blamed.isEmpty() && !started) return
        val at = now()
        synchronized(files) {
            val earlier = read(crashFile, CrashRecord.serializer()) ?: CrashRecord()
            val record = CrashRecord(if (started) at else earlier.crashedAt, earlier.blamed + blamed.map { (id, version) -> Blamed(id, version, at) })
            write(crashFile, CrashRecord.serializer(), record)
        }
    }

    override fun recordError(id: String, versionCode: Long) {
        val at = now()
        synchronized(files) {
            val earlier = read(crashFile, CrashRecord.serializer()) ?: CrashRecord()
            write(crashFile, CrashRecord.serializer(), earlier.copy(blamed = earlier.blamed + Blamed(id, versionCode, at, error = true)))
        }
    }

    override fun loading(id: String, versionCode: Long) {
        running[id] = versionCode
        synchronized(files) { write(markerFile, Marker.serializer(), Marker(id, versionCode)) }
    }

    override fun loadDone(id: String, versionCode: Long, running: Boolean) {
        if (!running) this.running.remove(id, versionCode)
        synchronized(files) {
            markerFile.delete()
            val starts = readStarts()
            if (starts.markers.any { it.id == id }) write(startsFile, StartsRecord.serializer(), starts.copy(markers = starts.markers.filter { it.id != id }))
        }
    }

    override fun letGo(id: String, versionCode: Long) {
        running.remove(id, versionCode)
    }

    override fun startCheck(toLoad: List<Pair<String, Long>>): StartCheck = synchronized(files) {
        // A device with no plugin, now or before, gets none of the guard's files.
        if (toLoad.isEmpty() && listOf(startsFile, crashFile, markerFile).none { it.exists() }) {
            started = false
            return@synchronized StartCheck(pluginsOff = false, crashed = emptySet(), errored = emptySet())
        }
        val starts = readStarts()
        val crash = read(crashFile, CrashRecord.serializer())
        val crashed = crash?.blamed.orEmpty().filter { !it.error }.mapTo(mutableSetOf()) { it.id to it.versionCode }
        val errored = crash?.blamed.orEmpty().filter { it.error }.mapTo(mutableSetOf()) { it.id to it.versionCode }
        // A marker that doesn't read counts for every version this start is to load.
        val died = when {
            !markerFile.exists() -> emptyList()
            else -> read(markerFile, Marker.serializer())?.let { listOf(it.id to it.versionCode) } ?: toLoad
        }
        markerFile.delete()
        val counts = starts.markers.associateBy { it.id }.toMutableMap()
        died.forEach { (id, version) ->
            val count = (counts[id]?.takeIf { it.versionCode == version }?.count ?: 0) + 1
            if (count >= 2) {
                crashed += id to version
                counts.remove(id)
            } else {
                counts[id] = MarkerCount(id, version, count)
            }
        }
        val lastStart = starts.lastStart
        val crashedAt = crash?.crashedAt
        val early = crashedAt != null && lastStart != null && crashedAt in lastStart..lastStart + EARLY_MS
        val earlyCrashes = if (early) starts.earlyCrashes + 1 else starts.earlyCrashes
        val off = starts.off || earlyCrashes >= 2
        // Only a start that loads a plugin times its crashes: a crash with none loaded is no plugin's.
        started = !off && toLoad.isNotEmpty()
        // Every start's time, a start that loads none too, so a crash time is never counted twice.
        write(startsFile, StartsRecord.serializer(), StartsRecord(now(), earlyCrashes, off, counts.values.toList()))
        if (off && !starts.off) log.warn("Plugins are off: NZStreamer closed twice just after starting")
        StartCheck(off, crashed.filterTo(mutableSetOf()) { it in toLoad }, errored.filterTo(mutableSetOf()) { it in toLoad })
    }

    override fun recordsApplied() {
        synchronized(files) { crashFile.delete() }
    }

    override fun lasted() {
        synchronized(files) {
            val starts = readStarts()
            if (starts.earlyCrashes != 0) write(startsFile, StartsRecord.serializer(), starts.copy(earlyCrashes = 0))
        }
    }

    override fun turnOnPlugins() {
        synchronized(files) { write(startsFile, StartsRecord.serializer(), readStarts().copy(earlyCrashes = 0, off = false)) }
        started = true
    }

    override fun turnedOnAgain(id: String, versionCode: Long) {
        synchronized(files) {
            val crash = read(crashFile, CrashRecord.serializer()) ?: return@synchronized
            val kept = crash.blamed.filter { it.id != id || it.versionCode != versionCode }
            if (kept.size != crash.blamed.size) write(crashFile, CrashRecord.serializer(), crash.copy(blamed = kept))
        }
    }

    private fun readStarts(): StartsRecord = read(startsFile, StartsRecord.serializer()) ?: StartsRecord()

    private fun <T> read(file: File, serializer: KSerializer<T>): T? = try {
        file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(serializer, it) }
    } catch (e: Exception) {
        log.warn("Plugins: the crash guard's {} didn't read: {}", file.name, e.javaClass.simpleName)
        null
    }

    /** [value] to [file] whole, through a file renamed over it. A write that fails is logged: the guard never throws. */
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        try {
            if (!folder.isDirectory) folder.mkdirs()
            val tmp = File(folder, file.name + TMP)
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                log.warn("Plugins: the crash guard's {} wasn't replaced", file.name)
            }
        } catch (e: Exception) {
            log.warn("Plugins: the crash guard's {} wasn't written: {}", file.name, e.javaClass.simpleName)
        }
    }

    companion object {
        /** A crash this soon after a start that loaded plugins counts as an early one; a start that lasts this long clears them. */
        const val EARLY_MS = 30_000L

        /** How deep the walk of a throwable's causes and suppressed ones goes, as printStackTrace guards a cycle. */
        const val MAX_DEPTH = 64

        private const val CRASH = "crash.json"
        private const val LOADING = "loading"
        private const val STARTS = "starts.json"
        private const val TMP = ".tmp"

        private val json = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(PluginCrashGuard::class.java)

        /**
         * Every frame of [t], its causes and its suppressed throwables, each throwable once (a cause cycle ends the walk),
         * nearest first, at most [MAX_DEPTH] deep. A throwable whose getters throw is passed over.
         */
        internal fun walk(t: Throwable, frame: (StackTraceElement) -> Unit) {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val next = ArrayDeque<Pair<Throwable, Int>>().apply { add(t to 0) }
            while (next.isNotEmpty()) {
                val (e, depth) = next.removeFirst()
                if (depth >= MAX_DEPTH || !seen.add(e)) continue
                try {
                    e.stackTrace.forEach(frame)
                    e.cause?.let { next.add(it to depth + 1) }
                    e.suppressed.forEach { next.add(it to depth + 1) }
                } catch (_: Exception) {
                    // A plugin's throwable whose getters throw: what was read of it stands, and the rest is walked.
                }
            }
        }
    }
}

/** crash.json: the guard's own time of the last crash of a start that loaded plugins, and the versions blamed since. */
@Serializable
private data class CrashRecord(val crashedAt: Long? = null, val blamed: List<Blamed> = emptyList())

/** A version a crash blamed, or that an [error] turned off, and when. */
@Serializable
private class Blamed(val id: String, val versionCode: Long, val at: Long, val error: Boolean = false)

/** loading: the version whose classes were loading. */
@Serializable
private class Marker(val id: String, val versionCode: Long)

/** starts.json: the last start that was to load plugins, the early crashes in a row, whether plugins are off, the deaths in loads. */
@Serializable
private data class StartsRecord(
    val lastStart: Long? = null,
    val earlyCrashes: Int = 0,
    val off: Boolean = false,
    val markers: List<MarkerCount> = emptyList(),
)

/** How many starts in a row died while [id]'s version [versionCode] loaded. */
@Serializable
private class MarkerCount(val id: String, val versionCode: Long, val count: Int)

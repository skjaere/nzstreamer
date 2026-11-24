package io.skjaere.usenet.app.plugins

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.ReleaseHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.IdentityHashMap

/** The skeleton plugin's id, as its builds carry it (L623). */
const val SKELETON = "skeleton"

/** Its entry class, made by its own loader. */
const val SKELETON_ENTRY = "app.nzstreamer.plugin.skeleton.SkeletonPlugin"

/** A class of the skeleton's that nothing calls until a play picks a file: the device classes load it late. */
const val SKELETON_PICKER = "app.nzstreamer.plugin.skeleton.LargestVideo"

/** The skeleton's page that throws as it is first drawn, and what it throws. */
const val SKELETON_CRASH_PAGE = "crash"
const val SKELETON_CRASH = "The skeleton's crash page"

/** How long a registry may take to read its files or load a plugin before a device class gives up on it. */
const val PLUGIN_WAIT_MS = 20_000L

/**
 * The skeleton plugin's four debug builds in the test APK's assets (L623), [versionCode] as each was built: v1, v2 and
 * newer signed as the app under test is, foreign (v2's code) with a throwaway key of the skeleton's build, newer stamped
 * with the next plugin API minor.
 */
enum class SkeletonBuild(private val asset: String, val versionCode: Long) {
    V1("skeleton-v1.apk", 1),
    V2("skeleton-v2.apk", 2),
    Foreign("skeleton-foreign.apk", 2),
    Newer("skeleton-newer.apk", 3),
    ;

    /** The build as a download leaves it in cacheDir/plugins/.download: a writable file nothing has checked yet. */
    fun download(context: Context): File {
        val file = File(File(File(context.cacheDir, PluginStore.DIR), DOWNLOADS), asset)
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** The build's file as the test APK's assets hold it. */
    fun bytes(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }

    /** What the plugin list would say of this build: the skeleton's name, this version and its file's [sha256]. */
    fun request(sha256: String) = InstallRequest(SKELETON, "Skeleton", versionCode, sha256)

    private companion object {
        const val DOWNLOADS = ".download"
    }
}

/**
 * A registry over the app's own plugin files, made as PluginRegistry.of makes the app's (Android's reading of the archive,
 * the pinned signer and the test signer, the app's secrets, the crash guard beside the files), with its [loader] in view:
 * what it installs, loads and swaps, it does as the app would. Each stands for one process's: its files read and its
 * plugins loaded at [start], and [close] for that process's end. [PluginStateRule.registry] makes them, and takes away
 * what they installed.
 */
class DeviceRegistry internal constructor(context: Context, secrets: NamedSecrets) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = PluginStore(context.filesDir, context.cacheDir, secrets)
    val loader = PluginLoader(PluginVerifier(AndroidArchiveReader(context.packageManager)))
    val registry = PluginRegistry(store, loader, PluginHosts(store, secrets), PluginCrashGuard(store.root), scope)

    /**
     * As a process starts: the files read (UsenetApplication), then, where [asked], the plugins loaded as MainActivity's
     * first creation asks ([firstScreen]). Unasked, an install stores its plugin and loads nothing until then.
     */
    fun start(asked: Boolean = true) {
        registry.start()
        if (asked) firstScreen()
    }

    /**
     * MainActivity's first creation asks: the crash guard's check of the last start, then each plugin installed so far
     * loads. Asked after an install, it runs in that order, as a start that finds the plugin stored does; asked before,
     * its check and the install would take the registry's lock in either order.
     */
    fun firstScreen() = registry.firstActivity()

    /** Installs [download] as [build], with the SHA-256 the list would give ([sha256], the file's own unless a test says). */
    suspend fun install(build: SkeletonBuild, download: File, sha256: String = Sha256.of(download)): InstallResult =
        registry.install(download, build.request(sha256))

    suspend fun await(id: String, what: String, holds: (InstalledPlugin) -> Boolean): InstalledPlugin = registry.await(id, what, holds)

    /** [id]'s running instance, as the registry lists it now. */
    fun running(id: String): PluginInstance = (listed(id).status as? PluginStatus.Running)?.instance
        ?: throw AssertionError("$id isn't running: the registry lists ${describe(registry.plugins.value)}")

    fun listed(id: String): InstalledPlugin = registry.plugins.value?.firstOrNull { it.id == id }
        ?: throw AssertionError("$id isn't listed: the registry lists ${describe(registry.plugins.value)}")

    /** The end of this registry's process: nothing it launched (the guard's 30-second timer among it) runs on. */
    fun close() {
        scope.cancel()
    }
}

/** [id] as this registry lists it once [what] holds, within [PLUGIN_WAIT_MS]; else the test fails, naming what it lists. */
suspend fun PluginRegistry.await(id: String, what: String, holds: (InstalledPlugin) -> Boolean): InstalledPlugin =
    withTimeoutOrNull(PLUGIN_WAIT_MS) { plugins.first { listed -> listed?.firstOrNull { it.id == id }?.let(holds) == true } }
        ?.first { it.id == id }
        ?: throw AssertionError("$id isn't $what: the registry lists ${describe(plugins.value)}")

/** The registry's list for a failure's message: each plugin's id, version and state, a refusal or failure by its kind. */
fun describe(listed: List<InstalledPlugin>?): String = listed?.joinToString(prefix = "[", postfix = "]") { plugin ->
    val status = when (val status = plugin.status) {
        is PluginStatus.Running -> "Running"
        is PluginStatus.Refused -> "Refused(${status.refusal})"
        is PluginStatus.CouldntStart -> "CouldntStart(${status.failure})"
        else -> status.toString()
    }
    "${plugin.id} ${plugin.versionCode} $status"
} ?: "nothing yet (its files aren't read)"

/** Flips one bit of the byte half-way through [file], which must be writable: the bytes the list's SHA-256 no longer names. */
fun changeAByte(file: File) {
    RandomAccessFile(file, "rw").use { bytes ->
        val at = bytes.length() / 2
        bytes.seek(at)
        val was = bytes.read()
        bytes.seek(at)
        bytes.write(was xor 1)
    }
}

/** A version the crash guard's crash.json names, and whether an [error] named it rather than a crash. */
data class Named(val id: String, val versionCode: Long, val error: Boolean)

/** What crash.json in [folder] (filesDir/plugins) names, in its order; null where there is no such file. */
fun crashJson(folder: File): List<Named>? {
    val file = File(folder, "crash.json").takeIf { it.isFile } ?: return null
    return Json.parseToJsonElement(file.readText()).jsonObject["blamed"]?.jsonArray.orEmpty().map { entry ->
        val named = entry.jsonObject
        Named(named.getValue("id").jsonPrimitive.content, named.getValue("versionCode").jsonPrimitive.long, named["error"]?.jsonPrimitive?.boolean ?: false)
    }
}

/** [t], its causes and its suppressed throwables, each once. */
fun everyThrowable(t: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val next = ArrayDeque(listOf(t))
    while (next.isNotEmpty()) {
        val e = next.removeFirst()
        if (!seen.add(e)) continue
        e.cause?.let(next::add)
        next.addAll(e.suppressed)
    }
    return seen.toList()
}

/** A plugin page's ways out, going nowhere: these classes draw one page alone. */
object NoNav : PluginNav {
    override fun open(page: String, arg: String?) = Unit

    override fun back() = Unit

    override fun openSettings() = Unit

    override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) = Unit

    override fun openSearch(words: String, note: String?) = Unit
}

/**
 * The plugins' measurements, through the instrumentation's status (as PairRecord says its lines), never logcat: code 2,
 * an instrumentation's "in progress", which result parsers pass over, so a line never counts as a test.
 */
object PluginMeasure {
    private const val IN_PROGRESS = 2

    fun say(key: String, line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(IN_PROGRESS, Bundle().apply { putString(key, line.replace('\n', ' ')) })
    }
}

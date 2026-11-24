package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.data.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The plugins' files in NZStreamer's own storage (L616, W155): Android never lists a plugin as an app.
 * - Code: filesDir/plugins/<id>/code/<versionCode>/plugin.apk, one folder per version, so what ART compiles beside it goes
 *   with it. Written through plugin.apk.tmp, checked against its SHA-256 again and made read-only before it is renamed.
 * - The plugin's own data in filesDir/plugins/<id>/data/, its cache in cacheDir/plugins/<id>/: emptied before an install
 *   hands them over, deleted with the plugin.
 * - The registry, filesDir/plugins/registry.json, written whole and synced, then renamed; one that doesn't read is an
 *   empty registry.
 * - Its secrets, plugin:<id>:<name> in the app's one secret store (L617).
 * Nothing of an id the registry doesn't name outlives it: each start sweeps it all away ([sweep]). The folder is left out
 * of backups and device transfer: code reaches a device only through the plugin list and its checks (L596).
 */
class PluginStore(filesDir: File, cacheDir: File, private val secrets: NamedSecrets) {
    /** filesDir/plugins: the plugins' code and data, the registry, the list's copy and the crash guard's files. */
    val root = File(filesDir, DIR)
    private val cacheRoot = File(cacheDir, DIR)
    private val registryFile = File(root, REGISTRY)

    fun apk(id: String, versionCode: Long): File = File(codeDir(id, versionCode), APK)

    fun dataDir(id: String): File = File(File(root, checked(id)), DATA)

    fun cacheDir(id: String): File = File(cacheRoot, checked(id))

    private fun codeDir(id: String, versionCode: Long): File {
        require(versionCode > 0) { "A version's code is above 0" }
        return File(File(File(root, checked(id)), CODE), versionCode.toString())
    }

    /**
     * Stores [source], a file that passed the checks, as [id]'s version [versionCode]: copied to plugin.apk.tmp in the
     * version's folder, its SHA-256 checked again against [sha256], made read-only, then renamed into place, so a death
     * half-way leaves only a .tmp, which the next start deletes. Throws IOException where it can't, leaving nothing.
     */
    fun writeVersion(id: String, versionCode: Long, source: File, sha256: String): File {
        val folder = codeDir(id, versionCode)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't make the version's folder")
        val tmp = File(folder, "$APK$TMP")
        val apk = File(folder, APK)
        try {
            tmp.delete()
            source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (Sha256.of(tmp) != sha256) throw IOException("The stored copy doesn't match its SHA-256")
            if (!tmp.setReadOnly()) throw IOException("Couldn't make the stored copy read-only")
            if (!tmp.renameTo(apk)) throw IOException("Couldn't put the stored copy in place")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        return apk
    }

    /** Empties [id]'s data and cache folders, so nothing of an earlier install of the same id is found in them. */
    fun emptyFolders(id: String) {
        listOf(dataDir(id), cacheDir(id)).forEach { folder ->
            if (folder.exists() && !folder.deleteRecursively()) throw IOException("Couldn't empty a plugin's folder")
            if (!folder.mkdirs()) throw IOException("Couldn't make a plugin's folder")
        }
    }

    /** Deletes [id]'s version [versionCode]: its file and what ART compiled beside it. */
    fun deleteVersion(id: String, versionCode: Long) {
        codeDir(id, versionCode).deleteRecursively()
    }

    /** Deletes everything of [id]: its code, data and cache folders, and its secrets. */
    suspend fun delete(id: String) {
        File(root, checked(id)).deleteRecursively()
        cacheDir(id).deleteRecursively()
        secrets.removeAll("$SECRETS$id:")
    }

    /**
     * At each start, before any plugin loads: deletes everything of an id [kept] doesn't name (its code, data, cache and
     * secrets), each version of a named one that [kept] doesn't keep, and a .tmp a death left behind.
     */
    suspend fun sweep(kept: Map<String, Set<Long>>) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            val versions = kept[folder.name]
            if (versions == null) {
                folder.deleteRecursively()
            } else {
                File(folder, CODE).listFiles().orEmpty().forEach { version ->
                    if (version.name.toLongOrNull() !in versions) version.deleteRecursively() else File(version, "$APK$TMP").delete()
                }
            }
        }
        cacheRoot.listFiles().orEmpty().filter { it.isDirectory && it.name != DOWNLOADS && it.name !in kept }.forEach { it.deleteRecursively() }
        secrets.removeAll(SECRETS) { name -> name.removePrefix(SECRETS).substringBefore(':', "") in kept }
    }

    /**
     * The registry as the file holds it; a file that doesn't read is an empty registry. Records it can't use are left out:
     * an id outside the rule, an id given twice (the first is kept). A slot outside the four, or one an earlier record took,
     * is given the lowest free one; with none free, the record is left out.
     */
    fun readRegistry(): RegistryFile {
        val read = try {
            registryFile.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(RegistryFile.serializer(), it) } ?: RegistryFile()
        } catch (e: Exception) {
            log.warn("plugins: the registry didn't read: {}", e.javaClass.simpleName)
            RegistryFile()
        }
        val usable = read.plugins.filter { PluginIds.valid(it.id) && it.versionCode > 0 }.distinctBy { it.id }
        val taken = mutableSetOf<Int>()
        val placed = usable.map { record -> record.takeIf { it.slot in SLOTS && taken.add(it.slot) } }
        val free = (SLOTS - taken).iterator()
        return RegistryFile(
            usable.mapIndexedNotNull { i, record -> placed[i] ?: if (free.hasNext()) record.copy(slot = free.next()) else null },
        )
    }

    /**
     * Writes [registry] whole, through a file renamed over the old one. The file is synced before the rename: one renamed
     * before its bytes reach the disk can be found empty after a power cut, which reads as no plugin, and the next start's
     * sweep would delete every plugin's files and secrets.
     */
    fun writeRegistry(registry: RegistryFile) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Couldn't make the plugins' folder")
        val tmp = File(root, "$REGISTRY$TMP")
        writeSynced(tmp, json.encodeToString(RegistryFile.serializer(), registry))
        if (!tmp.renameTo(registryFile)) {
            tmp.delete()
            throw IOException("Couldn't write the plugins' registry")
        }
    }

    private fun checked(id: String): String = id.also { require(PluginIds.valid(it)) { "Not a plugin's id" } }

    companion object {
        /** The folder under filesDir and cacheDir; backups and transfers leave it out by this path (L596). */
        const val DIR = "plugins"

        /** The slots plugin tabs are bound to (L582). */
        val SLOTS = 1..4

        private const val CODE = "code"
        private const val DATA = "data"
        private const val APK = "plugin.apk"
        private const val TMP = ".tmp"
        private const val REGISTRY = "registry.json"

        /** A download on its way, under cacheDir/plugins: not a plugin's folder. */
        private const val DOWNLOADS = ".download"

        /** Every plugin's secrets' names start with it, then the plugin's id and ":" (L617). */
        const val SECRETS = "plugin:"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
        private val log = LoggerFactory.getLogger(PluginStore::class.java)
    }
}

/** Writes [text] to [file] and syncs it to the disk before this returns, so a rename after it can't publish an empty file. */
internal fun writeSynced(file: File, text: String) {
    FileOutputStream(file).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
}

/** The app's one secret store, by a secret's full name, as the plugins' secrets need it (L617). */
interface NamedSecrets {
    suspend fun get(name: String): String?

    suspend fun put(name: String, value: String?)

    fun has(name: String): Flow<Boolean>

    /** Removes every secret whose name starts with [prefix], but the names [keep] keeps. */
    suspend fun removeAll(prefix: String, keep: (String) -> Boolean = { false })
}

fun SecretStore.named(): NamedSecrets = object : NamedSecrets {
    override suspend fun get(name: String): String? = this@named.get(name)

    override suspend fun put(name: String, value: String?) = this@named.put(name, value)

    override fun has(name: String): Flow<Boolean> = this@named.has(name)

    override suspend fun removeAll(prefix: String, keep: (String) -> Boolean) = this@named.removeAll(prefix, keep)
}

/** registry.json: the installed plugins. Read with keys it doesn't know left aside, so a later build's fields don't stop it. */
@Serializable
class RegistryFile(val plugins: List<PluginRecord> = emptyList())

/**
 * One installed plugin, as registry.json keeps it: its [id], the list's [name] for it, the version installed and the
 * SHA-256 recorded at install, the [slot] its tab is bound to (L582), the tab's [label] as the plugin last gave it, its
 * [state], the [highestLoaded] of its versions that ever loaded (a version never goes down, L583), its [lastUpdate], when
 * it was installed, an update stored beside it that waits for the swap ([pending], L581) and the last update that
 * couldn't start ([failedVersion]), which isn't tried again.
 */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val slot: Int,
    val installedAt: Long,
    val label: String? = null,
    val state: StoredState = StoredState.On,
    val highestLoaded: Long = 0,
    val lastUpdate: LastUpdate? = null,
    val pending: PendingVersion? = null,
    val failedVersion: FailedVersion? = null,
)

/**
 * A plugin's kept state: on, or off until Turn on again (after a crash, after an error), or until a fixed version comes
 * (withdrawn). Refused is what the last start's checks found; each start checks the file again.
 */
@Serializable
enum class StoredState { On, Crashed, Errored, Withdrawn, Refused }

/** The last update: its versionName and when it took over (epoch millis). */
@Serializable
data class LastUpdate(val versionName: String, val at: Long)

/** An update stored beside the running version, waiting for its slot to leave the screen. */
@Serializable
data class PendingVersion(val versionCode: Long, val versionName: String, val sha256: String)

/** An update that couldn't start, and the Plugins page's reason why: the version before it ran on. */
@Serializable
data class FailedVersion(val versionCode: Long, val versionName: String, val reason: String)

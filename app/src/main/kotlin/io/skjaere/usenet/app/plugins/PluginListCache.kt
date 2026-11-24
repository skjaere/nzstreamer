package io.skjaere.usenet.app.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * The plugin list's copy, filesDir/plugins/list.json (L616): the list as GitHub last gave it, so the Plugins page shows it
 * offline, and the times its reads are timed by. Written whole and synced, then renamed; one that doesn't read is no copy,
 * so the next trigger reads the list again.
 */
class PluginListCache(private val file: File) {
    fun read(): ListCopy = try {
        file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListCopy.serializer(), it) } ?: ListCopy()
    } catch (e: Exception) {
        log.warn("plugins: the list's copy didn't read: {}", e.javaClass.simpleName)
        ListCopy()
    }

    /** Writes [copy] whole, through a file renamed over the old one; throws IOException where it can't. */
    fun write(copy: ListCopy) {
        val folder = file.parentFile ?: throw IOException("The list's copy has no folder")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't make the plugins' folder")
        val tmp = File(folder, "${file.name}.tmp")
        writeSynced(tmp, json.encodeToString(ListCopy.serializer(), copy))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Couldn't write the list's copy")
        }
    }

    companion object {
        const val FILE = "list.json"

        /** The app's copy, beside the registry under filesDir/plugins. */
        fun of(filesDir: File) = PluginListCache(File(File(filesDir, PluginStore.DIR), FILE))

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val log = LoggerFactory.getLogger(PluginListCache::class.java)
    }
}

/**
 * The list's copy: its [body] as GitHub last gave it (null before the first read, and after a 404) and its [etag]; when
 * GitHub last answered for it ([readAt]: a list, a 304 or a 404); the [generated] time of the list accepted last, which
 * an older list never replaces; when a read was last tried, worked or not ([attemptedAt]), which the daily read is timed
 * from; and until when GitHub asked that nothing be read ([waitUntil], a day at most). Times are epoch millis.
 */
@Serializable
data class ListCopy(
    val body: String? = null,
    val etag: String? = null,
    val readAt: Long? = null,
    val generated: String? = null,
    val attemptedAt: Long? = null,
    val waitUntil: Long? = null,
)

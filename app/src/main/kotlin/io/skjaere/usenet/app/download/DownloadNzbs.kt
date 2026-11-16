package io.skjaere.usenet.app.download

import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A season pack's NZB, kept once for its season (L394, L432, C1): the season's later rows take it from here rather than
 * grab it again, and the last copy's prepare is kept in memory ([hold]) so they don't prepare it again in this process.
 *
 * Each copy is a folder of [dir] (filesDir's `downloads/.packs/`, out of backups and transfers with the rest of
 * `downloads/`) named by the SHA-256 of its release's key, never by the key: `nzb`, `password` where it has one, and
 * `meta` ([Meta]), each written whole then renamed, `meta` last, so a folder with no readable `meta` is no copy. A row id
 * never starts with ".", so reconcile tells the folder from a download's by its name.
 *
 * A copy is held while any row of its show and season is still to finish, read from the rows at each [sweep]: no count
 * that could drift. A row removed whose Undo can still bring it back counts as one still to finish. The sweep runs at
 * reconcile and at each row's end, and deletes what no row holds, and any folder with no readable `meta` with its `.tmp`
 * files, so a process that died between the `nzb` and the `meta` leaves no NZB or password behind. Writes, reads and
 * sweeps take turns: a sweep never takes a copy half written.
 *
 * The password is never logged; a log line names a copy by its folder's first 8 hex digits.
 */
class DownloadNzbs(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /**
     * What a copy is: its release's [key] and [title], the [candidate] it is in the rows (for a later row to try it from
     * the copy), the [show]'s TMDB id and the [season] it is held for, and whether it [leads]: tried first for the season's
     * later rows, as it isn't above Download size (L431, L432).
     */
    @Serializable
    data class Meta(val key: String, val title: String, val candidate: DownloadCandidate, val show: Int, val season: Int, val leads: Boolean)

    /** A copy as kept: its [meta], and its NZB with its password ([source]). */
    class Copy(val meta: Meta, val source: DownloadStorage.Source) {
        // The release's title and key, and the password, are never in a log line.
        override fun toString() = "Copy(${short(digest(meta.key))}, $source)"
    }

    /** The prepare of copy [key]'s NZB, as Resolve last made it. */
    private class Held(val key: String, val prepared: PreparedNzb)

    private val turns = Mutex()

    @Volatile
    private var held: Held? = null

    /** Copy [key]'s folder: the key's SHA-256, in hex. */
    fun folder(key: String): File = File(dir, digest(key))

    /** Keeps [nzb] and its [password] (none: no file) as the copy [meta] says, in place of one kept before under its key. */
    suspend fun write(meta: Meta, nzb: ByteArray, password: String?): Unit = turns.withLock {
        withContext(io) {
            val folder = folder(meta.key)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't make season pack ${short(folder.name)}'s folder")
            // Kept again: no copy until its meta is back over what is written now.
            File(folder, META).delete()
            writeWhole(File(folder, NZB), nzb)
            val passwordFile = File(folder, PASSWORD)
            if (password != null) writeWhole(passwordFile, password.toByteArray()) else passwordFile.delete()
            writeWhole(File(folder, META), json.encodeToString(Meta.serializer(), meta).toByteArray())
            log.info("Season pack {}: kept for its season, {} bytes", short(folder.name), nzb.size)
        }
    }

    /** The copy kept under [key]; null when there is none, or its meta can't be read. */
    suspend fun find(key: String): Copy? = turns.withLock {
        withContext(io) {
            val folder = folder(key)
            val meta = meta(folder)?.takeIf { it.key == key } ?: return@withContext null
            val nzb = File(folder, NZB).takeIf { it.isFile }?.readBytes() ?: return@withContext null
            Copy(meta, DownloadStorage.Source(nzb, File(folder, PASSWORD).takeIf { it.isFile }?.readText()))
        }
    }

    /**
     * Deletes every copy no row of [rows] holds (one of its show and season still to finish, [DownloadRunner.GOING_ON], or
     * one of [removed], the ids of rows removed whose Undo can still bring them back), and whatever else is in the folder
     * that is no copy, its `.tmp` files with it. The prepare held goes with its copy.
     */
    suspend fun sweep(rows: List<Download>, removed: Set<String> = emptySet()): Unit = turns.withLock {
        withContext(io) {
            val holding = rows.filter { it.state in DownloadRunner.GOING_ON || it.id in removed }
                .mapNotNullTo(HashSet()) { row -> row.showTmdbId?.let { show -> row.season?.let { show to it } } }
            dir.listFiles().orEmpty().forEach { entry ->
                val meta = if (entry.isDirectory) meta(entry) else null
                if (meta != null && (meta.show to meta.season) in holding) return@forEach
                if (entry.deleteRecursively() || !entry.exists()) {
                    log.info("Season pack {}: dropped, {}", short(entry.name), if (meta == null) "no copy" else "no download of its season to finish")
                } else {
                    log.warn("Season pack {}: couldn't be dropped", short(entry.name))
                }
            }
            held?.let { kept -> if (meta(folder(kept.key)) == null && held === kept) held = null }
        }
    }

    /** Keeps [prepared], copy [key]'s NZB as Resolve prepared it, in place of the one kept before, while that copy lasts. */
    fun hold(key: String, prepared: PreparedNzb) {
        held = Held(key, prepared)
    }

    /** The last copy's prepare while its copy lasts; null when there is none, as in a new process. */
    fun held(): PreparedNzb? = held?.prepared

    /** [folder]'s meta; null where it has none or it can't be read. */
    private fun meta(folder: File): Meta? = try {
        File(folder, META).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Meta.serializer(), it) }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // SerializationException, which every malformed or mistyped meta throws, is one.
        null
    }

    private fun writeWhole(file: File, bytes: ByteArray) {
        val tmp = File(file.path + TMP)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
    }

    companion object {
        /** The copies' folder in `downloads/`: a name no download's id can have. */
        const val DIR = ".packs"

        /** The copies' folder under filesDir. */
        const val PATH = "${DownloadStorage.DIR}/$DIR"

        const val NZB = "nzb"
        const val PASSWORD = "password"

        /** What the copy is ([Meta]), written last. */
        const val META = "meta"
        private const val TMP = ".tmp"

        /** As the player's launch reads its own: what a later version adds (DN2b's Import) is passed over. */
        private val json = Json { ignoreUnknownKeys = true }

        private val HEX = Regex("[0-9a-f]{64}")

        private fun digest(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        /** How a log line names a copy's folder [name]: its first 8 hex digits; anything else in the folder by none. */
        private fun short(name: String): String = if (HEX.matches(name)) name.take(8) else "(not a copy)"
    }
}

private val log = LoggerFactory.getLogger(DownloadNzbs::class.java)

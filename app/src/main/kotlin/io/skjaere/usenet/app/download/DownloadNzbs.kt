package io.skjaere.usenet.app.download

import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * And an NZB the user opened on Import, with the archive password typed for it (L423): kept for the downloads of its files,
 * whose turns take it as their grab, as no indexer has it.
 *
 * Each copy is a folder of [dir] (filesDir's `downloads/.packs/`, out of backups and transfers with the rest of
 * `downloads/`) named by the SHA-256 of its release's key, never by the key: `nzb`, `password` where it has one, and
 * `meta` ([Meta]), each written whole then renamed, `meta` last, so a folder with no readable `meta` is no copy. A row id
 * never starts with ".", so reconcile tells the folder from a download's by its name.
 *
 * A season's copy is held while any row of its show and season is still to finish; an imported NZB's while a download of
 * one of its files is still to finish or failed (for its Try again), never once they are all done or gone (C11). Both are
 * read from the rows at each [sweep]: no count that could drift. A row removed whose Undo can still bring it back counts
 * as one still to finish. The sweep runs at reconcile and at each row's end, and deletes what no row holds, and any folder
 * with no readable `meta` with its `.tmp` files, so a process that died between the `nzb` and the `meta` leaves no NZB or
 * password behind. Writes, reads and sweeps take turns: a sweep never takes a copy half written. An import's copy is
 * written with its row under [changes], storage's, which a sweep outside reconcile reads the rows and deletes under
 * ([sweepRows], L456): no sweep takes a copy whose row came after its read.
 *
 * The password is never logged; a log line names a copy by its folder's first 8 hex digits.
 */
class DownloadNzbs(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** What the rows change under, storage's ([DownloadStorage.changes]): an import's copy is written with its row under it. */
    private val changes: Mutex = Mutex(),
) {
    /**
     * What a copy is: its release's [key] and [title], the [candidate] it is in the rows (for a later row to try it from
     * the copy), the [show]'s TMDB id and the [season] it is held for, and whether it [leads]: tried first for the season's
     * later rows, as it isn't above Download size (L431, L432). An imported NZB's ([imported]) names the [nzb] whose rows
     * hold it, and has no show or season, leading none (L423); a copy written before imports were reads with none.
     */
    @Serializable
    data class Meta(
        val key: String,
        val title: String,
        val candidate: DownloadCandidate,
        val show: Int?,
        val season: Int?,
        val leads: Boolean,
        val nzb: String? = null,
    ) {
        companion object {
            /**
             * The copy of an NZB opened on Import, whose own release is [candidate] (its guid the NZB's id): keyed by the
             * candidate, as Resolve finds a copy, and held by that NZB's rows.
             */
            fun imported(candidate: DownloadCandidate) = Meta(
                ReleaseKey.of(candidate.indexerId, candidate.guid), candidate.title, candidate, show = null, season = null, leads = false, nzb = candidate.guid,
            )
        }
    }

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

    private val _copies = MutableStateFlow<List<Meta>>(emptyList())

    /**
     * What the copies kept are, as the last write and the last sweep left them: none before the first sweep, which a
     * process's reconcile makes at its start. What the Downloads page says of a season's pack is read from it (L406).
     */
    val copies: StateFlow<List<Meta>> = _copies.asStateFlow()

    /** Copy [key]'s folder: the key's SHA-256, in hex. */
    fun folder(key: String): File = File(dir, digest(key))

    /**
     * Keeps [nzb] and its [password] (none: no file) as the copy [meta] says, in place of one kept before under its key. One
     * kept already of that same NZB with that same password is left as it was written (L456): another file of an imported
     * NZB queued, or one put back, would otherwise take away for a moment, while it is written again, the copy the NZB's
     * other downloads still to finish or failed open, and a write that failed part way, or a process that died in it, would
     * leave them none.
     */
    suspend fun write(meta: Meta, nzb: ByteArray, password: String?): Unit = turns.withLock {
        withContext(io) {
            val folder = folder(meta.key)
            kept(folder, meta.key, nzb, password)?.let { kept ->
                _copies.update { copies -> if (copies.any { it.key == kept.key }) copies else copies + kept }
                log.info("{} {}: kept as it was", what(kept), short(folder.name))
                return@withContext
            }
            if (!folder.isDirectory && !folder.mkdirs()) {
                throw IOException("Couldn't make ${if (meta.nzb == null) "season pack" else "imported NZB copy"} ${short(folder.name)}'s folder")
            }
            // Kept again: no copy until its meta is back over what is written now.
            _copies.update { kept -> kept.filter { it.key != meta.key } }
            File(folder, META).delete()
            writeWhole(File(folder, NZB), nzb)
            val passwordFile = File(folder, PASSWORD)
            if (password != null) writeWhole(passwordFile, password.toByteArray()) else passwordFile.delete()
            writeWhole(File(folder, META), json.encodeToString(Meta.serializer(), meta).toByteArray())
            _copies.update { kept -> kept + meta }
            log.info("{} {}: kept for its {}, {} bytes", what(meta), short(folder.name), if (meta.nzb == null) "season" else "downloads", nzb.size)
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
     * The copies kept for [show]'s [season] (L431), in their keys' order: a season row's turn tries one that leads first.
     * Never an imported NZB's, which is no season's.
     */
    suspend fun forSeason(show: Int, season: Int): List<Copy> = turns.withLock {
        withContext(io) {
            dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
                val meta = meta(folder)?.takeIf { it.nzb == null && it.show == show && it.season == season && folder.name == digest(it.key) }
                    ?: return@mapNotNull null
                val nzb = File(folder, NZB).takeIf { it.isFile }?.readBytes() ?: return@mapNotNull null
                Copy(meta, DownloadStorage.Source(nzb, File(folder, PASSWORD).takeIf { it.isFile }?.readText()))
            }.sortedBy { it.meta.key }
        }
    }

    /**
     * Deletes every copy no row of [rows] holds, and whatever else is in the folder that is no copy, its `.tmp` files with
     * it. A season's copy is held by a row of its show and season still to finish ([DownloadRunner.GOING_ON]); an imported
     * NZB's by a download of one of its files still to finish or failed, its NZB read from its pick (L423); either by one
     * of [removed], the ids of rows removed whose Undo can still bring them back. The prepare held goes with its copy.
     */
    suspend fun sweep(rows: List<Download>, removed: Set<String> = emptySet()): Unit = turns.withLock {
        withContext(io) {
            val seasons = rows.filter { it.state in DownloadRunner.GOING_ON || it.id in removed }
                .mapNotNullTo(HashSet()) { row -> row.showTmdbId?.let { show -> row.season?.let { show to it } } }
            // A failed one too: its Try again opens the file from the copy, with the password typed for it.
            val imports = rows.filter { row ->
                row.kind == DownloadKind.Release && (row.state in DownloadRunner.GOING_ON || row.state == DownloadState.Failed || row.id in removed)
            }.mapNotNullTo(HashSet()) { row -> DownloadColumns.pick(row)?.nzbId }
            fun held(meta: Meta): Boolean =
                meta.nzb?.let { it in imports } ?: (meta.show?.let { show -> meta.season?.let { show to it } } in seasons)
            val kept = mutableListOf<Meta>()
            dir.listFiles().orEmpty().forEach { entry ->
                val meta = if (entry.isDirectory) meta(entry) else null
                if (meta != null && held(meta)) {
                    kept += meta
                    return@forEach
                }
                val named = meta?.let(::what) ?: "Season pack"
                if (entry.deleteRecursively() || !entry.exists()) {
                    log.info("{} {}: dropped, {}", named, short(entry.name), if (meta == null) "no copy" else "no download of it to finish")
                } else {
                    log.warn("{} {}: couldn't be dropped", named, short(entry.name))
                }
            }
            _copies.value = kept.sortedBy { it.key }
            held?.let { last -> if (meta(folder(last.key)) == null && held === last) held = null }
        }
    }

    /**
     * [sweep] over the rows [rows] reads, read and swept under [changes] (L456): an import writes its copy and then its row
     * under it, so a sweep whose read came before that row never deletes its copy. Reconcile, which holds [changes] already
     * (a Mutex isn't reentrant), sweeps with the rows it read itself.
     */
    suspend fun sweepRows(rows: suspend () -> List<Download>, removed: Set<String> = emptySet()): Unit =
        changes.withLock { sweep(rows(), removed) }

    /** Keeps [prepared], copy [key]'s NZB as Resolve prepared it, in place of the one kept before, while that copy lasts. */
    fun hold(key: String, prepared: PreparedNzb) {
        held = Held(key, prepared)
    }

    /** The last copy's prepare while its copy lasts; null when there is none, as in a new process. */
    fun held(): PreparedNzb? = held?.prepared

    /** [folder]'s copy of [key] where it holds one, readable, of [nzb] with [password]: its meta; else null. */
    private fun kept(folder: File, key: String, nzb: ByteArray, password: String?): Meta? {
        val meta = meta(folder)?.takeIf { it.key == key } ?: return null
        if (File(folder, PASSWORD).takeIf { it.isFile }?.readText() != password) return null
        val kept = File(folder, NZB).takeIf { it.isFile && it.length() == nzb.size.toLong() } ?: return null
        return meta.takeIf { kept.readBytes().contentEquals(nzb) }
    }

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

        /** What a log line calls the copy [meta]: a season's pack, or an NZB opened on Import. */
        private fun what(meta: Meta): String = if (meta.nzb == null) "Season pack" else "Imported NZB copy"

        /** How a log line names a copy's folder [name]: its first 8 hex digits; anything else in the folder by none. */
        private fun short(name: String): String = if (HEX.matches(name)) name.take(8) else "(not a copy)"
    }
}

private val log = LoggerFactory.getLogger(DownloadNzbs::class.java)

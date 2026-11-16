package io.skjaere.usenet.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps prepared NZBs in memory (their metadata is what streams need) and the raw NZB
 * bytes, title and archive password on disk, so an NZB can be re-prepared after the process
 * is killed.
 *
 * It keeps the newest [maxEntries]: by default the last 10 played or imported, for Resume and the next episode, and the one
 * an attempt is reading, which its probe kept and which goes again should the attempt fail ([UsenetTarget.drop]).
 *
 * What an attempt keeps it holds ([hold]), and a play and a download may hold the same NZB at once (backlog 353): an entry
 * goes when its last holder lets go ([release]), and only if a hold added it. One [put] keeps (Import's), or one an
 * earlier process left, is the store's own, whoever holds it.
 *
 * Safe to call from the main thread: the files are read and written on [ioDispatcher].
 */
class PreparedNzbStore(
    private val dir: File,
    private val maxEntries: Int = 10 + 1,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val prepared = ConcurrentHashMap<String, PreparedNzb>()
    private val order = ArrayDeque<String>()

    // Whether [order] holds the NZBs earlier processes left on disk; only read and set under [mutex].
    private var adopted = false

    // Each held entry's holders, by its id; only read and written under [mutex]. An entry evicted or let go of has none.
    private val holders = HashMap<String, Holders>()

    // One writer or reader at a time, so an eviction never deletes files a concurrent put just rewrote.
    private val mutex = Mutex()

    fun get(id: String): PreparedNzb? = prepared[id]

    /**
     * Keeps [nzb], as the newest: true when the store didn't hold it before. A plain keep (Import's): the entry is the
     * store's own from then on, and its holders letting go never take it out. Throws [java.io.IOException] if the files
     * can't be written, without adding [nzb] or evicting an entry to make room for it.
     */
    suspend fun put(nzb: PreparedNzb, nzbBytes: ByteArray): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            write(nzb, nzbBytes).also { holders[nzb.id]?.added = false }
        }
    }

    /**
     * Keeps [nzb] as [put] does, and counts one more holder of it until its [release] (backlog 353): the entry goes with
     * its last holder's release where a hold added it, and stays where the store held it before the first hold (a [put]'s,
     * an earlier process's). Throws as [put] does, holding nothing.
     */
    suspend fun hold(nzb: PreparedNzb, nzbBytes: ByteArray): Hold = mutex.withLock {
        withContext(ioDispatcher) {
            val added = write(nzb, nzbBytes)
            Hold(nzb.id).also { holders.getOrPut(nzb.id) { Holders(added) }.holds += it }
        }
    }

    /**
     * Lets go of [hold]: once an entry's last holder has, an entry a hold added goes, from memory and from disk. A hold let
     * go of before, or whose entry was evicted since, changes nothing.
     */
    suspend fun release(hold: Hold) = mutex.withLock {
        withContext(ioDispatcher) {
            val held = holders[hold.id]?.takeIf { it.holds.remove(hold) } ?: return@withContext
            if (held.holds.isNotEmpty()) return@withContext
            holders.remove(hold.id)
            if (held.added) {
                order.remove(hold.id)
                delete(hold.id)
            }
        }
    }

    /** Raw bytes, title and archive password (null without one) of a previously stored NZB, for re-preparation. */
    suspend fun stored(id: String): Triple<ByteArray, String, String?>? = mutex.withLock {
        withContext(ioDispatcher) {
            adoptEarlierEntries()
            val bytes = File(dir, "$id.nzb").takeIf { it.isFile }?.readBytes() ?: return@withContext null
            val title = File(dir, "$id.title").takeIf { it.isFile }?.readText() ?: id.take(12)
            val password = File(dir, "$id.pw").takeIf { it.isFile }?.readText()
            Triple(bytes, title, password)
        }
    }

    /** Returns the prepared NZB, re-preparing it from disk if the process was restarted. */
    suspend fun getOrPrepare(id: String, preparer: NzbPreparer): PrepareOutcome {
        get(id)?.let { return PrepareOutcome.Ready(it) }
        val (bytes, title, password) = stored(id) ?: return PrepareOutcome.Failed(UsenetError.Unknown("NZB $id is no longer available"))
        return preparer.prepare(bytes, title, password).also { if (it is PrepareOutcome.Ready) put(it.nzb, bytes) }
    }

    /** The NZBs kept on disk for resume: how many, and the bytes they take with their titles and passwords. */
    suspend fun storedFiles(): StoredFiles = mutex.withLock {
        withContext(ioDispatcher) {
            adoptEarlierEntries()
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            StoredFiles(count = files.count { it.extension == "nzb" }, bytes = files.sumOf { it.length() })
        }
    }

    /**
     * Deletes the NZBs kept on disk, so resuming one of them grabs its NZB from the indexer again.
     * Those prepared in this process stay in memory, and are dropped from it in their turn: a play
     * in progress goes on.
     */
    suspend fun clearStored() {
        mutex.withLock {
            withContext(ioDispatcher) {
                adoptEarlierEntries()
                dir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
                order.retainAll { prepared.containsKey(it) }
            }
        }
    }

    /**
     * On first use, queues the NZBs earlier processes left behind for eviction, oldest first, so
     * they count towards [maxEntries] too, and deletes titles and passwords whose NZB is gone. Call
     * under [mutex] on [ioDispatcher].
     */
    private fun adoptEarlierEntries() {
        if (adopted) return
        adopted = true
        val (nzbs, others) = dir.listFiles().orEmpty().filter { it.isFile }.partition { it.extension == "nzb" }
        val ids = nzbs.sortedBy { it.lastModified() }.map { it.nameWithoutExtension }
        val kept = ids.toSet()
        others.filter { it.extension in sidecars && it.nameWithoutExtension !in kept }.forEach { it.delete() }
        order.addAll(ids)
        evictOverflow()
    }

    /**
     * [nzb]'s files written and [nzb] kept as the newest, evicting the oldest past [maxEntries]: true when the store didn't
     * hold it before. Throws, changing nothing, where the files can't be written. Call under [mutex] on [ioDispatcher].
     */
    private fun write(nzb: PreparedNzb, nzbBytes: ByteArray): Boolean {
        adoptEarlierEntries()
        val added = nzb.id !in order
        dir.mkdirs()
        File(dir, "${nzb.id}.nzb").writeBytes(nzbBytes)
        File(dir, "${nzb.id}.title").writeText(nzb.title)
        // A password entered on import is only in the metadata, not in the NZB.
        val passwordFile = File(dir, "${nzb.id}.pw")
        nzb.metadata.orderedArchiveNzb.password?.let { passwordFile.writeText(it) } ?: passwordFile.delete()
        prepared[nzb.id] = nzb
        order.remove(nzb.id)
        order.addLast(nzb.id)
        evictOverflow()
        return added
    }

    private fun evictOverflow() {
        while (order.size > maxEntries) delete(order.removeFirst())
    }

    /** [id] out of memory with its holders, and its files off disk. Call under [mutex] on [ioDispatcher]. */
    private fun delete(id: String) {
        prepared.remove(id)
        holders.remove(id)
        File(dir, "$id.nzb").delete()
        sidecars.forEach { File(dir, "$id.$it").delete() }
    }

    /** [count] NZBs on disk, taking [bytes] with their titles and passwords. */
    data class StoredFiles(val count: Int, val bytes: Long)

    /**
     * One holder of the entry [id] ([hold]), let go of with [release]. Each is its own, equal only to itself, so a hold
     * let go of twice counts once.
     */
    class Hold internal constructor(val id: String)

    /** An entry's [holds], and whether the first of them [added] it (a [put] since makes it the store's own). */
    private class Holders(var added: Boolean) {
        val holds = HashSet<Hold>()
    }

    private companion object {
        /** Extensions of the files stored next to each `<id>.nzb`. */
        val sidecars = listOf("title", "pw")
    }
}

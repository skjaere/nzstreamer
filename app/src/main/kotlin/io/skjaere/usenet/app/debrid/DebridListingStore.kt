package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * The listings debrid services gave for the releases played from them (L181), as the NZB store keeps NZBs: the last [max],
 * in memory and in [file] (filesDir's `debrid/listings.json`), so a Resume, the next episode of a pack and a player made
 * again after the process went find a release's files and the service's ids for them. Each is kept by an opaque id
 * ([idFor]: URI-unreserved, naming neither the torrent nor the account), which the file's URI names (L171), with the
 * account it came from, the release's name, and what the service prepared ([Prepared]: the torrent's hash, its files with
 * the service's ids, the service's handle for it, such as TorBox's torrent id). Never a link or a key.
 *
 * A listing is [put] as a play prepares it, in memory; [keep] writes the one that plays to [file], newest last. Files are
 * read and written on [io]. Each attempt of a play puts its listing, and its probe keeps it, whether it plays or not, so
 * [max] is 10 for the releases played before and as many again as a play's attempts ([PlaybackPrefs.RELEASES_TO_TRY]):
 * a play whose every attempt fails leaves those 10 kept.
 */
class DebridListingStore(
    private val file: File,
    private val max: Int = 10 + PlaybackPrefs.RELEASES_TO_TRY.last(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One release's listing from the service account [account], kept as [id], named [title]. */
    @Serializable
    data class Entry(val id: String, val account: String, val title: String, val prepared: Prepared) {
        // No hash, name or account in a log line (L208).
        override fun toString() = "Entry($prepared)"
    }

    private val entries = ArrayList<Entry>()
    private val lock = Mutex()

    /** [entries] as they were last changed, for a read that can't wait on [lock] ([kept]). */
    @Volatile
    private var shown: List<Entry> = emptyList()

    // Whether [entries] holds what earlier processes left in [file]; read and set under [lock].
    private var read = false

    /** Keeps [prepared], the release named [title] as [account]'s service prepared it, in memory, newest; the entry it is kept as. */
    suspend fun put(account: String, title: String, prepared: Prepared): Entry = lock.withLock {
        readEarlier()
        Entry(idFor(account, prepared.hash), account, title, prepared).also(::newest)
    }

    /**
     * Writes the listing kept as [id] to [file], as the newest: what plays from it is found there after the process goes.
     * Throws when it can't be written, as on a full disk; nothing for an id it doesn't keep.
     */
    suspend fun keep(id: String) = lock.withLock {
        readEarlier()
        entries.firstOrNull { it.id == id }?.let(::newest) ?: return@withLock
        write()
    }

    /** The listing kept as [id], or null. */
    suspend fun get(id: String): Entry? = lock.withLock {
        readEarlier()
        entries.firstOrNull { it.id == id }
    }

    /** The newest listing kept of the torrent [hash], from whichever account; null for none. */
    suspend fun find(hash: InfoHash): Entry? = lock.withLock {
        readEarlier()
        entries.lastOrNull { it.prepared.hash == hash }
    }

    /**
     * The service's handle for the torrent [hash] in the account it was added to (TorBox's torrent id), the newest kept:
     * what a play of it again asks the service about, so nothing is added again (L184). Null for none.
     */
    suspend fun handleOf(hash: InfoHash): String? = lock.withLock {
        readEarlier()
        entries.lastOrNull { it.prepared.hash == hash && it.prepared.handle != null }?.prepared?.handle
    }

    /**
     * [find] without waiting, from what this process holds: the listing of a release playing, which its play put here, for
     * the screens that ask as they draw (the next episode's card).
     */
    fun kept(hash: InfoHash): Entry? = shown.lastOrNull { it.prepared.hash == hash }

    /**
     * The listing [launch] plays from, put back in memory from [file] if this process doesn't hold it (one made after the
     * process that kept it went): asked before a player is made for a debrid launch (L171), so the player made again, its
     * links and the pack's next episode ([kept]) find it. Null for a launch that isn't a debrid service's, or whose
     * listing is no longer kept.
     */
    suspend fun ensure(launch: PlayerLaunch): Entry? = (PlayUris.parse(launch.uri) as? PlayUris.Target.Service)?.let { get(it.listingId) }

    /** The listing kept as [id] that this process holds, without waiting: the player's, which [ensure] put back before it began. */
    fun keptAs(id: String): Entry? = shown.lastOrNull { it.id == id }

    /**
     * Test seam: forgets every listing of the torrent [hash], here and in [file], as on a device where no play kept one: its
     * next play asks the account, not a handle kept from an earlier play.
     */
    @VisibleForTesting
    internal suspend fun forget(hash: InfoHash) = lock.withLock {
        readEarlier()
        entries.removeAll { it.prepared.hash == hash }
        shown = entries.toList()
        write()
    }

    /** Test seam: forgets what this process holds, as a new process starts with only [file]. */
    @VisibleForTesting
    internal suspend fun forgetInMemory() = lock.withLock {
        entries.clear()
        shown = emptyList()
        read = false
    }

    /** As the newest, [entry] in place of any of its id, the oldest let go past [max]. Under [lock]. */
    private fun newest(entry: Entry) {
        entries.removeAll { it.id == entry.id }
        entries += entry
        while (entries.size > max) entries.removeAt(0)
        shown = entries.toList()
    }

    /** [entries] to [file], through a new file renamed over it. Under [lock]. */
    private suspend fun write() = withContext(io) {
        file.parentFile?.mkdirs()
        val written = File(file.parentFile, "${file.name}.new")
        written.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
        if (!written.renameTo(file)) throw java.io.IOException("Couldn't replace ${file.name}")
    }

    /** On first use, what earlier processes wrote, oldest first; a file that doesn't read is none. Under [lock]. */
    private suspend fun readEarlier() {
        if (read) return
        read = true
        val earlier = withContext(io) {
            try {
                file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListSerializer(Entry.serializer()), it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("debrid: the kept listings didn't read: {}", e.javaClass.simpleName)
                emptyList()
            }
        }
        // Those this process put before the file was read stay the newest.
        val now = entries.toList()
        entries.clear()
        (earlier + now).forEach(::newest)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DebridListingStore::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The id a listing of the torrent [hash] from the account [account] is kept by: the same for the same release in
         * the same account, so playing it again keeps one entry; hex, so a URI holds it as it is; and a digest with the
         * account's own random id in it, so neither the torrent nor the account can be read from it.
         */
        fun idFor(account: String, hash: InfoHash): String =
            MessageDigest.getInstance("SHA-256").digest("$account:${hash.hex}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}

package io.skjaere.usenet.app.download

import android.content.Context
import android.os.storage.StorageManager
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Where this device's downloads live, and how their files come and go (L346, L348, L350).
 *
 * - The video, in the app's own folder on the primary volume ([volumeDir], removed with the app, W109):
 *   `downloads/<id>/v.<ext>`, `.part` until it is renamed when done, never named after the release, beside a `.nomedia`
 *   in `downloads/`. Its whole size is allocated before its first byte ([open]), so it can't run out of room part way.
 * - Each download's bookkeeping, in [filesDir]'s `downloads/<id>/`: the marker `.row`, made with the row ([add], a
 *   season's at once with [addAll]) and lasting as long as it; the NZB and its password, kept at Resolve (or with the
 *   row, for a file chosen by hand), or for a torrent its listing's copy and the mark of its restart from 0 (L420), each
 *   dropped once the file is complete, failed or cancelled; its poster and still, kept as long as the row
 *   ([DownloadImages], L411). Neither place goes in a backup or a transfer, so a row whose marker is missing came from
 *   another device ([reconcile]).
 * - A season pack's NZB and password, kept once for the season's downloads in filesDir's `downloads/.packs/` ([packs],
 *   L394) while one of them is still to finish; and an NZB opened on Import with the password typed for it, beside them,
 *   for the downloads of its files (L423), written with the row that needs it.
 * - A delete goes in the crash-safe order: the row Deleting (hidden), the video, the bookkeeping, the row ([delete]).
 *
 * The rows and the folders change one call at a time, so reconcile never takes a folder [add] has just made for one with
 * no row. [afterDeleteStep] is a test's seam: a process dying after that step of a delete.
 */
class DownloadStorage(
    private val filesDir: File,
    /** The app's folder on the primary volume (getExternalFilesDirs' first); null while it isn't there, unmounted. */
    private val volumeDir: () -> File?,
    private val downloads: DownloadDao,
    private val storageManager: StorageManager,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val afterDeleteStep: (DeleteStep) -> Unit = {},
) {
    /**
     * Held while the rows and the folders change, one call at a time; a download's pictures are written and deleted under
     * it too ([DownloadImages]), so none goes into a folder a delete is taking.
     */
    internal val changes = Mutex()

    /**
     * The season packs' NZBs, each kept once for its season's downloads, and the NZBs opened on Import, each kept for its
     * files' downloads, in filesDir's `downloads/.packs/` (L394, L423). An import's copy is written under [changes].
     */
    val packs = DownloadNzbs(File(filesDir, DownloadNzbs.PATH), io, changes)

    /**
     * Drops the copies no download holds (L394, L423), nor one of [removed], removed with its Undo still to come: at a
     * download's end. The rows are read and the copies deleted under [changes], as an import writes its copy and its row
     * (L456), so a sweep never takes a copy whose row came after its read.
     */
    suspend fun sweepPacks(removed: Set<String> = emptySet()) = packs.sweepRows({ downloads.all().first() }, removed)

    /** The steps of a delete, in order, each one a process may die after. */
    enum class DeleteStep { Marked, VideoGone, BookkeepingGone }

    /** The volume's folder isn't there (unmounted): the download waits, as "Storage isn't available" (L388). */
    class Unavailable : IOException("Storage isn't available")

    /** A download's own copy of its NZB, and its archive's password where it has one (L346). */
    class Source(val nzb: ByteArray, val password: String?) {
        // The password is the release's: never in a log line.
        override fun toString() = "Source(${nzb.size} bytes, password=${if (password == null) "none" else "set"})"
    }

    /**
     * The NZB grabbed for a release Resolve tried and had no answer about (the providers out of reach, the attempt cut
     * short, the volume away as it was picked): its candidate's [key] and its [source], so its next try costs no grab (L340).
     */
    class Grab(val key: String, val source: Source) {
        override fun toString() = "Grab($source)"
    }

    /** [id]'s bookkeeping folder: its marker, and its NZB and password while it needs them. */
    fun bookkeeping(id: String): File = File(File(filesDir, DIR), checked(id))

    /** [id]'s video folder on the primary volume; null while the volume isn't there. */
    fun videoFolder(id: String): File? = volumeDir()?.let { File(File(it, DIR), checked(id)) }

    /**
     * Where [id]'s finished file goes under the volume's folder, as its row keeps it: `downloads/<id>/v.<ext>`, the
     * extension the file's own ([fileName]'s), as the cast server names its path; one a path can't hold is left off.
     */
    fun relativePath(id: String, fileName: String): String {
        val extension = ContainerTypes.extension(fileName).takeIf { EXTENSION.matches(it) }
        return "$DIR/${checked(id)}/" + if (extension == null) VIDEO else "$VIDEO.$extension"
    }

    /** Whether the primary volume's folder is there: a download waiting for it ("Storage isn't available") can run again. */
    fun volumeThere(): Boolean = volumeDir() != null

    /** [row]'s finished file; null before it has one, or while the volume isn't there. */
    fun file(row: Download): File? = row.relativePath?.let { path -> volumeDir()?.let { File(it, path) } }

    /**
     * Makes [row]'s bookkeeping folder with its marker, and [grab] beside them where it has one (the NZB of a file chosen by
     * hand, L403, as [keepGrab] keeps it), or the [copy] of the NZB opened on Import in `.packs/` ([packs], L423), then
     * the row: never a row without its marker (L346, L350), nor a picked row that a walk could take up before its NZB is
     * there. All under [changes], which a sweep reads the rows and deletes under (L456). A copy left by a row that couldn't
     * be inserted goes at the next sweep, unless another row of its NZB holds it.
     */
    suspend fun add(row: Download, grab: Grab? = null, copy: DownloadNzbs.Copy? = null): Unit = changes.withLock {
        withContext(io) {
            val folder = bookkeeping(row.id)
            mark(folder)
            try {
                copy?.let { packs.write(it.meta, it.source.nzb, it.source.password) }
                grab?.let { writeGrab(folder, it.key, it.source.nzb, it.source.password) }
                downloads.insert(row)
            } catch (e: Throwable) {
                dropUnlessRow(row.id, folder, e)
                throw e
            }
        }
    }

    /**
     * A season queued at once (L392, L396): every one of [rows]' bookkeeping folders and markers made first, then every row
     * inserted and the rows [putBack] written whole in one transaction ([DownloadDao.insertAll]). One that fails leaves none
     * of them, and their new folders go too; a process that dies between the two leaves folders with no row, which the next
     * start's reconcile deletes. An imported file's failed row put back (L469) comes with its NZB's [copy], written again
     * before it, as [add] writes one.
     */
    suspend fun addAll(rows: List<Download>, putBack: List<Download> = emptyList(), copy: DownloadNzbs.Copy? = null): Unit = changes.withLock {
        withContext(io) {
            val folders = rows.map { bookkeeping(it.id) }
            var made = 0
            try {
                folders.forEach { folder ->
                    made++
                    mark(folder)
                }
                copy?.let { packs.write(it.meta, it.source.nzb, it.source.password) }
                downloads.insertAll(rows, putBack)
            } catch (e: Throwable) {
                rows.zip(folders).take(made).forEach { (row, folder) -> dropUnlessRow(row.id, folder, e) }
                throw e
            }
        }
    }

    /** Makes [folder] with its marker. */
    private fun mark(folder: File) {
        folder.mkdirs()
        File(folder, MARKER).createNewFile()
        if (!File(folder, MARKER).isFile) throw IOException("Couldn't make download ${folder.name}'s folder")
    }

    /**
     * [folder], made for row [id] whose insert failed with [failure]: it goes too, as reconcile would delete it. A row may be
     * there all the same: an insert whose caller was cancelled as it committed, or one whose id was taken, the folder then
     * that row's. Asked whatever the cancellation; a failed ask keeps the folder, for the next reconcile to judge.
     */
    private suspend fun dropUnlessRow(id: String, folder: File, failure: Throwable) = withContext(NonCancellable) {
        val rowThere = try {
            downloads.get(id) != null
        } catch (lookup: Exception) {
            failure.addSuppressed(lookup)
            true
        }
        if (!rowThere) folder.deleteRecursively()
    }

    /**
     * Opens [id]'s video to write: its `.part`, made if new, with its whole [size] allocated before the first byte (C8), so
     * the room is taken at once and progress is the row's alone. Opened again (a resume) it keeps what was written, and a
     * file [finish] renamed already, by a run that died before its row said so, is taken back whole as the `.part`: a new
     * one beside it would be zeros, which its finish would rename over it. What another release left in its folder (its
     * file couldn't go while the volume was away) goes, its room with it: a file of another name is deleted, and a longer
     * one of this name cut to [size]. Throws [Unavailable] while the volume isn't there, and an IOException when Android
     * can't allocate it. The caller closes it.
     */
    suspend fun open(id: String, fileName: String, size: Long): FileChannel = withContext(io) {
        require(size >= 0) { "A size of $size" }
        val volume = volumeDir() ?: throw Unavailable()
        val folder = File(downloadsDir(volume), checked(id)).apply { mkdirs() }
        val part = part(volume, id, fileName)
        val done = File(volume, relativePath(id, fileName))
        folder.listFiles().orEmpty().filter { it != part && it != done }.forEach(::deleteFolder)
        if (done.isFile && !part.exists() && !done.renameTo(part)) throw IOException("Couldn't reopen download $id's file")
        val file = RandomAccessFile(part, "rw")
        try {
            if (file.length() > size) file.setLength(size)
            // Android's fallocate refuses an empty length.
            if (size > 0) storageManager.allocateBytes(file.fd, size)
            file.channel
        } catch (e: Throwable) {
            file.close()
            throw e
        }
    }

    /**
     * Renames [id]'s complete `.part` to its finished name, in its folder (atomic): the finished file. Never over a finished
     * file, which a rename would replace.
     */
    suspend fun finish(id: String, fileName: String): File = withContext(io) {
        val volume = volumeDir() ?: throw Unavailable()
        val part = part(volume, id, fileName)
        val done = File(volume, relativePath(id, fileName))
        when {
            // Renamed already, by a run that died before its row said so.
            !part.exists() && done.isFile -> done
            done.exists() -> throw IOException("Download $id's file is finished already")
            part.renameTo(done) -> done
            else -> throw IOException("Couldn't rename download $id's file")
        }
    }

    /** Deletes [id]'s video, finished or not, giving back what it held: another release replaces it, or it starts again from 0. */
    suspend fun deleteVideo(id: String): Unit = withContext(io) {
        deleteFolder(videoFolder(id) ?: throw Unavailable())
    }

    /** Keeps [id]'s NZB and its [password] (none: no file) in its bookkeeping folder, at Resolve (L346). */
    suspend fun keepSource(id: String, nzb: ByteArray, password: String?): Unit = withContext(io) {
        val folder = bookkeeping(id)
        // Each written whole, then renamed: a process dying part way leaves the one before, never half of one.
        writeWhole(File(folder, NZB), nzb)
        val passwordFile = File(folder, PASSWORD)
        if (password != null) writeWhole(passwordFile, password.toByteArray()) else passwordFile.delete()
    }

    /** [id]'s NZB and its password, as [keepSource] kept them; null when it has none. */
    suspend fun source(id: String): Source? = withContext(io) {
        val folder = bookkeeping(id)
        val nzb = File(folder, NZB).takeIf { it.isFile }?.readBytes() ?: return@withContext null
        Source(nzb, File(folder, PASSWORD).takeIf { it.isFile }?.readText())
    }

    /**
     * Deletes [id]'s NZB and password, and any kept for an attempt ([keepGrab]), or its torrent's listing and restart mark
     * ([keepListing], [markRestarted]), once its file is complete, failed or cancelled; its folder and marker stay.
     */
    suspend fun dropSource(id: String): Unit = withContext(io) {
        val folder = bookkeeping(id)
        listOf(NZB, PASSWORD, GRAB, GRAB_KEY, GRAB_PASSWORD, LISTING, RESTARTED).forEach { name ->
            File(folder, name).delete()
            File(folder, name + TMP).delete()
        }
    }

    /**
     * Keeps [entry], the listing of the torrent [id] downloads as its service prepared it (L420), in its bookkeeping
     * folder ([DebridListingStore.copyOf]: the account's id, the release's name, its files with the service's ids and its
     * handle; never a link or a key), written whole, then renamed: before its row takes the release, and again where the
     * torrent is added again or comes from another account. A reopen finds its file in it.
     */
    suspend fun keepListing(id: String, entry: DebridListingStore.Entry): Unit = withContext(io) {
        writeWhole(File(bookkeeping(id), LISTING), DebridListingStore.copyOf(entry).toByteArray())
    }

    /** The listing [keepListing] kept for [id]; null when it has none, or one that doesn't read, which is then resolved again. */
    suspend fun listing(id: String): DebridListingStore.Entry? = withContext(io) {
        val copy = File(bookkeeping(id), LISTING).takeIf { it.isFile } ?: return@withContext null
        try {
            DebridListingStore.entryOf(copy.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Download {}: its listing's copy didn't read: {}", id, e.javaClass.simpleName)
            null
        }
    }

    /**
     * Marks [id]'s file as started again from 0, its server having answered a resume with another part (L419): a second
     * such answer, in this process or a later one, fails it. Dropped with its source ([dropSource]).
     */
    suspend fun markRestarted(id: String): Unit = withContext(io) {
        writeWhole(File(bookkeeping(id), RESTARTED), ByteArray(0))
    }

    /** Whether [id]'s file was started again from 0 ([markRestarted]), its source not dropped since. */
    suspend fun restarted(id: String): Boolean = withContext(io) { File(bookkeeping(id), RESTARTED).isFile }

    /**
     * Keeps the NZB grabbed for [id]'s release [key] as Resolve tries it, with its [password] (none: no file), until that
     * attempt has its answer ([dropGrab]): one that has none yet is tried again from it, with no grab. The key goes last,
     * so a process dying part way leaves no key over another release's NZB.
     */
    suspend fun keepGrab(id: String, key: String, nzb: ByteArray, password: String?): Unit = withContext(io) {
        writeGrab(bookkeeping(id), key, nzb, password)
    }

    /** [keepGrab]'s files in [folder], each written whole, the key last. */
    private fun writeGrab(folder: File, key: String, nzb: ByteArray, password: String?) {
        File(folder, GRAB_KEY).delete()
        writeWhole(File(folder, GRAB), nzb)
        val passwordFile = File(folder, GRAB_PASSWORD)
        if (password != null) writeWhole(passwordFile, password.toByteArray()) else passwordFile.delete()
        writeWhole(File(folder, GRAB_KEY), key.toByteArray())
    }

    /** The NZB [keepGrab] kept for [id], with its release's key; null when none is. */
    suspend fun grab(id: String): Grab? = withContext(io) {
        val folder = bookkeeping(id)
        val key = File(folder, GRAB_KEY).takeIf { it.isFile }?.readText() ?: return@withContext null
        val nzb = File(folder, GRAB).takeIf { it.isFile }?.readBytes() ?: return@withContext null
        Grab(key, Source(nzb, File(folder, GRAB_PASSWORD).takeIf { it.isFile }?.readText()))
    }

    /** Deletes the NZB [keepGrab] kept for [id]: its attempt had its answer. */
    suspend fun dropGrab(id: String): Unit = withContext(io) {
        val folder = bookkeeping(id)
        listOf(GRAB_KEY, GRAB, GRAB_PASSWORD).forEach { name ->
            File(folder, name).delete()
            File(folder, name + TMP).delete()
        }
    }

    /**
     * What the primary volume can give and what downloads take of it (L348); null while it isn't there. [except]: the
     * download a check is for, left out of what is set aside, as its own size is the one checked, and its file's size,
     * where it has one made, counted as available to it ([DownloadSpace.of]).
     */
    suspend fun space(except: String? = null): DownloadSpace? = withContext(io) {
        val volume = volumeDir() ?: return@withContext null
        val allocatable = storageManager.getAllocatableBytes(storageManager.getUuidForPath(volume))
        DownloadSpace.of(allocatable, volume.usableSpace, downloads.all().first(), except)
    }

    /**
     * Brings the rows and the folders into step, at start and after Delete all (L348, L350, L379):
     * - a row being deleted is finished: its Undo went with the process that showed it; but one [held] while it plays,
     *   whose row and folders stay until its last hold ends (L413);
     * - a row whose marker is missing came from another device (a transfer carries the library, never these folders), and
     *   is dropped; a queued one has its marker from [add], before any NZB, and a finished one keeps it;
     * - a finished row whose file is gone, or cut short since it finished (L417), fails, "The downloaded file is gone",
     *   holding no file and no progress;
     * - a folder no row has, in either place, is deleted: what a process left as it died; but for the season packs' copies
     *   (`.packs`, a name no row's id can have), which are swept on their own, with the rows read here, as this holds
     *   [changes]: those no download holds, and any copy half written, go ([DownloadNzbs.sweep]).
     *
     * Files beside the folders stay. While the volume isn't there, no row is taken for gone and its folders stay.
     */
    suspend fun reconcile(held: (String) -> Boolean = { false }): Unit = changes.withLock {
        withContext(io) {
            val volume = volumeDir()
            var dropped = 0
            var failed = 0
            downloads.all().first().forEach { row ->
                when {
                    // An id no folder can be named by never had one: it came from elsewhere.
                    !ID.matches(row.id) -> downloads.delete(row.id).also { dropped++ }
                    row.state == DownloadState.Deleting -> if (!held(row.id)) deleteRow(row.id)
                    !File(bookkeeping(row.id), MARKER).isFile -> deleteRow(row.id).also { dropped++ }
                    volume != null && isGone(row, volume) -> {
                        failGone(row)
                        failed++
                    }
                }
            }
            val left = downloads.all().first()
            val rows = left.mapTo(HashSet()) { it.id }
            val bookkeeping = File(filesDir, DIR)
            val leftOver = listOfNotNull(bookkeeping, volume?.let { File(it, DIR) })
                .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isDirectory && it.name !in rows && !(dir == bookkeeping && it.name == DownloadNzbs.DIR) } }
            leftOver.forEach(::deleteFolder)
            packs.sweep(left)
            if (dropped + failed + leftOver.size > 0) {
                log.info("Reconciled downloads: {} rows dropped, {} files gone, {} folders without a row deleted", dropped, failed, leftOver.size)
            }
        }
    }

    /**
     * [id]'s finished file found gone as it plays (deleted from outside the app while it runs, or cut short since, L417):
     * [reconcile]'s rule for that row alone, at once, rather than at the next start, so its title streams again meanwhile
     * (the hooks play only Done rows) and the Downloads page offers Download again. Whether it failed it: not for a row
     * that isn't Done, nor while the volume isn't there, when nothing is known gone.
     */
    suspend fun fileGone(id: String): Boolean = changes.withLock {
        withContext(io) {
            val volume = volumeDir() ?: return@withContext false
            val row = downloads.get(id)?.takeIf { isGone(it, volume) } ?: return@withContext false
            failGone(row)
            log.info("Download {}: its file is gone; failed as reconcile would fail it", id)
            true
        }
    }

    /**
     * Whether [row] is finished and its file isn't whole on [volume]: not there, or shorter than its size, cut short since it
     * finished (another app, or a fault of the file system), which nothing can read to its end (L417).
     */
    private fun isGone(row: Download, volume: File): Boolean {
        if (row.state != DownloadState.Done) return false
        val file = row.relativePath?.let { File(volume, it) }?.takeIf { it.isFile } ?: return true
        return row.size?.let { file.length() < it } == true
    }

    /** [row], whose file is gone, failed ([failedGone]). */
    private suspend fun failGone(row: Download) = downloads.update(failedGone(row, clock.now()))

    /** Deletes [id]: its row, its video and its bookkeeping, in the crash-safe order. */
    suspend fun delete(id: String): Unit = changes.withLock { withContext(io) { deleteRow(id) } }

    /**
     * The row Deleting (hidden), the video folder, the bookkeeping folder, then the row: stopped after any step, the row is
     * there, Deleting, for the next reconcile to finish, and a file never outlives its row unseen. The video can't go while
     * its volume isn't there: the row then waits, Deleting, for a reconcile with the volume back.
     */
    private suspend fun deleteRow(id: String) {
        downloads.setState(id, DownloadState.Deleting, null, clock.now())
        afterDeleteStep(DeleteStep.Marked)
        val video = videoFolder(id) ?: return
        deleteFolder(video)
        afterDeleteStep(DeleteStep.VideoGone)
        deleteFolder(bookkeeping(id))
        afterDeleteStep(DeleteStep.BookkeepingGone)
        downloads.delete(id)
    }

    private fun deleteFolder(folder: File) {
        if (!folder.deleteRecursively() && folder.exists()) throw IOException("Couldn't delete ${folder.name}")
    }

    /** The volume's `downloads/`, made with its `.nomedia` where it is new, so no media scanner lists what is in it. */
    private fun downloadsDir(volume: File): File = File(volume, DIR).apply {
        mkdirs()
        File(this, NOMEDIA).createNewFile()
    }

    private fun part(volume: File, id: String, fileName: String) = File(volume, relativePath(id, fileName) + PART)

    private fun writeWhole(file: File, bytes: ByteArray) {
        val tmp = File(file.path + TMP)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
    }

    /** [id], refused where it isn't a download's: a folder is made and deleted by it. */
    private fun checked(id: String): String = id.also { require(ID.matches(it)) { "Not a download's id" } }

    companion object {
        /** The folder under filesDir and under the volume's folder; backups and transfers leave it out by this path (L350). */
        const val DIR = "downloads"

        /** The bookkeeping folder's marker: a row whose folder has none came from another device. */
        const val MARKER = ".row"
        const val NZB = "nzb"
        const val PASSWORD = "password"

        /** The NZB kept for an attempt Resolve has no answer about yet, its password and its release's key ([keepGrab]). */
        const val GRAB = "grab"
        const val GRAB_PASSWORD = "grab-password"
        const val GRAB_KEY = "grab-key"

        /** A torrent's listing, as its service prepared it ([keepListing]), and the mark of its file's restart from 0 ([markRestarted]). */
        const val LISTING = "listing"
        const val RESTARTED = "restarted"
        const val NOMEDIA = ".nomedia"
        const val PART = ".part"
        private const val TMP = ".tmp"

        /** The video's name in its folder: the release's never goes on disk. */
        private const val VIDEO = "v"

        /** Reconcile's reason for a finished download whose file is gone. */
        const val FILE_GONE = "The downloaded file is gone"

        /**
         * [row], whose file is gone, as reconcile and [fileGone] fail it at [now]: "The downloaded file is gone", holding no
         * file and no progress. What it is of (its title, year, length, release) stays.
         */
        internal fun failedGone(row: Download, now: Long): Download =
            // Nothing of it is on the device: no byte written, no part missing, so Download again starts from 0.
            row.copy(
                state = DownloadState.Failed, reason = FILE_GONE, relativePath = null, safeOffset = 0, gaps = emptyList(), completedAt = null,
                updatedAt = now,
            )

        /**
         * A download's id, as [Download.newId] makes it: RFC 3986's unreserved characters, starting with a letter or digit,
         * so it is never `.` or `..`, nor a name the folders hold beside the downloads (`.nomedia`).
         */
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]*")

        /** Whether [id] is one a download's folder can be named by ([ID]). */
        internal fun isId(id: String): Boolean = ID.matches(id)

        /** An extension the video's name keeps: as the cast server's path holds one. */
        private val EXTENSION = Regex("[a-z0-9]{1,8}")

        /** The downloads' places on this device: filesDir, and the app's folder on the primary volume (DN1's only one, L348). */
        fun of(context: Context, downloads: DownloadDao) = DownloadStorage(
            filesDir = context.filesDir,
            volumeDir = { context.getExternalFilesDirs(null).firstOrNull()?.takeIf { it.isDirectory } },
            downloads = downloads,
            storageManager = context.getSystemService(StorageManager::class.java),
        )
    }
}

private val log = LoggerFactory.getLogger(DownloadStorage::class.java)

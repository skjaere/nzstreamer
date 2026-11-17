package io.skjaere.usenet.app.download

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.metadata.TmdbImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/** A picture's bytes from its address: the app's image loader's, as a JPEG ([io.skjaere.usenet.app.jpegOf]), or a test's. Null where none came. */
fun interface ImageFetch {
    suspend fun fetch(url: String): ByteArray?
}

/**
 * Each download's poster and still, kept with it so its pages show them offline (L411): `poster.jpg` (TMDB's w342) and an
 * episode's `still.jpg` (w300) in its bookkeeping folder under [root] (filesDir's `downloads/`), beside its marker. Never
 * in its video folder, which a resume empties ([DownloadStorage.open]), nor anywhere else under [root], which reconcile
 * deletes; reconcile and [DownloadStorage.dropSource] leave them, and a download's delete takes them with its folder.
 * - Fetched ([fetch], through the app's image loader) at enqueue from the visible app ([enqueued]), and again for a
 *   finished download whose copy is missing or [REFRESH_MILLIS] old while the app is in front and online ([follow]).
 * - Drawn while under [KEEP_MILLIS] old ([model]), TMDB's six months; one that old is deleted as it is read and at the
 *   app's start ([sweep]), offline or not. A copy's age is its file's time, written from [clock] as it is kept.
 * - None while Show [artwork] is off, and every copy deleted as it is switched off, while the app runs ([drop]).
 *
 * Each copy is written to a temp file, then renamed over the one before, under the storage's lock over the rows and their
 * folders ([folders], [DownloadStorage.changes]): only into a folder whose marker is there, never as a delete takes it,
 * and never once Show artwork is off. Log lines name a download by its id and a picture by what it is, never its address.
 */
class DownloadImages(
    private val root: File,
    private val artwork: Flow<Boolean>,
    fetch: ImageFetch,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val folders: Mutex = Mutex(),
) {
    /** Where the pictures come from: the app's image loader, or a device test's seam (no TMDB). */
    @VisibleForTesting
    @Volatile
    var fetch: ImageFetch = fetch

    /** A download's two pictures, by the name of their files. */
    enum class Kind(val file: String) { Poster("poster.jpg"), Still("still.jpg") }

    /** [rows], just queued from a sheet in the visible app: their pictures fetched in [scope], best effort. */
    fun enqueued(rows: List<Download>) {
        if (rows.isEmpty()) return
        scope.launch { quietly("keep the pictures of ${rows.size} downloads") { keep(rows) } }
    }

    /** [rows]' pictures that are missing or [REFRESH_MILLIS] old fetched and kept; none while Show artwork is off. */
    suspend fun keep(rows: List<Download>) {
        for (row in rows) {
            for (kind in Kind.entries) {
                val url = url(row, kind) ?: continue
                if (!artwork.first()) return
                keep(row.id, kind, url)
            }
        }
    }

    private suspend fun keep(id: String, kind: Kind, url: String) {
        try {
            val folder = folder(id) ?: return
            if (!withContext(io) { marked(folder) && due(File(folder, kind.file)) }) return
            val bytes = fetch.fetch(url)
            if (bytes == null) {
                log.info("Download {}: its {} wasn't kept, none came", id, kind.word)
                return
            }
            folders.withLock {
                // Switched off meanwhile ([drop] has gone or waits for this lock), or the download deleted: nothing kept.
                if (artwork.first()) withContext(io) { if (marked(folder)) write(File(folder, kind.file), bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Download {}: its {} wasn't kept ({})", id, kind.word, e::class.simpleName)
        }
    }

    /**
     * Download [id]'s stored [kind] while it is under [KEEP_MILLIS] old, else [url]: what [io.skjaere.usenet.app.search.Poster]'s
     * model form and a still's image draw. A copy that old is deleted as it is read.
     */
    suspend fun model(id: String, kind: Kind, url: String?): Any? = try {
        val file = folder(id)?.let { File(it, kind.file) }
        when {
            file == null || !withContext(io) { file.isFile } -> url
            withContext(io) { age(file) < KEEP_MILLIS } -> file
            else -> {
                folders.withLock { withContext(io) { if (age(file) >= KEEP_MILLIS) file.delete() } }
                log.info("Download {}: its {} was 180 days old, deleted", id, kind.word)
                url
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.info("Download {}: its {} couldn't be read ({})", id, kind.word, e::class.simpleName)
        url
    }

    /** At the app's start: every copy [KEEP_MILLIS] old deleted, and any a process left half written. */
    suspend fun sweep() {
        val gone = folders.withLock {
            withContext(io) {
                pictures().filter { it.name.endsWith(TMP) || age(it) >= KEEP_MILLIS }.count { it.delete() }
            }
        }
        if (gone > 0) log.info("Downloads' pictures: {} 180 days old or half written deleted", gone)
    }

    /** Show artwork switched off: every download's pictures deleted at once. */
    suspend fun drop() {
        val gone = folders.withLock { withContext(io) { pictures().count { it.delete() } } }
        if (gone > 0) log.info("Downloads' pictures: {} deleted as Show artwork is off", gone)
    }

    /**
     * For the process's life, where downloads run: the start's [sweep]; every copy dropped as Show artwork is off, as it
     * starts and whenever it is switched off; and while it is on, the app in front ([inFront]) and [online], the finished
     * ones of [done] whose copies are missing or [REFRESH_MILLIS] old fetched again, each time those come to hold and as
     * downloads finish.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun follow(done: Flow<List<Download>>, inFront: Flow<Boolean>, online: Flow<Boolean>): Unit = coroutineScope {
        quietly("delete the downloads' pictures 180 days old") { sweep() }
        launch {
            artwork.distinctUntilChanged().collect { on -> if (!on) quietly("delete the downloads' pictures") { drop() } }
        }
        combine(artwork, inFront, online) { on, front, net -> on && front && net }
            .distinctUntilChanged()
            .flatMapLatest { refreshing -> if (refreshing) done.map { rows -> rows.filter { it.state == DownloadState.Done } } else emptyFlow() }
            .collectLatest { rows -> quietly("keep the finished downloads' pictures") { keep(rows) } }
    }

    /** [id]'s bookkeeping folder; none for an id no download's folder can have. */
    private fun folder(id: String): File? = File(root, id).takeIf { DownloadStorage.isId(id) }

    private fun marked(folder: File) = File(folder, DownloadStorage.MARKER).isFile

    /** Whether [copy] is to be fetched: missing, or [REFRESH_MILLIS] old. */
    private fun due(copy: File) = !copy.isFile || age(copy) >= REFRESH_MILLIS

    private fun age(file: File) = clock.now() - file.lastModified()

    /** [bytes] as [file], written whole to a temp file stamped with the clock's time, then renamed over the one before. */
    private fun write(file: File, bytes: ByteArray) {
        val tmp = File(file.path + TMP)
        try {
            tmp.writeBytes(bytes)
            tmp.setLastModified(clock.now())
            if (!tmp.renameTo(file)) throw IOException("Couldn't rename a picture")
        } catch (e: Exception) {
            if (tmp.isFile) tmp.delete()
            throw e
        }
    }

    /** Every download's pictures and their temp files, in each folder a download's id names. */
    private fun pictures(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && DownloadStorage.isId(it.name) }
        .flatMap { folder -> Kind.entries.flatMap { listOf(File(folder, it.file), File(folder, it.file + TMP)) } }
        .filter { it.isFile }

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't {} ({})", what, e::class.simpleName)
        }
    }

    private val Kind.word: String get() = name.lowercase()

    companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L

        /** A finished download's copy this old is fetched again while the app is in front and online. */
        const val REFRESH_MILLIS = 30 * DAY_MILLIS

        /** TMDB's six months: no copy is drawn or kept this old (L411). */
        const val KEEP_MILLIS = 180 * DAY_MILLIS

        /** The poster's width as kept: it serves the Movie page's 96 dp too. */
        const val POSTER_WIDTH = 342

        /** The still's width as kept, the next-episode card's. */
        const val STILL_WIDTH = 300
        private const val TMP = ".tmp"

        /** [row]'s [kind] at TMDB, as it is kept; none where TMDB gave none, and a still for an episode alone. */
        fun url(row: Download, kind: Kind): String? = when (kind) {
            Kind.Poster -> TmdbImages.poster(row.posterPath, POSTER_WIDTH)
            Kind.Still -> row.stillPath.takeIf { row.kind == DownloadKind.Episode }?.let { TmdbImages.still(it, STILL_WIDTH) }
        }
    }
}

/**
 * Download [id]'s stored [kind] while it is fresh, else [url] (L411): the model [io.skjaere.usenet.app.search.Poster]'s
 * model form and a still's image take. Read off the main thread, nothing meanwhile; [url] at once where there is no
 * download.
 */
@Composable
internal fun storedPicture(id: String?, kind: DownloadImages.Kind, url: String?): Any? {
    if (id == null) return url
    val images = LocalContext.current.container.downloadImages
    val model by produceState<Any?>(null, images, id, kind, url) { value = images.model(id, kind, url) }
    return model
}

private val log = LoggerFactory.getLogger(DownloadImages::class.java)

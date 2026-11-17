package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.DownloadImages.Kind
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.metadata.TmdbImages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L411: each download's poster and still kept with it, in a temp folder laid out as filesDir's `downloads/` (a row's
 * bookkeeping folder with its `.row`) beside the volume's video folders, on a fixed clock and a fake image host:
 * - kept at w342 and w300 beside the marker, written whole through a temp file, never in the video folder, nor for a row
 *   whose folder is gone;
 * - the stored file drawn while under 180 days old, the address after, a copy 180 days old deleted as it is read and at
 *   the start's sweep, offline or not;
 * - a finished download's copy missing or 30 days old fetched again only while the app is in front and online;
 * - none while Show artwork is off, and the copies gone as it is switched off, while the process lives;
 * - a picture the host doesn't give keeps nothing, said by the download's id and the picture alone.
 * Titles and paths are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadImagesTest {
    @TempDir
    lateinit var dir: File

    private val root by lazy { File(dir, "files/${DownloadStorage.DIR}") }
    private val volume by lazy { File(dir, "volume/${DownloadStorage.DIR}") }
    private var now = START
    private val fetch = FakeImageFetch()
    private val artwork = MutableStateFlow(true)

    private fun TestScope.images() = DownloadImages(root, artwork, fetch, backgroundScope, { now }, io = Dispatchers.Unconfined)

    @Test
    fun `a download's poster and still are kept beside its marker, never in its video folder`() = runTest {
        val images = images()
        val episode = made(episode("e4", 4, DownloadState.Queued))
        val film = made(film("cc"))
        val bare = made(episode("e5", 5).copy(posterPath = null, stillPath = null))
        images.keep(listOf(episode, film, bare))

        assertEquals(listOf(posterUrl(POSTER), stillUrl(stillPath(4)), posterUrl(FILM_POSTER)), fetch.asked)
        assertContentEquals(FakeImageFetch.bytes(posterUrl(POSTER)), File(root, "e4/${Kind.Poster.file}").readBytes())
        assertContentEquals(FakeImageFetch.bytes(stillUrl(stillPath(4))), File(root, "e4/${Kind.Still.file}").readBytes())
        assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg", "still.jpg"), names("e4"))
        // A film has its poster alone; a row with no picture of TMDB's, nothing.
        assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg"), names("cc"))
        assertEquals(setOf(DownloadStorage.MARKER), names("e5"))
        // The video folders keep their videos alone.
        listOf("e4", "cc", "e5").forEach { assertEquals(setOf(VIDEO), File(volume, it).list()!!.toSet(), it) }
        // Its age is read from its time, the clock's as it was written.
        assertEquals(START, File(root, "e4/poster.jpg").lastModified())

        // Fresh: nothing asked again, at enqueue or after.
        images.keep(listOf(episode))
        images.enqueued(listOf(episode))
        runCurrent()
        assertEquals(3, fetch.asked.size)
    }

    @Test
    fun `a download whose folder is gone gets no picture, and no folder is made for it`() = runTest {
        val images = images()
        images.keep(listOf(episode("e6", 6)))
        assertFalse(File(root, "e6").exists())
        // A folder whose marker has gone (its download being deleted) neither.
        File(root, "e7").mkdirs()
        images.keep(listOf(episode("e7", 7)))
        assertEquals(emptySet(), names("e7"))
        assertEquals(emptyList(), fetch.asked)
    }

    @Test
    fun `a picture is written whole through a temp file, the one before kept where the new one can't be written`() = runTest {
        val images = images()
        val row = made(episode("e4", 4))
        val before = File(root, "e4/poster.jpg").apply {
            writeBytes(OLD)
            setLastModified(START - 40 * DAY)
        }
        // What no temp file can be written over: a folder of that name, with something in it.
        File(root, "e4/poster.jpg.tmp").mkdirs()
        File(root, "e4/poster.jpg.tmp/x").writeText("x")
        images.keep(listOf(row))

        assertEquals(listOf(posterUrl(POSTER), stillUrl(stillPath(4))), fetch.asked)
        assertContentEquals(OLD, before.readBytes(), "the poster before, never half of the new one")
        assertContentEquals(FakeImageFetch.bytes(stillUrl(stillPath(4))), File(root, "e4/still.jpg").readBytes())
        assertFalse(File(root, "e4/still.jpg.tmp").exists(), "no temp file left beside the still")
    }

    @Test
    fun `the stored copy is drawn while under 180 days old, the address after, and one 180 days old goes as it is read`() = runTest {
        val images = images()
        images.keep(listOf(made(episode("e4", 4))))
        val url = TmdbImages.poster(POSTER, 154)

        now = START + 180 * DAY - 1
        assertEquals(File(root, "e4/poster.jpg"), images.model("e4", Kind.Poster, url))
        assertEquals(File(root, "e4/still.jpg"), images.model("e4", Kind.Still, stillUrl(stillPath(4))))
        now = START + 180 * DAY
        assertEquals(url, images.model("e4", Kind.Poster, url))
        assertFalse(File(root, "e4/poster.jpg").exists())
        assertEquals(stillUrl(stillPath(4)), images.model("e4", Kind.Still, stillUrl(stillPath(4))))
        assertEquals(setOf(DownloadStorage.MARKER), names("e4"))
        // No copy: the address; no copy and no address: none.
        assertEquals(url, images.model("e9", Kind.Poster, url))
        assertNull(images.model("e9", Kind.Poster, null))
    }

    @Test
    fun `the start's sweep deletes the copies 180 days old and what a death left half written, and nothing else`() = runTest {
        val images = images()
        val old = made(episode("e4", 4))
        images.keep(listOf(old))
        now = START + 100 * DAY
        val young = made(episode("e5", 5))
        images.keep(listOf(young))
        File(root, "e5/still.jpg.tmp").writeText("half")
        File(root, "${DownloadNzbs.DIR}/abc").mkdirs()
        File(root, "${DownloadNzbs.DIR}/abc/meta").writeText("{}")
        File(root, "token").writeText("t")

        now = START + 180 * DAY
        images.sweep()
        assertEquals(setOf(DownloadStorage.MARKER), names("e4"))
        assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg", "still.jpg"), names("e5"))
        assertTrue(File(root, "${DownloadNzbs.DIR}/abc/meta").isFile)
        assertTrue(File(root, "token").isFile)
        assertEquals(emptyList(), fetch.asked.drop(4), "the sweep fetches nothing")
    }

    @Test
    fun `a finished download's copy missing or 30 days old is fetched again only while the app is in front and online`() = runTest {
        val images = images()
        val kept = made(episode("e1", 1))
        images.keep(listOf(kept))
        fetch.asked.clear()
        val missing = made(episode("e2", 2))
        val queued = made(episode("e3", 3, DownloadState.Queued))
        val done = MutableStateFlow(listOf(kept, missing, queued))
        val front = MutableStateFlow(false)
        val online = MutableStateFlow(true)
        now = START + 29 * DAY
        backgroundScope.launch { images.follow(done, front, online) }
        runCurrent()
        assertEquals(emptyList(), fetch.asked, "in the background")

        front.value = true
        runCurrent()
        // The missing one's; not one 29 days old, nor one still to finish.
        assertEquals(listOf(posterUrl(POSTER), stillUrl(stillPath(2))), fetch.asked)
        assertEquals(setOf(DownloadStorage.MARKER), names("e3"))

        now = START + 30 * DAY
        front.value = false
        runCurrent()
        front.value = true
        runCurrent()
        assertEquals(listOf(posterUrl(POSTER), stillUrl(stillPath(2)), posterUrl(POSTER), stillUrl(stillPath(1))), fetch.asked)
        assertEquals(now, File(root, "e1/poster.jpg").lastModified())

        // Offline: nothing, in front or not.
        now = START + 70 * DAY
        online.value = false
        front.value = false
        runCurrent()
        front.value = true
        runCurrent()
        assertEquals(4, fetch.asked.size)
        // Online again, in front: both, each 30 days old by now.
        online.value = true
        runCurrent()
        assertEquals(8, fetch.asked.size)
    }

    /**
     * L411: a refresh under way stops as the app leaves the front or goes offline, the picture it waits on kept nothing and
     * none asked after; what is left is fetched the next time it is in front and online.
     */
    @Test
    fun `a refresh under way stops as the app leaves the front or goes offline`() = runTest {
        val images = images()
        val rows = (1..3).map { made(episode("e$it", it)) }
        val front = MutableStateFlow(true)
        val online = MutableStateFlow(true)
        val answer = CompletableDeferred<Unit>()
        fetch.gate = answer
        backgroundScope.launch { images.follow(MutableStateFlow(rows), front, online) }
        runCurrent()
        assertEquals(listOf(posterUrl(POSTER)), fetch.asked, "waiting on the host's first answer")

        front.value = false
        runCurrent()
        answer.complete(Unit)
        runCurrent()
        assertEquals(listOf(posterUrl(POSTER)), fetch.asked, "nothing more asked in the background")
        rows.forEach { assertEquals(setOf(DownloadStorage.MARKER), names(it.id), it.id) }

        // In front again, then offline as the first answer is awaited.
        val again = CompletableDeferred<Unit>()
        fetch.gate = again
        front.value = true
        runCurrent()
        assertEquals(2, fetch.asked.size)
        online.value = false
        runCurrent()
        again.complete(Unit)
        runCurrent()
        assertEquals(2, fetch.asked.size, "nothing more asked offline")

        // Online again: every one fetched.
        fetch.gate = null
        online.value = true
        runCurrent()
        rows.forEach { assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg", "still.jpg"), names(it.id), it.id) }
    }

    /**
     * L411: the refresh reads the device's network as it is, from the app's start, not the pages' offline signal, which
     * reads as online for its first three seconds (OfflineSignal). Nothing in a JVM test makes the container, so its
     * source is read.
     */
    @Test
    fun `the container's refresh follows the device's network itself`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/UsenetApplication.kt").readText()
        assertEquals(1, Regex(Regex.escape("downloadImages.follow(downloads.done.map { it.values.toList() }, foreground, validatedNetwork)")).findAll(source).count())
    }

    @Test
    fun `nothing is fetched while Show artwork is off, and the copies go as it is switched off, with no restart`() = runTest {
        val images = images()
        val episode = made(episode("e1", 1))
        val film = made(film("cc"))
        images.keep(listOf(episode, film))
        fetch.asked.clear()
        val done = MutableStateFlow(listOf(episode, film))
        val front = MutableStateFlow(true)
        backgroundScope.launch { images.follow(done, front, MutableStateFlow(true)) }
        runCurrent()
        assertEquals(emptyList(), fetch.asked, "fresh copies")

        artwork.value = false
        runCurrent()
        assertEquals(setOf(DownloadStorage.MARKER), names("e1"))
        assertEquals(setOf(DownloadStorage.MARKER), names("cc"))
        // While it is off: nothing at enqueue, as a download is kept, nor as the app comes to the front.
        val queued = made(episode("e2", 2, DownloadState.Queued))
        images.enqueued(listOf(queued))
        images.keep(listOf(episode))
        front.value = false
        runCurrent()
        front.value = true
        runCurrent()
        assertEquals(emptyList(), fetch.asked)
        assertEquals(setOf(DownloadStorage.MARKER), names("e2"))
        assertEquals(TmdbImages.poster(POSTER, 154), images.model("e1", Kind.Poster, TmdbImages.poster(POSTER, 154)))

        // On again: the finished ones' fetched again, in front and online.
        artwork.value = true
        runCurrent()
        assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg", "still.jpg"), names("e1"))
        assertEquals(setOf(DownloadStorage.MARKER, "poster.jpg"), names("cc"))
    }

    @Test
    fun `a picture still being fetched as Show artwork is switched off is never written`() = runTest {
        val images = images()
        val row = made(episode("e1", 1, DownloadState.Queued))
        backgroundScope.launch { images.follow(MutableStateFlow(emptyList()), MutableStateFlow(false), MutableStateFlow(true)) }
        val answer = CompletableDeferred<Unit>()
        fetch.gate = answer
        images.enqueued(listOf(row))
        runCurrent()
        assertEquals(listOf(posterUrl(POSTER)), fetch.asked, "waiting on the host's answer")

        artwork.value = false
        runCurrent()
        answer.complete(Unit)
        runCurrent()
        assertEquals(setOf(DownloadStorage.MARKER), names("e1"))
        assertEquals(listOf(posterUrl(POSTER)), fetch.asked, "nor the still asked after")
    }

    @Test
    fun `a picture the host doesn't give keeps nothing, said by the download's id and the picture alone`() = runTest {
        val images = images()
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        fetch.none = setOf(posterUrl(POSTER))
        fetch.failing = setOf(stillUrl(stillPath(4)))
        images.keep(listOf(made(episode("e4", 4))))
        assertEquals(listOf(posterUrl(POSTER), stillUrl(stillPath(4))), fetch.asked)
        assertEquals(setOf(DownloadStorage.MARKER), names("e4"))
        // The other download's still kept all the same.
        images.keep(listOf(made(episode("e5", 5))))
        assertEquals(setOf(DownloadStorage.MARKER, "still.jpg"), names("e5"))

        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(lines.any { "e4" in it && "still" in it }, lines.toString())
        assertEquals(emptyList(), lines.filter { line -> listOf(POSTER, stillPath(4), "image.host.invalid", "Harbor Lights").any { it in line } })
    }

    /** [row]'s bookkeeping folder with its marker, as the storage makes it, and its video folder with its video. */
    private fun made(row: Download): Download {
        File(root, row.id).mkdirs()
        File(root, "${row.id}/${DownloadStorage.MARKER}").createNewFile()
        File(volume, row.id).mkdirs()
        File(volume, "${row.id}/$VIDEO").writeBytes(ByteArray(16))
        return row
    }

    private fun names(id: String): Set<String> = File(root, id).list().orEmpty().toSet()

    private fun episode(id: String, number: Int, state: DownloadState = DownloadState.Done) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, 2, number), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW, season = 2,
        episode = number, posterPath = POSTER, stillPath = stillPath(number), state = state, position = number.toLong(), createdAt = 0, updatedAt = 0,
    )

    private fun film(id: String) = Download(
        id = id, watchKey = WatchKey.movie(MOVIE), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = MOVIE, posterPath = FILM_POSTER,
        state = DownloadState.Done, position = 9, createdAt = 0, updatedAt = 0,
    )

    /**
     * The image host as a test has it: each address asked, in order, answered with bytes of its own; none for those in
     * [none], a failure naming the host for those in [failing], and only once [gate] (where one is set) is complete.
     */
    private class FakeImageFetch : ImageFetch {
        val asked = CopyOnWriteArrayList<String>()
        var none = emptySet<String>()
        var failing = emptySet<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(url: String): ByteArray? {
            asked += url
            gate?.await()
            if (url in failing) throw IOException("image.host.invalid didn't answer for $url")
            return if (url in none) null else bytes(url)
        }

        companion object {
            fun bytes(url: String) = "jpeg of $url".encodeToByteArray()
        }
    }

    private companion object {
        const val DAY = DownloadImages.DAY_MILLIS

        /** Whole seconds, so a file's time keeps it on any file system. */
        const val START = 1_800_000_000_000L
        const val SHOW = 990_901
        const val MOVIE = 990_902
        const val POSTER = "/harbor-lights-poster.jpg"
        const val FILM_POSTER = "/copper-crown-poster.jpg"
        const val VIDEO = "v.mkv"
        val OLD = "the poster before".encodeToByteArray()

        fun stillPath(episode: Int) = "/harbor-lights-s02e0$episode.jpg"

        fun posterUrl(path: String) = TmdbImages.poster(path, DownloadImages.POSTER_WIDTH)!!

        fun stillUrl(path: String) = TmdbImages.still(path, DownloadImages.STILL_WIDTH)!!
    }
}

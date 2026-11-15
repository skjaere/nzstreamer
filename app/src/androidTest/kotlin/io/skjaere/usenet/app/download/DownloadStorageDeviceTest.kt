package io.skjaere.usenet.app.download

import android.os.storage.StorageManager
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.LibraryDatabase
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Where a download's files live and how they come and go (L346, L348, L350, C8), on this device's own storage: the video in
 * the app's folder on the primary volume, `.part` until it is renamed, beside a `.nomedia`; the bookkeeping in filesDir,
 * its marker made with the row, its NZB and password while it needs them; the whole size allocated before the first byte;
 * reconcile at start; and a delete that a process dying part way through leaves for the next start to finish.
 *
 * The places are checked on the real folders, with ids of the test's own, deleted after. Reconcile and the deletes run over
 * folders of the test's own, so they never meet the app's own downloads. Files are 16 MB at most.
 */
@RunWith(AndroidJUnit4::class)
class DownloadStorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = LibraryDatabase.inMemory(context)
    private val downloads = db.downloads()
    private val storageManager = context.getSystemService(StorageManager::class.java)

    // The test's own places, in the app's private storage as the real ones are.
    private val root = File(context.filesDir, "download-storage-test")
    private val files = File(root, "files")
    private val volume = File(root, "volume").apply { mkdirs() }

    // Ids the test made in the real places, deleted after.
    private val realIds = mutableListOf<String>()

    private fun storage(volumeDir: () -> File? = { volume }, afterDeleteStep: (DownloadStorage.DeleteStep) -> Unit = {}) =
        DownloadStorage(files, volumeDir, downloads, storageManager, afterDeleteStep = afterDeleteStep)

    @After
    fun cleanUp() {
        db.close()
        root.deleteRecursively()
        val external = context.getExternalFilesDirs(null).first()
        realIds.forEach { id ->
            File(context.filesDir, "downloads/$id").deleteRecursively()
            File(external, "downloads/$id").deleteRecursively()
        }
    }

    @Test
    fun theVideoIsAPartFileInTheAppsFolderOnThePrimaryVolumeUntilRenamedAndNeverNamedAfterTheRelease() = runBlocking<Unit> {
        val storage = DownloadStorage.of(context, downloads)
        val id = Download.newId().also(realIds::add)
        val external = context.getExternalFilesDirs(null)[0]
        storage.add(row(id))
        assertEquals(File(context.filesDir, "downloads/$id"), storage.bookkeeping(id))
        assertTrue("the marker, made with the row", File(context.filesDir, "downloads/$id/.row").isFile)
        assertEquals(File(external, "downloads/$id"), storage.videoFolder(id))

        val bytes = Random(7).nextBytes(MIB.toInt())
        storage.open(id, NAME, bytes.size.toLong()).use { it.write(ByteBuffer.wrap(bytes), 0) }
        val part = File(external, "downloads/$id/v.mkv.part")
        assertTrue(part.isFile)
        assertFalse(File(external, "downloads/$id/v.mkv").exists())
        assertTrue("no media scanner lists them", File(external, "downloads/.nomedia").isFile)

        val done = storage.finish(id, NAME)
        assertEquals(File(external, "downloads/$id/v.mkv"), done)
        assertFalse(part.exists())
        assertArrayEquals(bytes, done.readBytes())
        assertEquals("downloads/$id/v.mkv", storage.relativePath(id, NAME))
        assertEquals(done, storage.file(row(id).copy(relativePath = storage.relativePath(id, NAME))))
        // Nothing on disk names the release.
        assertEquals(listOf("v.mkv"), done.parentFile!!.list()!!.toList())
        // The extension is the file name's, as the cast server names its path; one a path can't hold is left off.
        assertEquals("downloads/$id/v.m2ts", storage.relativePath(id, "Copper.Crown.2019.BluRay-GRP.M2TS"))
        assertEquals("downloads/$id/v", storage.relativePath(id, "Copper Crown"))
        assertEquals("downloads/$id/v", storage.relativePath(id, "Copper.Crown.extension-too-long"))

        storage.delete(id)
        assertNull(downloads.get(id))
        assertFalse(File(external, "downloads/$id").exists())
        assertFalse(File(context.filesDir, "downloads/$id").exists())
    }

    /** C8: allocateBytes gives the file its whole length, its blocks reserved, before the first byte is written. */
    @Test
    fun theWholeSizeIsAllocatedBeforeTheFirstByteAndAReopenKeepsWhatWasWritten() = runBlocking<Unit> {
        val storage = DownloadStorage.of(context, downloads)
        val id = Download.newId().also(realIds::add)
        storage.add(row(id))
        val size = 16 * MIB
        val part = File(context.getExternalFilesDirs(null)[0], "downloads/$id/v.mkv.part")
        storage.open(id, NAME, size).use { channel ->
            assertEquals("the .part's length before its first byte", size, part.length())
            assertEquals(size, channel.size())
            val onDisk = Os.stat(part.path).st_blocks * 512
            assertTrue("its blocks reserved, not a sparse file: $onDisk bytes on disk", onDisk >= size)
            channel.write(ByteBuffer.wrap(ByteArray(4096) { 7 }), 8 * MIB)
        }
        // A resume opens it again: allocated again (nothing to add), what was written kept.
        storage.open(id, NAME, size).use { channel ->
            assertEquals(size, channel.size())
            val read = ByteBuffer.allocate(4096)
            channel.read(read, 8 * MIB)
            assertArrayEquals(ByteArray(4096) { 7 }, read.array())
        }
        // Before its row says its file is made it still needs its whole size; after, it holds it and needs nothing more.
        assertEquals(4096L, checkNotNull(storage.space()).queued)
        downloads.update(row(id).copy(size = size, relativePath = storage.relativePath(id, NAME)))
        val space = checkNotNull(storage.space())
        assertTrue(space.allocatable > 0)
        assertEquals(0L, space.queued)
        assertEquals(size, space.used)
    }

    @Test
    fun theNzbAndItsPasswordAreKeptBesideTheMarkerUntilDropped() = runBlocking<Unit> {
        val storage = storage()
        storage.add(row("kept"))
        val nzb = "<nzb><!-- Harbor Lights S02E04 --></nzb>".toByteArray()
        storage.keepSource("kept", nzb, "harbor-pw")
        assertEquals(setOf(".row", "nzb", "password"), storage.bookkeeping("kept").list()!!.toSet())
        val source = checkNotNull(storage.source("kept"))
        assertArrayEquals(nzb, source.nzb)
        assertEquals("harbor-pw", source.password)

        // Kept again without one (another release): no password left from the first.
        storage.keepSource("kept", nzb, null)
        assertNull(storage.source("kept")!!.password)

        // Done, failed or cancelled: both go, the folder and its marker stay as long as the row (L346).
        storage.dropSource("kept")
        assertEquals(setOf(".row"), storage.bookkeeping("kept").list()!!.toSet())
        assertNull(storage.source("kept"))
    }

    @Test
    fun reconcileDeletesFoldersWithNoRowDropsRowsWithoutTheirMarkerAndKeepsQueuedAndDoneRows() = runBlocking<Unit> {
        val storage = storage()
        // Queued: its marker, no NZB yet.
        val queued = row("queued").also { storage.add(it) }
        // Done: its marker and its file, its NZB and password dropped.
        storage.add(row("done"))
        storage.keepSource("done", NZB, "pw")
        storage.open("done", NAME, 4096).close()
        storage.finish("done", NAME)
        storage.dropSource("done")
        val done = finished("done", storage)
        // Done, its file gone since.
        storage.add(row("gone"))
        finished("gone", storage)
        // Running when the process went: its marker, its NZB and its .part.
        val running = row("running").copy(state = DownloadState.Running).also { storage.add(it) }
        storage.keepSource("running", NZB, null)
        storage.open("running", NAME, 4096).close()
        // From another device: the library's row came, its folder never did (L350).
        downloads.insert(row("moved").copy(state = DownloadState.Done, relativePath = storage.relativePath("moved", NAME)))
        // Folders no row has, in both places, and a file of the app's own beside them.
        File(files, "downloads/left-over").mkdirs()
        File(files, "downloads/left-over/nzb").writeBytes(NZB)
        File(volume, "downloads/left-video").mkdirs()
        File(volume, "downloads/left-video/v.mkv.part").writeBytes(ByteArray(1024))
        File(files, "downloads/.trace").writeText("ids, offsets and causes")

        storage.reconcile()

        assertEquals(listOf("done", "gone", "queued", "running"), downloads.all().first().map { it.id }.sorted())
        assertEquals(queued, downloads.get("queued"))
        assertEquals(done, downloads.get("done"))
        assertEquals(running, downloads.get("running"))
        val gone = downloads.get("gone")!!
        assertEquals(DownloadState.Failed to "The downloaded file is gone", gone.state to gone.reason)
        assertNull("it holds no file now", gone.relativePath)
        assertNull(downloads.get("moved"))

        assertTrue(File(files, "downloads/queued/.row").isFile)
        assertTrue(storage.file(done)!!.isFile)
        assertTrue(File(files, "downloads/running/nzb").isFile)
        assertTrue(File(volume, "downloads/running/v.mkv.part").isFile)
        assertFalse(File(files, "downloads/left-over").exists())
        assertFalse(File(volume, "downloads/left-video").exists())
        assertFalse(File(files, "downloads/moved").exists())
        assertTrue("files beside the folders stay", File(files, "downloads/.trace").isFile)
        assertTrue(File(volume, "downloads/.nomedia").isFile)
    }

    /**
     * L379, 3-storage's cleanup: a delete goes the row Deleting (hidden), the video folder, the bookkeeping folder, the row,
     * so a process that dies after any step leaves a row the next start's reconcile finishes, and never a file without one.
     */
    @Test
    fun aDeleteStoppedAfterEachStepIsFinishedAtTheNextStart() = runBlocking<Unit> {
        DownloadStorage.DeleteStep.entries.forEach { step ->
            val id = "stopped-after-${step.name.lowercase()}"
            val dying = storage(afterDeleteStep = { if (it == step) throw ProcessDied() })
            dying.add(row(id))
            dying.keepSource(id, NZB, "pw")
            dying.open(id, NAME, 4096).close()

            assertThrows(ProcessDied::class.java) { runBlocking { dying.delete(id) } }
            assertEquals("$step: hidden at once", DownloadState.Deleting, downloads.get(id)?.state)
            assertEquals("$step: the video", step < DownloadStorage.DeleteStep.VideoGone, dying.videoFolder(id)!!.exists())
            assertEquals("$step: the bookkeeping", step < DownloadStorage.DeleteStep.BookkeepingGone, dying.bookkeeping(id).exists())

            // The next start.
            storage().reconcile()
            assertNull("$step: the row", downloads.get(id))
            assertFalse("$step: the video", dying.videoFolder(id)!!.exists())
            assertFalse("$step: the bookkeeping", dying.bookkeeping(id).exists())
        }
    }

    /** L388: with the volume's folder missing (unmounted), nothing is written and no download is taken for gone. */
    @Test
    fun aVolumeThatIsntThereMakesNoFileFailsNoDownloadAndHoldsADeleteUntilItIsBack() = runBlocking<Unit> {
        val there = storage()
        there.add(row("done"))
        there.open("done", NAME, 4096).close()
        there.finish("done", NAME)
        val done = finished("done", there)

        val away = storage(volumeDir = { null })
        assertNull(away.videoFolder("done"))
        assertNull(away.space())
        assertThrows(DownloadStorage.Unavailable::class.java) { runBlocking { away.open("other", NAME, 4096) } }
        away.reconcile()
        assertEquals(done, downloads.get("done"))

        // Removed meanwhile: hidden, its files kept until the volume is back, then the next reconcile finishes it.
        away.delete("done")
        assertEquals(DownloadState.Deleting, downloads.get("done")?.state)
        assertTrue(there.file(done)!!.isFile)
        assertTrue(there.bookkeeping("done").isDirectory)
        there.reconcile()
        assertNull(downloads.get("done"))
        assertFalse(there.videoFolder("done")!!.exists())
        assertFalse(there.bookkeeping("done").exists())
    }

    /** [id]'s row as finished, its file at its place; what the row now is. */
    private suspend fun finished(id: String, storage: DownloadStorage): Download {
        val row = downloads.get(id)!!.copy(
            state = DownloadState.Done, fileName = NAME, size = 4096, safeOffset = 4096, relativePath = storage.relativePath(id, NAME), completedAt = 20,
            updatedAt = 20,
        )
        downloads.update(row)
        return row
    }

    private fun row(id: String) = Download(
        id = id, watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", subtitle = "S02E04",
        showTmdbId = 900002, season = 2, episode = 4, position = 0, indexerSize = 4096, createdAt = 10, updatedAt = 10,
    )

    private class ProcessDied : RuntimeException("the process died here")

    private companion object {
        const val MIB = 1024L * 1024
        const val NAME = "Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv"
        val NZB = "<nzb><!-- Harbor Lights S02E04 --></nzb>".toByteArray()
    }
}

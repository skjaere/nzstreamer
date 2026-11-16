package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L352: what the player's download source is given for a `download://` id ([DownloadPlayFiles]): the Done row's file where
 * storage keeps it, its exact size, its gaps as file offsets, and the release's own name for the file (its container type).
 * A download not finished, removed, unknown, or whose file has no place now (its volume away) gives none. Names invented.
 */
class DownloadPlayFilesTest {
    private val done = Download(
        id = "3f2c9e1a", watchKey = WatchKey.episode(900002, 1, 5), kind = DownloadKind.Episode, title = "Harbor Lights", season = 1, episode = 5,
        state = DownloadState.Done, position = 0, fileName = "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL.mkv", size = 1_400_000_000,
        safeOffset = 1_400_000_000, gaps = listOf(DownloadGap(10_000, 19_999), DownloadGap(500_000, 500_099)),
        relativePath = "downloads/3f2c9e1a/v.mkv", createdAt = 1, completedAt = 2, updatedAt = 2,
    )
    private val volume = File("/volume")
    private val rows = mutableMapOf<String, Download>()

    /** Where storage keeps a row's finished file: under [volume] by its path, none while [volumeThere] is false. */
    private var volumeThere = true
    private val files = DownloadPlayFiles(Rows(rows), fileOf = { row -> row.relativePath?.takeIf { volumeThere }?.let { File(volume, it) } })

    @Test
    fun `a finished download gives its file, size, gaps and the release's name for the file`() {
        rows[done.id] = done
        val found = assertNotNull(files.find(done.id))
        assertEquals(File(volume, "downloads/3f2c9e1a/v.mkv"), found.file)
        assertEquals(1_400_000_000, found.size)
        assertEquals(listOf(10_000L..19_999L, 500_000L..500_099L), found.gaps)
        assertEquals("Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL.mkv", found.name)
        // Printed by a slip, it names no release.
        assertFalse("Harbor" in found.toString(), found.toString())
    }

    /**
     * A finished download whose file is gone (deleted from outside the app while it runs) is told as gone, so its row fails
     * as the next start's reconcile would fail it, and its file is given still, for the source to say it is gone. A file
     * there isn't, nor one with no place now (its volume away), which may come back.
     */
    @Test
    fun `a finished download whose file is gone is told as gone, its file given still for the source to say so`(@TempDir dir: File) {
        val told = mutableListOf<String>()
        val video = File(dir, "v.mkv")
        val here = DownloadPlayFiles(Rows(rows), { row -> row.relativePath?.takeIf { volumeThere }?.let { video } }) { told += it.id }
        rows[done.id] = done
        assertEquals(video, here.find(done.id)?.file)
        assertEquals(listOf(done.id), told)
        video.writeBytes(ByteArray(16))
        assertNotNull(here.find(done.id))
        volumeThere = false
        assertNull(here.find(done.id))
        assertEquals(listOf(done.id), told)
    }

    @Test
    fun `a download not finished, removed or unknown gives none`() {
        DownloadState.entries.filter { it != DownloadState.Done }.forEach { state ->
            rows[done.id] = done.copy(state = state)
            assertNull(files.find(done.id), "$state")
        }
        assertNull(files.find("not-a-download"))
    }

    @Test
    fun `a finished download whose file has no place now gives none`() {
        rows[done.id] = done
        volumeThere = false
        assertNull(files.find(done.id))
        volumeThere = true
        rows[done.id] = done.copy(relativePath = null)
        assertNull(files.find(done.id))
        rows[done.id] = done.copy(size = null)
        assertNull(files.find(done.id))
    }

    /** The table as [get] reads it; nothing else is asked. */
    private class Rows(private val rows: Map<String, Download>) : DownloadDao {
        override suspend fun get(id: String) = rows[id]
        override fun queue(): Flow<List<Download>> = emptyFlow()
        override fun all(): Flow<List<Download>> = emptyFlow()
        override fun done(): Flow<List<Download>> = emptyFlow()
        override suspend fun doneFor(watchKey: String): Download? = error("not asked")
        override fun forShow(showTmdbId: Int): Flow<List<Download>> = emptyFlow()
        override fun forMovie(movieTmdbId: Int): Flow<Download?> = emptyFlow()
        override suspend fun insert(download: Download) = error("not asked")
        override suspend fun insertAll(downloads: List<Download>, putBack: List<Download>) = error("not asked")
        override suspend fun update(download: Download) = error("not asked")
        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) = error("not asked")
        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) = error("not asked")
        override suspend fun move(id: String, position: Long) = error("not asked")
        override suspend fun delete(id: String) = error("not asked")
    }
}

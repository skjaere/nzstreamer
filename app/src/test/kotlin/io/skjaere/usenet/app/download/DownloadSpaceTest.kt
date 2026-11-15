package io.skjaere.usenet.app.download

import io.skjaere.usenet.core.MIN_FREE_BYTES
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What downloads take of the device's storage and what a new one may take (L348): what is free on the volume (or what
 * Android can allocate, where less), less the 1 GB the video cache leaves free, less what the queued downloads still need. A download whose file is made holds its
 * whole size already (its allocation before the first byte, C8), so the running one counts whole and needs nothing more.
 * The numbers are the Download sheet's and the Downloads page's strip's. Sizes in GB of 10^9 bytes, as the sheet and the page count them.
 */
class DownloadSpaceTest {
    @Test
    fun `free space is what Android can allocate less the 1 GB kept free and what the queued downloads still need`() {
        val space = DownloadSpace.of(
            allocatable = 20 * GB,
            usable = 20 * GB,
            rows = listOf(
                // Not resolved yet: the indexer's size.
                row("a", DownloadState.Queued, indexerSize = 1_200_000_000),
                // Resolved, its file not made yet: the exact size, not the indexer's.
                row("b", DownloadState.Queued, size = 2 * GB, indexerSize = 2_100_000_000),
                row("c", DownloadState.Done, size = 4 * GB, file = true),
            ),
        )
        assertEquals(20 * GB, space.available)
        assertEquals(3_200_000_000, space.queued)
        assertEquals(4 * GB, space.used)
        assertEquals(15_800_000_000, space.free)
        // The video cache's own reserve, so downloads never make Android clear it or TMDB's cache (C14).
        assertEquals(MIN_FREE_BYTES, DownloadSpace.KEPT_FREE)
        assertEquals(1_000_000_000, DownloadSpace.KEPT_FREE)
    }

    @Test
    fun `the running download counts whole, as its file has its whole size from before its first byte`() {
        val running = row("run", DownloadState.Running, size = 3_100_000_000, indexerSize = 3_200_000_000, file = true, safeOffset = 1_300_000_000)
        val space = DownloadSpace.of(41 * GB, 41 * GB, listOf(running, row("q", DownloadState.Queued, indexerSize = 1_200_000_000)))
        // The strip: "23.4 GB · 41 GB free" counts it at its 3.1 GB, not the 1.3 GB written so far.
        assertEquals(3_100_000_000, space.used)
        // Its remaining 1.8 GB is allocated already: only the queued one's 1.2 GB is set aside from what Android can give.
        assertEquals(1_200_000_000, space.queued)
        assertEquals(41 * GB - GB - 1_200_000_000, space.free)

        // While it resolves, before its file is made, it still needs its whole size, as a queued one does.
        val resolving = DownloadSpace.of(41 * GB, 41 * GB, listOf(running.copy(state = DownloadState.Resolving, relativePath = null, safeOffset = 0)))
        assertEquals(0, resolving.used)
        assertEquals(3_100_000_000, resolving.queued)
    }

    @Test
    fun `a held download keeps what it set aside, and a failed one without its file or one being deleted sets aside nothing`() {
        val space = DownloadSpace.of(
            allocatable = 30 * GB,
            usable = 30 * GB,
            rows = listOf(
                // Paused and stopped keep their file and its whole size (C8); one that never started still needs its own.
                row("paused", DownloadState.Paused, size = 2 * GB, file = true, safeOffset = GB),
                row("stopped", DownloadState.Stopped, indexerSize = 3 * GB),
                row("waiting", DownloadState.Waiting, indexerSize = 500_000_000),
                // "Storage is full" keeps its file, to go on from its offset (L382).
                row("full", DownloadState.Failed, size = 6 * GB, file = true, safeOffset = 5 * GB),
                // Failed without a file: nothing until Try again, whose Resolve checks the space again.
                row("failed", DownloadState.Failed, indexerSize = 7 * GB),
                // Removed: hidden at once, its file gone once its Undo has (L379); Android counts it until then.
                row("removed", DownloadState.Deleting, size = 8 * GB, file = true),
                row("done", DownloadState.Done, size = GB, file = true),
            ),
        )
        assertEquals(2 * GB + 6 * GB + GB, space.used)
        assertEquals(3 * GB + 500_000_000, space.queued)
        assertEquals(30 * GB - GB - 3_500_000_000, space.free)
    }

    @Test
    fun `a download fits in what is free, to the byte, and free is never below nothing`() {
        val space = DownloadSpace.of(10 * GB, 10 * GB, listOf(row("q", DownloadState.Queued, indexerSize = 4 * GB)))
        assertEquals(5 * GB, space.free)
        assertTrue(space.fits(5 * GB))
        assertTrue(space.fits(1))
        assertFalse(space.fits(5 * GB + 1))

        // Less than the reserve and the queue: nothing is free, and nothing fits.
        val full = DownloadSpace.of(GB + 100, GB + 100, listOf(row("q", DownloadState.Queued, indexerSize = 4 * GB)))
        assertEquals(0, full.free)
        assertFalse(full.fits(1))
        assertEquals(0, DownloadSpace.of(0, 0, emptyList()).free)
    }

    /**
     * Android's allocatable bytes count the caches it would clear to make room, this app's watched video and TMDB's
     * answers among them. Downloads take only what is free on the volume, so an allocation never clears them: here 2 GB
     * free and 15 GB of caches Android could clear.
     */
    @Test
    fun `what is free never counts the caches Android would clear to make room`() {
        val space = DownloadSpace.of(allocatable = 16_500_000_000, usable = 2 * GB, rows = emptyList())
        assertEquals(2 * GB, space.available)
        assertEquals(GB, space.free)
        assertFalse(space.fits(10 * GB))
        // Where Android keeps more of what is free for itself than it could clear, what it can allocate is the bound.
        assertEquals(GB, DownloadSpace.of(allocatable = 2 * GB, usable = 3 * GB, rows = emptyList()).free)
    }

    /**
     * Resolve checks the exact size against the space with the row it resolves among the rows, Resolving: its own size
     * is what is checked, so it is not also set aside from what is free. A 25 GB film fits in 39 GB free.
     */
    @Test
    fun `the download a check is for is left out of what is set aside`() {
        val film = row("film", DownloadState.Resolving, size = 25 * GB, indexerSize = 26 * GB)
        val rows = listOf(film, row("q", DownloadState.Queued, indexerSize = 2 * GB), row("done", DownloadState.Done, size = GB, file = true))
        val space = DownloadSpace.of(42 * GB, 42 * GB, rows, except = "film")
        assertEquals(2 * GB, space.queued)
        assertEquals(GB, space.used)
        assertEquals(39 * GB, space.free)
        assertTrue(space.fits(25 * GB))
        // Counted with the others, its own size would be taken from what is free twice.
        assertFalse(DownloadSpace.of(42 * GB, 42 * GB, rows).fits(25 * GB))
    }

    /**
     * Try again on a download whose file is made (from its safe offset, after "Storage is full" or a failed resolve)
     * resolves it once more, and Resolve checks its exact size again. Its file was allocated whole before its first byte
     * (C8), so what is free on the volume is less its whole size already, and allocating it again asks only for what it
     * lacks: none of it. 17 GB free after its 25 GB file and 2 GB queued: it fits, as it did when its file was made.
     */
    @Test
    fun `the download a check is for counts what its file holds already as its own`() {
        val film = row("film", DownloadState.Resolving, size = 25 * GB, indexerSize = 26 * GB, file = true, safeOffset = 9 * GB)
        val rows = listOf(film, row("q", DownloadState.Queued, indexerSize = 2 * GB))
        val retry = DownloadSpace.of(17 * GB, 17 * GB, rows, except = "film")
        assertTrue(retry.fits(25 * GB), "its 25 GB, its file made, with 17 GB free: free ${retry.free}")
        assertEquals(0, retry.used)
        assertEquals(2 * GB, retry.queued)
        assertEquals(17 * GB + 25 * GB, retry.available)
        assertEquals(39 * GB, retry.free)
        // Counted with the others, it holds its file, and its whole size again doesn't fit in the 14 GB left.
        val counted = DownloadSpace.of(17 * GB, 17 * GB, rows)
        assertEquals(25 * GB, counted.used)
        assertEquals(14 * GB, counted.free)
        assertFalse(counted.fits(25 * GB))
        // With no file made yet (its first resolve), it holds nothing: 25 GB doesn't fit in the 14 GB free.
        val first = DownloadSpace.of(17 * GB, 17 * GB, listOf(film.copy(relativePath = null, safeOffset = 0), rows[1]), except = "film")
        assertEquals(14 * GB, first.free)
        assertFalse(first.fits(25 * GB))
    }

    /**
     * DlPhoneWontFit's numbers: "✕ Won't fit on this device", "Needs about 4.3 GB · 3.0 GB free on this device", "That is
     * after the 1.2 GB the queued download still needs, and the 1 GB kept free for the phone."
     */
    @Test
    fun `the numbers the sheet says when a release won't fit`() {
        val space = DownloadSpace.of(5_200_000_000, 5_200_000_000, listOf(row("queued", DownloadState.Queued, indexerSize = 1_200_000_000)))
        assertEquals(3 * GB, space.free)
        assertEquals(1_200_000_000, space.queued)
        assertEquals(GB, DownloadSpace.KEPT_FREE)
        assertFalse(space.fits(4_300_000_000))
        // A release of 2.9 GB, "about" as the indexer says it, fits.
        assertTrue(space.fits(2_900_000_000))
        // Nothing downloaded or queued: the empty page's "41 GB free on this device".
        assertEquals(41 * GB, DownloadSpace.of(42 * GB, 42 * GB, emptyList()).free)
    }

    private fun row(id: String, state: DownloadState, size: Long? = null, indexerSize: Long = 0, file: Boolean = false, safeOffset: Long = 0) = Download(
        id = id, watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", state = state, position = 0,
        size = size, indexerSize = indexerSize, safeOffset = safeOffset, relativePath = if (file) "downloads/$id/v.mkv" else null, createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val GB = 1_000_000_000L
    }
}

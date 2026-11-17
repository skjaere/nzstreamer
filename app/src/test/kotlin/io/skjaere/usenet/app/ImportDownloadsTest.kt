package io.skjaere.usenet.app

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.download.DownloadColumns
import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadMark
import io.skjaere.usenet.app.download.DownloadPick
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.PageLine
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import's rows' downloads (L454, L455, L469): an NZB's downloads matched to its files, by the name and size each row's
 * pick keeps, then by its index, as the host's prepare may list the NZB's files otherwise; the line after a file's size by
 * state, in the episode rows' words; the button's menu by state; and where a Download button shows at all. Invented
 * names and sizes, from an invented Quiet Meridian season pack.
 */
class ImportDownloadsTest {
    private val saved = Locale.getDefault()

    // Sizes are said with the device's decimal mark: these read as written here.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val files = listOf(
        file(0, "Quiet.Meridian.S01E01.1080p.WEB-DL.H264.mkv", 1_400_000_000),
        file(1, "Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv", 1_300_000_000),
        file(2, "Quiet.Meridian.S01E03.1080p.WEB-DL.H264.mkv", 1_300_000_000),
        file(3, "Quiet.Meridian.S01.nfo", 2_000),
    )

    @Test
    fun `an NZB's downloads are matched to its files by name and size, then by index, and a file with none has none`() {
        val e1 = row("e1", files[0], DownloadState.Done)
        // Listed at another place by the host's prepare: found by its name and size all the same.
        val e2 = row("e2", files[1], DownloadState.Running, index = 2)
        // A name the listing no longer has: its index stands in.
        val e3 = row("e3", files[2], DownloadState.Failed, name = "Quiet.Meridian.S01E03.renamed.mkv")
        val other = row("other", files[0], DownloadState.Queued, nzbId = OTHER_NZB)
        val film = e1.copy(id = "film", kind = DownloadKind.Movie, watchKey = WatchKey.movie(7_808), movieTmdbId = 7_808, pick = null)
        val matched = ImportDownloads.of(NZB, files, listOf(e1, e2, e3, other, film).map(::item))
        assertEquals(mapOf(0 to "e1", 1 to "e2", 2 to "e3"), matched.mapValues { it.value.row.id })
        assertEquals(DownloadState.Running, matched.getValue(1).download.state)
        assertNull(matched[3], "the .nfo has none")
        assertEquals(emptyMap(), ImportDownloads.of(NZB, files, emptyList()))

        // A file downloaded twice: the latest added.
        val again = row("e1-again", files[0], DownloadState.Queued, created = 9)
        assertEquals("e1-again", ImportDownloads.of(NZB, files, listOf(e1, again).map(::item)).getValue(0).row.id)
        // Going tomorrow, as the runner says.
        assertTrue(ImportDownloads.of(NZB, files, listOf(item(e1)), deleting = setOf("e1")).getValue(0).download.deleting)
    }

    @Test
    fun `the line after a file's size says its state as the episode rows do, Downloaded alone once done`() {
        fun line(state: DownloadState?, reason: String? = null, percent: Int? = null, deleting: Boolean = false, gaps: Int = 0) =
            ImportDownloads.line(files[1], state?.let { TitleDownload("e2", it, reason, percent, 1_300_000_000, gaps, deleting = deleting, imported = true) })
        fun words(parts: List<PageLine>) = parts.joinToString("") { it.text }

        assertEquals(listOf(PageLine("1.3 GB", Tone.Muted)), line(null))
        assertEquals("1.3 GB · Queued for download", words(line(DownloadState.Queued)))
        assertEquals("1.3 GB · Downloading · 64%", words(line(DownloadState.Running, percent = 64)))
        assertEquals(Tone.Accent, line(DownloadState.Running, percent = 64).last().tone)
        assertEquals("1.3 GB · Download failed", words(line(DownloadState.Failed)))
        assertEquals(Tone.Warn, line(DownloadState.Failed).last().tone)
        assertEquals("1.3 GB · Downloaded", words(line(DownloadState.Done)))
        assertEquals("1.3 GB · Downloaded · Deleting tomorrow", words(line(DownloadState.Done, deleting = true)))
        assertEquals("1.3 GB · Downloaded · 2 parts missing", words(line(DownloadState.Done, gaps = 2)))
        assertEquals(Tone.Warn, line(DownloadState.Done, gaps = 2).last().tone)
        // Removed, its Undo pending: as not downloaded.
        assertEquals(listOf(PageLine("1.3 GB", Tone.Muted)), line(DownloadState.Deleting))
        // Nothing is searched for an imported file (L454): its steps' words, never "Finding releases…".
        assertEquals("1.3 GB · Opening…", words(line(DownloadState.Resolving, reason = DownloadHostRules.OPENING)))
        assertEquals("1.3 GB · Checking availability", words(line(DownloadState.Resolving, reason = "Checking availability")))
        assertEquals("1.3 GB · Opening…", words(line(DownloadState.Resolving)))
        assertEquals("Opening", TitleDownloads.spoken(TitleDownload("e2", DownloadState.Resolving, DownloadHostRules.OPENING, imported = true)))
        // What isn't a video says so, and has no download.
        assertEquals(listOf(PageLine("2 KB · not a video", Tone.Muted)), ImportDownloads.line(files[3], null))
    }

    @Test
    fun `the button's menu once started is the episode row's by state, then Open Downloads`() {
        fun menu(state: DownloadState) = ImportDownloads.menu(TitleDownload("e2", state))
        assertEquals(listOf(DownloadEntry.Remove, DownloadEntry.OpenDownloads), menu(DownloadState.Queued))
        assertEquals(listOf(DownloadEntry.Pause, DownloadEntry.Remove, DownloadEntry.OpenDownloads), menu(DownloadState.Running))
        assertEquals(listOf(DownloadEntry.Remove, DownloadEntry.OpenDownloads), menu(DownloadState.Done))
        assertEquals(listOf(DownloadEntry.DownloadAgain, DownloadEntry.Remove, DownloadEntry.OpenDownloads), menu(DownloadState.Failed))
        assertEquals(listOf(DownloadEntry.Resume, DownloadEntry.Remove, DownloadEntry.OpenDownloads), menu(DownloadState.Paused))
        // Not started, or removed: the button opens the sheet, with no menu.
        assertTrue(ImportDownloads.opensSheet(null))
        assertTrue(ImportDownloads.opensSheet(TitleDownload("e2", DownloadState.Deleting)))
        assertFalse(ImportDownloads.opensSheet(TitleDownload("e2", DownloadState.Queued)))
        // Its mark: the arrow offered, then the state's.
        assertEquals(DownloadMark.Download, ImportDownloads.mark(null))
        assertEquals(DownloadMark.Done, ImportDownloads.mark(TitleDownload("e2", DownloadState.Done)))
        assertEquals("Download Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv", ImportDownloads.label(files[1]))
    }

    @Test
    fun `a Download button shows on each video, none on what isn't one nor on any file of a copy of a disc`() {
        val season = prepared("Quiet.Meridian.S01.1080p.WEB-DL.H264", files)
        assertEquals(listOf(true, true, true, false), files.map { ImportDownloads.offered(season, it) })
        val disc = listOf("Sample/copper.crown.sample.mkv" to 212_000_000L, "BDMV/STREAM/00800.m2ts" to 31_200_000_000L, "BDMV/index.bdmv" to 1_000L)
            .mapIndexed { i, (path, size) -> file(i, path, size) }
        val copy = prepared("Copper.Crown.2022.COMPLETE.BLURAY", disc)
        assertEquals(listOf(false, false, false), disc.map { ImportDownloads.offered(copy, it) })
    }

    private fun item(row: Download) = DownloadRunner.Item(row, row.safeOffset, null)

    /** [file]'s download in [state], as Import's Download queues it: of NZB [nzbId], its pick naming [name] at [index]. */
    private fun row(
        id: String,
        file: FileCandidate,
        state: DownloadState,
        nzbId: String = NZB,
        index: Int = file.index,
        name: String = file.name,
        created: Long = 1,
    ) = Download(
        id = id, watchKey = WatchKey.release(nzbId, index), kind = DownloadKind.Release, title = name, subtitle = "Quiet.Meridian.S01.1080p.WEB-DL.H264",
        state = state, position = 0, size = file.size.takeIf { state == DownloadState.Done }, indexerSize = file.size,
        safeOffset = if (state == DownloadState.Done) file.size else 0, gaps = emptyList<DownloadGap>(),
        relativePath = if (state == DownloadState.Done) "downloads/$id/v.mkv" else null, pick = DownloadColumns.text(DownloadPick(index, name, file.size, nzbId)),
        createdAt = created, updatedAt = created,
    )

    private companion object {
        const val NZB = "nzb-quiet-meridian"
        const val OTHER_NZB = "nzb-harbor-lights"

        fun file(index: Int, path: String, size: Long) = FileCandidate(index, StreamableFile(path, size, index, 0, 0, 0))

        fun prepared(title: String, files: List<FileCandidate>) = PreparedNzb(
            PreparedNzb.idFor(title.toByteArray()), title,
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())), files,
        )
    }
}

package io.skjaere.usenet.app

import io.skjaere.usenet.app.download.AutoDelete
import io.skjaere.usenet.app.download.DownloadColumns
import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadMark
import io.skjaere.usenet.app.download.DownloadRequest
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.PageLine
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState

/** A file's download on Import: its [row] as the runner keeps it, and what its row says of it ([download], a title's words). */
data class ImportDownload(val row: Download, val download: TitleDownload)

/**
 * Import's rows' downloads (L454, L455, L469): which of the NZB's files are downloaded or on their way, what
 * each row says of it after the file's size in the episode rows' words, and what its 48 dp button offers. Pure, so the JVM
 * reads every case; the screen only draws it.
 */
object ImportDownloads {
    private const val SEP = " · "

    /**
     * The downloads of NZB [nzbId]'s [files], by each file's index: the runner's rows ([items]) of files opened on Import
     * whose pick names that NZB, matched to their file by the name and size the pick keeps, else by its index, as the
     * host's prepare may list the NZB's files otherwise; where a file has two, the latest added. Each finished one among
     * [deleting] goes tomorrow (L415). A file with no row has none.
     */
    fun of(nzbId: String, files: List<FileCandidate>, items: List<DownloadRunner.Item>, deleting: Set<String> = emptySet()): Map<Int, ImportDownload> = items
        .filter { it.row.kind == DownloadKind.Release && DownloadColumns.pick(it.row)?.nzbId == nzbId }
        .sortedBy { it.row.createdAt }
        .mapNotNull { item -> fileOf(item.row, files)?.let { file -> file.index to ImportDownload(item.row, TitleDownload.of(item, item.row.id in deleting)) } }
        .toMap()

    /** [row]'s file among [files]: by the name and size its pick keeps, else at its index; null for a row of no file of them. */
    fun fileOf(row: Download, files: List<FileCandidate>): FileCandidate? {
        val pick = DownloadColumns.pick(row) ?: return null
        return files.firstOrNull { it.name == pick.name && it.size == pick.size } ?: files.firstOrNull { it.index == pick.index }
    }

    /** Whether [file] of [nzb] has a Download button (L455): a video, and no file of a copy of a disc, which plays as no single video. */
    fun offered(nzb: PreparedNzb, file: FileCandidate): Boolean = DownloadRequest.File.offered(nzb, file)

    /**
     * The line under [file]'s name: its size, "· not a video" for what isn't one; then its [download]'s state as an episode
     * row says it ("Queued for download", "Downloading · 64%", "Download failed"), "Downloaded" alone once done (the size is
     * said already), with its missing parts and "· Deleting tomorrow" where they are so. One removed, its Undo pending, says
     * nothing more, as a file not downloaded.
     */
    fun line(file: FileCandidate, download: TitleDownload?): List<PageLine> {
        val size = formatSize(file.size)
        if (!file.isVideo) return listOf(PageLine("$size$SEP$NOT_A_VIDEO", Tone.Muted))
        val state = when (download?.state) {
            null, DownloadState.Deleting -> emptyList()
            DownloadState.Done -> listOfNotNull(
                PageLine(TitleDownloads.DOWNLOADED, Tone.Muted),
                TitleDownloads.missing(download)?.let { PageLine("$SEP$it", Tone.Warn) },
                PageLine("$SEP${AutoDelete.DELETING_TOMORROW}", Tone.Muted).takeIf { download.deleting },
            )
            else -> TitleDownloads.line(download)
        }
        return if (state.isEmpty()) listOf(PageLine(size, Tone.Muted)) else listOf(PageLine("$size$SEP", Tone.Muted)) + state
    }

    /** Whether the button opens the Download sheet: no download of the file, or one removed with its Undo pending. */
    fun opensSheet(download: TitleDownload?): Boolean = download == null || download.state == DownloadState.Deleting

    /** The button's menu once started: what an episode's ⋮ offers of its download by state, then Open Downloads. */
    fun menu(download: TitleDownload): List<DownloadEntry> = TitleDownloads.menu(download)

    /** The button's mark: the arrow while the file can be downloaded, then its download's ring, tick or ⚠. */
    fun mark(download: TitleDownload?): DownloadMark = if (opensSheet(download)) DownloadMark.Download else TitleDownloads.mark(download!!)

    /** The button for TalkBack: "Download Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv", its state said after it. */
    fun label(file: FileCandidate): String = "Download ${file.name}"

    /** "not a video", after the size of a file that isn't one. */
    private const val NOT_A_VIDEO = "not a video"
}

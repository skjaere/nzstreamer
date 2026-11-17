package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.player.DownloadFile
import io.skjaere.usenet.player.DownloadFiles
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The finished downloads' files as the player, the probe and a cast read them (L352, L417,
 * [io.skjaere.usenet.player.DownloadDataSource]): the Done row of the download a `download://` URI names, read from the
 * library, so a player its launch brings back after the process died finds the file as one the app just started does. The
 * row gives the file ([fileOf], where storage keeps it), its exact size, the gaps it was written with, and the release's
 * own name for the file, which says its container type and is never logged. A row that isn't Done, or whose file has no
 * place now (its volume away), has none. A Done row whose file is gone (deleted from outside the app) is told to [gone],
 * which fails it as the next start's reconcile would, and its file is given still, for the source to say it is gone. A row
 * being deleted is served while its file is [held] (L413): removed while it plays, the play's next seek still finds its
 * file.
 *
 * One rule, run two ways (C5): [found] suspends, for a cast's load, which runs on the main thread and calls it on an IO
 * dispatcher; [find] blocks on it, for the player's and the probe's loading threads.
 */
class DownloadPlayFiles(
    private val downloads: DownloadDao,
    private val fileOf: (Download) -> File?,
    private val held: (String) -> Boolean = { false },
    private val gone: (Download) -> Unit = {},
) : DownloadFiles {
    /** Download [id]'s finished file, or none; it touches the disk, so never on the main thread. */
    suspend fun found(id: String): DownloadFile? {
        val row = downloads.get(id)?.takeIf { it.state == DownloadState.Done || (it.state == DownloadState.Deleting && held(id)) } ?: return null
        val size = row.size ?: return null
        val file = fileOf(row) ?: return null
        if (!file.isFile) gone(row)
        return DownloadFile(file, size, row.gaps.map { it.first..it.last }, row.fileName)
    }

    override fun find(id: String): DownloadFile? = runBlocking { found(id) }
}

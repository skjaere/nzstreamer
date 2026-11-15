package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.StreamGaps
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A finished download's file, as the app keeps it: the [file] on this device, of [size] bytes, the stretches no provider
 * had when it was written ([gaps], file offsets, as zeros in it), and the release's own [name] for the file, which says its
 * container type. It prints none of the file's name or path: a slip into a log line names no release.
 */
class DownloadFile(val file: File, val size: Long, val gaps: List<LongRange>, val name: String?) {
    override fun toString() = "DownloadFile($size bytes, ${gaps.size} gaps)"
}

/** Where the app keeps its finished downloads' files: [DownloadDataSource] asks for one by the download's id. */
fun interface DownloadFiles {
    /**
     * The finished download [id]'s file; null when there is none (no such download, or it isn't finished). Called on the
     * player's or the probe's loading thread, never the main one: it may block.
     */
    fun find(id: String): DownloadFile?
}

/**
 * The player's and the probe's source for a finished download (L352): the file of the download a `download://<id>` URI
 * names ([PlayUris.Target.Download]), as [files] finds it, read through Media3's [FileDataSource]. Nothing networked: no
 * read-ahead, and Media3 is told it is no network transfer.
 *
 * - Its container type is said from the file's [DownloadFile.name], as Usenet's source says it ([UsenetDataSource]).
 * - The stretches its download had no article for are zeros in the file, which Media3's extractors can't take: a read
 *   stops at such a stretch's first byte and the next fails with [GapReached], as a stream's does
 *   ([UsenetStreamReader]), so [GapSkippingExtractor] goes past it where the file's index allows.
 * - A download with no file (deleted, removed, or its volume away) opens as a file not there
 *   ([PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND]), which the load policy doesn't retry ([UsenetLoadErrorHandlingPolicy]);
 *   a file that ends short of its size fails as a read past its end, which Media3 doesn't retry either.
 *
 * Its log lines name the download by its id alone: never its file's name or path.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadDataSource(private val files: DownloadFiles) : BaseDataSource(/* isNetwork = */ false) {
    private val file = FileDataSource()
    private var uri: Uri? = null

    /** The download opened, by its id, until [close]. */
    private var id: String? = null
    private var name: String? = null
    private var gaps = StreamGaps()
    private var position = 0L
    private var bytesRemaining = 0L
    private var fileOpen = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val target = PlayUris.parse(dataSpec.uri.toString()) as? PlayUris.Target.Download
            ?: throw DataSourceException("Not a download URI", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val found = find(target.id)
            ?: throw DataSourceException("Download ${target.id} has no finished file", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        if (!found.file.isFile) throw DataSourceException("Download ${target.id}'s file is gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val at = dataSpec.position
        if (at < 0 || at > found.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) found.size - at else minOf(dataSpec.length, found.size - at)
        transferInitializing(dataSpec)
        // Merged where they touch, as a stream's are: a read stops at the first byte of the whole stretch.
        gaps = StreamGaps().apply { found.gaps.forEach(::add) }
        if (length > 0) {
            file.open(DataSpec.Builder().setUri(Uri.fromFile(found.file)).setPosition(at).setLength(length).build())
            fileOpen = true
        }
        id = target.id
        name = found.name
        position = at
        bytesRemaining = length
        log.debug("open download {} at {} for {} of {} bytes, {} gaps", target.id, at, length, found.size, found.gaps.size)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        // No further than the first byte of a gap ahead; at it, the read fails, saying which bytes are missing.
        val ahead = gaps.next(position)
        if (ahead != null && ahead.first <= position) throw reached(ahead)
        val toRead = minOf(length.toLong(), bytesRemaining, ahead?.let { it.first - position } ?: Long.MAX_VALUE).toInt()
        val n = file.read(buffer, offset, toRead)
        if (n == C.RESULT_END_OF_INPUT) {
            throw DataSourceException("Download $id's file ends at byte $position, $bytesRemaining short", null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /** The file's container type, the hint Media3's extractors' factory tries first, as Usenet's source gives it ([ContainerTypes]). */
    override fun getResponseHeaders(): Map<String, List<String>> =
        name?.let(ContainerTypes::mimeOf)?.let { mapOf(CONTENT_TYPE to listOf(it)) } ?: emptyMap()

    override fun close() {
        try {
            if (fileOpen) file.close()
        } finally {
            fileOpen = false
            uri = null
            id = null
            name = null
            bytesRemaining = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** [id]'s file as the app finds it, on this loading thread; its failure one Media3 loads again for. */
    private fun find(id: String): DownloadFile? = try {
        files.find(id)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Interrupted while finding download $id's file").apply { initCause(e) }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    /** The read is at [gap]'s first byte: logged by the download's id, as the failure the next step skips or fails on. */
    private fun reached(gap: LongRange): GapReached {
        log.info("Missing part of download {} reached at byte {}: no provider had bytes {}-{}", id, position, gap.first, gap.last)
        return GapReached(gap)
    }

    /** Makes a [DownloadDataSource] over [files] for each player and probe. */
    class Factory(private val files: DownloadFiles) : DataSource.Factory {
        override fun createDataSource(): DataSource = DownloadDataSource(files)
    }

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        val log = LoggerFactory.getLogger(DownloadDataSource::class.java)
    }
}

package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.StreamGaps
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetException
import io.skjaere.usenet.core.toUsenetError
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.slf4j.LoggerFactory
import java.io.InterruptedIOException

/**
 * Blocking, single-reader view of one byte range of a prepared NZB file. ExoPlayer calls
 * a DataSource from its loader thread: a read copies what the stream already holds, and only
 * one that must wait for it, like every other suspend call, is bridged with runBlocking.
 * A seek is `close()` followed by `open()` at the new position. [traffic] counts what each stream hands the
 * player, for the stream-cost line ([io.skjaere.usenet.core.StreamCost]).
 *
 * An article no provider has, a stream fills with zeros of its size and records where
 * ([OpenedStream.gaps]). Media3's extractors can't take zeros (a Matroska one fails on the first, an
 * H.264 one reads past the gap, a fragmented MP4 one ends the film early), so they never get one: a
 * read hands over the bytes in front of a gap and stops at its first byte, and the next fails with
 * [GapReached], a missing article, which the player's extractors go past where the file's index
 * allows ([GapSkippingExtractor]). What the reader learns of a file's gaps outlives its streams: one
 * reopened past a gap, or in front of it again, stops at it as well. Each stretch a stream finds, the
 * reader reports by file ([reports]), as found, for the player of that file to count.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsenetStreamReader(
    private val store: PreparedNzbStore,
    private val preparer: NzbPreparer,
    private val opener: StreamOpener,
    private val traffic: StreamTraffic? = null,
    private val reports: GapReports? = null,
) {
    private val log = LoggerFactory.getLogger(UsenetStreamReader::class.java)
    private var stream: OpenedStream? = null

    /** The bytes this stream has handed the player, for the stream-cost line: from its start (the first read) to [close]. */
    private var meter: StreamTraffic.Meter? = null

    /**
     * The stream [open] asked for, started by the first [read]. Media3 opens where an extractor seeks
     * before its loader waits, and the track probe's loader waits for good once it has the tracks: an
     * MP4's or an AVI's seek back from its index opened a stream nobody read, and one opened past a
     * file's start downloads its whole read-ahead at once.
     */
    private var pending: (suspend () -> OpenedStream)? = null
    private var bytesRemaining = 0L

    /** Where in the file the next byte read is: the position opened, and what was read since. */
    private var position = 0L

    /** The file whose gaps [gaps] are (the NZB's id and the file's index): the reader keeps them while it reads that file. */
    private var gapsOf: Pair<String, Int>? = null

    /** That file's name, for the reports. */
    private var gapsName: String? = null

    /** The file's gaps as its streams found them, merged. */
    private var gaps = StreamGaps()

    /** Reads since the last [open], and how many of them went through the blocking bridge. */
    internal var reads = 0L
        private set
    internal var blockingReads = 0L
        private set

    /** Name of the file the last successful [open] is reading, until [close]. */
    var fileName: String? = null
        private set

    /**
     * Returns the number of bytes that will be readable, as Media3's `DataSource.open` does. The file
     * is the one named [name], of [size] bytes, when the URI says ([io.skjaere.usenet.core.PreparedNzb.find]).
     */
    fun open(nzbId: String, fileIndex: Int, position: Long, requestedLength: Long, name: String? = null, size: Long? = null): Long {
        close()
        val nzb = when (val outcome = blocking { store.getOrPrepare(nzbId, preparer) }) {
            is PrepareOutcome.Ready -> outcome.nzb
            is PrepareOutcome.Failed -> throw dataSourceException(UsenetException(outcome.error))
            is PrepareOutcome.MissingArticles -> throw dataSourceException(UsenetException(UsenetError.MissingArticle(outcome.message)))
            // Prepared again after process death without what made it work (a password): loading
            // again only repeats it, so it reads as a file not there (UsenetLoadErrorHandlingPolicy).
            else -> throw DataSourceException("NZB $nzbId cannot be prepared: $outcome", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        val file = nzb.find(fileIndex, name, size)
            ?: throw DataSourceException("No file ${name?.let { "'$it'" } ?: fileIndex} in ${nzb.title}", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val total = file.size
        if (position < 0 || position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val length = if (requestedLength == C.LENGTH_UNSET.toLong()) total - position else minOf(requestedLength, total - position)
        bytesRemaining = length
        this.position = position
        if (gapsOf != nzbId to file.index) {
            gapsOf = nzbId to file.index
            gapsName = file.name
            gaps = StreamGaps()
        }
        log.debug("open {} file {} at {} for {} of {} bytes", nzbId.take(12), file.index, position, length, total)
        if (length > 0) {
            val range = position until position + length
            pending = { opener.open(nzb, file.index, range) }
        }
        fileName = file.name
        return length
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val opened = stream ?: pending?.let { start ->
            pending = null
            blocking { start() }.also {
                stream = it
                meter = traffic?.open()
                val known = gaps
                val file = gapsOf
                val name = gapsName
                it.gaps.listen { range ->
                    known.add(range)
                    if (file != null) reports?.found(GapReports.Found(file.first, file.second, name, range))
                }
            }
        } ?: throw IllegalStateException("read before open")
        val channel = opened.channel
        // No further than the first byte of a gap ahead; at it, the read fails, saying which bytes are missing.
        val ahead = gaps.next(position)
        if (ahead != null && ahead.first <= position) throw reached(ahead)
        val toRead = minOf(length.toLong(), bytesRemaining, ahead?.let { it.first - position } ?: Long.MAX_VALUE).toInt()
        reads++
        // What the stream already holds is copied without the blocking bridge: an extractor reads a
        // container's index a few bytes at a time (Matroska's Cues, megabytes in a long film), and a
        // round trip per read made that tens of seconds on a TV box (#224).
        val n = channel.readHeld(buffer, offset, toRead).takeIf { it > 0 } ?: run {
            blockingReads++
            blocking { channel.readAvailable(buffer, offset, toRead) }
        }
        if (n == -1) {
            // A writer that failed closes the channel with its cause; with Ktor 3 the reader
            // sees end-of-stream rather than the exception, so recover the cause here.
            channel.closedCause?.let { throw dataSourceException(it) }
            throw DataSourceException(
                "Stream ended with $bytesRemaining bytes outstanding",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        }
        // A gap found while this read waited: the stream records one before its zeros, so it is known by now. The read
        // ends at its start, and the zeros read past it go no further (the next read fails there).
        val found = gaps.next(position)
        val kept = if (found != null && found.first < position + n) (found.first - position).toInt() else n
        if (kept <= 0) throw reached(found!!)
        position += kept
        bytesRemaining -= kept
        meter?.add(kept)
        return kept
    }

    /** The read is at [gap]'s first byte: logged, as the failure the next step skips or fails on. */
    private fun reached(gap: LongRange): GapReached {
        log.info("Missing part of {} reached at byte {}: no provider has bytes {}-{}", fileName, position, gap.first, gap.last)
        return GapReached(gap)
    }

    fun close() {
        stream?.let {
            log.debug("close after {} reads, {} of them through the blocking bridge", reads, blockingReads)
            it.job.cancel()
            it.channel.cancel(null)
        }
        stream = null
        meter?.close()
        meter = null
        pending = null
        bytesRemaining = 0
        reads = 0
        blockingReads = 0
        fileName = null
    }

    /**
     * Copies up to [length] of the bytes this channel holds, without suspending; -1 when it holds
     * none, or is closed or closing (the blocking read then says how it ended).
     */
    private fun ByteReadChannel.readHeld(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosedForRead) return -1
        copier.into(buffer, offset, offset + length)
        return try {
            readAvailable(1, copier)
        } catch (e: Exception) {
            // Closed with a failure since: Ktor's buffer throws it, and the blocking read reports it.
            // Anything else is a fault of the channel's own, and it fails the read.
            if (closedCause == null) throw dataSourceException(e)
            -1
        } finally {
            copier.into(EMPTY, 0, 0)
        }
    }

    /**
     * Copies held bytes into the range [into] names, one for every read: a lambda capturing it
     * was allocated each read on ART, 150 MB of garbage for a 4K remux's index read a few bytes at a time.
     */
    private class HeldCopier : (Buffer) -> Int {
        private var target = EMPTY
        private var from = 0
        private var to = 0

        fun into(target: ByteArray, from: Int, to: Int) {
            this.target = target
            this.from = from
            this.to = to
        }

        override fun invoke(held: Buffer): Int = held.readAtMostTo(target, from, to)
    }

    private val copier = HeldCopier()

    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Interrupted while streaming").apply { initCause(e) }
    } catch (e: DataSourceException) {
        throw e
    } catch (e: Exception) {
        throw dataSourceException(e)
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun dataSourceException(e: Throwable): DataSourceException {
            val error = e.toUsenetError()
            val reason = when (error) {
                is UsenetError.MissingArticle -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                is UsenetError.Timeout -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                is UsenetError.ConnectionFailed, is UsenetError.Network -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                is UsenetError.AuthFailed -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
            return UsenetDataSourceException(error, e, reason)
        }
    }
}

/**
 * How a player error's cause chain classifies the failure: as the [UsenetDataSourceException]
 * in it says, else derived from the chain. The exception is an IOException itself, so deriving
 * past it would turn every failure it carries without a known cause into Network.
 */
fun Throwable.usenetError(): UsenetError =
    generateSequence(this) { it.cause }.take(16).filterIsInstance<UsenetDataSourceException>().firstOrNull()?.error ?: toUsenetError()

/** Keeps the classified [error] available to the UI via `PlaybackException.cause`. */
@androidx.annotation.OptIn(UnstableApi::class)
open class UsenetDataSourceException(val error: UsenetError, cause: Throwable?, reason: Int) :
    DataSourceException(error.detail ?: error::class.simpleName, cause, reason)

/**
 * A read reached [range], a stretch of the file no provider has, which its stream fills with zeros
 * ([io.skjaere.usenet.core.OpenedStream.gaps]), merged with the stretches beside it known so far. The
 * reader stops at its first byte, so the zeros never reach an extractor. It is a missing article, so
 * whatever can't skip it fails as a missing article did before zeros were filled in: the player
 * doesn't load it again ([UsenetLoadErrorHandlingPolicy]) and says part of the release is missing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GapReached(val range: LongRange) : UsenetDataSourceException(
    UsenetError.MissingArticle("No provider has bytes ${range.first}-${range.last} of the file"), null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
)

package io.skjaere.usenet.core

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A download's own file as a writer fills it, and its record (L345): the part both writers share, the Usenet one
 * ([DownloadWriter]) and the debrid one (L419). Bytes taken ([took]) go at the byte reached, [written]; a stretch can be
 * written again behind it ([writeAt]). One write at a time; it blocks on the file in place.
 *
 * **The record.** Each [recordBytes] (32 MiB) taken or [recordEvery] (10 s), and whenever its writer says ([save]), the
 * file is forced to storage, then [record] keeps the safe offset (every byte before it written and forced) with the
 * stretches [gaps] says are missing. A power cut can lose the last record, never put it ahead of the data. A
 * [rewind] takes it back to an earlier byte, the next save recording that: the debrid writer's restart from 0 when its
 * server won't resume (L419), the Usenet writer's long gap asked for again from its first byte.
 *
 * A write or force that fails throws [StorageFailure], sorted into the download's words: [STORAGE_FULL] for want of room
 * (ENOSPC, EFBIG), else [WRITE_FAILED]. Its rate ([rate]) and where it is ([said], to [live]) are what the download's
 * row reads. Its log line names the download by its id, with offsets, sizes and a cause's kind: never a cause's words,
 * which can name the file.
 */
class RecordedFile(
    /** The download's id: what its live and its log line name it by. */
    val id: String,
    private val out: FileChannel,
    /** The file's size, allocated whole before its first byte. */
    val size: Long,
    /** The first byte to write: every byte before it is written and recorded. */
    from: Long,
    /** The stretches missing so far, read at each save: none for a file that comes whole. */
    private val gaps: () -> List<LongRange> = { emptyList() },
    /** Hears where the write is: at each chunk taken and each [said]. */
    private val live: (DownloadWriter.Live) -> Unit = {},
    /** Keeps the safe offset and the gaps, after the file is forced. */
    private val record: suspend (safeOffset: Long, gaps: List<LongRange>) -> Unit,
    /** The record's and the rate's clock. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val recordBytes: Long = RECORD_BYTES,
    private val recordEvery: Duration = RECORD_EVERY,
) {
    /** What a stream is read into, [CHUNK] bytes at most: [took] and [writeAt] write from it. */
    val buffer = ByteArray(CHUNK)

    /** The write's bytes a second, as its row says them. */
    val rate = TransferRate(timeSource)

    /** Every byte before this is written. */
    @Volatile
    var written = from
        private set

    /** Whether the write's source is out of reach now, as its live says; bytes taken clear it. */
    var unreachable = false

    private var recorded = from
    private var recordedGaps = gaps()
    private var since = timeSource.markNow()

    /** [n] bytes of [buffer] came: written at [written], and recorded when the cadence says. */
    suspend fun took(n: Int) {
        writeAt(written, n, advance = true)
        rate.add(n.toLong())
        // Bytes come: the source is in reach.
        unreachable = false
        said()
        if (written - recorded >= recordBytes || since.elapsedNow() >= recordEvery) save()
    }

    /** Says where the write is ([live]). */
    fun said() {
        live(DownloadWriter.Live(id, written, unreachable))
    }

    /** Writes [n] bytes of [buffer] at [position]; with [advance], [written] goes on with each part written. */
    fun writeAt(position: Long, n: Int, advance: Boolean) {
        val bytes = ByteBuffer.wrap(buffer, 0, n)
        var at = position
        while (bytes.hasRemaining()) {
            at += try {
                out.write(bytes, at)
            } catch (e: IOException) {
                throw StorageFailure(e)
            }
            if (advance) written = at
        }
    }

    /**
     * Back to byte [to], or where it is when that is before it: the bytes from there are written again. The record follows
     * at the next [save].
     */
    fun rewind(to: Long = 0) {
        written = minOf(written, to)
    }

    /** Forces the file, then records [written] with what is missing: nothing when neither changed. */
    suspend fun save() {
        val at = written
        if (at == recorded && gaps() == recordedGaps) return
        try {
            out.force(false)
        } catch (e: IOException) {
            throw StorageFailure(e)
        }
        // Every stretch before [at] was added before its zeros came, so before they were written.
        val gaps = gaps()
        record(at, gaps)
        recorded = at
        recordedGaps = gaps
        since = timeSource.markNow()
    }

    /** [save], storage refusing it meaning only that the record stays where it was. */
    suspend fun saveQuietly() {
        try {
            save()
        } catch (e: StorageFailure) {
            log.warn("download {}: couldn't record {} of {} bytes: {}", id, written, size, if (e.full) "storage full" else e.cause::class.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't record {} of {} bytes: {}", id, written, size, e::class.simpleName)
        }
    }

    /**
     * A write or force of the download's file failed: [full] when for want of room (ENOSPC, EFBIG). Its own words are
     * fixed; the cause's, which can name the file, stay with the cause.
     */
    class StorageFailure(override val cause: IOException) : RuntimeException("The download's file couldn't be written", cause) {
        val full = cause.causeChain().any { c -> c.message?.let { message -> STORAGE_FULL_WORDS.any { it in message } } == true }

        /** The failed download's words for it. */
        val reason get() = if (full) STORAGE_FULL else WRITE_FAILED
    }

    companion object {
        /** The record at least this often in bytes (L345; M5 measures it). */
        const val RECORD_BYTES = 32L * 1024 * 1024

        /** The record at least this often in time. */
        val RECORD_EVERY = 10.seconds

        /** The failed download's words: the volume has no room for it (L382). */
        const val STORAGE_FULL = "Storage is full"

        /** The failed download's words: its file can't be written, for want of anything but room. */
        const val WRITE_FAILED = "Couldn't write the file"

        /** What a stream is read in at most. */
        private const val CHUNK = 256 * 1024

        /** How the JVM and Android say ENOSPC and EFBIG. */
        private val STORAGE_FULL_WORDS = listOf("ENOSPC", "No space left on device", "EFBIG", "File too large")

        private val log = LoggerFactory.getLogger(RecordedFile::class.java)
    }
}

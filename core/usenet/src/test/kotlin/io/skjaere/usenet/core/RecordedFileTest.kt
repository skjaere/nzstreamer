package io.skjaere.usenet.core

import io.skjaere.usenet.core.RecordedFile.StorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * A download's file as both writers fill it (L345, L419): bytes at their offsets, the record's cadence of 32 MiB or 10 s
 * on a test clock, the file forced before each record, a rewind to 0 for the debrid writer's restart, and storage's
 * failures sorted into the download's words. The file is the test's own channel in memory, or one that counts its bytes
 * and keeps none, so 32 MiB go through it with no file of that size.
 */
class RecordedFileTest {
    private val time = TestTimeSource()
    private val records = mutableListOf<Record>()
    private val lives = mutableListOf<DownloadWriter.Live>()

    /** The stretches missing that the writer would say: read at each save. */
    private var gaps = emptyList<LongRange>()

    /** A record the file made: its safe offset and gaps. */
    private data class Record(val offset: Long, val gaps: List<LongRange>)

    /** [disk] as a download's file from [from], its records kept in [records] and told to [disk] as they come. */
    private fun file(
        disk: Disk,
        from: Long = 0,
        recordBytes: Long = RecordedFile.RECORD_BYTES,
        record: suspend (Long, List<LongRange>) -> Unit = { offset, known ->
            disk.events += Disk.Recorded(offset)
            records += Record(offset, known)
        },
    ) = RecordedFile(
        ID, disk, disk.size, from, gaps = { gaps }, live = { lives += it }, record = record, timeSource = time, recordBytes = recordBytes,
    )

    @Test
    fun `bytes taken go at their offsets from the first, and a stretch written behind them moves nothing`() = runBlocking<Unit> {
        val disk = Disk(MIB)
        val data = pattern(MIB.toInt())
        val file = file(disk)
        file.take(data, 0 until 600_000L)
        assertEquals(600_000, file.written)
        // A stretch written again behind the safe offset, as the Usenet writer's end retry writes one.
        file.buffer.fill(7, 0, 50_000)
        file.writeAt(100_000, 50_000, advance = false)
        assertEquals(600_000, file.written)
        assertTrue(disk.bytes!!.copyOfRange(100_000, 150_000).all { it == 7.toByte() })
        data.fill(7, 100_000, 150_000)
        file.take(data, 600_000 until MIB)
        assertEquals(MIB, file.written)
        assertContentEquals(data, disk.bytes)
        // The channel took each chunk in slices: the bytes before each slice written were written first.
        assertTrue(disk.events.filterIsInstance<Disk.Wrote>().size > MIB / file.buffer.size, "writes ${disk.events.size}")
    }

    @Test
    fun `a record each 32 MiB of bytes taken, and each 10 s when they come slower`() = runBlocking<Unit> {
        assertEquals(32L * 1024 * 1024, RecordedFile.RECORD_BYTES)
        assertEquals(10.seconds, RecordedFile.RECORD_EVERY)
        // DN1's writer keeps its own names for them.
        assertEquals(RecordedFile.RECORD_BYTES, DownloadWriter.RECORD_BYTES)
        assertEquals(RecordedFile.RECORD_EVERY, DownloadWriter.RECORD_EVERY)

        // 80 MiB as fast as they come: a record at 32 and 64 MiB, none between.
        val fast = file(Disk(80 * MIB, keep = false))
        fast.takeZeros(80 * MIB)
        assertEquals(listOf(32 * MIB, 64 * MIB), records.map { it.offset })

        // Slower: a record once 10 s have passed since the last, at the next bytes taken.
        records.clear()
        val slow = file(Disk(MIB, keep = false))
        slow.takeZeros(1_000)
        time += 9_999.milliseconds
        slow.takeZeros(1_000)
        assertEquals(emptyList(), records)
        time += 1.milliseconds
        slow.takeZeros(1_000)
        assertEquals(listOf(Record(3_000, emptyList())), records)
        // The 10 s count again from that record.
        time += 5.seconds
        slow.takeZeros(1_000)
        assertEquals(1, records.size)
        time += 5.seconds
        slow.takeZeros(1_000)
        assertEquals(listOf(3_000L, 5_000L), records.map { it.offset })
    }

    @Test
    fun `the file is forced before each record, which never names a byte the force didn't cover`() = runBlocking<Unit> {
        val disk = Disk(4 * MIB)
        val data = pattern((4 * MIB).toInt())
        val file = file(disk, recordBytes = 512 * 1024) { offset, known ->
            assertTrue(offset <= disk.forced, "recorded $offset with ${disk.forced} forced")
            disk.events += Disk.Recorded(offset)
            records += Record(offset, known)
        }
        file.take(data, chunk = 100_000)
        file.save()
        assertTrue(records.size >= 6, "a record each 512 KiB or so: ${records.map { it.offset }}")
        assertEquals(4 * MIB, records.last().offset)
        disk.events.withIndex().filter { it.value is Disk.Recorded }.forEach { (i, _) ->
            assertEquals(Disk.Forced, disk.events[i - 1], "what came before the record at event $i")
        }
        assertContentEquals(data, disk.bytes)
    }

    @Test
    fun `a save records only what changed, the gaps the writer says among it`() = runBlocking<Unit> {
        val disk = Disk(MIB)
        val file = file(disk, from = 1_000)
        // Nothing taken since the record it started from: nothing to record.
        file.save()
        assertEquals(emptyList(), records)
        file.take(pattern(3_000), 1_000 until 3_000L)
        file.save()
        file.save()
        assertEquals(listOf(Record(3_000, emptyList())), records)
        // A stretch said missing, with no new byte: recorded, forced first.
        gaps = listOf(1_500L..1_999L)
        file.save()
        file.save()
        assertEquals(listOf(Record(3_000, emptyList()), Record(3_000, gaps)), records)
        assertEquals(Disk.Forced, disk.events[disk.events.lastIndex - 1])
    }

    // L419: the debrid writer's restart from 0 when its server won't resume, and the Usenet writer's long gap asked for
    // again from its first byte.
    @Test
    fun `rewound to 0, the next save records 0 and the bytes are written again from the first`() = runBlocking<Unit> {
        val disk = Disk(MIB)
        val first = pattern(MIB.toInt())
        val file = file(disk)
        file.take(first, 0 until 700_000L)
        file.save()
        assertEquals(Record(700_000, emptyList()), records.last())

        // Back to an earlier byte, never forward to a later one.
        file.rewind(900_000)
        assertEquals(700_000, file.written)
        file.rewind(300_000)
        assertEquals(300_000, file.written)

        file.rewind()
        assertEquals(0, file.written)
        file.save()
        assertEquals(Record(0, emptyList()), records.last())
        assertEquals(Disk.Forced, disk.events[disk.events.lastIndex - 1])

        val rewound = disk.events.size
        val again = pattern(MIB.toInt(), seed = 5)
        file.take(again)
        file.save()
        assertEquals(Record(MIB, emptyList()), records.last())
        assertContentEquals(again, disk.bytes)
        assertEquals(0, disk.events.drop(rewound).filterIsInstance<Disk.Wrote>().first().position)
    }

    @Test
    fun `storage full is sorted from the volume's words, anything else is a write that failed`() = runBlocking<Unit> {
        assertEquals("Storage is full", RecordedFile.STORAGE_FULL)
        assertEquals("Couldn't write the file", RecordedFile.WRITE_FAILED)
        assertEquals(RecordedFile.STORAGE_FULL, DownloadWriter.STORAGE_FULL)
        assertEquals(RecordedFile.WRITE_FAILED, DownloadWriter.WRITE_FAILED)

        // The JVM's words for ENOSPC and EFBIG, and Android's; one wrapped as a channel may wrap it.
        val full = listOf(
            IOException("No space left on device"),
            IOException("write failed: ENOSPC (No space left on device)"),
            IOException("write failed: EFBIG (File too large)"),
            IOException("File too large"),
            IOException("write failed", IOException("ENOSPC")),
        )
        val other = listOf(IOException("Input/output error"), IOException("write failed: EIO (I/O error)"))
        (full.map { it to true } + other.map { it to false }).forEach { (cause, room) ->
            records.clear()
            val at = 300_000L
            val disk = Disk(MIB, fullAt = at, failure = cause)
            val file = file(disk)
            val failure = assertFailsWith<StorageFailure>("for \"${cause.message}\"") { file.take(pattern(MIB.toInt())) }
            assertEquals(room, failure.full, "for \"${cause.message}\"")
            assertEquals(if (room) RecordedFile.STORAGE_FULL else RecordedFile.WRITE_FAILED, failure.reason, "for \"${cause.message}\"")
            assertEquals(cause, failure.cause)
            // The bytes in front of the failed write are written, and the record can go that far once forced.
            assertEquals(at, file.written, "for \"${cause.message}\"")
            assertEquals(emptyList(), records)
            file.saveQuietly()
            assertEquals(listOf(Record(at, emptyList())), records, "for \"${cause.message}\"")
        }

        // A force the volume refuses: no record.
        records.clear()
        val disk = Disk(MIB, forceFailure = IOException("No space left on device"))
        val file = file(disk)
        file.take(pattern(1_000))
        val failure = assertFailsWith<StorageFailure> { file.save() }
        assertTrue(failure.full)
        assertEquals(emptyList(), records)
    }

    @Test
    fun `saveQuietly leaves the record where it was when storage or the record fails, and passes a cancel on`() = runBlocking<Unit> {
        // A file's name and path in the failure's words: the log line holds neither.
        val disk = Disk(MIB, forceFailure = IOException("$FILE_NAME: No space left on device"))
        val file = file(disk)
        file.take(pattern(1_000))
        val (_, full) = stderrOf { file.saveQuietly() }
        assertEquals(emptyList(), records)
        disk.forceFailure = IOException("Input/output error at $FILE_NAME")
        val (_, broken) = stderrOf { file.saveQuietly() }
        assertEquals(emptyList(), records)
        listOf(full, broken).forEach { log ->
            val lines = log.lines().filter { "RecordedFile" in it }
            assertEquals(1, lines.size, "the file's lines: $log")
            assertTrue(ID in lines.single() && "1000" in lines.single(), "no id or offset: $lines")
            listOf("Harbor", ".mkv", "Input/output", "No space").forEach { words -> assertFalse(words in lines.single(), "\"$words\" in $lines") }
        }
        assertTrue("storage full" in full && "IOException" in broken, "the causes' kinds: $full $broken")

        // The record itself failing: kept where it was, the write going on.
        disk.forceFailure = null
        val refusing = file(disk) { _, _ -> throw IllegalStateException("the database is closed") }
        refusing.take(pattern(1_000))
        refusing.saveQuietly()
        // A cancel while it records goes on up.
        val cancelled = file(disk) { _, _ -> throw CancellationException("paused") }
        cancelled.take(pattern(1_000))
        assertFailsWith<CancellationException> { cancelled.saveQuietly() }
    }

    @Test
    fun `the live sink hears where the write is, and bytes taken say its source is in reach`() = runBlocking<Unit> {
        val file = file(Disk(MIB), from = 2_000)
        file.unreachable = true
        file.said()
        assertEquals(DownloadWriter.Live(ID, 2_000, unreachable = true), lives.single())
        file.take(pattern(3_000), 2_000 until 3_000L)
        assertFalse(file.unreachable)
        assertEquals(DownloadWriter.Live(ID, 3_000, unreachable = false), lives.last())
        // One for each chunk taken.
        assertEquals(2, lives.size)
    }

    @Test
    fun `its rate is the bytes taken a second over the last 5 s`() = runBlocking<Unit> {
        val file = file(Disk(4 * MIB, keep = false))
        assertEquals(0, file.rate.bytesPerSecond())
        file.takeZeros(1_000_000)
        time += 1.seconds
        file.takeZeros(1_000_000)
        assertEquals(2_000_000, file.rate.bytesPerSecond())
        time += 10.seconds
        assertEquals(0, file.rate.bytesPerSecond())
    }

    @Test
    fun `a failure's own words are fixed, never the cause's, which can name the file`() = runBlocking<Unit> {
        listOf("$FILE_NAME: No space left on device", "Input/output error at $FILE_NAME").forEach { words ->
            val file = file(Disk(MIB, fullAt = 10, failure = IOException(words)))
            val failure = assertFailsWith<StorageFailure> { file.take(pattern(1_000)) }
            assertFalse(FILE_NAME in failure.toString(), "${failure.message}")
            assertFalse(FILE_NAME in file.toString())
            assertIs<IOException>(failure.cause)
        }
    }

    /** [data]'s [range] taken as a stream gives it: through the file's buffer, [chunk] bytes at most each time. */
    private suspend fun RecordedFile.take(data: ByteArray, range: LongRange = 0 until data.size.toLong(), chunk: Int = buffer.size) {
        var at = range.first
        while (at <= range.last) {
            val n = minOf(chunk.toLong(), range.last - at + 1).toInt()
            data.copyInto(buffer, 0, at.toInt(), at.toInt() + n)
            took(n)
            at += n
        }
    }

    /** [count] bytes of zeros taken, a buffer's worth at a time: what a file that keeps nothing is given. */
    private suspend fun RecordedFile.takeZeros(count: Long) {
        buffer.fill(0)
        var left = count
        while (left > 0) {
            val n = minOf(buffer.size.toLong(), left).toInt()
            took(n)
            left -= n
        }
    }

    /** What [block] logs: slf4j-simple writes to System.err as it is at the time. */
    private suspend fun <T> stderrOf(block: suspend () -> T): Pair<T, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    /**
     * The download's file for the test: [size] bytes kept in memory ([bytes]), or none kept with [keep] false, so a test
     * can write past 32 MiB with no file of that size. It says what was done to it in order ([events]) and how far it was
     * written at the last force ([forced]), and writes at most [slice] bytes a call, as a channel may. A write reaching byte
     * [fullAt] fails there with [failure], the bytes in front of it written; a force fails with [forceFailure] while set.
     */
    private class Disk(
        val size: Long,
        keep: Boolean = true,
        private val fullAt: Long? = null,
        private val failure: IOException = IOException("No space left on device"),
        var forceFailure: IOException? = null,
        private val slice: Int = 64 * 1024,
    ) : FileChannel() {
        sealed interface Event

        data class Wrote(val position: Long, val end: Long) : Event

        data object Forced : Event

        data class Recorded(val offset: Long) : Event

        val bytes: ByteArray? = if (keep) ByteArray(size.toInt()) else null
        val events = mutableListOf<Event>()

        /** How far the file is written: the end of the furthest write. */
        var end = 0L
            private set

        /** How far the file was written at the last force. */
        var forced = 0L
            private set

        override fun write(src: ByteBuffer, position: Long): Int {
            if (position == fullAt) throw failure
            var n = minOf(src.remaining(), slice)
            if (fullAt != null && position < fullAt) n = minOf(n.toLong(), fullAt - position).toInt()
            val kept = bytes
            if (kept != null) src.get(kept, position.toInt(), n) else src.position(src.position() + n)
            end = maxOf(end, position + n)
            events += Wrote(position, position + n)
            return n
        }

        override fun force(metaData: Boolean) {
            forceFailure?.let { throw it }
            forced = end
            events += Forced
        }

        override fun size(): Long = size
        override fun position(): Long = 0
        override fun position(newPosition: Long): FileChannel = this
        override fun read(dst: ByteBuffer): Int = throw UnsupportedOperationException("the file is written, not read")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException("the file is written, not read")
        override fun read(dst: ByteBuffer, position: Long): Int = throw UnsupportedOperationException("the file is written, not read")
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("the file is written at offsets")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException("the file is written at offsets")
        override fun truncate(size: Long): FileChannel = throw UnsupportedOperationException("the file is allocated whole")
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = throw UnsupportedOperationException()
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = throw UnsupportedOperationException()
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
        override fun implCloseChannel() = Unit
    }

    private companion object {
        const val ID = "dl-4Rk8"
        const val MIB = 1024L * 1024

        /** An invented release's file, as a failure's words might name it. */
        const val FILE_NAME = "Harbor.Lights.S01E02.1080p.WEB-DL-KESTREL.mkv"

        /** [size] bytes that differ from place to place, by [seed]. */
        fun pattern(size: Int, seed: Int = 1) = ByteArray(size) { (it * 31 + it / 251 + seed).toByte() }
    }
}

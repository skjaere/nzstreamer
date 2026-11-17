package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L417: a finished download's file as the cast's server reads it ([FileByteSource]): any range's exact bytes, to the
 * size and no further; the recorded gaps told at each open, those ending at or after its byte; never 0 from a read, and
 * -1 only at the size; a missing or short file failing its open, and one that shrinks failing the read there, each in
 * fixed words that never hold the path; a close from another thread ending a read. Then through a real server on
 * loopback, as ServeRangeTest drives one: the head opened ahead, a 206 from a middle byte, a 416 past the end, and a file
 * deleted once its head is open still served to its end. The file's name is invented, its bytes [byteAt]'s.
 */
class FileByteSourceTest : ServeTestBase() {
    @TempDir
    lateinit var dir: File

    private val size = 300_000L

    /** What the download's writer recorded: zeros on disk, as it leaves them. */
    private val gaps = listOf(1_000L..1_999L, 120_000L..120_099L, 250_000L..259_999L)

    /** The test's file of [length] bytes ([extra] past them), [byteAt] of each position and zeros in [gaps]. */
    private fun file(length: Long = size, extra: Int = 0, name: String = NAME): File =
        File(dir, name).apply { writeBytes(expected(0, length - 1 + extra, gaps)) }

    private fun source(file: File = file(), size: Long = this.size) = FileByteSource(file, size, gaps)

    /** Everything [upstream] gives in reads of [buffer] bytes, to its -1, each read giving some: never 0. */
    private fun readAll(upstream: Upstream, buffer: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(buffer)
        while (true) {
            val n = upstream.read(chunk, 0, chunk.size, 0)
            if (n < 0) return out.toByteArray()
            assertTrue(n > 0, "a read gave 0")
            out.write(chunk, 0, n)
        }
    }

    /** Neither [what]'s words nor anything it prints, its trace and causes included, holds the file's folder or its name. */
    private fun assertNoPath(what: String) {
        assertFalse(dir.path in what, "the path in: $what")
        assertFalse(NAME in what, "the file's name in: $what")
        assertFalse("Copper" in what, "the file's name in: $what")
    }

    @Test
    fun `any range reads its exact bytes, to the size`() {
        val source = source()
        for (position in listOf(0L, 1L, 999L, 1_000L, 120_050L, 150_000L, size - 1)) {
            for (buffer in listOf(7, 65_536)) {
                val read = source.open(position) {}.use { readAll(it, buffer) }
                assertContentEquals(expected(position, size - 1, gaps), read, "from $position in reads of $buffer")
            }
        }
        val tail = source.open(size - 100) {}.use { readAll(it, 1) }
        assertContentEquals(expected(size - 100, size - 1, gaps), tail, "the last 100 bytes, one at a time")
        source.open(size) {}.use { assertEquals(-1, it.read(ByteArray(16), 0, 16, 0), "opened at the size: its end at once") }
    }

    @Test
    fun `the recorded gaps that end at or after the open's byte are told before it returns, earlier ones not`() {
        val source = source()
        fun toldAt(position: Long): List<LongRange> {
            val told = mutableListOf<LongRange>()
            // Read as soon as the open returns: no read has run yet.
            source.open(position) { told += it }.use { return told.toList() }
        }
        assertEquals(gaps, toldAt(0))
        assertEquals(gaps, toldAt(1_999), "a gap ending at the open's byte")
        assertEquals(gaps.drop(1), toldAt(2_000))
        assertEquals(gaps.drop(1), toldAt(120_050), "one the open's byte is inside")
        assertEquals(gaps.drop(2), toldAt(120_100))
        assertEquals(emptyList(), toldAt(size), "none ends at or after the size")
    }

    @Test
    fun `a read never gives 0, and -1 only at the size, a longer file read to its size alone`() {
        val source = source(file(extra = 5_000))
        assertContentEquals(expected(0, size - 1, gaps), source.open(0) {}.use { readAll(it, 65_536) })
        source.open(size - 10) {}.use { upstream ->
            val buffer = ByteArray(64)
            assertEquals(10, upstream.read(buffer, 0, buffer.size, 0), "no further than the size")
            assertContentEquals(expected(size - 10, size - 1, gaps), buffer.copyOf(10))
            assertEquals(-1, upstream.read(buffer, 0, buffer.size, 0))
            assertEquals(-1, upstream.read(buffer, 0, buffer.size, 0), "and again")
        }
        // Into the middle of a buffer, as the server asks for less than it holds.
        source.open(500) {}.use { upstream ->
            val buffer = ByteArray(32)
            assertEquals(8, upstream.read(buffer, 4, 8, 0))
            assertContentEquals(expected(500, 507, gaps), buffer.copyOfRange(4, 12))
        }
    }

    @Test
    fun `a missing file, or one shorter than its size, fails its open in fixed words, telling no gap`() {
        val told = mutableListOf<LongRange>()
        val missing = assertFailsWith<IOException> { source(File(dir, "gone-$NAME")).open(0) { told += it } }
        assertEquals("the file can't be opened", missing.message)
        assertNoPath(missing.stackTraceToString())
        val short = assertFailsWith<IOException> { source(file(length = size - 1)).open(0) { told += it } }
        assertEquals("the file is shorter than its size", short.message)
        assertNoPath(short.stackTraceToString())
        assertEquals(emptyList(), told)
        // A folder where the file was: no file to read either.
        File(dir, "folder").mkdirs()
        val folder = assertFailsWith<IOException> { source(File(dir, "folder")).open(0) {} }
        assertEquals("the file can't be opened", folder.message)
    }

    @Test
    fun `a file that shrinks under a read fails the read there in fixed words`() {
        val file = file()
        source(file).open(0) {}.use { upstream ->
            val buffer = ByteArray(50_000)
            assertEquals(50_000, upstream.read(buffer, 0, buffer.size, 0))
            RandomAccessFile(file, "rw").use { it.setLength(80_000) }
            // What is still there reads; the next read finds the end short of the size.
            assertEquals(30_000, upstream.read(buffer, 0, buffer.size, 0))
            assertContentEquals(expected(50_000, 79_999, gaps), buffer.copyOf(30_000))
            val shrank = assertFailsWith<IOException> { upstream.read(buffer, 0, buffer.size, 0) }
            assertEquals("the file shrank", shrank.message)
            assertNoPath(shrank.stackTraceToString())
        }
    }

    /**
     * A close from another thread (the server's, as an answer is revoked or taken) ends a read under way with an
     * IOException: the reader reads a byte at a time through 8 MB, and the close comes once it has begun, long before it
     * could reach the end. A read after the close fails too, and a second close does nothing.
     */
    @Test
    fun `a close from another thread ends a read under way with an IOException`() {
        val big = 8_000_000L
        val upstream = source(file(length = big), big).open(0) {}
        val begun = CountDownLatch(1)
        val ended = AtomicReference<Throwable?>()
        val read = AtomicLong()
        val reader = thread(name = "file-source-reader") {
            val one = ByteArray(1)
            try {
                while (true) {
                    val n = upstream.read(one, 0, 1, 0)
                    if (n < 0) break
                    read.addAndGet(n.toLong())
                    begun.countDown()
                }
            } catch (e: Throwable) {
                ended.set(e)
            }
        }
        assertTrue(begun.await(10, TimeUnit.SECONDS), "the read never began")
        upstream.close()
        reader.join(10_000)
        assertFalse(reader.isAlive, "the read went on after the close")
        val thrown = assertNotNull(ended.get(), "the reader came to the end, ${read.get()} bytes, before the close")
        assertTrue(thrown is IOException, "ended with $thrown")
        assertTrue(read.get() < big, "read to the end")
        assertFailsWith<IOException> { upstream.read(ByteArray(16), 0, 16, 0) }
        upstream.close()
    }

    @Test
    fun `nothing it prints holds the file's path or name`() {
        val source = source()
        assertEquals("FileByteSource(300000 bytes)", source.toString())
        source.open(5) {}.use { assertNoPath(it.toString()) }
    }

    /** Through a real server: the head opened ahead and read from it, a 206 from a middle byte, a 416 past the end. */
    @Test
    fun `served on loopback, the head from 0, a middle byte's 206 and a 416 past the end, its gaps told`() {
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source()), loopback)
        served.openHead()
        val (head, film) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, head.status)
        assertEquals(listOf("bytes 0-299999/300000"), head.header("Content-Range"))
        assertContentEquals(expected(0, size - 1, gaps), film)
        assertEquals(gaps, served.gaps(), "the head's open told every gap")

        val (middle, rest) = exchange(server, request(server, served.path, range = "bytes=150000-"))
        assertEquals(206, middle.status)
        assertEquals(listOf("bytes 150000-299999/300000"), middle.header("Content-Range"))
        assertEquals(150_000L, middle.length)
        assertContentEquals(expected(150_000, size - 1, gaps), rest)

        val (past, nothing) = exchange(server, request(server, served.path, range = "bytes=300000-"))
        assertEquals(416, past.status)
        assertEquals(listOf("bytes */300000"), past.header("Content-Range"))
        assertEquals(0, nothing.size)
    }

    @Test
    fun `served on loopback, a middle byte's open tells only the gaps from there`() {
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source()), loopback)
        val (middle, rest) = exchange(server, request(server, served.path, range = "bytes=150000-"))
        assertEquals(206, middle.status)
        assertContentEquals(expected(150_000, size - 1, gaps), rest)
        assertEquals(listOf(250_000L..259_999L), served.gaps())
    }

    /**
     * A file deleted once its head is open (a Remove that nothing held) still serves that open stream to its end, as an
     * open file reads on; a new open of it then fails, and the server closes that answer after its headers.
     */
    @Test
    fun `a file deleted after its head is opened is still served to its end from it`() {
        val file = file()
        val server = server()
        val served = server.serve(ServedFile(size, "video/x-matroska", "mkv", source(file)), loopback)
        served.openHead()
        assertTrue(file.delete())
        val (head, film) = exchange(server, request(server, served.path, range = "bytes=0-"))
        assertEquals(206, head.status)
        assertContentEquals(expected(0, size - 1, gaps), film)

        val (middle, rest) = exchange(server, request(server, served.path, range = "bytes=150000-"))
        assertEquals(206, middle.status, "the headers go before the source opens")
        assertEquals(0, rest.size, "nothing to serve from a new open")
        eventually("the failed open counted") { served.counters().upstreamFailures == 1 }
    }

    private companion object {
        /** A download's file as its release names it: invented. */
        const val NAME = "Copper.Crown.2022.1080p.WEB-DL-KESTREL.mkv"
    }
}

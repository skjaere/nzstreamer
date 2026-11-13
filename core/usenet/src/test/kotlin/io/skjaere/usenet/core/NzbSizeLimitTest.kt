package io.skjaere.usenet.core

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NzbSizeLimitTest {
    private val cap = 64L * 1024

    /** [length] bytes generated on demand (nothing is held in memory), counting how many were read. */
    private class GeneratedStream(private val length: Long) : InputStream() {
        var consumed = 0L

        override fun read(): Int = if (consumed < length) (consumed++ % 251).toInt() else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= length) return -1
            val n = minOf(len.toLong(), length - consumed).toInt()
            for (i in 0 until n) b[off + i] = ((consumed + i) % 251).toByte()
            consumed += n
            return n
        }
    }

    @Test
    fun `a stream past the cap fails after reading at most the cap plus one buffer`() {
        // A 2 GB .mkv picked instead of the .nzb used to be read whole (OutOfMemoryError).
        val stream = GeneratedStream(cap * 4)
        val result = runCatching { stream.readNzbBytes(cap) }
        assertTrue(stream.consumed <= cap + DEFAULT_BUFFER_SIZE, "read ${stream.consumed} bytes for a $cap-byte cap")
        val e = assertIs<NzbTooLargeException>(result.exceptionOrNull())
        assertNull(e.size)
        assertEquals(cap, e.limit)
    }

    @Test
    fun `a stream one byte past the cap fails`() {
        assertFailsWith<NzbTooLargeException> { GeneratedStream(cap + 1).readNzbBytes(cap) }
    }

    @Test
    fun `a stream exactly at the cap is read whole`() {
        val bytes = GeneratedStream(cap).readNzbBytes(cap)
        assertContentEquals(GeneratedStream(cap).readBytes(), bytes)
    }

    @Test
    fun `an empty stream reads as no bytes`() {
        assertEquals(0, GeneratedStream(0).readNzbBytes(cap).size)
    }

    @Test
    fun `a reported size over the cap is rejected before reading`() {
        val e = assertFailsWith<NzbTooLargeException> { checkNzbSize(2_000_000_000) }
        assertEquals(2_000_000_000, e.size)
        assertEquals(MAX_NZB_BYTES, e.limit)
    }

    @Test
    fun `unknown or allowed reported sizes pass`() {
        listOf(null, -1L, 0L, 40_000_000L, MAX_NZB_BYTES).forEach { checkNzbSize(it) }
        checkNzbSize(cap, limit = cap)
    }
}

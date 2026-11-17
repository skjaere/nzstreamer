package io.skjaere.usenet.serve

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A finished download's file as Cast's media server reads it (L417): [size] bytes of [file] on this device, each
 * upstream a channel of its own read with positional reads from its byte, so a read never waits, never gives 0 where it
 * asks for bytes, and gives -1 only at [size], however long the file is. The stretches its download had no article
 * for are zeros in the file, as its writer left them ([gaps], inclusive file offsets): each open tells every one that
 * ends at or after its byte before it returns, as the Usenet stream tells its own before their zeros are read.
 *
 * A file missing, or shorter than [size], fails its open, and one that shrinks fails the read that finds its end: each
 * in fixed words, so no message, cause or [toString] here ever holds the file's path or name. An open channel reads on
 * once the file is deleted, as an open file does: a Remove a cast didn't hold harms none of the reads already open, and
 * only the next open fails. A close from any thread ends a read under way with the channel's own IOException.
 */
class FileByteSource(private val file: File, private val size: Long, private val gaps: List<LongRange>) : ByteSource {
    init {
        require(size >= 0) { "a size of $size" }
    }

    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
        require(position in 0..size) { "a position outside the file" }
        // The platform's own words name the path: none of them, nor the cause that holds them, goes on. A folder where
        // the file was opens as none, as a RandomAccessFile refuses one.
        val channel: FileChannel = try {
            RandomAccessFile(file, "r").channel
        } catch (_: IOException) {
            throw IOException(CANT_OPEN)
        }
        try {
            val length = try {
                channel.size()
            } catch (_: IOException) {
                throw IOException(CANT_OPEN)
            }
            if (length < size) throw IOException(SHORT)
            this.gaps.filter { it.last >= position }.forEach(gaps)
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
        return Read(channel, position)
    }

    override fun toString() = "FileByteSource($size bytes)"

    /** One stretch of the file from [from] to [size], read on one answer's thread, closed from any. */
    private inner class Read(private val channel: FileChannel, private val from: Long) : Upstream {
        private var position = from

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (position >= size) return -1
            if (length == 0) return 0
            val want = minOf(length.toLong(), size - position).toInt()
            val n = channel.read(ByteBuffer.wrap(buffer, offset, want), position)
            // Nothing where bytes were asked short of the size: the file's end is nearer than it was.
            if (n <= 0) throw IOException(SHRANK)
            position += n
            return n
        }

        override fun close() = channel.close()

        override fun toString() = "FileByteSource.Read(from $from)"
    }

    private companion object {
        const val CANT_OPEN = "the file can't be opened"
        const val SHORT = "the file is shorter than its size"
        const val SHRANK = "the file shrank"
    }
}

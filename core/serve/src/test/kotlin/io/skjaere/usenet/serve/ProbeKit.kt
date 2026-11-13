package io.skjaere.usenet.serve

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * A media file on disk as a served file's source, for the workstation's probes: [zeros] read as zeros and told as gaps,
 * as the engine passes missing articles on, and a stall at a byte ([stallAt]) for a while, as a slow provider.
 */
class FileSource(val file: File) : ByteSource {
    val size = file.length()

    /** Stretches read as zeros, each told to the server the first time a read reaches it. */
    @Volatile var zeros: List<LongRange> = emptyList()

    /** A read reaching [at] gets nothing for [millis], once: when it began and ended (nanoTime), once they have. */
    class Stall(val at: Long, val millis: Long) {
        @Volatile var startedAt: Long? = null
        @Volatile var endedAt: Long? = null
    }

    @Volatile var stall: Stall? = null

    fun stallAt(at: Long, millis: Long) = Stall(at, millis).also { stall = it }

    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream = Stream(position, gaps)

    private inner class Stream(private var position: Long, private val sink: (LongRange) -> Unit) : Upstream {
        private val input = RandomAccessFile(file, "r")
        private val closed = CountDownLatch(1)
        private val told = mutableSetOf<LongRange>()

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (closed.count == 0L) throw IOException("closed")
            if (position >= size) return -1
            var n = minOf(length.toLong(), size - position)
            stall?.takeIf { it.endedAt == null }?.let { stall ->
                if (position == stall.at) {
                    val started = stall.startedAt ?: System.nanoTime().also { stall.startedAt = it }
                    val left = TimeUnit.NANOSECONDS.toMillis(started - System.nanoTime()) + stall.millis
                    if (left > 0) {
                        closed.await(minOf(left, waitMillis), MILLISECONDS)
                        if (closed.count == 0L) throw IOException("closed")
                        return 0
                    }
                    stall.endedAt = System.nanoTime()
                } else if (position < stall.at && position + n > stall.at) {
                    n = stall.at - position
                }
            }
            val end = position + n - 1
            for (zero in zeros) if (zero.first <= end && zero.last >= position && told.add(zero)) sink(zero)
            synchronized(input) {
                input.seek(position)
                input.readFully(buffer, offset, n.toInt())
            }
            for (zero in zeros) {
                val from = maxOf(zero.first, position)
                val to = minOf(zero.last, end)
                if (from <= to) buffer.fill(0, offset + (from - position).toInt(), offset + (to - position).toInt() + 1)
            }
            position += n
            return n.toInt()
        }

        override fun close() {
            if (closed.count == 0L) return
            closed.countDown()
            synchronized(input) { input.close() }
        }
    }
}

/** Where a Matroska file's top-level elements are: enough to place zeros inside a cluster or over its header. */
class MatroskaLayout(file: File) {
    /** Each cluster's bytes, its ID to its last byte, in file order. */
    val clusters: List<LongRange>
    /** Where the Cues element starts, the index a first seek reads at the end; null without one. */
    val cues: Long?

    init {
        RandomAccessFile(file, "r").use { input ->
            fun byte(at: Long): Int {
                input.seek(at)
                return input.read()
            }

            /** A variable-length integer at [at]: its value (the marker kept for an ID) and its length. */
            fun vint(at: Long, keepMarker: Boolean): Pair<Long, Int> {
                val first = byte(at)
                require(first > 0) { "not an EBML number at $at" }
                val length = Integer.numberOfLeadingZeros(first) - 23
                var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
                for (i in 1 until length) value = (value shl 8) or byte(at + i).toLong()
                return value to length
            }

            var at = 0L
            val (_, headerId) = vint(at, true)
            val (headerSize, headerSizeLength) = vint(at + headerId, false)
            at += headerId + headerSizeLength + headerSize
            val (segment, segmentIdLength) = vint(at, true)
            require(segment == SEGMENT) { "no Segment where one was due" }
            val (segmentSize, segmentSizeLength) = vint(at + segmentIdLength, false)
            at += segmentIdLength + segmentSizeLength
            val end = minOf(input.length(), at + segmentSize)
            val clusters = mutableListOf<LongRange>()
            var cues: Long? = null
            while (at < end) {
                val (id, idLength) = vint(at, true)
                val (size, sizeLength) = vint(at + idLength, false)
                val total = idLength + sizeLength + size
                when (id) {
                    CLUSTER -> clusters += at until at + total
                    CUES -> cues = at
                }
                at += total
            }
            this.clusters = clusters
            this.cues = cues
        }
    }

    private companion object {
        const val SEGMENT = 0x18538067L
        const val CLUSTER = 0x1F43B675L
        const val CUES = 0x1C53BB6BL
    }
}

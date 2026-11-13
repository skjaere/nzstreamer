package io.skjaere.usenet.player

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One open's bytes read ahead of the player (L239, W88): a debrid play gets the cushion a Usenet play has, Usenet's
 * streams' cap ([io.skjaere.usenet.player.tv.PlaybackBudgets.streamBytes]) held in front of Media3's own buffer, in the
 * shape nzb-streamer ramps it. A reader [fill]s 256 KiB chunks from the [Upstream] while the player [take]s them, so a
 * CDN that goes silent for a moment is ridden on the bytes queued, and one that cuts the body is opened again at its byte
 * while the player still has bytes.
 *
 * From the file's first byte the reader keeps 2 MiB ahead until the player has taken 1 MiB: an extractor reads a
 * container's head there and often seeks to its index at the end, which a whole budget read for nothing would hold up.
 * From any other byte, and from the first once 1 MiB is taken, it keeps the budget ahead. A read never goes past its
 * chunk, so it holds no more than the budget and one chunk ahead.
 *
 * A cut (a read that fails) after bytes since the last open is opened again once at the byte reached; one with none since
 * fails there, as does an open again that fails (a dead link the service won't replace). Either failure is kept at its
 * byte: the player takes the bytes before it first, then the failure, for Media3's load policy to judge as it would the
 * read's own. The chunks go back to the [Pool] as the player takes them and on [close], for the next open to take: the
 * bytes sit in arrays used again, so the collector has little to do (a Media3 buffer grown as large hitched the Shield).
 *
 * The reader runs on a thread of its own, the player on Media3's loader: they meet under one lock. Pure JVM.
 */
class ReadAhead(
    /** The byte the open reads from. */
    private val start: Long,
    /** How much the reader keeps ahead of what the player took. */
    private val budget: Long,
    private val pool: Pool,
) {
    /** What the reader reads: the open's body, and the file again from a byte after a cut. Blocking. */
    interface Upstream {
        /** Up to [length] bytes into [buffer] at [offset]: how many, or -1 at the end. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int

        /** Lets the cut body go and opens the file again at [position]. */
        fun reopen(position: Long)
    }

    /** Chunks free to fill, kept between one source's opens: at most [keep] of them, the rest left to the collector. */
    class Pool(private val keep: Int) {
        private val free = ArrayDeque<ByteArray>()

        private var madeCount = 0

        /** How many chunks it has made, and holds free now (for the tests). */
        internal val made: Int
            @Synchronized get() = madeCount
        internal val kept: Int
            @Synchronized get() = free.size

        @Synchronized
        fun take(): ByteArray = free.removeLastOrNull() ?: ByteArray(CHUNK).also { madeCount++ }

        @Synchronized
        fun give(chunk: ByteArray) {
            if (free.size < keep) free.addLast(chunk)
        }
    }

    private val lock = ReentrantLock()

    /** Signalled when there is something for the player: a byte, the end, a failure, the close. */
    private val forPlayer = lock.newCondition()

    /** Signalled when there is room for the reader, or the close. */
    private val forReader = lock.newCondition()

    /** Signalled when the reader comes out of the [Upstream]: for a [close] waiting to let it go itself. */
    private val outOfUpstream = lock.newCondition()

    // Under [lock]. Byte n from [start] sits in chunk n / CHUNK, at n % CHUNK: every chunk but the last is full.
    private val chunks = ArrayDeque<ByteArray>()
    private var filled = 0L
    private var taken = 0L
    /** How much of the last chunk is filled: [CHUNK] while there is none with room. */
    private var tailFill = CHUNK
    private var ended = false
    private var failure: Throwable? = null
    private var closed = false

    /** The reader is in a call to the [Upstream] (a read or an open again), and the chunk it reads into, if any. */
    private var inUpstream = false
    private var writing: ByteArray? = null

    /** The reader waits for the player to take bytes and make room. */
    private var waitingForRoom = false

    /** The close came while the reader was in the [Upstream]: what the reader read from is the reader's to let go. */
    @Volatile
    var upstreamLeftToReader = false
        private set

    /**
     * Whether the reader waits for room with none to be had: what it holds stays as it is until the player takes some (for
     * the tests, which read how far ahead it went once it stopped).
     */
    internal val readerWaitsForRoom: Boolean
        get() = lock.withLock { waitingForRoom && !closed && filled - taken >= window() }

    /** How far ahead the reader may be: 2 MiB from the file's first byte until the player has taken 1 MiB, else the budget. */
    private fun window(): Long = if (start == 0L && taken < FIRST_TAKEN) minOf(FIRST_WINDOW, budget) else budget

    /** The reader's loop, on its own thread: until the end, a failure kept, or [close]. */
    fun fill(upstream: Upstream) {
        // Bytes read since the last open: a cut after some is opened again once.
        var sinceOpen = 0L
        while (true) {
            val chunk: ByteArray
            val at: Int
            lock.withLock {
                while (!closed && filled - taken >= window()) {
                    waitingForRoom = true
                    try {
                        forReader.await()
                    } catch (e: InterruptedException) {
                        // Nothing here interrupts the reader: whatever did, the player is told rather than left waiting.
                        Thread.currentThread().interrupt()
                        failure = InterruptedIOException("The read-ahead's reader was interrupted")
                        forPlayer.signal()
                        return
                    } finally {
                        waitingForRoom = false
                    }
                }
                if (closed) return
                // The last chunk while it has room, else a fresh one from the pool.
                if (tailFill == CHUNK) {
                    chunks.addLast(pool.take())
                    tailFill = 0
                }
                chunk = chunks.last()
                at = tailFill
                inUpstream = true
                writing = chunk
            }
            val n = try {
                upstream.read(chunk, at, CHUNK - at)
            } catch (e: IOException) {
                if (!cameBack()) return
                if (sinceOpen == 0L) return failed(e)
                sinceOpen = 0
                if (!reopened(upstream)) return
                continue
            } catch (e: Throwable) {
                if (!cameBack()) return
                return failed(e)
            }
            lock.withLock {
                if (!cameBackLocked()) return
                when {
                    n < 0 -> {
                        ended = true
                        forPlayer.signal()
                        return
                    }
                    n > 0 -> {
                        filled += n
                        tailFill += n
                        sinceOpen += n
                        forPlayer.signal()
                    }
                }
            }
        }
    }

    /** Opens again at the byte reached after a cut: whether the reader goes on. */
    private fun reopened(upstream: Upstream): Boolean {
        val position = lock.withLock {
            if (closed) return false
            inUpstream = true
            start + filled
        }
        try {
            upstream.reopen(position)
        } catch (e: Throwable) {
            if (cameBack()) failed(e)
            return false
        }
        return cameBack()
    }

    /** The reader is out of the [Upstream]: whether it goes on, or was closed meanwhile (its chunk then back to the pool). */
    private fun cameBack(): Boolean = lock.withLock { cameBackLocked() }

    private fun cameBackLocked(): Boolean {
        inUpstream = false
        outOfUpstream.signalAll()
        val chunk = writing
        writing = null
        if (!closed) return true
        // [close] left the chunk being read into: it is back with the pool now that nothing writes to it.
        if (chunk != null) pool.give(chunk)
        return false
    }

    /** The reader stops at [e], kept for the player after the bytes before it. */
    private fun failed(e: Throwable) = lock.withLock {
        failure = e
        forPlayer.signal()
    }

    /**
     * The player's read: up to [length] of the bytes held, waiting for one; -1 once the file's end is taken; the failure
     * kept once the bytes before it are. Interrupted (Media3 cancelling the load) or closed while it waits, it fails as an
     * interrupted read.
     */
    fun take(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        lock.withLock {
            while (true) {
                if (closed) throw InterruptedIOException("The read-ahead was closed")
                if (filled > taken) break
                failure?.let { throw it }
                if (ended) return END
                try {
                    forPlayer.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting for the read-ahead")
                }
            }
            val at = (taken % CHUNK).toInt()
            val head = chunks.first()
            val n = minOf(length.toLong(), filled - taken, (CHUNK - at).toLong()).toInt()
            System.arraycopy(head, at, buffer, offset, n)
            taken += n
            // A chunk taken to its end is full, so the reader is done with it.
            if (taken % CHUNK == 0L) pool.give(chunks.removeFirst())
            forReader.signal()
            return n
        }
    }

    /**
     * Ends the reader at its next turn and fails a read waiting; the chunks go back to the pool, all but the one the reader
     * is reading into, which it gives back as its read comes back. A call to the [Upstream] under way is waited for up to
     * [waitMs]: a read from a body still flowing comes back within milliseconds, and the caller then closes what it reads
     * from itself, on its own thread alone, the reader done with it. Whether the reader is still in the call once the wait
     * is out ([upstreamLeftToReader]), waiting on a CDN gone silent or on the service: the caller then aborts what the
     * reader reads from, as the call may wait for long, and the reader closes it as it comes out. The wait is the same on a
     * thread interrupted (Media3 interrupts a load it cancels, then closes its source there), which stays so. Again does
     * nothing (false).
     */
    fun close(waitMs: Long = 0): Boolean = lock.withLock {
        if (closed) return false
        closed = true
        for (chunk in chunks) if (chunk !== writing) pool.give(chunk)
        chunks.clear()
        forPlayer.signalAll()
        forReader.signalAll()
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        var interrupted = false
        while (inUpstream) {
            val left = until - System.nanoTime()
            if (left <= 0) break
            try {
                outOfUpstream.awaitNanos(left)
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        upstreamLeftToReader = inUpstream
        inUpstream
    }

    companion object {
        /** What the reader reads into at a time, and the pool's unit. */
        const val CHUNK = 256 * 1024

        /** From the file's first byte: so much ahead until the player has taken [FIRST_TAKEN]. */
        const val FIRST_WINDOW = 2L * 1024 * 1024
        const val FIRST_TAKEN = 1L * 1024 * 1024

        /** A read at the file's end (Media3's `C.RESULT_END_OF_INPUT`). */
        const val END = -1

        /** How many chunks a pool for [budget] keeps: the budget's, the one being read into and the one being taken. */
        fun chunksFor(budget: Long): Int = ((budget + CHUNK - 1) / CHUNK).toInt() + 2
    }
}

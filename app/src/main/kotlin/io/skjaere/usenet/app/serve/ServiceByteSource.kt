package io.skjaere.usenet.app.serve

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.ServiceDataSource
import io.skjaere.usenet.serve.ByteSource
import io.skjaere.usenet.serve.Upstream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A debrid play's file as Cast's media server reads it (L217, W73): each upstream the server opens is one ranged GET of the
 * file from its byte to the end, opened on the caller's thread through [reads] (the app's: a [ServiceDataSource] of the
 * container's, so the relay shares the probe's and the player's held link and every rule of D1's for a link), and a
 * refusal fails the open itself: the head opened ahead of the LOAD sees it (L218). A coroutine of [scope]'s, on
 * [Dispatchers.IO], copies the body into a queue the server's thread takes from, at most [QUEUED] chunks of [CHUNK] bytes
 * (2 MiB), as [ServedStream] does: a receiver that stops reading holds the download there. What a read brings is handed
 * over at once while the server has nothing queued, else a chunk at a time.
 *
 * Its rules beside D1's: an open whose length isn't the rest of the file fails as no working link (another file, or one
 * cut short); a body cut after some bytes since its open is opened once more at the byte reached, with the same rules,
 * and one cut with none since fails there, after the bytes before it; the first final refusal ([LinkUnavailable]) is
 * kept, so every later open fails with it and no call of the service's; nothing of an exception's text is logged, as a
 * Media3 HTTP exception's cause can name the CDN's host (DFH-1, L221). A cached file is whole: the server's gaps are never
 * told. The bytes are metered by the source [reads] opens, never here, or they would count twice.
 *
 * Closing an upstream returns at once: it ends a read waiting for bytes, and stops the download. One in a read from the
 * CDN is given a moment ([LET_GO_MS]) to come back, as one from a body still flowing does within milliseconds, and then
 * lets its source go on its own thread; one still in its read after that waits on a CDN gone silent, which could hold it
 * for the read time-out: its connection is aborted ([ServiceRead.abort], L249), off the closing thread so that never
 * waits, and the download closes the rest as its read comes back.
 */
class ServiceByteSource(private val size: Long, private val reads: ServiceReads, private val scope: CoroutineScope) : ByteSource {
    /** The first final refusal any open met: every later open fails with it, asking the service nothing. */
    private val refused = AtomicReference<LinkUnavailable?>(null)

    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
        require(position in 0..size) { "a position outside the file" }
        refused.get()?.let { throw LinkUnavailable(it.failure) }
        if (position == size) return AtTheEnd()
        return Feed(position, opened(position))
    }

    /** The file opened at [position] on this thread, its length checked; a failure lets the source go, and a final one is kept. */
    private fun opened(position: Long): ServiceRead {
        val read = reads.make()
        try {
            val length = read.open(position)
            if (length != null && length != size - position) throw LinkUnavailable(LinkFailure.NoWorkingLink)
            return read
        } catch (e: Throwable) {
            if (e is LinkUnavailable) refused.compareAndSet(null, e)
            closeQuietly(read)
            throw e
        }
    }

    override fun toString() = "ServiceByteSource($size bytes)"

    /** What the download hands the server's thread, in order. */
    private sealed interface Item

    /** Bytes of the file. */
    private class Bytes(val bytes: ByteArray) : Item

    /** The file's end. */
    private object End : Item

    /** Why the download stopped short: the server's read throws it. */
    private class Failed(val cause: Throwable) : Item

    /** The upstream was closed: a read waiting for bytes ends. */
    private object Closed : Item

    /** An upstream from the file's very end: its end, with nothing asked of the service. */
    private class AtTheEnd : Upstream {
        @Volatile private var closed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int = if (closed) throw IOException("closed") else -1

        override fun close() {
            closed = true
        }
    }

    /** One GET of the file from [from] to its end, [first] its open, for the server's answers that read it. */
    private inner class Feed(private val from: Long, first: ServiceRead) : Upstream {
        private val queue = ArrayBlockingQueue<Item>(QUEUED)
        private val closed = AtomicBoolean()

        /** Guards [current] and [inRead]: the download's, which [close] looks at from another thread. */
        private val lock = ReentrantLock()
        private val cameOut = lock.newCondition()
        private var current: ServiceRead = first
        private var inRead = false

        /** The item being read and how far: the server's thread's alone. */
        private var held: Item? = null
        private var heldAt = 0

        /**
         * The download, started last, once everything it uses is there. It runs even when the close came first: it alone
         * lets the opened source go (CRL-1). core:serve closes a head that opened after the play ended at once, on the
         * thread that opened it, often before the download has left that thread's queue.
         */
        @OptIn(DelicateCoroutinesApi::class)
        private val download: Job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { download() }

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (closed.get()) throw IOException("closed")
            val item = held ?: next(waitMillis) ?: return 0
            held = item
            return when (item) {
                is Bytes -> {
                    val n = minOf(length, item.bytes.size - heldAt)
                    item.bytes.copyInto(buffer, offset, heldAt, heldAt + n)
                    heldAt += n
                    if (heldAt == item.bytes.size) {
                        held = null
                        heldAt = 0
                    }
                    n
                }
                End -> -1
                // A refusal as itself (its words the bucket alone), anything else as the read's failure.
                is Failed -> throw (item.cause as? LinkUnavailable)?.let { LinkUnavailable(it.failure) } ?: IOException("the debrid read failed", item.cause)
                Closed -> throw IOException("closed")
            }
        }

        /** The next item within [waitMillis]; null when none came. */
        private fun next(waitMillis: Long): Item? {
            val item = try {
                queue.poll(waitMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for the debrid read").apply { initCause(e) }
            }
            if (item != null || closed.get()) return item ?: Closed
            // A download that ended without a word: its scope was cancelled (the app's, as it ends), not this upstream.
            return if (download.isCompleted) queue.poll() ?: Failed(IOException("the debrid read stopped")) else null
        }

        private suspend fun download() {
            var reached = from
            // Bytes read since the last open: a cut after some is opened again once.
            var sinceOpen = 0L
            var chunk = ByteArray(CHUNK)
            var filled = 0
            try {
                while (true) {
                    val failure: IOException? = try {
                        val wanted = minOf((CHUNK - filled).toLong(), size - reached).toInt()
                        val n = if (wanted == 0) -1 else reading { it.read(chunk, filled, wanted) }
                        when {
                            n > 0 -> {
                                filled += n
                                reached += n
                                sinceOpen += n
                                null
                            }
                            n == 0 -> null
                            reached >= size -> {
                                if (filled > 0) put(Bytes(chunk.copyOf(filled)))
                                put(End)
                                return
                            }
                            // An end before the file's: as a cut.
                            else -> IOException("the debrid read ended ${size - reached} bytes short")
                        }
                    } catch (e: IOException) {
                        e
                    }
                    if (closed.get()) return
                    if (failure == null) {
                        // A full chunk is handed over as it is; less, only while the server has nothing queued to read.
                        if (filled == CHUNK) {
                            put(Bytes(chunk))
                            chunk = ByteArray(CHUNK)
                            filled = 0
                        } else if (filled > 0 && queue.isEmpty()) {
                            put(Bytes(chunk.copyOf(filled)))
                            filled = 0
                        }
                        continue
                    }
                    // The bytes before the cut are the server's first.
                    if (filled > 0) {
                        put(Bytes(chunk.copyOf(filled)))
                        filled = 0
                    }
                    if (failure is LinkUnavailable || sinceOpen == 0L) {
                        if (failure is LinkUnavailable) refused.compareAndSet(null, failure)
                        put(Failed(failure))
                        return
                    }
                    sinceOpen = 0
                    if (!reopened(reached)) return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else the source threw: the server's read says so, and the server closes its answer there.
                if (!closed.get()) put(Failed(e))
            } finally {
                // The source is let go here alone, on the download's thread: a close only ever aborts it.
                closeQuietly(lock.withLock { current })
            }
        }

        /**
         * Lets the cut body go and opens the file again at [position]: whether the download goes on. A failure is queued,
         * after the bytes before it; a final refusal is kept for every later open.
         */
        private suspend fun reopened(position: Long): Boolean {
            // The cut body is the download's alone (a close aborts only a source being read or opened): let go off the lock.
            closeQuietly(lock.withLock { current })
            val fresh = lock.withLock {
                if (closed.get()) return false
                reads.make().also {
                    current = it
                    inRead = true
                }
            }
            val failure = try {
                val length = fresh.open(position)
                if (length != null && length != size - position) LinkUnavailable(LinkFailure.NoWorkingLink) else null
            } catch (e: IOException) {
                e
            } finally {
                cameBack()
            }
            if (closed.get()) return false
            if (failure != null) {
                if (failure is LinkUnavailable) refused.compareAndSet(null, failure)
                put(Failed(failure))
                return false
            }
            return true
        }

        /** [block] over the source being read, the download marked in it meanwhile for [close] to see. */
        private inline fun <T> reading(block: (ServiceRead) -> T): T {
            val read = lock.withLock {
                if (closed.get()) throw InterruptedIOException("closed")
                inRead = true
                current
            }
            try {
                return block(read)
            } finally {
                cameBack()
            }
        }

        private fun cameBack() = lock.withLock {
            inRead = false
            cameOut.signalAll()
        }

        /** Waits for room in the queue: the server reading, or this upstream's close, which interrupts the wait. */
        private suspend fun put(item: Item) = runInterruptible { queue.put(item) }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            download.cancel()
            queue.clear()
            // Wakes a read waiting for bytes; the queue has room, having just been emptied.
            queue.offer(Closed)
            // None starts after this: a read or an open again looks at the close under the lock first.
            if (lock.withLock { inRead }) letGo()
        }

        /**
         * Gives the download's read [LET_GO_MS] to come back, then aborts its connection if it hasn't: off the closing
         * thread, which never waits (CRL-2), as a cast's end closes its upstreams on the main thread. Atomic, so the abort
         * comes whatever becomes of the scope meanwhile.
         */
        @OptIn(DelicateCoroutinesApi::class)
        private fun letGo() {
            scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                val silent = lock.withLock {
                    var left = TimeUnit.MILLISECONDS.toNanos(LET_GO_MS)
                    while (inRead && left > 0) {
                        left = try {
                            cameOut.awaitNanos(left)
                        } catch (e: InterruptedException) {
                            // Interrupted: the abort at once.
                            0
                        }
                    }
                    current.takeIf { inRead }
                }
                silent?.abort()
            }
        }

        override fun toString() = "ServiceByteSource.Feed(from $from)"
    }

    private fun closeQuietly(read: ServiceRead) {
        try {
            read.close()
        } catch (e: IOException) {
            // Let go all the same.
        }
    }

    companion object {
        /** A hand-over from the download to the server's thread, at most. */
        const val CHUNK = 256 * 1024

        /** Chunks queued at most: 2 MiB ahead of what the server has taken. */
        const val QUEUED = 8

        /**
         * How long a closed upstream's download is given to come out of a read before its connection is aborted: one from
         * a body still flowing comes back within milliseconds, and its source is then let go on its own thread alone, as
         * Android's HTTP stack reads a body's rest on the thread that closes it
         * ([io.skjaere.usenet.player.ReadAheadDataSource]'s).
         */
        internal const val LET_GO_MS = 200L
    }
}

/** How [ServiceByteSource] reads its file: each upstream one [ServiceRead], made, then opened. */
fun interface ServiceReads {
    fun make(): ServiceRead
}

/** One GET of a debrid play's file, opened and read on one thread at a time; [abort] from any thread. */
interface ServiceRead {
    /** Opens the file from [position] to its end: the answer's length, null where it didn't say. Throws as the open fails. */
    fun open(position: Long): Long?

    /** Up to [length] bytes into [buffer] at [offset]: how many, or -1 at the end. Blocks. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Lets the connection go from another thread: a read waiting on it fails at once, an open under way or to come too. */
    fun abort()

    /** Lets it go, on the thread that reads it. */
    fun close()
}

/**
 * The app's [ServiceReads]: the file at [uri], a launch's `debrid://` URI, read by a [ServiceDataSource] each from
 * [sources], the container's one factory (L249: never the player's read-ahead), whose links the probe and the player hold
 * too and whose meter counts its bytes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SourceReads(private val sources: ServiceDataSource.Factory, private val uri: String) : ServiceReads {
    override fun make(): ServiceRead = object : ServiceRead {
        private val source = sources.createDataSource()

        override fun open(position: Long): Long? =
            source.open(DataSpec.Builder().setUri(Uri.parse(uri)).setPosition(position).build()).takeIf { it != C.LENGTH_UNSET.toLong() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun abort() = source.abort()

        override fun close() = source.close()

        // No URI or link in its words.
        override fun toString() = "SourceReads.Read"
    }

    override fun toString() = "SourceReads"
}

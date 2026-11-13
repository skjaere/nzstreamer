package io.skjaere.usenet.app.serve

import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.player.GapReports
import io.skjaere.usenet.serve.ByteSource
import io.skjaere.usenet.serve.Upstream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A prepared file of an NZB as Cast's media server reads it (C1, L56): each upstream the server opens is one stream of the
 * file from its byte to the end, through [opener]. A coroutine of [scope]'s, on [Dispatchers.IO], reads the stream into a
 * queue the server's thread takes from: at most [QUEUED] chunks of [CHUNK] bytes (2 MiB), so a receiver that stops reading
 * (paused, or its buffer full) holds the download there, over the engine's own read-ahead. Closing an upstream cancels its
 * download, also while it opens or waits for room.
 *
 * A stream that fails to open, or ends short of the file's end, fails the server's read (an IOException): the server
 * closes that answer, and the receiver asks again (L53, L76). Not always at the byte that failed: the engine's channel,
 * closed with the failure as its cause, drops what it holds, up to 1 MiB in each of its two buffers with an article's
 * write on top of each (148,352 bytes on the Shield in item 6(b)), and the server's memo of failures allows for that.
 *
 * A stretch no provider had comes as zeros (the engine fills it), told as the stream records it, before its zeros: to the
 * server's gaps, and to [reports] by file for the cast's missing parts (L94). The bytes the server takes go through a
 * [traffic] meter, one an upstream.
 */
class ServedStream(
    private val nzb: PreparedNzb,
    private val file: FileCandidate,
    private val opener: StreamOpener,
    private val scope: CoroutineScope,
    private val traffic: StreamTraffic? = null,
    private val reports: GapReports? = null,
) : ByteSource {
    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream {
        require(position in 0..file.size) { "a position outside the file" }
        return Feed(position, gaps)
    }

    /** What the download hands the server's thread, in order. */
    private sealed interface Item

    /** Bytes of the file: the first [length] of [bytes]. */
    private class Bytes(val bytes: ByteArray, val length: Int) : Item

    /** The file's end. */
    private object End : Item

    /** Why the stream stopped short: the server's read throws it. */
    private class Failed(val cause: Throwable) : Item

    /** The upstream was closed: a read waiting for bytes ends. */
    private object Closed : Item

    /** One stream of the file from [from] to its end, for the server's answers that read it. */
    private inner class Feed(private val from: Long, private val gaps: (LongRange) -> Unit) : Upstream {
        private val queue = ArrayBlockingQueue<Item>(QUEUED)
        private val closed = AtomicBoolean()
        private val meter = traffic?.open()

        /** The stream once open: a close cancels it at once, rather than when the download's coroutine gets to it. */
        @Volatile
        private var stream: OpenedStream? = null

        /** The item being read and how far: the server's thread's alone. */
        private var held: Item? = null
        private var heldAt = 0

        /** The download, started last, once everything it uses is there; none from the file's end, which is its end at once. */
        private val download: Job? = if (from < file.size) {
            scope.launch(Dispatchers.IO) { download() }
        } else {
            queue.add(End)
            null
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (closed.get()) throw IOException("closed")
            val item = held ?: next(waitMillis) ?: return 0
            held = item
            return when (item) {
                is Bytes -> {
                    val n = minOf(length, item.length - heldAt)
                    item.bytes.copyInto(buffer, offset, heldAt, heldAt + n)
                    heldAt += n
                    if (heldAt == item.length) {
                        held = null
                        heldAt = 0
                    }
                    meter?.add(n)
                    n
                }
                End -> -1
                is Failed -> throw IOException("the stream of the file failed", item.cause)
                Closed -> throw IOException("closed")
            }
        }

        /** The next item within [waitMillis]; null when none came. */
        private fun next(waitMillis: Long): Item? {
            val item = try {
                queue.poll(waitMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for the stream").apply { initCause(e) }
            }
            if (item != null || closed.get()) return item ?: Closed
            // A download that ended without a word: its scope was cancelled (the app's, as it ends), not this upstream.
            val ended = download?.isCompleted == true
            return if (ended) queue.poll() ?: Failed(IOException("the stream of the file stopped")) else null
        }

        private suspend fun download() {
            var opened: OpenedStream? = null
            try {
                opened = opener.open(nzb, file.index, from until file.size)
                stream = opened
                // Closed while it opened: the close may have looked for the stream before it was there.
                if (closed.get()) return
                // Each stretch the stream records, those before this too, once; never once the server let the stream go.
                opened.gaps.listen { range ->
                    if (!closed.get()) {
                        gaps(range)
                        reports?.found(GapReports.Found(nzb.id, file.index, file.name, range))
                    }
                }
                val channel = opened.channel
                var position = from
                while (position < file.size) {
                    val chunk = ByteArray(minOf(CHUNK.toLong(), file.size - position).toInt())
                    var filled = channel.readAvailable(chunk, 0, chunk.size)
                    if (filled < 0) break
                    // What the stream holds already, up to the chunk, without waiting for more: a chunk a hand-over.
                    while (filled < chunk.size && channel.availableForRead > 0) {
                        val n = channel.readAvailable(chunk, filled, chunk.size - filled)
                        if (n < 0) break
                        filled += n
                    }
                    position += filled
                    put(Bytes(chunk, filled))
                }
                put(if (position >= file.size) End else Failed(channel.closedCause ?: IOException("the stream ended ${file.size - position} bytes short")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The opener or the stream failed: the server's read says so, and the server closes its answer there.
                put(Failed(e))
            } finally {
                opened?.let(::stop)
            }
        }

        /** Waits for room in the queue: the server reading, or this upstream's close, which interrupts the wait. */
        private suspend fun put(item: Item) = runInterruptible { queue.put(item) }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            download?.cancel()
            stream?.let(::stop)
            queue.clear()
            // Wakes a read waiting for bytes; the queue has room, having just been emptied.
            queue.offer(Closed)
            meter?.close()
        }

        private fun stop(stream: OpenedStream) {
            stream.job.cancel()
            stream.channel.cancel(null)
        }
    }

    companion object {
        /** A hand-over from the download to the server's thread, at most. */
        const val CHUNK = 256 * 1024

        /** Chunks queued at most: 2 MiB ahead of what the server has taken. */
        const val QUEUED = 8
    }
}

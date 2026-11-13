package io.skjaere.usenet.link

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * One connection, on either end: its plain socket, then TLS over it ([secured]), its frames, and why it closed. Before
 * its link is up its own thread writes and reads in turn ([writeNow], [next]), held to the hello deadline by a timer
 * that closes it; once up, or pairing, frames go out through a queue and a writer thread of its own ([send],
 * [startWriter]), so no one waits on a peer that stops reading, and [finish] closes it after a last word, or [endWith]
 * after one in place of what waits.
 */
internal class LinkConnection(
    val plain: Socket,
    val address: InetAddress,
    private val timer: ScheduledExecutorService,
    private val limits: LinkLimits,
    private val clock: () -> Long,
) {
    @Volatile private var ssl: SSLSocket? = null
    private lateinit var reader: FrameReader
    private val why = AtomicReference<LinkClose?>(null)
    /** Why it is to close once its last frame is written ([finish], [endWith]); null while frames are still taken. */
    private val ending = AtomicReference<LinkClose?>(null)
    private val queue = ArrayBlockingQueue<ByteArray>(limits.writeQueue)
    @Volatile private var writer: Thread? = null
    /** [endWith]'s timer, which closes it if its last frame isn't written in time. */
    @Volatile private var cutOff: ScheduledFuture<*>? = null

    /** Why it closed, the first reason given; null while open. */
    val closeReason: LinkClose? get() = why.get()

    val isClosed: Boolean get() = why.get() != null

    /** Its last frame is given ([finish], [endWith]): it takes no more, and closes once that is written. */
    val isEnding: Boolean get() = ending.get() != null

    /** Neither closed nor ending: what it brings in still counts, for its link's handler. */
    val counts: Boolean get() = !isClosed && !isEnding

    /**
     * Each read of TLS and the hellos within [millis], on the plain socket; the hello timer holds the whole of it to the
     * same. False when the connection closed meanwhile: a closed socket's timeout can't be set.
     */
    fun timeReads(millis: Long): Boolean = try {
        plain.soTimeout = millis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        !isClosed
    } catch (_: IOException) {
        close(LinkClose.PeerLeft)
        false
    }

    /** TLS is up on [socket]: frames go over it from now. False when the connection closed meanwhile. */
    fun secured(socket: SSLSocket): Boolean {
        ssl = socket
        try {
            reader = FrameReader(socket, limits, clock)
        } catch (_: IOException) {
            // Closed under this thread: Android's TLS socket reads as closed once the plain one is.
            close(LinkClose.PeerLeft)
        }
        if (isClosed) {
            socket.closeQuietly()
            return false
        }
        return true
    }

    /**
     * Closes it now for [reason], unless it closed already; returns at once. Once it is ending ([finish], [endWith]), the
     * peer leaving is no reason of its own: it closes for the ending's reason. The peer may leave on the last word, and be
     * seen to go before the writer closes it. It may have gone before the word went out, and the writer's write fails.
     * Either way this end closed it.
     */
    fun close(reason: LinkClose) {
        val given = if (reason == LinkClose.PeerLeft) ending.get() ?: reason else reason
        if (!why.compareAndSet(null, given)) return
        // The plain socket first: that ends a read or write under way at once, where an SSLSocket's own close may wait on
        // them, and owes a peer nothing more, not even a close_notify.
        plain.closeQuietly()
        ssl?.closeQuietly()
        writer?.interrupt()
        cutOff?.cancel(false)
    }

    /** Before the link is up, on the connection's own thread: [message] written now. False when it couldn't be. */
    fun writeNow(message: LinkMessage): Boolean {
        val socket = ssl ?: return false
        val frame = LinkCodec.encode(message)?.let(LinkFrames::encode) ?: return false
        return try {
            socket.outputStream.apply {
                write(frame)
                flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /** The next message, or why there is none: the caller then closes the connection with it. */
    fun next(): Next = when (val read = reader.read()) {
        is FrameRead.Frame -> when (val decoded = LinkCodec.decode(read.payload)) {
            is Decoded.Read -> Next.Got(decoded.value)
            is Decoded.Bad -> Next.Ended(LinkClose.Bad(decoded.reason))
        }
        FrameRead.End -> Next.Ended(LinkClose.PeerLeft)
        FrameRead.Idle -> Next.Ended(LinkClose.Idle)
        is FrameRead.Broken -> Next.Ended(LinkClose.Frame(read.error))
    }

    /** Once the link is up: frames written on a thread of their own, in order. False when the connection closed meanwhile. */
    fun startWriter(): Boolean {
        val output = try {
            (ssl ?: return false).outputStream
        } catch (_: IOException) {
            // A closed TLS socket gives no stream.
            close(LinkClose.PeerLeft)
            return false
        }
        writer = thread(isDaemon = true, name = "link-writer") { write(output) }
        // close() may have looked for the writer before it was there.
        if (isClosed) writer?.interrupt()
        return !isClosed
    }

    /** [message] queued for the writer: [Sent.Queued], or why not. */
    fun send(message: LinkMessage): Sent {
        val frame = LinkCodec.encode(message)?.let(LinkFrames::encode) ?: return Sent.TooBig
        return if (enqueue(frame)) Sent.Queued else Sent.Closed
    }

    /**
     * [message] queued as the last frame, then the connection closed for [reason] once the writer has written it: a
     * peer's last word before the end (pairing's result, L22), which closing at once could cut off. No frame is taken
     * after it. The writer must run, or start later; one that can't take it closes the connection now.
     */
    fun finish(message: LinkMessage, reason: LinkClose) {
        if (isClosed || !ending.compareAndSet(null, reason)) return
        val frame = LinkCodec.encode(message)?.let(LinkFrames::encode)
        if (frame == null || !queue.offer(frame) || !queue.offer(END)) close(reason)
    }

    /**
     * [message] as the last frame in place of every one still waiting, then the connection closed for [reason] once it is
     * written, and within [LinkLimits.frameMillis] whatever the writer does: Remove's refusal (L30), which what was queued
     * for a phone no longer paired mustn't hold back, nor a phone that reads slowly. No frame is taken after it. A frame
     * the writer has begun is finished first, within the same time.
     */
    fun endWith(message: LinkMessage, reason: LinkClose) {
        if (isClosed || !ending.compareAndSet(null, reason)) return
        queue.clear()
        val frame = LinkCodec.encode(message)?.let(LinkFrames::encode)
        if (frame == null || !queue.offer(frame) || !queue.offer(END)) return close(reason)
        cutOff = try {
            timer.schedule({ close(reason) }, limits.frameMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // The timer is shut down with the server, which closes its connections itself: this one now.
            close(reason)
            null
        }
        // close() may have looked for the timer before it was there.
        if (isClosed) cutOff?.cancel(false)
    }

    /** [frame] queued for the writer; false when closed, or closed now, as the queue is full: the peer stopped reading. */
    fun enqueue(frame: ByteArray): Boolean {
        if (isClosed || ending.get() != null) return false
        if (queue.offer(frame)) return true
        close(LinkClose.Backlog)
        return false
    }

    private fun write(output: OutputStream) {
        try {
            while (!isClosed) {
                val frame = queue.take()
                if (frame === END) return close(ending.get() ?: LinkClose.Closed)
                // A peer whose window stays shut leaves the write waiting: a timer ends that.
                val stuck = timer.schedule({ close(LinkClose.Backlog) }, limits.frameMillis, TimeUnit.MILLISECONDS)
                try {
                    output.write(frame)
                    output.flush()
                } finally {
                    stuck.cancel(false)
                }
            }
        } catch (_: InterruptedException) {
            // Closed.
        } catch (_: IOException) {
            close(LinkClose.PeerLeft)
        } catch (_: Throwable) {
            // Anything else, a timer shut down with the server among them: the connection closes and the thread ends, as
            // L13 has it. A thread of its own runs no coroutine whose cancellation this could swallow.
            close(LinkClose.Failed)
        }
    }

    sealed interface Next {
        class Got(val value: LinkMessage) : Next

        class Ended(val why: LinkClose) : Next
    }
}

/** The writer's sign that [LinkConnection.finish]'s or [LinkConnection.endWith]'s frame was the last: closed once it is written. */
private val END = ByteArray(0)

/**
 * Whether one more event fits in a sliding window of [windowMillis] holding at most [limit]; one that fits is counted.
 * Not thread-safe: each is kept by one thread, or under its owner's lock.
 */
internal class RateWindow(private val limit: Int, private val windowMillis: Long) {
    private val times = LongArray(limit)
    /** Where the next time goes: once full, the oldest. */
    private var next = 0
    private var filled = 0

    fun take(now: Long): Boolean {
        if (filled == limit && now - times[next] < windowMillis) return false
        times[next] = now
        next = (next + 1) % limit
        if (filled < limit) filled++
        return true
    }
}

/** A timer on one daemon thread, its cancelled tasks let go at once. */
internal fun linkTimer(name: String): ScheduledThreadPoolExecutor =
    ScheduledThreadPoolExecutor(1) { task -> Thread(task, name).apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is all that's left to do.
    }
}

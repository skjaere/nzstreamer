package io.skjaere.usenet.serve

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Which open stream of a served file's source each answer reads (L56). Over Usenet a stream is costly, so a file has few,
 * and an answer that ends leaves its stream parked for the next.
 */
internal interface Upstreams : Closeable {
    /**
     * Opens the file's head and parks it, before the device is told to load the file: its first request, `bytes=0-`, then
     * reads at once. Blocks while the source opens; throws what the source throws, and that request opens its own.
     */
    fun openHead()

    /**
     * The upstream of an answer from [position]. Its reads give 0 while it waits for a stream, as a source with nothing yet
     * does; closing it parks its stream at the byte it reached. [onTaken] is called on another answer's thread when that one
     * takes its stream, before any of its reads can fail for it, so the answer knows why it ends: it is to close its
     * connection, and nothing more, as the streams wait for it. Throws an IOException for an answer refused after a failure
     * there (L76).
     */
    fun open(position: Long, onTaken: () -> Unit): Upstream

    /** Closes every stream, parked or read, and opens no more. */
    override fun close()
}

/**
 * One stream feeding playback and one for a far read (L56), straight from the source: at most [ServeLimits.upstreams]
 * open at once. The Shield reads one connection in steady play and closes it at each seek, so an answer that ends parks its
 * stream at the byte it reached, and an answer from there or up to [ServeLimits.skipBytes] on takes it, the bytes between
 * read and dropped; one parked for [ServeLimits.parkMillis] is closed. An answer that finds no such stream opens one,
 * closing a parked one first when they are all open; with all of them read, it waits [ServeLimits.upstreamWaitMillis],
 * then takes the one whose reads last made progress longest ago, never one inside the head guard (L101), whose connection
 * closes and is asked again.
 *
 * A stream that fails at a byte (L76) is closed, never parked, and for [ServeLimits.failureMillis] after the last failure
 * there, answers from that byte or up to [ServeLimits.skipBytes] before it open at most one stream every
 * [ServeLimits.retryMillis]; the others are refused. A source that reads ahead may say it failed short of the byte, a
 * different way short each time, so failures within [SHORTFALL_BYTES] of one another are one, from the first byte seen to
 * the last; a stream that read it and reads on more than that past it ends it.
 *
 * Each upstream is read on its answer's thread alone; everything else may come from any thread.
 */
internal class DirectUpstreams(
    private val source: ByteSource,
    private val size: Long,
    private val limits: ServeLimits,
    private val clock: () -> Long,
    private val timer: ScheduledExecutorService,
    private val gaps: (LongRange) -> Unit,
) : Upstreams {
    /** An open stream of the source, opened at [start], at [position]: read by one answer at a time, or parked between answers. */
    private class Stream(val upstream: Upstream, val start: Long) {
        var position = start

        /** Closes it when parked too long; null while it is read. */
        var expiry: ScheduledFuture<*>? = null
    }

    /**
     * A failure seen from byte [first] to byte [last]: at one byte, or at several a little short of the one that failed
     * ([SHORTFALL_BYTES]). The last one there [since], and when an answer last opened a stream from there.
     */
    private class Failure(var first: Long, var last: Long, var since: Long, var openedAt: Long?)

    /** What an answer that needs a stream does once the lock is let go: closes [gone], one parked or taken, then opens its own. */
    private class Opening(val gone: Stream?)

    private val lock = ReentrantLock()
    /** Signalled when a stream is parked or closed, and on [close]: an answer waiting for one looks again. */
    private val freed = lock.newCondition()

    // Under [lock].
    private val parked = mutableListOf<Stream>()
    private val leases = mutableListOf<Lease>()
    /** Streams being opened: each holds its place. */
    private var opening = 0
    private val failures = mutableListOf<Failure>()
    private var closed = false

    private val inUse: Int get() = parked.size + leases.count { it.stream != null } + opening

    override fun openHead() {
        lock.withLock {
            if (closed || inUse >= limits.upstreams || parked.any { it.position == 0L }) return
            opening++
        }
        var head: Upstream? = null
        try {
            head = source.open(0, gaps)
        } finally {
            val unused = lock.withLock {
                opening--
                freed.signalAll()
                head?.let { if (closed) it else park(Stream(it, 0)) }
            }
            unused?.closeQuietly()
        }
    }

    override fun open(position: Long, onTaken: () -> Unit): Upstream = lock.withLock {
        if (closed) throw IOException("closed")
        val now = clock()
        failures.removeAll { now - it.since >= limits.failureMillis }
        failures.firstOrNull { position in it.first - limits.skipBytes..it.last }?.let { failure ->
            val opened = failure.openedAt
            // Asked again too soon after a failure here: headers and a close, as the receiver's player asks again and again.
            if (opened != null && now - opened < limits.retryMillis) throw IOException("refused after a failure")
            failure.openedAt = now
        }
        Lease(position, now, onTaken)
    }

    override fun close() {
        val streams = lock.withLock {
            if (closed) return
            closed = true
            freed.signalAll()
            val all = parked.toList() + leases.mapNotNull { it.stream }
            all.forEach { it.expiry?.cancel(false) }
            parked.clear()
            all
        }
        streams.forEach { it.upstream.closeQuietly() }
    }

    /** Where the parked streams are, in the order they were parked. */
    internal fun parked(): List<Long> = lock.withLock { parked.map { it.position } }

    /** Under [lock]: parks [stream] for the next answer, until the park time ends; or, past the end or the timer gone, gives it back to be closed. */
    private fun park(stream: Stream): Upstream? {
        if (stream.position >= size) return stream.upstream
        stream.expiry = try {
            timer.schedule({ expire(stream) }, limits.parkMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // The server is closing, and closes its streams itself.
            return stream.upstream
        }
        parked += stream
        freed.signalAll()
        return null
    }

    private fun expire(stream: Stream) {
        lock.withLock {
            if (!parked.remove(stream)) return
            freed.signalAll()
        }
        stream.upstream.closeQuietly()
    }

    /**
     * Under [lock]: a failure at byte [at]. Within [SHORTFALL_BYTES] of one seen before, it is that one, seen short of its
     * byte or at it at last: the answers asking again there stay one stream every [ServeLimits.retryMillis].
     */
    private fun failed(at: Long) {
        val now = clock()
        val failure = failures.firstOrNull { at in it.first - SHORTFALL_BYTES..it.last + SHORTFALL_BYTES }
        if (failure == null) {
            failures += Failure(at, at, now, null)
        } else {
            failure.first = minOf(failure.first, at)
            failure.last = maxOf(failure.last, at)
            failure.since = now
        }
    }

    /**
     * Under [lock]: what [lease] does to get a stream. A parked one at its byte or a little before is taken at once (null);
     * else a place is held for a new one ([Opening]), or it can only wait (thrown away by the caller: [WAIT]).
     */
    private fun next(lease: Lease): Opening? {
        val from = lease.from
        parked.filter { it.position <= from && from - it.position <= limits.skipBytes }.maxByOrNull { it.position }?.let { stream ->
            parked.remove(stream)
            stream.expiry?.cancel(false)
            stream.expiry = null
            lease.bind(stream)
            return null
        }
        if (inUse < limits.upstreams) return Opening(null).also { opening++ }
        // All open: a parked one goes first, the one parked longest.
        parked.firstOrNull()?.let { stream ->
            parked.remove(stream)
            stream.expiry?.cancel(false)
            return Opening(stream).also { opening++ }
        }
        if (clock() - lease.askedAt < limits.upstreamWaitMillis) return WAIT
        val victim = leases.filter { it !== lease && it.stream != null && !it.guarded }.minByOrNull { it.progressAt } ?: return WAIT
        return Opening(victim.take()).also { opening++ }
    }

    /** An answer's view of a stream: waits for one, reads it from the answer's byte, parks it when closed. */
    private inner class Lease(val from: Long, val askedAt: Long, private val onTaken: () -> Unit) : Upstream {
        // Under [lock].
        var stream: Stream? = null
        var progressAt = askedAt
        var delivered = 0L
        /** Bytes of its stream still to read and drop before its own: it took a parked stream short of its byte. */
        var skip = 0L
        /** Inside a [read]: a close from another thread leaves it to the read to let the stream go. */
        var reading = false
        var released = false
        var taken = false
        /** Its stream failed: closed, never parked. */
        var broken = false

        /** Inside the head guard (L101): an answer from byte 0 that has given fewer than the guard's bytes. */
        val guarded: Boolean get() = from == 0L && delivered < limits.headGuardBytes

        private var scrap: ByteArray? = null

        /**
         * Under [lock]: another answer takes its stream, given back to be closed. Its answer is told here, before the lock is
         * let go, so none of its reads can fail for the take before the server knows why it ends: never as a failure of the
         * source's, which would count against the release (L88).
         */
        fun take(): Stream? {
            val held = stream
            taken = true
            stream = null
            leases.remove(this)
            onTaken()
            return held
        }

        /** Under [lock]. */
        fun bind(stream: Stream) {
            this.stream = stream
            skip = from - stream.position
            leases += this
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            lock.withLock {
                if (closed || taken || broken || released) throw IOException("closed")
                reading = true
            }
            var ended = false
            try {
                val stream = acquire(waitMillis) ?: return 0
                while (skip > 0) {
                    val drop = scrap ?: ByteArray(CHUNK).also { scrap = it }
                    val n = readFrom(stream, drop, 0, minOf(drop.size.toLong(), skip).toInt(), waitMillis)
                    if (n <= 0) {
                        ended = n < 0
                        return n
                    }
                    lock.withLock { skip -= n }
                }
                val n = readFrom(stream, buffer, offset, length, waitMillis)
                if (n > 0) {
                    lock.withLock {
                        delivered += n
                        progressAt = clock()
                    }
                }
                ended = n < 0
                return n
            } finally {
                val unused = lock.withLock {
                    reading = false
                    if (ended) broken = true
                    if (released) letGo() else null
                }
                unused?.closeQuietly()
            }
        }

        /** [stream] read into [buffer]: a failure there, or an end short of the file's, is remembered at its byte (L76). */
        private fun readFrom(stream: Stream, buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            val at = stream.position
            var n = -1
            var failed = true
            try {
                n = stream.upstream.read(buffer, offset, length, waitMillis)
                failed = n < 0 && at < size
            } finally {
                lock.withLock {
                    if (n > 0) {
                        stream.position += n
                        // Read on from a byte that failed before, past where a failure there could have been seen: it reads again.
                        if (failures.isNotEmpty()) {
                            failures.removeAll { stream.start <= it.last && it.last + SHORTFALL_BYTES in at until stream.position }
                        }
                    }
                    // Closed under it as taken, or with the file: no failure of the source's.
                    if (failed && !taken && !closed) {
                        broken = true
                        failed(at)
                    }
                }
            }
            return n
        }

        /** Its stream: one parked, or one opened when there is room, or null when none came within [waitMillis]. */
        private fun acquire(waitMillis: Long): Stream? {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
            while (true) {
                val opening = lock.withLock {
                    if (closed || taken) throw IOException("closed")
                    stream?.let { return it }
                    // Closed by its answer while waiting: nothing more to wait for.
                    if (released) return null
                    val step = next(this)
                    if (step === WAIT) {
                        val left = until - System.nanoTime()
                        if (left <= 0) return null
                        freed.awaitNanos(left)
                        return@withLock null
                    }
                    step ?: return stream
                } ?: continue
                return open(opening)
            }
        }

        /** Opens its own stream in the place [opening] holds, after letting go of the one that held it. */
        private fun open(opening: Opening): Stream? {
            // A taken one's answer was told as it was taken, so it knows why it ends before its stream closes under it.
            opening.gone?.upstream?.closeQuietly()
            var upstream: Upstream? = null
            try {
                upstream = source.open(from, gaps)
            } finally {
                val unused = lock.withLock {
                    this@DirectUpstreams.opening--
                    freed.signalAll()
                    val opened = upstream
                    when {
                        opened == null -> {
                            broken = true
                            if (!closed) failed(from)
                            null
                        }
                        closed -> opened
                        // Its answer ended while it opened: kept for the next, which may well ask from here.
                        released -> park(Stream(opened, from))
                        else -> {
                            bind(Stream(opened, from))
                            null
                        }
                    }
                }
                unused?.closeQuietly()
            }
            return lock.withLock { stream }
        }

        /** Under [lock]: lets its stream go, parked at the byte it reached, or gives it back to be closed. */
        private fun letGo(): Upstream? {
            val held = stream ?: return null
            stream = null
            leases.remove(this)
            freed.signalAll()
            return if (closed || broken) held.upstream else park(held)
        }

        override fun close() {
            val unused = lock.withLock {
                if (released) return
                released = true
                freed.signalAll()
                // A read under way lets it go as it returns, within its wait.
                if (reading) null else letGo()
            }
            unused?.closeQuietly()
        }
    }

    internal companion object {
        /** What the bytes skipped are read in. */
        private const val CHUNK = 64 * 1024

        /** [next]'s answer when an answer can only wait. */
        private val WAIT = Opening(null)

        /**
         * How far short of the byte that failed a source may say it failed (L76), dropping what it held ahead: the app's
         * does, as the engine's ktor channel, closed with the failure as its cause, drops its bytes (148,352 on the Shield in
         * item 6(b)). That channel holds up to 1 MiB in each of its two buffers with one article's write on top of each
         * (a remux's articles are about 3.84 MB, so some 9.3 MiB in all), and the app's adapter may lose the 256 KiB chunk
         * it was filling, though never its queue. Past [ServeLimits.skipBytes], so a constant of its own, with a margin.
         */
        const val SHORTFALL_BYTES = 16L * 1024 * 1024
    }
}

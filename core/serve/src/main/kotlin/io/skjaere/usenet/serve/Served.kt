package io.skjaere.usenet.serve

import io.skjaere.usenet.handoff.SetupToken
import java.net.InetAddress

/**
 * A file the server serves, at [url] until [revoke]: for one LOAD on a Cast device (L55). [path] and [url] hold its token:
 * give them to the device alone, never log them or put them in saved state; [toString] has only its [id]. The token isn't
 * secret on the home network (the receiver tells every sender its media's URL), so the first GET pins the file to its
 * peer, and the device it was served for ([expected]) takes the pin whenever it asks.
 */
class Served internal constructor(
    /** What events and logs name it by: a number, from 1, in the order files were served. */
    val id: Int,
    private val token: SetupToken,
    internal val file: ServedFile,
    internal val expected: InetAddress?,
    authority: String,
    private val server: ServeServer,
) {
    /** `/<token>/v.<extension>`. */
    val path: String = "/${token.value}/v.${file.extension}"

    /** `http://<ip>:<port>/<token>/v.<extension>`, for the LOAD. */
    val url: String = "http://$authority$path"

    /** Guards what follows, and [counters]'s fields. */
    private val lock = Any()

    // Under the server's lock: who holds the pin, and whether the file is still served.
    internal var pin: InetAddress? = null
    internal var revoked = false

    private var counters = ServeCounters()
    /** The stretches of zeros passed on, merged where they touch, in file order. */
    private val gaps = ArrayList<LongRange>()

    /**
     * Its answers' bodies, by where each began and how far it wrote, in the order they began: one is let go once a later
     * one began as far back, which then says more of every byte it could. So they begin further on as they go.
     */
    private val reads = ArrayList<Read>()

    /** The streams of its source its answers read (L56). */
    internal val upstreams: Upstreams = server.upstreamsOf(file, ::gap)

    /** What its requests came to so far. */
    fun counters(): ServeCounters = synchronized(lock) { counters }

    /** The stretches of zeros its source passed on so far (file offsets, merged where they touch), in file order. */
    fun gaps(): List<LongRange> = synchronized(lock) { gaps.toList() }

    /**
     * Of [gaps], those the device was sent what follows (L104): the answer that last began at or before a gap's end wrote
     * past it. A read further on (the index at the file's end, L96) says nothing of the stream that plays through a gap, and
     * an answer that takes the device back before one says it hasn't what follows until it too goes past.
     */
    fun passed(): List<LongRange> = synchronized(lock) {
        gaps.filter { gap -> reads.lastOrNull { it.first <= gap.last + 1 }?.let { it.position > gap.last + 1 } == true }
    }

    /**
     * How far the device was sent past [byte] (L134): one past the last byte written by the latest answer that began at or
     * before it and wrote past it; null when none did. A read further on (the index at the file's end, L96) says nothing of
     * it, and a later answer from before it does, though it went less far.
     */
    fun reach(byte: Long): Long? = answered(byte)?.let { it.last + 1 }

    /**
     * The bytes the answer [reach] goes by wrote, from where it began to the last it wrote; null when none wrote past
     * [byte]. Where an answer began tells the stream the device plays from one a seek back left behind, which began
     * further back.
     */
    fun answered(byte: Long): LongRange? = synchronized(lock) {
        reads.lastOrNull { it.first <= byte && it.position > byte }?.let { it.first until it.position }
    }

    /** Stops serving it now: its path answers 404 from here on, and its open connections close, their upstreams with them. */
    fun revoke() = server.revoke(this)

    /**
     * Opens the file's head ahead of the LOAD, so the device's first request (`bytes=0-`) is answered from it at once (L56).
     * Blocks while the source opens; throws what the source throws, and that request then opens its own.
     */
    fun openHead() = upstreams.openHead()

    /** Whether [path] (decoded, without its query) names this file: its token, however a client folded its case, and its extension. */
    internal fun answers(path: String): Boolean {
        val parts = path.removePrefix("/").split('/')
        return parts.size == 2 && parts[1] == "v.${file.extension}" && SetupToken.isTokenShaped(parts[0]) && token.matches(parts[0])
    }

    internal fun count(change: (ServeCounters) -> ServeCounters) = synchronized(lock) { counters = change(counters) }

    /** An answer's body from [first]: how far it writes is told to the [Read] given ([wrote]). */
    internal fun reading(first: Long): Read = synchronized(lock) {
        reads.removeAll { it.first >= first }
        Read(first).also { reads += it }
    }

    /** [read]'s answer wrote [n] bytes more, up to [position] (one past its last byte). */
    internal fun wrote(read: Read, n: Int, position: Long) = synchronized(lock) {
        counters = counters.copy(bytesSent = counters.bytesSent + n)
        read.position = position
    }

    /** An answer's body: where it began, and one past the last byte it wrote. Under the file's lock. */
    internal class Read(val first: Long) {
        var position = first
    }

    /** A stretch of zeros its source passed on, merged with those it touches: [ServeCounters.gaps] is how many merged ones there are. */
    internal fun gap(range: LongRange) {
        if (range.isEmpty()) return
        synchronized(lock) {
            var from = range.first
            var to = range.last
            val at = gaps.indexOfFirst { it.last + 1 >= from }.let { if (it < 0) gaps.size else it }
            var end = at
            while (end < gaps.size && gaps[end].first <= to + 1) {
                from = minOf(from, gaps[end].first)
                to = maxOf(to, gaps[end].last)
                end++
            }
            gaps.subList(at, end).clear()
            gaps.add(at, from..to)
            counters = counters.copy(gaps = gaps.size)
        }
    }

    override fun toString() = "Served(#$id)"
}

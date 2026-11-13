package io.skjaere.usenet.serve

import io.skjaere.usenet.handoff.HeadLimits

/**
 * How big a request may be, how long its parts may take and how many connections the server holds (L54, L101). Tests
 * shrink them; the defaults are the design's, from what the Shield's receiver did (items 6(a) and 6(b)).
 */
data class ServeLimits(
    val requestLine: Int = 2048,
    /** The header lines together, their line ends and the empty line included. */
    val headerBytes: Int = 8192,
    val headers: Int = 64,
    /** The request's head must be complete within this of the accept; the Shield's came within 23 ms. No deadline for the body. */
    val headMillis: Long = 10_000,
    /** Connections serving a body at once: a valid token from the peer that holds the pin. The Shield never used more than 2. */
    val serving: Int = 8,
    /** Connections still reading their head, beside those serving: a newcomer past them displaces the oldest. */
    val reading: Int = 8,
    /** Of those, from one address other than the expected device and the peer that holds a pin. */
    val readingPerAddress: Int = 2,
    /** Accepts in [acceptWindowMillis] from such an address; more are closed unanswered. */
    val acceptsPerWindow: Int = 30,
    val acceptWindowMillis: Long = 60_000,
    /**
     * A writer whose socket write has made no progress for this long is closed: a paused receiver holds its connection with
     * no progress for the whole pause, and opens a new one when it needs bytes. One waiting for its source isn't stalled.
     */
    val stallMillis: Long = 90_000,
    /**
     * The head guard (L101): a response from byte 0 is never evicted, nor cut for its upstream, and its stall limit is
     * [headStallMillis], until it has written this many bytes. The Shield never retries a cut of its first answer while it
     * holds less than the file's first 32 KiB, and the media is gone at once (item 6(b): a FIN was fatal at 26,624 bytes
     * and retried from 32,768 up; an RST, which loses what was in flight, fatal at 64 KiB and retried from 256 KiB up,
     * each retry from the last byte it had). 1 MiB is that with a margin.
     */
    val headGuardBytes: Long = 1L * 1024 * 1024,
    /**
     * The stall limit inside the head guard: [stallMillis], as any writer's, since item 6(b). The Shield's first answer,
     * paused with 12.6 MB written and closed 58 s later, was asked again from where the receiver was once its next read
     * after play found the close (12.8 s after play), with no error: once it holds the file's first 32 KiB, a close harms
     * nothing a pause needs.
     */
    val headStallMillis: Long = stallMillis,
    /** While a connection waits for its source, its socket is looked at this often for a hang-up. */
    val pollMillis: Long = 250,
    /**
     * Streams of a file's source open at once (L56): one feeding playback and one for a far read, such as the Matroska
     * index at the end. Over Usenet each one holds provider connections and its own read-ahead.
     */
    val upstreams: Int = 2,
    /** A parked stream serves an answer from the byte it reached or up to this far after it, the bytes between read and dropped. */
    val skipBytes: Long = 4L * 1024 * 1024,
    /** A parked stream unused this long is closed: it keeps the engine busy, deferring a provider rebuild, and holds its read-ahead. */
    val parkMillis: Long = 5 * 60_000L,
    /**
     * An answer that needs a stream while all of them are read waits this long for one to be parked, then takes the one
     * whose reads last made progress longest ago, never one inside the head guard.
     */
    val upstreamWaitMillis: Long = 10_000,
    /**
     * After a source fails at a byte (L76), answers from there, or up to [skipBytes] before it, open at most one stream
     * every [retryMillis] for this long after the last failure there; the others get their headers and a close. A
     * receiver's player asks again some 30 times in 30 s, which would otherwise be as many downloads of a broken part.
     */
    val failureMillis: Long = 30_000,
    val retryMillis: Long = 5_000,
) {
    init {
        require(serving > 0 && reading > 0 && readingPerAddress > 0 && acceptsPerWindow > 0 && pollMillis > 0 && upstreams > 0) { "a limit of nothing" }
    }

    /** What the request's reader holds the head to. */
    internal val head: HeadLimits get() = HeadLimits(requestLine, headerBytes, headers)
}

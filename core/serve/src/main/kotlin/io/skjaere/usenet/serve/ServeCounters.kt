package io.skjaere.usenet.serve

/**
 * What one served file's requests came to (L75), as the app reads it to tell what the Cast device did (L60, L88, L102):
 * counts and times on the server's clock, never a path, a token or an address.
 */
data class ServeCounters(
    /** Requests answered for the file: from its pin's peer, or any while it has none, a HEAD or OPTIONS too. */
    val requests: Int = 0,
    /** 206 answers to GETs, sent with their bodies. */
    val partials: Int = 0,
    /** When the first of [requests] came; null before. */
    val firstRequestAt: Long? = null,
    /** Body bytes written to the sockets. */
    val bytesSent: Long = 0,
    /** Stretches of zeros its source passed on, counted once per merged range (L53). */
    val gaps: Int = 0,
    /** Answers its source failed (L76). */
    val upstreamFailures: Int = 0,
    /**
     * Of [upstreamFailures], answers from byte 0: the head the device reads before it plays, whose cut it can only call a
     * file it couldn't open (L76, L101).
     */
    val headFailures: Int = 0,
    /** A peer other than the expected device took the pin (L55): the device fetched from another address, or someone else did. */
    val mismatches: Int = 0,
    /** Requests refused with a 403: from a peer other than the one that holds the pin. */
    val refused: Int = 0,
    /** Connections closed as their peer lost the pin to the expected device. */
    val unpinned: Int = 0,
    /** Connections evicted for a newer one: for its serving slot (L54), or for its stream of the source (L56). */
    val evictions: Int = 0,
    /** Connections closed for writes with no progress (L54, L101). */
    val stalls: Int = 0,
    /**
     * Time its answers spent waiting in reads of their source (L133), whether bytes then came or none did: waiting on the
     * providers, or on a stream of the source another answer held.
     */
    val sourceWaitMillis: Long = 0,
    /** Time its answers spent inside writes to their sockets (L133): waiting for the device to take what was sent. */
    val peerWaitMillis: Long = 0,
)

/** What the server did with requests no served file could answer for. */
data class ServerCounters(
    /** 404s: a path that names no file served, or one revoked. Never locking anything. */
    val notFound: Int = 0,
    /** Connections closed unanswered, by why. */
    val dropped: Map<ServeDrop, Int> = emptyMap(),
)

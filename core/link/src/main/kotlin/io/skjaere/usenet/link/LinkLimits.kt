package io.skjaere.usenet.link

/**
 * How long a link's steps may take, how many connections a TV takes and how fast a peer may send (L24). Tests shrink
 * them; the defaults are the design's. A phone holds what a TV sends to the same frame and rate limits.
 */
data class LinkLimits(
    /** A frame must be complete within this of its first byte (L5), so a slow sender can't hold a reader. */
    val frameMillis: Long = 10_000,
    /**
     * TLS and the hellos, within this of the accept on a TV or the connect on a phone. A timer closes the socket then,
     * since a socket's timeout covers one read, not a whole handshake.
     */
    val helloMillis: Long = 10_000,
    /** A phone's connect, everywhere (L12). */
    val connectMillis: Int = 2_000,
    /** How often a phone pings a TV. */
    val pingMillis: Long = 15_000,
    /** Without a complete frame for this long, a link closes. */
    val idleMillis: Long = 45_000,
    /** Connections a TV holds at once: 4 for use, 1 pairing, 1 waiting. */
    val connections: Int = 6,
    /** Of those, the paired phones' links. */
    val useLinks: Int = 4,
    /** Connections from one address before their hello. */
    val perAddressBeforeHello: Int = 2,
    /** Accepts in [acceptWindowMillis] from an address without a live paired link; more are closed before TLS. */
    val acceptsPerWindow: Int = 10,
    val acceptWindowMillis: Long = 60_000,
    /** Frames waiting to be written to a peer; one more closes it, as a peer that stops reading. */
    val writeQueue: Int = 64,
    /** Frames a second on one connection: a TV answers more commands with `Rejected{too-fast}`, a phone drops more. */
    val framesPerSecond: Int = 20,
    /** Status frames a second a TV sends, and a phone takes. */
    val statusPerSecond: Int = 4,
    /** The TV's question, "Pair with Galaxy phone?", closes after this unanswered, nothing saved (L22). */
    val questionMillis: Long = 60_000,
    /** Pairing closes by itself this long after Pair a phone opened, or after its last ceremony ended (L22). */
    val pairingMillis: Long = 600_000,
    /** Failed ceremonies from one address in one opening of Pair a phone; then it is refused `too-many` (L22). */
    val pairingFailures: Int = 3,
    /**
     * After a failed ceremony, from any address, the next commit waits this long, twice as long for each failure in a row
     * in one opening, up to 16 times this; a pairing ends the wait. A relay with many addresses then gets a few tries
     * against the one number a phone shows, not hundreds (L21). 0 waits not at all.
     */
    val pairingPauseMillis: Long = 2_000,
) {
    init {
        require(framesPerSecond > 0 && statusPerSecond > 0 && acceptsPerWindow > 0 && writeQueue > 0 && connections > 0 && pairingFailures > 0) { "a limit of nothing" }
    }
}

/** Monotonic milliseconds: what the link's deadlines are counted in. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

package io.skjaere.usenet.serve

/** A request's method, of those the server answers. */
enum class ServeMethod { Get, Head, Options }

/** Why the server closed a connection without an answer. */
enum class ServeDrop {
    /** The filter refused its address: not a private IPv4 one, in the app. */
    NotAllowed,

    /** Over [ServeLimits.acceptsPerWindow] from an address that is neither the expected device nor a pin's. */
    TooManyAccepts,

    /** [ServeLimits.readingPerAddress] from its address were already reading their heads. */
    TooManyFromAddress,

    /** Every head slot was taken, and a newcomer took the slot of this one, the oldest. */
    Displaced,

    /** A GET for a served file found every serving slot held by a response that can't go (L101). */
    Full,

    /** A TLS ClientHello: something trying https. */
    Tls,

    /** Its request's head didn't come whole within [ServeLimits.headMillis] of the accept. */
    TimedOut,

    /** The peer hung up before its request's head was whole. */
    Left,
}

/** How a response with a body ended. */
enum class ServeEnd {
    /** Its last byte was written. */
    Done,

    /** The peer hung up: a seek, a stop, the receiver's own close. */
    Left,

    /** Its writes made no progress for the stall limit (L54, L101). */
    Stalled,

    /** A newer connection took its serving slot: its progress was the oldest (L54). */
    Evicted,

    /** Another answer of its file, which had waited for a stream of the source, took its stream: its reads' progress was the oldest (L56). */
    Taken,

    /** Its source failed, or ended before the file's size (L76): closed at the byte it reached, never a 5xx. */
    UpstreamFailed,

    /** Its peer held the pin, and the expected device took it (L55). */
    Unpinned,

    /** Its file was revoked (L55). */
    Revoked,

    /** The server closed. */
    Closed,
}

/**
 * What the server tells the app, on its own threads: post it to the app's own and don't block. None holds a token, a path,
 * an address, a title or an exception: each may be logged as it is. A file is the [Served.id] it was served under. Nothing
 * comes after [ServeServer.close].
 */
sealed interface ServeEvent {
    /** A connection closed unanswered. */
    data class Dropped(val why: ServeDrop) : ServeEvent

    /** A request answered with [status]: of [file], when it named one served and its peer could ask; of [method], once read. */
    data class Answered(val file: Int?, val method: ServeMethod?, val status: Int) : ServeEvent

    /** [file]'s pin was set by a GET: [expected] when from the device the file was served for, else from another peer. */
    data class Pinned(val file: Int, val expected: Boolean) : ServeEvent

    /** An answer of [file] with a body, from byte [from], ended after [sent] bytes of it. */
    data class Ended(val file: Int, val from: Long, val sent: Long, val why: ServeEnd) : ServeEvent

    /**
     * The listening socket was lost, as Android destroys the sockets bound to an address it takes away (the phone's Wi-Fi
     * blipping, L74), or failed. Every file is still served and the connections go on; [ServeServer.relisten] listens
     * again on the same port, so the URL a device holds answers again.
     */
    data object ListenerLost : ServeEvent
}

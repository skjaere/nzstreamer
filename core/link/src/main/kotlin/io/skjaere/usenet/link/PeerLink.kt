package io.skjaere.usenet.link

import java.net.InetAddress

/**
 * A link that is up: TLS with a pinned key, both hellos said, frames both ways. [peer] is the key TLS met, never what the
 * peer claims (L23). [handshakeMillis] is how long TLS took, [protocol] and [cipherSuite] what it chose, [pv] the protocol
 * version both ends speak: what the app logs. It prints as the key's short id alone.
 */
class PeerLink internal constructor(
    val peer: KeyId,
    val address: InetAddress,
    val protocol: String,
    val cipherSuite: String,
    val handshakeMillis: Long,
    val pv: Int,
    private val connection: LinkConnection,
) {
    val isOpen: Boolean get() = !connection.isClosed

    /**
     * Queues [message] for the peer, without waiting. A peer that lets [LinkLimits.writeQueue] frames wait, or a write
     * wait past [LinkLimits.frameMillis], has stopped reading: the link closes ([LinkClose.Backlog]).
     */
    fun send(message: LinkMessage): Sent = connection.send(message)

    /**
     * [message] as the link's last word, then the link closed once it is written (its handler hears [LinkClose.Closed]):
     * Forget's Unpair (L30), which closing at once could cut off. Nothing is sent after it.
     */
    fun finish(message: LinkMessage) = connection.finish(message, LinkClose.Closed)

    /** Closes it now; its handler hears [LinkClose.Closed]. */
    fun close() = connection.close(LinkClose.Closed)

    override fun toString() = "PeerLink(${peer.short})"
}

/** What came of [PeerLink.send]. */
enum class Sent {
    Queued,

    /** Bigger than a frame: not sent, and the link goes on. */
    TooBig,

    /** The link is closed, or closed as this one found its queue full. */
    Closed,
}

/**
 * What a link's owner hears of it, on the link's own thread, in order: post it to the app's own before doing anything
 * with it, and don't block. Nothing comes after the owner's `close()`.
 */
interface LinkHandler {
    /** A message from the peer. The link answers pings itself; neither they nor their answers are heard. */
    fun received(link: PeerLink, message: LinkMessage)

    /** The link closed, and why; nothing more comes of it. */
    fun closed(link: PeerLink, reason: LinkClose)
}

/** Why a connection closed. All a caller learns: never a frame or an exception's words (L13). */
sealed interface LinkClose {
    /** This end closed it. */
    data object Closed : LinkClose

    /** The peer closed it, or the connection went. */
    data object PeerLeft : LinkClose

    /** No complete frame for [LinkLimits.idleMillis]. */
    data object Idle : LinkClose

    /** TLS and the hellos took longer than [LinkLimits.helloMillis]. */
    data object TimedOut : LinkClose

    /** A frame was refused. */
    data class Frame(val error: FrameError) : LinkClose

    /** A frame wasn't a message. */
    data class Bad(val reason: BadFrame) : LinkClose

    /** A message out of place: a TV's from a phone, say, or anything but a hello first. */
    data object Unexpected : LinkClose

    /** The peer stopped reading. */
    data object Backlog : LinkClose

    /** The same phone linked again: its newer link took this one's place. */
    data object Replaced : LinkClose

    /** Before its hello, its slot went to a newer connection (L24). */
    data object Evicted : LinkClose

    /** The peer refused this end. */
    data class Refused(val reason: Refusal) : LinkClose

    /** Something failed on this end. */
    data object Failed : LinkClose
}

/** The protocol versions an end speaks: [pv] the newest, [minPv] the oldest (L26). */
data class LinkVersions(val pv: Int, val minPv: Int) {
    init {
        require(minPv in 1..pv) { "versions from 1, the oldest first" }
    }

    /** The version a link with [peer] speaks: the highest both do. */
    internal fun common(peer: LinkMessage.Hello): Int = minOf(pv, peer.pv)

    /**
     * Why an end in [role] can't link with the end whose hello is [peer]: an update for whichever end is the older one;
     * null when they share a version. The peer's own range is taken as it says; [wellFormed] checks it.
     */
    internal fun refusal(peer: LinkMessage.Hello, role: Role): Refusal? = when {
        peer.minPv > pv -> update(role)
        minPv > peer.pv -> update(if (role == Role.Tv) Role.Phone else Role.Tv)
        else -> null
    }

    private fun update(role: Role) = if (role == Role.Tv) Refusal.UpdateTv else Refusal.UpdatePhone

    companion object {
        val V1 = LinkVersions(pv = 1, minPv = 1)

        /** Whether a hello's versions make sense: from 1, the oldest no newer than the newest. */
        internal fun wellFormed(hello: LinkMessage.Hello) = hello.minPv in 1..hello.pv
    }
}

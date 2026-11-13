package io.skjaere.usenet.app.casting

import androidx.media3.common.MediaItem
import io.skjaere.usenet.app.play.SessionMetadata
import java.net.InetAddress

/**
 * Cast's service (W27, L58, L63, L85): when it runs and holds the foreground, what its session says while a cast play
 * starts, and what ends a cast play and what else that end does. Pure, so a JVM test reads every case; the cast
 * package's CastService only wires it.
 */
object CastServiceRules {
    /**
     * L58: the foreground while Media3 asks for it ([required]) or while a cast play is served ([serving]): Media3 lets
     * it go 10 minutes into a pause, and a phone out of the foreground in Doze would no longer answer the device when it
     * plays again. The receiver closes a session 20 minutes into a pause, and the service stops with it.
     */
    fun foreground(required: Boolean, serving: Boolean) = required || serving

    /**
     * L85: the service builds its session (and its player, server and locks) only for a cast play this process started
     * ([playing]), and never on a TV. Otherwise (a sticky restart, another app's controller binding, a media button) it
     * builds nothing, and stops.
     */
    fun builds(television: Boolean, playing: Boolean) = !television && playing

    /**
     * L63: what the session has while a cast play starts, before the device has anything of it: one item, with no URI,
     * said as [said] says the play (a play of TMDB's by its titles, any other by the app's name alone, never by a
     * release's name), by its watch key. Its line names the [device] the play goes to, as Android [sdkInt] puts it, and is
     * its artist too, as the device's item has it ([CastMetadata.session], L120): what Android's notification, media card
     * and lock screen show under the title (L86), so the line stays as it is when the device takes over.
     */
    fun placeholder(said: SessionMetadata, device: String, sdkInt: Int): MediaItem {
        val line = CastMetadata.line(said.subtitle, device, sdkInt)
        return MediaItem.Builder().setMediaId(said.mediaId).setMediaMetadata(said.metadata().buildUpon().setSubtitle(line).setArtist(line).build()).build()
    }

    /**
     * L63: the device's player takes the session's place once its current item ([current]) is the one this play sent
     * ([sent]): never another sender's on a device taken over (W32), nor before anything was sent.
     */
    fun handsOver(sent: String?, current: String?) = sent != null && current == sent

    /**
     * L58: a swipe-away stops the cast, as Stop casting does, only while it is paused: not while the play starts
     * ([starting]: the pipeline, the device loading, a device taken over still showing the other sender's media), nor
     * while it buffers or plays ([playWhenReady]). Media3's own would also stop one starting or buffering.
     */
    fun stopsOnTaskRemoved(starting: Boolean, playWhenReady: Boolean) = !starting && !playWhenReady

    /** What a cast play's end does to the Cast session (L85), besides stopping the service and revoking its address (L55). */
    enum class Ending {
        /** The session ends too, and the receiver stops: Cast.endCurrentSession(true). */
        EndSession,

        /** The session stays connected and the device chosen: the next Play's tap starts the service again. */
        KeepSession,

        /** The session went by itself, or the device is another sender's: nothing more to end, nothing of theirs stopped. */
        SessionGone,
    }

    /** Why a cast play ends, as the service hears of it. */
    enum class Why {
        /** Stop casting: the notification's ×, the session's own command, the placeholder's. */
        StopCasting,

        /** Swiped away while paused (L58). */
        TaskRemovedPaused,

        /** The phone's address changed to another (L74). */
        AddressChanged,

        /** The device played it to the end. */
        Finished,

        /** Cancel on the play sheet (L82). */
        Cancelled,

        /** A failure before or after the load (L83, L88), or nothing to load (L84). */
        Failed,

        /** The server couldn't listen again at the address (L74, L88). */
        LostStream,

        /** The session ended by itself: the receiver's timers, a reconnection given up (L60). */
        SessionEnded,

        /** Another load replaced this play's on the device: another app's, another phone's. */
        Interrupted,

        /** The session is with another device now. */
        OtherDevice,
    }

    fun ending(why: Why): Ending = when (why) {
        Why.StopCasting, Why.TaskRemovedPaused, Why.AddressChanged -> Ending.EndSession
        Why.Finished, Why.Cancelled, Why.Failed, Why.LostStream -> Ending.KeepSession
        Why.SessionEnded, Why.Interrupted, Why.OtherDevice -> Ending.SessionGone
    }

    /**
     * Whether [why]'s end takes every cast play with it (L84, L85): the session ends, went, or is another device's, so a
     * play still starting has nowhere to go either. Another sender's load or stop ends only the play whose media it
     * replaced: the session lives on, and a play still starting takes the device back with its own load (W32).
     */
    fun endsEvery(why: Why): Boolean = when (why) {
        Why.StopCasting, Why.TaskRemovedPaused, Why.AddressChanged, Why.SessionEnded, Why.OtherDevice -> true
        Why.Finished, Why.Cancelled, Why.Failed, Why.LostStream, Why.Interrupted -> false
    }

    /**
     * Whether a cast play's end stops what it [loaded] on the device (L82): Cancel and a failure do. Not what played to
     * its end, nor another sender's media, nor where the session ends, which stops the receiver itself.
     */
    fun stopsDevice(why: Why, loaded: Boolean) = loaded && (why == Why.Cancelled || why == Why.Failed || why == Why.LostStream)

    /**
     * What a device's [event] ends of the play that [sent] its media there: the session's end ends any, a status only the
     * media of its own ([sent]) ended, by FINISHED or by another load (INTERRUPTED). This play's load on a device taken over
     * interrupts the other sender's media (W32): that isn't its end.
     */
    fun ends(event: CastEvent, sent: String?): Why? = when (event) {
        CastEvent.Ended -> Why.SessionEnded
        is CastEvent.Status -> when {
            sent == null || event.contentId != sent || event.state != CastPlayerState.Idle -> null
            event.idle == CastIdleReason.Finished -> Why.Finished
            event.idle == CastIdleReason.Interrupted -> Why.Interrupted
            else -> null
        }
        CastEvent.Started, CastEvent.Suspended, CastEvent.Resumed, is CastEvent.MediaError -> null
    }

    /**
     * Whether the play's device ([route]) is no longer the session's ([now]): none (it ended, Stop casting on the sheet,
     * This phone or a TV chosen), or another device's. A suspended session may come back (L91).
     */
    fun lost(now: CastLinkNow?, route: String): Why? = when {
        now == null -> Why.SessionEnded
        now.route != route -> Why.OtherDevice
        else -> null
    }
}

/**
 * Whether the cast service owes Android a turn in the foreground before it stops (L85). Android takes down an app whose
 * service it started for the foreground stops before it gets there, and any start may be one: the app's own for a cast
 * play, or another app's start of the exported service, with any action or none, where there is nothing to show. So it is
 * owed from the service's creation and from each start that finds it out of the foreground, until it is seen there; once
 * it stops, nothing more. Media3's own account can't say it: that says only whether the service was ever in the
 * foreground, which an instance a controller kept bound across two plays always was. Not thread-safe: the service's main
 * thread's.
 */
class ForegroundOwed {
    private var owed = false

    /** The service was made: for a start, perhaps one for the foreground, not yet delivered. */
    fun created() {
        owed = true
    }

    /** A start came, the service in the foreground ([inForeground]) or not. */
    fun started(inForeground: Boolean) {
        if (!inForeground) owed = true
    }

    /** The service is in the foreground now ([inForeground]), or not: once there, the starts before are answered. */
    fun seen(inForeground: Boolean) {
        if (inForeground) owed = false
    }

    /** The service stops now, in the foreground ([inForeground]) or not: whether it passes through the foreground first. */
    fun stopping(inForeground: Boolean): Boolean = (owed && !inForeground).also { owed = false }
}

/**
 * Where the service's server listens as the phone's address goes (L74): the flow's first value is always null, before any
 * network is heard, so the first address it has is bound; a later null is the Wi-Fi blipping, which the Cast session
 * rides out (L91); the same address back changes nothing; another address stops the cast. The listening socket can go
 * without the address changing (Android destroys sockets bound to an address it takes away, and a blip the flow merges
 * into no change, or EMFILE, says nothing there): lost while the bound address is current, the server listens again at
 * once; lost with it, as it comes back. Android destroys those sockets at once and tells the app of the address later, so
 * listening again at once can fail with the address gone: then it waits for the address to be back on the phone, by the
 * flow or as the service looks, and only a failure with the address there (its port taken) is the lost stream (L88).
 * Not thread-safe: the service's main thread's.
 */
class CastAddressWatch {
    sealed interface Act {
        /** Bind the server to [address]. */
        data class Bind(val address: InetAddress) : Act

        /** Listen again at the bound address and port, the files served kept. */
        data object Relisten : Act

        /** The address changed: the cast stops ([CastServiceRules.Why.AddressChanged]). */
        data object Stop : Act

        /** The address is the phone's and the server still can't listen there, tried once more: the device lost the stream (L88). */
        data object Lost : Act
    }

    private var bound: InetAddress? = null
    private var current: InetAddress? = null
    private var lost = false

    /** Listening again failed once with the address on the phone, and is being tried once more. */
    private var retried = false

    /** The address is now [now] (null: none). */
    fun address(now: InetAddress?): Act? {
        current = now
        val at = bound ?: return now?.let { Act.Bind(it) }
        return when {
            now == null -> null
            now != at -> Act.Stop
            lost -> Act.Relisten.also { lost = false }
            else -> null
        }
    }

    /** The server listens at [address] now. */
    fun bound(address: InetAddress) {
        bound = address
    }

    /** The listening socket was lost. */
    fun listenerLost(): Act? {
        val at = bound ?: return null
        if (current == at) return Act.Relisten
        lost = true
        return null
    }

    /**
     * A bind failed: the address there is now is bound, while nothing is bound; null with none, which the flow says when
     * it's back. The service asks a second after each failed bind, so an address that stays unbindable is tried each
     * second. An address the flow said while the bind ran or waited (which the service leaves to it), or one that went
     * and came back within it (which the flow says nothing more of), is so bound.
     */
    fun bindAgain(): Act.Bind? = if (bound == null) current?.let { Act.Bind(it) } else null

    /** Where the server listens, once bound. */
    val listeningAt: InetAddress? get() = bound

    /**
     * Listening again at the bound address failed, the address the phone's now ([present]) or not. With it, listening
     * again is tried once more, for the address may have come back between the failure and the look; failing again with
     * it, the port can't be had, and the stream is lost. Without it, the socket went with the address before the flow
     * said so, and the server listens again as it comes back ([address], [looked]).
     */
    fun relistenFailed(present: Boolean): Act? {
        if (present) {
            if (retried) return Act.Lost.also { retried = false }
            retried = true
            return Act.Relisten
        }
        retried = false
        lost = true
        return null
    }

    /** Listening again worked: a later loss has its own try once more. */
    fun relistened() {
        retried = false
    }

    /** Whether the server waits for the bound address to come back, to listen there again. */
    val waiting: Boolean get() = lost

    /** The bound address looked for while waiting, the phone's again ([present]) or not: back, the server listens there again. */
    fun looked(present: Boolean): Act? = if (lost && present) Act.Relisten.also { lost = false } else null
}

/**
 * The service's partial wake lock (L59): held while the server writes, let go [letGoMillis] after its last write, so a
 * paused receiver holding its connection open costs none. Taken with a timeout ([timeoutMillis]), and taken again at half
 * of it while the writes go on, so no missed path holds it unbounded and a long write never outlives it. Each [tick]
 * reads how many bytes were sent so far; what to do with the lock, or null for nothing. Not thread-safe: the service's
 * main thread's.
 */
class CastWakeLock(private val letGoMillis: Long, private val timeoutMillis: Long) {
    enum class Act { Acquire, Release }

    private var sent = 0L
    private var lastWrite: Long? = null
    private var armedAt: Long? = null

    /** [sent] bytes written so far, at [now] (elapsedRealtime). */
    fun tick(now: Long, sent: Long): Act? {
        if (sent > this.sent) {
            this.sent = sent
            lastWrite = now
        }
        return decide(now)
    }

    /** A body's write began at [now]: the lock before its first bytes, which may wait on the providers. */
    fun began(now: Long): Act? {
        lastWrite = now
        return decide(now)
    }

    private fun decide(now: Long): Act? {
        val writing = lastWrite?.let { now - it < letGoMillis } == true
        val armed = armedAt
        return when {
            writing && (armed == null || now - armed >= timeoutMillis / 2) -> Act.Acquire.also { armedAt = now }
            !writing && armed != null -> Act.Release.also { armedAt = null }
            else -> null
        }
    }
}

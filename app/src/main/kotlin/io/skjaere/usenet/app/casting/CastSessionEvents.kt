package io.skjaere.usenet.app.casting

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What Google's Cast software says of this phone's session with the chosen device and of the media the device has (L60),
 * as the cast package hands it on from Google's listeners: the session's SessionManagerListener, and the
 * RemoteMediaClient.Callback of the media. The rules read these, never Google's types, so a JVM test and a device test
 * tell their own.
 */
sealed interface CastEvent {
    /** The session with the device started. */
    data object Started : CastEvent

    /** The session is suspended: the phone's Wi-Fi gone a moment (L91). It may come back. */
    data object Suspended : CastEvent

    data object Resumed : CastEvent

    /** The session ended: Stop casting, the device's own timers (L60), a reconnection given up, another app's take-over. */
    data object Ended : CastEvent

    /**
     * A media status from the device: its player's [state], why it is idle ([idle]; null while it isn't), the media it has
     * ([contentId]: the id of a load of this phone's, another sender's, or null for none), where the receiver says it is
     * ([positionMs], never an extrapolation), when the status came ([at], elapsedRealtime), the picture it shows ([video],
     * L131; null while it shows none, or doesn't say), and the media's [title] and [subtitle] as its sender gave them (L154:
     * the list's block names another sender's film by them). It prints without the id or the words.
     */
    data class Status(
        val state: CastPlayerState,
        val idle: CastIdleReason?,
        val contentId: String?,
        val positionMs: Long,
        val at: Long,
        val video: CastVideo? = null,
        val title: String? = null,
        val subtitle: String? = null,
    ) : CastEvent {
        override fun toString() = "Status($state, $idle)"
    }

    /** The device's player failed, by its detailed code ([code]; null when it gave none). */
    data class MediaError(val code: Int?) : CastEvent
}

/**
 * The picture a Cast device shows, as its media status says (MediaStatus' videoInfo, L131): its [width] and [height], and
 * how it shows it ([hdr]). The receiver says it only once it decodes a picture, for what a device teaches (L128).
 */
data class CastVideo(val width: Int, val height: Int, val hdr: CastHdr)

/** How a Cast device shows its picture (VideoInfo's HDR types): SDR, HDR10, another HDR, Dolby Vision, or not said. */
enum class CastHdr { Sdr, Hdr10, Hdr, Dv, Unknown }

/** The device's player, as its media status says (MediaStatus' player states). */
enum class CastPlayerState { Idle, Loading, Buffering, Playing, Paused, Unknown }

/**
 * What the Cast device has now, by its last media status in this phone's session (L154): its player's [state], whether
 * it is one of this phone's loads ([ours]), and for another sender's its [title] and [line] as that sender gave them,
 * which the list's block alone shows. It prints without them.
 */
data class CastDeviceMedia(val state: CastPlayerState, val ours: Boolean, val title: String? = null, val line: String? = null) {
    /** Something there, playing, paused, buffering or loading: not idle. */
    val playing: Boolean get() = state != CastPlayerState.Idle

    override fun toString() = "CastDeviceMedia($state, ${if (ours) "ours" else "another's"})"
}

/** Why the device's player is idle (MediaStatus' idle reasons). */
enum class CastIdleReason {
    None,

    /** It played the media to the end. */
    Finished,

    /** A sender stopped it. */
    Cancelled,

    /** Another load took its place: another sender's, or this phone's next. */
    Interrupted,

    Error,
}

/** Where [CastEvent]s come from, in a seam (L60, L73): Google's listeners in the cast package, or a test's own. */
fun interface CastSessionEvents {
    /** Tells [heard] each event from now, on the main thread, until closed. */
    fun listen(heard: (CastEvent) -> Unit): AutoCloseable
}

/** [CastSessionEvents] told to whoever listens now, as the cast package hears Google's: on the main thread. */
class CastEventHub : CastSessionEvents {
    private val listeners = CopyOnWriteArrayList<(CastEvent) -> Unit>()

    override fun listen(heard: (CastEvent) -> Unit): AutoCloseable {
        listeners += heard
        return AutoCloseable { listeners -= heard }
    }

    fun tell(event: CastEvent) = listeners.forEach { it(event) }
}

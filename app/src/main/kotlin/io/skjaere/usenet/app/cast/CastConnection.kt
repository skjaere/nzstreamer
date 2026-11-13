@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.media3.cast.Cast
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.VideoInfo
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import io.skjaere.usenet.app.casting.CastDeviceMedia
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastHdr
import io.skjaere.usenet.app.casting.CastIdleReason
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastSessionEvents
import io.skjaere.usenet.app.casting.CastVideo
import io.skjaere.usenet.app.casting.CastVolume
import io.skjaere.usenet.app.casting.CastVolumeNow
import io.skjaere.usenet.app.casting.castConnectedLine
import io.skjaere.usenet.app.casting.castTimings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.math.roundToInt

/**
 * Google's Cast sessions, in a seam (L73): connects to a route, ends the session or leaves it, says what comes of each and
 * of the media the device has, reaches the session's media channel, and reads the device's volume as the session has it,
 * on the main thread. Google's on a phone; a device test's own fake, with no Google call.
 */
interface CastConnector {
    /** What becomes of the sessions, each naming its device by [CastRoute.deviceId] (null where it says none). */
    interface Events {
        fun started(device: String?)

        fun startFailed(device: String?)

        fun suspended(device: String?)

        fun resumed(device: String?)

        fun ended(device: String?)

        /** The device's media, in the session with [device]: a status ([CastEvent.Status]) or an error ([CastEvent.MediaError]) (L60). */
        fun media(device: String?, event: CastEvent)
    }

    /** Connects to [route] (a busy device too: it is taken over, W32); false if there is no such route to select now. */
    fun select(route: CastRoute): Boolean

    /** Ends the session, the device's player stopped (L85); one still connecting is given up. */
    fun end()

    /** Leaves the session, the device playing on for the sender whose film it is (Disconnect, L119). */
    fun disconnect()

    /** Tells [events] of the sessions until closed. */
    fun listen(events: Events): AutoCloseable

    /** The session's media channel now, for a cast play's load (L55, L68, L103); null while the session has none (#889). */
    fun media(): CastMedia?

    /**
     * The device's volume as the session itself has it now (L118), on Google's scale of 0 to 20 as Media3's Cast player
     * reads it, never that player's own copy, which keeps a level it sent until the device says otherwise; null with no
     * connected session.
     */
    fun volume(): CastVolumeNow?
}

/** The session's media channel, as a cast play's load uses it: Google's RemoteMediaClient, or a test's. On the main thread. */
interface CastMedia {
    /** Whether the device has a media status: another sender's on a device taken over (W32), or none (L68). */
    val hasStatus: Boolean

    /** The device's address as it was found, which the server lets in first (L55); null where it says none. */
    val address: InetAddress?

    /** Asks the device for its media status now (L103): it sends one by itself only now and then. */
    fun requestStatus()
}

/**
 * Google's own (L48, L60, L85): MediaRouter selects the route, which Google's software makes a session of; every end goes
 * through `Cast.endCurrentSession(true)`, and a route still selected (a session still starting) is let go as the stock
 * dialog's Stop casting does; Disconnect, `endCurrentSession(false)` and the route let go as disconnected, as the stock
 * dialog's Disconnect does, which leaves the device's player alone (L119). Media3's options resume no session, so each
 * starts from a tap in this process. The device's media is heard through a RemoteMediaClient.Callback of the app's own on
 * each session's client, as it starts and as it resumes: Media3's player says no error and no end (L60). A device taken
 * over already has another sender's media (W32), whose status is told as the session starts.
 */
internal class GoogleConnector(context: Context) : CastConnector {
    private val app = context.applicationContext
    private val router: MediaRouter get() = MediaRouter.getInstance(app)

    override fun select(route: CastRoute): Boolean {
        val info = router.routes.firstOrNull { it.id == route.id } ?: return false
        info.select()
        return true
    }

    override fun end() {
        Cast.getSingletonInstance(app).endCurrentSession(true)
        if (!router.selectedRoute.isDefault) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
    }

    override fun disconnect() {
        Cast.getSingletonInstance(app).endCurrentSession(false)
        if (!router.selectedRoute.isDefault) router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
    }

    override fun listen(events: CastConnector.Events): AutoCloseable {
        val media = MediaHeard(events)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = Unit

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                events.started(session.device)
                media.follow(session)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) = events.startFailed(session.device)

            override fun onSessionEnding(session: CastSession) = Unit

            override fun onSessionEnded(session: CastSession, error: Int) {
                media.follow(null)
                events.ended(session.device)
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                events.resumed(session.device)
                media.follow(session)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                media.follow(null)
                events.ended(session.device)
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) = events.suspended(session.device)
        }
        // Queued by Media3 until the Cast context has loaded.
        val cast = Cast.getSingletonInstance(app)
        cast.addSessionManagerListener(listener)
        return AutoCloseable {
            media.follow(null)
            cast.removeSessionManagerListener(listener)
        }
    }

    override fun volume(): CastVolumeNow? {
        val session = Cast.getSingletonInstance(app).currentCastSession?.takeIf { it.isConnected } ?: return null
        // Rounded as Media3's player rounds it, so the two agree on a level the device kept.
        return CastVolumeNow((session.volume * CastVolume.MAX).roundToInt().coerceIn(0, CastVolume.MAX), session.isMute)
    }

    override fun media(): CastMedia? {
        val session = Cast.getSingletonInstance(app).currentCastSession ?: return null
        val client = session.remoteMediaClient ?: return null
        return object : CastMedia {
            override val hasStatus: Boolean get() = client.mediaStatus != null

            override val address: InetAddress? get() = session.castDevice?.inetAddress

            override fun requestStatus() {
                // Its answer comes as a status, through the callback.
                client.requestStatus()
            }
        }
    }

    /**
     * The device's media in the session followed, told to [events] (L60), with the picture it shows (L131) and the title
     * and subtitle its sender gave it (L154).
     */
    private class MediaHeard(private val events: CastConnector.Events) : RemoteMediaClient.Callback() {
        private var client: RemoteMediaClient? = null
        private var device: String? = null

        /** Hears [session]'s client from now, the one before let go; none for null. */
        fun follow(session: CastSession?) {
            val next = session?.remoteMediaClient
            if (next === client) return
            client?.unregisterCallback(this)
            client = next
            device = session?.device
            next ?: return
            next.registerCallback(this)
            // Another sender's media already there (W32), or ours as the session comes back.
            if (next.mediaStatus != null) onStatusUpdated()
        }

        override fun onStatusUpdated() {
            val status = client?.mediaStatus
            val state = stateOf(status?.playerState)
            val said = status?.mediaInfo?.metadata
            events.media(
                device,
                CastEvent.Status(
                    state, if (state == CastPlayerState.Idle) idleOf(status?.idleReason) else null, status?.mediaInfo?.contentId,
                    status?.streamPosition ?: 0, SystemClock.elapsedRealtime(), status?.videoInfo?.let { CastVideo(it.width, it.height, hdrOf(it.hdrType)) },
                    said?.getString(MediaMetadata.KEY_TITLE), said?.getString(MediaMetadata.KEY_SUBTITLE),
                ),
            )
        }

        override fun onMediaError(error: MediaError) = events.media(device, CastEvent.MediaError(error.detailedErrorCode))

        private fun stateOf(state: Int?): CastPlayerState = when (state) {
            MediaStatus.PLAYER_STATE_IDLE -> CastPlayerState.Idle
            MediaStatus.PLAYER_STATE_LOADING -> CastPlayerState.Loading
            MediaStatus.PLAYER_STATE_BUFFERING -> CastPlayerState.Buffering
            MediaStatus.PLAYER_STATE_PLAYING -> CastPlayerState.Playing
            MediaStatus.PLAYER_STATE_PAUSED -> CastPlayerState.Paused
            // No media at all: what vanished media looks like (L102).
            null -> CastPlayerState.Idle
            else -> CastPlayerState.Unknown
        }

        /** The picture's HDR type as the receiver says it (VideoInfo's); one this version doesn't know is not said. */
        private fun hdrOf(type: Int): CastHdr = when (type) {
            VideoInfo.HDR_TYPE_SDR -> CastHdr.Sdr
            VideoInfo.HDR_TYPE_HDR10 -> CastHdr.Hdr10
            VideoInfo.HDR_TYPE_HDR -> CastHdr.Hdr
            VideoInfo.HDR_TYPE_DV -> CastHdr.Dv
            else -> CastHdr.Unknown
        }

        private fun idleOf(reason: Int?): CastIdleReason? = when (reason) {
            MediaStatus.IDLE_REASON_NONE -> CastIdleReason.None
            MediaStatus.IDLE_REASON_FINISHED -> CastIdleReason.Finished
            MediaStatus.IDLE_REASON_CANCELED -> CastIdleReason.Cancelled
            MediaStatus.IDLE_REASON_INTERRUPTED -> CastIdleReason.Interrupted
            MediaStatus.IDLE_REASON_ERROR -> CastIdleReason.Error
            else -> null
        }
    }
}

private val CastSession.device: String? get() = castDevice?.deviceId

/**
 * This phone's Cast session (L48, L77, L78), for the process's life: what Play on chooses by ([now]: connecting from a
 * tap until the session starts, connected, reconnecting while it is suspended; null with none), and which device's last
 * connect failed ([failed]: refused, or not connected in [castTimings]' 15 s), whose row says so. Its connector is
 * attached as the gate first lets the sheet's group look for devices; until then nothing of Google's is called, and [now]
 * is null. A session that ended, or another device's, is never taken for the one chosen. [now] carries what the tapped
 * route said of the device (L153), and as each session starts the log has one line of its model, kind and volume, never
 * its name or address. On the main thread, but [stop].
 *
 * It holds the session's player too ([player], L48): built through [CastPlayers] once in the process, as the first
 * session starts, and never released, so it follows each session after; a cast play's service wraps it while it runs.
 * What the chosen device's session does, and the device's media in it, is told to [events]; what the device has now is
 * kept for the list's block ([media], L154: one of this phone's loads, by [ours], or another sender's, by its words),
 * and so is the session's volume ([volume], L118), made anew as each session starts.
 */
internal class CastConnection(
    private val scope: CoroutineScope,
    private val ours: (String) -> Boolean = { id -> id == Casting.feed.value?.load || CastItems.made(id) },
) {
    private val log = LoggerFactory.getLogger(CastConnection::class.java)
    private val _now = MutableStateFlow<CastLinkNow?>(null)
    val now: StateFlow<CastLinkNow?> = _now.asStateFlow()

    private val _failed = MutableStateFlow<String?>(null)

    /** The route whose last connect failed; null once another is tried. */
    val failed: StateFlow<String?> = _failed.asStateFlow()

    private val _media = MutableStateFlow<CastDeviceMedia?>(null)

    /** What the device in [now]'s session has, by its last media status there (L154); null before one, and with no session. */
    val media: StateFlow<CastDeviceMedia?> = _media.asStateFlow()

    private val _volume = MutableStateFlow<CastVolume?>(null)

    /**
     * The device's volume in [now]'s session (L118): one for each session, made as it starts over the session's player
     * and its own level ([CastConnector.volume]), kept while it reconnects; null with none.
     */
    val volume: StateFlow<CastVolume?> = _volume.asStateFlow()

    /** The device [now]'s session is with, as its route named it. */
    private var device: String? = null
    private var connector: CastConnector? = null
    private var listening: AutoCloseable? = null
    private var waiting: Job? = null
    private var app: Context? = null
    private var built: Player? = null
    private val hub = CastEventHub()

    /** The device a tap on a sheet's row connects ([connect]), until a sheet has closed for it, or it is given up. */
    private var tapped: String? = null

    /** How many of the sheet's Cast groups show now ([showing]). */
    private var shown = 0

    /** What the chosen device's session does (started, suspended, resumed, ended) and its media's statuses and errors (L60). */
    val events: CastSessionEvents get() = hub

    /** Listens to [connector]'s sessions from now; the one before, a test's, is let go. */
    @MainThread
    fun attach(connector: CastConnector, context: Context) {
        app = context.applicationContext
        if (this.connector === connector) return
        listening?.close()
        this.connector = connector
        listening = connector.listen(sessions)
    }

    /** The session's media channel now, for a cast play's load (L55, L68, L103); none without a session or a connector. */
    @MainThread
    fun media(): CastMedia? = connector?.media()

    /** The session's player (L48): built once in the process, at the first session's start or now, never released. */
    @MainThread
    fun player(context: Context): Player = built ?: CastPlayers.make(context.applicationContext).also { built = it }

    /** The session's player once built ([player]), for the bar's and the remote's buttons (L91); null before. */
    val made: Player? get() = built

    /**
     * A tap on [route]'s row: connecting at once, then connected, or failed after [castTimings]' wait or a refusal. Not
     * [fromList] (the player's Play & connect, whose list takes the tap, L151), no list closes for it once connected.
     */
    @MainThread
    fun connect(route: CastRoute, fromList: Boolean = true) {
        val connector = connector ?: return
        waiting?.cancel()
        _failed.value = null
        tapped = route.id.takeIf { fromList }
        device = route.deviceId
        session(null)
        _now.value = CastLinkNow(route.id, route.name, CastLink.Connecting, route.facts)
        waiting = scope.launch {
            delay(castTimings().connectMillis)
            if (_now.value?.takeIf { it.route == route.id }?.link == CastLink.Connecting) {
                fail(route.id)
                connector.end()
            }
        }
        if (!connector.select(route)) fail(route.id)
    }

    /**
     * A sheet's Cast group shows from now until closed: one that shows as the device a tap connects is connected closes
     * then ([tappedConnected]); with none showing, nothing is left to close.
     */
    @MainThread
    fun showing(): AutoCloseable {
        shown++
        return AutoCloseable { shown-- }
    }

    /**
     * Whether [route] is now connected from a sheet's tap that no sheet has closed for yet: true once, for the group that
     * sees it, the one tapped or one made again since (a rotation, the sheet closed and opened while it connected).
     */
    @MainThread
    fun tappedConnected(route: String): Boolean {
        val now = _now.value ?: return false
        if (tapped != route || now.route != route || now.link != CastLink.Connected) return false
        tapped = null
        return true
    }

    /** Stop casting (L85): the session ends, the device's player stopped; the session's end comes after, unheeded. */
    fun stop() {
        scope.launch {
            gone()
            connector?.end()
        }
    }

    /**
     * Disconnect (L119): this phone leaves the session, the device playing on for the sender whose film it is; the
     * session's end comes after, unheeded. Any thread.
     */
    fun disconnect() {
        scope.launch {
            gone()
            connector?.disconnect()
        }
    }

    private fun fail(route: String) {
        gone()
        _failed.value = route
    }

    /** No session from now: none waited for, none tapped, nothing of a device's. */
    private fun gone() {
        waiting?.cancel()
        tapped = null
        device = null
        _now.value = null
        session(null)
    }

    /** A session's own: what its device has, unheard yet, and [volume] (null between sessions). */
    private fun session(volume: CastVolume?) {
        _media.value = null
        _volume.value = volume
    }

    /** Whether [device]'s session is the one [now] says: a device its route named no id for takes any. */
    private fun mine(device: String?) = _now.value != null && (this.device == null || device == null || device == this.device)

    private val sessions = object : CastConnector.Events {
        override fun started(device: String?) {
            if (!mine(device)) return
            waiting?.cancel()
            // Connected with no sheet showing: nothing to close, and none opened later closes for it.
            if (shown == 0) tapped = null
            _now.update { it?.copy(link = CastLink.Connected) }
            _now.value?.device?.let { log.info(castConnectedLine(it.model, it.kind, it.fixedVolume)) }
            // Before anything plays: it takes the session as it is built, and follows each one after. The session's volume
            // with it, a refusal remembered for this session alone.
            app?.let { context -> session(CastVolume(player(context)) { connector?.volume() }) }
            hub.tell(CastEvent.Started)
        }

        override fun startFailed(device: String?) {
            val now = _now.value ?: return
            if (mine(device) && now.link == CastLink.Connecting) fail(now.route)
        }

        override fun suspended(device: String?) {
            if (!mine(device)) return
            _now.update { now -> now?.takeIf { it.link == CastLink.Connected }?.copy(link = CastLink.Reconnecting) ?: now }
            hub.tell(CastEvent.Suspended)
        }

        override fun resumed(device: String?) {
            if (!mine(device)) return
            _now.update { it?.copy(link = CastLink.Connected) }
            hub.tell(CastEvent.Resumed)
        }

        override fun ended(device: String?) {
            if (!mine(device)) return
            gone()
            hub.tell(CastEvent.Ended)
        }

        override fun media(device: String?, event: CastEvent) {
            if (!mine(device)) return
            if (event is CastEvent.Status) _media.value = heardOf(event)
            hub.tell(event)
        }
    }

    /** What the device has by [status]: one of this phone's loads, or another sender's with the words it gave. */
    private fun heardOf(status: CastEvent.Status): CastDeviceMedia {
        val mine = status.contentId?.let(ours) == true
        return if (mine) CastDeviceMedia(status.state, ours = true) else CastDeviceMedia(status.state, ours = false, status.title, status.subtitle)
    }

    /** A test's own process: no session, no connector and no player (a test's fake), as before the gate first passed. */
    @VisibleForTesting
    @MainThread
    fun forget() {
        waiting?.cancel()
        listening?.close()
        listening = null
        connector = null
        device = null
        tapped = null
        built = null
        _now.value = null
        _failed.value = null
        session(null)
    }
}

@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.casting.CastAddressWatch
import io.skjaere.usenet.app.casting.CastServiceRules
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.casting.CastWakeLock
import io.skjaere.usenet.app.casting.ForegroundOwed
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.StartingPlayer
import io.skjaere.usenet.app.casting.castTimings
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.ControlsPlayer
import io.skjaere.usenet.app.play.SessionPlayer
import io.skjaere.usenet.serve.ServeEvent
import io.skjaere.usenet.serve.ServeMethod
import io.skjaere.usenet.serve.ServeServer
import io.skjaere.usenet.serve.Served
import io.skjaere.usenet.serve.ServedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * Cast's service (W27, L58, L59, L63, L85, L86, L87): while a cast play runs, from its tap until it ends, the phone serves
 * its file to the Cast device and is its remote. A media-playback foreground service with a media session of its own,
 * "cast", beside the player's, over the device's player ([GoogleCast]'s, never released here): its notification and lock
 * screen say what plays and offer play or pause and Stop casting, a tap on it opens the remote, and the volume keys go to
 * the device. Until the device has the play, a placeholder stands in for it ([StartingPlayer]), so Media3 posts the
 * notification and takes the service into the foreground at once, however long the phone's search takes; from then on the
 * foreground is held for the service's life, a long pause past Media3's own 10 minutes included (K1), until the receiver
 * itself closes the session 20 minutes into a pause.
 *
 * It serves on the phone's private address ([ServeServer], L74), listening there again when the listening socket goes
 * with a Wi-Fi blip, and stopping the cast if the address changes; a partial wake lock while it writes and a Wi-Fi lock for
 * its life (L59). What ends a play and what else that does is [CastPlays]' and [CastServiceRules]'; a swipe-away stops the
 * cast only while it is paused.
 *
 * With no cast play in the process (a restart after the process died, another app's controller or start, a media button)
 * it builds nothing (no player, server or Cast call), gives no controller a session, and stops, through the foreground
 * where a start may have wanted it; on a TV, always. It never restarts
 * by itself: a cast never outlives its process (L64).
 */
class CastService : MediaSessionService() {
    private val log = LoggerFactory.getLogger(CastService::class.java)

    /** What runs the cast plays here: built for the play this process started, never otherwise (L85). */
    private var run: Run? = null

    /** Whether a start may still want the foreground: Android takes the app down if the service stops before it gets there. */
    private val owed = ForegroundOwed()

    override fun onCreate() {
        super.onCreate()
        owed.created()
        val notifications = CastNotifications(this)
        setMediaNotificationProvider(notifications)
        setForegroundServiceTimeoutMs(castTimings().foregroundTimeoutMillis)
        setListener(
            object : MediaSessionService.Listener {
                @RequiresApi(Build.VERSION_CODES.S)
                override fun onForegroundServiceStartNotAllowedException() {
                    // Media3 couldn't take the service back into the foreground from the background; it says nothing else of it.
                    log.info("Android refused the cast service the foreground from the background")
                }
            },
        )
        build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Any start may be one for the foreground: the app's own for a cast play, or another app's, any action or none.
        owed.started(inForeground())
        if (build() == null) stopNow()
        return START_NOT_STICKY
    }

    /**
     * The run for the play this process started, built now if there is one and none yet; null with none, or on a TV, or
     * where the play ended as its run was built: the session it was asked for had gone, or is another device's.
     */
    private fun build(): Run? {
        run?.let { return it }
        val play = CastPlays.now
        if (!CastServiceRules.builds(application.container.television, playing = play != null) || play == null) return null
        val built = Run(play)
        // Its watches hear the session as it is at once: gone already, the play is over before the service is the plays'.
        if (play.ended) {
            built.close()
            return null
        }
        run = built
        CastPlays.up(this)
        return built
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = run?.session

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> =
        super.onUpdateNotificationAsync(session, CastServiceRules.foreground(startInForegroundRequired, serving = run != null)).also { update ->
            // Media3 takes the service into the foreground with its notification: the starts before it are answered.
            update.addListener({ owed.seen(inForeground()) }, ContextCompat.getMainExecutor(this))
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val run = run ?: return super.onTaskRemoved(rootIntent)
        if (CastServiceRules.stopsOnTaskRemoved(run.starting.starting, run.starting.playWhenReady)) CastPlays.stopCasting(Why.TaskRemovedPaused)
    }

    override fun onDestroy() {
        // Gone with plays still running (Android stopped it): they end first, what they loaded stopped on the device.
        CastPlays.down(this)
        run?.close()
        run = null
        super.onDestroy()
    }

    /** The device's player, while a play runs here. */
    internal val remote: Player? get() = run?.remote

    /** The server, once it listens, for a device test to take its listening socket away as Android does (L74). */
    @VisibleForTesting
    internal val server: ServeServer? get() = run?.server?.value

    /** The session's player over the device's, for a device test to read where it keeps the play (L58). */
    @VisibleForTesting
    internal val sessionPlayer: StartingPlayer? get() = run?.starting

    /** A cast play starts while another runs here (L84): the session says it until the device has it. */
    @MainThread
    internal fun starting(play: CastPlay) {
        run?.starting?.starting(play.said, play.cast.name)
    }

    /** The play starting was given up, and [play], before it, still plays there: the session follows the device. */
    @MainThread
    internal fun show(play: CastPlay) {
        if (play.sent != null) run?.starting?.gaveUp(play.sent)
    }

    /** The play starting sent the device its item, as [mediaId]. */
    @MainThread
    internal fun sent(mediaId: String) {
        run?.starting?.sent(mediaId)
    }

    /** What [play]'s load is handed: this service's server and the device's player. */
    @MainThread
    internal fun serving(play: CastPlay): CastServing = run?.serving(play) ?: throw IllegalStateException("the cast service runs no play")

    /** Every play here ended: the session goes, Media3 takes the notification down, and the service stops. */
    @MainThread
    internal fun stopForPlays() {
        run?.close()
        run = null
        stopNow()
    }

    private fun stopNow() {
        if (owed.stopping(inForeground())) passThroughForeground()
        stopSelf()
    }

    /** Whether Android has the service in the foreground now. */
    private fun inForeground(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        } else {
            // Before Android 10 a service can't ask; Android still lists an app's own services to it.
            @Suppress("DEPRECATION")
            getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .any { it.foreground && it.pid == Process.myPid() && it.service.className == CastService::class.java.name }
        }

    /**
     * A start that may want the foreground, with nothing to show: the play ended before the notification came, or there
     * was none (another app's start). Android ends an app whose service it started for the foreground stops before it
     * gets there, so it gets there once, unseen, as Media3's own does.
     */
    // The type is Android 10's: below it ServiceCompat leaves it out, as the manifest has it.
    @SuppressLint("InlinedApi")
    private fun passThroughForeground() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID))
                .build(),
        )
        val notification = NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(false)
            .build()
        try {
            ServiceCompat.startForeground(this, PASSING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: IllegalStateException) {
            // Refused: past Android's window already, which has said so itself.
            log.info("The cast service couldn't pass through the foreground ({})", e.javaClass.simpleName)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * What runs the cast plays here, from the first one's start: the session over the device's player, the server, the
     * locks, and the watches of the address, the session and the device.
     */
    private inner class Run(first: CastPlay) {
        val remote: Player = GoogleCast.connection.player(this@CastService)
        val starting = StartingPlayer(remote)
        private val outside = SessionPlayer(ControlsPlayer(starting), leave = { CastPlays.stopCasting() })
        private val artwork = CastArtwork()
        val session: MediaSession = MediaSession.Builder(this@CastService, outside)
            .setId(SESSION_ID)
            .setCallback(Callback(outside))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this@CastService, 0, MainActivity.openRemote(this@CastService), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setBitmapLoader(artwork)
            .setMediaButtonPreferences(listOf(STOP_BUTTON))
            .build()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** The server, once it listens at the phone's address. */
        val server = MutableStateFlow<ServeServer?>(null)

        /** Every server made here, bound or binding: closed with the run. */
        private val servers = CopyOnWriteArrayList<ServeServer>()

        /** The bind running, or waiting to be tried again. */
        private var binding: Job? = null

        /** Whether a bind failed in this run: said once. */
        private var bindFailed = false

        private var looking: Job? = null

        /** Every file served here: what the wake lock counts the writes by. */
        private val served = CopyOnWriteArrayList<Served>()
        private val watch = CastAddressWatch()
        private val writes = castTimings().let { CastWakeLock(it.wakeLetGoMillis, it.wakeTimeoutMillis) }
        private val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }

        // Helps the Wi-Fi keep up with the screen off up to Android 13; harmless later (L59).
        @Suppress("DEPRECATION")
        private val wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        private val events: AutoCloseable

        init {
            starting.starting(first.said, first.cast.name)
            addSession(session)
            scope.launch { CastSeams.addresses(this@CastService).collect(::address) }
            scope.launch { GoogleCast.connection.now.collect(CastPlays::linked) }
            events = CastSeams.events(this@CastService).listen(CastPlays::heard)
            scope.launch {
                while (true) {
                    wake(writes.tick(SystemClock.elapsedRealtime(), served.sumOf { it.counters().bytesSent }))
                    // The device's player says nothing of its steady progress: what the session keeps of the play follows the feed.
                    starting.followed(Casting.feed.value)
                    delay(TICK_MS)
                }
            }
        }

        fun serving(play: CastPlay): CastServing = object : CastServing {
            override val remote: Player get() = this@Run.remote

            override suspend fun serve(file: ServedFile, expected: InetAddress?): Served? {
                val listening = withTimeoutOrNull(castTimings().noRequestMillis) { server.filterNotNull().first() } ?: return null
                val serving = listening.serve(file, expected)
                served += serving
                play.served += serving
                // Ended meanwhile: never served for it.
                if (play.ended) serving.revoke()
                return serving
            }

            override fun artwork(bytes: ByteArray) = artwork.prepare(bytes)

            override fun sent(mediaId: String) = CastPlays.sent(play, mediaId)

            override val over: Deferred<Why?> get() = play.over

            override fun end(why: Why) = CastPlays.end(play, why)
        }

        /** The phone's address is now [now] (L74). */
        private fun address(now: InetAddress?) {
            when (val act = watch.address(now)) {
                is CastAddressWatch.Act.Bind -> bind(act.address)
                CastAddressWatch.Act.Relisten -> relisten()
                CastAddressWatch.Act.Stop -> CastPlays.addressChanged()
                CastAddressWatch.Act.Lost, null -> Unit
            }
        }

        /**
         * Binds the server to [address], unless a bind runs, or waits to be tried again: the flow's addresses meanwhile
         * are left to it, since it binds the address there is then.
         */
        private fun bind(address: InetAddress) {
            if (binding?.isActive == true) return
            val created = ServeServer(address as Inet4Address, onEvent = ::heard)
            // Closed with the run, even while it binds.
            servers += created
            binding = scope.launch {
                val failed = withContext(Dispatchers.IO) {
                    try {
                        created.start()
                        null
                    } catch (e: IOException) {
                        // The address went as it came, or isn't the phone's: tried again below.
                        e
                    } catch (e: IllegalStateException) {
                        // Closed with the run as it bound.
                        e
                    }
                }
                if (failed != null) {
                    // Said once a run, not each second.
                    if (failed is IOException && !bindFailed) {
                        bindFailed = true
                        log.info("Cast's server couldn't bind the phone's address ({})", failed.javaClass.simpleName)
                    }
                    servers -= created
                    created.close()
                    // The flow says only changes, and an address it said as this bind ran or waited wasn't bound: the
                    // address there is then is tried a second on, and so each second while nothing binds.
                    delay(BIND_AGAIN_MS)
                    // This job is the guard: done with, so the bind below runs.
                    binding = null
                    watch.bindAgain()?.let { bind(it.address) }
                    return@launch
                }
                watch.bound(address)
                server.value = created
            }
        }

        private fun relisten() {
            val listening = server.value ?: return
            val at = watch.listeningAt ?: return
            scope.launch {
                // Listening again (true), not (false), or the server closed with the run as it got here (null).
                val again = withContext(Dispatchers.IO) {
                    try {
                        listening.relisten()
                        true
                    } catch (e: IOException) {
                        log.info("Cast's server couldn't listen again at the phone's address ({})", e.javaClass.simpleName)
                        false
                    } catch (e: IllegalStateException) {
                        null
                    }
                }
                when (again) {
                    true -> {
                        watch.relistened()
                        return@launch
                    }
                    // The run's scope is cancelled with it: nothing more of the run goes on.
                    null -> return@launch
                    false -> Unit
                }
                // The address may have gone with the socket, the flow not told yet: no lost stream until it is back.
                val present = withContext(Dispatchers.IO) { CastSeams.present(at) }
                when (watch.relistenFailed(present)) {
                    CastAddressWatch.Act.Lost -> CastPlays.lostStream()
                    // It may have come back just as the relisten failed: once more.
                    CastAddressWatch.Act.Relisten -> relisten()
                    else -> lookFor(at)
                }
            }
        }

        /** The listening socket went with [at], before the flow said so: looked for each second, and listened at again once back. */
        private fun lookFor(at: InetAddress) {
            if (looking?.isActive == true) return
            looking = scope.launch {
                while (watch.waiting) {
                    delay(LOOK_MS)
                    val present = withContext(Dispatchers.IO) { CastSeams.present(at) }
                    if (watch.looked(present) == CastAddressWatch.Act.Relisten) relisten()
                }
            }
        }

        /** What the server says, on its own threads: taken on the main thread. */
        private fun heard(event: ServeEvent) {
            scope.launch {
                when (event) {
                    ServeEvent.ListenerLost -> if (watch.listenerLost() == CastAddressWatch.Act.Relisten) relisten()
                    // A body's write begins: the lock before its first bytes, which may wait on the providers.
                    is ServeEvent.Answered -> if (event.method == ServeMethod.Get && (event.status == 200 || event.status == 206)) {
                        wake(writes.began(SystemClock.elapsedRealtime()))
                    }
                    else -> Unit
                }
            }
        }

        private fun wake(act: CastWakeLock.Act?) {
            when (act) {
                CastWakeLock.Act.Acquire -> wakeLock.acquire(castTimings().wakeTimeoutMillis)
                CastWakeLock.Act.Release -> if (wakeLock.isHeld) wakeLock.release()
                null -> Unit
            }
        }

        fun close() {
            scope.cancel()
            events.close()
            session.release()
            // The server stops listening, and closes every connection (L85).
            servers.forEach { it.close() }
            if (wakeLock.isHeld) wakeLock.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    /**
     * The "cast" session's answers to controllers (L63, W35): trusted ones (the app's own, the system's) get Stop casting
     * besides the usual; the notification's controller only play or pause, a seek and the device's volume, with Stop
     * casting as its own button, and no next, previous or ±10 s. No controller loads anything on the device (Terms 3.4.1).
     * Media buttons go as the player's do ([SessionPlayer.mediaButton]).
     */
    private inner class Callback(outside: SessionPlayer) : MediaSession.Callback {
        private val buttons = outside.callback()

        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.isTrusted) result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(STOP).build())
            if (session.isMediaNotificationController(controller)) result.setAvailablePlayerCommands(NOTIFICATION_COMMANDS).setMediaButtonPreferences(listOf(STOP_BUTTON))
            return Futures.immediateFuture(result.build())
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction != STOP_ACTION) return super.onCustomCommand(session, controller, customCommand, args)
            CastPlays.stopCasting()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFailedFuture(UnsupportedOperationException("nothing is loaded on the device from outside the app"))

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFailedFuture(UnsupportedOperationException("nothing is loaded on the device from outside the app"))

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
            buttons.onMediaButtonEvent(session, controllerInfo, intent)
    }

    companion object {
        /** The app's own start of the service, for a cast play's tap (L85). */
        private const val ACTION_PLAY = "io.skjaere.usenet.cast.PLAY"

        /** The session's id: the player's has the default one, and two sessions may not share one. */
        @VisibleForTesting
        internal const val SESSION_ID = "cast"

        /** The session's own command: Stop casting (L86). */
        @VisibleForTesting
        internal const val STOP_ACTION = "io.skjaere.usenet.cast.STOP"
        private val STOP = SessionCommand(STOP_ACTION, Bundle.EMPTY)

        /** Stop casting, by play or pause, as the notification's × (CsPhoneNotification). */
        private val STOP_BUTTON = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_stop_casting)
            .setDisplayName(STOP_CASTING)
            .setSessionCommand(STOP)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        /** What the notification's controller, and with it the lock screen and the system's media controls, may do (W35). */
        @Suppress("DEPRECATION")
        private val NOTIFICATION_COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()

        /** Passing through the foreground for nothing to show: never the media notification's own id. */
        private const val PASSING_ID = 20_939
        private const val WAKE_TAG = "NZStreamer:cast"
        private const val WIFI_TAG = "NZStreamer:cast"

        /** How often the writes are looked at for the wake lock, and the play as followed for the session. */
        private const val TICK_MS = 1_000L

        /** How often the phone's address is looked for, gone with the listening socket. */
        private const val LOOK_MS = 1_000L

        /** How long after a failed bind the address there is then is tried: each second while none binds. */
        private const val BIND_AGAIN_MS = 1_000L

        /** The app's start of the service for a cast play's tap. */
        internal fun start(context: Context): Intent = Intent(context, CastService::class.java).setAction(ACTION_PLAY)
    }
}

/**
 * The cast notification (L86, W35; CsPhoneNotification): Media3's own, with the app's mark as its small icon, play or
 * pause and the session's Stop casting; never the device's next, previous or ±10 s, whatever its player offers.
 */
private class CastNotifications(context: Context) : DefaultMediaNotificationProvider(context) {
    init {
        setSmallIcon(R.drawable.ic_cast_notification)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: com.google.common.collect.ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): com.google.common.collect.ImmutableList<CommandButton> {
        val shown = playerCommands.buildUpon().removeAll(
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        ).build()
        return super.getMediaButtons(session, shown, mediaButtonPreferences, showPauseButton)
    }
}

/**
 * The cast session's artwork (L58): a still decoded before an item carrying it reaches the session ([prepare]), handed
 * over done. Media3's own decodes on an executor, so its first future for a new still isn't done as the notification is
 * made, and the notification's late callback then works the foreground out by itself: let go, in a pause past Media3's
 * 10 minutes (K1). Anything not prepared has none, and it never loads a URI.
 */
internal class CastArtwork : BitmapLoader {
    /** The last stills prepared, by their bytes. */
    private val decoded = ArrayDeque<Pair<ByteArray, Bitmap>>()

    /**
     * Decodes [bytes], sampled down by powers of two while that leaves [SIDE_PX] or more on its longer side: a large still
     * comes out between 512 and 1023 px there (a 1920-wide one at 960), and Media3's SizeLimitedBitmapLoader, which the
     * session wraps around this one, scales it after. Off the main thread.
     */
    fun prepare(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE_PX) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        synchronized(decoded) {
            decoded.removeAll { it.first.contentEquals(bytes) }
            decoded.addLast(bytes to bitmap)
            while (decoded.size > KEPT) decoded.removeFirst()
        }
    }

    private fun find(bytes: ByteArray): Bitmap? = synchronized(decoded) { decoded.lastOrNull { it.first.contentEquals(bytes) }?.second }

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        find(data)?.let { Futures.immediateFuture(it) } ?: Futures.immediateFailedFuture(IllegalStateException("a still not prepared"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = Futures.immediateFailedFuture(UnsupportedOperationException("no still is loaded from an address"))

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? = metadata.artworkData?.let(::find)?.let { Futures.immediateFuture(it) }

    private companion object {
        /** The longer side a still is sampled down towards and kept at or above: the notification scales its icon from it. */
        const val SIDE_PX = 512

        /** The play's still and the one before it, as a metadata change brings a new one. */
        const val KEPT = 2
    }
}

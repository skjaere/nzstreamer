package io.skjaere.usenet.app.cast

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastLoad
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastServiceRules
import io.skjaere.usenet.app.casting.CastServiceRules.Ending
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.casting.lostTheStream
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.serve.Served
import io.skjaere.usenet.serve.ServedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a cast play's load is handed (L65, L85): the service's server, the device's player, and where the load says what
 * it sent. The service's, while it runs the play.
 */
internal interface CastServing {
    /** The Cast session's player: the device's (L48). On the main thread. */
    val remote: Player

    /**
     * Serves [file] for the play to the device at [expected] (its address as it was found; null when unknown), once the
     * server listens at the phone's address: revoked as the play ends (L55). Null where it doesn't listen there within the
     * device's own wait for a request (W34's 30 s): the phone has no private address on this network, or none it can
     * listen at, so the device can't reach it.
     */
    suspend fun serve(file: ServedFile, expected: InetAddress?): Served?

    /** Decodes [bytes], a still, for the session before an item carrying it goes to the device (L58). Off the main thread. */
    fun artwork(bytes: ByteArray)

    /** The play's item went to the device as [mediaId], its content id: the session follows the device's player once it has it (L63). */
    @MainThread
    fun sent(mediaId: String)

    /** How the play ended, once it has: why, or null where the next play's load took its place there (L84). */
    val over: Deferred<Why?>

    /** Ends the play for [why], as the device says (L60, L103), each end doing what [CastServiceRules] says it does. */
    @MainThread
    fun end(why: Why)
}

/** Loads a cast play's launch on its device over the service (L65, L68): how it came out, the device playing it or not. */
internal fun interface CastLoader {
    suspend fun load(launch: PlayerLaunch, serving: CastServing): CastLoadOutcome
}

/** A cast play (L85): from its tap until it ends. Its fields are the main thread's but [served]. */
internal class CastPlay(val cast: CastTo, val said: SessionMetadata) {
    /** The content id of what it sent the device; null before its load. */
    var sent: String? = null

    /** Its load came out with the device playing it: past starting, its sheet closed. */
    var played = false

    var ended = false

    /** Its files served, revoked as it ends (L55). */
    val served = CopyOnWriteArrayList<Served>()

    /** What the service found against it before its load came out: the stream lost (L88). */
    val failed = CompletableDeferred<CastLoadOutcome.Failed>()

    /** Why it ended, once it has; null where the next play's load took its place there (L84). */
    val over = CompletableDeferred<Why?>()

    override fun toString() = "CastPlay"
}

/**
 * This process's cast plays (L84, L85), and what starts each on its device for the coordinator ([CastLauncher]): a play
 * starts the service at its tap, its load runs over the service once the phone has the release ready, and it ends as the
 * device or the coordinator says, each end doing what [CastServiceRules] says it does. One play at a time: a new one gives
 * up one still starting, its load on the device too, while one the device plays goes on there until the new one's load.
 * The service runs while any does. All on the main thread; the coordinator's [load] and [stop] come from its own and are
 * taken as they come. It holds the application's context alone, and the service only while it runs.
 */
@SuppressLint("StaticFieldLeak")
internal object CastPlays : CastLauncher {
    private val log = LoggerFactory.getLogger(CastPlays::class.java)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    private val _playing = MutableStateFlow(false)

    /** Whether a cast play runs: from its tap until the last one ends. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** The play starting, or the one playing: what the service shows. Read off the main thread as the coordinator hands over. */
    @Volatile
    var now: CastPlay? = null
        private set

    /** The play the device still plays while [now] starts (L84), until [now]'s load. */
    private var before: CastPlay? = null

    /** The service running the plays, once it is built for one; none once it stops. */
    private val service = MutableStateFlow<CastService?>(null)

    /** The service running the plays now, for a device test to look into. */
    @VisibleForTesting
    internal val running: CastService? get() = service.value

    /** This launcher for [context]'s app: the service is started from there. */
    fun with(context: Context): CastPlays = apply { app = context.applicationContext }

    @MainThread
    override fun starting(cast: CastTo, metadata: SessionMetadata): Boolean {
        val play = CastPlay(cast, metadata)
        // One still starting is given up for this one, its load on the device too, before it plays: its play sheet's job is
        // gone, and with it what would follow it there. That leaves the play before it, if any, as the one there.
        now?.takeIf { !it.played }?.let { finish(it, Why.Cancelled) }
        // The one there goes on until this one's load (L84).
        now?.let { there ->
            before?.takeIf { it !== there }?.let(::retire)
            before = there
        }
        now = play
        _playing.value = true
        val running = service.value
        if (running != null) {
            running.starting(play)
            return true
        }
        return try {
            ContextCompat.startForegroundService(app, CastService.start(app))
            true
        } catch (e: IllegalStateException) {
            // From Android 12 on, a ForegroundServiceStartNotAllowedException: the app wasn't let start it (L97).
            log.info("Android refused the cast service's start ({})", e.javaClass.simpleName)
            now = null
            before = null
            _playing.value = false
            false
        }
    }

    override fun load(launch: PlayerLaunch): CastLoad {
        // The play the coordinator hands over, read now, inside its sheet's lock: a play started after it isn't this one.
        val play = now
        return CastLoad {
            withContext(Dispatchers.Main.immediate) {
                if (play == null || play.ended) return@withContext GONE
                val serving = service.filterNotNull().first().serving(play)
                // The load runs in the coordinator's job, which Cancel ends; a failure the service finds first wins.
                val outcome = coroutineScope {
                    val loading = async { CastSeams.loader(app).load(launch, serving) }
                    select {
                        loading.onAwait { it }
                        play.failed.onAwait { loading.cancel(); it }
                    }
                }
                if (outcome is CastLoadOutcome.Playing) play.played = true
                outcome
            }
        }
    }

    /** Once, as the coordinator's cast play ends without playing: Cancel, a failure, or nothing to load (L82, L84). */
    override fun stop() {
        val play = now
        onMain { play?.let { end(it, Why.Cancelled) } }
    }

    /** Stop casting (L85): the notification's ×, the session's own command, the placeholder's. */
    @MainThread
    fun stopCasting(why: Why = Why.StopCasting) {
        now?.let { end(it, why) }
    }

    /**
     * Stop casting from the app's own controls (L85): the sheet's pill, This phone or a TV chosen there. A cast play ends
     * as by the notification's ×, one still starting cancelled with its sheet, rather than as a session that went by
     * itself, which would leave its sheet to grab and fail; the session ends either way. Any thread.
     */
    fun endCast() = onMain {
        val play = now
        if (play != null) end(play, Why.StopCasting) else GoogleCast.connection.stop()
    }

    /** The service is up, for the plays: their loads go to it. */
    @MainThread
    fun up(running: CastService) {
        service.value = running
    }

    /** The service went: what it ran ends, its server gone with it. */
    @MainThread
    fun down(running: CastService) {
        if (service.value !== running) return
        listOfNotNull(now, before).forEach { finish(it, Why.Failed) }
        service.value = null
        _playing.value = false
    }

    /** An event of the device's, through the service: each play whose media it ends, ended. */
    @MainThread
    fun heard(event: CastEvent) {
        listOfNotNull(before, now).forEach { play -> CastServiceRules.ends(event, play.sent)?.let { end(play, it) } }
    }

    /** The session as it is now ([link]): a play whose device it no longer is, ended. */
    @MainThread
    fun linked(link: CastLinkNow?) {
        now?.let { play -> CastServiceRules.lost(link, play.cast.route)?.let { end(play, it) } }
    }

    /** The service's server couldn't listen again (L74, L88): every play it served has lost its stream. */
    @MainThread
    fun lostStream() {
        listOfNotNull(before, now).forEach { end(it, Why.LostStream) }
    }

    /** The phone's address changed (L74): the cast stops, its session with it. */
    @MainThread
    fun addressChanged() {
        now?.let { end(it, Why.AddressChanged) }
    }

    /** [play] sent the device its item as [mediaId]: the play before it there is over (L84), and the session follows the device once it has it. */
    @MainThread
    fun sent(play: CastPlay, mediaId: String) {
        if (play.ended) return
        play.sent = mediaId
        if (play !== now) return
        before?.let(::retire)
        service.value?.sent(mediaId)
    }

    /**
     * Ends [play] for [why] (L85): its addresses revoked, what it loaded stopped where the rules say, and where the session
     * ends, went or is another device's, every play with it; another sender's load ends only the play it replaced. Stop
     * casting while the play still starts cancels the play sheet's job, which is its own. The service goes on for a play
     * still running, else stops.
     */
    @MainThread
    fun end(play: CastPlay, why: Why) {
        if (play.ended) return
        val ending = CastServiceRules.ending(why)
        val starting = now?.takeIf { !it.played }
        val plays = if (CastServiceRules.endsEvery(why)) listOfNotNull(play, now, before).distinct() else listOf(play)
        plays.forEach { finish(it, why) }
        if (ending == Ending.EndSession) {
            if (starting != null && starting in plays) app.container.playback.cancel()
            GoogleCast.connection.stop()
        }
        val left = now
        val running = service.value
        if (left != null) {
            running?.show(left)
        } else {
            _playing.value = false
            service.value = null
            running?.stopForPlays()
        }
    }

    private fun finish(play: CastPlay, why: Why) {
        if (play.ended) return
        play.ended = true
        play.over.complete(why)
        play.served.forEach { it.revoke() }
        if (CastServiceRules.stopsDevice(why, loaded = play.sent != null)) service.value?.remote?.stop()
        if (why == Why.LostStream) play.failed.complete(CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CouldNotPlay, lostTheStream(play.cast.name))))
        if (play === before) before = null
        if (play === now) {
            now = before
            before = null
        }
        log.info("A cast play ended: {}", why)
    }

    /** The play before the one starting is over there, its load having replaced it (L84): only its addresses go. */
    private fun retire(play: CastPlay) {
        play.ended = true
        play.over.complete(null)
        play.served.forEach { it.revoke() }
        if (before === play) before = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    /** The outcome of a load whose play ended before it began: the session went, with nothing asked of the device (L60). */
    private val GONE = CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true))
}

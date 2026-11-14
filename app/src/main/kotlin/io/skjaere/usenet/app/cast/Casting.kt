@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.casting.CastEnd
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastFile
import io.skjaere.usenet.app.casting.CastLearn
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastMetadata
import io.skjaere.usenet.app.casting.CastOutcomes
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastProgress
import io.skjaere.usenet.app.casting.CastRefusal
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.casting.CastingRules
import io.skjaere.usenet.app.casting.CastingRules.Act
import io.skjaere.usenet.app.casting.LoadStep
import io.skjaere.usenet.app.casting.castEndLine
import io.skjaere.usenet.app.casting.castTimings
import io.skjaere.usenet.app.casting.counted
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.link.TvPlayingDeps
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchSaver
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.serve.ServedStream
import io.skjaere.usenet.app.serve.ServiceByteSource
import io.skjaere.usenet.app.serve.SourceReads
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastPrefs
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.serve.ByteSource
import io.skjaere.usenet.serve.ServeCounters
import io.skjaere.usenet.serve.Served
import io.skjaere.usenet.serve.ServedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * What loads a cast play's launch on its device and follows it there (L60, L62, L65, L68, L88, L102, L103): the real
 * [CastLoader], over the cast service ([CastServing]). The launch's file is found again as the player finds it, a Usenet
 * play's in the phone's store and a debrid play's in its kept listing (W73), and checked once more for a type the device
 * plays (L57); the service's server serves it to the device at the address it was found at (L55), its head opened ahead
 * of the LOAD so the device's first request is answered at once (L56), and a debrid play's link refused there fails the
 * load before anything is sent (L218). Its item ([CastItems]) says the play as [CastMetadata] does, TMDB's picture with
 * it while "Show artwork" is on, decoded first for the session's notification (L58), whose line names the device (L120).
 * Once the session's media
 * channel exists (#889), the load goes in
 * [CastingRules]' order (L68), and the rules, which hear the device through [CastSeams.events] and read the server's
 * counters, say how it came out: the coordinator's sheet waits on that. Once it played, the play is followed there until
 * it ends ([feed]), with the missing parts the server sent it and those it went on past (L94, L104), the device asked for
 * its status while the end or a served gap is near (L103, L104) and the play ended as the device says, its progress is
 * saved as a phone play's ([CastProgress], L61), what the device shows of it is learned for its ranking and verdicts
 * ([CastLearn], L128), and what the server sent it is counted each second for the slow-stream banner (L134). All on the main thread, the device's player's; it holds the application's context alone.
 */
@SuppressLint("StaticFieldLeak")
internal object Casting : CastLoader {
    private val log = LoggerFactory.getLogger(Casting::class.java)
    private lateinit var app: Context

    /** Where plays are followed once loaded: past the coordinator's job, which ends as the device plays. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _feed = MutableStateFlow<CastFeed?>(null)

    /** The play on the device as it is followed (L91): from its LOAD, and after its end until the next LOAD. */
    val feed: StateFlow<CastFeed?> = _feed.asStateFlow()

    /** What this phone has of TMDB's for a watch key, the artwork setting heeded: the TV's lookup (L62), or a test's. */
    @VisibleForTesting
    @Volatile
    var artOf: suspend (AppContainer, String) -> TvArt? = TvPlayingDeps::art

    /** A picture's bytes from its address, for the session's notification (L58): Coil's, its caches first, or a test's. */
    @VisibleForTesting
    @Volatile
    var picture: suspend (Context, String) -> ByteArray? = ::fetch

    /** This loader for [context]'s app. */
    fun with(context: Context): Casting = apply { app = context.applicationContext }

    /** A test's play on the device, as a load's is followed ([feed]): what the bar, the remote and the Show page show (L91). */
    @VisibleForTesting
    fun follows(feed: CastFeed?) {
        _feed.value = feed
    }

    override suspend fun load(launch: PlayerLaunch, serving: CastServing): CastLoadOutcome = withContext(Dispatchers.Main.immediate) {
        val container = app.container
        // What a debrid play added to the account is held while it plays there (L234): from here, before a move's player
        // closes and lets go of its own, until the play is followed no more, or the load ends without its following.
        val lease = (PlayUris.parse(launch.uri) as? PlayUris.Target.Service)?.let { Lease(container.debridAdds, it.listingId) }
        try {
            loadHeld(container, launch, serving, lease)
        } finally {
            if (lease?.followed == false) lease.release()
        }
    }

    /** [load] with what its debrid play added held by [lease], which its [Follow] takes over once the LOAD is sent. */
    private suspend fun loadHeld(container: AppContainer, launch: PlayerLaunch, serving: CastServing, lease: Lease?): CastLoadOutcome {
        val name = launch.cast?.name ?: return gone()
        val file = find(container, launch) ?: return CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CouldNotPlay, CAST_NOT_HERE))
        val extension = ContainerTypes.extension(file.name)
        val type = ContainerTypes.mimeOf(file.name)
        // L57 again: the probe checked it, and a file chosen by hand is checked here too, before anything is sent.
        if (!ContainerTypes.castable(file.name) || type == null) {
            return CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.FileType, AttemptFailure.NotForCast(extension).text))
        }
        val media = media() ?: return CastLoadOutcome.Failed(CastOutcomes.noRequest())
        val served = serving.serve(ServedFile(file.size, type, extension, file.source), media.address) ?: return CastLoadOutcome.Failed(CastOutcomes.noRequest())
        val refused = withContext(Dispatchers.IO) {
            try {
                served.openHead()
                null
            } catch (e: IOException) {
                // The device's first request opens its own; but a link the service refused fails the load here (L218).
                log.info("A cast's head couldn't be opened ahead ({})", e.javaClass.simpleName)
                file.account?.let { CastRefusal.of(launch, e, it) }
            }
        }
        if (refused != null) {
            // Remembered against the account as a play's failure is, so plays pass it over meanwhile (L174).
            saveOrLog("remember a debrid cast's refused link") { container.serviceMisses.record(refused.candidate, refused.account, refused.failure) }
            return CastLoadOutcome.Failed(CastRefusal.card(refused))
        }
        val showArtwork = container.settings.metadata.first().showArtwork
        val art = if (showArtwork && launch.watch.rememberedKey != null) withTimeoutOrNull(PICTURE_MS) { artOf(container, launch.watchKey) } else null
        val said = CastMetadata.of(launch, container.appName, art, showArtwork)
        val still = said.imageUrl?.let { url -> withTimeoutOrNull(PICTURE_MS) { picture(app, url) } }
        still?.let { bytes -> withContext(Dispatchers.Default) { serving.artwork(bytes) } }
        val item = CastItems.item(served.url, type, said, still, name, Build.VERSION.SDK_INT)
        val start = startOf(container, launch)
        // The maximum when casting this play was ranked by, for its end's line (L136).
        val castMax = container.settings.playback.first().castMaxBitrate?.let { (it / 1_000_000).toInt() }
        val loadedAt = SystemClock.elapsedRealtime()
        val rules = CastingRules(
            name, item.mediaId, CastFile(extension, video = launch.video != null, fromService = file.account != null), media.hasStatus, loadedAt,
            launch.video?.durationMs, size = file.size,
        )
        // What the device shows of it is learned by its Cast id (L128), over what was kept of it: a launch from before the
        // id went with the play teaches nothing.
        val learning = launch.cast.device?.let { device ->
            Learning(CastLearn(device, item.mediaId, launch.tracks), container.settings.cast.first().devices.firstOrNull { it.id == device }, container.settings)
        }
        // Listening before the LOAD: the device's first word of it is never missed.
        val follow = Follow(
            serving, served, rules, item.mediaId, CastFeed(item.mediaId, launch, CastPlayerState.Loading, start, null, art = art, size = file.size), learning,
            loadedAt, castMax, lease,
        )
        rules.steps.forEach { step ->
            when (step) {
                LoadStep.PlayWhenReady -> serving.remote.playWhenReady = true
                LoadStep.SetMediaItem -> serving.remote.setMediaItem(item, start)
            }
        }
        serving.sent(item.mediaId)
        follow.start()
        lease?.followed = true
        return try {
            follow.outcome.await()
        } catch (e: CancellationException) {
            // Cancel, or a failure the service found first: the play is given up, and so is following it.
            follow.stop()
            throw e
        }
    }

    /**
     * A debrid cast's hold on what its play added to the account (L234), by an owner of its own, taken as it is made:
     * [followed] once its [Follow] has started, which lets it go as following ends; else its load does. Letting go again
     * changes nothing.
     */
    private class Lease(private val adds: DebridAdds, listingId: String) {
        private val owner = Any()

        @Volatile
        var followed = false

        init {
            adds.hold(owner, listingId)
        }

        fun release() = adds.release(owner)
    }

    /**
     * [launch]'s file as a cast serves it ([source]), its [name] and [size]; [account]: the debrid service account its
     * listing came from, null for a Usenet play's.
     */
    private class Found(val name: String, val size: Long, val source: ByteSource, val account: String? = null)

    /**
     * [launch]'s file, found as the player finds it, by its URI's source (W73): a Usenet play's in the phone's store, read
     * over the engine's stream; a debrid play's in the listing kept for it, read over HTTPS through the container's own
     * debrid source, one ranged GET an upstream (L217). Null when the store or the listings have it no longer.
     */
    private suspend fun find(container: AppContainer, launch: PlayerLaunch): Found? = when (val target = PlayUris.parse(launch.uri)) {
        is PlayUris.Target.Usenet -> {
            val nzb = (container.store.getOrPrepare(target.nzb.nzbId, container.preparer) as? PrepareOutcome.Ready)?.nzb
            nzb?.find(target.fileIndex, target.name, target.size)?.let { file ->
                Found(file.name, file.size, ServedStream(nzb, file, container.opener, container.appScope, container.streamTraffic, container.gapReports))
            }
        }
        is PlayUris.Target.Service -> container.debridListings.get(target.listingId)?.let { entry ->
            entry.prepared.listing.files.firstOrNull { it.index == target.fileIndex }?.let { file ->
                val source = ServiceByteSource(file.size, SourceReads(container.serviceSources, launch.uri), container.appScope)
                Found(file.name, file.size, source, entry.account)
            }
        }
        null -> null
    }

    /** The session's media channel, once it exists (#889): within the connect's own wait, else none. */
    @MainThread
    private suspend fun media(): CastMedia? = withTimeoutOrNull(castTimings().connectMillis) {
        var media = GoogleCast.connection.media()
        while (media == null) {
            delay(CLIENT_POLL_MS)
            media = GoogleCast.connection.media()
        }
        media
    }

    /** Where the play starts: where the release before it failed, else where its watch was left, as the player starts (L61). */
    private suspend fun startOf(container: AppContainer, launch: PlayerLaunch): Long =
        launch.startPositionMs ?: WatchSaver.resumeAt(container.library.state(launch.watchKey).first())

    /** The outcome of a load with no device to go to: as one whose session went (L60). */
    private fun gone() = CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true))

    /**
     * One play on its device, from its LOAD ([loadedAt]) until it ends there ([CastingRules]): the device's events, a tick
     * a second and the play's own end, taken in turn. Its [outcome] is the load's; once the device plays it, it goes on in
     * [scope], past the coordinator's job, following the play for [feed] ([initial] from the LOAD) and saving its progress.
     * As it ends, one line says what it came to ([castEndLine], with the maximum when casting it was ranked by, [castMax]).
     * A debrid play's [lease] is let go as following ends, the grace of what it added beginning then (L234).
     */
    private class Follow(
        private val serving: CastServing,
        private val served: Served,
        private val rules: CastingRules,
        private val id: String,
        initial: CastFeed,
        private val learning: Learning?,
        private val loadedAt: Long,
        private val castMax: Int?,
        private val lease: Lease?,
    ) {
        val outcome = CompletableDeferred<CastLoadOutcome>()

        private sealed interface Input {
            data class Heard(val event: CastEvent) : Input

            data object Tick : Input

            data class Over(val why: Why?) : Input
        }

        private val inputs = Channel<Input>(Channel.UNLIMITED)
        private val listening = CastSeams.events(app).listen { inputs.trySend(Input.Heard(it)) }
        private val jobs = mutableListOf<Job>()

        /** The play as followed: what it gives [feed] while that is still this play's, and its progress is saved from (L61). */
        private var current: CastFeed = initial.also { _feed.value = it }

        /** Whether [feed] is still this play's: another play's LOAD takes its place, and this one then gives no more. */
        private var mine = true

        private val progress = CastProgress({ WatchSaver.save(app.container, it) })

        /** The device played it: from then on its progress is saved. Before, nothing is, so the resume point stays (L60). */
        private var played = false

        fun start() {
            jobs += scope.launch {
                while (true) {
                    delay(TICK_MS)
                    inputs.trySend(Input.Tick)
                }
            }
            jobs += scope.launch { inputs.trySend(Input.Over(serving.over.await())) }
            jobs += scope.launch {
                try {
                    for (input in inputs) if (!take(input)) break
                } finally {
                    stop()
                }
            }
        }

        /**
         * Following ends: as the play ended there, or as it was given up before the device played it (Cancel, or a failure
         * the service found first). Then nothing plays there that the phone follows (L82), so [feed] lets it go, as for a
         * load that failed: its LOAD left it loading, and nothing would say more of it.
         */
        fun stop() {
            listening.close()
            inputs.close()
            jobs.forEach { it.cancel() }
            lease?.release()
            if (!played && mine) {
                mine = false
                _feed.compareAndSet(current, null)
            }
        }

        /** Takes [input] in; false once there is nothing more to follow. */
        private fun take(input: Input): Boolean {
            val now = SystemClock.elapsedRealtime()
            val counters = served.counters()
            val acts = when (input) {
                is Input.Heard -> {
                    heard(input.event, now)
                    (input.event as? CastEvent.Status)?.let { learning?.heard(it) }
                    rules.heard(input.event, counters, now)
                }
                Input.Tick -> {
                    val (position, duration) = polled()
                    val gaps = served.gaps()
                    follow(now) {
                        it.copy(positionMs = position ?: it.positionMs, durationMs = duration, gaps = gaps, passed = served.passed()).counted(counters, now, served::answered)
                    }
                    learning?.tick(now)
                    rules.tick(counters, now, position, duration, gaps)
                }
                is Input.Over -> rules.over(input.why, counters, now)
            }
            var more = true
            for (act in acts) {
                when (act) {
                    Act.Play -> serving.remote.play()
                    Act.RequestStatus -> GoogleCast.connection.media()?.requestStatus()
                    is Act.Outcome -> {
                        outcome.complete(act.outcome)
                        val failed = act.outcome as? CastLoadOutcome.Failed
                        played = failed == null
                        log.info("A cast play's load came out: {}", failed?.failure?.kind ?: "playing")
                        // Failed before the device played it: the play sheet says so, and nothing plays there to follow.
                        if (failed != null) {
                            if (mine) _feed.compareAndSet(current, null)
                            more = false
                        }
                    }
                    is Act.End -> {
                        if (act.stopDevice) serving.remote.stop()
                        serving.end(act.why)
                    }
                    is Act.Ended -> {
                        ended(act.end, now, counters)
                        more = false
                    }
                }
            }
            return more
        }

        /** The device's status of this play: what it says it does, and where (an idle one says nowhere). */
        private fun heard(event: CastEvent, now: Long) {
            if (event !is CastEvent.Status || event.contentId != id) return
            val at = event.positionMs.takeIf { event.state != CastPlayerState.Idle }
            follow(now) { it.copy(state = event.state, positionMs = at ?: it.positionMs, reported = at ?: it.reported, reportedAt = event.at) }
        }

        /** Where the session's player is in this play, and its length, while its item is this play's; nulls otherwise. */
        private fun polled(): Pair<Long?, Long?> {
            val remote = serving.remote
            if (remote.currentMediaItem?.mediaId != id) return null to null
            return remote.currentPosition to remote.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        }

        /**
         * The play ended there so ([end]), saved so: the feed keeps it, so its words stay after the service stops, unless
         * the next play's LOAD took its place there (L84), which says nothing of it. The log says what it came to, by the
         * server's [counters] and the slow-stream dips the bar's follower counted (L136).
         */
        private fun ended(end: CastEnd, now: Long, counters: ServeCounters) {
            if (end.how == CastEnd.How.Replaced) mine = false
            follow(now) { it.copy(end = end) }
            log.info(castEndLine(current, counters, now - loadedAt, app.container.castPlaying?.dips(id) ?: 0, castMax))
        }

        /** The play as [change] makes it now: given to [feed] while that is this play's, and its progress saved as it says. */
        private fun follow(now: Long, change: (CastFeed) -> CastFeed) {
            val before = current
            current = change(before)
            if (mine) mine = _feed.compareAndSet(before, current)
            if (played) progress.heard(current, now)
        }
    }

    /**
     * What one play teaches of its device (L128), by [learn], over [memory], the device's facts as kept when the play
     * loaded: each change kept, and written to [settings] then, and only then ([CastPrefs.remembering], at most 20 devices).
     */
    private class Learning(private val learn: CastLearn, private var memory: CastDeviceMemory?, private val settings: SettingsRepository) {
        fun heard(status: CastEvent.Status) = keep(learn.heard(memory, status, System.currentTimeMillis()))

        fun tick(now: Long) = keep(learn.tick(memory, now, System.currentTimeMillis()))

        private fun keep(changed: CastDeviceMemory?) {
            changed ?: return
            memory = changed
            scope.launch { saveOrLog("keep what a Cast device has shown") { settings.updateCast { it.remembering(changed) } } }
        }
    }

    /** The picture at [url] as JPEG bytes, through the app's image loader and its caches. */
    private suspend fun fetch(context: Context, url: String): ByteArray? {
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray() }
    }

    /** The release's file isn't on this phone any longer: it was just prepared, so seldom. Words not drawn. */
    private const val CAST_NOT_HERE = "Couldn't open the release on this phone"

    /** How often the session's player is read, as Media3 polls the device's position (L61). */
    private const val TICK_MS = 1_000L

    /** How often the session's media channel is looked for while it doesn't exist yet. */
    private const val CLIENT_POLL_MS = 100L

    /** The picture's wait, at most: the LOAD doesn't wait longer on TMDB. */
    private const val PICTURE_MS = 5_000L

    private const val JPEG_QUALITY = 90
}

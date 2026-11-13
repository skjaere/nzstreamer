package io.skjaere.usenet.app.cast

import android.content.Context
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLoad
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.CastVolumeNow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.serve.ByteSource
import io.skjaere.usenet.serve.Served
import io.skjaere.usenet.serve.ServedFile
import io.skjaere.usenet.serve.Upstream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google's Cast software in the device tests (L73): the runner has Play services answer "missing" for the whole run
 * ([beforeTheApp], [missing]), so the gate starts nothing; a test that needs the Cast group says Cast [started], with no
 * Google call, over its own devices and sessions, and puts the runner's back after ([reset]). CastStartRecordDeviceTest
 * alone puts Google's own back, in a run of its own: once started, Google's software stays started for the rest of its
 * process.
 *
 * L143: [started] also has this phone look for Cast devices and its introduction shown, as one who chose to would, so a
 * test's group looks at once and nothing covers its screen; [reset] puts the settings back. A test of the opt-in's row
 * turns it off itself. The app's castAvailable follows each fake ([refreshCastAvailable]), as MainActivity's start would
 * read it, which a test that composes its own screens never runs.
 */
object CastFakes {
    /** How many times the gate started the fakes' Cast: what a test reads to know that nothing started, or once. */
    val starts = AtomicInteger()

    /** A start that starts nothing but the count, and never fails. */
    private val noStart = object : CastStarter {
        override fun start(context: Context) {
            starts.incrementAndGet()
        }

        override fun failed(context: Context) = false
    }

    /** The settings as [started] first found them, which [reset] puts back. */
    @Volatile private var settingsBefore: AppSettings? = null

    private val realRoutes = CastSeams.routes
    private val realConnector = CastSeams.connector
    private val realAnnounce = CastSeams.announce
    private val realLauncher = CastSeams.launcher
    private val realLoader = CastSeams.loader
    private val realEvents = CastSeams.events
    private val realAddresses = CastSeams.addresses
    private val realPresent = CastSeams.present
    private val realArt = Casting.artOf
    private val realPicture = Casting.picture

    /**
     * The runner's: Play services missing, so the Cast group says Cast isn't available and nothing starts; and a fake
     * session's player ([FakeRemotePlayer]) wherever a test's fake session starts one, never Media3's, which needs Google's
     * software started.
     */
    fun missing() {
        set(CastAvailability { false }, noStart)
        CastPlayers.make = { FakeRemotePlayer() }
    }

    /** The runner's [missing], before the app is made: the app's own start reads castAvailable from the fake then. */
    fun beforeTheApp() {
        gate(CastAvailability { false }, noStart)
        CastPlayers.make = { FakeRemotePlayer() }
    }

    /**
     * As if Google's software started, with no Google call: the Cast group looks for devices through a test's fakes, this
     * phone looking for them ("Look for Cast devices" chosen) and the introduction shown (L143).
     */
    fun started() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.container
        runBlocking {
            if (settingsBefore == null) settingsBefore = app.settings.settings.first()
            app.settings.updateCast { it.copy(look = true, introShown = true) }
            // Read by the app before a test's list opens: the group looks at once, with no row to tap first.
            withTimeout(10_000) { app.castLooking.first { it == true || app.television } }
            // And by the shell before Play services is there: the introduction never shows over a test's screen for a moment.
            withTimeout(10_000) { app.castIntroShown.first { it == true || app.television } }
        }
        set(CastAvailability { true }, noStart)
    }

    /** As [started], the group's devices from [routes], its sessions [connector]'s, and what TalkBack is told in [announced]. */
    fun started(routes: FakeRoutes, connector: FakeConnector, announced: MutableList<String>) {
        CastSeams.routes = { routes }
        CastSeams.connector = { connector }
        CastSeams.announce = { _, words -> announced += words }
        started()
    }

    /** [availability] and [starter] from now, the gate as if Cast had never been asked for, and castAvailable as it answers. */
    fun set(availability: CastAvailability, starter: CastStarter) {
        gate(availability, starter)
        refreshCastAvailable()
    }

    private fun gate(availability: CastAvailability, starter: CastStarter) {
        CastGate.availability = availability
        CastGate.starter = starter
        CastGate.forget()
    }

    /** The app's castAvailable as the gate's check answers now (L146): a phone's alone, a TV's staying unread. */
    private fun refreshCastAvailable() = InstrumentationRegistry.getInstrumentation().targetContext.container.refreshCastAvailable()

    /**
     * A session with [route] as Google's would start it, over [connector], and the device chosen in Play on: what a cast
     * play needs, with no Google call.
     */
    fun connected(route: CastRoute, connector: FakeConnector) {
        onMainThread {
            GoogleCast.connection.attach(connector, InstrumentationRegistry.getInstrumentation().targetContext)
            GoogleCast.connection.connect(route)
        }
        connector.started(route)
    }

    /** The runner's again: no session, no cast play or feed, Google's seams, the design's timings, the settings as they were. */
    fun reset() {
        settingsBefore?.let { before ->
            runBlocking { InstrumentationRegistry.getInstrumentation().targetContext.container.settings.restore(before) }
            settingsBefore = null
        }
        starts.set(0)
        missing()
        CastSeams.routes = realRoutes
        CastSeams.connector = realConnector
        CastSeams.announce = realAnnounce
        CastSeams.launcher = realLauncher
        CastSeams.loader = realLoader
        CastSeams.events = realEvents
        CastSeams.addresses = realAddresses
        CastSeams.present = realPresent
        Casting.artOf = realArt
        Casting.picture = realPicture
        // No play on the device for the next test's bar to show, nor a line about one for it to say.
        Casting.follows(null)
        InstrumentationRegistry.getInstrumentation().targetContext.container.castPlaying?.forget()
        // The session's end ends any cast play left, and its service.
        onMainThread { GoogleCast.connection.forget() }
        // The device's player, kept for the process, as the boards have it again.
        (onMainThread { GoogleCast.connection.made } as? FakeRemotePlayer)?.lasting(null)
        CastTimings.current = CastTimings()
    }
}

/**
 * A Cast device as MediaRouter would list it, invented: [name], what the device says it is doing ([description]), what it
 * is (a screen, else a speaker, or a [group]; sound from each, as [audioOut]), and the facts its route gives (L153): its
 * [model], [address] and whether its route says its volume is fixed ([fixedVolume]).
 */
fun castRoute(
    name: String,
    description: String? = null,
    videoOut: Boolean = true,
    group: Boolean = false,
    model: String? = null,
    address: String? = null,
    fixedVolume: Boolean = false,
    audioOut: Boolean = true,
) = CastRoute(
    id = "test-route:$name", name = name, description = description, isDefault = false, bluetooth = false, enabled = true, matches = true,
    videoOut = videoOut, group = group, deviceId = "test-device:$name", model = model, address = address, fixedVolume = fixedVolume, audioOut = audioOut,
)

/** The devices a test says are found ([found]), told to the group as MediaRouter tells it, on the main thread. */
class FakeRoutes : CastRouteSource {
    @Volatile private var routes: List<CastRoute> = emptyList()
    private val watchers = CopyOnWriteArrayList<(List<CastRoute>) -> Unit>()

    /** How many look now: the sheet's group while it shows. */
    val watching: Int get() = watchers.size

    fun found(vararg routes: CastRoute) {
        this.routes = routes.toList()
        onMainThread { watchers.forEach { it(this.routes) } }
    }

    override fun watch(found: (List<CastRoute>) -> Unit): AutoCloseable {
        watchers += found
        found(routes)
        return AutoCloseable { watchers -= found }
    }
}

/**
 * Sessions a test drives: what was [selected], how many times a session was ended ([ends], the device stopped: Google's
 * `endCurrentSession(true)`) and left ([disconnects], the device playing on: `endCurrentSession(false)` and the route let
 * go as disconnected); each event as Google's would come. The session's media channel ([media]) exists from the start;
 * whether the device has a media status already ([FakeMedia.hasStatus]: another sender's) is the test's to say, and the
 * status asks are counted. The device's volume as the session has it is the test's too ([volume]; none until it says).
 */
class FakeConnector : CastConnector {
    val selected = CopyOnWriteArrayList<String>()
    val ends = AtomicInteger()
    val disconnects = AtomicInteger()
    val media = FakeMedia()

    @Volatile
    var volume: CastVolumeNow? = null
    @Volatile private var events: CastConnector.Events? = null
    @Volatile private var device: String? = null

    override fun select(route: CastRoute): Boolean {
        selected += route.name
        device = route.deviceId
        return true
    }

    override fun end() {
        ends.incrementAndGet()
        events?.ended(device)
    }

    override fun disconnect() {
        disconnects.incrementAndGet()
        events?.ended(device)
    }

    override fun volume(): CastVolumeNow? = volume

    override fun listen(events: CastConnector.Events): AutoCloseable {
        this.events = events
        return AutoCloseable { if (this.events === events) this.events = null }
    }

    override fun media(): CastMedia = media

    fun started(route: CastRoute) = onMainThread { events?.started(route.deviceId) }

    fun refused(route: CastRoute) = onMainThread { events?.startFailed(route.deviceId) }

    fun suspended(route: CastRoute) = onMainThread { events?.suspended(route.deviceId) }

    fun resumed(route: CastRoute) = onMainThread { events?.resumed(route.deviceId) }

    fun ended(route: CastRoute) = onMainThread { events?.ended(route.deviceId) }

    /** The device's media in [route]'s session says [event], as Google's callback would tell it. */
    fun media(route: CastRoute, event: CastEvent) = onMainThread { events?.media(route.deviceId, event) }
}

/** A session's media channel a test plays: whether the device has a media status, its address, and how often it was asked for one. */
class FakeMedia : CastMedia {
    @Volatile
    override var hasStatus = false

    @Volatile
    override var address: InetAddress? = null

    val asked = AtomicInteger()

    override fun requestStatus() {
        asked.incrementAndGet()
    }
}

/**
 * The device's side of cast plays, which a test drives (L65): what started a play ([started], each with what its service
 * says), each launch handed over ([loads]) and the stops; each load waits until the test says the device plays it
 * ([plays]) or it failed ([fails]). [refuse]: Android refuses the service's start.
 */
class FakeLauncher : CastLauncher {
    val started = CopyOnWriteArrayList<Pair<CastTo, SessionMetadata>>()
    val loads = CopyOnWriteArrayList<PlayerLaunch>()
    val stops = AtomicInteger()
    private val outcomes = CopyOnWriteArrayList<CompletableDeferred<CastLoadOutcome>>()

    @Volatile
    var refuse = false

    override fun starting(cast: CastTo, metadata: SessionMetadata): Boolean {
        if (refuse) return false
        started += cast to metadata
        return true
    }

    override fun load(launch: PlayerLaunch): CastLoad {
        val outcome = CompletableDeferred<CastLoadOutcome>()
        outcomes += outcome
        loads += launch
        return CastLoad { outcome.await() }
    }

    override fun stop() {
        stops.incrementAndGet()
    }

    /** The device plays the last launch handed over. */
    fun plays() = outcomes.last().complete(CastLoadOutcome.Playing)

    /** The last launch handed over failed, for [failure]. */
    fun fails(failure: CastLoadFailure) = outcomes.last().complete(CastLoadOutcome.Failed(failure))
}

/**
 * The Cast session's player as a test plays the device (L73): as Media3's RemoteCastPlayer starts, idle with an empty
 * timeline, playing on a remote device whose volume it has. A load ([setMediaItem]) puts the item there, buffering; the
 * test says when the device plays it ([playing]); play, pause and seeks act at once; stop empties it, as the device's player
 * does. [progressed] moves its place on with no event, as Media3's does while the device plays. [changed] gives its item
 * new metadata, as a status from the device can; [emptied] has it empty for a moment, as a resumed session's first status
 * with no media leaves Media3's, until [back]. Its item lasts the boards' 52 minutes, or what a test's device found
 * ([lasting]). Its device volume starts at 8 of 20 (40 %); the levels set are kept ([volumes]), and the device's own
 * changes come as [deviceVolume] says. On the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FakeRemotePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items: List<MediaItem> = emptyList()
    private var away: List<MediaItem> = emptyList()
    private var buffering = true
    private var playWhenReady = false

    /** Read live, as Media3's player moves its position on between the device's statuses ([progressed]). */
    @Volatile private var positionMs = 0L
    private var volume = 8

    /** How long the device's player says its item lasts. */
    @Volatile private var durationUs = DURATION_US

    /** The items loaded, in order. */
    val loads = CopyOnWriteArrayList<MediaItem>()

    /** How many times the device's player was stopped. */
    val stops = AtomicInteger()

    /** What it was asked to do, in order: "play", "pause", "seek to <ms>". */
    val commands = CopyOnWriteArrayList<String>()

    /** The device volumes it was asked to set, in order (0 to 20). */
    val volumes = CopyOnWriteArrayList<Int>()

    private var muted = false

    override fun getState(): State {
        val state = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setDeviceInfo(DEVICE)
            .setDeviceVolume(volume)
            .setIsDeviceMuted(muted)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setMediaMetadata(it.mediaMetadata).setDurationUs(durationUs).build() })
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { positionMs })
        if (items.isEmpty()) return state.setPlaybackState(Player.STATE_IDLE).build()
        return state.setCurrentMediaItemIndex(0).setPlaybackState(if (buffering) Player.STATE_BUFFERING else Player.STATE_READY).build()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        loads += mediaItems
        buffering = true
        positionMs = startPositionMs.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        commands += if (playWhenReady) "play" else "pause"
        this.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        stops.incrementAndGet()
        items = emptyList()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        commands += "seek to $positionMs"
        this.positionMs = positionMs.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volumes += deviceVolume
        volume = deviceVolume
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume + 1, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume - 1, flags)

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** The device plays what it was sent. */
    fun playing() = onMainThread {
        buffering = false
        invalidateState()
    }

    /** The device plays on to [toMs]: Media3's player says the new place when asked, and sends no event for it. */
    fun progressed(toMs: Long) = onMainThread { positionMs = toMs }

    /** Nothing on the device's player for a moment: a status with no media, as a resumed session's first can be. */
    fun emptied() = onMainThread {
        away = items
        items = emptyList()
        invalidateState()
    }

    /** The device's item back, as the next status brings it. */
    fun back() = onMainThread {
        items = away
        invalidateState()
    }

    /** The device's player found its item lasts [ms], as a receiver says once it has read the file; null: the boards' 52 minutes. */
    fun lasting(ms: Long?) = onMainThread {
        durationUs = ms?.let { it * 1_000 } ?: DURATION_US
        invalidateState()
    }

    /** The device's volume changed there ([level] of 20, [mute]d or not): by its own remote, another phone, as Media3 hears it. */
    fun deviceVolume(level: Int, mute: Boolean = false) = onMainThread {
        volume = level
        muted = mute
        invalidateState()
    }

    /** The device's item says [metadata] now. */
    fun changed(metadata: MediaMetadata) = onMainThread {
        items = items.map { it.buildUpon().setMediaMetadata(metadata).build() }
        invalidateState()
    }

    private companion object {
        val DEVICE = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(20).build()
        /** 52 minutes, as the boards' episode is: room for a pause at 32:10. */
        const val DURATION_US = 3_120_000_000L

        /** What Media3's own offers that the session might show: its next and previous too, which the notification leaves out. */
        val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_RELEASE,
        ).build()
    }
}

/**
 * The load of a cast play as a test plays the device's part (L65): each launch handed over ([launches]) is served from the
 * test's own [bytes] as an MKV ([served]), its still (if [artwork]) prepared, and its item sent to the device's player as
 * [said] says it, by an id of the load's own ([sent]), the device asked to play it; then the outcome the test gives
 * ([plays], [fails]). No URL goes in the item: the token is the load's alone (L62).
 */
internal class FakeLoader(
    private val bytes: ByteArray,
    private val said: (PlayerLaunch) -> MediaMetadata,
    @Volatile var artwork: ByteArray? = null,
) : CastLoader {
    val launches = CopyOnWriteArrayList<PlayerLaunch>()
    val served = CopyOnWriteArrayList<Served>()
    val sent = CopyOnWriteArrayList<String>()

    /** What the last load was handed. */
    @Volatile
    var serving: CastServing? = null
    private val outcomes = CopyOnWriteArrayList<CompletableDeferred<CastLoadOutcome>>()

    override suspend fun load(launch: PlayerLaunch, serving: CastServing): CastLoadOutcome {
        val outcome = CompletableDeferred<CastLoadOutcome>().also { outcomes += it }
        launches += launch
        this.serving = serving
        val still = artwork?.also(serving::artwork)
        // Nowhere to serve from in the device's wait: it can't reach the phone, as the real load says.
        served += serving.serve(ServedFile(bytes.size.toLong(), "video/x-matroska", "mkv", ByteSource { position, _ -> InMemory(bytes, position) }), null)
            ?: return CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CannotReach)).also { outcome.complete(it) }
        val id = "test-load-${launches.size}"
        sent += id
        serving.sent(id)
        val metadata = said(launch).buildUpon().apply { if (still != null) setArtworkData(still, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }.build()
        serving.remote.setPlayWhenReady(true)
        serving.remote.setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build())
        return outcome.await()
    }

    /** The device plays the last launch handed over. */
    fun plays() = outcomes.last().complete(CastLoadOutcome.Playing)

    /** The last launch handed over failed, for [failure]. */
    fun fails(failure: CastLoadFailure) = outcomes.last().complete(CastLoadOutcome.Failed(failure))

    /** The test's bytes from [at] on. */
    private class InMemory(private val bytes: ByteArray, private var at: Long) : Upstream {
        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (at >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - at).toInt()
            bytes.copyInto(buffer, offset, at.toInt(), at.toInt() + n)
            at += n
            return n
        }

        override fun close() = Unit
    }
}

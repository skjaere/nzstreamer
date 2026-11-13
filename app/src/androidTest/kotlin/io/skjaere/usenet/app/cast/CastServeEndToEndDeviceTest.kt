package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastIdleReason
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.RemoteBar
import io.skjaere.usenet.app.link.RemoteKind
import io.skjaere.usenet.app.link.RemotePlaying
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A cast end to end on a phone (U3): from its tap to its end through the real cast service, its server and the real load
 * ([Casting]), over a fake Cast session whose device player's LOAD has a stand-in receiver in the test's process fetch
 * the token URL the LOAD carries, at the emulator's Wi-Fi address the service listens at. The stand-in ([StandInReceiver])
 * reads as the Shield's receiver did (a spike's T1-T3, T6, T8; as ChromiumLikeClientTest): `bytes=0-` first, a burst, then
 * deferring; the first seek a reset of that connection, the Cues at the end on a new one and the target, which our
 * `Connection: close` puts on a new one too; a reset mid-body asked again 0.3 s later from the last byte it had; never
 * more than two connections; a pause holding its connection with no progress. Then its media player (ExoPlayer over
 * DefaultHttpDataSource) plays the 20 s film, a release on the test's own NNTP server (LocalNntp, L99), to its end, the
 * device's player following it and the stand-in answering the phone's status asks.
 *
 * All of it in a pocket ([Pocket]): the screen off, unplugged, Doze forced, the app in the restricted standby bucket and
 * battery saver on, each put back after, whatever happens, and by the next process's runner should this one die in it
 * ([Pocket.putBackLeft]). The service holds the foreground all along, looked at from the
 * pocket's start to the device's end of the film, paused from the phone past Media3's (shrunk) foreground timeout too;
 * played again, the server answers. The progress is saved, at the pause and as watched at the end.
 *
 * What this can show: the code runs to its end under all that, the server's threads answering. Not what keeps it running
 * on a phone in a pocket: under instrumentation Android holds the test's process, the app's own, at a foreground
 * service's process state whatever the service does, so Doze, the bucket and battery saver spare it and its wake lock
 * either way; the wake lock's lines show only that it was taken while the stand-in read. That the service's own
 * foreground does it here is what its looks say; on a phone, M2 and M4. Nor that a separate peer on the network gets in
 * (W31): the stand-in runs in the app's own process and UID and reaches the server at the emulator's own address. The
 * film, the release and the device are invented, the media synthetic.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastServeEndToEndDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val film = WatchTarget(WatchKind.Movie, FILM_TITLE, movieTmdbId = FILM)
    private val key = WatchKey.movie(FILM)
    private val fixtures = GapFixtures(local, INDEXER, film)

    /** The film: 20 s of H.264 and AAC in Matroska, its Cues at the end (synthetic, the device tests' own). */
    private val media = fixtures.media("dip.mkv")
    private val size = media.size.toLong()
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val events = CastEventHub()
    private val pocket = Pocket(context)
    private val receiver = StandInReceiver(media)

    /** Where the device's part runs beside the test: following its media player, never the test's own runBlocking. */
    private val beside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var remote: FakeRemotePlayer

    /** What the bar's and the remote's buttons go to: the cast while one lives (L91). */
    private lateinit var playing: RemotePlaying
    private var player: ExoPlayer? = null
    private var tried: List<TriedRelease>? = null

    /** Every line `dumpsys power` gave of the service's wake lock while the stand-in read. */
    private val wakeLines = CopyOnWriteArrayList<String>()

    /** Whether the service was in the foreground, at each look from the pocket's start to the film's end there. */
    private val looks = CopyOnWriteArrayList<Boolean>()

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        // Media3's foreground timeout shrunk, for a pause past it; the wake lock let go 2 s after the last write.
        CastTimings.current = CastTimings(foregroundTimeoutMillis = 2_000, wakeLetGoMillis = 2_000)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.loader = { Casting.with(it) }
        CastSeams.events = { events }
        Casting.artOf = { _, _ -> null }
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        // Followed from here, as the app's shell follows it from its start: made at first use (no screen of the app's shows
        // here), it would show nothing of the play until it heard its next word, and a pause sent then would go nowhere.
        playing = container.remotePlaying
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        waitUntil("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        waitUntil("no cast service left over") { !serviceRunning() }
    }

    @After
    fun restore() = runBlocking<Unit> {
        // The phone out of the pocket first, whatever happened: the tests after this one need it as it was.
        val notBack = pocket.restore()
        beside.cancel()
        receiver.close()
        player?.let { onMainThread { it.release() } }
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        PlaybackCoordinator.nzbSource = realSource
        PlayerActivity.lookUpSegments = realLookUp
        local.close()
        tried?.let {
            container.library.restoreTriedReleases(it)
            forgetFilmsRelease()
        }
        container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        waitUntil("the cast service gone") { !serviceRunning() }
        assertEquals("the phone not put back as it was", emptyList<String>(), notBack)
    }

    /**
     * W27, L54-L56, L58, L59, L61, L96, L101 (U3): a cast served with the phone in a pocket plays to its end, paused past the
     * foreground timeout and played again, and is saved.
     */
    /** Within the sharded runner's 4 minutes of a hang: the body alone, so a hang's @After puts the phone back at once. */
    @Test(timeout = TIMEOUT_MS)
    fun aCastServedFromAPocketPlaysToItsEndAndIsSaved() = runBlocking<Unit> {
        val nzb = onTheTestsServer()
        val held = CompletableDeferred<Unit>()
        val loadsBefore = remote.loads.size
        playHeld(nzb, held)
        // Tapped with the screen on; then into the pocket, and what follows runs there: the pipeline, the LOAD, the play.
        waitUntil("the service in the foreground", 10_000) { foreground() }
        pocket.enter()
        // Looked at all along from here: the process's state under instrumentation says nothing of it (the class's KDoc).
        val looking = lookAtTheForeground()
        held.complete(Unit)

        // The LOAD: the stand-in fetches the token URL it carries, as the receiver is sent it (CastItems).
        waitUntil("the LOAD", 60_000) { remote.loads.size > loadsBefore }
        val item = remote.loads.last()
        val url = onMainThread { CastItems.toMediaQueueItem(item).media?.contentUrl } ?: throw AssertionError("no address in the LOAD")
        val wifi = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        assertEquals("served at the phone's Wi-Fi address", wifi.hostAddress, URI(url).host)
        receiver.at(url)

        // T1: bytes=0- first, on a connection of its own: a burst, then deferring, its buffer full.
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        assertEquals("bytes 0-${size - 1}/$size", head.header("content-range"))
        assertEquals("$size", head.header("content-length"))
        assertEquals("bytes", head.header("accept-ranges"))
        assertEquals("video/x-matroska", head.header("content-type"))
        assertEquals("no-store", head.header("cache-control"))
        assertEquals("close", head.header("connection"))
        assertEquals("*", head.header("access-control-allow-origin"))
        head.read(BURST)
        sampleWakeLock()
        // T2, the first seek: the head's connection reset as a new one reads the Cues at the end, to its end.
        val tail = media.lastAt(CUES) / ALIGN * ALIGN
        val cues = receiver.ask(tail) ?: throw AssertionError("the tail's request unanswered")
        receiver.cut(head)
        cues.toEnd()
        // The receiver asks its target on the tail's connection: closed after its body (Connection: close), so on a new one.
        assertNull("a second answer on one connection", receiver.ask(TARGET, on = cues))
        val target = receiver.ask(TARGET) ?: throw AssertionError("the target's request unanswered")
        target.read(SEEK_READ)
        // T6: reset by the receiver mid-body, asked again 0.3 s later from the last byte it had.
        receiver.cut(target)
        Thread.sleep(300)
        val again = receiver.ask(TARGET + target.got) ?: throw AssertionError("the retry unanswered")
        again.read(SEEK_READ)
        receiver.cut(again)
        assertEquals("nothing but 206", List(receiver.statuses.size) { 206 }, receiver.statuses.toList())
        assertTrue("more than two of its connections at once: ${receiver.mostOpen}", receiver.mostOpen <= 2)

        // The device's media player plays the film from its start, over HTTP, as a receiver's does; the device's player
        // (the session's) follows it, and the device says it plays: the load played, the play sheet goes.
        val exo = play(url)
        waitUntil("the stand-in's player playing", 30_000) { onMainThread { exo.isPlaying } }
        val length = onMainThread { exo.duration }
        assertTrue("the film's own length: $length ms", length in 19_000..21_000)
        val id = item.mediaId
        remote.lasting(length)
        remote.playing()
        tell(CastPlayerState.Playing, id, onMainThread { exo.currentPosition })
        waitUntil("the play sheet closed: the device plays it") { container.playback.state.value == PlayState.Idle }
        follow(exo, id)

        // Paused from the phone 6 s in: the device pauses there and holds its connection with no progress for the pause.
        waitUntil("6 s in", 30_000) { onMainThread { exo.currentPosition } >= PAUSE_AT }
        waitUntil("the bar's play playing there") { playing.now.value?.let { it.kind == RemoteKind.Cast && it.bar == RemoteBar.Playing } == true }
        onMainThread { playing.pause() }
        waitUntil("the device told to pause") { remote.commands.lastOrNull() == "pause" }
        val pausedAt = onMainThread {
            exo.pause()
            exo.currentPosition
        }
        tell(CastPlayerState.Paused, id, pausedAt)
        val place = size * pausedAt / length / ALIGN * ALIGN
        val holding = receiver.ask(place) ?: throw AssertionError("the pause's request unanswered")
        holding.read(PAUSE_READ)
        // Past Media3's foreground timeout (2 s here), looked at all along: still in the foreground, paused.
        val until = SystemClock.uptimeMillis() + PAUSED_MS
        while (SystemClock.uptimeMillis() < until) {
            assertTrue("in the foreground, paused past Media3's timeout", foreground())
            Thread.sleep(250)
        }
        val atPause = withTimeout(10_000) { container.library.state(key).first { it?.positionMs == pausedAt } }
        assertFalse("saved at the pause, not watched", atPause!!.watched)

        // Played again from the phone: the server answers, on the connection held through the pause and on a new one.
        waitUntil("the bar's play paused there") { playing.now.value?.bar == RemoteBar.Paused }
        onMainThread { playing.resume() }
        waitUntil("the device told to play") { remote.commands.lastOrNull() == "play" }
        holding.read(PAUSE_READ)
        sampleWakeLock()
        val resumed = receiver.ask(place + PAUSE_READ * 2 / ALIGN * ALIGN) ?: throw AssertionError("the resume's request unanswered")
        assertEquals("the server answers after the pause", 206, resumed.status)
        resumed.read(PAUSE_READ)
        receiver.close()
        onMainThread { exo.play() }
        tell(CastPlayerState.Playing, id, pausedAt)

        // To its end: FINISHED there. The play ends, saved as watched with its release; the service and its server go.
        waitUntil("the film played to its end", 60_000) { onMainThread { exo.playbackState } == Player.STATE_ENDED }
        assertTrue("the phone never asked the device where it is near the end (L103)", connector.media.asked.get() > 0)
        looking.cancelAndJoin()
        assertTrue("looked at the foreground ${looks.size} times", looks.size >= LOOKS)
        assertEquals("out of the foreground in the pocket, of ${looks.size} looks", 0, looks.count { !it })
        tell(CastPlayerState.Idle, id, length, CastIdleReason.Finished)
        waitUntil("the service stopped") { !serviceRunning() }
        val watched = withTimeout(10_000) { container.library.state(key).first { it?.watched == true } }
        assertEquals("the release that played remembered", MKV, watched?.release?.title)
        assertFalse("the server gone with the service", listening(url))

        // In the pocket all along, and the service's wake lock held while the stand-in read, never let go by Doze (which
        // spares the instrumented process anyway: the foreground's looks are what tell).
        pocket.still()
        assertTrue("the wake lock never seen held while the stand-in read", wakeLines.isNotEmpty())
        assertEquals("the wake lock disabled under Doze", emptyList<String>(), wakeLines.filter { "DISABLED" in it })
    }

    /**
     * Looks at the service's foreground every [LOOK_MS] into [looks], until cancelled: on the IO threads, as each look
     * blocks on the shell, so the app's own work on Dispatchers.Default (the cast play the buttons go to) isn't held up.
     */
    private fun lookAtTheForeground(): Job = beside.launch(Dispatchers.IO) {
        while (true) {
            looks += foreground()
            delay(LOOK_MS)
        }
    }

    /** The test's own NNTP server under the engine, and the film's release on it, prepared and in the store (L40): its NZB. */
    private suspend fun onTheTestsServer(): ByteArray {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        tried = container.library.triedReleaseEntries()
        container.library.clearTriedReleases()
        forgetFilmsRelease()
        return fixtures.release("dip.mkv", media, PART, "cast-end-to-end", MKV) { emptySet() }.nzb
    }

    private suspend fun forgetFilmsRelease() {
        container.library.lastRelease(key)?.let { container.library.forgetRelease(key, it) }
    }

    /** The film played through the coordinator onto Kitchen display, as a tap asks for it, its NZB held until [held] completes. */
    private fun playHeld(nzb: ByteArray, held: CompletableDeferred<Unit>) {
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { held.await(); nzb } }
        onMainThread {
            container.playback.play(PlayRequest(FILM_TITLE, null, listOf(fixtures.candidate(MKV, MKV)), FilePicker.Largest, film, chosen = true), PlayOrigin(cast = device))
        }
    }

    /** The stand-in's media player on [url], playing, silent: diagnostics off, as every player the app builds. */
    private fun play(url: String): ExoPlayer = onMainThread {
        ExoPlayer.Builder(context)
            .setUsePlatformDiagnostics(false)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(StandInReceiver.SHIELD_AGENT)))
            .build()
            .apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
    }.also { player = it }

    /**
     * The device as the session's player says it, while [exo] plays [id]: its place, which Media3's player moves on with no
     * event, and an answer to each of the phone's status asks (L103), as the receiver gives.
     */
    private fun follow(exo: ExoPlayer, id: String): Job = beside.launch {
        var asked = connector.media.asked.get()
        while (true) {
            remote.progressed(onMainThread { exo.currentPosition })
            val now = connector.media.asked.get()
            if (now != asked) {
                asked = now
                onMainThread { events.tell(statusOf(exo, id)) }
            }
            delay(FOLLOW_MS)
        }
    }

    /** What the receiver says of [exo]'s play of [id]: playing, paused, buffering, or done. */
    private fun statusOf(exo: ExoPlayer, id: String): CastEvent.Status {
        val at = exo.currentPosition
        val now = SystemClock.elapsedRealtime()
        return when {
            exo.playbackState == Player.STATE_ENDED -> CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Finished, id, at, now)
            exo.isPlaying -> CastEvent.Status(CastPlayerState.Playing, null, id, at, now)
            !exo.playWhenReady -> CastEvent.Status(CastPlayerState.Paused, null, id, at, now)
            else -> CastEvent.Status(CastPlayerState.Buffering, null, id, at, now)
        }
    }

    /** The device says it is [state] in [id] at [positionMs] ([idle]: why it is idle). */
    private fun tell(state: CastPlayerState, id: String, positionMs: Long, idle: CastIdleReason? = null) =
        onMainThread { events.tell(CastEvent.Status(state, idle, id, positionMs, SystemClock.elapsedRealtime())) }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.shell(command)

    /** Android's record of the cast service, read and never printed: it names the app's process. */
    private fun serviceRecord(): String = shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}")

    private fun serviceRunning(): Boolean = serviceRecord().let { "ServiceRecord{" in it && "CastService" in it }

    private fun foreground(): Boolean = serviceRecord().let { "CastService" in it && "isForeground=true" in it }

    /** The service's wake lock as `dumpsys power` lists it while the stand-in reads, for 2 s at most: kept, never printed. */
    private fun sampleWakeLock() {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            val held = shell("dumpsys power").lines().dropWhile { !it.trim().startsWith("Wake Locks:") }.drop(1).takeWhile { it.startsWith(" ") }
                .filter { "'$WAKE_TAG'" in it }
            if (held.isNotEmpty()) {
                wakeLines += held
                return
            }
            Thread.sleep(50)
        }
    }

    /** Where [part] last starts in these bytes. */
    private fun ByteArray.lastAt(part: ByteArray): Long =
        (size - part.size downTo 0).first { at -> part.indices.all { this[at + it] == part[it] } }.toLong()

    /** Whether anything listens where [url] points. */
    private fun listening(url: String): Boolean = URI(url).let { uri -> runCatching { Socket(uri.host, uri.port).close() }.isSuccess }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 991_651
        const val FILM_TITLE = "Lantern Field"
        const val INDEXER = "cast-end-to-end-device-test"
        const val MKV = "Lantern.Field.2026.1080p.WEB-DL.H.264-CAST"
        const val PART = 128_000

        /** The receiver asks from 32 KiB boundaries (item 6(a)). */
        const val ALIGN = 32_768L

        /** Matroska's Cues element id: the film's last, its index (its SeekHead points there). */
        val CUES = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)

        /** The opening burst, then deferring; reads after a seek, and around the pause. */
        const val BURST = 512L * 1024
        const val SEEK_READ = 128L * 1024
        const val PAUSE_READ = 64L * 1024

        /** The first seek's target, the film's middle, as the receiver rounds it. */
        const val TARGET = 1_048_576L

        /** Where the phone pauses it, and for how long: well past Media3's shrunk 2 s. */
        const val PAUSE_AT = 6_000L
        const val PAUSED_MS = 6_000L

        /** How often the device's place is followed. */
        const val FOLLOW_MS = 200L

        /** How often the service's foreground is looked at in the pocket, and at least how many looks the film takes. */
        const val LOOK_MS = 250L
        const val LOOKS = 20

        /** The case's own limit: it takes about 30 s. */
        const val TIMEOUT_MS = 180_000L

        const val WAKE_TAG = "NZStreamer:cast"
    }
}

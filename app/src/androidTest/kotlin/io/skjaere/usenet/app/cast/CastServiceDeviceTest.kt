package io.skjaere.usenet.app.cast

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.cast.Cast
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PictureInPicturePlays
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastHdr
import io.skjaere.usenet.app.casting.CastIdleReason
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastMetadata
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.CastVideo
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.lostTheStream
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.controllerOf
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.serve.ServeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cast's service (W27, W35, L48, L58, L59, L63, L74, L85, L86; CsPhoneNotification), on a phone, over a fake Cast session
 * and a fake device player that starts as Media3's RemoteCastPlayer does (idle, its timeline empty): Google's software is
 * never started (L73). The device's part of a load is the test's ([FakeLoader]): it serves the test's own bytes and sends
 * the device an item said as the play is; one case runs the real load ([Casting]) over the fake session instead. What it
 * proves: the service goes into the foreground from the tap with the placeholder's words, however long the pipeline
 * takes, and holds it through a pause past Media3's timeout; its session never says a release's or a file's name; it
 * stops as each end says and ends the Cast session only where Stop casting does; its server binds the address there is
 * a second after a bind that failed, listens again as Android takes its listening socket, and stops the cast as the
 * address changes; it holds a wake lock only while it writes; another app's controller gets nothing with no cast; the
 * notification's tap comes back to the activity there is; and the real load serves the release and plays at the
 * device's word. The film, the release and the device are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val film = WatchTarget(WatchKind.Movie, FILM_TITLE, movieTmdbId = FILM)
    private val fixtures = GapFixtures(local, INDEXER, film)
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val events = CastEventHub()

    /** The file the device is served: invented bytes, larger than the sockets hold, so a slow reader keeps the writes going. */
    private val media = ByteArray(16 * 1024 * 1024) { (it % 251).toByte() }
    /** The device's words as the real load says them ([CastMetadata], L89, L120). */
    private val loader = FakeLoader(media, said = { CastMetadata.of(it, container.appName).session(artwork = null, it.cast?.name.orEmpty(), Build.VERSION.SDK_INT) })
    private val launcher: CastLauncher get() = container.castLauncher() ?: throw AssertionError("no cast launcher")
    private val release = SavedRelease(INDEXER, MKV, MKV, null, 0, "cast-service-nzb", FILE_NAME, media.size.toLong())
    private val launch = PlayerLaunch(
        UsenetUris.build(release.nzbId, 0, release.fileName, release.fileSize), FILM_TITLE, null, WatchKey.movie(FILM), film, release, cast = device,
    )
    private var tried: List<TriedRelease>? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        CastTimings.current = CastTimings(foregroundTimeoutMillis = 2_000, wakeLetGoMillis = 2_000)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.loader = { loader }
        CastSeams.events = { events }
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        waitUntil("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        waitUntil("no cast service left over") { !serviceRunning() }
    }

    @After
    fun restore() = runBlocking<Unit> {
        waits.cancel()
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
        waitUntil("the cast service gone") { !serviceRunning() }
    }

    /** The test's own NNTP server under the engine, and a release of the film on it, for a play through the coordinator (L40). */
    private suspend fun onTheTestsServer(): GapFixtures.Release {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        tried = container.library.triedReleaseEntries()
        container.library.clearTriedReleases()
        forgetFilmsRelease()
        return fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "cast-service", MKV) { emptySet() }
    }

    private suspend fun forgetFilmsRelease() {
        val key = WatchKey.movie(FILM)
        container.library.lastRelease(key)?.let { container.library.forgetRelease(key, it) }
    }

    /**
     * A play of [title] (with [subtitle] under it) through the coordinator onto Kitchen display ([to]), as a tap asks for
     * it, its NZB held until [held] completes.
     */
    private fun playHeld(title: String, watch: WatchTarget, nzb: ByteArray, held: CompletableDeferred<Unit>, to: CastTo = device, subtitle: String? = null) {
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { held.await(); nzb } }
        onMainThread {
            container.playback.play(PlayRequest(title, subtitle, listOf(fixtures.candidate(MKV, MKV)), FilePicker.Largest, watch, chosen = true), PlayOrigin(cast = to))
        }
    }

    /**
     * Where the loads' outcomes are waited on, as the coordinator's job waits: never the test's own runBlocking, which would
     * wait for an outcome the test leaves to come.
     */
    private val waits = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A cast play started as the coordinator starts one at its tap, and its load ([launch]) handed over: how it comes out. */
    private fun cast(
        said: SessionMetadata = SessionMetadata.starting(FILM_TITLE, null, film, container.appName),
        launch: PlayerLaunch = this.launch,
    ): Deferred<CastLoadOutcome> {
        assertTrue("the service started", onMainThread { launcher.starting(device, said) })
        val load = launcher.load(launch)
        return waits.async { load.outcome() }
    }

    /** The device's player: the fake session's, built as it started. */
    private lateinit var remote: FakeRemotePlayer

    /** Waits for the [count]th load to be on the device, its item there. */
    private fun loaded(count: Int = 1) {
        waitUntil("load $count on the device") { loader.sent.size >= count && onMainThread { remote.currentMediaItem?.mediaId } == loader.sent[count - 1] }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** Android's record of the cast service, read and never printed: it names the app's process. */
    private fun serviceRecord(): String = instrumentation.uiAutomation.shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}")

    private fun serviceRunning(): Boolean = serviceRecord().let { "ServiceRecord{" in it && "CastService" in it }

    private fun foreground(): Boolean = serviceRecord().let { "CastService" in it && "isForeground=true" in it }

    /** Whether Android lists the service's wake lock as held: read from `dumpsys power`, never printed. */
    private fun wakeLockHeld(): Boolean = instrumentation.uiAutomation.shell("dumpsys power").lines()
        .dropWhile { !it.trim().startsWith("Wake Locks:") }.drop(1).takeWhile { it.startsWith(" ") }
        .any { "'$WAKE_TAG'" in it }

    /** The "cast" session as Android lists it to other apps, the system's media controls and the lock screen. */
    private fun castSession(): MediaController? {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .firstOrNull { it.packageName == context.packageName && it.tag == CAST_TAG }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    /** The app's sessions as Android lists them, by tag. */
    private fun sessionTags(): List<String> {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null).filter { it.packageName == context.packageName }.map { it.tag }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    /** Every word the platform's session says of the play: its metadata's texts, and its queue's. */
    private fun words(session: MediaController): List<String> {
        val metadata = session.metadata
        val said = metadata?.keySet().orEmpty().mapNotNull { key -> runCatching { metadata?.getText(key)?.toString() }.getOrNull() }
        val queued = session.queue.orEmpty().flatMap { listOfNotNull(it.description.title, it.description.subtitle, it.description.description, it.description.mediaId) }
        return said + queued.map { it.toString() }
    }

    private fun title(session: MediaController?): String? = session?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

    /** The line the session says under its title. */
    private fun subtitle(session: MediaController?): String? = session?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

    /** The line Android's media card and lock screen show under the title: the session's artist. */
    private fun artist(session: MediaController?): String? = session?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)

    /** The cast notification, by its Stop casting; null while there is none. */
    private fun castNotification(): android.app.Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }
        .firstOrNull { n -> n.actions.orEmpty().any { it.title?.toString() == STOP_CASTING } }

    /** The notification's title and the line under it. */
    private fun shown(notification: android.app.Notification?): Pair<String?, String?> =
        notification?.extras?.let { it.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() to it.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() } ?: (null to null)

    /** No word of a release's name or a file's in what the session says. */
    private fun assertNoReleaseOrFile(session: MediaController) {
        val leaks = words(session).filter { word -> listOf("Copper.Crown", "1080p", "WEB-DL", "CAST", FILE_NAME, ".mkv", "chapters").any { it in word } }
        assertEquals("the session says a release's or a file's name", 0, leaks.size)
    }

    /** A GET of [url] at the phone's address, as a receiver's: its status, and the socket to read its body from. Never printed. */
    private fun get(url: String): Pair<Int, Socket> {
        val uri = URI(url)
        val socket = Socket(uri.host, uri.port).apply { soTimeout = 10_000 }
        socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nRange: bytes=0-\r\n\r\n".toByteArray())
        val head = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
        }
        val code = head.toString(Charsets.ISO_8859_1.name()).lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        return code to socket
    }

    private fun status(url: String): Int = get(url).let { (code, socket) -> socket.close(); code }

    /** Whether anything listens where [url] points. */
    private fun listening(url: String): Boolean = URI(url).let { uri -> runCatching { Socket(uri.host, uri.port).close() }.isSuccess }

    /** L58, L63, L85: from the tap, the service is in the foreground with the play's words while the pipeline runs, however long. */
    @Test
    fun fromTheTapTheServiceIsInTheForegroundWithThePlaysWordsHoweverLongThePipelineTakes() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        playHeld(FILM_TITLE, film, onTheTestsServer().nzb, held)
        waitUntil("the service in the foreground", 10_000) { foreground() }
        // Past Android's window for a foreground start, with nothing loaded: this process still runs to say so.
        Thread.sleep(15_000)
        assertTrue("in the foreground 15 s on", foreground())
        val starting = castSession() ?: throw AssertionError("no cast session")
        assertEquals(FILM_TITLE, title(starting))
        assertEquals(android.media.session.PlaybackState.STATE_BUFFERING, starting.playbackState?.state)
        assertTrue("the play sheet still on the phone's steps", container.playback.state.value != PlayState.Idle)
        assertTrue("nothing loaded yet", loader.sent.isEmpty())
        held.complete(Unit)
        loaded()
        remote.playing()
        loader.plays()
        waitUntil("the sheet closed") { container.playback.state.value == PlayState.Idle }
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        assertEquals(FILM_TITLE, title(castSession()))
        assertTrue(foreground())
    }

    /**
     * L55, L60, L62, L65, L68: the real load, over the fake session: the release the pipeline prepared is found again and
     * served at the phone's address, its item sent to the device with autoplay, no URI in it; the device asks and is
     * answered; the play sheet waits on "Starting on" until the device says it plays the load, and its feed follows it.
     */
    @Test
    fun theRealLoadServesTheReleaseAndPlaysAtTheDevicesWord() = runBlocking<Unit> {
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        playHeld(FILM_TITLE, film, onTheTestsServer().nzb, CompletableDeferred(Unit))
        waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
        val item = onMainThread { remote.currentMediaItem } ?: throw AssertionError("no item")
        assertTrue("sent with autoplay (L68)", onMainThread { remote.playWhenReady })
        assertEquals("one LOAD", 1, remote.loads.size)
        assertNull("no address in the session's item (L62)", item.localConfiguration)
        assertEquals(FILM_TITLE, item.mediaMetadata.title?.toString())
        val url = onMainThread { CastPlays.now?.served?.singleOrNull()?.url } ?: throw AssertionError("nothing served")
        assertEquals("the device's first request answered", 206, status(url))
        assertTrue("still starting on the device", (container.playback.state.value as? PlayState.Running)?.castStep != null)
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, item.mediaId, 0, SystemClock.elapsedRealtime())) }
        waitUntil("the sheet closed") { container.playback.state.value == PlayState.Idle }
        val feed = Casting.feed.value ?: throw AssertionError("no feed")
        assertEquals(item.mediaId, feed.load)
        assertEquals(CastPlayerState.Playing, feed.state)
        assertEquals(FILM_TITLE, feed.launch.title)
    }

    /**
     * L128, L131: what the device shows of a play of ours is kept for it, by its Cast id, through the real load: a status
     * with a 3840 × 2160 picture of the test's film (H.264) says it reports pictures and has shown 4K H.264. The settings
     * are put back after.
     */
    @Test
    fun aPictureTheDeviceShowsOfOurLoadIsKeptForIt() = runBlocking<Unit> {
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        val kept = container.settings.cast.first().devices
        assertNull("nothing kept of the fake device before", kept.firstOrNull { it.id == kitchen.deviceId })
        try {
            playHeld(FILM_TITLE, film, onTheTestsServer().nzb, CompletableDeferred(Unit), to = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model))
            waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
            val id = onMainThread { remote.currentMediaItem?.mediaId } ?: throw AssertionError("no item")
            onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, 0, SystemClock.elapsedRealtime(), CastVideo(3840, 2160, CastHdr.Sdr))) }
            waitUntil("the device's picture kept") { runBlocking { container.settings.cast.first() }.devices.any { it.id == kitchen.deviceId } }
            val memory = container.settings.cast.first().devices.single { it.id == kitchen.deviceId }
            assertTrue("it reports pictures", memory.reportsVideo)
            assertEquals("it has shown 4K H.264", listOf(CastVideoCodec("H264")), memory.shown2160)
            assertEquals(listOf(false, false), listOf(memory.hdrSdr, memory.dolbyVision))
            assertEquals(emptyList<CastVideoCodec>(), memory.noPicture)
        } finally {
            container.settings.updateCast { it.copy(devices = kept) }
        }
        assertNull("put back", container.settings.cast.first().devices.firstOrNull { it.id == kitchen.deviceId })
    }

    /**
     * W34, L88: on a Wi-Fi where the phone has no private address (or none the server can listen at), the real load never
     * gets a server to serve from: it fails as the device can't reach this phone once the device's own wait is over,
     * rather than leave the sheet on "Starting on Kitchen display".
     */
    @Test
    fun withNowhereToListenTheRealLoadFailsAsTheDeviceCantReachThisPhone() = runBlocking<Unit> {
        CastTimings.current = CastTimings.current.copy(noRequestMillis = 3_000)
        CastSeams.addresses = { MutableStateFlow<Inet4Address?>(null) }
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        playHeld(FILM_TITLE, film, onTheTestsServer().nzb, CompletableDeferred(Unit))
        waitUntil("the play failed", 30_000) { container.playback.state.value is PlayState.Failed }
        val failed = container.playback.state.value as PlayState.Failed
        assertEquals(CastFailKind.CannotReach, failed.cast?.kind)
        assertTrue("nothing sent to the device", remote.loads.isEmpty())
        waitUntil("the service stopped") { !serviceRunning() }
        assertEquals("the Cast session lives on", 0, connector.ends.get())
    }

    /**
     * L74: a bind that fails (the address said isn't the phone's) is tried again a second later at the address there is
     * then: the phone's own, said within that second, is bound, and the device's request answered.
     */
    @Test
    fun anAddressSaidWithinTheSecondAfterAFailedBindIsBoundAndServed() = runBlocking<Unit> {
        CastTimings.current = CastTimings.current.copy(noRequestMillis = 10_000)
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        val addresses = MutableStateFlow<Inet4Address?>(NOWHERE)
        val heard = CompletableDeferred<Unit>()
        CastSeams.addresses = { addresses.onEach { if (it == NOWHERE) heard.complete(Unit) } }
        val outcome = cast()
        withTimeout(10_000) { heard.await() }
        // The bind there fails at once: this is well within the second after it.
        Thread.sleep(200)
        addresses.value = home
        assertServed(outcome)
    }

    /**
     * L74: the phone's address said as a bind elsewhere runs, which the flow never says again: that bind failing, the
     * address there is now is bound a second later, and the device's request answered.
     */
    @Test
    fun anAddressSaidAsAFailedBindRanIsBoundASecondLater() = runBlocking<Unit> {
        CastTimings.current = CastTimings.current.copy(noRequestMillis = 10_000)
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        // One after the other: the first still binding as the second is said.
        CastSeams.addresses = {
            flow {
                emit(NOWHERE)
                emit(home)
                awaitCancellation()
            }
        }
        assertServed(cast())
    }

    /** The load whose [outcome] this is was served, in the device's wait, and the device's request is answered. */
    private fun assertServed(outcome: Deferred<CastLoadOutcome>) {
        waitUntil("the load served or failed", 15_000) { loader.served.isNotEmpty() || outcome.isCompleted }
        assertFalse("the load failed: nothing listened at the phone's address", outcome.isCompleted)
        loaded()
        assertEquals("the device's address answers", 206, status(loader.served.single().url))
    }

    /**
     * L61: a cast paused at 32:10 is saved there as a play here would be, through the real load's progress: Resume reads
     * 32:10 of the device's 52 minutes, not watched.
     */
    @Test
    fun aCastPausedAt3210IsSavedThereAndResumeReadsIt() = runBlocking<Unit> {
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        val key = WatchKey.movie(FILM)
        container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        try {
            playHeld(FILM_TITLE, film, onTheTestsServer().nzb, CompletableDeferred(Unit))
            waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
            val id = onMainThread { remote.currentMediaItem?.mediaId } ?: throw AssertionError("no item")
            remote.playing()
            onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, 0, SystemClock.elapsedRealtime())) }
            waitUntil("the sheet closed") { container.playback.state.value == PlayState.Idle }
            // The device's own length, polled each second; the probe's (the file's own) stands in only before it.
            waitUntil("the device's length followed") { Casting.feed.value?.durationMs == 3_120_000L }
            // Paused there from the remote: the device's player says so.
            onMainThread {
                remote.seekTo(PAUSED_AT)
                remote.pause()
                events.tell(CastEvent.Status(CastPlayerState.Paused, null, id, PAUSED_AT, SystemClock.elapsedRealtime()))
            }
            var saved = container.library.state(key).first()
            val until = SystemClock.uptimeMillis() + 20_000
            while (saved?.positionMs != PAUSED_AT) {
                if (SystemClock.uptimeMillis() > until) throw AssertionError("not saved at 32:10: at ${saved?.positionMs} of ${saved?.durationMs}, watched ${saved?.watched}")
                Thread.sleep(50)
                saved = container.library.state(key).first()
            }
            assertFalse("not watched", saved.watched)
            assertEquals("the device's length", 3_120_000L, saved.durationMs)
            assertEquals("the release that played remembered", MKV, saved.release?.title)
        } finally {
            container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        }
    }

    /** L63, M12: a Search play without TMDB says the app's name alone, before its load and after, never the release's. */
    @Test
    fun aSearchPlayWithoutTmdbIsSaidAsTheAppAloneBeforeAndAfterItsLoad() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        playHeld(MKV, WatchTarget(WatchKind.Release, MKV), onTheTestsServer().nzb, held)
        waitUntil("the cast session") { castSession() != null }
        val before = castSession()!!
        assertEquals(container.appName, title(before))
        assertNoReleaseOrFile(before)
        held.complete(Unit)
        loaded()
        remote.playing()
        loader.plays()
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val after = castSession()!!
        assertEquals(container.appName, title(after))
        assertNoReleaseOrFile(after)
    }

    /**
     * L63, L86, L89, L120 (CsPhoneNotification, CsPhoneNotificationC2): an episode is said by its show over its episode's
     * line, which names the device, from the tap, as the device's item says it once the device has it: the words never
     * change places at the hand-over.
     */
    @Test
    fun anEpisodeIsSaidByItsShowOverItsLineBeforeAndAfterItsLoad() = runBlocking<Unit> {
        val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
        val episodeLaunch = launch.copy(title = SHOW_TITLE, subtitle = EPISODE_LINE, watchKey = WatchKey.episode(SHOW, 2, 4), watch = episode)
        cast(SessionMetadata.starting(SHOW_TITLE, EPISODE_LINE, episode, container.appName), episodeLaunch)
        waitUntil("the cast session") { castSession() != null }
        val before = castSession()!!
        assertEquals("the title while it starts", SHOW_TITLE, title(before))
        assertEquals("the line while it starts", EPISODE_ON_KITCHEN, subtitle(before))
        assertEquals("the media card's line while it starts", EPISODE_ON_KITCHEN, artist(before))
        waitUntil("the notification while it starts") { castNotification() != null }
        assertEquals("the notification while it starts", SHOW_TITLE to EPISODE_ON_KITCHEN, shown(castNotification()))
        loaded()
        remote.playing()
        loader.plays()
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val after = castSession()!!
        assertEquals("the title once the device has it", SHOW_TITLE, title(after))
        assertEquals("the line once the device has it", EPISODE_ON_KITCHEN, subtitle(after))
        assertEquals("the media card's line once the device has it", EPISODE_ON_KITCHEN, artist(after))
        // The notification is made again for the device's item: its Pause shows once the device plays it.
        waitUntil("the notification for the device's item") { castNotification()?.actions.orEmpty().any { it.title?.toString() == "Pause" } }
        assertEquals("the notification once the device has it", SHOW_TITLE to EPISODE_ON_KITCHEN, shown(castNotification()))
    }

    /**
     * W62, L120 (CsPhoneNotificationC2): the line under the title names the device the play is cast to, the play's own
     * CastTo.name, from the tap (the placeholder's) and once the device has it (the real load's item, which Media3 hands
     * back to the cast session), on this phone (Android 11 or later) after the line: what Android's notification, media card
     * and lock screen show there. One UI showed "Unknown" there for a play without TMDB (M1).
     */
    @Test
    fun theLineUnderTheTitleNamesTheDeviceFromTheTapAndOnceTheRealLoadIsThere() = runBlocking<Unit> {
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        val held = CompletableDeferred<Unit>()
        playHeld(FILM_TITLE, film, onTheTestsServer().nzb, held, subtitle = FILM_LINE)
        waitUntil("the cast session") { castSession() != null }
        val before = castSession()!!
        assertEquals("the title while it starts", FILM_TITLE, title(before))
        assertEquals("the media card's line while it starts", FILM_ON_KITCHEN, artist(before))
        assertEquals("the line while it starts", FILM_ON_KITCHEN, subtitle(before))
        waitUntil("the notification while it starts") { castNotification() != null }
        assertEquals("the notification while it starts", FILM_TITLE to FILM_ON_KITCHEN, shown(castNotification()))
        held.complete(Unit)
        waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
        val item = onMainThread { remote.currentMediaItem } ?: throw AssertionError("no item")
        assertEquals("the real load's line", FILM_ON_KITCHEN, item.mediaMetadata.artist?.toString())
        remote.playing()
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, item.mediaId, 0, SystemClock.elapsedRealtime())) }
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val after = castSession()!!
        assertEquals("the title once the device has it", FILM_TITLE, title(after))
        assertEquals("the media card's line once the device has it", FILM_ON_KITCHEN, artist(after))
        assertEquals("the line once the device has it", FILM_ON_KITCHEN, subtitle(after))
        waitUntil("the notification for the device's item") { castNotification()?.actions.orEmpty().any { it.title?.toString() == "Pause" } }
        assertEquals("the notification once the device has it", FILM_TITLE to FILM_ON_KITCHEN, shown(castNotification()))
    }

    /** L82, L85: a play that ends before its load, by Cancel or a failure, stops the service; the Cast session and the choice live on. */
    @Test
    fun aPlayEndingBeforeItsLoadStopsTheServiceAndKeepsTheSession() = runBlocking<Unit> {
        val nzb = onTheTestsServer().nzb
        val held = CompletableDeferred<Unit>()
        playHeld(FILM_TITLE, film, nzb, held)
        waitUntil("the service in the foreground", 10_000) { foreground() }
        onMainThread { container.playback.cancel() }
        waitUntil("the service stopped by Cancel") { !serviceRunning() }
        assertEquals("the Cast session isn't ended", 0, connector.ends.get())
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        held.complete(Unit)
        // A failure: no release could be fetched.
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { throw FetchException("HTTP 404") } }
        onMainThread {
            container.playback.play(PlayRequest(FILM_TITLE, null, listOf(fixtures.candidate(MKV, MKV)), FilePicker.Largest, film, chosen = true), PlayOrigin(cast = device))
        }
        waitUntil("the play failed") { container.playback.state.value is PlayState.Failed }
        waitUntil("the service stopped by the failure") { !serviceRunning() }
        assertEquals(0, connector.ends.get())
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        assertTrue("nothing loaded", loader.sent.isEmpty())
    }

    /**
     * L85: a cast play asked for as its session goes (Try again on a sheet left open, a tap as the Wi-Fi drops) ends as its
     * service is made: the service builds nothing for it and stops, leaving no notification, and the load hears the session
     * went.
     */
    @Test
    fun aCastPlayWhoseSessionWentAsItStartedLeavesNoServiceBehind() = runBlocking<Unit> {
        connector.ended(kitchen)
        waitUntil("the session gone") { GoogleCast.connection.now.value == null }
        val outcome = cast()
        waitUntil("no cast service left") { !serviceRunning() }
        assertNull("no cast notification", castNotification())
        val failed = withTimeout(10_000) { outcome.await() } as? CastLoadOutcome.Failed ?: throw AssertionError("the load didn't fail")
        assertTrue("the session went with it", failed.failure.sessionEnded)
        assertNull("no cast play left", onMainThread { CastPlays.now })
    }

    /**
     * L85: This phone chosen on the sheet while a cast play is still on the phone's steps ends it as Stop casting does: the
     * play is cancelled, its sheet closes, no NZB is grabbed for it, nothing says the device can't reach this phone, and
     * the Cast session ends.
     */
    @Test
    fun thisPhoneChosenWhileACastStartsCancelsItAndGrabsNothing() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        playHeld(FILM_TITLE, film, onTheTestsServer().nzb, held)
        waitUntil("the service in the foreground", 10_000) { foreground() }
        // As the sheet's This phone row chooses it while the cast's sheet waits on the release.
        onMainThread { container.playOn.choose(PlayChoice.Phone) }
        waitUntil("the cast's sheet closed") { container.playback.state.value == PlayState.Idle }
        held.complete(Unit)
        Thread.sleep(3_000)
        assertEquals("nothing more of the play: no failure", PlayState.Idle, container.playback.state.value)
        assertTrue("nothing loaded", loader.launches.isEmpty())
        waitUntil("the service stopped") { !serviceRunning() }
        assertEquals("the Cast session ended", 1, connector.ends.get())
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
    }

    /** L63, L86, W35: after its load the session says the play and offers Stop casting, which ends the cast and the service. */
    @Test
    fun afterItsLoadTheSessionSaysThePlayAndItsStopCastingEndsTheCast() = runBlocking<Unit> {
        cast()
        loaded()
        remote.playing()
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val session = castSession()!!
        assertEquals(FILM_TITLE, title(session))
        assertEquals("the load's own id, never the file's", loader.sent.single(), session.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertNoReleaseOrFile(session)
        val stop = session.playbackState?.customActions.orEmpty().singleOrNull { it.action == CastService.STOP_ACTION }
        assertNotNull("Stop casting offered", stop)
        assertEquals(STOP_CASTING, stop!!.name.toString())
        // The notification's: play or pause and Stop casting, never next, previous or ±10 s.
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }
            .firstOrNull { n -> n.actions.orEmpty().any { it.title?.toString() == STOP_CASTING } } ?: throw AssertionError("no cast notification")
        assertEquals(listOf("Pause", STOP_CASTING), notification.actions.map { it.title.toString() })
        session.transportControls.sendCustomAction(CastService.STOP_ACTION, null)
        waitUntil("the service stopped") { !serviceRunning() }
        assertEquals("the Cast session ended", 1, connector.ends.get())
        waitUntil("This phone chosen again") { container.playOn.chosen.value == PlayChoice.Phone }
    }

    /** K1, U3, L58: paused past Media3's foreground timeout, it stays in the foreground, also as the device's item takes a new still. */
    @Test
    fun pausedPastMediasTimeoutItStaysInTheForegroundAlsoWithANewStill() = runBlocking<Unit> {
        loader.artwork = still(Color.DKGRAY)
        cast()
        loaded()
        remote.playing()
        waitUntil("in the foreground", 10_000) { foreground() }
        onMainThread { remote.pause() }
        // Media3's timeout is 2 s here: well past it.
        Thread.sleep(6_000)
        assertTrue("in the foreground 6 s into a pause", foreground())
        val again = still(Color.GRAY)
        loader.serving!!.artwork(again)
        remote.changed(CastMetadata.of(launch, container.appName).session(again, device.name, Build.VERSION.SDK_INT))
        // Looked at all along: the notification's late callback for a still not ready would let the foreground go for a moment.
        val until = SystemClock.uptimeMillis() + 4_000
        while (SystemClock.uptimeMillis() < until) assertTrue("in the foreground as the new still comes, paused", foreground())
        assertTrue("still serving", serviceRunning())
    }

    /**
     * L58, L74, L91: once the device played it, its player has nothing for a moment (a session resumed with a first
     * status that has no media): the session keeps the play's item, so its notification stays and the service holds the
     * foreground, which Android wouldn't let it take back from the background; then the device's own state again.
     */
    @Test
    fun theDevicesPlayerEmptyForAMomentKeepsThePlayInTheSessionAndTheForeground() = runBlocking<Unit> {
        cast()
        loaded()
        remote.playing()
        loader.plays()
        waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        waitUntil("in the foreground", 10_000) { foreground() }
        remote.emptied()
        // Looked at all along: with nothing in the session Media3 would take its notification and the foreground down at once.
        val until = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < until) assertTrue("in the foreground with the device's player empty", foreground())
        assertNotNull("the notification stays", castNotification())
        assertEquals("the play still said", FILM_TITLE, title(castSession()))
        remote.back()
        waitUntil("the device's own state again") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        assertTrue(foreground())
    }

    /**
     * L58, L91 (CSS-2): 40 minutes on, with no event from the device's player (Media3's sends none for steady progress),
     * its player has nothing for a moment: the session keeps the play where the device was, from the service's poll of the
     * play as the phone follows it, never where the device's last event left it.
     */
    @Test
    fun theDevicesPlayerEmptyFortyMinutesInKeepsThePlayWhereTheDeviceWas() = runBlocking<Unit> {
        CastSeams.loader = { Casting.with(it) }
        Casting.artOf = { _, _ -> null }
        container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        try {
            playHeld(FILM_TITLE, film, onTheTestsServer().nzb, CompletableDeferred(Unit))
            waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
            val id = onMainThread { remote.currentMediaItem?.mediaId } ?: throw AssertionError("no item")
            remote.playing()
            onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, 0, SystemClock.elapsedRealtime())) }
            waitUntil("the sheet closed") { container.playback.state.value == PlayState.Idle }
            waitUntil("the device's item in the session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            remote.progressed(FORTY_MINUTES)
            waitUntil("the play followed there") { Casting.feed.value?.positionMs == FORTY_MINUTES }
            // The service's poll, each second, takes the session's play there too.
            waitUntil("the session's play followed there") { onMainThread { CastPlays.running?.sessionPlayer?.keptAt } == FORTY_MINUTES }
            remote.emptied()
            waitUntil("the play kept, waiting") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_BUFFERING }
            assertEquals("where the device was", FORTY_MINUTES, castSession()?.playbackState?.position)
        } finally {
            container.library.markMovies(listOf(MovieMark(FILM, FILM_TITLE)), watched = false)
        }
    }

    /**
     * L58, K1: the cast session's stills are its own artwork's: the play's, prepared before its item came, is there at once
     * through the session's loader, and none is loaded from an address.
     */
    @Test
    fun theSessionsStillIsReadyAtOnceAndNoneIsLoadedFromAnAddress() = runBlocking<Unit> {
        val play = still(Color.DKGRAY)
        loader.artwork = play
        cast()
        loaded()
        val session = onMainThread { CastPlays.running?.sessions?.singleOrNull() } ?: throw AssertionError("no cast session")
        assertTrue("the play's still ready at once", onMainThread { session.bitmapLoader.decodeBitmap(play).isDone })
        val fromAddress = onMainThread { session.bitmapLoader.loadBitmap(Uri.parse("https://image.tmdb.org/t/p/w300/still-cc.jpg")) }
        assertTrue("refused at once", fromAddress.isDone)
        assertTrue("never loaded", runCatching { fromAddress.get() }.isFailure)
    }

    /** L63: the cast session lives beside a player's, each its own id. */
    @Test
    fun itLivesBesideAPlayersSession() = runBlocking<Unit> {
        val plays = PictureInPicturePlays(INDEXER)
        try {
            plays.setUp()
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("cast-beside"))).use { scenario ->
                plays.playing(scenario)
                cast()
                loaded()
                waitUntil("both sessions") { sessionTags().size == 2 }
                val tags = sessionTags()
                assertTrue("the cast's", CAST_TAG in tags)
                assertEquals("two ids", 2, tags.toSet().size)
                assertTrue("the player still plays", scenario.read { it.player?.isPlaying == true })
                plays.finish(scenario)
            }
        } finally {
            plays.close()
            plays.forget()
        }
    }

    /** L58: swiped away while paused, the cast stops as Stop casting does; while it starts or plays, it goes on. */
    @Test
    fun aSwipeAwayStopsAPausedCastAndNotOneStartingOrPlaying() = runBlocking<Unit> {
        cast()
        loaded()
        // Loading: the device's player buffers what it was sent, meant to play.
        swipeAway()
        Thread.sleep(1_000)
        assertTrue("goes on while loading", serviceRunning())
        remote.playing()
        swipeAway()
        Thread.sleep(1_000)
        assertTrue("goes on while playing", serviceRunning())
        assertEquals(0, connector.ends.get())
        onMainThread { remote.pause() }
        swipeAway()
        waitUntil("stopped while paused") { !serviceRunning() }
        assertEquals("the Cast session ended", 1, connector.ends.get())
    }

    /** The app's task taken from Recents, as a swipe does: Android tells the service. */
    private fun swipeAway() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil("the app in front") { scenario.state == Lifecycle.State.RESUMED }
            scenario.onActivity { it.finishAndRemoveTask() }
            waitUntil("the task gone") { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    /** L74: the address gone a moment and back, the cast goes on; another address, it stops, the Cast session with it. */
    @Test
    fun theAddressGoneAMomentKeepsTheCastAndAnotherAddressStopsIt() = runBlocking<Unit> {
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        val addresses = MutableStateFlow<Inet4Address?>(null)
        CastSeams.addresses = { addresses }
        cast()
        addresses.value = home
        loaded()
        remote.playing()
        val url = loader.served.single().url
        assertEquals(206, status(url))
        addresses.value = null
        Thread.sleep(5_000)
        addresses.value = home
        Thread.sleep(500)
        assertTrue("still casting", serviceRunning())
        assertEquals("still served", 206, status(url))
        assertEquals(0, connector.ends.get())
        addresses.value = InetAddress.getByAddress(byteArrayOf(10, 0, 2, 99)) as Inet4Address
        waitUntil("stopped for another address") { !serviceRunning() }
        assertEquals("the Cast session ended", 1, connector.ends.get())
        assertFalse("the server gone", listening(url))
    }

    /**
     * L74, L88: Android takes the listening socket away (a Wi-Fi blip the address flow never shows): the server listens again
     * at once, on the same port, and the device's address answers. Taken with the address gone and the port taken meanwhile,
     * it can't, and the play ends with the lost stream's words.
     */
    @Test
    fun aListeningSocketTakenAwayListensAgainOrEndsThePlayWithTheLostStream() = runBlocking<Unit> {
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        val addresses = MutableStateFlow<Inet4Address?>(home)
        CastSeams.addresses = { addresses }
        val outcome = cast()
        loaded()
        val url = loader.served.single().url
        val server = onMainThread { CastPlays.running?.server } ?: throw AssertionError("no server")
        val first = listener(server)
        first.close()
        waitUntil("listening again") { runCatching { listener(server) }.getOrNull()?.let { it !== first && !it.isClosed } == true }
        assertEquals(URI(url).port, listener(server).localPort)
        assertEquals("the device's address answers again", 206, status(url))
        // Lost with the address, and the port taken before it comes back: listening again fails.
        addresses.value = null
        Thread.sleep(300)
        val port = listener(server).localPort
        listener(server).close()
        waitUntil("the listener gone") { runCatching { listener(server) }.isFailure }
        ServerSocket().use { taken ->
            taken.reuseAddress = true
            taken.bind(InetSocketAddress(home, port))
            addresses.value = home
            val failed = withTimeout(10_000) { outcome.await() } as? CastLoadOutcome.Failed ?: throw AssertionError("the play didn't fail")
            assertEquals(CastFailKind.CouldNotPlay, failed.failure.kind)
            assertEquals(lostTheStream(kitchen.name), failed.failure.reason)
        }
        waitUntil("the service stopped") { !serviceRunning() }
        assertEquals("the Cast session lives on", 0, connector.ends.get())
    }

    /**
     * L74: Android takes the listening socket with the address at once and tells the app of the address later, so listening
     * again at once fails there (its port taken meanwhile, here): no lost stream yet, the play goes on. Once the address is
     * back on the phone, the server listens again on its port, and the device's address answers.
     */
    @Test
    fun aListeningSocketLostWithTheAddressBeforeTheFlowSaysSoListensAgainAsItComesBack() = runBlocking<Unit> {
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        CastSeams.addresses = { MutableStateFlow<Inet4Address?>(home) }
        val here = AtomicBoolean(true)
        CastSeams.present = { here.get() }
        val outcome = cast()
        loaded()
        val url = loader.served.single().url
        val server = onMainThread { CastPlays.running?.server } ?: throw AssertionError("no server")
        val port = listener(server).localPort
        ServerSocket().use { taken ->
            taken.reuseAddress = true
            // The address goes, its sockets with it; the flow still says it's there. Taken before the server hears of its
            // loss, which comes after this on the main thread.
            here.set(false)
            onMainThread {
                listener(server).close()
                // The port is the accept thread's until its accept has returned.
                waitUntil("the listener gone") { runCatching { listener(server) }.isFailure }
                taken.bind(InetSocketAddress(home, port))
            }
            Thread.sleep(2_000)
            assertFalse("no lost stream while the address is gone", outcome.isCompleted)
        }
        here.set(true)
        waitUntil("listening again") { runCatching { listener(server) }.getOrNull()?.let { !it.isClosed } == true }
        assertEquals(port, listener(server).localPort)
        assertEquals("the device's address answers again", 206, status(url))
        assertFalse("the play goes on", outcome.isCompleted)
        assertTrue(serviceRunning())
    }

    /**
     * L74, L88: listening again fails, its port taken as the address went, and the address is back by the time the phone
     * looks for it: the server tries once more, listens on its port again, and the play goes on with no lost stream.
     */
    @Test
    fun aRelistenThatFailedAsTheAddressCameBackIsTriedOnceMore() = runBlocking<Unit> {
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        CastSeams.addresses = { MutableStateFlow<Inet4Address?>(home) }
        val taken = ServerSocket().apply { reuseAddress = true }
        // The address back just as the phone looks: its port free again.
        CastSeams.present = {
            taken.close()
            true
        }
        try {
            val outcome = cast()
            loaded()
            val url = loader.served.single().url
            val server = onMainThread { CastPlays.running?.server } ?: throw AssertionError("no server")
            val port = listener(server).localPort
            // Taken before the server hears of its loss, which comes after this on the main thread.
            onMainThread {
                listener(server).close()
                // The port is the accept thread's until its accept has returned.
                waitUntil("the listener gone") { runCatching { listener(server) }.isFailure }
                taken.bind(InetSocketAddress(home, port))
            }
            waitUntil("listening again") { runCatching { listener(server) }.getOrNull()?.let { !it.isClosed && it.localPort == port } == true }
            assertTrue("the port was taken as it listened again", taken.isClosed)
            assertEquals("the device's address answers again", 206, status(url))
            Thread.sleep(1_000)
            assertFalse("no lost stream", outcome.isCompleted)
            assertTrue(serviceRunning())
        } finally {
            taken.close()
        }
    }

    /**
     * L74, L85: Android takes the listening socket away and the cast stops while the server's listening again is still on
     * its way: it ends with the run, the server it asks closed meanwhile, and nothing is thrown at the app for it. The
     * server's own lock, held here, keeps the relisten waiting until the run has closed.
     */
    @Test
    fun aRelistenOnItsWayAsTheCastStopsEndsWithIt() = runBlocking<Unit> {
        val home = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        CastSeams.addresses = { MutableStateFlow<Inet4Address?>(home) }
        val thrown = CopyOnWriteArrayList<Throwable>()
        val main = Looper.getMainLooper().thread
        val before = Thread.getDefaultUncaughtExceptionHandler()
        // What would take the app down: the run's scope hands a failed coroutine's throw to the main thread's handler.
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> if (thread === main) thrown += e else before?.uncaughtException(thread, e) }
        try {
            cast()
            loaded()
            val server = onMainThread { CastPlays.running?.server } ?: throw AssertionError("no server")
            synchronized(listenLock(server)) {
                listener(server).close()
                waitUntil("a relisten on its way") { relistenWaiting() }
                onMainThread { CastPlays.stopCasting() }
            }
            waitUntil("the service stopped") { !serviceRunning() }
            // The relisten, let go, has met the closed server by now.
            waitUntil("the relisten done") { !relistenWaiting() }
            Thread.sleep(1_000)
            assertEquals("nothing thrown as the run closed", emptyList<String>(), thrown.map { "${it.javaClass.simpleName}: ${it.message}" })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    /** The server's lock its listening again holds while it runs: no API gives it, so it is read as the server keeps it. */
    private fun listenLock(server: ServeServer): Any =
        ServeServer::class.java.getDeclaredField("listenLock").apply { isAccessible = true }.get(server) ?: throw IllegalStateException("no lock")

    /** Whether a thread waits to listen again on a server, for its lock. */
    private fun relistenWaiting(): Boolean = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.state == Thread.State.BLOCKED && stack.any { it.className == ServeServer::class.java.name && it.methodName == "relisten" }
    }

    /** The server's listening socket, as Android would take it away: no API gives it, so it is read as the server keeps it. */
    private fun listener(server: ServeServer): ServerSocket =
        ServeServer::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(server) as? ServerSocket ?: throw IllegalStateException("not listening")

    /**
     * L55, L85, through the session events' seam: a play the device finished is revoked at once, its address answering 404
     * while the service runs on for the next; the server stops listening as the service stops. The session's end and
     * another sender's load stop the service and end nothing of the session's or the device's.
     */
    @Test
    fun theDevicesEventsEndItsPlayAndOnlyStopCastingEndsTheSession() = runBlocking<Unit> {
        cast()
        loaded()
        remote.playing()
        loader.plays()
        val finished = loader.served.single().url
        assertEquals(206, status(finished))
        // The next play starts while the first still plays there (L84), another replaces it before its load, and the first
        // ends there before the last one's load.
        repeat(2) { assertTrue(onMainThread { launcher.starting(device, SessionMetadata.starting(FILM_TITLE, null, film, container.appName)) }) }
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Finished, loader.sent[0], 0, SystemClock.elapsedRealtime())) }
        assertEquals("the finished play's address revoked", 404, status(finished))
        assertTrue("the service runs on for the next", serviceRunning())
        val load = launcher.load(launch)
        waits.async { load.outcome() }
        loaded(2)
        val next = loader.served[1].url
        assertEquals(206, status(next))
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Finished, loader.sent[1], 0, SystemClock.elapsedRealtime())) }
        waitUntil("the service stopped") { !serviceRunning() }
        assertFalse("the server stopped listening", listening(next))
        assertEquals(0, connector.ends.get())
        assertEquals(0, remote.stops.get())
        // The session's end.
        cast()
        loaded(3)
        onMainThread { events.tell(CastEvent.Ended) }
        waitUntil("the service stopped at the session's end") { !serviceRunning() }
        // Another sender's load replaced this one's.
        cast()
        loaded(4)
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Interrupted, loader.sent[3], 0, SystemClock.elapsedRealtime())) }
        waitUntil("the service stopped for another sender") { !serviceRunning() }
        assertEquals("nothing of the session ended", 0, connector.ends.get())
        assertEquals("nothing of the device's stopped", 0, remote.stops.get())
    }

    /**
     * L84, W32: another sender's load interrupts the play on the device while the next one is still on the phone's steps:
     * only the play interrupted ends; the next goes on, its load takes the device back, and it plays.
     */
    @Test
    fun anotherSendersLoadEndsOnlyThePlayItInterrupted() = runBlocking<Unit> {
        cast()
        loaded()
        remote.playing()
        loader.plays()
        assertTrue(onMainThread { launcher.starting(device, SessionMetadata.starting(FILM_TITLE, null, film, container.appName)) })
        val next = onMainThread { CastPlays.now } ?: throw AssertionError("no next play")
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Interrupted, loader.sent[0], 0, SystemClock.elapsedRealtime())) }
        assertFalse("the next play goes on", onMainThread { next.ended })
        assertTrue(serviceRunning())
        assertEquals("the first play's address revoked", 404, status(loader.served[0].url))
        val load = launcher.load(launch)
        val outcome = waits.async { load.outcome() }
        loaded(2)
        loader.plays()
        assertEquals(CastLoadOutcome.Playing, withTimeout(10_000) { outcome.await() })
        assertEquals("nothing of the session ended", 0, connector.ends.get())
    }

    /**
     * L84: a new cast play asked for while the one before is still loading on the device (sent, not yet playing) gives
     * that one up: its media stopped there and its address revoked, so nothing is left playing there unfollowed when the
     * new one ends before its own load.
     */
    @Test
    fun aNewCastPlayGivesUpOneStillLoadingThere() = runBlocking<Unit> {
        cast()
        loaded()
        assertTrue(onMainThread { launcher.starting(device, SessionMetadata.starting(FILM_TITLE, null, film, container.appName)) })
        assertEquals("what the first loaded stopped there", 1, remote.stops.get())
        assertEquals("its address revoked", 404, status(loader.served[0].url))
        // The new one ends before its load: nothing is left there, and the service stops.
        launcher.stop()
        waitUntil("the service stopped") { !serviceRunning() }
        assertEquals("the Cast session lives on", 0, connector.ends.get())
    }

    /** L59: the wake lock is held only while the server writes to a reader, and let go after. */
    @Test
    fun theWakeLockIsHeldOnlyWhileAStandInReads() = runBlocking<Unit> {
        cast()
        loaded()
        remote.playing()
        Thread.sleep(3_000)
        assertFalse("no wake lock before a request", wakeLockHeld())
        val (code, socket) = get(loader.served.single().url)
        assertEquals(206, code)
        var held = false
        socket.use {
            val input = it.getInputStream()
            val chunk = ByteArray(32 * 1024)
            val until = SystemClock.uptimeMillis() + 4_000
            while (SystemClock.uptimeMillis() < until) {
                input.read(chunk)
                held = held || wakeLockHeld()
                Thread.sleep(50)
            }
        }
        assertTrue("held while the stand-in reads", held)
        // Let go 2 s (shrunk) after the last write.
        waitUntil("let go after the reads", 10_000) { !wakeLockHeld() }
        assertTrue(serviceRunning())
    }

    /** L85: with no cast play, another app's controller is refused, nothing starts, and Google's software stays unstarted. */
    @Test
    fun anotherAppsControllerWithNoCastIsRefused() = runBlocking<Unit> {
        val token = SessionToken(context, ComponentName(context, CastService::class.java))
        val refused = try {
            controllerOf(token).release()
            false
        } catch (e: ExecutionException) {
            true
        } catch (e: TimeoutException) {
            throw AssertionError("the controller neither connected nor was refused", e)
        }
        assertTrue("refused", refused)
        waitUntil("the service not running") { !serviceRunning() }
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    /**
     * L85: another app starts the exported service for the foreground with no cast play, with no action or one of its own:
     * there is nothing to show, so the service passes through the foreground and stops, and Android leaves this process be
     * (a service started for the foreground that stops without getting there takes its app down).
     */
    @Test
    fun anotherAppsStartForTheForegroundWithNoCastStopsItAndTheAppLivesOn() = runBlocking<Unit> {
        listOf(null, "com.example.other.PLAY").forEach { action ->
            context.startForegroundService(Intent().setComponent(ComponentName(context, CastService::class.java)).setAction(action))
            waitUntil("the service gone after a start with ${action ?: "no action"}") { !serviceRunning() }
            // Android takes an app down within moments of such a stop: this process still runs to say it didn't.
            Thread.sleep(3_000)
            assertFalse("not started again", serviceRunning())
        }
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    /**
     * L86: a tap on the notification, with the app open from the launcher, comes back to the activity there is (a second one
     * would choose This phone again, W18) and opens the remote there, as the remote has the play to follow (L91).
     */
    @Test
    fun aTapOnTheNotificationOpensTheRemoteInTheActivityThereIs() = runBlocking<Unit> {
        // As the launcher opens the app: its own task, which the tap's flags find. ActivityScenario's has another affinity.
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitUntil("the app in front") { mainActivities(Stage.RESUMED).size == 1 }
        val opened = mainActivities(Stage.RESUMED).single()
        try {
            cast()
            loaded()
            remote.playing()
            loader.plays()
            // The play followed on the device, as the real load's feed says it: the remote has a play to show (L86, L91).
            onMainThread { Casting.follows(CastFeed(loader.sent.single(), launch, CastPlayerState.Playing, 0, null)) }
            waitUntil("the remote's play") { container.remotePlaying.now.value != null }
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }
                .firstOrNull { n -> n.actions.orEmpty().any { it.title?.toString() == STOP_CASTING } } ?: throw AssertionError("no cast notification")
            notification.contentIntent.send()
            waitUntil("the remote asked for") { onMainThread { opened.remotesAsked } == 1 }
            waitUntil("back in front") { mainActivities(Stage.RESUMED).singleOrNull() === opened }
            assertEquals("one MainActivity", listOf(opened), mainActivities(*ALIVE))
            assertEquals("Kitchen display still chosen: no new activity chose This phone (W18)", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        } finally {
            mainActivities(*ALIVE).forEach { activity -> onMainThread { activity.finish() } }
            waitUntil("the app's activities closed") { mainActivities(*ALIVE).isEmpty() }
        }
    }

    /** The app's MainActivity instances in any of [stages]. */
    private fun mainActivities(vararg stages: Stage): List<MainActivity> = onMainThread {
        stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
    }

    /** A still of one colour, as a TMDB still would come: PNG bytes. */
    private fun still(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900096
        const val FILM_TITLE = "Copper Crown"

        /** An invented show's TMDB id, and its episode's words as the boards draw them. */
        const val SHOW = 900097
        const val SHOW_TITLE = "Harbor Lights"
        const val EPISODE_LINE = "S02E04 · Low Water"

        /** A film's line under its title, as its launch says it. */
        const val FILM_LINE = "2026"

        /** 32:10, where the boards' cast is paused. */
        const val PAUSED_AT = 1_930_000L

        /** Well into the play, with no event from the device's player since its start. */
        const val FORTY_MINUTES = 2_400_000L
        const val INDEXER = "cast-service-device-test"
        const val MKV = "Copper.Crown.2026.1080p.WEB-DL.H.264-CAST"
        const val FILE_NAME = "copper-crown.mkv"
        const val PART = 32_000

        /** The platform session's tag for Media3's session with the id "cast". */
        const val CAST_TAG = "androidx.media3.session.id.cast"
        const val WAKE_TAG = "NZStreamer:cast"

        /** The lines under the titles on this phone, the device named (W62, L120): last from Android 11 on, else first. */
        val EPISODE_ON_KITCHEN = if (Build.VERSION.SDK_INT >= 30) "$EPISODE_LINE · on Kitchen display" else "On Kitchen display · $EPISODE_LINE"
        val FILM_ON_KITCHEN = if (Build.VERSION.SDK_INT >= 30) "$FILM_LINE · on Kitchen display" else "On Kitchen display · $FILM_LINE"

        /** A private address the emulator hasn't: no server binds it. */
        val NOWHERE = InetAddress.getByAddress(byteArrayOf(10, 0, 2, 254.toByte())) as Inet4Address

        /** Every stage of an activity not yet destroyed. */
        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}

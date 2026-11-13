package io.skjaere.usenet.app.cast

import android.Manifest
import android.app.NotificationManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastMetadata
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The slow-stream banner during a cast (W52, W64, L134; CsPhoneSlowCast) in the app as MainActivity shows it, over a fake
 * Cast session and device player and a fake feed of the play there that Casting counts each second as its server would
 * (L73): Google's software is never started. What reached Kitchen display falls behind the film's 31 Mbps, so the banner
 * says so with which part is slow and the hint by the maximum when casting set (40 Mbps): over the remote's picture, and
 * above the Playing on bar elsewhere; the missing-parts prompt comes first; Try a smaller release keeps the device and opens
 * the play sheet, which says the release playing goes on there meanwhile (L135); and the cast's notification never offers
 * it. The detector's waits are shortened (CastTimings.dip); the
 * show, the device and the releases are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastSlowStreamDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val home = HomeContentShown()
    private val context get() = compose.activity
    private val container get() = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val launcher = FakeLauncher()
    private val realSource = PlaybackCoordinator.nzbSource
    private lateinit var remote: FakeRemotePlayer

    /** Where the fake device's plays are driven from: never the test's own thread, whose waits drive the compositions. */
    private val feeding = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether a case started the cast's real service, which @After waits to see gone. */
    private var service = false

    /** This case's LOAD, its content id: each case's own, as each LOAD's is. */
    private val load = "cast-slow-load-${System.nanoTime()}"
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val playing = SavedRelease(INDEXER, GUID, RELEASE, null, 0, "cast-slow-nzb", "harbor-lights.mkv", SIZE)

    @Before
    fun setUp() {
        home.show()
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        CastTimings.current = CastTimings(dip = DipDetector.Timings(graceMs = 1_000, windowMs = 3_000, fallMs = 500, recoverMs = 2_000, shownForMs = 1_000))
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        // After the fakes' start, which keeps the settings it found for its reset to put back.
        runBlocking { container.settings.updatePlayback { it.copy(maxBitrateMbps = null, castMaxBitrateMbps = 40) } }
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        onMainThread { container.playback.withdraw() }
    }

    @After
    fun restore() {
        feeding.cancel()
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        PlaybackCoordinator.nzbSource = realSource
        if (service) compose.waitUntil(20_000) { castSession() == null }
        home.restore()
    }

    /** A search's release [guid] titled [title], estimated at [bitrate], as a request's candidates are. */
    private fun candidate(guid: String, title: String, bitrate: Long) = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = guid, title = title, sizeBytes = SIZE, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$INDEXER/$guid", title, SIZE), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = bitrate),
    )

    /** The episode cast from a request with a smaller release in it (kept as the coordinator keeps a play's). */
    private fun episodeLaunch(): PlayerLaunch {
        val request = PlayRequest(
            SHOW_TITLE, EPISODE_LINE, listOf(candidate(GUID, RELEASE, BITRATE), candidate(SMALLER_GUID, SMALLER, 8_000_000)), FilePicker.Largest, episode,
            chosen = true,
        )
        return PlayerLaunch(
            UsenetUris.build(playing.nzbId, 0, playing.fileName, playing.fileSize), SHOW_TITLE, EPISODE_LINE, WatchKey.episode(SHOW, 2, 4), episode, playing,
            requestId = container.playback.keep(request), cast = device,
        )
    }

    /**
     * The device playing [launch]'s [load] from [from] while [rate] bits a second reach it, as Casting counts it every
     * quarter second: the film sent past it falls behind as it plays, the server's answers waiting [sourceMs] a second on
     * the providers and [peerMs] on the device. At [stallAt] it stands buffering, what was sent past it as it was. [gaps]:
     * missing parts the server sent, gone past.
     */
    private fun play(
        launch: PlayerLaunch,
        load: String,
        from: Long,
        rate: Long,
        sourceMs: Long,
        peerMs: Long,
        gaps: List<LongRange> = emptyList(),
        stallAt: Long? = null,
        stalled: AtomicBoolean = AtomicBoolean(),
    ): Job = feeding.launch {
        val start = SystemClock.elapsedRealtime()
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val t = now - start
            val sending = stallAt?.let { minOf(t, it - from) } ?: t
            val position = from + sending
            val buffering = stallAt != null && position >= stallAt
            val aheadMs = START_AHEAD_MS + sending * rate / BITRATE - sending
            val feed = CastFeed(
                load, launch, if (buffering) CastPlayerState.Buffering else CastPlayerState.Playing, position, LENGTH, reported = position, reportedAt = now,
                size = SIZE, gaps = gaps, passed = gaps, sent = rate * t / 8_000, ahead = aheadMs * SIZE / LENGTH, sourceWaitMs = sourceMs * t / 1_000,
                peerWaitMs = peerMs * t / 1_000, countedAt = now,
            )
            onMainThread { Casting.follows(feed) }
            if (buffering) stalled.set(true)
            delay(250)
        }
    }

    /** The play there as its LOAD leaves it, before Casting counts anything. */
    private fun loaded(launch: PlayerLaunch, load: String = this.load) {
        onMainThread {
            remote.setMediaItem(MediaItem.Builder().setMediaId(load).build())
            Casting.follows(CastFeed(load, launch, CastPlayerState.Playing, FROM, LENGTH, reported = FROM, reportedAt = SystemClock.elapsedRealtime(), size = SIZE))
        }
    }

    /** A second of missing articles [atMs] into the film: 3,875 bytes a millisecond. */
    private fun gapAt(atMs: Long) = atMs * 3_875 until (atMs + 1_000) * 3_875

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 20_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInWindow

    private val bar = hasText("$SHOW_TITLE · S02E04")
    private val positionOn = "Position on Kitchen display"
    private val trySmaller = hasText(TRY_SMALLER) and hasClickAction()
    private val missingParts = hasText("This release is missing parts (3 so far)")
    private val wifi = hasContentDescription(SPOKEN_WIFI)
    private val providers = hasContentDescription(SPOKEN_PROVIDERS)

    @Test
    fun overTheRemotesPictureTheWiFiToTheDeviceIsTheSlowPart() {
        CastSeams.launcher = { launcher }
        val launch = episodeLaunch()
        loaded(launch)
        waitFor(bar)
        compose.onNode(bar).performClick()
        waitFor(hasContentDescription(positionOn))
        // The Wi-Fi short: the server held in its writes, until the device stands buffering 3 s in (CFS-1).
        play(launch, load, FROM, rate = 19_000_000, sourceMs = 50, peerMs = 600, stallAt = FROM + 3_000)
        // Its words are one node, as TalkBack reads them: the headline, the numbers, which part is slow and the hint.
        waitFor(wifi)
        waitFor(trySmaller)
        // Over the picture, as the missing-parts prompt is: above the title, and no bar on the remote.
        assertTrue("over the picture, above the title", bounds(trySmaller).bottom <= bounds(hasText(SHOW_TITLE)).top)
        assertFalse(exists(bar))
        // TalkBack: read once as it shows, the numbers in words (CsPhoneTalkBackC2 19), then its button (20).
        val words = compose.onNode(wifi, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, words.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(Role.Button, compose.onNode(trySmaller).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        // Anywhere else, the same banner above the bar.
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(bar)
        waitFor(trySmaller)
        assertTrue("above the bar", bounds(trySmaller).bottom <= bounds(bar).top + 0.5f)
        assertTrue(exists(wifi))
    }

    @Test
    fun anywhereElseTheProvidersAreTheSlowPartAboveTheBar() {
        CastSeams.launcher = { launcher }
        val launch = episodeLaunch()
        loaded(launch)
        waitFor(bar)
        play(launch, load, FROM, rate = 13_000_000, sourceMs = 600, peerMs = 50)
        waitFor(providers)
        waitFor(trySmaller)
        // Above the bar, the bar on the tab bar: the page ends above them.
        val tabs = bounds(hasText("Home") and hasClickAction())
        val shownBar = bounds(bar)
        assertEquals("the bar on the tab bar", tabs.top, shownBar.bottom, 0.5f)
        assertTrue("the banner above the bar", bounds(trySmaller).bottom <= shownBar.top + 0.5f)
        assertEquals("one banner", 1, compose.onAllNodes(providers).fetchSemanticsNodes().size)
    }

    /** One at a time, the missing-parts prompt first (W37, L134): the dip found under it shows as it goes. */
    @Test
    fun theMissingPartsPromptComesFirst() {
        CastSeams.launcher = { launcher }
        val launch = episodeLaunch()
        loaded(launch)
        waitFor(bar)
        // Three within ten minutes of film, behind where it plays; the Wi-Fi short, the device buffering 3 s in.
        play(
            launch, load, FROM, rate = 19_000_000, sourceMs = 50, peerMs = 600, gaps = listOf(gapAt(1_500_000), gapAt(1_800_000), gapAt(1_900_000)),
            stallAt = FROM + 3_000,
        )
        waitFor(missingParts)
        // The dip found under it, as the play's end counts it (L136): the slot is still the prompt's.
        compose.waitUntil(15_000) { (container.castPlaying?.dips(load) ?: 0) >= 1 }
        compose.waitForIdle()
        assertTrue(exists(missingParts))
        assertFalse("the prompt has the slot", exists(wifi))
        compose.onNode(hasText("Keep watching") and hasClickAction()).performClick()
        waitFor(wifi, 10_000)
        assertFalse(exists(missingParts))
    }

    @Test
    fun trySmallerKeepsTheDeviceAndOpensThePlaySheet() {
        CastSeams.launcher = { launcher }
        val held = CompletableDeferred<ByteArray>()
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { held.await() } }
        val launch = episodeLaunch()
        loaded(launch)
        waitFor(bar)
        // Slow on the Wi-Fi from 6 s before 33:35, standing there buffering from then on.
        val stalled = AtomicBoolean()
        play(launch, load, PLACE - 6_000, rate = 19_000_000, sourceMs = 50, peerMs = 600, stallAt = PLACE, stalled = stalled)
        waitFor(trySmaller)
        compose.waitUntil(20_000) { stalled.get() }
        waitFor(trySmaller)
        compose.onNode(trySmaller).performClick()
        compose.waitUntil(10_000) { container.playback.state.value is PlayState.Running }
        waitFor(hasText("Casting to ${kitchen.name}"))
        // L135: the release playing goes on there until the smaller one is ready, from where it stands.
        waitFor(hasText("The release playing goes on there until this one is ready, at 33:35."), 10_000)
        assertEquals("the device's play started for the smaller release", listOf(device), launcher.started.map { it.first })
        assertTrue("nothing loaded yet: the one playing goes on", launcher.loads.isEmpty())
        compose.waitUntil(10_000) { !exists(wifi) }
    }

    /** The banner is the app's alone (L134): the cast's session, which the notification and lock screen show, never offers it. */
    @Test
    fun theCastsNotificationNeverOffersIt() {
        service = true
        val loader = FakeLoader(ByteArray(1 shl 20) { (it % 251).toByte() }, said = { CastMetadata.of(it, container.appName).session(artwork = null, it.cast?.name.orEmpty(), Build.VERSION.SDK_INT) })
        CastSeams.loader = { loader }
        val real = container.castLauncher() ?: throw AssertionError("no cast launcher")
        val launch = episodeLaunch()
        assertTrue("the service started", onMainThread { real.starting(device, SessionMetadata.starting(SHOW_TITLE, EPISODE_LINE, episode, container.appName)) })
        // The load runs as its outcome is waited on, as the coordinator's job waits: here in the feeding scope, never the test's thread.
        val load = real.load(launch)
        feeding.launch { load.outcome() }
        compose.waitUntil(20_000) { loader.sent.isNotEmpty() && onMainThread { remote.currentMediaItem?.mediaId } == loader.sent[0] }
        remote.playing()
        loader.plays()
        compose.waitUntil(20_000) { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        play(launch, loader.sent.single(), FROM, rate = 13_000_000, sourceMs = 600, peerMs = 50)
        waitFor(trySmaller)
        val session = castSession() ?: throw AssertionError("no cast session")
        val actions = session.playbackState?.customActions.orEmpty().map { it.name.toString() }
        assertFalse("never Try a smaller release: $actions", TRY_SMALLER in actions)
        assertTrue("Stop casting offered: $actions", STOP_CASTING in actions)
        val offered = context.getSystemService(NotificationManager::class.java).activeNotifications.flatMap { it.notification.actions.orEmpty().map { a -> a.title.toString() } }
        assertFalse("never in the notification: $offered", TRY_SMALLER in offered)
        assertTrue("the cast's notification: $offered", STOP_CASTING in offered)
    }

    /** The "cast" session as Android lists it to the system's media controls and the lock screen. */
    private fun castSession(): MediaController? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .firstOrNull { it.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName && it.tag == CAST_TAG }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_641
        const val SHOW_TITLE = "Harbor Lights"
        const val EPISODE_LINE = "S02E04 · Low Water"
        const val INDEXER = "cast-slow-stream-device-test"
        const val GUID = "hl-1080p-remux"
        const val RELEASE = "Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-CAST"
        const val SMALLER_GUID = "hl-1080p-web"
        const val SMALLER = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-GRP"

        /** 52 minutes of 31 Mbps: 3,875 bytes a millisecond. */
        const val LENGTH = 3_120_000L
        const val SIZE = 12_090_000_000L
        const val BITRATE = 31_000_000L

        /** Where the plays start: 32:35, a minute before the boards' 33:35 ([PLACE]). */
        const val FROM = 1_955_000L
        const val PLACE = 2_015_000L

        /** The film sent past the device as the counts start. */
        const val START_AHEAD_MS = 8_000L

        const val CAST_TAG = "androidx.media3.session.id.cast"
        const val TRY_SMALLER = "Try a smaller release"

        /** CsPhoneSlowCast's banners as TalkBack reads them (CsPhoneTalkBackC2 19): what the board shows, the numbers in words. */
        const val SPOKEN_PROVIDERS = "Streaming is slower than this video needs. 13 of 31 megabits per second. Your providers are the slow part. " +
            "Lowering Maximum bitrate when casting to 10 megabits per second, in Settings, Quality and languages, picks smaller releases next time."
        const val SPOKEN_WIFI = "Streaming is slower than this video needs. 19 of 31 megabits per second. The Wi-Fi to Kitchen display is the slow part. " +
            "Lowering Maximum bitrate when casting to 15 megabits per second, in Settings, Quality and languages, picks smaller releases next time."
    }
}

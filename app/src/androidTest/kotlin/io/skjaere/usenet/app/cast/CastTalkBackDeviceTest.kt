package io.skjaere.usenet.app.cast

import android.Manifest
import android.app.NotificationManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.Cast
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.casting.CAST_CONNECTING
import io.skjaere.usenet.app.casting.CAST_DEVICES
import io.skjaere.usenet.app.casting.CAST_FROM_ANOTHER
import io.skjaere.usenet.app.casting.CAST_LOOKING_SAID
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_LOOK_LINE
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.casting.CAST_SIMPLE
import io.skjaere.usenet.app.casting.CastDip
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastModel
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.CastRanking
import io.skjaere.usenet.app.casting.CastVerdict
import io.skjaere.usenet.app.casting.CastVolumeNow
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.connectedAnnouncement
import io.skjaere.usenet.app.casting.connectingAnnouncement
import io.skjaere.usenet.app.casting.disconnectFrom
import io.skjaere.usenet.app.casting.ownVolume
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.casting.volumeOn
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.ButtonFace
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.FAST_FORWARD_10
import io.skjaere.usenet.app.link.OPEN_THE_REMOTE
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnDeps
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.REWIND_10
import io.skjaere.usenet.app.link.RemoteSaidBar
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.TvNow
import io.skjaere.usenet.app.link.TvRemoteScreen
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.app.link.pauseOn
import io.skjaere.usenet.app.link.playOnTv
import io.skjaere.usenet.app.link.sheetStateLine
import io.skjaere.usenet.app.link.stoppedOnAt
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastSlowStream
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlaySheet
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.app.play.SlowStreamBanner
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.rankedForLine
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What TalkBack hears of a cast (L95; CsPhoneTalkBack), in one walk on a real device, as a regression pass over the
 * pieces items 10, 13 and 16 built and tested one by one: the Play on sheet's Cast devices group (12-15) and the two
 * steps it announces, the Playing on bar (9-11), the remote (1-8) and the line Stop casting leaves, read once, and the
 * name of the cast session's own button (19). Over a fake Cast session and device player (L73), Google's software never
 * started; the play is the real load's (Casting), of a release on the test's own NNTP server (LocalNntp, L99), so the
 * words after Stop casting are the app's own. The show, the devices, the TV and the release are invented.
 *
 * Then C2's pieces in a second walk (L150; CsPhoneTalkBackC2 1-22), as items 27, 30-35, 40-41, 44 and 49 built and tested
 * them one by one: the Cast button in each face and the steps the list says once; the list the first time, with "Look
 * for Cast devices" and Privacy; the connected device's block, its pause, its volume and Stop casting or Disconnect; the
 * remote's button and volume; the pre-check; a list ranked for the device and its chips; the slow-cast banner and its
 * button. Each piece is the app's own composable over the fakes, page by page; where each sits on its screen is its own
 * test's (CastButtonDeviceTest, CastBlockDeviceTest, CastRemoteDeviceTest, CastPrecheckDeviceTest, CastRankedDeviceTest,
 * CastSlowStreamDeviceTest). The film, its releases and the devices are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastTalkBackDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val library = container.library
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()
    private val kitchen = castRoute("Kitchen display")
    private val den = castRoute("Den TV", description = "Casting Default Media Receiver")
    private val living = castRoute("Living room TV")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val events = CastEventHub()
    private val local = LocalNntp()
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val key = WatchKey.episode(SHOW, 2, 4)
    private val fixtures = GapFixtures(local, INDEXER, episode)
    private lateinit var remote: FakeRemotePlayer
    private val launcher: CastLauncher get() = container.castLauncher() ?: throw AssertionError("no cast launcher")

    /** Where the load's outcome and the said lines are waited on, never the test's own runBlocking. */
    private val waits = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every line the app says of its plays elsewhere, in order. */
    private val lines = CopyOnWriteArrayList<String>()

    /** An NZStreamer TV paired with this phone, of the same name as a Cast device (CsPhoneTalkBack 15): ready, in this test alone. */
    private val livingTv = PairedTv(TV_KEY, living.name, pairedAt = 0)

    /** Whether the remote is open over the bar's page, as the bar's tap opens it and Stop casting closes it. */
    private var remoteOpen by mutableStateOf(false)

    /** The second walk's page (CsPhoneTalkBackC2's frames), each with Play on's list over it. */
    private enum class Page { Show, Remote, Precheck, Ranked, Banner }

    private var page by mutableStateOf(Page.Show)

    /** A face shown on the Show page's button in place of where Play goes: a TV chosen (CsPhoneTalkBackC2 1); null for none. */
    private var face by mutableStateOf<PlayTarget?>(null)

    private var asked by mutableStateOf<PlayState.Prechecking?>(null)
    private var ranked by mutableStateOf<ResultGroup?>(null)
    private var banner by mutableStateOf<SlowStream.Shown?>(null)

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        runBlocking { forgetTheRelease() }
        CastFakes.started(routes, connector, announced)
        CastSeams.events = { events }
        Casting.artOf = { _, _ -> null }
        onMainThread { container.playback.withdraw() }
        waits.launch(start = CoroutineStart.UNDISPATCHED) { container.remotePlaying.said.filterNotNull().collect { lines += it.text } }
    }

    @After
    fun restore() {
        waits.cancel()
        compose.runOnUiThread { container.playOn.closeSheet() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        local.close()
        runBlocking {
            forgetTheRelease()
            library.markEpisodes(listOf(EpisodeRef(SHOW, 2, 4, WatchTitle.episode(SHOW_TITLE, 2, 4))), watched = false)
        }
    }

    /** The release a play remembers for the episode goes with the test: its NZB names the test's own server. */
    private suspend fun forgetTheRelease() {
        library.lastRelease(key)?.let { library.forgetRelease(key, it) }
    }

    /** The phone as the walk meets it: the Play on sheet over the bar's page, the line said and the bar at its foot, or the remote. */
    private fun show() = compose.setContent {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                if (remoteOpen) TvRemoteScreen(onBack = { remoteOpen = false })
                Column(Modifier.align(Alignment.BottomCenter)) {
                    RemoteSaidBar(container.remotePlaying)
                    if (!remoteOpen) PlayMiniBar(container.playback, playing = container.remotePlaying, onRemote = { remoteOpen = true })
                }
                PlayOnHost(
                    onAddTv = {}, onPairAgain = {}, onPrivacy = {},
                    deps = PlayOnDeps(
                        container.playOn, flowOf(listOf(livingTv)), flowOf(mapOf(KeyId.parse(TV_KEY)!! to TvNow(TvState.Ready, linked = true))),
                        hold = { AutoCloseable {} },
                    ),
                )
            }
        }
    }

    /**
     * The second walk's phone, page by page: a title page's Cast button (Play on's choice, or [face]), the remote, the
     * pre-check, a release list ranked for the device, or the slow-cast banner; Play on's list over each.
     */
    private fun showC2() = compose.setContent {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                val target by container.playOn.target.collectAsStateWithLifecycle()
                when (page) {
                    Page.Show -> Column { CastButton(face ?: target, onClick = { container.playOn.openSheet() }) }
                    Page.Remote -> TvRemoteScreen(onBack = {})
                    Page.Precheck -> asked?.let {
                        PlaySheet(it, onMinimize = {}, onCancel = {}, onDismiss = {}, onSearch = {}, onProviders = {}, castTo = device, castTarget = target)
                    }
                    Page.Ranked -> ranked?.let { ReleaseList(it, FILM_TITLE, onBack = {}, rankedFor = kitchen.name, onPlay = {}) }
                    Page.Banner -> banner?.let { SlowStreamBanner(it, compact = false, onTrySmaller = {}) }
                }
                PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
            }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun node(matcher: SemanticsMatcher): SemanticsNode {
        waitFor(matcher)
        return compose.onNode(matcher).fetchSemanticsNode()
    }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    /** A Cast device's row by TalkBack's words for it: "Kitchen display, Cast device", and its line, if any. */
    private fun castRow(name: String, line: String? = null) = hasContentDescription(listOfNotNull(name, "Cast device", line).joinToString(", ")) and hasClickAction()

    /** A node TalkBack reads as [words], whatever its time says: [words] a pattern. */
    private fun spoken(words: Regex) = SemanticsMatcher("spoken as $words") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any(words::matches) }

    private fun SemanticsNode.role(): Role? = config.getOrNull(SemanticsProperties.Role)

    private fun SemanticsNode.live(): LiveRegionMode? = config.getOrNull(SemanticsProperties.LiveRegion)

    /** The "cast" session as Android lists it to the system's media controls and the lock screen. */
    private fun castSession(): MediaController? {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null).firstOrNull { it.packageName == context.packageName && it.tag == CAST_TAG }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /**
     * The episode's release on the test's own NNTP server, loaded on Kitchen display through the real load ([Casting]) as
     * the coordinator hands it over at its last step, played there at the device's word, then at 32:10: its content id.
     */
    private fun castTheEpisode(): String = runBlocking {
        CastSeams.loader = { Casting.with(it) }
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        val posted = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "cast-talkback", RELEASE) { emptySet() }
        val release = fixtures.saved(RELEASE, posted.prepared)
        val launch = PlayerLaunch(
            UsenetUris.build(posted.prepared.id, release.fileIndex, release.fileName, release.fileSize), SHOW_TITLE, EPISODE_LINE, key, episode, release,
            cast = device,
        )
        assertTrue("the service started", onMainThread { launcher.starting(device, SessionMetadata.starting(SHOW_TITLE, EPISODE_LINE, episode, container.appName)) })
        val load = launcher.load(launch)
        val outcome = waits.async { load.outcome() }
        waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
        val id = onMainThread { remote.currentMediaItem?.mediaId } ?: throw AssertionError("no item")
        remote.playing()
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, 0, SystemClock.elapsedRealtime())) }
        assertEquals(CastLoadOutcome.Playing, withTimeout(20_000) { outcome.await() })
        // The device's own length, polled each second; then it plays on at 32:10.
        waitUntil("the device's length followed") { Casting.feed.value?.durationMs == LENGTH }
        onMainThread {
            remote.seekTo(PLACE)
            events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, PLACE, SystemClock.elapsedRealtime()))
        }
        waitUntil("the play followed at 32:10") { Casting.feed.value?.positionMs == PLACE }
        id
    }

    @Test
    fun talkBackHearsTheSheetTheBarTheRemoteAndTheSessionsButtonInOneWalk() {
        show()

        // 12-15, the Play on sheet (CsPhoneSheet's first frame): the Cast group, "Cast to" (W47), under the TVs.
        routes.found(kitchen, den, living)
        compose.runOnUiThread { container.playOn.openSheet() }
        waitFor(hasText(SHEET_TITLE))
        val heading = node(hasContentDescription(CAST_DEVICES))
        assertNotNull("12: \"Cast to, heading\"", heading.config.getOrNull(SemanticsProperties.Heading))
        val simple = node(hasText(CAST_SIMPLE))
        assertTrue("12: then its line", heading.boundsInRoot.top < simple.boundsInRoot.top)
        val kitchenRow = node(castRow(kitchen.name) and isNotSelected())
        assertEquals("13: \"Kitchen display, Cast device, radio button, not selected\"", Role.RadioButton, kitchenRow.role())
        val denRow = node(castRow(den.name, den.description) and isNotSelected())
        assertEquals("14: \"Den TV, Cast device, Casting Default Media Receiver, radio button, not selected\"", Role.RadioButton, denRow.role())
        val castLiving = node(castRow(living.name) and isNotSelected())
        assertEquals(Role.RadioButton, castLiving.role())
        // 15: the same TV under TVs with NZStreamer reads its name and state alone; "Cast device" tells the two apart.
        val tvLiving = node(hasText(living.name) and hasText(sheetStateLine(TvState.Ready, living.name)) and hasClickAction())
        assertNull("15: the TV's row says no \"Cast device\"", tvLiving.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("15: \"Living room TV, Ready, radio button\"", Role.RadioButton, tvLiving.role())
        assertTrue("15: the TVs first, then the Cast devices", tvLiving.boundsInRoot.top < heading.boundsInRoot.top)
        val rows = listOf(simple, denRow, kitchenRow, castLiving).map { it.boundsInRoot.top }
        assertEquals("the line, then the devices by name", rows.sorted(), rows)
        // A double-tap connects: "Connecting to Kitchen display", then "Connected to Kitchen display", each once.
        tap(castRow(kitchen.name))
        waitFor(castRow(kitchen.name, CAST_CONNECTING) and isSelected())
        assertEquals("13: the tap's step, once", listOf(connectingAnnouncement(kitchen.name)), announced.toList())
        connector.started(kitchen)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        compose.waitUntil(10_000) { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        assertEquals("13: then connected, once", listOf(connectingAnnouncement(kitchen.name), connectedAnnouncement(kitchen.name)), announced.toList())
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer

        // The episode plays on Kitchen display, at 32:10 of the device's 52 minutes.
        val id = castTheEpisode()

        // 19, the notification's card: the cast session's own button is "Stop casting".
        waitUntil("the device's play in the cast session") { castSession()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val stop = castSession()?.playbackState?.customActions.orEmpty().singleOrNull { it.action == CastService.STOP_ACTION }
        assertEquals("19: \"Stop casting, button\"", STOP_CASTING, stop?.name?.toString())
        val card = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }
            .firstOrNull { n -> n.actions.orEmpty().any { it.title?.toString() == STOP_CASTING } }
        assertNotNull("19: the notification's own button says \"Stop casting\"", card)

        // 9-11, the bar: one stop, its words merged, a double-tap opening the remote; then its two buttons.
        val playingThere = Regex("$SHOW_TITLE, season 2, episode 4\\. Playing on ${kitchen.name}, 32 minutes \\d{1,2} seconds of 52 minutes")
        val bar = node(spoken(playingThere))
        assertEquals("9: one stop, double-tap \"open the remote\"", OPEN_THE_REMOTE, bar.config.getOrNull(SemanticsActions.OnClick)?.label)
        val barPause = node(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
        assertEquals("10: \"Pause on Kitchen display, button\"", Role.Button, barPause.role())
        val barStop = node(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        assertEquals("11: \"Stop casting to Kitchen display, button\"", Role.Button, barStop.role())
        assertTrue("9-11: in reading order", bar.boundsInRoot.right <= barPause.boundsInRoot.left && barPause.boundsInRoot.right <= barStop.boundsInRoot.left)
        compose.onNode(spoken(playingThere)).performClick()
        compose.waitUntil(5_000) { remoteOpen }

        // 1-8, the remote, top to bottom; the picture passed over.
        val back = node(hasContentDescription("Back") and hasClickAction())
        assertEquals("1: \"Back, button\"", Role.Button, back.role())
        val title = node(hasContentDescription("$SHOW_TITLE, season 2, episode 4, Low Water"))
        assertNotNull("2: a heading", title.config.getOrNull(SemanticsProperties.Heading))
        val state = node(hasText("Playing on ${kitchen.name}"))
        assertEquals("3: read again once as it changes", LiveRegionMode.Polite, state.live())
        val slider = node(hasContentDescription("Position on ${kitchen.name}"))
        assertTrue(
            "4: \"Position on Kitchen display, 32 minutes 10 seconds of 52 minutes, slider\"",
            Regex("32 minutes \\d{1,2} seconds of 52 minutes").matches(slider.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()),
        )
        val rewind = node(hasContentDescription(REWIND_10) and hasClickAction())
        val pause = node(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
        val forward = node(hasContentDescription(FAST_FORWARD_10) and hasClickAction())
        val stopCasting = node(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        listOf(rewind, pause, forward, stopCasting).forEach { assertEquals("5-8: buttons", Role.Button, it.role()) }
        assertTrue("8: it reads Stop casting", exists(hasText(STOP_CASTING)))
        val order = listOf(back, title, state, slider, pause, stopCasting).map { it.boundsInRoot.top }
        assertEquals("1-8: Back, the title, the state, the time bar, the buttons, Stop casting", order.sorted(), order)
        assertTrue("5-7: rewind, play or pause, fast-forward", rewind.boundsInRoot.right <= pause.boundsInRoot.left && pause.boundsInRoot.right <= forward.boundsInRoot.left)
        // 6: paused there from the remote, the state line is read again, the time stands at 32:10, and Play is offered.
        val before = remote.commands.size
        tap(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { remote.commands.size > before }
        assertEquals("pause", remote.commands.last())
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Paused, null, id, PLACE, SystemClock.elapsedRealtime())) }
        val paused = node(hasText("Paused on ${kitchen.name}"))
        assertEquals("3: \"Paused on Kitchen display\", read as it changes", LiveRegionMode.Polite, paused.live())
        val still = node(hasContentDescription("Position on ${kitchen.name}"))
        assertEquals("4", "32 minutes 10 seconds of 52 minutes", still.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("4: a step a 10 s seek", ProgressBarRangeInfo(1_930f, 0f..3_120f, steps = 311), still.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertEquals("6: \"Play on Kitchen display, button\"", Role.Button, node(hasContentDescription(playOnTv(kitchen.name)) and hasClickAction()).role())
        // 8: Stop casting ends the cast; the remote closes, and "Stopped on Kitchen display at 32:10" is read once.
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        compose.waitUntil(10_000) { connector.ends.get() == 1 }
        compose.waitUntil(10_000) { !remoteOpen }
        val line = stoppedOnAt(kitchen.name, PLACE)
        assertEquals("8: read once as it comes", LiveRegionMode.Polite, node(hasText(line)).live())
        compose.waitUntil(10_000) { !exists(hasText(line)) }
        assertEquals("8: said once", listOf(line), lines.toList())
        assertFalse("the bar gone with the play", exists(hasContentDescription(stopCastingTo(kitchen.name))))
    }

    /** The Cast button: what TalkBack reads it as, and its face. */
    private fun button(words: String, look: ButtonFace.Look) {
        val node = node(hasContentDescription(words) and SemanticsMatcher.keyIsDefined(CastButtonLook))
        assertEquals("\"$words, button\"", Role.Button, node.role())
        assertEquals(words, look, node.config[CastButtonLook])
    }

    /** Harbor Lights as this phone's cast play on Kitchen display, for the second walk's block and remote: its feed alone. */
    private val played = PlayerLaunch(
        UsenetUris.build("cast-talkback-c2-nzb", 0, "harbor-lights.mkv", 2_000_000_000), SHOW_TITLE, EPISODE_LINE, key, episode,
        SavedRelease(INDEXER, RELEASE, RELEASE, null, 0, "cast-talkback-c2-nzb", "harbor-lights.mkv", 2_000_000_000), cast = device,
    )

    /** A release of Lantern Field as an indexer would list it, [gigabytes] large: the walk's list ranks it. */
    private fun release(title: String, gigabytes: Double) = NewznabItem(
        indexerId = "cast-talkback-c2", guid = title, title = title, sizeBytes = (gigabytes * 1e9).toLong(), publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
        season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    /**
     * L150 (CsPhoneTalkBackC2 1-22): C2's pieces in one walk, as TalkBack meets them. The button at rest, without Play
     * services and with a TV chosen; the list the first time, "Cast to" a heading, "Look for Cast devices" a button with
     * its line after it, Privacy a link, and "Looking for Cast devices" said once as it looks; a device connected from its
     * row, each step said once, the button saying where Play goes as it does. The connected block: a heading, this phone's
     * film in words, pause, the volume as a slider of 5 % steps, a change the device refuses said under it (M1-A), Stop
     * casting, or Disconnect for another phone's film. The remote's button and its volume at its own level. The pre-check,
     * named by its headline, read top to bottom; a list ranked for the device, its line before the releases and the chips in
     * each card's reading, ⚠ as "Warning" and the numbers in words; the slow-cast banner, read once, the numbers in words,
     * then its button.
     */
    @Test
    fun aSecondWalkHearsTheButtonTheListTheBlockTheRemoteThePrecheckTheRankingAndTheBanner() = runBlocking<Unit> {
        showC2()
        // 1, the button: without Play services the TV icon, and Google's Cast icon at rest; with a TV chosen, its name.
        CastFakes.missing()
        compose.waitUntil(10_000) { container.castAvailable.value == false }
        button("Play on: this phone", ButtonFace.Look.NoPlayServices)
        CastFakes.started(routes, connector, announced)
        compose.waitUntil(10_000) { container.castAvailable.value == true }
        button("Play on: this phone", ButtonFace.Look.Rest)
        face = PlayTarget.Tv(KeyId.parse(TV_KEY)!!, living.name, TvState.Ready)
        button("Play on: ${living.name}", ButtonFace.Look.Tv)
        face = null

        // 2-4, the list the first time (CsPhoneTalkBackC2 1): "Look for Cast devices" not chosen yet.
        container.settings.updateCast { it.copy(look = false) }
        withTimeout(10_000) { container.castLooking.first { it == false } }
        compose.onNode(hasContentDescription("Play on: this phone") and hasClickAction()).performClick()
        val lookFor = hasText(CAST_LOOK_FOR) and hasClickAction()
        waitFor(lookFor)
        assertNotNull("2: \"Cast to, heading\"", node(hasContentDescription(CAST_DEVICES)).config.getOrNull(SemanticsProperties.Heading))
        val row = node(lookFor)
        assertEquals("3: \"Look for Cast devices, button\"", Role.Button, row.role())
        val line = node(hasText(CAST_LOOK_LINE))
        assertTrue("3: then its line", row.boundsInRoot.top < line.boundsInRoot.top)
        val privacy = node(hasText(CAST_PRIVACY))
        assertTrue("4: \"Privacy, link\"", privacy.config[SemanticsProperties.Text].any { it.getLinkAnnotations(0, it.length).isNotEmpty() })
        compose.onNode(lookFor).performClick()
        compose.waitUntil(10_000) { announced.isNotEmpty() }
        assertEquals("3: said once as it looks", listOf(CAST_LOOKING_SAID), announced.toList())
        routes.found(kitchen, den)
        // 1: a device connected from its row, each step said once, the button saying so.
        waitFor(castRow(kitchen.name))
        compose.onNode(castRow(kitchen.name)).performClick()
        button("Play on: ${kitchen.name}, connecting", ButtonFace.Look.Connecting)
        connector.started(kitchen)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        button("Play on: ${kitchen.name}", ButtonFace.Look.Connected)
        assertEquals(
            "1: each change once", listOf(CAST_LOOKING_SAID, connectingAnnouncement(kitchen.name), connectedAnnouncement(kitchen.name)), announced.toList(),
        )

        // 5-8, the block (CsPhoneTalkBackC2 2): Harbor Lights playing there from this phone, the device's volume at 45 %.
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        remote.deviceVolume(9)
        onMainThread { Casting.follows(CastFeed(LOAD, played, CastPlayerState.Playing, PLACE, LENGTH)) }
        connector.media(kitchen, CastEvent.Status(CastPlayerState.Playing, null, LOAD, PLACE, SystemClock.elapsedRealtime()))
        compose.onNode(hasContentDescription("Play on: ${kitchen.name}") and hasClickAction()).performClick()
        val heading = node(hasText(kitchen.name) and isHeading())
        val playing = node(hasContentDescription("Playing $SHOW_TITLE, season 2, episode 4"))
        assertTrue("5: \"Kitchen display, heading\", then what plays", heading.boundsInRoot.top <= playing.boundsInRoot.top)
        assertEquals("6: \"Pause on Kitchen display, button\"", Role.Button, node(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction()).role())
        val volume = node(hasContentDescription(volumeOn(kitchen.name)))
        assertEquals("7: \"…, 45 percent, slider\"", "45 percent", volume.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("7: a swipe is one 5 % step", ProgressBarRangeInfo(45f, 0f..100f, steps = 19), volume.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNode(hasContentDescription(volumeOn(kitchen.name))).performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.waitUntil(5_000) { remote.volumes.toList() == listOf(10) }
        // Its second gone by, the session's level unread: nothing snaps back. Then the device keeps its own level against
        // the next change (M1-A): the slider snaps back, its line under it, still usable.
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        connector.volume = CastVolumeNow(10, muted = false)
        compose.onNode(hasContentDescription(volumeOn(kitchen.name))).performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        waitFor(hasText(ownVolume(kitchen.name)))
        compose.waitUntil(5_000) { node(hasContentDescription(volumeOn(kitchen.name))).config.getOrNull(SemanticsProperties.StateDescription) == "50 percent" }
        assertFalse("7: still usable, never \"unavailable\"", node(hasContentDescription(volumeOn(kitchen.name))).config.contains(SemanticsProperties.Disabled))
        assertEquals("8: \"Stop casting to Kitchen display, button\"", Role.Button, node(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()).role())
        // 6: paused there, Play is offered.
        compose.onNode(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { "pause" in remote.commands }
        onMainThread { Casting.follows(CastFeed(LOAD, played, CastPlayerState.Paused, PLACE, LENGTH)) }
        connector.media(kitchen, CastEvent.Status(CastPlayerState.Paused, null, LOAD, PLACE, SystemClock.elapsedRealtime()))
        assertEquals("6: \"Play on Kitchen display, button\"", Role.Button, node(hasContentDescription(playOnTv(kitchen.name)) and hasClickAction()).role())
        // 8: another phone's film there: Disconnect.
        onMainThread { Casting.follows(null) }
        val other = CastEvent.Status(CastPlayerState.Playing, null, "another-phones-load", 0, SystemClock.elapsedRealtime(), title = "Copper Crown", subtitle = "S03E02")
        connector.media(kitchen, other)
        waitFor(hasText(CAST_FROM_ANOTHER))
        assertEquals("8: \"Disconnect from Kitchen display, button\"", Role.Button, node(hasContentDescription(disconnectFrom(kitchen.name)) and hasClickAction()).role())
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }

        // 21-22, the remote (CsPhoneTalkBackC2 5): its Cast button, and its volume at its own level.
        onMainThread { Casting.follows(CastFeed(LOAD, played, CastPlayerState.Playing, PLACE, LENGTH)) }
        connector.media(kitchen, CastEvent.Status(CastPlayerState.Playing, null, LOAD, PLACE, SystemClock.elapsedRealtime()))
        // Turned up meanwhile by the device's own remote: the session's level, which a slider reads as it shows.
        connector.volume = CastVolumeNow(11, muted = false)
        remote.deviceVolume(11)
        page = Page.Remote
        button("Play on: ${kitchen.name}", ButtonFace.Look.Connected)
        compose.waitUntil(5_000) { exists(hasContentDescription(volumeOn(kitchen.name)) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "55 percent")) }

        // 9-15, the pre-check (CsPhoneTalkBackC2 3): a German 2160p release Kitchen display, a first Chromecast, shows in HD.
        val tracks = ProbedTracks(
            listOf(ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 3840, height = 2160, bitDepth = 8)),
            listOf(ProbedTrack(MimeTypes.AUDIO_E_AC3, "E-AC-3", "de", channels = 6), ProbedTrack(MimeTypes.AUDIO_E_AC3, "E-AC-3", "en", channels = 6)),
            listOf(ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "en")),
        )
        val wanted = CastVerdict.Prefs(audioLanguages = listOf("en"), subtitlesWhenForeign = true)
        val precheck = CastPrecheck.of(
            CastVerdict.judge(tracks, CastModel.Chromecast.profile(null), wanted), tracks, kitchen.name, GERMAN_4K, ReleaseParser.parse(GERMAN_4K).audio,
            compatible = true, here = true,
        ) ?: throw AssertionError("nothing asked")
        asked = PlayState.Prechecking(FILM_TITLE, "2008", precheck)
        page = Page.Precheck
        val headline = "Kitchen display shows HD"
        waitFor(hasText(headline))
        assertTrue("9: the sheet named by its headline", exists(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, headline)))
        val head = node(hasText(headline) and isHeading())
        val card = node(hasText(GERMAN_4K))
        assertEquals(
            "10: the reason, then the release's name, one stop",
            listOf("⚠ 2160p (4K). Kitchen display shows up to 1080p, so it may not play this file.", GERMAN_4K), card.config[SemanticsProperties.Text].map { it.text },
        )
        val audio = node(hasText("Kitchen display will play the German audio: Cast devices play a file’s first sound track."))
        val subtitles = node(hasText("Subtitles don’t show on Cast devices"))
        val answers = CastPrecheck.Button.entries.map { node(hasText(it.words) and hasClickAction()) }
        answers.forEach { assertEquals("13-15: \"${it.config[SemanticsProperties.Text].single()}, button\"", Role.Button, it.role()) }
        val order = listOf(head, card, audio, subtitles) + answers
        assertEquals("9-15: top to bottom", order.map { it.boundsInRoot.top }.sorted(), order.map { it.boundsInRoot.top })

        // 16-18, a list ranked for Kitchen display (CsPhoneTalkBackC2 4), with 40 Mbps the maximum when casting.
        container.settings.updatePlayback { it.copy(castMaxBitrateMbps = 40) }
        val forKitchen = container.rankingContext(CastRanking(kitchen.name, CastModel.Chromecast.profile(null))).copy(runtimeMinutes = 118)
        val items = listOf(release(ORCHID, 10.4), release(LUMEN, 24.7), release(HALYARD, 38.6)).associateBy { it.guid }
        val sorted = ReleaseRanker().rank(items.values.map { ReleaseFacts(it.guid, it.title, it.sizeBytes) }, forKitchen).map { RankedItem(items.getValue(it.facts.id), it) }
        val (playable, hidden) = sorted.partition { it.ranked.playable }
        ranked = ResultGroup("lantern field|movie", GroupKind.Movie, null, emptyList(), null, 2008, playable, hidden)
        page = Page.Ranked
        val ranking = node(hasText(rankedForLine(kitchen.name)))
        assertNull("16: read once, in its place, not as news", ranking.config.getOrNull(SemanticsProperties.LiveRegion))
        val best = node(hasText(ORCHID, substring = true) and hasClickAction())
        assertTrue("16: before the releases", ranking.boundsInRoot.bottom <= best.boundsInRoot.top)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(LUMEN, substring = true))
        val vc1 = node(hasText(LUMEN, substring = true) and hasClickAction())
        assertTrue("17: shown in its card", vc1.config[SemanticsProperties.Text].any { it.text == "⚠ VC-1: no picture on Kitchen display" })
        assertTrue("17: read in its card as words", vc1.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Warning: VC-1, no picture on Kitchen display"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(HALYARD, substring = true))
        val flac = node(hasText(HALYARD, substring = true) and hasClickAction())
        assertTrue("18: shown in its card", flac.config[SemanticsProperties.Text].any { it.text == "⚠ Above your 40 Mbps for casting" })
        assertTrue(
            "18: read in its card as the banner says its numbers",
            flac.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Warning: above your 40 megabits per second for casting"),
        )

        // 19-20, the slow-cast banner (CsPhoneTalkBackC2 5): 19 of 31 Mbps reached Kitchen display over its Wi-Fi.
        val slow = CastDip.Slow(DipDetector.Dip(19_000_000, 31_000_000), CastDip.Cause.WiFi)
        banner = CastSlowStream.shown(slow, kitchen.name, max = null, castMax = 40)
        page = Page.Banner
        val spoken = "Streaming is slower than this video needs. 19 of 31 megabits per second. The Wi-Fi to Kitchen display is the slow part. " +
            "Lowering Maximum bitrate when casting to 15 megabits per second, in Settings, Quality and languages, picks smaller releases next time."
        val words = node(hasContentDescription(spoken))
        assertEquals("19: read once as it shows, the numbers in words", LiveRegionMode.Polite, words.live())
        assertEquals("20: \"Try a smaller release, button\"", Role.Button, node(hasText(SlowStream.TRY_SMALLER) and hasClickAction()).role())
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_641
        const val SHOW_TITLE = "Harbor Lights"
        const val EPISODE_LINE = "S02E04 · Low Water"
        const val INDEXER = "cast-talkback-device-test"
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"
        const val PART = 32_000

        /** An invented TV's key: 64 hex digits nothing real has. */
        const val TV_KEY = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"

        /** 32:10 of the fake device's 52 minutes, where the boards' cast is. */
        const val PLACE = 1_930_000L
        const val LENGTH = 3_120_000L

        /** The second walk's LOAD there, its content id. */
        const val LOAD = "cast-talkback-c2-load"
        const val FILM_TITLE = "Lantern Field"

        /** CsPhoneTalkBackC2's releases: the pre-check's German 2160p one, and the ranked list's three. */
        const val GERMAN_4K = "Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x264-WATTWURM"
        const val ORCHID = "Lantern.Field.2008.1080p.BluRay.DD5.1.x264-ORCHID"
        const val LUMEN = "Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN"
        const val HALYARD = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD"

        /** The platform session's tag for Media3's session with the id "cast". */
        const val CAST_TAG = "androidx.media3.session.id.cast"
    }
}

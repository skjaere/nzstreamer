package io.skjaere.usenet.app.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.casting.CAST_FORMAT_NOTE
import io.skjaere.usenet.app.casting.CAST_NOT_STARTED
import io.skjaere.usenet.app.casting.CastAction
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.cannotReach
import io.skjaere.usenet.app.casting.castingTo
import io.skjaere.usenet.app.casting.checkedBefore
import io.skjaere.usenet.app.casting.couldNotPlayThis
import io.skjaere.usenet.app.casting.fileTypeCannotPlay
import io.skjaere.usenet.app.casting.onDevice
import io.skjaere.usenet.app.casting.sameWifi
import io.skjaere.usenet.app.casting.startingOn
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starting a cast play (W26, W39, L57, L65, L82, L83, L84; CsPhoneStarting): the coordinator's own cases on a real device,
 * as L40 plays a film, with no mock NNTP server and no indexer asked. The device tests' own media is posted on a server in
 * the test's process ([LocalNntp]) and prepared into the store; the Cast device is a fake connected one and its side a
 * fake launcher ([FakeLauncher]), which says what the device does: Google's software is never started (L73). A cast
 * launch goes to the launcher and never to this phone's player; the sheet says where it plays and waits on "Starting on
 * Kitchen display"; a file type a Cast device can't open is refused before anything is sent, or skipped in a pick of the
 * phone's own; each failure says its words and each button does what it says, Play on this phone starting the player
 * here. The film, its releases and the device are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastPlayDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private val local = LocalNntp()
    private val film = WatchTarget(WatchKind.Movie, FILM_TITLE, movieTmdbId = FILM)
    private val fixtures = GapFixtures(local, INDEXER, film)
    private val launcher = FakeLauncher()
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    /** The device as the router's play names it, with its Cast id and model (L153). */
    private val device = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model)

    /** The NZBs the coordinator fetched from an indexer: none, as L40's play. */
    private val grabs = AtomicInteger()
    private var tried: List<TriedRelease>? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        assumeTrue("no usable Usenet provider saved, so nothing can play", container.noUsableProvider() == null)
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        tried = container.library.triedReleaseEntries()
        container.library.clearTriedReleases()
        forgetFilmsRelease()
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        compose.setContent {
            AppTheme {
                Column {
                    PlayMiniBar(container.playback)
                    PlaySheetHost(container.playback, onSearch = {}, onProviders = {})
                }
            }
        }
        compose.waitUntil(10_000) { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
    }

    @After
    fun restore() = runBlocking<Unit> {
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        PlaybackCoordinator.nzbSource = realSource
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>() }
            .forEach { player -> onMainThread { player.finish() } }
        local.close()
        container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = FILM_TITLE, updatedAt = 0), 0, DURATION_MS)
        forgetFilmsRelease()
        tried?.let { container.library.restoreTriedReleases(it) }
    }

    /** The film's release remembered by a test before, whose NZB names articles of a server that is gone. */
    private suspend fun forgetFilmsRelease() {
        val key = WatchKey.movie(FILM)
        container.library.lastRelease(key)?.let { container.library.forgetRelease(key, it) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 30_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(words: String) {
        waitFor(hasText(words) and hasClickAction())
        compose.onNode(hasText(words) and hasClickAction()).performClick()
    }

    private fun state(): PlayState = container.playback.state.value

    private fun failed(): PlayState.Failed {
        compose.waitUntil(60_000) { state() is PlayState.Failed }
        return state() as PlayState.Failed
    }

    /** Waits until the launcher has been handed [count] launches, and returns the last; [failed] ends the wait, a play's failure. */
    private fun loaded(count: Int = 1, failed: PlayState.Failed? = null): PlayerLaunch {
        compose.waitUntil(60_000) { launcher.loads.size >= count || state().let { it is PlayState.Failed && it != failed } }
        assertEquals("handed to the device; the sheet says ${state()}", count, launcher.loads.size)
        return launcher.loads.last()
    }

    /** Whether this phone's player was sent a launch: none comes for a cast. */
    private suspend fun launchedHere(): PlayerLaunch? = withTimeoutOrNull(1_000) { container.playback.launches.first() }

    /** The film's release on the test's server, prepared and in the store as the one that played it before (L40). */
    private suspend fun rememberedFilm(): GapFixtures.Release {
        val release = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "cast-film", MKV_TITLE) { emptySet() }
        container.library.recordProgress(
            WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = FILM_TITLE, updatedAt = 0), 0, DURATION_MS,
            fixtures.saved(MKV, release.prepared).toRemembered(),
        )
        return release
    }

    private val target = MovieTarget(FILM, FILM_TITLE, 2026, null, 6)

    /** The Movie page's Play with Kitchen display chosen: the router casts it. */
    private fun playFilm() = onMainThread { container.playRouter.playMovie(target) }

    /** Releases [named] (guid to file name) on the test's server, their NZBs served to the play as an indexer's would be. */
    private suspend fun served(vararg named: Pair<String, String>): List<RankedItem> {
        val nzbs = named.associate { (guid, file) -> "$INDEXER/$guid" to fixtures.release(file, fixtures.media("chapters.mkv"), PART, "cast-$guid", guid) { emptySet() }.nzb }
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> nzbs[candidate.id] ?: grab.fetch(candidate) } }
        return named.map { (guid, _) -> fixtures.candidate(guid, guid) }
    }

    /** A play of [candidates] through the router, as a release list's Play is with Kitchen display chosen. */
    private fun chosen(candidates: List<RankedItem>, chosen: Boolean): Deferred<PlayOutcome> = onMainThread {
        container.playRouter.chosen(PlayRequest(FILM_TITLE, "2026", candidates, FilePicker.Largest, film, chosen = chosen))
    }

    @Test
    fun aCastPlayGoesToTheDeviceAndTheSheetWaitsUntilItPlays() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        val launch = loaded()
        assertEquals(device, launch.cast)
        assertEquals(MKV, launch.release.guid)
        val (to, said) = launcher.started.single()
        assertEquals(device, to)
        assertEquals("a TMDB film's own title", FILM_TITLE, said.title)
        // CsPhoneStarting: where it plays under the title, the phone's steps, then the device's, and no "Plays on this device".
        waitFor(hasText(startingOn(kitchen.name)))
        assertTrue(exists(hasText(castingTo(kitchen.name))))
        assertFalse(exists(hasText("✓ Plays on this device")))
        assertEquals(PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts), container.playOn.target.value)
        val running = state() as PlayState.Running
        assertTrue("the device's step running: ${running.castStep}", running.castStep is StepStatus.Running)
        // Minimised, the bar says where and that it is starting there.
        onMainThread { container.playback.minimize() }
        waitFor(hasText(onDevice(kitchen.name, "Starting…")))
        onMainThread { container.playback.expand() }
        assertNull("nothing for this phone's player", launchedHere())
        assertEquals("no indexer asked", 0, grabs.get())
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertFalse(exists(hasText(startingOn(kitchen.name))))
        assertNull(launchedHere())
        assertEquals("the play goes on there", 0, launcher.stops.get())
    }

    /** L82: Cancel in the cast step stops what the device was loading; the session lives on, the device still chosen. */
    @Test
    fun cancelWhileTheDeviceStartsStopsItsLoad() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        loaded()
        tap("Cancel")
        compose.waitUntil(10_000) { launcher.stops.get() > 0 }
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals("stopped once", 1, launcher.stops.get())
        assertEquals("the Cast session isn't ended", 0, connector.ends.get())
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        assertNull(launchedHere())
    }

    /** L57, L83: a release chosen by hand of a type a Cast device can't play fails before anything is sent there. */
    @Test
    fun aHandPickedAviFailsAsAFileTypeBeforeAnyLoad() = runBlocking<Unit> {
        val outcome = chosen(served(AVI to "copper-crown.avi"), chosen = true)
        val failed = failed()
        assertEquals(CastFailKind.FileType, failed.cast?.kind)
        waitFor(hasText(fileTypeCannotPlay(kitchen.name)))
        assertTrue(exists(hasText("✕ AVI file. Cast devices play MKV, MP4, WebM and MOV.")))
        assertTrue(exists(hasText(AVI)))
        assertTrue(exists(hasText(checkedBefore(kitchen.name))))
        assertEquals("nothing sent to the device", 0, launcher.loads.size)
        assertEquals("its service stopped", 1, launcher.stops.get())
        val said = (withTimeoutOrNull(5_000) { outcome.await() } as? PlayOutcome.Failed)?.reason(AVI)
        assertEquals("the release list hears why", "AVI file. Cast devices play MKV, MP4, WebM and MOV.", said)
        // Try another release goes back to the list it was chosen on, which says why.
        tap("Try another release")
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(0, launcher.loads.size)
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /** L57, L97: in a pick of the phone's own, an AVI is a failed attempt like an incomplete release, and the next one casts. */
    @Test
    fun anAutomaticPickSkipsAnAviWithItsNotice() = runBlocking<Unit> {
        chosen(served(AVI to "copper-crown.avi", MKV to "copper-crown.mkv"), chosen = false)
        val launch = loaded()
        assertEquals(MKV, launch.release.guid)
        waitFor(hasText("AVI file can't be cast · trying release 2 of 2"))
        assertEquals(0, grabs.get())
    }

    /** CsPhoneStarting: the device couldn't play it; Try another release plays the request's next one on the same device. */
    @Test
    fun aReleaseTheDeviceCouldNotPlayOffersAnotherOnTheSameDevice() = runBlocking<Unit> {
        val outcome = chosen(served(MKV to "copper-crown.mkv", SECOND to "copper-crown-2.mkv"), chosen = false)
        assertEquals(MKV, loaded().release.guid)
        launcher.fails(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true))
        failed()
        waitFor(hasText(couldNotPlayThis(kitchen.name)))
        assertTrue(exists(hasText("✕ $DECODE")))
        assertTrue(exists(hasText(CAST_FORMAT_NOTE)))
        assertTrue(exists(hasText("Play on this phone") and hasClickAction()))
        assertEquals(DECODE, (withTimeoutOrNull(5_000) { outcome.await() } as? PlayOutcome.Failed)?.reason(MKV))
        val shown = state() as PlayState.Failed
        tap("Try another release")
        val next = loaded(2, failed = shown)
        assertEquals(SECOND, next.release.guid)
        assertEquals("still on Kitchen display", device, next.cast)
        assertEquals("its service started again", 2, launcher.started.size)
    }

    /** W39: can't reach says what to check, its steps ending in no request, and Try again loads the same launch again. */
    @Test
    fun aDeviceThatCannotReachThePhoneIsAskedAgain() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        val first = loaded()
        launcher.fails(CastLoadFailure(CastFailKind.CannotReach))
        val shown = failed()
        waitFor(hasText(cannotReach(kitchen.name)))
        assertTrue(exists(hasText(sameWifi(kitchen.name))))
        assertTrue(exists(hasText("no request came")))
        assertTrue(exists(hasText(startingOn(kitchen.name))))
        assertFalse("not another release first", exists(hasText("Try another release")))
        tap("Try again")
        assertEquals("the same launch, the same device", first, loaded(2, failed = shown))
        waitFor(hasText(startingOn(kitchen.name)))
        assertTrue(state() is PlayState.Running)
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
    }

    /**
     * L60, W36: the device's session ends while its failure shows (the receiver closes an idle session after five minutes,
     * another sender takes it, another device is chosen): the sheet offers it nothing more, only this phone and Close, and a
     * press made as it went sends nothing there.
     */
    @Test
    fun aFailureOffersTheDeviceNothingOnceItIsNoLongerChosen() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        val sent = loaded()
        launcher.fails(CastLoadFailure(CastFailKind.CannotReach))
        failed()
        waitFor(hasText("Try again") and hasClickAction())
        connector.ended(kitchen)
        compose.waitUntil(10_000) { container.playOn.chosen.value == PlayChoice.Phone }
        compose.waitUntil(10_000) { !exists(hasText("Try again")) }
        assertTrue(exists(hasText("Play on this phone") and hasClickAction()))
        assertTrue(exists(hasText("Close") and hasClickAction()))
        // Try again pressed as the device went: the sheet closes, and nothing more goes to the device.
        onMainThread { container.playback.act(CastAction.CastAgain(sent)) }
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals("no other start for the device", 1, launcher.started.size)
        assertEquals(1, launcher.loads.size)
    }

    /** L83, L85: Play on this phone ends the cast, chooses This phone and plays the launch here, in this phone's player. */
    @Test
    fun playOnThisPhoneEndsTheCastAndPlaysHere() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        val sent = loaded()
        launcher.fails(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true))
        failed()
        tap("Play on this phone")
        val here = withTimeoutOrNull(10_000) { container.playback.launches.first() } ?: throw AssertionError("nothing for this phone's player")
        assertEquals(sent.copy(cast = null), here)
        compose.waitUntil(10_000) { connector.ends.get() == 1 }
        compose.waitUntil(10_000) { container.playOn.chosen.value == PlayChoice.Phone }
        assertEquals(PlayState.Idle, state())
        // What MainActivity does with it: the player plays it here.
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, here)).use { scenario ->
            compose.waitUntil(30_000) { scenario.read { it.player?.playbackState == Player.STATE_READY } }
        }
    }

    /** L84: a new cast play leaves the one playing there until its own load; the coordinator stops neither. */
    @Test
    fun aNewCastPlayLeavesTheOldOneUntilItsLoad() = runBlocking<Unit> {
        rememberedFilm()
        playFilm()
        loaded()
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        playFilm()
        compose.waitUntil(10_000) { launcher.started.size == 2 }
        loaded(2)
        assertEquals("nothing stopped on the device", 0, launcher.stops.get())
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(0, launcher.stops.get())
    }

    /** L84: the missing-parts Try another going back to the release that still plays there loads nothing. */
    @Test
    fun theMissingPartsWayBackLoadsNothing() = runBlocking<Unit> {
        val playing = fixtures.release("dip.mkv", fixtures.media("dip.mkv"), PART, "cast-playing", MKV) { emptySet() }
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> if (candidate.id == "$INDEXER/$SECOND") throw FetchException("HTTP 404") else grab.fetch(candidate) } }
        val launch = fixtures.launch(playing.prepared, MKV, 0, listOf(fixtures.candidate(MKV, MKV), fixtures.candidate(SECOND, SECOND))).copy(cast = device)
        onMainThread { container.playback.tryAnotherForMissingParts(launch, AT_MS, listOf(local.provider)) }
        compose.waitUntil(10_000) { launcher.started.size == 1 }
        compose.waitUntil(60_000) { launcher.stops.get() > 0 || launcher.loads.isNotEmpty() || state() is PlayState.Failed }
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals("the release left still plays there", 0, launcher.loads.size)
        assertEquals("the play started for it ends", 1, launcher.stops.get())
        assertNull(launchedHere())
    }

    /** L85, L97: Android refusing the service's start fails the play before anything is searched or fetched. */
    @Test
    fun aServiceAndroidRefusesFailsThePlayAtOnce() = runBlocking<Unit> {
        rememberedFilm()
        launcher.refuse = true
        playFilm()
        val failed = failed()
        assertEquals(CastFailKind.NotStarted, failed.cast?.kind)
        waitFor(hasText(CAST_NOT_STARTED))
        assertEquals(0, launcher.loads.size)
        assertTrue(exists(hasText("Play on this phone") and hasClickAction()))
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900095
        const val FILM_TITLE = "Copper Crown"
        const val INDEXER = "cast-play-device-test"
        const val MKV = "Copper.Crown.2026.1080p.WEB-DL.H.264-CAST"
        const val MKV_TITLE = MKV
        const val SECOND = "Copper.Crown.2026.720p.WEB.h264-CAST"
        const val AVI = "Copper.Crown.2026.DVDRip.XviD-CAST"
        const val DECODE = "The device's player stopped with a decoding error"
        const val PART = 32_000
        const val DURATION_MS = 360_000L
        const val AT_MS = 6_000L
    }
}

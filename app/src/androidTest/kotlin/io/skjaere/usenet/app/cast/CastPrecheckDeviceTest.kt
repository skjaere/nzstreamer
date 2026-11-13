package io.skjaere.usenet.app.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.views
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/**
 * The pre-check (W51, W55, W63, L130, L142, L152; CsPhonePrecheck) on a real device, as CastPlayDeviceTest plays a film:
 * the films are the test's own, posted on a server in its process ([LocalNntp]), so this phone's probe reads their real
 * tracks; the Cast device is a fake connected one, a first Chromecast by its model, and its side a fake launcher
 * ([FakeLauncher]): Google's software is never started (L73). A release picked by hand that the device can't fully play
 * asks first, in the design's words, with nothing sent to the device; each answer does what it says: Cast anyway loads it
 * there, Play on this phone plays it here at once, Play a compatible release plays the list's next there, Cancel stops.
 * One the device plays says its audio and subtitles lines on the starting sheet instead, with nothing to ask. In the
 * player, the film playing here, the device's row asks the same question over the film before anything connects.
 *
 * The films, made with ffmpeg 8.0.1 (libx264 0.165.3222) in `app/src/androidTest/fixtures`, two seconds of 160x90 test
 * pattern each, reruns giving the same bytes: `precheck-dts.mkv` (197 KB, `84c3cab7…2a46`), DTS 5.1 its only sound, from
 * `ffmpeg -f lavfi -i testsrc=duration=2:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000
 * -map 0:v -map 1:a -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow -crf 45 -g 10 -threads 1 -c:a dca
 * -strict -2 -ac 6 -b:a 768k -metadata:s:a:0 language=eng -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a
 * +bitexact precheck-dts.mkv`; and `precheck-german.mkv` (53 KB, `a9ec081c…a00a`), German AC-3 5.1 first, English AC-3
 * stereo second and English subtitles (tracks-en.srt), from `ffmpeg -f lavfi -i testsrc=duration=2:size=160x90:rate=10
 * -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000 -f lavfi -i sine=frequency=660:duration=2:sample_rate=48000
 * -i tracks-en.srt -map 0:v -map 1:a -map 2:a -map 3:s -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow
 * -crf 45 -g 10 -threads 1 -c:a ac3 -b:a 96k -ac:a:0 6 -ac:a:1 2 -c:s srt -metadata:s:a:0 language=ger -metadata:s:a:1
 * language=eng -metadata:s:s:0 language=eng -disposition:a:0 default -disposition:a:1 0 -disposition:s:0 0 -write_crc32 0
 * -fflags +bitexact -flags:v +bitexact -flags:a +bitexact precheck-german.mkv`. The HEVC film is core:player's
 * hevc-level62.mkv, which this phone plays and a first Chromecast doesn't; the player's is PlayerCastDeviceTest's
 * `move-dts.mkv`, ninety seconds long, DTS 5.1 its only sound. The film, its releases and the device are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastPrecheckDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private val local = LocalNntp()
    private val film = WatchTarget(WatchKind.Movie, FILM_TITLE, movieTmdbId = FILM)
    private val fixtures = GapFixtures(local, INDEXER, film)
    private val launcher = FakeLauncher()
    private val routes = FakeRoutes()
    private val connector = FakeConnector()

    /** A first Chromecast by its model (L128): H.264 up to 1080p, no HEVC. */
    private val kitchen = castRoute("Kitchen display", model = "Chromecast")
    private val device = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model)
    private var tried: List<TriedRelease>? = null

    /** Every sheet the play showed, in order, as it showed them: what a test reads to know a question never came. */
    private val shown = CopyOnWriteArrayList<PlayState>()
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        // English first, and subtitles when the audio isn't: what the lines are said by. CastFakes.reset puts them back.
        container.settings.updatePlayback { it.copy(audioLanguages = listOf("en"), subtitlesWhenForeign = true) }
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        watching.launch { container.playback.state.collect { shown += it } }
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
        watching.cancel()
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        PlaybackCoordinator.nzbSource = realSource
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>() }
            .forEach { player -> onMainThread { player.finish() } }
        local.close()
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

    /** Waits for the pre-check's question, and returns it. */
    private fun asked(): PlayState.Prechecking {
        compose.waitUntil(60_000) { state() is PlayState.Prechecking || state() is PlayState.Failed }
        return state() as? PlayState.Prechecking ?: throw AssertionError("no pre-check: the sheet says ${state()}")
    }

    /**
     * Waits until the launcher has been handed a launch, and returns it; a play's failure ends the wait, but not
     * [failed], the failure's sheet a button started the play from, which shows on until the play's job, on its own
     * thread, takes the sheet. Told apart by identity: a failure of Cast anyway's own, the same release on the same
     * device, would equal it.
     */
    private fun loaded(failed: PlayState.Failed? = null): PlayerLaunch {
        compose.waitUntil(60_000) { launcher.loads.isNotEmpty() || state().let { it is PlayState.Failed && it !== failed } }
        assertEquals("handed to the device; the sheet says ${state()}", 1, launcher.loads.size)
        return launcher.loads.single()
    }

    /** Releases [named] (guid to its file in the test's assets) on the test's server, their NZBs served as an indexer's would be. */
    private suspend fun served(vararg named: Pair<String, String>): List<RankedItem> {
        val nzbs = named.associate { (guid, file) -> "$INDEXER/$guid" to fixtures.release(file, fixtures.media(file), PART, "precheck-${guid.hashCode()}", guid) { emptySet() }.nzb }
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> nzbs[candidate.id] ?: grab.fetch(candidate) } }
        return named.map { (guid, _) -> fixtures.candidate(guid, guid) }
    }

    /** [picked] played by hand from a list holding [others] too, as a release list's Play is with Kitchen display chosen. */
    private fun picked(picked: RankedItem, others: List<RankedItem> = emptyList()): Deferred<PlayOutcome> = onMainThread {
        container.playRouter.chosen(PlayRequest(FILM_TITLE, YEAR, listOf(picked), FilePicker.Largest, film, rememberedFirst = false, chosen = true, alternatives = others))
    }

    /** Whether this phone's player was sent a launch. */
    private suspend fun launchedHere(): PlayerLaunch? = withTimeoutOrNull(1_000) { container.playback.launches.first() }

    /** CsPhonePrecheck frame 1, then Cast anyway: the device is sent the file all the same (Terms 3.4.5). */
    @Test
    fun aHandPickedFilmWithOnlyDtsSoundAsksFirstAndCastAnywayLoadsIt() = runBlocking<Unit> {
        val (dts, german) = served(DTS_REMUX to "precheck-dts.mkv", GERMAN_DL to "precheck-german.mkv")
        val outcome = picked(dts, others = listOf(german))
        val question = asked()
        waitFor(hasText(NO_SOUND))
        assertTrue(exists(hasText(FILM_TITLE)))
        assertTrue(exists(hasText(YEAR)))
        assertTrue("its card", exists(hasText("⚠ DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.")))
        assertTrue("the release's name", exists(hasText(DTS_REMUX)))
        assertTrue(exists(hasText("Checked before anything was sent to Kitchen display.")))
        assertFalse("no audio line: no sound plays", exists(hasText(GERMAN_LINE)))
        assertFalse(exists(hasText(NO_SUBTITLES)))
        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        // CFU-1: Play on this phone only where this phone plays DTS itself, as its own probe says: most phones don't.
        val dtsHere = MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_DTS, false, false).isNotEmpty()
        assertEquals("this phone's own verdict on the file", dtsHere, question.precheck.here)
        assertEquals(listOfNotNull("Play a compatible release", "Play on this phone".takeIf { dtsHere }, "Cast anyway", "Cancel"), buttons.filter { it in BUTTONS })
        // TalkBack: the sheet is a pane named by its headline, which is a heading, above the card.
        assertTrue("the pane named by the headline", exists(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, NO_SOUND)))
        assertTrue("the headline a heading", exists(hasText(NO_SOUND) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)))
        val headline = compose.onNode(hasText(NO_SOUND)).fetchSemanticsNode().boundsInRoot.top
        val card = compose.onNode(hasText(DTS_REMUX)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("the headline before the card", headline < card)
        assertTrue("nothing sent to the device yet", launcher.loads.isEmpty())
        tap("Cast anyway")
        val launch = loaded()
        assertEquals(DTS_REMUX, launch.release.guid)
        assertEquals(device, launch.cast)
        waitFor(hasText("Starting on Kitchen display"))
        assertNull("nothing for this phone's player", launchedHere())
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(PlayOutcome.Launched, withTimeoutOrNull(5_000) { outcome.await() })
    }

    /** L130: Play on this phone chooses This phone and plays the release here at once, its NZB in the store; nothing goes there. */
    @Test
    fun playOnThisPhonePlaysTheFileHereAtOnce() = runBlocking<Unit> {
        val (hevc) = served(HEVC_WEB to "hevc-level62.mkv")
        picked(hevc)
        asked()
        waitFor(hasText("No picture on Kitchen display"))
        // A first Chromecast's own picture limit names the device (L149).
        assertTrue(exists(hasText("⚠ HEVC video. Kitchen display can’t play it.")))
        assertFalse("no list to play a compatible one from", exists(hasText("Play a compatible release")))
        tap("Play on this phone")
        val here = withTimeoutOrNull(30_000) { container.playback.launches.first() } ?: throw AssertionError("nothing for this phone's player; the sheet says ${state()}")
        assertEquals(HEVC_WEB, here.release.guid)
        assertNull("a play here", here.cast)
        assertTrue("its NZB in the store", container.store.stored(here.release.nzbId) != null)
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
        compose.waitUntil(10_000) { connector.ends.get() == 1 }
        assertTrue("nothing sent to the device", launcher.loads.isEmpty())
        assertEquals("what started for the device stopped", 1, launcher.stops.get())
        // What MainActivity does with it: the player plays it here.
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, here)).use { scenario ->
            compose.waitUntil(30_000) { scenario.read { it.player?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) } }
        }
    }

    /** L152: Play a compatible release plays the list's next release the device plays, on the device, as an automatic pick. */
    @Test
    fun playACompatibleReleasePlaysTheListsNextOnTheDevice() = runBlocking<Unit> {
        val (dts, german) = served(DTS_REMUX to "precheck-dts.mkv", GERMAN_DL to "precheck-german.mkv")
        val outcome = picked(dts, others = listOf(german))
        asked()
        tap("Play a compatible release")
        val launch = loaded()
        assertEquals(GERMAN_DL, launch.release.guid)
        assertEquals("still on Kitchen display", device, launch.cast)
        waitFor(hasText("Starting on Kitchen display"))
        assertEquals("one start: the same play went on", 1, launcher.started.size)
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(PlayOutcome.Launched, withTimeoutOrNull(5_000) { outcome.await() })
    }

    /** W63: a release with nothing the device can't play asks nothing; its two lines show on the starting sheet, and it loads. */
    @Test
    fun aGermanFirstFilmSaysItsTwoLinesOnTheStartingSheetAndLoads() = runBlocking<Unit> {
        val (german) = served(GERMAN_DL to "precheck-german.mkv")
        picked(german)
        val launch = loaded()
        assertEquals(GERMAN_DL, launch.release.guid)
        waitFor(hasText("Starting on Kitchen display"))
        assertTrue(exists(hasText("Casting to Kitchen display")))
        assertTrue("the audio line under Casting to", exists(hasText(GERMAN_LINE)))
        assertTrue("the subtitles line", exists(hasText(NO_SUBTITLES)))
        assertFalse("never asked", shown.any { it is PlayState.Prechecking })
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
    }

    /**
     * L149, CFT-3: an automatic play (a page's Play, no question asked) whose every release its device can't fully play
     * says so with each one's reason, and Cast anyway sends the best of them there all the same (Terms 3.4.5): to Kitchen
     * display, from the play's own job for it, the verdict not asked again.
     */
    @Test
    fun anAutomaticPlayNoReleaseOfWhichPlaysFullyCastsTheBestAnyway() = runBlocking<Unit> {
        val (dts) = served(DTS_REMUX to "precheck-dts.mkv")
        val request = PlayRequest(FILM_TITLE, YEAR, listOf(dts), FilePicker.Largest, film, rememberedFirst = false)
        onMainThread { container.playback.play(request, PlayOrigin(cast = device)) }
        compose.waitUntil(60_000) { state() is PlayState.Failed }
        waitFor(hasText("This release doesn't play fully on Kitchen display"))
        assertTrue("its card", exists(hasText("✕ No sound", substring = true)))
        assertTrue(exists(hasText("Checked before anything was sent to Kitchen display.")))
        assertFalse("never asked: an automatic pick", shown.any { it is PlayState.Prechecking })
        assertTrue("nothing sent to the device yet", launcher.loads.isEmpty())
        val allFailed = state() as PlayState.Failed
        tap("Cast anyway")
        val launch = loaded(failed = allFailed)
        assertEquals(DTS_REMUX, launch.release.guid)
        assertEquals(device, launch.cast)
        assertNull("nothing for this phone's player", launchedHere())
        launcher.plays()
        compose.waitUntil(10_000) { state() == PlayState.Idle }
    }

    /**
     * CFU-1 on the all-failed sheet (NF): the DTS film picked by hand, then Play a compatible release, whose one release
     * the device can't play either (its name says nothing of its DTS). Play on this phone there would ask again for the
     * film picked, here, so it is offered only where this phone plays it, by its own probe (most phones have no DTS
     * decoder): elsewhere ending the cast for a play that fails at once. Cast anyway and Close stay.
     */
    @Test
    fun afterACompatibleReleaseTheAllFailedSheetOffersThisPhoneOnlyWhereItPlaysThePickedFilm() = runBlocking<Unit> {
        val (dts, plain) = served(DTS_REMUX to "precheck-dts.mkv", PLAIN to "precheck-dts.mkv")
        picked(dts, others = listOf(plain))
        val question = asked()
        val dtsHere = MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_DTS, false, false).isNotEmpty()
        assertEquals("this phone's own verdict on the film picked", dtsHere, question.precheck.here)
        tap("Play a compatible release")
        compose.waitUntil(60_000) { state() is PlayState.Failed }
        waitFor(hasText("This release doesn't play fully on Kitchen display"))
        assertTrue("the compatible release's card", exists(hasText("✕ No sound", substring = true)))
        assertTrue("nothing sent to the device", launcher.loads.isEmpty())
        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        assertEquals(listOfNotNull("Play on this phone".takeIf { dtsHere }, "Cast anyway", "Close"), buttons.filter { it in FAILED_BUTTONS })
        assertEquals("still chosen", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /**
     * CFU-1 on the file type's sheet: the DTS film picked by hand, then Play a compatible release, whose one release is a
     * file no Cast device opens (a .ts, its name saying nothing of it), refused before anything is sent. Play on this
     * phone there would ask again for the film picked, here, so it is offered only where this phone plays it, by its own
     * probe; Close stays.
     */
    @Test
    fun afterACompatibleReleaseTheFileTypeSheetOffersThisPhoneOnlyWhereItPlaysThePickedFilm() = runBlocking<Unit> {
        val nzbs = mapOf(
            "$INDEXER/$DTS_REMUX" to fixtures.release("precheck-dts.mkv", fixtures.media("precheck-dts.mkv"), PART, "precheck-ts-dts", DTS_REMUX) { emptySet() }.nzb,
            "$INDEXER/$TS_PLAIN" to fixtures.release("lantern-field.ts", fixtures.media("precheck-dts.mkv"), PART, "precheck-ts-plain", TS_PLAIN) { emptySet() }.nzb,
        )
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> nzbs[candidate.id] ?: grab.fetch(candidate) } }
        picked(fixtures.candidate(DTS_REMUX, DTS_REMUX), others = listOf(fixtures.candidate(TS_PLAIN, TS_PLAIN)))
        val question = asked()
        val dtsHere = MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_DTS, false, false).isNotEmpty()
        assertEquals("this phone's own verdict on the film picked", dtsHere, question.precheck.here)
        tap("Play a compatible release")
        compose.waitUntil(60_000) { state() is PlayState.Failed }
        waitFor(hasText("This file type can't play on Kitchen display"))
        assertTrue("nothing sent to the device", launcher.loads.isEmpty())
        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        assertEquals(listOfNotNull("Play on this phone".takeIf { dtsHere }, "Close"), buttons.filter { it in FAILED_BUTTONS })
        assertEquals("still chosen", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /** Cancel (Back and a swipe down too) stops the play: nothing goes to the device, and what started for it stops. */
    @Test
    fun cancelStopsThePlay() = runBlocking<Unit> {
        val (dts) = served(DTS_REMUX to "precheck-dts.mkv")
        val outcome = picked(dts)
        asked()
        assertFalse("no list to play a compatible one from", exists(hasText("Play a compatible release")))
        tap("Cancel")
        compose.waitUntil(10_000) { state() == PlayState.Idle }
        assertEquals(PlayOutcome.Stopped, withTimeoutOrNull(5_000) { outcome.await() })
        assertTrue(launcher.loads.isEmpty())
        assertEquals(1, launcher.stops.get())
        assertNull(launchedHere())
        assertEquals("still chosen", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /**
     * L124 step 2 (item 44's player half; CsPhonePrecheck): the DTS film playing here in the player, Kitchen display's row
     * tapped in its list asks the same question over the film, before anything connects, the film playing on under it:
     * Play a compatible release (its list holds the German release), Play on this phone (it plays here now), Cast anyway
     * and Cancel. Cancel leaves the film playing here, nothing connected, started or sent.
     */
    @Test
    fun inThePlayerTheDtsFilmsRowAsksOverTheFilmAndCancelLeavesItPlaying() = runBlocking<Unit> {
        // The film plays here: This phone chosen, ending the session, and Kitchen display listed to cast to.
        container.playOn.choose(PlayChoice.Phone)
        compose.waitUntil(10_000) { connector.ends.get() == 1 }
        routes.found(kitchen)
        val launch = playerLaunch()
        val selected = connector.selected.size
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            compose.waitUntil(30_000) { scenario.read { it.player?.isPlaying == true } }
            scenario.onActivity { a ->
                val view = views(a.window.decorView).filterIsInstance<PlayerView>().single()
                view.controllerShowTimeoutMs = 0
                view.showController()
            }
            waitFor(SemanticsMatcher.keyIsDefined(CastButtonLook))
            compose.onNode(SemanticsMatcher.keyIsDefined(CastButtonLook)).performSemanticsAction(SemanticsActions.OnClick)
            val row = SemanticsMatcher("Kitchen display's row") { n ->
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("${kitchen.name}, Cast device") }
            } and hasClickAction()
            waitFor(row)
            compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
            waitFor(hasText(NO_SOUND))
            assertTrue("its card", exists(hasText("⚠ DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.")))
            assertTrue("the release's name", exists(hasText(DTS_REMUX)))
            assertTrue(exists(hasText("Checked before anything was sent to Kitchen display.")))
            assertTrue("the pane named by the headline", exists(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, NO_SOUND)))
            val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
            assertEquals("the design's four", BUTTONS.toList(), buttons.filter { it in BUTTONS })
            assertTrue("asked over the film, playing on", scenario.read { it.player?.isPlaying == true })
            assertEquals("nothing connected while it asks", selected, connector.selected.size)
            tap("Cancel")
            compose.waitUntil(10_000) { !exists(hasText(NO_SOUND)) }
            assertTrue("still playing here", scenario.read { it.player?.isPlaying == true })
        }
        assertEquals("nothing connected", selected, connector.selected.size)
        assertTrue("nothing started for the device", launcher.started.isEmpty())
        assertTrue("nothing sent", launcher.loads.isEmpty())
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
    }

    /**
     * The DTS film as the player plays it: posted as its release, its launch with the tracks this phone's probe reads of it
     * (L129), as the coordinator's launch carries them, kept with a request of it and the German release after it.
     */
    private suspend fun playerLaunch(): PlayerLaunch {
        val release = fixtures.release("move-dts.mkv", fixtures.media("move-dts.mkv"), PART, "precheck-player-dts", DTS_REMUX) { emptySet() }
        val launch = fixtures.launch(release.prepared, DTS_REMUX, 0, candidates = listOf(fixtures.candidate(DTS_REMUX, DTS_REMUX), fixtures.candidate(GERMAN_DL, GERMAN_DL)))
        val target = UsenetTarget(PlayCandidate(launch.release.key, DTS_REMUX), release.nzb, release.prepared, release.prepared.defaultPick!!, container.store)
        val tracks = when (val probed = container.trackProbe.probe(target)) {
            is ProbeResult.Playable -> probed.tracks
            is ProbeResult.NoPlayableAudio -> probed.tracks
            else -> null
        }
        return launch.copy(tracks = tracks ?: throw AssertionError("no tracks read of the DTS film"))
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900121
        const val FILM_TITLE = "Lantern Field"
        const val YEAR = "2008"
        const val INDEXER = "cast-precheck-device-test"
        const val DTS_REMUX = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val GERMAN_DL = "Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM"
        const val HEVC_WEB = "Lantern.Field.2008.1080p.WEB-DL.DD5.1.x265-LUMEN"

        /** A release whose name says nothing of its sound: the ranking warns of nothing, and its file is DTS alone. */
        const val PLAIN = "Lantern.Field.2008.1080p.BluRay.x264-PLAIN"

        /** A release whose name says nothing of its file, a .ts no Cast device opens. */
        const val TS_PLAIN = "Lantern.Field.2008.1080p.HDTV.x264-TSFILE"
        const val NO_SOUND = "No sound on Kitchen display"
        const val GERMAN_LINE = "Kitchen display will play the German audio: Cast devices play a file’s first sound track."
        const val NO_SUBTITLES = "Subtitles don’t show on Cast devices"
        val BUTTONS = setOf("Play a compatible release", "Play on this phone", "Cast anyway", "Cancel")
        val FAILED_BUTTONS = setOf("Play on this phone", "Cast anyway", "Close")
        const val PART = 32_000
    }
}

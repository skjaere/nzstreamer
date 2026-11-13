package io.skjaere.usenet.app.cast

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PictureInPicturePlays
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.casting.CastButton
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastFile
import io.skjaere.usenet.app.casting.CastOutcomes
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.CastStart
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.ADD_TV_TITLE
import io.skjaere.usenet.app.link.ButtonFace
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.CastIntroFocus
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnAsk
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.SOUND_ONLY_TEXT
import io.skjaere.usenet.app.link.THIS_PHONE
import io.skjaere.usenet.app.link.TITLE_PAGE_LINE
import io.skjaere.usenet.app.link.inventedTvName
import io.skjaere.usenet.app.link.soundOnlyTitle
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.overlayNodes
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerCast
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.serve.ServeCounters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The player's Cast button and Play & connect (W44, W45, W66, L115, L124; CsPhonePlayer). The button: right of ⓘ in the
 * top bar, 48 dp, with the controls, the titles taking what the buttons leave ([PlayerCast.titleRoom]); a tap opens Play
 * on's list as the player's own side panel, your TVs greyed with where they play from and This phone ticked (L116, W59),
 * and Back closes it, the focus back on the button for TalkBack; Add a TV and Privacy there open the app's own pages;
 * This phone there closes it as it chooses (L148), and calls off a move under way with no line.
 * Play & connect, the film playing at 0:32: a Cast row tapped pauses it and says "Moving to Kitchen display…" while the
 * device connects; once connected the same launch is loaded there from where it paused, and once the device plays it the
 * player closes, its place saved, and the remote opens; the device refusing the file as it opens (L102), or a connect
 * that fails, has the film play on here from there with its line, This phone chosen; an AVI says so at once and nothing
 * connects; a speaker asks first, over the film. A file Import opened moves through Import's cast at its place. Its
 * tracks judged by the device (item 44): a film the device can't fully play asks the pre-check first, over the film, its
 * Play a compatible release playing the list's next there from where the film paused; one whose first sound track isn't
 * in your first language moves with the line saying which the device will play.
 *
 * Over fakes alone (L143): the Cast group's devices and sessions (FakeRoutes, FakeConnector), and the device's side of a
 * cast play (FakeLauncher), whose load the test says the device plays or refuses; nothing of Google's starts. The films
 * are PictureInPicturePlays' (a server inside the test's process), and two of the test's own, whose tracks this phone's
 * probe reads as the coordinator's launch has them: ninety seconds of 160x90 test pattern with two seconds of sound,
 * made with ffmpeg 8.0.1 (libx264 0.165.3222) in `app/src/androidTest/fixtures`, reruns giving the same bytes.
 * `move-dts.mkv` (292 KB, `2c129cf2…9485`), DTS 5.1 its only sound: `ffmpeg -f lavfi -i
 * testsrc=duration=90:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000 -map 0:v -map 1:a
 * -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow -crf 45 -g 10 -threads 1 -c:a dca -strict -2 -ac 6 -b:a
 * 768k -metadata:s:a:0 language=eng -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact move-dts.mkv`;
 * `move-german.mkv` (147 KB, `6344bf15…c2ec2`), German AC-3 5.1 first and English AC-3 stereo: `ffmpeg -f lavfi -i
 * testsrc=duration=90:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000 -f lavfi -i
 * sine=frequency=660:duration=2:sample_rate=48000 -map 0:v -map 1:a -map 2:a -c:v libx264 -profile:v high -pix_fmt
 * yuv420p -preset veryslow -crf 45 -g 10 -threads 1 -c:a ac3 -b:a 96k -ac:a:0 6 -ac:a:1 2 -metadata:s:a:0 language=ger
 * -metadata:s:a:1 language=eng -disposition:a:0 default -disposition:a:1 0 -write_crc32 0 -fflags +bitexact -flags:v
 * +bitexact -flags:a +bitexact move-german.mkv`. The speaker question and the pre-check are windows of their own, which
 * only the compose rule reaches: its clock drives every composition here, so every wait is on it. The TV, the devices,
 * the films and their releases are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerCastDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val link = container.data.link
    private val plays = PictureInPicturePlays(INDEXER)
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()
    private val launcher = FakeLauncher()
    private val kitchen = castRoute("Kitchen display", model = "Chromecast Ultra")
    private val hallway = castRoute("Hallway speaker", videoOut = false)
    private val realExploring = CastIntroFocus.exploring
    private var saved: AppSettings? = null
    private var tv: FakeTv? = null

    @Before
    fun setUp() {
        // In touch mode, as a finger leaves a phone: where the focus goes back to differs for TalkBack and a keyboard.
        instrumentation.setInTouchMode(true)
        runBlocking {
            saved = container.settings.settings.first()
            plays.setUp()
        }
        CastFakes.started(routes, connector, announced)
        CastSeams.launcher = { launcher }
        routes.found(kitchen, hallway)
    }

    @After
    fun restore() {
        CastIntroFocus.exploring = realExploring
        mainActivities(*ALIVE).forEach { activity -> onMainThread { activity.finish() } }
        await("the app's activities closed") { mainActivities(*ALIVE).isEmpty().takeIf { it } }
        plays.close()
        tv?.let {
            it.close()
            runBlocking { link.forgetTv(it.key.id) }
        }
        container.playOn.choose(PlayChoice.Phone)
        // Last: the settings as they were before CastFakes chose to look for Cast devices.
        CastFakes.reset()
        runBlocking {
            saved?.let { container.settings.restore(it) }
            plays.forget()
        }
    }

    private fun launch(launch: PlayerLaunch): ActivityScenario<PlayerActivity> = ActivityScenario.launch(PlayerActivity.intent(context, launch))

    /**
     * L115 (CsPhonePlayer 1): the button is last in the top bar, right of ⓘ, 48 dp, Google's Cast icon at rest ("Play on:
     * this phone"), up and down with the controls; the titles have what the buttons leave, 48 dp less with it.
     */
    @Test
    fun theButtonIsRightOfFileDetailsWithTheControlsAndTheTitlesHaveWhatItLeaves() {
        launch(runBlocking { plays.launch("copper-tide-cast-button") }).use { scenario ->
            playing(scenario)
            controlsUp(scenario)
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            scenario.read { a ->
                val details = views(a.window.decorView).single { it.contentDescription == "File details" }
                val bar = details.parent as ViewGroup
                val cast = bar.getChildAt(bar.indexOfChild(details) + 1)
                assertTrue("a Compose view right of ⓘ, not $cast", cast is ComposeView)
                assertEquals("last in the top bar", bar.childCount - 1, bar.indexOfChild(cast))
                assertEquals("right of ⓘ", details.right, cast.left)
                assertEquals("48 dp wide", details.width, cast.width)
                assertEquals("48 dp tall", details.height, cast.height)
                val node = castNode(a)!!
                assertEquals(listOf("Play on: this phone"), node.config.getOrNull(SemanticsProperties.ContentDescription))
                assertEquals("Google's Cast icon at rest", ButtonFace.Look.Rest, node.config[CastButtonLook])
                // The titles have the rest: the bar's width less its padding (inside the insets), the back arrow and the
                // buttons, and their own margins.
                val density = a.resources.displayMetrics.density
                fun dp(px: Int) = Math.round(px / density)
                val pip = views(a.window.decorView).single { it.contentDescription == "Picture in picture" }.visibility == View.VISIBLE
                val room = PlayerCast.titleRoom(
                    dp(bar.width), pip, cast = true,
                    safeStart = dp(bar.paddingLeft) - PlayerCast.BAR_START_DP, safeEnd = dp(bar.paddingRight) - PlayerCast.BAR_END_DP,
                )
                val titles = bar.getChildAt(1)
                assertTrue("the titles are ${titles.width / density} dp wide, not $room", abs(titles.width / density - room) <= 2f)
            }
            assertTrue("Google's Cast software unstarted", castUnstarted())
            // It goes with the controls.
            scenario.onActivity { playerView(it).hideController() }
            await("the button gone with the controls") { scenario.read { a -> (castNode(a) == null).takeIf { it } } }
        }
    }

    /**
     * L124, L116, W59 (CsPhonePlayer 2): a tap opens Play on's list as the player's panel, titled Play on, the film playing
     * on under it; your TV greyed with where it plays from, not to be chosen, and This phone ticked though the TV is
     * chosen; the Cast group with its devices. Nothing of Google's started for the button or the list.
     */
    @Test
    fun aTapOpensPlayOnAsThePlayersPanelWithTheTvsGreyed() {
        val fake = runBlocking { pairedTv() }
        container.playOn.choose(PlayChoice.Tv(fake.key.id))
        launch(runBlocking { plays.launch("copper-tide-cast-panel") }).use { scenario ->
            playing(scenario)
            controlsUp(scenario)
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            assertEquals("the button says this phone, as Play plays here", listOf("Play on: this phone"), scenario.read { a -> castNode(a)!!.config.getOrNull(SemanticsProperties.ContentDescription) })
            assertTrue("drawn: Google's Cast software unstarted", castUnstarted())
            tapCast(scenario)
            await("Play on's panel") { scenario.read { a -> (SHEET_TITLE in paneTitles(a)).takeIf { it } } }
            await("the TV's row, greyed") { scenario.read { a -> row(a, fake.name) } }
            scenario.read { a ->
                val tvRow = row(a, fake.name)!!
                assertTrue("where it plays from: ${words(tvRow)}", TITLE_PAGE_LINE in words(tvRow))
                assertNotNull("not to be chosen from the player", tvRow.config.getOrNull(SemanticsProperties.Disabled))
                assertFalse("nor ticked", tvRow.config.getOrNull(SemanticsProperties.Selected) == true)
                assertTrue("This phone ticked", row(a, THIS_PHONE)?.config?.getOrNull(SemanticsProperties.Selected) == true)
                assertTrue("the panel's title is a heading", overlayNodes(a).any { it.config.getOrNull(SemanticsProperties.Heading) != null && SHEET_TITLE in words(it) })
                assertTrue("playing on under the panel", a.player!!.isPlaying)
            }
            await("the Cast group's device") { scenario.read { a -> castRow(a, kitchen.name) } }
            assertTrue("opened: Google's Cast software unstarted", castUnstarted())
            assertEquals("the TV still chosen", PlayChoice.Tv(fake.key.id), container.playOn.chosen.value)
        }
    }

    /**
     * L124: Back closes the panel, as the player's others close, and the focus goes back to the button, with the controls
     * up, for TalkBack (a test's word that it explores by touch); a finger leaves no button focused.
     */
    @Test
    fun backClosesItAndTheFocusGoesBackToTheButton() {
        launch(runBlocking { plays.launch("copper-tide-cast-back") }).use { scenario ->
            playing(scenario)
            for (talkBack in listOf(true, false)) {
                CastIntroFocus.exploring = { talkBack }
                controlsUp(scenario)
                await("the Cast button") { scenario.read { a -> castNode(a) } }
                tapCast(scenario)
                await("Play on's panel") { scenario.read { a -> (SHEET_TITLE in paneTitles(a)).takeIf { it } } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("the panel closed") { scenario.read { a -> (SHEET_TITLE !in paneTitles(a)).takeIf { it } } }
                await("the controls up again, with the button") { scenario.read { a -> castNode(a)?.takeIf { playerView(a).isControllerFullyVisible } } }
                if (talkBack) {
                    await("the focus back on the button") { scenario.read { a -> castNode(a)?.takeIf { it.config.getOrNull(SemanticsProperties.Focused) == true } } }
                } else {
                    assertFalse("a finger leaves the button unfocused", scenario.read { a -> castNode(a)!!.config.getOrNull(SemanticsProperties.Focused) == true })
                }
                assertTrue("still playing", scenario.read { it.player!!.isPlaying })
            }
        }
    }

    /**
     * Add a TV, and the Look-for row's Privacy (L138, item 29), from the player's list: each opens in the app's screens
     * (MainActivity's asks), the panel closed first.
     */
    @Test
    fun addATvAndPrivacyFromTheListOpenInTheApp() {
        launch(runBlocking { plays.launch("copper-tide-cast-asks") }).use { scenario ->
            playing(scenario)
            controlsUp(scenario)
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            tapCast(scenario)
            await("Add a TV") { scenario.read { a -> clickable(a, ADD_TV_TITLE) } }
            scenario.onActivity { a -> clickable(a, ADD_TV_TITLE)!!.config[SemanticsActions.OnClick].action!!.invoke() }
            val added = await("the app with Add a TV asked for") { mainActivities(Stage.RESUMED).singleOrNull()?.takeIf { onMainThread { it.addTvsAsked } == 1 } }
            await("Add a TV over the app") { onMainThread { overlayWords(added) }.takeIf { ADD_TV_TITLE in it } }
            assertFalse("the panel closed first", scenario.read { a -> SHEET_TITLE in paneTitles(a) })
            onMainThread { added.finish() }
            await("the app closed") { mainActivities(*ALIVE).isEmpty().takeIf { it } }
            // The Look-for row, as for one who hasn't chosen to look: its Privacy link.
            runBlocking { container.settings.updateCast { it.copy(look = false) } }
            playing(scenario)
            controlsUp(scenario)
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            tapCast(scenario)
            await("the Privacy link") { scenario.read { a -> privacyLink(a) } }
            scenario.onActivity { a -> privacyLink(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }
            val privacy = await("the app with the privacy page asked for") { mainActivities(Stage.RESUMED).singleOrNull()?.takeIf { onMainThread { it.privaciesAsked } == 1 } }
            assertEquals("Add a TV not asked again", 0, onMainThread { privacy.addTvsAsked })
        }
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /**
     * L124 steps 3-5 (CsPhonePlayer 3, 4), W45: the film at 0:32, Kitchen display's row tapped. The panel closes, the film
     * pauses there and the line says "Moving to Kitchen display…" while the device connects, the button connecting; once
     * connected, the same launch is loaded there from where it paused, nothing searched and nothing to this phone's
     * player; once the device plays it the player closes, its place saved at 0:32, and the remote opens in the app.
     */
    @Test
    fun aRowTappedPausesTheFilmMovesItThereAndTheRemoteOpensOnceItPlays() {
        val launch = runBlocking { plays.launch("copper-tide-moves") }
        launch(launch).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display…" && a.player?.playWhenReady == false } } }
            assertEquals("TalkBack hears it once", "Moving to Kitchen display", scenario.read { a -> momentView(a)?.contentDescription?.toString() })
            assertFalse("the panel closed", scenario.read { a -> SHEET_TITLE in paneTitles(a) })
            assertEquals("its own connect, from the player", listOf(kitchen.name), connector.selected.toList())
            assertEquals("chosen as it connects", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
            assertTrue("nothing loaded before it connects", launcher.loads.isEmpty())
            val pausedAt = scenario.read { it.player!!.currentPosition }
            connector.started(kitchen)
            val loaded = await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            assertEquals("the same launch, there from where it paused", launch.copy(cast = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model), startPositionMs = loaded.startPositionMs), loaded)
            assertTrue("from 0:32: ${loaded.startPositionMs}", abs(loaded.startPositionMs!! - pausedAt) < 1_000)
            assertEquals("the cast play started for it", listOf(loaded.cast), launcher.started.map { it.first })
            assertTrue("still in front, paused, as the device starts it", scenario.read { a -> !a.isFinishing && a.player?.playWhenReady == false })
            // The device plays it, as the real load's feed says.
            onMainThread { Casting.follows(CastFeed("player-cast-load", loaded, CastPlayerState.Playing, loaded.startPositionMs, 60_000)) }
            // Made and following it before the app's screens come: they come only for the remote (the app's shell makes it).
            await("the remote's play") { container.remotePlaying.now.value }
            launcher.plays()
            // Read by its stage: a player closed and destroyed between two polls can't be read.
            await("the player closed") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
            await("the remote asked for in the app") { mainActivities(Stage.RESUMED).singleOrNull()?.takeIf { onMainThread { it.remotesAsked } == 1 } }
            // The app's screens take any launch sent for this phone's player: none comes, so no player opens again.
            val until = android.os.SystemClock.uptimeMillis() + 2_000
            compose.waitUntil(5_000) { android.os.SystemClock.uptimeMillis() > until }
            assertTrue("no player opened again", players().all { it.isFinishing || it.isDestroyed })
        }
        val savedAt = runBlocking { plays.savedMs() }
        assertTrue("its place saved at 0:32: $savedAt", savedAt in 31_000..35_000)
        assertEquals("the sheet back to Idle", io.skjaere.usenet.app.play.PlayState.Idle, container.playback.state.value)
        assertEquals("still Kitchen display's", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        assertEquals("nothing stopped", 0, launcher.stops.get())
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /**
     * L124 step 6, L102: the device refuses the file as it opens it (its media session vanishing, a MOV): nothing more goes
     * there, This phone is chosen, ending the session, and the film plays on here from 0:32, the line saying why for 6 s.
     * The play sheet shows no failure.
     */
    @Test
    fun aRefusalAsItOpensPlaysOnHereFromWhereItPausedWithItsLine() {
        launch(runBlocking { plays.launch("copper-tide-refused") }).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") } } }
            val pausedAt = scenario.read { it.player!!.currentPosition }
            connector.started(kitchen)
            await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            launcher.fails(CastOutcomes.vanished(ServeCounters(requests = 2, partials = 1), kitchen.name, CastFile("mov", video = true)))
            await("playing on here, with the line") {
                scenario.read { a -> moment(a)?.takeIf { it == "MOV file. Kitchen display refused it as it opened. Still playing here." && a.player?.isPlaying == true } }
            }
            assertTrue("from where it paused", scenario.read { a -> a.player!!.currentPosition >= pausedAt - 500 && a.player!!.currentPosition < pausedAt + 5_000 })
            await("This phone chosen") { (container.playOn.chosen.value == PlayChoice.Phone).takeIf { it } }
            assertEquals("what started for the device stopped", 1, launcher.stops.get())
            assertEquals("the session ended", 1, connector.ends.get())
            assertEquals("no failure's sheet", io.skjaere.usenet.app.play.PlayState.Idle, container.playback.state.value)
            assertFalse("the player stays", scenario.read { it.isFinishing })
        }
    }

    /** L124 step 1, L57 (CsPhonePlayer 5): an AVI says so at once, the film never stopping, and nothing connects. */
    @Test
    fun anAviSaysSoAtOnceAndNothingConnects() {
        val avi = runBlocking { aviLaunch() }
        launch(avi).use { scenario ->
            playing(scenario)
            tapRow(scenario, kitchen)
            await("the line") { scenario.read { a -> moment(a)?.takeIf { it == "This file type can't play on Kitchen display. Still playing here." } } }
            assertTrue("never paused", scenario.read { a -> a.player?.playWhenReady == true })
            assertFalse("the panel closed", scenario.read { a -> SHEET_TITLE in paneTitles(a) })
        }
        assertTrue("nothing connected", connector.selected.isEmpty())
        assertTrue("nothing started", launcher.started.isEmpty())
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
    }

    /**
     * L124 step 6, L149: the device refuses the connect: the film plays on here, with its line, This phone chosen; the
     * focus back on the Cast button for TalkBack (a test's word that it explores by touch), as the list's row it was on
     * has gone.
     */
    @Test
    fun aFailedConnectPlaysOnHereWithItsLine() {
        CastIntroFocus.exploring = { true }
        launch(runBlocking { plays.launch("copper-tide-no-connect") }).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") } } }
            connector.refused(kitchen)
            await("playing on here, with the line") {
                scenario.read { a -> moment(a)?.takeIf { it == "Couldn't connect to Kitchen display. Still playing here." && a.player?.isPlaying == true } }
            }
            await("This phone chosen") { (container.playOn.chosen.value == PlayChoice.Phone).takeIf { it } }
            await("the focus back on the Cast button", 5_000) { scenario.read { a -> castNode(a)?.takeIf { it.config.getOrNull(SemanticsProperties.Focused) == true } } }
        }
        assertTrue("nothing started", launcher.started.isEmpty())
    }

    /**
     * L124 step 2, L122: a speaker's row asks first, over the film, with its three answers; Cancel leaves it playing here
     * and connects nothing.
     */
    @Test
    fun aSpeakersRowAsksOverTheFilmAndCancelLeavesItPlaying() {
        launch(runBlocking { plays.launch("copper-tide-speaker") }).use { scenario ->
            playing(scenario)
            tapRow(scenario, hallway)
            await("the question") { exists(hasText(soundOnlyTitle(hallway.name))).takeIf { it } }
            assertTrue(exists(hasText(SOUND_ONLY_TEXT)))
            PlayOnAsk.SoundOnly.Answer.entries.forEach { assertTrue(it.words, exists(hasText(it.words) and hasClickAction())) }
            assertTrue("asked over the film, still playing", scenario.read { a -> a.player?.isPlaying == true })
            assertTrue("nothing connected while it asks", connector.selected.isEmpty())
            compose.onNode(hasText(PlayOnAsk.SoundOnly.Answer.Cancel.words) and hasClickAction()).performClick()
            await("the question gone") { (!exists(hasText(soundOnlyTitle(hallway.name)))).takeIf { it } }
            assertTrue("still playing", scenario.read { a -> a.player?.isPlaying == true })
        }
        assertTrue("nothing connected", connector.selected.isEmpty())
        assertTrue("nothing started", launcher.started.isEmpty())
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
    }

    /**
     * L148: This phone tapped in the player's list chooses it everywhere and closes the list, as on any screen (a TV was
     * chosen); tapped while the film moves, it calls the move off: the session the move started ends, the list closes,
     * and the film plays on here, no line blaming the device, as nothing failed.
     */
    @Test
    fun thisPhoneTappedInTheListClosesItAndCallsAMoveOff() {
        // The connect given up after its 15 s would say so before This phone is tapped (CastFakes.reset puts it back).
        CastTimings.current = CastTimings(connectMillis = 60_000)
        val fake = runBlocking { pairedTv() }
        container.playOn.choose(PlayChoice.Tv(fake.key.id))
        launch(runBlocking { plays.launch("copper-tide-this-phone") }).use { scenario ->
            playing(scenario)
            controlsUp(scenario)
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            tapCast(scenario)
            tapThisPhone(scenario)
            await("the list closed") { scenario.read { a -> (SHEET_TITLE !in paneTitles(a)).takeIf { it } } }
            assertEquals("This phone chosen everywhere", PlayChoice.Phone, container.playOn.chosen.value)
            assertTrue("still playing", scenario.read { a -> a.player?.isPlaying == true })
            // While the film moves: This phone calls it off.
            tapRow(scenario, kitchen)
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") && a.player?.playWhenReady == false } } }
            tapCast(scenario)
            tapThisPhone(scenario)
            await("the list closed again") { scenario.read { a -> (SHEET_TITLE !in paneTitles(a)).takeIf { it } } }
            await("playing on here") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            frames(2_000)
            assertNull("no line: nothing failed", scenario.read { a -> moment(a) })
            assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
            await("the session the move started ended") { (connector.ends.get() == 1).takeIf { it } }
        }
        assertTrue("nothing loaded there", launcher.loads.isEmpty())
        assertTrue("nothing started there", launcher.started.isEmpty())
    }

    /**
     * L124: a Cast device connected already, from another screen while the film played on here (in its window, or beside
     * the app on a tablet), ticked in the player's list: its row takes the film all the same, the session as it is, nothing
     * connected again; the same launch loads there from where it paused, and once it plays the player closes.
     */
    @Test
    fun aDeviceConnectedAlreadyTakesTheFilmFromItsTickedRow() {
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        val launch = runBlocking { plays.launch("copper-tide-connected") }
        launch(launch).use { scenario ->
            atThirtyTwoSeconds(scenario)
            val selected = connector.selected.toList()
            tapRow(scenario, kitchen)
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display…" && a.player?.playWhenReady == false } } }
            val pausedAt = scenario.read { it.player!!.currentPosition }
            val loaded = await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            assertEquals("the same launch, there from where it paused", launch.copy(cast = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model), startPositionMs = loaded.startPositionMs), loaded)
            assertTrue("from 0:32: ${loaded.startPositionMs}", abs(loaded.startPositionMs!! - pausedAt) < 1_000)
            assertEquals("nothing connected again", selected, connector.selected.toList())
            launcher.plays()
            await("the player closed") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
        }
        assertEquals("the session as it was", 0, connector.ends.get())
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /**
     * L122, L124 step 2: Cast anyway, answered to a speaker's question over the film, moves it there and is remembered for
     * the session it opens, as the router's Cast anyway is: a Play to the speaker in that session isn't asked again.
     */
    @Test
    fun aSpeakersCastAnywayIsRememberedForTheSessionItOpens() {
        launch(runBlocking { plays.launch("copper-tide-speaker-anyway") }).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, hallway)
            await("the question") { exists(hasText(soundOnlyTitle(hallway.name))).takeIf { it } }
            compose.onNode(hasText(PlayOnAsk.SoundOnly.Answer.CastAnyway.words) and hasClickAction()).performClick()
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to Hallway speaker") && a.player?.playWhenReady == false } } }
            connector.started(hallway)
            await("the launch loaded on the speaker") { launcher.loads.firstOrNull() }
            assertTrue("told for the session it opened", container.playOn.castAnywayTold(hallway.id))
            launcher.plays()
            await("the player closed") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
        }
        assertTrue("still told while that session lasts", container.playOn.castAnywayTold(hallway.id))
    }

    /**
     * L124: the player made again while its film moves (a change of dark mode, the font or the language, which make it
     * anew): the move is called off, whether the device still connects or already loads the film, so only one side plays.
     * The new player plays the film here from where it paused, This phone chosen, the session the move started ended and
     * what it started on the device stopped; the device playing it later closes nothing and opens no remote.
     */
    @Test
    fun aPlayerMadeAgainWhileItsFilmMovesCallsTheMoveOff() {
        // A connect given up after its 15 s would choose This phone too: not here (CastFakes.reset puts the timings back).
        CastTimings.current = CastTimings(connectMillis = 60_000)
        launch(runBlocking { plays.launch("copper-tide-made-again") }).use { scenario ->
            // While the device connects.
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") && a.player?.playWhenReady == false } } }
            val first = madeAgain(scenario)
            await("This phone chosen", 5_000) { (container.playOn.chosen.value == PlayChoice.Phone).takeIf { it } }
            await("the session the move started ended", 5_000) { (connector.ends.get() == 1).takeIf { it } }
            connector.started(kitchen)
            frames(2_000)
            assertTrue("nothing loaded there", launcher.loads.isEmpty())
            assertTrue("still playing here", onMainThread { first.player?.isPlaying == true })
            // Once the device has the film.
            onMainThread { first.player!!.seekTo(32_000) }
            await("the film playing at 0:32") { onMainThread { first.player?.takeIf { it.isPlaying && it.currentPosition in 32_000..34_000 } } }
            tapRow(scenario, kitchen)
            await("the film paused, moving again") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") && a.player?.playWhenReady == false } } }
            connector.started(kitchen)
            await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            val second = madeAgain(scenario)
            await("what started there stopped") { (launcher.stops.get() >= 1).takeIf { it } }
            await("This phone chosen again") { (container.playOn.chosen.value == PlayChoice.Phone).takeIf { it } }
            launcher.plays()
            frames(2_000)
            assertTrue("still playing here", onMainThread { second.player?.isPlaying == true && !second.isFinishing })
            assertTrue("no remote asked for", mainActivities(*ALIVE).isEmpty())
        }
    }

    /**
     * L124, L147: a file Import opened plays with no launch; moved, it goes through Import's cast, its launch made for the
     * file there (saving no progress) from where it paused.
     */
    @Test
    fun aFileImportOpenedMovesThroughImportsCastFromWhereItPaused() {
        val opened = runBlocking { plays.launch("copper-tide-imported") }
        val intent = android.content.Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, opened.uri).putExtra("title", "Copper Tide")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") } } }
            val pausedAt = scenario.read { it.player!!.currentPosition }
            connector.started(kitchen)
            val loaded = await("the file's launch loaded on the device", 60_000) { launcher.loads.firstOrNull() }
            assertTrue("a file Import opened", loaded.imported)
            assertEquals(opened.uri, loaded.uri)
            assertEquals(kitchen.id, loaded.cast?.route)
            assertTrue("from where it paused: ${loaded.startPositionMs}", abs(loaded.startPositionMs!! - pausedAt) < 1_000)
            launcher.plays()
            // Read by its stage: a player closed and destroyed between two polls can't be read.
            await("the player closed") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
        }
    }

    /**
     * L124 step 3, W51 (item 44): a film whose first sound track, the one a Cast device plays, is German, English wanted
     * first, asks nothing; its move's line says Kitchen display will play the German audio, read so by TalkBack.
     */
    @Test
    fun aGermanFirstFilmsMoveSaysKitchenDisplayWillPlayTheGermanAudio() {
        runBlocking { container.settings.updatePlayback { it.copy(audioLanguages = listOf("en"), subtitlesWhenForeign = false) } }
        val launch = runBlocking { probedLaunch("move-german.mkv", "copper-tide-german", GERMAN_DL) }
        launch(launch).use { scenario ->
            playing(scenario)
            tapRow(scenario, kitchen)
            await("moving, with the audio it will play there") {
                scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display… Kitchen display will play the German audio" && a.player?.playWhenReady == false } }
            }
            assertEquals("Moving to Kitchen display. Kitchen display will play the German audio", scenario.read { a -> momentView(a)?.contentDescription?.toString() })
            assertEquals("its own connect", listOf(kitchen.name), connector.selected.toList())
            // Within the connect's wait: the rule's own reads are left until the device has the film.
            connector.started(kitchen)
            val loaded = await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            assertEquals("the same film there", launch.uri, loaded.uri)
            assertFalse("nothing asked", exists(hasText(CastPrecheck.Button.CastAnyway.words)))
        }
    }

    /**
     * L124 step 2, L130 (item 44; CsPhonePrecheck): a film whose only sound Kitchen display can't play asks the pre-check
     * over the film before anything connects, in the design's words; Play on this phone offered, as it plays here now, and
     * no Play a compatible release, its list holding no other. Play on this phone leaves it playing here, connecting
     * nothing; asked again, Cast anyway moves it there as any move goes, from where it paused.
     */
    @Test
    fun aDtsFilmAsksFirstOverItPlayOnThisPhoneLeavesItAndCastAnywayMovesIt() {
        val launch = runBlocking { probedLaunch("move-dts.mkv", "copper-tide-dts", DTS_REMUX) }
        launch(launch).use { scenario ->
            playing(scenario)
            tapRow(scenario, kitchen)
            await("the pre-check") { exists(hasText(NO_SOUND)).takeIf { it } }
            assertTrue("its card", exists(hasText("⚠ DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.")))
            assertTrue("the release's name", exists(hasText(DTS_REMUX)))
            assertTrue(exists(hasText("Checked before anything was sent to Kitchen display.")))
            assertEquals(listOf("Play on this phone", "Cast anyway", "Cancel"), buttons())
            assertTrue("asked over the film, still playing", scenario.read { a -> a.player?.isPlaying == true })
            assertFalse("the panel closed", scenario.read { a -> SHEET_TITLE in paneTitles(a) })
            assertTrue("nothing connected while it asks", connector.selected.isEmpty())
            compose.onNode(hasText(CastPrecheck.Button.PlayHere.words) and hasClickAction()).performClick()
            await("the pre-check gone") { (!exists(hasText(NO_SOUND))).takeIf { it } }
            assertTrue("still playing here", scenario.read { a -> a.player?.isPlaying == true })
            assertTrue("nothing connected", connector.selected.isEmpty())
            assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
            // Asked again: Cast anyway moves it, its line without the sheet's lines.
            scenario.onActivity { it.player!!.seekTo(32_000) }
            await("the film playing at 0:32", 20_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition in 32_000..34_000 } } }
            tapRow(scenario, kitchen)
            await("the pre-check again") { exists(hasText(CastPrecheck.Button.CastAnyway.words) and hasClickAction()).takeIf { it } }
            compose.onNode(hasText(CastPrecheck.Button.CastAnyway.words) and hasClickAction()).performClick()
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display…" && a.player?.playWhenReady == false } } }
            assertEquals("its own connect, once asked", listOf(kitchen.name), connector.selected.toList())
            val pausedAt = scenario.read { it.player!!.currentPosition }
            connector.started(kitchen)
            val loaded = await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            assertEquals("the same launch, there from where it paused", launch.copy(cast = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model), startPositionMs = loaded.startPositionMs), loaded)
            assertTrue("from 0:32: ${loaded.startPositionMs}", abs(loaded.startPositionMs!! - pausedAt) < 1_000)
        }
    }

    /**
     * L152, L124 step 2 (item 44): Play a compatible release, in the pre-check over a film whose list holds another release:
     * the device connects as for a move, the film paused; then the list's next release the device should play starts
     * there from where it paused, as an automatic pick, and the player closes, its place saved, the play sheet saying how
     * it goes. The film itself is never sent there.
     */
    @Test
    fun playACompatibleReleaseConnectsAndPlaysTheListsNextThereFromWhereItPaused() {
        assumeTrue("no usable Usenet provider saved, so no play starts", runBlocking { container.noUsableProvider() } == null)
        val german = plays.fixtures.candidate("copper-tide-compatible-german", GERMAN_DL)
        val launch = runBlocking {
            val posted = plays.fixtures.release("move-german.mkv", plays.fixtures.media("move-german.mkv"), PART, "copper-tide-compatible-german", GERMAN_DL) { emptySet() }
            PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> if (candidate.id == german.key) posted.nzb else grab.fetch(candidate) } }
            probedLaunch("move-dts.mkv", "copper-tide-compatible-dts", DTS_REMUX, others = listOf(german))
        }
        launch(launch).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("the pre-check") { exists(hasText(NO_SOUND)).takeIf { it } }
            assertEquals(listOf("Play a compatible release", "Play on this phone", "Cast anyway", "Cancel"), buttons())
            compose.onNode(hasText(CastPrecheck.Button.Compatible.words) and hasClickAction()).performClick()
            await("the film paused while it connects") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display…" && a.player?.playWhenReady == false } } }
            val pausedAt = scenario.read { it.player!!.currentPosition }
            assertTrue("nothing started before it connects", launcher.started.isEmpty())
            connector.started(kitchen)
            // Closing at once: read by its stage, as a destroyed player can't be read.
            await("the player closed for the play") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
            val loaded = await("the compatible release loaded on the device", 60_000) { launcher.loads.firstOrNull() }
            assertEquals("the list's next", german.item.guid, loaded.release.guid)
            assertEquals(CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model), loaded.cast)
            assertTrue("from where the film paused: ${loaded.startPositionMs}", abs(loaded.startPositionMs!! - pausedAt) < 1_000)
            assertEquals("never the film itself", 1, launcher.loads.size)
            launcher.plays()
            await("the sheet back to Idle") { (container.playback.state.value == PlayState.Idle).takeIf { it } }
        }
        val savedAt = runBlocking { plays.savedMs() }
        assertTrue("its place saved at 0:32: $savedAt", savedAt in 31_000..35_000)
        assertEquals("Kitchen display's", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    /**
     * L124 step 2, L147 (item 44): a file Import opened has no launch, nor its probe's tracks: the player judges what it
     * reads of the file it plays, so a DTS-only one asks the pre-check too, named by its NZB, with no compatible release
     * (no list); Cancel leaves it playing here, nothing connected.
     */
    @Test
    fun aDtsFileImportOpenedAsksTooAndCancelLeavesItPlaying() {
        val opened = runBlocking {
            val release = plays.fixtures.release("move-dts.mkv", plays.fixtures.media("move-dts.mkv"), PART, "copper-tide-opened-dts", DTS_REMUX) { emptySet() }
            plays.fixtures.launch(release.prepared, "copper-tide-opened-dts", 0)
        }
        val intent = android.content.Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, opened.uri).putExtra("title", "Copper Tide")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            playing(scenario)
            tapRow(scenario, kitchen)
            await("the pre-check") { exists(hasText(NO_SOUND)).takeIf { it } }
            assertTrue("named by its NZB", exists(hasText(DTS_REMUX)))
            assertTrue("its card", exists(hasText("⚠ DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.")))
            assertEquals(listOf("Play on this phone", "Cast anyway", "Cancel"), buttons())
            compose.onNode(hasText(CastPrecheck.Button.Cancel.words) and hasClickAction()).performClick()
            await("the pre-check gone") { (!exists(hasText(NO_SOUND))).takeIf { it } }
            assertTrue("still playing here", scenario.read { a -> a.player?.isPlaying == true })
        }
        assertTrue("nothing connected", connector.selected.isEmpty())
        assertTrue("nothing started", launcher.started.isEmpty())
    }

    /**
     * NF: Play a compatible release from the player, none of whose releases the device can play fully (the list's other
     * one is DTS alone too, its name saying nothing of it): the all-failed sheet's Play on this phone brings back the film
     * the player was playing, which plays here, from where it paused, rather than those releases here.
     */
    @Test
    fun noCompatibleReleasePlayingFullyPlayOnThisPhoneBringsTheFilmBackFromWhereItPaused() {
        assumeTrue("no usable Usenet provider saved, so no play starts", runBlocking { container.noUsableProvider() } == null)
        val plain = plays.fixtures.candidate("copper-tide-compatible-plain", PLAIN)
        val launch = runBlocking {
            val posted = plays.fixtures.release("move-dts.mkv", plays.fixtures.media("move-dts.mkv"), PART, "copper-tide-compatible-plain", PLAIN) { emptySet() }
            PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> if (candidate.id == plain.key) posted.nzb else grab.fetch(candidate) } }
            probedLaunch("move-dts.mkv", "copper-tide-compatible-film", DTS_REMUX, others = listOf(plain))
        }
        launch(launch).use { scenario ->
            atThirtyTwoSeconds(scenario)
            tapRow(scenario, kitchen)
            await("the pre-check") { exists(hasText(CastPrecheck.Button.Compatible.words) and hasClickAction()).takeIf { it } }
            compose.onNode(hasText(CastPrecheck.Button.Compatible.words) and hasClickAction()).performClick()
            await("the film paused while it connects") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to Kitchen display…" && a.player?.playWhenReady == false } } }
            val pausedAt = scenario.read { it.player!!.currentPosition }
            connector.started(kitchen)
            val failed = await("the all-failed sheet", 60_000) { (container.playback.state.value as? PlayState.Failed)?.cast }
            assertEquals(CastFailKind.NotFully, failed.kind)
            assertTrue("nothing sent there", launcher.loads.isEmpty())
            assertTrue("this phone offered", CastButton.PlayHere in CastStart.buttons(failed, live = true))
            // What the app's screens would take for this phone's player: heard off the test's thread, waited for on the rule's clock.
            val sent = CopyOnWriteArrayList<PlayerLaunch>()
            val hearing = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { container.playback.launches.collect { sent += it } }
            onMainThread { container.playback.act(CastStart.press(CastButton.PlayHere, failed)) }
            val back = try {
                await("the film for this phone's player, the sheet saying ${container.playback.state.value}", 30_000) { sent.firstOrNull() }
            } finally {
                hearing.cancel()
            }
            assertEquals("the film it was playing", launch.uri, back.uri)
            assertNull("here", back.cast)
            assertTrue("from where it paused: ${back.startPositionMs}", abs(back.startPositionMs!! - pausedAt) < 1_000)
            await("This phone chosen") { (container.playOn.chosen.value == PlayChoice.Phone).takeIf { it } }
        }
    }

    /**
     * The film [file] of the test's assets posted as the release [guid] named [title], with the tracks this phone's probe
     * reads of it, as the coordinator's launch carries them (L129), kept with a request of it and [others] after it.
     */
    private suspend fun probedLaunch(file: String, guid: String, title: String, others: List<RankedItem> = emptyList()): PlayerLaunch {
        val release = plays.fixtures.release(file, plays.fixtures.media(file), PART, guid, title) { emptySet() }
        val launch = plays.fixtures.launch(release.prepared, guid, 0, candidates = listOf(plays.fixtures.candidate(guid, title)) + others)
        val target = UsenetTarget(PlayCandidate(launch.release.key, title), release.nzb, release.prepared, release.prepared.defaultPick!!, container.store)
        val tracks = when (val probed = container.trackProbe.probe(target)) {
            is ProbeResult.Playable -> probed.tracks
            is ProbeResult.NoPlayableAudio -> probed.tracks
            is ProbeResult.NoPlayableVideo -> probed.tracks
            else -> null
        }
        return launch.copy(tracks = tracks ?: throw AssertionError("no tracks read of $file"))
    }

    /** The answers the question over the film offers, in order. */
    private fun buttons(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } }
        .filter { it in CastPrecheck.Button.entries.map(CastPrecheck.Button::words) }

    /** The film posted as an AVI (its bytes an MP4's, which the player reads all the same): the type by its name alone. */
    private suspend fun aviLaunch(): PlayerLaunch {
        val release = plays.fixtures.release("copper-tide.avi", plays.fixtures.media("gaps-faststart.mp4"), 64_000, "copper-tide-avi", "Copper.Tide.2026.DVDRip.XviD-NZST") { emptySet() }
        return plays.fixtures.launch(release.prepared, "copper-tide-avi", 0)
    }

    /** One of your TVs, paired in the link store (its identity made first, so a list's first read doesn't wipe it). */
    private suspend fun pairedTv(): FakeTv {
        val phone = link.identity().identity.key.id
        return FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { fake ->
            tv = fake
            assertTrue(link.addTv(fake.key.id, fake.name, fake.address.hostAddress!!, fake.port))
        }
    }

    /** Waits for the film to play. */
    private fun playing(scenario: ActivityScenario<PlayerActivity>) =
        await("the film to play", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }

    /** The film playing at 0:32. */
    private fun atThirtyTwoSeconds(scenario: ActivityScenario<PlayerActivity>) {
        playing(scenario)
        scenario.onActivity { it.player!!.seekTo(32_000) }
        await("the film playing at 0:32", 20_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition in 32_000..34_000 } } }
    }

    /** The controls up and held there, with the top bar. */
    private fun controlsUp(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a ->
            playerView(a).controllerShowTimeoutMs = 0
            playerView(a).showController()
        }
        await("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
    }

    /** A tap on the Cast button, as its click does. */
    private fun tapCast(scenario: ActivityScenario<PlayerActivity>) =
        scenario.onActivity { a -> castNode(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }

    /** [route]'s row tapped in the player's list, opened from its button. */
    private fun tapRow(scenario: ActivityScenario<PlayerActivity>, route: CastRoute) {
        controlsUp(scenario)
        await("the Cast button") { scenario.read { a -> castNode(a) } }
        tapCast(scenario)
        await("${route.name}'s row") { scenario.read { a -> castRow(a, route.name) } }
        scenario.onActivity { a -> castRow(a, route.name)!!.config[SemanticsActions.OnClick].action!!.invoke() }
    }

    /** The player made again, as a change of dark mode makes it: the new one, once it plays here. */
    private fun madeAgain(scenario: ActivityScenario<PlayerActivity>): PlayerActivity {
        val before = scenario.read { it }
        scenario.onActivity { it.recreate() }
        return await("the player made again, playing here", 30_000) {
            onMainThread {
                ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<PlayerActivity>()
                    .firstOrNull { it !== before && it.player?.isPlaying == true }
            }
        }
    }

    /** This phone's row tapped in the player's list, open. */
    private fun tapThisPhone(scenario: ActivityScenario<PlayerActivity>) {
        await("This phone's row") { scenario.read { a -> row(a, THIS_PHONE) } }
        scenario.onActivity { a -> row(a, THIS_PHONE)!!.config[SemanticsActions.OnClick].action!!.invoke() }
    }

    /** [millis] of the player's frames, which the rule's clock drives: never a sleep. */
    private fun frames(millis: Long) {
        val until = android.os.SystemClock.uptimeMillis() + millis
        compose.waitUntil(millis + 5_000) { android.os.SystemClock.uptimeMillis() > until }
    }

    /** The Cast button's node while it shows; null while the controls are down. */
    private fun castNode(activity: Activity): SemanticsNode? = overlayNodes(activity).firstOrNull { it.config.getOrNull(CastButtonLook) != null }

    /** A Cast device's row in the list, by TalkBack's words for it. */
    private fun castRow(activity: Activity, name: String): SemanticsNode? =
        overlayNodes(activity).firstOrNull { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("$name, Cast device") } }

    private fun paneTitles(activity: Activity) = overlayNodes(activity).mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** A row of the list by its name: a node that can be selected, with [name] among its words. */
    private fun row(activity: Activity, name: String): SemanticsNode? =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) != null && name in words(it) }

    /** A clickable node with [name] among its words. */
    private fun clickable(activity: Activity, name: String): SemanticsNode? =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && name in words(it) }

    /** The Look-for row's Privacy link: the clickable part of the text that says it. */
    private fun privacyLink(activity: Activity): SemanticsNode? =
        overlayNodes(activity).firstOrNull { n -> CAST_PRIVACY in words(n) && n.config.getOrNull(SemanticsProperties.Text) != null }
            ?.let { text -> (listOf(text) + text.children).firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null } }

    /** The player's moment label while it shows: its view, and its words. */
    private fun momentView(activity: Activity): TextView? = activity.findViewById<TextView>(R.id.moment_label)?.takeIf { it.isShown }

    private fun moment(activity: Activity): String? = momentView(activity)?.text?.toString()

    /** A node's words and those of the nodes in it. */
    private fun words(node: SemanticsNode): List<String> = subtree(node).flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [probe] to give something, on the compose rule's clock, which drives every composition here (the player's
     * panel and button, and the app's): a wait off it would hold them still.
     */
    private fun <T : Any> await(what: String, millis: Long = 20_000, probe: () -> T?): T {
        var found: T? = null
        compose.waitUntil("$what, in ${millis / 1_000} s", millis) { probe()?.also { found = it } != null }
        return found!!
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** The players alive. */
    private fun players(): List<PlayerActivity> = onMainThread {
        ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<PlayerActivity>().distinct()
    }

    /** The app's MainActivity instances in any of [stages]. */
    private fun mainActivities(vararg stages: Stage): List<MainActivity> = onMainThread {
        stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
    }

    private companion object {
        const val INDEXER = "player-cast-device-test"
        const val DTS_REMUX = "Copper.Tide.2026.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val GERMAN_DL = "Copper.Tide.2026.German.DL.1080p.BluRay.x264-WATTWURM"
        const val NO_SOUND = "No sound on Kitchen display"

        /** A release whose name says nothing of its sound: the ranking warns of nothing, and its file is DTS alone. */
        const val PLAIN = "Copper.Tide.2026.1080p.BluRay.x264-PLAIN"
        const val PART = 64_000

        /** An activity's stages from its making to its end. */
        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}

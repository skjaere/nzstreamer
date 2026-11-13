package io.skjaere.usenet.app.link

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.unit.dp
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.volumeOn
import io.skjaere.usenet.app.castExtras
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The remote (item 26, PtPhoneRemote, PtPhoneLost 1, PtPhoneTalkBack, L38, L43) on a real device, through the app's own
 * links, start and bar: a TV in the test's process on this device's own address, paired with the app's key and chosen,
 * takes a Play and says how it plays, as NZStreamer on a TV would. TalkBack reads it in its order, the state line a polite
 * live region and the time bar a slider whose swipe is one seek; ±10 s taps make one seek after the last (also as the
 * remote closes just after them), and a drag one as the finger lifts; play, pause and Stop send their commands, and the
 * remote closes as the TV stops. Lost, it says so and waits; Playback stopped there, Stop alone is left. The TV and the
 * show are invented; the TV leaves the app's link.json after, and the app follows nothing.
 */
@RunWith(AndroidJUnit4::class)
class TvRemoteDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val closed = AtomicInteger()
    private lateinit var playing: PlayingTv

    /** Whether a case made a Cast session over its fakes ([CastFakes]), which [restore] puts back. */
    private var casting = false
    private val tv get() = playing.tv

    @Before
    fun setUp() {
        assumeFalse("a TV plays itself", container.television)
        playing = PlayingTv()
    }

    @After
    fun restore() {
        if (::playing.isInitialized) playing.close()
        // Last, once This phone is chosen again: no session, and Google's seams and the settings as they were.
        if (casting) CastFakes.reset()
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

    private fun say(message: LinkMessage) = playing.say(message)

    private fun status(state: Playback) = playing.status(state)

    /** A play started on the TV, which took it, its player [state]; the remote open on it. */
    private fun remote(state: Playback) {
        playing.play(state)
        compose.setContent { AppTheme { TvRemoteScreen(onBack = { closed.incrementAndGet() }) } }
    }

    private inline fun <reified T : LinkMessage> sent(): List<T> = playing.sent<T>()

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(InstrumentationRegistry.getInstrumentation().targetContext).needsInitialization() }

    /**
     * L115, L118 (CsPhoneRemoteC2 4): on a TV with NZStreamer the remote's button shows the TV, and there is no volume: a
     * Play on TV play has none.
     */
    @Test
    fun aTvsPlayShowsTheTvOnTheButtonAndNoVolume() {
        remote(Playback.Playing)
        val button = node(SemanticsMatcher.keyIsDefined(CastButtonLook) and hasClickAction())
        assertEquals(ButtonFace.Look.Tv, button.config.getOrNull(CastButtonLook))
        node(hasContentDescription(stopPlayingOn(tv.name)) and hasClickAction())
        assertTrue("no volume", compose.onAllNodes(hasContentDescription("Volume on", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * L118, L143: a TV's play on the remote while a Cast device's session is connected (its row tapped in the remote's
     * list, the TV's play going on): the remote still follows the TV's play, and the Cast device's volume isn't on it, as
     * the slider is a cast's alone; the list opened from the remote's button has it, in the device's block. Drawing the
     * remote and opening its list start nothing of Google's.
     */
    @Test
    fun aTvsPlayWithACastSessionConnectedShowsNoCastVolume() {
        val kitchen = castRoute("Kitchen display")
        val connector = FakeConnector()
        playing.play(Playback.Playing)
        CastFakes.started(FakeRoutes().apply { found(kitchen) }, connector, CopyOnWriteArrayList())
        casting = true
        try {
            CastFakes.connected(kitchen, connector)
            compose.waitUntil(5_000) { castExtras?.volume?.value != null }
            compose.setContent {
                AppTheme {
                    TvRemoteScreen(onBack = { closed.incrementAndGet() })
                    PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
                }
            }
            node(hasContentDescription(stopPlayingOn(tv.name)) and hasClickAction())
            val button = node(SemanticsMatcher.keyIsDefined(CastButtonLook) and hasClickAction())
            assertEquals("the TV's face: Play goes there", ButtonFace.Look.Tv, button.config.getOrNull(CastButtonLook))
            assertTrue("no volume on a TV's play", compose.onAllNodes(hasContentDescription("Volume on", substring = true)).fetchSemanticsNodes().isEmpty())
            assertTrue("drawn: Google's Cast software unstarted", castUnstarted())
            compose.onNode(SemanticsMatcher.keyIsDefined(CastButtonLook) and hasClickAction()).performClick()
            waitFor(hasText(kitchen.name) and isHeading())
            assertEquals("the block's volume alone", 1, compose.onAllNodes(hasContentDescription("Volume on", substring = true)).fetchSemanticsNodes().size)
            assertTrue("the block's, the Cast device's", exists(hasContentDescription(volumeOn(kitchen.name))))
            assertTrue("its list opened: Google's Cast software unstarted", castUnstarted())
        } finally {
            compose.runOnUiThread { container.playOn.closeSheet() }
        }
    }

    @Test
    fun talkBackReadsItInItsOrderTheStateLineLiveAndTheTimeBarAsASlider() {
        remote(Playback.Paused)
        val back = node(hasContentDescription("Back") and hasClickAction())
        val heading = node(hasContentDescription("Harbor Lights, season 1, episode 3, Episode 3"))
        assertNotNull("a heading", heading.config.getOrNull(SemanticsProperties.Heading))
        val state = node(hasText("Paused on ${tv.name}"))
        assertEquals("read again as it changes", LiveRegionMode.Polite, state.config.getOrNull(SemanticsProperties.LiveRegion))
        val slider = node(hasContentDescription(POSITION_ON_TV))
        assertEquals("32 minutes 10 seconds of 52 minutes", slider.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("a step a 10 s seek", ProgressBarRangeInfo(1_930f, 0f..3_120f, steps = 311), slider.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertNotNull(slider.config.getOrNull(SemanticsActions.SetProgress))
        val rewind = node(hasContentDescription(REWIND_10) and hasClickAction())
        val play = node(hasContentDescription(playOnTv(tv.name)) and hasClickAction())
        val forward = node(hasContentDescription(FAST_FORWARD_10) and hasClickAction())
        val stop = node(hasContentDescription(stopPlayingOn(tv.name)) and hasClickAction())
        val order = listOf(back, heading, state, slider, play, stop).map { it.boundsInRoot.top }
        assertEquals("top to bottom: Back, the title, the state, the time bar, the buttons, Stop", order.sorted(), order)
        assertTrue("rewind, play, fast-forward from the left", rewind.boundsInRoot.right <= play.boundsInRoot.left && play.boundsInRoot.right <= forward.boundsInRoot.left)
        with(compose.density) {
            assertEquals("72 dp", 72.dp.roundToPx().toFloat(), play.boundsInRoot.width, 2f)
            assertEquals("56 dp", 56.dp.roundToPx().toFloat(), rewind.boundsInRoot.width, 2f)
        }
        // The state line follows the TV.
        say(status(Playback.Playing))
        waitFor(hasText("Playing on ${tv.name}"))
        waitFor(hasContentDescription(pauseOn(tv.name)) and hasClickAction())
    }

    @Test
    fun theSlidersSwipeIsOneSeekAndTapsInQuickSuccessionMakeOne() {
        remote(Playback.Paused)
        val slider = hasContentDescription(POSITION_ON_TV)
        waitFor(slider)
        // TalkBack's swipe up: one step on.
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(1_940f) }
        compose.waitUntil(5_000) { sent<LinkMessage.SeekTo>().isNotEmpty() }
        assertEquals(listOf(1_940_000L), sent<LinkMessage.SeekTo>().map { it.positionMs })
        repeat(3) { compose.onNode(hasContentDescription(FAST_FORWARD_10)).performClick() }
        compose.waitUntil(5_000) { sent<LinkMessage.SeekBy>().isNotEmpty() }
        Thread.sleep(1_000)
        assertEquals("three taps, one seek, 30 s on", listOf(30_000L), sent<LinkMessage.SeekBy>().map { it.deltaMs })
        compose.onNode(hasContentDescription(REWIND_10)).performClick()
        compose.waitUntil(5_000) { sent<LinkMessage.SeekBy>().size == 2 }
        Thread.sleep(1_000)
        assertEquals(listOf(30_000L, -10_000L), sent<LinkMessage.SeekBy>().map { it.deltaMs })
    }

    @Test
    fun tapsJustBeforeTheRemoteClosesStillMakeTheirSeek() {
        playing.play(Playback.Paused)
        var open by mutableStateOf(true)
        compose.setContent { AppTheme { if (open) TvRemoteScreen(onBack = { open = false }) } }
        waitFor(hasContentDescription(FAST_FORWARD_10))
        repeat(3) { compose.onNode(hasContentDescription(FAST_FORWARD_10)).performClick() }
        // Back within the burst's half second: the remote goes, and its seek with it unless it is sent as it goes.
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(hasContentDescription(FAST_FORWARD_10)) }
        compose.waitUntil(5_000) { sent<LinkMessage.SeekBy>().isNotEmpty() }
        Thread.sleep(1_000)
        assertEquals("the three taps' one seek, 30 s on", listOf(30_000L), sent<LinkMessage.SeekBy>().map { it.deltaMs })
    }

    @Test
    fun aDragOnTheTimeBarIsOneSeekToAsTheFingerLifts() {
        remote(Playback.Paused)
        val slider = hasContentDescription(POSITION_ON_TV)
        waitFor(slider)
        val width = compose.onNode(slider).fetchSemanticsNode().boundsInRoot.width
        val edge = with(compose.density) { 24.dp.toPx() }
        compose.onNode(slider).performTouchInput { swipe(Offset(width * 0.3f, centerY), Offset(width * 0.8f, centerY), durationMillis = 800) }
        compose.waitUntil(5_000) { sent<LinkMessage.SeekTo>().isNotEmpty() }
        Thread.sleep(1_000)
        val seeks = sent<LinkMessage.SeekTo>()
        assertEquals("one seek for the drag", 1, seeks.size)
        val expected = ((width * 0.8f - edge) / (width - 2 * edge) * 3_120_000).toLong()
        assertTrue("to where the finger lifted: ${seeks.single().positionMs}, about $expected", abs(seeks.single().positionMs - expected) < 60_000)
        assertTrue("nothing seeks by", sent<LinkMessage.SeekBy>().isEmpty())
    }

    @Test
    fun playPauseAndStopSendTheirCommandsAndTheRemoteClosesAsTheTvStops() {
        remote(Playback.Paused)
        tap(hasContentDescription(playOnTv(tv.name)) and hasClickAction())
        compose.waitUntil(5_000) { sent<LinkMessage.Resume>().isNotEmpty() }
        say(status(Playback.Playing))
        tap(hasContentDescription(pauseOn(tv.name)) and hasClickAction())
        compose.waitUntil(5_000) { sent<LinkMessage.Pause>().isNotEmpty() }
        tap(hasContentDescription(stopPlayingOn(tv.name)) and hasClickAction())
        compose.waitUntil(5_000) { sent<LinkMessage.Stop>().isNotEmpty() }
        Thread.sleep(500)
        assertEquals(listOf(1, 1, 1), listOf(sent<LinkMessage.Resume>().size, sent<LinkMessage.Pause>().size, sent<LinkMessage.Stop>().size))
        assertEquals(0, closed.get())
        say(Status("s-1", Playback.Stopped, mine = true, title = "Harbor Lights"))
        compose.waitUntil(5_000) { closed.get() == 1 }
    }

    @Test
    fun playbackStoppedThereLeavesStopAloneAndLostItSaysSoAndWaits() {
        remote(Playback.Playing)
        say(status(Playback.Failed))
        waitFor(hasText(playbackStoppedOn(tv.name)))
        compose.onNode(hasContentDescription(REWIND_10)).assertIsNotEnabled()
        compose.onNode(hasContentDescription(FAST_FORWARD_10)).assertIsNotEnabled()
        compose.onNode(hasContentDescription(POSITION_ON_TV)).assertIsNotEnabled()
        compose.onNode(hasContentDescription(stopPlayingOn(tv.name))).assertIsEnabled()
        say(status(Playback.Playing))
        waitFor(hasText("Playing on ${tv.name}"))
        // NZStreamer on the TV gone: the link lost.
        tv.close()
        val lost = "${lostConnection(tv.name)}. $KEEPS_PLAYING"
        val box = node(hasContentDescription(lost))
        assertEquals(LiveRegionMode.Polite, box.config.getOrNull(SemanticsProperties.LiveRegion))
        compose.onNode(hasContentDescription(pauseOn(tv.name))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(stopPlayingOn(tv.name))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(POSITION_ON_TV)).assertIsNotEnabled()
        assertEquals("not closed: the TV plays on", 0, closed.get())
    }
}

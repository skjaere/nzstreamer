package io.skjaere.usenet.app.cast

import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.casting.CAST_CONNECTED
import io.skjaere.usenet.app.casting.CAST_FROM_ANOTHER
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.CastVolumeNow
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.disconnectFrom
import io.skjaere.usenet.app.casting.ownVolume
import io.skjaere.usenet.app.casting.playingOn
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.casting.volumeOn
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.RECONNECTING
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.pauseOn
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
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

/**
 * The connected Cast device's block at the top of Play on's list (W47, W49, L117, L118, L119, L154, M1-A;
 * CsPhoneSheetConnected) on a real device, over a fake session and a fake device player with a device volume (L73):
 * Google's software is never started. Connected with nothing playing, the device's name heads the list over "Connected",
 * its volume and Stop casting, its row keeping its ✓ and line with no button of its own; this phone's film there, what
 * plays with pause; another phone's, its title, no pause, the line and Disconnect, which leaves the device playing and
 * this phone off. A drag sends each step it reaches, and TalkBack's swipe and a keyboard's Right or Left one 5 % step
 * (Down leaving the slider); the slider follows the device's own changes; a change the device refuses snaps back a
 * second later, with the line under the slider for the rest of the session, still usable; a route that says its volume
 * is fixed keeps the slider and its drags. The devices and the show are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastBlockDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val den = castRoute("Den TV", fixedVolume = true)
    private lateinit var remote: FakeRemotePlayer

    /** This case's LOAD, its content id: each case's own, as each LOAD's is. */
    private val load = "cast-block-load-${System.nanoTime()}"

    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val release = SavedRelease("cast-block-device-test", RELEASE, RELEASE, null, 0, "cast-block-nzb", "harbor-lights.mkv", 2_000_000_000)
    private val launch = PlayerLaunch(
        UsenetUris.build(release.nzbId, 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(SHOW, 2, 4), episode, release,
        cast = CastTo(kitchen.id, kitchen.name),
    )

    @Before
    fun setUp() {
        assumeFalse("a TV has no Play on list, nor Cast (tv.TvNoCastTest)", container.television)
        CastTimings.current = CastTimings(connectMillis = 60_000, noneFoundMillis = 1_500)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        // Made before anything is followed: its collectors start with the play there, so the block's pause acts at once.
        container.castPlaying
        compose.setContent { AppTheme { PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {}) } }
    }

    @After
    fun restore() {
        // As a finger leaves the screen: a case's keys took it out of touch mode, for the whole display.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        compose.runOnUiThread { container.playOn.closeSheet() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
    }

    /** [route]'s session started, as Google's software would start it, and the device chosen: the fake device player's. */
    private fun connected(route: CastRoute) {
        routes.found(kitchen, den)
        CastFakes.connected(route, connector)
        container.playOn.choose(PlayChoice.Cast(route.id, route.name))
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
    }

    private fun openSheet() {
        compose.runOnUiThread { container.playOn.openSheet() }
        waitFor(hasText(SHEET_TITLE))
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun node(matcher: SemanticsMatcher): SemanticsNode {
        waitFor(matcher)
        return compose.onNode(matcher).fetchSemanticsNode()
    }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    /** A Cast row by TalkBack's words for it: "Kitchen display, Cast device", and its line. */
    private fun castRow(name: String, line: String? = null) = hasContentDescription(listOfNotNull(name, "Cast device", line).joinToString(", ")) and hasClickAction()

    private fun heading(name: String) = hasText(name) and isHeading()

    private fun slider(name: String) = hasContentDescription(volumeOn(name))

    private fun SemanticsNode.state(): String? = config.getOrNull(SemanticsProperties.StateDescription)

    /** What the slider says now, to TalkBack. */
    private fun level(name: String) = compose.onNode(slider(name)).fetchSemanticsNode().state()

    /** TalkBack's swipe on the slider: one step, to [percent]. */
    private fun swipedTo(name: String, percent: Float) = compose.onNode(slider(name)).performSemanticsAction(SemanticsActions.SetProgress) { it(percent) }

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    private fun focused(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    /** [code] pressed and let go on the keyboard, with [meta] held. */
    private fun key(code: Int, meta: Int = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
        compose.waitForIdle()
    }

    /**
     * L117 (CsPhoneSheetConnected 1; CsPhoneTalkBackC2 5-9): connected with nothing playing, "Kitchen display" heads the
     * list, a heading, over "Connected", its volume at the device's 40 %, and Stop casting, then "Play on", a heading too,
     * and the list;
     * the device's row below keeps its ✓ and its line, and no button of its own. Nothing is sent to the device as it
     * shows. Reconnecting, the block says so, its volume waiting and Stop casting still there.
     */
    @Test
    fun connectedTheBlockHeadsTheListWithItsVolumeAndStopCasting() {
        connected(kitchen)
        openSheet()
        val heading = node(heading(kitchen.name))
        val line = node(hasContentDescription(CAST_CONNECTED))
        val volume = node(slider(kitchen.name))
        assertEquals("40 percent", volume.state())
        assertEquals("a step 5 %", ProgressBarRangeInfo(40f, 0f..100f, steps = 19), volume.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        val stop = node(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        val title = node(hasText(SHEET_TITLE))
        val order = listOf(heading, line, volume, stop, title).map { it.boundsInRoot.top }
        assertEquals("the name, its line, the volume, Stop casting, then Play on", order.sorted(), order)
        assertTrue("Play on a heading, as in the player's list, between the block's and Cast to", exists(hasText(SHEET_TITLE) and isHeading()))
        assertFalse("no pause with nothing of this phone's there", exists(hasContentDescription(pauseOn(kitchen.name))))
        assertTrue("the row keeps its ✓ and its line", exists(castRow(kitchen.name, CAST_CONNECTED) and isSelected()))
        assertEquals("Stop casting the block's alone", 1, compose.onAllNodes(hasText(STOP_CASTING)).fetchSemanticsNodes().size)
        assertEquals("nothing sent as it connected or showed", emptyList<Int>(), remote.volumes.toList())
        assertTrue("Google's Cast software unstarted", castUnstarted())
        // The phone's Wi-Fi gone a moment: the block waits, Stop casting still there.
        connector.suspended(kitchen)
        waitFor(hasContentDescription(RECONNECTING))
        compose.onNode(slider(kitchen.name)).assertIsNotEnabled()
        compose.onNode(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()).assertIsEnabled()
        connector.resumed(kitchen)
        waitFor(hasContentDescription(CAST_CONNECTED))
        compose.onNode(slider(kitchen.name)).assertIsEnabled()
    }

    /**
     * L117, L119 (CsPhoneSheetConnected 2): this phone's film there, "Playing Harbor Lights · S02E04" with pause, which goes
     * to the device; Stop casting ends the session with the device stopped, and Play on goes back to This phone.
     */
    @Test
    fun thisPhonesFilmThereHasPauseAndStopCastingEndsTheSessionWithTheDeviceStopped() {
        connected(kitchen)
        onMainThread { Casting.follows(CastFeed(load, launch, CastPlayerState.Playing, 1_930_000, 3_120_000)) }
        connector.media(kitchen, CastEvent.Status(CastPlayerState.Playing, null, load, 1_930_000, SystemClock.elapsedRealtime()))
        openSheet()
        node(hasText("Playing Harbor Lights · S02E04") and hasContentDescription("Playing Harbor Lights, season 2, episode 4"))
        tap(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { "pause" in remote.commands }
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        assertEquals("stopped, not left", 0, connector.disconnects.get())
        compose.waitUntil(5_000) { container.playOn.chosen.value == PlayChoice.Phone }
        compose.waitUntil(5_000) { !exists(heading(kitchen.name)) }
    }

    /**
     * W49, L119, L154 (CsPhoneSheetConnected 3): another phone's film there, by the title and line it gave, no pause, the
     * line saying Disconnect leaves it playing, and Disconnect, which takes this phone off without stopping the device
     * (Google's `endCurrentSession(false)`, the route let go as disconnected). One that gave no title is "Playing on
     * Kitchen display".
     */
    @Test
    fun anotherPhonesFilmHasDisconnectWhichLeavesItPlaying() {
        connected(kitchen)
        val other = CastEvent.Status(CastPlayerState.Playing, null, "another-phones-load", 0, SystemClock.elapsedRealtime(), title = "Copper Crown", subtitle = "S03E02")
        connector.media(kitchen, other)
        openSheet()
        node(hasText("Playing Copper Crown · S03E02") and hasContentDescription("Playing Copper Crown, S03E02"))
        assertTrue(exists(hasText(CAST_FROM_ANOTHER)))
        assertFalse("no pause: that phone sends it", exists(hasContentDescription(pauseOn(kitchen.name))))
        assertFalse(exists(hasContentDescription(stopCastingTo(kitchen.name))))
        // Its sender gave no title.
        connector.media(kitchen, other.copy(title = null, subtitle = null))
        waitFor(hasContentDescription(playingOn(kitchen.name)))
        tap(hasContentDescription(disconnectFrom(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { connector.disconnects.get() == 1 }
        assertEquals("the device left playing", 0, connector.ends.get())
        assertEquals(0, remote.stops.get())
        compose.waitUntil(5_000) { container.playOn.chosen.value == PlayChoice.Phone }
        compose.waitUntil(5_000) { !exists(heading(kitchen.name)) }
    }

    /**
     * L118 (CsPhoneRemoteC2 2): TalkBack's swipe moves it one 5 % step, which goes to the device; a drag sends each step
     * it reaches, once, as it moves; and the device's own change (its remote, another phone) moves the slider.
     */
    @Test
    fun aDragSendsEachStepItReachesAndTheSliderFollowsTheDevice() {
        connected(kitchen)
        openSheet()
        waitFor(slider(kitchen.name))
        swipedTo(kitchen.name, 45f)
        compose.waitUntil(5_000) { remote.volumes.isNotEmpty() }
        assertEquals(listOf(9), remote.volumes.toList())
        compose.waitUntil(5_000) { level(kitchen.name) == "45 percent" }
        compose.onNode(slider(kitchen.name)).performTouchInput { swipe(Offset(width * 0.45f, centerY), Offset(width * 0.9f, centerY), durationMillis = 800) }
        compose.waitUntil(5_000) { remote.volumes.size > 1 }
        compose.waitForIdle()
        val dragged = remote.volumes.drop(1)
        assertTrue("each step once, up the slider: $dragged", dragged.zipWithNext().all { (a, b) -> b > a } && dragged.first() > 9)
        assertTrue("to near the end: $dragged", dragged.last() >= 16)
        compose.waitUntil(5_000) { level(kitchen.name) == "${dragged.last() * 5} percent" }
        // The device's own remote turns it down: the slider follows, and nothing is sent for it.
        val sent = remote.volumes.size
        remote.deviceVolume(3)
        compose.waitUntil(5_000) { level(kitchen.name) == "15 percent" }
        assertEquals(sent, remote.volumes.size)
    }

    /**
     * M1-A: a change the device refuses (its session's level a second later still where it was) snaps the slider back,
     * and "Kitchen display sets its own volume: use its own remote." stays under it for the rest of the session, read
     * once by TalkBack as it appears (a polite live region), the slider still taking drags; the next session starts
     * without it.
     */
    @Test
    fun aChangeTheDeviceRefusesSnapsBackAndItsLineStaysForTheSession() {
        connected(kitchen)
        connector.volume = CastVolumeNow(8, muted = false)
        openSheet()
        waitFor(slider(kitchen.name))
        swipedTo(kitchen.name, 60f)
        compose.waitUntil(5_000) { remote.volumes.toList() == listOf(12) }
        val line = node(hasText(ownVolume(kitchen.name)))
        assertEquals("read once as it appears", LiveRegionMode.Polite, line.config.getOrNull(SemanticsProperties.LiveRegion))
        compose.waitUntil(5_000) { level(kitchen.name) == "40 percent" }
        compose.onNode(slider(kitchen.name)).assertIsEnabled()
        swipedTo(kitchen.name, 70f)
        compose.waitUntil(5_000) { remote.volumes.toList() == listOf(12, 14) }
        // The list closed and opened again: still said.
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        openSheet()
        waitFor(hasText(ownVolume(kitchen.name)))
        // At the level the session has, not the one the device's player still holds.
        compose.waitUntil(5_000) { level(kitchen.name) == "40 percent" }
        // A new session with the device: not said, until it refuses again.
        connector.ended(kitchen)
        compose.waitUntil(5_000) { !exists(heading(kitchen.name)) }
        // Connected with no list showing, as a list that shows closes for a session that connects as it is tapped.
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        CastFakes.connected(kitchen, connector)
        openSheet()
        waitFor(slider(kitchen.name))
        assertFalse(exists(hasText(ownVolume(kitchen.name))))
    }

    /**
     * L118, with a keyboard or a D-pad (RemoteKeys Q4): Right and Left on the focused slider move it one 5 % step each,
     * sent to the device as TalkBack's swipe is, the level read again a second after; Down goes on to Stop casting, as
     * Android's own sliders let Up and Down leave them.
     */
    @Test
    fun theArrowKeysMoveTheFocusedSliderOneStepAndDownLeavesIt() {
        connected(kitchen)
        connector.volume = CastVolumeNow(8, muted = false)
        openSheet()
        waitFor(slider(kitchen.name))
        val stop = hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()
        // The keyboard's way to the slider: on to Stop casting, then back to the slider before it.
        var tabs = 0
        while (!focused(stop)) {
            assertTrue("Stop casting never took the focus", tabs++ < 20)
            key(KeyEvent.KEYCODE_TAB)
        }
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        compose.waitUntil(5_000) { !focused(stop) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil("Right's step sent, not ${remote.volumes}", 5_000) { remote.volumes.toList() == listOf(9) }
        compose.waitUntil(5_000) { level(kitchen.name) == "45 percent" }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil("Left's steps sent, not ${remote.volumes}", 5_000) { remote.volumes.toList() == listOf(9, 8, 7) }
        compose.waitUntil(5_000) { level(kitchen.name) == "35 percent" }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil("Down on to Stop casting", 5_000) { focused(stop) }
        assertEquals("Down sent nothing", listOf(9, 8, 7), remote.volumes.toList())
    }

    /** M1-A: a route that says its volume is fixed keeps the slider enabled, with no line, and its drags go to the device. */
    @Test
    fun aRouteSayingItsVolumeIsFixedKeepsTheSliderAndItsDrags() {
        connected(den)
        openSheet()
        waitFor(heading(den.name))
        compose.onNode(slider(den.name)).assertIsEnabled()
        assertFalse(exists(hasText(ownVolume(den.name))))
        swipedTo(den.name, 50f)
        compose.waitUntil(5_000) { remote.volumes.toList() == listOf(10) }
        // The session's level unread (none said): nothing snaps back, and nothing is said.
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertEquals("50 percent", level(den.name))
        assertFalse(exists(hasText(ownVolume(den.name))))
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_661
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"
    }
}

package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.DisplayPorts
import io.skjaere.usenet.app.play.DisplayModeSwitcher
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.Passthrough
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Match the TV to the video's frame rate" in the player (TV-7's L7) on a phone emulator made a TV, its display a fake
 * one with a TV's modes ([FakeDisplay]; the emulators list one 60 Hz mode): the 23.976 fixture played from memory over
 * and over ([memoryFile]: it is a second long), its launch saying what the track probe would (23.976 fps, two hours).
 * Switched on, the window asks once for the 23.976 Hz mode and the player is made only once the TV runs it and has
 * settled, behind "Matching the TV to 23.976 Hz…"; off, nothing is asked and the player is made at once, Media3's own
 * frame-rate strategy left as it is. The waits' caps, OK, Back and Home, plays that leave the mode, Dolby Vision and the
 * HDMI audio waited for. Short timings.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FrameRateDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    /** The app let into picture-in-picture: Home during the TV's frame-rate wait must not put the player in the window. */
    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realRead = PlayerActivity.readPlayback
    private val realDataSources = PlayerActivity.dataSources
    private val realTimings = PlayerActivity.timings
    private val realTv = PlayerActivity.isTelevision
    private val realPort = DisplayPorts.of
    private val realLoadControl = PlayerActivity.loadControlFor
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val display = FakeDisplay()

    /** Players made, counted as each is made. */
    private val made = AtomicInteger()

    private val file = instrumentation.context.assets.open("rate-23976.mkv").use { it.readBytes() }

    @Before
    fun setUp() {
        PlayerActivity.dataSources = { memoryFile(file) }
        PlayerActivity.isTelevision = { true }
        DisplayPorts.of = display::port
        PlayerActivity.timings = PlayerActivity.Timings(switching = SHORT)
        PlayerActivity.loadControlFor = { made.incrementAndGet(); realLoadControl(it) }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        matching(true)
    }

    @After
    fun restore() {
        PlayerActivity.readPlayback = realRead
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.timings = realTimings
        PlayerActivity.isTelevision = realTv
        DisplayPorts.of = realPort
        PlayerActivity.loadControlFor = realLoadControl
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        container.displayHold.clear("the test ended")
        instrumentation.setInTouchMode(true)
        runBlocking {
            // Out of Up next and unwatched, once the player's last save is in.
            Thread.sleep(500)
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 1_000)
            container.library.markMovies(listOf(MovieMark(FILM, TITLE)), watched = false)
        }
    }

    private fun matching(on: Boolean) {
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = on) }
    }

    /** The invented film, the 23.976 fixture (under [nzb]), its launch saying [video] as the track probe would. */
    private fun launch(video: VideoTiming? = VideoTiming(24_000.0 / 1001, TWO_HOURS), nzb: String = NZB): PlayerLaunch {
        val name = "$RELEASE.mkv"
        val film = WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM)
        return PlayerLaunch(
            uri = UsenetUris.build(nzb, 0, name, file.size.toLong()), title = TITLE, subtitle = "2026", watchKey = film.key(nzb, 0), watch = film,
            release = SavedRelease("frame-rate-test", RELEASE.lowercase(), RELEASE, null, 0, nzb, name, file.size.toLong()), video = video,
        )
    }

    private fun play(launch: PlayerLaunch = launch()) = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch))

    @Test
    fun onAFilmAt23976TheTvIsAskedOnceAndThePlayerWaitsBehindTheLine() {
        display.respondAfterMs = null
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())
            // The TV is dark, switching: no player, the line on black.
            Thread.sleep(700)
            assertEquals(0, made.get())
            assertNull(scenario.read { it.player })
            assertEquals("Matching the TV to 23.976 Hz…", scenario.read(::moment))
            assertEquals(FakeDisplay.FILM.id, onMain { scenario.read { display.voteOf(it) } })

            display.respond()
            // Settling.
            Thread.sleep(SHORT.settleMs / 2)
            assertEquals("made before the TV settled", 0, made.get())
            waitFor("the player") { scenario.read { it.player } }
            assertTrue("made ${SystemClock.elapsedRealtime() - display.changedAt!!} ms after the change", SystemClock.elapsedRealtime() - display.changedAt!! >= SHORT.settleMs - 50)
            waitFor("playback to start") { scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_READY } } }
            assertEquals(null, scenario.read(::moment))
            // Media3's own (ONLY_IF_SEAMLESS): the window's mode does the switching.
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, scenario.read { it.player!!.videoChangeFrameRateStrategy })
            assertEquals(1, made.get())
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())
            assertEquals(0, display.listening)
        }
        val lines = logged(since)
        assertTrue(lines.toString(), "Frame rate: 23.976 fps; display 1920x1080 at 60 Hz: switching to 23.976 Hz (mode 2)" in lines)
        assertTrue(lines.toString(), lines.any { Regex("""Display switched in \d+(\.\d)? s, settled""").matches(it) })
        Log.i(TAG, "a matched play: ${lines.joinToString(" / ")}")
    }

    /** L2: off, as by default, the play is today's: nothing asked of the window or the display, no wait, Media3 as it is. */
    @Test
    fun offNothingIsAskedAndThePlayerIsMadeAtOnce() {
        matching(false)
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play().use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, scenario.read { it.player!!.videoChangeFrameRateStrategy })
            assertEquals(null, scenario.read(::moment))
        }
        assertEquals(emptyList<Pair<String, Int>>(), display.writes.toList())
        assertEquals(0, display.listening)
        assertEquals("no frame-rate line at INFO", emptyList<String>(), logged(since).filter { it.startsWith("Frame rate") || it.startsWith("Display ") })
    }

    /**
     * L2: on a phone (the device's kind, whatever the layouts) the setting on asks nothing either: nothing of the window
     * or the display, not even a mode a TV's hand-over held, no moment, the player made at once with Media3's strategy as
     * it is, no frame-rate line at INFO; one DEBUG line says why.
     */
    @Test
    fun onAPhoneTheSettingOnAsksNothing() {
        PlayerActivity.isTelevision = { false }
        val detailed = AndroidLogConfig.detailed
        AndroidLogConfig.detailed = true
        try {
            onMain {
                display.run(FakeDisplay.FILM)
                container.displayHold.handOver(FakeDisplay.FILM)
            }
            val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
            play().use { scenario ->
                waitFor("the player") { scenario.read { it.player } }
                assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, scenario.read { it.player!!.videoChangeFrameRateStrategy })
                assertEquals(null, scenario.read(::moment))
            }
            assertEquals(emptyList<Pair<String, Int>>(), display.writes.toList())
            assertEquals(0, display.listening)
            assertEquals("no frame-rate line at INFO", emptyList<String>(), logged(since).filter { it.startsWith("Frame rate") || it.startsWith("Display ") })
            val debug = LogBuffer.snapshot().filter { it.seq > since && it.tag == "PlayerActivity" && it.message.startsWith("Frame rate") }.map { it.message }
            assertEquals(listOf("Frame rate matching is off: not a TV"), debug)
        } finally {
            AndroidLogConfig.detailed = detailed
        }
    }

    @Test
    fun aTvThatNeverSwitchesIsPlayedOnAfterTheWait() {
        display.respondAfterMs = null
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            waitFor("the player", timeoutMs = 10_000) { scenario.read { it.player } }
            // From the ask as the display noted it: on a busy emulator the test sees it a few hundred ms late.
            val waited = SystemClock.elapsedRealtime() - display.askedAt!!
            assertTrue("played $waited ms after the ask", waited >= SHORT.changeMs)
        }
        val lines = logged(since)
        assertTrue(lines.toString(), "Display didn't switch within 1.5 s; playing at 60 Hz" in lines)
        Log.i(TAG, "a TV that never switched: ${lines.joinToString(" / ")}")
    }

    /** tv36's shape: one 60 Hz mode, nothing that shows 23.976 without pulldown. Left as it is, logged, played at once. */
    @Test
    fun noFittingModeNothingIsAsked() {
        display.modes = listOf(FakeDisplay.SIXTY)
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play().use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            assertEquals(null, scenario.read(::moment))
        }
        assertEquals(emptyList<Int>(), display.switches.toList())
        val lines = logged(since)
        assertTrue(lines.toString(), "Frame rate: 23.976 fps; display 1920x1080 at 60 Hz: left: no 23.976 Hz mode at 1920x1080" in lines)
        Log.i(TAG, "an unmatched play: ${lines.joinToString(" / ")}")
    }

    /** A clip under 5 minutes, and a play whose rate the probe couldn't read (Import, an old launch), ask nothing. */
    @Test
    fun aShortFileAndAnUnknownRateAskNothing() {
        listOf(VideoTiming(24_000.0 / 1001, 4 * 60_000L), null).forEach { video ->
            play(launch(video)).use { scenario ->
                val player = waitFor("the player") { scenario.read { it.player } }
                assertTrue(onMain { player.playWhenReady })
            }
        }
        assertEquals(emptyList<Int>(), display.switches.toList())
    }

    /** Back during the wait leaves: no player is ever made, the display isn't listened to any more. */
    @Test
    fun backDuringTheWaitLeavesWithoutAPlayer() {
        display.respondAfterMs = null
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            Thread.sleep(200)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        // Past where the wait would have ended.
        Thread.sleep(SHORT.capMs)
        assertEquals(0, made.get())
        assertEquals(0, display.listening)
    }

    /**
     * E5: a film played to its end with nothing next leaves, as Back does, and the mode its window asked for goes with it:
     * the TV back on its own. The file plays once here, not over and over.
     */
    @Test
    fun aFilmPlayedToItsEndLeavesAndTheTvGoesBackToItsOwnMode() {
        PlayerActivity.dataSources = { DataSource.Factory { ByteArrayDataSource(file) } }
        play().use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())
            waitFor("the player to leave at the film's end", timeoutMs = 10_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        waitFor("the TV back on its own mode") { display.active.takeIf { it == FakeDisplay.SIXTY } }
        assertEquals(listOf("PlayerActivity" to FakeDisplay.FILM.id, "PlayerActivity" to 0), display.writes.toList())
        assertEquals(1, display.releases.get())
    }

    /** A gamepad's B, which Android sends again as Back when nothing takes it, leaves during the wait as Back does. */
    @Test
    fun aGamepadsBLeavesDuringTheWait() {
        display.respondAfterMs = null
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            Thread.sleep(200)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
            waitFor("the player to close", timeoutMs = 3_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(0, made.get())
        assertEquals(0, display.listening)
    }

    /**
     * Back as the wait ends (here the TV switches as the key is let go, with nothing to settle): the activity is leaving,
     * and its onStop comes only once the screen below shows. No player is made in between for the play left.
     */
    @Test
    fun backAsTheWaitEndsLeavesWithoutAPlayer() {
        display.respondAfterMs = null
        PlayerActivity.timings = PlayerActivity.Timings(switching = SHORT.copy(settleMs = 0))
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            Thread.sleep(200)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            display.respond()
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals("players made after Back", 0, made.get())
        assertEquals(0, display.listening)
    }

    /**
     * Home during the wait stops the setup: no player plays in the background, the window asks for nothing more. Back,
     * the TV went back to its own mode meanwhile: asked again, waited for again, then played.
     */
    @Test
    fun homeDuringTheWaitAsksAgainOnReturn() {
        display.respondAfterMs = null
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(SHORT.capMs)
            assertEquals(0, made.get())
            assertEquals(0, display.listening)
            assertEquals(0, onMain { scenario.read { display.voteOf(it) } })

            display.respondAfterMs = 300
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the player") { scenario.read { it.player } }
            assertEquals(listOf(FakeDisplay.FILM.id, FakeDisplay.FILM.id), display.switches.toList())
            assertEquals(1, made.get())
        }
    }

    /** OK during the moment plays at once, whatever is left of the wait. */
    @Test
    fun okPlaysAtOnce() {
        display.respondAfterMs = null
        PlayerActivity.timings = PlayerActivity.Timings(switching = SHORT.copy(changeMs = 8_000, capMs = 10_000))
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        instrumentation.setInTouchMode(false)
        play().use { scenario ->
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            val asked = SystemClock.elapsedRealtime()
            Thread.sleep(300)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the player", timeoutMs = 3_000) { scenario.read { it.player } }
            assertTrue("OK didn't end the wait", SystemClock.elapsedRealtime() - asked < 3_000)
            assertEquals(null, scenario.read(::moment))
            // The OK was the moment's: playback runs, not paused by it.
            assertTrue(scenario.read { it.player!!.playWhenReady })
        }
        assertTrue(logged(since).toString(), logged(since).any { it.endsWith("; OK pressed: playing at once") })
    }

    /**
     * W106: a player made again after a stop waits paused, but OK during the moment is the user's Play, there too. Home
     * with the film playing, back: the TV gone back to its own mode meanwhile is asked again, and OK during that wait
     * plays at once.
     */
    @Test
    fun okDuringTheWaitAfterAStopPlaysAtOnce() {
        PlayerActivity.timings = PlayerActivity.Timings(switching = SHORT.copy(changeMs = 8_000, capMs = 10_000))
        instrumentation.setInTouchMode(false)
        play().use { scenario ->
            waitFor("playback to start") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor("the TV back on its own mode") { display.active.takeIf { it == FakeDisplay.SIXTY } }
            display.respondAfterMs = null
            val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the switch to be asked for again") { display.switches.takeIf { it.size == 2 } }
            val asked = SystemClock.elapsedRealtime()
            Thread.sleep(300)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val player = waitFor("the player", timeoutMs = 3_000) { scenario.read { it.player } }
            assertTrue("OK didn't end the wait", SystemClock.elapsedRealtime() - asked < 3_000)
            assertTrue("made paused after OK", onMain { player.playWhenReady })
            assertEquals(2, made.get())
            assertTrue(logged(since).toString(), logged(since).any { it.endsWith("; OK pressed: playing at once") })
        }
    }

    /** A Dolby Vision file, on a TV that showed Dolby Vision: the player waits for Dolby Vision to be back after the switch. */
    @Test
    fun aDolbyVisionFileWaitsForDolbyVision() {
        display.hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10)
        display.onSwitch = { display.hdr = setOf(Hdr.Hdr10) }
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play(launch(VideoTiming(24_000.0 / 1001, TWO_HOURS, dolbyVision = true))).use { scenario ->
            waitFor("the TV to switch") { display.changedAt }
            Thread.sleep(SHORT.settleMs + 500)
            assertEquals("made before Dolby Vision was back", 0, made.get())
            display.hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10)
            waitFor("the player") { scenario.read { it.player } }
        }
        assertTrue(logged(since).toString(), logged(since).any { it.endsWith(", settled; HDR back") })
    }

    /** What passed through over HDMI before the request is back before the player is made (the PCM fallback, Media3 #2258). */
    @Test
    fun theHdmiAudioIsWaitedFor() {
        val dd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3))
        display.route = dd
        display.onSwitch = { display.route = Passthrough.NONE }
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        play().use { scenario ->
            waitFor("the TV to switch") { display.changedAt }
            Thread.sleep(SHORT.settleMs + 500)
            assertEquals("made before the HDMI audio was back", 0, made.get())
            display.route = dd
            waitFor("the player") { scenario.read { it.player } }
        }
        assertTrue(logged(since).toString(), logged(since).any { it.endsWith(", settled; passthrough back") })
    }

    /**
     * A play started in the open player (#154) keeps the window's mode until its own plan: the same rate asks for nothing
     * again and the TV never goes back between them; one whose rate isn't known gives the mode up, the TV back once.
     */
    @Test
    fun aPlayStartedInTheOpenPlayerKeepsTheModeUntilItsOwnPlan() {
        play().use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())

            val second = launch(nzb = "$NZB-second")
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the second play") { scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri } } }
            assertEquals(listOf("PlayerActivity" to FakeDisplay.FILM.id), display.writes.toList())
            assertEquals(0, display.releases.get())

            val third = launch(video = null, nzb = "$NZB-third")
            context.startActivity(PlayerActivity.intent(context, third).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the third play") { scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == third.uri } } }
            assertEquals(listOf("PlayerActivity" to FakeDisplay.FILM.id, "PlayerActivity" to 0), display.writes.toList())
            assertEquals(1, display.releases.get())
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())
        }
    }

    /**
     * A mode handed over as the app went to the background (Home as the next episode started, #142), the TV back on its
     * own mode since: the next player doesn't ask for it as it is made, which would switch the TV before the moment shows
     * and with nothing waiting for it. The hold goes, and the play's own plan switches the TV, once, behind the moment.
     */
    @Test
    fun aHandedOverModeTheDisplayHasLeftIsNotAskedForAsThePlayerIsMade() {
        display.respondAfterMs = null
        onMain { container.displayHold.handOver(FakeDisplay.FILM) }
        play().use { scenario ->
            waitFor("the moment") { scenario.read(::moment) }
            waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
            // Past where the switcher would ask again.
            Thread.sleep(SHORT.beforeRequestMs + 300)
            assertEquals("the switches asked for", listOf(FakeDisplay.FILM.id), display.switches.toList())
            assertEquals(listOf("PlayerActivity" to FakeDisplay.FILM.id), display.writes.toList())
            assertNull(container.displayHold.held.value)
            display.respond()
            waitFor("the player") { scenario.read { it.player } }
        }
    }

    /**
     * The HDMI audio route dropped as the TV switches (its re-sync), for longer than a loss's 2 s hold: the app's ranking
     * doesn't take it for lost while the switch is under way, and back before the switch ends, nothing changed or was said.
     */
    @Test
    fun aRouteDroppedAsTheTvSwitchesIsNotTakenForLost() {
        val withTrueHd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD))
        runBlocking { container.device.get() }
        val original = container.device.latest!!.passthrough
        assumeTrue("this device already passes DD, DD+ and TrueHD through", original != withTrueHd)
        // A receiver switched on before the play.
        container.device.audioRouteChanged(withTrueHd)
        try {
            display.respondAfterMs = null
            display.onSwitch = { container.device.audioRouteChanged(Passthrough.NONE) }
            PlayerActivity.timings = PlayerActivity.Timings(switching = SHORT.copy(changeMs = 4_000, capMs = 5_000))
            val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
            play().use { scenario ->
                waitFor("the switch to be asked for") { display.switches.takeIf { it.isNotEmpty() } }
                Thread.sleep(3_000)
                assertEquals("the route taken for lost during the switch", emptyList<String>(), routeLines(since))
                container.device.audioRouteChanged(withTrueHd)
                display.respond()
                waitFor("the player") { scenario.read { it.player } }
            }
            assertEquals(emptyList<String>(), routeLines(since))
            assertEquals(withTrueHd, container.device.latest?.passthrough)
        } finally {
            // As the platform reads it; a loss counts once it has held 2 s.
            container.device.audioRouteChanged(original)
            waitFor("the route put back", timeoutMs = 8_000) { container.device.latest?.passthrough?.takeIf { it == original } }
        }
    }

    private fun routeLines(since: Long) = LogBuffer.snapshot().filter { it.seq > since && it.message.startsWith("Audio route changed") }.map { it.message }

    /** The moment's line as it shows; null while hidden. */
    private fun moment(activity: Activity): String? =
        views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.startsWith("Matching the TV") }?.text?.toString()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    /** The player's INFO lines since the log's line [since]. */
    private fun logged(since: Long) = LogBuffer.snapshot().filter { it.seq > since && it.tag == "PlayerActivity" && it.level.toInt() >= org.slf4j.event.Level.INFO.toInt() }.map { it.message }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "FrameRateDeviceTest"
        /** An invented film; nothing real has the id. */
        const val FILM = 900731
        const val TITLE = "Slow Harbour"
        const val RELEASE = "Slow.Harbour.2026.1080p.WEB-DL.H.264-NZST"
        const val NZB = "frame-rate-23976"
        const val TWO_HOURS = 2 * 60 * 60_000L

        /** The waits, short: the TV's change at most 1.5 s, 0.5 s to settle, 2.5 s in all, 4 s for Dolby Vision. */
        val SHORT = DisplayModeSwitcher.Timings(beforeRequestMs = 200, changeMs = 1_500, settleMs = 500, capMs = 2_500, dolbyVisionCapMs = 4_000, pollMs = 50)
    }
}

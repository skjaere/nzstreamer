package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.ScrubRate
import io.skjaere.usenet.app.play.SpeedCue
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Seeking by holding the remote's Right on a real TV (tv36), with the controls hidden and on the focused time bar (L2 to
 * L8): nothing seeks while the key is down and one seek follows its up, as far as [ScrubRate] says for how long it was
 * held. Hidden, the label says how far and where to, with the speed cue's three chevrons at the top speed (W8), and the
 * controls stay hidden; on the bar, the bar follows the target and the controls stay up past their timeout. tv36 takes
 * the test's key events; their times say how long the key is held. It takes them from HDMI-CEC too, as a TV's own
 * remote sends a held key there, a press and a release over and over, and taps the same way ([cecTap]). The release is chapters.mkv (six minutes, its chapters left out) posted on a server inside the test's
 * process ([LocalNntp]); the film is invented (TMDB id 900036).
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvHeldSeekTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realChapters = PlayerActivity.readChapters
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM))

    @Before
    fun setUp() {
        assumeTv()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readChapters = { emptyList() }
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        local.close()
        runBlocking {
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, DURATION_MS)
        }
    }

    /** Right held 3 s with the controls hidden: past the doubling to the top speed, one seek a second after the up. */
    @Test
    fun aHeldRightWithTheControlsHiddenSeeksOnceAfterTheUpTheLabelSayingWhere() = play { scenario, seen ->
        hideControls(scenario)
        val down = SystemClock.uptimeMillis() - 3_000
        fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
        instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        (1..6).forEach { n -> instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n)) }
        val jump = ScrubRate.offsetMs(3_000, duration)
        val target = first - ScrubRate.offsetMs(0, duration) + jump
        scenario.read { a ->
            assertEquals(target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)} · ${time(target)}", label(a)?.text?.toString())
            assertEquals("the speed cue's chevrons lit at the top speed", 3, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
        }
        instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 3_000, 0))
        delay(600)
        assertEquals("sought before a second after a fast hold's up", emptyList<Long>(), seen.toList())
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
        scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
    }

    /** Right held on the focused time bar over 6 s, past the controls' 5 s timeout: they stay up, the bar following. */
    @Test
    fun aHeldRightOnTheFocusedTimeBarKeepsTheControlsUpAndSeeksOnceAfterTheUp() = play { scenario, seen ->
        scenario.onActivity { a -> playerView(a).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        scenario.onActivity { a -> assertTrue(timeBar(a).requestFocus()) }
        val down = SystemClock.uptimeMillis()
        fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
        instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        (1..15).forEach { n ->
            delay(400)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 100L, n))
            scenario.read { a ->
                assertEquals("the bar at repeat $n", barTime(playerView(a).player!!.currentPosition), timeBar(a).contentDescription.toString())
                assertTrue("the controls went, at repeat $n", playerView(a).isControllerFullyVisible && timeBar(a).isFocused)
            }
            assertEquals("sought while held, at repeat $n", emptyList<Long>(), seen.toList())
        }
        val jump = ScrubRate.offsetMs(1_500, duration)
        val target = first - ScrubRate.offsetMs(0, duration) + jump
        scenario.read { a ->
            assertEquals(target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)}", label(a)?.text?.toString())
        }
        instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_500, 0))
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
        scenario.read { a -> assertTrue("the controls went", playerView(a).isControllerFullyVisible) }
    }

    /**
     * Right held about 3 s on a TV's own remote over HDMI-CEC with the controls hidden, as many TVs send it: a press
     * and a release 71 ms later every 250 ms ([cecTap]). One hold all the same, timed from its first press: as far as
     * [ScrubRate] says, the top speed's chevrons, the controls hidden, and one seek a second after the last up.
     */
    @Test
    fun aRightHeldOverHdmiCecAsAPressAndAReleaseFourTimesASecondGoesOnTheCurve() = play { scenario, seen ->
        hideControls(scenario)
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT).press
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        scenario.read { a -> assertEquals("the press over HDMI-CEC reached the player", "+10 s · ${time(first)}", label(a)?.text?.toString()) }
        val last = (1..12).map { n -> cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, press.eventTime + n * 250L).press }.last()
        val heldMs = last.eventTime - press.eventTime
        val jump = ScrubRate.offsetMs(heldMs, duration)
        val target = first - ScrubRate.offsetMs(0, duration) + jump
        scenario.read { a ->
            assertEquals("where $heldMs ms held goes", target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)} · ${time(target)}", label(a)?.text?.toString())
            assertEquals("the speed cue's chevrons lit at the top speed, $heldMs ms held", 3, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
        }
        assertEquals("sought while held", emptyList<Long>(), seen.toList())
        delay(600)
        assertEquals("sought before a second after a fast hold's last up", emptyList<Long>(), seen.toList())
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
        scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
    }

    /**
     * Right held about 3 s on a TV's own remote over HDMI-CEC with the controls hidden, its presses and releases as the Shield
     * had them from it (its CEC history): the first repeat 501 ms after the press, then a beat that
     * jitters (357, 166, 233, then about 250 ms). The first three go 10 s each, as taps; the fourth, three presses within
     * [ScrubRate.CEC_HOLD_WITHIN_MS], makes them a hold, timed from the first press: as far as [ScrubRate] says by the last,
     * the top speed's chevrons, the controls hidden, one seek a second after the last up. The label is no live region
     * between the presses, and TalkBack hears the hold once, a moment after its last up (L8).
     */
    @Test
    fun aRightHeldOverHdmiCecSpeedsUpFromItsFourthPress() = play { scenario, seen ->
        hideControls(scenario)
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, pressMs = 75).press
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        val start = first - ScrubRate.offsetMs(0, duration)
        val last = CEC_RIGHT.mapIndexed { n, (down, up) ->
            cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, press.eventTime + down, pressMs = up - down).also {
                scenario.read { a ->
                    if (n < 2) assertEquals("press ${n + 2}, a tap", start + 10_000L * (n + 2), playerView(a).player!!.currentPosition)
                    if (n == 2) assertEquals("press 4, the hold's", start + ScrubRate.offsetMs(down, duration), playerView(a).player!!.currentPosition)
                    assertEquals("a live region after press ${n + 2}'s up", View.ACCESSIBILITY_LIVE_REGION_NONE, momentLabel(a).accessibilityLiveRegion)
                }
            }
        }.last()
        val heldMs = last.press.eventTime - press.eventTime
        val jump = ScrubRate.offsetMs(heldMs, duration)
        val target = start + jump
        scenario.read { a ->
            assertEquals("where $heldMs ms held goes", target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)} · ${time(target)}", label(a)?.text?.toString())
            assertEquals("the speed cue's chevrons lit at the top speed, $heldMs ms held", 3, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
        }
        assertEquals("sought while held", emptyList<Long>(), seen.toList())
        delay(600)
        scenario.read { a ->
            assertEquals("no live region once the hold was over", View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
            val spoken = momentLabel(a).contentDescription?.toString()
            assertTrue("TalkBack hears \"$spoken\"", spoken != null && spoken.startsWith("Forward ") && spoken.endsWith(", ${time(target)}"))
        }
        assertEquals("sought before a second after a fast hold's last up", emptyList<Long>(), seen.toList())
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
        scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
    }

    /**
     * CSK-1: Right tapped five times over HDMI-CEC with the controls hidden, as a TV's remote sent them, about
     * 400 ms apart, then held from 329 ms after the last up, as recorded: one run, but the hold is timed from its own
     * first press. The taps and the hold's first three presses go 10 s each; its fourth, 1,024 ms in, as far as the five
     * taps and the curve for 1,024 ms say, with no speed cue until 2 s into the hold; at its last, 3 s in, the top
     * speed's chevrons. One seek, a second after the last up.
     */
    @Test
    fun tapsOfRightOverHdmiCecThenRightHeldWithinTheLiftTimeTheHoldFromItsOwnFirstPress() = play { scenario, seen ->
        hideControls(scenario)
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT).press
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        val start = first - ScrubRate.offsetMs(0, duration)
        CEC_TAPS.forEach { (down, up) -> cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, press.eventTime + down, pressMs = up - down) }
        val hold = press.eventTime + CEC_TAPS.last().second + 329
        cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, hold, pressMs = 75)
        val taps = 50_000L
        val last = CEC_RIGHT.mapIndexed { n, (down, up) ->
            cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, hold + down, pressMs = up - down).also {
                scenario.read { a ->
                    val at = playerView(a).player!!.currentPosition
                    if (n < 2) assertEquals("the hold's press ${n + 2}, a tap", start + 10_000L * (n + 7), at)
                    if (n == 2) assertEquals("the hold's press 4, $down ms into it", start + taps + ScrubRate.offsetMs(down, duration), at)
                    if (down < 2_000) assertEquals("the speed cue $down ms into the hold", null, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
                }
            }
        }.last()
        val heldMs = last.press.eventTime - hold
        val jump = ScrubRate.offsetMs(heldMs, duration)
        val target = start + taps + jump
        scenario.read { a ->
            assertEquals("where the taps and $heldMs ms held go", target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(taps + jump + 500)} · ${time(target)}", label(a)?.text?.toString())
            assertEquals("the speed cue's chevrons lit at the top speed, $heldMs ms into the hold", 3, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
        }
        assertEquals("sought while held", emptyList<Long>(), seen.toList())
        delay(600)
        assertEquals("sought before a second after a fast hold's last up", emptyList<Long>(), seen.toList())
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
        scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
    }

    /**
     * Right pressed about 3.5 s over HDMI-CEC with the controls hidden, a press and a release every 490 ms, each within
     * the lift of the up before but never three within [ScrubRate.CEC_HOLD_WITHIN_MS]: taps of one run, as a TV
     * repeating that slowly would send. TalkBack hears them once, how far and where to, a moment after the last up. The
     * label is no live region between the presses (L8): the words each up puts off are dropped as the next press comes,
     * not said while it is down; the beat has them due 31 ms after it.
     */
    @Test
    fun aRunOfRightsOverHdmiCecIsSaidOnceAMomentAfterItsLastUp() = play { scenario, _ ->
        hideControls(scenario)
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT).press
        (1..7).forEach { n ->
            cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, press.eventTime + n * 490L)
            scenario.read { a -> assertEquals("a live region after press $n's up", View.ACCESSIBILITY_LIVE_REGION_NONE, momentLabel(a).accessibilityLiveRegion) }
        }
        val target = scenario.read { a -> playerView(a).player!!.currentPosition }
        delay(600)
        scenario.read { a ->
            assertEquals("no live region once the run was over", View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
            val spoken = momentLabel(a).contentDescription?.toString()
            assertTrue("TalkBack hears \"$spoken\"", spoken != null && spoken.startsWith("Forward ") && spoken.endsWith(", ${time(target)}"))
        }
    }

    /**
     * Right held about 2.5 s over HDMI-CEC on the focused time bar, a press and a release every 250 ms: the bar follows
     * the hold's curve and the controls stay up. A tap of Left 0.3 s after the last up, within the lift, takes 10 s off
     * it, and the one seek goes there. OK, which doesn't seek there, pressed in the lift after Left's up: the words that
     * up put off, how far and where to, are said as it goes down, before it acts (L8).
     */
    @Test
    fun aRightHeldOverHdmiCecOnTheFocusedTimeBarGoesOnTheCurveAndATapOfLeftAfterItTakesTenSecondsOff() = play { scenario, seen ->
        scenario.onActivity { a -> playerView(a).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        scenario.onActivity { a -> assertTrue(timeBar(a).requestFocus()) }
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT).press
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        val last = (1..10).map { n ->
            cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, press.eventTime + n * 250L).also {
                scenario.read { a ->
                    assertEquals("the bar at press $n", barTime(playerView(a).player!!.currentPosition), timeBar(a).contentDescription.toString())
                    assertTrue("the controls went, at press $n", playerView(a).isControllerFullyVisible && timeBar(a).isFocused)
                }
            }
        }.last()
        val heldMs = last.press.eventTime - press.eventTime
        val jump = ScrubRate.offsetMs(heldMs, duration)
        val target = first - ScrubRate.offsetMs(0, duration) + jump
        scenario.read { a ->
            assertEquals("where $heldMs ms held goes", target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)}", label(a)?.text?.toString())
        }
        assertEquals("sought while held", emptyList<Long>(), seen.toList())
        val left = cecTap(KeyEvent.KEYCODE_DPAD_LEFT, inTheLift(last.up, 300))
        scenario.read { a -> assertEquals("Left's tap after the hold", target - 10_000, playerView(a).player!!.currentPosition) }
        val ok = cecKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, inTheLift(left.up, 150))
        scenario.read { a ->
            assertEquals("a live region as OK went down", View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
            val spoken = momentLabel(a).contentDescription?.toString()
            assertTrue("TalkBack hears \"$spoken\" before OK acts", spoken != null && spoken.startsWith("Forward ") && spoken.endsWith(", ${time(target - 10_000)}"))
        }
        cecKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, ok.eventTime + CEC_PRESS_MS)
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target - 10_000), seen.toList())
        scenario.read { a -> assertTrue("the controls went", playerView(a).isControllerFullyVisible) }
    }

    /**
     * Quick taps of Right over HDMI-CEC with the controls hidden for about 3.5 s, a press and a release each, 260 to 400
     * ms apart but never three within [ScrubRate.CEC_HOLD_WITHIN_MS]: 10 s each however long they go on, never the speed
     * cue, and one seek after the last.
     */
    @Test
    fun quickTapsOfRightOverHdmiCecNeverThreeWithinTheWindowGoTenSecondsEach() = play { scenario, seen ->
        hideControls(scenario)
        val press = cecTap(KeyEvent.KEYCODE_DPAD_RIGHT).press
        val first = scenario.read { a -> playerView(a).player!!.currentPosition }
        val downs = listOf(300L, 380, 270, 350, 290, 400, 280, 360, 310, 390, 260).runningFold(press.eventTime, Long::plus)
        downs.drop(1).forEachIndexed { n, at ->
            cecTap(KeyEvent.KEYCODE_DPAD_RIGHT, at)
            scenario.read { a ->
                val target = first + 10_000L * (n + 1)
                val far = (n + 2) * 10L
                assertEquals("tap ${n + 2}, ${at - press.eventTime} ms in", target, playerView(a).player!!.currentPosition)
                assertEquals("+${if (far < 60) "$far s" else time(far * 1_000)} · ${time(target)}", label(a)?.text?.toString())
                assertEquals("the speed cue at tap ${n + 2}", null, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
            }
        }
        assertEquals("sought while tapping", emptyList<Long>(), seen.toList())
        waitFor("the seek", timeoutMs = 5_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(first + 10_000L * (downs.size - 1)), seen.toList())
        scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
    }

    /**
     * CST-1 (#321): Right held on a TV's own remote over HDMI-CEC that sends a held key as repeats without a release
     * (NXT-1), each event with its own time as its downTime, its press taken by File details, which close as it is held
     * (by Back's callback here, as the skip button goes or the TV's frame-rate switch ends, with no key): its repeats,
     * 500 ms after the press and then every 250 ms, seek with the controls hidden as far as [ScrubRate] says for the time
     * since its press, 30 s at the first, the top speed's chevrons by 3 s; not 10 s once and then nothing. CSK-3: its up
     * ends the hold as any held key's does: TalkBack hears where it went once the lift has passed, and the one seek
     * comes a second after the up, not two after the last repeat.
     */
    @Test
    fun rightHeldOverHdmiCecAsRepeatsWhosePressDidNotSeekGoesOnTheCurveFromItsPressAndItsUpEndsTheHold() = play { scenario, seen ->
        hideControls(scenario)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
        waitFor("File details") { scenario.read { a -> details(a) } }
        val press = cecKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis())
        scenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
        waitFor("File details to close") { scenario.read { a -> (details(a) == null).takeIf { it } } }
        val firstRepeat = cecKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, press.eventTime + 500, repeat = 1)
        val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
        scenario.read { a -> assertEquals("its first repeat, 500 ms after its press", "+30 s · ${time(first)}", label(a)?.text?.toString()) }
        val last = (2..11).map { n -> cecKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, firstRepeat.eventTime + (n - 1) * 250L, repeat = n) }.last()
        val heldMs = last.eventTime - press.eventTime
        val jump = ScrubRate.offsetMs(heldMs, duration)
        val target = first - ScrubRate.offsetMs(500, duration) + jump
        scenario.read { a ->
            assertEquals("where $heldMs ms held goes", target, playerView(a).player!!.currentPosition)
            assertEquals("+${time(jump + 500)} · ${time(target)}", label(a)?.text?.toString())
            assertEquals("the speed cue's chevrons lit at the top speed, $heldMs ms held", 3, (label(a)?.compoundDrawablesRelative?.first() as? SpeedCue)?.lit)
            assertFalse("the controls came up", playerView(a).isControllerFullyVisible)
        }
        assertEquals("sought while held", emptyList<Long>(), seen.toList())
        val up = cecKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, last.eventTime + 100)
        delay(600)
        scenario.read { a ->
            assertEquals("no live region once the hold was over", View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
            val spoken = momentLabel(a).contentDescription?.toString()
            assertTrue("TalkBack hears \"$spoken\"", spoken != null && spoken.startsWith("Forward ") && spoken.endsWith(", ${time(target)}"))
        }
        assertEquals("sought before a second after a fast hold's up", emptyList<Long>(), seen.toList())
        delay(up.eventTime + 1_500 - SystemClock.uptimeMillis())
        assertEquals("sought a second after the up", listOf(target), seen.toList())
        delay(1_000)
        assertEquals(listOf(target), seen.toList())
    }

    /** A press and its release over HDMI-CEC. */
    private data class Tap(val press: KeyEvent, val up: KeyEvent)

    /**
     * [code] pressed on a TV's own remote over HDMI-CEC at [at] (uptime) and let go [pressMs] later, as Android's CEC
     * service passes each <User Control Pressed> and <User Control Released> on (AOSP's HdmiCecLocalDevice): from the
     * virtual device, its source HDMI, each event its own time as its downTime. Each is sent at its time, which it keeps
     * however late the test's thread comes to it, so the presses' beat is the one asked for.
     */
    private suspend fun cecTap(code: Int, at: Long = SystemClock.uptimeMillis(), pressMs: Long = CEC_PRESS_MS): Tap =
        Tap(cecKey(code, KeyEvent.ACTION_DOWN, at), cecKey(code, KeyEvent.ACTION_UP, at + pressMs))

    /** [code] going down or up ([action]) over HDMI-CEC at [at], sent then: a down the [repeat]th repeat of a held key, as Android's CEC service sends one (NXT-1). */
    private suspend fun cecKey(code: Int, action: Int, at: Long, repeat: Int = 0): KeyEvent =
        KeyEvent(at, at, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_HDMI).also { key ->
            delay(at - SystemClock.uptimeMillis())
            instrumentation.sendKeySync(key)
        }

    /**
     * [afterMs] after [up], a key's up over HDMI-CEC, waited for: the time then, which must still be within the lift
     * ([ScrubRate.CEC_LIFT_MS]), while the words the up put off wait.
     */
    private suspend fun inTheLift(up: KeyEvent, afterMs: Long): Long {
        val at = up.eventTime + afterMs
        delay(at - SystemClock.uptimeMillis())
        val since = SystemClock.uptimeMillis() - up.eventTime
        assertTrue("$since ms after the up, past the lift", since < ScrubRate.CEC_LIFT_MS)
        return at
    }

    /** chapters.mkv posted and played from the start as the invented film; [check] with it playing and its seeks watched from there. */
    private fun play(check: suspend (ActivityScenario<PlayerActivity>, CopyOnWriteArrayList<Long>) -> Unit) = runBlocking<Unit> {
        val mkv = fixtures.media("chapters.mkv")
        val release = fixtures.release("chapters.mkv", mkv, 100_000, "tv-held-seek", RELEASE) { emptySet() }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, GUID, 0))).use { scenario ->
            waitFor("playback", timeoutMs = 40_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition > 0 } } }
            val seeks = CopyOnWriteArrayList<Long>()
            scenario.onActivity { a ->
                a.player!!.addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks += newPosition.positionMs
                        }
                    },
                )
            }
            check(scenario, seeks)
        }
    }

    /** Hidden, with their hide animation over: the focus is then PlayerView's, as a remote finds the player playing. */
    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a -> playerView(a).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
        delay(800)
    }

    private fun playerView(a: PlayerActivity) = views(a.window.decorView).filterIsInstance<PlayerView>().single()

    private fun timeBar(a: PlayerActivity): View = a.findViewById(androidx.media3.ui.R.id.exo_progress)

    /** The label under the top bar, while it shows: not a live region while the key is held (L8). */
    private fun label(a: PlayerActivity) = momentLabel(a).takeIf { it.isShown }

    private fun momentLabel(a: PlayerActivity) = a.findViewById<TextView>(R.id.moment_label)

    /** File details, while they show. */
    private fun details(a: PlayerActivity): SemanticsNode? =
        views(a.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }.flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** How media3's controls write [ms], to the nearest second: the time bar's contentDescription. */
    private fun barTime(ms: Long): String = StringBuilder().let { b -> Util.getStringForTime(b, Formatter(b, Locale.getDefault()), ms) }

    /** How the player writes [ms]. */
    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        /** An invented film; nothing real has the id. */
        const val FILM = 900036
        const val TITLE = "Copper Weir"
        const val RELEASE = "Copper.Weir.2026.1080p.WEB.h264-HELD"
        const val GUID = "copper-weir-tv"
        const val INDEXER = "tv-held-seek-test"
        /** The chapters fixture's six minutes. */
        const val DURATION_MS = 360_000L
        /** How long a TV's remote holds each press over HDMI-CEC: its release as soon after as the bus carries it. */
        const val CEC_PRESS_MS = 71L
        /**
         * Right tapped on a TV's remote, after its first tap (at 0, let go at 71 ms): each later tap's down and up, in ms
         * from it, as the Shield's HDMI-CEC history had them (CEC-2).
         */
        val CEC_TAPS = listOf(399L to 470L, 799L to 870L, 1_139L to 1_210L, 1_538L to 1_609L)
        /**
         * Right held on a TV's remote, after its first press (at 0, let go at 75 ms): each later press's down and up, in ms
         * from it, as the Shield's HDMI-CEC history had them (CEC-2).
         */
        val CEC_RIGHT = listOf(
            501L to 572L, 858L to 929L, 1_024L to 1_095L, 1_257L to 1_328L, 1_508L to 1_579L, 1_761L to 1_831L, 2_011L to 2_082L, 2_263L to 2_334L,
            2_517L to 2_588L, 2_766L to 2_838L, 3_018L to 3_089L,
        )
    }
}

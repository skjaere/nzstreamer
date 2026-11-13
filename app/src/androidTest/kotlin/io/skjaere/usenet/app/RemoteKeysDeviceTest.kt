package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.ScrubRate
import io.skjaere.usenet.app.play.SpeedCue
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.ShowTrackChoice
import io.skjaere.usenet.data.SubtitleMode
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * A remote in the player end to end (RemoteKeys, TV-PC), on a phone in non-touch mode as a TV's
 * remote leaves it: the chapters.mkv fixture (six minutes of test pattern with an intro at 5–25 s,
 * see SkipSegmentsDeviceTest; its chapters left out but where a test needs the skip button)
 * opened as an NZB file is. With the controls hidden OK pauses and brings them, focused on
 * play/pause, where OK plays again; Left and Right seek with them still hidden, a burst of presses
 * and a held key making one seek, said by the label with where it goes. On the focused time bar
 * Left and Right seek the same way, and ⏩ anywhere, the controls staying up; nothing seeks while
 * a key is held, and TalkBack hears where it went once, at its up. Back hides the controls
 * before it leaves, but in touch mode. The captions and audio track keys go through the tracks.mkv
 * fixture's tracks (see TrackSheetDeviceTest) and are saved for the show, as the sheet's picks are.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RemoteKeysDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realChapters = PlayerActivity.readChapters
    private val realRead = PlayerActivity.readPlayback
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val levelBefore = AndroidLogConfig.minLevel
    private lateinit var chapters: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            chapters = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
            container.library.clearTrackChoice(SHOW)
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readChapters = { emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        PlayerActivity.readPlayback = realRead
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        AndroidLogConfig.minLevel = levelBefore
        instrumentation.setInTouchMode(true)
        runBlocking { container.library.clearTrackChoice(SHOW) }
    }

    /** The fixture opened as an NZB file is: no launch, so no resume, no saved progress and no next episode. */
    private fun fileIntent(nzb: PreparedNzb = chapters) = Intent(context, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(nzb.id, nzb.defaultPick!!.index))

    @Test
    fun withTheControlsHiddenOkPausesAndBringsThemAndOkOnPlayPausePlaysAgain() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            hideControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the pause, with the controls up and play/pause focused", timeoutMs = 3_000) {
                scenario.read { a -> (!a.player!!.playWhenReady && playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("playback again", timeoutMs = 3_000) { scenario.read { a -> a.player!!.playWhenReady.takeIf { it } } }
            // Enter, as a keyboard's, is OK too.
            hideControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            waitFor<Boolean>("the pause after Enter, with the controls up", timeoutMs = 3_000) {
                scenario.read { a -> (!a.player!!.playWhenReady && playerView(a).isControllerFullyVisible).takeIf { it } }
            }
        }
    }

    /** Two quick presses of Right: one seek, 20 s on, half a second after the last; the controls stay hidden and the label says where it goes. */
    @Test
    fun withTheControlsHiddenTwoQuickRightsSeekOnceTwentySecondsOnAndKeepThemHidden() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            hideControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            val first = scenario.read { a -> playerView(a).player!!.currentPosition }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            val target = first + 10_000
            scenario.read { a ->
                assertEquals("sought before the window ended", emptyList<Long>(), seen.seeks.toList())
                assertEquals(target, playerView(a).player!!.currentPosition)
                assertEquals("+20 s · ${time(target)}", label(a))
            }
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
            scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible || backArrow(a).isShown) }
            // Left goes back the same way.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            val back = scenario.read { a -> playerView(a).player!!.currentPosition }
            awaitSeeks(seen, 2)
            assertEquals(back, seen.seeks.last())
            assertTrue("Left went to $back from about $target", back < target)
        }
    }

    /**
     * Right held for 1.5 s, its repeats coming 0.6 s apart (later than the burst's half second, as a TV's own remote over
     * HDMI can send them): nothing seeks while it is down, then one seek, as far as ScrubRate says for the file's length,
     * half a second after it is let go (L5). The controls stay hidden; the label says how far and where to, with no speed
     * cue in a hold's first 2 s (W8).
     */
    @Test
    fun aHeldRightSeeksOnceAfterItIsLetGoHoweverFarApartItsRepeatsCome() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            hideControls(scenario)
            val down = SystemClock.uptimeMillis()
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
            (1..3).forEach { n ->
                delay(600)
                instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n))
                assertEquals("sought while held, at repeat $n", emptyList<Long>(), seen.seeks.toList())
            }
            val jump = ScrubRate.offsetMs(1_500, duration)
            val target = first - ScrubRate.offsetMs(0, duration) + jump
            scenario.read { a ->
                assertEquals(target, playerView(a).player!!.currentPosition)
                // To the nearest second, as the label says it.
                assertEquals("+${time(jump + 500)} · ${time(target)}", label(a))
                assertEquals("a speed cue in the hold's first 2 s", null, cue(a))
            }
            delay(600)
            assertEquals("sought while held", emptyList<Long>(), seen.seeks.toList())
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_500, 0))
            delay(250)
            assertEquals("sought before half a second after the up", emptyList<Long>(), seen.seeks.toList())
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
            scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
        }
    }

    /**
     * The time bar focused, Right held (L2, L8): the bar's thumb and its contentDescription follow the target on the
     * curve, nothing seeks while the key is down, and the controls stay up past their 5 s timeout, the focus on the bar;
     * one seek follows the up half a second later. The label says how far, without the time the bar shows. Held 1.5 s by
     * the events' times over 6 s of the test's.
     */
    @Test
    fun aHeldRightOnTheFocusedTimeBarMovesItKeepsTheControlsUpAndSeeksOnceLetGo() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
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
                    assertTrue("the controls went, at repeat $n", playerView(a).isControllerFullyVisible)
                    assertTrue("the focus left the bar, at repeat $n", timeBar(a).isFocused)
                }
                assertEquals("sought while held, at repeat $n", emptyList<Long>(), seen.seeks.toList())
            }
            val jump = ScrubRate.offsetMs(1_500, duration)
            val target = first - ScrubRate.offsetMs(0, duration) + jump
            scenario.read { a ->
                assertEquals(target, playerView(a).player!!.currentPosition)
                assertEquals(barTime(target), timeBar(a).contentDescription.toString())
                assertEquals("+${time(jump + 500)}", label(a))
            }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_500, 0))
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
            scenario.read { a -> assertTrue("the controls went", playerView(a).isControllerFullyVisible) }
        }
    }

    /**
     * The time bar focused, Right held on a TV's own remote over HDMI-CEC: the Shield's HDMI service sends each repeat, and
     * the up, with its own time as its downTime (HdmiCecLocalDevice), so the events can't say how long the key has been
     * down. The hold is timed from its press all the same: the bar goes as far as ScrubRate says for the time held (L3),
     * the speed cue shows past the curve's first 2 s (W8), and the seek waits a second after the up (L5). Repeats 0.3 s
     * apart, as a TV sends them, in the test's own time.
     */
    @Test
    fun aRightHeldOverHdmiCecWhoseEventsEachCarryTheirOwnDownTimeGoesOnTheCurve() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
            scenario.onActivity { a -> assertTrue(timeBar(a).requestFocus()) }
            fun key(action: Int, repeat: Int) = SystemClock.uptimeMillis().let { t -> KeyEvent(t, t, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat) }
            val press = key(KeyEvent.ACTION_DOWN, 0)
            instrumentation.sendKeySync(press)
            val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
            var last = press
            (1..8).forEach { n ->
                delay(300)
                last = key(KeyEvent.ACTION_DOWN, n)
                instrumentation.sendKeySync(last)
            }
            val heldMs = last.eventTime - press.eventTime
            val target = first - ScrubRate.offsetMs(0, duration) + ScrubRate.offsetMs(heldMs, duration)
            scenario.read { a ->
                assertEquals("where $heldMs ms held goes", target, playerView(a).player!!.currentPosition)
                assertEquals(barTime(target), timeBar(a).contentDescription.toString())
                assertTrue("no speed cue $heldMs ms into the hold", cue(a) != null)
            }
            assertEquals("sought while held", emptyList<Long>(), seen.seeks.toList())
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 0))
            delay(700)
            assertEquals("sought before a second after a fast hold's up", emptyList<Long>(), seen.seeks.toList())
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
        }
    }

    /**
     * ⏩ held with the controls up (L7): as Right on the bar, the bar follows the target and nothing seeks while it is
     * down, its repeats 0.6 s apart; the controls stay up. Held 2.5 s, past the curve's first 2 s, the label shows two
     * chevrons lit while the speed doubles (W8), and the seek waits a second after the up, room for a correction (L5).
     */
    @Test
    fun aHeldFastForwardWithTheControlsUpMovesTheBarAndSeeksOnceASecondAfterItIsLetGo() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
            val down = SystemClock.uptimeMillis()
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
            (1..5).forEach { n ->
                delay(600)
                instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n))
                scenario.read { a -> assertEquals("the bar at repeat $n", barTime(playerView(a).player!!.currentPosition), timeBar(a).contentDescription.toString()) }
                assertEquals("sought while held, at repeat $n", emptyList<Long>(), seen.seeks.toList())
            }
            val jump = ScrubRate.offsetMs(2_500, duration)
            val target = first - ScrubRate.offsetMs(0, duration) + jump
            scenario.read { a ->
                assertEquals(target, playerView(a).player!!.currentPosition)
                assertEquals("+${time(jump + 500)}", label(a))
                assertEquals("the speed cue's chevrons lit while the speed doubles", 2, cue(a)?.lit)
                assertTrue("the controls went", playerView(a).isControllerFullyVisible)
            }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 2_500, 0))
            delay(700)
            assertEquals("sought before a second after a fast hold's up", emptyList<Long>(), seen.seeks.toList())
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
        }
    }

    /**
     * Left held past the curve's first 2 s, with the controls hidden: the speed cue's chevrons point back, the way the
     * target goes (W8), and are lit as going on, mirrored: two while the speed doubles, the one in the lead dim.
     */
    @Test
    fun aHeldLeftsSpeedCuePointsBack() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            // Four minutes in, so the hold's 2 minutes back don't stop at the start.
            scenario.onActivity { it.player!!.seekTo(240_000) }
            awaitSeeks(seen, 1)
            hideControls(scenario)
            val down = SystemClock.uptimeMillis() - 2_500
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_LEFT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            (1..5).forEach { n -> instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n)) }
            scenario.read { a ->
                assertTrue("the label: ${label(a)}", label(a)?.startsWith("\u2212") == true)
                val cue = cue(a) ?: throw AssertionError("no speed cue 2.5 s into the hold")
                val back = tips(cue, BACK_TIPS)
                assertTrue("the chevrons point on: ink at their tips going back $back, going on ${tips(cue, ON_TIPS)}", back.all { it != null })
                assertTrue("lit from the trailing end: $back", same(back[1], back[2]) && !same(back[0], back[1]))
            }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 2_500, 0))
        }
    }

    /**
     * A correction the other way within the wait (L5): Right held 2.5 s and let go, then Left pressed 0.7 s later, inside
     * the second a fast hold's seek waits: one burst and one seek, 10 s short of where the hold went.
     */
    @Test
    fun aCorrectionTheOtherWayWithinTheWaitJoinsTheSameSeek() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            hideControls(scenario)
            val down = SystemClock.uptimeMillis() - 2_500
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            val (first, duration) = scenario.read { a -> playerView(a).player!!.currentPosition to a.player!!.duration }
            (1..5).forEach { n -> instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n)) }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 2_500, 0))
            val from = first - ScrubRate.offsetMs(0, duration)
            val held = from + ScrubRate.offsetMs(2_500, duration)
            assertEquals(held, scenario.read { a -> playerView(a).player!!.currentPosition })
            delay(700)
            assertEquals("sought before the correction", emptyList<Long>(), seen.seeks.toList())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            val corrected = held - 10_000
            scenario.read { a ->
                assertEquals(corrected, playerView(a).player!!.currentPosition)
                assertEquals("+${time(corrected - from + 500)} · ${time(corrected)}", label(a))
            }
            awaitSeeks(seen, 1)
            delay(1_500)
            assertEquals(listOf(corrected), seen.seeks.toList())
        }
    }

    /**
     * TalkBack hears a held seek once, at the key's up (L8, M4): while the key is down the label follows every repeat but
     * isn't a live region, so nothing is announced 20 times a second; at the up it is one again, and says how far and
     * where to. With the controls up, on the time bar, the label shows how far, the bar having the time, and TalkBack hears
     * where to as well: the bar's own words change without a word to TalkBack.
     */
    @Test
    fun talkBackHearsAHeldSeekOnceAtTheKeysUp() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            hideControls(scenario)
            val down = SystemClock.uptimeMillis() - 1_500
            fun key(action: Int, heldMs: Long, repeat: Int, code: Int = KeyEvent.KEYCODE_DPAD_RIGHT) = KeyEvent(down, down + heldMs, action, code, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            val first = scenario.read { a -> playerView(a).player!!.currentPosition }
            (0..3).forEach { n ->
                if (n > 0) instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n))
                scenario.read { a ->
                    assertTrue("the label, at repeat $n", momentLabel(a).isShown)
                    assertEquals("a live region while held, at repeat $n", View.ACCESSIBILITY_LIVE_REGION_NONE, momentLabel(a).accessibilityLiveRegion)
                }
            }
            // 1.5 s held: 70 s on.
            val target = first - 10_000 + 70_000
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_500, 0))
            scenario.read { a ->
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
                assertEquals("+1:10 · ${time(target)}", momentLabel(a).text.toString())
                assertEquals("Forward 1 minute 10 seconds, ${time(target)}", momentLabel(a).contentDescription?.toString())
            }

            // On the focused time bar, the controls up: a press, shown at its up without the time, and spoken with it.
            delay(2_000)
            showControls(scenario)
            scenario.onActivity { a -> assertTrue(timeBar(a).requestFocus()) }
            val press = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(press, press, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0))
            val back = scenario.read { a ->
                assertEquals("a live region while down", View.ACCESSIBILITY_LIVE_REGION_NONE, momentLabel(a).accessibilityLiveRegion)
                playerView(a).player!!.currentPosition
            }
            instrumentation.sendKeySync(KeyEvent(press, press + 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
            scenario.read { a ->
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
                assertEquals("\u221210 s", momentLabel(a).text.toString())
                assertEquals("Back 10 seconds, ${time(back)}", momentLabel(a).contentDescription?.toString())
            }
        }
    }

    /**
     * Up pressed while Right is held to seek, with the controls hidden: the controls come up, and Right's up still ends the
     * hold. The one seek follows it half a second later, well before the 2 s a lost up waits, and TalkBack hears where the
     * hold went at the up (L5, L8).
     */
    @Test
    fun anotherKeysPressWhileRightIsHeldLeavesRightsUpEndingTheHold() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            hideControls(scenario)
            val down = SystemClock.uptimeMillis() - 1_000
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            val first = scenario.read { a -> playerView(a).player!!.currentPosition }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 500, 1))
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 1_000, 2))
            val lastEvent = SystemClock.uptimeMillis()
            // 1 s held: 50 s on.
            val target = first - 10_000 + 50_000
            assertEquals(target, scenario.read { a -> playerView(a).player!!.currentPosition })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_000, 0))
            scenario.read { a ->
                assertEquals("a live region at Right's up", View.ACCESSIBILITY_LIVE_REGION_POLITE, momentLabel(a).accessibilityLiveRegion)
                assertEquals("Forward 50 seconds, ${time(target)}", momentLabel(a).contentDescription?.toString())
            }
            awaitSeeks(seen, 1)
            val soughtAfter = SystemClock.uptimeMillis() - lastEvent
            assertTrue("sought $soughtAfter ms after Right's last repeat: at a lost up's 2 s, not half a second after its up", soughtAfter < 1_900)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
        }
    }

    /**
     * Right held from before the intro, with the file's chapters: Skip intro shows while the
     * burst's target is in the intro, but doesn't take the focus while the seek waits, so the rest
     * of the key goes on seeking. The hold seeks once, past the intro, the controls still hidden.
     * Held slowly at first (the repeats' times say how long), so the target stays in the intro over
     * several of the button's updates.
     */
    @Test
    fun aRightHeldThroughTheIntroSeeksOncePastItWithTheControlsHidden() = runBlocking<Unit> {
        PlayerActivity.readChapters = realChapters
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitFromTheStart(scenario)
            val down = SystemClock.uptimeMillis() - 2_000
            fun key(action: Int, heldMs: Long, repeat: Int) = KeyEvent(down, down + heldMs, action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
            var shown = false
            (1..10).forEach { n ->
                delay(100)
                // 0.4 s further each, 10 to 14 s on: in the intro.
                instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 10L, n))
                scenario.read { a ->
                    shown = shown || "Skip intro" in overlayWords(a)
                    assertFalse("Skip intro took the focus from the key held", "Skip intro" in focusedWords(a))
                    assertFalse("the controls came up", playerView(a).isControllerFullyVisible)
                }
            }
            assertTrue("Skip intro never showed", shown)
            // Then on, past the intro.
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 1_500, 11))
            val target = scenario.read { a -> playerView(a).player!!.currentPosition }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 1_500, 0))
            assertEquals("sought while held", emptyList<Long>(), seen.seeks.toList())
            assertTrue("held to $target, short of the intro's end", target > INTRO_END_MS)
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
            scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible || backArrow(a).isShown) }
        }
    }

    /**
     * A held Right whose first repeat comes after its press's seek has run: the burst waits for
     * the key's up at most 2 s after its last event, so a lost or late event still seeks. The press
     * took the target into the intro, and Skip intro the focus as that seek ran. The repeats still
     * seek, the controls stay hidden, and the hold seeks once more, past the intro.
     */
    @Test
    fun aHeldRightGoesOnSeekingThoughSkipIntroTookTheFocusBeforeItsFirstRepeat() = runBlocking<Unit> {
        PlayerActivity.readChapters = realChapters
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitFromTheStart(scenario)
            val down = SystemClock.uptimeMillis()
            fun key(action: Int, repeat: Int) = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0))
            awaitSeeks(seen, 1)
            waitFor("Skip intro to take the focus") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            (1..4).forEach { n ->
                instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n))
                delay(50)
            }
            val target = scenario.read { a -> playerView(a).player!!.currentPosition }
            instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, 0))
            scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible) }
            assertTrue("held to $target, short of the intro's end", target > INTRO_END_MS)
            awaitSeeks(seen, 2)
            delay(1_000)
            assertEquals(target, seen.seeks.last())
            assertEquals(2, seen.seeks.size)
            scenario.read { a -> assertFalse("the controls came up", playerView(a).isControllerFullyVisible || backArrow(a).isShown) }
        }
    }

    /** OK on the skip button that took the focus as it showed skips, and doesn't pause. */
    @Test
    fun okOnTheFocusedSkipButtonSkipsWithoutPausing() = runBlocking<Unit> {
        PlayerActivity.readChapters = realChapters
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 2_000) }
            waitFor("Skip intro to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val landed = waitFor("the skip") { scenario.read { a -> a.player!!.currentPosition.takeIf { it >= INTRO_END_MS } } }
            assertTrue("landed at $landed ms", landed < INTRO_END_MS + 1_500)
            assertTrue("OK paused too", scenario.read { it.player!!.playWhenReady })
        }
    }

    /** Back hides the controls first, then leaves; in touch mode it leaves at once, as before. */
    @Test
    fun backHidesTheControlsBeforeItLeavesButInTouchMode() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            showControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the controls to hide", timeoutMs = 3_000) { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            delay(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close", timeoutMs = 3_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            showControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor<Lifecycle.State>("the player to close", timeoutMs = 3_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
    }

    /**
     * The time bar focused, a press of Right still goes 10 s out of touch mode, on every device (media3's own step is a
     * twentieth of the file), now through the burst (L2): the label says how far, without the time the bar shows.
     */
    @Test
    fun aPressOnTheFocusedTimeBarIsStillTenSecondsThroughTheBurst() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            scenario.onActivity { a ->
                a.player!!.pause()
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            scenario.onActivity { a -> assertTrue(timeBar(a).requestFocus()) }
            delay(500)
            val before = scenario.read { a -> a.player!!.currentPosition }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.read { a ->
                assertEquals(before + 10_000, playerView(a).player!!.currentPosition)
                assertEquals(barTime(before + 10_000), timeBar(a).contentDescription.toString())
                assertEquals("+10 s", label(a))
            }
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(before + 10_000), seen.seeks.toList())
        }
    }

    /**
     * In touch mode the time bar keeps media3's own step, a twentieth of the file: TalkBack and
     * Switch Access adjust it on a touch screen by its scroll actions, which take the step its keys
     * do, and 10 s would leave them no coarse way to seek. Out of touch mode, as a D-pad leaves it,
     * Right on it goes 10 s again.
     */
    @Test
    fun inTouchModeTheTimeBarAdjustsByATwentiethOfTheFileForTalkBack() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            scenario.onActivity { a ->
                a.player!!.pause()
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            delay(500)
            val (before, duration) = scenario.read { a -> a.player!!.currentPosition to a.player!!.duration }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            }
            awaitSeeks(seen, 1)
            val adjusted = seen.seeks.single()
            assertTrue("the time bar went to $adjusted from $before in $duration", abs(adjusted - (before + duration / 20)) < 500)
            instrumentation.setInTouchMode(false)
            scenario.onActivity { a -> assertTrue(a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).requestFocus()) }
            delay(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitSeeks(seen, 2)
            assertTrue("Right on the time bar went to ${seen.seeks.last()} from $adjusted", abs(seen.seeks.last() - (adjusted + 10_000)) < 500)
        }
    }

    /**
     * Info opens File details, with the controls hidden or up, and closes them (W2); with the audio and subtitles sheet up
     * it is the sheet's, as every key is there. A key nothing takes is logged with detailed logging (not the D-pad moving
     * the focus among the controls, which Android does once the player has passed the key on), and Stop leaves as the back
     * arrow does.
     */
    @Test
    fun infoOpensAndClosesFileDetailsAnUnhandledKeyIsLoggedAndStopLeaves() = runBlocking<Unit> {
        AndroidLogConfig.detailed = true
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            hideControls(scenario)
            val before = LogBuffer.snapshot().maxOfOrNull { it.seq } ?: 0L
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor("File details after Info", timeoutMs = 3_000) { scenario.read { a -> ("File details" in paneTitles(a) && !playerView(a).isControllerFullyVisible).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor("File details to close after Info", timeoutMs = 3_000) { scenario.read { a -> ("File details" !in paneTitles(a)).takeIf { it } } }
            // With the controls up too.
            showControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor("File details over the controls after Info", timeoutMs = 3_000) { scenario.read { a -> ("File details" in paneTitles(a)).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor("File details to close again", timeoutMs = 3_000) { scenario.read { a -> ("File details" !in paneTitles(a)).takeIf { it } } }
            // The sheet keeps it.
            showControls(scenario)
            scenario.onActivity { a -> views(a.window.decorView).single { it.contentDescription == "Audio and subtitles" }.performClick() }
            waitFor("the sheet") { scenario.read { a -> ("Audio and subtitles" in paneTitles(a)).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            delay(500)
            assertEquals(listOf("Audio and subtitles"), scenario.read(::paneTitles))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close, the controls up") { scenario.read { a -> (paneTitles(a).isEmpty() && playerView(a).isControllerFullyVisible).takeIf { it } } }
            val from = scenario.read { a -> a.currentFocus }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.read { a -> assertTrue("Right didn't move the focus from $from", a.currentFocus != null && a.currentFocus !== from) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CHANNEL_UP)
            waitFor("the log line") { LogBuffer.snapshot().lastOrNull { "Key 166 CHANNEL_UP not handled" in it.message } }
            val moves = LogBuffer.snapshot().filter { it.seq > before && "DPAD_RIGHT not handled" in it.message }.map { it.message }
            assertEquals("the D-pad's move of the focus was logged", emptyList<String>(), moves)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_STOP)
            waitFor<Lifecycle.State>("the player to close", timeoutMs = 3_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
    }

    /**
     * Captions: with the subtitle rule's "none" for English audio, the file's English subtitles
     * show, then none again (Forced only, as before, not text turned off), then those again; the
     * audio track key goes on to the German audio, then to the English commentary, said with what
     * tells it from the main audio. Each is said, and kept for the show as a pick in the sheet is.
     */
    @Test
    fun captionsAndTheAudioTrackKeySwitchTracksSayItAndKeepItForTheShow() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, tracksEpisode("en", "de"))).use { scenario ->
            awaitTracks(scenario, audio = "en", text = null)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CAPTIONS)
            assertEquals("Subtitles: English · SRT", scenario.read(::label))
            awaitTracks(scenario, audio = "en", text = "en")
            awaitSaved { it?.subtitles == SubtitleMode.Language && it.subtitleLanguage == "en" && !it.subtitleSdh }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CAPTIONS)
            assertEquals("Subtitles off", scenario.read(::label))
            awaitTracks(scenario, audio = "en", text = null)
            scenario.read { a -> assertFalse("forced subtitles were turned off", C.TRACK_TYPE_TEXT in a.player!!.trackSelectionParameters.disabledTrackTypes) }
            awaitSaved { it?.subtitles == SubtitleMode.ForcedOnly }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CAPTIONS)
            awaitTracks(scenario, audio = "en", text = "en")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
            assertEquals("Audio: German · 5.1 · AAC", scenario.read(::label))
            awaitTracks(scenario, audio = "de", text = "en")
            awaitSaved { it?.audioLanguage == "de" && it.subtitles == SubtitleMode.Language }
            // As the sheet's row says it, so it isn't taken for the main audio.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
            assertEquals("Audio: English · Stereo · AAC · Commentary", scenario.read(::label))
            awaitTracks(scenario, audio = "en", text = "en")
            scenario.read { a -> assertFalse("the keys brought the controls", playerView(a).isControllerFullyVisible) }
        }
    }

    /**
     * For a German-first viewer the German audio plays with the file's German forced subtitles
     * (Forced only, the rule's "none"): captions bring the full German ones, then the forced ones
     * back, kept as such for the show, not text turned off.
     */
    @Test
    fun captionsGoBackToTheForcedSubtitlesThatShowedBefore() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, tracksEpisode("de"))).use { scenario ->
            awaitTracks(scenario, audio = "de", text = null, forced = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CAPTIONS)
            assertEquals("Subtitles: German · SRT", scenario.read(::label))
            awaitTracks(scenario, audio = "de", text = "de")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CAPTIONS)
            assertEquals("Subtitles off", scenario.read(::label))
            awaitTracks(scenario, audio = "de", text = null, forced = true)
            scenario.read { a -> assertFalse("forced subtitles were turned off", C.TRACK_TYPE_TEXT in a.player!!.trackSelectionParameters.disabledTrackTypes) }
            awaitSaved { it?.subtitles == SubtitleMode.ForcedOnly }
        }
    }

    /** The tracks.mkv fixture played as episode 1 of a fake show, for a viewer preferring [languages], the subtitle rule on. */
    private suspend fun tracksEpisode(vararg languages: String): PlayerLaunch {
        val tracks = engineSetup("the tracks fixture prepared") { prepareFixture("tracks") }
        PlayerActivity.readPlayback = { realRead(it).copy(audioLanguages = languages.toList(), subtitlesWhenForeign = true, autoPlayNext = false) }
        return PlayerLaunch(
            uri = UsenetUris.build(tracks.id, tracks.defaultPick!!.index), title = "Harbor Lights", subtitle = "S01E01 · Episode 1",
            watchKey = WatchKey.episode(SHOW, 1, 1), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = SHOW, season = 1, episode = 1),
            release = SavedRelease("fixture", "tracks", "Harbor.Lights.S01E01.Tracks", null, tracks.defaultPick!!.index, tracks.id),
        )
    }

    // What the player does.

    /** The player's seeks, where they went. */
    private class Seen : Player.Listener {
        val seeks = CopyOnWriteArrayList<Long>()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks += newPosition.positionMs
        }
    }

    /** Waits until the file plays, then watches what its player does. */
    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>): Seen {
        waitFor("playback", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition > 0 } } }
        return Seen().also { seen -> scenario.onActivity { a -> a.player!!.addListener(seen) } }
    }

    /** Playing from the start, before the intro (5–25 s), the controls hidden: the player's seeks watched from there. */
    private suspend fun awaitFromTheStart(scenario: ActivityScenario<PlayerActivity>): Seen {
        val seen = awaitPlaying(scenario)
        scenario.onActivity { it.player!!.seekTo(0) }
        awaitSeeks(seen, 1)
        hideControls(scenario)
        waitFor("playback before the intro") { scenario.read { a -> a.player!!.takeIf { it.isPlaying && it.currentPosition < INTRO_START_MS - 1_000 } } }
        seen.seeks.clear()
        return seen
    }

    private suspend fun awaitSeeks(seen: Seen, count: Int) {
        waitFor("$count seeks, not ${seen.seeks}", timeoutMs = 5_000) { seen.seeks.takeIf { it.size >= count } }
    }

    /**
     * Waits until the player plays audio in [audio] with full subtitles in [text] (null: none,
     * forced ones aside), and with [forced] a forced track showing.
     */
    private suspend fun awaitTracks(scenario: ActivityScenario<PlayerActivity>, audio: String, text: String?, forced: Boolean = false) {
        fun playing(a: PlayerActivity): Triple<String?, String?, Boolean>? {
            val tracks = a.player?.currentTracks?.takeUnless { it.isEmpty } ?: return null
            fun selected(type: Int): Format? = tracks.groups.filter { it.type == type && it.isSelected }
                .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }
            val shown = selected(C.TRACK_TYPE_TEXT)
            val full = shown?.takeIf { it.selectionFlags and C.SELECTION_FLAG_FORCED == 0 && it.label == null }
            return Triple(selected(C.TRACK_TYPE_AUDIO)?.language, full?.language, shown != null && shown.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
        }
        waitFor("audio $audio with subtitles $text${if (forced) " and forced ones" else ""}, not ${scenario.read(::playing)}", timeoutMs = 30_000) {
            scenario.read { a -> playing(a)?.takeIf { (playingAudio, fullText, forcedShown) -> playingAudio == audio && fullText == text && (forcedShown || !forced) } }
        }
    }

    /** Waits for the show's saved choice to be as [ok] says. */
    private suspend fun awaitSaved(ok: (ShowTrackChoice?) -> Boolean) {
        waitFor("the show's choice, not ${container.library.trackChoice(SHOW)}", timeoutMs = 5_000) { runBlocking { ok(container.library.trackChoice(SHOW)).takeIf { it } } }
    }

    private suspend fun showControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
    }

    /** Hidden, with their hide animation over: the focus is then PlayerView's, as a TV remote finds the player playing. */
    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible && !backArrow(a).isShown).takeIf { it } } }
        delay(800)
    }

    /** How media3's controls write [ms], to the nearest second: the time bar's contentDescription. */
    private fun barTime(ms: Long): String = StringBuilder().let { b -> Util.getStringForTime(b, Formatter(b, Locale.getDefault()), ms) }

    /** How the player writes [ms]. */
    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // Views.

    private fun backArrow(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /** What the label under the top bar says, if it shows. */
    private fun label(activity: Activity) = momentLabel(activity).takeIf { it.isShown }?.text?.toString()

    /** The label under the top bar, found whether or not it is a live region (it isn't while a key is held). */
    private fun momentLabel(activity: Activity): TextView = activity.findViewById(R.id.moment_label)

    /** The speed cue at the start of the label (W8), while it shows one. */
    private fun cue(activity: Activity) = momentLabel(activity).takeIf { it.isShown }?.compoundDrawablesRelative?.first() as? SpeedCue

    /**
     * [cue] drawn: the colour across its middle at each of [at] (dp of its 22 across), null where nothing is drawn. Pointing
     * on, its chevrons' tips there are at [ON_TIPS]; pointing back, at [BACK_TIPS], the one in the lead first.
     */
    private fun tips(cue: SpeedCue, at: List<Float>): List<Int?> {
        val w = cue.intrinsicWidth
        val h = cue.intrinsicHeight
        val drawn = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cue.setBounds(0, 0, w, h)
        cue.draw(Canvas(drawn))
        return at.map { dp -> drawn.getPixel((dp / 22 * w).toInt(), h / 2).takeIf { Color.alpha(it) > 128 } }
    }

    /** Two of [tips]' colours alike, both drawn. */
    private fun same(a: Int?, b: Int?) = a != null && b != null && listOf(Color::red, Color::green, Color::blue).all { c -> abs(c(a) - c(b)) < 16 }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun timeBar(activity: Activity): View = activity.findViewById(androidx.media3.ui.R.id.exo_progress)

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** The panes over the player: the audio and subtitles sheet's, File details'. */
    private fun paneTitles(activity: Activity) = overlayNodes(activity).mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** The words of the overlays showing (the skip button, the card). */
    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    private fun focusedWords(activity: Activity) =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::subtree).orEmpty()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private companion object {
        /** A fake show's id in TMDB numbering; nothing real has it. */
        const val SHOW = 900023
        const val INTRO_START_MS = 5_000L
        const val INTRO_END_MS = 25_000L

        /** Where the speed cue's chevrons have their tips across its middle, in dp of its 22: pointing back, the leading one first. */
        val BACK_TIPS = listOf(2.5f, 9.5f, 16.5f)
        val ON_TIPS = listOf(5.5f, 12.5f, 19.5f)
    }
}

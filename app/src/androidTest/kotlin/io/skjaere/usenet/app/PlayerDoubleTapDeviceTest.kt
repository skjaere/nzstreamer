package io.skjaere.usenet.app

import android.app.Activity
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SideMark
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Double-tap to seek on a phone (L9 to L14), by touch as a finger gives it: a double-tap in the picture's right third
 * goes 10 s on, in its left third 10 s back, and single taps at either side after it add 10 s more each way, all one
 * burst and one seek half a second after the last tap. The side mark says how far, and where to while the controls are
 * hidden; they stay as they were. A single tap at a side shows or hides the controls once the double-tap timeout has
 * passed (W1), one in the middle at once, as before. Off under a side panel, on a TV and with TalkBack's touch
 * exploration on; a tap on an overlay's surface, a button, or a tap followed by a pinch never counts. The release is
 * chapters.mkv (six minutes, its chapters left out) posted on a server inside the test's process ([LocalNntp]), so this
 * runs without the shared mock; the film (TMDB id 900051) and the show (900052) are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerDoubleTapDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its touches. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realChapters = PlayerActivity.readChapters
    private val realExploration = PlayerActivity.touchExploration
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realRead = PlayerActivity.readPlayback
    private val realTimings = PlayerActivity.timings
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM))
    private lateinit var saved: AppSettings
    /** Indexers that were on before the card's test; switched back on after it. */
    private var switchedOff = emptyList<String>()

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readChapters = { emptyList() }
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
            // A pinch saves the picture's size: put back after.
            saved = container.settings.settings.first()
        }
        instrumentation.setInTouchMode(true)
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        PlayerActivity.touchExploration = realExploration
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.readPlayback = realRead
        PlayerActivity.timings = realTimings
        container.playback.cancel()
        local.close()
        runBlocking {
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
            // Out of Up next.
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, DURATION_MS)
            container.library.recordProgress(
                WatchState(WatchKey.episode(SHOW, 1, 1), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 1, title = "$SHOW_NAME S01E01", updatedAt = 0),
                0, DURATION_MS,
            )
            // The player saves in the app's scope: let a save still on its way land before putting the preferences back.
            delay(500)
            container.settings.restore(container.settings.settings.first().copy(playback = saved.playback))
        }
    }

    /** With the controls hidden: one seek, 10 s on, half a second after the second tap; the mark says how far and where to, and the controls stay hidden. */
    @Test
    fun aDoubleTapAtTheRightGoesTenSecondsOnInOneSeekTheControlsStayingHidden() = play { scenario, seen ->
        hideControls(scenario)
        doubleTap(at(scenario, 0.85f, 0.5f))
        val tappedAt = SystemClock.uptimeMillis()
        val target = scenario.read { a ->
            val target = playerView(a).player!!.currentPosition
            assertNear("the target", a.player!!.currentPosition + 10_000, target)
            assertEquals("+10 s", count(a))
            assertEquals(time(target), markTime(a))
            assertEquals("Forward 10 seconds, ${time(target)}", mark(a)?.contentDescription)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, mark(a)?.accessibilityLiveRegion)
            assertTrue("the mark isn't at the right: ${markRect(a)}", markRect(a).centerX() > screenWidth(a) * 2 / 3)
            assertFalse("the controls came up", controlsUp(a))
            assertNull("the label under the top bar spoke for touch", topLabel(a))
            target
        }
        assertEquals("sought at once", emptyList<Long>(), seen.positions())
        waitFor("the seek", timeoutMs = 3_000) { seen.takeIf { it.isNotEmpty() } }
        val waited = seen.first().second - tappedAt
        assertTrue("sought $waited ms after the double-tap, not about half a second", waited in 400..1_500)
        // It fades a moment after the last tap: there as the seek runs, gone soon after.
        assertTrue("the mark went with the seek", scenario.read { a -> mark(a) != null })
        waitFor("the mark to fade", timeoutMs = 3_000) { scenario.read { a -> (mark(a) == null).takeIf { it } } }
        assertEquals(listOf(target), seen.positions())
        scenario.read { a -> assertFalse("the controls came up", controlsUp(a)) }
    }

    /**
     * The controls coming up as the mark fades (a tap in the middle to see where it went, say) don't bring it back for
     * good: it goes as its fade ends (L13). The test asks for the fade itself, as the mark's timer does 1.5 s after the
     * last tap, and has the controls come up in that moment: the emulators run with animations off, so a fade there lasts
     * a frame, and no tap could be timed into it.
     */
    @Test
    fun theControlsComingUpAsTheMarkFadesDontBringItBack() = play { scenario, _ ->
        hideControls(scenario)
        doubleTap(at(scenario, 0.85f, 0.5f))
        scenario.read { a -> assertEquals("+10 s", count(a)) }
        scenario.onActivity { a ->
            views(a.window.decorView).filterIsInstance<SideMark>().single().fade()
            playerView(a).showController()
        }
        // Well before the mark's own timer asks for its fade.
        delay(400)
        scenario.read { a -> assertNull("the mark came back as the controls came up, saying ${count(a)}", mark(a)) }
    }

    /** A double-tap, then two single taps in the right third: the mark counts up, and one seek goes 30 s on. */
    @Test
    fun aDoubleTapAndTwoMoreTapsGoThirtySecondsOnInOneSeek() = play { scenario, seen ->
        hideControls(scenario)
        val right = at(scenario, 0.85f, 0.5f)
        doubleTap(right)
        val counts = mutableListOf(scenario.read(::count))
        val first = scenario.read { a -> playerView(a).player!!.currentPosition }
        delay(150)
        // Anywhere in the third.
        tap(at(scenario, 0.95f, 0.2f))
        counts += scenario.read(::count)
        delay(150)
        tap(right)
        counts += scenario.read(::count)
        assertEquals(listOf("+10 s", "+20 s", "+30 s"), counts)
        val target = scenario.read { a -> playerView(a).player!!.currentPosition }
        assertEquals(first + 20_000, target)
        assertEquals("sought before the last tap's window ended", emptyList<Long>(), seen.positions())
        waitFor("the seek", timeoutMs = 3_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.positions())
        scenario.read { a -> assertFalse("the controls came up", controlsUp(a)) }
    }

    /** Taps the other way take 10 s off each, and the mark goes to the side the burst's total points to (L16b). */
    @Test
    fun tapsTheOtherWayTakeTenSecondsOffTheMarkGoingWhereTheTotalPoints() = play { scenario, seen ->
        hideControls(scenario)
        doubleTap(at(scenario, 0.85f, 0.5f))
        val first = scenario.read { a -> playerView(a).player!!.currentPosition }
        val marks = mutableListOf(scenario.read(::side))
        val left = at(scenario, 0.15f, 0.5f)
        repeat(2) {
            delay(150)
            tap(left)
            marks += scenario.read(::side)
        }
        assertEquals(listOf("+10 s" to "right", "0 s" to "right", "−10 s" to "left"), marks)
        val target = scenario.read { a -> playerView(a).player!!.currentPosition }
        assertEquals(first - 20_000, target)
        waitFor("the seek", timeoutMs = 3_000) { seen.takeIf { it.isNotEmpty() } }
        delay(1_000)
        assertEquals(listOf(target), seen.positions())
    }

    /** At 0:00, paused, a double-tap at the left can't go back: the mark says so, and nothing seeks. */
    @Test
    fun aDoubleTapAtTheLeftAtTheStartSaysSoAndSeeksNowhere() = play { scenario, seen ->
        scenario.onActivity { a ->
            a.player!!.pause()
            a.player!!.seekTo(0)
        }
        waitFor("the seek to the start") { seen.takeIf { it.isNotEmpty() } }
        waitFor("the start to show") { scenario.read { a -> a.player!!.takeIf { it.playbackState == Player.STATE_READY } } }
        seen.clear()
        hideControls(scenario)
        doubleTap(at(scenario, 0.15f, 0.5f))
        scenario.read { a ->
            assertEquals("At the start", count(a))
            assertEquals("0:00", markTime(a))
            assertEquals("At the start, 0:00", mark(a)?.contentDescription)
            assertTrue("the mark isn't at the left: ${markRect(a)}", markRect(a).centerX() < screenWidth(a) / 3)
        }
        delay(1_500)
        assertEquals(emptyList<Long>(), seen.positions())
    }

    /**
     * In the middle third a tap shows the controls at once, as before, and a double-tap there seeks nothing: two taps
     * that show and hide them, or show them twice while they fade in where animations run.
     */
    @Test
    fun aDoubleTapInTheMiddleSeeksNothingItsTapsShowingTheControlsAtOnce() = play { scenario, seen ->
        hideControls(scenario)
        // Under the top bar, above the controls' centre buttons: on the picture whether or not they show.
        val middle = at(scenario, 0.5f, 0.3f)
        tap(middle)
        assertTrue("the controls waited", scenario.read(::controlsUp))
        delay(100)
        tap(middle)
        delay(1_500)
        assertEquals(emptyList<Long>(), seen.positions())
        scenario.read { a -> assertNull("a mark showed", mark(a)) }
    }

    /** A single tap at a side shows the controls once the double-tap timeout has passed with no second (W1), and hides them the same way. */
    @Test
    fun aSingleTapAtASideShowsOrHidesTheControlsAfterTheWait() = play { scenario, seen ->
        hideControls(scenario)
        val right = at(scenario, 0.85f, 0.5f)
        tap(right)
        assertFalse("the controls came up at once", scenario.read(::controlsUp))
        waitFor("the controls", timeoutMs = 3_000) { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        tap(right)
        assertTrue("the controls went at once", scenario.read { a -> playerView(a).isControllerFullyVisible })
        waitFor("the controls to go", timeoutMs = 3_000) { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
        assertEquals(emptyList<Long>(), seen.positions())
        scenario.read { a -> assertNull("a mark showed", mark(a)) }
    }

    /** A double-tap where the top bar's Back shows with the controls up: they stay hidden, so it goes back 10 s and the player stays. */
    @Test
    fun aDoubleTapWhereBackShowsGoesBackAndDoesntLeave() = play { scenario, seen ->
        showControls(scenario)
        val back = scenario.read { a -> rectOnScreen(backArrow(a)).let { PointF(it.centerX(), it.centerY()) } }
        hideControls(scenario)
        doubleTap(back)
        val target = scenario.read { a ->
            assertNear("the target", a.player!!.currentPosition - 10_000, playerView(a).player!!.currentPosition)
            playerView(a).player!!.currentPosition
        }
        delay(1_500)
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        assertEquals(listOf(target), seen.positions())
        scenario.read { a -> assertFalse("the controls came up", controlsUp(a)) }
    }

    /** With the audio and subtitles sheet open, a double-tap on it seeks nothing; nor one beside it, whose first tap closes it. */
    @Test
    fun withASidePanelOpenNothingSeeks() = play { scenario, seen ->
        scenario.onActivity { a ->
            playerView(a).showController()
            views(a.window.decorView).single { it.contentDescription == "Audio and subtitles" }.performClick()
        }
        waitFor("the sheet") { scenario.read { a -> ("Audio and subtitles" in overlayWords(a)).takeIf { it } } }
        doubleTap(at(scenario, 0.85f, 0.5f))
        delay(1_500)
        assertTrue("the sheet closed", scenario.read { a -> "Audio and subtitles" in overlayWords(a) })
        doubleTap(at(scenario, 0.15f, 0.5f))
        delay(1_500)
        assertFalse("the sheet stayed", scenario.read { a -> "Audio and subtitles" in overlayWords(a) })
        assertEquals(emptyList<Long>(), seen.positions())
        scenario.read { a -> assertNull("a mark showed", mark(a)) }
    }

    /** The next-episode card's surface takes its taps: a double-tap on it neither seeks nor shows the controls. */
    @Test
    fun aDoubleTapOnTheNextEpisodeCardsSurfaceSeeksNothing() = runBlocking<Unit> {
        // The card from the start, with nothing counting down and nothing searched for (no indexer on).
        switchedOff = container.settings.indexers.first().filter { it.enabled }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
        PlayerActivity.timings = PlayerActivity.Timings(cardBeforeEndMs = DURATION_MS * 2)
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false) }
        PlayerActivity.nextEpisode = { _, _ -> NEXT }
        PlayerActivity.previousEpisode = { _, _ -> null }
        val launch = fixtures.launch(release().prepared, GUID, START_MS).copy(
            watchKey = WatchKey.episode(SHOW, 1, 1),
            watch = WatchTarget(WatchKind.Episode, "$SHOW_NAME S01E01", showTmdbId = SHOW, season = 1, episode = 1),
        )
        play(launch) { scenario, seen ->
            val card = waitFor("the next-episode card") { scenario.read { a -> cardRect(a)?.takeIf { "Play now" in overlayWords(a) } } }
            hideControls(scenario)
            // The middle of its top row, beside its still: words, no button.
            val surface = scenario.read { a -> PointF(card.centerX(), card.top + 40 * a.resources.displayMetrics.density) }
            assertTrue("the card isn't in the right third", scenario.read { a -> surface.x > screenWidth(a) * 2 / 3 })
            // A tap that reached the picture would show the controls, at once or after the wait for a second.
            tap(surface)
            assertFalse("the tap reached the picture: the controls came up", scenario.read(::controlsUp))
            delay(600)
            assertFalse("the tap reached the picture: the controls came up after the wait", scenario.read(::controlsUp))
            doubleTap(surface)
            delay(1_500)
            assertEquals(emptyList<Long>(), seen.positions())
            scenario.read { a ->
                assertFalse("the controls came up", controlsUp(a))
                assertNull("a mark showed", mark(a))
                assertTrue("the card went", "Play now" in overlayWords(a))
            }
            // A double-tap above it in its third: the mark goes up clear of it (NxPhoneSeekOverlays).
            doubleTap(at(scenario, 0.85f, 0.15f))
            scenario.read { a ->
                assertEquals("+10 s", count(a))
                val clear = 8 * a.resources.displayMetrics.density
                assertTrue("the mark ${markRect(a)} isn't clear above the card $card", markRect(a).bottom <= card.top - clear + 1)
            }
        }
    }

    /** A tap, then at once a pinch where it was: no seek. */
    @Test
    fun aTapAndThenAPinchSeekNothing() = play { scenario, seen ->
        hideControls(scenario)
        val right = at(scenario, 0.85f, 0.5f)
        tap(right)
        delay(80)
        val down = SystemClock.uptimeMillis()
        var span = 200f
        fun fingers() = listOf(right, PointF(right.x - span, right.y))
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(down, MotionEvent.ACTION_DOWN, fingers().take(1))
        send(down, MotionEvent.ACTION_POINTER_DOWN or second, fingers())
        repeat(12) {
            span += 24f
            send(down, MotionEvent.ACTION_MOVE, fingers())
            delay(16)
        }
        send(down, MotionEvent.ACTION_POINTER_UP or second, fingers())
        send(down, MotionEvent.ACTION_UP, fingers().take(1))
        delay(1_500)
        assertEquals(emptyList<Long>(), seen.positions())
        scenario.read { a -> assertNull("a mark showed", mark(a)) }
    }

    /** The player in a TV's form (the debug builds' launch extra): a double-tap at a side seeks nothing. */
    @Test
    fun onATvNothingSeeks() = runBlocking<Unit> {
        play(fixtures.launch(release().prepared, GUID, START_MS), tv = true) { scenario, seen ->
            hideControls(scenario)
            doubleTap(at(scenario, 0.85f, 0.5f))
            delay(1_500)
            assertEquals(emptyList<Long>(), seen.positions())
            scenario.read { a -> assertNull("a mark showed", mark(a)) }
        }
    }

    /** With TalkBack's touch exploration on its double-tap is its own: the picture's seeks nothing. */
    @Test
    fun withTouchExplorationOnNothingSeeks() = runBlocking<Unit> {
        PlayerActivity.touchExploration = { true }
        play(fixtures.launch(release().prepared, GUID, START_MS)) { scenario, seen ->
            hideControls(scenario)
            doubleTap(at(scenario, 0.85f, 0.5f))
            delay(1_500)
            assertEquals(emptyList<Long>(), seen.positions())
            scenario.read { a -> assertNull("a mark showed", mark(a)) }
        }
    }

    /**
     * With the controls up a double-tap at a side keeps them up, their timeout put off (L14): the time bar shows where it
     * goes at once, and the mark says only how far.
     */
    @Test
    fun withTheControlsUpADoubleTapKeepsThemUpTheBarShowingWhereItGoes() = play { scenario, seen ->
        showControls(scenario)
        // Most of the controls' 5 s timeout gone.
        delay(3_000)
        doubleTap(at(scenario, 0.85f, 0.5f))
        val target = scenario.read { a ->
            val target = playerView(a).player!!.currentPosition
            assertNear("the target", a.player!!.currentPosition + 10_000, target)
            assertEquals(barTime(target), a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).contentDescription.toString())
            assertEquals("+10 s", count(a))
            assertNull("the mark said the time the bar shows", markTime(a))
            assertEquals("Forward 10 seconds", mark(a)?.contentDescription)
            // Beside the controls' centre buttons, clear of the last of them (Next).
            val buttons = a.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls).let { row -> (0 until row.childCount).map(row::getChildAt).filter { it.isShown } }
            val last = buttons.maxOf { rectOnScreen(it).right }
            assertTrue("the mark ${markRect(a)} reaches the centre buttons, which end at $last", markRect(a).left >= last)
            target
        }
        waitFor("the seek", timeoutMs = 3_000) { seen.takeIf { it.isNotEmpty() } }
        // Past the timeout the double-tap didn't put off.
        delay(3_000)
        assertEquals(listOf(target), seen.positions())
        scenario.read { a -> assertTrue("the controls went", playerView(a).isControllerFullyVisible) }
    }

    // Playing.

    /** chapters.mkv, posted on the test's own server and prepared. */
    private suspend fun release(): GapFixtures.Release {
        val mkv = fixtures.media("chapters.mkv")
        return fixtures.release("chapters.mkv", mkv, 100_000, "double-tap", RELEASE) { emptySet() }
    }

    /** The invented film from a minute in, playing, and its seeks watched from there. */
    private fun play(check: suspend (ActivityScenario<PlayerActivity>, CopyOnWriteArrayList<Pair<Long, Long>>) -> Unit) = runBlocking<Unit> {
        play(fixtures.launch(release().prepared, GUID, START_MS), check = check)
    }

    /** [launch] playing, on a TV's form if [tv]; [check] with its seeks (where to, and when on the uptime clock) watched from there. */
    private suspend fun play(launch: PlayerLaunch, tv: Boolean = false, check: suspend (ActivityScenario<PlayerActivity>, CopyOnWriteArrayList<Pair<Long, Long>>) -> Unit) {
        val intent = PlayerActivity.intent(context, launch).apply { if (tv) putExtra(FormFactor.EXTRA, "tv") }
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            waitFor("playback", timeoutMs = 40_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition > 0 } } }
            val seeks = CopyOnWriteArrayList<Pair<Long, Long>>()
            scenario.onActivity { a ->
                a.player!!.addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks += newPosition.positionMs to SystemClock.uptimeMillis()
                        }
                    },
                )
            }
            check(scenario, seeks)
        }
    }

    private fun List<Pair<Long, Long>>.positions() = map { it.first }

    private suspend fun showControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a -> playerView(a).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
    }

    /** Hidden, with their hide animation over. */
    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a -> playerView(a).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!controlsUp(a)).takeIf { it } } }
        delay(800)
    }

    private fun assertNear(what: String, expected: Long, actual: Long) {
        assertTrue("$what is $actual, not about $expected", abs(actual - expected) < 500)
    }

    // Touch, as a finger gives it.

    /** The point [fx] of the picture's width and [fy] of its height across, on the screen. */
    private fun at(scenario: ActivityScenario<PlayerActivity>, fx: Float, fy: Float): PointF = scenario.read { a ->
        val r = rectOnScreen(playerView(a))
        PointF(r.left + r.width() * fx, r.top + r.height() * fy)
    }

    /** One finger down and up at [p]. */
    private suspend fun tap(p: PointF) {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, listOf(p))
        delay(50)
        send(down, MotionEvent.ACTION_UP, listOf(p))
    }

    /** Two taps at [p], the second 100 ms after the first's up: well within the double-tap timeout. */
    private suspend fun doubleTap(p: PointF) {
        tap(p)
        delay(100)
        tap(p)
    }

    private fun send(downTime: Long, action: Int, fingers: List<PointF>) {
        val properties = fingers.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = fingers.map { f -> MotionEvent.PointerCoords().apply { x = f.x; y = f.y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, fingers.size, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    // Views.

    private fun playerView(a: Activity) = views(a.window.decorView).filterIsInstance<PlayerView>().single()

    private fun backArrow(a: Activity) = views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /** Whether any of the controls shows: the top bar comes and goes with them at once, without their fade. */
    private fun controlsUp(a: Activity) = backArrow(a).isShown

    /** The side mark's pill, while it shows. */
    private fun mark(a: Activity): View? = a.findViewById<View>(R.id.side_mark)?.takeIf { it.isShown }

    /** How far the mark says the burst goes. */
    private fun count(a: Activity): String? = mark(a)?.let { a.findViewById<TextView>(R.id.side_mark_count).text.toString() }

    /** Where the mark says it goes, while it says it. */
    private fun markTime(a: Activity): String? = a.findViewById<TextView>(R.id.side_mark_time)?.takeIf { it.isShown }?.text?.toString()

    private fun markRect(a: Activity): RectF = rectOnScreen(mark(a)!!)

    /** How far the mark says, and on which side of the picture it shows; nulls while it doesn't. */
    private fun side(a: Activity): Pair<String?, String?> = count(a) to mark(a)?.let { if (rectOnScreen(it).centerX() > screenWidth(a) / 2) "right" else "left" }

    /** The label under the top bar, while it shows. */
    private fun topLabel(a: Activity): TextView? = a.findViewById<TextView>(R.id.moment_label)?.takeIf { it.isShown }

    /** The next-episode card's bounds on the screen, while it shows: its Compose view, the one with Play now, is as large as it. */
    private fun cardRect(a: Activity): RectF? =
        views(a.window.decorView).filterIsInstance<ViewRootForTest>().firstOrNull { it.view.isShown && "Play now" in words(it.semanticsOwner.unmergedRootSemanticsNode) }
            ?.let { rectOnScreen(it.view) }

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::words)

    private fun screenWidth(a: Activity): Float = playerView(a).width.toFloat()

    private fun rectOnScreen(v: View): RectF {
        val xy = IntArray(2).also(v::getLocationOnScreen)
        return RectF(xy[0].toFloat(), xy[1].toFloat(), xy[0] + v.width * v.scaleX, xy[1] + v.height * v.scaleY)
    }

    /** How the player writes [ms]. */
    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** How media3's controls write [ms], to the nearest second: the time bar's contentDescription. */
    private fun barTime(ms: Long): String = StringBuilder().let { b -> Util.getStringForTime(b, Formatter(b, Locale.getDefault()), ms) }

    private companion object {
        /** An invented film and show; nothing real has the ids. */
        const val FILM = 900051
        const val TITLE = "Tidewater Hall"
        const val SHOW = 900052
        const val SHOW_NAME = "Saltmarsh Lane"
        const val RELEASE = "Tidewater.Hall.2026.1080p.WEB.h264-TAPS"
        const val GUID = "tidewater-hall-taps"
        const val INDEXER = "double-tap-device-test"
        /** The chapters fixture's six minutes. */
        const val DURATION_MS = 360_000L
        /** A minute in, clear of the start for a tap back. */
        const val START_MS = 60_000L
        val NEXT = EpisodePlay(
            showTmdbId = SHOW, showName = SHOW_NAME, showYear = 2024, aliases = emptyList(), ids = TvIds(tvdb = 900053),
            season = 1, episode = 2, episodeName = "Low Water", airDate = null, runtimeMinutes = 6,
        )
    }
}

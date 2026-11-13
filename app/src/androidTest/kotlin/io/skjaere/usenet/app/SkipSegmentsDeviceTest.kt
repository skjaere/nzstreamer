package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.FileChapter
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Skip intro and credits end to end, through the real UsenetDataSource: a six-minute test pattern
 * (`app/src/androidTest/fixtures/chapters.mkv`, made with ffmpeg from testsrc and a sine tone, its
 * chapters from `chapters.ffmetadata`: "Prologue" 0–5 s, "Intro" 5–25 s, "Part A" 25 s–5:30,
 * "Credits" 5:30–6:00) streamed from the mock NNTP server by the NZB in this test's assets. Six
 * minutes, because an intro must end five minutes before the end of the file to count. The
 * matroska chapters reach the player with the tracks; TheIntroDB is stubbed out (no lookups). A
 * test that would wait out the skip button's ten seconds, or the next-episode card's, gives them
 * shorter ones ([PlayerActivity.timings]).
 *
 * The NZB stays valid while the mock keeps the fixture's articles (their ids come from the file's
 * bytes). After restarting the mock, seed them again, writing the NZB anywhere but over the asset:
 * `./gradlew :tools:mock-seed:run --args="seed --video app/src/androidTest/fixtures/chapters.mkv --out build/chapters.nzb --segment 100000"`.
 * To change the fixture, regenerate it and write the NZB over `app/src/androidTest/assets/chapters.nzb`:
 * `ffmpeg -f lavfi -i testsrc=duration=360:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=360:sample_rate=8000
 * -i chapters.ffmetadata -map 0:v -map 1:a -map_metadata 2 -map_chapters 2 -c:v libx264 -profile:v high -pix_fmt yuv420p
 * -preset veryslow -crf 38 -g 50 -c:a aac -b:a 8k -ac 1 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact -shortest chapters.mkv`.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SkipSegmentsDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realRead = PlayerActivity.readPlayback
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realChapters = PlayerActivity.readChapters
    private val realTimings = PlayerActivity.timings
    /** Indexers that were on before a test that switched them off; switched back on after it. */
    private var switchedOff = emptyList<String>()
    private var fakeIndexerAdded = false
    /** The playback preferences before a test pressed the aspect button, which saves the picture's size; put back after it. */
    private var playbackBefore: PlaybackPrefs? = null
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.previousEpisode = { _, _ -> null }
    }

    @After
    fun restore() {
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.readPlayback = realRead
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        PlayerActivity.timings = realTimings
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            if (fakeIndexerAdded) container.settings.deleteIndexer(FAKE_INDEXER)
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
            playbackBefore?.let { before ->
                // The player saves in the app's scope: let the save land before putting them back.
                withTimeoutOrNull(5_000) { container.settings.playback.first { it != before } }
                container.settings.restore(container.settings.settings.first().copy(playback = before))
            }
        }
    }

    private fun uri() = UsenetUris.build(fixture.id, fixture.defaultPick!!.index)

    /** The fixture opened as an NZB file is: no launch, so no resume and no next episode. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, uri())

    @Test
    fun theSkipIntroButtonShowsInTheIntroAndSkipsToItsEnd() = runBlocking<Unit> {
        PlayerActivity.timings = PlayerActivity.Timings(skipShowForMs = 1_000)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val duration = waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            assertTrue("duration $duration", duration in 355_000L..365_000L)
            // Playing from the start: it shows as the intro starts at 5 s.
            val shownAt = waitFor("Skip intro", timeoutMs = 30_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { "Skip intro" in overlayWords(a) } } }
            assertTrue("showed at $shownAt ms", shownAt in INTRO_START_MS..INTRO_START_MS + 2_000)
            // Its time on screen later (a second here) it has gone by itself, without skipping anything.
            val goneAt = waitFor("Skip intro to go", timeoutMs = 5_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { "Skip intro" !in overlayWords(a) } } }
            assertTrue("went at $goneAt ms", goneAt in shownAt + 500..shownAt + 3_000)
            assertTrue("it skipped by itself to $goneAt", goneAt < INTRO_END_MS - 2_000)
            // The controls (held up for the test) bring it back while still in the intro, clear of
            // their buttons and time bar, also on a 360 dp tall screen.
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("Skip intro with the controls") { scenario.read { a -> overlayWords(a).takeIf { "Skip intro" in it && playerView(a).isControllerFullyVisible } } }
            assertClearOfTheControls(scenario, "on this screen")
            layOutShort(scenario)
            assertClearOfTheControls(scenario, "on a 360 dp tall screen")
            // Pressing it lands at the end of the intro.
            scenario.onActivity { a -> overlayNodes(a).single { n -> n.config.contains(SemanticsActions.OnClick) && subtree(n).any { "Skip intro" in it.words() } }.config[SemanticsActions.OnClick].action!!.invoke() }
            val landed = scenario.read { it.player!!.currentPosition }
            assertTrue("landed at $landed ms", landed in INTRO_END_MS..INTRO_END_MS + 1_500)
            waitFor<Boolean>("Skip intro to go after the press") { scenario.read { a -> ("Skip intro" !in overlayWords(a)).takeIf { it } } }
            // Gone, it holds the centre buttons up no longer.
            assertCentredClearOfTheLabel(scenario, "on a 360 dp tall screen")
        }
    }

    /** A TV remote: the button takes focus as it shows, and the centre key skips; Back hides it. */
    @Test
    fun onATvRemoteTheButtonTakesFocusAndBackHidesIt() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // Seeking into the intro counts as entering it.
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 3_000) }
            waitFor("Skip intro to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Skip intro to go") { scenario.read { a -> ("Skip intro" !in overlayWords(a)).takeIf { it } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertTrue("Back skipped", scenario.read { it.player!!.currentPosition } < INTRO_END_MS - 2_000)
            // Out of the intro and back in: it shows again, and the centre key skips to its end.
            scenario.onActivity { it.player!!.seekTo(1_000) }
            delay(1_000)
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 3_000) }
            waitFor("Skip intro to have focus again") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val landed = waitFor("the skip") { scenario.read { a -> a.player!!.currentPosition.takeIf { it >= INTRO_END_MS } } }
            assertTrue("landed at $landed ms", landed in INTRO_END_MS..INTRO_END_MS + 1_500)
            waitFor("Skip intro to go after the press") { scenario.read { a -> ("Skip intro" !in overlayWords(a)).takeIf { it } } }
            // The focus doesn't stay with the hidden button: it is on play/pause with the controls
            // up, else on PlayerView (or nowhere), where D-pad keys bring the controls.
            scenario.read { a ->
                val focused = a.currentFocus
                val ok = if (playerView(a).isControllerFullyVisible) focused?.id == androidx.media3.ui.R.id.exo_play_pause else focused == null || focused === playerView(a)
                assertTrue("focus left on $focused", ok)
            }
        }
    }

    /**
     * A TV remote in the intro after the button's time on screen (a second here): OK pauses and
     * brings the controls (RemoteKeys), and the button with them, but play/pause keeps the focus,
     * so the next OK plays rather than skips.
     */
    @Test
    fun onATvRemoteTheButtonComingBackWithTheControlsLeavesTheFocusOnPlayPause() = runBlocking<Unit> {
        PlayerActivity.timings = PlayerActivity.Timings(skipShowForMs = 1_000)
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 1_000) }
            waitFor("Skip intro to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            waitFor("Skip intro to go", timeoutMs = 5_000) { scenario.read { a -> ("Skip intro" !in overlayWords(a)).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the pause, with Skip intro and the controls", timeoutMs = 3_000) {
                scenario.read { a -> (!a.player!!.playWhenReady && "Skip intro" in overlayWords(a) && playerView(a).isControllerFullyVisible).takeIf { it } }
            }
            // A few of the button's updates later, the focus is still on play/pause.
            delay(1_000)
            scenario.read { a -> assertEquals("the focus is on ${a.currentFocus}", androidx.media3.ui.R.id.exo_play_pause, a.currentFocus?.id) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("playback again", timeoutMs = 3_000) { scenario.read { a -> a.player!!.playWhenReady.takeIf { it } } }
            val at = scenario.read { it.player!!.currentPosition }
            assertTrue("OK skipped to $at ms", at < INTRO_END_MS - 1_000)
        }
    }

    /**
     * A TV remote reaches the button that came back with the controls without taking the focus: it
     * is next below play/pause, where it shows (by distance alone the D-pad passes it for the time
     * bar). The time bar is next below it, so scrubbing is a step further, and up from either goes
     * back to play/pause. OK on the button skips. Its time on screen is a second here.
     */
    @Test
    fun onATvRemoteDownFromPlayPauseReachesTheButtonThatCameBackWithTheControls() = runBlocking<Unit> {
        PlayerActivity.timings = PlayerActivity.Timings(skipShowForMs = 1_000)
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 1_000) }
            waitFor("Skip intro to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Skip intro" in it } } }
            waitFor("Skip intro to go", timeoutMs = 5_000) { scenario.read { a -> ("Skip intro" !in overlayWords(a)).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("Skip intro with the controls, play/pause focused", timeoutMs = 3_000) {
                scenario.read { a -> ("Skip intro" in overlayWords(a) && playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } }
            }
            suspend fun press(key: Int, focused: String, isIt: (PlayerActivity) -> Boolean) {
                instrumentation.sendKeyDownUpSync(key)
                waitFor("$focused to have focus after ${KeyEvent.keyCodeToString(key)}, not ${scenario.read { it.currentFocus }}", timeoutMs = 2_000) {
                    scenario.read { a -> isIt(a).takeIf { it } }
                }
            }
            val skip = { a: PlayerActivity -> "Skip intro" in focusedWords(a) }
            val playPause = { a: PlayerActivity -> a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause }
            press(KeyEvent.KEYCODE_DPAD_DOWN, "Skip intro", skip)
            press(KeyEvent.KEYCODE_DPAD_DOWN, "the time bar") { a -> a.currentFocus?.id == androidx.media3.ui.R.id.exo_progress }
            // Up goes back to play/pause, from the time bar as from the button.
            press(KeyEvent.KEYCODE_DPAD_UP, "play/pause", playPause)
            press(KeyEvent.KEYCODE_DPAD_DOWN, "Skip intro", skip)
            press(KeyEvent.KEYCODE_DPAD_UP, "play/pause", playPause)
            press(KeyEvent.KEYCODE_DPAD_DOWN, "Skip intro", skip)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val landed = waitFor("the skip") { scenario.read { a -> a.player!!.currentPosition.takeIf { it >= INTRO_END_MS } } }
            assertTrue("landed at $landed ms", landed in INTRO_END_MS..INTRO_END_MS + 1_500)
        }
    }

    /**
     * A button that appeared with the controls up (seeking into the intro from them) leaves the
     * focus on play/pause, but takes it as they hide while it still shows, each time: the D-pad
     * would otherwise only bring them back, and OK would never skip.
     */
    @Test
    fun onATvRemoteTheButtonTakesTheFocusAsTheControlsHide() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 1_000) }
            waitFor("Skip intro with the controls") { scenario.read { a -> ("Skip intro" in overlayWords(a) && playerView(a).isControllerFullyVisible).takeIf { it } } }
            delay(500)
            scenario.read { a -> assertEquals("the focus is on ${a.currentFocus}", androidx.media3.ui.R.id.exo_play_pause, a.currentFocus?.id) }
            suspend fun hideControls() {
                scenario.onActivity { playerView(it).hideController() }
                waitFor("Skip intro to have focus as the controls hide", timeoutMs = 3_000) {
                    scenario.read { a -> (!playerView(a).isControllerFullyVisible && "Skip intro" in focusedWords(a)).takeIf { it } }
                }
            }
            hideControls()
            // A key other than OK brings them back, with the focus on play/pause again.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            waitFor("the controls, play/pause focused again", timeoutMs = 3_000) {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } }
            }
            hideControls()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val landed = waitFor("the skip") { scenario.read { a -> a.player!!.currentPosition.takeIf { it >= INTRO_END_MS } } }
            assertTrue("landed at $landed ms", landed in INTRO_END_MS..INTRO_END_MS + 1_500)
        }
    }

    /**
     * Backlog #139: a TV remote's D-pad brings back the hidden controls whatever has the focus;
     * OK also plays or pauses (TV-PC, RemoteKeys), Down only brings them. Once they hide, Android
     * gives the focus to PlayerView itself (clickable, so focusable by default since API 26). With
     * nothing focused, the key reaches the player only because PlayerActivity takes keys first;
     * here that is a PlayerView that can't take focus.
     */
    @Test
    fun aDpadPressBringsBackTheControlsAlsoWithNothingFocused() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // Past the intro, so the skip button doesn't take the focus.
            scenario.onActivity { it.player!!.seekTo(60_000) }
            suspend fun pressBringsControls(key: Int, case: String, focused: (Activity) -> Boolean) {
                scenario.onActivity { playerView(it).hideController() }
                waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
                // Their hide animation, after which play/pause no longer has the focus.
                delay(1_000)
                assertTrue("$case: the focus is on ${scenario.read { it.currentFocus }}", scenario.read(focused))
                val playing = scenario.read { it.player!!.playWhenReady }
                instrumentation.sendKeyDownUpSync(key)
                waitFor<Boolean>("the controls to show after ${KeyEvent.keyCodeToString(key)} $case", timeoutMs = 3_000) {
                    scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } }
                }
                val toggled = key == KeyEvent.KEYCODE_DPAD_CENTER
                assertEquals("${KeyEvent.keyCodeToString(key)} $case: playing", playing != toggled, scenario.read { it.player!!.playWhenReady })
            }
            listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN).forEach { key ->
                pressBringsControls(key, "with PlayerView focused") { a -> a.currentFocus === playerView(a) }
            }
            scenario.onActivity { a ->
                playerView(a).isFocusable = false
                a.currentFocus?.clearFocus()
            }
            listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN).forEach { key ->
                pressBringsControls(key, "with nothing focused") { a -> a.currentFocus == null }
            }
        }
    }

    /**
     * Keys go to PlayerView first only while it has the focus or nothing has: OK on the on-screen
     * back arrow leaves, also as the controls fade. media3 hides them in two steps, the time bar
     * staying a moment after the buttons; meanwhile the arrow still shows, and PlayerView would
     * take any D-pad key to bring the controls back.
     */
    @Test
    fun onATvRemoteOkOnTheBackArrowLeavesAsTheControlsFade() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // Past the intro, so the skip button doesn't take the focus.
            scenario.onActivity { it.player!!.seekTo(60_000) }
            waitFor("playback past the intro") { scenario.read { a -> (a.player!!.isPlaying && a.player!!.currentPosition >= 60_000).takeIf { it } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                assertTrue(backArrow(a).requestFocus())
            }
            // The controls' buttons go after their timeout, the time bar and the arrow a little later.
            waitFor("the controls to fade with the arrow still focused", timeoutMs = 10_000) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && backArrow(a).isShown && backArrow(a).isFocused).takeIf { it } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor<Boolean>("the player to close", timeoutMs = 3_000) { (scenario.state == Lifecycle.State.DESTROYED).takeIf { it } }
        }
    }

    private fun backArrow(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /**
     * Credits feed the next-episode card: for a TMDB episode it shows from the credits' start
     * (5:30), offering Stop and Play now, but counts down only in the last seconds (ten in the
     * app, two here). The next-episode seam stands in for TMDB; the fake indexer (`mock-seed
     * indexer`, 10.0.2.2:8089) answers the card's search for S01E02.
     */
    @Test
    fun theCreditsBringTheNextEpisodeCardAndTheCountdownWaitsForTheEnd() = runBlocking<Unit> {
        PlayerActivity.timings = PlayerActivity.Timings(cardBeforeEndMs = 2_000)
        useFakeIndexer()
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = true) }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch())).use { scenario ->
            val duration = waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { it.player!!.seekTo(CREDITS_START_MS - 3_000) }
            val (shownAt, card) = waitFor("the next-episode card", timeoutMs = 20_000) {
                scenario.read { a -> cardWords(a).takeIf { "Play now" in it }?.let { a.player!!.currentPosition to it } }
            }
            assertTrue("the card showed at $shownAt ms", shownAt in CREDITS_START_MS..CREDITS_START_MS + 2_000)
            assertTrue(card.toString(), "S01E02 · Episode 2" in card && "Stop" in card)
            assertFalse("a countdown in the credits: $card", "Next episode plays automatically" in card)
            // Well into the credits, still none.
            scenario.onActivity { it.player!!.seekTo(duration - 6_000) }
            delay(1_500)
            val later = scenario.read(::cardWords)
            assertTrue(later.toString(), "Play now" in later && "Next episode plays automatically" !in later)
            // The countdown starts in the last two seconds.
            val countingAt = waitFor("the countdown", timeoutMs = 30_000) {
                scenario.read { a -> a.player!!.currentPosition.takeIf { "Next episode plays automatically" in cardWords(a) } }
            }
            assertTrue("the countdown started at $countingAt ms, the file ends at $duration", countingAt >= duration - 2_600)
            // Stopped here, so nothing starts.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the card to go") { scenario.read { a -> cardWords(a).isEmpty().takeIf { it } } }
            assertEquals(PlayState.Idle, container.playback.state.value)
        }
    }

    /**
     * Should credits ever start inside an intro (a short file's credits may start anywhere), the
     * next-episode card takes the button's place at once: no frame shows both. The chapters seam
     * stands in for such a file, "Intro" 5–25 s and "Credits" from 15 s, over the fixture.
     */
    @Test
    fun whereCreditsMeetAnIntroTheCardReplacesTheButtonAtOnce() = runBlocking<Unit> {
        useFakeIndexer()
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.readChapters = { listOf(FileChapter("Intro", INTRO_START_MS, INTRO_END_MS), FileChapter("Credits", 15_000, 360_000)) }
        // Counted on every frame drawn from the seek on.
        val framesWithBoth = AtomicInteger()
        val framesWithCard = AtomicInteger()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch())).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { a ->
                // The overlays, as big as their content: not the words under the top bar, which take its width.
                val all = views(a.window.decorView).filterIsInstance<ComposeView>().filter { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT }
                assertEquals(5, all.size)
                // The card and the skip button; then the missing-parts prompt, the slow-stream banner and the "Playback stopped" panel.
                val overlays = all.take(2)
                a.window.decorView.viewTreeObserver.addOnPreDrawListener {
                    if (overlays[0].isShown || overlays[1].isShown) {
                        if (overlays.all { it.isShown }) framesWithBoth.incrementAndGet()
                        if ("Play now" in overlayWords(a)) framesWithCard.incrementAndGet()
                    }
                    true
                }
            }
            // In and out several times, so the card meets the button at different points of their updates.
            repeat(5) {
                scenario.onActivity { it.player!!.seekTo(2_000) }
                waitFor("the card and the button to go") { scenario.read { a -> overlayWords(a).isEmpty().takeIf { it } } }
                delay(it * 70L)
                scenario.onActivity { it.player!!.seekTo(13_000) }
                waitFor("Skip intro") { scenario.read { a -> overlayWords(a).takeIf { "Skip intro" in it || "Play now" in it } } }
                waitFor("the next-episode card", timeoutMs = 10_000) { scenario.read { a -> overlayWords(a).takeIf { "Play now" in it } } }
                delay(500)
            }
            assertTrue("no frame drew the card", framesWithCard.get() > 0)
            assertEquals("frames that drew the card and the button together", 0, framesWithBoth.get())
            // Back now goes to the card.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the card to go") { scenario.read { a -> ("Play now" !in overlayWords(a)).takeIf { it } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** The fixture played as Harbor Lights S01E01, a TMDB episode that leads on to S01E02. */
    private fun episodeLaunch() = PlayerLaunch(
        uri = uri(), title = "Harbor Lights", subtitle = "S01E01 · Episode 1", watchKey = WatchKey.episode(SHOW, 1, 1),
        watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = SHOW, season = 1, episode = 1),
        release = SavedRelease("fixture", "chapters", "Harbor.Lights.S01E01.Chapters", null, fixture.defaultPick!!.index, fixture.id),
    )

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = SHOW, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = "Episode $n", airDate = null, runtimeMinutes = 45,
    )

    /** The card's search for the next episode asks the fake indexer only. */
    private suspend fun useFakeIndexer() {
        assumeTrue(
            "fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)",
            runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess,
        )
        switchedOff = container.settings.indexers.first().filter { it.enabled && it.id != FAKE_INDEXER }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
        container.settings.saveIndexer(FAKE_INDEXER, "Fake (skip segments test)", "http://10.0.2.2:8089", "fake-indexer-key")
        fakeIndexerAdded = true
    }

    /** With the controls up the button keeps below their centre buttons and above the time bar, as the compact card does, [where]. */
    private suspend fun assertClearOfTheControls(scenario: ActivityScenario<PlayerActivity>, where: String) {
        fun placed(a: Activity): Triple<Float, RectF, RectF> {
            val row = a.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
            val centre = (0 until row.childCount).map(row::getChildAt).filter { it.isShown }.maxOf { windowRect(it).bottom }
            return Triple(centre, windowRect(a.findViewById(androidx.media3.ui.R.id.exo_progress)), buttonRect(a))
        }
        // Laid out again a frame after the controls show or the player's size changes.
        val (centre, timeBar, button) = withTimeoutOrNull(3_000) {
            var now = scenario.read(::placed)
            while (now.third.top < now.first) {
                delay(100)
                now = scenario.read(::placed)
            }
            now
        } ?: scenario.read(::placed)
        val density = context.resources.displayMetrics.density
        assertTrue("the button $button reaches ${(centre - button.top) / density} dp past the centre controls' bottom at $centre $where", button.top >= centre)
        assertTrue("the button $button covers the time bar $timeBar $where", button.bottom <= timeBar.top)
    }

    /**
     * Without the button the controls' centre buttons stay in the middle [where], and the picture's
     * new size, said under the top bar as the aspect button is pressed, keeps clear of them.
     */
    private suspend fun assertCentredClearOfTheLabel(scenario: ActivityScenario<PlayerActivity>, where: String) {
        playbackBefore = container.settings.playback.first()
        scenario.onActivity { a -> aspectButton(a).performClick() }
        // Laid out a frame after it shows.
        val (label, centre) = waitFor("the label") { scenario.read { a -> zoomLabel(a)?.takeIf { it.height > 0 }?.let { l -> windowRect(l) to centreTop(a) } } }
        val density = context.resources.displayMetrics.density
        assertTrue("the label $label reaches ${(label.bottom - centre) / density} dp into the centre controls at $centre $where", label.bottom <= centre)
        val lift = scenario.read { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls).translationY }
        assertTrue("the centre controls are lifted ${-lift / density} dp $where", lift == 0f)
    }

    /**
     * The player's views laid out 360 dp tall at the bottom of the window, as on a phone that short
     * held sideways: the window's insets stay this screen's, with the gesture area under them.
     */
    private suspend fun layOutShort(scenario: ActivityScenario<PlayerActivity>) {
        val height = (360 * context.resources.displayMetrics.density).roundToInt()
        scenario.onActivity { a ->
            a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).updateLayoutParams<FrameLayout.LayoutParams> {
                this.height = height
                gravity = Gravity.BOTTOM
            }
        }
        waitFor("the player laid out 360 dp tall") { scenario.read { a -> playerView(a).takeIf { it.height == height } } }
    }

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

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun windowRect(view: View): RectF {
        val at = IntArray(2).also(view::getLocationInWindow)
        return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    /** Every semantics node the shown Compose views of [activity] hold: the next-episode card and the skip button. */
    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { it.words() }

    /** The skip button never shows with the card, so what the overlays say is the card's while it shows. */
    private fun cardWords(activity: Activity) = overlayWords(activity)

    private fun focusedWords(activity: Activity) =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::subtree).orEmpty().flatMap { it.words() }

    /** Where the controls' shown centre buttons start, inside their row's padding. */
    private fun centreTop(activity: Activity): Float {
        val row = activity.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
        return (0 until row.childCount).map(row::getChildAt).filter { it.isShown }.minOf { windowRect(it).top }
    }

    private fun aspectButton(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription?.startsWith("Aspect:") == true }

    /** The picture's new size, said for a moment under the top bar: a polite live region. */
    private fun zoomLabel(activity: Activity) =
        views(activity.window.decorView).filterIsInstance<TextView>().singleOrNull { it.isShown && it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }

    /** The skip button's bounds: its node is the polite live region. */
    private fun buttonRect(activity: Activity): RectF =
        overlayNodes(activity).single { it.config.contains(SemanticsProperties.LiveRegion) }.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) }

    private companion object {
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val SHOW = 900002
        const val FAKE_INDEXER = "skip-segments-device-test"
        const val INTRO_START_MS = 5_000L
        const val INTRO_END_MS = 25_000L
        const val CREDITS_START_MS = 330_000L
    }
}

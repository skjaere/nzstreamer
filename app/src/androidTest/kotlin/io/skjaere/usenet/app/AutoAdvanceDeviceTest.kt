package io.skjaere.usenet.app

import android.app.Activity
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Auto-advance end to end: Harbor Lights S01E01 found and prepared by the app's coordinator from
 * the fake indexer (`mock-seed indexer`, 10.0.2.2:8089) and the mock NNTP server, played to near
 * its end; the next-episode card offers S01E02 and the coordinator starts it as the player closes.
 * The next-episode seam stands in for TMDB. The app's indexers are swapped for the fake one while
 * a test runs, since the player's prefetch and the coordinator search those. A test that would
 * wait out the card's ten-second countdown gives it a shorter one ([PlayerActivity.timings]), and
 * seeks nearer the end where it waits for the file to end as well.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AutoAdvanceDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realRead = PlayerActivity.readPlayback
    private val realTimings = PlayerActivity.timings
    /** Indexers that were on before the test; switched back on after it. */
    private var switchedOff = emptyList<String>()

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = SHOW, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = "Episode $n", airDate = null, runtimeMinutes = 45,
    )

    @Before
    fun setUp() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        runBlocking {
            engineSetup("the mock on the engine") { mockOnEngine() }
            switchedOff = container.settings.indexers.first().filter { it.enabled && it.id != FAKE_INDEXER }.map { it.id }
            switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (auto-advance test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = true) }
    }

    @After
    fun restore() {
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.readPlayback = realRead
        PlayerActivity.timings = realTimings
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            container.settings.deleteIndexer(FAKE_INDEXER)
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
        }
    }

    @Test
    fun theCountdownStartsTheNextEpisode() = runBlocking<Unit> {
        // Long enough for the card to say the release was found before it moves on.
        val countdown = 3_000L
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = countdown)
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            val card = waitFor("the next-episode card") { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E02" in it } } } }
            val seenAt = SystemClock.elapsedRealtime()
            assertTrue(card.toString(), "S01E02 · Episode 2" in card && "Play now" in card && "Stop" in card)
            assertTrue(card.toString(), "Next episode plays automatically" in card)
            // The search for S01E02 started as the card showed, while S01E01 played on.
            waitFor("the release for S01E02") { scenario.read { a -> cardWords(a).firstOrNull { it.startsWith("Release found") } } }
            waitFor("the player to close", timeoutMs = 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            val waited = SystemClock.elapsedRealtime() - seenAt
            assertTrue("closed ${waited} ms after the card showed; the countdown is $countdown ms", waited in countdown - 2_000..countdown + 6_000)
        }
        val next = launchFor(2)
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S01E02", showTmdbId = SHOW, season = 1, episode = 2), next.watch)
        assertTrue(next.release.title, "S01E02" in next.release.title)
        // Saved on the way out: S01E01 counts as watched.
        assertEquals(true, container.library.state(WatchKey.episode(SHOW, 1, 1)).first()?.watched)
    }

    /** A TV remote: the card takes focus on Play now, and the D-pad centre starts the next episode at once. */
    @Test
    fun dpadCentreOnPlayNowStartsTheNextEpisodeAtOnce() = runBlocking<Unit> {
        val first = start(episode(1))
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("Play now to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Play now" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(2, launchFor(2).watch.episode)
    }

    /**
     * Back hides the card and nothing advances: with the card stopped there is nothing to move on
     * to, so the player leaves at the end (EndOfPlay), and S01E02 never starts.
     */
    @Test
    fun backHidesTheCardAndNothingAdvances() = runBlocking<Unit> {
        val countdown = 2_000L
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = countdown)
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario, beforeEndMs = 2_000)
            waitFor("the next-episode card") { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E02" in it } } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the card to go") { scenario.read { a -> cardWords(a).isEmpty().takeIf { it } } }
            // The end is waited for, not a fixed time: the seek can buffer for seconds while the card's search shares the
            // mock server's four connections (#239).
            waitFor("the player to leave at the end", timeoutMs = 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        // Past where the countdown would have ended.
        assertEquals(null, withTimeoutOrNull(countdown + 2_000) { container.playback.launches.first { it.watch.showTmdbId == SHOW && it.watch.episode == 2 } })
        assertEquals(PlayState.Idle, container.playback.state.value)
        // Saved as it ended: S01E01 counts as watched.
        assertEquals(true, container.library.state(WatchKey.episode(SHOW, 1, 1)).first()?.watched)
    }

    /**
     * The audio and subtitles sheet covers the card, whose Stop it leaves out of reach: the
     * countdown holds while the sheet shows, past the end of the file, so the next episode can't
     * start unseen; it runs on once the sheet closes and the card shows again.
     */
    @Test
    fun theCountdownHoldsWhileTheAudioAndSubtitlesSheetShows() = runBlocking<Unit> {
        val countdown = 3_000L
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = countdown)
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario, beforeEndMs = 3_000)
            waitFor("the countdown") { scenario.read { a -> cardWords(a).takeIf { "Stop" in it } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Audio and subtitles" }.performClick()
            }
            waitFor("the sheet") { scenario.read { a -> cardWords(a).takeIf { "Audio and subtitles" in it } } }
            // Past the end of the file (waited for, as the seek can buffer for seconds: #239) and of the countdown.
            waitForTheEnd(scenario)
            delay(countdown + 1_000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals(Player.STATE_ENDED, scenario.read { it.player?.playbackState })
            assertEquals(PlayState.Idle, container.playback.state.value)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the card again, counting") { scenario.read { a -> cardWords(a).takeIf { "Stop" in it && "Audio and subtitles" !in it } } }
            waitFor("the player to close", timeoutMs = 15_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(2, launchFor(2).watch.episode)
    }

    /**
     * Close after the file has ended, paused there: the controls come back with play/pause focused,
     * as they do for a file paused at its end without the card, so a TV remote can replay or seek at
     * once. Playing at the end, Close leaves the player instead, with nothing to move on to
     * (EndOfPlay).
     */
    @Test
    fun closingTheCardAfterTheEndBringsBackTheControls() = runBlocking<Unit> {
        // No countdown: the card stays up at the end until it is closed.
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false) }
        val first = start(episode(1))
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("the next-episode card with Close") { scenario.read { a -> cardWords(a).takeIf { "Close" in it } } }
            waitForTheEnd(scenario)
            // While the card shows, the end of the file leaves the controls down (they'd take Play now's focus).
            waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
            // Paused at the end, as a remote's pause key leaves it: the user is still there.
            scenario.onActivity { it.player!!.pause() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the card to go") { scenario.read { a -> cardWords(a).isEmpty().takeIf { it } } }
            waitFor("the controls to show", timeoutMs = 3_000) { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            assertEquals(androidx.media3.ui.R.id.exo_play_pause, scenario.read { it.currentFocus?.id })
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** Paused with the card up, the controls stay up, as they do when pausing without the card. */
    @Test
    fun pausingWithTheCardUpKeepsTheControlsUp() = runBlocking<Unit> {
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("the next-episode card") { scenario.read { a -> cardWords(a).takeIf { "Play now" in it } } }
            // A tap brings the controls up; then their pause button. (Pausing while they are still
            // animating in leaves media3's own hide timer running, card or not.)
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls to show") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            scenario.onActivity { it.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
            assertEquals(false, scenario.read { it.player?.playWhenReady })
            // Past the controls' 5 s timeout.
            delay(8_000)
            assertTrue("the controls hid while paused", scenario.read { playerView(it).isControllerFullyVisible })
            assertTrue(scenario.read { cardWords(it).contains("Play now") })
        }
    }

    /** The on-screen back arrow leaves as Stop does: the card goes at once and the countdown can't start S01E02. */
    @Test
    fun theBackArrowStopsTheCardAsItLeaves() = runBlocking<Unit> {
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = 2_000)
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("the countdown") { scenario.read { a -> cardWords(a).takeIf { "Stop" in it } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }.performClick()
                assertTrue("the card outlived the back arrow: ${cardWords(a)}", cardWords(a).isEmpty())
            }
            waitFor("the player to close") { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        // Past where the countdown would have ended.
        assertEquals(null, withTimeoutOrNull(4_000) { container.playback.launches.first { it.watch.showTmdbId == SHOW && it.watch.episode == 2 } })
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    /**
     * Once the player is closing, neither the countdown nor Play now starts the next episode: the
     * activity can tick on for a moment after finish() until it stops.
     */
    @Test
    fun nothingAdvancesOnceThePlayerIsLeaving() = runBlocking<Unit> {
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("the next-episode card") { scenario.read { a -> cardWords(a).takeIf { "Play now" in it } } }
            scenario.onActivity { a ->
                a.finish()
                val playNow = cardNodes(a).single { n -> n.config.contains(SemanticsActions.OnClick) && subtree(n).any { "Play now" in it.words() } }
                playNow.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(PlayState.Idle, container.playback.state.value)
            }
            waitFor("the player to close") { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    /**
     * The controls' Next with the card up plays the next episode at once, as Play now does, and the
     * card goes for good: it doesn't come back (Play now taking a remote's focus) while the player
     * closes, though the activity ticks on until it stops.
     */
    @Test
    fun nextOnTheControlsWithTheCardUpLeavesTheCardGone() = runBlocking<Unit> {
        val first = start(episode(1))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            seekNearTheEnd(scenario)
            waitFor("the next-episode card") { scenario.read { a -> cardWords(a).takeIf { "Play now" in it } } }
            waitFor("Next to be enabled") { scenario.read { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_next).takeIf { it.isEnabled } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                a.findViewById<View>(androidx.media3.ui.R.id.exo_next).performClick()
                assertTrue("the card outlived Next: ${cardWords(a)}", cardWords(a).isEmpty())
            }
            // Until the player stops: the card ticks every 100 ms meanwhile.
            val seen = mutableSetOf<List<String>>()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (scenario.state.isAtLeast(Lifecycle.State.STARTED) && SystemClock.elapsedRealtime() < deadline) {
                runCatching { scenario.read(::cardWords) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { seen += it }
                delay(20)
            }
            assertEquals("the card as the player closed", emptySet<List<String>>(), seen)
            waitFor("the player to close") { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(2, launchFor(2).watch.episode)
    }

    /**
     * Playing another episode doesn't wait for the release search the player started for the next
     * one, which a slow indexer can keep going for a minute; that search is dropped instead.
     */
    @Test
    fun anotherEpisodeDoesNotWaitForTheNextOnesSearch() = runBlocking<Unit> {
        SilentIndexer().use { silent ->
            container.settings.saveIndexer(SILENT_INDEXER, "Silent (auto-advance test)", silent.url, "silent-key")
            try {
                container.playback.prefetchEpisode(episode(2))
                waitFor("the search for S01E02 to reach the silent indexer") { silent.connections.size.takeIf { it > 0 } }
                container.playback.playEpisode(episode(1))
                val searching = withTimeoutOrNull(5_000) {
                    container.playback.state.first { it is PlayState.Searching && "for the episode" in it.line }
                }
                assertTrue("S01E01 waited for S01E02's search: ${container.playback.state.value}", searching != null)
            } finally {
                container.playback.cancel()
                container.settings.deleteIndexer(SILENT_INDEXER)
            }
        }
    }

    /**
     * With the controls up the card shrinks to one row, below their centre buttons (play/pause and
     * seeking) and above the time bar, also on a 360 dp tall screen with the gesture area under
     * it, and grows back as they hide. On a shorter screen still the buttons go up for it no
     * further than the top bar. Play now keeps a TV remote's focus.
     */
    @Test
    fun theCardMakesRoomForTheControls() = runBlocking<Unit> {
        // No countdown: the card stays while the test looks at it.
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false) }
        val first = start(episode(1))
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            val duration = waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // Seeking to the end with the controls up (held up for the test): the card shows while they do.
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
                a.player!!.seekTo(duration - 6_000)
            }
            waitFor("Play now to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Play now" in it } } }
            assertBetweenTheControls(scenario, "on this screen")
            layOutShort(scenario)
            assertBetweenTheControls(scenario, "on a 360 dp tall screen")
            layOutShort(scenario, 300)
            assertBelowTheTopBar(scenario, "on a 300 dp tall screen")
            val words = scenario.read(::cardWords)
            assertTrue(words.toString(), words.containsAll(listOf("Close", "Play now")))
            scenario.onActivity { playerView(it).hideController() }
            waitFor("the full card") { scenario.read { a -> cardWords(a).takeIf { "S01E02 · Episode 2" in it } } }
            assertTrue("Play now lost the focus", "Play now" in scenario.read { focusedWords(it) })
        }
    }

    /**
     * The compact card below the controls' centre buttons (previous, rewind, play/pause,
     * fast-forward and next, inside the row's padding) and above the time bar, [where].
     */
    private suspend fun assertBetweenTheControls(scenario: ActivityScenario<PlayerActivity>, where: String) {
        fun placed(a: Activity): Triple<Float, RectF, RectF> {
            val row = a.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
            val centre = (0 until row.childCount).map(row::getChildAt).filter { it.isShown }.maxOf { windowRect(it).bottom }
            return Triple(centre, windowRect(a.findViewById(androidx.media3.ui.R.id.exo_progress)), cardRect(a))
        }
        // Laid out again a frame after the controls show or the player's size changes.
        val (centre, timeBar, card) = withTimeoutOrNull(3_000) {
            var now = scenario.read(::placed)
            while (now.third.top < now.first) {
                delay(100)
                now = scenario.read(::placed)
            }
            now
        } ?: scenario.read(::placed)
        val density = context.resources.displayMetrics.density
        assertTrue("the card $card reaches ${(centre - card.top) / density} dp past the centre controls' bottom at $centre $where", card.top >= centre)
        assertTrue("the card $card covers the time bar $timeBar $where", card.bottom <= timeBar.top)
    }

    /**
     * The controls' centre buttons, lifted for the card, start no higher than the top bar's bottom
     * [where]: the card covers their bottom rather than they the titles.
     */
    private fun assertBelowTheTopBar(scenario: ActivityScenario<PlayerActivity>, where: String) {
        val (centre, topBar) = scenario.read { a ->
            val row = a.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
            val back = views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }
            (0 until row.childCount).map(row::getChildAt).filter { it.isShown }.minOf { windowRect(it).top } to windowRect(back.parent as View).bottom
        }
        val density = context.resources.displayMetrics.density
        assertTrue("the centre controls start at $centre, ${(topBar - centre) / density} dp over the top bar's bottom at $topBar $where", centre >= topBar)
    }

    /**
     * The player's views laid out [dp] tall at the bottom of the window, as on a phone that short
     * held sideways: the window's insets stay this screen's, with the gesture area under them.
     */
    private suspend fun layOutShort(scenario: ActivityScenario<PlayerActivity>, dp: Int = 360) {
        val height = (dp * context.resources.displayMetrics.density).roundToInt()
        scenario.onActivity { a ->
            a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).updateLayoutParams<FrameLayout.LayoutParams> {
                this.height = height
                gravity = Gravity.BOTTOM
            }
        }
        waitFor("the player laid out $dp dp tall") { scenario.read { a -> playerView(a).takeIf { it.height == height } } }
    }

    /** Finds and prepares [ep] through the coordinator, as Play on the Show screen does; returns its launch. */
    private suspend fun start(ep: EpisodePlay): PlayerLaunch {
        container.playback.playEpisode(ep)
        return launchFor(ep.episode)
    }

    /** The coordinator's next launch of S01E[episode], or a failure as soon as the play sheet shows one. */
    private suspend fun launchFor(episode: Int): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches.filter { it.watch.showTmdbId == SHOW && it.watch.episode == episode },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("S01E0$episode didn't start: $it") },
        ).first()
    }

    /** Into the last ten seconds, where the card shows: [beforeEndMs] before the end. */
    private suspend fun seekNearTheEnd(scenario: ActivityScenario<PlayerActivity>, beforeEndMs: Long = 6_000) {
        val duration = waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
        scenario.onActivity { it.player!!.seekTo(duration - beforeEndMs) }
    }

    private suspend fun waitForTheEnd(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("the file to end", timeoutMs = 30_000) { scenario.read { a -> (a.player?.playbackState == Player.STATE_ENDED).takeIf { it } } }
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

    /** The next-episode card's bounds: its node is the polite live region. */
    private fun cardRect(activity: Activity): RectF =
        cardNodes(activity).single { it.config.contains(SemanticsProperties.LiveRegion) }.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) }

    /**
     * Every semantics node the shown Compose views of [activity] hold but the top bar's Cast button's (W44), which shows
     * with the controls: the next-episode card.
     */
    private fun cardNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .map { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .filterNot { nodes -> nodes.any { it.config.getOrNull(CastButtonLook) != null } }
            .flatten()

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun cardWords(activity: Activity) = cardNodes(activity).flatMap { it.words() }

    private fun focusedWords(activity: Activity) =
        cardNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::subtree).orEmpty().flatMap { it.words() }

    /** An indexer that takes every connection and never answers, until closed: each search of it runs into its timeout. */
    private class SilentIndexer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val connections = CopyOnWriteArrayList<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true, name = "silent-indexer") {
                while (!server.isClosed) runCatching { connections += server.accept() }
            }
        }

        override fun close() {
            server.close()
            connections.forEach { it.close() }
        }
    }

    private companion object {
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val SHOW = 900002
        const val FAKE_INDEXER = "auto-advance-device-test"
        const val SILENT_INDEXER = "auto-advance-device-test-silent"
    }
}

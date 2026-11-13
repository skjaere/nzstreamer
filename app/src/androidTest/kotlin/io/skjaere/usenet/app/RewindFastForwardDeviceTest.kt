package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
 * Rewind and fast-forward end to end, through the real UsenetDataSource: the chapters.mkv fixture
 * (six minutes of test pattern, see SkipSegmentsDeviceTest; its chapters left out here, so no skip
 * button shows) opened as an NZB file is. Both buttons go 10 s. Taps in quick succession, a
 * remote's ⏩ key presses and OK presses on a focused button add up to one seek, half a second
 * after the last, counted as the player reports its seeks; the controls show where it goes at
 * once. Play/pause meanwhile seeks first, after the end too (where the player stays only with a
 * next episode to offer); Back leaves without seeking; a stop keeps where it goes for the player
 * made again.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RewindFastForwardDeviceTest {
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
    private lateinit var fixture: PreparedNzb
    /** Indexers [offerTheNextEpisode] switched off; switched back on after the test. */
    private var switchedOff = emptyList<String>()
    /** Whether the test played [episodeLaunch], whose progress is put back after it. */
    private var episodePlayed = false

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readChapters = { emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        PlayerActivity.readPlayback = realRead
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
            if (episodePlayed) {
                // Unwatched again, from its start, once the player's last save is in.
                delay(500)
                container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 1, WatchTitle.episode(SHOW_NAME, 1, 1))), watched = false)
            }
        }
    }

    /** The fixture opened as an NZB file is: no launch, so no resume, no saved progress and no next episode. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))

    /** The fixture as an invented show's first episode. */
    private fun episodeLaunch(): PlayerLaunch {
        episodePlayed = true
        return PlayerLaunch(
            uri = UsenetUris.build(fixture.id, fixture.defaultPick!!.index), title = SHOW_NAME, subtitle = "S01E01 · Low Tide", watchKey = WatchKey.episode(SHOW, 1, 1),
            watch = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_NAME, 1, 1), showTmdbId = SHOW, season = 1, episode = 1),
            release = SavedRelease("fixture", "chapters", "Brine.Hollow.S01E01.Chapters", null, fixture.defaultPick!!.index, fixture.id),
        )
    }

    /**
     * The next-episode card for [episodeLaunch] in its last seconds, offering S01E02 (the seam stands in for TMDB) with
     * auto-play off: it waits at the end, and the player with it. Its search asks no indexer, every one switched off.
     */
    private suspend fun offerTheNextEpisode() {
        switchedOff = container.settings.indexers.first().filter { it.enabled }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false) }
        PlayerActivity.nextEpisode = { _, playing -> SECOND.takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.previousEpisode = { _, _ -> null }
    }

    @Test
    fun bothButtonsGoTenSeconds() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            showControls(scenario)
            scenario.read { a ->
                assertEquals("10", rewind(a).text.toString())
                assertEquals("10", fastForward(a).text.toString())
                assertTrue(rewind(a).contentDescription.toString(), "10" in rewind(a).contentDescription)
                assertTrue(fastForward(a).contentDescription.toString(), "10" in fastForward(a).contentDescription)
                assertEquals(10_000L, playerView(a).player!!.seekBackIncrement)
                assertEquals(10_000L, playerView(a).player!!.seekForwardIncrement)
            }
        }
    }

    /** Four taps 150 ms apart: one seek, 40 s on, half a second after the last; the time bar, the position and the label show it at once. */
    @Test
    fun fourQuickTapsOnFastForwardSeekOnceFortySecondsOn() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
            val (before, first) = tap(scenario, ::fastForward)
            assertNear("the first tap's target", before + 10_000, first)
            repeat(3) {
                delay(150)
                tap(scenario, ::fastForward)
            }
            val target = first + 30_000
            scenario.read { a ->
                assertEquals("sought before the window ended", emptyList<Long>(), seen.seeks.toList())
                assertEquals(target, playerView(a).player!!.currentPosition)
                assertEquals(time(target), a.findViewById<TextView>(androidx.media3.ui.R.id.exo_position).text.toString())
                assertEquals(time(target), a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).contentDescription.toString())
                assertEquals("+40 s", label(a))
            }
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(target), seen.seeks.toList())
            scenario.read { a -> assertTrue("playback went on from ${a.player!!.currentPosition}", a.player!!.currentPosition >= target) }
        }
    }

    @Test
    fun twoRewindsAndAFastForwardLandTenSecondsBack() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            scenario.onActivity { a -> a.player!!.seekTo(90_000) }
            awaitSeeks(seen, 1)
            waitFor("playback after the seek") { scenario.read { a -> a.player!!.isPlaying.takeIf { it } } }
            seen.seeks.clear()
            showControls(scenario)
            val (before, first) = tap(scenario, ::rewind)
            assertNear("the first tap's target", before - 10_000, first)
            delay(150)
            tap(scenario, ::rewind)
            delay(150)
            tap(scenario, ::fastForward)
            scenario.read { a -> assertEquals("−10 s", label(a)) }
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(first), seen.seeks.toList())
        }
    }

    @Test
    fun playOrPauseWhileASeekWaitsSeeksFirst() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
            val (_, first) = tap(scenario, ::fastForward)
            delay(150)
            val done = scenario.read { a ->
                fastForward(a).performClick()
                a.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick()
                seen.events.toList()
            }
            assertEquals(listOf("seek ${first + 10_000}", "pause"), done)
            delay(1_000)
            assertEquals(listOf(first + 10_000), seen.seeks.toList())
            scenario.read { a -> assertTrue(!a.player!!.playWhenReady) }
        }
    }

    /**
     * After the end, where media3's controls take Play for a replay from the start and drop
     * fast-forward: two rewinds, a fast-forward and a remote's ▶ at once go on 10 s before the end.
     * The player stays at the end only with something to move on to (EndOfPlay): here an episode
     * whose next one the card offers, waiting there with auto-play off.
     */
    @Test
    fun afterTheEndRewindsAndPlayGoOnBeforeTheEnd() = runBlocking<Unit> {
        offerTheNextEpisode()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch())).use { scenario ->
            val seen = awaitPlaying(scenario)
            scenario.onActivity { a -> a.player!!.seekTo(a.player!!.duration - 2_000) }
            waitFor("the end") { scenario.read { a -> a.player!!.takeIf { it.playbackState == Player.STATE_ENDED } } }
            seen.seeks.clear()
            showControls(scenario)
            // At once, all of it: well within the half second before a burst's seek.
            val (first, said) = scenario.read { a ->
                rewind(a).performClick()
                val first = playerView(a).player!!.currentPosition
                rewind(a).performClick()
                fastForward(a).performClick()
                listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { playerView(a).dispatchKeyEvent(KeyEvent(it, KeyEvent.KEYCODE_MEDIA_PLAY)) }
                first to label(a)
            }
            delay(1_000)
            assertEquals(listOf(first), seen.seeks.toList())
            assertEquals("−10 s", said)
            scenario.read { a ->
                val at = a.player!!.currentPosition
                assertTrue("playback is at $at, not on from $first", at in first..first + 5_000)
                assertTrue(a.player!!.playWhenReady)
            }
        }
    }

    /** Where a tap can't move (rewind at the start, fast-forward in the last second), the label says why, not "0 s". */
    @Test
    fun aTapThatCantMoveSaysWhy() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            scenario.onActivity { a ->
                a.player!!.pause()
                a.player!!.seekTo(0)
            }
            awaitSeeks(seen, 1)
            showControls(scenario)
            val atStart = scenario.read { a ->
                rewind(a).performClick()
                said(a)
            }
            assertEquals("At the start" to "At the start", atStart)
            // That burst over, seeking nowhere.
            delay(1_000)
            scenario.onActivity { a -> a.player!!.seekTo(a.player!!.duration - 500) }
            awaitSeeks(seen, 2)
            showControls(scenario)
            val atEnd = scenario.read { a ->
                fastForward(a).performClick()
                said(a)
            }
            assertEquals("At the end" to "At the end", atEnd)
            delay(1_000)
            assertEquals(2, seen.seeks.size)
        }
    }

    /** ⏩ on a remote, three times; then OK twice on the focused fast-forward button, as on a TV. */
    @Test
    fun aRemotesKeysAddUpToo() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            val first = scenario.read { a -> playerView(a).player!!.currentPosition }
            repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            assertEquals("sought before the window ended", emptyList<Long>(), seen.seeks.toList())
            awaitSeeks(seen, 1)
            delay(1_000)
            assertEquals(listOf(first + 20_000), seen.seeks.toList())

            // As on a TV: no touch mode (media keys leave it on), so the button can take the focus.
            instrumentation.setInTouchMode(false)
            showControls(scenario)
            scenario.onActivity { a -> fastForward(a).requestFocus() }
            waitFor("fast-forward to have the focus") { scenario.read { a -> fastForward(a).isFocused.takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val next = scenario.read { a -> playerView(a).player!!.currentPosition }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitSeeks(seen, 2)
            delay(1_000)
            assertEquals(listOf(first + 20_000, next + 10_000), seen.seeks.toList())
        }
    }

    /** Back with a seek waiting: the player goes without seeking, its stream left alone. */
    @Test
    fun backWhileASeekWaitsLeavesWithoutSeeking() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            val seen = awaitPlaying(scenario)
            showControls(scenario)
            tap(scenario, ::fastForward)
            scenario.onActivity { a -> backArrow(a).performClick() }
            waitFor("the player to close") { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            delay(1_000)
            assertEquals(emptyList<Long>(), seen.seeks.toList())
        }
    }

    /** Stopped with a seek waiting (the screen off, another app): the player made again waits paused where it was going (W106). */
    @Test
    fun aStopKeepsWhereTheSeekWasGoing() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            showControls(scenario)
            val (_, first) = tap(scenario, ::fastForward)
            scenario.onActivity { a -> fastForward(a).performClick() }
            val stopped = scenario.read { it.player }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val again = waitFor("the player made again", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it !== stopped && it.playbackState == Player.STATE_READY } } }
            scenario.read { a ->
                assertNotSame(stopped, a.player)
                assertTrue("paused after the stop", !again.playWhenReady)
                val at = again.currentPosition
                assertTrue("the new player is at $at, not at ${first + 10_000}", at in first + 10_000..first + 10_000 + 5_000)
            }
        }
    }

    // What the player does.

    /** The player's seeks, where they went, and its seeks and pauses in order. */
    private class Seen : Player.Listener {
        val seeks = CopyOnWriteArrayList<Long>()
        val events = CopyOnWriteArrayList<String>()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            seeks += newPosition.positionMs
            events += "seek ${newPosition.positionMs}"
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            events += if (playWhenReady) "play" else "pause"
        }
    }

    /** Waits until the file plays, then watches what its player does. */
    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>): Seen {
        waitFor("playback", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition > 0 } } }
        return Seen().also { seen -> scenario.onActivity { a -> a.player!!.addListener(seen) } }
    }

    private suspend fun awaitSeeks(seen: Seen, count: Int) {
        waitFor("$count seeks, not ${seen.seeks}", timeoutMs = 5_000) { seen.seeks.takeIf { it.size >= count } }
    }

    /** A tap on [button]: where playback was just before, and where the controls show it going after. */
    private fun tap(scenario: ActivityScenario<PlayerActivity>, button: (Activity) -> TextView): Pair<Long, Long> = scenario.read { a ->
        val shown = playerView(a).player!!
        val before = shown.currentPosition
        button(a).performClick()
        before to shown.currentPosition
    }

    private suspend fun showControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).showController() }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
    }

    private fun assertNear(what: String, expected: Long, actual: Long) {
        assertTrue("$what is $actual, not about $expected", abs(actual - expected) < 500)
    }

    /** How media3's controls write [ms]. */
    private fun time(ms: Long): String = StringBuilder().let { b -> Util.getStringForTime(b, Formatter(b, Locale.getDefault()), ms) }

    // Views.

    private fun rewind(activity: Activity): TextView = activity.findViewById(androidx.media3.ui.R.id.exo_rew_with_amount)

    private fun fastForward(activity: Activity): TextView = activity.findViewById(androidx.media3.ui.R.id.exo_ffwd_with_amount)

    private fun backArrow(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /** The label under the top bar (a polite live region), if it shows. */
    private fun labelView(activity: Activity) =
        views(activity.window.decorView).filterIsInstance<TextView>().singleOrNull { it.isShown && it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }

    /** What the label under the top bar says, if it shows. */
    private fun label(activity: Activity) = labelView(activity)?.text?.toString()

    /** What the label under the top bar says, and what TalkBack reads of it. */
    private fun said(activity: Activity) = labelView(activity)?.let { it.text.toString() to (it.contentDescription ?: it.text).toString() }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

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
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 900111
        const val SHOW_NAME = "Brine Hollow"

        val SECOND = EpisodePlay(
            showTmdbId = SHOW, showName = SHOW_NAME, showYear = 2026, aliases = emptyList(), ids = TvIds(tmdb = SHOW), season = 1, episode = 2,
            episodeName = "High Tide", airDate = null, runtimeMinutes = 6,
        )
    }
}

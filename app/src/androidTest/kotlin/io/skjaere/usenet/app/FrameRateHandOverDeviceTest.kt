package io.skjaere.usenet.app

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.DisplayModeSwitcher
import io.skjaere.usenet.app.play.DisplayPorts
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The TV's mode kept between two episodes (TV-7's W3 and L8), end to end on a phone emulator made a TV with a fake
 * display ([FakeDisplay]): Harbor Lights S01E01 found by the app's play sheet (MainActivity) from the fake indexer
 * (`mock-seed indexer`, 10.0.2.2:8089) and played from the mock NNTP server; near its end auto-advance hands S01E02
 * over. The episodes' frame rates and lengths are the test's ([PlayerActivity.videoOf]): the mock server's are short
 * clips. At the same rate the TV switches once, and no window gives the mode up between the episodes (the play sheet's
 * window holds it); at another, once more, straight from the first's; a next play that doesn't start lets it go, and so
 * does the play sheet's activity stopping. The next-episode seam stands in for TMDB; the app's indexers are swapped for
 * the fake one while a test runs.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FrameRateHandOverDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val home = HomeContentShown()
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realRead = PlayerActivity.readPlayback
    private val realTimings = PlayerActivity.timings
    private val realTv = PlayerActivity.isTelevision
    private val realVideo = PlayerActivity.videoOf
    private val realPort = DisplayPorts.of
    private val realLookUp = PlayerActivity.lookUpSegments
    private val display = FakeDisplay()
    /** Indexers that were on before the test; switched back on after it. */
    private var switchedOff = emptyList<String>()

    /** Each episode's frame rate, as the track probe would read it; two hours long. */
    private var rates = mapOf(1 to FILM_RATE, 2 to FILM_RATE)

    private fun episode(n: Int, season: Int = 1) = EpisodePlay(
        showTmdbId = SHOW, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = SHOW, imdb = "tt9000003"), season = season, episode = n,
        episodeName = "Episode $n", airDate = null, runtimeMinutes = 45,
    )

    @Before
    fun setUp() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        home.show()
        runBlocking {
            engineSetup("the mock on the engine") { mockOnEngine() }
            switchedOff = container.settings.indexers.first().filter { it.enabled && it.id != FAKE_INDEXER }.map { it.id }
            switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (frame-rate hand-over test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = true, matchFrameRate = true) }
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = 1_500, switching = SHORT)
        PlayerActivity.isTelevision = { true }
        PlayerActivity.videoOf = { launch -> rates[launch.watch.episode]?.let { VideoTiming(it, TWO_HOURS) } }
        DisplayPorts.of = display::port
    }

    @After
    fun restore() {
        // A player left open by a failure would be in front of the next test.
        instrumentation.runOnMainSync { players(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).forEach { it.finish() } }
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        PlayerActivity.timings = realTimings
        PlayerActivity.isTelevision = realTv
        PlayerActivity.videoOf = realVideo
        DisplayPorts.of = realPort
        container.playback.cancel()
        container.displayHold.clear("the test ended")
        runBlocking {
            container.settings.deleteIndexer(FAKE_INDEXER)
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
        }
        home.restore()
    }

    private fun main(): ActivityScenario<MainActivity> = ActivityScenario.launch(Intent(context, MainActivity::class.java))

    @Test
    fun twoEpisodesAtTheSameRateSwitchTheTvOnceAndHoldItBetween() {
        val since = seq()
        main().use { main ->
            val (first, second) = playOnToTheNext(main)
            assertEquals("the switches the TV made", listOf(FakeDisplay.FILM.id), display.switches.toList())
            assertEquals("the TV went back to its own mode between the episodes", 0, display.releases.get())
            assertTrue("the play sheet's window didn't hold the mode: ${display.writes}", ("MainActivity" to FakeDisplay.FILM.id) in display.writes)
            assertEquals(FakeDisplay.FILM.id, onMain { display.voteOf(second) })
            assertTrue(first.isDestroyed || first.isFinishing)
            // Without the transitions, during which a TV's display counts a window's mode for nothing (this one doesn't).
            assertTrue("the first player closed with its transition", onMain { first.closesWithoutTransition })
            assertTrue("the next player started with its transition", (second.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0)
            assertEquals("the first player, no mode held as it started, started without its transition", 0, first.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION)
            waitFor("the hold to go as the play sheet's activity stopped") { (container.displayHold.held.value == null).takeIf { it } }
            assertEquals(0, onMain { main.read { display.voteOf(it) } })
            assertEquals(0, display.releases.get())
        }
        val lines = logged(since)
        Log.i(TAG, "same rate: ${lines.joinToString(" / ")}")
        assertTrue(lines.toString(), "Frame rate: 23.976 fps; display 1920x1080 at 23.976 Hz: already fits" in lines)
        assertTrue(lines.toString(), "Display mode held for the next play: 1920x1080 at 23.976 Hz" in lines)
    }

    /** The next episode at 25 fps: one more switch, to 50 Hz, straight from the first episode's 23.976 Hz. */
    @Test
    fun aNextEpisodeAt25SwitchesOnceMoreTo50() {
        rates = mapOf(1 to FILM_RATE, 2 to 25.0)
        val since = seq()
        main().use { main ->
            val (_, second) = playOnToTheNext(main)
            assertEquals("the switches the TV made", listOf(FakeDisplay.FILM.id, FakeDisplay.FIFTY.id), display.switches.toList())
            assertEquals("the TV went back to its own mode between the episodes", 0, display.releases.get())
            assertEquals(FakeDisplay.FIFTY.id, onMain { display.voteOf(second) })
        }
        val lines = logged(since)
        Log.i(TAG, "another rate: ${lines.joinToString(" / ")}")
        assertTrue(lines.toString(), "Frame rate: 25 fps; display 1920x1080 at 23.976 Hz: switching to 50 Hz (mode 4)" in lines)
    }

    /** A next play that doesn't start (nothing found for it): the hold goes at once, and the TV back to its own mode. */
    @Test
    fun aNextPlayThatDoesNotStartLetsTheModeGo() {
        // An episode the fake indexer has nothing for, nor its season pack (season 1's).
        PlayerActivity.nextEpisode = { _, playing -> episode(9, season = 5).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        val since = seq()
        main().use { main ->
            container.playback.playEpisode(episode(1))
            val first = waitFor("S01E01's player", timeoutMs = 120_000) { onMain { players(Stage.RESUMED).firstOrNull()?.takeIf { it.player != null } } }
            waitFor("Next to be enabled", timeoutMs = 30_000) { onMain { first.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.takeIf { it.isEnabled } } }
            onMain { first.findViewById<View>(androidx.media3.ui.R.id.exo_next).performClick() }
            waitFor("S05E09's play to end without starting", timeoutMs = 60_000) {
                container.playback.state.value.takeIf { it is PlayState.NoRelease || it is PlayState.Failed }
            }
            waitFor("the hold to go") { (container.displayHold.held.value == null).takeIf { it } }
            waitFor("the play sheet's window to give the mode up") { onMain { main.read { display.voteOf(it) } }.takeIf { it == 0 } }
            assertEquals(Lifecycle.State.RESUMED, main.state)
            assertEquals(listOf(FakeDisplay.FILM.id), display.switches.toList())
            assertEquals(1, display.releases.get())
            assertEquals(FakeDisplay.SIXTY, display.active)
        }
        val lines = logged(since)
        assertTrue(lines.toString(), "Display mode hold released: the next play didn't start" in lines)
    }

    /** The play sheet's activity stopping (Home while it finds the next play) lets the mode go. */
    @Test
    fun thePlaySheetsActivityStoppingLetsTheModeGo() {
        main().use { main ->
            waitFor("the app") { main.state.takeIf { it == Lifecycle.State.RESUMED } }
            // As a player's switch left the display, and its window handed the mode over.
            onMain {
                display.run(FakeDisplay.FILM)
                container.displayHold.handOver(FakeDisplay.FILM)
            }
            waitFor("the play sheet's window to hold the mode") { onMain { main.read { display.voteOf(it) } }.takeIf { it == FakeDisplay.FILM.id } }
            waitFor("the TV to switch") { display.changedAt }
            main.moveToState(Lifecycle.State.CREATED)
            waitFor("the hold to go") { (container.displayHold.held.value == null).takeIf { it } }
            assertEquals(0, onMain { main.read { display.voteOf(it) } })
            assertEquals(1, display.releases.get())
            assertEquals(FakeDisplay.SIXTY, display.active)
            main.moveToState(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A mode handed over as the app went to the background (Home as the next episode started, #142): the play sheet's
     * activity stayed stopped, and the TV went back to its own mode. Back in the app, the play sheet doesn't ask the TV for
     * the mode it left, which would switch it with no player waiting; the hold goes.
     */
    @Test
    fun aHoldTheDisplayHasLeftIsLetGoAsThePlaySheetShows() {
        val since = seq()
        main().use { main ->
            waitFor("the app") { main.state.takeIf { it == Lifecycle.State.RESUMED } }
            main.moveToState(Lifecycle.State.CREATED)
            onMain { container.displayHold.handOver(FakeDisplay.FILM) }
            main.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(500)
            assertEquals("the switches the TV made", emptyList<Int>(), display.switches.toList())
            assertEquals(0, onMain { main.read { display.voteOf(it) } })
            waitFor("the hold to go") { (container.displayHold.held.value == null).takeIf { it } }
        }
        val lines = logged(since)
        assertTrue(lines.toString(), "Display mode hold released: the display runs another mode" in lines)
    }

    /** S01E01 played from the play sheet, near its end auto-advance to S01E02: the two players, once the second is made. */
    private fun playOnToTheNext(main: ActivityScenario<MainActivity>): Pair<PlayerActivity, PlayerActivity> {
        waitFor("the app") { main.state.takeIf { it == Lifecycle.State.RESUMED } }
        container.playback.playEpisode(episode(1))
        val first = waitFor("S01E01's player", timeoutMs = 120_000) { onMain { players(Stage.RESUMED).firstOrNull()?.takeIf { it.player?.duration?.let { d -> d > 0 } == true } } }
        assertEquals("S01E01's switch", listOf(FakeDisplay.FILM.id), display.switches.toList())
        onMain { first.player!!.seekTo(first.player!!.duration - 6_000) }
        val second = waitFor("S01E02's player", timeoutMs = 120_000) { onMain { players(Stage.RESUMED).firstOrNull { it !== first && it.player != null } } }
        return first to second
    }

    private fun players(vararg stages: Stage): List<PlayerActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>()
    }

    private fun seq() = LogBuffer.snapshot().lastOrNull()?.seq ?: 0

    /** The INFO lines of the player and the container (the hold's) since the log's line [since]. */
    private fun logged(since: Long) = LogBuffer.snapshot()
        .filter { it.seq > since && (it.tag == "PlayerActivity" || it.tag == "AppContainer") && it.level.toInt() >= org.slf4j.event.Level.INFO.toInt() }
        .map { it.message }

    private fun <T> ActivityScenario<MainActivity>.read(probe: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        const val TAG = "FrameRateHandOver"
        const val SHOW = 900002
        const val FAKE_INDEXER = "frame-rate-hand-over-fake"
        const val FILM_RATE = 24_000.0 / 1001
        const val TWO_HOURS = 2 * 60 * 60_000L

        /** The waits, short: the TV's change at most 1.5 s, 0.5 s to settle, 2.5 s in all. */
        val SHORT = DisplayModeSwitcher.Timings(beforeRequestMs = 200, changeMs = 1_500, settleMs = 500, capMs = 2_500, dolbyVisionCapMs = 4_000, pollMs = 50)
    }
}

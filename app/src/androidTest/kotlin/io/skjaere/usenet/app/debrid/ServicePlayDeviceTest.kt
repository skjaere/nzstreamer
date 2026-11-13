package io.skjaere.usenet.app.debrid

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.ReadAheadDataSource
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * DbPhonePlaySheet over the fakes (L189, [DebridSearchFixture]): Play Harbor Lights S01E02 with the Usenet indexer off plays
 * the release TorBox has cached through one Preparing step, then Opening and Checking audio and video, each with its time,
 * "Release" tagged TorBox; a mixed play of S01E01 goes from Usenet's broken PROPER to the PROPER TorBox has cached, the
 * sheet's steps Usenet's four, then the torrent's three; S01E03 plays from the cached season pack; and a play passing over
 * an account it remembers failed the torrent names the one it tries on every sheet. The plays are the
 * coordinator's, as Play's own pick makes them, up to the player's launch, which needs the player's HTTPS source and the
 * debrid fake's CDN to probe the file (items 30-32). S01E02 played on in the player reads through the read-ahead and rides
 * a silence of the CDN on what its source read ahead (W88); one the cushion can't cover is one stall, logged once (L238).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServicePlayDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixture = DebridSearchFixture()
    private val realLoadControl = PlayerActivity.loadControlFor

    /** Every sheet the coordinator showed, in order: collected as each is set, none passed over. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null

    @Before
    fun setUp() {
        fixture.open()
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch { container.playback.state.collect { seen += it } }
    }

    @After
    fun tearDown() {
        PlayerActivity.loadControlFor = realLoadControl
        watching?.cancel()
        onMainThread { container.playback.cancel() }
        fixture.close()
    }

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = DebridSearchFixture.EPISODES[n - 1], airDate = fixture.today.minusDays(60L - 7 * (n - 1)), runtimeMinutes = 45,
    )

    /** The coordinator's launch of what Play picked; fails the test if the sheet shows a failure instead. */
    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    private val running: List<PlayState.Running> get() = seen.filterIsInstance<PlayState.Running>()

    /** [state]'s rows had their times: each step done, Preparing and Opening taking some. */
    private fun assertTorrentStepsDone(state: PlayState.Running) {
        assertEquals(ServiceSource.STEPS, state.sourceSteps)
        ServiceSource.STEPS.forEach { step -> assertTrue("$step done: ${state.steps[step]}", state.steps[step] is StepStatus.Done) }
        listOf(PipelineStep.Prepare, PipelineStep.Open).forEach { step -> assertTrue("$step timed", (state.steps.getValue(step) as StepStatus.Done).millis > 0) }
        assertEquals("TorBox", state.tag?.text)
    }

    @Test
    fun aCachedTorrentPlaysThroughPreparingOpeningAndTheProbe() = runBlocking<Unit> {
        fixture.usenetIndexerOn(false)
        onMainThread { container.playback.playEpisode(episode(2)) }
        val launch = launched()
        assertTrue(launch.uri, launch.uri.startsWith("debrid://"))
        assertEquals(GOOD, launch.release.title)
        assertTorrentStepsDone(running.last())
        assertTrue("no Usenet step on a torrent's sheet", running.none { state -> state.steps.keys.any { it in UsenetSource.STEPS - ServiceSource.STEPS } })
        // Preparing was the first step seen running, by itself.
        assertEquals(PipelineStep.Prepare, running.first { it.steps.isNotEmpty() }.steps.keys.single())
    }

    @Test
    fun aMixedPlayGoesFromUsenetsBrokenProperToTheProperCachedOnTorBox() = runBlocking<Unit> {
        assumeTrue("the mock NNTP server isn't reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        engineSetup("the mock on the engine") { mockOnEngine() }
        onMainThread { container.playback.playEpisode(episode(1)) }
        val launch = launched()
        assertEquals(PROPER_TORRENT, launch.release.title)
        val first = running.first { it.attempt == 1 }
        assertEquals(BROKEN, first.release)
        assertEquals(UsenetSource.STEPS, first.sourceSteps)
        assertEquals("Usenet", first.tag?.text)
        val second = running.last()
        assertEquals(2, second.attempt)
        assertEquals(PROPER_TORRENT, second.release)
        assertTrue(second.notice.orEmpty(), second.notice.orEmpty().startsWith("Release incomplete · trying release 2 of "))
        assertTorrentStepsDone(second)
    }

    /**
     * L247, COT-2: Premiumize first in the order, both services having S01E02's GOOD cached, Premiumize remembered as no
     * longer having it: Play (not chosen) passes over Premiumize without a call, and every sheet the play shows names the
     * service it tries, TorBox, from the start, never Premiumize.
     */
    @Test
    fun aPlayPassingOverAnAccountItRemembersFailedNamesTheOneItTriesOnEverySheet() = runBlocking<Unit> {
        val premiumize = fixture.premiumizeFirst()
        fixture.usenetIndexerOn(false)
        val good = PlayCandidate("btih:$GOOD_HASH", GOOD, null, source = ReleaseFacts.TORRENT, routes = listOf(premiumize, fixture.torBox).map(UsableRoutes::serviceRoute))
        container.serviceMisses.record(good, premiumize, AttemptFailure.NoLongerCached)
        try {
            onMainThread { container.playback.playEpisode(episode(2)) }
            val launch = launched()
            assertEquals(GOOD, launch.release.title)
            assertEquals(listOf("TorBox"), running.filter { it.release == GOOD }.map { it.tag?.text }.distinct())
            assertTorrentStepsDone(running.last())
        } finally {
            container.serviceMisses.forget(good, premiumize)
        }
    }

    @Test
    fun anEpisodeOfACachedSeasonPackPlays() = runBlocking<Unit> {
        fixture.usenetIndexerOn(false)
        onMainThread { container.playback.playEpisode(episode(3)) }
        val launch = launched()
        assertEquals(PACK, launch.release.title)
        assertEquals("Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv", launch.release.fileName)
        assertTorrentStepsDone(running.last())
    }

    /**
     * W88, item 27: a debrid play rides a 4 s silence of the CDN on what its source read ahead, as a Usenet play rides one on
     * its streams' read-ahead. The fake's sample (90 s at 0.78 Mbps, 8.8 MB) would sit whole in Media3's buffer, whichever
     * source read it, so Media3's target is shrunk to 2 s (the loadControlFor seam) and the CDN paced just above the
     * sample's rate, so a body is still flowing when the silence comes, mid-play. Media3's loader asks the load control only
     * every 1 MiB, 10.7 s of this film, so its own buffer holds up to that much over the target, and a silence sent at any
     * moment could be ridden on it alone (FGX-1): the silence is sent once Media3 has stopped loading with 2.3 to 2.5 s
     * buffered. Riding 4 s then takes the bytes queued in the read-ahead, and the loader, paused until its buffer is under
     * 2 s, takes them during the silence, not before it. The player neither rebuffers nor logs a stall, and plays on through
     * it. On an emulator the network path from the host's fake holds megabytes unread (all of 2.4 MB, 25 s of the sample,
     * when measured), so a play through the plain source rides the silence too: the case keeps the read-ahead's path
     * through the player sound, and [io.skjaere.usenet.player.ReadAheadDataSource]'s own device test shows its queue
     * riding one.
     */
    @Test
    fun aPlayRidesAFourSecondSilenceOfTheCdnOnWhatItsSourceReadAhead() = runBlocking<Unit> {
        fixture.usenetIndexerOn(false)
        PlayerActivity.loadControlFor = { DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 2_000, 1_000, 1_000).build() }
        DebridFakes.control("cdn?slow=$PACE_KBPS")
        onMainThread { container.playback.playEpisode(episode(2)) }
        val launch = launched()
        assertEquals(GOOD, launch.release.title)
        val before = readers()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the episode to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            // L249, FGX-3: the player reads a debrid file through the read-ahead, whatever this case's network holds.
            assertTrue("the player reads ahead: no reader thread of its own", readers().any { it !in before })
            val duration = scenario.read { it.player!!.duration }
            assumeTrue("the debrid fake's video runs ${duration / 1000} s: start it with the README's 90 s mock video", duration >= MIN_VIDEO_MS)
            // Played on a while first: the source reads ahead what the CDN's pace gives over the film's rate.
            waitFor("the play to reach ${SILENCE_AT_MS / 1000} s", timeoutMs = 90_000) {
                scenario.read { a -> a.player?.takeIf { it.currentPosition >= SILENCE_AT_MS } }
            }
            val rebuffers = AtomicInteger()
            scenario.onActivity { a ->
                val p = a.player!!
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && p.playWhenReady) rebuffers.incrementAndGet()
                    }
                })
            }
            // Media3 paused at its target, the rest of what the CDN's pace gave in the read-ahead (FGX-1).
            val held = waitFor("Media3 to stop loading with 2.3 to 2.5 s buffered", timeoutMs = 40_000, everyMs = 40) {
                scenario.read { a -> a.player?.takeIf { !it.isLoading && it.totalBufferedDuration in HELD_MS }?.totalBufferedDuration }
            }
            val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
            val from = scenario.read { it.player!!.currentPosition }
            DebridFakes.control("cdn-dip?silent=$SILENCE_MS")
            // The silence, and as long again: a stall would have come and gone by then.
            delay(2 * SILENCE_MS)
            val to = scenario.read { it.player!!.currentPosition }
            val stalls = LogBuffer.snapshot().filter { it.seq > since && it.message.startsWith("player: stalled") }.map { it.message }
            assertEquals("no rebuffer through the silence, sent with $held ms in Media3's buffer", 0, rebuffers.get())
            assertEquals("no stall logged", emptyList<String>(), stalls)
            assertTrue("played on through it: from $from to $to ms", to - from >= 2 * SILENCE_MS - 1_000)
        }
    }

    /**
     * L238, item 24 (FGX-4): a silence of the CDN the cushion can't cover, short of the 10 s read time-out, is one stall,
     * logged once with its time and the most the player had buffered before it ("player: stalled N ms (X.X s buffered
     * before)"), and the play goes on after it, the reader carrying on from its long silent read. The player's own load
     * control and the CDN paced just above the film's rate: a few seconds in, the cushion is under 5 s.
     */
    @Test
    fun aSilenceTheCushionCantCoverIsOneStallLoggedOnceAndThePlayGoesOn() = runBlocking<Unit> {
        fixture.usenetIndexerOn(false)
        DebridFakes.control("cdn?slow=$PACE_KBPS")
        onMainThread { container.playback.playEpisode(episode(2)) }
        val launch = launched()
        assertEquals(GOOD, launch.release.title)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the episode to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            val duration = scenario.read { it.player!!.duration }
            assumeTrue("the debrid fake's video runs ${duration / 1000} s: start it with the README's 90 s mock video", duration >= MIN_VIDEO_MS)
            waitFor("the play to reach ${LONG_SILENCE_AT_MS / 1000} s") {
                scenario.read { a -> a.player?.takeIf { it.currentPosition >= LONG_SILENCE_AT_MS } }
            }
            val rebuffers = AtomicInteger()
            scenario.onActivity { a ->
                val p = a.player!!
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && p.playWhenReady) rebuffers.incrementAndGet()
                    }
                })
            }
            val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
            val from = scenario.read { it.player!!.currentPosition }
            DebridFakes.control("cdn-dip?silent=$LONG_SILENCE_MS")
            waitFor("the play to stall in the silence", timeoutMs = LONG_SILENCE_MS + 5_000) { rebuffers.get().takeIf { it > 0 } }
            waitFor("the play to go on after it", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            // On a while, as a second stall would come in the seconds after.
            delay(3_000)
            val to = scenario.read { it.player!!.currentPosition }
            val stalls = LogBuffer.snapshot().filter { it.seq > since && it.message.startsWith("player: stalled") }.map { it.message }
            assertEquals("one rebuffer", 1, rebuffers.get())
            val line = stalls.singleOrNull() ?: throw AssertionError("one stall logged: $stalls")
            val timed = Regex("""player: stalled (\d+) ms \(\d+\.\d s buffered before\)""").matchEntire(line) ?: throw AssertionError("the stall's line: $line")
            assertTrue("timed in seconds: $line", timed.groupValues[1].toLong() >= 2_000)
            assertTrue("played on after it: from $from to $to ms", to - from >= 3_000)
        }
    }

    /** The read-ahead's reader threads alive now: a debrid play's player reads through one ([ReadAheadDataSource]). */
    private fun readers(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name == ReadAheadDataSource.READER && it.isAlive }.toSet()

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, everyMs: Long = 100, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(everyMs)
        }
    }

    private companion object {
        /** The CDN's pace, kB/s: just above the fake's sample, 8.8 MB over 90 s (97.7 kB/s). */
        const val PACE_KBPS = 125

        /** Where in the film the CDN goes silent at the earliest, and for how long. */
        const val SILENCE_AT_MS = 25_000L
        const val SILENCE_MS = 4_000L

        /**
         * What Media3's buffer holds as the silence is sent, its loader paused: at most half a second over its 2 s target,
         * so riding 4 s takes the read-ahead's bytes, and over 2.3 s, so the loader stays paused until the silence has begun.
         */
        val HELD_MS = 2_300L..2_500L

        /** Where the silence the cushion can't cover comes, and how long it lasts: short of the source's 10 s read time-out. */
        const val LONG_SILENCE_AT_MS = 3_000L
        const val LONG_SILENCE_MS = 8_000L

        /** How long the fake's video must run for the silence and the play after it. */
        const val MIN_VIDEO_MS = 60_000L

        /** FakeTorrents' and FakeCatalog's names (tools/mock-seed), and GOOD_S01E02's hash. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val BROKEN = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"
        const val PROPER_TORRENT = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-TIDE"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"
    }
}

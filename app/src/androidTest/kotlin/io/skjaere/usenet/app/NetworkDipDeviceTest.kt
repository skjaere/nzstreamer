package io.skjaere.usenet.app

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stream slower than the film needs (W1, L7, QlPhoneDip): the player says so in its slow-stream banner, with what it
 * gets against what the film needs and a hint about Maximum bitrate, never taking a remote's focus; while it waits for
 * bytes Media3's spinner shows (W5); once the stream catches up the banner goes. With a release in the play's list
 * estimated well under the playing one, the banner offers Try a smaller release, which plays it from where this one was,
 * keeping this one as the release Resume plays until the smaller one does (L8). The release is dip.mkv (20 s at about
 * 840 kbps) posted on a server inside the test's process ([LocalNntp]) and launched straight into the player; the stream
 * is held to 0.4 of the film's bitrate under the player's reader ([ThrottledOpener]), and the detector's waits are
 * shortened. The film is invented ("Tidewater Signal", TMDB id 900026); its progress is put back after each test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NetworkDipDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realDataSources = PlayerActivity.dataSources
    private val realLoadControl = PlayerActivity.loadControlFor
    private val realTimings = PlayerActivity.timings
    private val realSource = PlaybackCoordinator.nzbSource
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Tidewater Signal", movieTmdbId = FILM))
    private val clip by lazy { fixtures.media("dip.mkv") }
    private val throttle = ThrottledOpener(container.opener)

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.dataSources = dataSourcesThrough(throttle)
        // A few seconds ahead, so a slow stream soon shows: Media3's own minimum is 50 s.
        PlayerActivity.loadControlFor = { DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 8_000, 500, 500).build() }
        PlayerActivity.timings = PlayerActivity.Timings(dip = DipDetector.Timings(graceMs = 1_000, windowMs = 3_000, fallMs = 500, recoverMs = 2_000, shownForMs = 1_000))
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
        // A remote's: the banner must never take its focus.
        instrumentation.setInTouchMode(false)
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.loadControlFor = realLoadControl
        PlayerActivity.timings = realTimings
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        local.close()
        runBlocking {
            container.library.recordProgress(template(), 0, 20_000)
        }
    }

    private fun template() = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Tidewater Signal", updatedAt = 0)

    /** A search's release [guid] titled [title], the ranker estimating it at [mbps]. */
    private fun sized(guid: String, title: String, mbps: Long) = fixtures.candidate(guid, title).let { it.copy(ranked = it.ranked.copy(estimatedBitrate = mbps * 1_000_000)) }

    /** The banner's words as TalkBack hears them, while it shows. */
    private fun bannerWords(a: PlayerActivity): String? = overlayWords(a).firstOrNull { it.startsWith(HEADLINE) }

    /**
     * The stream at 0.4 of the film's bitrate: the banner, its numbers in Mbps (what it gets under what the film needs)
     * and, as 0.8 of that is under the lowest choice, that even the lowest Maximum bitrate may stall; nothing else to try
     * (a launch with no list of releases), so no button. The spinner shows while playback waits for bytes. Neither takes
     * the focus. The stream as fast as it comes: the banner goes, and the film plays on.
     */
    @Test
    fun aSlowStreamShowsTheBannerAndTheSpinnerAndTheyGoOnceItCatchesUp() = runBlocking<Unit> {
        val release = fixtures.release("dip.mkv", clip, PART, "dip-plain", TITLE) { emptySet() }
        throttle.bytesPerSecond = clip.size * 4L / 10 / CLIP_SECONDS
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, GUID, 0))).use { scenario ->
            val said = waitFor("the slow-stream banner", timeoutMs = 40_000) { scenario.read(::bannerWords) }
            val numbers = Regex("$HEADLINE\\. ([\\d.]+) of ([\\d.]+) megabits per second\\. Even the lowest Maximum bitrate, \\d+ megabits per second, may stall at this speed\\.")
                .matchEntire(said)?.groupValues ?: throw AssertionError("the banner says: $said")
            assertTrue(said, numbers[1].toDouble() < numbers[2].toDouble())
            assertTrue(scenario.read { a -> "Try a smaller release" !in overlayWords(a) })
            waitFor("the spinner as playback waits for bytes", timeoutMs = 20_000) {
                scenario.read { a ->
                    val waiting = a.player?.let { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING } == true
                    true.takeIf { waiting && a.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).isVisible }
                }
            }
            assertTrue(scenario.read { a -> focusedWords(a).none { it.startsWith(HEADLINE) } })

            throttle.bytesPerSecond = null
            waitFor("the banner to go", timeoutMs = 20_000) { scenario.read { a -> true.takeIf { bannerWords(a) == null } } }
            waitFor("the film to play on") { scenario.read { a -> true.takeIf { a.player?.isPlaying == true } } }
            assertFalse(scenario.read { a -> "Playback stopped" in overlayWords(a) })
        }
    }

    /**
     * Try a smaller release (L8, QlPhoneSheet): the play's list has a release estimated at 2 Mbps beside the playing one's
     * 10, so the banner offers it. Pressed, the player closes, the sheet says it switches, and the smaller release plays
     * from where this one was, its NZB from the indexer. Meanwhile the playing one stays the release Resume plays, and
     * isn't remembered as missing.
     */
    @Test
    fun trySmallerReleaseOnTheBannerPlaysTheSmallerOneFromWhereThisOneWas() = runBlocking<Unit> {
        val big = fixtures.release("dip.mkv", clip, PART, "dip-big", TITLE) { emptySet() }
        val small = fixtures.release("dip.mkv", clip, PART, "dip-small", SMALL_TITLE) { emptySet() }
        val served = CopyOnWriteArrayList<String>()
        PlaybackCoordinator.nzbSource = { _, grab ->
            NzbSource { candidate ->
                served += candidate.id
                if (candidate.id == "$INDEXER/$SMALL") small.nzb else grab.fetch(candidate)
            }
        }
        // It played the film before: Resume plays it.
        container.library.recordProgress(template(), 1_000, 20_000, fixtures.saved(GUID, big.prepared).toRemembered())
        val launch = fixtures.launch(big.prepared, GUID, 0, listOf(sized(GUID, TITLE, 10), sized(SMALL, SMALL_TITLE, 2)))
        throttle.bytesPerSecond = clip.size * 4L / 10 / CLIP_SECONDS
        val resumesWhileTrying = CopyOnWriteArrayList<String?>()
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = launch {
            container.playback.state.collect { state ->
                states += state
                if (state is PlayState.Running && resumesWhileTrying.isEmpty()) resumesWhileTrying += container.library.lastRelease(WatchKey.movie(FILM))?.guid
            }
        }
        val position = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the banner with Try a smaller release", timeoutMs = 40_000) { scenario.read { a -> true.takeIf { bannerWords(a) != null && "Try a smaller release" in overlayWords(a) } } }
            assertTrue(scenario.read { a -> focusedWords(a).none { it == "Try a smaller release" } })
            val at = scenario.read { a -> a.player!!.currentPosition }
            scenario.onActivity { clickOverlay(it, "Try a smaller release") }
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            at
        }
        throttle.bytesPerSecond = null
        val next = nextLaunch()
        watching.cancel()
        assertEquals(SMALL, next.release.guid)
        val start = next.startPositionMs!!
        assertTrue("starts at $start, the play was at $position", start in position - 1_000..position + 3_000)
        assertEquals(listOf("$INDEXER/$SMALL"), served)
        assertEquals("Switching to a smaller release · it starts where you were", states.filterIsInstance<PlayState.Running>().first().notice)
        assertEquals(listOf<String?>(GUID), resumesWhileTrying.toList())
        // Not missing, nor forgotten: the smaller one takes Resume over as its own player saves.
        delay(1_000)
        assertEquals(GUID, container.library.lastRelease(WatchKey.movie(FILM))?.guid)
        assertTrue(container.library.triedReleases(listOf("$INDEXER/$GUID")).isEmpty())
    }

    /** The coordinator's next launch; fails the test if it shows a failure instead. */
    private suspend fun nextLaunch(): PlayerLaunch = withTimeout(60_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900026
        const val INDEXER = "network-dip-device-test"
        const val GUID = "tidewater-signal-remux"
        const val TITLE = "Tidewater.Signal.2026.1080p.BluRay.REMUX.AVC-DIP"
        const val SMALL = "tidewater-signal-web"
        const val SMALL_TITLE = "Tidewater.Signal.2026.720p.WEB.h264-DIP"
        const val HEADLINE = "Streaming is slower than this video needs"
        /** About a third of a second of dip.mkv. */
        const val PART = 32_000
        const val CLIP_SECONDS = 20
    }
}

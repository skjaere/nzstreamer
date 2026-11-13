package io.skjaere.usenet.app

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L33: a phone's play the player hands on stays in the phone's session through the coordinator's own retries: Try
 * another release, Try another for missing parts and Try a smaller release each start their job with the launch's
 * session, so the release they play carries it, from where the one before was. Called on the coordinator as the player
 * calls them, with no player in front, on a server inside the test's process ([LocalNntp]), no mock: the release that
 * plays is dip.mkv (20 s), its NZB served by the test. The film and the phone are invented ("Tidewater Signal", TMDB id
 * 900027); its progress and the releases found missing are put back after each test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RetrySessionDeviceTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Tidewater Signal", movieTmdbId = FILM))
    private val clip by lazy { fixtures.media("dip.mkv") }
    private val session = RemotePlay("s-retry", "ab".repeat(32), "Invented phone")
    private var tried: List<TriedRelease>? = null

    @Before
    fun setUp() {
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
            tried = container.library.triedReleaseEntries()
            container.library.clearTriedReleases()
        }
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
    }

    @After
    fun tearDown() {
        PlaybackCoordinator.nzbSource = realSource
        onMainThread { container.playback.cancel() }
        local.close()
        runBlocking {
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Tidewater Signal", updatedAt = 0), 0, 20_000)
            tried?.let { container.library.restoreTriedReleases(it) }
        }
    }

    /** A search's release [guid] titled [title], the ranker estimating it at [mbps]. */
    private fun sized(guid: String, title: String, mbps: Long) = fixtures.candidate(guid, title).let { it.copy(ranked = it.ranked.copy(estimatedBitrate = mbps * 1_000_000)) }

    /** The phone's play of the big release, the small one beside it in its request; the small one's NZB served when asked. */
    private suspend fun playing(): PlayerLaunch {
        val big = fixtures.release("dip.mkv", clip, PART, "retry-big", BIG_TITLE) { emptySet() }
        val small = fixtures.release("dip.mkv", clip, PART, "retry-small", SMALL_TITLE) { emptySet() }
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> if (candidate.id == "$INDEXER/$SMALL") small.nzb else grab.fetch(candidate) } }
        return fixtures.launch(big.prepared, BIG, 0, listOf(sized(BIG, BIG_TITLE, 10), sized(SMALL, SMALL_TITLE, 2))).copy(remote = session)
    }

    /** The coordinator's next launch; fails the test if it shows a failure instead. */
    private suspend fun nextLaunch(): PlayerLaunch = withTimeout(60_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    /** The release after [launch]'s, played in the phone's session, from where it was. */
    private fun assertInTheSession(next: PlayerLaunch) {
        assertEquals(SMALL, next.release.guid)
        assertEquals("the phone's session, handed on", session, next.remote)
        val start = next.startPositionMs ?: 0
        assertTrue("from where the one before was: $start", start in AT_MS - 1_000..AT_MS + 1_000)
    }

    @Test
    fun tryAnotherKeepsThePhonesSession() = runBlocking<Unit> {
        val launch = playing()
        onMainThread { container.playback.tryAnother(launch, AT_MS) }
        assertInTheSession(nextLaunch())
    }

    @Test
    fun tryAnotherForMissingPartsKeepsThePhonesSession() = runBlocking<Unit> {
        val launch = playing()
        onMainThread { container.playback.tryAnotherForMissingParts(launch, AT_MS, listOf(local.provider)) }
        assertInTheSession(nextLaunch())
    }

    @Test
    fun trySmallerKeepsThePhonesSession() = runBlocking<Unit> {
        val launch = playing()
        onMainThread { container.playback.trySmaller(launch, AT_MS, measuredBps = 4_000_000, playingBps = 10_000_000) }
        assertInTheSession(nextLaunch())
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900027
        const val INDEXER = "retry-session-device-test"
        const val BIG = "tidewater-signal-remux"
        const val BIG_TITLE = "Tidewater.Signal.2026.1080p.BluRay.REMUX.AVC-RETRY"
        const val SMALL = "tidewater-signal-web"
        const val SMALL_TITLE = "Tidewater.Signal.2026.720p.WEB.h264-RETRY"

        /** About a third of a second of dip.mkv. */
        const val PART = 32_000

        /** Where the play was as it was handed on. */
        const val AT_MS = 6_000L
    }
}

package io.skjaere.usenet.app

import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.TriedRelease
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
 * A release missing parts here and there: once three skipped spots lie within ten minutes of the film, the player asks
 * whether to try another release (W8). Keep watching asks again at six; Try another release plays the request's next
 * release from where this one was, keeping this one as the release that played, and not remembering it as missing, until
 * another plays (L9, L10); and when none does, the play goes back to it from there (W3). The releases are gaps-faststart.mp4
 * (a keyframe each second, a part about a second) posted on a server inside the test's process ([LocalNntp]), launched
 * straight into the player; the other release's NZB comes through the coordinator's seam. The film is invented ("Lantern
 * Field", TMDB id 900024); its progress and the releases found missing are put back after each test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class GapPromptDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realSource = PlaybackCoordinator.nzbSource
    private val realLoadControl = PlayerActivity.loadControlFor
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = FILM))
    private val mp4 by lazy { fixtures.media("gaps-faststart.mp4") }
    /** The NZBs the seam was asked for, by candidate id. */
    private val served = CopyOnWriteArrayList<String>()
    /** The releases found missing before the test, put back after it. */
    private var tried: List<TriedRelease>? = null

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
            tried = container.library.triedReleaseEntries()
            container.library.clearTriedReleases()
        }
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlaybackCoordinator.nzbSource = realSource
        PlayerActivity.loadControlFor = realLoadControl
        container.playback.cancel()
        local.close()
        runBlocking {
            // Out of Up next.
            container.library.recordProgress(template(), 0, DURATION_MS)
            tried?.let { container.library.restoreTriedReleases(it) }
        }
    }

    private fun template() = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Lantern Field", updatedAt = 0)

    /** The byte of gaps-faststart.mp4 [seconds] into the film: its samples run evenly through its mdat, after its index. */
    private fun atSecond(seconds: Int): Long {
        var at = 0
        while (true) {
            val size = (mp4[at].toInt() and 0xFF shl 24) or (mp4[at + 1].toInt() and 0xFF shl 16) or (mp4[at + 2].toInt() and 0xFF shl 8) or (mp4[at + 3].toInt() and 0xFF)
            if (String(mp4, at + 4, 4, Charsets.US_ASCII) == "mdat") return at + size.toLong() * seconds / DURATION_S
            at += size
        }
    }

    /** gaps-faststart.mp4 as the release [tag], its parts at [seconds] into the film missing. */
    private suspend fun release(tag: String, vararg seconds: Int) =
        fixtures.release("gaps-faststart.mp4", mp4, PART, tag, if (tag == GOOD) GOOD_TITLE else BROKEN_TITLE) { posted -> seconds.map { posted.idAt(atSecond(it)) }.toSet() }

    /** Waits for the prompt to say [count] while the play goes on; the player's position then. */
    private suspend fun ActivityScenario<PlayerActivity>.waitForPrompt(count: Int, buttons: Boolean = true): Long =
        waitFor("the prompt saying $count so far", timeoutMs = 40_000) {
            read { a ->
                val words = overlayWords(a)
                if ("Playback stopped" in words) throw AssertionError("the play stopped: $words")
                a.player?.takeIf { "This release is missing parts ($count so far)" in words && it.isPlaying }?.let {
                    if (buttons) assertTrue(words.toString(), "Try another release? It starts where you are." in words && "Keep watching" in words && "Try another release" in words)
                    it.currentPosition
                }
            }
        }

    /** A request of the broken release and the good one, the broken one playing; it played the film before, so Resume plays it. */
    private suspend fun brokenPlaying(broken: GapFixtures.Release, good: GapFixtures.Release): PlayerLaunch {
        container.library.recordProgress(template(), 1_000, DURATION_MS, fixtures.saved(BROKEN, broken.prepared).toRemembered())
        return fixtures.launch(broken.prepared, BROKEN, START_MS, listOf(fixtures.candidate(BROKEN, BROKEN_TITLE), fixtures.candidate(GOOD, GOOD_TITLE)))
    }

    /**
     * Three spots within a minute ask; Keep watching hides the prompt, and it asks again once the play has found six. The
     * player buffers a few seconds, so the parts are found as the play comes near them; the second three, after a seek.
     */
    @Test
    fun threeMissingPartsAskAndKeepWatchingAsksAgainAtSix() = runBlocking<Unit> {
        PlayerActivity.loadControlFor = { DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 3_000, 500, 500).build() }
        val release = release("prompt", 10, 13, 16, 40, 44, 48)
        // Another release to offer, so the prompt asks rather than says the count alone.
        val candidates = listOf(fixtures.candidate(BROKEN, BROKEN_TITLE), fixtures.candidate(GOOD, GOOD_TITLE))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, BROKEN, START_MS, candidates))).use { scenario ->
            scenario.waitForPrompt(3)
            scenario.onActivity { clickOverlay(it, "Keep watching") }
            waitFor("the prompt to hide") { scenario.read { a -> true.takeIf { "Keep watching" !in overlayWords(a) } } }
            scenario.onActivity { it.player?.seekTo(SEEK_MS) }
            scenario.waitForPrompt(6)
        }
    }

    /**
     * Try another release on the prompt: the player closes, the sheet says why, and the request's other release plays from
     * where this one was. Until it does, this one is still the release Resume plays; once it does, this one is remembered
     * as missing on the provider it was fetched from, and Resume no longer plays it.
     */
    @Test
    fun tryAnotherFromThePromptPlaysTheNextReleaseFromThereAndKeepsThisOneUntilItDoes() = runBlocking<Unit> {
        val broken = release(BROKEN, 10, 13, 16)
        val good = release(GOOD)
        PlaybackCoordinator.nzbSource = { _, grab ->
            NzbSource { candidate ->
                served += candidate.id
                if (candidate.id == "$INDEXER/$GOOD") good.nzb else grab.fetch(candidate)
            }
        }
        val launch = brokenPlaying(broken, good)
        // What Resume plays as the next release's first step runs: before it plays.
        val resumesWhileTrying = CopyOnWriteArrayList<String?>()
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = launch {
            container.playback.state.collect { state ->
                states += state
                if (state is PlayState.Running && resumesWhileTrying.isEmpty()) resumesWhileTrying += container.library.lastRelease(WatchKey.movie(FILM))?.guid
            }
        }
        val position = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val at = scenario.waitForPrompt(3)
            scenario.onActivity { clickOverlay(it, "Try another release") }
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            at
        }
        val next = nextLaunch()
        watching.cancel()
        assertEquals(GOOD, next.release.guid)
        val start = next.startPositionMs!!
        assertTrue("starts at $start, the play was at $position", start in position - 1_500..position + 2_000)
        assertEquals(listOf("$INDEXER/$GOOD"), served)
        val running = states.filterIsInstance<PlayState.Running>()
        assertEquals("The release before was missing parts · trying another", running.first().notice)
        assertEquals(listOf<String?>(BROKEN), resumesWhileTrying.toList())
        // Another plays: the broken one is missing on the provider in use, and no longer the release Resume plays.
        val remembered = withTimeout(5_000) {
            var entries = container.library.triedReleases(listOf("$INDEXER/$BROKEN"))
            while (entries.isEmpty()) {
                delay(100)
                entries = container.library.triedReleases(listOf("$INDEXER/$BROKEN"))
            }
            entries.values.single()
        }
        assertEquals(listOf(ReleaseChecks.provider("local", "127.0.0.1", local.provider.port)), remembered.missingOn.map { it.provider })
        withTimeout(5_000) { while (container.library.lastRelease(WatchKey.movie(FILM))?.guid == BROKEN) delay(100) }
    }

    /**
     * Try another release, and the other release doesn't play (its NZB can't be fetched): the play goes back to the one
     * that was playing, from where it was, its last try, from the NZB the store keeps, without the availability check.
     * It is still the release Resume plays, and isn't remembered as missing.
     */
    @Test
    fun withNoOtherReleasePlayingThePlayGoesBackToTheOneThatWasPlaying() = runBlocking<Unit> {
        val broken = release(BROKEN, 10, 13, 16)
        val good = release(GOOD)
        PlaybackCoordinator.nzbSource = { _, _ ->
            NzbSource { candidate ->
                served += candidate.id
                throw FetchException("The indexer has no NZB for it")
            }
        }
        val launch = brokenPlaying(broken, good)
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = launch { container.playback.state.collect { states += it } }
        val position = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val at = scenario.waitForPrompt(3)
            scenario.onActivity { clickOverlay(it, "Try another release") }
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            at
        }
        val next = nextLaunch()
        watching.cancel()
        assertEquals(BROKEN, next.release.guid)
        assertEquals(broken.prepared.id, next.release.nzbId)
        val start = next.startPositionMs!!
        assertTrue("starts at $start, the play was at $position", start in position - 1_500..position + 2_000)
        // The other's NZB was asked for; this one's came from the store.
        assertEquals(listOf("$INDEXER/$GOOD"), served)
        val back = states.filterIsInstance<PlayState.Running>().last { BROKEN_TITLE in it.release }
        assertTrue(back.notice ?: "", back.notice?.startsWith("No other release played · back to the ") == true && back.notice!!.endsWith(", from where you were"))
        assertEquals(StepStatus.Done(0, PlayPipeline.SKIPPED_PLAYING), back.steps[PipelineStep.CheckAvailability])
        assertEquals(2, back.attempt)
        // Still the release Resume plays, not missing.
        delay(1_000)
        assertEquals(BROKEN, container.library.lastRelease(WatchKey.movie(FILM))?.guid)
        assertFalse(container.library.triedReleases(listOf("$INDEXER/$BROKEN")).isNotEmpty())
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
        const val FILM = 900024
        const val INDEXER = "gap-prompt-device-test"
        const val BROKEN = "lantern-field-gaps"
        const val GOOD = "lantern-field-whole"
        const val BROKEN_TITLE = "Lantern.Field.2026.1080p.WEB.h264-GAPS"
        const val GOOD_TITLE = "Lantern.Field.2026.720p.WEB.h264-WHOLE"
        /** About a second of gaps-faststart.mp4. */
        const val PART = 2_000
        const val START_MS = 2_000L
        /** Short of the second three. */
        const val SEEK_MS = 32_000L
        const val DURATION_S = 60
        const val DURATION_MS = DURATION_S * 1_000L
    }
}

package io.skjaere.usenet.app

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.player.PlayGaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A release with an article missing on every provider mid-film plays through it: the stream fills it with zeros, the
 * reader stops in front of them, and the player goes on from the first point of the file's index after it, the moment
 * label saying "A missing part was skipped" as playback gets there. The files are the device tests' own media, posted
 * again in small parts on a server inside the test's process ([LocalNntp]) with a part's BODY answering 430, and launched
 * straight into the player, past the availability check. The film is invented ("Lantern Field", TMDB id 900023, which
 * nothing real has); its progress is put back to nothing after each test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class GapPlaybackDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = FILM))

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        runBlocking {
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        local.close()
        runBlocking {
            // Out of Up next.
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Lantern Field", updatedAt = 0), 0, DURATION_MS)
        }
    }

    /** How a play went past a missing part: the largest step its position took between two looks 100 ms apart while playing, the notice, the jumps made. */
    private class Played(val jump: Long, val noticeShown: Boolean, val jumps: List<PlayGaps.Jump>)

    /** Plays [launch] from its start until [until] ms while playing, failing on "Playback stopped". */
    private suspend fun playPast(launch: PlayerLaunch, until: Long): Played {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            var last = -1L
            var jump = 0L
            var notice = false
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (true) {
                val (position, playing, words) = scenario.read { a -> Triple(a.player?.currentPosition ?: -1, a.player?.isPlaying == true, overlayWords(a)) }
                if ("Playback stopped" in words) throw AssertionError("the play stopped: $words")
                scenario.read(::momentLabel)?.let { label ->
                    notice = true
                    // Said, never focused: the remote's keys stay the player's.
                    assertFalse("the notice took the focus", label.isFocused || label.isFocusable)
                }
                if (playing && last >= 0) jump = maxOf(jump, position - last)
                last = if (playing) position else -1
                if (playing && position > until) return Played(jump, notice, scenario.read { it.jumps.toList() })
                if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("stuck at $position ms, playing $playing")
                delay(100)
            }
        }
    }

    /** Where in [mkv] each Matroska cluster starts: its id, 1F 43 B6 75. */
    private fun clusters(mkv: ByteArray): List<Long> = (0..mkv.size - 4).filter { i ->
        mkv[i] == 0x1F.toByte() && mkv[i + 1] == 0x43.toByte() && mkv[i + 2] == 0xB6.toByte() && mkv[i + 3] == 0x75.toByte()
    }.map { it.toLong() }

    /**
     * A part missing just after a cluster's start, a minute in: playback goes on past it with a jump to the next cluster
     * (the next cue: about 5 s in this file), the notice says so, and no "Playback stopped".
     */
    @Test
    fun aMissingPartInAnMkvClusterIsSkippedWithTheNotice() = runBlocking<Unit> {
        val mkv = fixtures.media("chapters.mkv")
        val cluster = clusters(mkv)[13]
        val release = fixtures.release("chapters.mkv", mkv, PART, "gap-mkv", TITLE) { setOf(it.idAt(cluster + 100)) }
        // Well past the stretch: the cluster's 5 s and the next's.
        val played = playPast(fixtures.launch(release.prepared, "lantern-field-gaps", MKV_START_MS), until = MKV_START_MS + 20_000)
        assertTrue("the play never jumped: at most ${played.jump} ms between two looks 100 ms apart", played.jump >= 2_000)
        assertTrue("\"${PlayerActivity.MISSING_PART_SKIPPED}\" never showed", played.noticeShown)
    }

    /**
     * A part missing in the middle of an MP4's samples (a keyframe each second): playback goes on past it from the first
     * keyframe whose samples all come after it, a second or two on, and no "Playback stopped".
     */
    @Test
    fun aMissingPartInAnMp4ResumesAtTheFirstKeyframeAfterIt() = runBlocking<Unit> {
        val mp4 = fixtures.media("gaps-faststart.mp4")
        // Half way through, 30 s in.
        val at = mp4.size / 2L + 8_000
        val release = fixtures.release("gaps-faststart.mp4", mp4, PART, "gap-mp4", TITLE) { setOf(it.idAt(at)) }
        val gap = release.posted.bytesOf(release.posted.idAt(at))
        val played = playPast(fixtures.launch(release.prepared, "lantern-field-gaps", MP4_START_MS), until = MP4_START_MS + 12_000)
        val jump = played.jumps.single()
        assertEquals(gap, jump.bytes)
        val to = jump.toMs!!
        val from = jump.fromMs!!
        // This file's keyframes are each second, on the second.
        assertEquals("went on at $to ms", 0L, to % 1_000)
        assertTrue("went on at $to ms, from $from", from < to && to - from <= 3_000)
        assertTrue("the play never jumped: at most ${played.jump} ms between two looks 100 ms apart", played.jump >= 600)
        assertTrue("\"${PlayerActivity.MISSING_PART_SKIPPED}\" never showed", played.noticeShown)
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900023
        const val INDEXER = "gap-playback-device-test"
        const val TITLE = "Lantern.Field.2026.1080p.WEB.h264-GAPS"
        /** About a second of these files, a fifth of a cluster: a release's parts are 0.1 to 0.6 s of film. */
        const val PART = 2_000
        /** A few seconds in front of chapters.mkv's 14th cluster, about a minute in. */
        const val MKV_START_MS = 55_000L
        /** A few seconds in front of the middle of gaps-faststart.mp4's samples. */
        const val MP4_START_MS = 25_000L
        const val DURATION_MS = 360_000L
    }
}

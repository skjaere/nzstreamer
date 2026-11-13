package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.casting.CastGapWatch.Act
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cast gap watchdog (L104): zeros over a Matroska cluster's header, or seconds of them, hung the Shield in BUFFERING
 * (or PLAYING at a frame that never moved on) with no error, so only the phone, which served the gap, can tell. Stuck for
 * 8 s inside a served gap's window, the stream through it past the gap's end, the device is sent past it, 2 s and then
 * 10 s, twice at most; then the prompt says it is stuck, as it does in place of a seek into the film's last 3 s. Where
 * the watch sent it counts as the gap's too. A pause, the start, a short stall, a place outside the window, a gap not
 * served or not yet gone past, and the film's last 3 s (the stuck end's) don't count. The film is invented: 52 minutes
 * in a file of a thousand bytes a millisecond, so byte b is b / 1000 ms in.
 */
class CastGapWatchTest {
    private val length = 3_120_000L
    private val size = 3_120_000_000L

    /** A second's worth of missing articles 33:20 in: placed from 2,000,000 ms to 2,001,000 ms. */
    private val gap = 2_000_000_000L..2_000_999_999L
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, "hl.mkv", size), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 4_101, season = 2, episode = 4),
        SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", size),
        cast = CastTo("route:kitchen", "Kitchen display"),
    )

    /** The device says [state] at [reported] at [at], the server having sent [gaps] and gone on past [passed] of them. */
    private fun feed(
        state: CastPlayerState, reported: Long, at: Long, gaps: List<LongRange> = listOf(gap), passed: List<LongRange> = gaps, position: Long = reported,
        load: String = "load-1",
    ) = CastFeed(load, launch, state, position, length, reported = reported, reportedAt = at, size = size, gaps = gaps, passed = passed)

    /** The watch after the device played from the start, as a play does. */
    private fun watch() = CastGapWatch(CastTimings()).apply { assertNull(heard(feed(CastPlayerState.Playing, 0, 0), 0)) }

    /** The device says [state] at [reported] at [at], and nothing more until [until], the watch heard each second. */
    private fun CastGapWatch.stands(
        state: CastPlayerState, reported: Long, at: Long, until: Long, gaps: List<LongRange> = listOf(gap), passed: List<LongRange> = gaps,
    ): Act? {
        var acted: Act? = heard(feed(state, reported, at, gaps, passed), at)
        var t = at + 1_000
        while (acted == null && t <= until) {
            acted = heard(feed(state, reported, at, gaps, passed), t)
            t += 1_000
        }
        return acted
    }

    @Test
    fun `a served gap is placed by the file's share of the film, its window 5 s each side`() {
        assertEquals(2_000_000L..2_001_000L, CastGapWatch.placed(gap, size, length))
        assertEquals(1_995_000L..2_006_000L, CastGapWatch.window(gap, size, length, CastTimings()))
    }

    /** L104: the Shield stuck 0.4 s before one gap's time; the seek goes to the later of its end plus 2 s and the place plus 2 s. */
    @Test
    fun `buffering for 8 s inside a served gap's window, the server past its end, seeks past the gap`() {
        val watch = watch()
        assertNull(watch.stands(CastPlayerState.Buffering, 1_999_600, 100_000, 107_999), "7.999 s")
        assertEquals(Act.Seek(2_003_000), watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 100_000), 108_000))
    }

    /** The receiver's own position, never Media3's, which moves on by itself while the status says PLAYING. */
    @Test
    fun `PLAYING with the device's own position standing still counts, though the extrapolated one moves on`() {
        val watch = watch()
        var acted: Act? = null
        // The device's answers every 2 s: PLAYING, at the same frame; Media3's poll moving on meanwhile.
        for (t in 100_000L..108_000L step 2_000) {
            acted = watch.heard(feed(CastPlayerState.Playing, 2_000_200, t, position = 2_000_200 + (t - 100_000)), t)
            if (t < 108_000) assertNull(acted, "${t - 100_000} ms")
        }
        assertEquals(Act.Seek(2_003_000), acted)
        // One that moves on between its answers isn't stuck.
        val moving = watch()
        for (t in 200_000L..220_000L step 2_000) assertNull(moving.heard(feed(CastPlayerState.Playing, 1_996_000 + (t - 200_000), t), t))
        // Heard once and no more: an answer standing still is what tells, not the time since.
        val once = watch()
        assertNull(once.stands(CastPlayerState.Playing, 2_000_200, 300_000, 330_000))
    }

    @Test
    fun `outside the window, or before the stream through the gap went past it, it doesn't count`() {
        // 30 s after the gap's end estimate.
        assertNull(watch().stands(CastPlayerState.Buffering, 2_031_000, 100_000, 140_000))
        // 6 s before its start: the window opens 5 s before.
        assertNull(watch().stands(CastPlayerState.Buffering, 1_994_000, 100_000, 140_000))
        // The stream through the gap is still on it, or just at its end, whatever was read further on (Served.passed): the
        // device may simply wait for it.
        assertNull(watch().stands(CastPlayerState.Buffering, 2_000_500, 100_000, 140_000, passed = emptyList()))
        // A gap not served: nothing to pass.
        assertNull(watch().stands(CastPlayerState.Buffering, 2_000_500, 100_000, 140_000, gaps = emptyList()))
    }

    /**
     * The session down meanwhile (reconnecting), the device's words don't come: the time away doesn't count, nor its last
     * word from before it, only its words once the session is back.
     */
    @Test
    fun `time away doesn't count, only the device's words once the session is back`() {
        val watch = watch()
        assertNull(watch.stands(CastPlayerState.Buffering, 1_999_600, 100_000, 103_000))
        var t = 103_500L
        while (t <= 113_000) {
            watch.away(feed(CastPlayerState.Buffering, 1_999_600, 100_000), t)
            t += 500
        }
        // Back: its last word, from before, is all there is for a moment.
        assertNull(watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 100_000), 113_500), "13.5 s since that word")
        assertNull(watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 100_000), 115_000))
        // Its first word since: buffering still, there; 8 s of it count.
        assertNull(watch.stands(CastPlayerState.Buffering, 1_999_600, 115_200, 123_199), "7.999 s since it")
        assertEquals(Act.Seek(2_003_000), watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 115_200), 123_200))
    }

    @Test
    fun `a pause, 7 s of buffering and the start before PLAYING don't count`() {
        assertNull(watch().stands(CastPlayerState.Paused, 2_000_500, 100_000, 200_000), "paused at the gap")
        val short = watch()
        assertNull(short.stands(CastPlayerState.Buffering, 2_000_500, 100_000, 107_000))
        assertNull(short.heard(feed(CastPlayerState.Playing, 2_000_500, 107_100), 107_100), "playing again, from the same place")
        assertNull(short.heard(feed(CastPlayerState.Playing, 2_008_000, 115_000), 115_000), "and on")
        // A resume loading straight into a gap: before its first PLAYING, the load's own rules decide.
        val start = CastGapWatch(CastTimings())
        assertNull(start.stands(CastPlayerState.Loading, 2_000_500, 100_000, 200_000))
        assertNull(start.stands(CastPlayerState.Buffering, 2_000_500, 100_000, 200_000))
    }

    /** L104: never back, wherever in the window it stuck. */
    @Test
    fun `the seek never goes back`() {
        assertEquals(Act.Seek(2_007_500), watch().stands(CastPlayerState.Buffering, 2_005_500, 100_000, 110_000), "stuck past the gap's end estimate")
        assertEquals(Act.Seek(2_003_000), watch().stands(CastPlayerState.Buffering, 1_995_000, 100_000, 110_000), "stuck at the window's start")
    }

    /** L103: within 3 s of the end, the stuck end decides instead. */
    @Test
    fun `within 3 s of the end the stuck end decides`() {
        val last = (size - 2_000_000)..(size - 1_500_001)
        assertNull(watch().stands(CastPlayerState.Buffering, length - 2_000, 100_000, 140_000, gaps = listOf(last)))
        assertTrue(CastGapWatch.window(last, size, length, CastTimings()).contains(length - 2_000), "inside its window all the same")
    }

    /**
     * L104: at most twice per gap; still stuck, the prompt at once, saying the device is stuck at a missing part (L97). The
     * device resumes from the keyframe at or before where it is sent (item 6(b)), and releases have up to about 10 s
     * between keyframes, so the second seek goes 10 s past, not 2 s, beyond the window; stuck there, it is the same gap's.
     */
    @Test
    fun `a second time seeks again, a third asks at once, then nothing more for that gap`() {
        val watch = watch()
        assertEquals(Act.Seek(2_003_000), watch.stands(CastPlayerState.Buffering, 1_999_600, 100_000, 108_000))
        // Its earlier word after the seek says nothing: only the device's answers from then on.
        assertNull(watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 100_000), 120_000))
        assertEquals(Act.Seek(2_013_000), watch.stands(CastPlayerState.Buffering, 2_003_000, 108_500, 116_500))
        assertEquals(Act.Prompt, watch.stands(CastPlayerState.Buffering, 2_013_000, 117_000, 125_000))
        assertNull(watch.stands(CastPlayerState.Buffering, 2_013_000, 126_000, 200_000), "asked once")
        assertEquals("Kitchen display is stuck at a missing part.", stuckAtAMissingPart("Kitchen display"))
        assertEquals("Kitchen display may show glitches.", mayShowGlitches("Kitchen display"))
        // Another gap further on has its own two seeks.
        val later = 2_600_000_000L..2_600_499_999L
        assertEquals(
            Act.Seek(2_602_500),
            watch.stands(CastPlayerState.Buffering, 2_600_100, 300_000, 310_000, gaps = listOf(gap, later)),
        )
        assertTrue(CastGapWatch.window(later, size, length, CastTimings()).contains(2_600_100))
    }

    /**
     * L104: a seek from late in the window lands past its end, and the second goes further; the device stuck again where
     * the watch sent it is stuck at the same gap, though outside its window: that counts, and ends in the prompt after the
     * seeks. Further on than it was sent, or with no seek of the watch's there, outside the window still doesn't count.
     */
    @Test
    fun `stuck at its own seek target past the gap's window still counts, and ends in the prompt after the seeks`() {
        val watch = watch()
        // Stuck late in the window: sent 2 s on, past the window's end (2,006,000).
        assertEquals(Act.Seek(2_007_500), watch.stands(CastPlayerState.Buffering, 2_005_500, 100_000, 110_000))
        assertNull(watch.stands(CastPlayerState.Buffering, 2_009_000, 111_000, 130_000), "further on than it was sent")
        // Stuck where it was sent: the second seek, 10 s past.
        assertEquals(Act.Seek(2_017_500), watch.stands(CastPlayerState.Buffering, 2_007_500, 131_000, 140_000))
        // PLAYING there with its own position still, its answers every 2 s: the prompt.
        var acted: Act? = null
        for (t in 141_000L..149_000L step 2_000) acted = acted ?: watch.heard(feed(CastPlayerState.Playing, 2_017_500, t), t)
        assertEquals(Act.Prompt, acted)
        assertNull(watch().stands(CastPlayerState.Buffering, 2_007_500, 100_000, 140_000), "no seek of the watch's there")
    }

    /**
     * L104, L103: the watch leaves the film's last 3 s to the stuck end, which reads only PLAYING, so a device it sent there
     * and stuck buffering again would be left to nothing. A seek that would land there asks at once instead, as after the
     * seeks, and the watch says nothing more of that gap.
     */
    @Test
    fun `a seek that would land in the film's last 3 s asks at once instead`() {
        // A second's worth missing 15 s before the end: placed from 51:45 to 51:46, its window to 51:51.
        val credits = 3_105_000_000L..3_105_999_999L
        val watch = watch()
        assertEquals(Act.Seek(3_108_000), watch.stands(CastPlayerState.Buffering, 3_105_500, 100_000, 110_000, gaps = listOf(credits)))
        // Stuck where it was sent: 10 s on would be 2 s before the end.
        assertEquals(Act.Prompt, watch.stands(CastPlayerState.Buffering, 3_108_000, 111_000, 120_000, gaps = listOf(credits)))
        assertNull(watch.stands(CastPlayerState.Buffering, 3_108_000, 121_000, 200_000, gaps = listOf(credits)), "asked once")
        // The first seek too: stuck 4.5 s before the end, 2 s on would be 2.5 s before it; 5.001 s before, it is sent on.
        val later = 3_113_000_000L..3_113_999_999L
        assertEquals(Act.Prompt, watch().stands(CastPlayerState.Buffering, length - 4_500, 100_000, 110_000, gaps = listOf(later)))
        assertEquals(Act.Seek(length - 3_001), watch().stands(CastPlayerState.Buffering, length - 5_001, 100_000, 110_000, gaps = listOf(later)))
    }

    @Test
    fun `the next play's load starts afresh`() {
        val watch = watch()
        assertEquals(Act.Seek(2_003_000), watch.stands(CastPlayerState.Buffering, 1_999_600, 100_000, 108_000))
        assertEquals(Act.Seek(2_013_000), watch.stands(CastPlayerState.Buffering, 2_003_000, 108_500, 116_500))
        assertNull(watch.heard(feed(CastPlayerState.Playing, 0, 120_000, load = "load-2"), 120_000))
        var acted: Act? = null
        var t = 121_000L
        while (acted == null && t <= 140_000) {
            acted = watch.heard(feed(CastPlayerState.Buffering, 1_999_600, 121_000, load = "load-2"), t)
            t += 1_000
        }
        assertEquals(Act.Seek(2_003_000), acted, "its own two seeks")
    }
}

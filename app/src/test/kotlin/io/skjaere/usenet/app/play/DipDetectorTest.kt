package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The slow-stream detector (L7) on a film of [BITRATE], driven as the player drives it: loading's starts and stops as
 * Media3 reports them, and a tick every half second with the buffer ahead, the bytes the streams handed the player and
 * the position. Its own timings: 5 s after a start, a seek or a jump; a 10 s window; a 2 s fall; 5 s of growth to recover;
 * 4 s on screen at least; 5 min of playback between two, and 3 a play.
 */
class DipDetectorTest {
    private var clock = 1_000_000L
    private val detector = DipDetector(now = { clock })

    /** The film played and loaded so far, in ms of it, and the bytes the player read. */
    private var position = 0L
    private var loaded = 0L
    private var bytes = 0L

    /**
     * Plays on for [forMs] in half-second ticks, loading the film at [speed] times its playing speed (0: not loading), the
     * bytes read coming at [bytesSpeed] times its bitrate (as the film's own, unless a scene needs more than the average);
     * playback stalls while less than a tick of it is buffered. The last tick's dip.
     */
    private fun play(
        forMs: Long,
        speed: Double,
        bytesSpeed: Double = speed,
        playing: Boolean = true,
        covered: Boolean = false,
        bitrate: Long? = BITRATE,
    ): DipDetector.Dip? {
        var dip: DipDetector.Dip? = null
        repeat((forMs / TICK_MS).toInt()) {
            clock += TICK_MS
            loaded += (TICK_MS * speed).toLong()
            bytes += (TICK_MS * bytesSpeed * BITRATE / 8_000).toLong()
            val stalled = playing && loaded < position + TICK_MS
            if (playing) position = minOf(position + TICK_MS, loaded)
            dip = detector.tick(DipDetector.Sample(playing, stalled, loaded - position, position, bytes, bitrate, covered))
        }
        return dip
    }

    /** The film started, [aheadMs] of it loaded, loading still on. */
    private fun started(aheadMs: Long = 20_000) {
        loaded = aheadMs
        detector.loading(true)
        detector.firstFrame()
    }

    /** A dip shown since it began: the stream at 0.6 of what the film needs from the start, past the start's 5 s and a 10 s window. */
    private fun dipShowing(): DipDetector.Dip {
        started()
        return assertNotNull(play(15_500, speed = 0.6))
    }

    @Test
    fun `the buffer falling while loading runs on, read slower than the film's bitrate, is a dip at the rate read`() {
        started()
        assertNull(play(15_000, speed = 0.6), "within the start's 5 s and the 10 s window")
        assertEquals(DipDetector.Dip(rateBps = 4_800_000, bitrateBps = BITRATE), play(500, speed = 0.6))
        // Its numbers stay as they came, as the banner says them once.
        assertEquals(DipDetector.Dip(4_800_000, BITRATE), play(5_000, speed = 0.3))
    }

    /** Media3 has stopped loading (the buffer is full): nothing is read, so the rate is naught, but that is no dip. */
    @Test
    fun `a full buffer that only looks slow is no dip`() {
        started()
        detector.loading(false)
        assertNull(play(60_000, speed = 0.0))
    }

    /** A buffer held at its byte cap starts and stops loading every few hundred ms: loading never runs on for the window. */
    @Test
    fun `loading that keeps stopping is no dip`() {
        started()
        repeat(40) {
            detector.loading(true)
            assertNull(play(1_000, speed = 0.6))
            detector.loading(false)
            assertNull(play(500, speed = 0.0))
        }
    }

    @Test
    fun `nothing before the first frame, nor in the five seconds after it`() {
        loaded = 20_000
        detector.loading(true)
        assertNull(play(30_000, speed = 0.3, playing = true), "before the first frame")
        detector.firstFrame()
        assertNull(play(15_000, speed = 0.3))
        assertNotNull(play(500, speed = 0.3))
    }

    /** A seek opens the stream again, which starts slowly: the wait starts again, and a dip showing goes. */
    @Test
    fun `a seek starts the wait again, and ends a dip`() {
        started()
        assertNull(play(12_000, speed = 0.6))
        detector.reopened()
        assertNull(play(15_000, speed = 0.6), "the seek's 5 s and a new window")
        assertNotNull(play(500, speed = 0.6))
        detector.reopened()
        assertNull(play(500, speed = 0.6))
    }

    /** The player jumping past a missing part opens the stream again at the next keyframe, as a seek does. */
    @Test
    fun `a jump past a missing part starts the wait again`() {
        started()
        assertNull(play(14_000, speed = 0.6))
        // As the window would have ended.
        detector.reopened()
        assertNull(play(2_000, speed = 0.6))
        assertNull(play(13_000, speed = 0.6))
        assertNotNull(play(500, speed = 0.6))
    }

    /** Paused, the film doesn't play and the buffer fills: no dip, and one showing goes at once. */
    @Test
    fun `paused, no dip, and one showing goes at once`() {
        started()
        assertNull(play(30_000, speed = 0.6, playing = false))
        assertNull(play(15_500, speed = 0.6, playing = false))
        // 47.3 s ahead as it plays again: a dip once it is down to 20 s.
        assertNotNull(play(69_000, speed = 0.6))
        assertNull(play(500, speed = 0.6, playing = false))
    }

    /** It ends as Media3 stops loading (the buffer full again) or the buffer has grown 5 s, but not before it showed 4 s. */
    @Test
    fun `it ends once the buffer recovers, after showing long enough to be read`() {
        dipShowing()
        detector.loading(false)
        assertNotNull(play(3_500, speed = 0.0), "shown 3.5 s")
        assertNull(play(500, speed = 0.0))
    }

    @Test
    fun `it ends once the buffer has grown five seconds`() {
        dipShowing()
        assertNotNull(play(4_500, speed = 2.0), "the buffer grew 4.5 s")
        assertNull(play(500, speed = 2.0))
    }

    /**
     * Media3 loads again as soon as it holds less than 50 s, so loading runs on whenever the stream falls short, from a
     * full buffer too. A 10 s shortfall there would never have rebuffered: it counts only with 20 s or less ahead, where
     * loading used to start again, so the banner is as eager as it was.
     */
    @Test
    fun `a shortfall with more than 20 s buffered ahead is no dip`() {
        started(aheadMs = 45_000)
        assertNull(play(15_500, speed = 0.6), "45 s ahead at the start, 38.8 s at the window's end")
    }

    @Test
    fun `the same shortfall with 18 s buffered ahead is a dip`() {
        started(aheadMs = 18_000)
        assertEquals(DipDetector.Dip(rateBps = 4_800_000, bitrateBps = BITRATE), play(15_500, speed = 0.6))
    }

    /** A shortfall that goes on still shows, once it has brought the buffer down to 20 s. */
    @Test
    fun `a shortfall that goes on shows once the buffer is down to 20 s`() {
        started(aheadMs = 45_000)
        assertNull(play(62_000, speed = 0.6), "20.2 s ahead")
        assertEquals(DipDetector.Dip(rateBps = 4_800_000, bitrateBps = BITRATE), play(500, speed = 0.6), "20 s ahead")
    }

    /** Playback stalling for want of bytes (on a TV box's short cushion, the usual sign) counts as the buffer falling. */
    @Test
    fun `a stall while loading runs on counts as the buffer falling`() {
        loaded = 500
        detector.loading(true)
        detector.firstFrame()
        assertNull(play(15_000, speed = 0.4))
        assertEquals(DipDetector.Dip(3_200_000, BITRATE), play(500, speed = 0.4))
    }

    /** The buffer can fall in a scene that needs more than the film's average while the stream keeps up with that average. */
    @Test
    fun `read at the film's bitrate or faster is no dip`() {
        started()
        assertNull(play(30_000, speed = 0.6, bytesSpeed = 1.0))
        assertNull(play(30_000, speed = 0.6, bytesSpeed = 1.3))
    }

    @Test
    fun `without the film's bitrate there is no dip`() {
        started()
        assertNull(play(30_000, speed = 0.6, bitrate = null))
    }

    /** Nothing read while loading runs on is a provider not answering, not a slow stream: no "0 of 80 Mbps". */
    @Test
    fun `nothing read at all is no dip`() {
        started()
        assertNull(play(30_000, speed = 0.6, bytesSpeed = 0.0))
    }

    /** One banner a dip; the next only after five minutes of playback, and three a play at most. */
    @Test
    fun `after one dip the next waits five minutes of playback, and three a play at most`() {
        dipShowing()
        detector.loading(false)
        assertNull(play(5_000, speed = 0.0))
        detector.loading(true)
        // Four minutes in, still slow: no banner. Paused time doesn't count. (Loading slowly while paused, so the buffer
        // is down to 20 s again within those four minutes.)
        assertNull(play(60_000, speed = 0.3, playing = false))
        assertNull(play(240_000, speed = 1.0))
        assertNull(play(30_000, speed = 0.6))
        // Past five minutes of playback since it went.
        assertNull(play(30_000, speed = 1.0))
        assertNotNull(play(15_000, speed = 0.6))
        detector.loading(false)
        assertNull(play(5_000, speed = 0.0))
        detector.loading(true)
        assertNull(play(300_000, speed = 1.0))
        assertNotNull(play(15_000, speed = 0.6), "the third")
        detector.loading(false)
        assertNull(play(5_000, speed = 0.0))
        detector.loading(true)
        assertNull(play(300_000, speed = 1.0))
        assertNull(play(60_000, speed = 0.6), "a fourth")
    }

    /** Waiting for the slot (another overlay holds it) or under a side panel, a dip isn't seen: it doesn't count. */
    @Test
    fun `a dip never seen doesn't count`() {
        started()
        assertNotNull(play(15_500, speed = 0.6, covered = true))
        detector.loading(false)
        // Gone at once: there was nothing to read.
        assertNull(play(500, speed = 0.0, covered = true))
        detector.loading(true)
        assertNull(play(10_000, speed = 0.6))
        assertNotNull(play(500, speed = 0.6), "the next dip shows: the one before was never seen")
    }

    /** Covered for a while, still slow, the time it needs on screen runs only while it shows. */
    @Test
    fun `time covered isn't time shown`() {
        dipShowing()
        assertNotNull(play(10_000, speed = 0.6, covered = true))
        detector.loading(false)
        assertNotNull(play(3_000, speed = 0.0))
        assertNull(play(1_000, speed = 0.0))
    }

    /**
     * One that recovers while out of sight (under a side panel, or while another overlay has the slot) is over then: it
     * doesn't come back as the cover goes, its numbers old. It was seen, so it counts: the next waits its 5 minutes.
     */
    @Test
    fun `a dip that recovers while covered ends there, and counts`() {
        dipShowing()
        detector.loading(false)
        assertNull(play(500, speed = 0.0, covered = true))
        assertNull(play(5_000, speed = 0.0))
        detector.loading(true)
        assertNull(play(60_000, speed = 0.6), "within 5 minutes of the last")
    }

    private companion object {
        /** 8 Mbps: a million bytes a second. */
        const val BITRATE = 8_000_000L
        const val TICK_MS = 500L
    }
}

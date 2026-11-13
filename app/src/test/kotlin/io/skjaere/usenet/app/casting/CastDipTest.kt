package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The slow-stream banner's judge during a cast (L134; CsPhoneSlowCast), driven as Casting's counts drive it: once a second,
 * what the phone's server sent the device of a 52-minute film of 31 Mbps, how far past the device's position, the device's
 * state and position, and how long the server's answers waited on the providers and on the device. The player's own
 * detector judges it (5 s after the first PLAYING or a seek, a 10 s window, a 2 s fall, 5 s of growth to recover, 4 s on
 * screen at least, 5 minutes of playback between two, 3 a play), over a buffer made of the bytes sent past the position.
 * The device and the show are invented.
 */
class CastDipTest {
    private val length = 3_120_000L

    /** 31 Mbps over the 52 minutes: 3,875 bytes a millisecond of film. */
    private val size = 12_090_000_000L
    private val bitrate = 31_000_000L
    private val release = SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", size)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 4_101, season = 2, episode = 4), release,
        video = VideoTiming(fps = 24.0, durationMs = length), cast = CastTo("route:kitchen", "Kitchen display"),
    )

    private var dip = CastDip(CastTimings())

    /** When Casting counted, the device's place in the film, the film sent past it, and the counters so far. */
    private var at = 1_000_000L
    private var position = 1_200_000L
    private var aheadMs = 15_000L
    private var sent = 0L
    private var sourceWaitMs = 0L
    private var peerWaitMs = 0L
    private var gaps: List<LongRange> = emptyList()

    /** The feed as Casting counted it last, in [state]. */
    private fun feed(state: CastPlayerState) = CastFeed(
        "load-1", launch, state, position, length, size = size, gaps = gaps, sent = sent, ahead = aheadMs * size / length,
        sourceWaitMs = sourceWaitMs, peerWaitMs = peerWaitMs, countedAt = at,
    )

    /**
     * [seconds] of Casting's counts: the server sending [served] times what the film needs, the device in [state] (playing
     * on a second each while it plays), the film sent past it changing by [buffer] ms a second (what was sent, less what
     * played, unless said), the answers waiting [source] and [peer] milliseconds a second. The last count's dip.
     */
    private fun counts(
        seconds: Int,
        served: Double,
        state: CastPlayerState = CastPlayerState.Playing,
        buffer: Long? = null,
        source: Long = 600,
        peer: Long = 100,
        covered: Boolean = false,
        connected: Boolean = true,
    ): CastDip.Slow? {
        var slow: CastDip.Slow? = null
        repeat(seconds) {
            at += 1_000
            sent += (served * bitrate / 8).toLong()
            val played = if (state == CastPlayerState.Playing) 1_000L else 0L
            position += played
            aheadMs += buffer ?: ((served * 1_000).toLong() - played)
            sourceWaitMs += source
            peerWaitMs += peer
            slow = dip.heard(feed(state), connected, covered)
        }
        return slow
    }

    /** A dip shown since it began: the server at 0.6 of what the film needs from the first PLAYING, past the 5 s and a 10 s window. */
    private fun dipShowing(): CastDip.Slow = assertNotNull(counts(17, served = 0.6))

    @Test
    fun `the buffer of what was sent past the device falling 2 s over 10 s of sending, slower than the film needs, is a dip`() {
        assertNull(counts(16, served = 0.6), "within the first PLAYING's 5 s and the 10 s window")
        val slow = assertNotNull(counts(1, served = 0.6))
        assertEquals(DipDetector.Dip(rateBps = 18_600_000, bitrateBps = bitrate), slow.dip, "what reached the device over the window, against the file's bitrate")
        // Its numbers stay as they came, as the banner says them once.
        assertEquals(slow, counts(5, served = 0.3))
    }

    @Test
    fun `a buffer that holds is no dip, nor one that falls while more than the film needs reaches the device`() {
        assertNull(counts(60, served = 1.0))
        // A scene that needs more than the average: the buffer falls, but the stream keeps up with the film's bitrate.
        assertNull(counts(30, served = 1.05, buffer = -300))
    }

    /** More than 20 s of film sent past the device: a shortfall there would never stall; it shows once the buffer is under. */
    @Test
    fun `more than 20 s sent ahead is no dip yet`() {
        aheadMs = 40_000
        assertNull(counts(25, served = 0.6), "40 s ahead, falling 0.4 s a second: still over 20 s")
        assertNotNull(counts(30, served = 0.6), "under 20 s now")
    }

    @Test
    fun `the device buffering away from a missing part is a stall, inside a watched one's window it isn't`() {
        aheadMs = 2_000
        counts(8, served = 1.0)
        // Buffering for want of bytes, what was sent past it rising as nothing plays: no fall, but a stall.
        assertNotNull(counts(11, served = 0.6, state = CastPlayerState.Buffering))

        dip = CastDip(CastTimings())
        position = 1_200_000
        aheadMs = 2_000
        // A missing part served 10 s on, which the device stands at: the gap watch's (L104), not a slow stream.
        gaps = listOf(1_210_000 * 3_875L until 1_211_000 * 3_875L)
        counts(8, served = 1.0)
        assertNull(counts(30, served = 0.6, state = CastPlayerState.Buffering))
    }

    @Test
    fun `it ends as the buffer rises 5 s from its lowest, once it was shown long enough to read`() {
        dipShowing()
        // 1.5 times what it needs: half a second a second.
        assertNotNull(counts(9, served = 1.5), "4.5 s up")
        assertNull(counts(1, served = 1.5), "5 s up")
    }

    @Test
    fun `a seek restarts the grace and the window`() {
        assertNull(counts(14, served = 0.6), "three seconds short of a dip")
        // The device's place jumps 60 s on: where it is isn't where it would be.
        position += 60_000
        assertNull(counts(15, served = 0.6), "the grace again, then the window")
        assertNotNull(counts(2, served = 0.6))
        // Back 30 s, while it shows: it goes at once.
        position -= 30_000
        assertNull(counts(1, served = 0.6))
    }

    @Test
    fun `paused, reconnecting or the device idle, it goes at once`() {
        dipShowing()
        assertNull(counts(1, served = 0.6, state = CastPlayerState.Paused), "paused")
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        dipShowing()
        assertNull(counts(1, served = 0.6, connected = false), "the session suspended")
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        dipShowing()
        assertNull(counts(1, served = 0.6, state = CastPlayerState.Idle))
    }

    @Test
    fun `nothing before the device first plays, and nothing sent is no dip`() {
        assertNull(counts(30, served = 0.6, state = CastPlayerState.Loading), "loading: not yet played")
        assertNull(counts(30, served = 0.0), "nothing reached it: the providers not answering, not a slow stream")
    }

    /** Covered by the missing-parts prompt or out of sight: still found, its time on screen not counted (W37). */
    @Test
    fun `under the missing-parts prompt it waits, its time on screen not counted`() {
        val slow = assertNotNull(counts(17, served = 0.6, covered = true))
        // Risen 5 s while covered: it ends then, unseen.
        assertEquals(slow, counts(9, served = 1.5, covered = true))
        assertNull(counts(1, served = 1.5, covered = true))
    }

    @Test
    fun `at most 3 a play, the playback between two waited out`() {
        dip = CastDip(CastTimings(dip = DipDetector.Timings(quietMs = 20_000)))
        repeat(3) { n ->
            assertNotNull(counts(if (n == 0) 17 else 20, served = 0.6), "dip ${n + 1}")
            assertNull(counts(14, served = 1.5), "dip ${n + 1} recovered")
            aheadMs = 15_000
        }
        assertNull(counts(120, served = 0.6), "no fourth")
    }

    /**
     * CFS-1: a healthy cast in a quieter scene. The device keeps its read-ahead full, so it takes what its scene needs, under
     * the film's average, and holds the server's writes meanwhile; the buffer of what was sent past it, counted at the
     * average, falls all the same. That is no dip: a fall shows only once the server waited longer on the providers than on
     * the device over its window, the device then wanting what didn't come. A stall shows whichever waited.
     */
    @Test
    fun `the buffer falling while the device holds the server and never stands is no dip`() {
        assertNull(counts(60, served = 0.5, buffer = -500, source = 0, peer = 900), "a quieter scene, the device full")
        assertNull(counts(30, served = 0.7, buffer = -300, source = 100, peer = 600))
        // The providers short, the server waiting on them: the same fall is a dip.
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        assertEquals(CastDip.Cause.Providers, counts(17, served = 0.6, source = 600, peer = 100)?.cause)
    }

    /**
     * L134: over the dip's window, the device taking what was sent slower than the providers gave it is the Wi-Fi's: the
     * server held in its writes, the device wanting what can't reach it, until it stands buffering (CFS-1: before that, the
     * same as a device whose read-ahead is full). Then the dip shows, with what reached it over the window by then.
     */
    @Test
    fun `more wait on the device than on the providers is the Wi-Fi, else the providers`() {
        assertNull(counts(16, served = 0.6, source = 50, peer = 600), "held in its writes, the device not yet short")
        assertEquals(
            CastDip.Slow(DipDetector.Dip(rateBps = 18_600_000, bitrateBps = bitrate), CastDip.Cause.WiFi),
            counts(1, served = 0.6, state = CastPlayerState.Buffering, source = 50, peer = 600),
        )
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        assertEquals(CastDip.Cause.Providers, counts(17, served = 0.6, source = 600, peer = 50)?.cause)
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        assertEquals(CastDip.Cause.Providers, counts(17, served = 0.6, source = 300, peer = 300)?.cause, "as much on each: the providers")
        // Only the window counts: a minute of waits on the device before it says nothing of a dip on the providers.
        dip = CastDip(CastTimings())
        aheadMs = 15_000
        assertNull(counts(60, served = 1.0, source = 0, peer = 900))
        assertNull(counts(12, served = 1.0, source = 600, peer = 100))
        assertEquals(CastDip.Cause.Providers, counts(10, served = 0.6, source = 600, peer = 100)?.cause)
    }

    /** L136: the dips a play had, for its end's log line: each counted once, seen or not, never one that wasn't found. */
    @Test
    fun `the dips found are counted, each once`() {
        assertEquals(0, dip.found)
        counts(60, served = 1.0)
        assertEquals(0, dip.found, "a buffer that holds")
        aheadMs = 15_000
        assertNotNull(counts(17, served = 0.6, covered = true))
        assertEquals(1, dip.found, "found under the missing-parts prompt")
        counts(5, served = 0.3, covered = true)
        assertEquals(1, dip.found, "the same dip")
        dip = CastDip(CastTimings(dip = DipDetector.Timings(quietMs = 20_000)))
        aheadMs = 15_000
        repeat(3) { n ->
            counts(if (n == 0) 17 else 20, served = 0.6)
            counts(14, served = 1.5)
            aheadMs = 15_000
        }
        counts(120, served = 0.6)
        assertEquals(3, dip.found, "3 a play")
    }

    /** Try a smaller release answers the dip: it shows no more. */
    @Test
    fun `a dip answered shows no more`() {
        dipShowing()
        dip.answered()
        assertNull(counts(1, served = 0.6))
        assertNull(counts(10, served = 0.6))
    }

    /** L134, item 48's note: what was sent past a byte, through answers that carry on where another stopped. */
    @Test
    fun `what was sent past the device's byte, chained through answers that carry on from it`() {
        // Nothing sent past it.
        assertEquals(0, CastDip.sentPast(5_000) { null })
        // One answer from 0 that wrote to 9,000.
        assertEquals(4_000, CastDip.sentPast(5_000, answersOf(0L..8_999L)))
        // The device read the index at the end, then went on from 9,000 in an answer of its own, to 20,000.
        assertEquals(15_000, CastDip.sentPast(5_000, answersOf(0L..8_999L, 9_000L..19_999L)))
        // An index read further on, not reached: it says nothing of the bytes before it.
        assertEquals(4_000, CastDip.sentPast(5_000, answersOf(0L..8_999L, 50_000L..59_999L)))
        // CFS-3: a seek back. The answer from before it (0 to 9,000) stays the server's, but the device dropped what it sent:
        // the device has only what the new one from 3,000 sent, to 5,000, not on into the old one.
        assertEquals(1_000, CastDip.sentPast(4_000, answersOf(0L..8_999L, 3_000L..4_999L)))
        // Where the old answer still covers the byte alone, the device is past where the new one went.
        assertEquals(3_000, CastDip.sentPast(6_000, answersOf(0L..8_999L, 3_000L..4_999L)))
    }

    @Test
    fun `the device's byte by its share of the film, none while the film can't be placed`() {
        position = length / 4
        val f = feed(CastPlayerState.Playing)
        val asked = mutableListOf<Long>()
        assertEquals(1_000_000, CastDip.ahead(f) { byte -> (byte until byte + 1_000_000).takeIf { asked.isEmpty() }.also { asked += byte } })
        assertEquals(listOf(size / 4, size / 4 + 1_000_000), asked, "a quarter of the film is a quarter of the file, then on from where that answer stopped")
        assertEquals(
            1_000_000, CastDip.ahead(f.copy(durationMs = null)) { byte -> (byte until byte + 1_000_000).takeIf { byte == size / 4 } }, "the probe's length while the device says none",
        )
        assertNull(CastDip.ahead(f.copy(size = null)) { 0L..0L })
        assertNull(CastDip.ahead(f.copy(durationMs = null, launch = launch.copy(video = null))) { 0L..0L })
    }

    /**
     * CFT-2: what Casting counts each second goes into the play's feed as the server counted it: the bytes that reached the
     * device, the providers' wait and the device's each as its own, the count's time, and what was sent past where the
     * device plays now.
     */
    @Test
    fun `each count goes into the feed as the server counted it`() {
        position = length / 4
        val counters = ServeCounters(bytesSent = 7_000_000, sourceWaitMillis = 3_000, peerWaitMillis = 41_000)
        // One answer from the file's start, 2,000,000 bytes past a quarter of it.
        val answered = answersOf(0L until size / 4 + 2_000_000)
        val counted = feed(CastPlayerState.Playing).copy(sent = 0, sourceWaitMs = 0, peerWaitMs = 0, countedAt = null).counted(counters, 1_234_567, answered)
        assertEquals(7_000_000, counted.sent)
        assertEquals(3_000, counted.sourceWaitMs, "the providers' wait")
        assertEquals(41_000, counted.peerWaitMs, "the device's wait")
        assertEquals(1_234_567L, counted.countedAt)
        assertEquals(2_000_000L, counted.ahead)
        // 258 ms of film further on (999,750 bytes): that much less sent past it.
        assertEquals(1_000_250L, feed(CastPlayerState.Playing).copy(positionMs = length / 4 + 258).counted(counters, 1_235_567, answered).ahead)
    }

    /** The bytes the latest of [answers] (in the order they began) covering a byte wrote: as Served.answered says it. */
    private fun answersOf(vararg answers: LongRange): (Long) -> LongRange? = { byte -> answers.lastOrNull { byte in it } }
}

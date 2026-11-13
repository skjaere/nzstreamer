package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.casting.CastingRules.Act
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchSave
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L61: a cast play's progress is saved as a phone play's, with its release, from the feed that follows it and a clock:
 * every 15 s while it plays, as it pauses, and as it ends there, where the end says (a FINISHED near the end is watched,
 * one well before it a stop there, L60). The film and its release are invented.
 */
class CastProgressTest {
    /** The film is 52 minutes long; the probe read 51:59 of it. */
    private val length = 3_120_000L
    private val probed = 3_119_000L
    private val release = SavedRelease("fake", "copper-crown", "Copper.Crown.2026.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-cc", "cc.mkv", 2_000_000_000)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-cc", 0, release.fileName, release.fileSize), "Copper Crown", "2026", WatchKey.movie(900010),
        WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), release, video = VideoTiming(fps = 24.0, durationMs = probed),
    )
    private val saved = mutableListOf<WatchSave>()
    private val progress = CastProgress(saved::add, CastTimings())

    private fun feed(state: CastPlayerState, positionMs: Long, durationMs: Long? = length, end: CastEnd? = null, of: PlayerLaunch = launch) =
        CastFeed("load-1", of, state, positionMs, durationMs, end = end)

    private fun playing(at: Long, positionMs: Long) = progress.heard(feed(CastPlayerState.Playing, positionMs), at)

    /** The end [CastingRules] makes of the device's FINISHED with the play at [positionMs] of [film]. */
    private fun finished(positionMs: Long, film: Long): CastEnd {
        val served = ServeCounters(requests = 1, partials = 1, firstRequestAt = 1)
        val rules = CastingRules("Kitchen display", "load-1", CastFile("mkv", video = true), deviceHasStatus = false, loadedAt = 0, probedDurationMs = film)
        rules.heard(CastEvent.Status(CastPlayerState.Playing, null, "load-1", 0, 0), served, 0)
        rules.tick(served, 1_000, positionMs, film)
        return rules.over(Why.Finished, served, 2_000).filterIsInstance<Act.Ended>().single().end
    }

    /** L147: a file Import opened, cast, saves nothing: not as it plays, pauses or ends, as its play here saves nothing. */
    @Test
    fun `a file Import opened saves nothing as it plays, pauses or ends`() {
        val imported = launch.copy(imported = true)
        for (second in 0..31) progress.heard(feed(CastPlayerState.Playing, 1_900_000 + second * 1_000L, of = imported), second * 1_000L)
        progress.heard(feed(CastPlayerState.Paused, 1_935_000, of = imported), 33_000)
        progress.heard(feed(CastPlayerState.Idle, length - 90_000, end = finished(length - 90_000, length), of = imported), 40_000)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `every 15 s while it plays`() {
        for (second in 0..31) playing(second * 1_000L, 1_900_000 + second * 1_000L)
        assertEquals(listOf(1_915_000L, 1_930_000L), saved.map { it.positionMs })
        assertEquals(listOf(length, length), saved.map { it.durationMs })
        assertEquals(release.toRemembered(), saved.first().release)
        assertEquals(WatchKey.movie(900010), saved.first().template.key)
    }

    @Test
    fun `a pause saves where it is, once`() {
        playing(0, 1_920_000)
        progress.heard(feed(CastPlayerState.Paused, 1_930_000), 5_000)
        progress.heard(feed(CastPlayerState.Paused, 1_930_000), 6_000)
        assertEquals(listOf(1_930_000L), saved.map { it.positionMs })
        // Playing again, then paused again: saved again.
        playing(7_000, 1_930_000)
        progress.heard(feed(CastPlayerState.Paused, 1_935_000), 12_000)
        assertEquals(listOf(1_930_000L, 1_935_000L), saved.map { it.positionMs })
    }

    @Test
    fun `a FINISHED within 2 minutes of the end saves the length`() {
        playing(0, length - 90_000)
        progress.heard(feed(CastPlayerState.Idle, length - 90_000, end = finished(length - 90_000, length)), 2_000)
        assertEquals(listOf(length to length), saved.map { it.positionMs to it.durationMs })
    }

    /** L60: recordProgress's 90 % rule then decides whether it is watched, and the line says where it stopped. */
    @Test
    fun `in a 2-hour film a FINISHED 10 minutes early saves a stop at 110 minutes, one 30 minutes early where it was`() {
        val film = 7_200_000L
        val tenEarly = finished(6_600_000, film)
        assertEquals(CastEnd(CastEnd.How.Stopped, 6_600_000), tenEarly)
        progress.heard(feed(CastPlayerState.Idle, 6_600_000, durationMs = film, end = tenEarly), 2_000)
        assertEquals(listOf(6_600_000L to film), saved.map { it.positionMs to it.durationMs })

        val other = mutableListOf<WatchSave>()
        CastProgress(other::add, CastTimings()).heard(feed(CastPlayerState.Idle, 5_400_000, durationMs = film, end = finished(5_400_000, film)), 2_000)
        assertEquals(listOf(5_400_000L to film), other.map { it.positionMs to it.durationMs })
    }

    /** Media3 1.11.1 clears the timeline at the queue's end: the length heard before it, else the probe's. */
    @Test
    fun `a timeline cleared at the end saves with the last length known, else the probe's`() {
        playing(0, 1_000_000)
        progress.heard(feed(CastPlayerState.Idle, 1_200_000, durationMs = null, end = CastEnd(CastEnd.How.Stopped, 1_200_000)), 2_000)
        assertEquals(listOf(1_200_000L to length), saved.map { it.positionMs to it.durationMs })

        val other = mutableListOf<WatchSave>()
        val never = CastProgress(other::add, CastTimings())
        never.heard(feed(CastPlayerState.Playing, 1_000_000, durationMs = null), 0)
        never.heard(feed(CastPlayerState.Paused, 1_000_000, durationMs = null), 1_000)
        assertEquals(listOf(1_000_000L to probed), other.map { it.positionMs to it.durationMs })
    }

    /** L88: the providers failed it there: where it was is saved, and Resume no longer plays that release. */
    @Test
    fun `an upstream failure leaves the release out`() {
        playing(0, 600_000)
        progress.heard(feed(CastPlayerState.Idle, 600_000, end = CastEnd(CastEnd.How.Failed, 600_000, providers = true)), 2_000)
        assertNull(saved.single().release)

        val other = mutableListOf<WatchSave>()
        CastProgress(other::add, CastTimings()).heard(feed(CastPlayerState.Idle, 600_000, end = CastEnd(CastEnd.How.Failed, 600_000)), 2_000)
        assertEquals(release.toRemembered(), other.single().release)
    }

    /** L84: the next play's load took its place there: saved then. L60: a long pause's end too. */
    @Test
    fun `each end saves where it was, once, and nothing after`() {
        playing(0, 900_000)
        progress.heard(feed(CastPlayerState.Playing, 900_000, end = CastEnd(CastEnd.How.Replaced, 900_000)), 1_000)
        progress.heard(feed(CastPlayerState.Playing, 950_000, end = CastEnd(CastEnd.How.Replaced, 900_000)), 2_000)
        playing(40_000, 960_000)
        assertEquals(listOf(900_000L), saved.map { it.positionMs })

        val other = mutableListOf<WatchSave>()
        CastProgress(other::add, CastTimings()).heard(feed(CastPlayerState.Paused, 1_930_000, end = CastEnd(CastEnd.How.LongPause, 1_930_000)), 0)
        assertEquals(listOf(1_930_000L), other.map { it.positionMs })
    }

    @Test
    fun `nothing is saved without a length`() {
        val bare = launch.copy(video = null)
        progress.heard(feed(CastPlayerState.Playing, 0, durationMs = null, of = bare), 0)
        progress.heard(feed(CastPlayerState.Paused, 30_000, durationMs = null, of = bare), 30_000)
        assertEquals(emptyList(), saved)
    }
}

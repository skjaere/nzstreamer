package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocation
import io.skjaere.usenet.player.tv.PlaybackBudget
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A play's load control ([PlayerFactory.loadControl]) asked as Media3's playback thread asks it while a film plays: with
 * the film buffered ahead, and the bytes it holds counted in its allocator's 64 KiB units. It loads on whenever it holds
 * less than 50 s and has room in the budget's bytes, so a stream never rests long enough for a NAT, a VPN or the
 * provider to drop its idle connections; at 20 s / 90 s it rested from 90 s, or the byte target, down to 20 s.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LoadControlTest {
    private val player = PlayerId("play")
    private val timeline = SinglePeriodTimeline(3_600_000_000L, true, false, false, null, MediaItem.Builder().build())
    private val period = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    /** [budget]'s load control, prepared for [player] as a play's player prepares it. */
    private fun loadControl(budget: PlaybackBudget = PlaybackBudget(64 * MIB, 40L * MIB, null)): DefaultLoadControl =
        PlayerFactory.loadControl(budget).also { it.onPrepared(player) }

    /** Whether it loads on with [aheadMs] of the film buffered ahead of the playhead. */
    private fun DefaultLoadControl.loadsAt(aheadMs: Long): Boolean =
        shouldContinueLoading(LoadControl.Parameters(player, timeline, period, 0, aheadMs * 1_000, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET))

    @Test
    fun `it stops at 50 s buffered and loads again as soon as it holds less`() {
        val control = loadControl()
        assertTrue(control.loadsAt(10_000), "10 s buffered")
        assertFalse(control.loadsAt(95_000), "95 s buffered")
        listOf(49_000L, 35_000, 20_000, 5_000).forEach { ahead ->
            assertTrue(control.loadsAt(ahead), "$ahead ms buffered, after it stopped")
            assertFalse(control.loadsAt(50_000), "50 s buffered")
        }
    }

    /** Time never takes precedence over the budget's bytes: at a high bitrate the bytes stop it short of 50 s, as before. */
    @Test
    fun `the budget's bytes stop it short of 50 s, and room in them lets it load again`() {
        val control = loadControl(PlaybackBudget(MIB, 16L * MIB, null))
        val allocator = control.getAllocator(player)
        val held = ArrayList<Allocation>()
        repeat(MIB / allocator.individualAllocationLength) { held += allocator.allocate() }
        assertFalse(control.loadsAt(10_000), "the budget's bytes held with 10 s buffered")
        allocator.release(held.removeAt(held.lastIndex))
        assertTrue(control.loadsAt(10_000), "a unit free again")
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}

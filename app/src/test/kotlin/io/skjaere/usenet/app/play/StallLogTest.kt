package io.skjaere.usenet.app.play

import androidx.media3.common.Player
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The player's measuring lines (L238), driven as the player drives them: its state as Media3 reports it, a seek as it
 * comes, the film buffered ahead on each half-second tick, and a load that failed. Both sources log them alike; they
 * name no file, URI or host.
 */
class StallLogTest {
    private var clock = 1_000_000L
    private val stalls = StallLog(now = { clock })

    /** The start: buffering, then ready, playing; then [ticks] of half a second with the buffer ahead at each. */
    private fun started(vararg ticks: Long) {
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 1_500
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true), "the start isn't a stall")
        tick(*ticks)
    }

    private fun tick(vararg aheadMs: Long) = aheadMs.forEach {
        clock += 500
        stalls.buffered(it)
    }

    @Test
    fun `a stall while playing is timed, with the most it had buffered ahead before`() {
        started(1_200, 4_100, 3_000, 1_000, 0)
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 2_300
        assertEquals("player: stalled 2300 ms (4.1 s buffered before)", stalls.stateChanged(Player.STATE_READY, playing = true))
        // The next one counts from the play's recovery: what it went through since.
        tick(800, 2_600, 0)
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 640
        assertEquals("player: stalled 640 ms (2.6 s buffered before)", stalls.stateChanged(Player.STATE_READY, playing = true))
    }

    @Test
    fun `a seek's buffering isn't a stall, and one after it counts from the seek`() {
        started(9_000, 12_000)
        stalls.seeked()
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 3_000
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true), "a seek's")
        tick(1_000, 2_000, 0)
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 1_000
        assertEquals("player: stalled 1000 ms (2.0 s buffered before)", stalls.stateChanged(Player.STATE_READY, playing = true))
    }

    @Test
    fun `a seek during a stall ends it uncounted`() {
        started(3_000, 0)
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 4_000
        stalls.seeked()
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 2_000
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true))
    }

    /**
     * FSL-1: Media3 choosing the tracks again lets the renderers go and takes them again (a TV's audio route changed under
     * the play: a receiver switched on, the HDMI link re-synced), loading the file again from where it plays, with no seek
     * said: the buffering that brings isn't a stall. One that brought none (subtitles, an audio track already loaded)
     * leaves a stall a while later counted.
     */
    @Test
    fun `the buffering of tracks chosen again isn't a stall, and one that brought none leaves a later stall counted`() {
        started(20_000, 50_000, 49_000)
        stalls.reloaded()
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 1_700
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true), "a reload's buffering")
        tick(3_000, 9_000)
        stalls.reloaded()
        tick(9_500, 2_000, 0)
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 1_200
        assertEquals("player: stalled 1200 ms (9.5 s buffered before)", stalls.stateChanged(Player.STATE_READY, playing = true))
    }

    @Test
    fun `the start, a start again after a stop, and buffering while paused aren't stalls`() {
        started(5_000)
        assertNull(stalls.stateChanged(Player.STATE_IDLE, playing = true))
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 2_000
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true), "prepared again: a start")
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = false))
        clock += 2_000
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = false), "paused")
        // A stall the play ended in, its film over, is none either.
        assertNull(stalls.stateChanged(Player.STATE_BUFFERING, playing = true))
        clock += 1_000
        assertNull(stalls.stateChanged(Player.STATE_ENDED, playing = true))
        assertNull(stalls.stateChanged(Player.STATE_READY, playing = true))
    }

    @Test
    fun `a load that failed and was opened again names the class of what failed alone`() {
        val cdn = "https://cdn-07.example.net/d/9c1f2e/Harbor.Lights.S01E02.mkv?sig=LINKSECRET77"
        val timedOut = SocketTimeoutException("Read timed out from $cdn")
        assertEquals("player: a load failed (SocketTimeoutException), opened again", stalls.loadFailed(timedOut, retried = true))
        val wrapped = IOException("Couldn't read $cdn", timedOut)
        val line = stalls.loadFailed(wrapped, retried = true)
        assertEquals("player: a load failed (SocketTimeoutException), opened again", line, "what failed, the wrappers aside")
        listOf("cdn-07", "example.net", "Harbor", "LINKSECRET77", "https").forEach { assertFalse(it in line.orEmpty(), it) }
        assertEquals("player: a load failed (IOException), opened again", stalls.loadFailed(IOException(cdn), retried = true))
        // Not opened again: the play fails, which its own line says.
        assertNull(stalls.loadFailed(timedOut, retried = false))
    }
}

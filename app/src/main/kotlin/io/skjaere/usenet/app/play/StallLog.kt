package io.skjaere.usenet.app.play

import androidx.media3.common.Player
import java.util.Locale

/**
 * The player's measuring lines (L238), for a play from either source, kept in the local log and naming no file, URI or
 * host: a stall while playing, timed, with the most the player had buffered ahead before it ("player: stalled 2300 ms
 * (4.1 s buffered before)"), and a load that failed and was opened again, by the class of what failed ("player: a load
 * failed (SocketTimeoutException), opened again"). Kept apart from the player so it can be tested; one per player.
 *
 * A stall is a playing player going from ready to buffering and back. The start's buffering isn't one, nor a start again
 * after a stop, nor a seek's, nor that of tracks chosen again, which load the file again; one the play ends in, or stops
 * in, isn't timed. What it had buffered ahead is the most seen on the player's ticks since it last became ready: the
 * cushion the stall went through.
 */
class StallLog(
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
) {
    /** Whether the player has been ready since it was last prepared: buffering before that is the start's. */
    private var started = false

    /** A seek's buffering is under way: the next ready ends it, uncounted. */
    private var seeking = false

    /** When the renderers were last let go and taken again ([reloaded]); null since the player was last ready. */
    private var reloadedAt: Long? = null

    /** When the stall under way began, and what was buffered ahead before it; null while none is. */
    private var stalledAt: Long? = null
    private var aheadBefore = 0L

    /** The most buffered ahead since the player last became ready. */
    private var mostAheadMs = 0L

    /** The player's state changed to [state] (Media3's `Player.STATE_…`), [playing] or not: the line for a stall it ended. */
    fun stateChanged(state: Int, playing: Boolean): String? {
        when (state) {
            Player.STATE_BUFFERING -> {
                if (started && playing && !seeking && stalledAt == null && !reloading()) {
                    stalledAt = now()
                    aheadBefore = mostAheadMs
                }
            }
            Player.STATE_READY -> {
                val line = stalledAt?.let { at -> "player: stalled ${now() - at} ms (${seconds(aheadBefore)} s buffered before)" }
                started = true
                seeking = false
                reloadedAt = null
                stalledAt = null
                mostAheadMs = 0
                return line
            }
            // Stopped, failed or over: a stall under way isn't one to time, and buffering on a prepare again is a start.
            else -> {
                started = false
                seeking = false
                reloadedAt = null
                stalledAt = null
            }
        }
        return null
    }

    /** A seek: the buffering it brings isn't a stall, and a stall under way ends uncounted. */
    fun seeked() {
        seeking = true
        stalledAt = null
    }

    /**
     * The renderers let go and taken again: Media3 chose the tracks again (a TV's audio route changed under the play, a
     * receiver switched on or the HDMI link re-synced; another audio track picked) and loads the file again from where it
     * plays, saying no seek. The buffering it brings comes at once, and isn't a stall; one that brought none leaves the
     * next counted, as only buffering within [RELOAD_MS] of it is the reload's.
     */
    fun reloaded() {
        reloadedAt = now()
    }

    private fun reloading(): Boolean = reloadedAt?.let { now() - it <= RELOAD_MS } == true

    /** The film buffered ahead, on the player's tick. */
    fun buffered(aheadMs: Long) {
        if (stalledAt == null) mostAheadMs = maxOf(mostAheadMs, aheadMs)
    }

    /**
     * A load that failed: its line when Media3 opens it again ([retried]); null when it doesn't, as the play then fails,
     * which its own line says. Only the class of what failed (the innermost cause, the wrappers aside) is said: a
     * message can name the file, its link or its host.
     */
    fun loadFailed(error: Throwable, retried: Boolean): String? {
        if (!retried) return null
        val failed = generateSequence(error) { it.cause }.take(16).last()
        return "player: a load failed (${failed.javaClass.simpleName.ifEmpty { failed.javaClass.name }}), opened again"
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f", ms / 1_000.0)

    private companion object {
        /**
         * How soon after the renderers' reset buffering is a reload's: Media3 says it let them go before it says it
         * buffers, from one turn of its thread, so a second is ample.
         */
        const val RELOAD_MS = 1_000L
    }
}

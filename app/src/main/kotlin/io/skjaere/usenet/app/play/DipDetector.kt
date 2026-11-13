package io.skjaere.usenet.app.play

/**
 * When streaming falls behind the film (L7): the player's buffer ahead falls while Media3 loads without a break, so it
 * gets the film's bytes slower than they play, and what it reads comes slower than the file's bitrate. Kept apart from
 * the player so it can be tested; one per player, so a player made again starts afresh.
 *
 * What it doesn't take for one:
 * - a full buffer: Media3 stops loading at its cap, and the rate it reads is then naught (it reads only what it asks for);
 * - a buffer near its byte cap, where loading starts and stops every few hundred ms: loading must run on for the window;
 * - more than [Timings.maxAheadMs] buffered ahead (20 s): Media3 loads again as soon as it holds less than 50 s, so
 *   loading runs on whenever the stream falls short, from a full buffer too, and a short shortfall there would never
 *   stall. One that goes on shows once the buffer is down to 20 s, where loading used to start again;
 * - the first [Timings.graceMs] after the first frame, a seek or a jump past a missing part ([reopened]): the stream
 *   opened again starts slowly while its read-ahead fills, and the buffer falls meanwhile;
 * - the play paused;
 * - a scene that needs more than the film's average while the stream keeps up with that average (the rate at or over
 *   the bitrate);
 * - nothing read at all while loading runs on: a provider not answering, not a slow stream.
 *
 * The buffer falls by [Timings.fallMs] over [Timings.windowMs] of loading, or playback stalls for want of bytes (a TV box's
 * cushion is seconds: there the stall is the usual sign). A dip shows once, with the numbers it came with, and ends as
 * the buffer recovers (Media3 stops loading, its buffer full again, or it has grown [Timings.recoverMs] from its lowest),
 * once it has been on screen [Timings.shownForMs], or at once as the play pauses or seeks. Time it waits for the slot or
 * spends under a side panel ([Sample.covered]) isn't time shown, and a dip never seen doesn't count; one that recovers
 * while covered ends then, so it doesn't come back as the cover goes, its numbers old. The next waits [Timings.quietMs]
 * of playback; [Timings.perPlay] a play at most.
 */
class DipDetector(
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
    private val timings: Timings = Timings(),
) {
    /** The detector's waits, and the most buffered ahead a dip counts with: the app's own, shorter only in tests. */
    data class Timings(
        val graceMs: Long = 5_000L,
        val windowMs: Long = 10_000L,
        val fallMs: Long = 2_000L,
        val maxAheadMs: Long = 20_000L,
        val recoverMs: Long = 5_000L,
        val shownForMs: Long = 4_000L,
        val quietMs: Long = 5 * 60_000L,
        val perPlay: Int = 3,
    )

    /**
     * The player on a tick: whether it plays (play pressed, nothing holding it back, not failed), whether it [stalled] for
     * want of bytes, the film it has buffered ahead, where it is, the bytes its streams have handed it so far, the file's
     * bitrate (its size over its length; null while not known), and whether the banner is [covered]: the slot is another
     * overlay's, or a side panel is over it.
     */
    data class Sample(
        val playing: Boolean,
        val stalled: Boolean,
        val bufferedAheadMs: Long,
        val positionMs: Long,
        val bytes: Long,
        val bitrateBps: Long?,
        val covered: Boolean,
    )

    /** A dip: what the stream read over the window ([rateBps]), against the file's bitrate. */
    data class Dip(val rateBps: Long, val bitrateBps: Long)

    /** A tick's reading, kept while loading runs on. */
    private class Point(val at: Long, val bufferedMs: Long, val bytes: Long, val stalled: Boolean)

    private var started = false
    /** Ticks up to this time are the start's, a seek's or a jump's. */
    private var graceUntil = 0L
    private var loading = false
    /** Since loading last ran on outside a grace: the oldest kept is the window's start. */
    private val window = ArrayDeque<Point>()

    private var dip: Dip? = null
    /** Whether the dip showing was ever uncovered, and how long it has been on screen. */
    private var seen = false
    private var shownMs = 0L
    private var lowestMs = 0L

    /** Dips seen this play, and the playback since the last of them went (null: none yet). */
    private var count = 0
    private var quietPlayedMs: Long? = null
    private var lastTick: Long? = null
    private var lastPositionMs = 0L

    /** The player showed its first frame (again after a seek): judging starts after the grace. */
    fun firstFrame() {
        started = true
        reopened()
    }

    /** The stream opened again (a seek, a jump past a missing part, tracks chosen again): the grace starts again, and a dip goes. */
    fun reopened() {
        graceUntil = now() + timings.graceMs
        window.clear()
        end(now = true)
    }

    /** Media3 started or stopped loading: a stop breaks the window. */
    fun loading(isLoading: Boolean) {
        loading = isLoading
        if (!isLoading) window.clear()
    }

    /** One tick: the dip to show, or null. */
    fun tick(sample: Sample): Dip? {
        val t = now()
        val dt = lastTick?.let { (t - it).coerceAtLeast(0) } ?: 0
        lastTick = t
        // Playback, by the position's progress, no faster than the clock: pauses and stalls don't count, nor a seek's leap.
        val played = (sample.positionMs - lastPositionMs).coerceIn(0, dt)
        lastPositionMs = sample.positionMs
        if (sample.playing) quietPlayedMs = quietPlayedMs?.plus(played)
        if (!started || !sample.playing) {
            window.clear()
            end(now = true)
            return null
        }
        if (t <= graceUntil) {
            window.clear()
            return dip
        }
        val current = dip
        if (current != null) {
            if (!sample.covered) {
                if (seen) shownMs += dt
                seen = true
            }
            lowestMs = minOf(lowestMs, sample.bufferedAheadMs)
            // Recovered: it waits out its time on screen only while it shows, so it can be read.
            if (!loading || sample.bufferedAheadMs >= lowestMs + timings.recoverMs) end(now = sample.covered)
            return dip
        }
        if (!loading) return null
        window.addLast(Point(t, sample.bufferedAheadMs, sample.bytes, sample.stalled))
        // The window's start: the newest point at least a window old.
        while (window.size >= 2 && t - window[1].at >= timings.windowMs) window.removeFirst()
        val first = window.first()
        val span = t - first.at
        if (span < timings.windowMs) return null
        val bitrate = sample.bitrateBps ?: return null
        val fell = first.bufferedMs - sample.bufferedAheadMs >= timings.fallMs || window.any { it.stalled }
        val rate = (sample.bytes - first.bytes) * 8_000 / span
        if (!fell || rate <= 0 || rate >= bitrate || sample.bufferedAheadMs > timings.maxAheadMs) return null
        if (count >= timings.perPlay || (quietPlayedMs ?: Long.MAX_VALUE) < timings.quietMs) return null
        seen = !sample.covered
        shownMs = 0
        lowestMs = sample.bufferedAheadMs
        return Dip(rate, bitrate).also { dip = it }
    }

    /** The dip goes: at once ([now]: paused, a seek, out of sight), or once it has been on screen long enough to read; counted if it was seen. */
    private fun end(now: Boolean) {
        if (dip == null) return
        if (!now && seen && shownMs < timings.shownForMs) return
        dip = null
        window.clear()
        if (seen) {
            count++
            quietPlayedMs = 0
        }
        seen = false
    }
}

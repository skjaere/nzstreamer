package io.skjaere.usenet.app.casting

/**
 * The cast gap watchdog (L104). On the Shield, zeros inside a cluster's block data played on, though the receiver dropped
 * the rest of that cluster (about 1.15 s of sound in item 6(b)), but zeros over a Matroska cluster's header, or seconds of
 * them, hung the receiver in item 6(a): BUFFERING for good, or PLAYING at a frame that never moved on, with no error, no
 * idle reason and no request that would tell. Only the phone knows it served a gap.
 *
 * So once the device has played the load, standing for [CastTimings.gapStuckMillis] at a place inside a served gap's
 * window ([window]: [CastTimings.gapWindowMillis] each side of where the gap is placed, [placed]), the stream through
 * the gap having gone on past its end (so the device has what follows it; [CastFeed.passed]), it is stuck there: it is
 * sent to the later of the gap's placed end and the stuck place, plus [CastTimings.gapPastMillis], never back, and the
 * next time plus [CastTimings.gapPastAgainMillis], as the device resumes from the keyframe at or before where it is sent
 * (item 6(b)); at most [CastTimings.gapSeeks] times a gap. Standing where the watch sent it, or short of it, counts for
 * that gap too, though past its window ([reach]). Stuck there again after the seeks, the missing-parts prompt asks at once
 * ([Act.Prompt]), and the watch says nothing more of that gap; so too in place of a seek that would send the device into
 * the film's last [CastTimings.stuckEndMillis], where neither the watch nor the stuck end, which reads only PLAYING,
 * would see it stuck buffering. Standing is BUFFERING with the time going by (the device says when it plays again), or
 * PLAYING with the device's own position the same in its answers (L103's: never Media3's, which moves on by itself while
 * the device says PLAYING, nor the time since one answer alone, which says nothing of a device that plays on steadily).
 * A pause, the start before the first PLAYING, a gap not served, one the stream through it hasn't gone past, and the
 * film's last [CastTimings.stuckEndMillis] (the stuck end's, L103) don't count; after a seek, or the session down for a
 * while ([away]), only the device's answers from then on.
 *
 * Pure, so a JVM test reads every case; the cast play the bar and the remote follow runs it (CastPlaying), a new one for
 * each LOAD. Not thread-safe.
 */
class CastGapWatch(private val timings: CastTimings = castTimings()) {
    /** What the watch asks for. */
    sealed interface Act {
        /** The device is sent to [toMs], past the missing part it stands at. */
        data class Seek(val toMs: Long) : Act

        /**
         * Still stuck after its seeks, or with the next seek into the film's last seconds: the missing-parts prompt at once,
         * whatever the count, saying the device is stuck.
         */
        data object Prompt : Act
    }

    /** The load watched: a new one starts afresh. */
    private var load: String? = null

    /** The device played the load: before, its loading is the load's own rules' (CastingRules). */
    private var played = false

    /** Where the device stands, since when (its first word there), its last word there, and whether it buffers there. */
    private var place: Long? = null
    private var since = 0L
    private var lastAt = 0L
    private var buffering = false

    /**
     * When the watch last sent the device on, or last found its session down: its words from before say nothing of where
     * it is now, nor of how long it has stood there.
     */
    private var freshFrom: Long? = null

    /** The watch's seeks and its ask, by the gap they were for. */
    private val tries = mutableMapOf<LongRange, Int>()

    /** Where the watch last sent the device past each gap. */
    private val sent = mutableMapOf<LongRange, Long>()

    /** [feed] as it is at [now] (elapsedRealtime, the clock the device's words are timed by): what to do, if anything. */
    fun heard(feed: CastFeed, now: Long): Act? {
        follow(feed)
        if (feed.end != null) return null
        when (feed.state) {
            CastPlayerState.Playing -> played = true
            CastPlayerState.Buffering, CastPlayerState.Loading -> if (!played) return null
            // A pause is the user's, never stuck; idle and unknown say nothing of where it is.
            CastPlayerState.Paused, CastPlayerState.Idle, CastPlayerState.Unknown -> return null.also { clear() }
        }
        val at = feed.reported ?: return null
        val heardAt = feed.reportedAt ?: return null
        if (freshFrom?.let { heardAt < it } == true) return null
        val buffers = feed.state != CastPlayerState.Playing
        if (at != place || buffers != buffering) {
            // Moved on, or now buffering or playing again where it was: standing starts from this word.
            place = at
            since = heardAt
            lastAt = heardAt
            buffering = buffers
        } else {
            lastAt = maxOf(lastAt, heardAt)
        }
        val stood = if (buffering) now - since else lastAt - since
        if (stood < timings.gapStuckMillis) return null
        val length = feed.durationMs ?: feed.launch.video?.durationMs ?: return null
        val size = feed.size?.takeIf { it > 0 } ?: return null
        // The film's last seconds are the stuck end's (L103).
        if (length - at <= timings.stuckEndMillis) return null
        // A gap whose own window holds the place first; else one the watch sent the device past, to here or further.
        val gap = feed.gaps.firstOrNull { at in window(it, size, length, timings) && it in feed.passed }
            ?: feed.gaps.firstOrNull { at in reach(it, size, length) && it in feed.passed }
            ?: return null
        val tried = tries.entries.filter { (bytes, _) -> bytes overlaps gap }.sumOf { it.value }
        if (tried > timings.gapSeeks) return null
        val past = maxOf(placed(gap, size, length).last, at) + if (tried == 0) timings.gapPastMillis else timings.gapPastAgainMillis
        // The film's last seconds are left to the stuck end, which reads only PLAYING: a device sent there and stuck
        // buffering again would be left to nothing, so the prompt asks at once instead, as after the seeks.
        val ask = tried == timings.gapSeeks || length - past <= timings.stuckEndMillis
        // Once it asks, the watch says nothing more of that gap.
        tries[gap] = (tries[gap] ?: 0) + if (ask) timings.gapSeeks + 1 - tried else 1
        freshFrom = now
        clear()
        if (ask) return Act.Prompt
        sent[gap] = maxOf(sent[gap] ?: 0, past)
        return Act.Seek(past)
    }

    /**
     * Where the device may stand stuck at [gap]: its [window], reaching on to where the watch last sent the device past it.
     * A seek from late in the window lands past its end, and the next goes further; stuck there, it is stuck at that gap.
     */
    private fun reach(gap: LongRange, size: Long, length: Long): LongRange {
        val window = window(gap, size, length, timings)
        val to = sent.entries.filter { (bytes, _) -> bytes overlaps gap }.maxOfOrNull { it.value } ?: return window
        return window.first..maxOf(window.last, to)
    }

    /**
     * [feed]'s session is down at [now] (reconnecting): the device's words don't come meanwhile, so the time away isn't
     * standing, and only its words once the session is back count.
     */
    fun away(feed: CastFeed, now: Long) {
        follow(feed)
        freshFrom = now
        clear()
    }

    /** [feed]'s load watched from now: a new one starts afresh. */
    private fun follow(feed: CastFeed) {
        if (feed.load == load) return
        load = feed.load
        played = false
        freshFrom = null
        tries.clear()
        sent.clear()
        clear()
    }

    /** Standing nowhere: the next word starts it again. */
    private fun clear() {
        place = null
        lastAt = 0
    }

    companion object {
        /**
         * Where [bytes] of a file of [size] bytes lies in a film of [lengthMs] (L94): by the file's share of the film, as
         * the player's fallback places a missing part with no index to ask. From its start to its end, in milliseconds.
         */
        fun placed(bytes: LongRange, size: Long, lengthMs: Long): LongRange =
            (bytes.first.toDouble() / size * lengthMs).toLong()..((bytes.last + 1).toDouble() / size * lengthMs).toLong()

        /** Where the device may stand stuck at [bytes] (L104): its placed times, [CastTimings.gapWindowMillis] wider each side. */
        fun window(bytes: LongRange, size: Long, lengthMs: Long, timings: CastTimings): LongRange =
            placed(bytes, size, lengthMs).let { (it.first - timings.gapWindowMillis)..(it.last + timings.gapWindowMillis) }

        /**
         * Where the device is asked for its status around [bytes] (L103, L104): its [window], and on past it as far as the
         * watch's seeks can send the device, so the watch hears it standing at its own targets too.
         */
        fun watched(bytes: LongRange, size: Long, lengthMs: Long, timings: CastTimings): LongRange {
            val furthest = timings.gapPastMillis + (timings.gapSeeks - 1).coerceAtLeast(0) * timings.gapPastAgainMillis
            return window(bytes, size, lengthMs, timings).let { it.first..(it.last + furthest) }
        }

        /** Whether two stretches of bytes share any: a gap's range grows as more of it is served. */
        private infix fun LongRange.overlaps(other: LongRange): Boolean = first <= other.last && other.first <= last
    }
}

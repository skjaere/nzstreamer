package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint

/**
 * Where a file's index ([SeekMap]: Matroska's cues, an MP4's sample tables) lets a play start, looked up by bytes rather
 * than by time. A point's bytes grow with its time, so a search over time finds them: about forty lookups for any film.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object SeekMaps {
    /**
     * The earliest point of [map] whose bytes start at [position] or after: for an MP4, every track's sample at its time
     * does. Null when none does (the position is in the file's last stretch), or when [map] doesn't know the file's length.
     */
    fun firstFrom(map: SeekMap, position: Long): SeekPoint? {
        val durationUs = map.durationUs
        if (durationUs == C.TIME_UNSET || durationUs <= 0) return null
        fun at(timeUs: Long) = map.getSeekPoints(timeUs).first
        if (at(durationUs).position < position) return null
        // The earliest time whose point (the one at or before it) starts there or after.
        var low = 0L
        var high = durationUs
        while (low < high) {
            val mid = low + (high - low) / 2
            if (at(mid).position >= position) high = mid else low = mid + 1
        }
        return at(high)
    }

    /** The time of the last point of [map] at or before byte [position]: when the stretch of the film holding it starts. Null when [map] can't tell. */
    fun timeAt(map: SeekMap, position: Long): Long? {
        val durationUs = map.durationUs
        if (!map.isSeekable || durationUs == C.TIME_UNSET || durationUs <= 0) return null
        fun at(timeUs: Long) = map.getSeekPoints(timeUs).first
        if (at(0).position > position) return 0
        // The latest time whose point starts at or before it.
        var low = 0L
        var high = durationUs
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (at(mid).position <= position) low = mid else high = mid - 1
        }
        return at(low).timeUs
    }
}

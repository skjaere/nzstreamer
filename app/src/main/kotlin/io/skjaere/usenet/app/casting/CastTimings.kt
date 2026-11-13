package io.skjaere.usenet.app.casting

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.play.DipDetector

/** Google Cast's timings (L98): the design's. Device tests shrink them, as PlayOnTimings is shrunk. */
data class CastTimings(
    /** A tapped device not connected after this: "Couldn't connect. Tap to try again.", This phone chosen again (W34). */
    val connectMillis: Long = 15_000,
    /** How long the sheet's Cast group looks before it says no Cast device was found (W34). */
    val noneFoundMillis: Long = 10_000,
    /** The service's wake lock is let go this long after the server's last write (L59). */
    val wakeLetGoMillis: Long = 10_000,
    /** The wake lock's own timeout, so no missed path holds it unbounded (L59): taken again at half of it while writing. */
    val wakeTimeoutMillis: Long = 10 * 60_000L,
    /**
     * How long Media3 keeps the service in the foreground after a pause, before the service's own hold (L58): Media3's
     * 10 minutes. Device tests shrink it to see the hold.
     */
    val foregroundTimeoutMillis: Long = 10 * 60_000L,
    /** No request from the device this long after the LOAD: it can't reach this phone (W34, L88). */
    val noRequestMillis: Long = 30_000,
    /**
     * A device's error status with no code yet waits this long for its code (L88), which comes in a message of its own;
     * with none, it is read as a media session that vanished (L102).
     */
    val errorCodeMillis: Long = 1_000,
    /** A cast play's progress is saved this often while the device plays it (L61), as the player's is. */
    val saveEveryMillis: Long = 15_000,
    /** A FINISHED earlier than this before the end is a stop there, never the end (L60). */
    val finishMarginMillis: Long = 2 * 60_000L,
    /** Paused this long when the device ends the session: it closed after a long pause (L60, L93). The Shield's is 20 minutes. */
    val longPauseMillis: Long = 19 * 60_000L,
    /**
     * PLAYING with the device's own position this close to the length, and standing still there for [stuckStillMillis],
     * is the end, though the device never says FINISHED (L103).
     */
    val stuckEndMillis: Long = 3_000,
    val stuckStillMillis: Long = 10_000,
    /**
     * How often the device is asked for its status while the stuck end or a missing part is watched for (L103, L104): it
     * says it only now and then.
     */
    val statusEveryMillis: Long = 2_000,
    /**
     * The device standing this long at a missing part the phone served (BUFFERING, or PLAYING with its own position still)
     * is stuck there: it is sent past it (L104).
     */
    val gapStuckMillis: Long = 8_000,
    /** Where a missing part's stuck place may be: from this long before its placed start to as long after its placed end (L104). */
    val gapWindowMillis: Long = 5_000,
    /** The device is sent this far past the missing part's placed end, or past where it stuck, whichever is later (L104). */
    val gapPastMillis: Long = 2_000,
    /**
     * The same for the seeks after the first: the Shield's receiver resumes from the keyframe at or before where it is sent,
     * MKV and MP4 alike (item 6(b)), so 2 s past may land before the missing part again, and releases have up to about 10 s
     * between keyframes (L104).
     */
    val gapPastAgainMillis: Long = 10_000,
    /** Seeks past one missing part at most; stuck again after them, the prompt asks at once (L104). */
    val gapSeeks: Int = 2,
    /** How long "A missing part: Kitchen display may glitch" shows, as the player's "A missing part was skipped" (L94). */
    val partNoticeMillis: Long = 3_000,
    /** The slow-stream banner's waits during a cast (L134): the player's own ([DipDetector]). */
    val dip: DipDetector.Timings = DipDetector.Timings(),
) {
    companion object {
        /** A test seam: read where each wait starts. */
        @VisibleForTesting
        @Volatile
        var current = CastTimings()
    }
}

/** The timings now: the design's, or a test's ([CastTimings.current]). */
fun castTimings(): CastTimings = CastTimings.current

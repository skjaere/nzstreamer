package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.WatchSave
import io.skjaere.usenet.app.play.WatchSaver

/**
 * A cast play's progress (L61), saved as a phone play's, with its release ([WatchSaver]), from the feed that follows it
 * ([CastFeed]) and a clock (elapsedRealtime): every [CastTimings.saveEveryMillis] while the device plays it, as it pauses
 * there, and once as it ends there, where its end says ([CastEnd]: the length for a play watched to its end, else where
 * it was, the release left out where the providers failed it). The length is the device's last known (Media3 clears its
 * timeline at the end), else the probe's. Nothing is saved without one. Each save goes to [save]. Not thread-safe: Casting's
 * main thread's.
 */
class CastProgress(private val save: (WatchSave) -> Unit, private val timings: CastTimings = castTimings()) {
    private var lastDurationMs: Long? = null

    /** When the last save while playing was, or the play started playing; null while it doesn't play. */
    private var savedAt: Long? = null
    private var paused = false
    private var ended = false

    /** The play as followed now ([feed]), at [now]. */
    fun heard(feed: CastFeed, now: Long) {
        if (ended) return
        feed.durationMs?.takeIf { it > 0 }?.let { lastDurationMs = it }
        val length = lastDurationMs ?: feed.launch.video?.durationMs ?: 0
        val end = feed.end
        if (end != null) {
            ended = true
            val at = if (end.how == CastEnd.How.Watched) length.takeIf { it > 0 } ?: end.positionMs else end.positionMs
            WatchSaver.of(feed.launch, at, length, releaseFailed = end.providers)?.let(save)
            return
        }
        when (feed.state) {
            CastPlayerState.Playing -> {
                paused = false
                val since = savedAt
                if (since == null) {
                    savedAt = now
                } else if (now - since >= timings.saveEveryMillis) {
                    savedAt = now
                    WatchSaver.of(feed.launch, feed.positionMs, length, releaseFailed = false)?.let(save)
                }
            }
            CastPlayerState.Paused -> if (!paused) {
                paused = true
                savedAt = now
                WatchSaver.of(feed.launch, feed.positionMs, length, releaseFailed = false)?.let(save)
            }
            CastPlayerState.Buffering, CastPlayerState.Loading, CastPlayerState.Idle, CastPlayerState.Unknown -> Unit
        }
    }
}

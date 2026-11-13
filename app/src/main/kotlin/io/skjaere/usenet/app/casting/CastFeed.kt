package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.serve.ServeCounters

/**
 * A cast play on its device as the phone follows it (L60, L61, L91), from its LOAD: what the bar, the remote and the Show
 * page show of it (Part E's CastPlaying), and what its progress is saved from. The LOAD's own id ([load]: the content id
 * the device reports, opaque), which tells two loads of one launch apart; its [launch] (the titles, the watch key, the
 * device by name); the device's player's [state] by its last status of this play (Loading before one); where it plays
 * ([positionMs]: as the device says it, and polled each second from the session's player, which moves on between the
 * device's statuses while it plays) and how long the file is ([durationMs], null while the device's player has no length: before it says one, and
 * once its timeline clears at the end); the device's own last position ([reported]) and when it said it ([reportedAt],
 * elapsedRealtime: the stuck end's and the gap watch's, L103, L104); TMDB's picture, if any; and, once it ended there, how
 * ([end]): kept after the cast's service has stopped, so its words stay. What the phone's server sent the device: the
 * file's [size], the stretches of zeros it passed on for missing parts ([gaps], file offsets, merged where they touch,
 * L53), and those of them the device was sent what follows ([passed]: the stream through each went on past it), which the
 * missing-parts prompt and the gap watch read (L94, L104). What the server counted of the file each second, which the
 * slow-stream banner reads (L134): the bytes that reached the device ([sent]), those of them past its position ([ahead]:
 * null while the position can't be placed in the file), the time its answers waited on the providers ([sourceWaitMs])
 * and on the device taking what was sent ([peerWaitMs]), and when they were read ([countedAt], elapsedRealtime; null
 * before the first count). It prints without the launch's words.
 */
data class CastFeed(
    val load: String,
    val launch: PlayerLaunch,
    val state: CastPlayerState,
    val positionMs: Long,
    val durationMs: Long?,
    val reported: Long? = null,
    val reportedAt: Long? = null,
    val art: TvArt? = null,
    val end: CastEnd? = null,
    val size: Long? = null,
    val gaps: List<LongRange> = emptyList(),
    val passed: List<LongRange> = emptyList(),
    val sent: Long = 0,
    val ahead: Long? = null,
    val sourceWaitMs: Long = 0,
    val peerWaitMs: Long = 0,
    val countedAt: Long? = null,
) {
    override fun toString() = "CastFeed($state, ${end?.how})"
}

/**
 * [this] play with what the server counted of its file at [now] (L133, L134), which the slow-stream banner reads each
 * second: what reached the device ([ServeCounters.bytesSent]), how far past where it plays now ([CastDip.ahead], by the
 * bytes the answer covering it wrote, [answered]: Served.answered), and how long the answers waited on the providers and
 * on the device.
 */
fun CastFeed.counted(counters: ServeCounters, now: Long, answered: (Long) -> LongRange?): CastFeed = copy(
    sent = counters.bytesSent, ahead = CastDip.ahead(this, answered), sourceWaitMs = counters.sourceWaitMillis,
    peerWaitMs = counters.peerWaitMillis, countedAt = now,
)

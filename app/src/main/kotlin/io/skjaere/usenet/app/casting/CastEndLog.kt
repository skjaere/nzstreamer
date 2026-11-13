package io.skjaere.usenet.app.casting

import io.skjaere.usenet.serve.ServeCounters
import java.util.Locale

/**
 * The log's one line as a cast play ends there (L136; M2 reads it in Settings › Logs, #299's gap for casts): how [play]
 * ended, [elapsedMs] after its LOAD; what the phone's server sent the device ([counters]) and at what rate on average;
 * how long its answers waited on the providers and on the Wi-Fi to the device (L133); the slow-stream [dips]; and the
 * maximum when casting set ([castMaxMbps], null: not set). Counts alone: [play] is read for how it ended, never for its
 * title, its device's name or model, or its file's address, and nothing in it is a token, a link or a link speed.
 */
fun castEndLine(play: CastFeed, counters: ServeCounters, elapsedMs: Long, dips: Int, castMaxMbps: Int?): String {
    val how = when (play.end?.how) {
        CastEnd.How.Stopped -> "stopped"
        CastEnd.How.Watched -> "watched"
        CastEnd.How.LongPause -> "long pause"
        CastEnd.How.Failed -> "failed"
        CastEnd.How.Replaced -> "replaced"
        null -> "unknown"
    }
    val minutes = (elapsedMs + 30_000) / 60_000
    val megabytes = (counters.bytesSent + 500_000) / 1_000_000
    val mbps = if (elapsedMs > 0) counters.bytesSent * 8.0 / elapsedMs / 1_000 else 0.0
    val maximum = castMaxMbps?.let { "$it Mbps" } ?: "not set"
    return "cast: ended ($how) after $minutes min: $megabytes MB sent, ${"%.1f".format(Locale.ROOT, mbps)} Mbps; " +
        "waits: providers ${seconds(counters.sourceWaitMillis)} s, Wi-Fi ${seconds(counters.peerWaitMillis)} s; dips $dips; maximum when casting $maximum"
}

/** Milliseconds as whole seconds, to the nearest. */
private fun seconds(ms: Long) = (ms + 500) / 1_000

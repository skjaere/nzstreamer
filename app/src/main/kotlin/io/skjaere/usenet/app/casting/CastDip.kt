package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.DipDetector
import kotlin.math.abs

/**
 * When streaming to a Cast device falls behind the film (W52, L134; CsPhoneSlowCast): the player's own judge
 * ([DipDetector], unchanged), fed from the cast's feed each time Casting counts what its server sent ([CastFeed.countedAt],
 * about once a second; its clock is the counts'). The device says nothing of its buffer, and a receiver reads all through
 * a play, so the detector is given one made of what the server sent it past its position ([CastFeed.ahead]), as film by
 * the file's bitrate (C-4): it falls as the device plays more than it was sent, and rises as it catches up, so a dip ends.
 * The rate is what reached the device ([CastFeed.sent]), not what the providers gave the phone.
 *
 * It plays from the device's first PLAYING ([DipDetector.firstFrame]), and while it buffers after it, play pressed;
 * paused, idle, the session down, or standing in a served missing part's window, which is the gap watch's (L104), it
 * doesn't, and a dip goes at once. Loading runs on while the server sends or the device buffers. Where the device is, set
 * against where it would be, more than [SEEK_MS] off is a seek, and the session coming back is the stream opened again:
 * the grace starts again ([DipDetector.reopened]). One a load: a new LOAD is a new one.
 *
 * A fall alone isn't a dip until the device wants what doesn't come: it stood buffering in the window, or the server
 * waited on the providers, and no less than on the device. A device that keeps its read-ahead full holds the server's
 * writes and takes what its scene needs, under the film's average in a quieter one, so the buffer counted at the average
 * falls on a healthy cast too. Such a fall is the detector's unseen, as one under a prompt is, and shows only once the
 * device wants, with what reached it over the window then.
 *
 * Which part is slow ([Cause]), over the dip's window: more of the server's time spent waiting for the device to take
 * what was sent ([CastFeed.peerWaitMs]) than waiting on the providers ([CastFeed.sourceWaitMs]) is the Wi-Fi to the
 * device, which then shows as it stands buffering; else the providers (W64). A dip shows with the numbers and the cause
 * it came with; Try a smaller release answers it ([answered]), and it shows no more. The dips are counted for the play's
 * end ([found]). Pure, so a JVM test reads every case; the cast play the bar follows runs it (CastPlaying). Not
 * thread-safe.
 */
class CastDip(private val timings: CastTimings = castTimings()) {
    /** Which part of the path is the slow one: the Wi-Fi from this phone to the device, or the providers to this phone. */
    enum class Cause { WiFi, Providers }

    /** A dip to show: what reached the device over its window against the file's bitrate ([dip]), and the slow part ([cause]). */
    data class Slow(val dip: DipDetector.Dip, val cause: Cause)

    /** The time of the count being judged: the detector's clock. */
    private var at = 0L
    private val detector = DipDetector({ at }, timings.dip)

    /** The last count judged: a feed counted again says nothing new. */
    private var last: Count? = null
    private var played = false
    private var loading = false
    private var connected = true

    /** The server's waits at each count, over a window back. */
    private val waits = ArrayDeque<Count>()

    private var slow: Slow? = null
    private var answered: DipDetector.Dip? = null

    /** The detector's dip [slow] shows, and the one it gave at the last count (held unseen, it may show later). */
    private var shownFor: DipDetector.Dip? = null
    private var held: DipDetector.Dip? = null

    /** The dips found this play, seen or not: its end's log line says how many (L136). */
    var found: Int = 0
        private set

    /** What a count said: when, where the device was and in what state, what was sent, and the server's waits so far. */
    private class Count(val at: Long, val positionMs: Long, val state: CastPlayerState, val sent: Long, val source: Long, val peer: Long)

    /**
     * [feed] as it is now, its session [connected] or not, the banner [covered] (the missing-parts prompt has the slot, or
     * the app isn't in front: found, but not time shown): the dip to show, or null. Judged once a count.
     */
    fun heard(feed: CastFeed, connected: Boolean, covered: Boolean): Slow? {
        val countedAt = feed.countedAt ?: return shown()
        val before = last
        if (before != null && countedAt <= before.at) return shown()
        at = countedAt
        val state = feed.state
        if (state == CastPlayerState.Playing && !played) {
            played = true
            detector.firstFrame()
        }
        // Back from reconnecting: what it did meanwhile is unknown, so the stream is taken as opened again.
        if (connected && !this.connected) detector.reopened()
        this.connected = connected
        if (before != null) {
            val expected = before.positionMs + if (before.state == CastPlayerState.Playing) countedAt - before.at else 0
            if (abs(feed.positionMs - expected) > SEEK_MS) detector.reopened()
        }
        val count = Count(countedAt, feed.positionMs, state, feed.sent, feed.sourceWaitMs, feed.peerWaitMs)
        last = count
        waits.addLast(count)
        // The window's start: the newest count at least a window old.
        while (waits.size >= 2 && countedAt - waits[1].at >= timings.dip.windowMs) waits.removeFirst()
        val buffering = state == CastPlayerState.Buffering
        val sending = before != null && feed.sent > before.sent
        if ((sending || buffering) != loading) {
            loading = sending || buffering
            detector.loading(loading)
        }
        val length = feed.durationMs ?: feed.launch.video?.durationMs
        val size = feed.size?.takeIf { it > 0 }
        val bitrate = if (length != null && length > 0 && size != null) size * 8_000 / length else null
        val atAGap = length != null && length > 0 && size != null &&
            feed.gaps.any { feed.positionMs in CastGapWatch.watched(it, size, length, timings) }
        val playing = connected && !atAGap && (state == CastPlayerState.Playing || (buffering && played))
        val aheadMs = if (bitrate != null && bitrate > 0) (feed.ahead ?: 0) * 8_000 / bitrate else 0
        val wants = waits.any { it.state == CastPlayerState.Buffering } || waitedOnTheProviders(count)
        val unseen = covered || (shownFor == null && !wants)
        val dip = detector.tick(DipDetector.Sample(playing, buffering, aheadMs, feed.positionMs, feed.sent, bitrate, unseen))
        val fresh = dip !== held
        held = dip
        slow = when {
            dip == null -> null.also { shownFor = null }
            dip === shownFor -> slow
            !wants -> null
            // One the detector held unseen: what reached the device over the window now.
            else -> Slow(if (fresh) dip else DipDetector.Dip(rateOver(count), dip.bitrateBps), cause(count)).also {
                shownFor = dip
                found++
            }
        }
        return shown()
    }

    /** Try a smaller release answered the dip showing: it shows no more. */
    fun answered() {
        answered = slow?.dip
    }

    private fun shown(): Slow? = slow?.takeIf { it.dip !== answered }

    /** Which part waited more over the window to [now]. */
    private fun cause(now: Count): Cause {
        val from = waits.first()
        return if (now.peer - from.peer > now.source - from.source) Cause.WiFi else Cause.Providers
    }

    /** What reached the device over the window to [now], in bits a second. */
    private fun rateOver(now: Count): Long {
        val from = waits.first()
        return if (now.at > from.at) (now.sent - from.sent) * 8_000 / (now.at - from.at) else 0
    }

    /** Whether over the window to [now] the server waited on the providers, and no less than on the device. */
    private fun waitedOnTheProviders(now: Count): Boolean {
        val from = waits.first()
        val source = now.source - from.source
        return source > 0 && source >= now.peer - from.peer
    }

    companion object {
        /** Where the device is, this far from where it would be: a seek. */
        const val SEEK_MS = 3_000L

        /**
         * How far past [byte] the device was sent, by the bytes the server's answer covering it wrote ([answered]:
         * Served.answered), on through an answer that carries on where that one stopped: a receiver that read the index at
         * the file's end goes on from where its first answer stopped in an answer of its own. Never on into an answer that
         * began further back, which a seek back left behind and the device dropped. 0 when none went past it.
         */
        fun sentPast(byte: Long, answered: (Long) -> LongRange?): Long {
            var covering = answered(byte) ?: return 0
            while (true) {
                val next = answered(covering.last + 1) ?: break
                if (next.first < covering.first) break
                covering = next
            }
            return covering.last + 1 - byte
        }

        /**
         * What [feed]'s device was sent past its position ([sentPast]), the position placed in the file by its share of
         * the film; null while it can't be (no length, or no size).
         */
        fun ahead(feed: CastFeed, answered: (Long) -> LongRange?): Long? {
            val length = feed.durationMs ?: feed.launch.video?.durationMs ?: return null
            val size = feed.size?.takeIf { it > 0 } ?: return null
            if (length <= 0) return null
            val byte = (feed.positionMs.coerceIn(0, length).toDouble() / length * size).toLong().coerceAtMost(size - 1)
            return sentPast(byte, answered)
        }
    }
}

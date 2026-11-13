package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.collection.mmss
import io.skjaere.usenet.app.link.stoppedOnAt
import io.skjaere.usenet.app.link.watchedOn
import io.skjaere.usenet.serve.ServeCounters

/** What a cast play's load sends the device, in order (L68). */
enum class LoadStep {
    /** The device's player meant to play: the LOAD goes with autoplay. */
    PlayWhenReady,

    /** The item, from where the play starts: the LOAD. */
    SetMediaItem,
}

/**
 * How a cast play ended on its device once it had played there (L60, L61, L84, L88, L93): where it was ([positionMs], the
 * film's length once it was watched), and whether the providers failed its release ([providers]), which Resume then no
 * longer plays.
 */
data class CastEnd(val how: How, val positionMs: Long, val providers: Boolean = false) {
    enum class How {
        /** Stopped there: Stop casting, the session's end, another sender, a FINISHED well before the end (L60). */
        Stopped,

        /** Played to its end: FINISHED near it, or the stuck end (L103). */
        Watched,

        /** The device closed the session after a long pause (L60). */
        LongPause,

        /** An error there after it played (L88): "Playback stopped on Kitchen display", with Stop casting alone. */
        Failed,

        /** The next play's load took its place there (L84): where it was is saved, and nothing is said. */
        Replaced,
    }

    /** What the phone says of it once, after (L93; CsPhoneStopped): none for a failure, which the bar says, or a play replaced. */
    fun said(name: String): String? = when (how) {
        How.Stopped -> stoppedOnAt(name, positionMs)
        How.Watched -> watchedOn(name)
        How.LongPause -> closedAfterLongPause(name, positionMs)
        How.Failed, How.Replaced -> null
    }
}

/** The device ended the session at least [CastTimings.longPauseMillis] into a pause (L60, L93). */
fun closedAfterLongPause(name: String, positionMs: Long) = "$name closed after a long pause. Stopped at ${mmss(positionMs)}."

/**
 * One cast load's rules (L60, L68, L88, L102, L103), from the moment its LOAD goes to the device ([loadedAt]) to the play's
 * end there: what the load sends ([steps]), how it came out (the device plays it, or how it failed), and, once it played,
 * how it ended. They read the device's events for the load's own content id ([contentId]), the counters of the phone's
 * server for the load's token, and a clock (elapsedRealtime); each call says what to do ([Act]). The device's own reported
 * position is what tells a stuck end, never the extrapolation of Media3's player, which moves on by itself while the device
 * says PLAYING. Pure, so a JVM test reads every case; the cast package's Casting wires them. Not thread-safe: Casting's
 * main thread's.
 *
 * - The order (L68): a device with no media status takes the LOAD with autoplay on; a device taken over still has another
 *   sender's (W32), whose paused media a play() would start, so ours loads as theirs was and is played at its first status.
 * - Before the device plays it: no request in [CastTimings.noRequestMillis] is can't reach; the receiver's code says the
 *   failure ([CastOutcomes.error]); an error status of ours waits [CastTimings.errorCodeMillis] for its code; the media
 *   gone with neither ([CastOutcomes.vanished], L102) once a status of ours or a request showed the device has the load,
 *   for before that another sender's status, or none, is the device's state before ours; the session's end, or another
 *   sender's load, fails it.
 * - Once it played: its FINISHED, another sender's load, an error, the session's end, or PLAYING within
 *   [CastTimings.stuckEndMillis] of the end with the device's position standing still for [CastTimings.stuckStillMillis]
 *   (L103: the device's media is stopped first) ends it. The device says where it is only now and then, never while it
 *   plays on steadily, so the end is near by where it should be (its last word moved on since, or the session's player's
 *   poll), and from then the device is asked for its status every [CastTimings.statusEveryMillis]: its answers are what
 *   stands still or moves on. It is asked so too while the play is inside a served gap's window, or past it as far as the
 *   gap watch's seeks can send the device (L104: [CastGapWatch.watched], by the file's [size]), by where it should be or by
 *   the device's own word while it plays, where the gap watch reads its answers. As it ends ([over]), where it was is
 *   known ([CastEnd]): a FINISHED more than [CastTimings.finishMarginMillis] before the end is a stop there, and the
 *   session ended by the device after [CastTimings.longPauseMillis] paused is the long pause's.
 *
 * The length is the device's while its player had one (its timeline clears at the end), else the probe's
 * ([probedDurationMs]); the position is the last heard, from the device or polled from the session's player.
 */
class CastingRules(
    private val name: String,
    private val contentId: String,
    private val file: CastFile,
    deviceHasStatus: Boolean,
    private val loadedAt: Long,
    private val probedDurationMs: Long?,
    private val size: Long? = null,
    private val timings: CastTimings = castTimings(),
) {
    /** What the rules ask for. */
    sealed interface Act {
        /** The device's player plays: a device taken over, at the load's first status (L68). */
        data object Play : Act

        /** The device is asked for its media status now (L103). */
        data object RequestStatus : Act

        /** How the load came out, before the device played it or as it did (L65). */
        data class Outcome(val outcome: CastLoadOutcome) : Act

        /** The play ends now, for [why], once the device's media is stopped where [stopDevice] (L103). */
        data class End(val why: Why, val stopDevice: Boolean = false) : Act

        /** The play ended there so ([end]): saved, and said, as it says. */
        data class Ended(val end: CastEnd) : Act
    }

    /** The load's steps, in order (L68). */
    val steps: List<LoadStep> = if (deviceHasStatus) listOf(LoadStep.SetMediaItem) else listOf(LoadStep.PlayWhenReady, LoadStep.SetMediaItem)

    private enum class Phase { Starting, Playing, Over }

    private var phase = Phase.Starting

    /** A status of ours came: the device has the load. */
    private var seen = false

    /** When an error status of ours came, its code not yet. */
    private var errorAt: Long? = null

    private var positionMs: Long? = null
    private var durationMs: Long? = null

    /** The last status of ours said PLAYING. */
    private var playing = false

    /** The device's own position while it plays, and since when it stands there. */
    private var reported: Long? = null
    private var stillSince: Long? = null
    private var stuck = false

    /** When the device last said it plays ours: where it should be is its word moved on since. */
    private var heardAt: Long? = null

    /** Since when the device says ours is paused. */
    private var pausedSince: Long? = null

    /** When the device was last asked for its status. */
    private var askedAt: Long? = null

    private val length: Long? get() = durationMs ?: probedDurationMs

    /** The device's [event], as the server [served] the load, at [now]. */
    fun heard(event: CastEvent, served: ServeCounters, now: Long): List<Act> {
        if (phase == Phase.Over) return emptyList()
        return when (event) {
            CastEvent.Started, CastEvent.Suspended, CastEvent.Resumed -> emptyList()
            CastEvent.Ended -> if (phase == Phase.Starting) fail(CastOutcomes.closedBeforePlaying(served, name)) + Act.End(Why.SessionEnded) else listOf(Act.End(Why.SessionEnded))
            is CastEvent.MediaError -> if (phase == Phase.Starting) fail(CastOutcomes.error(event.code, served, name, file)) else listOf(Act.End(Why.Failed))
            is CastEvent.Status -> if (event.contentId == contentId) ours(event, served, now) else other(event, served)
        }
    }

    /**
     * A second has gone, at [now]: what the server [served] the load, and the stretches of zeros it passed on ([gaps]), and
     * what the session's player says while its item is the load's ([positionMs], [durationMs]: null while it has none, its
     * timeline cleared at the end).
     */
    fun tick(served: ServeCounters, now: Long, positionMs: Long? = null, durationMs: Long? = null, gaps: List<LongRange> = emptyList()): List<Act> {
        if (phase == Phase.Over) return emptyList()
        positionMs?.let { this.positionMs = it }
        durationMs?.takeIf { it > 0 }?.let { this.durationMs = it }
        if (phase == Phase.Starting) {
            if (errorAt?.let { now - it >= timings.errorCodeMillis } == true) return fail(CastOutcomes.error(null, served, name, file))
            if (served.requests == 0 && now - loadedAt >= timings.noRequestMillis) return fail(CastOutcomes.noRequest())
            return emptyList()
        }
        if (!nearTheEnd() && !nearing(now, positionMs) && !atAGap(now, gaps)) {
            askedAt = null
            return emptyList()
        }
        val acts = mutableListOf<Act>()
        if (askedAt?.let { now - it >= timings.statusEveryMillis } != false) {
            askedAt = now
            acts += Act.RequestStatus
        }
        // Stuck by the device's own word alone: its answers standing still there.
        val since = stillSince?.takeIf { nearTheEnd() } ?: return acts
        if (!stuck && now - since >= timings.stuckStillMillis) {
            stuck = true
            acts += Act.End(Why.Finished, stopDevice = true)
        }
        return acts
    }

    /**
     * The play ended for [why] (null: the next play's load took its place there, L84), as the server [served] it, at [now]:
     * before the device played it, how the load came out; after, how it ended there.
     */
    fun over(why: Why?, served: ServeCounters, now: Long): List<Act> = when (phase) {
        Phase.Over -> emptyList()
        Phase.Starting -> when (why) {
            // It played and ended between two of the device's statuses: a short file.
            Why.Finished -> {
                phase = Phase.Playing
                listOf(Act.Outcome(CastLoadOutcome.Playing)) + over(why, served, now)
            }
            Why.SessionEnded, Why.OtherDevice -> fail(CastOutcomes.closedBeforePlaying(served, name))
            Why.Interrupted -> fail(CastOutcomes.interrupted(name))
            // Ended from the phone (its load is given up), or failed by the service, whose failure is the load's own.
            else -> fail(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true))
        }
        Phase.Playing -> {
            phase = Phase.Over
            listOf(Act.Ended(end(why, served, now)))
        }
    }

    private fun ours(status: CastEvent.Status, served: ServeCounters, now: Long): List<Act> {
        val acts = mutableListOf<Act>()
        if (!seen && steps.first() == LoadStep.SetMediaItem && status.state != CastPlayerState.Playing) acts += Act.Play
        seen = true
        playing = status.state == CastPlayerState.Playing
        if (!playing) stillSince = null
        when (status.state) {
            CastPlayerState.Playing -> {
                pausedSince = null
                positionMs = status.positionMs
                heardAt = status.at
                if (reported != status.positionMs || stillSince == null) {
                    reported = status.positionMs
                    stillSince = status.at
                }
                if (phase == Phase.Starting) {
                    phase = Phase.Playing
                    acts += Act.Outcome(CastLoadOutcome.Playing)
                }
            }
            CastPlayerState.Paused -> {
                pausedSince = pausedSince ?: status.at
                positionMs = status.positionMs
            }
            CastPlayerState.Buffering, CastPlayerState.Loading -> {
                pausedSince = null
                positionMs = status.positionMs
            }
            CastPlayerState.Idle -> acts += idle(status.idle, served, now)
            CastPlayerState.Unknown -> Unit
        }
        return acts
    }

    /** Ours is idle there, for [reason]. */
    private fun idle(reason: CastIdleReason?, served: ServeCounters, now: Long): List<Act> = when (reason) {
        CastIdleReason.Finished -> if (phase == Phase.Starting) {
            phase = Phase.Playing
            listOf(Act.Outcome(CastLoadOutcome.Playing), Act.End(Why.Finished))
        } else {
            listOf(Act.End(Why.Finished))
        }
        CastIdleReason.Interrupted -> if (phase == Phase.Starting) fail(CastOutcomes.interrupted(name)) + Act.End(Why.Interrupted) else listOf(Act.End(Why.Interrupted))
        // Stopped by another sender once it played; before, by this phone, whose play ends of itself.
        CastIdleReason.Cancelled -> if (phase == Phase.Playing) listOf(Act.End(Why.Interrupted)) else emptyList()
        CastIdleReason.Error -> if (phase == Phase.Playing) {
            listOf(Act.End(Why.Failed))
        } else {
            // Its code comes in a message of its own: waited for a moment.
            errorAt = errorAt ?: now
            emptyList()
        }
        CastIdleReason.None, null -> emptyList()
    }

    /**
     * A status of another sender's media, or of none. Before the device showed it has the load (a status of ours, or a
     * request for its token), it is the device's state before ours (W32, L68); after, no media at all is ours vanished
     * (L102), unless an error status of ours waits for its code. Once it played, the play's own ends say how it ends.
     */
    private fun other(status: CastEvent.Status, served: ServeCounters): List<Act> {
        val armed = seen || served.requests > 0
        if (phase != Phase.Starting || !armed || status.contentId != null || errorAt != null) return emptyList()
        return fail(CastOutcomes.vanished(served, name, file))
    }

    private fun fail(failure: CastLoadFailure): List<Act> {
        phase = Phase.Over
        return listOf(Act.Outcome(CastLoadOutcome.Failed(failure)))
    }

    /** PLAYING within [CastTimings.stuckEndMillis] of the length, by the device's own position. */
    private fun nearTheEnd(): Boolean {
        val length = length ?: return false
        val at = reported ?: return false
        return playing && length - at <= timings.stuckEndMillis
    }

    /**
     * PLAYING within [CastTimings.stuckEndMillis] of the length by where it should be at [now]: the device's last word
     * moved on since, or the session's player's poll ([polled]), whichever is further. Only asks the device: what it
     * answers says whether it is there.
     */
    private fun nearing(now: Long, polled: Long?): Boolean {
        val length = length ?: return false
        if (!playing) return false
        val moved = reported?.let { at -> heardAt?.let { at + (now - it) } }
        val at = listOfNotNull(moved, polled).maxOrNull() ?: return false
        return length - at <= timings.stuckEndMillis
    }

    /**
     * Inside a served gap's window, or past it as far as the gap watch's seeks go (L104), by where the play should be at
     * [now] (while it plays, its last word moved on since, else where it was last heard or polled), or, while it plays,
     * by the device's own last word. A device standing still near the watch's furthest target answers there, but its word
     * moved on runs past that between two asks; asked by where it should be alone, it would be asked no more and never
     * heard standing. One that plays on answers past it, and is asked no more.
     */
    private fun atAGap(now: Long, gaps: List<LongRange>): Boolean {
        if (gaps.isEmpty()) return false
        val size = size?.takeIf { it > 0 } ?: return false
        val length = length ?: return false
        val moved = if (playing) reported?.let { at -> heardAt?.let { at + (now - it) } } else null
        val should = listOfNotNull(moved, positionMs).maxOrNull()
        val own = reported?.takeIf { playing }
        return listOfNotNull(should, own).any { at -> gaps.any { at in CastGapWatch.watched(it, size, length, timings) } }
    }

    private fun end(why: Why?, served: ServeCounters, now: Long): CastEnd {
        val at = positionMs ?: 0
        return when (why) {
            null -> CastEnd(CastEnd.How.Replaced, at)
            Why.Finished -> {
                val length = length
                if (length == null || length - at <= timings.finishMarginMillis) CastEnd(CastEnd.How.Watched, length ?: at) else CastEnd(CastEnd.How.Stopped, at)
            }
            Why.SessionEnded -> if (pausedSince?.let { now - it >= timings.longPauseMillis } == true) CastEnd(CastEnd.How.LongPause, at) else CastEnd(CastEnd.How.Stopped, at)
            Why.Failed, Why.LostStream -> CastEnd(CastEnd.How.Failed, at, providers = served.upstreamFailures > 0)
            else -> CastEnd(CastEnd.How.Stopped, at)
        }
    }
}

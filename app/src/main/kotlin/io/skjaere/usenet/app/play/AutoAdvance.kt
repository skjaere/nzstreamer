package io.skjaere.usenet.app.play

import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKind
import kotlin.math.ceil
import kotlin.math.max

/**
 * The next-episode card's decisions, kept apart from the player so they can be tested: when the
 * card shows, how its countdown runs, when the next episode's release search starts and when to
 * move on. PlayerActivity feeds it what the player reports on every tick and does what it says.
 * One instance per player setup, made once the next episode is known.
 *
 * The card shows in the last [cardBeforeEndMs] of the file, or once playback has ended; when the
 * file's credits are known, from their start. Its countdown runs only in those last seconds (or
 * once ended), so nothing is cut short: wall-clock time from when they began, held while the user
 * has paused (not once the file has ended), and while the card is out of sight, ended or not.
 * With auto-play off, or when the search found nothing, there is no countdown and only "Play now"
 * moves on.
 *
 * The search starts once the episode counts as watched, but no earlier than
 * [PREFETCH_BEFORE_END_MS] before the end, since the coordinator hands its result on for only ten
 * minutes; or as the card shows, if that comes first. It runs again as the card shows, and while
 * it shows (a pause in the credits), when what it found is no longer held, so the card never calls
 * ready a release the play will search for again. While the card waits on the user (paused,
 * stalled on an error or buffering, at the end with no countdown, or out of sight) that happens
 * once at most: a later lapse is searched for as playback goes on, so a long pause asks the
 * indexers once, not every ten minutes. Nor does it run again after a search whose result is not
 * held (an indexer failed). Whenever nothing searches and nothing found is held, the card says the
 * play searches again rather than that it is looking.
 */
class AutoAdvance(
    private val autoPlayNext: Boolean,
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
    /** How long before the end the card shows, and how long its countdown runs: the app's own, shorter only in tests. */
    private val cardBeforeEndMs: Long = CARD_BEFORE_END_MS,
    private val countdownMs: Long = COUNTDOWN_MS,
) {
    /**
     * What the player reports. [durationMs] is zero or less while unknown; [paused] is the user's
     * pause; [creditsStartMs] is where the file's credits start, when known; [stalled] says the
     * player is not paused but not playing either: stopped on an error, or buffering. [hidden]
     * says the card can't be seen or reached (the audio and subtitles sheet covers it): the card
     * waits on the user meanwhile.
     */
    data class Playback(
        val positionMs: Long,
        val durationMs: Long,
        val paused: Boolean,
        val ended: Boolean,
        val creditsStartMs: Long? = null,
        val stalled: Boolean = false,
        val hidden: Boolean = false,
    )

    /**
     * The card as it shows now. [secondsLeft] and [progress] (1 down to 0) are null when nothing
     * counts down; [autoPlays] says the next episode will start by itself, as it does from the
     * credits on before the countdown begins. [prefetch] is null when nothing searches and what was
     * found is not held: the play searches again.
     */
    data class Card(val secondsLeft: Int?, val progress: Float?, val prefetch: Prefetch?, val autoPlays: Boolean = secondsLeft != null)

    /**
     * What [update] decided: [advance] is true at most once, [startPrefetch] once and again each
     * time the card shows with no search, or the release found stops being held while it shows
     * (once at most while the card waits on the user, then again as playback goes on).
     */
    data class Step(val card: Card?, val startPrefetch: Boolean = false, val advance: Boolean = false)

    /** The episode playing, by TMDB numbers. */
    data class Episode(val showTmdbId: Int, val season: Int, val episode: Int)

    private var prefetchStarted = false
    private var stopped = false
    private var advanced = false
    private var showing = false
    /** Countdown left while it runs, in the last seconds of the file; null before. */
    private var remainingMs: Long? = null
    private var lastTick = 0L
    /** Whether the last update was told of a release found, and so still held for the next play. */
    private var releaseHeld = false
    /**
     * A release found stopped being held while the card waited on the user, after a search had
     * started meanwhile: it is searched for as playback goes on.
     */
    private var searchOwed = false
    /** Whether a search has started since the card began waiting on the user. */
    private var searchedWhileWaiting = false

    /**
     * [prefetch] is the next episode's search as the player knows it: null when none is running or
     * holds a result, including a release found too long ago to be handed on by the end of the countdown.
     */
    fun update(playback: Playback, prefetch: Prefetch?): Step {
        if (stopped || advanced) return Step(null)
        val t = now()
        val known = playback.durationMs > 0
        val lastSeconds = playback.ended || known && playback.durationMs - playback.positionMs <= cardBeforeEndMs
        val credits = known && playback.creditsStartMs?.let { it < playback.durationMs && playback.positionMs >= it } == true
        val appeared = (lastSeconds || credits) && !showing
        showing = lastSeconds || credits
        val counting = autoPlayNext && prefetch !is Prefetch.NothingFound
        val remaining = remainingMs
        remainingMs = when {
            !lastSeconds -> null
            remaining == null -> countdownMs
            counting && !playback.hidden && !(playback.paused && !playback.ended) -> remaining - (t - lastTick)
            else -> remaining
        }
        lastTick = t
        val nearEnd = known && playback.positionMs >= prefetchFrom(playback.durationMs)
        // Nothing moves on until the user does something: paused, stalled, ended with no countdown,
        // or the card out of sight.
        val waiting = playback.hidden || if (playback.ended) !counting else playback.paused || playback.stalled
        if (!waiting) searchedWhileWaiting = false
        // Once each time the card shows without a search, or what it found is no longer held; while
        // it waits on the user, once at most, and a later lapse as playback goes on. Not when a
        // search ends with nothing held (an indexer failed): that would search on and on.
        val lapsed = prefetch == null && showing && (releaseHeld || searchOwed)
        val startPrefetch = prefetch == null && (appeared || !prefetchStarted && (showing || nearEnd)) || lapsed && !(waiting && searchedWhileWaiting)
        if (startPrefetch) {
            prefetchStarted = true
            if (waiting) searchedWhileWaiting = true
        }
        searchOwed = lapsed && !startPrefetch
        releaseHeld = prefetch is Prefetch.Ready
        // With no search running or starting, nothing found is held: the play searches again.
        val status = prefetch ?: Prefetch.Searching.takeIf { startPrefetch }
        if (!showing) return Step(null, startPrefetch)
        // From the credits on, until the last seconds: the card without a countdown.
        val left = remainingMs ?: return Step(Card(null, null, status, autoPlays = counting), startPrefetch)
        if (counting && left <= 0) {
            advanced = true
            return Step(null, startPrefetch, advance = true)
        }
        val card = if (counting) {
            Card(ceil(left / 1000.0).toInt(), left.toFloat() / countdownMs, status)
        } else {
            Card(null, null, status)
        }
        return Step(card, startPrefetch)
    }

    /** "Play now": true when the caller should start the next episode, which happens once at most. */
    fun playNow(): Boolean {
        if (stopped || advanced || !showing) return false
        advanced = true
        return true
    }

    /** "Stop", "Close" or Back on the card: hides it for the rest of this episode, and nothing advances. */
    fun stop() {
        stopped = true
        showing = false
        remainingMs = null
    }

    companion object {
        const val CARD_BEFORE_END_MS = 10_000L
        const val COUNTDOWN_MS = 10_000L
        /** The search starts no earlier than this before the end, so its result is still held at the end. */
        const val PREFETCH_BEFORE_END_MS = 5 * 60_000L

        /** Where the next episode's search starts: where this one counts as watched, or later in a long one. */
        private fun prefetchFrom(durationMs: Long) = max(durationMs * LibraryRepository.WATCHED_FRACTION, (durationMs - PREFETCH_BEFORE_END_MS).toDouble())

        /**
         * The TMDB episode [launch] plays, which can lead on to the next one. Null for movies,
         * releases played without TMDB numbers and opened NZB files (no launch).
         */
        fun episodeOf(launch: PlayerLaunch?): Episode? {
            val w = launch?.watch?.takeIf { it.kind == WatchKind.Episode } ?: return null
            return Episode(w.showTmdbId ?: return null, w.season ?: return null, w.episode ?: return null)
        }
    }
}

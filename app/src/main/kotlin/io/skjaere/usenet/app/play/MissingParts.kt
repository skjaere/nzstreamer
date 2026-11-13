package io.skjaere.usenet.app.play

/**
 * What a play says of the parts of its release no provider has, which the player skips (the stream fills them with
 * zeros, and the player's extractors go past them), kept apart from the player so it can be tested. One per launch: a
 * new release starts a new count. The player feeds it the parts its streams find and the jumps its extractors make, and
 * the position on every tick, and shows what it says.
 *
 * Counting (W8): each skipped spot counts once, missing articles side by side merged into one, by its place in the
 * film, so a spot the stream finds ahead counts as it is found; one found again (a seek back, a stream opened again)
 * doesn't. The prompt ("This release is missing parts (3 so far)") comes once three spots lie within ten minutes of
 * film, saying the count as it came (TalkBack reads it once). Keep watching asks again at six, then at twelve, in the
 * whole play, and never after; answered with more found meanwhile, at the first of those past them.
 *
 * Unanswered (W7), the prompt hides after [answerForMs] of playback, as Keep watching; with no other release to offer
 * it is the count alone and hides after [countOnlyForMs]. Playback, not the clock: pauses, loading and stalls don't use
 * it up, nor the time it waits for the slot (another overlay holds it) or spends under a side panel ([update]'s waiting).
 *
 * The notice ("A missing part was skipped") comes as playback gets to where a jump past a part went on from, which a
 * seek that lands inside a part gets to as well: once a jump, and not while the prompt shows, which says more.
 */
class MissingParts(
    /** Milliseconds from a monotonic clock. */
    private val now: () -> Long,
    /** How long the prompt shows unanswered: the app's own, shorter only in tests. */
    private val answerForMs: Long = ANSWER_FOR_MS,
    /** How long the count alone shows. */
    private val countOnlyForMs: Long = COUNT_ONLY_FOR_MS,
) {
    /** The prompt as it shows: how many spots so far, and whether it offers another release (else the count alone). */
    data class Prompt(val count: Int, val tryAnother: Boolean)

    /** What shows after a tick: the prompt, or null; whether the notice is due; and whether the prompt hid unanswered at it. */
    data class Shown(val prompt: Prompt?, val notice: Boolean, val timedOut: Boolean = false)

    /** A skipped spot: a stretch of the file, its articles side by side merged, and where it starts in the film. */
    private class Spot(val bytes: LongRange, val atMs: Long)

    /** Apart from each other, by their bytes. */
    private val spots = mutableListOf<Spot>()

    /** Which ask is next: the first (three within ten minutes), then [AGAIN_AT]'s in turn; past them, none. */
    private var ask = 0

    private var shown: Prompt? = null

    /** Playback since the prompt showed: the position's progress, no faster than the clock's. */
    private var playedMs = 0L
    private var lastPositionMs = 0L
    private var lastTick = 0L

    /** Where each jump the player made went on from, in the film, by the stretch it went past: until playback gets there. */
    private val jumps = mutableMapOf<LongRange, Long>()

    /** Spots so far. */
    val count: Int get() = spots.size

    /** Counts [bytes], a missing stretch of the file at [atMs] in the film; false when it was one, or part of one, already. */
    fun found(bytes: LongRange, atMs: Long): Boolean {
        if (bytes.isEmpty()) return false
        val touching = spots.filter { it.bytes.first <= bytes.last + 1 && bytes.first <= it.bytes.last + 1 }
        if (touching.size == 1 && bytes.first >= touching[0].bytes.first && bytes.last <= touching[0].bytes.last) return false
        spots -= touching.toSet()
        val from = minOf(bytes.first, touching.minOfOrNull { it.bytes.first } ?: bytes.first)
        val to = maxOf(bytes.last, touching.maxOfOrNull { it.bytes.last } ?: bytes.last)
        spots += Spot(from..to, minOf(atMs, touching.minOfOrNull { it.atMs } ?: atMs))
        spots.sortBy { it.bytes.first }
        return touching.isEmpty()
    }

    /** The player went past [bytes], going on at [toMs] in the film: the notice comes as playback gets there. */
    fun jumped(bytes: LongRange, toMs: Long) {
        jumps[bytes] = toMs
    }

    /**
     * One tick of the player's, at [positionMs]: what shows. [waiting]: the slot is another overlay's, or a side panel
     * covers it, and the prompt's time doesn't run. [tryAnother]: whether another release can be tried.
     */
    fun update(positionMs: Long, waiting: Boolean, tryAnother: Boolean): Shown {
        val t = now()
        var notice = false
        jumps.entries.removeAll { (_, toMs) ->
            when {
                positionMs < toMs - REACHED_WITHIN_MS -> false
                // Got there; much later (a seek far past it), it is long gone.
                else -> true.also { notice = positionMs < toMs + NOTICE_LATE_MS }
            }
        }
        var timedOut = false
        val prompt = shown
        if (prompt == null) {
            if (due()) {
                shown = Prompt(count, tryAnother)
                playedMs = 0
            }
        } else {
            if (!waiting) playedMs += minOf(positionMs - lastPositionMs, t - lastTick).coerceAtLeast(0)
            if (playedMs >= if (prompt.tryAnother) answerForMs else countOnlyForMs) {
                keepWatching()
                timedOut = true
            } else {
                shown = prompt.copy(tryAnother = tryAnother)
            }
        }
        lastPositionMs = positionMs
        lastTick = t
        return Shown(shown, notice && shown == null, timedOut)
    }

    /**
     * Asks now, whatever the count: a Cast device stuck at a missing part can't play on past it (L104). As any ask, Keep
     * watching then asks next at the first threshold past the count.
     */
    fun askNow(tryAnother: Boolean) {
        shown = Prompt(count, tryAnother)
        playedMs = 0
    }

    /** Keep watching, Back, or the prompt's time up: it hides, and asks next at the first threshold past the count. */
    fun keepWatching() {
        shown = null
        ask = (1..AGAIN_AT.size).firstOrNull { AGAIN_AT[it - 1] > count } ?: (AGAIN_AT.size + 1)
    }

    /** Whether the prompt's next ask is due. */
    private fun due(): Boolean = when (ask) {
        0 -> spots.map { it.atMs }.sorted().windowed(FIRST_AT).any { it.last() - it.first() <= WINDOW_MS }
        in 1..AGAIN_AT.size -> count >= AGAIN_AT[ask - 1]
        else -> false
    }

    companion object {
        /** How long the prompt shows unanswered (W7). */
        const val ANSWER_FOR_MS = 20_000L

        /** How long the count alone shows, with no other release to offer. */
        const val COUNT_ONLY_FOR_MS = 5_000L

        /** The first ask: this many spots within [WINDOW_MS] of film. */
        private const val FIRST_AT = 3
        private const val WINDOW_MS = 10 * 60_000L

        /** Asks after Keep watching, by spots in the whole play. */
        private val AGAIN_AT = listOf(6, 12)

        /**
         * How near where a jump went on from playback must be to have got there: the sound may go on from its sample just
         * before the picture's keyframe.
         */
        private const val REACHED_WITHIN_MS = 500L

        /** How far past it playback may be and still have got there by the jump (a tick later, a slow one), not a seek far past. */
        private const val NOTICE_LATE_MS = 5_000L
    }
}

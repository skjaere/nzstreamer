package io.skjaere.usenet.app.link

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.link.LinkLimits

/** Play on TV's timings (L46): the design's. Device tests shrink them, as SetupLimits is shrunk. */
data class PlayOnTimings(
    /** The TV's question, "Pair with Galaxy phone?", closes after this unanswered (L22). */
    val questionMillis: Long = 60_000,
    /** Pairing closes by itself this long after Pair a phone opened, or after its last ceremony ended (L22). */
    val pairingMillis: Long = 600_000,
    /** After a failed ceremony the next commit waits this long, doubled for each failure in a row (LinkLimits.pairingPauseMillis). */
    val pairingPauseMillis: Long = 2_000,
    /** How long a passing line is said: a phone's "Stopped on Living room TV at 32:10" (item 27), a Cast play's lines (L93). */
    val noticeMillis: Long = 4_000,
    /** How long the line "Watched on Kitchen display" stays with its next episode's button (W65, L125). */
    val nextMillis: Long = 10_000,
    /** A phone's Cancel of a start on its TV: after this unanswered, "<TV> didn't answer. It may still start there." (item 24). */
    val cancelAnswerMillis: Long = 5_000,
    /** A start on a TV unheard this long, its link down: "Lost the connection to <TV>" (item 24). */
    val silenceMillis: Long = 20_000,
    /** The Playing on bar's link lost this long, the app in front: "Can't reach <TV>", with × to put it away (W15). */
    val cantReachMillis: Long = 120_000,
    /**
     * As the app comes to the front, how long a phone holds the links of the TVs it started plays on, for what they owe it
     * (W5, L33, L36): their Finals, and a play there still in one of its sessions.
     */
    val sessionLinkMillis: Long = 30_000,
) {
    companion object {
        /** A test seam: the listener reads it as it binds, the page as it counts down. */
        @VisibleForTesting
        @Volatile
        var current = PlayOnTimings()
    }
}

/** The timings now: the design's, or a test's ([PlayOnTimings.current]). */
internal fun playOnTimings(): PlayOnTimings = PlayOnTimings.current

/** The TV listener's limits with the timings now: its question's and its pairing's times and pause, the rest the design's. */
internal fun pairingLimits(): LinkLimits = playOnTimings().let {
    LinkLimits(questionMillis = it.questionMillis, pairingMillis = it.pairingMillis, pairingPauseMillis = it.pairingPauseMillis)
}

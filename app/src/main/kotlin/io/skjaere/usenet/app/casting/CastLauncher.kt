package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SessionMetadata

/**
 * What starts a cast play on its device (L65, L85), from castExtras: the service's start as the play is asked for, the
 * load once the phone has the release ready, and the stop. The coordinator only hands things over: each returns at once,
 * and what comes of a load comes later, through its [CastLoad].
 */
interface CastLauncher {
    /**
     * A cast play to [cast] is asked for, from a tap in the app, so the app is in front: the service starts, saying
     * [metadata] until the device plays (L63, L85). A play still starting before it is given up for this one, its load on
     * the device stopped too, while one the device plays goes on until this one's load (L84). False if Android refused the
     * start: the play then fails before anything is searched (L97). On the main thread.
     */
    fun starting(cast: CastTo, metadata: SessionMetadata): Boolean

    /** Hands [launch] to the device it names (L65), at once: the device loads it while the play sheet waits on the outcome. */
    fun load(launch: PlayerLaunch): CastLoad

    /**
     * The play [starting] began ends without playing there: Cancel (L82), a failure, or nothing to load, the release it
     * left still playing there (L84). What it loaded stops on the device and its address is revoked (L55), and the
     * service stops, unless a play loaded before it still plays there. The Cast session lives on, and the device stays
     * chosen.
     */
    fun stop()
}

/** A load handed to the device (L65). */
fun interface CastLoad {
    /** Waits for how it came out: the device plays it, or it failed. */
    suspend fun outcome(): CastLoadOutcome
}

/** How a load came out (L65): the device reports it PLAYING, or it failed (L83). */
sealed interface CastLoadOutcome {
    data object Playing : CastLoadOutcome

    data class Failed(val failure: CastLoadFailure) : CastLoadOutcome
}

/** How a cast play failed (L83; CsPhoneStarting): each kind has its own words and buttons ([CastStart]). */
enum class CastFailKind {
    /** The device's player failed after it fetched the file: another release may play, this phone plays this one. */
    CouldNotPlay,

    /** Nothing from the device reached this phone (L88): the same release again may, once the Wi-Fi allows it (W39). */
    CannotReach,

    /** A file type the device can't open: refused before anything was sent (L57), or by the device as it opened (L102). */
    FileType,

    /** Android refused the service's start, so nothing was searched (L85, L97); never a load's. */
    NotStarted,

    /**
     * Every release tried is one the device can't fully play, by the cast's verdict on its tracks (L129, L149), found before
     * anything was sent to it; never a load's. Cast anyway sends it the best of them all the same.
     */
    NotFully,
}

/**
 * Why a cast play failed (L83, L88, L102), as the device and the phone's server tell it: its [kind], and the words after ✕
 * on its card ([reason]: none for can't reach, whose step says no request came). [format]: a decoding or format error,
 * which adds that Cast devices play fewer kinds of video. [providers]: the providers failed the release, not the device,
 * so Try another release forgets it as the release Resume plays, as for a release that failed here. [sessionEnded]: the
 * Cast session ended with it (L60), so the device is no longer chosen (W36) and neither Try again nor Try another
 * release is offered. [releases]: how many releases the verdict found the device can't fully play ([CastFailKind.NotFully]).
 */
data class CastLoadFailure(
    val kind: CastFailKind,
    val reason: String? = null,
    val format: Boolean = false,
    val providers: Boolean = false,
    val sessionEnded: Boolean = false,
    val releases: Int = 0,
)

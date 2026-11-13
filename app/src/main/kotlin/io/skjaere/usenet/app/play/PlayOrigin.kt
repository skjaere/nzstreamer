package io.skjaere.usenet.app.play

import io.skjaere.usenet.data.LibraryRepository
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A play a paired phone asked for over Play on TV (L33): its [session], and the phone, by its link key's id ([phone], 64
 * hex digits) and its own [name], cleaned (L28). The launch keeps it ([PlayerLaunch.remote]), so the player knows whose
 * play it is and hands it on with the next episode. It prints as its kind alone: a phone's name reaches no log by a slip.
 */
@Serializable
data class RemotePlay(val session: String, val phone: String, val name: String) {
    override fun toString() = "RemotePlay"
}

/**
 * A play cast to the Google Cast device at MediaRouter's [route], named [name] (L81): the phone runs the play and serves
 * the file, the device shows it. The launch keeps it ([PlayerLaunch.cast]), so a retry stays on the device. The device's
 * Cast id ([device]) and [model] go with it (L153), so the play's job finds the device's profile; null where its route
 * said none, and in a launch kept before them. It prints none of them: a device's name reaches no log by a slip.
 */
@Serializable
data class CastTo(val route: String, val name: String, val device: String? = null, val model: String? = null) {
    override fun toString() = "CastTo"
}

/**
 * The play the player in front was playing as another took its place (L18): its [title], where it stopped, and whether
 * Resume goes on from there ([resumable]).
 */
data class StoppedPlay(val title: String, val positionMs: Long, val resumable: Boolean = true) {
    companion object {
        /**
         * [title]'s play, stopped at [positionMs] of [durationMs] (null: not known yet); [kept]: its place is saved as the
         * player leaves (it has a launch; a file opened in Import has none). Resume goes on from there only for a kept place
         * past a minute and short of watched, as the player saves it ([LibraryRepository.recordProgress]). Null under a
         * minute in (as before its player was made, at 0): nothing to tell of it.
         */
        fun of(title: String, positionMs: Long, durationMs: Long?, kept: Boolean): StoppedPlay? {
            if (positionMs < LibraryRepository.MIN_RESUME_MS) return null
            val resumable = kept && durationMs != null && durationMs > 0 && positionMs < durationMs * LibraryRepository.WATCHED_FRACTION
            return StoppedPlay(title, positionMs, resumable)
        }
    }
}

/**
 * Where a play was asked for (L33, L39), kept with the coordinator's job in its coroutine context, so every request built
 * inside it (MoviePlay's remembered and search requests, the search's and its way back, a season pack's) makes a launch
 * that carries it without a copy on [PlayRequest]. [remote]: a paired phone's play, whose session its launches keep;
 * null for a play asked for here. [start]: where the phone's place says it starts (L39; 0 from the beginning), null for
 * the place saved here, as any play; a mid-stream retry's own place wins over it ([launchOf]). [stopped]: the play a
 * phone's play took the player from, which its sheet names. [cast]: the Google Cast device the play goes to (L80, L81),
 * null for one that plays on this device.
 */
data class PlayOrigin(val start: Long? = null, val remote: RemotePlay? = null, val stopped: StoppedPlay? = null, val cast: CastTo? = null) :
    AbstractCoroutineContextElement(PlayOrigin) {
    companion object Key : CoroutineContext.Key<PlayOrigin> {
        /** A play asked for on this device. */
        val Here = PlayOrigin()

        /**
         * A play the player of [launch] hands on (auto-advance, Previous and Next, a hand-over, Try another, Try a smaller
         * release): in its session, if a phone started it, on its Cast device, if it was cast (L81), and from the next
         * play's own place, not the phone's.
         */
        fun after(launch: PlayerLaunch?): PlayOrigin = PlayOrigin(remote = launch?.remote, cast = launch?.cast)
    }
}

/**
 * What the play sheet says of a play a phone asked for (PtTvIncoming): under its title, by the phone icon, "Started from
 * Galaxy phone", or "Galaxy phone is waiting for you to choose" while it asks which file plays; and, after a hand-over,
 * what stopped for it.
 */
data class StartedFrom(val phone: String, val stopped: StoppedPlay? = null) {
    val line: String get() = "Started from $phone"

    val waiting: String get() = "$phone is waiting for you to choose"

    /** "The Glass Orchard stopped at 1:02:14. Resume goes on from there.", the promise only where Resume keeps it. */
    val stoppedLine: String?
        get() = stopped?.let { "${it.title} stopped at ${clockTime(it.positionMs)}." + if (it.resumable) " Resume goes on from there." else "" }

    companion object {
        /** The sheet's words for [origin]'s play: none for a play asked for here. */
        fun of(origin: PlayOrigin): StartedFrom? = origin.remote?.let { StartedFrom(it.name, origin.stopped) }
    }
}

/** A position as the player's clock shows it: "32:10", "1:02:14". */
internal fun clockTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
}

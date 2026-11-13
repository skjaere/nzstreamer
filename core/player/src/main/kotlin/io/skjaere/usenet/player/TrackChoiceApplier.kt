package io.skjaere.usenet.player

import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

/**
 * Plans a player's audio and subtitles ([TrackPlanner.plan]) once, when it reports its first
 * track list. Later track changes on that player leave the selection alone, so what the sheet
 * picks stays; a new player (PlayerActivity builds one in every onStart) starts from fresh track
 * selection and is planned again, with the choices made so far. So is a player that rates its
 * audio tracks again (a TV's, as a receiver is switched on or off: PlayerFactory's
 * followAudioRoute), as the track pinned for the old route would stay; the sheet's picks still
 * come first. [apply] puts the plan on the player ([TrackSelection.apply]). One debug line per
 * plan says what the file had (each track as the sheet shows it, with its own name and flags),
 * where each part came from and what plays.
 */
class TrackChoiceApplier<P : Any>(private val apply: (player: P, plan: TrackPlan) -> Unit) {
    private val log = LoggerFactory.getLogger(TrackChoiceApplier::class.java)

    // Weak, so a released player isn't kept alive while the activity is stopped.
    private var appliedTo = WeakReference<P>(null)

    /** Which audio tracks played as the last plan was made. */
    private var playableAudio: List<Boolean> = emptyList()

    /** Called with each track list [player] reports; its first one gets the plan, which is returned, and so does one that rates its audio again. */
    fun onTracksChanged(player: P, options: List<TrackOption>, sheet: TrackChoice, show: TrackChoice?, preferred: List<String>, rule: Boolean): TrackPlan? {
        if (appliedTo.get() === player && audioPlayable(options) == playableAudio) return null
        return planAgain(player, options, sheet, show, preferred, rule)
    }

    /** Plans [player] now, whatever it had: "Reset to my defaults". */
    fun planAgain(player: P, options: List<TrackOption>, sheet: TrackChoice, show: TrackChoice?, preferred: List<String>, rule: Boolean): TrackPlan {
        appliedTo = WeakReference(player)
        playableAudio = audioPlayable(options)
        val plan = TrackPlanner.plan(options, sheet, show, preferred, rule)
        log.debug("Tracks: {}", plan.describe(options))
        apply(player, plan)
        return plan
    }

    private fun audioPlayable(options: List<TrackOption>) = options.filter { it.isAudio }.map { it.playable }
}

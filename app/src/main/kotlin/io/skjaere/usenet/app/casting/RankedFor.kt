package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.releases.RankingTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Releases ranked for the Cast device [name]d, by what its [profile] says it plays (L126, L128): what a search, a release
 * list and a play's job rank by while Play goes there. It prints without its name.
 */
data class CastRanking(val name: String, val profile: CastProfile) {
    /** Whom the ranker ranks for: the device, by the name its chips say. */
    val target: RankingTarget.Cast get() = RankingTarget.Cast(name)

    override fun toString() = "CastRanking"
}

/**
 * Whom releases are ranked for (L127): inside a play's job ([origin], its PlayOrigin), the job's own device, its Cast device
 * or this one for a play here, whatever is chosen meanwhile; else the Cast device chosen in Play on ([target]: connecting,
 * connected or reconnecting), as its session's route said it is (L153); else this device, null (This phone, or a TV,
 * which searches for itself). [profiles] give a device's profile, with what it has shown.
 */
fun rankingTargetOf(origin: PlayOrigin?, target: PlayTarget, profiles: CastProfiles): CastRanking? = when {
    origin != null -> origin.cast?.let { CastRanking(it.name, profiles.of(it.device, it.model)) }
    target is PlayTarget.Cast -> CastRanking(target.name, profiles.of(target.device?.id, target.device?.model))
    else -> null
}

/** Whom a list on screen ranks for: a Cast device ([cast]), or this device (null). It prints without the device's name. */
data class RankFor(val cast: CastRanking?)

/**
 * Whom a release list on screen ranks for again as a choice is made in Play on (L127): the choice made last on purpose
 * ([picked]), once Play goes there ([target]): the Cast device as its session says it is, or this device for This phone or
 * a TV. Null while Play hasn't caught up with the choice, and while This phone is back by itself (the session ended,
 * failed or went to another device, W36), the moment before it too, when the device is still chosen with no session:
 * the list keeps its order, its chips and its "Ranked for" line until it is next opened or searched again, so nothing
 * re-ranks under a finger.
 */
fun listRankingOf(picked: PlayChoice, target: PlayTarget, profiles: CastProfiles): RankFor? = when (picked) {
    // Its session's facts come with the session, from its connecting on (L153): none, none to rank by yet, or any more.
    is PlayChoice.Cast -> (target as? PlayTarget.Cast)?.takeIf { it.route == picked.route && it.device != null }?.let { RankFor(rankingTargetOf(null, it, profiles)) }
    PlayChoice.Phone, is PlayChoice.Tv -> RankFor(null)
}

/**
 * Play on's cue for the release lists on screen (L127): whom each ranks for again ([listRankingOf]) as a choice is made
 * ([picked]) and Play goes there ([target]), by the Cast device's profile with what it has shown by then ([devices], read
 * as it is chosen). Only a choice cues: what the device shows meanwhile, or its session suspended and back, ranks no list
 * on screen again, so nothing re-ranks under a finger. A list compares the cue with how it ranked, so one it already
 * follows changes nothing there.
 */
fun listRankings(picked: Flow<PlayChoice>, target: Flow<PlayTarget>, devices: Flow<List<CastDeviceMemory>>): Flow<RankFor> =
    combine(picked, target) { choice, now -> choice to now.asChosen() }
        // Before This phone back by itself goes: a list opened meanwhile ranked for this phone ranks again as the same
        // device is chosen once more.
        .distinctUntilChanged()
        .map { (choice, now) -> listRankingOf(choice, now, CastProfiles(devices.first())) }
        .filterNotNull()

/** Where Play goes as a choice: a Cast device whatever its session's state, which ranks nothing. */
private fun PlayTarget.asChosen(): PlayTarget = if (this is PlayTarget.Cast) copy(link = CastLink.Connected) else this

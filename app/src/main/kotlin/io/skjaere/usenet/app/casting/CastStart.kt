package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.RetryPlan
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.search.RankedItem

// Starting a cast play (L65, L83; CsPhoneStarting): the play sheet's words for a cast, and what each failure offers. The
// coordinator runs the play as it runs one here; only the last step differs, and this decides it, where a JVM test reads it.

/** Under the sheet's title while a cast play starts, by Google's filled Cast icon. */
fun castingTo(name: String) = "Casting to $name"

/** The sheet's last step for a cast: from the hand-over until the device plays, or fails. */
fun startingOn(name: String) = "Starting on $name"

/** The minimised start's line: "On Kitchen display · Fetching NZB…". */
fun onDevice(name: String, step: String) = "On $name · $step"

/** The minimised start's step once the device has it. */
const val CAST_STARTING = "Starting…"

/** The minimised start's line once it failed: "Couldn't play Harbor Lights on Kitchen display". */
fun couldNotPlayOn(title: String, name: String) = "Couldn't play $title on $name"

fun couldNotPlayThis(name: String) = "$name couldn't play this release"

/** Under it, for a decoding or format error. */
const val CAST_FORMAT_NOTE = "This phone plays it, but Cast devices play fewer kinds of video. A 1080p H.264 release plays on most."

fun cannotReach(name: String) = "$name can't reach this phone"

fun sameWifi(name: String) = "Is this phone on the same Wi-Fi as $name? A VPN on the phone, or a guest network, can block it."

/** The last step's words when nothing came from the device in its 30 s (W34). */
const val CAST_NO_REQUEST = "no request came"

fun fileTypeCannotPlay(name: String) = "This file type can't play on $name"

/** Under a file type refused by the phone's own check (L57), which sent the device nothing. */
fun checkedBefore(name: String) = "Checked before anything was sent to $name."

/** An automatic pick's notice as it moves on from a release whose sound the device can't play (L149). */
fun noSoundOn(name: String) = "No sound on $name"

/** An automatic pick's notice as it moves on from a release whose video shows no picture on the device. */
fun noPictureOn(name: String) = "No picture on $name"

/** Over the cards of every release a play tried, none of which plays fully on the device (L149): one release is said as one. */
fun noneFully(name: String, releases: Int) =
    if (releases == 1) "This release doesn't play fully on $name" else "None of these releases plays fully on $name"

/** Android refused the service's start (L85, L97). */
const val CAST_NOT_STARTED = "Couldn't start casting. Try again with NZStreamer open."

/** The phone's server couldn't listen again at its address, or the device lost its connection to it (L74, L88). */
fun lostTheStream(name: String) = "$name lost the stream from this phone"

/** A failure's buttons, in their order: the first is the sheet's main one. */
enum class CastButton(val words: String) {
    TryAnother("Try another release"),
    TryAgain("Try again"),
    PlayHere("Play on this phone"),
    CastAnyway("Cast anyway"),
    Close("Close"),
}

/** What a failure's button asks of the coordinator (L83). */
sealed interface CastAction {
    /**
     * The play's request again without [launch]'s release, on the same device, from where it was to start ([positionMs]);
     * [forget]: it is no longer the release Resume plays, where the providers failed it. A device's failure leaves it:
     * this phone plays it.
     */
    data class TryAnother(val launch: PlayerLaunch, val positionMs: Long?, val forget: Boolean) : CastAction

    /** The sheet closes onto the release list the release was chosen on, which says why it didn't play. */
    data object BackToList : CastAction

    /** [launch] loaded again on the same device (W39). */
    data class CastAgain(val launch: PlayerLaunch) : CastAction

    /**
     * This phone chosen, which ends the cast (L85), and [launch], made for the device, played here instead; with none
     * (nothing reached the device), the play asked for again, here.
     */
    data class PlayHere(val launch: PlayerLaunch?) : CastAction

    /**
     * Cast anyway (L149, Terms 3.4.5): [request], the play's best release alone, to [device] whatever the verdict on its
     * tracks says ([PlayRequest.castAnyway]).
     */
    data class CastAnyway(val request: PlayRequest, val device: CastTo) : CastAction

    data object Close : CastAction
}

/**
 * A cast play that failed (L83), as the play sheet shows it: on [device], for [failure]; [running], the sheet as the device
 * was asked, with its steps and the last one's failure; [launch], what the device was asked to play (none for a file type
 * refused before anything was sent, L57, or a service that didn't start); what Try another release does ([another]); and
 * what Cast anyway plays ([anyway], L149), none where it isn't offered. [here]: false where Play on this phone would ask
 * again for a release this phone's own probe already refused (CFU-1), which would end the cast for a play that fails at
 * once: it isn't offered then.
 */
data class CastFailed(
    val device: CastTo,
    val failure: CastLoadFailure,
    val running: PlayState.Running? = null,
    val launch: PlayerLaunch? = null,
    val another: Another = Another.None,
    val anyway: PlayRequest? = null,
    val here: Boolean = true,
) {
    val kind: CastFailKind get() = failure.kind

    /** What Try another release does: plays the request's next release, goes back to the release list, or isn't offered. */
    enum class Another { Again, BackToList, None }
}

/**
 * A cast failure's words on the play sheet (CsPhoneStarting), each in its place, top to bottom: [where] under the title,
 * in the warning colour by Google's outline Cast icon, and [advice] under it; a [headline]; the releases as cards ([cards],
 * "✕ <reason>" over each release) or the phone's steps ([steps]); a small [note].
 */
data class CastFailedWords(
    val where: String? = null,
    val advice: String? = null,
    val headline: String? = null,
    val cards: Boolean = false,
    val steps: Boolean = false,
    val note: String? = null,
)

/**
 * The decisions of a cast play's start (L65, L83, L84), which the coordinator only wires: where a launch goes, the sheet
 * while the device loads it, the sheet and the release list's outcome once it plays or fails, each failure's words and
 * buttons, and what each button asks for.
 */
object CastStart {
    /** Where a launch goes (L65). */
    sealed interface Handover {
        /** This phone's player, as for any play here. */
        data object Player : Handover

        /** The Cast device [to]: the launcher loads it, and nothing goes to this phone's player. */
        data class Device(val to: CastTo) : Handover

        /** Nowhere: the release the play left for its missing parts still plays on the device (L84). */
        data object Stays : Handover
    }

    /** Where [launch] goes: its Cast device, unless the play [wentBack] to the release that still plays there; else this phone. */
    fun handover(launch: PlayerLaunch, wentBack: Boolean): Handover {
        val to = launch.cast ?: return Handover.Player
        return if (wentBack) Handover.Stays else Handover.Device(to)
    }

    /**
     * The sheet's line for a play that leaves the release while it still plays (L84, L94, L135; CsPhoneMissingParts 3,
     * CsPhoneSlowCast 3): for a cast, the release playing goes on there until the next one loads, from where it was
     * ([positionMs]); null for a play here, whose player closes for the next.
     */
    fun goesOn(launch: PlayerLaunch, positionMs: Long): String? = launch.cast?.let { goesOnThere(positionMs) }

    /**
     * The sheet's line for [plan], a play that leaves [launch]'s release while it still plays there (L84, L135): Try another
     * release from the missing-parts prompt, or Try a smaller release from the slow-stream banner (C-3: the device plays
     * the release it has until the next LOAD either way). Null for a play here, and after a release that failed, which
     * plays no more.
     */
    internal fun goesOn(launch: PlayerLaunch, plan: RetryPlan): String? {
        val retry = when (plan) {
            is RetryPlan.Again -> plan.retry
            is RetryPlan.SearchEpisode -> plan.retry
            RetryPlan.None -> return null
        }
        if (retry.left == null) return null
        return goesOn(launch, retry.startPositionMs ?: return null)
    }

    /**
     * Play & connect's launch (W45, L124 step 4): the player's [launch], playing here, loaded on the Cast device [to] from
     * [positionMs], where the film paused. The same prepared release, file and request, so nothing is searched, as Try
     * again loads one again (W39).
     */
    fun moving(launch: PlayerLaunch, positionMs: Long, to: CastTo): PlayerLaunch = launch.copy(cast = to, startPositionMs = positionMs)

    /** How a move ends ([moveEnd]): the play sheet's state, and what the player hears. */
    data class MoveEnd(val sheet: PlayState, val outcome: MoveOutcome)

    /**
     * A move's end once its load came out ([outcome], L124 steps 5 and 6): the sheet goes back to Idle whatever came of it,
     * never a failure's sheet, as the player plays on here and says why itself; the player hears it moved, or the failure.
     */
    fun moveEnd(outcome: CastLoadOutcome): MoveEnd = when (outcome) {
        CastLoadOutcome.Playing -> MoveEnd(PlayState.Idle, MoveOutcome.Moved)
        is CastLoadOutcome.Failed -> MoveEnd(PlayState.Idle, MoveOutcome.Failed(outcome.failure))
    }

    /** The sheet as the device is asked (L65): the phone's steps done, then "Starting on Kitchen display" running. */
    fun loading(running: PlayState.Running): PlayState.Running = running.copy(castStep = StepStatus.Running())

    /**
     * The sheet once [launch]'s load came out ([outcome]), [millis] after it was handed over: closed as the device plays;
     * else its failure, over [running]. Try another release is offered for a failure on the device while the play has
     * another release to try ([canTryAnother]).
     */
    fun after(outcome: CastLoadOutcome, launch: PlayerLaunch, running: PlayState.Running, millis: Long, canTryAnother: Boolean): PlayState {
        val failure = (outcome as? CastLoadOutcome.Failed)?.failure ?: return PlayState.Idle
        val device = launch.cast ?: return PlayState.Idle
        // Can't reach has no card: the release list and its step say what happened, as the sheet does.
        val reason = failure.reason ?: cannotReach(device.name)
        val step = StepStatus.Failed(millis, AttemptFailure.Elsewhere(if (failure.kind == CastFailKind.CannotReach) CAST_NO_REQUEST else reason))
        return PlayState.Failed(
            running.title, running.subtitle, listOf(running.release to reason), notTried = 0,
            cast = CastFailed(device, failure, running.copy(castStep = step), launch, if (canTryAnother) CastFailed.Another.Again else CastFailed.Another.None),
        )
    }

    /** What the release list the play was chosen on hears once the sheet says [after] (L65): it played, or why not. */
    fun outcome(after: PlayState): PlayOutcome = (after as? PlayState.Failed)?.let(PlayOutcome::Failed) ?: PlayOutcome.Launched

    /**
     * [failed], a cast play's failure before anything reached [device], made a file type's (L83) when every release tried
     * was of a type a Cast device can't play ([attempts]), none skipped: a release [chosen] by hand goes back to its list
     * for another; an automatic pick has tried them all. [here]: whether Play on this phone, which asks for the play
     * again on this phone, may play, as [notFully] has it. Null for any other failure: one the device can't fully play
     * ([notFully]), or one that shows as a play here's does.
     */
    fun refused(failed: PlayState.Failed, attempts: List<AttemptFailure>, device: CastTo, chosen: Boolean, here: Boolean = true): PlayState.Failed? {
        if (attempts.isEmpty() || failed.missing.isNotEmpty() || attempts.any { it !is AttemptFailure.NotForCast }) return null
        val failure = CastLoadFailure(CastFailKind.FileType, attempts.last().text)
        return failed.copy(cast = CastFailed(device, failure, another = if (chosen) CastFailed.Another.BackToList else CastFailed.Another.None, here = here))
    }

    /**
     * [failed], a cast play's failure before anything reached [device], made the verdict's (L149) when every release tried
     * ([attempts]) is one the device can't fully play (L129), or of a type it can't open beside them, none skipped: Cast
     * anyway then plays the best of the first kind, as the list ranked them, alone on the device ([castAnyway]); [tried]
     * gives each tried release by its id. [here]: whether Play on this phone, which asks for the play again on this phone,
     * may play: false after the pre-check's Play a compatible release for a release picked by hand that this phone's own
     * probe refused, which is what it would ask for. Null for any other failure, which [refused] or a play here's sheet shows.
     */
    fun notFully(
        failed: PlayState.Failed, attempts: List<Pair<PlayCandidate, AttemptFailure>>, device: CastTo, request: PlayRequest, tried: Map<String, RankedItem>,
        here: Boolean = true,
    ): PlayState.Failed? {
        if (failed.missing.isNotEmpty() || attempts.any { (_, f) -> f !is AttemptFailure.CastMiss && f !is AttemptFailure.NotForCast }) return null
        val best = attempts.firstOrNull { it.second is AttemptFailure.CastMiss }?.first?.let { tried[it.id] } ?: return null
        val failure = CastLoadFailure(CastFailKind.NotFully, releases = attempts.size)
        return failed.copy(cast = CastFailed(device, failure, anyway = castAnyway(request, best), here = here))
    }

    /**
     * What Cast anyway plays (L149): [best], alone, as a release chosen by hand is, whatever the verdict on its tracks says
     * there (Terms 3.4.5: no Cast device is blocked); its type is still checked, as no Cast device opens one it can't.
     */
    fun castAnyway(request: PlayRequest, best: RankedItem): PlayRequest =
        request.copy(candidates = listOf(best), chosen = true, rememberedFirst = false, castAnyway = true)

    /** The play of [title] to [device] couldn't start the service (L85, L97): nothing was searched. */
    fun notStarted(title: String, subtitle: String?, device: CastTo): PlayState.Failed = PlayState.Failed(
        title, subtitle, listOf(title to CAST_NOT_STARTED), notTried = 0, cast = CastFailed(device, CastLoadFailure(CastFailKind.NotStarted, CAST_NOT_STARTED)),
    )

    /** [failed]'s words on the sheet. */
    fun words(failed: CastFailed): CastFailedWords {
        val name = failed.device.name
        return when (failed.kind) {
            CastFailKind.CouldNotPlay -> CastFailedWords(headline = couldNotPlayThis(name), cards = true, note = CAST_FORMAT_NOTE.takeIf { failed.failure.format })
            CastFailKind.CannotReach -> CastFailedWords(where = cannotReach(name), advice = sameWifi(name), steps = true)
            // Refused by the device as it opened (L102), its card says so: never that a type it takes can't play.
            CastFailKind.FileType -> CastFailedWords(headline = fileTypeCannotPlay(name), cards = true, note = checkedBefore(name).takeIf { failed.launch == null })
            CastFailKind.NotStarted -> CastFailedWords(advice = CAST_NOT_STARTED)
            // Checked before anything was sent, as a file type is (L149): the cards say what each can't play there.
            CastFailKind.NotFully -> CastFailedWords(headline = noneFully(name, failed.failure.releases), cards = true, note = checkedBefore(name))
        }
    }

    /**
     * Whether the device a cast play failed on ([to]) is still where Play goes ([target]), connected: a failure's sheet
     * offers it more only then (L60, W36). Its session can end while the sheet shows: the receiver closes one whose media
     * never started, another sender takes the device, another device or a TV is chosen.
     */
    fun live(to: CastTo, target: PlayTarget): Boolean = when (target) {
        is PlayTarget.Cast -> target.route == to.route && target.link == CastLink.Connected
        PlayTarget.Phone, is PlayTarget.Tv -> false
    }

    /** [failed]'s buttons, the main one first, its device [live] or not; this phone's where it may play ([CastFailed.here]). */
    fun buttons(failed: CastFailed, live: Boolean = true): List<CastButton> {
        val here = listOfNotNull(CastButton.PlayHere.takeIf { failed.here }, CastButton.Close)
        // The device is no longer chosen (W36): nothing more goes to it from this sheet.
        if (failed.failure.sessionEnded || !live) return here
        return when (failed.kind) {
            CastFailKind.CannotReach -> if (failed.launch != null) listOf(CastButton.TryAgain) + here else here
            CastFailKind.CouldNotPlay, CastFailKind.FileType -> if (failed.another != CastFailed.Another.None) listOf(CastButton.TryAnother) + here else here
            CastFailKind.NotStarted -> here
            // This phone first: the device can't fully play any of them (L149).
            CastFailKind.NotFully -> if (failed.anyway != null) listOfNotNull(CastButton.PlayHere.takeIf { failed.here }, CastButton.CastAnyway, CastButton.Close) else here
        }
    }

    /** What [button] asks for, on [failed]'s sheet. */
    fun press(button: CastButton, failed: CastFailed): CastAction {
        val launch = failed.launch
        return when (button) {
            CastButton.TryAnother -> when {
                launch != null -> CastAction.TryAnother(launch, launch.startPositionMs, forget = failed.failure.providers)
                failed.another == CastFailed.Another.BackToList -> CastAction.BackToList
                else -> CastAction.Close
            }
            CastButton.TryAgain -> launch?.let(CastAction::CastAgain) ?: CastAction.Close
            CastButton.PlayHere -> CastAction.PlayHere(launch?.copy(cast = null))
            CastButton.CastAnyway -> failed.anyway?.let { CastAction.CastAnyway(it, failed.device) } ?: CastAction.Close
            CastButton.Close -> CastAction.Close
        }
    }
}

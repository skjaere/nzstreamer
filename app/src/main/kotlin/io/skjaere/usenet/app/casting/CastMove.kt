package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PlayOnAsk
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.releases.ReleaseParser

// Play & connect in the player (W45, W66, L124; CsPhonePlayer): the player's words while its film moves to a Cast device,
// and what each tap, answer and outcome does there. The player only wires them, and a JVM test reads them here.

/** The player's line while its film moves to a Cast device (L124 step 3; CsPhonePlayer 3): "Moving to Kitchen display…". */
fun movingTo(name: String) = "Moving to $name…"

/** After the reason a move didn't go (L124 steps 1 and 6): the film plays on here. */
const val STILL_PLAYING_HERE = "Still playing here."

/** A connect from the player that failed: refused, or not connected in its 15 s (L149). */
fun couldNotConnect(name: String) = "Couldn't connect to $name"

/** The move's line on the audio a Cast device will play (L124 step 3, W51): "Kitchen display will play the German audio". */
fun willPlayTheAudio(name: String, language: String) = "$name will play the $language audio"

/** How the coordinator's move of the player's film to a Cast device came out (L124). */
sealed interface MoveOutcome {
    /** The device plays it: the player closes, and the remote opens. */
    data object Moved : MoveOutcome

    /** The device didn't play it, for [failure] (C1's kinds and words): nothing more goes there, and This phone is chosen. */
    data class Failed(val failure: CastLoadFailure) : MoveOutcome

    /** Another play took the sheet's place before it came out: that one says what plays where. */
    data object Stopped : MoveOutcome
}

/**
 * Play & connect's decisions in the player (W45, W66, L124; CsPhonePlayer), for a Cast row tapped in its list: a file
 * of a type no Cast device plays, by its name (L57), connects nothing and says so; a speaker or a speaker group asks
 * first (L122), as anywhere; a film the device can't fully play asks the pre-check first (L130, CsPhonePrecheck), over
 * the film, before anything connects; else the film pauses where it is and moves there, the line saying so while it
 * does, with the audio language the device will play where it isn't your first (W51) and the subtitles' line where they
 * show here (W55). Once the device plays it the player closes; a connect that fails, or the device not playing it, has
 * the film play on here, with the reason and "Still playing here." for [LINE_MS].
 */
object CastMove {
    /** How long a line saying why a move didn't go stays: longer than a moment's 1.5 s, as it says more (CsPhonePlayer). */
    const val LINE_MS = 6_000L

    /** A line under the player's top bar: its [words], what TalkBack reads ([spoken]), and how long it stays ([forMs]; null: until the move ends). */
    data class Line(val words: String, val spoken: String = words, val forMs: Long? = LINE_MS)

    /** What the player does next. */
    sealed interface Step {
        /** Nothing connects, and the film plays on as it was, [line] saying why. */
        data class Refuse(val line: Line) : Step

        /** The speaker question first ([PlayOnAsk.SoundOnly]): nothing connects until it is answered ([answered]). */
        data object AskSoundOnly : Step

        /** The pre-check's [question] first, over the film ([CastPrecheck], L124 step 2): nothing connects until it is answered ([prechecked]). */
        data class AskPrecheck(val question: CastPrecheck) : Step

        /**
         * The list closes, the film pauses where it is, [line] shows, and the device connects; once it has, the film moves,
         * or for [compatible] (Play a compatible release, L152) the list's other releases the device should play start
         * there in its place, from where it paused.
         */
        data class Connect(val line: Line, val compatible: Boolean = false) : Step

        /** Nothing more: the film is as it is. */
        data object Stay : Step

        /** The device plays the film: the player saves its place and closes, and the remote opens. */
        data object Close : Step

        /** The move didn't go: the film plays on here from where it paused, [line] saying why, This phone chosen. */
        data class PlayOn(val line: Line) : Step
    }

    /**
     * What the Cast device makes of the film playing, by its verdict on the film's tracks (L124 steps 2 and 3, item 44): the
     * pre-check's [question] where it can't fully play them; else the [language] it will play, by its English name
     * ("German"), where that isn't the first one wanted (W51); neither where nothing was judged ([None]).
     */
    data class Fit(val question: CastPrecheck? = null, val language: String? = null) {
        companion object {
            /** Nothing judged: the film's tracks weren't read. */
            val None = Fit()

            /**
             * The fit of a film whose [tracks] the device [name] plays as [verdict] says (null for either: nothing judged),
             * the film being the release [release]. Its question offers Play on this phone, as this phone plays the film
             * now, and Play a compatible release where the film's list has another the device should play ([compatible]).
             */
            fun of(verdict: CastVerdict?, tracks: ProbedTracks?, name: String, release: String, compatible: Boolean): Fit {
                if (verdict == null || tracks == null) return None
                val question = CastPrecheck.of(verdict, tracks, name, release, ReleaseParser.parse(release).audio, compatible, here = true)
                return Fit(question, verdict.otherLanguage?.takeIf { question == null }?.let(CastPrecheck::languageName))
            }
        }
    }

    /**
     * A tap on the Cast device [name] of [kind] in the player's list, the film playing [fileName] (null for a launch from
     * before names were kept: the device's own load checks the type again), [subtitles] showing or not, and [fit]ting the
     * device as its verdict says. A speaker told Cast anyway for its session ([castAnywayTold]) isn't asked again. A film
     * played from a debrid service moves as any, the phone relaying it (W73).
     */
    fun tapped(name: String, kind: CastDeviceKind, fileName: String?, subtitles: Boolean, castAnywayTold: Boolean, fit: Fit = Fit.None): Step = when {
        fileName != null && !ContainerTypes.castable(fileName) -> Step.Refuse(Line("${fileTypeCannotPlay(name)}. $STILL_PLAYING_HERE"))
        kind != CastDeviceKind.Video && !castAnywayTold -> Step.AskSoundOnly
        else -> judged(name, subtitles, fit)
    }

    /**
     * The speaker question's [answer]: Cast anyway goes on as for any device, the film [fit]ting it or not; Play on this
     * phone and Cancel leave it playing here.
     */
    fun answered(answer: PlayOnAsk.SoundOnly.Answer, name: String, subtitles: Boolean, fit: Fit = Fit.None): Step = when (answer) {
        PlayOnAsk.SoundOnly.Answer.CastAnyway -> judged(name, subtitles, fit)
        PlayOnAsk.SoundOnly.Answer.PlayHere, PlayOnAsk.SoundOnly.Answer.Cancel -> Step.Stay
    }

    /**
     * The pre-check's [button] (L124 step 2): Cast anyway moves the film there all the same, its line without the audio
     * line the sheet said; Play a compatible release connects, and the list's others play there in its place; Play on this
     * phone and Cancel leave the film playing here, connecting nothing.
     */
    fun prechecked(button: CastPrecheck.Button, name: String, subtitles: Boolean): Step = when (button) {
        CastPrecheck.Button.CastAnyway -> connect(name, subtitles, language = null)
        CastPrecheck.Button.Compatible -> connect(name, subtitles, language = null).copy(compatible = true)
        CastPrecheck.Button.PlayHere, CastPrecheck.Button.Cancel -> Step.Stay
    }

    /** The device [name] didn't connect: refused, or not in its 15 s. */
    fun connectFailed(name: String): Step = Step.PlayOn(Line("${couldNotConnect(name)}. $STILL_PLAYING_HERE"))

    /** What comes after the coordinator's move to [name] came out as [outcome]. */
    fun after(outcome: MoveOutcome, name: String): Step = when (outcome) {
        MoveOutcome.Moved -> Step.Close
        is MoveOutcome.Failed -> Step.PlayOn(failedLine(outcome.failure, name))
        MoveOutcome.Stopped -> Step.Stay
    }

    /**
     * Why the device [name] didn't play the film, in C1's words for a cast's failure (CastOutcomes), then that it plays on
     * here: "MOV file. Kitchen display refused it as it opened. Still playing here."
     */
    fun failedLine(failure: CastLoadFailure, name: String): Line {
        val reason = failure.reason ?: when (failure.kind) {
            CastFailKind.CannotReach -> cannotReach(name)
            CastFailKind.NotFully -> noneFully(name, failure.releases.coerceAtLeast(1))
            CastFailKind.CouldNotPlay, CastFailKind.FileType, CastFailKind.NotStarted -> couldNotPlayThis(name)
        }
        return Line("${reason.trimEnd('.')}. $STILL_PLAYING_HERE")
    }

    /** The pre-check's question where the film doesn't [fit] the device, else the move. */
    private fun judged(name: String, subtitles: Boolean, fit: Fit): Step = fit.question?.let(Step::AskPrecheck) ?: connect(name, subtitles, fit.language)

    /**
     * The film moving to [name]: "Moving to Kitchen display…", then the audio [language] it will play there where it isn't
     * yours (W51), and where subtitles show here, that they don't there (W55), one sentence each.
     */
    private fun connect(name: String, subtitles: Boolean, language: String?): Step.Connect {
        val more = listOfNotNull(language?.let { willPlayTheAudio(name, it) }, CAST_NO_SUBTITLES.takeIf { subtitles })
        val words = if (more.isEmpty()) movingTo(name) else "${movingTo(name)} ${more.joinToString(". ")}"
        return Step.Connect(Line(words, (listOf("Moving to $name") + more).joinToString(". "), forMs = null))
    }
}

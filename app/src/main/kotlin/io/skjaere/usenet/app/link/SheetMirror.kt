package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.playsHere
import io.skjaere.usenet.app.play.shortFailure
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.Severity
import io.skjaere.usenet.search.RankedItem

/**
 * L37: the TV's play sheet as a paired phone mirrors it, the wire's `sheet`. It carries only what the sheet shows, as
 * text: the titles, each step's state, the notice, the chips, the stage lines, the note, the failures, and the hidden
 * releases' names and reasons; never a release's link, an indexer's id or name, or a cover's address. Each failure is
 * written here from its kind and counts ([failure]), as are the stages' indexer errors (counted where the sheet is made,
 * [PlayState.NoRelease.remoteStages]): a library's or a server's own words can name a provider's host or carry its reply.
 * The phone rewrites "your" to "the TV's" itself (TvWords).
 */
object SheetMirror {
    /** A failure line whose words weren't written for a phone: plain, never the TV's own. */
    const val COULDNT = "Couldn't start it on the TV"

    /** Hidden releases named, at most; the rest are counted. Each holds a release's name, and a frame holds 64 KiB. */
    const val MAX_HIDDEN = 20

    /**
     * What a phone shows of [state]; null for no play. A search sends the rows the TV's own sheet lists meanwhile,
     * [searchSteps] (the coordinator's, L223): a TV that plays from its debrid service alone lists its service's.
     */
    fun of(state: PlayState, searchSteps: List<PipelineStep> = UsenetSource.STEPS): Status.Sheet? = when (state) {
        PlayState.Idle -> null
        is PlayState.Searching -> Status.Sheet(SheetKind.Searching, state.title, state.subtitle, line = state.line, rows = searchSteps.map(::wire))
        is PlayState.Running -> Status.Sheet(
            SheetKind.Running, state.title, state.subtitle, line = state.searchLine, notice = notice(state), steps = steps(state.steps),
            attempt = state.attempt, of = state.of, release = state.release, chips = state.chips, playsHere = playsHere(state.steps),
        )
        // The files stay on the TV: the phone says to choose there, with its remote.
        is PlayState.ChoosingFile -> Status.Sheet(SheetKind.Choosing, state.title, state.subtitle, lookingFor = state.lookingFor, release = state.release)
        // A cast's question (L130): a TV never casts (L110), so a phone is never told one.
        is PlayState.Prechecking -> null
        is PlayState.NoRelease -> Status.Sheet(
            SheetKind.NoRelease, state.title, state.subtitle, stages = state.remoteStages.map { (what, why) -> Status.Line(what, why) },
            note = state.note, hidden = state.hidden.take(MAX_HIDDEN).map(::hidden), hiddenCount = state.hidden.size,
        )
        is PlayState.Failed -> when (val missing = state.noProvider) {
            null -> Status.Sheet(
                SheetKind.Failed, state.title, state.subtitle,
                failures = state.failures.mapIndexed { i, (release, _) -> Status.Line(release, state.remote.getOrNull(i) ?: COULDNT) },
                skippedBefore = state.missing.map { (release, line) -> Status.Line(release, line) }, notTried = state.notTried, skipped = state.skipped,
                noRoute = state.noRoute,
            )
            else -> Status.Sheet(SheetKind.NoProvider, state.title, state.subtitle, noProvider = missing.message)
        }
    }

    /**
     * Why [f] failed, as a phone is told it: from its kind and counts alone. The formats a release has that the TV can't
     * play are named as the TV's own sheet names them, from what it read of the file.
     */
    fun failure(f: AttemptFailure): String = when (f) {
        is AttemptFailure.FetchFailed -> "Couldn't get the NZB on the TV"
        is AttemptFailure.MissingArticles -> when {
            f.unanswered != null -> "Incomplete on the TV's providers that answered"
            f.providersLeftOut -> "Incomplete on the TV's providers in use"
            else -> "Incomplete on the TV's providers"
        } + articles(f)
        AttemptFailure.Passworded -> "Passworded"
        is AttemptFailure.Unsupported -> "Can't be streamed on the TV"
        // Said of the TV's player, never of its providers (L318); a reader that crashed as any read that failed there.
        is AttemptFailure.Unreadable -> when (f.kind) {
            UnreadableFile.UnknownFormat -> f.extension?.let { "The TV's player can't read this $it file" } ?: "The TV's player can't read this file's format"
            UnreadableFile.Broken -> (f.extension?.let { "The TV's player can't read this $it file" } ?: "The TV's player can't read this file") + ": it may be damaged"
            UnreadableFile.ReaderCrashed -> "Reading the release failed on the TV"
        }
        AttemptFailure.NoVideo -> "No video file"
        // The release's own shape, whichever device opened it (L313): the TV's words as they are.
        is AttemptFailure.DiscCopy -> f.text
        is AttemptFailure.NoMatchingEpisode -> "No file for S%02dE%02d".format(java.util.Locale.ROOT, f.season, f.episode)
        is AttemptFailure.NoRememberedFile -> "The file that played before isn't in it"
        is AttemptFailure.NoPlayableAudio -> "No audio the TV can play" + if (f.found.isEmpty()) "" else " (${f.found.joinToString()})"
        is AttemptFailure.NoPlayableVideo -> "No video the TV can play" + if (f.found.isEmpty()) "" else ": ${f.found.joinToString()}"
        is AttemptFailure.Provider -> provider(f.error)
        is AttemptFailure.Failed -> provider(f.error)
        // Another device's own words (a phone's mirror): never told on. A file refused for a Cast device, by its type or what
        // the device can't play of it: a TV never casts (L67).
        is AttemptFailure.Elsewhere, is AttemptFailure.NotForCast, is AttemptFailure.CastMiss -> COULDNT
        // A debrid service's, the TV's own, naming no service (L204), not even the one whose key it refused.
        AttemptFailure.NoLongerCached -> "No longer cached on the TV's debrid service"
        AttemptFailure.RemovedByService -> "Removed by the TV's debrid service"
        is AttemptFailure.ServiceLimit -> "The TV's debrid service's limit was reached"
        is AttemptFailure.ServiceRefused -> if (f.tooLarge) "Too large for the TV's debrid plan" else "The TV's debrid service won't play this release"
        is AttemptFailure.KeyRefused -> "The TV's debrid service refused its API key"
        is AttemptFailure.ServiceDown -> "The TV's debrid service stopped answering"
        AttemptFailure.NoWorkingLink -> "No longer available from the TV's debrid service"
    }

    /** "· 41 of 256 checked articles missing", when the availability check counted them. */
    private fun articles(f: AttemptFailure.MissingArticles): String {
        val missing = f.missing ?: return ""
        val checked = f.checked ?: return ""
        return " · $missing of $checked checked article${if (checked == 1) "" else "s"} missing"
    }

    /** A Usenet failure as a phone is told it, by its kind. */
    private fun provider(error: UsenetError): String = when (error) {
        is UsenetError.MissingArticle -> "Incomplete on the TV's providers"
        is UsenetError.Corrupt -> "Damaged on the TV's providers"
        is UsenetError.AuthFailed -> "A provider on the TV rejected the login"
        is UsenetError.ConnectionFailed -> "The TV couldn't reach its provider"
        is UsenetError.Network -> "The TV lost the connection to its provider"
        is UsenetError.Timeout -> "A provider on the TV stopped answering"
        is UsenetError.Protocol -> "A provider on the TV sent an unexpected answer"
        UsenetError.NotConfigured -> "The TV has no Usenet provider set up"
        is UsenetError.Unknown -> "Reading the release failed on the TV"
    }

    /**
     * The notice above the steps: one that names the release that failed is told in the TV's words, as the TV's own names it
     * ([io.skjaere.usenet.app.play.shortFailure]); the app's own notices (a retry's, the releases skipped) as they are. The
     * same release tried on the TV's next debrid service (DEP-6, L226) is told in the mirror's own words, from the failure's
     * kind: no other release is tried, and the TV's words for a refused key name its service, which never crosses.
     */
    private fun notice(state: PlayState.Running): String? {
        val failed = state.noticeFailure ?: return state.notice
        if (state.noticeNextService) return "${short(failed)} · trying the TV's next debrid service"
        return "${short(failed)} · trying release ${state.attempt} of ${state.of}"
    }

    private fun short(f: AttemptFailure): String = when (f) {
        is AttemptFailure.MissingArticles -> when {
            f.unanswered != null -> "Incomplete on the TV's providers that answered"
            f.providersLeftOut -> "Incomplete on the TV's providers in use"
            else -> "Release incomplete"
        }
        AttemptFailure.Passworded -> "Release passworded"
        is AttemptFailure.NoPlayableAudio -> "No playable audio"
        is AttemptFailure.NoPlayableVideo -> "No playable video"
        // The TV's notice words, said of the TV (L329), not the line's longer ones; a disc copy's name no device.
        is AttemptFailure.Unreadable -> "${shortFailure(f)} on the TV"
        is AttemptFailure.DiscCopy -> shortFailure(f)
        else -> failure(f)
    }

    /** [step] as the wire names it. */
    internal fun wire(step: PipelineStep): SheetStep = when (step) {
        // A phone before D1 reads it as Unknown and leaves it out, a TV's torrent start then told from its Opening (L206).
        PipelineStep.Prepare -> SheetStep.Prepare
        PipelineStep.FetchNzb -> SheetStep.FetchNzb
        PipelineStep.CheckAvailability -> SheetStep.CheckAvailability
        PipelineStep.Open -> SheetStep.Open
        PipelineStep.ProbeTracks -> SheetStep.ProbeTracks
    }

    /** The steps reached, in the sheet's order, as each row shows. */
    private fun steps(steps: Map<PipelineStep, StepStatus>): List<Status.Step> = PipelineStep.entries.mapNotNull { step ->
        val name = wire(step)
        when (val status = steps[step]) {
            null -> null
            is StepStatus.Running -> Status.Step(name, StepState.Running, done = status.done, total = status.total)
            // A detail the row has no room for, it leaves out (the file opened, by its name): so does the mirror.
            is StepStatus.Done -> Status.Step(name, StepState.Done, millis = status.millis, detail = status.detail?.takeIf { it.length < DETAIL_SHOWN })
            is StepStatus.Failed -> Status.Step(name, StepState.Failed, millis = status.millis, failure = failure(status.failure))
        }
    }

    /** A hidden release: its name, and its reasons as the release list's chips say them (ReasonChips), its audio last. */
    private fun hidden(item: RankedItem): Status.Hidden {
        val reasons = item.ranked.reasons.map { reason ->
            when {
                reason is ReleaseReason.PlaysOnDevice -> "✓ ${reason.text}"
                reason is ReleaseReason.Language -> reason.text
                reason.severity == Severity.Blocking || reason.severity == Severity.Warning -> "⚠ ${reason.text}"
                reason.severity == Severity.Positive -> "✓ ${reason.text}"
                else -> reason.text
            }
        }
        val audio = item.parsed.audio.firstOrNull()?.let { it.label + (item.parsed.channels?.let { c -> " $c" } ?: "") }
        return Status.Hidden(item.item.title, reasons + listOfNotNull(audio))
    }

    /** The longest step detail the sheet's row shows, plus one (PlaySheet's StepRow). */
    private const val DETAIL_SHOWN = 24
}

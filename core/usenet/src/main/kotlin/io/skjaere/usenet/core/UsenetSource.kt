package io.skjaere.usenet.core

import io.skjaere.compressionutils.ArchiveService
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Usenet's releases ([PlayCandidate.USENET]), for [PlayPipeline]: for each, fetch the NZB from [source], STAT a sample of
 * its articles ([availability]), prepare it ([preparer]) and pick the file, which the pipeline then probes. Only articles
 * that can matter are checked: never those of PAR2, NFO and other extras, and in a release that may hold more than the
 * file to play (a season pack) only those of the picked file's volumes, once it is prepared. A failure that hits every
 * Usenet release alike (the provider down) stops the Usenet route ([AttemptFailure.route]). A gap found while
 * [providersLeftOut] says a provider is left out of the engine is reported as on the providers in use: that one may have
 * the articles, unless the gap is a file the NZB doesn't hold, which no provider has. While [providerFallback] says
 * another provider serves when one fails, a provider failing (its login rejected, out of reach) where the others lack an
 * article fails that release, not the play: the article may be on the one that failed, and the next release may be
 * whole on the others (#172).
 *
 * An encrypted release opens with the password in its NZB, else with the one its source got beside
 * the NZB ([NzbSource.fetchWithPassword]: its indexer's); with neither it fails as passworded,
 * without asking for one. So does one whose archive opens but whose file the player can't read,
 * when its indexer says it needs a password ([PlayCandidate.passworded]): its file data is
 * encrypted, its headers not.
 *
 * Where a release of several videos names none for what plays, [chooser] is asked which it is; a release whose files are a
 * copy of a disc fails before that, as soon as they are listed ([discCopy]). What plays is kept in [store]
 * ([UsenetTarget.keep]), where the player's data source finds its NZB.
 */
class UsenetSource(
    private val source: NzbSource,
    private val availability: AvailabilityBackend,
    private val preparer: NzbPreparer,
    private val store: PreparedNzbStore,
    private val providersLeftOut: () -> Boolean = { false },
    private val providerFallback: () -> Boolean = { false },
    private val chooser: FileChooser = FileChooser.None,
) : PlaySource<UsenetTarget> {
    // The pipeline's: these lines read as they did when the attempt was its own.
    private val log = LoggerFactory.getLogger(PlayPipeline::class.java)

    override val steps get() = STEPS

    override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): UsenetTarget {
        val fetched = step(candidate, PipelineStep.FetchNzb) {
            try {
                source.fetchWithPassword(candidate).let { StepResult.Ok(it, formatSize(it.bytes.size.toLong())) }
            } catch (e: FetchException) {
                StepResult.Fail(AttemptFailure.FetchFailed(e.reason, if (e.stopsAll) PlayCandidate.USENET else null, e.blocksIndexer))
            }
        }
        val nzb = fetched.bytes
        // A release that is all the one file to play is checked before it is opened: opening costs
        // more than the check and is wasted on a broken release. Any other is checked once the
        // file is picked, so a gap in another episode of a season pack doesn't reject it.
        val whole = pickFile.takesWhole(candidate.title)
        if (whole) checkAvailability(candidate) { ExtraFileFilter.withoutExtras(NzbParser.parse(nzb)) }
        val prepared = step(candidate, PipelineStep.Open) { watch ->
            // The NZB's own password wins: nzb-streamer reads it, and one given to prepare would take its place.
            val password = fetched.password?.takeIf { guarded { NzbParser.parse(nzb).password } == null }
            when (val outcome = preparer.prepare(nzb, candidate.title, password)) {
                is PrepareOutcome.Ready -> {
                    // A copy of a disc plays as no single video: it fails as soon as its files are listed, before any is
                    // picked, the user asked or the file probed (W103, L311).
                    outcome.nzb.discCopy()?.let { return@step StepResult.Fail(it) }
                    // Until the release is open, what fails is the release's; from then on, the picked file's, which
                    // is the release's unless the release holds other videos too. One video kept is the release's only
                    // one unless prepare left out another, which may be whole.
                    val leftOut by lazy(LazyThreadSafetyMode.NONE) { leftOutOf(nzb, outcome.nzb) }
                    wholeRelease = whole || outcome.nzb.oneVideo && leftOut.isEmpty()
                    // One named for it that prepare left out makes the release incomplete; else, where no name tells, the user may.
                    val file = pickFile.pick(outcome.nzb)
                        ?: leftOut.firstOrNull(pickFile::wants)?.let { return@step StepResult.Fail(missing("'$it' was left out: it or its archive set lacks a first article")) }
                        ?: chosen(candidate, outcome.nzb, pickFile, watch, chooser)
                        ?: return@step StepResult.Fail(pickFile.noMatchIn(outcome.nzb))
                    StepResult.Ok(outcome.nzb to file, file.name)
                }
                is PrepareOutcome.NeedsPassword -> StepResult.Fail(AttemptFailure.Passworded)
                is PrepareOutcome.MissingArticles -> StepResult.Fail(missing(outcome.message, notInNzb = outcome.notInNzb))
                is PrepareOutcome.Unsupported -> StepResult.Fail(AttemptFailure.Unsupported(outcome.message))
                is PrepareOutcome.Failed -> StepResult.Fail(classify(outcome.error))
            }
        }
        if (!whole) checkAvailability(candidate) { prepared.first.volumesOf(prepared.second) }
        return UsenetTarget(candidate, nzb, prepared.first, prepared.second, store, fetched.password)
    }

    /**
     * The probe couldn't read [target]'s file: its bytes encrypted data where the indexer says the release needs a password
     * ([encryptedData]), which only bytes it read can be, never a reader that crashed (L330); else in the words every
     * source says a file the player can't read in ([unreadableFailure]); else as [failed]'s error says.
     */
    override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: UsenetTarget): AttemptFailure =
        if (failed.unreadable in setOf(UnreadableFile.UnknownFormat, UnreadableFile.Broken) && encryptedData(target)) AttemptFailure.Passworded
        else unreadableFailure(failed, target.file.name) ?: classify(failed.error)

    override fun failed(error: Exception): AttemptFailure = classify(error.toUsenetError())

    /**
     * Whether the file the player can't read is most likely one [target]'s archive holds encrypted, which no password
     * came with to open: the indexer says its candidate needs one, none came beside its NZB or in it, and the file is in an
     * archive. An archive whose headers aren't encrypted (rar -p) lists and opens without a password, and its files'
     * bytes are read as they are stored: kotlin-compression-utils reads no file's own encryption record.
     */
    private suspend fun AttemptSteps.encryptedData(target: UsenetTarget): Boolean =
        target.candidate.passworded && target.prepared.metadata !is ExtractedMetadata.Raw && target.password == null &&
            guarded { NzbParser.parse(target.nzbBytes).password } == null

    /**
     * STATs the articles of what [articles] returns, sampled or all as [availability] is set up; not those of the release
     * that was playing ([PlayCandidate.wasPlaying]), whose step says it was skipped.
     */
    private suspend fun AttemptSteps.checkAvailability(candidate: PlayCandidate, articles: () -> NzbDocument) {
        if (candidate.wasPlaying) {
            send(PipelineEvent.Step(candidate, PipelineStep.CheckAvailability, StepStatus.Done(0, PlayPipeline.SKIPPED_PLAYING)))
            return
        }
        step(candidate, PipelineStep.CheckAvailability) {
            // Progress arrives on the checking threads; forward it at most every 100 ms.
            var lastSent = 0L
            val answered = AtomicInteger()
            var of = 0
            val onProgress = { done: Int, total: Int ->
                answered.accumulateAndGet(done, ::maxOf)
                of = total
                val now = System.nanoTime()
                if (done == total || now - lastSent > 100_000_000L) {
                    lastSent = now
                    trySend(PipelineEvent.Step(candidate, PipelineStep.CheckAvailability, StepStatus.Running(done, total)))
                }
            }
            val started = timeSource.markNow()
            val result = guarded { availability.verify(articles(), onProgress) }
            val counts = when (result) {
                VerificationResult.Success -> "${answered.get()} of $of articles answered, none missing"
                is VerificationResult.MissingArticles -> "${result.answeredCount} of ${result.totalCount} articles answered, ${result.missingCount} missing"
                is VerificationResult.Failure -> "${answered.get()} of $of articles answered, then ${result.message}"
            }
            log.info("play_pipeline: {} availability check in {} ms: {}", candidate.title, started.elapsedNow().inWholeMilliseconds, counts)
            when (result) {
                VerificationResult.Success -> StepResult.Ok(Unit)
                is VerificationResult.MissingArticles -> StepResult.Fail(missing(result.message, result.missingCount, result.answeredCount))
                is VerificationResult.Failure -> StepResult.Fail(classify(result.cause.toUsenetError()))
            }
        }
    }

    /**
     * The failure [error] is for a release. A login or connection problem hits every release alike,
     * unless another provider serves ([providerFallback]): then the others lack an article that may
     * be on the one that failed.
     */
    private fun classify(error: UsenetError): AttemptFailure = when (error) {
        is UsenetError.AuthFailed, is UsenetError.ConnectionFailed ->
            if (providerFallback()) AttemptFailure.MissingArticles(error.detail.orEmpty(), providersLeftOut = providersLeftOut(), unanswered = error)
            else AttemptFailure.Provider(error)
        UsenetError.NotConfigured -> AttemptFailure.Provider(error)
        is UsenetError.MissingArticle -> missing(error.detail.orEmpty())
        else -> AttemptFailure.Failed(error)
    }

    /**
     * The names of the files [nzb] lists that may hold what plays (a video but a sample, an archive
     * volume) and that the release it [prepared] went without: nzb-streamer leaves out one that lacks
     * its first article while another of its size stands in for it (library issue N15), such as an
     * episode of a season pack whose other episodes are whole, which may be the one wanted
     * ([FilePicker.wants]). Not one it needn't keep (a subtitle, a PAR2 index under an obfuscated name).
     */
    private fun leftOutOf(nzb: ByteArray, prepared: PreparedNzb): List<String> {
        val kept = prepared.metadata.orderedArchiveNzb.files.mapNotNull { it.segments.firstOrNull()?.articleId }.toSet()
        return NzbParser.parse(nzb).files
            .filter { it.segments.firstOrNull()?.articleId !in kept }
            .mapNotNull { FilenameResolver.extractSubjectFilename(it.subject) }
            .filter { VideoFileFilter.isVideo(it) && !VideoFileFilter.isSample(it) || ArchiveService.fileHasKnownExtension(it) }
    }

    /** A gap that is a file the NZB doesn't hold ([notInNzb]) is on no provider, left out or not (library issue NR-18). */
    private fun missing(detail: String, missing: Int? = null, checked: Int? = null, notInNzb: Boolean = false) =
        AttemptFailure.MissingArticles(detail, missing, checked, providersLeftOut = !notInNzb && providersLeftOut(), notInNzb = notInNzb)

    companion object {
        /** A Usenet release's steps, in their order: what the play sheet lists for one, and while a search for one runs. */
        val STEPS = listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks)
    }
}

/**
 * [file] of [prepared], an NZB as its source fetched it ([nzbBytes]) with the archive [password] that came beside it, if
 * one did. Kept in [store], where the player's data source finds the NZB by its id ([UsenetUris]).
 */
class UsenetTarget(
    override val candidate: PlayCandidate,
    val nzbBytes: ByteArray,
    val prepared: PreparedNzb,
    override val file: FileCandidate,
    private val store: PreparedNzbStore,
    val password: String? = null,
) : PlaybackTarget {
    override val listing: PreparedNzb get() = prepared
    override val uri: String get() = UsenetUris.of(prepared.id, file)

    override suspend fun keep() {
        store.put(prepared, nzbBytes)
    }

    override fun equals(other: Any?) = other is UsenetTarget && candidate == other.candidate && prepared.id == other.prepared.id && file == other.file
    override fun hashCode() = 31 * candidate.hashCode() + prepared.id.hashCode()
}

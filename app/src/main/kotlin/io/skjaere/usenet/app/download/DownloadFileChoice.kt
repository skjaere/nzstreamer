package io.skjaere.usenet.app.download

import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.usenet.app.play.ChooseFile
import io.skjaere.usenet.app.play.EpisodeTarget
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.play.fileChoices
import io.skjaere.usenet.app.play.lookingFor
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.discCopy
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.core.toUsenetError
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

/**
 * A file chosen in a pack (L403): its [pick] (its place and name in the pack, its exact size, the NZB's id), and the pack's
 * NZB with the password its indexer sent beside it, as the download's kept [grab]: its attempt opens that, with no grab.
 * The password is the release's: never said of it.
 */
class ChosenFile(val pick: DownloadPick, val grab: DownloadStorage.Grab) {
    override fun toString() = "ChosenFile(${pick.size} bytes, $grab)"
}

/**
 * Choose file in download mode (L403, L435), for one episode's download, from a release list's pack: the pack [release]'s
 * NZB grabbed once from its indexer and prepared once, on the sheet, as the user waits ([State.Opening], in the play
 * sheet's step words). Outside the NZB store (DN1's L383): nothing of it is kept until a file is chosen, and then only with
 * the download. Its videos are listed as a play's file chooser lists them ([State.Files]): largest first, the episode's
 * named, each at its exact size, one larger than the room free greyed with what it needs. The one chosen goes to
 * [onPick] with the NZB and its indexer's password, for the download to keep as its grab. A pack that can't be fetched or
 * opened says why in the play's words ([State.Failed]), which [onFailed] marks it with in the list.
 *
 * It runs in [scope], the sheet's or the list's: [cancel], and their end, stop it. Its log lines name the download whose
 * release the list replaces ([replacing], the Downloads page's) by its id, and the sheet's own, before any download is
 * there, none: never the release, a file, the indexer or the password.
 */
class DownloadFileChoice internal constructor(
    val release: RankedItem,
    /** Under the sheet's title, as the Download sheet's: "Harbor Lights · S02E04 · Low Water". */
    val subtitle: String,
    private val episode: FilePicker.Episode,
    private val episodeName: String?,
    private val deps: DownloadSheetDeps,
    private val scope: CoroutineScope,
    private val replacing: String?,
    private val onFailed: (reason: String) -> Unit,
    private val onPick: (FileChoice, ChosenFile) -> Unit,
) {
    /** What the sheet shows: the pack [Opening], its [Files], or why it [Failed]. */
    sealed interface State {
        /** The pack's NZB fetched ([fetch]), then the pack opened ([open], null until it starts), each as the play sheet says its step. */
        data class Opening(val fetch: StepStatus, val open: StepStatus? = null) : State

        /** The pack's videos to choose from, in the chooser's order, with what is [free] for a new download. */
        data class Files(val files: List<Option>, val free: Long) : State {
            /** "1.5 GB free on this device. Files that won't fit are greyed."; with room for each, what is free alone. */
            val room: String get() = DownloadSheetModel.free(free) + if (files.all { it.wontFit == null }) "." else ". Files that won't fit are greyed."
        }

        /** The pack couldn't be fetched or opened: [reason], in the play's words for its failure. */
        data class Failed(val reason: String) : State
    }

    /** A video of the pack, and, where it is larger than the room free, what it [wontFit]s: "Won't fit: needs 1.6 GB". */
    data class Option(val file: FileChoice, val wontFit: String? = null)

    private val _state = MutableStateFlow<State>(State.Opening(StepStatus.Running()))
    val state: StateFlow<State> = _state.asStateFlow()

    private val label = EpisodeTarget.label(episode.season, episode.episode)

    /** "Looking for S02E04 'Low Water'", the files' heading. */
    val lookingFor: String get() = lookingFor(label, episodeName)

    /** "Choose the file to download as S02E04:". */
    val prompt: String get() = "Choose the file to download as $label:"

    /** What a tap on a file does, as TalkBack says it: back to the sheet, or from the Downloads page its download at once. */
    val tapLabel: String get() = if (replacing == null) CHOOSE_THIS else DOWNLOAD_THIS

    /** The pack as fetched and opened, once it is: what a chosen file's download keeps, and where its file is. */
    private var opened: Opened? = null

    private class Opened(val nzb: FetchedNzb, val prepared: PreparedNzb)

    private var job: Job? = null

    /** Whether a file was chosen: one only, as a second tap before the sheet goes chooses nothing more. */
    private var picked = false

    /** What its log lines say first: the download the list replaces, by its id, or nothing before there is one. */
    private val named: String get() = replacing?.let { "download $it: " }.orEmpty()

    /** Fetches and opens the pack, once. */
    fun start() {
        if (job != null) return
        job = scope.launch { open() }
    }

    /** Cancel: the grab or the prepare under way stops, and nothing of the pack is kept. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * The video at [index] chosen: to [onPick], with the pack's NZB; none that won't fit, nor any before the files are
     * listed, and one only.
     */
    fun pick(index: Int) {
        val files = _state.value as? State.Files ?: return
        val file = files.files.firstOrNull { it.file.index == index }?.takeIf { it.wontFit == null }?.file ?: return
        val pack = opened ?: return
        if (picked) return
        picked = true
        log.info("{}Choose file: a file of {} bytes chosen", named, file.sizeBytes)
        val grab = DownloadStorage.Grab(release.key, DownloadStorage.Source(pack.nzb.bytes, pack.nzb.password))
        onPick(file, ChosenFile(DownloadPick(file.index, file.name, file.sizeBytes, pack.prepared.id), grab))
    }

    /** The grab, then the prepare, as the play's attempt makes them; then the videos, or why there are none. */
    private suspend fun open() {
        val fetching = TimeSource.Monotonic.markNow()
        val fetched = try {
            deps.grab(release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            return failed("fetched", AttemptFailure.FetchFailed(e.reason))
        } catch (e: Exception) {
            return failed("fetched", AttemptFailure.Failed(e.toUsenetError()))
        }
        log.info("{}Choose file: the pack's NZB fetched, {} bytes", named, fetched.bytes.size)
        _state.value = State.Opening(StepStatus.Done(fetching.elapsedNow().inWholeMilliseconds, formatSize(fetched.bytes.size.toLong())), StepStatus.Running())
        // The NZB's own password wins: nzb-streamer reads it, and one given to prepare would take its place. A pack's NZB of
        // several MB is read off the sheet's main thread, its scope's.
        val password = fetched.password?.takeIf { withContext(deps.nzbReading) { ownPassword(fetched.bytes) } == null }
        val prepared = when (val outcome = deps.prepare(fetched.bytes, release.item.title, password)) {
            is PrepareOutcome.Ready -> outcome.nzb
            is PrepareOutcome.NeedsPassword -> return failed("opened", AttemptFailure.Passworded)
            is PrepareOutcome.MissingArticles -> return failed(
                "opened",
                AttemptFailure.MissingArticles(outcome.message, providersLeftOut = !outcome.notInNzb && deps.providersLeftOut(), notInNzb = outcome.notInNzb),
            )
            is PrepareOutcome.Unsupported -> return failed("opened", AttemptFailure.Unsupported(outcome.message))
            is PrepareOutcome.Failed -> return failed("opened", failure(outcome.error))
        }
        // A copy of a disc plays as no single video, as a play's attempt finds it (L311).
        prepared.discCopy()?.let { return failed("opened", it) }
        // A pack of one video lists that one: it is the choice.
        val videos = ChooseFile(episode).choices(prepared).ifEmpty { listOfNotNull(prepared.defaultPick) }
        if (videos.isEmpty()) return failed("opened", AttemptFailure.NoVideo)
        val free = deps.space()?.free ?: 0
        opened = Opened(fetched, prepared)
        log.info("{}Choose file: the pack opened, {} videos", named, videos.size)
        _state.value = State.Files(fileChoices(videos, episode).map { Option(it, wontFit(it.sizeBytes, free)) }, free)
    }

    private fun failed(step: String, failure: AttemptFailure) {
        log.info("{}Choose file: the pack couldn't be {}: {}", named, step, failure::class.simpleName)
        _state.value = State.Failed(failure.text)
        onFailed(failure.text)
    }

    companion object {
        const val OPENING_NOTE = "Fetches the NZB and opens the pack to list its videos. No video is downloaded until you choose Download."
        const val FAILED = "Couldn't list the pack's files. The release failed:"
        const val FAILED_NOTE = "Try another release goes back to the list, where this one says why it failed."
        const val TRY_ANOTHER = "Try another release"
        const val SEASON_PACK = "Season pack"
        const val RELEASE = "Release"
        private const val CHOOSE_THIS = "Choose this file"
        private const val DOWNLOAD_THIS = "Download this file"

        /** What the release under the steps is: "Season pack", else, a release of several episodes, "Release". */
        fun kind(item: RankedItem): String = if (item.parsed.isSeasonPack) SEASON_PACK else RELEASE

        /** "Won't fit: needs 1.6 GB" for a file of [size] bytes larger than the [free] room: an exact size, no "about". */
        private fun wontFit(size: Long, free: Long): String? = if (size <= free) null else "Won't fit: needs ${formatSize(size)}"

        /** The password the NZB holds itself, which wins over its indexer's; none where it can't be read. */
        private fun ownPassword(nzb: ByteArray): String? = try {
            NzbParser.parse(nzb).password
        } catch (e: Exception) {
            null
        }

        /** As a play's attempt says a prepare that failed: the providers failing, a gap, else the error's own words. */
        private fun failure(error: UsenetError): AttemptFailure = when (error) {
            is UsenetError.AuthFailed, is UsenetError.ConnectionFailed, UsenetError.NotConfigured -> AttemptFailure.Provider(error)
            is UsenetError.MissingArticle -> AttemptFailure.MissingArticles(error.detail.orEmpty())
            else -> AttemptFailure.Failed(error)
        }

        private val log = LoggerFactory.getLogger(DownloadFileChoice::class.java)
    }
}

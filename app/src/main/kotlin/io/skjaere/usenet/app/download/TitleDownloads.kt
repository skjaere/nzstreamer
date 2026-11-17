package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.collection.UNDO_MS
import io.skjaere.usenet.app.collection.UndoTimer
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * A title's download as its pages show it (L384, L356): the Show page's episode rows, the Movie page's button and Search's
 * movie card. Only what they say, so a page draws again only when that changes: its [state] and [reason] as the runner
 * keeps them, how far it is ([percent], once its file is made), its file's [size] (exact once resolved, else the
 * indexer's), how many parts it lacks ([partsMissing]) and how many releases were [tried]; finished, whether Delete once
 * watched will delete it and it isn't due yet ([deleting], L415), and whether its file plays from the row alone ([plays],
 * as the play hook's launch takes it: what the Show page's line counts, L407); [id] for the runner's calls.
 */
data class TitleDownload(
    val id: String,
    val state: DownloadState,
    val reason: String? = null,
    val percent: Int? = null,
    val size: Long = 0,
    val partsMissing: Int = 0,
    val tried: Int = 0,
    val deleting: Boolean = false,
    val plays: Boolean = false,
) {
    companion object {
        /**
         * [item] as its title's page says it: the live write's bytes where it is being written, else its safe offset; and,
         * finished, whether it is among those going tomorrow ([deleting], the runner's [DownloadRunner.deletingTomorrow]).
         */
        fun of(item: DownloadRunner.Item, deleting: Boolean = false): TitleDownload {
            val row = item.row
            val total = row.size ?: row.indexerSize
            val percent = if (row.relativePath != null && total > 0) (item.written.coerceIn(0, total) * 100 / total).toInt() else null
            val done = row.state == DownloadState.Done
            return TitleDownload(row.id, row.state, row.reason, percent, total, row.gaps.size, row.tried.size, deleting && done, done && DownloadLaunch.accepts(row))
        }
    }
}

/** What a title's ⋮, or its Download button's menu, offers of its download (L379, L380), in the words drawn. */
enum class DownloadEntry(val label: String) {
    Download("Download"),
    Pause("Pause download"),
    Resume("Resume download"),
    Remove("Remove download"),
    DownloadAgain("Download again"),
    OpenDownloads("Open Downloads"),
}

/**
 * The 18 dp mark of a download's state (DlPhoneEpisodeRow, DlPhoneMovie): [Download] offered (the arrow into the tray, in the
 * text's colour); [Queued] or waiting (the arrow, muted); [Finding] its release (a ring turning); its file's [Progress] (a
 * ring, in the accent while it is written, muted with two bars while [held]); [Done] (the tick in the tray, never in the
 * green of the watched tick); [Failed] (⚠).
 */
sealed interface DownloadMark {
    data object Download : DownloadMark

    data object Queued : DownloadMark

    data object Finding : DownloadMark

    data class Progress(val fraction: Float, val held: Boolean) : DownloadMark

    data object Done : DownloadMark

    data object Failed : DownloadMark
}

/** The Download button's words (DlPhoneMovie): its [text], the detail on a [second] line where drawn, and its [mark]. */
data class DownloadButtonWords(val text: String, val second: PageLine?, val mark: DownloadMark)

/**
 * What a title's download says on its pages and what they offer of it (L356, L380, L384; DlPhoneEpisodeRow, DlPhoneMovie,
 * DlPhoneSearchMovie), in the words drawn. Pure, so the JVM reads every state; the rows and the button only draw it.
 * Where downloads don't show (L369), the pages are given no download and offer none ([offered], [entries]).
 */
object TitleDownloads {
    const val QUEUED = "Queued for download"
    const val DOWNLOADING = "Downloading"
    const val DOWNLOADED = "Downloaded"
    const val PAUSED = "Paused"
    const val FAILED = "Download failed"
    private const val WAITING = "Waiting"
    private const val TRYING = "Trying release"
    private const val SEP = " · "

    /** "Deleting tomorrow" as TalkBack hears it after "Downloaded": in the row's state's sentence. */
    private const val DELETING_SPOKEN = "deleting tomorrow"

    /**
     * The episode row's line under the date (DlPhoneEpisodeRow): "Downloaded · 1.3 GB", "Downloaded · 2 parts missing" (what
     * is missing in the warning colour), "Downloading · 42%", "Queued for download", "Paused · 18%", "Download failed",
     * "Finding releases…", "Trying release 2 of 3", "Waiting for Wi-Fi"; and, not drawn, the other waits, a stop by Android
     * and the provider out of reach as it is written, in the Downloads page's words. A finished one going tomorrow ends
     * " · Deleting tomorrow" (L415).
     */
    fun line(download: TitleDownload): List<PageLine> = when (download.state) {
        DownloadState.Queued -> listOf(PageLine(QUEUED, Tone.Muted))
        DownloadState.Resolving -> trying(download)?.let { listOf(PageLine(it, Tone.Accent)) } ?: listOf(PageLine(DownloadHostRules.FINDING_RELEASES, Tone.Muted))
        DownloadState.Running -> download.reason?.let { listOf(PageLine(far(it, download), Tone.Warn)) } ?: listOf(PageLine(far(DOWNLOADING, download), Tone.Accent))
        DownloadState.Paused -> listOf(PageLine(far(PAUSED, download), Tone.Muted))
        DownloadState.Stopped -> listOf(PageLine(far(DownloadHostRules.STOPPED_BY_ANDROID, download), Tone.WarnStrong))
        DownloadState.Waiting -> listOf(PageLine(download.reason ?: WAITING, if (holdsOnNeed(download)) Tone.Muted else Tone.Warn))
        DownloadState.Done -> {
            val going = if (download.deleting) "$SEP${AutoDelete.DELETING_TOMORROW}" else ""
            when (val missing = missing(download)) {
                null -> listOf(PageLine("$DOWNLOADED$SEP${formatSize(download.size)}$going", Tone.Muted))
                else -> listOfNotNull(PageLine("$DOWNLOADED$SEP", Tone.Muted), PageLine(missing, Tone.Warn), PageLine(going, Tone.Muted).takeIf { going.isNotEmpty() })
            }
        }
        DownloadState.Failed -> listOf(PageLine(FAILED, Tone.Warn))
        DownloadState.Deleting -> emptyList()
    }

    /** What TalkBack hears as the row's state (DlPhoneEpisodeRow): "Downloading, 42 percent", "Download paused, 18 percent". */
    fun spoken(download: TitleDownload): String {
        fun percent(words: String) = download.percent?.let { "$words, $it percent" } ?: words
        return when (download.state) {
            DownloadState.Queued -> QUEUED
            DownloadState.Resolving -> trying(download) ?: DownloadHostRules.FINDING_RELEASES.removeSuffix("…")
            DownloadState.Running -> percent(download.reason ?: DOWNLOADING)
            DownloadState.Paused -> percent("Download paused")
            DownloadState.Stopped -> percent(DownloadHostRules.STOPPED_BY_ANDROID)
            DownloadState.Waiting -> download.reason ?: WAITING
            DownloadState.Done -> listOfNotNull(DOWNLOADED, missing(download), DELETING_SPOKEN.takeIf { download.deleting }).joinToString(", ")
            DownloadState.Failed -> FAILED
            DownloadState.Deleting -> ""
        }
    }

    /** The row's and the button's mark: the arrow while queued or waiting, a ring while found or written, the tick once done. */
    fun mark(download: TitleDownload): DownloadMark = when (download.state) {
        DownloadState.Queued, DownloadState.Waiting -> DownloadMark.Queued
        DownloadState.Resolving -> DownloadMark.Finding
        DownloadState.Running -> DownloadMark.Progress(fraction(download), held = false)
        DownloadState.Paused, DownloadState.Stopped -> DownloadMark.Progress(fraction(download), held = true)
        DownloadState.Done -> DownloadMark.Done
        DownloadState.Failed -> DownloadMark.Failed
        DownloadState.Deleting -> DownloadMark.Download
    }

    /**
     * What the episode's ⋮ offers of its download, after Choose release and Mark watched (L380): Download while there is
     * none, and a Usenet provider is saved and on ([usenet], L370); the running one, the queue's Pause and Remove; one
     * queued behind it, Remove; one held, or waiting for what only Resume ends, Resume and Remove; a finished one, Remove;
     * a failed one, Download again and Remove.
     */
    fun entries(download: TitleDownload?, usenet: Boolean): List<DownloadEntry> = when (download?.state) {
        null, DownloadState.Deleting -> if (usenet) listOf(DownloadEntry.Download) else emptyList()
        DownloadState.Running, DownloadState.Resolving -> listOf(DownloadEntry.Pause, DownloadEntry.Remove)
        DownloadState.Waiting -> if (holdsOnNeed(download)) listOf(DownloadEntry.Pause, DownloadEntry.Remove) else listOf(DownloadEntry.Resume, DownloadEntry.Remove)
        DownloadState.Queued, DownloadState.Done -> listOf(DownloadEntry.Remove)
        DownloadState.Paused, DownloadState.Stopped -> listOf(DownloadEntry.Resume, DownloadEntry.Remove)
        DownloadState.Failed -> listOf(DownloadEntry.DownloadAgain, DownloadEntry.Remove)
    }

    /**
     * The Movie page's and Search's card's Download (DlPhoneMovie): "Download"; "Queued for download"; "Finding releases…";
     * "Downloading" with "42% of 4.2 GB" or "Trying release 2 of 3"; "Queued for download" with "Waiting for Wi-Fi";
     * "Paused" with "42% of 4.2 GB"; "Downloaded" with "4.2 GB" or "2 parts missing", or "Deleting tomorrow" while it is
     * going (L415); "Download failed" with "3 releases tried". The detail muted, but in the warning colour where something
     * went wrong.
     */
    fun button(download: TitleDownload?): DownloadButtonWords {
        if (download == null || download.state == DownloadState.Deleting) return DownloadButtonWords(DownloadEntry.Download.label, null, DownloadMark.Download)
        val mark = mark(download)
        val far = download.percent?.let { PageLine("$it% of ${formatSize(download.size)}", Tone.Muted) }
        return when (download.state) {
            DownloadState.Queued -> DownloadButtonWords(QUEUED, null, mark)
            DownloadState.Resolving -> trying(download)?.let { DownloadButtonWords(DOWNLOADING, PageLine(it, Tone.Muted), mark) }
                ?: DownloadButtonWords(DownloadHostRules.FINDING_RELEASES, null, mark)
            DownloadState.Running -> DownloadButtonWords(DOWNLOADING, download.reason?.let { PageLine(it, Tone.Warn) } ?: far, mark)
            DownloadState.Waiting -> DownloadButtonWords(QUEUED, PageLine(download.reason ?: WAITING, if (holdsOnNeed(download)) Tone.Muted else Tone.Warn), mark)
            DownloadState.Paused -> DownloadButtonWords(PAUSED, far, mark)
            DownloadState.Stopped -> DownloadButtonWords(DownloadHostRules.STOPPED_BY_ANDROID, far, mark)
            DownloadState.Done -> DownloadButtonWords(
                DOWNLOADED,
                if (download.deleting) {
                    PageLine(AutoDelete.DELETING_TOMORROW, Tone.Muted)
                } else {
                    missing(download)?.let { PageLine(it, Tone.Warn) } ?: PageLine(formatSize(download.size), Tone.Muted)
                },
                mark,
            )
            DownloadState.Failed -> DownloadButtonWords(
                FAILED, download.tried.takeIf { it > 0 }?.let { PageLine("$it release${if (it == 1) "" else "s"} tried", Tone.Warn) }, mark,
            )
            DownloadState.Deleting -> error("Handled above")
        }
    }

    /**
     * The Show page's line under the watched bar (L409), from the show's [downloads] by episode as its rows say them: "3
     * downloaded · 4.1 GB", counting the finished ones whose file plays ([TitleDownload.plays], L407), then " · 5 in the
     * queue" while some are still to finish (the one being written among them). None until one is downloaded: the line
     * opens the show's downloads, which lists the finished ones.
     */
    fun showLine(downloads: Collection<TitleDownload>): String? {
        val done = downloads.filter { it.state == DownloadState.Done && it.plays }
        if (done.isEmpty()) return null
        val queued = downloads.count { it.state in DownloadRunner.GOING_ON }
        return "${done.size} downloaded$SEP${formatSize(done.sumOf { it.size })}" + if (queued > 0) "$SEP$queued in the queue" else ""
    }

    /** The button's menu once it is started (DlPhoneMovie): the ⋮'s items by state, then Open Downloads. */
    fun menu(download: TitleDownload): List<DownloadEntry> = entries(download, usenet = false) + DownloadEntry.OpenDownloads

    /**
     * Whether a film's Download button shows: where downloads do ([shown], L369), while a Usenet provider is saved and on
     * ([usenet], L370) or the film has a download, which says how it is whatever is saved since.
     */
    fun offered(shown: Boolean, usenet: Boolean, download: TitleDownload?): Boolean = shown && (usenet || download != null)

    /**
     * A show's downloads ([items], the runner's, by season and episode) by episode: the latest added where one has two, each
     * finished one among [deleting] going tomorrow.
     */
    fun byEpisode(items: List<DownloadRunner.Item>, deleting: Set<String> = emptySet()): Map<Pair<Int, Int>, TitleDownload> = items
        .filter { it.row.season != null && it.row.episode != null }
        .sortedBy { it.row.createdAt }
        .associate { (it.row.season!! to it.row.episode!!) to TitleDownload.of(it, it.row.id in deleting) }

    /** "Trying release 2 of 3" from Resolve's "Trying release 2 of 3 · Checking availability"; null on its first release. */
    private fun trying(download: TitleDownload): String? = download.reason?.takeIf { it.startsWith(TRYING) }?.substringBefore(" · ")

    /** "Downloading · 42%": how far, once its file is made. */
    private fun far(words: String, download: TitleDownload) = download.percent?.let { "$words · $it%" } ?: words

    private fun fraction(download: TitleDownload): Float = (download.percent ?: 0) / 100f

    private fun missing(download: TitleDownload): String? = when (val parts = download.partsMissing) {
        0 -> null
        1 -> "1 part missing"
        else -> "$parts parts missing"
    }

    /** A wait for a network or the charger, which ends by itself: the queue is there, and Pause holds it. */
    private fun holdsOnNeed(download: TitleDownload): Boolean = DownloadHostRules.waitsForNeed(download.reason)
}

/**
 * Whether a Usenet provider is saved and on, with its password readable (L370): the Download entries show only then, as a
 * user with only debrid services has none to download from until DN2.
 */
fun usenetUsable(settings: SettingsRepository): Flow<Boolean> =
    settings.providersWithPasswords.map { providers -> providers.any { it.record.enabled && it.password != null } }.distinctUntilChanged()

/**
 * Show [showTmdbId]'s downloads by episode, as its page's rows say them, at most four times a second, each finished one
 * the runner says goes tomorrow so marked ([TitleDownloads.byEpisode], L415).
 */
fun showDownloads(container: AppContainer, showTmdbId: Int): Flow<Map<Pair<Int, Int>, TitleDownload>> =
    combine(container.downloads.forShow(showTmdbId).atMostEvery(PROGRESS_REDRAW_MILLIS), container.downloads.deletingTomorrow, TitleDownloads::byEpisode)
        .distinctUntilChanged()

/** Film [movieTmdbId]'s download, as its Download button says it, at most four times a second, going tomorrow or not (L415). */
fun movieDownload(container: AppContainer, movieTmdbId: Int): Flow<TitleDownload?> =
    combine(container.downloads.forMovie(movieTmdbId).atMostEvery(PROGRESS_REDRAW_MILLIS), container.downloads.deletingTomorrow) { item, deleting ->
        item?.let { TitleDownload.of(it, it.row.id in deleting) }
    }.distinctUntilChanged()

/**
 * At most one value each [millis], the latest: the writer says each chunk it writes (about 30 times a second), and a page
 * needs no more than how far it is as it goes.
 */
internal fun <T> Flow<T>.atMostEvery(millis: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(millis)
}

/** How often a page draws a download's progress again, at most: four times a second, as the Downloads page does. */
internal const val PROGRESS_REDRAW_MILLIS = 250L

/** Removed downloads' files go this long after their Undo bar has, so an Undo in its last moment still finds them. */
internal const val UNDO_SLACK_MILLIS = 1_000L

/**
 * What a title's download entries do on the Show page, the Movie page and Search's card (L379, L380), as the Downloads
 * page does: Pause holds the whole queue; Resume lets it go on, then asks for its host from here, the visible app (L335);
 * Download again tries a failed one again from its releases, then asks for its host; Remove hides it at once with the
 * Undo bar ([offer]), its files going a second after the bar has ([UNDO_SLACK_MILLIS]), and Undo brings it back, then asks
 * for its host. Download (the sheet) and Open Downloads (the page) are the page's own. The runner's calls run in [work],
 * the app's scope, so one asked as the page closes is still made; the bar is the page's ([scope]).
 */
class TitleDownloadActions(private val deps: Deps, scope: CoroutineScope, private val work: CoroutineScope) {
    /** The runner's calls, the host's schedule, a row read for the bar's words, and the bar's time before TalkBack's ([undoMillis]). */
    class Deps(
        val get: suspend (id: String) -> Download?,
        val pause: suspend () -> Unit,
        val resume: suspend () -> Unit,
        val remove: suspend (ids: Collection<String>, keepFor: Long) -> Unit,
        val undoRemove: suspend (ids: Collection<String>) -> Unit,
        val retry: suspend (id: String) -> Unit,
        val schedule: suspend (ScheduleCause) -> Unit,
        val undoMillis: Long = UNDO_MS,
    ) {
        companion object {
            /** The app's: its runner, its table and its host's schedule. */
            fun of(container: AppContainer) = Deps(
                get = container.data.downloads::get,
                pause = container.downloads::pause,
                resume = container.downloads::resume,
                remove = { ids, keepFor -> container.downloads.remove(ids, keepFor) },
                undoRemove = container.downloads::undoRemove,
                retry = container.downloads::retry,
                schedule = { cause -> container.downloadHosts.schedule(cause) },
            )
        }
    }

    private val undo = UndoTimer<List<String>>(scope)

    /** The Undo bar's offer after a Remove: "Deleted Harbor Lights S02E04 · 1.3 GB". */
    val offer: StateFlow<UndoTimer.Offer<List<String>>?> = undo.offer

    /** The bar's time before TalkBack's own. */
    val undoMillis: Long get() = deps.undoMillis

    /** Does [entry] for [download]; a Remove's files go [keepFor] (the bar's own time) and a second after. */
    fun take(entry: DownloadEntry, download: TitleDownload, keepFor: Long) {
        when (entry) {
            DownloadEntry.Pause -> act("pause the downloads") { deps.pause() }
            DownloadEntry.Resume -> act("resume the downloads") {
                deps.resume()
                deps.schedule(ScheduleCause.Resume)
            }
            DownloadEntry.DownloadAgain -> act("try a download again") {
                deps.retry(download.id)
                deps.schedule(ScheduleCause.Download)
            }
            DownloadEntry.Remove -> act("remove a download") {
                val row = deps.get(download.id) ?: return@act
                undo.offer(DownloadsPage.deleted(listOf(row)), listOf(row.id))
                deps.remove(listOf(row.id), keepFor + UNDO_SLACK_MILLIS)
            }
            DownloadEntry.Download, DownloadEntry.OpenDownloads -> Unit
        }
    }

    /**
     * The bar's Undo: what it named comes back as it was, while its time lasts, then the host is asked for from here (L335):
     * one removed as it ran ended its host's walk, and is queued again with nothing to take it up.
     */
    fun undo() {
        val ids = undo.take() ?: return
        act("bring a download back") {
            deps.undoRemove(ids)
            deps.schedule(ScheduleCause.Resume)
        }
    }

    /** The bar is on screen: it goes [timeoutMs] later. */
    fun shown(timeoutMs: Long) = undo.shown(timeoutMs)

    private fun act(what: String, action: suspend () -> Unit) {
        work.launch { logged(what, action) }
    }
}

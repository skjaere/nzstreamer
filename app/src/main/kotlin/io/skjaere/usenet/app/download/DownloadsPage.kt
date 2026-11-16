package io.skjaere.usenet.app.download

import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.WatchState
import java.util.Locale

/** How a line of the Downloads page is coloured, by the app's colours' names. */
enum class Tone { Accent, Text, Secondary, Muted, Warn, WarnStrong }

/** One line of a download's row: its [text] in its [tone]. */
data class PageLine(val text: String, val tone: Tone)

/** The bar under a download still to finish: [Part] of it written, or [Finding] while Resolve finds its release. */
sealed interface PageBar {
    data class Part(val fraction: Float, val tone: Tone) : PageBar

    data object Finding : PageBar
}

/** The button at the end of the row the queue is at (L380): Pause holds the whole queue; Resume lets it go on. */
enum class RowButton { Pause, Resume }

/**
 * What a download still to finish says on the page: its state's [lines] (Resolve's step on a line of its own after
 * "Trying release 2 of 3"), its [bar], how far it is ([bytes]: "1.3 of 3.1 GB · 8.4 MB/s · about 4 min left"), and on the
 * row the queue is at, its [button].
 */
data class ActiveWords(val lines: List<PageLine>, val bar: PageBar?, val bytes: String?, val button: RowButton?)

/** What a show's downloads play next ([DownloadsShow.next]): an episode begun, [Resume]d at [positionMs], or the next to [Play]. */
sealed interface ShowNext {
    val row: Download

    data class Resume(override val row: Download, val positionMs: Long) : ShowNext

    data class Play(override val row: Download) : ShowNext
}

/** A row of the Downloads page; in select mode, one target that selects every download it stands for. */
sealed interface DownloadsRow {
    /** Its key in the list and in select mode. */
    val key: String

    /** The downloads it stands for: a show's are its finished episodes. */
    val downloads: List<Download>
    val title: String
    val posterPath: String?

    /** What deleting it gives back: what its files hold, each allocated whole; a queued one's, nothing yet. */
    val held: Long get() = downloads.sumOf(DownloadsPage::held)

    /** A download still to finish: under way, held, waiting or queued; [current]: the one the queue is at. [up] and [down]: whether a queued one can move. */
    data class Active(val item: DownloadRunner.Item, val current: Boolean, val up: Boolean = false, val down: Boolean = false) : DownloadsRow {
        val row: Download get() = item.row
        override val key: String get() = row.id
        override val downloads: List<Download> get() = listOf(row)
        override val title: String get() = row.title
        override val posterPath: String? get() = row.posterPath
    }

    /** A download that failed, for Try again or another release. */
    data class Failed(val row: Download) : DownloadsRow {
        override val key: String get() = row.id
        override val downloads: List<Download> get() = listOf(row)
        override val title: String get() = row.title
        override val posterPath: String? get() = row.posterPath
    }

    /** A show's finished episodes, by season and episode, and what it plays [next]. */
    data class Show(
        val showTmdbId: Int,
        override val title: String,
        override val posterPath: String?,
        val episodes: List<Download>,
        val next: ShowNext?,
    ) : DownloadsRow {
        override val key: String get() = "show-$showTmdbId"
        override val downloads: List<Download> get() = episodes
    }

    /** A finished film, and whether it is [watched] (its ⋮ marks it the other way). */
    data class Film(val row: Download, val watched: Boolean = false) : DownloadsRow {
        override val key: String get() = row.id
        override val downloads: List<Download> get() = listOf(row)
        override val title: String get() = row.title
        override val posterPath: String? get() = row.posterPath
    }
}

/** The space strip's numbers: what downloads [used] (the running one whole) and what is [free] for more. */
data class DownloadsStrip(val used: Long, val free: Long) {
    /** How much of the bar is downloads'. */
    val fraction: Float get() = if (used + free <= 0) 0f else used.toFloat() / (used + free)
}

/**
 * The Downloads page (L372, L379-L381): [downloading] (the row the queue is at first, then the rest in the queue's order),
 * [failed] under it, and [downloaded], one row per show and one per film, the latest finished first. Names come from the
 * rows, so it reads the same offline (L368). Pure, so the JVM reads every case; the screen only draws it.
 */
data class DownloadsPage(
    val downloading: List<DownloadsRow.Active> = emptyList(),
    val failed: List<DownloadsRow.Failed> = emptyList(),
    val downloaded: List<DownloadsRow> = emptyList(),
) {
    /** Every row, in the page's order: what select mode selects from. */
    val rows: List<DownloadsRow> get() = downloading + failed + downloaded

    val empty: Boolean get() = rows.isEmpty()

    companion object {
        const val TITLE = "Downloads"
        const val SELECT = "Select"
        const val SETTINGS = "Downloads settings"
        const val DOWNLOADING = "Downloading"
        const val FAILED = "Failed"
        const val DOWNLOADED = "Downloaded"
        const val QUEUED = "Queued · searched when its turn comes"
        const val PAUSED = "Paused"
        const val PAUSE = "Pause downloads"
        const val RESUME = "Resume"
        const val TRY_AGAIN = "Try again"
        const val CHOOSE_RELEASE = "Choose release"
        const val CHOOSE_ANOTHER = "Choose another release"
        const val CANCEL = "Cancel download"
        const val MOVE_UP = "Move up"
        const val MOVE_DOWN = "Move down"
        const val REMOVE = "Remove"
        const val REMOVE_DOWNLOAD = "Remove download"
        const val MARK_WATCHED = "Mark watched"
        const val MARK_UNWATCHED = "Mark unwatched"
        const val NEEDS_CONNECTION = "Needs a connection"
        const val MOBILE_DATA = "On mobile data: downloads wait for Wi-Fi or Ethernet."
        const val RESTRICTED = "Android limits NZStreamer in the background: downloads go on only while it is open."
        const val APP_SETTINGS = "App settings"
        const val EMPTY = "Nothing downloaded yet"
        const val EMPTY_LINE = "Download an episode or a film from its page."
        const val STAYS = "Downloads stay on this device, in NZStreamer's own storage."
        const val MISSING_PARTS = "Your provider is missing parts of this release"

        /** The states of a download still to finish: on the page under Downloading. */
        private val ACTIVE = setOf(
            DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting, DownloadState.Stopped,
        )

        /**
         * The page for [items] (the runner's, in the queue's order), what each show's episodes' watch states are
         * ([episodeStates], by show) and the films' ([films], by film): for each show what it plays next, for each film
         * whether it is watched.
         */
        fun of(
            items: List<DownloadRunner.Item>,
            episodeStates: Map<Int, Map<Pair<Int, Int>, WatchState>> = emptyMap(),
            films: Map<Int, MovieWatch> = emptyMap(),
        ): DownloadsPage {
            val shown = items.filter { it.row.state != DownloadState.Deleting }
            val active = shown.filter { it.row.state in ACTIVE }
            // The row the queue is at: the one being found or written, else one held or waiting.
            val current = active.firstOrNull { it.row.state == DownloadState.Running || it.row.state == DownloadState.Resolving }
                ?: active.firstOrNull { it.row.state != DownloadState.Queued }
            val queued = active.filter { it.row.state == DownloadState.Queued }
            val downloading = (listOfNotNull(current) + active.filter { it !== current }).map { item ->
                val at = queued.indexOf(item)
                DownloadsRow.Active(item, current = item === current, up = at > 0, down = at >= 0 && at < queued.lastIndex)
            }
            val failed = shown.filter { it.row.state == DownloadState.Failed }.map { DownloadsRow.Failed(it.row) }
            val done = shown.map { it.row }.filter { it.state == DownloadState.Done }
            val shows = done.filter { it.kind == DownloadKind.Episode && it.showTmdbId != null }.groupBy { it.showTmdbId!! }.map { (id, rows) ->
                val latest = rows.sortedByDescending { it.completedAt ?: it.createdAt }
                val episodes = DownloadsShow.ordered(rows)
                val next = DownloadsShow.next(episodes, episodeStates[id].orEmpty())
                DownloadsRow.Show(id, latest.first().title, latest.firstNotNullOfOrNull { it.posterPath }, episodes, next)
            }
            val movies = done.filter { it.kind == DownloadKind.Movie }.map { row -> DownloadsRow.Film(row, row.movieTmdbId?.let { films[it] }?.watched == true) }
            val downloaded = (shows + movies).sortedByDescending { row -> row.downloads.maxOf { it.completedAt ?: it.createdAt } }
            return DownloadsPage(downloading, failed, downloaded)
        }

        /** What deleting [row] gives back: its file's whole size once it is made (allocated whole, C8); nothing before. */
        fun held(row: Download): Long = if (row.relativePath != null) row.size ?: row.indexerSize else 0

        /** "S02E04 · Low Water" under an episode's show; a film's year. */
        fun sub(row: Download): String? = when (row.kind) {
            DownloadKind.Episode -> listOfNotNull(label(row), row.episodeName).joinToString(" · ").ifEmpty { null }
            DownloadKind.Movie -> row.year?.toString()
        }

        /** Choose release's title for [row]: "Download S02E04", "Download Copper Crown", as the list in download mode says it. */
        fun choosing(row: Download): String = "Download ${label(row) ?: row.title}"

        /** "S02E04": an episode's season and number; null for a film. */
        fun label(row: Download): String? {
            val season = row.season ?: return null
            val episode = row.episode ?: return null
            return "S%02dE%02d".format(Locale.ROOT, season, episode)
        }

        /**
         * What [item], a download still to finish, says (L380, L382, L388): its state in the page's words, with how far it
         * is once its file is made; the bar; the bytes, with the rate and time left while it is written; and the button of
         * the row the queue is at ([current]): Pause while it runs or waits for its network or the charger, Resume while
         * it is held, or waits for what only a Resume or a saved provider ends.
         */
        fun active(item: DownloadRunner.Item, current: Boolean): ActiveWords {
            val row = item.row
            val total = row.size ?: row.indexerSize
            val made = row.relativePath != null && total > 0
            val written = item.written.coerceIn(0, total.coerceAtLeast(0))
            val far = if (made) " · ${written * 100 / total}%" else ""
            val bytes = if (made) DownloadHostRules.progress(written, total) else null
            val share = (written.toDouble() / total).toFloat()
            val held = if (made) PageBar.Part(share, Tone.Muted) else null
            val words = when (row.state) {
                DownloadState.Queued -> ActiveWords(listOf(PageLine(QUEUED, Tone.Muted)), null, null, null)
                DownloadState.Resolving -> {
                    // "Trying release 2 of 3 · Checking availability": which release, then its step on a line of its own.
                    val step = row.reason ?: DownloadHostRules.FINDING_RELEASES
                    val lines = if (step.startsWith(TRYING)) {
                        step.split(" · ", limit = 2).mapIndexed { i, part -> PageLine(part, if (i == 0) Tone.Accent else Tone.Muted) }
                    } else {
                        listOf(PageLine(step, Tone.Accent))
                    }
                    ActiveWords(lines, PageBar.Finding, null, RowButton.Pause)
                }
                DownloadState.Running -> when (val reason = row.reason) {
                    null -> {
                        val rate = item.bytesPerSecond?.takeIf { it > 0 }
                        val left = DownloadHostRules.timeLeft(total - written, rate)
                        val line = listOfNotNull(bytes, rate?.let { "${formatSize(it)}/s" }, left?.let { "about $it" }).joinToString(" · ").ifEmpty { null }
                        ActiveWords(listOf(PageLine("$DOWNLOADING$far", Tone.Accent)), if (made) PageBar.Part(share, Tone.Accent) else null, line, RowButton.Pause)
                    }
                    // The provider out of reach while the writer backs off (L382).
                    else -> ActiveWords(listOf(PageLine("$reason$far", Tone.Warn)), held, bytes, RowButton.Pause)
                }
                DownloadState.Paused -> ActiveWords(listOf(PageLine("$PAUSED$far", Tone.Muted)), held, bytes, RowButton.Resume)
                DownloadState.Waiting -> {
                    val reason = row.reason
                    if (DownloadHostRules.waitsForNeed(reason)) {
                        ActiveWords(listOf(PageLine("$reason$far", Tone.Secondary)), held, bytes, RowButton.Pause)
                    } else {
                        // The provider, the indexers or storage (L388): nothing goes on until Resume or a provider saved.
                        ActiveWords(listOf(PageLine(reason ?: WAITING, Tone.Warn)), held, bytes, RowButton.Resume)
                    }
                }
                DownloadState.Stopped -> ActiveWords(listOf(PageLine("${DownloadHostRules.STOPPED_BY_ANDROID}$far", Tone.WarnStrong)), held, bytes, RowButton.Resume)
                DownloadState.Failed, DownloadState.Done, DownloadState.Deleting -> error("Not a download still to finish: ${row.state}")
            }
            return if (current) words else words.copy(button = null)
        }

        /**
         * Why [row] failed, in technical words (L366): "✕ Your provider is missing parts of this release" for a release
         * that lacked parts, else the reason its row keeps; then how many releases were tried, where any were.
         */
        fun failure(row: Download): List<PageLine> {
            val reason = when (row.reason) {
                DownloadResolver.INCOMPLETE -> MISSING_PARTS
                null -> FAILED
                else -> row.reason!!
            }
            val tried = row.tried.size
            return listOfNotNull(
                PageLine("✕ $reason", Tone.Warn),
                PageLine("$tried release${if (tried == 1) "" else "s"} tried", Tone.Muted).takeIf { tried > 0 },
            )
        }

        /** "6 episodes · 11.2 GB": a show's finished episodes and what they hold. */
        fun showSub(episodes: List<Download>): String =
            "${episodes.size} episode${if (episodes.size == 1) "" else "s"} · ${formatSize(episodes.sumOf { it.size ?: it.indexerSize })}"

        /** "Resume S02E01" for an episode begun; "Next: S01E04" for the next to play. */
        fun nextLine(next: ShowNext): String = when (next) {
            is ShowNext.Resume -> "Resume ${label(next.row)}"
            is ShowNext.Play -> "Next: ${label(next.row)}"
        }

        /** "2022 · 1 h 52 min · 5.5 GB": a film's year, length and size. */
        fun filmSub(row: Download): String =
            listOfNotNull(row.year?.toString(), row.runtimeMinutes?.takeIf { it > 0 }?.let(::duration), formatSize(row.size ?: row.indexerSize)).joinToString(" · ")

        /** "2 parts missing": what a finished download lacks, where it lacks any. */
        fun missing(row: Download): String? = when (val parts = row.gaps.size) {
            0 -> null
            1 -> "1 part missing"
            else -> "$parts parts missing"
        }

        /** The strip's numbers for [space]: downloads' own, and what is free, those removed counted gone at once (L379). */
        fun strip(space: DownloadSpace): DownloadsStrip = DownloadsStrip(space.used, space.free + space.deleting)

        fun selected(count: Int): String = "$count selected"

        fun selectAll(count: Int): String = "Select all ($count)"

        /** "Delete · 16.7 GB": what the selected rows give back; plain "Delete" when it is nothing yet. */
        fun delete(bytes: Long): String = if (bytes > 0) "Delete · ${formatSize(bytes)}" else "Delete"

        /**
         * The Undo bar's words for [downloads] removed (L379): "Deleted Harbor Lights (6 episodes) and Copper Crown · 16.7 GB".
         * A show's episodes go together, one named by its code; past three names, how many downloads; the size where they
         * held any.
         */
        fun deleted(downloads: List<Download>): String {
            val groups = LinkedHashMap<String, MutableList<Download>>()
            downloads.forEach { row ->
                val key = row.showTmdbId?.takeIf { row.kind == DownloadKind.Episode }?.let { "show-$it" } ?: "row-${row.id}"
                groups.getOrPut(key) { mutableListOf() } += row
            }
            val names = groups.values.map { rows ->
                val first = rows.first()
                when {
                    first.kind == DownloadKind.Movie -> first.title
                    rows.size == 1 -> listOfNotNull(first.title, label(first)).joinToString(" ")
                    else -> "${first.title} (${rows.size} episodes)"
                }
            }
            val what = when (names.size) {
                1 -> names.single()
                2, 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
                else -> "${downloads.size} downloads"
            }
            val bytes = downloads.sumOf(::held)
            return "Deleted $what" + if (bytes > 0) " · ${formatSize(bytes)}" else ""
        }

        /** "1 h 52 min", "52 min". */
        internal fun duration(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 || h == 0 }?.let { "$it min" }).joinToString(" ")
        }

        private const val TRYING = "Trying release"
        private const val WAITING = "Waiting"
    }
}

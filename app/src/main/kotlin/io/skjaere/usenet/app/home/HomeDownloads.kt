package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.DownloadsPage
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState

/**
 * A row of Home's Downloads section (DlPhoneHome): its [title] and [status], each half the row as the collection's rows
 * are, the status in its [tone]; the one being written with how far it is ([progress]); and what a tap [opens]. A show's
 * row while two or more of its episodes are still to finish says how many over its status ([queue], "5 in the queue",
 * L436).
 */
data class HomeDownloadRow(
    val key: String,
    val title: String,
    val status: String,
    val tone: Tone,
    val progress: Float?,
    val opens: HomeDownloadOpen,
    val queue: String? = null,
)

/** What a row of Home's Downloads opens: what it names (DlPhoneHome). */
sealed interface HomeDownloadOpen {
    /** The Downloads page: a download still to finish, or one that failed. */
    data object Page : HomeDownloadOpen

    /** A show's downloads: its finished episodes. */
    data class Show(val showTmdbId: Int) : HomeDownloadOpen

    /** A finished film, played here from its file. */
    data class Play(val row: Download) : HomeDownloadOpen
}

/**
 * Home's Downloads section (L384, DlPhoneHome's online section), between Up next and the collection: "Downloads · 23.4 GB"
 * (what downloads [held] on the device, the running one whole, as the Downloads page's strip counts them) with See all, and
 * at most three [rows]: the one the queue is at first, then the latest. A show's finished episodes are one row, as on the
 * Downloads page; a show with episodes still to finish is one row too, its finished ones in it while its queue lasts, so a
 * season queued leaves room for the rest (W120, L436). Pure, so the JVM reads every case; Home only draws it.
 */
data class HomeDownloads(val held: Long, val rows: List<HomeDownloadRow>) {
    /** "Downloads · 23.4 GB"; "Downloads" alone while no download has its file yet (queued, or failed), never "0 KB". */
    val header: String get() = if (held > 0) "${DownloadsPage.TITLE} · ${formatSize(held)}" else DownloadsPage.TITLE

    companion object {
        /** A phone's few rows, as the collection's. */
        const val ROWS = 3

        /** The section for the runner's [items]; null while nothing is queued or downloaded, when Home is as it was. */
        fun of(items: List<DownloadRunner.Item>): HomeDownloads? {
            val shown = items.filter { it.row.state != DownloadState.Deleting }
            if (shown.isEmpty()) return null
            val active = shown.filter { it.row.state !in ENDED }
            // The one the queue is at: being found or written, else one held or waiting (the Downloads page's).
            val current = at(active)
            // A show with episodes still to finish is one row, with its finished ones while its queue lasts (L436).
            val queues = active.filter { it.row.show != null }.groupBy { it.row.show!! }
            val (failed, finished) = shown.filter { it.row.state in ENDED }.partition { it.row.state == DownloadState.Failed }
            val done = finished.filter { it.row.show !in queues }
            val shows = done.filter { it.row.show != null }.groupBy { it.row.show!! }
            val films = done.filter { it.row.show == null }
            // Each with when it was last added or finished, the latest first; a failed episode keeps its own row.
            val dated = queues.map { (id, left) -> (left + finished.filter { it.row.show == id }).maxOf { it.row.at } to queue(id, left) } +
                (active.filter { it.row.show == null } + failed).map { it.row.at to row(it) } + films.map { it.row.at to film(it.row) } +
                shows.map { (id, episodes) -> episodes.maxOf { it.row.at } to show(id, episodes.map { it.row }) }
            val first = current?.row?.let { it.show?.let(::showKey) ?: it.id }
            val (lead, rest) = dated.partition { it.second.key == first }
            val rows = lead.map { it.second } + rest.sortedByDescending { it.first }.map { it.second }
            return HomeDownloads(shown.sumOf { DownloadsPage.held(it.row) }, rows.take(ROWS))
        }

        private val ENDED = setOf(DownloadState.Done, DownloadState.Failed)

        private val Download.at: Long get() = completedAt ?: createdAt

        /** The show an episode's download is of, which its show's row gathers; none for a film. */
        private val Download.show: Int? get() = showTmdbId.takeIf { kind == DownloadKind.Episode }

        private fun showKey(showTmdbId: Int) = "show-$showTmdbId"

        /** Of [items] still to finish, the one the queue is at: being found or written, else one held or waiting. */
        private fun at(items: List<DownloadRunner.Item>): DownloadRunner.Item? =
            items.firstOrNull { it.row.state == DownloadState.Running || it.row.state == DownloadState.Resolving }
                ?: items.firstOrNull { it.row.state != DownloadState.Queued }

        /**
         * A show's episodes still to finish, [left], in the queue's order (L436): its first as its own row says it
         * ("S02E01 · downloading 42%", or "S02E05 · queued for download" while another title is written), with its bar,
         * under "5 in the queue", counting the one written as the Show page's line does; with one left, that one's row
         * alone. The Downloads page opens, where the queue is.
         */
        private fun queue(showTmdbId: Int, left: List<DownloadRunner.Item>): HomeDownloadRow =
            row(at(left) ?: left.first()).copy(key = showKey(showTmdbId), queue = left.size.takeIf { it > 1 }?.let { "$it in the queue" })

        /**
         * A download still to finish, or failed: "S02E04 · downloading 42%", its episode first, else its state alone as the
         * Movie page's button says it; the page opens. A file of an NZB opened on Import is named as its Downloads page names
         * it, with the episode its name says (L472).
         */
        private fun row(item: DownloadRunner.Item): HomeDownloadRow {
            val row = item.row
            val download = TitleDownload.of(item)
            val (words, tone) = when (row.state) {
                DownloadState.Running -> if (row.reason == null) {
                    (download.percent?.let { "${TitleDownloads.DOWNLOADING} $it%" } ?: TitleDownloads.DOWNLOADING) to Tone.Accent
                } else {
                    TitleDownloads.line(download).single().let { it.text to it.tone }
                }
                DownloadState.Paused -> (download.percent?.let { "${TitleDownloads.PAUSED} $it%" } ?: TitleDownloads.PAUSED) to Tone.Muted
                else -> TitleDownloads.line(download).let { line -> line.joinToString("") { it.text } to line.last().tone }
            }
            val code = DownloadsPage.label(row)
            val status = if (code != null) "$code · ${words.replaceFirstChar { it.lowercase() }}" else words
            val progress = download.percent?.takeIf { row.state == DownloadState.Running && row.reason == null }?.let { it / 100f }
            return HomeDownloadRow(row.id, DownloadsPage.name(row), status, tone, progress, HomeDownloadOpen.Page)
        }

        /**
         * A finished film: "Downloaded · 5.5 GB"; it plays. A finished file of an NZB opened on Import too, named as its page
         * names it, and worded as an episode's row where its name says an episode: "S01E03 · downloaded · 1.3 GB" (L472).
         */
        private fun film(row: Download): HomeDownloadRow {
            val words = "${TitleDownloads.DOWNLOADED} · ${formatSize(row.size ?: row.indexerSize)}"
            val status = DownloadsPage.label(row)?.let { code -> "$code · ${words.replaceFirstChar { it.lowercase() }}" } ?: words
            return HomeDownloadRow(row.id, DownloadsPage.name(row), status, Tone.Muted, null, HomeDownloadOpen.Play(row))
        }

        /** A show's finished episodes: "2 episodes · 3.6 GB"; its downloads open. */
        private fun show(showTmdbId: Int, episodes: List<Download>): HomeDownloadRow {
            val latest = episodes.maxBy { it.at }
            return HomeDownloadRow(showKey(showTmdbId), latest.title, DownloadsPage.showSub(episodes), Tone.Muted, null, HomeDownloadOpen.Show(showTmdbId))
        }
    }
}

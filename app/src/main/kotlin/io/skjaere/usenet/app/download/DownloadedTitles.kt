package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A title's finished downloads (L407): how many of a show's episodes, or a film's one ([count]), and their [bytes]. */
data class Downloaded(val count: Int, val bytes: Long)

/**
 * What the marks say of the finished downloads (L407): the runner's [DownloadRunner.done] by title, for the Collection's
 * Downloaded chip and its rows' marks (L408), and by watch key, for Up next's (L409). Only the finished rows the play hook's
 * launch takes count ([DownloadLaunch.accepts]), never the queue, so a mark means the file plays. Pure but for [flow].
 */
object DownloadedTitles {
    /** [done] by title: a show counts its episodes, each once however often it was downloaded; a film counts one. */
    fun of(done: Collection<Download>): Map<TitleKey, Downloaded> = done.filter(::counts)
        .mapNotNull { row -> titleOf(row)?.let { it to row } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, rows) -> rows.distinctBy { it.watchKey }.let { Downloaded(it.size, it.sumOf { row -> row.size ?: row.indexerSize }) } }

    /**
     * [done] (the runner's, by watch key) as the Collection reads it: told only when what the marks say changes, as the
     * runner tells its map again whenever a finished row is written, so the Collection's thousands of rows aren't viewed
     * again for nothing.
     */
    fun flow(done: Flow<Map<String, Download>>): Flow<Map<TitleKey, Downloaded>> = done.map { of(it.values) }.distinctUntilChanged()

    /** The watch keys whose download plays from its file: what Up next's rows look for ([io.skjaere.usenet.app.home.UpNextRow.watchKey]). */
    fun keys(done: Map<String, Download>): Set<String> = done.values.filter(::counts).mapTo(HashSet()) { it.watchKey }

    /** The same downloads' ids by watch key: whose stored poster Up next's rows draw (L411). */
    fun ids(done: Map<String, Download>): Map<String, String> = done.values.filter(::counts).associate { it.watchKey to it.id }

    /** What TalkBack adds to a collection row's sentence: "3 episodes downloaded." for a show, "Downloaded." for a film. */
    fun spoken(downloaded: Downloaded, show: Boolean): String =
        if (show) "${downloaded.count} episode${if (downloaded.count == 1) "" else "s"} downloaded." else "Downloaded."

    /** A row's sentence [a11y] with [spoken]'s after it where it has a download, a stop between where it ends with none. */
    fun said(a11y: String, downloaded: Downloaded?, show: Boolean): String {
        val words = downloaded?.let { spoken(it, show) } ?: return a11y
        return if (a11y.endsWith('.') || a11y.endsWith('…')) "$a11y $words" else "$a11y. $words"
    }

    private fun counts(row: Download): Boolean = row.state == DownloadState.Done && DownloadLaunch.accepts(row)

    private fun titleOf(row: Download): TitleKey? = when (row.kind) {
        DownloadKind.Episode -> row.showTmdbId?.let(TitleKey::show)
        DownloadKind.Movie -> row.movieTmdbId?.let(TitleKey::movie)
    }
}

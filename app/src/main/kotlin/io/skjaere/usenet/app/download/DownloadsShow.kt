package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.collection.mmss
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchState

/**
 * A show's downloads (L372): its finished episodes by season, what Resume at the top plays, and each episode's words.
 * Pure, so the JVM reads every case; DownloadsShowScreen only draws it. Names come from the rows, so it reads the same
 * offline (L368).
 */
object DownloadsShow {
    /** A season's finished episodes, in order. */
    data class Season(val number: Int, val episodes: List<Download>)

    /** [rows]' finished episodes, by season and episode. */
    fun ordered(rows: List<Download>): List<Download> =
        rows.filter { it.state == DownloadState.Done && it.kind == DownloadKind.Episode && it.season != null && it.episode != null }
            .sortedWith(compareBy({ it.season }, { it.episode }, { it.createdAt }))

    /** [rows]' finished episodes by season, each season's in order. */
    fun seasons(rows: List<Download>): List<Season> = ordered(rows).groupBy { it.season!! }.map { (number, episodes) -> Season(number, episodes) }

    /**
     * What Resume at the top plays (L372), by [states], the show's episodes' watch states by season and episode: the episode
     * begun most lately among those downloaded and not watched, from where it stopped; else the first downloaded one not
     * watched after the furthest episode watched, or the first not watched where none comes after it. Null once every
     * downloaded episode is watched.
     */
    fun next(rows: List<Download>, states: Map<Pair<Int, Int>, WatchState>): ShowNext? {
        fun state(row: Download) = states[row.season!! to row.episode!!]
        val unwatched = ordered(rows).filter { state(it)?.watched != true }
        if (unwatched.isEmpty()) return null
        val begun = unwatched.filter { (state(it)?.positionMs ?: 0) >= LibraryRepository.MIN_RESUME_MS }.maxByOrNull { state(it)!!.lastPlayedAt }
        if (begun != null) return ShowNext.Resume(begun, state(begun)!!.positionMs)
        val furthest = states.filterValues { it.watched }.keys.maxWithOrNull(EPISODE_ORDER)
        val after = furthest?.let { watched -> unwatched.firstOrNull { EPISODE_ORDER.compare(it.season!! to it.episode!!, watched) > 0 } }
        return ShowNext.Play(after ?: unwatched.first())
    }

    /** Resume's words: "Resume S02E01 · 20:00" for one begun, "Play S01E04" for the next. */
    fun button(next: ShowNext): String = when (next) {
        is ShowNext.Resume -> "Resume ${DownloadsPage.label(next.row)} · ${mmss(next.positionMs)}"
        is ShowNext.Play -> "Play ${DownloadsPage.label(next.row)}"
    }

    /** "Season 1"; season 0's are TMDB's specials. */
    fun seasonTitle(number: Int): String = if (number == 0) "Specials" else "Season $number"

    /** An episode's name, or its number where TMDB gave none. */
    fun name(row: Download): String = row.episodeName ?: "Episode ${row.episode}"

    /**
     * "1.7 GB · 52 min": an episode's size and length, its length as the row keeps it, else as its last play measured it
     * ([state]); its missing parts follow ([DownloadsPage.missing]).
     */
    fun meta(row: Download, state: WatchState?): String {
        val minutes = row.runtimeMinutes?.takeIf { it > 0 } ?: state?.durationMs?.takeIf { it > 0 }?.let { (it / 60_000).toInt() }?.takeIf { it > 0 }
        return listOfNotNull(formatSize(row.size ?: row.indexerSize), minutes?.let(DownloadsPage::duration)).joinToString(" · ")
    }

    /** "32:00 left" of an episode begun and not watched, as its row on the Show page says it; null otherwise. */
    fun left(state: WatchState?): String? = begun(state)?.let { "${mmss(it.durationMs - it.positionMs)} left" }

    /** How far an episode begun is, for its 2 dp bar; null otherwise. */
    fun progress(state: WatchState?): Float? = begun(state)?.let { it.positionMs.toFloat() / it.durationMs }

    private fun begun(state: WatchState?): WatchState? =
        state?.takeIf { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS && it.durationMs > 0 }

    private val EPISODE_ORDER: Comparator<Pair<Int, Int>> = compareBy({ it.first }, { it.second })
}

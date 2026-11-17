package io.skjaere.usenet.app.collection.model

import androidx.compose.runtime.Immutable
import io.skjaere.usenet.app.download.Downloaded

/** A filter chip: its setting's [name], what it reads, how many titles it holds with the current search. */
data class Chip(val name: String, val label: String, val count: Int, val selected: Boolean)

/** A–Z's letters ('#', then A to Z), each letter's first row and how many rows it has; letters without rows are left out of [first]. */
data class LetterIndex(val letters: List<Char>, val first: Map<Char, Int>, val counts: Map<Char, Int>) {
    /** The first row of [letter], else of the next letter with rows, else of the last letter with rows. */
    fun indexFor(letter: Char): Int {
        val at = letters.indexOf(letter).coerceAtLeast(0)
        return letters.drop(at).firstNotNullOfOrNull { first[it] } ?: letters.take(at).asReversed().firstNotNullOfOrNull { first[it] } ?: 0
    }
}

/** Why a side shows no rows. */
sealed interface Empty {
    /** Nothing collected on this side. */
    data object NoTitles : Empty

    /** The filter (its setting's name) has nothing. */
    data class FilterEmpty(val filter: String) : Empty

    /** Nothing in the filter matches [query]; [elsewhere] match under All (0 when the filter is All). */
    data class SearchEmpty(val query: String, val elsewhere: Int) : Empty
}

/** One side of the collection as the list shows it. [pending]: rows still waiting for TMDB; [total]: every title of the side. */
@Immutable
data class SideView(
    val rows: List<TitleRow>,
    val chips: List<Chip>,
    val header: String,
    val letters: LetterIndex?,
    val empty: Empty?,
    val pending: Int,
    val total: Int,
)

/** The rows a side shows for a filter, a sort and what is typed in the search, with the chips' counts and the header line. */
object CollectionView {
    private val LETTERS = listOf('#') + ('A'..'Z')

    /**
     * [downloaded]: the finished downloads by title (L408), empty where downloads don't show (a TV). Its chip, Downloaded,
     * comes second while a show here has one, and under it the header counts those shows and their episodes.
     */
    fun shows(
        rows: List<ShowRow>,
        filter: ShowFilter,
        sort: ShowSort,
        text: String,
        frozenOrder: List<TitleKey>? = null,
        downloaded: Map<TitleKey, Downloaded> = emptyMap(),
    ): SideView {
        val matching = search(rows, text)
        val newEpisodes = rows.count { it.newEpisodes > 0 }
        val chip = hasDownload(rows, downloaded)
        val shown = shown(filter, rows, downloaded)
        val visible = sorted(matching.filter { inFilter(it, shown, downloaded) }, sort)
        return view(
            matching, total = rows.size, pending = rows.count { it.load == LoadState.Pending }, text = text,
            chips = ShowFilter.entries.filter { it != ShowFilter.Downloaded || chip }.map { f -> Chip(f.name, f.label, matching.count { inFilter(it, f, downloaded) }, f == shown) },
            visible = visible, frozenOrder = frozenOrder, az = sort == ShowSort.AZ,
            filter = shown.name, all = shown == ShowFilter.All, noun = "show",
            summary = if (newEpisodes > 0) "$newEpisodes with new episodes" else null,
            downloadedHead = if (shown == ShowFilter.Downloaded) {
                "${count(visible.size, "show")} · ${count(visible.sumOf { downloaded[it.key]?.count ?: 0 }, "episode")} downloaded"
            } else {
                null
            },
        )
    }

    /** As [shows], for films: under Downloaded the header counts them ("1 movie downloaded"). */
    fun movies(
        rows: List<MovieRow>,
        filter: MovieFilter,
        sort: MovieSort,
        text: String,
        frozenOrder: List<TitleKey>? = null,
        downloaded: Map<TitleKey, Downloaded> = emptyMap(),
    ): SideView {
        val matching = search(rows, text)
        val unwatched = rows.count { it.state == MovieState.NotWatched }
        val chip = hasDownload(rows, downloaded)
        val shown = shown(filter, rows, downloaded)
        val visible = sorted(matching.filter { inFilter(it, shown, downloaded) }, sort)
        return view(
            matching, total = rows.size, pending = rows.count { it.load == LoadState.Pending }, text = text,
            chips = MovieFilter.entries.filter { it != MovieFilter.Downloaded || chip }.map { f -> Chip(f.name, f.label, matching.count { inFilter(it, f, downloaded) }, f == shown) },
            visible = visible, frozenOrder = frozenOrder, az = sort == MovieSort.AZ,
            filter = shown.name, all = shown == MovieFilter.All, noun = "movie",
            summary = if (unwatched > 0) "$unwatched unwatched" else null,
            downloadedHead = if (shown == MovieFilter.Downloaded) "${count(visible.size, "movie")} downloaded" else null,
        )
    }

    /**
     * Each started show with facts is in one of Watching, Up to date and Finished. Without facts a show
     * is Not started while nothing of it played, and otherwise in All only. Downloaded, beside those, holds
     * the shows among [downloaded].
     */
    fun inFilter(row: ShowRow, filter: ShowFilter, downloaded: Map<TitleKey, Downloaded> = emptyMap()): Boolean = when (filter) {
        ShowFilter.All -> true
        ShowFilter.Downloaded -> row.key in downloaded
        ShowFilter.Watching -> row.state == ShowState.Watching
        ShowFilter.NewEpisodes -> row.newEpisodes > 0
        ShowFilter.NotStarted -> row.state == ShowState.NotStarted
        ShowFilter.UpToDate -> row.state == ShowState.UpToDate
        ShowFilter.Finished -> row.state == ShowState.Finished
    }

    /**
     * The chips other than All and Downloaded split the movies with facts; In progress and Watched need only the watch
     * state. Downloaded, beside those, holds the films among [downloaded].
     */
    fun inFilter(row: MovieRow, filter: MovieFilter, downloaded: Map<TitleKey, Downloaded> = emptyMap()): Boolean = when (filter) {
        MovieFilter.All -> true
        MovieFilter.Downloaded -> row.key in downloaded
        MovieFilter.Unwatched -> row.state == MovieState.NotWatched
        MovieFilter.InProgress -> row.state == MovieState.InProgress
        MovieFilter.Watched -> row.state == MovieState.Watched
        MovieFilter.NotYetReleased -> row.state == MovieState.Upcoming
    }

    /** [filter] as [shows] shows it: Downloaded reads as All while no show of [rows] has a download, as its chip is gone (L408). */
    fun shown(filter: ShowFilter, rows: List<ShowRow>, downloaded: Map<TitleKey, Downloaded>): ShowFilter =
        if (filter == ShowFilter.Downloaded && !hasDownload(rows, downloaded)) ShowFilter.All else filter

    /** [filter] as [movies] shows it: Downloaded reads as All while no film of [rows] has a download (L408). */
    fun shown(filter: MovieFilter, rows: List<MovieRow>, downloaded: Map<TitleKey, Downloaded>): MovieFilter =
        if (filter == MovieFilter.Downloaded && !hasDownload(rows, downloaded)) MovieFilter.All else filter

    /** Whether a title of the side has a download, whatever the search leaves: only then is the Downloaded chip there. */
    private fun hasDownload(rows: List<TitleRow>, downloaded: Map<TitleKey, Downloaded>): Boolean =
        downloaded.isNotEmpty() && rows.any { it.key in downloaded }

    /** Every sort ends by title, then by key, so the order stays the same from one build to the next. */
    fun sorted(rows: List<ShowRow>, sort: ShowSort): List<ShowRow> = rows.sortedWith(
        when (sort) {
            ShowSort.RecentlyWatched -> compareByDescending<ShowRow> { it.lastWatchedAt }.thenByDescending { it.addedAt }
            ShowSort.NewEpisodes -> compareByDescending<ShowRow> { it.newEpisodes }
                .thenBy(nullsLast(reverseOrder())) { it.lastAired }
                .thenByDescending { it.progress?.unwatched ?: -1 }
            ShowSort.NextToAir -> compareBy<ShowRow, java.time.LocalDate?>(nullsLast()) { it.upcoming }
            ShowSort.RecentlyAdded -> compareByDescending { it.addedAt }
            ShowSort.AZ -> compareBy { 0 }
        }.thenBy { it.text.sortKey }.thenBy { it.key.value },
    )

    fun sorted(rows: List<MovieRow>, sort: MovieSort): List<MovieRow> = rows.sortedWith(
        when (sort) {
            MovieSort.RecentlyAdded -> compareByDescending<MovieRow> { it.addedAt }
            MovieSort.RecentlyWatched -> compareByDescending<MovieRow> { it.lastWatchedAt }.thenByDescending { it.addedAt }
            MovieSort.AZ -> compareBy { 0 }
            // Upcoming first (the latest dates), unknown dates last.
            MovieSort.ReleaseDate -> compareBy<MovieRow, java.time.LocalDate?>(nullsLast(reverseOrder())) { it.releaseDate }
        }.thenBy { it.text.sortKey }.thenBy { it.key.value },
    )

    private fun <R : TitleRow> search(rows: List<R>, text: String): List<R> {
        val tokens = TitleSearch.tokens(text)
        return if (tokens.isEmpty()) rows else rows.filter { it.text.matches(tokens) }
    }

    private fun view(
        matching: List<TitleRow>,
        total: Int,
        pending: Int,
        text: String,
        chips: List<Chip>,
        visible: List<TitleRow>,
        frozenOrder: List<TitleKey>?,
        az: Boolean,
        filter: String,
        all: Boolean,
        noun: String,
        summary: String?,
        downloadedHead: String? = null,
    ): SideView {
        val rows = frozenOrder?.let { order ->
            val at = order.withIndex().associate { (i, key) -> key to i }
            // Stable: rows the frozen order doesn't know keep their sorted order, after the others.
            visible.sortedBy { at[it.key] ?: Int.MAX_VALUE }
        } ?: visible
        val query = text.trim()
        val searching = TitleSearch.tokens(text).isNotEmpty()
        val head = if (searching) "${rows.size} of ${count(total, noun)} ${if (rows.size == 1) "matches" else "match"} “$query”"
        else downloadedHead ?: listOfNotNull(count(total, noun), summary).joinToString(" · ")
        val empty = when {
            total == 0 -> Empty.NoTitles
            rows.isNotEmpty() -> null
            searching -> Empty.SearchEmpty(query, if (all) 0 else matching.size)
            else -> Empty.FilterEmpty(filter)
        }
        return SideView(
            rows = rows,
            chips = chips,
            header = head + if (pending > 0) " · checking $pending" else "",
            letters = if (az) letterIndex(rows) else null,
            empty = empty,
            pending = pending,
            total = total,
        )
    }

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

    /** [rows]' letters, each one's first row in their order and how many there are: A–Z's index, and the letter grid's counts in any sort. */
    fun letterIndex(rows: List<TitleRow>): LetterIndex {
        val first = HashMap<Char, Int>()
        val counts = HashMap<Char, Int>()
        rows.forEachIndexed { i, row ->
            val letter = row.text.letter
            first.putIfAbsent(letter, i)
            counts[letter] = (counts[letter] ?: 0) + 1
        }
        return LetterIndex(LETTERS, first, counts)
    }
}

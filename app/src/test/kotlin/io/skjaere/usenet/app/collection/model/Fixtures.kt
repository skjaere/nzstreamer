package io.skjaere.usenet.app.collection.model

import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.ShowWatchAggregate
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import java.time.LocalDate

/** A Wednesday in the middle of a year, so "this year" dates leave the year out. */
internal val today: LocalDate = LocalDate.of(2026, 10, 14)

internal fun collected(id: Int, name: String = "Show $id", addedAt: Long = id.toLong(), year: Int? = 2021) =
    CollectedShow(CollectedShow.idFor(id), id, name = name, year = year, addedAt = addedAt, updatedAt = 0)

internal fun collectedMovie(id: Int, title: String = "Film $id", year: Int? = 2019, addedAt: Long = id.toLong()) =
    CollectedMovie(CollectedMovie.idFor(id), id, title = title, year = year, addedAt = addedAt, updatedAt = 0)

/** Regular seasons of [counts] episodes; [last] aired on [lastAired]; [next] airs on [nextAirs]. */
internal fun showDetails(
    id: Int,
    name: String = "Show $id",
    counts: List<Int> = listOf(10, 10),
    last: Pair<Int, Int>? = 2 to 10,
    lastAired: LocalDate? = LocalDate.of(2025, 3, 1),
    next: Pair<Int, Int>? = null,
    nextAirs: LocalDate? = null,
    status: String = "Returning Series",
    originalName: String? = null,
    specials: Int = 0,
) = ShowDetails(
    id, name, originalName, 2021, null, null, null, status, status == "Returning Series", 45,
    (if (specials > 0) listOf(SeasonSummary(0, "Specials", specials, null, null)) else emptyList()) +
        counts.mapIndexed { i, n -> SeasonSummary(i + 1, null, n, null, null) },
    lastEpisode = last?.let { (s, e) -> EpisodeSummary(s, e, "Episode $s.$e", lastAired) },
    nextEpisode = next?.let { (s, e) -> EpisodeSummary(s, e, "Episode $s.$e", nextAirs) },
    ids = ExternalIds(),
)

internal fun watched(show: Int, s: Int, e: Int, at: Long = 1) = WatchState(
    WatchKey.episode(show, s, e), WatchKind.Episode, showTmdbId = show, season = s, episode = e, title = "Show $show",
    durationMs = 2_700_000, watched = true, lastPlayedAt = at, updatedAt = at,
)

internal fun started(show: Int, s: Int, e: Int, positionMs: Long = 600_000, at: Long = 1) = WatchState(
    WatchKey.episode(show, s, e), WatchKind.Episode, showTmdbId = show, season = s, episode = e, title = "Show $show",
    positionMs = positionMs, durationMs = 2_700_000, lastPlayedAt = at, updatedAt = at,
)

/** What the library's aggregate query gives for [states] (WatchStateDao.showWatchAggregates), as a reference in Kotlin. */
internal fun aggregateOf(show: Int, states: List<WatchState>): ShowWatchAggregate? {
    val live = states.filter { it.kind == WatchKind.Episode && !it.deleted && it.showTmdbId == show }
    if (live.isEmpty()) return null
    val regular = live.filter { (it.season ?: 0) >= 1 }
    return ShowWatchAggregate(
        show,
        regular.filter { it.watched }.joinToString(",") { "${it.season}:${it.episode}" }.ifEmpty { null },
        regular.filter { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS }
            .joinToString(";") { "${it.season}:${it.episode}:${it.positionMs}:${it.lastPlayedAt}" }.ifEmpty { null },
        live.maxOf { it.lastPlayedAt },
    )
}

/** One show's row, built as the store builds it. */
internal fun showRow(
    show: CollectedShow,
    details: ShowDetails?,
    states: List<WatchState> = emptyList(),
    failure: String? = null,
    hasKey: Boolean = true,
    day: LocalDate = today,
): ShowRow = ShowRows.build(
    listOf(show), listOfNotNull(details?.toSummary()).associateBy { it.tmdbId }, listOfNotNull(aggregateOf(show.tmdbId, states)).associateBy { it.showTmdbId },
    failure?.let { mapOf(TitleKey.show(show.tmdbId) to it) }.orEmpty(), day, hasKey,
).single()

internal fun movieSummary(id: Int, release: LocalDate? = LocalDate.of(2019, 5, 1), runtime: Int? = 118, status: String? = "Released", original: String? = null) =
    MovieSummary(id, original, runtime, release?.toEpochDay(), status)

internal fun movieWatch(id: Int, positionMs: Long = 0, durationMs: Long = 7_080_000, watched: Boolean = false, at: Long = 1) =
    MovieWatch(id, positionMs, durationMs, watched, at)

internal fun movieRow(
    movie: CollectedMovie,
    summary: MovieSummary?,
    watch: MovieWatch? = null,
    failure: String? = null,
    hasKey: Boolean = true,
    day: LocalDate = today,
): MovieRow = MovieRows.build(
    listOf(movie), listOfNotNull(summary).associateBy { it.tmdbId }, listOfNotNull(watch).associateBy { it.movieTmdbId },
    failure?.let { mapOf(TitleKey.movie(movie.tmdbId) to it) }.orEmpty(), day, hasKey,
).single()

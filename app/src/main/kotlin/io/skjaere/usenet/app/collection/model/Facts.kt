package io.skjaere.usenet.app.collection.model

import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.SeasonCounts
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.ShowWatchAggregate
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.EpisodeWatch
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The show as the stored facts tell it, with what [ShowProgress][io.skjaere.usenet.metadata.ShowProgress]
 * reads (regular seasons and their counts, the last and next episodes): its progress is the one the
 * Show page computes from TMDB's whole answer. Name, year and poster are the collection's.
 */
fun ShowSummary.toDetails(show: CollectedShow): ShowDetails = ShowDetails(
    tmdbId = show.tmdbId,
    name = show.name,
    originalName = originalName,
    year = show.year,
    overview = null,
    posterPath = show.posterPath,
    backdropPath = null,
    status = status,
    inProduction = false,
    episodeRuntimeMinutes = null,
    seasons = SeasonCounts.parse(seasons).map { (number, episodes) -> SeasonSummary(number, null, episodes, null, null) },
    lastEpisode = last?.toEpisode(),
    nextEpisode = next?.toEpisode(),
    ids = ExternalIds(tvdb = tvdbId ?: show.tvdbId, imdb = imdbId ?: show.imdbId, tvmaze = show.tvmazeId),
)

/** What the lists keep of TMDB's answer: regular seasons with episodes only, as ShowProgress counts them. */
fun ShowDetails.toSummary(): ShowSummary = ShowSummary(
    tmdbId = tmdbId,
    originalName = originalName,
    status = status,
    seasons = SeasonCounts.encode(seasons.filter { !it.isSpecials && it.episodeCount > 0 }.sortedBy { it.number }.map { it.number to it.episodeCount }),
    last = lastEpisode?.toMark(),
    next = nextEpisode?.toMark(),
    tvdbId = ids.tvdb,
    imdbId = ids.imdb,
)

fun MovieDetails.toSummary(): MovieSummary =
    MovieSummary(tmdbId, originalTitle, runtimeMinutes, releaseDate?.toEpochDay(), status, ids.imdb, originalLanguage)

private fun EpisodeMark.toEpisode() = EpisodeSummary(season, episode, name, airDay?.let(LocalDate::ofEpochDay))

private fun EpisodeSummary.toMark() = EpisodeMark(season, episode, name, airDate?.toEpochDay())

/** Reads the library's [ShowWatchAggregate] back into the watches ShowProgress takes. */
object WatchAggregates {
    /**
     * Watched episodes, then those in progress by when they last played (ties by episode, so the
     * one ShowProgress resumes doesn't depend on the order SQL gave). Malformed parts are skipped.
     */
    fun parse(aggregate: ShowWatchAggregate): List<EpisodeWatch> {
        val watched = aggregate.watched.orEmpty().split(',').mapNotNull { part ->
            val n = part.split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
            if (n.size == 2) EpisodeWatch(n[0], n[1], watched = true) else null
        }
        val inProgress = aggregate.inProgress.orEmpty().split(';').mapNotNull { part ->
            val n = part.split(':').map { it.toLongOrNull() ?: return@mapNotNull null }
            if (n.size == 4) EpisodeWatch(n[0].toInt(), n[1].toInt(), watched = false, positionMs = n[2], lastPlayedAt = n[3]) else null
        }.sortedWith(compareBy({ it.lastPlayedAt }, { it.season }, { it.episode }))
        return watched + inProgress
    }
}

/** "14 Nov" this year, "14 Nov 2027" another. */
fun shortDate(date: LocalDate, today: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", Locale.ENGLISH))

/** [shortDate] on a poster's one line: "14 Nov" this year, "Nov 2027" another (the day left out for the year's room). */
fun cardDate(date: LocalDate, today: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "MMM yyyy", Locale.ENGLISH))

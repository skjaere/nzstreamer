package io.skjaere.usenet.metadata

import java.time.Duration
import java.time.LocalDate

/**
 * How long TMDB's answer about a title stays good, by what it says. One policy for [TmdbClient]'s
 * cache, the lists' refresher and the title pages, so the client never serves a copy the refresher
 * would call stale, and a page asks again just when the client would.
 */
object Freshness {
    /** A show whose next episode is due: it may have aired since, or its date moved. */
    val AIRING: Duration = Duration.ofHours(1)
    val RETURNING: Duration = Duration.ofHours(24)
    val ENDED: Duration = Duration.ofDays(7)
    /** Not out yet, or no date announced. */
    val UPCOMING_MOVIE: Duration = Duration.ofHours(24)
    /** Out for less than [RECENT_DAYS] days. */
    val RECENT_MOVIE: Duration = Duration.ofDays(7)
    val OLD_MOVIE: Duration = Duration.ofDays(30)
    private const val RECENT_DAYS = 90L
    private const val RELEASED = "Released"

    fun ended(status: String?) = status == "Ended" || status == "Canceled"

    /** TmdbClient's hourly rule: the next episode airs today or should have aired. */
    fun nextEpisodeDue(next: LocalDate?, today: LocalDate) = next?.isAfter(today) == false

    fun show(status: String?, next: LocalDate?, today: LocalDate): Duration = when {
        nextEpisodeDue(next, today) -> AIRING
        ended(status) -> ENDED
        else -> RETURNING
    }

    /** Without a date a movie is upcoming, unless TMDB lists it as out. */
    fun movie(release: LocalDate?, status: String?, today: LocalDate): Duration = when {
        release == null -> if (status == RELEASED) RECENT_MOVIE else UPCOMING_MOVIE
        release.isAfter(today) -> UPCOMING_MOVIE
        release.isAfter(today.minusDays(RECENT_DAYS)) -> RECENT_MOVIE
        else -> OLD_MOVIE
    }

    /** TmdbClient keeps an answer about a show at most a day, a movie at most a week, whatever these say. */
    internal val SHOW_ANSWER: Duration = Duration.ofHours(24)
    internal val MOVIE_ANSWER: Duration = Duration.ofDays(7)
    internal val AIRING_SEASON: Duration = Duration.ofHours(1)
    internal val SEASON: Duration = Duration.ofHours(24)

    /** How long TmdbClient keeps its answer about a show before asking TMDB again: [show], capped. */
    fun showAnswer(status: String?, next: LocalDate?, today: LocalDate): Duration = minOf(show(status, next, today), SHOW_ANSWER)

    /** How long TmdbClient keeps its answer about a movie before asking TMDB again: [movie], capped. */
    fun movieAnswer(release: LocalDate?, status: String?, today: LocalDate): Duration = minOf(movie(release, status, today), MOVIE_ANSWER)

    /** A season airing (an episode from the last week, one still to come, or one without a date) is asked about hourly, others daily. */
    fun season(airDates: List<LocalDate?>, today: LocalDate): Duration =
        if (airDates.any { it?.isAfter(today.minusDays(7)) ?: true }) AIRING_SEASON else SEASON

    /** How long TmdbClient's cache serves [show] before asking TMDB again: a page that keeps it asks again after that. */
    fun answer(show: ShowDetails, today: LocalDate): Duration = showAnswer(show.status, show.nextEpisode?.airDate, today)

    fun answer(season: Season, today: LocalDate): Duration = season(season.episodes.map { it.airDate }, today)

    fun answer(movie: MovieDetails, today: LocalDate): Duration = movieAnswer(movie.releaseDate, movie.status, today)
}

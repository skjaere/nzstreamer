package io.skjaere.usenet.metadata

import java.time.Duration
import java.time.LocalDate

enum class MediaType { Movie, Tv }

/** A search-as-you-type hit. */
data class TitleSuggestion(
    val type: MediaType,
    val tmdbId: Int,
    val name: String,
    val originalName: String?,
    val year: Int?,
    val posterPath: String?,
    val overview: String?,
)

/** Ids other services use; indexers search by these. */
data class ExternalIds(val tvdb: Int? = null, val imdb: String? = null, val tvmaze: Int? = null)

data class EpisodeSummary(
    val season: Int,
    val episode: Int,
    val name: String?,
    val airDate: LocalDate?,
)

data class SeasonSummary(
    val number: Int,
    val name: String?,
    val episodeCount: Int,
    val airDate: LocalDate?,
    val posterPath: String?,
) {
    val isSpecials: Boolean get() = number == 0
}

data class ShowDetails(
    val tmdbId: Int,
    val name: String,
    val originalName: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    /** "Returning Series", "Ended", "Canceled", ... as TMDB reports it. */
    val status: String?,
    val inProduction: Boolean,
    val episodeRuntimeMinutes: Int?,
    val seasons: List<SeasonSummary>,
    val lastEpisode: EpisodeSummary?,
    val nextEpisode: EpisodeSummary?,
    val ids: ExternalIds,
    /** ISO 639-1 ([TmdbClient.originalLanguage]); null when TMDB has none. */
    val originalLanguage: String? = null,
)

data class Episode(
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String?,
    val airDate: LocalDate?,
    val runtimeMinutes: Int?,
    val stillPath: String?,
) {
    fun hasAired(today: LocalDate): Boolean = airDate != null && !airDate.isAfter(today)
}

data class Season(val showTmdbId: Int, val number: Int, val name: String?, val overview: String?, val episodes: List<Episode>)

data class MovieDetails(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val runtimeMinutes: Int?,
    val ids: ExternalIds,
    val releaseDate: LocalDate? = null,
    val status: String? = null,
    /** ISO 639-1 ([TmdbClient.originalLanguage]); null when TMDB has none. */
    val originalLanguage: String? = null,
)

/**
 * An answer and how old it is: [Duration.ZERO] from TMDB just now, the cached copy's age otherwise.
 * [offline]: TMDB couldn't be reached and this is the copy it gave last, however old.
 * [rateLimited]: TMDB said to slow down and this is the copy it gave last (only for a fetch that asked for it).
 */
data class Aged<out T>(val value: T, val age: Duration, val offline: Boolean = false, val rateLimited: Boolean = false) {
    fun <R> map(transform: (T) -> R): Aged<R> = Aged(transform(value), age, offline, rateLimited)
}

object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"

    fun poster(path: String?, width: Int = 342): String? = path?.let { "${BASE}w$width$it" }
    fun still(path: String?, width: Int = 300): String? = path?.let { "${BASE}w$width$it" }
    fun backdrop(path: String?, width: Int = 780): String? = path?.let { "${BASE}w$width$it" }
}

/** TMDB's required attribution; shown on the metadata settings screen and in About. */
const val TMDB_ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

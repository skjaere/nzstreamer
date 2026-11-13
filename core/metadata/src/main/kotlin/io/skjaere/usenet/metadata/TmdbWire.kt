package io.skjaere.usenet.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TMDB v3 response shapes, only the fields used. Unknown fields are ignored.

/** TMDB's paged answer (20 results a page), for [TmdbClient.fetch] callers too, with their own result type. */
@Serializable
data class PagedWire<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
internal data class MultiResultWire(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
internal data class ExternalIdsWire(
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
internal data class EpisodeRefWire(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
internal data class SeasonSummaryWire(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
internal data class TvWire(
    val id: Int,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("in_production") val inProduction: Boolean = false,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val seasons: List<SeasonSummaryWire> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: EpisodeRefWire? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: EpisodeRefWire? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsWire? = null,
)

@Serializable
internal data class EpisodeWire(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
)

@Serializable
internal data class SeasonWire(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val episodes: List<EpisodeWire> = emptyList(),
)

@Serializable
internal data class MovieWire(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val runtime: Int? = null,
    /** "Released", "Post Production", "Planned", ... */
    val status: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsWire? = null,
)

@Serializable
internal data class FindWire(
    @SerialName("movie_results") val movieResults: List<MultiResultWire> = emptyList(),
    @SerialName("tv_results") val tvResults: List<MultiResultWire> = emptyList(),
)

@Serializable
internal data class StatusWire(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    val success: Boolean? = null,
)

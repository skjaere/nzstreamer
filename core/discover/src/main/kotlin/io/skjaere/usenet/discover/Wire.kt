package io.skjaere.usenet.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TMDB v3 answers for Discover's lists, only the fields used. Unknown fields are ignored.

/** A title on a list page: a show's fields or a movie's; [mediaType] only on Trending ("tv", "movie" or "person"). */
@Serializable
internal data class ListItemWire(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val adult: Boolean = false,
)

@Serializable
internal data class GenresWire(val genres: List<GenreWire> = emptyList())

@Serializable
internal data class GenreWire(val id: Int, val name: String)

@Serializable
internal data class ProvidersWire(val results: List<ProviderWire> = emptyList())

/** A streaming service; its logo is never read. [displayPriorities] by region (ISO 3166-1), lowest first. */
@Serializable
internal data class ProviderWire(
    @SerialName("provider_id") val id: Int,
    @SerialName("provider_name") val name: String,
    @SerialName("display_priority") val displayPriority: Int = Int.MAX_VALUE,
    @SerialName("display_priorities") val displayPriorities: Map<String, Int> = emptyMap(),
)

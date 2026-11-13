package io.skjaere.usenet.newznab

import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.MediaInfo
import io.skjaere.usenet.releases.ReleaseKey
import java.time.Instant
import java.time.LocalDate

/**
 * What an indexer's results are: NZBs from a Newznab one, torrents from a Torznab one (Prowlarr's or Jackett's). [timeoutMs]:
 * how long its caps and each search may take (L170); a torrent indexer asks trackers' sites, which are slow.
 */
enum class IndexerKind(val timeoutMs: Long) { Newznab(15_000), Torznab(25_000) }

/** An indexer the user added. [apiKey] is a secret: never log it or persist it unencrypted. */
data class IndexerConfig(
    val id: String,
    val name: String,
    /** API endpoint, e.g. `https://indexer.example.org/api` (`/api` is appended when missing). */
    val baseUrl: String,
    val apiKey: String,
    val enabled: Boolean = true,
    /** How its results are read: an item that isn't of this kind is left out of its pages ([LeftOut]). */
    val kind: IndexerKind = IndexerKind.Newznab,
    /**
     * A torrent indexer's "Use with your debrid service" (L194): off, it is still searched, and nothing of its results
     * goes to any service. Always on for a Newznab indexer.
     */
    val useWithService: Boolean = true,
) {
    override fun toString() = "IndexerConfig(id=$id, name=$name, baseUrl=$baseUrl, enabled=$enabled, kind=$kind, useWithService=$useWithService)"
}

data class TvIds(val tvdb: Int? = null, val tmdb: Int? = null, val imdb: String? = null, val tvmaze: Int? = null)

sealed interface SearchQuery {
    val categories: List<Int>

    data class Text(val query: String, override val categories: List<Int> = emptyList()) : SearchQuery

    data class Tv(
        val query: String? = null,
        val ids: TvIds = TvIds(),
        val season: Int? = null,
        val episode: Int? = null,
        /** A daily show's episode, asked the Newznab way: `season=YYYY&ep=MM/DD`, in place of [season] and [episode]. */
        val airDate: LocalDate? = null,
        override val categories: List<Int> = emptyList(),
    ) : SearchQuery

    data class Movie(
        val query: String? = null,
        val imdb: String? = null,
        val tmdb: Int? = null,
        override val categories: List<Int> = emptyList(),
    ) : SearchQuery
}

/** [this] query asking for [categories] in place of its own. */
fun SearchQuery.withCategories(categories: List<Int>): SearchQuery = when (this) {
    is SearchQuery.Text -> copy(categories = categories)
    is SearchQuery.Tv -> copy(categories = categories)
    is SearchQuery.Movie -> copy(categories = categories)
}

/**
 * The categories a torrent indexer's [query] asks for (L167): of the Movies (2000) and TV (5000) parents its [caps] list,
 * TV's for a TV search, Movies' for a film's, both for words alone. Jackett runs a search without them over every category
 * a site has, software and music among them; a parent takes its children in, and a tracker's own categories (100000 on)
 * are each mapped to one too. None listed: none, never a built-in catalogue (the caps rule).
 */
fun torznabCategories(caps: IndexerCaps, query: SearchQuery): List<Int> {
    val wanted = when (query) {
        is SearchQuery.Tv -> listOf(TV_CATEGORY)
        is SearchQuery.Movie -> listOf(MOVIES_CATEGORY)
        is SearchQuery.Text -> listOf(MOVIES_CATEGORY, TV_CATEGORY)
    }
    val listed = caps.categories.map { it.id }.toSet()
    return wanted.filter { it in listed }
}

/** Newznab's standard parents, as Prowlarr and Jackett number them. */
const val MOVIES_CATEGORY = 2000
const val TV_CATEGORY = 5000

enum class SearchType(val function: String, val capsElement: String) {
    Search("search", "search"), Tv("tvsearch", "tv-search"), Movie("movie", "movie-search"),
}

data class Category(val id: Int, val name: String, val subcategories: List<Category> = emptyList())

data class IndexerCaps(
    val serverTitle: String? = null,
    val serverVersion: String? = null,
    val maxLimit: Int? = null,
    val defaultLimit: Int? = null,
    /** Supported parameters per available search type, lower-case (`q`, `tvdbid`, `season`, `ep`, ...). */
    val searching: Map<SearchType, Set<String>> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val limits: ApiLimits? = null,
) {
    fun supports(type: SearchType, param: String? = null): Boolean =
        searching[type]?.let { param == null || param.lowercase() in it } == true
}

/** Usage the indexer reported; any field may be missing. */
data class ApiLimits(val apiCurrent: Int? = null, val apiMax: Int? = null, val grabCurrent: Int? = null, val grabMax: Int? = null)

data class NewznabItem(
    val indexerId: String,
    /** Stable id for `t=get&id=`. */
    val guid: String,
    val title: String,
    val sizeBytes: Long,
    val publishedAt: Instant?,
    /** When the release was posted to Usenet; falls back to [publishedAt]. */
    val postedAt: Instant?,
    val categories: List<Int>,
    val grabs: Int?,
    val passworded: Boolean,
    val files: Int?,
    val tvdbId: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val tvmazeId: Int?,
    val season: Int?,
    val episode: Int?,
    val coverUrl: String?,
    val mediaInfo: MediaInfo?,
    /**
     * The item's download link with the API key replaced by [API_KEY_PLACEHOLDER], so it
     * can be stored. Null when the indexer gave no link; then `t=get&id=guid` is used.
     */
    val downloadTemplate: String?,
    val attributes: Map<String, List<String>>,
    /**
     * A torrent's v1 info hash, 40 lower-case hex digits, by which it is one release whichever indexer lists it ([key]);
     * null for an NZB. A Torznab indexer's item always has one: one without is left out ([LeftOut.NoHash]).
     */
    val infoHash: String? = null,
    /** The enclosure's media type as the feed gave it (`application/x-nzb`, `application/x-bittorrent`); null when none. */
    val enclosureType: String? = null,
    /** A torrent's cache check, once it settled ([IndexerOutcome.Checked], L199); null for an NZB and a torrent not checked yet. */
    val availability: Availability? = null,
) {
    /** What the release is known by: its hash for a torrent, else this indexer's guid ([ReleaseKey]). */
    val key: String get() = ReleaseKey.of(indexerId, guid, infoHash)

    companion object {
        const val API_KEY_PLACEHOLDER = "{apikey}"
    }
}

data class SearchPage(
    val items: List<NewznabItem>,
    val offset: Int,
    val total: Int?,
    val limits: ApiLimits?,
    /** How many of the feed's items were left out of [items], by why: what this indexer's kind can't use. */
    val leftOut: Map<LeftOut, Int> = emptyMap(),
    /**
     * The feed's items as read, in its order: null for one kept in [items], else why it was left out. An indexer may
     * answer more than it was asked; the torrent indexer's Test counts its first ones by this (L238).
     */
    val feed: List<LeftOut?> = emptyList(),
)

/** Why a feed's item was left out of its [SearchPage]: what the indexer's [IndexerKind] can't use (L161, L168). */
enum class LeftOut {
    /** On a Newznab indexer, an item whose enclosure is there and isn't an NZB: a torrent feed added under Indexers. */
    NotNzb,

    /** On a Torznab indexer, an NZB: a Usenet feed added under Torrent indexers. */
    Nzb,

    /**
     * On a Torznab indexer, a torrent with no v1 info hash: neither an `infohash` nor a magnet holding one, a v2-only
     * magnet among them. Its `.torrent` would tell, but each fetch is a grab and a tracker request, which D1 doesn't make.
     */
    NoHash,
}

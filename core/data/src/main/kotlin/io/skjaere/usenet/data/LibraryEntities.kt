package io.skjaere.usenet.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A TV show in the user's collection. [id] is `tmdb:<tmdbId>`. */
@Entity(tableName = "collected_show")
data class CollectedShow(
    @PrimaryKey val id: String,
    val tmdbId: Int,
    val tvdbId: Int? = null,
    val imdbId: String? = null,
    val tvmazeId: Int? = null,
    val name: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val addedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        fun idFor(tmdbId: Int) = "tmdb:$tmdbId"
    }
}

/**
 * A movie in the user's collection, sync-ready like [CollectedShow]: [id] is `tmdb:<tmdbId>`, and its
 * progress is under `movie:` + [id] ([WatchKey.movie]), so history from before it was added shows.
 */
@Entity(tableName = "collected_movie")
data class CollectedMovie(
    @PrimaryKey val id: String,
    val tmdbId: Int,
    val imdbId: String? = null,
    /** As TMDB named it when it was added. */
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val addedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        fun idFor(tmdbId: Int) = "tmdb:$tmdbId"
    }
}

/** TMDB's last or next episode of a show; [airDay] is the air date's epoch day (TMDB's dates have no zone: never stored as instants). */
data class EpisodeMark(val season: Int, val episode: Int, val name: String? = null, val airDay: Long? = null)

/**
 * What TMDB last said of a show, for the lists. A cache (see Sync.kt): derived, never synced, no
 * tombstone; kept for titles no longer collected (Undo, adding again) until pruned. Nothing that
 * depends on watching or on today's date is stored here. A later schema change drops and recreates
 * this table, which the refresher fills again, or adds a column that TMDB's next answers fill in
 * (as version 7 did to [MovieSummary]).
 */
@Entity(tableName = "show_summary")
data class ShowSummary(
    @PrimaryKey val tmdbId: Int,
    val originalName: String? = null,
    /** "Returning Series", "Ended", "Canceled", ... */
    val status: String? = null,
    /** Regular seasons with episodes, in order: "1:10,2:8" ([SeasonCounts] encodes and parses it). */
    val seasons: String = "",
    @Embedded(prefix = "last_") val last: EpisodeMark? = null,
    @Embedded(prefix = "next_") val next: EpisodeMark? = null,
    val tvdbId: Int? = null,
    val imdbId: String? = null,
)

/** What TMDB last said of a movie: a cache like [ShowSummary], and dropped and recreated the same way. */
@Entity(tableName = "movie_summary")
data class MovieSummary(
    @PrimaryKey val tmdbId: Int,
    val originalTitle: String? = null,
    val runtimeMinutes: Int? = null,
    /** Epoch day; null when TMDB has none. */
    val releaseDay: Long? = null,
    /** "Released", "Post Production", ... */
    val status: String? = null,
    val imdbId: String? = null,
    /** ISO 639-1: the movie's releases without a language tag speak it. */
    val originalLanguage: String? = null,
)

/**
 * When the refresher last asked TMDB about a title and how that went. A table of its own, so that
 * writing it wakes no list (Room invalidates per table). [type] is [SHOW] or [MOVIE].
 */
@Entity(tableName = "summary_refresh", primaryKeys = ["type", "tmdbId"])
data class SummaryRefresh(
    val type: String,
    val tmdbId: Int,
    /** When TMDB produced the stored facts: the answer's time less its age. 0: never answered. */
    val fetchedAt: Long = 0,
    /** Due again from here; 0: due now. */
    val staleAt: Long = 0,
    /** After a failure or an answer from TMDB's cache while offline: not before this. */
    val retryAt: Long = 0,
    val failures: Int = 0,
    /** "NotFound", "Server", "BadResponse"; null after an answer. */
    val error: String? = null,
) {
    companion object {
        const val SHOW = "show"
        const val MOVIE = "movie"
    }
}

/** [ShowSummary.seasons]: each regular season and its episode count, "1:10,2:8". */
object SeasonCounts {
    fun encode(counts: List<Pair<Int, Int>>): String = counts.joinToString(",") { (season, episodes) -> "$season:$episodes" }

    /** Malformed parts are skipped. */
    fun parse(text: String): List<Pair<Int, Int>> = text.split(',').mapNotNull { part ->
        val numbers = part.trim().split(':').map { it.toIntOrNull() }
        val (season, episodes) = numbers.takeIf { it.size == 2 } ?: return@mapNotNull null
        if (season == null || episodes == null || episodes < 0) null else season to episodes
    }
}

enum class WatchKind { Episode, Movie, Release }

/**
 * Progress for one playable thing: an episode of a TMDB show, a TMDB movie, or a release
 * played without metadata. [key] is built by [WatchKey]. The release fields remember what
 * played, so resume and re-watch need no new search or grab. The (watched, positionMs) index finds
 * what is in progress: it made the lists' in-progress query 40 % faster at 26k episodes (version 5).
 */
@Entity(
    tableName = "watch_state",
    indices = [Index("showTmdbId"), Index("lastPlayedAt"), Index("watched", "positionMs")],
)
data class WatchState(
    @PrimaryKey val key: String,
    val kind: WatchKind,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movieTmdbId: Int? = null,
    val title: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val lastPlayedAt: Long = 0,
    @Embedded(prefix = "release_") val release: RememberedRelease? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * [downloadTemplate] has the API key replaced by a placeholder; never store the real link. The file
 * that played is [fileName], of [fileSize] bytes; its position in the release, [fileIndex], decides
 * only for a release remembered before names were (database version 3): positions shift (#179).
 */
data class RememberedRelease(
    val indexerId: String,
    val guid: String,
    val title: String,
    val downloadTemplate: String? = null,
    val fileIndex: Int,
    /** sha256 of the NZB, to find it in the local NZB store without fetching again. */
    val nzbId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /**
     * Where it played from, as the play's candidate names its source; null for Usenet, as every release remembered
     * before sources were (database version 11) played from there. A torrent's ("torrent") has its content id,
     * `btih:<hex>`, for its guid and its NZB's id, and nothing to download it by (L181).
     */
    val source: String? = null,
)

/**
 * A release whose articles were found missing ([ReleaseChecks]), by the indexer and guid the search gave it
 * ([id] is a search result's key, [io.skjaere.usenet.releases.ReleaseKey]), so a play knows it before grabbing
 * its NZB. [missingOn]: the providers that answered that they lack them, each with when; [incompleteAt]: when
 * its NZB was found to lack files, which no provider can supply; [passwordAt]: when its archive was found
 * encrypted with no password in its NZB or from its indexer (version 8); [discAt]: when its files were found a
 * copy of a disc, which plays as no single video (version 13). A device's own, derived from its
 * providers: never synced, no tombstone. [updatedAt] is its last check, by which it expires and the least
 * recently checked go first.
 */
@Entity(tableName = "tried_release", indices = [Index("updatedAt")])
data class TriedRelease(
    @PrimaryKey val id: String,
    val indexerId: String,
    val guid: String,
    val missingOn: List<ProviderCheck> = emptyList(),
    val incompleteAt: Long? = null,
    val updatedAt: Long,
    val passwordAt: Long? = null,
    val discAt: Long? = null,
)

/** A [provider] ([ReleaseChecks.provider]) found lacking a release's articles at [checkedAt]. */
@Serializable
data class ProviderCheck(val provider: String, val checkedAt: Long)

@Entity(tableName = "recent_search")
data class RecentSearch(
    /** Normalised query, so "Harbor Lights" and "harbor  lights" are one entry; a title opened, its id's ([titleKey]). */
    @PrimaryKey val key: String,
    /** The words searched; a title opened, its name. */
    val query: String,
    val searchedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** The TMDB title the search was picked from, or the title opened; null for one typed and searched as text. */
    @Embedded(prefix = "pick_") val pick: SearchPick? = null,
    @ColumnInfo(defaultValue = "Search") val kind: RecentKind = RecentKind.Search,
) {
    companion object {
        /** A title opened's entry: one per title, whatever words found it. */
        fun titleKey(title: SearchPick): String = "title:${title.type.name.lowercase()}:${title.tmdbId}"
    }
}

/** What a recent search was, and so what it does again. */
enum class RecentKind {
    /** The indexers searched for its words, by the title picked for them when there was one: it searches them again. */
    Search,

    /** A TMDB title opened from Search, its show's or movie's page: it opens the page again. */
    Title,
}

enum class TitleType { Tv, Movie }

/**
 * A TMDB title picked from the search suggestions, so a recent search replays by the title's ids
 * and records progress against its episodes or the film, not against the release that played.
 */
data class SearchPick(
    val type: TitleType,
    val tmdbId: Int,
    /** The title as TMDB names it. */
    val name: String,
    /** The season and episode typed with a show ("Harbor Lights S02E05"); null without. */
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * The audio and subtitles picked in the player for an episode of a TV show, which its later
 * episodes play ahead of the preferences and the subtitle rule. Either part may be unset: it then
 * follows the preferences. [showTmdbId] is the stable key; "Reset to my defaults" leaves a
 * [deleted] tombstone.
 */
@Entity(tableName = "show_track_choice")
data class ShowTrackChoice(
    @PrimaryKey val showTmdbId: Int,
    /** Media3's code for the audio's language ("und" for a track without one); null when no audio was picked. */
    val audioLanguage: String? = null,
    val audioCommentary: Boolean = false,
    /** The audio description (Matroska's FlagVisualImpaired) picked: later episodes play theirs, the others their main audio. */
    @ColumnInfo(defaultValue = "0") val audioDescription: Boolean = false,
    /** Null when no subtitles were picked. */
    val subtitles: SubtitleMode? = null,
    /** For [SubtitleMode.Language]: Media3's code ("und" for a track without one). */
    val subtitleLanguage: String? = null,
    val subtitleSdh: Boolean = false,
    /** A forced track picked as such, rather than [SubtitleMode.ForcedOnly] following the audio. */
    val subtitleForced: Boolean = false,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** Off: nothing shows, forced subtitles included. ForcedOnly: no full subtitles. Language: [ShowTrackChoice.subtitleLanguage]. */
enum class SubtitleMode { Off, ForcedOnly, Language }

/**
 * A title taken out of Home's Up next, until it plays again: the user's wish, so a synced record (see Sync.kt). [id] is
 * the row's key as Home has it: a show's ("show:<tmdbId>"), whose every row goes, else what played ([WatchKey.movie],
 * [WatchKey.release]). A play since [removedAt] brings it back: of any episode of [showTmdbId]'s show, specials
 * included, else of [watchKey]. Marks write no play, so marking it watched leaves it out. Undo leaves a [deleted]
 * tombstone.
 */
@Entity(tableName = "up_next_removal")
data class UpNextRemoval(
    @PrimaryKey val id: String,
    val showTmdbId: Int? = null,
    val watchKey: String? = null,
    val removedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One show's episode watches, aggregated in SQL: what ShowProgress reads of them. */
data class ShowWatchAggregate(
    val showTmdbId: Int,
    /** Watched regular episodes: "2:5,2:6". */
    val watched: String?,
    /** Unwatched regular episodes played at least a minute: "season:episode:positionMs:lastPlayedAt;…". */
    val inProgress: String?,
    /** Over every episode state of the show, specials too. */
    val lastPlayedAt: Long,
)

data class MovieWatch(val movieTmdbId: Int, val positionMs: Long, val durationMs: Long, val watched: Boolean, val lastPlayedAt: Long)

/** A collected title for the refresher: its bookkeeping (null when never asked) and when it was last watched. */
data class RefreshCandidate(
    val tmdbId: Int,
    val addedAt: Long,
    val fetchedAt: Long?,
    val staleAt: Long?,
    val retryAt: Long?,
    val failures: Int?,
    val lastWatchedAt: Long?,
)

/** A title TMDB has told nothing of yet, whose last refresh failed with [error]. */
data class RefreshFailure(val type: String, val tmdbId: Int, val error: String)

data class CollectionCounts(val shows: Int, val movies: Int)

/** The titles a removal tombstoned, at [at]: what its Undo brings back. */
data class Removal(val shows: List<Int>, val movies: List<Int>, val at: Long)

/** What a bulk mark replaced: the [before] rows as they were and the keys it [created], all written at [at]. */
data class WatchSnapshot(val before: List<WatchState>, val created: List<String>, val at: Long)

/** A show to mark: its aired regular [episodes] (season to episode), [name] for rows it creates. */
data class ShowMark(val tmdbId: Int, val name: String, val episodes: List<Pair<Int, Int>>)

data class MovieMark(val tmdbId: Int, val title: String)

object WatchKey {
    fun episode(showTmdbId: Int, season: Int, episode: Int) = "episode:tmdb:$showTmdbId:s$season:e$episode"
    fun movie(tmdbId: Int) = "movie:tmdb:$tmdbId"
    fun release(nzbId: String, fileIndex: Int) = "release:$nzbId:$fileIndex"
}

/** The [WatchState.title] to store, which history and the player show. */
object WatchTitle {
    /** "Show S01E02". The name is an argument, not part of the format: TMDB names contain '%' ("100% Hotter"). */
    fun episode(showName: String, season: Int, episode: Int) = "%s S%02dE%02d".format(showName, season, episode)
}

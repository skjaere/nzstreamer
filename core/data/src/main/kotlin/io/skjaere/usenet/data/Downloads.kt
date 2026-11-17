package io.skjaere.usenet.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * What a download is of: an episode of a TMDB show, a TMDB film, or a file of an NZB the user opened on Import ([Release],
 * L423), which has no TMDB title. Stored by its name, so a kind added later needs no migration.
 */
enum class DownloadKind { Episode, Movie, Release }

/**
 * Where a download is. [Queued] waits for its turn; [Resolving] finds its release (grab, check, prepare, probe); [Running]
 * writes it; [Paused] and [Stopped] (by Android: Task Manager's Stop or a force stop) hold the queue until Resume; [Waiting]
 * waits for a network, for charging, for the provider or for storage, as its [Download.reason] says; [Failed] and [Done]
 * have ended; [Deleting] is removed, hidden at once, its files going once its Undo has gone (L379).
 */
enum class DownloadState { Queued, Resolving, Running, Paused, Waiting, Stopped, Failed, Done, Deleting }

/**
 * A release a download may be made from, as the Download sheet ranked it (L338): what the host grabs when the item's turn
 * comes. Its link is kept as the app keeps links, with the API key replaced by its placeholder ([downloadTemplate]; null:
 * `t=get` by [guid]), its [size] as the indexer says it, in bytes, and whether the indexer flags it [passworded].
 */
@Serializable
data class DownloadCandidate(
    val indexerId: String,
    val guid: String,
    val title: String,
    val downloadTemplate: String? = null,
    val size: Long = 0,
    val passworded: Boolean = false,
)

/**
 * Bytes [first] to [last] (inclusive, as a stream's gaps are) of a download's file that no provider had: written as zeros,
 * and skipped as it plays (L342).
 */
@Serializable
data class DownloadGap(val first: Long, val last: Long)

/**
 * An episode, a film or an imported file saved on this device, or on its way (L351). A device's own, as [TriedRelease] is:
 * never synced, no tombstone; a removed download's row is deleted. [id] is random, of RFC 3986's unreserved characters
 * ([newId]): its `download://` URI's and its folders' name, never the release's. The names are kept with it, so the
 * Downloads page works offline (L368): [title] (the show's or the film's), [subtitle], and for an episode its [season],
 * [episode], [episodeName] and the season's episode count ([seasonEpisodes], from the sheet's request, for the next episode
 * offline), for a film its [year] and [runtimeMinutes]. It plays as [watchKey] ([WatchKey.episode] or [WatchKey.movie]). A
 * file opened on Import ([DownloadKind.Release], L423) keeps the file's name as its [title] and the NZB's as its
 * [subtitle], and plays as [WatchKey.release] (the NZB's id and the file's place in it), so its progress is saved there.
 *
 * The queue is the rows by [position] ([DownloadDao.queue]); [state], with [reason] in the row's words where it says one.
 * [candidates]: up to 10 releases in download order (L338); [tried]: the ones tried so far, by their search result's key
 * ([io.skjaere.usenet.releases.ReleaseKey]), in the order tried, which caps a download's grabs (L340). Resolve sets
 * [release], the file's [fileName] and exact [size] (until then the indexer's, [indexerSize]), whether the release is
 * [oneVideo] (L342), and what the probe read of the file as the player's launch keeps it ([video] and [tracks], JSON).
 *
 * The progress record (L345): [safeOffset], every byte before which is written and forced to storage, and the [gaps] known
 * so far. [relativePath]: the finished file's path under the app's folder on the primary volume
 * ("downloads/<id>/v.mkv", its `.part` until done), set once the file is made and its whole [size] allocated, and null
 * while there is none; so a row with one holds its size on the device, and one without still needs it (DownloadSpace).
 *
 * Version 15's three (L405), JSON the app reads and writes, as [video] and [tracks] are: a season row's search facts
 * ([search], L392: the show's names and ids, the episode's air date and runtime, whether its turn has searched), the file
 * chosen in a pack by hand ([pick], L403: never in [release]), and an indexer's limit the row waits out ([limitWait],
 * L434: which indexer, which limit, since and until when, as the row's [reason] names none). Null where there is none.
 */
@Entity(tableName = "download", indices = [Index("watchKey"), Index("showTmdbId"), Index("position")])
data class Download(
    @PrimaryKey val id: String,
    val watchKey: String,
    val kind: DownloadKind,
    val title: String,
    val subtitle: String? = null,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val seasonEpisodes: Int? = null,
    val episodeName: String? = null,
    val movieTmdbId: Int? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val posterPath: String? = null,
    val stillPath: String? = null,
    val state: DownloadState = DownloadState.Queued,
    val reason: String? = null,
    val position: Long,
    val candidates: List<DownloadCandidate> = emptyList(),
    val tried: List<String> = emptyList(),
    val oneVideo: Boolean = false,
    @Embedded(prefix = "release_") val release: RememberedRelease? = null,
    val fileName: String? = null,
    val size: Long? = null,
    val indexerSize: Long = 0,
    val safeOffset: Long = 0,
    val gaps: List<DownloadGap> = emptyList(),
    val relativePath: String? = null,
    val video: String? = null,
    val tracks: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val updatedAt: Long,
    // No default value in the schema (no @ColumnInfo(defaultValue)), as MIGRATION_14_15's ADD COLUMN makes them: Room
    // compares the two as it opens the database.
    val search: String? = null,
    val pick: String? = null,
    val limitWait: String? = null,
) {
    companion object {
        /** A new download's id: a random UUID, whose letters, digits and hyphens a URI and a folder's name hold as they are. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** This device's downloads ([Download]). */
@Dao
interface DownloadDao {
    /** What is still to finish, failed ones included: neither [DownloadState.Done] nor being deleted, in the queue's order. */
    @Query("SELECT * FROM download WHERE state NOT IN ('Done', 'Deleting') ORDER BY position, createdAt")
    fun queue(): Flow<List<Download>>

    /** Every row, the finished and the deleted among them. */
    @Query("SELECT * FROM download ORDER BY position, createdAt")
    fun all(): Flow<List<Download>>

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun get(id: String): Download?

    /** The finished downloads, the latest finished last: kept by watch key, the latest wins. */
    @Query("SELECT * FROM download WHERE state = 'Done' ORDER BY completedAt, createdAt")
    fun done(): Flow<List<Download>>

    /** The finished download that plays as [watchKey], the latest finished; null when none has. */
    @Query("SELECT * FROM download WHERE state = 'Done' AND watchKey = :watchKey ORDER BY completedAt DESC, createdAt DESC LIMIT 1")
    suspend fun doneFor(watchKey: String): Download?

    /** A show's downloads, whatever their state but those being deleted, by season and episode. */
    @Query("SELECT * FROM download WHERE showTmdbId = :showTmdbId AND state != 'Deleting' ORDER BY season, episode, createdAt")
    fun forShow(showTmdbId: Int): Flow<List<Download>>

    /** A film's download, whatever its state but being deleted, the latest added; null when none. */
    @Query("SELECT * FROM download WHERE movieTmdbId = :movieTmdbId AND state != 'Deleting' ORDER BY createdAt DESC LIMIT 1")
    fun forMovie(movieTmdbId: Int): Flow<Download?>

    @Insert
    suspend fun insert(download: Download)

    /**
     * Writes [putBack] whole and inserts [downloads], in one transaction (a season queued at once, L392, L396): all of
     * them, or none where one fails.
     */
    @Transaction
    suspend fun insertAll(downloads: List<Download>, putBack: List<Download> = emptyList()) {
        putBack.forEach { update(it) }
        downloads.forEach { insert(it) }
    }

    @Update
    suspend fun update(download: Download)

    /** The progress record, its offset and its gaps in one statement, so no reader sees one without the other (L345). */
    @Query("UPDATE download SET safeOffset = :safeOffset, gaps = :gaps, updatedAt = :updatedAt WHERE id = :id")
    suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long)

    @Query("UPDATE download SET state = :state, reason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long)

    @Query("UPDATE download SET position = :position WHERE id = :id")
    suspend fun move(id: String, position: Long)

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * A finished download whose title is marked watched (L412): download [id], of [watchKey], finished at [completedAt], and
 * when its watch state was last written watched with no place in it ([watchedAt]): its mark, or a play to its end since.
 */
data class WatchedDownload(val id: String, val watchKey: String, val completedAt: Long, val watchedAt: Long)

/**
 * Delete once watched's clock (L412): the finished downloads joined with their titles' watch states, a query of the two
 * tables Room has, so no column is added to the synced watch states and [DownloadDao]'s fakes stay as they are. Its own
 * DAO for the same reason.
 */
@Dao
interface DownloadWatchDao {
    /**
     * Each finished download whose title's watch state is watched with no place to resume and not a tombstone, written
     * after the download finished. Watched with no place is what a mark and a play to its end leave; a replay's save in
     * its first minute keeps it watched with a place in it and a later time, which is no mark. So one marked before it
     * finished (a title downloaded again after it was watched) isn't watched as far as its download goes, however it is
     * opened since, until it is marked or played to its end again. Room runs it again on a write to either table, so a
     * mark, a play's save or a removal is seen.
     */
    @Query(
        """
        SELECT d.id AS id, d.watchKey AS watchKey, d.completedAt AS completedAt, w.updatedAt AS watchedAt
        FROM download d JOIN watch_state w ON w.`key` = d.watchKey
        WHERE d.state = 'Done' AND w.watched = 1 AND w.positionMs = 0 AND w.deleted = 0 AND w.updatedAt > d.completedAt
        ORDER BY d.completedAt, d.createdAt
        """,
    )
    fun watched(): Flow<List<WatchedDownload>>
}

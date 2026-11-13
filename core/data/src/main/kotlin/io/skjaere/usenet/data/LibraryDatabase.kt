package io.skjaere.usenet.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collected_show WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun shows(): Flow<List<CollectedShow>>

    @Query("SELECT * FROM collected_show WHERE id = :id")
    suspend fun get(id: String): CollectedShow?

    @Upsert
    suspend fun upsert(show: CollectedShow)

    @Query("SELECT EXISTS(SELECT 1 FROM collected_show WHERE id = :id AND deleted = 0)")
    fun isCollected(id: String): Flow<Boolean>

    @Query("SELECT tmdbId FROM collected_show WHERE deleted = 0")
    fun ids(): Flow<List<Int>>

    /** Both kinds in one query, so Home's summary doesn't build the rows. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM collected_show WHERE deleted = 0) AS shows, " +
            "(SELECT COUNT(*) FROM collected_movie WHERE deleted = 0) AS movies",
    )
    fun counts(): Flow<CollectionCounts>

    /** Which of [ids] are collected (not tombstoned). At most 999 ids before Android 11: callers chunk. */
    @Query("SELECT id FROM collected_show WHERE id IN (:ids) AND deleted = 0")
    suspend fun live(ids: List<String>): List<String>

    @Query("UPDATE collected_show SET deleted = 1, updatedAt = :now WHERE id IN (:ids) AND deleted = 0")
    suspend fun tombstone(ids: List<String>, now: Long): Int

    /** Brings back the ones still tombstoned by the removal at [removedAt]: not one added and removed again since. */
    @Query("UPDATE collected_show SET deleted = 0, updatedAt = :now WHERE id IN (:ids) AND deleted = 1 AND updatedAt = :removedAt")
    suspend fun untombstone(ids: List<String>, removedAt: Long, now: Long): Int

    @Query(
        "SELECT c.tmdbId AS tmdbId, c.addedAt AS addedAt, r.fetchedAt AS fetchedAt, r.staleAt AS staleAt, r.retryAt AS retryAt, r.failures AS failures, " +
            "(SELECT MAX(w.lastPlayedAt) FROM watch_state w WHERE w.showTmdbId = c.tmdbId AND w.kind = 'Episode' AND w.deleted = 0) AS lastWatchedAt " +
            "FROM collected_show c LEFT JOIN summary_refresh r ON r.type = 'show' AND r.tmdbId = c.tmdbId WHERE c.deleted = 0",
    )
    fun refreshCandidates(): Flow<List<RefreshCandidate>>
}

/** The movie collection, as [CollectionDao] keeps the shows. */
@Dao
interface MovieCollectionDao {
    @Query("SELECT * FROM collected_movie WHERE deleted = 0 ORDER BY title COLLATE NOCASE")
    fun movies(): Flow<List<CollectedMovie>>

    @Query("SELECT * FROM collected_movie WHERE id = :id")
    suspend fun get(id: String): CollectedMovie?

    /** [get]'s row as it changes: a removed one (a tombstone) included. */
    @Query("SELECT * FROM collected_movie WHERE id = :id")
    fun movie(id: String): Flow<CollectedMovie?>

    @Upsert
    suspend fun upsert(movie: CollectedMovie)

    @Query("SELECT EXISTS(SELECT 1 FROM collected_movie WHERE id = :id AND deleted = 0)")
    fun isCollected(id: String): Flow<Boolean>

    @Query("SELECT tmdbId FROM collected_movie WHERE deleted = 0")
    fun ids(): Flow<List<Int>>

    @Query("SELECT id FROM collected_movie WHERE id IN (:ids) AND deleted = 0")
    suspend fun live(ids: List<String>): List<String>

    @Query("UPDATE collected_movie SET deleted = 1, updatedAt = :now WHERE id IN (:ids) AND deleted = 0")
    suspend fun tombstone(ids: List<String>, now: Long): Int

    @Query("UPDATE collected_movie SET deleted = 0, updatedAt = :now WHERE id IN (:ids) AND deleted = 1 AND updatedAt = :removedAt")
    suspend fun untombstone(ids: List<String>, removedAt: Long, now: Long): Int

    @Query(
        "SELECT m.tmdbId AS tmdbId, m.addedAt AS addedAt, r.fetchedAt AS fetchedAt, r.staleAt AS staleAt, r.retryAt AS retryAt, r.failures AS failures, " +
            "(SELECT w.lastPlayedAt FROM watch_state w WHERE w.`key` = 'movie:tmdb:' || m.tmdbId AND w.deleted = 0) AS lastWatchedAt " +
            "FROM collected_movie m LEFT JOIN summary_refresh r ON r.type = 'movie' AND r.tmdbId = m.tmdbId WHERE m.deleted = 0",
    )
    fun refreshCandidates(): Flow<List<RefreshCandidate>>
}

/** The lists' cache of TMDB facts and the refresher's bookkeeping. */
@Dao
interface SummaryDao {
    @Query("SELECT * FROM show_summary")
    fun shows(): Flow<List<ShowSummary>>

    @Query("SELECT * FROM movie_summary")
    fun movies(): Flow<List<MovieSummary>>

    @Query("SELECT * FROM show_summary WHERE tmdbId = :id")
    fun show(id: Int): Flow<ShowSummary?>

    @Query("SELECT * FROM movie_summary WHERE tmdbId = :id")
    fun movie(id: Int): Flow<MovieSummary?>

    @Query("SELECT * FROM show_summary WHERE tmdbId IN (:ids)")
    suspend fun showsIn(ids: List<Int>): List<ShowSummary>

    @Query("SELECT * FROM movie_summary WHERE tmdbId IN (:ids)")
    suspend fun moviesIn(ids: List<Int>): List<MovieSummary>

    @Upsert
    suspend fun upsertShows(rows: List<ShowSummary>)

    @Upsert
    suspend fun upsertMovies(rows: List<MovieSummary>)

    @Upsert
    suspend fun upsertRefresh(rows: List<SummaryRefresh>)

    /** Titles TMDB has told nothing of yet whose last refresh failed; it re-runs on every refresh write, so callers drop repeats. */
    @Query(
        "SELECT r.type AS type, r.tmdbId AS tmdbId, r.error AS error FROM summary_refresh r WHERE r.error IS NOT NULL AND (" +
            "(r.type = 'show' AND NOT EXISTS (SELECT 1 FROM show_summary s WHERE s.tmdbId = r.tmdbId)) OR " +
            "(r.type = 'movie' AND NOT EXISTS (SELECT 1 FROM movie_summary m WHERE m.tmdbId = r.tmdbId))) ORDER BY r.type, r.tmdbId",
    )
    fun factlessFailures(): Flow<List<RefreshFailure>>

    /** Everything due now (the TMDB language changed). */
    @Query("UPDATE summary_refresh SET staleAt = 0, retryAt = 0")
    suspend fun invalidateAll()

    // Pruning: the facts and bookkeeping of titles no longer collected that TMDB was last asked about before a time.

    @Query(
        "DELETE FROM show_summary WHERE tmdbId NOT IN (SELECT tmdbId FROM collected_show WHERE deleted = 0) AND NOT EXISTS " +
            "(SELECT 1 FROM summary_refresh r WHERE r.type = 'show' AND r.tmdbId = show_summary.tmdbId AND r.fetchedAt >= :before)",
    )
    suspend fun pruneShows(before: Long)

    @Query(
        "DELETE FROM movie_summary WHERE tmdbId NOT IN (SELECT tmdbId FROM collected_movie WHERE deleted = 0) AND NOT EXISTS " +
            "(SELECT 1 FROM summary_refresh r WHERE r.type = 'movie' AND r.tmdbId = movie_summary.tmdbId AND r.fetchedAt >= :before)",
    )
    suspend fun pruneMovies(before: Long)

    @Query(
        "DELETE FROM summary_refresh WHERE fetchedAt < :before AND (" +
            "(type = 'show' AND tmdbId NOT IN (SELECT tmdbId FROM collected_show WHERE deleted = 0)) OR " +
            "(type = 'movie' AND tmdbId NOT IN (SELECT tmdbId FROM collected_movie WHERE deleted = 0)))",
    )
    suspend fun pruneRefresh(before: Long)
}

@Dao
interface WatchStateDao {
    @Query("SELECT * FROM watch_state WHERE `key` = :key")
    suspend fun get(key: String): WatchState?

    @Query("SELECT * FROM watch_state WHERE `key` = :key")
    fun observe(key: String): Flow<WatchState?>

    @Query("SELECT * FROM watch_state WHERE showTmdbId = :showTmdbId AND kind = 'Episode' AND deleted = 0")
    fun forShow(showTmdbId: Int): Flow<List<WatchState>>

    @Query(
        "SELECT * FROM watch_state WHERE deleted = 0 AND watched = 0 AND positionMs >= :minPositionMs " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit",
    )
    fun inProgress(minPositionMs: Long, limit: Int): Flow<List<WatchState>>

    @Upsert
    suspend fun upsert(states: List<WatchState>)

    /**
     * Each show's episode watches in one query, for the lists (a flow per show doesn't scale). The
     * CASEs give NULL for the rows a column leaves out, which group_concat skips.
     */
    @Query(
        "SELECT w.showTmdbId AS showTmdbId, " +
            "group_concat(CASE WHEN w.watched = 1 AND w.season >= 1 THEN w.season || ':' || w.episode END) AS watched, " +
            "group_concat(CASE WHEN w.watched = 0 AND w.season >= 1 AND w.positionMs >= :minResumeMs " +
            "THEN w.season || ':' || w.episode || ':' || w.positionMs || ':' || w.lastPlayedAt END, ';') AS inProgress, " +
            "MAX(w.lastPlayedAt) AS lastPlayedAt " +
            "FROM watch_state w WHERE w.kind = 'Episode' AND w.deleted = 0 AND w.showTmdbId IS NOT NULL GROUP BY w.showTmdbId",
    )
    fun showWatchAggregates(minResumeMs: Long): Flow<List<ShowWatchAggregate>>

    @Query("SELECT movieTmdbId, positionMs, durationMs, watched, lastPlayedAt FROM watch_state WHERE kind = 'Movie' AND deleted = 0 AND movieTmdbId IS NOT NULL")
    fun movieWatches(): Flow<List<MovieWatch>>

    /** Every in-progress episode of a collected show, whole rows (Resume needs the release). */
    @Query(
        "SELECT * FROM watch_state WHERE kind = 'Episode' AND deleted = 0 AND watched = 0 AND positionMs >= :minResumeMs " +
            "AND showTmdbId IN (SELECT tmdbId FROM collected_show WHERE deleted = 0)",
    )
    fun inProgressOfCollected(minResumeMs: Long): Flow<List<WatchState>>

    /** Tombstones too. At most 999 keys before Android 11: callers chunk. */
    @Query("SELECT * FROM watch_state WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<WatchState>

    @Query("SELECT * FROM watch_state WHERE kind = 'Episode' AND deleted = 0 AND showTmdbId IN (:ids)")
    suspend fun episodesOf(ids: List<Int>): List<WatchState>

    /** Keeps the [keep] most recent release-only entries; episodes and movies are watch history. */
    @Query(
        "DELETE FROM watch_state WHERE kind = 'Release' AND `key` NOT IN " +
            "(SELECT `key` FROM watch_state WHERE kind = 'Release' ORDER BY lastPlayedAt DESC LIMIT :keep)",
    )
    suspend fun pruneReleases(keep: Int)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_search WHERE deleted = 0 ORDER BY searchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RecentSearch>>

    @Upsert
    suspend fun upsert(search: RecentSearch)

    @Query("UPDATE recent_search SET deleted = 1, updatedAt = :now WHERE deleted = 0 AND `key` NOT IN (SELECT `key` FROM recent_search WHERE deleted = 0 ORDER BY searchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int, now: Long)

    @Query("UPDATE recent_search SET deleted = 1, updatedAt = :now WHERE deleted = 0")
    suspend fun clear(now: Long)

    @Query("UPDATE recent_search SET deleted = 1, updatedAt = :now WHERE `key` = :key")
    suspend fun remove(key: String, now: Long)
}

@Dao
interface TrackChoiceDao {
    @Query("SELECT * FROM show_track_choice WHERE showTmdbId = :showTmdbId")
    suspend fun get(showTmdbId: Int): ShowTrackChoice?

    @Upsert
    suspend fun upsert(choice: ShowTrackChoice)

    @Query("UPDATE show_track_choice SET deleted = 1, updatedAt = :now WHERE showTmdbId = :showTmdbId AND deleted = 0")
    suspend fun clear(showTmdbId: Int, now: Long)
}

/** The titles taken out of Home's Up next ([UpNextRemoval]). */
@Dao
interface UpNextRemovalDao {
    @Query("SELECT * FROM up_next_removal WHERE id = :id")
    suspend fun get(id: String): UpNextRemoval?

    @Upsert
    suspend fun upsert(removal: UpNextRemoval)

    /** Brings back the one still as the removal at [removedAt] left it: not one made again since. */
    @Query("UPDATE up_next_removal SET deleted = 1, updatedAt = :now WHERE id = :id AND deleted = 0 AND updatedAt = :removedAt")
    suspend fun undo(id: String, removedAt: Long, now: Long): Int

    /**
     * The ids of the titles out of Up next: those not played since their removal, a show by its latest play of any
     * episode (specials too), anything else by its own. It reads both tables, so it runs again as either changes.
     */
    @Query(
        "SELECT r.id FROM up_next_removal r WHERE r.deleted = 0 AND r.removedAt >= COALESCE(CASE WHEN r.showTmdbId IS NOT NULL " +
            "THEN (SELECT MAX(w.lastPlayedAt) FROM watch_state w WHERE w.kind = 'Episode' AND w.showTmdbId = r.showTmdbId AND w.deleted = 0) " +
            "ELSE (SELECT w.lastPlayedAt FROM watch_state w WHERE w.`key` = r.watchKey AND w.deleted = 0) END, 0)",
    )
    fun hidden(): Flow<List<String>>
}

/** The releases found missing on the providers ([TriedRelease]). */
@Dao
interface TriedReleaseDao {
    @Query("SELECT * FROM tried_release WHERE id = :id")
    suspend fun get(id: String): TriedRelease?

    /** At most 999 ids before Android 11: callers chunk. */
    @Query("SELECT * FROM tried_release WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<TriedRelease>

    /** [getAll], again whenever the table changes. */
    @Query("SELECT * FROM tried_release WHERE id IN (:ids)")
    fun observe(ids: List<String>): Flow<List<TriedRelease>>

    @Query("SELECT * FROM tried_release")
    suspend fun all(): List<TriedRelease>

    @Upsert
    suspend fun upsert(entries: List<TriedRelease>)

    /** Those last checked after [at]: a check of theirs counts still. */
    @Query("SELECT COUNT(*) FROM tried_release WHERE updatedAt > :at")
    suspend fun count(at: Long): Int

    @Query("DELETE FROM tried_release WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tried_release")
    suspend fun clear()

    /** Those last checked at [at] or before: every check of theirs has expired. */
    @Query("DELETE FROM tried_release WHERE updatedAt <= :at")
    suspend fun prune(at: Long)

    /** Keeps the [keep] most recently checked. */
    @Query("DELETE FROM tried_release WHERE id NOT IN (SELECT id FROM tried_release ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

internal class Converters {
    @TypeConverter
    fun fromKind(kind: WatchKind): String = kind.name

    @TypeConverter
    fun toKind(value: String): WatchKind = WatchKind.valueOf(value)

    @TypeConverter
    fun fromSubtitleMode(mode: SubtitleMode?): String? = mode?.name

    @TypeConverter
    fun toSubtitleMode(value: String?): SubtitleMode? = value?.let(SubtitleMode::valueOf)

    @TypeConverter
    fun fromTitleType(type: TitleType?): String? = type?.name

    @TypeConverter
    fun toTitleType(value: String?): TitleType? = value?.let(TitleType::valueOf)

    @TypeConverter
    fun fromChecks(checks: List<ProviderCheck>): String = Json.encodeToString(checksSerializer, checks)

    /** A value that doesn't parse reads as no checks: the release is tried, and its next check writes it again. */
    @TypeConverter
    fun toChecks(value: String): List<ProviderCheck> = runCatching { Json.decodeFromString(checksSerializer, value) }.getOrDefault(emptyList())
}

/** [TriedRelease.missingOn] as stored: a JSON array. Typed, as KSP doesn't see the serializer the plugin adds. */
private val checksSerializer: KSerializer<List<ProviderCheck>> = ListSerializer(ProviderCheck.serializer())

@Database(
    entities = [
        CollectedShow::class, WatchState::class, RecentSearch::class, ShowTrackChoice::class,
        CollectedMovie::class, ShowSummary::class, MovieSummary::class, SummaryRefresh::class, TriedRelease::class, UpNextRemoval::class,
    ],
    version = 13,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun collection(): CollectionDao
    abstract fun movies(): MovieCollectionDao
    abstract fun summaries(): SummaryDao
    abstract fun watchStates(): WatchStateDao
    abstract fun recentSearches(): RecentSearchDao
    abstract fun trackChoices(): TrackChoiceDao
    abstract fun triedReleases(): TriedReleaseDao
    abstract fun upNextRemovals(): UpNextRemovalDao

    companion object {
        /** Version 2 adds each show's audio and subtitles ([ShowTrackChoice]); every version 1 row stays. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../2.json).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `show_track_choice` (`showTmdbId` INTEGER NOT NULL, `audioLanguage` TEXT, " +
                        "`audioCommentary` INTEGER NOT NULL, `subtitles` TEXT, `subtitleLanguage` TEXT, `subtitleSdh` INTEGER NOT NULL, " +
                        "`subtitleForced` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`showTmdbId`))",
                )
            }
        }

        /** Version 3 adds the TMDB title a recent search was picked from ([SearchPick]); every version 2 row stays. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates them (schemas/.../3.json); a search from before has no pick and replays as text.
                listOf("`pick_type` TEXT", "`pick_tmdbId` INTEGER", "`pick_name` TEXT", "`pick_season` INTEGER", "`pick_episode` INTEGER")
                    .forEach { db.execSQL("ALTER TABLE `recent_search` ADD COLUMN $it") }
            }
        }

        /**
         * Version 4 adds the name and size of the file a remembered release played ([RememberedRelease.fileName]);
         * every version 3 row stays, its release found by the file's position as before.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates them (schemas/.../4.json).
                listOf("`release_fileName` TEXT", "`release_fileSize` INTEGER")
                    .forEach { db.execSQL("ALTER TABLE `watch_state` ADD COLUMN $it") }
            }
        }

        /**
         * Version 5 adds the movie collection ([CollectedMovie]), the lists' cache of TMDB facts ([ShowSummary],
         * [MovieSummary]) and its bookkeeping ([SummaryRefresh]), and an index for what is in progress;
         * every version 4 row stays. A movie added later finds the progress it had by its key.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates them (schemas/.../5.json). New tables only: nothing here depends on the rows there are.
                listOf(
                    "CREATE TABLE IF NOT EXISTS `collected_movie` (`id` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `imdbId` TEXT, `title` TEXT NOT NULL, " +
                        "`year` INTEGER, `posterPath` TEXT, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `show_summary` (`tmdbId` INTEGER NOT NULL, `originalName` TEXT, `status` TEXT, `seasons` TEXT NOT NULL, " +
                        "`tvdbId` INTEGER, `imdbId` TEXT, `last_season` INTEGER, `last_episode` INTEGER, `last_name` TEXT, `last_airDay` INTEGER, " +
                        "`next_season` INTEGER, `next_episode` INTEGER, `next_name` TEXT, `next_airDay` INTEGER, PRIMARY KEY(`tmdbId`))",
                    "CREATE TABLE IF NOT EXISTS `movie_summary` (`tmdbId` INTEGER NOT NULL, `originalTitle` TEXT, `runtimeMinutes` INTEGER, " +
                        "`releaseDay` INTEGER, `status` TEXT, `imdbId` TEXT, PRIMARY KEY(`tmdbId`))",
                    "CREATE TABLE IF NOT EXISTS `summary_refresh` (`type` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "`staleAt` INTEGER NOT NULL, `retryAt` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`type`, `tmdbId`))",
                    "CREATE INDEX IF NOT EXISTS `index_watch_state_watched_positionMs` ON `watch_state` (`watched`, `positionMs`)",
                ).forEach(db::execSQL)
            }
        }

        /** Version 6 adds the releases found missing on the providers ([TriedRelease]); every version 5 row stays. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates them (schemas/.../6.json). A new table only.
                listOf(
                    "CREATE TABLE IF NOT EXISTS `tried_release` (`id` TEXT NOT NULL, `indexerId` TEXT NOT NULL, `guid` TEXT NOT NULL, " +
                        "`missingOn` TEXT NOT NULL, `incompleteAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_tried_release_updatedAt` ON `tried_release` (`updatedAt`)",
                ).forEach(db::execSQL)
            }
        }

        /**
         * Version 7 adds a movie's original language to its TMDB facts ([MovieSummary.originalLanguage]); every
         * version 6 row stays. The facts stored say none until TMDB's next answer about the movie, which
         * the refresher writes as a change, or the movie's page offers.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../7.json). A column only: nothing is asked of TMDB again for it.
                db.execSQL("ALTER TABLE `movie_summary` ADD COLUMN `originalLanguage` TEXT")
            }
        }

        /**
         * Version 8 adds when a release found missing needed a password it didn't come with ([TriedRelease.passwordAt]);
         * every version 7 row stays, as one that needed none.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../8.json). A column only.
                db.execSQL("ALTER TABLE `tried_release` ADD COLUMN `passwordAt` INTEGER")
            }
        }

        /**
         * Version 9 adds which kind a recent search is ([RecentSearch.kind]): every version 8 row stays a search of the
         * indexers, one picked from a title searched by its ids again as before. A title opened from Search has a kind
         * of its own: the pick alone can't tell it apart, as the play sheet's searches by a title's ids have one too.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../9.json). A column only.
                db.execSQL("ALTER TABLE `recent_search` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'Search'")
            }
        }

        /**
         * Version 10 adds whether a show's audio is the audio description ([ShowTrackChoice.audioDescription]): every
         * version 9 choice stays one of the main audio or commentary, as Matroska's description flag wasn't read before.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../10.json). A column only.
                db.execSQL("ALTER TABLE `show_track_choice` ADD COLUMN `audioDescription` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 11 adds the titles taken out of Home's Up next ([UpNextRemoval]); every version 10 row stays, and nothing is taken out. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../11.json). A new table only.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `up_next_removal` (`id` TEXT NOT NULL, `showTmdbId` INTEGER, `watchKey` TEXT, " +
                        "`removedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * Version 12 adds where a remembered release played from ([RememberedRelease.source]); every version 11 row stays,
         * its release one that played from Usenet, as all did.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../12.json). A column only.
                db.execSQL("ALTER TABLE `watch_state` ADD COLUMN `release_source` TEXT")
            }
        }

        /**
         * Version 13 adds when a release was found a copy of a disc ([TriedRelease.discAt]); every version 12 row stays, as
         * one that wasn't.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // As Room creates it (schemas/.../13.json). A column only.
                db.execSQL("ALTER TABLE `tried_release` ADD COLUMN `discAt` INTEGER")
            }
        }

        /** Every migration, oldest first, as [create] opens the database with them. */
        val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        )

        /** The app's library, or another file of [name] (the scale tests' own, so the app's is never touched). */
        fun create(context: Context, name: String = "library.db"): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*MIGRATIONS).build()

        fun inMemory(context: Context): LibraryDatabase =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }
}

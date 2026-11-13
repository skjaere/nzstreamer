package io.skjaere.usenet.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library database from version 1 (shows, watch states, searches) to 2 (with each show's audio
 * and subtitles), 3 (with the TMDB title a recent search was picked from), 4 (with the name and
 * size of the file a remembered release played), 5 (with the movie collection and the lists'
 * cache of TMDB facts), 6 (with the releases found missing on the providers), 7 (with a movie's
 * original language among its TMDB facts), 8 (with when a release needed a password it didn't come with), 9
 * (with which kind a recent search is: a search of the indexers, or a TMDB title opened), 10 (with whether
 * a show's audio is the audio description), 11 (with the titles taken out of Home's Up next), 12 (with where a
 * remembered release played from) and 13 (with when a release was found a copy of a disc).
 */
@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, LibraryDatabase::class.java)

    @After
    fun delete() {
        instrumentation.targetContext.deleteDatabase(NAME)
    }

    @Test
    fun everyVersion1RowSurvivesTheMigration() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0), " +
                    "('tmdb:900005', 900005, NULL, NULL, NULL, 'The Quiet Orbit', NULL, NULL, 12, 13, 1)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId) VALUES " +
                    "('episode:tmdb:900002:s1:e1', 'Episode', 900002, 1, 1, NULL, 'Harbor Lights S01E01', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s01e01', 'Harbor.Lights.S01E01', 'http://x/api?apikey={apikey}', 0, 'abc'), " +
                    "('release:n1:0', 'Release', NULL, NULL, NULL, NULL, 'Release 1', 120000, 2700000, 1, 22, 23, 0, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO recent_search (`key`, query, searchedAt, updatedAt, deleted) VALUES ('harbor lights', 'Harbor Lights', 30, 31, 0)")
        }

        helper.runMigrationsAndValidate(NAME, 2, true, LibraryDatabase.MIGRATION_1_2).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2, count("collected_show"))
            assertEquals(2, count("watch_state"))
            assertEquals(1, count("recent_search"))
            assertEquals(0, count("show_track_choice"))
        }

        // Room opens the migrated file (on to the current version), the old rows as they were and the new table at work.
        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("Harbor Lights"), library.shows.first().map { it.name })
                val episode = library.state("episode:tmdb:900002:s1:e1").first()!!
                assertEquals(600_000, episode.positionMs)
                assertEquals(RememberedRelease("fake", "hl-s01e01", "Harbor.Lights.S01E01", "http://x/api?apikey={apikey}", 0, "abc"), episode.release)
                assertEquals(true, library.state("release:n1:0").first()?.watched)
                assertEquals(listOf("Harbor Lights"), library.recentSearches.first().map { it.query })
                library.setTrackChoice(ShowTrackChoice(900002, audioLanguage = "de", subtitles = SubtitleMode.ForcedOnly))
                assertEquals(ShowTrackChoice(900002, audioLanguage = "de", subtitles = SubtitleMode.ForcedOnly, updatedAt = 40), library.trackChoice(900002))
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun everyVersion2RowSurvivesTheMigrationTo3() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId) VALUES " +
                    "('episode:tmdb:900002:s2:e5', 'Episode', 900002, 2, 5, NULL, 'Harbor Lights S02E05', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s02e05', 'Harbor.Lights.S02E05', NULL, 0, 'abc')",
            )
            db.execSQL(
                "INSERT INTO recent_search (`key`, query, searchedAt, updatedAt, deleted) VALUES " +
                    "('harbor lights s02e05', 'Harbor Lights S02E05', 30, 31, 0), ('copper crown', 'Copper Crown', 32, 33, 1)",
            )
            db.execSQL(
                "INSERT INTO show_track_choice (showTmdbId, audioLanguage, audioCommentary, subtitles, subtitleLanguage, subtitleSdh, subtitleForced, updatedAt, deleted) " +
                    "VALUES (900002, 'de', 0, 'ForcedOnly', NULL, 0, 0, 34, 0)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 3, true, LibraryDatabase.MIGRATION_2_3).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("collected_show"))
            assertEquals(1, count("watch_state"))
            assertEquals(2, count("recent_search"))
            assertEquals(1, count("show_track_choice"))
            // The tombstone too, as sync needs it; an old search has no picked title.
            db.query("SELECT `key`, deleted, pick_type, pick_tmdbId, pick_name, pick_season, pick_episode FROM recent_search ORDER BY `key`").use { c ->
                val rows = generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) } else null }.toList()
                assertEquals(
                    listOf(listOf("copper crown", "1", null, null, null, null, null), listOf("harbor lights s02e05", "0", null, null, null, null, null)),
                    rows,
                )
            }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                // Old searches replay as free text, as before.
                assertEquals(listOf(RecentSearch("harbor lights s02e05", "Harbor Lights S02E05", 30, 31)), library.recentSearches.first())
                assertEquals(600_000L, library.state("episode:tmdb:900002:s2:e5").first()?.positionMs)
                assertEquals(SubtitleMode.ForcedOnly, library.trackChoice(900002)?.subtitles)
                val pick = SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5)
                library.addSearch("Harbor Lights S02E05", pick)
                assertEquals(listOf(RecentSearch("harbor lights s02e05", "Harbor Lights S02E05", 40, 40, pick = pick)), library.recentSearches.first())
            }
        } finally {
            room.close()
        }
    }

    /**
     * A release remembered in version 3 has no file name: it stays, found by its position as before,
     * and takes the name of the file the next time it plays (#179).
     */
    @Test
    fun everyVersion3RowSurvivesTheMigrationTo4() {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId) VALUES " +
                    "('episode:tmdb:900002:s2:e5', 'Episode', 900002, 2, 5, NULL, 'Harbor Lights S02E05', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s02-pack', 'Harbor.Lights.S02.1080p.WEB.h264-GRP', NULL, 5, 'abc'), " +
                    "('release:n1:0', 'Release', NULL, NULL, NULL, NULL, 'Release 1', 120000, 2700000, 1, 22, 23, 0, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 4, true, LibraryDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT `key`, release_fileIndex, release_fileName, release_fileSize FROM watch_state ORDER BY `key`").use { c ->
                val rows = generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) } else null }.toList()
                assertEquals(listOf(listOf("episode:tmdb:900002:s2:e5", "5", null, null), listOf("release:n1:0", null, null, null)), rows)
            }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                val key = "episode:tmdb:900002:s2:e5"
                val old = RememberedRelease("fake", "hl-s02-pack", "Harbor.Lights.S02.1080p.WEB.h264-GRP", null, 5, "abc")
                assertEquals(old, library.lastRelease(key))
                assertEquals(true, library.state("release:n1:0").first()?.watched)
                // Played again, it is remembered with its file's name.
                val named = old.copy(fileIndex = 4, fileName = "Harbor.Lights.S02E05.1080p.WEB.h264-GRP.mkv", fileSize = 1_000_005_000)
                val template = WatchState(key, WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 5, title = "Harbor Lights S02E05", updatedAt = 0)
                library.recordProgress(template, 700_000, 2_700_000, named)
                assertEquals(named, library.lastRelease(key))
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 5 only adds tables: every version 4 row stays, and a movie added to the collection
     * afterwards shows the progress it had before.
     */
    @Test
    fun everyVersion4RowSurvivesTheMigrationTo5() {
        helper.createDatabase(NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0), " +
                    "('tmdb:900005', 900005, NULL, NULL, NULL, 'The Quiet Orbit', NULL, NULL, 12, 13, 1)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize) VALUES " +
                    "('episode:tmdb:900002:s2:e5', 'Episode', 900002, 2, 5, NULL, 'Harbor Lights S02E05', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s02e05', 'Harbor.Lights.S02E05', NULL, 0, 'abc', 'Harbor.Lights.S02E05.mkv', 1000005000), " +
                    "('episode:tmdb:900002:s2:e4', 'Episode', 900002, 2, 4, NULL, 'Harbor Lights S02E04', 0, 2700000, 1, 18, 19, 0, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                    "('movie:tmdb:900011', 'Movie', NULL, NULL, NULL, 900011, 'Copper Crown', 2400000, 7080000, 0, 22, 23, 0, " +
                    "'fake', 'cc-2019', 'Copper.Crown.2019.1080p', NULL, 0, 'def', 'Copper.Crown.2019.1080p.mkv', 4000000000), " +
                    "('release:n1:0', 'Release', NULL, NULL, NULL, NULL, 'Release 1', 120000, 2700000, 1, 24, 25, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO recent_search (`key`, query, searchedAt, updatedAt, deleted, pick_type, pick_tmdbId, pick_name, pick_season, pick_episode) " +
                    "VALUES ('copper crown', 'Copper Crown', 30, 31, 0, 'Movie', 900011, 'Copper Crown', NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO show_track_choice (showTmdbId, audioLanguage, audioCommentary, subtitles, subtitleLanguage, subtitleSdh, subtitleForced, updatedAt, deleted) " +
                    "VALUES (900002, 'de', 0, 'ForcedOnly', NULL, 0, 0, 34, 0)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 5, true, LibraryDatabase.MIGRATION_4_5).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2, count("collected_show"))
            assertEquals(4, count("watch_state"))
            assertEquals(1, count("recent_search"))
            assertEquals(1, count("show_track_choice"))
            listOf("collected_movie", "show_summary", "movie_summary", "summary_refresh").forEach { assertEquals(0, count(it)) }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("Harbor Lights"), library.shows.first().map { it.name })
                assertEquals(
                    RememberedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05", null, 0, "abc", "Harbor.Lights.S02E05.mkv", 1_000_005_000),
                    library.lastRelease("episode:tmdb:900002:s2:e5"),
                )
                assertEquals(SearchPick(TitleType.Movie, 900011, "Copper Crown"), library.recentSearches.first().single().pick)
                assertEquals(SubtitleMode.ForcedOnly, library.trackChoice(900002)?.subtitles)
                // The new tables at work.
                library.addMovie(CollectedMovie(CollectedMovie.idFor(900011), 900011, title = "Copper Crown", year = 2019, addedAt = 0, updatedAt = 0))
                assertEquals(listOf(CollectedMovie("tmdb:900011", 900011, title = "Copper Crown", year = 2019, addedAt = 40, updatedAt = 40)), library.movies.first())
                assertEquals(CollectionCounts(shows = 1, movies = 1), library.counts.first())
                // Its progress from before it was collected.
                assertEquals(listOf(MovieWatch(900011, 2_400_000, 7_080_000, false, 22)), library.movieWatches.first())
                assertEquals("Copper.Crown.2019.1080p.mkv", library.lastRelease(WatchKey.movie(900011))?.fileName)
                val show = ShowSummary(900002, "Harbor Lights", "Returning Series", "1:3,2:8", EpisodeMark(2, 5, "The Long Tide", 20_732), EpisodeMark(2, 6, null, 20_746), 900001, "tt9000003")
                val movie = MovieSummary(900011, "Kupferkrone", 118, 18_208, "Released", "tt9000012")
                library.saveSummaries(listOf(show), listOf(movie), listOf(SummaryRefresh(SummaryRefresh.SHOW, 900002, fetchedAt = 40, staleAt = 50)))
                assertEquals(listOf(show), library.showSummaries.first())
                assertEquals(listOf(movie), library.movieSummaries.first())
                assertEquals(
                    listOf(ShowWatchAggregate(900002, "2:4", "2:5:600000:20", 20)),
                    library.showWatchAggregates().first(),
                )
            }
        } finally {
            room.close()
        }
    }

    /** Version 6 only adds a table: every version 5 row stays, and a release found missing is remembered beside them. */
    @Test
    fun everyVersion5RowSurvivesTheMigrationTo6() {
        helper.createDatabase(NAME, 5).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0)",
            )
            db.execSQL(
                "INSERT INTO collected_movie (id, tmdbId, imdbId, title, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900011', 900011, 'tt9000012', 'Copper Crown', 2019, NULL, 12, 13, 0)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize) VALUES " +
                    "('episode:tmdb:900002:s1:e1', 'Episode', 900002, 1, 1, NULL, 'Harbor Lights S01E01', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s01e01-good', 'Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD', NULL, 0, 'abc', 'Harbor.Lights.S01E01.mkv', 1000005000)",
            )
            db.execSQL("INSERT INTO recent_search (`key`, query, searchedAt, updatedAt, deleted) VALUES ('harbor lights', 'Harbor Lights', 30, 31, 0)")
            db.execSQL(
                "INSERT INTO show_track_choice (showTmdbId, audioLanguage, audioCommentary, subtitles, subtitleLanguage, subtitleSdh, subtitleForced, updatedAt, deleted) " +
                    "VALUES (900002, 'de', 0, 'ForcedOnly', NULL, 0, 0, 34, 0)",
            )
            db.execSQL(
                "INSERT INTO show_summary (tmdbId, originalName, status, seasons, tvdbId, imdbId, last_season, last_episode, last_name, last_airDay, " +
                    "next_season, next_episode, next_name, next_airDay) VALUES (900002, NULL, 'Ended', '1:10', 900001, 'tt9000003', 1, 10, NULL, 18750, NULL, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO movie_summary (tmdbId, originalTitle, runtimeMinutes, releaseDay, status, imdbId) VALUES (900011, NULL, 118, 17956, 'Released', 'tt9000012')")
            db.execSQL("INSERT INTO summary_refresh (type, tmdbId, fetchedAt, staleAt, retryAt, failures, error) VALUES ('show', 900002, 35, 36, 0, 0, NULL)")
        }

        helper.runMigrationsAndValidate(NAME, 6, true, LibraryDatabase.MIGRATION_5_6).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            listOf("collected_show", "collected_movie", "watch_state", "recent_search", "show_track_choice", "show_summary", "movie_summary", "summary_refresh")
                .forEach { assertEquals(it, 1, count(it)) }
            assertEquals(0, count("tried_release"))
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("Harbor Lights"), library.shows.first().map { it.name })
                assertEquals(listOf("Copper Crown"), library.movies.first().map { it.title })
                assertEquals("Harbor.Lights.S01E01.mkv", library.lastRelease("episode:tmdb:900002:s1:e1")?.fileName)
                assertEquals(listOf("Harbor Lights"), library.recentSearches.first().map { it.query })
                assertEquals(SubtitleMode.ForcedOnly, library.trackChoice(900002)?.subtitles)
                assertEquals("1:10", library.showSummaries.first().single().seasons)
                assertEquals(118, library.movieSummaries.first().single().runtimeMinutes)
                // The new table at work.
                library.recordMissing("fake", "hl-s01e01-broken", listOf("p-a@10.0.2.2:1119"))
                assertEquals(
                    listOf(TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck("p-a@10.0.2.2:1119", 40)), null, 40)),
                    library.triedReleaseEntries(),
                )
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 7 adds a column to the movies' TMDB facts: every version 6 row stays, the facts stored
     * included, which say no original language until TMDB's next answer does.
     */
    @Test
    fun everyVersion6RowSurvivesTheMigrationTo7() {
        helper.createDatabase(NAME, 6).use { db ->
            db.execSQL(
                "INSERT INTO collected_movie (id, tmdbId, imdbId, title, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900042', 900042, 'tt9000042', 'Moonwell Village', 2021, NULL, 12, 13, 0)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize) VALUES " +
                    "('movie:tmdb:900042', 'Movie', NULL, NULL, NULL, 900042, 'Moonwell Village', 2400000, 7080000, 0, 22, 23, 0, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO show_summary (tmdbId, originalName, status, seasons, tvdbId, imdbId, last_season, last_episode, last_name, last_airDay, " +
                    "next_season, next_episode, next_name, next_airDay) VALUES (900002, NULL, 'Ended', '1:10', 900001, 'tt9000003', 1, 10, NULL, 18750, NULL, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO movie_summary (tmdbId, originalTitle, runtimeMinutes, releaseDay, status, imdbId) VALUES (900042, 'Dalsaem Maeul', 118, 18700, 'Released', 'tt9000042')")
            db.execSQL("INSERT INTO summary_refresh (type, tmdbId, fetchedAt, staleAt, retryAt, failures, error) VALUES ('movie', 900042, 35, 36, 0, 0, NULL)")
            db.execSQL(
                "INSERT INTO tried_release (id, indexerId, guid, missingOn, incompleteAt, updatedAt) VALUES " +
                    "('fake/mv-broken', 'fake', 'mv-broken', '[{\"provider\":\"p-a@10.0.2.2:1119\",\"checkedAt\":30}]', NULL, 30)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 7, true, LibraryDatabase.MIGRATION_6_7).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            listOf("collected_movie", "watch_state", "show_summary", "movie_summary", "summary_refresh", "tried_release")
                .forEach { assertEquals(it, 1, count(it)) }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("Moonwell Village"), library.movies.first().map { it.title })
                assertEquals(listOf(MovieWatch(900042, 2_400_000, 7_080_000, false, 22)), library.movieWatches.first())
                assertEquals("1:10", library.showSummaries.first().single().seasons)
                assertEquals(MovieSummary(900042, "Dalsaem Maeul", 118, 18_700, "Released", "tt9000042"), library.movieSummaries.first().single())
                assertEquals(listOf("fake/mv-broken"), library.triedReleaseEntries().map { it.id })
                // TMDB's next answer fills the new column in.
                val answered = MovieSummary(900042, "Dalsaem Maeul", 118, 18_700, "Released", "tt9000042", originalLanguage = "ko")
                library.saveSummaries(emptyList(), listOf(answered), emptyList())
                assertEquals(answered, library.movieSummary(900042).first())
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 8 adds a column to the releases found missing: every version 7 row stays, those releases included, which
     * say no password was needed; one found to need a password it didn't come with is remembered among them.
     */
    @Test
    fun everyVersion7RowSurvivesTheMigrationTo8() {
        helper.createDatabase(NAME, 7).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize) VALUES " +
                    "('episode:tmdb:900002:s1:e1', 'Episode', 900002, 1, 1, NULL, 'Harbor Lights S01E01', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s01e01-good', 'Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD', NULL, 0, 'abc', 'Harbor.Lights.S01E01.mkv', 1000005000)",
            )
            db.execSQL("INSERT INTO movie_summary (tmdbId, originalTitle, runtimeMinutes, releaseDay, status, imdbId, originalLanguage) VALUES (900011, NULL, 118, 17956, 'Released', 'tt9000012', 'en')")
            db.execSQL(
                "INSERT INTO tried_release (id, indexerId, guid, missingOn, incompleteAt, updatedAt) VALUES " +
                    "('fake/hl-s01e01-broken', 'fake', 'hl-s01e01-broken', '[{\"provider\":\"p-a@10.0.2.2:1119\",\"checkedAt\":30}]', NULL, 30)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 8, true, LibraryDatabase.MIGRATION_7_8).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            listOf("collected_show", "watch_state", "movie_summary", "tried_release").forEach { assertEquals(it, 1, count(it)) }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("Harbor Lights"), library.shows.first().map { it.name })
                assertEquals("Harbor.Lights.S01E01.mkv", library.lastRelease("episode:tmdb:900002:s1:e1")?.fileName)
                assertEquals("en", library.movieSummaries.first().single().originalLanguage)
                assertEquals(
                    listOf(TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck("p-a@10.0.2.2:1119", 30)), null, 30)),
                    library.triedReleaseEntries(),
                )
                // The new column at work.
                library.recordPasswordNeeded("fake", "hl-s01e01-passwd")
                assertEquals(40L, library.triedReleases(listOf("fake/hl-s01e01-passwd")).values.single().passwordAt)
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 9 adds which kind a recent search is: every version 8 search stays a search of the indexers, a picked
     * title's searched by its ids again as before, and a title opened from Search is remembered beside them.
     */
    @Test
    fun everyVersion8RowSurvivesTheMigrationTo9() {
        helper.createDatabase(NAME, 8).use { db ->
            db.execSQL(
                "INSERT INTO recent_search (`key`, query, searchedAt, updatedAt, deleted, pick_type, pick_tmdbId, pick_name, pick_season, pick_episode) VALUES " +
                    "('harbor lights s02e05', 'Harbor Lights S02E05', 30, 31, 0, 'Tv', 900002, 'Harbor Lights', 2, 5), " +
                    "('copper crown', 'Copper Crown', 32, 33, 0, NULL, NULL, NULL, NULL, NULL), " +
                    "('quiet marsh', 'quiet marsh', 34, 35, 1, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 9, true, LibraryDatabase.MIGRATION_8_9).use { db ->
            // The tombstone too, as sync needs it.
            db.query("SELECT `key`, kind FROM recent_search ORDER BY `key`").use { c ->
                val rows = generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toList()
                assertEquals(listOf("copper crown" to "Search", "harbor lights s02e05" to "Search", "quiet marsh" to "Search"), rows)
            }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                val episode = SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5)
                assertEquals(
                    listOf(RecentSearch("copper crown", "Copper Crown", 32, 33), RecentSearch("harbor lights s02e05", "Harbor Lights S02E05", 30, 31, pick = episode)),
                    library.recentSearches.first(),
                )
                assertEquals(listOf("Harbor Lights"), library.shows.first().map { it.name })
                // The new column at work: the movie opened, beside the search of its words.
                val movie = SearchPick(TitleType.Movie, 900011, "Copper Crown")
                library.addTitle(movie)
                assertEquals(
                    listOf(RecentSearch("title:movie:900011", "Copper Crown", 40, 40, pick = movie, kind = RecentKind.Title), RecentSearch("copper crown", "Copper Crown", 32, 33)),
                    library.recentSearches.first().take(2),
                )
            }
        } finally {
            room.close()
        }
    }

    /** Version 10 adds whether a show's audio is the audio description: every version 9 choice stays one of the main audio or commentary. */
    @Test
    fun everyVersion9RowSurvivesTheMigrationTo10() {
        helper.createDatabase(NAME, 9).use { db ->
            db.execSQL(
                "INSERT INTO show_track_choice (showTmdbId, audioLanguage, audioCommentary, subtitles, subtitleLanguage, subtitleSdh, subtitleForced, updatedAt, deleted) VALUES " +
                    "(900002, 'de', 0, 'Language', 'en', 1, 0, 30, 0), " +
                    "(900005, 'en', 1, NULL, NULL, 0, 0, 31, 0), " +
                    "(900008, NULL, 0, 'Off', NULL, 0, 0, 32, 1)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 10, true, LibraryDatabase.MIGRATION_9_10).use { db ->
            // The tombstone too, as sync needs it.
            db.query("SELECT showTmdbId, audioDescription FROM show_track_choice ORDER BY showTmdbId").use { c ->
                val rows = generateSequence { if (c.moveToNext()) c.getInt(0) to c.getInt(1) else null }.toList()
                assertEquals(listOf(900002 to 0, 900005 to 0, 900008 to 0), rows)
            }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(
                    ShowTrackChoice(900002, audioLanguage = "de", subtitles = SubtitleMode.Language, subtitleLanguage = "en", subtitleSdh = true, updatedAt = 30),
                    library.trackChoice(900002),
                )
                assertEquals(ShowTrackChoice(900005, audioLanguage = "en", audioCommentary = true, updatedAt = 31), library.trackChoice(900005))
                // The new column at work: the audio description picked for a show.
                library.setTrackChoice(ShowTrackChoice(900005, audioLanguage = "en", audioDescription = true))
                assertEquals(ShowTrackChoice(900005, audioLanguage = "en", audioDescription = true, updatedAt = 40), library.trackChoice(900005))
            }
        } finally {
            room.close()
        }
    }

    /** Version 11 adds the titles taken out of Home's Up next: every version 10 row stays, and nothing is taken out. */
    @Test
    fun everyVersion10RowSurvivesTheMigrationTo11() {
        helper.createDatabase(NAME, 10).use { db ->
            db.execSQL(
                "INSERT INTO collected_show (id, tmdbId, tvdbId, imdbId, tvmazeId, name, year, posterPath, addedAt, updatedAt, deleted) " +
                    "VALUES ('tmdb:900002', 900002, 900001, 'tt9000003', NULL, 'Harbor Lights', 2021, '/p.jpg', 10, 11, 0)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, updatedAt, deleted) VALUES " +
                    "('episode:tmdb:900002:s1:e2', 'Episode', 900002, 1, 2, NULL, 'Harbor Lights S01E02', 600000, 2700000, 0, 20, 21, 0), " +
                    "('movie:tmdb:900011', 'Movie', NULL, NULL, NULL, 900011, 'Copper Crown', 900000, 6000000, 0, 22, 23, 0)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 11, true, LibraryDatabase.MIGRATION_10_11).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("collected_show"))
            assertEquals(2, count("watch_state"))
            assertEquals(0, count("up_next_removal"))
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(listOf("movie:tmdb:900011", "episode:tmdb:900002:s1:e2"), library.continueWatching.first().map { it.key })
                assertEquals(emptySet<String>(), library.upNextHidden.first())
                // The new table at work: the show taken out of Up next, until it plays again.
                library.removeFromUpNext("show:900002", showTmdbId = 900002)
                assertEquals(setOf("show:900002"), library.upNextHidden.first())
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 12 adds where a remembered release played from ([RememberedRelease.source]): every version 11 row stays,
     * its release whole and from Usenet (none said).
     */
    @Test
    fun everyVersion11RowSurvivesTheMigrationTo12() {
        helper.createDatabase(NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize) VALUES " +
                    "('episode:tmdb:900002:s1:e2', 'Episode', 900002, 1, 2, NULL, 'Harbor Lights S01E02', 600000, 2700000, 0, 20, 21, 0, " +
                    "'fake', 'hl-s01-pack', 'Harbor.Lights.S01.1080p.WEB.h264-GRP', 'http://x/api?t=get&id=hl-s01-pack&apikey={apikey}', 1, 'abc', " +
                    "'Harbor.Lights.S01E02.1080p.WEB.h264-GRP.mkv', 1400000000), " +
                    "('movie:tmdb:900011', 'Movie', NULL, NULL, NULL, 900011, 'Copper Crown', 900000, 6000000, 0, 22, 23, 0, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO up_next_removal (id, showTmdbId, watchKey, removedAt, updatedAt, deleted) VALUES ('show:900005', 900005, NULL, 30, 31, 0)")
        }

        helper.runMigrationsAndValidate(NAME, 12, true, LibraryDatabase.MIGRATION_11_12).use { db ->
            db.query("SELECT `key`, release_guid, release_fileSize, release_source FROM watch_state ORDER BY `key`").use { c ->
                val rows = generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) } else null }.toList()
                assertEquals(
                    listOf(listOf("episode:tmdb:900002:s1:e2", "hl-s01-pack", "1400000000", null), listOf("movie:tmdb:900011", null, null, null)),
                    rows,
                )
            }
            db.query("SELECT COUNT(*) FROM up_next_removal").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                val key = "episode:tmdb:900002:s1:e2"
                val old = RememberedRelease(
                    "fake", "hl-s01-pack", "Harbor.Lights.S01.1080p.WEB.h264-GRP", "http://x/api?t=get&id=hl-s01-pack&apikey={apikey}", 1, "abc",
                    "Harbor.Lights.S01E02.1080p.WEB.h264-GRP.mkv", 1_400_000_000,
                )
                assertEquals(old, library.lastRelease(key))
                assertEquals(null, library.lastRelease(key)?.source)
                assertEquals(900_000L, library.state("movie:tmdb:900011").first()?.positionMs)
                // The new column at work: a release remembered with where it played from reads back with it.
                val template = WatchState(key, WatchKind.Episode, showTmdbId = 900002, season = 1, episode = 2, title = "Harbor Lights S01E02", updatedAt = 0)
                library.recordProgress(template, 700_000, 2_700_000, old.copy(source = "torrent"))
                assertEquals("torrent", library.lastRelease(key)?.source)
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 13 adds when a release was found a copy of a disc ([TriedRelease.discAt]): every version 12 row stays, the
     * releases found missing or needing a password among them, as none was found one.
     */
    @Test
    fun everyVersion12RowSurvivesTheMigrationTo13() {
        helper.createDatabase(NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO tried_release (id, indexerId, guid, missingOn, incompleteAt, updatedAt, passwordAt) VALUES " +
                    "('fake/hl-s01e01-broken', 'fake', 'hl-s01e01-broken', '[{\"provider\":\"p-a@10.0.2.2:1119\",\"checkedAt\":30}]', NULL, 30, NULL), " +
                    "('fake/hl-s01e01-passwd', 'fake', 'hl-s01e01-passwd', '[]', NULL, 31, 31)",
            )
            db.execSQL(
                "INSERT INTO watch_state (`key`, kind, showTmdbId, season, episode, movieTmdbId, title, positionMs, durationMs, watched, lastPlayedAt, " +
                    "updatedAt, deleted, release_indexerId, release_guid, release_title, release_downloadTemplate, release_fileIndex, release_nzbId, " +
                    "release_fileName, release_fileSize, release_source) VALUES " +
                    "('movie:tmdb:900011', 'Movie', NULL, NULL, NULL, 900011, 'Copper Crown', 900000, 6000000, 0, 22, 23, 0, " +
                    "'prowlarr', 'btih:c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55', 'Copper.Crown.2019.1080p.WEB-DL', NULL, 0, " +
                    "'btih:c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55', 'Copper.Crown.2019.1080p.WEB-DL.mkv', 4000, 'torrent')",
            )
        }

        helper.runMigrationsAndValidate(NAME, 13, true, LibraryDatabase.MIGRATION_12_13).use { db ->
            db.query("SELECT id, passwordAt, discAt FROM tried_release ORDER BY id").use { c ->
                val rows = generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) } else null }.toList()
                assertEquals(listOf(listOf("fake/hl-s01e01-broken", null, null), listOf("fake/hl-s01e01-passwd", "31", null)), rows)
            }
            db.query("SELECT COUNT(*) FROM watch_state").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }

        val room = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, NAME).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val library = LibraryRepository(room) { 40 }
            runBlocking {
                assertEquals(
                    listOf(
                        TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck("p-a@10.0.2.2:1119", 30)), null, 30),
                        TriedRelease("fake/hl-s01e01-passwd", "fake", "hl-s01e01-passwd", emptyList(), null, 31, passwordAt = 31),
                    ),
                    library.triedReleaseEntries().sortedBy { it.id },
                )
                assertEquals("torrent", library.lastRelease("movie:tmdb:900011")?.source)
                // The new column at work: a release found a copy of a disc, kept with what was found of it before.
                library.recordDiscCopy("fake", "hl-s01e01-broken")
                val disc = library.triedReleases(listOf("fake/hl-s01e01-broken")).values.single()
                assertEquals(40L, disc.discAt)
                assertEquals(listOf(ProviderCheck("p-a@10.0.2.2:1119", 30)), disc.missingOn)
            }
        } finally {
            room.close()
        }
    }

    /** Every migration in turn, from the first version on, ends at the schema Room expects of version 13. */
    @Test
    fun theWholeChainFrom1To13Validates() {
        helper.createDatabase(NAME, 1).close()
        helper.runMigrationsAndValidate(NAME, 13, true, *LibraryDatabase.MIGRATIONS).close()
    }

    private companion object {
        const val NAME = "library-migration-test.db"
    }
}

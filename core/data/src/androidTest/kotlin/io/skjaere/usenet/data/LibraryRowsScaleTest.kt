package io.skjaere.usenet.data

import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.data.LibraryRepository.Companion.MIN_RESUME_MS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * The queries the lists read at the scale the slice aims for (1,000 shows with 26k episode states,
 * 2,000 movies), in memory: each well under the budget, with its plan logged ("LibraryRowsScale" in
 * logcat). It measures them without and with the index on watch_state(watched, positionMs), which
 * the schema has because it saved more than 10 % here (40 % on inProgressOfCollected).
 */
@RunWith(AndroidJUnit4::class)
class LibraryRowsScaleTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val library = LibraryRepository(db) { 1_000 }

    @After
    fun close() = db.close()

    /** Deterministic: show i has (i * 7919) % 53 episode states, 26 on average; every third has one in progress. */
    private suspend fun seed(): Int {
        var episodes = 0
        db.withTransaction {
            (1..1_000).forEach { show ->
                db.collection().upsert(CollectedShow(CollectedShow.idFor(show), show, name = "Avrel %04d".format(show), addedAt = show.toLong(), updatedAt = 1))
                val count = (show * 7_919) % 53
                episodes += count
                db.watchStates().upsert(
                    (0 until count).map { e ->
                        val season = if (e % 25 == 24) 0 else 1 + e / 10
                        val inProgress = e == count - 1 && show % 3 == 0
                        WatchState(
                            key = WatchKey.episode(show, season, 1 + e % 10), kind = WatchKind.Episode, showTmdbId = show, season = season, episode = 1 + e % 10,
                            title = "Avrel $show", positionMs = if (inProgress) 600_000 else 0, durationMs = 2_700_000, watched = !inProgress && e < count - 2,
                            lastPlayedAt = show * 1_000L + e, updatedAt = 1, deleted = e % 41 == 40,
                        )
                    },
                )
                if (show % 2 == 0) db.summaries().upsertRefresh(listOf(SummaryRefresh(SummaryRefresh.SHOW, show, fetchedAt = 1, staleAt = 2)))
            }
            (1..2_000).forEach { movie ->
                db.movies().upsert(CollectedMovie(CollectedMovie.idFor(10_000 + movie), 10_000 + movie, title = "Ébrin %04d".format(movie), addedAt = movie.toLong(), updatedAt = 1))
                if (movie % 5 < 3) {
                    db.watchStates().upsert(
                        listOf(
                            WatchState(
                                key = WatchKey.movie(10_000 + movie), kind = WatchKind.Movie, movieTmdbId = 10_000 + movie, title = "Ébrin $movie",
                                positionMs = if (movie % 20 == 1) 1_200_000 else 0, durationMs = 6_000_000, watched = movie % 20 != 1, lastPlayedAt = movie.toLong(), updatedAt = 1,
                            ),
                        ),
                    )
                }
                if (movie % 2 == 0) db.summaries().upsertRefresh(listOf(SummaryRefresh(SummaryRefresh.MOVIE, 10_000 + movie, fetchedAt = 1, staleAt = 2)))
            }
            db.watchStates().upsert((1..200).map { WatchState(WatchKey.release("n$it", 0), WatchKind.Release, title = "Release $it", positionMs = 600_000, updatedAt = 1) })
        }
        return episodes
    }

    private class Query(val name: String, val sql: String, val read: () -> Flow<List<*>>)

    // As in the DAOs, the minimum position inlined.
    private val queries = listOf(
        Query(
            "showWatchAggregates",
            "SELECT w.showTmdbId AS showTmdbId, " +
                "group_concat(CASE WHEN w.watched = 1 AND w.season >= 1 THEN w.season || ':' || w.episode END) AS watched, " +
                "group_concat(CASE WHEN w.watched = 0 AND w.season >= 1 AND w.positionMs >= $MIN_RESUME_MS " +
                "THEN w.season || ':' || w.episode || ':' || w.positionMs || ':' || w.lastPlayedAt END, ';') AS inProgress, " +
                "MAX(w.lastPlayedAt) AS lastPlayedAt " +
                "FROM watch_state w WHERE w.kind = 'Episode' AND w.deleted = 0 AND w.showTmdbId IS NOT NULL GROUP BY w.showTmdbId",
        ) { library.showWatchAggregates() },
        Query(
            "movieWatches",
            "SELECT movieTmdbId, positionMs, durationMs, watched, lastPlayedAt FROM watch_state WHERE kind = 'Movie' AND deleted = 0 AND movieTmdbId IS NOT NULL",
        ) { library.movieWatches },
        Query(
            "inProgressOfCollected",
            "SELECT * FROM watch_state WHERE kind = 'Episode' AND deleted = 0 AND watched = 0 AND positionMs >= $MIN_RESUME_MS " +
                "AND showTmdbId IN (SELECT tmdbId FROM collected_show WHERE deleted = 0)",
        ) { library.inProgressOfCollected },
        Query(
            "showCandidates",
            "SELECT c.tmdbId AS tmdbId, c.addedAt AS addedAt, r.fetchedAt AS fetchedAt, r.staleAt AS staleAt, r.retryAt AS retryAt, r.failures AS failures, " +
                "(SELECT MAX(w.lastPlayedAt) FROM watch_state w WHERE w.showTmdbId = c.tmdbId AND w.kind = 'Episode' AND w.deleted = 0) AS lastWatchedAt " +
                "FROM collected_show c LEFT JOIN summary_refresh r ON r.type = 'show' AND r.tmdbId = c.tmdbId WHERE c.deleted = 0",
        ) { db.collection().refreshCandidates() },
        Query(
            "movieCandidates",
            "SELECT m.tmdbId AS tmdbId, m.addedAt AS addedAt, r.fetchedAt AS fetchedAt, r.staleAt AS staleAt, r.retryAt AS retryAt, r.failures AS failures, " +
                "(SELECT w.lastPlayedAt FROM watch_state w WHERE w.`key` = 'movie:tmdb:' || m.tmdbId AND w.deleted = 0) AS lastWatchedAt " +
                "FROM collected_movie m LEFT JOIN summary_refresh r ON r.type = 'movie' AND r.tmdbId = m.tmdbId WHERE m.deleted = 0",
        ) { db.movies().refreshCandidates() },
    )

    /** In logcat, which the test run keeps per test. */
    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private fun plan(sql: String): List<String> =
        db.query("EXPLAIN QUERY PLAN $sql", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("detail")) else null }.toList() }

    /** Median of [RUNS] reads (rows mapped, as a list reads them), after one to warm up. */
    private suspend fun median(query: Query): Pair<Double, Int> {
        var rows = query.read().first().size
        val times = (1..RUNS).map { measureNanoTime { rows = query.read().first().size } / 1e6 }.sorted()
        return times[RUNS / 2] to rows
    }

    @Test
    fun theListsQueriesStayFastAtScaleWithAndWithoutTheIndex() = runBlocking<Unit> {
        val episodes = seed()
        report("seeded 1000 shows, $episodes episode states, 2000 movies")
        val results = mutableMapOf<String, MutableList<Double>>()
        // The first queries of a process are slow (statements compiled, pages read): not measured.
        queries.forEach { median(it) }
        // Twice each way, in turn first, so a warm-up or a GC favours neither.
        repeat(2) { round ->
            listOf(round == 1, round == 0).forEach { indexed ->
                if (indexed) db.openHelper.writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS $INDEX ON watch_state (watched, positionMs)")
                else db.openHelper.writableDatabase.execSQL("DROP INDEX IF EXISTS $INDEX")
                queries.forEach { query ->
                    val (ms, rows) = median(query)
                    results.getOrPut("${query.name} ${if (indexed) "indexed" else "plain"}") { mutableListOf() } += ms
                    if (round == 0) report("${query.name} (${if (indexed) "with" else "without"} the index): plan ${plan(query.sql)}")
                    report("round $round ${query.name} ${if (indexed) "with" else "without"} the index: %.1f ms, $rows rows".format(ms))
                    assertTrue("${query.name} took $ms ms", ms < BUDGET_MS)
                }
            }
        }
        // The better round of each: the other may have paid for a warm-up.
        queries.forEach { query ->
            val plain = results.getValue("${query.name} plain").min()
            val indexed = results.getValue("${query.name} indexed").min()
            report("${query.name}: %.1f ms without, %.1f ms with the index (%+.0f %%)".format(plain, indexed, (indexed - plain) / plain * 100))
        }
        assertEquals(1_000, library.counts.first().shows)
    }

    private companion object {
        const val TAG = "LibraryRowsScale"
        const val RUNS = 7
        const val BUDGET_MS = 250.0
        const val INDEX = "index_watch_state_watched_positionMs"
    }
}

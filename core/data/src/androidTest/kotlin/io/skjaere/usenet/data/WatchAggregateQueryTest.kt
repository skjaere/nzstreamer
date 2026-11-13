package io.skjaere.usenet.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.data.LibraryRepository.Companion.MIN_RESUME_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The lists read every show's watches in one aggregate query, and parse it back: for random watch
 * histories (specials, tombstones, movies and releases among them) it says what the rows say.
 */
@RunWith(AndroidJUnit4::class)
class WatchAggregateQueryTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val library = LibraryRepository(db) { 1_000 }

    @After
    fun close() = db.close()

    /** A random history: episodes of shows 1..[shows] (some only specials, some all tombstones), movies and releases. */
    private fun history(random: Random, shows: Int): List<WatchState> {
        val positions = listOf(0L, 30_000L, MIN_RESUME_MS - 1, MIN_RESUME_MS, 600_000L)
        val episodes = (1..shows).flatMap { show ->
            val specialsOnly = show % 9 == 0
            val allTombstones = show % 11 == 0
            (0 until random.nextInt(0, 30)).map {
                val season = if (specialsOnly) 0 else random.nextInt(0, 4)
                val episode = random.nextInt(1, 13)
                WatchState(
                    key = WatchKey.episode(show, season, episode), kind = WatchKind.Episode, showTmdbId = show, season = season, episode = episode,
                    title = "Avrel $show", positionMs = positions.random(random), watched = random.nextInt(3) == 0,
                    lastPlayedAt = random.nextLong(1, 1_000_000), updatedAt = 1, deleted = allTombstones || random.nextInt(10) == 0,
                )
            }
        }
        val movies = (1..30).map { movie ->
            WatchState(
                key = WatchKey.movie(500 + movie), kind = WatchKind.Movie, movieTmdbId = 500 + movie, title = "Ébrin $movie",
                positionMs = positions.random(random), durationMs = 6_000_000, watched = random.nextBoolean(),
                lastPlayedAt = random.nextLong(1, 1_000_000), updatedAt = 1, deleted = movie % 7 == 0,
            )
        }
        val releases = (1..10).map { WatchState(WatchKey.release("n$it", 0), WatchKind.Release, title = "Release $it", positionMs = 600_000, updatedAt = 1) }
        return (episodes + movies + releases).associateBy { it.key }.values.toList()
    }

    private fun watchedRefs(text: String?): Set<Pair<Int, Int>> =
        text?.split(',')?.map { it.split(':').let { (s, e) -> s.toInt() to e.toInt() } }?.toSet().orEmpty()

    private fun inProgress(text: String?): Set<List<Long>> =
        text?.split(';')?.map { part -> part.split(':').map { it.toLong() } }?.toSet().orEmpty()

    @Test
    fun eachShowsAggregateSaysWhatItsEpisodeRowsSay() = runBlocking<Unit> {
        listOf(1, 2, 3).forEach { seed ->
            val rows = history(Random(seed), shows = 60)
            db.withTransaction {
                db.watchStates().upsert(rows)
            }
            val aggregates = library.showWatchAggregates().first().associateBy { it.showTmdbId }
            val byShow = rows.filter { it.kind == WatchKind.Episode && !it.deleted }.groupBy { it.showTmdbId!! }
            assertEquals(byShow.keys, aggregates.keys)
            byShow.forEach { (show, states) ->
                val aggregate = aggregates.getValue(show)
                val regular = states.filter { it.season!! >= 1 }
                assertEquals("show $show watched", regular.filter { it.watched }.map { it.season!! to it.episode!! }.toSet(), watchedRefs(aggregate.watched))
                assertEquals(
                    "show $show in progress",
                    regular.filter { !it.watched && it.positionMs >= MIN_RESUME_MS }.map { listOf(it.season!!.toLong(), it.episode!!.toLong(), it.positionMs, it.lastPlayedAt) }.toSet(),
                    inProgress(aggregate.inProgress),
                )
                // Over every episode, specials too.
                assertEquals("show $show last played", states.maxOf { it.lastPlayedAt }, aggregate.lastPlayedAt)
            }
            assertTrue("a show with only specials", aggregates.values.any { it.watched == null && it.inProgress == null })

            val movies = rows.filter { it.kind == WatchKind.Movie && !it.deleted }
                .map { MovieWatch(it.movieTmdbId!!, it.positionMs, it.durationMs, it.watched, it.lastPlayedAt) }
            assertEquals(movies.toSet(), library.movieWatches.first().toSet())

            db.clearAllTables()
        }
    }

    /** Home's in-progress episodes of collected shows: one query, whole rows, not a flow per show. */
    @Test
    fun theInProgressEpisodesOfCollectedShows() = runBlocking<Unit> {
        val rows = history(Random(4), shows = 40)
        db.withTransaction {
            db.watchStates().upsert(rows)
            (1..20).forEach { db.collection().upsert(CollectedShow(CollectedShow.idFor(it), it, name = "Avrel $it", addedAt = 1, updatedAt = 1, deleted = it == 5)) }
        }
        val expected = rows.filter {
            it.kind == WatchKind.Episode && !it.deleted && !it.watched && it.positionMs >= MIN_RESUME_MS && it.showTmdbId!! in 1..20 && it.showTmdbId != 5
        }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected.sortedBy { it.key }, library.inProgressOfCollected.first().sortedBy { it.key })
    }
}

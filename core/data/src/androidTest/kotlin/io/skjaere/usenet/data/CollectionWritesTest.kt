package io.skjaere.usenet.data

import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** The collection's writes: movies, removal in bulk with a guarded Undo, marking whole titles watched or unwatched. */
@RunWith(AndroidJUnit4::class)
class CollectionWritesTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private var time = 1_000L
    private val library = LibraryRepository(db) { time }

    @After
    fun close() = db.close()

    private fun show(id: Int, name: String = "Avrel $id") = CollectedShow(CollectedShow.idFor(id), id, name = name, addedAt = 0, updatedAt = 0)

    private fun movie(id: Int, title: String = "Ébrin $id") = CollectedMovie(CollectedMovie.idFor(id), id, title = title, addedAt = 0, updatedAt = 0)

    private fun episode(show: Int, s: Int, e: Int) = WatchState(
        key = WatchKey.episode(show, s, e), kind = WatchKind.Episode, showTmdbId = show, season = s, episode = e,
        title = "Harbor Lights S0${s}E0$e", updatedAt = 0,
    )

    private fun movieState(id: Int, title: String = "Copper Crown") =
        WatchState(key = WatchKey.movie(id), kind = WatchKind.Movie, movieTmdbId = id, title = title, updatedAt = 0)

    private val release = RememberedRelease("fake", "hl-s01e02", "Harbor.Lights.S01E02.1080p", null, 0, "abc", "Harbor.Lights.S01E02.mkv", 1_000)

    @Test
    fun moviesAreCollectedAsShowsAreAndKeepWhenTheyWereAdded() = runBlocking<Unit> {
        library.addMovie(movie(2, "Lantern Field"))
        time = 2_000
        library.addMovie(movie(1, "Copper Crown"))
        assertEquals(listOf("Copper Crown", "Lantern Field"), library.movies.first().map { it.title })
        // Added again: when it was first added stays.
        time = 3_000
        library.addMovie(movie(2, "Lantern Field"))
        assertEquals(1_000L to 3_000L, db.movies().get("tmdb:2")!!.let { it.addedAt to it.updatedAt })
        assertTrue(library.isMovieCollected(2).first())
        // Removed, it is a tombstone; added after that, it is added anew.
        time = 4_000
        library.removeMovie(2)
        assertTrue(db.movies().get("tmdb:2")!!.deleted)
        assertFalse(library.isMovieCollected(2).first())
        time = 5_000
        library.addMovie(movie(2, "Lantern Field"))
        assertEquals(5_000, db.movies().get("tmdb:2")!!.addedAt)
        assertEquals(setOf(1, 2), library.collectedMovieIds.first())

        library.addShow(show(7))
        assertEquals(CollectionCounts(shows = 1, movies = 2), library.counts.first())
        assertTrue(library.isCollected(7).first())
        assertFalse(library.isCollected(8).first())
        assertEquals(setOf(7), library.collectedShowIds.first())
    }

    @Test
    fun aMoviesRowStillNamesItOnceRemoved() = runBlocking<Unit> {
        library.addMovie(movie(1, "Copper Crown"))
        assertEquals("Copper Crown", library.movie(1).first()?.title)
        library.removeMovie(1)
        // Its page, TMDB not answering, is named by the removed row.
        assertEquals("Copper Crown", library.movie(1).first()?.takeIf { it.deleted }?.title)
        assertNull(library.movie(2).first())
    }

    @Test
    fun aRemovalTombstonesAndItsUndoBringsBackOnlyWhatItRemoved() = runBlocking<Unit> {
        (1..4).forEach { library.addShow(show(it)) }
        (11..12).forEach { library.addMovie(movie(it)) }
        time = 2_000
        library.removeShow(4)
        time = 3_000
        // Show 4 was removed before, 99 never collected: neither is part of this removal.
        val removal = library.removeTitles(shows = listOf(1, 2, 4, 99), movies = listOf(11))
        assertEquals(Removal(shows = listOf(1, 2), movies = listOf(11), at = 3_000), removal)
        assertEquals(setOf(3), library.collectedShowIds.first())
        assertEquals(setOf(12), library.collectedMovieIds.first())
        assertEquals(2_000, db.collection().get("tmdb:4")!!.updatedAt)

        // Show 2 added and removed again meanwhile: Undo leaves it as that left it.
        time = 4_000
        library.addShow(show(2))
        time = 5_000
        library.removeShow(2)
        time = 6_000
        assertEquals(2, library.undoRemove(removal))
        assertEquals(setOf(1, 3), library.collectedShowIds.first())
        assertEquals(setOf(11, 12), library.collectedMovieIds.first())
        // Back as it was added, a change of its own.
        assertEquals(1_000L to 6_000L, db.collection().get("tmdb:1")!!.let { it.addedAt to it.updatedAt })
        assertEquals(5_000, db.collection().get("tmdb:2")!!.updatedAt)
        assertTrue(db.collection().get("tmdb:2")!!.deleted)
        // Undone once, again it does nothing.
        assertEquals(0, library.undoRemove(removal))
    }

    @Test
    fun aRemovedShowKeepsItsWatchHistory() = runBlocking<Unit> {
        library.addShow(show(900002, "Harbor Lights"))
        library.recordProgress(episode(900002, 1, 2), positionMs = 600_000, durationMs = 2_700_000, release = release)
        time = 2_000
        library.removeTitles(shows = listOf(900002))
        assertEquals(600_000L, library.episodeStates(900002).first().getValue(1 to 2).positionMs)
        assertEquals(listOf(WatchKey.episode(900002, 1, 2)), library.continueWatching.first().map { it.key })
        // Added again, it resumes where it was.
        library.addShow(show(900002, "Harbor Lights"))
        assertEquals(release, library.lastRelease(WatchKey.episode(900002, 1, 2)))
    }

    /** SQLite takes at most 999 variables in one statement before Android 11. */
    @Test
    fun aRemovalOfMoreTitlesThanOneStatementTakesIsChunked() = runBlocking<Unit> {
        val ids = (1..1_500).toList()
        db.withTransaction {
            ids.forEach {
                db.collection().upsert(show(it))
                db.movies().upsert(movie(it))
            }
        }
        time = 2_000
        val removal = library.removeTitles(shows = ids, movies = ids)
        assertEquals(ids, removal.shows)
        assertEquals(ids, removal.movies)
        assertEquals(CollectionCounts(0, 0), library.counts.first())
        time = 3_000
        assertEquals(3_000, library.undoRemove(removal))
        assertEquals(CollectionCounts(1_500, 1_500), library.counts.first())
    }

    @Test
    fun markingShowsWatchedKeepsWhatItReplacedForUndo() = runBlocking<Unit> {
        // Show 1: S01E01 watched, S01E02 part-way with its release, S01E04 a tombstone; S01E03 nothing yet.
        library.recordProgress(episode(1, 1, 1), positionMs = 2_600_000, durationMs = 2_700_000)
        library.recordProgress(episode(1, 1, 2), positionMs = 600_000, durationMs = 2_700_000, release = release)
        db.watchStates().upsert(listOf(episode(1, 1, 4).copy(watched = true, updatedAt = 500, deleted = true)))
        val before = db.watchStates().getAll(listOf(WatchKey.episode(1, 1, 2), WatchKey.episode(1, 1, 4))).associateBy { it.key }
        time = 2_000
        val marks = listOf(
            ShowMark(1, "Harbor Lights", listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4)),
            ShowMark(2, "The Quiet Orbit", listOf(1 to 1, 1 to 2)),
        )
        val snapshot = library.markShows(marks, watched = true)
        assertEquals(2_000, snapshot.at)
        // What was already so stays as it was.
        assertEquals(setOf(WatchKey.episode(1, 1, 2), WatchKey.episode(1, 1, 4)), snapshot.before.map { it.key }.toSet())
        assertEquals(before.values.toSet(), snapshot.before.toSet())
        assertEquals(setOf(WatchKey.episode(1, 1, 3), WatchKey.episode(2, 1, 1), WatchKey.episode(2, 1, 2)), snapshot.created.toSet())
        val states = library.episodeStates(1).first()
        assertEquals(setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4), states.keys)
        assertTrue(states.values.all { it.watched && it.positionMs == 0L })
        assertEquals(1_000, states.getValue(1 to 1).updatedAt)
        assertEquals(release, states.getValue(1 to 2).release)
        assertEquals("The Quiet Orbit S01E02", library.episodeStates(2).first().getValue(1 to 2).title)

        // Played since: Undo leaves that one as the play left it.
        time = 3_000
        library.recordProgress(episode(2, 1, 2), positionMs = 120_000, durationMs = 2_700_000)
        time = 4_000
        assertEquals(4, library.undoMarks(snapshot))
        val undone = library.episodeStates(1).first()
        assertEquals(setOf(1 to 1, 1 to 2), undone.keys)
        assertEquals(before.getValue(WatchKey.episode(1, 1, 2)).copy(updatedAt = 4_000), undone.getValue(1 to 2))
        assertTrue(db.watchStates().get(WatchKey.episode(1, 1, 4))!!.deleted)
        assertTrue(db.watchStates().get(WatchKey.episode(1, 1, 3))!!.deleted)
        assertEquals(listOf(1 to 2), library.episodeStates(2).first().keys.toList())
        assertEquals(120_000L, library.episodeStates(2).first().getValue(1 to 2).positionMs)
    }

    @Test
    fun markingShowsUnwatchedClearsEveryEpisodeKeepingWhenAndWhatPlayed() = runBlocking<Unit> {
        library.recordProgress(episode(1, 1, 1), positionMs = 2_600_000, durationMs = 2_700_000)
        time = 1_100
        library.recordProgress(episode(1, 1, 2), positionMs = 600_000, durationMs = 2_700_000, release = release)
        library.recordProgress(episode(1, 0, 1), positionMs = 2_600_000, durationMs = 2_700_000)
        library.markEpisodes(listOf(EpisodeRef(1, 1, 3, "Harbor Lights S01E03")), watched = false)
        val before = library.episodeStates(1).first()
        time = 2_000
        val snapshot = library.markShows(listOf(ShowMark(1, "Harbor Lights", emptyList())), watched = false)
        assertEquals(emptyList<String>(), snapshot.created)
        // S01E03 was unwatched from the start already.
        assertEquals(setOf(1 to 1, 1 to 2, 0 to 1).map { (s, e) -> WatchKey.episode(1, s, e) }.toSet(), snapshot.before.map { it.key }.toSet())
        val after = library.episodeStates(1).first()
        assertTrue(after.values.all { !it.watched && it.positionMs == 0L })
        assertEquals(before.mapValues { it.value.lastPlayedAt }, after.mapValues { it.value.lastPlayedAt })
        assertEquals(release, after.getValue(1 to 2).release)
        assertEquals(emptyList<WatchState>(), library.continueWatching.first())

        time = 3_000
        assertEquals(3, library.undoMarks(snapshot))
        val undone = library.episodeStates(1).first()
        assertEquals(before.mapValues { (_, s) -> s.watched to s.positionMs }, undone.mapValues { (_, s) -> s.watched to s.positionMs })
        assertEquals(600_000L, library.continueWatching.first().single().positionMs)
    }

    /** A thousand shows at once, in chunks: every aired episode marked, and all of it undone. */
    @Test
    fun markingAThousandShowsWatchedAndBack() = runBlocking<Unit> {
        val marks = (1..1_000).map { id -> ShowMark(id, "Avrel $id", (1..2).flatMap { s -> (1..10).map { s to it } }) }
        time = 2_000
        val snapshot: WatchSnapshot
        val markMs = measureTimeMillis { snapshot = library.markShows(marks, watched = true) }
        assertEquals(20_000, snapshot.created.size)
        assertEquals(20_000, library.showWatchAggregates().first().sumOf { it.watched!!.split(',').size })
        time = 3_000
        val undoMs = measureTimeMillis { assertEquals(20_000, library.undoMarks(snapshot)) }
        assertEquals(emptyList<ShowWatchAggregate>(), library.showWatchAggregates().first())
        Log.i("CollectionWrites", "marked 1,000 shows (20,000 episodes) in $markMs ms, undone in $undoMs ms")
    }

    @Test
    fun markingMoviesWatchedAndUnwatched() = runBlocking<Unit> {
        val copper = RememberedRelease("fake", "cc-2019", "Copper.Crown.2019.1080p", null, 0, "def", "Copper.Crown.2019.1080p.mkv", 4_000)
        library.recordProgress(movieState(11), positionMs = 2_400_000, durationMs = 7_080_000, release = copper)
        library.recordProgress(movieState(13, "Glasswater Bay"), positionMs = 6_900_000, durationMs = 7_080_000)
        val inProgress = db.watchStates().get(WatchKey.movie(11))!!
        val marks = listOf(MovieMark(11, "Copper Crown"), MovieMark(12, "Lantern Field"), MovieMark(13, "Glasswater Bay"))
        time = 2_000
        val watched = library.markMovies(marks, watched = true)
        assertEquals(listOf(inProgress), watched.before)
        assertEquals(listOf(WatchKey.movie(12)), watched.created)
        assertTrue(listOf(11, 12, 13).all { db.watchStates().get(WatchKey.movie(it))!!.let { s -> s.watched && s.positionMs == 0L } })
        assertEquals(copper, library.lastRelease(WatchKey.movie(11)))
        val created = db.watchStates().get(WatchKey.movie(12))!!
        assertEquals(Triple(WatchKind.Movie, 12, "Lantern Field"), Triple(created.kind, created.movieTmdbId, created.title))

        time = 3_000
        assertEquals(2, library.undoMarks(watched))
        assertEquals(inProgress.copy(updatedAt = 3_000), db.watchStates().get(WatchKey.movie(11)))
        assertTrue(db.watchStates().get(WatchKey.movie(12))!!.deleted)

        time = 4_000
        val unwatched = library.markMovies(marks, watched = false)
        // Nothing is created to say a movie is unwatched; the tombstone stays one.
        assertEquals(emptyList<String>(), unwatched.created)
        assertEquals(setOf(WatchKey.movie(11), WatchKey.movie(13)), unwatched.before.map { it.key }.toSet())
        assertFalse(db.watchStates().get(WatchKey.movie(13))!!.watched)
        assertTrue(db.watchStates().get(WatchKey.movie(12))!!.deleted)
        assertNull(library.continueWatching.first().firstOrNull { it.key == WatchKey.movie(11) })
    }
}

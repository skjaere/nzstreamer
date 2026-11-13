package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** "+ Collection" on Search writes the title from what Search has, then jumps; TMDB is asked afterwards, not waited for. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddToCollectionTest {
    private val orbit = TitleSuggestion(MediaType.Tv, 900005, "The Quiet Orbit", null, 2024, "/orbit.jpg", "A lighthouse keeper.")
    private val copper = TitleSuggestion(MediaType.Movie, 900011, "Copper Crown", null, 2019, "/cc.jpg", null)

    /** The writes and the refresher's requests, in order; [gate] holds the writes until completed. */
    private class Library(val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit), val fail: Boolean = false) {
        val shows = mutableListOf<CollectedShow>()
        val movies = mutableListOf<CollectedMovie>()
        val events = mutableListOf<String>()

        suspend fun addShow(show: CollectedShow) {
            gate.await()
            if (fail) throw IOException("disk full")
            shows += show
            events += "write ${show.tmdbId}"
        }

        suspend fun addMovie(movie: CollectedMovie) {
            gate.await()
            movies += movie
            events += "write ${movie.tmdbId}"
        }
    }

    private fun TestScope.adder(library: Library): Pair<CollectionAdder, CoroutineScope> {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val adder = CollectionAdder(
            app, library::addShow, library::addMovie,
            refresh = { keys -> library.events += "refresh ${keys.joinToString()}"; awaitCancellation() },
            now = { 1_000 },
        )
        return adder to app
    }

    @Test
    fun `a suggestion goes in from its own data, and the jump comes once it is stored`() = runTest {
        val library = Library(gate = CompletableDeferred())
        val (adder, app) = adder(library)
        val jump = adder.add(orbit)
        runCurrent()
        assertFalse(jump.isCompleted, "jumped before the show was stored")
        assertEquals(setOf(TitleKey.show(900005)), adder.adding.value)
        library.gate.complete(Unit)
        runCurrent()
        assertEquals(CollectionJump("show:900005", "The Quiet Orbit"), jump.await())
        assertEquals(listOf(CollectedShow(CollectedShow.idFor(900005), 900005, name = "The Quiet Orbit", year = 2024, posterPath = "/orbit.jpg", addedAt = 1_000, updatedAt = 1_000)), library.shows)
        assertEquals(emptySet(), adder.adding.value)
        app.cancel()
    }

    @Test
    fun `TMDB is asked for its facts after the write, and the jump doesn't wait for it`() = runTest {
        val library = Library()
        val (adder, app) = adder(library)
        val jump = adder.add(orbit)
        runCurrent()
        // The refresher never returns here: the jump came all the same.
        assertTrue(jump.isCompleted)
        assertEquals(listOf("write 900005", "refresh show:900005"), library.events)
        app.cancel()
    }

    @Test
    fun `a movie suggestion goes to the movies`() = runTest {
        val library = Library()
        val (adder, app) = adder(library)
        val jump = adder.add(copper)
        runCurrent()
        assertEquals(CollectionJump("movie:900011", "Copper Crown"), jump.await())
        assertEquals(listOf(CollectedMovie(CollectedMovie.idFor(900011), 900011, title = "Copper Crown", year = 2019, posterPath = "/cc.jpg", addedAt = 1_000, updatedAt = 1_000)), library.movies)
        assertEquals(listOf("write 900011", "refresh movie:900011"), library.events)
        app.cancel()
    }

    @Test
    fun `the title card's title keeps the ids its lookup found`() = runTest {
        val library = Library()
        val (adder, app) = adder(library)
        val show = TitleInfo(MediaType.Tv, 900002, "Harbor Lights", 2021, "TV series · 2021 · 1 season", null, "/hl.jpg", null, TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003", tvmaze = 900004), 45)
        val movie = TitleInfo(MediaType.Movie, 900011, "Copper Crown", 2019, "Movie · 2019", null, "/cc.jpg", null, TvIds(tmdb = 900011, imdb = "tt9000012"), 118)
        assertEquals(CollectionJump("show:900002", "Harbor Lights"), adder.add(show).await())
        assertEquals(CollectionJump("movie:900011", "Copper Crown"), adder.add(movie).await())
        assertEquals(
            CollectedShow(CollectedShow.idFor(900002), 900002, 900001, "tt9000003", 900004, "Harbor Lights", 2021, "/hl.jpg", addedAt = 1_000, updatedAt = 1_000),
            library.shows.single(),
        )
        assertEquals(CollectedMovie(CollectedMovie.idFor(900011), 900011, "tt9000012", "Copper Crown", 2019, "/cc.jpg", addedAt = 1_000, updatedAt = 1_000), library.movies.single())
        app.cancel()
    }

    @Test
    fun `a write that fails fails the jump, and nothing is asked of TMDB`() = runTest {
        val library = Library(fail = true)
        val (adder, app) = adder(library)
        val jump = adder.add(orbit)
        runCurrent()
        assertIs<IOException>(runCatching { jump.await() }.exceptionOrNull())
        assertEquals(emptySet(), adder.adding.value)
        assertEquals(emptyList(), library.events)
        app.cancel()
    }
}

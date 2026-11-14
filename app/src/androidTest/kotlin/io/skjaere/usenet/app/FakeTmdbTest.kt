package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FakeTmdb's strict mode, as the README screenshots use it: nothing it doesn't know reaches TMDB. The real lookups are
 * swapped here for stand-ins that only record that they were asked, so no test reaches the network, and are put back
 * after. An id, query or key the strict fake has no answer for throws, is kept, and fails [FakeTmdb.close]. The
 * player's own TMDB seams answer from the fake: the next and previous episode from its shows and seasons, and
 * TheIntroDB's segments none. The titles are the README screens catalogue's invented ones, with this test's own facts.
 */
@RunWith(AndroidJUnit4::class)
class FakeTmdbTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val reached = CopyOnWriteArrayList<String>()

    private val realShow = TmdbLookup.showAged
    private val realSeason = TmdbLookup.seasonAged
    private val realMovie = TmdbLookup.movieAged
    private val realSearch = TmdbLookup.searchTitles
    private val realValidate = TmdbLookup.validateKey
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realSegments = PlayerActivity.lookUpSegments

    private val standInShow: suspend (AppContainer, Int) -> Aged<ShowDetails> = { _, id -> reach("show $id") }
    private val standInSeason: suspend (AppContainer, Int, Int) -> Aged<Season> = { _, id, n -> reach("season $n of $id") }
    private val standInMovie: suspend (AppContainer, Int) -> Aged<MovieDetails> = { _, id -> reach("movie $id") }
    private val standInSearch: suspend (AppContainer, String) -> List<TitleSuggestion> = { _, q -> reach("search $q") }
    private val standInValidate: suspend (AppContainer, String) -> KeyCheck = { _, _ -> reach("a key") }
    private val standInNext: suspend (AppContainer, AutoAdvance.Episode) -> io.skjaere.usenet.app.play.EpisodePlay? = { _, e -> reach("next after $e") }
    private val standInPrevious: suspend (AppContainer, AutoAdvance.Episode) -> io.skjaere.usenet.app.play.EpisodePlay? = { _, e -> reach("previous before $e") }
    private val standInSegments: suspend (AppContainer, AutoAdvance.Episode, Long) -> List<io.skjaere.usenet.metadata.Segment> = { _, e, _ -> reach("segments of $e") }

    private fun reach(what: String): Nothing {
        reached += what
        throw IllegalStateException("the real lookup was asked for $what")
    }

    @Before
    fun standIns() {
        TmdbLookup.showAged = standInShow
        TmdbLookup.seasonAged = standInSeason
        TmdbLookup.movieAged = standInMovie
        TmdbLookup.searchTitles = standInSearch
        TmdbLookup.validateKey = standInValidate
        PlayerActivity.nextEpisode = standInNext
        PlayerActivity.previousEpisode = standInPrevious
        PlayerActivity.lookUpSegments = standInSegments
    }

    @After
    fun putBack() {
        TmdbLookup.showAged = realShow
        TmdbLookup.seasonAged = realSeason
        TmdbLookup.movieAged = realMovie
        TmdbLookup.searchTitles = realSearch
        TmdbLookup.validateKey = realValidate
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realSegments
    }

    private fun strict() = FakeTmdb(
        shows = mapOf(SHOW to show()),
        movies = mapOf(MOVIE to movie()),
        seasons = { id, n -> season(n).takeIf { id == SHOW && n == 1 } },
        suggestions = { q -> listOf(TitleSuggestion(MediaType.Tv, SHOW, "Quillon Station", null, 2024, null, null)).takeIf { q == "quillon" } },
        keys = FakeTmdb.inventedKey,
        strict = true,
    )

    @Test
    fun strictAnswersWhatItKnows() = runBlocking {
        val fake = strict()
        fake.use {
            assertEquals("Quillon Station", TmdbLookup.show(container, SHOW).name)
            assertEquals(3, TmdbLookup.season(container, SHOW, 1).episodes.size)
            assertEquals("Cindermoor", TmdbLookup.movie(container, MOVIE).title)
            assertEquals(listOf(SHOW), TmdbLookup.searchTitles(container, "quillon").map { it.tmdbId })
            assertEquals(KeyCheck.Valid, TmdbLookup.validate(container, FakeTmdb.INVENTED_KEY))
        }
        assertEquals(5, fake.lookups.get())
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun strictThrowsForWhatItDoesNotKnowAndNeverAsksTheRealLookups() = runBlocking {
        val fake = strict()
        assertThrows(FakeTmdb.Unknown::class.java) { runBlocking { TmdbLookup.show(container, OTHER_SHOW) } }
        assertThrows(FakeTmdb.Unknown::class.java) { runBlocking { TmdbLookup.season(container, SHOW, 2) } }
        assertThrows(FakeTmdb.Unknown::class.java) { runBlocking { TmdbLookup.movie(container, OTHER_MOVIE) } }
        assertThrows(FakeTmdb.Unknown::class.java) { runBlocking { TmdbLookup.searchTitles(container, "copperfen") } }
        assertThrows(FakeTmdb.Unknown::class.java) { runBlocking { TmdbLookup.validate(container, "0000another0000invented0key00000") } }
        assertEquals("none passed on", emptyList<String>(), reached)
        assertEquals(5, fake.misses.size)
        val failure = assertThrows(AssertionError::class.java) { fake.close() }
        assertTrue(failure.message!!, failure.message!!.contains("show $OTHER_SHOW") && failure.message!!.contains("season 2 of show $SHOW"))
        assertTrue("the key itself isn't said", !failure.message!!.contains("another"))
        assertSame("the real lookups are back all the same", standInShow, TmdbLookup.showAged)
        assertSame(standInValidate, TmdbLookup.validateKey)
    }

    @Test
    fun strictAnswersThePlayersNextAndPreviousEpisodeFromItsShowsAndLooksUpNoSegments() = runBlocking {
        strict().use {
            val next = PlayerActivity.nextEpisode(container, AutoAdvance.Episode(SHOW, 1, 1))
            assertEquals(listOf(1, 2, "Signal Fault at Brackenwick"), listOf(next?.season, next?.episode, next?.episodeName))
            val previous = PlayerActivity.previousEpisode(container, AutoAdvance.Episode(SHOW, 1, 2))
            assertEquals(listOf(1, 1, "The Quillon Timetable"), listOf(previous?.season, previous?.episode, previous?.episodeName))
            assertNull("the first has none before it", PlayerActivity.previousEpisode(container, AutoAdvance.Episode(SHOW, 1, 1)))
            assertNull("the third hasn't aired", PlayerActivity.nextEpisode(container, AutoAdvance.Episode(SHOW, 1, 2)))
            assertEquals(emptyList<Any>(), PlayerActivity.lookUpSegments(container, AutoAdvance.Episode(SHOW, 1, 1), 300_000L))
        }
        assertEquals(emptyList<String>(), reached)
        assertSame("the player's seams are put back", standInNext, PlayerActivity.nextEpisode)
        assertSame(standInPrevious, PlayerActivity.previousEpisode)
        assertSame(standInSegments, PlayerActivity.lookUpSegments)
    }

    @Test
    fun aMissThroughThePlayersSeamFailsTheCloseThoughThePlayerOnlySeesNoEpisode() = runBlocking {
        val fake = strict()
        assertNull(PlayerActivity.nextEpisode(container, AutoAdvance.Episode(OTHER_SHOW, 1, 1)))
        assertEquals(emptyList<String>(), reached)
        val failure = assertThrows(AssertionError::class.java) { fake.close() }
        assertTrue(failure.message!!, failure.message!!.contains("show $OTHER_SHOW"))
    }

    /** Without strict, as every other device test has it: what it doesn't know goes on to the real lookups. */
    @Test
    fun withoutStrictWhatItDoesNotKnowStillGoesOn() = runBlocking {
        FakeTmdb(shows = mapOf(SHOW to show())).use { fake ->
            assertEquals("Quillon Station", TmdbLookup.show(container, SHOW).name)
            assertThrows(IllegalStateException::class.java) { runBlocking { TmdbLookup.show(container, OTHER_SHOW) } }
            assertEquals(listOf("show $OTHER_SHOW"), reached.toList())
            assertSame("the player's seams are left alone", standInNext, PlayerActivity.nextEpisode)
            assertEquals(emptyList<String>(), fake.misses)
        }
    }

    private companion object {
        // Ids past TMDB's ranges, as the screens catalogue has them.
        const val SHOW = 990_002
        const val OTHER_SHOW = 990_009
        const val MOVIE = 9_900_104
        const val OTHER_MOVIE = 9_900_103
        val today: LocalDate = LocalDate.now()

        fun show() = ShowDetails(
            tmdbId = SHOW, name = "Quillon Station", originalName = null, year = 2024, overview = null, posterPath = null,
            backdropPath = null, status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 40,
            seasons = listOf(SeasonSummary(1, "Season 1", 3, today.minusDays(14), null)), lastEpisode = null, nextEpisode = null,
            ids = ExternalIds(tvdb = 9_900_002),
        )

        fun season(number: Int) = Season(
            SHOW, number, "Season $number", null,
            listOf(
                Episode(number, 1, "The Quillon Timetable", null, today.minusDays(14), 40, null),
                Episode(number, 2, "Signal Fault at Brackenwick", null, today.minusDays(7), 40, null),
                Episode(number, 3, "The Pennoch Spur", null, today.plusDays(7), 40, null),
            ),
        )

        fun movie() = MovieDetails(MOVIE, "Cindermoor", null, 2023, null, null, null, 95, ExternalIds())
    }
}

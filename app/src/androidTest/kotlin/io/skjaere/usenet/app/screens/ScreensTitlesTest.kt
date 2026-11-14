package io.skjaere.usenet.app.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.ScaleImages
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The README screens catalogue as the device tests read it ([ScreensTitles]): the strict FakeTmdb built from it knows
 * every title and season it names, and nothing reaches the real lookups (stand-ins here that only record they were
 * asked); the player's next and previous episode come from it; a search sends the ids mock-seed's `indexer --catalog
 * screens` lists the releases under (its JVM test reads the same file); and every picture it names loads from the
 * assets tools/screens/art.py drew.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTitlesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    // Today, as the player counts what has aired from today too.
    private val today = LocalDate.now()
    private val titles = ScreensTitles(today)
    private val reached = CopyOnWriteArrayList<String>()

    private val realShow = TmdbLookup.showAged
    private val realSeason = TmdbLookup.seasonAged
    private val realMovie = TmdbLookup.movieAged
    private val realSearch = TmdbLookup.searchTitles
    private val realValidate = TmdbLookup.validateKey

    @Before
    fun standIns() {
        TmdbLookup.showAged = { _: AppContainer, id: Int -> reach("show $id") }
        TmdbLookup.seasonAged = { _: AppContainer, id: Int, n: Int -> reach("season $n of $id") }
        TmdbLookup.movieAged = { _: AppContainer, id: Int -> reach("movie $id") }
        TmdbLookup.searchTitles = { _: AppContainer, q: String -> reach("search $q") }
        TmdbLookup.validateKey = { _: AppContainer, _: String -> reach("a key") }
    }

    @After
    fun putBack() {
        TmdbLookup.showAged = realShow
        TmdbLookup.seasonAged = realSeason
        TmdbLookup.movieAged = realMovie
        TmdbLookup.searchTitles = realSearch
        TmdbLookup.validateKey = realValidate
    }

    private fun reach(what: String): Nothing {
        reached += what
        throw IllegalStateException("the real lookup was asked for $what")
    }

    @Test
    fun theStrictFakeKnowsEveryTitleAndSeasonTheCatalogueNames() = runBlocking {
        assertEquals(14 to 4, titles.shows.size to titles.movies.size)
        titles.fakeTmdb().use {
            titles.shows.values.forEach { show ->
                assertEquals(show.name, TmdbLookup.show(container, show.tmdbId).name)
                show.seasons.forEach { s -> assertEquals(s.episodeCount, TmdbLookup.season(container, show.tmdbId, s.number).episodes.size) }
            }
            titles.movies.values.forEach { movie -> assertEquals(movie.title, TmdbLookup.movie(container, movie.tmdbId).title) }
            assertEquals(KeyCheck.Valid, TmdbLookup.validate(container, FakeTmdb.INVENTED_KEY))
            assertEquals(listOf("Kelpmoor"), TmdbLookup.searchTitles(container, "kelp").map { it.name })
            assertEquals(listOf(MediaType.Movie), TmdbLookup.searchTitles(container, "Ninth Bollard").map { it.type })
            assertEquals("a search no title matches finds none, from the catalogue", emptyList<TitleSuggestion>(), TmdbLookup.searchTitles(container, "zzz"))
        }
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun theCataloguesFactsAreTmdbsShapes() {
        val kelpmoor = titles.show("kelpmoor")
        assertEquals(listOf(1 to 6, 2 to 3), kelpmoor.seasons.map { it.number to it.episodeCount })
        assertEquals(today.minusDays(420).year, kelpmoor.year)
        assertEquals("/nzs/kelpmoor-poster.jpg" to "/nzs/kelpmoor-backdrop.jpg", kelpmoor.posterPath to kelpmoor.backdropPath)
        assertEquals(listOf(2, 3, "The Tarry Knot"), kelpmoor.lastEpisode.let { listOf(it?.season, it?.episode, it?.name) })
        assertEquals(null, kelpmoor.nextEpisode)
        val e4 = titles.season(kelpmoor.tmdbId, 1).episodes[3]
        assertEquals(listOf("A Ledger of Tides", today.minusDays(399), "/nzs/kelpmoor-s01e04.jpg", 48), listOf(e4.name, e4.airDate, e4.stillPath, e4.runtimeMinutes))
        val glimmerholt = titles.show("glimmerholt")
        assertEquals("a title without named episodes: no stills, no backdrop", listOf(null, null), listOf(titles.season(glimmerholt.tmdbId, 1).episodes.first().stillPath, glimmerholt.backdropPath))
        assertEquals(today.minusDays(800 - 7), titles.season(glimmerholt.tmdbId, 1).episodes[1].airDate)
        val bollard = titles.movie("the-ninth-bollard")
        assertEquals(listOf(2022, LocalDate.of(2022, 3, 18), 104), listOf(bollard.year, bollard.releaseDate, bollard.runtimeMinutes))
    }

    /** What the app searches indexers by: the ids mock-seed's screens catalogue lists the releases under. */
    @Test
    fun aSearchSendsTheIdsTheScreensIndexerListsReleasesUnder() {
        val kelpmoor = titles.show("kelpmoor")
        val play = EpisodePlay.of(kelpmoor, titles.season(kelpmoor.tmdbId, 1).episodes[4], today)
        assertEquals(TvIds(tvdb = 9_900_001, tmdb = 990_001, imdb = "tt99000001"), play.ids)
        assertEquals(listOf("Kelpmoor", 1, 5, "Brinefall", true), listOf(play.showName, play.season, play.episode, play.episodeName, play.aired))
        assertEquals("tt99000101", titles.movie("the-ninth-bollard").ids.imdb)
    }

    @Test
    fun thePlayersNextAndPreviousEpisodeComeFromTheCatalogue() = runBlocking {
        val kelpmoor = titles.show("kelpmoor").tmdbId
        titles.fakeTmdb().use {
            val next = PlayerActivity.nextEpisode(container, AutoAdvance.Episode(kelpmoor, 1, 4))
            assertEquals(listOf(1, 5, "Brinefall"), listOf(next?.season, next?.episode, next?.episodeName))
            val nextSeason = PlayerActivity.nextEpisode(container, AutoAdvance.Episode(kelpmoor, 1, 6))
            assertEquals(listOf(2, 1, "Kelpmoor Rising"), listOf(nextSeason?.season, nextSeason?.episode, nextSeason?.episodeName))
            val previous = PlayerActivity.previousEpisode(container, AutoAdvance.Episode(kelpmoor, 2, 1))
            assertEquals(listOf(1, 6, "The Lamp on the Breakwater"), listOf(previous?.season, previous?.episode, previous?.episodeName))
            assertEquals(emptyList<Any>(), PlayerActivity.lookUpSegments(container, AutoAdvance.Episode(kelpmoor, 1, 5), 339_000L))
        }
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun everyPictureTheCatalogueNamesLoadsFromTheAssets() {
        assertEquals(42, titles.imagePaths.size)
        val images = ScaleImages.screens(context).also { it.install() }
        try {
            titles.imagePaths.forEach { path ->
                val url = "https://image.tmdb.org/t/p/w342$path"
                val result = runBlocking {
                    images.loader.execute(
                        ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
                            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                    )
                }
                assertTrue(path, result is SuccessResult)
            }
        } finally {
            images.restore()
        }
        assertEquals(42, images.fetched.get())
    }
}

package io.skjaere.usenet.app

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Search over invented titles, two shows and two movies that TMDB doesn't know, answered by
 * [FakeTmdb] (suggestions for anything typed come from them alone, so nothing typed reaches TMDB).
 * The indexers are switched off, so Search on a picked title shows its card and nothing else is asked; the
 * collection's filters are set to ones a new title isn't in. [close] puts back the settings, the TMDB
 * key, what the tests collected and the searches they made.
 */
class JumpFixture : AutoCloseable {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private var keyAdded = false
    private var showsBefore: Set<Int> = emptySet()
    private var moviesBefore: Set<Int> = emptySet()
    lateinit var tmdb: FakeTmdb
        private set

    val glasswater = ShowDetails(
        GLASSWATER, "Glasswater Bay", null, 2024, "A harbour pilot keeps a ledger of every ship.", null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 8, LocalDate.of(2024, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 8, "Episode 8", LocalDate.of(2024, 4, 19)), nextEpisode = null, ids = ExternalIds(),
    )
    val orbit = ShowDetails(
        ORBIT, "The Quiet Orbit", null, 2025, null, null, null, "Ended", false, 40,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2025, 1, 6), null)),
        lastEpisode = EpisodeSummary(1, 6, "Episode 6", LocalDate.of(2025, 2, 10)), nextEpisode = null, ids = ExternalIds(),
    )
    val lantern = MovieDetails(
        LANTERN, "Lantern Field", null, 2023, "A night watchman counts the lights.", null, null, 101, ExternalIds(),
        releaseDate = LocalDate.of(2023, 6, 2), status = "Released",
    )
    val valley = MovieDetails(VALLEY, "Copper Valley", null, 2022, null, null, null, 95, ExternalIds(), releaseDate = LocalDate.of(2022, 9, 9), status = "Released")

    private val suggestions = listOf(
        TitleSuggestion(MediaType.Tv, GLASSWATER, glasswater.name, null, glasswater.year, null, glasswater.overview),
        TitleSuggestion(MediaType.Tv, ORBIT, orbit.name, null, orbit.year, null, null),
        TitleSuggestion(MediaType.Movie, LANTERN, lantern.title, null, lantern.year, null, lantern.overview),
        TitleSuggestion(MediaType.Movie, VALLEY, valley.title, null, valley.year, null, null),
    )

    fun open() = runBlocking {
        home.show()
        saved = settings.settings.first()
        // Suggestions need a key; the fake answers in TMDB's place.
        if (settings.tmdbKey() == null) {
            settings.setTmdbKey("jump-device-test")
            keyAdded = true
        }
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.updateCollectionView { it.copy(side = "Shows", showFilter = "Watching", movieFilter = "Watched") }
        showsBefore = library.collectedShowIds.first()
        moviesBefore = library.collectedMovieIds.first()
        tmdb = FakeTmdb(
            shows = mapOf(GLASSWATER to glasswater, ORBIT to orbit),
            movies = mapOf(LANTERN to lantern, VALLEY to valley),
            suggestions = { query -> suggestions.filter { it.name.contains(query, ignoreCase = true) } },
        )
    }

    override fun close() = runBlocking {
        if (::tmdb.isInitialized) tmdb.close()
        if (::saved.isInitialized) {
            settings.restore(saved)
            if (keyAdded) settings.setTmdbKey(null)
            library.removeTitles(shows = listOf(GLASSWATER, ORBIT) - showsBefore, movies = listOf(LANTERN, VALLEY) - moviesBefore)
            // Search on a picked title is remembered as a search of its name; a title opened from the Titles as a title, by its id.
            listOf(glasswater.name, orbit.name, lantern.title, valley.title).forEach { library.removeSearch(it.lowercase()) }
            listOf(TitleType.Tv to GLASSWATER, TitleType.Tv to ORBIT, TitleType.Movie to LANTERN, TitleType.Movie to VALLEY)
                .forEach { (type, id) -> library.removeSearch(RecentSearch.titleKey(SearchPick(type, id, ""))) }
        }
        home.restore()
    }

    companion object {
        // Invented titles; nothing real has these ids.
        const val GLASSWATER = 900071
        const val ORBIT = 900072
        const val LANTERN = 900073
        const val VALLEY = 900074
    }
}

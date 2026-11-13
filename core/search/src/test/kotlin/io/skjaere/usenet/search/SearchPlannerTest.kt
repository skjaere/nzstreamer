package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TvIds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SearchPlannerTest {
    private val harborLights = PickedTitle("Harbor Lights", 2021, isTv = true, TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"))
    private val lanternkeepers = PickedTitle("The Lanternkeepers", 1995, isTv = true, TvIds(tvdb = 900101, tmdb = 900102))
    private val copperCrown = PickedTitle("Copper Crown", 2019, isTv = false, TvIds(tmdb = 900010, imdb = "tt9000011"))

    private val byTvdb = IndexerCaps(searching = mapOf(SearchType.Search to setOf("q"), SearchType.Tv to setOf("q", "tvdbid", "season", "ep")))
    private val byName = IndexerCaps(searching = mapOf(SearchType.Search to setOf("q"), SearchType.Tv to setOf("q", "season", "ep")))
    private val textOnly = IndexerCaps(searching = mapOf(SearchType.Search to setOf("q")))
    private val movies = IndexerCaps(searching = mapOf(SearchType.Search to setOf("q"), SearchType.Movie to setOf("q", "imdbid")))

    /** What picking [title] with [typed] in the field does: the field's (and recent search's) new text, and what [caps] is asked. */
    private fun pick(typed: String, title: PickedTitle, caps: IndexerCaps): Pair<String, SearchQuery?> {
        val text = SearchPlanner.pickedText(typed, title.name, title.isTv)
        return text to SearchPlanner.plan(caps, QueryParser.parse(text), title)
    }

    @Test
    fun `picking a show keeps the typed episode`() {
        assertEquals(
            "Harbor Lights S02E05" to SearchQuery.Tv(ids = TvIds(tvdb = 900001), season = 2, episode = 5),
            pick("harbor lights s02e05", harborLights, byTvdb),
        )
        assertEquals(
            "The Lanternkeepers S02E05" to SearchQuery.Tv(ids = TvIds(tvdb = 900101), season = 2, episode = 5),
            pick("lanternkeepers 2x05", lanternkeepers, byTvdb),
        )
        assertEquals(
            "Harbor Lights S02E05" to SearchQuery.Tv(query = "Harbor Lights", season = 2, episode = 5),
            pick("harbor lights s02e05", harborLights, byName),
        )
    }

    @Test
    fun `picking a show keeps a typed season`() {
        assertEquals(
            "Harbor Lights S03" to SearchQuery.Tv(ids = TvIds(tvdb = 900001), season = 3),
            pick("harbor lights s03", harborLights, byTvdb),
        )
        assertEquals("The Lanternkeepers S03" to SearchQuery.Tv(ids = TvIds(tvdb = 900101), season = 3), pick("lanternkeepers season 3", lanternkeepers, byTvdb))
    }

    @Test
    fun `a show on an indexer without tv-search is searched by name with the episode`() {
        assertEquals(SearchQuery.Text("Harbor Lights S02E05"), SearchPlanner.plan(textOnly, QueryParser.parse("Harbor Lights 2x05"), harborLights))
        assertEquals("Harbor Lights S02E05" to SearchQuery.Text("Harbor Lights S02E05"), pick("harbor lights s02e05", harborLights, textOnly))
        assertEquals("Harbor Lights S02" to SearchQuery.Text("Harbor Lights S02"), pick("harbor lights s02", harborLights, textOnly))
        assertEquals("Harbor Lights" to SearchQuery.Text("Harbor Lights"), pick("harbor", harborLights, textOnly))
    }

    @Test
    fun `picking a show without a typed episode searches the whole show`() {
        assertEquals("The Lanternkeepers" to SearchQuery.Tv(ids = TvIds(tvdb = 900101)), pick("lanternkeepers", lanternkeepers, byTvdb))
    }

    @Test
    fun `a pasted release name is searched as it is`() {
        listOf(
            "Harbor.Lights.S02E05.1080p.WEB.h264-GRP",
            "Harbor.Lights.S02.1080p.WEB.h264-GRP",
            "harbor_lights_s02e05_720p",
            "Harbor Lights S02E05 1080p WEB h264-GRP",
        ).forEach { name -> assertEquals(SearchQuery.Text(name), SearchPlanner.plan(byName, QueryParser.parse(name), null), name) }
        val movie = "Copper.Crown.2019.1080p.BluRay.x264-GRP"
        assertEquals(SearchQuery.Text(movie), SearchPlanner.plan(movies, QueryParser.parse(movie), null))
    }

    @Test
    fun `typed words with an episode are searched by name with the season and episode`() {
        assertEquals(SearchQuery.Tv(query = "show", season = 1, episode = 2), SearchPlanner.plan(byName, QueryParser.parse("show s01e02"), null))
        assertEquals(SearchQuery.Tv(query = "K.E.L.P.", season = 1, episode = 2), SearchPlanner.plan(byName, QueryParser.parse("K.E.L.P. s01e02"), null))
        assertEquals(SearchQuery.Tv(query = "Tide-Walker", season = 1), SearchPlanner.plan(byName, QueryParser.parse("Tide-Walker season 1"), null))
    }

    @Test
    fun `picking a movie ignores a typed season and episode`() {
        assertEquals("Copper Crown" to SearchQuery.Movie(imdb = "tt9000011"), pick("copper crown s01e02", copperCrown, movies))
        assertEquals("Copper Crown" to SearchQuery.Text("Copper Crown 2019"), pick("copper crown 2019", copperCrown, textOnly))
    }
}

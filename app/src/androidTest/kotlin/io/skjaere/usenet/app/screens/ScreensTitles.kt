package io.skjaere.usenet.app.screens

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.ScaleImages
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The README screenshots' titles as TMDB would give them, read from the screens catalogue (the androidTest asset
 * screens/catalog.json): the file mock-seed's `indexer --catalog screens` lists releases under by the same ids, and
 * tools/screens/art.py draws the pictures for. Air dates are counted from [today]; an episode without a name of its own
 * comes a week after the one before. Pictures have the paths [ScaleImages.screens] answers ("/nzs/<file>.jpg"):
 * every title a poster, those marked "backdrop" a backdrop, and every named episode a still. [fakeTmdb] answers TMDB's
 * lookups for these titles alone, strictly, so a lookup of anything else fails the run.
 */
class ScreensTitles(val today: LocalDate = LocalDate.now()) {
    private val catalog = JSONObject(
        InstrumentationRegistry.getInstrumentation().context.assets.open(CATALOG).use { it.readBytes().decodeToString() },
    )
    private val slugs = mutableMapOf<String, Int>()
    private val seasons = mutableMapOf<Pair<Int, Int>, Season>()
    private val paths = mutableSetOf<String>()

    val shows: Map<Int, ShowDetails> = catalog.getJSONArray("shows").objects().associate { show -> show.tmdbId() to show(show) }
    val movies: Map<Int, MovieDetails> = catalog.getJSONArray("movies").objects().associate { movie -> movie.tmdbId() to movie(movie) }

    /** Every picture the titles name: what the screenshots' image loader must answer. */
    val imagePaths: Set<String> get() = paths

    fun show(slug: String): ShowDetails = shows.getValue(slugs.getValue(slug))
    fun movie(slug: String): MovieDetails = movies.getValue(slugs.getValue(slug))
    fun season(show: Int, number: Int): Season = seasons.getValue(show to number)

    /** What TMDB's suggestions would be: the titles whose name holds the query, ignoring case; none for a blank one. */
    fun suggestions(query: String): List<TitleSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return shows.values.filter { it.name.contains(q, ignoreCase = true) }.map { TitleSuggestion(MediaType.Tv, it.tmdbId, it.name, it.originalName, it.year, it.posterPath, it.overview) } +
            movies.values.filter { it.title.contains(q, ignoreCase = true) }.map { TitleSuggestion(MediaType.Movie, it.tmdbId, it.title, it.originalTitle, it.year, it.posterPath, it.overview) }
    }

    /** TMDB for the screenshots: these titles, their seasons and suggestions, and the invented key, nothing else. */
    fun fakeTmdb(): FakeTmdb = FakeTmdb(
        shows = shows,
        movies = movies,
        seasons = { show, number -> seasons[show to number] },
        suggestions = ::suggestions,
        keys = FakeTmdb.inventedKey,
        strict = true,
    )

    private fun show(json: JSONObject): ShowDetails {
        val slug = json.getString("slug")
        val tmdb = json.tmdbId()
        slugs[slug] = tmdb
        val runtime = json.getInt("runtime")
        val made = json.getJSONArray("seasons").objects().map { season ->
            val number = season.getInt("number")
            val named = season.optJSONArray("episodes")
            val episodes = if (named != null) {
                named.objects().mapIndexed { i, e ->
                    val still = image("$slug-s%02de%02d".format(number, i + 1))
                    Episode(number, i + 1, e.getString("name"), e.getString("overview"), today.plusDays(e.getLong("aired")), runtime, still)
                }
            } else {
                val first = today.plusDays(season.getLong("aired"))
                (1..season.getInt("episodes")).map { Episode(number, it, null, null, first.plusWeeks(it - 1L), runtime, null) }
            }
            Season(tmdb, number, "Season $number", null, episodes).also { seasons[tmdb to number] = it }
        }
        val all = made.flatMap { it.episodes }
        val aired = all.filter { it.hasAired(today) }
        val status = json.getString("status")
        val ids = json.getJSONObject("ids")
        return ShowDetails(
            tmdbId = tmdb, name = json.getString("name"), originalName = json.getString("name"), year = all.first().airDate?.year,
            overview = json.getString("overview"), posterPath = image("$slug-poster"),
            backdropPath = if (json.optBoolean("backdrop")) image("$slug-backdrop") else null,
            status = status, inProduction = status == "Returning Series", episodeRuntimeMinutes = runtime,
            seasons = made.map { s -> SeasonSummary(s.number, s.name, s.episodes.size, s.episodes.first().airDate, null) },
            lastEpisode = aired.lastOrNull()?.let { EpisodeSummary(it.season, it.episode, it.name, it.airDate) },
            nextEpisode = all.firstOrNull { !it.hasAired(today) }?.let { EpisodeSummary(it.season, it.episode, it.name, it.airDate) },
            ids = ExternalIds(tvdb = ids.getInt("tvdb"), imdb = ids.getString("imdb"), tvmaze = ids.getInt("tvmaze")),
            originalLanguage = "en",
        )
    }

    private fun movie(json: JSONObject): MovieDetails {
        val slug = json.getString("slug")
        val tmdb = json.tmdbId()
        slugs[slug] = tmdb
        return MovieDetails(
            tmdbId = tmdb, title = json.getString("title"), originalTitle = json.getString("title"), year = json.getInt("year"),
            overview = json.getString("overview"), posterPath = image("$slug-poster"),
            backdropPath = if (json.optBoolean("backdrop")) image("$slug-backdrop") else null,
            runtimeMinutes = json.getInt("runtime"), ids = ExternalIds(imdb = json.getJSONObject("ids").getString("imdb")),
            releaseDate = LocalDate.parse(json.getString("released")), status = "Released", originalLanguage = "en",
        )
    }

    private fun image(name: String) = "${ScaleImages.SCREENS_PATH}$name.jpg".also { paths += it }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.tmdbId(): Int = getJSONObject("ids").getInt("tmdb")

    private companion object {
        const val CATALOG = "screens/catalog.json"
    }
}

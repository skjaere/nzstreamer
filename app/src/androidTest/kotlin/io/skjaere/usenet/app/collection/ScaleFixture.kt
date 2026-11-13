package io.skjaere.usenet.app.collection

import androidx.room.withTransaction
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.app.refresh.TitleSource
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LibraryDatabase
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.util.Collections

/**
 * A collection at the scale slice 12 aims for, the same on every run: [shows] shows and [movies]
 * movies with invented names (a made-up word for every letter, accented ones, digits under '#', one
 * with a leading "The", each with its number: "Avrel 0017", "Ébrin 0007", "7 Orbel 0100"); no real
 * titles. Ids from 910,000,000. TMDB's facts are stored fresh at [now] except for the titles
 * [factless] picks (about 50), which wait for the refresher.
 *
 * Shows: a quarter ended, the rest returning, most with an episode tomorrow or in three weeks, some
 * airing [today], 40 with an episode out three days ago; 300 started (5 to 40 episodes watched),
 * 50 of them with an episode in progress. Movies: 600 watched, 100 in progress, 80 not out yet. With [posters]
 * each title has a poster's path of its own ([showPoster], [moviePoster]), which no real TMDB image has.
 */
class ScaleFixture(val today: LocalDate, val now: Long, val shows: Int = 1_000, val movies: Int = 2_000, val posters: Boolean = false) {
    fun showId(i: Int) = SHOWS + i
    fun movieId(j: Int) = MOVIES + j

    fun showPoster(i: Int) = "/scale/s%04d.jpg".format(i)
    fun moviePoster(j: Int) = "/scale/m%04d.jpg".format(j)

    fun showName(i: Int) = "${WORDS[i % WORDS.size]} %04d".format(i)
    fun movieName(j: Int) = "${WORDS[(j + 11) % WORDS.size]} %04d".format(j)

    /** No facts stored: 25 shows and 25 movies, a few of them under '#' (first in A–Z). */
    fun factless(key: TitleKey): Boolean {
        val n = if (key.isShow) key.tmdbId - SHOWS else key.tmdbId - MOVIES
        return if (key.isShow) n % 40 == 28 else n % 80 == 17
    }

    fun started(i: Int) = i % 10 < 3
    fun resumable(i: Int) = i % 20 == 0
    private fun newEpisode(i: Int) = i % 25 == 1
    private fun airsToday(i: Int) = i % 50 == 7

    fun showDetails(i: Int): ShowDetails {
        val counts = List(1 + i % 4) { 8 + (i + it) % 5 }
        val ended = i % 4 == 0 && !newEpisode(i) && !airsToday(i)
        val lastSeason = counts.size
        val (last, next) = when {
            ended -> EpisodeSummary(lastSeason, counts.last(), null, today.minusDays(400L + i)) to null
            newEpisode(i) -> EpisodeSummary(lastSeason, counts.last() - 2, null, today.minusDays(3)) to
                EpisodeSummary(lastSeason, counts.last() - 1, null, today.plusDays(4))
            airsToday(i) -> EpisodeSummary(lastSeason, counts.last() - 3, null, today.minusDays(7)) to
                EpisodeSummary(lastSeason, counts.last() - 2, null, today)
            else -> EpisodeSummary(lastSeason, counts.last() - 4, null, today.minusDays(30L + i % 60)) to
                EpisodeSummary(lastSeason, counts.last() - 3, null, today.plusDays(if (i % 2 == 0) 1L else 21L))
        }
        return ShowDetails(
            showId(i), showName(i), null, 2000 + i % 25, null, null, null, if (ended) "Ended" else "Returning Series", !ended, 45,
            counts.mapIndexed { s, n -> SeasonSummary(s + 1, null, n, null, null) }, last, next, ExternalIds(),
        )
    }

    fun movieDetails(j: Int): MovieDetails {
        val upcoming = j % 25 == 0
        return MovieDetails(
            movieId(j), movieName(j), null, 1980 + j % 45, null, null, null, 80 + j % 70, ExternalIds(),
            releaseDate = if (upcoming) today.plusDays(10L + j % 70) else today.minusDays(100L + j),
            status = if (upcoming) "Post Production" else "Released",
        )
    }

    private fun movieWatched(j: Int) = j % 10 in 1..3
    private fun movieInProgress(j: Int) = j % 20 == 4

    /** The watch states: [started] shows' first episodes in order, the next one in progress when [resumable]; movies watched or in progress. */
    private fun watchStates(): List<WatchState> {
        val episodes = (0 until shows).filter(::started).flatMap { i ->
            val d = showDetails(i)
            val aired = d.seasons.flatMap { s -> (1..s.episodeCount).map { s.number to it } }
                .takeWhile { (s, e) -> s < d.lastEpisode!!.season || e <= d.lastEpisode!!.episode }
            val watched = minOf(5 + (i * 7) % 36, aired.size - if (resumable(i)) 1 else 0)
            val at = now - i * 60_000L
            aired.take(watched).mapIndexed { n, (s, e) -> episode(i, s, e, watched = true, at = at - (watched - n) * 1_000) } +
                listOfNotNull(aired.getOrNull(watched)?.takeIf { resumable(i) }?.let { (s, e) -> episode(i, s, e, watched = false, at = at) })
        }
        val films = (0 until movies).filter { movieWatched(it) || movieInProgress(it) }.map { j ->
            val duration = (80 + j % 70) * 60_000L
            WatchState(
                WatchKey.movie(movieId(j)), WatchKind.Movie, movieTmdbId = movieId(j), title = movieName(j),
                positionMs = if (movieInProgress(j)) duration * 2 / 5 else 0, durationMs = duration, watched = movieWatched(j),
                lastPlayedAt = now - j * 60_000L, updatedAt = now,
            )
        }
        return episodes + films
    }

    private fun episode(i: Int, s: Int, e: Int, watched: Boolean, at: Long) = WatchState(
        WatchKey.episode(showId(i), s, e), WatchKind.Episode, showTmdbId = showId(i), season = s, episode = e, title = "${showName(i)} S%02dE%02d".format(s, e),
        positionMs = if (watched) 0 else 900_000, durationMs = 2_700_000, watched = watched, lastPlayedAt = at, updatedAt = at,
    )

    /** Everything above into [db], in one transaction through the DAOs. */
    suspend fun seed(db: LibraryDatabase) = db.withTransaction {
        val fresh = now + Duration.ofDays(1).toMillis()
        (0 until shows).forEach { i ->
            db.collection().upsert(
                CollectedShow(
                    CollectedShow.idFor(showId(i)), showId(i), name = showName(i), year = 2000 + i % 25, posterPath = showPoster(i).takeIf { posters },
                    addedAt = now - (shows - i) * 3_600_000L, updatedAt = now,
                ),
            )
        }
        (0 until movies).forEach { j ->
            db.movies().upsert(
                CollectedMovie(
                    CollectedMovie.idFor(movieId(j)), movieId(j), title = movieName(j), year = 1980 + j % 45, posterPath = moviePoster(j).takeIf { posters },
                    addedAt = now - (movies - j) * 3_600_000L, updatedAt = now,
                ),
            )
        }
        val showsWithFacts = (0 until shows).filterNot { factless(TitleKey.show(showId(it))) }
        val moviesWithFacts = (0 until movies).filterNot { factless(TitleKey.movie(movieId(it))) }
        db.summaries().upsertShows(showsWithFacts.map { showDetails(it).toSummary() })
        db.summaries().upsertMovies(moviesWithFacts.map { movieDetails(it).toSummary() })
        db.summaries().upsertRefresh(
            showsWithFacts.map { SummaryRefresh(SummaryRefresh.SHOW, showId(it), fetchedAt = now - 3_600_000, staleAt = fresh) } +
                moviesWithFacts.map { SummaryRefresh(SummaryRefresh.MOVIE, movieId(it), fetchedAt = now - 3_600_000, staleAt = fresh) },
        )
        db.watchStates().upsert(watchStates())
    }

    companion object {
        const val SHOWS = 910_000_000
        const val MOVIES = 911_000_000

        /** Made up, one for each letter, then accented, a number and a leading article. */
        val WORDS = listOf(
            "Avrel", "Bennor", "Corvel", "Dovan", "Elmar", "Fennick", "Garov", "Heskel", "Ilvar", "Jorun", "Kestov", "Lunel", "Mirel",
            "Norvo", "Ostral", "Pelin", "Quessa", "Revan", "Sorvin", "Tavel", "Ulmar", "Veskin", "Wyrrel", "Xalor", "Yevar", "Zorin",
            "Ébrin", "Ödrel", "7 Orbel", "The Kavel",
        )
    }
}

/** TMDB for the scale tests: the fixture's answers after [latencyMs], in the order asked ([calls]); nothing reaches the network. */
class ScaleTitleSource(private val fixture: ScaleFixture, private val latencyMs: Long = 20) : TitleSource {
    val calls: MutableList<TitleKey> = Collections.synchronizedList(mutableListOf())

    override suspend fun show(tmdbId: Int): Aged<ShowDetails> {
        calls += TitleKey.show(tmdbId)
        delay(latencyMs)
        val i = tmdbId - ScaleFixture.SHOWS
        if (i !in 0 until fixture.shows) throw TmdbException(TmdbError.NotFound("Not in the fixture"))
        return Aged(fixture.showDetails(i), Duration.ZERO)
    }

    override suspend fun movie(tmdbId: Int): Aged<MovieDetails> {
        calls += TitleKey.movie(tmdbId)
        delay(latencyMs)
        val j = tmdbId - ScaleFixture.MOVIES
        if (j !in 0 until fixture.movies) throw TmdbException(TmdbError.NotFound("Not in the fixture"))
        return Aged(fixture.movieDetails(j), Duration.ZERO)
    }
}

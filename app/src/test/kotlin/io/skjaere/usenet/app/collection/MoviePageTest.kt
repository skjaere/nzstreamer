package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.MovieState
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The movie page says what the collection's row says, from what is stored, and offers Play, Resume or Play again. */
class MoviePageTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val collected = CollectedMovie(CollectedMovie.idFor(900011), 900011, "tt9000012", "Copper Crown", 2019, "/cc.jpg", addedAt = 1, updatedAt = 1)
    private val stored = MovieSummary(900011, originalTitle = null, runtimeMinutes = 118, releaseDay = LocalDate.of(2019, 3, 1).toEpochDay(), status = "Released", imdbId = "tt9000012")
    private val details = MovieDetails(
        900011, "Copper Crown", null, 2019, "A retired forger is pulled into one last job.", "/cc.jpg", null, 121, ExternalIds(imdb = "tt9000012"),
        releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )

    private fun watch(positionMs: Long = 0, durationMs: Long = 7_080_000, watched: Boolean = false, deleted: Boolean = false) = WatchState(
        WatchKey.movie(900011), WatchKind.Movie, movieTmdbId = 900011, title = "Copper Crown",
        positionMs = positionMs, durationMs = durationMs, watched = watched, lastPlayedAt = 5, updatedAt = 5, deleted = deleted,
    )

    @Test
    fun `a collected movie's header comes from what is stored, before TMDB answers`() {
        val row = assertNotNull(movieHeader(collected, stored, details = null, watch(positionMs = 7_080_000 - 2_520_000), today))
        assertEquals("Copper Crown", row.text.name)
        assertEquals("2019 · 1 h 58 min", row.meta)
        assertEquals("42 min left", row.status)
        assertEquals(MovieState.InProgress, row.state)
        assertEquals("/cc.jpg", row.posterPath)
    }

    @Test
    fun `TMDB's answer, once it comes, wins over the stored facts`() {
        assertEquals("2019 · 2 h 1 min", movieHeader(collected, stored, details, watch = null, today)?.meta)
    }

    @Test
    fun `a movie opened from Search, not collected, is TMDB's answer`() {
        val row = assertNotNull(movieHeader(collected = null, stored = null, details, watch = null, today))
        assertEquals("Copper Crown", row.text.name)
        assertEquals("2019 · 2 h 1 min", row.meta)
        assertEquals("Not watched", row.status)
        assertNull(movieHeader(collected = null, stored = null, details = null, watch = null, today))
    }

    @Test
    fun `a movie removed from the collection is named by its removed row until TMDB answers, then by TMDB`() {
        val removed = collected.copy(deleted = true)
        // "In collection ✓" tapped while TMDB can't answer: the page, its toggle and Play stay.
        val row = assertNotNull(movieHeader(removed, stored, details = null, watch = null, today))
        assertEquals("Copper Crown", row.text.name)
        assertEquals("2019 · 1 h 58 min", row.meta)
        // Not collected any more: TMDB's answer, as for one opened from Search.
        assertEquals("/new.jpg", movieHeader(removed, stored, details.copy(posterPath = "/new.jpg"), watch = null, today)?.posterPath)
        assertEquals("/cc.jpg", movieHeader(collected, stored, details.copy(posterPath = "/new.jpg"), watch = null, today)?.posterPath)
    }

    @Test
    fun `Play and Choose release look for the IMDb id the lists have, before TMDB answers the page`() {
        // Added from a suggestion: collected without an IMDb id; the refresher stored TMDB's.
        val row = assertNotNull(movieHeader(collected.copy(imdbId = null), stored, details = null, watch = null, today))
        assertEquals("tt9000012", movieTarget(row, details = null).imdb)
        // TMDB's answer to the page, once in, has the last word.
        assertEquals("tt9000099", movieTarget(row, details.copy(ids = ExternalIds(imdb = "tt9000099"))).imdb)
    }

    @Test
    fun `Play and Choose release rank untagged releases in the movie's original language`() {
        val korean = details.copy(originalLanguage = "ko")
        assertEquals("ko", movieTarget(assertNotNull(movieHeader(collected, stored, korean, watch = null, today)), korean).originalLanguage)
        // Before TMDB answers the page: the language the lists' facts have, which keep TMDB's.
        assertEquals("ko", korean.toSummary().originalLanguage)
        val row = assertNotNull(movieHeader(collected, stored.copy(originalLanguage = "ko"), details = null, watch = null, today))
        assertEquals("ko", movieTarget(row, details = null).originalLanguage)
        assertNull(movieTarget(assertNotNull(movieHeader(collected, stored, details = null, watch = null, today)), details = null).originalLanguage)
    }

    @Test
    fun `a watch state no longer kept isn't progress`() {
        assertEquals(MovieState.NotWatched, movieHeader(collected, stored, null, watch(positionMs = 3_000_000, deleted = true), today)?.state)
    }

    @Test
    fun `the page offers Play, Resume or Play again by how far it was watched`() {
        fun text(watch: WatchState?, summary: MovieSummary? = stored) = moviePageText(movieHeader(collected, summary, null, watch, today)!!, today)
        assertEquals(MoviePageText("Not watched", "Play", null), text(null))
        assertEquals(MoviePageText("42 min left", "Resume", null), text(watch(positionMs = 7_080_000 - 2_520_000)))
        assertEquals(MoviePageText("✓ Watched", "Play again", null), text(watch(watched = true)))
        val upcoming = stored.copy(releaseDay = LocalDate.of(2027, 3, 12).toEpochDay(), status = "Post Production")
        assertEquals(MoviePageText("Out 12 Mar 2027", "Play", "TMDB lists it for 12 Mar 2027. Releases often appear around then."), text(null, upcoming))
        // Nothing known from TMDB and never played: not watched, whatever TMDB says later.
        assertEquals(MoviePageText("Not watched", "Play", null), text(null, summary = null))
    }

    private fun release(indexer: IndexerConfig, title: String, hash: String? = null) = NewznabItem(
        indexerId = indexer.id, guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 8_000_000_000, publishedAt = null,
        postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        infoHash = hash,
    )

    /**
     * W71: Choose release on the movie's page lists its releases once every indexer has answered, a torrent indexer at its
     * Checking, and its torrents join the list as their checks settle.
     */
    @Test
    fun `the page's list shows once every indexer has answered, and a checked torrent joins it after`() = runBlocking<Unit> {
        val usenet = IndexerConfig("a", "Indexer A", "http://a.invalid", "key-a")
        val prowlarr = IndexerConfig("p", "Home Prowlarr", "http://p.invalid", "key-p", kind = IndexerKind.Torznab)
        val byId = SearchQuery.Movie(imdb = "tt9000012")
        val nzb = release(usenet, "Copper.Crown.2019.1080p.BluRay.x264-GOOD")
        val torrent = release(prowlarr, "Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE", "81e639dbe53c58f9a63da37a37f29c66428ba3ab")
        val outcomes = flowOf(
            IndexerOutcome.Results(usenet, SearchPage(listOf(nzb), 0, null, null), byId),
            IndexerOutcome.Checking(prowlarr, byId, SearchPage(listOf(torrent), 0, null, null), waiting = 1),
            IndexerOutcome.Checked(prowlarr, listOf(torrent.copy(availability = Availability.Cached(listOf("torbox-1")))), waiting = 0),
        )
        val target = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118)
        val finds = MovieFind.found(target, outcomes, indexers = 2, RankingContext(StaticDeviceCapabilities())).toList()
        assertEquals(listOf(listOf(nzb.title), listOf(nzb.title, torrent.title)), finds.map { f -> f.playable.map { it.item.title }.sorted() })
        assertEquals(IndexerStatus.State.Checking(results = 1, waiting = 1), finds.first().statuses.last().state)
        assertEquals(IndexerStatus.State.Answered(1), finds.last().statuses.last().state)
    }

    /** A TV's Play button, as its mockup: Resume says where it goes on from, as the Show page's does. */
    @Test
    fun `on a TV Resume says where it goes on from, and the other labels are the phone's`() {
        fun label(watch: WatchState?): String {
            val row = movieHeader(collected, stored, null, watch, today)!!
            return tvMoviePlayLabel(row, moviePageText(row, today), watch)
        }
        assertEquals("Resume · 1:16:05", label(watch(positionMs = 4_565_000)))
        assertEquals("Resume · 42:00", label(watch(positionMs = 2_520_000)))
        assertEquals("Play", label(null))
        assertEquals("Play again", label(watch(watched = true)))
        // Under a minute in is no progress to resume.
        assertEquals("Play", label(watch(positionMs = 30_000)))
    }
}

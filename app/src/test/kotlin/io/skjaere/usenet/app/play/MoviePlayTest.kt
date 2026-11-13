package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Play on a movie's page: the release that played it before alone while its NZB is stored, else the search, as Search finds the movie. */
class MoviePlayTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val copperCrown = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118, LocalDate.of(2019, 3, 1), released = true)
    private val remembered = RememberedRelease(
        "a", "cc-2019-good", "Copper.Crown.2019.1080p.BluRay.x264-GOOD", null, 0, "nzb-cc",
        "Copper.Crown.2019.1080p.BluRay.x264-GOOD.mkv", 8_000_000_000,
    )
    private val watch = WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900011)

    private fun ranked(indexer: String, guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 8_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$guid", title, 8_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private val good = ranked("a", "cc-2019-good", "Copper.Crown.2019.1080p.BluRay.x264-GOOD")
    private val other = ranked("b", "cc-2019-web", "Copper.Crown.2019.1080p.WEB-DL.H.264-WEB")
    private val statuses = listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(1)), IndexerStatus("b", "Indexer B", IndexerStatus.State.Answered(1)))

    private fun found(vararg playable: RankedItem) = MovieFind(
        ResultGroup("known|movie:known", GroupKind.Movie, null, emptyList(), null, 2019, playable.toList(), emptyList()),
        statuses,
    )

    /** What one run asked for: the requests played (with their retries), the searches, the sheet's lines. */
    private class Run {
        val plays = mutableListOf<Pair<PlayRequest, Retry?>>()
        var finds = 0
        val lines = mutableListOf<String>()
    }

    private fun run(
        stored: Boolean,
        remembered: RememberedRelease? = this.remembered,
        target: MovieTarget = copperCrown,
        find: MovieFind = found(good, other),
        outcomes: List<PlayState.Failed?> = listOf(null),
    ): Pair<Run, PlayState?> {
        val r = Run()
        val left = outcomes.toMutableList()
        val end = runBlocking {
            MoviePlay.run(
                target, remembered, stored, today,
                play = { request, retry -> r.plays += request to retry; left.removeAt(0) },
                searching = { r.lines += it },
                find = { r.finds++; find },
            )
        }
        return r to end
    }

    private fun failed(what: String) = PlayState.Failed("Copper Crown", "2019", listOf(what to "Release incomplete"), notTried = 0)

    @Test
    fun `the release that played it, still stored, plays alone without a search`() {
        assertEquals(MoviePlay.Plan.RememberedAlone(remembered), MoviePlay.plan(remembered, stored = true))
        val (r, end) = run(stored = true)
        assertNull(end)
        assertEquals(0, r.finds)
        assertEquals(emptyList(), r.lines)
        val (request, retry) = r.plays.single()
        assertNull(retry)
        assertEquals(listOf("a/cc-2019-good"), request.candidates.map { it.key })
        assertEquals(remembered, request.remembered)
        assertEquals(watch, request.watch)
        assertEquals(Replay.file(remembered, FilePicker.Largest, watch), request.picker)
        assertEquals("Copper Crown" to "2019", request.title to request.subtitle)
    }

    @Test
    fun `without its NZB stored it searches, the remembered release first`() {
        assertEquals(MoviePlay.Plan.Search, MoviePlay.plan(remembered, stored = false))
        assertEquals(MoviePlay.Plan.Search, MoviePlay.plan(null, stored = false))
        val (r, end) = run(stored = false)
        assertNull(end)
        assertEquals(1, r.finds)
        assertEquals(listOf("Searching your indexers…"), r.lines)
        val (request, retry) = r.plays.single()
        assertNull(retry)
        assertEquals(listOf(good, other), request.candidates)
        assertTrue(request.rememberedFirst)
        assertEquals(FilePicker.Largest, request.picker)
        assertEquals(watch, request.watch)
        assertEquals("Searched 2 indexers · 2 found", request.searchLine)
    }

    @Test
    fun `when the remembered release fails, the search plays the others without it`() {
        val (r, end) = run(stored = true, outcomes = listOf(failed("Copper.Crown.2019.1080p.BluRay.x264-GOOD"), null))
        assertNull(end)
        assertEquals(1, r.finds)
        assertEquals(listOf("The release that played it failed · Searching your indexers…"), r.lines)
        val (request, retry) = r.plays[1]
        assertEquals(Retry(setOf("a/cc-2019-good"), startPositionMs = null, midStream = false), retry)
        assertEquals(listOf(other), request.candidates)
        assertEquals(false, request.rememberedFirst)
    }

    /**
     * The remembered release skipped without a grab as found incomplete before (its gap found while a provider was left
     * out, which kept it remembered): it wasn't tried, so the sheet doesn't say it failed while it searches.
     */
    @Test
    fun `when the remembered release is skipped as found incomplete before, the search says so, not that it failed`() {
        val skipped = PlayState.Failed(
            "Copper Crown", "2019", emptyList(), notTried = 0,
            missing = listOf("Copper.Crown.2019.1080p.BluRay.x264-GOOD" to "Skipped: missing on your providers (checked 3 days ago)"),
        )
        val (r, end) = run(stored = true, outcomes = listOf(skipped, null))
        assertNull(end)
        assertEquals(listOf("The release that played it was found incomplete before · Searching your indexers…"), r.lines)
        assertEquals(listOf(other), r.plays[1].first.candidates)
    }

    /** DEP-8: a remembered torrent skipped as its service failed it before is said so, never as found incomplete. */
    @Test
    fun `when the remembered torrent is skipped as its service failed it before, the search says so`() {
        val skipped = PlayState.Failed("Copper Crown", "2019", emptyList(), notTried = 0, missing = listOf("Copper.Crown.2019.1080p.WEB-DL.H.264-TIDE" to "No longer cached"))
        val (r, _) = run(stored = true, outcomes = listOf(skipped, null))
        assertEquals(listOf("The release that played it was skipped: no longer cached · Searching your indexers…"), r.lines)
        assertEquals(false, r.plays[1].second?.foundIncomplete)
    }

    @Test
    fun `nothing found ends on the no-release sheet, whose search is by the movie's id`() {
        val (r, end) = run(stored = false, remembered = null, find = MovieFind(null, statuses))
        assertEquals(emptyList(), r.plays)
        val none = assertIs<PlayState.NoRelease>(end)
        assertEquals("Copper Crown" to "2019", none.title to none.subtitle)
        assertEquals("Copper Crown 2019", none.nameQuery)
        assertEquals(SearchPick(TitleType.Movie, 900011, "Copper Crown"), none.searchPick)
        assertNull(none.note)
        assertEquals(listOf("Indexer A" to "1 result, none playable", "Indexer B" to "1 result, none playable"), none.stages)
        assertEquals(SearchRoute("Copper Crown 2019", "Movie", 900011, "Copper Crown"), searchRoute(none.nameQuery, none.searchPick))
    }

    @Test
    fun `a movie still to come says when TMDB lists it for`() {
        val upcoming = copperCrown.copy(releaseDate = LocalDate.of(2027, 3, 12), released = false)
        val (_, end) = run(stored = false, remembered = null, target = upcoming, find = MovieFind(null, statuses))
        assertEquals("TMDB lists it for 12 Mar 2027. Releases often appear around then.", assertIs<PlayState.NoRelease>(end).note)
    }

    @Test
    fun `when the remembered release fails and the search finds nothing else, its failure shows, with a search for the movie`() {
        val failure = failed("Copper.Crown.2019.1080p.BluRay.x264-GOOD")
        val (r, end) = run(stored = true, find = found(good), outcomes = listOf(failure))
        assertEquals(1, r.plays.size)
        assertEquals(failure.copy(search = "Copper Crown 2019", searchPick = SearchPick(TitleType.Movie, 900011, "Copper Crown")), end)
    }

    @Test
    fun `the note for a movie still to come gives TMDB's date, and none for one that is out`() {
        assertEquals("TMDB lists it for 12 Mar 2027. Releases often appear around then.", movieNoReleaseNote(LocalDate.of(2027, 3, 12), released = false, today))
        assertEquals("TMDB lists it for 20 Dec. Releases often appear around then.", movieNoReleaseNote(LocalDate.of(2026, 12, 20), released = false, today))
        assertEquals("TMDB has no release date for it yet.", movieNoReleaseNote(null, released = false, today))
        assertNull(movieNoReleaseNote(null, released = true, today))
        assertNull(movieNoReleaseNote(LocalDate.of(2019, 3, 1), released = true, today))
        // Out today: out.
        assertNull(movieNoReleaseNote(today, released = true, today))
    }
}

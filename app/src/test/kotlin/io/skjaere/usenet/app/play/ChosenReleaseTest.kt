package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.collection.chooseFileRequest
import io.skjaere.usenet.app.collection.chosenRequest
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.SearchRequest
import io.skjaere.usenet.app.search.alternativesTo
import io.skjaere.usenet.app.search.chosenRequest
import io.skjaere.usenet.app.search.playRequest
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * TR-12: a release the user picks by hand (Choose release on the Show and Movie pages, a release list's Play in Search) is
 * tried alone and whatever is known of it: its request says it was chosen ([PlayRequest.chosen]), which the coordinator hands
 * the play's lookups ([MissingReleases.forPlay]), so one found missing on every provider in use before is grabbed and tried
 * rather than skipped. Each place builds its request with these.
 */
class ChosenReleaseTest {
    private fun item(title: String) = NewznabItem(
        indexerId = "fake", guid = "g-" + title.lowercase(), title = title, sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))

    /** [title] ranked, as a search result's release. */
    private fun ranked(title: String): RankedItem = ResultGrouper().group(listOf(item(title)), context).sections.single().groups.single().playable.single()

    @Test
    fun `Choose release on the Show page plays the episode's release as chosen`() {
        val release = ranked("Harbor.Lights.S01E01.1080p.WEB.h264-GRP")
        val ep = EpisodePlay(
            showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 900001, tmdb = 900002),
            season = 1, episode = 1, episodeName = "Low Water", airDate = null, runtimeMinutes = 45,
        )
        assertEquals(
            PlayRequest(
                "Harbor Lights", "S01E01 · Low Water", listOf(release), FilePicker.Episode(1, 1),
                WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = 900002, season = 1, episode = 1),
                rememberedFirst = false, chosen = true, episodeName = "Low Water",
            ),
            chosenRequest(ep, release),
        )
    }

    @Test
    fun `Choose release on the Movie page plays the movie's release as chosen`() {
        val release = ranked("Copper.Crown.2019.1080p.BluRay.x264-GRP")
        val target = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118)
        assertEquals(
            PlayRequest(
                "Copper Crown", "2019", listOf(release), FilePicker.Largest, WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900011),
                rememberedFirst = false, chosen = true,
            ),
            chosenRequest(target, release),
        )
    }

    /** A release list's Play of one release is the group's Play of it alone, chosen; the group's own Play isn't. */
    @Test
    fun `a release list's Play in Search plays that release as chosen`() {
        val statuses = listOf(IndexerStatus("fake", "Fake", IndexerStatus.State.Answered(2)))
        val items = listOf("Harbor.Lights.S01E01.1080p.WEB.h264-GRP", "Harbor.Lights.S01E01.720p.WEB.h264-GRP").map(::item)
        val results = SearchRequest("harbor lights s01e01").results(null, statuses, ResultGrouper().group(items, context), done = true)
        val group = results.grouped.sections.single().groups.single()
        val picked = group.playable.last()
        assertEquals(
            playRequest(results, group, listOf(picked)).copy(rememberedFirst = false, chosen = true, alternatives = group.playable.dropLast(1)),
            chosenRequest(results, group, picked),
        )
        assertFalse(playRequest(results, group, group.playable).chosen)
    }

    /**
     * L152: a release picked by hand carries the list's other playable releases in the list's order, as ranked for whom it
     * shows them (the Cast device chosen), for the pre-check's Play a compatible release; never the one picked, nor a hidden
     * one.
     */
    @Test
    fun `a release picked by hand carries the list's others, in its order, for a compatible one`() {
        val titles = listOf("Harbor.Lights.S01E01.1080p.BluRay.x264-GRP", "Harbor.Lights.S01E01.1080p.WEB.h264-GRP", "Harbor.Lights.S01E01.720p.WEB.h264-GRP")
        val statuses = listOf(IndexerStatus("fake", "Fake", IndexerStatus.State.Answered(3)))
        val results = SearchRequest("harbor lights s01e01").results(null, statuses, ResultGrouper().group(titles.map(::item) + item("Harbor.Lights.S01E01.1080p.BluRay.ISO-GRP"), context), done = true)
        val group = results.grouped.sections.single().groups.single()
        val (first, middle, last) = group.playable
        assertEquals(listOf(first, last), group.alternativesTo(middle))
        assertEquals(listOf(middle, last), group.alternativesTo(first))
        assertEquals(listOf(first, last), chosenRequest(results, group, middle).alternatives)
        val ep = EpisodePlay(
            showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 900001, tmdb = 900002),
            season = 1, episode = 1, episodeName = "Low Water", airDate = null, runtimeMinutes = 45,
        )
        assertEquals(listOf(first, last), chosenRequest(ep, middle, group.alternativesTo(middle)).alternatives)
        assertEquals(listOf(first, last), chooseFileRequest(ep, middle, group.alternativesTo(middle)).alternatives)
        val target = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118)
        assertEquals(listOf(first, last), chosenRequest(target, middle, group.alternativesTo(middle)).alternatives)
    }
}

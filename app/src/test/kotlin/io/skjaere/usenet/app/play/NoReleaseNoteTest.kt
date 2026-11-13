package io.skjaere.usenet.app.play

import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The no-release sheet's note: an episode TMDB lists as not aired yet may still have releases, and often has around its date. */
class NoReleaseNoteTest {
    /** In this year, so shortDate leaves the year out. */
    private val airs = LocalDate.now().withMonth(10).withDayOfMonth(20)
    private val today = airs.minusDays(5)
    private val numbering = "Indexers can also number some shows differently from TMDB."

    /** Glasswater Bay: S01E01 aired a week ago, S01E02 airs on the 20th, S01E03 has no date yet. */
    private val show = ShowDetails(
        tmdbId = 900041, name = "Glasswater Bay", originalName = null, year = airs.year, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(SeasonSummary(1, "Season 1", 3, null, null)),
        lastEpisode = EpisodeSummary(1, 1, null, today.minusDays(7)), nextEpisode = EpisodeSummary(1, 2, null, airs),
        ids = ExternalIds(),
    )

    private fun play(number: Int, airDate: LocalDate?) = EpisodePlay.of(show, Episode(1, number, "Episode $number", null, airDate, 45, null), today)

    @Test
    fun `an episode TMDB lists for a later date gives that date, next to the numbering note`() {
        val ep = play(2, airs)
        assertFalse(ep.aired)
        assertEquals("TMDB lists this episode for 20 Oct. Releases often appear around then. $numbering", noReleaseNote(ep, today))
    }

    @Test
    fun `one TMDB has no date for, after the last aired episode, hasn't aired yet according to TMDB`() {
        val ep = play(3, null)
        assertFalse(ep.aired)
        assertEquals("This episode hasn't aired yet according to TMDB. $numbering", noReleaseNote(ep, today))
    }

    @Test
    fun `an aired episode gets no line about airing, beyond releases still coming in the first days`() {
        assertEquals(numbering, noReleaseNote(play(1, today.minusDays(7)), today))
        assertEquals(numbering, noReleaseNote(play(1, null), today))
        assertEquals("This episode aired yesterday, so releases may still appear. $numbering", noReleaseNote(play(1, today.minusDays(1)), today))
    }

    /**
     * L213: a torrent indexer whose releases were all hidden as not cached gets a line of its own on the no-release sheet,
     * naming no service: "Home Prowlarr: 4 found, none cached". A paired phone isn't told indexers by name (L37).
     */
    @Test
    fun `a torrent indexer whose releases none of the services has cached says so on the sheet`() {
        val ep = play(1, today.minusDays(7))
        val usenet = IndexerConfig("a", "Indexer A", "https://a.invalid/api", "k")
        val prowlarr = IndexerConfig("p", "Home Prowlarr", "https://p.invalid/api", "k", kind = IndexerKind.Torznab)
        val quarry = IndexerConfig("q", "Quarry", "https://q.invalid/api", "k", kind = IndexerKind.Torznab, useWithService = false)
        fun torrent(indexer: IndexerConfig, n: Int, availability: Availability): FoundRelease {
            val hash = "%040x".format(n)
            val title = "Glasswater.Bay.S01E01.1080p.WEB-DL.H.264-T$n"
            val item = NewznabItem(
                indexerId = indexer.id, guid = "btih:$hash", title = title, sizeBytes = 1_500_000_000, publishedAt = null, postedAt = null,
                categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
                season = 1, episode = 1, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = hash,
                availability = availability,
            )
            val ranked = ReleaseRanker().rank(listOf(ReleaseFacts(item.key, title, item.sizeBytes, source = ReleaseFacts.TORRENT, availability = availability)), RankingContext(StaticDeviceCapabilities())).single()
            return FoundRelease(item, ranked, SearchStage.Episode)
        }
        val hidden = (1..4).map { torrent(prowlarr, it, Availability.NotCached) } + (5..6).map { torrent(quarry, it, Availability.NotForService) }
        val reports = listOf(IndexerReport(usenet, "TVDB id"), IndexerReport(prowlarr, "name", found = 5, accepted = 4), IndexerReport(quarry, "name", found = 2, accepted = 2))
        val sheet = noRelease(ep, FindResult(ep.request, playable = emptyList(), hidden = hidden, stages = listOf(StageReport(SearchStage.Episode, reports))), today)
        assertEquals(listOf("Searched 3 indexers by TVDB id / name" to "6 found, none playable", "Home Prowlarr" to "4 found, none cached"), sheet.stages)
        assertEquals(listOf("Searched 3 indexers by TVDB id / name" to "6 found, none playable"), sheet.remoteStages)
    }
}

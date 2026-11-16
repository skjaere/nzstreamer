package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.download.SeasonDownloadRequest
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.hasAired
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The season's mark button goes by its aired episodes, but marking the season unwatched takes an
 * episode watched before TMDB's date too (releases often come early), so the season starts over.
 * The season's Download beside it goes by its aired episodes too: those with no download, or only a
 * failed one, are what it queues; none left, it isn't there; never on the Specials chip (L397). Its sheet's request lists
 * those, from the page's own answer, and counts the others apart (L433).
 */
class SeasonMarkTest {
    private val today = LocalDate.of(2026, 10, 15)

    private fun episode(number: Int, airDate: LocalDate?) = Episode(1, number, "Episode $number", null, airDate, 45, null)

    /** Glasswater Bay: S01E01 and E02 aired, S01E03 airs on the 20th, S01E04 has no date yet. */
    private val episodes = listOf(episode(1, today.minusDays(14)), episode(2, today.minusDays(7)), episode(3, today.plusDays(5)), episode(4, null))
    private val show = ShowDetails(
        tmdbId = 900041, name = "Glasswater Bay", originalName = null, year = 2026, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(SeasonSummary(1, "Season 1", 4, null, null)),
        lastEpisode = EpisodeSummary(1, 2, null, today.minusDays(7)), nextEpisode = EpisodeSummary(1, 3, null, today.plusDays(5)),
        ids = ExternalIds(),
    )

    private fun mark(vararg watched: Int) = seasonMark(episodes, { show.hasAired(it, today) }, { it.episode in watched })

    private val List<Episode>.numbers get() = map { it.episode }

    @Test
    fun `a season with an aired episode unwatched marks its aired episodes watched`() {
        listOf(mark(), mark(1), mark(3), mark(1, 3, 4)).forEach { m ->
            assertEquals("Mark season watched", m.label)
            assertEquals(true, m.enabled)
            assertEquals(listOf(1, 2), m.episodes.numbers)
            assertEquals(true, m.watched)
        }
    }

    @Test
    fun `a season whose aired episodes are watched marks them unwatched`() {
        val m = mark(1, 2)
        assertEquals("Mark season unwatched", m.label)
        assertEquals(listOf(1, 2), m.episodes.numbers)
        assertEquals(false, m.watched)
    }

    @Test
    fun `marking it unwatched takes episodes watched before TMDB's date too`() {
        val m = mark(1, 2, 3, 4)
        assertEquals("Mark season unwatched", m.label)
        assertEquals(listOf(1, 2, 3, 4), m.episodes.numbers)
        assertEquals(false, m.watched)
    }

    /** Harbor Lights' season 2: S02E01 to E06 aired, S02E07 airs next week, S02E08 has no date yet. */
    private val season2 = (1..8).map { n ->
        Episode(2, n, "Episode $n", null, if (n <= 6) today.minusDays(70L - 7 * n) else if (n == 7) today.plusDays(7) else null, 52, null)
    }

    private fun aired(e: Episode) = e.hasAired(today)

    private fun download(states: List<Pair<Int, DownloadState>>) = states.associate { (n, state) -> (2 to n) to TitleDownload("dl-$n", state) }

    /** The button's words and the episodes it queues by number; null where there is no button. */
    private fun button(episodes: List<Episode>, states: List<Pair<Int, DownloadState>> = emptyList()) =
        seasonDownload(episodes, ::aired, download(states))?.let { it.label to it.episodes.numbers }

    private fun all(state: DownloadState, numbers: IntRange = 1..6) = numbers.map { it to state }

    @Test
    fun `none of the aired episodes downloaded, it downloads the season's aired episodes`() {
        assertEquals("Download season 2" to listOf(1, 2, 3, 4, 5, 6), button(season2))
    }

    @Test
    fun `some downloaded or queued, it counts the aired episodes left, one in the singular`() {
        val some = listOf(1 to DownloadState.Done, 2 to DownloadState.Done, 3 to DownloadState.Queued)
        assertEquals("Download 3 episodes" to listOf(4, 5, 6), button(season2, some))
        assertEquals("Download 1 episode" to listOf(6), button(season2, all(DownloadState.Done, 1..5)))
    }

    @Test
    fun `a download still to finish counts as downloaded, so with none left there is no button`() {
        val going = listOf(DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting, DownloadState.Stopped)
        assertNull(button(season2, (1..6).map { it to going[it - 1] }))
        assertNull(button(season2, all(DownloadState.Done)))
    }

    @Test
    fun `a failed download counts as not downloaded`() {
        assertEquals("Download season 2" to listOf(1, 2, 3, 4, 5, 6), button(season2, all(DownloadState.Failed)))
        assertEquals("Download 1 episode" to listOf(6), button(season2, all(DownloadState.Done, 1..5) + (6 to DownloadState.Failed)))
        assertEquals("Download 2 episodes" to listOf(2, 6), button(season2, all(DownloadState.Done, 3..5) + (1 to DownloadState.Queued) + (2 to DownloadState.Failed)))
    }

    @Test
    fun `episodes not aired yet are left out, downloaded or not`() {
        // S02E07 downloaded before TMDB's date counts for nothing: none of the aired ones has a download.
        assertEquals("Download season 2" to listOf(1, 2, 3, 4, 5, 6), button(season2, listOf(7 to DownloadState.Done, 8 to DownloadState.Queued)))
        // Nothing aired yet: nothing to download.
        assertNull(button(season2.drop(6)))
        assertNull(button(emptyList()))
    }

    @Test
    fun `the Specials chip has no season Download`() {
        val specials = listOf(
            Episode(0, 1, "A Harbor Christmas", null, today.minusDays(300), 44, null),
            Episode(0, 2, "The Pilot's Logbook", null, today.minusDays(100), 12, null),
        )
        assertNull(seasonDownload(specials, ::aired, emptyMap()))
        assertNull(seasonDownload(specials, ::aired, mapOf((0 to 1) to TitleDownload("dl-s", DownloadState.Done))))
    }

    /** Harbor Lights as the Show page's answer has it: season 2 of 8 episodes, its poster. */
    private val harbor = show.copy(
        tmdbId = 990417, name = "Harbor Lights", year = 2021, posterPath = "/poster.jpg", seasons = listOf(SeasonSummary(2, "Season 2", 8, null, null)),
        lastEpisode = EpisodeSummary(2, 6, null, today.minusDays(28)), nextEpisode = EpisodeSummary(2, 7, null, today.plusDays(7)),
    )

    @Test
    fun `the season's sheet lists what its Download queues, and counts the others apart by their downloads, else by their airing`() {
        // S02E07 downloaded before TMDB's date says so; S02E08, not aired and not downloaded, hasn't aired.
        val states = listOf(1 to DownloadState.Done, 2 to DownloadState.Running, 3 to DownloadState.Failed, 7 to DownloadState.Done)
        val request = seasonDownloadRequest(harbor, season2, today, download(states))!!
        assertEquals(seasonDownload(season2, ::aired, download(states))!!.episodes.numbers, request.episodes.map { it.play.episode }, "what the button counts")
        assertEquals(listOf(3, 4, 5, 6), request.episodes.map { it.play.episode })
        assertEquals(listOf(true, false, false, false), request.episodes.map { it.failedBefore })
        assertEquals(listOf(1, 7), request.downloaded)
        assertEquals(listOf(2), request.queued)
        assertEquals(listOf(8), request.notAired)
        assertEquals(
            SeasonDownloadRequest(
                "Harbor Lights", 2, (3..6).map { n -> SeasonDownloadRequest.Listed(EpisodePlay.of(harbor, season2[n - 1], today), failedBefore = n == 3) },
                downloaded = listOf(1, 7), queued = listOf(2), notAired = listOf(8), posterPath = "/poster.jpg",
            ),
            request,
            "each episode's play as the page's answer gives it, its season's count among it",
        )
        assertEquals(8, request.episodes.first().play.seasonEpisodes)

        // Nothing left: no sheet, as no button; never for Specials.
        assertNull(seasonDownloadRequest(harbor, season2, today, download(all(DownloadState.Done))))
        val specials = listOf(Episode(0, 1, "A Harbor Christmas", null, today.minusDays(300), 44, null))
        assertNull(seasonDownloadRequest(harbor, specials, today, emptyMap()))
    }

    @Test
    fun `a season with nothing aired yet can't be marked`() {
        val unaired = episodes.drop(2)
        val m = seasonMark(unaired, { show.hasAired(it, today) }, { it.episode == 3 })
        assertEquals("Mark season watched", m.label)
        assertEquals(false, m.enabled)
    }
}

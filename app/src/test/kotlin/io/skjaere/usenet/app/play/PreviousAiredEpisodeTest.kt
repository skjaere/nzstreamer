package io.skjaere.usenet.app.play

import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The player's Previous within an episode's first seconds: the episode before, by TMDB's order (NextAiredEpisodeTest has the one after). */
class PreviousAiredEpisodeTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val aired = today.minusDays(30)

    private fun episode(season: Int, number: Int, airDate: LocalDate? = aired) =
        Episode(season, number, "Episode $number", null, airDate, 45, "/s${season}e$number.jpg")

    /**
     * Harbor Lights as TMDB lists it: specials listed after season 1, the first of them with no air
     * date; an announced season 2 with no episodes yet; season 3 airing, its last episode next week.
     */
    private val seasons = mapOf(
        0 to listOf(episode(0, 1, airDate = null), episode(0, 2), episode(0, 3)),
        1 to listOf(episode(1, 1), episode(1, 2), episode(1, 3)),
        2 to emptyList(),
        3 to listOf(episode(3, 1), episode(3, 2), episode(3, 3, airDate = today.plusDays(7))),
    )

    private val show = ShowDetails(
        tmdbId = 900002, name = "Harbor Lights", originalName = null, year = 2021, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(1, 0, 2, 3).map { SeasonSummary(it, if (it == 0) "Specials" else "Season $it", seasons.getValue(it).size, null, null) },
        lastEpisode = EpisodeSummary(3, 2, null, aired), nextEpisode = EpisodeSummary(3, 3, null, today.plusDays(7)),
        ids = ExternalIds(tvdb = 900001),
    )

    private fun previous(season: Int, episode: Int, loadShow: suspend (Int) -> ShowDetails = { show }) = runBlocking {
        previousAiredEpisode(900002, season, episode, today, loadShow, loadSeason = { _, n -> Season(900002, n, null, null, seasons.getValue(n)) })
    }

    @Test
    fun `the episode before in the season, with its still`() {
        val e = previous(1, 3)!!
        assertEquals(1 to 2, e.season to e.episode)
        assertEquals("S01E02 · Episode 2", e.subtitle)
        assertEquals("/s1e2.jpg", e.stillPath)
        assertEquals(900001, e.ids.tvdb)
    }

    @Test
    fun `before a season's first episode, the last of the regular season before it with episodes`() {
        // The empty season 2 and the specials are skipped.
        val e = previous(3, 1)!!
        assertEquals(1 to 3, e.season to e.episode)
    }

    @Test
    fun `nothing before the show's first episode, not even a special`() {
        assertNull(previous(1, 1))
    }

    @Test
    fun `an episode not aired yet, played all the same, has the one before it`() {
        assertEquals(3 to 2, previous(3, 3)?.let { it.season to it.episode })
    }

    @Test
    fun `specials lead back only to the special before, one that has aired`() {
        assertEquals(0 to 2, previous(0, 3)?.let { it.season to it.episode })
        // S00E01 has no air date, and specials aren't counted among the aired regular episodes.
        assertNull(previous(0, 2))
        // The first special doesn't lead back into a regular season.
        assertNull(previous(0, 1))
    }

    @Test
    fun `a TMDB failure means no previous episode`() {
        assertNull(previous(1, 2, loadShow = { throw TmdbException(TmdbError.Network("offline")) }))
    }

    @Test
    fun `cancellation is passed on, not taken for a failure`() {
        assertFailsWith<CancellationException> { previous(1, 2, loadShow = { throw CancellationException("player left") }) }
    }
}

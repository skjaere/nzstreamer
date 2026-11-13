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

class NextAiredEpisodeTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val aired = today.minusDays(30)

    private fun episode(season: Int, number: Int, airDate: LocalDate? = aired) =
        Episode(season, number, "Episode $number", null, airDate, 45, "/s${season}e$number.jpg")

    /** Harbor Lights as TMDB lists it: specials listed after season 1, an announced season 2 with no episodes yet. */
    private val seasons = mapOf(
        0 to listOf(episode(0, 1), episode(0, 2)),
        1 to listOf(episode(1, 1), episode(1, 2), episode(1, 3)),
        2 to emptyList(),
        3 to listOf(episode(3, 1), episode(3, 2, airDate = today.plusDays(7))),
    )

    private val show = ShowDetails(
        tmdbId = 900002, name = "Harbor Lights", originalName = null, year = 2021, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(1, 0, 2, 3).map { SeasonSummary(it, if (it == 0) "Specials" else "Season $it", seasons.getValue(it).size, null, null) },
        lastEpisode = EpisodeSummary(3, 1, null, aired), nextEpisode = EpisodeSummary(3, 2, null, today.plusDays(7)),
        ids = ExternalIds(tvdb = 900001),
    )

    private fun next(season: Int, episode: Int, loadShow: suspend (Int) -> ShowDetails = { show }) = runBlocking {
        nextAiredEpisode(900002, season, episode, today, loadShow, loadSeason = { _, n -> Season(900002, n, null, null, seasons.getValue(n)) })
    }

    @Test
    fun `the next episode in the season, with its still`() {
        val e = next(1, 1)!!
        assertEquals(1 to 2, e.season to e.episode)
        assertEquals("S01E02 · Episode 2", e.subtitle)
        assertEquals("/s1e2.jpg", e.stillPath)
        assertEquals(900001, e.ids.tvdb)
    }

    @Test
    fun `after a season's last episode, the first of the next regular season with episodes`() {
        // Specials and the empty season 2 are skipped.
        val e = next(1, 3)!!
        assertEquals(3 to 1, e.season to e.episode)
    }

    @Test
    fun `nothing that has not aired yet`() {
        assertNull(next(3, 1))
    }

    @Test
    fun `nothing after the last special`() {
        // Specials are watched one by one; the last one doesn't lead into season 1.
        assertEquals(0 to 2, next(0, 1)?.let { it.season to it.episode })
        assertNull(next(0, 2))
    }

    @Test
    fun `a TMDB failure means no next episode`() {
        assertNull(next(1, 1, loadShow = { throw TmdbException(TmdbError.Network("offline")) }))
    }

    @Test
    fun `cancellation is passed on, not taken for a failure`() {
        assertFailsWith<CancellationException> { next(1, 1, loadShow = { throw CancellationException("player left") }) }
    }
}

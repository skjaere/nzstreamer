package io.skjaere.usenet.app.collection

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

/** An episode TMDB lists as not aired yet still plays (releases often come early); its row only says so. */
class EpisodeRowTextTest {
    /** In this year, so shortDate leaves the year out. */
    private val airs = LocalDate.now().withMonth(10).withDayOfMonth(20)
    private val today = airs.minusDays(5)

    private fun episode(season: Int, number: Int, airDate: LocalDate?) = Episode(season, number, "Episode $number", null, airDate, 45, null)

    /** Glasswater Bay: S01E01 aired, S01E02 airs on the 20th, S01E03 has no date yet; one special, undated. */
    private val show = ShowDetails(
        tmdbId = 900041, name = "Glasswater Bay", originalName = null, year = airs.year, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(SeasonSummary(1, "Season 1", 3, null, null), SeasonSummary(0, "Specials", 1, null, null)),
        lastEpisode = EpisodeSummary(1, 1, null, today.minusDays(7)), nextEpisode = EpisodeSummary(1, 2, null, airs),
        ids = ExternalIds(),
    )

    private fun row(e: Episode) = episodeRowText(e, show.hasAired(e, today))

    @Test
    fun `an aired episode gives its date and runtime, and nothing more for TalkBack`() {
        val text = row(episode(1, 1, today.minusDays(7)))
        assertEquals("${shortDate(today.minusDays(7))} · 45 min", text.meta)
        assertNull(text.stateDescription)
    }

    @Test
    fun `an episode TMDB lists for a later date says when it airs, and TalkBack that it hasn't aired by TMDB`() {
        val text = row(episode(1, 2, airs))
        assertEquals("Airs 20 Oct · 45 min", text.meta)
        assertEquals("Hasn't aired yet according to TMDB", text.stateDescription)
    }

    @Test
    fun `one with no date after the last aired episode says it hasn't aired, as does an undated special`() {
        listOf(episode(1, 3, null), episode(0, 1, null)).forEach { e ->
            val text = row(e)
            assertEquals("Not aired yet · 45 min", text.meta, "S${e.season}E${e.episode}")
            assertEquals("Hasn't aired yet according to TMDB", text.stateDescription, "S${e.season}E${e.episode}")
        }
    }

    @Test
    fun `one with no date up to the last aired episode counts as aired`() {
        val text = row(episode(1, 1, null))
        assertEquals("45 min", text.meta)
        assertNull(text.stateDescription)
    }
}

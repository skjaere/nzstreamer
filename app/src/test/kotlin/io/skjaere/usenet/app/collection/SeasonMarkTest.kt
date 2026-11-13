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

/**
 * The season's mark button goes by its aired episodes, but marking the season unwatched takes an
 * episode watched before TMDB's date too (releases often come early), so the season starts over.
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

    @Test
    fun `a season with nothing aired yet can't be marked`() {
        val unaired = episodes.drop(2)
        val m = seasonMark(unaired, { show.hasAired(it, today) }, { it.episode == 3 })
        assertEquals("Mark season watched", m.label)
        assertEquals(false, m.enabled)
    }
}

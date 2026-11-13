package io.skjaere.usenet.metadata

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiredEpisodesTest {
    private val today = LocalDate.of(2026, 10, 13)

    /** Seasons 1 (3 eps) and 2 (8 eps), specials; S02E05 last aired, S02E06 airs 20 Oct. */
    private val show = ShowDetails(
        tmdbId = 1, name = "Harbor Lights", originalName = null, year = 2021, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(
            SeasonSummary(0, "Specials", 2, null, null),
            SeasonSummary(1, "Season 1", 3, LocalDate.of(2021, 3, 4), null),
            SeasonSummary(2, "Season 2", 8, LocalDate.of(2026, 9, 1), null),
        ),
        lastEpisode = EpisodeSummary(2, 5, "The Long Tide", LocalDate.of(2026, 10, 6)),
        nextEpisode = EpisodeSummary(2, 6, null, LocalDate.of(2026, 10, 20)),
        ids = ExternalIds(),
    )

    private fun refs(season: Int, episodes: IntRange) = episodes.map { EpisodeRef(season, it) }

    @Test
    fun `every regular episode up to the last aired, specials left out`() {
        assertEquals(refs(1, 1..3) + refs(2, 1..5), show.airedEpisodes(today))
    }

    @Test
    fun `the next episode counts from its air day on, though the details are from before`() {
        assertEquals(refs(1, 1..3) + refs(2, 1..6), show.airedEpisodes(LocalDate.of(2026, 10, 20)))
        assertEquals(refs(1, 1..3) + refs(2, 1..5), show.airedEpisodes(LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun `nothing aired yet, and seasons TMDB lists without episodes`() {
        val announced = show.copy(lastEpisode = null, nextEpisode = EpisodeSummary(1, 1, null, LocalDate.of(2027, 1, 1)))
        assertEquals(emptyList(), announced.airedEpisodes(today))
        val emptySeason = show.copy(seasons = show.seasons + SeasonSummary(3, "Season 3", 0, null, null), lastEpisode = EpisodeSummary(3, 1, null, null))
        assertEquals(refs(1, 1..3) + refs(2, 1..8), emptySeason.airedEpisodes(today))
    }

    /** Undated episodes at or before the last aired one count as aired, as the show page shows them. */
    @Test
    fun `episodes without air dates are aired as the show page has them`() {
        val undated = show.copy(seasons = show.seasons.map { it.copy(airDate = null) }, lastEpisode = EpisodeSummary(2, 5, null, null))
        val episodes = listOf(1 to 3, 2 to 8).flatMap { (s, n) -> (1..n).map { Episode(s, it, null, null, null, null, null) } }
        assertEquals(episodes.filter { undated.hasAired(it, today) }.map { EpisodeRef(it.season, it.episode) }, undated.airedEpisodes(today))
    }

    /** Marking a whole show watched marks what its progress counts as aired, and leaves nothing unwatched. */
    @Test
    fun `as many as ShowProgress counts aired, and all of them watched leaves none unwatched`() {
        val variants = listOf(
            show,
            show.copy(nextEpisode = EpisodeSummary(2, 6, null, today)),
            show.copy(nextEpisode = null, status = "Ended"),
            show.copy(lastEpisode = EpisodeSummary(1, 2, null, null)),
            show.copy(seasons = show.seasons.reversed()),
            show.copy(lastEpisode = null, nextEpisode = null),
        )
        variants.forEach { v ->
            val aired = v.airedEpisodes(today)
            assertEquals(ShowProgress.of(v, emptyList(), today).aired, aired.size, "$v")
            val all = ShowProgress.of(v, aired.map { EpisodeWatch(it.season, it.episode, watched = true) }, today)
            assertEquals(0, all.unwatched)
            assertTrue(all.nextUp is NextUp.UpToDate || aired.isEmpty())
        }
    }
}

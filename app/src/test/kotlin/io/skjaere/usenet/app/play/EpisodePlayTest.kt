package io.skjaere.usenet.app.play

import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.ShowRef
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals

class EpisodePlayTest {
    private val episode = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = 900002),
        season = 5, episode = 3, episodeName = null, airDate = null, runtimeMinutes = null,
    )

    @Test
    fun `the name search writes the episode in ascii digits whatever the device locale`() {
        // The query goes to the indexers, which match release names written S05E03.
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        try {
            assertEquals("Harbor Lights s05e03", episode.nameQuery)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `an episode's search ranks its untagged releases in the show's original language`() {
        val show = ShowDetails(
            900040, "Moonwell Village", "Dalsaem Maeul", 2021, null, null, null, "Ended", false, 60, emptyList(), null, null,
            ExternalIds(tvdb = 900041), originalLanguage = "ko",
        )
        val ep = EpisodePlay.of(show, Episode(1, 2, "The Well", null, LocalDate.of(2021, 3, 6), null, null), today = LocalDate.of(2026, 10, 18))
        assertEquals("ko", ep.request.show.originalLanguage)
        // What else the finder is asked is as before.
        assertEquals(ShowRef("Moonwell Village", 2021, listOf("Dalsaem Maeul"), TvIds(tvdb = 900041, tmdb = 900040), originalLanguage = "ko"), ep.request.show)
        assertEquals(EpisodeRequest(ep.request.show, 1, 2, LocalDate.of(2021, 3, 6), 60), ep.request)
    }

    @Test
    fun `an episode's search counts the episodes TMDB lists for its season, for a season pack's bitrate`() {
        val seasons = listOf(SeasonSummary(1, "Season 1", 8, null, null), SeasonSummary(2, "Season 2", 0, null, null))
        val show = ShowDetails(900040, "Moonwell Village", null, 2021, null, null, null, "Ended", false, 60, seasons, null, null, ExternalIds())
        fun request(season: Int) = EpisodePlay.of(show, Episode(season, 1, null, null, LocalDate.of(2021, 3, 6), null, null), today = LocalDate.of(2026, 10, 18)).request
        assertEquals(8, request(1).seasonEpisodes)
        // None listed yet, or a season TMDB doesn't list: not known.
        assertEquals(null, request(2).seasonEpisodes)
        assertEquals(null, request(3).seasonEpisodes)
    }
}

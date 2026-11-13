package io.skjaere.usenet.metadata

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowProgressTest {
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

    private fun watched(vararg refs: Pair<Int, Int>) = refs.map { (s, e) -> EpisodeWatch(s, e, watched = true) }

    private fun episode(season: Int, episode: Int, airDate: LocalDate?) = Episode(season, episode, null, null, airDate, null, null)

    @Test
    fun `not started`() {
        val p = ShowProgress.of(show, emptyList(), today)
        assertEquals(8, p.aired)
        assertEquals(8, p.unwatched)
        assertEquals(NextUp.Play(EpisodeRef(1, 1), firstEpisode = true), p.nextUp)
        assertFalse(p.started)
    }

    @Test
    fun `next is the episode after the furthest watched, across seasons`() {
        assertEquals(NextUp.Play(EpisodeRef(1, 3), false), ShowProgress.of(show, watched(1 to 1, 1 to 2), today).nextUp)
        assertEquals(NextUp.Play(EpisodeRef(2, 1), false), ShowProgress.of(show, watched(1 to 3), today).nextUp)
        // Specials and stray earlier gaps don't move "next".
        assertEquals(NextUp.Play(EpisodeRef(2, 3), false), ShowProgress.of(show, watched(0 to 1, 2 to 2), today).nextUp)
    }

    @Test
    fun `an unfinished episode is resumed first`() {
        val watches = watched(1 to 1, 1 to 2) + EpisodeWatch(2, 1, watched = false, positionMs = 600_000, lastPlayedAt = 5) +
            EpisodeWatch(1, 3, watched = false, positionMs = 30_000, lastPlayedAt = 9)
        assertEquals(NextUp.Resume(EpisodeRef(2, 1), 600_000), ShowProgress.of(show, watches, today).nextUp)
    }

    @Test
    fun `everything aired watched is up to date with the return date`() {
        val all = watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5)
        val p = ShowProgress.of(show, all, today)
        assertEquals(0, p.unwatched)
        assertEquals(NextUp.UpToDate(LocalDate.of(2026, 10, 20), EpisodeRef(2, 6)), p.nextUp)
        assertTrue(p.started)
    }

    @Test
    fun `new counts unwatched episodes aired in the last week`() {
        val recent = mapOf(EpisodeRef(2, 4) to LocalDate.of(2026, 9, 29), EpisodeRef(2, 5) to LocalDate.of(2026, 10, 6), EpisodeRef(2, 6) to LocalDate.of(2026, 10, 20))
        assertEquals(1, ShowProgress.of(show, emptyList(), today, recent).newEpisodes)
        assertEquals(0, ShowProgress.of(show, watched(2 to 5), today, recent).newEpisodes)
    }

    @Test
    fun `a next episode that aired since the show was fetched counts as aired`() {
        val throughE05 = watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5)
        listOf(today, today.minusDays(1)).forEach { airDate ->
            // Fetched before S02E06 aired: TMDB still had it as the next episode.
            val fetchedEarlier = show.copy(nextEpisode = EpisodeSummary(2, 6, null, airDate))
            val p = ShowProgress.of(fetchedEarlier, throughE05, today, mapOf(EpisodeRef(2, 5) to LocalDate.of(2026, 10, 6)))
            assertEquals(9, p.aired)
            assertEquals(1, p.unwatched)
            assertEquals(NextUp.Play(EpisodeRef(2, 6), firstEpisode = false), p.nextUp)
            assertEquals(1, p.newEpisodes)
            // Watched as well: up to date, and when the episode after it airs isn't known yet.
            val all = ShowProgress.of(fetchedEarlier, throughE05 + watched(2 to 6), today)
            assertEquals(9, all.watched)
            assertEquals(NextUp.UpToDate(null, EpisodeRef(2, 7)), all.nextUp)
        }
        // A show whose first episode airs today.
        val premiere = show.copy(lastEpisode = null, nextEpisode = EpisodeSummary(1, 1, null, today))
        val p = ShowProgress.of(premiere, emptyList(), today)
        assertEquals(1, p.aired)
        assertEquals(NextUp.Play(EpisodeRef(1, 1), firstEpisode = true), p.nextUp)
    }

    @Test
    fun `a next episode watched before TMDB's date is no longer the one to come`() {
        // Released early: S02E06 watched before the 20th, the date TMDB lists it for.
        val early = watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5, 2 to 6)
        val p = ShowProgress.of(show, early, today)
        assertEquals(8, p.watched)
        assertEquals(0, p.unwatched)
        // Up to date, and when the episode after it airs isn't known yet.
        assertEquals(NextUp.UpToDate(null, EpisodeRef(2, 7)), p.nextUp)
        assertEquals(2, ShowProgress.openingSeason(show, early, today))
    }

    @Test
    fun `a season premiere watched before TMDB's date is no longer the one to come`() {
        val premiere = show.copy(
            seasons = show.seasons.map { if (it.number == 2) it.copy(episodeCount = 5) else it } + SeasonSummary(3, "Season 3", 6, null, null),
            nextEpisode = EpisodeSummary(3, 1, null, LocalDate.of(2026, 10, 20)),
        )
        val throughS2 = watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5)
        assertEquals(NextUp.UpToDate(LocalDate.of(2026, 10, 20), EpisodeRef(3, 1)), ShowProgress.of(premiere, throughS2, today).nextUp)
        assertEquals(NextUp.UpToDate(null, EpisodeRef(3, 2)), ShowProgress.of(premiere, throughS2 + watched(3 to 1), today).nextUp)
        assertEquals(3, ShowProgress.openingSeason(premiere, throughS2 + watched(3 to 1), today))
    }

    @Test
    fun `a show opens on the season of the next episode to watch`() {
        assertEquals(1, ShowProgress.openingSeason(show, emptyList(), today))
        assertEquals(2, ShowProgress.openingSeason(show, watched(1 to 1, 1 to 2, 1 to 3), today))
        assertEquals(2, ShowProgress.openingSeason(show, watched(1 to 1, 1 to 2) + EpisodeWatch(2, 4, watched = false, positionMs = 600_000), today))
        // Up to date: the season the next episode airs in.
        val all = watched(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5)
        assertEquals(2, ShowProgress.openingSeason(show, all, today))
        // Ended, all watched: the last season.
        val ended = show.copy(seasons = show.seasons.map { if (it.number == 2) it.copy(episodeCount = 5) else it }, nextEpisode = null)
        assertEquals(2, ShowProgress.openingSeason(ended, all, today))
    }

    @Test
    fun `the opening season waits for the watch states when they arrive after the show`() = runBlocking<Unit> {
        // The show came from TMDB's file cache; the library hasn't answered yet.
        val library = MutableSharedFlow<List<EpisodeWatch>>(replay = 1)
        val opening = async(start = CoroutineStart.UNDISPATCHED) { ShowProgress.openingSeason(show, library, today) }
        library.emit(watched(1 to 1, 1 to 2, 1 to 3) + EpisodeWatch(2, 4, watched = false, positionMs = 600_000, lastPlayedAt = 5))
        assertEquals(2, opening.await())
    }

    @Test
    fun `an episode without an air date has aired when it comes at or before the last aired one`() {
        assertTrue(show.hasAired(episode(1, 2, null), today))
        assertTrue(show.hasAired(episode(2, 5, null), today))
        assertFalse(show.hasAired(episode(2, 6, null), today))
        assertFalse(show.hasAired(episode(3, 1, null), today))
        // Specials have no place in the order.
        assertFalse(show.hasAired(episode(0, 1, null), today))
        // An air date decides on its own.
        assertTrue(show.hasAired(episode(2, 7, LocalDate.of(2026, 10, 1)), today))
        assertFalse(show.hasAired(episode(1, 2, LocalDate.of(2026, 10, 20)), today))
        // The next episode aired since the show was fetched; nothing aired yet.
        assertTrue(show.copy(nextEpisode = EpisodeSummary(2, 6, null, today)).hasAired(episode(2, 6, null), today))
        assertFalse(show.copy(lastEpisode = null, nextEpisode = null).hasAired(episode(1, 1, null), today))
    }

    @Test
    fun `marking a season's aired episodes watched includes those without an air date`() {
        // TMDB has no air date for S01E02.
        val season1 = listOf(episode(1, 1, LocalDate.of(2021, 3, 4)), episode(1, 2, null), episode(1, 3, LocalDate.of(2021, 3, 18)))
        val marked = season1.filter { show.hasAired(it, today) }.map { EpisodeWatch(it.season, it.episode, watched = true) }
        assertEquals(3, ShowProgress.of(show, marked, today).watched)
    }

    @Test
    fun `watching an unaired episode doesn't count towards aired progress`() {
        assertEquals(1, ShowProgress.of(show, watched(1 to 1, 2 to 7), today).watched)
    }
}

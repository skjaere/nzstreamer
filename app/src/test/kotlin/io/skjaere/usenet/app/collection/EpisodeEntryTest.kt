package io.skjaere.usenet.app.collection

import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/** Where Down from a TV's season chips enters the season's episodes (TV-3 D7). */
class EpisodeEntryTest {
    private fun season(number: Int, episodes: Int = 6) = (1..episodes).map { Episode(number, it, "Episode $it", null, null, 45, null) }

    private fun watchedUpTo(last: Int): (Episode) -> Boolean = { it.episode <= last }

    @Test
    fun `the next-up episode, resumed or to play, when the season shown holds it`() {
        assertEquals(4, episodeEntry(season(2), NextUp.Resume(EpisodeRef(2, 5), 600_000), watchedUpTo(3)))
        assertEquals(2, episodeEntry(season(2), NextUp.Play(EpisodeRef(2, 3), firstEpisode = false), watchedUpTo(2)))
    }

    /** Another season shown: its first episode not watched, not the place of the next-up one. */
    @Test
    fun `in another season, its first unwatched episode`() {
        assertEquals(3, episodeEntry(season(1), NextUp.Play(EpisodeRef(2, 1), firstEpisode = false), watchedUpTo(3)))
    }

    @Test
    fun `up to date, the episode it returns with when the season shown holds it`() {
        val nextAirs = NextUp.UpToDate(LocalDate.of(2026, 11, 2), EpisodeRef(3, 4))
        assertEquals(3, episodeEntry(season(3), nextAirs, watchedUpTo(3)))
    }

    @Test
    fun `a season watched through, its first episode`() {
        assertEquals(0, episodeEntry(season(1), NextUp.UpToDate(null, null), watchedUpTo(6)))
        assertEquals(0, episodeEntry(season(1), NextUp.UpToDate(LocalDate.of(2026, 11, 2), EpisodeRef(2, 1)), watchedUpTo(6)))
    }

    /** One watched early, out of order: the first one not watched still. */
    @Test
    fun `the first unwatched one, wherever the watched ones are`() {
        val watched = setOf(1, 2, 4)
        assertEquals(2, episodeEntry(season(1), NextUp.Play(EpisodeRef(2, 1), firstEpisode = false)) { it.episode in watched })
    }

    @Test
    fun `a season with no episodes yet has its first place`() {
        assertEquals(0, episodeEntry(emptyList(), NextUp.Play(EpisodeRef(1, 1), firstEpisode = true), watchedUpTo(0)))
    }
}

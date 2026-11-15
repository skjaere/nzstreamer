package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.TvIds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L354: the episodes either side of a downloaded one by the downloads alone ([OfflineNext]), for when TMDB doesn't answer
 * (offline, or failing). Next is (season, episode + 1) while the row's season goes on by its episode count, or the count
 * isn't known, else the next season's first; specials lead only to specials. One with a finished download plays from its
 * file, as an episode built from its row; one not downloaded is offered offline inside a season (the card says so), and
 * nothing past a season's end or online. Previous goes back the same way, only to a finished download, and across a
 * season only to the season before's last, as its own count says. Names and ids are invented.
 */
class OfflineNextTest {
    private fun row(season: Int, episode: Int, seasonEpisodes: Int? = 8, name: String? = "Episode $episode", show: Int = SHOW) = Download(
        id = "row-$show-$season-$episode", watchKey = WatchKey.episode(show, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S0${season}E0$episode", showTmdbId = show, season = season, episode = episode, seasonEpisodes = seasonEpisodes, episodeName = name,
        runtimeMinutes = 44, stillPath = "/still-$season-$episode.jpg", state = DownloadState.Done, position = 0,
        release = RememberedRelease("fake-indexer", "hl-$season-$episode", "Harbor.Lights.S0${season}E0$episode.1080p.WEB.h264-KESTREL", null, 0, "nzb-$season-$episode"),
        createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private fun done(vararg rows: Download) = rows.associateBy { it.watchKey }

    private val EpisodePlay?.at get() = this?.let { it.season to it.episode }

    @Test
    fun `a finished next episode in the season plays from its row, named and pictured as its row keeps it`() {
        val playing = row(1, 4)
        val next = OfflineNext.next(playing, done(playing, row(1, 5, name = "Low Water")), online = false)
        assertEquals(
            EpisodePlay(
                showTmdbId = SHOW, showName = "Harbor Lights", showYear = null, aliases = emptyList(), ids = TvIds(tmdb = SHOW), season = 1, episode = 5,
                episodeName = "Low Water", airDate = null, runtimeMinutes = 44, stillPath = "/still-1-5.jpg", seasonEpisodes = 8,
            ),
            next,
        )
        assertEquals(WatchKey.episode(SHOW, 1, 5), next?.key)
        // Online, with TMDB failing, the same: it plays from its file.
        assertEquals(1 to 5, OfflineNext.next(playing, done(playing, row(1, 5)), online = true).at)
    }

    @Test
    fun `offline, a next episode in the season not downloaded is still the next, for the card to say so`() {
        val playing = row(1, 4)
        // E06 is downloaded, but E05 is the next: an episode not downloaded is never skipped.
        val next = OfflineNext.next(playing, done(playing, row(1, 6)), online = false)
        assertEquals(1 to 5, next.at)
        assertNull(next?.episodeName)
        assertNull(next?.stillPath)
        // Online with TMDB failing, one not downloaded has nothing to search by: none, as before.
        assertNull(OfflineNext.next(playing, done(playing, row(1, 6)), online = true))
    }

    @Test
    fun `with the season's count unknown the season goes on`() {
        val playing = row(2, 9, seasonEpisodes = null)
        assertEquals(2 to 10, OfflineNext.next(playing, done(playing), online = false).at)
    }

    @Test
    fun `at a season's end the next is the next season's first, only when it is downloaded`() {
        val last = row(1, 8)
        assertEquals(2 to 1, OfflineNext.next(last, done(last, row(2, 1)), online = false).at)
        // Nothing past a season's end that isn't downloaded, offline or online.
        assertNull(OfflineNext.next(last, done(last, row(2, 2)), online = false))
        assertNull(OfflineNext.next(last, done(last), online = true))
        // Another show's season 2 is no next.
        assertNull(OfflineNext.next(last, done(last, row(2, 1, show = SHOW + 1)), online = false))
    }

    @Test
    fun `specials lead only to specials`() {
        val special = row(0, 2, seasonEpisodes = 3)
        assertEquals(0 to 3, OfflineNext.next(special, done(special, row(0, 3, seasonEpisodes = 3)), online = false).at)
        // The specials' last: never on to season 1.
        val lastSpecial = row(0, 3, seasonEpisodes = 3)
        assertNull(OfflineNext.next(lastSpecial, done(lastSpecial, row(1, 1)), online = false))
        // A regular season's end never leads to the specials either.
        assertNull(OfflineNext.next(row(1, 8), done(row(0, 1)), online = false))
    }

    @Test
    fun `previous is the one before in the season, only when it is downloaded`() {
        val playing = row(1, 4)
        assertEquals(1 to 3, OfflineNext.previous(playing, done(playing, row(1, 3))).at)
        assertNull(OfflineNext.previous(playing, done(playing, row(1, 2))))
    }

    @Test
    fun `at a season's first, previous is the season before's last, only when its own count says it is the last`() {
        val first = row(2, 1)
        assertEquals(1 to 8, OfflineNext.previous(first, done(first, row(1, 8))).at)
        // Its count says it isn't the last, or says nothing.
        assertNull(OfflineNext.previous(first, done(first, row(1, 7))))
        assertNull(OfflineNext.previous(first, done(first, row(1, 8, seasonEpisodes = null))))
        // Season 1's first leads back to no special, and the specials' first to nothing.
        assertNull(OfflineNext.previous(row(1, 1), done(row(0, 3, seasonEpisodes = 3))))
        assertNull(OfflineNext.previous(row(0, 1), done(row(0, 1))))
        assertEquals(0 to 1, OfflineNext.previous(row(0, 2), done(row(0, 1))).at)
    }

    @Test
    fun `a film or a row without its numbers has none either side`() {
        val film = row(1, 1).copy(kind = DownloadKind.Movie, watchKey = WatchKey.movie(900101), showTmdbId = null, season = null, episode = null, movieTmdbId = 900101)
        assertNull(OfflineNext.next(film, done(row(1, 2)), online = false))
        assertNull(OfflineNext.previous(film, done(row(1, 1))))
    }

    private companion object {
        /** Harbor Lights' invented TMDB id. */
        const val SHOW = 900002
    }
}

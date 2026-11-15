package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.TvIds

/**
 * The episodes either side of a downloaded one by the downloads alone (L354), for the player's Next, its card and Previous
 * when TMDB doesn't answer (offline, or failing): the row's own season and episode, and its season's episode count as the
 * Download sheet kept it ([Download.seasonEpisodes]). An episode not downloaded is never skipped for a later one that is.
 *
 * - Next is (season, episode + 1) while the season goes on by that count, or it says nothing; else the next season's
 *   first. Specials lead only to specials, and a regular season never on to them.
 * - One with a finished download in [done] plays from its file, as an episode built from its row ([episodeOf]): the
 *   coordinator's hook finds it by its watch key (L353).
 * - One not downloaded is the next only inside a season and offline, for the card to say it isn't downloaded; nothing past
 *   a season's end. Online, with TMDB failing, there is nothing to search for it by, so none, as before.
 * - Previous goes back the same way, only to a finished download: (season, episode − 1), and from a season's first the
 *   season before's last, as its own count says it is.
 */
internal object OfflineNext {
    /** The episode after [row]'s, as above; null for none, or a row that isn't a show's episode. */
    fun next(row: Download, done: Map<String, Download>, online: Boolean): EpisodePlay? {
        val (show, season, episode) = numbers(row) ?: return null
        val count = row.seasonEpisodes
        if (count == null || episode < count) {
            done[WatchKey.episode(show, season, episode + 1)]?.let { return episodeOf(it) }
            // Not downloaded: offline the card says so; the play has nothing else to go by.
            return if (online) null else row.notDownloaded(episode + 1)
        }
        // Specials end where they end: they never lead on to season 1.
        if (season == 0) return null
        return done[WatchKey.episode(show, season + 1, 1)]?.let(::episodeOf)
    }

    /** The episode before [row]'s, as above; null for none. */
    fun previous(row: Download, done: Map<String, Download>): EpisodePlay? {
        val (show, season, episode) = numbers(row) ?: return null
        if (episode > 1) return done[WatchKey.episode(show, season, episode - 1)]?.let(::episodeOf)
        // Season 1's first leads back to no special, and the specials' first to nothing.
        if (season <= 1) return null
        return done.values.firstOrNull { it.showTmdbId == show && it.season == season - 1 && it.episode != null && it.episode == it.seasonEpisodes }
            ?.let(::episodeOf)
    }

    /**
     * The episode [row]'s download is of, to play as TMDB's would be played: its show by its TMDB id, its numbers, name,
     * still and length as the row keeps them. Its file is what plays, so nothing searched by is missed.
     */
    fun episodeOf(row: Download): EpisodePlay? {
        val (show, season, episode) = numbers(row) ?: return null
        return EpisodePlay(
            showTmdbId = show, showName = row.title, showYear = null, aliases = emptyList(), ids = TvIds(tmdb = show), season = season, episode = episode,
            episodeName = row.episodeName, airDate = null, runtimeMinutes = row.runtimeMinutes, stillPath = row.stillPath, seasonEpisodes = row.seasonEpisodes,
        )
    }

    /** Episode [number] of this row's season, not downloaded: what the row says of its show and season alone. */
    private fun Download.notDownloaded(number: Int): EpisodePlay? =
        episodeOf(this)?.copy(episode = number, episodeName = null, runtimeMinutes = null, stillPath = null)

    /** A show's episode's TMDB show id, season and episode; null for a film's row. */
    private fun numbers(row: Download): Triple<Int, Int, Int>? {
        if (row.kind != DownloadKind.Episode) return null
        return Triple(row.showTmdbId ?: return null, row.season ?: return null, row.episode ?: return null)
    }
}

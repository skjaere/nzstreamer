package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.player.PlayUris

/**
 * The play of a finished download (L352, L353): the launch the coordinator's hooks send for its row, and which plays look
 * for one. Built from the row alone, so it needs no TMDB, indexer or provider: offline it plays as online.
 */
internal object DownloadLaunch {
    /**
     * Whether a play from [origin] plays a finished download where there is one: a play asked for on this device (a tap, or
     * what its player hands on: auto-advance, Previous and Next), or cast from it to a Google Cast device, whose file the
     * hook may cast (W121 c, L446); never a paired phone's (L353), or anything on a TV, which has no downloads (W108).
     */
    fun looks(origin: PlayOrigin, television: Boolean): Boolean = !television && origin.remote == null

    /**
     * [row]'s launch: its file by its `download://` URI, under the row's names and watch key, with the watch target a stream
     * of the title has (so progress and Up next are the same either way; an imported file's is a release's, so its progress
     * saves under its `release:` key, L423), the release its file came from with the file's name and exact size, no
     * request behind it (nothing to try another release from), from where the title's progress says, and what Resolve's
     * probe read of the video and its tracks (none where that can't be read). Null for a row with no release kept, which no
     * finished download is: the play then streams.
     */
    fun of(row: Download): PlayerLaunch? {
        val release = row.release ?: return null
        val nzbId = release.nzbId ?: return null
        val season = row.season
        val episode = row.episode
        val watch = when (row.kind) {
            DownloadKind.Episode -> WatchTarget(
                WatchKind.Episode, if (season != null && episode != null) WatchTitle.episode(row.title, season, episode) else row.title,
                showTmdbId = row.showTmdbId, season = season, episode = episode,
            )
            DownloadKind.Movie -> WatchTarget(WatchKind.Movie, row.title, movieTmdbId = row.movieTmdbId)
            // A file opened on Import (L423): no TMDB title, so its watch is the release's, named by the file. Never an Import
            // stream's launch, which saves nothing.
            DownloadKind.Release -> WatchTarget(WatchKind.Release, row.title)
        }
        return PlayerLaunch(
            uri = PlayUris.download(row.id),
            title = row.title,
            subtitle = row.subtitle,
            watchKey = row.watchKey,
            watch = watch,
            release = SavedRelease(
                release.indexerId, release.guid, release.title, release.downloadTemplate, release.fileIndex, nzbId,
                row.fileName ?: release.fileName, row.size ?: release.fileSize, release.source,
            ),
            requestId = null,
            video = readable { DownloadResolver.video(row) },
            tracks = readable { DownloadResolver.tracks(row) },
        )
    }

    /**
     * Whether [of] gives [row] a launch, told without building one (its probe's facts read): what every mark counts (L407),
     * many rows at a time, so a mark means the file plays from the row alone. [of]'s own first test.
     */
    fun accepts(row: Download): Boolean = row.release?.nzbId != null

    /**
     * What a cast of [row]'s launch shows of TMDB's (L451), from the row alone with no lookup, as a title's play says it
     * ([io.skjaere.usenet.app.link.TvPlayingDeps]): an episode's still, else its show's poster; a film's poster. None where
     * the row keeps neither.
     */
    fun art(row: Download): TvArt? =
        row.stillPath?.takeIf { row.kind == DownloadKind.Episode }?.let { TvArt(it) } ?: row.posterPath?.let { TvArt(it, poster = true) }

    /**
     * [known], the film a download's [launch] streams instead (L354), with the year and length its download's row says,
     * which the film's search and the ranker's estimate go by where TMDB doesn't answer. The row is read by the launch's
     * id through [rowOf] in any state, never among the finished ones: by the time Stream instead is tapped on "The
     * downloaded file is gone", the player's open has failed it ([DownloadStorage.fileGone]). As it is for a launch that
     * isn't a download's, or a row gone since.
     */
    suspend fun filmFacts(known: MovieTarget, launch: PlayerLaunch, rowOf: suspend (id: String) -> Download?): MovieTarget {
        val id = (PlayUris.parse(launch.uri) as? PlayUris.Target.Download)?.id ?: return known
        val row = rowOf(id) ?: return known
        return known.copy(year = row.year ?: known.year, runtimeMinutes = row.runtimeMinutes ?: known.runtimeMinutes)
    }

    /**
     * How a log line names [launch]'s release: a download's by its download's id, never by the release's name, as the
     * downloads' own lines do; a stream's by its title, as before.
     */
    fun releaseNamed(launch: PlayerLaunch): String =
        (PlayUris.parse(launch.uri) as? PlayUris.Target.Download)?.let { "the release of download ${it.id}" } ?: launch.release.title

    /** What [read] gives, or none where what the row keeps can't be read (a later version's form): never a failure at the tap. */
    private inline fun <T> readable(read: () -> T?): T? = try {
        read()
    } catch (e: IllegalArgumentException) {
        null
    }
}

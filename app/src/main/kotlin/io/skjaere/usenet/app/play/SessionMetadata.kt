package io.skjaere.usenet.app.play

import androidx.media3.common.MediaMetadata
import io.skjaere.usenet.app.casting.CastMetadata
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris

/**
 * What the media session says of the play (L17), which watches, car displays, the Assistant and other apps' controllers
 * read: [title] and [subtitle] as the top bar shows them for a play of TMDB's ([PlayerTitles]), the app's name alone for
 * any other, whose top bar shows a release's parsed name or a file's; by its watch key ([mediaId]), or [NO_KEY] for a file
 * opened without a launch. Never the stream's URI, which names the file, nor a release's name (M12). A torrent played
 * without TMDB, whose watch key holds its content id, is said by its URI's opaque places instead, `debrid:<listingId>:<index>`
 * (L181): never its hash. No artwork.
 */
data class SessionMetadata(val mediaId: String, val title: String, val subtitle: String?, val mediaType: Int) {
    fun metadata(): MediaMetadata = MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setMediaType(mediaType).build()

    companion object {
        /** The mediaId of a play without a watch key: an NZB opened as a file. */
        const val NO_KEY = "file"

        /**
         * What a cast's session says of its play before the device has the release (L63): the play asked for [watch], named
         * [title] and [subtitle], as the device's item will say its launch ([CastMetadata], L86, L89: a show over its
         * episode's line, where the player's top bar has them the other way round), so the words stay as they are when the
         * device takes over; the app's name alone for a play without TMDB, whose title is a release's name, by no key of
         * its own yet.
         */
        fun starting(title: String, subtitle: String?, watch: WatchTarget, appName: String): SessionMetadata =
            CastMetadata.starting(title, subtitle, watch, appName).let { SessionMetadata(watch.rememberedKey ?: NO_KEY, it.title, it.subtitle, it.mediaType) }

        /** What the session says of [launch] (null: a file opened without one), [appName] standing in for what isn't TMDB's. */
        fun of(launch: PlayerLaunch?, appName: String): SessionMetadata =
            launch?.let { said(it.watch, it.title, it.subtitle, mediaIdOf(it), appName) } ?: SessionMetadata(NO_KEY, appName, subtitle = null, MediaMetadata.MEDIA_TYPE_VIDEO)

        /** [launch]'s watch key, but for a debrid play without TMDB, whose key holds the torrent's hash: its URI's places. */
        private fun mediaIdOf(launch: PlayerLaunch): String {
            val service = PlayUris.parse(launch.uri) as? PlayUris.Target.Service
            return if (service != null && launch.watch.rememberedKey == null) "${PlayUris.SERVICE}:${service.listingId}:${service.fileIndex}" else launch.watchKey
        }

        private fun said(watch: WatchTarget, title: String, subtitle: String?, mediaId: String, appName: String): SessionMetadata {
            if (watch.rememberedKey == null) return SessionMetadata(mediaId, appName, subtitle = null, MediaMetadata.MEDIA_TYPE_VIDEO)
            val shown = PlayerTitles.of(watch.kind, title, subtitle)
            val type = if (watch.kind == WatchKind.Episode) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE
            return SessionMetadata(mediaId, shown.title, shown.under, type)
        }
    }
}

/** The player's top bar's title and the line [under] it ([PlayerActivity]'s). */
data class PlayerTitles(val title: String, val under: String?) {
    companion object {
        /**
         * For [launch]: an episode's name with its show under it, else the title and its line; for a file opened without a
         * launch, its [bareTitle].
         */
        fun of(launch: PlayerLaunch?, bareTitle: String?): PlayerTitles =
            launch?.let { of(it.watch.kind, it.title, it.subtitle) } ?: PlayerTitles(bareTitle.orEmpty(), null)

        /** For a play of [kind] named [title] and [subtitle] as its launch names it. */
        fun of(kind: WatchKind, title: String, subtitle: String?): PlayerTitles =
            if (kind == WatchKind.Episode && subtitle != null) PlayerTitles(subtitle, title) else PlayerTitles(title, subtitle)
    }
}

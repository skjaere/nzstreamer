package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.isntDownloaded
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.ReleaseName
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.EpisodeRef

/**
 * Home offline (L410): the line [LINE] under the recent searches, the Downloads section
 * before Up next, and Up next's rows [greyed] (by their keys) where they can't play: not downloaded, and not played by a
 * TV chosen, which searches for itself. A greyed row's tap says why in a bar ([bar]); its ⋮ is as it was. Pure, so the
 * JVM reads every case; Home only draws it.
 */
data class OfflineHome(val greyed: Set<String>) {
    companion object {
        /** The line under the recent searches, small and muted, as Discover's offline line looks. */
        const val LINE = "Offline · showing downloads"

        /**
         * Home offline for [upNext] with [downloads], Home's section, and the watch keys finished and playing from their
         * file ([downloadedKeys], Up next's marks'); null, Home as it was, unless [offline], on a phone or tablet (a TV,
         * and a phone's layout on one, are given no section and no keys) and with a finished download that plays. A row
         * not downloaded is greyed unless a TV chosen plays it there ([playOn], [playsOn]), as it searches for itself. A
         * Cast device chosen plays what this phone serves (L450): a downloaded row casts its file, but one not downloaded
         * the phone would have to stream, and can't, so it greys as with This phone.
         */
        fun of(upNext: List<UpNextRow>, downloads: HomeDownloads?, downloadedKeys: Set<String>, offline: Boolean, playOn: PlayTarget?): OfflineHome? {
            if (!offline || downloads == null || downloadedKeys.isEmpty()) return null
            return OfflineHome(upNext.filter { it.watchKey !in downloadedKeys && it.playsOn(playOn) !is PlayTarget.Tv }.mapTo(HashSet()) { it.key })
        }

        /**
         * The bar a greyed [row]'s tap shows: "Quiet Meridian S01E03 isn't downloaded. It plays once you're online.", a
         * film's by its title alone, a release played without TMDB by what its name says, as its Play names it.
         */
        fun bar(row: UpNextRow): String = isntDownloaded(named(row))

        private fun named(row: UpNextRow): String = when (val play = row.play) {
            is UpNextPlay.NextEpisode -> "${row.title} ${play.episode.label}"
            is UpNextPlay.Resume -> {
                val state = play.state
                val season = state.season
                val episode = state.episode
                when {
                    state.kind == WatchKind.Episode && season != null && episode != null -> "${row.title} ${EpisodeRef(season, episode).label}"
                    state.kind == WatchKind.Release -> ReleaseName(state.title).query
                    else -> row.title
                }
            }
        }
    }
}

package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.DownloadsPage
import io.skjaere.usenet.app.download.DownloadsRow
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.isntDownloaded
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Show page offline (L439), where downloads show and the app reads as [offline] (the container's
 * signal, L438), with Play going to [playOn]. Pure, so the JVM reads every case; the page only draws it. Online every
 * answer is the page as it was.
 */
internal data class ShowOffline(val offline: Boolean, val playOn: PlayTarget) {
    /**
     * Whether an episode with [download] (the page's, its latest) is greyed: offline, it has no finished download its file
     * plays from ([TitleDownload.plays], as the play hook takes it), and no TV is chosen, which searches for an episode
     * itself, as Home's [io.skjaere.usenet.app.home.playsOn] says of an episode of a show TMDB knows. A Cast device chosen
     * plays what the phone serves (L450): a downloaded episode casts its file, one not downloaded the phone would have to
     * stream, and can't, so it greys as with This phone.
     */
    fun greyed(download: TitleDownload?): Boolean = offline && playOn !is PlayTarget.Tv && download?.plays != true

    /** Whether the header's Play or Resume of [next] greys, as its episode's row does, by the page's [downloads]. */
    fun playGreyed(next: NextUp, downloads: Map<Pair<Int, Int>, TitleDownload>): Boolean =
        next.episodeRef()?.let { greyed(downloads[it.season to it.episode]) } == true

    /** The season's Download: greyed offline, whatever Play on says, as its sheet searches this phone's indexers first. */
    val seasonDownloadEnabled: Boolean get() = !offline

    /**
     * Whether an episode's ⋮ [item] can be picked: offline, Choose release and Download search the indexers, so they grey;
     * Mark watched or unwatched and the download's own entries (Remove, Pause, Resume, Download again) are kept.
     */
    fun enabled(item: EpisodeMenuItem): Boolean =
        !offline || (item != EpisodeMenuItem.ChooseRelease && item != EpisodeMenuItem.Download(DownloadEntry.Download))

    companion object {
        /** The line under the page's bar while offline, staying as the page scrolls. */
        const val LINE = "Offline · only downloaded episodes play"

        /** What a greyed episode's tap says: "Harbor Lights S02E05 isn't downloaded. It plays once you're online." */
        fun bar(show: String, e: Episode): String = isntDownloaded("$show ${EpisodeRef(e.season, e.episode).label}")

        /**
         * Of the show's downloads as the runner tells them ([items], with each chunk the writer writes), its finished ones
         * alone, told again only as they change: what [downloaded] needs, read at the page's pace, not the writer's.
         */
        fun finished(items: Flow<List<DownloadRunner.Item>>): Flow<List<DownloadRunner.Item>> =
            items.map { all -> all.filter { it.row.state == DownloadState.Done } }.distinctUntilChanged()

        /**
         * The page with no answer from TMDB (L439): show [showTmdbId]'s finished downloads among [items] (the runner's for
         * the show, which need no TMDB) as one row, as the Downloads page draws it with what plays next by [states]; null
         * without one, when the page shows its error alone.
         */
        fun downloaded(showTmdbId: Int, items: List<DownloadRunner.Item>, states: Map<Pair<Int, Int>, WatchState>): DownloadsRow.Show? =
            DownloadsPage.of(items, episodeStates = mapOf(showTmdbId to states)).downloaded
                .filterIsInstance<DownloadsRow.Show>().firstOrNull { it.showTmdbId == showTmdbId }
    }
}

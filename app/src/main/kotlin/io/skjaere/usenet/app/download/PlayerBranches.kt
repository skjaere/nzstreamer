package io.skjaere.usenet.app.download

import androidx.media3.common.C
import io.skjaere.usenet.app.play.PlayerCast
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.player.PlayUris

/**
 * What the player does otherwise while it plays a finished download's file (L354, L377): the plain decisions its branches
 * take, each keyed on the launch's URI naming one ([isDownload]). A file on this device is no stream: the player keeps the
 * CPU awake but holds no Wi-Fi lock, watches no stream's speed, logs no stream's cost, and remembers nothing as missing on
 * the providers, which its play never asked. What needs the network, the title's stream ([Other.StreamInstead]) and
 * TheIntroDB, is offered or asked only while the device's network is validated. Its file isn't cast in DN1, so the Cast
 * button doesn't show. The next episode's line on the card comes from the downloads, with no search ([nextStatus]).
 */
internal object PlayerBranches {
    /** Whether [uri] names a finished download's file: `download://<id>` ([PlayUris.Target.Download]). */
    fun isDownload(uri: String?): Boolean = uri != null && PlayUris.parse(uri) is PlayUris.Target.Download

    /** What the player keeps awake while it plays: the CPU alone for a download's file, a stream's Wi-Fi lock with it otherwise. */
    fun wakeMode(download: Boolean): Int = if (download) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK

    /** Whether the slow-stream banner watches the play: a file on this device comes as fast as it is read. */
    fun watchesDips(download: Boolean): Boolean = !download

    /** Whether the stream-cost line is logged while it plays: a download's play streams nothing, so it would log zeros. */
    fun logsStreamCost(download: Boolean): Boolean = !download

    /**
     * Whether a part the play can't go past is remembered as missing on the providers in use: never for a download's file,
     * whose gaps are those its download found, on the providers of then, which its runner remembered itself (L342).
     */
    fun remembersMissing(download: Boolean): Boolean = !download

    /** Whether the top bar's Cast button shows: on a phone or a tablet ([PlayerCast.shows]), but not while a download plays (L377). */
    fun castShown(television: Boolean, download: Boolean): Boolean = PlayerCast.shows(television) && !download

    /** Whether TheIntroDB is asked for the episode's segments: for a download only while [online]; its file's chapters work offline. */
    fun asksIntroDb(download: Boolean, online: Boolean): Boolean = !download || online

    /** What "Playback stopped" and the missing-parts prompt offer beside Close and Keep watching. */
    enum class Other { None, TryAnother, StreamInstead }

    /**
     * What is offered in place of the play: for a download, its title by its stream path ("Stream instead"), only while
     * [online], as it has no request behind it to try another release of; else another release while [canTryAnother].
     */
    fun other(download: Boolean, online: Boolean, canTryAnother: Boolean): Other = when {
        download -> if (online) Other.StreamInstead else Other.None
        canTryAnother -> Other.TryAnother
        else -> Other.None
    }

    /**
     * Whether the next episode is looked for as online ([nextStatus], [OfflineNext.next]): a download's play while the
     * network is validated ([online]); a stream's always, as the stream playing shows it has a network whatever Android
     * says of it (a Wi-Fi that blocks Android's check, a handover's moments), so its next is searched for as before.
     */
    fun nextOnline(download: Boolean, online: Boolean): Boolean = !download || online

    /**
     * The next episode's line on the card from the downloads alone, with no search (L354): [Prefetch.Downloaded] where its
     * watch [key] has a finished download in [done], which the coordinator's hook plays from its file, with the parts its
     * download had no article for. While not [online], [Prefetch.StillDownloading] where one of it is on its way among the
     * runner's [items], by how much of it is written (of its exact size, else the indexer's), else
     * [Prefetch.NotDownloadedOffline]: neither can play. Null online for one not downloaded or not yet complete: the card
     * searches for it, and it streams, as before. Never null for a [standIn], an episode the playing one's download row
     * named as TMDB couldn't be asked ([OfflineNext]): it has nothing to be searched by (no year, no other titles), so with
     * the network back it stays as it was offline.
     */
    fun nextStatus(key: String, online: Boolean, done: Map<String, Download>, items: List<DownloadRunner.Item>, standIn: Boolean = false): Prefetch? {
        done[key]?.let { return Prefetch.Downloaded(it.gaps.size) }
        if (online && !standIn) return null
        val coming = items.firstOrNull { it.row.watchKey == key && it.row.state in ON_ITS_WAY } ?: return Prefetch.NotDownloadedOffline
        val size = coming.row.size ?: coming.row.indexerSize
        val percent = if (size > 0) (coming.written * 100 / size).toInt().coerceIn(0, 99) else 0
        return Prefetch.StillDownloading(percent)
    }

    /**
     * Whether Previous plays the episode before, by its watch [key]: while [online] ([nextOnline]) as before, streamed or
     * from its file; offline only one with a finished download in [done], from its file, as no stream can start then.
     * Otherwise Previous starts the playing one again, as with no episode before.
     */
    fun previousPlays(key: String, online: Boolean, done: Map<String, Download>): Boolean = online || key in done

    /**
     * Whether Stream instead leaves the download's own release out of the stream: after its missing parts (a gap its file
     * can't go past, the missing-parts prompt) or a fault of its own, as the stream would meet the same; not after its
     * [fileGone], which says nothing of the release, so it goes first as the one that played the title.
     */
    fun streamLeavesOut(fileGone: Boolean): Boolean = !fileGone

    /** A download not yet finished but still to finish: queued, under way, waiting, or held until Resume. */
    private val ON_ITS_WAY = setOf(
        DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Waiting, DownloadState.Paused, DownloadState.Stopped,
    )
}

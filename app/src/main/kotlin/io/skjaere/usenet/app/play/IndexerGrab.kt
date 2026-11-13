package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import kotlinx.coroutines.delay

/**
 * The NZBs of a play's [items] (by candidate id), each downloaded from its indexer (of [indexers], by id) with
 * [download], with the archive password the indexer sent beside it, if any.
 */
internal fun indexerGrab(
    items: Map<String, NewznabItem>,
    indexers: Map<String, IndexerConfig>,
    download: suspend (IndexerConfig, NewznabItem) -> NzbDownload,
): NzbSource = object : NzbSource {
    override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

    override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb {
        val item = items.getValue(candidate.id)
        val indexer = indexers[item.indexerId] ?: throw FetchException("Indexer no longer set up", stopsAll = false)
        val nzb = try {
            try {
                download(indexer, item)
            } catch (e: NewznabException) {
                // A dropped connection or DNS hiccup is usually gone a second later.
                if (e.error !is NewznabError.Network && e.error !is NewznabError.Timeout) throw e
                delay(1_000)
                download(indexer, item)
            }
        } catch (e: NewznabException) {
            // A used-up download allowance or a bad key fails every release from that indexer alike;
            // the pipeline skips its other releases and moves on to the other indexers'.
            throw FetchException(e.error.message, blocksIndexer = e.error is NewznabError.DownloadLimitReached || e.error is NewznabError.Auth, cause = e)
        }
        return FetchedNzb(nzb.bytes, nzb.password)
    }
}

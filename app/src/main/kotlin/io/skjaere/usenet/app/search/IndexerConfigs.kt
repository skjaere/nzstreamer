package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind

/** The indexers a search asks, as the container gives them to Search, the episode finder and a film's page. */
object IndexerConfigs {
    /**
     * Whether a search asks any indexer: one of the Usenet indexers ([newznab]) switched on, or one of the torrent indexers
     * ([torznab]) while a service account is on ([serviceOn], which debrid being hidden never is), so a debrid-only user
     * isn't told to add an indexer (L194).
     */
    fun anySearched(newznab: List<IndexerRecord>, torznab: List<TorrentIndexerRecord>, serviceOn: Boolean): Boolean =
        newznab.any { it.enabled } || serviceOn && torznab.any { it.enabled }

    /**
     * The Usenet indexers ([newznab]), then the torrent indexers ([torznab]) while a service account is on ([serviceOn])
     * and debrid shows ([shown]): without a service their results could play from nowhere (L166), and a build with
     * debrid hidden searches none of what a shown one saved (L192). Each is read as its kind, and a torrent indexer
     * carries its "Use with your debrid service". A key that can't be read goes empty, as the search then reports.
     */
    fun searchable(newznab: List<IndexerWithKey>, torznab: List<TorrentIndexerWithKey>, serviceOn: Boolean, shown: Boolean): List<IndexerConfig> =
        newznab.map { IndexerConfig(it.record.id, it.record.name, it.record.baseUrl, it.apiKey.orEmpty(), it.record.enabled) } +
            torznab.takeIf { serviceOn && shown }.orEmpty().map {
                IndexerConfig(
                    it.record.id, it.record.name, it.record.baseUrl, it.apiKey.orEmpty(), it.record.enabled,
                    kind = IndexerKind.Torznab, useWithService = it.record.useWithService,
                )
            }

    /**
     * The torrent indexers whose "Use with your debrid service" is off, by id, from every one [saved]: enabled or not, its
     * key readable or not, and removed since, whose tombstone keeps the switch as it last was. The switch is the user's
     * "no" (L194), whether a search asks the indexer now or not: a release of theirs, remembered or found, goes to no
     * service (DDC-2). None while debrid is hidden ([shown], L192): a play there leaves nothing out in the service's words,
     * and with no account on, a torrent has no route anyway, so nothing of it goes to a service (DDC-2b).
     */
    fun offService(saved: AppSettings, shown: Boolean = DebridGate.shown): Set<String> =
        if (!shown) emptySet() else saved.torrentIndexers.filterNot { it.useWithService }.mapTo(mutableSetOf()) { it.id }
}

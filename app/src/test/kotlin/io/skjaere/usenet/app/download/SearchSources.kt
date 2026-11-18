package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.newznab.IndexerConfig
import kotlinx.coroutines.CoroutineDispatcher

/**
 * What a download's searches read their indexers from (L422, L459), as the app keeps it, in memory: Usenet's Indexer A and
 * the torrent indexer Home Prowlarr saved, and TorBox's account signed in where a case asks for it; the service registry
 * over them makes no client, as nothing here calls a service. [indexers] is what the container gives a search
 * ([IndexerConfigs.of]), with debrid shown or hidden. Invented indexers, addresses and keys.
 */
internal class SearchSources private constructor(val settings: SettingsRepository, private val services: ServiceRegistry) {
    /** The indexers a search asks now, debrid [shown] or hidden. */
    suspend fun indexers(shown: Boolean): List<IndexerConfig> = IndexerConfigs.of(settings, services, shown)

    companion object {
        const val USENET = "Indexer A"
        const val TORZNAB = "Home Prowlarr"

        /** The two indexers saved, TorBox signed in where [account], their secrets sealed on [secrets] (a test's own clock). */
        suspend fun of(secrets: CoroutineDispatcher, account: Boolean): SearchSources {
            val settings = memorySettings(secrets)
            settings.saveIndexer(null, USENET, "https://indexer-a.invalid/api", "key-a")
            settings.saveTorrentIndexer(null, TORZNAB, "https://prowlarr.invalid/1", "key-p")
            if (account) settings.saveService(ServiceKind.TorBox.name, "tb-key") { ServiceSignIn("viewer@example.invalid") }
            val services = ServiceRegistry(settings::servicesWithKeys, settings::markKeyRefused, make = { _, _, _, _, _ -> error("no client is made") })
            return SearchSources(settings, services)
        }
    }
}

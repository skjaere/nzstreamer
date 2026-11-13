package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.Need
import io.skjaere.usenet.link.Rejection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * What a TV has set up, as its readiness reads it (L224): the providers saved and how many of them are usable (switched
 * on, their passwords readable), the Newznab indexers, whether it has a TMDB key, and its service accounts, with their
 * keys, and torrent indexers, in the user's order. Deleted ones are left out.
 */
data class TvSetup(
    val providers: List<ProviderRecord> = emptyList(),
    val usableProviders: Int = 0,
    val indexers: List<IndexerRecord> = emptyList(),
    val tmdb: Boolean = false,
    val services: List<ServiceWithKey> = emptyList(),
    val torrentIndexers: List<TorrentIndexerRecord> = emptyList(),
) {
    // The records hold hosts and names, the keys are said only as set or not: never the setup itself in a log line.
    override fun toString() = "TvSetup(providers=${providers.size}, usable=$usableProviders, services=${services.size})"

    companion object {
        /** [settings]' setup as it is now. */
        suspend fun read(settings: SettingsRepository): TvSetup {
            val saved = settings.settings.first()
            return TvSetup(
                saved.activeProviders, settings.usableProviders().size, saved.activeIndexers, settings.hasTmdbKey.first(),
                settings.servicesWithKeys(), saved.activeTorrentIndexers,
            )
        }
    }
}

/**
 * Whether a TV can play a paired phone's play (L224): one rule, for the hello a paired phone gets ([hello]) and for each
 * Play ([rejection]). By Usenet: a usable [provider] and a Newznab [indexer] switched on. By its debrid service ([debrid]):
 * an account on, as Search and the plays count one (a limit's closed gate still counts, and the play's sheet says so),
 * and a torrent indexer switched on, as Search counts one, while debrid shows. Hidden, only Usenet's way counts (L254).
 *
 * When neither way plays, [lacks] says what comes first. The debrid way's lack, while it alone is under way (a service
 * or a torrent indexer saved, and no provider and no Newznab indexer saved), as First run's next step goes; otherwise
 * Usenet's, also when both or neither are started (W81). The hello and the refusal name no service or account (L223);
 * [refusedBy] names the service whose key was refused, for the TV's own sheet alone (L248).
 */
data class TvReadiness(
    val provider: Boolean,
    val indexer: Boolean,
    val tmdb: Boolean,
    val debrid: Boolean,
    val lacks: Lack?,
    val refusedBy: String? = null,
) {
    /** What a TV lacks first for a phone's play: Usenet's two parts, or the debrid way's three. */
    enum class Lack { Provider, Indexer, Service, ServiceOff, TorrentIndexer }

    val ready: Boolean get() = lacks == null

    /** What a paired phone's hello says (L25, L223): Usenet's parts as they are, the debrid way, and its lack if it is the one. */
    val hello: Hello.Ready
        get() = Hello.Ready(
            provider, indexer, tmdb, debrid,
            needs = when (lacks) {
                Lack.Service -> Need.Service
                Lack.ServiceOff -> Need.ServiceOff
                Lack.TorrentIndexer -> Need.TorrentIndexer
                Lack.Provider, Lack.Indexer, null -> null
            },
        )

    /** A phone's Play refused for what lacks first; null while the TV can play. */
    val rejection: Rejection?
        get() = when (lacks) {
            Lack.Provider -> Rejection.NotReadyProvider
            Lack.Indexer -> Rejection.NotReadyIndexer
            Lack.Service -> Rejection.NotReadyService
            Lack.ServiceOff -> Rejection.NotReadyServiceOff
            Lack.TorrentIndexer -> Rejection.NotReadyTorrentIndexer
            null -> null
        }

    companion object {
        /** [setup]'s readiness, the debrid way counted only while it is [shown] (L192). */
        fun of(setup: TvSetup, shown: Boolean): TvReadiness {
            val provider = setup.usableProviders > 0
            val indexer = setup.indexers.any { it.enabled }
            val services = if (shown) setup.services else emptyList()
            val torrentIndexers = if (shown) setup.torrentIndexers else emptyList()
            val serviceOn = services.any(ServiceRegistry::isOn)
            val debrid = serviceOn && torrentIndexers.any { it.enabled }
            val usenetStarted = setup.providers.isNotEmpty() || setup.indexers.isNotEmpty()
            val debridStarted = services.isNotEmpty() || torrentIndexers.isNotEmpty()
            val lacks = when {
                provider && indexer || debrid -> null
                usenetStarted || !debridStarted -> if (!provider) Lack.Provider else Lack.Indexer
                services.isEmpty() -> Lack.Service
                !serviceOn -> Lack.ServiceOff
                else -> Lack.TorrentIndexer
            }
            // Of the accounts switched on, the first whose key the service refused: the TV's sheet says where to replace it.
            val refusedBy = if (lacks != Lack.ServiceOff) {
                null
            } else {
                services.filter { it.record.enabled && it.record.keyRefused }.firstNotNullOfOrNull { ServiceRegistry.kindOf(it.record) }?.displayName
            }
            return TvReadiness(provider, indexer, setup.tmdb, debrid, lacks, refusedBy)
        }

        /** The hello's readiness as [settings] change: a sign-in, a switch, a key the service refused, a TMDB key. */
        fun hellos(settings: SettingsRepository, shown: () -> Boolean = { DebridGate.shown }): Flow<Hello.Ready> =
            combine(settings.settings, settings.hasTmdbKey) { _, _ -> of(TvSetup.read(settings), shown()).hello }.distinctUntilChanged()
    }
}

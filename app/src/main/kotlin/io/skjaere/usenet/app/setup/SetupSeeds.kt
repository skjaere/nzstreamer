package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.kindOf
import io.skjaere.usenet.app.settings.providerForm
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.SavedIndexer
import io.skjaere.usenet.handoff.SavedProvider
import io.skjaere.usenet.handoff.SavedSecret
import io.skjaere.usenet.handoff.SavedService
import io.skjaere.usenet.handoff.SavedTorrentIndexer
import io.skjaere.usenet.handoff.ServiceChoice
import io.skjaere.usenet.handoff.SetupSeed
import kotlinx.coroutines.flow.first

/**
 * What is saved, as a session's page starts from it: every provider in the order they are tried and every indexer,
 * switched off or not, with what an untouched card sends back (a provider named as its host has no name, as its form
 * shows it), an indexer's address without its login, query or fragment ([shownAddress]), and of each password or key
 * only whether one is saved and can be read here. The record ids go as the refs, which stay on the TV.
 *
 * While debrid is [shown] (L209), the page's debrid sections too: the services it offers, A to Z, each account of a
 * service this version knows (its name as the service gave it), and each torrent indexer as an indexer, with its switch.
 * Hidden (L192), nothing of them, so the page has no debrid card and its server refuses their slots.
 */
internal suspend fun SettingsRepository.setupSeed(shown: Boolean = DebridGate.shown): SetupSeed {
    val providers = providersWithPasswords.first().map { p ->
        val r = p.record
        SavedProvider(r.id, providerForm(p).name, r.host, r.port, r.useTls, r.username, r.connections, r.enabled, login(p))
    }
    val indexers = indexers.first().map { r ->
        val address = shownAddress(r.baseUrl)
        SavedIndexer(r.id, r.name, address.url, address.hiddenParts, r.enabled, keyState(indexer(r.id)?.apiKey) { indexerKeyStored(r.id) })
    }
    val tmdb = when {
        tmdbKey() != null -> SavedSecret.Saved
        tmdbKeyStored() -> SavedSecret.Unreadable
        else -> SavedSecret.None
    }
    if (!shown) return SetupSeed(providers, indexers, tmdb)
    val services = servicesWithKeys().mapNotNull { s ->
        val kind = kindOf(s.record) ?: return@mapNotNull null
        SavedService(s.record.id, kind.choice, s.record.accountName, s.record.enabled, keyState(s.apiKey) { serviceKeyStored(s.record.id) })
    }
    val torrentIndexers = torrentIndexers.first().map { r ->
        val address = shownAddress(r.baseUrl)
        val key = keyState(torrentIndexer(r.id)?.apiKey) { torrentIndexerKeyStored(r.id) }
        SavedTorrentIndexer(r.id, r.name, address.url, address.hiddenParts, r.enabled, r.useWithService, key)
    }
    return SetupSeed(providers, indexers, tmdb, SERVICE_CHOICES, services, torrentIndexers)
}

/** A key as the page may say it: saved and readable, stored but unreadable here ([stored]), or none. */
private suspend fun keyState(key: String?, stored: suspend () -> Boolean): SavedSecret = when {
    !key.isNullOrEmpty() -> SavedSecret.Saved
    key == null && stored() -> SavedSecret.Unreadable
    else -> SavedSecret.None
}

/** The debrid services the setup page offers, A to Z, each as its radio sends it and with where it keeps its key (L209). */
val SERVICE_CHOICES: List<ServiceChoice> = ServiceKind.entries.sortedBy { it.displayName }.map { ServiceChoice(it.choice, it.displayName, it.keyPlace) }

/** A service as the setup page's radio sends it: its name in lower case. */
val ServiceKind.choice: String get() = name.lowercase()

/** The service a setup page's radio sent, or null for one this version doesn't know. */
fun serviceKindOf(choice: String): ServiceKind? = ServiceKind.entries.firstOrNull { it.choice == choice }

/** A provider's login as the page may say it: none without a username (or with an empty password), saved, or unreadable. */
private fun login(p: ProviderWithPassword): SavedSecret = when {
    p.record.username.isBlank() -> SavedSecret.None
    p.password == null -> SavedSecret.Unreadable
    p.password.isNullOrEmpty() -> SavedSecret.None
    else -> SavedSecret.Saved
}

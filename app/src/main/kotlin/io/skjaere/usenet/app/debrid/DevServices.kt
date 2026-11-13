package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.DevIndexers
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first

/**
 * Debug builds' debrid accounts from local.properties (devTorBoxKey, devPremiumizeKey; L182, W77), as the build encodes
 * them into BuildConfig.DEV_SERVICE_KEYS: one line per service, its name and its key separated by a tab. Every other
 * build type has none. One for a service this version doesn't know, or without a key, is left out.
 */
object DevServices {
    data class Entry(val kind: ServiceKind, val key: String) {
        override fun toString() = "Entry($kind)"
    }

    fun parse(encoded: String): List<Entry> =
        encoded.lineSequence()
            .map { it.split('\t').map(String::trim) }
            .mapNotNull { fields ->
                val kind = ServiceKind.entries.firstOrNull { it.name == fields.firstOrNull() }
                val key = fields.getOrNull(1).orEmpty()
                if (kind != null && key.isNotEmpty()) Entry(kind, key) else null
            }
            .toList()

    /**
     * Test seam: whether debug's debrid seeding runs. The device tests' runner turns it off before the app starts unless
     * asked for the live accounts (L182), so a device run never saves the developer's real accounts or torrent indexers.
     */
    @VisibleForTesting
    @Volatile
    internal var seeding: Boolean = true
}

/**
 * Saves the debug build's dev accounts ([services], DevServices' encoding) and torrent indexers ([torrentIndexers],
 * DevIndexers') while none was ever saved: one signed out of or removed leaves its record behind, so neither comes back
 * (L182). The accounts are saved untested, so nothing calls a service as the app starts; their page's Test names the
 * account. Nothing while the seeding is off ([enabled], device runs) or debrid hidden ([shown], L192).
 */
internal suspend fun seedDevDebrid(
    settings: SettingsRepository,
    services: String = BuildConfig.DEV_SERVICE_KEYS,
    torrentIndexers: String = BuildConfig.DEV_TORRENT_INDEXERS,
    enabled: Boolean = DevServices.seeding,
    shown: Boolean = DebridGate.shown,
) {
    if (!enabled || !shown) return
    val saved = settings.settings.first()
    if (saved.services.isEmpty()) {
        DevServices.parse(services).forEach { settings.saveService(it.kind.name, it.key) { ServiceSignIn(accountName = "") } }
    }
    if (saved.torrentIndexers.isEmpty()) {
        DevIndexers.parse(torrentIndexers).forEach { settings.saveTorrentIndexer(null, it.name, it.baseUrl, it.apiKey) }
    }
}

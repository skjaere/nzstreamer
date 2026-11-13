package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Debug builds' debrid accounts and torrent indexers from local.properties (L182, W77), as the build encodes them into
 * BuildConfig (DEV_SERVICE_KEYS: a service's name and its key a line; DEV_TORRENT_INDEXERS: as DEV_INDEXERS), and their
 * seeding: while none was ever saved, debrid shown and the seeding on (device runs turn it off). Invented keys.
 */
class DevServicesTest {
    @Test
    fun `the encoded keys parse, a service and its key a line`() {
        assertEquals(
            listOf(DevServices.Entry(ServiceKind.TorBox, "tb-dev-key"), DevServices.Entry(ServiceKind.Premiumize, "pm-dev-key")),
            DevServices.parse("TorBox\ttb-dev-key\nPremiumize\tpm-dev-key"),
        )
    }

    @Test
    fun `an empty value, an unknown service or nothing configured gives no account`() {
        assertEquals(emptyList(), DevServices.parse(""))
        assertEquals(emptyList(), DevServices.parse("TorBox\t\nPremiumize\t  \n\n"))
        assertEquals(listOf(DevServices.Entry(ServiceKind.TorBox, "tb-dev-key")), DevServices.parse("SomeLaterService\tk1\nTorBox\t tb-dev-key "))
    }

    @Test
    fun `seeding saves the accounts untested and the torrent indexers while none was ever saved`() = runBlocking<Unit> {
        val settings = memorySettings()
        seedDevDebrid(settings, "TorBox\ttb-dev-key", "Quarry\thttp://192.0.2.7:9696/1\tquarry-dev-key", enabled = true, shown = true)
        val account = settings.servicesWithKeys().single()
        assertEquals("TorBox", account.record.kind)
        assertEquals("", account.record.accountName, "seeded untested: no call at start")
        assertEquals("tb-dev-key", account.apiKey)
        val indexer = settings.torrentIndexers.first().single()
        assertEquals("Quarry", indexer.name)
        assertEquals("quarry-dev-key", settings.torrentIndexer(indexer.id)?.apiKey)

        // Signed out of and removed: neither comes back on a later start.
        settings.signOut(account.record.id)
        settings.deleteTorrentIndexer(indexer.id)
        seedDevDebrid(settings, "TorBox\ttb-dev-key", "Quarry\thttp://192.0.2.7:9696/1\tquarry-dev-key", enabled = true, shown = true)
        assertEquals(emptyList(), settings.services.first())
        assertEquals(emptyList(), settings.torrentIndexers.first())
    }

    @Test
    fun `an empty value seeds nothing`() = runBlocking<Unit> {
        val settings = memorySettings()
        seedDevDebrid(settings, "", "", enabled = true, shown = true)
        seedDevDebrid(settings, "TorBox\t", "Quarry\thttp://192.0.2.7:9696/1\t", enabled = true, shown = true)
        assertEquals(emptyList(), settings.settings.first().services)
        assertEquals(emptyList(), settings.settings.first().torrentIndexers)
    }

    /** L182: device runs turn the seeding off, so they never save the real accounts; L192: hidden, nothing of debrid is seeded. */
    @Test
    fun `nothing is seeded while the seeding is off or debrid is hidden`() = runBlocking<Unit> {
        val settings = memorySettings()
        seedDevDebrid(settings, "TorBox\ttb-dev-key", "Quarry\thttp://192.0.2.7:9696/1\tquarry-dev-key", enabled = false, shown = true)
        seedDevDebrid(settings, "TorBox\ttb-dev-key", "Quarry\thttp://192.0.2.7:9696/1\tquarry-dev-key", enabled = true, shown = false)
        assertEquals(emptyList(), settings.settings.first().services)
        assertEquals(emptyList(), settings.settings.first().torrentIndexers)
    }
}

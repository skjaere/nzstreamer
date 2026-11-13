package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.link.TvReadiness.Lack
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.link.LinkCodec
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.Need
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.link.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L224: one rule says whether a TV can play a paired phone's play, for its hello and for each Play. Usenet's way counts a
 * usable provider and a Newznab indexer switched on; the debrid way an account on and a torrent indexer switched on,
 * while debrid shows. Neither: what lacks first, Usenet's while Usenet's way is under way or neither is (W81). What
 * crosses names no service or account (L223); a refused key's service is named for the TV's own sheet alone (L248). The
 * accounts and names are invented.
 */
class TvReadinessTest {
    private val provider = ProviderRecord("p1", "Quay News", "news.quay.invalid")
    private val newznab = IndexerRecord("i1", "Quay Indexer", "https://indexer.quay.invalid/api")
    private val torbox = ServiceWithKey(ServiceAccount("tb", "TorBox", accountName = "quay.viewer@example.org"), "TB-KEY")
    private val premiumize = ServiceWithKey(ServiceAccount("pm", "Premiumize"), "PM-KEY")
    private val prowlarr = TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1/api")

    private fun of(
        providers: List<ProviderRecord> = emptyList(),
        usable: Int = providers.count { it.enabled },
        indexers: List<IndexerRecord> = emptyList(),
        services: List<ServiceWithKey> = emptyList(),
        torrents: List<TorrentIndexerRecord> = emptyList(),
        tmdb: Boolean = false,
        shown: Boolean = true,
    ) = TvReadiness.of(TvSetup(providers, usable, indexers, tmdb, services, torrents), shown)

    private fun bytes(ready: Hello.Ready) = String(LinkCodec.encode(Hello(1, 1, Role.Tv, "Living room TV", ready = ready))!!)

    /** What each TV has, whether it plays and by which way, or what it lacks first. */
    @Test
    fun `each TV of the table plays by its way, or lacks what comes first`() {
        class Row(val has: String, val readiness: TvReadiness, val lacks: Lack?, val debrid: Boolean)
        val rows = listOf(
            Row("nothing", of(), Lack.Provider, debrid = false),
            Row("a provider and a Newznab indexer", of(listOf(provider), indexers = listOf(newznab)), null, debrid = false),
            Row("a service on and a torrent indexer, nothing of Usenet", of(services = listOf(torbox), torrents = listOf(prowlarr)), null, debrid = true),
            Row(
                "a provider, a service on and a torrent indexer, no Newznab indexer",
                of(listOf(provider), services = listOf(torbox), torrents = listOf(prowlarr)), null, debrid = true,
            ),
            Row(
                "a provider switched off, a Newznab indexer, a service on and a torrent indexer",
                of(listOf(provider.copy(enabled = false)), indexers = listOf(newznab), services = listOf(torbox), torrents = listOf(prowlarr)), null, debrid = true,
            ),
            Row("a service on, no torrent indexer, nothing of Usenet", of(services = listOf(torbox)), Lack.TorrentIndexer, debrid = false),
            Row("a torrent indexer, no service, nothing of Usenet", of(torrents = listOf(prowlarr)), Lack.Service, debrid = false),
            Row(
                "a service saved but switched off, and a torrent indexer",
                of(services = listOf(torbox.copy(record = torbox.record.copy(enabled = false))), torrents = listOf(prowlarr)), Lack.ServiceOff, debrid = false,
            ),
            Row(
                "a provider, no Newznab indexer, a service on, no torrent indexer: Usenet is under way",
                of(listOf(provider), services = listOf(torbox)), Lack.Indexer, debrid = false,
            ),
            Row(
                "a Newznab indexer, no provider, a torrent indexer, no service: Usenet is under way",
                of(indexers = listOf(newznab), torrents = listOf(prowlarr)), Lack.Provider, debrid = false,
            ),
            Row(
                "a provider switched off, a torrent indexer, no service: Usenet is under way, saved though off",
                of(listOf(provider.copy(enabled = false)), torrents = listOf(prowlarr)), Lack.Provider, debrid = false,
            ),
        )
        for (row in rows) {
            assertEquals(row.lacks, row.readiness.lacks, row.has)
            assertEquals(row.lacks == null, row.readiness.ready, row.has)
            assertEquals(row.debrid, row.readiness.debrid, row.has)
            assertEquals(row.debrid, row.readiness.hello.debrid, row.has)
        }
    }

    /** L223: each lack is refused in its own word on the wire, and the hello names what the debrid way lacks, nothing more. */
    @Test
    fun `each lack has its refusal, and the hello says what the debrid way lacks`() {
        val cases = listOf(
            of() to Rejection.NotReadyProvider,
            of(listOf(provider)) to Rejection.NotReadyIndexer,
            of(torrents = listOf(prowlarr)) to Rejection.NotReadyService,
            of(services = listOf(torbox.copy(apiKey = null)), torrents = listOf(prowlarr)) to Rejection.NotReadyServiceOff,
            of(services = listOf(torbox)) to Rejection.NotReadyTorrentIndexer,
        )
        for ((readiness, rejection) in cases) assertEquals(rejection, readiness.rejection, "${readiness.lacks}")
        assertNull(of(listOf(provider), indexers = listOf(newznab)).rejection)
        assertNull(of(services = listOf(torbox), torrents = listOf(prowlarr)).rejection)

        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = false), of().hello, "Usenet's lack: provider and indexer say it")
        assertEquals(Hello.Ready(provider = true, indexer = false, tmdb = false), of(listOf(provider)).hello)
        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.Service), of(torrents = listOf(prowlarr)).hello)
        assertEquals(
            Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.ServiceOff),
            of(services = listOf(torbox.copy(record = torbox.record.copy(keyRefused = true))), torrents = listOf(prowlarr), tmdb = true).hello,
        )
        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.TorrentIndexer), of(services = listOf(torbox)).hello)
        assertEquals(
            Hello.Ready(provider = false, indexer = true, tmdb = false, debrid = true),
            of(indexers = listOf(newznab), services = listOf(torbox), torrents = listOf(prowlarr)).hello,
        )
    }

    /** L223: a TV ready by Usenet alone, as every TV was before D2, says so in today's bytes. */
    @Test
    fun `a TV ready by Usenet says so in today's hello`() {
        val ready = of(listOf(provider), indexers = listOf(newznab), tmdb = true)
        assertEquals(Hello.Ready(provider = true, indexer = true, tmdb = true), ready.hello)
        assertEquals("""{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV","ready":{"provider":true,"indexer":true,"tmdb":true}}""", bytes(ready.hello))
        // With a service saved and switched off, or a torrent indexer alone, Usenet still plays: nothing more is said.
        val alsoStarted = of(listOf(provider), indexers = listOf(newznab), services = listOf(torbox.copy(record = torbox.record.copy(enabled = false))), torrents = listOf(prowlarr), tmdb = true)
        assertEquals(bytes(ready.hello), bytes(alsoStarted.hello))
    }

    /**
     * L223, L224: a provider counts only when it can be used, as each Play has always counted it: switched on and its
     * password readable. The hello said "provider" for one switched on whatever its password; it now says what the Play
     * does, the one intended difference from today's bytes.
     */
    @Test
    fun `a provider counts only when it can be used, in the hello as in each Play`() {
        val unreadable = of(listOf(provider), usable = 0, indexers = listOf(newznab))
        assertEquals(Lack.Provider, unreadable.lacks)
        assertEquals(Rejection.NotReadyProvider, unreadable.rejection)
        assertEquals(Hello.Ready(provider = false, indexer = true, tmdb = false), unreadable.hello)
        assertTrue(of(listOf(provider, provider.copy(id = "p2")), usable = 1, indexers = listOf(newznab)).ready, "one of two usable")
        assertEquals(Lack.Provider, of(listOf(provider.copy(enabled = false)), indexers = listOf(newznab)).lacks)
        // Its indexer switched off too: what lacks first is still the provider.
        assertEquals(Lack.Provider, of(listOf(provider), usable = 0, indexers = listOf(newznab.copy(enabled = false))).lacks)
        assertEquals(Lack.Indexer, of(listOf(provider), indexers = listOf(newznab.copy(enabled = false))).lacks)
    }

    /**
     * L224, L248: an account counts while it is on, as Search and the plays count it: switched on, its key readable and not
     * refused since, a service this version knows. Every other is off. A refused key names its service for the TV's own
     * sheet, the first switched on in the user's order; the hello and the refusal never name one.
     */
    @Test
    fun `a refused or unreadable key, or a service this version doesn't know, is off, and only a refused key names its service`() {
        val refused = torbox.copy(record = torbox.record.copy(keyRefused = true))
        val cases = listOf(
            "switched off" to (torbox.copy(record = torbox.record.copy(enabled = false)) to null),
            "its key unreadable" to (torbox.copy(apiKey = null) to null),
            "a newer version's service" to (ServiceWithKey(ServiceAccount("ld", "Later Debrid"), "LD-KEY") to null),
            "its key refused" to (refused to "TorBox"),
        )
        for ((why, case) in cases) {
            val (account, named) = case
            val readiness = of(services = listOf(account), torrents = listOf(prowlarr))
            assertEquals(Lack.ServiceOff, readiness.lacks, why)
            assertEquals(named, readiness.refusedBy, why)
            assertEquals(Rejection.NotReadyServiceOff, readiness.rejection, why)
            val wire = bytes(readiness.hello) + String(LinkCodec.encode(Rejected(1, readiness.rejection!!))!!)
            listOf("TorBox", "Later", "quay.viewer", "tb", "TB-KEY").forEach { assertFalse(it in wire, "$why: $it crossed in $wire") }
        }
        // Premiumize switched off, TorBox's key refused: the refused one is named, whatever the order.
        val off = premiumize.copy(record = premiumize.record.copy(enabled = false))
        assertEquals("TorBox", of(services = listOf(off, refused), torrents = listOf(prowlarr)).refusedBy)
        val pmRefused = premiumize.copy(record = premiumize.record.copy(keyRefused = true))
        assertEquals("Premiumize", of(services = listOf(pmRefused, refused), torrents = listOf(prowlarr)).refusedBy, "the first in the user's order")
        // A refused key switched off since is off, not refused: switching it on is what the TV's sheet asks.
        assertNull(of(services = listOf(refused.copy(record = refused.record.copy(enabled = false))), torrents = listOf(prowlarr)).refusedBy)
        // One account on is enough, though another's key is refused; and nothing is named of a TV that plays.
        val oneOn = of(services = listOf(refused, premiumize), torrents = listOf(prowlarr))
        assertTrue(oneOn.ready)
        assertNull(oneOn.refusedBy)
        // A key refused with no torrent indexer: the service is off, so that comes first.
        assertEquals(Lack.ServiceOff, of(services = listOf(refused)).lacks)
    }

    /** L224: a torrent indexer counts while switched on, as Search counts it; "Use with your debrid service" changes nothing. */
    @Test
    fun `switched-off torrent indexers don't count, and Use with your debrid service changes nothing`() {
        assertEquals(Lack.TorrentIndexer, of(services = listOf(torbox), torrents = listOf(prowlarr.copy(enabled = false))).lacks)
        assertTrue(of(services = listOf(torbox), torrents = listOf(prowlarr.copy(enabled = false), prowlarr.copy(id = "t2"))).ready)
        val notWithService = of(services = listOf(torbox), torrents = listOf(prowlarr.copy(useWithService = false)))
        assertTrue(notWithService.ready)
        assertTrue(notWithService.debrid)
    }

    /** L254: with debrid hidden, the TV's readiness is today's, whatever a shown build saved: Usenet's alone. */
    @Test
    fun `hidden, the TV's readiness is today's`() {
        val debridOnly = of(services = listOf(torbox), torrents = listOf(prowlarr), shown = false)
        assertEquals(Lack.Provider, debridOnly.lacks)
        assertEquals(Rejection.NotReadyProvider, debridOnly.rejection)
        assertEquals("""{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV","ready":{"provider":false,"indexer":false,"tmdb":false}}""", bytes(debridOnly.hello))
        assertEquals(Lack.Indexer, of(listOf(provider), services = listOf(torbox), torrents = listOf(prowlarr), shown = false).lacks)
        assertEquals(Lack.Provider, of(torrents = listOf(prowlarr), shown = false).lacks, "never Service")
        assertNull(of(services = listOf(torbox.copy(record = torbox.record.copy(keyRefused = true))), torrents = listOf(prowlarr), shown = false).refusedBy)
        assertTrue(of(listOf(provider), indexers = listOf(newznab), services = listOf(torbox), torrents = listOf(prowlarr), shown = false).ready)
        assertFalse(of(listOf(provider), indexers = listOf(newznab), services = listOf(torbox), torrents = listOf(prowlarr), shown = false).hello.debrid)
    }

    /**
     * What the rule reads, from the settings as they are: a provider whose password this TV can't read, an account whose key
     * the service refused, and the TV's hello following each change as it comes.
     */
    @Test
    fun `the setup is read from the settings, and the hello follows them`() = runBlocking<Unit> {
        val cipher = TestCipher()
        val settings = memorySettings(cipher = cipher)
        val hellos = TvReadiness.hellos(settings) { true }
        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = false), hellos.first())

        settings.saveProvider(provider.copy(id = "", username = "quay"), "harbor-bell-7")
        settings.saveIndexer(null, "Quay Indexer", "https://indexer.quay.invalid/api", "IX-KEY")
        settings.setTmdbKey("TM-KEY")
        assertEquals(Hello.Ready(provider = true, indexer = true, tmdb = true), hellos.first())
        cipher.unreadable += "harbor-bell-7"
        assertEquals(Hello.Ready(provider = false, indexer = true, tmdb = true), hellos.first(), "its password can't be read here")

        val tb = settings.saveService("TorBox", "TB-KEY") { ServiceSignIn("quay.viewer@example.org") }
        settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/1/api", "TP-KEY")
        assertEquals(Hello.Ready(provider = false, indexer = true, tmdb = true, debrid = true), hellos.first())
        settings.markKeyRefused(tb)
        val read = TvReadiness.of(TvSetup.read(settings), shown = true)
        // Usenet's way is under way (a provider saved), so its lack comes first; the refused key is the debrid way's.
        assertEquals(Lack.Provider, read.lacks)
        assertEquals(Hello.Ready(provider = false, indexer = true, tmdb = true), hellos.first())
        settings.deleteProvider(settings.settings.first().activeProviders.single().id)
        settings.deleteIndexer(settings.settings.first().activeIndexers.single().id)
        val debridOnly = TvReadiness.of(TvSetup.read(settings), shown = true)
        assertEquals(Lack.ServiceOff, debridOnly.lacks)
        assertEquals("TorBox", debridOnly.refusedBy)
        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.ServiceOff), hellos.first())
        assertEquals(Lack.Provider, TvReadiness.of(TvSetup.read(settings), shown = false).lacks)
    }
}

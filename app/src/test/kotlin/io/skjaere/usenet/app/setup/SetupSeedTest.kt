package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.handoff.SavedIndexer
import io.skjaere.usenet.handoff.SavedProvider
import io.skjaere.usenet.handoff.SavedSecret
import io.skjaere.usenet.handoff.SavedService
import io.skjaere.usenet.handoff.SavedTorrentIndexer
import io.skjaere.usenet.handoff.ServiceChoice
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupPage
import io.skjaere.usenet.handoff.SetupResult
import io.skjaere.usenet.handoff.SetupSeed
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.handoff.SetupToken
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a session's page starts from: what is saved, as the page may show it. Every saved provider and indexer as an
 * untouched card would send it back, an address without its login, query or fragment, and of a password or key only
 * whether one is saved and can be read. Over a real server on loopback: nothing of a secret reaches any page.
 */
class SetupSeedTest {
    private val cipher = TestCipher()
    private val settings = memorySettings(cipher = cipher)
    private val servers = mutableListOf<SetupServer>()
    private val events = CopyOnWriteArrayList<SetupEvent>()

    @AfterEach
    fun close() = servers.forEach { it.close() }

    @Test
    fun `providers are seeded in the order they are tried, as their forms show them`() = runBlocking<Unit> {
        val main = settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice", connections = 20), "pass-main")
        // Named as its host before names were optional: its form shows no name, so neither does the page.
        val bare = settings.saveProvider(ProviderRecord("", "block.example.com", "block.example.com", 119, "", useTls = false, connections = 4), "")
        val lost = settings.saveProvider(ProviderRecord("", "Lost", "lost.example.net", 563, "carol"), "pass-lost")
        settings.setProviderEnabled(bare, false)
        cipher.unreadable += "pass-lost"
        assertEquals(
            listOf(
                SavedProvider(main, "Main", "news.example.net", 563, true, "alice", 20, true, SavedSecret.Saved),
                SavedProvider(bare, "", "block.example.com", 119, false, "", 4, false, SavedSecret.None),
                SavedProvider(lost, "Lost", "lost.example.net", 563, true, "carol", 8, true, SavedSecret.Unreadable),
            ),
            settings.setupSeed().providers,
        )
    }

    @Test
    fun `indexers are seeded with their addresses shown and whether their keys can be read`() = runBlocking<Unit> {
        val plain = settings.saveIndexer(null, "Prowlarr 1", "http://192.168.1.5:9696/1/api", "key-one")
        val hidden = settings.saveIndexer(null, "Hidden", "https://u:p@indexer.example.org/api?apikey=K", "key-two", enabled = false)
        val lost = settings.saveIndexer(null, "Lost", "https://lost.example.org/api", "key-lost")
        val none = settings.saveIndexer(null, "None", "https://none.example.org/api", null)
        cipher.unreadable += "key-lost"
        assertEquals(
            listOf(
                SavedIndexer(plain, "Prowlarr 1", "http://192.168.1.5:9696/1/api", hiddenParts = false, enabled = true, key = SavedSecret.Saved),
                SavedIndexer(hidden, "Hidden", "https://indexer.example.org/api", hiddenParts = true, enabled = false, key = SavedSecret.Saved),
                SavedIndexer(lost, "Lost", "https://lost.example.org/api", hiddenParts = false, enabled = true, key = SavedSecret.Unreadable),
                SavedIndexer(none, "None", "https://none.example.org/api", hiddenParts = false, enabled = true, key = SavedSecret.None),
            ),
            settings.setupSeed().indexers,
        )
    }

    @Test
    fun `the TMDB key is seeded as none, saved, or saved but unreadable`() = runBlocking<Unit> {
        assertEquals(SavedSecret.None, settings.setupSeed().tmdbKey)
        // Unreadable from the start, as after a device move: a key read once is kept for the app's life.
        cipher.unreadable += "tmdb-key-lost"
        settings.setTmdbKey("tmdb-key-lost")
        assertEquals(SavedSecret.Unreadable, settings.setupSeed().tmdbKey)
        settings.setTmdbKey("tmdb-key-one")
        assertEquals(SavedSecret.Saved, settings.setupSeed().tmdbKey)
    }

    /**
     * L209: while debrid shows, the page offers the services A to Z, each saved account as its card shows it (TorBox over
     * "quay.viewer@example.invalid · key saved") and each torrent indexer with its address shown and its switch; never a
     * key, and nothing of them at all while debrid is hidden (L192).
     */
    @Test
    fun `debrid services and torrent indexers are seeded while debrid shows, and nothing of them while hidden`() = runBlocking<Unit> {
        val tb = settings.saveService("TorBox", "SAVED-SERVICE-KEY") { ServiceSignIn("quay.viewer@example.invalid", "Standard") }
        cipher.unreadable += "LOST-SERVICE-KEY"
        val pm = settings.saveService("Premiumize", "LOST-SERVICE-KEY") { ServiceSignIn("customer 900000042") }
        settings.setServiceEnabled(pm, false)
        val home = settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api?apikey=marker-in-query", "SAVED-TORRENT-KEY", useWithService = false)
        val quarry = settings.saveTorrentIndexer(null, "Quarry", "https://quarry.example.invalid/api", null, enabled = false)
        val seed = settings.setupSeed(shown = true)
        assertEquals(
            listOf(
                ServiceChoice("premiumize", "Premiumize", "In Premiumize: Account › API key"),
                ServiceChoice("torbox", "TorBox", "In TorBox: Settings › API key"),
            ),
            seed.serviceKinds,
        )
        assertEquals(SERVICE_CHOICES, seed.serviceKinds)
        assertEquals(
            listOf(
                SavedService(tb, "torbox", "quay.viewer@example.invalid", enabled = true, key = SavedSecret.Saved),
                SavedService(pm, "premiumize", "customer 900000042", enabled = false, key = SavedSecret.Unreadable),
            ),
            seed.services,
        )
        assertEquals(
            listOf(
                SavedTorrentIndexer(home, "Home Prowlarr", "http://192.168.1.20:9696/3/api", hiddenParts = true, enabled = true, useWithService = false, key = SavedSecret.Saved),
                SavedTorrentIndexer(quarry, "Quarry", "https://quarry.example.invalid/api", hiddenParts = false, enabled = false, useWithService = true, key = SavedSecret.None),
            ),
            seed.torrentIndexers,
        )
        assertFalse(listOf("SAVED-SERVICE-KEY", "LOST-SERVICE-KEY", "SAVED-TORRENT-KEY", "marker").any { it in seed.toString() }, seed.toString())
        // Signed out of: no card. A service only a newer version knows: none either, as its radio couldn't show it.
        settings.signOut(pm)
        assertEquals(listOf(tb), settings.setupSeed(shown = true).services.map { it.ref })

        val hidden = settings.setupSeed(shown = false)
        assertEquals(listOf(emptyList<Any>(), emptyList(), emptyList()), listOf(hidden.serviceKinds, hidden.services, hidden.torrentIndexers))
        assertFalse(hidden.debrid)
    }

    /** Over a real server: the debrid cards' saved keys reach no page, opened or tested. */
    @Test
    fun `no page shows a debrid account's or a torrent indexer's saved key`() = runBlocking<Unit> {
        settings.saveService("TorBox", "marker-service-key") { ServiceSignIn("quay.viewer@example.invalid") }
        settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api?apikey=marker", "marker-torrent-key")
        settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "pass-main")
        settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "key-one")
        val client = open(SetupMode.Edit, settings.setupSeed(shown = true))
        var page = client.get()
        assertTrue(page.body.contains("<h2>Debrid service</h2>") && page.body.contains("quay.viewer@example.invalid · key saved"), page.body)
        for (slot in listOf("s1", "r1")) page = client.press(page, "open:$slot")
        page = client.press(page, "test:s1")
        page = client.press(page, "test:r1")
        client.press(page, "send")
        assertTrue(client.answers.size > 4)
        client.answers.forEach { assertFalse("marker" in it.everything, "a saved debrid key on a page") }
    }

    /** By type: a field added to the seed is reviewed here first, as one that could carry a secret to the page. */
    @Test
    fun `the seed's types have no field that could hold a password or key`() {
        fun fields(type: Class<*>) = type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }.associate { it.name to it.type.simpleName }
        assertEquals(
            mapOf(
                "ref" to "String", "name" to "String", "host" to "String", "port" to "int", "useTls" to "boolean", "username" to "String",
                "connections" to "int", "enabled" to "boolean", "login" to "SavedSecret",
            ),
            fields(SavedProvider::class.java),
        )
        assertEquals(
            mapOf("ref" to "String", "name" to "String", "shownUrl" to "String", "hiddenParts" to "boolean", "enabled" to "boolean", "key" to "SavedSecret"),
            fields(SavedIndexer::class.java),
        )
        assertEquals(
            mapOf("ref" to "String", "kind" to "String", "account" to "String", "enabled" to "boolean", "key" to "SavedSecret"),
            fields(SavedService::class.java),
        )
        assertEquals(
            mapOf(
                "ref" to "String", "name" to "String", "shownUrl" to "String", "hiddenParts" to "boolean", "enabled" to "boolean", "useWithService" to "boolean",
                "key" to "SavedSecret",
            ),
            fields(SavedTorrentIndexer::class.java),
        )
        // What the page offers of each service is the app's words for it, never anything of an account.
        assertEquals(mapOf("value" to "String", "name" to "String", "keyPlace" to "String"), fields(ServiceChoice::class.java))
        assertEquals(
            mapOf(
                "providers" to "List", "indexers" to "List", "tmdbKey" to "SavedSecret", "serviceKinds" to "List", "services" to "List",
                "torrentIndexers" to "List",
            ),
            fields(SetupSeed::class.java),
        )
        assertEquals(listOf("None", "Saved", "Unreadable"), SavedSecret.entries.map { it.name })
    }

    /** Values that didn't come back exactly as saved would make every saved card a change, or a test the user never asked for. */
    @Test
    fun `a card left as it was sends back what is saved, so nothing would change`() = runBlocking<Unit> {
        settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice", connections = 20), "pass-main")
        settings.saveProvider(ProviderRecord("", "block.example.com", "block.example.com", 119, "", useTls = false, connections = 4), "")
        val off = settings.saveProvider(ProviderRecord("", "", "third.example.net", 443, "bob", connections = 2), "pass-third")
        settings.setProviderEnabled(off, false)
        settings.saveIndexer(null, "Prowlarr 1", "http://192.168.1.5:9696/1/api", "key-one")
        settings.saveIndexer(null, "Hidden", "https://u:p@indexer.example.org/api?apikey=K#top", "key-two")
        val seed = settings.setupSeed()
        val client = open(SetupMode.Edit, seed)
        var page = client.get()
        for (slot in listOf("p1", "p2", "p3", "i1", "i2")) {
            page = client.press(page, "open:$slot")
            assertTrue(page.body.contains("<fieldset id=\"$slot\">"), slot)
        }
        val sent = client.press(page, "send")
        assertEquals(200, sent.status)
        assertTrue(sent.body.contains(SetupPage.NOTHING_TO_SEND), "a card left as it was counts as a change")
        assertTrue(events.none { it is SetupEvent.Received })
    }

    /** Marker secrets in every place a saved value keeps one: none of them reaches a page, a header or an answer. */
    @Test
    fun `no page shows a saved password, key or the hidden parts of an address`() = runBlocking<Unit> {
        settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "marker-pass")
        settings.saveIndexer(null, "Query", "https://one.example.org/api?apikey=marker&r=marker", "marker-key-one")
        settings.saveIndexer(null, "Fragment", "https://two.example.org/api#marker", "marker-key-two")
        settings.saveIndexer(null, "Login", "https://u:marker@three.example.org/api", "marker-key-three")
        settings.setTmdbKey("marker-tmdb")
        val seed = settings.setupSeed()
        assertFalse("marker" in seed.toString(), seed.toString())
        for (mode in SetupMode.entries) {
            val client = open(mode, seed)
            var page = client.get()
            if (mode == SetupMode.Edit) {
                for (slot in listOf("p1", "i1", "i2", "i3", "t")) page = client.press(page, "open:$slot")
                page = client.press(page, "test:i1")
            } else {
                page = client.press(page, "open:p1")
                page = client.press(page, "next")
                for (slot in listOf("i1", "i2", "i3")) page = client.press(page, "open:$slot")
                page = client.press(page, "next")
                page = client.press(page, "test", emptyList())
                client.press(page, "next", emptyList())
            }
            assertTrue(client.answers.size > 5)
            client.answers.forEach { assertFalse("marker" in it.everything, "$mode: a saved secret on a page") }
        }
    }

    /** A real server for [seed] on loopback, its tests answered at once, and a phone's browser on its page. */
    private fun open(mode: SetupMode, seed: SetupSeed): PageClient {
        val server = SetupServer(
            InetAddress.getByName("127.0.0.1") as Inet4Address, token = SetupToken.generate(), mode = mode, seed = seed, allowPeer = { true },
            tester = { CompletableFuture.completedFuture(SetupResult(SetupOutcome.Passed, "Works")) }, onEvent = { events += it },
        )
        servers += server
        server.start()
        return PageClient(server.address.url)
    }
}

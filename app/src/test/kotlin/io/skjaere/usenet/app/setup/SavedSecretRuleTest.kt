package io.skjaere.usenet.app.setup

import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.handoff.ProviderDraft
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a password or key saved on the TV may go with what the phone's page holds: only while the server is the one it
 * was saved for. Whoever holds the code chooses where a test or a save sends it, so each way a server could be another
 * is tried here, and the shown (redacted) address of a saved indexer stands for the saved one whole.
 */
class SavedSecretRuleTest {
    private val saved = ProviderRecord("p-main", "Main", "news.example.net", 563, "alice", useTls = true, connections = 20)

    private fun draft(host: String = "news.example.net", port: Int? = 563, tls: Boolean = true, username: String = "alice", name: String = "Main", connections: Int? = 20) =
        ProviderDraft(name, host, port, tls, username, "", connections)

    @Test
    fun `the same provider keeps its password, whatever its name, connections or the host's case`() {
        listOf(
            draft(),
            draft(host = "NEWS.Example.NET"),
            draft(host = " news.example.net "),
            draft(name = "Renamed", connections = 50),
            draft(name = ""),
            draft(port = null),
            draft(username = " alice "),
        ).forEach { assertTrue(SavedSecretRule.sameProvider(saved, it), it.toString()) }
    }

    @Test
    fun `another host, port, TLS setting or username is another server`() {
        val others = mapOf(
            "another host" to draft(host = "news.examp1e.net"),
            "a host that only starts the same" to draft(host = "news.example.net.evil.example"),
            "the plain port with TLS off" to draft(port = 119, tls = false),
            "TLS off on the same port" to draft(tls = false),
            "another port with TLS" to draft(port = 443),
            "the default port for no TLS" to draft(port = null, tls = false),
            "another username" to draft(username = "bob"),
            "the username in another case" to draft(username = "Alice"),
            "no username" to draft(username = ""),
        )
        others.forEach { (why, draft) -> assertFalse(SavedSecretRule.sameProvider(saved, draft), why) }
    }

    /** String's own ignoreCase folds other letters to these: "ı" to "I", "ſ" to "S", the Kelvin sign to "k". */
    @Test
    fun `a host in look-alike letters is another host`() {
        val ascii = saved.copy(host = "news.provider.sk")
        assertTrue(SavedSecretRule.sameProvider(ascii, draft(host = "NEWS.PROVIDER.SK")))
        listOf("news.provıder.sk", "newſ.provider.sk", "news.provider.sK", "news.prоvider.sk").forEach { host ->
            assertTrue(host.equals("news.provider.sk", ignoreCase = true) || host.contains('о'), "the case a looser rule would pass: $host")
            assertFalse(SavedSecretRule.sameProvider(ascii, draft(host = host)), host)
        }
    }

    @Test
    fun `an indexer shown as saved is the saved one, its hidden parts and all`() {
        val url = "https://u:p@indexer.example.org/api?apikey=K&r=R#top"
        val shown = shownAddress(url)
        assertEquals(ShownAddress("https://indexer.example.org/api", hiddenParts = true), shown)
        assertTrue(SavedSecretRule.sameIndexer(url, shown.url))
        assertEquals(url, SavedSecretRule.indexerAddress(url, shown.url), "the saved address is used whole")
        assertEquals(url, SavedSecretRule.indexerAddress(url, " ${shown.url} "))
        // Typed otherwise, the address is what was typed, without the parts the page never showed.
        assertEquals("https://indexer.example.org/api/", SavedSecretRule.indexerAddress(url, "https://indexer.example.org/api/"))
    }

    @Test
    fun `the same indexer written another way keeps its key, up to https but not down`() {
        val url = "http://192.168.1.5:9696/1/api"
        listOf("http://192.168.1.5:9696/1", "http://192.168.1.5:9696/1/api/", "https://192.168.1.5:9696/1/api").forEach {
            assertTrue(SavedSecretRule.sameIndexer(url, it), it)
        }
        assertFalse(SavedSecretRule.sameIndexer("https://indexer.example.org/api", "http://indexer.example.org/api"), "down to http")
    }

    @Test
    fun `another host, port, path or login is another indexer`() {
        val url = "https://indexer.example.org/api"
        val others = mapOf(
            "another host" to "https://indexer.example.net/api",
            "a login hiding the host" to "https://indexer.example.org%2Fapi@evil.example/api",
            "a login added" to "https://someone@indexer.example.org/api",
            "another port" to "https://indexer.example.org:8443/api",
            "another path" to "https://indexer.example.org/other/api",
            "parsers that disagree" to "https://indexer.example.org\\@evil.example/api",
            "not an address" to "indexer.example.org/api",
            "a look-alike host" to "https://ındexer.example.org/api",
        )
        others.forEach { (why, sent) -> assertFalse(SavedSecretRule.sameIndexer(url, sent), why) }
        assertFalse(SavedSecretRule.sameIndexer("http://192.168.1.5:9696/1/api", "http://192.168.1.5:9696/2/api"), "another of Prowlarr's indexers")
    }

    /** The one answer the tester, the review and Save each take: the saved secret to use, or why there is none. */
    @Test
    fun `a card's saved password or key is used only when one is saved, readable, and for the same server`() {
        val with = ProviderWithPassword(saved, "saved-pass")
        assertEquals(SavedUse.Use("saved-pass"), SavedSecretRule.password(with, draft(name = "Renamed")))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.password(null, draft()), "a new card, or its record gone")
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.password(with, draft(port = 119, tls = false)))
        assertEquals(SavedUse.No(SavedRefusal.Unreadable), SavedSecretRule.password(with.copy(password = null), draft()))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.password(with.copy(password = ""), draft()))
        // The server first: a changed one is said so even when the saved password couldn't be read anyway.
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.password(with.copy(password = null), draft(host = "elsewhere.example.net")))
        val indexer = IndexerWithKey(IndexerRecord("i-one", "One", "https://indexer.example.org/api?r=x"), "SAVED-KEY")
        assertEquals(SavedUse.Use("SAVED-KEY"), SavedSecretRule.key(indexer, stored = true, sent = "https://indexer.example.org/api"))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.key(null, stored = false, sent = "https://indexer.example.org/api"))
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.key(indexer, stored = true, sent = "http://indexer.example.org/api"))
        assertEquals(SavedUse.No(SavedRefusal.Unreadable), SavedSecretRule.key(indexer.copy(apiKey = null), stored = true, sent = "https://indexer.example.org/api"))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.key(indexer.copy(apiKey = null), stored = false, sent = "https://indexer.example.org/api"))
        // What a log or a failed assertion prints holds no secret.
        assertFalse("saved-pass" in SavedUse.Use("saved-pass").toString())
    }

    /**
     * L209: a debrid account's saved key goes only with a card of its own service (a page can't change a saved account's
     * service, but the rule doesn't lean on that), and one only a newer version knows is no card's.
     */
    @Test
    fun `a debrid account's saved key goes only with a card of its own service`() {
        val torbox = ServiceWithKey(ServiceAccount("svc-1", "TorBox", accountName = "quay.viewer@example.invalid"), "SAVED-SERVICE-KEY")
        assertEquals(SavedUse.Use("SAVED-SERVICE-KEY"), SavedSecretRule.serviceKey(torbox, stored = true, kind = "torbox"))
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.serviceKey(torbox, stored = true, kind = "premiumize"))
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.serviceKey(torbox, stored = true, kind = ""))
        assertEquals(SavedUse.No(SavedRefusal.Unreadable), SavedSecretRule.serviceKey(torbox.copy(apiKey = null), stored = true, kind = "torbox"))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.serviceKey(torbox.copy(apiKey = null), stored = false, kind = "torbox"))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.serviceKey(null, stored = false, kind = "torbox"), "a new card")
        val newer = ServiceWithKey(ServiceAccount("svc-9", "Futurebox"), "FUTURE-KEY")
        assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.serviceKey(newer, stored = true, kind = "futurebox"))
    }

    /** L209: a torrent indexer's saved key goes as an indexer's (`key` with `sameIndexer`): to its own address alone. */
    @Test
    fun `a torrent indexer's saved key goes only to its own address, as an indexer's`() {
        val prowlarr = TorrentIndexerWithKey(TorrentIndexerRecord("tor-1", "Home Prowlarr", "http://192.168.1.20:9696/3/api?apikey=hidden"), "SAVED-TORRENT-KEY")
        val shown = shownAddress(prowlarr.record.baseUrl).url
        assertEquals("http://192.168.1.20:9696/3/api", shown)
        assertEquals(SavedUse.Use("SAVED-TORRENT-KEY"), SavedSecretRule.torrentIndexerKey(prowlarr, stored = true, sent = shown))
        assertEquals(SavedUse.Use("SAVED-TORRENT-KEY"), SavedSecretRule.torrentIndexerKey(prowlarr, stored = true, sent = "https://192.168.1.20:9696/3/api"), "up to https")
        for (moved in listOf("http://192.168.1.21:9696/3/api", "https://tracker.example.invalid/3/api", "http://192.168.1.20:9696/4/api", "http://u@192.168.1.20:9696/3/api")) {
            assertEquals(SavedUse.No(SavedRefusal.ServerChanged), SavedSecretRule.torrentIndexerKey(prowlarr, stored = true, sent = moved), moved)
        }
        assertEquals(SavedUse.No(SavedRefusal.Unreadable), SavedSecretRule.torrentIndexerKey(prowlarr.copy(apiKey = null), stored = true, sent = shown))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.torrentIndexerKey(prowlarr.copy(apiKey = null), stored = false, sent = shown))
        assertEquals(SavedUse.No(SavedRefusal.NoneSaved), SavedSecretRule.torrentIndexerKey(null, stored = false, sent = shown))
    }

    /** A page shows an address as scheme, host, port and path: a login, a query or a fragment may hold a secret. */
    @Test
    fun `an address is shown without its login, query or fragment`() {
        mapOf(
            "https://indexer.example.org/api" to ShownAddress("https://indexer.example.org/api", hiddenParts = false),
            "http://192.168.1.5:9696/1/api" to ShownAddress("http://192.168.1.5:9696/1/api", hiddenParts = false),
            "http://[fd12:3456::1]:9696" to ShownAddress("http://[fd12:3456::1]:9696", hiddenParts = false),
            "https://indexer.example.org/api?apikey=marker&r=marker" to ShownAddress("https://indexer.example.org/api", hiddenParts = true),
            "https://indexer.example.org/api#marker" to ShownAddress("https://indexer.example.org/api", hiddenParts = true),
            "https://u:marker@indexer.example.org/api" to ShownAddress("https://indexer.example.org/api", hiddenParts = true),
            "not an address" to ShownAddress("", hiddenParts = true),
        ).forEach { (url, shown) -> assertEquals(shown, shownAddress(url), url) }
    }
}

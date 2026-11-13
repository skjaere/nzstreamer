package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.debrid.signedInLine
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.app.settings.TMDB_REJECTED
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.app.settings.resultsOfOtherKind
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.IndexerDraft
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.ServiceDraft
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupPage
import io.skjaere.usenet.handoff.SetupResult
import io.skjaere.usenet.handoff.SetupSection
import io.skjaere.usenet.handoff.SetupSlot
import io.skjaere.usenet.handoff.SetupTestItem
import io.skjaere.usenet.handoff.TorrentIndexerDraft
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tests the phone asks for from its page, a card at a time: the card tested as its form tests it, with what was
 * typed, or with what is saved for its own record while the server is the one it was saved for, and never otherwise.
 * The forms' checks come first, an item that takes too long is said to, nothing is saved, and each pass is logged
 * with exactly what it tested, for the TV's review.
 */
class PhoneTesterTest {
    private val cipher = TestCipher()
    /** On the test's clock ([Fakes] sets it), so a test's read of a saved secret runs as the test runs. */
    private lateinit var settings: SettingsRepository

    private inner class Fakes(scope: TestScope) {
        init {
            settings = memorySettings(StandardTestDispatcher(scope.testScheduler), cipher)
        }

        val providers = mutableListOf<Pair<ProviderForm, String>>()
        val indexers = mutableListOf<IndexerConfig>()
        val keys = mutableListOf<String>()
        /** The debrid keys signed in with, each on a client of its own. */
        val serviceKeys = mutableListOf<Pair<ServiceKind, String>>()
        var service: (ServiceKind, String) -> Account = { _, _ -> QUAY_ACCOUNT }
        val passes = PassLog()
        var provider: suspend (ProviderForm, String) -> ProviderTestResult = { f, password -> result(f, password, ok = true) }
        var indexer: suspend (IndexerConfig) -> IndexerTest = { IndexerTest.Ok(IndexerCaps(serverTitle = "Example", serverVersion = "1.2"), null) }
        var tmdb: suspend (String) -> KeyCheck = { KeyCheck.Valid }

        fun tester(scope: TestScope, limit: Long = 25_000) = PhoneTester(
            scope.backgroundScope,
            SavedLookup.of(settings),
            testProvider = { f, password -> providers += f to password; provider(f, password) },
            testIndexer = { config -> indexers += config; indexer(config) },
            validateTmdbKey = { key -> keys += key; tmdb(key) },
            passes = passes,
            trialService = { kind, key -> serviceKeys += kind to key; SignInService(kind, key, service) },
            limitMillis = limit,
        )

        /** Every secret any test was handed. */
        val secretsTested: List<String> get() = providers.map { it.second } + indexers.map { it.apiKey } + keys + serviceKeys.map { it.second }
    }

    private val p1 = SetupSlot(SetupSection.Provider, 1)
    private val i1 = SetupSlot(SetupSection.Indexer, 1)
    private val provider = ProviderDraft("Mine", "news.example.net", 443, true, "alice", " hunter2 ", 12)
    private val indexer = IndexerDraft("Idx", " https://indexer.example.org/api ", "INDEXER-KEY-0000-1111")

    private val s1 = SetupSlot(SetupSection.Service, 1)
    private val r1 = SetupSlot(SetupSection.TorrentIndexer, 1)
    private val prowlarr = TorrentIndexerDraft("Home Prowlarr", "http://192.168.1.20:9696/3/api", "TORRENT-KEY-0000-1111", useWithService = true)

    private fun item(draft: ProviderDraft, ref: String? = null) = SetupTestItem.Provider(p1, ref, draft)
    private fun item(draft: ServiceDraft, ref: String? = null) = SetupTestItem.Service(s1, ref, draft)
    private fun item(draft: TorrentIndexerDraft, ref: String? = null, plainHttp: Boolean = false) = SetupTestItem.TorrentIndexer(r1, ref, draft, plainHttp)
    private fun item(draft: IndexerDraft, ref: String? = null, plainHttp: Boolean = false) = SetupTestItem.Indexer(i1, ref, draft, plainHttp)

    /** [item]'s test, as the server asks for it, run as far as it goes. */
    private fun TestScope.run(tester: PhoneTester, item: SetupTestItem): SetupResult {
        val future = tester.test(item)
        runCurrent()
        return future.get(0, TimeUnit.SECONDS)
    }

    private fun passed(words: String) = SetupResult(SetupOutcome.Passed, words)
    private fun failed(words: String) = SetupResult(SetupOutcome.Failed, words)
    private fun notTested(words: String) = SetupResult(SetupOutcome.NotTested, words)

    @Test
    fun `each card is tested as its form tests it, with what was typed`() = runTest {
        val f = Fakes(this)
        val tester = f.tester(this)
        assertEquals(passed("Connected and logged in to news.example.net"), run(tester, item(provider)))
        assertEquals(passed("Connected · Example 1.2"), run(tester, item(indexer)))
        assertEquals(passed("Key works"), run(tester, SetupTestItem.Tmdb("tmdb-key-0123456789abcdef")))
        val (form, password) = f.providers.single()
        assertEquals(listOf("news.example.net", "443", true, "alice", "12"), listOf(form.host, form.port, form.useTls, form.username, form.connections))
        assertEquals(" hunter2 ", password, "the password as typed")
        val config = f.indexers.single()
        assertEquals(listOf("https://indexer.example.org/api", "INDEXER-KEY-0000-1111"), listOf(config.baseUrl, config.apiKey))
        assertEquals(listOf("tmdb-key-0123456789abcdef"), f.keys)
    }

    /**
     * L209: a debrid service card is signed in to as its key form's Test does, on a client of its own (the registry's
     * trial, which marks no saved account), and its line is that Test's: the account as the service names it.
     */
    @Test
    fun `a service card signs in on a client of its own, as its key form's Test does, saving nothing`() = runTest {
        val f = Fakes(this)
        val tester = f.tester(this)
        val result = run(tester, item(ServiceDraft("torbox", "TYPED-SERVICE-KEY")))
        assertEquals(passed(signedInLine(QUAY_ACCOUNT)), result)
        assertTrue(result.words.startsWith("Signed in as quay.viewer@example.invalid · plan until "), result.words)
        assertEquals(listOf(ServiceKind.TorBox to "TYPED-SERVICE-KEY"), f.serviceKeys)
        assertEquals(ServiceSignIn("quay.viewer@example.invalid", "Standard", QUAY_ACCOUNT.planUntil?.toEpochMilli()), f.passes.service(ServiceKind.TorBox, "TYPED-SERVICE-KEY"))
        assertNull(f.passes.service(ServiceKind.Premiumize, "TYPED-SERVICE-KEY"), "a pass is for its own service")
        assertEquals(emptyList(), settings.services.first(), "nothing saved")
        // Refusals in the key form's words, which name the service the card chose.
        f.service = { _, _ -> throw ServiceException(ServiceError.KeyRefused) }
        assertEquals(failed("TorBox refused this key. Check you copied all of it."), run(tester, item(ServiceDraft("torbox", "WRONG-KEY"))))
        f.service = { _, _ -> throw ServiceException(ServiceError.Refused()) }
        assertEquals(failed("Premiumize didn’t allow this account. Check it on Premiumize’s site."), run(tester, item(ServiceDraft("premiumize", "RESTRICTED-KEY"))))
        assertNull(f.passes.service(ServiceKind.TorBox, "WRONG-KEY"))
    }

    /** L209: a saved account's card, its key left empty, is tested with its saved key for its own service alone. */
    @Test
    fun `a saved service's card is tested with its saved key for its own service only`() = runTest {
        val f = Fakes(this)
        val tb = settings.saveService("TorBox", "SAVED-SERVICE-KEY") { ServiceSignIn("quay.viewer@example.invalid") }
        val tester = f.tester(this)
        assertEquals(SetupOutcome.Passed, run(tester, item(ServiceDraft("torbox", ""), ref = tb)).outcome)
        assertEquals(listOf(ServiceKind.TorBox to "SAVED-SERVICE-KEY"), f.serviceKeys)
        assertEquals(notTested(PHONE_TEST_SERVICE_CHANGED), run(tester, item(ServiceDraft("premiumize", ""), ref = tb)))
        assertEquals("the service changed, so type its API key to test it here.", PHONE_TEST_SERVICE_CHANGED)
        assertEquals(notTested(PHONE_TEST_NO_KEY), run(tester, item(ServiceDraft("torbox", ""))), "a new card has no saved key")
        settings.signOut(tb)
        assertEquals(notTested(PHONE_TEST_NO_KEY), run(tester, item(ServiceDraft("torbox", ""), ref = tb)), "signed out meanwhile")
        cipher.unreadable += "LOST-SERVICE-KEY"
        val lost = settings.saveService("Premiumize", "LOST-SERVICE-KEY") { ServiceSignIn("customer 900000042") }
        assertEquals(notTested(PHONE_TEST_KEY_UNREADABLE), run(tester, item(ServiceDraft("premiumize", ""), ref = lost)))
        assertEquals(1, f.serviceKeys.size, "only the saved key for its own service was tried")
    }

    /**
     * L209: a torrent indexer card is tested as its own form's Test, a torrent indexer's (Torznab, its latest torrent
     * searched), under a throwaway id; a feed of NZBs says so in that form's words.
     */
    @Test
    fun `a torrent indexer card is tested as a torrent indexer, and a feed of NZBs is told so`() = runTest {
        val f = Fakes(this)
        val tester = f.tester(this)
        assertEquals(passed("Connected · Example 1.2"), run(tester, item(prowlarr)))
        val config = f.indexers.single()
        assertEquals(listOf(IndexerKind.Torznab, "http://192.168.1.20:9696/3/api", "TORRENT-KEY-0000-1111"), listOf(config.kind, config.baseUrl, config.apiKey))
        assertTrue(config.id.startsWith("phone-test-"), config.id)
        assertEquals("Connected · Example 1.2", f.passes.torrentIndexer("http://192.168.1.20:9696/3/api", "TORRENT-KEY-0000-1111"))
        assertNull(f.passes.indexer("http://192.168.1.20:9696/3/api", "TORRENT-KEY-0000-1111"), "a torrent indexer's pass is no indexer's")
        f.indexer = { c -> if (c.kind == IndexerKind.Torznab) IndexerTest.Failed(resultsOfOtherKind(IndexerKind.Torznab, shown = true)) else IndexerTest.Ok(IndexerCaps(), null) }
        assertEquals(failed("This indexer’s results are NZBs. Add it under Indexers instead."), run(tester, item(prowlarr.copy(url = "https://indexer.example.org/api"))))
        // An indexer card is still an indexer's.
        run(tester, item(indexer))
        assertEquals(IndexerKind.Newznab, f.indexers.last().kind)
        // Its form's checks, and Test anyway over plain http on the internet, as an indexer's.
        assertEquals(failed(PHONE_TEST_NO_URL), run(tester, item(prowlarr.copy(url = ""))))
        assertEquals(SetupResult(SetupOutcome.NotTested, PHONE_TEST_PUBLIC_HTTP, plainHttp = true), run(tester, item(prowlarr.copy(url = "http://tracker.example.org/api"))))
    }

    /**
     * L209: a torrent indexer card's empty key is its saved one only at its own address; moved to another host, the card
     * is refused as an indexer's is, and the saved key reaches no request.
     */
    @Test
    fun `a torrent indexer card moved to another host with an empty key is refused, and the saved key reaches no request`() = runTest {
        val f = Fakes(this)
        val saved = "http://192.168.1.20:9696/3/api?apikey=hidden"
        val r = settings.saveTorrentIndexer(null, "Home Prowlarr", saved, "SAVED-TORRENT-KEY")
        val tester = f.tester(this)
        assertEquals(SetupOutcome.Passed, run(tester, item(prowlarr.copy(url = shownAddress(saved).url, key = ""), ref = r)).outcome)
        assertEquals(listOf(saved, "SAVED-TORRENT-KEY"), f.indexers.single().let { listOf(it.baseUrl, it.apiKey) }, "its saved address whole")
        for (moved in listOf("https://tracker.example.invalid/3/api", "http://192.168.1.21:9696/3/api", "http://192.168.1.20:9697/3/api")) {
            val result = run(tester, item(prowlarr.copy(url = moved, key = ""), ref = r, plainHttp = true))
            assertEquals(notTested(PHONE_TEST_ADDRESS_CHANGED), result, moved)
        }
        assertEquals(notTested(PHONE_TEST_NO_KEY), run(tester, item(prowlarr.copy(key = ""))), "a new card")
        assertEquals(1, f.indexers.size)
        assertFalse(f.indexers.drop(1).any { it.apiKey == "SAVED-TORRENT-KEY" })
        cipher.unreadable += "LOST-TORRENT-KEY"
        val lost = settings.saveTorrentIndexer(null, "Quarry", "https://quarry.example.invalid/api", "LOST-TORRENT-KEY")
        assertEquals(notTested(PHONE_TEST_KEY_UNREADABLE), run(tester, item(prowlarr.copy(url = "https://quarry.example.invalid/api", key = ""), ref = lost)))
    }

    @Test
    fun `a failure is said in the form's words`() = runTest {
        val f = Fakes(this)
        f.provider = { form, password -> result(form, password, ok = false) }
        f.indexer = { IndexerTest.Failed("No answer within 15 s") }
        f.tmdb = { KeyCheck.Invalid }
        val tester = f.tester(this)
        assertEquals(failed("The provider rejected the username or password."), run(tester, item(provider)))
        assertEquals(failed("No answer within 15 s"), run(tester, item(indexer)))
        assertEquals(failed(TMDB_REJECTED), run(tester, SetupTestItem.Tmdb("k")))
        f.tmdb = { KeyCheck.Failed(TmdbError.Network("timed out")) }
        val words = run(tester, SetupTestItem.Tmdb("k"))
        assertEquals(SetupOutcome.Failed, words.outcome)
        assertTrue(words.words.startsWith("Couldn't check the key: "), words.words)
    }

    /** A new card has no saved secret of its own: what the phone left empty is never filled from the TV for it. */
    @Test
    fun `a new login without its password and a new indexer without its key aren't tested`() = runTest {
        val f = Fakes(this)
        settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 443, "alice"), "saved-pass")
        settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY")
        val tester = f.tester(this)
        assertEquals(notTested(PHONE_TEST_NO_PASSWORD), run(tester, item(provider.copy(password = ""))))
        assertEquals(notTested(PHONE_TEST_NO_KEY), run(tester, item(indexer.copy(apiKey = ""))))
        assertEquals("type the password to test it here.", PHONE_TEST_NO_PASSWORD)
        assertEquals("type the API key to test it here.", PHONE_TEST_NO_KEY)
        assertTrue(f.providers.isEmpty() && f.indexers.isEmpty())
    }

    /** A saved card left with its password or key empty is its saved one, while the server is the one it was saved for. */
    @Test
    fun `a saved card on its own server is tested with its saved password or key`() = runTest {
        val f = Fakes(this)
        val p = settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 443, "alice", connections = 12), "saved-pass")
        val i = settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY")
        settings.setTmdbKey("saved-tmdb-key")
        val tester = f.tester(this)
        // Renamed, its host in capitals, more connections: still its server.
        assertEquals(SetupOutcome.Passed, run(tester, item(provider.copy(name = "Renamed", host = "NEWS.example.net", password = "", connections = 30), ref = p)).outcome)
        assertEquals(SetupOutcome.Passed, run(tester, item(indexer.copy(apiKey = "", url = "https://indexer.example.org"), ref = i)).outcome)
        assertEquals(SetupOutcome.Passed, run(tester, SetupTestItem.Tmdb("")).outcome)
        assertEquals(listOf("saved-pass"), f.providers.map { it.second })
        assertEquals(listOf("SAVED-KEY"), f.indexers.map { it.apiKey })
        assertEquals(listOf("saved-tmdb-key"), f.keys)
        // A secret typed for it is the one tested.
        run(tester, item(provider.copy(password = "typed-pass"), ref = p))
        assertEquals("typed-pass", f.providers.last().second)
    }

    /** Shown without its login and query, the address stands for the saved one whole: the test goes where the saved one does. */
    @Test
    fun `a saved indexer shown as it was is tested at its saved address, hidden parts and all`() = runTest {
        val f = Fakes(this)
        val url = "https://u:p@indexer.example.org/api?r=token"
        val i = settings.saveIndexer(null, "Idx", url, "SAVED-KEY")
        val tester = f.tester(this)
        assertEquals(SetupOutcome.Passed, run(tester, item(IndexerDraft("Idx", shownAddress(url).url, ""), ref = i)).outcome)
        assertEquals(url, f.indexers.single().baseUrl)
        assertEquals("SAVED-KEY", f.indexers.single().apiKey)
    }

    /**
     * A failed test's words can hold the address it went to whole (Ktor's connect and read timeouts do): tested at a
     * saved address, that is its hidden login, query and fragment, which the page must never show. Each address in the
     * words keeps only what a page may show of it.
     */
    @Test
    fun `a failed indexer test's words keep no login, query or fragment of an address in them`() = runTest {
        val f = Fakes(this)
        val url = "https://alice:marker-pass@indexer.example.org/api?r=marker-token#marker-fragment"
        val i = settings.saveIndexer(null, "Idx", url, "SAVED-KEY")
        f.indexer = { config -> IndexerTest.Failed("Connect timeout has expired [url=${config.baseUrl}&t=caps&apikey={apikey}, connect_timeout=unknown ms]") }
        val tester = f.tester(this)
        val saved = run(tester, item(IndexerDraft("Idx", shownAddress(url).url, ""), ref = i))
        assertEquals(SetupOutcome.Failed, saved.outcome)
        assertEquals("Connect timeout has expired [url=https://indexer.example.org/api, connect_timeout=unknown ms]", saved.words)
        assertFalse("marker" in saved.words, saved.words)
        // A typed one the same, and words with no address in them stay as they are.
        f.indexer = { config -> IndexerTest.Failed("Socket timeout has expired [url=${config.baseUrl}?apikey=marker-key] after http://u:marker@h.example.org:9696/x") }
        val typed = run(tester, item(IndexerDraft("Idx", "https://u:marker@indexer.example.org/api", "INDEXER-KEY")))
        assertEquals("Socket timeout has expired [url=https://indexer.example.org/api] after http://h.example.org:9696/x", typed.words)
        f.indexer = { IndexerTest.Failed("No answer within 15 s") }
        assertEquals(failed("No answer within 15 s"), run(tester, item(indexer)))
    }

    /**
     * Whoever holds the code chooses where a test goes: each way the server could be another one than the saved
     * secret's (the attacks the phone setup's research lists) is refused, and no test is handed the saved secret.
     */
    @Test
    fun `a saved secret never goes to another server`() = runTest {
        val f = Fakes(this)
        val p = settings.saveProvider(ProviderRecord("", "Main", "news.provider.sk", 563, "alice"), "saved-pass")
        val other = settings.saveProvider(ProviderRecord("", "Other", "other.example.net", 563, "bob"), "other-pass")
        val i = settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY")
        val tester = f.tester(this)
        val asSaved = ProviderDraft("Main", "news.provider.sk", 563, true, "alice", "", 8)
        val providers = mapOf(
            "another host" to asSaved.copy(host = "news.evil.example"),
            "the plain port with TLS off" to asSaved.copy(port = 119, useTls = false),
            "TLS off" to asSaved.copy(useTls = false),
            "another port" to asSaved.copy(port = 443),
            "another username" to asSaved.copy(username = "mallory"),
            "a look-alike host" to asSaved.copy(host = "news.provıder.sk"),
        )
        for ((why, draft) in providers) assertEquals(notTested(PHONE_TEST_SERVER_CHANGED), run(tester, item(draft, ref = p)), why)
        // Another record's card with this one's server: bound to its own record, whose server it isn't.
        assertEquals(notTested(PHONE_TEST_SERVER_CHANGED), run(tester, item(asSaved, ref = other)), "another card's record")
        assertEquals(notTested(PHONE_TEST_NO_PASSWORD), run(tester, item(asSaved, ref = null)), "a new card")
        val asShown = IndexerDraft("Idx", "https://indexer.example.org/api", "")
        val indexers = mapOf(
            "another host" to asShown.copy(url = "https://indexer.example.net/api"),
            "a login hiding the host" to asShown.copy(url = "https://indexer.example.org%2Fapi@evil.example/api"),
            "another port" to asShown.copy(url = "https://indexer.example.org:8443/api"),
            "down to http" to asShown.copy(url = "http://indexer.example.org/api"),
            "another path" to asShown.copy(url = "https://indexer.example.org/other/api"),
            "parsers that disagree" to asShown.copy(url = "https://indexer.example.org\\@evil.example/api"),
        )
        for ((why, draft) in indexers) {
            val result = run(tester, item(draft, ref = i, plainHttp = true))
            assertTrue(result == notTested(PHONE_TEST_ADDRESS_CHANGED) || result.outcome == SetupOutcome.Failed, "$why: $result")
        }
        assertEquals(notTested(PHONE_TEST_NO_KEY), run(tester, item(asShown, ref = null)), "a new card")
        assertFalse(f.secretsTested.any { it in listOf("saved-pass", "other-pass", "SAVED-KEY") }, "a saved secret went to another server: ${f.secretsTested}")
    }

    /** The record is read when the test runs: one changed on the TV meanwhile is what the card is compared with. */
    @Test
    fun `a record changed on the TV since the page opened is compared as it is now`() = runTest {
        val f = Fakes(this)
        val p = settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "saved-pass")
        val tester = f.tester(this)
        val asOpened = ProviderDraft("Main", "news.example.net", 563, true, "alice", "", 8)
        settings.saveProvider(ProviderRecord(p, "Main", "moved.example.net", 563, "alice"), null)
        assertEquals(notTested(PHONE_TEST_SERVER_CHANGED), run(tester, item(asOpened, ref = p)))
        settings.deleteProvider(p)
        assertEquals(notTested(PHONE_TEST_NO_PASSWORD), run(tester, item(asOpened, ref = p)), "deleted meanwhile")
        assertTrue(f.providers.isEmpty())
    }

    @Test
    fun `a saved password or key this TV can't read isn't tested, and says so`() = runTest {
        val f = Fakes(this)
        cipher.unreadable += listOf("lost-pass", "LOST-KEY")
        val p = settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "lost-pass")
        val i = settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "LOST-KEY")
        val tester = f.tester(this)
        assertEquals(notTested(PHONE_TEST_PASSWORD_UNREADABLE), run(tester, item(ProviderDraft("Main", "news.example.net", 563, true, "alice", "", 8), ref = p)))
        assertEquals(notTested(PHONE_TEST_KEY_UNREADABLE), run(tester, item(IndexerDraft("Idx", "https://indexer.example.org/api", ""), ref = i)))
        assertEquals("the password saved on the TV can't be read: type it.", PHONE_TEST_PASSWORD_UNREADABLE)
        assertEquals("the server changed, so type the password to test it here.", PHONE_TEST_SERVER_CHANGED)
        assertTrue(f.providers.isEmpty() && f.indexers.isEmpty())
    }

    @Test
    fun `a provider without a username is tested without a login`() = runTest {
        val f = Fakes(this)
        assertEquals(SetupOutcome.Passed, run(f.tester(this), item(ProviderDraft("", "10.0.2.2", 1119, false, "", "", null))).outcome)
        val (form, password) = f.providers.single()
        assertEquals(listOf("10.0.2.2", "1119", false, "", "8", ""), listOf(form.host, form.port, form.useTls, form.username, form.connections, password))
    }

    @Test
    fun `the forms' own checks come first, and what fails them isn't tested`() = runTest {
        val f = Fakes(this)
        val tester = f.tester(this)
        val providerChecks = mapOf(
            provider.copy(host = "") to PHONE_TEST_NO_HOST,
            provider.copy(host = "alice:secret@news.example.net") to "Only the server's name: the username and password go in their own fields.",
            provider.copy(host = "nntps://news.example.net") to "Only the server's name, without nntps:// or nntp://.",
            provider.copy(port = 0) to PROVIDER_PORT_RANGE,
            provider.copy(port = 65536) to PROVIDER_PORT_RANGE,
            provider.copy(connections = 0) to PROVIDER_CONNECTIONS_RANGE,
            provider.copy(connections = 101) to PROVIDER_CONNECTIONS_RANGE,
            // A check that fails comes before a missing password.
            provider.copy(port = 0, password = "") to PROVIDER_PORT_RANGE,
        )
        for ((draft, words) in providerChecks) assertEquals(failed(words), run(tester, item(draft)), draft.toString())
        val indexerChecks = mapOf(
            indexer.copy(url = " ") to PHONE_TEST_NO_URL,
            indexer.copy(url = "ftp://indexer.example.org/api") to INDEXER_URL_SCHEME,
            indexer.copy(url = "indexer.example.org/api") to INDEXER_URL_SCHEME,
            indexer.copy(url = "ftp://indexer.example.org/api", apiKey = "") to INDEXER_URL_SCHEME,
        )
        for ((draft, words) in indexerChecks) assertEquals(failed(words), run(tester, item(draft)), draft.toString())
        assertTrue(f.providers.isEmpty() && f.indexers.isEmpty(), "tested what failed a check")
        assertEquals("The port must be from 1 to 65535.", PROVIDER_PORT_RANGE)
        assertEquals("Connections must be from 1 to 100.", PROVIDER_CONNECTIONS_RANGE)
        assertEquals("The API URL must start with http:// or https://.", INDEXER_URL_SCHEME)
        // A blank port or connections is the default, as on the TV.
        assertEquals(SetupOutcome.Passed, run(tester, item(provider.copy(port = null, connections = null, useTls = true))).outcome)
        assertEquals("563" to "8", f.providers.single().first.let { it.port to it.connections })
    }

    /**
     * The key would cross the internet unencrypted: the page warns and offers Test anyway, and only then is it tested.
     * Plain http on the home network is tested, as the indexer form tests it without a warning.
     */
    @Test
    fun `an indexer over plain http on the internet is tested only after Test anyway`() = runTest {
        val f = Fakes(this)
        val tester = f.tester(this)
        for (draft in listOf(indexer.copy(url = "http://indexer.example.org/api"), indexer.copy(url = " http://203.0.113.7:9696 "))) {
            assertEquals(SetupResult(SetupOutcome.NotTested, PHONE_TEST_PUBLIC_HTTP, plainHttp = true), run(tester, item(draft)), draft.toString())
        }
        assertTrue(f.indexers.isEmpty(), "sent the key over plain http")
        assertEquals(SetupOutcome.Passed, run(tester, item(indexer.copy(url = "http://indexer.example.org/api"), plainHttp = true)).outcome)
        assertEquals(SetupOutcome.Passed, run(tester, item(indexer.copy(url = "http://192.168.1.20:9696/api"))).outcome)
        assertEquals(listOf("http://indexer.example.org/api", "http://192.168.1.20:9696/api"), f.indexers.map { it.baseUrl })
    }

    /** Its caps are forgotten after a test: under an id no saved indexer has, no saved indexer's are touched. */
    @Test
    fun `an indexer is tested under a throwaway id, a new one each time`() = runTest {
        val f = Fakes(this)
        val saved = settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY")
        val tester = f.tester(this)
        run(tester, item(indexer))
        run(tester, item(indexer.copy(apiKey = "", url = "https://indexer.example.org/api"), ref = saved))
        val (first, second) = f.indexers.map { it.id }
        assertTrue(first.startsWith("phone-test-") && second.startsWith("phone-test-"), "$first $second")
        assertNotEquals(first, second)
    }

    @Test
    fun `a test that doesn't finish in time says so, and is stopped`() = runTest {
        val f = Fakes(this)
        var stopped = false
        f.provider = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val future = f.tester(this).test(item(provider))
        advanceTimeBy(25_000 - 1)
        runCurrent()
        assertFalse(future.isDone)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(failed(PHONE_TEST_UNFINISHED), future.get(0, TimeUnit.SECONDS))
        assertEquals("The test didn't finish in time.", PHONE_TEST_UNFINISHED)
        assertTrue(stopped)
    }

    @Test
    fun `a test that breaks is a failure, its message not shown`() = runTest {
        val f = Fakes(this)
        f.indexer = { throw IllegalStateException("no caps for marker-host") }
        assertEquals(failed(SetupPage.COULDNT_RUN), run(f.tester(this), item(indexer)))
    }

    /** The server cancels a test it gives up on, or that a newer one replaces: the work stops. */
    @Test
    fun `cancelling the future stops the test`() = runTest {
        val f = Fakes(this)
        var stopped = 0
        f.provider = { _, _ ->
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                stopped++
                throw e
            }
        }
        val future = f.tester(this).test(item(provider))
        runCurrent()
        future.cancel(true)
        runCurrent()
        assertEquals(1, stopped)
    }

    /**
     * Each pass is logged with exactly what it tested, the secret it used included (as a digest), so the TV's review
     * can tell what passed from the phone with the values it would save; a failure, or a card not tested, isn't. The
     * tester writes nothing.
     */
    @Test
    fun `a pass is logged with exactly what it tested, and nothing is saved`() = runTest {
        val f = Fakes(this)
        val p = settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 443, "alice", connections = 12), "saved-pass")
        val before = settings.settings.first()
        val tester = f.tester(this)
        run(tester, item(provider.copy(password = ""), ref = p))
        run(tester, item(indexer))
        run(tester, SetupTestItem.Tmdb("tmdb-key-0123456789abcdef"))
        val login = ProviderLogin.of("news.example.net", 443, "alice", true, "saved-pass")
        assertEquals(true, f.passes.provider(login, 12)?.ok)
        assertNull(f.passes.provider(login, 13), "other connections")
        assertNull(f.passes.provider(ProviderLogin.of("news.example.net", 443, "alice", true, "typed"), 12), "another password")
        assertEquals("Connected · Example 1.2", f.passes.indexer("https://indexer.example.org/api", "INDEXER-KEY-0000-1111"))
        assertNull(f.passes.indexer("https://indexer.example.org/api", "OTHER-KEY"))
        assertTrue(f.passes.tmdb("tmdb-key-0123456789abcdef"))
        assertFalse(f.passes.tmdb("another-key"))
        // A failure isn't a pass.
        f.indexer = { IndexerTest.Failed("No answer within 15 s") }
        run(tester, item(indexer.copy(apiKey = "FAILING-KEY")))
        assertNull(f.passes.indexer("https://indexer.example.org/api", "FAILING-KEY"))
        assertEquals(before, settings.settings.first())
        assertEquals("saved-pass", settings.provider(p)?.password)
        assertFalse(listOf("saved-pass", "INDEXER-KEY", "tmdb-key").any { it in f.passes.toString() }, f.passes.toString())
    }

    private companion object {
        fun result(f: ProviderForm, password: String, ok: Boolean) = ProviderTestResult(
            ProviderLogin.of(f.host, f.portValue ?: 563, f.username, f.useTls, password), ok,
            if (ok) "Connected and logged in to ${f.host.trim()}" else "The provider rejected the username or password.",
        )
    }
}

package io.skjaere.usenet.app.setup

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.IndexerRoute
import io.skjaere.usenet.app.IndexersRoute
import io.skjaere.usenet.app.PhoneSetupRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.settings.ProviderStatus
import io.skjaere.usenet.app.settings.SavedProvider
import io.skjaere.usenet.app.settings.login
import io.skjaere.usenet.app.settings.providerRows
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.tv.tvHome
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.handoff.SetupPage
import io.skjaere.usenet.metadata.KeyCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * "Set up with your phone" end to end, over the app's real settings (taken away and put back afterwards, secrets
 * included), this device standing in for the phone at its own private address. The wizard: two providers on step 1 (the
 * mock NNTP server, TLS off, which works; one on a port nothing listens on, which fails and isn't added), the fake
 * indexer on step 2, the TMDB key skipped, Send; the TV's review, nothing saved until Save, which tests nothing the
 * phone tested and saves both; Done goes home and the port is closed. The edit page: a saved provider changed, another
 * removed, the saved indexer tested with its saved key, a TMDB key FakeTmdb takes, Send and Save. The debrid cards
 * (L209): a TorBox account and a torrent indexer added in the wizard (the debrid and Torznab fakes), tested on the TV
 * from the page, sent, and saved. Also: Discard saves nothing, five wrong codes close the page, Done from a form closes
 * the form too. Debrid accounts and torrent indexers are taken away and put back as the providers are. No assertion
 * message holds a saved value: debug builds hold real ones.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val remote = Remote(compose)
    private lateinit var nav: NavHostController
    private lateinit var saved: AppSettings
    /** Passwords and API keys by secret name, and the TMDB key, as they were. */
    private var secrets = emptyMap<String, String>()
    private var tmdbKey: String? = null

    @Before
    fun clearSources() = runBlocking {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.any { it is Inet4Address && it.isSiteLocalAddress }
        assumeTrue("this device has no private IPv4 address for the page", own)
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable("10.0.2.2", 1119))
        assumeTrue("the mock NNTP server's control API isn't reachable at 10.0.2.2:8081", runCatching { statMissing() }.isSuccess)
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089", reachable("10.0.2.2", 8089))
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap() +
            saved.activeServices.mapNotNull { a -> settings.service(a.id)?.apiKey?.let { a.apiKeySecret to it } }.toMap() +
            saved.activeTorrentIndexers.mapNotNull { r -> settings.torrentIndexer(r.id)?.apiKey?.let { r.apiKeySecret to it } }.toMap()
        tmdbKey = settings.tmdbKey()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // A service and a torrent indexer saved would make the edit page of the wizard's cases (L165).
        saved.activeServices.forEach { settings.signOut(it.id) }
        saved.activeTorrentIndexers.forEach { settings.deleteTorrentIndexer(it.id) }
        settings.setTmdbKey(null)
        settings.setIndexerSetupSkipped(false)
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach {
            settings.deleteProvider(it.id)
            container.providerTests.forget(it.id)
        }
        settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
        settings.services.first().filter { a -> saved.services.none { it.id == a.id } }.forEach { settings.signOut(it.id) }
        settings.torrentIndexers.first().filter { r -> saved.torrentIndexers.none { it.id == r.id } }.forEach { settings.deleteTorrentIndexer(it.id) }
        // The secrets first: the app's provider sync reads them as the providers come back, and nothing else would wake it.
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        settings.restore(saved)
        settings.setTmdbKey(tmdbKey)
        assertEquals(saved, settings.settings.first())
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    private fun reachable(host: String, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 2_000) } }.isSuccess

    /** Has the mock NNTP server answer a provider test's STAT with 430, through its control API (mock server issue M1). */
    private fun statMissing() {
        val connection = URL("http://10.0.2.2:8081/mocks/stat").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"articleId":"<provider-test@usenet-streamer.invalid>","exists":false}""".encodeToByteArray()) }
            check(connection.responseCode in 200..299) { "the mock answered ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)

    private fun showTheApp() {
        compose.setTvContent { nav = rememberNavController(); AppNav(nav = nav) }
        compose.waitForIdle()
    }

    /** OK on [words], a button on screen. */
    private fun ok(words: String) {
        compose.waitUntil(10_000) { exists(hasText(words) and hasClickAction()) }
        compose.onNode(hasText(words) and hasClickAction()).requestFocus()
        remote.waitForFocus(words)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** The page's address as it shows it: "ip:port/" and the code in fours. */
    private fun shown(): String {
        compose.waitUntil(10_000) { exists(addressNode) }
        return compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")
    }

    private fun authority(address: String) = address.substringBefore('/').let { it.substringBefore(':') to it.substringAfter(':').toInt() }

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String) {
        /** Its headers and body: all a page could leak through. */
        val everything: String get() = headers.values.joinToString("\n") + "\n" + body
    }

    /**
     * A request to the page as a phone's browser makes it: [form] posted urlencoded, else a GET. A form that is read is
     * answered with a 303 back to the page, which is followed with a GET, as a browser does, and its page returned.
     */
    private fun request(address: String, form: List<Pair<String, String>>? = null): Answer {
        val answer = exchange("http://$address", form)
        if (answer.status != 303) return answer
        val location = answer.headers.getValue("Location")
        assertTrue("a 303 to the page itself", location.startsWith("/") && !location.startsWith("//"))
        return exchange("http://${address.substringBefore('/')}${location.substringBefore('#')}", null)
    }

    private fun exchange(url: String, form: List<Pair<String, String>>?): Answer {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 40_000
            if (form != null) {
                val body = form.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Answer(status, connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString() }, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun closed(address: String): Boolean = authority(address).let { (host, port) -> !reachable(host, port) }

    /** The first run's way in, to the page, and its address. */
    private fun openFromTheFirstRun(): String {
        showTheApp()
        compose.waitUntil(10_000) { exists(hasText("Set up your sources")) }
        ok(PHONE_SETUP_TITLE)
        return shown()
    }

    /** The mock NNTP server, no login, as a provider card's fields. */
    private val provider = listOf(
        "p.name" to "Mock (phone setup test)", "p.host" to "10.0.2.2", "p.port" to "1119", "p.user" to "", "p.pass" to "", "p.conns" to "2",
    )
    /** A provider on a port nothing listens on: its test fails. */
    private val failing = provider.map { (name, value) -> name to if (name == "p.port") "9" else if (name == "p.name") "Nowhere (phone setup test)" else value }
    private val indexer = listOf("i.name" to "Fake (phone setup test)", "i.url" to "http://10.0.2.2:8089", "i.key" to "fake-indexer-key")
    private val blankTmdb = listOf("t.key" to "")

    /** The page's render counter, as its form posts it. */
    private fun version(page: Answer) = Regex("""name="v" value="([0-9]+)"""").find(page.body)?.groupValues?.get(1) ?: error("no form on the page")

    /** [action] pressed on [page], its open card holding [fields]: the page after it, or Send's own answer. */
    private fun press(address: String, page: Answer, action: String, fields: List<Pair<String, String>> = emptyList()) =
        request(address, listOf("v" to version(page), "do" to action) + fields)

    /** Every page and answer of one test, to check none holds what mustn't leave the TV. */
    private val answers = mutableListOf<Answer>()

    private fun Answer.kept() = also { answers += it }

    /**
     * The wizard as a phone goes through it: two providers on step 1, the failing one tried and then not added, the
     * indexer on step 2, the TMDB key skipped, Send. Each answer as it comes, the TV's Waiting line followed as it goes.
     */
    @Test
    fun aWholeWizardIsSentThenSavedOnTheTv() {
        assumeTrue("something listens on 10.0.2.2:9, the port the failing provider uses", !reachable("10.0.2.2", 9))
        val address = openFromTheFirstRun()
        val (host, port) = authority(address)
        assertTrue("the page listens on a private address", (java.net.InetAddress.getByName(host) as Inet4Address).isSiteLocalAddress)
        assertFalse("the page listens on loopback too", reachable("127.0.0.1", port))

        var page = request(address).kept()
        assertEquals(200, page.status)
        assertEquals("no-store", page.headers["Cache-Control"])
        assertEquals("same-origin", page.headers["Referrer-Policy"])
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
        assertEquals(SetupPage.contentSecurityPolicy, page.headers["Content-Security-Policy"])
        assertTrue(page.body.contains("Step 1 of 4"))

        page = press(address, page, "test", provider).kept()
        assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected and logged in to 10.0.2.2</p>"))
        compose.waitUntil(10_000) { exists(hasText("Tested from your phone: Provider 1 works")) }
        compose.waitUntil(10_000) { exists(hasText("On your phone: step 1 of 4, providers")) }
        page = press(address, page, "add:provider", provider).kept()
        page = press(address, page, "test", failing).kept()
        assertEquals("one ✗ line", 1, Regex("<p class=\"result fail\">✗ ").findAll(page.body).count())
        compose.waitUntil(10_000) { exists(hasText("Tested from your phone: Provider 1 works · Provider 2 failed")) }
        // Next waits for it; Don't add this one lets it go.
        page = press(address, page, "next", failing).kept()
        assertTrue("on past a failing card", page.body.contains("Step 1 of 4"))
        page = press(address, page, "drop:p2", failing).kept()
        page = press(address, page, "next").kept()
        assertTrue(page.body.contains("Step 2 of 4"))
        compose.waitUntil(10_000) { exists(hasText("On your phone: step 2 of 4, indexers")) }
        page = press(address, page, "test", indexer).kept()
        assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected"))
        assertFalse("the key typed came back", page.body.contains("fake-indexer-key"))
        page = press(address, page, "next", indexer).kept()
        assertTrue(page.body.contains("Step 3 of 4"))
        page = press(address, page, "next", blankTmdb).kept()
        assertTrue(page.body.contains("Step 4 of 4"))
        assertEquals("tests save nothing", 0, runBlocking { settings.providers.first().size + settings.indexers.first().size })
        val sent = press(address, page, "send").kept()
        assertEquals(200, sent.status)
        assertTrue(sent.body.contains("Sent. Check your TV."))

        compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
        assertTrue(exists(hasText("2 new")))
        assertTrue(exists(hasText("Usenet provider · New")) && exists(hasText("Indexer · New")))
        assertEquals("both tested from the phone", 2, compose.onAllNodes(hasText("✓ $PHONE_TESTED")).fetchSemanticsNodes().size)
        assertEquals("nothing saved before Save", 0, runBlocking { settings.providers.first().size + settings.indexers.first().size })
        compose.waitUntil(5_000) { closed(address) }
        answers.forEach { assertFalse("an answer holds the key typed", it.body.contains("fake-indexer-key")) }

        remote.waitForFocus(PHONE_SETUP_SAVE)
        Thread.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(20_000) { exists(hasText(PHONE_SETUP_DONE) and hasClickAction()) }
        val savedProvider = runBlocking { settings.providers.first().single() }
        assertEquals(listOf("10.0.2.2", 1119, false, "Mock (phone setup test)"), listOf(savedProvider.host, savedProvider.port, savedProvider.useTls, savedProvider.name))
        val savedIndexer = runBlocking { settings.indexers.first().single() }
        assertEquals("http://10.0.2.2:8089", savedIndexer.baseUrl)
        assertEquals("the key typed", true, runBlocking { settings.indexer(savedIndexer.id)?.apiKey } == "fake-indexer-key")
        assertEquals("the TMDB key skipped", null, runBlocking { settings.tmdbKey() })
        // The providers list shows the phone's test for the provider as saved.
        val login = runBlocking { settings.provider(savedProvider.id)?.login }
        val row = providerRows(listOf(SavedProvider(savedProvider, login)), container.providerTests.results.value, testing = null).single()
        assertEquals(ProviderStatus.Connected, row.status)

        remote.waitForFocus(PHONE_SETUP_DONE)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(tvHome) }
        assertTrue(closed(address))
    }

    /**
     * The edit page, once a provider and an indexer are saved: it shows them by name, never a password or key; one
     * provider changed and tested, another removed, the saved indexer tested with its saved key (the same server), a
     * TMDB key FakeTmdb takes. The review says what changes; Save saves it all, testing nothing the phone tested.
     */
    @Test
    fun theEditPageChangesOneProviderAndRemovesAnother() {
        val (mock, old) = runBlocking {
            settings.saveProvider(ProviderRecord("", "Mock (phone setup test)", "10.0.2.2", 1119, "", useTls = false, connections = 2), "") to
                settings.saveProvider(ProviderRecord("", "Old (phone setup test)", "old.example.invalid", 563, "someone"), INVENTED_PASSWORD)
        }
        val fake = runBlocking { settings.saveIndexer(null, "Fake (phone setup test)", "http://10.0.2.2:8089", "fake-indexer-key") }
        FakeTmdb(keys = { if (it == INVENTED_TMDB_KEY) KeyCheck.Valid else null }).use {
            showTheApp()
            compose.runOnIdle { nav.navigate(PhoneSetupRoute) }
            val address = shown()
            compose.waitUntil(10_000) { exists(hasText("2  $PHONE_SETUP_CHANGE")) }
            var page = request(address).kept()
            assertTrue("the saved ones by name", listOf("Mock (phone setup test)", "Old (phone setup test)", "Fake (phone setup test)").all { it in page.body })

            val renamed = provider.map { (name, value) -> name to if (name == "p.name") "Mock renamed (phone setup test)" else if (name == "p.conns") "3" else value }
            page = press(address, page, "open:p1").kept()
            page = press(address, page, "test", renamed).kept()
            assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected and logged in to 10.0.2.2</p>"))
            page = press(address, page, "drop:p2", renamed).kept()
            assertTrue(page.body.contains("Will be removed"))
            // The saved indexer, its key left empty: its own saved key, its server unchanged.
            page = press(address, page, "test:i1", renamed).kept()
            assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected"))
            compose.waitUntil(10_000) { exists(hasText("Tested from your phone: Provider 1 works · Indexer 1 works")) }
            // A new card at the same address has no key of its own.
            page = press(address, page, "add:indexer", renamed).kept()
            page = press(address, page, "test", indexer.map { if (it.first == "i.key") it.first to "" else it }).kept()
            assertTrue("tested without a key", page.body.contains("<p class=\"result\">Not tested: $PHONE_TEST_NO_KEY</p>"))
            page = press(address, page, "drop:i2", indexer.map { if (it.first == "i.key") it.first to "" else it }).kept()
            page = press(address, page, "open:t").kept()
            page = press(address, page, "test", listOf("t.key" to INVENTED_TMDB_KEY)).kept()
            assertTrue(page.body.contains("✓ Key works"))
            answers.forEach { answer ->
                assertTrue("an answer holds a saved or typed secret", listOf(INVENTED_PASSWORD, "fake-indexer-key", INVENTED_TMDB_KEY).none { it in answer.everything })
            }
            assertEquals(200, press(address, page, "send", blankTmdb).status)

            compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
            assertTrue(exists(hasText("1 new · 1 changed · 1 to remove")))
            assertTrue(exists(hasText("Usenet provider · Changes “Mock (phone setup test)”")))
            assertTrue(exists(hasText("Connections: 2 → 3")))
            assertTrue(exists(hasText("Usenet provider · Removes “Old (phone setup test)”")))
            assertTrue(exists(hasText("old.example.invalid:563 · its saved password is deleted too")))
            assertTrue(exists(hasText("Unchanged: Fake (phone setup test).")))
            assertEquals("tested from the phone", 2, compose.onAllNodes(hasText("✓ $PHONE_TESTED")).fetchSemanticsNodes().size)
            assertEquals("nothing saved before Save", listOf(mock, old), runBlocking { settings.providers.first().map { it.id } })
            remote.waitForFocus(PHONE_SETUP_SAVE)
            Thread.sleep(1_100)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(20_000) { exists(hasText(PHONE_SETUP_DONE) and hasClickAction()) }
            val providers = runBlocking { settings.providers.first() }
            assertEquals(listOf(mock), providers.map { it.id })
            assertEquals(listOf("Mock renamed (phone setup test)", 3, false), providers.single().let { listOf(it.name, it.connections, it.useTls) })
            assertEquals(listOf(fake), runBlocking { settings.indexers.first().map { it.id } })
            assertEquals("the saved key kept", true, runBlocking { settings.indexer(fake)?.apiKey } == "fake-indexer-key")
            assertEquals(true, runBlocking { settings.tmdbKey() } == INVENTED_TMDB_KEY)
            assertEquals(true, container.providerTests.results.value[mock]?.ok)
            remote.waitForFocus(PHONE_SETUP_DONE)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { !exists(hasText(PHONE_SETUP_REVIEW)) }
            assertTrue(closed(address))
        }
    }

    /** The form that arrived closed the port; Discard saves nothing and leaves the page, no new code opened. */
    @Test
    fun discardSavesNothing() {
        val address = openFromTheFirstRun()
        var page = request(address)
        page = press(address, page, "test", provider)
        page = press(address, page, "next", provider)
        page = press(address, page, "next")
        page = press(address, page, "next", blankTmdb)
        assertEquals(200, press(address, page, "send").status)
        compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
        compose.waitUntil(5_000) { closed(address) }
        remote.waitForFocus(PHONE_SETUP_SAVE)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(hasText(PHONE_SETUP_DISCARD_ASK)) }
        remote.waitForFocus("Keep")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.waitForFocus("Discard")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText("Set up your sources")) }
        assertEquals(0, runBlocking { settings.providers.first().size + settings.indexers.first().size })
        Thread.sleep(1_000)
        assertFalse(exists(addressNode))
    }

    @Test
    fun fiveWrongCodesCloseThePage() {
        val address = openFromTheFirstRun()
        val authority = address.substringBefore('/')
        val statuses = (1..5).map { request("$authority/${"0".repeat(25)}$it").status }
        assertEquals(listOf(404, 404, 404, 404, 410), statuses)
        compose.waitUntil(5_000) { exists(hasText(PHONE_SETUP_LOCKED)) }
        remote.waitForFocus("New code")
        compose.waitUntil(5_000) { closed(address) }
    }

    /**
     * L209: the wizard with debrid shown takes a debrid service on step 1 and a torrent indexer on step 2: a TorBox card
     * and a torrent indexer card at the Torznab fake's Prowlarr feed, each tested on the TV from the page (the debrid fake
     * answers for TorBox), then sent. The review lists both as New, tested from the phone, the key masked; Save keeps the
     * account (its key and the account's name as the service gave it) and the torrent indexer with its key, which
     * Settings then has, and nothing of either key ever came back to the phone.
     */
    @Test
    fun aDebridServiceAndATorrentIndexerAreSentThenSavedOnTheTv() {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        DebridFakes.assumeTorznab()
        val giveBack = DebridFakes.holdTheFake()
        val pointBack = DebridFakes.pointServicesAtTheFake()
        try {
            DebridFakes.control("reset")
            val address = openFromTheFirstRun()
            var page = request(address).kept()
            assertTrue(page.body.contains("Step 1 of 4"))
            assertTrue(page.body.contains("<h2>Debrid service</h2>"))
            // The blank provider card goes as the service's opens.
            page = press(address, page, "add:service").kept()
            val torbox = listOf("s.kind" to "torbox", "s.key" to DebridFakes.TORBOX_KEY)
            page = press(address, page, "test", torbox).kept()
            assertTrue(page.body.contains("<p class=\"result ok\">✓ Signed in as ${DebridFakes.TORBOX_ACCOUNT}"))
            compose.waitUntil(10_000) { exists(hasText("Tested from your phone: Debrid service 1 works")) }
            page = press(address, page, "next", torbox.map { if (it.first == "s.key") it.first to "" else it }).kept()
            assertTrue(page.body.contains("Step 2 of 4"))
            page = press(address, page, "add:torrent-indexer").kept()
            val torrent = listOf("r.name" to "Home Prowlarr (phone setup test)", "r.url" to DebridFakes.PROWLARR_URL, "r.key" to DebridFakes.TORZNAB_KEY, "r.debrid" to "on")
            page = press(address, page, "test", torrent).kept()
            assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected"))
            page = press(address, page, "next", torrent.map { if (it.first == "r.key") it.first to "" else it }).kept()
            page = press(address, page, "next", blankTmdb).kept()
            assertTrue(page.body.contains("Step 4 of 4"))
            assertEquals("tests save nothing", 0, runBlocking { settings.services.first().size + settings.torrentIndexers.first().size })
            assertEquals(200, press(address, page, "send").kept().status)

            compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
            assertTrue(exists(hasText("2 new")))
            assertTrue(exists(hasText("Debrid service · New")) && exists(hasText("Torrent indexer · New")))
            assertTrue(exists(hasText("TorBox · API key ${maskKey(DebridFakes.TORBOX_KEY)}")))
            assertEquals("both tested from the phone", 2, compose.onAllNodes(hasText("✓ $PHONE_TESTED")).fetchSemanticsNodes().size)
            assertEquals("nothing saved before Save", 0, runBlocking { settings.services.first().size + settings.torrentIndexers.first().size })
            compose.waitUntil(5_000) { closed(address) }
            answers.forEach { answer -> assertTrue("an answer holds a key typed", listOf(DebridFakes.TORBOX_KEY, DebridFakes.TORZNAB_KEY).none { it in answer.everything }) }

            remote.waitForFocus(PHONE_SETUP_SAVE)
            Thread.sleep(1_100)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(20_000) { exists(hasText(PHONE_SETUP_DONE) and hasClickAction()) }
            val account = runBlocking { settings.servicesWithKeys().single() }
            assertEquals(listOf("TorBox", DebridFakes.TORBOX_ACCOUNT, true), listOf(account.record.kind, account.record.accountName, account.record.enabled))
            assertEquals("the key typed", true, account.apiKey == DebridFakes.TORBOX_KEY)
            val indexer = runBlocking { settings.torrentIndexers.first().single() }
            assertEquals(listOf("Home Prowlarr (phone setup test)", DebridFakes.PROWLARR_URL, true, true), listOf(indexer.name, indexer.baseUrl, indexer.useWithService, indexer.enabled))
            assertEquals("its key typed", true, runBlocking { settings.torrentIndexer(indexer.id)?.apiKey } == DebridFakes.TORZNAB_KEY)
            assertEquals("no provider or indexer", 0, runBlocking { settings.providers.first().size + settings.indexers.first().size })

            remote.waitForFocus(PHONE_SETUP_DONE)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            // The torrent pair finishes the first run.
            compose.waitUntil(10_000) { !exists(hasText(PHONE_SETUP_REVIEW)) && !exists(hasText("Set up your sources")) }
            assertTrue(closed(address))
        } finally {
            pointBack()
            giveBack()
        }
    }

    /** A form under the page read its record once: Done closes it too, so its Save can't write over what was saved. */
    @Test
    fun doneFromAFormClosesTheFormToo() {
        showTheApp()
        compose.runOnIdle { nav.navigate(IndexersRoute); nav.navigate(IndexerRoute()) }
        ok(PHONE_SETUP_TITLE)
        val address = shown()
        var page = request(address)
        // No provider: step 1 left empty.
        page = press(address, page, "next")
        page = press(address, page, "test", indexer)
        page = press(address, page, "next", indexer)
        page = press(address, page, "next", blankTmdb)
        assertEquals(200, press(address, page, "send").status)
        compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
        remote.waitForFocus(PHONE_SETUP_SAVE)
        Thread.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(30_000) { exists(hasText(PHONE_SETUP_DONE) and hasClickAction()) }
        remote.waitForFocus(PHONE_SETUP_DONE)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.runOnIdle { nav.currentDestination?.hasRoute(IndexersRoute::class) == true } }
        assertEquals("http://10.0.2.2:8089", runBlocking { settings.indexers.first().single().baseUrl })
    }

    private companion object {
        const val INVENTED_TMDB_KEY = "0000invented0000tmdb0000key00000"
        const val INVENTED_PASSWORD = "invented-old-password-0000"
    }
}

package io.skjaere.usenet.app.debrid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.settings.PHONE_SETUP_HUB_ROW
import io.skjaere.usenet.app.settings.phoneSetupHubLine
import io.skjaere.usenet.app.setup.ADDRESS
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.setup.PhoneSetupPart
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.SetupPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * A build with debrid hidden offers no way in (L192), over what a build that showed it saved earlier: a TorBox account
 * and Home Prowlarr, saved with invented sign-in facts (nothing asks a service). The gate is closed before each test
 * makes the app's screens, as -PhideDebrid=true, or a beta built without -PwithDebrid=true, closes it. Settings has
 * neither debrid row, on a phone or in a TV's layout, whose Set up with your phone line is today's; First run, with no
 * Usenet provider or indexer, shows Usenet's way alone though the torrent pair is saved; the setup page has no debrid
 * card and refuses a form naming one; and a search asks Usenet's indexer alone: no torrent indexer's pill, no release's
 * tag, no cache check at the debrid fake. What was saved stays saved. The device's own sources are taken away for each
 * test and put back after, keys included.
 */
@RunWith(AndroidJUnit4::class)
class DebridHiddenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()
    private var account: String? = null
    private var prowlarr: String? = null
    private var fakeBack: (() -> Unit)? = null
    private var endpointsBack: (() -> Unit)? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        // Closed before any settings change below, so what the app works out from them (First run's pairs) is hidden's.
        DebridGate.shownForTests = false
        // The debug build's own settings saved first: else they could land after this test took the settings away.
        container.devSettingsSaved.await()
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        settings.setIndexerSetupSkipped(false)
        // What a build that showed debrid saved: the torrent pair, which hidden counts for nothing.
        account = settings.saveService(ServiceKind.TorBox.name, "invented-hidden-build-key") { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
        prowlarr = settings.saveTorrentIndexer(null, "Home Prowlarr", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
    }

    /**
     * Each step whatever the one before it did: a debrid fake that stopped answering, or the check that what a shown
     * build saved stayed saved failing, mustn't leave the gate closed, the clients at the fake, or the device's sources
     * and keys away for the classes after this one.
     */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            { if (fakeBack != null) DebridFakes.control("reset") },
            { fakeBack?.invoke() },
            { endpointsBack?.invoke() },
            { DebridGate.shownForTests = null },
            { putBack() },
        )
    }

    /** The device's sources as they were, keys included; then the check of the torrent pair, read before any of it. */
    private suspend fun putBack() {
        if (!::saved.isInitialized) return
        val services = settings.services.first().filter { !it.deleted }.map { it.id }
        val torrentIndexers = settings.torrentIndexers.first().filter { !it.deleted }.map { it.id }
        // Their keys go with them (restore only puts back the settings document).
        account?.let { settings.signOut(it) }
        prowlarr?.let { settings.deleteTorrentIndexer(it) }
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
        settings.restore(saved)
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        // Still saved, as a shown build left them (as far as setUp got).
        account?.let { assertEquals(listOf(it), services) }
        prowlarr?.let { assertEquals(listOf(it), torrentIndexers) }
        assertEquals(saved, settings.settings.first())
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(DebridFakes.HOST, port), 2_000) } }.isSuccess

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never: $what", e)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = waitFor(matcher.description, timeoutMs) { exists(matcher) }

    /** Scrolls whichever list holds [matcher]'s node to it (a lazy list lays out only what shows) and says whether it is there. */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean {
        if (exists(matcher)) return true
        val lists = compose.onAllNodes(hasScrollAction())
        repeat(lists.fetchSemanticsNodes().size) { i ->
            if (runCatching { lists[i].performScrollToNode(matcher) }.isSuccess && exists(matcher)) return true
        }
        return exists(matcher)
    }

    private fun click(matcher: SemanticsMatcher) {
        assertTrue("nothing to click for ${matcher.description}", scrolledTo(matcher))
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Settings' rows: the hub's, Sources' among them, as [assertNoDebridRow] reads them. */
    private fun openSettings() {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        waitFor(hasText("Usenet providers"))
        assertTrue("Indexers' row", exists(hasText("Indexers")))
        assertTrue("Metadata's row", exists(hasText("Metadata")))
    }

    private fun assertNoDebridRow() {
        assertFalse("a Debrid services row", exists(hasText("Debrid services")))
        assertFalse("a Torrent indexers row", exists(hasText("Torrent indexers")))
    }

    @Test
    fun settingsOnAPhoneHasNoDebridRow() {
        compose.setContent {
            nav = rememberNavController()
            AppTheme { AppNav(nav = nav) }
        }
        openSettings()
        assertNoDebridRow()
    }

    @Test
    fun settingsInATvsLayoutHasNoDebridRowAndThePhoneSetupLineIsTodays() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        openSettings()
        assertNoDebridRow()
        assertTrue("today's line under Set up with your phone", exists(hasText(PHONE_SETUP_HUB_ROW)))
        assertFalse("the line naming the debrid cards", exists(hasText(phoneSetupHubLine(shown = true))))
    }

    /** DbPhoneFirstRun's rule while hidden: the torrent pair saved counts for nothing, and only Usenet's way shows. */
    @Test
    fun firstRunShowsUsenetsWayAloneThoughTheTorrentPairIsSaved() {
        compose.setContent { AppTheme { AppNav() } }
        waitFor(hasText("Set up your sources"))
        assertTrue("Usenet's button", exists(hasText("Add a Usenet provider") and hasClickAction()))
        listOf("Add a debrid service", "Or torrents, through a debrid service", "Debrid service", "Torrent indexer", "Add a torrent indexer").forEach {
            assertFalse("\"$it\" shown", exists(hasText(it, ignoreCase = true)))
        }
        assertFalse("Home past First run", exists(hasText("Search your indexers")))
    }

    /**
     * L209 while hidden: the page a phone opens has no Debrid service or Torrent indexer section, on either of the
     * wizard's steps that would hold one, and a form naming one of their buttons or cards is refused as no page of this
     * TV's sends it: Add a debrid service and the saved account's card on step 1, Add a torrent indexer on step 2, where
     * a build that shows debrid takes each. The page is the wizard: hidden, the saved torrent pair makes nothing playable.
     */
    @Test
    fun theSetupPageHasNoDebridCardAndRefusesAFormNamingOne() {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.any { it is Inet4Address && it.isSiteLocalAddress }
        assumeTrue("this device has no private IPv4 address for the page", own)
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        waitFor(hasText("Set up your sources"))
        click(hasText(PHONE_SETUP_TITLE) and hasClickAction())
        val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)
        waitFor(addressNode)
        val address = compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")

        fun versionOf(page: String) = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
        fun refused(page: String, action: String) =
            assertEquals(action, 400 to SetupPage.rejected, exchange("http://$address", listOf("v" to versionOf(page), "do" to action)))

        val (status, page) = exchange("http://$address", null)
        assertEquals(200, status)
        assertTrue("the wizard", "Step 1 of 4" in page)
        assertFalse("a debrid service section", "Debrid service" in page)
        assertFalse("a torrent indexer section", "Torrent indexer" in page)
        assertTrue("step 1's line that nothing plays, the saved account standing in for nothing", SetupPage.NO_PROVIDER in page)
        refused(page, "add:service")
        refused(page, "open:s1")

        // Step 2, where a build that shows debrid has its Torrent indexers section: its blank provider card goes as it is.
        assertEquals(303, exchange("http://$address", listOf("v" to versionOf(page), "do" to "next")).first)
        val (stepStatus, stepTwo) = exchange("http://$address", null)
        assertEquals(200, stepStatus)
        assertTrue("step 2", "Step 2 of 4" in stepTwo)
        assertFalse("a torrent indexer section", "Torrent indexer" in stepTwo)
        assertFalse("a debrid service section", "Debrid service" in stepTwo)
        assertTrue("step 2's line that nothing is searched, the saved torrent indexer standing in for nothing", SetupPage.NO_INDEXER in stepTwo)
        refused(stepTwo, "add:torrent-indexer")
    }

    /**
     * L192, L166: a search asks Usenet's indexer alone though a torrent indexer and a service account are saved:
     * Indexer A's pill, none of Home Prowlarr's; no release says where it plays from; and no cache check reaches the
     * debrid fake, where the services' clients point for the test.
     */
    @Test
    fun aSearchAsksUsenetsIndexerAloneThoughATorrentIndexerIsSaved() {
        assumeTrue("the fake indexer isn't reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        runBlocking { settings.saveIndexer(DebridSearchFixture.INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key") }
        compose.setContent { AppTheme { AppNav() } }
        val searchTab = hasText("Search") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitFor(searchTab, 15_000)
        compose.onNode(searchTab).performClick()
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("harbor lights")
        click(hasContentDescription("Search indexers for “harbor lights”") and hasClickAction())
        val indexerA = hasText("Indexer A · ", substring = true)
        waitFor("Indexer A's pill, answered", 30_000) { exists(indexerA) && !exists(indexerA and hasText("searching", substring = true)) }
        assertFalse("Home Prowlarr's pill", exists(hasText("Home Prowlarr", substring = true)))

        click(hasContentDescription("for S01E02", substring = true) and hasClickAction())
        val good = hasText(GOOD) and hasClickAction()
        waitFor("S01E02's list") { scrolledTo(good) }
        assertEquals("GOOD once, from Usenet", 1, compose.onAllNodes(good).fetchSemanticsNodes().size)
        listOf("From Usenet", "From TorBox", "Torrent, not playable").forEach { assertFalse("a release tagged \"$it\"", exists(hasContentDescription(it))) }
        val calls = JSONObject(DebridFakes.control("counts")).getJSONObject("calls")
        assertEquals("cache checks at the debrid fake", 0 to 0, calls.getInt("torbox checkcached") to calls.getInt("premiumize cache/check"))
    }

    /** A request to the page as a phone's browser makes it: [form] posted urlencoded, else a GET. Its status and body. */
    private fun exchange(url: String, form: List<Pair<String, String>>?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 20_000
            if (form != null) {
                val body = form.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            status to ((if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** S01E02 on the shared fake indexer, as Usenet lists it (FakeCatalog), and as a cached torrent on the Torznab fake. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
    }
}

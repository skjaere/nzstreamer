package io.skjaere.usenet.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's first-run screen over the real settings: with no provider and no indexer saved it asks
 * for the provider first; once one is saved, for an indexer, with "Not now" leading to Home. While
 * debrid shows, Usenet's button leads and "Add a debrid service" sits under it, and the torrent way
 * (a service and a torrent indexer, through the fakes) leads Home too (DbPhoneFirstRun, W81). The
 * tests take the saved providers and indexers away (and the choice of "Not now") and put them
 * back as they were, secrets included; the saved services and torrent indexers are hidden meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()

    @Before
    fun clearSources() = runBlocking {
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        settings.setIndexerSetupSkipped(false)
    }

    @After
    fun restore() = runBlocking {
        fakeBack?.invoke()
        endpointsBack?.invoke()
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.services.first().filter { s -> saved.services.none { it.id == s.id } }.forEach { settings.signOut(it.id) }
        settings.torrentIndexers.first().filter { t -> saved.torrentIndexers.none { it.id == t.id } }.forEach { settings.deleteTorrentIndexer(it.id) }
        settings.restore(saved)
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        assertEquals(saved, settings.settings.first())
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        delay(1_000)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Where the service clients pointed before a test pointed them at the debrid fake, and that test's hold on the fake. */
    private var endpointsBack: (() -> Unit)? = null
    private var fakeBack: (() -> Unit)? = null

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    /** Scrolled to first where it is in a scrolling column (a dialog's buttons and the first run's foot aren't). */
    private fun click(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** DbPhoneFirstRun 2: the two ways, Usenet's first (W81), with nothing saved. */
    @Test
    fun withDebridShownUsenetsButtonLeadsAndTheDebridServiceIsUnderIt() {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        compose.setContent { AppTheme { AppNav() } }
        waitFor(hasText("Set up your sources"))
        val usenet = compose.onNode(hasText("Add a Usenet provider") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val debrid = compose.onNode(hasText("Add a debrid service") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Usenet's button ($usenet) above the debrid service's ($debrid)", usenet.bottom <= debrid.top)
        listOf("Usenet", "Or torrents, through a debrid service", "Debrid service", "Your debrid account, with its API key", "Torrent indexer", "Prowlarr or Jackett, to search for torrents")
            .forEach { assertTrue("\"$it\" shown", exists(hasText(it, ignoreCase = true))) }
        assertTrue("Not now before a provider is saved", !exists(hasText("Not now")))
    }

    /** DbPhoneFirstRun 2-3: Add a debrid service, TorBox's key, then the torrent indexer the one button adds: Home. */
    @Test
    fun theTorrentWayLeadsHome() {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        DebridFakes.assumeTorznab()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        compose.setContent { AppTheme { AppNav() } }
        waitFor(hasText("Add a debrid service") and hasClickAction())
        click(hasText("Add a debrid service") and hasClickAction())
        waitFor(hasText("TorBox") and hasClickAction())
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText("Sign in to TorBox"))
        compose.onNode(hasSetTextAction()).performTextReplacement(DebridFakes.TORBOX_KEY)
        click(hasText("Save") and hasClickAction())

        // Back on the first run (DbPhoneFirstRun 3): the service done, the torrent indexer next, the one button adding it.
        waitFor(hasText("Add a torrent indexer") and hasClickAction())
        assertTrue("the step names the service", exists(hasText("Debrid service")) && exists(hasText("TorBox")))
        assertTrue("one way under way: one button", !exists(hasText("Add a Usenet provider") and hasClickAction()))
        click(hasText("Add a torrent indexer") and hasClickAction())
        waitFor(hasText("Add torrent indexer"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("Home Prowlarr")
        fields[1].performTextReplacement(DebridFakes.PROWLARR_URL)
        fields[2].performTextReplacement(DebridFakes.TORZNAB_KEY)
        click(hasText("Save") and hasClickAction())

        waitFor(hasText("Search your indexers"))
        assertTrue(!exists(hasText("Set up your sources")))
        runBlocking {
            assertEquals(listOf("TorBox"), settings.services.first().map { it.kind })
            assertEquals(listOf("Home Prowlarr"), settings.torrentIndexers.first().map { it.name })
            assertEquals(emptyList<Any>(), settings.providers.first())
        }
    }

    @Test
    fun withoutSourcesHomeAsksForAProviderThenAnIndexerAndNotNowLeadsHome() {
        compose.setContent { AppTheme { AppNav() } }
        compose.waitUntil(5_000) { exists(hasText("Set up your sources")) }
        compose.onNodeWithText("Add a Usenet provider").assertIsDisplayed()
        compose.onNodeWithText("Your Usenet account, to stream from").assertIsDisplayed()
        compose.onNodeWithText("Open a .nzb file instead").assertIsDisplayed()
        // A phone has its keyboard at hand: "Set up with your phone" is a TV's only.
        assertTrue("a phone's first run offers the phone setup", !exists(hasText(PHONE_SETUP_TITLE)))
        assertTrue("Not now before a provider is saved", !exists(hasText("Not now")))
        assertTrue(!exists(hasText("Search your indexers")))

        // As the provider screen saves it: the rows follow at once.
        runBlocking { settings.saveProvider(ProviderRecord("", "Mock (first-run test)", "10.0.2.2", 1119, useTls = false, connections = 4), "") }
        compose.waitUntil(5_000) { exists(hasText("Add an indexer")) }
        compose.onNodeWithText("Mock (first-run test)").assertIsDisplayed()
        compose.onNodeWithText("Set up your sources").assertIsDisplayed()

        compose.onNode(hasText("Not now") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { exists(hasText("Search your indexers")) }
        assertTrue(!exists(hasText("Set up your sources")))
        assertEquals(true, runBlocking { settings.indexerSetupSkipped.first() })
    }

    @Test
    fun aStepOpensItsSettingsScreen() {
        compose.setContent { AppTheme { AppNav() } }
        compose.waitUntil(5_000) { exists(hasText("Set up your sources")) }
        compose.onNode(hasText("TMDB API key") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { exists(hasText("Metadata")) }
        assertTrue(!exists(hasText("Set up your sources")))
    }
}

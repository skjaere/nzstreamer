package io.skjaere.usenet.app.debrid

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Settings › Torrent indexers on a phone (L194; DbPhoneSources, DbPhoneTorrentIndexer) over the real settings and the
 * Torznab fake's Prowlarr-shaped feed (10.0.2.2:8096, mock-seed torznab): Home Prowlarr added from the hub's row, its
 * Test saying Torrents and Movies, TV; saved, its row in the list and the hub's line saying none is searched without a
 * service; then "Use with your debrid service" turned off, which its row says. The saved torrent indexers are hidden for
 * the test (tombstoned, their keys untouched) and put back as they were; the test's own is deleted with its key.
 */
@RunWith(AndroidJUnit4::class)
class TorrentIndexersDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController

    @Before
    fun setUp() = runBlocking<Unit> {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("the Torznab fake isn't reachable at 10.0.2.2:8096 (mock-seed torznab --port 8096)", reachable(8096))
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        saved = settings.settings.first()
        // Hidden, not deleted: deleting would remove their keys from the secret store. The service accounts too, so the
        // hub's line says none is searched without a service.
        settings.restore(saved.copy(torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }, services = saved.services.map { it.copy(deleted = true) }))
        home.show()
    }

    @After
    fun restore() = runBlocking<Unit> {
        home.restore()
        // Skipped before anything was changed (the fake isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.torrentIndexers.first().filter { t -> saved.torrentIndexers.none { it.id == t.id } }.forEach { settings.deleteTorrentIndexer(it.id) }
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    private fun click(matcher: SemanticsMatcher) = compose.onNode(matcher).performScrollTo().performClick()

    /** The form's fields, in order: Name, API URL, API key. */
    private fun field(label: String) = compose.onAllNodes(hasSetTextAction())[listOf("Name", "API URL", "API key").indexOf(label)]

    @Test
    fun aTorrentIndexerIsAddedTestedSavedAndKeptFromTheService() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(SettingsRoute) }
        // The hub's row, after Indexers, with none yet.
        waitFor(hasText("Torrent indexers") and hasText("None yet") and hasClickAction())
        click(hasText("Torrent indexers") and hasClickAction())
        waitFor(hasText(TORRENT_INDEXERS_LINE))
        assertTrue("the list's footnote", exists(hasText(torrentIndexersFootnote(television = false))))

        click(hasText("+ Add") and hasClickAction())
        waitFor(hasText("Add torrent indexer"))
        field("Name").performTextReplacement("Home Prowlarr")
        field("API URL").performTextReplacement("http://10.0.2.2:8096/prowlarr/1")
        field("API key").performTextReplacement("fake-torznab-key")
        waitFor(hasText("Plain http on your local network."))
        click(hasText("Test connection") and hasClickAction())
        // DbPhoneTorrentIndexer 2: what its results are, and what it searches in, from the fake's caps.
        waitFor(hasText("Connected · Prowlarr"), timeoutMs = 30_000)
        listOf("Results", "Torrents", "TV search by", "Movie search by", "Searches in", "Movies, TV").forEach { words ->
            assertTrue("the Test's \"$words\"", exists(hasText(words)))
        }
        // Both switches on for a new one.
        assertTrue("Use for searches on", exists(hasText("Use for searches") and isOn()))
        assertTrue("Use with your debrid service on", exists(hasText(USE_WITH_SERVICE) and hasText(USE_WITH_SERVICE_HELP) and isOn()))
        click(hasText("Save") and hasClickAction())

        waitFor(hasText("Home Prowlarr") and hasText("10.0.2.2") and hasClickAction())
        val id = runBlocking { settings.torrentIndexers.first().single().id }
        runBlocking {
            val stored = settings.torrentIndexer(id)!!
            assertEquals("fake-torznab-key", stored.apiKey)
            assertTrue("used with the service by default", stored.record.useWithService)
        }

        // DbPhoneSources 2: no service yet, so it isn't searched, which the hub's row says.
        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText("Torrent indexers") and hasText("1 torrent indexer · not searched without a service"))

        // DbPhoneTorrentIndexer 3: a private tracker's switch off; still searched, which the row's line says apart.
        click(hasText("Torrent indexers") and hasClickAction())
        click(hasText("Home Prowlarr") and hasClickAction())
        waitFor(hasText("Remove torrent indexer"))
        waitFor(hasText("Home Prowlarr") and hasSetTextAction())
        click(hasText(USE_WITH_SERVICE) and isToggleable())
        click(hasText("Save") and hasClickAction())
        waitFor(hasText("Home Prowlarr") and hasText("10.0.2.2 · not used with your debrid service"))
        runBlocking {
            val stored = settings.torrentIndexer(id)!!
            assertEquals(false, stored.record.useWithService)
            assertTrue("still searched", stored.record.enabled)
            assertEquals("the empty key field kept the key", "fake-torznab-key", stored.apiKey)
        }
    }

    /**
     * D2PhoneIndexerTest 1 (L238): the Test reads the newest 10 of the Torznab fake's Jackett feed, two of which (the
     * v2-only torrent and the one with a .torrent link alone) have no v1 hash, and says so under its lines, still saying
     * Torrents.
     */
    @Test
    fun theTestSaysHowManyOfTheLast10ResultsHaveNoInfoHash() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(SettingsRoute) }
        waitFor(hasText("Torrent indexers") and hasClickAction())
        click(hasText("Torrent indexers") and hasClickAction())
        waitFor(hasText(TORRENT_INDEXERS_LINE))
        click(hasText("+ Add") and hasClickAction())
        waitFor(hasText("Add torrent indexer"))
        field("Name").performTextReplacement("Jackett")
        field("API URL").performTextReplacement("http://10.0.2.2:8096/jackett/api/v2.0/indexers/fake/results/torznab")
        field("API key").performTextReplacement("fake-torznab-key")
        click(hasText("Test connection") and hasClickAction())
        waitFor(hasText("Connected · Jackett"), timeoutMs = 30_000)
        val line = hasText("2 of the last 10 results have no info hash, so the app leaves them out.")
        waitFor(line)
        compose.onNode(line).performScrollTo()
        assertTrue("Results still says Torrents", exists(hasText("Torrents")))
    }
}

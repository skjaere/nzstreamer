package io.skjaere.usenet.app.debrid

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.EngineState
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A debrid-only user plays (L165, L194) over the fakes: with nothing saved, no Usenet provider among it, First run's
 * torrent way (TorBox's key, then Home Prowlarr) leads Home; Search asks for no indexer; and Copper Crown plays from a
 * release TorBox has cached, the engine never asked to check a provider. The saved providers, indexers, services and
 * torrent indexers are taken away for the test and put back after, keys included. Written with item 29; its play needs
 * the player's HTTPS source and the debrid fake's CDN to probe the file (items 30-32).
 */
@RunWith(AndroidJUnit4::class)
class DebridOnlyDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()
    private var fakeBack: (() -> Unit)? = null
    private var endpointsBack: (() -> Unit)? = null

    /** Every sheet the coordinator showed, in order, and every state the engine was in: collected as each is set. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private val engine = CopyOnWriteArrayList<EngineState>()
    private var watching: Job? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        DebridFakes.assumeTorznab()
        // The debug build's own settings saved first: else they could land after this test took the settings away, and be
        // put back over by them.
        container.devSettingsSaved.await()
        // The film plays from a torrent whatever an earlier class in the run played it from (DFX-1).
        DebridSearchFixture.forgetPlays(container.library)
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        settings.setIndexerSetupSkipped(false)
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch {
            launch { container.playback.state.collect { seen += it } }
            container.engine.state.collect { engine += it }
        }
    }

    @After
    fun restore() = runBlocking<Unit> {
        watching?.cancel()
        onMainThread { container.playback.cancel() }
        try {
            DebridFakes.control("reset")
        } finally {
            fakeBack?.invoke()
            endpointsBack?.invoke()
        }
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

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    private fun click(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    @Test
    fun aUserWithOnlyADebridServiceSetsUpSearchesAndPlaysWithoutTheEngine() = runBlocking<Unit> {
        compose.setContent { AppTheme { AppNav() } }
        waitFor(hasText("Add a debrid service") and hasClickAction())
        click(hasText("Add a debrid service") and hasClickAction())
        waitFor(hasText("TorBox") and hasClickAction())
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText("Sign in to TorBox"))
        compose.onNode(hasSetTextAction()).performTextReplacement(DebridFakes.TORBOX_KEY)
        click(hasText("Save") and hasClickAction())
        waitFor(hasText("Add a torrent indexer") and hasClickAction())
        click(hasText("Add a torrent indexer") and hasClickAction())
        waitFor(hasText("Add torrent indexer"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("Home Prowlarr")
        fields[1].performTextReplacement(DebridFakes.PROWLARR_URL)
        fields[2].performTextReplacement(DebridFakes.TORZNAB_KEY)
        click(hasText("Save") and hasClickAction())
        waitFor(hasText("Search your indexers"))
        assertEquals(emptyList<Any>(), settings.providers.first())

        // Search, with torrent indexers and a service on and no Usenet indexer: no card asking for one.
        click(hasText("Search") and hasClickAction())
        waitFor(hasSetTextAction())
        compose.waitForIdle()
        assertTrue("Search asked for an indexer", !exists(hasText("Add an indexer to search")))

        // The film plays from the release TorBox has cached, the engine never asked about a provider: from here on, it
        // stays as having none.
        withTimeout(10_000) { container.engine.state.first { it == EngineState.NotConfigured } }
        engine.clear()
        onMainThread { container.playback.playMovie(MovieTarget(DebridSearchFixture.COPPER_CROWN, "Copper Crown", 2019, "tt9000012", 104)) }
        val launch = launched()
        // One of the film's cached torrents this device plays (an emulator plays no E-AC-3, so not the DD+ one).
        assertTrue(launch.release.title, launch.release.title.startsWith(COPPER_CROWN_RELEASE))
        assertTrue(launch.uri, launch.uri.startsWith("debrid://"))
        assertTrue("the sheet waited on the providers' check", seen.none { it is PlayState.Searching && "Checking your providers" in it.line })
        assertTrue("the engine started: $engine", engine.all { it == EngineState.NotConfigured })
    }

    private companion object {
        /** How FakeCatalog's Copper Crown's releases' names start (tools/mock-seed). */
        const val COPPER_CROWN_RELEASE = "Copper.Crown.2019."
    }
}

package io.skjaere.usenet.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.ItemFound
import io.skjaere.usenet.app.play.ItemTarget
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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
 * A search a plugin opened with a note, on a phone (L629; SpPhoneSearchWarning): ⚠ and the note in the warning colour
 * under "Releases for “Copper Crown” on your indexers", above the indexers' lines; still there over a release list opened
 * from its results, under its sorts; gone once other words are searched; and the search not kept in the recent searches.
 * A plugin's item's no-release sheet (SpPhoneNothing) offers Search “<words>”, which opens that noted search. The fake
 * indexer (10.0.2.2:8089) answers; TMDB is the fake, with no titles for the words. The Search tab and its pages are the
 * app's graph, with the play sheet's host over them as AppNav has it.
 *
 * The saved providers are hidden for the test and put back as they were; the test's own provider and indexer are deleted
 * with their secrets, and the recent searches put back as they were.
 */
@RunWith(AndroidJUnit4::class)
class SearchNoteDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private lateinit var saved: AppSettings
    private lateinit var recent: Set<String>

    /**
     * The recent searches by when each was searched, as the test began: a noted search kept would add its words, or, where
     * they were searched before (an earlier class, a run stopped before its clean-up), search them again.
     */
    private lateinit var recentAt: Map<String, Long>

    private fun recentNow(): Map<String, Long> = runBlocking { library.recentSearches.first().associate { it.key to it.searchedAt } }

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        saved = settings.settings.first()
        recent = library.recentSearches.first().map { it.key }.toSet()
        recentAt = recentNow()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        val mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, "Fake (search note test)", "http://10.0.2.2:8089", "fake-indexer-key")
        engineSetup("the engine on the saved mock") { container.engine.configuredProviders.first { it == listOf(mock) } }
        tmdb = FakeTmdb(suggestions = { emptyList() })
    }

    @After
    fun restore() = runBlocking<Unit> {
        tmdb?.close()
        container.playback.cancel()
        // Skipped before anything was changed (a fake isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (saved.indexers.none { it.id == FAKE_INDEXER } && settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
        settings.restore(saved)
        library.recentSearches.first().filter { it.key !in recent }.forEach { library.removeSearch(it.key) }
        engineOnSavedProviders()
    }

    @Test
    fun theNoteShowsAboveTheResultsAndOverTheirReleaseListUntilOtherWordsAreSearched() {
        showSearch()
        compose.runOnIdle { nav.switchTab(SearchRoute(query = WORDS, note = NOTE), restore = false) }
        waitFor(header, 60_000)
        waitFor(group, 60_000)
        waitFor(note)
        val bounds = { m: SemanticsMatcher -> compose.onAllNodes(m)[0].fetchSemanticsNode().boundsInRoot }
        assertTrue("the note under what was searched", bounds(note).top >= bounds(header).bottom)
        assertTrue("the note above the results", bounds(note).bottom <= bounds(group).top)
        assertEquals("a noted search kept in the recent searches", recentAt, recentNow())

        // Its releases: the note under the sorts, above the first release.
        compose.onAllNodes(group)[0].performClick()
        waitFor(hasText(GOOD, substring = true) and hasClickAction())
        waitFor(note)
        assertTrue("the note under the sorts", bounds(note).top >= bounds(hasText("Size") and hasClickAction()).bottom)
        assertTrue("the note above the releases", bounds(note).bottom <= bounds(hasText(GOOD, substring = true) and hasClickAction()).top)
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(header)
        assertTrue("the note while its words show", exists(note))

        // Other words searched: the note goes.
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(OTHER)
        compose.onNode(hasContentDescription("Search indexers for “$OTHER”") and hasClickAction()).performClick()
        waitFor(hasText("Releases for “$OTHER” on your indexers"), 60_000)
        waitFor(group, 60_000)
        assertTrue("the note over other words' results", !exists(note))
    }

    /** SpPhoneNothing: a plugin's item that found nothing offers Search “<words>”, which opens Search with the note. */
    @Test
    fun theNoReleaseSheetsSearchOpensTheNotedSearch() {
        showSearch()
        val item = ItemTarget(KESTREL, "cc-2019:final", "Copper Crown · Final", "KCL", searchWords = WORDS, searchNote = NOTE)
        val nothing = ItemFound(emptyList(), lines = listOf("Indexer A" to "2 results, none for this game"), note = "Releases often appear a few hours after a game ends. Try Play again later.")
        compose.runOnIdle { container.playback.playItem(item, { nothing }) }
        val search = hasText("Search “$WORDS”") and hasClickAction() and hasAnyAncestor(isDialog())
        waitFor(search, 30_000)
        assertTrue("the plugin's line", exists(hasText("2 results, none for this game") and hasAnyAncestor(isDialog())))
        assertTrue("never Show the n found", !exists(hasText("found", substring = true) and hasClickAction() and hasAnyAncestor(isDialog())))
        compose.onNode(search).performClick()
        waitFor(header, 60_000)
        waitFor(note)
        waitFor(group, 60_000)
        assertEquals("a noted search kept in the recent searches", recentAt, recentNow())
    }

    /**
     * The app's graph and the play sheet's host over it, as AppNav has them; its start an empty screen in Home's place, so
     * Search opens as a tab opens over the start, and nothing of Home is read.
     */
    private fun showSearch() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                val app = LocalContext.current.container
                val jumps = rememberCollectionJumps(nav)
                Box {
                    NavHost(nav, startDestination = NoteStart) {
                        composable<NoteStart> {}
                        appGraph(nav, app, jumps, FormFactor.Phone)
                    }
                    PlaySheetHost(app.playback, onSearch = { route -> nav.switchTab(route, restore = false) }, onProviders = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private val header = hasText("Releases for “$WORDS” on your indexers")

    /** The note's line: ⚠ and the note, read as one. */
    private val note = hasText(NOTE) and hasText("⚠")

    /** A release group's row in Search's results, the part that lists its releases ("2 releases for 2019"). */
    private val group = SemanticsMatcher("lists a group's releases") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { RELEASES_FOR.containsMatchIn(it) }
    } and hasClickAction()

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private companion object {
        const val FAKE_INDEXER = "search-note-device-test"
        const val KESTREL = "kestrel"
        const val WORDS = "Copper Crown"
        const val OTHER = "Harbor Lights"
        const val NOTE = "Indexer results can show scores."
        const val GOOD = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"
        val RELEASES_FOR = Regex("""^\d+ releases? for """)
    }
}

/** An empty start for [SearchNoteDeviceTest]'s graph, in Home's place. */
@Serializable
private data object NoteStart

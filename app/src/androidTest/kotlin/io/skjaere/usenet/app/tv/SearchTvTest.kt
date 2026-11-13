package io.skjaere.usenet.app.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.JumpFixture
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.search.SearchPaneKey
import io.skjaere.usenet.app.search.VoiceSearch
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voice search on a TV (forced on the phone emulator), its recognizer a fake one: the mic beside the
 * field's gate where a recognizer resolves, none where none does; what is said fills the field, TMDB's titles for it
 * show on the right with the focus on the first, else the indexers are searched at once and the focus goes to the first
 * result; nothing said or cancelled leaves the query as it was. The remote's search key opens Search and asks for voice
 * once, and a restore never asks again. Invented titles ([JumpFixture]) and, for releases, the fake indexer.
 */
@RunWith(AndroidJUnit4::class)
class SearchTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val fixture = JumpFixture()
    private val recognizer = FakeRecognizer()
    private val searchKeys = Channel<Unit>(Channel.CONFLATED)
    private val realResolves = VoiceSearch.resolves
    private lateinit var nav: NavHostController
    private lateinit var searchedBefore: Set<String>

    @Before
    fun setUp() {
        fixture.open()
        searchedBefore = runBlocking { compose.activity.container.library.recentSearches.first().map { it.key }.toSet() }
        instrumentation.setInTouchMode(false)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        VoiceSearch.resolves = realResolves
        val library = compose.activity.container.library
        runBlocking { library.recentSearches.first().filter { it.key !in searchedBefore }.forEach { library.removeSearch(it.key) } }
        fixture.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val mic = hasContentDescription("Search by voice") and hasClickAction()

    private fun query(): String = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Composable
    private fun App() {
        nav = rememberNavController()
        // The recognizer the mic starts: the fake's answers, not a real one's.
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides recognizer) {
            AppNav(nav = nav, searchKeys = searchKeys.receiveAsFlow())
        }
    }

    /** The app on a TV, on Home, with a recognizer that resolves ([resolves]) or none; through [restoration] when given. */
    private fun showTheApp(resolves: Boolean = true, restoration: StateRestorationTester? = null) {
        VoiceSearch.resolves = { resolves }
        if (restoration == null) {
            compose.setTvContent { App() }
        } else {
            restoration.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(TV_SIZE)) { AppTheme(FormFactor.Tv) { App() } }
            }
        }
        remote.waitForHomeFocus()
    }

    /** Search, opened fresh: its gate focused. */
    private fun openSearch() {
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        // Whether a recognizer resolves is asked off the main thread: the mic shows once it is known.
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /** OK on the mic, reached with the remote: Left from the field's gate. */
    private fun sayWithTheRemote() {
        assertEquals("Left from the gate", "Search by voice", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun theMicSitsLeftOfTheFieldWhereARecognizerResolves() {
        showTheApp(resolves = true)
        openSearch()
        assertTrue("no mic", exists(mic))
        val m = compose.onNode(mic).fetchSemanticsNode().boundsInRoot
        val gate = compose.onNode(fieldGate("Search")).fetchSemanticsNode().boundsInRoot
        val left = compose.onNode(SemanticsMatcher.expectValue(SearchPaneKey, "input")).fetchSemanticsNode().boundsInRoot
        assertTrue("the mic isn't beside the field, in the left pane: $m, the gate $gate", m.right <= gate.left && m.center.y in gate.top..gate.bottom && m.left >= left.left)
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    @Test
    fun noMicWithoutARecognizer() {
        showTheApp(resolves = false)
        openSearch()
        assertFalse("a mic, though no recognizer resolves", exists(mic))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the gate: the rail; focus on ${remote.focused()}", remote.isFocused(railItem("Search")))
    }

    @Test
    fun wordsSaidShowTheirTitlesWithTheFocusOnTheFirst() {
        showTheApp()
        openSearch()
        recognizer.answer(Activity.RESULT_OK, "glasswater")
        sayWithTheRemote()
        compose.waitUntil(5_000) { remote.focused().startsWith("Glasswater Bay") }
        assertEquals(1, recognizer.launched.get())
        assertEquals("glasswater", query())
        assertTrue("the title isn't on the right", compose.onNode(hasText("Glasswater Bay") and hasClickAction()).fetchSemanticsNode().boundsInRoot.left >=
            compose.onNode(SemanticsMatcher.expectValue(SearchPaneKey, "results")).fetchSemanticsNode().boundsInRoot.left)
        assertFalse("the field took the focus, and its keyboard", exists(hasSetTextAction() and isFocused()))
    }

    @Test
    fun wordsSaidWithNoTitlesSearchTheIndexersWithTheFocusOnTheFirstResult() = withFakeIndexer {
        showTheApp()
        openSearch()
        recognizer.answer(Activity.RESULT_OK, QUERY)
        sayWithTheRemote()
        compose.waitUntil(20_000) { exists(hasText("Releases for “$QUERY” on your indexers")) && exists(playsAGroup) }
        try {
            compose.waitUntil(5_000) { exists(playsAGroup and isFocused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the first result never had the focus; it is on \"${remote.focused()}\"", e)
        }
        assertEquals(QUERY, query())
    }

    @Test
    fun nothingSaidOrCancelledLeavesTheQuery() {
        showTheApp()
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("lantern")
        compose.onNode(fieldGate("Search")).requestFocus()
        compose.waitUntil(5_000) { exists(fieldGate("Search") and isFocused()) }
        recognizer.answer(Activity.RESULT_CANCELED, null)
        sayWithTheRemote()
        compose.waitForIdle()
        assertEquals("cancelled", "lantern", query())
        recognizer.answer(Activity.RESULT_OK, "   ")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals("nothing said", "lantern", query())
        assertEquals(2, recognizer.launched.get())
    }

    @Test
    fun theSearchKeyOpensSearchAndAsksForVoiceOnceNotAgainOnARestore() {
        val restoration = StateRestorationTester(compose)
        showTheApp(restoration = restoration)
        recognizer.answer(Activity.RESULT_CANCELED, null)
        searchKeys.trySend(Unit)
        compose.waitUntil(5_000) { recognizer.launched.get() == 1 }
        compose.waitUntil(5_000) { exists(fieldGate("Search")) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("voice asked for more than once", 1, recognizer.launched.get())

        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists(fieldGate("Search")) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("a restore asked for voice again", 1, recognizer.launched.get())
    }

    /**
     * The search key pressed while Search shows: it opens a new Search, which asks for voice, and what is said lands in
     * it, not in the Search going (still on screen while it fades out).
     */
    @Test
    fun theSearchKeyWhileSearchShowsPutsTheWordsInTheNewSearch() {
        showTheApp()
        openSearch()
        recognizer.answer(Activity.RESULT_OK, "glasswater")
        searchKeys.trySend(Unit)
        compose.waitUntil(5_000) { recognizer.launched.get() == 1 }
        // The Search that showed gone: the new one alone on the screen.
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        try {
            compose.waitUntil(5_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the words said went nowhere: the new Search's field has \"${query()}\"", e)
        }
        assertEquals("glasswater", query())
        compose.waitUntil(5_000) { remote.focused().startsWith("Glasswater Bay") }
        assertEquals("voice asked for more than once", 1, recognizer.launched.get())
    }

    /**
     * A release list open on the right, then words said from the left pane's mic: the list goes, the titles for the words
     * show on the right, and the focus goes to the first of them, not into the list.
     */
    @Test
    fun wordsSaidWithAReleaseListOpenShowTheirTitles() = withFakeIndexer {
        showTheApp()
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput(QUERY)
        compose.onNode(hasContentDescription("Search indexers for “$QUERY”") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { exists(playsAGroup and isFocused()) }
        assertTrue("Right from the group: its releases; focus on ${remote.focused()}", "release" in remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Size") and hasClickAction()) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertTrue("Left from the release: the field's gate; focus on ${remote.focused()}", remote.press(KeyEvent.KEYCODE_DPAD_LEFT) == "Search")
        recognizer.answer(Activity.RESULT_OK, "glasswater")
        sayWithTheRemote()
        try {
            compose.waitUntil(5_000) { remote.focused().startsWith("Glasswater Bay") }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus isn't on the first title for the words said; it is on \"${remote.focused()}\"", e)
        }
        assertFalse("the release list still shows", exists(hasText("Size") and hasClickAction()))
        assertEquals("glasswater", query())
    }

    /** [test] with the fake indexer (10.0.2.2:8089) switched on, the fixture's being off; it goes again after, with its key. */
    private fun withFakeIndexer(test: () -> Unit) {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        val settings = compose.activity.container.settings
        try {
            runBlocking { settings.saveIndexer(FAKE_INDEXER, "Fake (Search on TV test)", "http://10.0.2.2:8089", "fake-indexer-key") }
            test()
        } finally {
            runBlocking { if (settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER) }
        }
    }

    /** A recognizer that answers each start at once with what it was told to ([answer]), counting the starts. */
    private class FakeRecognizer : ActivityResultRegistryOwner {
        val launched = AtomicInteger()
        private var code = Activity.RESULT_CANCELED
        private var words: String? = null

        fun answer(resultCode: Int, said: String?) {
            code = resultCode
            words = said
        }

        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, (input as Intent).action)
                launched.incrementAndGet()
                val data = words?.let { Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(it)) }
                dispatchResult(requestCode, code, data)
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 700L
        const val FAKE_INDEXER = "search-tv-test"
        const val QUERY = "harbor lights"
    }
}

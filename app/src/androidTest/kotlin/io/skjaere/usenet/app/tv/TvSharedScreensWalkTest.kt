package io.skjaere.usenet.app.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.CacheRoute
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.ImportPicker
import io.skjaere.usenet.app.ImportRoute
import io.skjaere.usenet.app.IndexerRoute
import io.skjaere.usenet.app.IndexersRoute
import io.skjaere.usenet.app.LogsRoute
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.PlaybackRoute
import io.skjaere.usenet.app.PrivacyRoute
import io.skjaere.usenet.app.ProviderRoute
import io.skjaere.usenet.app.ProvidersRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.ThisDeviceRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.discover.DiscoverListRoute
import io.skjaere.usenet.app.discover.DiscoverRoute
import io.skjaere.usenet.app.discover.FakeDiscover
import io.skjaere.usenet.app.home.FirstRun
import io.skjaere.usenet.app.home.SetupState
import io.skjaere.usenet.app.link.PHONES_REMOVE
import io.skjaere.usenet.app.link.PHONES_TITLE
import io.skjaere.usenet.app.link.PairPageEvent
import io.skjaere.usenet.app.link.PairPageListener
import io.skjaere.usenet.app.link.PairPhoneDeps
import io.skjaere.usenet.app.link.PairPhoneScreen
import io.skjaere.usenet.app.link.PhoneQuestion
import io.skjaere.usenet.app.link.PhonesDeps
import io.skjaere.usenet.app.link.PhonesScreen
import io.skjaere.usenet.app.link.TvListener
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.MaxBitrateSetting
import io.skjaere.usenet.app.setup.ADDRESS
import io.skjaere.usenet.app.setup.PHONE_SETUP_DONE
import io.skjaere.usenet.app.setup.PHONE_SETUP_LEAVE_OUT
import io.skjaere.usenet.app.setup.PHONE_SETUP_REVIEW
import io.skjaere.usenet.app.setup.PHONE_SETUP_SAVE
import io.skjaere.usenet.app.setup.PhoneSetupPart
import io.skjaere.usenet.app.setup.PhoneSetupScreen
import io.skjaere.usenet.app.setup.SetupFakes
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FieldGateLabel
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.PairedPhone
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SetupField
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupRejection
import io.skjaere.usenet.handoff.SetupSection
import io.skjaere.usenet.handoff.SetupSlot
import io.skjaere.usenet.handoff.SetupStep
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
 * The phone's screens in the TV shell (forced on the phone emulator, 960 x 540 dp), walked with a
 * remote at 1x and 1.3x the font size: every element that takes the focus is reached, and every text
 * and focusable element stays inside the TV's safe area (48 dp from the sides, 27 dp from the top
 * and bottom) all the way down; at 2x no text is cut off. One test per screen, each with all its
 * problems in one report. A text field is reached at its gate, which OK opens: the arrows never
 * put the focus in the field itself.
 */
@RunWith(AndroidJUnit4::class)
class TvSharedScreensWalkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private var scale by mutableFloatStateOf(1f)
    private lateinit var nav: NavHostController
    private lateinit var focusManager: FocusManager

    @Before
    fun remote() {
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private fun showTheApp() {
        compose.setTvContent({ scale }) {
            focusManager = LocalFocusManager.current
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitForIdle()
    }

    /** The app's [route], fresh: a tab's own screen started again, a page opened over Home. */
    private fun open(route: Any) {
        compose.runOnIdle {
            nav.switchTab(HomeRoute)
            if (tabsFor(FormFactor.Tv).any { it.route == route }) nav.switchTab(route, restore = false) else nav.navigate(route)
        }
        compose.waitForIdle()
        // Its data read (the screens load from the settings and the library).
        Thread.sleep(LOAD_MS)
        compose.waitForIdle()
    }

    private fun focusables(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            val rail = node.isRailItem()
            val gated = node.config.contains(SemanticsActions.SetText) && generateSequence(node.parent) { it.parent }.any { it.config.contains(FieldGateLabel) }
            if (node.config.contains(SemanticsProperties.Focused) && !rail && !gated && node.boundsInRoot.width > 0) out += node
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    /** A rail item: a tab named by its description (the Collection's Shows | Movies are tabs too, named by their words). */
    private fun SemanticsNode.isRailItem(): Boolean =
        config.getOrNull(SemanticsProperties.Role) == Role.Tab && config.getOrNull(SemanticsProperties.ContentDescription) != null

    /** What the walk calls a focusable element: its label, or for one without (a text field), its node. */
    private fun walkLabel(node: SemanticsNode): String = node.label().ifEmpty { "(unlabelled #${node.id})" }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }

    /** Texts on screen cut off at the bottom: more lines than their box holds; not those [intended] to end in an ellipsis. */
    private fun clippedTexts(intended: (String) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            if (node.boundsInRoot.height > 0 && node.textLayout()?.didOverflowHeight == true && !intended(node.label())) out += "\"${node.label()}\" cut off"
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    /**
     * The remote's moves from the screen's first focus: from each element reached, each of the four
     * arrows (back on that element before each), so every element reached is reached with the
     * D-pad; at each stop what shows is checked. [required]: the focusable elements that must be
     * reached (and are walked on from). [mustReach]: elements, by how their labels start, the walk must have reached: a
     * screen without them passes the rest.
     */
    private fun walk(
        screen: String, problems: MutableList<String>, required: (String) -> Boolean, reach: Boolean, area: Boolean, clipping: Boolean,
        intended: (String) -> Boolean, scrollBack: Boolean, mustReach: List<String> = emptyList(),
    ) {
        val seen = linkedSetOf<String>()
        val reached = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        val outside = linkedSetOf<String>()
        val clipped = linkedSetOf<String>()
        val moves = mutableListOf<String>()
        fun focusedNode() = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().lastOrNull()
        fun onRail() = focusedNode()?.isRailItem() == true
        fun focused() = focusedNode()?.let(::walkLabel) ?: "(nothing)"
        fun look() {
            focusables().map(::walkLabel).filter(required).forEach { seen += it }
            if (area) outside += compose.outsideTheSafeArea()
            if (clipping) clipped += clippedTexts(intended)
        }
        fun visit(label: String) {
            if (label != "(nothing)" && required(label) && reached.add(label)) queue += label
        }
        /** Back on [label]'s element, as the remote left it there. */
        fun refocus(label: String): Boolean {
            val matcher = SemanticsMatcher("is $label") { it.config.contains(SemanticsProperties.Focused) && walkLabel(it) == label }
            val nodes = compose.onAllNodes(matcher)
            // Gone with its lazy list's item, the list walked on down past it: scrolled back to it first.
            if (scrollBack && nodes.fetchSemanticsNodes().isEmpty()) {
                runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
                compose.waitForIdle()
            }
            if (nodes.fetchSemanticsNodes().isEmpty()) return false
            // Off the rail first, as the user goes back: a request from the rail is turned, on its way into the content,
            // to the element the content last had focused (the shell's focus restorer).
            if (onRail()) remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            nodes[0].requestFocus()
            compose.waitForIdle()
            return focused() == label
        }
        // From the content: a tab's screen may start with the focus on the rail (Right leaves it).
        if (onRail()) remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (focusedNode() == null) remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        look()
        // Walked from wherever the screen starts, also an element not required: the Collection's first poster, without a
        // TMDB key (what earlier tests left in the library decides, #143).
        if (!onRail()) focused().takeIf { it != "(nothing)" && reached.add(it) }?.let { queue += it }
        while (queue.isNotEmpty() && reached.size < MAX_REACHED) {
            val from = queue.removeFirst()
            for (key in ARROWS) {
                if (!refocus(from)) break
                look()
                // A remote's arrows leave a text field (an emulator's keyboard moves its cursor): as the field does then.
                if (focusedNode()?.config?.contains(SemanticsActions.SetText) == true) {
                    compose.runOnIdle { focusManager.moveFocus(DIRECTIONS.getValue(key)) }
                    compose.waitForIdle()
                } else {
                    remote.press(key)
                }
                if (!onRail()) {
                    val to = focused()
                    if (to != from) moves += "$from ${KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_DPAD_")} → $to"
                    visit(to)
                    look()
                }
            }
        }
        val label = "$screen at ${scale}x"
        val missed = if (reach) seen - reached else emptySet()
        missed.forEach { problems += "$label: never focused: \"$it\"" }
        if (reach) mustReach.filter { must -> reached.none { it.startsWith(must) } }.forEach { problems += "$label: never reached: \"$it\"" }
        if (missed.isNotEmpty()) problems += "$label: the moves: " + moves.joinToString("; ")
        outside.forEach { problems += "$label: outside the safe area: $it" }
        clipped.forEach { problems += "$label: $it" }
    }

    /**
     * [intended]: texts made to end in an ellipsis when there is no room (a poster's name and line), not counted as cut at
     * 2x. [scrollBack]: the screen's one list is long enough to dispose of its first lines as the walk goes down it, which
     * the walk scrolls back to. [mustReach]: elements the walk must reach at 1x and 1.3x, by how their labels start.
     */
    private fun check(
        screen: String, required: (String) -> Boolean = { true }, intended: (String) -> Boolean = { false }, scrollBack: Boolean = false,
        mustReach: List<String> = emptyList(), show: () -> Unit,
    ) {
        val problems = mutableListOf<String>()
        listOf(1f, 1.3f, 2f).forEach { s ->
            scale = s
            try {
                compose.waitForIdle()
                show()
                val large = s >= 2f
                walk(screen, problems, required, reach = !large, area = !large, clipping = large, intended = intended, scrollBack = scrollBack, mustReach = mustReach)
            } catch (e: Exception) {
                problems += "$screen at ${s}x: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.take(3)?.joinToString(" ")}"
            }
        }
        assertEquals("problems on $screen:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    /**
     * Discover over a few invented titles a list, with a key whatever the device has stored (none in a bench run, nor
     * where no dev key is set up): any will do, the lists being the fake's. Without one the walk would see only the
     * no-key card, and pass having walked none of the rows.
     */
    private fun withDiscover(block: (FakeDiscover) -> Unit) {
        val settings = compose.activity.container.settings
        val before = runBlocking { settings.tmdbKey() }
        if (before == null) runBlocking { settings.setTmdbKey(WALK_TMDB_KEY) }
        try {
            FakeDiscover(size = 3).install().use { block(it) }
        } finally {
            runBlocking { settings.setTmdbKey(before) }
        }
    }

    /** Discover's tab: every row's cards and See all, the switch and the chips. */
    @Test
    fun discover() {
        withDiscover { fake ->
            showTheApp()
            // Its rows' See all each named by their list, the walk goes down the column before it is done with the top line.
            check("Discover", intended = ::posterWords, scrollBack = true) { open(DiscoverRoute) }
            assertTrue("the lists were asked: the rows walked, not the no-key card", fake.asked.isNotEmpty())
        }
    }

    /** A list's See all page: its pills, its posters and their paging. */
    @Test
    fun discoverSeeAll() {
        withDiscover { fake ->
            showTheApp()
            check("Discover's See all", intended = ::posterWords) { open(DiscoverListRoute(MediaType.Movie.name, DiscoverList.Popular.name)) }
            assertTrue("the list was asked: its posters walked, not the no-key card", fake.asked.isNotEmpty())
        }
    }

    /** A Discover poster's name and line, made to end in an ellipsis when there is no room, as the Collection's are. */
    private fun posterWords(words: String) = DISCOVER_NAME.matches(words) || words == "2021"

    /** On a TV (tv36) its top card has Phones too (L36: by what the device is, not its layout), which the walk must reach. */
    @Test
    fun settings() {
        showTheApp()
        val phones = if (instrumentation.targetContext.container.television) listOf(PHONES_TITLE) else emptyList()
        check("Settings", mustReach = phones) { open(SettingsRoute) }
    }

    @Test
    fun providers() {
        showTheApp()
        check("Usenet providers") { open(ProvidersRoute) }
    }

    @Test
    fun providerForm() {
        showTheApp()
        check("Add provider") { open(ProviderRoute()) }
    }

    @Test
    fun indexers() {
        showTheApp()
        check("Indexers") { open(IndexersRoute) }
    }

    @Test
    fun indexerForm() {
        showTheApp()
        check("Add indexer") { open(IndexerRoute()) }
    }

    @Test
    fun metadata() {
        showTheApp()
        check("Metadata") { open(MetadataRoute) }
    }

    /**
     * Behaviour's last row is a TV's own: "Match the TV to the video's frame rate". Under Maximum bitrate, the footnote in
     * the device's own words (L308), by the app's form factor, not the shell's: on tv36 the TV's, where larger releases of
     * a quality go first. Read, not reached: a footnote never takes the focus.
     */
    @Test
    fun playback() {
        showTheApp()
        check("Quality and languages", mustReach = listOf("Match the TV to the video’s frame rate")) { open(PlaybackRoute) }
        val footnote = MaxBitrateSetting.footnote(instrumentation.targetContext.container.television)
        assertTrue("Maximum bitrate's footnote: $footnote", compose.onAllNodes(hasText(footnote)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun cache() {
        showTheApp()
        check("Cache") { open(CacheRoute) }
    }

    /** Its cards, each a stop: read within LOAD_MS of opening, so the walk sees the facts. */
    @Test
    fun thisDevice() {
        showTheApp()
        check("This device") { open(ThisDeviceRoute) }
    }

    /** The log's lines are too many to walk; its controls must all be reached. */
    @Test
    fun logs() {
        showTheApp()
        check("Logs", required = { !LOG_LINE.containsMatchIn(it) }) { open(LogsRoute) }
    }

    /** The address alone, opening nothing, over the policy, each paragraph a stop (W60, L138). */
    @Test
    fun privacy() {
        showTheApp()
        check("Privacy") { open(PrivacyRoute) }
    }

    /** As on the TV builds without a document picker (#121): Import says how else a .nzb gets here. */
    @Test
    fun import() {
        val real = ImportPicker.contract
        ImportPicker.contract = {
            object : ActivityResultContract<Array<String>, Uri?>() {
                override fun createIntent(context: Context, input: Array<String>) = Intent("io.skjaere.usenet.test.NO_DOCUMENT_PICKER")
                override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
            }
        }
        try {
            showTheApp()
            check("Open NZB file") { open(ImportRoute()) }
        } finally {
            ImportPicker.contract = real
        }
    }

    /**
     * The Collection tab as the app's library has it (TV-4's grid): its own elements are each reached and walked from; its
     * posters, by their titles, aren't each walked from (a grid of them would take the walk's whole budget), and their
     * names and lines end in an ellipsis by design.
     */
    @Test
    fun collection() {
        val store = instrumentation.targetContext.container.collection
        val titles = runBlocking { store.shows.filterNotNull().first() + store.movies.filterNotNull().first() }
        val posters = titles.map { it.a11y }.toSet()
        val words = titles.flatMap { listOf(it.text.name, it.cardLine) }.toSet()
        showTheApp()
        check("Collection", required = { it !in posters }, intended = { it in words }) { open(CollectionRoute) }
    }

    @Test
    fun searchStart() {
        showTheApp()
        check("Search") { open(SearchRoute()) }
    }

    /**
     * Search's results in its two panes: the fake indexer's (10.0.2.2:8089) releases for the words, TMDB answering with
     * no titles. Its groups' rows and their releases are walked like the rest.
     */
    @Test
    fun searchResults() {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        val container = instrumentation.targetContext.container
        val searchedBefore = runBlocking { container.library.recentSearches.first().map { it.key }.toSet() }
        val saved = runBlocking { container.settings.settings.first() }
        FakeTmdb(suggestions = { emptyList() }).use {
            try {
                runBlocking {
                    container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
                    container.settings.saveIndexer(WALK_INDEXER, "Fake (walk test)", "http://10.0.2.2:8089", "fake-indexer-key")
                }
                showTheApp()
                check("Search results") {
                    open(SearchRoute(query = WALK_QUERY))
                    compose.waitUntil(20_000) { compose.onAllNodes(playsAGroup).fetchSemanticsNodes().isNotEmpty() }
                    compose.waitForIdle()
                }
            } finally {
                runBlocking {
                    if (container.settings.indexer(WALK_INDEXER) != null) container.settings.deleteIndexer(WALK_INDEXER)
                    container.settings.restore(saved)
                    container.library.recentSearches.first().filter { it.key !in searchedBefore }.forEach { container.library.removeSearch(it.key) }
                }
            }
        }
    }

    /** The first run, on Home's tab, before anything is set up. */
    @Test
    fun firstRun() {
        val state = SetupState(providers = emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent({ scale }) {
            focusManager = LocalFocusManager.current
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { FirstRun(state, onStep = {}, onSkip = {}, onImport = {}, onPhone = {}) }
                    composable<SettingsRoute> {}
                }
            }
        }
        check("First run") {
            compose.runOnIdle { nav.switchTab(SettingsRoute); nav.switchTab(HomeRoute, restore = false) }
            compose.waitForIdle()
        }
    }

    @Serializable
    private data object PhoneSetupPage

    /** "Set up with your phone" over fakes, on a page of its own over Home, in the TV shell. */
    private fun showPhoneSetup(fakes: SetupFakes) {
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent({ scale }) {
            focusManager = LocalFocusManager.current
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {}
                    composable<PhoneSetupPage> { PhoneSetupScreen(onDone = {}, onBack = {}, deps = fakes.deps) }
                }
            }
        }
    }

    /** The page opened afresh (a new session), and once [fakes] sent what [then] waits for. */
    private fun openPhoneSetup(fakes: SetupFakes, then: () -> Unit = {}) {
        compose.runOnIdle { nav.popBackStack(HomeRoute, inclusive = false); nav.navigate(PhoneSetupPage) }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)).fetchSemanticsNodes().isNotEmpty() }
        then()
        compose.waitForIdle()
    }

    private fun receive(fakes: SetupFakes) {
        fakes.receive()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(PHONE_SETUP_REVIEW)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { remote.isFocused(hasText(PHONE_SETUP_SAVE) and hasClickAction()) }
    }

    /**
     * Every line Waiting can show: the wrong tries, the page's opener, where the phone is, a test from the phone and a
     * form that couldn't be read.
     */
    private fun everyWaitingLine(fakes: SetupFakes) {
        fakes.session.events(SetupEvent.WrongTry(1))
        fakes.session.events(SetupEvent.Opened(SetupFakes.PHONE))
        fakes.session.events(SetupEvent.StepChanged(SetupStep.Check))
        fakes.session.events(SetupEvent.TestStarted(SetupSlot(SetupSection.Provider, 1)))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot(SetupSection.Provider, 1), SetupOutcome.NotTested))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot(SetupSection.Indexer, 1), SetupOutcome.NotTested))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot.Tmdb, SetupOutcome.NotTested))
        fakes.session.events(SetupEvent.Rejected(SetupRejection(SetupField.ProviderHost, SetupRejection.Reason.Characters)))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Tested from your phone", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waiting for the phone, with every line it can show: nothing to press, everything to read inside the safe area. */
    @Test
    fun phoneSetupWaiting() {
        val fakes = SetupFakes()
        showPhoneSetup(fakes)
        check("Phone setup, waiting") { openPhoneSetup(fakes) { everyWaitingLine(fakes) } }
    }

    /**
     * Waiting with every line it can show, at each font size: what doesn't fit scrolls with the remote's Down, so each
     * of its texts is shown in full at some point on the way down. The walk can't see that: it reads the part of a
     * text a scrolling container leaves showing, and Waiting has nothing to press.
     */
    @Test
    fun phoneSetupWaitingReadsToItsEndWithTheRemote() {
        val fakes = SetupFakes()
        showPhoneSetup(fakes)
        val problems = mutableListOf<String>()
        listOf(1f, 1.3f, 2f).forEach { s ->
            scale = s
            compose.waitForIdle()
            openPhoneSetup(fakes) { everyWaitingLine(fakes) }
            val seen = mutableMapOf<String, Boolean>()
            // The countdown's words change every second: one text whatever it reads.
            fun look() = compose.textsShownInFull().forEach { (text, full) ->
                val key = text.replace(Regex("""\d+:\d\d"""), "m:ss")
                seen[key] = seen[key] == true || full
            }
            look()
            repeat(12) {
                remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
                look()
            }
            seen.filterValues { !it }.keys.forEach { problems += "Waiting at ${s}x: never shown in full: \"$it\"" }
        }
        assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun phoneSetupReview() {
        val fakes = SetupFakes()
        showPhoneSetup(fakes)
        check("Phone setup, review") { openPhoneSetup(fakes) { receive(fakes) } }
    }

    /** The results of Save's tests, a failing indexer's Try again, Save anyway and Leave out among them, nothing saved. */
    @Test
    fun phoneSetupResults() {
        val fakes = SetupFakes()
        fakes.indexer = { IndexerTest.Failed("No answer within 15 s") }
        showPhoneSetup(fakes)
        check("Phone setup, results") {
            openPhoneSetup(fakes) {
                receive(fakes)
                // Past the review's OK guard.
                Thread.sleep(1_100)
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(PHONE_SETUP_LEAVE_OUT)).fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    /** Everything saved: what each item came to, and Done. */
    @Test
    fun phoneSetupSaved() {
        val fakes = SetupFakes()
        showPhoneSetup(fakes)
        check("Phone setup, saved") {
            openPhoneSetup(fakes) {
                receive(fakes)
                Thread.sleep(1_100)
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { remote.isFocused(hasText(PHONE_SETUP_DONE) and hasClickAction()) }
            }
        }
    }

    @Serializable
    private data object LinkPage

    /** One of Play on TV's pages ([page]), over fakes, on a page of its own over Home, in the TV shell. */
    private fun showLinkPage(page: @androidx.compose.runtime.Composable () -> Unit) {
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent({ scale }) {
            focusManager = LocalFocusManager.current
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {}
                    composable<LinkPage> { page() }
                }
            }
        }
    }

    /** The page opened afresh, and [then]. */
    private fun openLinkPage(then: () -> Unit = {}) {
        compose.runOnIdle { nav.popBackStack(HomeRoute, inclusive = false); nav.navigate(LinkPage) }
        compose.waitForIdle()
        then()
        compose.waitForIdle()
    }

    /** Two invented phones, one linked now and one last seen an hour before. */
    private fun phonesDeps(listed: Boolean = true): PhonesDeps {
        val now = System.currentTimeMillis()
        val galaxy = KeyId.of(ByteArray(91) { 1 })
        val tablet = KeyId.of(ByteArray(91) { 2 })
        val rows = if (!listed) emptyList() else listOf(
            PairedPhone(galaxy.hex, "Galaxy phone", now - 86_400_000, now, "192.168.1.57"),
            PairedPhone(tablet.hex, "Pixel tablet", now - 3 * 86_400_000, now - 3_600_000, "192.168.1.58"),
        )
        return PhonesDeps(MutableStateFlow(rows), MutableStateFlow(setOf(galaxy)), remove = {}, now = { now }, zone = { java.time.ZoneId.systemDefault() })
    }

    @Test
    fun phones() {
        showLinkPage { PhonesScreen(onBack = {}, onPair = {}, deps = phonesDeps()) }
        check("Phones", mustReach = listOf(PHONES_REMOVE)) { openLinkPage() }
    }

    @Test
    fun phonesNoneYet() {
        showLinkPage { PhonesScreen(onBack = {}, onPair = {}, deps = phonesDeps(listed = false)) }
        check("Phones, none yet") { openLinkPage() }
    }

    /** Pair a phone over a fake listener at an invented address, whose words a test sends as the listener would. */
    private class FakePairing(phones: Int, listener: TvListener) {
        @Volatile var page: PairPageListener? = null
        val deps = PairPhoneDeps(
            name = "Living room TV",
            listener = MutableStateFlow(listener),
            open = { page = it }, close = { if (page === it) page = null }, questionMillis = { 60_000 }, now = SystemClock::elapsedRealtime,
            phones = kotlinx.coroutines.flow.flowOf(phones),
        )
        val question = PhoneQuestion("Galaxy phone", "192.168.1.57", "482913") {}

        fun send(vararg events: PairPageEvent) = events.forEach { event -> page!!.heard(event) }
    }

    /**
     * Pair a phone opened afresh at each size, on a TV keeping [phones], then told [events] as the listener would tell them.
     * [mustReach]: its buttons the walk must reach, by how their labels start.
     */
    private fun pairPage(
        name: String,
        vararg events: (FakePairing) -> PairPageEvent,
        phones: Int = 0,
        listener: TvListener = TvListener.Listening(java.net.InetAddress.getByName("192.168.1.20") as java.net.Inet4Address, 28_462),
        mustReach: List<String> = emptyList(),
    ) {
        val fake = FakePairing(phones, listener)
        showLinkPage { PairPhoneScreen(onDone = {}, onBack = {}, deps = fake.deps) }
        check(name, mustReach = mustReach) {
            openLinkPage {
                compose.waitUntil(5_000) { fake.page != null }
                events.forEach { fake.send(it(fake)) }
            }
        }
    }

    @Test
    fun pairPhoneWaiting() {
        pairPage("Pair a phone, waiting")
    }

    /** The question, another phone waiting behind it: Pair, and Cancel beside it, both reached. */
    @Test
    fun pairPhoneAsking() {
        pairPage("Pair a phone, asking", { PairPageEvent.Asking(it.question) }, { PairPageEvent.Waiting(another = true) }, mustReach = listOf("Pair", "Cancel"))
    }

    @Test
    fun pairPhonePaired() {
        pairPage("Pair a phone, paired", { PairPageEvent.Asking(it.question) }, { PairPageEvent.Ended(it.question, PairOutcome.Paired) })
    }

    /** Both said yes, and the TV keeps 16 phones already. */
    @Test
    fun pairPhoneNotKept() {
        pairPage("Pair a phone, not kept", { PairPageEvent.Asking(it.question) }, { PairPageEvent.Ended(it.question, PairOutcome.NotSaved) }, phones = 16)
    }

    /** This TV's link key couldn't be loaded. */
    @Test
    fun pairPhoneNoKey() {
        pairPage("Pair a phone, no key", listener = TvListener.NoKey)
    }

    @Test
    fun pairPhoneClosed() {
        pairPage("Pair a phone, closed", { PairPageEvent.Closed })
    }

    /** A release list, as Search opens it for an episode, on a page of its own: its cards' bitrate chips are checked with the rest. */
    @Test
    fun releaseList() {
        showReleaseList(releases(EPISODES))
        check("Release list") {
            openReleaseList()
            assertTrue("no release card shows its bitrate", compose.onAllNodes(hasText("≈", substring = true) and hasText(" Mbps", substring = true)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    /**
     * A list of season packs as Choose release shows them, whose rows have a menu offering Choose file. Its ⋮, inside
     * the row, is for touch and TalkBack: the arrows don't reach it, and a long OK on the row opens the menu instead.
     */
    @Test
    fun seasonPackReleaseList() {
        showReleaseList(releases(PACKS), onChooseFile = {})
        check("Season packs", required = { !it.startsWith("More for ") }) { openReleaseList() }
    }

    /** A long OK on a season pack's row opens its menu, whose Choose file the remote reaches and picks; nothing plays meanwhile. */
    @Test
    fun aLongOkOnASeasonPacksRowOffersChooseFile() {
        val chosen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val played = java.util.concurrent.CopyOnWriteArrayList<String>()
        val group = releases(PACKS)
        showReleaseList(group, onChooseFile = { chosen += it.item.title }, onPlay = { played += it.item.title })
        openReleaseList()
        val best = group.playable.first().item.title
        // From the content: the screen may start with the focus on the rail (Right leaves it).
        val onRail = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.Role) == Role.Tab
        if (onRail) remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val reached = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, best)
        assertTrue("never reached $best; focus on \"$reached\"", best in reached)
        // OK held past the long-press time, which the row's timer counts on the test's clock.
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 200L)
        compose.waitForIdle()
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose file")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("a long OK played the release", emptyList<String>(), played.toList())
        assertEquals("a long OK chose a file", emptyList<String>(), chosen.toList())
        val onItem = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Choose file", max = 3)
        assertTrue("the menu's Choose file never had the focus; it is on \"$onItem\"", "Choose file" in onItem)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { chosen.isNotEmpty() }
        assertEquals(listOf(best), chosen.toList())
        assertEquals("played", emptyList<String>(), played.toList())
    }

    /**
     * [group] as a release list, on a page of its own over Home, in the TV shell; [onChooseFile] as Choose release passes it,
     * and [onPlay] hears a release played.
     */
    private fun showReleaseList(
        group: ResultGroup,
        onChooseFile: ((io.skjaere.usenet.search.RankedItem) -> Unit)? = null,
        onPlay: (io.skjaere.usenet.search.RankedItem) -> Unit = {},
    ) {
        val context = instrumentation.targetContext.container
        compose.setTvContent({ scale }) {
            focusManager = LocalFocusManager.current
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), context.playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {}
                    composable<ShowRoute> { ReleaseList(group, "Harbor Lights", onBack = {}, onChooseFile = onChooseFile, onPlay = onPlay) }
                }
            }
        }
    }

    private fun openReleaseList() {
        compose.runOnIdle { nav.popBackStack(HomeRoute, inclusive = false); nav.navigate(ShowRoute(1)) }
        compose.waitForIdle()
    }

    /** [releases] (names and sizes) ranked, as the one group they make. */
    private fun releases(releases: List<Pair<String, Long>>): ResultGroup {
        fun item(title: String, size: Long) = NewznabItem(
            indexerId = "walk", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranking = runBlocking { instrumentation.targetContext.container.rankingContext() }
        return ResultGrouper().group(releases.map { (title, size) -> item(title, size) }, ranking).sections.flatMap { it.groups }.first()
    }

    private companion object {
        val EPISODES = listOf(
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.WEB-DL.DDP5.1.H.264-NZST" to 3_200_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.720p.WEB-DL.DDP5.1.H.264-NZST" to 1_400_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.2160p.WEB-DL.DDP5.1.HDR.HEVC-NZST" to 9_800_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.HDTV.x264-NZST" to 2_100_000_000L,
        )
        val PACKS = listOf(
            "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-NZST" to 24_000_000_000L,
            "Harbor.Lights.S02.720p.WEB-DL.DDP5.1.H.264-NZST" to 9_500_000_000L,
            "Harbor.Lights.S02.1080p.HDTV.x264-NZST" to 16_000_000_000L,
        )
        const val LOAD_MS = 400L
        /** FakeDiscover's invented names, each with its page's number. */
        val DISCOVER_NAME = Regex("""\D+ \d+""")
        const val WALK_INDEXER = "tv-walk-test"
        /** A made-up TMDB key: Discover's lists are the fake's. */
        const val WALK_TMDB_KEY = "tv-walk-test"
        /** The fake indexer's invented show. */
        const val WALK_QUERY = "harbor lights"
        /** Enough for any of these screens; a walk that goes on beyond is lost. */
        const val MAX_REACHED = 60
        val ARROWS = listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)
        val DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_DOWN to FocusDirection.Down, KeyEvent.KEYCODE_DPAD_UP to FocusDirection.Up,
            KeyEvent.KEYCODE_DPAD_RIGHT to FocusDirection.Right, KeyEvent.KEYCODE_DPAD_LEFT to FocusDirection.Left,
        )
        /** A log line's description: its level, then its time. */
        val LOG_LINE = Regex("""^(Debug|Info|Warning|Error|Trace|Verbose), \d""")
    }
}

package io.skjaere.usenet.app.plugins

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.FAKE
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.KESTREL
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.SECOND_PAGE
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * A plugin's tab in a phone's bar (L582, L590, L618), the app's own shell and graph (AppNav) over the app's registry, an
 * in-process fake in its first slot ([FakePluginRule]): the bar's order, Home, Discover, the fake, Collection, Search; its
 * own page, and its history kept as tabs switch. Every tab's history kept across an install, a removal and a swap, and
 * each plugin's tab reopening on its own page, never on another's (the real skeleton, whose update swaps, and a second
 * fake). One line for every label: five tabs at 1.3 times the text size, then six with a label of 16 characters at 1x and
 * 1.3x. And the fake's scope throwing: its tab stays, the host's placeholder in its page, whose Open Plugins leaves the slot
 * for its card in Settings › Plugins, where Turn on again and Remove work.
 *
 * The bar shows on the tabs' own screens alone: a tab left on a page opened from it is switched to as the bar's tap does
 * it ([switchTab]). TMDB answers only for the Collection's show (a strict fake): what Home asks of other titles is
 * refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class PluginTabsDeviceTest {
    private val plugins = PluginStateRule()
    private val fake = FakePlugin()
    private val fakes = FakePluginRule(plugins, fake)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule, then the fakes, around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(fakes).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private var fontScale by mutableFloatStateOf(1f)

    private val harbor = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45, listOf(SeasonSummary(1, null, 3, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = null, nextEpisode = null, ids = ExternalIds(),
    )
    private val season = Season(SHOW, 1, null, null, (1..3).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })

    @Before
    fun setUp() {
        home.show()
        // As MainActivity's first creation does: a plugin installed from its file loads from now on.
        container.plugins.firstActivity()
        tmdb = FakeTmdb(shows = mapOf(SHOW to harbor), seasons = { id, n -> season.takeIf { id == SHOW && n == 1 } }, strict = true)
    }

    @After
    fun restore() {
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        home.restore()
    }

    /** The app's screens, as MainActivity shows them on a phone, at the text size [fontScale] says. */
    private fun showTheApp() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme {
                    nav = rememberNavController()
                    AppNav(nav = nav)
                }
            }
        }
        waitFor(barTab("Home"))
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun barTab(label: String) = hasText(label) and tabRole

    /** The bar's labels, left to right: the tabs at the screen's foot, whatever their labels' heights. */
    private fun barLabels(): List<String> {
        val tabs = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        val bottom = tabs.maxOfOrNull { it.boundsInRoot.bottom } ?: return emptyList()
        val bar = tabs.first().layoutInfo.density.density * BAR_DP
        return tabs.filter { bottom - it.boundsInRoot.center.y < bar }.sortedBy { it.boundsInRoot.left }
            .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}, the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
    }

    private fun button(words: String) = hasText(words) and hasClickAction()

    /**
     * [id] as the app's registry lists it once [what] holds, the screens' effects running meanwhile: the slots' watch, which
     * asks for the swap point, is one, and a wait that held the test's thread would hold it too.
     */
    private fun listed(id: String, what: String, holds: (InstalledPlugin) -> Boolean): InstalledPlugin {
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value?.firstOrNull { it.id == id }?.let(holds) == true }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$id isn't $what: the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
        return plugins.app.plugins.value!!.first { it.id == id }
    }

    private fun press(words: String) = press(button(words))

    private fun press(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performScrollTo().performClick()
    }

    /** The bar's tab [label], tapped. */
    private fun openTab(label: String) {
        waitFor(barTab(label))
        compose.onNode(barTab(label)).performClick()
        compose.waitForIdle()
    }

    /** [route]'s tab, as the bar's tap goes to it ([switchTab]); then [shown] once it shows. */
    private fun backTo(route: Any, shown: SemanticsMatcher) {
        compose.runOnIdle { nav.switchTab(route) }
        waitFor(shown)
    }

    /** What Back does on a phone. */
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun ownPage(label: String) = hasText(FakePlugin.ownPage(label))

    private fun secondPage(label: String) = hasText(FakePlugin.secondArg(label))

    /** Home's own screen: its search field. */
    private val homeShown = hasText("Search your indexers") and hasClickAction()

    /** The Collection's show page, opened as a poster opens it: its episodes. */
    private val showPage = hasContentDescription("More for episode 1")

    /** The skeleton's own page, by its last row's line, and its second page, by what it was opened with. */
    private val skeletonsPage = hasText("Throws as it is drawn")
    private val skeletonsSecondPage = hasText("Opened from the skeleton's first page")

    /** The real skeleton's [build], from the test APK's assets, installed in the app's registry as the list's would be. */
    private fun installSkeleton(build: SkeletonBuild): InstallResult = runBlocking {
        val download = build.download(context)
        plugins.app.install(download, build.request(Sha256.of(download)))
    }

    /** L618, W160: the bar's order; the fake's page; its second page still there once the bar has gone elsewhere and back. */
    @Test
    fun theFakesTabComesAfterDiscoverWithItsPageAndItsHistory() {
        showTheApp()
        waitFor(barTab("Fake"))
        assertEquals("the bar", listOf("Home", "Discover", "Fake", "Collection", "Search"), barLabels())

        openTab("Fake")
        waitFor(ownPage("Fake"))
        assertTrue("its tab chosen", compose.onNode(barTab("Fake")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        press(SECOND_PAGE)
        waitFor(secondPage("Fake"))

        backTo(HomeRoute, homeShown)
        openTab("Collection")
        openTab("Fake")
        waitFor(secondPage("Fake"))
    }

    /**
     * L582, L581: the Collection on a show's page and the skeleton (A) on its second page. B (a fake) installed: each tab
     * comes back where it was, B on its own page, then its second; B removed: the Collection and A still where they were,
     * and B installed again opens on its own page, its slot's history gone with it; A swapped to v2 once its slot is off
     * the screen: the Collection and B where they were, and A on its own page, as no page another version drew comes back.
     */
    @Test
    @FakeCount(0)
    fun eachTabComesBackWhereItWasAcrossAnInstallARemovalAndASwap() {
        val kestrel = FakePlugin(KESTREL, "Kestrel")
        assertTrue("A, the skeleton v1, installed", installSkeleton(SkeletonBuild.V1) is InstallResult.Installed)
        val v1 = runBlocking { plugins.app.await(SKELETON, "running") { it.status is PluginStatus.Running } }
        assertEquals("A in the first slot", 1, v1.slot)
        showTheApp()
        openTab("Collection")
        compose.runOnIdle { nav.navigate(ShowRoute(SHOW)) }
        waitFor(showPage)
        backTo(PluginTab1, barTab("Skeleton"))
        waitFor(skeletonsPage)
        press(SECOND_PAGE)
        waitFor(skeletonsSecondPage)

        // B installed.
        backTo(HomeRoute, homeShown)
        fakes.install(kestrel)
        waitFor(barTab("Kestrel"))
        assertEquals("the bar with two plugins", listOf("Home", "Discover", "Skeleton", "Kestrel", "Collection", "Search"), barLabels())
        openTab("Kestrel")
        waitFor(ownPage("Kestrel"))
        press(SECOND_PAGE)
        waitFor(secondPage("Kestrel"))
        backTo(CollectionRoute, showPage)
        backTo(PluginTab1, skeletonsSecondPage)
        backTo(PluginTab2, secondPage("Kestrel"))

        // B removed, then installed again.
        backTo(HomeRoute, homeShown)
        fakes.remove(kestrel)
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(barTab("Kestrel")) }
        backTo(CollectionRoute, showPage)
        backTo(PluginTab1, skeletonsSecondPage)
        backTo(HomeRoute, homeShown)
        fakes.install(kestrel)
        openTab("Kestrel")
        waitFor(ownPage("Kestrel"))
        assertTrue("B's second page came back with it", !exists(secondPage("Kestrel")))
        press(SECOND_PAGE)
        waitFor(secondPage("Kestrel"))

        // A swapped: v2 stored, and taking over at once, its slot off the screen.
        backTo(HomeRoute, homeShown)
        assertTrue("v2 stored to wait for the swap", installSkeleton(SkeletonBuild.V2) == InstallResult.UpdateWaiting)
        val v2 = listed(SKELETON, "swapped to v2") { it.versionCode == 2L && it.status is PluginStatus.Running }
        assertNotEquals("a new instance", (v1.status as PluginStatus.Running).instance.key, (v2.status as PluginStatus.Running).instance.key)
        backTo(CollectionRoute, showPage)
        backTo(PluginTab2, secondPage("Kestrel"))
        backTo(PluginTab1, skeletonsPage)
        assertTrue("v1's second page came back over v2", !exists(skeletonsSecondPage))
    }

    /**
     * L618, PlPhoneTabs: five tabs at 1.3x, every label whole on one line; then six, the second plugin's label 16
     * characters, at 1x and 1.3x: every label on one line, shrunk to fit, and only the longest ending in "…" where even
     * shrunk it doesn't.
     */
    @Test
    fun everyLabelStaysOnOneLineWithSixTabsAndALongLabel() {
        fontScale = 1.3f
        showTheApp()
        waitFor(barTab("Fake"))
        val problems = labelProblems(mayEnd = emptySet()).map { "five tabs at 1.3x: $it" }.toMutableList()

        fakes.install(FakePlugin(KESTREL, LONG))
        waitFor(barTab(LONG))
        assertEquals("the bar with two plugins", listOf("Home", "Discover", "Fake", LONG, "Collection", "Search"), barLabels())
        listOf(1f, 1.3f).forEach { scale ->
            fontScale = scale
            compose.waitForIdle()
            problems += labelProblems(mayEnd = setOf(LONG)).map { "six tabs at ${scale}x: $it" }
        }
        assertEquals("the bar's labels", emptyList<String>(), problems)
    }

    /** Each label in the bar laid out on more than one line, cut at its foot, or ending in "…" when it isn't one of [mayEnd]. */
    private fun labelProblems(mayEnd: Set<String>): List<String> = barLabels().mapNotNull { label ->
        val node = compose.onAllNodes(hasText(label) and hasAnyAncestor(tabRole), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            ?: return@mapNotNull "\"$label\" not one text"
        val layout = node.textLayout() ?: return@mapNotNull "\"$label\" has no layout"
        when {
            layout.lineCount > 1 -> "\"$label\" on ${layout.lineCount} lines"
            layout.size.height < layout.multiParagraph.height - 1 -> "\"$label\" cut at its foot"
            layout.isLineEllipsized(0) && label !in mayEnd -> "\"$label\" ends in an ellipsis"
            else -> null
        }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }

    /**
     * L619, L590: the fake's work in its scope throws; NZStreamer goes on, the version is off after an error, its tab stays
     * with the host's placeholder, and its close() comes once its page has gone; Open Plugins goes Home, then Settings ›
     * Plugins with its card in view and no entry of its slot left on the back stack (L581); there Turn on again loads it
     * again, and Remove takes it, its tab with it.
     */
    @Test
    fun theFakesScopeThrowingShowsThePlaceholderWhoseOpenPluginsLeavesTheSlotForItsCard() {
        showTheApp()
        openTab("Fake")
        waitFor(ownPage("Fake"))
        val first = fakes.instance(fake)
        fake.failIn(first.host)
        listed(FAKE, "off after an error") { it.status == PluginStatus.OffAfterError }

        waitFor(hasText(PluginPlaceholder.OPEN_PLUGINS) and hasClickAction())
        compose.onNode(hasText("Fake") and isHeading()).assertIsDisplayed()
        compose.onNode(hasText("Fake is turned off")).assertIsDisplayed()
        compose.onNode(hasText("The fake plugin was turned off after an error. What you watched is kept.")).assertIsDisplayed()
        assertEquals("its tab kept", listOf("Home", "Discover", "Fake", "Collection", "Search"), barLabels())
        compose.waitUntil(5_000) { fake.closed.get() == 1 }
        assertEquals("none of its pages drawn as it closed (NzPlugin.close)", listOf(0), fake.composedAtClose)

        press(PluginPlaceholder.OPEN_PLUGINS)
        waitFor(hasText(PluginsPage.INTRO))
        waitFor(button("Turn on again"))
        compose.onNode(hasText(PluginsPage.ERRORED)).assertIsDisplayed()
        compose.onNode(button("Turn on again")).assertIsDisplayed()
        val slotEntries = compose.runOnIdle { PluginSlots.routeTypes.filter { type -> runCatching { nav.getBackStackEntry(type) }.isSuccess } }
        assertEquals("no entry of a slot on the back stack", emptyList<Any>(), slotEntries)

        press("Turn on again")
        val again = listed(FAKE, "running again") { it.status is PluginStatus.Running }
        assertNotEquals("a new instance", first.key, (again.status as PluginStatus.Running).instance.key)
        waitFor(button("Remove"))

        press("Remove")
        waitFor(hasText("Remove fake?"))
        waitFor(button("Remove"))
        compose.onAllNodes(button("Remove")).onLast().performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value.orEmpty().isEmpty() }
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(button("Turn on again")) && !exists(button("Remove")) }

        // Back on Settings, then Home: the bar without it.
        back()
        back()
        waitFor(barTab("Home"))
        assertEquals("its tab gone", listOf("Home", "Discover", "Collection", "Search"), barLabels())
    }

    /**
     * L581, L590: the fake's settings page, opened from its card in Settings › Plugins over Home, and its scope throwing
     * there: the page's placeholder's Open Plugins leaves no entry of its slot on the back stack either, and Back goes to
     * Settings, then Home.
     */
    @Test
    fun openPluginsFromItsSettingsPageLeavesNoSlotEntryEither() {
        showTheApp()
        press(hasContentDescription("Settings") and hasClickAction())
        press("Plugins")
        waitFor(hasText(PluginsPage.INTRO))
        press("Settings")
        waitFor(hasText(FakePlugin.settingsTitle("Fake")))
        fake.failIn(fakes.instance(fake).host)
        listed(FAKE, "off after an error") { it.status == PluginStatus.OffAfterError }

        press(PluginPlaceholder.OPEN_PLUGINS)
        waitFor(hasText(PluginsPage.INTRO))
        waitFor(button("Turn on again"))
        val slotEntries = compose.runOnIdle { PluginSlots.routeTypes.filter { type -> runCatching { nav.getBackStackEntry(type) }.isSuccess } }
        assertEquals("no entry of a slot on the back stack", emptyList<Any>(), slotEntries)
        back()
        waitFor(hasText("Plugins") and hasClickAction())
        compose.waitUntil(5_000) { !exists(hasText(PluginsPage.INTRO)) }
        back()
        waitFor(homeShown)
    }

    private companion object {
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val SHOW = 900002

        /** How far above the lowest tab's foot the bar's tabs' middles are, at most: the bar's height and then some. */
        const val BAR_DP = 120f

        /** A second plugin's label as long as a tab's label may be: 16 characters (L613). */
        const val LONG = "Kestrel Cup Live"
    }
}

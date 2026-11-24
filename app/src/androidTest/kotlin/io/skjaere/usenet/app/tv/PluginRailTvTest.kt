package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.DeviceRegistry
import io.skjaere.usenet.app.plugins.FakeCount
import io.skjaere.usenet.app.plugins.FakePlugin
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.FAKE
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.KESTREL
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.SECOND_PAGE
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.SETTINGS
import io.skjaere.usenet.app.plugins.FakePluginRule
import io.skjaere.usenet.app.plugins.InstallResult
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginPlaceholder
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginTab1
import io.skjaere.usenet.app.plugins.SKELETON
import io.skjaere.usenet.app.plugins.Sha256
import io.skjaere.usenet.app.plugins.SkeletonBuild
import io.skjaere.usenet.app.plugins.await
import io.skjaere.usenet.app.plugins.pluginScreens
import io.skjaere.usenet.app.plugins.pluginTabs
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A plugin's rail entry on a real TV (tv36; L582, L590, L618), with the remote, an in-process fake in the app's first slot
 * ([FakePluginRule]): the rail's order, Search, Home, Discover, the fake, Collection, Settings, and with two plugins; OK on
 * its item takes the focus into its page, Down goes along its rows, Left to the rail and Right back, Back to the rail and
 * from there Home, as on every tab (TvBackPrecedenceTest). The rail's focus state kept as the tabs change: as the registry
 * first loads (a skeleton a process before stored, the shell over a registry of the test's own that starts later), the
 * focus held on the rail stays there, on Home or on an item the plugin's comes in above, the rail open with its names,
 * and Right comes back where it was in the content; at a cold start on the plugin's
 * tab its page takes the focus as it loads; as another plugin's label changes, the focus in the page and on the rail stays
 * put. The placeholder of a plugin off after an error: its Open Plugins focused after OK, and focusing the card in
 * Settings › Plugins. And the real skeleton v1 from the test APK's assets: its second page, and Back (L595's last gap).
 *
 * TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PluginRailTvTest {
    private val plugins = PluginStateRule()
    private val fake = FakePlugin()
    private val fakes = FakePluginRule(plugins, fake)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule, then the fakes, around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(fakes).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = plugins.context
    private val container = context.container
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun tv() {
        assumeTv()
        home.show()
        // As MainActivity's first creation does: a plugin installed from its file loads from now on.
        container.plugins.firstActivity()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        home.restore()
    }

    /** The app's screens as a TV shows them, the first focus in Home's content. */
    private fun showTheApp() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; focus on \"${remote.focused()}\"", e)
        }
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The rail's items, top to bottom, by their tabs' names. */
    private fun railLabels(): List<String> = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }.sortedBy { it.boundsInRoot.top }.map { it.label() }

    /** A button or a row of a page, by its words: never a rail item, whose names are the same at times (Settings, Search). */
    private fun control(words: String) = hasText(words, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun waitForFocusOn(matcher: SemanticsMatcher, what: String, timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"", e)
        }
    }

    /** From Home's content to the rail, then down it to [label]'s item. */
    private fun toRailItem(label: String) {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertEquals("on the rail", label, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, label, max = 6))
    }

    /** [kestrel]'s next version gives [label]: off after an error in its scope, then on again, as Turn on again does it. */
    private fun relabel(kestrel: FakePlugin, label: String) = runBlocking {
        kestrel.failIn(fakes.instance(kestrel).host)
        plugins.app.await(KESTREL, "off after an error") { it.status == PluginStatus.OffAfterError }
        kestrel.label = label
        plugins.app.turnOnAgain(KESTREL)
        plugins.app.await(KESTREL, "running as $label") { it.status is PluginStatus.Running && it.label == label }
    }

    /** L618, W160: the rail's order with one plugin, then two; Home's content keeps the focus as the second comes. */
    @Test
    fun theRailHasThePluginsAfterDiscover() {
        showTheApp()
        waitFor(railItem("Fake"))
        assertEquals("the rail", listOf("Search", "Home", "Discover", "Fake", "Collection", "Settings"), railLabels())
        fakes.install(FakePlugin(KESTREL, "Kestrel"))
        waitFor(railItem("Kestrel"))
        assertEquals("the rail with two plugins", listOf("Search", "Home", "Discover", "Fake", "Kestrel", "Collection", "Settings"), railLabels())
        assertTrue("the focus still in Home's content: \"${remote.focused()}\"", remote.isFocused(inTvHome))
    }

    /**
     * Spike §2.4's walk, on a plugin of the platform's: OK on its item, the focus on its page's first row; Down along its
     * rows; Left to the rail, Right back to the row it left; Back to the rail, then from it Home, the rail on Home's item.
     */
    @Test
    fun okOnItsItemTakesTheFocusIntoItsPageAndTheRemoteGoesAsOnEveryTab() {
        showTheApp()
        toRailItem("Fake")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(FakePlugin.ownPage("Fake")))
        waitForFocusOn(control(SECOND_PAGE), "its first row")
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitForFocusOn(control(SETTINGS), "its second row")
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitForFocusOn(control(FakePlugin.PLAY), "its third row")

        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Fake"), "its rail item")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForFocusOn(control(FakePlugin.PLAY), "the row it left")

        remote.press(KeyEvent.KEYCODE_BACK)
        waitForFocusOn(railItem("Fake"), "its rail item, after Back")
        assertTrue("its page still shows", exists(hasText(FakePlugin.ownPage("Fake"))))
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(tvHome)
        waitForFocusOn(railItem("Home"), "the rail's Home, after Back on the rail")
        assertFalse("its page closed", exists(hasText(FakePlugin.ownPage("Fake"))))
    }

    /** The skeleton v1, installed and loaded by a registry standing for the process before: its record has its tab's label. */
    private fun storedByAProcessBefore() = runBlocking {
        val before = plugins.registry().apply { start(asked = false) }
        assertTrue("v1 installed", before.install(SkeletonBuild.V1, SkeletonBuild.V1.download(context)) is InstallResult.Installed)
        before.firstScreen()
        before.await(SKELETON, "running") { it.status is PluginStatus.Running }
        before.close()
    }

    /**
     * The TV shell over [registry]'s tabs and slots, not started yet, as the app's at a cold start: Home a stand-in with two
     * buttons, the plugin slots the app's.
     */
    private fun showTheShell(registry: DeviceRegistry) {
        val playback = container.playback
        compose.setTvContent {
            nav = rememberNavController()
            val installed by registry.registry.plugins.collectAsState()
            val tabs = remember(installed) { tabsFor(FormFactor.Tv, plugins = pluginTabs(installed)) }
            TvShell(nav, tabs, playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { screen ->
                        TopLevelBack(nav, screen)
                        Column {
                            Button(onClick = {}) { Text(FIRST_STAND_IN) }
                            Button(onClick = {}) { Text(SECOND_STAND_IN) }
                        }
                    }
                    pluginScreens(nav, registry.registry)
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * L582: the registry's first load adds the plugin's rail item as the user holds the rail: the focus stays on the rail's
     * Home, and Right comes back to the second of Home's buttons, where it left the content, as the same rail state goes on.
     */
    @Test
    @FakeCount(0)
    fun theFocusOnTheRailStaysAsTheRegistryFirstLoads() {
        storedByAProcessBefore()
        val next = plugins.registry()
        showTheShell(next)
        compose.onNode(hasText(SECOND_STAND_IN) and hasClickAction()).requestFocus()
        remote.waitForFocus(SECOND_STAND_IN)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertFalse("no plugin's item before the load", exists(railItem("Skeleton")))

        next.start()
        runBlocking { next.await(SKELETON, "running") { it.status is PluginStatus.Running } }
        waitFor(railItem("Skeleton"))
        compose.waitForIdle()
        assertTrue("the focus kept on the rail's Home: \"${remote.focused()}\"", remote.isFocused(railItem("Home")))
        assertTrue("the rail still open, its names shown", exists(railItem("Skeleton") and hasText("Skeleton")))
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.waitForFocus(SECOND_STAND_IN)
    }

    /**
     * L582: the same with the rail held on an item the plugin's comes in above (Collection, after Discover): each tab's item
     * keeps its own element as the rail's list changes, so the focus stays on Collection's, never handed to the plugin's
     * item, and Right comes back to the content.
     */
    @Test
    @FakeCount(0)
    fun theFocusOnARailItemBelowThePluginsStaysAsTheRegistryFirstLoads() {
        storedByAProcessBefore()
        val next = plugins.registry()
        showTheShell(next)
        compose.onNode(hasText(SECOND_STAND_IN) and hasClickAction()).requestFocus()
        remote.waitForFocus(SECOND_STAND_IN)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertEquals("on the rail", "Collection", remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Collection", max = 4))

        next.start()
        runBlocking { next.await(SKELETON, "running") { it.status is PluginStatus.Running } }
        waitFor(railItem("Skeleton"))
        compose.waitForIdle()
        assertEquals("the rail", listOf("Search", "Home", "Discover", "Skeleton", "Collection", "Settings"), railLabels())
        assertTrue("the focus kept on Collection's item: \"${remote.focused()}\"", remote.isFocused(railItem("Collection")))
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.waitForFocus(SECOND_STAND_IN)
    }

    /**
     * L590: a cold start on the plugin's tab draws nothing while its registry reads its files; as it loads, its page
     * shows beside the rail and takes the focus, so something is focused.
     */
    @Test
    @FakeCount(0)
    fun atAColdStartOnItsTabItsPageTakesTheFocusAsItLoads() {
        storedByAProcessBefore()
        val next = plugins.registry()
        showTheShell(next)
        compose.runOnIdle { nav.switchTab(PluginTab1) }
        compose.waitForIdle()
        assertFalse("nothing drawn before its files are read", exists(SKELETONS_PAGE))

        next.start()
        waitFor(SKELETONS_PAGE)
        waitFor(railItem("Skeleton"))
        // The skeleton's own first focus, Search, else the first of its page the shell puts the focus on.
        try {
            compose.waitUntil(5_000) { remote.isFocused(control("Search")) || remote.isFocused(fieldGate("Words")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never went into its page; it is on \"${remote.focused()}\"", e)
        }
    }

    /**
     * L582: another plugin's label changes (its next version's): the focus in the fake's page stays on its row; then held
     * on the rail on the fake's item, it stays there, and Right comes back to that row.
     */
    @Test
    fun theFocusStaysPutAsAnotherPluginsLabelChanges() {
        val kestrel = FakePlugin(KESTREL, "Kestrel")
        fakes.install(kestrel)
        showTheApp()
        toRailItem("Fake")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForFocusOn(control(SECOND_PAGE), "its first row")
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitForFocusOn(control(SETTINGS), "its second row")

        relabel(kestrel, "Kestrel Cup")
        waitFor(railItem("Kestrel Cup"))
        compose.waitForIdle()
        assertTrue("the focus kept in its page: \"${remote.focused()}\"", remote.isFocused(control(SETTINGS)))

        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Fake"), "its rail item")
        relabel(kestrel, "Kestrel Live")
        waitFor(railItem("Kestrel Live"))
        compose.waitForIdle()
        assertTrue("the focus kept on the rail: \"${remote.focused()}\"", remote.isFocused(railItem("Fake")))
        assertTrue("the rail still open, its names shown", exists(railItem("Kestrel Live") and hasText("Kestrel Live")))
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForFocusOn(control(SETTINGS), "the row it left")
    }

    /**
     * L590, SpTvListStates 5: the fake off after an error, OK on its item focuses the placeholder's Open Plugins; OK there,
     * Settings › Plugins with its card's first button, Turn on again, focused; Back, Settings, its Plugins row focused, as
     * Back from the page its hub row opened leaves it.
     */
    @Test
    fun thePlaceholdersOpenPluginsIsFocusedAndFocusesItsCard() {
        showTheApp()
        fake.failIn(fakes.instance(fake).host)
        runBlocking { plugins.app.await(FAKE, "off after an error") { it.status == PluginStatus.OffAfterError } }
        toRailItem("Fake")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForFocusOn(control(PluginPlaceholder.OPEN_PLUGINS), "Open Plugins")
        assertTrue("why it is off", exists(hasText("Fake is turned off")))

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(PluginsPage.INTRO))
        waitForFocusOn(control("Turn on again"), "its card's Turn on again")
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Plugins")
        assertTrue("Settings, its Plugins row focused: \"${remote.focused()}\"", remote.focused().startsWith("Plugins | "))
    }

    /**
     * L595's last gap: the real skeleton v1, from the assets, installed and loaded from the app's storage: OK on its item,
     * the focus in its page; its second page by remote, whose Back button, then Back on the remote, bring its page back
     * with the focus in it.
     */
    @Test
    @FakeCount(0)
    fun theRealSkeletonsSecondPageOpensAndBackComesBack() {
        val download = SkeletonBuild.V1.download(context)
        assertTrue("v1 installed", runBlocking { plugins.app.install(download, SkeletonBuild.V1.request(Sha256.of(download))) } is InstallResult.Installed)
        runBlocking { plugins.app.await(SKELETON, "running") { it.status is PluginStatus.Running } }
        showTheApp()
        toRailItem("Skeleton")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(SKELETONS_PAGE)
        waitForFocusOn(control("Search"), "its Search")

        repeat(2) { time ->
            assertTrue("its Second page row reached", SECOND_PAGE in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SECOND_PAGE, max = 6))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Opened from the skeleton's first page"))
            waitForFocusOn(control("Back"), "its second page's Back")
            // Its own Back button, then the remote's.
            remote.press(if (time == 0) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_BACK)
            waitFor(SKELETONS_PAGE)
            compose.waitUntil(5_000) { !exists(hasText("Opened from the skeleton's first page")) }
            try {
                compose.waitUntil(5_000) { remote.focused() != "(nothing)" && !remote.isFocused(tabRole) }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("the focus not in its page after Back; it is on \"${remote.focused()}\"", e)
            }
        }
    }

    private companion object {
        const val FIRST_STAND_IN = "Home stand-in"
        const val SECOND_STAND_IN = "Home's second stand-in"

        /** The skeleton's own page, by its last row's line. */
        val SKELETONS_PAGE = hasText("Throws as it is drawn")
    }
}

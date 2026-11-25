package io.skjaere.usenet.app.plugins.sportarr

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.InstallResult
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginLoader
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.await
import io.skjaere.usenet.app.plugins.describe
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.fieldGate
import io.skjaere.usenet.app.tv.label
import io.skjaere.usenet.app.tv.leaveTouchMode
import io.skjaere.usenet.app.tv.railItem
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.plugin.NzPlugin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The real Sportarr plugin on a device (L639, L657): its debug build from the test APK's assets, installed through the app's
 * own registry as the Plugins page's installer does after a download, loads in this process by a class loader of its own;
 * its tab "Sports" comes after Discover in the shell's order (W160: a phone's bar Home, Discover, Sports, Collection,
 * Search; a TV's rail Search, Home, Discover, Sports, Collection, Settings); and its page draws, on a TV with the focus in
 * it. The phone's case skips on a TV and the TV's on a phone: each shell is the device's own.
 *
 * TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class SportarrLoadDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = plugins.context
    private val container = context.container
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private val remote = Remote(compose)

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

    /** The real plugin installed and running in the app's registry, its first screen asked as MainActivity's creation asks. */
    private fun installed() = runBlocking {
        container.plugins.firstActivity()
        val result = plugins.installSportarr()
        assertTrue("installed in the first slot: $result", (result as? InstallResult.Installed)?.slot == 1)
        plugins.app.await(SPORTARR, "running") { it.status is PluginStatus.Running }
    }

    /** L657: from the assets, through the app's own registry, into this process by a PathClassLoader of its own. */
    @Test
    fun itInstallsFromTheAssetsThroughTheAppsRegistryAndLoadsInThisProcess() {
        val listed = installed()
        assertEquals("its version, 0.1.0's code", SportarrBuild.VERSION_CODE, listed.versionCode)
        assertEquals("its name, as the list gives it", SportarrBuild.NAME, listed.name)
        val instance = (listed.status as PluginStatus.Running).instance
        val entry = instance.plugin.javaClass
        val loader = entry.classLoader!!
        val appLoader = PluginLoader::class.java.classLoader!!
        assertEquals(SPORTARR_ENTRY, entry.name)
        assertTrue("the entry's loader is a PathClassLoader of its own", loader is PathClassLoader && loader !== appLoader)
        assertTrue("whose parent is the app's loader", loader.parent === appLoader)
        assertTrue("over its file in the app's own storage", loader.toString().contains(plugins.root.path + "/"))
        assertSame("the API the entry implements is the app's own", NzPlugin::class.java, entry.interfaces.single())
        assertEquals("its tab's label", "Sports", instance.tab.label)
        // Loaded in this process, from the app's own registry (above): the test runs in the app's process, so a load that
        // restarted it would have ended this run.
        assertSame("the instance the app's registry runs", instance, (plugins.app.plugins.value!!.single().status as PluginStatus.Running).instance)
    }

    /** W160 on a phone: the bar's order, then the tab's page, the setup form's first lines. */
    @Test
    fun onAPhoneItsTabComesAfterDiscoverAndItsPageDraws() {
        assumeFalse("a TV: its rail is the TV case's", container.television)
        home.show()
        tmdb = FakeTmdb(strict = true)
        installed()
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        waitFor(barTab("Sports"))
        assertEquals("the bar", listOf("Home", "Discover", "Sports", "Collection", "Search"), barLabels())

        compose.onNode(barTab("Sports")).performClick()
        waitFor(hasText(INTRO))
        assertTrue("its address field", exists(hasText("Sportarr address")))
        assertTrue("its key field", exists(hasText("API key")))
    }

    /** W160 on a TV: the rail's order; OK on its item, the tab's page with the focus on its first field. */
    @Test
    fun onATvItsRailItemComesAfterDiscoverAndItsPageTakesTheFocus() {
        assumeTv()
        home.show()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
        installed()
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
        waitFor(railItem("Sports"))
        assertEquals("the rail", listOf("Search", "Home", "Discover", "Sports", "Collection", "Settings"), railLabels())

        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertEquals("on the rail", "Sports", remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Sports", max = 6))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(INTRO))
        waitForFocusOn(fieldGate("Sportarr address"), "its address field")
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun barTab(label: String) = hasText(label) and tabRole

    /** The bar's labels, left to right: the tabs at the screen's foot. */
    private fun barLabels(): List<String> {
        val tabs = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        val bottom = tabs.maxOfOrNull { it.boundsInRoot.bottom } ?: return emptyList()
        val bar = tabs.first().layoutInfo.density.density * BAR_DP
        return tabs.filter { bottom - it.boundsInRoot.center.y < bar }.sortedBy { it.boundsInRoot.left }
            .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } }
    }

    /** The rail's items, top to bottom, by their tabs' names. */
    private fun railLabels(): List<String> = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }.sortedBy { it.boundsInRoot.top }.map { it.label() }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
    }

    private fun waitForFocusOn(matcher: SemanticsMatcher, what: String) {
        try {
            compose.waitUntil(5_000) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"", e)
        }
    }

    private companion object {
        /** The tab's first screen, under its title. */
        const val INTRO =
            "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."

        /** How far above the screen's foot a tab's centre may be and still be the bar's. */
        const val BAR_DP = 120f
    }
}

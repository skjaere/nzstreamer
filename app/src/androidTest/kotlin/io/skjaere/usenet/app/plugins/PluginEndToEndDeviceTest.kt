package io.skjaere.usenet.app.plugins

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real plugin end to end on a phone (W155, W159, L581, L621, L633): the skeleton's real builds, listed and served by a list
 * of the test's own on this device's loopback address ([PluginListServer], read through PluginSource's test source as
 * GitHub's would be), installed, updated and removed through the app's own Settings › Plugins, list, installer and
 * registry, in the app's own shell and graph (AppNav). Settings › Plugins lists the skeleton; Install, then Cancel while it
 * downloads, keeps nothing; Install again goes to its end, and its tab is there with nothing made again (the activity, the
 * app's container, the shell's navigation and a value its composition keeps are the ones from before the install: a restart
 * would end the instrumentation's process with the app's, and the run with it); Open Skeleton goes to it. (mock) Its page's
 * search of the fake indexer lists the answer; it marks its item watched and saves a key. Then v2 listed: the update
 * waits while the tab shows, swaps once another tab shows, its card says "Updated to 2", and the tab is v2's. Then v2
 * withdrawn: the placeholder, its Open Plugins, and Remove there: the tab, its files and its keys gone, its watch row kept.
 * And M-size: the four builds' sizes, through the instrumentation's status.
 *
 * The settings the test changes and the item's watch row are put back as they were; PluginStateRule takes the plugin out.
 * TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class PluginEndToEndDeviceTest {
    @get:Rule(order = 0)
    val plugins = PluginStateRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = plugins.context
    private val container = context.container
    private val server = PluginListServer()
    private val home = HomeContentShown()
    private val mock = PluginMockSetup(FAKE_INDEXER, INDEXER_NAME)
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    /** A value the shell's composition keeps for its life: the same one is there as long as nothing was made again. */
    private var kept: Any? = null

    @Before
    fun setUp() {
        PluginSource.override = server.source
        // As MainActivity's first creation does: the plugins' code loads from now on, as an install's does.
        container.plugins.firstActivity()
        home.show()
        tmdb = FakeTmdb(strict = true)
        runBlocking { mock.forget(WATCH_KEY) }
    }

    @After
    fun restore() {
        // The runner's source for the whole run: an address that answers nothing (L622).
        PluginSource.override = PluginSource.CLOSED
        server.close()
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        runBlocking { mock.restore() }
        home.restore()
    }

    private fun showTheApp() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                kept = remember { Any() }
                AppNav(nav = nav)
            }
        }
        waitFor(barTab("Home"))
    }

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** A page's button or row, never a tab of the bar, whose names are the same at times (Search). */
    private fun control(words: String) = hasText(words) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}, the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
    }

    private fun press(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performScrollTo().performClick()
    }

    private fun press(words: String) = press(hasText(words) and hasClickAction())

    /** What Back does on a phone. */
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openTab(label: String) {
        waitFor(barTab(label))
        compose.onNode(barTab(label)).performClick()
        compose.waitForIdle()
    }

    /** Settings › Plugins from Home, as the gear and the hub's row open it. */
    private fun openPlugins() {
        press(hasContentDescription("Settings") and hasClickAction())
        press("Plugins")
        waitFor(hasText(PluginsPage.INTRO))
    }

    /** A read of the list, as the day's read or Check again would make it, and what it brought (updates, withdrawals) done. */
    private fun read() = runBlocking {
        val before = container.pluginInstaller.readsApplied.value
        container.pluginList.checkAgain().join()
        withTimeout(PLUGIN_WAIT_MS) { container.pluginInstaller.readsApplied.first { it > before } }
    }

    /**
     * The skeleton as the app's registry lists it once [what] holds, the screens' effects running meanwhile: the slots'
     * watch, which asks for the swap point, is one, and a wait that held the test's thread would hold it too.
     */
    private fun listed(what: String, holds: (InstalledPlugin) -> Boolean): InstalledPlugin {
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value?.firstOrNull { it.id == SKELETON }?.let(holds) == true }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the skeleton isn't $what: the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
        return plugins.app.plugins.value!!.first { it.id == SKELETON }
    }

    private fun running(): PluginInstance = (plugins.app.plugins.value?.firstOrNull { it.id == SKELETON }?.status as? PluginStatus.Running)?.instance
        ?: throw AssertionError("the skeleton isn't running: ${describe(plugins.app.plugins.value)}")

    /** Home's own screen: its search field. */
    private val homeShown = hasText("Search your indexers") and hasClickAction()

    private val downloads: File get() = File(File(context.cacheDir, PluginStore.DIR), PluginStore.DOWNLOADS)

    @Test
    fun aRealPluginFromTheListInstallsUpdatesAndGoesWithNoRestart() {
        mock.assumeUp(plays = false)
        runBlocking { mock.setUp(plays = false) }
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        showTheApp()
        val activity = compose.activity
        val app = context.container
        val shellNav = nav
        val shellKept = kept

        // Settings › Plugins lists the skeleton.
        openPlugins()
        waitFor(hasText("The plugin platform's own example, with invented words."))
        waitFor(hasText("Skeleton"))

        // Install, then Cancel while it downloads: nothing kept.
        server.holdDownloads()
        press("Install")
        waitFor(hasText(PluginsPage.DOWNLOADING))
        press("Cancel")
        waitFor(hasText("Install") and hasClickAction())
        compose.waitUntil(PLUGIN_WAIT_MS) { downloads.listFiles().isNullOrEmpty() }
        assertTrue("no plugin", plugins.app.plugins.value.orEmpty().isEmpty())
        assertFalse("nothing stored", File(plugins.root, SKELETON).exists())
        server.release()

        // Install again, to its end: its tab, with nothing made again.
        press("Install")
        waitFor(hasText("Installed. Skeleton is now a tab after ${pluginTabsFollow(FormFactor.Phone)}."))
        val v1 = listed("running") { it.status is PluginStatus.Running }
        assertEquals("v1", 1L, v1.versionCode)
        assertSame("the same activity", activity, compose.activity)
        assertSame("the same app", app, context.container)
        assertSame("the same navigation", shellNav, nav)
        assertSame("the same composition", shellKept, kept)

        // Open Skeleton: its tab, beside the others.
        press("Open Skeleton")
        waitFor(SKELETONS_PAGE)
        waitFor(barTab("Skeleton"))
        assertTrue("its tab chosen", compose.onNode(barTab("Skeleton")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)

        // (mock) Its page's search of the fake indexer: the indexer's line and the answer listed.
        press(control("Search"))
        waitFor(hasText(GOOD), 60_000)
        assertTrue("the indexer's line", exists(hasText("$INDEXER_NAME: 2 found")))

        // Its item marked watched, and a key saved on its settings page.
        press(control(MARK))
        waitFor(control(MARK) and hasText("Watched"))
        assertTrue("its watch row", runBlocking { container.library.state(WATCH_KEY).first() }?.watched == true)
        press(control("Settings"))
        waitFor(hasText("Skeleton settings"))
        compose.onNode(hasSetTextAction()).performTextInput(INVENTED_KEY)
        press(control("Save"))
        waitFor(hasText("A key is saved"))
        assertNotNull("its key kept as its secret", runBlocking { container.data.secrets.get(SECRET) })
        back()
        waitFor(SKELETONS_PAGE)

        // v2 listed: downloaded and checked while its tab shows, and waiting.
        val first = running()
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"))
        read()
        listed("with v2 waiting") { it.waitingVersion == 2L }
        compose.waitForIdle()
        assertSame("v1 runs on while its tab shows", first, running())
        assertTrue("its page still shows", exists(SKELETONS_PAGE))

        // Another tab shows: v2 takes over. Home's tab comes back where Open Skeleton left it, Settings › Plugins with the
        // install's line, until that page is left; opened again, its card says so.
        openTab("Home")
        val v2 = listed("swapped to v2") { it.versionCode == 2L && it.status is PluginStatus.Running }
        assertNotEquals("a new instance", first.key, (v2.status as PluginStatus.Running).instance.key)
        compose.waitUntil(PLUGIN_WAIT_MS) { exists(homeShown) || exists(hasText(PluginsPage.INTRO)) }
        repeat(2) { if (!exists(homeShown)) back() }
        waitFor(homeShown)
        openPlugins()
        waitFor(hasText("Updated to 2 today"))
        back()
        back()
        waitFor(homeShown)
        openTab("Skeleton")
        waitFor(SKELETONS_PAGE)
        assertEquals("the tab is v2's", 2L, running().versionCode)

        // v2 withdrawn: let go at once, its tab not showing; its tab then shows the placeholder.
        openTab("Home")
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"), withdrawn = setOf(2))
        read()
        listed("withdrawn") { it.status == PluginStatus.Withdrawn }
        openTab("Skeleton")
        waitFor(hasText(PluginPlaceholder.OPEN_PLUGINS) and hasClickAction())
        assertTrue(exists(hasText("Skeleton") and isHeading()))
        assertTrue(exists(hasText("Skeleton is turned off")))
        assertTrue(exists(hasText("The Skeleton plugin was turned off: version 2 was withdrawn. What you watched is kept.")))

        // Open Plugins, and Remove there.
        press(PluginPlaceholder.OPEN_PLUGINS)
        waitFor(hasText("Version 2 was withdrawn, so it's off until a fixed version is out."))
        press("Remove")
        waitFor(hasText("Remove Skeleton?"))
        compose.onAllNodes(hasText("Remove") and hasClickAction()).onLast().performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value.orEmpty().isEmpty() }
        waitFor(hasText("Install") and hasClickAction())
        assertFalse("its code and data gone", File(plugins.root, SKELETON).exists())
        assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SKELETON).exists())
        assertNull("its key gone", runBlocking { container.data.secrets.get(SECRET) })
        assertTrue("its watch row kept", runBlocking { container.library.state(WATCH_KEY).first() }?.watched == true)
        back()
        back()
        waitFor(homeShown)
        assertFalse("its tab gone", exists(barTab("Skeleton")))
        assertSame("the same activity throughout", activity, compose.activity)
    }

    /**
     * M-size: the skeleton's four builds as the test APK's assets have them from the build's package, through the
     * instrumentation's status (code 2, which result parsers pass over), never logcat.
     */
    @Test
    fun theSkeletonsFourBuildsSizesAreReported() {
        val sizes = SkeletonBuild.entries.associateWith { it.bytes().size }
        PluginMeasure.say("m-size", "skeleton APKs: " + sizes.entries.joinToString { (build, size) -> "${build.name.lowercase()} $size B" })
        sizes.forEach { (build, size) -> assertTrue("$build's APK in the assets", size > 0) }
    }

    private companion object {
        const val FAKE_INDEXER = "plugin-end-to-end-test"
        const val INDEXER_NAME = "Fake (end to end test)"
        const val GOOD = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"

        /** The skeleton's one item, its row's words, and its one key's name. */
        val WATCH_KEY = WatchKey.item(SKELETON, "copper-crown")
        const val MARK = "Mark Copper Crown watched"
        const val SECRET = "${PluginStore.SECRETS}$SKELETON:key"

        /** An invented key, nothing any service gives out. */
        const val INVENTED_KEY = "kestrel-end-to-end-key"

        /** The skeleton's own page, by its last row's line. */
        val SKELETONS_PAGE = hasText("Throws as it is drawn")
    }
}

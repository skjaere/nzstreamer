package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginInstance
import io.skjaere.usenet.app.plugins.PluginListServer
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.app.plugins.PluginMockSetup
import io.skjaere.usenet.app.plugins.PluginPlaceholder
import io.skjaere.usenet.app.plugins.PluginSource
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.SKELETON
import io.skjaere.usenet.app.plugins.SkeletonBuild
import io.skjaere.usenet.app.plugins.describe
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real plugin end to end on a real TV (tv36; W155, W159, L581, L621, L633), by remote: the skeleton's real builds from a
 * list of the test's own on this device's loopback address ([PluginListServer]), through the app's own Settings › Plugins,
 * list, installer and registry, in the app's own shell (AppNav). Settings › Plugins by the rail; Install, Cancel while it
 * downloads keeping nothing, Install to its end with nothing made again (as the phone's class shows it); Open Skeleton,
 * its page beside the rail with its entry there after Discover; (mock) its search of the fake indexer; its item marked
 * watched. v2 listed while its tab shows waits; Home by the rail swaps it in; its card says "Updated to 2" and its hub row
 * that it was updated; its tab is v2's. v2 withdrawn while Home shows: its tab's placeholder, whose Open Plugins focuses
 * Remove, the card's only button; Remove's question, Cancel focused, then Remove: its entry, files and keys gone, its
 * watch row kept, and the card's Install focused. Something is focused after every step.
 *
 * The settings the test changes and the item's watch row are put back as they were; PluginStateRule takes the plugin out.
 * TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PluginEndToEndTvTest {
    @get:Rule(order = 0)
    val plugins = PluginStateRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = plugins.context
    private val container = context.container
    private val remote = Remote(compose)
    private val server = PluginListServer()
    private val home = HomeContentShown()
    private val mock = PluginMockSetup(FAKE_INDEXER, INDEXER_NAME)
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    /** A value the shell's composition keeps for its life: the same one is there as long as nothing was made again. */
    private var kept: Any? = null

    @Before
    fun setUp() {
        assumeTv()
        PluginSource.override = server.source
        // As MainActivity's first creation does: the plugins' code loads from now on, as an install's does.
        container.plugins.firstActivity()
        home.show()
        tmdb = FakeTmdb(strict = true)
        runBlocking { mock.forget(WATCH_KEY) }
        leaveTouchMode(compose.activity)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
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

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; focus on \"${remote.focused()}\", the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The rail's items, top to bottom, by their tabs' names. */
    private fun railLabels(): List<String> = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }.sortedBy { it.boundsInRoot.top }.map { it.label() }

    /** A button or a row of a page, by its words: never a rail item, whose names are the same at times (Search, Settings). */
    private fun control(words: String) = hasText(words, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun focusOn(matcher: SemanticsMatcher, what: String, timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"", e)
        }
    }

    /** Something focused, after every step. */
    private fun somethingFocused(step: String) = assertNotEquals("nothing focused $step", "(nothing)", remote.focused())

    private fun press(key: Int) = remote.press(key)

    /** From the content to the rail, then along it to [label]'s item, and OK. */
    private fun openByTheRail(label: String) {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        try {
            compose.waitUntil(5_000) { remote.isFocused(tabRole) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Left never reached the rail; focus on \"${remote.focused()}\"", e)
        }
        val rail = railLabels()
        val at = rail.indexOf(remote.focused())
        val to = rail.indexOf(label)
        assertTrue("$label on the rail $rail", to >= 0)
        repeat(kotlin.math.abs(to - at)) { press(if (to > at) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        focusOn(railItem(label), "the rail's $label")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /**
     * Settings › Plugins by remote: the rail's Settings, then down to its hub row ([row] its line), and OK; or, where the
     * Settings tab comes back on the page, as it was left there, the page at once.
     */
    private fun openPlugins(row: String) {
        openByTheRail("Settings")
        compose.waitUntil(PLUGIN_WAIT_MS) { exists(hasText(PluginsPage.INTRO)) || "Usenet providers" in remote.focused() }
        if (exists(hasText(PluginsPage.INTRO))) return
        assertTrue("the hub's row", row in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Plugins"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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

    private val downloads: File get() = File(File(context.cacheDir, PluginStore.DIR), PluginStore.DOWNLOADS)

    @Test
    fun aRealPluginFromTheListByRemote() {
        mock.assumeUp(plays = false)
        runBlocking { mock.setUp(plays = false) }
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        compose.setTvContent {
            nav = rememberNavController()
            kept = remember { Any() }
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
        val activity = compose.activity
        val app = context.container
        val shellNav = nav
        val shellKept = kept

        // Settings › Plugins: the skeleton listed, its Install focused.
        openPlugins("Plugins | None yet")
        remote.waitForFocus("Install", PLUGIN_WAIT_MS)
        assertTrue("the skeleton listed", exists(hasText("The plugin platform's own example, with invented words.")))

        // Install, then Cancel while it downloads: nothing kept, Install focused again.
        server.holdDownloads()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Cancel", PLUGIN_WAIT_MS)
        waitFor(hasText(PluginsPage.DOWNLOADING))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Install", PLUGIN_WAIT_MS)
        compose.waitUntil(PLUGIN_WAIT_MS) { downloads.listFiles().isNullOrEmpty() }
        assertTrue("no plugin", plugins.app.plugins.value.orEmpty().isEmpty())
        assertFalse("nothing stored", File(plugins.root, SKELETON).exists())
        server.release()

        // Install to its end: Open Skeleton focused, nothing made again.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Open Skeleton", PLUGIN_WAIT_MS)
        assertTrue(exists(hasText("Installed. Skeleton is now a tab after ${pluginTabsFollow(FormFactor.Tv)}.")))
        assertSame("the same activity", activity, compose.activity)
        assertSame("the same app", app, context.container)
        assertSame("the same navigation", shellNav, nav)
        assertSame("the same composition", shellKept, kept)
        val first = running()

        // Open Skeleton: its page, the focus in it, its entry on the rail after Discover.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(SKELETONS_PAGE)
        focusOn(control("Search"), "its Search")
        assertEquals("the rail", listOf("Search", "Home", "Discover", "Skeleton", "Collection", "Settings"), railLabels())

        // (mock) Its search of the fake indexer: the answer listed.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(GOOD), 60_000)
        assertTrue("the indexer's line", exists(hasText("$INDEXER_NAME: 2 found")))
        somethingFocused("after its search")

        // Its item marked watched by remote.
        assertTrue(MARK in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, MARK, max = 12))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(control(MARK) and hasText("Watched"))
        assertTrue("its watch row", runBlocking { container.library.state(WATCH_KEY).first() }?.watched == true)
        // A key, as its settings page saves one (its field would take the TV's keyboard).
        runBlocking { running().host.secrets.put("key", INVENTED_KEY) }

        // v2 listed while its tab shows: stored, waiting, v1 running on.
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"))
        read()
        listed("with v2 waiting") { it.waitingVersion == 2L }
        compose.waitForIdle()
        assertSame("v1 runs on while its tab shows", first, running())
        somethingFocused("with v2 waiting")

        // Home by the rail: v2 takes over.
        openByTheRail("Home")
        waitFor(tvHome)
        val v2 = listed("swapped to v2") { it.versionCode == 2L && it.status is PluginStatus.Running }
        assertNotEquals("a new instance", first.key, (v2.status as PluginStatus.Running).instance.key)
        somethingFocused("on Home after the swap")

        // Its hub row and card say so. The Settings tab comes back where Open Skeleton left it, Settings › Plugins with the
        // install's line, until that page is left: Back from it to the row, and OK there opens it afresh.
        openByTheRail("Settings")
        compose.waitUntil(PLUGIN_WAIT_MS) { exists(hasText(PluginsPage.INTRO)) || "Usenet providers" in remote.focused() }
        if (exists(hasText(PluginsPage.INTRO))) {
            somethingFocused("on the page it left")
            press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(UPDATED_ROW)
        } else {
            assertTrue("the hub's row", UPDATED_ROW in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Plugins"))
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Updated to 2 today"))
        somethingFocused("on its card")
        press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(UPDATED_ROW)
        openByTheRail("Skeleton")
        waitFor(SKELETONS_PAGE)
        focusOn(control("Search"), "v2's Search")
        assertEquals("the tab is v2's", 2L, running().versionCode)

        // v2 withdrawn while Home shows: let go at once; its tab the placeholder, Open Plugins focused.
        openByTheRail("Home")
        waitFor(tvHome)
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"), withdrawn = setOf(2))
        read()
        listed("withdrawn") { it.status == PluginStatus.Withdrawn }
        somethingFocused("on Home after the withdrawal")
        openByTheRail("Skeleton")
        focusOn(control(PluginPlaceholder.OPEN_PLUGINS), "Open Plugins")
        assertTrue(exists(hasText("The Skeleton plugin was turned off: version 2 was withdrawn. What you watched is kept.")))

        // Open Plugins: its card's Remove focused; the question with Cancel focused; Remove.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Version 2 was withdrawn, so it's off until a fixed version is out."))
        remote.waitForFocus("Remove")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Remove Skeleton?"))
        remote.waitForFocus("Cancel")
        // Past the question's guard, which lets by an OK just after the one that asked.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.waitForFocus("Remove")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value.orEmpty().isEmpty() }
        remote.waitForFocus("Install", PLUGIN_WAIT_MS)
        assertFalse("its code and data gone", File(plugins.root, SKELETON).exists())
        assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SKELETON).exists())
        assertNull("its key gone", runBlocking { container.data.secrets.get(SECRET) })
        assertTrue("its watch row kept", runBlocking { container.library.state(WATCH_KEY).first() }?.watched == true)

        // Back on Settings, its first row focused, its hub row's line again "None yet": the rail without it.
        press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Usenet providers")
        assertTrue("the hub's row", exists(hasText("None yet")))
        assertFalse("its rail entry gone", exists(railItem("Skeleton")))
        assertSame("the same activity throughout", activity, compose.activity)
    }

    private companion object {
        const val FAKE_INDEXER = "plugin-end-to-end-tv-test"
        const val INDEXER_NAME = "Fake (end to end TV test)"
        const val GOOD = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"

        /** The skeleton's one item, its row's words, and its one key's name. */
        val WATCH_KEY = WatchKey.item(SKELETON, "copper-crown")
        const val MARK = "Mark Copper Crown watched"
        const val SECRET = "${PluginStore.SECRETS}$SKELETON:key"

        /** An invented key, nothing any service gives out. */
        const val INVENTED_KEY = "kestrel-end-to-end-key"

        /** The skeleton's own page, by its last row's line. */
        val SKELETONS_PAGE = hasText("Throws as it is drawn")

        /** Settings' Plugins row once v2 took over. */
        const val UPDATED_ROW = "Plugins | Skeleton · updated to 2"
    }
}

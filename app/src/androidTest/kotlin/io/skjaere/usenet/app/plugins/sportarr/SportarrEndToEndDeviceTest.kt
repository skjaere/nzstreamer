package io.skjaere.usenet.app.plugins.sportarr

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginDevSecrets
import io.skjaere.usenet.app.plugins.PluginListServer
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.app.plugins.PluginMeasure
import io.skjaere.usenet.app.plugins.PluginSource
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.describe
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * The real Sportarr plugin end to end on a phone (L657): its debug build, sportarr.apk, offered by a plugin list of the
 * test's own on this device's loopback address ([PluginListServer], read through PluginSource's test source as GitHub's
 * would be) with its SHA-256, its version as built (0.1.0, versionCode 10000, until its first release) and its
 * listing's name and summary, as its release would list it. Settings › Plugins shows its name, summary and "Version
 * <its version> · <size>"; Install, through the app's own list, installer and registry, in the app's own shell and
 * graph (AppNav), with nothing made again and nothing of the debug build's own Sportarr settings stored; "Installed.
 * Sports is now a tab after Discover."; Open Sports, the setup's first screen; Test against the fake Sportarr and Save;
 * the list; (mock) a game's page from its row and its Play, a release the plugin accepts from the sports indexer,
 * played in the player through the mock and stopped part-way; Home's Up next with it; and Remove in Settings › Plugins:
 * the tab, the plugin's files and both its secrets gone, the game's watch row kept, Home's Up next with it still. And
 * M-size: sportarr.apk's size, through the instrumentation's status.
 *
 * The settings, the watch row and the fake's switches the test changes are put back as they were; PluginStateRule takes
 * the plugin out whatever the outcome. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class SportarrEndToEndDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrEndToEndDeviceTest")
    private val server = PluginListServer()
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        assumeFalse("a TV: its end to end is SportarrEndToEndTvTest's", container.television)
        PluginSource.override = server.source
        // As MainActivity's first creation does: the plugins' code loads from now on, as an install's does.
        container.plugins.firstActivity()
        home.show()
        tmdb = FakeTmdb(strict = true)
    }

    @After
    fun restore() {
        container.playback.cancel()
        // The runner's source for the whole run: an address that answers nothing (L622).
        PluginSource.override = PluginSource.CLOSED
        server.close()
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        sportarr.restore()
        home.restore()
    }

    private val library get() = sportarr.catalogue

    private fun showTheApp() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        waitFor(barTab("Home"))
    }

    @Test
    fun theRealPluginFromAListSetsUpPlaysAndGoes() {
        sportarr.assumeUp(plays = true)
        sportarr.hold()
        sportarr.indexers(plays = true)
        val game = library.event(GAME)
        val accepted = library.found(GAME, CatalogueEvent.FULL, "accept").map { it.first }.filter { it.whole }.map { it.name }
        sportarr.forget(game.part())
        val size = SportarrBuild.bytes().size
        theBuildsCodeIsItsVersionsByTheConvention()
        server.list(Listed(SportarrBuild.listing, SportarrBuild.versionName))
        showTheApp()
        val activity = compose.activity
        val app = context.container

        // Settings › Plugins: its listing's name and summary, its version and its size.
        openPlugins()
        waitFor(hasText(SportarrBuild.SUMMARY))
        assertTrue("its name", exists(hasText(SportarrBuild.NAME)))
        assertTrue("its version and size", exists(hasText("Version ${SportarrBuild.versionName} · ${megabytes(size)} MB")))

        // Install, to its end: its tab, with nothing made again and nothing of the debug build's own Sportarr stored.
        check(!PluginDevSecrets.seeding) { "The runner left the plugins' dev seeding on: an install would store local.properties' values" }
        press("Install")
        waitFor(hasText("Installed. $SPORTS is now a tab after ${pluginTabsFollow(FormFactor.Phone)}."))
        val installed = listed("running") { it.status is PluginStatus.Running }
        assertEquals("the listed version", SportarrBuild.versionCode to SportarrBuild.versionName, installed.versionCode to installed.versionName)
        assertTrue("its file from its release's address", server.path(SportarrBuild.versionName, SPORTARR) in server.requests)
        assertSame("the same activity", activity, compose.activity)
        assertSame("the same app", app, context.container)
        assertFalse("an address or a key stored by the install", stored("url") || stored("key"))

        // Open Sports: its tab chosen, the setup's first screen.
        press("Open $SPORTS")
        waitFor(hasText(INTRO))
        assertTrue("its tab chosen", compose.onNode(barTab(SPORTS)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)

        // The setup: Test against the fake Sportarr, then Save; the list, read at once.
        type(addressField, SportsFakes.URL)
        type(keyField, SportsFakes.KEY)
        press(button(TEST))
        waitFor(hasText("Connected · Sportarr ${sportarr.version()}"))
        press(button(SAVE))
        waitFor(sideTab(RECENT))
        waitFor(hasText(game.title), LIST_WAIT_MS)
        assertTrue("its address and key stored", stored("url") && stored("key"))
        assertEquals("canary values on the list", emptyList<String>(), compose.canaries())

        // (mock) The game's page from its row, and Play: a release the plugin accepts, from the sports indexer, played in
        // the player through the mock and stopped part-way.
        compose.onAllNodes(list)[0].performScrollToNode(row(game.title))
        compose.onNode(row(game.title)).performClick()
        waitFor(button(PLAY))
        sportarr.paceSearches()
        compose.onNode(button(PLAY)).performScrollTo().performClick()
        val launch = sportarr.launchOf(game.part())
        assertTrue("one the plugin accepts: ${launch.release.title}", launch.release.title in accepted)
        assertEquals("by the game's title", game.title, launch.watch.title)
        val orientation = compose.activity.resources.configuration.orientation
        val kept = sportarr.playAndKeep(game.part(), launch, compose.activity)

        // Home's Up next: the game, to resume.
        homeAfterThePlayer(orientation)
        scrollToOnHome(hasContentDescription("Resume ${game.title}"))
        assertEquals("canary values on Home", emptyList<String>(), compose.canaries())

        // Remove in Settings › Plugins: the tab, its files and both its secrets gone, the game's watch row kept.
        openPlugins()
        waitFor(hasText("Version ${SportarrBuild.versionName} · updates by itself"))
        press("Remove")
        waitFor(hasText(PluginsPage.removeQuestion(SportarrBuild.NAME)))
        compose.onAllNodes(hasText("Remove") and hasClickAction()).onLast().performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value.orEmpty().isEmpty() }
        waitFor(hasText("Install") and hasClickAction())
        assertFalse("its code and data gone", File(plugins.root, SPORTARR).exists())
        assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SPORTARR).exists())
        assertFalse("its address gone", stored("url"))
        assertFalse("its key gone", stored("key"))
        val row = sportarr.state(game.part())
        assertNotNull("the game's watch row after Remove", row)
        assertFalse("taken back", row!!.deleted)
        assertEquals("where it stopped", kept.positionMs, row.positionMs)
        back()
        back()
        waitFor(homeShown)
        assertFalse("its tab gone", exists(barTab(SPORTS)))
        scrollToOnHome(hasContentDescription("Resume ${game.title}"))
    }

    /**
     * M-size: sportarr.apk as the test APK's assets have it from the plugin's debug build, through the instrumentation's
     * status (code 2, which result parsers pass over), never logcat.
     */
    @Test
    fun sportarrsApkSizeIsReported() {
        val size = SportarrBuild.bytes().size
        PluginMeasure.say("m-size", "sportarr.apk: $size B")
        assertTrue("sportarr.apk in the assets", size > 0)
    }

    /**
     * The list offers the build's own version, read from the file: its versionCode is its versionName's by the plugin
     * convention, ((MAJOR * 100 + MINOR) * 100 + PATCH) * 100, as the release's workflow builds it from version.txt.
     */
    private fun theBuildsCodeIsItsVersionsByTheConvention() {
        val (major, minor, patch) = SportarrBuild.versionName.split('.').map(String::toLong)
        assertEquals("the build's versionCode, by its versionName ${SportarrBuild.versionName}", ((major * 100 + minor) * 100 + patch) * 100, SportarrBuild.versionCode)
    }

    /** The list's size in MB with one decimal, as the Plugins page's card gives it. */
    private fun megabytes(size: Int) = String.format(Locale.ROOT, "%.1f", size / 1_000_000.0)

    /** Settings › Plugins from Home, as the gear and the hub's row open it. */
    private fun openPlugins() {
        press(hasContentDescription("Settings") and hasClickAction())
        press("Plugins")
        waitFor(hasText(PluginsPage.INTRO))
    }

    /**
     * Home once the player has closed. Where the test's activity was made again without its content, the app is shown
     * afresh, its plugin running on; else Back from the game's page, which has no tab bar, to the list, and Home's tab,
     * which comes back where Open Sports left it (Settings › Plugins) until that page is left.
     */
    private fun homeAfterThePlayer(orientation: Int) {
        if (settledAfterThePlayer({ compose.activity }, orientation)) {
            showTheApp()
        } else {
            back()
            waitFor(sideTab(RECENT))
            openTab("Home")
            compose.waitUntil(PLUGIN_WAIT_MS) { exists(homeShown) || exists(hasText(PluginsPage.INTRO)) }
            repeat(2) { if (!exists(homeShown)) back() }
        }
        waitFor(homeShown)
    }

    /** Home's [matcher] brought into view, once it shows. */
    private fun scrollToOnHome(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown on Home: ${matcher.description}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
    }

    /** The plugin as the app's registry lists it once [what] holds, the screens' effects running meanwhile. */
    private fun listed(what: String, holds: (InstalledPlugin) -> Boolean): InstalledPlugin {
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value?.firstOrNull { it.id == SPORTARR }?.let(holds) == true }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Sportarr isn't $what: the registry lists ${describe(plugins.app.plugins.value)}", e)
        }
        return plugins.app.plugins.value!!.first { it.id == SPORTARR }
    }

    private fun stored(name: String) = runBlocking { container.data.secrets.get(secret(name)) } != null

    private fun secret(name: String) = "${PluginStore.SECRETS}$SPORTARR:$name"

    private fun type(field: SemanticsMatcher, words: String) {
        compose.onNode(field).performScrollTo().performTextReplacement(words)
        compose.waitForIdle()
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

    /** Home's own screen: its search field. */
    private val homeShown = hasText("Search your indexers") and hasClickAction()

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the bar") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    /** Recent or Upcoming in the list's title line. */
    private fun sideTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** An event's row in a day, by its title: its start's time beside it, which Up next's rows don't have. */
    private fun row(title: String) = hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
        SemanticsMatcher("with its time") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { TIME.matches(it.text) } }

    /** The list itself: the column that scrolls up and down. */
    private val list = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    /** The key's field, read by its label and that it is hidden; the address's, the form's other field. */
    private val keyField = hasSetTextAction() and hasContentDescription("$KEY, hidden")
    private val addressField = hasSetTextAction() and SemanticsMatcher("not the key") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "never shown: ${matcher.description}; on ${nav.currentDestination?.route}, the registry lists ${describe(plugins.app.plugins.value)}; " +
                    "shown: ${compose.screenWords()}",
                e,
            )
        }
    }

    private companion object {
        /** Lantern Bay FC's Premier League game, aired yesterday at the fake's anchor, with releases the plugin accepts. */
        const val GAME = 1001

        const val SPORTS = "Sports"
        const val RECENT = "Recent"
        const val INTRO =
            "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."
        const val KEY = "API key"
        const val TEST = "Test"
        const val SAVE = "Save"
        const val PLAY = "Play"

        /** A row's start, as the list writes it. */
        val TIME = Regex("""\d\d:\d\d""")

        /** The list's first read against the fake. */
        const val LIST_WAIT_MS = 30_000L
    }
}

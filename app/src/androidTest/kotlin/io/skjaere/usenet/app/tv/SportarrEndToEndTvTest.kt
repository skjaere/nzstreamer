package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
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
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginDevSecrets
import io.skjaere.usenet.app.plugins.PluginListServer
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.app.plugins.PluginSource
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.describe
import io.skjaere.usenet.app.plugins.sportarr.CatalogueEvent
import io.skjaere.usenet.app.plugins.sportarr.SPORTARR
import io.skjaere.usenet.app.plugins.sportarr.SportarrBuild
import io.skjaere.usenet.app.plugins.sportarr.SportarrDevice
import io.skjaere.usenet.app.plugins.sportarr.SportsFakes
import io.skjaere.usenet.app.plugins.sportarr.canaries
import io.skjaere.usenet.app.plugins.sportarr.screenWords
import io.skjaere.usenet.app.plugins.sportarr.settledAfterThePlayer
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.FieldGateLabel
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * The real Sportarr plugin end to end on a real TV (tv36; L657), by remote, as SportarrEndToEndDeviceTest takes it on a
 * phone: its debug build, sportarr.apk, offered by a plugin list of the test's own on this device's loopback address
 * ([PluginListServer]) with its SHA-256, its version as built (0.1.0, versionCode 10000, until its first release) and
 * its listing's name and summary. Settings › Plugins by the rail: its name, summary and "Version <its version> ·
 * <size>", Install focused; Install to its end, Open Sports focused; Open Sports, the setup's first focus on the
 * address, the rail with Sports after Discover; the address and key typed, Test against the fake Sportarr, Save
 * focused, Save; the list, All focused; Down to a game's row, OK, its page with Play focused; (mock) Play, a release
 * the plugin accepts from the sports indexer, played in the player through the mock and stopped part-way; Home by the
 * rail, its Up next card for the game focused first; and Remove on its card in Settings › Plugins, its question with
 * Cancel first: the rail without Sports, the plugin's files and both its secrets gone, the game's watch row kept, the
 * card's Install focused. Something is focused after every step. Installed so, UFC 999's page by OK on its row shows its
 * bouts from the fake Wikipedia, read once with NZStreamer's User-Agent, and OK on a bout plays its part, the focus back
 * on the bout (L-B13, L-B10, W5).
 *
 * The settings, the watch row and the fake's switches the test changes are put back as they were; PluginStateRule takes
 * the plugin out whatever the outcome. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class SportarrEndToEndTvTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrEndToEndTvTest")
    private val remote = Remote(compose)
    private val server = PluginListServer()
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun tv() {
        assumeTv()
        sportarr.assumeUp(plays = true)
        PluginSource.override = server.source
        // As MainActivity's first creation does: the plugins' code loads from now on, as an install's does.
        container.plugins.firstActivity()
        home.show()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
        sportarr.hold()
        // Whatever plays goes to the fakes alone: the sports indexer and the mock.
        sportarr.indexers(plays = true)
    }

    /** Each step whatever the ones before it did: a fake that stopped answering mustn't leave Home's setting changed. */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            // A player a failure left open, closed before the rows are put back: its save as it closes would come after.
            { sportarr.closePlayers() },
            // Touch mode outlasts the test's process.
            { instrumentation.setInTouchMode(true) },
            { container.playback.cancel() },
            // The runner's source for the whole run: an address that answers nothing (L622).
            { PluginSource.override = PluginSource.CLOSED },
            { server.close() },
            {
                try {
                    tmdb?.close()
                } catch (e: AssertionError) {
                    // What Home asked of other titles was refused, never sent.
                }
            },
            { sportarr.restore() },
            { home.restore() },
        )
    }

    private val library get() = sportarr.catalogue

    /** The app as a TV shows it, Home's first focus in its content. */
    private fun showTheApp() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    @Test
    fun theRealPluginFromAListByRemote() {
        val game = library.event(GAME)
        val accepted = library.found(GAME, CatalogueEvent.FULL, "accept").map { it.first }.filter { it.whole }.map { it.name }
        sportarr.forget(game.part())
        server.list(Listed(SportarrBuild.listing, SportarrBuild.versionName))
        showTheApp()
        installFromTheListByRemote()

        // Down to the game's row in its day, never Up next's; OK, its page, Play focused.
        val rowOf = Regex("""\d\d:\d\d \| ${Regex.escape(game.title)}( \|.*)?""")
        var row = remote.focused()
        repeat(MAX_DOWNS) { if (!rowOf.matches(row)) row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("the game's row: \"$row\"", rowOf.matches(row))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(game.title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        remote.waitForFocus(PLAY)
        // Past the page's OK guard, which takes an OK in its first moment for the one that opened it.
        SystemClock.sleep(OkGuard.WINDOW_MS)

        // (mock) Play: a release the plugin accepts, from the sports indexer, played in the player through the mock.
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val launch = sportarr.launchOf(game.part())
        assertTrue("one the plugin accepts: ${launch.release.title}", launch.release.title in accepted)
        assertEquals("by the game's title", game.title, launch.watch.title)
        val orientation = compose.activity.resources.configuration.orientation
        val kept = sportarr.playAndKeep(game.part(), launch, compose.activity)
        if (settledAfterThePlayer({ compose.activity }, orientation)) {
            // Made again without its content: the app afresh, its plugin running on.
            leaveTouchMode(compose.activity)
            showTheApp()
        } else {
            // The page again, its Play now Resume; Back to the game's row, as the rail isn't beside a page.
            remote.waitForFocus("Resume")
            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(game.title)
        }
        somethingFocused("after the player")

        // Home by the rail: its Up next card for the game, the first focus.
        openByTheRail("Home")
        waitFor(tvHome)
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { remote.isFocused(inTvHome and hasText(game.title)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on Home's card for the game; it is on \"${remote.focused()}\"", e)
        }
        assertEquals("canary values on Home", emptyList<String>(), compose.canaries())

        // Settings › Plugins by the rail. The Settings tab comes back where Open Sports left it, Settings › Plugins with
        // the install's line, until that page is left: Back from it to the row, and OK there opens it afresh.
        openByTheRail("Settings")
        compose.waitUntil(PLUGIN_WAIT_MS) { exists(hasText(PluginsPage.INTRO)) || "Usenet providers" in remote.focused() }
        if (exists(hasText(PluginsPage.INTRO))) {
            somethingFocused("on the page it left")
            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(INSTALLED_ROW)
        } else {
            assertTrue("the hub's row", INSTALLED_ROW in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Plugins"))
        }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Version ${SportarrBuild.versionName} · updates by itself"))
        // Its card's Settings, never the rail's item of the same name.
        waitForFocusOn(button("Settings"), "its card's Settings")

        // Remove: its question, Cancel first; then Remove, the card's Install focused.
        assertEquals("Right from Settings", "Remove", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(PluginsPage.removeQuestion(SportarrBuild.NAME)))
        remote.waitForFocus("Cancel")
        // Past the question's guard, which lets by an OK just after the one that asked.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        assertEquals("Right from Cancel", "Remove", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PLUGIN_WAIT_MS) { plugins.app.plugins.value.orEmpty().isEmpty() }
        remote.waitForFocus("Install", PLUGIN_WAIT_MS)
        assertFalse("its code and data gone", File(plugins.root, SPORTARR).exists())
        assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SPORTARR).exists())
        assertFalse("its address gone", stored("url"))
        assertFalse("its key gone", stored("key"))
        val watched = sportarr.state(game.part())
        assertNotNull("the game's watch row after Remove", watched)
        assertFalse("taken back", watched!!.deleted)
        assertEquals("where it stopped", kept.positionMs, watched.positionMs)

        // Back on Settings, its Plugins row focused, its line again "None yet": the rail without Sports.
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Plugins | None yet")
        assertFalse("its rail entry gone", exists(railItem(SPORTS)))
    }

    /**
     * (mock) Installed from the list and set up by remote, UFC 999's page by OK on its row: its bouts in the right pane
     * under their parts as the fake Wikipedia's card has them, read once with NZStreamer's User-Agent (W2) through the
     * app's own wiring; Down to a bout, and OK on it plays its part as the part's row does (W5): the part's play, a release
     * the plugin accepts for it, by the card's title and the part, the focus back on the bout (L-B10).
     */
    @Test
    fun theRealPluginFromAListShowsUfc999sBoutsAndOkOnOnePlaysItsPart() {
        sportarr.assumeWikipedia()
        val card = library.event(UFC_999)
        val prelims = card.part("prelims")
        sportarr.forget(prelims)
        server.list(Listed(SportarrBuild.listing, SportarrBuild.versionName))
        showTheApp()
        installFromTheListByRemote()
        // Nothing on the screen says that no request is coming: a moment for one to reach the fake, were it made.
        SystemClock.sleep(QUIET_MS)
        assertEquals("requests for the list", 0, sportarr.wikiRequests().size)

        // Down to UFC 999's row in its day, never Up next's; OK, its page, its top button focused, its bouts shown.
        val rowOf = Regex("""\d\d:\d\d \| ${Regex.escape(card.title)}( \|.*)?""")
        var row = remote.focused()
        repeat(MAX_DOWNS) { if (!rowOf.matches(row)) row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("UFC 999's row: \"$row\"", rowOf.matches(row))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(card.title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        remote.waitForFocus(TOP_BUTTON)
        waitFor(hasText(MAIN_EVENT))
        val asked = sportarr.wikiRequests()
        assertEquals("one request: $asked", listOf("/wiki/UFC_999"), asked.map { it.getValue("path").jsonPrimitive.content })
        val agent = asked.single().getValue("userAgent").jsonPrimitive.content
        assertTrue("NZStreamer's User-Agent: $agent", USER_AGENT.matches(agent))
        // Past the page's OK guard, which takes an OK in its first moment for the one that opened it.
        SystemClock.sleep(OkGuard.WINDOW_MS)

        // The right pane from its first row, Down part by bout, as the fake's card runs.
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the right pane's first row", UFC_999_ORDER.first(), remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, UFC_999_ORDER.first(), max = 20))
        UFC_999_ORDER.drop(1).forEach { next -> assertEquals("Down to $next", next, remote.press(KeyEvent.KEYCODE_DPAD_DOWN)) }
        val tapped = UFC_999_ORDER.last()
        assertEquals("canary values on the page", emptyList<String>(), compose.canaries())

        // (mock) OK on the bout: its part's play, as the part's row plays it; the focus back on the bout.
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val launch = sportarr.launchOf(prelims)
        val accepted = library.found(UFC_999, "prelims", "accept").map { it.first.name }
        assertTrue("a release the plugin accepts for the prelims: ${launch.release.title}", launch.release.title in accepted)
        assertEquals("the part's play, by the card's title and the part", "${card.title} · Prelims", launch.watch.title)
        container.playback.cancel()
        compose.waitUntil(PLUGIN_WAIT_MS) { container.playback.state.value == PlayState.Idle }
        remote.waitForFocus(tapped, PLUGIN_WAIT_MS)
    }

    /**
     * Settings › Plugins by the rail: Sportarr listed, its Install focused; Install to its end, Open Sports focused, nothing
     * of the debug build's own Sportarr stored; Open Sports, the setup with the focus on the address and Sports on the rail
     * after Discover; the address and the key typed as with the remote; Test against the fake, Save focused; Save, the
     * list, All focused.
     */
    private fun installFromTheListByRemote() {
        val size = SportarrBuild.bytes().size
        val activity = compose.activity

        // Settings › Plugins by the rail: Sportarr listed, its Install focused.
        openPlugins("Plugins | None yet")
        remote.waitForFocus("Install", PLUGIN_WAIT_MS)
        assertTrue("its name", exists(hasText(SportarrBuild.NAME)))
        assertTrue("its summary", exists(hasText(SportarrBuild.SUMMARY)))
        assertTrue("its version and size", exists(hasText("Version ${SportarrBuild.versionName} · ${megabytes(size)} MB")))

        // Install to its end: Open Sports focused, nothing of the debug build's own Sportarr stored.
        check(!PluginDevSecrets.seeding) { "The runner left the plugins' dev seeding on: an install would store local.properties' values" }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Open $SPORTS", PLUGIN_WAIT_MS)
        assertTrue(exists(hasText("Installed. $SPORTS is now a tab after ${pluginTabsFollow(FormFactor.Tv)}.")))
        val installed = listed("running") { it.status is PluginStatus.Running }
        assertEquals("the listed version", SportarrBuild.versionCode to SportarrBuild.versionName, installed.versionCode to installed.versionName)
        assertSame("the same activity", activity, compose.activity)
        assertFalse("an address or a key stored by the install", stored("url") || stored("key"))

        // Open Sports: the setup, the focus on the address; Sports on the rail after Discover.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForFocusOn(fieldGate(ADDRESS), "the address")
        assertEquals("the rail", listOf("Search", "Home", "Discover", SPORTS, "Collection", "Settings"), railLabels())

        // Typed as with the remote: OK opens the address, the keyboard's Next goes on to the key, Done closes it.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(field(ADDRESS) and isFocused())
        compose.onNode(field(ADDRESS)).performTextReplacement(SportsFakes.URL)
        compose.onNode(field(ADDRESS)).performImeAction()
        waitFor(field(KEY) and isFocused())
        compose.onNode(field(KEY)).performTextReplacement(SportsFakes.KEY)
        compose.onNode(field(KEY)).performImeAction()
        waitForFocusOn(fieldGate(KEY), "the key's gate, after Done")

        // Test against the fake: Save focused; Save: the list, All focused.
        assertEquals("Down from the key", TEST, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Connected · Sportarr ${sportarr.version()}"))
        waitForFocusOn(button(SAVE), "Save, after a passed Test")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(TODAY), LIST_WAIT_MS)
        waitForFocusOn(chip(ALL), "All")
        assertTrue("its address and key stored", stored("url") && stored("key"))
        assertEquals("canary values on the list", emptyList<String>(), compose.canaries())
    }

    /** The list's size in MB with one decimal, as the Plugins page's card gives it. */
    private fun megabytes(size: Int) = String.format(Locale.ROOT, "%.1f", size / 1_000_000.0)

    /** From the content to the rail, then along it to [label]'s item, and OK. */
    private fun openByTheRail(label: String) {
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(tabRole, "the rail, after Left")
        val rail = railLabels()
        val at = rail.indexOf(remote.focused())
        val to = rail.indexOf(label)
        assertTrue("$label on the rail $rail", to >= 0)
        repeat(kotlin.math.abs(to - at)) { remote.press(if (to > at) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        waitForFocusOn(railItem(label), "the rail's $label")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Settings › Plugins by remote: the rail's Settings, then down to its hub row ([row] its line), and OK. */
    private fun openPlugins(row: String) {
        openByTheRail("Settings")
        remote.waitForFocus("Usenet providers", PLUGIN_WAIT_MS)
        assertTrue("the hub's row", row in remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Plugins"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(PluginsPage.INTRO))
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

    /** Something focused, after every step. */
    private fun somethingFocused(step: String) = assertNotEquals("nothing focused $step", "(nothing)", remote.focused())

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The rail's items, top to bottom, by their tabs' names. */
    private fun railLabels(): List<String> = compose.onAllNodes(tabRole).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }.sortedBy { it.boundsInRoot.top }.map { it.label() }

    /** The text field behind [label]'s gate. */
    private fun field(label: String): SemanticsMatcher = hasSetTextAction() and SemanticsMatcher("behind the gate $label") { node ->
        generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(FieldGateLabel) == label }
    }

    private fun chip(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun button(words: String) = hasText(words) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "never shown: ${matcher.description}; focus on \"${remote.focused()}\", the registry lists ${describe(plugins.app.plugins.value)}; " +
                    "shown: ${compose.screenWords()}",
                e,
            )
        }
    }

    private fun waitForFocusOn(matcher: SemanticsMatcher, what: String) {
        try {
            compose.waitUntil(FOCUS_WAIT_MS) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"; shown: ${compose.screenWords()}", e)
        }
    }

    private companion object {
        /** Lantern Bay FC's Premier League game, aired yesterday at the fake's anchor, with releases the plugin accepts. */
        const val GAME = 1001

        /** The library's UFC 999, yesterday, whose card the fake Wikipedia has (L-B12). */
        const val UFC_999 = 1901
        const val TOP_BUTTON = "Play Early Prelims"
        const val MAIN_EVENT = "Quessel vs. Halde"

        /** UFC 999's right pane as the remote reads it, from its first row down to the bout OK is pressed on. */
        val UFC_999_ORDER = listOf(
            "Early Prelims",
            "Ambrel vs. Savrin | Flyweight",
            "Durnay vs. Wexmoor | Women's Strawweight",
            "Kjelsen vs. Tollerup | Welterweight",
            "Prelims",
            "Ferlund vs. Tarnoy | Bantamweight",
            "Grisholm vs. Pellicot | Light Heavyweight",
        )

        /** NZStreamer's User-Agent on a plugin's requests, its contact the public repository (W2). */
        val USER_AGENT = Regex("""NZStreamer/\d+\.\d+\.\d+ \(https://github\.com/skjaere/nzstreamer\)""")

        /** How long a request the list mustn't make gets to reach the fake. */
        const val QUIET_MS = 1_500L

        const val SPORTS = "Sports"
        const val TODAY = "TODAY"
        const val ALL = "All"
        const val ADDRESS = "Sportarr address"
        const val KEY = "API key"
        const val TEST = "Test"
        const val SAVE = "Save"
        const val PLAY = "Play"

        /** Settings' Plugins row with Sportarr installed. */
        const val INSTALLED_ROW = "Plugins | Sportarr"

        const val LIST_WAIT_MS = 30_000L
        const val FOCUS_WAIT_MS = 8_000L

        /** Presses of Down from the title line to any row of the list's first days. */
        const val MAX_DOWNS = 40
    }
}

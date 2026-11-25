package io.skjaere.usenet.app.plugins.sportarr

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * The Sports tab's setup on a phone (L640, L652), the real plugin over the fake Sportarr: the tab's first
 * screen, nothing sent before Test; Test's answers as the fake's switches give them (connected, the key refused, a
 * Sportarr too old, a page that isn't Sportarr's, a port nobody answers on); the warning for plain http to a public
 * address, typed and never sent; Save, after which the tab shows the list; the settings page from the list's ⋮ and from
 * Settings › Plugins' card, its key "Saved" with Change, and after Change "Paste the new key", Test and Save waiting for
 * a key; and the form whole at 1.3 times the text size.
 *
 * Nothing but loopback and the emulator's host (10.0.2.2) is ever tested or saved; the public address is typed and its
 * line read, Test and Save never pressed on it. The fake's switches are put back, and the plugin with its files and
 * secrets removed, after each test. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class SportarrSetupDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrSetupDeviceTest")
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        assumeFalse("a TV: its setup is SportarrTvTest's", container.television)
        sportarr.assumeUp()
        home.show()
        tmdb = FakeTmdb(strict = true)
        sportarr.hold()
    }

    @After
    fun restore() {
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        sportarr.restore()
        home.restore()
    }

    /** The app at [fontScale] times the text size, its shell and graph, with the Sports tab opened. */
    private fun openSports(fontScale: Float = 1f) {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    nav = rememberNavController()
                    AppNav(nav = nav)
                }
            }
        }
        waitFor(barTab(SPORTS))
        compose.onNode(barTab(SPORTS)).performClick()
    }

    /** The tab's first screen: its title, intro and fields, Test and Save waiting for an address and a key, nothing sent. */
    @Test
    fun theTabsFirstScreenSendsNothingUntilTest() {
        sportarr.install(connected = false)
        val before = asked()
        openSports()
        waitFor(hasText(INTRO))
        listOf(ADDRESS, KEY, ADDRESS_HELP, KEY_HELP, READ_ONLY).forEach { words -> assertTrue("shown: $words", exists(hasText(words))) }
        assertTrue("the address's placeholder", exists(hasText(PLACEHOLDER)))
        compose.onNode(button(TEST)).assertIsNotEnabled()
        compose.onNode(button(SAVE)).assertIsNotEnabled()
        type(addressField, SportsFakes.URL)
        compose.onNode(button(TEST)).assertIsNotEnabled()
        type(keyField, SportsFakes.KEY)
        compose.onNode(button(TEST)).assertIsEnabled()
        compose.onNode(button(SAVE)).assertIsEnabled()
        assertEquals("asked of the fake before Test", before, asked())
        assertFalse("an address stored before Save", stored("url"))
    }

    /**
     * Test against the fake, each answer as a switch gives it: connected with Sportarr's four-part version; the key refused;
     * a Sportarr too old; a page in Sportarr's place; and a port nobody answers on, which names this phone. Nothing is
     * stored by Test.
     */
    @Test
    fun testSaysWhatTheFakeAnswers() {
        sportarr.install(connected = false)
        openSports()
        waitFor(hasText(INTRO))
        type(addressField, SportsFakes.URL)
        type(keyField, SportsFakes.KEY)

        val version = sportarr.version()
        assertEquals("the fake's version, as a release build's: $version", 4, version.split('.').size)
        test()
        waitFor(hasText("Connected · Sportarr $version"))

        sportarr.switch("refuse-key")
        test()
        waitFor(hasText(REFUSED))
        sportarr.off("refuse-key")

        sportarr.switch("version", "v=$OLD")
        test()
        waitFor(hasText("This Sportarr is $OLD. Sports needs 4.1.1 or newer: update Sportarr, then Test again."))
        sportarr.off("version")

        sportarr.switch("html")
        test()
        waitFor(hasText(NOT_SPORTARR))
        sportarr.off("html")

        // A loopback port of this test's own, closed: nobody answers there.
        val closed = ServerSocket(0).use { it.localPort }
        type(addressField, "http://127.0.0.1:$closed")
        test()
        waitFor(hasText("Couldn't reach 127.0.0.1:$closed. Check that Sportarr is running and that this phone is on its network."))
        assertFalse("stored by Test", stored("url") || stored("key"))
    }

    /**
     * L640: plain http to a public address shows the warning under the field, as typed; neither Test nor Save is pressed, so
     * nothing goes there. TEST-NET-3's address, which names no host.
     */
    @Test
    fun aPublicAddressShowsTheWarningAsTyped() {
        sportarr.install(connected = false)
        openSports()
        waitFor(hasText(INTRO))
        type(addressField, SportsFakes.URL)
        waitFor(hasText(LOCAL_HTTP))
        type(addressField, PUBLIC)
        waitFor(hasText(PUBLIC_HTTP))
        assertFalse("the local line with the warning", exists(hasText(LOCAL_HTTP)))
        assertFalse("stored", stored("url"))
    }

    /** Save stores the address and the key, and the tab shows the list, read at once (L641). */
    @Test
    fun saveShowsTheList() {
        sportarr.install(connected = false)
        openSports()
        waitFor(hasText(INTRO))
        type(addressField, SportsFakes.URL)
        type(keyField, SportsFakes.KEY)
        compose.onNode(button(SAVE)).performScrollTo().performClick()
        waitFor(sideTab(RECENT))
        waitFor(hasText(sportarr.catalogue.event(GAME).title), LIST_WAIT_MS)
        assertTrue("its address and key stored", stored("url") && stored("key"))
        assertFalse("the form still shown", exists(hasText(INTRO)))
    }

    /**
     * Sportarr settings from the list's ⋮ (L652): "Sportarr settings" with its arrow, the address as stored and the key
     * "Saved" with Change; Change empties it, "Paste the new key", Test and Save waiting for one; Back to the list.
     */
    @Test
    fun theListsMenuOpensItsSettingsWithTheKeySaved() {
        sportarr.install()
        openSports()
        waitFor(sideTab(RECENT))
        compose.onNode(hasContentDescription(MORE) and hasClickAction()).performClick()
        compose.onNode(hasText(SETTINGS) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        waitFor(hasText(SAVED))
        assertTrue("its title", exists(hasText(SETTINGS)))
        assertFalse("the tab's intro on its settings page", exists(hasText(INTRO)))
        assertEquals("the address as stored", SportsFakes.URL, field(addressField).config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertFalse("a key field with Saved", exists(keyField))
        compose.onNode(button(TEST)).assertIsEnabled()

        compose.onNode(button(CHANGE)).performClick()
        waitFor(hasText(PASTE))
        assertFalse("Saved after Change", exists(hasText(SAVED)))
        compose.onNode(button(TEST)).assertIsNotEnabled()
        compose.onNode(button(SAVE)).assertIsNotEnabled()

        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(sideTab(RECENT))
        assertTrue("the stored key kept", stored("key"))
    }

    /** Its card's Settings in Settings › Plugins opens the same form; its Back goes back to the Plugins page. */
    @Test
    fun itsCardInPluginsOpensTheSameForm() {
        sportarr.install()
        openSports()
        waitFor(barTab("Home"))
        compose.onNode(barTab("Home")).performClick()
        waitFor(hasContentDescription("Settings") and hasClickAction())
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        compose.onNode(button("Plugins")).performScrollTo().performClick()
        waitFor(hasText(PluginsPage.INTRO))
        compose.onNode(button("Settings")).performScrollTo().performClick()
        waitFor(hasText(SAVED))
        assertTrue("its title", exists(hasText(SETTINGS)))
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(hasText(PluginsPage.INTRO))
    }

    /** At 1.3 times the text size, the form's words are whole, none cut or off the screen's side, and Test's answer too. */
    @Test
    fun theFormAtLargeTextIsWhole() {
        sportarr.install(connected = false)
        openSports(fontScale = 1.3f)
        waitFor(hasText(INTRO))
        type(addressField, SportsFakes.URL)
        type(keyField, SportsFakes.KEY)
        test()
        waitFor(hasText("Connected · Sportarr ${sportarr.version()}"))
        compose.onNode(hasText(READ_ONLY)).performScrollTo()
        assertEquals("at 1.3x", emptyList<String>(), cutTexts())
    }

    private fun test() {
        compose.onNode(button(TEST)).performScrollTo().performClick()
    }

    /** The fake's requests since its last reset, by route: none before Test or Save. */
    private fun asked(): Map<String, Int> = sportarr.counts().getValue("routes").jsonObject.mapValues { it.value.jsonPrimitive.int }

    private fun stored(name: String) = runBlocking { container.data.secrets.has("plugin:$SPORTARR:$name").first() }

    private fun type(field: SemanticsMatcher, words: String) {
        compose.onNode(field).performScrollTo().performTextReplacement(words)
        compose.waitForIdle()
    }

    private fun field(matcher: SemanticsMatcher): SemanticsNode = compose.onNode(matcher).fetchSemanticsNode()

    /** Each text whose words are cut, laid out taller than its room or ended in an ellipsis, or off the screen's side. */
    private fun cutTexts(): List<String> {
        val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
        val problems = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val layout = node.textLayout()
            if (words != null && layout != null) {
                if (layout.size.height < layout.multiParagraph.height - 1) problems += "\"$words\" clipped"
                if (layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)) problems += "\"$words\" ends in an ellipsis"
                if (node.boundsInRoot.right > screen.right + 1 || node.boundsInRoot.left < screen.left - 1) problems += "\"$words\" off the screen's side"
            }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return problems
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the bar") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    /** Recent or Upcoming in the list's title line. */
    private fun sideTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** The key's field, read by its label and that it is hidden; the address's, the form's other field. */
    private val keyField = hasSetTextAction() and hasContentDescription("$KEY, hidden")
    private val addressField = hasSetTextAction() and SemanticsMatcher("not the key") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}; shown: ${compose.screenWords()}", e)
        }
    }

    private companion object {
        /** Lantern Bay FC's Premier League game, aired yesterday at the fake's anchor. */
        const val GAME = 1001

        const val SPORTS = "Sports"
        const val RECENT = "Recent"
        const val MORE = "More"
        const val SETTINGS = "Sportarr settings"
        const val INTRO =
            "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."
        const val ADDRESS = "Sportarr address"
        const val PLACEHOLDER = "http://sportarr.home:1867"
        const val ADDRESS_HELP = "Where you open Sportarr in a browser, with its port."
        const val LOCAL_HTTP = "Plain http on your local network."
        const val PUBLIC_HTTP =
            "⚠ Not encrypted: your API key would be readable on its way over the internet. Use https:// if your Sportarr has it."
        const val KEY = "API key"
        const val KEY_HELP = "In Sportarr: Settings › General › Security. Stored encrypted on this device."
        const val SAVED = "Saved"
        const val CHANGE = "Change"
        const val PASTE = "Paste the new key"
        const val TEST = "Test"
        const val SAVE = "Save"
        const val READ_ONLY =
            "Sports only reads from Sportarr. It never changes, searches or downloads anything there. To change what shows here, change it in Sportarr."
        const val REFUSED = "Sportarr refused this API key. Copy it again from Settings › General › Security in Sportarr."
        const val NOT_SPORTARR = "This address answers, but it isn't Sportarr."
        const val OLD = "4.0.998"

        /** TEST-NET-3 (RFC 5737): plain http to a public address, typed alone. */
        const val PUBLIC = "http://203.0.113.7:1867"

        /** The list's first read against the fake. */
        const val LIST_WAIT_MS = 30_000L
    }
}

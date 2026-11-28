package io.skjaere.usenet.app.plugins.sportarr

import android.content.res.Configuration
import android.os.SystemClock
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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import io.skjaere.usenet.app.plugins.PluginPage1
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
import java.io.File
import java.net.ServerSocket

/**
 * The Sports tab's setup on a phone (L640, L652), the real plugin over the fake Sportarr: the tab's first
 * screen, nothing sent before Test; Test's answers as the fake's switches give them (connected, the key refused, a
 * Sportarr too old, a page that isn't Sportarr's, a port nobody answers on); the warning for plain http to a public
 * address, typed and never sent; Save, after which the tab shows the list; the settings page from the list's ⋮ and from
 * Settings › Plugins' card, its key "Saved" with Change, and after Change "Paste the new key", Test and Save waiting for
 * a key; and the form whole at 1.3 times the text size. The settings page alone has the Fight cards from Wikipedia switch
 * (L-B8, BtPhoneSettings), on at first: switched off, the kept cards go and a UFC card's page says so, its Sportarr
 * settings opening the switch; on again, the page reads its card as it shows.
 *
 * Nothing but loopback and the emulator's host (10.0.2.2) is ever tested or saved; the public address is typed and its
 * line read, Test and Save never pressed on it. The fake's switches are put back, and the plugin with its files and
 * secrets removed (the fight cards switch's file with them), after each test. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
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
        assertFalse("the fight cards switch on the first screen", exists(hasText(FIGHT_CARDS)))
        assertFalse("its part on the first screen", exists(hasText(EVENT_PAGES)))
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
        openSettings()
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

    /**
     * BtPhoneSettings: Sportarr settings' Event pages part under the reworded footnote, its Fight cards from Wikipedia switch
     * on at first with its detail, nothing written for it until it is switched; whole at 1.3 times the text size.
     */
    @Test
    fun theSettingsPageHoldsTheFightCardsSwitchOnAtFirst() {
        sportarr.install()
        openSports(fontScale = 1.3f)
        waitFor(sideTab(RECENT))
        openSettings()
        waitFor(cardsSwitch)
        compose.onNode(cardsSwitch).performScrollTo().assertIsOn()
        assertTrue("its detail", exists(cardsSwitch and hasText(FIGHT_CARDS_DETAIL)))
        val footnote = top(hasText(READ_ONLY))
        val part = top(hasText(EVENT_PAGES))
        assertTrue("the part under the footnote, the switch under the part", footnote < part && part < top(cardsSwitch))
        assertFalse("its file before it is switched", options().exists())
        assertEquals("at 1.3x", emptyList<String>(), cutTexts())
    }

    /**
     * Switched off on settings (L-B8): kept at once, the kept fight cards deleted, and a UFC card's page then says so with
     * Sportarr settings, today's footnote and neither bouts nor credit; nothing more is asked of Wikipedia.
     */
    @Test
    fun switchedOffTheKeptCardsGoAndACardsPageSaysSo() {
        sportarr.assumeWikipedia()
        sportarr.install()
        openSports()
        waitFor(sideTab(RECENT))
        openEvent(UFC_999)
        waitFor(hasText(MAIN_EVENT))
        val cards = File(sportarr.dataDir, CARDS_FILE)
        assertTrue("its card kept", cards.exists())
        compose.runOnIdle { nav.popBackStack() }
        waitFor(sideTab(RECENT))

        openSettings()
        switchCards(on = false)
        compose.waitUntil(PLUGIN_WAIT_MS) { !cards.exists() && switchedOn() == false }
        back()
        waitFor(sideTab(RECENT))
        openEvent(UFC_999)
        waitFor(hasText(OFF))
        assertTrue("Sportarr settings beside it", exists(button(SETTINGS)))
        assertFalse("bouts", exists(hasText(MAIN_EVENT)))
        assertFalse("the credit", exists(hasText(CREDIT)))
        scrollTo(hasText(CARD_FOOTNOTE))
        assertEquals("asked of Wikipedia: the first opening alone", 1, sportarr.wikiRequests().size)
        assertFalse("a card kept since", cards.exists())
    }

    /**
     * Off's Sportarr settings opens the settings page with the switch off; switched on there, Back to the card's page reads
     * its card as the page shows again, one request, and the bouts take the line's place.
     */
    @Test
    fun offsSportarrSettingsOpensTheSwitchAndOnAgainReadsTheCard() {
        sportarr.assumeWikipedia()
        sportarr.install()
        openSports()
        waitFor(sideTab(RECENT))
        openSettings()
        switchCards(on = false)
        back()
        waitFor(sideTab(RECENT))
        openEvent(UFC_999)
        waitFor(hasText(OFF))
        assertEquals("asked of Wikipedia while off", 0, sportarr.wikiRequests().size)

        compose.onNode(button(SETTINGS)).performScrollTo().performClick()
        waitFor(hasText(SAVED))
        assertTrue("Sportarr settings", exists(hasText(SETTINGS)))
        compose.onNode(cardsSwitch).performScrollTo().assertIsOff()
        switchCards(on = true)
        compose.waitUntil(PLUGIN_WAIT_MS) { switchedOn() == true }
        back()
        waitFor(hasText(MAIN_EVENT))
        assertFalse("Off's line", exists(hasText(OFF)))
        val asked = sportarr.wikiRequests().map { it["path"]?.jsonPrimitive?.content }
        assertEquals("read once, as the page showed again", listOf("/wiki/UFC_999"), asked)
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

    /** Sportarr settings from the list's ⋮, its key Saved. */
    private fun openSettings() {
        compose.onNode(hasContentDescription(MORE) and hasClickAction()).performClick()
        compose.onNode(hasText(SETTINGS) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        waitFor(hasText(SAVED))
    }

    /** The plugin's page of the event [id], as its row opens it. */
    private fun openEvent(id: Int) {
        // The catalogue first, off the main thread: its first use asks the fake for its clock.
        val key = sportarr.catalogue.event(id).key
        compose.runOnIdle { nav.navigate(PluginPage1(EVENT, key)) }
    }

    /** The page's arrow back. */
    private fun back() {
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
    }

    /** The fight cards switch tapped [on] or off, as it then shows. */
    private fun switchCards(on: Boolean) {
        compose.onNode(cardsSwitch).performScrollTo().performClick()
        waitFor(cardsSwitch and if (on) isOn() else isOff())
    }

    /** The plugin's file for the switch (L-B8), in its own folder. */
    private fun options() = File(sportarr.dataDir, OPTIONS_FILE)

    /** Whether the switch's file says on; null while there is none. */
    private fun switchedOn(): Boolean? = options().takeIf { it.exists() }?.let { file ->
        Json.parseToJsonElement(file.readText()).jsonObject["fightCards"]?.jsonPrimitive?.boolean
    }

    /** The fight cards switch on settings: its row, read with its detail and whether it is on. */
    private val cardsSwitch = hasText(FIGHT_CARDS) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun top(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher).fetchSemanticsNodes().single().positionInRoot.y

    /** A card page's [matcher] brought into view, its list scrolled to it. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown: ${matcher.description}; shown: ${compose.screenWords()}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
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
            "Sports never changes, searches or downloads anything in Sportarr; it only reads from it. To change what shows here, change it in Sportarr."
        const val EVENT_PAGES = "EVENT PAGES"
        const val FIGHT_CARDS = "Fight cards from Wikipedia"
        const val FIGHT_CARDS_DETAIL =
            "Reads a UFC event's fight card from Wikipedia when you open the event. Wikipedia sees which events you open."
        const val REFUSED = "Sportarr refused this API key. Copy it again from Settings › General › Security in Sportarr."
        const val NOT_SPORTARR = "This address answers, but it isn't Sportarr."
        const val OLD = "4.0.998"

        /** The plugin's pages (L652), and the library's UFC 999, yesterday (the fake Wikipedia's card). */
        const val EVENT = "event"
        const val UFC_999 = 1901
        const val MAIN_EVENT = "Quessel vs. Halde"
        const val CREDIT = "Fight card from Wikipedia"
        const val OFF = "Fight cards from Wikipedia are off."
        const val CARD_FOOTNOTE = "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."

        /** The plugin's kept fight cards and its switch, in its own folder (L-B5, L-B8). */
        const val CARDS_FILE = "cards.json"
        const val OPTIONS_FILE = "options.json"

        /** TEST-NET-3 (RFC 5737): plain http to a public address, typed alone. */
        const val PUBLIC = "http://203.0.113.7:1867"

        /** The list's first read against the fake. */
        const val LIST_WAIT_MS = 30_000L
    }
}

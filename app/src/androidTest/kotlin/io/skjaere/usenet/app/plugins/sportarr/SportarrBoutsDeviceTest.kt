package io.skjaere.usenet.app.plugins.sportarr

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginPage1
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A UFC card's bouts on a phone (L-B7, W4, W5, W6), the real plugin over the fake Sportarr and the fake Wikipedia:
 * UFC 999's bouts under their parts in the order fought, each pair's names, weight class and labels as BtPhoneCard
 * draws them, nothing of a result anywhere on the page; a bout's row says it plays its part, and a tap plays it; the credit
 * and its sheet (View on Wikipedia shown, never tapped: a tap would leave the app for a browser), open while the credit's
 * row leaves the list and never opening by itself; one request as the page first opens, none for the list or the page
 * again, sent with NZStreamer's User-Agent; the card read before the event and after it, the same rows; an upcoming fight
 * night's bouts, its early prelims' bout under Prelims, none of them tappable; and the lines of BtPhoneCardStates:
 * Reading, not out yet, no card (a page missing, and one with no card), and can't reach with Try again.
 *
 * The fake's switches are put back after each test, the plugin with its files and secrets removed; each test installs it
 * anew, so nothing kept answers for the one before. TMDB is a strict fake: what Home asks of other titles is refused.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SportarrBoutsDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrBoutsDeviceTest")
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        assumeFalse("a TV: its bouts are SportarrTvBoutsTest's", container.television)
        sportarr.assumeUp()
        home.show()
        tmdb = FakeTmdb(strict = true)
        sportarr.hold()
        sportarr.assumeWikipedia()
    }

    @After
    fun restore() {
        container.playback.cancel()
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        sportarr.restore()
        home.restore()
    }

    private val library get() = sportarr.catalogue

    /** The app, then the Sports tab's list, read and shown. */
    private fun openList() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        waitFor(barTab(SPORTS))
        compose.onNode(barTab(SPORTS)).performClick()
        waitFor(hasText(TODAY), LIST_WAIT_MS)
    }

    /** The plugin's page of the event [id], as its row opens it. */
    private fun openEvent(id: Int) {
        // The catalogue first, off the main thread: its first use asks the fake for its clock.
        val key = library.event(id).key
        compose.runOnIdle { nav.navigate(PluginPage1(EVENT, key)) }
    }

    /**
     * UFC 999 after its start, the fake's results form: its bouts under their parts as BtPhoneCard draws them, each pair as
     * the reader orders it (the main event as the title names it), each saying it plays its part; the credit and the bouts'
     * footnote; nothing of a result on the page. One request as the page first opens, with NZStreamer's User-Agent; none
     * for the list before it, or for the page opened again.
     */
    @Test
    fun ufc999sBoutsShowUnderTheirPartsWithNoResult() {
        sportarr.install()
        openList()
        assertEquals("requests for the list", emptyList<JsonObject>(), sportarr.wikiRequests())
        openEvent(UFC_999)
        waitFor(bout(MAIN_EVENT))
        assertBoards()
        assertNoResult()
        scrollTo(hasText(CREDIT) and hasClickAction())
        scrollTo(hasText(BOUTS_FOOTNOTE))
        assertEquals("canary values", emptyList<String>(), compose.canaries())

        val asked = sportarr.wikiRequests()
        assertEquals("one request as the page opened: $asked", listOf("/wiki/UFC_999"), asked.map { it.string("path") })
        assertEquals("answered in the fake's results form", "results", asked.single().string("form"))
        assertTrue("NZStreamer's User-Agent: ${asked.single().string("userAgent")}", USER_AGENT.matches(asked.single().string("userAgent").orEmpty()))

        // Back, then the page again: its card kept shows at once, and nothing more is asked.
        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText(TODAY), LIST_WAIT_MS)
        openEvent(UFC_999)
        waitFor(bout(MAIN_EVENT))
        assertEquals("requests after the page again", 1, sportarr.wikiRequests().size)
    }

    /** The card as the fake Wikipedia has it before the event: the same bouts, the same rows. */
    @Test
    fun thePreEventFormShowsTheBoardsRows() = theFormShowsTheBoardsRows("pre")

    /** The card after the event, its winners moved left with "def.": the same bouts, the same rows. */
    @Test
    fun theResultsFormShowsTheBoardsRows() = theFormShowsTheBoardsRows("results")

    private fun theFormShowsTheBoardsRows(form: String) {
        sportarr.switch("wiki-form", "event=$UFC_999&form=$form")
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(bout(MAIN_EVENT))
        assertBoards()
        assertNoResult()
        val asked = sportarr.wikiRequests().filter { it.string("path") == "/wiki/UFC_999" }
        assertEquals("UFC 999 asked once, in the $form form: $asked", listOf(form), asked.map { it.string("form") })
    }

    /** The credit's sheet (BtPhoneCredit): the attribution, what is left out, the warning, View on Wikipedia and Close. */
    @Test
    fun theCreditOpensItsSheet() {
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(bout(MAIN_EVENT))
        scrollTo(hasText(CREDIT) and hasClickAction())
        compose.onNode(hasText(CREDIT) and hasClickAction()).performClick()
        val inSheet = hasAnyAncestor(isDialog())
        waitFor(hasText("About the bouts") and inSheet)
        assertTrue("the attribution", exists(hasText("Fight card: Wikipedia, “UFC 999”, CC BY-SA 4.0") and inSheet))
        assertTrue(
            "what is left out",
            exists(hasText("Sports shows the bouts and leaves out the results: no winners, methods, rounds or times.") and inSheet),
        )
        assertTrue("the warning, read once", exists(hasText("⚠") and hasText("Wikipedia's page shows the results.") and inSheet))
        // Shown, never tapped: a tap would leave the app for a browser.
        assertTrue("View on Wikipedia", exists(hasText("View on Wikipedia") and hasClickAction() and inSheet))
        assertEquals("canary values, with the sheet", emptyList<String>(), compose.canaries())
        compose.onNode(hasText("Close") and hasClickAction() and inSheet).performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(hasText("About the bouts")) }
    }

    /**
     * The credit's sheet stays open while the credit's row leaves the page's list, as a page made again in landscape, or at
     * a larger text size, can leave it below the fold; closed, it never opens again by itself as the row comes back.
     */
    @Test
    fun theCreditsSheetOutlivesItsRowLeavingTheList() {
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(bout(MAIN_EVENT))
        val credit = hasText(CREDIT) and hasClickAction()
        scrollTo(credit)
        compose.onNode(credit).performClick()
        val sheet = hasText(ABOUT) and hasAnyAncestor(isDialog())
        waitFor(sheet)
        // The page's list back at its top under the sheet: the credit's row out of it.
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.waitForIdle()
        assertFalse("the credit's row out of the list", exists(credit))
        assertTrue("the sheet still open", exists(sheet))
        compose.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(hasText(ABOUT)) }
        scrollTo(credit)
        compose.waitForIdle()
        assertFalse("the sheet opened again by itself", exists(hasText(ABOUT)))
    }

    /**
     * (mock) A bout's tap plays its part, as the part's row does (W5): the play is the part's, its sheet titled for the part
     * (frame A), with a release the plugin accepts for the part.
     */
    @Test
    fun aBoutsTapPlaysItsPart() {
        sportarr.assumeUp(plays = true)
        sportarr.install()
        sportarr.indexers(plays = true)
        val card = library.event(UFC_999)
        sportarr.forget(card.part("prelims"))
        openList()
        openEvent(UFC_999)
        val tapped = bout("Grisholm vs. Pellicot")
        waitFor(tapped)
        assertEquals("what it plays, as TalkBack says it", "Play Prelims", node(tapped).config.getOrNull(SemanticsActions.OnClick)?.label)
        val titles = CopyOnWriteArrayList<String>()
        val watching = CoroutineScope(Dispatchers.Default).launch {
            container.playback.state.collect { state ->
                when (state) {
                    is PlayState.Searching -> titles += state.title
                    is PlayState.Running -> titles += state.title
                    else -> Unit
                }
            }
        }
        try {
            sportarr.paceSearches()
            compose.onNode(tapped).performScrollTo().performClick()
            val launch = sportarr.launchOf(card.part("prelims"))
            val accepted = library.found(UFC_999, "prelims", "accept").map { it.first.name }
            assertTrue("a release the plugin accepts for the prelims: ${launch.release.title}", launch.release.title in accepted)
            assertEquals("the part's play, by the card's title and the part", "${card.title} · Prelims", launch.watch.title)
            assertTrue("the sheet titled for the part: $titles", titles.isNotEmpty() && titles.all { it == "${card.title} · Prelims" })
        } finally {
            watching.cancel()
            container.playback.cancel()
        }
    }

    /**
     * The fight night six days on, its card out: its bouts under its parts in the not-aired colour, none tappable, its
     * early prelims' bout under Prelims, first; the credit and the bouts' footnote. Two requests: Sportarr's title, then
     * its number's page, which Wikipedia serves as a redirect.
     */
    @Test
    fun anUpcomingCardsBoutsShowAndCantBeTapped() {
        sportarr.install()
        openList()
        openEvent(FIGHT_NIGHT)
        waitFor(bout("Brisk vs. Loam"))
        val rows = FIGHT_NIGHT_ROWS.map { node(bout(it.first())) }
        val tops = rows.map { it.positionInRoot.y }
        assertEquals("in the order fought", tops.sorted(), tops)
        FIGHT_NIGHT_ROWS.zip(rows).forEach { (expected, row) ->
            assertEquals(expected.first(), expected, row.texts())
            assertFalse("${expected.first()} can't be tapped before the start", row.config.contains(SemanticsActions.OnClick))
        }
        val prelims = node(hasText("Prelims") and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).positionInRoot.y
        val main = node(hasText("Main Card") and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).positionInRoot.y
        assertTrue("the early prelims' bout under Prelims, first", tops.first() > prelims && tops.take(5).all { it < main } && tops.drop(5).all { it > main })
        scrollTo(hasText(CREDIT) and hasClickAction())
        scrollTo(hasText(BOUTS_FOOTNOTE))
        assertEquals("two requests: ${sportarr.wikiRequests()}", 2, sportarr.wikiRequests().size)
    }

    /** Reading (BtPhoneCardStates): while Wikipedia holds its answer, the line under the parts; then the bouts. */
    @Test
    fun readingThenTheBouts() {
        sportarr.switch("wiki-hold", "on=true")
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(hasText(READING))
        assertTrue("the parts play meanwhile", exists(hasText("Prelims") and hasClickAction()))
        assertTrue("today's footnote while none show", exists(hasText(CARD_FOOTNOTE)))
        sportarr.off("wiki-hold")
        waitFor(bout(MAIN_EVENT))
        assertFalse("the line gone", exists(hasText(READING)))
    }

    /** Not out yet: a card Wikipedia hasn't split into parts, with today's footnote. */
    @Test
    fun anUnsplitCardIsNotOutYet() = assertLine("unsplit", NOT_YET)

    /** No card found: no page for the event after its start. */
    @Test
    fun aMissingPageIsNoCardFound() = assertLine("missing", NO_CARD)

    /** No card found: the event's page with no card on it, after its start. */
    @Test
    fun aPageWithNoCardIsNoCardFound() = assertLine("bare", NO_CARD)

    private fun assertLine(form: String, line: String) {
        sportarr.switch("wiki-form", "event=$UFC_999&form=$form")
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(hasText(line))
        assertFalse("no bouts", exists(bout(MAIN_EVENT)))
        assertFalse("no credit", exists(hasText(CREDIT)))
        scrollTo(hasText(CARD_FOOTNOTE))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /**
     * Can't reach, with nothing kept: Try again. Pressed while the 503's hold lasts it asks nothing; once the hold has passed,
     * one request, and the bouts.
     */
    @Test
    fun cantReachThenTryAgain() {
        sportarr.switch("wiki-fail", "status=503")
        sportarr.install()
        openList()
        openEvent(UFC_999)
        waitFor(hasText(CANT_REACH))
        val failedAt = SystemClock.elapsedRealtime()
        assertEquals("one request, answered 503", listOf(503), sportarr.wikiRequests().map { it.string("status")?.toInt() })
        sportarr.off("wiki-fail")
        compose.onNode(hasText(TRY_AGAIN) and hasClickAction()).performScrollTo().performClick()
        waitFor(hasText(CANT_REACH))
        SystemClock.sleep(QUIET_MS)
        assertEquals("nothing asked while the hold lasts", 1, sportarr.wikiRequests().size)
        SystemClock.sleep((failedAt + HOLD_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        compose.onNode(hasText(TRY_AGAIN) and hasClickAction()).performScrollTo().performClick()
        waitFor(bout(MAIN_EVENT))
        assertBoards()
        assertEquals("one more request, answered", listOf(503, 200), sportarr.wikiRequests().map { it.string("status")?.toInt() })
    }

    /** UFC 999's rows as BtPhoneCard draws them: each part's bouts under it in order, their words, labels and what they play. */
    private fun assertBoards() {
        val parts = UFC_999_ROWS.keys.associateWith { label ->
            node(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasClickAction()).positionInRoot.y
        }
        var last = -1f
        UFC_999_ROWS.forEach { (part, rows) ->
            val partTop = parts.getValue(part)
            assertTrue("$part after the bouts before it", partTop > last)
            rows.forEach { expected ->
                val row = node(bout(expected.first()))
                assertEquals(expected.first(), expected, row.texts())
                assertEquals("${expected.first()} plays its part", "Play $part", row.config.getOrNull(SemanticsActions.OnClick)?.label)
                val top = row.positionInRoot.y
                assertTrue("${expected.first()} under $part, after the row before it", top > partTop && top > last)
                last = top
            }
        }
    }

    /** Nothing of a result anywhere on the page: no separator, method, round, time, note or bonus. */
    private fun assertNoResult() {
        val words = compose.screenWords(max = Int.MAX_VALUE)
        val shown = RESULT_WORDS.filter { it in words } + TIME.findAll(words).map { it.value }
        assertEquals("a result on the page: $words", emptyList<String>(), shown)
    }

    /** A bout's row by its names: the node that plays it, or shows it before the start. */
    private fun bout(names: String) = hasText(names) and SemanticsMatcher("a bout's row") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().firstOrNull()?.text == names
    }

    /** The one node [matcher] finds; its [SemanticsNode.positionInRoot] places it, as its bounds are cut at the screen's edge. */
    private fun node(matcher: SemanticsMatcher): SemanticsNode = compose.onAllNodes(matcher).fetchSemanticsNodes().single()

    /** A node's words, as TalkBack reads them, in order. */
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.takeUnless { it.content == "null" && !it.isString }?.content

    /** The page's [matcher] brought into view, its list scrolled to it. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown: ${matcher.description}; shown: ${compose.screenWords()}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
    }

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the bar") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}; shown: ${compose.screenWords()}", e)
        }
    }

    private companion object {
        /** The plugin's pages (L652). */
        const val EVENT = "event"

        /** The library's UFC 999, yesterday, and the fight night six days on (L-B12's two cards). */
        const val UFC_999 = 1901
        const val FIGHT_NIGHT = 1902

        const val SPORTS = "Sports"
        const val TODAY = "TODAY"
        const val MAIN_EVENT = "Quessel vs. Halde"
        const val CREDIT = "Fight card from Wikipedia"
        const val ABOUT = "About the bouts"
        const val READING = "Reading the fight card…"
        const val NOT_YET = "The fight card isn't on Wikipedia yet."
        const val NO_CARD = "No fight card found on Wikipedia for this event."
        const val CANT_REACH = "Couldn't reach Wikipedia for the fight card."
        const val TRY_AGAIN = "Try again"
        const val CARD_FOOTNOTE = "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val BOUTS_FOOTNOTE =
            "Tap a part, or a bout in it, to play the part's best release, or ⋮ to choose one yourself. Details from your Sportarr."

        /** NZStreamer's User-Agent on a plugin's requests, its contact the public repository (W2). */
        val USER_AGENT = Regex("""NZStreamer/\d+\.\d+\.\d+ \(https://github\.com/skjaere/nzstreamer\)""")

        /** UFC 999's rows as BtPhoneCard draws them (the fake's card), by part: names, weight class, labels. */
        val UFC_999_ROWS = linkedMapOf(
            "Early Prelims" to listOf(
                listOf("Ambrel vs. Savrin", "Flyweight"),
                listOf("Durnay vs. Wexmoor", "Women's Strawweight"),
                listOf("Kjelsen vs. Tollerup", "Welterweight"),
            ),
            "Prelims" to listOf(
                listOf("Ferlund vs. Tarnoy", "Bantamweight"),
                listOf("Grisholm vs. Pellicot", "Light Heavyweight"),
                listOf("Kestvik vs. Velmar", "Featherweight"),
                listOf("Brenneck vs. Marhoff", "Middleweight"),
            ),
            "Main Card" to listOf(
                listOf("Lindqvar vs. Zelinek", "Heavyweight"),
                listOf("Ostrem vs. Tessmar", "Women's Flyweight"),
                listOf("Rendahl vs. Varnholt", "Welterweight"),
                listOf("Brisk vs. Loam", "Bantamweight", "Title fight"),
                listOf("Quessel vs. Halde", "Lightweight", "Title fight", "Main event"),
            ),
        )

        /** The fight night's rows in the order fought: its early prelims' bout first under Prelims, the main event last. */
        val FIGHT_NIGHT_ROWS = listOf(
            listOf("Ostrem vs. Wexmoor", "Women's Strawweight"),
            listOf("Kestvik vs. Savrin", "Flyweight"),
            listOf("Ferlund vs. Velmar", "Bantamweight"),
            listOf("Durnay vs. Tessmar", "Women's Flyweight"),
            listOf("Kjelsen vs. Rendahl", "Welterweight"),
            listOf("Brenneck vs. Pellicot", "Light Heavyweight"),
            listOf("Lindqvar vs. Zelinek", "Heavyweight"),
            listOf("Grisholm vs. Marhoff", "Middleweight"),
            listOf("Brisk vs. Loam", "Featherweight", "Main event"),
        )

        /** What a page would show of a result: the separator, the methods, the notes and the bonuses. */
        val RESULT_WORDS = listOf("def.", "Decision", "Submission", "KO", "Draw", "No Contest", "deducted", "Night:", "Performance", "took the")

        /** A round's time, as a result's cell writes it (not a time of day, which has two digits before its colon). */
        val TIME = Regex("""(?<![\d:])\d:\d\d(?!\d)""")

        /** The 503's hold (L-B6), and a moment more. */
        const val HOLD_MS = 31_000L

        /** How long a Try again pressed in a hold may take to ask nothing. */
        const val QUIET_MS = 1_500L

        const val LIST_WAIT_MS = 30_000L
    }
}

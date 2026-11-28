package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelected
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
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.sportarr.SportarrDevice
import io.skjaere.usenet.app.plugins.sportarr.canaries
import io.skjaere.usenet.app.plugins.sportarr.screenWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A UFC card's bouts on a real TV (tv36; L-B7, L-B10, BtTvCard, BtTvCardStates), with the remote, the real plugin over
 * the fake Sportarr and the fake Wikipedia: the right pane row by row, each part, then its bouts, the credit the list's
 * last stop; Right on a part to its ⋮, and on a bout or the credit nowhere; OK on a bout plays its part, the focus staying
 * on it; the credit's sheet, a double OK leaving it open with Close focused, Close and Back closing it with the focus back
 * on the credit; an upcoming card's bouts taking the focus, OK on one playing nothing; and the states: Reading with the
 * first focus on the top button, no card's line taking no focus, and Can't reach's Try again, reached by Down from the last
 * part, the focus on that part as the bouts come.
 *
 * The fake's switches, the indexer and the provider the tests set and the watch rows are put back after each test, and the
 * plugin with its files and secrets removed. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class SportarrTvBoutsTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = plugins.context.container
    private val sportarr = SportarrDevice(plugins, "SportarrTvBoutsTest")
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun tv() {
        assumeTv()
        sportarr.assumeUp()
        home.show()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
        sportarr.hold()
        sportarr.assumeWikipedia()
    }

    @After
    fun restore() {
        container.playback.cancel()
        instrumentation.setInTouchMode(true)
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of other titles was refused, never sent.
        }
        sportarr.restore()
        home.restore()
    }

    private val library get() = sportarr.catalogue

    /**
     * The page of the list's row titled [title], on the list's [upcoming] side or Recent, opened by OK as the remote opens
     * it, and its first moment let pass: an OK then is taken for the one that opened it (its OK guard).
     */
    private fun openPage(title: String, upcoming: Boolean = false) {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
        waitFor(railItem(SPORTS))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SPORTS, max = 6)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("TODAY"), LIST_WAIT_MS)
        try {
            compose.waitUntil(FOCUS_WAIT_MS) { remote.focused() == ALL }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the list's first focus isn't on All; it is on \"${remote.focused()}\"", e)
        }
        if (upcoming) {
            assertEquals("Upcoming", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Upcoming") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and isSelected())
        }
        // A day's row, its time first, never Up next's row of the same event.
        val rowOf = Regex("""\d\d:\d\d \| ${Regex.escape(title)}( \|.*)?""")
        var row = remote.focused()
        repeat(MAX_DOWNS) { if (!rowOf.matches(row)) row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("the row of $title: \"$row\"", rowOf.matches(row))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        SystemClock.sleep(OkGuard.WINDOW_MS)
    }

    private fun openUfc999() = openPage(library.event(UFC_999).title)

    /** From the top button, Right into the right pane and Up to its first row. */
    private fun toTheFirstRow(first: String) {
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the right pane's first row", first, remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, first, max = 20))
    }

    /**
     * L-B10: Down row by row, each part, then its bouts, to the credit, the list's last stop; Right on a part to its ⋮, on a
     * bout or the credit nowhere; Left back to the left pane.
     */
    @Test
    fun downGoesPartByBoutToTheCredit() {
        sportarr.install()
        openUfc999()
        remote.waitForFocus(TOP_BUTTON)
        waitFor(hasText(MAIN_EVENT))
        toTheFirstRow(UFC_999_ORDER.first())
        UFC_999_ORDER.drop(1).forEach { next -> assertEquals("Down to $next", next, remote.press(KeyEvent.KEYCODE_DPAD_DOWN)) }
        assertEquals("the credit, the list's last stop", CREDIT, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Right on the credit", CREDIT, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        val bout = UFC_999_ORDER.last()
        assertEquals("Up from the credit", bout, remote.press(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("Right on a bout", bout, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left back to the left pane", TOP_BUTTON, remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Right back to the bout", bout, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        val prelims = remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, "Prelims", max = 12)
        assertEquals("Prelims", prelims)
        assertEquals("Right on a part: its ⋮", "More for Prelims", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left back to the part", "Prelims", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Down to its first bout", "Ferlund vs. Tarnoy | Bantamweight", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("the bouts' footnote", exists(hasText(BOUTS_TV_FOOTNOTE)))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** (mock) OK on a bout plays its part (W5), and the focus stays on the bout under the play. */
    @Test
    fun okOnABoutPlaysItsPartAndTheFocusStays() {
        sportarr.assumeUp(plays = true)
        sportarr.install()
        sportarr.indexers(plays = true)
        val card = library.event(UFC_999)
        sportarr.forget(card.part("prelims"))
        openUfc999()
        remote.waitForFocus(TOP_BUTTON)
        waitFor(hasText(MAIN_EVENT))
        toTheFirstRow(UFC_999_ORDER.first())
        val tapped = "Grisholm vs. Pellicot | Light Heavyweight"
        assertEquals(tapped, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, tapped, max = 10))
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val launch = sportarr.launchOf(card.part("prelims"))
        assertEquals("the part's play", "${card.title} · Prelims", launch.watch.title)
        container.playback.cancel()
        compose.waitUntil(PLUGIN_WAIT_MS) { container.playback.state.value == PlayState.Idle }
        remote.waitForFocus(tapped, PLUGIN_WAIT_MS)
    }

    /**
     * The credit's sheet: OK twice quickly on the credit (a remote over CEC) leaves it open, Close focused; the attribution
     * as plain words and what is left out, with no link and no warning; OK on Close, and Back, close it, the focus back on
     * the credit.
     */
    @Test
    fun theCreditsSheetKeepsADoubleOkAndGivesTheFocusBack() {
        sportarr.install()
        openUfc999()
        remote.waitForFocus(TOP_BUTTON)
        waitFor(hasText(MAIN_EVENT))
        toTheFirstRow(UFC_999_ORDER.first())
        assertEquals(CREDIT, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, CREDIT, max = 20))
        val first = SystemClock.uptimeMillis()
        okSentAt(first)
        okSentAt(first + DOUBLE_OK_MS)
        val inSheet = hasAnyAncestor(isDialog())
        waitFor(hasText(ABOUT) and inSheet)
        remote.waitForFocus(CLOSE)
        assertTrue("the attribution as plain words", exists(hasText("Fight card: Wikipedia, “UFC 999”, CC BY-SA 4.0") and inSheet))
        assertTrue("what is left out", exists(hasText("Sports shows the bouts and leaves out the results: no winners, methods, rounds or times.") and inSheet))
        assertFalse("View on Wikipedia on a TV", exists(hasText("View on Wikipedia")))
        assertFalse("the warning on a TV", exists(hasText("Wikipedia's page shows the results.")))
        SystemClock.sleep(OkGuard.WINDOW_MS)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(hasText(ABOUT)) }
        remote.waitForFocus(CREDIT)

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(ABOUT) and inSheet)
        remote.waitForFocus(CLOSE)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(hasText(ABOUT)) }
        remote.waitForFocus(CREDIT)
        assertTrue("still on the card's page", exists(hasText(library.event(UFC_999).title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)))
    }

    /**
     * An upcoming card, its card out: no part takes the focus before the start, so Right from the line in the buttons'
     * place reaches its bouts, Down each in turn to the credit; OK on one plays nothing.
     */
    @Test
    fun anUpcomingCardsBoutsTakeTheFocus() {
        sportarr.install()
        val card = library.event(FIGHT_NIGHT)
        openPage(card.title, upcoming = true)
        waitForFocusOn(hasAnyDescendant(hasText(CARD_NOT_STARTED)), "its line")
        waitFor(hasText("Brisk vs. Loam"))
        toTheFirstRow(FIGHT_NIGHT_ORDER.first())
        FIGHT_NIGHT_ORDER.drop(1).forEach { next -> assertEquals("Down to $next", next, remote.press(KeyEvent.KEYCODE_DPAD_DOWN)) }
        assertEquals("the credit", CREDIT, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val bout = remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(FIGHT_NIGHT_ORDER.last(), bout)
        val started = CopyOnWriteArrayList<String>()
        val watching = CoroutineScope(Dispatchers.Default).launch {
            launch { container.playback.state.collect { if (it != PlayState.Idle) started += "the play ${it.javaClass.simpleName}" } }
            launch { container.playback.launches.collect { started += "a launch of ${it.watchKey}" } }
        }
        try {
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(QUIET_MS)
        } finally {
            watching.cancel()
        }
        assertEquals("what OK on a bout before the start started", emptyList<String>(), started.toList())
        assertEquals("the focus still on the bout", bout, remote.focused())
    }

    /** Reading: the page's first focus as ever, on its top button, the line under the parts; then the bouts. */
    @Test
    fun readingKeepsTheFirstFocusOnTheTopButton() {
        sportarr.switch("wiki-hold", "on=true")
        sportarr.install()
        openUfc999()
        waitFor(hasText(READING))
        remote.waitForFocus(TOP_BUTTON)
        sportarr.off("wiki-hold")
        waitFor(hasText(MAIN_EVENT))
        assertEquals("the focus where it was", TOP_BUTTON, remote.focused())
    }

    /** No card found: its line takes no focus, so Down from the last part stays there. */
    @Test
    fun noCardsLineTakesNoFocus() {
        sportarr.switch("wiki-form", "event=$UFC_999&form=missing")
        sportarr.install()
        openUfc999()
        waitFor(hasText(NO_CARD))
        remote.waitForFocus(TOP_BUTTON)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(MAIN_CARD, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, MAIN_CARD, max = 5))
        assertEquals("Down from the last part", MAIN_CARD, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("today's footnote", exists(hasText(CARD_TV_FOOTNOTE)))
    }

    /**
     * Can't reach: Try again, a small button before the line, reached by Down from the last part; pressed once the 503's
     * hold has passed, the bouts come and the focus is on the last part's row.
     */
    @Test
    fun cantReachsTryAgainButton() {
        sportarr.switch("wiki-fail", "status=503")
        sportarr.install()
        openUfc999()
        waitFor(hasText(CANT_REACH))
        val failedAt = SystemClock.elapsedRealtime()
        remote.waitForFocus(TOP_BUTTON)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(MAIN_CARD, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, MAIN_CARD, max = 5))
        assertEquals("Down from the last part", TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        sportarr.off("wiki-fail")
        SystemClock.sleep((failedAt + HOLD_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(MAIN_EVENT))
        assertEquals("the focus on the last part's row", MAIN_CARD, remote.focused())
        assertEquals("one more request", listOf("503", "200"), sportarr.wikiRequests().map { it["status"].toString() })
    }

    /** One press of OK as the remote sent it at [at] (uptime), however late it reaches the app. */
    private fun okSentAt(at: Long) {
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }

    private fun waitForFocusOn(matcher: SemanticsMatcher, what: String) {
        try {
            compose.waitUntil(FOCUS_WAIT_MS) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"; shown: ${compose.screenWords()}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; focus on \"${remote.focused()}\"; shown: ${compose.screenWords()}", e)
        }
    }

    private companion object {
        /** The library's UFC 999, yesterday, and the fight night six days on (L-B12's two cards). */
        const val UFC_999 = 1901
        const val FIGHT_NIGHT = 1902

        const val SPORTS = "Sports"
        const val ALL = "All"
        const val TOP_BUTTON = "Play Early Prelims"
        const val MAIN_CARD = "Main Card"
        const val MAIN_EVENT = "Quessel vs. Halde"
        const val CREDIT = "Fight card from Wikipedia"
        const val ABOUT = "About the bouts"
        const val CLOSE = "Close"
        const val READING = "Reading the fight card…"
        const val NO_CARD = "No fight card found on Wikipedia for this event."
        const val CANT_REACH = "Couldn't reach Wikipedia for the fight card."
        const val TRY_AGAIN = "Try again"
        const val CARD_NOT_STARTED = "The parts play once the card has started. Releases often appear a few hours after the end."
        const val CARD_TV_FOOTNOTE = "Press OK on a part to play the best release, or ⋮ to choose one yourself."
        const val BOUTS_TV_FOOTNOTE = "Press OK on a part, or a bout in it, to play the part's best release, or ⋮ to choose one yourself."

        /** UFC 999's right pane as the remote reads it, top to bottom: each part, then its bouts (the fake's card). */
        val UFC_999_ORDER = listOf(
            "Early Prelims",
            "Ambrel vs. Savrin | Flyweight",
            "Durnay vs. Wexmoor | Women's Strawweight",
            "Kjelsen vs. Tollerup | Welterweight",
            "Prelims",
            "Ferlund vs. Tarnoy | Bantamweight",
            "Grisholm vs. Pellicot | Light Heavyweight",
            "Kestvik vs. Velmar | Featherweight",
            "Brenneck vs. Marhoff | Middleweight",
            MAIN_CARD,
            "Lindqvar vs. Zelinek | Heavyweight",
            "Ostrem vs. Tessmar | Women's Flyweight",
            "Rendahl vs. Varnholt | Welterweight",
            "Brisk vs. Loam | Bantamweight | Title fight",
            "Quessel vs. Halde | Lightweight | Title fight | Main event",
        )

        /** The fight night's bouts as the remote reads them before its start, its parts taking no focus. */
        val FIGHT_NIGHT_ORDER = listOf(
            "Ostrem vs. Wexmoor | Women's Strawweight",
            "Kestvik vs. Savrin | Flyweight",
            "Ferlund vs. Velmar | Bantamweight",
            "Durnay vs. Tessmar | Women's Flyweight",
            "Kjelsen vs. Rendahl | Welterweight",
            "Brenneck vs. Pellicot | Light Heavyweight",
            "Lindqvar vs. Zelinek | Heavyweight",
            "Grisholm vs. Marhoff | Middleweight",
            "Brisk vs. Loam | Featherweight | Main event",
        )

        /** A double press's second OK after its first, and how long nothing may start after an OK that plays nothing. */
        const val DOUBLE_OK_MS = 150L
        const val QUIET_MS = 3_000L

        /** The 503's hold (L-B6), and a moment more. */
        const val HOLD_MS = 31_000L

        const val LIST_WAIT_MS = 30_000L
        const val FOCUS_WAIT_MS = 8_000L

        /** Presses of Down from the title line to any row of the list's first days. */
        const val MAX_DOWNS = 40
    }
}

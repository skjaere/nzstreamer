package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
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
import io.skjaere.usenet.app.search.PossibleMatches
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
 * The event pages on a real TV (tv36; L590, L652, L660), with the remote, the real
 * plugin over the fake Sportarr, the sports indexer as the one indexer and the mock as the one provider: a game's two
 * panes, the first focus on Play, Left and Right back to where the focus was in each; a game not started, its line
 * holding the focus; a weekend's top button first, its sessions on the right, a session's ⋮ opening its menu with the
 * focus on Choose release; a weekend not started, whose sessions take no focus; a card's top button first; Choose
 * release's possible matches for the race (the German name alone, "Press OK on one if it's the right session."), Back
 * landing on the ⋮ it came from, or on the left pane's Choose release; and a double OK on a list's row, which opens the
 * page and plays nothing (its OK guard). No canary value on any page.
 *
 * The watch rows the tests write, the indexer and provider they set and the fake's switches are put back after each
 * test, and the plugin with its files and secrets removed. TMDB is a strict fake: what Home asks of other titles is
 * refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class SportarrTvPagesTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = plugins.context.container
    private val sportarr = SportarrDevice(plugins, "SportarrTvPagesTest")
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun tv() {
        assumeTv()
        sportarr.assumeUp(plays = true)
        home.show()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
        sportarr.hold()
        sportarr.install()
        // Whatever plays goes to the fakes alone: the sports indexer and the mock.
        sportarr.indexers(plays = true)
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
     * The app as a TV shows it, its Sports tab by the rail, the list's [upcoming] side or Recent, and the focus on the row
     * titled [title], Down from the chips: its label as the remote reads it.
     */
    private fun toRow(title: String, upcoming: Boolean = false): String {
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
        // The title line: the list's own first focus is SportarrTvTest's to check.
        compose.waitUntil(FOCUS_WAIT_MS) { remote.focused() in setOf(ALL, "Recent", "Upcoming") }
        if (upcoming) {
            // Upcoming sits between Recent and All.
            if (remote.focused() == "Recent") remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            if (remote.focused() == ALL) remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("Upcoming", remote.focused())
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Upcoming") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and isSelected())
        }
        // A day's row, its time first, never Up next's row of the same event.
        val rowOf = Regex("""\d\d:\d\d \| ${Regex.escape(title)}( \|.*)?""")
        var row = remote.focused()
        repeat(MAX_DOWNS) { if (!rowOf.matches(row)) row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("the row of $title: \"$row\"", rowOf.matches(row))
        return row
    }

    /**
     * The page of the row titled [title], opened by OK, and its first moment let pass: an OK then is taken for the one that
     * opened it (its OK guard, which the double OK's case checks).
     */
    private fun openPage(title: String, upcoming: Boolean = false) {
        toRow(title, upcoming)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        SystemClock.sleep(OkGuard.WINDOW_MS)
    }

    /** A game's page: the first focus on Play; Right to Mark watched and Left back; Down to Choose release; the panes keep their place. */
    @Test
    fun aGamesPanesAndFocus() {
        openPage(library.event(GAME).title)
        remote.waitForFocus(PLAY)
        assertEquals("Right from Play", MARK_WATCHED, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left from Mark watched", PLAY, remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Down from Play", CHOOSE, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Right from Choose release", MARK_WATCHED, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left: where the focus was in the left pane", CHOOSE, remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse("the Cast button on a TV", exists(hasText("Play on: this phone")))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** A game not started: its line in the buttons' place holds the focus. */
    @Test
    fun aGameNotStartedHoldsTheFocusOnItsLine() {
        openPage(library.event(LATER_GAME).title, upcoming = true)
        waitForFocusOn(held(GAME_NOT_STARTED), "its line")
        assertFalse("Play before the start", exists(hasText(PLAY) and button))
    }

    /**
     * A weekend's page: the top button first, Choose release for its session under it, the sessions on the right; a session's ⋮
     * opens its menu with the focus on Choose release, and Back from the menu puts it back on the ⋮.
     */
    @Test
    fun aWeekendsTopButtonThenItsSessions() {
        openPage(KESTREL_GP)
        remote.waitForFocus("Play Practice 1")
        assertEquals("Down from the top button", CHOOSE, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val session = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right: a session, \"$session\"", SESSION.matches(session))
        val race = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Race |", max = 8)
        assertTrue("the race's row: \"$race\"", race.startsWith("Race |"))
        assertEquals("Right from the race", "More for Race", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(CHOOSE)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("More for Race")
        assertTrue("its footnote", exists(hasText("Press OK on a session to play the best release, or ⋮ to choose one yourself.")))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** A weekend not started: its line holds the focus, and its sessions, none begun, take none: Right finds nothing there. */
    @Test
    fun aWeekendNotStartedSkipsItsSessions() {
        openPage(TARNHOLM_GP, upcoming = true)
        waitForFocusOn(held(WEEKEND_NOT_STARTED), "its line")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from its line: on \"${remote.focused()}\"", remote.isFocused(held(WEEKEND_NOT_STARTED)))
    }

    /** A card's page on a TV: the top button first, the parts on the right. */
    @Test
    fun aCardsTopButtonFirst() {
        openPage(library.event(CARD).title)
        remote.waitForFocus("Play Early Prelims")
        val part = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).substringBefore(" |")
        assertTrue("Right: a part, \"$part\"", part in setOf("Early Prelims", "Prelims", "Main Card"))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /**
     * Choose release on a TV: the race's ⋮, Choose release: the accepted releases, then the possible matches, the German name the only
     * one the searches reach, "Press OK on one if it's the right session."; Back on the ⋮ it came from. With the sessions
     * before it watched, the race is the top button's, and Back from its Choose release lands on the left pane's.
     */
    @Test
    fun chooseReleasesPossibleMatchesAndBack() {
        val race = library.event(KESTREL_RACE)
        library.events.filter { it.leagueId == FORMULA_1 && it.round == "20" && it.id != KESTREL_RACE }.forEach { session ->
            sportarr.watched(session.part(), "$KESTREL_GP · ${session.title.substringAfterLast(" - ")}")
        }
        openPage(KESTREL_GP)
        remote.waitForFocus("Play Race")
        assertTrue("Right from the top button: a session", SESSION.matches(remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue("the race's row", remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Race |", max = 8).startsWith("Race |"))
        assertEquals("More for Race", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(CHOOSE)
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(PossibleMatches.HEADING.uppercase()), SEARCH_WAIT_MS)
        val offered = library.found(race.id, "full", "offer")
        assertEquals("the race's possible matches the searches reach", listOf("kestrel-race-german"), offered.map { it.first.id })
        assertTrue("the German name", exists(hasText(offered.single().first.name, substring = true)))
        val unreached = library.releases.filter { release -> release.verdicts.any { it.event == race.id && it.verdict == "offer" && !it.reached } }
        unreached.forEach { assertFalse("${it.id}, which no search reaches", exists(hasText(it.name, substring = true))) }
        assertTrue("its line", exists(hasText("Press OK on one if it's the right session.", substring = true)))
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("More for Race")

        // Left to the left pane, where the focus comes back to its top button or the Choose release under it.
        repeat(3) { if (remote.focused() !in setOf("Play Race", CHOOSE)) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        if (remote.focused() == "Play Race") remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the left pane's Choose release", CHOOSE, remote.focused())
        // The page came back from Choose release: an OK in its first moment is taken for the one that opened it.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(PossibleMatches.HEADING.uppercase()), SEARCH_WAIT_MS)
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(CHOOSE)
        assertTrue("on the page's Choose release", exists(hasText(KESTREL_GP) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)))
    }

    /**
     * L660, a remote over CEC, which sends a held key as separate presses: the OK that opened a game's page, pressed twice (its second press as the remote sent
     * it, 150 ms after the first, reaching the page once Play has the focus), opens the page and plays nothing: no play
     * starts and no watch row is written.
     */
    @Test
    fun aDoubleOkOnARowOpensThePageAndPlaysNothing() {
        val game = library.event(GAME)
        sportarr.forget(game.part())
        val before = sportarr.state(game.part())
        toRow(game.title)
        val first = SystemClock.uptimeMillis()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(PLAY)
        // What the coordinator does meanwhile, heard as it happens: a play on the mock can start and end within one wait for idle.
        val started = CopyOnWriteArrayList<String>()
        val watching = CoroutineScope(Dispatchers.Default).launch {
            launch { container.playback.state.collect { if (it != PlayState.Idle) started += "the play ${it.javaClass.simpleName}" } }
            launch { container.playback.launches.collect { started += "a launch of ${it.watchKey}" } }
        }
        try {
            okSentAt(first + DOUBLE_OK_MS)
            SystemClock.sleep(QUIET_MS)
        } finally {
            watching.cancel()
        }
        assertEquals("what the double OK started", emptyList<String>(), started.toList())
        assertEquals("the watch row", before, sportarr.state(game.part()))
        assertEquals("the focus still on Play", PLAY, remote.focused())
    }

    /** One press of OK as the remote sent it at [at] (uptime), however late it reaches the app. */
    private fun okSentAt(at: Long) {
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitForIdle()
    }

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** The line that holds a page's focus before its start: the focus on its box, its words inside. */
    private fun held(line: String) = hasAnyDescendant(hasText(line))

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
        /** The library's: Lantern Bay FC's game, aired yesterday; a game six days on; UFC 999's card; Formula 1 and its Kestrel race. */
        const val GAME = 1001
        const val LATER_GAME = 1014
        const val CARD = 1901
        const val FORMULA_1 = 7
        const val KESTREL_RACE = 1605
        const val KESTREL_GP = "Grand Prix of Kestrel"
        const val TARNHOLM_GP = "Grand Prix of Tarnholm"

        const val SPORTS = "Sports"
        const val ALL = "All"
        const val PLAY = "Play"
        const val CHOOSE = "Choose release"
        const val MARK_WATCHED = "Mark watched"
        const val GAME_NOT_STARTED =
            "Play, Choose release and Search indexers show once it has started. Releases often appear a few hours after the end."
        const val WEEKEND_NOT_STARTED = "The sessions play once each has started. Releases often appear a few hours after the end."

        /** A session's row as the remote reads it: its name, then its day and time. */
        val SESSION = Regex("""(Practice \d|Qualifying|Race) \| .+""")

        /** A double press's second OK after its first, and how long nothing may start after it. */
        const val DOUBLE_OK_MS = 150L
        const val QUIET_MS = 3_000L

        const val LIST_WAIT_MS = 30_000L
        const val SEARCH_WAIT_MS = 60_000L
        const val FOCUS_WAIT_MS = 8_000L

        /** Presses of Down from the title line to any row of the list's first days. */
        const val MAX_DOWNS = 40
    }
}

package io.skjaere.usenet.app.tv

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
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
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginMeasure
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.plugins.sportarr.SportarrDevice
import io.skjaere.usenet.app.plugins.sportarr.SportsFakes
import io.skjaere.usenet.app.plugins.sportarr.screenWords
import io.skjaere.usenet.app.ui.FieldGateLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Sports tab on a real TV (tv36; L590, L652, L660), with the remote, the real
 * plugin over the fake Sportarr: the setup by remote (the first focus on the address, Save after a passed Test, Test
 * keeping the focus after a refused key, Up to the key); Change opening the key's field with its keyboard, Right from the
 * key's gate to Show; the list's first focus on All, Down into Up next and the days, Left to the rail and Right back to
 * the row left, Back to the rail and then Home; Back from a game's page on its row; "+N more" opening in place with the
 * focus on its sixth row, and Back from that row's page on it; Upcoming by OK, the focus staying on it; each state's
 * focus (loading, no list, nothing followed, the key refused, a failing page under the kept list); and M-big with the
 * fake's busy league.
 *
 * The watch rows the tests write and the fake's switches are put back after each test, and the plugin with its files and
 * secrets removed. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class SportarrTvTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = plugins.context.container
    private val sportarr = SportarrDevice(plugins, "SportarrTvTest")
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun tv() {
        assumeTv()
        sportarr.assumeUp()
        home.show()
        tmdb = FakeTmdb(strict = true)
        leaveTouchMode(compose.activity)
        sportarr.hold()
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
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

    /**
     * The app's screens: a new [generation] makes them again from nothing, as after the app's start, while the plugin and
     * its list run on.
     */
    private var generation by mutableIntStateOf(0)

    /** The app as a TV shows it, then its Sports tab by the rail: Left to the rail, Down to Sports, OK. */
    private fun openSports() {
        compose.setTvContent {
            key(generation) {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        toSportsByTheRail()
    }

    /** From Home's first focus to the Sports tab by the rail: Left to the rail, Down to Sports, OK. */
    private fun toSportsByTheRail() {
        remote.waitForHomeFocus()
        waitFor(railItem(SPORTS))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertEquals("on the rail", SPORTS, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SPORTS, max = 6))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** The list read and shown, the focus on All, its first focus. */
    private fun listFocused() {
        waitFor(hasText(TODAY), LIST_WAIT_MS)
        waitForFocusOn(chip(ALL), "All")
    }

    /**
     * The setup by remote: the first focus on the address; Test, connected, puts the focus on Save; with the key refused
     * it stays on Test, Up going back to the key; Save shows the list, the focus on All.
     */
    @Test
    fun theSetupByRemote() {
        sportarr.install(connected = false)
        openSports()
        waitForFocusOn(fieldGate(ADDRESS), "the address")
        // Typed as with the remote: OK opens the address to type, the keyboard's Next goes on to the key, Done closes it.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(field(ADDRESS) and isFocused())
        compose.onNode(field(ADDRESS)).performTextReplacement(SportsFakes.URL)
        compose.onNode(field(ADDRESS)).performImeAction()
        waitFor(field(KEY) and isFocused())
        compose.onNode(field(KEY)).performTextReplacement(SportsFakes.KEY)
        compose.onNode(field(KEY)).performImeAction()
        waitForFocusOn(fieldGate(KEY), "the key's gate, after Done")
        assertEquals("Down from the key", "Test", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Connected · Sportarr ${sportarr.version()}"))
        waitForFocusOn(button("Save"), "Save, after a passed Test")

        sportarr.switch("refuse-key")
        assertEquals("Left from Save", "Test", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(REFUSED))
        waitForFocusOn(button("Test"), "Test, after a refused key")
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from Test: the key's gate, not \"${remote.focused()}\"", remote.isFocused(fieldGate(KEY)))
        sportarr.off("refuse-key")

        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Right from Test", "Save", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        listFocused()
    }

    /**
     * The settings page from the list's ⋮, the key Saved; Down from the address to Change, whose OK opens the
     * key's field to type, its keyboard up; Back closes it to its gate, and Right from there reaches Show.
     */
    @Test
    fun changeOpensTheKeysFieldToType() {
        sportarr.install()
        openSports()
        listFocused()
        assertEquals("the ⋮ at the title line's end", MORE, remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, MORE, max = 12))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(SHOW_WATCHED)
        assertEquals(SETTINGS, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SETTINGS, max = 3))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Saved"))
        waitForFocusOn(fieldGate(ADDRESS), "the address")
        assertEquals("Down from the address", "Change", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { exists(field(KEY) and isFocused()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Change didn't open the key's field; the focus is on \"${remote.focused()}\"", e)
        }
        assertTrue("Paste the new key", exists(hasText("Paste the new key")))
        // The keyboard, if it comes, up before Back.
        SystemClock.sleep(KEYBOARD_MS)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForFocusOn(fieldGate(KEY), "the key's gate, after Back")
        assertEquals("Right from the key's gate", "Show $KEY", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /**
     * The list's first focus on All; Down into Up next, then the days; Left to the rail and Right back to the row left
     * (the list's focus memory); Back to the rail, and from there Home.
     */
    @Test
    fun theListsFocusFromAllIntoUpNextAndTheDays() {
        sportarr.install()
        val game = library.event(GAME)
        sportarr.progress(game.part(), game.title, 40 * MINUTE, 110 * MINUTE)
        openSports()
        listFocused()
        val upNext = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from All: Up next's game, \"$upNext\"", upNext.startsWith("${game.title} | Premier League · "))
        val row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Up next: Today's first row, \"$row\"", ROW.matches(row))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem(SPORTS), "its rail item")
        assertEquals("Right from the rail: the row left", row, remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_BACK)
        waitForFocusOn(railItem(SPORTS), "its rail item, after Back")
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(tvHome)
        waitForFocusOn(railItem("Home"), "the rail's Home, after Back on the rail")
    }

    /**
     * OK on an Up next row plays it and leaves nothing for the focus to come back to: on a TV the list stays under the
     * player, or the host's sheet. Home, and the tab again: the list's first focus, All, never the Up next row, whose OK
     * plays it again. Here the game has no release on the sports indexer, so the host's sheet says so.
     */
    @Test
    fun anUpNextPlayLeavesTheTabsFirstFocusAsItWas() {
        sportarr.assumeUp(indexer = true)
        sportarr.install()
        sportarr.indexers(plays = false)
        val game = library.event(NOTHING_GAME)
        sportarr.progress(game.part(), game.title, 40 * MINUTE, 110 * MINUTE)
        openSports()
        listFocused()
        val upNext = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from All: Up next's game, \"$upNext\"", upNext.startsWith("${game.title} | Premier League · "))
        sportarr.paceSearches()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("No playable release found"), SEARCH_WAIT_MS)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(FOCUS_WAIT_MS) { !exists(hasText("No playable release found")) }
        // The play over, as the next test needs it.
        container.playback.cancel()
        compose.waitUntil(FOCUS_WAIT_MS) { container.playback.state.value == PlayState.Idle }

        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem(SPORTS), "its rail item")
        assertEquals("Home", remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, "Home", max = 4))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(tvHome)
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitForFocusOn(railItem("Home"), "the rail's Home")
        assertEquals(SPORTS, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SPORTS, max = 6))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        listFocused()
    }

    /** L660: Back from a game's page, opened by OK on its row, lands on that row. */
    @Test
    fun backFromAGamesPageLandsOnItsRow() {
        sportarr.install()
        val game = library.event(GAME)
        openSports()
        listFocused()
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, game.title, max = 30)
        assertTrue("its row: \"$row\"", game.title in row)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(NOT_WATCHED))
        remote.waitForFocus("Play")
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(game.title)
        assertEquals("back on its row", row, remote.focused())
    }

    /**
     * Back from a game's page once it was watched there: its row has gone (watched rows wait for Show watched), and the
     * focus lands on the row now in its place, not back at the top on All.
     */
    @Test
    fun backFromAGameWatchedOnItsPageLandsOnTheRowInItsPlace() {
        sportarr.install()
        val game = library.event(GAME)
        val day = library.league(PREMIER_LEAGUE).events.filter { it.listed && it.date(zone) == game.date(zone) }.sortedWith(compareBy({ it.start }, { it.key }))
        val after = day[day.indexOf(game) + 1]
        openSports()
        listFocused()
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, game.title, max = 30)
        assertTrue("its row: \"$row\"", game.title in row)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(NOT_WATCHED))
        remote.waitForFocus("Play")
        // The OK that opened the page is past its guard.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        assertEquals("Right from Play", "Mark watched", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        sportarr.forget(game.part())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("✓ Watched"))
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(after.title, FOCUS_WAIT_MS)
        assertTrue("on a day's row: \"${remote.focused()}\"", ROW.matches(remote.focused()))
    }

    /** "+N more" by OK opens the league's day in place, the focus on its sixth row; Back from that row's page lands on it. */
    @Test
    fun moreOpensInPlaceOnItsSixthRow() {
        sportarr.install()
        val yesterday = LocalDate.now(zone).minusDays(1)
        val games = library.league(PREMIER_LEAGUE).events.filter { it.listed && it.date(zone) == yesterday }.sortedWith(compareBy({ it.start }, { it.key }))
        assertTrue("more than five games yesterday: ${games.size}", games.size > 5)
        openSports()
        listFocused()
        val more = "+${games.size - 5} more"
        assertEquals(more, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, more, max = 30))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(games[5].title)
        val sixth = remote.focused()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(NOT_WATCHED))
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus(games[5].title)
        assertEquals("back on the sixth row", sixth, remote.focused())
    }

    /** OK on Upcoming switches the side, the focus staying on it and the chips keeping their choice. */
    @Test
    fun upcomingByOkKeepsTheFocusThere() {
        sportarr.install()
        openSports()
        listFocused()
        assertEquals("Left from All", UPCOMING, remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(sideTab(UPCOMING) and isSelected())
        waitFor(hasText("TOMORROW"))
        assertEquals("the focus after OK", UPCOMING, remote.focused())
        assertTrue("All still picked", exists(chip(ALL) and isSelected()))
    }

    /**
     * A sport kept from before (view.json) past the chips the title line shows: with the app's screens made again, as after
     * its start, the list's first focus goes to that chip, brought into view, and it shows picked.
     */
    @Test
    fun aKeptSportFarAlongTheChipsTakesTheFirstFocusInView() {
        sportarr.install()
        openSports()
        listFocused()
        assertEquals(SKIING, remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, SKIING, max = 12))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(chip(SKIING) and isSelected())

        compose.runOnIdle { generation++ }
        toSportsByTheRail()
        waitFor(hasText(library.event(SKI_EVENT).title), LIST_WAIT_MS)
        waitForFocusOn(chip(SKIING), "the kept sport's chip")
        assertTrue("shown picked", exists(chip(SKIING) and isSelected()))
    }

    /** While the first read runs, the blank cards: the focus on Recent; then the list, the focus moved on to All. */
    @Test
    fun loadingFocusesRecentThenAll() {
        sportarr.install()
        sportarr.switch("slow", "ms=$SLOW_MS")
        openSports()
        waitForFocusOn(sideTab(RECENT), "Recent while the cards are blank")
        listFocused()
    }

    /** No list kept and Sportarr failing: the card's Try again focused; once it reads, the list with the focus on All. */
    @Test
    fun noListKeptFocusesTryAgain() {
        sportarr.install()
        sportarr.switch("fail", "status=500&route=leagues")
        openSports()
        waitFor(hasText(CANT_REACH), LIST_WAIT_MS)
        waitForFocusOn(button(TRY_AGAIN), "the card's Try again")
        sportarr.off("fail")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        listFocused()
    }

    /** A Sportarr that follows nothing: the card's Refresh focused. */
    @Test
    fun nothingFollowedFocusesRefresh() {
        sportarr.install()
        sportarr.switch("empty")
        openSports()
        waitFor(hasText("Your Sportarr follows nothing yet"), LIST_WAIT_MS)
        waitForFocusOn(button(REFRESH), "the card's Refresh")
    }

    /** The key refused with no list kept: the card's Sportarr settings focused, as Try again would be; OK opens the settings. */
    @Test
    fun aRefusedKeyFocusesSportarrSettings() {
        sportarr.install()
        sportarr.switch("refuse-key")
        openSports()
        waitFor(hasText("Sportarr refused the API key"), LIST_WAIT_MS)
        waitForFocusOn(button(SETTINGS), "the card's Sportarr settings")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Saved"))
    }

    /** A failing page under the kept list: the line over it, its Try again first and one Down from the chips. */
    @Test
    fun aFailingPageUnderTheKeptListPutsTryAgainOneDown() {
        sportarr.install()
        openSports()
        listFocused()
        sportarr.switch("fail", "status=500&route=events&page=1")
        refreshByTheMenu()
        waitFor(hasText("Can't reach Sportarr · list from", substring = true), LIST_WAIT_MS)
        remote.waitForFocus(MORE)
        assertEquals(ALL, remote.pressUntil(KeyEvent.KEYCODE_DPAD_LEFT, ALL, max = 12))
        assertEquals("Down from the chips", TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    /**
     * M-big on a TV: with the fake's busy league kept (5,000 events), the time from OK on the rail's Sports to the list's
     * first row, and the share of slow frames as the remote goes down the rows, through the instrumentation's status.
     */
    @Test
    fun theBusyLeagueOnATv() {
        sportarr.install()
        openSports()
        listFocused()
        sportarr.switch("busy", "events=$BUSY")
        refreshByTheMenu()
        val busyRow = SemanticsMatcher("a busy league's row") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(BUSY_CLUB) }
        }
        waitFor(busyRow, BUSY_WAIT_MS)
        remote.press(KeyEvent.KEYCODE_BACK)
        waitForFocusOn(railItem(SPORTS), "its rail item")
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(tvHome)
        waitForFocusOn(railItem("Home"), "the rail's Home, after Back on the rail")
        assertEquals(SPORTS, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, SPORTS, max = 6))
        val opened = SystemClock.elapsedRealtime()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(BUSY_WAIT_MS) { exists(hasText(TODAY)) }
        val firstRowMs = SystemClock.elapsedRealtime() - opened
        listFocused()
        val (frames, slow) = framesOf(compose.activity.window) { repeat(DOWNS) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) } }
        PluginMeasure.say(
            "M-big",
            "tv: the tab's first row ${firstRowMs} ms after OK on its rail item over 5,000 kept events; $DOWNS presses of Down: $slow of " +
                "$frames frames slow (over ${SLOW_FRAME_MS} ms)",
        )
        assertTrue("frames drawn going down", frames > 0)
    }

    /** The ⋮'s Refresh, by remote: Right along the title line to the ⋮, OK, Down to Refresh, OK. */
    private fun refreshByTheMenu() {
        assertEquals(MORE, remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, MORE, max = 12))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(SHOW_WATCHED)
        assertEquals(REFRESH, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Frames [window] drew while [block] ran and a moment after, and how many of them were slow. */
    private fun framesOf(window: Window, block: () -> Unit): Pair<Int, Int> {
        val thread = HandlerThread("frames").apply { start() }
        val frames = AtomicInteger()
        val slow = AtomicInteger()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.incrementAndGet()
            if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > SLOW_FRAME_MS * 1_000_000) slow.incrementAndGet()
        }
        compose.runOnUiThread { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        try {
            block()
            SystemClock.sleep(SETTLE_MS)
        } finally {
            compose.runOnUiThread { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
        return frames.get() to slow.get()
    }

    /** The text field behind [label]'s gate. */
    private fun field(label: String): SemanticsMatcher = hasSetTextAction() and SemanticsMatcher("behind the gate $label") { node ->
        generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(FieldGateLabel) == label }
    }

    private fun chip(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun sideTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun button(words: String) = hasText(words) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; focus on \"${remote.focused()}\"; shown: ${compose.screenWords()}", e)
        }
    }

    private fun waitForFocusOn(matcher: SemanticsMatcher, what: String) {
        try {
            compose.waitUntil(FOCUS_WAIT_MS) { remote.isFocused(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on $what; it is on \"${remote.focused()}\"", e)
        }
    }

    private companion object {
        /** The library's Premier League, and Lantern Bay FC's game, aired yesterday. */
        const val PREMIER_LEAGUE = 1
        const val GAME = 1001

        /** A game with no release of its own on the sports indexer. */
        const val NOTHING_GAME = 1016

        const val SPORTS = "Sports"
        const val RECENT = "Recent"
        const val UPCOMING = "Upcoming"
        const val TODAY = "TODAY"
        const val ALL = "All"
        const val SKIING = "Skiing"

        /** The library's one skiing event, the last sport's. */
        const val SKI_EVENT = 2201
        const val MORE = "More"
        const val SHOW_WATCHED = "Show watched"
        const val REFRESH = "Refresh"
        const val SETTINGS = "Sportarr settings"
        const val TRY_AGAIN = "Try again"
        const val CANT_REACH = "Can't reach Sportarr"
        const val NOT_WATCHED = "Not watched"
        const val ADDRESS = "Sportarr address"
        const val KEY = "API key"
        const val REFUSED = "Sportarr refused this API key. Copy it again from Settings › General › Security in Sportarr."

        /** A day's row as the remote reads it: its start, then its title. */
        val ROW = Regex("""\d\d:\d\d \| .+""")

        const val MINUTE = 60_000L
        const val BUSY = 5_200
        const val BUSY_CLUB = "Kestrel Club"
        const val DOWNS = 30

        /** The fake's answers held this long, each of the read's dozen: the blank cards meanwhile. */
        const val SLOW_MS = 700L

        const val LIST_WAIT_MS = 30_000L
        const val BUSY_WAIT_MS = 90_000L
        const val SEARCH_WAIT_MS = 60_000L
        const val FOCUS_WAIT_MS = 8_000L
        const val KEYBOARD_MS = 1_000L
        const val SETTLE_MS = 1_500L
        const val SLOW_FRAME_MS = 16L
    }
}

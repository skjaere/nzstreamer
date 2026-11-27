package io.skjaere.usenet.app.plugins.sportarr

import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import io.skjaere.usenet.app.plugins.PluginMeasure
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Sports list on a phone (L641, L644, L652), the real plugin over the fake Sportarr's
 * library: Recent as the tab opens, Up next first (a game played part-way, a card's next part), then Today and the days
 * before it, each league a small heading over its rows; the sport and league chips, the choice kept when the tab comes
 * back; "+N more" opening a league's day in place and Show fewer closing it; Show watched; Upcoming; each state as the
 * fake's switches give it (no list and Sportarr failing, a failing page under a kept list, the key refused, nothing
 * followed); the cut line with the fake's busy league, and M-big over it; no canary value anywhere on the screen or in
 * the plugin's files; and the list whole at 1.3 times the text size.
 *
 * Days are the device's: what the tests expect is read from the catalogue's starts in the device's zone, never from the
 * device's date, which is the fake's anchor only until midnight there (the fixtures' days count from the anchor). The watch rows
 * the tests write, and the fake's switches, are put back after each test, and the plugin with its files and secrets
 * removed. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
class SportarrListDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrListDeviceTest")
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        assumeFalse("a TV: its list is SportarrTvTest's", container.television)
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

    private val library get() = sportarr.catalogue

    /** The app at [fontScale] times the text size, with the Sports tab opened. */
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

    /** The list read and shown: Today's header, whatever comes first. */
    private fun listShown() = waitFor(hasText(TODAY), LIST_WAIT_MS)

    /**
     * Recent as the tab opens, Up next first, with a game played part-way (its time left) and a card's next
     * part (the card's count), then Today; yesterday's game under its league's heading.
     */
    @Test
    fun recentOpensWithUpNextThenToday() {
        sportarr.install()
        val game = library.event(GAME)
        val card = library.event(CARD)
        sportarr.progress(game.part(), game.title, 40 * MINUTE, 110 * MINUTE)
        sportarr.watched(card.part("early-prelims"), "${card.title} · Early Prelims")
        openSports()
        listShown()
        waitFor(hasText("Premier League · 1 h 10 min left"))
        assertTrue("Recent picked", exists(sideTab(RECENT) and isSelected()))
        val upNext = top(hasText(UP_NEXT))
        val first = top(hasText(game.title) and hasText("Premier League · 1 h 10 min left"))
        val second = top(hasText("${card.title} · Prelims") and hasText("UFC · 1 of 3 watched"))
        assertTrue("Up next, its in-progress game, then the card's next part", upNext < first && first < second)
        assertTrue("Up next before Today", second < top(hasText(TODAY)))

        // Yesterday's game, under its day and its league's heading.
        val day = header(game.date(zone))
        scrollTo(hasText(day))
        scrollTo(row(game.title))
        assertTrue("its league's heading above it", top(hasText("Premier League")) < top(row(game.title)))
    }

    /**
     * The sport chips (All, then by how many rows each sport has, Soccer and Combat first), a sport picked showing its
     * leagues, a league picked narrowing the days; both kept for the tab, back after Home (view.json, L645); All clears them.
     */
    @Test
    fun chipsFilterTheDaysAndAreKept() {
        sportarr.install()
        openSports()
        listShown()
        val chips = compose.onAllNodes(chip).fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }.map { it.text() }
        assertEquals("the first chips", listOf(ALL, "Soccer", "Combat"), chips.take(3))
        assertTrue("All picked", exists(chip and hasText(ALL) and isSelected()))

        compose.onNode(chip and hasText("Motorsport")).performClick()
        waitFor(chip and hasText(ALL_LEAGUES))
        assertTrue("its leagues", exists(chip and hasText("Formula 1")) && exists(chip and hasText("MotoGP")))
        // At the list's top, where any day's rows would show first: a lazy list composes only what is near, so an absence
        // is looked for there, before scrolling away.
        waitFor(row(KESTREL_GP))
        assertFalse("the Premier League with Motorsport picked", exists(hasText(PREMIER_LEAGUE_NAME)))

        compose.onNode(chip and hasText("MotoGP")).performClick()
        waitFor(chip and hasText("MotoGP") and isSelected())
        waitFor(row(OSKELUND_GP))
        assertFalse("Formula 1's weekend with MotoGP picked", exists(row(KESTREL_GP)))

        // The tab again after Home: the chips as they were.
        compose.onNode(barTab("Home")).performClick()
        waitFor(hasText("Search your indexers") and hasClickAction())
        compose.onNode(barTab(SPORTS)).performClick()
        waitFor(chip and hasText("MotoGP") and isSelected())
        assertTrue("its sport too", exists(chip and hasText("Motorsport") and isSelected()))

        compose.onNode(chip and hasText(ALL)).performClick()
        waitFor(chip and hasText(ALL) and isSelected())
        assertFalse("the leagues' line with All", exists(chip and hasText(ALL_LEAGUES)))
        scrollTo(row(library.event(GAME).title))
    }

    /**
     * L645: the side opens on Recent each time the tab opens. Upcoming is kept on the way back from a page the list opened,
     * and not after Home and the tab again.
     */
    @Test
    fun theSideOpensOnRecentEachTimeTheTabOpens() {
        sportarr.install()
        openSports()
        listShown()
        compose.onNode(sideTab(UPCOMING)).performClick()
        waitFor(sideTab(UPCOMING) and isSelected())

        // A row's page, and Back: the list as it was left.
        val first = anyRow.also { waitFor(it) }
        compose.onAllNodes(first)[0].performClick()
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(sideTab(UPCOMING)) }
        compose.runOnIdle { nav.popBackStack() }
        waitFor(sideTab(UPCOMING) and isSelected())

        compose.onNode(barTab("Home")).performClick()
        waitFor(hasText("Search your indexers") and hasClickAction())
        compose.onNode(barTab(SPORTS)).performClick()
        waitFor(sideTab(RECENT) and isSelected())
        assertFalse("Upcoming kept across the tabs", exists(sideTab(UPCOMING) and isSelected()))
    }

    /** Five rows a league a day, "+N more" opening the day in place, Show fewer closing it. */
    @Test
    fun moreOpensTheLeaguesDayAndShowFewerClosesIt() {
        sportarr.install()
        val games = gamesOnTheDayOf(GAME)
        assertTrue("the library's Premier League has more than five games the day of its game: ${games.size}", games.size > 5)
        openSports()
        listShown()
        val more = "+${games.size - 5} more"
        scrollTo(hasText(more))
        games.take(5).forEach { scrollTo(row(it.title)) }
        assertFalse("the sixth before +N more", exists(row(games[5].title)))

        compose.onNode(button(more)).performScrollTo().performClick()
        waitFor(hasText(SHOW_FEWER))
        games.forEach { scrollTo(row(it.title)) }
        compose.onNode(button(SHOW_FEWER)).performScrollTo().performClick()
        waitFor(hasText(more))
        assertFalse("the sixth after Show fewer", exists(row(games[5].title)))
    }

    /** A watched game is left out until the ⋮'s Show watched, then in its place with its ✓; Hide watched. */
    @Test
    fun showWatchedPutsWatchedRowsBackWithTheirTick() {
        sportarr.install()
        val games = gamesOnTheDayOf(GAME)
        val watched = library.event(WATCHED_GAME)
        sportarr.watched(watched.part(), watched.title)
        openSports()
        listShown()
        scrollTo(hasText("+${games.size - 6} more"))
        compose.onNode(button("+${games.size - 6} more")).performScrollTo().performClick()
        waitFor(hasText(SHOW_FEWER))
        assertFalse("a watched game shown", exists(row(watched.title)))

        menu(SHOW_WATCHED)
        scrollTo(row(watched.title))
        assertTrue("its ✓", exists(row(watched.title) and hasText(TICK)))
        compose.onNode(list).performScrollToKey(TOP_KEY)
        compose.onNode(hasContentDescription(MORE) and hasClickAction()).performClick()
        waitFor(hasText(HIDE_WATCHED) and hasAnyAncestor(isPopup()))
        compose.onNode(hasText(HIDE_WATCHED) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(row(watched.title)) }
    }

    /** Upcoming, soonest first: its first day, the next game under its day, and a weekend still to come by its first session. */
    @Test
    fun upcomingListsWhatsStillToStart() {
        sportarr.install()
        openSports()
        listShown()
        compose.onNode(sideTab(UPCOMING)).performClick()
        waitFor(sideTab(UPCOMING) and isSelected())
        val upcoming = library.upcoming(Instant.now())
        // Soonest first: the first day still to come heads the list, nothing that has aired above it. Looked for at the
        // top, as a lazy list composes only what is near.
        waitFor(hasText(header(upcoming.first().date(zone))))
        val aired = library.event(GAME)
        assertFalse("yesterday's game on Upcoming", exists(row(aired.title)))
        assertFalse("its day on Upcoming", exists(hasText(header(aired.date(zone)))))
        val next = upcoming.first { it.leagueId == PREMIER_LEAGUE }
        scrollTo(hasText(header(next.date(zone))))
        scrollTo(row(next.title))
        scrollTo(row(TARNHOLM_GP))
        assertTrue("its round, first session and sessions", exists(row(TARNHOLM_GP) and hasText("Round 21 · Practice 1 first · 5 sessions")))
    }

    /** With no list kept and Sportarr failing, the card in the list's place says why, and Try again reads the list. */
    @Test
    fun withNoListKeptAFailingSportarrGetsTheCard() {
        sportarr.install()
        sportarr.switch("fail", "status=500&route=leagues")
        openSports()
        waitFor(hasText(CANT_REACH), LIST_WAIT_MS)
        assertTrue("why", exists(hasText("Sportarr answered with an error. Try again in a moment.")))
        sportarr.off("fail")
        compose.onNode(button(TRY_AGAIN)).performClick()
        listShown()
        assertFalse("the card with the list", exists(hasText(CANT_REACH)))
    }

    /** A page that fails under a kept list: the line over the list, with Try again, the list kept under it (L641). */
    @Test
    fun aFailingPageKeepsTheListUnderItsLine() {
        sportarr.install()
        openSports()
        listShown()
        sportarr.switch("fail", "status=500&route=events&page=1")
        menu(REFRESH)
        waitFor(hasText("Can't reach Sportarr · list from", substring = true), LIST_WAIT_MS)
        assertTrue("its Try again", exists(button(TRY_AGAIN)))
        scrollTo(row(library.event(GAME).title))
        sportarr.off("fail")
        compose.onNode(button(TRY_AGAIN)).performScrollTo().performClick()
        compose.waitUntil(LIST_WAIT_MS) { !exists(hasText("Can't reach Sportarr · list from", substring = true)) }
    }

    /** The key refused (changed in Sportarr): its line over the kept list, and Sportarr settings in Try again's place. */
    @Test
    fun aRefusedKeyKeepsTheListWithSportarrSettings() {
        sportarr.install()
        openSports()
        listShown()
        sportarr.switch("refuse-key")
        menu(REFRESH)
        waitFor(hasText("Sportarr refused the API key · list from", substring = true), LIST_WAIT_MS)
        scrollTo(row(library.event(GAME).title))
        // The line's button: the ⋮'s menu, which has the same words, is closed.
        compose.onNode(button(SETTINGS)).performScrollTo().performClick()
        waitFor(hasText(SAVED))
    }

    /** A Sportarr that follows nothing says so, with Refresh, and the line about events with no league. */
    @Test
    fun aSportarrThatFollowsNothingSaysSo() {
        sportarr.install()
        openSports()
        listShown()
        sportarr.switch("empty")
        menu(REFRESH)
        waitFor(hasText(NOTHING_FOLLOWED), LIST_WAIT_MS)
        assertTrue("its line", exists(hasText(NOTHING_FOLLOWED_LINE)))
        assertTrue("its footnote", exists(hasText(NO_LEAGUE)))
        sportarr.off("empty")
        // The card's button: the ⋮'s menu, which has the same words, is closed.
        compose.onNode(button(REFRESH)).performClick()
        listShown()
    }

    /**
     * The fake's busy league, past the 5,000 kept and the page budget: the cut line ends Recent.
     * M-big: the time from the tab's first frame to its first row with 5,000 events kept, opened afresh (the plugin made
     * again, as after the app's start, its kept list read from its file and built) and again while it runs; and the share
     * of slow frames in one fling, through the instrumentation's status.
     */
    @Test
    fun theBusyLeagueEndsRecentWithTheCutLine() {
        sportarr.install()
        openSports()
        listShown()
        // The same fling over the library's few dozen events first: how the emulator draws a fling at all.
        val (fewFrames, fewSlow) = framesOf(compose.activity.window) {
            compose.onNode(list).performTouchInput { swipeUp(durationMillis = FLING_MS) }
            compose.waitForIdle()
        }
        sportarr.switch("busy", "events=$BUSY")
        menu(REFRESH)
        waitFor(row(BUSY_CLUB, substring = true), BUSY_WAIT_MS)
        compose.onNode(list).performScrollToKey(CUT_KEY)
        waitFor(hasText(CUT))

        // The tab again: its first frame to its first row drawn, over the 5,000 kept.
        compose.onNode(barTab("Home")).performClick()
        waitFor(hasText("Search your indexers") and hasClickAction())
        val opened = SystemClock.elapsedRealtime()
        compose.onNode(barTab(SPORTS)).performClick()
        compose.waitUntil(BUSY_WAIT_MS) { exists(row(BUSY_CLUB, substring = true)) }
        val firstRowMs = SystemClock.elapsedRealtime() - opened
        val (frames, slow) = framesOf(compose.activity.window) {
            compose.onNode(list).performTouchInput { swipeUp(durationMillis = FLING_MS) }
            compose.waitForIdle()
        }

        // Opened afresh: the plugin removed and installed again, its kept list put back in its folder, the tab opened.
        val kept = File(context.cacheDir, "sportarr-busy-list.json")
        File(sportarr.dataDir, LIST_FILE).copyTo(kept, overwrite = true)
        compose.onNode(barTab("Home")).performClick()
        waitFor(hasText("Search your indexers") and hasClickAction())
        try {
            runBlocking { plugins.app.remove(SPORTARR) }
            compose.waitUntil(PLUGIN_WAIT_MS) { !exists(barTab(SPORTS)) }
            sportarr.install()
            kept.copyTo(File(sportarr.dataDir, LIST_FILE), overwrite = true)
        } finally {
            kept.delete()
        }
        waitFor(barTab(SPORTS))
        val afresh = SystemClock.elapsedRealtime()
        compose.onNode(barTab(SPORTS)).performClick()
        compose.waitUntil(BUSY_WAIT_MS) { exists(row(BUSY_CLUB, substring = true)) }
        val coldMs = SystemClock.elapsedRealtime() - afresh
        PluginMeasure.say(
            "M-big",
            "phone: the tab's first row ${coldMs} ms after it was opened afresh over 5,000 kept events (${firstRowMs} ms opened again while the " +
                "plugin runs); one fling: $slow of $frames frames slow (over ${SLOW_FRAME_MS} ms), against $fewSlow of $fewFrames over the library alone",
        )
        assertTrue("frames drawn in the fling", frames > 0)
    }

    /**
     * No canary value of the fake's (scores, statuses, images, venues, files) anywhere on Recent, Upcoming or with Show
     * watched, every row of every day brought into view in turn, or in the plugin's files.
     */
    @Test
    fun noCanaryValueShowsOrIsKept() {
        sportarr.install()
        val game = library.event(GAME)
        sportarr.progress(game.part(), game.title, 40 * MINUTE, 110 * MINUTE)
        openSports()
        listShown()
        assertEquals("on Recent", emptyList<String>(), wholeListsCanaries())
        menu(SHOW_WATCHED)
        assertEquals("with Show watched", emptyList<String>(), wholeListsCanaries())
        compose.onNode(list).performScrollToKey(TOP_KEY)
        compose.onNode(sideTab(UPCOMING)).performClick()
        waitFor(sideTab(UPCOMING) and isSelected())
        assertEquals("on Upcoming", emptyList<String>(), wholeListsCanaries())
        val files = sportarr.dataDir.listFiles().orEmpty().filter { it.isFile }
        assertTrue("its kept list: ${files.map { it.name }}", files.any { it.name == "list.json" })
        files.forEach { file -> assertFalse("a canary value in ${file.name}", file.readText().contains(SportsFakes.CANARY, ignoreCase = true)) }
    }

    /** At 1.3 times the text size the list's words are whole: none cut, none off the screen's side but in a line of chips. */
    @Test
    fun theListAtLargeTextIsWhole() {
        sportarr.install()
        val card = library.event(CARD)
        sportarr.watched(card.part("early-prelims"), "${card.title} · Early Prelims")
        openSports(fontScale = 1.3f)
        listShown()
        waitFor(hasText("UFC · 1 of 3 watched"))
        assertEquals("at 1.3x as the tab opens", emptyList<String>(), cutTexts())
        scrollTo(row(library.event(GAME).title))
        assertEquals("at 1.3x on yesterday's rows", emptyList<String>(), cutTexts())
    }

    private fun today(): LocalDate = LocalDate.now(zone)

    /** A day's header as the phone writes it, upper-cased: Today, Yesterday, else "Mon 23 Nov". */
    private fun header(date: LocalDate): String = dayHeader(date, today())

    /** The Premier League's listed games on the day [event] shows under, in time order. */
    private fun gamesOnTheDayOf(event: Int): List<CatalogueEvent> {
        val day = library.event(event).date(zone)
        return library.league(PREMIER_LEAGUE).events.filter { it.listed && it.date(zone) == day }.sortedBy { it.start }
    }

    /** Opens the ⋮, at the list's top, and presses its [item]. */
    private fun menu(item: String) {
        compose.onNode(list).performScrollToKey(TOP_KEY)
        compose.onNode(hasContentDescription(MORE) and hasClickAction()).performClick()
        val inMenu = hasText(item) and hasAnyAncestor(isPopup()) and hasClickAction()
        waitFor(inMenu)
        compose.onNode(inMenu).performClick()
        compose.waitForIdle()
    }

    /**
     * The canary values on the screen as the list shows each of its items in turn, from the top: a lazy list composes only
     * the items near its view, so a scan of the screen alone sees the first screenful. Back at the top after.
     */
    private fun wholeListsCanaries(): List<String> {
        val found = linkedSetOf<String>()
        compose.onNode(list).performScrollToKey(TOP_KEY)
        var index = 0
        while (true) {
            compose.waitForIdle()
            found += compose.canaries()
            index += SCAN_STEP
            // Past the last item the list refuses the scroll: every item has been in view.
            if (runCatching { compose.onNode(list).performScrollToIndex(index) }.isFailure) break
        }
        compose.onNode(list).performScrollToKey(TOP_KEY)
        return found.toList()
    }

    /** [matcher] brought into the list's view. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(PLUGIN_WAIT_MS) { exists(list) }
            compose.onAllNodes(list)[0].performScrollToNode(matcher)
        } catch (e: AssertionError) {
            throw AssertionError("not in the list: ${matcher.description}", e)
        }
    }

    private fun top(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        ?: throw AssertionError("not shown: ${matcher.description}")

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
            // The fling's last frames, and their metrics' way back.
            SystemClock.sleep(FLING_SETTLE_MS)
        } finally {
            compose.runOnUiThread { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
        return frames.get() to slow.get()
    }

    /** Each text whose words are cut, laid out taller than its room or ended in an ellipsis, or off the screen's side outside a line of chips. */
    private fun cutTexts(): List<String> {
        val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
        val problems = mutableListOf<String>()
        fun walk(node: SemanticsNode, sideways: Boolean) {
            val across = sideways || node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val layout = node.textLayout()
            if (words != null && layout != null) {
                if (layout.size.height < layout.multiParagraph.height - 1) problems += "\"$words\" clipped"
                if (layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)) problems += "\"$words\" ends in an ellipsis"
                val off = node.boundsInRoot.right > screen.right + 1 || node.boundsInRoot.left < screen.left - 1
                if (!across && off) problems += "\"$words\" off the screen's side"
            }
            node.children.forEach { walk(it, across) }
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { walk(it, false) }
        return problems
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }

    private fun SemanticsNode.text(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the bar") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    /** Recent or Upcoming in the list's title line: a tab of the switch, under the list's title. */
    private fun sideTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the list's title line") { it.boundsInRoot.top < it.layoutInfo.density.density * TITLE_LINE_DP }

    /** A sport's or league's chip. */
    private val chip = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** An event's or weekend's row in a day, by its title: its start's time beside it, which Up next's rows don't have. */
    private fun row(title: String, substring: Boolean = false) = hasText(title, substring = substring) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
        SemanticsMatcher("with its time") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { TIME.matches(it.text) } }

    /** Any event's or weekend's row in a day. */
    private val anyRow = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
        SemanticsMatcher("with its time") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { TIME.matches(it.text) } }

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** The list itself: the column that scrolls up and down. */
    private val list = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}; shown: ${compose.screenWords()}", e)
        }
    }

    private companion object {
        /** The Premier League, its game of Lantern Bay FC, yesterday's earliest, and UFC 999's card. */
        const val PREMIER_LEAGUE = 1
        const val PREMIER_LEAGUE_NAME = "Premier League"
        const val GAME = 1001
        const val WATCHED_GAME = 1002
        const val CARD = 1901
        const val KESTREL_GP = "Grand Prix of Kestrel"
        const val OSKELUND_GP = "Grand Prix of Oskelund"
        const val TARNHOLM_GP = "Grand Prix of Tarnholm"

        /** The fake's busy league's games, past the cap, and their clubs' names. */
        const val BUSY = 5_200
        const val BUSY_CLUB = "Kestrel Club"

        const val SPORTS = "Sports"
        const val RECENT = "Recent"
        const val UPCOMING = "Upcoming"
        const val TODAY = "TODAY"
        const val UP_NEXT = "UP NEXT"
        const val MORE = "More"
        const val ALL = "All"
        const val ALL_LEAGUES = "All leagues"
        const val SHOW_WATCHED = "Show watched"
        const val HIDE_WATCHED = "Hide watched"
        const val REFRESH = "Refresh"
        const val SETTINGS = "Sportarr settings"
        const val SAVED = "Saved"
        const val SHOW_FEWER = "Show fewer"
        const val TICK = "✓"
        const val TRY_AGAIN = "Try again"
        const val CANT_REACH = "Can't reach Sportarr"
        const val NOTHING_FOLLOWED = "Your Sportarr follows nothing yet"
        const val NOTHING_FOLLOWED_LINE =
            "Sports lists the leagues you switched on in Sportarr, with the teams and sessions you chose there. Switch one on in Sportarr, then Refresh."
        const val NO_LEAGUE = "Events with no league in Sportarr don't show here: give them one there."
        const val CUT = "Sports keeps the newest 5,000 events: older ones aren't shown."

        /** The title line's and the cut line's items in the list. */
        const val TOP_KEY = "top"
        const val CUT_KEY = "cut"

        /** The plugin's kept list in its folder (L645). */
        const val LIST_FILE = "list.json"

        /** Items the canary scan moves on at a time: fewer than a screenful of the list's shortest. */
        const val SCAN_STEP = 2

        const val MINUTE = 60_000L

        /** A row's start, as the day rows write it. */
        val TIME = Regex("""\d\d:\d\d""")

        /** How far down the title line ends, in dp: Recent and Upcoming sit in it. */
        const val TITLE_LINE_DP = 160f

        const val LIST_WAIT_MS = 30_000L
        const val BUSY_WAIT_MS = 90_000L
        const val FLING_MS = 150L
        const val FLING_SETTLE_MS = 1_500L

        /** A frame slower than a 60 Hz screen's. */
        const val SLOW_FRAME_MS = 16L
    }
}

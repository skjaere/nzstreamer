package io.skjaere.usenet.app.plugins.sportarr

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.PortChoice
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginPage1
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.search.PossibleMatches
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * The event pages on a phone (L647, L652, L653, L654), the real plugin over the
 * fake Sportarr's library: a game's page aired and not watched, with NZStreamer's Cast button in its top bar, played
 * part-way (Resume, what is left), marked watched and unwatched, and before its start; a weekend's sessions with their ⋮;
 * a card's parts, a UFC card's with its bouts and a premium card's with the footnote as before; Generic's page, which you
 * pick from. With the sports indexer: Search indexers opens Search with the
 * game's words and the scores note; Play with only possible matches opens Choose release with their reasons; Play with
 * nothing shows the no-release sheet with the indexer's line and the note, and the page keeps its line. With the mock:
 * Play plays an accepted release, its progress kept under item:sportarr:ev-…:full. No canary value on any page.
 *
 * The watch rows, the indexer and provider the tests set, and the fake's switches are put back after each test, and the
 * plugin with its files and secrets removed. TMDB is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SportarrPagesDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrPagesDeviceTest")
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        assumeFalse("a TV: its pages are SportarrTvPagesTest's", container.television)
        sportarr.assumeUp()
        home.show()
        tmdb = FakeTmdb(strict = true)
        sportarr.hold()
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

    /** The app, then the plugin's [page] with [key] over Home, as a row of the list opens it. */
    private fun openPage(page: String, key: String) {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        waitFor(hasText("Search your indexers") and hasClickAction())
        compose.runOnIdle { nav.navigate(PluginPage1(page, key)) }
    }

    private fun openEvent(id: Int) = openPage(EVENT, library.event(id).key)

    /** Aired, not watched: its title, league and when, Play, Choose release, Search indexers, Mark watched, the footnote, the Cast button. */
    @Test
    fun aGamesPageAiredAndNotWatched() {
        sportarr.install()
        val game = library.event(GAME)
        openEvent(GAME)
        waitFor(hasText(game.title))
        assertTrue("its line", exists(hasText("Premier League · ${dayAndTime(game.start)}")))
        assertTrue("not watched", exists(hasText(NOT_WATCHED)))
        listOf(PLAY, CHOOSE, SEARCH).forEach { assertTrue("its $it", exists(button(it))) }
        assertTrue("Mark watched", exists(button(MARK_WATCHED)))
        assertTrue("its footnote", exists(hasText(GAME_FOOTNOTE)))
        assertTrue("NZStreamer's Cast button in its top bar (L654)", exists(hasContentDescription(CAST) and hasClickAction()))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /**
     * L654: with a TV chosen to play on, a plugin's page's Cast button keeps This phone's face, as Search's and the
     * release lists' do: a plugin's item plays on the phone then. The TV is an invented one at a name that never resolves.
     */
    @Test
    fun theCastButtonSaysThisPhoneWithATvChosen() {
        sportarr.install()
        val tv = KeyId.of(Random.nextBytes(TV_KEY_BYTES))
        // This phone's link identity first: one made later would start with no TVs kept.
        runBlocking { container.data.link.identity() }
        assertTrue("the TV kept", runBlocking { container.data.link.addTv(tv, "Living room TV", "living-room-tv.invalid", PortChoice.DEFAULT) })
        try {
            container.playOn.choose(tv)
            compose.waitUntil(10_000) { container.playOn.target.value is PlayTarget.Tv }
            openEvent(GAME)
            waitFor(hasText(library.event(GAME).title))
            assertTrue("This phone's face", exists(hasContentDescription(CAST) and hasClickAction()))
            assertFalse("the TV's face", exists(hasContentDescription("Play on: Living room TV", substring = true)))
        } finally {
            container.playOn.choose(PlayChoice.Phone)
            runBlocking { container.data.link.forgetTv(tv) }
        }
    }

    /** Played part-way, what is left with its bar, and Resume in Play's place. */
    @Test
    fun aGamePlayedPartWayResumes() {
        sportarr.install()
        val game = library.event(GAME)
        sportarr.progress(game.part(), game.title, 68 * MINUTE, 110 * MINUTE)
        openEvent(GAME)
        waitFor(hasText("42 min left"))
        assertTrue("Resume", exists(button(RESUME)))
        assertFalse("Play beside Resume", exists(button(PLAY)))
        assertEquals("canary values, played part-way", emptyList<String>(), compose.canaries())
    }

    /** Mark watched, ✓ Watched, Play again, Mark unwatched; and back again, the row kept under the part's key. */
    @Test
    fun markWatchedThenUnwatched() {
        sportarr.install()
        val game = library.event(GAME)
        sportarr.forget(game.part())
        openEvent(GAME)
        compose.onNode(button(MARK_WATCHED).also { waitFor(it) }).performScrollTo().performClick()
        waitFor(hasText(WATCHED))
        assertTrue("Play again", exists(button(PLAY_AGAIN)))
        assertEquals("canary values, watched", emptyList<String>(), compose.canaries())
        compose.waitUntil(5_000) { sportarr.state(game.part())?.watched == true }
        assertEquals("kept as a plugin's item", WatchKind.Item, sportarr.state(game.part())!!.kind)
        compose.onNode(button(MARK_UNWATCHED)).performScrollTo().performClick()
        waitFor(hasText(NOT_WATCHED))
        assertTrue("Play", exists(button(PLAY)))
        compose.waitUntil(5_000) { sportarr.state(game.part())?.watched == false }
    }

    /** Before its start, when it starts, its line in the buttons' place and no buttons. */
    @Test
    fun aGameBeforeItsStart() {
        sportarr.install()
        val game = library.event(LATER_GAME)
        openEvent(LATER_GAME)
        waitFor(hasText(game.title))
        waitFor(hasText(starts(game.start)))
        assertTrue("its line", exists(hasText(GAME_NOT_STARTED)))
        listOf(PLAY, CHOOSE, SEARCH).forEach { assertFalse("its $it before the start", exists(button(it))) }
        assertTrue("its footnote", exists(hasText(GAME_FOOTNOTE)))
        assertEquals("canary values, before the start", emptyList<String>(), compose.canaries())
    }

    /**
     * A weekend's page: the round's sessions in time order, each with its day and time, the next to play on the top button,
     * a session's ⋮ with Choose release, Search indexers and Mark watched; its footnote.
     */
    @Test
    fun aWeekendsPageListsItsSessions() {
        sportarr.install()
        val sessions = library.events.filter { it.leagueId == FORMULA_1 && it.round == "20" }.sortedBy { it.start }
        openPage(WEEKEND, "$FORMULA_1:r20")
        waitFor(hasText(KESTREL_GP))
        assertTrue("its line", exists(hasText("Formula 1 · Round 20 · ${span(sessions.first().start, sessions.last().start)}")))
        assertTrue("the first session to play", exists(button("Play Practice 1")))
        listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race").zip(sessions).forEach { (label, session) ->
            assertTrue("$label, ${dayAndTime(session.start)}", exists(hasText(label) and hasText(dayAndTime(session.start))))
        }
        compose.onNode(hasContentDescription("More for Race") and hasClickAction()).performScrollTo().performClick()
        val inMenu = hasAnyAncestor(isPopup()) and hasClickAction()
        waitFor(hasText(CHOOSE) and inMenu)
        assertTrue("its Search indexers and Mark watched", exists(hasText(SEARCH) and inMenu) && exists(hasText(MARK_WATCHED) and inMenu))
        assertTrue("its footnote", exists(hasText("Tap a session to play the best release, or ⋮ to choose one yourself. Details from your Sportarr.")))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /**
     * A card's page: UFC 999's parts in card order, as Sportarr names them, the first to play on the top button; its bouts
     * under them once the fake Wikipedia's card is read (SportarrBoutsDeviceTest's), and the footnote that names them.
     */
    @Test
    fun aCardsPageListsItsParts() {
        sportarr.install()
        val card = library.event(CARD)
        openEvent(CARD)
        waitFor(hasText(card.title))
        assertTrue("its line", exists(hasText("UFC · ${dayAndTime(card.start)}")))
        assertTrue("its count", exists(hasText("0 of 3 watched")))
        assertTrue("the first part to play", exists(button("Play Early Prelims")))
        // Its card read from the fake Wikipedia, its main event last: the page as it stays.
        waitFor(hasText("Quessel vs. Halde") and hasClickAction())
        val tops = listOf("Early Prelims", "Prelims", "Main Card").map { label ->
            val row = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            compose.onAllNodes(row).fetchSemanticsNodes().single().boundsInRoot.top
        }
        assertEquals("in card order", tops.sorted(), tops)
        scrollTo(hasText(BOUTS_FOOTNOTE))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** A card that reads none from Wikipedia (a premium card's, not UFC's): its parts, and the footnote as before. */
    @Test
    fun aPremiumCardsPageKeepsItsFootnote() {
        sportarr.install()
        val card = library.event(PREMIUM_CARD)
        openEvent(PREMIUM_CARD)
        waitFor(hasText(card.title))
        listOf("Pre-Show", "Main Show").forEach { assertTrue("its $it", exists(hasText(it) and hasClickAction())) }
        scrollTo(hasText(CARD_FOOTNOTE))
        assertFalse("no credit", exists(hasText("Fight card from Wikipedia")))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** Generic's page: Choose release in Play's place, "You pick the release", its footnote; never Play. */
    @Test
    fun genericsPageIsYoursToPick() {
        sportarr.install()
        val event = library.event(GENERIC)
        openEvent(GENERIC)
        waitFor(hasText(event.title))
        assertTrue("You pick the release", exists(hasText("You pick the release")))
        assertTrue("Choose release", exists(button(CHOOSE)))
        assertFalse("Play", exists(button(PLAY)))
        assertTrue("its footnote", exists(hasText(GENERIC_FOOTNOTE)))
        assertEquals("canary values", emptyList<String>(), compose.canaries())
    }

    /** Search indexers opens Search with the game's two sides' words, and the scores note above the results. */
    @Test
    fun searchIndexersOpensSearchWithTheScoresNote() {
        sportarr.assumeUp(indexer = true)
        sportarr.install()
        sportarr.indexers(plays = false)
        openEvent(GAME)
        compose.onNode(button(SEARCH).also { waitFor(it) }).performScrollTo().performClick()
        waitFor(hasText("Releases for “Lantern Bay Copper Crown” on your indexers"), SEARCH_WAIT_MS)
        waitFor(hasText(SCORES, substring = true))
    }

    /**
     * Play with nothing for the game: the host's sheet over the page with the sports indexer's line and the
     * plugin's note; closed, the page keeps "No release at …".
     */
    @Test
    fun playWithNothingShowsTheSheetAndThePageKeepsItsLine() {
        sportarr.assumeUp(indexer = true)
        sportarr.install()
        sportarr.indexers(plays = false)
        val game = library.event(NOTHING_GAME)
        sportarr.forget(game.part())
        openEvent(NOTHING_GAME)
        sportarr.paceSearches()
        compose.onNode(button(PLAY).also { waitFor(it) }).performScrollTo().performClick()
        val inSheet = hasAnyAncestor(isDialog())
        waitFor(hasText("No playable release found") and inSheet, SEARCH_WAIT_MS)
        assertTrue("the indexer's line", exists(hasText("none for this game", substring = true) and inSheet))
        assertTrue("the note", exists(hasText("Releases often appear a few hours after a game ends. Try Play again later.") and inSheet))
        compose.onNode(button("Close") and inSheet).performClick()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        waitFor(hasText("No release at ", substring = true))
        val kept = hasText("No release at ", substring = true) and hasText("Releases often appear a few hours after a game ends.", substring = true)
        assertTrue("its words", exists(kept))
        assertTrue("announced as it comes, as Test's answer is", exists(kept and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)))
        assertEquals("canary values, with its line", emptyList<String>(), compose.canaries())
    }

    /** (mock) Play with possible matches alone opens Choose release with them and their reasons, and the left-out line. */
    @Test
    fun playWithOnlyPossibleMatchesOpensChooseRelease() {
        sportarr.assumeUp(plays = true)
        sportarr.install()
        sportarr.indexers(plays = true)
        val game = library.event(OFFERED_GAME)
        sportarr.forget(game.part())
        openEvent(OFFERED_GAME)
        sportarr.paceSearches()
        compose.onNode(button(PLAY).also { waitFor(it) }).performScrollTo().performClick()
        // The heading as the kit's section headers draw it, upper-cased.
        waitFor(hasText(PossibleMatches.HEADING.uppercase()), SEARCH_WAIT_MS)
        val offered = library.found(OFFERED_GAME, "full", "offer")
        assertTrue("the library offers two: ${offered.map { it.first.id }}", offered.size == 2)
        offered.forEach { (release, verdict) ->
            assertTrue("${release.id} with its reason", exists(hasText(release.name, substring = true)) && exists(hasText(verdict.reason!!, substring = true)))
        }
        assertTrue("the left-out line", exists(hasText("1 left out: a name with a score. Their names aren't shown.", substring = true)))
        container.playback.cancel()
    }

    /**
     * (mock) Play plays one of the releases the plugin accepts for the game (the host's pick among them), from the sports
     * indexer and the mock; the player keeps where it stopped and the release under item:sportarr:ev-…:full. Nothing of
     * the app's screens is read after the player: as it closes, the test's activity under it is made again for the phone
     * turned back upright, without them.
     */
    @Test
    fun playPlaysAnAcceptedReleaseAndKeepsItsProgress() {
        sportarr.assumeUp(plays = true)
        sportarr.install()
        sportarr.indexers(plays = true)
        val game = library.event(GAME)
        val key = WatchKey.item(SPORTARR, game.part())
        sportarr.forget(game.part())
        openEvent(GAME)
        val pressedAt = System.currentTimeMillis() - 60_000
        sportarr.paceSearches()
        compose.onNode(button(PLAY).also { waitFor(it) }).performScrollTo().performClick()
        val launch = launchOf(key)
        val accepted = library.found(GAME, "full", "accept").map { it.first }.filter { it.whole }.map { it.name }
        assertTrue("one the plugin accepts: ${launch.release.title}", launch.release.title in accepted)
        assertEquals("its title", game.title, launch.watch.title)
        val stoppedAt = playAndStop(launch)
        val saved = waitFor("the player's save as it closed") { sportarr.state(game.part())?.takeIf { it.lastPlayedAt >= pressedAt } }
        assertEquals("kept as a plugin's item", WatchKind.Item, saved.kind)
        assertTrue("where it stopped (${saved.positionMs} ms, $stoppedAt ms)", kotlin.math.abs(saved.positionMs - stoppedAt) < 5_000)
        assertFalse("in progress, not watched", saved.watched)
        assertEquals("the release that played it remembered", launch.release.nzbId, saved.release?.nzbId)
    }

    /** The player over [launch], stopped three quarters in, past Up next's minute and before watched: where it stopped. */
    private fun playAndStop(launch: PlayerLaunch): Long = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
        val duration = waitFor("the player to be ready") {
            var length: Long? = null
            scenario.onActivity { a -> length = a.player?.takeIf { it.playbackState == Player.STATE_READY }?.duration?.takeIf { it > 0 } }
            length
        }
        val target = duration * 3 / 4
        assertTrue("the sample clip runs $duration ms: too short to be in progress", target >= LibraryRepository.MIN_RESUME_MS)
        scenario.onActivity { a ->
            a.player!!.seekTo(target)
            a.player!!.pause()
        }
        waitFor("the player at ${target / 1000} s") {
            var at: Long? = null
            scenario.onActivity { a -> at = a.player?.currentPosition?.takeIf { kotlin.math.abs(it - target) < 2_000 } }
            at
        }
    }

    /** The coordinator's next launch for [key], taken here so no player opens on its own; a failure as soon as the sheet shows one. */
    private fun launchOf(key: String): PlayerLaunch = runBlocking {
        withTimeout(90_000) {
            merge(
                container.playback.launches.filter { it.watchKey == key },
                container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("didn't start: $it") },
            ).first()
        }
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun today(): LocalDate = LocalDate.now(zone)

    /** When an event started, as a page says it: "Tue 24 Nov · 16:00", the year after the month in another year. */
    private fun dayAndTime(at: Instant): String = "${day(at.atZone(zone).toLocalDate())} · ${time(at)}"

    private fun day(date: LocalDate) = "${DAYS[date.dayOfWeek.value - 1]} ${shortDay(date)}"

    private fun shortDay(date: LocalDate) = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}" + if (date.year != today().year) " ${date.year}" else ""

    private fun time(at: Instant): String = at.atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /** When an event not started yet starts: today, a day of the six after today by its name, else its date. */
    private fun starts(at: Instant): String {
        val date = at.atZone(zone).toLocalDate()
        val on = when (val days = ChronoUnit.DAYS.between(today(), date)) {
            0L -> "today"
            in 1L..6L -> DAYS[date.dayOfWeek.value - 1]
            else -> shortDay(date).also { check(days > 6) }
        }
        return "Starts $on ${time(at)}"
    }

    /** A weekend's days, first to last: "20–22 Nov", across months "30 Oct–1 Nov". */
    private fun span(first: Instant, last: Instant): String {
        val (a, b) = first.atZone(zone).toLocalDate() to last.atZone(zone).toLocalDate()
        return when {
            a == b -> shortDay(b)
            a.year != b.year -> "${a.dayOfMonth} ${MONTHS[a.monthValue - 1]} ${a.year}–${b.dayOfMonth} ${MONTHS[b.monthValue - 1]} ${b.year}"
            a.month == b.month -> "${a.dayOfMonth}–${shortDay(b)}"
            else -> "${a.dayOfMonth} ${MONTHS[a.monthValue - 1]}–${shortDay(b)}"
        }
    }

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** The page's [matcher] brought into view, its list scrolled to it: a card's page with its bouts is taller than the screen. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown: ${matcher.description}; shown: ${compose.screenWords()}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
    }

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
        const val WEEKEND = "weekend"

        /**
         * The library's: Lantern Bay FC's game, aired yesterday; a game six days on; a game with possible matches alone; one with
         * nothing for it; UFC 999's card; a wrestling promotion's premium card; the skiing event Generic lists; Formula 1's
         * league.
         */
        const val GAME = 1001
        const val LATER_GAME = 1014
        const val OFFERED_GAME = 1017
        const val NOTHING_GAME = 1016
        const val CARD = 1901
        const val PREMIUM_CARD = 2004
        const val GENERIC = 2201
        const val FORMULA_1 = 7
        const val KESTREL_GP = "Grand Prix of Kestrel"

        const val NOT_WATCHED = "Not watched"
        const val WATCHED = "✓ Watched"
        const val PLAY = "Play"
        const val RESUME = "Resume"
        const val PLAY_AGAIN = "Play again"
        const val CHOOSE = "Choose release"
        const val SEARCH = "Search indexers"
        const val MARK_WATCHED = "Mark watched"
        const val MARK_UNWATCHED = "Mark unwatched"
        const val CAST = "Play on: this phone"
        const val SCORES = "Indexer results can show scores."
        const val GAME_FOOTNOTE =
            "Play finds the best release on your indexers that names both teams and the date; the one that played before goes first. Details from your Sportarr."
        const val GAME_NOT_STARTED =
            "Play, Choose release and Search indexers show once it has started. Releases often appear a few hours after the end."
        const val CARD_FOOTNOTE = "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val BOUTS_FOOTNOTE =
            "Tap a part, or a bout in it, to play the part's best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val GENERIC_FOOTNOTE =
            "NZStreamer can't tell this sport's releases apart for sure, so you pick one; the one you played goes first next time. Details from your Sportarr."

        val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        const val MINUTE = 60_000L

        /** A TV's link key, as the platform's tests make an invented one. */
        const val TV_KEY_BYTES = 91

        /** A search of the sports indexer, its stages one after the other. */
        const val SEARCH_WAIT_MS = 60_000L
    }
}

package io.skjaere.usenet.app.plugins.sportarr

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
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
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.plugins.PLUGIN_WAIT_MS
import io.skjaere.usenet.app.plugins.PluginPage1
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The plugin's plays through NZStreamer on a phone (L644, L647, L652, L653), the real plugin over the fake Sportarr, the
 * sports indexer and the mock: a play per family, judged by what the player keeps under item:sportarr:… (a weekend's
 * session from the round's one release, its file picked by the plugin; a card's main card from the full event, chosen in
 * Choose release; a basketball game; the skiing event Generic lists, picked from its possible matches); a tap on the tab's
 * Up next playing its part; Home's Up next naming a session by the title its play stores, with no canary value on Home;
 * Resume from Home with the fake Sportarr answering nothing a plugin could read, which asks no indexer; and Remove, which
 * leaves the watch rows.
 *
 * Every play waits for the player to close and the play to end. The watch rows, the indexer and provider the tests set,
 * and the fake's switches are put back after each test, and the plugin with its files and secrets removed. TMDB is a
 * strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SportarrPlaysDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val sportarr = SportarrDevice(plugins, "SportarrPlaysDeviceTest")
    private val home = HomeContentShown()
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val grabs = AtomicInteger()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        assumeFalse("a TV: its plays are SportarrTvPagesTest's", container.television)
        sportarr.assumeUp(plays = true)
        home.show()
        tmdb = FakeTmdb(strict = true)
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
        sportarr.hold()
        sportarr.install()
        sportarr.indexers(plays = true)
    }

    @After
    fun restore() {
        container.playback.cancel()
        PlaybackCoordinator.fetchNzb = realFetch
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
        waitFor(homeShown)
    }

    /** The plugin's [page] with [key] over Home, as a row of the list opens it. */
    private fun openPage(page: String, key: String) {
        showTheApp()
        compose.runOnIdle { nav.navigate(PluginPage1(page, key)) }
    }

    /**
     * Motorsport: a session of a round whose one release holds every session (the library's Vellmar weekend), played from
     * its row; the plugin's picker plays the session's own file of it, and the player keeps it under the session's key.
     */
    @Test
    fun aSessionPlaysItsOwnFileOfTheRoundsRelease() {
        val session = library.event(VELLMAR_PRACTICE_1)
        val pack = library.found(VELLMAR_PRACTICE_1, "full", "accept").single().first
        sportarr.forget(session.part())
        openPage(WEEKEND, "$FORMULA_1:r19")
        val row = hasText("Practice 1") and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        waitFor(row)
        sportarr.paceSearches()
        compose.onNode(row).performScrollTo().performClick()
        val launch = launchOf(session.part())
        assertEquals("the round's release", pack.name, launch.release.title)
        assertTrue("the session's file of it: ${launch.release.fileName}", launch.release.fileName.orEmpty().contains(".Practice.1."))
        assertEquals("by the weekend's name and the session", "Grand Prix of Vellmar · Practice 1", launch.watch.title)
        kept(session.part(), launch)
    }

    /** A fight card: the main card's ⋮, Choose release, the full event chosen; the picker plays its one file as the main card. */
    @Test
    fun aCardsMainCardPlaysFromTheFullEvent() {
        val card = library.event(CARD)
        val full = library.releases.single { it.id == "ufc-999-full-event" }
        sportarr.forget(card.part("main-card"))
        openPage(EVENT, card.key)
        val more = hasContentDescription("More for Main Card") and hasClickAction()
        waitFor(more)
        compose.onNode(more).performScrollTo().performClick()
        sportarr.paceSearches()
        compose.onNode(hasText(CHOOSE) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        val release = hasText(full.name, substring = true) and hasClickAction()
        waitFor(release, SEARCH_WAIT_MS)
        compose.onNode(release).performScrollTo().performClick()
        val launch = launchOf(card.part("main-card"))
        assertEquals("the full event", full.name, launch.release.title)
        assertEquals("by the card's title and the part", "${card.title} · Main Card", launch.watch.title)
        kept(card.part("main-card"), launch)
    }

    /** A team game: the basketball game's Play plays the release the plugin accepts, named by the US evening's own date. */
    @Test
    fun aGamePlaysTheReleaseThePluginAccepts() {
        val game = library.event(BASKETBALL)
        val accepted = library.found(BASKETBALL, "full", "accept").map { it.first }.filter { it.whole }.map { it.name }
        sportarr.forget(game.part())
        openPage(EVENT, game.key)
        waitFor(button(PLAY))
        sportarr.paceSearches()
        compose.onNode(button(PLAY)).performScrollTo().performClick()
        val launch = launchOf(game.part())
        assertTrue("one the plugin accepts: ${launch.release.title}", launch.release.title in accepted)
        kept(game.part(), launch)
    }

    /** Generic: Choose release lists the skiing event's possible match with its reason; the one picked plays. */
    @Test
    fun genericPlaysTheReleaseYouPick() {
        val event = library.event(GENERIC)
        val (picked, verdict) = library.found(GENERIC, "full", "offer").single()
        sportarr.forget(event.part())
        openPage(EVENT, event.key)
        waitFor(button(CHOOSE))
        sportarr.paceSearches()
        compose.onNode(button(CHOOSE)).performScrollTo().performClick()
        val release = hasText(picked.name, substring = true) and hasClickAction()
        waitFor(release, SEARCH_WAIT_MS)
        assertTrue("its reason", exists(hasText(verdict.reason!!, substring = true)))
        compose.onNode(release).performScrollTo().performClick()
        val launch = launchOf(event.part())
        assertEquals(picked.name, launch.release.title)
        kept(event.part(), launch)
    }

    /** L652: a tap on the tab's Up next row plays its part, as OK does on a TV. */
    @Test
    fun aTapOnUpNextPlaysItsPart() {
        val game = library.event(GAME)
        // Stopped a minute in, so the player opens where the sample clip still runs.
        sportarr.progress(game.part(), game.title, 65_000, 110 * MINUTE)
        showTheApp()
        compose.onNode(barTab(SPORTS)).performClick()
        val upNext = hasText(game.title) and hasText("Premier League · 1 h 49 min left") and hasClickAction()
        waitFor(upNext, LIST_WAIT_MS)
        sportarr.paceSearches()
        compose.onNode(upNext).performClick()
        val launch = launchOf(game.part())
        assertEquals(game.title, launch.watch.title)
        kept(game.part(), launch)
    }

    /**
     * Home's Up next names a session by the title its play stores ("Grand Prix of Kestrel · Race"), with no canary value on
     * Home; Resume plays its release again from the NZB the play kept, with the fake Sportarr answering a page in place of
     * Sportarr: nothing of Sportarr's and no indexer is asked.
     */
    @Test
    fun homesUpNextResumesASessionWithSportarrOff() {
        val race = library.event(KESTREL_RACE)
        sportarr.forget(race.part())
        openPage(WEEKEND, "$FORMULA_1:r20")
        val row = hasText("Race") and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        waitFor(row)
        sportarr.paceSearches()
        compose.onNode(row).performScrollTo().performClick()
        val launch = launchOf(race.part())
        assertEquals("Grand Prix of Kestrel · Race", launch.watch.title)
        // The launch taken here: no player opens. Kept as the player keeps a play stopped part-way.
        container.playback.cancel()
        runBlocking {
            container.library.recordProgress(
                WatchState(WatchKey.item(SPORTARR, race.part()), WatchKind.Item, title = launch.watch.title, updatedAt = 0), 65_000, 90_000,
                launch.release.toRemembered(),
            )
        }
        compose.runOnIdle { nav.popBackStack() }
        waitFor(homeShown)
        val resume = hasContentDescription("Resume Grand Prix of Kestrel · Race")
        scrollTo(resume)
        assertEquals("canary values on Home", emptyList<String>(), compose.canaries())

        sportarr.switch("html")
        val asked = sportarr.counts().getValue("routes").jsonObject.mapValues { it.value.jsonPrimitive.int }
        val grabsBefore = grabs.get()
        compose.onNode(resume).performClick()
        val again = launchOf(race.part())
        assertEquals("the same release, from its kept NZB", launch.release.nzbId, again.release.nzbId)
        assertEquals("no indexer asked", grabsBefore, grabs.get())
        assertEquals("nothing asked of Sportarr", asked, sportarr.counts().getValue("routes").jsonObject.mapValues { it.value.jsonPrimitive.int })
        container.playback.cancel()
    }

    /** Remove: the tab goes, the watch rows stay, Home's Up next with them. */
    @Test
    fun removeLeavesTheWatchRows() {
        val race = library.event(KESTREL_RACE)
        sportarr.progress(race.part(), "Grand Prix of Kestrel · Race", 65_000, 90_000)
        showTheApp()
        waitFor(barTab(SPORTS))
        runBlocking { plugins.app.remove(SPORTARR) }
        compose.waitUntil(PLUGIN_WAIT_MS) { !exists(barTab(SPORTS)) }
        val row = sportarr.state(race.part())
        assertNotNull("the watch row after Remove", row)
        assertFalse("taken back", row!!.deleted)
        scrollTo(hasContentDescription("Resume Grand Prix of Kestrel · Race"))
    }

    /** The player over [launch] stopped part-way and closed, then what it kept under [part]'s key: the release and the file. */
    private fun kept(part: String, launch: PlayerLaunch) {
        val pressedAt = System.currentTimeMillis() - 60_000
        val stoppedAt = playAndStop(launch)
        val saved = waitFor("the player's save as it closed") { sportarr.state(part)?.takeIf { it.lastPlayedAt >= pressedAt } }
        assertEquals("kept as a plugin's item", WatchKind.Item, saved.kind)
        assertEquals("by its title", launch.watch.title, saved.title)
        assertTrue("where it stopped (${saved.positionMs} ms, $stoppedAt ms)", kotlin.math.abs(saved.positionMs - stoppedAt) < 5_000)
        assertEquals("the release that played it", launch.release.nzbId, saved.release?.nzbId)
        assertEquals("its file", launch.release.fileName, saved.release?.fileName)
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

    /** The coordinator's next launch for [part], taken here so no player opens on its own; a failure as soon as the sheet shows one. */
    private fun launchOf(part: String): PlayerLaunch = runBlocking {
        val key = WatchKey.item(SPORTARR, part)
        withTimeout(90_000) {
            merge(
                container.playback.launches.filter { it.watchKey == key },
                container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("didn't start: $it") },
            ).first()
        }
    }

    /** Home's [matcher] brought into view, once it shows. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown on Home: ${matcher.description}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    /** Home's own screen: its search field. */
    private val homeShown = hasText("Search your indexers") and hasClickAction()

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        SemanticsMatcher("in the bar") { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }

    private fun button(words: String) = hasText(words) and hasClickAction()

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
         * The library's: Formula 1's league, the Vellmar round's first practice and the Kestrel round's race; UFC 999's card; a
         * basketball game named by its US evening; the skiing event Generic lists; Lantern Bay FC's game.
         */
        const val FORMULA_1 = 7
        const val VELLMAR_PRACTICE_1 = 1611
        const val KESTREL_RACE = 1605
        const val CARD = 1901
        const val BASKETBALL = 1501
        const val GENERIC = 2201
        const val GAME = 1001

        const val SPORTS = "Sports"
        const val PLAY = "Play"
        const val CHOOSE = "Choose release"

        const val MINUTE = 60_000L
        const val LIST_WAIT_MS = 30_000L

        /** A search of the sports indexer, its stages one after the other. */
        const val SEARCH_WAIT_MS = 60_000L
    }
}

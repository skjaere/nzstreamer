package io.skjaere.usenet.app.plugins

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.MARK_WATCHED
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.FAKE
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.ITEM_KEY
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.ITEM_TITLE
import io.skjaere.usenet.app.plugins.FakePlugin.Companion.SECOND_PAGE
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.plugin.IndexerNote
import io.skjaere.usenet.plugin.ItemReleases
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A plugin's pages on a phone (L582, L590, L593, L594, L627), the app's own shell and graph (AppNav) over the app's
 * registry, an in-process fake in its first slot ([FakePluginRule]): its second page and Back; its settings page, opened
 * from its card in Settings › Plugins; its Search, on the Search tab started afresh with its words and note; nothing it asks
 * of its nav from its scope once another tab shows; the play sheet over its page. With the mock (the fake indexer's Copper Crown, the
 * mock NNTP server): its item played from a release its search found, the player keeping the progress and the release
 * under item:fake:<key>, never a release's key; and, played and stopped in progress, the plugin removed, Resume from
 * Home's Up next plays that release again from its stored NZB, asking no indexer. And Up next's Mark watched of a
 * plugin's item, and its Undo.
 *
 * Settings the tests change, the item's watch row, and the releases remembered as tried are put back as they were. TMDB
 * is a strict fake: what Home asks of other titles is refused, never sent.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PluginPagesDeviceTest {
    private val plugins = PluginStateRule()
    private val fake = FakePlugin()
    private val fakes = FakePluginRule(plugins, fake)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule, then the fake, around the compose rule: in before the activity starts, out once it has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(fakes).around(compose)

    private val context = plugins.context
    private val container = context.container
    private val library = container.library
    private val home = HomeContentShown()
    private val mock = PluginMockSetup(FAKE_INDEXER, "Fake (plugin pages test)")
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val grabs = AtomicInteger()
    private var tmdb: FakeTmdb? = null
    private lateinit var nav: NavHostController

    /** The fake's item, as the host stores it. */
    private val key = WatchKey.item(FAKE, ITEM_KEY)

    @Before
    fun setUp() {
        home.show()
        runBlocking { mock.forget(key) }
        tmdb = FakeTmdb(strict = true)
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
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
        runBlocking { mock.restore() }
        home.restore()
    }

    private fun showTheApp() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        waitFor(barTab("Home"))
    }

    private fun barTab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; on ${nav.currentDestination?.route}, the play ${container.playback.state.value}", e)
        }
    }

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** Home's own screen: its search field. */
    private val homeShown = hasText("Search your indexers") and hasClickAction()

    private fun press(words: String) {
        waitFor(button(words))
        compose.onNode(button(words)).performScrollTo().performClick()
    }

    private fun openTheFakesTab() {
        waitFor(barTab("Fake"))
        compose.onNode(barTab("Fake")).performClick()
        waitFor(hasText(FakePlugin.ownPage("Fake")))
    }

    /** What Back does on a phone. */
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** L582: its second page opened with its argument; Back, the phone's and the page's own, back to its own page. */
    @Test
    fun aSecondPageOpensOverItsOwnAndBackComesBackToIt() {
        showTheApp()
        openTheFakesTab()
        press(SECOND_PAGE)
        waitFor(hasText(FakePlugin.secondArg("Fake")))
        assertFalse("the bar on a page opened from a tab", exists(barTab("Fake")))
        back()
        waitFor(hasText(FakePlugin.ownPage("Fake")))
        waitFor(barTab("Fake"))

        press(SECOND_PAGE)
        waitFor(hasText(FakePlugin.secondArg("Fake")))
        press(FakePlugin.GO_BACK)
        waitFor(hasText(FakePlugin.ownPage("Fake")))
        assertFalse("one page back, not two", exists(hasText("Search your indexers")))
    }

    /** L633, PlPhoneList: its card's Settings in Settings › Plugins opens its settings page, whose Back is the page's. */
    @Test
    fun itsSettingsPageOpensFromItsCardInPlugins() {
        showTheApp()
        waitFor(hasContentDescription("Settings") and hasClickAction())
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        press("Plugins")
        waitFor(hasText(PluginsPage.INTRO))
        press("Settings")
        waitFor(hasText(FakePlugin.settingsTitle("Fake")))
        back()
        waitFor(hasText(PluginsPage.INTRO))
        assertFalse("its settings page closed", exists(hasText(FakePlugin.settingsTitle("Fake"))))
    }

    /**
     * L629: its page's openSearch switches to the Search tab started afresh, whatever the tab showed before (its empty
     * search here), with the plugin's words searched and its note above the results; Search's tab chosen in the bar, and
     * no entry of the plugin's slot on the current stack (L581).
     */
    @Test
    fun itsSearchOpensTheSearchTabAfreshWithItsWordsAndNote() {
        mock.assumeUp(plays = false)
        runBlocking { mock.setUp(plays = false) }
        showTheApp()
        waitFor(barTab("Search"))
        compose.onNode(barTab("Search")).performClick()
        waitFor(hasSetTextAction())
        openTheFakesTab()
        press(FakePlugin.SEARCH)
        waitFor(hasText("Releases for “${FakePlugin.SEARCH_WORDS}” on your indexers"), 60_000)
        waitFor(hasText(FakePlugin.SEARCH_NOTE, substring = true))
        assertTrue("the bar's Search tab chosen", compose.onNode(barTab("Search")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        val slotEntries = compose.runOnIdle { PluginSlots.routeTypes.filter { type -> runCatching { nav.getBackStackEntry(type) }.isSuccess } }
        assertEquals("no entry of a slot on the back stack", emptyList<Any>(), slotEntries)
    }

    /**
     * Each move a plugin's page asks for happens only while that page is the one shown: called later, from its scope, once
     * Home shows, back, open, openSettings and openSearch move nothing.
     */
    @Test
    fun itsPageMovesNothingOnceAnotherTabShows() {
        showTheApp()
        openTheFakesTab()
        val pageNav = fake.ownPageNav ?: throw AssertionError("its page was given no nav")
        waitFor(barTab("Home"))
        compose.onNode(barTab("Home")).performClick()
        waitFor(homeShown)
        compose.waitForIdle()
        val host = fakes.instance(fake).host
        compose.runOnIdle {
            host.scope.launch {
                pageNav.back()
                pageNav.open(FakePlugin.SECOND, "later")
                pageNav.openSettings()
                pageNav.openSearch(FakePlugin.SEARCH_WORDS, FakePlugin.SEARCH_NOTE)
            }
        }
        compose.waitForIdle()
        waitFor(homeShown)
        assertTrue("Home still the screen: ${nav.currentDestination?.route}", compose.runOnIdle { nav.currentDestination?.hasRoute(HomeRoute::class) == true })
        assertFalse("its pages opened", exists(hasText("later")) || exists(hasText(FakePlugin.settingsTitle("Fake"))))
        assertFalse("Search opened", exists(hasText("Releases for “${FakePlugin.SEARCH_WORDS}” on your indexers")))
    }

    /**
     * L627, SpPhoneNothing: Play on its page with nothing found shows the host's sheet over the page, the plugin's indexer
     * line and its note in it; Close leaves the page as it was.
     */
    @Test
    fun thePlaySheetShowsOverItsPage() {
        fake.releases = { ItemReleases(emptyList(), lines = listOf(IndexerNote("Indexer 1", NOTE_LINE)), note = NOTE) }
        showTheApp()
        openTheFakesTab()
        press(FakePlugin.PLAY)
        val inSheet = hasAnyAncestor(isDialog())
        waitFor(hasText("No playable release found") and inSheet)
        assertTrue("its title", exists(hasText(ITEM_TITLE) and inSheet))
        assertTrue("its indexer's line", exists(hasText(NOTE_LINE) and inSheet))
        assertTrue("its note", exists(hasText(NOTE) and inSheet))
        assertTrue("the page under it", exists(hasText(FakePlugin.ownPage("Fake"))))
        compose.onNode(button("Close") and inSheet).performClick()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        waitFor(hasText(FakePlugin.ownPage("Fake")))
        compose.onNode(button(FakePlugin.PLAY)).assertIsDisplayed()
    }

    /** (mock) The fake's item's Play, with GOOD, which its search of the fake indexer found: the launch the host made. */
    private fun playGood(): PlayerLaunch {
        mock.assumeUp(plays = true)
        runBlocking { mock.setUp(plays = true) }
        val good = runBlocking { fake.search(fakes.instance(fake).host, "Copper Crown") }.firstOrNull { it.name == GOOD }?.handle
            ?: throw AssertionError("the fake's search found no $GOOD")
        fake.releases = { ItemReleases(listOf(good)) }
        showTheApp()
        openTheFakesTab()
        press(FakePlugin.PLAY)
        val launch = launchOf(key)
        assertEquals("$FAKE_INDEXER/cc-2019-good", launch.release.key)
        assertEquals("its title", ITEM_TITLE, launch.watch.title)
        return launch
    }

    /**
     * (mock) L627, L593, L594: its item played from Copper Crown's GOOD, as its search of the fake indexer found it: the
     * player keeps where it stopped and the release under item:fake:<key>, never under the release's own key, and the
     * plugin's own watch hears the item is in progress. Nothing of the app's screens is read after the player: as it
     * closes, the test's activity under it is made again for the phone turned back upright, without them.
     */
    @Test
    fun itsItemPlayedIsKeptUnderItsKeyNeverUnderItsReleases() {
        val launch = playGood()
        val pressedAt = System.currentTimeMillis() - 60_000
        // The player: where it stopped saved under the item's key as it closes.
        val stoppedAt = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val duration = waitFor("the player to be ready") {
                var length: Long? = null
                scenario.onActivity { a -> length = a.player?.takeIf { it.playbackState == Player.STATE_READY }?.duration?.takeIf { it > 0 } }
                length
            }
            // In progress: past Up next's minute, before the share that marks it watched.
            val target = duration * 3 / 4
            assertTrue("the fake indexer's clip runs $duration ms: too short to be in progress", target >= LibraryRepository.MIN_RESUME_MS)
            scenario.onActivity { a -> a.player!!.seekTo(target); a.player!!.pause() }
            waitFor("the player at ${target / 1000} s") {
                var at: Long? = null
                scenario.onActivity { a -> at = a.player?.currentPosition?.takeIf { abs(it - target) < 2_000 } }
                at
            }
        }
        val saved = waitFor("the player's save as it closed") { runBlocking { library.state(key).first() }?.takeIf { it.lastPlayedAt >= pressedAt } }
        assertEquals("kept as a plugin's item", WatchKind.Item, saved.kind)
        assertEquals("by its title", ITEM_TITLE, saved.title)
        assertTrue("where it stopped (${saved.positionMs} ms, $stoppedAt ms)", abs(saved.positionMs - stoppedAt) < 5_000)
        assertFalse("in progress, not watched", saved.watched)
        assertEquals("the release that played it remembered", launch.release.nzbId, saved.release?.nzbId)
        assertEquals("cc-2019-good", saved.release?.guid)
        assertNull("nothing under the release's own key", runBlocking { library.state(WatchKey.release(launch.release.nzbId, saved.release!!.fileIndex)).first() })
        // The plugin's own watch, through its host, by its own key.
        val heard = runBlocking { withTimeout(5_000) { fakes.instance(fake).host.watch.observe(listOf(ITEM_KEY)).first { ITEM_KEY in it } } }.getValue(ITEM_KEY)
        assertEquals(FakePlugin.IN_PROGRESS, FakePlugin.watchLine(heard))
        assertEquals(saved.positionMs, heard.positionMs)
    }

    /**
     * (mock) L594, L627: its item, played from GOOD, stopped in progress (saved as the player saves it); the plugin removed,
     * its page goes Home and its tab with it, and Up next's Resume plays the release again from its stored NZB, asking no
     * indexer: no plugin is needed.
     */
    @Test
    fun upNextResumesItsItemWithThePluginGone() {
        val launch = playGood()
        // The launch taken here: no player opens, and the play is over.
        container.playback.cancel()
        runBlocking { library.recordProgress(WatchState(key, WatchKind.Item, title = ITEM_TITLE, updatedAt = 0), 65_000, 90_000, launch.release.toRemembered()) }
        waitFor(hasText(FakePlugin.IN_PROGRESS))

        fakes.remove(fake)
        waitFor(hasText("Search your indexers") and hasClickAction())
        assertFalse("its tab gone", exists(barTab("Fake")))
        val resume = hasContentDescription("Resume $ITEM_TITLE")
        scrollTo(resume)
        val grabsBefore = grabs.get()
        compose.onNode(resume).performClick()
        val again = launchOf(key)
        assertEquals("the same release, from its stored NZB", launch.release.nzbId, again.release.nzbId)
        assertEquals("no indexer asked", grabsBefore, grabs.get())
    }

    /**
     * L594, SpPhoneUpNext: a plugin's item in progress in Up next, its line the time left alone; Mark watched in its ⋮
     * marks that item, its row going; Undo puts it back where it was.
     */
    @Test
    fun upNextMarksItsItemWatchedAndUndoPutsItBack() {
        runBlocking {
            val template = WatchState(key, WatchKind.Item, title = ITEM_TITLE, updatedAt = 0)
            library.recordProgress(template, POSITION_MS, DURATION_MS, RememberedRelease(FAKE_INDEXER, "cc-2019-final", GOOD, null, 0, "not-stored"))
        }
        showTheApp()
        val resume = hasContentDescription("Resume $ITEM_TITLE")
        scrollTo(resume)
        assertTrue("the time left alone as its line", exists(hasText("4 min left")))
        val more = hasContentDescription("More for $ITEM_TITLE")
        compose.onNode(more).performClick()
        compose.onNode(hasText(MARK_WATCHED) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(resume) }
        waitFor(hasText("Marked “$ITEM_TITLE” watched"))
        assertTrue("marked watched", runBlocking { library.state(key).first() }!!.watched)

        compose.onNode(button("Undo")).performClick()
        waitFor(resume)
        val back = runBlocking { library.state(key).first() }!!
        assertFalse("not watched again", back.watched)
        assertEquals("where it was", POSITION_MS, back.positionMs)
    }

    /** Up next's [matcher] brought into view on Home, once it shows. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        val deadline = SystemClock.elapsedRealtime() + PLUGIN_WAIT_MS
        while (!exists(matcher)) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher) }
            assertTrue("never shown on Home: ${matcher.description}", SystemClock.elapsedRealtime() < deadline)
            compose.waitForIdle()
        }
        compose.onNode(matcher).performScrollTo()
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

    private fun abs(n: Long) = kotlin.math.abs(n)

    private companion object {
        const val FAKE_INDEXER = "plugin-pages-device-test"
        const val GOOD = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"
        const val NOTE_LINE = "6 results, none for this game"
        const val NOTE = "Releases often appear a few hours after a game ends. Try Play again later."
        const val POSITION_MS = 120_000L
        const val DURATION_MS = 360_000L
    }
}

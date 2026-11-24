package io.skjaere.usenet.app.plugins

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.leaveTouchMode
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host's Choose release for a plugin's item on a device (L628, L627): an in-process plugin of the test's own, Kestrel,
 * in slot 1 of the app's registry through its test seam (L622), searches the fake indexer (10.0.2.2:8089) for Copper Crown
 * through its host, as a plugin does, and hands the host what it accepted and offered. Its page's Choose release shows the
 * accepted release ranked, then "Possible matches" with its line naming the item as a game, each possible match with
 * "Possible" in its rank's place and its reason, then the plugin's left-out line. Its Play with only possible matches
 * opens Choose release with them alone, under their own line; there (the mock NNTP server, 10.0.2.2:1119) MISLABEL, whose
 * E-AC-3 audio this device can't play, fails and is marked, and GOOD plays and closes the page. On a TV the first release
 * takes the first focus, or the first possible match when only those show, and an OK right after the page opens plays
 * nothing. The slot's own screens and the play sheet's host are the app's (pluginScreens, PlaySheetHost); no player
 * opens: the launches are taken here.
 *
 * The saved providers are hidden for the test and put back as they were; the test's own provider and indexer are deleted
 * with their secrets, and the releases remembered as tried put back. PluginStateRule takes the plugin out after.
 */
@RunWith(AndroidJUnit4::class)
class PluginReleasesDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The plugins' rule around the compose rule: the plugin taken out once the page's activity has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val kestrel = Kestrel()
    private lateinit var nav: NavHostController
    private lateinit var saved: AppSettings
    private lateinit var tried: List<TriedRelease>

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        saved = settings.settings.first()
        tried = library.triedReleaseEntries()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        val mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, "Fake (plugin releases test)", "http://10.0.2.2:8089", "fake-indexer-key")
        engineSetup("the engine on the saved mock") { container.engine.configuredProviders.first { it == listOf(mock) } }
        plugins.app.installForTest(kestrel, KESTREL)
        val running = plugins.app.await(KESTREL, "running") { it.status is PluginStatus.Running }
        assertEquals("Kestrel's slot", 1, running.slot)
        // What its searches found, as a plugin's own search of the user's indexers gives it.
        val host = (running.status as PluginStatus.Running).instance.host
        val found = withTimeoutOrNull(60_000) { host.search.search("Copper Crown", runtimeMinutes = 118).toList().lastOrNull()?.releases }
            ?: throw AssertionError("the plugin's search of the fake indexer found nothing in 60 s")
        kestrel.mislabel = found.handleOf(MISLABEL)
        kestrel.good = found.handleOf(GOOD)
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        // Skipped before anything was changed (a fake isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (saved.indexers.none { it.id == FAKE_INDEXER } && settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
        settings.restore(saved)
        library.restoreTriedReleases(tried)
        engineOnSavedProviders()
    }

    /**
     * SpPhoneChoose: the release the plugin accepted ranked first, then "Possible matches" with its line, the possible match
     * with "Possible" and its reason, then the left-out line; the bar the item's title over its subtitle and the count of
     * both. Back from it: the plugin's page.
     */
    @Test
    fun choosingShowsTheAcceptedReleaseThenThePossibleMatchesWithTheirReasonsAndTheLeftOutLine() {
        showTheSlot()
        kestrel.accepted = listOf(kestrel.mislabel)
        kestrel.offered = listOf(Offered(kestrel.good, "No date in the name"))
        compose.onNode(hasText(CHOOSE) and hasClickAction()).performClick()
        waitFor(heading)

        assertTrue("the bar's title", exists(hasText(TITLE)))
        assertTrue("the bar's line", exists(hasText("KCL · 2 releases from 1 indexer")))
        assertTrue("the accepted release, ranked", exists(card(MISLABEL) and hasText("Best match")))
        assertTrue("the line under the heading", exists(hasText("Play never picks these. Tap one if it's the right game.")))
        assertTrue("the possible match, its rank and its reason", exists(card(GOOD) and hasText("Possible") and hasText("No date in the name")))
        assertTrue("the left-out line", exists(hasText(LEFT_OUT)))
        val top = { m: SemanticsMatcher -> compose.onNode(m).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(
            "in order: the accepted release, the heading, the possible match, the left-out line",
            top(card(MISLABEL)) < top(heading) && top(heading) < top(card(GOOD)) && top(card(GOOD)) < top(hasText(LEFT_OUT)),
        )

        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(hasText(CHOOSE) and hasClickAction())
        assertTrue("Choose release still shows", !exists(heading))
    }

    /**
     * SpPhoneChoose 3, (mock): Play with only possible matches opens Choose release with them alone, under their own line;
     * MISLABEL, tapped, fails on the track probe and is marked as the sheet says; GOOD, tapped, plays and the page closes.
     */
    @Test
    fun playWithOnlyPossibleMatchesOpensThemAloneAndATapPlaysOneAfterAFailureIsMarked() {
        showTheSlot()
        kestrel.releases = { ItemReleases(emptyList(), listOf(Offered(kestrel.mislabel, "Team codes only"), Offered(kestrel.good, "No date in the name")), LEFT_OUT) }
        compose.onNode(hasText(PLAY) and hasClickAction()).performClick()
        waitFor(hasText(ONLY_LINE), 60_000)
        assertTrue("a release ranked as Best match", !exists(card(MISLABEL) and hasText("Best match")) && !exists(card(GOOD) and hasText("Best match")))
        assertTrue("both as possible matches", exists(card(MISLABEL) and hasText("Possible") and hasText("Team codes only")) && exists(card(GOOD) and hasText("Possible")))
        assertTrue("the sheet closed", container.playback.state.value == PlayState.Idle)

        compose.onNode(card(MISLABEL)).performClick()
        val reason = failureOf(MISLABEL)
        assertTrue(reason, reason.startsWith("No audio this device can play"))
        closeTheSheet()
        waitFor(card(MISLABEL) and hasText("✕ $reason"))

        compose.onNode(card(GOOD)).performClick()
        val launch = launched()
        assertEquals("$FAKE_INDEXER/cc-2019-good", launch.release.key)
        assertEquals("played as the item, under its key", "item:$KESTREL:cc-2019:final", launch.watchKey)
        waitFor(hasText(PLAY) and hasClickAction())
        assertTrue("Choose release still shows after the launch", !exists(hasText(ONLY_LINE)))
    }

    /** SpTvChoose: on a TV the first release takes the first focus, Best match; with only possible matches, the first of them. */
    @Test
    fun onATvTheFirstFocusIsTheFirstReleaseOrTheFirstPossibleMatch() {
        val remote = Remote(compose)
        showTheSlot(tv = true)
        leaveTouchMode(compose.activity)
        kestrel.accepted = listOf(kestrel.mislabel)
        kestrel.offered = listOf(Offered(kestrel.good, "No date in the name"))
        compose.runOnIdle { kestrel.nav!!.openReleases(kestrel.item, kestrel.accepted, kestrel.offered, LEFT_OUT) }
        waitFor(hasText("Play never picks these. Press OK on one if it's the right game."))
        compose.waitUntil(5_000) { remote.isFocused(card(MISLABEL) and hasText("Best match")) }

        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText(CHOOSE) and hasClickAction())
        compose.runOnIdle { kestrel.nav!!.openReleases(kestrel.item, emptyList(), listOf(Offered(kestrel.good, "No date in the name"), Offered(kestrel.mislabel, "Team codes only")), null) }
        waitFor(hasText("Nothing names this game for sure yet. Play never picks these; press OK on one if it's the right game."))
        try {
            compose.waitUntil(5_000) { remote.isFocused(card(GOOD) and hasText("Possible")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the first possible match never had the focus; it is on \"${remote.focused()}\"", e)
        }
    }

    /**
     * On a TV, Play with only possible matches opens Choose release with the first of them focused: an OK that comes
     * right after it opens, the second of a double press on Play or a held one (some TV remotes send those as separate
     * presses), is the one that opened it, and plays nothing (Play never picks a possible match); a press after that
     * plays it.
     */
    @Test
    fun onATvAnOkRightAfterThePageOpensPlaysNothing() {
        val remote = Remote(compose)
        showTheSlot(tv = true)
        leaveTouchMode(compose.activity)
        // Each play as it starts, heard from before the first OK: the mock can play a release in under a second, so a
        // play may be over, its player launched and the sheet Idle again, before a look at the play's state saw it.
        val starts = AtomicInteger()
        val listening = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            container.playback.starting.collect { starts.incrementAndGet() }
        }
        try {
            val opened = SystemClock.uptimeMillis()
            compose.runOnIdle { kestrel.nav!!.openReleases(kestrel.item, emptyList(), listOf(Offered(kestrel.good, "No date in the name")), null) }
            waitFor(hasText("Nothing names this game for sure yet. Play never picks these; press OK on one if it's the right game."))
            compose.waitUntil(5_000) { remote.isFocused(card(GOOD) and hasText("Possible")) }

            // Stamped as pressed just after the page opened, as the second press of a double OK.
            instrumentation.sendKeySync(KeyEvent(opened + 50, opened + 50, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            instrumentation.sendKeySync(KeyEvent(opened + 50, opened + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            compose.waitForIdle()
            SystemClock.sleep(OkGuard.WINDOW_MS)
            compose.waitForIdle()
            assertEquals("an OK that opened the page played", 0, starts.get())

            // Its launch waits for whoever takes it: the press's wait for the screen to settle (the sheet, then the
            // page closing) can outlast the whole play.
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("the press after the page's first moments played it", "$FAKE_INDEXER/cc-2019-good", launched().release.key)
        } finally {
            listening.cancel()
        }
    }

    /** Slot 1's screens, as the app's graph has them, with the play sheet's host over them as AppNav has it. */
    private fun showTheSlot(tv: Boolean = false) {
        val app = @Composable {
            nav = rememberNavController()
            Box {
                NavHost(nav, startDestination = PluginTab1) { pluginScreens(nav, plugins.app) }
                PlaySheetHost(container.playback, onSearch = {}, onProviders = {}, onReleases = { token -> nav.navigate(PluginReleasesRoute(token)) })
            }
        }
        if (tv) compose.setTvContent { app() } else compose.setContent { AppTheme { app() } }
        waitFor(hasText(CHOOSE) and hasClickAction(), 15_000)
    }

    /** A plugin of the test's own: its page's Choose release and Play hand the host what its search found, as set. */
    private class Kestrel : NzPlugin {
        lateinit var mislabel: ReleaseHandle
        lateinit var good: ReleaseHandle
        var accepted: List<ReleaseHandle> = emptyList()
        var offered: List<Offered> = emptyList()
        var releases: () -> ItemReleases = { ItemReleases(emptyList()) }

        /** The page's nav, as it was last given. */
        @Volatile
        var nav: PluginNav? = null
        val item = PlayItem("cc-2019:final", TITLE, "KCL", 118, noun = "game")

        override fun tab() = PluginTab("Kestrel", Icons.Filled.Build)

        @Composable
        override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
            this.nav = nav
            Column {
                Text("Kestrel Cup")
                PrimaryButton(CHOOSE, { nav.openReleases(item, accepted, offered, LEFT_OUT) })
                PrimaryButton(PLAY, { host.play.play(item) { releases() } })
            }
        }

        @Composable
        override fun Settings(host: PluginHost, nav: PluginNav) = Unit

        override fun close() = Unit
    }

    private val heading = hasText("POSSIBLE MATCHES") and isHeading()

    /** A release's card in the list, by its name. */
    private fun card(release: String) = hasText(release, substring = true) and hasClickAction() and !hasAnyAncestor(isDialog())

    private fun List<ReleaseInfo>.handleOf(name: String): ReleaseHandle =
        firstOrNull { it.name == name }?.handle ?: throw AssertionError("the plugin's search found no $name: ${map { it.name }}")

    /** Why [release] didn't start, as the play sheet's failure says; one that never fails says where the play is. */
    private fun failureOf(release: String): String {
        val failed = runBlocking { withTimeoutOrNull(90_000) { container.playback.state.filterIsInstance<PlayState.Failed>().first() } }
            ?: throw AssertionError("$release didn't fail within 90 s: the play is ${container.playback.state.value}")
        return failed.failures.singleOrNull { it.first.contains(release) }?.second ?: throw AssertionError("not $release's failure: $failed")
    }

    private fun closeTheSheet() {
        waitFor(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog()))
        compose.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        compose.waitForIdle()
    }

    /** The coordinator's next launch, taken here so no player opens; a failure as soon as the sheet shows one. */
    private fun launched(): PlayerLaunch = runBlocking {
        withTimeoutOrNull(90_000) {
            merge(
                container.playback.launches,
                container.playback.state.filterIsInstance<PlayState.Failed>().map { throw AssertionError("didn't start: $it") },
            ).first()
        } ?: throw AssertionError("nothing launched within 90 s: the play is ${container.playback.state.value}")
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private companion object {
        const val KESTREL = "kestrel"
        const val FAKE_INDEXER = "plugin-releases-device-test"
        const val TITLE = "Copper Crown · Final"
        const val CHOOSE = "Choose release"
        const val PLAY = "Play"
        const val LEFT_OUT = "2 left out: a name with a score, other games. Their names aren't shown."
        const val ONLY_LINE = "Nothing names this game for sure yet. Play never picks these; tap one if it's the right game."
        const val MISLABEL = "Copper.Crown.2019.PROPER.1080p.WEB-DL.AAC2.0.H.264-MISLABEL"
        const val GOOD = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"
    }
}

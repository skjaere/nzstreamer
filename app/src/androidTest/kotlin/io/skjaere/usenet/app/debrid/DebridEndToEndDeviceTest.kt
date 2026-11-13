package io.skjaere.usenet.app.debrid

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.PlayingPack
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.Season
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debrid on a phone end to end, over the fakes (L189, item 38), from settings with nothing in them: First run's torrent
 * way (TorBox's key, then Home Prowlarr) leads Home; with Usenet's side added (the mock NNTP server and the shared fake
 * indexer, as Indexer A), Search asks both kinds of indexers, and S01E02's list holds Usenet's GOOD and the same
 * release TorBox has cached. From Harbor Lights' page, Choose release plays S01E02 from the season pack TorBox has
 * cached. In the player, with links that die 60 s after they are made: a seek to near the end, a pause until 70 s past
 * the link, then back near the start and play again: the open there meets the dead link, one fresh link plays on with
 * nothing shown, and every open was a ranged one (the debrid fake's counts: no 200). Closed past a minute in, Up next's
 * Resume plays it again from there with no search and nothing added to the account, and the player's Next plays S01E03
 * from the pack, with no search either; its File details name Home Prowlarr and TorBox. With the build's gate closed,
 * Settings offers no way in, and what was saved stays saved. What the device had saved is taken away for the test and
 * put back after, keys included; TMDB is answered by [FakeTmdb] for Harbor Lights alone.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DebridEndToEndDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()
    private var fakeBack: (() -> Unit)? = null
    private var endpointsBack: (() -> Unit)? = null
    private var tmdb: FakeTmdb? = null
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments

    /** Every sheet the coordinator showed, in order: collected as each is set, none passed over. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null

    /** Harbor Lights as the debrid classes' fixture has TMDB know it; never opened: this test sets its own sources up. */
    private val harbor = DebridSearchFixture()
    private val season = Season(
        DebridSearchFixture.HARBOR_LIGHTS, 1, null, null,
        DebridSearchFixture.EPISODES.mapIndexed { i, name -> Episode(1, i + 1, name, null, harbor.today.minusDays(60L - 7 * i), 45, null) },
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        assumeTrue("the fake indexer isn't reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        assumeTrue("the mock NNTP server isn't reachable at 10.0.2.2:1119", reachable(1119))
        DebridFakes.assumeTorznab()
        DebridFakes.assumeDebrid()
        // The debug build's own settings saved first: else they could land after this test took the settings away.
        container.devSettingsSaved.await()
        DebridSearchFixture.forgetPlays(container.library)
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        settings.setIndexerSetupSkipped(false)
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        tmdb = FakeTmdb(shows = mapOf(DebridSearchFixture.HARBOR_LIGHTS to harbor.show), seasons = { id, number -> season.takeIf { id == DebridSearchFixture.HARBOR_LIGHTS && number == 1 } }, suggestions = { emptyList() })
        // The player asks TMDB itself for the episodes either side, past FakeTmdb's seams: these answer from the fake's.
        PlayerActivity.nextEpisode = { _, playing -> beside(playing, 1) }
        PlayerActivity.previousEpisode = { _, playing -> beside(playing, -1) }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch { container.playback.state.collect { seen += it } }
    }

    /** Harbor Lights' episode [by] after [playing]'s in its first season, as TMDB would say it; null for any other. */
    private fun beside(playing: AutoAdvance.Episode, by: Int): EpisodePlay? =
        season.episodes.getOrNull(playing.episode - 1 + by)
            ?.takeIf { playing.showTmdbId == DebridSearchFixture.HARBOR_LIGHTS && playing.season == 1 }
            ?.let { EpisodePlay.of(harbor.show, it, harbor.today) }

    /** Each step whatever the one before it did: a debrid fake that stopped answering mustn't leave the device's sources away. */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            {
                // A player left open by a failure would be in front of the next test.
                instrumentation.runOnMainSync { players(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).forEach { it.finish() } }
                DebridGate.shownForTests = null
                PlayerActivity.nextEpisode = realNext
                PlayerActivity.previousEpisode = realPrevious
                PlayerActivity.lookUpSegments = realLookUp
                watching?.cancel()
                onMainThread { container.playback.cancel() }
                tmdb?.close()
            },
            { if (fakeBack != null) DebridFakes.control("reset") },
            { fakeBack?.invoke() },
            { endpointsBack?.invoke() },
            { putBack() },
        )
    }

    /** The device's sources as they were, keys included, and nothing of this class's plays remembered. */
    private suspend fun putBack() {
        if (!::saved.isInitialized) return
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
        settings.services.first().filter { s -> saved.services.none { it.id == s.id } }.forEach { settings.signOut(it.id) }
        settings.torrentIndexers.first().filter { t -> saved.torrentIndexers.none { it.id == t.id } }.forEach { settings.deleteTorrentIndexer(it.id) }
        settings.restore(saved)
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        assertEquals(saved, settings.settings.first())
        // Nor does a class after this one find the torrents this one played remembered (DFX-1).
        DebridSearchFixture.forgetPlays(container.library)
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        delay(1_000)
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(DebridFakes.HOST, port), 2_000) } }.isSuccess

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [probe] to give something, through the Compose rule's clock, which drives the player's overlays as well
     * as the app's screens; [what] names it if it never does.
     */
    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        var found: T? = null
        try {
            compose.waitUntil(timeoutMs) { probe()?.also { found = it } != null }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never: $what", e)
        }
        return found!!
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        waitFor(matcher.description, timeoutMs) { exists(matcher).takeIf { it } }
    }

    /** Scrolled to first where it is in a scrolling column, or in a lazy list that hasn't laid it out yet. */
    private fun click(matcher: SemanticsMatcher) {
        assertTrue("nothing to click for ${matcher.description}", scrolledTo(matcher))
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tab(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** A pill whose words start with [name]'s, then " · ", as Search draws one per indexer. */
    private fun pill(name: String) = hasText("$name · ", substring = true)

    /** A pill still waiting: its indexer searching, or its torrents being checked. */
    private val waiting = hasText("searching", substring = true) or hasText("checking", substring = true)

    /** A release's card in a list, by its name and what its tag says it plays from. */
    private fun card(name: String, from: String) = hasText(name) and hasContentDescription(from) and hasClickAction()

    /**
     * Scrolls whichever list holds [matcher]'s node to it (a lazy list lays out only what shows; a screen can have more
     * than one list, the pills' row beside the results) and says whether it is there.
     */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean {
        if (exists(matcher)) return true
        val lists = compose.onAllNodes(hasScrollAction())
        repeat(lists.fetchSemanticsNodes().size) { i ->
            if (runCatching { lists[i].performScrollToNode(matcher) }.isSuccess && exists(matcher)) return true
        }
        return exists(matcher)
    }

    /** The coordinator's next launch; fails the test if the sheet shows a failure instead. */
    private fun launched(): PlayerLaunch = runBlocking {
        withTimeout(120_000) {
            merge(
                container.playback.launches,
                container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
            ).first()
        }
    }

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    private fun calls(call: String): Int = counts().getJSONObject("calls").getInt(call)

    private fun cdn(status: Int): Int = counts().getJSONObject("cdn").getInt("$status")

    /** Whether a play searched the indexers since [seen] was cleared: the sheet would have said so. */
    private fun searched(): Boolean = seen.any { it is PlayState.Searching && it.line == "Searching your indexers…" }

    @Test
    fun aDebridUserSetsUpSearchesBothKindsAndPlaysATorrentThroughSeeksAPauseResumeNextAndFileDetails() {
        compose.setContent {
            nav = rememberNavController()
            AppTheme { AppNav(nav = nav) }
        }

        // First run's torrent way (DbPhoneFirstRun 2-3): the service's key, then the torrent indexer one button adds.
        waitFor(hasText("Add a debrid service") and hasClickAction())
        click(hasText("Add a debrid service") and hasClickAction())
        waitFor(hasText("TorBox") and hasClickAction())
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText("Sign in to TorBox"))
        compose.onNode(hasSetTextAction()).performTextReplacement(DebridFakes.TORBOX_KEY)
        click(hasText("Save") and hasClickAction())
        waitFor(hasText("Add a torrent indexer") and hasClickAction())
        click(hasText("Add a torrent indexer") and hasClickAction())
        waitFor(hasText("Add torrent indexer"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("Home Prowlarr")
        fields[1].performTextReplacement(DebridFakes.PROWLARR_URL)
        fields[2].performTextReplacement(DebridFakes.TORZNAB_KEY)
        click(hasText("Save") and hasClickAction())
        waitFor(hasText("Search your indexers"), timeoutMs = 15_000)
        runBlocking {
            assertEquals(listOf("TorBox"), settings.services.first().filter { !it.deleted }.map { it.kind })
            assertEquals(listOf("Home Prowlarr"), settings.torrentIndexers.first().filter { !it.deleted }.map { it.name })
            // And Usenet's side, for a list of both kinds: the mock NNTP server and the shared fake indexer.
            settings.saveProvider(ProviderRecord("", "Mock (debrid end to end)", "10.0.2.2", 1119, useTls = false, connections = 4), "")
            settings.saveIndexer(DebridSearchFixture.INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key")
        }

        // Search asks both kinds (DbPhoneSearch): each indexer's pill, Home Prowlarr's once its torrents are checked.
        click(tab("Search"))
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("harbor lights")
        click(hasContentDescription("Search indexers for “harbor lights”") and hasClickAction())
        waitFor("both indexers' pills, each answered, Home Prowlarr's torrents checked", 40_000) {
            listOf("Indexer A", "Home Prowlarr").all { exists(pill(it)) && !exists(pill(it) and waiting) }.takeIf { it }
        }
        // One list, Usenet's GOOD and the same release TorBox has cached.
        click(hasContentDescription("for S01E02", substring = true) and hasClickAction())
        waitFor("S01E02's list, the cached torrent in it beside Usenet's", 20_000) {
            (scrolledTo(card(GOOD, "From TorBox") and hasText("✓ Cached")) && scrolledTo(card(GOOD, "From Usenet"))).takeIf { it }
        }
        click(hasContentDescription("Back") and hasClickAction())

        // Harbor Lights' page, Choose release for S01E02: the season pack TorBox has cached, picked from the list.
        compose.runOnIdle { nav.navigate(ShowRoute(DebridSearchFixture.HARBOR_LIGHTS)) }
        val episode = hasText(DebridSearchFixture.EPISODES[1]) and hasClickAction()
        waitFor(episode, timeoutMs = 15_000)
        compose.onNode(episode).performTouchInput { longClick() }
        waitFor(hasText("Choose release"))
        compose.onNode(hasText("Choose release")).performClick()
        val pack = card(PACK, "From TorBox")
        waitFor("the pack in S01E02's list beside Usenet's releases", 40_000) { (scrolledTo(pack) && scrolledTo(card(GERMAN, "From Usenet"))).takeIf { it } }
        // Links made from now on die 60 s after they are made, as a service's do after their hour.
        DebridFakes.control("cdn?expire=60")
        scrolledTo(pack)
        compose.onNode(pack).performClick()
        val launch = launched()
        // Opening made the link before the launch came: waiting from here waits at least as long from it.
        val madeAt = SystemClock.elapsedRealtime()
        assertEquals(PACK, launch.release.title)
        assertEquals(E02, launch.release.fileName)
        assertTrue(launch.uri, launch.uri.startsWith("debrid://"))
        assertEquals("one link, Opening's", 1, calls("torbox requestdl"))
        assertEquals("the pack added once", 1, calls("torbox createtorrent"))

        playing(launch) { player ->
            val duration = waitFor("the episode to play", 60_000) { onMain { player.player?.takeIf { it.isPlaying }?.duration?.takeIf { it > 0 } } }
            assumeTrue("the debrid fake's video runs ${duration / 1000} s: start it with the README's 90 s mock video", duration >= MIN_VIDEO_MS)
            onMain { player.player?.pause() }
            val opened = cdn(206)

            // Near the end, past what the player holds: a ranged open there. Paused, so the play doesn't end, and short
            // of the last 10 s, where the next episode's card comes up.
            val end = duration - 15_000
            onMain { player.player?.seekTo(end) }
            waitFor("the player ready near the end") { onMain { player.player?.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition >= end - 500 } } }

            // Paused until 70 s past the link's making: it died at 60.
            waitFor("70 s past the link", 90_000) { (SystemClock.elapsedRealtime() - madeAt >= PAUSE_PAST_MS).takeIf { it } }
            assertEquals("no fresh link while paused", 1, calls("torbox requestdl"))

            // Back near the start and play: the open there meets the dead link, and one fresh link plays on.
            onMain {
                player.player?.seekTo(BACK_TO_MS)
                player.player?.play()
            }
            waitFor("the play to go on from near the start", 30_000) {
                onMain { player.player?.takeIf { it.isPlaying && it.currentPosition >= BACK_TO_MS + 1_000 } }
            }
            assertFalse("nothing shown", "Playback stopped" in onMain { overlayWords(player) })
            assertEquals("one fresh link", 2, calls("torbox requestdl"))
            assertTrue("the dead link refused", cdn(403) >= 1)
            assertEquals("every open a ranged one: no 200", 0, cdn(200))
            assertTrue("the seek's and the way back's opens, ranged", cdn(206) - opened >= 2)

            // Links made from now on last. Stopped past the minute under which a play starts again from its beginning.
            DebridFakes.control("cdn")
            onMain { player.player?.seekTo(STOP_AT_MS) }
            waitFor("the place it stops at") { onMain { player.player?.takeIf { it.currentPosition >= STOP_AT_MS && it.playbackState == Player.STATE_READY } } }
            onMain { player.player?.pause() }
        }
        val key = WatchKey.episode(DebridSearchFixture.HARBOR_LIGHTS, 1, 2)
        waitFor("S01E02's progress") { runBlocking { container.library.state(key).first() }?.takeIf { it.positionMs >= STOP_AT_MS - 1_000 } }

        // Up next's Resume, back from the show's page to Home: the pack again from where it stopped, with no search and
        // nothing added again.
        waitFor(hasText("Resume S01E02", substring = true))
        click(hasContentDescription("Back") and hasClickAction())
        click(tab("Home"))
        val resume = hasContentDescription("Resume Harbor Lights S01E02") and hasClickAction()
        waitFor(resume, timeoutMs = 15_000)
        seen.clear()
        click(resume)
        val resumed = launched()
        assertEquals(PACK, resumed.release.title)
        assertEquals(E02, resumed.release.fileName)
        assertFalse("Resume searched", searched())
        assertEquals("nothing added again", 1, calls("torbox createtorrent"))
        playing(resumed) { player ->
            val from = waitFor("Resume to play", 60_000) { onMain { player.player?.takeIf { it.isPlaying }?.currentPosition } }
            assertTrue("from where it stopped ($from of $STOP_AT_MS)", from >= STOP_AT_MS - 1_000)

            // The player's Next: S01E03, from the pack playing; the player closes for it.
            onMain {
                playerView(player).controllerShowTimeoutMs = 0
                playerView(player).showController()
            }
            waitFor("Next to be enabled") { onMain { player.findViewById<View>(androidx.media3.ui.R.id.exo_next).isEnabled.takeIf { it } } }
            seen.clear()
            onMain { player.findViewById<View>(androidx.media3.ui.R.id.exo_next).performClick() }
            waitFor("the player to close") { onMain { player.isDestroyed.takeIf { it } } }
        }
        val next = launched()
        assertEquals(PACK, next.release.title)
        assertEquals(E03, next.release.fileName)
        assertFalse("the next episode searched", searched())
        assertTrue("the pack's own line", seen.any { it is PlayState.Searching && it.line.startsWith(PlayingPack.SEARCH_LINE) })
        assertEquals("nothing added again", 1, calls("torbox createtorrent"))

        // File details (DbPhoneFileDetails): the torrent indexer that found it, then the service it plays from.
        playing(next) { player ->
            waitFor("the player to know the file's tracks", 60_000) { onMain { player.player?.currentTracks?.takeUnless { it.isEmpty } } }
            onMain {
                playerView(player).showController()
                button(player, "File details").performClick()
            }
            val rows = waitFor("the Service row") { onMain { rows(player).takeIf { "Service" in it } } }
            assertEquals(listOf("Home Prowlarr"), rows["Indexer"])
            assertEquals(listOf("TorBox", "a cached torrent, streamed from the service"), rows["Service"])
            assertFalse("no providers: $rows", "Providers" in rows)
            val words = onMain { paneWords(player) }
            listOf(next.release.guid.removePrefix("btih:"), DebridFakes.TORBOX_ACCOUNT, "/cdn/", DebridFakes.HOST).forEach { secret ->
                assertTrue("the panel shows what it mustn't", words.none { secret in it })
            }
            close(player)
        }

        // The build's gate closed (L192): Settings offers no way in, and what was saved stays saved.
        DebridGate.shownForTests = false
        click(tab("Home"))
        click(hasContentDescription("Settings") and hasClickAction())
        waitFor(hasText("Usenet providers"))
        assertFalse("a Debrid services row", exists(hasText("Debrid services")))
        assertFalse("a Torrent indexers row", exists(hasText("Torrent indexers")))
        runBlocking {
            assertEquals(listOf("TorBox"), settings.services.first().filter { !it.deleted }.map { it.kind })
            assertEquals(listOf("Home Prowlarr"), settings.torrentIndexers.first().filter { !it.deleted }.map { it.name })
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun players(vararg stages: Stage): List<PlayerActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>()
    }

    /**
     * [launch] played as the app plays one, its player started over the app's own screens, which come back as it
     * closes; [then] with the player in front. A player [then] leaves open is closed after it.
     */
    private fun playing(launch: PlayerLaunch, then: (PlayerActivity) -> Any?) {
        onMain { compose.activity.startActivity(PlayerActivity.intent(compose.activity, launch)) }
        val player = waitFor("the player in front", 30_000) { onMain { players(Stage.RESUMED).firstOrNull() } }
        try {
            then(player)
        } finally {
            if (!onMain { player.isDestroyed }) close(player)
        }
    }

    /** Closes [player] as Back does, and waits for it to go: it saves where it was as it goes. */
    private fun close(player: PlayerActivity) {
        onMain { player.finish() }
        waitFor("the player to close") { onMain { player.isDestroyed.takeIf { it } } }
    }

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun button(activity: Activity, description: String) =
        views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == description }

    private fun composeRoots(activity: Activity) = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** Every word the player's shown Compose views say: its panels among them. */
    private fun overlayWords(activity: Activity): List<String> =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }.flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.PaneTitle))
        }

    private fun pane(activity: Activity): SemanticsNode? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }.firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }

    /** Each row of File details by its label: its value and notes, as one node reads. */
    private fun rows(activity: Activity): Map<String, List<String>> =
        pane(activity)?.let(::subtree).orEmpty().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.takeIf { it.size >= 2 } }
            .associate { it.first() to it.drop(1) }

    private fun paneWords(activity: Activity): List<String> =
        pane(activity)?.let(::subtree).orEmpty().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private companion object {
        /** FakeTorrents' and FakeCatalog's names (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GERMAN = "Harbor.Lights.S01E02.German.DL.720p.WEB.h264-GERGRP"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"
        const val E02 = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv"
        const val E03 = "Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv"

        /** How long the fake's video must run for a seek to 10 s before its end to land past the player's 50 s buffer. */
        const val MIN_VIDEO_MS = 80_000L

        /** The pause runs until this long after the link was made: past its 60 s. */
        const val PAUSE_PAST_MS = 70_000L

        /** Where the play goes back to after the pause: far from what the player holds near the end. */
        const val BACK_TO_MS = 5_000L

        /** Where the play stops for Resume: past the minute under which the player starts a play again from its beginning. */
        const val STOP_AT_MS = 70_000L
    }
}

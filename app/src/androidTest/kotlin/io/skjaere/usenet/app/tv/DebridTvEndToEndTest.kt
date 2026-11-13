package io.skjaere.usenet.app.tv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridSearchFixture
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileDetailsSection
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.PlayingPack
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
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
 * Debrid on a real TV (tv36) end to end with a remote, over the fakes (L189, L210, item 38): the phone class's walk, by
 * D-pad. From settings with nothing in them, First run's torrent way: Add a debrid service, TorBox, the key typed with
 * the TV's own keyboard, Save; then Add a torrent indexer, its name, address and key typed the same way, Save: Home.
 * With Usenet's side added (the mock NNTP server and the shared fake indexer, as Indexer A), Search's indexers asked
 * from the right pane's button: both kinds answer. On Harbor Lights' page, S01E02's ⋮ and Choose release: the remote
 * goes down the list to the season pack TorBox has cached and plays it. In the player, with links that die 60 s after
 * they are made: OK pauses, Right held seeks on to near the end, the pause runs past the link's life, Left seeks back
 * and OK plays on, through one fresh link with nothing shown and every open a ranged one. Back leaves the player; the
 * page's Resume plays the pack again from there, tried first and nothing added; the player's Next plays S01E03 from the
 * pack, with no search; its File details, reached Up and Right from play/pause, name Home Prowlarr and TorBox. With the
 * build's gate closed, Settings' Sources offer no way in. tv36's Android 16 doesn't move a view's focus for an
 * instrumentation's arrow keys, so in the player's controls the focus is moved as they would move it (the views' focus
 * search, the panel's FocusManager), as TvFileDetailsTest does. What the TV had saved is taken away for the test and
 * put back after, keys included.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DebridTvEndToEndTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val remote = Remote(compose)
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
        assumeTv()
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
        instrumentation.setInTouchMode(false)
        // As the app's activities are: the keyboard's insets reach the screens.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    /** Each step whatever the one before it did: a debrid fake that stopped answering mustn't leave the TV's sources away. */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            {
                // A player left open by a failure would be in front of the next test.
                instrumentation.runOnMainSync { players(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).forEach { it.finish() } }
                // Touch mode outlasts the test's process.
                instrumentation.setInTouchMode(true)
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

    /** The TV's sources as they were, keys included, and nothing of this class's plays remembered. */
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

    /** Harbor Lights' episode [by] after [playing]'s in its first season, as TMDB would say it; null for any other. */
    private fun beside(playing: AutoAdvance.Episode, by: Int): EpisodePlay? =
        season.episodes.getOrNull(playing.episode - 1 + by)
            ?.takeIf { playing.showTmdbId == DebridSearchFixture.HARBOR_LIGHTS && playing.season == 1 }
            ?.let { EpisodePlay.of(harbor.show, it, harbor.today) }

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
            throw AssertionError("never: $what; the focus on \"${runCatching { remote.focused() }.getOrDefault("?")}\"", e)
        }
        return found!!
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        waitFor(matcher.description, timeoutMs) { exists(matcher).takeIf { it } }
    }

    /** Presses [key] until [matcher] has the remote's focus, at most [max] times; fails naming where the focus is. */
    private fun pressUntil(key: Int, matcher: SemanticsMatcher, what: String, max: Int = 10) {
        repeat(max) {
            if (remote.isFocused(matcher)) return
            remote.press(key)
        }
        assertTrue("never reached $what with ${KeyEvent.keyCodeToString(key)}; at \"${remote.focused()}\"", remote.isFocused(matcher))
    }

    private fun keyboardUp(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    /** With the remote on [label]'s field: OK, the TV's keyboard up, [text] typed in it, Back to put the keyboard away. */
    private fun type(label: String, text: String) {
        assertTrue("the remote isn't on $label's field; at \"${remote.focused()}\"", remote.isFocused(fieldGate(label)))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("the TV's keyboard for $label", 5_000) { keyboardUp().takeIf { it } }
        compose.onNode(hasSetTextAction() and isFocused()).performTextReplacement(text)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitFor("the keyboard put away", 5_000) { (!keyboardUp()).takeIf { it } }
        waitFor("the remote back on $label's field", 5_000) { remote.isFocused(fieldGate(label)).takeIf { it } }
    }

    /** A pill whose words start with [name]'s, then " · ", as Search draws one per indexer. */
    private fun pill(name: String) = hasText("$name · ", substring = true)

    /** A pill still waiting: its indexer searching, or its torrents being checked. */
    private val waiting = hasText("searching", substring = true) or hasText("checking", substring = true)

    /** A release's card in a list, by its name and what its tag says it plays from. */
    private fun card(name: String, from: String) = hasText(name) and hasContentDescription(from) and hasClickAction()

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
    fun aDebridUserSetsUpByRemoteSearchesBothKindsAndPlaysATorrentThroughSeeksAPauseResumeNextAndFileDetails() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }

        // First run's torrent way (DbPhoneFirstRun on a TV, DbTvSignIn 1-4), the remote starting on the phone's button.
        waitFor(hasText("Set up your sources"), timeoutMs = 15_000)
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, hasText("Add a debrid service") and hasClickAction(), "Add a debrid service")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Premiumize")
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, hasText("TorBox") and hasClickAction(), "TorBox", max = 4)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Sign in to TorBox"))
        waitFor("the key form on its field", 5_000) { remote.isFocused(fieldGate("API key")).takeIf { it } }
        type("API key", DebridFakes.TORBOX_KEY)
        val test = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Test", max = 3)
        assertTrue("never reached Test; at $test", test.startsWith("Test"))
        val save = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from Test: $save", save.startsWith("Save"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Back on the first run, the service done: the one button adds the torrent indexer.
        waitFor(hasText("Add a torrent indexer") and hasClickAction(), 15_000)
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, hasText("Add a torrent indexer") and hasClickAction(), "Add a torrent indexer", max = 4)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Add torrent indexer"))
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, fieldGate("Name"), "the Name field", max = 4)
        type("Name", "Home Prowlarr")
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, fieldGate("API URL"), "the API URL field", max = 3)
        type("API URL", DebridFakes.PROWLARR_URL)
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, fieldGate("API key"), "the API key field", max = 3)
        type("API key", DebridFakes.TORZNAB_KEY)
        pressUntil(KeyEvent.KEYCODE_DPAD_UP, hasText("Save") and hasClickAction(), "Save", max = 8)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForHomeFocus(timeoutMs = 15_000)
        runBlocking {
            assertEquals(listOf("TorBox"), settings.services.first().filter { !it.deleted }.map { it.kind })
            assertEquals(listOf("Home Prowlarr"), settings.torrentIndexers.first().filter { !it.deleted }.map { it.name })
            // And Usenet's side, for a list of both kinds: the mock NNTP server and the shared fake indexer.
            settings.saveProvider(ProviderRecord("", "Mock (debrid TV end to end)", "10.0.2.2", 1119, useTls = false, connections = 4), "")
            settings.saveIndexer(DebridSearchFixture.INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key")
        }

        // Search: the words typed with the TV's keyboard, the indexers asked from the right pane's button: both answer.
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        waitFor("Search's field", 5_000) { remote.isFocused(fieldGate("Search")).takeIf { it } }
        type("Search", "harbor lights")
        // Right into the right pane (onto TMDB's line where no title is suggested), Up to Search indexers at its top.
        val searchIndexers = hasContentDescription("Search indexers for “harbor lights”")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressUntil(KeyEvent.KEYCODE_DPAD_UP, searchIndexers, "Search indexers", max = 4)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("both indexers' pills, each answered, Home Prowlarr's torrents checked", 40_000) {
            listOf("Indexer A", "Home Prowlarr").all { exists(pill(it)) && !exists(pill(it) and waiting) }.takeIf { it }
        }

        // Harbor Lights' page: S01E02's ⋮, Choose release; down the list to the season pack TorBox has cached.
        compose.runOnIdle { nav.navigate(ShowRoute(DebridSearchFixture.HARBOR_LIGHTS)) }
        val episode = hasText(DebridSearchFixture.EPISODES[1], substring = true) and hasClickAction()
        waitFor(episode, 15_000)
        val shownAt = SystemClock.uptimeMillis()
        // As the remote reaches it: a row's focus is the list's to give, Down from the chips entering at the next one.
        compose.onNode(episode).requestFocus()
        remote.waitForFocus(DebridSearchFixture.EPISODES[1])
        assertEquals("Right from the row", "More for episode 2", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Past the page's guard against the OK that opened it (OkGuard's 400 ms).
        waitFor("the page's OK guard to pass", 5_000) { (SystemClock.uptimeMillis() - shownAt >= 700).takeIf { it } }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Choose release")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val pack = card(PACK, "From TorBox")
        waitFor("the pack in S01E02's list beside Usenet's releases", 40_000) { (exists(pack) && exists(card(GOOD, "From Usenet"))).takeIf { it } }
        waitFor("the list's first focus", 10_000) { remote.focused().takeIf { "Best match" in it } }
        pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, pack, "the pack", max = 8)
        // Links made from now on die 60 s after they are made, as a service's do after their hour.
        DebridFakes.control("cdn?expire=60")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
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
            hideControls(player)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("OK to pause") { onMain { (!player.player!!.playWhenReady).takeIf { it } } }
            val opened = cdn(206)

            // Right held a second and a half: one seek on to near the end, past what the player holds, a ranged open
            // there (a press at a time would stay inside what the player reads on from its first open). Short of the
            // last 10 s, where the next episode's card takes the remote's keys.
            val from = onMain { player.player!!.currentPosition }
            hideControls(player)
            hold(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT_HELD_MS)
            val at = waitFor("Right held to seek on from $from ms", 30_000) {
                onMain { player.player!!.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition >= from + RIGHT_HELD_MS * 20 }?.currentPosition }
            }
            assertTrue("short of the next episode's card: at $at of $duration", at < duration - 10_000)

            // Paused until 70 s past the link's making: it died at 60.
            waitFor("70 s past the link", 90_000) { (SystemClock.elapsedRealtime() - madeAt >= PAUSE_PAST_MS).takeIf { it } }
            assertEquals("no fresh link while paused", 1, calls("torbox requestdl"))

            // Left: back to what the player no longer holds, through the dead link and one fresh one; OK plays on.
            hideControls(player)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            val back = try {
                waitFor("Left to seek back", 30_000) { onMain { player.player!!.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition <= at - 5_000 }?.currentPosition } }
            } catch (e: AssertionError) {
                val p = onMain { player.player!!.let { "at ${it.currentPosition} of ${it.duration}, state ${it.playbackState}, playWhenReady ${it.playWhenReady}, error ${it.playerError?.errorCodeName}" } }
                throw AssertionError("${e.message}: from $at; the player $p; links ${calls("torbox requestdl")}, CDN ${counts().getJSONObject("cdn")}; shown ${onMain { overlayWords(player) }.take(12)}", e)
            }
            hideControls(player)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the play to go on", 30_000) { onMain { player.player?.takeIf { it.isPlaying && it.currentPosition >= back + 1_000 } } }
            assertFalse("nothing shown", "Playback stopped" in onMain { overlayWords(player) })
            assertEquals("one fresh link", 2, calls("torbox requestdl"))
            assertTrue("the dead link refused", cdn(403) >= 1)
            assertEquals("every open a ranged one: no 200", 0, cdn(200))
            assertTrue("the seeks' opens, ranged", cdn(206) - opened >= 2)
            assertTrue("played on past the minute under which a play starts again from its beginning", back >= RESUME_FROM_MS)

            // Links made from now on last. Back leaves the player, which saves where it was.
            DebridFakes.control("cdn")
            hideControls(player)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back to leave the player") { onMain { (player.isDestroyed || player.isFinishing).takeIf { it } } }
        }

        // The page's Resume, the remote on it: the pack again from where it stopped, nothing added again. A show page's
        // Resume plays the episode, so the indexers are searched, and the release that played it is tried first.
        val resume = hasText("Resume S01E02", substring = true) and hasClickAction()
        waitFor(resume, 15_000)
        // Where Back leaves the remote; else on it as Left and Up along the page would put it.
        if (!remote.isFocused(resume)) compose.onNode(resume).requestFocus()
        waitFor("the remote on Resume", 5_000) { remote.isFocused(resume).takeIf { it } }
        seen.clear()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val resumed = launched()
        assertEquals(PACK, resumed.release.title)
        assertEquals(E02, resumed.release.fileName)
        val first = seen.filterIsInstance<PlayState.Running>().first()
        assertEquals("the pack tried first", PACK to 1, first.release to first.attempt)
        assertEquals("nothing added again", 1, calls("torbox createtorrent"))
        playing(resumed) { player ->
            val from = waitFor("Resume to play", 60_000) { onMain { player.player?.takeIf { it.isPlaying }?.currentPosition } }
            assertTrue("from where it stopped ($from)", from >= RESUME_FROM_MS - 1_000)

            // The player's Next, OK on it: S01E03, from the pack playing; the player closes for it.
            onMain {
                playerView(player).controllerShowTimeoutMs = 0
                playerView(player).showController()
            }
            val next = waitFor("Next to be enabled") { onMain { player.findViewById<View>(androidx.media3.ui.R.id.exo_next).takeIf { it.isEnabled } } }
            onMain { next.requestFocus() }
            waitFor("Next focused", 5_000) { onMain { (player.currentFocus === next).takeIf { it } } }
            seen.clear()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the player to close") { onMain { player.isDestroyed.takeIf { it } } }
        }
        val next = launched()
        assertEquals(PACK, next.release.title)
        assertEquals(E03, next.release.fileName)
        assertFalse("the next episode searched", searched())
        assertTrue("the pack's own line", seen.any { it is PlayState.Searching && it.line.startsWith(PlayingPack.SEARCH_LINE) })
        assertEquals("nothing added again", 1, calls("torbox createtorrent"))

        // File details (DbPhoneFileDetails on a TV): Up from play/pause, Right to ⓘ, OK; down to Source.
        playing(next) { player ->
            waitFor("the player to know the file's tracks", 60_000) { onMain { player.player?.currentTracks?.takeUnless { it.isEmpty } } }
            onMain {
                playerView(player).controllerShowTimeoutMs = 0
                playerView(player).showController()
            }
            waitFor("the controls, play/pause focused") { onMain { (playerView(player).isControllerFullyVisible && player.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            onMain {
                val up = player.currentFocus!!.focusSearch(View.FOCUS_UP)
                assertTrue("Up from play/pause: Audio and subtitles", up === button(player, "Audio and subtitles"))
                up.requestFocus()
                val right = up.focusSearch(View.FOCUS_RIGHT)
                assertTrue("Right from Audio and subtitles: File details", right === button(player, "File details"))
                right.requestFocus()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the panel, on its first section") { onMain { focusedSection(player)?.takeIf { it == "Release" } } }
            listOf("File", "Video", "On this device", "Source").forEach { section ->
                onMain { player.panelFocus!!.moveFocus(FocusDirection.Down) }
                waitFor<String>("$section focused") { onMain { focusedSection(player)?.takeIf { it == section } } }
            }
            val rows = onMain { rows(player) }
            assertEquals(listOf("Home Prowlarr"), rows["Indexer"])
            assertEquals(listOf("TorBox", "a cached torrent, streamed from the service"), rows["Service"])
            assertFalse("no providers: $rows", "Providers" in rows)
            val words = onMain { paneWords(player) }
            listOf(next.release.guid.removePrefix("btih:"), DebridFakes.TORBOX_ACCOUNT, "/cdn/", DebridFakes.HOST).forEach { secret ->
                assertTrue("the panel shows what it mustn't", words.none { secret in it })
            }
        }

        // The build's gate closed (L192): Down Settings' Sources passes no debrid row; what was saved stays saved.
        DebridGate.shownForTests = false
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        remote.waitForFocus("Usenet providers", timeoutMs = 10_000)
        val passed = mutableListOf<String>()
        while (passed.size < 4 && passed.lastOrNull()?.startsWith("Metadata") != true) passed += remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Usenet providers: $passed", passed.firstOrNull()?.startsWith("Indexers") == true && passed.lastOrNull()?.startsWith("Metadata") == true)
        assertFalse("a debrid row: $passed", passed.any { it.startsWith("Debrid services") || it.startsWith("Torrent indexers") })
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
            if (!onMain { player.isDestroyed || player.isFinishing }) {
                onMain { player.finish() }
                waitFor("the player to close") { onMain { player.isDestroyed.takeIf { it } } }
            }
        }
    }

    /** [keyCode] held [heldMs], as a remote's key is: its down, a repeat each half second, its up. */
    private fun hold(keyCode: Int, heldMs: Long) {
        val down = SystemClock.uptimeMillis() - heldMs
        fun key(action: Int, atMs: Long, repeat: Int) = KeyEvent(down, down + atMs, action, keyCode, repeat)
        instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, 0, 0))
        (1..(heldMs / 500).toInt()).forEach { n -> instrumentation.sendKeySync(key(KeyEvent.ACTION_DOWN, n * 500L, n)) }
        instrumentation.sendKeySync(key(KeyEvent.ACTION_UP, heldMs, 0))
    }

    /** The player's controls hidden, as they are while a remote watches: OK and the arrows then act on the playing. */
    private fun hideControls(player: PlayerActivity) {
        onMain { playerView(player).hideController() }
        waitFor("the controls to hide") { onMain { (!playerView(player).isControllerFullyVisible).takeIf { it } } }
        // As TvPlayerSmokeTest: the controls' hiding settles before a key comes.
        Thread.sleep(800)
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

    private fun focusedSection(activity: Activity): String? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(FileDetailsSection) }?.config?.get(FileDetailsSection)

    private companion object {
        /** FakeTorrents' and FakeCatalog's names (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"
        const val E02 = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv"
        const val E03 = "Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv"

        /** How long the fake's video must run for the seeks to land past the player's 50 s buffer. */
        const val MIN_VIDEO_MS = 80_000L

        /** How long Right is held: on 10 s and 40 ms a millisecond held (ScrubRate), from the start to about 70 s in. */
        const val RIGHT_HELD_MS = 1_500L

        /** The pause runs until this long after the link was made: past its 60 s. */
        const val PAUSE_PAST_MS = 70_000L

        /** The player starts a play saved under a minute in from its beginning: Resume's needs to be past it. */
        const val RESUME_FROM_MS = 60_000L
    }
}

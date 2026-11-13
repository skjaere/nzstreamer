package io.skjaere.usenet.app.debrid

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

/**
 * A debrid play's links over the fakes (L172, W81, DbPhoneFailures): Harbor Lights S01E02 from the release TorBox has
 * cached plays in the player while the debrid fake's CDN is made to fail under it. A link that expires 20 s after it was
 * made plays on past that through a seek, with one fresh link in the fake's counts, every open a ranged one, and nothing
 * shown; a file gone from the CDN stops the play with "No longer available from the service." and the panel's usual
 * buttons (no "Play again"), and the release is remembered an hour against its account; a key the service refuses mid-play
 * stops it with the refused key's own words, and the account is marked; a service that stops answering mid-play stops its
 * account for the rest of the play, so Try another release tries none of its releases. Played as Play's own pick plays it,
 * with the Usenet indexer off ([DebridSearchFixture]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceLinkDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixture = DebridSearchFixture()

    @Before
    fun setUp() {
        fixture.open()
        fixture.usenetIndexerOn(false)
        onMainThread { container.playback.withdraw() }
    }

    @After
    fun tearDown() {
        onMainThread { container.playback.cancel() }
        fixture.close()
    }

    private val episode = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = 2,
        episodeName = DebridSearchFixture.EPISODES[1], airDate = fixture.today.minusDays(53), runtimeMinutes = 45,
    )

    /** The coordinator's launch of what Play picked; fails the test if the sheet shows a failure instead. */
    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    /** The links TorBox gave: Opening's, and each fresh one. */
    private fun links(): Int = counts().getJSONObject("calls").getInt("torbox requestdl")

    private fun cdn(status: Int): Int = counts().getJSONObject("cdn").getInt("$status")

    /**
     * Plays S01E02 in the player until it plays, paused there; [then] with the player, the play's launch and [madeAt] (the
     * elapsed time once its first link was made: Opening made it before the launch came, however long the search and
     * Preparing took, DDX-1).
     */
    private fun playing(then: suspend (scenario: ActivityScenario<PlayerActivity>, launch: PlayerLaunch, madeAt: Long) -> Unit) = runBlocking<Unit> {
        onMainThread { container.playback.playEpisode(episode) }
        val launch = launched()
        val madeAt = SystemClock.elapsedRealtime()
        assertEquals(GOOD, launch.release.title)
        assertEquals("Opening's link", 1, links())
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the episode to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            scenario.onActivity { it.player?.pause() }
            // Each case seeks to 10 s before the end, past the 50 s the player holds, to make it open the file again there
            // (DDX-5): a shorter video would end in the buffer, and the case fail as if the refresh or the panel had.
            val duration = scenario.read { it.player!!.duration }
            assumeTrue("the debrid fake's video runs ${duration / 1000} s: start it with the README's 90 s mock video, as a seek past the buffer needs 80 s", duration >= MIN_VIDEO_MS)
            then(scenario, launch, madeAt)
        }
    }

    /** The one TorBox account the fixture saved, by its id. */
    private suspend fun account(): String = container.settings.services.first().single { !it.deleted }.id

    /** Presses the shown button whose words are [words], as a tap does. */
    private fun ActivityScenario<PlayerActivity>.press(words: String) = onActivity { a ->
        val node = overlayNodes(a).first { words in it.words() }
        val button = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        button.config[SemanticsActions.OnClick].action?.invoke()
    }

    /** Seeks to 10 s before the end, past what the player holds, so it opens the file again there, and plays. */
    private fun ActivityScenario<PlayerActivity>.seekNearTheEnd(): Long {
        val to = read { it.player!!.duration } - 10_000
        onActivity { a ->
            a.player?.seekTo(to)
            a.player?.play()
        }
        return to
    }

    /**
     * The service slow to answer (1.5 s a call), as one waiting out its limit is: the link comes seconds after the play
     * starts, and the seek is timed from when it exists, so it meets the link expired however long the search and Preparing
     * took (DDX-1). The fresh link is the seek's: none before it.
     */
    @Test
    fun aLinkThatExpiresPlaysOnPastItThroughASeekWithOneFreshLinkAndNothingShown() {
        DebridFakes.control("cdn?expire=20")
        DebridFakes.control("slow?ms=1500")
        playing { scenario, _, madeAt ->
            // Past the link's 20 s: the open the seek makes meets it expired.
            delay(maxOf(0, 22_000 - (SystemClock.elapsedRealtime() - madeAt)))
            assertEquals("no fresh link before the seek", 1 to 0, links() to cdn(403))
            val to = scenario.seekNearTheEnd()
            waitFor("the play to go on from the seek", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition >= to } }
            }
            assertFalse("nothing shown", "Playback stopped" in scenario.read(::overlayWords))
        }
        assertEquals("one fresh link", 2, links())
        assertTrue("the expired link was refused", cdn(403) >= 1)
        assertEquals("every open a ranged one: no 200", 0, cdn(200))
    }

    @Test
    fun aFileGoneFromTheServiceStopsThePlayWithThePanelsUsualButtons() = playing { scenario, launch, _ ->
        DebridFakes.control("cdn-gone?on=true")
        scenario.seekNearTheEnd()
        val words = waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
        assertTrue(words.toString(), "No longer available from the service." in words)
        listOf("Close", "Try another release", "File details").forEach { assertTrue("$it in $words", it in words) }
        assertFalse("no Play again", "Play again" in words)
        assertEquals("one fresh link asked for, which failed too", 2, links())
        // Remembered an hour against the account that played it (L174, DDX-2): the next play passes it over there.
        val account = account()
        val release = PlayCandidate(launch.release.key, launch.release.title, launch.release.indexerId, source = "torrent", routes = listOf("service:$account"))
        waitFor("the failure remembered", timeoutMs = 10_000) { container.serviceMisses.known(release, account) }
            .let { assertEquals(AttemptFailure.NoWorkingLink, it) }
    }

    /**
     * DDX-2, L164, L204: a service that stops answering mid-play (its link dead, its refresh refused as down) stops that
     * account for the rest of the play: Try another release leaves out every release that plays from it alone, so none is
     * tried though the service answers again by then, and the episode's search, with the Usenet indexer off, finds nothing
     * else to play.
     */
    @Test
    fun aServiceThatStopsAnsweringMidPlayLeavesItsReleasesOutOfTryAnother() = playing { scenario, _, _ ->
        DebridFakes.control("down?on=true")
        DebridFakes.control("cdn-expire")
        scenario.seekNearTheEnd()
        val words = waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
        assertTrue(words.toString(), "The debrid service stopped answering. Try again in a few minutes." in words)
        DebridFakes.control("down?on=false")
        scenario.press("Try another release")
        // The search's end, or a release's attempt starting: none may, its account stopped.
        val end = withTimeout(120_000) {
            container.playback.state.first { it is PlayState.NoRelease || it is PlayState.Failed || it is PlayState.Running }
        }
        assertTrue("a release of the stopped account was tried: ${(end as? PlayState.Running)?.release ?: end}", end is PlayState.NoRelease)
    }

    @Test
    fun aKeyTheServiceRefusesMidPlayStopsItWithTheRefusedKeysWords() = playing { scenario, _, _ ->
        DebridFakes.control("refuse-keys?on=true")
        DebridFakes.control("cdn-expire")
        scenario.seekNearTheEnd()
        val words = waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
        assertTrue(words.toString(), "TorBox refused your API key. Replace it in Settings › Debrid services." in words)
        val account = container.settings.services.first().single { !it.deleted }
        assertTrue("the account is marked until a new key", account.keyRefused)
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    /** Every semantics node of the shown Compose views: the panel among them. */
    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsProperties.PaneTitle))

    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { it.words() }

    private companion object {
        /** FakeTorrents' S01E02 (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"

        /** How long the fake's video must run for a seek to 10 s before its end to land past the player's 50 s buffer. */
        const val MIN_VIDEO_MS = 80_000L
    }
}

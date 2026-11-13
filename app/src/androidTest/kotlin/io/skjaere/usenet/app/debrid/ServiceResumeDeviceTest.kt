package io.skjaere.usenet.app.debrid

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.PlayingPack
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DbTvPlaySheet's note over the fakes (L181, L184): S01E02 played from the season pack TorBox has cached, stopped past a
 * minute in (the player starts a play stopped sooner from its beginning) and its player closed, then the listings the app
 * keeps forgotten in memory (their file kept, as after a new process): Resume from Home plays it again from where it
 * stopped, with no search and nothing added to the account again (the debrid fake's counts), and Next from the pack plays
 * S01E03 from it, with no search either. Written with item 28; its plays need the player's HTTPS source and the debrid
 * fake's CDN (items 30-32), and a video over the minute and more (the README's 90 s mock video as the fake's media).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceResumeDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixture = DebridSearchFixture()

    /** Every sheet the coordinator showed, in order: collected as each is set, none passed over. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null

    @Before
    fun setUp() {
        fixture.open()
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch { container.playback.state.collect { seen += it } }
    }

    @After
    fun tearDown() {
        watching?.cancel()
        onMainThread { container.playback.cancel() }
        fixture.close()
    }

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = DebridSearchFixture.EPISODES[n - 1], airDate = fixture.today.minusDays(60L - 7 * (n - 1)), runtimeMinutes = 45,
    )

    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    private fun added(): Int = JSONObject(DebridFakes.control("counts")).getJSONObject("calls").getInt("torbox createtorrent")

    /** Whether the play searched the indexers: the sheet would have said so. */
    private fun searched(): Boolean = seen.any { it is PlayState.Searching && it.line == "Searching your indexers…" }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    @Test
    fun aTorrentResumesWhereItStoppedAndItsPackPlaysTheNextEpisodeWithNoSearchAndNothingAdded() = runBlocking<Unit> {
        fixture.usenetIndexerOn(false)
        val ep2 = episode(2)
        // S01E02 from the season pack alone, as a list's pick plays it.
        val events = container.finder.find(ep2.request, container.indexerConfigs(), container.rankingContext(), null).toList()
        val result = events.filterIsInstance<FinderEvent.Joined>().lastOrNull()?.result ?: events.filterIsInstance<FinderEvent.Finished>().single().result
        val pack = result.playable.map { RankedItem(it.item, it.ranked) }.single { it.item.title == PACK }
        onMainThread { container.playback.play(PlayRequest(ep2.showName, ep2.subtitle, listOf(pack), FilePicker.Episode(1, 2), ep2.watch, rememberedFirst = false)) }
        val first = launched()
        assertEquals(PACK, first.release.title)
        assertEquals(1, added())

        // Played partway, then the player closed: its progress saved there.
        var at = 0L
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            val duration = waitFor("the film to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying }?.duration?.takeIf { it > 0 } } }
            assumeTrue("the debrid fake's video is too short to stop past a minute in (the README's 90 s mock video)", duration >= AT_MS + 10_000)
            at = AT_MS
            scenario.onActivity { it.player?.seekTo(at) }
            waitFor("the place it stops at") { scenario.read { a -> a.player?.takeIf { it.currentPosition >= at && it.playbackState == Player.STATE_READY } } }
            scenario.onActivity { it.player?.pause() }
        }
        val saved = waitFor("its progress") { runBlocking { container.library.state(ep2.key).first() }?.takeIf { it.positionMs >= at - 1_000 } }

        // As after a new process: only the kept listings' file is left.
        container.debridListings.forgetInMemory()
        seen.clear()
        onMainThread { container.playback.resume(saved) }
        val resumed = launched()
        assertEquals(PACK, resumed.release.title)
        assertEquals(first.release.fileName, resumed.release.fileName)
        assertTrue("Resume searched", !searched())
        assertEquals("nothing added again", 1, added())
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, resumed)).use { scenario ->
            val from = waitFor("Resume to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying }?.currentPosition } }
            assertTrue("from where it stopped ($from of $at)", from >= at - 1_000)
        }

        // Next from the pack: S01E03 from the same release, with no search.
        seen.clear()
        onMainThread { container.playback.playEpisode(episode(3), playing = resumed.release) }
        val next = launched()
        assertEquals(PACK, next.release.title)
        assertEquals("Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv", next.release.fileName)
        assertTrue("the next episode searched", !searched())
        assertTrue(seen.any { it is PlayState.Searching && it.line.startsWith(PlayingPack.SEARCH_LINE) })
        assertEquals("nothing added again", 1, added())
    }

    private companion object {
        /** FakeTorrents' season pack (tools/mock-seed). */
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"

        /** Where the play stops: past the minute under which the player starts a play again from its beginning. */
        const val AT_MS = 70_000L
    }
}

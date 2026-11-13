package io.skjaere.usenet.app

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A play started while the player is open (#154): the player is singleTask, so the new launch
 * reaches the open one, which saves where the first play was and plays the second. Two invented
 * films ("Lantern Field" and "Copper Crown", TMDB ids nothing real has) by the chapters.mkv and
 * tracks.mkv fixtures on the mock NNTP server (see SkipSegmentsDeviceTest and TrackSheetDeviceTest).
 * Their library rows are left at position 0, out of Up next.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerNewIntentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private lateinit var chapters: PreparedNzb
    private lateinit var tracks: PreparedNzb

    @Before
    fun setUp() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        runBlocking {
            engineSetup("the mock on the engine, the chapters and tracks fixtures prepared") {
                mockOnEngine()
                chapters = prepareFixture("chapters")
                tracks = prepareFixture("tracks")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        runBlocking {
            listOf(FIRST to "Lantern Field", SECOND to "Copper Crown").forEach { (id, title) ->
                container.library.recordProgress(WatchState(WatchKey.movie(id), WatchKind.Movie, movieTmdbId = id, title = title, updatedAt = 0), 0, 60_000)
            }
        }
    }

    private fun launch(nzb: PreparedNzb, id: Int, title: String): PlayerLaunch {
        val file = nzb.defaultPick!!.index
        val watch = WatchTarget(WatchKind.Movie, title, movieTmdbId = id)
        return PlayerLaunch(
            uri = UsenetUris.build(nzb.id, file), title = title, subtitle = null, watchKey = watch.key(nzb.id, file), watch = watch,
            release = SavedRelease("new-intent-device-test", nzb.id, nzb.title, null, file, nzb.id),
        )
    }

    @Test
    fun aSecondLaunchWhileOnePlaysPlaysTheSecondFile() = runBlocking<Unit> {
        val first = launch(chapters, FIRST, "Lantern Field")
        val second = launch(tracks, SECOND, "Copper Crown")
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            waitFor("the first file to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            scenario.onActivity { it.player!!.seekTo(90_000) }
            waitFor("the first file to play on at 1:30") { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition >= 90_000 } } }
            val (activity, player) = scenario.read { it to it.player!! }

            // As the coordinator's launches start it, from outside the open player.
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            val playing = waitFor("the second file to play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri && it.playbackState == Player.STATE_READY } }
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { a ->
                assertSame("a second player activity opened", activity, a)
                assertNotSame("the first file's player plays on", player, a.player)
                // tracks.mkv is a minute long, chapters.mkv six.
                assertTrue("duration ${playing.duration}", playing.duration in 55_000L..65_000L)
                // From its start: neither the first file's position nor a saved one.
                assertTrue("started at ${playing.currentPosition}", playing.currentPosition < 10_000)
                assertTrue("the title still says ${titles(a)}", "Copper Crown" in titles(a) && "Lantern Field" !in titles(a))
            }
            // The first file's place was saved as it was replaced.
            val saved = withTimeout(5_000) { container.library.state(WatchKey.movie(FIRST)).first { it != null && it.positionMs >= 90_000 } }!!
            assertTrue("saved at ${saved.positionMs}", saved.positionMs in 90_000L..100_000L)
            // The second plays on and saves under its own key.
            delay(1_000)
            scenario.moveToState(Lifecycle.State.CREATED)
            val secondSaved = withTimeout(5_000) { container.library.state(WatchKey.movie(SECOND)).first { it != null && it.lastPlayedAt > saved.lastPlayedAt } }!!
            assertTrue("the second saved at ${secondSaved.positionMs}", secondSaved.positionMs < 15_000)
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    /** The top bar's texts. */
    private fun titles(activity: PlayerActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    private companion object {
        /** Invented films' TMDB ids; nothing real has them. */
        const val FIRST = 900021
        const val SECOND = 900010
    }
}

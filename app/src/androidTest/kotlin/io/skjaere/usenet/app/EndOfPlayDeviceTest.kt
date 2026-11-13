package io.skjaere.usenet.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.PictureInPicturePlays.Companion.FILM
import io.skjaere.usenet.app.PictureInPicturePlays.Companion.SHOW
import io.skjaere.usenet.app.PictureInPicturePlays.Companion.SHOW_NAME
import io.skjaere.usenet.app.PictureInPicturePlays.Companion.TITLE
import io.skjaere.usenet.app.play.ControlsPlayer
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The end of a play (E1-E6): the last frame is the end, so with nothing to move on to the player leaves as its back arrow
 * does, back to where the play started. A film played to its end leaves, saved as watched; so does an episode TMDB has
 * nothing after, and one whose next-episode lookup was still running at the end, once it answers with none. With a next
 * episode and auto-play off, the card stays at the end, and its Close then leaves. Paused and sought to the end, the user
 * is still there: the player stays; so it does under "Playback stopped". An episode played from its show's page lands on
 * that page, the episode watched. In the picture-in-picture window a film ending closes the window as its ✕ does, its
 * task with it, the app's screens left where they were. Fast-forward tapped in the last moments, or ⏩ held over the end,
 * doesn't keep the player on the last frame. The plays are PictureInPicturePlays' (a server inside the test's process);
 * the next-episode seam stands in for TMDB, and the TMDB seam for the show's page ([FakeTmdb]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class EndOfPlayDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    /** For the window's case; no other case leaves the player for Home, so nothing else goes into the window. */
    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val plays = PictureInPicturePlays(INDEXER)
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private var keyAdded = false

    @Before
    fun setUp() {
        runBlocking {
            plays.setUp()
            unwatched()
        }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
    }

    @After
    fun restore() {
        plays.close()
        tmdb?.close()
        home.restore()
        runBlocking {
            // Once the player's last save is in.
            delay(500)
            plays.forget()
            unwatched()
            container.library.removeSearch(RecentSearch.titleKey(OPENED))
            if (keyAdded) container.settings.setTmdbKey(null)
        }
    }

    /** The film and the episode unwatched, so a test sees its own play saved as watched. */
    private suspend fun unwatched() {
        container.library.markMovies(listOf(MovieMark(FILM, TITLE)), watched = false)
        container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 1, WatchTitle.episode(SHOW_NAME, 1, 1))), watched = false)
    }

    private fun launch(launch: PlayerLaunch): ActivityScenario<PlayerActivity> = ActivityScenario.launch(PlayerActivity.intent(context, launch))

    @Test
    fun aFilmPlayedToItsEndLeavesSavedAsWatched() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-to-the-end")).use { scenario ->
            plays.playing(scenario)
            nearTheEnd(scenario)
            awaitLeft(scenario, "at the film's end")
        }
        awaitWatched(WatchKey.movie(FILM))
    }

    /** The latest episode, or the next not aired yet, no TMDB key or TMDB failing: the lookup answers with none. */
    @Test
    fun anEpisodeWithNothingAfterItLeavesAtItsEnd() = runBlocking<Unit> {
        launch(plays.episodeLaunch("lowland-signal-nothing-after")).use { scenario ->
            plays.playing(scenario)
            nearTheEnd(scenario)
            awaitLeft(scenario, "at the episode's end")
        }
        awaitWatched(WatchKey.episode(SHOW, 1, 1))
    }

    /**
     * Fast-forward tapped in the film's last moments goes nowhere ("At the end"), and the file ends while the tap's seek
     * waits: the player leaves all the same, saved as watched, rather than staying on the last frame. The tap is the
     * controls' own step, its seek waiting [TAP_WAIT_MS] rather than a tap's half second, so the end comes inside the wait
     * however slowly the emulator plays the last moments: in a full run beside two other emulators, the end came after a
     * half-second wait had run out.
     */
    @Test
    fun aFastForwardTappedInTheLastMomentsLeavesAllTheSame() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-tapped-at-the-end")).use { scenario ->
            plays.playing(scenario)
            val waitedAtTheEnd = CompletableDeferred<Boolean>()
            scenario.onActivity { a -> atTheEnd(a) { waitedAtTheEnd.complete(controls(a).seeking) } }
            nearTheEnd(scenario)
            inTheLast(scenario, TAPPED_BEFORE_END_MS) { a -> controls(a).let { c -> c.step(c.seekForwardIncrement, waitMs = TAP_WAIT_MS) {} } }
            assertTrue("the end came while the tap's seek waited", withTimeout(20_000) { waitedAtTheEnd.await() })
            awaitLeft(scenario, "at the film's end, fast-forward tapped just before it")
        }
        awaitWatched(WatchKey.movie(FILM))
    }

    /**
     * ⏩ held over the end, as through the credits, and not let go: as the file ends the hold's seek runs, back to its
     * last second (a seek asked for in the last moments isn't lost), and as that runs out too the player leaves.
     */
    @Test
    fun aFastForwardHeldOverTheEndLeavesAllTheSame() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-held-over-the-end")).use { scenario ->
            plays.playing(scenario)
            val ends = AtomicInteger()
            scenario.onActivity { a -> atTheEnd(a) { ends.incrementAndGet() } }
            nearTheEnd(scenario)
            var down = 0L
            val player = inTheLast(scenario, HELD_BEFORE_END_MS) { a ->
                down = SystemClock.uptimeMillis()
                a.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 0))
            }
            // A remote's repeats while the key is down, as the hold waits for its up.
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var repeat = 0
            while (scenario.state != Lifecycle.State.DESTROYED) {
                if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for the player to leave at the film's end, ⏩ still held; ${ends.get()} ends")
                onMain {
                    if (!player.isFinishing) player.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, ++repeat))
                }
                delay(100)
            }
            assertEquals("the ends: the file's, then its last second's", 2, ends.get())
        }
        awaitWatched(WatchKey.movie(FILM))
    }

    /** The lookup still running at the end: the player waits there for its answer, and leaves only as it says none. */
    @Test
    fun atTheEndThePlayerWaitsForTheLookupThenLeavesAsItAnswersNone() = runBlocking<Unit> {
        val answer = CompletableDeferred<EpisodePlay?>()
        PlayerActivity.nextEpisode = { _, _ -> answer.await() }
        try {
            launch(plays.episodeLaunch("lowland-signal-lookup-held")).use { scenario ->
                plays.playing(scenario)
                nearTheEnd(scenario)
                awaitTheEnd(scenario)
                delay(STAYS_MS)
                assertEquals("the player while the lookup runs", Lifecycle.State.RESUMED, scenario.state)
                answer.complete(null)
                awaitLeft(scenario, "as the lookup answers none")
            }
        } finally {
            answer.complete(null)
        }
    }

    /** Auto-play off: the card waits at the end with Play now and Close, and the player with it; Close then leaves, and nothing plays. */
    @Test
    fun withANextEpisodeTheCardStaysAtTheEndAndItsCloseLeaves() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        launch(plays.episodeLaunch("lowland-signal-card-at-the-end")).use { scenario ->
            waitFor("the next-episode card", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Close" in it } } }
            nearTheEnd(scenario)
            awaitTheEnd(scenario)
            delay(STAYS_MS)
            assertEquals("the player with the card at the end", Lifecycle.State.RESUMED, scenario.state)
            val words = scenario.read(::overlayWords)
            assertTrue(words.toString(), "Play now" in words && "Close" in words)
            scenario.onActivity { clickOverlay(it, "Close") }
            awaitLeft(scenario, "as Close is pressed at the end")
        }
        assertEquals("the next episode's play", PlayState.Idle, container.playback.state.value)
    }

    /**
     * Paused, then the time bar dragged to the end: the user is still there. (Media3 keeps a paused player ready there, at
     * its last moment, rather than ended.)
     */
    @Test
    fun pausedAndSoughtToTheEndThePlayerStays() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-paused-at-the-end")).use { scenario ->
            plays.playing(scenario)
            scenario.onActivity { a ->
                val p = a.player!!
                p.pause()
                p.seekTo(p.duration)
            }
            waitFor("the end, paused", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { !it.playWhenReady && it.currentPosition >= it.duration - 1_000 } } }
            delay(STAYS_MS)
            assertEquals("the player paused at the end", Lifecycle.State.RESUMED, scenario.state)
            assertNotNull("the player paused at the end", scenario.read { it.player })
        }
    }

    /** A failure isn't an end: "Playback stopped" stays until the user leaves it. */
    @Test
    fun underPlaybackStoppedThePlayerStays() = runBlocking<Unit> {
        val refuse = AtomicBoolean(false)
        plays.refusing(refuse)
        launch(plays.launch("copper-tide-stopped")).use { scenario ->
            plays.playing(scenario)
            plays.failByASeek(scenario, refuse)
            waitFor("Playback stopped", timeoutMs = 20_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            delay(STAYS_MS)
            assertEquals("the player under Playback stopped", Lifecycle.State.RESUMED, scenario.state)
            assertTrue(scenario.read(::overlayWords).toString(), "Playback stopped" in scenario.read(::overlayWords))
        }
    }

    /**
     * Played from the show's page, as the page's play starts the player over the app's screens (here as
     * PictureInPictureChainDeviceTest starts it): at the end the player leaves for that page, which shows the episode
     * watched.
     */
    @Test
    fun anEpisodePlayedFromItsShowsPageLeavesForThatPage() = runBlocking<Unit> {
        assumeFalse("a TV's Home and pages", container.television)
        showsPage()
        val launch = plays.episodeLaunch("lowland-signal-from-its-page")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
            waitFor("Home's chip for the show", timeoutMs = 15_000) { main.read { a -> overlayWords(a).takeIf { SHOW_NAME in it } } }
            main.onActivity { clickOverlay(it, SHOW_NAME) }
            waitFor("the show's page", timeoutMs = 15_000) { main.read { a -> overlayWords(a).takeIf { EPISODE_ONE in it } } }
            main.onActivity { PlayerActivity.start(it, launch) }
            val player = waitFor("the episode to play", timeoutMs = 30_000) { onMain { live<PlayerActivity>(Stage.RESUMED).firstOrNull { it.player?.isPlaying == true } } }
            onMain { player.player!!.let { it.seekTo(it.duration - BEFORE_END_MS) } }
            waitFor("the player to leave at the episode's end", timeoutMs = 20_000) { onMain { true.takeIf { player.isDestroyed } } }
            waitFor("the app's screens") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
            assertTrue("the show's page after the player", EPISODE_ONE in main.read(::overlayWords))
            waitFor("the episode shown watched on its page", timeoutMs = 10_000) { main.read { a -> overlayWords(a).takeIf { "✓" in it } } }
        }
        awaitWatched(WatchKey.episode(SHOW, 1, 1))
    }

    /**
     * E4: a film ending in the window closes it as its ✕ does, saved as watched; the player's task goes with it, not left
     * in Recents with nothing in it, the app's screens' task is all that is left (beside the empty ones earlier tests'
     * windows left there before this one began), and nothing is brought forward (the user is elsewhere).
     */
    @Test
    fun aFilmEndingInTheWindowClosesIt() = runBlocking<Unit> {
        plays.assumeWindow()
        val leftBefore = appTasks().filter { it.second == null }.map { it.first }.toSet()
        val launch = plays.launch("copper-tide-ends-in-the-window")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
            waitFor("the app") { true.takeIf { main.isIn(Lifecycle.State.RESUMED) } }
            main.onActivity { PlayerActivity.start(it, launch) }
            val player = waitFor("the film to play", timeoutMs = 30_000) { onMain { live<PlayerActivity>(Stage.RESUMED).firstOrNull { it.player?.isPlaying == true } } }
            // Turned to landscape over the app's screens and settled there: Home while the screen still turned has left the
            // window placed for the other way round, where it never settles (PictureInPictureChainDeviceTest waits as long).
            waitFor("the film a few seconds in", timeoutMs = 15_000) { onMain { player.player?.takeIf { it.currentPosition >= 3_000 } } }
            pressHome()
            awaitPinned(player)
            val task = onMain { player.taskId }
            // Where it was as it ended: in the window, not taken out of it first.
            val pinnedAtTheEnd = CompletableDeferred<Boolean>()
            onMain {
                val p = player.player!!
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) pinnedAtTheEnd.complete(player.isInPictureInPictureMode)
                    }
                })
                p.seekTo(p.duration - BEFORE_END_MS)
            }
            waitFor("the window to close at the film's end", timeoutMs = 20_000) { onMain { true.takeIf { player.isDestroyed } } }
            assertTrue("in the window at the end", withTimeout(1_000) { pinnedAtTheEnd.await() })
            waitFor("the window's task to go", timeoutMs = 5_000) { true.takeIf { appTasks().none { it.first == task } } }
            val left = appTasks().filterNot { it.second == null && it.first in leftBefore }
            assertEquals("the app's tasks left", listOf(MainActivity::class.java.name), left.map { it.second })
            // Time for the app's screens to come up, were they brought forward.
            delay(1_500)
            assertTrue("the app's screens brought forward", main.isIn(Lifecycle.State.CREATED))
        }
        awaitWatched(WatchKey.movie(FILM))
    }

    /** Lowland Signal's page, reached from Home's chip for it as a title opened from Search: TMDB (the fake) has its first season. */
    private suspend fun showsPage() {
        home.show()
        // The page's lookups need a key; the seam answers in TMDB's place.
        if (container.settings.tmdbKey() == null) {
            container.settings.setTmdbKey("end-of-play-test")
            keyAdded = true
        }
        val aired = LocalDate.now().minusDays(14)
        val show = ShowDetails(
            SHOW, SHOW_NAME, null, aired.year, null, null, null, "Returning Series", true, 45,
            listOf(SeasonSummary(1, null, 1, aired, null)), lastEpisode = EpisodeSummary(1, 1, "Low Water", aired), nextEpisode = null, ids = ExternalIds(),
        )
        val season = Season(SHOW, 1, null, null, listOf(Episode(1, 1, "Low Water", null, aired, 45, null)))
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, number -> season.takeIf { id == SHOW && number == 1 } })
        container.library.addTitle(OPENED)
    }

    /** Into the film's last seconds, playing. */
    private suspend fun nearTheEnd(scenario: ActivityScenario<PlayerActivity>) {
        val duration = waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
        scenario.onActivity { it.player!!.seekTo(duration - BEFORE_END_MS) }
    }

    /**
     * [act] on the main thread the moment the play, still playing, is within [ms] of its end (looked for every 10 ms, as
     * the moments before the end are short); the player then.
     */
    private suspend fun inTheLast(scenario: ActivityScenario<PlayerActivity>, ms: Long, act: (PlayerActivity) -> Unit): PlayerActivity {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (true) {
            val found = scenario.read { a -> a.takeIf { a.player?.let { it.isPlaying && it.currentPosition >= it.duration - ms } == true }?.also(act) }
            if (found != null) return found
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for the play's last $ms ms")
            delay(10)
        }
    }

    /** [heard] as the player of [a] ends, after the player's own listeners have heard it. */
    private fun atTheEnd(a: PlayerActivity, heard: () -> Unit) {
        a.player!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) heard()
            }
        })
    }

    /** The player as the controls see it, where rewind and fast-forward go. */
    private fun controls(a: PlayerActivity): ControlsPlayer = views(a.window.decorView).filterIsInstance<PlayerView>().single().player as ControlsPlayer

    private suspend fun awaitTheEnd(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("the end", timeoutMs = 20_000) { scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_ENDED } } }
    }

    private suspend fun awaitLeft(scenario: ActivityScenario<PlayerActivity>, how: String) {
        waitFor("the player to leave $how", timeoutMs = 20_000) { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
    }

    private suspend fun awaitWatched(key: String) {
        waitFor("$key saved as watched") { runBlocking { container.library.state(key).first()?.watched }?.takeIf { it } }
    }

    private fun <T> ActivityScenario<MainActivity>.read(probe: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    /**
     * The app's tasks, each one's id and the class of the activity at its base: none for a task with no activity in it,
     * which Recents keeps when its last one finished without taking it along, as a window an earlier test opened can leave.
     */
    private fun appTasks(): List<Pair<Int, String?>> =
        context.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { t ->
            runCatching { t.taskInfo.let { info -> info.taskId to info.baseActivity?.className?.takeIf { info.numActivities > 0 } } }.getOrNull()
        }

    /** Whether the activity is in [wanted]: not while it moves between stages, where ActivityScenario has no state to give and throws. */
    private fun ActivityScenario<*>.isIn(wanted: Lifecycle.State): Boolean =
        try {
            state == wanted
        } catch (e: NullPointerException) {
            false
        }

    /** This process's activities of [A] in [stages] (on the main thread, as the monitor wants). */
    private inline fun <reified A : Activity> live(vararg stages: Stage): List<A> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<A>().distinct()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        const val INDEXER = "end-of-play-device-test"

        /** Where a play is sought to before its end: it ends a few seconds later, playing. */
        const val BEFORE_END_MS = 3_000L

        /** How near the end fast-forward is tapped: inside the file's last second, the end within the tap's wait. */
        const val TAPPED_BEFORE_END_MS = 300L

        /** How long the tap's seek waits: long past the end, which then always comes while it waits. */
        const val TAP_WAIT_MS = 10_000L

        /** How near the end ⏩ goes down: before the file's last second, which the hold's seek goes back to at the end. */
        const val HELD_BEFORE_END_MS = 2_500L

        /** How long the player is watched staying where it should. */
        const val STAYS_MS = 2_000L

        /** The show as Search opened it, which Home offers again. */
        val OPENED = SearchPick(TitleType.Tv, SHOW, SHOW_NAME)

        /** The page's first episode, by its More button. */
        const val EPISODE_ONE = "More for episode 1"
    }
}

package io.skjaere.usenet.app

import android.content.Intent
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The player's media session (L14-L17), as what controls a play from outside the app finds it: a controller sees the
 * play as the top bar shows it (a play without TMDB as the app alone, never by its release or file), and what it may
 * do; it pauses, plays and seeks through the controls' burst, its Next plays the next episode as the controls' does,
 * and its Stop leaves the player. The session Android hands media keys to offers what the play does, Next once the
 * lookup knows it. A media key acts once, through the player's window or through Android; with the sheet open ⏩ and
 * Next do nothing, and under "Playback stopped" Play doesn't start the failed stream again. One session for the player,
 * made again or given another play; none once it closes. In the picture-in-picture window, where the player's window
 * takes no keys, Android's Pause and Play reach the play through the session, and Next and Previous do nothing (L13).
 * On phones and a TV alike, but for the window; the film plays from a server inside the test's process
 * ([PictureInPicturePlays]).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaSessionDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    /** The window's tests go into it; for the others it is as if the run refused it, as nothing leaves the player. */
    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val plays = PictureInPicturePlays(INDEXER)
    private val controllers = mutableListOf<MediaController>()
    /** The watch key a play without TMDB saved its progress under, to forget. */
    private var bareKey: String? = null

    @Before
    fun setUp() {
        runBlocking { plays.setUp() }
    }

    @After
    fun restore() {
        controllers.forEach { c -> onMainThread { c.release() } }
        plays.close()
        runBlocking {
            plays.forget()
            bareKey?.let { key -> container.library.recordProgress(WatchState(key, WatchKind.Release, title = TITLE, updatedAt = 0), 0, 60_000) }
        }
    }

    private fun launch(launch: PlayerLaunch): ActivityScenario<PlayerActivity> = ActivityScenario.launch(PlayerActivity.intent(context, launch))

    /** A controller of the player's session, in the test's process. */
    private suspend fun controllerFor(scenario: ActivityScenario<PlayerActivity>): MediaController {
        val token = waitFor("the player's media session") { scenario.read { it.session?.token } }
        return controllerOf(token).also { controllers += it }
    }

    @Test
    fun aControllerSeesThePlayAsTheTopBarShowsItAndWhatItMayDo() = runBlocking<Unit> {
        launch(plays.episodeLaunch("lowland-signal-session")).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            waitFor("the controller to see the play") { onMainThread { controller.isPlaying.takeIf { it } } }
            onMainThread {
                assertEquals("S01E01 · Low Water", controller.mediaMetadata.title?.toString())
                assertEquals(PictureInPicturePlays.SHOW_NAME, controller.mediaMetadata.subtitle?.toString())
                assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, controller.mediaMetadata.mediaType)
                assertEquals(WatchKey.episode(PictureInPicturePlays.SHOW, 1, 1), controller.currentMediaItem?.mediaId)
                assertEquals(Player.STATE_READY, controller.playbackState)
                OFFERED.forEach { assertTrue("command $it", controller.isCommandAvailable(it)) }
                WITHHELD.forEach { assertFalse("command $it", controller.isCommandAvailable(it)) }
            }
            plays.finish(scenario)
        }
    }

    /** What watches, car displays and other apps read: the app's name for a play TMDB doesn't name, never the release's nor its file's (M12). */
    @Test
    fun aPlayWithoutTmdbIsSaidAsTheAppAloneNeverByItsReleaseOrFile() = runBlocking<Unit> {
        val film = plays.launch("copper-tide-bare")
        val target = UsenetUris.parse(film.uri)!!
        val key = WatchKey.release(target.nzbId, target.fileIndex).also { bareKey = it }
        val bare = film.copy(title = TITLE, subtitle = "1080p · WEB", watchKey = key, watch = WatchTarget(WatchKind.Release, TITLE))
        launch(bare).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            waitFor("the controller to see the play") { onMainThread { controller.isPlaying.takeIf { it } } }
            onMainThread {
                assertEquals(context.getString(R.string.app_name), controller.mediaMetadata.title?.toString())
                assertNull(controller.mediaMetadata.subtitle)
            }
            val platform = scenario.read { android.media.session.MediaController(context, it.session!!.platformToken) }
            waitFor("the platform session's metadata") { platform.metadata }
            val said = platformWords(platform)
            listOf("usenet://", PictureInPicturePlays.RELEASE_TITLE, target.name!!, TITLE).forEach { secret ->
                assertFalse("\"$secret\" said by the session", said.any { secret in it })
            }
            plays.finish(scenario)
        }
    }

    @Test
    fun aControllerPausesPlaysAndSeeksThroughTheControls() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-controller")).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            onMainThread { controller.pause() }
            waitFor("the player paused") { scenario.read { a -> (a.player?.playWhenReady == false).takeIf { it } } }
            onMainThread { controller.play() }
            waitFor("the player playing") { scenario.read { a -> a.player?.isPlaying?.takeIf { it } } }
            waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 3_000 } } }
            val seeks = seeksOf(scenario)
            val from = scenario.read { it.player!!.currentPosition }
            onMainThread { controller.seekForward() }
            waitFor("the seek") { seeks.get().takeIf { it > 0 } }
            // The burst's one seek, 10 s on.
            delay(1_000)
            assertEquals("seeks", 1, seeks.get())
            val at = scenario.read { it.player!!.currentPosition }
            assertTrue("at $at, from $from", at in from + 9_000..from + 13_000)
            onMainThread { controller.seekTo(5_000) }
            waitFor("the film at 0:05") { scenario.read { a -> a.player?.takeIf { it.currentPosition in 5_000L..8_000L } } }
            plays.finish(scenario)
        }
    }

    @Test
    fun nextFromOutsidePlaysTheNextEpisodeAsTheControlsDo() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        launch(plays.episodeLaunch("lowland-signal-next")).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            waitFor("Next, once the lookup knows the next episode") { onMainThread { controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT).takeIf { it } } }
            onMainThread { controller.seekToNext() }
            waitFor<Boolean>("the player to close for the next episode") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            withTimeout(30_000) { container.playback.state.first { "High Water" in it.toString() || "S01E02" in it.toString() } }
        }
    }

    /**
     * The platform session, which the picture-in-picture window, a Bluetooth headset and the Assistant read: what the play
     * does, and Next once the lookup knows the next episode, seconds into the play with nothing else changing. A Media3
     * controller hears of Next only as the session is told of it; the platform session is also brought up to date with the
     * position every 3 s.
     */
    @Test
    fun theSessionAndroidHandsKeysToOffersWhatThePlayDoesAndNextOnceKnown() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        val known = CompletableDeferred<Unit>()
        val next = PlayerActivity.nextEpisode
        PlayerActivity.nextEpisode = { c, e -> known.await(); next(c, e) }
        try {
            launch(plays.episodeLaunch("lowland-signal-actions")).use { scenario ->
                plays.playing(scenario)
                val controller = controllerFor(scenario)
                val platform = scenario.read { android.media.session.MediaController(context, it.session!!.platformToken) }
                assertEquals(context.packageName, platform.packageName)
                val playing = waitFor("the session playing") { platform.playbackState?.takeIf { it.state == PlaybackState.STATE_PLAYING } }
                ACTIONS.forEach { (action, name) -> assertTrue("$name offered", playing.actions and action != 0L) }
                NO_ACTIONS.forEach { (action, name) -> assertEquals("$name offered", 0L, playing.actions and action) }
                assertEquals("Next before the lookup knows it", 0L, playing.actions and PlaybackState.ACTION_SKIP_TO_NEXT)
                assertFalse("a controller's Next before the lookup knows it", onMainThread { controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) })
                // The play steady, nothing else to change.
                delay(2_000)
                known.complete(Unit)
                waitFor("a controller's Next once the lookup knows it", timeoutMs = 5_000) { onMainThread { controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT).takeIf { it } } }
                waitFor("Next once the lookup knows it", timeoutMs = 5_000) { platform.playbackState?.takeIf { it.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L } }
                plays.finish(scenario)
            }
        } finally {
            known.complete(Unit)
        }
    }

    /** A session's Stop (the Assistant's "stop", a headset's) leaves the player as the remote's Stop does. */
    @Test
    fun aSessionsStopLeavesThePlayer() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-stop-controller")).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            onMainThread { controller.stop() }
            waitFor<Boolean>("the player to close by a controller's Stop") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
        }
        launch(plays.launch("copper-tide-stop-key")).use { scenario ->
            plays.playing(scenario)
            waitFor("the app's session to take media keys") { mediaButtonSessionIsOurs().takeIf { it } }
            mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
            waitFor<Boolean>("the player to close by Android's Stop") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
        }
        assertEquals("sessions", 0, ourSessions())
    }

    /** A Bluetooth headset's play/pause comes through Android, a keyboard's or a remote's through the window: each once. */
    @Test
    fun aMediaKeyActsOnceThroughTheWindowAndThroughAndroid() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        launch(plays.episodeLaunch("lowland-signal-keys")).use { scenario ->
            plays.playing(scenario)
            // So that two quick presses could go to it, as Media3's own would.
            waitFor("Next known") { scenario.read { a -> a.session?.player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)?.takeIf { it } } }
            val flips = flipsOf(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            waitFor("paused by the window's key") { scenario.read { a -> (a.player?.playWhenReady == false).takeIf { it } } }
            delay(1_500)
            assertEquals("the window's key acted", 1, flips.get())
            waitFor("the app's session to take media keys") { mediaButtonSessionIsOurs().takeIf { it } }
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            waitFor("playing by Android's key") { scenario.read { a -> a.player?.playWhenReady?.takeIf { it } } }
            delay(1_500)
            assertEquals("Android's key acted once", 2, flips.get())
            // Two in quick succession: a pause and a play again, not Media3's double press for the next episode.
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            delay(100)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            delay(2_000)
            assertEquals("two presses acted", 4, flips.get())
            assertTrue("playing", scenario.read { it.player!!.playWhenReady })
            assertEquals("the player still open", Lifecycle.State.RESUMED, scenario.state)
            assertEquals(PlayState.Idle, container.playback.state.value)
            plays.finish(scenario)
        }
    }

    @Test
    fun oneSessionForThePlayerMadeAgainOrGivenAnotherPlayNoneOnceItCloses() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-one-session")).use { scenario ->
            plays.playing(scenario)
            waitFor("the session listed") { ourSessions().takeIf { it > 0 } }
            assertEquals("sessions", 1, ourSessions())
            val before = scenario.read { it }
            scenario.recreate()
            waitFor("the player made again, playing", timeoutMs = 30_000) { scenario.read { a -> a.takeIf { it !== before && it.player?.isPlaying == true } } }
            assertEquals("sessions after the player was made again", 1, ourSessions())
            val second = plays.launch("copper-tide-one-session-2")
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the second play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri && it.isPlaying } }
            }
            assertEquals("sessions after another play", 1, ourSessions())
            val controller = controllerFor(scenario)
            plays.finish(scenario)
            waitFor("no session of the app's") { ourSessions().takeIf { it == 0 } }
            waitFor<Boolean>("the controller let go") { onMainThread { (!controller.isConnected).takeIf { it } } }
        }
    }

    /** The sheet takes the keys but play and pause (TV-0): ⏩ and Next, which it leaves, go no further, to the app's own session. */
    @Test
    fun withTheSheetOpenFastForwardAndNextDoNothing() = runBlocking<Unit> {
        plays.offerTheNextEpisode()
        launch(plays.episodeLaunch("lowland-signal-sheet")).use { scenario ->
            plays.playing(scenario)
            waitFor("Next known") { scenario.read { a -> a.session?.player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)?.takeIf { it } } }
            waitFor("the film's tracks") { scenario.read { a -> a.player?.currentTracks?.takeUnless { it.isEmpty } } }
            scenario.onActivity { a -> views(a.window.decorView).single { it.contentDescription == "Audio and subtitles" && it.isClickable }.performClick() }
            waitFor("the sheet") { scenario.read { a -> overlayWords(a).takeIf { "Audio and subtitles" in it } } }
            val seeks = seeksOf(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_NEXT)
            delay(2_000)
            assertEquals("seeks under the sheet", 0, seeks.get())
            assertEquals("the player still open", Lifecycle.State.RESUMED, scenario.state)
            assertEquals(PlayState.Idle, container.playback.state.value)
            assertTrue("the sheet", "Audio and subtitles" in scenario.read(::overlayWords))
            plays.finish(scenario)
        }
    }

    /**
     * A wired headset's button comes to the player's window as HEADSETHOOK: under the sheet, which takes the keys but play
     * and pause, it pauses and plays as the remote's play/pause does there, and as it does full screen.
     */
    @Test
    fun withTheSheetOpenAHeadsetsButtonPausesAndPlays() = runBlocking<Unit> {
        launch(plays.launch("copper-tide-sheet-headset")).use { scenario ->
            plays.playing(scenario)
            waitFor("the film's tracks") { scenario.read { a -> a.player?.currentTracks?.takeUnless { it.isEmpty } } }
            scenario.onActivity { a -> views(a.window.decorView).single { it.contentDescription == "Audio and subtitles" && it.isClickable }.performClick() }
            waitFor("the sheet") { scenario.read { a -> overlayWords(a).takeIf { "Audio and subtitles" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HEADSETHOOK)
            waitFor("paused by the headset's button under the sheet", timeoutMs = 5_000) { scenario.read { a -> (a.player?.playWhenReady == false).takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HEADSETHOOK)
            waitFor("playing again by it", timeoutMs = 5_000) { scenario.read { a -> a.player?.playWhenReady?.takeIf { it } } }
            assertTrue("the sheet", "Audio and subtitles" in scenario.read(::overlayWords))
            plays.finish(scenario)
        }
    }

    /**
     * Media3's Play prepares an idle player: under "Playback stopped" it would start the failed stream again behind the
     * panel. What the session says of the error is that playback stopped, no more: the player's error names the provider's
     * refusal, and could name a release's volume or the provider's host.
     */
    @Test
    fun underPlaybackStoppedPlayDoesNotStartTheStreamAgain() = runBlocking<Unit> {
        val refuse = AtomicBoolean(false)
        plays.refusing(refuse)
        launch(plays.launch("copper-tide-session-failed")).use { scenario ->
            plays.playing(scenario)
            val controller = controllerFor(scenario)
            val platform = scenario.read { android.media.session.MediaController(context, it.session!!.platformToken) }
            plays.failByASeek(scenario, refuse)
            waitFor("the panel", timeoutMs = 20_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            val error = waitFor("a controller to see the error") { onMainThread { controller.playerError } }
            assertEquals("Playback stopped", error.message)
            assertNull("the error's cause, as a controller sees it", error.cause)
            val stopped = waitFor("the platform session's error") { platform.playbackState?.takeIf { it.state == PlaybackState.STATE_ERROR } }
            assertEquals("Playback stopped", stopped.errorMessage?.toString())
            waitFor("the app's session to take media keys") { mediaButtonSessionIsOurs().takeIf { it } }
            val states = statesOf(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            delay(2_000)
            assertEquals("the player's states since", emptyList<Int>(), states.toList())
            assertEquals(Player.STATE_IDLE, scenario.read { it.player!!.playbackState })
            assertTrue("the panel", "Playback stopped" in scenario.read(::overlayWords))
            plays.finish(scenario)
        }
    }

    /** In the window the player's own window has no focus: a headset's keys reach the play through the session alone. */
    @Test
    fun inTheWindowAndroidsPauseAndPlayReachThePlay() = runBlocking<Unit> {
        plays.assumeWindow()
        launch(plays.launch("copper-tide-window-keys")).use { scenario ->
            plays.playing(scenario)
            pressHome()
            awaitPinned(scenario.read { it })
            assertTrue("the app's session takes media keys", mediaButtonSessionIsOurs())
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            waitFor("paused in the window") { scenario.read { a -> (a.player?.playWhenReady == false).takeIf { it } } }
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            waitFor("playing in the window") { scenario.read { a -> a.player?.isPlaying?.takeIf { it } } }
            assertTrue("in the window", scenario.read { it.isInPictureInPictureMode })
            plays.finish(scenario)
        }
    }

    /**
     * Next and Previous hand over to another play, which can't start from the window (L13): a headset's do nothing there,
     * where Next would close the window and Previous start the film again from 0:00 (M14). Expanded, Next is back.
     */
    @Test
    fun inTheWindowNextAndPreviousDoNothing() = runBlocking<Unit> {
        plays.assumeWindow()
        plays.offerTheNextEpisode()
        launch(plays.episodeLaunch("lowland-signal-window-next")).use { scenario ->
            val player = plays.playing(scenario)
            waitFor("Next known") { scenario.read { a -> a.session?.player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)?.takeIf { it } } }
            waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 4_000 } } }
            pressHome()
            val activity = scenario.read { it }
            awaitPinned(activity)
            val platform = scenario.read { android.media.session.MediaController(context, it.session!!.platformToken) }
            waitFor("no Next nor Previous in the window") {
                platform.playbackState?.takeIf { it.actions and (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0L }
            }
            val seeks = seeksOf(scenario)
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            delay(2_000)
            assertTrue("in the window", scenario.read { it.isInPictureInPictureMode })
            assertTrue("the same player, playing", scenario.read { it.player === player && player.isPlaying })
            assertEquals("seeks", 0, seeks.get())
            assertEquals(PlayState.Idle, container.playback.state.value)
            expandFromMenu(activity)
            waitFor("the player full screen") { true.takeIf { scenario.state == Lifecycle.State.RESUMED } }
            waitFor("Next back") { platform.playbackState?.takeIf { it.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L } }
            plays.finish(scenario)
        }
    }

    /** The player's seeks from now on: one for each burst of rewind and fast-forward taps. */
    private fun seeksOf(scenario: ActivityScenario<PlayerActivity>): AtomicInteger {
        val seeks = AtomicInteger()
        scenario.onActivity { a ->
            a.player!!.addListener(
                object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                },
            )
        }
        return seeks
    }

    /** How often the play has been played or paused from now on. */
    private fun flipsOf(scenario: ActivityScenario<PlayerActivity>): AtomicInteger {
        val flips = AtomicInteger()
        scenario.onActivity { a ->
            a.player!!.addListener(
                object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        flips.incrementAndGet()
                    }
                },
            )
        }
        return flips
    }

    /** The player's states from now on. */
    private fun statesOf(scenario: ActivityScenario<PlayerActivity>): MutableList<Int> {
        val states = java.util.Collections.synchronizedList(mutableListOf<Int>())
        scenario.onActivity { a ->
            a.player!!.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        states += playbackState
                    }
                },
            )
        }
        return states
    }

    /** Every word the platform session says: its metadata's, its own extras' and its playback state's. */
    private fun platformWords(platform: android.media.session.MediaController): List<String> {
        val metadata = platform.metadata?.let { m -> m.keySet().mapNotNull { m.getText(it)?.toString() } }.orEmpty()
        return metadata + words(platform.extras) + words(platform.playbackState?.extras) + listOfNotNull(platform.queueTitle?.toString())
    }

    @Suppress("DEPRECATION")
    private fun words(bundle: android.os.Bundle?): List<String> = bundle?.let { b -> b.keySet().mapNotNull { b.get(it)?.toString() } }.orEmpty()

    private companion object {
        const val INDEXER = "media-session-device-test"

        /** The play as a release's parsed name gives it, without TMDB. */
        const val TITLE = "Copper Tide"

        val OFFERED = listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        )

        val WITHHELD = listOf(
            Player.COMMAND_PREPARE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_PLAYLIST_METADATA, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        )

        val ACTIONS = listOf(
            PlaybackState.ACTION_PAUSE to "Pause", PlaybackState.ACTION_PLAY_PAUSE to "Play/pause", PlaybackState.ACTION_REWIND to "Rewind",
            PlaybackState.ACTION_FAST_FORWARD to "Fast-forward", PlaybackState.ACTION_SEEK_TO to "Seek", PlaybackState.ACTION_STOP to "Stop",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "Previous",
        )

        val NO_ACTIONS = listOf(
            PlaybackState.ACTION_PREPARE to "Prepare", PlaybackState.ACTION_PLAY_FROM_URI to "Play from a URI", PlaybackState.ACTION_PLAY_FROM_SEARCH to "Play from a search",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "Play from an id", PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "Queue item",
        )
    }
}

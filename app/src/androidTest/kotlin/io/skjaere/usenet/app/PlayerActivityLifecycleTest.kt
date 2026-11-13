package io.skjaere.usenet.app

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.SourceNames
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.PlaybackPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Player setup suspends on settings and Room reads before it makes the player. Leaving the
 * screen in that window must not let it make one later: onStop has already run, so nothing
 * would release it and it would play in the background. Started again, the player it makes
 * then plays as the first would: none of the play was made before the stop (W106). And the
 * player it makes is the device's: a TV's asks Media3 to follow the audio route.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivityLifecycleTest {
    /** The app let into picture-in-picture: a stop during setup, with no picture yet, must not put the player in the window. */
    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val realRead = PlayerActivity.readPlayback
    private val realIndexers = PlayerActivity.readIndexers
    /** One gate per setup, in start order: setup waits on it in place of the settings read. */
    private val setups = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    @After
    fun restoreRead() {
        PlayerActivity.readPlayback = realRead
        PlayerActivity.readIndexers = realIndexers
    }

    private fun holdSetups(ignoreCancellation: Boolean = false) {
        PlayerActivity.readPlayback = {
            val gate = CompletableDeferred<Unit>().also { setups.trySend(it) }
            if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            PlaybackPrefs()
        }
    }

    private fun nextSetup() = runBlocking { withTimeout(10_000) { setups.receive() } }

    private fun intent() = Intent(instrumentation.targetContext, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build("0", 0))

    @Test
    fun noPlayerIsMadeWhenStoppedDuringSetup() {
        holdSetups()
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            val setup = nextSetup()
            scenario.moveToState(Lifecycle.State.CREATED)
            setup.complete(Unit)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNull("a player was made after onStop", it.player) }
            // Closing a stopped activity waits out ActivityScenario's 45 s timeout.
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun aStoppedSetupThatIgnoresCancellationDoesNotReplaceThePlayer() {
        holdSetups(ignoreCancellation = true)
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            val stopped = nextSetup()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            nextSetup().complete(Unit)
            instrumentation.waitForIdleSync()
            var made: ExoPlayer? = null
            scenario.onActivity { made = it.player }
            assertNotNull(made)
            stopped.complete(Unit)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertSame("a stopped setup replaced the player", made, it.player) }
        }
    }

    @Test
    fun aPlayerIsMadeWhenReturningAfterAStopDuringSetup() {
        holdSetups()
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            nextSetup()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            nextSetup().complete(Unit)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertNotNull(it.player)
                assertTrue("asked to play, as the play's first player", it.player!!.playWhenReady)
            }
        }
    }

    @Test
    fun aPlayerIsMadeWhenSetupFinishesWhileStarted() {
        holdSetups()
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            nextSetup().complete(Unit)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNotNull(it.player) }
        }
    }

    /** The indexers are for File details' Indexer row, which can fill in later: the player doesn't wait for them. */
    @Test
    fun aPlayerIsMadeWhileTheIndexersAreStillRead() {
        PlayerActivity.readIndexers = { CompletableDeferred<SourceNames>().await() }
        holdSetups()
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            nextSetup().complete(Unit)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNotNull("no player while the indexers were still being read", it.player) }
        }
    }

    /** Setup runs inside onStart until it first suspends, while the lifecycle still says CREATED. */
    @Test
    fun aPlayerIsMadeWhenSetupFinishesInsideOnStart() {
        PlayerActivity.readPlayback = { PlaybackPrefs() }
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            scenario.onActivity { assertNotNull("no player after a setup that never suspended", it.player) }
        }
    }

    /**
     * By the device's kind, not its layouts: a TV's player asks Media3 to select its tracks again when the audio route
     * changes (what Media3 then plays is checked on the Shield); a phone's selects as it always has.
     */
    @Test
    fun aTvsPlayerAsksMedia3ToFollowTheAudioRouteAndAPhonesDoesNot() {
        PlayerActivity.readPlayback = { PlaybackPrefs() }
        ActivityScenario.launch<PlayerActivity>(intent()).use { scenario ->
            var follows: Boolean? = null
            scenario.onActivity { a -> follows = (a.player?.trackSelector as? DefaultTrackSelector)?.parameters?.allowInvalidateSelectionsOnRendererCapabilitiesChange }
            assertEquals(instrumentation.targetContext.container.television, follows)
        }
    }
}

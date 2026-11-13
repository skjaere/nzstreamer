package io.skjaere.usenet.app.tv

import android.media.AudioDeviceInfo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.NoisyPlays
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.read
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's "becoming noisy" on a real TV (tv36): it comes as a box's HDMI re-syncs, as it does with every switch of
 * the TV's refresh rate, and there the play goes on. It pauses only for headphones: a Bluetooth output listed as it
 * arrives, as on a TV set whose headphones are its only ones of Android's "noisy" kinds (from a box with HDMI connected
 * Android sends nothing as they go), or gone just before, as when a box's HDMI re-syncs in the 2 s after them. The
 * outputs listed are the test's ([PlayerActivity.audioOutputs]), the broadcast and the removals called on the player's
 * watcher as Android would send them ([NoisyPlays]). As the player closes, or another play starts in it, its watcher
 * goes.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvBecomingNoisyTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plays = NoisyPlays(FILM, INDEXER)

    @Before
    fun setUp() {
        assumeTv()
        runBlocking { plays.setUp() }
    }

    @After
    fun tearDown() {
        plays.close()
        runBlocking { plays.forget() }
    }

    /** The film playing, with [listed] the outputs a "becoming noisy" finds; [check] then. */
    private fun play(listed: List<Int>, check: suspend (ActivityScenario<PlayerActivity>) -> Unit) = runBlocking<Unit> {
        PlayerActivity.audioOutputs = { listed }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, plays.launch("saltmarsh-evening-tv"))).use { scenario ->
            plays.playing(scenario)
            check(scenario)
        }
    }

    /** As the TV re-syncs: HDMI goes away, Android says the output is going away, and only HDMI is listed. */
    @Test
    fun aNoisyWithOnlyHdmiListedLeavesItPlaying() = play(listOf(AudioDeviceInfo.TYPE_HDMI)) { scenario ->
        scenario.onActivity { NoisyPlays.watcherOf(it).removed(listOf(AudioDeviceInfo.TYPE_HDMI)) }
        val before = scenario.read { it.player!!.currentPosition }
        val mark = plays.logMark()
        plays.noisy(scenario)
        delay(2_000)
        scenario.read { a ->
            val p = a.player!!
            assertTrue("paused by an HDMI re-sync", p.playWhenReady)
            assertTrue("the position didn't move: ${p.currentPosition}, was $before", p.currentPosition >= before + 1_000)
        }
        assertEquals(
            listOf(
                "DEBUG BecomingNoisy: Audio output going away; outputs listed: HDMI; gone in the last 2 s: HDMI",
                "INFO PlayerActivity: Audio output going away (HDMI re-sync, no headphones): playing on",
            ),
            plays.audioLines(mark),
        )
    }

    /**
     * A TV set's Bluetooth headphones going: Android says so before it moves the sound to the speaker, so they are
     * still listed. And once paused, another changes nothing: the play would not play.
     */
    @Test
    fun aNoisyWithABluetoothOutputListedPausesIt() = play(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) { scenario ->
        val mark = plays.logMark()
        plays.noisy(scenario)
        assertFalse("played on with Bluetooth headphones listed", scenario.read { it.player!!.playWhenReady })
        delay(500)
        assertFalse(scenario.read { it.player!!.isPlaying })
        plays.noisy(scenario)
        assertFalse(scenario.read { it.player!!.playWhenReady })
        val heard = "DEBUG BecomingNoisy: Audio output going away; outputs listed: speaker, Bluetooth; gone in the last 2 s: none"
        assertEquals(
            listOf(heard, "INFO PlayerActivity: Audio output going away (Bluetooth): paused", heard, "DEBUG PlayerActivity: Audio output going away: not playing"),
            plays.audioLines(mark),
        )
    }

    /**
     * The headphones' removal heard before the broadcast: they are no longer listed as it arrives, as when a box's HDMI
     * re-syncs a second after they went.
     */
    @Test
    fun aNoisyAfterABluetoothOutputWentASecondEarlierPausesIt() = play(listOf(AudioDeviceInfo.TYPE_HDMI)) { scenario ->
        scenario.onActivity { NoisyPlays.watcherOf(it).removed(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) }
        delay(1_000)
        val mark = plays.logMark()
        plays.noisy(scenario)
        assertFalse("played on with Bluetooth headphones gone a second before", scenario.read { it.player!!.playWhenReady })
        assertEquals(
            listOf(
                "DEBUG BecomingNoisy: Audio output going away; outputs listed: HDMI; gone in the last 2 s: Bluetooth",
                "INFO PlayerActivity: Audio output going away (Bluetooth): paused",
            ),
            plays.audioLines(mark),
        )
    }

    /** Media3's own handling stays off (PlayerFactory): Android holds the watcher's receiver alone. */
    @Test
    fun theWatcherIsTheOnlyReceiverOfTheBroadcast() = runBlocking<Unit> {
        plays.onlyTheWatcherHearsTheBroadcast()
    }

    @Test
    fun afterThePlayerClosesItsWatcherIsGone() = runBlocking<Unit> {
        plays.closingLeavesNoWatcher(personal = PERSONAL)
    }

    @Test
    fun aPlayStartedInThePlayerLeavesOnlyItsWatcher() = runBlocking<Unit> {
        plays.aPlayStartedHereLeavesOnlyItsWatcher(personal = PERSONAL)
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900029
        const val INDEXER = "tv-becoming-noisy-test"
        /** A TV set's outputs with Bluetooth headphones connected, with which its "becoming noisy" pauses. */
        val PERSONAL = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    }
}

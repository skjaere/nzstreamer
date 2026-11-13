package io.skjaere.usenet.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.BecomingNoisy
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue

/**
 * The "becoming noisy" tests' plays (TvBecomingNoisyTest, BecomingNoisyDeviceTest): gaps-faststart.mp4, a minute long,
 * posted whole on a server inside the test's process ([LocalNntp]) and launched straight into the player, as [GapFixtures]
 * launch theirs. Android's broadcast is the system's alone, so a test calls the player's watcher as its receiver would
 * ([BecomingNoisy.noisy]), and tells it of an output going away as its device callback would ([BecomingNoisy.removed]).
 * Detailed logging is on meanwhile, for the outputs' kinds the watcher logs at DEBUG. What Android itself holds for the
 * broadcast is read from its listing of receivers ([noisyReceivers]). The film is invented ([film], "Saltmarsh Evening");
 * its progress is put back by [forget].
 */
class NoisyPlays(private val film: Int, indexer: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realOutputs = PlayerActivity.audioOutputs
    private val detailed = AndroidLogConfig.detailed
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, indexer, WatchTarget(WatchKind.Movie, FILM_TITLE, movieTmdbId = film))
    private val mp4 by lazy { fixtures.media("gaps-faststart.mp4") }

    /** The engine on the test's own server, and no TheIntroDB lookups. */
    suspend fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        AndroidLogConfig.detailed = true
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
    }

    override fun close() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.audioOutputs = realOutputs
        AndroidLogConfig.detailed = detailed
        local.close()
    }

    /** Out of Up next. */
    suspend fun forget() = container.library.recordProgress(WatchState(WatchKey.movie(film), WatchKind.Movie, movieTmdbId = film, title = FILM_TITLE, updatedAt = 0), 0, 60_000)

    /** The film as the release [guid], posted under its own message ids, from its start. */
    suspend fun launch(guid: String): PlayerLaunch {
        val release = fixtures.release("gaps-faststart.mp4", mp4, PART, guid, RELEASE_TITLE) { emptySet() }
        return fixtures.launch(release.prepared, guid, 0)
    }

    /** Waits for the player to play; it, then. */
    suspend fun playing(scenario: ActivityScenario<PlayerActivity>): ExoPlayer =
        waitFor("the film to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }

    /** The log's last line now, for [audioLines] to read what follows it. */
    fun logMark(): Long = LogBuffer.snapshot().lastOrNull()?.seq ?: 0

    /** The lines about the audio output going away since [mark], with their levels and tags: "INFO PlayerActivity: Audio output going away (Bluetooth): paused". */
    fun audioLines(mark: Long): List<String> =
        LogBuffer.snapshot().filter { it.seq > mark && it.message.startsWith("Audio output going away") }.map { "${it.level} ${it.tag}: ${it.message}" }

    /** The player's watcher, called as its receiver is as Android says the audio output is going away. */
    fun noisy(scenario: ActivityScenario<PlayerActivity>) = scenario.onActivity { watcherOf(it).noisy() }

    /**
     * Android holds one receiver of this process's for "becoming noisy" while the film plays, the watcher's, and none once
     * the player closes. Media3's own handling, which paused the next episode on a TV, would add a second: it is off
     * (PlayerFactory), and the test calling the watcher never sees Media3's. None either while the play is stopped
     * (Home: onStop), with the activity still there, so Android's own clean-up of a destroyed activity's receivers can't
     * stand in for the watcher's; the new player's alone, as it waits paused where the film was (W106) and as it plays again.
     */
    suspend fun onlyTheWatcherHearsTheBroadcast() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch("saltmarsh-evening-receivers"))).use { scenario ->
            val player = playing(scenario)
            assertEquals("receivers of the broadcast while it plays", 1, noisyReceivers())

            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("receivers of the broadcast while it is stopped", 0, noisyReceivers())
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the player made again", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it !== player && it.playbackState == Player.STATE_READY } } }
            assertEquals("receivers of the broadcast as it waits paused", 1, noisyReceivers())
            scenario.onActivity { it.player!!.play() }
            waitFor("the film to play again", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it !== player && it.isPlaying } } }
            assertEquals("receivers of the broadcast as it plays again", 1, noisyReceivers())

            scenario.onActivity { it.finish() }
            waitFor("the player to close") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            assertEquals("receivers of the broadcast after it closed", 0, noisyReceivers())
        }
    }

    /**
     * The player closes (finish): its watcher's receiver and callback are gone, none other is left registered, Android
     * holds no receiver of the broadcast for it, and a "becoming noisy" it is called with then changes nothing, with
     * [personal] outputs listed that would pause.
     */
    suspend fun closingLeavesNoWatcher(personal: List<Int>?) {
        val before = BecomingNoisy.registered
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch("saltmarsh-evening-closed"))).use { scenario ->
            val player = playing(scenario)
            val watcher = scenario.read(::watcherOf)
            assertTrue(scenario.read { watcher.started })
            assertEquals("watchers registered while it plays", before + 1, BecomingNoisy.registered)
            assertEquals("receivers of the broadcast while it plays", 1, noisyReceivers())

            scenario.onActivity { it.finish() }
            waitFor("the player to close") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
            assertEquals("receivers of the broadcast after it closed", 0, noisyReceivers())
            personal?.let { listed -> PlayerActivity.audioOutputs = { listed } }
            var started = true
            var playWhenReady = false
            // On the main thread, as the player and the watcher want, with the activity gone.
            instrumentation.runOnMainSync {
                started = watcher.started
                watcher.noisy()
                playWhenReady = player.playWhenReady
            }
            assertFalse("the closed player's watcher is still registered", started)
            assertEquals("watchers registered after it closed", before, BecomingNoisy.registered)
            assertTrue("a noisy after it closed paused the player it had", playWhenReady)
        }
    }

    /**
     * Another play started while one plays (onNewIntent): the first player's watcher is gone, the second's is the only
     * one registered, and the only receiver of the broadcast Android holds, and a "becoming noisy" the first's is called
     * with then leaves the second playing, with [personal] outputs listed that would pause; the second's pauses it.
     * Android holds none once the player closes.
     */
    suspend fun aPlayStartedHereLeavesOnlyItsWatcher(personal: List<Int>?) {
        val before = BecomingNoisy.registered
        val first = launch("saltmarsh-evening-first")
        val second = launch("saltmarsh-evening-second")
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
            val firstPlayer = playing(scenario)
            val firstWatcher = scenario.read(::watcherOf)
            assertEquals("receivers of the broadcast with the first playing", 1, noisyReceivers())

            // As the coordinator's launches start it, from outside the open player.
            context.startActivity(PlayerActivity.intent(context, second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the second play to play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it !== firstPlayer && it.currentMediaItem?.localConfiguration?.uri?.toString() == second.uri && it.isPlaying } }
            }
            val secondWatcher = scenario.read(::watcherOf)
            assertNotSame("the second player has the first's watcher", firstWatcher, secondWatcher)
            assertEquals(listOf(false, true), scenario.read { listOf(firstWatcher.started, secondWatcher.started) })
            assertEquals("watchers registered with the second playing", before + 1, BecomingNoisy.registered)
            assertEquals("receivers of the broadcast with the second playing", 1, noisyReceivers())

            personal?.let { listed -> PlayerActivity.audioOutputs = { listed } }
            scenario.onActivity { firstWatcher.noisy() }
            delay(500)
            assertTrue("the first player's watcher paused the second play", scenario.read { it.player!!.playWhenReady })
            noisy(scenario)
            assertFalse("the second player's watcher didn't pause it", scenario.read { it.player!!.playWhenReady })
        }
        waitFor("the player's watcher to go as it closes") { true.takeIf { BecomingNoisy.registered == before } }
        assertEquals("receivers of the broadcast after it closed", 0, noisyReceivers())
    }

    companion object {
        /**
         * How many receivers this process has registered with Android for its "becoming noisy" broadcast, as Android lists
         * them (`dumpsys activity broadcasts`, which the shell may run). The listing is read here, never printed: it names
         * other apps' receivers too.
         */
        fun noisyReceivers(): Int {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val listing = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys activity broadcasts ${instrumentation.targetContext.packageName}"))
                .use { it.readBytes().decodeToString() }
            return receiversIn(listing, Process.myPid(), "android.media.AUDIO_BECOMING_NOISY")
        }

        /** The player's watcher, which it makes as it makes a player. */
        fun watcherOf(a: PlayerActivity): BecomingNoisy = a.noisy ?: throw AssertionError("the player has no \"becoming noisy\" watcher")

        /**
         * The receivers of process [pid] that listen for [action] in Android's [listing]: each is a "* ReceiverList{hash
         * pid ...}" line with its filters indented under it, an action as `Action: "..."`.
         */
        private fun receiversIn(listing: String, pid: Int, action: String): Int {
            var count = 0
            // The indent of the receiver being read, -1 between them; whether it is the process's, and hears the action.
            var indent = -1
            var ours = false
            var hears = false
            for (line in listing.lineSequence().filter { it.isNotBlank() }) {
                val depth = line.length - line.trimStart().length
                if (indent >= 0 && depth <= indent) {
                    if (ours && hears) count++
                    indent = -1
                }
                val receiver = RECEIVER.find(line)
                if (receiver != null) {
                    indent = depth
                    ours = receiver.groupValues[1].toInt() == pid
                    hears = false
                } else if (indent >= 0 && line.trim() == "Action: \"$action\"") {
                    hears = true
                }
            }
            if (indent >= 0 && ours && hears) count++
            return count
        }

        private val RECEIVER = Regex("""^\s*\* ReceiverList\{[0-9a-f]+ (\d+) """)

        private const val FILM_TITLE = "Saltmarsh Evening"
        private const val RELEASE_TITLE = "Saltmarsh.Evening.2026.1080p.WEB.h264-NOISE"
        /** gaps-faststart.mp4 (136 KB) in five parts. */
        private const val PART = 32_000
    }
}

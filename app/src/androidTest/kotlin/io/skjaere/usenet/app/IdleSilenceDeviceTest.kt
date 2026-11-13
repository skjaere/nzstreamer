package io.skjaere.usenet.app

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.UsenetEngine
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider, a NAT or a VPN that drops connections nobody used for a while without a word, the costliest of the ways
 * idle connections die: a play reads on through it, never waiting for bytes, never showing the slow-stream banner and
 * never stopping, while the pool checks its idle connections before it lends them and opens others for the dead ones.
 *
 * The release is dip.mkv (20 s at about 840 kbps) posted on a server inside the test's process ([LocalNntp]) whose
 * connections go silent after 11 s idle, launched straight into the player and played over and over. The player loads
 * 10 s ahead and again below 5 s, a play's rests in small. A stream holds 1 MiB its reader hasn't taken and the player
 * reads 1 MiB at a time, so each pass's stream has the whole file within its first second, and its connections then
 * sit idle until the next pass's stream opens, as the player loads the last of this one with about 5 s left to play,
 * some 15-20 s later: past the silence. The player reads through an engine of the test's own, built as the app builds
 * its own, with the app's timings, but without the video cache, which would give every pass after the first from the
 * device. The app checks a connection idle 10 s or more before use, so the silence is just past that, where a NAT's or
 * a VPN's comes after tens of seconds; the check's 2 s and a connect fit in the 5 s. Without the check and the retry,
 * each fetch on a dead connection waited out its 5 s, and the stream failed and opened again onto the next dead one.
 * The film is invented ("Quietline Harbour", TMDB id 900037); its progress is put back after the test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
open class IdleSilenceDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realDataSources = PlayerActivity.dataSources
    private val realLoadControl = PlayerActivity.loadControlFor
    private val local = LocalNntp(idleAfterMs = SILENCE_MS)
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Quietline Harbour", movieTmdbId = FILM))
    private val clip by lazy { fixtures.media("dip.mkv") }
    /** The play's own engine: the app's streamer and timings, with no video cache. */
    private val engine = UsenetEngine()

    /** The device the test is for: any, here; a TV, in tv's TvIdleSilenceTest. */
    protected open fun assumeDevice() {}

    @Before
    fun setUp() {
        assumeDevice()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.dataSources = dataSourcesThrough(NzbStreamOpener(engine))
        PlayerActivity.loadControlFor = { DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 10_000, 500, 500).build() }
        runBlocking {
            // The app's engine prepares the release; the play streams it through the test's.
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
            // A provider as the app sets one up, with its eight connections.
            engine.configure(EngineSettings(listOf(local.provider.copy(maxConnections = 8))))
        }
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.loadControlFor = realLoadControl
        container.playback.cancel()
        engine.close()
        local.close()
        runBlocking {
            container.library.recordProgress(template(), 0, 20_000)
        }
    }

    private fun template() = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Quietline Harbour", updatedAt = 0)

    /**
     * A minute of play over and over: no wait for bytes once it has played, no banner, no "Playback stopped". Its
     * connections went silent while idle, and others were opened for them.
     */
    @Test
    fun aPlayWhoseIdleConnectionsGoSilentPlaysOnWithoutWaitingForBytes() = runBlocking<Unit> {
        val release = fixtures.release("dip.mkv", clip, PART, "idle-silence", TITLE) { emptySet() }
        val rebuffers = AtomicInteger()
        val passes = AtomicInteger(1)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, fixtures.launch(release.prepared, GUID, 0))).use { scenario ->
            val player = waitFor("the player", 30_000) { scenario.read { it.player } }
            scenario.onActivity {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.addListener(object : Player.Listener {
                    var ready = player.playbackState == Player.STATE_READY

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready = true
                        if (state == Player.STATE_BUFFERING && ready) rebuffers.incrementAndGet()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) passes.incrementAndGet()
                    }
                })
            }
            waitFor("playback", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            val started = SystemClock.elapsedRealtime()
            fun at() = "${(SystemClock.elapsedRealtime() - started) / 1_000} s into the play, pass ${passes.get()}"
            // The connections open when the first dead one was found: the ones opened after it took the dead ones' place.
            var openedAtFirstSilence: Int? = null
            while (SystemClock.elapsedRealtime() - started < PLAY_MS) {
                scenario.read { a ->
                    val words = overlayWords(a)
                    if ("Playback stopped" in words) throw AssertionError("the play stopped ${at()}: $words")
                    words.firstOrNull { it.startsWith(BANNER) }?.let { throw AssertionError("the slow-stream banner ${at()}: $it") }
                }
                if (rebuffers.get() > 0) throw AssertionError("the player waited for bytes ${at()}")
                if (openedAtFirstSilence == null && local.silenced.get() > 0) openedAtFirstSilence = local.connections.get()
                delay(250)
            }
            assertTrue("still playing", scenario.read { a -> a.player?.isPlaying == true })
            assertTrue("played ${passes.get()} passes", passes.get() >= 3)
            val opened = openedAtFirstSilence ?: throw AssertionError("no connection went silent: ${local.connections.get()} opened")
            assertTrue("${local.silenced.get()} went silent, none opened after the first (${local.connections.get()} opened)", local.connections.get() > opened)
        }
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900037
        const val INDEXER = "idle-silence-device-test"
        const val GUID = "quietline-harbour-web"
        const val TITLE = "Quietline.Harbour.2026.1080p.WEB.h264-IDLE"
        const val BANNER = "Streaming is slower than this video needs"
        /** About a third of a second of dip.mkv. */
        const val PART = 32_000
        /** Just past the 10 s idle after which the app checks a connection before use. */
        const val SILENCE_MS = 11_000L
        const val PLAY_MS = 60_000L
    }
}

package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.memoryFile
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.tv.ScreenModes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Match the TV to the video's frame rate" on a real TV's window and display (tv36), the setting on: its display lists
 * one 60 Hz mode, so a 23.976 film asks for nothing, logs why, and plays at once, as before the setting.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FrameRateTvSmokeTest {
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realRead = PlayerActivity.readPlayback
    private val realDataSources = PlayerActivity.dataSources
    private val realLookUp = PlayerActivity.lookUpSegments
    private val file = instrumentation.context.assets.open("rate-23976.mkv").use { it.readBytes() }

    @Before
    fun setUp() {
        assumeTv()
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = true) }
        PlayerActivity.dataSources = { memoryFile(file) }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.readPlayback = realRead
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.lookUpSegments = realLookUp
        runBlocking {
            Thread.sleep(500)
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 1_000)
            container.library.markMovies(listOf(MovieMark(FILM, TITLE)), watched = false)
        }
    }

    @Test
    fun aFilmOnTheTvsOneModeAsksForNothingAndPlaysAtOnce() {
        val modes = ScreenModes.read(context)
        Log.i(TAG, "modes: ${modes.modes}, active ${modes.active}")
        assertEquals("tv36 lists one mode", 1, modes.modes.size)
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        val name = "$RELEASE.mkv"
        val film = WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM)
        val launch = PlayerLaunch(
            uri = UsenetUris.build(NZB, 0, name, file.size.toLong()), title = TITLE, subtitle = "2026", watchKey = film.key(NZB, 0), watch = film,
            release = SavedRelease("frame-rate-tv", RELEASE.lowercase(), RELEASE, null, 0, NZB, name, file.size.toLong()),
            video = VideoTiming(24_000.0 / 1001, 2 * 60 * 60_000L),
        )
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val started = SystemClock.elapsedRealtime()
            var made = false
            while (!made && SystemClock.elapsedRealtime() - started < 10_000) {
                scenario.onActivity { a ->
                    made = a.player != null
                    assertEquals("the window asked for a mode", 0, a.window.attributes.preferredDisplayModeId)
                }
                if (!made) Thread.sleep(50)
            }
            assertTrue("no player", made)
            scenario.onActivity { a -> assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, a.player!!.videoChangeFrameRateStrategy) }
        }
        val lines = LogBuffer.snapshot().filter { it.seq > since && it.tag == "PlayerActivity" && it.message.startsWith("Frame rate") }.map { it.message }
        Log.i(TAG, "logged: $lines")
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("Frame rate: 23.976 fps; display ") && ": left: " in lines.single())
    }

    private companion object {
        const val TAG = "FrameRateTvSmoke"
        const val FILM = 900732
        const val TITLE = "Low Water Mark"
        const val RELEASE = "Low.Water.Mark.2026.1080p.WEB-DL.H.264-NZST"
        const val NZB = "frame-rate-tv-23976"
    }
}

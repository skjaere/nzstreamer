package io.skjaere.usenet.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Matroska's track flags as a play's player and the track probe read them, from `flags.mkv` in
 * this test's assets (see the README there): an English commentary flagged default and
 * commentary comes first, then English audio with no flags, then tracks flagged visual impaired,
 * hearing impaired and original, one with FlagOriginal 0, one only named "Commentary with the
 * crew", and subtitles flagged hearing impaired. Media3's own extractor reads none of these flags,
 * and its selector plays the default-flagged commentary.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TrackFlagsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = DefaultDataSource.Factory(instrumentation.context)

    @Test
    fun aPlaysPlayerTagsTheFlaggedTracksAndPlaysTheMainAudioOverTheDefaultCommentary() = runBlocking<Unit> {
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(500, 1_000, 250, 250).build()
        onPlayer({ PlayerFactory.create(context, assets, loadControl) }, thenPlays = "3") { player, tracks ->
            assertFlags(tracks)
            Log.i(TAG, "Media3's own pick: audio ${selectedAudio(tracks)}")
            // What PlayerActivity's applier does with a play's first track list, nothing picked yet.
            val plan = TrackPlanner.plan(TrackOptions.of(tracks, Locale.ENGLISH), TrackChoice(), null, ENGLISH, rule = true)
            assertEquals("the plan's audio", "3", plan.audio?.key?.id)
            player.trackSelectionParameters = TrackSelection.apply(player.trackSelectionParameters, tracks, plan, ENGLISH)
        }
    }

    @Test
    fun theTrackProbesPlayerReadsTheFlagsToo() = runBlocking<Unit> {
        onPlayer({ looper -> ExoTrackProbe(context, assets).build(looper) }) { _, tracks -> assertFlags(tracks) }
    }

    private fun assertFlags(tracks: Tracks) {
        val roles = tracks.groups.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }.associate { it.id to it.roleFlags }
        assertEquals(
            mapOf(
                "1" to 0, "2" to C.ROLE_FLAG_COMMENTARY, "3" to 0, "4" to C.ROLE_FLAG_DESCRIBES_VIDEO, "5" to C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND,
                "6" to C.ROLE_FLAG_MAIN, "7" to 0, "8" to 0, "9" to C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND,
            ),
            roles,
        )
        val tags = TrackOptions.of(tracks, Locale.ENGLISH).associate { it.key.id to it.tags }
        assertEquals(setOf(TrackTag.Commentary), tags["2"])
        assertEquals(emptySet<TrackTag>(), tags["3"])
        assertEquals(setOf(TrackTag.AudioDescription), tags["4"])
        assertEquals(setOf(TrackTag.Sdh), tags["5"])
        assertEquals(setOf(TrackTag.Original), tags["6"])
        assertEquals(emptySet<TrackTag>(), tags["7"])
        assertEquals(setOf(TrackTag.Commentary), tags["8"])
        assertEquals(setOf(TrackTag.Sdh), tags["9"])
    }

    /**
     * Builds a player on its own thread for the fixture and gives [onTracks] its first track list
     * there; then, with [thenPlays], waits until the player plays that audio track. Releases it.
     */
    private suspend fun onPlayer(build: (Looper) -> ExoPlayer, thenPlays: String? = null, onTracks: (ExoPlayer, Tracks) -> Unit) {
        val thread = HandlerThread("track-flags-test").apply { start() }
        val handler = Handler(thread.looper)
        val first = CompletableDeferred<Unit>()
        var player: ExoPlayer? = null
        try {
            handler.post {
                player = build(thread.looper).apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (tracks.isEmpty || first.isCompleted) return
                            runCatching { onTracks(this@apply, tracks) }.fold({ first.complete(Unit) }, { first.completeExceptionally(it) })
                        }
                    })
                    setMediaItem(MediaItem.fromUri("asset:///flags.mkv"))
                    prepare()
                }
            }
            withTimeout(20_000) { first.await() }
            if (thenPlays == null) return
            var playing: String? = null
            withTimeoutOrNull(10_000) {
                while (playing != thenPlays) {
                    delay(100)
                    val now = CompletableDeferred<String?>()
                    handler.post { now.complete(player?.currentTracks?.let(::selectedAudio)) }
                    playing = now.await()
                }
            }
            assertEquals("the audio playing", thenPlays, playing)
        } finally {
            handler.post {
                player?.release()
                thread.quitSafely()
            }
        }
    }

    private fun selectedAudio(tracks: Tracks): String? =
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let { g.getTrackFormat(it).id } }

    private companion object {
        const val TAG = "TrackFlagsDeviceTest"
        val ENGLISH = listOf("en")
    }
}

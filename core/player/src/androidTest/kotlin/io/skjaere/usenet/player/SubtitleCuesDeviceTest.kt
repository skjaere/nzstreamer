package io.skjaere.usenet.player

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.Locale

/**
 * Subtitles as a play's player shows them, from `subtitles.mkv` in this test's assets (see the README there): seven
 * seconds with English audio and eight English subtitle tracks, each with cues at times of its own. PGS (track 3) at
 * 1-2, 3-4 and 5-6 s, its bitmaps 40, 48 and 56 pixels wide; PGS flagged forced (4) at 1.5-2.5 and 4.5-5.5 s, 24 and
 * 32 wide; SRT (5) "One", "Two" and "Three" from 1.2, 3.2 and 5.2 s; ASS (6) "Alpha", "Beta" and "Gamma" from 1.4, 3.4
 * and 5.4 s, each a second long; VobSub (7) from 1.6, 3.6 and 5.6 s, 64, 72 and 80 wide, each 0.87 s long (its stop
 * comes 87 ticks after its start, which Media3 reads as hundredths of a second). Then cues that overlap: ASS (8), a
 * sign "North Gate" at 1-4 s with "Which way now?" at 2-3 s inside it, and "First voice" and "Second voice" both at
 * 5-6 s; SRT (9), "[Rain on the roof]" at 1-4 s with "Close the window." at 2-3 s inside it. DVB (10) at 1.8-2.8,
 * 3.8-4.8 and 5.8-6.8 s, 44, 52 and 60 wide.
 *
 * Each test picks subtitles as the sheet does ([TrackSelection]) and records what Player.Listener.onCues shows: each
 * change, with its cues' own time (CueGroup's presentation time), the player's position when it came, and whether it
 * was playing.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SubtitleCuesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = DefaultDataSource.Factory(instrumentation.context)

    @Test
    fun theSheetLabelsEachSubtitleFormatAndCanPickIt() = runBlocking<Unit> {
        val options = play(pick = null).options.filter { it.isText }
        assertEquals(
            mapOf(
                "3" to "English · PGS", "4" to "English · PGS", "5" to "English · SRT", "6" to "English · SSA/ASS", "7" to "English · VobSub",
                "8" to "English · SSA/ASS", "9" to "English · SRT", "10" to "English",
            ),
            options.associate { it.key.id to it.label },
        )
        assertEquals(setOf(TrackTag.Forced), options.single { it.key.id == "4" }.tags)
        assertTrue("a track the sheet can't pick: ${options.filterNot { it.selectable }}", options.all { it.selectable })
    }

    /**
     * The text tracks are parsed while extracting, Media3's default, so overlapping cues show together; so is DVB, whose
     * parser keeps what earlier samples set up. PGS and VobSub reach the renderer undecoded, for it to decode those of
     * the track it shows: parsed while extracting, every bitmap track was decoded on the loader thread.
     */
    @Test
    fun theBitmapTracksReachTheRendererUndecodedAndTheOthersParsed() = runBlocking<Unit> {
        val formats = play(pick = null).tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map { it.getTrackFormat(0) }
        assertEquals(
            mapOf(
                "3" to "raw ${MimeTypes.APPLICATION_PGS}", "4" to "raw ${MimeTypes.APPLICATION_PGS}", "5" to "parsed ${MimeTypes.APPLICATION_SUBRIP}",
                "6" to "parsed ${MimeTypes.TEXT_SSA}", "7" to "raw ${MimeTypes.APPLICATION_VOBSUB}", "8" to "parsed ${MimeTypes.TEXT_SSA}",
                "9" to "parsed ${MimeTypes.APPLICATION_SUBRIP}", "10" to "parsed ${MimeTypes.APPLICATION_DVBSUBS}",
            ),
            formats.associate { it.id to if (it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) "parsed ${it.codecs}" else "raw ${it.sampleMimeType}" },
        )
    }

    @Test
    fun aPgsTrackShowsItsBitmapsAtTheirTimes() = runBlocking<Unit> {
        val shown = play(pick = track("3"), Step(6_300)).shown
        assertShows(listOf(1_000 to "bitmap 40x10", 2_000 to NOTHING, 3_000 to "bitmap 48x10", 4_000 to NOTHING, 5_000 to "bitmap 56x10", 6_000 to NOTHING), shown)
    }

    @Test
    fun anSrtTrackShowsItsLinesAtTheirTimes() = runBlocking<Unit> {
        val shown = play(pick = track("5"), Step(6_500)).shown
        assertShows(listOf(1_200 to "One", 2_200 to NOTHING, 3_200 to "Two", 4_200 to NOTHING, 5_200 to "Three", 6_200 to NOTHING), shown)
    }

    @Test
    fun anAssTrackShowsItsLinesAtTheirTimes() = runBlocking<Unit> {
        val shown = play(pick = track("6"), Step(6_700)).shown
        assertShows(listOf(1_400 to "Alpha", 2_400 to NOTHING, 3_400 to "Beta", 4_400 to NOTHING, 5_400 to "Gamma", 6_400 to NOTHING), shown)
    }

    @Test
    fun aVobSubTrackShowsItsBitmapsAtTheirTimes() = runBlocking<Unit> {
        val shown = play(pick = track("7"), Step(6_900)).shown
        assertShows(listOf(1_600 to "bitmap 64x12", 2_470 to NOTHING, 3_600 to "bitmap 72x12", 4_470 to NOTHING, 5_600 to "bitmap 80x12", 6_470 to NOTHING), shown)
    }

    /** A line inside a longer one, then two that start together: each shows with the others it overlaps, as ASS has it. */
    @Test
    fun anAssTrackShowsOverlappingLinesTogether() = runBlocking<Unit> {
        val shown = play(pick = track("8"), Step(6_500)).shown
        assertShows(
            listOf(
                1_000 to "North Gate", 2_000 to "North Gate + Which way now?", 3_000 to "North Gate", 4_000 to NOTHING,
                5_000 to "First voice + Second voice", 6_000 to NOTHING,
            ),
            shown,
        )
    }

    /** An SRT line inside a longer one (a sound described while someone speaks) shows with it. */
    @Test
    fun anSrtTrackShowsALineInsideALongerOneWithIt() = runBlocking<Unit> {
        val shown = play(pick = track("9"), Step(4_500)).shown
        assertShows(
            listOf(1_000 to "[Rain on the roof]", 2_000 to "[Rain on the roof] + Close the window.", 3_000 to "[Rain on the roof]", 4_000 to NOTHING),
            shown,
        )
    }

    @Test
    fun aDvbTrackShowsItsBitmapsAtTheirTimes() = runBlocking<Unit> {
        val shown = play(pick = track("10"), Step(6_900)).shown
        assertShows(listOf(1_800 to "bitmap 44x10", 2_800 to NOTHING, 3_800 to "bitmap 52x10", 4_800 to NOTHING, 5_800 to "bitmap 60x10", 6_800 to NOTHING), shown)
    }

    @Test
    fun forcedOnlyShowsTheForcedTrackInTheAudiosLanguage() = runBlocking<Unit> {
        val shown = play(pick = { p, t -> TrackSelection.withSubtitles(p, t, SubtitlePlan.ForcedOnly) }, Step(5_800)).shown
        assertShows(listOf(1_500 to "bitmap 24x12", 2_500 to NOTHING, 4_500 to "bitmap 32x12", 5_500 to NOTHING), shown)
    }

    /**
     * From PGS to SRT at 3.5 s, in the middle of a cue of each: the PGS bitmap goes at once and the SRT line that had
     * started ("Two", from 3.2 s) shows at once; from then on the SRT's cues show at their times.
     */
    @Test
    fun switchingFromPgsToSrtMidCueShowsTheSrtAtOnce() = runBlocking<Unit> {
        val shown = play(pick = track("3"), Step(3_500, pick(track("5"))), Step(6_500)).shown
        assertSwitch(
            shown, at = 3_500,
            before = listOf(1_000 to "bitmap 40x10", 2_000 to NOTHING, 3_000 to "bitmap 48x10"),
            current = 3_200 to "Two",
            after = listOf(4_200 to NOTHING, 5_200 to "Three", 6_200 to NOTHING),
        )
    }

    /** From SRT to PGS at 3.5 s, in the middle of a cue of each: as above, the other way round. */
    @Test
    fun switchingFromSrtToPgsMidCueShowsThePgsAtOnce() = runBlocking<Unit> {
        val shown = play(pick = track("5"), Step(3_500, pick(track("3"))), Step(6_300)).shown
        assertSwitch(
            shown, at = 3_500,
            before = listOf(1_200 to "One", 2_200 to NOTHING, 3_200 to "Two"),
            current = 3_000 to "bitmap 48x10",
            after = listOf(4_000 to NOTHING, 5_000 to "bitmap 56x10", 6_000 to NOTHING),
        )
    }

    /**
     * From SRT to DVB at 4 s, in the middle of a cue of each: the DVB bitmap that had started (from 3.8 s) shows at once,
     * as its parser has read the samples before it while extracting.
     */
    @Test
    fun switchingFromSrtToDvbMidCueShowsTheDvbAtOnce() = runBlocking<Unit> {
        val shown = play(pick = track("5"), Step(4_000, pick(track("10"))), Step(6_900)).shown
        assertSwitch(
            shown, at = 4_000,
            before = listOf(1_200 to "One", 2_200 to NOTHING, 3_200 to "Two"),
            current = 3_800 to "bitmap 52x10",
            after = listOf(4_800 to NOTHING, 5_800 to "bitmap 60x10", 6_800 to NOTHING),
        )
    }

    /**
     * Seeking with PGS on: during its first cue forward past its second, from the end back before the first, then into
     * the middle of the second. The cue showing goes at the seek, a cue the seek lands in shows at once, and each cue
     * after shows at its time.
     */
    @Test
    fun aSeekShowsTheCuesOfTheNewPosition() = runBlocking<Unit> {
        val shown = play(pick = track("3"), Step(1_500, seek(4_500)), Step(6_300, seek(500)), Step(2_300, seek(3_500)), Step(4_300)).shown
        Log.i(TAG, "shown: $shown")
        assertEquals(
            "what showed: $shown",
            listOf("bitmap 40x10", NOTHING, "bitmap 56x10", NOTHING, "bitmap 40x10", NOTHING, "bitmap 48x10", NOTHING),
            shown.map { it.cues },
        )
        assertOnTime(shown[0], 1_000)
        assertTrue("the first cue didn't go at the seek: $shown", shown[1].positionMs in 4_500..4_500 + SWITCH_MS)
        assertOnTime(shown[2], 5_000)
        assertOnTime(shown[3], 6_000)
        assertOnTime(shown[4], 1_000)
        assertOnTime(shown[5], 2_000)
        assertTrue("the cue the seek landed in didn't show at once: $shown", shown[6].positionMs in 3_500..3_500 + SWITCH_MS && shown[6].presentationMs in 2_970L..3_030L)
        assertOnTime(shown[7], 4_000)
    }

    /**
     * Paused, a seek into a text cue shows it at once: the text renderer updates text cues while paused, as it does
     * for every track parsed while extracting.
     */
    @Test
    fun aSeekWhilePausedShowsTheSrtCueAtOnce() = runBlocking<Unit> {
        val shown = play(pick = track("5"), Step(2_500, pauseAndSeek(3_500)), Step(3_500, holdMs = 1_500)).shown
        Log.i(TAG, "shown: $shown")
        assertEquals("what showed: $shown", listOf("One", NOTHING, "Two"), shown.map { it.cues })
        assertOnTime(shown[0], 1_200)
        assertOnTime(shown[1], 2_200)
        assertTrue("the cue the seek landed in didn't show while paused: $shown", !shown[2].playing && shown[2].positionMs == 3_500L && shown[2].presentationMs in 3_170L..3_230L)
    }

    /**
     * Paused, a seek into a PGS cue shows it only once playing again: Media3 decodes bitmap subtitles as it renders them
     * (PlayerFactory), and its text renderer renders those only while playing. A known trade-off of decoding only the
     * track shown; it shows at once when playing resumes.
     */
    @Test
    fun aSeekWhilePausedShowsThePgsCueOncePlaying() = runBlocking<Unit> {
        val shown = play(pick = track("3"), Step(1_500, pauseAndSeek(3_500)), Step(3_500, { p -> p.play() }, holdMs = 1_500), Step(4_300)).shown
        Log.i(TAG, "shown: $shown")
        assertEquals("what showed: $shown", listOf("bitmap 40x10", NOTHING, "bitmap 48x10", NOTHING), shown.map { it.cues })
        assertOnTime(shown[0], 1_000)
        assertTrue("the first cue didn't go at the seek: $shown", !shown[1].playing && shown[1].positionMs == 3_500L)
        assertTrue("the cue the seek landed in didn't show at once on play: $shown", shown[2].playing && shown[2].positionMs in 3_500..3_500 + SWITCH_MS && shown[2].presentationMs in 2_970L..3_030L)
        assertOnTime(shown[3], 4_000)
    }

    private fun assertShows(expected: List<Pair<Int, String>>, shown: List<Shown>) {
        Log.i(TAG, "shown: $shown")
        assertEquals("what showed: $shown", expected.map { it.second }, shown.map { it.cues })
        expected.zip(shown).forEach { (e, s) -> assertOnTime(s, e.first) }
    }

    /**
     * A switch at [at], in the middle of a cue of each track: [before] showed; at the switch the old track's cue goes and
     * the new track's [current] cue, which had started, shows at once; then the new track's cues show at their times
     * ([after]). Nothing of the old track shows after the switch.
     */
    private fun assertSwitch(shown: List<Shown>, at: Int, before: List<Pair<Int, String>>, current: Pair<Int, String>, after: List<Pair<Int, String>>) {
        assertShows(before, shown.take(before.size))
        val switched = shown.drop(before.size).take(2)
        assertEquals("at the switch: $shown", listOf(NOTHING, current.second), switched.map { it.cues })
        assertTrue("the switch showed late: $switched", switched.all { it.positionMs in at.toLong()..at + SWITCH_MS })
        assertTrue("$switched: the current cue's time is ${current.first} ms", switched[1].presentationMs in current.first - 30L..current.first + 30L)
        assertShows(after, shown.drop(before.size + 2))
    }

    private fun assertOnTime(shown: Shown, ms: Int) {
        assertTrue("$shown: its cue's time is $ms ms", shown.presentationMs in ms - 30L..ms + 30L)
        assertTrue("$shown: it showed late or early for $ms ms", shown.positionMs in ms - 100L..ms + LATE_MS)
    }

    private fun track(id: String): Pick = { parameters, tracks ->
        TrackSelection.withSubtitles(parameters, tracks, SubtitlePlan.Track(TrackOptions.of(tracks, Locale.ENGLISH).single { it.key.id == id }))
    }

    private fun pick(pick: Pick): (ExoPlayer) -> Unit = { p -> p.trackSelectionParameters = pick(p.trackSelectionParameters, p.currentTracks) }

    private fun seek(ms: Long): (ExoPlayer) -> Unit = { p -> p.seekTo(ms) }

    private fun pauseAndSeek(ms: Long): (ExoPlayer) -> Unit = { p ->
        p.pause()
        p.seekTo(ms)
    }

    /**
     * Plays the fixture on a player of [PlayerFactory] on its own thread, with [pick]'s subtitles (none picked:
     * Media3's own), and does each of [steps]' actions on that thread once the position reaches the step and its hold
     * has passed: picks again, seeks, pauses or plays. Records what onCues shows until the last step's position and
     * hold. Without steps it returns once the first track list is in, not playing.
     */
    private suspend fun play(pick: Pick?, vararg steps: Step): Played {
        val thread = HandlerThread("subtitle-cues-test").apply { start() }
        val handler = Handler(thread.looper)
        val first = CompletableDeferred<Tracks>()
        val failed = CompletableDeferred<PlaybackException>()
        val shown = Collections.synchronizedList(mutableListOf<Shown>())
        var player: ExoPlayer? = null
        try {
            handler.post {
                val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 10_000, 250, 250).build()
                player = PlayerFactory.create(context, assets, loadControl).apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (tracks.isEmpty || first.isCompleted) return
                            pick?.let { trackSelectionParameters = it(trackSelectionParameters, tracks) }
                            first.complete(tracks)
                        }

                        override fun onCues(cueGroup: CueGroup) {
                            val cues = cueGroup.cues.joinToString(" + ", transform = ::describe)
                            // Each change: an empty group while nothing shows (a seek's, a new stream's) is none.
                            if (cues != (shown.lastOrNull()?.cues ?: NOTHING)) shown += Shown(cues, cueGroup.presentationTimeUs / 1_000, currentPosition, isPlaying)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failed.complete(error)
                        }
                    })
                    setMediaItem(MediaItem.fromUri("asset:///subtitles.mkv"))
                    playWhenReady = steps.isNotEmpty()
                    prepare()
                }
            }
            val tracks = withTimeout(20_000) { first.await() }
            for (step in steps) {
                withTimeout(15_000) {
                    while (position(handler) { player } < step.atMs) {
                        if (failed.isCompleted) throw AssertionError("playback failed", failed.await())
                        delay(20)
                    }
                }
                delay(step.holdMs)
                step.action?.let { action -> handler.post { player?.let(action) } }
            }
            if (failed.isCompleted) throw AssertionError("playback failed", failed.await())
            return Played(tracks, TrackOptions.of(tracks, Locale.ENGLISH), shown.toList())
        } finally {
            handler.post {
                player?.release()
                thread.quitSafely()
            }
        }
    }

    private suspend fun position(handler: Handler, player: () -> ExoPlayer?): Long {
        val now = CompletableDeferred<Long>()
        handler.post { now.complete(player()?.currentPosition ?: 0) }
        return now.await()
    }

    private fun describe(cue: Cue): String = cue.bitmap?.let { "bitmap ${it.width}x${it.height}" } ?: cue.text.toString()

    private class Step(val atMs: Long, val action: ((ExoPlayer) -> Unit)? = null, val holdMs: Long = 0)

    private class Played(val tracks: Tracks, val options: List<TrackOption>, val shown: List<Shown>)

    /** A change of what shows: its [cues], their own time, and the player's position when it came and whether it was [playing]. */
    private data class Shown(val cues: String, val presentationMs: Long, val positionMs: Long, val playing: Boolean) {
        override fun toString() = "\"$cues\" (${presentationMs} ms, at $positionMs${if (playing) "" else ", paused"})"
    }

    private companion object {
        const val TAG = "SubtitleCuesDeviceTest"
        const val NOTHING = ""

        /** How late a cue may show on the emulator: the renderer runs every 10 ms, the listener on the player's thread. */
        const val LATE_MS = 400L

        /** How long a switch may take: the selector, the renderer's new stream, the old cue's clearing. */
        const val SWITCH_MS = 600L
    }
}

private typealias Pick = (TrackSelectionParameters, Tracks) -> TrackSelectionParameters

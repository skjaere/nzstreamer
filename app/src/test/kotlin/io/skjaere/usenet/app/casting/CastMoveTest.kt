package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.app.link.PlayOnAsk
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play & connect in the player (W45, W66, L124; CsPhonePlayer): a Cast row tapped there moves the film to the device,
 * unless its file type is one no Cast device plays, or the device is a speaker, which asks first, or can't fully play the
 * film's tracks, which asks the pre-check first; the line under the top bar while it moves, with the audio language the
 * device will play where it isn't yours, and once it didn't, with C1's reasons, then "Still playing here."; the device
 * playing it closes the player. The device, the files and their releases are invented.
 */
class CastMoveTest {
    private val name = "Kitchen display"

    /** A Chromecast Ultra by its model: H.264, HEVC and VP9; AC-3 as every Cast device, no DTS. */
    private val ultra = CastModel.ChromecastUltra.profile(null)
    private val prefs = CastVerdict.Prefs(audioLanguages = listOf("en"), subtitlesWhenForeign = false)
    private val h264 = ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 8)

    /** precheck-german.mkv's tracks: German AC-3 5.1 first, English AC-3 stereo second, English subtitles. */
    private val germanFirst = ProbedTracks(
        listOf(h264),
        listOf(ProbedTrack(MimeTypes.AUDIO_AC3, "AC-3", "de", channels = 6), ProbedTrack(MimeTypes.AUDIO_AC3, "AC-3", "en", channels = 2)),
        listOf(ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "en")),
    )

    /** precheck-dts.mkv's: DTS 5.1 its only sound. */
    private val dtsOnly = ProbedTracks(listOf(h264), listOf(ProbedTrack(MimeTypes.AUDIO_DTS, "DTS", "en", channels = 6)))

    private fun tapped(
        file: String? = "Harbor.Lights.S02E04.mkv", kind: CastDeviceKind = CastDeviceKind.Video, subtitles: Boolean = false, told: Boolean = false,
        fit: CastMove.Fit = CastMove.Fit.None,
    ) = CastMove.tapped(name, kind, file, subtitles, told, fit)

    private fun fit(tracks: ProbedTracks, release: String, compatible: Boolean = false, wanted: CastVerdict.Prefs = prefs) =
        CastMove.Fit.of(CastVerdict.judge(tracks, ultra, wanted), tracks, name, release, compatible)

    /** L124 step 1, L57 (CsPhonePlayer 5): by the file's name, before anything connects; the film never stops. */
    @Test
    fun `a file type no Cast device plays says so for 6 s and connects nothing`() {
        for (file in listOf("Copper.Tide.2026.avi", "Copper.Tide.2026.TS", "copper-tide.wmv")) {
            val step = assertIs<CastMove.Step.Refuse>(tapped(file), file)
            assertEquals("This file type can't play on Kitchen display. Still playing here.", step.line.words)
            assertEquals(step.line.words, step.line.spoken)
            assertEquals(6_000L, step.line.forMs)
        }
        // A castable type, or a launch from before names were kept, moves: the device's load checks it again.
        for (file in listOf("Harbor.Lights.S02E04.mkv", "copper-tide.MP4", "copper-tide.mov", "copper-tide.webm", null)) {
            assertIs<CastMove.Step.Connect>(tapped(file), "$file")
        }
    }

    /**
     * L202 lifted (W73): a film played from a debrid service moves as any, the phone relaying it: its file type judged by its
     * name, a speaker asked first, the pre-check where the device can't fully play it, else it moves.
     */
    @Test
    fun `a debrid film moves as any`() {
        val step = assertIs<CastMove.Step.Connect>(CastMove.tapped(name, CastDeviceKind.Video, "Harbor.Lights.S02E04.mkv", subtitles = false, castAnywayTold = false))
        assertEquals(CastMove.Line("Moving to Kitchen display…", "Moving to Kitchen display", forMs = null), step.line)
        assertIs<CastMove.Step.Refuse>(CastMove.tapped(name, CastDeviceKind.Video, "Harbor.Lights.S02E04.avi", subtitles = false, castAnywayTold = false))
        assertIs<CastMove.Step.AskSoundOnly>(CastMove.tapped(name, CastDeviceKind.Speaker, "Harbor.Lights.S02E04.mkv", subtitles = false, castAnywayTold = false))
    }

    /** L124 step 3 (CsPhonePlayer 3): the panel closes, the film pauses, and the line stays while it moves. */
    @Test
    fun `a screen moves the film there, the line saying so until the move ends`() {
        val step = assertIs<CastMove.Step.Connect>(tapped())
        assertEquals(CastMove.Line("Moving to Kitchen display…", "Moving to Kitchen display", forMs = null), step.line)
        assertEquals("Moving to Kitchen display…", movingTo(name))
    }

    /** W66, W55: with subtitles showing here the move still goes, and its line says they don't show there. */
    @Test
    fun `with subtitles showing the line adds that they don't show on Cast devices`() {
        val step = assertIs<CastMove.Step.Connect>(tapped(subtitles = true))
        assertEquals("Moving to Kitchen display… Subtitles don’t show on Cast devices", step.line.words)
        assertEquals("Moving to Kitchen display. Subtitles don’t show on Cast devices", step.line.spoken)
        assertNull(step.line.forMs)
    }

    /**
     * L124 step 2, L122: a speaker or a speaker group asks first, before anything connects: Cast anyway moves the film,
     * Play on this phone and Cancel leave it playing here, connecting nothing. One told Cast anyway for its session isn't
     * asked again; a refused type is refused before any question.
     */
    @Test
    fun `a speaker asks first, Cast anyway connects, Play on this phone and Cancel leave the film playing`() {
        for (kind in listOf(CastDeviceKind.Speaker, CastDeviceKind.Group)) {
            assertEquals(CastMove.Step.AskSoundOnly, tapped(kind = kind), "$kind")
            assertIs<CastMove.Step.Connect>(CastMove.answered(PlayOnAsk.SoundOnly.Answer.CastAnyway, name, subtitles = false))
            assertEquals(CastMove.Step.Stay, CastMove.answered(PlayOnAsk.SoundOnly.Answer.PlayHere, name, subtitles = false))
            assertEquals(CastMove.Step.Stay, CastMove.answered(PlayOnAsk.SoundOnly.Answer.Cancel, name, subtitles = false))
            assertIs<CastMove.Step.Connect>(tapped(kind = kind, told = true), "$kind told Cast anyway")
            assertIs<CastMove.Step.Refuse>(tapped("hallway.avi", kind = kind), "$kind with an AVI")
        }
        val anyway = assertIs<CastMove.Step.Connect>(CastMove.answered(PlayOnAsk.SoundOnly.Answer.CastAnyway, name, subtitles = true))
        assertEquals("Moving to Kitchen display… Subtitles don’t show on Cast devices", anyway.line.words)
    }

    /** L124 step 6, L149: a connect that fails plays on here, with its line for 6 s. */
    @Test
    fun `a connect that fails plays on here with its line`() {
        val step = assertIs<CastMove.Step.PlayOn>(CastMove.connectFailed(name))
        assertEquals(CastMove.Line("Couldn't connect to Kitchen display. Still playing here."), step.line)
        assertEquals(6_000L, step.line.forMs)
    }

    /** L124 steps 5 and 6: the device playing it closes the player; another play taking the sheet's place leaves it as it is. */
    @Test
    fun `moved closes the player, another play's leaves it`() {
        assertEquals(CastMove.Step.Close, CastMove.after(MoveOutcome.Moved, name))
        assertEquals(CastMove.Step.Stay, CastMove.after(MoveOutcome.Stopped, name))
        val failed = assertIs<CastMove.Step.PlayOn>(CastMove.after(MoveOutcome.Failed(CastLoadFailure(CastFailKind.CouldNotPlay, CAST_DECODING_ERROR, format = true)), name))
        assertEquals("The device's player stopped with a decoding error. Still playing here.", failed.line.words)
    }

    /** L124 step 6: each failure the device or the phone's server tells, in C1's words, then that the film plays on here. */
    @Test
    fun `each failure's line is C1's reason and Still playing here`() {
        val nothing = ServeCounters()
        val served = ServeCounters(requests = 2, partials = 1)
        val lines = listOf(
            CastOutcomes.vanished(served, name, CastFile("mov", video = true)) to "MOV file. Kitchen display refused it as it opened. Still playing here.",
            CastOutcomes.vanished(served, name, CastFile("mkv", video = false)) to "Audio-only file. Kitchen display refused it as it opened. Still playing here.",
            CastOutcomes.error(102, served, name, CastFile("mkv", video = true)) to "The device's player stopped with a decoding error. Still playing here.",
            CastOutcomes.error(104, served, name, CastFile("mkv", video = true)) to "The device's player can't open this file. Still playing here.",
            CastOutcomes.error(110, served, name, CastFile("mkv", video = true)) to "The device's player stopped (error 110). Still playing here.",
            CastOutcomes.error(103, served, name, CastFile("mkv", video = true)) to "Kitchen display lost the stream from this phone. Still playing here.",
            CastOutcomes.vanished(ServeCounters(requests = 2, upstreamFailures = 1), name, CastFile("mkv", video = true)) to
                "Couldn't read the release from your providers. Still playing here.",
            CastOutcomes.vanished(served, name, CastFile("mkv", video = true)) to "The device's player closed the file without an error. Still playing here.",
            CastOutcomes.noRequest() to "Kitchen display can't reach this phone. Still playing here.",
            CastOutcomes.closedBeforePlaying(served, name) to "Kitchen display closed before it played. Still playing here.",
            CastOutcomes.closedBeforePlaying(nothing, name) to "Kitchen display can't reach this phone. Still playing here.",
            CastOutcomes.interrupted(name) to "Something else started playing on Kitchen display. Still playing here.",
            CastLoadFailure(CastFailKind.NotStarted, CAST_NOT_STARTED) to "Couldn't start casting. Try again with NZStreamer open. Still playing here.",
            CastLoadFailure(CastFailKind.CouldNotPlay) to "Kitchen display couldn't play this release. Still playing here.",
        )
        for ((failure, words) in lines) {
            val line = CastMove.failedLine(failure, name)
            assertEquals(words, line.words, "$failure")
            assertEquals(words, line.spoken)
            assertEquals(6_000L, line.forMs)
        }
    }

    /**
     * L124 step 3, W51 (item 44): with nothing to ask, a film whose first sound track, the one a Cast device plays, isn't
     * in your first language moves with its line saying which one the device will play; and with subtitles showing here,
     * that they don't show there too. In your first language, or with its tracks never read, the line is as before.
     */
    @Test
    fun `a German-first film's move line says the device will play the German audio`() {
        val german = fit(germanFirst, GERMAN_DL)
        assertNull(german.question, "nothing to ask")
        assertEquals("German", german.language)
        val step = assertIs<CastMove.Step.Connect>(tapped(fit = german))
        assertEquals(CastMove.Line("Moving to Kitchen display… Kitchen display will play the German audio", "Moving to Kitchen display. Kitchen display will play the German audio", forMs = null), step.line)
        assertEquals(false, step.compatible)
        val both = assertIs<CastMove.Step.Connect>(tapped(subtitles = true, fit = german))
        assertEquals("Moving to Kitchen display… Kitchen display will play the German audio. Subtitles don’t show on Cast devices", both.line.words)
        assertEquals("Moving to Kitchen display. Kitchen display will play the German audio. Subtitles don’t show on Cast devices", both.line.spoken)
        // German wanted first: the device plays what you want, and the line says nothing of it.
        val yours = fit(germanFirst, GERMAN_DL, wanted = prefs.copy(audioLanguages = listOf("de", "en")))
        assertEquals(CastMove.Fit(), yours)
        assertEquals(CastMove.Line("Moving to Kitchen display…", "Moving to Kitchen display", forMs = null), assertIs<CastMove.Step.Connect>(tapped(fit = yours)).line)
        // Tracks never read (a launch from before they were kept): nothing judged, nothing said.
        assertEquals(CastMove.Fit.None, CastMove.Fit.of(null, null, name, GERMAN_DL, compatible = true))
        // A speaker told Cast anyway moves with the same line.
        val anyway = assertIs<CastMove.Step.Connect>(CastMove.answered(PlayOnAsk.SoundOnly.Answer.CastAnyway, name, subtitles = false, german))
        assertEquals(step.line, anyway.line)
    }

    /**
     * L124 step 2, L130, L152 (item 44; CsPhonePrecheck): a film the device can't fully play asks the pre-check first, over
     * the film, before anything connects: Play on this phone offered, as this phone plays it now; Play a compatible release
     * only where the launch's request has another release the device should play. Cast anyway moves the film, with no
     * audio line (the sheet said it); Play a compatible release connects, and the list's others play there in its place;
     * Play on this phone and Cancel leave the film playing here and connect nothing. A refused type is refused first, and
     * a speaker asks its own question first, whose Cast anyway then asks this one.
     */
    @Test
    fun `a film the device can't fully play asks the pre-check first, and each answer does what it says`() {
        val dts = fit(dtsOnly, DTS_REMUX, compatible = true)
        val question = assertNotNull(dts.question)
        assertEquals("No sound on Kitchen display", question.headline)
        assertEquals("DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.", question.reason)
        assertEquals(DTS_REMUX, question.release)
        assertTrue(question.here, "this phone plays it: it is playing here")
        assertEquals(listOf(CastPrecheck.Button.Compatible, CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), question.buttons())
        assertNull(dts.language, "the sheet says its own lines")
        assertEquals(CastMove.Step.AskPrecheck(question), tapped(fit = dts))
        assertIs<CastMove.Step.Refuse>(tapped("lantern-field.avi", fit = dts))
        assertEquals(CastMove.Step.AskSoundOnly, tapped(kind = CastDeviceKind.Speaker, fit = dts))
        assertEquals(CastMove.Step.AskPrecheck(question), CastMove.answered(PlayOnAsk.SoundOnly.Answer.CastAnyway, name, subtitles = false, dts))
        assertEquals(CastMove.Step.Stay, CastMove.answered(PlayOnAsk.SoundOnly.Answer.Cancel, name, subtitles = false, dts))
        // The answers.
        assertEquals(
            CastMove.Step.Connect(CastMove.Line("Moving to Kitchen display…", "Moving to Kitchen display", forMs = null)),
            CastMove.prechecked(CastPrecheck.Button.CastAnyway, name, subtitles = false),
        )
        val compatible = assertIs<CastMove.Step.Connect>(CastMove.prechecked(CastPrecheck.Button.Compatible, name, subtitles = true))
        assertTrue(compatible.compatible, "the list's others play there")
        assertEquals("Moving to Kitchen display… Subtitles don’t show on Cast devices", compatible.line.words)
        assertEquals(CastMove.Step.Stay, CastMove.prechecked(CastPrecheck.Button.PlayHere, name, subtitles = false))
        assertEquals(CastMove.Step.Stay, CastMove.prechecked(CastPrecheck.Button.Cancel, name, subtitles = false))
        // No other release the device should play: no Play a compatible release.
        val alone = assertNotNull(fit(dtsOnly, DTS_REMUX, compatible = false).question)
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), alone.buttons())
    }

    private companion object {
        const val GERMAN_DL = "Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM"
        const val DTS_REMUX = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
    }
}

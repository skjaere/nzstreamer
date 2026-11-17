package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.app.casting.CastDownloadPlan.Place
import io.skjaere.usenet.app.casting.CastDownloadPlan.Plan
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.Hdr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A finished download's file cast (W121 c; L446, L447, L473): what the coordinator's hook makes of a title's Play with a
 * Cast device chosen, and what castDownload shows for it. A file the device plays, or one whose tracks nothing read, is
 * cast. A file the device can't fully play, or of a type no Cast device opens, streams the title for the device from a
 * title's page while online, as before (W121 c); offline, as nothing can stream, and always from the places of files (the
 * Downloads pages, Home's Downloads rows, the notifications, Import), it asks the pre-check over the file, or says its type
 * can't play there. An imported file has no title to stream (L473). The sheet then: the title and its line, the one step
 * "Starting on Kitchen display" with W63's lines and the release under it; the pre-check with Play on this phone, Cast
 * anyway and Cancel, never Play a compatible release; the type's failure with Play on this phone and Close. The show,
 * its releases and the device are invented.
 */
class CastDownloadPlanTest {
    /** A first Chromecast: H.264 up to 1080p, nothing else. */
    private val chromecast = CastModel.Chromecast.profile(null)

    /** A Chromecast Ultra: HEVC at 10 bits, Dolby Vision. */
    private val ultra = CastModel.ChromecastUltra.profile(null)
    private val kitchen = CastTo("route-kitchen", "Kitchen display")
    private val prefs = CastVerdict.Prefs(audioLanguages = listOf("en"), subtitlesWhenForeign = true)

    private val h264 = ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 8)
    private val hevc10 = ProbedTrack(MimeTypes.VIDEO_H265, "HEVC", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.Hdr10)
    private val dolbyVision = ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 8)
    private fun audio(mime: String, label: String, language: String? = "en", channels: Int = 6) = ProbedTrack(mime, label, language, channels = channels)
    private val ac3 = audio(MimeTypes.AUDIO_AC3, "AC-3")
    private val dts = audio(MimeTypes.AUDIO_DTS, "DTS")
    private val englishSubs = ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "en")

    private val plays = ProbedTracks(listOf(h264), listOf(ac3))
    private val silent = ProbedTracks(listOf(h264), listOf(dts))

    private fun of(fileName: String, tracks: ProbedTracks?, profile: CastProfile = chromecast, offline: Boolean = false, place: Place = Place.Title, release: Boolean = false) =
        CastDownloadPlan.of(fileName, tracks, profile, offline, place, release)

    @Test
    fun `a file the device plays, or whose tracks nothing read, is cast from anywhere, online or not`() {
        for (place in Place.entries) for (offline in listOf(false, true)) {
            assertEquals(Plan.Cast, of(MKV, plays, offline = offline, place = place), "$place offline=$offline")
            assertEquals(Plan.Cast, of(MKV, null, offline = offline, place = place), "no tracks, $place offline=$offline")
            assertEquals(Plan.Cast, of(MKV, ProbedTracks(), offline = offline, place = place), "none read, $place offline=$offline")
        }
        // MP4, WebM and MOV are cast too.
        listOf("Harbor.Lights.S02E05.mp4", "Harbor.Lights.S02E05.webm", "Harbor.Lights.S02E05.mov").forEach { assertEquals(Plan.Cast, of(it, plays), it) }
    }

    /** W121 (c), L446, L447: a miss streams the title for the device from its page while online, and asks over the file otherwise. */
    @Test
    fun `a file the device can't fully play streams from a title's page online, and asks over the file offline or from the files`() {
        assertEquals(Plan.Stream, of(MKV, silent))
        assertEquals(Plan.Ask, of(MKV, silent, offline = true))
        assertEquals(Plan.Ask, of(MKV, silent, place = Place.Files))
        assertEquals(Plan.Ask, of(MKV, silent, offline = true, place = Place.Files))
    }

    /** L446, L447: a type no Cast device opens streams from a title's page while online; otherwise its type's failure says so. */
    @Test
    fun `a type no Cast device opens streams from a title's page online, and says so offline or from the files`() {
        for (avi in listOf(AVI, "Harbor.Lights.S02E05.1080i.HDTV.MPEG2-GRP.ts")) {
            assertEquals(Plan.Stream, of(avi, plays), avi)
            assertEquals(Plan.Type, of(avi, plays, offline = true), avi)
            assertEquals(Plan.Type, of(avi, plays, place = Place.Files), avi)
            assertEquals(Plan.Type, of(avi, null, place = Place.Files), "$avi with no tracks")
        }
    }

    /** L473: an imported file has no title to stream: a miss always asks and its type always says so, from Up next's Resume too. */
    @Test
    fun `an imported file never streams, from a title's page online too`() {
        assertEquals(Plan.Ask, of(MKV, silent, release = true))
        assertEquals(Plan.Type, of(AVI, plays, release = true))
        assertEquals(Plan.Cast, of(MKV, plays, release = true))
    }

    /** The verdict is the device's: DTS-only sound plays nowhere; Dolby Vision and HEVC at 10 bits only on a device that shows them. */
    @Test
    fun `DTS-only sound, Dolby Vision and HEVC at 10 bits on a device without them`() {
        assertEquals(Plan.Stream, of(MKV, silent, ultra))
        assertEquals(Plan.Ask, of(MKV, silent, ultra, place = Place.Files))
        for (video in listOf(hevc10, dolbyVision)) {
            val tracks = ProbedTracks(listOf(video), listOf(ac3))
            assertEquals(Plan.Stream, of(MKV, tracks, chromecast), video.label)
            assertEquals(Plan.Ask, of(MKV, tracks, chromecast, offline = true), video.label)
            assertEquals(Plan.Ask, of(MKV, tracks, chromecast, place = Place.Files), video.label)
            assertEquals(Plan.Cast, of(MKV, tracks, ultra), "${video.label} on a Chromecast Ultra")
        }
    }

    private val release = SavedRelease("fake-indexer", "hl-s02e05", "Harbor.Lights.S02E05.German.DL.1080p.WEB.H264-KESTREL", null, 0, "nzb-hl", MKV, 1_300_000_000)

    /** A finished download's launch to Kitchen display, its tracks as Resolve's probe read them. */
    private fun launch(tracks: ProbedTracks?, fileName: String = MKV) = PlayerLaunch(
        PlayUris.download("3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d"), "Harbor Lights", "S02E05 · The Long Tide", WatchKey.episode(900002, 2, 5),
        WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 5), showTmdbId = 900002, season = 2, episode = 5),
        release.copy(fileName = fileName), requestId = null, tracks = tracks, cast = kitchen,
    )

    private fun sheet(plan: Plan, launch: PlayerLaunch, profile: CastProfile = chromecast) = CastDownloadPlan.sheet(plan, launch, profile, prefs, kitchen)

    /**
     * L447, W63: the cast's sheet is the play's title and its line, "Casting to Kitchen display", and the one step "Starting
     * on Kitchen display" once the file is handed over, the release's name under it, with no release steps; and the lines
     * W63 says before a cast plays, from the file's tracks and what the user wants of them: the German audio played, as
     * Cast devices play a file's first sound track, and no subtitles there.
     */
    @Test
    fun `a file cast says the title, the one step and W63's lines, with no release steps`() {
        val german = ProbedTracks(listOf(h264), listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "de"), ac3), listOf(englishSubs))
        val starting = assertIs<PlayState.Running>(sheet(Plan.Cast, launch(german)))
        assertEquals("Harbor Lights" to "S02E05 · The Long Tide", starting.title to starting.subtitle)
        assertEquals(release.title, starting.release)
        assertEquals(emptyMap(), starting.steps)
        assertNull(starting.searchLine)
        assertEquals(
            listOf(
                CastLine(CastLine.Kind.Audio, "Kitchen display will play the German audio: Cast devices play a file’s first sound track."),
                CastLine(CastLine.Kind.Subtitles, "Subtitles don’t show on Cast devices"),
            ),
            starting.castLines,
        )
        assertEquals("Casting to Kitchen display", castingTo(kitchen.name))
        assertEquals(StepStatus.Running(), CastStart.loading(starting).castStep)
        assertEquals("Starting on Kitchen display", startingOn(kitchen.name))
        // English first, no subtitles to show: nothing to say. Nothing read of the tracks: nothing either.
        assertEquals(emptyList(), assertIs<PlayState.Running>(sheet(Plan.Cast, launch(plays))).castLines)
        assertEquals(emptyList(), assertIs<PlayState.Running>(sheet(Plan.Cast, launch(null))).castLines)
    }

    /**
     * L447 (CQ2): the pre-check over the file, in the pre-check's words: what the device can't play as the headline, the
     * reason over the release's name, and that nothing was sent yet; then Play on this phone, Cast anyway and Cancel, never
     * Play a compatible release, as a file has no list of others.
     */
    @Test
    fun `a file the device can't fully play asks the pre-check with this phone, Cast anyway and Cancel`() {
        val tracks = ProbedTracks(listOf(hevc10), listOf(ac3), listOf(englishSubs))
        val asking = assertIs<PlayState.Prechecking>(sheet(Plan.Ask, launch(tracks)))
        assertEquals("Harbor Lights" to "S02E05 · The Long Tide", asking.title to asking.subtitle)
        val precheck = asking.precheck
        assertEquals("No picture on Kitchen display", precheck.headline)
        assertEquals("HEVC 10-bit video. Kitchen display can’t play it.", precheck.reason)
        assertEquals(release.title, precheck.release)
        assertEquals("Checked before anything was sent to Kitchen display.", precheck.note)
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), precheck.buttons())
        assertEquals(listOf("Play on this phone", "Cast anyway", "Cancel"), precheck.buttons().map { it.words })
        // Cast anyway: the starting sheet, with no lines, as the pre-check said them.
        assertEquals(emptyList(), assertIs<PlayState.Running>(sheet(Plan.Cast, launch(tracks))).castLines)
    }

    /** L447, L57: a type no Cast device opens: its failure, checked before anything was sent, with this phone and Close. */
    @Test
    fun `a type no Cast device opens says so, with this phone and Close`() {
        val failed = assertIs<PlayState.Failed>(sheet(Plan.Type, launch(plays, AVI)))
        assertEquals("Harbor Lights" to "S02E05 · The Long Tide", failed.title to failed.subtitle)
        assertEquals(listOf(release.title to "AVI file. Cast devices play MKV, MP4, WebM and MOV."), failed.failures)
        val cast = failed.cast ?: throw AssertionError("not a cast's failure")
        assertEquals(CastFailKind.FileType, cast.kind)
        assertEquals(
            CastFailedWords(headline = "This file type can't play on Kitchen display", cards = true, note = "Checked before anything was sent to Kitchen display."),
            CastStart.words(cast),
        )
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(cast))
        // Nothing was made for the device: Play on this phone asks for the play again here, which plays the file.
        assertEquals(CastAction.PlayHere(null), CastStart.press(CastButton.PlayHere, cast))
    }

    @Test
    fun `a stream shows no sheet of the file's`() {
        assertNull(sheet(Plan.Stream, launch(silent)))
    }

    private companion object {
        const val MKV = "Harbor.Lights.S02E05.German.DL.1080p.WEB.H264-KESTREL.mkv"
        const val AVI = "Harbor.Lights.S02E05.The.Long.Tide.DVDRip.XviD-GRP.avi"
    }
}

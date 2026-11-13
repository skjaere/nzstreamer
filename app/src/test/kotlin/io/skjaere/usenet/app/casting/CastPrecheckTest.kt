package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.app.play.ChooseFile
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The pre-check (W51, W63, L130, L142, L152; CsPhonePrecheck): a release picked by hand that the Cast device can't fully
 * play asks first, in the design's words: what it can't play there as the headline, a card with the reason and the
 * release's name, the audio and subtitles lines where they apply, and that nothing was sent yet; then Play a compatible
 * release (only with one to play), Play on this phone, Cast anyway and Cancel. A file with nothing to ask has its two
 * lines said on the starting sheet instead. The film, its releases and the device are invented.
 */
class CastPrecheckTest {
    private val chromecast = CastModel.Chromecast.profile(null)
    private val other = CastModel.Other.profile(null)
    private val prefs = CastVerdict.Prefs(audioLanguages = listOf("en"), subtitlesWhenForeign = true)

    private val h264 = ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 8)
    private fun audio(mime: String, label: String, language: String? = "en", channels: Int? = 6) = ProbedTrack(mime, label, language, channels = channels)

    /** Media3 reads a Matroska A_DTS track as plain DTS, whatever its extension: the release's name says which DTS it is. */
    private val dts = audio(MimeTypes.AUDIO_DTS, "DTS")
    private val trueHd = audio(MimeTypes.AUDIO_TRUEHD, "TrueHD", channels = 8)
    private val germanAc3 = audio(MimeTypes.AUDIO_AC3, "AC-3", "de")
    private val englishAc3 = audio(MimeTypes.AUDIO_AC3, "AC-3", channels = 2)
    private val englishSubs = ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "en")

    private fun precheck(tracks: ProbedTracks, release: String, profile: CastProfile = other, compatible: Boolean = true, here: Boolean = true): CastPrecheck =
        assertNotNull(CastPrecheck.of(CastVerdict.judge(tracks, profile, prefs), tracks, KITCHEN, release, ReleaseParser.parse(release).audio, compatible, here))

    @Test
    fun `a file whose only sound the device can't play says so, by the release's own word for its DTS`() {
        val sheet = precheck(ProbedTracks(listOf(h264), listOf(dts)), DTS_REMUX)
        assertEquals("No sound on Kitchen display", sheet.headline)
        assertEquals("DTS-HD MA 5.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.", sheet.reason)
        assertEquals(DTS_REMUX, sheet.release)
        assertEquals(emptyList(), sheet.lines)
        assertEquals("Checked before anything was sent to Kitchen display.", sheet.note)
        // A name that says nothing of its DTS leaves the probe's word, with its channels.
        val plain = precheck(ProbedTracks(listOf(h264), listOf(audio(MimeTypes.AUDIO_DTS_HD, "DTS-HD", channels = 8))), PLAIN)
        assertEquals("DTS-HD 7.1 is its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.", plain.reason)
        // Two tracks it can't play are said together (L149), the name's word for the first DTS alone.
        val two = precheck(ProbedTracks(listOf(h264), listOf(dts, trueHd)), "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1.TrueHD.7.1-FERRY")
        assertEquals("DTS-HD MA 5.1 and TrueHD 7.1 are its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.", two.reason)
        // Three, as a list; a track of no known channels is said by its codec alone, once.
        val three = precheck(ProbedTracks(listOf(h264), listOf(dts, trueHd, audio(MimeTypes.AUDIO_DTS, "DTS", channels = null), audio(MimeTypes.AUDIO_DTS, "DTS", channels = 2))), PLAIN)
        assertEquals("DTS 5.1, TrueHD 7.1, DTS and DTS 2.0 are its only sound. Cast devices can’t play DTS or TrueHD, so it would play silent.", three.reason)
        // Sound beyond DTS and TrueHD that receivers skip (CFR-3: MP2) is said as what they can't play.
        val mp2 = audio(MimeTypes.AUDIO_MPEG_L2, "MP2", channels = 2)
        assertEquals("MP2 2.0 is its only sound. Cast devices can’t play it, so it would play silent.", precheck(ProbedTracks(listOf(h264), listOf(mp2)), PLAIN).reason)
        assertEquals("MP2 2.0 and DTS 5.1 are its only sound. Cast devices can’t play them, so it would play silent.", precheck(ProbedTracks(listOf(h264), listOf(mp2, dts)), PLAIN).reason)
    }

    @Test
    fun `video no Cast device shows says so, and one the device's model doesn't show names it`() {
        val vc1 = precheck(ProbedTracks(listOf(ProbedTrack(MimeTypes.VIDEO_VC1, "VC-1", width = 1920, height = 1080)), listOf(englishAc3)), VC1_REMUX)
        assertEquals("No picture on Kitchen display", vc1.headline)
        assertEquals("VC-1 video. Cast devices can’t play it.", vc1.reason)
        assertEquals(VC1_REMUX, vc1.release)
        val tenBit = precheck(ProbedTracks(listOf(h264.copy(bitDepth = 10)), listOf(englishAc3)), PLAIN)
        assertEquals("H.264 10-bit video. Cast devices can’t play it.", tenBit.reason)
        // A first Chromecast shows no HEVC; others do (L149's words).
        val hevc = precheck(ProbedTracks(listOf(ProbedTrack(MimeTypes.VIDEO_H265, "HEVC", width = 1920, height = 1080, bitDepth = 8)), listOf(englishAc3)), PLAIN, chromecast)
        assertEquals("No picture on Kitchen display", hevc.headline)
        assertEquals("HEVC video. Kitchen display can’t play it.", hevc.reason)
        val dolbyVision = ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 1920, height = 1080, bitDepth = 10, dolbyVisionProfile = 8)
        assertEquals("Dolby Vision profile 8 video. Kitchen display can’t play it.", precheck(ProbedTracks(listOf(dolbyVision), listOf(englishAc3)), PLAIN, chromecast).reason)
    }

    /** CsPhonePrecheck frame 3: 4K on a model known to show up to 1080p, with German audio first and English subtitles. */
    @Test
    fun `4K on an HD model says what it shows, with the audio and subtitles lines`() {
        val uhd = ProbedTracks(listOf(h264.copy(width = 3840, height = 2160)), listOf(germanAc3, englishAc3), listOf(englishSubs))
        val sheet = precheck(uhd, GERMAN_UHD, chromecast)
        assertEquals("Kitchen display shows HD", sheet.headline)
        assertEquals("2160p (4K). Kitchen display shows up to 1080p, so it may not play this file.", sheet.reason)
        assertEquals(
            listOf(
                CastLine(CastLine.Kind.Audio, "Kitchen display will play the German audio: Cast devices play a file’s first sound track."),
                CastLine(CastLine.Kind.Subtitles, "Subtitles don’t show on Cast devices"),
            ),
            sheet.lines,
        )
        // A size with no name of its own is said by its lines (a track whose width the file doesn't give is sized by its height).
        val qhd = precheck(ProbedTracks(listOf(h264.copy(width = null, height = 1440)), listOf(englishAc3)), PLAIN, chromecast)
        assertEquals("1440p. Kitchen display shows up to 1080p, so it may not play this file.", qhd.reason)
    }

    @Test
    fun `Play a compatible release is offered only with one to play, and nothing more to a device that went`() {
        val sheet = precheck(ProbedTracks(listOf(h264), listOf(dts)), DTS_REMUX)
        assertEquals(listOf("Play a compatible release", "Play on this phone", "Cast anyway", "Cancel"), sheet.buttons().map { it.words })
        val none = precheck(ProbedTracks(listOf(h264), listOf(dts)), DTS_REMUX, compatible = false)
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), none.buttons())
        assertFalse(none.compatible)
        // The device went as the sheet showed (W36): nothing more goes to it.
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.Cancel), sheet.buttons(live = false))
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.Cancel), none.buttons(live = false))
        // CFU-1: this phone's own probe can't play it either (DTS, with no DTS decoder here): Play on this phone would end
        // the cast for a play that can't start, so it isn't offered.
        val notHere = precheck(ProbedTracks(listOf(h264), listOf(dts)), DTS_REMUX, here = false)
        assertEquals(listOf(CastPrecheck.Button.Compatible, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), notHere.buttons())
        assertEquals(listOf(CastPrecheck.Button.Cancel), notHere.buttons(live = false))
    }

    /**
     * L152: Play a compatible release plays the list's other releases in its order, those warned for the device left out,
     * as an automatic pick, which skips any that fail the verdict; with none left it isn't offered.
     */
    @Test
    fun `Play a compatible release plays the list's others the device should play, as an automatic pick`() {
        val fits = ranked("Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM")
        val silent = ranked("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.TrueHD.7.1-FERRY", ReleaseReason.NoSoundOn("TrueHD", KITCHEN))
        val dark = ranked("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD.5.1-LUMEN", ReleaseReason.NoPictureOn("VC-1", KITCHEN))
        val big = ranked("Lantern.Field.2008.2160p.UHD.BluRay.x265-WATTWURM", ReleaseReason.TooBigFor(2160, 1080, KITCHEN))
        val colours = ranked("Lantern.Field.2008.2160p.DV.WEB-DL.DDP5.1.H.265-GRP", ReleaseReason.DolbyVisionOnlyOn(KITCHEN))
        val buffers = ranked("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-GRP", ReleaseReason.MayBuffer(31))
        val picked = ranked(DTS_REMUX, ReleaseReason.NoSoundOn("DTS-HD MA", KITCHEN))
        val request = PlayRequest(
            "Lantern Field", "2008", listOf(picked), FilePicker.Largest, WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = FILM),
            rememberedFirst = false, chosen = true, alternatives = listOf(silent, fits, dark, big, colours, buffers),
        )
        assertEquals(
            request.copy(candidates = listOf(fits, buffers), chosen = false, rememberedFirst = false, alternatives = emptyList()),
            CastPrecheck.compatible(request),
        )
        // Choose file's question isn't asked again of releases the user didn't pick: their own file plays.
        val episode = request.copy(picker = ChooseFile(FilePicker.Episode(2, 4)))
        assertEquals(FilePicker.Episode(2, 4), CastPrecheck.compatible(episode)?.picker)
        assertNull(CastPrecheck.compatible(request.copy(alternatives = listOf(silent, dark, big, colours))))
        assertNull(CastPrecheck.compatible(request.copy(alternatives = emptyList())))
    }

    /** W63: with nothing the device can't play, the two lines show on the starting sheet, under "Casting to Kitchen display". */
    @Test
    fun `with nothing to ask the starting sheet says the audio and subtitles lines`() {
        val german = CastVerdict.judge(ProbedTracks(listOf(h264), listOf(germanAc3, englishAc3), listOf(englishSubs)), chromecast, prefs)
        assertEquals(
            listOf(
                CastLine(CastLine.Kind.Audio, "Kitchen display will play the German audio: Cast devices play a file’s first sound track."),
                CastLine(CastLine.Kind.Subtitles, "Subtitles don’t show on Cast devices"),
            ),
            CastPrecheck.startingLines(german, KITCHEN),
        )
        // French first, English subtitles off: the audio line alone.
        val french = CastVerdict.judge(ProbedTracks(listOf(h264), listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "fra"))), chromecast, prefs.copy(subtitlesWhenForeign = false))
        assertEquals(listOf(CastLine(CastLine.Kind.Audio, "Kitchen display will play the French audio: Cast devices play a file’s first sound track.")), CastPrecheck.startingLines(french, KITCHEN))
        // The first language wanted, nothing to say; nor of a language with no English name.
        assertEquals(emptyList(), CastPrecheck.startingLines(CastVerdict.judge(ProbedTracks(listOf(h264), listOf(englishAc3)), chromecast, prefs), KITCHEN))
        assertEquals(emptyList(), CastPrecheck.startingLines(CastVerdict.judge(ProbedTracks(listOf(h264), listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "qqq"))), chromecast, prefs), KITCHEN))
        // What the device can't play asked first, on the pre-check: the starting sheet repeats none of it.
        val uhd = CastVerdict.judge(ProbedTracks(listOf(h264.copy(width = 3840, height = 2160)), listOf(germanAc3), listOf(englishSubs)), chromecast, prefs)
        assertEquals(emptyList(), CastPrecheck.startingLines(uhd, KITCHEN))
        assertEquals(emptyList(), CastPrecheck.startingLines(null, KITCHEN))
    }

    /** A device's name reaches no log by a slip: the sheet's words print as what they are, not as what they say. */
    @Test
    fun `the pre-check prints without the device's name`() {
        val marker = "Marker-7f3k9q"
        val uhd = ProbedTracks(listOf(h264.copy(width = 3840, height = 2160)), listOf(germanAc3), listOf(englishSubs))
        val sheet = assertNotNull(CastPrecheck.of(CastVerdict.judge(uhd, chromecast, prefs), uhd, marker, GERMAN_UHD, emptyList(), compatible = true, here = true))
        assertFalse(marker in "$sheet", "$sheet")
        sheet.lines.forEach { assertFalse(marker in "$it", "$it") }
        // Nothing to ask, no sheet.
        assertNull(CastPrecheck.of(CastVerdict.judge(ProbedTracks(listOf(h264), listOf(englishAc3)), other, prefs), ProbedTracks(listOf(h264), listOf(englishAc3)), KITCHEN, PLAIN, emptyList(), compatible = true, here = true))
    }

    private fun ranked(title: String, vararg reasons: ReleaseReason) = RankedItem(
        NewznabItem(
            indexerId = "fake", guid = title.lowercase(), title = title, sizeBytes = 9_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("fake/${title.lowercase()}", title, 9_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = reasons.toList(), estimatedBitrate = null),
    )

    private companion object {
        const val KITCHEN = "Kitchen display"
        const val FILM = 900120
        const val DTS_REMUX = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val VC1_REMUX = "Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD.5.1-LUMEN"
        const val GERMAN_UHD = "Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x265-WATTWURM"
        const val PLAIN = "Lantern.Field.2008.1080p.BluRay.x264-GRP"
    }
}

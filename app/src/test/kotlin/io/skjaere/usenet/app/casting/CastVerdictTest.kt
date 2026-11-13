package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.player.SubtitleChoice
import io.skjaere.usenet.releases.Hdr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cast's verdict on the file's tracks (L129, W51): a Cast device plays the first video track and the first audio track
 * it can play, in the file's order, so the verdict judges those by the device's profile (L128), not this phone's
 * decoders. What it can't play is no sound, no picture or too big; and before it plays, which audio language it will play
 * and whether subtitles this phone would show go missing (L142, W55). The tracks are invented.
 */
class CastVerdictTest {
    /** A first Chromecast: H.264 up to 1080p, nothing else. */
    private val chromecast = CastModel.Chromecast.profile(null)

    /** A device by a name no row has (a TV with Google Cast built in): H.264 for sure, HEVC, VP9 and AV1 not held back. */
    private val other = CastModel.Other.profile(null)
    private val prefs = CastVerdict.Prefs(audioLanguages = listOf("en"), subtitlesWhenForeign = true)

    private val h264 = ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1920, height = 1080, bitDepth = 8)
    private fun audio(mime: String, label: String, language: String? = "en", channels: Int = 6) = ProbedTrack(mime, label, language, channels = channels)
    private val dtsHd = audio(MimeTypes.AUDIO_DTS_HD, "DTS-HD", channels = 8)
    private val trueHd = audio(MimeTypes.AUDIO_TRUEHD, "TrueHD", channels = 8)
    private val dts = audio(MimeTypes.AUDIO_DTS, "DTS")
    private val ac3 = audio(MimeTypes.AUDIO_AC3, "AC-3")
    private val englishSubs = ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "en")

    private fun tracks(video: List<ProbedTrack> = listOf(h264), audio: List<ProbedTrack> = listOf(ac3), text: List<ProbedTrack> = emptyList()) =
        ProbedTracks(video, audio, text)

    private fun judge(tracks: ProbedTracks, profile: CastProfile = other, prefs: CastVerdict.Prefs = this.prefs) = CastVerdict.judge(tracks, profile, prefs)

    private fun miss(tracks: ProbedTracks, profile: CastProfile = other) = judge(tracks, profile).miss

    @Test
    fun `a file whose only sound is DTS or TrueHD would play silent`() {
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), miss(tracks(audio = listOf(dtsHd))))
        assertEquals(ProbeResult.NoCastAudio(listOf("TrueHD")), miss(tracks(audio = listOf(trueHd))))
        // Both, each named once, in the file's order.
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS", "TrueHD")), miss(tracks(audio = listOf(dts, trueHd, dts))))
        // Every device alike: what a device has shown never changes its sound (W41).
        val shown = CastModel.Shield.profile(CastDeviceMemory("den", reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc", tenBit = true)), dolbyVision = true))
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), miss(tracks(audio = listOf(dtsHd)), shown))
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), miss(tracks(audio = listOf(dtsHd)), chromecast))
    }

    @Test
    fun `the first sound track it plays is the one it plays, whatever comes before it`() {
        // TrueHD first, AC-3 second: the device plays the AC-3.
        assertNull(miss(tracks(audio = listOf(trueHd, ac3))))
        // AC-3 and E-AC-3 are fine everywhere, beside AAC, Opus, MP3, FLAC and PCM.
        listOf(
            ac3, audio(MimeTypes.AUDIO_E_AC3, "E-AC-3"), audio(MimeTypes.AUDIO_E_AC3_JOC, "E-AC-3 Atmos"), audio(MimeTypes.AUDIO_AAC, "AAC", channels = 2),
            audio(MimeTypes.AUDIO_OPUS, "Opus"), audio(MimeTypes.AUDIO_MPEG, "MP3", channels = 2), audio(MimeTypes.AUDIO_FLAC, "FLAC"), audio(MimeTypes.AUDIO_RAW, "PCM"),
        ).forEach { assertNull(miss(tracks(audio = listOf(dtsHd, it)), chromecast), it.label) }
        // Codecs receivers map though no release name says them (Vorbis, ALAC, A-law) play as the others do.
        for (mime in listOf(MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_ALAW)) assertNull(miss(tracks(audio = listOf(audio(mime, mime)))), mime)
        // One they can't map (CFR-3: MP2, from a DVB capture; a track of no known type) never becomes a stream: skipped, as DTS is.
        val mp2 = audio(MimeTypes.AUDIO_MPEG_L2, "MP2", channels = 2)
        assertEquals(ProbeResult.NoCastAudio(listOf("MP2")), miss(tracks(audio = listOf(mp2))))
        assertEquals(ProbeResult.NoCastAudio(listOf("unknown")), miss(tracks(audio = listOf(ProbedTrack(null, "unknown", "en")))))
        assertNull(miss(tracks(audio = listOf(mp2, ac3))))
    }

    @Test
    fun `a file with no audio, or no video, has nothing the device can't play`() {
        assertNull(miss(tracks(audio = emptyList())))
        assertNull(miss(tracks(video = emptyList())))
        assertNull(miss(ProbedTracks()))
    }

    @Test
    fun `video no Cast device shows is no picture, checked before the sound`() {
        val vc1 = ProbedTrack(MimeTypes.VIDEO_VC1, "VC-1", width = 1920, height = 1080)
        assertEquals(ProbeResult.NoCastVideo("VC-1"), miss(tracks(video = listOf(vc1))))
        assertEquals(ProbeResult.NoCastVideo("MPEG-2"), miss(tracks(video = listOf(ProbedTrack(MimeTypes.VIDEO_MPEG2, "MPEG-2", width = 720, height = 576)))))
        assertEquals(ProbeResult.NoCastVideo("MPEG-4"), miss(tracks(video = listOf(ProbedTrack(MimeTypes.VIDEO_MP4V, "MPEG-4", width = 720, height = 576)))))
        assertEquals(ProbeResult.NoCastVideo("H.264 10-bit"), miss(tracks(video = listOf(h264.copy(bitDepth = 10)))))
        // Without a picture the sound doesn't matter.
        assertEquals(ProbeResult.NoCastVideo("VC-1"), miss(tracks(video = listOf(vc1), audio = listOf(dtsHd))))
        // The device plays the first video track alone.
        assertEquals(ProbeResult.NoCastVideo("VC-1"), miss(tracks(video = listOf(vc1, h264))))
        assertNull(miss(tracks(video = listOf(h264, vc1))))
        // A codec the verdict has no word on isn't held against the file.
        assertNull(miss(tracks(video = listOf(ProbedTrack(MimeTypes.VIDEO_VP8, "VP8", width = 1920, height = 1080)))))
    }

    @Test
    fun `HEVC on a first Chromecast is no picture, and plays on a device whose model shows it`() {
        val hevc = ProbedTrack(MimeTypes.VIDEO_H265, "HEVC", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.Hdr10)
        assertEquals(ProbeResult.NoCastVideo("HEVC 10-bit"), miss(tracks(video = listOf(hevc)), chromecast))
        assertEquals(ProbeResult.NoCastVideo("HEVC"), miss(tracks(video = listOf(hevc.copy(bitDepth = 8, hdr = null))), chromecast))
        assertNull(miss(tracks(video = listOf(hevc)), other))
        assertNull(miss(tracks(video = listOf(hevc)), CastModel.Shield.profile(null)))
        // Dolby Vision plays on its base, profile 8's HEVC; its name says its profile.
        val dv8 = ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 8)
        assertEquals(ProbeResult.NoCastVideo("Dolby Vision profile 8"), miss(tracks(video = listOf(dv8)), chromecast))
        assertNull(miss(tracks(video = listOf(dv8)), other))
        // A device that played no picture of HEVC at 10 bits shows none there any more (L128).
        val dark = CastModel.Other.profile(CastDeviceMemory("hall", reportsVideo = true, noPicture = listOf(CastVideoCodec("Hevc", tenBit = true))))
        assertEquals(ProbeResult.NoCastVideo("HEVC 10-bit"), miss(tracks(video = listOf(hevc)), dark))
    }

    @Test
    fun `4K on a model that shows HD is too big, and plays on a device not known to stop at HD`() {
        val uhd = h264.copy(width = 3840, height = 2160)
        assertEquals(ProbeResult.TooBigForCast(2160, 1080), miss(tracks(video = listOf(uhd)), chromecast))
        // A film cropped to 2.40:1 is still 4K, as release names have it.
        assertEquals(ProbeResult.TooBigForCast(2160, 1080), miss(tracks(video = listOf(uhd.copy(height = 1600))), chromecast))
        // An unknown device aims at 1080p in the ranking (W53), but nothing says it won't play 4K.
        assertNull(miss(tracks(video = listOf(uhd)), other))
        // Once it has shown 4K of it, a first Chromecast's row is lifted too.
        val shown = CastModel.Chromecast.profile(CastDeviceMemory("kitchen", reportsVideo = true, shown2160 = listOf(CastVideoCodec("H264"))))
        assertNull(miss(tracks(video = listOf(uhd)), shown))
        // Of no known size, nothing is held against it.
        assertNull(miss(tracks(video = listOf(uhd.copy(width = null, height = null))), chromecast))
        // 1080p fits.
        assertNull(miss(tracks(), chromecast))
    }

    @Test
    fun `the language it plays is its first sound track's, said when it isn't the first one wanted`() {
        val germanFirst = tracks(audio = listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "de"), audio(MimeTypes.AUDIO_AC3, "AC-3", "en")))
        assertEquals("de", judge(germanFirst).otherLanguage)
        assertNull(judge(germanFirst, prefs = prefs.copy(audioLanguages = listOf("de", "en"))).otherLanguage)
        // English TrueHD first, German AC-3 second: the German plays.
        assertEquals("de", judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_TRUEHD, "TrueHD", "en"), audio(MimeTypes.AUDIO_AC3, "AC-3", "de")))).otherLanguage)
        // A Czech MP2 track first, which receivers skip, then English AC-3: the English plays (CFR-3).
        assertNull(judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_MPEG_L2, "MP2", "cs"), audio(MimeTypes.AUDIO_AC3, "AC-3", "en")))).otherLanguage)
        // By its main language, however the file and the settings write it.
        assertNull(judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "he"))), prefs = prefs.copy(audioLanguages = listOf("iw"))).otherLanguage)
        assertNull(judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "en-us")))).otherLanguage)
        // A track of no stated language, no first wanted, or nothing the device plays: nothing to say.
        assertNull(judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", null)))).otherLanguage)
        assertNull(judge(germanFirst, prefs = prefs.copy(audioLanguages = emptyList())).otherLanguage)
        assertNull(judge(tracks(audio = listOf(audio(MimeTypes.AUDIO_DTS_HD, "DTS-HD", "de")))).otherLanguage)
    }

    @Test
    fun `subtitles go missing where this phone would show them, by its rule and the show's memory`() {
        val german = listOf(audio(MimeTypes.AUDIO_AC3, "AC-3", "de"))
        // Subtitles when audio isn't your first language, with the audio the device plays.
        assertTrue(judge(tracks(audio = german, text = listOf(englishSubs))).subtitles)
        assertFalse(judge(tracks(audio = german, text = listOf(englishSubs)), prefs = prefs.copy(subtitlesWhenForeign = false)).subtitles)
        assertFalse(judge(tracks(audio = listOf(ac3), text = listOf(englishSubs))).subtitles)
        assertFalse(judge(tracks(audio = german, text = listOf(englishSubs.copy(language = "fr")))).subtitles)
        assertFalse(judge(tracks(audio = german)).subtitles)
        // English TrueHD first, German AC-3 second: the device plays the German, so the rule shows English subtitles.
        assertTrue(judge(tracks(audio = listOf(trueHd, german.single()), text = listOf(englishSubs))).subtitles)
        // The show's memory wins over the rule: on in a language the file has...
        assertTrue(judge(tracks(text = listOf(englishSubs)), prefs = prefs.copy(show = SubtitleChoice.Language("en", sdh = false))).subtitles)
        // ... off, or forced only, whatever the rule says...
        assertFalse(judge(tracks(audio = german, text = listOf(englishSubs)), prefs = prefs.copy(show = SubtitleChoice.Off)).subtitles)
        assertFalse(judge(tracks(audio = german, text = listOf(englishSubs)), prefs = prefs.copy(show = SubtitleChoice.ForcedOnly)).subtitles)
        // ... and in a language the file lacks, the rule decides, as the player goes on to it.
        assertTrue(judge(tracks(audio = german, text = listOf(englishSubs)), prefs = prefs.copy(show = SubtitleChoice.Language("fr", sdh = false))).subtitles)
        // A forced track in the audio's language shows as the player shows it: a film's foreign lines.
        val forcedGerman = ProbedTrack(MimeTypes.APPLICATION_SUBRIP, "SRT", "de", forced = true)
        assertTrue(judge(tracks(audio = german, text = listOf(forcedGerman)), prefs = prefs.copy(subtitlesWhenForeign = false)).subtitles)
        assertTrue(judge(tracks(audio = german, text = listOf(forcedGerman)), prefs = prefs.copy(show = SubtitleChoice.ForcedOnly)).subtitles)
        assertFalse(judge(tracks(audio = german, text = listOf(forcedGerman)), prefs = prefs.copy(show = SubtitleChoice.Off)).subtitles)
    }
}

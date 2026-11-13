package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** [SubtitleRule.withSubtitles] against ExoPlayer's track selector, which needs the framework's TextUtils. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SubtitleTrackSelectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val englishAudio = format("audio", MimeTypes.AUDIO_AAC, "en", C.SELECTION_FLAG_DEFAULT)
    private val englishForced = format("forced", MimeTypes.APPLICATION_SUBRIP, "en", C.SELECTION_FLAG_FORCED)
    private val englishFull = format("full", MimeTypes.APPLICATION_SUBRIP, "en", C.SELECTION_FLAG_DEFAULT)
    private val german = format("german", MimeTypes.APPLICATION_SUBRIP, "de", 0)

    @Test
    fun noSubtitlesStillShowsForcedSubtitlesForTheAudio() {
        assertEquals("forced", selectedSubtitles(null, englishAudio, englishForced, englishFull, german))
    }

    @Test
    fun noSubtitlesSkipsADefaultFlaggedFullTrack() {
        assertNull(selectedSubtitles(null, englishAudio, englishFull, german))
    }

    @Test
    fun aChosenLanguageGetsItsTrack() {
        assertEquals("german", selectedSubtitles("de", englishAudio, englishForced, englishFull, german))
    }

    // Media3 tags Indonesian ms-ind and Croatian hbs-hrv: the rule must not hand the selector just "ms" or "hbs".
    @Test
    fun anIndonesianViewerGetsIndonesianRatherThanMalaySubtitles() {
        val malay = format("malay", MimeTypes.APPLICATION_SUBRIP, "msa", 0)
        val indonesian = format("indonesian", MimeTypes.APPLICATION_SUBRIP, "ind", 0)
        assertEquals("indonesian", subtitlesByRule(listOf("in", "en"), englishAudio, malay, indonesian))
    }

    @Test
    fun aCroatianViewerGetsCroatianRatherThanSerbianSubtitles() {
        val serbian = format("serbian", MimeTypes.APPLICATION_SUBRIP, "srp", 0)
        val croatian = format("croatian", MimeTypes.APPLICATION_SUBRIP, "hrv", 0)
        assertEquals("croatian", subtitlesByRule(listOf("hr", "en"), englishAudio, serbian, croatian))
    }

    // The full code still reaches a track tagged with the bare macrolanguage, as most Norwegian ones are.
    @Test
    fun aBokmalViewerStillGetsAPlainNorwegianTrack() {
        val norwegian = format("norwegian", MimeTypes.APPLICATION_SUBRIP, "nor", 0)
        assertEquals("norwegian", subtitlesByRule(listOf("nb", "en"), englishAudio, englishFull, norwegian))
    }

    // Many Matroska files name a forced track so ("English (Forced)") without its flag, the only
    // thing the selector goes by: forced only pins it (TrackSelection) for the audio playing.
    @Test
    fun forcedOnlyShowsATrackForcedByItsNameOnly() {
        val named = format("named", MimeTypes.APPLICATION_SUBRIP, "en", 0, label = "English (Forced)")
        assertEquals("named", selectedByPlan(englishAudio, englishAudio, englishFull, named) { p, t -> TrackSelection.withSubtitles(p, t, SubtitlePlan.ForcedOnly) })
    }

    @Test
    fun forcedOnlyFollowsTheAudioPicked() {
        val germanAudio = format("german audio", MimeTypes.AUDIO_AAC, "de", 0)
        val englishNamed = format("english named", MimeTypes.APPLICATION_SUBRIP, "en", 0, label = "English (Forced)")
        val germanNamed = format("german named", MimeTypes.APPLICATION_SUBRIP, "de", 0, label = "Deutsch (Forced)")
        val selected = selectedByPlan(englishAudio, englishAudio, germanAudio, englishFull, englishNamed, germanNamed) { p, t ->
            val forcedOnly = TrackSelection.withSubtitles(p, t, SubtitlePlan.ForcedOnly)
            TrackSelection.withAudio(forcedOnly, t, TrackOptions.of(t).single { it.key.id == "german audio" }, listOf("en"))
        }
        assertEquals("german named", selected)
    }

    /**
     * The subtitle track the selector picks under the parameters [plan] makes from the player's
     * tracks, which report [playing] as the audio selected; null for none.
     */
    private fun selectedByPlan(playing: Format, vararg formats: Format, plan: (TrackSelectionParameters, Tracks) -> TrackSelectionParameters): String? {
        val groups = formats.map { TrackGroup(it) }
        val tracks = Tracks(groups.map { g -> Tracks.Group(g, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(g.getFormat(0) === playing)) })
        return select(groups) { plan(DefaultTrackSelector.Parameters.getDefaults(context), tracks) }
    }

    /** The subtitle track picked once [SubtitleRule.choose] ran on the tracks' languages, as PlayerActivity does. */
    private fun subtitlesByRule(preferred: List<String>, audio: Format, vararg subtitles: Format): String? {
        val language = SubtitleRule.choose(true, audio.language, preferred, subtitles.mapNotNull { it.language })
        return selectedSubtitles(language, audio, *subtitles)
    }

    /** The id of the subtitle track the selector picks once the rule chose [language], or null for none. */
    private fun selectedSubtitles(language: String?, vararg formats: Format): String? =
        select(formats.map { TrackGroup(it) }) { SubtitleRule.withSubtitles(DefaultTrackSelector.Parameters.getDefaults(context), language) }

    /** The id of the subtitle track the selector picks from [groups] under [parameters], or null for none. */
    private fun select(groups: List<TrackGroup>, parameters: () -> TrackSelectionParameters): String? {
        var selected: String? = null
        // The selector wants a looper, as on the player's playback thread.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val selector = DefaultTrackSelector(context, parameters())
            selector.init({}, DefaultBandwidthMeter.getSingletonInstance(context))
            val result = selector.selectTracks(
                arrayOf(renderer(C.TRACK_TYPE_AUDIO), renderer(C.TRACK_TYPE_TEXT)),
                TrackGroupArray(*groups.toTypedArray()),
                MediaSource.MediaPeriodId(Any()),
                Timeline.EMPTY,
            )
            selected = result.selections[1]?.selectedFormat?.id?.takeIf { result.isRendererEnabled(1) }
            selector.release()
        }
        return selected
    }

    private fun renderer(type: Int) = object : RendererCapabilities {
        override fun getName() = "fake"
        override fun getTrackType() = type
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.getTrackType(format.sampleMimeType) == type) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private fun format(id: String, mimeType: String, language: String, flags: Int, label: String? = null) =
        Format.Builder().setId(id).setSampleMimeType(mimeType).setLanguage(language).setSelectionFlags(flags).setLabel(label).build()
}

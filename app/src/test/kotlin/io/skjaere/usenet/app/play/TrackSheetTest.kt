package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackOptions
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

class TrackSheetTest {
    private fun audio(id: String, language: String, mime: String = MimeTypes.AUDIO_AAC, channels: Int = 2, label: String? = null) =
        Format.Builder().setId(id).setSampleMimeType(mime).setLanguage(language).setChannelCount(channels).setLabel(label).build()

    private fun text(id: String, language: String, label: String? = null, flags: Int = 0) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language).setLabel(label).setSelectionFlags(flags).build()

    private fun options(formats: List<Format>, selected: Set<String>, unsupported: Set<String> = emptySet()) = TrackOptions.of(
        Tracks(
            formats.mapIndexed { i, f ->
                Tracks.Group(TrackGroup("0:$i", f), false, intArrayOf(if (f.id in unsupported) C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED), booleanArrayOf(f.id in selected))
            },
        ),
        Locale.ENGLISH,
    )

    private val fixture = listOf(
        audio("1", "en"), audio("2", "de", channels = 6), audio("3", "en", label = "Commentary"), audio("4", "fr", MimeTypes.AUDIO_E_AC3, 6),
        text("5", "en"), text("6", "en", "SDH"), text("7", "de", flags = C.SELECTION_FLAG_FORCED), text("8", "de"),
    )

    private fun rows(sheet: TrackSheet) = sheet.sections.map { s -> s.title to s.rows.map { r -> listOfNotNull(r.title, r.details, "current".takeIf { r.current }, "disabled".takeIf { !r.enabled }).joinToString(" | ") } }

    @Test
    fun `audio, then subtitles with off and forced only, the current choices marked`() {
        val options = options(fixture, selected = setOf("2", "8"), unsupported = setOf("4"))
        val sheet = TrackSheet(options, SubtitlePlan.Track(options.single { it.key.id == "8" }), canReset = false)
        assertEquals(
            listOf(
                "Audio" to listOf(
                    "English · Stereo · AAC",
                    "German · 5.1 · AAC | current",
                    "English · Stereo · AAC | Commentary",
                    "French · 5.1 · E-AC-3 | Can't play on this device | disabled",
                ),
                "Subtitles" to listOf(
                    "Off",
                    "Forced only | Only parts in another language",
                    "English · SRT",
                    "English · SRT | SDH",
                    "German · SRT | Forced",
                    "German · SRT | current",
                ),
            ),
            rows(sheet),
        )
        assertEquals(TrackSheet.Action.Audio(options[1]), sheet.sections[0].rows[1].action)
        assertEquals(TrackSheet.Action.Subtitles(SubtitlePlan.ForcedOnly), sheet.sections[1].rows[1].action)
        assertEquals(sheet.sections[0].rows[1], sheet.focusFirst)
    }

    @Test
    fun `forced only is offered only when the file has a forced track`() {
        val options = options(listOf(audio("1", "en"), text("5", "en")), selected = setOf("1"))
        assertEquals(listOf("Off | current", "English · SRT"), rows(TrackSheet(options, SubtitlePlan.Off, canReset = false))[1].second)
        val forced = options(fixture, selected = setOf("1"))
        assertEquals("Forced only | Only parts in another language | current", rows(TrackSheet(forced, SubtitlePlan.ForcedOnly, canReset = false))[1].second[1])
    }

    /** Forced only in a file without a forced track shows nothing: the sheet marks Off, the only row saying so. */
    @Test
    fun `forced only in a file without a forced track is marked as off`() {
        val options = options(listOf(audio("1", "en"), text("5", "en")), selected = setOf("1"))
        assertEquals(listOf("Off | current", "English · SRT"), rows(TrackSheet(options, SubtitlePlan.ForcedOnly, canReset = false))[1].second)
    }

    @Test
    fun `a show with a remembered choice can go back to the defaults`() {
        val options = options(fixture, selected = setOf("1"))
        val sheet = TrackSheet(options, SubtitlePlan.ForcedOnly, canReset = true)
        assertEquals("" to listOf("Reset to my defaults | Forget this show's audio and subtitles"), rows(sheet).last())
        assertEquals(TrackSheet.Action.Reset, sheet.sections.last().rows.single().action)
    }

    /** Before the player knows the file's tracks: nothing to pick, nothing current, no Reset. */
    @Test
    fun `while the tracks are loading the sheet lists nothing`() {
        val sheet = TrackSheet(emptyList(), SubtitlePlan.ForcedOnly, canReset = true, loading = true)
        assertEquals(emptyList(), sheet.sections)
        assertEquals(null, sheet.focusFirst)
    }

    @Test
    fun `with no audio playing the first row takes the focus`() {
        val options = options(fixture, selected = emptySet())
        val sheet = TrackSheet(options, SubtitlePlan.Off, canReset = false)
        assertEquals(sheet.sections[0].rows[0], sheet.focusFirst)
    }
}

package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SubtitleRuleTest {
    private val german = listOf("de", "en")

    @Test
    fun `english audio for a german viewer gets german subtitles`() =
        assertEquals("de", SubtitleRule.choose(true, "en", german, setOf("en", "de")))

    @Test
    fun `audio in the first language needs no subtitles`() =
        assertNull(SubtitleRule.choose(true, "de", german, setOf("de", "en")))

    @Test
    fun `no subtitles in the first language means none`() =
        assertNull(SubtitleRule.choose(true, "en", german, setOf("en", "fr")))

    @Test
    fun `rule off or audio language unknown means none`() {
        assertNull(SubtitleRule.choose(false, "en", german, setOf("de")))
        assertNull(SubtitleRule.choose(true, "und", german, setOf("de")))
        assertNull(SubtitleRule.choose(true, null, german, setOf("de")))
    }

    // Android reports nb, in and iw; Media3 tags the tracks of those languages no-nob, ms-ind and he.
    @Test
    fun `preferred codes match tracks under media3's normalized codes`() {
        assertEquals("no-nob", SubtitleRule.choose(true, "en", listOf("nb", "en"), setOf("no")))
        assertEquals("no-nob", SubtitleRule.choose(true, "en", listOf("nb", "en"), setOf("no-nob")))
        assertEquals("ms-ind", SubtitleRule.choose(true, "en", listOf("in", "en"), setOf("ms-ind")))
        assertEquals("ms-ind", SubtitleRule.choose(true, "en", listOf("id", "en"), setOf("ind")))
        assertEquals("he", SubtitleRule.choose(true, "en", listOf("iw", "en"), setOf("he")))
    }

    // The track selector scores an exact code above a sibling of the same macrolanguage (Malay, Serbian).
    @Test
    fun `the chosen code keeps the viewer's own variant of a macrolanguage`() {
        assertEquals("ms-ind", SubtitleRule.choose(true, "en", listOf("in", "en"), setOf("ms", "ms-ind")))
        assertEquals("hbs-hrv", SubtitleRule.choose(true, "en", listOf("hr", "en"), setOf("hbs-srp", "hbs-hrv")))
    }

    @Test
    fun `audio in the first language under another code needs no subtitles`() {
        assertNull(SubtitleRule.choose(true, "no-nob", listOf("nb", "en"), setOf("no", "en")))
        assertNull(SubtitleRule.choose(true, "heb", listOf("iw", "en"), setOf("he", "en")))
        assertNull(SubtitleRule.choose(true, "ms-ind", listOf("in", "en"), setOf("ms", "en")))
    }

    private val defaults = TrackSelectionParameters.Builder().build()

    // Forced tracks carry foreign dialogue for audio in their own language: the player picks them by itself.
    @Test
    fun `no subtitles still lets forced subtitles for the audio show`() {
        val parameters = SubtitleRule.withSubtitles(defaults, null)
        assertFalse(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes, "text tracks disabled, forced ones with them")
        assertEquals(0, parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_FORCED, "forced flag ignored")
        assertEquals(C.SELECTION_FLAG_DEFAULT, parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT, "default-flagged full tracks still picked")
        assertEquals(emptyList(), parameters.preferredTextLanguages)
    }

    @Test
    fun `a chosen language is preferred and counts default flags again`() {
        val parameters = SubtitleRule.withSubtitles(SubtitleRule.withSubtitles(defaults, null), "de")
        assertFalse(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        assertEquals(listOf("de"), parameters.preferredTextLanguages)
        assertEquals(0, parameters.ignoredTextSelectionFlags)
    }
}

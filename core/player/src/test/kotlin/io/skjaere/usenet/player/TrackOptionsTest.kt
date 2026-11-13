package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.skjaere.usenet.player.TrackFixtures.audio
import io.skjaere.usenet.player.TrackFixtures.parsedWhileExtracting
import io.skjaere.usenet.player.TrackFixtures.text
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackOptionsTest {
    private val english = Locale.ENGLISH

    private fun option(format: Format, locale: Locale = english) = TrackOptions.of(TrackFixtures.tracks(listOf(format)), locale).single()

    @Test
    fun `audio labels name the language, the channels and the codec`() {
        assertEquals("English · Stereo · AAC", option(audio("1", "en")).label)
        assertEquals("German · 5.1 · E-AC-3", option(audio("1", "de", MimeTypes.AUDIO_E_AC3, channels = 6)).label)
        assertEquals("French · 7.1 · TrueHD", option(audio("1", "fr", MimeTypes.AUDIO_TRUEHD, channels = 8)).label)
        assertEquals("Japanese · Mono · MP3", option(audio("1", "ja", MimeTypes.AUDIO_MPEG, channels = 1)).label)
    }

    @Test
    fun `every codec has its name`() {
        val names = mapOf(
            MimeTypes.AUDIO_AAC to "AAC", MimeTypes.AUDIO_AC3 to "AC-3", MimeTypes.AUDIO_E_AC3 to "E-AC-3",
            MimeTypes.AUDIO_E_AC3_JOC to "E-AC-3 Atmos", MimeTypes.AUDIO_DTS to "DTS", MimeTypes.AUDIO_DTS_HD to "DTS-HD",
            MimeTypes.AUDIO_DTS_EXPRESS to "DTS-HD", MimeTypes.AUDIO_DTS_X to "DTS:X", MimeTypes.AUDIO_TRUEHD to "TrueHD",
            MimeTypes.AUDIO_FLAC to "FLAC", MimeTypes.AUDIO_OPUS to "Opus", MimeTypes.AUDIO_MPEG to "MP3", MimeTypes.AUDIO_RAW to "PCM",
            MimeTypes.AUDIO_VORBIS to "Vorbis",
        )
        names.forEach { (mime, name) -> assertEquals("English · Stereo · $name", option(audio("1", "en", mime)).label, mime) }
    }

    @Test
    fun `channel layouts from the channel count`() {
        assertEquals(
            listOf("Mono", "Stereo", "5.1", "7.1", "3 channels"),
            listOf(1, 2, 6, 8, 3).map { option(audio("1", "en", channels = it)).label.split(" · ")[1] },
        )
        assertEquals("English · AAC", option(audio("1", "en", channels = Format.NO_VALUE)).label)
    }

    @Test
    fun `a missing or undetermined language is unknown`() {
        assertEquals("Unknown language · Stereo · AAC", option(audio("1", null)).label)
        assertEquals("Unknown language · Stereo · AAC", option(audio("1", "und")).label)
        assertEquals("Unknown language · SRT", option(text("1", "und")).label)
        assertNull(option(audio("1", "und")).language)
    }

    @Test
    fun `language names are in the device's language`() {
        val german = Locale.GERMAN
        assertEquals("Englisch · Stereo · AAC", option(audio("1", "en"), german).label)
        assertEquals("Deutsch · 5.1 · AAC", option(audio("1", "ger", channels = 6), german).label)
        assertEquals("Französisch · 5.1 · E-AC-3", option(audio("1", "fre", MimeTypes.AUDIO_E_AC3, channels = 6), german).label)
        assertEquals("Deutsch · SRT", option(text("1", "deu"), german).label)
        // The app's own words stay English.
        assertEquals("Unknown language · SRT", option(text("1", null), german).label)
        // Lower-case language names (French) start a label capitalised.
        assertEquals("Anglais · Stereo · AAC", option(audio("1", "en"), Locale.FRENCH).label)
    }

    // Media3 tags Bokmål no-nob and Indonesian ms-ind; the label names the language, not the macrolanguage.
    @Test
    fun `media3's macrolanguage codes are named by their own language`() {
        assertEquals("Norwegian Bokmål · SRT", option(text("1", "nob")).label)
        assertEquals("Indonesian · SRT", option(text("1", "ind")).label)
        assertEquals("English (United States) · SRT", option(text("1", "en-US")).label)
    }

    @Test
    fun `subtitle labels name the format`() {
        val formats = mapOf(
            MimeTypes.APPLICATION_SUBRIP to "SRT", MimeTypes.TEXT_SSA to "SSA/ASS", MimeTypes.APPLICATION_PGS to "PGS",
            MimeTypes.APPLICATION_VOBSUB to "VobSub", MimeTypes.TEXT_VTT to "WebVTT", MimeTypes.APPLICATION_TTML to "TTML",
        )
        formats.forEach { (mime, name) -> assertEquals("English · $name", option(text("1", "en", mime)).label, mime) }
        // Parsed while extracting, Media3's default (the track probe's): the track's own format is in codecs.
        formats.forEach { (mime, name) -> assertEquals("English · $name", option(parsedWhileExtracting(text("1", "en", mime))).label, mime) }
        assertEquals("English", option(text("1", "en", MimeTypes.APPLICATION_CEA608)).label)
    }

    // A play's player parses subtitles while extracting but for PGS and VobSub (PlaySubtitleParserFactory), and players
    // of earlier versions parsed all of them, or none. A choice kept from any of them finds its track again by the key.
    @Test
    fun `a subtitle track is the same option whether parsed while extracting or not`() {
        listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB).forEach { mime ->
            val own = text("5", "en", mime, label = "English SDH", selection = C.SELECTION_FLAG_FORCED, roles = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
            assertEquals(option(own), option(parsedWhileExtracting(own)), mime)
            assertEquals(TrackKey("5", mime, Format.NO_VALUE, "English SDH"), option(own).key)
        }
    }

    @Test
    fun `tags come from the file's flags`() {
        assertEquals(setOf(TrackTag.Commentary), option(audio("1", "en", roles = C.ROLE_FLAG_COMMENTARY)).tags)
        assertEquals(setOf(TrackTag.AudioDescription), option(audio("1", "en", roles = C.ROLE_FLAG_DESCRIBES_VIDEO)).tags)
        assertEquals(setOf(TrackTag.Forced), option(text("1", "en", selection = C.SELECTION_FLAG_FORCED)).tags)
        assertEquals(setOf(TrackTag.Sdh), option(text("1", "en", roles = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)).tags)
        assertEquals(emptySet(), option(text("1", "en", selection = C.SELECTION_FLAG_DEFAULT)).tags)
    }

    @Test
    fun `a track flagged original says so, as the other tags do`() {
        val original = option(audio("1", "ja", roles = C.ROLE_FLAG_MAIN))
        assertEquals(listOf("Original"), original.tags.map { it.label })
        assertEquals("Original", original.details)
        assertEquals("Japanese · Stereo · AAC · Original", original.summary)
        // Flags combine, with each other and with the name's.
        assertEquals(listOf("Commentary", "Original"), option(audio("1", "ja", label = "Commentary", roles = C.ROLE_FLAG_MAIN)).tags.map { it.label })
        // The name says no more than the tag.
        assertNull(option(audio("1", "ja", label = "Japanese (Original)", roles = C.ROLE_FLAG_MAIN)).name)
        // Only the flag says it: a track merely named so is left as it is.
        assertEquals("Original", option(audio("1", "ja", label = "Original")).details)
        assertEquals(emptySet(), option(audio("1", "ja", label = "Original")).tags)
    }

    @Test
    fun `tags come from the track's name`() {
        assertEquals(setOf(TrackTag.Commentary), option(audio("1", "en", label = "Commentary")).tags)
        assertEquals(setOf(TrackTag.Commentary), option(audio("1", "de", label = "Audiokommentar")).tags)
        assertEquals(setOf(TrackTag.Commentary), option(audio("1", "en", label = "Director's commentary")).tags)
        assertEquals(setOf(TrackTag.Forced), option(text("1", "en", label = "English (Forced)")).tags)
        assertEquals(setOf(TrackTag.Sdh), option(text("1", "en", label = "SDH")).tags)
        assertEquals(setOf(TrackTag.Sdh), option(text("1", "en", label = "English [CC]")).tags)
        assertEquals(setOf(TrackTag.Sdh), option(text("1", "en", label = "For the hearing impaired")).tags)
        assertEquals(setOf(TrackTag.Sdh, TrackTag.Forced), option(text("1", "en", label = "Forced SDH")).tags)
        // Not words of their own.
        assertEquals(emptySet(), option(text("1", "en", label = "Accented")).tags)
        assertEquals(emptySet(), option(text("1", "en", label = "Sdhx")).tags)
    }

    @Test
    fun `the track's own name shows only when it says something the label and tags don't`() {
        assertNull(option(audio("1", "en", label = "Commentary")).name)
        assertNull(option(text("1", "en", label = "SDH")).name)
        assertNull(option(text("1", "en", label = "English SDH")).name)
        assertNull(option(audio("1", "de", channels = 6, label = "Deutsch 5.1")).name)
        assertNull(option(audio("1", "en", MimeTypes.AUDIO_E_AC3, label = "English E-AC3 Stereo")).name)
        assertNull(option(text("1", "de", label = "Full")).name)
        assertEquals("Director's commentary", option(audio("1", "en", label = "Director's commentary")).name)
        assertEquals("Signs & songs", option(text("1", "en", label = "Signs & songs")).name)
        assertEquals("Commentary", option(audio("1", "en", label = "Commentary")).details)
        assertEquals("Director's commentary", option(audio("1", "en", label = "Director's commentary")).details)
    }

    @Test
    fun `tracks the device can't play are listed but can't be picked`() {
        val tracks = TrackFixtures.fixtureTracks()
        val options = TrackOptions.of(tracks, english)
        val french = options.single { it.key.id == "4" }
        assertEquals("French · 5.1 · E-AC-3", french.label)
        assertFalse(french.playable)
        assertFalse(french.selectable)
        assertEquals("Can't play on this device", french.reason)
        assertEquals("Can't play on this device", french.details)
        assertTrue(options.filter { it.key.id != "4" }.all { it.playable && it.selectable && it.reason == null })
        // The hook for Pro: nothing is locked in this version.
        assertTrue(options.none { it.locked })
    }

    @Test
    fun `a locked track can't be picked and says so`() {
        val options = TrackOptions.of(TrackFixtures.fixtureTracks(), english) { it.sampleMimeType == MimeTypes.AUDIO_AAC && it.channelCount == 6 }
        val german = options.single { it.key.id == "2" }
        assertTrue(german.locked)
        assertFalse(german.selectable)
        assertEquals("Needs Pro", german.reason)
    }

    @Test
    fun `the file's audio and subtitle tracks in order, video left out`() {
        val options = TrackOptions.of(TrackFixtures.fixtureTracks(), english)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), options.map { it.key.id })
        assertEquals(List(4) { C.TRACK_TYPE_AUDIO } + List(4) { C.TRACK_TYPE_TEXT }, options.map { it.type })
        assertEquals((1..8).toList(), options.map { it.groupIndex })
        assertEquals(listOf("3"), options.filter { it.selected }.map { it.key.id })
        assertEquals(
            listOf(
                "English · Stereo · AAC", "German · 5.1 · AAC", "English · Stereo · AAC", "French · 5.1 · E-AC-3",
                "English · SRT", "English · SRT", "German · SRT", "German · SRT",
            ),
            options.map { it.label },
        )
        assertEquals(
            listOf(null, null, "Commentary", "Can't play on this device", null, "SDH", "Forced", null),
            options.map { it.details },
        )
        assertTrue(options.single { it.key.id == "3" }.isDefault)
    }
}

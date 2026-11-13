package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import io.skjaere.usenet.player.TrackFixtures.audio
import io.skjaere.usenet.player.TrackFixtures.text
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackPlannerTest {
    private val options = TrackOptions.of(TrackFixtures.fixtureTracks(), Locale.ENGLISH)
    private fun id(id: String) = options.single { it.key.id == id }
    private val englishStereo = id("1")
    private val german51 = id("2")
    private val commentary = id("3")
    private val englishSubs = id("5")
    private val englishSdh = id("6")
    private val germanSubs = id("8")
    private val englishFirst = listOf("en", "de")

    private fun plan(sheet: TrackChoice = TrackChoice(), show: TrackChoice? = null, preferred: List<String> = englishFirst, rule: Boolean = true, from: List<TrackOption> = options) =
        TrackPlanner.plan(from, sheet, show, preferred, rule)

    @Test
    fun `with nothing chosen the preferences pick the audio and the rule the subtitles`() {
        val plan = plan()
        assertEquals(englishStereo, plan.audio)
        assertEquals(ChoiceSource.Preferences, plan.audioSource)
        // English audio for an English-first viewer: no full subtitles.
        assertEquals(SubtitlePlan.ForcedOnly, plan.subtitles)
        assertEquals(ChoiceSource.Rule, plan.subtitleSource)
    }

    @Test
    fun `german first gets german audio and no full subtitles`() {
        val plan = plan(preferred = listOf("de", "en"))
        assertEquals(german51, plan.audio)
        assertEquals(SubtitlePlan.ForcedOnly, plan.subtitles)
    }

    @Test
    fun `a commentary track is not picked by preference even when flagged default`() {
        assertEquals(englishStereo, plan(preferred = listOf("en")).audio)
        // No preferred language in the file: the main track, not the default-flagged commentary.
        assertEquals(englishStereo, plan(preferred = listOf("ja")).audio)
    }

    @Test
    fun `commentary plays when it was chosen`() {
        assertEquals(commentary, plan(sheet = TrackChoice(audio = AudioChoice("en", commentary = true))).audio)
        assertEquals(commentary, plan(show = TrackChoice(audio = AudioChoice("en", commentary = true))).audio)
    }

    @Test
    fun `the sheet comes before the show, and the show before the preferences`() {
        val show = TrackChoice(AudioChoice("de", commentary = false), SubtitleChoice.Language("en", sdh = true))
        val fromShow = plan(show = show)
        assertEquals(german51, fromShow.audio)
        assertEquals(ChoiceSource.Show, fromShow.audioSource)
        assertEquals(SubtitlePlan.Track(englishSdh), fromShow.subtitles)
        assertEquals(ChoiceSource.Show, fromShow.subtitleSource)

        val fromSheet = plan(sheet = TrackChoice(AudioChoice("en", commentary = false), SubtitleChoice.Off), show = show)
        assertEquals(englishStereo, fromSheet.audio)
        assertEquals(ChoiceSource.Sheet, fromSheet.audioSource)
        assertEquals(SubtitlePlan.Off, fromSheet.subtitles)
        assertEquals(ChoiceSource.Sheet, fromSheet.subtitleSource)

        // Each part on its own: the sheet's audio with the show's subtitles.
        val mixed = plan(sheet = TrackChoice(audio = AudioChoice("en", commentary = true)), show = show)
        assertEquals(commentary, mixed.audio)
        assertEquals(ChoiceSource.Sheet, mixed.audioSource)
        assertEquals(SubtitlePlan.Track(englishSdh), mixed.subtitles)
        assertEquals(ChoiceSource.Show, mixed.subtitleSource)
    }

    @Test
    fun `a remembered language the file lacks falls back for that part only`() {
        val plan = plan(show = TrackChoice(AudioChoice("ja", commentary = false), SubtitleChoice.Language("de", sdh = false)))
        assertEquals(englishStereo, plan.audio)
        assertEquals(ChoiceSource.Preferences, plan.audioSource)
        assertEquals(SubtitlePlan.Track(germanSubs), plan.subtitles)
        assertEquals(ChoiceSource.Show, plan.subtitleSource)

        // Missing subtitles go back to the rule, which sees the show's German audio.
        val subtitlesMissing = plan(show = TrackChoice(AudioChoice("de", commentary = false), SubtitleChoice.Language("it", sdh = false)))
        assertEquals(german51, subtitlesMissing.audio)
        assertEquals(SubtitlePlan.Track(englishSubs), subtitlesMissing.subtitles)
        assertEquals(ChoiceSource.Rule, subtitlesMissing.subtitleSource)
    }

    @Test
    fun `audio the device can't play is never picked`() {
        assertEquals(englishStereo, plan(preferred = listOf("fr", "en")).audio)
        val chosen = plan(sheet = TrackChoice(audio = AudioChoice("fr", commentary = false)))
        assertEquals(englishStereo, chosen.audio)
        assertEquals(ChoiceSource.Preferences, chosen.audioSource)
    }

    @Test
    fun `off shows nothing and forced only no full subtitles`() {
        assertEquals(SubtitlePlan.Off, plan(sheet = TrackChoice(subtitles = SubtitleChoice.Off)).subtitles)
        assertEquals(SubtitlePlan.ForcedOnly, plan(sheet = TrackChoice(subtitles = SubtitleChoice.ForcedOnly)).subtitles)
        // A choice turns the rule off: German audio would otherwise get English subtitles.
        val german = TrackChoice(AudioChoice("de", commentary = false), SubtitleChoice.ForcedOnly)
        assertEquals(SubtitlePlan.ForcedOnly, plan(sheet = german).subtitles)
        assertEquals(ChoiceSource.Sheet, plan(sheet = german).subtitleSource)
    }

    @Test
    fun `sdh and full subtitles of one language are told apart`() {
        assertEquals(SubtitlePlan.Track(englishSdh), plan(sheet = TrackChoice(subtitles = SubtitleChoice.Language("en", sdh = true))).subtitles)
        assertEquals(SubtitlePlan.Track(englishSubs), plan(sheet = TrackChoice(subtitles = SubtitleChoice.Language("en", sdh = false))).subtitles)
        // Only an SDH track in the language: still that language.
        val sdhOnly = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.englishStereo, TrackFixtures.englishSdh)), Locale.ENGLISH)
        assertEquals("6", (plan(sheet = TrackChoice(subtitles = SubtitleChoice.Language("en", sdh = false)), from = sdhOnly).subtitles as SubtitlePlan.Track).option.key.id)
    }

    @Test
    fun `a forced track picked by itself is found again`() {
        val forced = id("7")
        assertEquals(SubtitlePlan.Track(forced), plan(sheet = TrackChoice(subtitles = SubtitleChoice.of(forced))).subtitles)
        assertEquals(SubtitlePlan.Track(forced), plan(sheet = TrackChoice(subtitles = SubtitleChoice.Language("de", sdh = false, forced = true))).subtitles)
    }

    @Test
    fun `two tracks of one language are told apart by what was picked`() {
        val stereo = audio("1", "en")
        val surround = audio("2", "en", MimeTypes.AUDIO_E_AC3, channels = 6, selection = C.SELECTION_FLAG_DEFAULT)
        val both = TrackOptions.of(TrackFixtures.tracks(listOf(stereo, surround)), Locale.ENGLISH)
        assertEquals("1", plan(sheet = TrackChoice(audio = AudioChoice.of(both[0])), from = both).audio?.key?.id)
        assertEquals("2", plan(sheet = TrackChoice(audio = AudioChoice.of(both[1])), from = both).audio?.key?.id)
        // The show remembers only the language: the file's default track of it.
        assertEquals("2", plan(show = TrackChoice(audio = AudioChoice("en", commentary = false)), from = both).audio?.key?.id)
    }

    @Test
    fun `the rule's subtitles are full ones, not sdh or forced`() {
        val plan = plan(sheet = TrackChoice(audio = AudioChoice("de", commentary = false)))
        assertEquals(SubtitlePlan.Track(englishSubs), plan.subtitles)
        assertEquals(ChoiceSource.Rule, plan.subtitleSource)
        val sdhFirst = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.german51, TrackFixtures.englishSdh, TrackFixtures.englishSubs)), Locale.ENGLISH)
        assertEquals("5", (plan(from = sdhFirst).subtitles as SubtitlePlan.Track).option.key.id)
        assertEquals(SubtitlePlan.ForcedOnly, plan(sheet = TrackChoice(audio = AudioChoice("de", commentary = false)), rule = false).subtitles)
    }

    // Media3 tags Indonesian ms-ind; the rule keeps the viewer's own variant over Malay.
    @Test
    fun `the rule keeps the viewer's variant of a macrolanguage`() {
        val formats = listOf(TrackFixtures.englishStereo, text("m", "msa"), text("i", "ind"))
        val plan = plan(preferred = listOf("in", "en"), from = TrackOptions.of(TrackFixtures.tracks(formats), Locale.ENGLISH))
        assertEquals("i", (plan.subtitles as SubtitlePlan.Track).option.key.id)
    }

    @Test
    fun `nothing playable but commentary leaves the audio to the player`() {
        val onlyCommentary = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.commentary, TrackFixtures.frenchEac3), unsupported = setOf("4")), Locale.ENGLISH)
        val plan = plan(from = onlyCommentary)
        assertNull(plan.audio)
        assertEquals(SubtitlePlan.ForcedOnly, plan.subtitles)
    }

    // Audio description, flagged visual impaired (as Matroska's FlagVisualImpaired reads), in two files: flagged default
    // ahead of the main track, and after it.
    private val describedFirst = TrackOptions.of(
        TrackFixtures.tracks(listOf(audio("d", "en", roles = C.ROLE_FLAG_DESCRIBES_VIDEO, selection = C.SELECTION_FLAG_DEFAULT), TrackFixtures.englishStereo, TrackFixtures.german51)),
        Locale.ENGLISH,
    )
    private val describedAfter = TrackOptions.of(
        TrackFixtures.tracks(listOf(TrackFixtures.englishStereo, audio("d", "en", roles = C.ROLE_FLAG_DESCRIBES_VIDEO), TrackFixtures.german51)),
        Locale.ENGLISH,
    )

    @Test
    fun `an audio description track is not picked by preference even when flagged default`() {
        assertEquals("1", plan(preferred = listOf("en"), from = describedFirst).audio?.key?.id)
        // No preferred language in the file: the main track, not the default-flagged description.
        assertEquals("1", plan(preferred = listOf("ja"), from = describedFirst).audio?.key?.id)
        // Nothing else playable: the player's pick, as with commentary.
        val onlyDescribed = TrackOptions.of(TrackFixtures.tracks(listOf(audio("d", "en", roles = C.ROLE_FLAG_DESCRIBES_VIDEO), TrackFixtures.frenchEac3), unsupported = setOf("4")), Locale.ENGLISH)
        assertNull(plan(from = onlyDescribed).audio)
    }

    @Test
    fun `audio description plays when it was chosen, in the sheet or for the show`() {
        val picked = AudioChoice.of(describedAfter.single { it.key.id == "d" })
        assertEquals(AudioChoice("en", commentary = false, track = picked.track, audioDescription = true), picked)
        assertEquals("d", plan(sheet = TrackChoice(audio = picked), from = describedAfter).audio?.key?.id)
        // The show's next episode: the kind remembered, not the track.
        val show = TrackChoice(audio = AudioChoice("en", commentary = false, audioDescription = true))
        assertEquals("d", plan(show = show, from = describedAfter).audio?.key?.id)
        assertEquals(ChoiceSource.Show, plan(show = show, from = describedAfter).audioSource)
        // A file without it: the language's main track, not its commentary.
        val noDescription = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.commentary, TrackFixtures.englishStereo)), Locale.ENGLISH)
        assertEquals("1", plan(show = show, from = noDescription).audio?.key?.id)
    }

    @Test
    fun `the main audio remembered for the show isn't taken for its audio description`() {
        assertEquals("1", plan(show = TrackChoice(audio = AudioChoice("en", commentary = false)), from = describedFirst).audio?.key?.id)
    }

    @Test
    fun `the plan says what was there, where the choice came from and what plays`() {
        val text = plan(sheet = TrackChoice(audio = AudioChoice("de", commentary = false))).describe(options)
        assertEquals(
            "audio [English · Stereo · AAC (id 1, no name, no flags); German · 5.1 · AAC (id 2, no name, no flags); " +
                "English · Stereo · AAC · Commentary (id 3, name \"Commentary\", flags default); " +
                "French · 5.1 · E-AC-3 · Can't play on this device (id 4, no name, no flags)] " +
                "subtitles [English · SRT (id 5, no name, no flags); English · SRT · SDH (id 6, name \"SDH\", no flags); " +
                "German · SRT · Forced (id 7, no name, flags forced); German · SRT (id 8, no name, no flags)] " +
                "-> audio from sheet: German · 5.1 · AAC; subtitles from rule: English · SRT",
            text,
        )
    }

    /** Two tracks the sheet shows alike, as on a phone with an MKV whose commentary was only flagged: the log tells them apart. */
    @Test
    fun `the plan's log line gives each track's own name and flags`() {
        val flagged = TrackOptions.of(
            TrackFixtures.tracks(
                listOf(
                    audio("2", "en", MimeTypes.AUDIO_AC3, label = "English", selection = C.SELECTION_FLAG_DEFAULT, roles = C.ROLE_FLAG_MAIN or C.ROLE_FLAG_COMMENTARY),
                    audio("3", "en", MimeTypes.AUDIO_AC3, label = "English"),
                ),
            ),
            Locale.ENGLISH,
        )
        assertEquals(
            "audio [English · Stereo · AC-3 · Commentary · Original (id 2, name \"English\", flags default, main, commentary); " +
                "English · Stereo · AC-3 (id 3, name \"English\", no flags)] subtitles [] " +
                "-> audio from preferences: English · Stereo · AC-3; subtitles from rule: forced only",
            plan(from = flagged).describe(flagged),
        )
    }
}

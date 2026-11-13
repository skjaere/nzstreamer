package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import io.skjaere.usenet.data.ShowTrackChoice
import io.skjaere.usenet.data.SubtitleMode
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.AudioChoice
import io.skjaere.usenet.player.SubtitleChoice
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.SubtitleRule
import io.skjaere.usenet.player.TrackChoice
import io.skjaere.usenet.player.TrackKey
import io.skjaere.usenet.player.TrackOptions
import io.skjaere.usenet.player.TrackSelection
import java.util.Locale
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackChoicesTest {
    private fun launch(watch: WatchTarget) = PlayerLaunch(
        uri = "usenet://x/0", title = "Harbor Lights", subtitle = null, watchKey = "k", watch = watch,
        release = SavedRelease("fake", "g", "Harbor.Lights.S01E01", null, 0, "abc"),
    )

    @Test
    fun `only an episode of a tmdb show remembers its choice`() {
        assertEquals(900002, TrackChoices.showOf(launch(WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = 900002, season = 1, episode = 1))))
        assertNull(TrackChoices.showOf(launch(WatchTarget(WatchKind.Movie, "The Quiet Orbit", movieTmdbId = 900010))))
        assertNull(TrackChoices.showOf(launch(WatchTarget(WatchKind.Release, "Harbor.Lights.S01E01"))))
        assertNull(TrackChoices.showOf(launch(WatchTarget(WatchKind.Episode, "Harbor Lights S01E01"))))
        assertNull(TrackChoices.showOf(null))
    }

    @Test
    fun `a show remembers languages and kinds, not one file's tracks`() {
        val key = TrackKey("2", "audio/mp4a-latm", 6, null)
        val picked = TrackChoice(AudioChoice("de", commentary = false, track = key), SubtitleChoice.Language("en", sdh = true, track = TrackKey("6", "application/x-subrip", -1, "SDH")))
        val row = TrackChoices.toShow(900002, picked)
        assertEquals(
            ShowTrackChoice(900002, audioLanguage = "de", audioCommentary = false, subtitles = SubtitleMode.Language, subtitleLanguage = "en", subtitleSdh = true),
            row,
        )
        assertEquals(TrackChoice(AudioChoice("de", commentary = false), SubtitleChoice.Language("en", sdh = true)), TrackChoices.fromShow(row))
    }

    @Test
    fun `each part is remembered on its own, an unknown language too`() {
        val commentary = TrackChoices.toShow(900002, TrackChoice(audio = AudioChoice(null, commentary = true)))
        assertEquals(ShowTrackChoice(900002, audioLanguage = "und", audioCommentary = true), commentary)
        assertEquals(TrackChoice(audio = AudioChoice(null, commentary = true)), TrackChoices.fromShow(commentary))

        listOf(SubtitleChoice.Off to SubtitleMode.Off, SubtitleChoice.ForcedOnly to SubtitleMode.ForcedOnly).forEach { (choice, mode) ->
            val row = TrackChoices.toShow(900002, TrackChoice(subtitles = choice))
            assertEquals(ShowTrackChoice(900002, subtitles = mode), row)
            assertEquals(TrackChoice(subtitles = choice), TrackChoices.fromShow(row))
        }
        val forcedTrack = TrackChoices.toShow(900002, TrackChoice(subtitles = SubtitleChoice.Language("de", sdh = false, forced = true)))
        assertEquals(TrackChoice(subtitles = SubtitleChoice.Language("de", sdh = false, forced = true)), TrackChoices.fromShow(forcedTrack))
    }

    @Test
    fun `audio description is remembered as a kind of its own`() {
        val described = TrackChoice(audio = AudioChoice("en", commentary = false, audioDescription = true))
        assertEquals(described, TrackChoices.fromShow(TrackChoices.toShow(900002, described)))
        assertEquals(described, TrackChoices.decode(TrackChoices.encode(described)))
        // A play's choice saved by an earlier version: the main audio.
        assertEquals(
            TrackChoice(audio = AudioChoice("en", commentary = false)),
            TrackChoices.decode("""{"audio":{"language":"en","commentary":false,"track":null}}"""),
        )
    }

    @Test
    fun `a new pick replaces only its own part`() {
        val before = TrackChoice(AudioChoice("de", commentary = false), SubtitleChoice.Language("en", sdh = false))
        assertEquals(
            TrackChoice(AudioChoice("en", commentary = true), SubtitleChoice.Language("en", sdh = false)),
            TrackChoices.updated(before, TrackChoice(audio = AudioChoice("en", commentary = true))),
        )
        assertEquals(TrackChoice(subtitles = SubtitleChoice.Off), TrackChoices.updated(null, TrackChoice(subtitles = SubtitleChoice.Off)))
    }

    private val formats = listOf(
        Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("en").setChannelCount(2).build(),
        Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("de").setChannelCount(6).build(),
        Format.Builder().setId("5").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build(),
        Format.Builder().setId("7").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_SUBRIP).setLanguage("de")
            .setSelectionFlags(C.SELECTION_FLAG_FORCED).build(),
    )

    private fun tracks(selected: Set<String>, from: List<Format> = formats) =
        Tracks(from.mapIndexed { i, f -> Tracks.Group(TrackGroup("0:$i", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(f.id in selected)) })

    @Test
    fun `what plays now, as a choice`() {
        val defaults = TrackSelectionParameters.Builder().build()
        val withSubs = TrackOptions.of(tracks(setOf("2", "5")), Locale.ENGLISH)
        val pinned = TrackSelection.withSubtitles(defaults, tracks(setOf("2", "5")), SubtitlePlan.Track(withSubs[2]))
        assertEquals(TrackChoice(AudioChoice.of(withSubs[1]), SubtitleChoice.of(withSubs[2])), TrackChoices.shown(withSubs, pinned))
        // German audio with its forced subtitles, the rule's "none": forced only.
        val forced = TrackOptions.of(tracks(setOf("2", "7")), Locale.ENGLISH)
        assertEquals(TrackChoice(AudioChoice.of(forced[1]), SubtitleChoice.ForcedOnly), TrackChoices.shown(forced, SubtitleRule.withSubtitles(defaults, null)))
        val off = TrackOptions.of(tracks(setOf("1")), Locale.ENGLISH)
        assertEquals(TrackChoice(AudioChoice.of(off[0]), SubtitleChoice.Off), TrackChoices.shown(off, TrackSelection.withSubtitles(defaults, tracks(setOf("1")), SubtitlePlan.Off)))
        assertEquals(TrackChoice(subtitles = SubtitleChoice.ForcedOnly), TrackChoices.shown(TrackOptions.of(tracks(emptySet()), Locale.ENGLISH), defaults))
    }

    /**
     * A pick keeps the other part as it shows (or as picked or remembered before), so a new
     * player or the show's next episode looks as the user left it, the rule no longer deciding.
     */
    @Test
    fun `a pick keeps the other part as it was`() {
        val shown = TrackChoice(AudioChoice("en", commentary = false, track = TrackKey("1", "audio/mp4a-latm", 2, null)), SubtitleChoice.ForcedOnly)
        val german = TrackChoice(audio = AudioChoice("de", commentary = false))
        assertEquals(TrackChoice(german.audio, SubtitleChoice.ForcedOnly), TrackChoices.afterPick(german, before = TrackChoice(), shown = shown))
        val remembered = TrackChoice(subtitles = SubtitleChoice.Language("fr", sdh = false))
        assertEquals(TrackChoice(german.audio, remembered.subtitles), TrackChoices.afterPick(german, before = remembered, shown = shown))
        assertEquals(TrackChoice(german.audio, SubtitleChoice.ForcedOnly), TrackChoices.afterPick(german, before = null, shown = shown))
    }

    /**
     * The rule's "none" in a file without a forced track shows nothing, as Off would, but is kept
     * as forced only: Off would hide the forced subtitles of the show's later episodes.
     */
    @Test
    fun `audio picked alone in a file without forced subtitles keeps them forced only`() {
        val noForced = tracks(setOf("1"), from = formats.filterNot { it.id == "7" })
        val options = TrackOptions.of(noForced, Locale.ENGLISH)
        val shown = TrackChoices.shown(options, SubtitleRule.withSubtitles(TrackSelectionParameters.Builder().build(), null))
        assertEquals(SubtitleChoice.ForcedOnly, shown.subtitles)
        val picked = TrackChoices.afterPick(TrackChoice(audio = AudioChoice.of(options[1])), before = null, shown = shown)
        assertEquals(SubtitleChoice.ForcedOnly, picked.subtitles)
        assertEquals(SubtitleMode.ForcedOnly, TrackChoices.toShow(900002, picked).subtitles)
    }

    @Test
    fun `the play's choice is kept across recreation with the very tracks picked`() {
        val choice = TrackChoice(
            AudioChoice("en", commentary = false, track = TrackKey("4", "audio/eac3", 6, "Surround")),
            SubtitleChoice.Language(null, sdh = true, forced = false, track = TrackKey("6", "application/x-subrip", -1, null)),
        )
        listOf(choice, TrackChoice(), TrackChoice(subtitles = SubtitleChoice.Off), TrackChoice(subtitles = SubtitleChoice.ForcedOnly)).forEach {
            assertEquals(it, TrackChoices.decode(TrackChoices.encode(it)))
        }
        assertEquals(TrackChoice(), TrackChoices.decode("not json"))
    }
}

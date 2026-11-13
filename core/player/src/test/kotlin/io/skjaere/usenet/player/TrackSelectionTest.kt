package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackSelectionTest {
    private val defaults = TrackSelectionParameters.Builder().build()
    private val tracks = TrackFixtures.fixtureTracks()
    private val options = TrackOptions.of(tracks, Locale.ENGLISH)
    private fun id(id: String) = options.single { it.key.id == id }

    @Test
    fun `audio is pinned to its track and its language goes first`() {
        val german = id("2")
        val parameters = TrackSelection.withAudio(defaults, tracks, german, listOf("en", "de"))
        val override = parameters.overrides.values.single()
        assertSame(tracks.groups[german.groupIndex].mediaTrackGroup, override.mediaTrackGroup)
        assertEquals(listOf(0), override.trackIndices)
        // The selector's own audio pick has the same language, so forced subtitles follow it.
        assertEquals(listOf("de", "en"), parameters.preferredAudioLanguages)
    }

    @Test
    fun `no audio track leaves the pick to the player`() {
        val pinned = TrackSelection.withAudio(defaults, tracks, id("2"), listOf("en"))
        val parameters = TrackSelection.withAudio(pinned, tracks, null, listOf("en"))
        assertTrue(parameters.overrides.isEmpty())
        assertEquals(listOf("en"), parameters.preferredAudioLanguages)
    }

    @Test
    fun `off disables text, forced tracks included`() {
        val pinned = TrackSelection.withSubtitles(defaults, tracks, SubtitlePlan.Track(id("5")))
        val parameters = TrackSelection.withSubtitles(pinned, tracks, SubtitlePlan.Off)
        assertTrue(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        assertTrue(parameters.overrides.isEmpty())
    }

    @Test
    fun `forced only is the rule's no subtitles`() {
        val pinned = TrackSelection.withSubtitles(TrackSelection.withSubtitles(defaults, tracks, SubtitlePlan.Off), tracks, SubtitlePlan.Track(id("5")))
        val parameters = TrackSelection.withSubtitles(pinned, tracks, SubtitlePlan.ForcedOnly)
        assertEquals(SubtitleRule.withSubtitles(defaults, null), parameters)
    }

    /**
     * The track selector knows a forced track only by its flag; many files name it "Forced"
     * instead. Forced only pins the forced track in the audio's language itself, either kind.
     */
    @Test
    fun `forced only pins the forced track in the audio's language, flagged or named so`() {
        val named = TrackFixtures.text("9", "en", label = "English (Forced)")
        val englishNamed = TrackFixtures.tracks(listOf(TrackFixtures.englishStereo, TrackFixtures.englishSubs, named), setOf("1"))
        val parameters = TrackSelection.withSubtitles(defaults, englishNamed, SubtitlePlan.ForcedOnly)
        assertSame(englishNamed.groups[2].mediaTrackGroup, parameters.overrides.values.single().mediaTrackGroup)
        // Still forced only's: no language asked for, the default flag ignored.
        assertTrue(parameters.preferredTextLanguages.isEmpty())
        assertEquals(C.SELECTION_FLAG_DEFAULT, parameters.ignoredTextSelectionFlags)

        val german = TrackSelection.withSubtitles(TrackSelection.withAudio(defaults, tracks, id("2"), listOf("en")), tracks, SubtitlePlan.ForcedOnly)
        assertSame(tracks.groups[id("7").groupIndex].mediaTrackGroup, german.overrides.values.single { it.type == C.TRACK_TYPE_TEXT }.mediaTrackGroup)
    }

    @Test
    fun `forced only follows the audio picked`() {
        val english = TrackFixtures.fixtureTracks(selected = setOf("0", "1"))
        val forcedOnly = TrackSelection.withSubtitles(defaults, english, SubtitlePlan.ForcedOnly)
        assertTrue(forcedOnly.overrides.isEmpty(), "no English forced track to pin")
        val german = TrackSelection.withAudio(forcedOnly, english, TrackOptions.of(english, Locale.ENGLISH).single { it.key.id == "2" }, listOf("en"))
        assertSame(english.groups[7].mediaTrackGroup, german.overrides.values.single { it.type == C.TRACK_TYPE_TEXT }.mediaTrackGroup)
        // A track picked as such stays as it is.
        val picked = TrackSelection.withSubtitles(defaults, english, SubtitlePlan.Track(TrackOptions.of(english, Locale.ENGLISH).single { it.key.id == "5" }))
        val stays = TrackSelection.withAudio(picked, english, TrackOptions.of(english, Locale.ENGLISH).single { it.key.id == "2" }, listOf("en"))
        assertSame(english.groups[5].mediaTrackGroup, stays.overrides.values.single { it.type == C.TRACK_TYPE_TEXT }.mediaTrackGroup)
    }

    @Test
    fun `a pinned forced track shows as forced only, and as itself when picked`() {
        val named = TrackFixtures.text("9", "en", label = "English (Forced)")
        val showing = TrackFixtures.tracks(listOf(TrackFixtures.englishStereo, TrackFixtures.englishSubs, named), setOf("1", "9"))
        val options = TrackOptions.of(showing, Locale.ENGLISH)
        assertEquals(SubtitlePlan.ForcedOnly, TrackSelection.shownSubtitles(options, TrackSelection.withSubtitles(defaults, showing, SubtitlePlan.ForcedOnly)))
        val track = SubtitlePlan.Track(options.single { it.key.id == "9" })
        assertEquals(track, TrackSelection.shownSubtitles(options, TrackSelection.withSubtitles(defaults, showing, track)))
    }

    @Test
    fun `a subtitle track is pinned`() {
        val off = TrackSelection.withSubtitles(defaults, tracks, SubtitlePlan.Off)
        val sdh = id("6")
        val parameters = TrackSelection.withSubtitles(off, tracks, SubtitlePlan.Track(sdh))
        assertFalse(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        val override = parameters.overrides.values.single()
        assertSame(tracks.groups[sdh.groupIndex].mediaTrackGroup, override.mediaTrackGroup)
        assertEquals(0, parameters.ignoredTextSelectionFlags)
    }

    @Test
    fun `audio and subtitles of a plan are pinned together`() {
        val plan = TrackPlanner.plan(options, TrackChoice(AudioChoice("de", false), SubtitleChoice.Language("en", false)), null, listOf("en"), true)
        val parameters = TrackSelection.apply(defaults, tracks, plan, listOf("en"))
        assertEquals(
            setOf(tracks.groups[2].mediaTrackGroup, tracks.groups[5].mediaTrackGroup),
            parameters.overrides.keys,
        )
    }

    /**
     * A new player of the same file has new TrackGroup instances (here with other ids, so an
     * override for the old ones would match nothing): the choice is found again by its descriptor.
     */
    @Test
    fun `a choice made on one player is pinned to the same tracks of the next`() {
        val sheet = TrackChoice(AudioChoice.of(id("2")), SubtitleChoice.of(id("6")))
        val rebuilt = TrackFixtures.fixtureTracks(groupIdPrefix = "1")
        val plan = TrackPlanner.plan(TrackOptions.of(rebuilt, Locale.ENGLISH), sheet, null, listOf("en"), true)
        val parameters = TrackSelection.apply(defaults, rebuilt, plan, listOf("en"))
        val pinned = parameters.overrides.keys
        assertEquals(2, pinned.size)
        assertTrue(pinned.any { it === rebuilt.groups[2].mediaTrackGroup })
        assertTrue(pinned.any { it === rebuilt.groups[6].mediaTrackGroup })
        assertTrue(pinned.none { k -> tracks.groups.any { it.mediaTrackGroup == k } }, "bound to the old player's groups")
    }

    @Test
    fun `the sheet marks what shows`() {
        assertEquals(SubtitlePlan.Off, TrackSelection.shownSubtitles(options, TrackSelection.withSubtitles(defaults, tracks, SubtitlePlan.Off)))
        val pinned = TrackFixtures.fixtureTracks(selected = setOf("0", "1", "6"))
        val pinnedOptions = TrackOptions.of(pinned, Locale.ENGLISH)
        assertEquals(
            SubtitlePlan.Track(pinnedOptions.single { it.key.id == "6" }),
            TrackSelection.shownSubtitles(pinnedOptions, TrackSelection.withSubtitles(defaults, pinned, SubtitlePlan.Track(pinnedOptions.single { it.key.id == "6" }))),
        )
        // Forced only, showing the German forced track for German audio: marked forced only.
        val forcedShowing = TrackOptions.of(TrackFixtures.fixtureTracks(selected = setOf("0", "2", "7")), Locale.ENGLISH)
        assertEquals(SubtitlePlan.ForcedOnly, TrackSelection.shownSubtitles(forcedShowing, SubtitleRule.withSubtitles(defaults, null)))
        // Forced only, nothing to show: still forced only.
        assertEquals(SubtitlePlan.ForcedOnly, TrackSelection.shownSubtitles(options, SubtitleRule.withSubtitles(defaults, null)))
    }

    /**
     * Off is text turned off, forced subtitles included; the rule's "none" leaves text on for
     * them. A file without a forced track shows nothing either way, but what the sheet keeps (for
     * the next player, and the show's next episode) must be what the parameters say.
     */
    @Test
    fun `forced only stays forced only in a file without a forced track`() {
        val noForced = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.englishStereo, TrackFixtures.englishSubs), setOf("1")), Locale.ENGLISH)
        assertEquals(SubtitlePlan.ForcedOnly, TrackSelection.shownSubtitles(noForced, SubtitleRule.withSubtitles(defaults, null)))
        val noText = TrackOptions.of(TrackFixtures.tracks(listOf(TrackFixtures.englishStereo), setOf("1")), Locale.ENGLISH)
        assertEquals(SubtitlePlan.ForcedOnly, TrackSelection.shownSubtitles(noText, SubtitleRule.withSubtitles(defaults, null)))
        assertEquals(SubtitlePlan.Off, TrackSelection.shownSubtitles(noForced, TrackSelection.withSubtitles(defaults, TrackFixtures.tracks(listOf(TrackFixtures.englishStereo)), SubtitlePlan.Off)))
    }
}

package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackChoiceApplierTest {
    private val english = listOf("en")
    private val applied = mutableListOf<Pair<FakePlayer, TrackPlan>>()
    private val applier = TrackChoiceApplier<FakePlayer> { player, plan -> applied += player to plan }
    private val options = TrackOptions.of(TrackFixtures.fixtureTracks(), Locale.ENGLISH)
    private val germanAudio = TrackChoice(audio = AudioChoice("de", commentary = false))

    /** Stands in for an ExoPlayer: the applier only tells players apart. */
    private class FakePlayer(val name: String) {
        override fun toString() = name
    }

    private fun tracks(player: FakePlayer, sheet: TrackChoice = TrackChoice(), show: TrackChoice? = null) =
        applier.onTracksChanged(player, options, sheet, show, english, rule = true)

    @Test
    fun `foreign audio gets subtitles in the first language`() {
        val player = FakePlayer("first")
        tracks(player, sheet = germanAudio)
        assertEquals(listOf(player), applied.map { it.first })
        assertEquals("5", (applied.single().second.subtitles as SubtitlePlan.Track).option.key.id)
    }

    @Test
    fun `later track changes keep a hand-picked choice`() {
        val player = FakePlayer("first")
        tracks(player)
        assertNull(tracks(player, sheet = germanAudio))
        assertEquals(1, applied.size)
    }

    @Test
    fun `a player built after the previous one was released is planned again`() {
        tracks(FakePlayer("first"), sheet = germanAudio)
        // onStop released the first player; onStart builds a new one with fresh track selection.
        val next = FakePlayer("next")
        tracks(next, sheet = germanAudio)
        assertEquals(next, applied.last().first, "nothing applied to the new player")
        assertEquals("2", applied.last().second.audio?.key?.id, "the sheet's audio lost with the old player")
        assertEquals("5", (applied.last().second.subtitles as SubtitlePlan.Track).option.key.id)
    }

    @Test
    fun `a player built after the previous one was released keeps subtitles off for own-language audio`() {
        tracks(FakePlayer("first"))
        val next = FakePlayer("next")
        tracks(next)
        assertEquals(SubtitlePlan.ForcedOnly, applied.last().second.subtitles, "default subtitle track left on for the new player")
    }

    @Test
    fun `reset plans the same player again`() {
        val player = FakePlayer("first")
        tracks(player, sheet = germanAudio)
        applier.planAgain(player, options, TrackChoice(), null, english, rule = true)
        assertEquals(2, applied.size)
        assertEquals("1", applied.last().second.audio?.key?.id)
        assertEquals(ChoiceSource.Preferences, applied.last().second.audioSource)
        // And later track lists of it are left alone, as before.
        assertNull(tracks(player))
    }

    /** A remux's TrueHD, the file's default, beside an AC-3 track; [trueHdPlays] as a receiver switched on makes it. */
    private val trueHd = TrackFixtures.audio("1", "en", MimeTypes.AUDIO_TRUEHD, channels = 8, selection = C.SELECTION_FLAG_DEFAULT)
    private val ac3 = TrackFixtures.audio("2", "en", MimeTypes.AUDIO_AC3, channels = 6)

    private fun remux(trueHdPlays: Boolean, selected: Set<String> = setOf("0")) = TrackOptions.of(
        TrackFixtures.tracks(listOf(TrackFixtures.video("0"), trueHd, ac3), selected, unsupported = if (trueHdPlays) emptySet() else setOf("1")),
        Locale.ENGLISH,
    )

    /**
     * A TV's player rates its tracks again as the audio route changes (PlayerFactory's followAudioRoute): the receiver
     * switched on mid-film makes TrueHD playable, and the AC-3 track pinned while it wasn't would otherwise stay.
     */
    @Test
    fun `a player that rates its audio again, as a receiver comes on, is planned again`() {
        val player = FakePlayer("tv")
        applier.onTracksChanged(player, remux(trueHdPlays = false), TrackChoice(), null, english, rule = true)
        assertEquals("2", applied.last().second.audio?.key?.id)
        // The plan's own selection, rated as before: left alone.
        assertNull(applier.onTracksChanged(player, remux(trueHdPlays = false, selected = setOf("0", "2")), TrackChoice(), null, english, rule = true))

        val again = applier.onTracksChanged(player, remux(trueHdPlays = true, selected = setOf("0", "2")), TrackChoice(), null, english, rule = true)

        assertEquals("1", again?.audio?.key?.id)
        assertEquals(listOf(player, player), applied.map { it.first })
        // And the receiver off again: back to AC-3, which still plays.
        assertEquals("2", applier.onTracksChanged(player, remux(trueHdPlays = false, selected = setOf("0", "1")), TrackChoice(), null, english, rule = true)?.audio?.key?.id)
    }

    @Test
    fun `audio picked in the sheet stays when the player rates its audio again`() {
        val player = FakePlayer("tv")
        val picked = TrackChoice(audio = AudioChoice.of(remux(trueHdPlays = false).single { it.key.id == "2" }))
        applier.onTracksChanged(player, remux(trueHdPlays = false), picked, null, english, rule = true)
        val again = applier.onTracksChanged(player, remux(trueHdPlays = true, selected = setOf("0", "2")), picked, null, english, rule = true)
        assertEquals("2", again?.audio?.key?.id)
        assertEquals(ChoiceSource.Sheet, again?.audioSource)
    }
}

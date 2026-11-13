package io.skjaere.usenet.player

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DisplayModes]: the refresh rates a display offers at the size it runs now, and Android's "Match content frame rate"
 * setting, read beside [DisplayFacts] (TCR-1). The Shield's modes as read: 3840x2160 and 1920x1080 at
 * 23.976 to 60 Hz, 1080p at 120 Hz too, and 720p and 480p.
 */
class DisplayModesTest {
    private val uhd = ModeSize(3840, 2160)
    private val fullHd = ModeSize(1920, 1080)
    private val shieldRates = listOf(23.976025f, 24f, 25f, 29.97003f, 30f, 50f, 59.94006f, 60f)
    private val shieldModes = shieldRates.map { ModeReading(uhd, it) } + (shieldRates + 120f).map { ModeReading(fullHd, it) } +
        listOf(ModeReading(ModeSize(1280, 720), 60f), ModeReading(ModeSize(720, 480), 59.94006f))

    private fun readings(active: ModeReading = ModeReading(uhd, 59.94006f), modes: List<ModeReading> = shieldModes, preference: () -> Int = { 1 }) =
        ModeReadings(active = { active }, modes = { modes }, matchPreference = preference)

    /** Once each: a rate listed twice, and one listed again as a float a hair apart (two modes that differ in what the app can't see). */
    @Test
    fun `the rates are those of the modes at the size the display runs now, ascending, each once`() {
        val modes = DisplayModes.of(30, readings(modes = shieldModes.shuffled(java.util.Random(3)) + ModeReading(uhd, 60f) + ModeReading(uhd, 59.94f)))
        assertEquals(uhd, modes.size)
        assertEquals(shieldRates, modes.rates)
        // The box set to 1080p output: its rates there, 120 Hz among them.
        assertEquals(shieldRates + 120f, DisplayModes.of(30, readings(active = ModeReading(fullHd, 60f))).rates)
    }

    /** A phone's adaptive refresh: the panel's own rates at its size, whatever rate it runs now. */
    @Test
    fun `a phone's rates are its panel's`() {
        val panel = ModeSize(1080, 2400)
        val modes = DisplayModes.of(34, readings(active = ModeReading(panel, 120f), modes = listOf(ModeReading(panel, 60f), ModeReading(panel, 120f), ModeReading(panel, 90f))))
        assertEquals(listOf(60f, 90f, 120f), modes.rates)
    }

    /** DisplayManager.getMatchContentFrameRateUserPreference, from Android 12: -1 unknown, 0 never, 1 seamless only, 2 always. */
    @Test
    fun `the match preference is read from Android 12, and not asked below`() {
        assertEquals(MatchContentFrameRate.Unknown, DisplayModes.of(31, readings(preference = { -1 })).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Never, DisplayModes.of(31, readings(preference = { 0 })).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.OnlyWhenSeamless, DisplayModes.of(33, readings(preference = { 1 })).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Always, DisplayModes.of(36, readings(preference = { 2 })).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Unknown, DisplayModes.of(36, readings(preference = { 7 })).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Unknown, DisplayModes.of(36, readings(preference = { throw IllegalStateException("display service gone") })).matchContentFrameRate)
        // The Shield, Android 11: the setting isn't asked for at all.
        assertNull(DisplayModes.of(30, readings(preference = { throw AssertionError("asked below API 31") })).matchContentFrameRate)
    }

    @Test
    fun `without a default display nothing is known`() {
        assertEquals(DisplayModes(size = null, rates = emptyList(), matchContentFrameRate = null), DisplayModes.of(34, null))
    }

    /**
     * TCR-1: a TV switching to a film's rate leaves [DisplayFacts] equal, so auto-advance's prefetched search is still
     * handed to the play; the modes are read beside the facts, never in them.
     */
    @Test
    fun `a switch to a film's rate changes the modes' reading, never the display's facts`() {
        val film = readings(active = ModeReading(uhd, 23.976025f))
        val ui = readings(active = ModeReading(uhd, 59.94006f))
        fun facts(modes: ModeReadings) = DisplayFacts.of(true, 30, { null }, fullHd, DisplayReadings({ modes.active().size }, { modes.modes().map { it.size } }, { intArrayOf(1, 2) }, { modes.active().refreshRate }))
        assertEquals(facts(film), facts(ui))
        assertEquals(DisplayModes.of(30, film), DisplayModes.of(30, ui))
    }
}

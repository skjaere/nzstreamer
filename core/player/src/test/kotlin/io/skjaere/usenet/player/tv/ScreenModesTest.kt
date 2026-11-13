package io.skjaere.usenet.player.tv

import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.releases.Hdr
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ScreenModes]: the default display's modes with their ids, as a frame-rate switch reads them at its moment, beside
 * [DisplayFacts]' output for #65's guard. The Shield's modes as its dumpsys display listed them.
 */
class ScreenModesTest {
    private val uhd = ModeSize(3840, 2160)
    private val fullHd = ModeSize(1920, 1080)
    private val facts = DisplayFacts(true, uhd, true, uhd, fullHd, setOf(Hdr.DolbyVision, Hdr.Hdr10))

    private fun reading(id: Int, size: ModeSize, rate: Float, alternatives: () -> List<Float> = { emptyList() }) = ScreenModeReading(id, size, rate, alternatives)

    private val listed = listOf(reading(23, uhd, 23.976025f), reading(31, uhd, 24.000002f), reading(41, uhd, 59.94006f), reading(25, fullHd, 23.976025f))

    private fun readings(
        active: ScreenModeReading = listed[2],
        modes: List<ScreenModeReading> = listed,
        preference: () -> Int = { 1 },
    ) = ScreenReadings(active = { active }, modes = { modes }, matchPreference = preference)

    @Test
    fun `the modes are read with their ids, physical sizes and rates, in the display's order, the one it runs among them`() {
        val modes = ScreenModes.of(30, readings(), facts)
        assertEquals(ScreenMode(41, uhd, 59.94006f), modes.active)
        assertEquals(
            listOf(ScreenMode(23, uhd, 23.976025f), ScreenMode(31, uhd, 24.000002f), ScreenMode(41, uhd, 59.94006f), ScreenMode(25, fullHd, 23.976025f)),
            modes.modes,
        )
        assertEquals(30, modes.sdk)
    }

    /** Display.Mode.getAlternativeRefreshRates is API 31: asked below it, the app would crash. */
    @Test
    fun `the seamless alternative rates are read from Android 12, and not asked below`() {
        val seamless = reading(1, fullHd, 60f) { listOf(120f) }
        val panel = listOf(seamless, reading(2, fullHd, 120f) { listOf(60f) })
        val modes = ScreenModes.of(31, readings(active = seamless, modes = panel), facts)
        assertEquals(listOf(120f), modes.active?.alternativeRates)
        assertEquals(listOf(listOf(120f), listOf(60f)), modes.modes.map { it.alternativeRates })
        val asked = reading(1, fullHd, 60f) { throw AssertionError("asked below API 31") }
        assertEquals(emptyList(), ScreenModes.of(30, readings(active = asked, modes = listOf(asked)), facts).active?.alternativeRates)
        // One the platform can't answer reads as none.
        val failing = reading(1, fullHd, 60f) { throw IllegalStateException("display gone") }
        assertEquals(emptyList(), ScreenModes.of(33, readings(active = failing, modes = listOf(failing)), facts).active?.alternativeRates)
    }

    @Test
    fun `the match preference is read from Android 12, and not asked below`() {
        assertEquals(MatchContentFrameRate.Never, ScreenModes.of(31, readings(preference = { 0 }), facts).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.OnlyWhenSeamless, ScreenModes.of(34, readings(preference = { 1 }), facts).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Always, ScreenModes.of(36, readings(preference = { 2 }), facts).matchContentFrameRate)
        assertEquals(MatchContentFrameRate.Unknown, ScreenModes.of(36, readings(preference = { throw IllegalStateException("display service gone") }), facts).matchContentFrameRate)
        assertNull(ScreenModes.of(30, readings(preference = { throw AssertionError("asked below API 31") }), facts).matchContentFrameRate)
    }

    /** #65's guard needs the output as Media3 reads it, the TV's display-size property first, beside the mode the display runs. */
    @Test
    fun `the output and whether the TV says it come from the display's facts`() {
        val modes = ScreenModes.of(30, readings(active = listed[3]), facts)
        assertEquals(uhd, modes.output)
        assertEquals(true, modes.outputFromTv)
        assertEquals(fullHd, modes.active?.size)
        val phone = ScreenModes.of(34, readings(), DisplayFacts(false, ModeSize(1080, 2400), false, ModeSize(1080, 2400), ModeSize(1080, 2400), emptySet()))
        assertEquals(ModeSize(1080, 2400) to false, phone.output to phone.outputFromTv)
    }

    @Test
    fun `without a default display nothing is known`() {
        assertEquals(ScreenModes(34, null, emptyList(), null, null, false), ScreenModes.of(34, null, DisplayFacts(false, null, false, null, fullHd, null)))
    }
}

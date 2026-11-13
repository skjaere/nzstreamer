package io.skjaere.usenet.player

import io.skjaere.usenet.releases.Hdr
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayFactsTest {
    private val uhd = ModeSize(3840, 2160)
    private val fullHd = ModeSize(1920, 1080)

    /** A TV's facts unless [tv] says otherwise. */
    private fun facts(output: ModeSize?, largest: ModeSize?, fromTv: Boolean = false, window: ModeSize = fullHd, hdr: Set<Hdr>? = emptySet(), tv: Boolean = true) =
        DisplayFacts(tv, output, fromTv, largest, window, hdr)

    @Test
    fun `the height is the output mode's, so a box set to 1080p output ranks for 1080p`() {
        // The Shield at 4K output: its window is the 1080p UI, its mode 2160p.
        assertEquals(2160, facts(output = uhd, largest = uhd).height)
        assertEquals(1080, facts(output = fullHd, largest = uhd).height)
        // A portrait phone's.
        assertEquals(1080, facts(output = ModeSize(1080, 2400), largest = ModeSize(1080, 2400), window = ModeSize(1080, 2400), tv = false).height)
    }

    @Test
    fun `without the TV's own word the output is never taken above the largest mode listed`() {
        assertEquals(1080, facts(output = uhd, largest = fullHd).height)
        // No output known: the largest mode, as before; no mode either: the window.
        assertEquals(2160, facts(output = null, largest = uhd).height)
        assertEquals(1080, facts(output = null, largest = null, window = ModeSize(2400, 1080)).height)
    }

    /** #65: a 4K panel whose modes list only the 1080p UI, where the TV's vendor.display-size says 3840x2160. */
    @Test
    fun `the TV's display-size property is trusted above the modes listed`() {
        assertEquals(2160, facts(output = uhd, largest = fullHd, fromTv = true).height)
        assertEquals(1080, facts(output = fullHd, largest = uhd, fromTv = true).height)
    }

    /** Media3 reads the TV's size from sys.display-size below Android 9 (API 28), vendor.display-size from then on. */
    @Test
    fun `the TV's size property is the one Media3 reads at the API level`() {
        assertEquals("sys.display-size", DisplayFacts.sizeProperty(26))
        assertEquals("sys.display-size", DisplayFacts.sizeProperty(27))
        assertEquals("vendor.display-size", DisplayFacts.sizeProperty(28))
        assertEquals("vendor.display-size", DisplayFacts.sizeProperty(36))
    }

    /** The display's answers as a test gives them: by default a 4K TV's, outputting 2160p at 59.94 Hz, with HDR10. */
    private fun readings(output: ModeSize = uhd, modes: List<ModeSize> = listOf(fullHd, uhd), hdrTypes: () -> IntArray? = { intArrayOf(2) }, refreshRate: Float = 59.94006f) =
        DisplayReadings(output = { output }, modes = { modes }, hdrTypes = hdrTypes, refreshRate = { refreshRate })

    private fun read(
        display: DisplayReadings? = readings(),
        tv: Boolean = true,
        sdk: Int = 30,
        property: (String) -> String? = { null },
        window: ModeSize = fullHd,
    ) = DisplayFacts.of(tv, sdk, property, window, display)

    /** #65: a 4K TV whose modes list only the 1080p UI says 3840x2160 in its display-size property, which Media3 outputs. */
    @Test
    fun `on a TV whose display-size property is set the output is the TV's word, read where Media3 reads it`() {
        val asked = mutableListOf<String>()
        fun property(value: String?): (String) -> String? = { name -> asked += name; value }
        val listsOnlyTheUi = readings(output = uhd, modes = listOf(fullHd))

        val set = read(listsOnlyTheUi, property = property("3840x2160"))
        assertTrue(set.fromTv)
        assertEquals(2160, set.height)
        assertEquals("vendor.display-size", asked.last())
        assertTrue(read(listsOnlyTheUi, sdk = 27, property = property("3840x2160")).fromTv)
        assertEquals("sys.display-size", asked.last())
        // Blank or not set, or not a TV (Media3 reads the property only on one): the modes listed bound the output.
        listOf(read(listsOnlyTheUi, property = property("")), read(listsOnlyTheUi, property = property(null)), read(listsOnlyTheUi, tv = false, property = property("3840x2160")))
            .forEach { facts ->
                assertFalse(facts.fromTv, "$facts")
                assertEquals(1080, facts.height, "$facts")
            }
    }

    /**
     * A phone's resolution setting is a display mode: a Pixel 8 Pro at its default runs 1008x2244 on its 1344x2992 panel.
     * A phone ranks for its largest mode, as before the output was read, so 1080p still goes first there; only a TV
     * follows its output, a box's setting.
     */
    @Test
    fun `a phone ranks for its largest mode whatever mode its resolution setting runs, a TV for its output`() {
        val modes = readings(output = ModeSize(1008, 2244), modes = listOf(ModeSize(1008, 2244), ModeSize(1344, 2992)))
        assertEquals(1344, read(modes, tv = false, window = ModeSize(1008, 2244)).height)
        assertEquals(1008, read(modes, tv = true).height)
    }

    @Test
    fun `the output and the largest mode are the display's, a size it can't say unknown`() {
        val facts = read(readings(output = fullHd, modes = listOf(ModeSize(1280, 720), uhd, fullHd)))
        assertEquals(DisplayFacts(true, fullHd, false, uhd, fullHd, setOf(Hdr.Hdr10)), facts)
        assertNull(read(readings(output = ModeSize(0, 0))).output)
        assertNull(read(readings(modes = emptyList())).largest)
    }

    /** Display.getHdrCapabilities: null without HDR, as Display.isHdr reads it; a call that throws leaves HDR unknown, holding no release back. */
    @Test
    fun `HDR is the display's types, none without capabilities, unknown when they can't be read`() {
        assertEquals(setOf(Hdr.DolbyVision, Hdr.Hdr10), read(readings(hdrTypes = { intArrayOf(1, 2) })).hdr)
        assertEquals(emptySet(), read(readings(hdrTypes = { null })).hdr)
        assertEquals(emptySet(), read(readings(hdrTypes = { intArrayOf() })).hdr)
        assertNull(read(readings(hdrTypes = { throw IllegalStateException("display removed") })).hdr)
    }

    @Test
    fun `without a default display only the window is known`() {
        val none = read(display = null, property = { "3840x2160" }, window = ModeSize(1080, 2400))
        assertEquals(DisplayFacts(true, null, false, null, ModeSize(1080, 2400), null), none)
        assertEquals(1080, none.height)
    }

    /**
     * Display.getHdrCapabilities' types as each API level gives them, all read by the same code: below 29 there is no
     * HDR10+ (type 4); from 31 the types the user turned off are left out; from 34 they are built from the current mode.
     * Types this code doesn't know are left out.
     */
    @Test
    fun `the display's HDR types are read whatever the API level gives`() {
        // The Shield (API 30): Dolby Vision and HDR10.
        assertEquals(setOf(Hdr.DolbyVision, Hdr.Hdr10), DisplayFacts.hdrOf(intArrayOf(1, 2)))
        // As API 26-28 give them: no type 4 yet.
        assertEquals(setOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hlg), DisplayFacts.hdrOf(intArrayOf(1, 2, 3)))
        // As API 29-33 give them, HDR10+ among them.
        assertEquals(setOf(Hdr.Hdr10, Hdr.Hdr10Plus), DisplayFacts.hdrOf(intArrayOf(2, 4)))
        // A later type, and a bad value.
        assertEquals(setOf(Hdr.Hdr10), DisplayFacts.hdrOf(intArrayOf(2, 5, -1)))
        // An SDR screen: an empty list, or no capabilities at all (Display.isHdr says no HDR then).
        assertEquals(emptySet(), DisplayFacts.hdrOf(intArrayOf()))
        assertEquals(emptySet(), DisplayFacts.hdrOf(null))
    }

    @Test
    fun `the log line says the output, the largest mode, the height ranked for and the HDR`() {
        assertEquals(
            "Display: 1920x1080 output, largest mode 3840x2160: 1080p; HDR: Dolby Vision, HDR10",
            facts(output = fullHd, largest = uhd, hdr = setOf(Hdr.Hdr10, Hdr.DolbyVision)).toString(),
        )
        assertEquals(
            "Display: 3840x2160 output (the TV's display-size property), largest mode 1920x1080: 2160p; HDR: none",
            facts(output = uhd, largest = fullHd, fromTv = true).toString(),
        )
        assertEquals(
            "Display: output unknown, largest mode unknown, window 1080x2400: 1080p; HDR: unknown",
            facts(output = null, largest = null, window = ModeSize(1080, 2400), hdr = null).toString(),
        )
    }

    @Test
    fun `facts read alike are equal, so the loader logs only a change`() {
        assertEquals(facts(output = uhd, largest = uhd, hdr = setOf(Hdr.Hdr10)), facts(output = uhd, largest = uhd, hdr = setOf(Hdr.Hdr10)))
        assertNull(facts(output = uhd, largest = uhd, hdr = null).hdr)
    }

    /**
     * TV-5's TCR-1: equal facts let auto-advance hand its prefetched search to the play. A TV matching a film's frame rate,
     * or a phone's adaptive refresh, mustn't make them unequal, nor write a log line: the rate File details show is read
     * beside the facts ([DisplayDetails]), never in them.
     */
    @Test
    fun `facts read from a display that differ only in its refresh rate are equal, the rate kept beside them`() {
        val film = DisplayDetails.of(true, 30, { null }, fullHd, readings(refreshRate = 23.976025f))
        val tv = DisplayDetails.of(true, 30, { null }, fullHd, readings(refreshRate = 59.94006f))
        assertEquals(film.facts, tv.facts)
        assertEquals(film.facts.hashCode(), tv.facts.hashCode())
        assertEquals(film.facts.toString(), tv.facts.toString())
        assertEquals(read(readings(refreshRate = 23.976025f)), film.facts)
        assertEquals(23.976025f to 59.94006f, film.refreshRate to tv.refreshRate)
    }

    /** A phone's window turns with it; the display's modes don't. The window counts only where the height comes from it. */
    @Test
    fun `facts that differ only in a window the height doesn't come from are equal`() {
        val portrait = facts(output = ModeSize(1080, 2400), largest = ModeSize(1080, 2400), window = ModeSize(1080, 2400), tv = false)
        val landscape = facts(output = ModeSize(1080, 2400), largest = ModeSize(1080, 2400), window = ModeSize(2400, 1080), tv = false)
        assertEquals(portrait, landscape)
        assertEquals(portrait.hashCode(), landscape.hashCode())
        assertEquals(facts(output = null, largest = uhd, window = fullHd), facts(output = null, largest = uhd, window = ModeSize(1080, 1920)))
        // With nothing else known the window is the height: another one is another display.
        assertNotEquals(facts(output = null, largest = null, window = fullHd), facts(output = null, largest = null, window = ModeSize(1280, 720)))
    }
}

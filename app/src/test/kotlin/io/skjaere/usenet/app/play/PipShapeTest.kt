package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.play.PipShape.Ratio
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PipShapeTest {
    /** A 20:9 phone in landscape, the player filling it. */
    private val screen = Area(0, 0, 2400, 1080)

    @Test
    fun `the window takes the picture's shape, as whole numbers in their lowest terms`() {
        assertEquals(Ratio(16, 9), PipShape.aspect(1920, 1080, 1f))
        assertEquals(Ratio(16, 9), PipShape.aspect(160, 90, 1f))
        assertEquals(Ratio(4, 3), PipShape.aspect(1440, 1080, 1f))
        assertEquals(Ratio(239, 100), PipShape.aspect(2390, 1000, 1f), "2.39:1 itself")
        // An 8K picture's numbers stay in an Int as they are worked out.
        assertEquals(Ratio(16, 9), PipShape.aspect(7680, 4320, 1f))
    }

    /** Anamorphic video: its pixels are wider than tall, and the picture as wide as they make it. */
    @Test
    fun `the pixels' own shape counts`() {
        assertEquals(Ratio(16, 9), PipShape.aspect(1440, 1080, 4f / 3))
        assertEquals(Ratio(16, 9), PipShape.aspect(720, 576, 64f / 45))
        assertEquals(Ratio(4, 3), PipShape.aspect(720, 480, 8f / 9))
    }

    /** Android allows 2.39:1 at the widest and 1:2.39 at the narrowest, and throws for any other: a scope film's 2.40:1 included. */
    @Test
    fun `wider or narrower than Android allows, it is the widest or narrowest it allows`() {
        assertEquals(Ratio(239, 100), PipShape.aspect(1920, 800, 1f), "1920x800, 2.40:1")
        assertEquals(Ratio(239, 100), PipShape.aspect(3840, 1600, 1f), "3840x1600")
        assertEquals(Ratio(239, 100), PipShape.aspect(1920, 696, 1f), "2.76:1")
        assertEquals(Ratio(100, 239), PipShape.aspect(1080, 2640, 1f), "a tall clip, 0.41:1")
        assertEquals(Ratio(239, 100), PipShape.aspect(1440, 600, 4f / 3), "2.4:1 as its pixels make it")
    }

    /** media3 turns a rotated video itself and reports its size as it shows (VideoSize: no rotation is left to apply). */
    @Test
    fun `a portrait video keeps its portrait shape`() {
        assertEquals(Ratio(9, 16), PipShape.aspect(1080, 1920, 1f))
    }

    /** The window then keeps Android's default shape until the size is known. */
    @Test
    fun `no shape while the size is unknown`() {
        assertNull(PipShape.aspect(0, 0, 1f))
        assertNull(PipShape.aspect(1920, 0, 1f))
        assertNull(PipShape.aspect(0, 1080, 1f))
        assertNull(PipShape.aspect(1920, 1080, 0f))
        assertNull(PipShape.aspect(1920, 1080, Float.NaN))
    }

    /** media3's content frame keeps Fit's shape in every zoom mode (only the surface is scaled), so it is where the picture is. */
    @Test
    fun `the window opens from the picture as Fit lays it out`() {
        val frame = Area(240, 0, 2160, 1080)
        assertEquals(frame, PipShape.hint(frame, screen, Ratio(16, 9)))
        assertEquals(frame, PipShape.hint(frame, screen, aspect = null), "the shape not known yet")
    }

    /** A 2.76:1 film shows Fit's bars inside the 2.39:1 window: the window opens from the picture with as much of them. */
    @Test
    fun `for a picture wider than the window, it opens from the picture with the bars the window shows`() {
        // 2400 wide at 2.76:1, centred: 870 tall from 105. At 2.39:1, 1004 tall about the same middle.
        assertEquals(Area(0, 38, 2400, 1042), PipShape.hint(Area(0, 105, 2400, 975), screen, Ratio(239, 100)))
        // As tall as the screen at 0.3:1, 324 wide; at 1:2.39, 452 wide.
        assertEquals(Area(974, 0, 1426, 1080), PipShape.hint(Area(1038, 0, 1362, 1080), screen, Ratio(100, 239)))
    }

    @Test
    fun `never outside the player`() {
        val short = Area(0, 0, 2400, 900)
        assertEquals(short, PipShape.hint(Area(0, 15, 2400, 885), short, Ratio(239, 100)))
    }

    @Test
    fun `no hint before the picture is laid out`() {
        assertNull(PipShape.hint(Area(0, 0, 0, 0), screen, Ratio(16, 9)))
        assertNull(PipShape.hint(Area(240, 0, 2160, 0), screen, Ratio(16, 9)))
    }
}

package io.skjaere.usenet.app.play

import io.skjaere.usenet.data.AspectMode
import io.skjaere.usenet.data.PlaybackPrefs
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoZoomTest {
    /** A 16:9 picture on a 20:9 phone: Fit lays it out at 1920×1080 in the 2400×1080 screen, with bars at the sides. */
    private val wide = PictureFrame(1920, 1080, 2400, 1080)
    /** A zoom well away from Fit and Fill screen, for a pinch that starts from neither. */
    private val zoomed = VideoZoom.zoom(2f)
    /** The emulator's phone held with its punch-hole camera on the left: a cutout 128 px deep there, the camera in the middle of the edge. */
    private val cameraLeft = Cutout(Sides(left = 128), listOf(Area(0, 470, 128, 588)))
    /** [wide] on that phone. */
    private val besideLeft = PictureFrame(1920, 1080, 2400, 1080, cameraLeft)

    @Test
    fun `without a camera beside the picture the button goes Fit, Fill screen and back to Fit`() {
        val presses = generateSequence(VideoZoom()) { it.next(camera = false) }.take(4).map { it.mode }.toList()
        assertEquals(listOf(AspectMode.Fit, AspectMode.Fill, AspectMode.Fit, AspectMode.Fill), presses)
    }

    @Test
    fun `with a camera beside the picture the button goes Fit, Fill screen, Fill beside camera and back to Fit`() {
        val presses = generateSequence(VideoZoom()) { it.next(camera = true) }.take(5).map { it.mode }.toList()
        assertEquals(listOf(AspectMode.Fit, AspectMode.Fill, AspectMode.FillBesideCamera, AspectMode.Fit, AspectMode.Fill), presses)
    }

    @Test
    fun `every mode keeps the picture's shape, scaling it alike across and down`() {
        val frames = listOf(wide, besideLeft, PictureFrame(1440, 1080, 2400, 1080), PictureFrame(608, 1080, 2400, 1080), PictureFrame(1080, 608, 1080, 2400))
        AspectMode.entries.forEach { mode ->
            frames.forEach { frame ->
                val (across, down) = VideoZoom(mode, 1.6f).scales(frame)
                assertEquals(across, down, "$mode in $frame")
            }
        }
    }

    @Test
    fun `from a pinch's zoom the button goes on to Fill screen, whichever side of it the zoom is`() {
        listOf(true, false).forEach { camera ->
            assertEquals(VideoZoom(AspectMode.Fill), VideoZoom.zoom(1.1f).next(camera))
            assertEquals(VideoZoom(AspectMode.Fill), VideoZoom.zoom(2.5f).next(camera))
        }
    }

    @Test
    fun `each mode has its label, and the button says the mode and what a press does`() {
        val modes = listOf(VideoZoom(), VideoZoom(AspectMode.Fill), VideoZoom(AspectMode.FillBesideCamera), VideoZoom.zoom(1.6f))
        assertEquals(listOf("Fit", "Fill screen", "Fill beside camera", "Zoom"), modes.map { it.label })
        assertEquals(
            listOf(
                "Aspect: Fit. Switch to Fill screen",
                "Aspect: Fill screen. Switch to Fill beside camera",
                "Aspect: Fill beside camera. Switch to Fit",
                "Aspect: Zoom. Switch to Fill screen",
            ),
            modes.map { it.description(camera = true) },
        )
        // Without a camera Fill screen goes back to Fit, and Fill beside camera shows as Fill screen.
        assertEquals(
            listOf(
                "Aspect: Fit. Switch to Fill screen",
                "Aspect: Fill screen. Switch to Fit",
                "Aspect: Fill screen. Switch to Fit",
                "Aspect: Zoom. Switch to Fill screen",
            ),
            modes.map { it.description(camera = false) },
        )
    }

    @Test
    fun `a Fill beside camera kept from a screen with a camera shows as Fill screen without one, and stays the choice`() {
        val kept = VideoZoom.of(PlaybackPrefs(aspectMode = AspectMode.FillBesideCamera.name))
        assertEquals(VideoZoom(AspectMode.FillBesideCamera), kept)
        assertEquals(VideoZoom(AspectMode.Fill), kept.shownAs(camera = false))
        assertEquals("Fill screen", kept.shownAs(camera = false).label)
        assertEquals(kept, kept.shownAs(camera = true))
        assertEquals(VideoZoom(), kept.next(camera = false))
        // Sized as Fill screen there, and as itself where the camera is.
        assertEquals(VideoZoom(AspectMode.Fill).scales(wide), kept.scales(wide))
        assertEquals(0f to 0f, kept.shift(wide))
        assertNull(kept.clip(wide))
        assertEquals(2272f / 1920 to 2272f / 1920, kept.scales(besideLeft))
        // A pinch that hardly moves leaves it the choice; it isn't taken for Fill screen.
        assertEquals(kept, kept.afterPinch(wide.fill, wide))
        assertEquals(kept, kept.afterPinch(wide.fill * 1.05f, wide))
        // The other modes are as they were wherever they are.
        AspectMode.entries.filter { it != AspectMode.FillBesideCamera }.map { VideoZoom(it, 1.6f) }.forEach { z ->
            listOf(true, false).forEach { camera -> assertEquals(z, z.shownAs(camera)) }
        }
    }

    // The room beside the camera.

    @Test
    fun `the camera keeps the picture out of a strip along the edge it sits on, as deep as it reaches`() {
        assertEquals(Sides(left = 128), cameraLeft.strips(2400, 1080))
        // Turned the other way round: on the right.
        assertEquals(Sides(right = 128), Cutout(Sides(right = 128), listOf(Area(2272, 492, 2400, 610))).strips(2400, 1080))
        // Upright, at the top; a safe inset deeper than the camera (padded to the status bar, say) crops no more than the camera needs.
        assertEquals(Sides(top = 128), Cutout(Sides(top = 136), listOf(Area(470, 0, 588, 128))).strips(1080, 2400))
        // A notch at the top and one at the bottom.
        assertEquals(Sides(top = 90, bottom = 100), Cutout(Sides(top = 90, bottom = 110), listOf(Area(400, 0, 680, 90), Area(400, 2300, 680, 2400))).strips(1080, 2400))
        // A camera reported past its safe inset is kept to the inset.
        assertEquals(Sides(left = 100), Cutout(Sides(left = 100), listOf(Area(0, 470, 128, 588))).strips(2400, 1080))
    }

    @Test
    fun `a camera outside the window, beside it in split screen, gives no strip`() {
        // A tablet's notch in the middle of its top edge, 2560 px wide: Android gives a window that
        // reaches that edge the cutout, its rect moved into the window's px but not cut to it.
        val notch = Area(1180, 0, 1380, 60)
        fun inWindowFrom(left: Int) = Cutout(Sides(top = 60), listOf(Area(notch.left - left, 0, notch.right - left, 60)))
        // A window 1000 px wide on the left, and one from 1400 px on the right: the notch is in neither.
        assertEquals(Sides(), inWindowFrom(0).strips(1000, 1600))
        assertEquals(Sides(), inWindowFrom(1400).strips(1160, 1600))
        assertFalse(PictureFrame(1000, 562, 1000, 1600, inWindowFrom(0)).hasCamera)
        // The right half from 1280 px holds part of it.
        assertEquals(Sides(top = 60), inWindowFrom(1280).strips(1280, 1600))
    }

    @Test
    fun `without a camera there is no strip, curved edges alone included`() {
        assertEquals(Sides(), Cutout().strips(2400, 1080))
        assertTrue(Cutout().strips(2400, 1080).none)
        // A waterfall screen's curved edges give safe insets but no camera.
        assertEquals(Sides(), Cutout(Sides(left = 40, right = 40)).strips(2400, 1080))
        assertFalse(PictureFrame(1920, 1080, 2400, 1080, Cutout(Sides(left = 40, right = 40))).hasCamera)
    }

    @Test
    fun `Fill beside camera covers the room beside a camera on the left, centred in it`() {
        assertTrue(besideLeft.hasCamera)
        assertEquals(Area(128, 0, 2400, 1080), besideLeft.besideCamera)
        val z = VideoZoom(AspectMode.FillBesideCamera)
        assertEquals(2272f / 1920 to 2272f / 1920, z.scales(besideLeft))
        assertEquals(64f to 0f, z.shift(besideLeft))
        // It just meets the strip: nothing to cut there. Its top and bottom go past the screen's edges.
        assertEquals(Area(0, 0, 1920, 1080), z.clip(besideLeft))
        assertShows(Area(128, 0, 2400, 1080), z, besideLeft)
    }

    @Test
    fun `Fill beside camera follows the camera to the right and to the top`() {
        val right = PictureFrame(1920, 1080, 2400, 1080, Cutout(Sides(right = 128), listOf(Area(2272, 492, 2400, 610))))
        val z = VideoZoom(AspectMode.FillBesideCamera)
        assertEquals(Area(0, 0, 2272, 1080), right.besideCamera)
        assertEquals(-64f to 0f, z.shift(right))
        assertShows(Area(0, 0, 2272, 1080), z, right)
        // Upright: a 16:9 picture laid out 1080×608, under a camera at the top.
        val top = PictureFrame(1080, 608, 1080, 2400, Cutout(Sides(top = 136), listOf(Area(470, 0, 588, 128))))
        assertEquals(Area(0, 128, 1080, 2400), top.besideCamera)
        assertEquals(2272f / 608 to 2272f / 608, z.scales(top))
        assertEquals(0f to 64f, z.shift(top))
        assertShows(Area(0, 128, 1080, 2400), z, top)
    }

    @Test
    fun `a picture wider than the room beside the camera is cropped at the strip, not shown in it`() {
        // 2.39:1, laid out by Fit across the screen's width: Fill beside camera's scale is Fill screen's, the picture moved off the camera.
        val cinema = PictureFrame(2400, 1004, 2400, 1080, cameraLeft)
        val z = VideoZoom(AspectMode.FillBesideCamera)
        assertEquals(cinema.fill to cinema.fill, z.scales(cinema))
        assertEquals(64f to 0f, z.shift(cinema))
        // Cut where it meets the strip (144 of its 2400 px, scaled, past the strip's edge), never short of it.
        assertEquals(Area(144, 0, 2400, 1004), z.clip(cinema))
        assertShows(Area(128, 0, 2400, 1080), z, cinema)
    }

    @Test
    fun `without a camera the room beside it is the whole screen and Fill beside camera is Fill screen`() {
        assertFalse(wide.hasCamera)
        assertEquals(Area(0, 0, 2400, 1080), wide.besideCamera)
        assertEquals(wide.fill, wide.besideCameraFill)
        // A strip deeper than the window leaves no room: no camera to keep clear of either.
        assertFalse(PictureFrame(90, 50, 100, 50, Cutout(Sides(left = 128), listOf(Area(0, 0, 128, 40)))).hasCamera)
    }

    @Test
    fun `only Fill beside camera moves and crops the picture`() {
        listOf(VideoZoom(), VideoZoom(AspectMode.Fill), VideoZoom.zoom(1.6f)).forEach { z ->
            assertEquals(0f to 0f, z.shift(besideLeft), "$z")
            assertNull(z.clip(besideLeft), "$z")
        }
        assertEquals(1.25f to 1.25f, VideoZoom(AspectMode.Fill).scales(besideLeft))
    }

    @Test
    fun `Fit leaves the picture as laid out, Fill screen covers the screen in shape`() {
        assertEquals(1f to 1f, VideoZoom().scales(wide))
        assertEquals(1.25f to 1.25f, VideoZoom(AspectMode.Fill).scales(wide))
        assertEquals(1.6f to 1.6f, VideoZoom.zoom(1.6f).scales(wide))
        // A 4:3 picture on the same screen: Fill screen crops its top and bottom.
        val square = PictureFrame(1440, 1080, 2400, 1080)
        assertEquals(2400f / 1440 to 2400f / 1440, VideoZoom(AspectMode.Fill).scales(square))
    }

    @Test
    fun `a picture of the screen's shape looks the same in every mode`() {
        val same = PictureFrame(1920, 1080, 1920, 1080)
        assertEquals(1f, same.fill)
        listOf(VideoZoom(AspectMode.Fill), VideoZoom(AspectMode.FillBesideCamera)).forEach { assertEquals(1f to 1f, it.scales(same)) }
    }

    @Test
    fun `a saved zoom is held to its range as it is shown`() {
        assertEquals(VideoZoom.MAX_SCALE to VideoZoom.MAX_SCALE, VideoZoom.zoom(9f).scales(wide))
        // A tall picture needs more than the maximum to fill the screen; a zoom reaches that far.
        val tall = PictureFrame(608, 1080, 2400, 1080)
        assertEquals(2400f / 608, tall.fill)
        assertEquals(tall.fill to tall.fill, VideoZoom.zoom(9f).scales(tall))
    }

    @Test
    fun `before the picture is laid out every mode shows it as it is`() {
        val unknown = PictureFrame(0, 0, 2400, 1080, cameraLeft)
        assertEquals(1f, unknown.fill)
        assertEquals(1f, unknown.besideCameraFill)
        AspectMode.entries.forEach {
            assertEquals(1f to 1f, VideoZoom(it, 2f).scales(unknown))
            assertEquals(0f to 0f, VideoZoom(it, 2f).shift(unknown))
            assertNull(VideoZoom(it, 2f).clip(unknown))
        }
    }

    @Test
    fun `a pinch starts from the scale showing`() {
        assertEquals(listOf(1f, 1.25f, 1.6f), listOf(VideoZoom(), VideoZoom(AspectMode.Fill), VideoZoom.zoom(1.6f)).map { it.pinchStart(wide) })
        assertEquals(2272f / 1920, VideoZoom(AspectMode.FillBesideCamera).pinchStart(besideLeft))
        assertEquals(1.25f, VideoZoom(AspectMode.FillBesideCamera).pinchStart(wide))
    }

    @Test
    fun `a pinch ending close to Fit snaps to Fit`() {
        listOf(1f, 1.03f, 1.07f).forEach { assertEquals(VideoZoom(), zoomed.afterPinch(it, wide), "ended at $it") }
    }

    @Test
    fun `a pinch ending close to Fill screen snaps to Fill screen, from either side`() {
        listOf(1.17f, 1.25f, 1.33f).forEach { assertEquals(VideoZoom(AspectMode.Fill), VideoZoom().afterPinch(it, wide), "ended at $it") }
    }

    @Test
    fun `a pinch ending between Fit and Fill screen, or past Fill screen, is a zoom of its own`() {
        assertEquals(VideoZoom.zoom(1.12f), VideoZoom().afterPinch(1.12f, wide))
        assertEquals(VideoZoom.zoom(2f), VideoZoom().afterPinch(2f, wide))
    }

    @Test
    fun `a pinch ending beyond the maximum stays at the maximum`() {
        assertEquals(VideoZoom.zoom(VideoZoom.MAX_SCALE), VideoZoom().afterPinch(5f, wide))
        // Where Fill screen is past it, the maximum is Fill screen.
        assertEquals(VideoZoom(AspectMode.Fill), VideoZoom().afterPinch(6f, PictureFrame(608, 1080, 2400, 1080)))
    }

    @Test
    fun `a pinch ending below Fit is Fit`() {
        listOf(0.4f, 0.9f, Float.NaN).forEach { assertEquals(VideoZoom(), zoomed.afterPinch(it, wide), "ended at $it") }
    }

    @Test
    fun `a pinch that ends about where it started leaves the picture as it was`() {
        listOf(VideoZoom(), VideoZoom(AspectMode.Fill), zoomed).forEach { z ->
            val start = z.pinchStart(wide)
            listOf(start, start * 1.05f, start / 1.05f).forEach { end -> assertEquals(z, z.afterPinch(end, wide), "$z ended at $end") }
        }
    }

    @Test
    fun `a pinch ending close to where it started and to Fill screen goes to the nearer`() {
        // 1.36 is a zoom of its own, just past Fill screen's reach.
        val nearFill = VideoZoom.zoom(1.36f)
        assertEquals(VideoZoom(AspectMode.Fill), nearFill.afterPinch(1.27f, wide))
        assertEquals(nearFill, nearFill.afterPinch(1.33f, wide))
    }

    @Test
    fun `beside a camera a pinch ending close to Fill beside camera snaps to it, and close to Fill screen to that`() {
        // Fill beside camera's scale is 1.18, Fill screen's 1.25.
        listOf(1.13f, 1.17f, 1.19f, 1.2f).forEach { assertEquals(VideoZoom(AspectMode.FillBesideCamera), VideoZoom().afterPinch(it, besideLeft), "ended at $it") }
        listOf(1.24f, 1.3f).forEach { assertEquals(VideoZoom(AspectMode.Fill), zoomed.afterPinch(it, besideLeft), "ended at $it") }
        assertEquals(VideoZoom(), zoomed.afterPinch(1.05f, besideLeft))
        // Without one there is nothing to snap to there: 1.19 is close to Fill screen, 1.13 a zoom of its own.
        assertEquals(VideoZoom(AspectMode.Fill), VideoZoom().afterPinch(1.19f, wide))
        assertEquals(VideoZoom.zoom(1.13f), VideoZoom().afterPinch(1.13f, wide))
    }

    @Test
    fun `a pinch from Fill beside camera that ends about where it started leaves it`() {
        val z = VideoZoom(AspectMode.FillBesideCamera)
        val start = z.pinchStart(besideLeft)
        listOf(start, start * 1.02f, start / 1.02f).forEach { end -> assertEquals(z, z.afterPinch(end, besideLeft), "ended at $end") }
        assertEquals(VideoZoom(AspectMode.Fill), z.afterPinch(1.25f, besideLeft))
    }

    @Test
    fun `growing from Fill beside camera, a pinch opens no bars, the picture moving into the strip, all of it by Fill screen's scale`() {
        val z = VideoZoom(AspectMode.FillBesideCamera)
        val start = besideLeft.besideCameraFill
        assertEquals(z.look(besideLeft), z.pinched(start, besideLeft))
        // On the way it covers the room beside the camera, its right edge on the screen's, its left going into the strip.
        val lefts = (0..10).map { start + (1.25f - start) * it / 10 }.map { s ->
            val (left, top, right, bottom) = shownAt(z.pinched(s, besideLeft), besideLeft)
            assertTrue(left <= 128.01f && top <= 0.01f && right >= 2399.99f && bottom >= 1079.99f, "at $s it shows at ${listOf(left, top, right, bottom)}")
            left
        }
        assertTrue(lefts.zipWithNext().all { (a, b) -> b < a }, "$lefts")
        // Half way, half the strip is left, black.
        val half = z.pinched((start + 1.25f) / 2, besideLeft)
        assertTrue(half.black)
        assertShows(Area(64, 0, 2400, 1080), half, besideLeft)
        // At Fill screen's scale and past it, as a zoom: centred, nothing cut.
        listOf(1.25f, 2f).forEach { s -> assertEquals(VideoZoom.zoom(s).look(besideLeft).copy(black = true), z.pinched(s, besideLeft)) }
    }

    @Test
    fun `a pinch from Fill beside camera shrinking toward Fit moves the picture back to the screen's centre, on black`() {
        val z = VideoZoom(AspectMode.FillBesideCamera)
        val start = besideLeft.besideCameraFill
        // Half way to Fit's scale: moved half as far, half the strip kept clear.
        val half = z.pinched((1 + start) / 2, besideLeft)
        assertEquals(32f, half.shift.first, 0.01f)
        assertNotNull(half.clip)
        assertShows(Area(184, 0, 2280, 1080), half, besideLeft)
        // At Fit's, where Fit puts it; its bars black till the pinch ends, as they were on the way, not the window's own.
        assertEquals(VideoZoom().look(besideLeft).copy(black = true), z.pinched(1f, besideLeft))
        assertFalse(VideoZoom().look(besideLeft).black)
    }

    @Test
    fun `a pinch from Fill beside camera with a picture wider than the room beside it glides into the strip`() {
        val cinema = PictureFrame(2400, 1004, 2400, 1080, cameraLeft)
        val z = VideoZoom(AspectMode.FillBesideCamera)
        val start = cinema.besideCameraFill
        assertEquals(z.look(cinema), z.pinched(start, cinema))
        // It covers the screen already: half the glide on, it shows in half the strip; at its end, in all of it.
        assertShows(Area(64, 0, 2400, 1080), z.pinched(start * (1 + VideoZoom.GLIDE / 2), cinema), cinema)
        val end = start * (1 + VideoZoom.GLIDE)
        assertEquals(VideoZoom.zoom(end).look(cinema).copy(black = true), z.pinched(end, cinema))
    }

    @Test
    fun `a pinch from any other mode, or from a kept Fill beside camera without a camera, shows as a zoom`() {
        listOf(VideoZoom(), VideoZoom(AspectMode.Fill), zoomed).forEach { z ->
            listOf(1f, 1.2f, 2f, 9f).forEach { s -> assertEquals(VideoZoom.zoom(s).look(besideLeft), z.pinched(s, besideLeft), "$z at $s") }
        }
        listOf(1f, 1.2f, 2f).forEach { s -> assertEquals(VideoZoom.zoom(s).look(wide), VideoZoom(AspectMode.FillBesideCamera).pinched(s, wide), "at $s") }
    }

    @Test
    fun `where Fill beside camera and Fill screen have one scale, a pinch ending there is Fill screen`() {
        val cinema = PictureFrame(2400, 1004, 2400, 1080, cameraLeft)
        assertEquals(cinema.fill, cinema.besideCameraFill)
        assertEquals(VideoZoom(AspectMode.Fill), zoomed.afterPinch(cinema.fill, cinema))
    }

    @Test
    fun `where Fit and Fill screen are close, a pinch snaps to the nearer`() {
        val near = PictureFrame(1920, 1080, 2040, 1080)
        assertEquals(VideoZoom(), zoomed.afterPinch(1.02f, near))
        assertEquals(VideoZoom(AspectMode.Fill), zoomed.afterPinch(1.05f, near))
        // Where they are the same, Fit.
        assertEquals(VideoZoom(), zoomed.afterPinch(1.02f, PictureFrame(1920, 1080, 1920, 1080)))
    }

    @Test
    fun `the choice is kept in the playback preferences and read back from them`() {
        val prefs = PlaybackPrefs(autoPlayNext = false)
        assertEquals(VideoZoom(), VideoZoom.of(prefs))
        listOf(VideoZoom(AspectMode.Fill), VideoZoom(AspectMode.FillBesideCamera), VideoZoom.zoom(1.6f), VideoZoom()).forEach { z ->
            val saved = z.saveIn(prefs)
            assertEquals(z, VideoZoom.of(saved))
            assertEquals(prefs.copy(aspectMode = saved.aspectMode, zoomScale = saved.zoomScale), saved)
        }
        assertEquals(1f, VideoZoom(AspectMode.Fill).saveIn(prefs).zoomScale)
    }

    @Test
    fun `a saved zoom that can't be one reads as Fit`() {
        listOf(1f, 0.5f, Float.NaN).forEach { s ->
            assertEquals(VideoZoom(), VideoZoom.of(PlaybackPrefs(aspectMode = AspectMode.Zoom.name, zoomScale = s)), "saved at $s")
        }
        assertEquals(VideoZoom(AspectMode.Fill), VideoZoom.of(PlaybackPrefs(aspectMode = AspectMode.Fill.name, zoomScale = 2f)))
    }

    @Test
    fun `a Stretch kept by an earlier version, which changed the picture's shape, reads as Fit`() {
        val kept = VideoZoom.of(PlaybackPrefs(aspectMode = "Stretch"))
        assertEquals(VideoZoom(), kept)
        assertEquals("Aspect: Fit. Switch to Fill screen", kept.description(camera = false))
        assertEquals(1f to 1f, kept.scales(wide))
    }

    /** The part of [frame]'s view where [z] shows the picture is [expected], give or take a pixel, and never past it. */
    private fun assertShows(expected: Area, z: VideoZoom, frame: PictureFrame) = assertShows(expected, z.look(frame), frame, "$z")

    private fun assertShows(expected: Area, look: Look, frame: PictureFrame, what: String = "$look") {
        val shown = shownAt(look, frame)
        val want = listOf(expected.left, expected.top, expected.right, expected.bottom).map(Int::toFloat)
        assertTrue(shown.zip(want).all { (a, b) -> abs(a - b) <= 1f }, "$what shows at $shown, not $want")
        assertTrue(shown[0] >= want[0] - 0.01f && shown[1] >= want[1] - 0.01f && shown[2] <= want[2] + 0.01f && shown[3] <= want[3] + 0.01f, "$what shows at $shown, past $want")
    }

    /**
     * Where [look] shows the picture in [frame]'s view, left, top, right and bottom: the surface
     * laid out by Fit in the view's centre, its clip scaled about that centre and moved by its
     * shift, as the player does; on screen, so no further than the view's edges.
     */
    private fun shownAt(look: Look, frame: PictureFrame): List<Float> {
        val (sx, sy) = look.scales
        val (dx, dy) = look.shift
        val clip = look.clip ?: Area(0, 0, frame.fitWidth, frame.fitHeight)
        fun x(local: Int) = (frame.viewWidth / 2f + dx + (local - frame.fitWidth / 2f) * sx).coerceIn(0f, frame.viewWidth.toFloat())
        fun y(local: Int) = (frame.viewHeight / 2f + dy + (local - frame.fitHeight / 2f) * sy).coerceIn(0f, frame.viewHeight.toFloat())
        return listOf(x(clip.left), y(clip.top), x(clip.right), y(clip.bottom))
    }
}

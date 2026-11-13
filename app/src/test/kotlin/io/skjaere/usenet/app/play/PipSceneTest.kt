package io.skjaere.usenet.app.play

import io.skjaere.usenet.data.AspectMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipSceneTest {
    private val fullScreen = PipScene(pinned = false)
    private val window = PipScene(pinned = true)
    private val fill = VideoZoom(AspectMode.Fill)

    @Test
    fun `full screen, the player is as it was left`() {
        assertTrue(fullScreen.controls, "media3's controls")
        assertTrue(fullScreen.overlays, "the overlays and their words")
        assertTrue(fullScreen.accessible, "TalkBack")
        assertEquals(fill, fullScreen.zoom(fill), "the size chosen")
        assertNull(fullScreen.subtitles, "the user's subtitles")
        assertFalse(fullScreen.unseen)
    }

    /** media3 would bring its controls up in the window as the play pauses or ends, and over a player made again in it. */
    @Test
    fun `in the window only the picture shows`() {
        listOf(window, window.copy(panel = true), window.copy(failed = true)).forEach { scene ->
            assertFalse(scene.controls, "media3's controls, $scene")
            assertFalse(scene.overlays, "the overlays and their words, $scene")
            assertFalse(scene.accessible, "TalkBack, which reaches the window through Android's menu alone, $scene")
        }
    }

    /** The window has the picture's shape: Fit fills it, where Fill or a zoom would cut the picture. */
    @Test
    fun `in the window the picture is in Fit, and the size chosen comes back with the player`() {
        listOf(fill, VideoZoom(AspectMode.FillBesideCamera), VideoZoom.zoom(2f)).forEach { chosen ->
            assertEquals(VideoZoom(), window.zoom(chosen), "$chosen in the window")
            assertEquals(chosen, fullScreen.zoom(chosen), "$chosen full screen")
        }
    }

    /** PipWindowShapes: at Android's 5.33 % and 8 % a 121 dp tall window would have 6.4 dp text. */
    @Test
    fun `in the window the subtitles are 9 percent of the picture's height, 4 percent up from its bottom`() {
        assertEquals(PipScene.Subtitles(textFraction = 0.09f, bottomFraction = 0.04f), window.subtitles)
    }

    /** One owner of the controls (M1): a side panel or "Playback stopped" keeps them down full screen too. */
    @Test
    fun `the controls only full screen with no side panel and no Playback stopped`() {
        assertFalse(fullScreen.copy(panel = true).controls, "under a side panel")
        assertFalse(fullScreen.copy(failed = true).controls, "with Playback stopped up")
        assertFalse(fullScreen.copy(panel = true, failed = true).controls, "both")
    }

    /** L12, PipWindowStates: "Playback stopped" on black in the window, the panel as it is expanded. */
    @Test
    fun `a failure is one line in the window and the panel full screen`() {
        val failedInTheWindow = window.copy(failed = true)
        assertTrue(failedInTheWindow.stoppedLine)
        assertFalse(failedInTheWindow.failurePanel)
        val expanded = failedInTheWindow.copy(pinned = false)
        assertTrue(expanded.failurePanel)
        assertFalse(expanded.stoppedLine)
        assertFalse(window.stoppedLine, "no failure")
        assertFalse(fullScreen.failurePanel, "no failure")
    }

    /**
     * The next-episode card, the missing-parts prompt and the slow-stream banner can't be seen in the window, nor under a
     * side panel: the card's countdown, the prompt's time to answer and the banner's count hold (W1, M3).
     */
    @Test
    fun `the overlays wait while they can't be seen`() {
        assertTrue(window.unseen, "in the window")
        assertTrue(fullScreen.copy(panel = true).unseen, "under a side panel")
        assertFalse(fullScreen.unseen, "full screen")
        assertFalse(fullScreen.copy(failed = true).unseen, "with Playback stopped up, which holds them its own way")
    }
}

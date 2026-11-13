package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ShieldFile
import io.skjaere.usenet.app.play.FileDetails
import io.skjaere.usenet.app.play.FileDetailsPanel
import io.skjaere.usenet.app.play.FileDetailsSection
import io.skjaere.usenet.app.ui.AppColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * File details on a TV (forced on the phone emulator, 960 x 540 dp) with what a Shield would show: at 1x, 1.3x and 2x
 * every word inside the TV's safe area and whole, section by section as a remote's Down brings each into view; the
 * remote opens on the release, Down and Up go through the sections, the last keeping the focus, and Back closes the
 * panel. The focused section shows as the TV's focus does, a 3 dp cream ring (FdTvOpen). A debrid play's Service row
 * (L207) shows as whole as the providers' does.
 */
@RunWith(AndroidJUnit4::class)
class FileDetailsTvTest {
    private companion object {
        const val PRESSES = 16
    }


    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val closed = AtomicInteger()
    private var fontScale by mutableFloatStateOf(1f)

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private fun show(details: FileDetails = ShieldFile.details) {
        compose.setTvContent({ fontScale }) {
            // As PlayerActivity hosts it on a TV: inside the 48/27 dp safe area.
            FileDetailsPanel(details, onClose = { closed.incrementAndGet() }, safeArea = WindowInsets(48.dp, 27.dp, 48.dp, 27.dp))
        }
    }

    private val section = SemanticsMatcher.keyIsDefined(FileDetailsSection)

    /** The section the remote's focus is on; null for none. */
    private fun focused(): String? = compose.onAllNodes(section and isFocused()).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(FileDetailsSection)

    private fun press(key: Int): String? {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    private fun waitForFocus(on: String) {
        try {
            compose.waitUntil(5_000) { focused() == on }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never went to $on; it is on ${focused()}", e)
        }
    }

    @Test
    fun theRemoteGoesThroughEverySectionAndBackCloses() {
        show()
        waitForFocus("Release")
        val down = ShieldFile.sections.drop(1).map { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(ShieldFile.sections.drop(1), down)
        // The last keeps the focus; Up goes back.
        assertEquals("Source", press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("On this device", press(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(0, closed.get())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, closed.get())
    }

    /**
     * From the panel as it opens at [scale], Down after Down to the end: each section brought into view, one taller than
     * the panel's room a page at a time, and every word inside the safe area, whole, and shown at some point.
     */
    private fun walk(scale: Float, details: FileDetails = ShieldFile.details): Map<String, Boolean> {
        fontScale = scale
        show(details)
        waitForFocus("Release")
        val shown = mutableMapOf<String, Boolean>()
        val visited = mutableListOf<String?>("Release")
        fun check(on: String?) {
            assertEquals("at ${scale}x on $on", emptyList<String>(), compose.outsideTheSafeArea())
            assertEquals("at ${scale}x on $on", emptyList<String>(), compose.textsCut())
            compose.textsShownInFull().forEach { (words, whole) -> shown[words] = shown[words] == true || whole }
        }
        check("Release")
        // Enough for every page of every section; the last ones find the end.
        repeat(PRESSES) {
            val now = press(KeyEvent.KEYCODE_DPAD_DOWN)
            check(now)
            if (now != visited.last()) visited += now
        }
        assertEquals("at ${scale}x", ShieldFile.sections, visited)
        assertEquals("at ${scale}x", emptyList<String>(), shown.filterValues { !it }.keys.toList())
        return shown
    }

    /** A debrid play's Source (L207, DbPhoneFileDetails) at [scale]: the service in place of the providers, its line whole. */
    private fun walkFromService(scale: Float) {
        val shown = walk(scale, ShieldFile.fromService)
        listOf("Home Prowlarr", "Service", "Premiumize", "a cached torrent, streamed from the service").forEach { words ->
            assertTrue("at ${scale}x \"$words\" shown whole", shown[words] == true)
        }
        assertTrue("no providers at ${scale}x", shown.keys.none { "news.example.net" in it || it == "Providers" })
    }

    @Test
    fun at1xADebridPlaysServiceRowIsInsideTheSafeAreaAndWhole() {
        walkFromService(1f)
    }

    @Test
    fun at200PercentADebridPlaysServiceRowIsInsideTheSafeAreaAndWhole() {
        walkFromService(2f)
    }

    @Test
    fun at1xEveryWordIsInsideTheSafeAreaAndWhole() {
        walk(1f)
    }

    @Test
    fun at130PercentEveryWordIsInsideTheSafeAreaAndWhole() {
        walk(1.3f)
    }

    @Test
    fun at200PercentEveryWordIsInsideTheSafeAreaAndWhole() {
        walk(2f)
    }

    @Test
    fun theFocusedSectionShowsACreamRing() {
        show()
        waitForFocus("Release")
        compose.waitForIdle()
        val image = compose.onNode(section and isFocused()).captureToImage().toPixelMap()
        // Along its top edge, halfway across: the 3 dp ring, which a TV's focus grows a little past the section's bounds.
        val ring = (instrumentation.targetContext.resources.displayMetrics.density * 3).roundToInt()
        val cream = AppColors().text
        val edge = (0 until ring).map { y -> image[image.width / 2, y] }
        val close = edge.any { pixel -> listOf(cream.red to pixel.red, cream.green to pixel.green, cream.blue to pixel.blue).all { (e, a) -> abs(e - a) < 0.1f } }
        assertTrue("the focused section's top edge is $edge, not ${Color(cream.value)}", close)
    }
}

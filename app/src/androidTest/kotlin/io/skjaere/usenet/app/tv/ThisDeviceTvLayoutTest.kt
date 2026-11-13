package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ThisDeviceRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.platformDisplayRow
import io.skjaere.usenet.app.settings.ThisDeviceCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * This device in the TV shell (forced on the phone emulator, 960 x 540 dp) at 1x, 1.3x and 2x: opened with the Device
 * card focused, Down after Down to the end brings each card into view, one taller than the screen a page at a time (at
 * 2x), and every word is inside the TV's safe area, whole, and shown in full at some point.
 */
@RunWith(AndroidJUnit4::class)
class ThisDeviceTvLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val home = HomeContentShown()
    private var scale by mutableFloatStateOf(1f)
    private lateinit var nav: NavHostController

    @Before
    fun remote() {
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private val card = SemanticsMatcher.keyIsDefined(ThisDeviceCard)

    private fun focused(): String? = compose.onAllNodes(card and isFocused()).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(ThisDeviceCard)

    private fun press(key: Int): String? {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    /** From the page as it opens at [s], Down to the end: [CARDS] in order, every word inside the safe area, whole, shown; how many Downs paged. */
    private fun walk(s: Float): Int {
        scale = s
        compose.setTvContent({ scale }) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(ThisDeviceRoute) }
        try {
            compose.waitUntil(5_000) { focused() == "Device" }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("at ${s}x the focus never went to Device; it is on ${focused()}", e)
        }
        assertTrue(compose.onAllNodes(hasText("…"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val shown = mutableMapOf<String, Boolean>()
        val visited = mutableListOf("Device")
        var paged = 0
        fun check(on: String?) {
            assertEquals("at ${s}x on $on", emptyList<String>(), compose.outsideTheSafeArea())
            assertEquals("at ${s}x on $on", emptyList<String>(), compose.textsCut())
            compose.textsShownInFull().forEach { (words, whole) -> shown[words] = shown[words] == true || whole }
        }
        check("Device")
        repeat(PRESSES) {
            val now = press(KeyEvent.KEYCODE_DPAD_DOWN)
            check(now)
            if (now != visited.last()) visited += now ?: "(nothing)" else if (now != CARDS.last()) paged++
        }
        assertEquals("at ${s}x", CARDS, visited)
        // The title and the cards' headers are passed on the way down; the footnote is reached at the end.
        assertEquals("at ${s}x", emptyList<String>(), shown.filterValues { !it }.keys.toList())
        return paged
    }

    /**
     * The page reads as it opens, not once the screens' cross-fade (700 ms) is over: by the screens' own clock, within
     * [LOAD_MS] of the navigation (TvSharedScreensWalkTest's) the Output row has its refresh rate and the Device card
     * the focus, while the page is still fading in. The start's reads are done first, as they are long before anyone
     * opens the page; the page's own then run in real time beside the clock's frames.
     */
    @Test
    fun theFactsAndTheFocusComeAsThePageFadesIn() {
        val container = instrumentation.targetContext.container
        runBlocking {
            container.device.get()
            container.native.get()
        }
        container.memory
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitForIdle()
        val output = platformDisplayRow(instrumentation.targetContext).first()
        fun outputShown() = compose.onAllNodes(hasText(output)).fetchSemanticsNodes().isNotEmpty()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { nav.navigate(ThisDeviceRoute) }
            val opened = compose.mainClock.currentTime
            while (!(focused() == "Device" && outputShown())) {
                val ms = compose.mainClock.currentTime - opened
                assertTrue("$ms ms after opening: the focus on ${focused()}, Output \"$output\" shown: ${outputShown()}", ms <= LOAD_MS)
                Thread.sleep(16)
                compose.mainClock.advanceTimeByFrame()
            }
            val state = compose.runOnUiThread { nav.currentBackStackEntry?.lifecycle?.currentState }
            assertTrue("the page had faded in by then: $state", state != Lifecycle.State.RESUMED)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun at1xEveryWordIsInsideTheSafeAreaAndWhole() {
        assertEquals("at 1x every card fits: Down never pages", 0, walk(1f))
    }

    @Test
    fun at130PercentEveryWordIsInsideTheSafeAreaAndWhole() {
        walk(1.3f)
    }

    /** At 2x the Device card is taller than the screen: Down pages through it before going on. */
    @Test
    fun at200PercentACardTallerThanTheScreenPagesAndEveryWordIsWhole() {
        assertTrue("at 2x no card paged", walk(2f) > 0)
    }

    /**
     * And back up at 2x, from the end: Up comes into a card taller than the screen at its last page and pages up through
     * it, as Down pages down, so every word is shown in full on the way up too; and the cards in turn back to Device.
     */
    @Test
    fun at200PercentUpPagesBackThroughACardTallerThanTheScreenFromItsEnd() {
        walk(2f)
        val shown = mutableMapOf<String, Boolean>()
        fun look() = compose.textsShownInFull().forEach { (words, whole) -> shown[words] = shown[words] == true || whole }
        fun top(title: String) = compose.onNode(SemanticsMatcher.expectValue(ThisDeviceCard, title)).fetchSemanticsNode().positionInRoot.y
        val visited = mutableListOf(CARDS.last())
        var paged = 0
        look()
        repeat(PRESSES) {
            val before = visited.last().let { it to top(it) }
            val now = press(KeyEvent.KEYCODE_DPAD_UP)
            look()
            // A press that moved the card it stayed on paged; one at the top of the page did nothing.
            if (now != visited.last()) visited += now ?: "(nothing)" else if (abs(top(now) - before.second) > 1f) paged++
        }
        assertEquals("at 2x, up", CARDS.reversed(), visited)
        // Not the page's title, above Device: paging back up to Device's top stops within the pivot's band, which can
        // leave the title's top a few pixels in the TV's margin. The walk down sees it whole as the page opens.
        assertEquals("at 2x, never shown in full on the way up", emptyList<String>(), shown.filterValues { !it }.keys.toList() - "This device")
        assertTrue("at 2x no card paged on the way up", paged > 0)
    }

    private companion object {
        val CARDS = listOf("Device", "Display", "Video", "Audio", "yEnc decoding")
        /** Enough for every page of every card at 2x; the last find the end. */
        const val PRESSES = 24

        /** How soon an opened screen's data shows on the emulators (TvSharedScreensWalkTest's). */
        const val LOAD_MS = 400L
    }
}

package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On a TV the player's controls and overlays keep inside the TV safe area, 48 dp from the sides
 * and 27 dp from the top and bottom (TV-OV), where a TV has no bars or cutout to keep clear of:
 * the top bar, media3's controls, the label under the top bar, the skip button, where the
 * next-episode card and the "Playback stopped" panel go, and the audio and subtitles sheet's rows
 * and words (its background goes to the edge). Shown on the phone emulator as a TV, by
 * the debug builds' launch extra, with the chapters.mkv fixture (an intro at 5–25 s, see
 * SkipSegmentsDeviceTest) opened as an NZB file is.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerSafeAreaDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        instrumentation.setInTouchMode(true)
    }

    /** The fixture opened as an NZB file is, the player told it is on a TV. */
    private fun tvIntent() = Intent(context, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))
        .putExtra(FormFactor.EXTRA, "tv")

    @Test
    fun onATvTheControlsAndOverlaysKeepInsideTheSafeArea() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(tvIntent()).use { scenario ->
            waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            // In the intro, with the controls held up: the skip button shows with them.
            scenario.onActivity { a ->
                a.player!!.seekTo(INTRO_START_MS + 1_000)
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls in place, with Skip intro") {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar).translationY == 0f && skipRect(a) != null).takeIf { it } }
            }
            delay(500)
            val safe = scenario.read(::safeArea)
            // What can be focused or read: the top bar's buttons and titles, the controls' buttons, times and time bar.
            val shown = scenario.read { a ->
                views(a.window.decorView).filter { v -> v.isShown && v !is ViewGroup && (v.isFocusable || v is TextView && v.text.isNotEmpty()) }
                    .map { v -> describe(v) to windowRect(v) }
            }
            assertTrue("found only $shown", shown.size >= 8)
            shown.forEach { (what, r) -> assertTrue("$what $r is outside the safe area $safe", safe.holds(r)) }
            val skip = scenario.read(::skipRect)!!
            assertTrue("Skip intro $skip is outside the safe area $safe", safe.holds(skip))

            // Where the card, the missing-parts prompt, the slow-stream banner and the panel go, as the skip button: at least
            // 48 dp from the end and 27 dp from the bottom.
            scenario.read { a ->
                val density = a.resources.displayMetrics.density
                val overlays = views(a.window.decorView).filterIsInstance<ComposeView>().filter { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT }
                assertEquals(5, overlays.size)
                overlays.forEach { o ->
                    val p = o.layoutParams as FrameLayout.LayoutParams
                    assertTrue("an overlay sits ${p.rightMargin / density} dp from the end", p.rightMargin >= 48 * density - 1)
                    assertTrue("an overlay sits ${p.bottomMargin / density} dp from the bottom", p.bottomMargin >= 27 * density - 1)
                }
            }

            // The label under the top bar, as Right seeks with the controls hidden: past the intro, so the skip button doesn't take the key.
            scenario.onActivity { a -> a.player!!.seekTo(60_000) }
            waitFor("Skip intro to go") { scenario.read { a -> (skipRect(a) == null).takeIf { it } } }
            scenario.onActivity { a -> playerView(a).hideController() }
            waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
            delay(800)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            val label = waitFor("the label") { scenario.read { a -> labelView(a)?.takeIf { it.height > 0 }?.let(::windowRect) } }
            assertTrue("the label $label is outside the safe area $safe", safe.holds(label))
        }
    }

    @Test
    fun onATvTheAudioAndSubtitlesSheetKeepsInsideTheSafeArea() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(tvIntent()).use { scenario ->
            waitFor("the player to know the tracks", timeoutMs = 30_000) { scenario.read { it.player?.currentTracks?.takeUnless { t -> t.isEmpty } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                views(a.window.decorView).single { it.contentDescription == "Audio and subtitles" }.performClick()
            }
            waitFor("the sheet's rows") { scenario.read { a -> sheetShown(a).takeIf { shown -> shown.count { it.first == "a row" } >= 2 } } }
            delay(500)
            val safe = scenario.read(::safeArea)
            val shown = scenario.read(::sheetShown)
            assertTrue("found only $shown", shown.any { it.first == "Audio and subtitles" } && shown.any { it.first == "Off" })
            shown.forEach { (what, r) -> assertTrue("$what $r is outside the safe area $safe", safe.holds(r)) }
        }
    }

    /** What of the audio and subtitles sheet can be focused or read, and shows, with its bounds: "a row" for a row, else its words. */
    private fun sheetShown(activity: Activity): List<Pair<String, RectF>> =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Audio and subtitles" }?.let(::subtree).orEmpty().drop(1)
            .filter { n -> n.config.contains(SemanticsProperties.Focused) || n.config.contains(SemanticsProperties.Text) }
            .map { n -> n.words().joinToString(" ").ifEmpty { "a row" } to n.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) } }
            // Scrolled out of the panel, it doesn't show.
            .filter { (_, r) -> r.width() > 0 && r.height() > 0 }

    /** The window but for 48 dp at the sides and 27 dp at the top and bottom. */
    private fun safeArea(a: Activity): RectF {
        val density = a.resources.displayMetrics.density
        val window = a.window.decorView
        return RectF(48 * density, 27 * density, window.width - 48 * density, window.height - 27 * density)
    }

    private fun RectF.holds(other: RectF) = other.left >= left - 1 && other.top >= top - 1 && other.right <= right + 1 && other.bottom <= bottom + 1

    private fun describe(v: View) = listOfNotNull(
        v.javaClass.simpleName,
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull(),
        v.contentDescription?.toString(),
        (v as? TextView)?.text?.toString(),
    ).joinToString(" ")

    private fun windowRect(view: View): RectF {
        val at = IntArray(2).also(view::getLocationInWindow)
        return RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    /** The skip button's bounds, while it shows: its node is the polite live region. */
    private fun skipRect(activity: Activity): RectF? =
        overlayNodes(activity).singleOrNull { it.config.contains(SemanticsProperties.LiveRegion) && "Skip intro" in it.words() + subtree(it).flatMap { n -> n.words() } }
            ?.boundsInWindow?.let { RectF(it.left, it.top, it.right, it.bottom) }

    private fun labelView(activity: Activity) =
        views(activity.window.decorView).filterIsInstance<TextView>().singleOrNull { it.isShown && it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private companion object {
        const val INTRO_START_MS = 5_000L
    }
}

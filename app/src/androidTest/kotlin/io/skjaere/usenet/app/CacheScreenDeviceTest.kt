package io.skjaere.usenet.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.CacheScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CachePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Cache with a TV remote alone, and at twice the default font size, with the video cache
 * on (as it is by default). The cache settings are put back as they were afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CacheScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val settings = container.settings
    private lateinit var saved: AppSettings

    @Before
    fun setUp() = runBlocking<Unit> {
        saved = settings.settings.first()
        settings.restore(saved.copy(cache = CachePrefs()))
        withTimeout(10_000) { while (container.videoCache.usageNow()?.let { it.on && it.chosenBytes == CachePrefs().limitBytes } != true) delay(50) }
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        withTimeout(10_000) {
            while (container.videoCache.usageNow()?.let { it.on == saved.cache.keepVideo && it.chosenBytes == saved.cache.limitBytes } != true) delay(50)
        }
    }


    /**
     * The screen starts on its switch; down the screen, along the limits and back up to Back, every
     * control is reached. OK on a Clear that asks first opens its question, whose buttons the remote
     * reaches, and Cancel there leaves the files as they were.
     */
    @Test
    fun aRemoteReachesEveryControlFromTheSwitchItStartsOn() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { CacheScreen(onBack = {}) } }
        compose.waitUntil(5_000) { "Keep watched video on this device" in focused() }
        val seen = mutableListOf(focused())
        repeat(7) { seen += press(KeyEvent.KEYCODE_DPAD_DOWN) }
        // Up to the limits, then along them both ways.
        repeat(6) { if (!limitFocused()) seen += press(KeyEvent.KEYCODE_DPAD_UP) }
        repeat(4) { seen += press(KeyEvent.KEYCODE_DPAD_LEFT).takeIf { limitFocused() } ?: return@repeat }
        repeat(4) { seen += press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(6) { seen += press(KeyEvent.KEYCODE_DPAD_UP) }
        listOf(
            "Back", "Keep watched video on this device", "Clear the video cache", "1 GB", "2 GB", "5 GB", "10 GB",
            "Clear posters and images", "Clear TMDB metadata", "Clear the NZB files kept for resume", "Clear the releases found incomplete",
        ).forEach { words -> assertTrue("never focused: $words; focus went ${seen.joinToString(" → ")}", seen.any { words in it }) }

        val nzbFiles = container.store.storedFiles()
        focus(hasContentDescription("Clear the NZB files kept for resume"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Delete the NZB files kept for resume?")) }
        val inDialog = mutableListOf<String>()
        repeat(3) { inDialog += press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(3) { inDialog += press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(3) { inDialog += press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue(
            "the question's buttons weren't reached: ${inDialog.distinct()}",
            inDialog.any { "Cancel" in it.split(" + ") } && inDialog.any { "Delete" in it.split(" + ") },
        )
        focus(hasText("Cancel") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(hasText("Delete the NZB files kept for resume?")) }
        assertEquals(nzbFiles, container.store.storedFiles())
    }

    /** The question a Clear asks outlives a rotation, which re-creates the screen; Cancel then closes it. */
    @Test
    fun theClearQuestionOutlivesARotation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppTheme { CacheScreen(onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasContentDescription("Clear the video cache")) }
        compose.onNode(hasContentDescription("Clear the video cache")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Clear the video cache?")) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the question went with the rotation", exists(hasText("Clear the video cache?")))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(hasText("Clear the video cache?")) }
    }

    /** Each button is read out with the words it shows (label in name), so a voice saying "Clear" finds every Clear. */
    @Test
    fun eachButtonIsReadOutWithTheWordsItShows() {
        compose.setContent { AppTheme { CacheScreen(onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasText("10 GB")) && !exists(hasText("…")) }
        val named = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } to
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
            }
            .filter { (shown, spoken) -> shown.isNotEmpty() && spoken.isNotEmpty() }
        // The video's Clear and the four under Stored data.
        assertEquals("buttons with words and a name: $named", 5, named.size)
        named.forEach { (shown, spoken) -> assertTrue("'$shown' is read out as '$spoken'", spoken.contains(shown, ignoreCase = true)) }
    }

    private val controls = SemanticsMatcher("a control") { it.config.contains(SemanticsProperties.Role) && it.config.contains(SemanticsActions.OnClick) }

    /** At twice the default font size every control is a 48 dp target, the words whole, the last reachable by scrolling. */
    @Test
    fun at200PercentEveryControlIsA48dpTargetAndTheWordsAreWhole() {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) { AppTheme { CacheScreen(onBack = {}) } }
        }
        compose.waitUntil(5_000) { exists(hasText("10 GB")) && !exists(hasText("…")) }
        val count = compose.onAllNodes(controls).fetchSemanticsNodes().size
        // Back, the switch, the video's Clear, four limits, four Clears.
        assertEquals(11, count)
        (0 until count).forEach { i ->
            compose.onAllNodes(controls)[i].performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        listOf(
            "Keep watched video on this device", "10 GB", "NZB files kept for resume", "Resume fetches them from the indexer again, one grab each.",
            "Releases found incomplete", "Plays skip them without a grab for 30 days, or until you add a provider they weren't checked on.",
            "Android may delete cached video, posters and metadata when the device runs low on storage.",
        ).forEach { words ->
            val node = compose.onAllNodesWithText(words, useUnmergedTree = true)[0]
            node.performScrollTo()
            val results = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            // Not the width: centred text is laid out across the whole width, wider than it draws.
            assertFalse("'$words' is cut", layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")

    /** What has the focus; with a dialog up, the screen under it may keep a focused node too: each, joined by " + ". */
    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.words() }.ifEmpty { listOf("(nothing)") }.joinToString(" + ")

    private fun limitFocused() = CachePrefs.LIMITS_GB.any { gb -> "$gb GB" in focused().split(" + ") }

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    /** Focuses [matcher]'s node with the remote's keys alone: down, else up, until it has the focus. */
    private fun focus(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { exists(matcher) }
        repeat(12) { if (!exists(matcher and isFocused())) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(12) { if (!exists(matcher and isFocused())) press(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("couldn't focus $matcher; the focus is on ${focused()}", exists(matcher and isFocused()))
    }
}

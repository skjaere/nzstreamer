package io.skjaere.usenet.app

import android.content.ClipboardManager
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.CrashLogScreen
import io.skjaere.usenet.app.settings.HUB_FOOTNOTE
import io.skjaere.usenet.app.settings.LogsScreen
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.LogBuffer
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
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Settings › Logs and the log of the last crash: the lines logged, the filters, Detailed logging,
 * Copy and Share, with a TV remote alone and at twice the default font size. The settings, and any
 * crash log the device had, are put back as they were afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LogsScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val settings = container.settings
    private val log = LoggerFactory.getLogger("io.skjaere.usenet.app.LogsProbe")
    private val crashFile = File(context.filesDir, "logs/last-crash.txt")
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private var savedCrash: ByteArray? = null

    /** Words no other line holds, so this run's lines are told apart. */
    private val mark = UUID.randomUUID().toString().take(8)

    @Before
    fun setUp() = runBlocking<Unit> {
        saved = settings.settings.first()
        savedCrash = crashFile.takeIf { it.isFile }?.readBytes()
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        home.restore()
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        val detailed = saved.logs.isDetailed(BuildConfig.DEBUG)
        withTimeout(10_000) { while (AndroidLogConfig.detailed != detailed) delay(50) }
        savedCrash?.let { bytes -> crashFile.parentFile?.mkdirs(); crashFile.writeBytes(bytes) } ?: crashFile.delete()
    }

    @Test
    fun theHubOpensLogs() {
        var opened = false
        compose.setContent { AppTheme { SettingsScreen({}, {}, {}, {}, {}, {}, onLogs = { opened = true }) } }
        compose.onNode(hasText("Logs") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
        assertTrue(exists(hasText(HUB_FOOTNOTE)))
    }

    /**
     * A line logged shows; Warnings and errors leaves the others out, the text filter (named for
     * TalkBack while it holds text) all but the lines that hold it; Copy takes the lines shown, under
     * the app and device; the phone offers Share.
     */
    @Test
    fun aLineLoggedShowsAndTheFiltersNarrowTheLines() = runBlocking<Unit> {
        log.info("probe {} plain line", mark)
        log.warn("probe {} warning line", mark)
        log.info("other {} line with a key: https://indexer.example.org/api?t=caps&apikey=0123abcd", mark)
        compose.setContent { AppTheme { LogsScreen(onBack = {}, onCrashLog = {}) } }
        val plain = hasText("probe $mark plain line", substring = true)
        val warning = hasText("probe $mark warning line", substring = true)
        val keyed = hasText("other $mark line", substring = true)
        compose.waitUntil(5_000) { exists(plain) && exists(warning) && exists(keyed) }
        assertTrue("the key reached the screen", exists(hasText("apikey=<redacted>", substring = true)))
        assertFalse(exists(hasText("0123abcd", substring = true)))
        assertTrue("Share isn't offered", exists(hasContentDescription("Share the log")))
        assertTrue("what a shared log tells isn't said", exists(hasText(contentsNote)))

        compose.onNode(hasText("Warnings and errors") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(plain) }
        assertTrue(exists(warning))

        compose.onNode(hasText("All") and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("probe $mark")
        compose.waitUntil(5_000) { exists(plain) && !exists(keyed) }
        assertTrue(exists(warning))
        // The placeholder goes as the text comes; what the field is for is still said.
        assertTrue("the filter isn't named once it holds text", exists(hasSetTextAction() and hasContentDescription("Filter the lines")))
        assertTrue(exists(hasText("Copy and Share take the 2 lines shown.")))

        compose.onNode(hasContentDescription("Copy the log")).performClick()
        var copied = ""
        compose.waitUntil(5_000) {
            instrumentation.runOnMainSync {
                copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            "probe $mark plain line" in copied
        }
        assertTrue(copied, copied.startsWith("NZStreamer ${BuildConfig.VERSION_NAME}"))
        // Under the device, its memory, which decides how much a play holds.
        assertTrue(copied, copied.lines()[2].startsWith("Memory: "))
        assertTrue(copied, "W LogsProbe: probe $mark warning line" in copied)
        assertFalse(copied, "other $mark" in copied)
    }

    /** With Detailed logging off a debug line is left out; turned on, the next one shows. */
    @Test
    fun detailedLoggingAddsDebugLines() = runBlocking<Unit> {
        settings.updateLogs { it.copy(detailed = false) }
        withTimeout(10_000) { while (AndroidLogConfig.detailed) delay(50) }
        log.debug("probe {} quiet debug line", mark)
        assertFalse(LogBuffer.snapshot().any { mark in it.message })

        compose.setContent { AppTheme { LogsScreen(onBack = {}, onCrashLog = {}) } }
        compose.waitUntil(5_000) { exists(hasText("Detailed logging")) }
        compose.onNode(hasText("Detailed logging") and hasClickAction()).performClick()
        withTimeout(10_000) { while (!AndroidLogConfig.detailed) delay(50) }
        assertEquals(true, settings.logs.first().detailed)
        log.debug("probe {} detailed debug line", mark)
        compose.waitUntil(5_000) { exists(hasText("probe $mark detailed debug line", substring = true)) }
        assertFalse(exists(hasText("probe $mark quiet debug line", substring = true)))
    }

    /**
     * The screen starts on its switch; down through the crash log's row, the choices, the filter and
     * the actions, into the lines a line at a time, and back up to Back, every control is reached.
     */
    @Test
    fun aRemoteReachesEveryControlAndMovesThroughTheLines() = runBlocking<Unit> {
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        // Few lines above the ones the remote moves through, so it is back up at Back in a few presses.
        LogBuffer.clear()
        repeat(30) { log.info("probe {} line {}", mark, it) }
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { LogsScreen(onBack = {}, onCrashLog = {}) } }
        compose.waitUntil(5_000) { "Detailed logging" in focused() }
        // The newest line shows, though the crash log's row came after the lines and took room from them.
        compose.waitUntil(5_000) { exists(hasText("Log of the last crash")) && exists(hasText("probe $mark line 29", substring = true)) }
        val seen = mutableListOf(focused())
        // The crash log's row, the choices both ways, the filter.
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT).forEach { seen += press(it) }
        seen += press(KeyEvent.KEYCODE_DPAD_DOWN)
        val fieldReached = exists(hasSetTextAction() and isFocused())
        // The actions, along to the last, then back along them and on again.
        seen += press(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(2) { if ("Clear the log" !in parts(focused())) seen += press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(2) { seen += press(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(2) { seen += press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // Into the lines, a line at a time.
        val lines = (1..4).map { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        seen += lines
        repeat(80) { if ("Back" !in parts(focused())) seen += press(KeyEvent.KEYCODE_DPAD_UP) }
        listOf(
            "Back", "Detailed logging", "Log of the last crash", "All", "Warnings and errors",
            "Copy the log", "Share the log", "Clear the log",
        ).forEach { words -> assertTrue("never focused: $words; focus went ${seen.joinToString(" → ")}", seen.any { words in parts(it) }) }
        assertTrue("the filter was never focused: ${seen.joinToString(" → ")}", fieldReached)
        val line = Regex("""^(Trace|Debug|Info|Warning|Error), \d\d:\d\d:\d\d, """)
        assertTrue("the lines weren't moved through a line at a time: $lines", lines.distinct().size == 4 && lines.all { line.containsMatchIn(it) })
    }

    /**
     * With a full log a remote moves through the lines one at a time, up to thousands of presses from
     * the controls: Back from a line takes it to Copy, and Back there leaves the screen as ever.
     */
    @Test
    fun backFromTheLinesReturnsToTheControlsHoweverLongTheLog() = runBlocking<Unit> {
        LogBuffer.clear()
        repeat(LogBuffer.CAPACITY) { log.info("probe {} line {}", mark, it) }
        var left = 0
        instrumentation.setInTouchMode(false)
        compose.setContent {
            AppTheme {
                // Stands in for the app's navigation, which takes the Back the screen leaves.
                BackHandler { left++ }
                LogsScreen(onBack = { left++ }, onCrashLog = {})
            }
        }
        compose.waitUntil(5_000) { "Detailed logging" in focused() }
        compose.waitUntil(5_000) { exists(hasText("probe $mark line ${LogBuffer.CAPACITY - 1}", substring = true)) }
        // Down past the controls into the lines, then up a few older ones.
        repeat(10) { if (!exists(aLineFocused)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("no line has the focus: ${focused()}", exists(aLineFocused))

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back from a line left the screen", 0, left)
        assertTrue("Back from a line went to ${focused()}", "Copy the log" in parts(focused()))
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back from Copy didn't leave the screen", 1, left)
    }

    /** On the last crash's log too, Back from a line takes a remote to the actions above. */
    @Test
    fun backFromTheCrashLogsLinesReturnsToItsActions() = runBlocking<Unit> {
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        var left = 0
        instrumentation.setInTouchMode(false)
        compose.setContent {
            AppTheme {
                BackHandler { left++ }
                CrashLogScreen(onBack = { left++ })
            }
        }
        compose.waitUntil(5_000) { "Share the log of the last crash" in parts(focused()) }
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("no line has the focus: ${focused()}", exists(aLineFocused))

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back from a line left the screen", 0, left)
        assertTrue("Back from a line went to ${focused()}", "Share the log of the last crash" in parts(focused()))
    }

    /**
     * Back from the last crash's log, a remote is on its row again, not on the switch above it, which
     * OK would turn (S11-16); once the log is deleted there, and its row gone, on the switch.
     */
    @Test
    fun backFromTheCrashLogTheRemoteIsWhereItWas() = runBlocking<Unit> {
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        home.show()
        compose.setContent { AppTheme { AppNav() } }
        compose.waitUntil(5_000) { exists(hasContentDescription("Settings")) }
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.onNode(hasText("Logs") and hasClickAction()).performScrollTo().performClick()
        val crashRow = hasText("Log of the last crash") and hasClickAction()
        compose.waitUntil(5_000) { exists(crashRow) }
        instrumentation.setInTouchMode(false)

        fun openTheCrashLog() {
            compose.onNode(crashRow).requestFocus()
            compose.waitUntil(5_000) { exists(crashRow and isFocused()) }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { exists(hasContentDescription("Delete the log of the last crash")) }
        }

        /** Whether the focus, back on Logs, comes to [words]; what it came to instead is said. */
        fun backTo(words: String) {
            press(KeyEvent.KEYCODE_BACK)
            val came = runCatching { compose.waitUntil(5_000) { words in parts(focused()) } }
            assertTrue("Back from the crash log went to ${focused()}, not $words", came.isSuccess)
        }

        openTheCrashLog()
        backTo("Log of the last crash")

        openTheCrashLog()
        compose.onNode(hasContentDescription("Delete the log of the last crash")).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())) }
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("No crash log is kept.")) }
        backTo("Detailed logging")
        assertFalse(exists(crashRow))
    }

    /** A line of a log, inside its list, has the focus. */
    private val aLineFocused = isFocused() and hasAnyAncestor(hasScrollToIndexAction())

    /** The log of the last crash: its lines, Share, and Delete after a question, which leaves none. */
    @Test
    fun theLastCrashsLogShowsAndIsDeleted() = runBlocking<Unit> {
        log.info("probe {} before the crash", mark)
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        compose.setContent { AppTheme { CrashLogScreen(onBack = {}) } }
        // What crashed first, under the app and device; then the log before it.
        compose.waitUntil(5_000) { exists(hasText("java.lang.IllegalStateException: probe $mark crash")) }
        assertTrue(exists(hasText("NZStreamer ${BuildConfig.VERSION_NAME}", substring = true)))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("probe $mark before the crash", substring = true))
        assertTrue(exists(hasContentDescription("Share the log of the last crash")))
        assertTrue("what a shared log tells isn't said", exists(hasText(contentsNote)))
        compose.onNode(hasContentDescription("Delete the log of the last crash")).performClick()
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { exists(hasText("No crash log is kept.")) }
        assertFalse(crashFile.exists())
    }

    /** The question before the crash log's Delete outlives a rotation, which re-creates the screen; Cancel keeps the log. */
    @Test
    fun theDeleteQuestionOutlivesARotation() = runBlocking<Unit> {
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppTheme { CrashLogScreen(onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasContentDescription("Delete the log of the last crash")) }
        compose.onNode(hasContentDescription("Delete the log of the last crash")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Delete the log of the last crash?")) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the question went with the rotation", exists(hasText("Delete the log of the last crash?")))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(hasText("Delete the log of the last crash?")) }
        assertTrue(crashFile.exists())
    }

    /** Said beside Copy and Share, and on the last crash's log: a log names what was searched for and played. */
    private val contentsNote = "The log names what you searched for and played, and your providers' and indexers' addresses. " +
        "It never holds passwords or keys."

    private val controls = SemanticsMatcher("a control") { it.config.contains(SemanticsProperties.Role) && it.config.contains(SemanticsActions.OnClick) }

    /** At twice the default font size every control is a 48 dp target, the words whole, each reachable by scrolling. */
    @Test
    fun at200PercentEveryControlIsA48dpTargetAndTheWordsAreWhole() = runBlocking<Unit> {
        container.crashLog.record(Thread.currentThread(), IllegalStateException("probe $mark crash"))
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) { AppTheme { LogsScreen(onBack = {}, onCrashLog = {}) } }
        }
        compose.waitUntil(5_000) { exists(hasText("Log of the last crash")) && exists(hasContentDescription("Share the log")) }
        val count = compose.onAllNodes(controls).fetchSemanticsNodes().size
        // Back, the switch, the crash log's row, two choices, Copy, Share and Clear.
        assertEquals(8, count)
        (0 until count).forEach { i ->
            val control = compose.onAllNodes(controls)[i]
            // Back stays where it is; the rest scroll.
            if (control.fetchSemanticsNode().parentScrolls()) control.performScrollTo()
            control.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        listOf("Detailed logging", "Keeps debug lines too, for tracking down a problem.", "Log of the last crash", "Warnings and errors").forEach { words ->
            val node = compose.onAllNodesWithText(words, useUnmergedTree = true)[0]
            node.performScrollTo()
            val results = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertFalse("'$words' is cut", layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    private fun SemanticsNode.parentScrolls(): Boolean =
        generateSequence(parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")

    /** What [focused] said of each node, each of its words. */
    private fun parts(focus: String): List<String> = focus.split(" + ").flatMap { it.split(" | ") }

    /** What has the focus; each, joined by " + ", should more than one node say so. */
    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.words() }.ifEmpty { listOf("(nothing)") }.joinToString(" + ")

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }
}
